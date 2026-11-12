package com.example.bluetoothtest

/**
 * The call's Bluetooth reader noting that one authenticated record arrived (`CarrierSet.Host.heard` for carrier 0).
 *
 * Package SW1, F3: the liveness time used to be stamped only after taking the call's record lock. The consumer thread holds that
 * lock while it delivers a video record, and `EncodedFrameQueue.offer` can wait up to 5 s for room there; the reader's stamp
 * waited behind it, so a healthy link looked silent and a false renewal started. With [stampBeforeLock]
 * (`test_liveness_before_record_lock`, Experimental, default off) a record of the current link generation stamps the time first,
 * so liveness never waits on the lock. [underLock] still runs as before, under the lock, for the current generation only.
 *
 * Returns false when [generation] belongs to a replaced link (the reader then ends).
 */
internal fun heardOnBluetoothLink(
    stampBeforeLock: Boolean,
    recordLock: Any,
    generation: Int,
    currentGeneration: () -> Int,
    stamp: () -> Unit,
    underLock: () -> Unit,
): Boolean {
    if (stampBeforeLock && generation == currentGeneration()) stamp()
    synchronized(recordLock) {
        if (generation != currentGeneration()) return false
        underLock()
        return true
    }
}
