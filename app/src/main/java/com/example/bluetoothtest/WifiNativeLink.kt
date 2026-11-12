package com.example.bluetoothtest

import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeDiscoverySession
import android.os.Build
import android.os.SystemClock
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The Wider-reach native hop: the knock (MWK1) and answer (MWA1) follow-ups on the discovery sessions, the knock
 * machines, the responder policy and one [NativeExchange] per data path. Platform calls run on the wifi thread
 * [WifiRadio.handler]; the blocking [dial] runs on the caller's connect thread and the blocking socket work is A4's
 * `AwareLink` (`wifi-accept-<gen>` / `wifi-connect-<gen>`). The one thread A8 adds is the short-lived daemon
 * `WifiNativeAccept`, which hands an accepted connection to the registry's acceptor. [API][X13]
 */
internal class WifiNativeLink(
    private val registry: WifiOfferRegistry,
    private val eligibility: () -> WifiEligibility.Decision,
    private val freeDataPaths: () -> Int,
) {
    private val throttle = KnockThrottle()
    private val responders = NativeResponderPolicy()
    private val messageIds = AtomicInteger(1)
    private val linkGens = AtomicLong(1L shl 40)     // never equals an A5 attempt generation [GAP+]
    private val liveLinks = AtomicInteger(0)         // NativeExchange links not yet closed
    private var messagesInFlight = 0                 // A8 sends without a result (knocks and answers)

    lateinit var discovery: AwareDiscovery            // assigned by WifiDiscovery.start

    private val dials = HashMap<Int, DialAttempt>()         // message id -> initiator knock
    private val answers = HashSet<Int>()                    // message ids of MWA1 sends
    private val pendingByNonce = HashMap<String, DialAttempt>()
    private val serving = HashMap<String, Serving>()
    private val exchanges = HashMap<Long, NativeExchange>()

    /** The one unauthenticated responder exchange (F-3); its preemption runs on the wifi thread (A8 review P1-3). [AM 12] */
    private val admission = NativeAdmission(WifiRadio.scheduler, release = { owner -> WifiRadio.releaseNdp(owner) })

    private class DialAttempt(
        val key: String,
        val nonceHex: String,
        val machine: NativeKnockMachine,
        val handle: PeerHandle,
        val subGen: Long,
        val epoch: Long,
        val linkGen: Long,
        val result: CompletableFuture<Pair<Socket, AwareDataPathInfo>>,
        val startedAtMs: Long,
    ) {
        @Volatile var exchange: NativeExchange? = null
        /** One instance per attempt: the radio handler cancels a posted tick by tag identity, not by equal text. */
        val tickTag = "a8-knock-$linkGen"
    }

    private class Serving(val nonceHex: String, val handleKey: String, val linkGen: Long, val exchange: NativeExchange)

    fun liveLinks(): Int = liveLinks.get()

    /** Native links that completed BMH3/BMH2 admission and are not yet closed (the epoch deferral counts these). [AM 8][AM 12] */
    fun admittedLinks(): Int = exchanges.values.count { it.admitted }

    /** The generations of the open native links (the `native_path` check of a forced stop reads this). */
    fun openLinkGens(): Set<Long> = exchanges.keys.toSet()

    // --- dial (initiator) -----------------------------------------------------------------------------------------

    /**
     * [deadlineMs] bounds the whole dial (a call passes what is left of its setup deadline); [cancelled] is polled while
     * the dial waits (the user's Cancel or the call ending), and a cancelled dial fails `cancelled` (A8 review P3-7).
     */
    fun dial(
        key: String,
        role: String,
        deadlineMs: Long = WifiLimits.ATTEMPT_MAX_MS,
        cancelled: () -> Boolean = { false },
    ): WifiNativeConnection {
        val startedAt = SystemClock.elapsedRealtime()
        if (!throttle.tryStart(key, startedAt)) throw nativeFail(role, "throttled", startedAt)
        if (!WifiDiscovery.widerReachOn()) throw nativeFail(role, "switch_off", startedAt)   // [AM 21]
        if (!eligibility().aware) throw nativeFail(role, "not_eligible", startedAt)
        if (!NativeBandRule.canVerify(Build.VERSION.SDK_INT)) throw nativeFail(role, "band_unverified", startedAt)   // P2-3
        if (discovery.isPaused()) throw nativeFail(role, "paused", startedAt)                // [R M-10]

        val linkGen = linkGens.incrementAndGet()
        val owner = "native-$linkGen"
        if (!WifiRadio.reserveNdp(owner)) throw nativeFail(role, "no_free_path", startedAt)

        WifiDiscovery.beginDialIntent()
        discovery.beginDial()
        var attemptRef: DialAttempt? = null
        var success = false
        try {
            val sighting = findSighting(key, startedAt + deadlineMs, cancelled)
                ?: throw nativeFail(role, if (cancelled()) "cancelled" else "peer_gone", startedAt)
            val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val attempt = DialAttempt(
                key = key,
                nonceHex = nonce.joinToString("") { "%02x".format(it) },
                machine = NativeKnockMachine(nonce, startedAt),
                handle = sighting.handle as PeerHandle,
                subGen = sighting.sessionGeneration,
                epoch = sighting.epoch,
                linkGen = linkGen,
                result = CompletableFuture(),
                startedAtMs = startedAt,
            )
            attemptRef = attempt
            WifiRadio.handler.post { beginAttempt(attempt) }

            val (socket, info) = when (
                val outcome = NativeDialWait.await(attempt.result, startedAt + deadlineMs, SystemClock::elapsedRealtime, cancelled)
            ) {
                is NativeDialWait.Outcome.Done -> outcome.value
                NativeDialWait.Outcome.TimedOut -> throw nativeFail(role, "attempt_timeout", startedAt)
                NativeDialWait.Outcome.Cancelled -> throw nativeFail(role, "cancelled", startedAt)
                is NativeDialWait.Outcome.Failed -> throw (outcome.cause as? IOException ?: IOException("native_fail:datapath_failed"))
            }
            val exchange = attempt.exchange
            success = true
            log(
                "native_up",
                "initiator",
                "setupMs" to (SystemClock.elapsedRealtime() - startedAt),
                "peer" to WifiLog.hash(info.peerAddress.hostAddress ?: ""),
                "chMhz" to info.channelsMhz?.firstOrNull(),
            )
            return WifiNativeConnection(
                socket.getInputStream(),
                socket.getOutputStream(),
                { exchange?.closeConnection() },
                WifiLog.hash(info.peerAddress.hostAddress ?: ""),
                admitted = { WifiRadio.handler.post { markAdmitted(linkGen) } },   // [AM 12]
            )
        } finally {
            if (!success) {
                val attempt = attemptRef
                if (attempt == null) {
                    WifiRadio.releaseNdp(owner)
                } else {
                    WifiRadio.handler.post {
                        val exchange = attempt.exchange
                        abandonAttempt(attempt)   // a cancel or timeout: the knock machine stops now, not at no_answer
                        when {
                            exchange == null -> WifiRadio.releaseNdp(owner)
                            exchange.hasSocket() -> exchange.closeConnection()
                            else -> exchange.cancel("attempt_timeout")
                        }
                    }
                }
            }
            discovery.endDial()
            WifiDiscovery.endDialIntent()
        }
    }

    /** The freshest sighting of the live subscribe session, else poll every 100 ms up to DIAL_WAIT_FOR_SIGHTING_MS (and the dial's deadline). [AM 3] */
    private fun findSighting(key: String, deadlineAtMs: Long, cancelled: () -> Boolean): WifiSighting? {
        var sighting = discovery.latest(key)?.takeIf { it.sessionGeneration == discovery.liveSessionGeneration() }
        if (sighting != null) return sighting
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < WifiLimits.DIAL_WAIT_FOR_SIGHTING_MS &&
            SystemClock.elapsedRealtime() < deadlineAtMs && !cancelled()
        ) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            sighting = discovery.latest(key)?.takeIf { it.sessionGeneration == discovery.liveSessionGeneration() }
            if (sighting != null) return sighting
        }
        return null
    }

    /** [AM 6] answers arrive on the discovery subscribe session; `gen` is its subscribe generation. */
    fun onAnswer(handle: PeerHandle, bytes: ByteArray, gen: Long) {
        val answer = runCatching { WifiWire.decodeMwa1(bytes) }.getOrNull() ?: return
        val attempt = pendingByNonce[answer.nonce.joinToString("") { "%02x".format(it) }] ?: return
        if (attempt.subGen != gen || attempt.handle != handle) return
        dispatchAttempt(attempt, attempt.machine.onAnswer(answer.nonce, answer.port))
    }

    private fun beginAttempt(attempt: DialAttempt) {
        if (attempt.result.isDone) return
        if (discovery.subscribeSessionOnHandler() == null) {
            failAttempt(attempt, "peer_gone")
            return
        }
        pendingByNonce[attempt.nonceHex] = attempt
        when (val action = attempt.machine.begin()) {
            is NativeKnockMachine.Action.SendKnock -> sendKnock(attempt, action.attempt)
            is NativeKnockMachine.Action.Fail -> failAttempt(attempt, action.reason)
            else -> Unit
        }
        scheduleTick(attempt)
    }

    private fun scheduleTick(attempt: DialAttempt) {
        val tag = attempt.tickTag
        WifiRadio.scheduler.cancel(tag)
        WifiRadio.scheduler.postDelayed(tag, 250L) {
            if (attempt.result.isDone) return@postDelayed
            when (val action = attempt.machine.onTick(SystemClock.elapsedRealtime())) {
                is NativeKnockMachine.Action.SendKnock -> {
                    sendKnock(attempt, action.attempt)
                    scheduleTick(attempt)
                }
                is NativeKnockMachine.Action.Fail -> failAttempt(attempt, action.reason)
                else -> scheduleTick(attempt)
            }
        }
    }

    private fun sendKnock(attempt: DialAttempt, n: Int) {
        if (attempt.result.isDone) return
        val session = discovery.subscribeSessionOnHandler()
        if (session == null) {
            failAttempt(attempt, "peer_gone")
            return
        }
        if (messagesInFlight >= WifiRadioLimits.MAX_MESSAGES_IN_FLIGHT) {
            dispatchAttempt(attempt, attempt.machine.onSendResult(false))
            return
        }
        val id = messageIds.getAndIncrement()
        dials[id] = attempt
        messagesInFlight++
        try {
            session.sendMessage(attempt.handle, id, Mwk1(attempt.machine.nonce).encode())
        } catch (e: RuntimeException) {
            completeSend(id, false)   // [AM 6]
            return
        }
        armMessageWatchdog(id)
        log("knock", "sent", "role" to "initiator", "n" to n)
    }

    private fun dispatchAttempt(attempt: DialAttempt, action: NativeKnockMachine.Action) {
        when (action) {
            is NativeKnockMachine.Action.RequestDataPath -> requestDataPath(attempt, action.port)
            is NativeKnockMachine.Action.Fail -> failAttempt(attempt, action.reason)
            else -> Unit
        }
    }

    /** [answerPort] is the responder's port from its `MWA1` answer (1 to 65535; checked by the knock machine). */
    private fun requestDataPath(attempt: DialAttempt, answerPort: Int) {
        val session = discovery.subscribeSessionOnHandler()
        if (session == null) {
            failAttempt(attempt, "peer_gone")
            return
        }
        pendingByNonce.remove(attempt.nonceHex)
        WifiRadio.scheduler.cancel(attempt.tickTag)
        val pmk = WifiKeys.nativePmk(attempt.epoch, attempt.machine.nonce)
        val exchange = NativeExchange(attempt.linkGen, true, attempt.handle, null, session, pmk, DialCallbacks(attempt), answerPort)
        exchange.link = WifiRadio.newLink(attempt.linkGen, exchange)
        attempt.exchange = exchange
        liveLinks.incrementAndGet()
        exchanges[attempt.linkGen] = exchange
        exchange.start()
    }

    /** The dialler gave up (already logged): stop knocking without a second `native_fail` line. */
    private fun abandonAttempt(attempt: DialAttempt) {
        if (attempt.result.isDone) return
        pendingByNonce.remove(attempt.nonceHex)
        WifiRadio.scheduler.cancel(attempt.tickTag)
        attempt.result.cancel(false)
    }

    private fun failAttempt(attempt: DialAttempt, reason: String) {
        if (attempt.result.isDone) return
        pendingByNonce.remove(attempt.nonceHex)
        WifiRadio.scheduler.cancel(attempt.tickTag)
        log(
            "native_fail",
            "initiator",
            "ms" to (SystemClock.elapsedRealtime() - attempt.startedAtMs),
            "reason" to reason,
        )
        attempt.result.completeExceptionally(IOException("native_fail:$reason"))
        val exchange = attempt.exchange
        if (exchange != null && !exchange.hasSocket()) exchange.cancel(reason)
    }

    private inner class DialCallbacks(private val attempt: DialAttempt) : NativeExchange.Callbacks {
        override fun onArmed(gen: Long, port: Int) = Unit

        override fun onConnected(gen: Long, socket: Socket, info: AwareDataPathInfo) {
            attempt.result.complete(socket to info)
        }

        override fun onFailed(gen: Long, reason: String) {
            if (attempt.result.isDone) return
            log(
                "native_fail",
                "initiator",
                "ms" to (SystemClock.elapsedRealtime() - attempt.startedAtMs),
                "reason" to reason,
            )
            attempt.result.completeExceptionally(IOException("native_fail:$reason"))
        }

        override fun onLost(gen: Long) { attempt.exchange?.closeConnection() }

        override fun onClosed(gen: Long) {
            liveLinks.decrementAndGet()
            exchanges.remove(gen)
            discovery.onLinksChanged()
        }
    }

    // --- accept (responder) ---------------------------------------------------------------------------------------

    fun onKnock(session: PublishDiscoverySession, handle: PeerHandle, bytes: ByteArray, epoch: Long, gen: Long) {
        val nonce = runCatching { WifiWire.decodeMwk1(bytes).nonce }.getOrNull()
        if (nonce == null) {
            log("knock", "bad_knock", "role" to "responder")
            return
        }
        if (!WifiDiscovery.widerReachOn()) {
            log("knock", "refused_switch_off", "role" to "responder")   // [AM 21]
            return
        }
        if (!NativeBandRule.canVerify(Build.VERSION.SDK_INT)) {
            log("knock", "refused_band_unverified", "role" to "responder")   // 5 GHz only, unverifiable here (A8 review P2-3)
            return
        }
        val context = NativeResponderPolicy.Context(
            eligible = eligibility().aware,
            accepting = registry.currentAcceptor() != null,
            freeDataPaths = freeDataPaths(),
            upgradeBusy = WifiRadio.activeCount() - liveLinks() > 0,
        )
        val hex = nonce.joinToString("") { "%02x".format(it) }
        val now = SystemClock.elapsedRealtime()
        when (val decision = responders.onKnock(handle.hashCode().toString(), nonce, now, context)) {
            is NativeResponderPolicy.Decision.Refuse -> {
                log("knock", "refused_${decision.reason}", "role" to "responder")
                return
            }
            is NativeResponderPolicy.Decision.Resend -> {
                sendAnswer(session, handle, nonce, decision.port)
                log("knock", "resend", "role" to "responder")
                return
            }
            is NativeResponderPolicy.Decision.Serve -> Unit
        }
        if (registry.currentAcceptor() == null) {
            responders.finished(hex)
            return
        }
        val linkGen = linkGens.incrementAndGet()
        val owner = "native-$linkGen"
        val built = AtomicReference<NativeExchange?>(null)
        // Registered BEFORE the reservation, so a preemption racing it always finds this stranger. The ledger may run
        // the preemption on another thread; NativeAdmission ends the exchange on the wifi thread (A8 review P1-3).
        admission.reserved(linkGen, owner) {
            val exchange = built.get()
            when {
                exchange == null -> responders.finished(hex)
                exchange.hasSocket() -> exchange.closeConnection()
                else -> exchange.cancel("preempted")
            }
            log("native_fail", "responder", "reason" to "preempted")
        }
        if (!WifiRadio.reserveUntrustedNdp(owner) { admission.preemptPending() }) {   // [AM 12] F-3 ledger
            admission.closed(linkGen)
            responders.finished(hex)
            log("native_fail", "responder", "reason" to "no_free_path")
            return
        }
        val pmk = WifiKeys.nativePmk(epoch, nonce)
        val callbacks = ResponderCallbacks(hex, nonce, session, handle)
        val exchange = NativeExchange(linkGen, false, handle, session, null, pmk, callbacks, answerPort = 0)
        exchange.link = WifiRadio.newLink(linkGen, exchange)
        callbacks.exchange = exchange
        liveLinks.incrementAndGet()
        exchanges[linkGen] = exchange
        serving[hex] = Serving(hex, handle.hashCode().toString(), linkGen, exchange)
        built.set(exchange)
        exchange.start()
    }

    private inner class ResponderCallbacks(
        private val hex: String,
        private val nonce: ByteArray,
        private val session: PublishDiscoverySession,
        private val handle: PeerHandle,
    ) : NativeExchange.Callbacks {
        lateinit var exchange: NativeExchange

        override fun onArmed(gen: Long, port: Int) {
            responders.armed(hex, port)
            sendAnswer(session, handle, nonce, port)
            log("knock", "served", "role" to "responder")
        }

        override fun onConnected(gen: Long, socket: Socket, info: AwareDataPathInfo) {
            log(
                "native_up",
                "responder",
                "peer" to WifiLog.hash(info.peerAddress.hostAddress ?: ""),
                "chMhz" to info.channelsMhz?.firstOrNull(),
            )
            exchange.markSocket(socket)
            admission.accepted(gen, exchange.ndpOwner) {   // [AM 12] F-3
                log("native_fail", "responder", "reason" to "admission_timeout")
                exchange.closeConnection()
            }
            val connection = WifiNativeConnection(
                socket.getInputStream(),
                socket.getOutputStream(),
                { exchange.closeConnection() },
                WifiLog.hash(info.peerAddress.hostAddress ?: ""),
                admitted = { WifiRadio.handler.post { onAdmitted(gen, hex) } },
            )
            thread(name = "WifiNativeAccept", isDaemon = true) {
                val acceptor = registry.currentAcceptor()
                if (acceptor != null) acceptor(connection) else connection.close()
            }
        }

        override fun onFailed(gen: Long, reason: String) {
            responders.finished(hex)
            log("native_fail", "responder", "reason" to reason)
        }

        override fun onLost(gen: Long) { exchange.closeConnection() }

        override fun onClosed(gen: Long) {
            responders.finished(hex)
            admission.closed(gen)
            serving.remove(hex)
            liveLinks.decrementAndGet()
            exchanges.remove(gen)
            discovery.onLinksChanged()   // [AM 8][AM 10]
        }
    }

    private fun sendAnswer(session: PublishDiscoverySession, handle: PeerHandle, nonce: ByteArray, port: Int) {
        if (messagesInFlight >= WifiRadioLimits.MAX_MESSAGES_IN_FLIGHT) return
        val id = messageIds.getAndIncrement()
        answers += id
        messagesInFlight++
        try {
            session.sendMessage(handle, id, Mwa1(nonce, port).encode())
        } catch (e: RuntimeException) {
            completeSend(id, false)   // [AM 6]
            return
        }
        armMessageWatchdog(id)
    }

    private fun armMessageWatchdog(id: Int) {
        val tag = "a8-msg-$id"
        WifiRadio.scheduler.cancel(tag)
        WifiRadio.scheduler.postDelayed(tag, WifiRadioLimits.MESSAGE_WATCHDOG_MS) { completeSend(id, false) }
    }

    /** The first of a send result and its watchdog, for a knock (routed to its machine) or an answer (slot only). */
    fun onSendResult(id: Int, ok: Boolean) { completeSend(id, ok) }

    private fun completeSend(id: Int, ok: Boolean) {
        WifiRadio.scheduler.cancel("a8-msg-$id")
        val attempt = dials.remove(id)
        val answer = answers.remove(id)
        if (attempt == null && !answer) return
        if (messagesInFlight > 0) messagesInFlight--
        if (attempt != null) dispatchAttempt(attempt, attempt.machine.onSendResult(ok))
    }

    private fun onAdmitted(linkGen: Long, hex: String) {
        admission.admitted(linkGen)
        responders.admitted(hex)
        val exchange = exchanges[linkGen]
        if (exchange != null) {
            exchange.admitted = true
            WifiRadio.trustNdp(exchange.ndpOwner)
        }
        discovery.onLinksChanged()
    }

    private fun markAdmitted(linkGen: Long) {
        val exchange = exchanges[linkGen] ?: return
        if (!exchange.admitted) {
            exchange.admitted = true
            discovery.onLinksChanged()
        }
    }

    /** Fails every pending dial and cancels every exchange without a socket; an established connection stays. [R M-10] */
    fun cancelAll(reason: String) {
        val attempts = pendingByNonce.values.toSet()
        pendingByNonce.clear()
        attempts.forEach { attempt ->
            WifiRadio.scheduler.cancel(attempt.tickTag)
            log("native_fail", "initiator", "ms" to (SystemClock.elapsedRealtime() - attempt.startedAtMs), "reason" to reason)
            attempt.result.completeExceptionally(IOException("native_fail:$reason"))
        }
        exchanges.values.toList().forEach { if (it.hasSocket()) Unit else it.cancel(reason) }
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private fun nativeFail(role: String, reason: String, startedAt: Long): IOException {
        log("native_fail", role, "ms" to (SystemClock.elapsedRealtime() - startedAt), "reason" to reason)
        return IOException("native_fail:$reason")
    }


    private fun log(event: String, reason: String, vararg extra: Pair<String, Any?>) {
        val fields = ArrayList<Pair<String, Any?>>(3 + extra.size)
        fields += "reason" to reason
        fields += extra
        WifiLog.emit(WifiLog.discovery(event, *fields.toTypedArray()))
    }
}

