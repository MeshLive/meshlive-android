package com.example.bluetoothtest

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The Wi-Fi radio's single scheduler abstraction: the `meshlive-wifi` handler in production, a manual queue in
 * tests. [WifiScheduler.isWifiThread] never creates the thread.
 */
internal interface WifiScheduler {
    fun post(block: () -> Unit)
    fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit)
    fun cancel(tag: Any)
    fun nowMs(): Long // elapsedRealtime
    fun isWifiThread(): Boolean
}

/** What still exists after a teardown; [clear] is the clean-shutdown gate. */
internal data class WifiResidue(
    val awareAttach: Int,
    val awareNetworks: Int,
    val wifiSockets: Int,
    val directGroups: Int,
) {
    val clear: Boolean get() = awareAttach == 0 && awareNetworks == 0 && wifiSockets == 0 && directGroups == 0
}

/**
 * The Wi-Fi radio of the process: one lazily started `meshlive-wifi` HandlerThread (never quit), the attach
 * coordinator, the phone-wide ledger, the discovery pause gate and the shutdown/wipe teardown. Nothing starts a
 * thread, registers a receiver or touches the platform at [attach]; a process with every Wi-Fi switch off pays no
 * cost at all (X4, [Am 2c]).
 */
internal object WifiRadio {
    const val THREAD_NAME = "meshlive-wifi"

    @Volatile private var appContext: Context? = null
    private val startLock = Any()
    @Volatile private var handlerRef: Handler? = null
    private val holdIds = AtomicLong(0)
    val ledger = WifiLedger(SystemClock::elapsedRealtime)
    private val liveLinks: MutableSet<AwareLink> = Collections.newSetFromMap(ConcurrentHashMap())
    private val shutdownCloseables: MutableSet<java.io.Closeable> = Collections.newSetFromMap(ConcurrentHashMap()) // A9 registers its Direct driver [GAP+]
    @Volatile private var platformReader: AndroidWifiPlatformReader? = null
    @Volatile private var factsReader: WifiFactsReader? = null
    @Volatile var switchSource: WifiSwitchSource = DefaultWifiSwitches // A6 installs the FeatureCatalog source with one assignment [GAP+][R M-7]

    fun attach(app: Context) {
        appContext = app.applicationContext
        // NO WifiCoexistence.attach here [Am 2b]: the platform reader reads the band with WifiCoexistence.connectedFrequencyMhz(context).
        // This also builds platformReader and factsReader; it starts no thread.
        if (platformReader == null) {
            val reader = AndroidWifiPlatformReader(app.applicationContext)
            platformReader = reader
            factsReader = WifiFactsReader(
                reader,
                ledger,
                WifiSwitchSource { switchSource.read() },
                DeviceWifiPolicyView,
                ::awareHeld,
                ::activeCount,
                SystemClock::elapsedRealtime,
            )
        }
    }

    /** Internal, not private: A8 and A9 start their platform work here. Lazy; the thread never quits. [GAP+] */
    val handler: Handler
        get() = handlerRef ?: synchronized(startLock) {
            handlerRef ?: Handler(HandlerThread(THREAD_NAME).also { it.start() }.looper).also { handlerRef = it }
        }

    /** A9: `WifiP2pManager.initialize(context, WifiRadio.looper, ...)`. [GAP+] */
    val looper: Looper get() = handler.looper

    val scheduler: WifiScheduler = object : WifiScheduler {
        override fun post(block: () -> Unit) {
            handler.post(block)
        }

        override fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit) {
            handler.postAtTime(block, tag, SystemClock.uptimeMillis() + delayMs)
        }

        override fun cancel(tag: Any) {
            handler.removeCallbacksAndMessages(tag)
        }

        override fun nowMs(): Long = SystemClock.elapsedRealtime()

