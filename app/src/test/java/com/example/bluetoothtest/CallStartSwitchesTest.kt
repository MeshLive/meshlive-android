package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The faster call start (promoted 2026-11-12): a person's launch gets all three steps; only a DEBUG launch can turn them off. */
class CallStartSwitchesTest {
    @Test
    fun aPersonsLaunchStartsCallsTheFastWayAndOnlyADebugLaunchHasTheControlArm() {
        try {
            DebugTunables.callCameraFirst = false
            DebugTunables.callAdvIdleFast = false
            DebugTunables.fastFirstRedial = false
            assertEquals("camera first: the control arm exists only in DEBUG", !BuildConfig.DEBUG, DebugTunables.callCameraFirst)
            assertEquals("quicker idle advert", !BuildConfig.DEBUG, DebugTunables.callAdvIdleFast)
            assertEquals("fast first redial", !BuildConfig.DEBUG, DebugTunables.fastFirstRedial)
        } finally {
            DebugTunables.callCameraFirst = true
            DebugTunables.callAdvIdleFast = true
            DebugTunables.fastFirstRedial = true
        }
        assertTrue(DebugTunables.callCameraFirst && DebugTunables.callAdvIdleFast && DebugTunables.fastFirstRedial)
        assertTrue(
            "an idle call endpoint keeps its fast advert",
            CallReadyAdvertisingHold.decide(
                enabled = DebugTunables.callAdvIdleFast,
                callEndpoint = true,
                closed = false,
                advertising = true,
                linkActive = false,
                boostHolding = false,
            ) == CallReadyAdvertisingHold.Action.HOLD,
        )
    }
}
