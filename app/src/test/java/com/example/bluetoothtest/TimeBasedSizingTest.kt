package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q31, time-based relay queue and viewer playout target (experimental, off by default). Injected clocks
 * throughout: nothing here sleeps.
 */
class TimeBasedSizingTest {
    private fun config(floorMs: Long = TimeBasedSizing.DEFAULT_FLOOR_MS, budgetMs: Long = TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS) =
        RelayTimeQueueConfig(latencyBudgetMs = budgetMs, floorMs = floorMs, recomputeNs = 0L)

    // ---- sizer ----

    @Test
    fun bothArmsAreOffByDefault() {
        assertFalse(DebugTunables.relayTimeQueue)
        assertFalse(DebugTunables.playoutTimeTarget)
        assertEquals(1_500L, DebugTunables.timeQueueBudgetMs)
        assertEquals(400L, DebugTunables.timeQueueFloorMs)
    }

    @Test
    fun withoutADrainMeasurementTheEarlierBudgetStandsUnchanged() {
        var nowNs = 0L
        val sizer = RelayTimeQueueSizer(config(), 66L) { nowNs }
        sizer.onOffered(20_000, true)
        assertEquals(1_750L, sizer.targetMs(1_750L))
        assertEquals(400L, sizer.targetMs(400L))
        assertEquals(0, sizer.snapshot(1_750L).drainKbps)
    }

    @Test
    fun aFastCalmLinkNeedsOnlyOneKeyframeAndTwoFrames() {
        var nowNs = 0L
        val sizer = RelayTimeQueueSizer(config(floorMs = 100L), 66L) { nowNs }
        sizer.setDrainKbps(1_000)
        sizer.onOffered(20_000, true)
        // 20 000 bytes at 1 000 kbps = 160 ms, plus two 66 ms frames; the earlier budget would hold 1 750 ms.
        assertEquals(160L + 132L, sizer.targetMs(1_750L))
        // The default floor is the earlier minimum.
        val defaults = RelayTimeQueueSizer(config(), 66L) { nowNs }
        defaults.setDrainKbps(1_000)
        defaults.onOffered(20_000, true)
        assertEquals(400L, defaults.targetMs(1_750L))
    }

    @Test
    fun aSlowLinkIsCappedByTheLatencyBudgetAndNeverAboveTheEarlierBudget() {
        var nowNs = 0L
        val sizer = RelayTimeQueueSizer(config(), 66L) { nowNs }
        sizer.setDrainKbps(300)
        sizer.onOffered(56_320, true) // 1 501 ms on the wire
        assertEquals(1_501L, sizer.snapshot(1_750L).keyFrameDrainMs)
        assertEquals(1_500L, sizer.targetMs(1_750L))
        // The child's playout already holds most of its maximum: the earlier budget is 400 ms, so is the target.
        assertEquals(400L, sizer.targetMs(400L))
    }

    @Test
    fun theWorstRecentStallWidensTheCushionAndTheMemoryExpiresAfterTheWindow() {
        var nowNs = 1_000_000_000L
        val sizer = RelayTimeQueueSizer(config(floorMs = 100L), 66L) { nowNs }
        sizer.setDrainKbps(1_000)
        // 2 500-byte records are 20 ms of airtime at 1 000 kbps; the queue is backed up throughout.
        sizer.onTaken(2_500, 0L, nowNs)
        repeat(50) {
            nowNs += 20_000_000L
            sizer.onTaken(2_500, 0L, nowNs)
        }
        assertEquals(0L, sizer.snapshot(1_750L).jitterMs)
        assertEquals(132L, sizer.targetMs(1_750L))
        // One write gap of 620 ms: 598 ms beyond the 22 ms the rate predicts (10% tolerance).
        nowNs += 620_000_000L
        sizer.onTaken(2_500, 0L, nowNs)
        assertEquals(598L, sizer.snapshot(1_750L).jitterMs)
        assertEquals(598L + 132L, sizer.targetMs(1_750L))
        // A minute without another stall: the target returns to its calm value.
        nowNs += TimeBasedSizing.WINDOW_NS + 1L
        assertEquals(132L, sizer.targetMs(1_750L))
    }

