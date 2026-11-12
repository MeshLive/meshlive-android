package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design VX: where the bundled libvpx VP9 encoder sits in Automatic, the picker, call fallback and the bench, with
 * `prefer_libvpx` off (manual choice only: benchmark launches without the extra) and on (shipped since 0.4.2; calls
 * hw AV1 > hw HEVC > hw VP9 > libvpx > hw H.264 > libaom; streams hw AV1 > hw VP9 > hw HEVC > libvpx > hw H.264 > libaom).
 */
class LibvpxEncoderChoiceTest {
    @After
    fun reset() {
        VideoCodecSwitches.preferLibvpxOverride = null
    }

    private val none: (VideoCodec) -> Boolean = { false }
    private val session = CodecSession(640, 480, 15)

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
    private val swAv1 = codec("c2.android.av1.encoder", StreamProtocol.CODEC_AV1, false)
    private val swVp9 = codec("c2.android.vp9.encoder", StreamProtocol.CODEC_VP9, false)
    private val hwAv1 = codec("c2.vendor.av1.encoder", StreamProtocol.CODEC_AV1, true)
    private val hwHevc = codec("c2.vendor.hevc.encoder", StreamProtocol.CODEC_HEVC, true)
    private val hwVp9 = codec("c2.vendor.vp9.encoder", StreamProtocol.CODEC_VP9, true)
    private val hwAvc = codec("OMX.MTK.VIDEO.ENCODER.AVC", StreamProtocol.CODEC_H264, true)

    /** The A15s as the census shows it: hardware H.264 only, platform VP9 encoder disabled. */
    private fun a15s() = listOf(hwAvc, libaom, swAv1, libvpx).sortedBy(VideoCodec::selectionPriority)

    private fun switches(preferLibvpx: Boolean, hc: Boolean = false) =
        CodecChoiceSwitches(hevc = hc, h264 = hc, preferLibvpx = preferLibvpx)

    private fun preferLibvpx(on: Boolean) {
        VideoCodecSwitches.preferLibvpxOverride = on
    }

    @Test
    fun shippedBuildLetsAutomaticPickLibvpxOnAPhoneWithoutHardwareVp9() {
        assertTrue("absent test_prefer_libvpx follows the shipped switch", VideoCodecSwitches.preferLibvpx)
        val shipped = CodecChoiceSwitches(hevc = true, h264 = true, preferLibvpx = VideoCodecSwitches.preferLibvpx)
        assertEquals(libvpx, VideoCodecChoice.automatic(a15s(), CodecChoiceMode.CALL, shipped, session, none).first())
        assertEquals(libvpx, VideoCodecChoice.pickerOrder(a15s(), shipped, session, none).first())
    }

    @Test
    fun preferLibvpxOffNeverPicksLibvpxAutomaticallyButKeepsItSelectable() {
        preferLibvpx(false)
        // test_prefer_libvpx is DEBUG-only: a release build keeps the shipped constant.
        assertEquals(if (BuildConfig.DEBUG) false else ConnectivityFeatureDefaults.LIBVPX_AUTOMATIC, VideoCodecSwitches.preferLibvpx)
        val legacyA15s = listOf(libaom, swAv1, libvpx).sortedBy(VideoCodec::selectionPriority)
        val off = switches(preferLibvpx = false)
        // Automatic (stream and call) is exactly the list without libvpx; the picker offers libvpx last.
        assertEquals(listOf(libaom, swAv1), VideoCodecChoice.automatic(legacyA15s, CodecChoiceMode.STREAM, off, session, none))
        assertEquals(listOf(libaom, swAv1, libvpx), VideoCodecChoice.pickerOrder(legacyA15s, off, session, none))
        val hcOff = switches(preferLibvpx = false, hc = true)
        assertEquals(hwAvc, VideoCodecChoice.automatic(a15s(), CodecChoiceMode.CALL, hcOff, session, none).first())
        assertFalse(libvpx in VideoCodecChoice.automatic(a15s(), CodecChoiceMode.CALL, hcOff, session, none))
        assertEquals(libvpx, VideoCodecChoice.pickerOrder(a15s(), hcOff, session, none).last())
    }

