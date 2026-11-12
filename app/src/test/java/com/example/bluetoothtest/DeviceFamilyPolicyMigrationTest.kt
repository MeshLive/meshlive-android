package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry migration changed no decision: every former ad-hoc check, run through
 * [PreRegistryPolicyOracle], agrees with the registry-backed function for the benchmark phones and
 * for edge fingerprints, over every transport and a grid of call settings and peer limits.
 */
class DeviceFamilyPolicyMigrationTest {
    private val known = KnownDeviceTraits.load()

    /** Edge cases around the former matchers: API 32/33, case, board prefixes, non-qcom boards. */
    private val synthetic = listOf(
        DeviceTraits("qcom", "msmnile", 32),
        DeviceTraits("qcom", "msmnile", 33),
        DeviceTraits("QCOM", "sm8150-ac", 32),
        DeviceTraits("Qcom", "SM8150", 29),
        DeviceTraits("qcom", "Msmnile", 31),
        DeviceTraits("qcom", "atoll", 30),
        DeviceTraits("qcom", "curtana", 30),
        DeviceTraits("QCOM", "sm7125-x", 32),
        DeviceTraits("qcom", "atoll", 33),
        DeviceTraits("qcom", "kalama", 33),
        DeviceTraits("qcom", "taro", 30),
        DeviceTraits("qcom", "lito", 29),
        DeviceTraits("qcom", "", 32),
        DeviceTraits("qcom", "msmnile", 36, manufacturer = "asus", socManufacturer = "QTI", socModel = "SM8150"),
        DeviceTraits("exynos9611", "atoll", 31),
        DeviceTraits("mt6765", "msmnile", 29),
        DeviceTraits("", "", 29),
        DeviceTraits("s5e9945", "erd9945", 36),
    )

    private val fingerprints: List<DeviceTraits> = known.map { it.traits } + synthetic

    /**
     * Each fingerprint and its experimental legacy-relief call view, as (before, after): before the
     * registry the relief cleared Build.HARDWARE; now it switches off the LEGACY_QUALCOMM_CALL entries.
     */
    private val callViews: List<Pair<DeviceTraits, DeviceTraits>> = fingerprints.flatMap { fingerprint ->
        listOf(false, true).map { relief ->
            PreRegistryPolicyOracle.privateCallStackFingerprint(fingerprint, relief) to
                privateCallStackFingerprint(fingerprint, relief)
        }
    }
    private val transports = BluetoothTransportOption.entries
    private val peerLimits = listOf(null, 0, 1, 109, 110, 111, 159, 160, 161, 249, 250, 251, 1_200)

    @After
    fun restoreSwitches() {
        DebugTunables.devicePolicyOff = emptySet()
    }

    @Test
    fun benchmarkPhonesLoadFromTheDeviceRegistryAndCapabilityFiles() {
        assertEquals(listOf("a15s", "a55", "asus", "m31", "realme", "s22", "s24", "xiaomi"), known.map { it.alias })
        val asus = known.single { it.alias == "asus" }.traits
        assertEquals(30, asus.sdkInt)
        assertEquals(SocFamily.QUALCOMM, asus.socFamily)
        assertEquals(SocFamily.MEDIATEK, known.single { it.alias == "a15s" }.traits.socFamily)
        assertEquals(SocFamily.EXYNOS, known.single { it.alias == "s24" }.traits.socFamily)
        assertEquals(OemSkin.COLOROS, known.single { it.alias == "a15s" }.traits.oemSkin)
        assertEquals(OemSkin.MIUI_HYPEROS, known.single { it.alias == "xiaomi" }.traits.oemSkin)
    }

    @Test
    fun callVideoSettingsMatchThePreRegistryDecisions() {
        val resolutions = listOf(320 to 240, 352 to 288, 426 to 240, 427 to 240, 480 to 360, 640 to 360, 640 to 480, 1280 to 720)
        var compared = 0
        for ((before, after) in callViews + listOf<Pair<DeviceTraits?, DeviceTraits?>>(null to null)) for (transport in transports) {
            for ((width, height) in resolutions) for (fps in listOf(10, 15, 20, 24, 30)) {
                for (bitRate in listOf(24_000, 100_000, 110_000, 150_000, 160_000, 200_000, 250_000, 600_000, 1_100_000)) {
                    for (preserve in listOf(false, true)) {
                        val selected = VideoSettings(VideoResolution(width, height, "${height}p"), fps, bitRate, 250)
                        assertEquals(
                            "$after $transport $selected preserve=$preserve",
                            PreRegistryPolicyOracle.privateCallVideoSettings(selected, transport, before, preserve),
                            privateCallVideoSettings(selected, transport, after, preserve),
                        )
                        compared++
                    }
                }
            }
        }
        assertTrue(compared > 60_000)
    }

