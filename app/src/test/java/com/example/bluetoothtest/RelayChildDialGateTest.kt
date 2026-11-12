package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b6-q31-c1-reset 06-A3 (build 2cc4ca5a, M31 relay, A15s child, GATT): the child opened a 15 s advertising window about every
 * 23 s and the relay dialled it twice per window; every dial ended in HCI 0x3E (status 133). After the second failure the
 * child is marked, and until the mark ends each sighting used to stop and restart the relay's offer and scan. Logged at
 * 20:27:23.6 to 24.4: six offer-set starts and five scan starts in 0.8 s, then "BLE scan start deferred 29471 ms: scan quota".
 * [RelayChildDialGate] decides, in the discovery callback and before the presence is stopped, whether a sighting is dropped.
 */
class RelayChildDialGateTest {
    private val child = "a15a15a15a15"

    private fun verdict(marked: Boolean = false, heldMs: Long = 0L, fallbackOn: Boolean = true, holdoffOn: Boolean = true) =
        RelayChildDialGate.verdict(fallbackOn, marked, holdoffOn, heldMs)

    @Test
    fun anUnmarkedChildIsDialled() {
        assertEquals(RelayChildDialGate.Verdict.DIAL, verdict())
    }

    @Test
    fun aMarkedChildIsDeferredAndAMarkWithTheFallbackSwitchOffIsIgnored() {
        assertEquals(RelayChildDialGate.Verdict.DEFER_MARKED, verdict(marked = true))
        assertEquals(RelayChildDialGate.Verdict.DIAL, verdict(marked = true, fallbackOn = false))
    }

    @Test
    fun aHeldOffChildIsDeferredAndTheHoldoffSwitchOffIsIgnored() {
        assertEquals(RelayChildDialGate.Verdict.DEFER_HOLDOFF, verdict(heldMs = 9_811L))
        assertEquals(RelayChildDialGate.Verdict.DIAL, verdict(heldMs = 9_811L, holdoffOn = false))
        assertEquals(RelayChildDialGate.Verdict.DIAL, verdict(heldMs = 0L))
    }

    @Test
    fun theMarkIsReportedBeforeTheHoldoff() {
        assertEquals(RelayChildDialGate.Verdict.DEFER_MARKED, verdict(marked = true, heldMs = 5_000L))
    }

    /** The relay's real trackers with the 06-A3 timeline: two failed dials, then the child is seen every 120 ms. */
    @Test
    fun theGateDefersEverySightingWhileTheChildIsMarkedAndDialsTheFirstOneAfterTheMark() {
        val tracker = EstablishmentFailureTracker()
        val holdoff = RelayStallHoldoff()
        val t0 = 1_000_000L
        // Dials at +0.0 s and +1.5 s (20:27:20.8 and 22.1); both fail 133, the second marks the child for 30 s (decaying).
        holdoff.onStall(child, t0)
        assertFalse(tracker.onFailure(child, t0, decaying = true))
        holdoff.onStall(child, t0 + 2_700L)
        assertTrue(tracker.onFailure(child, t0 + 2_700L, decaying = true))
        val markedUntil = t0 + 2_700L + EstablishmentFailureTracker.decayingMarkMs(0)

        // The mark is 30 s long; read it before the loop, because isMarked drops an expired mark.
        assertTrue(tracker.remainingMs(child, t0 + 2_800L) in 29_000L..30_000L)

        var deferred = 0
        var dialAt: Long? = null
        var now = t0 + 2_800L
        while (now <= markedUntil + 2_000L && dialAt == null) {
            val v = RelayChildDialGate.verdict(
                fallbackOn = true,
                marked = tracker.isMarked(child, now),
                holdoffOn = true,
                heldMs = holdoff.remainingMs(child, now),
            )
            if (v == RelayChildDialGate.Verdict.DIAL) dialAt = now else deferred++
            now += 120L
        }
        // About 30 s of sightings at 120 ms are all dropped; none of them touched the mark or the hold-off.
        assertTrue("deferred $deferred", deferred in 240..260)
        assertTrue("dial at $dialAt", dialAt != null && dialAt >= markedUntil && dialAt < markedUntil + 120L)
    }

    @Test
    fun theDeferralLogIsLimitedToOneLinePerChildAndInterval() {
        val log = DialDeferLog(everyMs = 5_000L)
        var lines = 0
        var covered = 0
        // 9.8 s of sightings every 120 ms: lines at 0 s and 5.04 s only; together they cover every sighting.
        var now = 0L
        var sightings = 0
        while (now < 9_800L) {
            sightings++
            val n = log.onDeferred(child, now)
            if (n > 0) {
                lines++
                covered += n
            }
            now += 120L
        }
        assertEquals(2, lines)
        assertTrue("covered $covered of $sightings", covered < sightings && covered >= 42)
        // A different child has its own interval.
        assertEquals(1, log.onDeferred("other", 120L))
    }

    @Test
    fun resetMakesTheNextDeferralLogAtOnce() {
        val log = DialDeferLog(everyMs = 5_000L)
        assertEquals(1, log.onDeferred(child, 0L))
        assertEquals(0, log.onDeferred(child, 100L))
        log.reset(child)
        assertEquals(1, log.onDeferred(child, 200L))
    }

    @Test
    fun holdInPlaceIsOnByDefaultAndTheControlArmIsDebugOnly() {
        // Connection review B1 (2026-10-27): promoted to default on; =false is the DEBUG control arm.
        assertTrue(DebugTunables.relayHoldInPlace)
        try {
            DebugTunables.relayHoldInPlace = false
            // The control arm is DEBUG-only: a release build keeps hold in place on.
            assertEquals(!BuildConfig.DEBUG, DebugTunables.relayHoldInPlace)
        } finally {
            DebugTunables.relayHoldInPlace = true
        }
    }
}
