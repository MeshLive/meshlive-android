package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Experimental burst-aware drain (sender) and one-step playout growth (receiver). */
class BurstDrainTest {
    private val second = 1_000_000_000L

    /**
     * regress-57484598 case 0006 (M31 -> ASUS GATT VP9 300 kbps), the window that cut to 206 kbps:
     * offered 284 kbit, sent 284 kbit, backlog 0 ms, child late, measured 843 kbps, grace over,
     * about 2.5 s after the 72 KB scene cut was queued.
     */
    private fun afterBurst(largeFrameAgeMs: Int = 2_500, backlogMs: Int = 0, sent: Long = 35_500) = LinkRateSample(
        windowNs = second,
        offeredBytes = 35_500,
        sentBytes = sent,
        backlogAgeMs = backlogMs,
        largeFrameGrace = false,
        measuredLinkKbps = 843,
        childLate = true,
        largeFrameAgeMs = largeFrameAgeMs,
    )

    @Test
    fun withoutTheExperimentOneBurstCutsTheRate() {
        // 284 kbps carried x 0.85 - 32 kbps audio: the cut the M31 made with an empty queue.
        val controller = LinkRateController(300_000)
        assertEquals(209_400, controller.onSample("a", afterBurst(), 0))
        assertEquals(0, controller.burstAttributedSamples)
    }

    @Test
    fun theExperimentAttributesLatenessAfterABurstOnALinkThatKeepsUp() {
        val controller = LinkRateController(300_000, burstAwareDrain = true)
        assertNull(controller.onSample("a", afterBurst(), 0))
        assertNull(controller.onSample("a", afterBurst(largeFrameAgeMs = 3_500), second))
        assertEquals(300_000, controller.effectiveVideoBps)
        assertEquals(2, controller.burstAttributedSamples)
    }

    @Test
    fun lateChildrenStillCountWhenTheLinkFallsBehindOrNoBurstExplainsIt() {
        // A backlog: the link does not carry the offer now.
        assertEquals(
            209_400,
            LinkRateController(300_000, burstAwareDrain = true).onSample("a", afterBurst(backlogMs = 600), 0),
        )
        // Less than 90% of the offer carried.
        assertTrue(
            LinkRateController(300_000, burstAwareDrain = true).onSample("a", afterBurst(sent = 30_000), 0)!! < 300_000,
        )
        // Long after the burst, or with no large frame at all.
        assertEquals(
            209_400,
            LinkRateController(300_000, burstAwareDrain = true).onSample("a", afterBurst(largeFrameAgeMs = 7_000), 0),
        )
        assertEquals(
            209_400,
            LinkRateController(300_000, burstAwareDrain = true).onSample("a", afterBurst(largeFrameAgeMs = -1), 0),
        )
    }

    @Test
    fun aBurstAttributedWindowIsNotCountedAsClean() {
        val controller = LinkRateController(300_000, burstAwareDrain = true)
        controller.onSample("a", LinkRateSample(second, 35_500, 30_000, 600, false), 0) // backlog: cut
        val cut = controller.effectiveVideoBps
        assertTrue(cut < 300_000)
        // Burst-attributed lateness holds; it never counts toward the clean windows that step up.
        repeat(5) { assertNull(controller.onSample("a", afterBurst(), (it + 1) * second)) }
        assertEquals(cut, controller.effectiveVideoBps)
    }

    @Test
    fun theDrainTrackerHoldsGraceUntilTheBurstStopsDraining() {
        val tracker = BurstDrainTracker()
        assertEquals(-1, tracker.largeFrameAgeMs(0))
        assertFalse(tracker.draining(0, 0))
        tracker.onLargeFrameQueued(second)
        // Queued but not yet written (or still being written): draining.
        assertTrue(tracker.draining(second + 500_000_000, 140_000))
        assertEquals(500, tracker.largeFrameAgeMs(second + 500_000_000))
        // The blocking write completed: the first sample after it is still the burst, even though
        // frames queued during the write make the backlog look older and larger.
        tracker.onLargeFrameWritten(4 * second)
        assertTrue(tracker.draining(4 * second + 10_000_000, 160_000))
        // Shrinking: still draining. Growing again: not the burst any more.
        assertTrue(tracker.draining(5 * second, 60_000))
        assertFalse(tracker.draining(6 * second, 90_000))
        // Bounded: never beyond 10 s after the large frame.
        assertFalse(tracker.draining(12 * second, 1_000))
    }

