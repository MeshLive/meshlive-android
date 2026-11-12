package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lab route controls of relayed calls (chain requirement, no-direct mask, helper and next-hop pins) are test geometry:
 * a DEBUG person's launch (`test_user_launch=true`) must be able to make co-located phones use a relayed chain, while a
 * release build, which any app can start with extras, never reads them.
 */
class RelayedRouteControlsLaunchTest {
    private val userLaunchExtras: Map<String, Any> = mapOf(
        BenchmarkLaunch.EXTRA_USER_LAUNCH to true,
        RelayedCallsFeature.EXTRA_REQUIRE to 2,
        RelayedCallsFeature.EXTRA_NO_DIRECT to true,
        RelayedCallsFeature.EXTRA_HELPER_PIN to "0a0b0c0d0e0f",
        RelayedCallsFeature.EXTRA_NEXT_HOP_PIN to "A1B2C3D4E5F6",
    )

    private fun launch(debug: Boolean, extras: Map<String, Any>) = RelayedCallsFeature.resolveRouteControls(
        debug,
        int = { key, default -> extras[key] as? Int ?: default },
        flag = { key, default -> extras[key] as? Boolean ?: default },
        string = { key -> extras[key] as? String },
    )

    @Test
    fun aDebugUserLaunchAppliesTheLabRouteControls() {
        // A person's launch, not a benchmark launch: every other benchmark-only extra keeps the user's settings.
        assertFalse(BenchmarkLaunch.fromExtras(userLaunchExtras.keys, userLaunch = true, debug = true))

        val controls = launch(debug = true, userLaunchExtras)

        assertEquals(2, controls.requireRelays)
        assertTrue(controls.noDirectSightings)
        assertEquals("0a0b0c0d0e0f", RelayedCallsFeature.pinLabel(controls.helperPin))
        assertEquals("a1b2c3d4e5f6", RelayedCallsFeature.pinLabel(controls.nextHopPin))
    }

    @Test
    fun aReleaseBuildIgnoresTheLabRouteControls() {
        val controls = launch(debug = false, userLaunchExtras)

        assertEquals(0, controls.requireRelays)
        assertFalse(controls.noDirectSightings)
        assertEquals("none", RelayedCallsFeature.pinLabel(controls.helperPin))
        assertEquals("none", RelayedCallsFeature.pinLabel(controls.nextHopPin))
    }

    @Test
    fun aDebugLaunchWithoutTheExtrasLeavesEveryRouteControlOff() {
        val controls = launch(debug = true, mapOf(BenchmarkLaunch.EXTRA_USER_LAUNCH to true))

        assertEquals(0, controls.requireRelays)
        assertFalse(controls.noDirectSightings)
        assertEquals("none", RelayedCallsFeature.pinLabel(controls.helperPin))
        assertEquals("none", RelayedCallsFeature.pinLabel(controls.nextHopPin))
    }

    @Test
    fun aMalformedPinMatchesNoRouteAndTheChainRequirementStaysWithinTheChainLimit() {
        val controls = launch(
            debug = true,
            mapOf(RelayedCallsFeature.EXTRA_REQUIRE to 9, RelayedCallsFeature.EXTRA_HELPER_PIN to "not-hex"),
        )

        assertEquals(PrivateCallRelayChain.MAX_RELAYS, controls.requireRelays)
        assertEquals("invalid", RelayedCallsFeature.pinLabel(controls.helperPin))
    }
}
