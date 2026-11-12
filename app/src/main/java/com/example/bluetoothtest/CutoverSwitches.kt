package com.example.bluetoothtest

import android.content.Intent

/**
 * Carrier-switch and call-liveness fixes (package SW1, 2026-11-10), promoted 2026-11-11 after the randomised Wi-Fi Direct
 * user-launch A/B (S22 <-> A15s, 12 calls per arm: on 12/12 calls reached Wi-Fi with 0 failed switches and 0 freeze-gate
 * failures, off 11/12 with 4 failed switches and 4; no difference on ASUS <-> A15s or S22 <-> ASUS, where the failure did not
 * occur). Both are on by default and release builds keep them on; a DEBUG launch with `=false` is the control arm. Listed in
 * SWITCHES "Experimental, alternative and optional behaviour".
 */
object CutoverSwitches {
    /**
     * `test_cutover_keep_fifo_slot` (promoted 2026-11-11, default on; `false` is the DEBUG control arm): a record sealed while Bluetooth is the send carrier keeps its
     * place in that carrier's FIFO. Today a producer that finds the carrier's queue full waits outside the lock and, when
     * the switch to Wi-Fi happens meanwhile, goes out on Wi-Fi below its own SWITCH boundary: the receiver drops it as STALE.
     * On: the record is queued at once (one over the capacity per blocked producer), then the producer waits for room.
     */
    @Volatile var keepFifoSlot: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /**
     * `test_liveness_before_record_lock` (promoted 2026-11-11, default on; `false` is the DEBUG control arm): the Bluetooth reader stamps its liveness time before it
     * takes the call's record lock. Today a consumer blocked in the video queue's back-pressure wait (5 s) holds that lock, the
     * stamp waits behind it, and a healthy link looks silent (a false renewal).
     */
    @Volatile var livenessBeforeRecordLock: Boolean = true
        set(value) {
            field = value || !BuildConfig.DEBUG
        }

    /** Reads the DEBUG launch extras `test_cutover_keep_fifo_slot` and `test_liveness_before_record_lock`; release builds keep the defaults. */
    fun applyExtras(boolExtra: (String, Boolean) -> Boolean) {
        keepFifoSlot = boolExtra("test_cutover_keep_fifo_slot", true)
        livenessBeforeRecordLock = boolExtra("test_liveness_before_record_lock", true)
    }

    fun applyLaunchExtras(intent: Intent) = applyExtras(intent::getBooleanExtra)

    /** One log token for the BENCHMARK family_switches line. */
    fun describe(): String = "keepFifoSlot=$keepFifoSlot,livenessBeforeRecordLock=$livenessBeforeRecordLock"
}