    @Test
    fun anIdleQueueIsNotADrainStallButAPauseThatHeldAQueuedRecordIs() {
        var nowNs = 1_000_000_000L
        val sizer = RelayTimeQueueSizer(config(floorMs = 100L), 66L) { nowNs }
        sizer.setDrainKbps(1_000)
        sizer.onTaken(2_500, 0L, nowNs)
        // The next record only arrived two seconds later: the writer waited for the source, not the link.
        nowNs += 2_000_000_000L
        sizer.onTaken(2_500, nowNs, nowNs)
        assertEquals(0L, sizer.snapshot(1_750L).jitterMs)
        // A 21 ms gap for a 20 ms record is inside the 10% tolerance: still zero.
        nowNs += 21_000_000L
        sizer.onTaken(2_500, 0L, nowNs)
        assertEquals(0L, sizer.snapshot(1_750L).jitterMs)
        // A record that arrived 1 ms after the previous take and left 300 ms later waited for the link:
        // the link could have started it after the 22 ms the rate predicts, so 278 ms of stall.
        val previousTakeNs = nowNs
        nowNs += 300_000_000L
        sizer.onTaken(2_500, previousTakeNs + 1_000_000L, nowNs)
        assertEquals(278L, sizer.snapshot(1_750L).jitterMs)
    }

    @Test
    fun aSustainedOverloadDropsTheJitterCushionBecauseTheQueueCannotCatchUp() {
        fun run(drainKbps: Int): RelayTimeQueueSnapshot {
            var nowNs = 1_000_000_000L
            val sizer = RelayTimeQueueSizer(config(floorMs = 100L), 66L) { nowNs }
            sizer.setDrainKbps(drainKbps)
            sizer.onTaken(5_000, 0L, nowNs)
            nowNs += 800_000_000L // one stall of 760 ms beyond the tolerance
            sizer.onTaken(5_000, 0L, nowNs)
            // 6 s of 15 FPS x 5 000 bytes = 600 kbps offered.
            repeat(90) {
                nowNs += 66_666_667L
                sizer.onOffered(5_000, false)
            }
            return sizer.snapshot(1_750L)
        }
        val overloaded = run(300)
        assertTrue(overloaded.overload)
        assertEquals(600, overloaded.offeredKbps)
        assertEquals(132L, overloaded.targetMs)
        val healthy = run(900)
        assertFalse(healthy.overload)
        assertTrue("healthy ${healthy.targetMs}", healthy.targetMs > 700L)
    }

    @Test
    fun theTargetNeverExceedsTheLatencyBudgetWhateverTheJitter() {
        var nowNs = 1_000_000_000L
        val sizer = RelayTimeQueueSizer(config(budgetMs = 900L), 66L) { nowNs }
        sizer.setDrainKbps(1_000)
        sizer.onTaken(2_500, 0L, nowNs)
        nowNs += 5_000_000_000L // a 5 s stall
        sizer.onTaken(2_500, 0L, nowNs)
        assertEquals(900L, sizer.targetMs(1_750L))
    }

    // ---- queue with the sizer (simulated link) ----

    private class SimResult(
        val maxAgeMs: Long,
        val shedEvents: Long,
        val shedFrames: Long,
        val audioOffered: Int,
        val audioTaken: Int,
        val videoTaken: Int,
        val keyFrameRequests: Int,
        val summary: String,
    )

