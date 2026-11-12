package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

object BleL2capControlProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("c8d5a101-9f88-4f65-a03d-7f5a2fb921fa")
    val PSM_CHARACTERISTIC_UUID: UUID = UUID.fromString("c8d5a102-9f88-4f65-a03d-7f5a2fb921fa")
    const val MESH_MANUFACTURER_ID = 0xffff

    fun encodePsm(psm: Int): ByteArray {
        require(psm in 1..0xffff) { "Invalid L2CAP PSM $psm" }
        return byteArrayOf((psm ushr 8).toByte(), psm.toByte())
    }

    fun decodePsm(bytes: ByteArray): Int {
        require(bytes.size == 2) { "Invalid L2CAP PSM response" }
        val psm = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        require(psm != 0) { "Invalid L2CAP PSM 0" }
        return psm
    }
}

data class BleL2capPeer(
    val device: BluetoothDevice,
    val label: String,
    val announcement: DiscoveryAnnouncement,
    /** The phone's Bluetooth name when Android reports one (unauthenticated, display only). */
    val advertisedName: String? = null,
    /**
     * Experimental relayed calls: set only on a row built from the reach table. [device] is then the
     * first helper's, [announcement] names the target at the route cost, and the call sends SETUP.
     */
    val relayRoute: RelayRoute? = null,
    /** A compact call offer's receive form (legacy or extended, compared ADs) for the copied-ID PC exemption; else null. */
    val twinForm: AdvertTwinForm? = null,
    /** Wider reach: the Wi-Fi Aware sighting of the same key, when the person was also (or only) heard over Wi-Fi. */
    val wifi: WifiSighting? = null,
    /** True for a row heard only over Wi-Fi. [device] is then a placeholder ([WifiOnlyPeer]) that is never connected to. */
    val wifiOnly: Boolean = false,
)

