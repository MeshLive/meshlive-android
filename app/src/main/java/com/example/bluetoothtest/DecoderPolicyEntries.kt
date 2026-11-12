package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.GOOGLE_OPEN_SOURCE
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * Decoder-robustness registry entries (family backlog batch D). Same rules as every area list:
 * matchers use the codec component name or a runtime trait, never a model; each entry has sources, a
 * confidence note, validation, a fallback and the off-switch `test_device_policy_off=<id>`.
 * [CodecPolicy.entries] appends [entries], so the one registry already contains them.
 *
 * Active here: release-instead-of-flush on `c2.mtk.` decoders (D-01), the no-output watchdog in its
 * logging form (D-02/D-05) and the bounded codec release (D-05). The Experimental arms and the
 * proposals are carried with `proposed = true`: the resolver only reports when they would match, and
 * the DEBUG switches in [DecoderSwitches] enable the arms.
 */
internal object DecoderPolicyEntries {
    private const val REVIEWED = "2026-10-14"
    private const val MEDIA3_COMMIT = "8c6678b657ede1e7883fc164ef73ed483c7796c3"
    private const val MEDIA3_RENDERER = "https://github.com/androidx/media/blob/$MEDIA3_COMMIT/libraries/exoplayer/" +
        "src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java"
    private const val WEBRTC = "https://webrtc.googlesource.com/src/+/42995c384b59f0da002a6078d4a0320af39a16b8/"
    private const val MOONLIGHT = "https://github.com/moonlight-stream/moonlight-android/blob/" +
        "b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/binding/video/"