    @Test
    fun aLargeFrameBeforeTheFirstSampleCountsAsWrittenAfterIt() {
        val tracker = BurstDrainTracker()
        tracker.onLargeFrameQueued(0)
        tracker.onLargeFrameWritten(second)
        assertTrue(tracker.draining(2 * second, 50_000))
    }

    @Test
    fun oneStepPlayoutGrowthCoversABurstThatTheStaircaseMeetsInSeveralHolds() {
        // A 4-frame burst at 15 FPS: the cut frame arrives 600 ms late, the next three follow
        // 325, 200 and 90 ms after it (40/24/11 KB at ~1 Mbps). Default: lateness + 50 ms per frame.
        val arrivalsMs = listOf(0L to 850L, 66L to 1_175L, 133L to 1_375L, 200L to 1_465L)
        fun grows(factor: Double?): Int {
            val clock = MediaPlayoutClock(
                delayMs = 250,
                adaptiveMaxDelayMs = 2_000,
                burstGrowthFactor = factor,
            )
            clock.timing(0, 0) // anchor: PTS 0 plays at 250 ms
            var steps = 0
            var last = clock.currentDelayMs
            arrivalsMs.forEach { (ptsMs, arrivalMs) ->
                clock.timing(1_000_000L + ptsMs * 1_000, (1_000L + arrivalMs) * 1_000_000L)
                if (clock.currentDelayMs > last) steps++
                last = clock.currentDelayMs
            }
            return steps
        }
        assertTrue(grows(null) >= 2)
        assertEquals(1, grows(2.0))
    }

    @Test
    fun oneStepGrowthLeavesSmallLatenessToTheMarginRule() {
        val clock = MediaPlayoutClock(delayMs = 250, adaptiveMaxDelayMs = 2_000, burstGrowthFactor = 2.0)
        clock.timing(0, 0)
        // 100 ms late (< 150 ms): grows by lateness + 50 ms, as without the experiment.
        clock.timing(1_000_000, 1_350_000_000)
        assertEquals(400, clock.currentDelayMs)
        // 600 ms late: grows by 2 x 600 ms at once, capped at the maximum.
        clock.timing(2_000_000, 2_400_000_000L + 600_000_000L)
        assertEquals(1_600, clock.currentDelayMs)
    }

    @Test
    fun theOneStepGrowthArmChangesStreamClocksButNeverCallClocks() {
        // A release build ignores launch extras (the setter keeps the shipped default); the growth arm cannot be set there.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        fun delayAfterLateFrame(profile: PlaybackDelayProfile): Long {
            val clock = profile.newPlayoutClock()
            clock.timing(0, 0)
            clock.timing(1_000_000, (1_000L + profile.delayMs + 600L) * 1_000_000L) // 600 ms late
            return clock.currentDelayMs
        }
        try {
            DebugTunables.playoutBurstGrowth = null
            val staircase = PlaybackDelayProfile.entries.associateWith(::delayAfterLateFrame)
            DebugTunables.playoutBurstGrowth = 2.0
            PlaybackDelayProfile.entries.filter { it.stabilityLabel == "Call" }.forEach { call ->
                assertEquals(call.name, staircase.getValue(call), delayAfterLateFrame(call))
            }
            assertTrue(delayAfterLateFrame(PlaybackDelayProfile.BALANCED) > staircase.getValue(PlaybackDelayProfile.BALANCED))
        } finally {
            DebugTunables.playoutBurstGrowth = null
        }
    }
}