class BleL2capDiscovery(
    private val adapter: BluetoothAdapter,
    private val onPeer: (BleL2capPeer) -> Unit,
    private val onFinished: (Int) -> Unit,
    private val onError: (String) -> Unit,
    private val acceptAnnouncement: (DiscoveryAnnouncement) -> Boolean = { true },
    private val scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS,
    private val reportDuplicates: Boolean = false,
    private val continuousLowLatency: Boolean = false,
    private val startLowLatency: Boolean = true,
    /**
     * Experimental rotating discovery IDs: decode version 5 / compact v2 offers too. Only the
     * call list (and a call's own rediscovery) passes true; relays, forwarders and stream scanners
     * keep false, so they never see or mirror a rotating ID (phase 1).
     */
    private val acceptRotating: Boolean = false,
    /**
     * Experimental relayed calls: a continuous scan for an idle relay-helper uses Android's low-power
     * mode (screen off, not charging) instead of balanced. Ignored when [startLowLatency] is set.
     */
    private val lowPower: Boolean = false,
    /**
     * A visible scanner (Nearby call list, Live list): restart the system scan when it has heard nothing at all for a
     * while ([ScanStallWatch]), through the scan-start gate. Off with `test_scan_recovery=false` (the control arm).
     */
    private val stallRecovery: Boolean = false,
    /**
     * F11: no offloaded (hardware) filter for this scanner; the service UUID is matched in software ([addResult]). Only
     * the screen-on, time-limited Meet scan passes true ([DebugTunables.meetSoftwareFilter]).
     */
    private val softwareFilter: Boolean = false,
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val seenAddresses = linkedSetOf<String>()
    private val deviceNames = mutableMapOf<String, String>()
    private var activeScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
    /**
     * iPhone compact discovery ("Find iPhones"): fixed at creation, except that a refused filter turns it
     * off for this scanner and the rest of the process ([CompactFilterGuard]).
     */
    @Volatile private var compactDiscovery = CompactDiscoveryFeature.filterActive()
    /**
     * "Find PCs" (Experimental, [PcAnnouncementFeature]): adds one service-data filter and extended scan settings, only on
     * a controller that reports extended advertising and only for a scanner that accepts rotating offers (the call list). A refused filter turns it off for this scanner and the rest of the
     * process, before the compact filter is touched ([PcFilterGuard]).
     */
    private val pcWanted: Boolean = PcAnnouncementFeature.filterActive() && acceptRotating
    private val pcUnsupported: Boolean = pcWanted && !extendedAdvertisingSupported()
    @Volatile private var pcDiscovery = pcWanted && !pcUnsupported
    private val pcDecodedLog = PcLogLimiter()
    private val pcRejectedLog = PcLogLimiter()
    private val stats = ScanStats(Integer.toHexString(System.identityHashCode(this)), compactDiscovery, pcDiscovery)
    /** DEBUG: why decoded announcements were not accepted (F11), once per address. */
    private val rejectLog = ScanRejectLog()
    private val backoff = ScanRecoveryBackoff()
    private var failureCode: Int? = null
    private var firstFailureMs = 0L
    private val retryScan = Runnable { restartAfterFailure() }
    private val stallWatch = ScanStallWatch()
    private val stallCheck = Runnable { checkStall() }
    private var lastQuotaFullLogMs = 0L

    /** Batch S: this scanner's name in [ScanRegistry], and whether renewal/Bluetooth-off has it paused. */
    private val registryName = "discovery-" + stats.scanner
    @Volatile private var scanPaused = false

    /** The one place [running] goes false: also drops the registry entry (batch S-03). */
    private fun halt(): Boolean {
        val was = running.getAndSet(false)
        if (was) {
            scanPaused = false
            ScanRegistry.unregister(registryName)
        }
        return was
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::addResult)

        override fun onScanFailed(errorCode: Int) {
            stats.logIfDebug("failed code=$errorCode")
            if (!running.get()) return
            if (errorCode == ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED) {
                // The stack has no free app registration (pool of 32): visible as gatt_pool_exhausted source=scanner.
                GattPoolBackoff.recordFailure(PoolSource.SCANNER, errorCode, "scanner-" + stats.scanner)
            }
            if (!DebugTunables.scanRecovery) {
                // Control arm (test_scan_recovery=false): the pre-2026-10-10 behaviour exactly.
                halt()
                handler.removeCallbacksAndMessages(null)
                onError("BLE scan failed (code $errorCode).")
                return
            }
            onScanFailure(errorCode)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        seenAddresses.clear()
        deviceNames.clear()
        // S-01: say whether Android 10-11 will deliver results at all (Location switch / permission).
        if (ScanEnvironment.guidanceActive(Build.VERSION.SDK_INT, DebugTunables.devicePolicyOff)) {
            scanLog(ScanEnvironment.line(ScanEnvironment.last) + " | scanner=${stats.scanner}")
        }
        // AOSP drops a 6th scan start inside 30 s WITHOUT any callback (no onScanFailed; BluetoothLeScanner returns
        // quietly when the stack answers SCANNING_TOO_FREQUENTLY, aosp-bt-versions SCAN-01), so this gate is the only
        // protection: every start of every app scanner waits for a slot (registry entry le-scan-start-quota).
        val waitMs = ScanStartGate.shared.reserve(scanBudget)
        if (waitMs > 0L) {
            android.util.Log.i("BluetoothVideo", "BLE scan start deferred ${waitMs} ms: scan quota (5 starts per 30 s) | scanner=${stats.scanner} | reason=quota")
            runCatching { ScanStartGate.deferralListener?.invoke(waitMs) }
            handler.postDelayed({ if (running.get()) beginScan(reserved = false) }, waitMs)
            return
        }
        beginScan(reserved = true)
    }

    private val scanBudget: LeScanStartBudget? = runCatching { BluetoothTransportPolicy.scanBudget() }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun beginScan(reserved: Boolean) {
        if (!reserved) {
            val waitMs = ScanStartGate.shared.reserve(scanBudget)
            if (waitMs > 0L) {
                handler.postDelayed({ if (running.get()) beginScan(reserved = false) }, waitMs)
                return
            }
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            halt()
            onError("BLE scanning is unavailable while Bluetooth is off.")
            return
        }
        activeScanMode = DiscoveryScanPlan.mode(startLowLatency, lowPower)
        try {
            EncoderBenchGuard.check("BleL2capControl.scan")
            // Review B11: a close() that ran while this start waited (quota deferral, another thread) must not leave a
            // scan registered that nothing will ever stop.
            synchronized(scanLock) {
                if (!running.get()) return
                scanner.startScan(scanFilters(), scanSettings(activeScanMode), callback)
            }
        } catch (_: SecurityException) {
            halt()
            onError("Nearby devices permission is unavailable. Allow it in Android Settings and try again.")
            return
        }
        backoff.reset()
        failureCode = null
        stats.reset()
        scanPaused = false
        ScanRuntime.onScanStart()
        ScanRegistry.register(registryName, stop = ::pauseForRenewal, start = ::resumeAfterRenewal, renewable = scanDurationMs <= 0L)
        logDuty()
        if (compactDiscovery) {
            android.util.Log.i(
                "BluetoothVideo",
                "compact_filter: event=started | scanner=${stats.scanner} | filters=${scanFilters().size} | mode=$activeScanMode",
            )
        }
        if (pcDiscovery) {
            android.util.Log.i(
                "BluetoothVideo",
                "pc_scan: event=started | scanner=${stats.scanner} | filters=${scanFilters().size} | extended_supported=true | legacy=false | phy=1M | mode=$activeScanMode",
            )
        } else if (pcUnsupported) {
            android.util.Log.i("BluetoothVideo", "pc_scan: unsupported reason=no_extended | extended_supported=false | scanner=${stats.scanner}")
        }
        if (BuildConfig.DEBUG) handler.postDelayed(::logScanStats, SCAN_STATS_INTERVAL_MS)
        if (stallRecovery && DebugTunables.scanRecovery) {
            stallWatch.onStarted(SystemClock.elapsedRealtime())
            handler.removeCallbacks(stallCheck)
            handler.postDelayed(stallCheck, ScanStallWatch.CHECK_INTERVAL_MS)
        }
        if (scanDurationMs > 0) {
            handler.postDelayed({ stop(notify = true) }, scanDurationMs)
        } else if (startLowLatency && !continuousLowLatency) {
            handler.postDelayed(::switchToBalancedScan, LOW_LATENCY_SCAN_BURST_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun switchToBalancedScan() {
        if (!running.get() || scanPaused || activeScanMode == ScanSettings.SCAN_MODE_BALANCED) return
        val scanner = adapter.bluetoothLeScanner ?: return
        // Restarting in balanced mode is a new scan start. Without a free quota slot keep the running
        // low-latency scan and do not queue the switch: the slots stay free for new discovery (calls).
        // tryReserve() checks and takes the slot atomically and, unlike reserve(), reports no deferral.
        if (!ScanStartGate.shared.tryReserve(scanBudget)) {
            android.util.Log.i("BluetoothVideo", "BLE balanced switch skipped: scan quota full; keeping low-latency scan")
            return
        }
        runCatching { scanner.stopScan(callback) }
        activeScanMode = ScanSettings.SCAN_MODE_BALANCED
        EncoderBenchGuard.check("BleL2capControl.scanRestart")
        runCatching { scanner.startScan(scanFilters(), scanSettings(activeScanMode), callback) }
            .onSuccess {
                ScanRuntime.onScanStart()
                ScanRegistry.shared.markStarted(registryName)
                stallWatch.onStarted(SystemClock.elapsedRealtime())
                logDuty()
            }
            .onFailure {
                if (halt()) onError("BLE balanced scan could not start.")
            }
    }

    /**
     * Scan-stall recovery ([ScanStallWatch]): a running, unpaused scan that heard nothing for the required silence is
     * stopped and started again, only when the scan-start gate has a free slot now (a full quota is waited out, never
     * queued past it). Every [ScanStallWatch.CHECK_INTERVAL_MS] while this scanner runs.
     */
    @SuppressLint("MissingPermission")
    private fun checkStall() {
        if (!running.get()) return
        handler.postDelayed(stallCheck, ScanStallWatch.CHECK_INTERVAL_MS)
        if (scanPaused || failureCode != null) return
        val now = SystemClock.elapsedRealtime()
        if (!stallWatch.due(now)) return
        val silentMs = stallWatch.silentMs(now)
        val scanner = adapter.bluetoothLeScanner ?: return
        if (!ScanStartGate.shared.tryReserve(scanBudget)) {
            if (now - lastQuotaFullLogMs >= ScanStallWatch.STALL_MS) {
                lastQuotaFullLogMs = now
                logStall(silentMs, "quota_full")
            }
            return
        }
        runCatching { scanner.stopScan(callback) }
        EncoderBenchGuard.check("BleL2capControl.scanStall")
        val restarted = runCatching { scanner.startScan(scanFilters(), scanSettings(activeScanMode), callback) }.isSuccess
        stallWatch.onRestarted(now)
        if (restarted) {
            ScanRuntime.onScanStart()
            ScanRegistry.shared.markStarted(registryName)
        }
        logStall(silentMs, if (restarted) "yes" else "failed")
    }

    private fun logStall(silentMs: Long, restart: String) {
        android.util.Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                ScanStallWatch.line(stats.scanner, silentMs, restart, ScanStartGate.shared.used(scanBudget)),
        )
    }

    /** DEBUG: the duty cycle the stack is expected to apply for this SDK and mode (SCAN-06), per (re)start. */
    private fun logDuty() {
        if (BuildConfig.DEBUG) {
            scanLog(
                ScanDutyCycle.line(Build.VERSION.SDK_INT, activeScanMode, ScanRuntime.screenOnHint, stats.scanner) +
                    " | phy=${if (scanOnLe1m()) "1M" else "all"}",
            )
        }
    }

    /**
     * Batch S-03/S-08: the registry's `stop`. Stops the system scan but keeps this scanner logically running
     * (timers, results already seen, owner callbacks); [resumeAfterRenewal] starts it again through the gate.
     */
    @SuppressLint("MissingPermission")
    private fun pauseForRenewal() {
        if (!running.get()) return
        scanPaused = true
        handler.removeCallbacks(retryScan)
        runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
    }

    /** The registry's `start`: back on the main thread, through ScanStartGate. */
    private fun resumeAfterRenewal() {
        handler.post { resumeScan() }
    }

    @SuppressLint("MissingPermission")
    private fun resumeScan() {
        if (!running.get() || !scanPaused) return
        val waitMs = ScanStartGate.shared.reserve(scanBudget)
        if (waitMs > 0L) {
            handler.postDelayed({ resumeScan() }, waitMs)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            // Bluetooth is off: the adapter-state restart (BluetoothEpoch) calls the registry's start again.
            ScanRegistry.shared.markPausedAgain(registryName)
            return
        }
        scanPaused = false
        EncoderBenchGuard.check("BleL2capControl.scanRenew")
        runCatching { scanner.startScan(scanFilters(), scanSettings(activeScanMode), callback) }
            .onSuccess {
                ScanRuntime.onScanStart()
                ScanRegistry.shared.markStarted(registryName)
                stallWatch.onStarted(SystemClock.elapsedRealtime())
            }
            .onFailure {
                if (halt()) {
                    handler.removeCallbacksAndMessages(null)
                    onError("BLE scan could not restart.")
                }
            }
    }

    /**
     * Scan-failure policy (research catalog P13, 2026-10-10; on by default, `test_scan_recovery=false`
     * restores the old stop-and-report). Before, any onScanFailed ended discovery: the callers showed
     * the error and restarted after their own delay (30 s for viewer requests), so a transient stack
     * refusal (registration failure, internal error, the scan-start quota) emptied the call list,
     * stream discovery or relay search meanwhile. Restarts take their slot from the registry's
     * scan-start gate (le-scan-start-quota); with that entry off nothing limits them but the back-off.
     */
    private fun onScanFailure(errorCode: Int) {
        if (ScanPolicy.enabled("android10-scanner-leak-on-process-death")) {
            ScanLeakClassifier.classify(Build.VERSION.SDK_INT, errorCode, null, ScanRuntime.uncleanExits)?.let {
                scanLog(
                    "scan_leak: event=scan_failed | classification=$it | code=$errorCode | " +
                        "uncleanExits=${ScanRuntime.uncleanExits} | scanner=${stats.scanner}",
                )
            }
        }
        if (PcFilterGuard.dropFor(errorCode, pcDiscovery, compactDiscovery) == PcFilterGuard.Drop.PC) {
            // The newest, experimental filter goes first: the PC service-data filter (and the extended scan settings that
            // come with it). The compact filter and the app's own filter are untouched; a repeat failure then drops compact.
            pcDiscovery = false
            PcAnnouncementFeature.filterRejected = true
            android.util.Log.w(
                "BluetoothVideo",
                "pc_filter: event=dropped | scanner=${stats.scanner} | code=$errorCode | " +
                    "name=${ScanFailurePolicy.name(errorCode)} | action=restart_without_pc_filter",
            )
            failureCode = errorCode
            handler.removeCallbacks(retryScan)
            runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
            handler.post(retryScan)
            return
        }
        if (compactDiscovery && CompactFilterGuard.refusesFilter(errorCode)) {
            // The masked iPhone filter is the likely cause: keep the app's own filter and restart at once.
            compactDiscovery = false
            CompactDiscoveryFeature.filterRejected = true
            android.util.Log.w(
                "BluetoothVideo",
                "compact_filter: event=dropped | scanner=${stats.scanner} | code=$errorCode | " +
                    "name=${ScanFailurePolicy.name(errorCode)} | action=restart_without_compact_filter",
            )
            failureCode = errorCode
            handler.removeCallbacks(retryScan)
            runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
            handler.post(retryScan)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (failureCode == null) firstFailureMs = now
        failureCode = errorCode
        handler.removeCallbacks(retryScan)
        val step = ScanFailurePolicy.step(errorCode, backoff, continuous = scanDurationMs <= 0L)
        if (step.handBack) {
            // FEATURE_UNSUPPORTED, or a continuous scan whose back-off reached its cap: the caller's
            // visible status/error path takes over (it shows the message and restarts discovery itself).
            logRecovery(if (step.action == ScanFailureAction.REPORT) "reported" else "handed_back", errorCode, step)
            halt()
            handler.removeCallbacksAndMessages(null)
            runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
            onError(ScanFailurePolicy.userMessage(errorCode))
            return
        }
        if (step.action == ScanFailureAction.RESTART) {
            // ALREADY_STARTED: this callback is still registered with the stack; stop it so the
            // restart registers cleanly instead of failing the same way.
            runCatching { adapter.bluetoothLeScanner?.stopScan(callback) }
        }
        android.util.Log.w(
            "BluetoothVideo",
            "BLE scan failed (code $errorCode, ${ScanFailurePolicy.name(errorCode)}); retrying in ${step.delayMs} ms",
        )
        logRecovery("failed", errorCode, step)
        handler.postDelayed(retryScan, step.delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun restartAfterFailure() {
        if (!running.get() || scanPaused) return
        val code = failureCode ?: return
        val step = ScanRecoveryStep(ScanFailurePolicy.action(code), 0L, handBack = false)
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            halt()
            handler.removeCallbacksAndMessages(null)
            onError("BLE scanning is unavailable while Bluetooth is off.")
            return
        }
        // The same per-process gate as every other start (null budget: entry switched off, no limit).
        val quotaWaitMs = ScanStartGate.shared.reserve(scanBudget)
        if (quotaWaitMs > 0L) {
            logRecovery("deferred", code, step.copy(delayMs = quotaWaitMs))
            handler.postDelayed(retryScan, quotaWaitMs)
            return
        }
        runCatching { scanner.stopScan(callback) }
        EncoderBenchGuard.check("BleL2capControl.scanRecover")
        runCatching { scanner.startScan(scanFilters(), scanSettings(activeScanMode), callback) }
            .onSuccess {
                ScanRuntime.onScanStart()
                ScanRegistry.shared.markStarted(registryName)
                logRecovery("restarted", code, step)
            }
            .onFailure {
                logRecovery("restart_failed", code, step.copy(delayMs = -1L))
                if (halt()) {
                    handler.removeCallbacksAndMessages(null)
                    onError(ScanFailurePolicy.userMessage(code))
                }
            }
    }

    private fun pcLogPrefix(): String = if (BuildConfig.DEBUG) "BENCHMARK " else ""

    /** One line per decoded PC announcement, at most one per address every 10 s. The address is logged in DEBUG only. */
    private fun logPcDecoded(result: ScanResult, decoded: PcAnnouncement) {
        if (!pcDecodedLog.allow(result.device.address, SystemClock.elapsedRealtime())) return
        android.util.Log.i(
            "BluetoothVideo",
            pcLogPrefix() + "pc_announcement: scanner=${stats.scanner} | rdi=" +
                decoded.rdi.joinToString("") { "%02x".format(it) } +
                " | relay=${decoded.relayCapable} | consent=${decoded.relayConsent} | codecs=${decoded.codecs} | " +
                "cost=${decoded.cost} | rssi=${result.rssi}" + if (BuildConfig.DEBUG) " | addr=${result.device.address}" else "",
        )
    }

    /** One line per rejected PC announcement (and error), at most one per address every 10 s. */
    private fun logPcRejected(result: ScanResult, error: PcAnnouncementError, data: ByteArray?) {
        if (!pcRejectedLog.allow(result.device.address, SystemClock.elapsedRealtime())) return
        android.util.Log.i(
            "BluetoothVideo",
            pcLogPrefix() + "pc_announcement_rejected: scanner=${stats.scanner} | error=$error | " +
                "len=${data?.size ?: -1} | rssi=${result.rssi}" + if (BuildConfig.DEBUG) " | addr=${result.device.address}" else "",
        )
    }

    private fun logRecovery(event: String, code: Int, step: ScanRecoveryStep) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK scan_recovery: scanner=${stats.scanner} | event=$event | code=$code | " +
                "name=${ScanFailurePolicy.name(code)} | action=${step.action.label} | attempt=${backoff.failures} | " +
                "delayMs=${step.delayMs} | continuous=${scanDurationMs <= 0L} | " +
                "quotaGate=${if (scanBudget == null) "off" else "on"} | " +
                "sinceFirstFailureMs=${SystemClock.elapsedRealtime() - firstFailureMs}",
        )
    }

    /**
     * The app's service UUID, plus (experimental compact discovery) the masked iPhone UUID. Filters
     * are ORed and one scan start covers both, so the scan quota is unchanged.
     */
    private fun scanFilters(): List<ScanFilter> =
        // S-06 (Experimental, screen on only) or this scanner's own F11 option: no hardware filter; addResult matches the
        // service UUID in software. [DiscoveryScanPlan] is the pure, tested description of what is built here.
        DiscoveryScanPlan.filters(ScanFilterFallback.softwareFor(softwareFilter), compactDiscovery, pcDiscovery).map { spec ->
            when (spec) {
                is ScanFilterSpec.ServiceUuid -> ScanFilter.Builder().also { builder ->
                    if (spec.mask == null) {
                        builder.setServiceUuid(ParcelUuid(spec.uuid))
                    } else {
                        builder.setServiceUuid(ParcelUuid(spec.uuid), ParcelUuid(spec.mask))
                    }
                }.build()
                // Empty data: matches any payload under the PC announcement UUID (AOSP prefix match).
                is ScanFilterSpec.ServiceDataAny -> ScanFilter.Builder()
                    .setServiceData(ParcelUuid(spec.uuid), ByteArray(0))
                    .build()
            }
        }

    private fun scanSettings(mode: Int): ScanSettings {
        // F11: this app's adverts are legacy, always on LE 1M; the default PHY_LE_ALL_SUPPORTED makes Android 13+
        // stacks scan LE 1M and Coded in turn ([DebugTunables.scanLe1mOnly], WeakPairReach). "Find PCs" needs extended
        // adverts, so while its filter is active it asks for them instead ([DiscoveryScanPlan.settings]).
        val spec = DiscoveryScanPlan.settings(mode, scanOnLe1m(), pcDiscovery)
        return ScanSettings.Builder()
            .setScanMode(spec.mode)
            .also { builder ->
                spec.legacy?.let(builder::setLegacy)
                spec.phy?.let(builder::setPhy)
            }
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun extendedAdvertisingSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)

    private fun scanOnLe1m(): Boolean = DebugTunables.scanLe1mOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @SuppressLint("MissingPermission")
    private fun addResult(result: ScanResult) {
        if (!running.get()) return
        stats.raw++
        stallWatch.onResult(SystemClock.elapsedRealtime())
        if (ScanFilterFallback.softwareFor(softwareFilter)) {
            val uuids = result.scanRecord?.serviceUuids?.map(ParcelUuid::getUuid)
            val wanted = ScanFilterFallback.matchesService(uuids, BleL2capControlProtocol.SERVICE_UUID) ||
                (compactDiscovery && CompactDiscoveryAnnouncement.firstCandidate(uuids) != null) ||
                (pcDiscovery && result.scanRecord?.getServiceData(ParcelUuid(PcAnnouncement.UUID)) != null)
            if (!wanted) return
        }
        ScanRuntime.onScanResult()
        if (GattPoolBackoff.scannerFailing) GattPoolBackoff.recordSuccess(PoolSource.SCANNER)
        failureCode?.let { code ->
            // Any result proves the scanner is alive again: the next failure starts from 3 s.
            logRecovery("recovered", code, ScanRecoveryStep(ScanFailurePolicy.action(code), 0L, handBack = false))
            failureCode = null
        }
        backoff.reset()
        val announcementBytes = result.scanRecord
            ?.getManufacturerSpecificData(BleL2capControlProtocol.MESH_MANUFACTURER_ID)
        // Experimental relayed calls: version-4 reach pages feed the reach table, before the per-address
        // duplicate filter (every rotated page is seen). Off: they fall through and are rejected as before.
        if (announcementBytes != null && RelayedCallsFeature.enabled &&
            RelayedCallsRuntime.onScanPage(announcementBytes, result.device.address)
        ) {
            return
        }
        // Experimental peer labels: version-3 title pages take their own path here, before the
        // per-address dedup, so every rotated page is seen (R15). Off: they fall through and are
        // rejected exactly as before.
        if (PeerLabelFeature.titlePagesEnabled && TitlePageCodec.isTitlePage(announcementBytes)) {
            runCatching { TitlePageCodec.decode(requireNotNull(announcementBytes)) }.getOrNull()?.let { page ->
                StreamTitles.pagesReceived++
                val complete = StreamTitles.board.add(page)
                if (complete != null && StreamTitles.isNew(page.routeToken, complete)) StreamTitles.onNewTitle?.invoke()
                if (BuildConfig.DEBUG && complete != null) {
                    android.util.Log.i("BluetoothVideo", "BENCHMARK title_page_rx: token=%08x | chars=%d | pages=%d".format(page.routeToken, complete.length, StreamTitles.pagesReceived))
                }
            }
            return
        }
        // Visible by name (default off): version 7 name pages feed the bounded board before the per-address dedup. They are
        // only ever attached to a window offer that is currently heard, and only as an unconfirmed claim (NamePageAssembler).
        val windowOn = NamingFeatures.visibleByNameOn()
        if (windowOn && NamePageCodec.isNamePage(announcementBytes)) {
            NamePageCodec.decode(requireNotNull(announcementBytes))?.let(NameWindowBoard::onPage)
            return
        }
        var twinForm: AdvertTwinForm? = null
        val announcement = if (announcementBytes != null) {
            runCatching { DiscoveryAnnouncementCodec.decode(announcementBytes, acceptRotating, acceptWindow = windowOn) }
                .getOrNull()
                ?: run {
                    stats.undecodable++
                    return
                }
        } else {
            // Experimental: an iPhone cannot send manufacturer data; its one service UUID is the
            // announcement. The 22-byte form always wins when both are present; then the compact UUID; then the
            // "Find PCs" service data (Design QW).
            val compact = if (compactDiscovery) {
                CompactDiscoveryAnnouncement.firstCandidate(result.scanRecord?.serviceUuids?.map(ParcelUuid::getUuid))
            } else {
                null
            }
            val pcData = if (pcDiscovery) result.scanRecord?.getServiceData(ParcelUuid(PcAnnouncement.UUID)) else null
            when (val outcome = ScanRecordForms.resolve(compact, pcData, acceptRotating)) {
                ScanRecordForms.Outcome.None -> {
                    stats.withoutAnnouncement++
                    return
                }
                ScanRecordForms.Outcome.CompactInvalid -> {
                    stats.compactInvalid++
                    return
                }
                is ScanRecordForms.Outcome.Compact -> {
                    stats.compact++
                    if (outcome.announcement.isPrivateCallOffer()) {
                        twinForm = AdvertTwinAds.compared(result.scanRecord?.bytes)?.let { compared ->
                            AdvertTwinForm(result.isLegacy, !result.isLegacy && result.secondaryPhy != 0, compared)
                        }
                    }
                    outcome.announcement
                }
                is ScanRecordForms.Outcome.Pc -> {
                    stats.pc++
                    PcCallTargets.record(outcome.announcement.nodeIdPrefix, android.os.SystemClock.elapsedRealtime())
                    logPcDecoded(result, outcome.decoded)
                    outcome.announcement
                }
                is ScanRecordForms.Outcome.PcStream -> {
                    stats.pc++
                    outcome.announcement
                }
                ScanRecordForms.Outcome.PcDisabled -> {
                    stats.pcDisabled++
                    return
                }
                is ScanRecordForms.Outcome.PcInvalid -> {
                    stats.pcInvalid++
                    logPcRejected(result, outcome.error, pcData)
                    return
                }
            }
        }
        if (!acceptAnnouncement(announcement)) {
            stats.rejected++
            if (BuildConfig.DEBUG) {
                rejectLog.line(stats.scanner, result.device.address, announcement, result.rssi)
                    ?.let { android.util.Log.i("BluetoothVideo", "BENCHMARK $it") }
            }
            return
        }
        stats.accepted++
        if (announcement.isWindowOffer) NameWindowBoard.onOffer(announcement)
        val device = result.device
        // A phone bonded to the advertiser resolves its RPA with the stored IRK and reports the identity address
        // (possibly the public one): that is resolution, not a leak, so the address lines say whether we are bonded.
        // Asked only when a DEBUG line is written: bondState is a binder call, not one per scan result.
        logAddressChurn(announcement, device.address, result.rssi) {
            runCatching { device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        }
        // Experimental relayed calls: every sighting of a cost-0 call offer refreshes the reach table
        // (before the duplicate filter, like PeerAddressBook). Off: nothing runs.
        if (RelayedCallsFeature.enabled && announcement.routeCost == 0 && announcement.isPrivateCallOffer()) {
            RelayedCallsRuntime.onScanOffer(BleL2capPeer(device, "", announcement), device.address, result.rssi)
        }
        // Experimental test_peer_address_book: every sighting (before the per-address dedup) refreshes its node.
        if (PeerAddressBook.enabled()) {
            PeerAddressBook.shared.record(PeerAddressBook.prefixKey(announcement.nodeIdPrefix), device.address, announcement.routeCost)
        }
        val firstResult = seenAddresses.add(device.address)
        if (!firstResult && !reportDuplicates) return
        val name = deviceNames.getOrPut(device.address) {
            result.scanRecord?.deviceName
                ?: runCatching { device.name }.getOrNull()
                ?: "Nearby device"
        }
        // A rotating ID is not a handle and never names the phone (design 5.4).
        val nodeLabel = if (announcement.rotating) {
            "rotating ID"
        } else {
            announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }
        }
        onPeer(
            BleL2capPeer(
                device,
                "$name [$nodeLabel] (${device.address})",
                announcement,
                advertisedName = name.takeUnless { it == "Nearby device" },
                twinForm = twinForm,
            ),
        )
    }

    /**
     * Phase 0 (rotating discovery IDs, DEBUG only): the advertising address each announced node
     * uses, logged when first seen and whenever it changes. Joined offline with the advertiser's
     * `adv_set_churn` lines (tools/rotid_phase0.py), it shows whether a set restart gives a new
     * address on each phone and when the stack's own timer rotates it.
     */
    private val lastAddressByNode = HashMap<String, String>()
    private val lastNodeByAddress = HashMap<String, String>()

    private fun logAddressChurn(announcement: DiscoveryAnnouncement, address: String, rssi: Int, bonded: () -> Boolean) {
        if (!BuildConfig.DEBUG) return
        val node = announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }
        // Hardening: a new rotating ID under an address that announced another one is the legacy-controller defect
        // (the address survived the restart); tools/rotid_tracker.py counts it per phone from the same sightings.
        if (announcement.rotating) {
            if (lastNodeByAddress.size > 2_000) lastNodeByAddress.clear()
            val before = lastNodeByAddress.put(address, node)
            if (before != null && before != node) {
                android.util.Log.i(
                    "BluetoothVideo",
                    "BENCHMARK rotid_address_reuse: event=observed | scanner=${stats.scanner} | address=$address | node=$node | " +
                        "previousNode=$before | wallMs=${System.currentTimeMillis()} | bonded=${bonded()}",
                )
            }
        }
        val previous = lastAddressByNode.put(node, address)
        if (previous == address) return
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_address_seen: scanner=${stats.scanner} | node=$node | version=${announcement.version} | " +
                "address=$address | previous=${previous ?: "none"} | elapsedMs=${SystemClock.elapsedRealtime()} | " +
                "wallMs=${System.currentTimeMillis()} | rssi=$rssi | bonded=${bonded()}",
        )
    }

    /**
     * DEBUG: scan evidence for discovery failures. A scanner with zero raw results points below the
     * app (the A15s stack after its GattService exception, run3d); results without the announcement
     * point at a missing scan response; rejected ones at the announcement filter.
     */
    private fun logScanStats() {
        if (!running.get()) return
        stats.logIfDebug("running mode=$activeScanMode")
        handler.postDelayed(::logScanStats, SCAN_STATS_INTERVAL_MS)
    }

    /** Review B11: start and stop of the scan are ordered, so a stop never runs between a start's check and its startScan. */
    private val scanLock = Any()

    @SuppressLint("MissingPermission")
    private fun stop(notify: Boolean) {
        val halted = synchronized(scanLock) {
            halt().also { if (it) runCatching { adapter.bluetoothLeScanner?.stopScan(callback) } }
        }
        if (!halted) return
        stats.logIfDebug("stopped")
        handler.removeCallbacksAndMessages(null)
        if (!notify) return
        val unrecovered = failureCode
        if (unrecovered != null && seenAddresses.isEmpty()) {
            // A timed scan whose failure never recovered still reports the failure, as before.
            logRecovery("gave_up", unrecovered, ScanRecoveryStep(ScanFailurePolicy.action(unrecovered), -1L, handBack = true))
            onError(ScanFailurePolicy.userMessage(unrecovered))
        } else {
            onFinished(seenAddresses.size)
        }
    }

    fun isLowLatency(): Boolean = running.get() && activeScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY

    /** The system scan mode this scanner runs now, for the DEBUG dial lines (JT2): `none` while it is not running. */
    fun scanModeLabel(): String = if (!running.get()) "none" else when (activeScanMode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "low_latency"
        ScanSettings.SCAN_MODE_LOW_POWER -> "low_power"
        ScanSettings.SCAN_MODE_BALANCED -> "balanced"
        else -> "other($activeScanMode)"
    }

    override fun close() = stop(notify = false)

    private companion object {
        const val DEFAULT_SCAN_DURATION_MS = 8_000L
        const val LOW_LATENCY_SCAN_BURST_MS = 8_000L
        const val SCAN_STATS_INTERVAL_MS = 10_000L
    }
}

