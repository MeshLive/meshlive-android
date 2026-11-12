package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** [WifiDebugArms] copies exactly the three DEBUG overrides and nothing else. */
class WifiDebugArmsTest {
    private fun facts() = WifiFacts(
        api = 33,
        featureAware = true,
        featureDirect = true,
        awareAvailable = true,
        otherAwareClientAttached = false,
        awareIcmSupported = true,
        awareAttachHeldByThisApp = false,
        wifiEnabled = true,
        staFrequencyMhz = 5_180,
        locationEnabled = true,
        permissionNearby = true,
        permissionFineLocation = false,
        switches = WifiSwitches(carrier = true, widerReach = false, direct = false),
        batteryPercent = 80,
        charging = true,
        batterySaver = false,
        thermalStatus = 1,
        familyPolicyAwareOff = false,
        familyPolicyDirectOff = false,
        localFaultPauseUntilMs = 0,
        nowMs = 1_000,
        directGroupActive = false,
        hotspotActive = false,
        freeNdp = 2,
    )

    @Test
    fun theThreeOverridesAreCopiedAndNothingElseChanges() {
        val base = facts()
        val adjusted = WifiDebugArms.adjust(base, allow24Ghz = true, shareAttach = true, icm = true)
        assertEquals(base.copy(debugAllow24Ghz = true, debugShareAttach = true, debugIcm = true), adjusted)
        assertEquals(base, adjusted.copy(debugAllow24Ghz = false, debugShareAttach = false, debugIcm = false))
    }

    @Test
    fun allFalseOverridesLeaveTheFactsEqual() {
        val base = facts()
        assertEquals(base, WifiDebugArms.adjust(base, allow24Ghz = false, shareAttach = false, icm = false))
    }

    @Test
    fun theTwoAwareArmsAreOffUnlessADebugLaunchTurnsThemOn() {
        // AP10: both are Experimental and off by default; a phone that never got the extras behaves as before.
        val base = facts()
        assertFalse(base.aware24GhzAlone)
        assertFalse(base.awareGoodputGuardActs)
        val on = WifiDebugArms.adjust(
            base, allow24Ghz = false, shareAttach = false, icm = false, aware24Alone = true, goodputGuardActs = true,
        )
        assertEquals(base.copy(aware24GhzAlone = true, awareGoodputGuardActs = true), on)
        // The arms never decide the hop's own fact: only the hop agent says a phone carries no other Bluetooth media.
        assertTrue(on.otherBluetoothMedia)
    }

    @Test
    fun aDebugLaunchExtraReachesTheFactsThroughTheTunables() {
        // A release build ignores launch extras (the setter keeps the default), so this DEBUG arm exists only there.
        assumeTrue("DEBUG launch arm", BuildConfig.DEBUG)
        val base = facts()
        val oldAlone = DebugTunables.aware24GhzAlone
        val oldGuard = DebugTunables.awareGoodputGuard
        try {
            DebugTunables.aware24GhzAlone = true
            DebugTunables.awareGoodputGuard = true
            val on = WifiDebugArms.adjust(base)
            assertTrue(on.aware24GhzAlone)
            assertTrue(on.awareGoodputGuardActs)
            DebugTunables.aware24GhzAlone = false
            DebugTunables.awareGoodputGuard = false
            val off = WifiDebugArms.adjust(base)
            assertFalse(off.aware24GhzAlone)
            assertFalse(off.awareGoodputGuardActs)
        } finally {
            DebugTunables.aware24GhzAlone = oldAlone
            DebugTunables.awareGoodputGuard = oldGuard
        }
    }
}
