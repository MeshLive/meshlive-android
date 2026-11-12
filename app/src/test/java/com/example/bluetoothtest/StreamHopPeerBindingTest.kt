package com.example.bluetoothtest

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-B2: a stream hop binds the authenticated end-to-end peer to the authenticated link peer. The relayed-handshake
 * attack is built from real handshakes (a phone in the middle forwards the other side's end-to-end hello, as the DEBUG
 * manual relay does), then each side runs the same check the source and the viewer run in production.
 */
class StreamHopPeerBindingTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private fun caps(maxChildren: Int = 0) = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_000,
        maxChildren = maxChildren,
    )

    private class Pipes {
        val aIn = PipedInputStream(64 * 1024)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(64 * 1024)
        val aOut = PipedOutputStream(bIn)
    }

    /** What each end of a stream hop knows after both handshakes: the link peer and the end-to-end peer. */
    private class HopView(val link: SecureBluetoothSession, val endpoint: EndToEndVideoSession)

    private class Joined(val source: HopView, val viewer: HopView)

    private val ids = List(3) { KeyPairSigningIdentity(generateEcKeyPair()) }
    private val source = ids[0]
    private val viewer = ids[1]
    private val attacker = ids[2]

    @After
    fun resetSwitch() {
        DebugTunables.streamRelayPassthrough = false
    }

    /** The source dials the viewer directly: the same key at both layers. */
    private fun direct(): Joined {
        val p = Pipes()
        val viewerSide = AtomicReference<HopView>()
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(p.bIn, p.bOut, viewer, caps())
                viewerSide.set(HopView(link, EndToEndVideoHandshake.respond(link, viewer, caps())))
            }.onFailure(failure::set)
        }
        val link = SecureBluetoothHandshake.initiate(p.aIn, p.aOut, source, caps())
        val sourceSide = HopView(link, EndToEndVideoHandshake.initiate(link, source, caps()))
        worker.join(120_000)
        failure.get()?.let { throw AssertionError("viewer failed", it) }
        return Joined(sourceSide, requireNotNull(viewerSide.get()))
    }

    /**
     * The attack: the attacker authenticates as itself to both ends (its own request to the source, the viewer's
     * request dialled), then forwards the end-to-end hello each way. Both link checks pass; the end-to-end peers
     * are each other.
     */
    private fun relayed(attackerSignsRelay: Boolean = false, attackerChildren: Int = if (attackerSignsRelay) 1 else 0): Joined {
        val toSource = Pipes()
        val toViewer = Pipes()
        val attackerCaps = caps(attackerChildren)
        val failure = AtomicReference<Throwable?>()
        val viewerSide = AtomicReference<HopView>()
        val viewerThread = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(toViewer.bIn, toViewer.bOut, viewer, caps())
                viewerSide.set(HopView(link, EndToEndVideoHandshake.respond(link, viewer, caps())))
            }.onFailure(failure::set)
        }
        val attackerThread = thread {
            runCatching {
                val upstream = SecureBluetoothHandshake.respond(toSource.bIn, toSource.bOut, attacker, attackerCaps)
                val downstream = SecureBluetoothHandshake.initiate(toViewer.aIn, toViewer.aOut, attacker, attackerCaps)
                val sourceHello = upstream.reader.read()
                downstream.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, sourceHello.payload)
                downstream.writer.flush()
                val viewerHello = downstream.reader.read()
                upstream.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, viewerHello.payload)
                upstream.writer.flush()
            }.onFailure(failure::set)
        }
        val link = SecureBluetoothHandshake.initiate(toSource.aIn, toSource.aOut, source, caps())
        val sourceSide = HopView(link, EndToEndVideoHandshake.initiate(link, source, caps()))
        viewerThread.join(120_000)
        attackerThread.join(120_000)
        failure.get()?.let { throw AssertionError("a relay party failed", it) }
        return Joined(sourceSide, requireNotNull(viewerSide.get()))
    }

    private fun sourceCheck(hop: HopView, rotating: Boolean, log: (String) -> Unit = {}) =
        StreamHopPeerBinding.requireBound(
            "source", rotating, hop.link.peerNodeId, hop.endpoint.peerNodeId, hop.link.peerCapabilities.maxChildren,
            "Peer identity changed between discovery and connection", log = log,
        )

    private fun viewerCheck(hop: HopView, rotating: Boolean, log: (String) -> Unit = {}) =
        StreamHopPeerBinding.requireBound(
            "viewer", rotating, hop.link.peerNodeId, hop.endpoint.peerNodeId, hop.link.peerCapabilities.maxChildren,
            "Stream source identity changed between discovery and connection", log = log,
        )

    @Test
    fun aRelayedHandshakeIsRefusedOnTheViewerSideAndOnTheSourceSideForVersion2() {
        val hop = relayed()
        // Both sides' link checks would pass (each link peer is the attacker); only the end-to-end peers differ.
        assertEquals(attacker.nodeId, hop.source.link.peerNodeId)
        assertEquals(attacker.nodeId, hop.viewer.link.peerNodeId)
        assertEquals(viewer.nodeId, hop.source.endpoint.peerNodeId)
        assertEquals(source.nodeId, hop.viewer.endpoint.peerNodeId)
        val viewerLog = mutableListOf<String>()
        val sourceLog = mutableListOf<String>()
        assertThrows(IllegalArgumentException::class.java) { viewerCheck(hop.viewer, rotating = false, viewerLog::add) }
        assertThrows(IllegalArgumentException::class.java) { sourceCheck(hop.source, rotating = false, sourceLog::add) }
        if (BuildConfig.DEBUG) {
            assertEquals(listOf("BENCHMARK stream_peer_binding: result=mismatch | role=viewer | version=2"), viewerLog)
            assertEquals(listOf("BENCHMARK stream_peer_binding: result=mismatch | role=source | version=2"), sourceLog)
        } else {
            assertTrue("a release build logs nothing extra", viewerLog.isEmpty() && sourceLog.isEmpty())
        }
    }

    @Test
    fun aRelayedHandshakeIsRefusedForVersion5Too() {
        val hop = relayed()
        assertThrows(IllegalArgumentException::class.java) { viewerCheck(hop.viewer, rotating = true) }
        assertThrows(IllegalArgumentException::class.java) { sourceCheck(hop.source, rotating = true) }
    }

    @Test
    fun aRelayThatSignsMaxChildrenIsStillRefusedWithoutTheDebugRelaySwitch() {
        val hop = relayed(attackerSignsRelay = true)
        assertEquals(1, hop.viewer.link.peerCapabilities.maxChildren)
        assertThrows(IllegalArgumentException::class.java) { viewerCheck(hop.viewer, rotating = false) }
        assertThrows(IllegalArgumentException::class.java) { sourceCheck(hop.source, rotating = false) }
    }

    @Test
    fun aLegitimateJoinWithTheSameKeyAtBothLayersStillWorksOnBothSidesAndVersions() {
        val hop = direct()
        assertEquals(hop.source.link.peerNodeId, hop.source.endpoint.peerNodeId)
        assertEquals(hop.viewer.link.peerNodeId, hop.viewer.endpoint.peerNodeId)
        for (rotating in listOf(false, true)) {
            val log = mutableListOf<String>()
            sourceCheck(hop.source, rotating, log::add)
            viewerCheck(hop.viewer, rotating, log::add)
            assertTrue("a bound hop logs nothing", log.isEmpty())
        }
    }

    @Test
    fun theDebugRelayExemptionAppliesOnlyToADebugVersion2HopThroughARelayThatSignedIt() {
        val hop = relayed(attackerSignsRelay = true)
        val expected = if (BuildConfig.DEBUG) StreamHopPeerBinding.Result.RELAY_EXEMPT else StreamHopPeerBinding.Result.MISMATCH
        fun result(rotating: Boolean, maxChildren: Int, debugRelay: Boolean) = StreamHopPeerBinding.check(
            hop.viewer.link.peerNodeId, hop.viewer.endpoint.peerNodeId, maxChildren, rotating, debugRelay,
        )
        assertEquals(expected, result(rotating = false, maxChildren = 1, debugRelay = true))
        // Never for a version 5 hop, never for a link peer that did not sign a relay, never with the switch off.
        assertEquals(StreamHopPeerBinding.Result.MISMATCH, result(rotating = true, maxChildren = 1, debugRelay = true))
        assertEquals(StreamHopPeerBinding.Result.MISMATCH, result(rotating = false, maxChildren = 0, debugRelay = true))
        assertEquals(StreamHopPeerBinding.Result.MISMATCH, result(rotating = false, maxChildren = 1, debugRelay = false))
    }

    @Test
    fun theLaunchSwitchDrivesTheExemptionOnlyInDebugBuilds() {
        val hop = relayed(attackerSignsRelay = true)
        DebugTunables.streamRelayPassthrough = true
        assertEquals(BuildConfig.DEBUG, DebugTunables.streamRelayPassthrough)
        if (BuildConfig.DEBUG) {
            viewerCheck(hop.viewer, rotating = false)
            sourceCheck(hop.source, rotating = false)
        } else {
            assertThrows(IllegalArgumentException::class.java) { viewerCheck(hop.viewer, rotating = false) }
            assertThrows(IllegalArgumentException::class.java) { sourceCheck(hop.source, rotating = false) }
        }
        DebugTunables.streamRelayPassthrough = false
        assertThrows(IllegalArgumentException::class.java) { viewerCheck(hop.viewer, rotating = false) }
    }

    @Test
    fun aPeerCannotClaimAGroupHopToRelayTheHandshake() {
        val message = "Stream source identity changed between discovery and connection"
        // No claim, or a single child: nothing to refuse.
        StreamHopPeerBinding.requireGroupAllowed("viewer", false, 0, message, log = {})
        StreamHopPeerBinding.requireGroupAllowed("viewer", false, 1, message, log = {})
        val error = assertThrows(IllegalArgumentException::class.java) {
            StreamHopPeerBinding.requireGroupAllowed("viewer", false, 2, message, debugRelay = false, log = {})
        }
        assertEquals(message, error.message)
        if (BuildConfig.DEBUG) {
            StreamHopPeerBinding.requireGroupAllowed("viewer", false, 2, message, debugRelay = true, log = {})
        } else {
            assertThrows(IllegalArgumentException::class.java) {
                StreamHopPeerBinding.requireGroupAllowed("viewer", false, 2, message, debugRelay = true, log = {})
            }
        }
    }

    @Test
    fun aVersion5SelectionNeverTakesTheGroupHopEvenWithTheDebugRelaySwitchOn() {
        // A peer that opens its own v5 request and also signs maxChildren 2 (both link hellos really carry it).
        val hop = relayed(attackerChildren = 2)
        assertEquals(2, hop.source.link.peerCapabilities.maxChildren)
        assertEquals(2, hop.viewer.link.peerCapabilities.maxChildren)
        DebugTunables.streamRelayPassthrough = true
        for ((role, view) in listOf("source" to hop.source, "viewer" to hop.viewer)) {
            val log = mutableListOf<String>()
            // The same call both sides make before their group handshake, with the real switch and the real link caps.
            assertThrows(IllegalArgumentException::class.java) {
                StreamHopPeerBinding.requireGroupAllowed(role, true, view.link.peerCapabilities.maxChildren, "refused", log = log::add)
            }
            if (BuildConfig.DEBUG) {
                assertEquals(
                    listOf("BENCHMARK stream_peer_binding: result=group_refused | role=$role | version=5 | hop=group"),
                    log,
                )
            }
            // The same claim for a version 2 selection is the DEBUG manual relay (exempt in DEBUG only).
            if (BuildConfig.DEBUG) {
                StreamHopPeerBinding.requireGroupAllowed(role, false, view.link.peerCapabilities.maxChildren, "refused", log = {})
            }
        }
    }

    @Test
    fun theViewerChecksTheLinkPeerAgainstTheSelectedVersion2Offer() {
        val hop = relayed()
        val offerOfSource = source.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        val offerOfLinkPeer = attacker.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        // The viewer selected the source's offer; the link peer is the attacker, so the join is refused before any handshake.
        assertFalse(StreamHopPeerBinding.linkMatchesOffer(hop.viewer.link.peerNodeId, offerOfSource, exempt = false))
        assertTrue(StreamHopPeerBinding.linkMatchesOffer(hop.viewer.link.peerNodeId, offerOfLinkPeer, exempt = false))
        // Behind the DEBUG relay the offer is the source's, so the link prefix is not compared.
        assertTrue(StreamHopPeerBinding.linkMatchesOffer(hop.viewer.link.peerNodeId, offerOfSource, exempt = true))
    }
}
