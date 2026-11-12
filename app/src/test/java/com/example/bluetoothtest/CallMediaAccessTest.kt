package com.example.bluetoothtest

import com.example.bluetoothtest.CallMediaAccess.Access
import com.example.bluetoothtest.CallMediaAccess.Need
import com.example.bluetoothtest.CallMediaAccess.Step
import com.example.bluetoothtest.CallMediaAccess.Use
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * UI audit 2026-11-10, UI-01: a call that needs the camera or the microphone after the user refused it. Before, the tap
 * was dropped (Accept on a video call did nothing and the phone kept ringing).
 */
class CallMediaAccessTest {
    private val allowed = Access(granted = true)
    private val refusedOnce = Access(granted = false, canAskAgain = true)
    private val refusedForGood = Access(granted = false, canAskAgain = false)

    @After
    fun restoreTheSwitch() {
        DebugTunables.callVoiceWhenCameraRefused = true
    }

    private fun decide(
        use: Use,
        video: Boolean = true,
        camera: Access = allowed,
        microphone: Access = allowed,
        nearby: Access = allowed,
        voiceCallsOn: Boolean = true,
        voiceWhenCameraRefused: Boolean,
    ) = CallMediaAccess.decide(use, video, camera, microphone, nearby, voiceCallsOn, voiceWhenCameraRefused)

    @Test
    fun asShippedAcceptingAVideoCallWithTheCameraRefusedAnswersAsVoice() {
        // No launch extra: the switch as a user's phone has it (design decision 2026-11-10: on).
        val shipped = DebugTunables.callVoiceWhenCameraRefused
        for (camera in listOf(refusedOnce, refusedForGood)) {
            assertEquals(Step.VOICE_INSTEAD, decide(Use.ANSWER, camera = camera, voiceWhenCameraRefused = shipped).step)
            assertEquals(Step.VOICE_INSTEAD, decide(Use.PLACE, camera = camera, voiceWhenCameraRefused = shipped).step)
        }
    }

    @Test
    fun aDebugLaunchCanSetTheControlArm() {
        // A release build ignores launch extras (the setter keeps the default), so this DEBUG arm exists only there.
        assumeTrue("DEBUG launch arm", BuildConfig.DEBUG)
        DebugTunables.callVoiceWhenCameraRefused = false
        val control = decide(Use.ANSWER, camera = refusedOnce, voiceWhenCameraRefused = DebugTunables.callVoiceWhenCameraRefused)
        assertEquals(Step.TELL, control.step)
        assertEquals(setOf(Need.CAMERA), control.missing)
    }

    @Test
    fun withEverythingAllowedTheCallGoesOnAsAsked() {
        for (use in Use.values()) {
            for (switch in listOf(false, true)) {
                val outcome = decide(use, voiceWhenCameraRefused = switch)
                assertEquals(Step.PROCEED, outcome.step)
                assertTrue(outcome.missing.isEmpty())
                assertFalse(outcome.openSettings)
            }
        }
    }

    @Test
    fun aVoiceCallNeverNeedsTheCamera() {
        for (use in listOf(Use.ANSWER, Use.PLACE)) {
            val outcome = decide(use, video = false, camera = refusedForGood, voiceWhenCameraRefused = true)
            assertEquals(Step.PROCEED, outcome.step)
            assertTrue(outcome.missing.isEmpty())
        }
    }

    @Test
    fun inTheControlArmAcceptingAVideoCallWithTheCameraRefusedIsNeverSilent() {
        // Control arm (switch off): the call keeps ringing, and the user is told what is missing.
        val first = decide(Use.ANSWER, camera = refusedOnce, voiceWhenCameraRefused = false)
        assertEquals(Step.TELL, first.step)
        assertEquals(setOf(Need.CAMERA), first.missing)
        assertFalse("the system asks again on the next tap", first.openSettings)

        // "Don't ask again": the system shows no prompt any more, so the message carries the way to the app's settings.
        val forGood = decide(Use.ANSWER, camera = refusedForGood, voiceWhenCameraRefused = false)
        assertEquals(Step.TELL, forGood.step)
        assertEquals(setOf(Need.CAMERA), forGood.missing)
        assertTrue(forGood.openSettings)
    }

