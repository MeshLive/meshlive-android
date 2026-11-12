package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI audit 2026-11-10, UI-03: the 2.4 GHz advice told every user to turn Wi-Fi off, which stops the Wi-Fi carriers a
 * user switched on. With any Wi-Fi switch on, the advice keeps Wi-Fi on and asks to leave the 2.4 GHz network instead.
 */
class WifiAdviceVariantTest {
    @After
    fun clean() {
        FeatureCatalog.clearForTest()
        WifiSwitchHub.resetForTest()
    }

    @Test
    fun withEveryWifiSwitchOffTheAdviceIsUnchanged() {
        assertEquals(WifiAdvicePolicy.Variant.TURN_WIFI_OFF, WifiAdvicePolicy.variant(WifiSwitchHub.OFF))
    }

    @Test
    fun anyWifiSwitchOnKeepsWifiOn() {
        val keep = WifiAdvicePolicy.Variant.KEEP_WIFI_ON
        assertEquals(keep, WifiAdvicePolicy.variant(WifiSwitches(carrier = true, widerReach = false, direct = false)))
        assertEquals(keep, WifiAdvicePolicy.variant(WifiSwitches(carrier = false, widerReach = true, direct = false)))
        assertEquals(keep, WifiAdvicePolicy.variant(WifiSwitches(carrier = false, widerReach = false, direct = true)))
        assertEquals(keep, WifiAdvicePolicy.variant(WifiSwitches(carrier = true, widerReach = true, direct = true)))
    }

    @Test
    fun theAdviceFollowsTheSwitchTheUserTurnedOnInSettings() {
        FeatureCatalog.clearForTest()
        FeatureCatalog.register(
            FeatureSpec(WifiFeatures.ID_DIRECT, WifiFeatures.PREF_DIRECT, false, WifiFeatures.EXTRA_DIRECT),
        )
        // Nothing stored: the row is off by default.
        FeatureCatalog.refresh(false, { null }) { null }
        assertEquals(WifiAdvicePolicy.Variant.TURN_WIFI_OFF, WifiAdvicePolicy.variant(WifiSwitchHub.current()))

        // The user turns Wi-Fi Direct on.
        val stored = mapOf(WifiFeatures.PREF_DIRECT to true)
        FeatureCatalog.refresh(false, { null }) { stored[it] }
        assertEquals(WifiAdvicePolicy.Variant.KEEP_WIFI_ON, WifiAdvicePolicy.variant(WifiSwitchHub.current()))

        // And off again.
        FeatureCatalog.refresh(false, { null }) { null }
        assertEquals(WifiAdvicePolicy.Variant.TURN_WIFI_OFF, WifiAdvicePolicy.variant(WifiSwitchHub.current()))
    }
}
