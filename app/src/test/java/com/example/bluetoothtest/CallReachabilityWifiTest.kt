package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallReachabilityWifiTest {
    private fun inputs(
        screen: ReachabilityScreen,
        bluetoothReady: Boolean = false,
        wifiNativeReady: Boolean = false,
        foreground: Boolean = true,
    ) = ReachabilityInputs(
        featureEnabled = true,
        screen = screen,
        foreground = foreground,
        foregroundServiceHeld = false,
        bluetoothReady = bluetoothReady,
        wifiNativeReady = wifiNativeReady,
    )

    @Test
    fun bluetoothOffWithWiderReachKeepsTheHomeEndpointAndReachability() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.HOME, bluetoothReady = false, wifiNativeReady = true))
        assertTrue(plan.endpoint)
        assertTrue(plan.reachable)
        assertFalse(plan.discoveryScan) // the people scan needs Bluetooth
    }

    @Test
    fun withoutEitherRadioTheEndpointStaysOff() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.HOME, bluetoothReady = false, wifiNativeReady = false))
        assertFalse(plan.endpoint)
        assertFalse(plan.reachable)
    }

    @Test
    fun bluetoothReadyIsUnchangedOnEveryScreen() {
        for (screen in ReachabilityScreen.values()) {
            for (foreground in listOf(true, false)) {
                val before = CallReachability.plan(inputs(screen, bluetoothReady = true, wifiNativeReady = false, foreground = foreground))
                val after = CallReachability.plan(inputs(screen, bluetoothReady = true, wifiNativeReady = true, foreground = foreground))
                assertEquals(before, after)
            }
        }
    }

    @Test
    fun aWifiOnlyCallScreenDoesNotScan() {
        val plan = CallReachability.plan(inputs(ReachabilityScreen.CALL_BROWSER, bluetoothReady = false, wifiNativeReady = true))
        assertTrue(plan.endpoint)
        assertTrue(plan.reachable)
        assertFalse(plan.discoveryScan)
    }

    @Test
    fun bluetoothOffClaimsWifiReachabilityOnlyWhileWifiAwareIsUsable() {
        fun ready(awareEligible: Boolean, nativeHopsPossible: Boolean = true) = CallReachability.wifiNativeReady(
            widerReach = true, bluetoothReady = false, permissionsGranted = true, adapterOff = true,
            awareEligible = { awareEligible }, nativeHopsPossible = nativeHopsPossible,
        )
        assertTrue(ready(awareEligible = true))
        // Wi-Fi off, or Location off on Android 11-12: nothing is on air, so the status never says "Available over Wi-Fi".
        val off = ready(awareEligible = false)
        assertFalse(off)
        assertFalse(CallReachability.plan(inputs(ReachabilityScreen.HOME, wifiNativeReady = off)).reachable)
        assertFalse(ready(awareEligible = true, nativeHopsPossible = false))   // Android 11-12: no native hop can arrive
    }

    @Test
    fun withWiderReachOffTheWifiEligibilityIsNeverRead() {
        var reads = 0
        val ready = CallReachability.wifiNativeReady(
            widerReach = false, bluetoothReady = false, permissionsGranted = true, adapterOff = true,
            awareEligible = { reads++; true }, nativeHopsPossible = true,
        )
        assertFalse(ready)
        assertEquals(0, reads)   // X4: no Wi-Fi radio read with the switch off
    }
}
