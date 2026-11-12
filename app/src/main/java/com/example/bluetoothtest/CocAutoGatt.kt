package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Automatic LE CoC -> GATT fallback for private calls (user question Q4, recommendation A, 2026-10-09;
 * promoted to Settings > Features on 2026-10-11, default [ConnectivityFeatureDefaults.COC_AUTO_GATT]
 * until phone test T6 passes). With the feature off nothing in this file changes any behaviour.
 *
 * Evidence: the A15s Bluetooth stack stops accepting LE CoC listens and connects after about 15
 * registrations per Bluetooth process, and closing sockets does not free the slots
 * (bmaraw-ab-call-9c121b30-*). The slots return only when the Bluetooth process restarts, which an
 * adapter off/on cycle does. Root cause and the default-on fix (one long-lived listener per role):
 * CocListenerHub.kt. Only listens leak; this experimental ledger still counts connects too.
 *
 * With the flag on, a session whose requested transport is BLE L2CAP is moved to BLE GATT, visibly
 * and with the requested and effective transports logged, when
 *  - a CoC listen (call availability) fails,
 *  - an outgoing CoC connect fails,
 *  - a CoC writer made no acknowledged progress from the first second ([CocCreditStall]; the caller
 *    that sees it reports [CocFallbackReason.CREDIT_STALL] through onCocFailure),
 *  - the CoC registration count of this Bluetooth-adapter session reached the threshold, or
 *  - the peer being called advertises GATT (a phone that already fell back).
 * A GATT or Classic request is never changed.
 */
enum class CocFallbackReason(val label: String) {
    NONE("none"),
    THRESHOLD("registration_threshold"),
    AVAILABILITY_FAILED("availability_failed"),
    CONNECT_FAILED("connect_failed"),
    PEER_GATT("peer_advertises_gatt"),

    /** A CoC writer got no acknowledged progress from the first second ([CocCreditStall]): possible receiver credit stall. */
    CREDIT_STALL("credit_stall"),
}

/**
 * Effective switches. [threshold] is the registration count that makes GATT preferred, and
 * [thresholdActive] says whether the count alone may do so. A benchmark launch always counts (the
 * experiment as measured). Every other launch counts only on a stack that leaks registrations
 * (DeviceFamilyPolicy.leCocServerLeaks, Android 10): on a healthy stack the count grows with
 * ordinary use and says nothing about the limit, so only a real listen or connect failure moves a
 * session to GATT there.
 */
data class CocAutoGattSettings(
    val enabled: Boolean = false,
    val threshold: Int = DEFAULT_THRESHOLD,
    val thresholdActive: Boolean = true,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 12
        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 64
        const val EXTRA_ENABLED = "test_coc_auto_gatt"
        const val EXTRA_THRESHOLD = "test_coc_gatt_threshold"
        const val PREF_ENABLED = "experimental_coc_auto_gatt"

        fun clampThreshold(value: Int) = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        /**
         * The shared feature rule ([FeatureCatalog.resolve]): a DEBUG benchmark launch (any `test_`
         * extra) uses only its own extras, default off, so a stored choice never leaks into a measured
         * case. Every other launch uses the user's explicit choice ([storedEnabled], null when never
         * touched) or the shipped default, with no dependence on Manual settings or the build type. The
         * threshold of a user launch is [DEFAULT_THRESHOLD]: it has no setting (the stored
         * `experimental_coc_gatt_threshold` it read until the 2026-10-27 switch audit was never written).
         */
        fun resolve(
            benchmarkLaunch: Boolean,
            extraEnabled: Boolean,
            extraThreshold: Int,
            storedEnabled: Boolean?,
            leakyStack: Boolean,
        ): CocAutoGattSettings {
            val enabled = FeatureCatalog.resolve(
                ConnectivityFeatures.cocAutoGatt,
                benchmarkLaunch,
                { extraEnabled },
                { storedEnabled },
            )
            return if (benchmarkLaunch) {
                CocAutoGattSettings(enabled, clampThreshold(extraThreshold))
            } else {
                CocAutoGattSettings(enabled, DEFAULT_THRESHOLD, thresholdActive = leakyStack)
            }
        }
    }
}

/** Process-wide effective switches, refreshed by the activity when settings or launch extras change. */
object CocAutoGatt {
    @Volatile var current = CocAutoGattSettings()
}

