package com.example.bluetoothtest

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/** A faithful stand-in for one attempt's [AwareLink]: every event is posted through the scheduler, never inline. */
internal class FakeAware(
    override val generation: Long,
    private val events: AwareLinkEvents,
    private val scheduler: ManualScheduler,
) : AwareLink {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    val messages: MutableList<Pair<Int, ByteArray>> = Collections.synchronizedList(ArrayList())
    val accepted: MutableList<Socket> = Collections.synchronizedList(ArrayList())

    var closeCount = 0
        private set
    var autoAttach = true
    var autoSession = true
    var synchronousSession = false
    var requestResult = true
    var handOffResult = true
    var connectTarget: InetSocketAddress? = null
    /** When nonzero, the port [connectTarget] listens on: a connect to any other port is refused, as by a real host. */
    @Volatile var connectTargetPort = 0
    var sendHook: ((Int, ByteArray) -> Unit)? = null
    var onListen: (() -> Unit)? = null
    var onResponder: (() -> Unit)? = null
    var onInitiator: (() -> Unit)? = null
    var lastResponderPort = 0
        private set
    var lastListenPort = 0
        private set
    var lastConnectPort = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var lossListener: ((AwareDownReason) -> Unit)? = null

    override val caps = AwareCapsSummary(4096, true, true, 4)
    override val registeredNetworkCallbacks = 0

    val serverAddress: InetSocketAddress?
        get() = serverSocket?.let { InetSocketAddress(InetAddress.getLoopbackAddress(), it.localPort) }

    override fun acquire() {
        calls += "acquire"
        if (autoAttach) scheduler.post { events.onAttached(generation) }
    }

    override fun publish(serviceName: String, ttlSec: Int, icm: Boolean) {
        calls += "publish:$serviceName"
        if (synchronousSession) {
            events.onSessionStarted(generation)
        } else if (autoSession) {
            scheduler.post { events.onSessionStarted(generation) }
        }
    }

    override fun subscribe(serviceName: String, ttlSec: Int, icm: Boolean) {
        calls += "subscribe:$serviceName"
        if (synchronousSession) {
            events.onSessionStarted(generation)
        } else if (autoSession) {
            scheduler.post { events.onSessionStarted(generation) }
        }
    }

    override fun sendMessage(peer: AwarePeer, bytes: ByteArray): Boolean {
        calls += "message:${peer.ordinal}"
        messages += peer.ordinal to bytes.copyOf()
        sendHook?.invoke(peer.ordinal, bytes.copyOf())
        return true
    }

    override fun requestResponder(peer: AwarePeer, pmk: ByteArray, port: Int, timeoutMs: Int): Boolean {
        calls += "respond:$port"
        lastResponderPort = port
        onResponder?.invoke()
        return requestResult
    }

    override fun requestInitiator(peer: AwarePeer, pmk: ByteArray, timeoutMs: Int): Boolean {
        calls += "initiate"
        onInitiator?.invoke()
        return requestResult
    }

    override fun listen(info: AwareDataPathInfo, port: Int, maxAccepts: Int, windowMs: Int) {
        calls += "listen:$port"
        lastListenPort = port
        val server = ServerSocket(0, maxAccepts, InetAddress.getLoopbackAddress())
        serverSocket = server
        val thread = Thread({
            try {
                var ordinal = 1
                while (!server.isClosed && ordinal <= maxAccepts) {
                    val socket = server.accept()
                    accepted += socket
                    val n = ordinal++
                    scheduler.post { events.onSocketAccepted(generation, socket, n, System.nanoTime()) }
                }
            } catch (_: Exception) {
                // closed
            }
        }, "fake-aware-accept-$generation")
        thread.isDaemon = true
        thread.start()
        onListen?.invoke()
    }

    override fun connect(info: AwareDataPathInfo, connectTimeoutMs: Int, windowMs: Int) {
        calls += "connect"
        lastConnectPort = info.port
        val thread = Thread({
            val deadline = System.currentTimeMillis() + windowMs
            while (System.currentTimeMillis() < deadline) {
                val target = connectTarget
                val port = connectTargetPort
                if (target != null && (port == 0 || port == info.port)) {
                    val socket = Socket()
                    try {
                        socket.connect(target, minOf(connectTimeoutMs, 500))
                        scheduler.post { events.onConnected(generation, socket, System.nanoTime()) }
                        return@Thread
                    } catch (_: Exception) {
                        runCatching { socket.close() }
                    }
                }
                Thread.sleep(20)
            }
            scheduler.post { events.onConnectFailed(generation, "window", true) }
        }, "fake-aware-connect-$generation")
        thread.isDaemon = true
        thread.start()
    }

    override fun handOff(lossListener: (AwareDownReason) -> Unit): Boolean {
        calls += "handOff"
        this.lossListener = lossListener
        return handOffResult
    }

    override fun close(reason: String) {
        calls += "close:$reason"
        closeCount++
        runCatching { serverSocket?.close() }
    }

    fun discover(ordinal: Int) {
        scheduler.post { events.onPeerDiscovered(generation, AwarePeer(ordinal, "h$ordinal")) }
    }

    fun deliver(ordinal: Int, bytes: ByteArray) {
        scheduler.post { events.onMessage(generation, AwarePeer(ordinal, "h$ordinal"), bytes.copyOf()) }
    }

    fun ready(channels: List<Int>? = listOf(5180), portKnown: Boolean = true, port: Int = 4242) {
        val loopback = InetAddress.getByName("::1") as Inet6Address
        val info = AwareDataPathInfo("aware-test", 1, loopback, loopback, if (portKnown) port else 0, channels)
        scheduler.post { events.onDataPathReady(generation, info) }
    }

    fun unavailable(elapsedMs: Long, timedOut: Boolean) {
        scheduler.post { events.onNetworkUnavailable(generation, elapsedMs, timedOut) }
    }

    fun lost() {
        scheduler.post { events.onNetworkLost(generation) }
    }

    fun sessionFailed(reason: String, local: Boolean = true) {
        scheduler.post { events.onSessionFailed(generation, reason, local) }
    }

    fun awareDown(reason: AwareDownReason) {
        scheduler.post { events.onAwareDown(generation, reason) }
    }

    fun platformLoss(reason: AwareDownReason) {
        lossListener?.invoke(reason)
    }
}

/**
 * Wires two fake links into one end-to-end attempt pair (host handle 7, guest handle 1). With [routeByPort] the guest reaches
 * the host only on the port the host listens on, and its platform reports the host's port unless [guestPortKnown] is false
 * (`WifiAwareNetworkInfo.getPort()` 0, as on the S22 with an S24 host).
 */
internal class FakeAwarePair(
    val host: FakeAware,
    val guest: FakeAware,
    private val channels: List<Int>? = listOf(5180),
    private val routeByPort: Boolean = false,
    private val guestPortKnown: Boolean = true,
) {
    private var hostRequested = false
    private var guestRequested = false

    init {
        host.sendHook = { _, bytes -> guest.deliver(1, bytes) }
        guest.sendHook = { _, bytes -> host.deliver(7, bytes) }
        host.onListen = {
            if (routeByPort) guest.connectTargetPort = host.lastListenPort
            guest.connectTarget = host.serverAddress
        }
        host.onResponder = {
            hostRequested = true
            maybeReady()
        }
        guest.onInitiator = {
            guestRequested = true
            maybeReady()
        }
    }

    private fun maybeReady() {
        if (!hostRequested || !guestRequested) return
        host.ready(channels)
        if (routeByPort) guest.ready(channels, guestPortKnown, host.lastResponderPort) else guest.ready(channels)
    }
}
