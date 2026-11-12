package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/*
 * Decoder watchdogs (family backlog batch D): silent no-output stalls, input-buffer starvation,
 * bounded MediaCodec release and configure-hang detection. Pure logic with injected clocks so the
 * decisions are unit-tested; VideoStreamDecoder owns the Android side.
 *
 * Decoder timestamps are proxies, not physical latency (AGENTS.md): everything here counts decoder
 * callbacks and logs them; no line claims screen or glass-to-glass timing.
 */

/** Thresholds shared by the decoder watchdogs. */
internal object DecoderWatchdogLimits {
    /** A stall needs at least this many access units queued with no decoder output... */
    const val STALL_MIN_INPUTS = 8

    /** ...and at least this long since the last output (or since the first input after a reset). */
    const val STALL_MIN_MS = 1_500L

    /** Moonlight DecoderHungException: no input buffer offered for 5 s while frames wait. */
    const val INPUT_STARVATION_MS = 5_000L

    /** Media3/WebRTC-style bounded release: MediaCodec.release() occasionally hangs. */
    const val RELEASE_TIMEOUT_MS = 5_000L

    /** configure()+start() normally take 100-200 ms; beyond this the component is suspicious (logged). */
    const val CONFIGURE_SLOW_MS = 800L

    /** configure() still not returned after this long: logged once as a hang (it cannot be interrupted). */
    const val CONFIGURE_HANG_MS = 5_000L

    /** Watchdog tick on the decoder worker. */
    const val CHECK_INTERVAL_MS = 500L

    /** Rendered frames after a recovery that count as healthy and forget earlier stalls. */
    const val HEALTHY_FRAMES = 60

    /** At most this many `decoder_stall` lines per decoder instance. */
    const val MAX_STALL_LOG_LINES = 5

    /** The stall input count for a stream of [fps]: two seconds of frames, never below [STALL_MIN_INPUTS]. */
    fun stallInputs(fps: Int): Int = maxOf(STALL_MIN_INPUTS, 2 * fps.coerceAtLeast(1))
}

internal enum class DecoderStallKind(val label: String) {
    /** The decoder took input but produced no output callback. */
    NO_OUTPUT("no_output"),

    /** The decoder offered no input buffer although frames wait. */
    NO_INPUT_BUFFER("no_input_buffer"),
}

internal data class DecoderStall(val kind: DecoderStallKind, val unanswered: Int, val ageMs: Long)

/**
 * Detects a decoder that initialised fine and then stopped answering (MediaTek Codec2 HEVC/VP9
 * regressions: androidx/media #2711, #2765; Moonlight decoderHungException). The signal is the
 * decoder's own output callback, not the surface: a hidden or paused surface keeps getting output
 * callbacks (buffers are released), and a paused stream queues no input, so neither looks like a stall.
 * Not thread-safe: the decoder worker thread owns it.
 */
