package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A9's implementation of A5's [BearerDriver]. It owns one [DirectGroup], the server socket and accept thread, the
 * connect thread and the discovery lease; every [BearerEvents] callback is posted to the wifi thread. [X1]
 */
internal class DirectBearerDriver(
    private val generation: Long,
    private val events: BearerEvents,
    private val p2p: P2pPort,
    private val record: DirectGroupRecord,
    private val ledger: WifiLedger,
    private val scheduler: DirectGroup.Scheduler,
    private val post: (block: () -> Unit) -> Unit,
    private val clock: () -> Long,
    private val pauseDiscovery: (owner: String) -> DiscoveryPauseLease,
    private val staFrequencyMhz: () -> Int?,
    private val allow24Ghz: Boolean,
    private val enabled: () -> Boolean,
    private val register: (Closeable) -> Unit,
    private val unregister: (Closeable) -> Unit,
    private val quarantine: DirectQuarantine,
    private val sockets: DirectSocketPort = DirectSockets,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : BearerDriver, Closeable {
    override val bearer: WifiBearer = WifiBearer.DIRECT

    private val closed = AtomicBoolean(false)
    private val leaseFinished = AtomicBoolean(false)
    private var lease: DiscoveryPauseLease? = null
    private var group: DirectGroup? = null
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var connectThread: Thread? = null

    @Volatile
    private var acceptLoop = true

    @Volatile
    private var handedOff = false

    @Volatile
    private var lossListener: ((AwareDownReason) -> Unit)? = null

    private var lossDelivered = false
    private var registered = false
    private var accepted = 0
    private var joined: DirectGroup.Joined? = null

    private val groupEvents = object : DirectGroup.Events {
        override fun onHostReady(ready: DirectGroup.HostReady) {
            if (closed.get()) return
            logReady(ready.frequencyMhz)
            startServer(ready)
        }

        override fun onJoined(value: DirectGroup.Joined) {
            if (closed.get()) return
            WifiLog.emit(
                WifiLog.upgrade(
                    "ready",
                    "carrier" to "direct",
                    "group" to "ready",
                    "freq_band" to band(value.frequencyMhz),
                    "freq_source" to value.frequencySource,
                    "same_channel" to "unknown",
                    "address_ok" to true,
                ),
            )
            joined = value
            post { events.onJoined(generation, value.frequencyMhz.takeIf { it > 0 }) }
        }

        override fun onFailed(failure: DirectFailure) {
            if (closed.get()) return
            logFailed(failure)
            post { events.onFailed(generation, failureOf(failure)) }
        }

        override fun onLost() {
            onGroupLost()
        }
    }

    override fun prepare(): BearerFailure? {
        if (closed.get()) return BearerFailure("closed", 1, false)
        if (!enabled()) return BearerFailure("switch_off", 1, false)
        if (quarantine.active || ledger.directGroupActive) return BearerFailure("busy", 2, false)
        if (lease != null) return null
        val taken = pauseDiscovery("direct")
        if (!taken.awaitPaused(WifiRadioLimits.DISCOVERY_PAUSE_AWAIT_MS)) {
            taken.resume()
            return BearerFailure("busy", 2, false)
        }
        lease = taken
        if (!registered) {
            registered = true
            register(this)
        }
        return null
    }

    override fun startHost(ssid: String, psk: String) {
        if (closed.get() || group != null) return
        val created = newGroup()
        group = created
        created.host(ssid, psk, DirectPolicy.hostFrequency(staFrequencyMhz()))
    }

    override fun startGuest(ssid: String, psk: String, freqMhz: Int) {
        if (closed.get() || group != null) return
        val created = newGroup()
        group = created
        created.join(ssid, psk, freqMhz)
    }

    override fun connect(ipv4: ByteArray, port: Int) {
        if (closed.get()) return
        if (!DirectAddressRules.credsAddressOk(ipv4)) {
            driverFailure(DirectFailure.CREDS_OFF_LINK)
            return
        }
        val host = runCatching { InetAddress.getByAddress(ipv4) as? Inet4Address }.getOrNull()
        if (host == null) {
            driverFailure(DirectFailure.CREDS_OFF_LINK)
            return
        }
        val value = joined
        if (value == null || !DirectAddressRules.credsOnLink(value.local, host)) {
            driverFailure(DirectFailure.CREDS_OFF_LINK)
            return
        }
        if (value.ownerAddress != null && value.ownerAddress != host) {
            driverFailure(DirectFailure.CREDS_OFF_LINK)
            return
        }
        val addresses = p2p.localAddresses()
        if (addresses.none { it == value.local }) {
            driverFailure(DirectFailure.NO_ADDRESS)
            return
        }
        if (DirectAddressRules.subnetOverlap(value.local, addresses)) {
            driverFailure(DirectFailure.SUBNET_OVERLAP)
            return
        }
        startConnectThread(value.local, host, port)
    }

    override fun handOff(lossListener: (AwareDownReason) -> Unit): Boolean {
        if (closed.get() || !enabled()) return false
        acceptLoop = false
        stopServer()
        handedOff = true
        this.lossListener = lossListener
        return true
    }

    override fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        AwareSockets.closeOwned(generation) // server, accepted-but-unhanded sockets, the connect socket
        post {
            if (registered) {
                registered = false
                runCatching { unregister(this) }
            }
            acceptLoop = false
            acceptThread?.interrupt()
            acceptThread = null
            connectThread?.interrupt()
            connectThread = null
            stopServer()
            val active = group
            if (active == null) {
                finishLease()
            } else {
                active.close { finishLease() }
            }
        }
    }

    override fun close() = close("shutdown")

    private fun newGroup(): DirectGroup =
        DirectGroup(p2p, record, ledger, scheduler, clock, groupEvents, quarantine, allow24Ghz)

    private fun startServer(ready: DirectGroup.HostReady) {
        val listener = try {
            sockets.listen(ready.local)
        } catch (error: IOException) {
            driverFailure(DirectFailure.PLATFORM_ERROR)
            return
        }
        server = listener
        AwareSockets.track(generation, listener)
        acceptLoop = true
        acceptThread = Thread({ runAcceptLoop(ready.local) }, "wifi-accept-$generation").also {
            it.isDaemon = true
            it.start()
        }
        post { events.onHostReady(generation, ready.frequencyMhz, ready.local.address.address, listener.localPort) }
    }

    /** A9 review P2-4: no throw ever leaves the thread (an uncaught one kills the process and the call with it). */
    private fun runAcceptLoop(local: DirectAddressRules.LocalAddress) {
        try {
            acceptSockets(local)
        } catch (error: RuntimeException) {
            if (!closed.get()) post { events.onConnectFailed(generation, "io", false) }
        }
    }

    private fun acceptSockets(local: DirectAddressRules.LocalAddress) {
        while (acceptLoop) {
            val acceptedSocket = try {
                server?.accept() ?: return
            } catch (error: IOException) {
                return
            }
            if (closed.get() || !acceptLoop) {
                runCatching { acceptedSocket.close() }
                return
            }
            AwareSockets.track(generation, acceptedSocket)
            val admitted = try {
                sockets.admit(acceptedSocket, local)
            } catch (error: Exception) {
                // close() may have closed the tracked socket between track and admit (applyOptions then throws).
                runCatching { acceptedSocket.close() }
                if (closed.get()) return
                post { events.onSocketRejected(generation, "error") }
                continue
            }
            if (!admitted) {
                post { events.onSocketRejected(generation, "outside_subnet") }
                continue
            }
            val ordinal = synchronized(this) { ++accepted }
            if (ordinal > WifiLimits.MAX_ACCEPTED_SOCKETS_PER_ATTEMPT) {
                runCatching { acceptedSocket.close() }
                post { events.onSocketRejected(generation, "too_many") }
                continue
            }
            post { events.onSocketAccepted(generation, acceptedSocket, ordinal, System.nanoTime()) }
        }
    }

    /**
     * A9 review P1-1 / P2-4: the retry sleep is interruptible (close() interrupts it) and ends the loop quietly, and no
     * throw leaves the thread. Nothing is reported once the driver is closed.
     */
    private fun startConnectThread(local: DirectAddressRules.LocalAddress, host: Inet4Address, port: Int) {
        connectThread = Thread({
            try {
                runConnectLoop(local, host, port)
            } catch (error: RuntimeException) {
                if (!closed.get()) post { events.onConnectFailed(generation, "io", false) }
            }
        }, "wifi-connect-$generation").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun runConnectLoop(local: DirectAddressRules.LocalAddress, host: Inet4Address, port: Int) {
        val deadline = clock() + WifiRadioLimits.CONNECT_WINDOW_MS
        while (!closed.get() && acceptLoop && clock() <= deadline) {
            val socket = try {
                sockets.connect(local, host, port)
            } catch (refused: ConnectException) {
                null // retry inside the window
            } catch (timeout: SocketTimeoutException) {
                // P3-3 / AM 22: no answer at all (an always-on VPN that blocks it, or a silent peer): connect_timeout.
                if (!closed.get()) post { events.onConnectFailed(generation, "timeout", false) }
                return
            } catch (error: IOException) {
                if (!closed.get()) post { events.onConnectFailed(generation, "io", false) }
                return
            }
            if (socket != null) {
                if (closed.get()) {
                    runCatching { socket.close() }
                } else {
                    AwareSockets.track(generation, socket)
                    if (closed.get()) runCatching { socket.close() } // close() ran between the check and track
                    else post { events.onConnected(generation, socket, System.nanoTime()) }
                }
                return
            }
            if (!sleepQuietly()) break
        }
        if (!closed.get()) post { events.onConnectFailed(generation, "timeout", true) }
    }

    /** false = interrupted (close): the loop ends without a report. As A5's AwareDataPath.sleepQuietly. */
    private fun sleepQuietly(): Boolean =
        try {
            sleep(WifiRadioLimits.CONNECT_RETRY_MS)
            true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun driverFailure(failure: DirectFailure) {
        logFailed(failure) // P3-2: driver-side failures get the same failed line as the group's
        if (DirectPolicy.countsAsLocalFault(failure)) {
            ledger.recordLocalFault("direct_" + failure.name.lowercase())
        }
        post { events.onFailed(generation, failureOf(failure)) }
    }

    private fun failureOf(failure: DirectFailure): BearerFailure =
        BearerFailure(failure.name.lowercase(), DirectPolicy.declineFor(failure).wire, DirectPolicy.countsAsLocalFault(failure))

    private fun logReady(freq: Int) {
        WifiLog.emit(
            WifiLog.upgrade(
                "ready",
                "carrier" to "direct",
                "group" to "ready",
                "freq_band" to band(freq),
                "freq_source" to if (freq > 0) "group" else "none",
                "same_channel" to sameChannel(freq),
                "address_ok" to true,
            ),
        )
    }

    private fun logFailed(failure: DirectFailure) {
        WifiLog.emit(
            WifiLog.upgrade(
                "failed",
                "carrier" to "direct",
                "group" to "failed",
                "reason" to failure.name.lowercase(),
                "freq_band" to "unknown",
                "freq_source" to "none",
                "same_channel" to "unknown",
                "address_ok" to false,
            ),
        )
    }

    private fun band(freq: Int): String = when {
        freq >= WifiLimits.MIN_5GHZ_MHZ -> "5"
        freq in 2_400..2_500 -> "24"
        else -> "unknown"
    }

    private fun sameChannel(freq: Int): String {
        val sta = staFrequencyMhz() ?: return "unknown"
        if (sta <= 0 || freq <= 0) return "unknown"
        return if (sta == freq) "true" else "false"
    }

    private fun onGroupLost() {
        if (lossDelivered) return
        lossDelivered = true
        val listener = lossListener
        if (handedOff && listener != null) listener(AwareDownReason.UNAVAILABLE)
        else post { events.onLost(generation, AwareDownReason.UNAVAILABLE) }
    }

    private fun stopServer() {
        val listener = server
        server = null
        if (listener != null) {
            AwareSockets.close(listener)
            runCatching { listener.close() }
        }
    }

    private fun finishLease() {
        if (!leaseFinished.compareAndSet(false, true)) return
        val taken = lease
        lease = null
        if (taken != null) {
            if (quarantine.active) quarantine.holdLease(taken) else taken.resume()
        }
    }
}

/** Builds and owns the process's Direct driver, the device-name reader and the one quarantine. [AM 8][AM 13] */
internal object DirectBearerFactory {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var supports5Ghz: Boolean? = null

    private var deviceName: DirectDeviceName? = null

    @Volatile
    private var quarantine: DirectQuarantine? = null

    /** Idempotent; stores the application context, builds the device-name reader and assigns the seam. No platform call. */
    fun attach(app: Context) {
        val context = app.applicationContext
        if (appContext === context) return
        appContext = context
        val name = DirectDeviceName(
            canRead = { permissionHeld(context) && !WifiRadio.awareHeld() && !WifiRadio.ledger.directGroupActive && !OfflineAppShareGate.liveSessionActive() },
            openPort = { AndroidP2pPort(context, WifiRadio.looper) },
            scheduler = DirectSchedulerAdapter(WifiRadio.scheduler),
            clock = SystemClock::elapsedRealtime,
        )
        deviceName = name
        DirectNameSource.read = { then -> name.refresh(then) }
    }

    /**
     * Switch first (so with Wi-Fi Direct off no platform call is made), then API, feature and 5 GHz support. A5 masks
     * the Direct CAPS bits when this is false, so a 2.4 GHz-only phone never offers Direct. [AM 13]
     */
    fun available(): Boolean {
        val context = appContext ?: return false
        if (!WifiRadio.switchSource.read().let { it.carrier && it.direct }) return false
        if (Build.VERSION.SDK_INT < 29) return false
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return false
        val five = supports5Ghz ?: run {
            val value = runCatching {
                context.getSystemService(WifiManager::class.java)?.is5GHzBandSupported == true
            }.getOrDefault(false)
            supports5Ghz = value
            value
        }
        return five || WifiRadio.facts().debugAllow24Ghz
    }

    /** The one per-process quarantine; created lazily so a process with every switch off never builds it. */
    fun quarantine(): DirectQuarantine {
        quarantine?.let { return it }
        synchronized(this) {
            quarantine?.let { return it }
            val context = appContext ?: error("DirectBearerFactory.attach not called")
            val created = DirectQuarantine(
                openPort = { AndroidP2pPort(context, WifiRadio.looper) },
                record = directGroupRecord(context),
                ledger = WifiRadio.ledger,
                scheduler = DirectSchedulerAdapter(WifiRadio.scheduler),
                notice = DirectQuarantineNotice::set,
                log = WifiLog::emit,
            )
            quarantine = created
            return created
        }
    }

    fun create(generation: Long, events: BearerEvents, facts: WifiFacts): BearerDriver? {
        val context = appContext ?: return null
        return DirectBearerDriver(
            generation = generation,
            events = events,
            p2p = AndroidP2pPort(context, WifiRadio.looper),
            record = directGroupRecord(context),
            ledger = WifiRadio.ledger,
            scheduler = DirectSchedulerAdapter(WifiRadio.scheduler),
            post = WifiRadio.scheduler::post,
            clock = SystemClock::elapsedRealtime,
            pauseDiscovery = WifiRadio::pauseDiscoveryLease,
            staFrequencyMhz = { facts.staFrequencyMhz },
            allow24Ghz = facts.debugAllow24Ghz,
            enabled = { WifiRadio.switchSource.read().let { it.carrier && it.direct } },
            register = WifiRadio::registerShutdown,
            unregister = WifiRadio::unregisterShutdown,
            quarantine = quarantine(),
        )
    }

    private fun permissionHeld(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
}
