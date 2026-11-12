package com.example.bluetoothtest

import java.net.Socket
import java.security.MessageDigest

/** What the agent keeps of a handed-off carrier (ticket = its own copy). */
internal class HandedCarrier(
    val id: Int,
    val bandVerified: Boolean,
    val channelMhz: Int?,
    val ticket: ByteArray,
    /** AP9: the bearer of the handed carrier; the Aware goodput guard applies only to AWARE. */
    val bearer: WifiBearer = WifiBearer.AWARE,
    /** AP10: the data path was accepted by the 2.4 GHz-alone rule (every one of its channels is in the 2.4 GHz band). */
    val band24Alone: Boolean = false,
)

/**
 * One attempt: the pure [AttemptModel] plus its executor. Everything runs on the wifi thread; blocking work (accept,
 * connect, the BWU1 exchange) runs on short-lived worker threads that post their result back.
 */
internal class WifiAttempt(
    val role: AttemptRole,
    val generation: Long,
    val ticket: ByteArray,
    private val uw: ByteArray,
    private val decision: WifiEligibility.Decision,
    private val env: WifiEnv,
    private val ports: WifiHopPorts,
    private val icm: Boolean,
    private val ndpOwner: String?,
    private val bearer: WifiBearer = WifiBearer.AWARE,
    private val hostFreqMhz: Int = 0,
    /** Called once, after the attempt released itself, when an exception escaped its own code (class name only). */
    private val onInternalError: (errorClass: String) -> Unit = {},
    private val onEnded: (Outcome, setupMs: Long?, handed: HandedCarrier?) -> Unit,
) : AwareLinkEvents, BearerEvents {
    private val level = if (decision.acceptsUnverifiedBand) "wider" else "private"
    /** AP9 DEBUG `test_aware_band_strict`: the old all-channels-5GHz Aware band rule. */
    private val bandStrict = WifiDebugArms.bandStrict()
    private val model = AttemptModel(
        role, generation, decision.acceptsUnverifiedBand, env.random()::nextBytes, bearer, bandStrict,
    )
    private val gate = TicketGate(ticket)
    /**
     * PROTOCOL "Keys, names and passwords" `port` (promoted 2026-11-10, W5): the Aware host's port comes from UW and the ticket, so a
     * guest whose platform reports none can still connect. A DEBUG launch with `test_wifi_aware_derived_port=false` is the control
     * arm (host draws a random port, a guest with no platform port ends `no_port`); a release build keeps the rule.
     */
    private val derivedPort = bearer == WifiBearer.AWARE && env.facts().derivedPort
    private val port = if (bearer == WifiBearer.AWARE && role == AttemptRole.HOST) {
        if (derivedPort) WifiKeys.port(uw, ticket) else AwarePorts.randomPort(env.random())
    } else {
        0
    }
    private val sockets = HashMap<Int, Pair<Socket, Long>>()
    private val peers = HashMap<Int, AwarePeer>()
    private var pathInfo: AwareDataPathInfo? = null

    /** AP10: the rule that judged this attempt's data path (set when the path came up). */
    private var bandRule: AwareBand.Rule? = null

    @Volatile
    private var bound: WifiBinding.Bound? = null
    private val queue = ArrayDeque<AttemptEvent>()
    private var running = false
    private var ended = false
    private var startedAtMs = 0L
    private var hostSentAtMs = 0L
    private var readyFreqMhz = 0
    private var joinedFreqMhz: Int? = null
    private val knockNonces = HashMap<Int, ByteArray>()
    private val timerTags = HashMap<TimerKind, Any>()
    private var handed: HandedCarrier? = null
    private var aborted = false

    private val link: AwareLink? = if (bearer == WifiBearer.AWARE) env.newLink(generation, this) else null
    private val driver: BearerDriver? =
        if (bearer == WifiBearer.AWARE) null else env.newBearerDriver(bearer, generation, "wifi-$generation", this)

    /** Dispatches `Start` synchronously on the wifi thread (no post), so an attempt created inside `onHost` is past IDLE
     * before the next posted record (CREDS right behind HOST) is handled. [Am 15] */
    fun start() {
        check(env.scheduler.isWifiThread()) { "WifiAttempt.start on the wrong thread" }
        startedAtMs = env.scheduler.nowMs()
        dispatch(Start(generation))
    }

    fun dispatch(event: AttemptEvent) {
        check(env.scheduler.isWifiThread()) { "WifiAttempt.dispatch on the wrong thread" }
        if (ended) return
        queue.addLast(event)
        if (running) return
        running = true
        var failure: Exception? = null
        try {
            while (queue.isNotEmpty()) {
                val effects = model.handle(queue.removeFirst())
                for (effect in effects) perform(effect)
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            running = false
        }
        failure?.let { abort(it) }
    }

    /**
     * Runs [block] on the wifi thread with a catch: an exception that escapes the attempt's own code must never reach
     * the Looper (an uncaught exception there ends the whole process, the Bluetooth call too). [P1-1]
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            abort(error)
        }
    }

    /**
     * An internal error: log the class name only (never a message, which may carry an address or a path), tell the
     * peer, release everything exactly once through the normal release contract and report to the agent, which ends
     * Wi-Fi for the hop. Bluetooth is never touched.
     */
    private fun abort(error: Exception) {
        if (aborted) return
        aborted = true
        queue.clear()
        val errorClass = error.javaClass.simpleName.ifEmpty { "Exception" }
        runCatching { log("internal_error", "error" to errorClass) }
        if (!ended) {
            runCatching { ports.sendRecord(Bwf.Decline(ticket, 1)) }
            runCatching { release(Outcome(OutcomeKind.CANCELLED, "internal_error")) }
        }
        runCatching { onInternalError(errorClass) }
    }

    fun onPick(ticketMatches: Boolean, challenge: ByteArray) {
        dispatch(PickIn(generation, ticketMatches, challenge))
    }

    fun onArmed(ticketMatches: Boolean) {
        dispatch(ArmedIn(generation, ticketMatches))
    }

    fun onDecline(ticketMatches: Boolean, reason: Int) {
        dispatch(DeclineIn(generation, ticketMatches, reason))
    }

    fun onCreds(ticketMatches: Boolean, port: Int, ipv4: ByteArray) {
        dispatch(CredsIn(generation, ticketMatches, port, ipv4.copyOf()))
    }

    fun command(cmd: Cmd) {
        dispatch(Command(generation, cmd))
    }

    // ----- effects -----

    private fun perform(effect: AttemptEffect) {
        when (effect) {
            AcquireAttach -> link?.acquire()
            is StartSession -> startSession(effect.role)
            SendHost -> sendHost()
            is SendDecline -> {
                ports.sendRecord(Bwf.Decline(ticket, effect.reason))
                log("decline", "dir" to "sent", "reason" to effect.reason)
            }
            is SendPick -> if (!ports.sendRecord(Bwf.Pick(ticket, effect.challenge))) {
                dispatch(SendFailed(generation, "pick"))
            }
            SendArmed -> if (!ports.sendRecord(Bwf.Armed(ticket))) {
                dispatch(SendFailed(generation, "armed"))
            }
            is SendKnock -> sendKnock(effect.ordinal, effect.gN)
            is SendChallenge -> sendChallenge(effect.ordinal, effect.gN, effect.c)
            is RequestPath -> requestPath(effect.responder, effect.ordinal)
            Listen -> pathInfo?.let { info ->
                link?.listen(info, port, WifiLimits.MAX_ACCEPTED_SOCKETS_PER_ATTEMPT, WifiRadioLimits.CONNECT_WINDOW_MS)
            }
            Connect -> pathInfo?.let { info ->
                link?.connect(info, WifiLimits.SOCKET_CONNECT_TIMEOUT_MS, WifiRadioLimits.CONNECT_WINDOW_MS)
            }
            is StartBind -> startBind(effect.ordinal)
            is CloseSocket -> AwareSockets.close(sockets.remove(effect.ordinal)?.first)
            is HandOff -> handOff(effect.ordinal)
            is Arm -> arm(effect.kind, effect.ms)
            is Cancel -> cancel(effect.kind)
            is LogEvent -> env.log(WifiLog.upgrade(effect.event, *commonFieldsWith(effect.fields)))
            is End -> release(effect.outcome)
            PrepareBearer -> prepareBearer()
            StartBearerHost -> startBearerHost()
            StartBearerGuest -> startBearerGuest()
            is SendCreds -> {
                if (!ports.sendRecord(Bwf.Creds(ticket, effect.port, effect.ipv4))) {
                    dispatch(SendFailed(generation, "creds"))
                } else {
                    log("creds", "dir" to "sent")
                }
            }
            is ConnectBearer -> driver?.connect(effect.ipv4, effect.port)
        }
    }

    private fun startSession(sessionRole: AttemptRole) {
        val link = link ?: return
        val service = WifiKeys.serviceName(uw, ticket)
        val ttl = (WifiLimits.ATTEMPT_MAX_MS / 1_000).toInt()
        val icmOn = icm && link.caps.instantCommunicationSupported
        // Experimental test_wifi_icm: whether this phone supports and applied the mode (awi-20261111 could not tell).
        if (icm) log("icm", "supported" to link.caps.instantCommunicationSupported, "applied" to icmOn)
        if (sessionRole == AttemptRole.HOST) link.publish(service, ttl, icmOn) else link.subscribe(service, ttl, icmOn)
    }

    private fun sendHost() {
        val record = if (bearer == WifiBearer.AWARE) {
            Bwf.Host(1, WifiWire.BEARER_AWARE, ticket, WifiLimits.HOST_VALID_MS, 0)
        } else {
            val freq = if (readyFreqMhz >= WifiLimits.MIN_5GHZ_MHZ) readyFreqMhz else 0
            Bwf.Host(1, WifiWire.BEARER_DIRECT, ticket, WifiLimits.HOST_VALID_MS, freq)
        }
        hostSentAtMs = env.scheduler.nowMs()
        if (!ports.sendRecord(record)) dispatch(SendFailed(generation, "host"))
    }

    private fun sendKnock(ordinal: Int, gN: ByteArray) {
        val link = link ?: return
        val peer = peers[ordinal] ?: return
        knockNonces[ordinal] = gN.copyOf()
        val ok = link.sendMessage(peer, WifiKeys.knock(uw, ticket, gN))
        log("knock", "dir" to "sent", "valid" to true, "result" to if (ok) "ok" else "fail")
    }

    private fun sendChallenge(ordinal: Int, gN: ByteArray, c: ByteArray) {
        val link = link ?: return
        val peer = peers[ordinal] ?: return
        val ok = link.sendMessage(peer, WifiKeys.awareChallenge(uw, ticket, gN, c))
        log("challenge", "dir" to "sent", "result" to if (ok) "ok" else "fail")
    }

    private fun requestPath(responder: Boolean, ordinal: Int) {
        val link = link ?: return
        val peer = peers[ordinal]
        if (peer == null) {
            dispatch(RequestFiled(generation, false))
            return
        }
        val pmk = WifiKeys.pmk(uw, ticket)
        val ok = try {
            if (responder) {
                log("port", "source" to if (derivedPort) "derived" else "random")
                link.requestResponder(peer, pmk, port, WifiLimits.DATA_PATH_TIMEOUT_MS.toInt())
            } else {
                link.requestInitiator(peer, pmk, WifiLimits.DATA_PATH_TIMEOUT_MS.toInt())
            }
        } finally {
            pmk.fill(0)
        }
        dispatch(RequestFiled(generation, ok))
        log("ndp_request", "role" to if (responder) "responder" else "initiator")
    }

    private fun startBind(ordinal: Int) {
        val entry = sockets[ordinal] ?: return
        val socket = entry.first
        val atNs = entry.second
        val uwLocal = uw.copyOf()
        val ready = ports.localReady()
        val thread = Thread({
            val result: Any = try {
                if (role == AttemptRole.HOST) {
                    env.binder().host(socket, atNs, uwLocal, gate, ticket, ready)
                } else {
                    env.binder().guest(socket, atNs, uwLocal, ticket, ready)
                }
            } catch (error: Exception) {
                if (role == AttemptRole.HOST) {
                    // The ticket may already be spent: report that honestly so the attempt ends now. [P3-1]
                    if (gate.consumed) WifiBinding.HostResult.FailedAfterTicket("exception") else WifiBinding.HostResult.Rejected("frame2")
                } else {
                    WifiBinding.GuestResult.Failed("frame1")
                }
            } finally {
                uwLocal.fill(0)
            }
            env.scheduler.post { guarded { onBindResult(ordinal, socket, result) } }
        }, "wifi-bind-$generation-$ordinal")
        thread.isDaemon = true
        thread.start()
    }

    private fun onBindResult(ordinal: Int, socket: Socket, result: Any) {
        if (ended) {
            val okBound = when (result) {
                is WifiBinding.HostResult.Ok -> result.bound
                is WifiBinding.GuestResult.Ok -> result.bound
                else -> null
            }
            okBound?.let {
                runCatching { it.reader.close() }
                runCatching { it.writer.close() }
            }
            AwareSockets.close(socket)
            return
        }
        when (result) {
            is WifiBinding.HostResult.Ok -> {
                bound = result.bound
                dispatch(BindFinished(generation, ordinal, BindCode.BOUND, "ok"))
            }
            is WifiBinding.HostResult.Rejected -> {
                AwareSockets.close(socket)
                dispatch(BindFinished(generation, ordinal, BindCode.REJECTED_BEFORE_TICKET, result.stage))
            }
            is WifiBinding.HostResult.FailedAfterTicket -> {
                AwareSockets.close(socket)
                dispatch(BindFinished(generation, ordinal, BindCode.FAILED_AFTER_TICKET, result.stage))
            }
            is WifiBinding.GuestResult.Ok -> {
                bound = result.bound
                dispatch(BindFinished(generation, ordinal, BindCode.BOUND, "ok"))
            }
            is WifiBinding.GuestResult.Failed -> {
                AwareSockets.close(socket)
                dispatch(BindFinished(generation, ordinal, BindCode.REJECTED_BEFORE_TICKET, result.stage))
            }
        }
    }

    private fun handOff(ordinal: Int) {
        val b = bound
        if (b == null) {
            dispatch(HandOffResult(generation, false))
            return
        }
        AwareSockets.closeOwned(generation, except = b.socket)
        val carrierKind = bearer.carrierKind()
        val freq = if (role == AttemptRole.HOST) {
            readyFreqMhz
        } else {
            joinedFreqMhz?.takeIf { it > 0 } ?: hostFreqMhz.takeIf { it >= WifiLimits.MIN_5GHZ_MHZ } ?: 0
        }
        val bandVerified = if (bearer == WifiBearer.AWARE) {
            pathInfo?.channelsMhz != null
        } else {
            freq >= WifiLimits.MIN_5GHZ_MHZ
        }
        val channelMhz = if (bearer == WifiBearer.AWARE) {
            pathInfo?.channelsMhz?.maxOrNull()
        } else {
            freq.takeIf { it > 0 }
        }
        val carrierLink = ports.carrierLink(b.reader, b.writer)
        val carrier = WifiSocketCarrier(
            kind = carrierKind,
            socket = b.socket,
            link = carrierLink,
            releaseLink = { reason -> link?.close(reason) ?: driver?.close(reason) },
            ledger = env.ledger,
            ndpOwner = ndpOwner,
            ticket = ticket.copyOf(),
            peerReady = b.peerReady,
            bandVerified = bandVerified,
            channelMhz = channelMhz,
        )
        val accepted = if (bearer == WifiBearer.AWARE) {
            link?.handOff { reason -> carrier.platformLoss(reason) } == true
        } else {
            driver?.handOff { reason -> carrier.platformLoss(reason) } == true
        }
        if (!accepted) {
            carrier.ticket.fill(0)
            dispatch(HandOffResult(generation, false))
            return
        }
        // The socket can be closed under us (Aware went down between the bind result and this task): a failed
        // disarm is a refused hand-off, never an exception on the wifi thread. [P1-1]
        if (runCatching { b.disarm() }.isFailure) {
            carrier.ticket.fill(0)
            dispatch(HandOffResult(generation, false))
            return
        }
        log(
            "ready",
            "recvCeilingKbps" to b.peerReady.recvCeilingKbps,
            "familyCapKbps" to b.peerReady.familyCapKbps,
            "transportBudgetKbps" to b.peerReady.transportBudgetKbps,
        )
        val id = ports.offerCarrier(carrier)
        if (id < 0) {
            carrier.ticket.fill(0)
            dispatch(HandOffResult(generation, false))
            return
        }
        carrier.lossHandler = { reason ->
            val prefix = if (bearer == WifiBearer.AWARE) "aware_down_" else "bearer_lost_"
            ports.carrierDied(id, prefix + reason.name.lowercase())
        }
        handed = HandedCarrier(
            id, bandVerified, channelMhz, ticket.copyOf(), bearer,
            band24Alone = bandRule == AwareBand.Rule.ALONE_24,
        )
        dispatch(HandOffResult(generation, true))
    }

    private fun prepareBearer() {
        val driver = driver
        val failure = if (driver == null) BearerFailure("no_driver", 1, false) else driver.prepare()
        if (failure == null) {
            dispatch(BearerPrepared(generation))
        } else {
            dispatch(BearerFailed(generation, failure.reason, failure.decline, failure.local))
        }
        log("bearer", "step" to "prepare", "reason" to failure?.reason)
    }

    private fun startBearerHost() {
        val driver = driver ?: return
        val credentials = WifiKeys.directCredentials(uw, ticket)
        driver.startHost(credentials.ssid, credentials.psk)
        log("bearer", "step" to "start_host")
    }

    private fun startBearerGuest() {
        val driver = driver ?: return
        val credentials = WifiKeys.directCredentials(uw, ticket)
        driver.startGuest(credentials.ssid, credentials.psk, hostFreqMhz)
        log("bearer", "step" to "start_guest")
    }

    private fun arm(kind: TimerKind, ms: Long) {
        val tag = timerTags.getOrPut(kind) { Any() }
        val armedAt = env.scheduler.nowMs()
        env.scheduler.postDelayed(tag, ms) {
            guarded {
                val now = env.scheduler.nowMs()
                val late = maxOf(0L, now - armedAt - ms)
                if (late > WifiAttemptLimits.LATE_TIMER_LOG_MS) log("timer", "late_ms" to late, "kind" to kind.name.lowercase())
                dispatch(TimerFired(generation, kind, late))
            }
        }
    }

    private fun cancel(kind: TimerKind) {
        timerTags.remove(kind)?.let { env.scheduler.cancel(it) }
    }

    private fun release(outcome: Outcome) {
        if (ended) return
        ended = true
        for (kind in TimerKind.entries) timerTags.remove(kind)?.let { env.scheduler.cancel(it) }
        outcome.fault?.let { env.ledger.recordLocalFault(it) }
        if (outcome.kind != OutcomeKind.SUCCESS) {
            if (bearer == WifiBearer.AWARE) {
                runCatching { link?.close(outcome.reason) }
            } else {
                runCatching { driver?.close(outcome.reason) }
            }
            AwareSockets.closeOwned(generation)
            ndpOwner?.let { env.ledger.releaseNdp(it) }
            bound?.let { b ->
                runCatching { b.reader.close() }
                runCatching { b.writer.close() }
                AwareSockets.close(b.socket)
            }
        }
        bound = null
        uw.fill(0)
        for (nonce in knockNonces.values) nonce.fill(0)
        knockNonces.clear()
        gate.wipe()
        val setupMs = when {
            outcome.kind != OutcomeKind.SUCCESS -> null
            role == AttemptRole.HOST -> (env.scheduler.nowMs() - hostSentAtMs).coerceAtLeast(0L)
            else -> (env.scheduler.nowMs() - startedAtMs).coerceAtLeast(0L)
        }
        log(
            "release",
            "outcome" to outcome.kind.name.lowercase(),
            "reason" to outcome.reason,
            "setupMs" to setupMs,
            "fault" to outcome.fault,
        )
        if (outcome.kind != OutcomeKind.SUCCESS && outcome.kind != OutcomeKind.CANCELLED) {
            env.log(
                WifiLog.carrier(
                    ports.hopKind.log,
                    if (bearer == WifiBearer.AWARE) "aware" else "direct",
                    "bluetooth",
                    outcome.reason,
                ),
            )
        }
        val handedOut = if (outcome.kind == OutcomeKind.SUCCESS) handed else null
        handed = null
        onEnded(outcome, setupMs, handedOut)
    }

    // ----- AwareLinkEvents -----

    override fun onAttached(gen: Long) {
        if (gen != generation) return
        dispatch(AttachOk(gen))
    }

    override fun onAttachFailed(gen: Long, reason: AttachFailure) {
        if (gen != generation) return
        dispatch(AttachFailed(gen, reason))
    }

    override fun onSessionStarted(gen: Long) {
        if (gen != generation) return
        guarded {
            log(if (role == AttemptRole.HOST) "publish" else "subscribe", "result" to "ok")
            dispatch(SessionStarted(gen))
        }
    }

    override fun onSessionFailed(gen: Long, reason: String, local: Boolean) {
        if (gen != generation) return
        guarded {
            log(if (role == AttemptRole.HOST) "publish" else "subscribe", "result" to "fail", "reason" to reason)
            dispatch(SessionFailed(gen, reason, local))
        }
    }

    override fun onSessionTerminated(gen: Long) {
        if (gen != generation) return
        dispatch(SessionTerminated(gen))
    }

    override fun onPeerDiscovered(gen: Long, peer: AwarePeer) {
        if (gen != generation) return
        check(env.scheduler.isWifiThread())
        guarded {
            peers[peer.ordinal] = peer
            dispatch(PeerDiscovered(gen, peer.ordinal))
        }
    }

    override fun onMessage(gen: Long, peer: AwarePeer, bytes: ByteArray) {
        if (gen != generation) return
        check(env.scheduler.isWifiThread())
        guarded {
            peers[peer.ordinal] = peer
            if (ended) return
            if (role == AttemptRole.HOST) {
                val valid = WifiKeys.knockMatches(uw, ticket, bytes)
                dispatch(KnockIn(gen, peer.ordinal, valid, if (valid) bytes.copyOfRange(0, 16) else null))
            } else {
                val gN = knockNonces[peer.ordinal]
                val verified = gN != null && WifiKeys.challengeMatches(uw, ticket, gN, bytes)
                dispatch(ChallengeIn(gen, peer.ordinal, verified, if (verified) bytes.copyOfRange(0, 16) else null))
            }
        }
    }

    override fun onNetworkAvailable(gen: Long) {
        if (gen != generation) return
        dispatch(NetworkAvailable(gen))
    }

    override fun onDataPathReady(gen: Long, info: AwareDataPathInfo) {
        if (gen != generation) return
        guarded {
            // A guest whose platform reported no port (WifiAwareNetworkInfo.getPort() == 0) uses the derived one; a nonzero
            // platform port is used as given (no second connect attempt). The control arm keeps port 0 (`no_port`).
            val fallback = derivedPort && role == AttemptRole.GUEST && info.port == 0
            val path = if (fallback) info.copy(port = WifiKeys.port(uw, ticket)) else info
            pathInfo = path
            val setupMs = (env.scheduler.nowMs() - startedAtMs).coerceAtLeast(0L)
            val facts = env.facts()
            val staMhz = facts.staFrequencyMhz
            log(
                "ndp_up",
                "setupMs" to setupMs,
                "staMhz" to staMhz,
                "addr" to WifiLog.hash(path.peerAddress.hostAddress.orEmpty()),
                "iface" to WifiLog.hash(path.interfaceName),
            )
            if (role == AttemptRole.GUEST) {
                val source = if (fallback) "derived" else if (path.port != 0) "platform" else "none"
                // Whether the platform's port is the derived one: a boolean only, never the port itself.
                if (info.port != 0) log("port", "source" to source, "match" to (info.port == WifiKeys.port(uw, ticket)))
                else log("port", "source" to source)
            }
            // AP10: the hop's fact is read once, here on the wifi thread when the path comes up (not when the attempt
            // started), and only with the switch on; it travels with the event so the log and the model judge the same
            // inputs. With the switch off the owner is not asked and the verdict is AP9's.
            val alone24 = facts.aware24GhzAlone
            val otherBt = if (alone24) ports.otherBluetoothMedia() else null
            val judgement = AwareBand.judge(path.channelsMhz, bandStrict, alone24, otherBt ?: true)
            bandRule = judgement.rule
            log(
                "band",
                "chMhz" to path.channelsMhz?.joinToString(","),
                "verdict" to judgement.verdict.name.lowercase(),
                "strict" to bandStrict,
                "alone24" to alone24,
                "otherBtMedia" to (otherBt ?: "unread"),
                "band" to judgement.rule.log,
            )
            dispatch(NetworkReady(gen, PathFacts(path.channelsMhz, path.port != 0, alone24, otherBt ?: true)))
        }
    }

    override fun onNetworkUnavailable(gen: Long, elapsedMs: Long, timedOut: Boolean) {
        if (gen != generation) return
        dispatch(NetworkUnavailable(gen, elapsedMs, timedOut))
    }

    override fun onNetworkLost(gen: Long) {
        if (gen != generation) return
        dispatch(NetworkLost(gen))
    }

    override fun onAwareDown(gen: Long, reason: AwareDownReason) {
        if (gen != generation) return
        dispatch(AwareDown(gen, reason))
    }

    override fun onServerListening(gen: Long) {
        if (gen != generation) return
        dispatch(ServerListening(gen))
    }

    override fun onServerFailed(gen: Long, reason: String) {
        if (gen != generation) return
        dispatch(ServerFailed(gen, reason))
    }

    override fun onSocketAccepted(gen: Long, socket: Socket, ordinal: Int, acceptedAtNs: Long) {
        if (gen != generation) return
        check(env.scheduler.isWifiThread())
        guarded {
            if (ended) {
                AwareSockets.close(socket)
                return
            }
            sockets[ordinal] = socket to acceptedAtNs
            dispatch(SocketAccepted(gen, ordinal))
        }
    }

    override fun onSocketRejected(gen: Long, reason: String) {
        if (gen != generation) return
        dispatch(SocketRejected(gen, reason))
    }

    override fun onConnected(gen: Long, socket: Socket, connectedAtNs: Long) {
        if (gen != generation) return
        check(env.scheduler.isWifiThread())
        guarded {
            if (ended) {
                AwareSockets.close(socket)
                return
            }
            sockets[1] = socket to connectedAtNs
            dispatch(Connected(gen))
        }
    }

    override fun onConnectFailed(gen: Long, reason: String, refusedOnly: Boolean) {
        if (gen != generation) return
        dispatch(ConnectFailed(gen, reason, refusedOnly))
    }

    // ----- BearerEvents -----

    override fun onHostReady(gen: Long, freqMhz: Int, ipv4: ByteArray, port: Int) {
        if (gen != generation) return
        guarded {
            readyFreqMhz = freqMhz
            log("bearer", "step" to "host_ready", "freqMhz" to freqMhz)
            dispatch(BearerHostReady(gen, freqMhz, port, ipv4.copyOf()))
        }
    }

    override fun onJoined(gen: Long, freqMhz: Int?) {
        if (gen != generation) return
        guarded {
            joinedFreqMhz = freqMhz
            log("bearer", "step" to "joined", "freqMhz" to freqMhz)
            dispatch(BearerJoined(gen, freqMhz))
        }
    }

    override fun onFailed(gen: Long, failure: BearerFailure) {
        if (gen != generation) return
        guarded {
            log("bearer", "step" to "failed", "reason" to failure.reason)
            dispatch(BearerFailed(gen, failure.reason, failure.decline, failure.local))
        }
    }

    override fun onLost(gen: Long, reason: AwareDownReason) {
        if (gen != generation) return
        dispatch(AwareDown(gen, reason))
    }

    // ----- logging -----

    private fun commonFieldsWith(extra: List<Pair<String, Any?>>): Array<Pair<String, Any?>> = (
        listOf(
            "hop" to ports.hopKind.log,
            "level" to level,
            "carrier" to if (bearer == WifiBearer.AWARE) "aware" else "direct",
            "role" to if (role == AttemptRole.HOST) "host" else "guest",
            "gen" to generation,
        ) + extra
        ).toTypedArray()

    private fun log(event: String, vararg fields: Pair<String, Any?>) {
        env.log(WifiLog.upgrade(event, *commonFieldsWith(fields.toList())))
    }
}
