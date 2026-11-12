package com.example.bluetoothtest

import android.content.Intent

/**
 * Experimental decoder-robustness switches (family backlog batch D). Every arm is DEBUG-only (a launch
 * extra from another app must not change a release build's behaviour) and default off, except the promoted stall
 * recovery (default on, 2026-11-10; its `false` is the DEBUG control arm); the shipped behaviour is the control arm. Listed in SWITCHES "Experimental, alternative and optional
 * behaviour". The registry arms of the same names are in [DecoderPolicyEntries].
 */
object DecoderSwitches {
    /**
     * `test_decoder_stall_recovery` (promoted on 2026-11-10, default on; `false` is the DEBUG control arm): a
     * `decoder_stall` (queued input with no decoder output) rebuilds the decoder once, then excludes the
     * component for the session and tries the next candidate, asking for a keyframe through the normal
     * needsKeyFrame path. Off: a stall is only logged. Read by [VideoStreamDecoder], so calls and streams
     * share it. The setter keeps the default in release builds (the extra is DEBUG-only).
     */
    @Volatile var stallRecovery: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * `test_decoder_low_latency_ladder=true` (Experimental, default off): hardware decoders that declare
     * FEATURE_LowLatency are tried first, and the configure format carries the vendor low-latency keys the
     * component lists in getSupportedVendorParameters() (Qualcomm, Exynos/RTC, generic) plus the MediaTek
     * format keys (vdec-lowlatency, vdec-no-record, use-clearmotion-mode=0), dropping the newest key first
     * when configure fails. Off: unchanged (candidate order and KEY_LOW_LATENCY gate as before).
     */
    @Volatile var lowLatencyLadder: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_decoder_priority=true` (Experimental, default off): KEY_PRIORITY=0 (real-time) on the decoder
     * format, dropped first when configure fails. Never KEY_OPERATING_RATE (docs: Media3 M-30, Moonlight L-5).
     */
    @Volatile var realtimePriority: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Reads the DEBUG launch extras `test_decoder_*`; release builds keep every default. */
    fun applyExtras(boolExtra: (String, Boolean) -> Boolean) {
        stallRecovery = boolExtra("test_decoder_stall_recovery", true)
        lowLatencyLadder = boolExtra("test_decoder_low_latency_ladder", false)
        realtimePriority = boolExtra("test_decoder_priority", false)
    }

    fun applyLaunchExtras(intent: Intent) = applyExtras(intent::getBooleanExtra)

    /** One log token for the BENCHMARK decoder lines. */
    fun describe(): String = "stallRecovery=$stallRecovery,lowLatencyLadder=$lowLatencyLadder,priority=$realtimePriority"
}
