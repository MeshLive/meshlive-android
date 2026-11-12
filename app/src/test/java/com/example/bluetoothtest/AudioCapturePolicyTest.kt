package com.example.bluetoothtest

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCapturePolicyTest {
    private class FakeEffect(
        private val defaultEnabled: Boolean?,
        private val uuid: String? = "uuid-1",
        private val implementor: String? = "The Android Open Source Project",
        private val control: Boolean? = true,
        private val setCode: Int? = 0,
        private val failReads: Boolean = false,
    ) : EffectHandle {
        var enabledState: Boolean? = defaultEnabled
        val setCalls = mutableListOf<Boolean>()
        override fun uuid() = if (failReads) throw IllegalStateException("dead") else uuid
        override fun implementor() = implementor
        override fun hasControl() = control
        override fun enabled() = enabledState
        override fun setEnabled(enabled: Boolean): Int? {
            setCalls += enabled
            if (setCode == 0) enabledState = enabled
            return setCode
        }
        override fun release() = Unit
    }

    @Test
    fun theDefaultStateIsReadBeforeEnablingAndAnEffectIsNeverSwitchedOff() {
        val effect = FakeEffect(defaultEnabled = true)
        val (outcome, handle) = AudioEffectProbe.enable("aec", true) { effect }
        assertEquals(true, outcome.defaultEnabled)
        assertEquals(true, outcome.finalEnabled)
        assertEquals(0, outcome.setEnabledCode)
        assertEquals(listOf(true), effect.setCalls)
        assertTrue(handle === effect)
        assertTrue("default on must never be switched off", false !in effect.setCalls)
    }

    @Test
    fun defaultOffIsRecordedAndTheEnableRequestStillMade() {
        val effect = FakeEffect(defaultEnabled = false)
        val (outcome, _) = AudioEffectProbe.enable("ns", true) { effect }
        assertEquals(false, outcome.defaultEnabled)
        assertEquals(true, outcome.finalEnabled)
        assertTrue(outcome.detail().startsWith("ns{default=0,final=1,set=0,ctl=1,sw=0"))
    }

    @Test
    fun aRejectedEnableKeepsTheStatusCodeAndFinalState() {
        val effect = FakeEffect(defaultEnabled = false, setCode = -3, control = false)
        val (outcome, _) = AudioEffectProbe.enable("agc", true) { effect }
        assertEquals(-3, outcome.setEnabledCode)
        assertEquals(false, outcome.finalEnabled)
        assertEquals("agc:off(The Android Open Source Project)", outcome.summary())
        assertTrue(outcome.detail().contains("ctl=0"))
    }

    @Test
    fun theAospSoftwareAecIsRecognisedButKept() {
        val effect = FakeEffect(defaultEnabled = true, uuid = AudioEffectUuids.AOSP_SOFTWARE_AEC.uppercase())
        val (outcome, handle) = AudioEffectProbe.enable("aec", true) { effect }
        assertTrue(outcome.aospSoftware)
        assertTrue(outcome.detail().contains("sw=1"))
        assertEquals(true, outcome.finalEnabled)
        assertTrue(handle != null)
        assertTrue(AudioEffectUuids.isAospSoftware(AudioEffectUuids.AOSP_SOFTWARE_NS))
        assertFalse(AudioEffectUuids.isAospSoftware("other"))
        assertFalse(AudioEffectUuids.isAospSoftware(null))
    }

    @Test
    fun unavailableAndFailedCreateKeepTheFormerSummary() {
        val (missing, missingHandle) = AudioEffectProbe.enable("aec", false) { error("not called") }
        assertEquals("aec:unavailable", missing.summary())
        assertNull(missingHandle)
        val (failed, failedHandle) = AudioEffectProbe.enable("ns", true) { null }
        assertEquals("ns:create-failed", failed.summary())
        assertNull(failedHandle)
        val (thrown, thrownHandle) = AudioEffectProbe.enable("agc", true) { throw IllegalStateException("x") }
        assertEquals("agc:create-failed", thrown.summary())
        assertNull(thrownHandle)
        assertEquals("aec:unavailable,ns:create-failed", AudioEffectProbe.summary(listOf(missing, failed)))
    }

    @Test
    fun anUnreadableDescriptorDoesNotBreakTheOutcome() {
        val effect = FakeEffect(defaultEnabled = true, failReads = true)
        val (outcome, _) = AudioEffectProbe.enable("aec", true) { effect }
        assertNull(outcome.uuid)
        assertTrue(outcome.detail().contains("uuid=?"))
    }

    @Test
    fun theSourceChainRelaxesVoiceCommunicationToMicToCamcorder() {
        assertEquals(
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
            ),
            CaptureSourceChain.forMode(true),
        )
        assertEquals(MediaRecorder.AudioSource.MIC, CaptureSourceChain.next(true, MediaRecorder.AudioSource.VOICE_COMMUNICATION))
        assertEquals(MediaRecorder.AudioSource.CAMCORDER, CaptureSourceChain.next(true, MediaRecorder.AudioSource.MIC))
        assertNull(CaptureSourceChain.next(true, MediaRecorder.AudioSource.CAMCORDER))
        // Stream audio starts on CAMCORDER exactly as before and can only relax to MIC.
        assertEquals(MediaRecorder.AudioSource.CAMCORDER, CaptureSourceChain.forMode(false).first())
        assertEquals(MediaRecorder.AudioSource.MIC, CaptureSourceChain.next(false, MediaRecorder.AudioSource.CAMCORDER))
        assertNull(CaptureSourceChain.next(false, MediaRecorder.AudioSource.MIC))
        assertNull(CaptureSourceChain.next(true, MediaRecorder.AudioSource.VOICE_RECOGNITION))
    }

    @Test
    fun startRetryTriesThreeTimesWithBackoffAndStopsOnSuccess() {
        val sleeps = mutableListOf<Long>()
        val retry = CaptureStartRetry(sleep = { sleeps += it })
        val tried = mutableListOf<Int>()
        // The first attempt happened on the caller's thread and failed; attempts 2 and 3 run here.
        val ok = retry.runFrom(2) { attempt ->
            tried += attempt
            attempt == 3
        }
        assertEquals(3, ok)
        assertEquals(listOf(2, 3), tried)
        assertEquals(listOf(250L, 250L), sleeps)
    }

    @Test
    fun startRetryGivesUpAfterTheLastAttemptAndHonoursClose() {
        val retry = CaptureStartRetry(sleep = {})
        val tried = mutableListOf<Int>()
        assertNull(retry.runFrom(1) { tried += it; false })
        assertEquals(listOf(1, 2, 3), tried)

        var running = true
        val aborted = mutableListOf<Int>()
        assertNull(retry.runFrom(2, { running }) { attempt -> aborted += attempt; running = false; false })
        assertEquals(listOf(2), aborted)
    }

    @Test
    fun theSilenceDetectorFiresOncePerEpisodeAfterTwoSeconds() {
        val detector = CaptureSilenceDetector(frameMs = 20)
        var fired = 0
        repeat(99) { if (detector.onFrame(0)) fired++ }
        assertEquals(0, fired)
        assertTrue(detector.onFrame(0))
        repeat(50) { assertFalse(detector.onFrame(0)) }
        // A non-zero frame ends the episode; a new silence can fire again.
        assertFalse(detector.onFrame(3))
        repeat(99) { assertFalse(detector.onFrame(0)) }
        assertTrue(detector.onFrame(0))
        detector.reset()
        repeat(99) { assertFalse(detector.onFrame(0)) }
    }

    @Test
    fun quietRoomNoiseSuppressionZerosNeverConfirmABlankCapture() {
        // F18 (S24/M31 VOICE_COMMUNICATION in a quiet room): 2-6 s zero runs between short bursts of signal, for minutes.
        val evidence = BlankCaptureEvidence(frameMs = 20)
        repeat(60) {
            repeat(5) { assertEquals(BlankCaptureEvidence.Verdict.NONE, evidence.onFrame(40)) }
            repeat(300) { assertEquals(BlankCaptureEvidence.Verdict.NONE, evidence.onFrame(0)) } // 6 s of zeros
        }
        // The 2 s episode still happens (diagnostic line), but its notice waits for evidence.
        assertNull(SilenceClassifier.episodeNotice(SilenceCause.BLANK_CAPTURE, micToggleSupported = true, evidence = true))
        assertEquals(
            "control arm: the old 2 s warning",
            AudioCaptureNotice.CAPTURE_SILENT,
            SilenceClassifier.episodeNotice(SilenceCause.BLANK_CAPTURE, micToggleSupported = true, evidence = false),
        )
        assertEquals(
            "the platform's own silencing is told at once",
            AudioCaptureNotice.MIC_PRIVACY_SWITCH,
            SilenceClassifier.episodeNotice(SilenceCause.SYSTEM_SILENCED, micToggleSupported = true, evidence = true),
        )
    }

    @Test
    fun aDeadCapturePathIsConfirmedOnceByNoSignalOrALongZeroRun() {
        // No signal since the recorder started: 8 s.
        val fresh = BlankCaptureEvidence(frameMs = 20)
        repeat(399) { assertEquals(BlankCaptureEvidence.Verdict.NONE, fresh.onFrame(0)) }
        assertEquals(BlankCaptureEvidence.Verdict.NEVER_SIGNAL, fresh.onFrame(0))
        assertEquals(8_000L, fresh.zeroMs)
        repeat(2_000) { assertEquals("once per run", BlankCaptureEvidence.Verdict.NONE, fresh.onFrame(0)) }

        // A path that dies mid-call: one 30 s zero run.
        val midCall = BlankCaptureEvidence(frameMs = 20)
        assertEquals(BlankCaptureEvidence.Verdict.NONE, midCall.onFrame(500))
        repeat(1_499) { assertEquals(BlankCaptureEvidence.Verdict.NONE, midCall.onFrame(0)) }
        assertEquals(BlankCaptureEvidence.Verdict.PERSISTENT, midCall.onFrame(0))

        // A relaxed source is a new stream and must show signal again.
        midCall.reset()
        repeat(399) { midCall.onFrame(0) }
        assertEquals(BlankCaptureEvidence.Verdict.NEVER_SIGNAL, midCall.onFrame(0))

        AudioSwitches.captureSilenceEvidence = false
        assertEquals("DEBUG control arm; a release build keeps it on", !BuildConfig.DEBUG, AudioSwitches.captureSilenceEvidence)
        AudioSwitches.captureSilenceEvidence = true
    }

    @Test
    fun aQuietButNotZeroMicrophoneNeverCountsAsSilence() {
        val detector = CaptureSilenceDetector(frameMs = 20)
        repeat(1_000) { assertFalse(detector.onFrame(1)) }
    }

    @Test
    fun peakReadsPcm16LittleEndian() {
        assertEquals(0, PcmLevel.peak(ByteArray(960)))
        val bytes = ByteArray(8)
        bytes[2] = 0x34
        bytes[3] = 0x12 // 0x1234
        bytes[6] = 0x00
        bytes[7] = 0x80.toByte() // -32768
        assertEquals(32768, PcmLevel.peak(bytes))
        bytes[7] = 0xFF.toByte()
        bytes[6] = 0xFE.toByte() // -2
        assertEquals(0x1234, PcmLevel.peak(bytes))
        assertEquals(0, PcmLevel.peak(bytes, length = 2))
    }

    @Test
    fun aSilencedClientIsNotChangedBySourceButABlankCaptureIs() {
        assertEquals(SilenceCause.SYSTEM_SILENCED, SilenceClassifier.classify(true))
        assertEquals(SilenceCause.BLANK_CAPTURE, SilenceClassifier.classify(false))
        assertEquals(SilenceCause.BLANK_CAPTURE, SilenceClassifier.classify(null))
        assertFalse(SilenceClassifier.mayChangeSource(SilenceCause.SYSTEM_SILENCED))
        assertTrue(SilenceClassifier.mayChangeSource(SilenceCause.BLANK_CAPTURE))
    }

    @Test
    fun aMicrophonePrivacySwitchHasItsOwnMessage() {
        assertEquals(
            AudioCaptureNotice.MIC_PRIVACY_SWITCH,
            SilenceClassifier.notice(SilenceCause.SYSTEM_SILENCED, micToggleSupported = true),
        )
        assertEquals(
            AudioCaptureNotice.MIC_BLOCKED,
            SilenceClassifier.notice(SilenceCause.SYSTEM_SILENCED, micToggleSupported = false),
        )
        assertEquals(AudioCaptureNotice.CAPTURE_SILENT, SilenceClassifier.notice(SilenceCause.BLANK_CAPTURE, true))
        AudioCaptureNotice.entries.forEach { assertTrue(it.name, it.text.isNotBlank()) }
    }

    @Test
    fun noticesAreRateLimitedPerKind() {
        val limiter = NoticeLimiter(minIntervalMs = 30_000)
        assertTrue(limiter.allow(AudioCaptureNotice.CAPTURE_SILENT, 1_000))
        assertFalse(limiter.allow(AudioCaptureNotice.CAPTURE_SILENT, 20_000))
        assertTrue(limiter.allow(AudioCaptureNotice.ECHO_RISK, 20_000))
        assertTrue(limiter.allow(AudioCaptureNotice.CAPTURE_SILENT, 31_000))
    }

    @Test
    fun echoRiskNeedsALoudspeakerCallWithoutAnEffectiveAec() {
        assertTrue(EchoRisk.atRisk(true, aecEnabledByApp = false, aecInFramework = false, loudspeaker = true))
        assertTrue(EchoRisk.atRisk(true, aecEnabledByApp = null, aecInFramework = null, loudspeaker = true))
        assertFalse(EchoRisk.atRisk(true, aecEnabledByApp = true, aecInFramework = false, loudspeaker = true))
        assertFalse(EchoRisk.atRisk(true, aecEnabledByApp = false, aecInFramework = true, loudspeaker = true))
        assertFalse("earpiece or headset", EchoRisk.atRisk(true, false, false, loudspeaker = false))
        assertFalse("not a call", EchoRisk.atRisk(false, false, false, loudspeaker = true))
    }

    @Test
    fun anUnusableBufferSizeGivesAPlainMessage() {
        assertNull(MinBufferCheck.captureProblem(1_920))
        assertTrue(MinBufferCheck.captureProblem(-2)!!.contains("getMinBufferSize=-2"))
        assertNull(MinBufferCheck.playbackProblem(3_840, 48_000))
        assertTrue(MinBufferCheck.playbackProblem(0, 48_000)!!.contains("48000 Hz"))
    }

    @Test
    fun theComplexityGovernorStepsDownOnlyAfterAHighWindowAndNeverBackUp() {
        val frameNs = 20_000_000L
        val governor = OpusComplexityGovernor(frameNs, windowFrames = 10)
        assertEquals(10, governor.complexity)
        // Fast frames (1 ms): no change.
        repeat(30) { assertNull(governor.onFrame(1_000_000L)) }
        // Slow frames (8 ms = 40% of the frame): the first full window steps to 8, the next to 5.
        repeat(9) { assertNull(governor.onFrame(8_000_000L)) }
        assertEquals(8, governor.onFrame(8_000_000L))
        repeat(9) { assertNull(governor.onFrame(8_000_000L)) }
        assertEquals(5, governor.onFrame(8_000_000L))
        // The ladder ends at 5 and does not climb back when the phone speeds up.
        repeat(100) { assertNull(governor.onFrame(8_000_000L)) }
        repeat(100) { assertNull(governor.onFrame(100_000L)) }
        assertEquals(5, governor.complexity)
    }
}
