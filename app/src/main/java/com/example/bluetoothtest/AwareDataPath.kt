package com.example.bluetoothtest

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android implementation of [AwareLink]: one instance is one attempt generation. Every callback of the platform
 * arrives on the wifi thread; every method except [close] must run there (`check`), and every event to the owner is
 * posted so the owner never runs inside this class. The release order of [close] is fixed (A4.10.4): sockets inline
 * first, then the network callback, the discovery sessions and the attach last. [Am 6]
 */
internal class AwareDataPath(
    override val generation: Long,
    private val events: AwareLinkEvents,
) : AwareLink, AwareHolder<WifiAwareSession> {
    @Volatile private var closed = false
    @Volatile private var handedOff = false
    private var hold: AwareHold? = null
    private var session: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val peers = HashMap<PeerHandle, AwarePeer>() // PeerHandle has equals/hashCode on peerId; scoped to THIS session only
    private var nextOrdinal = 1
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile override var registeredNetworkCallbacks = 0
        private set
    private var network: Network? = null
    private var capsSeen: NetworkCapabilities? = null
    private var props: LinkProperties? = null
    private var readyDelivered = false
    private var requestedAtMs = 0L
    private var requestTimeoutMs = 0
    private var callbackSeen = false
    private var lossListener: ((AwareDownReason) -> Unit)? = null
    private var summary = AwareCapsSummary(255, false, false, 1)
    private var ownsSessions = true // false after adoptSessions: the discovery sessions belong to the caller (A8) [GAP+]
    private var pmkCopy: ByteArray? = null // wiped in close() [API]
    private val closing = AtomicBoolean(false)
    private val timerTag = Any()
    private val watchdogTags = HashMap<Int, Any>() // message id -> its own Any() tag; never a boxed Int [Am 6]
    private var messageId = 0
    private var inFlight = 0
    private var messageStuckReported = false
    private val queued = ArrayDeque<QueuedMessage>()

    private val handler get() = WifiRadio.handler
    private val connectivityManager get() = WifiRadio.connectivityManager()

    override val caps: AwareCapsSummary get() = summary

    // --- AwareLink: lifecycle --------------------------------------------------------------------------------------

    override fun acquire() {
        checkWifi()
        if (closed || hold != null) return // one hold per link [Am 17]
        hold = WifiRadio.acquireAware("link-$generation", this)
    }

    override fun onAttached(session: WifiAwareSession) { // AwareHolder, called on the wifi thread by the coordinator
        if (closed) return
        this.session = session
        readSummary()
        post { events.onAttached(generation) }
    }

    override fun onAttachFailed(reason: AttachFailure) { // AwareHolder: the hold is already gone
        hold = null
        post { events.onAttachFailed(generation, reason) }
    }

    override fun onAwareDown(reason: AwareDownReason) { // AwareHolder: the hold is already gone
        hold = null
        if (handedOff) lossListener?.invoke(reason) else post { events.onAwareDown(generation, reason) }
        releaseTail() // everything bound to the session is dead
    }

    override fun handOff(lossListener: (AwareDownReason) -> Unit): Boolean {
        checkWifi()
        if (closed) return false
        handedOff = true
        this.lossListener = lossListener
        return true
    }

    // --- AwareLink: discovery sessions -----------------------------------------------------------------------------

    override fun publish(serviceName: String, ttlSec: Int, icm: Boolean) {
        checkWifi()
        val session = session ?: return
        try {
            session.publish(AwareConfigs.publish(serviceName, ttlSec, icm), DiscoveryCallback(generation), handler)
        } catch (e: SecurityException) {
            postFailure("security") // API 30-32 background location app-op lands here
        } catch (e: IllegalArgumentException) {
            postFailure("config") // service name or config over the HAL limits
        } catch (e: RuntimeException) {
            postFailure("exception")
        }
    }

    override fun subscribe(serviceName: String, ttlSec: Int, icm: Boolean) {
        checkWifi()
        val session = session ?: return
        try {
            session.subscribe(AwareConfigs.subscribe(serviceName, ttlSec, icm), DiscoveryCallback(generation), handler)
        } catch (e: SecurityException) {
            postFailure("security")
        } catch (e: IllegalArgumentException) {
            postFailure("config")
        } catch (e: RuntimeException) {
            postFailure("exception")
        }
    }

    override fun adoptSessions(publish: Any?, subscribe: Any?): Boolean {
        checkWifi()
        if (closed) return false
        if (publish != null && publish !is PublishDiscoverySession) return false
        if (subscribe != null && subscribe !is SubscribeDiscoverySession) return false
        publishSession = publish as? PublishDiscoverySession
        subscribeSession = subscribe as? SubscribeDiscoverySession
        ownsSessions = false
        return true
    }

    override fun peerFor(handle: Any): AwarePeer {
        checkWifi()
        val peer = handle as PeerHandle
        return peers.getOrPut(peer) { AwarePeer(nextOrdinal++, peer) }
    }

    // --- AwareLink: follow-up messages -----------------------------------------------------------------------------

    override fun sendMessage(peer: AwarePeer, bytes: ByteArray): Boolean {
        checkWifi()
        if (!ownsSessions) return false // the send callbacks belong to A8 [GAP+]
        if (closed) return false
        if (bytes.size > summary.maxServiceSpecificInfoLength) return false // platform 1.4: oversize throws synchronously
        if (queued.size >= WifiRadioLimits.MAX_MESSAGES_QUEUED) return false
        queued.addLast(QueuedMessage(peer, bytes))
        pump()
        return true
    }

    private fun pump() {
        while (inFlight < WifiRadioLimits.MAX_MESSAGES_IN_FLIGHT && queued.isNotEmpty()) {
            val session = publishSession ?: subscribeSession ?: return
            val next = queued.first()
            val id = ++messageId
            try {
                session.sendMessage(next.peer.handle as PeerHandle, id, next.bytes) // three-argument overload: zero service-level retries, platform 1.4
            } catch (e: RuntimeException) {
                postFailure("exception")
                return
            }
            queued.removeFirst()
            inFlight++
            val tag = Any()
            watchdogTags[id] = tag
            WifiRadio.scheduler.postDelayed(tag, WifiRadioLimits.MESSAGE_WATCHDOG_MS) { onMessageWatchdog(id) }
        }
    }

    private fun completeSend(id: Int, ok: Boolean) {
        watchdogTags.remove(id)?.let { WifiRadio.scheduler.cancel(it) }
        if (inFlight > 0) inFlight--
        WifiLog.emit(WifiLog.upgrade("message", "gen" to generation, "id" to id, "ok" to ok))
        pump()
    }

    private fun onMessageWatchdog(id: Int) {
        if (watchdogTags.remove(id) == null) return
        if (closed || !ownsSessions) return
        if (inFlight > 0) inFlight--
        if (!messageStuckReported) {
            messageStuckReported = true // platform 1.4: the follow-up queue can stay blocked for hours
            postFailure("message_stuck")
        }
    }

    // --- AwareLink: data path --------------------------------------------------------------------------------------

    override fun requestResponder(peer: AwarePeer, pmk: ByteArray, port: Int, timeoutMs: Int): Boolean {
        checkWifi()
        val session = publishSession ?: return false // a publish handle always files a responder request
        if (callback != null) return false // one request per attempt: refuse before copying, so no key copy is dropped un-wiped [A4 review P3-7]
        val key = pmk.copyOf()
        pmkCopy?.fill(0)
        pmkCopy = key
        val specifier = try {
            AwareConfigs.responderSpecifier(session, peer.handle as PeerHandle, key, port, channelHint(), WifiDebugArms.requireChannel())
        } catch (e: RuntimeException) {
            return false
        }
        return registerNetwork(AwareConfigs.request(specifier), timeoutMs)
    }

    override fun requestInitiator(peer: AwarePeer, pmk: ByteArray, timeoutMs: Int): Boolean {
        checkWifi()
        val session = subscribeSession ?: return false // a subscribe handle can never be used as responder
        if (callback != null) return false // one request per attempt: refuse before copying [A4 review P3-7]
        val key = pmk.copyOf()
        pmkCopy?.fill(0)
        pmkCopy = key
        val specifier = try {
            AwareConfigs.initiatorSpecifier(session, peer.handle as PeerHandle, key, channelHint(), WifiDebugArms.requireChannel())
        } catch (e: RuntimeException) {
            return false
        }
        return registerNetwork(AwareConfigs.request(specifier), timeoutMs)
    }

    /** The phone's own 5 GHz client channel first, then the fixed hint; only when the platform honours a channel. [Am 15] */
    private fun channelHint(): Int? {
        if (!summary.setChannelOnDataPathSupported) return null
        val sta = WifiRadio.context()?.let { WifiCoexistence.connectedFrequencyMhz(it) }
        return sta?.takeIf { it >= WifiLimits.MIN_5GHZ_MHZ } ?: WifiRadioLimits.CHANNEL_HINT_MHZ
    }

    private fun registerNetwork(request: NetworkRequest, timeoutMs: Int): Boolean {
        if (callback != null) return false // exactly one request per attempt: never re-file (platform 8 Knit 3)
        val cb = NetCallback(generation)
        requestedAtMs = SystemClock.elapsedRealtime()
        requestTimeoutMs = timeoutMs
        callbackSeen = false
        val cm = connectivityManager ?: return false
        try {
            cm.requestNetwork(request, cb, handler, timeoutMs)
            callback = cb
            registeredNetworkCallbacks = 1
        } catch (e: SecurityException) {
            return false // a missing CHANGE_NETWORK_STATE; the owner records a local fault
        } catch (e: RuntimeException) {
            // The only local resource error that lowers the learned cap (F-2, [Am 3]); a refusal that is not about
            // data paths (the per-app network-callback limit) leaves the cap alone. [A4 review P3-10]
            if (WifiRadioLimits.lowersLearnedNdpCap(e)) {
                val reserved = WifiRadio.ledger.ndpReserved
                if (reserved > 1) WifiRadio.ledger.noteNdpRefusedWhileActive(reserved - 1)
            }
            return false
        }
        WifiRadio.scheduler.postDelayed(timerTag, timeoutMs + WifiRadioLimits.NETWORK_WATCHDOG_EXTRA_MS) {
            if (!closed && !callbackSeen) {
                val elapsed = SystemClock.elapsedRealtime() - requestedAtMs
                post { events.onNetworkUnavailable(generation, elapsed, timedOut = true) }
            }
        }
        return true
    }

    override fun listen(info: AwareDataPathInfo, port: Int, maxAccepts: Int, windowMs: Int) {
        checkWifi()
        Thread({
            val server = try {
                AwareSockets.track(generation, AwareSockets.openServer(info.localAddress, port))
            } catch (e: IOException) {
                post { events.onServerFailed(generation, "bind") }
                return@Thread
            }
            post { events.onServerListening(generation) }
            val deadline = SystemClock.elapsedRealtime() + windowMs
            var accepted = 0
            try {
                while (!closed && accepted < maxAccepts && SystemClock.elapsedRealtime() < deadline) {
                    server.soTimeout = WifiRadioLimits.ACCEPT_POLL_MS
                    val socket = try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: SocketException) {
                        break
                    } catch (e: IOException) {
                        break
                    }
                    if (!AwareSockets.isExpectedPeer(socket.inetAddress, info.peerAddress, info.interfaceIndex)) {
                        AwareSockets.close(socket) // refused without counting toward the attempt
                        post { events.onSocketRejected(generation, "peer_address") }
                        continue
                    }
                    AwareSockets.track(generation, socket) // closed at once if the link closed meanwhile [A4 review P2-3]
                    if (closed || socket.isClosed) break
                    try {
                        AwareSockets.applyOptions(socket)
                    } catch (e: IOException) {
                        AwareSockets.close(socket) // closed under us by a racing close(): never an uncaught throw
                        break
                    }
                    accepted++
                    post { events.onSocketAccepted(generation, socket, accepted, System.nanoTime()) }
                }
            } finally {
                AwareSockets.close(server) // also refuses a fifth connection
            }
        }, "wifi-accept-$generation").apply { isDaemon = true }.start()
    }

    override fun connect(info: AwareDataPathInfo, connectTimeoutMs: Int, windowMs: Int) {
        checkWifi()
        if (info.port == 0) {
            post { events.onConnectFailed(generation, "no_port", false) }
            return
        }
        val net = network
        if (net == null) {
            post { events.onConnectFailed(generation, "window", false) }
            return
        }
        Thread({
            val deadline = SystemClock.elapsedRealtime() + windowMs
            var refusedOnly = true
            while (!closed && SystemClock.elapsedRealtime() < deadline) {
                val socket = try {
                    net.socketFactory.createSocket() // already bound to the Aware network; never bindProcessToNetwork
                } catch (e: IOException) {
                    refusedOnly = false
                    sleepQuietly()
                    continue
                }
                AwareSockets.track(generation, socket) // closed at once if the link closed meanwhile [A4 review P2-3]
                if (closed || socket.isClosed) return@Thread
                try {
                    AwareSockets.applyOptions(socket) // inside the try: a racing close() throws an IOException here
                    socket.connect(InetSocketAddress(info.peerAddress, info.port), connectTimeoutMs)
                    post { events.onConnected(generation, socket, System.nanoTime()) }
                    return@Thread
                } catch (e: ConnectException) {
                    AwareSockets.close(socket)
                    sleepQuietly()
                } catch (e: IOException) {
                    refusedOnly = false
                    AwareSockets.close(socket)
                    sleepQuietly()
                }
            }
            post { events.onConnectFailed(generation, "window", refusedOnly) }
        }, "wifi-connect-$generation").apply { isDaemon = true }.start()
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(WifiRadioLimits.CONNECT_RETRY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // --- AwareLink: release -----------------------------------------------------------------------------------------

    /** ANY thread: sockets close inline, the rest is posted to the wifi thread. Idempotent. [Am 6] */
    override fun close(reason: String) {
        if (!closing.compareAndSet(false, true)) return
        closed = true
        AwareSockets.closeGeneration(generation) // also closes a socket an accept/connect thread tracks later [A4 review P2-3]
        if (WifiRadio.scheduler.isWifiThread()) releaseOnWifi(reason) else WifiRadio.handler.post { releaseOnWifi(reason) }
    }

    private fun releaseOnWifi(reason: String) {
        AwareSockets.closeOwned(generation) // backstop sweep [A4 review P2-3]
        WifiRadio.scheduler.cancel(timerTag) // the request watchdog
        watchdogTags.values.forEach { WifiRadio.scheduler.cancel(it) }
        watchdogTags.clear()
        callback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        callback = null
        registeredNetworkCallbacks = 0
        pmkCopy?.fill(0)
        pmkCopy = null
        if (ownsSessions) {
            runCatching { publishSession?.close() }
            runCatching { subscribeSession?.close() }
        }
        publishSession = null
        subscribeSession = null
        hold?.let { WifiRadio.releaseAware(it) }
        hold = null
        queued.clear()
        WifiRadio.linkClosed(this)
        WifiLog.emit(WifiLog.upgrade("release", "scope" to "link", "gen" to generation, "reason" to reason))
    }

    /** Steps 3 to 5 of the release without the hold (used when Aware went down and the hold is already gone). */
    private fun releaseTail() {
        closed = true // the link is dead: a hand-off asked for after this is refused (handOff returns false) [A5 review P1-1]
        AwareSockets.closeOwned(generation)
        WifiRadio.scheduler.cancel(timerTag)
        watchdogTags.values.forEach { WifiRadio.scheduler.cancel(it) }
        watchdogTags.clear()
        callback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        callback = null
        registeredNetworkCallbacks = 0
        pmkCopy?.fill(0)
        pmkCopy = null
        if (ownsSessions) {
            runCatching { publishSession?.close() }
            runCatching { subscribeSession?.close() }
        }
        publishSession = null
        subscribeSession = null
    }

    // --- platform callbacks ----------------------------------------------------------------------------------------

    private inner class DiscoveryCallback(val gen: Long) : DiscoverySessionCallback() {
        private fun stale() = gen != generation || closed

        override fun onPublishStarted(session: PublishDiscoverySession) {
            if (stale()) {
                runCatching { session.close() }
                return
            }
            publishSession = session
            post { events.onSessionStarted(gen) }
        }

        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            if (stale()) {
                runCatching { session.close() }
                return
            }
            subscribeSession = session
            post { events.onSessionStarted(gen) }
        }

        override fun onSessionConfigFailed() {
            if (stale()) return
            post { events.onSessionFailed(gen, "config_failed", true) } // never a phone-wide fault [Am 3]
        }

        override fun onSessionTerminated() {
            if (stale()) return
            if (requestedAtMs != 0L || handedOff) {
                WifiLog.emit(WifiLog.upgrade("session", "gen" to gen, "event" to "terminated_after_request"))
            } else {
                post { events.onSessionTerminated(gen) }
            }
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            if (stale()) return
            post { events.onMessage(gen, peerFor(peerHandle), message) }
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
            if (stale()) return
            post { events.onPeerDiscovered(gen, peerFor(peerHandle)) }
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            if (stale()) return
            completeSend(messageId, true)
        }

        override fun onMessageSendFailed(messageId: Int) {
            if (stale()) return
            completeSend(messageId, false)
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) { // compileSdk 36 signature
            if (stale()) return
            WifiLog.emit(WifiLog.upgrade("message", "gen" to gen, "event" to "service_lost"))
        }
    }

    private inner class NetCallback(val gen: Long) : ConnectivityManager.NetworkCallback() {
        private fun stale() = gen != generation || closed

        override fun onAvailable(network: Network) {
            if (stale()) return
            callbackSeen = true
            this@AwareDataPath.network = network
            post { events.onNetworkAvailable(gen) }
            tryReady()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (stale()) return
            callbackSeen = true
            if (readyDelivered) {
                // Fires repeatedly: only log the channel list once the data path is ready.
                val channels = channelsOf(networkCapabilities)
                WifiLog.emit(WifiLog.upgrade("data_path", "gen" to gen, "channels" to channels, "port" to (portOf(networkCapabilities) != 0)))
                return
            }
            capsSeen = networkCapabilities
            tryReady()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (stale()) return
            callbackSeen = true
            props = linkProperties
            tryReady()
        }

        override fun onUnavailable() {
            if (stale()) return
            callbackSeen = true
            val elapsed = SystemClock.elapsedRealtime() - requestedAtMs
            // Early or late, never a local fault and never a learned-cap change (F-2, [Am 3]).
            post { events.onNetworkUnavailable(gen, elapsed, timedOut = false) }
        }

        override fun onLost(network: Network) {
            if (stale()) return
            callbackSeen = true
            if (handedOff) {
                lossListener?.invoke(AwareDownReason.UNAVAILABLE)
            } else {
                post { events.onNetworkLost(gen) }
            }
        }
    }

    private fun tryReady() {
        if (closed || readyDelivered) return
        network ?: return
        val capsNow = capsSeen ?: return
        val propsNow = props ?: return
        val info = capsNow.transportInfo as? WifiAwareNetworkInfo ?: return
        val peerRaw = info.peerIpv6Addr ?: return
        val ifaceName = propsNow.interfaceName ?: return
        val linkLocal = propsNow.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet6Address && it.isLinkLocalAddress } as? Inet6Address ?: return
        val ni = NetworkInterface.getByName(ifaceName) ?: return
        val local = runCatching { AwareSockets.scopedLocal(linkLocal, ni) }.getOrNull() ?: return
        val peer = if (peerRaw.scopeId == 0 || peerRaw.scopedInterface == null) {
            runCatching { Inet6Address.getByAddress(null, peerRaw.address, ni) }.getOrNull() ?: return
        } else {
            peerRaw
        }
        val channels = channelsOf(capsNow)
        readyDelivered = true
        post { events.onDataPathReady(generation, AwareDataPathInfo(ifaceName, ni.index, local, peer, info.port, channels)) }
    }

    private fun portOf(networkCapabilities: NetworkCapabilities): Int =
        (networkCapabilities.transportInfo as? WifiAwareNetworkInfo)?.port ?: 0

    /** API 33+: the channel list of the data path; below 33: null (the band is unverified). */
    private fun channelsOf(networkCapabilities: NetworkCapabilities): List<Int>? {
        if (Build.VERSION.SDK_INT < 33) return null
        val info = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return null
        return Api33.channels(info)
    }

    private fun readSummary() {
        val manager = WifiRadio.awareManager() ?: return
        val characteristics = manager.characteristics ?: return
        summary = AwareCapsSummary(
            maxServiceSpecificInfoLength = characteristics.maxServiceSpecificInfoLength,
            instantCommunicationSupported = if (Build.VERSION.SDK_INT >= 33) Api33.icm(characteristics) else false,
            setChannelOnDataPathSupported = if (Build.VERSION.SDK_INT >= 33) Api33.hint(manager) else false,
            maxNdp = if (Build.VERSION.SDK_INT >= 33) Api33.ndpOf(characteristics) else 1,
        )
    }

    private fun postFailure(reason: String) {
        post { events.onSessionFailed(generation, reason, local = true) }
    }

    private fun post(block: () -> Unit) {
        WifiRadio.scheduler.post(block)
    }

    private fun checkWifi() {
        check(WifiRadio.scheduler.isWifiThread()) { "AwareDataPath off the wifi thread" }
    }

    private class QueuedMessage(val peer: AwarePeer, val bytes: ByteArray)

    @RequiresApi(33)
    private object Api33 {
        fun channels(info: WifiAwareNetworkInfo): List<Int> = info.channelInfoList?.map { it.channelFrequencyMhz } ?: emptyList()
        fun ndpOf(characteristics: Characteristics): Int = characteristics.numberOfSupportedDataPaths
        fun icm(characteristics: Characteristics): Boolean = characteristics.isInstantCommunicationModeSupported
        fun hint(manager: WifiAwareManager): Boolean = manager.isSetChannelOnDataPathSupported
    }
}
