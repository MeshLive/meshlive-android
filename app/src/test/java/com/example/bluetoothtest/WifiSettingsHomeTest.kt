package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-W7: the Wi-Fi Direct confirmation (what a person agrees to before the switch turns on), and which Connection rows are
 * developer rows.
 */
class WifiSettingsHomeTest {
    private class Screens {
        val granted = mutableSetOf<String>()
        var locationOn = true
        var confirmations = 0
        var accept = true
        var locationDialogs = 0
        var denied = 0
        var proceeded = 0
        var permissionsAnswer = true

        fun driver(sdk: Int = 33, withConfirmation: Boolean = true) = WifiEnableDriver(
            flow = WifiEnableFlow(sdk, { it in granted }, { locationOn }),
            sdkInt = sdk,
            askPermissions = { permissions, answered -> if (permissionsAnswer) granted += permissions; answered() },
            showLocationOff = { locationDialogs++ },
            showDenied = { denied++ },
            confirmDirect = if (withConfirmation) { proceed -> confirmations++; if (accept) proceed() } else null,
        )
    }

    @Test
    fun turningOnWifiDirectAsksOnceAndOnlyTurnsOnWhenTheUserAgrees() {
        val s = Screens().apply { granted += WifiFeatures.PERMISSION_NEARBY_WIFI }
        assertTrue(s.driver().confirm(WifiFeatures.ID_DIRECT) { s.proceeded++ })
        assertEquals(1, s.confirmations)
        assertEquals(1, s.proceeded)

        val cancelled = Screens().apply { granted += WifiFeatures.PERMISSION_NEARBY_WIFI; accept = false }
        assertTrue("the driver took over, the switch stays off", cancelled.driver().confirm(WifiFeatures.ID_DIRECT) { cancelled.proceeded++ })
        assertEquals(1, cancelled.confirmations)
        assertEquals(0, cancelled.proceeded)
    }

    @Test
    fun theConfirmationComesAfterThePermissionAndNeverAfterARefusal() {
        val allowed = Screens()   // permission not granted yet; the system dialog will grant it
        assertTrue(allowed.driver().confirm(WifiFeatures.ID_DIRECT) { allowed.proceeded++ })
        assertEquals(1, allowed.confirmations)
        assertEquals(1, allowed.proceeded)

        val refused = Screens().apply { permissionsAnswer = false }
        assertTrue(refused.driver().confirm(WifiFeatures.ID_DIRECT) { refused.proceeded++ })
        assertEquals("a refused permission shows its own dialog, not the confirmation", 0, refused.confirmations)
        assertEquals(1, refused.denied)
        assertEquals(0, refused.proceeded)
    }

    @Test
    fun locationOffOnAndroidTenToTwelveShowsTheLocationDialogInsteadOfTheConfirmation() {
        val s = Screens().apply { locationOn = false; granted += listOf(WifiFeatures.PERMISSION_FINE, WifiFeatures.PERMISSION_COARSE) }
        assertTrue(s.driver(sdk = 31).confirm(WifiFeatures.ID_DIRECT) { s.proceeded++ })
        assertEquals(1, s.locationDialogs)
        assertEquals(0, s.confirmations)
        assertEquals(0, s.proceeded)
    }

    @Test
    fun theOtherWifiSwitchesAreNeverConfirmed() {
        val s = Screens().apply { granted += WifiFeatures.PERMISSION_NEARBY_WIFI }
        assertFalse("an allowed carrier switch turns on at once", s.driver().confirm(WifiFeatures.ID_CARRIER) { s.proceeded++ })
        assertFalse(s.driver().confirm(WifiFeatures.ID_WIDER_REACH) { s.proceeded++ })
        assertEquals(0, s.confirmations)
    }

    @Test
    fun withoutAConfirmationWifiDirectBehavesLikeTheOthers() {
        val s = Screens().apply { granted += WifiFeatures.PERMISSION_NEARBY_WIFI }
        assertFalse(s.driver(withConfirmation = false).confirm(WifiFeatures.ID_DIRECT) { s.proceeded++ })
    }

    @Test
    fun theSummariesAndDestinationOfConnectionAreDebugOnly() {
        assertTrue(ConnectionPageRows.developerRowsShown(debugBuild = true))
        assertFalse(ConnectionPageRows.developerRowsShown(debugBuild = false))
    }

    @Test
    fun aReleaseBuildHidesTheTransportPickerUnlessANonDefaultTransportIsStored() {
        val default = BluetoothTransportOption.DEFAULT.name
        assertFalse("never chosen", ConnectionPageRows.transportPickerShown(debugBuild = false, storedTransportName = null))
        assertFalse("default stored", ConnectionPageRows.transportPickerShown(debugBuild = false, storedTransportName = default))
        assertFalse("unreadable stored value falls back to the default", ConnectionPageRows.transportPickerShown(false, "NO_SUCH_TRANSPORT"))
        val others = BluetoothTransportOption.entries.filter { it != BluetoothTransportOption.DEFAULT }
        assertTrue(others.isNotEmpty())
        for (other in others) {
            assertTrue("${other.name} stored: the person can still change it back", ConnectionPageRows.transportPickerShown(false, other.name))
        }
    }

    @Test
    fun aDebugBuildAlwaysShowsTheTransportPicker() {
        for (stored in listOf(null, BluetoothTransportOption.DEFAULT.name, BluetoothTransportOption.CLASSIC_RFCOMM.name, "junk")) {
            assertTrue(ConnectionPageRows.transportPickerShown(debugBuild = true, storedTransportName = stored))
        }
    }

    @Test
    fun theWifiRowsAreTheCarrierWithItsTwoOptions() {
        val rows = WifiRowPolicy.rows(ConnectivityFeatures.wifi, sdkInt = 34)
        assertEquals(listOf(WifiFeatures.ID_CARRIER, WifiFeatures.ID_WIDER_REACH, WifiFeatures.ID_DIRECT), rows.map { it.id })
        assertEquals(emptyList<String>(), rows.first().dependsOn)
        assertTrue(rows.drop(1).all { it.dependsOn == listOf(WifiFeatures.ID_CARRIER) })
    }
}
