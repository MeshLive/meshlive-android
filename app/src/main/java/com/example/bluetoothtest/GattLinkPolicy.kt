package com.example.bluetoothtest

import com.example.bluetoothtest.PolicyConfidence.HIGH
import com.example.bluetoothtest.PolicyConfidence.LOW
import com.example.bluetoothtest.PolicyConfidence.MEDIUM
import com.example.bluetoothtest.PolicySourceKind.AOSP_COMMIT
import com.example.bluetoothtest.PolicySourceKind.AOSP_SOURCE
import com.example.bluetoothtest.PolicySourceKind.API_REFERENCE
import com.example.bluetoothtest.PolicySourceKind.PLATFORM_DOC
import com.example.bluetoothtest.PolicySourceKind.VENDOR_DOC
import com.example.bluetoothtest.PolicySourceStatus.OFFICIAL
import com.example.bluetoothtest.PolicySourceStatus.PARTIAL

/**
 * Device-family entries of batch G (GATT links and capacity). The list joins the registry through
 * [BluetoothTransportPolicy.entries] (same area), so the aggregate in DevicePolicyRegistry needs no extra line.
 * Never read DevicePolicyRegistry.entries from here (initialisation order). Every active entry has the off switch
 * `test_device_policy_off=<id>`; proposals are inert and only reported when they match. Matchers use the SDK only:
 * the stack defects are keyed by AOSP era, the runtime guards by behaviour (R-01's patch matcher would narrow the
 * Android 13/14 windows; until it lands an entry also matches fixed builds, where its guard never fires).
 *
 * Research 2026-10-14: device-family backlog batch G (BACKLOG G-01..G-15), catalogues bluetooth.md, oem-bt.md,
 * app-trackers.md, ble-library-trackers.md.
 */
internal object GattLinkPolicy {
    private const val REVIEWED = "2026-10-14"
    private const val BT = "https://android.googlesource.com/platform/packages/modules/Bluetooth/+"
    private const val A14 = "$BT/refs/heads/android14-release"
    private const val A13 = "$BT/refs/heads/android13-release"
    private const val A15 = "$BT/refs/heads/android15-release"
    private const val A16 = "$BT/refs/heads/android16-release"
    private const val A10_BT = "https://android.googlesource.com/platform/system/bt/+/refs/heads/android10-release"
    private const val NORDIC_BLE = "https://github.com/NordicSemiconductor/Android-BLE-Library"