/**
 * DEBUG (F11): one line per address the first time a scanner decodes its announcement and rejects it, with what the
 * acceptance rules look at, so a phone that never reaches another can tell "heard but refused" (and why) from "never
 * heard". At most [max] lines per scanner. Main thread only.
 */
internal class ScanRejectLog(private val max: Int = 24) {
    private val seen = HashSet<String>()

    fun line(scanner: String, address: String, announcement: DiscoveryAnnouncement, rssi: Int): String? {
        if (seen.size >= max || !seen.add(address)) return null
        return "scan_reject: scanner=$scanner | address=$address | version=${announcement.version} | " +
            "rotating=${announcement.rotating} | callOffer=${announcement.isPrivateCallOffer()} | " +
            "window=${announcement.isWindowOffer} | cost=${announcement.routeCost} | " +
            "flags=0x%02x | id=%s | rssi=%d".format(
                announcement.flags,
                announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) },
                rssi,
            )
    }
}

/** Counts of one discovery scan's results (main-thread callbacks; DEBUG log only). */
internal class ScanStats(
    val scanner: String,
    private val compactDiscovery: Boolean = false,
    private val pcDiscovery: Boolean = false,
) {
    @Volatile var raw = 0
    @Volatile var withoutAnnouncement = 0
    @Volatile var rejected = 0
    @Volatile var accepted = 0
    /** Experimental compact discovery: decoded iPhone UUIDs, and magic-matching UUIDs that failed. */
    @Volatile var compact = 0
    @Volatile var compactInvalid = 0
    /** Experimental "Find PCs": decoded PC service-data announcements and rejected ones (never the compact counters). */
    @Volatile var pc = 0
    @Volatile var pcInvalid = 0
    @Volatile var pcDisabled = 0
    /** Manufacturer data of this app that did not decode (before F11 these left no trace). */
    @Volatile var undecodable = 0
    private var startedMs = System.currentTimeMillis()

    fun reset() {
        raw = 0
        withoutAnnouncement = 0
        rejected = 0
        accepted = 0
        compact = 0
        compactInvalid = 0
        pc = 0
        pcInvalid = 0
        pcDisabled = 0
        undecodable = 0
        startedMs = System.currentTimeMillis()
    }

    /** Unchanged with compact discovery and "Find PCs" off; on, each appends its own two counters. */
    fun line(state: String): String =
        "scan_stats: scanner=$scanner | state=$state | " +
            "elapsedMs=${System.currentTimeMillis() - startedMs} | raw=$raw | " +
            "withoutAnnouncement=$withoutAnnouncement | rejected=$rejected | accepted=$accepted" +
            (if (compactDiscovery) " | compact=$compact | compactInvalid=$compactInvalid" else "") +
            (if (pcDiscovery) " | pc=$pc | pcInvalid=$pcInvalid | pcDisabled=$pcDisabled" else "") +
            (if (undecodable > 0) " | undecodable=$undecodable" else "")

    fun logIfDebug(state: String) {
        if (BuildConfig.DEBUG) android.util.Log.i("BluetoothVideo", "BENCHMARK " + line(state))
    }
}

