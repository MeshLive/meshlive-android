package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDiscoveryGateTest {
    private fun inputs(
        widerReach: Boolean = true,
        awareAllowed: Boolean = true,
        sdkInt: Int = 33,
        activityStarted: Boolean = true,
        connectedDeviceServiceHeld: Boolean = false,
        locationServiceHeld: Boolean = false,
        locationServiceAvailable: Boolean = true,
        wantPublish: Boolean = true,
        wantSubscribe: Boolean = true,
    ) = WifiDiscoveryGate.Inputs(
        widerReach = widerReach,
        awareAllowed = awareAllowed,
        sdkInt = sdkInt,
        activityStarted = activityStarted,
        connectedDeviceServiceHeld = connectedDeviceServiceHeld,
        locationServiceHeld = locationServiceHeld,
        locationServiceAvailable = locationServiceAvailable,
        wantPublish = wantPublish,
        wantSubscribe = wantSubscribe,
    )

    @Test
    fun theSwitchDecidesFirstWhateverElseLooksReady() {
        for (sdk in listOf(29, 30, 31, 32, 33, 35)) {
            for (started in listOf(true, false)) {
                for (held in listOf(true, false)) {
                    val decision = WifiDiscoveryGate.decide(
                        inputs(sdkInt = sdk, activityStarted = started, connectedDeviceServiceHeld = held, locationServiceHeld = held, widerReach = false),
                    )
                    assertEquals(WifiDiscoveryGate.Block.SWITCH_OFF, decision.block)
                    assertEquals(false, decision.publish)
                    assertEquals(false, decision.subscribe)
                }
            }
        }
        assertEquals(WifiDiscoveryGate.Block.NOT_ELIGIBLE, WifiDiscoveryGate.decide(inputs(awareAllowed = false)).block)
    }

    @Test
    fun aStartedActivityRunsOnEverySupportedSdk() {
        for (sdk in listOf(29, 30, 31, 32, 33, 35)) {
            val decision = WifiDiscoveryGate.decide(inputs(sdkInt = sdk, activityStarted = true))
            assertEquals(null, decision.block)
            assertEquals(true, decision.publish)
            assertEquals(true, decision.subscribe)
        }
    }

    @Test
    fun theBackgroundRulesPerSdk() {
        // 33+: the connectedDevice foreground service is enough.
        assertEquals(null, WifiDiscoveryGate.decide(inputs(sdkInt = 33, activityStarted = false, connectedDeviceServiceHeld = true)).block)
        assertEquals(null, WifiDiscoveryGate.decide(inputs(sdkInt = 35, activityStarted = false, connectedDeviceServiceHeld = true)).block)
        assertEquals(
            WifiDiscoveryGate.Block.FOREGROUND_ONLY,
            WifiDiscoveryGate.decide(inputs(sdkInt = 33, activityStarted = false, connectedDeviceServiceHeld = false)).block,
        )
        // 30: connectedDevice AND the location service AND its availability (the Play flavour never has it).
        assertEquals(
            null,
            WifiDiscoveryGate.decide(
                inputs(sdkInt = 30, activityStarted = false, connectedDeviceServiceHeld = true, locationServiceHeld = true, locationServiceAvailable = true),
            ).block,
        )
        assertEquals(
            WifiDiscoveryGate.Block.FOREGROUND_ONLY,
            WifiDiscoveryGate.decide(
                inputs(sdkInt = 30, activityStarted = false, connectedDeviceServiceHeld = true, locationServiceHeld = true, locationServiceAvailable = false),
            ).block,
        )
        assertEquals(
            WifiDiscoveryGate.Block.FOREGROUND_ONLY,
            WifiDiscoveryGate.decide(
                inputs(sdkInt = 30, activityStarted = false, connectedDeviceServiceHeld = true, locationServiceHeld = false, locationServiceAvailable = true),
            ).block,
        )
        assertEquals(
            WifiDiscoveryGate.Block.FOREGROUND_ONLY,
            WifiDiscoveryGate.decide(
                inputs(sdkInt = 30, activityStarted = false, connectedDeviceServiceHeld = false, locationServiceHeld = true, locationServiceAvailable = true),
            ).block,
        )
        // 31 and 32: the app-op needs the app visible.
        for (sdk in listOf(31, 32)) {
            assertEquals(
                WifiDiscoveryGate.Block.FOREGROUND_ONLY,
                WifiDiscoveryGate.decide(
                    inputs(sdkInt = sdk, activityStarted = false, connectedDeviceServiceHeld = true, locationServiceHeld = true, locationServiceAvailable = true),
                ).block,
            )
        }
    }

    @Test
    fun nothingWantedIsAllowedButIdle() {
        val decision = WifiDiscoveryGate.decide(inputs(wantPublish = false, wantSubscribe = false))
        assertEquals(WifiDiscoveryGate.Decision(false, false, null), decision)
    }
}
