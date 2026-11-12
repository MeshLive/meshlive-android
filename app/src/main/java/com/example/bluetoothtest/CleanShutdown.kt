package com.example.bluetoothtest

import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * DEBUG clean shutdown before a test driver force-stops the app (`test_control shutdown`, or the broadcast [ACTION]).
 *
 * 2026-10-21: the ASUS (Android 11) counted `uncleanExits=99` and refilled the phone-wide 32-slot GATT/scanner table
 * within minutes of a Bluetooth restart, because drivers ran `am force-stop` while the app scanned: Android 10-11 can keep
 * the registration of a scanner (and of GATT apps) whose process is killed. The shutdown hangs up, stops every scanner,
 * lets the activity's teardown close the call endpoint, advertisers, GATT servers and clients, stops the session service,
 * then waits until nothing of this app is registered (at most [SETTLE_MS]) and logs
 * `BENCHMARK clean_shutdown: done | nonce=<n> | settled=<bool> | scanners=.. | advertisers=.. | gattClients=.. | gattServers=..`.
 * Only after that line should the driver force-stop.
 */
internal object CleanShutdown {
    /** `adb shell am broadcast -a <ACTION> -p <package> --es nonce <n>`: reaches only a running app (never relaunches one). */
    const val ACTION = "com.example.bluetoothtest.debug.CLEAN_SHUTDOWN"
    const val EXTRA_NONCE = "nonce"
    const val SETTLE_MS = 2_500L
    const val POLL_MS = 100L

    data class Residue(
        val scanners: Int,
        val advertisers: Int,
        val gattClients: Int,
        val gattServers: Int,
        val awareAttach: Int = 0,
        val awareNetworks: Int = 0,
        val wifiSockets: Int = 0,
        val directGroups: Int = 0,
    ) {
        val clear: Boolean get() = scanners == 0 && advertisers == 0 && gattClients == 0 && gattServers == 0 &&
            awareAttach == 0 && awareNetworks == 0 && wifiSockets == 0 && directGroups == 0
    }

    @Volatile var requested = false
        private set
    @Volatile private var nonce = "none"
    @Volatile private var startedAtMs = 0L

    fun begin(requestNonce: String?, nowMs: Long = SystemClock.elapsedRealtime()) {
        requested = true
        nonce = sanitizeNonce(requestNonce)
        startedAtMs = nowMs
    }

    /** Only letters, digits, '-' and '_' (at most 32) reach the log line a driver greps for. */
    fun sanitizeNonce(value: String?): String =
        value?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }?.take(32)?.takeIf { it.isNotEmpty() } ?: "none"

    fun residue(): Residue {
        val gatt = runCatching { GattResourceRegistry.shared.snapshot() }.getOrNull()
        val wifi = runCatching { WifiRadio.residue() }.getOrNull()
        return Residue(
            scanners = runCatching { ScanRegistry.shared.size }.getOrDefault(0),
            advertisers = runCatching { AdvertisingBudget.shared.owned() }.getOrDefault(0),
            gattClients = gatt?.clients ?: 0,
            gattServers = gatt?.servers ?: 0,
            awareAttach = wifi?.awareAttach ?: 0,
            awareNetworks = wifi?.awareNetworks ?: 0,
            wifiSockets = wifi?.wifiSockets ?: 0,
            directGroups = wifi?.directGroups ?: 0,
        )
    }

    fun doneLine(residue: Residue, settled: Boolean, elapsedMs: Long, requestNonce: String = nonce): String =
        "BENCHMARK clean_shutdown: done | nonce=$requestNonce | settled=$settled | scanners=${residue.scanners} | " +
            "advertisers=${residue.advertisers} | gattClients=${residue.gattClients} | gattServers=${residue.gattServers} | " +
            "awareAttach=${residue.awareAttach} | awareNetworks=${residue.awareNetworks} | wifiSockets=${residue.wifiSockets} | " +
            "directGroups=${residue.directGroups} | elapsedMs=$elapsedMs"

    /**
     * Polls [residue] every [POLL_MS] on [handler] until it is clear or [SETTLE_MS] passed since [begin], then logs the
     * done line once. The closes of the teardown are partly asynchronous (advertiser and GATT callbacks).
     */
    fun awaitAndLog(
        handler: Handler,
        probe: () -> Residue = ::residue,
        now: () -> Long = SystemClock::elapsedRealtime,
        log: (String) -> Unit = { line -> Log.i("BluetoothVideo", line) },
    ) {
        val check = object : Runnable {
            override fun run() {
                val residue = probe()
                val elapsed = now() - startedAtMs
                if (residue.clear || elapsed >= SETTLE_MS) {
                    log(doneLine(residue, residue.clear, elapsed))
                    requested = false
                } else {
                    handler.postDelayed(this, POLL_MS)
                }
            }
        }
        handler.post(check)
    }
}
