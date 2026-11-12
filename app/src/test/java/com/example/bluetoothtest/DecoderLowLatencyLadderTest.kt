package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderLowLatencyLadderTest {
    private val ll = DecoderLowLatencyLadder.KEY_LOW_LATENCY
    private val pr = DecoderLowLatencyLadder.KEY_PRIORITY

    @After
    fun restoreSwitches() {
        DecoderSwitches.stallRecovery = true
        DecoderSwitches.lowLatencyLadder = false
        DecoderSwitches.realtimePriority = false
    }

    private fun plan(
        name: String,
        feature: Boolean = true,
        ladder: Boolean = false,
        priority: Boolean = false,
        vendor: Collection<String>? = null,
    ) = DecoderLowLatencyLadder.plan(name, feature, preferLowLatency = true, ladder = ladder, priority = priority, vendorParameters = vendor)

    @Test
    fun aPolicyVendorKeyReplacesTheMissingStandardKeyAndFallsBackToPlain() {
        val qti = DecoderLowLatencyLadder.VENDOR_QTI
        fun qcom(feature: Boolean, prefer: Boolean = true, ladder: Boolean = false) = DecoderLowLatencyLadder.plan(
            "OMX.qcom.video.decoder.avc", feature, preferLowLatency = prefer, ladder = ladder, priority = false,
            vendorParameters = null, defaultVendorKey = qti,
        )
        val rungs = qcom(feature = false)
        assertEquals(listOf(mapOf(qti to 1), emptyMap()), rungs.map { it.keys })
        assertEquals(listOf("vendor($qti)", "plain"), rungs.map { it.label })
        assertTrue(DecoderLowLatencyLadder.requestsLowLatency(rungs[0].keys))
        assertFalse(DecoderLowLatencyLadder.requestsLowLatency(rungs[1].keys))
        // A component that declares FEATURE_LowLatency keeps the standard key alone.
        assertEquals(listOf(mapOf(ll to 1)), qcom(feature = true).map { it.keys })
        // The receiver's low-latency preference off: nothing is requested.
        assertEquals(listOf(emptyMap<String, Int>()), qcom(feature = false, prefer = false).map { it.keys })
        // With the Experimental ladder the policy key leads too, ending at the plain format.
        assertEquals(listOf(mapOf(qti to 1), emptyMap()), qcom(feature = false, ladder = true).map { it.keys })
    }

    @Test
    fun withEveryArmOffTheFormatIsTheFormerSingleAttempt() {
        assertEquals(listOf(mapOf(ll to 1)), plan("c2.exynos.vp9.decoder").map { it.keys })
        assertEquals(listOf(emptyMap<String, Int>()), plan("c2.qti.vp9.decoder", feature = false).map { it.keys })
        // Vendor parameters and the MediaTek keys are ignored while the ladder is off.
        assertEquals(1, plan("OMX.MTK.VIDEO.DECODER.VP9", feature = false, vendor = listOf(DecoderLowLatencyLadder.VENDOR_QTI)).size)
        assertTrue(plan("OMX.MTK.VIDEO.DECODER.VP9", feature = false)[0].keys.isEmpty())
    }

    @Test
    fun preferLowLatencyFalseKeepsTheKeyOut() {
        val rungs = DecoderLowLatencyLadder.plan("c2.exynos.vp9.decoder", true, preferLowLatency = false, ladder = false, priority = false, vendorParameters = null)
        assertEquals(listOf(emptyMap<String, Int>()), rungs.map { it.keys })
    }

    @Test
    fun ladderSetsOnlyTheVendorKeysTheComponentLists() {
        val listed = listOf(DecoderLowLatencyLadder.VENDOR_QTI, "vendor.qti-ext-dec-picture-order.enable", "vendor.other.thing")
        val rungs = plan("c2.qti.avc.decoder", ladder = true, vendor = listed)
        assertEquals(
            listOf(mapOf(ll to 1, DecoderLowLatencyLadder.VENDOR_QTI to 1), mapOf(ll to 1), emptyMap()),
            rungs.map { it.keys },
        )
        assertEquals(listOf("low-latency+vendor(${DecoderLowLatencyLadder.VENDOR_QTI})", "low-latency", "plain"), rungs.map { it.label })
        // The unknown and the picture-order keys are never set.
        assertFalse(rungs.any { "vendor.other.thing" in it.keys || "vendor.qti-ext-dec-picture-order.enable" in it.keys })
    }

    @Test
    fun exynosAndGenericVendorKeysAreRecognised() {
        val rungs = plan("c2.exynos.vp9.decoder", ladder = true,
            vendor = listOf(DecoderLowLatencyLadder.VENDOR_RTC, DecoderLowLatencyLadder.VENDOR_GENERIC))
        assertEquals(
            mapOf(ll to 1, DecoderLowLatencyLadder.VENDOR_RTC to 1, DecoderLowLatencyLadder.VENDOR_GENERIC to 1),
            rungs.first().keys,
        )
    }

    @Test
    fun withoutVendorParametersTheLadderAddsNothingExceptOnMediaTek() {
        // Nothing to add: the standard key, then the plain format as the last resort.
        assertEquals(listOf(mapOf(ll to 1), emptyMap()), plan("c2.qti.avc.decoder", ladder = true, vendor = null).map { it.keys })
        val mtk = plan("OMX.MTK.VIDEO.DECODER.AVC", feature = false, ladder = true, vendor = null)
        assertEquals(
            listOf(mapOf("vdec-lowlatency" to 1, "vdec-no-record" to 1, "use-clearmotion-mode" to 0), emptyMap()),
            mtk.map { it.keys },
        )
        assertTrue(DecoderLowLatencyLadder.isMediaTekDecoder("c2.mtk.vp9.decoder"))
        assertFalse(DecoderLowLatencyLadder.isMediaTekDecoder("c2.android.vp9.decoder"))
    }

    @Test
    fun priorityIsTheNewestKeyAndIsDroppedFirst() {
        val rungs = plan("c2.exynos.vp9.decoder", priority = true)
        assertEquals(listOf(mapOf(ll to 1, pr to 0), mapOf(ll to 1), emptyMap()), rungs.map { it.keys })
        val all = plan("c2.qti.avc.decoder", ladder = true, priority = true, vendor = listOf(DecoderLowLatencyLadder.VENDOR_QTI))
        assertEquals(
            listOf(
                mapOf(ll to 1, DecoderLowLatencyLadder.VENDOR_QTI to 1, pr to 0),
                mapOf(ll to 1, DecoderLowLatencyLadder.VENDOR_QTI to 1),
                mapOf(ll to 1),
                emptyMap(),
            ),
            all.map { it.keys },
        )
    }

    @Test
    fun operatingRateIsNeverSetAndPriorityAloneIsOne() {
        val all = plan("c2.qti.avc.decoder", ladder = true, priority = true,
            vendor = DecoderLowLatencyLadder.KNOWN_VENDOR_KEYS)
        assertTrue(all.none { "operating-rate" in it.keys })
        assertEquals(mapOf(pr to 0), plan("c2.android.vp9.decoder", feature = false, priority = true).first().keys)
    }

    @Test
    fun logsOnlyTheInterestingVendorParameters() {
        assertEquals(
            listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable"),
            DecoderLowLatencyLadder.interestingVendorParameters(
                listOf("vendor.qti-ext-dec-picture-order.enable", "vendor.qti-ext-dec-low-latency.enable", "vendor.a.b"),
            ),
        )
        assertEquals(emptyList<String>(), DecoderLowLatencyLadder.interestingVendorParameters(null))
    }

    @Test
    fun stallRecoveryIsOnAfterAPlainLaunchAndTheOtherArmsStayOff() {
        DecoderSwitches.applyExtras { _, default -> default }
        assertTrue(DecoderSwitches.stallRecovery)
        assertFalse(DecoderSwitches.lowLatencyLadder)
        assertFalse(DecoderSwitches.realtimePriority)
    }

    @Test
    fun theFalseLaunchExtraIsTheDebugOnlyControlArmForStallRecovery() {
        DecoderSwitches.applyExtras { key, default -> if (key == "test_decoder_stall_recovery") false else default }
        // A release build ignores the extra: recovery stays on there.
        assertEquals(!BuildConfig.DEBUG, DecoderSwitches.stallRecovery)
        DecoderSwitches.applyExtras { _, default -> default }
        assertTrue(DecoderSwitches.stallRecovery)
    }

    @Test
    fun theExperimentalArmsFollowTheLaunchExtrasInDebugOnly() {
        DecoderSwitches.applyExtras { key, default ->
            when (key) {
                "test_decoder_low_latency_ladder", "test_decoder_priority" -> true
                else -> default
            }
        }
        // Unit tests run the debug variant, where the DEBUG-only arms can be set.
        assertEquals(BuildConfig.DEBUG, DecoderSwitches.lowLatencyLadder)
        assertEquals(BuildConfig.DEBUG, DecoderSwitches.realtimePriority)
        DecoderSwitches.applyExtras { _, default -> default }
        assertFalse(DecoderSwitches.lowLatencyLadder || DecoderSwitches.realtimePriority)
        assertTrue(DecoderSwitches.stallRecovery)
    }
}
