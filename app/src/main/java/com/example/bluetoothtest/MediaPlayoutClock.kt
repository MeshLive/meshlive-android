package com.example.bluetoothtest

import kotlin.math.max

data class MediaPlayoutTiming(
    val targetTimeNs: Long,
    val latenessMs: Long,
)

/**
 * Maps source audio/video timestamps onto one receiver presentation timeline.
 *
 * With [adaptiveMaxDelayMs] above [delayMs] the delay adapts to this link: media arriving after its
 * target grows the delay by the measured lateness plus a margin (up to the maximum), and after
 * the calm period without late media it shrinks back toward [delayMs] slowly. Bluetooth
 * delivered frames up to 104-245 ms late in bursts; a fixed 250 ms buffer then drained to zero,
 * while 500 ms gave p99 84 ms cadence at a permanent latency cost.
 *
 * Streams remember a burst for 60 s before shrinking (design decision 2026-10-09; was 10 s). Scene-cut frames
 * of 54-101 KB took 0.65-1.3 s to cross S24 -> A15s GATT; the delay grew to cover the first, shrank
 * below it within 20 s and the next scene cut froze again (regress-57484598, 300 kbps: 518 -> 431 ms
 * against a 652 ms gap). Calls keep [CALL_CALM_BEFORE_SHRINK_MS]: holding extra delay for a minute
 * after one burst costs conversation more than an occasional repeat freeze (review finding 2026-10-09).
 */