    /**
     * One relay child queue fed by a source at [frameBytes] x 15 FPS plus 50 audio records/s and drained by a
     * link at [drainKbps] (optionally stalling [stallNs] every [stallPeriodNs]). A shed makes the next frame a
     * keyframe, as the source's encoder does on request.
     */
    private fun simulate(
        timeArm: Boolean,
        drainKbps: Int,
        frameBytes: Int,
        seconds: Int,
        stallPeriodNs: Long = 0L,
        stallNs: Long = 0L,
    ): SimResult {
        var nowNs = 0L
        val sizer = if (timeArm) RelayTimeQueueSizer(config(), 67L) { nowNs } else null
        val bound = RelayMediaAgeBound(sizer)
        bound.setDrainKbps(drainKbps)
        val queue = RelayChildQueue(mediaAgeBound = bound, nanoTime = { nowNs })
        sealer = EndToEndContentSealer(key)
        meshSequence = 0L
        var nextFrameNs = 0L
        var nextAudioNs = 0L
        var nextTakeNs = 0L
        var nextStallNs = if (stallPeriodNs > 0L) stallPeriodNs else Long.MAX_VALUE
        var wantKeyFrame = true
        var maxAgeMs = 0L
        var audioOffered = 0
        var audioTaken = 0
        var videoTaken = 0
        var requests = 0
        var tickNs = 0L
        while (tickNs < seconds * SECOND) {
            nowNs = tickNs
            if (nowNs >= nextAudioNs) {
                audioOffered++
                queue.offer(record(StreamProtocol.FLAG_AUDIO, 160))
                nextAudioNs += 20_000_000L
            }
            if (nowNs >= nextFrameNs) {
                val offer = if (wantKeyFrame) {
                    queue.offer(record(StreamProtocol.FLAG_KEY_FRAME, frameBytes * 3))
                } else {
                    queue.offer(record(0, frameBytes))
                }
                wantKeyFrame = when (offer) {
                    ChildQueueOffer.SHED_WAITING_FOR_KEYFRAME -> true.also { requests++ }
                    ChildQueueOffer.SHED_DROPPED -> true
                    else -> false
                }
                nextFrameNs += 66_666_667L
            }
            if (nowNs >= nextStallNs) {
                nextTakeNs = maxOf(nextTakeNs, nowNs) + stallNs
                nextStallNs += stallPeriodNs
            }
            if (nowNs >= nextTakeNs) {
                queue.poll()?.let { envelope ->
                    if (envelope.isAudio) audioTaken++ else videoTaken++
                    nextTakeNs = nowNs + envelope.payload.size * 8_000_000L / drainKbps
                }
            }
            maxAgeMs = maxOf(maxAgeMs, queue.backlogAgeMs())
            tickNs += 1_000_000L
        }
        return SimResult(
            maxAgeMs, bound.shedder.shedEvents, bound.shedder.shedFrames, audioOffered, audioTaken, videoTaken,
            requests, bound.timeQueueSummary(queue.backlogAgeMs()),
        )
    }

    @Test
    fun aLinkSlowerThanTheStreamHoldsLessMediaWithTheTimeTargetThanWithTheFixedBudget() {
        // 5 000-byte frames at 15 FPS plus audio is about 640 kbps offered into 500 kbps: 0.28 s of backlog per second.
        val fixed = simulate(timeArm = false, drainKbps = 500, frameBytes = 5_000, seconds = 40)
        val timed = simulate(timeArm = true, drainKbps = 500, frameBytes = 5_000, seconds = 40)
        println("TIMEQ overload fixed: maxAge=${fixed.maxAgeMs} ms sheds=${fixed.shedEvents} | ${fixed.summary}")
        println("TIMEQ overload timed: maxAge=${timed.maxAgeMs} ms sheds=${timed.shedEvents} | ${timed.summary}")
        assertTrue("fixed ${fixed.maxAgeMs} ms", fixed.maxAgeMs >= 1_500L)
        assertTrue("timed ${timed.maxAgeMs} ms", timed.maxAgeMs in 400L..1_600L)
        assertTrue("timed ${timed.maxAgeMs} < fixed ${fixed.maxAgeMs}", timed.maxAgeMs < fixed.maxAgeMs)
        assertTrue(timed.shedEvents > fixed.shedEvents)
        // Speech is never dropped in either arm.
        assertTrue(fixed.audioOffered - fixed.audioTaken <= 150)
        assertTrue(timed.audioOffered - timed.audioTaken <= 150)
        assertTrue(timed.summary.startsWith("arm=time | "))
        assertTrue(fixed.summary.startsWith("arm=fixed | "))
    }

