package com.example.bluetoothtest

import kotlin.math.roundToInt

/**
 * Experimental call fast recovery (DEBUG `test_call_fast_recovery`, runner tunable `call_fast_recovery`, default off):
 * after one ISOLATED late burst, the call's added playout delay returns to its level before the burst within seconds,
 * by leaving out quiet decoded audio inside pauses instead of waiting for the calm shrink.
 *
 * Measured problem (b12sm, user-launch direct GATT call S22 <- ASUS): the ASUS encoder paused for 526 ms, the S22's
 * shared call clock grew by +303 ms (`addedDelayMs`) and receive->surface latency stayed at p50 ~447 ms (from ~150 ms)
 * until the call ended 12 s later. [MediaPlayoutClock] grows by lateness + 50 ms; speech-led playout limits only late
 * video, so late audio after a gap grows the shared clock; calls shrink only after [MediaPlayoutClock.CALL_CALM_BEFORE_SHRINK_MS]
 * of calm and then at 1 ms per 100 ms, so 300 ms takes ~40 s: one short glitch leaves the conversation ~0.3 s late.
 *
 * Investigation (B13 source, 2026-11-11): how audio and video consume the clock.
 * - Both read one clock (`audioPlayoutClock ?: playoutClock` in PrivateCallMedia; audio has its own only with the
 *   Experimental `test_call_audio_own_clock`). Only network arrival ([MediaPlayoutClock.timing]) grows it; the decoder
 *   feed, the video output release and the audio writer use the read-only [MediaPlayoutClock.targetFor].
 * - Video: encoded frames wait in the decoder input queue until their target minus the decode lead; decoded frames are
 *   released with `releaseOutputBuffer(index, renderTimeNs)` at their target, never earlier than now. When the anchor
 *   moves earlier, queued frames are fed and released sooner and a frame whose new target has passed is released at
 *   once. The app drops nothing and every key frame is still decoded; the display can only skip a frame that lands on
 *   the same refresh as its successor.
 * - Audio: decoded 20 ms PCM chunks (50 Opus packets/s) wait in the decoder's PCM queue and then in the AudioTrack
 *   (blocking writes, 250 ms track buffer on CALL). Nothing already queued is re-timed. `followPlayoutClock` compares
 *   now + queued against the chunk's target, and [AudioPlayoutSync] changes the pitch-preserving playback speed
 *   (0.96-1.06x, 40 ms dead band). So the anchor moving earlier is followed by speeding up: today's 1 %/s calm shrink
 *   is ~1 % faster speech; a fast anchor move alone would play 300 ms of excess at 1.06x for ~5 s, which is audible
 *   tempo change across speech. The Experimental silence-gated catch-up (concealment arm) gates the speed-up to quiet
 *   chunks but still time-stretches, and not above 400 ms of excess.
 * - Safe audio catch-up point: yes, the playback writer in OpusAudioDecoder, between the PCM queue and the AudioTrack
 *   write, is the one place where audio's physical delay can drop without a speed change. Call Opus sends ordinary frames
 *   in pauses (DTX is the Experimental `test_call_native_opus_dtx`, off; there are no comfort-noise frames on the wire),
 *   so the usable signal is decoded energy: the same -35 dBFS RMS speech threshold as [SilenceGatedCatchUp]. Whole quiet
 *   chunks inside a pause can be left out with a short crossfade (no click, no stretch, speech untouched). Shortening
 *   the pauses between talk spurts is the classic way adaptive voice playout changes its delay; this is implemented
 *   here from first principles.
 *
 * Design (two pure parts; the clock and the audio writer only call them):
 * - [CallFastRecovery] (in the clock) classifies lateness. A burst starts at the first counted late arrival (one that
 *   the clock counts for its calm timer; speech-led late video above its limit does not) and records the added delay
 *   before it. Late arrivals within [BURST_WINDOW_MS] of its start belong to it (the measured 526 ms pause plus its
 *   backlog fits). [ARM_CALM_MS] without counted late media after the burst arms recovery: two peer report periods
 *   (reports go about every 1 s), so a second burst within 2 s is not mistaken for calm. A counted late arrival after
 *   the burst window but before arming, or while armed within [REPEAT_WINDOW_MS] of the burst, is sustained lateness:
 *   recovery stops and today's behaviour (10 s calm, slow shrink) holds until [SUSTAINED_CLEAR_MS] of calm.
 * - [QuietAudioSkipper] (in the audio writer) leaves out one quiet chunk only while recovery is armed, after at least
 *   [QuietAudioSkipper.HANGOVER_MS] of continuous quiet (longer than the silent closure of a stop consonant, so a
 *   quiet gap inside a word is never shortened), only when the next chunk is quiet too (a speech onset is never
 *   touched), at most once per [QuietAudioSkipper.MIN_PLAYED_BETWEEN_DROPS_MS] of played audio (at most 25 % of a pause
 *   is removed; video locally runs at most 1.33x for the length of the pause) and only while the AudioTrack holds
 *   [QuietAudioSkipper.MIN_QUEUED_MS] so the look-ahead wait cannot underrun it. 300 ms of excess needs about 1.2 s of
 *   pause. Continuous speech: nothing is dropped and the slow shrink still applies, as today.
 * - Each dropped chunk lowers the clock by exactly its duration from the next chunk's PTS on
 *   ([MediaPlayoutClock.consumeFastRecovery]): video frames at or after that PTS show that much earlier, the same
 *   point where audio skips, so lip sync holds and no frame is moved twice. Streams and encoded media are unchanged.
 *
 * Log (DEBUG): `BENCHMARK call_fast_recovery: event=armed|consumed|done|skipped_speech|sustained | addedDelayMs= |
 * droppedAudioMs= | sinceGapMs= | targetAddedMs=`. Single clock lock: every call comes from [MediaPlayoutClock] under
 * its monitor.
 */
