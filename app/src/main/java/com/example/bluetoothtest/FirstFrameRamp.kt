package com.example.bluetoothtest

import java.util.Locale

/**
 * Experimental `test_call_first_frame_ramp` ([DebugTunables.callFirstFrameRamp], calls only, default off).
 *
 * `test_call_first_frame_now` shows the first decoded call frame at once, but every later frame keeps its playout
 * target, so the picture then freezes for the whole playout lead (about 0.4-0.5 s on CoC calls): phone A/B Q16 cut
 * decoder-configured -> first surface from 0.6-0.8 s to under 0.1 s with about one early freeze per call.
 *
 * This arm also shows the first frame at once, then plays the following frames slowed down by a bounded factor, so
 * the playout delay grows smoothly from about 0 to the normal target instead of jumping there:
 * render(pts) = start + (pts - firstPts) x factor, never later than the clock's own target. The factor is chosen from
 * the first frame's lead so the ramp meets the target after about [rampMs] (at most [maxSlowdown], i.e. never slower
 * than 1 / [maxSlowdown] speed; a longer lead then takes longer). From the frame that reaches its target on, the shared
 * timeline applies unchanged. No frame is dropped: frames are only held for less time than without the ramp.
 *
 * Thread-safe; JVM-testable (times are passed in).
 */
internal class FirstFrameRamp(
    private val log: (String) -> Unit,
    private val rampMs: Long = RAMP_MS,
    private val maxSlowdown: Double = MAX_SLOWDOWN,
    private val maxRampMs: Long = MAX_RAMP_MS,
) {
    enum class State { WAITING, RAMPING, DONE }

    @Volatile var state: State = State.WAITING
        private set

    /** The slowdown factor in use (1.0 before the ramp starts or when it was skipped). */
    @Volatile var factor: Double = 1.0
        private set

    private var startNs = 0L
    private var firstPtsUs = 0L
    private var leadMs = 0L
    private var frames = 0

    /**
     * The render time of a decoded frame. [clockTargetNs] is the shared playout clock's target for it (already not before
     * [nowNs]). The first call starts the ramp and returns [nowNs].
     */
    @Synchronized
    fun renderTimeNs(presentationTimeUs: Long, clockTargetNs: Long, nowNs: Long): Long = when (state) {
        State.WAITING -> start(presentationTimeUs, clockTargetNs, nowNs)
        State.RAMPING -> {
            frames++
            val rampNs = rampTargetNs(presentationTimeUs)
            val elapsedMs = (nowNs - startNs) / NANOS_PER_MS
            when {
                rampNs >= clockTargetNs -> finish("caught_up", nowNs, clockTargetNs)
                elapsedMs >= maxRampMs -> finish("timeout", nowNs, clockTargetNs)
                else -> rampNs.coerceAtLeast(nowNs)
            }
        }
        State.DONE -> clockTargetNs
    }

    /**
     * The decoder-feed target of a frame: null while the ramp has not started (feed now, as the first frame is fed),
     * the earlier of the ramp and the clock while it runs, the clock afterwards.
     */
    @Synchronized
    fun feedTargetNs(presentationTimeUs: Long, clockTargetNs: Long): Long? = when (state) {
        State.WAITING -> null
        State.RAMPING -> minOf(clockTargetNs, rampTargetNs(presentationTimeUs))
        State.DONE -> clockTargetNs
    }

    private fun rampTargetNs(presentationTimeUs: Long): Long =
        startNs + ((presentationTimeUs - firstPtsUs) * NANOS_PER_US * factor).toLong()

    private fun start(presentationTimeUs: Long, clockTargetNs: Long, nowNs: Long): Long {
        leadMs = (clockTargetNs - nowNs) / NANOS_PER_MS
        if (leadMs < MIN_LEAD_MS) {
            // The first frame is already at (or past) its target: nothing to ramp, the clock applies.
            state = State.DONE
            log("BENCHMARK call_first_frame_ramp: phase=skipped | leadMs=$leadMs | reason=no_lead")
            return clockTargetNs
        }
        startNs = nowNs
        firstPtsUs = presentationTimeUs
        factor = slowdownFor(leadMs, rampMs, maxSlowdown)
        state = State.RAMPING
        log("BENCHMARK call_first_frame_ramp: phase=start | leadMs=$leadMs | factor=${String.format(Locale.US, "%.3f", factor)} | rampMs=$rampMs")
        return nowNs
    }

    private fun finish(reason: String, nowNs: Long, clockTargetNs: Long): Long {
        state = State.DONE
        log(
            "BENCHMARK call_first_frame_ramp: phase=end | reason=$reason | elapsedMs=${(nowNs - startNs) / NANOS_PER_MS} | " +
                "handoverRenderMs=${(clockTargetNs - startNs) / NANOS_PER_MS} | " +
                "frames=$frames | startLeadMs=$leadMs | factor=${String.format(Locale.US, "%.3f", factor)}",
        )
        return clockTargetNs
    }

    companion object {
        /** Ramp length for an ordinary lead (the brief: about 2 s). */
        const val RAMP_MS = 2_000L

        /** Slowest playback during the ramp: 1 / 1.5 = 67% speed. */
        const val MAX_SLOWDOWN = 1.5

        /** After this long the clock takes over even if the ramp has not met it (a delay that kept growing). */
        const val MAX_RAMP_MS = 4_000L

        /** A lead below one frame interval at 60 FPS is not worth a ramp. */
        const val MIN_LEAD_MS = 16L

        private const val NANOS_PER_MS = 1_000_000L
        private const val NANOS_PER_US = 1_000L

        /**
         * The factor that makes a [leadMs] lead disappear after [rampMs] of wall time: the ramp gains (factor - 1) ms per
         * media ms, so media span d = lead / (factor - 1) takes d x factor = rampMs; capped at [maxSlowdown].
         */
        fun slowdownFor(leadMs: Long, rampMs: Long = RAMP_MS, maxSlowdown: Double = MAX_SLOWDOWN): Double =
            if (leadMs >= rampMs) maxSlowdown else minOf(maxSlowdown, rampMs.toDouble() / (rampMs - leadMs))
    }
}