/** What a production scanner does after [android.bluetooth.le.ScanCallback.onScanFailed]. */
internal enum class ScanFailureAction(val label: String) {
    /** Stop the still-registered scan, then start again after the back-off. */
    RESTART("restart"),

    /** Start again after the back-off delay. */
    BACK_OFF("back_off"),

    /** Not retryable: stop and tell the user. */
    REPORT("report"),
}

/** One decision: wait [delayMs] and retry, or hand the failure back to the caller ([handBack]). */
internal data class ScanRecoveryStep(val action: ScanFailureAction, val delayMs: Long, val handBack: Boolean)

/**
 * Failure codes of android.bluetooth.le.ScanCallback (AOSP framework constants 1-6; 5 and 6 are public
 * from API 33, so they are literals here). Every code but FEATURE_UNSUPPORTED can be transient;
 * SCANNING_TOO_FREQUENTLY is listed for completeness: AOSP (Android 8-16) never delivers it through onScanFailed,
 * it drops the start silently, so no back-off here can react to the quota. The registry's ScanStartGate
 * (le-scan-start-quota) is the only protection, and ScanHealth names a silent scan with a full budget
 * `scan_quota_silent`.
 */
internal object ScanFailurePolicy {
    const val ALREADY_STARTED = 1
    const val APPLICATION_REGISTRATION_FAILED = 2
    const val INTERNAL_ERROR = 3
    const val FEATURE_UNSUPPORTED = 4
    const val OUT_OF_HARDWARE_RESOURCES = 5
    const val SCANNING_TOO_FREQUENTLY = 6