/**
 * Counts LE CoC registrations (listens and outgoing connects) in the current Bluetooth-adapter
 * session. [reset] starts a new session and bumps [generation], so a decision remembered from an
 * earlier session stops applying.
 */
class CocRegistrationLedger {
    private var countValue = 0
    private var carriedValue = 0
    private var generationValue = 0

    /** This process's registrations plus those carried from earlier app processes ([carry]; 0 unless persisted). */
    val count: Int @Synchronized get() = countValue + carriedValue
    val generation: Int @Synchronized get() = generationValue

    /** Records one registration and returns the new count. */
    @Synchronized
    fun record(): Int {
        countValue++
        return countValue + carriedValue
    }

    /** Experimental `test_coc_ledger_persist` (CocBudget): registrations of earlier app processes on this Bluetooth process. */
    @Synchronized
    fun carry(registrations: Int) {
        carriedValue = registrations.coerceAtLeast(0)
    }

    @Synchronized
    fun reset() {
        countValue = 0
        carriedValue = 0
        generationValue++
    }
}

/** The transport a session asked for, the one it uses, and why they differ. */
data class CocTransportChoice(
    val requested: BluetoothTransportOption,
    val effective: BluetoothTransportOption,
    val reason: CocFallbackReason,
    val registrations: Int,
    val threshold: Int,
    val autoGattEnabled: Boolean,
    /** Whether the registration count alone could move this session (see [CocAutoGattSettings.thresholdActive]). */
    val thresholdActive: Boolean = true,
) {
    val automatic: Boolean get() = effective != requested

    /** The visible notice for an automatic choice (null when the requested transport is used). */
    val notice: String?
        get() = when {
            !automatic -> null
            reason == CocFallbackReason.THRESHOLD ->
                "Using BLE GATT: this phone's Bluetooth has used $registrations of $threshold L2CAP " +
                    "connections. Turning Bluetooth off and on restores L2CAP"
            reason == CocFallbackReason.PEER_GATT -> "Using BLE GATT: the other phone uses GATT"
            reason == CocFallbackReason.CREDIT_STALL -> "Using BLE GATT: the L2CAP link carried no data"
            else -> "Using BLE GATT: L2CAP unavailable. Turning Bluetooth off and on may restore it"
        }

    /** The BENCHMARK/log line body: requested and effective are always both stated. */
    fun logLine(): String =
        "requested=${requested.name} | effective=${effective.name} | automatic=$automatic | " +
            "reason=${reason.label} | cocRegistrations=$registrations | threshold=$threshold | " +
            "coc_auto_gatt=$autoGattEnabled | thresholdActive=$thresholdActive"
}

/**
 * Decides the effective transport of private-call sessions from the registration count and the
 * failures seen in this adapter session. Pure JVM logic; [ledger] is the process counter.
 */
class CocAutoGattSession(private val ledger: CocRegistrationLedger) {
    private var forced: CocFallbackReason? = null
    private var forcedGeneration = -1

    /** Transport for a session that requests [requested]. Never changes anything with the flag off. */
    @Synchronized
    fun choose(requested: BluetoothTransportOption, settings: CocAutoGattSettings): CocTransportChoice {
        val count = ledger.count
        if (!settings.enabled || requested != BluetoothTransportOption.BLE_L2CAP) {
            return CocTransportChoice(
                requested, requested, CocFallbackReason.NONE, count, settings.threshold, settings.enabled,
                settings.thresholdActive,
            )
        }
        val reason = forced?.takeIf { forcedGeneration == ledger.generation }
            ?: CocFallbackReason.THRESHOLD.takeIf { settings.thresholdActive && count >= settings.threshold }
        return if (reason != null) {
            CocTransportChoice(
                requested, BluetoothTransportOption.BLE_GATT, reason, count, settings.threshold, true,
                settings.thresholdActive,
            )
        } else {
            CocTransportChoice(
                requested, requested, CocFallbackReason.NONE, count, settings.threshold, true,
                settings.thresholdActive,
            )
        }
    }

