package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOfferRegistryTest {
    private fun announcement(prefix: Byte = 7, flags: Int = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE): DiscoveryAnnouncement =
        DiscoveryAnnouncement(
            nodeIdPrefix = byteArrayOf(prefix, 1, 2, 3, 4, 5),
            flags = flags,
            capabilitySummary = 0,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        )

    private fun fixedAnnouncement(): DiscoveryAnnouncement = announcement().copy(version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION)

    @Test
    fun theInfoIsMwd1AroundTheExactBleBytes() {
        val registry = WifiOfferRegistry()
        val a = announcement()
        assertTrue(registry.publish(WifiOfferRole.CALL_OFFER, a))
        val info = registry.info()!!
        val bytes = WifiWire.decodeMwd1(info).announcement
        assertArrayEquals(DiscoveryAnnouncementCodec.encode(a), bytes)
        assertEquals(22, bytes.size)
        assertEquals(27, info.size)
    }

    @Test
    fun aViewerRequestWinsOverAStreamOfferWhichWinsOverACallOffer() {
        val registry = WifiOfferRegistry()
        registry.publish(WifiOfferRole.CALL_OFFER, announcement())
        assertEquals("call", registry.roleName())
        registry.publish(WifiOfferRole.STREAM_OFFER, announcement())
        assertEquals("stream", registry.roleName())
        registry.publish(WifiOfferRole.VIEWER_REQUEST, announcement())
        assertEquals("viewer", registry.roleName())
        registry.clear(WifiOfferRole.VIEWER_REQUEST)
        assertEquals("stream", registry.roleName())
        registry.clear(WifiOfferRole.STREAM_OFFER)
        assertEquals("call", registry.roleName())
        registry.clear(WifiOfferRole.CALL_OFFER)
        assertEquals("none", registry.roleName())
        assertNull(registry.info())
    }

    @Test
    fun onlyListeningOffersHaveAnAcceptor() {
        val registry = WifiOfferRegistry()
        registry.publish(WifiOfferRole.STREAM_OFFER, announcement())
        assertNull(registry.currentAcceptor())
        val acceptor: (WifiNativeConnection) -> Unit = { it.close() }
        registry.publish(WifiOfferRole.CALL_OFFER, announcement(), acceptor)
        assertNull(registry.currentAcceptor()) // the stream offer still wins and has none
        registry.clear(WifiOfferRole.STREAM_OFFER)
        assertEquals("call", registry.roleName())
        assertNotNull(registry.currentAcceptor())
        registry.publish(WifiOfferRole.VIEWER_REQUEST, announcement(), acceptor)
        assertEquals("viewer", registry.roleName())
        assertNotNull(registry.currentAcceptor())
    }

    @Test
    fun listenersHearEveryRealChange() {
        val registry = WifiOfferRegistry()
        var heard = 0
        registry.addListener { heard++ }
        registry.publish(WifiOfferRole.CALL_OFFER, announcement())
        registry.publish(WifiOfferRole.CALL_OFFER, announcement())
        registry.clear(WifiOfferRole.VIEWER_REQUEST) // absent: no change
        assertEquals(2, heard)
    }

    @Test
    fun aFixedPrefixOfferIsNeverPublished() {
        val registry = WifiOfferRegistry()
        val rotating = announcement()
        assertTrue(registry.publish(WifiOfferRole.CALL_OFFER, rotating))
        assertNotNull(registry.info())
        assertFalse(registry.publish(WifiOfferRole.CALL_OFFER, fixedAnnouncement()))
        assertNull(registry.info())
    }

    @Test
    fun rotatingBytesFollowTheRepublishedAnnouncement() {
        val registry = WifiOfferRegistry()
        registry.publish(WifiOfferRole.CALL_OFFER, announcement(prefix = 7))
        val before = WifiWire.decodeMwd1(registry.info()!!).announcement
        registry.publish(WifiOfferRole.CALL_OFFER, announcement(prefix = 9))
        val after = WifiWire.decodeMwd1(registry.info()!!).announcement
        assertFalse(before.contentEquals(after))
        assertEquals(9.toByte(), after[1])
    }

    @Test
    fun anOwnersStopRemovesOnlyItsOwnRequest() {
        val registry = WifiOfferRegistry()
        val first: (WifiNativeConnection) -> Unit = {}
        val second: (WifiNativeConnection) -> Unit = {}
        registry.publish(WifiOfferRole.VIEWER_REQUEST, announcement(1), first)
        registry.publish(WifiOfferRole.VIEWER_REQUEST, announcement(2), second)   // a newer receiver took the role
        registry.clearIf(WifiOfferRole.VIEWER_REQUEST) { it.acceptor === first }  // the old receiver stops late
        assertNotNull(registry.info())
        registry.clearIf(WifiOfferRole.VIEWER_REQUEST) { it.acceptor === second }
        assertNull(registry.info())
    }

    @Test
    fun aSnapshotKeepsTheKeyAndTheBytesOfOneOfferWhileTheOfferChanges() {
        val registry = WifiOfferRegistry()
        val a = announcement(1)
        registry.publish(WifiOfferRole.CALL_OFFER, a)
        val snapshot = registry.snapshot()!!
        registry.publish(WifiOfferRole.CALL_OFFER, announcement(2))   // a rotation lands between the reads (A8 review P2-2)
        assertEquals("call:" + WifiSighting.keyOf(a), snapshot.key)
        assertArrayEquals(DiscoveryAnnouncementCodec.encode(a), WifiWire.decodeMwd1(snapshot.info).announcement)
        assertNotEquals(snapshot.key, registry.snapshot()!!.key)
    }
}
