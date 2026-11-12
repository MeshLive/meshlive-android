package com.example.bluetoothtest

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Experimental `test_slow_2m_in_place` ([PhySwitches.slow2mInPlace], default off, runner tunable `slow_2m_in_place`): a
 * caller whose first symmetric LE 2M call link stalls or crawls during the hop or end-to-end handshake first asks THAT SAME
 * live link for LE 1M and keeps the handshake running, and closes it for today's 2M -> 1M setup retry only when 1M is not
 * confirmed or the handshake still makes no progress. When that close happens, the retry's rediscovery scan also lasts until
 * the callee's own incoming-handshake deadline has released it ([rediscoverBudgetMs]).
 *
 * Why (measured, B10/B12, 2026-11-11, user launch, DECISIONS "A shorter incoming handshake bound does not rescue the A15s
 * calling the ASUS"): the OPPO A15s (MT6765, Android 10) calling the ASUS (Android 11) on symmetric 2M either STALLED (the 3 s
 * guard closed the link, `reason=setup_stall | action=close`, and the 1M retry had to rediscover the callee: dial -> ringing
 * 9.8-14.9 s) or CRAWLED (hop authentication 1363-1761 ms, then the end-to-end layer took 12.0 s, or the link died with an
 * EOFException after authenticating). After a stall close the callee often never hears the close (no LL_TERMINATE from the
 * A15s controller), keeps its incoming handshake until its 20 s deadline ([DualSetupBudget.INCOMING_HANDSHAKE_MS]) without
 * advertising, while the caller's scan lasts 17 s (CoC) / 18.5 s (GATT) ([StallRediscover.releaseWindowMs]): 4 of 18
 * A15s -> ASUS release-gate calls never rang. Earlier records of the pair: every symmetric 2M link died or crawled while 3 of 3
 * 1M links were clean, and a live stream link that crawled on 2M recovered at link speed after an in-place move to 1M
 * (`stalled_one_way`). The per-peer bad-2M memory cannot help: a user launch sees an unrecognised rotating offer, which has no
 * memory key ([AdaptivePhyMemory.offerMemoryNode] null).
 *
 * The crawl, from what the caller itself observes ([Watch.triggerOf]):
 * - [HOP_CRAWL_MS] 1.5 s without hop authentication after the link opened. Healthy hop authentication: 200-249 ms (A15s ->
 *   S22, B10), 471 ms (A15s -> ASUS, the one fast 2M setup), 129-259 ms on 1M; 353 saved setups had p90 at most 1050 ms and no
 *   healthy one above 1050 ms ([AdaptivePhyMemory.SETUP_STALL_MS]). The crawls were 1363 and 1761 ms; 1.5 s is 1.4 x the worst
 *   healthy setup and still leaves 1.5 s before the 3 s guard for the PHY procedure.
 * - [ENDPOINT_CRAWL_MS] 2 s from hop authentication without the end-to-end handshake done. Healthy: 265-340 ms (A15s -> S22),
 *   1108 ms on the slowest clean A15s -> ASUS setup; the crawl took 12.0 s.
 * - [NO_PROGRESS_MS] 1.5 s with no byte received from the peer and no handshake milestone. Each handshake step is a round
 *   trip of a few hundred bytes; the healthy end-to-end layer finishes its whole exchange in about 0.3 s.
 *
 * The move: the link layer's PHY update procedure (Core v5.4 Vol 6 Part B 5.1.10) changes the PHY of the live ACL without
 * losing data (unacknowledged PDUs are retransmitted, and the GATT/CoC byte streams the handshake reads and writes keep their
 * queued bytes), so the handshake simply resumes; a central's 1M-only preference is enough for the result to be 1M. GATT asks
 * the call's own client ([GattVideoConnection.requestPhy]); LE CoC asks the control GATT connection the socket rides on (one
 * ACL per pair: [BleL2capControlConnection.requestPhyInPlace]). Both go through the link's [LlProcedureGate] as every PHY
 * request does. 1M is CONFIRMED only by a PHY the stack reported after the request ([LinkPhyView], from the link's PHY
 * summary, readPhy polled at 200 ms then every 400 ms, as [PhyWaitPlan.ANDROID_13]), within [CONFIRM_MS] (that plan's 2 s).
 *
 * The bounds (never earlier than today's close): before hop authentication the link is closed into today's setup retry
 * (`outcome=fallback_close`) only once the stall guard has passed AND 1M was not confirmed within [CONFIRM_MS], or the
 * handshake made no progress for [PROGRESS_AFTER_MOVE_MS] after the confirmation, or hop authentication is still missing at
 * the guard plus [EXTENSION_MS] (6 s with the 3 s guard). After hop authentication there is no close today, so there is none
 * here either: an unconfirmed or unprogressing move ends the watch (`outcome=unresolved`) and the existing caller and callee
 * deadlines apply unchanged.
 *
 * Scope: the first, guarded symmetric 2M attempt of a direct LE call to a peer with no memory key (no `test_ble_phy` arm, no
 * static or remembered split). Recognised peers, relayed offers, the remembered/early_loss paths, the callee's incoming
 * handshake code and deadlines, and the handshake content, authentication and encryption are unchanged: only the PHY of the
 * caller's own link and its own timers change. Lines: `BENCHMARK adaptive_phy: link=call | node=... | phy=2M |
 * reason=slow_2m_in_place | trigger=... | outcome=moved|confirmed|fallback_close|unresolved|not_two_m | sinceOpenMs=...`.
 */
internal object SlowTwoMInPlace {
    const val HOP_CRAWL_MS = 1_500L
    const val ENDPOINT_CRAWL_MS = 2_000L
    const val NO_PROGRESS_MS = 1_500L
    const val CONFIRM_MS = 2_000L
    const val READ_FIRST_MS = 200L
    const val READ_EVERY_MS = 400L
    const val PROGRESS_AFTER_MOVE_MS = 1_500L
    const val EXTENSION_MS = 3_000L
    const val POLL_MS = 100L

    /** The rediscovery window after an in-place fallback close never exceeds this (23.5 s would cover a close at 0 s on GATT). */
    const val WINDOW_CAP_MS = 23_000L

    /**
     * Whether this dial gets the in-place move: the switch, a symmetric [BlePhyPreference.TWO_M] setup on LE (no `test_ble_phy`
     * arm, no remembered or static split), a direct offer, and no memory key (an unrecognised rotating offer, whose stall the
     * per-peer memory can never prevent). The caller also needs the stall guard armed (the first attempt that can retry).
     */
    fun applies(
        enabled: Boolean,
        setupPhy: BlePhyPreference,
        le: Boolean,
        direct: Boolean,
        hasMemoryKey: Boolean,
        splitApplied: Boolean,
        blePhyArm: Boolean,
        staticSplit: Boolean,
    ): Boolean = enabled && le && direct && !hasMemoryKey && setupPhy == BlePhyPreference.TWO_M &&
        !splitApplied && !blePhyArm && !staticSplit

    /**
     * The rediscovery scan after an in-place fallback close [closeSinceOpenMs] after the link opened: at least until the
     * callee's incoming-handshake deadline (it started when the callee accepted, at or before this link opened) released the
     * dead link, plus its restart ([calleeRestartMs]: GATT 1.5 s, CoC 0) and the scan slack ([StallRediscover.SCAN_SLACK_MS]),
     * capped at [WINDOW_CAP_MS]. Never shorter than [baseMs] (today's budget). Null [closeSinceOpenMs] (no in-place close):
     * [baseMs] unchanged. Cost: a callee that is really gone is reported up to about 2-3 s later than today (close at 3 s:
     * GATT 20.5 s vs 18.5 s, CoC 19 s vs 17 s); a present callee is still dialled as soon as its advertisement arrives.
     */
    fun rediscoverBudgetMs(baseMs: Long, closeSinceOpenMs: Long?, calleeRestartMs: Long): Long {
        if (closeSinceOpenMs == null || closeSinceOpenMs < 0L) return baseMs
        val calleeWindow = (DualSetupBudget.INCOMING_HANDSHAKE_MS - closeSinceOpenMs + calleeRestartMs.coerceAtLeast(0L) +
            StallRediscover.SCAN_SLACK_MS).coerceIn(0L, WINDOW_CAP_MS)
        return maxOf(baseMs, calleeWindow)
    }

    fun line(node: String, trigger: String?, outcome: String, sinceOpenMs: Long, detail: String = ""): String =
        "BENCHMARK adaptive_phy: link=call | node=$node | phy=2M | reason=slow_2m_in_place | trigger=${trigger ?: "none"} | " +
            "outcome=$outcome | sinceOpenMs=$sinceOpenMs" + (if (detail.isEmpty()) "" else " | $detail")

    /** What the link's PHY summary ("PHY TX 2M / RX 2M") says. */
    enum class LinkPhyView {
        /** Both directions reported 1M. */
        ONE_M,

        /** At least one direction reported 2M. */
        TWO_M,

        /** Pending, unavailable, Coded or unknown. */
        OTHER,
        ;

        companion object {
            fun of(summary: String): LinkPhyView = when {
                "TX 1M / RX 1M" in summary -> ONE_M
                "2M" in summary -> TWO_M
                else -> OTHER
            }
        }
    }

    /** One look at the attempt, in ms since the link opened. */
    data class Observation(
        val sinceOpenMs: Long,
        /** When hop authentication finished (ms since open), null while it is pending. */
        val hopAuthAtMs: Long?,
        /** When the last byte from the peer arrived (ms since open); 0 when none arrived yet. */
        val lastProgressAtMs: Long,
        val phy: LinkPhyView,
        /** The setup ended (the end-to-end handshake finished, or the attempt failed). */
        val finished: Boolean,
    )

    enum class Action {
        NONE,

        /** Ask the link for 1M now, then report the result with [Watch.onRequest]. */
        REQUEST_1M,

        /** Read the link's PHY (starts no LL procedure). */
        READ_PHY,

        /** The stack reported 1M after the request. */
        CONFIRMED,

        /** Close the still-unauthenticated link into today's setup retry (only after the stall guard). */
        FALLBACK_CLOSE,

        /** After hop authentication: the move did not help; stop watching, the existing deadlines apply. */
        UNRESOLVED,

        /** The crawl was seen but the stack already reports 1M: not a 2M crawl, today's guard applies. */
        NOT_TWO_M,
    }

    data class Decision(val action: Action, val cause: String = "")

    /** The decisions of one attempt; [decide] is called every [POLL_MS] by one watcher thread. */
    class Watch(
        private val guardMs: Long,
        private val hopCrawlMs: Long = HOP_CRAWL_MS,
        private val endpointCrawlMs: Long = ENDPOINT_CRAWL_MS,
        private val noProgressMs: Long = NO_PROGRESS_MS,
        private val confirmMs: Long = CONFIRM_MS,
        private val progressAfterMoveMs: Long = PROGRESS_AFTER_MOVE_MS,
        private val extensionMs: Long = EXTENSION_MS,
    ) {
        private enum class Step { WATCH, MOVING, MOVED, GUARD_ONLY, DONE }

        private var step = Step.WATCH
        private var movedAtMs = -1L
        private var confirmedAtMs = -1L
        private var nextReadAtMs = -1L
        private var guardCause = ""

        /** The crawl that started the move (`hop_crawl`, `endpoint_crawl`, `no_progress`, `setup_stall`), or null. */
        @Volatile var trigger: String? = null
            private set

        /** The move was requested and queued. */
        @Volatile var moved = false
            private set

        /** 1M was confirmed after the move. */
        @Volatile var confirmed = false
            private set

        val done: Boolean get() = step == Step.DONE

        fun triggerOf(o: Observation): String? {
            val now = o.sinceOpenMs
            val hopAt = o.hopAuthAtMs
            if (hopAt == null) {
                if (now >= minOf(hopCrawlMs, guardMs)) return if (now >= guardMs) "setup_stall" else "hop_crawl"
            } else if (now - hopAt >= endpointCrawlMs) {
                return "endpoint_crawl"
            }
            if (now - maxOf(o.lastProgressAtMs, hopAt ?: 0L) >= noProgressMs) return "no_progress"
            return null
        }

        @Synchronized
        fun decide(o: Observation): Decision {
            if (step == Step.DONE) return Decision(Action.NONE)
            if (o.finished) {
                step = Step.DONE
                return Decision(Action.NONE)
            }
            val now = o.sinceOpenMs
            val hopPending = o.hopAuthAtMs == null
            return when (step) {
                Step.WATCH -> {
                    val crawl = triggerOf(o) ?: return Decision(Action.NONE)
                    trigger = crawl
                    if (o.phy == LinkPhyView.ONE_M) {
                        guardOnly(hopPending, "not_two_m")
                        Decision(Action.NOT_TWO_M, crawl)
                    } else {
                        step = Step.MOVING
                        movedAtMs = now
                        nextReadAtMs = now + READ_FIRST_MS
                        Decision(Action.REQUEST_1M, crawl)
                    }
                }
                Step.MOVING -> when {
                    o.phy == LinkPhyView.ONE_M -> {
                        step = Step.MOVED
                        confirmedAtMs = now
                        confirmed = true
                        Decision(Action.CONFIRMED)
                    }
                    now - movedAtMs >= confirmMs -> if (hopPending) closeAtGuard(now, "unconfirmed") else unresolved("unconfirmed")
                    now >= nextReadAtMs -> {
                        nextReadAtMs = now + READ_EVERY_MS
                        Decision(Action.READ_PHY)
                    }
                    else -> Decision(Action.NONE)
                }
                Step.MOVED -> {
                    val quiet = now - maxOf(confirmedAtMs, o.lastProgressAtMs, o.hopAuthAtMs ?: 0L) >= progressAfterMoveMs
                    when {
                        hopPending && now >= guardMs && quiet -> close("no_progress")
                        hopPending && now >= guardMs + extensionMs -> close("no_auth")
                        !hopPending && quiet -> unresolved("no_progress")
                        else -> Decision(Action.NONE)
                    }
                }
                Step.GUARD_ONLY -> if (hopPending) closeAtGuard(now, guardCause) else {
                    step = Step.DONE
                    Decision(Action.NONE)
                }
                Step.DONE -> Decision(Action.NONE)
            }
        }

        /** The result of [Action.REQUEST_1M]: queued keeps waiting for the confirmation; refused falls back like today. */
        @Synchronized
        fun onRequest(queued: Boolean, o: Observation): Decision {
            if (queued) {
                moved = true
                return Decision(Action.NONE)
            }
            return if (o.hopAuthAtMs == null) {
                guardOnly(true, "not_queued")
                closeAtGuard(o.sinceOpenMs, "not_queued")
            } else {
                unresolved("not_queued")
            }
        }

        private fun guardOnly(hopPending: Boolean, cause: String) {
            guardCause = cause
            step = if (hopPending) Step.GUARD_ONLY else Step.DONE
        }

        private fun closeAtGuard(now: Long, cause: String): Decision =
            if (now >= guardMs) close(cause) else Decision(Action.NONE)

        private fun close(cause: String): Decision {
            step = Step.DONE
            return Decision(Action.FALLBACK_CLOSE, cause)
        }

        private fun unresolved(cause: String): Decision {
            step = Step.DONE
            return Decision(Action.UNRESOLVED, cause)
        }
    }

    /** When the last byte from the peer arrived on this attempt's link ([clockMs] time; 0 before any). */
    class Progress(private val clockMs: () -> Long) {
        @Volatile var lastAtMs = 0L
            private set

        fun mark() {
            lastAtMs = clockMs()
        }

        /** [lastAtMs] in ms since [openedAtMs]; 0 when nothing arrived yet. */
        fun sinceOpen(openedAtMs: Long): Long = if (lastAtMs <= 0L) 0L else (lastAtMs - openedAtMs).coerceAtLeast(0L)
    }

    /** The link's input, marking [progress] whenever bytes arrive. Bytes pass through unchanged. */
    class ProgressInputStream(input: InputStream, private val progress: Progress) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) progress.mark() }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) progress.mark() }
    }
}
