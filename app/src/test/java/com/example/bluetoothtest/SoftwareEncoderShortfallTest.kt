package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP5 item 2 (Experimental `test_sw_shortfall_avc`): a software encoder that stays short of the frame rate while its
 * input keeps up is remembered, and the next call or stream start prefers hardware H.264. The window replays use the 5 s
 * `Encoder output` windows of rd-20261107.
 */
class SoftwareEncoderShortfallTest {
    private val frameMs = 1_000.0 / 15

    /** Feeds (output FPS, source frame ms, encode p95 ms) windows at [target] FPS with [drops] per window; every answer. */
    private fun replay(
        windows: List<Triple<Double, Double?, Double?>>,
        drops: Long = 0L,
        target: Int = 15,
        tracker: SoftwareShortfallWindows = SoftwareShortfallWindows(),
    ): List<SoftwareShortfallVerdict?> = windows.map { (fps, source, encode) -> tracker.onWindow(fps, target, source, encode, drops) }

    @Test
    fun aSoftwareEncoderShortWhileTheCameraKeepsUpIsRecordedOnceAfterTheWarmUp() {
        // The A15s's libvpx at 640x480 (recorded encode load 1.76: p95 about 117 ms of a 66.7 ms frame), camera at 15 FPS.
        val tracker = SoftwareShortfallWindows()
        val short = Triple(8.5, frameMs, 117.0)
        val answers = replay(List(6) { short }, tracker = tracker)
        // Window 1 is the warm-up; windows 2-4 are the three short windows; one record, then nothing more.
        assertEquals(listOf(null, null, null), answers.take(3))
        val verdict = answers[3]!!
        assertEquals(SoftwareEncoderShortfall.SHORT_WINDOWS, verdict.windows)
        assertEquals(15, verdict.targetFps)
        assertEquals(8.5, verdict.outputFps, 0.0)
        assertEquals(listOf(null, null), answers.drop(4))
        assertTrue(tracker.recorded)
        assertEquals(SoftwareShortfallWindows.Window.DONE, tracker.last)
    }

    @Test
    fun aDarkSceneThatSlowsTheCameraIsNotTheEncodersShortfall() {
        // rd-20261107 0004, A15s libaom 640x480: 13.4 then 9.9-10.1 FPS, encode p95 89.4 ms, sensor frame 100.05 ms
        // (exposure 70 ms): the camera delivered 10 FPS, and at 10 FPS the encoder kept up.
        val tracker = SoftwareShortfallWindows()
        val answers = replay(listOf(13.4, 9.9, 10.0, 10.1, 10.0, 10.0).map { Triple(it, 100.05, 89.4) }, tracker = tracker)
        assertTrue(answers.all { it == null })
        assertFalse(tracker.recorded)
        assertEquals(SoftwareShortfallWindows.Window.SOURCE_LIMITED, tracker.last)
    }

    @Test
    fun theRealmesLibvpxHeldTheRateAndIsNeverRecorded() {
        // rd-20261107 0001 (640x480) and 0009 (960x540): 15.0-15.4 FPS, encode p95 21-34 ms, sensor frame 66.7 ms.
        val tracker = SoftwareShortfallWindows()
        val answers = replay(listOf(15.4, 15.0, 15.0, 15.0, 15.0, 15.0).map { Triple(it, 66.7, 34.0) }, tracker = tracker)
        assertTrue(answers.all { it == null })
        assertEquals(SoftwareShortfallWindows.Window.ON_RATE, tracker.last)
    }