    /**
     * A CoC listen or connect failed while the session was using L2CAP. Returns the GATT retry
     * choice, or null when there is no retry (flag off, not an L2CAP request, or already on GATT).
     */
    @Synchronized
    fun onCocFailure(
        requested: BluetoothTransportOption,
        settings: CocAutoGattSettings,
        reason: CocFallbackReason,
    ): CocTransportChoice? {
        require(
            reason == CocFallbackReason.AVAILABILITY_FAILED || reason == CocFallbackReason.CONNECT_FAILED ||
                reason == CocFallbackReason.CREDIT_STALL,
        )
        return force(requested, settings, reason)
    }

    /**
     * The peer being called advertises GATT, so this L2CAP session calls it over GATT. Applies to
     * that one call only (nothing is remembered): the next endpoint chooses again. Null when the
     * session is not currently on L2CAP.
     */
    @Synchronized
    fun onPeerAdvertisesGatt(requested: BluetoothTransportOption, settings: CocAutoGattSettings): CocTransportChoice? {
        if (!settings.enabled || requested != BluetoothTransportOption.BLE_L2CAP) return null
        val current = choose(requested, settings)
        if (current.effective != BluetoothTransportOption.BLE_L2CAP) return null
        return current.copy(effective = BluetoothTransportOption.BLE_GATT, reason = CocFallbackReason.PEER_GATT)
    }

    private fun force(
        requested: BluetoothTransportOption,
        settings: CocAutoGattSettings,
        reason: CocFallbackReason,
    ): CocTransportChoice? {
        if (!settings.enabled || requested != BluetoothTransportOption.BLE_L2CAP) return null
        if (choose(requested, settings).effective != BluetoothTransportOption.BLE_L2CAP) return null
        forced = reason
        forcedGeneration = ledger.generation
        return choose(requested, settings)
    }

    /**
     * The GATT choice this adapter session already forced (a listen, connect or credit-stall failure), or null.
     * Read-only: a stream receiver uses it to join an L2CAP offer over GATT straight away (Design SG, stage 1).
     */
    @Synchronized
    fun forcedFor(requested: BluetoothTransportOption, settings: CocAutoGattSettings): CocTransportChoice? {
        if (!settings.enabled || requested != BluetoothTransportOption.BLE_L2CAP) return null
        if (forced == null || forcedGeneration != ledger.generation) return null
        return choose(requested, settings)
    }

    @Synchronized
    fun reset() {
        forced = null
        forcedGeneration = -1
    }

    companion object {
        /**
         * Which advertised call transports a scanning phone lists. Off (or not an L2CAP request):
         * exactly the effective transport, as before. On with an L2CAP request: also GATT phones, so a
         * phone that fell back stays reachable.
         */
        fun acceptsAdvertised(
            requested: BluetoothTransportOption,
            effective: BluetoothTransportOption,
            advertised: BluetoothTransportOption,
            settings: CocAutoGattSettings,
        ): Boolean = advertised == effective ||
            (settings.enabled && requested == BluetoothTransportOption.BLE_L2CAP &&
                advertised == BluetoothTransportOption.BLE_GATT)
    }
}

/** Process-wide CoC registration counter, reset when the Bluetooth adapter turns off or on. */
object CocRegistrations {
    val ledger = CocRegistrationLedger()
    val session = CocAutoGattSession(ledger)
    private var installed = false

    /** Logs and counts one LE CoC listen or outgoing connect ([kind] is "listen" or "connect"). */
    fun note(kind: String, owner: String): Int {
        val count = ledger.record()
        CocBudget.onCount(count)
        val settings = CocAutoGatt.current
        Log.i(
            "BluetoothVideo",
            "LE CoC registration: kind=$kind | owner=$owner | count=$count | " +
                "threshold=${settings.threshold} | thresholdActive=${settings.thresholdActive} | " +
                "coc_auto_gatt=${settings.enabled}",
        )
        return count
    }

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        CocBudget.install(context, ledger)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state != BluetoothAdapter.STATE_OFF && state != BluetoothAdapter.STATE_ON) return
                val before = ledger.count
                ledger.reset()
                session.reset()
                CocBudget.clear(if (state == BluetoothAdapter.STATE_ON) "adapter_on" else "adapter_off")
                CocListeners.resetAll(if (state == BluetoothAdapter.STATE_ON) "adapter_on" else "adapter_off")
                Log.i(
                    "BluetoothVideo",
                    "LE CoC registration count reset: adapter=${if (state == BluetoothAdapter.STATE_ON) "on" else "off"} | previousCount=$before",
                )
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }
}