class MediaPlayoutClock(
    private val delayMs: Long,
    private val maxScheduleAheadMs: Long = MAX_SCHEDULE_AHEAD_MS,
    private val adaptiveMaxDelayMs: Long = delayMs,
    calmBeforeShrinkMs: Long = STREAM_CALM_BEFORE_SHRINK_MS,
    /**
     * Experimental one-step growth (DebugTunables.playoutBurstGrowth via PlaybackDelayProfile, streams only; null = off): media at least
     * [BURST_LATENESS_MS] late grows the delay by this multiple of its lateness at once. With the
     * default lateness + 50 ms per late frame, one GATT scene-cut burst stepped the delay
     * 250 -> ~1030 -> 1350-2000 ms within ~1.5 s, each step a separate hold, and two report rises
     * read as sustained lateness (lane-b F8). The single step trades those holds for one longer hold
     * and more held latency; the A/B measures which users see as better.
     */
    private val burstGrowthFactor: Double? = null,
    /**
     * Experimental time-based target (Q31, DebugTunables.playoutTimeTarget, null = off, streams only):
     * the delay grows to the measured arrival jitter before a frame is late, and calm shrinking stops at
     * it. The lateness rule above still applies, and still reaches [adaptiveMaxDelayMs].
     */
    private val timeTarget: PlayoutJitterTarget? = null,
    /**
     * Call fast recovery ([CallFastRecovery], DEBUG `test_call_fast_recovery`, promoted 2026-11-12; null = off, calls only):
     * after an isolated late burst the audio writer may lower the delay by leaving out quiet audio
     * ([consumeFastRecovery]). Without consumption this clock times media exactly as without it.
     */
    private val fastRecovery: CallFastRecovery? = null,
) {
    /** Delay left out by fast recovery from [fromPtsUs] on, not yet folded into the anchor. */
    private class RecoveryShift(val fromPtsUs: Long, val shiftNs: Long)

    private val calmBeforeShrinkNs = calmBeforeShrinkMs * NANOS_PER_MILLISECOND
    private var anchorPresentationUs: Long? = null
    private var anchorRenderNs = 0L
    private var latestPresentationUs: Long? = null
    private var extraDelayNs = 0L
    private var lastLateNs = Long.MIN_VALUE
    private var lastShrinkNs = Long.MIN_VALUE
    /** Extra delay kept even when calm (see [reserve]). */
    private var reservedExtraNs = 0L
    /** Extra delay the time-based target currently asks for; calm shrinking stops there (0 when off). */
    private var timeFloorNs = 0L
    /** Fast-recovery shifts in PTS order (empty with the experiment off). */
    private val recoveryShifts = ArrayDeque<RecoveryShift>()

    /** Times the anchor moved earlier because packets arrived ahead of a stale anchor. */
    @Volatile var reanchors = 0
        private set

    /** Current target delay including adaptive growth. */
    @Volatile var currentDelayMs = delayMs
        private set

    private val adaptive: Boolean get() = adaptiveMaxDelayMs > delayMs

    /**
     * Late media pushed the adaptive delay to its maximum: buffering no longer absorbs this link's
     * bursts. Healthy runs grew only to 325-346 ms after one late frame; an overloaded CoC link sat
     * at 500 ms with frames up to 1.1 s late (adaptive-643926ef).
     */
    val saturated: Boolean
        get() = isSaturated(System.nanoTime())

    /**
     * Pinned at the maximum and media is still arriving late. A pinned but calm delay is a burst
     * being remembered, not lateness: reporting the level kept the peer "late" for 12-17 s after
     * one burst, and the sender cut every 3 s (regress-57484598 calls).
     */
    @Synchronized
    fun isSaturated(nowNs: Long): Boolean =
        adaptive && currentDelayMs >= adaptiveMaxDelayMs &&
            lastLateNs != Long.MIN_VALUE && nowNs - lastLateNs < SATURATED_LATE_WINDOW_NS

    /**
     * Keeps at least [extraMs] above the starting delay, capped at [RESERVE_RANGE_FRACTION] of the
     * adaptive range so a pinned delay still means late media. Before the first frame it simply
     * starts playback later; calm shrinking never goes below it. A relay keeps this room for the
     * stall when a child joins: the joining child's keyframe crosses the parent link exactly when
     * that link loses airtime to the new child link (1.7 s arrival gap, trend-3641df77).
     */
    @Synchronized
    fun reserve(extraMs: Long) {
        if (!adaptive) return
        val rangeNs = (adaptiveMaxDelayMs - delayMs) * NANOS_PER_MILLISECOND
        reservedExtraNs = (extraMs * NANOS_PER_MILLISECOND).coerceIn(0L, (rangeNs * RESERVE_RANGE_FRACTION).toLong())
        if (anchorPresentationUs == null && extraDelayNs < reservedExtraNs) {
            extraDelayNs = reservedExtraNs
            currentDelayMs = delayMs + extraDelayNs / NANOS_PER_MILLISECOND
        }
    }

    /** Time-based target counters for the viewer's `playout_time_target` line; null with the arm off. */
    fun timeTargetSummary(): String? = timeTarget?.summary()

    /** Delay added above the starting delay to absorb late arrivals on this link. */
    val addedDelayMs: Long
        get() = currentDelayMs - delayMs

    /**
     * Network-arrival timing: anchors the timeline and, for adaptive clocks, grows the delay when
     * this media arrived after its target. Call it once per packet where it is received from the
     * link; post-arrival stages use [targetFor].
     *
     * [growthLimitMs] (experimental speech-led call playout, CallPlayoutPolicy): this arrival may
     * grow the added delay only up to this many ms (null: up to the adaptive maximum, unchanged).
     * Lateness beyond it is still returned (and reported to the peer) but is played late instead of
     * delaying everything that shares the clock, and it does not hold off the calm shrink of delay
     * that other media added above the limit.
     */
    @Synchronized
    fun timing(
        presentationTimeUs: Long,
        nowNs: Long = System.nanoTime(),
        growthLimitMs: Long? = null,
    ): MediaPlayoutTiming {
        val latest = latestPresentationUs
        val effectiveDelayNs = delayMs * NANOS_PER_MILLISECOND + extraDelayNs
        if (
            anchorPresentationUs == null ||
            latest != null && (
                presentationTimeUs > latest + RESET_AFTER_GAP_US ||
                    presentationTimeUs < latest - RESET_AFTER_GAP_US
                )
        ) {
            anchorPresentationUs = presentationTimeUs
            anchorRenderNs = nowNs + effectiveDelayNs
            latestPresentationUs = presentationTimeUs
            timeTarget?.reset()
            recoveryShifts.clear()
        } else if (latest == null || presentationTimeUs > latest) {
            latestPresentationUs = presentationTimeUs
        }

        if (adaptive) shrinkWhenCalm(nowNs)
        foldRecoveryShifts(nowNs)
        var rawTargetNs = mappedTargetNs(presentationTimeUs, requireNotNull(anchorPresentationUs))
        val delayNowNs = delayMs * NANOS_PER_MILLISECOND + extraDelayNs
        val slackNs = maxOf(delayNowNs, EARLY_REANCHOR_SLACK_MS * NANOS_PER_MILLISECOND)
        if (rawTargetNs - nowNs > delayNowNs + slackNs) {
            // The anchor came from a packet that arrived late (e.g. a large first keyframe behind a
            // startup backlog). Holding every later on-time packet by that backlog overflowed the
            // decoder queue and looped on recovery keyframes; restore the configured delay instead.
            anchorPresentationUs = presentationTimeUs
            anchorRenderNs = nowNs + delayNowNs
            rawTargetNs = anchorRenderNs
            recoveryShifts.clear()
            reanchors++
        }
        val latenessNs = nowNs - rawTargetNs
        if (adaptive && latenessNs > LATE_TOLERANCE_NS) {
            val maxExtraNs = (adaptiveMaxDelayMs - delayMs) * NANOS_PER_MILLISECOND
            val limitNs = growthLimitMs?.let { minOf(maxExtraNs, it.coerceAtLeast(0L) * NANOS_PER_MILLISECOND) }
                ?: maxExtraNs
            val withinLimit = extraDelayNs <= limitNs
            val extraBeforeNs = extraDelayNs
            val stepNs = burstGrowthFactor
                ?.takeIf { latenessNs >= BURST_LATENESS_MS * NANOS_PER_MILLISECOND }
                ?.let { factor -> (latenessNs * factor).toLong() }
                ?: (latenessNs + GROWTH_MARGIN_NS)
            val grownNs = (extraDelayNs + stepNs).coerceAtMost(limitNs)
            val deltaNs = grownNs - extraDelayNs
            if (deltaNs > 0) {
                extraDelayNs = grownNs
                anchorRenderNs += deltaNs
                rawTargetNs += deltaNs
            }
            if (withinLimit) {
                lastLateNs = nowNs
                lastShrinkNs = nowNs
                fastRecovery?.onLate(
                    nowNs / NANOS_PER_MILLISECOND,
                    extraBeforeNs / NANOS_PER_MILLISECOND,
                    extraDelayNs / NANOS_PER_MILLISECOND,
                )
            }
        }
        if (adaptive && timeTarget != null) {
            timeTarget.onArrival(nowNs, presentationTimeUs)
            val neededMs = timeTarget.neededDelayMs(nowNs)
            if (neededMs != null) {
                val capMs = minOf(adaptiveMaxDelayMs, timeTarget.budgetMs)
                val wantExtraNs = (minOf(neededMs, capMs) - delayMs).coerceAtLeast(0L) * NANOS_PER_MILLISECOND
                timeFloorNs = wantExtraNs
                if (wantExtraNs > extraDelayNs) {
                    // Grows to cover the measured jitter in one step. Not late media: the saturation
                    // flag and the calm-shrink timers belong to the lateness rule.
                    val deltaNs = wantExtraNs - extraDelayNs
                    extraDelayNs = wantExtraNs
                    anchorRenderNs += deltaNs
                    rawTargetNs += deltaNs
                    timeTarget.noteGrow()
                }
            }
        }
        currentDelayMs = delayMs + extraDelayNs / NANOS_PER_MILLISECOND
        val latestAllowedTargetNs = nowNs + maxScheduleAheadMs * NANOS_PER_MILLISECOND
        return MediaPlayoutTiming(
            targetTimeNs = rawTargetNs.coerceAtMost(latestAllowedTargetNs),
            latenessMs = max(0L, nowNs - rawTargetNs) / NANOS_PER_MILLISECOND,
        )
    }

    /** Target of [presentationTimeUs] on the current timeline, after the fast-recovery shifts that apply to it. */
    private fun mappedTargetNs(presentationTimeUs: Long, anchorUs: Long): Long {
        var targetNs = anchorRenderNs + (presentationTimeUs - anchorUs) * NANOS_PER_MICROSECOND
        for (shift in recoveryShifts) if (presentationTimeUs >= shift.fromPtsUs) targetNs -= shift.shiftNs
        return targetNs
    }

    /**
     * Folds a shift into the anchor once media at its PTS is due: anything earlier still unplayed is late by then and
     * plays at once either way. Keeps the list to a few entries (one per 80 ms of pause at most).
     */
    private fun foldRecoveryShifts(nowNs: Long) {
        val anchorUs = anchorPresentationUs ?: return
        while (recoveryShifts.isNotEmpty()) {
            val first = recoveryShifts.first()
            if (recoveryShifts.size <= MAX_RECOVERY_SHIFTS && mappedTargetNs(first.fromPtsUs, anchorUs) > nowNs) return
            anchorRenderNs -= first.shiftNs
            recoveryShifts.removeFirst()
        }
    }

    /** Experimental call fast recovery is attached ([CallFastRecovery]). */
    val hasFastRecovery: Boolean
        get() = fastRecovery != null

    /** Delay (ms) fast recovery may still remove by leaving out quiet audio now; 0 when off or not armed. */
    @Synchronized
    fun fastRecoveryPendingMs(nowNs: Long = System.nanoTime()): Long {
        val recovery = fastRecovery ?: return 0L
        if (anchorPresentationUs == null) return 0L
        return recovery.pendingMs(
            nowNs / NANOS_PER_MILLISECOND,
            extraDelayNs / NANOS_PER_MILLISECOND,
            maxOf(reservedExtraNs, timeFloorNs) / NANOS_PER_MILLISECOND,
        )
    }

    /**
     * The audio writer left out [ms] of quiet audio just before [fromPtsUs]: media at or after that sender time plays
     * [ms] earlier (video and audio skip at the same point, so lip sync holds). Returns false, changing nothing, unless
     * fast recovery is armed with at least [ms] still to remove.
     */
    @Synchronized
    fun consumeFastRecovery(ms: Long, fromPtsUs: Long, nowNs: Long = System.nanoTime()): Boolean {
        val recovery = fastRecovery ?: return false
        if (ms <= 0L || anchorPresentationUs == null) return false
        if (fastRecoveryPendingMs(nowNs) < ms) return false
        foldRecoveryShifts(nowNs)
        val shiftNs = ms * NANOS_PER_MILLISECOND
        extraDelayNs -= shiftNs
        currentDelayMs = delayMs + extraDelayNs / NANOS_PER_MILLISECOND
        recoveryShifts.addLast(RecoveryShift(fromPtsUs, shiftNs))
        recovery.onConsumed(nowNs / NANOS_PER_MILLISECOND, ms, extraDelayNs / NANOS_PER_MILLISECOND)
        return true
    }

    /** Fast recovery is armed but the audio on offer was speech: logged once per episode. */
    @Synchronized
    fun noteFastRecoverySpeech(nowNs: Long = System.nanoTime()) {
        fastRecovery?.onSpeechSkip(nowNs / NANOS_PER_MILLISECOND, extraDelayNs / NANOS_PER_MILLISECOND)
    }

    private fun shrinkWhenCalm(nowNs: Long) {
        val floorNs = maxOf(reservedExtraNs, timeFloorNs)
        if (extraDelayNs <= floorNs || lastLateNs == Long.MIN_VALUE || nowNs - lastLateNs < calmBeforeShrinkNs) return
        val elapsedNs = nowNs - maxOf(lastShrinkNs, lastLateNs + calmBeforeShrinkNs)
        if (elapsedNs <= 0L) return
        val shrinkNs = (elapsedNs / SHRINK_DIVISOR).coerceAtMost(extraDelayNs - floorNs)
        if (shrinkNs <= 0L) return
        extraDelayNs -= shrinkNs
        anchorRenderNs -= shrinkNs
        lastShrinkNs = nowNs
    }

    /**
     * Read-only lookup of [presentationTimeUs]'s target on the current timeline, for post-arrival
     * stages (decoder feed, output release, audio write). Only [timing] at network arrival may move
     * the anchor or grow the delay: a frame fed late because the decoder stalled is not late media.
     * The S24 AV1 decoder's 163-179 ms stall every 61 frames otherwise grew the delay 250 -> 2000 ms
     * with flat arrivals (lane-a 2026-10-08). Before any arrival it returns now plus the delay.
     */
    @Synchronized
    fun targetFor(
        presentationTimeUs: Long,
        nowNs: Long = System.nanoTime(),
    ): MediaPlayoutTiming {
        val anchorUs = anchorPresentationUs
        val rawTargetNs = if (anchorUs == null) {
            nowNs + delayMs * NANOS_PER_MILLISECOND + extraDelayNs
        } else {
            mappedTargetNs(presentationTimeUs, anchorUs)
        }
        return MediaPlayoutTiming(
            targetTimeNs = rawTargetNs.coerceAtMost(nowNs + maxScheduleAheadMs * NANOS_PER_MILLISECOND),
            latenessMs = max(0L, nowNs - rawTargetNs) / NANOS_PER_MILLISECOND,
        )
    }

    /** Non-mutating render target (never in the past); see [targetFor]. */
    fun renderTimeNs(presentationTimeUs: Long, nowNs: Long = System.nanoTime()): Long =
        targetFor(presentationTimeUs, nowNs).targetTimeNs.coerceAtLeast(nowNs)

    fun isTooLate(
        presentationTimeUs: Long,
        maximumLateMs: Long,
        nowNs: Long = System.nanoTime(),
    ): Boolean = targetFor(presentationTimeUs, nowNs).latenessMs > maximumLateMs

    companion object {
        const val STREAM_CALM_BEFORE_SHRINK_MS = 60_000L
        const val CALL_CALM_BEFORE_SHRINK_MS = 10_000L
        private const val MAX_SCHEDULE_AHEAD_MS = 2_000L
        private const val RESET_AFTER_GAP_US = 5_000_000L
        private const val EARLY_REANCHOR_SLACK_MS = 150L
        private const val LATE_TOLERANCE_NS = 20_000_000L
        private const val RESERVE_RANGE_FRACTION = 0.75
        private const val GROWTH_MARGIN_NS = 50_000_000L
        /** Experimental one-step growth applies from this lateness (shorter lateness keeps the margin rule). */
        const val BURST_LATENESS_MS = 150L
        /** Late media this recent keeps a pinned delay saturated (reports go out about every 1 s). */
        private const val SATURATED_LATE_WINDOW_NS = 2_000_000_000L
        // 1 ms of delay per 100 ms of calm playback: imperceptible frame-time compression.
        private const val SHRINK_DIVISOR = 100L
        /** Fast-recovery shifts kept unfolded; more fold the oldest (late media then plays at once, as it would). */
        private const val MAX_RECOVERY_SHIFTS = 32
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
