package com.example.bluetoothtest

import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design decision 2026-10-28, switch `codec_power_aware`: battery saver or a warm phone at session start makes Automatic
 * prefer a hardware encoder over a software first choice. Covers power state x phone (with and without hardware encoders)
 * x call/stream x the DEBUG control arm, plus the rules it must never override.
 */
class CodecPowerAwareTest {
    @After
    fun reset() {
        VideoCodecSwitches.preferLibvpxOverride = null
        CodecPowerAware.enabled = true
    }

    private val none: (VideoCodec) -> Boolean = { false }
    private val session = CodecSession(640, 480, 15)
    private val allDecode = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1 or
        codecCapabilityMask(StreamProtocol.CODEC_H264) or codecCapabilityMask(StreamProtocol.CODEC_HEVC)

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
    private val hwAv1 = codec("c2.vendor.av1.encoder", StreamProtocol.CODEC_AV1, true)
    private val hwHevc = codec("c2.vendor.hevc.encoder", StreamProtocol.CODEC_HEVC, true)
    private val hwVp9 = codec("c2.vendor.vp9.encoder", StreamProtocol.CODEC_VP9, true)
    private val hwAvc = codec("c2.vendor.avc.encoder", StreamProtocol.CODEC_H264, true)

    private fun phone(vararg codecs: VideoCodec) = codecs.toList().sortedBy(VideoCodec::selectionPriority)

    /** Hardware H.264 only (the A15s census). */
    private fun avcOnly() = phone(hwAvc, libaom, swAv1, libvpx)
    /** Hardware HEVC and H.264, no hardware VP9. */
    private fun hevcNoVp9() = phone(hwHevc, hwAvc, libaom, swAv1, libvpx)
    /** Every hardware encoder. */
    private fun fullHardware() = phone(hwAv1, hwHevc, hwVp9, hwAvc, libaom, swAv1, libvpx)
    /** No hardware encoder at all. */
    private fun softwareOnly() = phone(libaom, swAv1, libvpx)

    private fun switches(power: CodecPowerState, aware: Boolean = true) =
        CodecChoiceSwitches(hevc = true, h264 = true, preferLibvpx = true, powerAware = aware, power = power)

    private fun call(available: List<VideoCodec>, s: CodecChoiceSwitches, peer: Int = allDecode, userChoice: Boolean = false, requested: VideoCodec = available.first()) =
        VideoCodecChoice.forCall(requested, userChoice, available, peer, s, familyVp9 = false, session = session, excluded = none)

    private fun stream(available: List<VideoCodec>, s: CodecChoiceSwitches) =
        VideoCodecChoice.forStream(available.first(), false, available, s, session, none)

