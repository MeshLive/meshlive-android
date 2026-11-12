package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WifiOnlyPeerTest {
    private fun sighting(key: String) = WifiSighting(
        key = key,
        announcement = DiscoveryAnnouncement(
            nodeIdPrefix = byteArrayOf(1, 2, 3, 4, 5, 6),
            flags = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            capabilitySummary = 0,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        ),
        handle = Any(),
        sessionGeneration = 3,
        epoch = 5,
        seenMs = 1_000,
    )

    @Test
    fun twoPeopleSeenOnlyOverWifiGetDifferentLabelsThatNeverShowAnAddress() {
        val a = WifiOnlyPeer.label(sighting("a1b2c3d4e5f6"))
        val b = WifiOnlyPeer.label(sighting("0f1e2d3c4b5a"))
        assertNotEquals(a, b)                                   // the list can tell the two entries apart
        for (label in listOf(a, b)) {
            assertFalse(label.contains(":"))                     // no MAC-like address characters
            assertFalse(label.contains(WifiOnlyPeer.PLACEHOLDER_ADDRESS))
        }
        assertEquals(a, WifiOnlyPeer.label(sighting("a1b2c3d4e5f6")))   // the same person keeps the same label
    }
}
