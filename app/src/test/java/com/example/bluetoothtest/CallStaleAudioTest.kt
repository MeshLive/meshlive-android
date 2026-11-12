package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental `test_call_stale_audio_drop` (design decision 2026-11-06, Q-AUDIO-STALL): replayed call audio arrivals of 20 ms
 * Opus units. The lateness is what the call's playout clock returns at arrival after its own growth to the maximum (600 ms
 * on a normal call), as PrivateCallMediaReceiver.offerAudio passes it.
 */
class CallStaleAudioTest {
    private val budgetMs = PlaybackDelayProfile.CALL.adaptiveMaxDelayMs

    private class Arrival(val arrivalMs: Long, val ptsUs: Long, val latenessMs: Long)

    /** [count] units every 20 ms from [fromMs], sender time from [firstPtsUs], on time. */
    private fun steady(fromMs: Long, count: Int, firstPtsUs: Long): List<Arrival> =
        (0 until count).map { i -> Arrival(fromMs + i * 20L, firstPtsUs + i * 20_000L, 0L) }

    /**
     * The link stalls [stallMs] after the last unit of [before] while the sender keeps producing one unit every 20 ms; at the
     * recovery the stalled units arrive at once (1 ms apart), each late by its age beyond the budget.
     */
    private fun burstAfterStall(before: List<Arrival>, stallMs: Long): List<Arrival> {
        val last = before.last()
        val recoveredMs = last.arrivalMs + stallMs
        return (0 until (stallMs / 20L).toInt()).map { i ->
            val producedMs = last.arrivalMs + 20L + i * 20L
            val ageMs = recoveredMs + i - producedMs
            Arrival(recoveredMs + i, last.ptsUs + 20_000L + i * 20_000L, (ageMs - budgetMs).coerceAtLeast(0L))
        }
    }

    private fun CallStaleAudio.replay(arrivals: List<Arrival>): List<CallStaleAudio.Decision> =
        arrivals.map { onAudio(it.arrivalMs, it.ptsUs, it.latenessMs) }

    @Test
    fun afterAFiveSecondStallOnlyAudioOlderThanTheBudgetIsDroppedAndTheConversationContinuesOnTime() {
        val stale = CallStaleAudio(enabled = true, budgetMs = budgetMs)
        val before = steady(1_000L, 50, 0L)
        assertTrue(stale.replay(before).all { it == CallStaleAudio.Decision.PLAY })
        val burst = burstAfterStall(before, 5_000L)
        val decisions = stale.replay(burst)
        burst.zip(decisions).forEach { (arrival, decision) ->
            val olderThanTheBudget = arrival.latenessMs > CallStaleAudio.LATE_SLACK_MS
            assertEquals(if (olderThanTheBudget) CallStaleAudio.Decision.DROP else CallStaleAudio.Decision.PLAY, decision)
        }
        // The newest part of the stall (within the budget) is played, and so is everything after it.
        assertEquals(CallStaleAudio.Decision.PLAY, decisions.last())
        val after = steady(burst.last().arrivalMs + 11L, 100, burst.last().ptsUs + 20_000L)
        assertTrue(stale.replay(after).all { it == CallStaleAudio.Decision.PLAY })
    }

    @Test
    fun switchedOffTheSameStallIsObservedButEveryPacketIsPlayed() {
        val on = CallStaleAudio(enabled = true, budgetMs = budgetMs)
        val off = CallStaleAudio(enabled = false, budgetMs = budgetMs)
        val before = steady(1_000L, 50, 0L)
        val timeline = before + burstAfterStall(before, 5_000L)
        val dropped = on.replay(timeline).count { it == CallStaleAudio.Decision.DROP }
        assertTrue(off.replay(timeline).all { it == CallStaleAudio.Decision.PLAY })
        assertTrue(dropped > 0)
        assertEquals(on.totalStale, off.totalStale)
    }

    @Test
    fun normalJitterUnderTwoSecondsPlaysEveryPacketHoweverLate() {
        val stale = CallStaleAudio(enabled = true, budgetMs = budgetMs)
        val before = steady(1_000L, 50, 0L)
        // A 1.5 s gap, then units that are late even after the clock grew: still played (jitter, not a stall).
        val late = (0 until 75).map { i ->
            Arrival(before.last().arrivalMs + 1_500L + i, before.last().ptsUs + 20_000L + i * 20_000L, 900L)
        }
        assertTrue(stale.replay(before + late).all { it == CallStaleAudio.Decision.PLAY })
        assertEquals(0L, stale.totalStale)
    }

    @Test
    fun aPeerThatPausedAndResumesOnTimeLosesNothing() {
        val stale = CallStaleAudio(enabled = true, budgetMs = budgetMs)
        val before = steady(1_000L, 50, 0L)
        // Mute or push-to-talk: no audio for 3 s, and the sender's time jumped by the same 3 s, so nothing is late.
        val resumed = steady(before.last().arrivalMs + 3_000L, 100, before.last().ptsUs + 3_000_000L)
        assertTrue(stale.replay(before + resumed).all { it == CallStaleAudio.Decision.PLAY })
        assertEquals(0L, stale.totalStale)
    }

    @Test
    fun aLinkThatStaysSlowAfterAStallIsPlayedLateOnceTheStallsOwnBacklogIsDropped() {
        val stale = CallStaleAudio(enabled = true, budgetMs = budgetMs)
        val before = steady(1_000L, 50, 0L)
        // After a 3 s stall every unit keeps arriving 1 s late: at most the gap plus the budget of sender time is dropped.
        val slow = (0 until 400).map { i ->
            Arrival(before.last().arrivalMs + 3_000L + i * 20L, before.last().ptsUs + 20_000L + i * 20_000L, 1_000L)
        }
        val decisions = stale.replay(before + slow).drop(before.size)
        val capUs = (3_000L + budgetMs) * 1_000L
        slow.zip(decisions).forEach { (arrival, decision) ->
            val withinCap = arrival.ptsUs - slow.first().ptsUs < capUs
            assertEquals(if (withinCap) CallStaleAudio.Decision.DROP else CallStaleAudio.Decision.PLAY, decision)
        }
    }
}