    @Test
    fun aShortWindowCountsOnlyWithEvidenceThatTheEncoderWasTheLimit() {
        // Short with the camera at rate, but every frame encoded in time and none dropped: not the encoder.
        val quick = SoftwareShortfallWindows()
        assertTrue(replay(List(6) { Triple(9.0, frameMs, 40.0) }, tracker = quick).all { it == null })
        assertEquals(SoftwareShortfallWindows.Window.NOT_ENCODER_LIMITED, quick.last)
        // The same windows with input frames dropped while the encoder was busy count.
        val busy = SoftwareShortfallWindows()
        assertTrue(replay(List(4) { Triple(9.0, frameMs, 40.0) }, drops = 3, tracker = busy)[3] != null)
        // No camera reading (a raw fixture paces its own input) with a slow encode counts too.
        val raw = SoftwareShortfallWindows()
        assertTrue(replay(List(4) { Triple(9.0, null, 117.0) }, tracker = raw)[3] != null)
    }

    @Test
    fun anOnRateWindowRestartsTheCountAndTheRateInForceIsTheTarget() {
        val tracker = SoftwareShortfallWindows()
        val short = Triple(8.5, frameMs, 117.0)
        // 12.0 FPS is exactly 80 % of 15: on rate, so the count starts again.
        val answers = replay(listOf(short, short, short, Triple(12.0, frameMs, 117.0), short, short, short), tracker = tracker)
        assertEquals(6, answers.indexOfFirst { it != null })
        // A link or thermal limit lowered the rate in force to 10 FPS: 9 FPS is on rate for it.
        val limited = SoftwareShortfallWindows()
        assertTrue(replay(List(6) { Triple(9.0, 100.0, 95.0) }, target = 10, tracker = limited).all { it == null })
        assertEquals(SoftwareShortfallWindows.Window.ON_RATE, limited.last)
    }

    @Test
    fun aMarkCoversTheSameOrALargerSessionOfTheSameEncoderForSevenDays() {
        val now = 1_000_000_000_000L
        val marks = mapOf(SoftwareShortfallMark("libvpx-vp9", 640, 480, 15).key to now - 3_600_000L)
        fun slow(w: Int, h: Int, fps: Int, at: Long = now) = SoftwareEncoderShortfall.slowEncoders(marks, w, h, fps, at)
        assertEquals(setOf("libvpx-vp9"), slow(640, 480, 15))
        assertEquals(setOf("libvpx-vp9"), slow(960, 540, 15))
        assertEquals(emptySet<String>(), slow(320, 240, 15))
        assertEquals(emptySet<String>(), slow(640, 480, 10))
        assertEquals(emptySet<String>(), slow(640, 480, 15, at = now - 3_600_000L + SoftwareEncoderShortfall.MEMORY_MS))
        // A mark dated in the future (the clock moved back) does not count.
        val future = mapOf(marks.keys.single() to now + 60_000L)
        assertEquals(emptySet<String>(), SoftwareEncoderShortfall.slowEncoders(future, 640, 480, 15, now))
    }

    @Test
    fun keysRoundTripAndAnythingElseIsIgnored() {
        val mark = SoftwareShortfallMark("libvpx-vp9", 960, 540, 15)
        assertEquals("libvpx-vp9|960x540@15", mark.key)
        assertEquals(mark, SoftwareEncoderShortfall.parse(mark.key))
        listOf("", "junk", "libvpx-vp9|960x540", "libvpx-vp9|0x540@15", "|960x540@15", "libvpx-vp9|960x540@x").forEach {
            assertNull(it, SoftwareEncoderShortfall.parse(it))
        }
        assertEquals(emptySet<String>(), SoftwareEncoderShortfall.slowEncoders(mapOf("junk" to 1L), 640, 480, 15, 2L))
    }

    // The choice, through VideoCodecChoice (the seam AndroidPrivateVideoCall and MainActivity call at session start).
    private val none: (VideoCodec) -> Boolean = { false }
    private val session = CodecSession(640, 480, 15)
    /** A peer that decodes VP9, AV1 and H.264 but not HEVC (the A15s with the H.264 master on). */
    private val noHevcPeer = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1 or codecCapabilityMask(StreamProtocol.CODEC_H264)
    private val envelope = EncoderEnvelope(
        minWidth = 96, maxWidth = 1920, minHeight = 96, maxHeight = 1088, widthAlignment = 2, heightAlignment = 2,
        fpsPoints = EncoderEnvelope.SAMPLE_SIZES.map { (w, h) -> FpsPoint(w, h, 120.0, "pp") },
    )

