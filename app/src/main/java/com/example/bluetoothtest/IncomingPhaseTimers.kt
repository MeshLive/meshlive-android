package com.example.bluetoothtest

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Design PP package A (2026-11-10): the phases of ONE incoming connection, each with its own monotonic milestone and at
 * most one timer that owns the wait before it. Nothing here reads a wire field, relaxes a check or adds a record: the timers
 * only observe that a milestone has not happened yet.
 *
 * Review of design PP (finding 10): today's single 20 s watchdog ([DualSetupBudget.INCOMING_HANDSHAKE_MS], unchanged and
 * still armed exactly as before) ends at `contentSession`, so it spans the hop handshake, the link opening, the first record,
 * the end-to-end handshake (through a relay chain, when relayed) and local setup, and nothing bounds the time between the
 * finished end-to-end handshake and the first INVITE (SEC-06). The two timers below are separate, incoming-only and off by
 * default; each ends at its own milestone and never reuses `contentSession == null` as its predicate.
 */
internal enum class IncomingMilestone(val label: String) {
    /** The transport handed this phone the connection (GATT CCCD write, accepted CoC/RFCOMM socket, Wi-Fi socket). */
    TRANSPORT_ACCEPT("transport_accept"),

    /** The first handshake frame arrived and was read. Instrumented on the BMH2 path only (BMH3 has no hook before msg1 is handled). */
    FIRST_HANDSHAKE_FRAME("first_handshake_frame"),

    /** The hop handshake and the link opening (BDO1, when required) finished: the link peer is verified. Owned by [IncomingPhaseTimers.hop]. */
    HOP_VERIFIED("hop_verified"),

    /** The end-to-end handshake finished (through a relay chain, when relayed); local codec and session setup follows. */
    ENDPOINT_AUTHENTICATED("endpoint_authenticated"),

    /** The reader that consumes the first signal is running; from here an INVITE can be received. Starts [IncomingPhaseTimers.preInvite]. */
    READER_STARTED("reader_started"),

    /** The first valid INVITE of an ordinary incoming call was read. Ends [IncomingPhaseTimers.preInvite]. */
    FIRST_INVITE("first_invite"),
}

/** What an authenticated incoming endpoint session is for. A verification-only (BVMID2) session never sends an INVITE. */
internal enum class IncomingSessionKind { CALL, VERIFICATION_ONLY }

/**
 * One timer with one owner and exactly one terminal outcome. A timer that is disarmed by its milestone and one that
 * expires race on the same compare-and-set, so a milestone arriving at the deadline instant has exactly one outcome:
 * [disarm] returns false when the expiry won (the connection is being closed; the milestone must not be acted on), and
 * [expireIfDue] returns false when the milestone won (nothing is closed).
 */
internal class PhaseTimer(
    val name: String,
    private val clockNs: () -> Long = System::nanoTime,
) {
    private val state = AtomicInteger(IDLE)
    private val settled = CountDownLatch(1)

    @Volatile var boundMs: Long = 0L
        private set

    @Volatile private var deadlineNs: Long = 0L

    /** Starts the clock at now; false when the timer was already armed, disarmed (the milestone came first) or expired. */
    @Synchronized
    fun arm(boundMs: Long): Boolean {
        if (state.get() != IDLE) return false
        this.boundMs = boundMs
        deadlineNs = clockNs() + boundMs * 1_000_000L
        return state.compareAndSet(IDLE, ARMED)
    }

    /**
     * The milestone happened. True: the timer will never expire (it was armed and is now disarmed, was never armed, or was
     * already disarmed). False: the deadline already won, this connection is being closed and the milestone is moot.
     * A never-armed timer becomes disarmed, so a milestone that races ahead of [arm] cannot be charged a deadline later.
     */
    fun disarm(): Boolean {
        while (true) {
            when (state.get()) {
                ARMED -> if (state.compareAndSet(ARMED, DISARMED)) {
                    settled.countDown()
                    return true
                }
                IDLE -> if (state.compareAndSet(IDLE, DISARMED)) return true
                EXPIRED -> return false
                else -> return true
            }
        }
    }

    /** True exactly once, when the deadline has passed and no milestone settled the timer first: the caller then closes the connection. */
    fun expireIfDue(): Boolean {
        if (clockNs() < deadlineNs) return false
        if (!state.compareAndSet(ARMED, EXPIRED)) return false
        settled.countDown()
        return true
    }

    /** Blocks until the deadline or until the timer is settled (disarmed or expired); the caller then calls [expireIfDue]. */
    fun awaitDue() {
        while (state.get() == ARMED) {
            val remaining = deadlineNs - clockNs()
            if (remaining <= 0L) return
            settled.await(remaining, TimeUnit.NANOSECONDS)
        }
    }

    val armed: Boolean get() = state.get() == ARMED
    val expired: Boolean get() = state.get() == EXPIRED

    private companion object {
        const val IDLE = 0
        const val ARMED = 1
        const val DISARMED = 2
        const val EXPIRED = 3
    }
}

