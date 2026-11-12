package com.example.bluetoothtest

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntercomCallTest {
    // ---- Feature switches -------------------------------------------------------------------

    @Test
    fun benchmarkLaunchUsesOnlyItsExtrasAndIgnoresStoredChoices() {
        val stored = { _: String -> true }
        val off = IntercomFeatureSettings.resolve(true, { false }, stored = stored)
        assertEquals(IntercomFeatureSettings(), off)
        val on = IntercomFeatureSettings.resolve(
            true,
            { it == IntercomFeatureSettings.EXTRA_VOICE_CALLS || it == IntercomFeatureSettings.EXTRA_PTT_MODE },
            stored = { false },
        )
        assertTrue(on.voiceCalls)
        assertFalse(on.pushToTalk)
        assertTrue(on.pttMode)
        assertTrue("PTT mode needs the hold button", on.pushToTalkButton)
    }

    @Test
    fun userSessionUsesTheShippedVoiceCallConstant() {
        // Voice calls have no Settings switch since 2026-10-17: nothing stored reaches them, and extras only count in a
        // benchmark launch.
        val on = IntercomFeatureSettings.resolve(false, { true }) { true }
        assertEquals(CallFeatureDefaults.VOICE_CALLS, on.voiceCalls)
        val off = IntercomFeatureSettings.resolve(false, { true }) { false }
        assertEquals(CallFeatureDefaults.VOICE_CALLS, off.voiceCalls)
        assertEquals(CallFeatureDefaults.VOICE_CALLS, IntercomFeatureSettings.resolve(false, { true }) { null }.voiceCalls)
    }

    @Test
    fun theTalkButtonAndPttModeAreLaunchExtrasOnly() {
        // DECISIONS: push-to-talk is not a mode. A stored value (the old Advanced setting) or any user
        // session never enables them.
        val stored = { key: String ->
            if (key == "experimental_push_to_talk" || key == "experimental_ptt_mode") true else null
        }
        val session = IntercomFeatureSettings.resolve(false, { true }, stored)
        assertFalse(session.pushToTalkButton)
        assertFalse(session.pttMode)
        val benchmark = IntercomFeatureSettings.resolve(true, { it == IntercomFeatureSettings.EXTRA_PUSH_TO_TALK }, { null })
        assertTrue(benchmark.pushToTalkButton)
        assertFalse(benchmark.pttMode)
    }

    @Test
    fun theDataClassDefaultsAreOff() {
        val defaults = IntercomFeatureSettings()
        assertFalse(defaults.voiceCalls)
        assertFalse(defaults.pushToTalkButton)
        assertFalse(defaults.pttMode)
    }

    // ---- Voice-call negotiation ---------------------------------------------------------------

    @Test
    fun voiceCallerSendsInviteThenCameraOff() {
        val caller = VoiceCallNegotiation.outgoing(enabled = true, requested = CallType.VOICE)
        assertEquals(CallType.VOICE, caller.localType)
        assertEquals(listOf(PrivateCallSignal.INVITE, PrivateCallSignal.CAMERA_OFF), caller.inviteSignals())
    }

    @Test
    fun videoCallerAndDisabledFeatureSendTheUnchangedInvite() {
        assertEquals(
            listOf(PrivateCallSignal.INVITE),
            VoiceCallNegotiation.outgoing(enabled = true, requested = CallType.VIDEO).inviteSignals(),
        )
        val disabled = VoiceCallNegotiation.outgoing(enabled = false, requested = CallType.VOICE)
        assertEquals(CallType.VIDEO, disabled.localType)
        assertEquals(listOf(PrivateCallSignal.INVITE), disabled.inviteSignals())
    }

    @Test
    fun cameraOffWhileRingingIsAVoiceOfferAndTheAnswerTurnsVideoOff() {
        val callee = VoiceCallNegotiation.incoming(enabled = true)
        assertEquals(CallType.VIDEO, callee.localType)
        assertTrue(callee.onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = false))
        assertTrue(callee.peerOfferedVoice)
        assertEquals(CallType.VOICE, callee.localType)
        assertFalse("A repeated signal changes nothing",
            callee.onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = false))
        assertEquals(listOf(PrivateCallSignal.ACCEPT, PrivateCallSignal.CAMERA_OFF), callee.acceptSignals())
    }

    @Test
    fun aCalleeAtCriticalHeatAnswersAVideoCallAsVoice() {
        // Calls at critical heat (DECISIONS 2026-11-06): the answer is the voice answer, whatever this phone's switch.
        for (voiceCalls in listOf(true, false)) {
            val callee = VoiceCallNegotiation.incoming(enabled = voiceCalls)
            assertEquals(CallType.VIDEO, callee.localType)
            callee.answerWithoutVideo()
            assertEquals(CallType.VOICE, callee.localType)
            assertEquals(listOf(PrivateCallSignal.ACCEPT, PrivateCallSignal.CAMERA_OFF), callee.acceptSignals())
            callee.onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = true)
            assertEquals("The caller's camera state does not bring this camera back", CallType.VOICE, callee.localType)
        }
    }

    @Test
    fun onlyACalleeCanAnswerWithoutVideo() {
        val caller = VoiceCallNegotiation.outgoing(enabled = true, requested = CallType.VIDEO)
        assertThrows(IllegalStateException::class.java) { caller.answerWithoutVideo() }
    }

    @Test
    fun cameraOnWhileRingingWithdrawsTheVoiceOffer() {
        val callee = VoiceCallNegotiation.incoming(enabled = true)
        callee.onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = false)
        assertTrue(callee.onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = true))
        assertEquals(CallType.VIDEO, callee.localType)
        assertEquals(listOf(PrivateCallSignal.ACCEPT), callee.acceptSignals())
    }

    @Test
    fun cameraSignalsAfterAnsweringAreOrdinaryCameraState() {
        val callee = VoiceCallNegotiation.incoming(enabled = true)
        assertFalse(callee.onRemoteCameraSignal(PrivateCallPhase.NEGOTIATING_MEDIA, enabled = false))
        assertFalse(callee.onRemoteCameraSignal(PrivateCallPhase.ACTIVE, enabled = false))
        assertEquals(CallType.VIDEO, callee.localType)
    }

    @Test
    fun calleeWithTheFeatureOffTreatsAVoiceOfferAsVideoWithTheCallerVideoOff() {
        val callee = VoiceCallNegotiation.incoming(enabled = false)
        assertFalse(callee.onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = false))
        assertEquals(CallType.VIDEO, callee.localType)
        assertEquals(listOf(PrivateCallSignal.ACCEPT), callee.acceptSignals())
    }

    @Test
    fun rolesCannotUseTheOtherSidesSignals() {
        assertThrows(IllegalStateException::class.java) {
            VoiceCallNegotiation.outgoing(true, CallType.VOICE).acceptSignals()
        }
        assertThrows(IllegalStateException::class.java) { VoiceCallNegotiation.incoming(true).inviteSignals() }
        assertFalse(
            "A caller never treats camera state as an offer",
            VoiceCallNegotiation.outgoing(true, CallType.VIDEO)
                .onRemoteCameraSignal(PrivateCallPhase.INCOMING_RINGING, enabled = false),
        )
    }

    /**
     * Both ends over the unchanged BMC2 codec and call state machine, for every combination of the
     * switch: no new wire value is produced, the ordered sequence stays valid for the existing state
     * machine, and the call types match the documented compatibility rule.
     */
    @Test
    fun voiceNegotiationUsesOnlyExistingWireValuesInEveryCombination() {
        for (callerOn in listOf(false, true)) {
            for (calleeOn in listOf(false, true)) {
                val result = simulate(callerOn, calleeOn, CallType.VOICE)
                val expectedCaller = if (callerOn) CallType.VOICE else CallType.VIDEO
                val expectedCallee = if (callerOn && calleeOn) CallType.VOICE else CallType.VIDEO
                assertEquals("caller $callerOn/$calleeOn", expectedCaller, result.callerType)
                assertEquals("callee $callerOn/$calleeOn", expectedCallee, result.calleeType)
                // The caller's video is off at the callee either way when the caller asked for voice.
                assertEquals(callerOn, result.callerVideoOffAtCallee)
                assertEquals(callerOn && calleeOn, result.calleeVideoOffAtCaller)
                assertEquals(PrivateCallPhase.NEGOTIATING_MEDIA, result.calleePhase)
                assertEquals(PrivateCallPhase.NEGOTIATING_MEDIA, result.callerPhase)
            }
        }
        val video = simulate(callerOn = true, calleeOn = true, requested = CallType.VIDEO)
        assertEquals(CallType.VIDEO, video.callerType)
        assertEquals(CallType.VIDEO, video.calleeType)
        assertFalse(video.callerVideoOffAtCallee)
        assertFalse(video.calleeVideoOffAtCaller)
    }

    private data class Simulation(
        val callerType: CallType,
        val calleeType: CallType,
        val callerVideoOffAtCallee: Boolean,
        val calleeVideoOffAtCaller: Boolean,
        val callerPhase: PrivateCallPhase,
        val calleePhase: PrivateCallPhase,
    )

    private fun simulate(callerOn: Boolean, calleeOn: Boolean, requested: CallType): Simulation {
        val callId = UUID.randomUUID()
        val caller = VoiceCallNegotiation.outgoing(callerOn, requested)
        var callerState = PrivateCallState.outgoing(callId)
        var calleeState: PrivateCallState? = null
        var callee: VoiceCallNegotiation? = null
        var callerVideoOffAtCallee = false
        var calleeVideoOffAtCaller = false

        fun wire(signal: PrivateCallSignal): PrivateCallSignalMessage {
            val encoded = PrivateCallSignalCodec.encode(PrivateCallSignalMessage(callId, signal))
            assertEquals(PrivateCallSignalCodec.ENCODED_BYTES, encoded.size)
            return PrivateCallSignalCodec.decode(encoded)
        }

        // Caller -> callee: the ordered invite sequence, then the callee rings.
        for (signal in caller.inviteSignals()) {
            val message = wire(signal)
            when (message.signal) {
                PrivateCallSignal.INVITE -> {
                    calleeState = PrivateCallState.incoming(message.callId)
                    callee = VoiceCallNegotiation.incoming(calleeOn)
                }
                PrivateCallSignal.CAMERA_OFF -> {
                    callee!!.onRemoteCameraSignal(calleeState!!.phase, enabled = false)
                    callerVideoOffAtCallee = true
                }
                else -> error("Unexpected invite signal ${message.signal}")
            }
        }
        callerState = callerState.transition(PrivateCallEvent.REMOTE_RINGING)
        // Callee answers with its ordered accept sequence.
        calleeState = calleeState!!.transition(PrivateCallEvent.LOCAL_ACCEPT)
        for (signal in callee!!.acceptSignals()) {
            val message = wire(signal)
            when (message.signal) {
                PrivateCallSignal.ACCEPT -> callerState = callerState.transition(PrivateCallEvent.REMOTE_ACCEPT)
                PrivateCallSignal.CAMERA_OFF -> {
                    assertFalse(caller.onRemoteCameraSignal(callerState.phase, enabled = false))
                    calleeVideoOffAtCaller = true
                }
                else -> error("Unexpected answer signal ${message.signal}")
            }
        }
        return Simulation(
            caller.localType,
            callee!!.localType,
            callerVideoOffAtCallee,
            calleeVideoOffAtCaller,
            callerState.phase,
            calleeState!!.phase,
        )
    }

    // ---- Layout: upgrade and downgrade ---------------------------------------------------------

    @Test
    fun voiceCallShowsTheVoiceLayoutAndEitherSideCanAddVideo() {
        val local = CallLayoutState(CallType.VOICE)
        assertEquals(CallLayout.VOICE, local.layout)
        assertTrue(local.onLocalVideo(true))
        assertEquals(CallLayout.VIDEO, local.layout)
        assertTrue("Removing my video returns to voice", local.onLocalVideo(false))
        assertEquals(CallLayout.VOICE, local.layout)

        val remote = CallLayoutState(CallType.VOICE)
        assertFalse(remote.onRemoteCameraSignal(false))
        assertTrue(remote.onRemoteCameraSignal(true))
        assertEquals(CallLayout.VIDEO, remote.layout)
    }

    @Test
    fun videoCallDowngradesToVoiceOnlyWhenNoVideoFlowsEitherWay() {
        val state = CallLayoutState(CallType.VIDEO)
        assertEquals(CallLayout.VIDEO, state.layout)
        assertFalse(state.onRemoteCameraSignal(false))
        assertEquals(CallLayout.VIDEO, state.layout)
        assertTrue(state.onLocalVideo(false))
        assertEquals(CallLayout.VOICE, state.layout)
    }

    @Test
    fun peerWithoutTheFeatureIsDetectedByItsFirstFrameButSignalsWin() {
        val oldPeer = CallLayoutState(CallType.VOICE)
        assertTrue("An unsignalled peer's first frame means its video is on", oldPeer.onRemoteFrame())
        assertEquals(CallLayout.VIDEO, oldPeer.layout)

        val signalled = CallLayoutState(CallType.VOICE)
        signalled.onRemoteCameraSignal(false)
        assertFalse("A late in-flight frame cannot override Camera off", signalled.onRemoteFrame())
        assertEquals(CallLayout.VOICE, signalled.layout)
    }

    // ---- Audio route ------------------------------------------------------------------------------

    @Test
    fun voiceStartsOnTheEarpieceAndTheRouteFollowsTheLayoutUntilTheUserChooses() {
        val voice = CallAudioRoutePolicy(CallType.VOICE)
        assertFalse(voice.initialSpeakerOn)
        assertTrue(CallAudioRoutePolicy(CallType.VIDEO).initialSpeakerOn)
        assertEquals(true, voice.speakerForLayout(CallLayout.VIDEO))
        assertEquals(false, voice.speakerForLayout(CallLayout.VOICE))
        voice.onUserChoice()
        assertNull(voice.speakerForLayout(CallLayout.VIDEO))
    }

    // ---- Push-to-talk -----------------------------------------------------------------------------

    @Test
    fun pttModeStartsMutedHoldUnmutesAndReleaseMutes() {
        val ptt = PushToTalkState(startMuted = true)
        assertTrue(ptt.muted)
        assertEquals(PushToTalkResult(accepted = true, mutedChange = false), ptt.press())
        assertTrue(ptt.held)
        assertEquals(PushToTalkResult(accepted = false, mutedChange = null), ptt.press())
        assertEquals(PushToTalkResult(accepted = true, mutedChange = true), ptt.release())
        assertTrue(ptt.muted)
        assertEquals(PushToTalkResult(accepted = false, mutedChange = null), ptt.release())
    }

    @Test
    fun releasingMutesEvenWhenTheMicrophoneWasOpenBeforeThePress() {
        val ptt = PushToTalkState(startMuted = false)
        assertEquals(PushToTalkResult(accepted = true, mutedChange = null), ptt.press())
        assertFalse(ptt.muted)
        assertEquals(PushToTalkResult(accepted = true, mutedChange = true), ptt.release())
        assertTrue(ptt.muted)
    }

    @Test
    fun muteButtonCannotChangeTheMicrophoneWhileTheTalkButtonIsHeld() {
        val ptt = PushToTalkState(startMuted = true)
        ptt.press()
        assertNull(ptt.setMuted(true))
        assertFalse(ptt.muted)
        ptt.release()
        assertEquals(false, ptt.setMuted(false))
        assertFalse("Open microphone after unmuting", ptt.muted)
        assertNull("No change when already in that state", ptt.setMuted(false))
    }
}
