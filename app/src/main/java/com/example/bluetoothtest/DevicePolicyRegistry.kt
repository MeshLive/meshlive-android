package com.example.bluetoothtest

import com.example.bluetoothtest.BluetoothTransportOption.BLE_GATT
import com.example.bluetoothtest.BluetoothTransportOption.BLE_L2CAP
import com.example.bluetoothtest.BluetoothTransportOption.CLASSIC_RFCOMM
import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.BLUETOOTH_SPEC
import com.example.bluetoothtest.PolicySourceKind.CDD
import com.example.bluetoothtest.PolicySourceKind.GOOGLE_OPEN_SOURCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.EVIDENCE_ONLY
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * Every device-family, stack-version and codec-component rule the app applies, with its sources.
 * Evidence paths are benchmark runs relative to the project root. Reviewed dates are when the
 * entry and its sources were last checked. Off-switch: DEBUG `test_device_policy_off=<id>` (or
 * `all`). Proposed entries are sourced but change nothing; promoting one needs its own A/B and
 * maintainer review (CONTRIBUTING.md). Sources were verified on 2026-10-09 against AOSP release
 * branches, commit history and the official pages named.
 */
internal object DevicePolicyRegistry {
    /** Call maxima legacy-family phones advertise; peers treat these as constrained links. */
    const val MSMNILE_CALL_KBPS = 110
    const val LEGACY_COC_CALL_KBPS = 160
    const val LEGACY_CLASSIC_ADVERTISED_KBPS = 250

    private const val REVIEWED = "2026-10-09"

    /** Qualcomm platform stack (ro.hardware=qcom) before the Bluetooth module (API <= 32). */
    val QCOM_LEGACY_FAMILY = DeviceMatcher(sdkMax = 32, hardware = setOf("qcom"))
    val QCOM_MSMNILE_FAMILY = QCOM_LEGACY_FAMILY.copy(boards = setOf("msmnile"), boardPrefixes = setOf("sm8150"))
    /** "curtana" is a device codename (Redmi Note 9S/9 Pro) kept from the former check. */
    val QCOM_ATOLL_FAMILY = QCOM_LEGACY_FAMILY.copy(boards = setOf("atoll", "curtana"), boardPrefixes = setOf("sm7125"))

    private val R240P = VideoResolution(426, 240, "240p")
    private val R360P = VideoResolution(640, 360, "360p")

    private val LEGACY_CALL_EVIDENCE = listOf(
        "benchmarks/runs/three_phone_2026-10-01",
        "benchmarks/runs/three_phone_2026-10-02",
        "benchmarks/runs/tracing_2c59d214",
        "benchmarks/runs/20261003-024421-asus-m31-gatt-call-rawpan320240-vp9-smoke",
        "benchmarks/runs/calls-noloss-m31asus-51cc8028 (cost: CoC audio batch fill p50 380 ms, playout 500-800 ms)",
    )
    private const val LEGACY_CALL_NOTE =
        "Historical ASUS (msmnile) and Redmi Note 9 Pro (atoll) call failures before pacing; no Qualcomm, ASUS or " +
            "AOSP document names these limits. Counter-evidence is listed; the relief A/B is pending."
    private val LEGACY_CALL_SWITCHES =
        listOf("test_call_legacy_relief=true (call paths only; every LEGACY_QUALCOMM_CALL entry)")

