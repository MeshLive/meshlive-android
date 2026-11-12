package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerSightingsTest {
    private fun sightings() = PeerSightings<String, String>(bleFresh = { _, seen, now -> now - seen <= 5_000 })

    private fun PeerSightings<String, String>.entryOf(key: String) = entry(key)!!

    @Test
    fun oneKeyOneEntryWhenHeardOnBothRadios() {
        val sightings = sightings()
        sightings.observeBle("k", "ble", 0)
        val entry = sightings.observeWifi("k", "wifi", 1_000)
        assertEquals(setOf("k"), sightings.keys())
        assertEquals(setOf(PeerSightings.Leg.BLE, PeerSightings.Leg.WIFI), entry.legs)
        assertEquals(1_000, sightings.entryOf("k").seenMs)
    }

    @Test
    fun aWifiOnlyKeyBecomesBothWhenBluetoothArrives() {
        val wifiFirst = sightings()
        wifiFirst.observeWifi("k", "wifi", 1_000)
        assertEquals(setOf(PeerSightings.Leg.WIFI), wifiFirst.entryOf("k").legs)
        val afterBle = wifiFirst.observeBle("k", "ble", 2_000)
        assertEquals(setOf(PeerSightings.Leg.BLE, PeerSightings.Leg.WIFI), afterBle.legs)

        val bleFirst = sightings()
        bleFirst.observeBle("k", "ble", 2_000)
        val afterWifi = bleFirst.observeWifi("k", "wifi", 1_000)
        assertEquals(afterBle.ble, afterWifi.ble)
        assertEquals(afterBle.bleSeenMs, afterWifi.bleSeenMs)
        assertEquals(afterBle.wifi, afterWifi.wifi)
        assertEquals(afterBle.wifiSeenMs, afterWifi.wifiSeenMs)
    }

    @Test
    fun bleLegExpiresBeforeWifiAndTheRowSurvivesOnWifi() {
        val sightings = sightings()
        sightings.observeBle("k", "ble", 0)
        sightings.observeWifi("k", "wifi", 4_000)
        val changes = sightings.expire(6_000)
        assertEquals(listOf(PeerSightings.Change("k", setOf(PeerSightings.Leg.BLE), setOf(PeerSightings.Leg.WIFI))), changes)
        assertNull(sightings.entryOf("k").ble)
        assertEquals("wifi", sightings.entryOf("k").wifi)
    }

    @Test
    fun wifiLegExpiresAfterTheWindow() {
        val sightings = sightings()
        sightings.observeWifi("k", "wifi", 0)
        assertTrue(sightings.expire(120_000).isEmpty())
        assertEquals(
            listOf(PeerSightings.Change("k", setOf(PeerSightings.Leg.WIFI), emptySet())),
            sightings.expire(120_001),
        )
        assertTrue(sightings.keys().isEmpty())
    }

    @Test
    fun serviceLostDropsOnlyTheWifiLeg() {
        val sightings = sightings()
        sightings.observeBle("k", "ble", 0)
        sightings.observeWifi("k", "wifi", 1_000)
        assertEquals(
            PeerSightings.Change("k", setOf(PeerSightings.Leg.WIFI), setOf(PeerSightings.Leg.BLE)),
            sightings.dropWifi("k"),
        )
        assertEquals(setOf(PeerSightings.Leg.BLE), sightings.entryOf("k").legs)

        val wifiOnly = sightings()
        wifiOnly.observeWifi("only", "wifi", 1_000)
        assertEquals(
            PeerSightings.Change("only", setOf(PeerSightings.Leg.WIFI), emptySet()),
            wifiOnly.dropWifi("only"),
        )
        assertTrue(wifiOnly.keys().isEmpty())
        assertNull(sightings.dropWifi("zz"))
        assertNull(wifiOnly.dropWifi("only"))
    }

    @Test
    fun dropAllWifiKeepsBluetoothRows() {
        val sightings = sightings()
        sightings.observeBle("both", "ble", 0)
        sightings.observeWifi("both", "wifi", 1_000)
        sightings.observeWifi("wifiOnly", "wifi", 1_000)
        val changes = sightings.dropAllWifi()
        assertEquals(2, changes.size)
        assertEquals(setOf("both"), sightings.keys())
        assertEquals(setOf(PeerSightings.Leg.BLE), sightings.entryOf("both").legs)
        assertNotNull(sightings.entry("both"))
    }

    @Test
    fun aNewerSightingOnTheSameLegReplacesTheOlder() {
        val sightings = sightings()
        sightings.observeWifi("k", "older", 1_000)
        sightings.observeWifi("k", "newer", 3_000)
        assertEquals("newer", sightings.entryOf("k").wifi)
        assertEquals(3_000, sightings.entryOf("k").wifiSeenMs)
        sightings.observeBle("k", "ble1", 4_000)
        sightings.observeBle("k", "ble2", 5_000)
        assertEquals("ble2", sightings.entryOf("k").ble)
        assertEquals(5_000, sightings.entryOf("k").seenMs)
    }
}