/**
 * One native data path: a thin adapter around one A4 [AwareLink], so "exactly one `requestNetwork` per link" holds.
 * Everything except [closeConnection] runs on the wifi thread; [closeConnection] may be called from any thread.
 * [API A4.10.6][X13]
 */
internal class NativeExchange(
    val gen: Long,                                              // WifiNativeLink.linkGens: the AwareLink generation and the AwareSockets owner id
    private val initiator: Boolean,
    private val peerHandle: Any,                                // the discovery PeerHandle (Any: only AwareLink.peerFor reads it)
    private val publishSession: PublishDiscoverySession?,       // responder
    private val subscribeSession: SubscribeDiscoverySession?,   // initiator
    private var pmk: ByteArray?,                                // wiped once the request is filed
    private val callbacks: Callbacks,
    /**
     * Initiator: the responder's port from its `MWA1` answer. Used when the platform reports no port for the data path
     * (`WifiAwareNetworkInfo.getPort()` is 0, as on the S22 with an S24 responder); a nonzero platform port is used as given.
     * Responder: 0 (it draws its own random port, there is no UW before BMH3).
     */
    private val answerPort: Int,
) : AwareLinkEventsAdapter() {
    interface Callbacks {
        fun onArmed(gen: Long, port: Int)                                       // responder: requestResponder returned true; send MWA1 now
        fun onConnected(gen: Long, socket: Socket, info: AwareDataPathInfo)     // initiator connected or responder accepted (once)
        fun onFailed(gen: Long, reason: String)                                 // before a socket exists (once)
        fun onLost(gen: Long)                                                   // after onConnected: the data path or the attach is gone
        fun onClosed(gen: Long)                                                 // the link is closed, exactly once on every path (liveLinks bookkeeping)
    }

    val ndpOwner = "native-$gen"
    lateinit var link: AwareLink              // WifiRadio.newLink(gen, this), assigned right after construction

    /** BMH3/BMH2 admission completed on this socket (F-3). [AM 12] */
    @Volatile var admitted = false

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    private var info: AwareDataPathInfo? = null
    private var responderPort = 0
    private val timerTag = "a8-exchange-$gen"

    /** Wifi thread: start the attach and the data-path request. */
    fun start() { link.acquire() }

    fun hasSocket(): Boolean = socket != null

    fun markSocket(socket: Socket) { this.socket = socket }

    /** Wifi thread: fail while no socket exists; no-op afterwards. */
    fun cancel(reason: String) {
        if (closed.get() || socket != null) return
        fail(reason)
    }

    /** Any thread, idempotent: the connection's close lambda. [AM 15] */
    fun closeConnection() {
        if (!closed.compareAndSet(false, true)) return
        AwareSockets.close(socket)
        WifiRadio.releaseNdp(ndpOwner)
        WifiRadio.handler.post {
            link.close("connection_closed")
            callbacks.onClosed(gen)
        }
    }

    // --- AwareLinkEvents ------------------------------------------------------------------------------------------

    override fun onAttached(gen: Long) {
        if (closed.get()) return
        if (initiator) {
            link.adoptSessions(null, subscribeSession)
            val ok = link.requestInitiator(link.peerFor(peerHandle), pmk ?: ByteArray(32), WifiLimits.DATA_PATH_TIMEOUT_MS.toInt())
            wipePmk()
            if (!ok) fail("datapath_failed")
        } else {
            link.adoptSessions(publishSession, null)
            responderPort = AwarePorts.randomPort()
            val ok = link.requestResponder(link.peerFor(peerHandle), pmk ?: ByteArray(32), responderPort, WifiLimits.DATA_PATH_TIMEOUT_MS.toInt())
            wipePmk()
            if (!ok) fail("datapath_failed") else callbacks.onArmed(gen, responderPort)
        }
    }

    override fun onAttachFailed(gen: Long, reason: AttachFailure) { fail("datapath_failed") }

    override fun onDataPathReady(gen: Long, info: AwareDataPathInfo) {
        if (closed.get()) return
        // 5 GHz only: a 2.4 GHz data path halves Bluetooth (D9), and an unreadable band is refused (A8 review P2-3).
        // AP10: a Wi-Fi-native link has no hop owner here that could say what else this phone carries on Bluetooth,
        // so other Bluetooth media is assumed and the 2.4 GHz-alone rule never applies; the facts are logged.
        val strict = WifiDebugArms.bandStrict()
        val judgement = AwareBand.judge(info.channelsMhz, strict, alone24 = false, otherBluetoothMedia = true)
        WifiLog.emit(
            WifiLog.discovery(
                "native_band",
                "chMhz" to info.channelsMhz?.joinToString(","),
                "verdict" to judgement.verdict.name.lowercase(),
                "strict" to strict,
                "alone24" to false,
                "otherBtMedia" to "assumed",
                "band" to judgement.rule.log,
            ),
        )
        NativeBandRule.refusal(judgement.verdict)?.let { reason ->
            fail(reason)
            return
        }
        if (initiator) {
            val platform = info.port != 0
            val path = if (platform) info else info.copy(port = answerPort)
            // The source and whether the platform agrees with MWA1: a boolean only, never the port itself.
            WifiLog.emit(
                if (platform) {
                    WifiLog.discovery("native_port", "role" to "initiator", "source" to "platform", "match" to (info.port == answerPort))
                } else {
                    WifiLog.discovery("native_port", "role" to "initiator", "source" to if (answerPort != 0) "mwa1" else "none")
                },
            )
            this.info = path   // a port still 0 ends in AwareLink.connect as `no_port`, as before
            link.connect(path, WifiLimits.SOCKET_CONNECT_TIMEOUT_MS, WifiRadioLimits.CONNECT_WINDOW_MS)
        } else {
            this.info = info
            link.listen(info, responderPort, 1, WifiRadioLimits.CONNECT_WINDOW_MS)
            WifiRadio.scheduler.cancel(timerTag)
            WifiRadio.scheduler.postDelayed(timerTag, WifiRadioLimits.CONNECT_WINDOW_MS + 500L) { fail("accept_timeout") }
        }
    }

    override fun onConnected(gen: Long, socket: Socket, connectedAtNs: Long) = connected(socket)

    override fun onSocketAccepted(gen: Long, socket: Socket, ordinal: Int, acceptedAtNs: Long) = connected(socket)

    override fun onSocketRejected(gen: Long, reason: String) = Unit   // a wrong peer address is not counted

    override fun onNetworkUnavailable(gen: Long, elapsedMs: Long, timedOut: Boolean) { fail("datapath_failed") } // never a phone-wide fault, F-2

    override fun onNetworkLost(gen: Long) { fail("datapath_lost") }

    override fun onAwareDown(gen: Long, reason: AwareDownReason) { fail("datapath_lost") }

    override fun onConnectFailed(gen: Long, reason: String, refusedOnly: Boolean) { fail("connect_failed") }

    override fun onServerFailed(gen: Long, reason: String) { fail("datapath_failed") }

    private fun connected(socket: Socket) {
        if (closed.get()) {
            AwareSockets.close(socket)
            return
        }
        val path = info
        if (path == null) {
            AwareSockets.close(socket)
            fail("datapath_failed")
            return
        }
        WifiRadio.scheduler.cancel(timerTag)
        link.handOff { callbacks.onLost(gen) }
        this.socket = socket
        callbacks.onConnected(gen, socket, path)
    }

    /** Wifi thread, idempotent through the same flag as [closeConnection]. */
    private fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        WifiRadio.scheduler.cancel(timerTag)
        link.close(reason)
        WifiRadio.releaseNdp(ndpOwner)
        wipePmk()
        callbacks.onFailed(gen, reason)
        callbacks.onClosed(gen)
    }

    private fun wipePmk() {
        pmk?.fill(0)
        pmk = null
    }
}
