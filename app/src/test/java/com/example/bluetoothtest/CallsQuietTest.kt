package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Calls page stays quiet (user feedback 2026-10-21): no narration, actionable states only, outcomes worth knowing. */
class CallsQuietTest {
    @Test
    fun aNormalCallEndSaysNothing() {
        for (message in listOf("Call ended", "Peer ended the call", "Call cancelled", CallRingTimeout.CALLER_CANCELLED_MESSAGE)) {
            assertFalse(message, CallEndText.worthTelling(CallEndText.kind(message)))
        }
        assertFalse("a missed call has its own notification", CallEndText.worthTelling(CallEndText.kind(CallRingTimeout.MISSED_MESSAGE)))
        assertFalse("a connect failure has its own Couldn't reach", CallEndText.worthTelling(CallEndText.kind("Call could not connect: x")))
    }

    @Test
    fun anOutcomeWorthKnowingIsTold() {
        for (message in listOf(CallRingTimeout.NO_ANSWER_MESSAGE, BUSY_DECLINE_MESSAGE, "Call declined", "Call link lost: Call peer stopped responding")) {
            assertTrue(message, CallEndText.worthTelling(CallEndText.kind(message)))
        }
    }

    @Test
    fun theStatusLineShowsOnlyAnActionableState() {
        assertEquals("", CallsQuiet.line(null))
        assertEquals("", CallsQuiet.line("  "))
        val actionable = "Can't receive calls over L2CAP right now. Switch to BLE GATT or restart Bluetooth."
        assertEquals(actionable, CallsQuiet.line(actionable))
    }

    @Test
    fun theEmptyStateExplainsOnlyAfterAWhile() {
        assertFalse(CallsQuiet.emptyHintVisible(0L))
        assertFalse(CallsQuiet.emptyHintVisible(CallsQuiet.EMPTY_HINT_AFTER_MS - 1))
        assertTrue(CallsQuiet.emptyHintVisible(CallsQuiet.EMPTY_HINT_AFTER_MS))
    }

    @Test
    fun aPhoneLeftOnNearbyStopsSpinningOnceTheSearchSettles() {
        val longWait = 30 * 60_000L
        assertTrue("today: spins for as long as discovery runs", CallsQuiet.indicatorVisible(true, true, longWait, settles = false))
        assertTrue("a fresh search still shows it", CallsQuiet.indicatorVisible(true, true, 0L, settles = true))
        assertFalse("a phone waiting on Nearby stops redrawing", CallsQuiet.indicatorVisible(true, true, longWait, settles = true))
        for (settles in listOf(false, true)) {
            assertFalse("not off Nearby", CallsQuiet.indicatorVisible(false, true, 0L, settles))
            assertFalse("not without discovery", CallsQuiet.indicatorVisible(true, false, 0L, settles))
        }
    }

    @Test
    fun onlyADebugLaunchCanKeepTheIndicatorSpinning() {
        try {
            DebugTunables.callsIndicatorSettles = false
            assertEquals("the control arm exists only in DEBUG", !BuildConfig.DEBUG, DebugTunables.callsIndicatorSettles)
        } finally {
            DebugTunables.callsIndicatorSettles = true
        }
        assertTrue("a person's launch settles", DebugTunables.callsIndicatorSettles)
    }
}
