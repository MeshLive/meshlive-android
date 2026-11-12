package com.example.bluetoothtest

import java.security.MessageDigest

/** What this phone does in one attempt. */
internal enum class AttemptRole { HOST, GUEST }

/** The nine phases of an attempt; the Direct events of A5.9 add no phase. */
internal enum class Phase { IDLE, ATTACHING, DISCOVERING, REQUESTING, ADDRESSING, CONNECTING, BINDING, HANDING_OFF, DONE }

internal enum class TimerKind { ATTEMPT, PICK, SESSION, REQUEST, ADDRESS, CONNECT, BIND, KNOCK_RESEND }

internal enum class OutcomeKind { SUCCESS, FAILED_LOCAL, FAILED_PEER, FAILED_SPOOFED, NO_PENALTY, CANCELLED }

internal enum class Cmd { SWITCH_OFF, RENEWAL, HANGUP, WIPE, SHUTDOWN, VIOLATION }

internal class Outcome(
    val kind: OutcomeKind,
    val reason: String,
    /** DECLINE code to send to the peer; null = send none. */
    val decline: Int? = null,
    /** Local-fault kind the executor must record in [WifiLedger]; null = none. Only failures that involve no peer. [Am 3] */
    val fault: String? = null,
    /** A DECLINE 2 / BUSY_DIRECT end: not counted toward declined_thrice. [Am 7] */
    val busy: Boolean = false,
)

/**
 * AP10: [alone24] (the 2.4 GHz-alone switch) and [otherBluetoothMedia] (the hop's fact, read when the data path came
 * up) travel with the path, so the model stays pure. The defaults are AP9's rule.
 */
internal class PathFacts(
    val channelsMhz: List<Int>?,
    val portKnown: Boolean,
    val alone24: Boolean = false,
    val otherBluetoothMedia: Boolean = true,
)

internal sealed interface AttemptEvent {
    val gen: Long
}

internal data class Start(override val gen: Long) : AttemptEvent

internal data class AttachOk(override val gen: Long) : AttemptEvent

internal data class AttachFailed(override val gen: Long, val reason: AttachFailure) : AttemptEvent

internal data class SessionStarted(override val gen: Long) : AttemptEvent

internal data class SessionFailed(override val gen: Long, val reason: String, val local: Boolean) : AttemptEvent

internal data class SessionTerminated(override val gen: Long) : AttemptEvent

internal data class PeerDiscovered(override val gen: Long, val ordinal: Int) : AttemptEvent

internal data class KnockIn(override val gen: Long, val ordinal: Int, val valid: Boolean, val gN: ByteArray?) : AttemptEvent

internal data class ChallengeIn(override val gen: Long, val ordinal: Int, val verified: Boolean, val c: ByteArray?) : AttemptEvent

internal data class PickIn(override val gen: Long, val ticketMatches: Boolean, val challenge: ByteArray) : AttemptEvent

internal data class ArmedIn(override val gen: Long, val ticketMatches: Boolean) : AttemptEvent

internal data class DeclineIn(override val gen: Long, val ticketMatches: Boolean, val reason: Int) : AttemptEvent

internal data class SendFailed(override val gen: Long, val what: String) : AttemptEvent

internal data class RequestFiled(override val gen: Long, val ok: Boolean) : AttemptEvent

internal data class NetworkAvailable(override val gen: Long) : AttemptEvent

internal data class NetworkReady(override val gen: Long, val path: PathFacts) : AttemptEvent

internal data class NetworkUnavailable(override val gen: Long, val elapsedMs: Long, val timedOut: Boolean) : AttemptEvent

internal data class NetworkLost(override val gen: Long) : AttemptEvent

internal data class AwareDown(override val gen: Long, val reason: AwareDownReason) : AttemptEvent

internal data class ServerListening(override val gen: Long) : AttemptEvent

internal data class ServerFailed(override val gen: Long, val reason: String) : AttemptEvent

internal data class SocketAccepted(override val gen: Long, val ordinal: Int) : AttemptEvent

internal data class SocketRejected(override val gen: Long, val reason: String) : AttemptEvent

internal data class Connected(override val gen: Long) : AttemptEvent

internal data class ConnectFailed(override val gen: Long, val reason: String, val refusedOnly: Boolean) : AttemptEvent

internal enum class BindCode { BOUND, REJECTED_BEFORE_TICKET, FAILED_AFTER_TICKET }

internal data class BindFinished(override val gen: Long, val ordinal: Int, val code: BindCode, val stage: String) : AttemptEvent

internal data class HandOffResult(override val gen: Long, val accepted: Boolean) : AttemptEvent

internal data class TimerFired(override val gen: Long, val kind: TimerKind, val lateMs: Long) : AttemptEvent

internal data class Command(override val gen: Long, val cmd: Cmd) : AttemptEvent

// Direct (Wi-Fi Direct) events of A5.9; they add no phase.
internal data class BearerPrepared(override val gen: Long) : AttemptEvent

internal data class BearerHostReady(override val gen: Long, val freqMhz: Int, val port: Int, val ipv4: ByteArray) : AttemptEvent

internal data class CredsIn(override val gen: Long, val ticketMatches: Boolean, val port: Int, val ipv4: ByteArray) : AttemptEvent

internal data class BearerJoined(override val gen: Long, val freqMhz: Int?) : AttemptEvent

internal data class BearerFailed(override val gen: Long, val reason: String, val decline: Int, val local: Boolean) : AttemptEvent