    @Test
    fun aMildOverloadIsBoundedNearTheTargetWhileTheFixedBudgetLetsItRideToTheFixedLimit() {
        // About 720 kbps offered (with record overhead) into 650: roughly 0.1 s of backlog per second.
        val fixed = simulate(timeArm = false, drainKbps = 650, frameBytes = 5_000, seconds = 60)
        val timed = simulate(timeArm = true, drainKbps = 650, frameBytes = 5_000, seconds = 60)
        println("TIMEQ mild fixed: maxAge=${fixed.maxAgeMs} ms sheds=${fixed.shedEvents} | ${fixed.summary}")
        println("TIMEQ mild timed: maxAge=${timed.maxAgeMs} ms sheds=${timed.shedEvents} | ${timed.summary}")
        assertTrue("fixed ${fixed.maxAgeMs} ms", fixed.maxAgeMs >= 1_500L)
        assertTrue("timed ${timed.maxAgeMs} ms vs fixed ${fixed.maxAgeMs} ms", timed.maxAgeMs <= fixed.maxAgeMs * 7 / 10)
        assertTrue(fixed.audioOffered - fixed.audioTaken <= 150)
        assertTrue(timed.audioOffered - timed.audioTaken <= 150)
    }

    @Test
    fun aLinkThatKeepsUpNeverShedsInEitherArm() {
        val fixed = simulate(timeArm = false, drainKbps = 1_000, frameBytes = 5_000, seconds = 30)
        val timed = simulate(timeArm = true, drainKbps = 1_000, frameBytes = 5_000, seconds = 30)
        assertEquals(0L, fixed.shedEvents)
        assertEquals(0L, timed.shedEvents)
        assertEquals(fixed.videoTaken, timed.videoTaken)
    }

    @Test
    fun repeatedStallsAreLearnedAndStopShedding() {
        // 400 kbps offered into 800 kbps with a 700 ms link pause every 5 s.
        val fixed = simulate(timeArm = false, drainKbps = 800, frameBytes = 3_000, seconds = 60, stallPeriodNs = 5 * SECOND, stallNs = 700_000_000L)
        val timed = simulate(timeArm = true, drainKbps = 800, frameBytes = 3_000, seconds = 60, stallPeriodNs = 5 * SECOND, stallNs = 700_000_000L)
        println("TIMEQ stalls fixed: maxAge=${fixed.maxAgeMs} ms sheds=${fixed.shedEvents} | ${fixed.summary}")
        println("TIMEQ stalls timed: maxAge=${timed.maxAgeMs} ms sheds=${timed.shedEvents} | ${timed.summary}")
        assertEquals(0L, fixed.shedEvents)
        // The first stall is unmeasured and may cost one shed; after it the cushion covers the pauses.
        assertTrue("timed sheds ${timed.shedEvents}", timed.shedEvents <= 1L)
        assertTrue("timed ${timed.maxAgeMs}", timed.maxAgeMs <= 1_500L)
    }

    @Test
    fun theTimeTargetShedsOnlyVideoAndKeepsTheFixedPathCodecSafe() {
        val timed = simulate(timeArm = true, drainKbps = 500, frameBytes = 5_000, seconds = 40)
        // Every shed asked for a keyframe through the existing recovery path, and video resumed after each.
        assertTrue(timed.keyFrameRequests >= 1)
        assertTrue(timed.videoTaken > 100)
        assertTrue(timed.shedFrames > 0)
    }