internal class DecoderStallDetector(
    fps: Int,
    private val minInputs: Int = DecoderWatchdogLimits.stallInputs(fps),
    private val minMs: Long = DecoderWatchdogLimits.STALL_MIN_MS,
    private val starvationMs: Long = DecoderWatchdogLimits.INPUT_STARVATION_MS,
) {
    private var unanswered = 0
    private var sinceNs = 0L
    private var lastInputBufferNs = 0L

    /** A new codec (or a flushed one) starts a clean window. */
    fun reset(nowNs: Long) {
        unanswered = 0
        sinceNs = nowNs
        lastInputBufferNs = nowNs
    }

    /** One non-config access unit was queued to the decoder. */
    fun onInputQueued(nowNs: Long) {
        if (unanswered == 0) sinceNs = nowNs
        unanswered++
    }

    /** The decoder produced an output callback (rendered or not). */
    fun onOutput(nowNs: Long) {
        unanswered = 0
        sinceNs = nowNs
    }

    /** The decoder offered an input buffer. */
    fun onInputBufferOffered(nowNs: Long) {
        lastInputBufferNs = nowNs
    }

    /**
     * Evaluated on a timer. [suspended]: input is deliberately blocked, a rebuild/recovery is pending or the
     * decoder output is deliberately held for its playout time; nothing fires and the window is re-based.
     * [framesWaiting]: encoded frames wait for a decoder input buffer. [inputBufferHeld]: the app already
     * holds an unused input buffer (it is pacing the feed, not starved).
     */
    fun check(nowNs: Long, suspended: Boolean, framesWaiting: Boolean, inputBufferHeld: Boolean): DecoderStall? {
        if (suspended) {
            sinceNs = nowNs
            lastInputBufferNs = nowNs
            return null
        }
        if (!framesWaiting || inputBufferHeld) lastInputBufferNs = nowNs
        val starvedMs = (nowNs - lastInputBufferNs) / 1_000_000L
        if (framesWaiting && !inputBufferHeld && starvedMs >= starvationMs) {
            return DecoderStall(DecoderStallKind.NO_INPUT_BUFFER, unanswered, starvedMs)
        }
        val ageMs = (nowNs - sinceNs) / 1_000_000L
        if (unanswered >= minInputs && ageMs >= minMs) {
            return DecoderStall(DecoderStallKind.NO_OUTPUT, unanswered, ageMs)
        }
        return null
    }
}

/**
 * What to do about repeated stalls of one decoder instance: log only, rebuild the same decoder once,
 * then exclude the component for the session and rebuild with the next candidate, and give up (log
 * only) after [maxStalls]. Recovery is the `test_decoder_stall_recovery` switch (default on; false is the DEBUG control arm); without it
 * every stall is only logged.
 */
internal class DecoderStallEscalation(private val maxStalls: Int = 3) {
    enum class Action { LOG_ONLY, REBUILD_SAME, EXCLUDE_AND_REBUILD, GIVE_UP }

    private var stalls = 0
    private var framesSinceStall = 0

    val stallCount: Int get() = stalls

    fun next(recoveryEnabled: Boolean, otherCandidateExists: Boolean): Action {
        stalls++
        framesSinceStall = 0
        if (!recoveryEnabled) return Action.LOG_ONLY
        return when {
            stalls > maxStalls -> Action.GIVE_UP
            stalls == 1 -> Action.REBUILD_SAME
            otherCandidateExists -> Action.EXCLUDE_AND_REBUILD
            else -> Action.REBUILD_SAME
        }
    }

    /** A frame was rendered; enough of them after a stall forget it. */
    fun onFrameRendered() {
        if (stalls == 0) return
        framesSinceStall++
        if (framesSinceStall >= DecoderWatchdogLimits.HEALTHY_FRAMES) {
            stalls = 0
            framesSinceStall = 0
        }
    }
}

/** Runs a blocking MediaCodec call on a throw-away thread and waits at most [timeoutMs]. */
internal object BoundedCodecRelease {
    /**
     * True when [action] finished in time. A false return leaves the thread running (a hung codec cannot be
     * interrupted); the caller proceeds without waiting longer. Exceptions of [action] are swallowed.
     */
    fun run(label: String, timeoutMs: Long, action: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        val thread = Thread({
            try {
                action()
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }, "DecoderRelease-$label")
        thread.isDaemon = true
        thread.start()
        return try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/** Logs a configure()/start() that has not returned after [DecoderWatchdogLimits.CONFIGURE_HANG_MS]. */
internal object DecoderConfigureWatch {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "DecoderConfigureWatch").apply { isDaemon = true }
    }

    /** Cancel when the call returns. [onHang] runs on the watch thread if it did not. */
    fun watch(hangMs: Long, onHang: () -> Unit): ScheduledFuture<*> =
        scheduler.schedule(Runnable { runCatching(onHang) }, hangMs, TimeUnit.MILLISECONDS)

    /** `slow` when a finished configure+start took longer than [DecoderWatchdogLimits.CONFIGURE_SLOW_MS]. */
    fun isSlow(elapsedMs: Long): Boolean = elapsedMs > DecoderWatchdogLimits.CONFIGURE_SLOW_MS
}