    private val MEDIA3_MTK_FLUSH = PolicySource(
        GOOGLE_OPEN_SOURCE,
        MEDIA3_RENDERER,
        "shouldReleaseCodecInsteadOfFlushing (L1712-L1725): for the exact names c2.mtk.avc.decoder, " +
            "c2.mtk.hevc.decoder and c2.mtk.vp9.decoder, with a video sink, 'Flushing a c2.mtk decoder that outputs to " +
            "a SurfaceTexture often fails and leaves the SurfaceTexture's BufferQueue in an unrecoverable state. " +
            "Release the codec instead. See b/362904942'",
    )
    private val MEDIA3_MTK_VP9_COMMIT = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://github.com/androidx/media/commit/f5bf49d0df43",
        "'Expand MediaTek flush workaround to include VP9' (the workaround first covered AVC and HEVC)",
    )
    private val MEDIA3_MTK_STALL_2711 = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://github.com/androidx/media/issues/2711",
        "MediaTek Dimensity HEVC decoders black or frozen after the Android 15 update with no error raised; " +
            "#2765 reports the same for c2.mtk.hevc.decoder, #3421 asks for runtime decoder fallback",
    )
    private val MOONLIGHT_HUNG = PolicySource(
        VENDOR_DOC,
        MOONLIGHT + "MediaCodecDecoderRenderer.java",
        "DecoderHungException: no input buffer for 5000 ms is treated like a decoder crash and recovered " +
            "(restart, then reset); the first IllegalStateException after teardown is a known race",
    )
    private val WEBRTC_RELEASE_TIMEOUT = PolicySource(
        GOOGLE_OPEN_SOURCE,
        WEBRTC + "sdk/android/src/java/org/webrtc/AndroidVideoDecoder.java",
        "MEDIA_CODEC_RELEASE_TIMEOUT_MS = 5000: 'MediaCodec.release() occasionally hangs'; release runs off the " +
            "decoding thread and returns a timeout after 5 s (HardwareVideoEncoder.release does the same)",
    )
    private val CHROMIUM_CODEC_ALLOCATOR = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://chromium.googlesource.com/chromium/src/+/refs/heads/main/media/gpu/android/codec_allocator.cc",
        "kHungTaskDetectionTimeout = 800 ms: MediaCodec.configure() normally takes 100-200 ms; a creation that " +
            "hangs is detected and later creations fall back (unpinned branch snapshot)",
    )
    private val MOONLIGHT_LATENCY = PolicySource(
        VENDOR_DOC,
        MOONLIGHT + "MediaCodecHelper.java",
        "findKnownSafeDecoder (decoders that declare FEATURE_LowLatency first; C2 low_latency variants are listed " +
            "after the standard ones), setDecoderLowLatencyOptions (options tried from the most to the least " +
            "risky, first MediaFormat that configures wins) and knownVendorLowLatencyOptions",
    )
    private val QCOM_VDEC_KEYS = PolicySource(
        VENDOR_DOC,
        "https://cs.android.com/android/platform/superproject/+/master:hardware/qcom/sdm845/media/mm-video-v4l2/" +
            "vidc/vdec/src/omx_vdec_extensions.hpp",
        "vendor.qti-ext-dec-low-latency.enable and vendor.qti-ext-dec-picture-order.enable decoder extensions",
    )
    private val QCOM_SM8150_VDEC_KEYS = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://android.googlesource.com/platform/hardware/qcom/sm8150/media/+/refs/heads/main/mm-video-v4l2/vidc/" +
            "vdec/src/omx_vdec_extensions.hpp",
        "sm8150 (msmnile) OMX decoder: ADD_EXTENSION(qti-ext-dec-low-latency, OMX_QTIIndexParamLowLatencyMode) with " +
            "the Int32 parameter enable; set_vendor_extension_config copies it into bEnableLowLatencyMode and calls " +
            "set_parameter(OMX_QTIIndexParamLowLatencyMode); one extension table serves every codec of omx_vdec",
    )
    private val ACODEC_VENDOR_PARAMETERS = PolicySource(
        GOOGLE_OPEN_SOURCE,
        "https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android11-release/media/libstagefright/ACodec.cpp",
        "configureCodec(mime, msg) ends with setVendorParameters(msg): the configure format's vendor.<extension>.<param> " +
            "keys reach an OMX component's Android vendor extensions (API 26+); KEY_LOW_LATENCY (low-latency) is a " +
            "separate OMX config, honoured only by components that implement it",
    )
    private val VENDOR_PARAMETERS_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodec#getSupportedVendorParameters()",
        "API 31: lists the vendor parameter names the codec accepts; the format keys of the ladder are set only " +
            "when listed, except the MediaTek format keys that MediaTek's modified ACodec reads by name",
    )
    private val MTK_ACODEC_KEYS = PolicySource(
        VENDOR_DOC,
        "https://github.com/andreknieriem/open-headunit/blob/d2d0a6bf71217e8658545314de63ed87c0031451/app/src/main/" +
            "java/com/andrerinas/openheadunit/decoder/video/DecoderConfigLadder.kt",
        "MediaTek tier: vdec-lowlatency, vdec-no-record, use-clearmotion-mode=0, then fewer keys; warns that a " +
            "configure that does not throw is not proof the component acted (ACodec read-back reflects its own bookkeeping)",
    )
    private val LOW_LATENCY_API = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaFormat#KEY_LOW_LATENCY",
        "API 30: KEY_LOW_LATENCY asks a decoder that declares FEATURE_LowLatency to return output as early as possible",
    )
    private val MEDIA3_DECODER_PRIORITY = PolicySource(
        GOOGLE_OPEN_SOURCE,
        MEDIA3_RENDERER,
        "getMediaFormat (L2692) sets MediaFormat.KEY_PRIORITY = 0 (real-time) on every decoder; Moonlight, CameraX, " +
            "scrcpy and Chromium do the same, and none combine it with a huge KEY_OPERATING_RATE (Media3 M-30, " +
            "Moonlight L-5)",
    )
    private val MOZILLA_MTK_CROP = PolicySource(
        VENDOR_DOC,
        "https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/gecko/media/" +
            "HardwareCodecCapabilityUtils.java",
        "decoder names containing .mtk. return a default crop Rect such as (0, 0, 318, 238); read the crop-left/" +
            "right/top/bottom integers instead (Kodi: MediaTek decoder output PTS can be -1)",
    )
    private val MOZILLA_TENSOR = PolicySource(
        VENDOR_DOC,
        "https://searchfox.org/mozilla-central/source/dom/media/platforms/android/RemoteDataDecoder.cpp",
        "areBT709ColorPrimariesMisreported: Tensor G1/G2 (SOC_MODEL Tensor or GS201, API >= 33) AV1 decoder " +
            "intermittently reports BT709 frames as BT601 (bug 1933055); areSmpte432ColorPrimariesBuggy: " +
            "SOC_MANUFACTURER Google, API >= 34 (bugs 1866020, 1879720)",
    )

    /** D-01: MediaTek Codec2 decoders are released instead of flushed when recovery restarts the decoder. */
    val C2_MTK_RELEASE_INSTEAD_OF_FLUSH = DevicePolicyEntry(
        id = "c2-mtk-release-instead-of-flush",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "MediaTek Codec2 decoders: recovery releases and re-creates the decoder instead of flush()",
        matcher = DeviceMatcher(),
        component = CodecComponentMatcher(prefixes = setOf("c2.mtk.")),
        effects = listOf(ReleaseCodecInsteadOfFlush),
        behaviour = "When the call or stream decoder recovers at a keyframe and the running decoder is a c2.mtk. " +
            "decoder, it is released and re-created with the newest codec configuration (the existing decoder " +
            "rebuild) instead of MediaCodec.flush(), which on these components can leave the TextureView's " +
            "SurfaceTexture BufferQueue unusable (picture frozen or black). The log line is " +
            "`decoder_recovery: recovery=release_not_flush`. Decoders of other components keep flush().",
        sources = listOf(MEDIA3_MTK_FLUSH, MEDIA3_MTK_VP9_COMMIT),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no owned phone ships c2.mtk decoders (the A15s is OMX.MTK on Android 10); the " +
            "decision is unit-tested and the rebuild path is the one used for decoder changes"),
        confidence = MEDIUM,
        confidenceNote = "Google ships the workaround (b/362904942), so the defect exists; Media3 names exactly avc, hevc and " +
            "vp9 and only with a video sink, so every other c2.mtk. decoder (c2.mtk.av1.decoder) is an extension of the prefix rule. Whether MeshLive's TextureView " +
            "recovery path is hit is unmeasured. A rebuild is about 100-300 ms slower, only on matched components.",
        reviewed = REVIEWED,
        origin = "Family backlog D-01 (codec-background A6); VideoStreamDecoder.restartAtRecoveryKeyFrame called flush()",
        fallback = "Off: flush() then start() as before on every component.",
    )

    /** D-02/D-05: log a decoder that takes input and produces no output; recovery is the Experimental arm. */
    val DECODER_NO_OUTPUT_WATCHDOG = DevicePolicyEntry(
        id = "decoder-no-output-watchdog",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Decoder no-output and input-starvation watchdog (logging)",
        matcher = DeviceMatcher(),
        effects = listOf(DecoderStallWatchdog),
        behaviour = "A decoder that was queued at least max(8, 2 x fps) access units with no output callback for " +
            "1.5 s, or that offers no input buffer for 5 s while frames wait, logs `BENCHMARK decoder_stall` (at " +
            "most five lines per decoder). Nothing is rebuilt unless the Experimental arm " +
            "decoder-stall-recovery-arm is on. Output callbacks, not the surface, are the signal, so a hidden " +
            "surface or a paused stream does not fire it; it is suspended while input is blocked or output is held.",
        sources = listOf(MEDIA3_MTK_STALL_2711, MOONLIGHT_HUNG),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no stall recorded on an owned phone; fake-decoder unit tests only"),
        confidence = LOW,
        confidenceNote = "the failure class is well reported on MediaTek HEVC; VP9/AV1 instances on MediaTek are " +
            "unconfirmed; the thresholds are ours (Moonlight uses 5 s of no input buffer)",
        reviewed = REVIEWED,
        origin = "Family backlog D-02/D-05 (codec-background A22, workaround list L-17); VideoStreamDecoder recovered only on a CodecException",
        fallback = "Off: no watchdog tick; a silent decoder freeze is not detected, as before.",
    )

    /** D-05: release (and a failed attempt's release) cannot freeze the decoder worker for more than 5 s. */
    val DECODER_BOUNDED_RELEASE = DevicePolicyEntry(
        id = "decoder-bounded-release",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Decoder stop/release is bounded to 5 s; slow or hung configure is logged",
        matcher = DeviceMatcher(),
        effects = listOf(BoundedDecoderRelease(timeoutMs = DecoderWatchdogLimits.RELEASE_TIMEOUT_MS)),
        behaviour = "MediaCodec stop()+release() of the decoder (rebuild, failed attempt, close) runs on a short-lived " +
            "thread and the decoder worker waits at most 5 s; a timeout logs `decoder_release_hang` and the " +
            "codec is abandoned. A configure()+start() over 800 ms logs `decoder_configure_slow`, and one still " +
            "running after 5 s logs `decoder_configure_hang` (it cannot be interrupted). The order of release " +
            "before the next create is unchanged.",
        sources = listOf(WEBRTC_RELEASE_TIMEOUT, CHROMIUM_CODEC_ALLOCATOR),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no release hang recorded locally; unit-tested with a blocking action"),
        confidence = MEDIUM,
        confidenceNote = "WebRTC and Chromium ship the same bound; only timing logs and a bounded wait change when " +
            "nothing hangs",
        reviewed = REVIEWED,
        origin = "Family backlog D-05 (workaround list W-20, C-15); release()/stop() ran inline on the decoder worker",
        fallback = "Off: stop() and release() run inline on the worker as before; configure timing is still logged.",
    )

    /** D-02: Experimental recovery from a stall (rebuild once, then exclude the component for the session). */
    val STALL_RECOVERY_ARM = DevicePolicyEntry(
        id = "decoder-stall-recovery-arm",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Decoder stall recovery A/B arm (rebuild once, then exclude the component)",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "With test_decoder_stall_recovery (default on; DEBUG false is the control arm), the first decoder_stall of a decoder makes the " +
            "receiver wait for a keyframe (the normal needsKeyFrame request) and rebuild the same decoder; a " +
            "repeat excludes the component for the rest of the session and rebuilds with the next candidate; " +
            "after three stalls without 60 healthy frames in between it only logs.",
        sources = listOf(MEDIA3_MTK_STALL_2711, MOONLIGHT_HUNG),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "randomised user-launch A/B on one pair, 12 calls per arm, same build: without recovery 8 of 12 calls " +
                "survived a software-AV1 stall at the Wi-Fi switch, with it 12 of 12 (one-sided Fisher p = 0.047); " +
                "promoted to default on 2026-11-10; an A15s (MediaTek) smoke and a hidden-surface case are still open",
        ),
        confidence = LOW,
        confidenceNote = "the mechanism mirrors Moonlight's recovery ladder; false positives on a legitimately " +
            "slow decoder are the risk; the DEBUG false arm stays as the control",
        reviewed = REVIEWED,
        origin = "Family backlog D-02",
        proposed = true,
        fallback = "DEBUG test_decoder_stall_recovery=false: a stall is logged only (the control arm).",
    )

    /** D-03: candidate order and vendor low-latency keys (Experimental). */
    val LOW_LATENCY_LADDER_ARM = DevicePolicyEntry(
        id = "decoder-low-latency-ladder-arm",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Decoder low-latency candidate order and vendor-key ladder A/B arm",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "With DEBUG test_decoder_low_latency_ladder=true, hardware decoders that declare " +
            "FEATURE_LowLatency are tried before the framework's choice (Exynos OMX before C2, Qualcomm " +
            ".low_latency variants), and the configure format adds the vendor keys the component lists in " +
            "getSupportedVendorParameters() (vendor.qti-ext-dec-low-latency.enable, " +
            "vendor.rtc-ext-dec-low-latency.enable, vendor.low-latency.enable) plus, on MediaTek decoders, " +
            "vdec-lowlatency, vdec-no-record and use-clearmotion-mode=0. A failed configure drops the newest key " +
            "first, ending at the plain format. The effect is judged by decoder_behaviour log lines (decode " +
            "latency, decoded versus rendered frames), not by a configure that does not throw.",
        sources = listOf(MOONLIGHT_LATENCY, QCOM_VDEC_KEYS, VENDOR_PARAMETERS_DOC, MTK_ACODEC_KEYS, LOW_LATENCY_API),
        sourceStatus = PARTIAL,
        evidence = listOf("none: Experimental; evidence in the sources is AVC/HEVC, VP9/AV1 unconfirmed"),
        confidence = LOW,
        confidenceNote = "large low-latency clients use these keys, but MeshLive's streams have no B-frames and are " +
            "AV1/VP9, where the reorder delay these keys remove is smaller; the benefit is unmeasured",
        reviewed = REVIEWED,
        origin = "Family backlog D-03 (codec-background A7/A30/A31, workaround list L-3..L-10)",
        proposed = true,
        fallback = "Off (default): the framework's candidate order and KEY_LOW_LATENCY gated on FEATURE_LowLatency.",
    )

    /** Qualcomm decoders without FEATURE_LowLatency get Qualcomm's own low-latency extension (default on). */
    val QCOM_DECODER_VENDOR_LOW_LATENCY = DevicePolicyEntry(
        id = "qcom-decoder-vendor-low-latency",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Qualcomm decoders: vendor.qti-ext-dec-low-latency.enable=1 where KEY_LOW_LATENCY is not offered",
        matcher = DeviceMatcher(sdkMin = 26),
        component = CodecComponentMatcher(prefixes = setOf("OMX.qcom.video.decoder.", "c2.qti.")),
        effects = listOf(VendorDecoderLowLatencyKey(DecoderLowLatencyLadder.VENDOR_QTI)),
        behaviour = "When the receiver asks for a low-latency decoder (the default) and a Qualcomm decoder does not " +
            "declare FEATURE_LowLatency, the configure format carries vendor.qti-ext-dec-low-latency.enable=1 " +
            "instead of KEY_LOW_LATENCY; a configure that refuses it retries with the plain format. Logged as " +
            "`decoder_low_latency: requested=... | effective=...` and judged by `decoder_hold` (input-to-output time).",
        sources = listOf(QCOM_SM8150_VDEC_KEYS, ACODEC_VENDOR_PARAMETERS, QCOM_VDEC_KEYS, MOONLIGHT_LATENCY),
        sourceStatus = OFFICIAL,
        evidence = listOf(
            "benchmarks/runs/codec-smoke-20261028/b-hc-smoke/hs2-stream-a15s-asus-h264 (before: ASUS " +
                "OMX.qcom.video.decoder.avc, formatKeys=plain, decoder_hold p50 68-69 ms at 15 FPS, one frame held)",
            "Moonlight sets the key on every omx.qcom/c2.qti decoder from API 26 (MediaCodecHelper.setDecoderLowLatencyOptions)",
        ),
        confidence = MEDIUM,
        confidenceNote = "the extension is in Qualcomm's AOSP decoder source and ACodec forwards vendor keys at " +
            "configure; whether the ASUS firmware's decoder honours it is unmeasured until decoder_hold is re-read. " +
            "Low-latency mode outputs in decode order, which is safe because MeshLive streams carry no B-frames",
        reviewed = "2026-10-28",
        origin = "Block 1 codec smoke (2026-10-28): the ASUS AVC decoder ran without low latency and held one frame",
        fallback = "Off (`test_device_policy_off=qcom-decoder-vendor-low-latency`): the plain format, as before.",
    )

    /** D-04: decoder KEY_PRIORITY=0 (Experimental). */
    val REALTIME_PRIORITY_ARM = DevicePolicyEntry(
        id = "decoder-realtime-priority-arm",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.EXPERIMENTAL,
        title = "Decoder KEY_PRIORITY=0 (real-time) A/B arm",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "With DEBUG test_decoder_priority=true, the decoder format carries KEY_PRIORITY=0; a failed " +
            "configure retries without it first. KEY_OPERATING_RATE is never set (a huge value crashes " +
            "Qualcomm configure, Media3 M-30; priority 0 plus it crashes Adreno 620 decoders, Moonlight L-5).",
        sources = listOf(MEDIA3_DECODER_PRIORITY),
        sourceStatus = PARTIAL,
        evidence = listOf("none: Experimental"),
        confidence = MEDIUM,
        confidenceNote = "priority alone is risk-free in Media3's decoder format; its latency effect on MeshLive is unmeasured",
        reviewed = REVIEWED,
        origin = "Family backlog D-04 (workaround list A-7b)",
        proposed = true,
        fallback = "Off (default): no priority key.",
    )

    /** D-05 / A-18: MediaTek decoder crop and timestamp quirks, proposal only. */
    val MEDIATEK_CROP_AND_PTS_QUIRKS = DevicePolicyEntry(
        id = "mediatek-decoder-crop-and-pts-quirks",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "MediaTek decoders: default crop Rect and invalid output timestamps (proposal)",
        matcher = DeviceMatcher(),
        component = CodecComponentMatcher(prefixes = setOf("c2.mtk.", "OMX.MTK.")),
        effects = emptyList(),
        behaviour = "Reports phones with a MediaTek decoder. MediaTek decoders may return a default crop Rect (for " +
            "example 0,0,318,238) and output presentation timestamps of -1 or 0. MeshLive already reads the " +
            "crop-left/right/top/bottom integers (decodedOutputSize) and ignores a 0x0 output size; the output " +
            "timestamp guard (match decoder output to input by PTS) has no effect yet.",
        sources = listOf(MOZILLA_MTK_CROP),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no owned phone has a c2.mtk decoder"),
        confidence = LOW,
        confidenceNote = "second-hand reports from Gecko and Kodi; unverified on VP9/AV1 MediaTek decoders",
        reviewed = REVIEWED,
        origin = "Family backlog D-05 (workaround list A-18)",
        proposed = true,
        fallback = "Not applied: decodedOutputSize and PTS matching behave as before.",
    )

    /** D-08 / A-3b: Tensor AV1 decoder reports BT709 frames as BT601; proposal only. */
    val TENSOR_AV1_COLOUR_STANDARD = DevicePolicyEntry(
        id = "tensor-av1-decoder-colour-standard-misreport",
        area = PolicyArea.CODEC,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        title = "Tensor G1/G2 AV1 decoder mislabels BT709 as BT601; Pixel SMPTE-432 tags (proposal)",
        matcher = DeviceMatcher(socFamilies = setOf(SocFamily.TENSOR), sdkMin = 33),
        effects = emptyList(),
        behaviour = "Reports Tensor phones on API 33+. If enabled, MeshLive would ignore the decoder-reported colour " +
            "standard and treat the stream as BT.709, which the encoder declares. The matcher is a superset " +
            "(SoC family Tensor); Gecko's rule is SOC_MODEL exactly Tensor or GS201 for the AV1 mislabel and " +
            "SOC_MANUFACTURER Google with API 34+ for SMPTE-432. The decoder renders to a Surface, so the " +
            "compositor handles colour and only flicker is expected.",
        sources = listOf(MOZILLA_TENSOR),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no Pixel among the required phones"),
        confidence = MEDIUM,
        confidenceNote = "a production fix in Gecko with bug references; unmeasured with MeshLive's encoder tags",
        reviewed = REVIEWED,
        origin = "Family backlog D-08 (workaround list A-3b, F-5, F-6)",
        proposed = true,
        fallback = "Not applied: the decoder output is rendered as the platform presents it.",
    )

    val entries: List<DevicePolicyEntry> = listOf(
        C2_MTK_RELEASE_INSTEAD_OF_FLUSH,
        DECODER_NO_OUTPUT_WATCHDOG,
        DECODER_BOUNDED_RELEASE,
        QCOM_DECODER_VENDOR_LOW_LATENCY,
        STALL_RECOVERY_ARM,
        LOW_LATENCY_LADDER_ARM,
        REALTIME_PRIORITY_ARM,
        MEDIATEK_CROP_AND_PTS_QUIRKS,
        TENSOR_AV1_COLOUR_STANDARD,
    )
}

/** Recovery of a matching decoder releases and re-creates it instead of calling flush(). */
internal data object ReleaseCodecInsteadOfFlush : DevicePolicyEffect {
    override fun describe(): String = "decoder recovery: release and re-create instead of flush()"
}

/** A matching decoder asks for low latency with this vendor format key (=1) when it lacks FEATURE_LowLatency. */
internal data class VendorDecoderLowLatencyKey(val key: String) : DevicePolicyEffect {
    override fun describe(): String = "decoder low latency via $key=1 (no FEATURE_LowLatency)"
}

/** The decoder watchdog logs a decoder that produces no output or offers no input buffer. */
internal data object DecoderStallWatchdog : DevicePolicyEffect {
    override fun describe(): String = "decoder no-output / input-starvation watchdog (logging)"
}

/** Decoder stop/release waits at most [timeoutMs] on the decoder worker. */
internal data class BoundedDecoderRelease(val timeoutMs: Long) : DevicePolicyEffect {
    override fun describe(): String = "decoder release bounded to $timeoutMs ms"
}