    @Test
    fun preferLibvpxOffCallFallbackToAVp9OnlyPeerDoesNotSendLibvpx() {
        preferLibvpx(false)
        val legacyA15s = listOf(libaom, swAv1, libvpx).sortedBy(VideoCodec::selectionPriority)
        val vp9Only = NodeCapabilities.CODEC_VP9
        val automatic = VideoCodecChoice.forCall(
            requested = libaom, userChoice = false, available = legacyA15s, peerDecodeMask = vp9Only,
            switches = switches(false), familyVp9 = false, session = session, excluded = none,
        )
        assertNull("no Automatic VP9 encoder while prefer_libvpx is off", automatic.sent)
        // Picked by hand, libvpx is sent to a VP9 peer.
        val manual = VideoCodecChoice.forCall(
            requested = libvpx, userChoice = true, available = legacyA15s, peerDecodeMask = vp9Only,
            switches = switches(false), familyVp9 = false, session = session, excluded = none,
        )
        assertEquals(libvpx, manual.sent)
        assertEquals(libvpx, privateCallCodecForPeer(libvpx, legacyA15s, vp9Only, preferLibvpx = false))
    }

    @Test
    fun preferLibvpxPutsItAfterHardwareVp9ClassAndBeforeHardwareAvcAndLibaom() {
        preferLibvpx(true)
        val on = switches(preferLibvpx = true, hc = true)
        // A15s: no hardware AV1/HEVC/VP9 encoder, so libvpx is sent instead of hardware H.264 or libaom.
        val a15sCall = VideoCodecChoice.automatic(a15s(), CodecChoiceMode.CALL, on, session, none)
        assertEquals(listOf(libvpx, hwAvc, libaom, swAv1), a15sCall)
        // A phone with everything: the hypothesis order of Design VX.
        val all = listOf(hwAv1, hwHevc, hwVp9, hwAvc, libaom, swAv1, swVp9, libvpx).sortedBy(VideoCodec::selectionPriority)
        val call = VideoCodecChoice.automatic(all, CodecChoiceMode.CALL, on, session, none)
        assertEquals(listOf(hwAv1, hwHevc, hwVp9, libvpx, hwAvc, libaom), call.take(6))
        // Streams (Q-HC1 A): hardware VP9 above hardware HEVC; libvpx stays below every hardware AV1/VP9/HEVC encoder.
        val stream = VideoCodecChoice.automatic(all, CodecChoiceMode.STREAM, on, session, none)
        assertEquals(listOf(hwAv1, hwVp9, hwHevc, libvpx, hwAvc, libaom), stream.take(6))
        // A phone with hardware HEVC but no hardware VP9 streams and calls with hardware HEVC, never libvpx.
        val noHwVp9 = listOf(hwHevc, hwAvc, libaom, swAv1, libvpx).sortedBy(VideoCodec::selectionPriority)
        assertEquals(hwHevc, VideoCodecChoice.forStream(hwHevc, false, noHwVp9, on, session, none).sent)
        assertEquals(listOf(hwHevc, libvpx, hwAvc), VideoCodecChoice.automatic(noHwVp9, CodecChoiceMode.STREAM, on, session, none).take(3))
        assertEquals(hwHevc, VideoCodecChoice.automatic(noHwVp9, CodecChoiceMode.CALL, on, session, none).first())
        // Legacy (no HC master): libvpx right after the hardware encoders, ahead of libaom and the platform software.
        val legacy = listOf(hwVp9, libaom, swAv1, swVp9, libvpx).sortedBy(VideoCodec::selectionPriority)
        assertEquals(
            listOf(hwVp9, libvpx, libaom, swAv1, swVp9),
            VideoCodecChoice.automatic(legacy, CodecChoiceMode.STREAM, switches(true), session, none),
        )
    }

    @Test
    fun preferLibvpxLetsAVp9OnlyPeerReceiveVp9FromTheA15s() {
        preferLibvpx(true)
        val legacyA15s = listOf(libaom, swAv1, libvpx).sortedBy(VideoCodec::selectionPriority)
        val choice = VideoCodecChoice.forCall(
            requested = legacyA15s.first(), userChoice = false, available = legacyA15s,
            peerDecodeMask = NodeCapabilities.CODEC_VP9, switches = switches(true), familyVp9 = false,
            session = session, excluded = none,
        )
        assertEquals(libvpx, choice.sent)
        assertTrue(VideoCodecChoice.logLine("call", choice, switches(true), NodeCapabilities.CODEC_VP9)
            .endsWith("| prefer_libvpx=true"))
    }

    @Test
    fun existingLogLinesKeepTheirFormatWhenLibvpxIsNotInvolved() {
        val legacy = listOf(libaom, swAv1).sortedBy(VideoCodec::selectionPriority)
        val choice = VideoCodecChoice.forStream(libaom, false, legacy, switches(false), session, none)
        assertFalse("prefer_libvpx" in VideoCodecChoice.logLine("stream", choice, switches(false), null))
    }