    val LINK_SLOT_LIMIT = DevicePolicyEntry(
        id = "gatt-link-slot-limit",
        title = "Phone-wide LE link limit: 7 up to Android 14, a system property from Android 15",
        matcher = DeviceMatcher(),
        effects = listOf(GattLinkSlotLimit(legacyCeiling = GattLinkBudget.LEGACY_CEILING, parentReserve = GattLinkBudget.PARENT_RESERVE)),
        behaviour = "Logging on every phone: the link budget line shows the stack's limit (7 links up to Android 14; " +
            "bluetooth.core.le.max_number_of_concurrent_connections clamped to 8..16 from Android 15), the links the stack " +
            "reports and MeshLive's own, and how many child slots remain after one slot reserved for the upstream parent. " +
            "A fan-out or relay child past the budget can be refused as \"relay full\" before the stack strands it; that " +
            "refusal is Experimental (test_link_budget_admission, default off) because watches and earbuds hold links " +
            "the app cannot tell from its own. The first unexplained drop of an existing link lowers the budget for the run. " +
            "The line also names the other hard ceilings by SDK: ACL links 13 (Android 8-12) / 16, L2CAP channels 32 / 64 " +
            "(Android 14), 32 GATT clients, 7 BTA DM peers up to Android 12, 128 LE PSMs, 30 RFCOMM ports.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14/system/internal_include/bt_target.h#535",
                "GATT_MAX_PHY_CHANNEL 7 (android10 line 664, android12 588, android14 535)"),
            PolicySource(AOSP_SOURCE, "$A15/system/stack/gatt/gatt_utils.cc#109",
                "gatt_get_max_phy_channel(): the property, floor 8, ceiling 16"),
            PolicySource(AOSP_SOURCE, "$A14/system/stack/gatt/gatt_main.cc#461",
                "outgoing (461-465) and incoming (541-545) connections fail when no transport block is free"),
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/system/libsysprop/+/80600fd6758989c8035b9c825281d6cfb67eaf2f",
                "adds bluetooth.core.le.max_number_of_concurrent_connections (2024-02-07, b/283344957)"),
            PolicySource(AOSP_SOURCE, "$A14/system/internal_include/bt_target.h#389",
                "MAX_L2CAP_LINKS 16 (13 on Android 8-12, bt_target.h:425-428), MAX_L2CAP_CHANNELS 64 on Android 14 (32 before)"),
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/system/bt/+/refs/heads/android12-release/bta/dm/bta_dm_int.h#170",
                "BTA_DM_NUM_PEER_DEVICE 7: the eighth simultaneous ACL peer gets no DM record (Android 12 and earlier)"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no saved run records a phone-wide link count; the budget line added by this entry is the first record"),
        confidence = HIGH,
        confidenceNote = "The stack limit is in AOSP source. A controller may impose a lower one (only phone tests show it), and " +
            "getConnectedDevices(GATT) misses CoC-only and native-profile links, so the count is a lower bound.",
        reviewed = REVIEWED,
        origin = "Backlog G-01: child caps were per-source constants and measured LinkCapacity, never phone-wide link counts.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Child caps stay the per-source constants and LinkCapacity; no phone-wide count is logged.",
    )

    val REGISTRATION_POOL_EXHAUSTION = DevicePolicyEntry(
        id = "gatt-registration-pool-exhaustion",
        title = "GATT registration pool (32) and client pool (22) are phone-wide",
        matcher = DeviceMatcher(),
        effects = listOf(GattPoolExhaustionGuard(registrationPool = 32, clientPool = 22)),
        behaviour = "MeshLive counts the BluetoothGatt, BluetoothGattServer and scanner objects it holds per owner, and " +
            "classifies a connect that fails with status 257 as \"registration pool exhausted\" and 128 as \"client pool " +
            "exhausted\". Such a failure is not retried in a tight loop: one retry follows only after idle, unused " +
            "clients of this app were closed; then the failure is shown. Six fresh-connect failures across two peers that " +
            "were in range stop new attempts and show restart-Bluetooth guidance.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A10_BT/btif/src/btif_ble_scanner.cc#208",
                "on Android 10-12 a scanner registers a GATT app: scanners share the pool"),
            PolicySource(AOSP_SOURCE, "$A10_BT/bta/gatt/bta_gattc_int.h#71",
                "BTA_GATTC_CL_MAX 32; GATT_MAX_APPS 32 in bt_target.h:655, two used by the stack"),
            PolicySource(AOSP_SOURCE, "$A14/framework/java/android/bluetooth/BluetoothGatt.java#225",
                "a failed onClientRegistered is surfaced as GATT_FAILURE (257) in onConnectionStateChange"),
            PolicySource(AOSP_SOURCE, "$A14/system/bta/gatt/bta_gattc_act.cc#277",
                "bta_gattc_process_api_open reports GATT_NO_RESOURCES (128) when no client block is free"),
            PolicySource(AOSP_COMMIT, "$BT/b6e2ac00442fc4f6cc6e295feff53a741973af8d",
                "SDK 35+ raises the client pool to GATT_MAX_PHY_CHANNEL * BTA_GATTC_CL_MAX (b/325438660)"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no saved run shows status 257 or 128; the registry line makes a leak visible when it happens"),
        confidence = HIGH,
        confidenceNote = "Pools and statuses are AOSP source. How many slots other apps (GMS, OEM wearables) take varies per phone and is unmeasured.",
        reviewed = REVIEWED,
        origin = "Backlog G-02: GATT objects were not counted; a leaked client kept its slot until process death.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Status 257/128 fails the connect as any other status; no registry counts.",
    )

    val CLOSE_BEFORE_REGISTRATION = DevicePolicyEntry(
        id = "gatt-close-before-registration-leak",
        title = "Closing a BluetoothGatt before its registration finished leaks the client (SDK 34 and lower)",
        matcher = DeviceMatcher(sdkMax = 34),
        effects = listOf(GattCloseAfterRegistration(maxWaitMs = GattClientTeardown.REGISTRATION_WAIT_MS)),
        behaviour = "A GATT client that has not delivered any callback yet is disconnected at once but closed only when " +
            "its first callback arrives or after 2 s, whichever is first, so the stack's registration completes before the " +
            "close. Closing earlier (an attempt deadline firing during registration) leaves the registration behind and " +
            "repeated early cancels drain the 32-slot pool. A client with a callback seen closes as before.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT/f036938ce871fb171ee8f7f57bc03c17baa75f74",
                "\"If BluetoothGatt#close is called before onClientRegistered, ensure unregisterClient is called once mClientIf is set\" " +
                    "(2023-09-14, b/295587623, android14-qpr2)"),
            PolicySource(AOSP_SOURCE, "$A14/framework/java/android/bluetooth/BluetoothGatt.java#225",
                "mClientIf is set by onClientRegistered; close() before it unregisters nothing"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no saved run shows an early-close leak; frequency depends on how fast MeshLive deadlines fire"),
        confidence = HIGH,
        confidenceNote = "Defect and fix are documented; builds carrying the fix (android14-qpr2 and later) only pay a bounded wait that never fires after a callback.",
        reviewed = REVIEWED,
        origin = "Backlog G-02: DeferredGattClientClose only waits for a disconnect on Android 10/11.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The client is closed immediately after disconnect() on Android 12+, after the disconnect callback on Android 10/11.",
        legacySwitches = listOf("test_gatt_close_deferral=false"),
    )

    val CLIENT_WEDGED_AFTER_BUSY = DevicePolicyEntry(
        id = "android13-14-gatt-client-wedged-after-busy",
        title = "Android 14 and earlier: a BluetoothGatt that returned busy once can stay busy for ever (Experimental arm: applied only with test_gatt_busy_wedge=recover)",
        matcher = DeviceMatcher(sdkMax = 34),
        effects = listOf(GattClientWedgeRecovery(waitForOtherHolderMs = GattWriteBusyPolicy.OTHER_HOLDER_WAIT_MS, stepMs = GattWriteBusyPolicy.STEP_MS)),
        behaviour = "MeshLive tracks its own outstanding write on each GATT client. A busy answer (201 on Android 13-14, a bare " +
            "false before API 33) while it has none in flight is not its own flag: the framework already retried 5 x 10 ms, so the client object is stuck " +
            "(mDeviceBusy never cleared, or the per-peer write permit leaked by a client that disconnected mid-write). " +
            "The 2 ms x 500 ms spin cannot succeed then. The link is ended at once with a distinct failure, the client is " +
            "closed, and the next connect to that peer waits for the ACL to drop (up to 2 s) so a new client does not inherit " +
            "the stuck permit. When the registry shows another local client to the same peer (a real second holder of the " +
            "permit) the write is still retried for up to 500 ms, now every 25 ms. ROOT REVIEW: this ends a link on ONE busy answer on " +
            "SDK 33-34 without phone evidence, so it is applied only with test_gatt_busy_wedge=recover (DEBUG, Experimental); " +
            "the shipped default is legacy, the old 2 ms spin.",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT/cf66648aa272d1c5df713885985df48da52cdd8f",
                "BluetoothGatt: reset mDeviceBusy when the service refuses a write (2024-01-30, b/322580271; android14-qpr3)"),
            PolicySource(AOSP_SOURCE, "$A14/framework/java/android/bluetooth/BluetoothGatt.java#1433",
                "writeCharacteristic: WRITE_CHARACTERISTIC_MAX_RETRIES 5 x 10 ms while BUSY; mDeviceBusy reset only on Timeout/RemoteException"),
            PolicySource(AOSP_SOURCE, "$A13/framework/java/android/bluetooth/BluetoothGatt.java#1356",
                "android13-release writeCharacteristic: the same flag handling"),
            PolicySource(AOSP_COMMIT, "$BT/67158cb9579e1e9957eb3007094a245fed7b985d",
                "gatt: release the write permit when the owning client disconnects (2022-08-17, android13-qpr1)"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothStatusCodes#ERROR_GATT_WRITE_REQUEST_BUSY",
                "API 33: busy answer for a write while another is in flight"),
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android12-release/src/com/android/bluetooth/gatt/GattService.java#2874",
                "Android 12 and earlier: the oneway write returns silently with no callback (no connection, failed permission check), so mDeviceBusy stays true for the object's life; " +
                    "fixed by flag gatt_fix_device_busy (04f2040e86, launched; in android14-qpr3, android15)"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no 'write queue rejected' line in saved runs; upstream-documented, not locally reproduced"),
        confidence = HIGH,
        confidenceNote = "Code read on both branches and, from aosp-bt-versions GATT-02, on Android 12 and earlier. Whether a given Android 13/14 build carries the fix depends on its base (R-01 will narrow the matcher by patch); on fixed builds the guard never fires.",
        reviewed = REVIEWED,
        origin = "Backlog G-03 (WRONG): BluetoothTransportPolicy gatt-write-busy-retry spun 2 ms for 500 ms on a stuck client.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The write is retried every 2 ms for up to 500 ms, then the link fails (the registry's gatt-write-busy-retry, the shipped default).",
        legacySwitches = listOf("test_gatt_busy_wedge=legacy"),
        arm = PolicyArm("test_gatt_busy_wedge=recover") { GattSwitches.busyWedgeRecovery },
    )

    val STALE_CONNECTED_CALLBACK = DevicePolicyEntry(
        id = "gatt-stale-connected-callback",
        title = "A CONNECTED callback can arrive after the client was cancelled",
        matcher = DeviceMatcher(),
        effects = listOf(GattStaleConnectedCleanup),
        behaviour = "When a CONNECTED callback arrives for a client MeshLive already cancelled, the client is disconnected and " +
            "closed (once; registry-checked) and the callback is otherwise ignored, so a cancelled attempt cannot leave a " +
            "live BluetoothGatt and its registration behind. It fires only on that stale callback.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/43",
                "Android can deliver STATE_CONNECTED after the app disconnected (Pixel, Android 7.1.2); close() and ignore"),
            PolicySource(VENDOR_DOC, "https://github.com/meshtastic/Meshtastic-Android/issues/3009",
                "Kable cancel race: the connect \"may never start, leaking BluetoothGatt\" (status 133); close on cancel"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: not observed in saved MeshLive runs; library field reports only"),
        confidence = MEDIUM,
        confidenceNote = "Library-level evidence on other Android versions; MeshLive's own teardown already closes the client, so the check usually finds nothing to do.",
        reviewed = REVIEWED,
        origin = "Backlog G-07: callbacks after cancel were ignored without checking that the client was closed.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "A callback after cancel is ignored.",
    )

    val SILENT_LINK_DETECTOR = DevicePolicyEntry(
        id = "gatt-link-silence-detector",
        title = "Connected link whose peer never answers ATT: classify, log with the phone fingerprint",
        matcher = DeviceMatcher(),
        effects = listOf(GattLinkSilenceDetector(remoteFullAfterMs = LinkSilence.REMOTE_FULL_AFTER_MS)),
        behaviour = "One classifier watches the setup stages every GATT open already goes through (MTU, discovery, " +
            "subscription). A link that is connected and gets no ATT answer to its first request within 2 s is logged as " +
            "\"remote GATT full\" (an incoming link kept without GATT resources); a later stage past its limit as a stage " +
            "hang. Each line carries SDK, security patch, manufacturer, SoC and skin so an Android 15/16 stock regression " +
            "separates from an OEM stack. Each line names the supervision timeout the last priority request armed (5 s on " +
            "Android 10+), so a status 8 after 5.0 s of silence reads as that timer; a failed connect is classified by its " +
            "duration (about 30 s = stack timeout, under 1.5 s = HCI 0x3E). Statuses 129, 133 and 8 are marked as needing a full teardown and wait. Logging " +
            "only: failing the link at 2 s is Experimental (test_gatt_remote_full_failfast) and the settle delay before the " +
            "first operation is Experimental (test_gatt_settle_ms).",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT/c77ceb7f3026a06ea09c89eeb62ce0a3c493e506",
                "an out-of-resources incoming connection kept connected with no ATT bearer; fixed in android14-qpr3 (2024-02-19)"),
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/448",
                "Android 13: connect and MTU succeed, then discoverServices never completes (OnePlus 8T/10 Pro, Samsung S22)"),
            PolicySource(VENDOR_DOC, "https://github.com/skalldri/rgb-sunglasses/issues/115",
                "OxygenOS stack hangs at ATT MTU 23 after a GATT layout change (Service Changed not honoured)"),
            PolicySource(VENDOR_DOC, "https://codeberg.org/Freeyourgadget/Gadgetbridge",
                "service/btle: STATE_CONNECTED is posted before encryption and Service Changed; settle 300 ms (1000 ms bonded on Android 15+); " +
                    "0x81/0x85/0x08 force a full disconnect and wait (secondary: read through the research catalogue)"),
            PolicySource(AOSP_COMMIT, "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/d32b2d461",
                "Lower the BLE connection supervision timeout to 5s (2017-03-21): requestConnectionPriority sends 5 s on Android 10+ " +
                    "(GattService connectionParameterUpdate timeout = 500; 20 s on Android 8-9), so a 5.0 s silence ends in HCI 0x08"),
            PolicySource(AOSP_COMMIT, "$BT/3615d8fa04",
                "Add GATT_CONNECTION_TIMEOUT and change connection timeout flow (2024-01-19): a direct connect ends after 30 s with 133, or 147 from Android 14 QPR3"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no saved MeshLive run shows a silent connected link; the 2 s window is the backlog's starting value"),
        confidence = MEDIUM,
        confidenceNote = "The mechanism is AOSP; the OEM hang reports are one-project symptoms with LOW cause confidence; thresholds are unmeasured on the six phones.",
        reviewed = REVIEWED,
        origin = "Backlog G-05: awaitGattSetup and the Q29 deadlines close silent strangers but name no cause and keep no fingerprint.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "Silent links end at the 12 s attempt deadline or the 3 s MTU watchdog, unclassified.",
    )

    val ONE_CLIENT_PER_REMOTE = DevicePolicyEntry(
        id = "gatt-one-client-per-remote",
        title = "Several BluetoothGatt objects to one remote, or a pair connected in both directions, hit stack bugs fixed in Android 15 QPR1+",
        matcher = DeviceMatcher(sdkMax = 35),
        effects = listOf(GattDialCheck),
        behaviour = "Diagnostics before every GATT dial: the log names a second live client to the same remote (discovery " +
            "stalls when the client that read the database hash closes; the accept-list entry removed by one client's " +
            "cancel; ContextMap removal of another client's record) and a link that already exists in the other direction " +
            "(server responses mis-multiplexed, ATT bearer blocked, ACL dropped after the 30 s ATT timeout). The write-busy " +
            "guard also treats a second local client as a legitimate holder of the per-peer write permit. The dial is not " +
            "refused: avoiding the situation is the callers' rule (one client per remote, no simultaneous crossed dials).",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT/be38871d69",
                "Retry reading db hash if canceled: other clients stuck in DISCOVER when the reading client closes (2024-04-16; android15+)"),
            PolicySource(AOSP_COMMIT, "$BT/38a128a7dc",
                "Remove device only upon the last cancellation of a pending direct connect (2024-06-25; android15-qpr1+)"),
            PolicySource(AOSP_COMMIT, "$BT/7a49fd0b08",
                "ContextMap.removeConnection used the wrong app id: closing one client could remove another's record (2024-03-12; android15+)"),
            PolicySource(AOSP_COMMIT, "$BT/4ffd870f18",
                "GattService: Fix server requests - GATT connected in both directions, mServerMap with two connIds for one address (2024-08-21; android15-qpr1+)"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: no saved run records a second client or a crossed link to one remote; the dial-check line is the first record"),
        confidence = HIGH,
        confidenceNote = "Commit messages are primary; how often MeshLive's probes, control and media clients overlap on one address is unmeasured.",
        reviewed = REVIEWED,
        origin = "Backlog G-02 plus aosp-bt-versions GATT-07/GATT-09 (root note 2026-10-14).",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No dial check line.",
    )

    val BLUETOOTH_AUDIO_AIRTIME = DevicePolicyEntry(
        id = "bluetooth-audio-airtime-coexistence",
        title = "Bluetooth audio (A2DP, SCO, LE Audio) takes airtime from LE links",
        matcher = DeviceMatcher(),
        effects = listOf(BluetoothAudioCoexistenceDiagnostics),
        behaviour = "Diagnostics on every phone: while a Bluetooth audio output exists the link lines and the device policy " +
            "line carry btAudio=<a2dp|sco|le_audio>, and the app can show advice (use the phone speaker or wired " +
            "headphones). Nothing is reduced by default; keeping learned capacity apart (test_bt_audio_capacity_key) and " +
            "offering less load (test_bt_audio_load_cut) are Experimental.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A14/system/bta/av/bta_av_act.cc#1101",
                "bta_av_stream_chg: L2CA_SetAclPriority(HIGH) when A2DP streams"),
            PolicySource(AOSP_SOURCE, "$A14/system/stack/l2cap/l2c_utils.cc",
                "l2cu_set_acl_priority: vendor priority command on Broadcom, Synaptics and Unisoc controllers"),
            PolicySource(PLATFORM_DOC, "https://developer.android.com/reference/android/media/AudioDeviceInfo#TYPE_BLUETOOTH_A2DP",
                "output device types for A2DP, SCO and (API 31+) BLE headset"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no saved run has Bluetooth audio active; the 2.4 GHz Wi-Fi halving (WifiCoexistence) is the measured analogue"),
        confidence = MEDIUM,
        confidenceNote = "The priority mechanism is AOSP source for three controller vendors; the size of the loss on the MeshLive phones is unmeasured.",
        reviewed = REVIEWED,
        origin = "Backlog G-10: WifiCoexistence exists for Wi-Fi only.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "No Bluetooth audio field in the diagnostics.",
    )

    // --- Proposals and Experimental arms: sourced, inert in the registry; the switch is the arm ------------------

    val SERVER_ONLY_LINK_IDLE_DROP = DevicePolicyEntry(
        id = "server-only-link-idle-drop",
        title = "Android 13 QPR2+ / 14: a link used only as GATT server can be dropped after the central's EATT or CoC closes",
        matcher = DeviceMatcher(sdkMin = 33, sdkMax = 34),
        effects = emptyList(),
        behaviour = "Experimental (test_gatt_server_hold=true, default off): after a viewer or child is admitted, " +
            "BluetoothGattServer.connect(device, false) marks the link in use by this app; the server's cancelConnection " +
            "at teardown releases it. The wait for the ACL to drop before a redial is the active part (see " +
            "android13-14-gatt-client-wedged-after-busy and test_gatt_acl_wait).",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT/545bc4e42721f35203ffa2f7d9b56aab4465445e",
                "exclude EATT channels from the dynamic-channel idle rule (2023-07-25, b/267635511; android14-qpr2)"),
            PolicySource(AOSP_COMMIT, "$BT/72689620f643da89fad1af24ad1fe444229672f6",
                "Reduce behavior change with LE ACL timeout on T"),
            PolicySource(AOSP_COMMIT, "$BT/c24851b3e4",
                "Fix infinite L2CAP timeout when ATT is used without GATT clients (2022-10-24, b/251954527): the ACL drops about 1 s after the last client or dynamic channel leaves (android13-qpr2+)"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothGattServer#connect(android.bluetooth.BluetoothDevice,%20boolean)",
                "connect(device, false): direct connection / mark the link in use by the server"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: the M31 -> S24 snoop in le-coc-eatt-stranded-channels shows Android 12 centrals try EATT; the hold's effect is unmeasured"),
        confidence = MEDIUM,
        confidenceNote = "The defect window is AOSP; that MeshLive links trigger it, and that the hold prevents it, are not shown.",
        reviewed = REVIEWED,
        origin = "Backlog G-04 (b): relay upstream links are server-only on the relay.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The server keeps the link by its own traffic; no hold call.",
    )

    val ATT_PAYLOAD_LL_PACKING = DevicePolicyEntry(
        id = "att-payload-ll-packing",
        title = "ATT values of 244 or 495 bytes fill whole 251-octet LE data PDUs",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "Experimental (test_gatt_payload=244|495|default, default off): the GATT value is one or two full LL " +
            "PDUs (244 = 1, 495 = 2) and never above 512; MTU stays 517. test_gatt_aligned_writes is the older alias of 495.",
        sources = listOf(
            PolicySource(PolicySourceKind.BLUETOOTH_SPEC, "https://www.bluetooth.com/specifications/specs/core-specification-5-4/",
                "Core Vol 6 Part B 4.5.10: 251-octet data PDU with data length extension; a PDU never carries parts of two L2CAP PDUs"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: the aligned-write A/B has no retained result for the 244 value"),
        confidence = LOW,
        confidenceNote = "Arithmetic of PDU packing is spec-based; the gain depends on the controller and is unmeasured on the six phones.",
        reviewed = REVIEWED,
        origin = "Backlog G-09 (L G9/G10, C C-24).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "ATT value = MTU - 3 up to 512.",
    )

    val ANDROID12_ACL_SEGMENT_MISCOUNT = DevicePolicyEntry(
        id = "android12-le-acl-segment-miscount",
        title = "Android 12: LE ACL segments are miscounted for L2CAP PDUs just over a buffer multiple",
        matcher = DeviceMatcher(sdkMin = 31, sdkMax = 32),
        effects = emptyList(),
        behaviour = "Experimental (test_gatt_payload=244, default off): on SDK 31-32 an ATT value of 244 bytes keeps every L2CAP " +
            "PDU in one 251-octet buffer, away from the lengths the miscount affects (252-255 and 503-510 octets).",
        sources = listOf(
            PolicySource(AOSP_COMMIT, "$BT/ab2c1e84fc027872b2457bc3080555dd72b66524",
                "l2c_link: count LE segments from the data size, not the buffer size incl. the HCI header (b/201372527, android13)"),
            PolicySource(AOSP_SOURCE, "https://android.googlesource.com/platform/system/bt/+/refs/heads/android12-release/stack/l2cap/l2c_link.cc#1097",
                "android12-release l2c_link_send_to_lower_ble computes num_segs from the wrong size"),
        ),
        sourceStatus = OFFICIAL,
        evidence = listOf("none: the M31 (Android 12) hardware-VP9 GATT freezes are a candidate, not attributed to this defect"),
        confidence = MEDIUM,
        confidenceNote = "The miscount is in source; whether it causes visible loss on a given OEM build is unproven.",
        reviewed = REVIEWED,
        origin = "Backlog G-09: M31 candidate for GATT video freezes.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "The default value size is used.",
    )

    val DISCOVERY_HANG_OEM_ANDROID13 = DevicePolicyEntry(
        id = "gatt-discovery-hang-oneplus-samsung-android13",
        title = "Android 13 OnePlus and Samsung S22: connect and MTU succeed, service discovery never completes",
        matcher = DeviceMatcher(sdkMin = 33, sdkMax = 33, manufacturers = setOf("oneplus", "samsung")),
        effects = emptyList(),
        behaviour = "Proposal (diagnostic): the link-silence detector's stage-hang line is the evidence; a recovery that closes, " +
            "waits for the settle delay and reconnects without 2M PHY is the ladder the detector names. No behaviour change.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/448",
                "OnePlus 8T (KB2001), OnePlus 10 Pro (NE2211), Samsung S22/S22 Ultra on Android 13; Google issue 242755161 (not readable)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: not seen on the S22 in saved MeshLive runs"),
        confidence = LOW,
        confidenceNote = "Symptom MEDIUM-HIGH on those phones, cause LOW; a single library thread.",
        reviewed = REVIEWED,
        origin = "Backlog G-05 (O S2-06).",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.UPSTREAM_DOCUMENTED,
        fallback = "A hung discovery ends at the attempt deadline.",
    )

    val CONNECT_GATT_ON_MAIN = DevicePolicyEntry(
        id = "gatt-connect-on-main-looper",
        title = "connectGatt on the main looper (Huawei, Samsung reports)",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "Experimental (test_connect_gatt_main=true, default off): GattConnectCompat posts connectGatt to the main " +
            "looper and the calling worker waits for it (5 s bound); the log names the arm that ran.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "$NORDIC_BLE/issues/159",
                "Huawei P20 Lite (Android 9): onConnectionStateChange never fires unless connectGatt runs on the main thread"),
            PolicySource(VENDOR_DOC, "https://codeberg.org/Freeyourgadget/Gadgetbridge",
                "BtLEQueue: \"main thread appears to fix Samsung\" (secondary, through the research catalogue)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none: no MeshLive phone shows the symptom; the A/B is for Samsung pair connect success and latency"),
        confidence = LOW,
        confidenceNote = "Old Huawei report plus one library comment; the cost is main-thread jank when the main thread is busy.",
        reviewed = REVIEWED,
        origin = "Backlog G-06: connectGatt ran on worker threads at three call sites.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "connectGatt runs on the calling worker thread.",
    )

    val GATT_CACHE_REFRESH = DevicePolicyEntry(
        id = "gatt-refresh-before-discovery",
        title = "Hidden BluetoothGatt.refresh() before service discovery",
        matcher = DeviceMatcher(),
        effects = emptyList(),
        behaviour = "Experimental (test_gatt_refresh=true, default off): refresh() is called by reflection before the first " +
            "discovery and logged with the SDK; it fails closed when the hidden API is blocked. The existing refresh when a " +
            "service is missing is unchanged. The MeshLive GATT layout stays append-only across versions.",
        sources = listOf(
            PolicySource(VENDOR_DOC, "https://github.com/skalldri/rgb-sunglasses/issues/115",
                "OxygenOS does not honour Service Changed: stale cache; refresh on/off did not rescue in that report"),
            PolicySource(VENDOR_DOC, "https://github.com/meshtastic/Meshtastic-Android/issues/3009",
                "Kable KableGattCacheRefresh logs the SDK level when refresh() stops working"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf("none"),
        confidence = LOW,
        confidenceNote = "MeshLive peers are unbonded and the layout changes only with app updates; no case is known.",
        reviewed = REVIEWED,
        origin = "Backlog G-15.",
        proposed = true,
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.EXPERIMENTAL,
        fallback = "The service cache is refreshed only when a service is missing.",
    )

    private const val A11_BT_APP = "https://android.googlesource.com/platform/packages/apps/Bluetooth/+/refs/heads/android11-release"

    val SERVER_CONNECTION_UNTRACKED = DevicePolicyEntry(
        id = "android11-gatt-server-connection-untracked",
        title = "Android 10-11: a GATT server's connection can be missing from the stack's server map (no answers to that client)",
        matcher = DeviceMatcher(stackLineages = setOf(BluetoothStackLineage.LEGACY_29_30)),
        effects = listOf(
            GattServerConnectionWatch(timeoutMs = GattServerConnectionWatch.DEFAULT_TIMEOUT_MS),
            GattServerFirstWriteWatch(timeoutMs = GattServerFirstWriteWatch.DEFAULT_TIMEOUT_MS),
            GattServerReregisterKeepsAddress,
        ),
        behaviour = "After a client connects to the advertising call listener, the server reads the link's PHY; a healthy stack " +
            "answers in 5-10 ms. When no PHY answer comes within 400 ms the stack has no record of this connection for the " +
            "server, so every response would go to connection 0 and the client's subscribe would hang to its timeout: the " +
            "server logs gatt_server_connection_untracked, cancels the connection and registers itself again (close, open, " +
            "add the service, advertise). The client's retry then reaches a freshly registered server. Only the advertising " +
            "listener is watched (the dual-listening second server also hears links it does not own). Newer stacks may never " +
            "call back a PHY read (AdaptivePhy's Android 13 backstop), so the watch is limited to the legacy stack. Second " +
            "stage: after the client's subscribe is accepted, its first data write must reach the app within 1.2 s (healthy " +
            "first writes arrive 70-140 ms later); otherwise the server logs gatt_server_first_write_missing, ends that " +
            "connection and registers again. A connection that has delivered any data is never cut. A re-registration replaces " +
            "only the GATT server: the advertising set is a separate stack object, so a running set keeps advertising with " +
            "the same address and the client's retry finds it at once; to keep it for stage 2 the set stays on after the " +
            "subscribe until the first data write (at most the 1.2 s watch) instead of stopping at the subscribe. A " +
            "rotating-ID epoch change always stops the set, so an address never carries into a new epoch.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A11_BT_APP/src/com/android/bluetooth/gatt/GattService.java#2763",
                "onClientConnected: the connection is added to mServerMap only when getById(serverIf) finds the server"),
            PolicySource(AOSP_SOURCE, "$A11_BT_APP/src/com/android/bluetooth/gatt/GattService.java#3027",
                "serverReadPhy: connIdByAddress(serverIf, address) null -> 'no connection to <address>', nothing is read"),
            PolicySource(AOSP_SOURCE, "$A11_BT_APP/src/com/android/bluetooth/gatt/GattService.java#3105",
                "sendResponse: a missing connection is sent as connId 0 (the stack then rejects it: gatt_if out of range)"),
            PolicySource(AOSP_SOURCE, "$A11_BT_APP/src/com/android/bluetooth/gatt/GattService.java#2830",
                "onServerWriteCharacteristic: a write that reached the stack is dropped silently when mHandleMap.getByHandle or " +
                    "mServerMap.getById finds no entry (stage 2: the first handshake write never reaches the app)"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "benchmarks/runs/edgecases-20261020 (asus-s22-fb0e, s22-asus-r3..r6, asus-s22-r3..r6) and " +
                "benchmarks/runs/longcall-035b5235-20261020 (lc-s22-asus-r3..r5), ASUS (Android 11, legacy stack) called by the " +
                "S22: 16 of 16 failed first connects reached a server idle for more " +
                "than 10 s: 'serverReadPhy() - no connection to <addr>' 8 ms after onClientConnected, then 'address not found " +
                "for given connId' and a failed CCCD sendResponse (gatt_if out of range = 0); the client's subscribe hung to its " +
                "12 s timeout. 16 of 16 retries on freshly registered servers worked. No failure before about 02:17; the onset " +
                "followed a rising count of other apps' GATT registrations without callbacks (5 to 9).",
            "benchmarks/runs/edgecases-20261020/asus-a15s-m31-055022 step 2 (A15s calling the ASUS back, server registered 16 s " +
                "earlier): the PHY read was answered and the subscribe acknowledged; the A15s's first 133-byte handshake write " +
                "reached the ASUS stack (gatt_data_process op_code=82) but never the app (bmh3 responder EOFException after " +
                "4692 ms); the A15s's setup-stall guard closed at 3.0 s, and the retry after rediscovery worked in 211 ms.",
        ),
        confidence = MEDIUM,
        confidenceNote = "Symptom and recovery measured 16/16 on one Android 11 phone; why the stack loses the record is not " +
            "proven (the server map lookup is the documented path). Other legacy-stack phones: not measured.",
        reviewed = "2026-10-21",
        origin = "Offline analysis of the S22-ASUS edge-case and long-call runs, 2026-10-21.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        fallback = "The client's subscribe waits for its 12 s timeout and retries; the retry reaches a fresh server only " +
            "when the listener was rebuilt for another reason.",
    )

    /**
     * Q-MTK (owner approved 2026-10-27): a MediaTek phone relaying a call over GATT corrupts single bytes inside queued
     * notifications; helper duty stays on LE CoC. PLAN keeps the protocol fix (per-chunk integrity + a GR1 re-request).
     */
    val MEDIATEK_GATT_RELAY_HELPER = DevicePolicyEntry(
        id = "mediatek-call-relay-gatt-helper-off",
        title = "MediaTek: call-relay helper duty over LE CoC only, never over GATT",
        matcher = DeviceMatcher(socFamilies = setOf(SocFamily.MEDIATEK)),
        effects = listOf(CallRelayHelperCocOnly),
        behaviour = "A MediaTek phone (Build.SOC_MANUFACTURER MediaTek on API 31+, else Build.HARDWARE mt<digits>) whose " +
            "call endpoint is GATT does not help with other people's calls: its call offer carries no helper flag, its " +
            "link hello signs no relay mark, and a SETUP or route search arriving over GATT is refused. Callers then " +
            "pick another helper for a GATT route. Over LE CoC it helps as before; its own direct calls over GATT are " +
            "unchanged. The Settings status line reads \"Helping: paused (CoC only on this phone)\".",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A10_BT/btif/src/btif_gatt_server.cc",
                "Android 10: a notification's onNotificationSent is reported when the value is queued in the stack, not " +
                    "when the controller sent it, so the app cannot see what a vendor controller does with queued data"),
            PolicySource(VENDOR_DOC, "https://github.com/zephyrproject-rtos/zephyr/security/advisories",
                "Analogue only, not a MediaTek document: a Zephyr Bluetooth host advisory on reassembly shared between " +
                    "connections, the class of defect that only a phone holding two links at once (a relay) exercises"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "benchmarks/runs/relay-showcase-20261026/trace-u* (A15s MT6765 Android 10 as GATT middle)",
            "benchmarks/runs/relay-showcase-20261026/trace-v* (control and test_gatt_byte_window=3072 + kbps 120)",
            "benchmarks/runs/relay-showcase-20261026/trace-w* (test_gatt_payload=244)",
        ),
        confidence = LOW,
        confidenceNote = "Local phones only, no MediaTek document: the A15s as GATT middle held 0/8 in every arm (control, " +
            "test_gatt_byte_window=3072 with kbps 120, test_gatt_payload=244); the M31's HCI log showed contiguous chunks " +
            "with single bytes changed inside queued notifications. Never on single-link calls (228 A15s direct GATT calls, " +
            "0 desyncs). Xiaomi Redmi 12 (also MediaTek) 3/10 as GATT middle; non-MediaTek middles 0/72; the A15s as CoC " +
            "middle held 7/8. The family-wide matcher rests on two MediaTek phones; no data from other MediaTek phones.",
        reviewed = "2026-10-27",
        origin = "Q-MTK (2026-10-27): relayed calls through a MediaTek GATT middle broke with record desyncs.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        fallback = "The phone offers helper duty over GATT as on every other family (and its GATT relays fail as measured).",
    )

    /**
     * Realme RMX5256 lane (2026-11-06): the MediaTek Android 16 stack stops opening central connections ("No resources to
     * open a new connection", status 128) with no client registered; only a Bluetooth restart clears it.
     */
    val MEDIATEK_COLOROS_NATIVE_CLIENT_WEDGE = DevicePolicyEntry(
        id = "mediatek-coloros-native-gatt-client-wedge",
        title = "MediaTek with ColorOS or Realme UI, Android 16+: a native GATT client wedge needs a Bluetooth restart " +
            "(on by default since 2026-11-10; DEBUG control arm test_gatt_native_wedge_guard=false)",
        matcher = DeviceMatcher(
            sdkMin = 36,
            socFamilies = setOf(SocFamily.MEDIATEK),
            skins = setOf(OemSkin.COLOROS, OemSkin.REALME_UI),
        ),
        effects = listOf(
            GattNativeClientWedgeGuard(
                threshold = GattNativeClientWedgeGuard.THRESHOLD,
                windowMs = GattNativeClientWedgeGuard.WINDOW_MS,
                immediateMs = GattNativeClientWedgeGuard.IMMEDIATE_MS,
            ),
        ),
        behaviour = "On a MediaTek phone with ColorOS or Realme UI on Android 16 or newer, central GATT and LE CoC connects " +
            "that fail with status 128 (GATT_NO_RESOURCES) within 500 ms of the dial, ${GattNativeClientWedgeGuard.THRESHOLD} " +
            "in a row inside 60 s, while MeshLive holds no GATT client, are read as a native client wedge: the reliability " +
            "card shows the Bluetooth off/on guidance at once (the generic path needs three back-off steps 1-4 s apart) " +
            "and one BENCHMARK gatt_native_client_wedge line is logged. A successful central connect clears it. Nothing " +
            "is toggled (apps cannot restart Bluetooth on Android 13+); the peripheral role is untouched.",
        sources = listOf(
            PolicySource(AOSP_SOURCE, "$A16/system/bta/gatt/bta_gattc_act.cc",
                "bta_gattc_process_api_open logs \"No resources to open a new connection.\" and reports GATT_NO_RESOURCES " +
                    "(128) to the app when bta_gattc_find_alloc_clcb returns null"),
            PolicySource(AOSP_SOURCE, "$A16/system/bta/gatt/bta_gattc_utils.cc",
                "bta_gattc_clcb_alloc fails when the client registration lookup (cl_rcb_map) or the server-cache " +
                    "allocation fails; bta_gattc_srcb_alloc is bounded by the controller's LE filter accept list size and " +
                    "recycles only an entry that is not connected, so a leaked entry blocks every new open"),
            PolicySource(PLATFORM_DOC, "https://source.android.com/docs/core/ota/modular-system/bluetooth",
                "the Bluetooth stack is a Mainline module, updatable from Android 16: a fix can arrive without an SDK change"),
            PolicySource(API_REFERENCE, "https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#disable()",
                "deprecated in API 33: an app targeting Android 13+ cannot turn Bluetooth off, so it can only ask the user"),
        ),
        sourceStatus = PARTIAL,
        evidence = listOf(
            "benchmarks/runs/p2b-20261106 cases 0005, 0006, 0010-0012 (Realme RMX5256, Bluetooth process up about 5.3 h, " +
                "about 150 central opens): first wedge; status 128 12-61 ms after the dial, clients=0",
            "benchmarks/runs/p3g-20261106 cases 0001-0006, benchmarks/runs/rm03g-20261106 cases 0005-0008, " +
                "benchmarks/runs/rm03m2-20261106 cases 0009-0016: every Realme central connect status 128; the generic " +
                "guidance appeared in 4 of 23 app sessions",
            "benchmarks/runs/rm3xa-20261106, rm3xb-20261106, rm3y-20261106, p3g2-20261106, p4a-20261106, p4b-20261106: " +
                "after Bluetooth off/on no status 128",
            "benchmarks/runs/rm3s-20261106, rm3t1..4-20261106, rm3uoff1..15-20261106, rm3voff1..3-20261106 (120 cases on a " +
                "fresh stack): no status 128; the trigger is unknown",
        ),
        confidence = LOW,
        confidenceNote = "One phone (Realme RMX5256, MT6835, Android 16, patch 2026-08-01); the leak reproduced once after " +
            "hours of use and never in 120 soak cases, so the trigger is unknown. The AOSP code shows how status 128 " +
            "arises; no MediaTek or OPPO document names this defect. The family scope (every MediaTek phone with ColorOS or " +
            "Realme UI on Android 16+) is an assumption; Android 16 phones of other makers are unmeasured.",
        reviewed = "2026-11-06",
        origin = "Realme RMX5256 lane 2026-11-06 (ab-lanes s24-realme-REPORT Updates 6, 9-11): a stuck native stack refused " +
            "every central connect and the generic guidance rarely showed.",
        area = PolicyArea.BLUETOOTH_TRANSPORT,
        validation = PolicyValidation.LOCALLY_REPRODUCED,
        fallback = "The generic pool back-off and guidance only: the card shows after three back-off steps of client " +
            "failures, which one failed call never reaches.",
        arm = PolicyArm("test_gatt_native_wedge_guard=true") { GattSwitches.nativeClientWedgeGuard },
    )

    val entries: List<DevicePolicyEntry> = listOf(
        LINK_SLOT_LIMIT,
        REGISTRATION_POOL_EXHAUSTION,
        CLOSE_BEFORE_REGISTRATION,
        CLIENT_WEDGED_AFTER_BUSY,
        STALE_CONNECTED_CALLBACK,
        SILENT_LINK_DETECTOR,
        ONE_CLIENT_PER_REMOTE,
        BLUETOOTH_AUDIO_AIRTIME,
        SERVER_ONLY_LINK_IDLE_DROP,
        ATT_PAYLOAD_LL_PACKING,
        ANDROID12_ACL_SEGMENT_MISCOUNT,
        DISCOVERY_HANG_OEM_ANDROID13,
        CONNECT_GATT_ON_MAIN,
        GATT_CACHE_REFRESH,
        SERVER_CONNECTION_UNTRACKED,
        MEDIATEK_GATT_RELAY_HELPER,
        MEDIATEK_COLOROS_NATIVE_CLIENT_WEDGE,
    )

    private fun policy(): ResolvedDevicePolicy = DeviceFamilyPolicy.current()

    /**
     * Whether the call relay opens its downstream GATT hop with acknowledged writes: the matched family entry and its
     * Experimental arm (`test_relay_gatt_acknowledged_writes=true`, DEBUG only); a release build never does. The entry
     * stays resolvable while the arm is off (it is reported under `armOff`), so the arm is checked here.
     */
    fun relayAcknowledgedWrites(resolved: ResolvedDevicePolicy = policy()): Boolean =
        GattSwitches.relayAcknowledgedWrites && resolved.has<CallRelayAcknowledgedGattWrites>()

    fun wedgeRecovery(resolved: ResolvedDevicePolicy = policy()): GattClientWedgeRecovery? =
        resolved.effects<GattClientWedgeRecovery>().firstOrNull()

    fun closeAfterRegistration(resolved: ResolvedDevicePolicy = policy()): GattCloseAfterRegistration? =
        resolved.effects<GattCloseAfterRegistration>().firstOrNull()

    fun staleConnectedCleanup(resolved: ResolvedDevicePolicy = policy()): Boolean = resolved.has<GattStaleConnectedCleanup>()

    fun silenceDetector(resolved: ResolvedDevicePolicy = policy()): GattLinkSilenceDetector? =
        resolved.effects<GattLinkSilenceDetector>().firstOrNull()

    fun poolGuard(resolved: ResolvedDevicePolicy = policy()): GattPoolExhaustionGuard? =
        resolved.effects<GattPoolExhaustionGuard>().firstOrNull()

    fun bluetoothAudioDiagnostics(resolved: ResolvedDevicePolicy = policy()): Boolean =
        resolved.has<BluetoothAudioCoexistenceDiagnostics>()

    fun linkSlotLimit(resolved: ResolvedDevicePolicy = policy()): GattLinkSlotLimit? =
        resolved.effects<GattLinkSlotLimit>().firstOrNull()

    fun serverConnectionWatch(resolved: ResolvedDevicePolicy = policy()): GattServerConnectionWatch? =
        resolved.effects<GattServerConnectionWatch>().firstOrNull()

    fun serverFirstWriteWatch(resolved: ResolvedDevicePolicy = policy()): GattServerFirstWriteWatch? =
        resolved.effects<GattServerFirstWriteWatch>().firstOrNull()

    fun serverReregisterKeepsAddress(resolved: ResolvedDevicePolicy = policy()): Boolean =
        resolved.has<GattServerReregisterKeepsAddress>()
}