class CallFastRecovery(
    private val log: (String) -> Unit = {},
) {
    private enum class State { IDLE, BURST, ARMED, SUSTAINED }

    private var state = State.IDLE
    private var burstStartMs = 0L
    private var lastLateMs = 0L
    private var targetAddedMs = 0L
    private var droppedMs = 0L
    private var speechLogged = false

    /** Episodes armed, for tests and summaries. */
    var armedEpisodes = 0
        private set

    /** A counted late arrival at [nowMs]; [addedBeforeMs] is the added delay before this arrival grew it. */
    fun onLate(nowMs: Long, addedBeforeMs: Long, addedAfterMs: Long) {
        when (state) {
            State.IDLE -> startBurst(nowMs, addedBeforeMs)
            State.BURST -> when {
                nowMs - burstStartMs <= BURST_WINDOW_MS -> lastLateMs = nowMs
                // Not asked meanwhile (no audio written): a burst this long after the last one starts afresh.
                nowMs - lastLateMs >= REPEAT_WINDOW_MS -> startBurst(nowMs, minOf(targetAddedMs, addedBeforeMs))
                else -> becomeSustained(nowMs, addedAfterMs)
            }
            State.ARMED -> if (nowMs - lastLateMs < REPEAT_WINDOW_MS) {
                becomeSustained(nowMs, addedAfterMs)
            } else {
                // A new isolated candidate long after the last one: keep the older, lower target.
                val keep = targetAddedMs
                startBurst(nowMs, minOf(keep, addedBeforeMs))
            }
            State.SUSTAINED -> lastLateMs = nowMs
        }
    }

    /**
     * Delay (ms) the clock may still drop through inaudible audio, given the current [addedMs] and the clock's own
     * calm floor [floorMs]; 0 unless armed. Arms (and ends) episodes lazily from the elapsed calm.
     */
    fun pendingMs(nowMs: Long, addedMs: Long, floorMs: Long): Long {
        when (state) {
            State.IDLE -> return 0L
            State.SUSTAINED -> {
                if (nowMs - lastLateMs >= SUSTAINED_CLEAR_MS) state = State.IDLE
                return 0L
            }
            State.BURST -> {
                if (nowMs - lastLateMs < ARM_CALM_MS) return 0L
                if (addedMs - maxOf(targetAddedMs, floorMs) < MIN_EXCESS_MS) {
                    state = State.IDLE
                    return 0L
                }
                state = State.ARMED
                armedEpisodes++
                droppedMs = 0L
                speechLogged = false
                emit("armed", nowMs, addedMs)
            }
            State.ARMED -> Unit
        }
        val pending = addedMs - maxOf(targetAddedMs, floorMs)
        if (pending < MIN_EXCESS_MS) {
            state = State.IDLE
            emit("done", nowMs, addedMs)
            return 0L
        }
        return pending
    }

    /** The clock dropped [ms] of delay through left-out quiet audio; [addedAfterMs] is the added delay now. */
    fun onConsumed(nowMs: Long, ms: Long, addedAfterMs: Long) {
        droppedMs += ms
        emit("consumed", nowMs, addedAfterMs)
    }

    /** Armed, but the audio writer had only speech (or a speech tail) to offer: logged once per episode. */
    fun onSpeechSkip(nowMs: Long, addedMs: Long) {
        if (state != State.ARMED || speechLogged) return
        speechLogged = true
        emit("skipped_speech", nowMs, addedMs)
    }

    private fun startBurst(nowMs: Long, addedBeforeMs: Long) {
        state = State.BURST
        burstStartMs = nowMs
        lastLateMs = nowMs
        targetAddedMs = addedBeforeMs
    }

    private fun becomeSustained(nowMs: Long, addedMs: Long) {
        state = State.SUSTAINED
        lastLateMs = nowMs
        emit("sustained", nowMs, addedMs)
    }

    private fun emit(event: String, nowMs: Long, addedMs: Long) {
        log(
            "BENCHMARK call_fast_recovery: event=$event | addedDelayMs=$addedMs | droppedAudioMs=$droppedMs | " +
                "sinceGapMs=${nowMs - burstStartMs} | targetAddedMs=$targetAddedMs",
        )
    }

    companion object {
        /** Lateness from one burst: its late arrivals all fall within this of the first. */
        const val BURST_WINDOW_MS = 1_000L
        /** Calm after the burst's last late arrival before recovery arms (two peer report periods). */
        const val ARM_CALM_MS = 2_000L
        /** A counted late arrival this soon after the last burst, while armed, is repeated (sustained) lateness. */
        const val REPEAT_WINDOW_MS = MediaPlayoutClock.CALL_CALM_BEFORE_SHRINK_MS
        /** Sustained lateness keeps today's behaviour until the clock's own call calm period passes. */
        const val SUSTAINED_CLEAR_MS = MediaPlayoutClock.CALL_CALM_BEFORE_SHRINK_MS
        /** One 20 ms call audio chunk: less excess is not worth an episode and sits inside the A/V dead band. */
        const val MIN_EXCESS_MS = 20L

        /** Recovery for a call clock when the switch is on; streams never get one. */
        fun forProfile(profile: PlaybackDelayProfile, enabled: Boolean, log: (String) -> Unit = {}): CallFastRecovery? =
            if (enabled && profile.stabilityLabel == "Call" && profile.adaptive) CallFastRecovery(log) else null
    }
}

