package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-B3 (reviewer finding 1): the address refresh behind a tap must keep the selected directness. The user taps a direct
 * (cost 0) row; the phone that answers the refresh with a cost 1 copy of the same prefix must not become the dialled offer.
 */
class DialRefreshGateTest {
    private val prefix = ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { 0x21 }

    private fun offer(routeCost: Int, version: Int = DiscoveryAnnouncement.FIXED_PREFIX_VERSION) = DiscoveryAnnouncement(
        nodeIdPrefix = prefix,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        capabilitySummary = NodeCapabilities.CODEC_VP9,
        streamToken = 0,
        routeCost = routeCost,
        sequence = 1,
        version = version,
    )

    @Test
    fun aDirectSelectionIsNotReplacedByARelayedAdvertisementOfTheSamePrefix() {
        val selected = offer(routeCost = 0)
        assertFalse(DialRefreshGate.accepts(selected.version, selected.routeCost, offer(routeCost = 1)))
        assertFalse(DialRefreshGate.accepts(selected.version, selected.routeCost, offer(routeCost = 4)))
    }

    @Test
    fun aDirectSelectionFollowsItsOwnFreshDirectAdvertisement() {
        val selected = offer(routeCost = 0)
        assertTrue(DialRefreshGate.accepts(selected.version, selected.routeCost, offer(routeCost = 0)))
    }

    @Test
    fun aRelayedSelectionKeepsItsOwnRefresh() {
        val selected = offer(routeCost = 1)
        assertTrue(DialRefreshGate.accepts(selected.version, selected.routeCost, offer(routeCost = 1)))
        assertTrue(DialRefreshGate.accepts(selected.version, selected.routeCost, offer(routeCost = 0)))
    }

    @Test
    fun theVersionMustStillMatch() {
        val selected = offer(routeCost = 0)
        assertFalse(
            DialRefreshGate.accepts(selected.version, selected.routeCost, offer(0, DiscoveryAnnouncement.ROTATING_VERSION)),
        )
    }
}
