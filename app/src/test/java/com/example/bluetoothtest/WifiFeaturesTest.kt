package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the Wi-Fi Settings card, its permission flow, the in-call notices and the switch hub. */
class WifiFeaturesTest {
    @After
    fun clean() {
        FeatureCatalog.clearForTest()
        WifiSwitchHub.resetForTest()
    }

    private fun grantedFlow(sdk: Int, granted: Set<String> = emptySet(), location: Boolean = true) =
        WifiEnableFlow(sdk, { it in granted }, { location })

    @Test
    fun permissionsPerApiAndSwitch() {
        assertEquals(listOf(WifiFeatures.PERMISSION_NEARBY_WIFI), WifiPermissionPlan.permissions("wifi_carrier", 33))
        assertEquals(
            listOf(WifiFeatures.PERMISSION_FINE, WifiFeatures.PERMISSION_COARSE),
            WifiPermissionPlan.permissions("wifi_wider_reach", 31),
        )
        assertEquals(listOf(WifiFeatures.PERMISSION_FINE), WifiPermissionPlan.permissions("wifi_direct", 30))
        assertEquals(emptyList<String>(), WifiPermissionPlan.permissions("wifi_wider_reach", 29))
        assertEquals(
            listOf(WifiFeatures.PERMISSION_FINE),
            WifiPermissionPlan.required("wifi_wider_reach", 31),
        )
        assertFalse(WifiPermissionPlan.rowShown("wifi_wider_reach", 29))
        assertTrue(WifiPermissionPlan.rowShown("wifi_wider_reach", 30))
    }

    @Test
    fun deniedPermissionLeavesTheSwitchOff() {
        val asked = mutableListOf<List<String>>()
        var proceeded = 0
        var denied = 0
        val driver = WifiEnableDriver(
            flow = grantedFlow(33),
            sdkInt = 33,
            askPermissions = { permissions, answered -> asked += permissions; answered() },
            showLocationOff = {},
            showDenied = { denied++ },
        )
        assertTrue(driver.confirm("wifi_carrier") { proceeded++ })
        assertEquals(listOf(listOf(WifiFeatures.PERMISSION_NEARBY_WIFI)), asked)
        assertEquals(0, proceeded)
        assertEquals(1, denied)
    }

