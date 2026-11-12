package com.example.bluetoothtest

import android.content.pm.ServiceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice-call hardening for the promotion (IntercomCall.kt, ActiveVideoSessionService, CallRinger.kt). */
class VoiceCallPromotionTest {
    @Test
    fun aPeerWithoutVoiceCallsIsDetectedFromABareVideoFrame() {
        val layout = CallLayoutState(CallType.VOICE)
        assertFalse(layout.peerLacksVoiceSupport)
        assertTrue("its first frame turns the layout to video", layout.onRemoteFrame())
        assertTrue(layout.peerLacksVoiceSupport)
        assertTrue("said once", layout.consumePeerLacksVoiceNotice())
        assertFalse(layout.consumePeerLacksVoiceNotice())
    }

    @Test
    fun aPeerThatSignalledItsCameraIsNeverReportedAsLackingVoiceCalls() {
        val layout = CallLayoutState(CallType.VOICE)
        layout.onRemoteCameraSignal(enabled = false) // an app with voice calls sends Camera off after Accept
        layout.onRemoteCameraSignal(enabled = true) // then adds video later
        assertFalse(layout.onRemoteFrame())
        assertFalse(layout.peerLacksVoiceSupport)
        assertFalse(layout.consumePeerLacksVoiceNotice())
    }

    @Test
    fun aVideoCallNeverShowsTheNotice() {
        val layout = CallLayoutState(CallType.VIDEO)
        assertFalse(layout.onRemoteFrame())
        assertFalse(layout.peerLacksVoiceSupport)
    }

    @Test
    fun audioRouteFollowsTheCallTypeUntilTheUserChoosesOne() {
        val voice = CallAudioRoutePolicy(CallType.VOICE)
        assertFalse("a voice call starts on the earpiece", voice.initialSpeakerOn)
        assertEquals(true, voice.speakerForLayout(CallLayout.VIDEO)) // video added: loudspeaker
        assertEquals(false, voice.speakerForLayout(CallLayout.VOICE)) // video removed: back to the earpiece
        voice.onUserChoice()
        assertNull("an explicit choice is never overridden", voice.speakerForLayout(CallLayout.VIDEO))
        assertTrue("a video call starts on the loudspeaker", CallAudioRoutePolicy(CallType.VIDEO).initialSpeakerOn)
    }

    @Test
    fun aVoiceCallRunsTheServiceWithoutTheCameraTypeAndAddingVideoWidensIt() {
        val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val voice = ActiveVideoSessionService.requestedTypes(usesCamera = false, usesMicrophone = true)
        assertEquals(device or microphone, voice)
        assertEquals(0, voice and camera)
        val video = ActiveVideoSessionService.requestedTypes(usesCamera = true, usesMicrophone = true)
        assertEquals(device or microphone or camera, video)
        // Ringing holds neither media type: the microphone starts with the answer.
        assertEquals(device, ActiveVideoSessionService.requestedTypes(usesCamera = false, usesMicrophone = false))
    }

    @Test
    fun ringbackPlaysAtTheEarForVoiceAndFromTheSpeakerForVideo() {
        assertEquals(AudioManager.STREAM_VOICE_CALL, RingbackPolicy.stream(CallType.VOICE))
        assertEquals(AudioManager.STREAM_MUSIC, RingbackPolicy.stream(CallType.VIDEO))
        assertEquals(AudioManager.STREAM_MUSIC, RingbackPolicy.stream(null))
    }

    @Test
    fun noRingbackWhileABlockedCallWaitsForItsOrdinaryEnd() {
        assertTrue(RingbackPolicy.plays(enabled = true, phase = PrivateCallPhase.OUTGOING_RINGING, blockedEndScheduled = false))
        assertFalse(RingbackPolicy.plays(enabled = true, phase = PrivateCallPhase.OUTGOING_RINGING, blockedEndScheduled = true))
        assertFalse(RingbackPolicy.plays(enabled = false, phase = PrivateCallPhase.OUTGOING_RINGING, blockedEndScheduled = false))
        assertFalse(RingbackPolicy.plays(enabled = true, phase = PrivateCallPhase.ACTIVE, blockedEndScheduled = false))
        assertFalse(RingbackPolicy.plays(enabled = true, phase = null, blockedEndScheduled = false))
    }

    @Test
    fun aMuteHeldByAnotherCallSurvivesAHoldToTalkPress() {
        // The activity maps talking to onUserMuted(!talking): while another call holds the microphone the
        // effective mute stays on, and releasing mutes the user again.
        val hold = CallFocusHold()
        hold.onUserMuted(true)
        hold.onAction(FocusAction.HOLD)
        assertTrue("pressing to talk during a cellular call keeps the microphone closed", hold.onUserMuted(false))
        assertEquals(false, hold.onAction(FocusAction.RESUME)) // the call ended while still holding: the microphone opens
        assertTrue(hold.onUserMuted(true))
    }
}
