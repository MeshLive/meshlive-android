package com.example.bluetoothtest

import com.example.bluetoothtest.PreRingFailure.Presentation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Call audit 2026-11-10, CALL-02: an incoming link that failed before the phone rang ran the whole "call ended" path
 * (the user was moved to Nearby and told "The incoming call couldn't connect" about a call they never saw).
 */
class PreRingFailureTest {
    /** What the endpoint reports when an incoming handshake fails (a caller's stalled first attempt, a silent stranger). */
    private val failedHandshake = CallEndText.kind("Incoming call failed: EOFException")

    private fun present(
        kind: CallEndText.Kind = failedHandshake,
        phase: PrivateCallPhase? = null,
        idleScreen: Boolean = true,
        quietOn: Boolean = true,
    ) = PreRingFailure.presentation(kind, phase, idleScreen, quietOn)

    @Test
    fun aLinkThatFailedBeforeItRangDisturbsNobody() {
        assertEquals(Presentation.QUIET, present())
    }

    @Test
    fun withTheSwitchOffTheEndIsPresentedAsToday() {
        assertEquals(Presentation.CALL_ENDED, present(quietOn = false))
    }

    @Test
    fun aCallTheUserSawEndsAsToday() {
        // Once the phone rang (or the call went further) the user saw it: the end is shown.
        for (phase in PrivateCallPhase.values()) {
            assertEquals(phase.name, Presentation.CALL_ENDED, present(phase = phase))
        }
    }

    @Test
    fun onlyAFailedIncomingLinkIsQuiet() {
        // Every other end with no phase on screen (the user's own dial that could not connect, a missed call...) stays visible.
        for (kind in CallEndText.Kind.values()) {
            val expected = if (kind == CallEndText.Kind.INCOMING_FAILED) Presentation.QUIET else Presentation.CALL_ENDED
            assertEquals(kind.name, expected, present(kind = kind))
        }
        assertEquals(Presentation.CALL_ENDED, present(kind = CallEndText.kind("Could not connect: timeout")))
    }

    @Test
    fun aUserWhoIsDiallingOrOnAScreenWithoutAnIdleEndpointGetsTodaysPath() {
        // The quiet rebuild exists only where an idle endpoint waits (Nearby, Home, Live browsing) and the user is not
        // dialling; anywhere else the ordinary end still restarts the endpoint.
        assertEquals(Presentation.CALL_ENDED, present(idleScreen = false))
    }
}
