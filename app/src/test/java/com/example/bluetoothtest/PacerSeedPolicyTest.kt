package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** One pacer seed policy and one per-child queue policy (switch audit F4: B3, B12). */
class PacerSeedPolicyTest {
    private var nowNs = 0L

    private fun pacer() = LinkPacer({ nowNs })

    private val defaults get() = PacerQueueConfig.current()

    @After
    fun restore() {
        DebugTunables.timeQueueBudgetMs = TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS
        DebugTunables.playoutTimeBudgetMs = null
        DebugTunables.relayTimeQueue = false
        DebugTunables.playoutTimeTarget = false
    }

    // ---- B3: a relay-child shed is not the link draining

    /**
     * A slow relay hop: the writer blocks in GATT writes (link-limited) while the child reports on time. Between two reports
     * the relay-child media age shed the queue to a keyframe, so its age fell from 1.8 s to 0.6 s although the link drained
     * nothing faster. Before, the guard read the fall as draining and raised the seed another 25%.
     */
    @Test
    fun aShedBetweenReportsDoesNotCountAsDraining() {
        val guarded = PacerQueueConfig.current().copy(drainGuard = true)
        val p = pacer()
        p.seedKbps(350)
        var dropped = 0L
        nowNs += 1_000_000_000L
        p.noteWrite(0L, 900_000_000L)
        PacerSeedPolicy.onTimeReport(p, guarded, { 1_800L }, { dropped }) // first report: no history, raises
        val afterFirst = p.seedKbps
        dropped += 12 // the shed removed 12 video records
        nowNs += 1_000_000_000L
        p.noteWrite(0L, 900_000_000L)
        val hold = PacerSeedPolicy.onTimeReport(p, guarded, { 600L }, { dropped })
        assertNotNull(hold)
        assertEquals(afterFirst, p.seedKbps) // no raise
        assertEquals(DrainGuardBlock(1_800L, 600L, 90L, 0L, shed = true), hold!!.guardBlock)
    }

    @Test
    fun withoutAShedTheSameFallIsDrainingAndTheRaiseContinues() {
        val guarded = PacerQueueConfig.current().copy(drainGuard = true)
        val p = pacer()
        p.seedKbps(350)
        nowNs += 1_000_000_000L
        p.noteWrite(0L, 900_000_000L)
        PacerSeedPolicy.onTimeReport(p, guarded, { 1_800L }, { 0L })
        val afterFirst = p.seedKbps
        nowNs += 1_000_000_000L
        p.noteWrite(0L, 900_000_000L)
        val hold = PacerSeedPolicy.onTimeReport(p, guarded, { 600L }, { 0L })
        assertTrue(hold!!.raised)
        assertTrue(p.seedKbps > afterFirst)
        assertNull(hold.guardBlock)
    }

    @Test
    fun theDefaultRaiseIgnoresDropsAndMatchesTheDirectHold() {
        // Drain guard off (the default): the policy is exactly the earlier holdSeed(backlog, false) per report.
        val viaPolicy = pacer().also { it.seedKbps(350) }
        val direct = pacer().also { it.seedKbps(350) }
        var dropped = 0L
        var droppedReads = 0
        for (backlog in listOf(900L, 300L, 1_200L, 2_000L, 450L, 700L)) {
            nowNs += 1_000_000_000L
            dropped += 5
            val hold = PacerSeedPolicy.onTimeReport(viaPolicy, defaults, { backlog }, { droppedReads++; dropped })
            val before = direct.seedKbps
            val held = direct.holdSeed(backlog, false)
            assertEquals(held, hold != null)
            assertEquals(direct.seedKbps, viaPolicy.seedKbps)
            assertEquals(direct.seedKbps > before, hold?.raised == true)
        }
        assertEquals(0, droppedReads) // the drop count is not even read while the guard is off
    }

    @Test
    fun withTheRaiseOffTheBacklogIsNotReadAndTheSeedIsOnlyHeld() {
        val p = pacer().also { it.seedKbps(350) }
        var reads = 0
        nowNs += 1_000_000_000L
        val hold = PacerSeedPolicy.onTimeReport(p, defaults.copy(onTimeSeedRaise = false), { reads++; 5_000L }, { 0L })
        assertEquals(0, reads)
        assertEquals(350, p.seedKbps)
        assertEquals(-1L, hold!!.backlogMs)
    }

    // ---- B3: the lead cap no longer starves the sustained drain

    private fun busyMeterWithEstimate(meter: SustainedDeliveryMeter): Long {
        var t = 0L
        meter.onWritten(t, 1_000, backlogged = true)
        // 6 s of link-bound 1 000-byte writes every 40 ms (200 kbps).
        repeat(150) {
            t += 40_000_000L
            meter.onWritten(t, 1_000, backlogged = true)
        }
        assertEquals(200, meter.estimateKbps(t))
        return t
    }

    @Test
    fun cyclesHeldByTheLeadCapKeepTheLastDrainWhileTheChildIsLate() {
        val meter = SustainedDeliveryMeter()
        var t = busyMeterWithEstimate(meter)
        // The child reports late: the cap holds the writer most of each cycle for 45 s, media still waiting.
        repeat(450) {
            t += 100_000_000L
            meter.onWritten(t, 500, backlogged = true, heldNs = 90_000_000L, leadCapped = true)
        }
        assertEquals(200, meter.estimateKbps(t))
    }

    @Test
    fun theSameHeldCyclesWithoutTheCapExpireTheEstimateAsBefore() {
        val meter = SustainedDeliveryMeter()
        var t = busyMeterWithEstimate(meter)
        repeat(450) {
            t += 100_000_000L
            meter.onWritten(t, 500, backlogged = true, heldNs = 90_000_000L)
        }
        assertNull(meter.estimateKbps(t))
    }

    @Test
    fun anIdleQueueUnderTheCapStillLetsTheEstimateExpire() {
        val meter = SustainedDeliveryMeter()
        var t = busyMeterWithEstimate(meter)
        repeat(450) {
            t += 100_000_000L
            meter.onWritten(t, 500, backlogged = false, heldNs = 90_000_000L, leadCapped = true)
        }
        assertNull(meter.estimateKbps(t))
    }

    // ---- B12: separate relay-queue and playout budgets

    @Test
    fun theOldKnobStillSetsBothBudgetsAndTheNewOneOnlyThePlayout() {
        // The time queue and playout target are Experimental DEBUG arms; a release build keeps both off.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        DebugTunables.relayTimeQueue = true
        DebugTunables.playoutTimeTarget = true
        DebugTunables.timeQueueBudgetMs = 1_200L
        assertEquals(1_200L, RelayChildQueuePolicy.of(PacerQueueConfig.current(), audioOnly = false).timeQueue!!.latencyBudgetMs)
        assertEquals(1_200L, PlayoutJitterTarget.fromTunables()!!.budgetMs)
        DebugTunables.playoutTimeBudgetMs = 3_000L
        assertEquals(1_200L, RelayChildQueuePolicy.of(PacerQueueConfig.current(), audioOnly = false).timeQueue!!.latencyBudgetMs)
        assertEquals(3_000L, PlayoutJitterTarget.fromTunables()!!.budgetMs)
    }

    @Test
    fun anAudioOnlyStreamGetsNoTimeQueue() {
        DebugTunables.relayTimeQueue = true
        assertNull(RelayChildQueuePolicy.of(PacerQueueConfig.current(), audioOnly = true).timeQueue)
    }
}
