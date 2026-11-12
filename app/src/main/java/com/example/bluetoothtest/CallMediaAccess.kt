package com.example.bluetoothtest

/**
 * A call that needs the camera or the microphone after the user was asked for them (UI audit 2026-11-10, UI-01).
 * Before, a refusal dropped the tap: Accept on a video call did nothing and the phone kept ringing, and after "Don't ask
 * again" the system showed no prompt either. Now every refusal is said, naming what is missing, with the way to the
 * app's settings when the system no longer asks.
 *
 * `test_call_voice_when_camera_refused` ([DebugTunables.callVoiceWhenCameraRefused], default on by the owner's decision
 * of 2026-11-10; false is the control arm): a video call answered or placed with only the camera refused goes on as a
 * voice call, with a notice. The answer is the existing camera-off answer (the one used at critical heat), so nothing
 * changes on the wire.
 */
internal object CallMediaAccess {
    /** What the user tapped. */
    enum class Use {
        /** Accept on a ringing call. */
        ANSWER,

        /** A call the user places. */
        PLACE,

        /** "Camera on" in a call that is already running: it has no voice form, the call simply stays as it is. */
        CAMERA_ON,
    }

    enum class Step {
        /** Everything the call needs is allowed: go on as asked. */
        PROCEED,

        /** Only the camera is refused (and the control arm is not set): go on as a voice call and say so. */
        VOICE_INSTEAD,

        /** Do not go on; say what is missing. */
        TELL,
    }

    /** What the message names, in the order it is said. */
    enum class Need { NEARBY, CAMERA, MICROPHONE }

    /**
     * One permission group after the request returned. [canAskAgain] is false when the system shows no prompt for it
     * any more (it reports no rationale after a refusal: denied for good, or the prompt was dismissed).
     */
    data class Access(val granted: Boolean, val canAskAgain: Boolean = true)

    /** [openSettings]: the message offers the app's system settings page, because a prompt will not come. */
    data class Outcome(val step: Step, val missing: Set<Need> = emptySet(), val openSettings: Boolean = false)

    /**
     * [video]: the call (or the action) needs the camera. [nearby]: Bluetooth access (location before Android 12).
     * [voiceCallsOn]: this phone can place voice calls; answering without the camera needs no switch.
     */
    fun decide(
        use: Use,
        video: Boolean,
        camera: Access,
        microphone: Access,
        nearby: Access,
        voiceCallsOn: Boolean,
        voiceWhenCameraRefused: Boolean,
    ): Outcome {
        val refused = buildList {
            if (!nearby.granted) add(Need.NEARBY to nearby)
            if (video && !camera.granted) add(Need.CAMERA to camera)
            if (!microphone.granted) add(Need.MICROPHONE to microphone)
        }
        if (refused.isEmpty()) return Outcome(Step.PROCEED)
        val missing: Set<Need> = refused.mapTo(linkedSetOf()) { it.first }
        val openSettings = refused.any { !it.second.canAskAgain }
        val voiceForm = when (use) {
            Use.ANSWER -> true
            Use.PLACE -> voiceCallsOn
            Use.CAMERA_ON -> false
        }
        // The voice notice is shown beside a call that goes on, so it carries no action.
        if (voiceWhenCameraRefused && voiceForm && missing == setOf(Need.CAMERA)) return Outcome(Step.VOICE_INSTEAD, missing)
        return Outcome(Step.TELL, missing, openSettings)
    }

    /** The one log line of a refusal (no identifiers). */
    fun line(use: Use, video: Boolean, outcome: Outcome, switchOn: Boolean): String =
        "call_media_refused: use=${use.name.lowercase()} | video=$video | " +
            "missing=${outcome.missing.joinToString("+") { it.name.lowercase() }} | step=${outcome.step.name.lowercase()} | " +
            "openSettings=${outcome.openSettings} | voiceWhenCameraRefused=$switchOn"
}