    @Test
    fun aRefusedCameraAnswersAsVoice() {
        for (camera in listOf(refusedOnce, refusedForGood)) {
            val outcome = decide(Use.ANSWER, camera = camera, voiceWhenCameraRefused = true)
            assertEquals(Step.VOICE_INSTEAD, outcome.step)
            assertEquals(setOf(Need.CAMERA), outcome.missing)
            assertFalse("the notice sits beside a call that goes on: it carries no action", outcome.openSettings)
        }
        // The voice answer is the existing camera-off answer, which every peer accepts whatever this phone's voice-call switch.
        assertEquals(
            Step.VOICE_INSTEAD,
            decide(Use.ANSWER, camera = refusedOnce, voiceCallsOn = false, voiceWhenCameraRefused = true).step,
        )
    }

    @Test
    fun aRefusedCameraPlacesAVoiceCall() {
        val outcome = decide(Use.PLACE, camera = refusedForGood, voiceWhenCameraRefused = true)
        assertEquals(Step.VOICE_INSTEAD, outcome.step)
        assertEquals(setOf(Need.CAMERA), outcome.missing)

        // A phone whose voice calls are off cannot place one: the refusal is said instead, with the way to settings.
        val noVoice = decide(Use.PLACE, camera = refusedForGood, voiceCallsOn = false, voiceWhenCameraRefused = true)
        assertEquals(Step.TELL, noVoice.step)
        assertEquals(setOf(Need.CAMERA), noVoice.missing)
        assertTrue(noVoice.openSettings)

        // Control arm: only the message.
        assertEquals(Step.TELL, decide(Use.PLACE, camera = refusedOnce, voiceWhenCameraRefused = false).step)
    }

    @Test
    fun aRefusedMicrophoneIsOnlyEverSaid() {
        for (use in Use.values()) {
            for (switch in listOf(false, true)) {
                val microphoneOnly = decide(use, microphone = refusedOnce, voiceWhenCameraRefused = switch)
                assertEquals(Step.TELL, microphoneOnly.step)
                assertEquals(setOf(Need.MICROPHONE), microphoneOnly.missing)
                assertFalse(microphoneOnly.openSettings)

                val both = decide(use, camera = refusedOnce, microphone = refusedForGood, voiceWhenCameraRefused = switch)
                assertEquals(Step.TELL, both.step)
                assertEquals(setOf(Need.CAMERA, Need.MICROPHONE), both.missing)
                assertTrue("one of them is no longer asked for", both.openSettings)
            }
        }
        // A voice call with the microphone refused names the microphone only, whatever the camera's state.
        val voice = decide(Use.ANSWER, video = false, camera = refusedForGood, microphone = refusedOnce, voiceWhenCameraRefused = true)
        assertEquals(Step.TELL, voice.step)
        assertEquals(setOf(Need.MICROPHONE), voice.missing)
        assertFalse(voice.openSettings)
    }

    @Test
    fun turningTheCameraOnInACallHasNoVoiceForm() {
        for (switch in listOf(false, true)) {
            val outcome = decide(Use.CAMERA_ON, camera = refusedForGood, voiceWhenCameraRefused = switch)
            assertEquals(Step.TELL, outcome.step)
            assertEquals(setOf(Need.CAMERA), outcome.missing)
            assertTrue(outcome.openSettings)
        }
    }

    @Test
    fun aRefusedNearbyDevicesPermissionStopsTheVoiceFallbackToo() {
        // Without Bluetooth access no call can be made at all, so nothing is answered or placed as voice.
        val outcome = decide(Use.ANSWER, camera = refusedOnce, nearby = refusedForGood, voiceWhenCameraRefused = true)
        assertEquals(Step.TELL, outcome.step)
        assertEquals(setOf(Need.NEARBY, Need.CAMERA), outcome.missing)
        assertTrue(outcome.openSettings)
    }
}