internal sealed interface AttemptEffect

internal object AcquireAttach : AttemptEffect

internal data class StartSession(val role: AttemptRole) : AttemptEffect

internal object SendHost : AttemptEffect

internal data class SendDecline(val reason: Int) : AttemptEffect

internal data class SendPick(val challenge: ByteArray) : AttemptEffect

internal object SendArmed : AttemptEffect

internal data class SendKnock(val ordinal: Int, val gN: ByteArray) : AttemptEffect

internal data class SendChallenge(val ordinal: Int, val gN: ByteArray, val c: ByteArray) : AttemptEffect

internal data class RequestPath(val responder: Boolean, val ordinal: Int) : AttemptEffect

internal object Listen : AttemptEffect

internal object Connect : AttemptEffect

internal data class StartBind(val ordinal: Int) : AttemptEffect

internal data class CloseSocket(val ordinal: Int) : AttemptEffect

internal data class HandOff(val ordinal: Int) : AttemptEffect

internal data class Arm(val kind: TimerKind, val ms: Long) : AttemptEffect

internal data class Cancel(val kind: TimerKind) : AttemptEffect

internal data class LogEvent(val event: String, val fields: List<Pair<String, Any?>>) : AttemptEffect

/** Always the LAST effect of a list; the executor performs the release (A5.5). */
internal data class End(val outcome: Outcome) : AttemptEffect

// Direct effects (A5.9).
internal object PrepareBearer : AttemptEffect

internal object StartBearerHost : AttemptEffect

internal object StartBearerGuest : AttemptEffect

internal data class SendCreds(val port: Int, val ipv4: ByteArray) : AttemptEffect

internal data class ConnectBearer(val ipv4: ByteArray, val port: Int) : AttemptEffect

/**
 * The pure attempt state machine: no Android type, no Socket, no clock. Time arrives as event fields, randomness as
 * [random]. Deterministic given the same events and random stream.
 */
