package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderPolicyEntriesTest {
    private val mtk = DeviceTraits(
        "mt6893", "k6893v1_64", 34, "Xiaomi", socManufacturer = "MediaTek",
        codecComponents = listOf("c2.mtk.vp9.decoder", "c2.mtk.av1.decoder", "c2.mtk.avc.decoder", "c2.android.vp9.decoder"),
    )
    private val exynos = DeviceTraits(
        "s5e9945", "erd9945", 36, "samsung",
        codecComponents = listOf("c2.exynos.vp9.decoder", "c2.android.vp9.decoder"),
    )

    @After
    fun restore() {
        DebugTunables.devicePolicyOff = emptySet()
    }

    @Test
    fun mediaTekCodec2DecodersAreReleasedNotFlushedAndOtherComponentsKeepFlush() {
        for (name in listOf("c2.mtk.vp9.decoder", "c2.mtk.av1.decoder", "c2.mtk.avc.decoder", "c2.mtk.hevc.decoder")) {
            assertTrue(name, CodecPolicy.releaseInsteadOfFlush(name, mtk))
            assertTrue("$name without traits", CodecPolicy.releaseInsteadOfFlush(name))
        }
        for (name in listOf("c2.android.vp9.decoder", "c2.exynos.vp9.decoder", "c2.qti.vp9.decoder", "OMX.MTK.VIDEO.DECODER.VP9",
            "OMX.Exynos.VP9.Decoder", "c2.mtk.avc.encoder")) {
            assertFalse(name, CodecPolicy.releaseInsteadOfFlush(name, mtk))
            assertFalse(name, CodecPolicy.releaseInsteadOfFlush(name, exynos))
        }
    }

    @Test
    fun qualcommDecodersGetQualcommsLowLatencyKeyAndOtherComponentsDoNot() {
        // The ASUS (msmnile, Android 11): OMX decoders, no getSupportedVendorParameters (API 31).
        val asus = DeviceTraits(
            "qcom", "msmnile", 30, "asus",
            codecComponents = listOf("OMX.qcom.video.decoder.avc", "OMX.qcom.video.decoder.hevc", "c2.android.vp9.decoder"),
        )
        val qti = DecoderLowLatencyLadder.VENDOR_QTI
        for (name in listOf("OMX.qcom.video.decoder.avc", "OMX.qcom.video.decoder.hevc", "OMX.qcom.video.decoder.vp9", "c2.qti.hevc.decoder")) {
            assertEquals(name, qti, CodecPolicy.vendorLowLatencyKey(name, asus))
        }
        for (name in listOf("OMX.qcom.video.encoder.avc", "c2.qti.avc.encoder", "c2.android.vp9.decoder", "OMX.Exynos.hevc.dec",
            "OMX.MTK.VIDEO.DECODER.AVC")) {
            assertNull(name, CodecPolicy.vendorLowLatencyKey(name, asus))
        }
        // Vendor extensions reach OMX components from API 26 (ACodec setVendorParameters).
        assertNull(CodecPolicy.vendorLowLatencyKey("OMX.qcom.video.decoder.avc", DeviceTraits("qcom", "msm8996", 25, "x")))
        DebugTunables.devicePolicyOff = setOf(DecoderPolicyEntries.QCOM_DECODER_VENDOR_LOW_LATENCY.id)
        // test_device_policy_off is DEBUG-only: a release build always keeps every entry.
        assertEquals(if (BuildConfig.DEBUG) null else qti, CodecPolicy.vendorLowLatencyKey("OMX.qcom.video.decoder.avc", asus))
    }

    @Test
    fun theReleaseRuleHasAnOffSwitchAndAnAllSwitch() {
        val entry = DecoderPolicyEntries.C2_MTK_RELEASE_INSTEAD_OF_FLUSH
        assertEquals("test_device_policy_off=c2-mtk-release-instead-of-flush", entry.offSwitch)
        DebugTunables.devicePolicyOff = setOf(entry.id)
        if (!BuildConfig.DEBUG) {
            // test_device_policy_off is DEBUG-only: a release build always keeps every entry.
            assertTrue(CodecPolicy.releaseInsteadOfFlush("c2.mtk.vp9.decoder", mtk))
            return
        }
        assertFalse(CodecPolicy.releaseInsteadOfFlush("c2.mtk.vp9.decoder"))
        assertFalse(CodecPolicy.releaseInsteadOfFlush("c2.mtk.vp9.decoder", mtk))
        DebugTunables.devicePolicyOff = setOf("all")
        assertFalse(CodecPolicy.releaseInsteadOfFlush("c2.mtk.vp9.decoder", mtk))
        DebugTunables.devicePolicyOff = emptySet()
        assertTrue(CodecPolicy.releaseInsteadOfFlush("c2.mtk.vp9.decoder", mtk))
    }

    @Test
    fun theRecoveryRebuildKeepsSizeAndNewestConfigurationAndIsNotASizeChange() {
        val csd = listOf(byteArrayOf(1, 2, 3))
        val tracker = DecoderFormatTracker(StreamProtocol.CODEC_VP9, csd, 640, 480)
        val plan = tracker.recoveryRebuild("release_not_flush")
        assertEquals(640, plan.width)
        assertEquals(480, plan.height)
        assertFalse(plan.sizeChanged)
        assertFalse(plan.configChanged)
        assertEquals("release_not_flush", plan.reason)
        assertEquals(1, plan.codecSpecificData.size)
        assertTrue(plan.codecSpecificData[0].contentEquals(csd[0]))
        // A copy: the tracker's own configuration is not shared with the decoder that is created.
        plan.codecSpecificData[0][0] = 9
        assertEquals(1, tracker.currentCodecSpecificData()[0][0].toInt())
    }

    @Test
    fun watchdogAndBoundedReleaseAreOnByDefaultAndSwitchable() {
        assertTrue(CodecPolicy.stallWatchdogEnabled(exynos))
        assertTrue(CodecPolicy.stallWatchdogEnabled())
        assertEquals(5_000L, CodecPolicy.boundedReleaseTimeoutMs(exynos))
        assertEquals(5_000L, CodecPolicy.boundedReleaseTimeoutMs())
        if (!BuildConfig.DEBUG) {
            // test_device_policy_off is DEBUG-only: a release build always keeps every entry.
            DebugTunables.devicePolicyOff = setOf("all")
            assertTrue(CodecPolicy.stallWatchdogEnabled(exynos))
            assertEquals(5_000L, CodecPolicy.boundedReleaseTimeoutMs(exynos))
            return
        }
        DebugTunables.devicePolicyOff = setOf("decoder-no-output-watchdog")
        assertFalse(CodecPolicy.stallWatchdogEnabled(exynos))
        assertEquals(5_000L, CodecPolicy.boundedReleaseTimeoutMs(exynos))
        DebugTunables.devicePolicyOff = setOf("decoder-bounded-release")
        assertNull(CodecPolicy.boundedReleaseTimeoutMs(exynos))
        assertNull(CodecPolicy.boundedReleaseTimeoutMs())
        assertTrue(CodecPolicy.stallWatchdogEnabled(exynos))
    }

    @Test
    fun experimentalArmsAndProposalsAreInertInTheResolverButReportedWhenTheyMatch() {
        val resolved = DeviceFamilyPolicy.resolve(mtk, emptySet())
        for (id in listOf("decoder-stall-recovery-arm", "decoder-low-latency-ladder-arm", "decoder-realtime-priority-arm")) {
            assertFalse(id, resolved.isActive(id))
            assertTrue(id, DecoderPolicyEntries.entries.single { it.id == id }.proposed)
            assertEquals(id, PolicyValidation.EXPERIMENTAL, DecoderPolicyEntries.entries.single { it.id == id }.validation)
        }
        assertTrue(resolved.isActive("c2-mtk-release-instead-of-flush"))
        assertTrue(resolved.componentEffects<ReleaseCodecInsteadOfFlush>("c2.mtk.vp9.decoder").isNotEmpty())
        assertTrue(resolved.matchingProposals.any { it.id == "mediatek-decoder-crop-and-pts-quirks" })
        assertFalse(DeviceFamilyPolicy.resolve(exynos, emptySet()).matchingProposals.any { it.id == "mediatek-decoder-crop-and-pts-quirks" })
        val tensor = DeviceTraits("tensor", "slider", 34, "Google", socManufacturer = "Google", socModel = "Tensor G2")
        assertTrue(DeviceFamilyPolicy.resolve(tensor, emptySet()).matchingProposals.any { it.id == "tensor-av1-decoder-colour-standard-misreport" })
        assertFalse(DeviceFamilyPolicy.resolve(tensor.copy(sdkInt = 32), emptySet()).matchingProposals.any { it.id == "tensor-av1-decoder-colour-standard-misreport" })
        assertFalse(resolved.matchingProposals.any { it.id == "tensor-av1-decoder-colour-standard-misreport" })
    }

    @Test
    fun theEntriesAreInTheOneRegistryWithCodecArea() {
        val registry = DeviceFamilyPolicy.entries.associateBy { it.id }
        for (entry in DecoderPolicyEntries.entries) {
            assertTrue(entry.id, registry.containsKey(entry.id))
            assertEquals(entry.id, PolicyArea.CODEC, entry.area)
            assertTrue(entry.id, entry.fallback.isNotBlank() && entry.confidenceNote.isNotBlank())
            assertTrue(entry.id, entry.sources.isNotEmpty() && entry.sourceStatus != PolicySourceStatus.EVIDENCE_ONLY)
        }
        // The existing codec rules stay.
        for (id in listOf("decoder-init-fallback", "codec-exception-recoverable-reinit", "aosp-codec2-opus-decoder-first",
            "c2-android-vp9-decoder-in-place-recovery")) {
            assertTrue(id, registry.containsKey(id))
        }
        assertEquals(DecoderPolicyEntries.entries.map { it.id }.toSet().size, DecoderPolicyEntries.entries.size)
    }
}
