package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The order in which a call's local media starts once the call is answered and the person policy has passed (JT2, switch
 * `test_call_camera_first`, promoted 2026-11-12, default on). Pure and JVM-tested; `MainActivity.startPrivateCallMedia` runs the steps in this order.
 *
 * Why: after Accept the call used to open its audio route inline on the UI thread (80-600 ms on the measured phones) before it
 * asked for the camera, so the camera's open, session and first capture (0.5-1.3 s, mostly the HAL) waited behind it. Camera
 * first starts that long chain first; the audio route open then runs on the audio command thread behind it, and the microphone
 * after the open (the voice-communication mode must be set before the capture opens: platform echo cancellation).
 *
 * Privacy: this order does not decide WHEN media may start. Nothing here runs before the answer and the block check: the
 * caller of [run] is only reached through the same gate as before ([CalleeMediaStart], `startLocalMedia`).
 */
internal object CallMediaStartOrder {
    enum class Step {
        /** Foreground service (camera and microphone types), call-focus hold. */
        FOREGROUND_SERVICE,

        /** The call's audio session object: focus, communication mode and route (the `call_audio_open` command). */
        AUDIO_SESSION,

        /** Push-to-talk start state and the volume-key stream. */
        MUTE_STATE,

        /** Camera on/off flags, proximity pause, the call-mode log line. */
        CAMERA_FLAGS,

        /** The local preview. */
        LOCAL_PREVIEW,

        /** Control labels (they read the audio route). */
        CONTROL_LABELS,

        /** The microphone encoder, created and started. */
        MICROPHONE_START,

        /** The call camera and its encoder (video calls only). */
        CAMERA_START,
    }

    /** The steps of one media start. A voice call has no camera step. [cameraFirst] false is the order before JT2. */
    fun plan(cameraFirst: Boolean, video: Boolean): List<Step> {
        val order = if (cameraFirst) {
            listOf(
                Step.FOREGROUND_SERVICE, Step.MUTE_STATE, Step.CAMERA_FLAGS, Step.LOCAL_PREVIEW, Step.CAMERA_START,
                Step.AUDIO_SESSION, Step.CONTROL_LABELS, Step.MICROPHONE_START,
            )
        } else {
            listOf(
                Step.FOREGROUND_SERVICE, Step.AUDIO_SESSION, Step.MUTE_STATE, Step.CAMERA_FLAGS, Step.LOCAL_PREVIEW,
                Step.CONTROL_LABELS, Step.MICROPHONE_START, Step.CAMERA_START,
            )
        }
        return if (video) order else order - Step.CAMERA_START
    }

    /** Runs the plan in order; [step] returns false to stop (a failure already ended the call). */
    inline fun run(cameraFirst: Boolean, video: Boolean, step: (Step) -> Boolean): Boolean {
        for (next in plan(cameraFirst, video)) if (!step(next)) return false
        return true
    }
}

/**
 * Whether the call's queued `call_audio_open` has finished, for the remote audio playback that must not create its AudioTrack
 * (usage VOICE_COMMUNICATION) before the communication mode is set. One open is awaited at a time (a call); no open expected
 * means nothing to wait for.
 */
internal class CallAudioOpenGate {
    @Volatile private var pending: CountDownLatch? = null

    /** A queued open begins: the returned latch is counted down when it finishes (or the session closes first). */
    fun expect(): CountDownLatch = CountDownLatch(1).also { pending = it }

    /** True at once when no open is expected or it finished; false when [timeoutMs] passed first (playback then goes on). */
    fun await(timeoutMs: Long): Boolean {
        val latch = pending ?: return true
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    companion object {
        val shared = CallAudioOpenGate()

        /** Longer than the slowest measured open (599 ms, M31 caller); a stuck open must not hold remote audio for ever. */
        const val WAIT_MS = 1_500L
    }
}
