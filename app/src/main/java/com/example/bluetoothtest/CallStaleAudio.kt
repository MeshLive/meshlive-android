package com.example.bluetoothtest

/**
 * Experimental `test_call_stale_audio_drop` (design decision 2026-11-06, DECISIONS "Owner answers on open questions", Audio
 * after a call stall, Q-AUDIO-STALL; Android first): after the peer's call audio stopped arriving for [stallMs] or more (a
 * link stall), audio that is still late after the call's playout clock grew to its maximum (older than the playout budget)
 * is dropped instead of played, so the listener does not hear seconds of old speech before catching up. Normal jitter (any
 * gap under [stallMs]) still plays every packet, as before (user direction 2026-10-07).
 *
 * Bounded: one stall never drops more than its own gap plus the budget of sender time (a link that stays slow after the
 * stall is played late, not silenced), and the catch-up ends at the first packet that is on time. Off ([enabled] false) the
 * same detection runs in observe mode: nothing is dropped and the line says what would have been, so an A/B compares the
 * same events. One line per stall: `BENCHMARK call_audio_stale: mode=drop|observe | gapMs=... | budgetMs=... | stale=... |
 * staleSpanMs=... | maxLateMs=... | end=on_time|cap|next_stall|closed | firstPlayedLateMs=...`.
 * Single-threaded: the call receiver calls it under its own lock, one call per received audio packet.
 */
internal class CallStaleAudio(
    private val enabled: Boolean,
    /** The playout budget: the receive profile's adaptive maximum delay (600 ms on a normal call). */
    private val budgetMs: Long,
    private val stallMs: Long = STALL_MS,
    private val log: (String) -> Unit = {},
) {
    enum class Decision { PLAY, DROP }

    private var lastArrivalMs = Long.MIN_VALUE
    private var catchingUp = false
    private var gapMs = 0L
    private var firstPtsUs = 0L
    private var firstStalePtsUs = 0L
    private var lastStalePtsUs = 0L
    private var stale = 0
    private var maxLateMs = 0L

    /** Packets found older than the budget after a stall, over the whole call (dropped when [enabled]). */
    var totalStale = 0L
        private set

    /**
     * One received audio packet: [arrivalMs] its arrival on a monotonic clock, [presentationTimeUs] its sender time,
     * [latenessMs] how late it is on the playout clock after that clock's own growth at this arrival.
     */
    fun onAudio(arrivalMs: Long, presentationTimeUs: Long, latenessMs: Long): Decision {
        val gap = if (lastArrivalMs == Long.MIN_VALUE) 0L else arrivalMs - lastArrivalMs
        lastArrivalMs = arrivalMs
        if (gap >= stallMs) {
            if (catchingUp) finish("next_stall", -1L)
            catchingUp = true
            gapMs = gap
            firstPtsUs = presentationTimeUs
            stale = 0
            maxLateMs = 0L
        }
        if (!catchingUp) return Decision.PLAY
        val late = latenessMs > LATE_SLACK_MS
        val withinCap = presentationTimeUs - firstPtsUs < (gapMs + budgetMs) * 1_000L
        if (late && withinCap) {
            if (stale == 0) firstStalePtsUs = presentationTimeUs
            stale++
            totalStale++
            lastStalePtsUs = presentationTimeUs
            maxLateMs = maxOf(maxLateMs, latenessMs)
            return if (enabled) Decision.DROP else Decision.PLAY
        }
        finish(if (late) "cap" else "on_time", latenessMs)
        return Decision.PLAY
    }

    /** The call's receiver closed while catching up after a stall. */
    fun close() {
        if (catchingUp) finish("closed", -1L)
    }

    private fun finish(end: String, firstPlayedLateMs: Long) {
        catchingUp = false
        val spanMs = if (stale == 0) 0L else (lastStalePtsUs - firstStalePtsUs) / 1_000L
        log(
            "BENCHMARK call_audio_stale: mode=${if (enabled) "drop" else "observe"} | gapMs=$gapMs | budgetMs=$budgetMs | " +
                "stale=$stale | staleSpanMs=$spanMs | maxLateMs=$maxLateMs | end=$end | firstPlayedLateMs=$firstPlayedLateMs",
        )
    }

    companion object {
        /** An arrival gap of call audio this long is a stall ("about 2 s", design decision 2026-11-06). */
        const val STALL_MS = 2_000L

        /** Lateness the clock's own tolerance and one 20 ms unit absorb: not counted as stale. */
        const val LATE_SLACK_MS = 40L
    }
}