internal data object GattServerReregisterKeepsAddress : DevicePolicyEffect {
    override fun describe(): String =
        "a re-registration replaces only the GATT server and keeps the running advertising set (same address); after a " +
            "subscribe the set stays on until the first data write; a rotating-ID epoch change always stops it"
}

internal data class GattServerFirstWriteWatch(val timeoutMs: Long) : DevicePolicyEffect {
    override fun describe(): String =
        "after a client subscribed, no data write for $timeoutMs ms means the stack drops this connection's writes before " +
            "the app: cancel the connection and register the server again (never after any data arrived)"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_200L
    }
}

internal data class GattServerConnectionWatch(val timeoutMs: Long) : DevicePolicyEffect {
    override fun describe(): String =
        "after a client connects, a PHY read unanswered for $timeoutMs ms means the stack lost the server's connection record: " +
            "cancel the connection and register the server again"

    companion object {
        const val DEFAULT_TIMEOUT_MS = 400L
    }
}

internal data class GattLinkSlotLimit(val legacyCeiling: Int, val parentReserve: Int) : DevicePolicyEffect {
    override fun describe(): String =
        "phone-wide LE link budget: $legacyCeiling up to Android 14 (property 8..16 from Android 15), $parentReserve reserved for the upstream parent"
}

internal data class GattPoolExhaustionGuard(val registrationPool: Int, val clientPool: Int) : DevicePolicyEffect {
    override fun describe(): String =
        "count GATT objects per owner; classify status 257 ($registrationPool-registration pool) and 128 ($clientPool-client pool)"
}

