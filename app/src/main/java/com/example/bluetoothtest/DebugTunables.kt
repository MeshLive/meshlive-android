package com.example.bluetoothtest

/** Debug-build overrides for tunable sweeps; production code ignores them. */
object DebugTunables {
    @Volatile var playBatteryGuidance: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    @Volatile var localReporting: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    @Volatile var nearbyListUx: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    @Volatile var strangerCallFollowup: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    @Volatile var completeRecentCalls: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    @Volatile var fullScreenCallGuidance: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    @Volatile var callReadinessPermissions: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    /** Control arm: the old status chip ignores stream-owned reachability. */
    @Volatile var honestCallStatus: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }

    /** Connection priority a streaming source requests on its GATT media links. */
    @Volatile var sourceGattPriority: BleConnectionPriority? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * MediaCodec bitrate mode forced past the encoder's advertised capabilities (0 CQ, 1 VBR, 2 CBR).
     * The M31 OMX VP9 advertises VBR only and emits ~210 KB four-frame scene-change bursts (2.8 s of a
     * 600 kbps stream); this tests whether it honours CBR anyway. Null keeps the resolved mode.
     */
    @Volatile var bitrateModeOverride: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Hardware encoder QP bounds (MediaFormat video-qp-*-min/max, API 31+) for the scene-cut burst
     * experiment: the M31 OMX VP9 encoder emitted 130 KB frames at scene cuts (VBR at low QP).
     * Null leaves the encoder's own range.
     */
    @Volatile var hardwareQpMin: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }
    @Volatile var hardwareQpMax: Int? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Experimental (off): the call frame-rate ladder starts at 0.4 x the request, as before
     * 2026-10-09, instead of at the encoded geometry's floor (see FrameRateLadder.floorBps).
     */
    @Volatile var callLadderFromRequest: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental delivery-based probing (off by default, 2026-10-09; see LinkRateController
     * `deliveryProbing`). On links whose peer sends delivery reports: the climb holds just below what
     * the link delivered at its last overuse and probes 10% past it rarely (a probe that builds a
     * measured queue is undone; failed probes back off), a measured queue of 300 ms that is not
     * draining is overuse, and decreases may be 1 s apart. Off: the default delivery-referenced cuts
     * in calls still apply. DEBUG launch extra `test_delivery_rate_control` (benchmark tunable
     * `delivery_rate_control`); read when a stream or call starts.
     */
    @Volatile var deliveryRateControl: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental stream delivery reports (off by default, 2026-10-09). Viewers report delivery
     * (link report version 3) every second, and a stream sender uses a child's delivery only with its
     * own switch on. A stream parent cannot advertise that it accepts version 3 (older viewers reject
     * any heartbeat from a parent), so every phone in the stream must run this build. Reports sent
     * only for delivery carry the delivery-only flag, which keeps the parent's lateness trend as it
     * was. DEBUG launch extra `test_stream_delivery_reports` (benchmark tunable
     * `stream_delivery_reports`); read when a stream link starts.
     */
    @Volatile var streamDeliveryReports: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental bounded media age (off by default; design decision 2026-10-09: A/B against the
     * no-loss default before choosing). On: calls keep speech near 400 ms mouth-to-ear by degrading
     * video (bitrate, verified non-reference frames, skip to a keyframe, then audio-only pause), and
     * stream queues shed stale video codec-safely beyond about 2 s of media age. Audio is never shed.
     * Off: no queue sheds and behaviour is unchanged; the BENCHMARK media-age lines are written in
     * both arms. See BoundedMediaAge. DEBUG launch extra `test_bounded_media_age` (benchmark tunable
     * `bounded_media_age`); read when a stream or call starts.
     */
    @Volatile var experimentalBoundedMediaAge: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relay-child media age (on by default since 2026-10-09, design decision). Applies the bounded media-age shedding of
     * [experimentalBoundedMediaAge] to a viewer-forwarder's child queue only: a child whose link is
     * slower than the stream gets codec-safe skip-to-keyframe on that branch instead of being
     * withdrawn (which made it reroute to the source and halve the source's other children, mesh3
     * c85a177c). Calls, direct sender queues and other branches stay no-loss. DEBUG launch extra
     * `test_relay_child_media_age` (benchmark tunable `relay_child_media_age`); otherwise the Advanced
     * setting "Relay: slow viewers skip to keyframes" (on by default, any build). Read when forwarding starts.
     */
    @Volatile var experimentalRelayChildMediaAge: Boolean = true

    /**
     * Speaker gain (0-1) for automated runs: generated reference audio played at full volume on four
     * phones at once (2026-10-09). Measurements read the PCM accepted by AudioTrack, before this gain.
     * Null keeps normal playback.
     */
    @Volatile var playbackGain: Float? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 0f..1f }
        }

    /** Pacing writes at the child-measured link rate; DEBUG A/B runs can turn it off. */
    @Volatile var linkPacing: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * BLE scan-failure recovery (on by default, 2026-10-10; BleL2capDiscovery.onScanFailure). Each
     * ScanCallback failure code maps to restart / back off (3 s x n, capped at 30 s, reset by any result;
     * each restart takes a ScanStartGate slot, le-scan-start-quota) / report. A continuous scan hands the
     * failure to its caller's visible error path when the back-off reaches the cap. DEBUG
     * `test_scan_recovery=false` (benchmark tunable `scan_recovery`) is the control arm: any failure
     * stops discovery and reports, as before.
     */
    @Volatile var scanRecovery: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * F11 weak-pair reach (2026-10-19): app scans ask for the LE 1M PHY only. Every advert of this app is a legacy advert,
     * which is always sent on LE 1M (BT Core 5.x Vol 6 Part B 4.4.2: legacy advertising PDUs use the primary channels on
     * LE 1M), but ScanSettings defaults to PHY_LE_ALL_SUPPORTED, and the Android 13+ stack then scans 1M and Coded in turn
     * (`le_scanning_manager.cc SetScanParameters scan_phy 255 phy 5` on the Redmi 12), leaving 1M a part of the window.
     * DEBUG `test_scan_le_1m_only=false` is the control arm (the platform default PHY).
     */
    @Volatile var scanLe1mOnly: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * F11 (2026-10-20): the Meet scan (Add contact, a code being scanned: screen on, at most [MeetBridge.scanLimitMs])
     * hands the stack an empty filter and matches the service UUID in software. The Redmi 12's (MT6769, HyperOS)
     * offloaded filter never reported the A15s's (Android 10) code advert: its HCI snoop of a filtered Meet scan has no
     * report from that advertiser at all, while the same phone's unfiltered DEBUG raw observer heard it (legacy,
     * connectable, -60 dBm). The A15s's advert differs from the others only in its flags (0x02, the pre-GD advertiser,
     * not 0x1A), which an Android 10 app cannot change. Default on; DEBUG `test_meet_software_filter=false` is the
     * control arm (the hardware filter, as before).
     */
    @Volatile var meetSoftwareFilter: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * F11 follow-up (Experimental, default off, `test_call_list_software_filter`): the Nearby call list's scanner also
     * matches the service UUID in software, for an A/B on the Redmi 12, whose offloaded filter misses the A15s's advert.
     * The list scans only while it is visible, so the screen is on; an unfiltered scan is paused by the stack otherwise.
     */
    @Volatile var callListSoftwareFilter: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, `test_drop_leading_blank_frames`): an Image-input encoder skips the camera's leading
     * blank buffers (luma 0, neutral chroma) until the first real frame, at most 15 frames or 1 s ([LeadingBlankFrames]).
     */
    @Volatile var dropLeadingBlankFrames: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Lost GATT chunks (default on; DEBUG control arm `test_gatt_gap_fast_fail=false`): a sequence gap held 1 s or behind
     * 128 chunks ends the stream at once with its reason ([ChunkGapPolicy]) instead of after the 8 s liveness timeout.
     */
    @Volatile var gattGapFastFail: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on since 2026-10-21 (design decision; DEBUG `test_gatt_chunk_recovery=false` is the control arm): `GR1` lost-chunk
     * recovery on GATT links ([GattChunkRecovery], DECISIONS "Design GR" option C). Off: no history, no type-2 values sent,
     * type 2 ignored.
     */
    @Volatile var gattChunkRecovery: Boolean = true
        set(value) {
            // Default on (design decision 2026-10-21); DEBUG launches can pass false as the control arm.
            field = value || !BuildConfig.DEBUG
        }

    /**
     * DEBUG only (`test_gatt_drop_received_every=N`, 0 = off): every GATT reader drops the first copy of each received data
     * chunk whose sequence is N-1 mod N, as if the host had lost it, so option A or recovery can be forced on any pair.
     */
    @Volatile var gattDropReceivedEvery: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, 1_000_000) else 0
        }

    /** DEBUG only (`test_inject_blank_frames=N`, 0..60): blank the first N camera images ([BlankFrameInjection]). */
    @Volatile var injectBlankFrames: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, BlankFrameInjection.MAX_FRAMES) else 0
        }

    /**
     * Experimental (default off, `test_source_media_prewarm`): a broadcast source starts camera and encoder when it begins
     * connecting to its first viewer instead of after that viewer authenticated ([SourceMediaPrewarm]).
     */
    @Volatile var sourceMediaPrewarm: Boolean = true
        set(value) {
            // Default on (design decision 2026-10-21); DEBUG launches can pass false as the control arm.
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, DEBUG `test_dial_recent_sighting`): a dial to a row heard under 1.5 s ago starts at once
     * instead of waiting for the next sighting ([RecentSightingDial]). Release builds never enable it.
     */
    @Volatile var dialRecentSighting: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, DEBUG `test_relay_join_key_frame`): a source honours a relay child's keyframe request
     * after a 2 s calm gap at once, bypassing the recovery back-off ([RelayJoinKeyFrame]). Release builds never enable it.
     */
    @Volatile var relayJoinKeyFrame: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, DEBUG `test_fast_0x3e_dials`): a call's first GATT dial redials a fast 0x3E failure up to
     * 4 dials, 0.3-0.8 s apart, inside the early-loss window ([FastEstablishDials]). Release builds never enable it.
     */
    @Volatile var fastEstablishDials: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Design QV review 1, R3 (default on; DEBUG control arm `test_viewer_admission_bounds=false`): a source starts at
     * most a few new pre-authentication viewer attempts per window, oldest request first ([PreAuthAdmissionGate]).
     * The bounded rejection tables are not switchable (memory bounds only).
     */
    @Volatile var viewerAdmissionBounds: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Failed live joins (default on; DEBUG control arm `test_stream_join_retry=false`): "Couldn't join <title>." with
     * Try again, and one automatic retry when the stream is listed again ([StreamJoinRetry]).
     */
    @Volatile var streamJoinRetry: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * A viewer's search for a lost stream ends after 60 s (default on, owner answer 2026-11-10, audit CALL-04; DEBUG control
     * arm `test_stream_search_bound=false` keeps the unbounded search): "The stream was lost" with Try again, back in the
     * stream list, which also reopens the phone to calls ([StreamSearchBound]).
     */
    @Volatile var streamSearchBound: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * A callee behind a throwaway key sends its name to a stranger caller only after that caller's reveal passed the block
     * check, like its camera and microphone, instead of with Accept ([CalleeNameRelease]); a blocked caller never learns it.
     * Default on since 2026-11-10 (design decision, audit CALL-06; DEBUG control arm `test_callee_name_after_reveal=false`).
     */
    @Volatile var calleeNameAfterReveal: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, `test_skip_redundant_priority`): a connection-priority request for the priority
     * already requested is not sent when the link's last update already put the interval in that priority's window
     * ([LeConnectionPriorityKeeper.isRedundant]; line `prio_request: skipped`). A/B for the A15s-source radio join
     * that dropped 5.07 s after such a request (2026-10-20).
     */
    @Volatile var skipRedundantPriority: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * F19 (default on; DEBUG control arm `test_call_ring_timeout=false`): an unanswered call ends: the caller cancels
     * after 60 s of ringing ("No answer"), the callee declines after 65 s ("Missed call"). [CallRingTimeout]
     */
    @Volatile var callRingTimeout: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Remote call video framing (default on; DEBUG control arm `test_remote_video_fit=false`): a remote video whose
     * shape differs strongly from the view's is shown whole with black bars, close shapes still fill, and a
     * double-tap toggles for the call. Off: the app-wide framing setting only, no double-tap. [RemoteVideoFit]
     */
    @Volatile var remoteVideoFit: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * F17 Stage A (Experimental, default off, `test_call_stall_tolerance`): keep a call whose peer went quiet while this
     * phone's own writes still complete, up to [CallStallTolerance.TOLERANCE_NS], showing "Poor connection" from 3 s.
     */
    @Volatile var callStallTolerance: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * F17 (default on; control arm `test_call_liveness_hang_up=false`): a best-effort HANG_UP before a local liveness
     * close, so a peer that can still hear us ends at once instead of after its own 8 s ([LivenessHangUp]).
     */
    @Volatile var callLivenessHangUp: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * F11 weak-pair reach: while a verification code is shown the call advert stays low-latency / high TX power, the Meet
     * scan looks for 30 s with one fresh scanner half-way, and a contact-call wait follows the measured sighting rate.
     * DEBUG `test_weak_pair_reach=false` is the control arm (10 s searches and waits, the balanced step as before).
     */
    @Volatile var weakPairReach: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** F16 fix 1, Experimental (default off, DEBUG `test_call_link_share=true`): [CallLinkShare] for Automatic calls. */
    @Volatile var callLinkShare: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Wi-Fi carrier (Experimental, default off, DEBUG `test_wifi_carrier`): forces the `wifi_carrier` switch on in a debug launch. Read only by DefaultWifiSwitches until A6 installs the FeatureCatalog source; the extra name stays. */
    @Volatile var wifiCarrier: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Wider reach (Experimental, default off, DEBUG `test_wifi_wider_reach`): same pattern as wifiCarrier. [R M-7] */
    @Volatile var wifiWiderReach: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Wi-Fi Direct (Experimental, default off, DEBUG `test_wifi_direct`): same pattern as wifiCarrier. [R M-7] */
    @Volatile var wifiDirect: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Wi-Fi Aware instant communication mode arm (`test_wifi_icm`); read only by WifiDebugArms. */
    @Volatile var wifiIcm: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Measured arm for D9: allow an upgrade while this phone's own Wi-Fi client is on 2.4 GHz (`test_wifi_allow_24ghz`); read only by WifiDebugArms. */
    @Volatile var wifiAllow24ghz: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Measured arm: attach even while another client holds Wi-Fi Aware, Private level (`test_wifi_share_attach`); read only by WifiDebugArms. */
    @Volatile var wifiShareAttach: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /**
     * PROTOCOL `port`, promoted 2026-11-10 (W5): the Wi-Fi Aware server port is derived from UW and the ticket and used when the
     * platform reports none (`test_wifi_aware_derived_port`); read only by WifiDebugArms. Default on: a release build keeps it on;
     * a DEBUG launch can pass false as the control arm (host random port, guest without a platform port ends `no_port`).
     */
    @Volatile var wifiAwareDerivedPort: Boolean = true
        set(value) { field = value || !BuildConfig.DEBUG }
    /** Experimental arm: the 5 GHz channel hint of the Aware data-path request is required, not a hint (`test_wifi_aware_require_channel`); read only by WifiDebugArms. */
    @Volatile var wifiAwareRequireChannel: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** AP9 DEBUG `test_aware_band_strict`: the old all-channels-5GHz Aware band rule (the new default judges by the 5 GHz data channel); read only by WifiDebugArms. */
    @Volatile var awareBandStrict: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** AP10 Experimental, default off, DEBUG `test_aware_24ghz_alone`: a phone with no other Bluetooth media link accepts a 2.4 GHz-only Aware data path (off = AP9's 5 GHz rule exactly); read only by WifiDebugArms. */
    @Volatile var aware24GhzAlone: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** AP10 Experimental, default off, DEBUG `test_aware_goodput_guard`: the Aware goodput guard returns a low hop to Bluetooth (off = it measures and logs `quality_fallback_suppressed` only); read only by WifiDebugArms. */
    @Volatile var awareGoodputGuard: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Census only (`test_wifi_probe`): CAPS goes out with every bit clear and no attempt is ever started; facts are logged. */
    @Volatile var wifiProbe: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }
    /** Benchmark arm (`test_wifi_force_carrier=aware|direct|bluetooth`): try only that carrier. Empty = normal. */
    @Volatile var wifiForceCarrier: String = ""
        set(value) { field = if (BuildConfig.DEBUG && value in setOf("aware", "direct", "bluetooth")) value else "" }
    /** DEBUG `test_wifi_exact_kbps`: exact call rate for the M2 measurement (DEBUG only; ignored without `test_rate_control` or with `auto`). [A5 Am 16] */
    @Volatile var wifiExactKbps: Int = 0
        set(value) { field = if (BuildConfig.DEBUG && value > 0) value else 0 }

    /** Experimental (default off), Design WF, RESERVED, no consumer yet (R-4): lower the Bluetooth standby's connection priority while Wi-Fi carries media. `test_wifi_ble_standby_low_power=true`. Parsed and logged; no code reads it; the benchmark runner refuses it. */
    @Volatile var wifiBleStandbyLowPower: Boolean = false
        set(value) { field = value && BuildConfig.DEBUG }

    /** F16 fix 2, Experimental (default off, DEBUG `test_call_fps_follow=true`): [CallFpsFollow] for Automatic calls. */
    @Volatile var callFpsFollow: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * F11 item 5, default ON since 2026-10-27 (control arm DEBUG `test_reachable_scan_yield=false`): the Nearby call list and the Live list
     * scan at low latency for their first 8 s and then balanced, instead of low latency without end. Hypothesis to A/B:
     * a callee whose radio scans without pause (the Redmi 12: 100/100 ms on 1M and Coded) misses the caller's CONNECT_IND or
     * cannot schedule the first connection events, so the caller sees status 133 with HCI 0x3E (failed to be established).
     * 2026-10-19 counts by target: Xiaomi 42, A15s 26, ASUS 20 failures. Promoted 2026-10-27 (owner go-ahead): better or
     * equal on every measured pair (S22/ASUS 10/10 vs 8/10, M31/A15s 4/5 vs 2/5, S24/M31 6/6 vs 3/6, ASUS/A15s 3/6 vs
     * 3/6; tap->ring median 1.9 vs 2.5 s); Xiaomi and A55 not yet measured.
     */
    @Volatile var reachableScanYield: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Bluetooth ON recovery (on by default, 2026-10-17; BluetoothOnRecovery.kt). After Bluetooth is switched off and on, the
     * scanners started during the toggle are restarted, a live broadcast offers its stream again instead of disarming, and an
     * idle call listener reopens its GATT server / CoC control server / advertising. DEBUG `test_bluetooth_on_recovery=false`
     * (benchmark tunable `bluetooth_on_recovery`) is the control arm: only the scanners the off callback paused are resumed.
     */
    @Volatile var bluetoothOnRecovery: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Q29 (2026-10-14): the 20 s incoming-handshake deadline closes a silent stranger on every CoC and GATT call
     * endpoint, not only dual ones (CallSetupDeadlines.incomingDeadlineApplies). DEBUG launch extra
     * `test_call_handshake_deadline_all=false` is the control arm: only dual endpoints keep the deadline, as before.
     */
    @Volatile var callHandshakeDeadlineAll: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Review A3 (2026-10-27): the caller closes a link still waiting for the callee's RINGING 15 s after hop authentication
     * (relayed: SETUP budget + 10 s), [CallSetupDeadlines.callerAnswerMs]. DEBUG `test_caller_answer_deadline=false` is the
     * control arm (no bound, as before). Release builds always have it.
     */
    @Volatile var callerAnswerDeadline: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Design PP package A (2026-11-10), Experimental: a second, shorter timer on an incoming BLE GATT/CoC connection that ends at the
     * verified hop plus opening ([IncomingPhaseBounds.HOP_AUTH_MS], 10 s instead of waiting out the 20 s watchdog), so a connector
     * that never completes the hop handshake releases the call endpoint sooner. The 20 s watchdog stays as it is; a BMH2 first frame,
     * Classic, Wi-Fi and the relay budgets keep the conservative bound. DEBUG launch extra `test_call_preauth_deadline_short=true`
     * (benchmark tunable `call_preauth_deadline_short`); off by default, in release builds always off.
     */
    @Volatile var callPreauthDeadlineShort: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Design PP package A (2026-11-10), Experimental, SEC-06: an authenticated incoming connection of an ordinary call that has not
     * delivered its first valid INVITE [IncomingPhaseBounds.PRE_INVITE_MS] after its reader started is closed (the conservative
     * 20 s for a relayed, Classic or Wi-Fi endpoint). Verification-only (BVMID2) sessions, message exchanges, relay duties, outgoing
     * calls and renewal are never bounded by it. DEBUG launch extra `test_call_pre_invite_deadline=true` (benchmark tunable
     * `call_pre_invite_deadline`); off by default, in release builds always off.
     */
    @Volatile var callPreInviteDeadline: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Review T5 (2026-10-27): the GATT control client re-attached after a CoC connect makes one attempt bounded at 3 s and
     * returns at once (the PHY request follows 600 ms later, off the connect path). DEBUG `test_coc_attach_single=false`
     * is the control arm: up to 3 attempts of 6 s, and the connect waits the 600 ms. Release builds always have it.
     */
    @Volatile var cocAttachSingle: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** Experimental (off), review T2/A11: [FastFailRedial] for the BLE control client. DEBUG `test_fast_fail_redial_all=true`. */
    @Volatile var fastFailRedialAll: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Promoted 2026-11-12 on mechanism (default on, release builds keep it on), join-latency P4a: [FastFirstRedial], the first
     * redial after a fast 133/0x3E (within the 1.5 s fast-establishment class) waits 200 ms instead of 500 (GATT video and BLE
     * control clients). DEBUG `test_fast_first_redial=false` is the control arm.
     */
    @Volatile var fastFirstRedial: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Promoted 2026-11-12 on mechanism (default on, release builds keep it on), join-time JT2-P2: [CallReadyAdvertisingHold], a
     * call endpoint's idle GATT receiver set keeps its 100 ms / high-power advertising instead of stepping to 250 ms / medium
     * 10 s after start. DEBUG `test_call_adv_idle_fast=false` is the control arm.
     */
    @Volatile var callAdvIdleFast: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Promoted on mechanism 2026-11-12 (default on, release builds keep it on): [CallsQuiet.indicatorVisible], the Calls >
     * Nearby searching indicator stops after [CallsQuiet.INDICATOR_FOR_MS] instead of spinning (and redrawing the window every
     * frame) for as long as discovery runs. DEBUG `test_calls_indicator_settles=false` is the control arm.
     */
    @Volatile var callsIndicatorSettles: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** Experimental (off), review A10: [StallRetryOwnBudget]. DEBUG `test_stall_retry_own_budget=true`. */
    @Volatile var stallRetryOwnBudget: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (0 = off), review A14: the first-2M setup stall guard's arm in ms (e.g. 2000) instead of
     * [AdaptivePhyMemory.SETUP_STALL_MS]. DEBUG `test_setup_stall_ms`.
     */
    @Volatile var setupStallMs: Long = 0L
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0L, 10_000L) else 0L
        }

    /**
     * Experimental (0 = off; AP7 F2, analysis a15s-asus 2026-11-07): a first-2M call attempt whose hop authentication
     * finished after this many ms is treated as a setup stall and takes the existing 1M retry, recording the peer bad on
     * 2M as a stalled setup does. DEBUG `test_stall_slow_auth_ms`. Clamped to [AdaptivePhyMemory.SETUP_STALL_MS]: at or
     * above it the existing guard claims the attempt first and this arm can never fire.
     */
    @Volatile var stallSlowAuthMs: Long = 0L
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0L, AdaptivePhyMemory.SETUP_STALL_MS) else 0L
        }

    /** Experimental (off), review A13: the relayed SETUP budget starts at hop authentication. DEBUG `test_relayed_budget_from_hop_auth=true`. */
    @Volatile var relayedBudgetFromHopAuth: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off), simplification pass A: the single connect-retry rule ([UnifiedConnectRule]) instead of the separate
     * dial rules ([ConnectRetryConfig]). DEBUG `test_unified_connect_policy=true`.
     */
    @Volatile var unifiedConnectPolicy: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off): private calls on socket transports (Classic RFCOMM, LE CoC) pace their
     * writes with [LinkPacer] at the peer-measured arrival rate, seeded like a stream child, and the
     * peer's call reports also teach this phone's learned single-link capacity (today only stream
     * children do, so a phone that only calls never learns a start rate for its next call).
     * A GATT call write blocks for its own transmission. An RFCOMM/CoC write returns at once, but
     * the call flushes every loop and LocalSocket.flush polls TIOCOUTQ every 10 ms until the stack
     * has read the socket buffer, so only the RFCOMM port queue (10 frames of up to 990 B, about
     * 10 KB, PORT_TX_HIGH_WM) plus L2CAP/controller buffers can hide below the app. Pacing keeps
     * that part in the app queue too. The tradeoff: the call's bounded queue can fill sooner, which
     * drops video until a keyframe, as it can on GATT. DEBUG launch extra `test_call_link_pacing`
     * (benchmark tunable `call_link_pacing`). Needs [linkPacing].
     */
    @Volatile var callLinkPacing: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Classic pages a scanned BLE address even when Android 15+ reports it random (the control arm
     * of classicScanAddressIsRandom). DEBUG launch extra `test_classic_page_random_address`.
     */
    @Volatile var classicPageRandomAddress: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Seeds a child link's pacer at join (learned capacity or a conservative prior) so it is paced
     * before the child's first arrival report; DEBUG A/B runs can turn it off. Needs [linkPacing].
     */
    @Volatile var linkPacerSeed: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): a child link's pacer seed is the child's own last arrival report for this
     * link (LinkCapacity.peerLinkKbps, 24 h) instead of this phone's best single-link probe. DEBUG
     * launch extra `test_link_pacer_peer_seed`. Needs [linkPacerSeed].
     */
    @Volatile var experimentalPeerLinkSeed: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Releases a starved fan-out child early when the encoder is at its rate floor and the child's
     * backlog keeps growing (see FanOutStarvationPolicy); DEBUG A/B runs can turn it off.
     */
    @Volatile var fanOutEarlyRelease: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Delivery reports in calls (link report version 3, on by default since 2026-10-09). DEBUG A/B
     * runs can turn them off (`test_delivery_reports=false`, benchmark tunable `delivery_reports`) to
     * restore the pre-2026-10-09 behaviour exactly: no version-3 hello or report is sent, received
     * delivery is ignored, rate control uses the old references (arrival trains, bytes the stack
     * accepted), and every cut is remembered again (calls and streams). Read when a call or stream
     * starts. A/B arms should also pass `test_reset_learned_rates` so both start from the same memory.
     */
    @Volatile var deliveryReports: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * On-time reports (on by default since 2026-10-16). A stream viewer that has not measured its
     * parent link yet (no large-record train) sends a full link report every 2 s while media keeps
     * arriving and its playout is not pinned late; the parent (or relay) holds its seeded pace while
     * such reports say the link is on time. Without them a viewer sent nothing until its delay moved,
     * so the parent halved the seed after 4 s of silence down to 150 kbps and starved streams whose
     * records never formed a train (lane12 s6-threshold-360p: 640x360 at 300-500 kbps fell to about
     * 157 kbps with a 65 s queue). DEBUG A/B runs can turn them off (`test_on_time_reports=false`,
     * benchmark tunable `on_time_reports`); read when a stream link starts.
     */
    @Volatile var onTimeReports: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * On-time seed raise (on by default since 2026-10-16, design decision Q12; see LinkPacer.holdSeed).
     * While an unmeasured child reports media on time and this side's own queue is at least 500 ms
     * old, the held pacer seed grows 25% per report (cap 1500 kbps) instead of staying at the cold
     * prior: a fresh-install A55 seeded at 350 kbps held a 400 kbps stream below its offered load and
     * froze 56-87% (lane15 pairB2/pairE, a55m31-seedraise). DEBUG A/B runs can turn it off
     * (`test_on_time_seed_raise=false`, benchmark tunable `on_time_seed_raise`); read when a stream
     * link starts.
     */
    @Volatile var onTimeSeedRaise: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off by default, 2026-10-17; ReceiverNodeHealthPolicy): a forwarding viewer's child-detach guard
     * also allows 1.35x the node's own pre-child p95, so a relay whose cadence was already ~2 frame periods (S22 relay,
     * p95 ~133 ms at 15 FPS, lane15 chain5) does not detach a child that made nothing worse. DEBUG
     * `test_receiver_node_baseline_p95=true` (benchmark tunable `receiver_node_baseline_p95`); read when a stream starts.
     */
    @Volatile var receiverNodeBaselineP95: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental join probe seed (off by default, 2026-10-17, design decision Q12 option C; see
     * LinkPacer.beginJoinProbe). A stream link with no measurement starts from a cautious prior (GATT
     * 350 kbps) and the on-time seed raise needs seconds to climb from it. With this on, the sender (and a
     * relay's child link) lets the writer run unpaced for a bounded window (1.5 s or 32 KiB), the child
     * times the back-to-back records it receives in its first 3 s as one burst (ArrivalTrainMeter
     * join burst) and reports it in the ordinary link report, and the first rate within 3 s seeds the
     * pacer (the measured link rate when the sender was backlogged, else the higher of prior and burst);
     * with no report the prior stands. No wire change; both ends should run it for a seed to arrive
     * (an older or switched-off child never reports a burst and the prior stands). DEBUG
     * `test_join_probe_seed=true` (benchmark tunable `join_probe_seed`); read when a stream link starts.
     */
    @Volatile var joinProbeSeed: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental seed raise drain guard (off by default, 2026-10-17; see LinkPacer.holdSeed). The
     * on-time raise keeps climbing on a link-limited path (M31 -> A55 at 1200 kbps carried 800-950 while the
     * raise went on, queue 14-31 s, frozen 49-65%). With this on a raise is skipped while the sender's
     * queue age is not falling between consecutive reports and its writes are blocking (link-limited, not
     * pacer-limited). Only meaningful with the raise on. DEBUG `test_seed_raise_drain_guard=true`
     * (benchmark tunable `seed_raise_drain_guard`); read when a stream link starts.
     */
    @Volatile var seedRaiseDrainGuard: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental relay establishment keeps 2M (off by default, 2026-10-16; see relayChildSetupPhyDecision).
     * A relay child setup that failed before any connection existed (status 133 / HCI 0x3E on connect, state 0
     * at the connection callback) no longer pins that child to 1M for the session: connection establishment
     * runs on the advertising PHY, so 1M would not have changed it, and the pin cost the child 2M for the whole
     * stream (lane15 chain2: relay TX 136-171 kbps instead of 350-374, child frozen 70-77%). A failure after the
     * link existed (2M PHY update requested or in flight) still pins 1M. DEBUG `test_relay_establish_keep_2m=true`
     * (benchmark tunable `relay_establish_keep_2m`); read when a relay dials a child.
     */
    @Volatile var relayEstablishKeep2M: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Learned call start (on by default since 2026-10-09; see CallStartRate). Learned sustained rates
     * survive app updates (format 2, 24 h), a call with no learned rate starts at a share of the
     * measured link or 400 kbps instead of the request, and the start applies before the first frame.
     * DEBUG A/B runs can turn it off (`test_start_rate_guard=false`, benchmark tunable
     * `start_rate_guard`): build-keyed rates, unmeasured calls start at the request. The control arm
     * `test_delivery_reports=false` also turns it off. Read when a call or stream starts.
     */
    @Volatile var startRateGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Fraction of the encoder target a hardware encoder gets for one app-requested keyframe (startup,
     * child join, recovery), restored after that keyframe. Hardware VP9 keyframes were 55-135 KB at
     * 300-600 kbps (1.5-2.2 s of the stream) and ignored QP bounds; null leaves keyframes unshaped.
     */
    @Volatile var keyFrameShaping: Double? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Shared LE CoC listeners (CocListeners): null = DeviceFamilyPolicy.leCocServerLeaks, so only
     * Android 10 stacks, which never stop a closed LE CoC server, keep one long-lived listener per
     * role; fixed stacks open and close one per session. DEBUG launch extra
     * `test_coc_shared_listener=true|false` forces either behaviour on any phone.
     */
    @Volatile var sharedCocListener: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * The low-latency -> balanced advertising step changes the parameters of the running advertising
     * set (StableAdvertisingSet), so its random address stays the one peers just discovered. DEBUG
     * launch extra `test_stable_adv_address=false` restores stop + restart (a new address).
     */
    @Volatile var stableAdvertisingAddress: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Rotating discovery IDs (hardening 2026-10-11): connectable app advertising sets are non-discoverable on
     * API 34+ while the rotating switch is on ([NonDiscoverableAdvertising]). DEBUG launch extra
     * `test_adv_non_discoverable=false` is the control arm (the previous discoverable sets); release builds
     * always follow the default.
     */
    @Volatile var advNonDiscoverable: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * B2 (DiscoverableWhenNeutral): null = default (call-offer and viewer-request sets are discoverable while the
     * adapter name is "Phone" exactly when the neutral-name option is on). DEBUG launch extra
     * `test_discoverable_when_neutral=true|false` forces it; release builds always keep the default.
     */
    @Volatile var discoverableWhenNeutral: Boolean? = null
        set(value) {
            field = if (BuildConfig.DEBUG) value else null
        }

    /**
     * Deferred GATT client close (GattClientTeardown): null = the stack-family default (deferred on
     * Android 11 and older, whose GattService ContextMap races an immediate close). DEBUG launch extra
     * `test_deferred_gatt_close=true|false` forces it on or off on any phone.
     */
    /**
     * Call/stream audio after a sender gap (mute, push-to-talk release) starts on its playout target
     * behind written silence (OpusAudioDecoder.alignAfterSenderGap). DEBUG launch extra
     * `test_audio_gap_align=false` is the control arm: the spurt plays as soon as it is written.
     */
    @Volatile var audioGapAlignment: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): voice-only calls use the short CALL_VOICE playout delay (80 ms, growing to
     * 400 ms on late audio) instead of the video-call 250 ms start. Constrained (batched) links keep
     * their longer delay. DEBUG launch extra `test_voice_call_low_delay`.
     */
    @Volatile var voiceCallLowDelay: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off): call audio keeps its own playout clock, grown only by late audio, instead of
     * sharing the video delay. Video calls held audio at 600 ms (calls-noloss-*, bmaraw-ab-call-*:
     * the shared delay grew by 350 ms for video bursts while audio itself arrived on time); with this
     * on, speech can lead the picture by up to that growth (lip sync traded for conversational delay).
     * DEBUG launch extra `test_call_audio_own_clock`.
     */
    @Volatile var callAudioOwnClock: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off; design decision 2026-11-06, Q-AUDIO-STALL): after the peer's call audio stopped arriving for 2 s or
     * more, audio still late after the playout clock grew to its maximum (older than the playout budget) is dropped and
     * logged ([CallStaleAudio]); normal jitter still plays every packet. Off logs the same line in observe mode. DEBUG launch
     * extra `test_call_stale_audio_drop`.
     */
    @Volatile var callStaleAudioDrop: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Promoted on mechanism 2026-11-12 (default on, release builds keep it on): after one isolated late burst (b12sm
     * 2026-11-11: a 526 ms sender pause grew the S22's call delay by 303 ms for the rest of the call), the call delay
     * returns to its level before the burst by leaving out quiet decoded audio inside pauses, never speech; video follows
     * at the same point ([CallFastRecovery]). Sustained lateness and continuous speech keep today's slow shrink. cfr3
     * (S22 <-> M31, two 526 ms pauses per call): time 100 ms or more late fell from about 64 s to about 8 s per call.
     * DEBUG launch extra `test_call_fast_recovery=false` is the control arm.
     */
    @Volatile var callFastRecovery: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off; audit MEDIA-06): a call's video decoder that stops taking frames (a decoder watchdog stall, or
     * no queue room within the 1.5 s playout budget instead of 5 s) is replaced at the next keyframe instead of ending
     * the call; after three stalls without 60 healthy frames video stops and voice continues ([CallVideoStallRestart]).
     * DEBUG launch extra `test_call_video_restart`.
     */
    @Volatile var callVideoRestart: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * LE CoC client on a fresh ACL (BleCocClient, DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS):
     * null = the stack-family default (Android 12/13). DEBUG launch extra
     * `test_coc_fresh_acl=true|false` forces it on or off on any phone.
     */
    /**
     * Private-call hang-up close waits for the peer to close the link, capped at 3 s
     * (privateCallCloseWait). DEBUG launch extra `test_call_close_wait_peer=false` is the control arm:
     * the previous fixed 400 ms drain.
     */
    /**
     * Relay child re-anchoring (default on): a relay whose child link is starved (under 50 kbps for 2 s
     * with media waiting) reconnects that child on a fresh connection, at most twice a minute, before
     * the withdrawal policy decides. See RelayChildReanchor.kt. DEBUG launch extra
     * `test_relay_child_reanchor=false` is the control arm. The child-side watch and same-parent preference
     * follow it unless [relayParentWatch] sets it apart; the same-parent preference [relayPreferParent] is off by default
     * since build 4 ([RelayRecoveryConfig]).
     */
    @Volatile var relayChildReanchor: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Relay child stall guard (default on): a LE child link that delivers no inbound byte for 2 s before
     * the end-to-end handshake finishes is closed, and before hop authentication it counts as an
     * establishment failure ([RelayChildStallGuard]). DEBUG launch extra `test_relay_child_stall_guard=false`
     * is the control arm (wait for the 5 s supervision timeout, not counted).
     */
    @Volatile var relayChildStallGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: after the stall guard closed a child link, dials to that child within [RelayStallHoldoff.HOLDOFF_MS] are skipped
     * and not counted as establishment failures (the child is still tearing down its dead link and receiver). DEBUG launch extra
     * `test_relay_stall_holdoff=false` is the control arm.
     */
    @Volatile var relayStallHoldoff: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: a guard-detected hop-stage stall (the dead-anchor signature) is counted apart from refusals, needs 3 within
     * 2 minutes, and marks the child for 30 s (60, 120, 300 s on consecutive marks) instead of 5 minutes
     * ([EstablishmentFailureTracker.onStall]). Refusals (status 133/62) keep 2 failures and the 5-minute mark. DEBUG launch extra
     * `test_relay_stall_separate=false` is the control arm (a stall counts as a refusal, as before).
     */
    @Volatile var relayStallSeparate: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: a relay's refusal marks (GATT status 133/62 to a child, [EstablishmentFailureTracker.onFailure]) escalate 30, 60,
     * 120, 300 s on consecutive marks instead of a flat 5 minutes, and a refusal round holds the relay's next dial to that child off
     * for [RelayStallHoldoff.HOLDOFF_MS] so the two failures are not spent in 3 s. The 5-minute mark withdrew the offer from a child
     * that kept asking and had no other parent (b5-audio-chain-cb548fc5 music r1/r3). DEBUG launch extra
     * `test_relay_refusal_escalation=false` is the control arm (flat 5 minutes, no hold-off).
     */
    @Volatile var relayRefusalEscalation: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on since review B1 (2026-10-27; was Experimental, off): a sighting of a child that the relay will not dial (marked by [EstablishmentFailureTracker] or inside
     * [RelayStallHoldoff]) is dropped in the discovery callback before `stopPresence()`, so the offer and the scan are not stopped
     * and restarted for nothing ([RelayChildDialGate]). b6-q31-c1-reset 06-A3: 51 Discovery advertising-set starts and 27 deferred
     * scan starts in 5 minutes, which left the relay blind for 29 s at a time. Release builds have it on; DEBUG launch extra
     * `test_relay_hold_in_place=false` is the control arm and restores the restart-per-sighting behaviour exactly.
     */
    @Volatile var relayHoldInPlace: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: when a GATT video receiver's input pipe fails (overflow, bad chunk header, reordering), it cancels the connection
     * so the sender sees the loss instead of writing into a dead input while heartbeats continue. DEBUG launch extra
     * `test_gatt_input_failure_ends_link=false` is the control arm.
     */
    @Volatile var gattInputFailureEndsLink: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on since 2026-10-21 (design decision; DEBUG `test_call_link_renewal=false` is the control arm): in-call link
     * renewal (Design SR revision 2, [CallRenewalWire]). A direct GATT call whose one direction stalled replaces its hop link
     * and keeps the call, when both end-to-end hellos carry bit 4. Both phones need it.
     */
    @Volatile var callLinkRenewal: Boolean = true
        set(value) {
            // Default on (design decision 2026-10-21); DEBUG launches can pass false as the control arm.
            field = value || !BuildConfig.DEBUG
        }

    /**
     * DEBUG test hook, default 0 (off): `test_call_tx_stall_after_s=N` makes this phone's call writer stop sending on the
     * call's first link N s after the call became active, once per call, so a one-way stall can be induced on any pair.
     */
    @Volatile var callTxStallAfterS: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, 3_600) else 0
        }

    /**
     * Default on, where the Android 10-11 entry `android11-gatt-server-connection-untracked` applies
     * ([GattServerReregisterKeepsAddress]): a server re-registration keeps the running advertising set (same address), and
     * after a subscribe the set stays on until the first data write. DEBUG launch extra
     * `test_gatt_reregister_keep_address=false` is the control arm (stop at the subscribe, new set after a re-registration).
     */
    @Volatile var gattReregisterKeepAddress: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental, default off (AP7 F1, analysis a15s-asus 2026-11-07): a well-formed GR1 hello or high accepted on the
     * server's write characteristic counts as the stage-2 first-write watch's delivered write, so the watch does not cancel
     * and re-register the server on a link whose writes did reach the app. DEBUG launch extra
     * `test_gatt_first_write_counts_control`. Off: today's data-only rule.
     */
    @Volatile var gattFirstWriteCountsControl: Boolean = true
        set(value) {
            // Default on: a release build keeps it on; a DEBUG launch can pass false as the control arm.
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental diagnostic (off; DEBUG only): after a `BMS1` tag failure on a GATT link with GR1, ask the peer for
     * the failed record's chunks again, compare them byte for byte and log `hop_integrity_diff` lines, then end the link
     * as before ([HopIntegrityResendDiff]). Launch extra `test_hop_integrity_resend_diff` (benchmark tunable
     * `hop_integrity_resend_diff`); read when a GATT link starts.
     */
    @Volatile var hopIntegrityResendDiff: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Default on: at a PHY move (`stalled_one_way`, `one_pdu_per_event`) a live stream sender drops queued media older than
     * [PhyMoveSkip.FLOOD_STALE_MS] once (video codec-safely, audio older than the viewer's playout) instead of flooding it into
     * the viewer ([PhyMoveSkip.floodGuard]). Calls and audio-only live never. DEBUG launch extra `test_phy_move_flood_guard=false`
     * is the control arm.
     */
    @Volatile var phyMoveFloodGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Viewer decoder size-change handling (default on; VideoStreamDecoder, DecoderFormatTracker): a keyframe
     * whose coded size or codec configuration differs from the running decoder's gets a re-created decoder
     * (a rung change of the Auto ladder or a call's resolution ladder, an encoder restart with new csd),
     * recovery re-queues the newest csd, and KEY_MAX_WIDTH/HEIGHT is set where adaptive playback is
     * supported. DEBUG launch extra `test_decoder_size_rebuild=false` is the control arm (one decoder at
     * the header size, a size change only logged, recovery re-queues the stream-start csd).
     */
    @Volatile var decoderSizeChangeRebuild: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * DEBUG only, default off: the manual stream relay ([BluetoothVideoRelay], `test_role=relay`) passes the
     * end-to-end handshake through, so a source or viewer behind it sees an end-to-end peer that is not its link peer.
     * Every other stream hop requires the two to be the same node (StreamHopPeerBinding, SEC-B2); this switch exempts
     * a version 2 hop whose link peer signed `maxChildren > 0`. Launch extra `test_stream_relay_passthrough`
     * (set it on the source and on the viewer of a DEBUG relay chain); `test_require_relay` implies it. Release builds
     * ignore it.
     */
    @Volatile var streamRelayPassthrough: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Default OFF again since 2026-10-15 afternoon (held: the full-chain A/B with a child, lane 11 block E, looked worse
     * with DCK; low-battery S24, n=3, so it is re-tested before any promotion). A central whose
     * just-authenticated child can relay (`maxChildren > 0`) requests the Android 14+ DCK connection priority
     * (30 ms by the AOSP default) on that link instead of HIGH (11.25-15 ms), so the relay's own HIGH child link
     * has two events per parent event ([relayUpstreamPriority]). DEBUG launch extra
     * `test_relay_upstream_dck=true` enables it (Experimental arm); an explicit `test_source_gatt_priority`
     * still wins.
     */
    @Volatile var relayUpstreamDck: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * After a re-anchor-eligible link loss the child waits up to PREFERRED_PARENT_GRACE_MS (2 s) for
     * its previous relay's offer before reconnecting elsewhere (RelayChildReanchor.kt). Default on;
     * DEBUG launch extra `test_relay_recovery_grace=false` is the control arm (first offer heard wins).
     */
    @Volatile var relayRecoveryGrace: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * A relayed child also closes a parent link that trickles under 1/8 of the stream bitrate for a
     * whole 5 s window ([RelayParentTrickle]; mesh4-8211dc8f 0001: ~12 kbps for 17 s, never silent).
     * Default on; DEBUG launch extra `test_relay_parent_trickle=false` is the control arm.
     */
    @Volatile var relayParentTrickle: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * A viewer link without the relay parent-link watch (a direct link, or GATT) is closed when a started, live,
     * camera-on video stream delivered no byte for 8 s, so link-loss recovery reconnects ([ViewerLinkSilenceWatch]).
     * Default on; DEBUG launch extra `test_viewer_link_silence=false` is the control arm.
     */
    @Volatile var viewerLinkSilence: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Child side of relay recovery, split out of [relayChildReanchor] (switch audit B2, [RelayRecoveryConfig]): a relayed child
     * watches its parent link and closes it when silent for 5 s (and, with [relayParentTrickle], when it trickles). Null (the
     * default, and always in release) follows [relayChildReanchor], so `test_relay_child_reanchor=false` alone still turns it
     * off; DEBUG `test_relay_parent_watch=true|false` sets it apart from the relay-side close.
     */
    @Volatile var relayParentWatch: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Child side of relay recovery, split out of [relayChildReanchor] (B2): after a link loss of an active relayed route the
     * child prefers the same relay parent (and waits for it under [relayRecoveryGrace]). Off by default since build 4 (and
     * always in release): a child whose middle phone was killed and relaunched re-anchored 5/5 (freeze median 2.9 s) without
     * it vs 4/5 (about 4 s, one never recovered) with it. DEBUG `test_relay_prefer_parent=true` brings the old behaviour back.
     */
    @Volatile var relayPreferParent: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * A relay's child writer skips its link pacer only when the next record is audio (default on,
     * 2026-10-11). It used to skip it whenever any audio was queued anywhere, and the child queue is
     * strictly ordered, so during a backlog (which always holds audio, 50 records/s) every record was
     * written unpaced and the Android socket buffer took the excess instead of the app queue
     * (relayfix-0725a213-diagM1: M31 -> ASUS CoC, 57-80 KB / 3.2-4.4 s in flight below the app).
     * DEBUG launch extra `test_relay_pace_head=false` (benchmark tunable `relay_pace_head`) is the
     * control arm restoring the wider test. Read when a child connects.
     */
    @Volatile var relayPaceHeadOnly: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): while a relay's child reports late media (its playout is pinned at the
     * maximum and still late), the relay keeps at most [RELAY_PACE_LEAD_CAP_NS] (250 ms) of
     * measured-rate link time ahead of the link instead of the 20% pace gain's unbounded surplus
     * (LinkPacer.setLeadCapNs). Needs [linkPacing]. DEBUG launch extra `test_relay_pace_lead_cap=true`
     * (benchmark tunable `relay_pace_lead_cap`). Tradeoff: with a train measurement that reads low
     * the capped pace is lower than the gained one while the child is late; fewer stale frames
     * reach the child, which sheds sooner. Read when a child connects.
     */
    @Volatile var relayPaceLeadCap: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off, Q31): a relay's child queue sheds at a target sized in milliseconds of media from the
     * child's measured drain rate and the drain's own jitter, inside the latency budget
     * [timeQueueBudgetMs], instead of the fixed "stream budget minus the child's playout" (RelayTimeQueueSizer).
     * Needs the relay-child media age ([experimentalRelayChildMediaAge], on by default). DEBUG launch extra
     * `test_relay_time_queue=true` (benchmark tunable `relay_time_queue`); read when a child connects.
     */
    @Volatile var relayTimeQueue: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off, Q31 follow-up): a relay's drain rate for a child is its own sustained delivery to that child
     * (bytes written over the seconds media waited for the link, [SustainedDeliveryMeter]) instead of only the
     * child's arrival-train report, which is 0 on slow GATT hops. The train report remains the fallback and the
     * control; `BENCHMARK relay_time_queue` logs `drainSource`. DEBUG launch extra `test_relay_drain_sustained=true`
     * (benchmark tunable `relay_drain_sustained`); read when a child connects. No wire field.
     */
    @Volatile var relayDrainSustained: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off, Q31): a stream viewer's playout grows to the arrival jitter it measured (99th
     * percentile of the relative arrival delay over 60 s) before a frame is late, inside the latency budget
     * [timeQueueBudgetMs]; the earlier lateness rule still applies (PlayoutJitterTarget). DEBUG launch
     * extra `test_playout_time_target=true` (benchmark tunable `playout_time_target`); read when a stream
     * starts. Calls are unaffected.
     */
    @Volatile var playoutTimeTarget: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Latency budget of both time-based arms, ms (300-5000). A relay queue never holds more than this and a
     * viewer pays at most this much delay in advance; beyond it the earlier behaviour applies. DEBUG launch
     * extra `test_time_queue_budget_ms` (benchmark tunable `time_queue_budget_ms`); [playoutTimeBudgetMs]
     * overrides the viewer's alone.
     */
    @Volatile var timeQueueBudgetMs: Long = TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(300L, 5_000L) else TimeBasedSizing.DEFAULT_LATENCY_BUDGET_MS
        }

    /**
     * Viewer playout budget of the time-based playout target alone, ms (300-5000; switch audit B12). Null (the default, and
     * always in release) follows [timeQueueBudgetMs], which keeps its meaning of setting both budgets; DEBUG launch extra
     * `test_playout_time_budget_ms` (benchmark tunable `playout_time_budget_ms`) lets an A/B vary the relay queue's budget
     * and the viewer's separately. Read through [TimeBasedSizing.playoutBudgetMs].
     */
    @Volatile var playoutTimeBudgetMs: Long? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG }?.coerceIn(300L, 5_000L)
        }

    /**
     * Shortest relay queue target of the time-based arm, ms (100-1000). DEBUG launch extra
     * `test_time_queue_floor_ms` (benchmark tunable `time_queue_floor_ms`).
     */
    @Volatile var timeQueueFloorMs: Long = TimeBasedSizing.DEFAULT_FLOOR_MS
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(100L, 1_000L) else TimeBasedSizing.DEFAULT_FLOOR_MS
        }

    @Volatile var callCloseWaitsForPeer: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    @Volatile var cocFreshAcl: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    @Volatile var deferredGattClose: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Re-issue a central's connection-priority request after the stack reports the LE connection
     * update failed (LeConnectionPriorityKeeper). DEBUG launch extra `test_le_priority_retry=false`
     * is the control arm.
     */
    @Volatile var lePriorityRetry: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Viewer UI stability (ReceiverRecoveryNoticePolicy, receiverBrowserEmptyStatus): a repeating
     * "Recovering video" cover is held through gaps under 1 s instead of flashing off and on, and the
     * browser says when the only nearby stream is cooling down after a failed connection instead of
     * "No nearby streams yet". DEBUG launch extra `test_receiver_ui_stability=false` is the control arm
     * (cover follows the decoder flag one to one; the old browser line).
     */
    @Volatile var receiverUiStability: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Watch list grouping (groupStreamRoutes): one row per broadcast, joined through its best route,
     * instead of one row per advertiser (the source and every relaying viewer). DEBUG launch extra
     * `test_stream_row_grouping=false` is the control arm (one row per advertiser, as before).
     */
    @Volatile var streamRowGrouping: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * A GATT client setup that has not seen `onMtuChanged` sends one PHY read at 0.75 s and, if the
     * platform answers it, keeps waiting up to 8 s (the MTU request is then only queued behind the
     * stack's own service discovery) instead of cancelling that discovery at 3 s ([GattMtuWait]).
     * No answer keeps the old 3 s verdict. DEBUG launch extra `test_gatt_mtu_probe=false` is the
     * control arm (fixed 3 s).
     */
    @Volatile var gattMtuProbe: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * LE CoC control links request the preferred PHY from the client only, after the priority update
     * and MTU exchange (BleL2capPsmReader), and the control server only reads it. DEBUG launch extra
     * `test_coc_sequenced_phy=false` restores the old order: both sides request the PHY at connection,
     * beside the priority update (status 42 collisions on M31/ASUS/A15s).
     */
    @Volatile var cocSequencedPhy: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off since 2026-10-28): an LE central's setup PHY request (LE CoC control client and GATT video
     * client) waits while the link's last reported interval is outside the requested priority window, until the requested
     * update lands (then 100 ms) or 1.5 s ([PhyAfterPriority]). Built for M31 relay child links that died after a PHY update
     * between the discovery 7.5 ms and the requested 15 ms updates; the phone A/B (phyprio-ab-20261028) did not confirm that
     * cause (child deaths 2 of 12 with it, 1 of 11 without; the deaths followed the 15 ms update with or without a PHY).
     * DEBUG launch extra `test_phy_after_priority=true` turns it on.
     */
    @Volatile var phyAfterPriority: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): the GATT link that only reads the LE CoC PSM before a fresh ACL requests no priority, PHY
     * or MTU ([PsmReadLinkQuiet]). DEBUG launch extra `test_psm_read_link_quiet=true` turns it on.
     */
    @Volatile var psmReadLinkQuiet: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off; A/B arm): an LE CoC central requests the preferred PHY only after the
     * CoC socket is connected and hop authentication has finished, and waits for onPhyUpdate (readPhy
     * fallback after 1 s) before the end-to-end handshake and media ([CocPhyAfterConnect]); the control
     * server never requests it. DEBUG launch extra `test_coc_phy_after_connect=true` turns it on.
     */
    @Volatile var cocPhyAfterConnect: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): peers tracked by node-ID prefix ([PeerAddressBook]). Every accepted
     * scan result updates its node's freshest address; a direct call to an address older than 10 s or
     * replaced by a newer one rescans for the node first, and connect failures are classified by elapsed
     * time ([LeConnectFailureClassifier]). DEBUG launch extra `test_peer_address_book=true` turns it on.
     */
    @Volatile var peerAddressBook: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): on stacks the `android-11-coc-small-sdu-coalescing` proposal matches
     * (API <= 30), CoC media SDUs are coalesced up to [COC_COALESCED_SDU_BYTES] instead of 245-byte
     * single-PDU SDUs ([cocWriteChunkBytes]). DEBUG launch extra `test_coc_coalesce_sdus=true` turns it on.
     */
    @Volatile var cocCoalesceSdus: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): on stacks the `pre-android-13-coc-congested-write-loss` proposal matches
     * (API <= 32), forwarding CoC writers (call relays, stream relays and forwarders, and a helper's parent link once it
     * becomes a relay hop) use coalesced SDUs of up to [COC_COALESCED_SDU_BYTES] instead of 245-byte single-PDU SDUs
     * ([ForwardingCocCoalescing]). Endpoint links are unchanged. DEBUG launch extra `test_relay_coc_coalesce=true`.
     */
    @Volatile var relayCocCoalesce: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Adaptive LE PHY (default on): a central moves a backlogged 2M link that carries at most about one
     * data PDU per connection event to 1M ([AdaptivePhyPolicy]). DEBUG launch extra
     * `test_adaptive_phy=false` is the control arm (2M kept on every link, as before 2026-10-09).
     */
    /**
     * DEBUG experiment (off = null = HIGH): connection priority a relay requests on its CoC child link
     * (`test_forwarder_coc_priority` = balanced|low|dck|high), to test interleaving of a relay's
     * peripheral (parent) and central (child) connections.
     */
    /** DEBUG experiment (off = null): relay child-link interval in 1.25 ms units ([LeChildInterval]). */
    @Volatile var forwarderChildIntervalUnits: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 6..40 }
        }

    @Volatile var forwarderCocPriority: BleConnectionPriority? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /** Adaptive PHY ignores relays' child links ([RelayUpstream], default on); `test_relay_phy_guard=false`. */
    /**
     * Experimental (off): relay child-link re-anchor ([RelayReanchor]); DEBUG `test_relay_reanchor=true`.
     * Not yet proven: in relaylink-arms3 it never fired before the relay's stall release reconnected.
     */
    @Volatile var relayReanchor: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental (off, DEBUG only): the inbound-child probe of DECISIONS "Design RR" measurement 7
     * ([InboundProbe]). `test_inbound_probe=server` hosts a connectable GATT sink; `=dial` redials it under load.
     * `test_inbound_probe_dials` (15), `_hold_ms` (20000), `_gap_ms` (2000), `_kbps` (60). No media, no hop records.
     */
    @Volatile var inboundProbe: String? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    @Volatile var inboundProbeDials: Int? = null
    @Volatile var inboundProbeHoldMs: Int? = null
    @Volatile var inboundProbeGapMs: Int? = null
    @Volatile var inboundProbeKbps: Int? = null

    @Volatile var relayPhyGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: a call endpoint whose hop is known to be the call's own peer (the hop authenticated as the end-to-end peer, or
     * this caller dialed a direct offer) ignores the hop's call-relay capability bit when it decides whether to write bad-2M memory
     * ([AdaptivePhyMemory.callHopRelays]). The bit is signed by every idle helper, so with relayed calls on a direct call never
     * remembered a bad 2M pair (relay-showcase-20261026 trace-s4 A15s -> ASUS: `bad2m=not_recorded | hop_relays=true`, 4 of 4
     * calls on 2M). DEBUG launch extra `test_direct_call_hop_memory=false` is the control arm (the bit alone skips the memory).
     */
    @Volatile var directCallHopMemory: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Under the relay PHY guard, a relay's child link that measures in the one-PDU-per-event band
     * (at least half of one PDU per event, [relayOnePduBand]) still moves to 1M, without recording
     * bad-2M memory; the starved scheduling band (4-37 kbps at 15 ms) stays untouched. Experimental,
     * default off (mesh4-b9726176-loop4-1 0004: the relay's own receive fell 283 -> ~200 kbps);
     * DEBUG launch extra `test_relay_one_pdu_move=true` turns it on.
     */
    @Volatile var relayOnePduMove: Boolean = false
        set(value) {
            // Default off: release must stay off too (the default-on setter `value || !DEBUG`
            // turned it on in release when MainActivity assigned the false default).
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relay cold start (default on): a viewer with no relay evidence (relay proven 0, no fresh relay
     * ceiling) counts the incoming link it is already carrying and may forward to ONE child, the same
     * "one link beyond what is proven" rule fan-out uses. Without it a fresh install (no probe: prior
     * 700 kbps) never relays a 300 kbps stream (need 807) until it has been a source
     * (mesh4-c0200edf-loop7-1 0001/0005). Rollback stays with the receiver-node health detach and the
     * congestion withdrawal, which record a relay ceiling. DEBUG `test_relay_cold_start=false` is the
     * control arm. Line: `BENCHMARK relay_admission`.
     */
    /**
     * Relay parent fallback on link-establishment failure ([EstablishmentFailureTracker], default on):
     * two establishment failures within 2 min mark the peer for 5 min. A child ranks the marked parent
     * last and a relay stops connecting to a marked child. DEBUG `test_relay_establish_fallback=false`
     * is the control arm. Line: `BENCHMARK relay_parent_fallback`.
     */
    @Volatile var relayEstablishFallback: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): relay establishment budget ([RelayEstablishBudget]). A relay's child
     * connect retries fast establishment failures (GATT 133 / HCI 0x3E) in place with 300-900 ms spacing
     * for up to 12 s from the first connect (a 5 s create-connection timeout at most once), counts ONE
     * failure per exhausted budget, escalates the child mark 30/60/120/300 s and keeps 2M after such a
     * failure (the pre-link rule it shares with [relayEstablishKeep2M], [RelayChildRecoveryPolicy.setupPhyAfterFailure]).
     * DEBUG launch extra `test_relay_establish_budget=true` turns it on; off keeps 2 attempts per cycle and one
     * failure per cycle. The mark escalation is shared with [relayRefusalEscalation] (on by default), so the flat
     * 5-minute mark exists only with that off too ([RelayRecoveryConfig.escalatingRefusalMarks]).
     * Line: `BENCHMARK relay_child_connect`.
     */
    @Volatile var relayEstablishBudget: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var relayColdStart: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental, default off (design decision 2026-11-06, DECISIONS "Relay admission without a measured ceiling"): with
     * no fresh relay ceiling the relay budget is at least 2 x the per-link load whatever relay load was proven before
     * ([LinkCapacityPolicy.relayFloor]); [relayColdStart] keeps naming the proven-0 case, and a fresh relay ceiling keeps
     * the measured budget. DEBUG launch extra `test_relay_no_ceiling_floor`. Lines: `BENCHMARK relay_floor_policy` (launch),
     * `BENCHMARK relay_admission` (adds `floor=` only while on).
     */
    @Volatile var relayNoCeilingFloor: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relayed calls: the idle call endpoint restart guard (default on, a bug fix; rc-ctl-20261106). On: a stale offer
     * never rebuilds an endpoint holding an accepted link that has not signalled a call yet (asked again at the next
     * tick), a new endpoint reads its offer's helper bits from the state at its creation with its own transport, and
     * the static call relay stops when its authenticated child link closes before the caller joins. DEBUG
     * `test_relayed_restart_guard=false` is the control arm (the old restart, cached bits and up to 180 s on a dead
     * child). Lines: `BENCHMARK relayed_helper: offer_restart=deferred|done`, `call_relay_child: event=closed_before_parent`.
     */
    @Volatile var relayedRestartGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Fan-out growth compares proven load and planned links in one unit ([LinkCapacityPolicy.fanOutProvenScale]):
     * proven wire kbps x 1.2 + one planned link. Default on; DEBUG `test_fanout_proven_links=false`
     * is the control arm (raw proven + planned link, which capped a 300 kbps source at 2 children).
     * Line: `BENCHMARK fanout_admission`.
     */
    @Volatile var fanoutProvenLinks: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): learned-limit recovery. A fresh relay/fan-out ceiling may admit one link
     * beyond it after a cool-down ([LinkCapacityPolicy.ceilingProbeOpen], 2 min doubling per failed
     * probe, at most 64 min); healthy traffic above the ceiling clears it (existing rule), a new
     * saturation re-records it and doubles the cool-down. Bad-2M marks age out after 24 h instead of
     * 7 days unless re-confirmed. DEBUG `test_ceiling_probe_recovery=true`. Line: `BENCHMARK ceiling_probe`.
     */
    @Volatile var ceilingProbeRecovery: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var adaptivePhy: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Per-peer bad-2M memory and the one bounded 1M call-setup retry ([AdaptivePhyMemory], default on).
     * DEBUG launch extra `test_adaptive_phy_memory=false` is the control arm.
     */
    @Volatile var adaptivePhyMemory: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * 2M call-setup stall guard (default on, part of the bounded 1M setup retry): the first 2M LE call
     * link is closed when its hop authentication has not finished [AdaptivePhyMemory.SETUP_STALL_MS]
     * after it opened, and the 1M retry of a direct offer scans for the peer's current advertisement (it
     * re-advertises under a new random address) instead of reconnecting to the old one; a relayed offer
     * keeps its original relay (requested parent). DEBUG launch extra
     * `test_phy_setup_stall=false` is the control arm (retry only after the link dies, to the old address).
     */
    @Volatile var phySetupStallGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default on; a protection that already guards calls): the adaptive-PHY protections of a call dial also guard
     * a store-and-forward message-exchange dial ([MessageDialPhy]): the first 2M link is closed when its hop authentication
     * stalls, one bounded 1M retry follows, and a peer that needed it (or whose 2M exchange died early with no answer) starts
     * on 1M next time. Needs [adaptivePhy] and [adaptivePhyMemory] for the retry and the memory, [phySetupStallGuard] for the
     * stall. DEBUG launch extra `test_message_adaptive_phy=false` is the control arm (one attempt on the endpoint PHY).
     */
    @Volatile var messageAdaptivePhy: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default on; the call and message-dial protection applied to the stream source): a direct GATT stream whose 2M
     * link went silent (connection timeout or heartbeat timeout) within [AdaptivePhyMemory.EARLY_LOSS_MS] of hop authentication marks
     * the authenticated hop peer bad on 2M, so the next stream link to it starts on 1M ([StreamPhyLoss]). Needs [adaptivePhy] and
     * [adaptivePhyMemory]. DEBUG launch extra `test_stream_adaptive_phy=false` is the control arm (the source redials on 2M).
     */
    @Volatile var streamAdaptivePhy: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default on): a call dial whose 2M setup stalled (hop authentication unfinished after the stall guard) remembers
     * the direct callee's advertised node as bad on 2M at the stall itself, not only after a successful 1M retry. Before, a retry
     * that could not find the callee again (the A15s calling the ASUS: no new advertisement within 8 s, then a 12 s connect to the
     * old address) left no memory and every later call repeated the stall. Needs [adaptivePhy], [adaptivePhyMemory] and
     * [phySetupStallGuard]. DEBUG launch extra `test_stall_remembers_2m=false` is the control arm (memory only after a 1M success).
     */
    @Volatile var stallRemembersTwoM: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental, default 8000 (= the earlier behaviour, [StallRediscover]): total scan time, ms, for the callee's new advertisement
     * in the 1M retry after a CallSetupStalledException. Above 8000 (the A/B arm is 20000) the scan is not clamped by the early-loss
     * window and a found fresh address is dialed at once; the stale address is dialed only when nothing was found. DEBUG launch
     * extra `test_stall_rediscover_ms`; release builds always use 8000.
     */
    @Volatile var stallRediscoverMs: Long = StallRediscover.DEFAULT_MS
        set(value) {
            field = if (BuildConfig.DEBUG) StallRediscover.normalize(value) else StallRediscover.DEFAULT_MS
        }

    /**
     * On by default since 2026-11-04 (owner, after release-window-ab2 16/17 vs 9/17 and preservation on three pairs;
     * [StallRediscover.releaseWindowMs]): the 1M retry after a failed 2M call setup scans for the callee's new advertisement
     * for as long as the callee can take to release the old link (3 x the LE supervision timeout after our own close, 1 x
     * after an observed loss, plus the callee's GATT restart pause and 2 s), instead of the fixed 8 s clamp that made A15s ->
     * ASUS retries dial a stale address. DEBUG `test_rediscover_release_window=false` is the control arm; release builds are
     * always on.
     */
    @Volatile var rediscoverReleaseWindow: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: the 1M call-setup retry after a 2M setup stall waits (bounded [SetupRetryAclWait.BOUND_MS]) for the stalled
     * link's ACL to drop before it dials, so it does not dial into the lingering ACL (HCI 0x3E/0x0B, GATT 133). DEBUG launch
     * extra `test_setup_retry_acl_wait=false` is the control arm (dial at once).
     */
    @Volatile var setupRetryAclWait: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: a fast 0x3E failure of the 1M call-setup retry after a 2M stall is redialed up to [StallRetryDials.MAX_ATTEMPTS]
     * dials, [StallRetryDials.SPACING_MS] apart, inside the early-loss window. DEBUG launch extra `test_stall_retry_dials=false` is
     * the control arm (the ordinary 2 GATT open attempts 500 ms apart).
     */
    @Volatile var stallRetryDials: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default on: for a rotating (pseudonym) call offer recognised as exactly one contact, the bad-2M memory is written and looked
     * up under that contact's node label (the key the 1M retry already writes), so a remembered pair starts on 1M; an
     * unrecognised rotating offer has no memory. The memory stays in local preferences. DEBUG launch extra
     * `test_phy_memory_contact_key=false` is the control arm (lookup under the pseudonym, no record at the stall).
     */
    @Volatile var phyMemoryContactKey: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Relayed calls (default on, relay-showcase 2026-10-26): the first hop of an on-demand relayed route (caller to the
     * selected helper, before SETUP) gets the direct-call 2M protections for that helper: its bad-2M memory is read at the
     * dial and written at a 2M setup stall under the helper's recognised contact node, and the 1M retry rescans for the
     * helper's own current offer (same helper ID; the hop opening still proves it) instead of redialing the stale address.
     * DEBUG launch extra `test_relayed_first_hop_direct=false` is the control arm (retry to the old address, no memory).
     */
    @Volatile var relayedFirstHopDirect: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Relayed calls (default on, relay-showcase 2026-10-26): a helper whose connect to its next hop fails within
     * [RelayHopPolicy.FAST_FAIL_MS] (no link was formed) retries that same connect once, logged, while at least
     * [RelayHopPolicy.RETRY_MIN_LEFT_MS] of the SETUP deadline is left. DEBUG `test_relayed_hop_retry=false` is the control arm.
     */
    @Volatile var relayedHopRetry: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, relay-showcase 2026-10-26): a helper opens its next hop of a relayed call on LE 1M instead of
     * the 2M default (logged as requested 2M, effective 1M). Hypothesis under test: the A15s -> ASUS child hop stalls right after
     * the 2M PHY update, as the direct A15s -> ASUS 2M setup does. DEBUG launch extra `test_relayed_hop_1m=true`.
     */
    @Volatile var relayedHop1m: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relayed calls (default on, relay-showcase 2026-10-26): a relayed call whose route failed before READY tries the
     * next-best route through a different helper inside the same attempt (at most 3 routes in 60 s, exact relay count
     * under `require`, every hop authenticated as before), logged as `relayed_route: ... attempt=N`. DEBUG launch extra
     * `test_relayed_candidates=false` is the control arm (the attempt ends at the first failed route, as before).
     */
    @Volatile var relayedCandidates: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Relayed calls (default on, relay-showcase 2026-10-26): a helper's next hop gets the call's measured per-link 2M
     * protection. A first 2M attempt whose connect fails or whose hop authentication stalls 3 s is retried once on 1M to the
     * next hop's freshest advert; a 1M retry that authenticates records that hop peer bad on 2M, so the next dial of the pair
     * starts on 1M. Evidence: A15s -> ASUS child hop 0 of 4 on 2M, 3 of 4 on 1M (`test_relayed_hop_1m` A/B). DEBUG launch
     * extra `test_relayed_hop_phy_retry=false` is the control arm; `test_relayed_hop_1m=true` still forces 1M from the start.
     */
    @Volatile var relayedHopPhyRetry: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental, default off (AP7 F3, analysis a15s-asus 2026-11-07): the DEBUG static private-call relay
     * (`test_private_call_relay=true`) gives its LE child hop the same 3 s hop-authentication guard and one 1M retry as an
     * on-demand hop, and records the pair bad on 2M after a stalled 2M attempt. Production relayed calls already use the
     * on-demand path (`startOnDemand` -> `openNextHop`); this makes the benchmark's static shape match it. DEBUG launch
     * extra `test_relay_start_hop_guard`.
     */
    @Volatile var relayStartHopGuard: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Relayed calls, DECISIONS Design C "Amendment CP" (default on, review CP-1): every relay edge (caller to helper, helper
     * to helper, helper to target) runs BMH3 with a throwaway that declares PSEUDONYM, fresh per connection, never BMH2 while
     * pseudonym mode is on; a hop is bound when it answers with a real key and not when it declared a throwaway; a helper
     * accepts one SETUP on such a link. DEBUG launch extra `test_relayed_pseudonymous_edges=false` is the control arm
     * (today's links: relayed calls fail at the first hop under the BMH3 pseudonym defaults).
     */
    @Volatile var relayedPseudonymousEdges: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Relayed calls between strangers (design decision 2026-10-26, with the safeguards of review CP-1): a relayed call whose
     * end-to-end caller is a throwaway rings, labelled "via a phone nearby", and a caller may place a relayed call to a target
     * it holds no contact tag for. A relay in the middle can pose as the caller of such a call. DEBUG launch extra
     * `test_relayed_stranger_calls=false` is the control arm (contacts only).
     */
    @Volatile var relayedStrangerCalls: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off): a caller that recognises the selected helper's offer as exactly one contact tags that
     * helper's own offer at the link and runs real/real with the opening check, instead of a throwaway. Privacy cost: the
     * helper learns who is calling. A failed contact-helper authentication fails the candidate (no anonymous retry). DEBUG
     * launch extra `test_relayed_contact_helper=true`.
     */
    @Volatile var relayedContactHelper: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental, default off (A/B for the A15s-central to ASUS-peripheral pair): the GATT client asks for different PHYs per
     * direction instead of the same one ([GattPhySplit]); `tx2m-rx1m` keeps the central -> peripheral media direction on 2M and
     * moves the peripheral -> central direction, the one that went silent, to 1M. DEBUG launch extra `test_gatt_phy_split`.
     */
    @Volatile var experimentalPhySplit: GattPhySplit? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Experimental, default off (A/B for the ASUS <-> A15s pair, which the static [experimentalPhySplit] fixes at a cost on healthy
     * pairs): links start on the normal 2M preference and, where the adaptive PHY would move a GATT link from 2M to 1M
     * (`one_pdu_per_event`, `stalled_one_way`), only one direction moves ([adaptiveSplitFor]); a link that still fails then moves to
     * full 1M as before. Non-null is the split to request: `test_adaptive_phy_split=true` selects `tx2m-rx1m`, and
     * `test_adaptive_phy_split_dir=tx1m-rx2m` the mirror. Needs `test_adaptive_phy`.
     */
    @Volatile var adaptivePhySplit: GattPhySplit? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Experimental, default off (Q14, design decision 2026-11-06; A/B on ASUS <-> A15s, S24 <-> Xiaomi, A55 <-> M31): a GATT
     * central's 2M link moves only the failing direction to 1M ([PhyDirectionSplit]: receive silence moves RX; a backlogged
     * writer tries RX, then the mirror, then full 1M) and the split that worked is remembered per peer, so the next link to
     * that peer asks for it at setup ([AdaptivePhyMemory.splitFor]). Takes precedence over [adaptivePhySplit]. Needs
     * `test_adaptive_phy`. DEBUG launch extra `test_phy_direction_split` ([PhySwitches.radioArms]).
     */
    @Volatile var phyDirectionSplit: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental, default off (A/B for the A15s-central to ASUS-peripheral pair and for rotating discovery IDs): the stream
     * source's GATT link opens on 1M and asks for 2M only after the hop peer authenticated, unless the authenticated node is
     * remembered bad on 2M ([phyAfterAuth]). The hop handshake then never runs on a 2M link, and the memory is read by the
     * authenticated node, which a rotating advertised prefix cannot be matched to. DEBUG launch extra `test_phy_after_auth`.
     */
    @Volatile var phyAfterAuth: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Default on: a single GATT link whose backlogged writer carries under a quarter of one PDU per event on 2M while the peer is
     * still heard moves to 1M and the peer is remembered bad on 2M ([AdaptivePhyLink], reason `stalled_one_way`). Before, such a
     * link was `kept` on 2M forever. DEBUG launch extra `test_stalled_2m_move=false` is the control arm.
     */
    @Volatile var stalledTwoMMove: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Control for the backlog skip at a PHY move (design decision Q-SKIP1, 2026-10-14). null (the default, and always in a release build)
     * follows [PhyMoveSkip.enabled]: on for a live stream with a fixed (manual) bitrate, off with Auto quality, calls and audio. DEBUG
     * launch extra `test_phy_move_skip_backlog=false` is the control arm; `=true` forces it on. At a PHY move queued video older than
     * twice the viewer's playout (at least 4 s) is dropped once, codec-safely, and a keyframe is requested ([PhyMoveSkip]).
     */
    @Volatile var phyMoveSkipBacklog: Boolean? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Messaging v2 progress-aware total limit (default on): a non-chat exchange's 120 s total limit is extended while chunks keep
     * flowing, up to [StoreForwardLimits.sessionHardCapMs] ([SessionDeadlines]); the 10 s liveness and progress deadlines stay strict.
     * DEBUG launch extra `test_mv2_progress_total=false` is the control arm (a fixed 120 s total, resumed in later sessions).
     */
    @Volatile var messageProgressTotal: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * LE CoC SDUs of at most 245 bytes, one 251-octet data PDU each ([cocWriteChunkBytes], default on).
     * DEBUG launch extra `test_coc_single_pdu_sdus=false` is the control arm (SDUs up to the socket limit).
     */
    @Volatile var cocSinglePduSdus: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): LE links request another PHY instead of 2M (DEBUG `test_ble_phy=1m`,
     * `coded-s2` or `coded-s8`; see [ExperimentalBlePhy]). 1M is a control for the ASUS <-> A15s
     * ~65 kbps link; Coded is a long-range option for low-rate (voice) links. 2M stays the only
     * user-selectable PHY.
     */
    @Volatile var experimentalBlePhy: ExperimentalBlePhy? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    /**
     * Experimental (off): ATT MTU the GATT video client requests (`test_gatt_request_mtu`, 23-517);
     * null keeps 517. A value of 247 keeps each write within one 251-byte data-length PDU.
     */
    @Volatile var gattRequestMtu: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 23..517 }
        }

    /**
     * Experimental scene-cut shaping (workstream C; off by default). A raw-frame luma detector finds
     * a hard cut before the frame reaches a MediaCodec encoder; SYNC requests that frame as a sync
     * frame at a lowered target, TARGET only lowers the target, and the target is restored once the
     * frame is encoded (SceneCutShaper). Only raw-fixture and ByteBuffer camera input pass the app;
     * live Camera2 surface input does not. Off: nothing is requested; benchmark traces still log the
     * detector's decisions (BENCHMARK scene_cut). DEBUG launch extra `test_scene_cut_shaping`
     * (0 off, 1 sync, 2 target; benchmark tunable `scene_cut_shaping`).
     */
    @Volatile var sceneCutShaping: SceneCutShapingMode = SceneCutShapingMode.OFF
        set(value) {
            field = if (BuildConfig.DEBUG) value else SceneCutShapingMode.OFF
        }

    /**
     * Experimental VP9 quantizer headroom (off by default): while the parsed base_q_idx stays below
     * this floor, the encoder target is lowered so a scene cut is not coded at a stale low
     * quantizer (QuantizerHeadroomGovernor). Null leaves the target alone. DEBUG launch extra
     * `test_vp9_q_floor` (1-254; benchmark tunable `vp9_q_floor`).
     */
    @Volatile var vp9QuantizerFloor: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 1..254 }
        }

    /**
     * Experimental encoder ranking by measured behaviour (a design decision; off by
     * default). On: an encoder whose recorded sessions kept scene-cut frames within about 0.5 s of
     * its own stream (and, for software, encode time within budget) ranks above one measured to
     * burst, e.g. the M31's libaom AV1 (26 KB cuts) above its OMX VP9 (135 KB). Off: the static
     * hardware-first order. DEBUG launch extra `test_encoder_ranking` (benchmark tunable
     * `encoder_ranking`); measurements are recorded in both arms.
     */
    @Volatile var experimentalEncoderRanking: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Automatic prefers an eligible hardware AV1 encoder (design decision 2026-10-27, release 0.4.1; default on).
     * DEBUG launch extra `test_prefer_hw_av1=false` is the control arm: hardware AV1 ranks after the other
     * hardware encoders (hardware VP9 first). Release builds always prefer it.
     */
    @Volatile var preferHardwareAv1: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (off): GATT writes and notifications carry values sized to fill whole 251-octet
     * LE data PDUs (495 bytes instead of 514 at MTU 517; see gattValueBytes). DEBUG launch extra
     * `test_gatt_aligned_writes`. Read when a GATT link opens.
     */
    @Volatile var experimentalAlignedGattWrites: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental burst-aware drain (off by default): a child's lateness that follows a large frame
     * while this link now carries everything offered is attributed to that one burst instead of
     * congestion, and large-frame grace lasts while the backlog keeps shrinking. A single 72 KB
     * scene cut otherwise cut M31->ASUS GATT 300 to 206 kbps for 12 s with an empty sender queue
     * (regress-57484598 case 0006). DEBUG launch extra `test_burst_drain` (benchmark tunable
     * `burst_drain`).
     */
    @Volatile var experimentalBurstDrain: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental one-step playout growth (lane-b P2, off by default): media later than
     * MediaPlayoutClock.BURST_LATENESS_MS grows the adaptive delay by this multiple of its lateness
     * at once, instead of lateness + 50 ms per late frame (a staircase of separate holds during one
     * burst). Costs a longer first hold and more held latency. Null keeps the staircase. DEBUG
     * launch extra `test_playout_burst_growth` (1.0-4.0; benchmark tunable `playout_burst_growth`).
     */
    @Volatile var playoutBurstGrowth: Double? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 1.0..4.0 }
        }

    /**
     * Experimental hardware intra refresh (PLAN workstream C, off by default): MediaFormat
     * intra-refresh-period in frames at configure. Whether the encoder applied it is logged from its
     * output format (BENCHMARK encoder_capabilities). Null leaves periodic keyframes. DEBUG launch
     * extra `test_intra_refresh_frames` (benchmark tunable `intra_refresh_frames`).
     */
    @Volatile var intraRefreshFrames: Int? = null
        set(value) {
            field = value?.takeIf { BuildConfig.DEBUG && it in 2..600 }
        }

    /**
     * Default-on measured bitrate adjuster for MediaCodec encoders (EncoderBitrateAdjuster, a port of
     * WebRTC's DynamicBitrateAdjuster); DEBUG extra `test_encoder_bitrate_adjuster=false` (benchmark
     * tunable `encoder_bitrate_adjuster`) is the control arm, which restores the output-rate trim.
     */
    @Volatile var encoderBitrateAdjuster: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Device-family policy entries turned off for this run (DevicePolicyRegistry IDs, or "all").
     * DEBUG launch extra `test_device_policy_off=id1,id2`; release builds always keep every entry.
     */
    @Volatile var devicePolicyOff: Set<String> = emptySet()
        set(value) {
            field = if (BuildConfig.DEBUG) value else emptySet()
        }

    /**
     * Experimental (off): WebRTC FramerateBitrateAdjuster for OMX.Exynos. VP9/H.264 encoders
     * (CodecPolicy.EXYNOS_FRAMERATE_ADJUSTER_ARM) instead of the measured bitrate adjuster. DEBUG extra
     * `test_encoder_framerate_adjuster=true` (benchmark tunable `encoder_framerate_adjuster`).
     */
    @Volatile var encoderFramerateAdjuster: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * True for a DEBUG launch carrying any `test_*` extra (benchmark runner or manual test launch).
     * Policies meant for user sessions only (decoder-init-fallback) keep the former behaviour then,
     * so a case measures what it requested.
     */
    @Volatile var benchmarkLaunch: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** Automatic encoder output-rate trim; DEBUG A/B runs can turn it off. */
    @Volatile var encoderRateTrim: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental temporal layering (workstream A; off by default). Removed on 2026-10-07 because
     * it drops frames, restored on 2026-10-08 because experiments stay in the code, off by default.
     * On: the encoder makes every other frame refresh nothing while LayeringPolicy sees a tight link
     * (native libaom AV1; MediaCodec only with [hardwareTemporalLayers]), frames are marked
     * discardable only when their bitstream proves it (BitstreamDroppability), sender, relay, call
     * and decode queues may skip those frames when behind (TemporalLayerThinning), and admission may
     * plan extra links at the measured base layer. Off: no layer control reaches the encoder, no
     * frame carries the discardable flag, no queue thins and admission is unchanged.
     *
     * Set from the DEBUG launch extra `test_temporal_layers` (benchmark tunable `temporal_layers`) or
     * the persisted Advanced setting "Experimental: temporal layering"; read when a stream starts.
     */
    @Volatile var experimentalTemporalLayers: Boolean = false

    /**
     * With [experimentalTemporalLayers] on, also ask MediaCodec encoders for two temporal layers
     * (DEBUG only, launch extra `test_hardware_temporal_layers`). Off: on the M31 OMX VP9 encoder the
     * error-resilient layered stream lost ~11 VMAF at the same rate (pacing-ab-c777b381) and burst
     * harder. Frames an encoder makes droppable on its own are still verified and used.
     */
    @Volatile var hardwareTemporalLayers: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental relay child thinning (P2 of the adaptive-scaling review; off by default, DEBUG only).
     * On: the source encodes two temporal layers (every other frame is verified discardable; libaom
     * always, MediaCodec AV1, MediaCodec VP9 only with [hardwareTemporalLayers]); each relay decides per
     * direct child, from that child's queue age, delivery and lateness report, whether to forward all
     * frames, only the base layer (half the frame rate, no re-encode, no keyframe) or audio only (video
     * paused for that child until its link recovers, with "Video paused: weak link" on the child); the
     * viewer shows that notice when audio keeps arriving but video does not. Hop-local: no state of any
     * phone beyond the next hop is used, and the wire is unchanged (whole records are dropped; the
     * end-to-end sequence check accepts gaps). Off: no layer control reaches the encoder, nothing is
     * dropped for a layer and no notice is shown. Launch extra `test_relay_child_thinning` (benchmark
     * tunable `relay_child_thinning`); read when a stream or forwarder starts.
     */
    @Volatile var relayChildThinning: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental call video fragments (off by default; see CallVideoFragments). On at both
     * endpoints of a direct call: video frames above 3 KB travel as authenticated 2 KB fragments and
     * queued call audio is written between them instead of waiting behind the whole frame (GATT
     * audio waited 620-1193 ms behind frames, calls-noloss-m31asus-51cc8028). Off: the hello carries
     * no fragment bit and the wire is unchanged. Launch extra `test_call_video_fragments`
     * (benchmark tunable `call_video_fragments`); read when the call endpoint is created.
     */
    @Volatile var callVideoFragments: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental airtime-sized call video fragments (off by default; see CallFragmentAirtime). Needs
     * [callVideoFragments]. The target link time per fragment in ms (the A/B arm uses 40): the sender
     * sizes each fragment as measured link rate x this time instead of the fixed 2 KB, within
     * 675..4096 bytes (framing and tags stay at or below 10%), so call audio waits about this long
     * behind one video write at any link rate. 0 = off = the fixed 2 KB fragments. Capped at 250 ms.
     * Launch extra `test_call_fragment_airtime_ms` (benchmark tunable `call_fragment_airtime_ms`);
     * read when the call endpoint is created.
     */
    @Volatile var callFragmentAirtimeMs: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) CallFragmentAirtime.clampTargetMs(value) else 0
        }

    /**
     * Experimental speech-led call playout (off by default; see CallPlayoutPolicy). On: late call
     * video may grow the shared call playout delay by at most 150 ms; late audio keeps the full call
     * range. Video beyond that plays late instead of delaying speech (every GATT call pinned the
     * shared delay at its 600 ms maximum, calls-noloss-*-51cc8028). Off: unchanged. Launch extra
     * `test_call_speech_led_playout` (benchmark tunable `call_speech_led_playout`).
     */
    /**
     * `test_call_head_of_line=off|gatt_default|on` ([CallHeadOfLine.Mode]; null = absent): one switch for the call head-of-line
     * measures of a DEBUG benchmark launch. The promoted policy (design decision 2026-10-11, DECISIONS "Call head-of-line A/B":
     * fragments plus speech-led playout on direct BLE GATT calls; GATT audio queue age p95 -38..-69%, playout delay p50 about
     * -100 ms; CoC and Classic unchanged) is what [CallHeadOfLine] resolves for user launches; `gatt_default` measures it.
     */
    @Volatile var callHeadOfLineMode: CallHeadOfLine.Mode? = null
        set(value) {
            field = value.takeIf { BuildConfig.DEBUG }
        }

    @Volatile var callSpeechLedPlayout: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Default-on fix (2026-10-09): call audio has its own room in the call send queue, so audio
     * waiting behind one long video write no longer fills the video budget and drops every queued
     * frame (privateCallAudioHeadroomPackets). Control arm: launch extra
     * `test_call_audio_headroom=false` (benchmark tunable `call_audio_headroom`). Release builds
     * always keep the fix.
     */
    @Volatile var callAudioHeadroom: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default-on fix (2026-10-09): a legacy Qualcomm phone's end-to-end call hello lists its real
     * decoders plus a signed VP9 preference instead of VP9 only (privateCallEndpointDecodeMask);
     * before, such calls failed ("Peer cannot decode any locally available call codec"). Control
     * arm: launch extra `test_call_codec_fallback=false` (benchmark tunable `call_codec_fallback`).
     */
    @Volatile var callCodecFallback: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental legacy low delay (off by default; see privateCallLegacyLowDelay): direct calls on
     * a legacy-constrained route send audio in 40 ms pairs instead of 400 ms batches and play with
     * the normal 250-600 ms call delay instead of 500-800 ms. Launch extra
     * `test_call_legacy_low_delay` (benchmark tunable `call_legacy_low_delay`).
     */
    @Volatile var callLegacyLowDelay: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Call audio coalescing (default on since build 4; see coalesceQueuedCallAudio): ordinary 20 ms Opus units
     * that already wait in the call queue go out as one existing pair/batch record. No added delay, no wire
     * change. DEBUG control arm: launch extra `test_call_audio_coalesce=false` (benchmark tunable `call_audio_coalesce`).
     */
    @Volatile var callAudioCoalesce: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental call audio pairs (off by default; see privateCallAudioBatchSizeForRoute): routes that send single
     * 20 ms units send 40 ms pairs (+20 ms for every other unit). No wire change. Launch extra
     * `test_call_audio_pairs` (benchmark tunable `call_audio_pairs`).
     */
    @Volatile var callAudioPairs: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental AEAD reuse (off by default; see MeshAead.Context): hop and endpoint record writers, readers,
     * sealers and openers keep one AES-GCM `Cipher` instead of a provider lookup per record, seal the header and
     * ciphertext into one array (one stream write per hop record) and open endpoint records in place. Byte-for-byte
     * the same records. Read when a link or endpoint session is created. Launch extra `test_aead_cipher_reuse`
     * (benchmark tunable `aead_cipher_reuse`).
     */
    @Volatile var aeadCipherReuse: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental stream audio batches (off = 2, the 40 ms pairs; see streamAudioBatchFrames): 3..10 units per stream
     * audio record. Launch extra `test_stream_audio_batch_frames` (benchmark tunable `stream_audio_batch_frames`).
     */
    @Volatile var streamAudioBatchFrames: Int = 2
        set(value) {
            field = if (BuildConfig.DEBUG) value else 2
        }

    /**
     * Experimental legacy relief (off by default; see privateCallStackFingerprint): no legacy
     * Qualcomm call protection applies on this phone (geometry, bitrate and audio caps, VP9-only
     * codec rules, software VP9 decoder, audio batching, flush interval, constrained playout), and
     * it advertises its normal receive limits. Launch extra `test_call_legacy_relief` (benchmark
     * tunable `call_legacy_relief`).
     */
    @Volatile var callLegacyRelief: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Private-call relay chains (on by default, 2026-10-09; see PrivateCallRelayChain): a call relay
     * may take another relay's offer as its child (caller -> R1 -> ... -> callee, at most
     * PrivateCallRelayChain.MAX_RELAYS relays). Control arm: launch extra
     * `test_call_relay_chain=false` (benchmark tunable `call_relay_chain`) keeps the one-relay rule
     * exactly (a relay's child must authenticate as the announced callee). The call relay itself is
     * DEBUG-only.
     */
    @Volatile var callRelayChain: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental measured relayed-call cap (off by default; see privateCallVideoSettingsForRoute):
     * a relayed call's video bitrate cap is this phone's fresh measured link x 0.2125 less audio
     * (a relay's one radio carries both adjacent links, each in both directions), never below the
     * legacy 110 kbps; FPS stays at most 15. Off: the legacy 15 FPS / 110 kbps cap. Launch extra
     * `test_relayed_call_measured_cap` (benchmark tunable `relayed_call_measured_cap`); read when the
     * call endpoint is created.
     */
    @Volatile var relayedCallMeasuredCap: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental lock-screen call presentation (off by default; see CallLockScreen). Launch extra
     * `test_call_lock_screen` (benchmark tunable `call_lock_screen`).
     */
    @Volatile var callLockScreen: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental first call frame shown on arrival (off by default): the first decoded frame of
     * the peer's call video is fed and shown at once instead of waiting for its 250-500 ms playout
     * target; later frames keep the shared timeline. Launch extra `test_call_first_frame_now`
     * (benchmark tunable `call_first_frame_now`).
     */
    @Volatile var callFirstFrameNow: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental first call frame with a playout ramp (off by default; see FirstFrameRamp): the first decoded frame
     * is shown at once, then the following frames play slowed (at least 67% speed) until they meet the normal playout
     * target, about 2 s later, instead of freezing until it (the freezes of callFirstFrameNow, Q16). Takes precedence
     * over callFirstFrameNow. Launch extra `test_call_first_frame_ramp` (benchmark tunable `call_first_frame_ramp`).
     */
    @Volatile var callFirstFrameRamp: Boolean = true
        set(value) {
            // Default on: a release build keeps it on; a DEBUG launch can pass false as the control arm.
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental (default off, JT1, [CallPrewarm]): a ringing video call prepares its encoder, and at the peer's call
     * header its decoder component; camera and microphone still start only after Accept and the media decision. Launch
     * extra `test_call_prewarm` (benchmark tunable `call_prewarm`).
     */
    @Volatile var callPrewarm: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Promoted 2026-11-12 on mechanism (default on, release builds keep it on; JT2, [CallMediaStartOrder]): after Accept and the
     * block check, the call starts its camera before its audio route (the route open is queued on the audio command thread, the
     * microphone after it), the camera opens while its preview surface is requested ([CameraOpenJoin]) and remote audio plays
     * only after the route open. Nothing starts earlier than before. DEBUG launch extra `test_call_camera_first=false` (benchmark
     * tunable `call_camera_first`) is the control arm.
     */
    @Volatile var callCameraFirst: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Default-on (owner 2026-11-10; see CallMediaAccess, UI audit 2026-11-10 UI-01): a video call answered or placed
     * while only camera access is refused goes on as a voice call with a notice (the existing camera-off answer, no
     * wire change). Control arm: launch extra `test_call_voice_when_camera_refused=false` (benchmark tunable
     * `call_voice_when_camera_refused`), where the refusal is only said. Release builds always keep it on.
     */
    @Volatile var callVoiceWhenCameraRefused: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Experimental quiet end of an incoming link that never rang (off by default until its phone A/B; see
     * PreRingFailure, call audit 2026-11-10 CALL-02): no screen change and no "The incoming call couldn't connect"; the
     * endpoint is rebuilt as today and one `call_pre_ring_failure` line records it. Off: the ordinary call-ended path.
     * Launch extra `test_call_quiet_pre_ring_failure` (benchmark tunable `call_quiet_pre_ring_failure`). The constant is
     * the release behaviour and the launch default in one place, so promoting it is one edit (a promoted switch that
     * keeps a `value && BuildConfig.DEBUG` setter stays off in release builds); a DEBUG launch can set either arm.
     */
    const val CALL_QUIET_PRE_RING_FAILURE_DEFAULT = false
    @Volatile var callQuietPreRingFailure: Boolean = CALL_QUIET_PRE_RING_FAILURE_DEFAULT
        set(value) {
            field = if (BuildConfig.DEBUG) value else CALL_QUIET_PRE_RING_FAILURE_DEFAULT
        }

    /**
     * Experimental call encoder fit (off by default; see CallEncoderFit): a call geometry at which
     * this phone's native encoder stayed over budget at its fastest settings is remembered, and later
     * calls start one supported size smaller at the same frame rate. Launch extra
     * `test_call_encoder_fit` (benchmark tunable `call_encoder_fit`); live camera only.
     */
    @Volatile var callEncoderFit: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental, default off (AP5, 2026-11-07; see SoftwareEncoderShortfall): when this phone's software encoder stayed
     * under 80 % of the frame rate in force for 3 windows while its input kept up, that encoder, size and FPS are remembered
     * for 7 days, and the next call or stream that would start with it prefers this phone's hardware H.264 encoder (when
     * the peer decodes H.264 and Automatic may use it). Changes the codec choice only, never the requested size, FPS or
     * bitrate; never a hand-picked encoder. Launch extra `test_sw_shortfall_avc` (benchmark tunable `sw_shortfall_avc`).
     */
    @Volatile var swShortfallAvc: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Experimental call audio starvation concealment (off by default, 2026-10-10; see
     * AudioConcealment): when a call's jitter buffer runs dry the playout writes a short
     * pitch-repeat fade into comfort noise instead of letting the AudioTrack underrun, cross-fades
     * into the next real audio, and speeds playout up only during silence. Launch extra
     * `test_call_audio_conceal` (benchmark tunable `call_audio_conceal`); read when a call's audio
     * decoder starts. Line: `BENCHMARK audio_conceal`.
     */
    @Volatile var callAudioConceal: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * Native libopus call audio (SWITCHES register "Native libopus call audio"; promoted to Settings >
     * Features on 2026-10-11, default [ConnectivityFeatureDefaults.NATIVE_OPUS] until phone test T10):
     * calls encode with OPUS_APPLICATION_VOIP and in-band FEC, and decode with libopus PLC on a gap
     * plus FEC recovery from the next packet, instead of MediaCodec (c2.android.opus has neither).
     * Same Opus bitstream; no wire change. Resolved by [NativeOpusSettings.resolveEnabled]: a DEBUG
     * benchmark launch reads only `test_call_native_opus` (benchmark tunable `call_native_opus`),
     * every other launch the user's Settings > Features choice or the default, in release builds too.
     * Read when a call's encoder or decoder starts. Streams unchanged.
     */
    @Volatile var callNativeOpus: Boolean = false

    /**
     * Native call audio sub-switch (`test_call_native_opus_fec`, default on): in-band FEC. Only a DEBUG
     * build can turn it off: now that the main switch is a release setting, a launch extra from
     * another app must not change the codec.
     */
    @Volatile var callNativeOpusFec: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * Native call audio expected-loss hint in percent (`test_call_native_opus_loss_pct`, default 10).
     * Release builds always use the default, for the reason given at [callNativeOpusFec].
     */
    @Volatile var callNativeOpusLossPercent: Int = NativeOpusSettings.DEFAULT_LOSS_PERCENT
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, 100) else NativeOpusSettings.DEFAULT_LOSS_PERCENT
        }

    /**
     * Native call audio DTX (`test_call_native_opus_dtx`, default off): silent frames are not sent.
     * Changes the packet cadence (receivers see gaps), so it needs its own A/B before any promotion.
     */
    @Volatile var callNativeOpusDtx: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    internal fun nativeOpusSettings(): NativeOpusSettings =
        NativeOpusSettings(fec = callNativeOpusFec, lossPercent = callNativeOpusLossPercent, dtx = callNativeOpusDtx)
}