    // --- Sources (each fetched and checked on the review date) -------------------------------
    private val LE_COC_SERVER_FREE_FIX = PolicySource(
        AOSP_COMMIT,
        "https://android.googlesource.com/platform/system/bt/+/d3c9966624530772fbdc469179726bd9191314c5",
        "'LE-COC: Free LE-COC server resource when BluetoothServerSocket is closed' (2020-01-16, b/144148429): " +
            "closed LE CoC servers kept FDs, PSMs and security IDs; in android11-release, backported to " +
            "android10-qpr2/qpr3, absent from android10-release and android10-qpr1",
    )
    private val LE_COC_SERVER_FREE_A10 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android10-release/btif/src/btif_sock_l2cap.cc#250",
        "btsock_l2cap_free_l: an LE CoC server only calls BTA_JvFreeChannel (no BTA_JvL2capStopServer)",
    )
    private val JV_SECURITY_IDS = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android10-release/bta/include/bta_jv_api.h#72",
        "BTA_JV_NUM_SERVICE_ID = 18 security IDs shared by RFCOMM/L2CAP/LE CoC servers (Android 10 and 11)",
    )
    private val LE_COC_CLIENT_SECURITY_FIX = PolicySource(
        AOSP_COMMIT,
        "https://android.googlesource.com/platform/system/bt/+/f4a911c0de8a29437c4a90eb30caa0081bf382e8",
        "'Fix L2cap LE COC security record leaks' (2021-07-27, b/193142224): LE CoC clients registered " +
            "security records that leaked; first in android12-release",
    )
    private val LE_COC_CLIENT_SECURITY_FIX_MODULE = PolicySource(
        AOSP_COMMIT,
        "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/f1ffeeb52168397588be2846d76a06faea1ed1ed",
        "the same fix in packages/modules/Bluetooth (2021-07-27, b/193142224): before it, L2CA_RegisterLECoc(psm, " +
            "p_cb_info, sec_level, cfg) called BTM_SetSecurityLevel(false, \"\", 0, sec_level, psm, 0, 0) for every " +
            "registration and gap_conn freed records by service ID; after it, outgoing-only registrations add no record " +
            "and cleanup uses BTM_SecClrServiceByPsm (diff reviewed 2026-10-10)",
    )
    private val LE_COC_CLIENT_SECURITY_A11 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android11-release/stack/l2cap/l2c_api.cc#433",
        "android11-release L2CA_RegisterLECoc(psm, p_cb_info) registers no security record; gap_conn.cc:272 registers " +
            "the client record under the JV security ID, gap_conn.cc:1156 frees it (BTM_SecClrService) and " +
            "bta_jv_act.cc:363 frees the JV ID on close, so the f1ffeeb5 leak is absent from Android 11",
    )
    private val CONTEXT_MAP_A11 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android11-release/src/com/android/bluetooth/gatt/ContextMap.java#283",
        "removeConnectionsByAppId iterates mConnections without synchronization (android10-release line 276 too)",
    )
    private val CONTEXT_MAP_FIX = PolicySource(
        AOSP_COMMIT,
        "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/1a2c1e97d203bd8e635104c72abdba16944ee3b6",
        "'Fully synchronize access to gatt connections' (2021-02-16, b/178334266): ConcurrentModificationException " +
            "iterating mConnections; first in android12-release, in no android10/11 branch",
    )
    private val GATT_UNREGISTER_A10 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android10-release/src/com/android/bluetooth/gatt/GattService.java#2190",
        "an exception in the ContextMap removal skips gattClientUnregisterAppNative, leaving the client registered",
    )
    private val JV_START_SERVER_A10 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android10-release/bta/jv/bta_jv_act.cc#1090",
        "bta_jv_l2cap_start_server takes one JV security ID per server; only bta_jv_l2cap_stop_server frees it",
    )
    private val EATT_REFUSED_A12 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android12-release/stack/l2cap/l2c_ble.cc#654",
        "a refused EATT (ECFC) response fails only the first of the 5 channels; the others stay in " +
            "CST_W4_L2CAP_CONNECT_RSP with the EATT registration (stack/eatt/eatt.cc:37-45 sets no pL2CA_ConnectCfm_Cb); " +
            "android13-release is the same; android14-release adds l2cble_handle_connect_rsp_neg over pending_ecoc_connection_cids",
    )
    private val EATT_SIGNAL_ID_A12 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android12-release/stack/l2cap/l2c_ble.cc#927",
        "an LE CoC response matches the first CCB with that signal id; l2cu_allocate_ccb does not reset local_id; " +
            "l2c_csm_indicate_connection_open then calls the null callback",
    )
    private val EATT_START_A12 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/system/bt/+/refs/heads/android12-release/stack/gatt/gatt_main.cc#416",
        "EATT starts on every LE ATT link-up as central; l2c_api.cc:529-540 allocates the CoC CCB before link-up, " +
            "so on a fresh ACL it is ahead of EATT's",
    )
    private val CDD_VP9_DECODE = PolicySource(
        CDD,
        "https://source.android.com/docs/compatibility/14/android-14-cdd",
        "5.3.7: VP9 decoding of the SD profiles is a MUST on every device (Android 10-16) while no VP9 encoder is " +
            "required (5.2), so VP9 is the call codec every peer decodes; supports the VP9 choice, not the family limits",
    )
    private const val LEGACY_CALL_COUNTER_EVIDENCE =
        "benchmarks/runs/bmaraw-ab-stream-9c121b30-m31asus-r1 (counter-evidence: the ASUS received native AV1 640x480 " +
            "at 300-1000 kbps over CoC and GATT with 0 freezes, 14.3-14.8 FPS worst window, in 12 stream cases)"

    private val ADAPTER_TOGGLE_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#disable()",
        "deprecated in API 33: for apps targeting TIRAMISU or above enable()/disable() always fail and return false",
    )
    private val CDD_CODEC_NAMES = PolicySource(
        CDD,
        "https://source.android.com/docs/compatibility/16/android-16-cdd",
        "5.1.9 Media Codec Security (same in Android 10-16): Codec 2.0 STRONGLY RECOMMENDED [C-SR]; " +
            "c2.android.* and OMX.google.* names MUST be AOSP-based [C-3-3]/[C-2-2]",
    )
    private val CODEC2_DEFAULT = PolicySource(
        AOSP_COMMIT,
        "https://android.googlesource.com/platform/frameworks/av/+/3c4b455145752aa57ee4777ac94e8b4c32fd59f7",
        "'media: enable Codec 2.0 by default' (2019-05-10), in android10-release",
    )
    private val CCODEC_QP = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android12-release/media/codec2/sfplugin/CCodec.cpp#1151",
        "video-qp-{i,p,b}-{min,max} become C2StreamPictureQuantizationTuning only on the Codec2 path; " +
            "media/libstagefright/ACodec.cpp (OMX) has no video-qp mapping",
    )
    private val QP_REWORK = PolicySource(
        AOSP_COMMIT,
        "https://android.googlesource.com/platform/frameworks/av/+/a9a724a0d1b6947c280fd2e3f01b78f3b12bb9a4",
        "'Rework how QP bounds are passed' (2021-03-10)",
    )
    private val QP_FEATURE = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_QpBounds",
        "FEATURE_QpBounds (API 31): the encoder supports the video-qp bound keys",
    )
    private val SOFT_OPUS_OMX = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android10-release/media/libstagefright/codecs/opus/dec/SoftOpus.cpp#424",
        "OMX.google.opus.decoder takes the first three input buffers as OpusHead, pre-skip and pre-roll by count",
    )
    private val SOFT_OPUS_C2 = PolicySource(
        AOSP_SOURCE,
        "https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android10-release/media/codec2/components/opus/C2SoftOpusDec.cpp#247",
        "c2.android.opus.decoder accepts a bare OpusHead or the combined AOPUSHDR/AOPUSDLY/AOPUSPRL block",
    )
    private val OPUS_CSD_DOC = PolicySource(
        API_REFERENCE,
        "https://developer.android.com/reference/android/media/MediaCodec",
        "codec-specific data table: Opus csd-0 identification header, csd-1 pre-skip (ns), csd-2 seek pre-roll (ns)",
    )

    /** Master 9284736 entry in the full registry format (same ID, scope and evidence). */
    val GATT_CONTEXT_MAP_RACE = DevicePolicyEntry(
        id = "gatt-context-map-race",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        title = "Android 10/11 GattService ContextMap race: deferred GATT close",
        matcher = DeviceMatcher(sdkMax = 30),
        effects = listOf(DeferredGattClientClose),
        behaviour = "Close a GATT client only after its disconnect completes or 2 s (GattClientTeardown); an expired " +
            "control attempt is cancelled first so its late callbacks do nothing.",
        sources = listOf(CONTEXT_MAP_A11, CONTEXT_MAP_FIX, GATT_UNREGISTER_A10),
        sourceStatus = OFFICIAL,
        evidence = listOf(
            "benchmarks/runs/bmaraw-ab-stream-9c121b30-s24a15s-run2c (0017 a15s-bluetooth-19107.log:690-710: " +
                "ConcurrentModificationException 22 ms after clientDisconnect)",
            "benchmarks/runs/lane-b-r3-529f36cc (0007: the same exception class on the ASUS, Android 11)",
            "benchmarks/runs/bmaraw-ab-stream-9c121b30-s24a15s-run3d",
        ),
        confidence = HIGH,
        confidenceNote = "high for the race; unproven as the cause of the run3d discovery outage",
        reviewed = REVIEWED,
        origin = "GattClientTeardown.deferredFor (d587725; scoped through the first DeviceFamilyPolicy in 9284736)",
        legacySwitches = listOf("test_deferred_gatt_close=true|false (forces either on any stack)"),
    )

    /** Master 9284736 entry in the full registry format (same ID, scope and evidence). */
    val LE_COC_SERVER_LEAK = DevicePolicyEntry(
        id = "le-coc-server-leak",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        title = "Android 10 LE CoC server leak: one long-lived listener per role",
        matcher = DeviceMatcher(sdkMax = 29),
        effects = listOf(SharedLeCocListener),
        behaviour = "Keep one long-lived LE CoC listener per role and lease it to sessions (CocListenerHub); fixed " +
            "stacks open and close one listener per session.",
        sources = listOf(LE_COC_SERVER_FREE_A10, LE_COC_SERVER_FREE_FIX, JV_SECURITY_IDS, JV_START_SERVER_A10),
        sourceStatus = OFFICIAL,
        evidence = listOf(
            "benchmarks/runs/bmaraw-ab-call-e3351065-r1 (0007 a15s-bluetooth-25059.log:189-190: 18 GAP opens, then " +
                "every listen and connect failed)",
            "benchmarks/runs/lane-a-matrix-529f36cc",
        ),
        confidence = HIGH,
        confidenceNote = "high for stock Android 10; OEM backports of d3c996662453 (android10-qpr2/qpr3 carry it) " +
            "are not detected",
        reviewed = REVIEWED,
        origin = "CocListeners.sharedEnabledFor (d587725; scoped to Android 10 through the first DeviceFamilyPolicy in 9284736)",
        legacySwitches = listOf("test_coc_shared_listener=true|false (forces either on any stack)"),
    )

    /** Master 81a3289 entry in the full registry format (same ID, scope and evidence). */
    val LE_COC_EATT_STRANDED_CHANNELS = DevicePolicyEntry(
        id = "le-coc-eatt-stranded-channels",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        title = "Android 12/13 refused-EATT stranded L2CAP channels: LE CoC client on a fresh ACL",
        matcher = DeviceMatcher(sdkMin = 31, sdkMax = 33),
        effects = listOf(FreshAclLeCocClient),
        behaviour = "Read the PSM over GATT, close that GATT client and wait (<= 2 s) for the ACL to drop, " +
            "connect the CoC on a fresh ACL, then reattach a GATT client for priority/PHY (BleCocClient).",
        sources = listOf(EATT_REFUSED_A12, EATT_SIGNAL_ID_A12, EATT_START_A12),
        sourceStatus = OFFICIAL,
        evidence = listOf(
            "benchmarks/runs/xpair2-s24m31-431d40dc (0003 m31-bluetooth-9989.log 19:56:11.217-12.433: M31 (Android 12) " +
                "SIGSEGV pc 0 in l2c_csm_indicate_connection_open 12 ms after its CoC request to the S24 (Android 14, " +
                "EATT refused 0x0005 unencrypted); 6 of 6 saved M31 CoC connects after a refused EATT crashed, 0 of 293 " +
                "without one)",
        ),
        confidence = HIGH,
        confidenceNote = "high for the crash mechanism; the fresh-ACL avoidance is unmeasured on phones",
        reviewed = "2026-10-10",
        origin = "BleCocClient.freshAclFor (81a3289; first DeviceFamilyPolicy entry format)",
        legacySwitches = listOf("test_coc_fresh_acl=true|false (forces either on any stack)"),
    )

    /**
     * The legacy Qualcomm private-call protections. privateCallStackFingerprint() is their single
     * call-path gate: the experimental legacy relief (DEBUG `test_call_legacy_relief`) hands call
     * paths a traits view with exactly these entries off. Relay and stream rules keep them.
     */
    val LEGACY_QUALCOMM_CALL: Set<String> = setOf(
        "qcom-legacy-gatt-call",
        "qcom-legacy-coc-call",
        "qcom-legacy-classic-call",
        "qcom-msmnile-call",
        "qcom-atoll-coc-call",
        "qcom-legacy-call-vp9-software-decoder",
    )

    private val coreEntries: List<DevicePolicyEntry> = listOf(
        DevicePolicyEntry(
            id = "qcom-legacy-gatt-call",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Legacy Qualcomm GATT call profile",
            matcher = QCOM_LEGACY_FAMILY,
            effects = listOf(
                CallVideoCap(setOf(BLE_GATT), maxResolution = R240P, maxFps = 15, maxVideoKbps = 150),
                CallAudioBitrate(setOf(BLE_GATT), 16_000),
                CallAudioPairing(setOf(BLE_GATT)),
                CallMediaFlushInterval(setOf(BLE_GATT), 40L),
                CallVp9Compatibility(setOf(BLE_GATT)),
            ),
            behaviour = "GATT private calls send VP9 (not AV1) at <=426x240, <=15 FPS, <=150 kbps with Opus 16 kbps " +
                "in paired records and 40 ms write coalescing, and advertise VP9-only decoding.",
            sources = listOf(CDD_VP9_DECODE),
            sourceStatus = EVIDENCE_ONLY,
            evidence = LEGACY_CALL_EVIDENCE + LEGACY_CALL_COUNTER_EVIDENCE,
            confidence = MEDIUM,
            reviewed = REVIEWED,
            origin = "BluetoothStackCompatibility.legacyQualcommGattCallFlushIntervalMs (40 ms) and the legacyGatt " +
                "branches of PrivateCallMedia.privateCallVideoSettings/AudioBitRate/pairPrivateCallAudioForRoute, " +
                "VideoCodec.privateCallCodec, privateCallDecodeCodecMask",
            confidenceNote = LEGACY_CALL_NOTE,
            legacySwitches = LEGACY_CALL_SWITCHES,
        ),
        DevicePolicyEntry(
            id = "qcom-legacy-coc-call",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Legacy Qualcomm LE CoC call profile",
            matcher = QCOM_LEGACY_FAMILY,
            effects = listOf(
                CallVideoCap(setOf(BLE_L2CAP), maxResolution = R240P, maxFps = 20, maxVideoKbps = LEGACY_COC_CALL_KBPS),
                CallAdvertisedMaxBitrate(setOf(BLE_L2CAP), LEGACY_COC_CALL_KBPS),
                CallAudioBitrate(setOf(BLE_L2CAP), 16_000),
                CallAudioPairing(setOf(BLE_L2CAP)),
                CallMediaFlushInterval(setOf(BLE_L2CAP), 20L),
                CallConstrainedPlayback(setOf(BLE_L2CAP)),
            ),
            behaviour = "CoC private calls cap video at <=426x240, <=20 FPS, <=160 kbps, advertise 160 kbps to the peer, " +
                "send Opus 16 kbps in 20-packet records, coalesce writes every 20 ms and start with the constrained call buffer.",
            sources = emptyList(),
            sourceStatus = EVIDENCE_ONLY,
            evidence = LEGACY_CALL_EVIDENCE + LEGACY_CALL_COUNTER_EVIDENCE,
            confidence = MEDIUM,
            reviewed = REVIEWED,
            origin = "PrivateCallMedia legacyCoc branches, privateCallCapabilityMaxBitrateKbps (CONSTRAINED_COC_BITRATE_KBPS), " +
                "privateCallMediaFlushIntervalMs, privateCallPlaybackDelayProfile",
            confidenceNote = LEGACY_CALL_NOTE,
            legacySwitches = LEGACY_CALL_SWITCHES,
        ),
        DevicePolicyEntry(
            id = "qcom-legacy-classic-call",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Legacy Qualcomm Classic call profile",
            matcher = QCOM_LEGACY_FAMILY,
            effects = listOf(
                CallVideoCap(setOf(CLASSIC_RFCOMM), maxResolution = R360P, maxFps = 15, maxVideoKbps = 200),
                CallAdvertisedMaxBitrate(setOf(CLASSIC_RFCOMM), LEGACY_CLASSIC_ADVERTISED_KBPS),
                CallAudioBitrate(setOf(CLASSIC_RFCOMM), 16_000),
                CallAudioPairing(setOf(CLASSIC_RFCOMM)),
                CallMediaFlushInterval(setOf(CLASSIC_RFCOMM), 20L),
                CallExtraVideoQueue(setOf(CLASSIC_RFCOMM), 8),
                CallConstrainedPlayback(setOf(CLASSIC_RFCOMM)),
                CallVp9Compatibility(setOf(CLASSIC_RFCOMM)),
            ),
            behaviour = "Classic private calls send VP9 at <=640x360, <=15 FPS, <=200 kbps, advertise 250 kbps, send Opus 16 kbps " +
                "in paired records, coalesce writes every 20 ms, add 8 decode-queue frames and start with the constrained call buffer.",
            sources = listOf(CDD_VP9_DECODE),
            sourceStatus = EVIDENCE_ONLY,
            evidence = LEGACY_CALL_EVIDENCE + LEGACY_CALL_COUNTER_EVIDENCE,
            confidence = MEDIUM,
            reviewed = REVIEWED,
            origin = "PrivateCallMedia legacyRfcomm branch (LEGACY_RFCOMM_SEND_BITRATE_KBPS 200, CONSTRAINED_RFCOMM_BITRATE_KBPS 250), " +
                "privateCallVideoQueueCapacity",
            confidenceNote = LEGACY_CALL_NOTE,
            legacySwitches = LEGACY_CALL_SWITCHES,
        ),
        DevicePolicyEntry(
            id = "qcom-msmnile-call",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Qualcomm msmnile (SM8150) call cap",
            matcher = QCOM_MSMNILE_FAMILY,
            effects = listOf(
                CallVideoCap(ALL_TRANSPORTS, maxResolution = R240P, maxFps = 15),
                CallVideoCap(setOf(BLE_L2CAP, CLASSIC_RFCOMM), maxVideoKbps = MSMNILE_CALL_KBPS),
                CallAdvertisedMaxBitrate(setOf(BLE_L2CAP, CLASSIC_RFCOMM), MSMNILE_CALL_KBPS),
                CallVp9Compatibility(ALL_TRANSPORTS),
            ),
            behaviour = "On every transport calls use VP9 at <=426x240 and <=15 FPS; on CoC and Classic video is capped " +
                "and advertised at 110 kbps (the 'ASUS legacy 426x240 cap').",
            sources = listOf(CDD_VP9_DECODE),
            sourceStatus = EVIDENCE_ONLY,
            evidence = LEGACY_CALL_EVIDENCE + LEGACY_CALL_COUNTER_EVIDENCE + listOf(
                "benchmarks/runs/media-workflow-7396b46e-call",
                "benchmarks/runs/calls-noloss-m31asus-51cc8028",
            ),
            confidence = MEDIUM,
            reviewed = REVIEWED,
            origin = "BluetoothStackCompatibility.isLegacyQualcommMsmnileStack; PrivateCallMedia legacyMsmnile and " +
                "MSMNILE_COC_BITRATE_KBPS; VideoCodec.privateCallCodec CoC exception",
            confidenceNote = LEGACY_CALL_NOTE,
            legacySwitches = LEGACY_CALL_SWITCHES,
        ),
        DevicePolicyEntry(
            id = "qcom-atoll-coc-call",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Qualcomm atoll (SM7125) CoC call cap",
            matcher = QCOM_ATOLL_FAMILY,
            effects = listOf(CallVideoCap(setOf(BLE_L2CAP), maxFps = 15, maxVideoKbps = 110)),
            behaviour = "CoC calls from this family send <=15 FPS and <=110 kbps (the peer is still told 160 kbps).",
            sources = emptyList(),
            sourceStatus = EVIDENCE_ONLY,
            evidence = listOf("benchmarks/runs/three_phone_2026-10-01", "benchmarks/runs/three_phone_2026-10-02"),
            confidence = LOW,
            reviewed = REVIEWED,
            origin = "BluetoothStackCompatibility.isLegacyQualcommAtollStack; PrivateCallMedia ATOLL_COC_SEND_BITRATE_KBPS",
            confidenceNote = LEGACY_CALL_NOTE,
            legacySwitches = LEGACY_CALL_SWITCHES,
        ),
        DevicePolicyEntry(
            id = "qcom-legacy-call-vp9-software-decoder",
            area = PolicyArea.CODEC,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Legacy Qualcomm call VP9 software decoder",
            matcher = QCOM_LEGACY_FAMILY,
            effects = listOf(CallPreferredDecoder(StreamProtocol.CODEC_VP9, "c2.android.vp9.decoder")),
            behaviour = "Incoming call VP9 is decoded by the AOSP software decoder c2.android.vp9.decoder (when it " +
                "accepts the format) instead of the framework's first choice.",
            sources = emptyList(),
            sourceStatus = EVIDENCE_ONLY,
            evidence = listOf("benchmarks/runs/20261003-024421-asus-m31-gatt-call-rawpan320240-vp9-smoke"),
            confidence = LOW,
            reviewed = REVIEWED,
            origin = "BluetoothStackCompatibility.privateCallPreferredDecoderName",
            confidenceNote = LEGACY_CALL_NOTE,
            legacySwitches = LEGACY_CALL_SWITCHES,
        ),
        DevicePolicyEntry(
            id = "qcom-legacy-call-relay-gatt-acknowledged-writes",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.EXPERIMENTAL,
            title = "Legacy Qualcomm call-relay GATT writes with response (Experimental arm: applied only with test_relay_gatt_acknowledged_writes=true)",
            matcher = QCOM_LEGACY_FAMILY,
            effects = listOf(CallRelayAcknowledgedGattWrites),
            behaviour = "Only with the Experimental arm test_relay_gatt_acknowledged_writes=true (DEBUG): the hidden one-hop " +
                "call relay opens its downstream GATT link with acknowledged writes paced at 24 KB/s. Off by default: the " +
                "relay writes without response, as on every other phone.",
            sources = emptyList(),
            sourceStatus = EVIDENCE_ONLY,
            evidence = listOf(
                "none located for the rule itself: it predates the git history and no retained run before 2026-11-10 has a legacy-Qualcomm call relay",
                "benchmarks/runs/l2q27ab-20261110g: relayed calls S22 -> ASUS -> A15s, interleaved A/B on one build; rule on (control) 0 of 3 calls held 60 s, " +
                    "each with the A15s dropping write responses (\"Sending response failed\"), \"GATT video write timed out\", 25-43 records forwarded, relay closed at 18-26 s; " +
                    "rule off (test_device_policy_off) 2 of 3 held 60 s with 4023-4034 records forwarded and none of those symptoms, the third failed on the other hop " +
                    "(S22-ASUS notification status 129); ASUS -> S22 -> A15s (S22 relays, rule does not match) held 2 of 2",
            ),
            confidence = LOW,
            reviewed = REVIEWED,
            origin = "BluetoothPrivateCallRelay: legacyQualcommGattCallFlushIntervalMs(...) > 0 as acknowledgedWrites",
            arm = PolicyArm("test_relay_gatt_acknowledged_writes=true") { GattSwitches.relayAcknowledgedWrites },
        ),
        DevicePolicyEntry(
            id = "qcom-msmnile-classic-source-balanced-scan",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "msmnile Classic source: no continuous low-latency viewer scan",
            matcher = QCOM_MSMNILE_FAMILY,
            effects = listOf(ClassicSourceBalancedViewerScan),
            behaviour = "A Classic broadcast source on this family scans for viewer requests with balanced scanning " +
                "instead of continuous low latency.",
            sources = emptyList(),
            sourceStatus = EVIDENCE_ONLY,
            evidence = listOf("benchmarks/runs/coverage-av1-c39c75b6", "benchmarks/runs/coverage-av1-5a709ed5"),
            confidence = MEDIUM,
            reviewed = REVIEWED,
            origin = "BluetoothStackCompatibility.LegacyQualcommClassicCompatibility / useContinuousLowLatencyViewerDiscovery",
        ),
        GATT_CONTEXT_MAP_RACE,
        LE_COC_SERVER_LEAK,
        LE_COC_EATT_STRANDED_CHANNELS,
        DevicePolicyEntry(
            id = "app-bluetooth-toggle-allowed",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "App may toggle Bluetooth (API <= 32)",
            matcher = DeviceMatcher(sdkMax = 32),
            effects = listOf(AppBluetoothRestartAllowed),
            behaviour = "The DEBUG bt_restart control may turn the adapter off and on (idle only); refused on API 33+.",
            sources = listOf(ADAPTER_TOGGLE_DOC),
            sourceStatus = OFFICIAL,
            evidence = listOf("none: refusal path only; no phone run yet"),
            confidence = HIGH,
            reviewed = REVIEWED,
            origin = "BluetoothRestart.allowed (LAST_APP_TOGGLE_SDK = 32), commit d587725",
        ),
        DevicePolicyEntry(
            id = "codec2-encoder-tie-break",
            area = PolicyArea.CODEC,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "Prefer Codec2 encoders at equal rank",
            matcher = DeviceMatcher(),
            component = CodecComponentMatcher(prefixes = setOf("c2.")),
            effects = listOf(PreferredEncoderTieBreak),
            behaviour = "Among encoders equal on hardware, codec and surface input, Codec2 (c2.*) components sort " +
                "before OMX ones; only the Codec2 path honours QP bounds.",
            sources = listOf(CDD_CODEC_NAMES, CODEC2_DEFAULT, CCODEC_QP),
            sourceStatus = PARTIAL,
            evidence = listOf("none: ordering rule; no A/B against OMX-first"),
            confidence = MEDIUM,
            reviewed = REVIEWED,
            origin = "VideoCodec.availableEncoders sort: thenBy { encoderName.startsWith(\"c2.\") }",
        ),
        DevicePolicyEntry(
            id = "aosp-codec2-opus-decoder-first",
            area = PolicyArea.CODEC,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Try AOSP Codec2 Opus decoders first",
            matcher = DeviceMatcher(),
            component = CodecComponentMatcher(prefixes = setOf("c2.android.")),
            effects = listOf(PreferredOpusDecoder),
            behaviour = "Opus decoding tries c2.android.* components before the framework's choice and the rest.",
            sources = listOf(SOFT_OPUS_C2, SOFT_OPUS_OMX, OPUS_CSD_DOC, CDD_CODEC_NAMES),
            sourceStatus = OFFICIAL,
            evidence = listOf("benchmarks/runs/lane-a-matrix-529f36cc", "benchmarks/runs/a15s-opusfix-5a2d93a7"),
            confidence = HIGH,
            reviewed = REVIEWED,
            origin = "OpusAudioDecoder.opusDecoderCandidates",
        ),
        DevicePolicyEntry(
            id = "c2-android-vp9-decoder-in-place-recovery",
            area = PolicyArea.CODEC,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Recreate a failed AOSP software VP9 call decoder",
            matcher = DeviceMatcher(),
            component = CodecComponentMatcher(names = setOf("c2.android.vp9.decoder")),
            effects = listOf(DecoderInPlaceRecovery(maxAttempts = 2)),
            behaviour = "When c2.android.vp9.decoder throws a CodecException in a call, it is recreated in place " +
                "(at most twice) and waits for a keyframe.",
            sources = emptyList(),
            sourceStatus = EVIDENCE_ONLY,
            evidence = listOf("benchmarks/runs/three_phone_2026-10-01"),
            confidence = LOW,
            reviewed = REVIEWED,
            origin = "PrivateCallMedia.canRecoverPrivateCallVideoDecoder",
        ),
    ) + proposals()

    /** Sourced proposals: no effect; the device_policy line reports which would match each phone. */
    private fun proposals(): List<DevicePolicyEntry> = listOf(
        proposal(
            id = "android-10-11-le-coc-security-id-budget",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "Android 10/11 LE CoC security-ID budget",
            matcher = DeviceMatcher(sdkMax = 30),
            behaviour = "Turn on the CoC registration count and automatic GATT fallback (test_coc_auto_gatt, Q4 option A) " +
                "by default on these stacks. Not promoted (source review 2026-10-10): the client security-record leak " +
                "that f1ffeeb5/f4a911c0 fix came with the Android 12 L2CA_RegisterLECoc(sec_level) refactor; " +
                "android11-release registers a client record under its JV security ID and frees it on close, so the " +
                "Android 11 client premise is unsupported. Android 10 servers stay covered by le-coc-server-leak (API <= 29).",
            sources = listOf(
                LE_COC_SERVER_FREE_FIX,
                LE_COC_CLIENT_SECURITY_FIX,
                LE_COC_CLIENT_SECURITY_FIX_MODULE,
                LE_COC_CLIENT_SECURITY_A11,
                JV_SECURITY_IDS,
            ),
            evidence = listOf("benchmarks/runs/lane-a-matrix-529f36cc", "benchmarks/runs/bmaraw-ab-call-9c121b30-s24a15s-run2c"),
            confidence = LOW,
            status = PARTIAL,
        ),
        proposal(
            id = "android-11-coc-small-sdu-coalescing",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.EXPERIMENTAL,
            title = "Android <= 11 LE CoC: coalesce small SDUs",
            matcher = DeviceMatcher(sdkMax = 30),
            behaviour = "Write CoC media in SDUs of up to 8,000 bytes instead of 245-byte single-PDU SDUs, so a 50 KB " +
                "keyframe is 7 SDUs (about 10 or fewer outstanding) instead of 205, without pacing or drops. On these " +
                "stacks every app write is one SDU in its own buffer, L2CAP counts congestion in queued SDUs and a write " +
                "that meets congestion is freed. Candidate for the ASUS (Android 11) Bluetooth crash; no backtrace yet. " +
                "DEBUG test_coc_coalesce_sdus=true applies it where this matcher holds; it replaces test_coc_single_pdu_sdus " +
                "there (the 251+4-octet K-frame split returns for MPS-251 peers).",
            sources = listOf(
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/system/bt/+/refs/heads/android11-release/btif/src/btif_sock_l2cap.cc#1032",
                    "btsock_l2cap_signaled reads one SOCK_SEQPACKET message per signal into its own BT_HDR and posts one " +
                        "BTA_JvL2capWrite per message, so each app write is one SDU buffer",
                ),
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/system/bt/+/refs/heads/android11-release/bta/jv/bta_jv_act.cc#1182",
                    "bta_jv_l2cap_write frees the message when the channel is congested (status FAILURE); btif " +
                        "on_l2cap_write_done ignores the status and counts the bytes as sent",
                ),
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/system/bt/+/refs/heads/android11-release/stack/l2cap/l2c_main.cc#997",
                    "L2CA_DataWrite frees the data and returns L2CAP_DW_FAILED when cong_sent is set",
                ),
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/system/bt/+/refs/heads/android11-release/stack/l2cap/l2c_utils.cc#3440",
                    "l2cu_check_channel_congestion: congested when the xmit_hold_q SDU count exceeds buff_quota",
                ),
                PolicySource(
                    AOSP_COMMIT,
                    "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/471249f472d11297915cbcd9e52736219607833b",
                    "'Fix L2CAP hold queue null pointer issue' (2021-08, Xiaomi): l2c_csm.cc enqueued a null buffer",
                ),
                PolicySource(
                    AOSP_COMMIT,
                    "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/46bd51ede06ccda8aefaba46172d75d88f61e510",
                    "'Fix potential data loss when writing to congested L2CAP CoC' (2022-03-24, b/223974328); first in android13-release",
                ),
            ),
            evidence = listOf(
                "none attributed: the ASUS Bluetooth crash has no backtrace; CocSocketOutput's >11.6 KB SDU corruption bounds the size",
            ),
            confidence = LOW,
            status = PARTIAL,
        ),
        proposal(
            id = "pre-android-13-coc-congested-write-loss",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.EXPERIMENTAL,
            title = "Android <= 12 CoC data loss under congestion",
            matcher = DeviceMatcher(sdkMax = 32),
            behaviour = "Forwarding LE CoC writers (call relays, stream relays and forwarders, and a helper's parent link " +
                "once it becomes a relay hop) write SDUs of up to 8,000 bytes (within the peer's receive limit) instead of " +
                "245-byte single-PDU SDUs, so the SDU-counted L2CAP queue stays below its congestion quota for the same " +
                "bytes; nothing is paced or dropped and endpoint links are unchanged. Before Android 13 btif_sock_thread " +
                "could read stale poll entries while several sockets were busy and drop data read from a socket whose " +
                "channel was congested. A 'Secure link decrypt failed' or record-sequence failure on a hop from such a " +
                "forwarder is a candidate stack data loss (the hop_integrity line tells a shifted stream from a changed " +
                "byte). DEBUG test_relay_coc_coalesce=true applies it where this matcher holds.",
            sources = listOf(
                PolicySource(
                    AOSP_COMMIT,
                    "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/46bd51ede06ccda8aefaba46172d75d88f61e510",
                    "'Fix potential data loss when writing to congested L2CAP CoC' (2022-03-24, b/223974328): several poll " +
                        "events in one poll call, poll_count raised without updating pfds, process_data_sock reads stale " +
                        "entries and the data read is dropped because the channel is congested; first in android13-release",
                ),
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/system/bt/+/refs/heads/android12-release/btif/src/btif_sock_thread.cc",
                    "android12-release before the fix: process_data_sock loops to ts[h].poll_count over the pfds prepared " +
                        "before poll",
                ),
            ),
            evidence = listOf(
                "benchmarks/runs (2026-10-26..28, LE CoC): every hop-integrity failure had a pre-13 forwarder as the " +
                    "transmitter: M31 (API 31) relay -> ASUS phyprio-ab-20261028/trace-pp11-a15s-m31-asus-ble-ctl (tag failure, " +
                    "header intact), M31 relay -> A15s relay-showcase-20261026/trace-y1-asus-s22-m31-a15s-video-ble and " +
                    "trace-m1-asus-s24-m31-a15s-video-ble-CPdef, A15s (API 29) relay -> S24/ASUS/M31 trace-h1, 55-t03, " +
                    "45-x03, 53-t07, trace-s6a, trace-k2, 69-n12 (tag failures, skipped sequences, damaged headers)",
                "no hop-integrity failure on a direct (non-forwarding) CoC link in the same runs; the ASUS (API 30) relayed " +
                    "17 CoC calls without one; no HCI snoop yet, so stack loss is the leading candidate, not proven",
            ),
            confidence = LOW,
            confidenceNote = "Matches the upstream bug's conditions (two busy sockets, congestion) and the failure signature; " +
                "needs the A/B (M31 and A15s as CoC helpers, both directions, HCI snoop on helper and receiver).",
        ),
        proposal(
            id = "android-14-gatt-mtu-517",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "Android 14+: first GATT MTU request fixes 517",
            matcher = DeviceMatcher(sdkMin = 34),
            behaviour = "Mark the test_gatt_request_mtu experiment as not applicable when either phone runs API 34+ " +
                "(the stack requests 517 for the first client and ignores later requests) and log the negotiated MTU instead. " +
                "A 517-byte MTU does not raise the attribute-value limit: a write-with-response or long read carries at most " +
                "512 bytes of value (MTU-3 = 514 is only the notification / write-without-response payload), and Android 13+ " +
                "enforces that, so an oversized write-with-response or long read fails (reports: a read over 512 bytes fails with status -100 and a disconnect; a 543-byte write-with-response crashed the native stack on Android 13).",
            sources = listOf(
                PolicySource(
                    PLATFORM_DOC,
                    "https://developer.android.com/about/versions/14/behavior-changes-all",
                    "'MTU is set to 517 for the first GATT client requesting an MTU' and later requests on that ACL are disregarded",
                ),
                PolicySource(
                    BLUETOOTH_SPEC,
                    "Bluetooth Core Specification 5.2, Vol 3 Part F, 3.2.9 'Long attribute values'",
                    "the maximum length of an attribute value is 512 octets; 517 = 512 value + 3 ATT header + 2 offset bytes of a long operation, so it is not a payload size",
                ),
            ),
            evidence = listOf("none: no MTU-experiment run on API 34+ yet"),
            confidence = HIGH,
            confidenceNote = "high for the 517 negotiation (Android 14 behaviour-change page) and for the 512-byte limit (spec); " +
                "the Android 13+ enforcement rests on library maintainer reports (Android-BLE-Library, Nordic; FlutterBluePlus #984, " +
                "RxAndroidBle) and is not yet reproduced on an owned phone",
            supersededBy = "gatt-mtu-fixed-517",
        ),
        proposal(
            id = "ble-scan-start-quota",
            area = PolicyArea.BLUETOOTH_TRANSPORT,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "BLE scan-start quota (5 per 30 s)",
            matcher = DeviceMatcher(),
            behaviour = "Count scan starts per 30 s and keep mode switches and discovery restarts under 5 per window. The " +
                "stack's refusal is silent (no onScanFailed(SCAN_FAILED_SCANNING_TOO_FREQUENTLY) is delivered on any release, " +
                "aosp-bt-versions SCAN-01), so a callback back-off cannot work: the app's own start budget is the protection and " +
                "is implemented as le-scan-start-quota (this proposal is kept for its sources).",
            sources = listOf(
                PolicySource(
                    AOSP_COMMIT,
                    "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/1fdc7c138db776b02bc751fd7a80c519ea3324d1",
                    "'Add protection against LE scanning abuse' (2016-04-13): AppScanStats NUM_SCAN_DURATIONS_KEPT 5, " +
                        "EXCESSIVE_SCANNING_PERIOD_MS 30 s",
                ),
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/android/app/src/com/android/bluetooth/btservice/AdapterService.java",
                    "DeviceConfig defaults scan_quota_count 5, scan_quota_window_millis 30000",
                ),
                PolicySource(
                    API_REFERENCE,
                    "https://developer.android.com/reference/android/bluetooth/le/ScanCallback#SCAN_FAILED_SCANNING_TOO_FREQUENTLY",
                    "error 6 (constant published in API 33); the stack never delivers it for the quota breach (SCAN-01)",
                ),
            ),
            evidence = listOf("none: no code-6 scan failure recorded yet"),
            confidence = HIGH,
            supersededBy = "le-scan-start-quota",
        ),
        proposal(
            id = "background-restriction-visible",
            area = PolicyArea.BACKGROUND,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "Report OEM/system background restriction",
            matcher = DeviceMatcher(sdkMin = 28),
            behaviour = "Log ActivityManager.isBackgroundRestricted() in the device_policy line and tell the user before " +
                "a call, stream or message exchange that the app may be stopped in the background.",
            sources = listOf(
                PolicySource(
                    CDD,
                    "https://source.android.com/docs/compatibility/14/android-14-cdd",
                    "3.5.1 Application Restriction: OEM restrictions beyond the Restricted bucket MUST be user-visible and " +
                        "reversible and isBackgroundRestricted() MUST return true [C-1-1..C-1-7]",
                ),
                PolicySource(
                    PLATFORM_DOC,
                    "https://source.android.com/docs/core/power/app_mgmt",
                    "OEMs may keep custom restriction methods; a restricted app can be un-restricted by the user",
                ),
            ),
            evidence = listOf("none: not yet logged"),
            confidence = MEDIUM,
            supersededBy = "bg-background-restricted",
        ),
        proposal(
            id = "one-ui-never-sleeping-apps",
            area = PolicyArea.BACKGROUND,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "One UI: offer 'Never sleeping apps' (implemented as bg-oem-samsung guidance)",
            matcher = DeviceMatcher(skins = setOf(OemSkin.ONE_UI)),
            behaviour = "Implemented by the plain-text entry bg-oem-samsung: when the user turns on background availability " +
                "(store-and-forward carrying, incoming calls), point to Samsung's Never sleeping apps list " +
                "(com.samsung.android.lool, ACTION_OPEN_CHECKABLE_LISTACTIVITY, activity_type 2). One UI gives no guarantee that a " +
                "foreground service keeps running (the Samsung page documents typed foreground services as an Android 14 rule only, and " +
                "community reports of kills on One UI 6 exist), so the advice is guidance, never a promise.",
            sources = listOf(
                PolicySource(
                    VENDOR_DOC,
                    "https://developer.samsung.com/mobile/app-management.html",
                    "Sleeping apps (unused ~3 days) and Deep sleeping apps are restricted; exemptions under Background usage " +
                        "limits; deep link documented; typed foreground services are an Android 14 rule, not a One UI keep-alive guarantee",
                ),
            ),
            evidence = listOf("none: Samsung phones have not run background-availability tests"),
            confidence = MEDIUM,
        ),
        proposal(
            id = "hyperos-autostart-and-no-restrictions",
            area = PolicyArea.BACKGROUND,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "MIUI/HyperOS: autostart and 'No restrictions' (implemented as bg-oem-xiaomi guidance)",
            matcher = DeviceMatcher(skins = setOf(OemSkin.MIUI_HYPEROS)),
            behaviour = "Implemented by the plain-text entry bg-oem-xiaomi (kept here for its sources): explain that autostart is off by default " +
                "and offer the battery 'No restrictions' setting when the user turns on background availability.",
            sources = listOf(
                PolicySource(VENDOR_DOC, "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624",
                    "apps cannot autostart by default; the user allows it per app"),
                PolicySource(VENDOR_DOC, "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1628",
                    "Powerkeeper restrictions, Settings > Battery > App smart power saving, 'No restrictions' lifts them, " +
                        "isBackgroundRestricted() returns true"),
                PolicySource(VENDOR_DOC, "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1625",
                    "starting a page from the background is denied by default; VoIP incoming-call allow-list on request"),
            ),
            evidence = listOf("none: the Xiaomi phone has not run background-availability tests"),
            confidence = MEDIUM,
        ),
        proposal(
            id = "asus-auto-start-manager",
            area = PolicyArea.BACKGROUND,
            validation = PolicyValidation.UPSTREAM_DOCUMENTED,
            title = "ASUS: Auto-start Manager (implemented as bg-oem-asus guidance)",
            matcher = DeviceMatcher(skins = setOf(OemSkin.ASUS)),
            behaviour = "Implemented by the plain-text entry bg-oem-asus (kept here for its sources): point users who enable background " +
                "availability to Settings > Battery > Auto-start Manager and to the battery-draining-app auto-stop option.",
            sources = listOf(
                PolicySource(VENDOR_DOC, "https://www.asus.com/support/faq/1054036/", "Auto-start Manager allows or denies auto-start per app"),
                PolicySource(VENDOR_DOC, "https://rog.asus.com/support/faq/1041329/", "'Detect battery-draining apps' can stop apps in the background"),
            ),
            evidence = listOf("none"),
            confidence = LOW,
        ),
        proposal(
            id = "omx-encoder-qp-bounds-ignored",
            area = PolicyArea.CODEC,
            validation = PolicyValidation.LOCALLY_REPRODUCED,
            title = "OMX encoders ignore QP bounds",
            matcher = DeviceMatcher(),
            component = CodecComponentMatcher(prefixes = setOf("OMX.")),
            behaviour = "Gate the DEBUG QP-bound experiment (test_hw_qp_min/max) on FEATURE_QpBounds and log the standard " +
                "video-qp-* keys as unsupported for OMX components instead of configuring keys that never reach them. " +
                "This covers the standard keys only: OMX.Exynos encoders do honour the vendor keys " +
                "vendor.sec-ext-enc-qp-range.{I,P,B}-{min,max}QP (Samsung slsi OpenMAX source maps them to Set_QpRange and " +
                "OMX_IndexConfigVideoQPRange) and OMX.qcom encoders expose qti-ext-enc-qp-range, so a vendor-key QP arm " +
                "is a separate default-off experiment, enabled per component only after the key appears in " +
                "MediaCodec.getSupportedVendorParameters() (API 31+) or the input/output format dump.",
            sources = listOf(
                CCODEC_QP, QP_REWORK, QP_FEATURE,
                PolicySource(
                    VENDOR_DOC,
                    "https://github.com/LineageOS/android_hardware_samsung_slsi-linaro_openmax/blob/15ab30812af9fe39fff2ae3cd867d7e954c73881/openmax/component/video/enc/vp9/Exynos_OMX_Vp9enc.c",
                    "Samsung slsi OpenMAX VP9 encoder (LineageOS mirror, not retail firmware): AddVendorExt registers " +
                        "sec-ext-enc-qp-range; the component applies it through Set_QpRange / OMX_IndexConfigVideoQPRange",
                ),
                PolicySource(
                    AOSP_SOURCE,
                    "https://android.googlesource.com/platform/hardware/qcom/sdm845/media/+/refs/heads/main/mm-video-v4l2/vidc/venc/src/omx_video_extensions.hpp",
                    "Qualcomm OMX video encoder vendor extensions include qti-ext-enc-qp-range",
                ),
                PolicySource(
                    API_REFERENCE,
                    "https://developer.android.com/reference/android/media/MediaCodec#getSupportedVendorParameters()",
                    "API 31: lists the vendor parameter names a codec accepts (runtime detection, no model matcher)",
                ),
            ),
            evidence = listOf("benchmarks/runs/lane-b-burst-r1-529f36cc"),
            confidence = HIGH,
            confidenceNote = "high that the standard keys never reach an OMX component (AOSP source plus the local run); the " +
                "Exynos vendor-key behaviour on retail firmware (M31 OMX.Exynos.VP9.Encoder) is read from vendor source only " +
                "and its effect on the M31 overshoot is unmeasured",
        ),
        proposal(
            id = "omx-exynos-vp9-framerate-bitrate-adjuster",
            area = PolicyArea.CODEC,
            validation = PolicyValidation.EXPERIMENTAL,
            title = "OMX.Exynos VP9: WebRTC frame-rate bitrate adjuster",
            matcher = DeviceMatcher(),
            component = CodecComponentMatcher(prefixes = setOf("OMX.Exynos.")),
            behaviour = "A/B WebRTC's FramerateBitrateAdjuster for OMX.Exynos VP9 (fixed 30 FPS in the encoder format, " +
                "bitrate scaled by actual FPS / 30) against the current output-rate trim for the M31 overshoot and bursts.",
            sources = listOf(
                PolicySource(
                    GOOGLE_OPEN_SOURCE,
                    "https://webrtc.googlesource.com/src/+/6f79786c977363aafe706c20c25b2037b28ab9ee/sdk/android/src/java/org/webrtc/HardwareVideoEncoderFactory.java#254",
                    "Exynos VP8 uses DynamicBitrateAdjuster; Exynos VP9/H264 (OMX.Exynos. prefix) use FramerateBitrateAdjuster",
                ),
            ),
            evidence = listOf(
                "DECISIONS row 73f517c5 -> b5ebe110 (OMX.Exynos.VP9 291-481 kbps for 300)",
                "benchmarks/runs/lane-b-burst-r1-529f36cc",
            ),
            confidence = LOW,
            status = PARTIAL,
        ),
    )

    /**
     * The one registry: core stack/call entries plus each component policy's own list. Lazy, so an
     * area object touched first (its init then initialises this object) never sees a half-built list;
     * area files may use this object's matchers and constants, never [entries].
     */
    val entries: List<DevicePolicyEntry> by lazy { coreEntries + areaEntryLists().flatten() }

    /**
     * Plug-in convention for policy areas and feature batches: an area file exposes
     * `internal val entries: List<DevicePolicyEntry>` (entries built from this object's matchers and
     * constants, never from [entries]) and is listed here on ONE line of its own, so adding an area is
     * a one-line append that does not touch its neighbours. `DevicePolicyConventionTest` checks every
     * entry of every listed area against the same metadata rules.
     */
    private fun areaEntryLists(): List<List<DevicePolicyEntry>> = listOf(
        CodecPolicy.entries,
        YuvPolicy.entries,
        BluetoothTransportPolicy.entries,
        BackgroundPolicy.entries,
        MemoryThermalPolicy.entries,
        NativeCompat.policyEntries,
        PeerLabelPolicy.entries,
        ScanPolicy.entries,
        ServiceFamilyPolicy.entries,
        AudioPolicyEntries.entries,
        CocPolicy.entries,
        CameraPolicy.entries,
        AdvertisingPolicy.entries,
        PhyLinkPolicy.entries,
        WifiFamilyPolicy.entries,
        // Batch plug-in point (append one line per batch). Not listed here because an area already
        // concatenates them: GattLinkPolicy (inside BluetoothTransportPolicy), DecoderPolicyEntries
        // (inside CodecPolicy), BackgroundOem (inside BackgroundPolicy), ThermalFamilyEntries (inside MemoryThermalPolicy).
    )

    private fun proposal(
        id: String,
        title: String,
        matcher: DeviceMatcher,
        behaviour: String,
        sources: List<PolicySource>,
        evidence: List<String>,
        confidence: PolicyConfidence,
        component: CodecComponentMatcher? = null,
        status: PolicySourceStatus = OFFICIAL,
        area: PolicyArea = PolicyArea.BLUETOOTH_TRANSPORT,
        validation: PolicyValidation = PolicyValidation.UPSTREAM_DOCUMENTED,
        confidenceNote: String = "",
        supersededBy: String? = null,
    ) = DevicePolicyEntry(
        id = id,
        title = title,
        matcher = matcher,
        effects = emptyList(),
        behaviour = behaviour,
        sources = sources,
        sourceStatus = status,
        evidence = evidence,
        confidence = confidence,
        reviewed = REVIEWED,
        origin = "Proposal (not implemented)",
        component = component,
        proposed = true,
        confidenceNote = confidenceNote,
        area = area,
        validation = validation,
        supersededBy = supersededBy,
    )
}
