package com.example.bluetoothtest

import android.content.Intent

/**
 * Switches of batch G (GATT links and capacity). Defaults are the shipped behaviour. Every arm other than the
 * defaults is DEBUG-only (a launch extra from another app must not change a release build's behaviour); the
 * default-on behaviours keep their off arm so an A/B can compare them. Register of switches: SWITCHES.
 *
 * Launch extras (DEBUG): `test_gatt_busy_wedge=legacy|recover` (default legacy), `test_gatt_close_deferral=true|false`,
 * `test_gatt_pool_backoff=true|false`,
 * `test_link_budget_admission`, `test_gatt_server_hold`, `test_gatt_settle_ms=<ms>`, `test_connect_gatt_main`,
 * `test_gatt_payload=244|495|default`, `test_bt_audio_load_cut`, `test_bt_audio_capacity_key`,
 * `test_gatt_refresh`, `test_gatt_acl_wait`, `test_gatt_remote_full_failfast`, `test_gatt_byte_window=<bytes>`,
 * `test_gatt_window_kbps=<kbps>`, `test_gatt_native_wedge_guard=true|false`, `test_relay_gatt_acknowledged_writes=true|false`.
 */
internal object GattSwitches {
    /** `test_gatt_payload`: ATT value size per GATT write or notification (G-09, Experimental). */
    enum class Payload(val debugValue: String) { DEFAULT("default"), ONE_PDU("244"), TWO_PDUS("495") }

