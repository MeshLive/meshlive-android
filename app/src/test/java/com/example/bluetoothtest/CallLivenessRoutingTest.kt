package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 step 9 item 4 (review A3 P1-2): in fan-out the carrier monitor asks whether the Bluetooth standby went silent. The
 * routing of a carrier death itself is proven by the `CarrierSetTest` scenarios (fallback, standby loss, both lost).
 */
class CallLivenessRoutingTest {
    private val second = 1_000_000_000L
    private val now = 100 * second

    @Test
    fun aStandbyThatStoppedAnsweringIsSilentWithoutTolerance() {
        assertTrue(CarrierLossPolicy.bluetoothSilent(false, now, now - 9 * second, now, renewalOwnsLiveness = false))
        assertFalse(
            "a standby heard a moment ago is alive",
            CarrierLossPolicy.bluetoothSilent(false, now, now - second, now, renewalOwnsLiveness = false),
        )
    }

    @Test
    fun withToleranceAStandbyWhoseWritesStillCompleteGetsLongerBeforeItIsSilent() {
        assertFalse(CarrierLossPolicy.bluetoothSilent(true, now, now - 9 * second, now, renewalOwnsLiveness = false))
        assertTrue(
            "writes stopped too: silent",
            CarrierLossPolicy.bluetoothSilent(true, now, now - 9 * second, now - 5 * second, renewalOwnsLiveness = false),
        )
        assertTrue(CarrierLossPolicy.bluetoothSilent(true, now, now - 20 * second, now, renewalOwnsLiveness = false))
    }

    @Test
    fun aRenewalThatOwnsTheLinkKeepsTheStandbyAlive() {
        assertFalse(CarrierLossPolicy.bluetoothSilent(false, now, now - 20 * second, now - 20 * second, renewalOwnsLiveness = true))
    }
}
