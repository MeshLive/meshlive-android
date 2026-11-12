package com.example.bluetoothtest

/**
 * Experimental `test_call_video_restart` (audit MEDIA-06, default off: [DebugTunables.callVideoRestart]): a call's video
 * decoder that stops taking frames must not end the voice.
 *
 * Before, a decoder that stopped completing work (S22 c2.android.av1-dav1d after a Wi-Fi Direct cutover) filled its
 * queue, the call's single receive path then waited 5 s for room (audio froze with it, under the receive and record
 * locks) and the queue's timeout ended the call as a local media failure. With this arm on:
 * - the wait for queue room is the playout budget ([QUEUE_WAIT_MS], 1.5 s) instead of 5 s, and a decoder watchdog stall
 *   releases a waiting receiver at once ([EncodedFrameQueue.reportStall]);
 * - the stall ([DecoderStalledException]) takes the decoder restart the call already uses for a recoverable
 *   CodecException: drop the decoder, keep the newest codec configuration, wait for a keyframe and ask the peer for
 *   one (hop-local). The stalled codec is released off the receive thread (bounded release). Audio, the playout clock
 *   and the carriers are untouched;
 * - escalation: the first stall rebuilds the same decoder, the next excludes that component for the call and tries the
 *   next decoder that supports the stream, and the [MAX_STALLS]th stall without [DecoderWatchdogLimits.HEALTHY_FRAMES]
 *   rendered frames in between stops video on this phone; voice continues.
 * Off: [handles] is false and the stall ends the call exactly as before.
 *
 * Single-threaded: the call receiver calls it under its own lock.
 */
internal class CallVideoStallRestart(
    val enabled: Boolean,
    private val onStatus: (String) -> Unit,
    private val onKeyFrameNeeded: () -> Unit,
    private val onRemoteVideoActive: (Boolean) -> Unit,
    private val log: (String) -> Unit = {},
    private val maxStalls: Int = MAX_STALLS,
    private val healthyFrames: Int = DecoderWatchdogLimits.HEALTHY_FRAMES,
) {
    enum class Action(val label: String) {
        REBUILD_SAME("rebuild_same"),
        EXCLUDE_AND_TRY_NEXT("exclude_try_next"),
        STOP_VIDEO("stop_video"),
    }

    private val excluded = LinkedHashSet<String>()
    private var stalls = 0
    private var restarts = 0

    /** Components a stall excluded for the rest of the call (the next decoder skips them unless nothing else is left). */
    val excludedDecoders: Set<String> get() = excluded.toSet()

    /** Video gave up on this phone: later video is ignored and the peer's camera choice no longer shows it. */
    var videoStopped = false
        private set

    /** How long a full decoder queue waits for room: the playout budget when on, null (the queue's 5 s) when off. */
    val queueWaitNs: Long? get() = if (enabled) QUEUE_WAIT_MS * 1_000_000L else null

    /** True when this arm replaces the decoder for [error]; false (the call ends as before) when off. */
    fun handles(error: Throwable): Boolean = enabled && error is DecoderStalledException

    /**
     * The decoder [decoderName], which rendered [renderedFrames] frames since it started, stalled with [stall]; the
     * caller has dropped it. Closes it through [closeDecoder] (which must not wait for the codec), tells the user, and
     * returns true while video waits for a fresh keyframe, false when video stopped.
     */
    fun onStall(stall: DecoderStalledException, decoderName: String, renderedFrames: Int, closeDecoder: () -> Unit): Boolean {
        check(handles(stall)) { "call video restart is off" }
        if (videoStopped) {
            runCatching(closeDecoder)
            return false
        }
        if (renderedFrames >= healthyFrames) stalls = 0
        stalls++
        val action = when {
            stalls >= maxStalls -> Action.STOP_VIDEO
            stalls == 1 -> Action.REBUILD_SAME
            else -> Action.EXCLUDE_AND_TRY_NEXT
        }
        if (action == Action.EXCLUDE_AND_TRY_NEXT) excluded += decoderName
        if (action != Action.STOP_VIDEO) restarts++
        runCatching(closeDecoder)
        log(
            "call_video_restart: stall=$stalls | decoder=$decoderName | rendered=$renderedFrames | " +
                "action=${action.label} | restarts=$restarts | excluded=${excluded.joinToString(",").ifEmpty { "none" }} | " +
                "queueWaitMs=$QUEUE_WAIT_MS | cause=${stall.message}",
        )
        if (action == Action.STOP_VIDEO) {
            videoStopped = true
            onRemoteVideoActive(false)
            onStatus(VIDEO_STOPPED_STATUS)
            return false
        }
        onStatus(RECOVERING_STATUS)
        onKeyFrameNeeded()
        return true
    }

    companion object {
        const val MAX_STALLS = 3

        /** The playout budget a call keeps (TimeBasedSizing's latency budget): the longest the voice may wait for video. */
        const val QUEUE_WAIT_MS = TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS

        /** The status the existing CodecException restart shows. */
        const val RECOVERING_STATUS = "Recovering remote video; audio and call remain connected"
        const val VIDEO_STOPPED_STATUS = "Video stopped on this phone; voice continues"
    }
}

/**
 * Experimental `test_call_video_restart`: the decoder order after a stall excluded components. [initial] is the usual
 * order (one decoder when decoder fallback is off), [wider] every decoder that may serve the same request; excluded
 * components are skipped unless nothing else is left, and at most [CodecPolicy.MAX_DECODER_INIT_ATTEMPTS] are tried.
 */
internal fun stallExcludedCandidates(initial: List<String>, wider: List<String>, excluded: Set<String>): List<String> {
    val all = ArrayList<String>()
    for (name in initial + wider) if (all.none { it.equals(name, ignoreCase = true) }) all += name
    val usable = all.filterNot { name -> excluded.any { it.equals(name, ignoreCase = true) } }
    return if (usable.isEmpty()) initial else usable.take(CodecPolicy.MAX_DECODER_INIT_ATTEMPTS)
}