/**
 * The audio writer's half of [CallFastRecovery]: which decoded chunk may be left out. Feed every chunk that is played
 * to [onPlayed] and every chunk left out to [onDropped]. Pure: no audio, no clock.
 */
internal class QuietAudioSkipper(
    private val speechRms: Double = SilenceGatedCatchUp.SPEECH_RMS,
    private val hangoverMs: Long = HANGOVER_MS,
    private val minPlayedBetweenDropsMs: Long = MIN_PLAYED_BETWEEN_DROPS_MS,
    private val minQueuedMs: Long = MIN_QUEUED_MS,
) {
    enum class Verdict {
        /** Play it: recovery is not asking, or the output is too shallow to wait for the next chunk. */
        PLAY,
        /** Recovery is asking but this is speech, a speech tail or too soon after the last drop: play it. */
        PLAY_SPEECH,
        /** Quiet inside a pause: leave it out if the next chunk is quiet too ([nextAllowsDrop]). */
        CANDIDATE,
    }

    private var quietRunMs = 0L
    private var playedSinceDropMs = Long.MAX_VALUE / 2

    /** The chunk about to be written: [rms] its level, [chunkMs] its length, [pendingMs] recovery's ask, [queuedMs] the output depth. */
    fun consider(rms: Double, chunkMs: Long, pendingMs: Long, queuedMs: Long): Verdict = when {
        chunkMs <= 0L || pendingMs < chunkMs -> Verdict.PLAY
        rms >= speechRms || quietRunMs < hangoverMs -> Verdict.PLAY_SPEECH
        playedSinceDropMs < minPlayedBetweenDropsMs -> Verdict.PLAY
        queuedMs < minQueuedMs -> Verdict.PLAY
        else -> Verdict.CANDIDATE
    }

    /** A candidate is left out only before another quiet chunk: a speech onset keeps its lead-in. */
    fun nextAllowsDrop(nextRms: Double): Boolean = nextRms < speechRms

    fun onPlayed(rms: Double, chunkMs: Long) {
        quietRunMs = if (rms < speechRms) quietRunMs + chunkMs else 0L
        playedSinceDropMs += chunkMs
    }

    fun onDropped() {
        playedSinceDropMs = 0L
    }

    companion object {
        /** Continuous quiet before any drop: longer than a stop consonant's silent closure inside a word. */
        const val HANGOVER_MS = 120L
        /** Played audio between drops: at most 20 ms of every 80 ms of pause is removed (video locally <= 1.33x). */
        const val MIN_PLAYED_BETWEEN_DROPS_MS = 60L
        /** The AudioTrack holds at least this, so waiting [LOOKAHEAD_WAIT_MS] for the next chunk cannot underrun it. */
        const val MIN_QUEUED_MS = 100L
        const val LOOKAHEAD_WAIT_MS = 40L
        /** Crossfade from the left-out chunk's start into the next chunk: removes the junction step, inaudible length. */
        const val CROSSFADE_MS = 5
    }
}

/**
 * Leaves [dropped] out between the chunk written before it and [next]: the first [fadeFrames] frames fade from [dropped]'s
 * start (which continues the previous chunk exactly) into [next]'s start, so the junction has no step. Returns [next]'s
 * length; interleaved PCM16 with [channels] channels.
 */
internal fun crossfadeOverDroppedChunk(dropped: ShortArray, next: ShortArray, channels: Int, fadeFrames: Int): ShortArray {
    require(channels > 0) { "channels must be positive" }
    val frames = minOf(fadeFrames, dropped.size / channels, next.size / channels)
    val out = next.copyOf()
    for (frame in 0 until frames) {
        val weight = (frame + 0.5) / frames
        for (channel in 0 until channels) {
            val i = frame * channels + channel
            val mixed = dropped[i] * (1.0 - weight) + next[i] * weight
            out[i] = mixed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
    return out
}