    @Test
    fun pickingApproximateLocationOnAndroid12SaysPreciseLocationIsNeeded() {
        val granted = mutableSetOf<String>()
        val answers = mutableListOf<Boolean>()
        var proceeded = 0
        val driver = WifiEnableDriver(
            flow = grantedFlow(31, granted),
            sdkInt = 31,
            // The user picks "Approximate" in the system dialog: coarse is granted, fine is not.
            askPermissions = { _, answered -> granted += WifiFeatures.PERMISSION_COARSE; answered() },
            showLocationOff = {},
            showDenied = { approximateOnly -> answers += approximateOnly },
        )
        assertTrue(driver.confirm("wifi_direct") { proceeded++ })
        assertEquals(0, proceeded)
        assertEquals(listOf(true), answers)

        // A plain refusal keeps the generic text.
        granted.clear()
        val refused = WifiEnableDriver(grantedFlow(31, granted), 31, { _, answered -> answered() }, {}, { answers += it })
        assertTrue(refused.confirm("wifi_direct") { proceeded++ })
        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun grantedAfterAskingProceeds() {
        val granted = mutableSetOf<String>()
        var proceeded = 0
        var denied = 0
        val driver = WifiEnableDriver(
            flow = grantedFlow(33, granted),
            sdkInt = 33,
            askPermissions = { permissions, answered -> granted += permissions; answered() },
            showLocationOff = {},
            showDenied = { denied++ },
        )
        assertTrue(driver.confirm("wifi_carrier") { proceeded++ })
        assertEquals(1, proceeded)
        assertEquals(0, denied)
    }

    @Test
    fun locationOffOnApi31BlocksWiderReachBeforeAskingAnything() {
        var asked = 0
        var locationShown = 0
        var proceeded = 0
        val driver = WifiEnableDriver(
            flow = grantedFlow(31, location = false),
            sdkInt = 31,
            askPermissions = { _, answered -> asked++; answered() },
            showLocationOff = { locationShown++ },
            showDenied = {},
        )
        assertTrue(driver.confirm("wifi_wider_reach") { proceeded++ })
        assertEquals(0, asked)
        assertEquals(1, locationShown)
        assertEquals(0, proceeded)
    }

    @Test
    fun alreadyGrantedSwitchesOnAtOnce() {
        val all = setOf(
            WifiFeatures.PERMISSION_NEARBY_WIFI, WifiFeatures.PERMISSION_FINE, WifiFeatures.PERMISSION_COARSE,
        )
        var asked = 0
        val driver = WifiEnableDriver(
            flow = grantedFlow(33, all),
            sdkInt = 33,
            askPermissions = { _, answered -> asked++; answered() },
            showLocationOff = {},
            showDenied = {},
        )
        assertFalse(driver.confirm("wifi_carrier") {})
        assertEquals(0, asked)
    }

    @Test
    fun carrierSwitchOnApi33AsksNearbyOnly() {
        val asked = mutableListOf<List<String>>()
        val driver = WifiEnableDriver(
            flow = grantedFlow(33),
            sdkInt = 33,
            askPermissions = { permissions, answered -> asked += permissions; answered() },
            showLocationOff = {},
            showDenied = {},
        )
        driver.confirm("wifi_carrier") {}
        assertEquals(listOf(listOf(WifiFeatures.PERMISSION_NEARBY_WIFI)), asked)
    }

    @Test
    fun carrierSwitchOnApi31AsksNothing() {
        var asked = 0
        val driver = WifiEnableDriver(
            flow = grantedFlow(31),
            sdkInt = 31,
            askPermissions = { _, answered -> asked++; answered() },
            showLocationOff = {},
            showDenied = {},
        )
        assertFalse(driver.confirm("wifi_carrier") {})
        assertEquals(0, asked)
    }

    @Test
    fun noticeForWifiOnlyAfterSwitchOff() {
        assertEquals(
            WifiCallNotices.SWITCH_OFF_WIFI_ONLY,
            WifiCallNotices.forTransition(
                CarrierUi(CarrierKind.WIFI_AWARE, false), CarrierUi(CarrierKind.WIFI_AWARE, false), true,
            ),
        )
        assertEquals(
            WifiCallNotices.BLUETOOTH_LOST_ON_WIFI,
            WifiCallNotices.forTransition(
                CarrierUi(CarrierKind.WIFI_AWARE, true), CarrierUi(CarrierKind.WIFI_AWARE, false), false,
            ),
        )
        assertNull(
            WifiCallNotices.forTransition(
                CarrierUi(CarrierKind.WIFI_AWARE, false), CarrierUi(CarrierKind.BLUETOOTH, false), false,
            ),
        )
    }

    @Test
    fun losingBluetoothWhileWifiCarriesTheCallShowsTheNoticeEvenBeforeTheSendCarrierMoves() {
        fun after(send: Int, vararg live: Pair<Int, CarrierKind>) =
            CarrierSnapshot(true, send, send, live.size, live.toMap(), emptyMap(), 0, 0, false, false, false)
        // Fan-out: carrier 0 is already dead, but the consumer has not moved the send carrier yet (review A6 P3-1).
        assertEquals(
            WifiCallNotices.BLUETOOTH_LOST_ON_WIFI,
            WifiCallNotices.afterCarrierLoss(CarrierKind.BLUETOOTH, after(0, 1 to CarrierKind.WIFI_AWARE)),
        )
        assertEquals(
            WifiCallNotices.BLUETOOTH_LOST_ON_WIFI,
            WifiCallNotices.afterCarrierLoss(CarrierKind.BLUETOOTH, after(1, 1 to CarrierKind.WIFI_AWARE)),
        )
        // A stale report about a renewed Bluetooth link: Bluetooth still carries the call, nothing to say.
        assertNull(
            WifiCallNotices.afterCarrierLoss(
                CarrierKind.BLUETOOTH, after(1, 0 to CarrierKind.BLUETOOTH, 1 to CarrierKind.WIFI_AWARE),
            ),
        )
        assertNull(WifiCallNotices.afterCarrierLoss(CarrierKind.WIFI_AWARE, after(0, 0 to CarrierKind.BLUETOOTH)))
        assertNull(WifiCallNotices.afterCarrierLoss(CarrierKind.BLUETOOTH, after(-1)))
    }

    @Test
    fun everyNoticeReachesTheScreen() {
        listOf(
            WifiCallNotices.SWITCH_OFF_WIFI_ONLY,
            WifiCallNotices.BLUETOOTH_LOST_ON_WIFI,
            WifiCallNotices.WIFI_CONNECT_FAILED,
            WifiCallNotices.WIFI_NATIVE_ENDED,
        ).forEach { assertTrue(it, displayStatus(it) != null) }
    }

    @Test
    fun theWiderReachHintIsOnlyForAndroid11And12() {
        assertFalse(WifiRowPolicy.carrierNeedsWiderReach(29))
        assertTrue(WifiRowPolicy.carrierNeedsWiderReach(30))
        assertTrue(WifiRowPolicy.carrierNeedsWiderReach(31))
        assertTrue(WifiRowPolicy.carrierNeedsWiderReach(32))
        assertFalse(WifiRowPolicy.carrierNeedsWiderReach(33))
    }

    @Test
    fun theHubReadsTheLastCatalogRefreshAndAnyOnFollowsIt() {
        FeatureCatalog.clearForTest()
        FeatureCatalog.register(
            FeatureSpec(WifiFeatures.ID_CARRIER, WifiFeatures.PREF_CARRIER, false, WifiFeatures.EXTRA_CARRIER),
        )
        val stored = mapOf(WifiFeatures.PREF_CARRIER to true)
        FeatureCatalog.refresh(false, { null }) { stored[it] }
        assertEquals(WifiSwitches(true, false, false), WifiSwitchHub.current())
        assertTrue(WifiSwitchHub.anyOn())

        FeatureCatalog.refresh(false, { null }) { null }
        assertEquals(WifiSwitchHub.OFF, WifiSwitchHub.current())
        assertFalse(WifiSwitchHub.anyOn())
    }

    @Test
    fun switchListenersHearOnlyRealChanges() {
        WifiSwitchHub.resetForTest()
        val heard = mutableListOf<Pair<WifiSwitches, WifiSwitches>>()
        WifiSwitchHub.addListener { old, new -> heard += old to new }

        WifiSwitchHub.publish(WifiSwitchHub.OFF)
        assertTrue(heard.isEmpty())

        val on = WifiSwitches(true, false, false)
        WifiSwitchHub.publish(on)
        assertEquals(1, heard.size)
        WifiSwitchHub.publish(on)
        assertEquals(1, heard.size)

        WifiSwitchHub.publish(WifiSwitchHub.OFF)
        assertEquals(2, heard.size)
        assertTrue(WifiSwitchHub.carrierTurnedOff(heard.last().first, heard.last().second))
    }

    @Test
    fun aStoredOnSwitchThatLostItsPermissionShowsANote() {
        assertEquals(WifiRowNote.NEEDS_PERMISSION, grantedFlow(33).note("wifi_carrier", true))
        assertEquals(WifiRowNote.NEEDS_LOCATION, grantedFlow(31, location = false).note("wifi_wider_reach", true))
        val fine = setOf(WifiFeatures.PERMISSION_FINE, WifiFeatures.PERMISSION_COARSE)
        assertNull(grantedFlow(31, fine).note("wifi_wider_reach", true))
    }

    @Test
    fun onAndroid11And12TheCarrierRowSaysItNeedsWiderReachUntilWiderReachIsOn() {
        assertEquals(WifiRowNote.NEEDS_WIDER_REACH, grantedFlow(30).note("wifi_carrier", true, widerReachOn = false))
        assertNull(grantedFlow(30).note("wifi_carrier", true, widerReachOn = true))
        assertNull("off: no note", grantedFlow(31).note("wifi_carrier", false, widerReachOn = false))
        assertNull("Android 13 needs no Wider reach", grantedFlow(33, setOf(WifiFeatures.PERMISSION_NEARBY_WIFI)).note("wifi_carrier", true))
        assertNull("Android 10 never uses Wi-Fi Aware: nothing to add", grantedFlow(29).note("wifi_carrier", true))
    }

    @Test
    fun aFailingSwitchListenerIsReportedByClassNameAndTheOthersStillHear() {
        WifiSwitchHub.resetForTest()
        val reported = mutableListOf<String>()
        WifiSwitchHub.reportListenerFailure = { reported += it }
        var heard = 0
        WifiSwitchHub.addListener { _, _ -> throw IllegalStateException("secret-detail") }
        WifiSwitchHub.addListener { _, _ -> heard++ }
        WifiSwitchHub.publish(WifiSwitches(true, false, false))
        assertEquals(1, heard)
        assertEquals(1, reported.size)
        assertTrue(reported.single(), reported.single().contains("IllegalStateException"))
        assertFalse("the message is never logged", reported.single().contains("secret-detail"))
    }

    @Test
    fun publishingOffTheMainThreadIsRefused() {
        WifiSwitchHub.resetForTest()
        var heard = 0
        WifiSwitchHub.addListener { _, _ -> heard++ }
        WifiSwitchHub.isPublishThread = { false }
        if (!BuildConfig.DEBUG) {
            // A debug assertion only (review A6 P3-6): a release build keeps running and still tells the listeners.
            WifiSwitchHub.publish(WifiSwitches(true, false, false))
            assertEquals(1, heard)
            return
        }
        assertThrows(IllegalStateException::class.java) { WifiSwitchHub.publish(WifiSwitches(true, false, false)) }
        assertEquals(0, heard)
    }

    @Test
    fun aSwitchThatIsOffReadsNoPermission() {
        var grantedReads = 0
        var locationReads = 0
        val flow = WifiEnableFlow(33, { grantedReads++; false }, { locationReads++; false })
        WifiFeatures.IDS.forEach { assertNull(flow.note(it, false)) }
        assertEquals(0, grantedReads)
        assertEquals(0, locationReads)
    }
}
