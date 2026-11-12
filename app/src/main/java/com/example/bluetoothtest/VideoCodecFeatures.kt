package com.example.bluetoothtest

/**
 * Design HC (DECISIONS "Design HC", PROTOCOL "Draft: HEVC and H.264 video codecs"): the two master switches of the
 * HEVC (codec 5) and hardware H.264 (codec 4) video codecs, plus the DEBUG control arms.
 *
 * - Masters: shipped constants [ConnectivityFeatureDefaults.HEVC_CODEC] and [ConnectivityFeatureDefaults.H264_CODEC], on since 0.4.2-beta
 *   (design decision 2026-10-28; a benchmark launch without the extra keeps them off). While a master is off its hello bits, summary bit, `BTV3` codec ID, picker entry and logs do not appear, and every byte is
 *   today's. A DEBUG launch decides with `test_hevc_codec` / `test_h264_codec` ([FeatureSpec.extraInAnyDebugLaunch]).
 * - Control arms (DEBUG): `test_prefer_hevc=false` / `test_prefer_hw_avc=false` keep the codec advertised and
 *   selectable by hand but Automatic never picks it; `test_av1_soft_decode_bit=false` does not send end-to-end bit 4;
 *   `test_bit7_hw_hevc=false` lets peer bit 7 put VP9 ahead of hardware HEVC too; `test_hc_session_level=false`
 *   requests the encoder's highest listed level instead of the session's.
 */
internal object VideoCodecFeatures {
    const val EXTRA_HEVC = "test_hevc_codec"
    const val EXTRA_H264 = "test_h264_codec"
    const val EXTRA_PREFER_HEVC = "test_prefer_hevc"
    const val EXTRA_PREFER_HW_AVC = "test_prefer_hw_avc"
    const val EXTRA_AV1_SOFT_DECODE_BIT = "test_av1_soft_decode_bit"
    const val EXTRA_BIT7_HW_HEVC = "test_bit7_hw_hevc"
    const val EXTRA_HC_SESSION_LEVEL = "test_hc_session_level"
    /** Design VX: DEBUG `test_prefer_libvpx=true|false` overrides [ConnectivityFeatureDefaults.LIBVPX_AUTOMATIC]. */
    const val EXTRA_PREFER_LIBVPX = "test_prefer_libvpx"

    val hevcSpec = FeatureSpec(
        id = "hevc_codec",
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.HEVC_CODEC,
        benchmarkExtra = EXTRA_HEVC,
        group = ViewerFeatures.GROUP,
        phoneTest = "HC",
        extraInAnyDebugLaunch = true,
    )

    val h264Spec = FeatureSpec(
        id = "h264_codec",
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.H264_CODEC,
        benchmarkExtra = EXTRA_H264,
        group = ViewerFeatures.GROUP,
        phoneTest = "HC",
        extraInAnyDebugLaunch = true,
    )
}

/**
 * The effective codec switches of this process. The masters follow the feature snapshot; JVM tests set
 * [hevcOverride] / [h264Override]. The prefer arms and bit 4 are DEBUG-only control arms (a release build always
 * prefers and always sends bit 4 while a master is on).
 */
internal object VideoCodecSwitches {
    @Volatile var hevcOverride: Boolean? = null
    @Volatile var h264Override: Boolean? = null

    val hevc: Boolean get() = hevcOverride ?: FeatureCatalog.current.isOn(VideoCodecFeatures.hevcSpec)
    val h264: Boolean get() = h264Override ?: FeatureCatalog.current.isOn(VideoCodecFeatures.h264Spec)

    /** Either master on: the Design HC choice rules apply; both off: exactly today's VP9/AV1 rules. */
    val anyNew: Boolean get() = hevc || h264

    /** DEBUG control arm `test_prefer_hevc=false`: Automatic never picks HEVC. */
    @Volatile var preferHevc: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** DEBUG control arm `test_prefer_hw_avc=false`: Automatic never picks H.264. */
    @Volatile var preferHwAvc: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Design VX: a DEBUG launch's `test_prefer_libvpx` (null: the shipped [ConnectivityFeatureDefaults.LIBVPX_AUTOMATIC]).
     * Release builds always use the shipped constant.
     */
    @Volatile var preferLibvpxOverride: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** Design VX: Automatic may pick the bundled libvpx VP9 encoder; off, it is a manual choice only. */
    val preferLibvpx: Boolean get() = preferLibvpxOverride ?: ConnectivityFeatureDefaults.LIBVPX_AUTOMATIC

    /** DEBUG control arm `test_av1_soft_decode_bit=false`: end-to-end bit 4 is not sent. */
    @Volatile var av1SoftDecodeBit: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * DEBUG control arm `test_hc_session_level=false`: HEVC/H.264 encoders get the highest level they list as
     * `KEY_LEVEL` (the first Design HC rule) instead of the lowest standard level the session needs.
     */
    @Volatile var hcSessionLevel: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** DEBUG control arm `test_bit7_hw_hevc=false`: peer bit 7 puts VP9 ahead of hardware HEVC as well. */
    @Volatile var bit7HwHevc: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** Whether the `BTV3` header check and the decoders accept [codecId] (VP9 and AV1 always; 4 and 5 with their master). */
    fun accepts(codecId: Int): Boolean = when (codecId) {
        StreamProtocol.CODEC_VP9, StreamProtocol.CODEC_AV1 -> true
        StreamProtocol.CODEC_H264 -> h264
        StreamProtocol.CODEC_HEVC -> hevc
        else -> false
    }

    /**
     * The current choice inputs (masters and prefer arms) for [VideoCodecChoice]. [power] is the session-start state
     * ([CodecPowerAware.read], read once when the call or stream starts); normal everywhere else (picker, summaries).
     */
    fun current(
        preferHwAv1: Boolean = DebugTunables.preferHardwareAv1,
        power: CodecPowerState = CodecPowerState.NORMAL,
        /** `test_sw_shortfall_avc`: the software encoders remembered short for the starting session (empty elsewhere). */
        slowSoftware: Set<String> = emptySet(),
    ): CodecChoiceSwitches = CodecChoiceSwitches(
        hevc = hevc,
        h264 = h264,
        preferHwAv1 = preferHwAv1,
        preferHevc = preferHevc,
        preferHwAvc = preferHwAvc,
        preferLibvpx = preferLibvpx,
        bit7AllowsHwHevc = bit7HwHevc,
        powerAware = CodecPowerAware.enabled,
        power = power,
        swShortfall = DebugTunables.swShortfallAvc,
        slowSoftware = slowSoftware,
    )
}
