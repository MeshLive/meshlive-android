package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors

/**
 * "Share app nearby" screen ([OfflineAppShareFeature]; the Help entry follows the Settings > Features switch). Starts a Wi-Fi
 * Direct group and [OfflineAppShareServer] when opened; stops both after
 * [OfflineAppSharePolicy.SHARE_TIMEOUT_MS], when the user leaves the screen, or when a live session
 * starts ([OfflineAppShareGate]). Nothing is stored: name, passphrase and URL token are new each time.
 */
class OfflineAppShareActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "app-share-nearby").apply { isDaemon = true } }

    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var countdownText: TextView
    private lateinit var wifiQr: ImageView
    private lateinit var urlQr: ImageView
    private lateinit var wifiCaption: TextView
    private lateinit var urlCaption: TextView
    private lateinit var actionButton: MaterialButton

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var server: OfflineAppShareServer? = null
    private val groupLifecycle = NearbyGroupLifecycle()
    private var stopped = false
    private var sharingSinceMs = 0L
    private var attempts: List<GroupBandAttempt> = emptyList()
    private var attemptIndex = 0
    private var busyRetries = 0
    private var groupInfoTries = 0
    /** Wi-Fi or location was off and the user was sent to Settings: check again on return. */
    private var recheckOnResume = false
    private val networkName = OfflineAppSharePolicy.randomNetworkName()
    private val passphrase = OfflineAppSharePolicy.randomPassphrase()
    private val token = OfflineAppSharePolicy.randomToken()

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) checkAndStart() else showStopped(getString(R.string.share_nearby_permission_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (!OfflineAppShareFeature.enabled) {
            showStopped(getString(R.string.share_nearby_disabled))
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkAndStart()
    }

    override fun onResume() {
        super.onResume()
        if (recheckOnResume && server == null && manager == null) {
            recheckOnResume = false
            stopped = false
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            checkAndStart()
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen ends sharing (the group and the server never outlive the visible screen).
        if (!isChangingConfigurations && !stopped) {
            stopSharing("left_screen", null)
            finish()
        }
    }

    override fun onDestroy() {
        stopSharing("destroyed", null) // clears foreignDirectGroup once the group is gone (cleanGroup) [A4 review P3-2]
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- Preconditions -----------------------------------------------------------------------------

    private fun requiredPermissions(): List<String> = OfflineAppSharePolicy.permissionsFor(Build.VERSION.SDK_INT)

    private fun checkAndStart() {
        if (stopped) return
        if (OfflineAppShareGate.liveSessionActive()) {
            showStopped(OfflineAppSharePolicy.BUSY_MESSAGE)
            return
        }
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            statusText.setText(R.string.share_nearby_permission_needed)
            permissionRequest.launch(missing.toTypedArray())
            return
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (wifi == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            showStopped(getString(R.string.share_nearby_unsupported))
            return
        }
        if (!wifi.isWifiEnabled) {
            showStopped(getString(R.string.share_nearby_wifi_off))
            actionButton.setText(R.string.share_nearby_open_wifi)
            actionButton.setOnClickListener {
                recheckOnResume = true
                runCatching { startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            }
            return
        }
        // Android 10-12 Wi-Fi Direct checks location mode as well as the permission (AOSP
        // WifiPermissionsUtil.checkCanAccessWifiDirect); Android 13+ uses NEARBY_WIFI_DEVICES instead.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val location = getSystemService(LocationManager::class.java)
            if (location != null && !location.isLocationEnabled) {
                showStopped(getString(R.string.share_nearby_location_off))
                actionButton.setText(R.string.share_nearby_open_location)
                actionButton.setOnClickListener {
                    recheckOnResume = true
                    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
                return
            }
        }
        statusText.setText(R.string.share_nearby_checking)
        val setupDeadline = SystemClock.elapsedRealtime() + 60_000L
        fun watchSetup() {
            if (stopped || server != null) return
            if (OfflineAppShareGate.liveSessionActive()) stopSharing("live_session", OfflineAppSharePolicy.BUSY_MESSAGE)
            else if (SystemClock.elapsedRealtime() >= setupDeadline) stopSharing("setup_timeout", getString(R.string.share_nearby_no_group))
            else handler.postDelayed({ watchSetup() }, OfflineAppSharePolicy.BUSY_CHECK_INTERVAL_MS)
        }
        watchSetup()
        val context = applicationContext
        worker.execute {
            val info = runCatching { AppShare.apkInfo(context) }.getOrNull()
            runOnUiThread {
                if (stopped || isDestroyed) return@runOnUiThread
                when {
                    info == null -> showStopped(AppSharePolicy.REFUSE_UNREADABLE)
                    info.refusal != null -> showStopped(info.refusal)
                    else -> startGroup(info, wifi.is5GHzBandSupported)
                }
            }
        }
    }

    // --- Wi-Fi Direct group ------------------------------------------------------------------------

    private var apkInfo: AppApkInfo? = null
    private var shareLease: DiscoveryPauseLease? = null
    private var shareReserved = false

    @SuppressLint("MissingPermission")
    private fun startGroup(info: AppApkInfo, supports5Ghz: Boolean) {
        apkInfo = info
        val lease = WifiRadio.pauseDiscoveryLease("share") // Wider reach's discovery holds the Aware attach: pause it first [M-10]
        shareLease = lease
        WifiRadio.scheduler.post { // FIFO behind the pause; never awaitPaused on the main thread
            val reserved = WifiRadio.ledger.reserveDirect("share") == DirectReserve.OK
            runOnUiThread {
                when {
                    stopped || isDestroyed -> {
                        if (reserved) WifiRadio.ledger.releaseDirect("share")
                        lease.resume()
                    }
                    !reserved -> {
                        lease.resume()
                        shareLease = null
                        showStopped(OfflineAppSharePolicy.BUSY_MESSAGE)
                    }
                    else -> {
                        shareReserved = true
                        beginGroup(info, supports5Ghz)
                    }
                }
            }
        }
    }

    /** Release THEN resume; both idempotent. The group (if any) stays claimed by [foreignDirectGroup] until it is gone. */
    private fun releaseShareClaim() {
        if (shareReserved) {
            shareReserved = false
            WifiRadio.ledger.releaseDirect("share")
        }
        shareLease?.resume()
        shareLease = null
    }

    @SuppressLint("MissingPermission")
    private fun beginGroup(info: AppApkInfo, supports5Ghz: Boolean) {
        val p2p = getSystemService(WifiP2pManager::class.java)
        if (p2p == null) {
            showStopped(getString(R.string.share_nearby_unsupported))
            return
        }
        manager = p2p
        channel = runCatching {
            p2p.initialize(this, mainLooper) {
                if (!stopped) stopSharing("channel_lost", getString(R.string.share_nearby_channel_lost))
            }
        }.getOrNull()
        if (channel == null) {
            stopSharing("channel_failed", getString(R.string.share_nearby_channel_lost))
            return
        }
        attempts = OfflineAppSharePolicy.bandAttempts(supports5Ghz)
        attemptIndex = 0
        busyRetries = 0
        statusText.setText(R.string.share_nearby_starting)
        log("starting | supports5ghz=$supports5Ghz | attempts=${attempts.joinToString(",") { it.name.lowercase() }}")
        // An existing group may belong to another app. Never tear it down to make room.
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { group ->
                if (stopped) return@requestGroupInfo
                if (group != null) stopSharing("existing_group", "Wi-Fi Direct is already in use. Stop the existing sharing session, then try again.")
                else createGroup()
            }
        } catch (error: SecurityException) {
            stopSharing("security", getString(R.string.share_nearby_permission_denied))
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (stopped) return
        if (OfflineAppShareGate.liveSessionActive()) {
            stopSharing("live_session", OfflineAppSharePolicy.BUSY_MESSAGE)
            return
        }
        val p2p = manager ?: return
        val ch = channel ?: return
        val attempt = attempts[attemptIndex]
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                cleanGroup(groupLifecycle.created(), p2p, ch)
                if (stopped) return
                WifiRadio.ledger.foreignDirectGroup = true
                log("group_created | attempt=${attempt.name.lowercase()}")
                groupInfoTries = 0
                handler.postDelayed({ readGroupInfo() }, 300L)
            }

            override fun onFailure(reason: Int) {
                cleanGroup(groupLifecycle.failed(), p2p, ch)
                if (stopped) return
                log("group_failed | attempt=${attempt.name.lowercase()} | reason=$reason")
                when (val step = OfflineAppSharePolicy.onCreateGroupFailure(reason, attemptIndex, attempts.size, busyRetries)) {
                    is OfflineAppSharePolicy.FailureStep.Retry -> {
                        busyRetries++
                        handler.postDelayed({ createGroup() }, step.delayMs)
                    }
                    OfflineAppSharePolicy.FailureStep.NextAttempt -> {
                        attemptIndex++
                        busyRetries = 0
                        createGroup()
                    }
                    is OfflineAppSharePolicy.FailureStep.GiveUp -> stopSharing("group_failed", step.reason)
                }
            }
        }
        groupLifecycle.creating()
        try {
            // Always a temporary group with our own random name and passphrase; the no-config createGroup
            // overload creates a persistent group and is never called (see GroupBandAttempt).
            val band = when (attempt) {
                GroupBandAttempt.BAND_5GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                GroupBandAttempt.BAND_AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            }
            val config = WifiP2pConfig.Builder()
                .setNetworkName(networkName)
                .setPassphrase(passphrase)
                .setGroupOperatingBand(band)
                .enablePersistentMode(false)
                .build()
            p2p.createGroup(ch, config, listener)
        } catch (error: SecurityException) {
            cleanGroup(groupLifecycle.failed(), p2p, ch)
            stopSharing("security", getString(R.string.share_nearby_permission_denied))
        } catch (error: IllegalArgumentException) {
            // A builder rejection (name/passphrase/band) moves on to the next attempt.
            listener.onFailure(OfflineAppSharePolicy.P2P_ERROR)
        } catch (error: UnsupportedOperationException) {
            listener.onFailure(OfflineAppSharePolicy.P2P_ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readGroupInfo() {
        if (stopped) return
        val p2p = manager ?: return
        val ch = channel ?: return
        try {
            p2p.requestGroupInfo(ch) { group -> onGroupInfo(group) }
        } catch (error: SecurityException) {
            stopSharing("security", getString(R.string.share_nearby_permission_denied))
        }
    }

    private fun onGroupInfo(group: WifiP2pGroup?) {
        if (stopped) return
        val ssid = group?.networkName
        val pass = group?.passphrase
        val ifaceName = group?.`interface`
        val local = ifaceName?.let(::groupAddress)
        if (group == null || !group.isGroupOwner || ssid.isNullOrEmpty() || pass.isNullOrEmpty() || local == null) {
            if (++groupInfoTries < 20) {
                handler.postDelayed({ readGroupInfo() }, 500L)
            } else {
                stopSharing("no_group_info", getString(R.string.share_nearby_no_group))
            }
            return
        }
        val frequency = group.frequency
        startServer(ssid, pass, local.first, local.second, frequency)
    }

    /** The group interface's IPv4 address and prefix length, or null while it is not up yet. */
    private fun groupAddress(interfaceName: String): Pair<InetAddress, Int>? = runCatching {
        NetworkInterface.getByName(interfaceName)?.interfaceAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.let { it.address to it.networkPrefixLength.toInt() }
    }.getOrNull()

    // --- Server and UI -----------------------------------------------------------------------------

    private fun startServer(ssid: String, pass: String, address: InetAddress, prefix: Int, frequency: Int) {
        val info = apkInfo ?: return
        val apkFileName = AppSharePolicy.shareFileName(info.versionName, info.versionCode)
        val page = OfflineAppSharePolicy.pageHtml(
            OfflineAppSharePolicy.PageInfo(
                appName = getString(R.string.app_name),
                versionName = info.versionName,
                versionCode = info.versionCode,
                sizeBytes = info.sizeBytes,
                apkFileName = apkFileName,
                apkSha256 = info.apkSha256,
                signingCertSha256 = info.signingCertSha256,
                matchesPublishedReleaseKey = info.matchesPublishedReleaseKey,
                debugBuild = info.debugBuild,
            ),
        )
        val apk = File(applicationInfo.sourceDir)
        val qrSize = (240 * resources.displayMetrics.density).toInt()
        worker.execute {
            val started = runCatching {
                OfflineAppShareServer(
                    bindAddress = address,
                    token = token,
                    apkFile = apk,
                    apkFileName = apkFileName,
                    pageHtml = page,
                    allowRemote = { remote -> OfflineAppSharePolicy.sameSubnet(address, prefix, remote) },
                    onEvent = { event -> log("server | event=$event") },
                ).also { it.start() }
            }
            val created = started.getOrNull()
            val url = created?.let { OfflineAppSharePolicy.pageUrl(address.hostAddress ?: "", it.port, token) }
            val wifiPayload = OfflineAppSharePolicy.wifiQrPayload(ssid, pass)
            val wifiBitmap = qr(wifiPayload, qrSize)
            val urlBitmap = url?.let { qr(it, qrSize) }
            runOnUiThread {
                if (stopped || isDestroyed || created == null || url == null) {
                    created?.close()
                    if (!stopped && created == null) {
                        stopSharing("server_failed", getString(R.string.share_nearby_server_failed))
                    }
                    return@runOnUiThread
                }
                server = created
                showSharing(ssid, pass, url, frequency, info, wifiBitmap, urlBitmap)
            }
        }
    }

    private fun qr(payload: String, size: Int): Bitmap? =
        runCatching { BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, size, size) }.getOrNull()

    private fun showSharing(
        ssid: String,
        pass: String,
        url: String,
        frequency: Int,
        info: AppApkInfo,
        wifiBitmap: Bitmap?,
        urlBitmap: Bitmap?,
    ) {
        sharingSinceMs = SystemClock.elapsedRealtime()
        val band = OfflineAppSharePolicy.bandLabel(frequency)
        log("sharing | band=$band | frequency=$frequency")
        statusText.text = if (OfflineAppSharePolicy.is24Ghz(frequency)) {
            getString(R.string.share_nearby_sharing_24ghz)
        } else {
            getString(R.string.share_nearby_sharing)
        }
        wifiQr.setImageBitmap(wifiBitmap)
        urlQr.setImageBitmap(urlBitmap)
        wifiCaption.text = getString(R.string.share_nearby_step_wifi, ssid, pass)
        urlCaption.text = getString(R.string.share_nearby_step_url, url)
        listOf(wifiQr, urlQr, wifiCaption, urlCaption, countdownText).forEach { it.visibility = View.VISIBLE }
        detailText.text = getString(
            R.string.share_nearby_details,
            band,
            info.apkSha256,
            info.signingCertSha256.joinToString("\n").ifEmpty { "unknown" },
        ) + "\n\n" + deviceNameNotice()
        detailText.visibility = View.VISIBLE
        actionButton.setText(R.string.share_nearby_stop)
        actionButton.setOnClickListener { stopSharing("user_stop", getString(R.string.share_nearby_stopped)) }
        tick()
    }

    /**
     * Wi-Fi Direct announces the phone's own device name (the group's probe responses carry it). An app cannot
     * change it (WifiP2pManager.setDeviceName is a system API that needs NETWORK_SETTINGS), so the screen says
     * what is visible and where to change it. The group name and password are random.
     */
    private fun deviceNameNotice(): String {
        val name = runCatching { Settings.Global.getString(contentResolver, "device_name") }.getOrNull()
        return if (name.isNullOrBlank()) {
            getString(R.string.share_nearby_device_name_unknown)
        } else {
            getString(R.string.share_nearby_device_name, name)
        }
    }

    private fun tick() {
        if (stopped) return
        if (OfflineAppShareGate.liveSessionActive()) {
            stopSharing("live_session", OfflineAppSharePolicy.BUSY_MESSAGE)
            return
        }
        val remaining = OfflineAppSharePolicy.SHARE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - sharingSinceMs)
        if (remaining <= 0L) {
            stopSharing("timeout", getString(R.string.share_nearby_timeout))
            return
        }
        val downloads = server?.apkRequests?.get() ?: 0
        countdownText.text = getString(
            R.string.share_nearby_countdown,
            OfflineAppSharePolicy.minutesSecondsLeft(remaining),
            downloads,
        )
        handler.postDelayed({ tick() }, 1_000L)
    }

    /** Idempotent: closes the server, removes the group and releases the channel. */
    @SuppressLint("MissingPermission")
    private fun stopSharing(reason: String, message: String?) {
        if (stopped) return
        stopped = true
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val downloads = server?.apkRequests?.get() ?: 0
        server?.close()
        server = null
        val p2p = manager
        val ch = channel
        val cleanup = groupLifecycle.stop()
        // The group (if any) stays claimed until removeGroup confirms it is gone, so an Aware attach never starts
        // against a group that still exists (its failure would count as a local fault). [A4 review P3-2]
        if (cleanup != NearbyGroupLifecycle.Cleanup.REMOVE_GROUP || p2p == null || ch == null) {
            WifiRadio.ledger.foreignDirectGroup = false
        }
        if (p2p != null && ch != null) cleanGroup(cleanup, p2p, ch)
        releaseShareClaim()
        manager = null
        channel = null
        log("stopped | reason=$reason | apk_requests=$downloads")
        if (message != null && !isDestroyed) showStopped(message)
    }

    @SuppressLint("MissingPermission")
    private fun cleanGroup(cleanup: NearbyGroupLifecycle.Cleanup, p2p: WifiP2pManager, ch: WifiP2pManager.Channel) {
        when (cleanup) {
            NearbyGroupLifecycle.Cleanup.NONE -> Unit
            NearbyGroupLifecycle.Cleanup.CLOSE_CHANNEL -> runCatching { ch.close() }
            NearbyGroupLifecycle.Cleanup.REMOVE_GROUP -> runCatching {
                p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        WifiRadio.ledger.foreignDirectGroup = false // the group is gone [A4 review P3-2]
                        runCatching { ch.close() }
                    }
                    override fun onFailure(reason: Int) {
                        log("group_remove_failed | reason=$reason")
                        runCatching { ch.close() }
                        if (!isDestroyed && !isFinishing) {
                            showStopped("Sharing stopped, but Android could not confirm Wi-Fi Direct closed. Check Wi-Fi Settings.")
                        }
                    }
                })
            }.onFailure {
                log("group_remove_failed | error=${it.javaClass.simpleName}")
                runCatching { ch.close() }
                if (!isDestroyed && !isFinishing) {
                    showStopped("Sharing stopped, but Android could not confirm Wi-Fi Direct closed. Check Wi-Fi Settings.")
                }
            }
        }
    }

    private fun showStopped(message: String) {
        stopped = true
        releaseShareClaim()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusText.text = message
        listOf(wifiQr, urlQr, wifiCaption, urlCaption, countdownText, detailText).forEach { it.visibility = View.GONE }
        actionButton.setText(R.string.share_nearby_close)
        actionButton.setOnClickListener { finish() }
    }

    private fun log(detail: String) {
        if (!BuildConfig.DEBUG) return
        Log.i("BluetoothVideo", "BENCHMARK offline_app_share: $detail")
    }

    private fun buildUi() {
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(sizeSp: Float, selectable: Boolean = false) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextIsSelectable(selectable)
            setPadding(0, dp(8), 0, dp(8))
        }
        fun qrView() = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setBackgroundColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
        }
        column.addView(text(22f).apply { setText(R.string.share_nearby_title) })
        statusText = text(16f).also(column::addView)
        wifiCaption = text(15f, selectable = true).apply { visibility = View.GONE }.also(column::addView)
        wifiQr = qrView().also(column::addView)
        urlCaption = text(15f, selectable = true).apply { visibility = View.GONE }.also(column::addView)
        urlQr = qrView().also(column::addView)
        countdownText = text(15f).apply { visibility = View.GONE }.also(column::addView)
        detailText = text(13f, selectable = true).apply { visibility = View.GONE }.also(column::addView)
        actionButton = MaterialButton(this).apply {
            setText(R.string.share_nearby_close)
            setOnClickListener { finish() }
        }
        column.addView(actionButton)
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context) = Intent(context, OfflineAppShareActivity::class.java)

        /** Toast shown by the caller when the feature is off or a live session runs. */
        fun startOrExplain(context: Context) {
            if (!OfflineAppShareFeature.availableInThisBuild) return
            if (OfflineAppShareGate.liveSessionActive()) {
                Toast.makeText(context, OfflineAppSharePolicy.BUSY_MESSAGE, Toast.LENGTH_LONG).show()
                return
            }
            context.startActivity(intent(context))
        }
    }
}