/**
 * The bounds, chosen from saved runs (DECISIONS "Design PP"; evidence in the change note). A timer exists only where the
 * timing was measured: BLE GATT and BLE CoC over Bluetooth. BMH2 hops (RV8 tarpit, legacy peers), Classic, Wi-Fi and a
 * relayed endpoint keep the existing conservative bound ([DualSetupBudget.INCOMING_HANDSHAKE_MS], 20 s) until measured.
 */
internal object IncomingPhaseBounds {
    /**
     * Accept to verified hop plus opening: the caller's own hop bound ([CallSetupDeadlines.HOP_AUTH_MS]). An honest initiator
     * gives up on a hop after that long from its link open, which is never later than this side's accept, so this side never
     * closes a hop an honest caller would still wait for. It also covers the unchanged responder signing budget's wait (at
     * most 4 s, [Bmh3SigningBudget]) and the lost-chunk recovery of a slow legacy GATT stack: the worst honest accept-to-hop
     * time on record is 6.2 s (an Android 11 legacy-Qualcomm phone; the typical one is 0.1-0.4 s).
     */
    const val HOP_AUTH_MS = CallSetupDeadlines.HOP_AUTH_MS

    /**
     * Reader started to the first INVITE of an ordinary direct call. The caller sends its INVITE right after its own end-to-end
     * handshake (its local codec/session set-up included: 0.03-0.6 s on record); the bound covers the lost-chunk recovery of a
     * slow legacy GATT stack, which stretched a 600-byte handshake to 10 s on the slowest phone on record, and stays below
     * the caller's own answer bound ([CallSetupDeadlines.CALLER_ANSWER_DIRECT_MS], 15 s from its hop authentication).
     */
    const val PRE_INVITE_MS = 10_000L

    /** Where the wait is unmeasured (Classic, Wi-Fi, a relayed endpoint, any BMH2 hop): the conservative bound, today's. */
    const val CONSERVATIVE_MS = DualSetupBudget.INCOMING_HANDSHAKE_MS

    private fun measured(transport: BluetoothTransportOption, carrier: CarrierKind): Boolean =
        carrier == CarrierKind.BLUETOOTH &&
            (transport == BluetoothTransportOption.BLE_GATT || transport == BluetoothTransportOption.BLE_L2CAP)

    /** The hop bound for a connection arriving on [transport], or null where no shorter-than-today bound is measured. */
    fun hopBoundMs(transport: BluetoothTransportOption, carrier: CarrierKind): Long? =
        if (measured(transport, carrier)) HOP_AUTH_MS else null

    /** The pre-INVITE bound; never shorter than measured, and the conservative one for relayed or unmeasured endpoints. */
    fun preInviteBoundMs(transport: BluetoothTransportOption, carrier: CarrierKind, viaRelay: Boolean): Long =
        if (!viaRelay && measured(transport, carrier)) PRE_INVITE_MS else CONSERVATIVE_MS
}

/**
 * The state of one accepted connection: a generation (a new accept is a new connection, so a stale timer can never close
 * or disarm a later one), its monotonic milestones, and the two timers. Created for every accepted connection; with both
 * switches off no timer is ever armed and the object only keeps timestamps for the DEBUG milestone line.
 */