    @Test
    fun benchmarkCodecNamesKeepVp9MeaningMediaCodec() {
        assertFalse(BenchmarkSetupRefusal.codecMatches("vp9", isAv1 = false, isVp9 = true, encoderName = VideoCodec.LIBVPX_ENCODER_NAME))
        assertTrue(BenchmarkSetupRefusal.codecMatches("vp9", isAv1 = false, isVp9 = true, encoderName = "c2.android.vp9.encoder"))
        assertTrue(BenchmarkSetupRefusal.codecMatches("libvpx", isAv1 = false, isVp9 = true, encoderName = VideoCodec.LIBVPX_ENCODER_NAME))
        assertTrue(BenchmarkSetupRefusal.codecMatches("libvpx-vp9", isAv1 = false, isVp9 = true, encoderName = VideoCodec.LIBVPX_ENCODER_NAME))
    }

    @Test
    fun encoderBenchResolvesLibvpxOnlyByNameAndBoundsItsSpeed() {
        val candidates = listOf(
            BenchCodecCandidate("c2.android.av1.encoder", EncoderBenchSpec.MIME_AV1, false),
            BenchCodecCandidate("libaom-native", EncoderBenchSpec.MIME_AV1, true),
            BenchCodecCandidate("OMX.MTK.VIDEO.ENCODER.AVC", EncoderBenchSpec.MIME_AVC, false, benchOnly = true),
            BenchCodecCandidate(VideoCodec.LIBVPX_ENCODER_NAME, EncoderBenchSpec.MIME_VP9, false, nativeLibvpx = true),
        )
        assertEquals(VideoCodec.LIBVPX_ENCODER_NAME, EncoderBenchSpec.resolveCodec("libvpx-vp9", candidates).first?.encoderName)
        assertEquals(VideoCodec.LIBVPX_ENCODER_NAME, EncoderBenchSpec.resolveCodec("libvpx", candidates).first?.encoderName)
        assertNull("vp9 means a MediaCodec VP9 encoder", EncoderBenchSpec.resolveCodec("vp9", candidates).first)
        assertEquals("c2.android.av1.encoder", EncoderBenchSpec.resolveCodec("auto", candidates).first?.encoderName)

        val caps = EncoderBenchCaps(
            VideoCodec.LIBVPX_ENCODER_NAME, EncoderBenchSpec.MIME_VP9, hardware = false, nativeLibaom = false,
            supportedModes = setOf(1, 2, 3), defaultMode = 2, sizeAndRateSupported = null, intraRefreshFeature = null,
            nativeLibvpx = true,
        )
        fun request(cpuUsed: Int?) = checkNotNull(
            EncoderBenchSpec.parse(
                mapOf(
                    "test_bench_codec" to "libvpx-vp9", "test_raw_yuv" to "/sdcard/x.yuv", "test_width" to 640,
                    "test_height" to 480, "test_bench_libaom_cpu_used" to cpuUsed,
                ).filterValues { it != null },
            ).request,
        )
        val governed = EncoderBenchSpec.plan(request(null), caps)
        assertTrue(governed.refusals.isEmpty())
        assertEquals("app_governor", governed.effective.toMap()["libaom_cpu_used"])
        assertEquals("off", governed.effective.toMap()["rate_adjuster"])
        assertTrue(EncoderBenchSpec.plan(request(7), caps).refusals.isEmpty())
        assertTrue(EncoderBenchSpec.plan(request(10), caps).refusals.single().startsWith("libaom_cpu_used_out_of_range"))
    }

    @Test
    fun libvpxSpeedTableAndGovernorStayInsideVp9RealtimeSpeeds() {
        val range = NativeVpxBridge.MIN_REALTIME_CPU_USED..NativeVpxBridge.MAX_REALTIME_CPU_USED
        for ((w, h) in listOf(320 to 180, 640 to 360, 640 to 480, 720 to 480, 1280 to 720)) {
            for (fps in listOf(10, 15, 24, 30)) assertTrue(nativeVpxCpuUsed(w, h, fps) in range)
        }
        val governor = NativeEncoderSpeedGovernor(9, 15, range.first, range.last)
        // Overloaded windows at the fastest preset never step past libvpx's fastest real-time speed.
        repeat(5 * NativeEncoderSpeedGovernor.WINDOW_FRAMES) { governor.record(200.0, it * 1_000_000L) }
        assertEquals(9, governor.cpuUsed)
        // A libaom-scale thermal floor (10) is held at libvpx's fastest speed.
        val cool = NativeEncoderSpeedGovernor(6, 15, range.first, range.last)
        assertEquals(9, cool.applyThermalFloor(10))
    }
}