    @Test
    fun callCapabilityAudioQueueFlushAndPlaybackMatchThePreRegistryDecisions() {
        for ((before, after) in callViews) for (transport in transports) {
            val label = "$after $transport"
            assertEquals(label, PreRegistryPolicyOracle.privateCallDecodeRestricted(before, transport),
                privateCallDecodeRestricted(after, transport))
            for (mask in 0..3) {
                assertEquals(label, PreRegistryPolicyOracle.privateCallDecodeCodecMask(before, transport, mask),
                    privateCallDecodeCodecMask(after, transport, mask))
            }
            for (default in listOf(100, 150, 1_100, 1_500)) {
                assertEquals(label, PreRegistryPolicyOracle.privateCallCapabilityMaxBitrateKbps(transport, before, default),
                    privateCallCapabilityMaxBitrateKbps(transport, after, default))
            }
            for (peer in peerLimits) {
                assertEquals("$label peer=$peer", PreRegistryPolicyOracle.privateCallAudioBitRate(transport, before, peer),
                    privateCallAudioBitRate(transport, after, peer))
                assertEquals("$label peer=$peer", PreRegistryPolicyOracle.privateCallMediaFlushIntervalMs(transport, before, peer),
                    privateCallMediaFlushIntervalMs(transport, after, peer))
                for (profile in PlaybackDelayProfile.entries) for (lowDelay in listOf(false, true)) {
                    assertEquals("$label peer=$peer $profile lowDelay=$lowDelay",
                        PreRegistryPolicyOracle.privateCallPlaybackDelayProfile(profile, transport, before, peer, lowDelay),
                        privateCallPlaybackDelayProfile(profile, transport, after, peer, lowDelay))
                }
                for (hops in 0..2) {
                    for ((old, new) in listOf<Pair<DeviceTraits?, DeviceTraits?>>(before to after, null to null)) {
                        assertEquals("$label peer=$peer hops=$hops",
                            PreRegistryPolicyOracle.pairPrivateCallAudioForRoute(transport, hops, old, peer),
                            pairPrivateCallAudioForRoute(transport, hops, new, peer))
                        for (lowDelay in listOf(false, true)) {
                            assertEquals("$label peer=$peer hops=$hops lowDelay=$lowDelay",
                                PreRegistryPolicyOracle.privateCallAudioBatchSizeForRoute(transport, hops, old, peer, lowDelay),
                                privateCallAudioBatchSizeForRoute(transport, hops, new, peer, lowDelay))
                        }
                    }
                }
                for (fps in listOf(5, 15, 30)) {
                    val settings = VideoSettings(VideoResolution(640, 480, "480p"), fps, 600_000, 250)
                    assertEquals(PreRegistryPolicyOracle.privateCallVideoSettingsForPeer(settings, transport, peer),
                        privateCallVideoSettingsForPeer(settings, transport, peer))
                }
            }
            for (fps in listOf(5, 15, 30)) for (profile in PlaybackDelayProfile.entries) {
                for ((old, new) in listOf<Pair<DeviceTraits?, DeviceTraits?>>(before to after, null to null)) {
                    assertEquals("$label $fps $profile",
                        PreRegistryPolicyOracle.privateCallVideoQueueCapacity(fps, profile, transport, old),
                        privateCallVideoQueueCapacity(fps, profile, transport, new))
                }
            }
        }
    }

