package com.example.bluetoothtest

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Socket rules: the expected peer, the TCP options, per-generation close and a bound server. */
class AwareSocketsTest {
    private fun expected(): Inet6Address = InetAddress.getByName("fe80::1ff:fe23:4567:890a") as Inet6Address

    /** A scoped IPv6 address with the given numeric scope id (no interface lookup is needed to build one). */
    private fun scopedTo(bytes: ByteArray, scopeId: Int): Inet6Address = Inet6Address.getByAddress(null, bytes, scopeId)

    @Test
    fun theExpectedPeerIsComparedByBytesAndScope() {
        val expected = expected()
        assertTrue(AwareSockets.isExpectedPeer(expected, expected, 3))

        val scoped = scopedTo(expected.address, 7)
        assertTrue(AwareSockets.isExpectedPeer(scoped, expected, 7))
        assertFalse("a non-zero scope of another interface is refused", AwareSockets.isExpectedPeer(scoped, expected, 8))

        val otherBytes = expected.address.copyOf().also { it[15] = (it[15] + 1).toByte() }
        val other = scopedTo(otherBytes, 7)
        assertFalse(AwareSockets.isExpectedPeer(other, expected, 7))

        val v4 = InetAddress.getByName("10.0.0.1") as Inet4Address
        assertFalse(AwareSockets.isExpectedPeer(v4, expected, 0))
        assertFalse(AwareSockets.isExpectedPeer(null, expected, 0))
    }

    @Test
    fun applyOptionsSetsTheTransportOptions() {
        val socket = Socket()
        try {
            AwareSockets.applyOptions(socket)
            assertTrue(socket.tcpNoDelay)
            assertFalse(socket.keepAlive)
            assertTrue("the OS may round the send buffer", socket.sendBufferSize > 0)
        } finally {
            socket.close()
        }
    }

    @Test
    fun trackAndCloseOwnedCloseOnlyTheSelectedSockets() {
        val first = ServerSocket()
        val second = ServerSocket()
        val other = ServerSocket()
        try {
            AwareSockets.track(4_000_001L, first)
            AwareSockets.track(4_000_001L, second)
            AwareSockets.track(4_000_002L, other)
            val before = AwareSockets.openCount()
            AwareSockets.closeOwned(4_000_001L, except = first)
            assertEquals(before - 1, AwareSockets.openCount())
            assertFalse(first.isClosed)
            assertTrue(second.isClosed)
            assertFalse("another generation is untouched", other.isClosed)
        } finally {
            AwareSockets.close(first)
            AwareSockets.close(second)
            AwareSockets.close(other)
        }
    }

    @Test
    fun aSocketTrackedAfterItsLinkClosedIsClosedAtOnceAndLeavesNoResidue() {
        // A connect or accept thread that passed its `closed` check tracks its socket after close() swept the
        // generation (A4 review P2-3): it must not stay open, or the shutdown residue never reaches zero.
        val before = AwareSockets.openCount()
        val early = ServerSocket()
        val late = ServerSocket()
        val otherLink = ServerSocket()
        try {
            AwareSockets.track(4_000_011L, early)
            AwareSockets.closeGeneration(4_000_011L)
            assertTrue(early.isClosed)
            AwareSockets.track(4_000_011L, late)
            assertTrue("the late socket of a closed link is closed at once", late.isClosed)
            AwareSockets.track(4_000_012L, otherLink)
            assertFalse("a live link's socket stays open", otherLink.isClosed)
            AwareSockets.close(otherLink)
            assertEquals("no residue", before, AwareSockets.openCount())
        } finally {
            AwareSockets.close(early)
            AwareSockets.close(late)
            AwareSockets.close(otherLink)
        }
    }

    @Test
    fun aCloseRacingTheTrackingThreadsAlwaysClosesEverySocket() {
        val before = AwareSockets.openCount()
        val sockets = (0 until 200).map { ServerSocket() }
        val worker = Thread { sockets.forEachIndexed { i, s -> AwareSockets.track(4_000_100L + i, s) } }
        worker.start()
        sockets.indices.forEach { AwareSockets.closeGeneration(4_000_100L + it) }
        worker.join(5_000)
        assertTrue(sockets.all { it.isClosed })
        assertEquals(before, AwareSockets.openCount())
    }

    @Test
    fun closeIsNullSafeAndIdempotent() {
        AwareSockets.close(null)
        val socket = ServerSocket()
        AwareSockets.track(4_000_003L, socket)
        AwareSockets.close(socket)
        AwareSockets.close(socket)
        assertTrue(socket.isClosed)
    }

    @Test
    fun aServerBindsToTheLoopbackAddress() {
        val loopback = try {
            InetAddress.getByName("::1") as? Inet6Address
        } catch (e: Exception) {
            null
        }
        Assume.assumeTrue("the build host has no IPv6 loopback", loopback != null)
        val server = AwareSockets.openServer(loopback!!, 0)
        try {
            assertTrue(server.isBound)
            assertTrue(server.localPort > 0)
            assertEquals(WifiRadioLimits.ACCEPT_POLL_MS, server.soTimeout)
        } finally {
            server.close()
        }
    }
}