        override fun isWifiThread(): Boolean = handlerRef.let { it != null && Looper.myLooper() === it.looper } // never creates the thread [Am 2c]
    }

    private val pauseGate = DiscoveryPauseGate(scheduler, WifiLog::emit)

    private val coordinator = AwareAttachCoordinator<WifiAwareSession>(RealAttachPort, ledger, SystemClock::elapsedRealtime)

    fun acquireAware(owner: String, holder: AwareHolder<WifiAwareSession>): AwareHold {
        val hold = AwareHold(holdIds.incrementAndGet(), owner)
        runOnWifi { coordinator.acquire(hold, holder) }
        return hold
    }

    fun releaseAware(hold: AwareHold) = runOnWifi { coordinator.release(hold) }

    private inline fun runOnWifi(crossinline block: () -> Unit) {
        if (scheduler.isWifiThread()) block() else handler.post { block() }
    }

    internal fun linkClosed(link: AwareLink) {
        liveLinks.remove(link)
    }

    /**
     * A new link of ANY owner. The generation must be unique among the live links of ALL owners (it names the hold
     * `link-<gen>`, keys `AwareSockets.closeOwned` and appears in the logs): A5 counts up from 0, A8 from 2^40, A4
     * keeps no shared counter. [dir A8]
     */
    fun newLink(generation: Long, events: AwareLinkEvents): AwareLink = AwareDataPath(generation, events).also { liveLinks.add(it) }

    /** ANY hold, including A8's discovery hold. */
    fun awareHeld(): Boolean = coordinator.heldOrAttaching

    /** Live links of ANY owner: A5 attempt and carrier links and A8 native exchange links. Feeds `awareAttachHeldByThisApp`, not a fact of its own. [R M-8][dir A8] */
    fun activeCount(): Int = liveLinks.size

    /** 0 with `wifi_carrier` off: no platform read, whoever calls it (X4). [GAP+] same value as facts().freeNdp */
    fun freeNdp(): Int = if (!switchSource.read().carrier) 0 else factsReader?.freeNdp() ?: 0

    /** The real budget of a reservation: reserved + free - the preemptable stranger's path (counted once). [A4 review P1-1] */
    fun ndpCapacity(): Int = WifiFactsReader.reserveCapacity(ledger, freeNdp())

    fun reserveNdp(owner: String): Boolean = ledger.reserveNdp(owner, ndpCapacity()) // [GAP+] preempts the stranger when full [F-3]

    fun releaseNdp(owner: String) {
        ledger.releaseNdp(owner) // [GAP+]
    }

    /** A8's unauthenticated native responder reservation (F-3); preempted by an authenticated reserve. [Am 4] */
    fun reserveUntrustedNdp(owner: String, preempt: () -> Unit): Boolean =
        ledger.reserveUntrustedNdp(owner, ndpCapacity(), preempt)

    /** A8 calls this when BMH3 admission completed on that socket. [Am 4] */
    fun trustNdp(owner: String) = ledger.trustNdp(owner)

    internal fun context(): Context? = appContext // [Am 13]

    internal fun awareManager(): WifiAwareManager? = runCatching { appContext?.getSystemService(WifiAwareManager::class.java) }.getOrNull() // [Am 13]

    internal fun connectivityManager(): ConnectivityManager? = runCatching { appContext?.getSystemService(ConnectivityManager::class.java) }.getOrNull() // [Am 13]

    /** Null with `wifi_carrier` off: no platform read, whoever calls it (X4). [GAP+] any thread */
    fun characteristics(): AwareCapsSummary? =
        if (!switchSource.read().carrier) null else runCatching { platformReader?.characteristics() }.getOrNull()

    /** Any thread; true when this fault started the pause. [GAP+] */
    fun noteLocalFault(owner: String, reason: String): Boolean {
        val paused = ledger.recordLocalFault("${owner}_$reason")
        WifiLog.emit(WifiLog.upgrade("fault", "owner" to owner, "kind" to reason, "paused" to paused))
        return paused
    }

    fun registerDiscoveryPauser(pauser: DiscoveryPauser) = pauseGate.register(pauser) // [R M-10]

    fun unregisterDiscoveryPauser(pauser: DiscoveryPauser) = pauseGate.unregister(pauser)

    fun pauseDiscoveryLease(owner: String): DiscoveryPauseLease = pauseGate.pause(owner)

    fun discoveryPaused(): Boolean = pauseGate.paused

    fun registerShutdown(closeable: java.io.Closeable) {
        shutdownCloseables.add(closeable) // [GAP+] A9
    }

    fun unregisterShutdown(closeable: java.io.Closeable) {
        shutdownCloseables.remove(closeable)
    }

    fun shutdownAll(reason: String) {
        if (handlerRef == null) return // no thread ever ran: nothing to close, and no thread is started [Am 2c]
        runOnWifi {
            pauseGate.clear() // first: drops every lease WITHOUT resume callbacks, so closing a registered owner below cannot restart discovery [R M-10]
            shutdownCloseables.toList().forEach { runCatching { it.close() } }
            shutdownCloseables.clear() // A9 Direct group/driver [GAP+]
            liveLinks.toList().forEach { runCatching { it.close(reason) } }
            coordinator.shutdown() // holders get onAwareDown(SHUTDOWN): terminal, they must not acquire again
            ledger.releaseAllClaims() // the closed links' owners were not told: free their NDP and Direct claims [Am 16]
            WifiLog.emit(WifiLog.upgrade("release", "scope" to "all", "reason" to reason))
        }
    }

    fun residue(): WifiResidue = WifiResidue(
        awareAttach = if (coordinator.heldOrAttaching) 1 else 0,
        awareNetworks = liveLinks.sumOf { it.registeredNetworkCallbacks },
        wifiSockets = AwareSockets.openCount(),
        directGroups = if (ledger.directGroupActive) 1 else 0,
    )

    fun logHash(value: String): String = WifiLog.hash(value)

    fun facts(): WifiFacts = WifiDebugArms.adjust(factsReader?.read() ?: unavailableFacts()) // [R M-7]; never throws

    fun decide(): WifiEligibility.Decision = WifiEligibility.decide(facts()) // [S3]
    // REMOVED [R M-8]: icmSupported(); A0's awareIcmSupported fact and Decision.awareIcm replace it.

    private fun unavailableFacts() = WifiFacts( // no context yet: featureAware = false and every permission false, so decide() declines
        api = Build.VERSION.SDK_INT,
        featureAware = false,
        featureDirect = false,
        awareAvailable = false,
        otherAwareClientAttached = null,
        awareIcmSupported = false,
        awareAttachHeldByThisApp = false,
        wifiEnabled = false,
        staFrequencyMhz = null,
        locationEnabled = false,
        permissionNearby = false,
        permissionFineLocation = false,
        switches = switchSource.read(),
        batteryPercent = 100,
        charging = false,
        batterySaver = false,
        thermalStatus = 0,
        familyPolicyAwareOff = false,
        familyPolicyDirectOff = false,
        localFaultPauseUntilMs = ledger.pauseUntilMs,
        nowMs = SystemClock.elapsedRealtime(),
        directGroupActive = ledger.directGroupActive,
        hotspotActive = false,
        freeNdp = 0,
    )

    /** The real attach port: receivers, the manage/attach calls and the two platform down edges. */
    private object RealAttachPort : AwareAttachPort<WifiAwareSession> {
        private var awareReceiverRegistered = false
        private var wifiReceiverRegistered = false

        override fun precheck(): AttachFailure? {
            return try {
                val ctx = appContext ?: return AttachFailure.NO_MANAGER
                if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) return AttachFailure.NO_MANAGER
                val manager = ctx.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return AttachFailure.NO_MANAGER
                val wifi = ctx.getSystemService(WifiManager::class.java)
                if (wifi?.isWifiEnabled != true) return AttachFailure.WIFI_OFF
                val permitted = if (Build.VERSION.SDK_INT >= 33) {
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                        (ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true)
                }
                if (!permitted) return AttachFailure.NO_PERMISSION
                if (!manager.isAvailable) return AttachFailure.UNAVAILABLE
                null
            } catch (e: SecurityException) {
                AttachFailure.NO_PERMISSION
            } catch (e: RuntimeException) {
                AttachFailure.UNAVAILABLE
            }
        }

        override fun deviceAttachedByAnyone(): Boolean? = runCatching {
            if (Build.VERSION.SDK_INT < 31) return null
            val manager = appContext?.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager ?: return null
            Api31.deviceAttached(manager)
        }.getOrNull()

        override fun startAttach(token: Long) {
            val manager = appContext?.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
                ?: throw RuntimeException("no Wi-Fi Aware manager")
            manager.attach(
                object : android.net.wifi.aware.AttachCallback() {
                    override fun onAttached(session: WifiAwareSession) = coordinator.onAttached(token, session)
                    override fun onAttachFailed() = coordinator.onAttachFailed(token, AttachFailure.ATTACH_REFUSED)
                    override fun onAwareSessionTerminated() = coordinator.onSessionTerminated(token)
                },
                handler,
            )
        }

        override fun closeSession(session: WifiAwareSession) {
            runCatching { session.close() }
        }

        override fun registerReceivers() {
            val ctx = appContext ?: return
            if (!awareReceiverRegistered) {
                runCatching {
                    ContextCompat.registerReceiver(
                        ctx,
                        awareStateReceiver,
                        IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED),
                        null,
                        handler,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    awareReceiverRegistered = true
                }
            }
            if (!wifiReceiverRegistered) {
                runCatching {
                    ContextCompat.registerReceiver(
                        ctx,
                        wifiStateReceiver,
                        IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                        null,
                        handler,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    wifiReceiverRegistered = true
                }
            }
        }

        override fun unregisterReceivers() {
            val ctx = appContext ?: return
            if (awareReceiverRegistered) {
                awareReceiverRegistered = false
                runCatching { ctx.unregisterReceiver(awareStateReceiver) }
            }
            if (wifiReceiverRegistered) {
                wifiReceiverRegistered = false
                runCatching { ctx.unregisterReceiver(wifiStateReceiver) }
            }
        }

        override fun post(block: () -> Unit) {
            handler.post(block)
        }

        override fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit) {
            scheduler.postDelayed(tag, delayMs, block)
        }

        override fun cancel(tag: Any) {
            scheduler.cancel(tag)
        }

        override fun log(line: String) {
            WifiLog.emit(line)
        }

        /**
         * Platform 1.1: Aware already treats a state change as "discard the session", so every receipt recycles it
         * (no `isAvailable` test; the coordinator returns at once when IDLE). [Am 10]
         */
        private val awareStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                coordinator.onAwareUnavailable(AwareDownReason.UNAVAILABLE)
            }
        }

        /** Platform 1.1: Wi-Fi off means Aware is gone for apps. */
        private val wifiStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                if (state == WifiManager.WIFI_STATE_DISABLING || state == WifiManager.WIFI_STATE_DISABLED) {
                    coordinator.onAwareUnavailable(AwareDownReason.WIFI_OFF)
                }
            }
        }
    }

    @RequiresApi(31)
    private object Api31 {
        fun deviceAttached(manager: WifiAwareManager): Boolean? = manager.isDeviceAttached
    }
}
