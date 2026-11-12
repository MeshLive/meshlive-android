package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide count of changes of a media link's physical layer (a PHY move, 2M to 1M or back). The
 * rate controllers compare it with the value they saw at the last sample: the evidence they gathered
 * before the change (cuts, the standing queue, a climb in progress) described a link that no longer
 * exists. First lane-4 cold run (ASUS to A15s GATT): the stream started on 2M, where the link carried
 * 85 kbps (one PDU per connection event), the controller cut to the 40 kbps floor, and when the adaptive
 * PHY policy moved the link to 1M 10 s later (about 130-180 kbps) the controller kept climbing from 40
 * kbps at 10 kbps per 3 s. See [LinkRateController.restartAfterLinkChange].
 */
internal object LinkChangeEvents {
    private val counter = AtomicInteger()

    val epoch: Int get() = counter.get()

    fun phyChanged() {
        counter.incrementAndGet()
    }

    /** A hop's media moved to another carrier (Bluetooth to Wi-Fi or back): same epoch as a PHY change (master 4.4). */
    fun carrierChanged() {
        counter.incrementAndGet()
    }

    /**
     * A call's send carrier changed for [reason]. "attach" is the call's first carrier at establishment: no link of a
     * running stream or call changed, so the epoch stays (review A3 P2-1: under the auto ladder every call start had
     * restarted the climb of every running rate controller).
     */
    fun sendCarrierChanged(reason: String) {
        if (reason != "attach") carrierChanged()
    }
}

/** Tells a real PHY change (a different transmit PHY than the one before) from the first report. */
internal class PhyChangeTracker {
    private var last = 0

    @Synchronized
    fun onPhy(txPhy: Int): Boolean {
        val changed = last != 0 && txPhy != last
        last = txPhy
        return changed
    }
}