    private fun codec(name: String, protocolId: Int, hardware: Boolean, backend: VideoEncoderBackend = VideoEncoderBackend.MEDIA_CODEC): VideoCodec {
        val hc = protocolId == StreamProtocol.CODEC_H264 || protocolId == StreamProtocol.CODEC_HEVC
        return VideoCodec(
            displayName = name, mimeType = "video/x", protocolId = protocolId, encoderName = name,
            hardwareAccelerated = hardware, usesSurfaceInput = hardware, bitrateMode = 2, supportedBitrateModes = setOf(2),
            complexity = null, backend = backend, envelope = if (hc) envelope else null,
        )
    }

    private val libvpx = codec(VideoCodec.LIBVPX_ENCODER_NAME, StreamProtocol.CODEC_VP9, false, VideoEncoderBackend.NATIVE_LIBVPX)
    private val libaom = codec("libaom-native", StreamProtocol.CODEC_AV1, false, VideoEncoderBackend.NATIVE_LIBAOM)
    private val hwHevc = codec("c2.vendor.hevc.encoder", StreamProtocol.CODEC_HEVC, true)
    private val hwAvc = codec("c2.vendor.avc.encoder", StreamProtocol.CODEC_H264, true)

    private fun phone(vararg codecs: VideoCodec) = codecs.toList().sortedBy(VideoCodec::selectionPriority)
    /** Hardware H.264 and the bundled software encoders (the A15s). */
    private fun a15s() = phone(hwAvc, libaom, libvpx)
    /** Hardware HEVC and H.264, no hardware VP9 (the Realme). */
    private fun realme() = phone(hwHevc, hwAvc, libaom, libvpx)

    private fun switches(on: Boolean, slow: Set<String> = setOf(VideoCodec.LIBVPX_ENCODER_NAME), power: CodecPowerState = CodecPowerState.NORMAL) =
        CodecChoiceSwitches(hevc = true, h264 = true, preferLibvpx = true, power = power, swShortfall = on, slowSoftware = slow)

    private fun call(
        available: List<VideoCodec>,
        s: CodecChoiceSwitches,
        peer: Int = noHevcPeer,
        userChoice: Boolean = false,
        requested: VideoCodec = available.first(),
        familyVp9: Boolean = false,
    ) = VideoCodecChoice.forCall(requested, userChoice, available, peer, s, familyVp9 = familyVp9, session = session, excluded = none)

    private fun stream(available: List<VideoCodec>, s: CodecChoiceSwitches) =
        VideoCodecChoice.forStream(available.first(), false, available, s, session, none)

    @Test
    fun aRememberedShortLibvpxYieldsToHardwareH264AtTheNextStart() {
        for (available in listOf(a15s(), realme())) {
            assertEquals(libvpx, call(available, switches(on = false)).sent)
            val on = call(available, switches(on = true))
            assertEquals(hwAvc, on.sent)
            assertEquals(SoftwareEncoderShortfall.REASON, on.reason)
            assertTrue(on.shortfallApplied)
            assertFalse(on.powerApplied)
            // The fallback still walks to libvpx right behind hardware H.264.
            assertEquals(listOf(hwAvc, libvpx), on.ordered.take(2))
        }
        // Streams: the A15s's stream order starts with libvpx too; the Realme's starts with hardware HEVC, untouched.
        assertEquals(libvpx, stream(a15s(), switches(on = false)).sent)
        val st = stream(a15s(), switches(on = true))
        assertEquals(hwAvc, st.sent)
        assertEquals(SoftwareEncoderShortfall.REASON, st.reason)
        assertTrue(st.shortfallApplied)
        assertEquals(hwHevc, stream(realme(), switches(on = true)).sent)
    }

