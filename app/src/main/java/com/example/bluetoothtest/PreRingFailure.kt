package com.example.bluetoothtest

/**
 * An incoming link that failed before the phone rang (call audit 2026-11-10, CALL-02): a caller's stalled first attempt
 * that it will retry, a verification scan, a relay hop that gave up, a silent stranger closed at the handshake deadline.
 * The ordinary "call ended" path moved the user to Nearby and said "The incoming call couldn't connect" about a call
 * they never saw.
 *
 * Experimental `test_call_quiet_pre_ring_failure` ([DebugTunables.callQuietPreRingFailure], default off): such an end
 * changes no screen and says nothing; the endpoint is rebuilt with the same pause, as after a relay duty.
 */
internal object PreRingFailure {
    enum class Presentation {
        /** Today's path: the call screens close, the user lands on Nearby and the outcome is said. */
        CALL_ENDED,

        /** Nothing is shown; the endpoint is rebuilt for the screen the user is on. */
        QUIET,
    }

    /**
     * [phase]: the last call phase the screen was given for this endpoint, null when none (nothing rang, nothing was
     * dialled). [idleScreen]: the user is on a screen where an idle endpoint waits for calls (Nearby, Home, Live
     * browsing) and is not dialling, so a quiet rebuild brings the endpoint back; anywhere else today's path stays.
     */
    fun presentation(kind: CallEndText.Kind, phase: PrivateCallPhase?, idleScreen: Boolean, quietOn: Boolean): Presentation =
        if (quietOn && kind == CallEndText.Kind.INCOMING_FAILED && phase == null && idleScreen) {
            Presentation.QUIET
        } else {
            Presentation.CALL_ENDED
        }

    /** The one log line of a quiet end. [message] is the endpoint's end message, already logged as "Private call ended". */
    fun line(message: String, screen: String): String {
        val reason = message.substringAfter(": ", "").ifBlank { message }.trim().replace(Regex("[^A-Za-z0-9_.-]+"), "_").take(80)
        return "call_pre_ring_failure: quiet=true | reason=$reason | screen=${screen.lowercase()}"
    }
}