    // ---- core wiring ----

    @Test
    fun theCoreBuildsASizerOnlyForASheddingQueueWhenTheArmIsOn() {
        fun core(bounded: Boolean, arm: RelayTimeQueueConfig?) =
            ReceiverNodeForwardingCore(node(1), node(3), STREAM_ID, SOURCE, queueCapacity = 8, boundedMediaAge = bounded, timeQueue = arm)
        val on = core(true, RelayTimeQueueConfig())
        on.addChild(node(2))
        on.setChildDrainKbps(node(2), 400)
        assertTrue(on.timeQueueSummary(node(2))!!.startsWith("arm=time | "))
        assertTrue(on.timeQueueSummary(node(2))!!.contains("drainKbps=400"))
        val control = core(true, null)
        control.addChild(node(2))
        control.setChildDrainKbps(node(2), 400)
        assertTrue(control.timeQueueSummary(node(2))!!.startsWith("arm=fixed | "))
        assertTrue(control.timeQueueSummary(node(2))!!.contains("targetMs=1750 | legacyBudgetMs=1750"))
        val noShedding = core(false, RelayTimeQueueConfig())
        noShedding.addChild(node(2))
        assertNull(noShedding.timeQueueSummary(node(2)))
    }

    // ---- viewer playout target ----

    private class Arrival(val ptsUs: Long, val atNs: Long)

    /** 15 FPS media arriving with a repeating 0-299 ms jitter pattern, in arrival order. */
    private fun jitteredArrivals(seconds: Int): List<Arrival> {
        val frames = seconds * 15
        return (0 until frames).map { i ->
            val ptsUs = i * 66_667L
            val jitterMs = (i * 37L) % 300L
            Arrival(ptsUs, 10_000_000_000L + ptsUs * 1_000L + 40_000_000L + jitterMs * 1_000_000L)
        }.sortedBy { it.atNs }
    }

    private fun lateFrames(clock: MediaPlayoutClock, arrivals: List<Arrival>): Int =
        arrivals.count { clock.timing(it.ptsUs, it.atNs).latenessMs > 0L }

    @Test
    fun theMeasuredJitterKeepsTheViewerFromShrinkingBackIntoTheNextHold() {
        val arrivals = jitteredArrivals(seconds = 300)
        val control = MediaPlayoutClock(250L, adaptiveMaxDelayMs = 2_000L)
        val target = PlayoutJitterTarget(budgetMs = 1_500L, recomputeNs = 0L)
        val timed = MediaPlayoutClock(250L, adaptiveMaxDelayMs = 2_000L, timeTarget = target)
        val controlLate = lateFrames(control, arrivals)
        val timedLate = lateFrames(timed, arrivals)
        println("TIMEQ viewer: control late=$controlLate delay=${control.currentDelayMs} | timed late=$timedLate delay=${timed.currentDelayMs} ${timed.timeTargetSummary()}")
        // The earlier rule forgets the burst after a calm minute, shrinks and is late again, cycle after cycle.
        assertTrue("control late $controlLate", controlLate >= 6)
        assertTrue("timed late $timedLate vs control $controlLate", timedLate < controlLate)
        assertTrue(target.grows >= 1)
        // The delay is the measured spread (about 300 ms) plus the margin, not the staircase's guess.
        assertTrue("delay ${timed.currentDelayMs}", timed.currentDelayMs in 320L..420L)
        assertNotNull(timed.timeTargetSummary())
        assertNull(control.timeTargetSummary())
    }