internal data class GattCloseAfterRegistration(val maxWaitMs: Long) : DevicePolicyEffect {
    override fun describe(): String = "do not close a GATT client before its first callback; wait at most $maxWaitMs ms"
}

internal data class GattClientWedgeRecovery(val waitForOtherHolderMs: Long, val stepMs: Long) : DevicePolicyEffect {
    override fun describe(): String =
        "busy write with nothing in flight = wedged client: end the link at once; with another local holder retry every $stepMs ms up to $waitForOtherHolderMs ms"
}

internal data object GattDialCheck : DevicePolicyEffect {
    override fun describe(): String = "log a second client or a crossed connection before dialling a remote as GATT client"
}

internal data object GattStaleConnectedCleanup : DevicePolicyEffect {
    override fun describe(): String = "close a client whose CONNECTED callback arrives after it was cancelled"
}

internal data class GattLinkSilenceDetector(val remoteFullAfterMs: Long) : DevicePolicyEffect {
    override fun describe(): String = "classify a connected link with no ATT answer for $remoteFullAfterMs ms and log the phone fingerprint"
}

internal data object BluetoothAudioCoexistenceDiagnostics : DevicePolicyEffect {
    override fun describe(): String = "report an active Bluetooth audio output in the link diagnostics"
}

/** Busy-write constants shared by the registry entry and [retryBusyGattWrite]. */
internal object GattWriteBusyPolicy {
    /** The shipped retry step (default arm, `test_gatt_busy_wedge=legacy`). */
    const val LEGACY_STEP_MS = 2L

    /** The framework already retried 5 x 10 ms; 20-50 ms is enough (backlog G-03; Experimental `recover` arm only). */
    const val STEP_MS = 25L
    const val OTHER_HOLDER_WAIT_MS = 500L
}