    fun action(code: Int): ScanFailureAction = when (code) {
        ALREADY_STARTED -> ScanFailureAction.RESTART
        FEATURE_UNSUPPORTED -> ScanFailureAction.REPORT
        // Registration failures, internal errors, out of resources, the start quota and unknown codes.
        else -> ScanFailureAction.BACK_OFF
    }

    /**
     * The next step after a failure with [code]. Retryable failures wait 3 s x n (capped at 30 s). A
     * continuous scan (no duration) hands the failure back to its caller once the back-off reaches the
     * cap, so the user sees it; a timed scan keeps retrying until its duration ends.
     */
    fun step(code: Int, backoff: ScanRecoveryBackoff, continuous: Boolean): ScanRecoveryStep {
        val action = action(code)
        if (action == ScanFailureAction.REPORT) return ScanRecoveryStep(action, -1L, handBack = true)
        val delayMs = backoff.nextDelayMs()
        return ScanRecoveryStep(action, delayMs, handBack = continuous && delayMs >= backoff.capMs)
    }

    fun name(code: Int): String = when (code) {
        ALREADY_STARTED -> "already_started"
        APPLICATION_REGISTRATION_FAILED -> "application_registration_failed"
        INTERNAL_ERROR -> "internal_error"
        FEATURE_UNSUPPORTED -> "feature_unsupported"
        OUT_OF_HARDWARE_RESOURCES -> "out_of_hardware_resources"
        SCANNING_TOO_FREQUENTLY -> "scanning_too_frequently"
        else -> "unknown"
    }

    fun userMessage(code: Int): String = when (code) {
        FEATURE_UNSUPPORTED -> "This phone's Bluetooth does not support the BLE scan the app needs (code $code)."
        else -> "BLE scan failed (code $code)."
    }
}

/** 3 s x consecutive failures, capped at 30 s; reset by any scan result or a fresh start. */
internal class ScanRecoveryBackoff(
    private val stepMs: Long = STEP_MS,
    val capMs: Long = CAP_MS,
) {
    var failures = 0
        private set

    fun nextDelayMs(): Long {
        failures++
        return minOf(stepMs * failures, capMs)
    }

    fun reset() {
        failures = 0
    }

    companion object {
        const val STEP_MS = 3_000L
        const val CAP_MS = 30_000L
    }
}

