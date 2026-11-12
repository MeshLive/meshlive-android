package com.example.bluetoothtest

/**
 * Intercom features (design decision 2026-10-09, product pillar 1; promoted to a normal Settings >
 * Features switch 2026-10-11): voice-only calls with per-direction video upgrade/downgrade, and
 * push-to-talk within a call. The shipped default is [CallFeatureDefaults.VOICE_CALLS]; the user's
 * switch and the DEBUG `test_voice_calls` extra override it.
 *
 * Voice calls add no wire value. They use the existing authenticated, ordered `BMC2` camera-state
 * signals (PROTOCOL "Voice calls"): a voice caller sends Invite immediately followed by
 * Camera off, and a callee that answers as voice sends Accept followed by Camera off. A peer without
 * the feature (an older build, or the switch off) already accepts Camera off at any call phase, so
 * it simply sees a video call whose caller has video off and answers with its own camera. Nothing
 * here changes encryption, ordering, hop-local control or admission.
 */
enum class CallType(val label: String) {
    VOICE("voice"),
    VIDEO("video"),
}

/**
 * Effective intercom switches. The hold-Mute-to-talk gesture is part of every call; the explicit
 * push-to-talk button and PTT mode stay DEBUG launch extras only (DECISIONS: push-to-talk is not a
 * mode), so a user session never has them.
 */
data class IntercomFeatureSettings(
    val voiceCalls: Boolean = false,
    val pushToTalk: Boolean = false,
    val pttMode: Boolean = false,
) {
    val pushToTalkButton: Boolean get() = pushToTalk || pttMode

    companion object {
        const val EXTRA_VOICE_CALLS = "test_voice_calls"
        const val EXTRA_PUSH_TO_TALK = "test_push_to_talk"
        const val EXTRA_PTT_MODE = "test_ptt_mode"

        /**
         * A DEBUG benchmark launch (any `test_` extra) uses only its own extras, default off. Otherwise voice calls
         * follow [CallFeatureDefaults.VOICE_CALLS] (a constant with no Settings switch since 2026-10-17). The push-to-talk
         * button and PTT mode exist only as launch extras; nothing stores them.
         */
        fun resolve(
            benchmarkLaunch: Boolean,
            extra: (String) -> Boolean,
            stored: (String) -> Boolean?,
        ): IntercomFeatureSettings = IntercomFeatureSettings(
            voiceCalls = CallFeatureResolver.enabled(CallFeature.VOICE_CALLS, benchmarkLaunch, extra, stored),
            pushToTalk = benchmarkLaunch && extra(EXTRA_PUSH_TO_TALK),
            pttMode = benchmarkLaunch && extra(EXTRA_PTT_MODE),
        )
    }
}

/** Process-wide effective switches, refreshed by the activity when settings or launch extras change. */
object IntercomFeatures {
    @Volatile var current = IntercomFeatureSettings()

    fun label(settings: IntercomFeatureSettings = current): String =
        "voiceCalls=${settings.voiceCalls} | pushToTalk=${settings.pushToTalkButton} | pttMode=${settings.pttMode}"
}

/**
 * Call-type negotiation for one call, using only existing `BMC2` signals.
 *
 * - Caller: a voice call sends `INVITE, CAMERA_OFF` back to back, before any media. Ordering is the
 *   endpoint's authenticated sequence, so the callee sees Camera off before it can answer.
 * - Callee (feature on): Camera off while the call is still ringing marks a voice offer. Answering
 *   it sends `ACCEPT, CAMERA_OFF` and starts no camera or encoder. Camera on while ringing (never
 *   sent today) withdraws the voice offer.
 * - Feature off on either side: nothing changes. The caller sends only `INVITE`; a callee treats a
 *   voice offer as a video call whose caller has video off (the existing camera-off path).
 */
class VoiceCallNegotiation private constructor(
    private val enabled: Boolean,
    val role: PrivateCallRole,
    val requested: CallType,
) {
    /** True after the caller of an incoming call offered a voice call (callee with the feature on). */
    @Volatile var peerOfferedVoice = false
        private set

    /** What this phone sends when its media starts: no camera, encoder or video packet for VOICE. */
    val localType: CallType
        get() = when {
            answeringWithoutVideo -> CallType.VOICE
            !enabled -> CallType.VIDEO
            role == PrivateCallRole.CALLER -> requested
            peerOfferedVoice -> CallType.VOICE
            else -> CallType.VIDEO
        }

    /**
     * Set by a callee that answers without its camera (critical heat, DECISIONS 2026-11-06): the answer is
     * `ACCEPT, CAMERA_OFF`, the existing voice answer, which every peer accepts whatever its own switches.
     */
    @Volatile var answeringWithoutVideo = false
        private set

    fun answerWithoutVideo() {
        check(role == PrivateCallRole.CALLEE) { "Only a callee answers" }
        answeringWithoutVideo = true
    }

    fun inviteSignals(): List<PrivateCallSignal> {
        check(role == PrivateCallRole.CALLER) { "Only a caller invites" }
        return if (localType == CallType.VOICE) {
            listOf(PrivateCallSignal.INVITE, PrivateCallSignal.CAMERA_OFF)
        } else {
            listOf(PrivateCallSignal.INVITE)
        }
    }

    fun acceptSignals(): List<PrivateCallSignal> {
        check(role == PrivateCallRole.CALLEE) { "Only a callee accepts" }
        return if (localType == CallType.VOICE) {
            listOf(PrivateCallSignal.ACCEPT, PrivateCallSignal.CAMERA_OFF)
        } else {
            listOf(PrivateCallSignal.ACCEPT)
        }
    }

    /**
     * A remote Camera off/on signal in [phase]. Returns true when it changed the offered call type
     * (the ringing screen should update). Signals in any other phase are ordinary camera state.
     */
    fun onRemoteCameraSignal(phase: PrivateCallPhase, enabled: Boolean): Boolean {
        if (!this.enabled || role != PrivateCallRole.CALLEE || phase != PrivateCallPhase.INCOMING_RINGING) {
            return false
        }
        val offered = !enabled
        if (offered == peerOfferedVoice) return false
        peerOfferedVoice = offered
        return true
    }

    companion object {
        fun outgoing(enabled: Boolean, requested: CallType) =
            VoiceCallNegotiation(enabled, PrivateCallRole.CALLER, if (enabled) requested else CallType.VIDEO)

        fun incoming(enabled: Boolean) = VoiceCallNegotiation(enabled, PrivateCallRole.CALLEE, CallType.VIDEO)
    }
}

