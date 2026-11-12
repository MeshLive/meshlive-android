package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallRediscoverTest {
    private val off = StallRediscover.DEFAULT_MS
    private val arm = 20_000L

    @Test
    fun armOffKeepsTheEarlierClampExactly() {
        // 3 s stall + retry start: 12 s of the early-loss window left -> the 8 s cap; later starts shrink it.
        assertEquals(8_000L, StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L))
        assertEquals(5_000L, StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 5_000L))
        assertEquals(0L, StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = -3_000L))
        // A value below the default is the default too.
        assertEquals(8_000L, StallRediscover.budgetMs(1_000L, stalled = true, earlyLossRemainingMs = 12_000L))
        // A found address at 11 s misses the 8 s budget: the stale address is dialed, as measured.
        assertFalse(StallRediscover.dialsFresh(8_000L, 11_000L))
    }

    @Test
    fun armOnOnlyChangesTheStalledPath() {
        assertEquals(8_000L, StallRediscover.budgetMs(arm, stalled = false, earlyLossRemainingMs = 12_000L))
        assertEquals(5_000L, StallRediscover.budgetMs(arm, stalled = false, earlyLossRemainingMs = 5_000L))
        assertEquals(20_000L, StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L))
        // Not bound by the early-loss window, nor by an exhausted one.
        assertEquals(20_000L, StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = -3_000L))
        assertEquals(StallRediscover.MAX_MS, StallRediscover.budgetMs(10_000_000L, stalled = true, earlyLossRemainingMs = 0L))
    }

    @Test
    fun foundAtThreeSecondsDialsTheFreshAddress() {
        val budget = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L)
        assertTrue(StallRediscover.dialsFresh(budget, 3_000L))
    }

    @Test
    fun foundAtElevenSecondsIsDialedOnlyWithTheArmOn() {
        // ASUS re-advertised 8.6-12.6 s after the close when the A15s LL_TERMINATE never arrived.
        val on = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L)
        val offBudget = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L)
        assertTrue(StallRediscover.dialsFresh(on, 11_000L))
        assertFalse(StallRediscover.dialsFresh(offBudget, 11_000L))
    }

    @Test
    fun nothingInTwentySecondsDialsTheStaleAddress() {
        val budget = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L)
        assertFalse(StallRediscover.dialsFresh(budget, null))
        assertFalse(StallRediscover.dialsFresh(budget, 20_001L))
        assertTrue(StallRediscover.dialsFresh(budget, 20_000L))
    }

    @Test
    fun theBenchmarkLineNamesBudgetWaitFoundAddressAndDial() {
        assertEquals(
            "BENCHMARK call_rediscover: budgetMs=20000 | waitedMs=11000 | found=true | sameAddress=false | dialed=fresh | " +
                "stalled=true | armMs=20000",
            StallRediscover.line(20_000L, 11_000L, found = true, sameAddress = false, stalled = true, armMs = arm),
        )
        assertTrue(
            StallRediscover.line(8_000L, 8_000L, found = false, sameAddress = false, stalled = true, armMs = off)
                .contains("dialed=stale"),
        )
    }

    // Release window (test_rediscover_release_window), measured timelines of a15s-rootcause-20261104.
    private val gatt = StallRediscover.CALLEE_GATT_RESTART_MS

    @Test
    fun aGattCalleeThatReleasesElevenAndAHalfSecondsAfterOurStallCloseIsDialedFresh() {
        // ASUS re-advertised 11.56 s after the close in the slowest of 12 GATT runs; the fixed 8 s scan found 0 of 12.
        val on = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = gatt)
        assertTrue(StallRediscover.dialsFresh(on, 11_560L))
        val offBudget = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = false, calleeRestartMs = gatt)
        assertFalse(StallRediscover.dialsFresh(offBudget, 11_560L))
    }

    @Test
    fun aCocCalleeThatReleasesNineSecondsAfterOurStallCloseIsDialedFresh() {
        val on = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = 0L)
        assertTrue(StallRediscover.dialsFresh(on, 9_240L))
    }

    @Test
    fun afterALossWeObservedTheCalleeSeenSixAndAHalfSecondsLaterIsDialed() {
        // A loss after the handshake leaves only the rest of the 15 s window (5 720 ms in one log); the callee came back at 6.53 s.
        val on = StallRediscover.budgetMs(off, stalled = false, earlyLossRemainingMs = 5_720L, releaseWindow = true, calleeRestartMs = gatt)
        assertTrue(StallRediscover.dialsFresh(on, 6_530L))
        val offBudget = StallRediscover.budgetMs(off, stalled = false, earlyLossRemainingMs = 5_720L, releaseWindow = false, calleeRestartMs = gatt)
        assertFalse(StallRediscover.dialsFresh(offBudget, 6_530L))
    }

    @Test
    fun theSlowestCalleesOfTheReleaseWindowAbAreDialedFresh() {
        // release-window-ab-20261104: CoC callee re-advertised 16.60 s after the close (A15s link down at 11.52 s); the two
        // on-arm misses re-advertised at 12.62 s (GATT) and 11.98 s (CoC), just after the earlier 12.5 s / 11 s scans ended.
        val coc = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = 0L)
        assertTrue(StallRediscover.dialsFresh(coc, 16_600L))
        assertTrue(StallRediscover.dialsFresh(coc, 11_980L))
        val g = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = gatt)
        assertTrue(StallRediscover.dialsFresh(g, 12_620L))
    }

    @Test
    fun aCalleeThatComesBackAtOnceIsStillDialedAtOnce() {
        val on = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = gatt)
        assertTrue(StallRediscover.dialsFresh(on, 200L))
    }

    @Test
    fun anAbsentCalleeIsGivenUpWithinTheCap() {
        val on = StallRediscover.budgetMs(off, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = 60_000L)
        assertTrue(on <= StallRediscover.RELEASE_CAP_MS)
        assertFalse(StallRediscover.dialsFresh(on, null))
    }

    @Test
    fun theReleaseWindowNeverShortensALargerStallArm() {
        val on = StallRediscover.budgetMs(arm, stalled = true, earlyLossRemainingMs = 12_000L, releaseWindow = true, calleeRestartMs = gatt)
        assertEquals(20_000L, on)
    }
}
