package com.example.bluetoothtest

import android.content.Context
import android.provider.Settings
import android.util.Log

/** What the budget remembers between app processes: the last listen PSM, the registrations since, and the boot. */
internal data class CocBudgetRecord(val lastPsm: Int, val count: Int, val bootCount: Int)

/**
 * Proposal 2 (`test_coc_ledger_persist`, Experimental, off): the Android 10 LE CoC budget across app processes.
 *
 * Android 10 never stops a closed LE CoC server (CocListenerHub.kt), so every app process leaks one registration per
 * role until the Bluetooth process restarts; the in-memory [CocRegistrationLedger] restarted at 0 in each app process and
 * the threshold never fired in the case that exhausts the stack (a force-stop or a kill and relaunch per run).
 *
 * The Bluetooth process cannot be read by an app, but its LE PSM allocator can: `L2CA_AllocateLePSM` hands out
 * 0x80..0xFF round-robin from the last PSM it assigned, starting again at 0x80 when the stack starts (`le_dyn_psm =
 * LE_DYNAMIC_PSM_START - 1`, l2c_main.cc; l2c_api.cc). So a listen PSM at or below the last stored one means a new
 * Bluetooth process (reset), and a higher one means the same process (count on). A wrap from 0xFF back to 0x80 inside
 * one Bluetooth process needs 128 PSM allocations, which a leaking Android 10 stack cannot reach (it runs out after
 * 12-15 listens), so a drop is always read as a restart: at worst the count restarts early and the listen fails with
 * the existing visible AVAILABILITY_FAILED fallback. A different boot count also resets; ACTION_STATE_CHANGED clears it.
 */
internal object CocBudgetDetector {
    const val PSM_MIN = 0x80
    const val PSM_MAX = 0xFF

    fun validPsm(psm: Int): Boolean = psm in PSM_MIN..PSM_MAX

    /** Registrations of earlier app processes on the current Bluetooth process, as far as is known at start. */
    fun carriedAtStart(record: CocBudgetRecord?, bootCount: Int): Int =
        if (record == null || record.bootCount != bootCount) 0 else record.count.coerceAtLeast(0)

    /** True when [psm] shows the Bluetooth process restarted since [record] (or nothing is known about it). */
    fun newBluetoothProcess(record: CocBudgetRecord?, psm: Int, bootCount: Int): Boolean =
        record == null || record.bootCount != bootCount || psm <= record.lastPsm
}

/**
 * The persisted half of [CocBudgetDetector], on stacks that leak (DeviceFamilyPolicy.leCocServerLeaks) only: on a fixed
 * stack registrations are freed and a carried count would only move healthy sessions to GATT.
 */
internal object CocBudget {
    private const val PREFS = "coc_budget"
    private const val KEY_PSM = "last_psm"
    private const val KEY_COUNT = "count"
    private const val KEY_BOOT = "boot_count"

    @Volatile private var context: Context? = null

    private fun active(): Boolean = CocAvailabilitySwitches.ledgerPersist && context != null &&
        DeviceFamilyPolicy.leCocServerLeaks(BluetoothStackFingerprint.current())

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private fun prefs() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): CocBudgetRecord? {
        val prefs = prefs() ?: return null
        if (!prefs.contains(KEY_PSM)) return null
        return CocBudgetRecord(prefs.getInt(KEY_PSM, -1), prefs.getInt(KEY_COUNT, 0), prefs.getInt(KEY_BOOT, -1))
    }

    private fun store(record: CocBudgetRecord) {
        prefs()?.edit()?.putInt(KEY_PSM, record.lastPsm)?.putInt(KEY_COUNT, record.count)
            ?.putInt(KEY_BOOT, record.bootCount)?.apply()
    }

    /** At app start: carry the earlier processes' registrations into [ledger]. */
    fun install(context: Context, ledger: CocRegistrationLedger) {
        this.context = context.applicationContext
        if (!active()) return
        val boot = bootCount(context)
        val record = load()
        val carried = CocBudgetDetector.carriedAtStart(record, boot)
        ledger.carry(carried)
        log("event=load | lastPsm=${record?.lastPsm ?: -1} | carried=$carried | bootSame=${record?.bootCount == boot}")
    }

    /** A listen got [psm]; [ledger] already counts it. A restarted Bluetooth process drops the carried count. */
    fun onListen(psm: Int, ledger: CocRegistrationLedger) {
        if (!active() || !CocBudgetDetector.validPsm(psm)) return
        val context = context ?: return
        val boot = bootCount(context)
        val record = load()
        val fresh = CocBudgetDetector.newBluetoothProcess(record, psm, boot)
        if (fresh) ledger.carry(0)
        val count = ledger.count
        store(CocBudgetRecord(psm, count, boot))
        val settings = CocAutoGatt.current
        log(
            "event=listen | psm=$psm | lastPsm=${record?.lastPsm ?: -1} | bluetoothProcess=${if (fresh) "new" else "same"} | " +
                "count=$count | threshold=${settings.threshold} | thresholdActive=${settings.thresholdActive}",
        )
    }

    /** Any registration (listens and connects): keep the stored count current. */
    fun onCount(count: Int) {
        if (!active()) return
        val record = load() ?: return
        store(record.copy(count = count))
    }

    /** Bluetooth turned off or on: the stack's registrations are gone. */
    fun clear(reason: String) {
        if (!active()) return
        prefs()?.edit()?.clear()?.apply()
        log("event=clear | reason=$reason")
    }

    private fun log(body: String) {
        Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "coc_budget: $body")
    }
}
