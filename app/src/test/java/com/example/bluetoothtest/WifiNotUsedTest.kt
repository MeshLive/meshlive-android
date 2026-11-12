package com.example.bluetoothtest

import com.example.bluetoothtest.WifiNotUsedReason.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Link health's "Wi-Fi not used: <reason>": the reason a person is shown for a call that stays on Bluetooth. */
class WifiNotUsedTest {
    private fun happy() = WifiFacts(
        api = 34, featureAware = true, featureDirect = true, awareAvailable = true, otherAwareClientAttached = false,
        awareIcmSupported = true, awareAttachHeldByThisApp = false, wifiEnabled = true, staFrequencyMhz = 5180,
        locationEnabled = true, permissionNearby = true, permissionFineLocation = true,
        switches = WifiSwitches(carrier = true, widerReach = false, direct = false),
        batteryPercent = 80, charging = false, batterySaver = false, thermalStatus = 0,
        familyPolicyAwareOff = false, familyPolicyDirectOff = false, localFaultPauseUntilMs = 0L, nowMs = 1_000L,
        directGroupActive = false, hotspotActive = false, freeNdp = 2,
    )

    private fun reason(f: WifiFacts, peer: WifiPeerView? = null) =
        WifiNotUsed.reason(f, WifiEligibility.decide(f), peer)

    @Test
    fun aSwitchedOffPhoneSaysSo() {
        val off = happy().copy(switches = WifiSwitches(carrier = false, widerReach = false, direct = false))
        assertEquals(SWITCHED_OFF, reason(off))
        // The switch is named first even when everything else is wrong too.
        assertEquals(SWITCHED_OFF, reason(off.copy(wifiEnabled = false, batterySaver = true, hotspotActive = true)))
    }

    @Test
    fun theLocalReasonsAreTheOnesTheEligibilityVerdictActsOn() {
        assertEquals(WIFI_OFF, reason(happy().copy(wifiEnabled = false)))
        assertEquals(HOTSPOT, reason(happy().copy(hotspotActive = true)))
        assertEquals(BATTERY_SAVER, reason(happy().copy(batterySaver = true)))
        assertEquals(BATTERY_LOW, reason(happy().copy(batteryPercent = 5)))
        assertNull("a charging phone with a low battery is not held back", reason(happy().copy(batteryPercent = 5, charging = true)))
        assertEquals(HEAT, reason(happy().copy(thermalStatus = WifiLimits.THERMAL_SEVERE)))
        assertEquals(NETWORK_24GHZ, reason(happy().copy(staFrequencyMhz = 2437)))
        assertEquals(NEEDS_PERMISSION, reason(happy().copy(permissionNearby = false)))
        assertEquals(BUSY, reason(happy().copy(directGroupActive = true)))
        assertEquals(BUSY, reason(happy().copy(freeNdp = 0)))
        assertEquals(RECENT_PROBLEM, reason(happy().copy(localFaultPauseUntilMs = 5_000L)))
        assertEquals(THIS_PHONE_UNSUPPORTED, reason(happy().copy(featureAware = false, featureDirect = false)))
    }

    @Test
    fun androidElevenAndTwelveNameTheMissingPieceInOrder() {
        val phone = happy().copy(api = 31, permissionNearby = false)
        assertEquals(WIDER_REACH_OFF, reason(phone))
        val wider = phone.copy(switches = phone.switches.copy(widerReach = true))
        assertNull(reason(wider))
        assertEquals(NEEDS_LOCATION, reason(wider.copy(locationEnabled = false)))
        assertEquals(NEEDS_PERMISSION, reason(wider.copy(permissionFineLocation = false)))
    }

    @Test
    fun whenThisPhoneIsFineTheOtherPhoneIsNamed() {
        val f = happy()
        assertNull("nothing is known to stand in the way of a call that just started", reason(f, WifiPeerView()))
        assertNull(reason(f, null))
        assertNull(reason(f, WifiPeerView(peerBearers = 1)))
        assertEquals(OTHER_NO_WIFI, reason(f, WifiPeerView(peerBearers = 0)))
        assertEquals(OTHER_BUSY, reason(f, WifiPeerView(peerBearers = 1, peerDeclineWire = WifiEligibility.DeclineReason.BUSY.wire)))
        assertEquals(OTHER_BATTERY_OR_HEAT, reason(f, WifiPeerView(1, WifiEligibility.DeclineReason.BATTERY_OR_THERMAL.wire)))
        assertEquals(OTHER_NETWORK_24GHZ, reason(f, WifiPeerView(1, WifiEligibility.DeclineReason.NOT_5GHZ.wire)))
        assertEquals(OTHER_WIFI_OFF, reason(f, WifiPeerView(1, WifiEligibility.DeclineReason.WIFI_UNAVAILABLE.wire)))
        assertEquals(OTHER_DECLINED, reason(f, WifiPeerView(1, WifiEligibility.DeclineReason.NOT_ELIGIBLE.wire)))
    }

    @Test
    fun aHopThatGaveUpSaysWifiDidNotHoldUp() {
        val f = happy()
        for (why in listOf("failed_twice", "declined_thrice", "fallbacks", "spoof_cap")) {
            assertEquals(why, DID_NOT_HOLD, reason(f, WifiPeerView(peerBearers = 1, stoppedBecause = why)))
        }
        assertEquals(NO_COMMON_WAY, reason(f, WifiPeerView(peerBearers = 1, stoppedBecause = "no_tuple")))
        assertNull("the user's own switch-off is explained by the switch", reason(f, WifiPeerView(1, null, "switch_off")))
    }

    @Test
    fun aLocalReasonBeatsWhatTheOtherPhoneSaid() {
        assertEquals(BATTERY_SAVER, reason(happy().copy(batterySaver = true), WifiPeerView(peerBearers = 0)))
    }
}
