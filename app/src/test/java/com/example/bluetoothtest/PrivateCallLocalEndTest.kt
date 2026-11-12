package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallLocalEndTest {
    private val call = UUID.randomUUID()

    @Test
    fun hangUpOnAFailingLinkFinishesTheCall() {
        // The user pressed End (phase ENDED by this phone), then the HANG_UP write failed: the call must finish.
        val ended = PrivateCallState(call, PrivateCallRole.CALLER, PrivateCallPhase.ACTIVE)
            .transition(PrivateCallEvent.LOCAL_HANG_UP)
        assertEquals(
            CallLinkErrorAction.FINISH_LOCAL_END,
            privateCallLinkErrorAction(callRunning = true, phase = ended.phase, callId = call, localEndCallId = call),
        )
    }

    @Test
    fun peerHangUpStaysWithTheReader() {
        // The peer's HANG_UP ended the phase; the reader finishes with "Peer ended the call", the writer must not race it.
        assertEquals(
            CallLinkErrorAction.NONE,
            privateCallLinkErrorAction(true, PrivateCallPhase.ENDED, call, localEndCallId = null),
        )
        // A local end of an older call does not count for this one.
        assertEquals(
            CallLinkErrorAction.NONE,
            privateCallLinkErrorAction(true, PrivateCallPhase.ENDED, call, localEndCallId = UUID.randomUUID()),
        )
    }

    @Test
    fun liveCallLinkFailureStillFails() {
        assertEquals(CallLinkErrorAction.FAIL, privateCallLinkErrorAction(true, PrivateCallPhase.ACTIVE, call, null))
        assertEquals(CallLinkErrorAction.NONE, privateCallLinkErrorAction(false, PrivateCallPhase.ACTIVE, call, call))
    }

    @Test
    fun aCarrierEofRacingMyHangUpStillEndsAsCallEnded() {
        // The user pressed End (phase ENDED by this phone); the last carrier then ended with no loss error recorded.
        val ended = PrivateCallState(call, PrivateCallRole.CALLER, PrivateCallPhase.ACTIVE).transition(PrivateCallEvent.LOCAL_HANG_UP)
        assertEquals(
            EndSessionAction.FINISH_LOCAL_END,
            privateCallEndSessionAction(hasLossError = false, callRunning = true, phase = ended.phase, callId = call, localEndCallId = call),
        )
        // The same with a recorded error: still the ordinary local end.
        assertEquals(
            EndSessionAction.FINISH_LOCAL_END,
            privateCallEndSessionAction(true, true, ended.phase, call, call),
        )
        assertEquals("Call ended", CALL_ENDED_MESSAGE)
    }

    @Test
    fun aLostLastCarrierWithoutALocalEndStillFailsAsCallLinkLost() {
        assertEquals(EndSessionAction.FAIL, privateCallEndSessionAction(false, true, PrivateCallPhase.ACTIVE, call, null))
        assertEquals(EndSessionAction.FAIL, privateCallEndSessionAction(false, true, PrivateCallPhase.ENDED, call, null))
        assertEquals(EndSessionAction.FAIL, privateCallEndSessionAction(false, true, PrivateCallPhase.ENDED, call, UUID.randomUUID()))
        assertEquals(EndSessionAction.LINK_ERROR, privateCallEndSessionAction(true, true, PrivateCallPhase.ACTIVE, call, null))
    }

    @Test
    fun cancelAfterTheCalleeAnsweredHangsUp() {
        assertEquals(CallCancelStep.CANCEL, privateCallCancelStep(PrivateCallPhase.OUTGOING_INVITE))
        assertEquals(CallCancelStep.CANCEL, privateCallCancelStep(PrivateCallPhase.OUTGOING_RINGING))
        assertEquals(CallCancelStep.HANG_UP, privateCallCancelStep(PrivateCallPhase.NEGOTIATING_MEDIA))
        assertEquals(CallCancelStep.HANG_UP, privateCallCancelStep(PrivateCallPhase.ACTIVE))
        assertEquals(CallCancelStep.IGNORE, privateCallCancelStep(PrivateCallPhase.ENDED))
        assertEquals(CallCancelStep.IGNORE, privateCallCancelStep(null))
    }

    @Test
    fun cancelDuringTheHandshakeFinishesAtOnce() {
        assertTrue(privateCallCancelFinishesNow(callRunning = true, writerAlive = false))
        assertTrue(privateCallCancelFinishesNow(callRunning = false, writerAlive = false))
        assertFalse(privateCallCancelFinishesNow(callRunning = true, writerAlive = true))
    }

    @Test
    fun linkCompletingAfterCancelDoesNotReviveTheCall() {
        assertTrue(privateCallOutgoingEstablishAllowed(running = true, phase = PrivateCallPhase.OUTGOING_INVITE))
        assertFalse(privateCallOutgoingEstablishAllowed(running = true, phase = PrivateCallPhase.ENDED))
        assertFalse(privateCallOutgoingEstablishAllowed(running = false, phase = PrivateCallPhase.OUTGOING_INVITE))
        assertFalse(privateCallOutgoingEstablishAllowed(running = true, phase = null))
    }
}
