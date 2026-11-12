package com.example.bluetoothtest

import java.util.UUID

/**
 * What a call's reader or writer does when its link fails (connection review A1, 2026-10-27).
 *
 * Before: a failure with the phase already ENDED was skipped as "the peer's hang-up being torn down". When the
 * phase was ENDED by this phone's own End/Cancel/Decline and the link was failing, nothing ever called finish():
 * the screen stayed on the call with the camera, microphone and foreground service held, and the endpoint never
 * re-advertised.
 */
internal enum class CallLinkErrorAction {
    /** The call was still live: the link failure ends it as a failure. */
    FAIL,

    /** This phone already ended the call (End/Cancel/Decline): finish it now as the ordinary local end. */
    FINISH_LOCAL_END,

    /** Nothing to do: no call, or the peer's end, which the reader finishes with the peer's own message. */
    NONE,
}

internal fun privateCallLinkErrorAction(
    callRunning: Boolean,
    phase: PrivateCallPhase?,
    callId: UUID?,
    localEndCallId: UUID?,
): CallLinkErrorAction = when {
    !callRunning -> CallLinkErrorAction.NONE
    phase != PrivateCallPhase.ENDED -> CallLinkErrorAction.FAIL
    callId != null && callId == localEndCallId -> CallLinkErrorAction.FINISH_LOCAL_END
    else -> CallLinkErrorAction.NONE
}

/**
 * What the carrier set's end-of-session report does (package SW1, END): the last carrier ending is reported without an error when the
 * set's own liveness or a clean EOF ended it. A call this phone ended itself (End/Cancel/Decline) always finishes as "Call ended",
 * also then; before, a Wi-Fi EOF racing the local hang-up showed "Call link lost" to the user who pressed End.
 */
internal enum class EndSessionAction {
    /** A link error was recorded: the ordinary link-error decision ([privateCallLinkErrorAction]). */
    LINK_ERROR,

    /** No error and no local end: the call failed with the carrier's loss message. */
    FAIL,

    /** This phone ended the call: finish it as the ordinary local end. */
    FINISH_LOCAL_END,
}

internal const val CALL_ENDED_MESSAGE = "Call ended"

internal fun privateCallEndSessionAction(
    hasLossError: Boolean,
    callRunning: Boolean,
    phase: PrivateCallPhase?,
    callId: UUID?,
    localEndCallId: UUID?,
): EndSessionAction = when {
    privateCallLinkErrorAction(callRunning, phase, callId, localEndCallId) == CallLinkErrorAction.FINISH_LOCAL_END ->
        EndSessionAction.FINISH_LOCAL_END
    hasLossError -> EndSessionAction.LINK_ERROR
    else -> EndSessionAction.FAIL
}

/** What Cancel does in each phase (review A7: the phase is re-checked under the call lock). */
internal enum class CallCancelStep { CANCEL, HANG_UP, IGNORE }

internal fun privateCallCancelStep(phase: PrivateCallPhase?): CallCancelStep = when (phase) {
    PrivateCallPhase.OUTGOING_INVITE, PrivateCallPhase.OUTGOING_RINGING -> CallCancelStep.CANCEL
    // The callee answered between the tap and the lock: Cancel becomes End, instead of an invalid transition.
    PrivateCallPhase.NEGOTIATING_MEDIA, PrivateCallPhase.ACTIVE -> CallCancelStep.HANG_UP
    else -> CallCancelStep.IGNORE
}

/**
 * Review A5: a Cancel while no writer runs (hop handshake, relayed SETUP, end-to-end handshake) can never be sent;
 * the call is finished at once and its link closed, which also unblocks the handshake thread.
 */
internal fun privateCallCancelFinishesNow(callRunning: Boolean, writerAlive: Boolean): Boolean =
    !callRunning || !writerAlive

/**
 * Review A2: an outgoing link may start the call only while the endpoint runs and the attempt is still dialling. A link
 * that completes after close() or a Cancel, or after a failed attempt cleared callRunning before its retry, is closed.
 */
internal fun privateCallOutgoingEstablishAllowed(running: Boolean, phase: PrivateCallPhase?): Boolean =
    running && phase == PrivateCallPhase.OUTGOING_INVITE