enum class CallLayout { VOICE, VIDEO }

/**
 * Which call screen to show. Each phone controls only its own camera (as in video calls today), so
 * an upgrade is "turn on my video" and a downgrade is "turn off my video". The voice layout shows
 * while no video flows in either direction.
 *
 * The remote side's video is known from its authenticated Camera off/on signals. A peer without the
 * feature never signals its initial state, so in a voice call its first rendered frame counts as
 * video on; once a signal has arrived, late frames already in flight cannot override it.
 */
class CallLayoutState(callType: CallType) {
    var localVideo = callType == CallType.VIDEO
        private set
    var remoteVideo = callType == CallType.VIDEO
        private set
    private var remoteSignalled = false

    /**
     * True when the peer's first video frame arrived in a voice call before any Camera on/off signal.
     * An app with voice calls signals its camera state right after Accept, so a bare video frame means
     * the peer's app (an older build, or voice calls switched off) treats this as a video call and
     * answered with its own camera. The call screen tells the caller once ([consumePeerLacksVoiceNotice]).
     */
    var peerLacksVoiceSupport = false
        private set
    private var peerLacksVoiceNoticed = false

    val layout: CallLayout
        get() = if (localVideo || remoteVideo) CallLayout.VIDEO else CallLayout.VOICE

    /** Returns true when the layout changed. */
    fun onLocalVideo(on: Boolean): Boolean = update { localVideo = on }

    fun onRemoteCameraSignal(enabled: Boolean): Boolean = update {
        remoteSignalled = true
        remoteVideo = enabled
    }

    fun onRemoteFrame(): Boolean {
        if (remoteSignalled || remoteVideo) return false
        peerLacksVoiceSupport = true
        return update { remoteVideo = true }
    }

    /** True once, the first time [peerLacksVoiceSupport] is set. */
    fun consumePeerLacksVoiceNotice(): Boolean {
        if (!peerLacksVoiceSupport || peerLacksVoiceNoticed) return false
        peerLacksVoiceNoticed = true
        return true
    }

    private inline fun update(change: () -> Unit): Boolean {
        val before = layout
        change()
        return layout != before
    }
}

/**
 * Speaker or earpiece. Voice calls start on the earpiece (held to the ear) and video calls on the
 * loudspeaker, as before. When the layout changes and the user has not picked a route in this
 * call, the route follows the layout; an explicit choice is never overridden.
 */
class CallAudioRoutePolicy(callType: CallType) {
    val initialSpeakerOn = callType == CallType.VIDEO
    private var userChose = false

    fun onUserChoice() {
        userChose = true
    }

    /** Desired loudspeaker state for [layout], or null to leave the route alone. */
    fun speakerForLayout(layout: CallLayout): Boolean? = if (userChose) null else layout == CallLayout.VIDEO
}

/** A push-to-talk press/release: whether it was a real transition, and the mute state to apply. */
data class PushToTalkResult(val accepted: Boolean, val mutedChange: Boolean?)

/**
 * Hold-to-talk on the existing mute path: the microphone and its Opus encoder keep running, and
 * the call drops captured audio while muted and sends the authenticated Microphone muted/unmuted
 * signals. Holding unmutes; releasing mutes, even if the microphone was open before the press.
 * The Mute button still toggles freely while not holding (an open microphone). In PTT mode the
 * call starts muted.
 */
class PushToTalkState(startMuted: Boolean) {
    var muted = startMuted
        private set
    var held = false
        private set

    fun press(): PushToTalkResult {
        if (held) return PushToTalkResult(accepted = false, mutedChange = null)
        held = true
        return PushToTalkResult(accepted = true, mutedChange = change(false))
    }

    fun release(): PushToTalkResult {
        if (!held) return PushToTalkResult(accepted = false, mutedChange = null)
        held = false
        return PushToTalkResult(accepted = true, mutedChange = change(true))
    }

    /** The Mute button. Ignored (null) while the talk button is held, which owns the microphone. */
    fun setMuted(value: Boolean): Boolean? {
        if (held) return null
        return change(value)
    }

    private fun change(value: Boolean): Boolean? {
        if (muted == value) return null
        muted = value
        return value
    }
}