    @Test
    fun codecDecoderAndScanDecisionsMatchThePreRegistryDecisions() {
        val codecs = listOf(
            codec("c2.exynos.av1.encoder", StreamProtocol.CODEC_AV1, hardware = true),
            codec("c2.android.av1.encoder", StreamProtocol.CODEC_AV1),
            codec("libaom-native", StreamProtocol.CODEC_AV1, backend = VideoEncoderBackend.NATIVE_LIBAOM),
            codec("c2.exynos.vp9.encoder", StreamProtocol.CODEC_VP9, hardware = true),
            codec("OMX.Exynos.VP9.Encoder", StreamProtocol.CODEC_VP9, hardware = true),
            codec("c2.android.vp9.encoder", StreamProtocol.CODEC_VP9),
        )
        val availableSets = listOf(codecs, codecs.filter { it.protocolId == StreamProtocol.CODEC_AV1 }, codecs.drop(3), emptyList())
        for ((before, after) in callViews) {
            for (transport in transports) for (available in availableSets) for (selected in codecs) {
                assertEquals("$after $transport ${selected.encoderName}",
                    PreRegistryPolicyOracle.privateCallCodec(selected, available, before, transport),
                    privateCallCodec(selected, available, after, transport))
            }
            for (codecId in listOf(StreamProtocol.CODEC_VP9, StreamProtocol.CODEC_AV1, 99)) {
                assertEquals("$after $codecId", PreRegistryPolicyOracle.privateCallPreferredDecoderName(codecId, before),
                    privateCallPreferredDecoderName(codecId, after))
            }
        }
        for (fingerprint in fingerprints) {
            for (transport in transports) {
                for (full in listOf(false, true)) {
                    assertEquals(PreRegistryPolicyOracle.useContinuousLowLatencyViewerDiscovery(transport, fingerprint, full),
                        useContinuousLowLatencyViewerDiscovery(transport, fingerprint, full))
                }
                assertEquals(PreRegistryPolicyOracle.LegacyQualcommClassicCompatibility.matches(transport, fingerprint),
                    LegacyQualcommClassicCompatibility.matches(transport, fingerprint))
                assertEquals(PreRegistryPolicyOracle.pauseClassicViewerDiscoveryAtCapacity(transport, fingerprint),
                    pauseClassicViewerDiscoveryAtCapacity(transport, fingerprint))
            }
            assertEquals(PreRegistryPolicyOracle.isLegacyQualcommMsmnileStack(fingerprint), isLegacyQualcommMsmnileStack(fingerprint))
            assertEquals(PreRegistryPolicyOracle.isLegacyQualcommAtollStack(fingerprint), isLegacyQualcommAtollStack(fingerprint))
            assertEquals(PreRegistryPolicyOracle.legacyQualcommGattCallFlushIntervalMs(fingerprint),
                legacyQualcommGattCallFlushIntervalMs(fingerprint))
            for (override in listOf(null, true, false)) {
                assertEquals(PreRegistryPolicyOracle.deferredFor(fingerprint, override),
                    GattClientTeardown.deferredFor(fingerprint, override))
                assertEquals(PreRegistryPolicyOracle.sharedEnabledFor(fingerprint, override),
                    CocListeners.sharedEnabledFor(fingerprint, override))
                assertEquals(PreRegistryPolicyOracle.freshAclEnabledFor(fingerprint, override),
                    BleCocFreshAcl.enabledFor(fingerprint, override))
            }
            assertEquals(PreRegistryPolicyOracle.leCocServerLeaks(fingerprint), DeviceFamilyPolicy.leCocServerLeaks(fingerprint))
            assertEquals(PreRegistryPolicyOracle.gattContextMapRace(fingerprint), DeviceFamilyPolicy.gattContextMapRace(fingerprint))
            assertEquals(PreRegistryPolicyOracle.leCocEattStrandedChannels(fingerprint),
                DeviceFamilyPolicy.leCocEattStrandedChannels(fingerprint))
        }
        for (sdk in 21..37) {
            val stack = DeviceTraits("mt6765", "board", sdk)
            for (override in listOf(null, true, false)) {
                assertEquals(PreRegistryPolicyOracle.deferredFor(stack, override), GattClientTeardown.deferredFor(stack, override))
                assertEquals(PreRegistryPolicyOracle.sharedEnabledFor(stack, override), CocListeners.sharedEnabledFor(stack, override))
                assertEquals(PreRegistryPolicyOracle.freshAclEnabledFor(stack, override), BleCocFreshAcl.enabledFor(stack, override))
            }
            for (busy in listOf(null, "receiver")) for (running in listOf(false, true)) {
                assertEquals(PreRegistryPolicyOracle.refusal(sdk, busy, running), BluetoothRestart.refusal(sdk, busy, running))
            }
        }
    }

    @Test
    fun codecComponentRulesMatchThePreRegistryDecisions() {
        val names = listOf(
            "c2.android.vp9.decoder", "C2.ANDROID.VP9.DECODER", "c2.android.av1.decoder", "c2.android.opus.decoder",
            "C2.android.opus.decoder", "c2.androidx.opus", "OMX.google.opus.decoder", "OMX.Exynos.vp9.dec",
            "c2.exynos.vp9.decoder", "c2.exynos.vp9.encoder", "OMX.qcom.video.encoder.vp9", "libaom-native",
            "c2.android.vp9.decoder.secure", "", "c2.",
        )
        for (name in names) {
            assertEquals(name, PreRegistryPolicyOracle.encoderTieBreakRank(name), encoderTieBreakRank(name))
            assertEquals(name, PreRegistryPolicyOracle.opusDecoderPreferred(name),
                DeviceFamilyPolicy.componentEffects<PreferredOpusDecoder>(name).isNotEmpty())
            for (traits in fingerprints) {
                assertEquals(PreRegistryPolicyOracle.opusDecoderPreferred(name),
                    DeviceFamilyPolicy.componentEffects<PreferredOpusDecoder>(name, traits).isNotEmpty())
            }
            for (attempts in -1..3) {
                assertEquals("$name $attempts", PreRegistryPolicyOracle.canRecoverPrivateCallVideoDecoder(name, attempts),
                    canRecoverPrivateCallVideoDecoder(name, attempts))
            }
        }
    }

    private fun codec(
        name: String,
        protocolId: Int,
        hardware: Boolean = false,
        backend: VideoEncoderBackend = VideoEncoderBackend.MEDIA_CODEC,
    ) = VideoCodec(
        displayName = name,
        mimeType = if (protocolId == StreamProtocol.CODEC_AV1) "video/av01" else "video/x-vnd.on2.vp9",
        protocolId = protocolId,
        encoderName = name,
        hardwareAccelerated = hardware,
        usesSurfaceInput = hardware,
        bitrateMode = null,
        supportedBitrateModes = emptySet(),
        complexity = null,
        backend = backend,
    )
}
