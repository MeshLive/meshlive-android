package com.example.bluetoothtest

/**
 * Experimental `test_phy_direction_split` (design decision 2026-11-06, DECISIONS "Owner answers on open questions", GATT PHY
 * split, Q14): on a GATT central's 2M link, move only the direction that fails to 1M and keep the other on 2M
 * (`setPreferredPhy(tx, rx)`), then remember the split that worked per peer so the next link to that peer starts split.
 *
 * Which direction fails is read from what this phone sees on its own link (hop-local, no wire change):
 * - Receive silence ([onTick]): the peer had been heard regularly (data at most [HEARD_GAP_MS] old for [REGULAR_MS]) and
 *   then nothing arrives for [RX_SILENT_MS] while this phone's own writes still complete (the link accepts them, so the
 *   peer acknowledges them): the peer -> this phone direction failed, so the receive direction moves (TX 2M / RX 1M). It is
 *   proven when the peer is heard again within [RX_RECOVER_MS] ([Kind.REMEMBER]); otherwise [Kind.UNPROVEN]: a peer that
 *   left is no evidence about 2M, so nothing is remembered and the link is not moved further.
 * - A backlogged writer ([onVerdict]: `one_pdu_per_event`, or `stalled_one_way` while the peer is heard): a connection event
 *   ends when either direction fails, so the host cannot name the direction from its writer. Step 1 keeps this phone's
 *   media direction on 2M and moves the receive direction (the side the ASUS snoops showed failing, and the static split
 *   that removed every ASUS <-> A15s freeze); if the writer still fails, step 2 tries the mirror (TX 1M / RX 2M); if that
 *   still fails, [Kind.FULL]: the full move to 1M with the old bad-2M memory. The split that the next decision keeps is
 *   the failing direction found ([Kind.REMEMBER]).
 * Pure and single-threaded per link (the link's writer calls it); the caller carries out the actions.
 */
internal class PhyDirectionSplit(
    private val rxSilentMs: Long = RX_SILENT_MS,
    private val regularMs: Long = REGULAR_MS,
    private val recoverMs: Long = RX_RECOVER_MS,
) {
    enum class Kind { NONE, SPLIT, FULL, REMEMBER, UNPROVEN }

    /** One thing for the link to do: [split] is the split to ask for ([Kind.SPLIT]) or the one to remember or report. */
    data class Action(val kind: Kind, val split: GattPhySplit? = null, val step: Int = 0, val reason: String = "")

    /** 0: not split; 1: first split; 2: the mirror split; 3: full 1M (nothing more). */
    var step = 0
        private set

    /** The split this link runs now (null before any). */
    var applied: GattPhySplit? = null
        private set

    private var reason = ""
    private var remembered = false
    private var rxPending = false
    private var splitAtMs = -1L
    private var heardSinceMs = -1L
    private var regular = false

    /** A link that asked for [split] at setup (remembered per peer): one more failure tries the mirror; a keep records nothing. */
    fun startedSplit(split: GattPhySplit?) {
        if (split == null || step != 0) return
        step = 1
        applied = split
        reason = REMEMBERED_SPLIT
        remembered = true
    }

    /**
     * The adaptive PHY decision of this link: [writerFailed] for `one_pdu_per_event` or a `stalled_one_way` that moves,
     * false for `kept`. [reason] is the verdict's log name.
     */
    fun onVerdict(writerFailed: Boolean, reason: String): Action {
        if (!writerFailed) {
            if (step !in 1..2 || remembered) return NONE
            remembered = true
            rxPending = false
            return Action(Kind.REMEMBER, applied, step, this.reason)
        }
        rxPending = false
        return when (step) {
            0 -> moveTo(GattPhySplit.TX_2M_RX_1M, 1, reason, -1L)
            1 -> moveTo(mirrorSplit(applied ?: GattPhySplit.TX_2M_RX_1M), 2, reason, -1L)
            2 -> {
                step = 3
                Action(Kind.FULL, applied, 3, reason)
            }
            else -> NONE
        }
    }

    /**
     * One completed write at [nowMs]: [onTwoM] the link reports 2M and is not split; [writerFlowing] this write was accepted
     * without a long block; [quietMs] ms since the peer's last data on this link (-1 before any).
     */
    fun onTick(nowMs: Long, onTwoM: Boolean, writerFlowing: Boolean, quietMs: Long): Action {
        if (quietMs < 0L) return NONE
        val heard = quietMs <= HEARD_GAP_MS
        if (rxPending) {
            if (heard) {
                rxPending = false
                remembered = true
                return Action(Kind.REMEMBER, applied, step, reason)
            }
            if (nowMs - splitAtMs < recoverMs) return NONE
            rxPending = false
            return Action(Kind.UNPROVEN, applied, step, reason)
        }
        if (heard) {
            if (heardSinceMs < 0L) heardSinceMs = nowMs - quietMs
            regular = nowMs - heardSinceMs >= regularMs
            return NONE
        }
        // A gap: [regular] keeps what it was when the peer was last heard.
        heardSinceMs = -1L
        if (step != 0 || !regular || !onTwoM || !writerFlowing || quietMs < rxSilentMs) return NONE
        val action = moveTo(GattPhySplit.TX_2M_RX_1M, 1, RX_SILENT, nowMs)
        rxPending = true
        return action
    }

    private fun moveTo(split: GattPhySplit, next: Int, why: String, nowMs: Long): Action {
        step = next
        applied = split
        reason = why
        remembered = false
        splitAtMs = nowMs
        return Action(Kind.SPLIT, split, next, why)
    }

    companion object {
        /** The peer counts as heard while its last data is at most this old (call heartbeats come every second). */
        const val HEARD_GAP_MS = 1_500L

        /** The peer must have been heard that regularly for this long before its silence counts. */
        const val REGULAR_MS = 4_000L

        /** Three of the peer's 1 s heartbeats missing in a row while this phone's own writes still complete. */
        const val RX_SILENT_MS = 3_000L

        /** After the receive split, the peer must be heard again within this time for the direction to count as proven. */
        const val RX_RECOVER_MS = 3_000L

        /** A write that returned within this time was accepted by the link: this phone's own direction still flows. */
        const val FLOWING_WRITE_BLOCK_MS = 500L

        const val RX_SILENT = "rx_silent"
        const val REMEMBERED_SPLIT = "remembered_split"

        val NONE = Action(Kind.NONE)
    }
}

/**
 * Whether [PhyDirectionSplit] runs on one central link: the switch is on, the link is the GATT client ("gatt"; the CoC control
 * link is not covered), and no static per-direction split (`test_gatt_phy_split`) or experimental PHY (`test_ble_phy`) is armed.
 */
internal fun phyDirectionSplitFor(
    on: Boolean,
    link: String,
    staticSplit: GattPhySplit?,
    experimentalPhy: ExperimentalBlePhy?,
): Boolean = on && link == "gatt" && staticSplit == null && experimentalPhy == null

/** The other split: the direction that stayed on 2M moves to 1M and the other goes back to 2M. */
internal fun mirrorSplit(split: GattPhySplit): GattPhySplit =
    if (split == GattPhySplit.TX_2M_RX_1M) GattPhySplit.TX_1M_RX_2M else GattPhySplit.TX_2M_RX_1M

/** The `direction=` value of the log line: which of this phone's directions went to 1M. */
internal fun phySplitDirectionLabel(split: GattPhySplit): String = if (split == GattPhySplit.TX_2M_RX_1M) "rx" else "tx"