internal class IncomingPhaseTimers(
    val generation: Long,
    private val hopSwitch: Boolean,
    private val preInviteSwitch: Boolean,
    private val clockNs: () -> Long = System::nanoTime,
) {
    val hop = PhaseTimer("hop_auth", clockNs)
    val preInvite = PhaseTimer("pre_invite", clockNs)
    private val marks = ConcurrentHashMap<IncomingMilestone, Long>()

    @Volatile var legacyHop: Boolean = false
        private set

    /** Records the milestone once on the monotonic clock; false when it was already recorded. */
    fun mark(milestone: IncomingMilestone): Boolean = marks.putIfAbsent(milestone, clockNs()) == null

    fun sinceAcceptMs(milestone: IncomingMilestone): Long? {
        val accepted = marks[IncomingMilestone.TRANSPORT_ACCEPT] ?: return null
        val at = marks[milestone] ?: return null
        return (at - accepted) / 1_000_000L
    }

    /** The accept itself: records the milestone and, with the hop switch on and a measured transport, arms the hop timer. Returns the bound armed, or null. */
    fun accepted(transport: BluetoothTransportOption, carrier: CarrierKind): Long? {
        mark(IncomingMilestone.TRANSPORT_ACCEPT)
        if (!hopSwitch) return null
        val bound = IncomingPhaseBounds.hopBoundMs(transport, carrier) ?: return null
        return bound.takeIf { hop.arm(it) }
    }

    /**
     * The peer's first frame is a BMH2 hello: a legacy hop (RV8 tarpit delay, older builds) whose honest time is not measured.
     * It keeps today's conservative bound only, so the short hop timer is withdrawn; false when it had already expired.
     */
    fun legacyFirstFrame(): Boolean {
        mark(IncomingMilestone.FIRST_HANDSHAKE_FRAME)
        legacyHop = true
        return hop.disarm()
    }

    /** The hop and the link opening finished. False: the hop deadline won first and this connection is being closed. */
    fun hopVerified(): Boolean {
        mark(IncomingMilestone.HOP_VERIFIED)
        return hop.disarm()
    }

    /** The end-to-end handshake finished; recorded for the milestone line only (the pre-INVITE clock starts when the reader can read). */
    fun endpointAuthenticated() {
        mark(IncomingMilestone.ENDPOINT_AUTHENTICATED)
    }

    /**
     * The reader is about to start on an authenticated endpoint session. The pre-INVITE timer is armed only for an ordinary
     * incoming call (never a verification-only session, which never sends an INVITE and keeps its own bounds) and only with
     * its switch on. Returns the bound armed, or null.
     */
    fun readerStarting(
        session: IncomingSessionKind,
        transport: BluetoothTransportOption,
        carrier: CarrierKind,
        viaRelay: Boolean,
    ): Long? {
        mark(IncomingMilestone.READER_STARTED)
        if (!preInviteSwitch || session != IncomingSessionKind.CALL) return null
        val bound = IncomingPhaseBounds.preInviteBoundMs(transport, carrier, viaRelay)
        return bound.takeIf { preInvite.arm(it) }
    }

    /**
     * The first valid INVITE of this connection was read (framing, sequence, call ID and identity checks already passed
     * upstream; block and dial checks follow). True: carry on. False: the deadline won at the same instant, the connection
     * is closed, and the INVITE must not ring.
     */
    fun inviteArrived(): Boolean {
        mark(IncomingMilestone.FIRST_INVITE)
        return preInvite.disarm()
    }

    /** The connection ended (call over, closed, promoted elsewhere): neither timer may close anything afterwards. */
    fun cancelAll() {
        hop.disarm()
        preInvite.disarm()
    }

    fun milestoneLine(milestone: IncomingMilestone): String =
        "incoming_phase: milestone=${milestone.label} | gen=$generation | sinceAcceptMs=${sinceAcceptMs(milestone) ?: -1}"

    fun deadlineLine(timer: PhaseTimer, transport: BluetoothTransportOption, carrier: CarrierKind): String =
        "incoming_phase: event=deadline | timer=${timer.name} | gen=$generation | closed=true | boundMs=${timer.boundMs} | " +
            "transport=${transport.name} | carrier=${carrier.name} | legacyHop=$legacyHop"
}