    @Test
    fun theViewerTargetNeverPaysInAdvanceBeyondTheLatencyBudget() {
        // 1.2 s of spread against a 600 ms budget: the advance growth stops at the budget; the lateness rule still reaches further.
        val arrivals = (0 until 900).map { i ->
            val ptsUs = i * 66_667L
            Arrival(ptsUs, 10_000_000_000L + ptsUs * 1_000L + ((i * 37L) % 1_200L) * 1_000_000L)
        }.sortedBy { it.atNs }
        val target = PlayoutJitterTarget(budgetMs = 600L, recomputeNs = 0L)
        val clock = MediaPlayoutClock(250L, adaptiveMaxDelayMs = 2_000L, timeTarget = target)
        var maxAdvanceOnlyDelay = 0L
        for (arrival in arrivals) {
            clock.timing(arrival.ptsUs, arrival.atNs)
            maxAdvanceOnlyDelay = maxOf(maxAdvanceOnlyDelay, clock.currentDelayMs)
        }
        // Lateness growth (today's rule) takes it past the budget because frames really are that late.
        assertTrue(maxAdvanceOnlyDelay > 600L)
        assertTrue(maxAdvanceOnlyDelay <= 2_000L)
        // A steady stream never triggers the advance growth at all.
        val steady = MediaPlayoutClock(250L, adaptiveMaxDelayMs = 2_000L, timeTarget = PlayoutJitterTarget(recomputeNs = 0L))
        repeat(600) { i ->
            val ptsUs = i * 66_667L
            steady.timing(ptsUs, 10_000_000_000L + ptsUs * 1_000L + 30_000_000L)
        }
        assertEquals(250L, steady.currentDelayMs)
    }

    @Test
    fun aTimestampJumpForgetsTheOldOffsets() {
        val target = PlayoutJitterTarget(recomputeNs = 0L)
        val clock = MediaPlayoutClock(250L, adaptiveMaxDelayMs = 2_000L, timeTarget = target)
        var nowNs = 10_000_000_000L
        repeat(100) { i ->
            clock.timing(i * 66_667L, nowNs + ((i * 37L) % 300L) * 1_000_000L)
            nowNs += 66_667_000L
        }
        assertTrue(target.lastNeededMs > 0L)
        // A 10 s jump in the sender's timestamps starts a new timeline.
        clock.timing(20_000_000L, nowNs)
        assertEquals(-1L, target.lastNeededMs)
    }

    @Test
    fun onlyStreamProfilesGetTheViewerTargetAndOnlyWhenSwitchedOn() {
        assertNull(PlaybackDelayProfile.AUTO.newPlayoutClock().timeTargetSummary())
        DebugTunables.playoutTimeTarget = true
        try {
            if (!BuildConfig.DEBUG) {
                // A release build ignores the Experimental switch: no viewer target.
                assertNull(PlaybackDelayProfile.AUTO.newPlayoutClock().timeTargetSummary())
                return
            }
            assertNotNull(PlaybackDelayProfile.AUTO.newPlayoutClock().timeTargetSummary())
            assertNotNull(PlaybackDelayProfile.BALANCED.newPlayoutClock().timeTargetSummary())
            assertNull(PlaybackDelayProfile.CALL_BALANCED.newPlayoutClock().timeTargetSummary())
        } finally {
            DebugTunables.playoutTimeTarget = false
        }
    }

    // ---- helpers ----

    private val key = ByteArray(MeshAead.KEY_BYTES) { 7 }
    private var sealer = EndToEndContentSealer(key)
    private var meshSequence = 0L

    private fun record(flags: Int, size: Int): MeshEnvelope {
        val sequence = meshSequence++
        val plaintext = EndToEndStreamProtocol.encodePacket(flags, sequence * 66_667L, ByteArray(size) { sequence.toByte() })
        return MeshEnvelope(
            streamId = STREAM_ID,
            sourceNodeId = SOURCE,
            sequence = sequence,
            ttl = 5,
            flags = meshPacketFlags(flags),
            payload = sealer.seal(EndToEndContentType.MEDIA_PACKET, flags, plaintext),
        )
    }

    private fun node(value: Int): NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { value.toByte() })

    private companion object {
        const val SECOND = 1_000_000_000L
        val STREAM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000078")
        val SOURCE: NodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 9 })
    }
}
