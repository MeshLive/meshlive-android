package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

/** AP9: the Aware goodput guard judges 5 s windows against the hop's requested media rate. */
class WifiGoodputGuardTest {
    private val window = WifiAttemptLimits.GOODPUT_WINDOW_MS

    @Test
    fun threeLowWindowsFallBackAndTwoDoNot() {
        val guard = WifiGoodputGuard()
        assertEquals(WifiGoodputGuard.Verdict.OK, guard.onSample(0, 0, mediaOffered = true, requestedKbps = 500))
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(window, 0, mediaOffered = true, requestedKbps = 500))
        assertEquals(
            "two windows are not enough",
            WifiGoodputGuard.Verdict.LOW,
            guard.onSample(2 * window, 0, mediaOffered = true, requestedKbps = 500),
        )
        assertEquals(
            WifiGoodputGuard.Verdict.FALL_BACK,
            guard.onSample(3 * window, 0, mediaOffered = true, requestedKbps = 500),
        )
    }

    @Test
    fun aGoodWindowResetsTheCount() {
        val guard = WifiGoodputGuard()
        guard.onSample(0, 0, mediaOffered = true, requestedKbps = 500)
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(window, 0, mediaOffered = true, requestedKbps = 500))
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(2 * window, 0, mediaOffered = true, requestedKbps = 500))
        // 625,000 bytes over 5 s = 1,000 kbps, above the requested 500.
        assertEquals(
            WifiGoodputGuard.Verdict.OK,
            guard.onSample(3 * window, 625_000, mediaOffered = true, requestedKbps = 500),
        )
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(4 * window, 625_000, mediaOffered = true, requestedKbps = 500))
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(5 * window, 625_000, mediaOffered = true, requestedKbps = 500))
        assertEquals(
            WifiGoodputGuard.Verdict.FALL_BACK,
            guard.onSample(6 * window, 625_000, mediaOffered = true, requestedKbps = 500),
        )
    }

    @Test
    fun noOfferedMediaNeverFallsBack() {
        val guard = WifiGoodputGuard()
        guard.onSample(0, 0, mediaOffered = false, requestedKbps = 500)
        repeat(3) { index ->
            assertEquals(
                WifiGoodputGuard.Verdict.OK,
                guard.onSample((index + 1) * window, 0, mediaOffered = false, requestedKbps = 500),
            )
        }
        assertEquals(0, guard.lowWindowCount)
    }

    @Test
    fun anUnknownRequestedRateIsOff() {
        val guard = WifiGoodputGuard()
        guard.onSample(0, 0, mediaOffered = true, requestedKbps = 0)
        repeat(3) { index ->
            assertEquals(
                WifiGoodputGuard.Verdict.OK,
                guard.onSample((index + 1) * window, 0, mediaOffered = true, requestedKbps = 0),
            )
        }
        assertEquals(0, guard.lowWindowCount)
    }

    @Test
    fun theWindowRateIsTheDeliveredBytesOfTheWindow() {
        val guard = WifiGoodputGuard()
        guard.onSample(0, 0, mediaOffered = true, requestedKbps = 500)
        // 156,250 bytes over 5 s = 250 kbps.
        assertEquals(
            WifiGoodputGuard.Verdict.LOW,
            guard.onSample(window, 156_250, mediaOffered = true, requestedKbps = 500),
        )
        assertEquals(250L, guard.lastWindowKbps)
    }

    @Test
    fun resetClearsTheWindowAndTheLowCount() {
        val guard = WifiGoodputGuard()
        guard.onSample(0, 0, mediaOffered = true, requestedKbps = 500)
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(window, 0, mediaOffered = true, requestedKbps = 500))
        guard.reset()
        assertEquals(0, guard.lowWindowCount)
        assertEquals(0L, guard.lastWindowKbps)
        // The next sample starts a new window, so three further low windows are needed.
        assertEquals(WifiGoodputGuard.Verdict.OK, guard.onSample(2 * window, 0, mediaOffered = true, requestedKbps = 500))
        assertEquals(WifiGoodputGuard.Verdict.LOW, guard.onSample(3 * window, 0, mediaOffered = true, requestedKbps = 500))
    }
}