class BleL2capControlServer(
    context: Context,
    private val adapter: BluetoothAdapter,
    psm: Int,
    private val announcement: DiscoveryAnnouncement,
    private val onLinkStatus: (String) -> Unit,
    private val phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
) : Closeable {
    private val appContext = context.applicationContext
    private val psmValue = BleL2capControlProtocol.encodePsm(psm)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    init {
        if (adapter.bluetoothLeAdvertiser == null) throw IOException("This phone cannot advertise over BLE")
    }
    private val advertiseHandler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    private var serviceLatch: CountDownLatch? = null
    private var serviceStatus = BluetoothGatt.GATT_FAILURE
    private val closed = AtomicBoolean(false)
    @Volatile private var advertising = false

    /** Keeps its random address through the low-latency -> balanced step (StableAdvertisingSet). */
    @Volatile private var advertisement = newAdvertisement(announcement)

    private fun newAdvertisement(value: DiscoveryAnnouncement) = StableAdvertisingSet(
        adapter,
        connectable = true,
        data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleL2capControlProtocol.SERVICE_UUID))
            .build(),
        scanResponse = AdvertiseData.Builder()
            .addManufacturerData(
                BleL2capControlProtocol.MESH_MANUFACTURER_ID,
                DiscoveryAnnouncementCodec.encode(value),
            )
            .build(),
        label = "BLE control",
        onStatus = onLinkStatus,
        onLost = ::onAdvertisingLost,
        nonDiscoverable = NonDiscoverableAdvertising.forNewSet(connectable = true),
        neutralRole = DiscoverableWhenNeutral.roleOf(value),
    )

    /**
     * Rotating IDs (RV6): while this server still advertises, stop the set, stay silent for [silenceMs]
     * and advertise [next] on a new set (new address). The GATT service and PSM stay. A server whose
     * advertising already stopped (a connection was accepted) only remembers [next] for a later start.
     */
    @SuppressLint("MissingPermission")
    fun reannounce(next: DiscoveryAnnouncement, silenceMs: Long) {
        if (closed.get()) return
        val wasAdvertising = synchronized(this) {
            val advertisingNow = advertising
            if (advertisingNow) stopAdvertising()
            advertisement = newAdvertisement(next)
            advertisingNow
        }
        if (!wasAdvertising) return
        try {
            Thread.sleep(silenceMs)
        } catch (_: InterruptedException) {
            return
        }
        if (closed.get()) return
        runCatching { startAdvertising(lowLatency = false) }
            .onFailure { onLinkStatus("BLE control could not advertise the new ID: ${it.message}") }
    }

    @Volatile var phySummary: String = "PHY pending"
        private set

    private val centrals = BoundaryRestartPlan.ConnectedCentrals()

    /** R1 C9 on LE CoC: a central is connected to the control service, so closing this server now could orphan its call setup. */
    fun linkInFlight(): Boolean = !closed.get() && centrals.any()

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != BleL2capControlProtocol.SERVICE_UUID) return
            serviceStatus = status
            serviceLatch?.countDown()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) centrals.connected(device.address)
            if (newState == BluetoothProfile.STATE_DISCONNECTED) centrals.disconnected(device.address)
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                val server = gattServer ?: return
                // The client requests the preferred PHY once its control setup is done (BleL2capPsmReader).
                // A server-initiated update here crossed the client's own request: 339 "BLE PHY
                // unavailable (status 42)" (Different Transaction Collision) lines on M31, ASUS and A15s
                // in runs since 2026-10-07, the same collision the GATT video path removed in 2cbdafb1.
                if (!DebugTunables.cocSequencedPhy && !DebugTunables.cocPhyAfterConnect) {
                    server.setPreferredPhy(
                        device,
                        phyPreference.mask,
                        phyPreference.mask,
                        phyPreference.option,
                    )
                }
                server.readPhy(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                phySummary = "PHY pending"
            }
        }

        /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            if (closed.get()) return
            LeConnectionParameterLog.record(
                "ble-control", LeLinkRole.PERIPHERAL, device.address, interval, latency, timeout, status,
            )
        }

        override fun onPhyUpdate(
            device: BluetoothDevice,
            txPhy: Int,
            rxPhy: Int,
            status: Int,
        ) = updatePhy(txPhy, rxPhy, status)

        override fun onPhyRead(
            device: BluetoothDevice,
            txPhy: Int,
            rxPhy: Int,
            status: Int,
        ) = updatePhy(txPhy, rxPhy, status)

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid != BleL2capControlProtocol.PSM_CHARACTERISTIC_UUID) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            if (offset !in 0..psmValue.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            server.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                psmValue.copyOfRange(offset, psmValue.size),
            )
        }
    }

    private fun updatePhy(txPhy: Int, rxPhy: Int, status: Int) {
        phySummary = if (status == BluetoothGatt.GATT_SUCCESS) {
            "PHY TX ${blePhyName(txPhy)} / RX ${blePhyName(rxPhy)}"
        } else {
            "PHY unavailable (status $status)"
        }
        onLinkStatus("BLE $phySummary")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        check(!closed.get()) { "BLE control server is closed" }
        check(gattServer == null) { "BLE control server is already running" }
        EncoderBenchGuard.check("BleL2capControl.gattServer")
        val server = bluetoothManager.openGattServer(appContext, gattCallback)
        if (server == null) {
            if (adapter.isEnabled) GattPoolBackoff.recordFailure(PoolSource.SERVER, null, "ble-control-server")
            throw IOException("Could not open the BLE control server")
        }
        GattPoolBackoff.recordSuccess(PoolSource.SERVER)
        gattServer = server
        try {
            startOpened(server)
        } catch (error: Throwable) {
            // `cocControlServer = BleL2capControlServer(..).also(start)` never receives this instance when start()
            // throws, so the opened server would keep its registration until process death. close() is idempotent.
            close()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOpened(server: BluetoothGattServer) {
        val characteristic = BluetoothGattCharacteristic(
            BleL2capControlProtocol.PSM_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(
            BleL2capControlProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply { addCharacteristic(characteristic) }
        val latch = CountDownLatch(1)
        serviceLatch = latch
        serviceStatus = BluetoothGatt.GATT_FAILURE
        if (!server.addService(service)) throw IOException("Could not register the BLE control service")
        if (!latch.await(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Timed out registering the BLE control service")
        }
        serviceLatch = null
        if (serviceStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("BLE control service failed to register (status $serviceStatus)")
        }
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() = startAdvertising(lowLatency = true)

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAdvertising(lowLatency: Boolean) {
        check(!closed.get()) { "BLE control server is closed" }
        if (advertising) return
        val status = advertisement.start(lowLatency, CONTROL_TIMEOUT_SECONDS)
            ?: throw IOException("Timed out starting BLE advertising")
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) throw IOException("BLE advertising failed (code $status)")
        if (closed.get()) {
            advertisement.stop()
            return
        }
        advertising = true
        if (lowLatency && !closed.get()) {
            advertiseHandler.postDelayed(::switchToBalancedAdvertising, LOW_LATENCY_ADVERTISE_BURST_MS)
        }
    }

    /** A failed fallback left nothing advertising: clear the state and start once more. */
    private fun onAdvertisingLost(reason: String) {
        advertising = false
        if (closed.get()) return
        thread(name = "BleControlReadvertise") {
            runCatching { if (!closed.get()) startAdvertising(lowLatency = false) }
                .onFailure { onLinkStatus("BLE control is not advertising ($reason); restart failed: ${it.message}") }
        }
    }

    private fun switchToBalancedAdvertising() {
        // F11: while a verification code is shown the set stays low-latency / high power; checked again later.
        when (CallReadyAdvertisingHold.decideControlListener(closed = closed.get(), boostHolding = AdvertisingBoost.holding())) {
            CallReadyAdvertisingHold.Action.IGNORE -> return
            CallReadyAdvertisingHold.Action.RETRY_LATER -> {
                advertiseHandler.postDelayed(::switchToBalancedAdvertising, LOW_LATENCY_ADVERTISE_BURST_MS)
                return
            }
            else -> Unit
        }
        // Same set, same address: a peer that just discovered it can still connect.
        if (DebugTunables.stableAdvertisingAddress && advertising && advertisement.switchToBalanced()) return
        thread(name = "BleControlAdvertiseBalance") {
            runCatching {
                synchronized(this) {
                    if (closed.get() || !advertising) return@synchronized
                    stopAdvertising()
                    startAdvertising(lowLatency = false)
                }
            }.onFailure { onLinkStatus("BLE balanced advertising failed: ${it.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun stopAdvertising() {
        advertiseHandler.removeCallbacksAndMessages(null)
        if (!advertising) return
        advertisement.stop()
        advertising = false
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        advertiseHandler.removeCallbacksAndMessages(null)
        stopAdvertising()
        runCatching { gattServer?.close() }
        gattServer = null
    }

    private companion object {
        const val CONTROL_TIMEOUT_SECONDS = 8L
        const val LOW_LATENCY_ADVERTISE_BURST_MS = 10_000L
    }
}

class BleL2capControlConnection internal constructor(
    val psm: Int,
    private val gatt: BluetoothGatt,
    private val phyStatus: BlePhyStatus,
    private val attempt: GattAttempt = GattAttempt(),
    private val adaptivePhy: AdaptivePhyLink? = null,
    private val phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
    /** test_coc_phy_after_connect: setup requested no PHY; [requestPhyAfterConnect] does. */
    private val phyDeferred: Boolean = false,
    /** The setup's priority keeper, so a later request keeps the failed-update retry (test_relay_upstream_dck). */
    private val priorityRequest: ((BleConnectionPriority) -> Boolean)? = null,
    /** The setup's LL procedure gate (Experimental test_ll_gate); a pass-through when off. */
    private val gate: LlProcedureGate? = null,
) : Closeable {
    val phySummary: String
        get() = phyStatus.summary

    /** Requests [priority] again on this control link (false when the setup kept no keeper or it is closed). */
    internal fun requestConnectionPriority(priority: BleConnectionPriority): Boolean =
        attempt.active && priorityRequest?.invoke(priority) == true

    private val deferredPhyRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Experimental (`test_coc_phy_after_connect`): requests the preferred PHY once the CoC socket is
     * connected and hop authentication finished, and blocks (at most ~1.5 s, never on the main thread)
     * until onPhyUpdate or the readPhy fallback reports it. No-op when the setup already requested the
     * PHY (the default arms) or when called again. Logs `BENCHMARK link_phy: link=<link> | requested=...
     * | effective=... | reason=after_connect`.
     */
    @SuppressLint("MissingPermission")
    internal fun requestPhyAfterConnect(link: String): CocPhyAfterConnect.Result? {
        if (!phyDeferred || !attempt.active || !deferredPhyRequested.compareAndSet(false, true)) return null
        val waiter = CocPhyAfterConnect(
            requestPhy = {
                gatt.setPreferredPhy(phyPreference.mask, phyPreference.mask, phyPreference.option)
                true
            },
            readPhy = {
                gatt.readPhy()
                true
            },
            nowMs = android.os.SystemClock::elapsedRealtime,
            log = { line -> android.util.Log.i("BluetoothVideo", line) },
            expectedTx = LePhy.valueForMask(phyPreference.mask),
            gate = gate,
        )
        phyStatus.listener = { tx, status, fromRead -> waiter.onPhy(tx, status, fromRead) }
        return try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // Never block the main thread: request only, and let the callbacks report the PHY.
                gatt.setPreferredPhy(phyPreference.mask, phyPreference.mask, phyPreference.option)
                android.util.Log.i(
                    "BluetoothVideo",
                    "BENCHMARK link_phy: link=$link | requested=${phyPreference.label} | effective=pending | " +
                        "reason=after_connect | source=none | readFallback=false | waitedMs=0 | skipped=main-thread",
                )
                null
            } else {
                waiter.run(link, phyPreference.label, "after_connect")
            }
        } finally {
            phyStatus.listener = null
        }
    }

    /**
     * Experimental `test_slow_2m_in_place` ([SlowTwoMInPlace]): asks this live LE link (the ACL the CoC socket rides on) for
     * [preference] now, through the setup's LL procedure gate, and reads the PHY back so [phySummary] follows the move. True when
     * the request was started or queued; false when this control link is closed or the stack refused it.
     */
    @SuppressLint("MissingPermission")
    internal fun requestPhyInPlace(preference: BlePhyPreference, label: String = "call_slow_2m_in_place"): Boolean {
        if (!attempt.active) return false
        val start: () -> Boolean = {
            runCatching {
                gatt.setPreferredPhy(preference.mask, preference.mask, preference.option)
                gatt.readPhy()
            }.isSuccess
        }
        val gate = gate ?: return start()
        return gate.submit(LlProcedure.PHY, label, expectedTx = LePhy.valueForMask(preference.mask), start = start).accepted
    }

    /** Reads this link's PHY (no LL procedure); the result updates [phySummary]. */
    @SuppressLint("MissingPermission")
    internal fun readPhyNow(): Boolean = attempt.active && runCatching { gatt.readPhy() }.isSuccess

    init {
        val address = gatt.device?.address
        if (adaptivePhy != null && address != null) AdaptivePhyRegistry.register(address, adaptivePhy)
        LeChildInterval.register(address, gatt)
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        val address = gatt.device?.address
        if (adaptivePhy != null && address != null) AdaptivePhyRegistry.unregister(address, adaptivePhy)
        // Cancelled first, so its late callbacks do nothing; Android 10/11 close after the disconnect.
        attempt.disconnectAndClose(gatt, "ble-control")
    }
}

internal class BlePhyStatus(private val onLinkStatus: (String) -> Unit) {
    @Volatile var summary: String = "PHY pending"
        private set

    /** test_coc_phy_after_connect waiter: (txPhy, status, fromRead). */
    @Volatile var listener: ((Int, Int, Boolean) -> Unit)? = null

    fun update(txPhy: Int, rxPhy: Int, status: Int, fromRead: Boolean = false) {
        summary = if (status == BluetoothGatt.GATT_SUCCESS) {
            "PHY TX ${blePhyName(txPhy)} / RX ${blePhyName(rxPhy)}"
        } else {
            "PHY unavailable (status $status)"
        }
        onLinkStatus("BLE $summary")
        listener?.invoke(txPhy, status, fromRead)
    }
}

private fun blePhyName(phy: Int): String = when (phy) {
    BluetoothDevice.PHY_LE_1M -> "1M"
    BluetoothDevice.PHY_LE_2M -> "2M"
    BluetoothDevice.PHY_LE_CODED -> "Coded"
    else -> "unknown($phy)"
}

object BleL2capPsmReader {
    fun open(
        context: Context,
        device: BluetoothDevice,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
        connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
        primeGattMtuForCoc: Boolean = true,
        /** Relay child connect only: Experimental RelayEstablishBudget (test_relay_establish_budget). */
        establishBudget: Boolean = false,
        /** The caller's setup deadline (a call's or message's dial), null when unbounded. */
        deadline: SetupDeadline? = null,
        /** Experimental psm_read_link_quiet: the link only reads the PSM and is closed for a fresh ACL ([PsmReadLinkQuiet]). */
        quietLink: Boolean = false,
    ): BleL2capControlConnection =
        openWithRetries(
            context, device, onLinkStatus, phyPreference, connectionPriority, primeGattMtuForCoc, null, establishBudget, deadline,
            quietLink = quietLink,
        )

    /**
     * Attaches a GATT client to an LE link whose CoC channel is already open (BleCocClient fresh ACL):
     * no service discovery or PSM read, only the link priority and, after [ATTACH_PHY_DELAY_MS], the PHY.
     */
    fun attach(
        context: Context,
        device: BluetoothDevice,
        psm: Int,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference = BlePhyPreference.TWO_M,
        connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
        deadline: SetupDeadline? = null,
    ): BleL2capControlConnection = if (DebugTunables.cocAttachSingle) {
        // Review T5 (2026-10-27): the CoC socket is already connected; this GATT client only adds priority and PHY control.
        // One attempt with a short bound, so a failing reattach costs at most ATTACH_TIMEOUT_MS, not 3 x 6 s plus retries.
        openOnce(context, device, onLinkStatus, phyPreference, connectionPriority, false, psm, timeoutMs = ATTACH_TIMEOUT_MS)
    } else {
        openWithRetries(context, device, onLinkStatus, phyPreference, connectionPriority, false, psm, deadline = deadline)
    }

    private fun openWithRetries(
        context: Context,
        device: BluetoothDevice,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
        primeGattMtuForCoc: Boolean,
        knownPsm: Int?,
        establishBudget: Boolean = false,
        deadline: SetupDeadline? = null,
        config: ConnectRetryConfig = ConnectRetryConfig.current(),
        quietLink: Boolean = false,
    ): BleL2capControlConnection {
        var linkReached = false
        val startedMs = SystemClock.elapsedRealtime()
        val attempts = ConnectAttempts(
            ConnectAttempts.Link.BLE_CONTROL, config, deadline, startedMs, establishBudget, device.address.takeLast(5),
        )
        while (true) {
            val attemptStartedMs = SystemClock.elapsedRealtime()
            try {
                val connection = openOnce(
                    context,
                    device,
                    onLinkStatus,
                    phyPreference,
                    connectionPriority,
                    primeGattMtuForCoc,
                    knownPsm,
                    timeoutMs = attempts.attemptTimeoutMs(attemptStartedMs),
                    connectPhaseMs = attempts.connectPhaseMs(),
                    quietLink = quietLink,
                )
                attempts.successLines(attemptStartedMs, SystemClock.elapsedRealtime()).forEach { android.util.Log.i("BluetoothVideo", it) }
                GattPoolBackoff.recordSuccess(PoolSource.CLIENT)
                return connection
            } catch (error: IOException) {
                if (error is LinkReachedSetupFailure) linkReached = true
                GattPoolBackoff.noteConnectFailure(error.message, "ble-control-client")
                GattNativeClientWedge.onCentralConnectFailure(
                    error.message,
                    SystemClock.elapsedRealtime() - attemptStartedMs,
                    "ble-control-client",
                )
                val retryable = runCatching { BluetoothTransportPolicy.retryableConnectStatuses() }
                    .getOrDefault(setOf(BluetoothTransportPolicy.GATT_ERROR_133))
                val nowMs = SystemClock.elapsedRealtime()
                // One decision (ConnectAttempts): relay establish budget, Experimental fast-fail redial, ordinary rule.
                val decision = attempts.onFailure(
                    error,
                    attempts.classify(error.message, nowMs - attemptStartedMs),
                    attemptStartedMs,
                    nowMs,
                    classifiedRetryable = isRetryableBleControlFailure(error.message, retryable),
                    linkReached = linkReached,
                )
                decision.lines.forEach { android.util.Log.i("BluetoothVideo", it) }
                when (decision) {
                    is ConnectAttempts.Decision.GiveUp -> throw decision.error
                    is ConnectAttempts.Decision.Dial -> {
                        onLinkStatus(decision.notice)
                        Thread.sleep(decision.delayMs)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openOnce(
        context: Context,
        device: BluetoothDevice,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
        primeGattMtuForCoc: Boolean,
        knownPsm: Int?,
        timeoutMs: Long = ConnectAttempts.Link.BLE_CONTROL.attemptTimeoutMs,
        /** Experimental unified policy only: the attempt ends if the control link is not connected after this long. */
        connectPhaseMs: Long? = null,
        /** Experimental psm_read_link_quiet: a PSM-read link closed for a fresh ACL requests no priority, PHY or MTU. */
        quietLink: Boolean = false,
    ): BleL2capControlConnection {
        val requests = PsmReadLinkQuiet.requests(quietLink, knownPsm != null, primeGattMtuForCoc)
        val result = AtomicReference<Int?>()
        val error = AtomicReference<String?>()
        val complete = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val phyStatus = BlePhyStatus(onLinkStatus)
        val serviceDiscoveryStarted = AtomicBoolean(false)
        val cacheRefreshAttempted = AtomicBoolean(false)
        val pendingPsm = AtomicReference<Int?>()
        val attempt = GattAttempt()
        val phyRequested = AtomicBoolean(false)
        // Experimental relay_establish_keep_2m: set once the control connection reports connected.
        val linkConnected = AtomicBoolean(false)
        // test_coc_phy_after_connect: no PHY request during setup; the caller requests it after hop auth.
        val phyDeferred = CocPhyAfterConnect.enabled()
        val activeGattRef = AtomicReference<BluetoothGatt?>()
        val adaptivePhyRef = AtomicReference<AdaptivePhyLink?>()
        // Experimental test_ll_gate (P-01): one MTU, discovery, PHY or connection-update request in flight on this link.
        // A pass-through (requests start at once, as before) unless the switch is on.
        val gate = newLinkGate("ble-control", LeLinkRole.CENTRAL, device.address)
        fun adaptivePhy(gatt: BluetoothGatt): AdaptivePhyLink {
            adaptivePhyRef.get()?.let { return it }
            adaptivePhyRef.compareAndSet(null, AdaptivePhyLink("coc", gatt.device?.address, gatt, restorePriority = connectionPriority, gate = gate))
            return adaptivePhyRef.get()!!
        }
        val linkDiag = LeLinkDiagnostics.create("ble-control", device.address).also { it.requestedPhy = phyPreference.label }
        // Experimental phy_after_priority (off): the setup PHY waits while the link sits outside the requested priority window.
        val phyHold = PhyAfterPriority(
            link = "ble-control",
            schedule = { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
            nowMs = SystemClock::elapsedRealtime,
            log = { line -> android.util.Log.i("BluetoothVideo", line) },
        )
        val priorityKeeper = LeConnectionPriorityKeeper(
            link = "ble-control",
            sendRequest = { priority -> activeGattRef.get()?.requestConnectionPriority(priority.requestValue) == true },
            schedule = { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
            // A closed or expired attempt is cancelled before its teardown (GattAttempt).
            active = { attempt.active && !attempt.teardown.disconnected },
            log = { line -> android.util.Log.i("BluetoothVideo", line) },
            gate = gate,
            phyHold = phyHold,
        )

        fun finish(psm: Int? = null, message: String? = null) {
            if (!complete.compareAndSet(false, true)) return
            result.set(psm)
            error.set(message)
            latch.countDown()
        }

        /**
         * Requests the preferred PHY once the control setup is done, after the connection-priority
         * update and the MTU exchange, as the GATT video client does (2cbdafb1). Requesting it at
         * connection beside the priority update, while the server requested its own, collided
         * (status 42 on the server in every M31/ASUS/A15s CoC setup). With the Experimental
         * phy_after_priority it also waits while the link is still outside the requested priority window
         * ([PhyAfterPriority]). A quiet PSM-read link ([PsmReadLinkQuiet]) never requests it.
         */
        fun requestSequencedPhy(gatt: BluetoothGatt) {
            if (requests.phy && !phyDeferred && DebugTunables.cocSequencedPhy && attempt.active && phyRequested.compareAndSet(false, true)) {
                phyHold.submit("ble_control_phy", isActive = { attempt.active }) {
                    gate.submit(
                        LlProcedure.PHY,
                        "ble_control_phy",
                        expectedTx = LePhy.valueForMask(phyPreference.mask),
                        fallback = { gatt.setPreferredPhy(LePhy.MASK_1M, LePhy.MASK_1M, phyPreference.option); gatt.readPhy(); true },
                    ) {
                        gatt.setPreferredPhy(phyPreference.mask, phyPreference.mask, phyPreference.option)
                        gatt.readPhy()
                        true
                    }
                }
            }
        }

        fun finishWithPsm(gatt: BluetoothGatt, psm: Int) {
            if (complete.get()) return
            requestSequencedPhy(gatt)
            linkDiag.log("setup")
            finish(psm = psm)
        }

        fun handleRead(gatt: BluetoothGatt, value: ByteArray, status: Int) {
            if (!attempt.active) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(message = "BLE channel read failed (status $status)")
                return
            }
            runCatching { BleL2capControlProtocol.decodePsm(value) }
                .onSuccess { psm ->
                    if (!requests.mtu) {
                        finishWithPsm(gatt, psm)
                        return@onSuccess
                    }
                    pendingPsm.set(psm)
                    // A peer whose last MTU change was followed by a lost link is asked for the smaller MTU (test_link_change_rollback).
                    val requestedMtu = LinkChangeRollbacks.mtuCap(AdaptivePhyMemory.peerKey(gatt.device?.address))
                        ?.coerceAtMost(MAX_ATT_MTU) ?: MAX_ATT_MTU
                    linkDiag.requestedMtu = requestedMtu
                    val mtuQueued = gate.submit(LlProcedure.MTU, "ble_control_mtu") { gatt.requestMtu(requestedMtu) }.accepted
                    onLinkStatus(
                        "BLE CoC setup requested GATT MTU $requestedMtu " +
                            if (mtuQueued) "queued" else "failed",
                    )
                    if (!mtuQueued) {
                        finishWithPsm(gatt, psm)
                        return@onSuccess
                    }
                    thread(name = "BleCocMtuFallback") {
                        if (!latch.await(MTU_PRIME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            onLinkStatus("BLE CoC setup continuing without an MTU callback")
                            finishWithPsm(gatt, psm)
                        }
                    }
                }
                .onFailure { finish(message = it.message ?: "Invalid BLE channel response") }
        }

        fun discoverServices(gatt: BluetoothGatt) {
            if (!attempt.active) return
            if (!serviceDiscoveryStarted.compareAndSet(false, true)) return
            if (!gate.submit(LlProcedure.SERVICE_DISCOVERY, "ble_control_discovery") { gatt.discoverServices() }.accepted) {
                finish(message = "Could not discover the BLE control service")
            }
        }

        val callback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!attempt.onConnectionState(newState == BluetoothProfile.STATE_DISCONNECTED, newState == BluetoothProfile.STATE_CONNECTED)) return
                if (newState == BluetoothProfile.STATE_DISCONNECTED) gate.onLinkClosed(status)
                when {
                    status != BluetoothGatt.GATT_SUCCESS ->
                        finish(message = "BLE control connection failed (status $status)")
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        linkConnected.set(true)
                        activeGattRef.set(gatt)
                        if (!requests.priority) {
                            // psm_read_link_quiet: this link is closed after the PSM read; it keeps the stack's own intervals.
                            android.util.Log.i("BluetoothVideo", PsmReadLinkQuiet.line(gatt.device?.address?.takeLast(5)))
                            onLinkStatus("BLE PSM-read link: no priority, PHY or MTU request (closed for a fresh ACL)")
                            discoverServices(gatt)
                            return
                        }
                        val highPriority = priorityKeeper.request(connectionPriority)
                        linkDiag.onPriority(connectionPriority, highPriority)
                        onLinkStatus(
                            (if (phyDeferred) "BLE deferred ${phyPreference.label} PHY until after connect / " else "BLE requested ${phyPreference.label} PHY / ") +
                                "${connectionPriority.label} priority " +
                                if (highPriority) "queued" else "failed",
                        )
                        if (!DebugTunables.cocSequencedPhy && !phyDeferred) {
                            // Control arm: the pre-2026-10-09 order (PHY beside the priority update).
                            gate.submit(LlProcedure.PHY, "ble_control_phy_at_connect", expectedTx = LePhy.valueForMask(phyPreference.mask)) {
                                gatt.setPreferredPhy(
                                    phyPreference.mask,
                                    phyPreference.mask,
                                    phyPreference.option,
                                )
                                gatt.readPhy()
                                true
                            }
                        }
                        if (knownPsm == null) {
                            discoverServices(gatt)
                        } else if (DebugTunables.cocAttachSingle) {
                            // Review T5: the attach returns at once (the CoC call or stream no longer waits 600 ms for
                            // it); the PHY request still follows the priority update by the same delay, off that path.
                            linkDiag.log("setup")
                            finish(psm = knownPsm)
                            thread(name = "BleCocAttachPhy", isDaemon = true) {
                                runCatching { Thread.sleep(ATTACH_PHY_DELAY_MS) }
                                if (attempt.active) requestSequencedPhy(gatt)
                            }
                        } else {
                            // Attach: the PHY request follows the priority update, as after a PSM read.
                            // A worker thread, not the main looper, so a caller's thread never matters.
                            thread(name = "BleCocAttachPhy", isDaemon = true) {
                                runCatching { Thread.sleep(ATTACH_PHY_DELAY_MS) }
                                if (attempt.active) finishWithPsm(gatt, knownPsm)
                            }
                        }
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED && !complete.get() ->
                        finish(message = "BLE receiver disconnected during channel discovery")
                }
            }

            override fun onPhyUpdate(
                gatt: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) {
                if (attempt.active) {
                    phyStatus.update(txPhy, rxPhy, status)
                    adaptivePhy(gatt).onPhyUpdate(txPhy, status)
                    linkDiag.onPhy(txPhy, rxPhy, status)
                    if (complete.get()) linkDiag.log("phy")
                }
            }

            override fun onPhyRead(
                gatt: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) {
                if (attempt.active) {
                    phyStatus.update(txPhy, rxPhy, status, fromRead = true)
                    adaptivePhy(gatt).onPhyRead(txPhy, status)
                    linkDiag.onPhy(txPhy, rxPhy, status)
                    if (complete.get()) linkDiag.log("phy")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (!attempt.active) return
                gate.onMtuChanged(status)
                linkDiag.onMtu(mtu, status)
                onLinkStatus(
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        "BLE CoC setup negotiated GATT MTU $mtu"
                    } else {
                        "BLE CoC setup GATT MTU request failed (status $status)"
                    },
                )
                pendingPsm.get()?.let { finishWithPsm(gatt, it) }
            }

            /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
            @Suppress("unused")
            fun onConnectionUpdated(gatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
                if (!attempt.active) return
                LeConnectionParameterLog.record(
                    "ble-control", LeLinkRole.CENTRAL, gatt.device?.address, interval, latency, timeout, status,
                )
                linkDiag.onParams(interval, latency, timeout, status)
                if (complete.get()) linkDiag.log("conn_params")
                priorityKeeper.onConnectionUpdated(status, interval)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (!attempt.active) return
                gate.onServicesDiscovered(status)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(message = "BLE service discovery failed (status $status)")
                    return
                }
                val characteristic = gatt
                    .getService(BleL2capControlProtocol.SERVICE_UUID)
                    ?.getCharacteristic(BleL2capControlProtocol.PSM_CHARACTERISTIC_UUID)
                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    characteristic == null &&
                    cacheRefreshAttempted.compareAndSet(false, true) &&
                    refreshGattServices(gatt)
                ) {
                    onLinkStatus("Refreshing a stale BLE control service cache")
                    serviceDiscoveryStarted.set(false)
                    Handler(Looper.getMainLooper()).postDelayed(
                        { if (!complete.get() && attempt.active) discoverServices(gatt) },
                        GATT_CACHE_REFRESH_DELAY_MS,
                    )
                    return
                }
                if (characteristic == null || !gatt.readCharacteristic(characteristic)) {
                    finish(message = "Receiver did not expose a BLE video channel")
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("Used on Android 12 and earlier")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                handleRead(gatt, characteristic.value ?: byteArrayOf(), status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) = handleRead(gatt, value, status)
        }

        EncoderBenchGuard.check("BleL2capControl.connectGatt")
        GattResourceRegistry.shared.dialWarnings(device.address).takeIf { it.isNotEmpty() }?.let { warnings ->
            android.util.Log.i("BluetoothVideo", "BENCHMARK gatt_dial_warning: owner=ble-control | warnings=${warnings.joinToString(",")}")
        }
        val gatt = GattConnectCompat.connect(
            device,
            context,
            callback,
            BluetoothDevice.TRANSPORT_LE,
            owner = "ble-control",
            phyMask = controlInitiatingPhys(phyPreference),
        ) ?: throw IOException("Could not start BLE channel discovery")
        try {
            if (!awaitControlSetup(latch, timeoutMs, connectPhaseMs, linkConnected::get)) {
                throw IOException(BLE_CONTROL_READ_TIMEOUT)
            }
            error.get()?.let { throw IOException(it) }
            val psm = result.get() ?: throw IOException("Receiver returned no BLE video channel")
            // The control link now backs a live call or stream: the registry must never treat it as an idle leak.
            GattResourceRegistry.shared.setActive(gatt, true)
            return BleL2capControlConnection(
                psm, gatt, phyStatus, attempt, adaptivePhy(gatt), phyPreference, phyDeferred,
                priorityRequest = { priority -> priorityKeeper.request(priority) },
                gate = gate,
            )
        } catch (error: Exception) {
            // Expired attempt: cancelled before teardown so a deferred close cannot overlap the retry.
            attempt.disconnectAndClose(gatt, "ble-control-setup")
            throw if (error is IOException) carryLinkReached(error, linkConnected.get()) else error
        }
    }

    /**
     * Waits for the control setup for [timeoutMs]. With [connectPhaseMs] (Experimental unified connect policy) the wait
     * ends early when the link has not reported connected by then. True when the setup finished.
     */
    internal fun awaitControlSetup(latch: CountDownLatch, timeoutMs: Long, connectPhaseMs: Long?, connected: () -> Boolean): Boolean {
        if (connectPhaseMs == null || connectPhaseMs >= timeoutMs) return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (latch.await(connectPhaseMs, TimeUnit.MILLISECONDS)) return true
        if (!connected()) return false
        return latch.await(timeoutMs - connectPhaseMs, TimeUnit.MILLISECONDS)
    }

    private const val GATT_CACHE_REFRESH_DELAY_MS = 500L
    private const val MTU_PRIME_TIMEOUT_MS = 1_500L
    private const val MAX_ATT_MTU = 517
    private const val ATTACH_PHY_DELAY_MS = 600L
    /** Review T5: the single GATT control reattach after a CoC connect (healthy: connected + priority in ~0.3-1 s). */
    private const val ATTACH_TIMEOUT_MS = 3_000L
}

/**
 * [retryableStatuses] defaults to the pre-audit rule (133 only) and applies to connection failures;
 * a PSM read keeps the 133-only rule. Production passes BluetoothTransportPolicy.retryableConnectStatuses().
 */
internal fun isRetryableBleControlFailure(
    message: String?,
    retryableStatuses: Set<Int> = setOf(BluetoothTransportPolicy.GATT_ERROR_133),
): Boolean =
    message == "BLE channel read failed (status 133)" ||
        message == BLE_CONTROL_READ_TIMEOUT ||
        isRetryableLeSetupStatus(message, retryableStatuses, listOf("BLE control connection failed (status "))

internal const val BLE_CONTROL_READ_TIMEOUT = "Timed out reading the receiver's BLE video channel"

/**
 * PHYs to initiate the control connection on. Legacy advertisements are on LE 1M, so initiating on
 * 2M alone cannot reach them; the M31 stack (no extended advertising) does not add 1M itself, and
 * every CoC control connection it initiated (as a relay to its child) failed in ~300 ms with status
 * 133. 2M is still requested after the connection (setPreferredPhy).
 */
internal fun controlInitiatingPhys(phyPreference: BlePhyPreference): Int =
    // Never initiate on Coded: the advertisements are legacy (LE 1M); Coded is requested after connecting.
    (phyPreference.mask and BluetoothDevice.PHY_LE_CODED_MASK.inv()) or BluetoothDevice.PHY_LE_1M_MASK

/**
 * Counts LE CoC server sockets this process opened and closed. Each listen consumes a stack
 * L2CAP registration; the Android 10 Oppo A15s refused `l2cap_listen` (status 0x01,
 * "SOCK_LIST: free(id) - NO app_fd!") after ~12 listens in a reconnect loop (lane-a 2026-10-08),
 * so phone runs can confirm from the log that every listener is closed exactly once.
 */
internal class ListenerLedger {
    private val openListeners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    var openedTotal = 0
        private set

    val openCount: Int @Synchronized get() = openListeners.size

    @Synchronized
    fun opened(listener: Any): Int {
        openListeners += listener
        openedTotal++
        return openListeners.size
    }

    /** True only for the first close of a listener this ledger saw opened. */
    @Synchronized
    fun closed(listener: Any): Boolean = openListeners.remove(listener)
}

internal object CocListenerLedger {
    private val ledger = ListenerLedger()

    fun opened(owner: String, listener: android.bluetooth.BluetoothServerSocket) {
        val open = ledger.opened(listener)
        val psm = runCatching { listener.psm }.getOrDefault(-1)
        android.util.Log.i(
            "BluetoothVideo",
            "LE CoC listener opened: owner=$owner | psm=$psm | open=$open | openedTotal=${ledger.openedTotal}",
        )
    }

    fun closed(owner: String, listener: android.bluetooth.BluetoothServerSocket) {
        if (!ledger.closed(listener)) return
        android.util.Log.i(
            "BluetoothVideo",
            "LE CoC listener closed: owner=$owner | open=${ledger.openCount} | openedTotal=${ledger.openedTotal}",
        )
    }
}