internal class AttemptModel(
    val role: AttemptRole,
    val generation: Long,
    private val acceptsUnverifiedBand: Boolean,
    private val random: (ByteArray) -> Unit,
    private val bearer: WifiBearer = WifiBearer.AWARE,
    /** AP9 DEBUG `test_aware_band_strict`: the old all-channels-5GHz Aware band rule. */
    private val bandStrict: Boolean = false,
) {
    var phase: Phase = Phase.IDLE
        private set
    val ended: Boolean get() = phase == Phase.DONE

    private var attached = false
    private var sessionStarted = false
    private var picked: Int? = null
    private var pickSent = false
    private var armedSeen = false
    private var accepted = 0
    private var resolvedFailures = 0
    private var messagesSeen = 0
    private var messageCapLogged = false
    private var ticketBurnt = false

    private class Issued(val ordinal: Int, val gN: ByteArray, val c: ByteArray, var sends: Int)
    private class KnockState(val gN: ByteArray, var sends: Int, var checks: Int)

    private val issued = ArrayList<Issued>()
    private var verifiedKnocks = 0

    private val knocked = LinkedHashMap<Int, KnockState>()
    private val waiting = ArrayDeque<Int>()
    private val admitted = HashSet<Int>()
    private var evicted = 0
    private var badChallenges = 0
    private var anyVerified = false
    private var knockSendsTotal = 0
    private var resendArmed = false

    // Direct guest
    private var joined = false
    private var credsSeen = false
    private var credsPort = 0
    private var credsIpv4: ByteArray? = null
    private var hostSent = false

    fun handle(event: AttemptEvent): List<AttemptEffect> {
        if (event.gen != generation) return emptyList()
        if (phase == Phase.DONE) return emptyList()
        when (event) {
            is Command -> return handleCommand(event.cmd)
            is TimerFired -> if (event.kind == TimerKind.ATTEMPT) {
                return end(Outcome(OutcomeKind.FAILED_PEER, "attempt_timeout", decline = 6))
            }
            else -> Unit
        }
        // F-1: a guest's PICK timeout (usually the host's DECLINE 6 arrives first) after spoof signs is not a peer failure.
        if (event is DeclineIn && event.ticketMatches && event.reason == 6 && role == AttemptRole.GUEST &&
            phase == Phase.DISCOVERING && !anyVerified && (badChallenges > 0 || evicted > 0)
        ) {
            return end(Outcome(OutcomeKind.FAILED_SPOOFED, "peer_declined_6"))
        }
        when (event) {
            is DeclineIn -> return handleDecline(event)
            is SendFailed -> return end(Outcome(OutcomeKind.CANCELLED, "no_carrier_${event.what}"))
            is AwareDown -> return handleAwareDown(event)
            else -> Unit
        }
        return if (bearer == WifiBearer.DIRECT) handleDirect(event) else handleAware(event)
    }

    private fun handleCommand(cmd: Cmd): List<AttemptEffect> = when (cmd) {
        Cmd.SWITCH_OFF -> end(Outcome(OutcomeKind.CANCELLED, "switch_off", decline = 1))
        Cmd.VIOLATION -> end(Outcome(OutcomeKind.CANCELLED, "violation", decline = 1))
        Cmd.RENEWAL -> end(Outcome(OutcomeKind.CANCELLED, "renewal", decline = 2))
        Cmd.HANGUP -> end(Outcome(OutcomeKind.CANCELLED, "hangup"))
        Cmd.WIPE -> end(Outcome(OutcomeKind.CANCELLED, "wipe"))
        Cmd.SHUTDOWN -> end(Outcome(OutcomeKind.CANCELLED, "shutdown"))
    }

    private fun handleDecline(event: DeclineIn): List<AttemptEffect> {
        if (!event.ticketMatches) return listOf(log("decline", "reason" to "stale"))
        val reason = event.reason
        val outcome = when (reason) {
            1, 2, 3, 4, 5 -> Outcome(OutcomeKind.NO_PENALTY, "peer_declined_$reason", busy = reason == 2)
            6 -> Outcome(OutcomeKind.FAILED_PEER, "peer_declined_6")
            7 -> Outcome(OutcomeKind.NO_PENALTY, "peer_declined_band")
            else -> Outcome(OutcomeKind.NO_PENALTY, "peer_declined_other")
        }
        return end(outcome)
    }

    private fun handleAwareDown(event: AwareDown): List<AttemptEffect> {
        if (phase >= Phase.HANDING_OFF) return emptyList()
        // Our own clean shutdown or wipe closed the radio: a cancellation, never a phone-wide fault. [A5 review P3-3]
        if (event.reason == AwareDownReason.SHUTDOWN) {
            return end(Outcome(OutcomeKind.CANCELLED, "shutdown", decline = 3))
        }
        return if (bearer == WifiBearer.AWARE) {
            val fault = if (event.reason == AwareDownReason.WIFI_OFF) "wifi_off_setup" else "aware_down_setup"
            end(Outcome(OutcomeKind.FAILED_LOCAL, "aware_down_${event.reason.name.lowercase()}", decline = 3, fault = fault))
        } else {
            end(Outcome(OutcomeKind.FAILED_LOCAL, "bearer_lost_${event.reason.name.lowercase()}", decline = 3))
        }
    }

    // ----- Aware transitions -----

    private fun handleAware(event: AttemptEvent): List<AttemptEffect> {
        if (event is RequestFiled) return dataPath(event)
        if (event is AttachOk || event is AttachFailed || event is SessionStarted || event is PeerDiscovered ||
            event is KnockIn || event is ChallengeIn || event is PickIn || event is ArmedIn ||
            event is NetworkAvailable || event is NetworkReady || event is NetworkUnavailable || event is NetworkLost
        ) {
            return when (phase) {
                Phase.IDLE, Phase.ATTACHING -> when (event) {
                    is AttachOk -> attachOk()
                    is AttachFailed -> attachFailed(event.reason)
                    is SessionStarted -> sessionStarted()
                    else -> listOf(log("ignored", "reason" to "early"))
                }
                Phase.DISCOVERING -> discovering(event)
                Phase.REQUESTING, Phase.ADDRESSING, Phase.CONNECTING, Phase.BINDING, Phase.HANDING_OFF -> {
                    if (event is AttachOk || event is AttachFailed || event is PeerDiscovered || event is KnockIn ||
                        event is ChallengeIn || event is PickIn || event is ArmedIn
                    ) {
                        listOf(log("ignored", "reason" to "late"))
                    } else {
                        dataPath(event)
                    }
                }
                Phase.DONE -> emptyList()
            }
        }
        return when (event) {
            is Start -> startAware()
            is SessionFailed -> sessionFailed(event.reason, event.local)
            is SessionTerminated -> sessionTerminated()
            is TimerFired -> timerFired(event.kind)
            is ServerListening -> listOf(log("socket", "result" to "listening"))
            is ServerFailed -> end(Outcome(OutcomeKind.FAILED_LOCAL, "server_failed", decline = 6))
            is SocketAccepted -> socketAccepted(event.ordinal)
            is SocketRejected -> listOf(log("socket", "result" to "rejected", "reason" to event.reason))
            is Connected -> connected()
            is ConnectFailed -> end(Outcome(OutcomeKind.FAILED_PEER, "connect_${event.reason}", decline = 6))
            is BindFinished -> bindFinished(event)
            is HandOffResult -> handOffResult(event.accepted)
            else -> emptyList()
        }
    }

    private fun startAware(): List<AttemptEffect> {
        if (phase != Phase.IDLE) return emptyList()
        phase = Phase.ATTACHING
        val effects = ArrayList<AttemptEffect>()
        effects += AcquireAttach
        if (role == AttemptRole.GUEST) effects += Arm(TimerKind.ATTEMPT, WifiLimits.ATTEMPT_MAX_MS)
        return effects
    }

    private fun attachOk(): List<AttemptEffect> {
        if (phase != Phase.ATTACHING) return emptyList()
        attached = true
        return listOf(Arm(TimerKind.SESSION, WifiAttemptLimits.SESSION_START_MS), StartSession(role))
    }

    private fun attachFailed(reason: AttachFailure): List<AttemptEffect> {
        val noPenalty = when (reason) {
            AttachFailure.BUSY_DIRECT, AttachFailure.BACKOFF, AttachFailure.DISABLED, AttachFailure.NO_MANAGER,
            AttachFailure.NO_PERMISSION, AttachFailure.WIFI_OFF, AttachFailure.UNAVAILABLE -> true
            AttachFailure.ATTACH_REFUSED, AttachFailure.ATTACH_TIMEOUT, AttachFailure.EXCEPTION -> false
        }
        val decline = if (role == AttemptRole.GUEST) reason.declineReason else null
        return end(
            Outcome(
                if (noPenalty) OutcomeKind.NO_PENALTY else OutcomeKind.FAILED_LOCAL,
                "attach_${reason.name.lowercase()}",
                decline = decline,
                busy = reason == AttachFailure.BUSY_DIRECT,
            ),
        )
    }

    private fun sessionStarted(): List<AttemptEffect> {
        if (phase != Phase.ATTACHING || !attached) return emptyList()
        sessionStarted = true
        phase = Phase.DISCOVERING
        val effects = ArrayList<AttemptEffect>()
        effects += Cancel(TimerKind.SESSION)
        if (role == AttemptRole.HOST) {
            effects += SendHost
            effects += Arm(TimerKind.PICK, WifiLimits.DISCOVERY_TO_PICK_MS)
            effects += Arm(TimerKind.ATTEMPT, WifiLimits.ATTEMPT_MAX_MS)
            effects += log("host", "dir" to "sent")
        } else {
            effects += Arm(TimerKind.PICK, WifiLimits.DISCOVERY_TO_PICK_MS)
        }
        return effects
    }

    private fun sessionFailed(reason: String, local: Boolean): List<AttemptEffect> {
        if (phase == Phase.IDLE || phase >= Phase.HANDING_OFF) return emptyList()
        val fault = sessionFault(reason, local)
        // A host that already sent HOST (DISCOVERING up to HANDING_OFF) tells the waiting guest; before HOST only a guest
        // has a peer to tell. [A5.2][A5 review P2-1]
        val decline = if (role == AttemptRole.GUEST || phase >= Phase.DISCOVERING) 6 else null
        return end(Outcome(OutcomeKind.FAILED_LOCAL, "session_$reason", decline = decline, fault = fault))
    }

    /** A session failure is a phone-wide fault only for our own start failures, never for config_failed or message_stuck. [Am 3] */
    private fun sessionFault(reason: String, local: Boolean): String? {
        if (!local) return null
        return when (reason) {
            "security", "config", "exception" -> reason
            else -> null
        }
    }

    private fun sessionTerminated(): List<AttemptEffect> {
        return when (phase) {
            Phase.DISCOVERING -> end(Outcome(OutcomeKind.FAILED_LOCAL, "session_terminated", decline = 6))
            Phase.REQUESTING, Phase.ADDRESSING, Phase.CONNECTING, Phase.BINDING -> listOf(log("session_terminated_ignored"))
            else -> emptyList()
        }
    }

    private fun discovering(event: AttemptEvent): List<AttemptEffect> = when {
        event is KnockIn && role == AttemptRole.HOST -> knockIn(event)
        event is PickIn && role == AttemptRole.HOST -> pickIn(event)
        event is PeerDiscovered && role == AttemptRole.GUEST -> peerDiscovered(event.ordinal)
        event is ChallengeIn && role == AttemptRole.GUEST -> challengeIn(event)
        event is ArmedIn && role == AttemptRole.GUEST -> armedIn(event)
        event is SessionFailed -> sessionFailed(event.reason, event.local)
        event is SessionTerminated -> sessionTerminated()
        event is NetworkAvailable || event is NetworkReady || event is NetworkUnavailable || event is NetworkLost ||
            event is AttachOk || event is AttachFailed || event is PickIn || event is PeerDiscovered -> listOf(log("ignored", "reason" to "early"))
        else -> emptyList()
    }

    private fun knockIn(event: KnockIn): List<AttemptEffect> {
        messagesSeen++
        if (messagesSeen > WifiAttemptLimits.MAX_MESSAGES_SEEN) {
            return if (messageCapLogged) emptyList() else {
                messageCapLogged = true
                listOf(log("message_cap"))
            }
        }
        val gN = event.gN
        if (!event.valid || gN == null) return listOf(log("knock", "dir" to "recv", "valid" to false))
        verifiedKnocks++
        val existing = issued.firstOrNull { it.ordinal == event.ordinal && MessageDigest.isEqual(it.gN, gN) }
        if (existing != null) {
            if (existing.sends >= WifiAttemptLimits.MAX_CHALLENGE_SENDS_PER_HANDLE) {
                return listOf(log("challenge_resend_cap", "n" to event.ordinal))
            }
            existing.sends++
            return listOf(
                log("knock", "dir" to "recv", "valid" to true, "n" to existing.sends),
                SendChallenge(event.ordinal, gN.copyOf(), existing.c.copyOf()),
            )
        }
        if (issued.size >= WifiLimits.MAX_CHALLENGES_PER_ATTEMPT) {
            return listOf(log("challenge", "capped" to true, "n" to event.ordinal))
        }
        val c = ByteArray(WifiWire.CHALLENGE_BYTES).also(random)
        issued += Issued(event.ordinal, gN.copyOf(), c, 1)
        return listOf(
            log("knock", "dir" to "recv", "valid" to true, "n" to 1),
            SendChallenge(event.ordinal, gN.copyOf(), c.copyOf()),
        )
    }

    private fun pickIn(event: PickIn): List<AttemptEffect> {
        if (!event.ticketMatches) return listOf(log("pick_stale"))
        if (picked != null) return listOf(log("pick_duplicate"))
        var match: Issued? = null
        for (entry in issued) { // constant-time over every entry, no early exit
            if (MessageDigest.isEqual(entry.c, event.challenge)) match = entry
        }
        val entry = match ?: return listOf(log("pick_unknown"))
        picked = entry.ordinal
        phase = Phase.REQUESTING
        return listOf(
            Cancel(TimerKind.PICK),
            Arm(TimerKind.REQUEST, WifiAttemptLimits.REQUEST_WATCHDOG_MS),
            RequestPath(responder = true, ordinal = entry.ordinal),
            log("pick", "dir" to "recv"),
        )
    }

    private fun peerDiscovered(ordinal: Int): List<AttemptEffect> {
        if (picked != null) return emptyList()
        if (ordinal in admitted) return emptyList()
        if (admitted.size >= WifiLimits.MAX_HANDLES_PER_ATTEMPT) return listOf(log("handle_cap", "n" to ordinal))
        admitted += ordinal
        return if (knocked.size < WifiLimits.MAX_KNOCKS_PER_ATTEMPT) {
            knock(ordinal)
        } else {
            waiting.addLast(ordinal)
            listOf(log("knock_wait", "n" to ordinal))
        }
    }

    private fun knock(ordinal: Int): List<AttemptEffect> {
        val gN = ByteArray(WifiWire.CHALLENGE_BYTES).also(random)
        knocked[ordinal] = KnockState(gN, 1, 0)
        knockSendsTotal++
        val effects = ArrayList<AttemptEffect>()
        effects += SendKnock(ordinal, gN.copyOf())
        if (!resendArmed) {
            effects += Arm(TimerKind.KNOCK_RESEND, WifiAttemptLimits.KNOCK_RESEND_MS)
            resendArmed = true
        }
        return effects
    }

    private fun resendKnocks(): List<AttemptEffect> {
        resendArmed = false
        if (picked != null) return emptyList()
        val effects = ArrayList<AttemptEffect>()
        for (ordinal in knocked.keys.toList()) {
            val state = knocked[ordinal] ?: continue
            if (state.sends >= WifiAttemptLimits.MAX_KNOCK_SENDS_PER_HANDLE) {
                knocked.remove(ordinal)
                evicted++
                effects += log("knock_evict", "n" to ordinal)
                continue
            }
            if (knockSendsTotal < WifiAttemptLimits.MAX_KNOCK_SENDS_TOTAL) {
                state.sends++
                knockSendsTotal++
                effects += SendKnock(ordinal, state.gN.copyOf())
            }
        }
        while (knocked.size < WifiLimits.MAX_KNOCKS_PER_ATTEMPT && waiting.isNotEmpty()) {
            effects += knock(waiting.removeFirst())
        }
        if (knocked.isNotEmpty() && !resendArmed) {
            effects += Arm(TimerKind.KNOCK_RESEND, WifiAttemptLimits.KNOCK_RESEND_MS)
            resendArmed = true
        }
        return effects
    }

    private fun challengeIn(event: ChallengeIn): List<AttemptEffect> {
        if (picked != null) return listOf(log("challenge_late", "n" to event.ordinal))
        val state = knocked[event.ordinal]
            ?: return listOf(log("challenge_unsolicited", "n" to event.ordinal))
        if (state.checks >= WifiAttemptLimits.MAX_CHALLENGE_CHECKS_PER_HANDLE) {
            return listOf(log("challenge_check_cap", "n" to event.ordinal))
        }
        state.checks++
        val c = event.c
        if (!event.verified || c == null) {
            badChallenges++
            return listOf(log("challenge_bad", "n" to event.ordinal))
        }
        anyVerified = true
        picked = event.ordinal
        pickSent = true
        resendArmed = false
        return listOf(
            Cancel(TimerKind.KNOCK_RESEND),
            SendPick(c.copyOf()),
            log("pick", "dir" to "sent"),
        )
    }

    private fun armedIn(event: ArmedIn): List<AttemptEffect> {
        if (!event.ticketMatches) return listOf(log("armed_stale"))
        if (!pickSent || armedSeen) return listOf(log("armed_early"))
        armedSeen = true
        phase = Phase.REQUESTING
        return listOf(
            Cancel(TimerKind.PICK),
            Arm(TimerKind.REQUEST, WifiAttemptLimits.REQUEST_WATCHDOG_MS),
            RequestPath(responder = false, ordinal = picked ?: return listOf(log("armed_early"))),
            log("armed", "dir" to "recv"),
        )
    }

    /** REQUESTING, ADDRESSING, CONNECTING, BINDING and HANDING_OFF share the data-path events. */
    private fun dataPath(event: AttemptEvent): List<AttemptEffect> = when (event) {
        is NetworkAvailable -> if (phase == Phase.REQUESTING) {
            phase = Phase.ADDRESSING
            listOf(Arm(TimerKind.ADDRESS, WifiLimits.ADDRESS_WAIT_MS))
        } else {
            emptyList()
        }
        is NetworkReady -> if (phase == Phase.REQUESTING || phase == Phase.ADDRESSING) networkReady(event.path) else emptyList()
        is NetworkUnavailable -> when (phase) {
            Phase.REQUESTING, Phase.ADDRESSING -> end(
                Outcome(
                    OutcomeKind.FAILED_PEER,
                    if (event.timedOut) "ndp_timeout" else "ndp_refused",
                    decline = 6,
                ),
            )
            else -> emptyList()
        }
        is NetworkLost -> if (phase <= Phase.BINDING) {
            end(Outcome(OutcomeKind.FAILED_PEER, "ndp_lost", decline = 6))
        } else {
            emptyList()
        }
        is SessionFailed -> if (phase <= Phase.BINDING) sessionFailed(event.reason, event.local) else emptyList()
        is SessionTerminated -> if (phase <= Phase.BINDING) sessionTerminated() else emptyList()
        else -> when (phase) {
            Phase.REQUESTING -> requestTimer(event)
            Phase.ADDRESSING -> addressTimer(event)
            Phase.CONNECTING -> connecting(event)
            Phase.BINDING -> binding(event)
            Phase.HANDING_OFF -> handOffPhase(event)
            else -> emptyList()
        }
    }

    private fun networkReady(path: PathFacts): List<AttemptEffect> {
        if (phase != Phase.REQUESTING && phase != Phase.ADDRESSING) return emptyList()
        val effects = ArrayList<AttemptEffect>()
        effects += Cancel(TimerKind.REQUEST)
        effects += Cancel(TimerKind.ADDRESS)
        if (role == AttemptRole.GUEST && !path.portKnown) {
            return effects + end(Outcome(OutcomeKind.FAILED_PEER, "no_port", decline = 6))
        }
        when (AwareBand.judge(path.channelsMhz, bandStrict, path.alone24, path.otherBluetoothMedia).verdict) {
            AwareBand.Verdict.OK -> effects += log("band", "verified" to true)
            AwareBand.Verdict.TOO_LOW -> return effects + end(Outcome(OutcomeKind.NO_PENALTY, "band_low", decline = 7))
            AwareBand.Verdict.UNKNOWN -> {
                if (!acceptsUnverifiedBand) {
                    return effects + end(Outcome(OutcomeKind.NO_PENALTY, "band_unknown", decline = 7))
                }
                effects += log("band", "verified" to false)
            }
        }
        phase = Phase.CONNECTING
        if (role == AttemptRole.HOST) {
            effects += Listen
        } else {
            effects += Connect
        }
        effects += Arm(TimerKind.CONNECT, WifiRadioLimits.CONNECT_WINDOW_MS.toLong())
        return effects
    }

    private fun requestTimer(event: AttemptEvent): List<AttemptEffect> = when (event) {
        is RequestFiled -> when {
            !event.ok -> end(Outcome(OutcomeKind.FAILED_LOCAL, "request_failed", decline = 6, fault = "request_failed"))
            role == AttemptRole.HOST -> listOf(SendArmed, log("armed", "dir" to "sent"))
            else -> emptyList()
        }
        is TimerFired -> if (event.kind == TimerKind.REQUEST) {
            end(Outcome(OutcomeKind.FAILED_PEER, "ndp_timeout", decline = 6))
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    private fun addressTimer(event: AttemptEvent): List<AttemptEffect> = when (event) {
        is TimerFired -> if (event.kind == TimerKind.ADDRESS) {
            end(Outcome(OutcomeKind.FAILED_PEER, "address_timeout", decline = 6))
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    private fun connecting(event: AttemptEvent): List<AttemptEffect> = when (event) {
        is ServerListening -> listOf(log("socket", "result" to "listening"))
        is ServerFailed -> end(Outcome(OutcomeKind.FAILED_LOCAL, "server_failed", decline = 6))
        is SocketRejected -> listOf(log("socket", "result" to "rejected", "reason" to event.reason))
        is SocketAccepted -> socketAccepted(event.ordinal)
        is Connected -> connected()
        is ConnectFailed -> end(Outcome(OutcomeKind.FAILED_PEER, "connect_${event.reason}", decline = 6))
        is TimerFired -> if (event.kind == TimerKind.CONNECT) {
            end(
                Outcome(
                    OutcomeKind.FAILED_PEER,
                    if (role == AttemptRole.HOST) "no_connect" else "connect_timeout",
                    decline = 6,
                ),
            )
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    private fun socketAccepted(ordinal: Int): List<AttemptEffect> {
        if (phase != Phase.CONNECTING && phase != Phase.BINDING) return emptyList()
        accepted++
        if (accepted > WifiLimits.MAX_ACCEPTED_SOCKETS_PER_ATTEMPT) {
            return listOf(CloseSocket(ordinal), log("socket", "capped" to true, "n" to ordinal))
        }
        val effects = ArrayList<AttemptEffect>()
        if (phase == Phase.CONNECTING) {
            phase = Phase.BINDING
            effects += Cancel(TimerKind.CONNECT)
            effects += Arm(TimerKind.BIND, WifiLimits.BIND_READY_DEADLINE_MS)
        }
        effects += StartBind(ordinal)
        effects += log("socket", "n" to ordinal)
        return effects
    }

    private fun connected(): List<AttemptEffect> {
        if (phase != Phase.CONNECTING) return emptyList()
        phase = Phase.BINDING
        return listOf(
            Cancel(TimerKind.CONNECT),
            Arm(TimerKind.BIND, WifiLimits.BIND_READY_DEADLINE_MS),
            StartBind(1),
            log("socket", "n" to 1),
        )
    }

    private fun binding(event: AttemptEvent): List<AttemptEffect> = when (event) {
        is SocketAccepted -> socketAccepted(event.ordinal)
        is BindFinished -> bindFinished(event)
        is TimerFired -> if (event.kind == TimerKind.BIND) {
            end(Outcome(OutcomeKind.FAILED_PEER, "bind_timeout", decline = 6))
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    private fun bindFinished(event: BindFinished): List<AttemptEffect> {
        if (phase != Phase.BINDING) return emptyList()
        return when (event.code) {
            BindCode.BOUND -> {
                phase = Phase.HANDING_OFF
                listOf(Cancel(TimerKind.BIND), HandOff(event.ordinal), log("bind", "result" to "ok", "n" to event.ordinal))
            }
            BindCode.REJECTED_BEFORE_TICKET -> {
                resolvedFailures++
                val effects = arrayListOf<AttemptEffect>(
                    CloseSocket(event.ordinal),
                    log("bind", "result" to "rejected", "stage" to event.stage, "n" to event.ordinal),
                )
                if (role == AttemptRole.HOST) {
                    if (resolvedFailures >= WifiLimits.MAX_ACCEPTED_SOCKETS_PER_ATTEMPT ||
                        (accepted >= WifiLimits.MAX_ACCEPTED_SOCKETS_PER_ATTEMPT && resolvedFailures >= accepted)
                    ) {
                        effects += end(Outcome(OutcomeKind.FAILED_PEER, "bind_sockets_exhausted", decline = 6))
                    }
                } else {
                    effects += end(Outcome(OutcomeKind.FAILED_PEER, "bind_${event.stage}", decline = 6))
                }
                effects
            }
            BindCode.FAILED_AFTER_TICKET -> {
                ticketBurnt = true
                end(Outcome(OutcomeKind.FAILED_PEER, "bind_failed_${event.stage}", decline = 6))
            }
        }
    }

    private fun handOffPhase(event: AttemptEvent): List<AttemptEffect> = when (event) {
        is HandOffResult -> handOffResult(event.accepted)
        else -> emptyList()
    }

    private fun handOffResult(accepted: Boolean): List<AttemptEffect> {
        if (phase != Phase.HANDING_OFF) return emptyList()
        return if (accepted) {
            end(Outcome(OutcomeKind.SUCCESS, "handed_off"))
        } else {
            end(Outcome(OutcomeKind.CANCELLED, "handoff_refused"))
        }
    }

    private fun timerFired(kind: TimerKind): List<AttemptEffect> = when (kind) {
        TimerKind.SESSION -> end(
            Outcome(OutcomeKind.FAILED_LOCAL, "session_timeout", decline = if (role == AttemptRole.GUEST) 6 else null, fault = "session_timeout"),
        )
        TimerKind.PICK -> when (role) {
            AttemptRole.HOST -> if (verifiedKnocks == 0) {
                end(Outcome(OutcomeKind.NO_PENALTY, "pick_timeout_no_knock", decline = 6))
            } else {
                end(Outcome(OutcomeKind.FAILED_PEER, "pick_timeout", decline = 6))
            }
            AttemptRole.GUEST -> if (!anyVerified && (badChallenges > 0 || evicted > 0)) {
                end(Outcome(OutcomeKind.FAILED_SPOOFED, "pick_timeout", decline = 4))
            } else {
                end(Outcome(OutcomeKind.FAILED_PEER, "pick_timeout", decline = 6))
            }
        }
        TimerKind.REQUEST -> if (phase == Phase.REQUESTING) {
            end(Outcome(OutcomeKind.FAILED_PEER, "ndp_timeout", decline = 6))
        } else {
            emptyList()
        }
        TimerKind.ADDRESS -> if (phase == Phase.ADDRESSING) {
            end(Outcome(OutcomeKind.FAILED_PEER, "address_timeout", decline = 6))
        } else {
            emptyList()
        }
        TimerKind.CONNECT -> if (phase == Phase.CONNECTING) {
            end(
                Outcome(
                    OutcomeKind.FAILED_PEER,
                    if (role == AttemptRole.HOST) "no_connect" else "connect_timeout",
                    decline = 6,
                ),
            )
        } else {
            emptyList()
        }
        TimerKind.BIND -> if (phase == Phase.BINDING) {
            end(Outcome(OutcomeKind.FAILED_PEER, "bind_timeout", decline = 6))
        } else {
            emptyList()
        }
        TimerKind.KNOCK_RESEND -> if (phase == Phase.DISCOVERING && role == AttemptRole.GUEST) resendKnocks() else emptyList()
        TimerKind.ATTEMPT -> emptyList() // handled by the global rule
    }

    // ----- Direct transitions (A5.9) -----

    private fun handleDirect(event: AttemptEvent): List<AttemptEffect> {
        if (event is AttachOk || event is AttachFailed || event is SessionStarted || event is PeerDiscovered ||
            event is KnockIn || event is ChallengeIn || event is PickIn || event is ArmedIn ||
            event is NetworkAvailable || event is NetworkReady || event is NetworkUnavailable || event is NetworkLost ||
            event is ServerListening || event is ServerFailed
        ) {
            return listOf(log("ignored", "reason" to "wrong_bearer"))
        }
        return when (event) {
            is Start -> startDirect()
            is BearerPrepared -> bearerPrepared()
            is BearerHostReady -> bearerHostReady(event)
            is BearerJoined -> bearerJoined(event.freqMhz)
            is CredsIn -> credsIn(event)
            is BearerFailed -> if (phase < Phase.HANDING_OFF) bearerFailed(event) else emptyList()
            is RequestFiled -> requestTimer(event)
            is Connected -> connected()
            is ConnectFailed -> end(Outcome(OutcomeKind.FAILED_PEER, "connect_${event.reason}", decline = 6))
            is SocketAccepted -> socketAccepted(event.ordinal)
            is SocketRejected -> listOf(log("socket", "result" to "rejected", "reason" to event.reason))
            is BindFinished -> bindFinished(event)
            is HandOffResult -> handOffResult(event.accepted)
            is TimerFired -> directTimer(event.kind)
            else -> emptyList()
        }
    }

    private fun startDirect(): List<AttemptEffect> {
        if (phase != Phase.IDLE) return emptyList()
        phase = Phase.ATTACHING
        val effects = ArrayList<AttemptEffect>()
        if (role == AttemptRole.HOST) {
            effects += PrepareBearer
        } else {
            effects += Arm(TimerKind.ATTEMPT, WifiLimits.ATTEMPT_MAX_MS)
            effects += Arm(TimerKind.PICK, WifiLimits.DISCOVERY_TO_PICK_MS)
            effects += PrepareBearer
        }
        return effects
    }

    private fun bearerPrepared(): List<AttemptEffect> {
        if (phase != Phase.ATTACHING) return emptyList()
        phase = Phase.REQUESTING
        val effects = ArrayList<AttemptEffect>()
        effects += Arm(TimerKind.REQUEST, WifiAttemptLimits.REQUEST_WATCHDOG_MS)
        effects += if (role == AttemptRole.HOST) StartBearerHost else StartBearerGuest
        return effects
    }

    private fun bearerHostReady(event: BearerHostReady): List<AttemptEffect> {
        if (phase != Phase.REQUESTING || role != AttemptRole.HOST) return emptyList()
        hostSent = true
        phase = Phase.CONNECTING
        return listOf(
            Cancel(TimerKind.REQUEST),
            Arm(TimerKind.ATTEMPT, WifiLimits.ATTEMPT_MAX_MS),
            SendHost,
            SendCreds(event.port, event.ipv4.copyOf()),
            Arm(TimerKind.CONNECT, WifiLimits.DATA_PATH_TIMEOUT_MS),
            log("host", "dir" to "sent"),
        )
    }

    private fun credsIn(event: CredsIn): List<AttemptEffect> {
        if (role != AttemptRole.GUEST) return listOf(log("creds", "ignored" to "host")) // only a Direct guest joins [P3-4]
        if (phase != Phase.IDLE && phase != Phase.ATTACHING && phase != Phase.REQUESTING && phase != Phase.ADDRESSING) {
            return emptyList()
        }
        if (!event.ticketMatches) return listOf(log("creds", "stale" to true))
        if (credsSeen) return emptyList()
        credsSeen = true
        credsPort = event.port
        credsIpv4 = event.ipv4.copyOf()
        val effects = ArrayList<AttemptEffect>()
        effects += Cancel(TimerKind.PICK)
        if (joined) {
            phase = Phase.CONNECTING
            effects += Arm(TimerKind.CONNECT, WifiLimits.DATA_PATH_TIMEOUT_MS)
            effects += ConnectBearer(credsIpv4!!.copyOf(), credsPort)
        }
        return effects
    }

    private fun bearerJoined(freqMhz: Int?): List<AttemptEffect> {
        if (phase != Phase.REQUESTING) return emptyList()
        joined = true
        val effects = ArrayList<AttemptEffect>()
        effects += Cancel(TimerKind.REQUEST)
        if (credsSeen) {
            phase = Phase.CONNECTING
            effects += Cancel(TimerKind.PICK)
            effects += Arm(TimerKind.CONNECT, WifiLimits.DATA_PATH_TIMEOUT_MS)
            effects += ConnectBearer(credsIpv4!!.copyOf(), credsPort)
        } else {
            phase = Phase.ADDRESSING
        }
        return effects
    }

    private fun bearerFailed(event: BearerFailed): List<AttemptEffect> {
        val kind = when {
            event.decline == 6 && event.local -> OutcomeKind.FAILED_LOCAL
            event.decline == 6 -> OutcomeKind.FAILED_PEER
            else -> OutcomeKind.NO_PENALTY
        }
        val decline = if (role == AttemptRole.GUEST || hostSent) event.decline else null
        return end(Outcome(kind, "bearer_${event.reason}", decline = decline, busy = event.decline == 2))
    }

    private fun directTimer(kind: TimerKind): List<AttemptEffect> = when (kind) {
        TimerKind.PICK -> if (role == AttemptRole.GUEST && !credsSeen) {
            end(Outcome(OutcomeKind.FAILED_PEER, "creds_timeout", decline = 6))
        } else {
            emptyList()
        }
        TimerKind.REQUEST -> if (phase == Phase.REQUESTING) {
            end(Outcome(OutcomeKind.FAILED_PEER, "ndp_timeout", decline = 6))
        } else {
            emptyList()
        }
        TimerKind.CONNECT -> if (phase == Phase.CONNECTING) {
            end(
                Outcome(
                    OutcomeKind.FAILED_PEER,
                    if (role == AttemptRole.HOST) "no_connect" else "connect_timeout",
                    decline = 6,
                ),
            )
        } else {
            emptyList()
        }
        TimerKind.BIND -> if (phase == Phase.BINDING) {
            end(Outcome(OutcomeKind.FAILED_PEER, "bind_timeout", decline = 6))
        } else {
            emptyList()
        }
        else -> emptyList()
    }

    // ----- release -----

    private fun end(outcome: Outcome): List<AttemptEffect> {
        if (phase == Phase.DONE) return emptyList()
        phase = Phase.DONE
        val effects = ArrayList<AttemptEffect>()
        for (kind in TimerKind.entries) effects += Cancel(kind)
        effects += log(
            "end",
            "outcome" to outcome.kind.name.lowercase(),
            "reason" to outcome.reason,
            "fault" to outcome.fault,
        )
        outcome.decline?.let { effects += SendDecline(it) }
        effects += End(outcome)
        return effects
    }

    private fun log(event: String, vararg fields: Pair<String, Any?>): LogEvent = LogEvent(event, fields.toList())
}
