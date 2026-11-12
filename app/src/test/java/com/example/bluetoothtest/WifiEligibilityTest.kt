package com.example.bluetoothtest

import com.example.bluetoothtest.WifiEligibility.DeclineReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiEligibilityTest {
    private fun happy() = WifiFacts(
        api = 34, featureAware = true, featureDirect = true, awareAvailable = true, otherAwareClientAttached = false,
        awareIcmSupported = true, awareAttachHeldByThisApp = false, wifiEnabled = true, staFrequencyMhz = 5180,
        locationEnabled = false, permissionNearby = true, permissionFineLocation = false,
        switches = WifiSwitches(carrier = true, widerReach = false, direct = false),
        batteryPercent = 80, charging = false, batterySaver = false, thermalStatus = 0,
        familyPolicyAwareOff = false, familyPolicyDirectOff = false, localFaultPauseUntilMs = 0L, nowMs = 1_000L,
        directGroupActive = false, hotspotActive = false, freeNdp = 2,
    )

    private fun decide(f: WifiFacts) = WifiEligibility.decide(f)
    private fun WifiFacts.wider() = copy(switches = switches.copy(widerReach = true))
    private fun WifiFacts.direct() = copy(switches = switches.copy(direct = true))

    @Test
    fun aPrivatePhoneWithEverythingAvailableMayUseWifiAware() {
        val decision = decide(happy())
        assertTrue(decision.aware)
        assertFalse(decision.awareIcm)
        assertFalse(decision.directGo)
        assertFalse(decision.directClient)
        assertFalse(decision.acceptsUnverifiedBand)
        assertNull(decision.reason)
    }

    @Test
    fun withEveryWifiSwitchOffNothingIsEligible() {
        val off = happy().copy(switches = WifiSwitches(carrier = false, widerReach = false, direct = false))
        val decision = decide(off)
        assertFalse(decision.aware)
        assertFalse(decision.directGo)
        assertFalse(decision.directClient)
        assertEquals(DeclineReason.NOT_ELIGIBLE, decision.reason)
        val noPermission = off.copy(permissionNearby = false)
        assertFalse(decide(noPermission).aware)
        assertEquals(DeclineReason.NOT_ELIGIBLE, decide(noPermission).reason)
    }

    @Test
    fun androidThirteenNeedsTheNearbyDevicesPermission() {
        val denied = decide(happy().copy(api = 33, permissionNearby = false))
        assertFalse(denied.aware)
        assertTrue(decide(happy().copy(api = 33, permissionNearby = true)).aware)
    }

    @Test
    fun androidElevenAndTwelveNeedWiderReachFineLocationAndLocationOn() {
        for (api in listOf(30, 31, 32)) {
            assertFalse("api $api private", decide(happy().copy(api = api)).aware)
            val fineOnly = happy().copy(api = api).wider().copy(permissionFineLocation = true)
            assertFalse("api $api fine only", decide(fineOnly).aware)
            val both = fineOnly.copy(locationEnabled = true)
            assertTrue("api $api both", decide(both).aware)
            assertTrue("api $api wider", decide(both).acceptsUnverifiedBand)
            val nearbyOnly = happy().copy(api = api).wider().copy(permissionNearby = true)
            assertFalse("api $api nearby only", decide(nearbyOnly).aware)
        }
    }

    @Test
    fun androidTenNeverGetsWifiAware() {
        val awareTen = decide(happy().copy(api = 29).wider().copy(permissionFineLocation = true, locationEnabled = true))
        assertFalse(awareTen.aware)
        assertEquals(DeclineReason.NOT_ELIGIBLE, awareTen.reason)
        val directTen = decide(happy().copy(api = 29).direct().copy(permissionFineLocation = true, locationEnabled = true))
        assertTrue(directTen.directGo)
    }

    @Test
    fun anotherAwareClientBlocksAPrivatePhoneButNotAWiderReachPhone() {
        val busy = decide(happy().copy(otherAwareClientAttached = true))
        assertFalse(busy.aware)
        assertEquals(DeclineReason.BUSY, busy.reason)
        assertEquals(DeclineReason.BUSY, decide(happy().copy(otherAwareClientAttached = null)).reason)
        assertTrue(decide(happy().copy(otherAwareClientAttached = false)).aware)
        assertTrue(decide(happy().wider().copy(otherAwareClientAttached = true)).aware)
        assertTrue(decide(happy().copy(debugShareAttach = true)).aware)
    }

    @Test
    fun aWifiClientOnTwoPointFourGigahertzMakesThePhoneDeclineLocally() {
        val onTwoPointFour = decide(happy().copy(staFrequencyMhz = 2437))
        assertFalse(onTwoPointFour.aware)
        assertEquals(DeclineReason.NOT_5GHZ, onTwoPointFour.reason)
        assertFalse(decide(happy().copy(staFrequencyMhz = 4899)).aware)
        for (ok in listOf(4900, 5180, 5745)) assertTrue("$ok", decide(happy().copy(staFrequencyMhz = ok)).aware)
        assertTrue(decide(happy().copy(staFrequencyMhz = null)).aware) // not connected passes
        assertTrue(decide(happy().copy(staFrequencyMhz = 2437, debugAllow24Ghz = true)).aware)
        assertNull(decide(happy()).reason)
    }

    @Test
    fun aWifiClientOnTwoPointFourMayUseAwareWhenThePhoneCarriesNoOtherBluetoothMedia() {
        val onTwoPointFour = happy().copy(staFrequencyMhz = 2437)
        // AP10, switch on: the hop's phone carries no other Bluetooth media link, so the 2.4 GHz client connection is accepted.
        val alone = decide(onTwoPointFour.copy(aware24GhzAlone = true, otherBluetoothMedia = false))
        assertTrue(alone.aware)
        assertNull(alone.reason)
        // A phone with other Bluetooth media links keeps the 5 GHz rule; so does a reader that does not know the fact.
        assertEquals(DeclineReason.NOT_5GHZ, decide(onTwoPointFour.copy(aware24GhzAlone = true, otherBluetoothMedia = true)).reason)
        assertEquals(DeclineReason.NOT_5GHZ, decide(onTwoPointFour.copy(aware24GhzAlone = true)).reason)
        // The switch is off by default: the 5 GHz rule, even for a phone without other Bluetooth media.
        val switchOff = decide(onTwoPointFour.copy(otherBluetoothMedia = false))
        assertFalse(switchOff.aware)
        assertEquals(DeclineReason.NOT_5GHZ, switchOff.reason)
        // Every other rule still applies to a phone that is alone on 2.4 GHz.
        assertEquals(
            DeclineReason.BATTERY_OR_THERMAL,
            decide(onTwoPointFour.copy(aware24GhzAlone = true, otherBluetoothMedia = false, batterySaver = true)).reason,
        )
    }

    @Test
    fun theTwoPointFourAloneFactNeverChangesWifiDirect() {
        val direct = happy().direct().copy(staFrequencyMhz = 2437, aware24GhzAlone = true)
        for (other in listOf(true, false)) {
            val decision = decide(direct.copy(otherBluetoothMedia = other))
            assertEquals("other=$other", decide(direct).directGo, decision.directGo)
            assertEquals("other=$other", decide(direct).directClient, decision.directClient)
        }
    }

    @Test
    fun lowBatteryBatterySaverAndSevereHeatBlockButChargingAndModerateHeatDoNot() {
        assertEquals(DeclineReason.BATTERY_OR_THERMAL, decide(happy().copy(batterySaver = true)).reason)
        assertEquals(DeclineReason.BATTERY_OR_THERMAL, decide(happy().copy(batteryPercent = 14)).reason)
        assertTrue(decide(happy().copy(batteryPercent = 14, charging = true)).aware)
        assertTrue(decide(happy().copy(batteryPercent = 15)).aware)
        assertTrue(decide(happy().copy(thermalStatus = 2)).aware)
        assertEquals(DeclineReason.BATTERY_OR_THERMAL, decide(happy().copy(thermalStatus = 3)).reason)
        assertEquals(DeclineReason.BATTERY_OR_THERMAL, decide(happy().copy(thermalStatus = 4)).reason)
    }

    @Test
    fun aHotspotADirectGroupNoFreeDataPathAndAPhoneWidePauseEachBlock() {
        assertEquals(DeclineReason.BUSY, decide(happy().copy(hotspotActive = true)).reason)
        assertEquals(DeclineReason.BUSY, decide(happy().copy(directGroupActive = true)).reason)
        assertEquals(DeclineReason.BUSY, decide(happy().copy(freeNdp = 0)).reason)
        val paused = decide(happy().copy(localFaultPauseUntilMs = 2_000L, nowMs = 1_000L))
        assertEquals(DeclineReason.LIMIT_OR_COOLDOWN, paused.reason)
        assertTrue(decide(happy().copy(localFaultPauseUntilMs = 2_000L, nowMs = 2_000L)).aware)
    }

    @Test
    fun aFamilyPolicyCanTurnWifiAwareOff() {
        val off = decide(happy().copy(familyPolicyAwareOff = true))
        assertFalse(off.aware)
        assertEquals(DeclineReason.NOT_ELIGIBLE, off.reason)
        assertTrue(decide(happy().direct().copy(familyPolicyAwareOff = true)).directGo)
        assertFalse(decide(happy().direct().copy(familyPolicyDirectOff = true)).directGo)
    }

    @Test
    fun wifiOffOrAwareUnavailableGivesTheUnavailableReason() {
        assertEquals(DeclineReason.WIFI_UNAVAILABLE, decide(happy().copy(wifiEnabled = false)).reason)
        assertEquals(DeclineReason.WIFI_UNAVAILABLE, decide(happy().copy(awareAvailable = false)).reason)
        assertEquals(DeclineReason.NOT_ELIGIBLE, decide(happy().copy(featureAware = false)).reason)
    }

    @Test
    fun theFirstFailingRuleInTheDocumentedOrderGivesTheReason() {
        // carrier off (1a) before Wi-Fi off (2b)
        assertEquals(
            DeclineReason.NOT_ELIGIBLE,
            decide(happy().copy(switches = WifiSwitches(false, false, false), wifiEnabled = false)).reason,
        )
        // Wi-Fi off (2b) before battery (6a)
        assertEquals(DeclineReason.WIFI_UNAVAILABLE, decide(happy().copy(wifiEnabled = false, batterySaver = true)).reason)
        // another client (3) before the 2.4 GHz band (4)
        assertEquals(
            DeclineReason.BUSY,
            decide(happy().copy(otherAwareClientAttached = true, staFrequencyMhz = 2437)).reason,
        )
        // 2.4 GHz (4) before the family policy (5)
        assertEquals(
            DeclineReason.NOT_5GHZ,
            decide(happy().copy(staFrequencyMhz = 2437, familyPolicyAwareOff = true)).reason,
        )
        // family policy (5) before battery (6a)
        assertEquals(
            DeclineReason.NOT_ELIGIBLE,
            decide(happy().copy(familyPolicyAwareOff = true, batterySaver = true)).reason,
        )
        // battery (6a) before hotspot (6d)
        assertEquals(
            DeclineReason.BATTERY_OR_THERMAL,
            decide(happy().copy(batterySaver = true, hotspotActive = true)).reason,
        )
        // hotspot (6d) before the pause (6f)
        assertEquals(
            DeclineReason.BUSY,
            decide(happy().copy(hotspotActive = true, localFaultPauseUntilMs = 2_000L, nowMs = 1_000L)).reason,
        )
        // pause (6f) before the free data path (6g)
        assertEquals(
            DeclineReason.LIMIT_OR_COOLDOWN,
            decide(happy().copy(localFaultPauseUntilMs = 2_000L, nowMs = 1_000L, freeNdp = 0)).reason,
        )
    }

    @Test
    fun wifiDirectNeedsItsOwnSwitchPermissionAndNoAwareAttachHeldByThisApp() {
        val on = decide(happy().direct())
        assertTrue(on.directGo)
        assertTrue(on.directClient)
        assertFalse(decide(happy()).directGo) // switch off
        assertFalse(decide(happy().copy(api = 33, permissionNearby = false).direct()).directGo)
        val fineOnly = happy().copy(api = 31, permissionFineLocation = true).direct()
        assertFalse(decide(fineOnly).directGo)
        assertTrue(decide(fineOnly.copy(locationEnabled = true)).directGo)
        assertFalse(decide(happy().direct().copy(familyPolicyDirectOff = true)).directGo)
        assertFalse(decide(happy().direct().copy(hotspotActive = true)).directGo)
        assertFalse(decide(happy().direct().copy(awareAttachHeldByThisApp = true)).directGo)
        assertFalse(decide(happy().direct().copy(featureDirect = false)).directGo)
        assertFalse(decide(happy().direct().copy(wifiEnabled = false)).directGo)
        assertFalse(decide(happy().direct().copy(batterySaver = true)).directGo)
        assertFalse(decide(happy().direct().copy(thermalStatus = 3)).directGo)
        assertFalse(decide(happy().direct().copy(localFaultPauseUntilMs = 2_000L, nowMs = 1_000L)).directGo)
        // the band rule is Aware's: a 2.4 GHz own client does not block Direct
        assertTrue(decide(happy().direct().copy(staFrequencyMhz = 2437)).directGo)
        // a live Direct group blocks a second one [Reviewer amendment 8]
        assertFalse(decide(happy().direct().copy(directGroupActive = true)).directGo)
    }

    @Test
    fun directIsIndependentOfWiderReachAndOfAwareEligibility() {
        val decision = decide(happy().direct().copy(featureAware = false))
        assertFalse(decision.aware)
        assertTrue(decision.directGo)
        assertTrue(decision.directClient)
    }

    @Test
    fun instantModeNeedsAnEligiblePhoneSupportAndTheDebugArm() {
        assertTrue(decide(happy().copy(debugIcm = true)).awareIcm)
        assertFalse(decide(happy().copy(debugIcm = false)).awareIcm)
        assertFalse(decide(happy().copy(debugIcm = true, awareIcmSupported = false)).awareIcm)
        val carrierOff = decide(happy().copy(debugIcm = true, switches = happy().switches.copy(carrier = false)))
        assertFalse(carrierOff.aware)
        assertFalse(carrierOff.awareIcm)
    }
}
