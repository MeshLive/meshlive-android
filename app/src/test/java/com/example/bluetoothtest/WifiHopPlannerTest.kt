package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiHopPlannerTest {
    private var now = 1_000_000L
    private fun planner() = WifiHopPlanner { now }

    private fun outcome(kind: OutcomeKind, busy: Boolean = false) = Outcome(kind, "test", busy = busy)

    private fun WifiHopPlanner.waitMs(): Long {
        val verdict = canStart(0)
        assertTrue("expected Wait, got $verdict", verdict is WifiHopPlanner.Verdict.Wait)
        return (verdict as WifiHopPlanner.Verdict.Wait).untilMs - now
    }

    @Test
    fun aFailedAttemptWaitsThirtySeconds() {
        val p = planner()
        assertEquals(WifiHopPlanner.Verdict.Go, p.canStart(0))
        p.onAttemptEnded(outcome(OutcomeKind.FAILED_PEER))
        assertEquals(30_000L, p.waitMs())
        now += 29_999
        assertTrue(p.canStart(0) is WifiHopPlanner.Verdict.Wait)
        now += 1
        assertEquals(WifiHopPlanner.Verdict.Go, p.canStart(0))
    }

    @Test
    fun twoFailedAttemptsEndWifiForTheHop() {
        val p = planner()
        p.onAttemptEnded(outcome(OutcomeKind.FAILED_LOCAL))
        now += 30_000
        p.onAttemptEnded(outcome(OutcomeKind.FAILED_PEER))
        assertEquals(WifiHopPlanner.Verdict.Never("failed_twice"), p.canStart(0))
        assertEquals(2, p.failedAttempts)
    }

    @Test
    fun noPenaltyEndsAreCappedAtThree() {
        val p = planner()
        repeat(3) {
            p.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY))
            now += 30_000
        }
        assertEquals(WifiHopPlanner.Verdict.Never("declined_thrice"), p.canStart(0))
        val fresh = planner()
        fresh.onAttemptEnded(outcome(OutcomeKind.CANCELLED))
        assertEquals(0, fresh.noPenaltyEnds)
        assertEquals(30_000L, fresh.waitMs())
    }

    @Test
    fun fallbacksAtTwoEndWifi() {
        val p = planner()
        assertEquals(WifiHopPlanner.Verdict.Go, p.canStart(1))
        assertEquals(WifiHopPlanner.Verdict.Never("fallbacks"), p.canStart(2))
    }

    @Test
    fun carrierLostCoolsDownThirtySeconds() {
        val p = planner()
        p.onAttemptEnded(outcome(OutcomeKind.SUCCESS))
        assertEquals(WifiHopPlanner.Verdict.Go, p.canStart(0))
        p.onCarrierLost()
        assertEquals(30_000L, p.waitMs())
        now += 30_000
        assertEquals(WifiHopPlanner.Verdict.Go, p.canStart(0))
    }

    @Test
    fun neverBecauseIsFinal() {
        val p = planner()
        p.neverBecause("switch_off")
        assertEquals(WifiHopPlanner.Verdict.Never("switch_off"), p.canStart(0))
        p.onAttemptEnded(outcome(OutcomeKind.SUCCESS))
        p.onCarrierLost()
        assertEquals(WifiHopPlanner.Verdict.Never("switch_off"), p.canStart(0))
    }

    @Test
    fun aGuestServesAtMostSixHostsTwentyNineSecondsApart() {
        val p = planner()
        for (i in 0 until 6) {
            assertEquals(WifiHopPlanner.GuestVerdict.Serve, p.guestMayServe())
            p.onGuestHostAccepted()
        }
        assertEquals(WifiHopPlanner.GuestVerdict.Decline(4, "host_limit"), p.guestMayServe())

        val spacing = planner()
        spacing.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY))
        now += 10_000
        assertEquals(WifiHopPlanner.GuestVerdict.Decline(4, "host_spacing"), spacing.guestMayServe())
        now += 19_000
        assertEquals(WifiHopPlanner.GuestVerdict.Serve, spacing.guestMayServe())
    }

    @Test
    fun twoSpoofedAttemptsStopWifiForTheHopWithoutCountingAsFailures() {
        val p = planner()
        p.onAttemptEnded(outcome(OutcomeKind.FAILED_SPOOFED))
        now += 30_000
        p.onAttemptEnded(outcome(OutcomeKind.FAILED_PEER))
        assertTrue(p.canStart(0) is WifiHopPlanner.Verdict.Wait)
        assertEquals(1, p.spoofedAttempts)
        assertEquals(1, p.failedAttempts)
        now += 30_000
        p.onAttemptEnded(outcome(OutcomeKind.FAILED_SPOOFED))
        assertEquals(WifiHopPlanner.Verdict.Never("spoof_cap"), p.canStart(0))
        assertEquals(2, p.spoofedAttempts)
        assertEquals(1, p.failedAttempts)
        assertEquals(WifiHopPlanner.GuestVerdict.Decline(1, "spoof_cap"), p.guestMayServe())
    }

    @Test
    fun busyEndsAreNotCounted() {
        val p = planner()
        val waits = ArrayList<Long>()
        repeat(5) {
            p.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
            waits += p.waitMs()
            now += waits.last()
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 240_000L), waits)
        assertEquals(0, p.noPenaltyEnds)
        assertEquals(WifiHopPlanner.Verdict.Go, p.canStart(0))
    }

    @Test
    fun aSuccessOrAReleasedPathResetsTheBusyBackOff() {
        val p = planner()
        p.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
        now += 30_000
        p.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
        assertEquals(60_000L, p.waitMs())
        p.onDataPathReleased()
        now += 60_000
        p.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
        assertEquals(30_000L, p.waitMs())

        val success = planner()
        success.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
        now += 30_000
        success.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
        now += 60_000
        success.onAttemptEnded(outcome(OutcomeKind.SUCCESS))
        success.onAttemptEnded(outcome(OutcomeKind.NO_PENALTY, busy = true))
        assertEquals(30_000L, success.waitMs())
    }
}
