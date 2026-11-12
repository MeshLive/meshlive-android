package com.example.bluetoothtest

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-B3: a selected direct call (routeCost 0) must reach the callee itself. The attack is built from real handshakes:
 * a phone advertising the callee's prefix authenticates its own link to the caller, dials the callee and forwards the
 * callee's genuine end-to-end handshake. The caller then runs the same check the call setup runs in production.
 */
class DirectCallPeerBindingTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private fun caps() = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_000,
        maxChildren = 0,
    )

    private class Pipes {
        val aIn = PipedInputStream(64 * 1024)
        val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(64 * 1024)
        val aOut = PipedOutputStream(bIn)
    }

    /** What the caller knows after both handshakes. */
    private class CallerView(val link: SecureBluetoothSession, val endpoint: EndToEndVideoSession)

    private val ids = List(3) { KeyPairSigningIdentity(generateEcKeyPair()) }
    private val caller = ids[0]
    private val callee = ids[1]
    private val attacker = ids[2]

    private fun offerFor(identity: KeyPairSigningIdentity, routeCost: Int) = DiscoveryAnnouncement(
        nodeIdPrefix = identity.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        capabilitySummary = NodeCapabilities.CODEC_VP9,
        streamToken = 0,
        routeCost = routeCost,
        sequence = 0,
    )

    /** The caller dials the callee directly: the same key at both layers. */
    private fun direct(): CallerView {
        val p = Pipes()
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(p.bIn, p.bOut, callee, caps())
                EndToEndVideoHandshake.respond(link, callee, caps())
            }.onFailure(failure::set)
        }
        val link = SecureBluetoothHandshake.initiate(p.aIn, p.aOut, caller, caps())
        val view = CallerView(link, EndToEndVideoHandshake.initiate(link, caller, caps()))
        worker.join(120_000)
        failure.get()?.let { throw AssertionError("callee failed", it) }
        return view
    }

    /** The attacker is the caller's link peer and forwards the callee's real end-to-end handshake. */
    private fun relayed(): CallerView {
        val toCaller = Pipes()
        val toCallee = Pipes()
        val failure = AtomicReference<Throwable?>()
        val calleeThread = thread {
            runCatching {
                val link = SecureBluetoothHandshake.respond(toCallee.bIn, toCallee.bOut, callee, caps())
                EndToEndVideoHandshake.respond(link, callee, caps())
            }.onFailure(failure::set)
        }
        val attackerThread = thread {
            runCatching {
                val upstream = SecureBluetoothHandshake.respond(toCaller.bIn, toCaller.bOut, attacker, caps())
                val downstream = SecureBluetoothHandshake.initiate(toCallee.aIn, toCallee.aOut, attacker, caps())
                val callerHello = upstream.reader.read()
                downstream.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, callerHello.payload)
                downstream.writer.flush()
                val calleeHello = downstream.reader.read()
                upstream.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, calleeHello.payload)
                upstream.writer.flush()
            }.onFailure(failure::set)
        }
        val link = SecureBluetoothHandshake.initiate(toCaller.aIn, toCaller.aOut, caller, caps())
        val view = CallerView(link, EndToEndVideoHandshake.initiate(link, caller, caps()))
        calleeThread.join(120_000)
        attackerThread.join(120_000)
        failure.get()?.let { throw AssertionError("a relay party failed", it) }
        return view
    }

    private fun check(view: CallerView, selected: DiscoveryAnnouncement, log: (String) -> Unit = {}) =
        DirectCallPeerBinding.requireBound(
            selected, view.link.peerNodeId, view.endpoint.peerNodeId,
            "Call peer identity changed between discovery and connection", log,
        )

    @Test
    fun aDirectOfferWhoseEndToEndHandshakeArrivesOverAnotherLinkIdentityIsRefused() {
        val view = relayed()
        val selected = offerFor(callee, routeCost = 0)
        // The old setup check passes: the genuine callee's prefix matches the selected offer at the end-to-end layer.
        assertTrue(
            view.endpoint.peerNodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                .contentEquals(selected.nodeIdPrefix),
        )
        assertEquals(attacker.nodeId, view.link.peerNodeId)
        assertEquals(callee.nodeId, view.endpoint.peerNodeId)
        val log = mutableListOf<String>()
        val failure = assertThrows(IllegalArgumentException::class.java) { check(view, selected, log::add) }
        assertEquals("Call peer identity changed between discovery and connection", failure.message)
        if (BuildConfig.DEBUG) {
            assertEquals(listOf("BENCHMARK call_peer_binding: result=mismatch | version=2 | route=direct"), log)
        } else {
            assertTrue("a release build logs nothing extra", log.isEmpty())
        }
    }

    @Test
    fun aDirectOfferReachedDirectlyPasses() {
        val view = direct()
        assertEquals(view.link.peerNodeId, view.endpoint.peerNodeId)
        val log = mutableListOf<String>()
        check(view, offerFor(callee, routeCost = 0), log::add)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aRelayedOfferKeepsItsIntentionalDifferenceBetweenHelperAndCallee() {
        // routeCost > 0: the first hop is a helper by design; the consent gates, not this rule, govern it.
        val view = relayed()
        val log = mutableListOf<String>()
        check(view, offerFor(callee, routeCost = 1), log::add)
        assertTrue(log.isEmpty())
        assertEquals(
            DirectCallPeerBinding.Result.RELAYED,
            DirectCallPeerBinding.check(2, view.link.peerNodeId, view.endpoint.peerNodeId),
        )
    }

    @Test
    fun fullNodeEqualityIsRequiredNotAPrefix() {
        val a = ByteArray(NodeId.BYTE_COUNT) { 1 }
        val b = a.copyOf().also { it[NodeId.BYTE_COUNT - 1] = 2 }
        assertEquals(
            DirectCallPeerBinding.Result.MISMATCH,
            DirectCallPeerBinding.check(0, NodeId.fromBytes(a), NodeId.fromBytes(b)),
        )
        assertEquals(
            DirectCallPeerBinding.Result.BOUND,
            DirectCallPeerBinding.check(0, NodeId.fromBytes(a), NodeId.fromBytes(a.copyOf())),
        )
    }
}
