package com.example.bluetoothtest

import android.os.SystemClock

/**
 * Native GATT client wedge (DeviceFamilyPolicy `mediatek-coloros-native-gatt-client-wedge`; on by default since
 * 2026-11-10 by the owner's answer to audit item BT-07 Q4, so a Realme-family user gets the guidance card; the DEBUG
 * control arm is `test_gatt_native_wedge_guard=false`). After hours of use the Realme RMX5256 (MediaTek MT6835,
 * Android 16, Realme UI) Bluetooth stack logs `bta_gattc_process_api_open: No resources to open a new connection` and
 * fails every new central GATT or LE CoC connect within about 50 ms with status 128 (GATT_NO_RESOURCES) while neither
 * MeshLive nor the framework holds a GATT client. Only a Bluetooth restart clears it; the peripheral role keeps working.
 * The generic pool back-off ([GattPoolBackoff]) raises the restart guidance only after three back-off steps (1 s, 2 s
 * and 4 s apart), which one failed call (an attempt and its retry about 100 ms apart) never reaches.
 *
 * With the entry active (this family only) and the switch on, [GattNativeClientWedgeGuard.threshold] such failures in a row, each within
 * [GattNativeClientWedgeGuard.immediateMs] of its dial, all within [GattNativeClientWedgeGuard.windowMs], with no GATT
 * client of this app held, raise the generic guidance at once: the reliability card's "Bluetooth may be stuck" with the
 * Bluetooth settings button ([PoolExhaustionPolicy.onNativeClientWedge]). Apps cannot restart Bluetooth on Android 13
 * and newer ([AppBluetoothRestartAllowed] ends at API 32), so nothing is toggled. Any other failure, a slow one or a
 * held client ends the run; a successful central connect clears the detection and the guidance.
 */
internal class NativeClientWedgeDetector(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    enum class Outcome { IGNORED, COUNTED, DETECTED, ALREADY_DETECTED }

    private val failures = ArrayDeque<Long>()
    private var detected = false

    /**
     * A central connect failed with [status] (null: no status in the message) [attemptElapsedMs] after its dial (null:
     * unknown) while this app held [heldClients] GATT clients.
     */
    @Synchronized
    fun onCentralConnectFailure(
        status: Int?,
        attemptElapsedMs: Long?,
        heldClients: Int,
        guard: GattNativeClientWedgeGuard,
    ): Outcome {
        val immediate = attemptElapsedMs != null && attemptElapsedMs >= 0L && attemptElapsedMs <= guard.immediateMs
        if (status != GattResourceRegistry.STATUS_NO_RESOURCES || !immediate || heldClients != 0) {
            failures.clear()
            return Outcome.IGNORED
        }
        if (detected) return Outcome.ALREADY_DETECTED
        val now = clock()
        failures.addLast(now)
        while (now - failures.first() > guard.windowMs) failures.removeFirst()
        if (failures.size < guard.threshold) return Outcome.COUNTED
        detected = true
        return Outcome.DETECTED
    }

    /** A central connect worked: the stack opens connections again. True when this ended a detection. */
    @Synchronized
    fun onCentralConnectSuccess(): Boolean {
        val ended = detected
        failures.clear()
        detected = false
        return ended
    }

    /** Failures in the current run (for the log line). */
    @Synchronized
    fun counted(): Int = failures.size
}

/** The entry's effect: what counts as a native client wedge ([NativeClientWedgeDetector]). */
internal data class GattNativeClientWedgeGuard(val threshold: Int, val windowMs: Long, val immediateMs: Long) : DevicePolicyEffect {
    override fun describe(): String =
        "$threshold central connects in a row failing with status 128 within $immediateMs ms of the dial, inside $windowMs ms, " +
            "with no GATT client of this app held = native client wedge: show the Bluetooth off/on guidance at once"

    companion object {
        /** Offline replay of the recorded wedge (23 app sessions): 2 in a row caught all 23, 3 caught 9. */
        const val THRESHOLD = 2
        const val WINDOW_MS = 60_000L

        /** Recorded wedge failures came 12-61 ms after the dial; a stack that really tries takes far longer. */
        const val IMMEDIATE_MS = 500L
    }
}

/**
 * The shared detector, the policy query and the `BENCHMARK gatt_native_client_wedge` line. Fed by the two central
 * connect loops (GattVideoClient and the BLE control client, beside [GattPoolBackoff.noteConnectFailure]) and by
 * [GattPoolBackoff.recordSuccess] for a client success. Never throws into a connect path.
 */
internal object GattNativeClientWedge {
    @Volatile var detector = NativeClientWedgeDetector()

    /** The resolved policy of this phone; unit tests replace it with a resolution of other traits. */
    @Volatile var policySource: () -> ResolvedDevicePolicy = { DeviceFamilyPolicy.current() }

    /**
     * GATT clients this app holds now: the production registry's count. Unit tests replace it (the registry stamps every
     * registration with SystemClock, which plain JVM tests cannot call).
     */
    @Volatile var heldClients: () -> Int = { GattResourceRegistry.shared.snapshot().clients }

    /** The guard on this phone, or null: arm off, entry not matching, or entry switched off (`test_device_policy_off`). */
    fun guard(): GattNativeClientWedgeGuard? {
        if (!GattSwitches.nativeClientWedgeGuard) return null
        return runCatching { policySource().effects<GattNativeClientWedgeGuard>().firstOrNull() }.getOrNull()
    }

    /** A central connect loop's failure, with the [message] the loop reports ("... (status N)"). */
    fun onCentralConnectFailure(message: String?, attemptElapsedMs: Long?, owner: String) {
        val guard = guard() ?: return
        runCatching {
            note(leSetupStatus(message), attemptElapsedMs, owner, guard, heldClients())
        }
    }

    fun note(
        status: Int?,
        attemptElapsedMs: Long?,
        owner: String,
        guard: GattNativeClientWedgeGuard,
        heldClients: Int,
        card: PoolExhaustionPolicy = GattPoolBackoff.policy,
        log: (String) -> Unit = { GattResourceRegistry.logQuietly(it) },
    ): NativeClientWedgeDetector.Outcome {
        val outcome = detector.onCentralConnectFailure(status, attemptElapsedMs, heldClients, guard)
        if (outcome == NativeClientWedgeDetector.Outcome.DETECTED) {
            card.onNativeClientWedge()
            log(
                "BENCHMARK gatt_native_client_wedge: event=detected | owner=$owner | failures=${detector.counted()} | " +
                    "status=$status | elapsedMs=$attemptElapsedMs | heldClients=$heldClients | windowMs=${guard.windowMs} | " +
                    "action=restart_guidance",
            )
            log("BENCHMARK gatt_restart_guidance: ${PoolExhaustionPolicy.GUIDANCE}")
        }
        return outcome
    }

    /** A central connect worked ([GattPoolBackoff.recordSuccess] for [PoolSource.CLIENT]). Never throws. */
    fun onCentralConnectSuccess(
        card: PoolExhaustionPolicy = GattPoolBackoff.policy,
        log: (String) -> Unit = { GattResourceRegistry.logQuietly(it) },
    ) {
        runCatching {
            if (detector.onCentralConnectSuccess()) {
                card.onConnectSuccess()
                log("BENCHMARK gatt_native_client_wedge: event=cleared")
            }
        }
    }

    /** Unit tests: a fresh detector, this phone's policy and the production registry count again. */
    fun resetForTest() {
        detector = NativeClientWedgeDetector()
        policySource = { DeviceFamilyPolicy.current() }
        heldClients = { GattResourceRegistry.shared.snapshot().clients }
    }
}
