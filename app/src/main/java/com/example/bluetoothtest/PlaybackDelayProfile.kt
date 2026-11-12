package com.example.bluetoothtest

import kotlin.math.ceil

/**
 * Receiver latency budgets. Since 2026-10-07 nothing is dropped to stay inside a budget (user
 * direction: latency instead of frame loss), so every streaming option is an adaptive range: it
 * starts at [delayMs] and grows toward [adaptiveMaxDelayMs] only while this link delivers late,
 * then shrinks back after calm (audio follows by a small pitch-preserving speed change). Queues are
 * sized to hold the whole maximum; late media is still played. Private calls keep their own,
 * shorter budgets until two-way latency is decided separately.
 */
enum class PlaybackDelayProfile(
    val label: String,
    val stabilityLabel: String,
    val description: String,
    val delayMs: Long,
    val audioBufferMs: Int,
    /** The delay may grow to this when media arrives late (equal to [delayMs] = fixed). */
    val adaptiveMaxDelayMs: Long = delayMs,
) {
    LOW(
        "Low latency",
        "Low",
        "Starts at 100 ms and may grow to 600 ms during Bluetooth bursts: least delay.",
        delayMs = 100,
        audioBufferMs = 100,
        adaptiveMaxDelayMs = 600,
    ),
    BALANCED(
        "Balanced",
        "Medium",
        "Starts at 250 ms and may grow to 1.2 s during Bluetooth bursts.",
        delayMs = 250,
        audioBufferMs = 250,
        adaptiveMaxDelayMs = 1_200,
    ),
    STABLE(
        "High stability",
        "High",
        "Starts at 500 ms and may grow to 2.5 s: smoothest motion through long Bluetooth stalls.",
        delayMs = 500,
        audioBufferMs = 500,
        adaptiveMaxDelayMs = 2_500,
    ),
    AUTO(
        "Auto",
        "Adaptive",
        "Starts at 250 ms and grows up to 2 s only while this Bluetooth link delivers late; never drops frames.",
        delayMs = 250,
        audioBufferMs = 250,
        adaptiveMaxDelayMs = 2_000,
    ),

    /**
     * Two-way calls adapt like streams but within a conversational bound (design decision
     * 2026-10-08: fit the link, avoid talking over each other): the delay absorbs bursts up to its
     * maximum, and a pinned maximum makes the sender lower its bitrate and then its frame rate. The
     * bounds keep one-way playout near ITU-T G.114's 400 ms "acceptable" range on top of encode/decode.
     */
    CALL_LOW(
        "Call (low latency)",
        "Call",
        "Starts at 30 ms and grows up to 200 ms during Bluetooth bursts.",
        delayMs = 30,
        audioBufferMs = 40,
        adaptiveMaxDelayMs = 200,
    ),
    CALL_BALANCED(
        "Call (balanced)",
        "Call",
        "Starts at 250 ms and grows up to 600 ms during Bluetooth bursts.",
        delayMs = 250,
        audioBufferMs = 250,
        adaptiveMaxDelayMs = 600,
    ),
    CALL(
        "Call",
        "Call",
        "Starts at 250 ms and grows up to 600 ms during Bluetooth bursts.",
        delayMs = 250,
        audioBufferMs = 250,
        adaptiveMaxDelayMs = 600,
    ),

    /**
     * Experimental (DEBUG `test_voice_call_low_delay`): voice-only calls carry no video bursts, so
     * audio alone sets the jitter. Voice-only senders queued audio for at most 27 ms (voice-ptt-*
     * runs, `audioQueueAgeMaxMs`) and receivers underran at most once at 250 ms, so the start delay
     * drops to 80 ms and grows on late audio like any call delay.
     */
    CALL_VOICE(
        "Call (voice, experimental low delay)",
        "Call",
        "Starts at 80 ms and grows up to 400 ms when voice audio arrives late.",
        delayMs = 80,
        audioBufferMs = 80,
        adaptiveMaxDelayMs = 400,
    ),

    /**
     * Audio-only live (Design AL "radio"): one-way, no picture to keep in sync, so a higher start delay than
     * streams (600 ms) that adapts up to 3 s while this link delivers late and shrinks after 60 s without late
     * media (the stream calm period). Catch-up uses the existing pitch-preserving speed change (up to 1.06x).
     */
    RADIO(
        "Radio",
        "Radio",
        "Starts at 600 ms and grows up to 3 s while this Bluetooth link delivers late.",
        delayMs = 600,
        audioBufferMs = 600,
        adaptiveMaxDelayMs = 3_000,
    ),

    /** Calls on legacy-constrained links start with more buffer. */
    CALL_STABLE(
        "Call (constrained link)",
        "Call",
        "Starts at 500 ms and grows up to 800 ms for constrained call links.",
        delayMs = 500,
        audioBufferMs = 500,
        adaptiveMaxDelayMs = 800,
    ),
    ;

    val adaptive: Boolean
        get() = adaptiveMaxDelayMs > delayMs

    /**
     * [fastRecovery] (`test_call_fast_recovery`, promoted 2026-11-12, [CallFastRecovery.forProfile]) is attached only to an
     * adaptive call clock; fixed and stream clocks ignore it.
     */
    fun newPlayoutClock(
        delayOverrideMs: Long? = null,
        fastRecovery: CallFastRecovery? = null,
    ): MediaPlayoutClock = if (delayOverrideMs != null && delayOverrideMs != delayMs) {
        MediaPlayoutClock(
            delayOverrideMs,
            maxScheduleAheadMs = scheduleAheadMs(delayOverrideMs),
            burstGrowthFactor = burstGrowthFactor(),
        )
    } else {
        MediaPlayoutClock(
            delayMs,
            maxScheduleAheadMs = scheduleAheadMs(adaptiveMaxDelayMs),
            adaptiveMaxDelayMs = adaptiveMaxDelayMs,
            calmBeforeShrinkMs = if (stabilityLabel == "Call") {
                MediaPlayoutClock.CALL_CALM_BEFORE_SHRINK_MS
            } else {
                MediaPlayoutClock.STREAM_CALM_BEFORE_SHRINK_MS
            },
            // Experimental time-based target: streams only; calls keep their conversational bounds.
            timeTarget = if (stabilityLabel == "Call") null else PlayoutJitterTarget.fromTunables(),
            burstGrowthFactor = burstGrowthFactor(),
            fastRecovery = fastRecovery?.takeIf { stabilityLabel == "Call" && adaptive },
        )
    }

    /**
     * Experimental one-step burst growth (`test_playout_burst_growth`): streams only, like the time target. Calls keep the
     * lateness + 50 ms staircase (switch audit 2026-10-27, A9: every clock read the tunable, so the arm also changed calls).
     */
    private fun burstGrowthFactor(): Double? = if (stabilityLabel == "Call") null else DebugTunables.playoutBurstGrowth

    /** Frames held for the whole maximum delay plus the decoder's own pipeline. */
    fun videoQueueCapacity(fps: Int): Int = (ceil(
        fps * (adaptiveMaxDelayMs + VIDEO_QUEUE_RECOVERY_HEADROOM_MS) / 1_000.0,
    )
        .toInt() + VIDEO_QUEUE_HEADROOM_FRAMES)
        .coerceIn(MIN_VIDEO_QUEUE_FRAMES, MAX_VIDEO_QUEUE_FRAMES)

    fun audioQueueCapacity(): Int = ceil(
        AUDIO_PACKETS_PER_SECOND * (adaptiveMaxDelayMs + VIDEO_QUEUE_RECOVERY_HEADROOM_MS) / 1_000.0,
    )
        .toInt()
        .coerceIn(MIN_AUDIO_QUEUE_PACKETS, MAX_AUDIO_QUEUE_PACKETS)

    val audioPrebufferMs: Int
        get() = minOf(delayMs.toInt(), MAX_AUDIO_PREBUFFER_MS).coerceAtLeast(MIN_AUDIO_PREBUFFER_MS)

    /** Fixed profiles sit on the manual slider; Auto is shown at the Balanced position. */
    val sliderValue: Float
        get() = MANUAL.indexOf(this).takeIf { it >= 0 }?.toFloat() ?: MANUAL.indexOf(BALANCED).toFloat()

    /** The profile a private call uses when this one is selected (its pre-2026-10-07 budget). */
    fun forCall(constrainedLink: Boolean): PlaybackDelayProfile = when {
        constrainedLink -> CALL_STABLE
        this == LOW || this == CALL_LOW -> CALL_LOW
        this == BALANCED || this == CALL_BALANCED -> CALL_BALANCED
        this == STABLE || this == CALL_STABLE -> CALL_STABLE
        else -> CALL
    }

    companion object {
        val MANUAL = listOf(LOW, BALANCED, STABLE)

        fun fromStoredValue(value: String?): PlaybackDelayProfile = MANUAL.plus(AUTO).firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: AUTO

        fun fromSliderValue(value: Float): PlaybackDelayProfile =
            MANUAL[value.toInt().coerceIn(MANUAL.indices)]

        private fun scheduleAheadMs(maxDelayMs: Long): Long = maxOf(2_000L, maxDelayMs + 1_000L)

        private const val AUDIO_PACKETS_PER_SECOND = 50
        private const val MIN_VIDEO_QUEUE_FRAMES = 2
        internal const val VIDEO_QUEUE_HEADROOM_FRAMES = 3
        private const val VIDEO_QUEUE_RECOVERY_HEADROOM_MS = 500
        private const val MAX_VIDEO_QUEUE_FRAMES = 240
        private const val MIN_AUDIO_QUEUE_PACKETS = 13
        private const val MAX_AUDIO_QUEUE_PACKETS = 200
        private const val MIN_AUDIO_PREBUFFER_MS = 20
        private const val MAX_AUDIO_PREBUFFER_MS = 500
    }
}