    @Test
    fun aPeerThatCannotDecodeH264AnExcludedEncoderOrNoMarkLeavesTheSoftwareEncoder() {
        val noAvc = call(a15s(), switches(on = true), peer = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1)
        assertEquals(libvpx, noAvc.sent)
        assertFalse(noAvc.shortfallApplied)
        val excludedAvc: (VideoCodec) -> Boolean = { it == hwAvc }
        val excluded = VideoCodecChoice.forCall(libvpx, false, a15s(), noHevcPeer, switches(on = true), false, session, excludedAvc)
        assertEquals(libvpx, excluded.sent)
        assertFalse(excluded.shortfallApplied)
        assertEquals(libvpx, call(a15s(), switches(on = true, slow = emptySet())).sent)
        assertEquals(libvpx, call(a15s(), switches(on = true, slow = setOf("libaom-native"))).sent)
    }

    @Test
    fun neverOverridesAHandPickedEncoderTheFamilyVp9RulePeerBit7OrThePowerAwareStart() {
        val on = switches(on = true)
        val manual = call(a15s(), on, userChoice = true, requested = libvpx)
        assertEquals(libvpx, manual.sent)
        assertEquals("user_choice", manual.reason)
        assertFalse(manual.shortfallApplied)
        // The family VP9 rule swaps the Realme's hardware HEVC first choice for VP9 (libvpx); the shortfall does not undo it.
        val family = call(realme(), on, peer = noHevcPeer or codecCapabilityMask(StreamProtocol.CODEC_HEVC), familyVp9 = true)
        assertEquals(libvpx, family.sent)
        assertEquals("family_vp9_compatibility", family.reason)
        assertFalse(family.shortfallApplied)
        val bit7 = call(a15s(), on, peer = noHevcPeer or NodeCapabilities.DECODE_PREFERS_VP9)
        assertEquals(libvpx, bit7.sent)
        assertFalse(bit7.shortfallApplied)
        // A warm phone: the power-aware start already put hardware first and names its own reason.
        val warm = call(a15s(), switches(on = true, power = CodecPowerState.WARM))
        assertEquals(hwAvc, warm.sent)
        assertTrue(warm.powerApplied)
        assertFalse(warm.shortfallApplied)
    }

    @Test
    fun switchedOffEveryChoiceIsTodaysExactly() {
        val phones = listOf(a15s(), realme(), phone(libaom, libvpx), phone(hwHevc, hwAvc))
        val slows = listOf(emptySet<String>(), setOf(VideoCodec.LIBVPX_ENCODER_NAME), setOf("libaom-native", VideoCodec.LIBVPX_ENCODER_NAME))
        for (available in phones) {
            for (slow in slows) {
                for (power in CodecPowerState.entries) {
                    val today = CodecChoiceSwitches(hevc = true, h264 = true, preferLibvpx = true, power = power)
                    val off = today.copy(swShortfall = false, slowSoftware = slow)
                    val label = "${available.map { it.encoderName }} slow=$slow power=$power"
                    for (peer in listOf(noHevcPeer, NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1)) {
                        val a = call(available, today, peer)
                        val b = call(available, off, peer)
                        assertEquals("call $label", a.sent, b.sent)
                        assertEquals("call $label", a.ordered, b.ordered)
                        assertEquals("call $label", a.reason, b.reason)
                        assertFalse("call $label", b.shortfallApplied)
                    }
                    val s1 = stream(available, today)
                    val s2 = stream(available, off)
                    assertEquals("stream $label", s1.sent, s2.sent)
                    assertEquals("stream $label", s1.ordered, s2.ordered)
                    assertEquals("stream $label", s1.reason, s2.reason)
                    assertFalse("stream $label", s2.shortfallApplied)
                }
            }
        }
        assertNull(SoftwareEncoderShortfall.reorder(a15s(), enabled = false, slow = setOf(VideoCodec.LIBVPX_ENCODER_NAME), excluded = none))
    }
}
