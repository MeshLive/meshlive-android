package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialIntentsTest {
    private var now = 0L
    private val intents = DialIntents(nowMs = { now }, tapWindowMs = 29_000)

    @Test
    fun oneDialsEndKeepsTheSubscribeSessionForAnotherDialInFlight() {
        intents.beginDial()   // the source dials a Wi-Fi-only viewer
        intents.beginDial()   // and the user calls a Wi-Fi-only person meanwhile
        intents.endDial()
        assertTrue(intents.active())
        intents.endDial()
        assertFalse(intents.active())
    }

    @Test
    fun aDialsEndKeepsAnotherPersonsPendingTap() {
        intents.noteTap()     // tap on person A
        intents.beginDial()   // A's dial starts and takes A's tap
        intents.noteTap()     // tap on person B before A's dial ends
        intents.endDial()     // A's dial ends
        assertTrue(intents.active())   // B's tap still wants the session (A8 review P3-2)
        intents.beginDial()
        intents.endDial()
        assertFalse(intents.active())
    }

    @Test
    fun aTapWhoseDialNeverStartsExpires() {
        intents.noteTap()
        now = 28_999
        assertTrue(intents.active())
        now = 29_000
        assertFalse(intents.active())
    }

    @Test
    fun anUnmatchedEndChangesNothing() {
        intents.endDial()
        intents.noteTap()
        assertTrue(intents.active())
    }
}