    @Test
    fun stateReadsBatterySaverFirstThenHeatAtLight() {
        assertEquals(CodecPowerState.NORMAL, CodecPowerAware.state(false, PowerManager.THERMAL_STATUS_NONE))
        assertEquals(CodecPowerState.WARM, CodecPowerAware.state(false, PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(CodecPowerState.WARM, CodecPowerAware.state(false, PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(CodecPowerState.SAVER, CodecPowerAware.state(true, PowerManager.THERMAL_STATUS_NONE))
        assertEquals(CodecPowerState.SAVER, CodecPowerAware.state(true, PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test
    fun everyPowerStatePhoneModeAndArm() {
        data class Case(val name: String, val phone: () -> List<VideoCodec>, val normalCall: VideoCodec, val normalStream: VideoCodec, val constrainedCall: VideoCodec, val constrainedStream: VideoCodec)
        val cases = listOf(
            // Software first choice with hardware H.264 available: the rule swaps libvpx for hardware H.264.
            Case("avc_only", ::avcOnly, libvpx, libvpx, hwAvc, hwAvc),
            // Hardware HEVC ranks above libvpx in calls and streams: a hardware first choice, never touched.
            Case("hevc_no_vp9", ::hevcNoVp9, hwHevc, hwHevc, hwHevc, hwHevc),
            // A hardware first choice is never touched.
            Case("full_hardware", ::fullHardware, hwAv1, hwAv1, hwAv1, hwAv1),
            // Nothing to prefer: software stays.
            Case("software_only", ::softwareOnly, libvpx, libvpx, libvpx, libvpx),
        )
        for (case in cases) {
            for (power in CodecPowerState.entries) {
                for (aware in listOf(true, false)) {
                    val constrained = aware && power != CodecPowerState.NORMAL
                    val s = switches(power, aware)
                    val label = "${case.name} power=${power.label} aware=$aware"
                    val c = call(case.phone(), s)
                    val st = stream(case.phone(), s)
                    assertEquals("call $label", if (constrained) case.constrainedCall else case.normalCall, c.sent)
                    assertEquals("stream $label", if (constrained) case.constrainedStream else case.normalStream, st.sent)
                    val callChanged = case.constrainedCall != case.normalCall && constrained
                    val streamChanged = case.constrainedStream != case.normalStream && constrained
                    assertEquals("call applied $label", callChanged, c.powerApplied)
                    assertEquals("stream applied $label", streamChanged, st.powerApplied)
                    if (callChanged) assertEquals(CodecPowerAware.reason(c.sent!!), c.reason)
                    if (streamChanged) assertEquals(CodecPowerAware.reason(st.sent!!), st.reason)
                }
            }
        }
    }

    @Test
    fun reasonsAndLogNameThePowerState() {
        val c = call(avcOnly(), switches(CodecPowerState.SAVER))
        assertEquals("power_prefers_hw_avc", c.reason)
        // The fallback list keeps libvpx right behind hardware H.264, then libaom as the last resort.
        assertEquals(listOf(hwAvc, libvpx, libaom), c.ordered.take(3))
        val line = VideoCodecChoice.logLine("call", c, switches(CodecPowerState.SAVER), allDecode)
        assertTrue(line, "reason=power_prefers_hw_avc" in line && "power=saver" in line && "codec_power_aware=true" in line)
        val warmStream = stream(avcOnly(), switches(CodecPowerState.WARM))
        assertEquals(hwAvc, warmStream.sent)
        assertEquals("power_prefers_hw_avc", warmStream.reason)
        assertTrue("power=warm" in VideoCodecChoice.logLine("stream", warmStream, switches(CodecPowerState.WARM), null))
        val normal = call(avcOnly(), switches(CodecPowerState.NORMAL))
        assertTrue("power=normal" in VideoCodecChoice.logLine("call", normal, switches(CodecPowerState.NORMAL), allDecode))
    }

    @Test
    fun ruleRunsAfterThePeerFilter() {
        // The peer cannot decode HEVC: the call's first decodable choice is libvpx, so a warm phone sends hardware H.264.
        val noHevc = allDecode and codecCapabilityMask(StreamProtocol.CODEC_HEVC).inv()
        assertEquals(libvpx, call(hevcNoVp9(), switches(CodecPowerState.NORMAL), noHevc).sent)
        assertEquals(hwAvc, call(hevcNoVp9(), switches(CodecPowerState.WARM), noHevc).sent)
        // A VP9-only peer: no hardware encoder it decodes, so libvpx stays.
        assertEquals(libvpx, call(avcOnly(), switches(CodecPowerState.SAVER), NodeCapabilities.CODEC_VP9).sent)
    }

    @Test
    fun neverOverridesAManualChoiceFamilyVp9OrPeerBit7() {
        val saver = switches(CodecPowerState.SAVER)
        assertEquals(libvpx, call(avcOnly(), saver, userChoice = true, requested = libvpx).sent)
        val bit7 = allDecode or NodeCapabilities.DECODE_PREFERS_VP9
        val viaBit7 = call(avcOnly(), saver, bit7)
        assertEquals(libvpx, viaBit7.sent)
        assertEquals("peer_prefers_vp9", viaBit7.reason)
        assertFalse(viaBit7.powerApplied)
        // The family VP9 rule swaps a hardware HEVC first choice for VP9 (libvpx here); saver does not undo it.
        val family = VideoCodecChoice.forCall(hwHevc, false, phone(hwHevc, libaom, libvpx), allDecode, saver, familyVp9 = true, session = session, excluded = none)
        assertEquals(libvpx, family.sent)
        assertEquals("family_vp9_compatibility", family.reason)
        assertFalse(family.powerApplied)
    }

    @Test
    fun aPolicyExcludedHardwareEncoderIsNeverPromoted() {
        val excludedAvc: (VideoCodec) -> Boolean = { it == hwAvc }
        val choice = VideoCodecChoice.forCall(libvpx, false, avcOnly(), allDecode, switches(CodecPowerState.SAVER), false, session, excludedAvc)
        assertEquals(libvpx, choice.sent)
        assertFalse(choice.powerApplied)
    }

    @Test
    fun thePickerAndSummariesKeepTheNormalOrder() {
        // Outside a session start the switches carry the normal state, so the picker never moves with the battery.
        assertEquals(CodecPowerState.NORMAL, CodecChoiceSwitches(hevc = true, h264 = true).power)
        assertEquals(libvpx, VideoCodecChoice.pickerOrder(avcOnly(), switches(CodecPowerState.SAVER), session, none).first())
    }

    @Test
    fun fullAutomaticOrderHolds() {
        val s = switches(CodecPowerState.NORMAL)
        assertEquals(
            listOf(hwAv1, hwHevc, hwVp9, libvpx, hwAvc, libaom),
            VideoCodecChoice.automatic(fullHardware(), CodecChoiceMode.CALL, s, session, none).take(6),
        )
        assertEquals(
            listOf(hwAv1, hwVp9, hwHevc, libvpx, hwAvc, libaom),
            VideoCodecChoice.automatic(fullHardware(), CodecChoiceMode.STREAM, s, session, none).take(6),
        )
        // libaom is last among the encoders Automatic uses: only when nothing above can start.
        val callOrder = VideoCodecChoice.forCall(libvpx, false, fullHardware(), allDecode, s, false, session, none).ordered
        assertEquals(listOf(hwAv1, hwHevc, hwVp9, libvpx, hwAvc), callOrder.take(5))
        assertTrue(callOrder.indexOf(libaom) > callOrder.indexOf(hwAvc))
    }

    @Test
    fun controlArmOffInDebugKeepsThePlainOrder() {
        CodecPowerAware.enabled = false
        if (!BuildConfig.DEBUG) {
            // A release build ignores the control arm: the shipped constant stays in force.
            assertEquals(ConnectivityFeatureDefaults.CODEC_POWER_AWARE, CodecPowerAware.enabled)
            return
        }
        assertFalse(CodecPowerAware.enabled)
        assertEquals(null, CodecPowerAware.reorder(avcOnly(), CodecPowerState.SAVER, CodecPowerAware.enabled, none))
    }
}