    /**
     * `test_gatt_busy_wedge` (Experimental, default `legacy`): `recover` classifies a write refused as busy while
     * MeshLive has nothing in flight on that client as a wedged client and ends the link fast (G-03), with the 25 ms retry
     * step. The root review kept `legacy` (the pre-batch 2 ms spin up to 500 ms, no wedge classification) as the default:
     * the wedge check ends a link on ONE such answer on SDK 33-34 (three of four lane phones), which is not
     * behaviour-safe without a phone A/B under load. DEBUG builds only; release always runs `legacy`.
     */
    @Volatile var busyWedgeRecovery: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /**
     * `test_gatt_close_deferral` (default true): on SDK 34 and lower a GATT client is not closed before its first
     * callback (bounded 2 s), so the stack's registration finishes first (G-02). False closes at once as before.
     */
    @Volatile var closeDeferral: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * `test_gatt_pool_backoff` (default true): automatic stream connects wait out an exponential hold (1 s to 30 s) after
     * the phone-wide registration pool refused a client registration (status 257/128), instead of re-dialling on every
     * viewer request (lane11 ASUS: about 1,500 stack errors per run). False is the control arm: no hold. The
     * `gatt_pool_exhausted` evidence lines and the reliability-card guidance do not depend on it.
     */
    @Volatile var poolBackoff: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** `test_link_budget_admission` (Experimental, default off): refuse a child past the phone-wide link budget (G-01). */
    @Volatile var linkBudgetAdmission: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_gatt_server_hold` (Experimental, default off): `BluetoothGattServer.connect(device,false)` on an admitted link (G-04b). */
    @Volatile var serverHold: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_gatt_settle_ms` (Experimental, default 0 = none): wait this long after CONNECTED before the first GATT operation (G-05). */
    @Volatile var settleMs: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, MAX_SETTLE_MS) else 0
        }

    /** `test_connect_gatt_main` (Experimental, default off): `connectGatt` runs on the main looper (G-06). */
    @Volatile var connectOnMain: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    @Volatile var payload: Payload = Payload.DEFAULT
        set(value) {
            field = if (BuildConfig.DEBUG) value else Payload.DEFAULT
        }

    /** `test_bt_audio_load_cut` (Experimental, default off): offer less load while Bluetooth audio streams (G-10). */
    @Volatile var btAudioLoadCut: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_bt_audio_capacity_key` (Experimental, default off): learned capacity is kept apart while Bluetooth audio streams (G-10). */
    @Volatile var btAudioCapacityKey: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_gatt_refresh` (Experimental, default off): hidden `BluetoothGatt.refresh()` before service discovery (G-15). */
    @Volatile var gattRefresh: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_gatt_acl_wait` (Experimental, default off): every GATT redial waits for the previous ACL to drop, up to 2 s (G-04a). */
    @Volatile var aclWaitOnRedial: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_gatt_remote_full_failfast` (Experimental, default off): a connected link with silent ATT for 2 s fails as "remote GATT full" (G-01). */
    @Volatile var remoteFullFailFast: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    /** `test_gatt_byte_window` (Experimental, default 0 = off): bytes the stack queue may hold per link (G-08). */
    @Volatile var byteWindowBytes: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, MAX_WINDOW_BYTES) else 0
        }

    /** `test_gatt_window_kbps` (Experimental, 0 = no drain model): the rate the byte window assumes the link drains. */
    @Volatile var windowDrainKbps: Int = 0
        set(value) {
            field = if (BuildConfig.DEBUG) value.coerceIn(0, MAX_WINDOW_KBPS) else 0
        }

    /**
     * `test_gatt_native_wedge_guard` (default true since 2026-11-10, owner answer BT-07 Q4; false is the control arm, DEBUG
     * only): on a phone matched by the DeviceFamilyPolicy entry `mediatek-coloros-native-gatt-client-wedge` (MediaTek with
     * ColorOS or Realme UI, Android 16+), two immediate status-128 central connect failures with no GATT client held raise
     * the Bluetooth off/on guidance card at once ([GattNativeClientWedge]). It only shows a card and toggles nothing; every
     * other phone is untouched because the entry does not match. A release build always has it on.
     */
    @Volatile var nativeClientWedgeGuard: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * `test_relay_gatt_acknowledged_writes` (Experimental, default off): a legacy-Qualcomm call relay opens its next GATT
     * hop with acknowledged writes paced at 24 KB/s (DeviceFamilyPolicy `qcom-legacy-call-relay-gatt-acknowledged-writes`).
     * The shipped relay writes without response, as every other relay does: with the rule on, 0 of 3 relayed calls S22 ->
     * ASUS -> A15s held 60 s (the A15s dropped write responses, the GATT write timed out), and with it off 2 of 3 did
     * (run l2q27ab-20261110g). DEBUG builds only; release always writes without response.
     */
    @Volatile var relayAcknowledgedWrites: Boolean = false
        set(value) {
            field = value && BuildConfig.DEBUG
        }

    fun applyLaunchExtras(intent: Intent?) {
        if (intent == null) return
        applyExtras(
            boolExtra = { key, default -> if (intent.hasExtra(key)) intent.getBooleanExtra(key, default) else default },
            intExtra = { key, default -> if (intent.hasExtra(key)) intent.getIntExtra(key, default) else default },
            stringExtra = { key -> intent.getStringExtra(key) },
        )
    }

    /** JVM-testable core of [applyLaunchExtras]. */
    fun applyExtras(
        boolExtra: (String, Boolean) -> Boolean,
        intExtra: (String, Int) -> Int,
        stringExtra: (String) -> String?,
    ) {
        busyWedgeRecovery = stringExtra("test_gatt_busy_wedge")?.lowercase() == "recover"
        closeDeferral = boolExtra("test_gatt_close_deferral", true)
        poolBackoff = boolExtra("test_gatt_pool_backoff", true)
        linkBudgetAdmission = boolExtra("test_link_budget_admission", false)
        serverHold = boolExtra("test_gatt_server_hold", false)
        settleMs = intExtra("test_gatt_settle_ms", 0)
        connectOnMain = boolExtra("test_connect_gatt_main", false)
        payload = Payload.entries.firstOrNull { it.debugValue == stringExtra("test_gatt_payload")?.lowercase() } ?: Payload.DEFAULT
        btAudioLoadCut = boolExtra("test_bt_audio_load_cut", false)
        btAudioCapacityKey = boolExtra("test_bt_audio_capacity_key", false)
        gattRefresh = boolExtra("test_gatt_refresh", false)
        aclWaitOnRedial = boolExtra("test_gatt_acl_wait", false)
        remoteFullFailFast = boolExtra("test_gatt_remote_full_failfast", false)
        byteWindowBytes = intExtra("test_gatt_byte_window", 0)
        windowDrainKbps = intExtra("test_gatt_window_kbps", 0)
        nativeClientWedgeGuard = boolExtra("test_gatt_native_wedge_guard", true)
        relayAcknowledgedWrites = boolExtra("test_relay_gatt_acknowledged_writes", false)
    }

    /** Every non-default arm, for the launch record (empty when the shipped defaults are in force). */
    fun describeNonDefaults(): String = listOfNotNull(
        "busy_wedge=recover".takeIf { busyWedgeRecovery },
        "close_deferral=off".takeIf { !closeDeferral },
        "pool_backoff=off".takeIf { !poolBackoff },
        "link_budget_admission".takeIf { linkBudgetAdmission },
        "server_hold".takeIf { serverHold },
        "settle_ms=$settleMs".takeIf { settleMs > 0 },
        "connect_on_main".takeIf { connectOnMain },
        "payload=${payload.debugValue}".takeIf { payload != Payload.DEFAULT },
        "bt_audio_load_cut".takeIf { btAudioLoadCut },
        "bt_audio_capacity_key".takeIf { btAudioCapacityKey },
        "gatt_refresh".takeIf { gattRefresh },
        "acl_wait".takeIf { aclWaitOnRedial },
        "remote_full_failfast".takeIf { remoteFullFailFast },
        "byte_window=$byteWindowBytes@${windowDrainKbps}kbps".takeIf { byteWindowBytes > 0 },
        "native_wedge_guard=off".takeIf { !nativeClientWedgeGuard },
        "relay_acknowledged_writes".takeIf { relayAcknowledgedWrites },
    ).joinToString(",").ifEmpty { "none" }

    /** Back to the shipped defaults (unit tests). */
    fun resetToDefaults() = applyExtras({ _, default -> default }, { _, default -> default }, { null })

    private const val MAX_SETTLE_MS = 3_000
    private const val MAX_WINDOW_BYTES = 262_144
    private const val MAX_WINDOW_KBPS = 2_000
}
