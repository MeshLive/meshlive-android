package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HopWifiRulesTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val streamId = UUID.fromString("61d6cc1e-3c45-4ee7-9b1e-d73d1030af11")
    private val source = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 3).toByte() })

    private fun mesh(sequence: Long): ByteArray = MeshEnvelopeCodec.encode(
        MeshEnvelope(streamId, source, sequence, 8, 0, byteArrayOf(1, 2, 3)),
    )

    private fun endToEndOfUnknownType(sequence: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("BME1".toByteArray(Charsets.US_ASCII))
            out.writeByte(0xEE) // A content type this build does not know.
            out.writeLong(sequence)
            out.writeByte(0)
            out.writeInt(16)
            out.write(ByteArray(16) { (it + 9).toByte() })
        }
        return bytes.toByteArray()
    }

    @Test
    fun hopSequenceReadsTheExistingMeshAndEndToEndSequences() {
        assertEquals(41L, HopSequence.of(SecureRecordType.MESH_ENVELOPE, mesh(41)))

        val sealer = EndToEndContentSealer(ByteArray(MeshAead.KEY_BYTES) { (it + 7).toByte() })
        repeat(7) { sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, byteArrayOf(1)) }
        val eighth = sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, byteArrayOf(2))
        assertEquals(7L, HopSequence.of(SecureRecordType.END_TO_END_CONTENT, eighth))
        assertEquals(7L, EndToEndContentProtocol.sequenceOf(eighth))
        sealer.close()

        assertNull(HopSequence.of(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1)))
        assertNull(HopSequence.of(SecureRecordType.ROUTE_CONTROL, byteArrayOf(1)))
        assertNull(HopSequence.of(SecureRecordType.END_TO_END_HANDSHAKE, byteArrayOf(1)))
        assertNull(HopSequence.of(SecureRecordType.BWF1, Bwf.Caps(1, 1, 1).encode()))

        val corrupted = mesh(5).also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            HopSequence.of(SecureRecordType.MESH_ENVELOPE, corrupted)
        }
    }

    @Test
    fun meshSequenceRoundTripsAtTheBoundaries() {
        listOf(0L, 1L, Long.MAX_VALUE - 1).forEach { sequence ->
            assertEquals(sequence, MeshEnvelopeCodec.sequenceOf(mesh(sequence)))
        }
    }

    @Test
    fun aContentTypeTheRelayDoesNotKnowStillHasItsSequence() {
        val payload = endToEndOfUnknownType(1234L)
        assertEquals(1234L, EndToEndContentProtocol.sequenceOf(payload))
        assertEquals(1234L, HopSequence.of(SecureRecordType.END_TO_END_CONTENT, payload))
    }

    @Test
    fun hopWifiGateHasOnlyTheEightModeOutcomes() {
        for (readOpen in listOf(false, true)) {
            for (switchOn in listOf(false, true)) {
                for (carrier0Bluetooth in listOf(false, true)) {
                    val mode = HopWifiGate.mode(readOpen, switchOn, carrier0Bluetooth)
                    val expected = when {
                        !readOpen || !carrier0Bluetooth -> HopWifiGate.Mode.OFF
                        switchOn -> HopWifiGate.Mode.ACTIVE
                        else -> HopWifiGate.Mode.PASSIVE
                    }
                    assertEquals("readOpen=$readOpen switchOn=$switchOn bt0=$carrier0Bluetooth", expected, mode)
                }
            }
        }
    }

    @Test
    fun streamChildSendsItsCapsOnceAfterTheHeader() {
        val plan = HopCapsPlan(HopCapsPlan.HopRole.STREAM_CHILD, HopWifiGate.Mode.ACTIVE, peerProvenCapable = true)
        assertTrue(plan.localIsInitiator)
        assertFalse(plan.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = false))
        assertTrue(plan.shouldSendOwn(firstHeaderSeen = true, contentForwarded = false, peerCapsSeen = false))
        assertTrue(plan.sentOwn)
        assertFalse(plan.shouldSendOwn(firstHeaderSeen = true, contentForwarded = false, peerCapsSeen = false))
        assertFalse(plan.shouldSendOwn(firstHeaderSeen = false, contentForwarded = true, peerCapsSeen = false))
    }

    @Test
    fun streamParentOnlyAnswers() {
        val plan = HopCapsPlan(HopCapsPlan.HopRole.STREAM_PARENT, HopWifiGate.Mode.ACTIVE, peerProvenCapable = true)
        assertFalse(plan.localIsInitiator)
        assertFalse(plan.shouldSendOwn(firstHeaderSeen = true, contentForwarded = true, peerCapsSeen = false))
        assertTrue(plan.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true))
        assertTrue(plan.sentOwn)
    }

    @Test
    fun aBmh3RelayResponderSendsCapsAfterForwardingContentAndTheInitiatorAnswers() {
        val responder = HopCapsPlan(
            HopCapsPlan.HopRole.RELAY_UPSTREAM, HopWifiGate.Mode.ACTIVE,
            peerProvenCapable = true, bmh2TagGate = false,
        )
        assertFalse(responder.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = false))
        assertTrue(responder.shouldSendOwn(firstHeaderSeen = false, contentForwarded = true, peerCapsSeen = false))

        val initiator = HopCapsPlan(
            HopCapsPlan.HopRole.RELAY_DOWNSTREAM, HopWifiGate.Mode.ACTIVE,
            peerProvenCapable = false, bmh2TagGate = false,
        )
        assertTrue(initiator.localIsInitiator)
        // A BMH3 initiator knows only that it signed the marker: it answers, it never sends first.
        assertFalse(initiator.shouldSendOwn(firstHeaderSeen = true, contentForwarded = true, peerCapsSeen = false))
        assertTrue(initiator.shouldSendOwn(firstHeaderSeen = false, contentForwarded = true, peerCapsSeen = true))
    }

    @Test
    fun aBmh2RelayInitiatorSendsCapsFirstOnlyTowardARelayAfterForwarding() {
        val downstream = HopCapsPlan(
            HopCapsPlan.HopRole.RELAY_DOWNSTREAM, HopWifiGate.Mode.ACTIVE,
            peerProvenCapable = true, bmh2TagGate = true, neighbourIsRelay = true,
        )
        assertFalse(downstream.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = false))
        assertTrue(downstream.shouldSendOwn(firstHeaderSeen = false, contentForwarded = true, peerCapsSeen = false))

        val upstream = HopCapsPlan(
            HopCapsPlan.HopRole.RELAY_UPSTREAM, HopWifiGate.Mode.ACTIVE,
            peerProvenCapable = true, bmh2TagGate = true,
        )
        assertFalse(upstream.shouldSendOwn(firstHeaderSeen = true, contentForwarded = true, peerCapsSeen = false))
        assertTrue(upstream.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true))
    }

    @Test
    fun aBmh2RelayNeverSendsCapsFirstTowardACallEndpoint() {
        // The X4 switch-off regression: tags allow the gate, but the neighbour is a call endpoint.
        val plan = HopCapsPlan(
            HopCapsPlan.HopRole.RELAY_DOWNSTREAM, HopWifiGate.Mode.PASSIVE,
            peerProvenCapable = true, bmh2TagGate = true, neighbourIsRelay = false,
        )
        assertFalse(plan.shouldSendOwn(firstHeaderSeen = false, contentForwarded = true, peerCapsSeen = false))
        assertFalse(plan.sentOwn)
    }

    @Test
    fun ownCapsIsSentExactlyOnceUnderContention() {
        val plans = List(10_000) {
            HopCapsPlan(HopCapsPlan.HopRole.STREAM_CHILD, HopWifiGate.Mode.ACTIVE, peerProvenCapable = true)
        }
        val trueCounts = AtomicIntegerArray(plans.size)
        val start = CountDownLatch(1)
        val threads = (0 until 2).map {
            thread(start = true) {
                start.await()
                plans.forEachIndexed { index, plan ->
                    if (plan.shouldSendOwn(firstHeaderSeen = true, contentForwarded = false, peerCapsSeen = false)) {
                        trueCounts.incrementAndGet(index)
                    }
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }
        for (index in plans.indices) {
            assertEquals("plan $index", 1, trueCounts.get(index))
        }
    }

    @Test
    fun aBmh3InitiatorNeverSendsCapsBeforeThePeerDid() {
        val roles = listOf(
            HopCapsPlan.HopRole.STREAM_CHILD,
            HopCapsPlan.HopRole.STREAM_PARENT,
            HopCapsPlan.HopRole.RELAY_UPSTREAM,
            HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
        )
        roles.forEach { role ->
            val plan = HopCapsPlan(role, HopWifiGate.Mode.ACTIVE, peerProvenCapable = false)
            assertFalse("$role", plan.shouldSendOwn(firstHeaderSeen = true, contentForwarded = true, peerCapsSeen = false))
            assertTrue("$role answers", plan.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true))
        }
    }

    @Test
    fun passiveAndOffModesAnswerOnlyWhenACapsArrives() {
        val passive = HopCapsPlan(HopCapsPlan.HopRole.STREAM_PARENT, HopWifiGate.Mode.PASSIVE, peerProvenCapable = true)
        assertFalse(passive.shouldSendOwn(firstHeaderSeen = true, contentForwarded = true, peerCapsSeen = false))
        assertTrue(passive.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true))

        val off = HopCapsPlan(HopCapsPlan.HopRole.STREAM_CHILD, HopWifiGate.Mode.OFF, peerProvenCapable = true)
        assertFalse(off.shouldSendOwn(firstHeaderSeen = true, contentForwarded = true, peerCapsSeen = false))
        assertFalse(off.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true))
        assertFalse(off.sentOwn)
    }

    @Test
    fun aSecondPeerCapsIsRejectedAndAttemptChoiceFiresOnce() {
        val plan = HopCapsPlan(HopCapsPlan.HopRole.STREAM_CHILD, HopWifiGate.Mode.ACTIVE, peerProvenCapable = true)
        val own = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1)
        val peer = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1)
        assertNull("nothing before CAPS", plan.attemptChoice(own))
        assertTrue(plan.shouldSendOwn(firstHeaderSeen = true, contentForwarded = false, peerCapsSeen = false))
        assertTrue(plan.onPeerCaps(peer))
        assertFalse("a repeat CAPS is never used", plan.onPeerCaps(peer))
        assertNotNull(plan.attemptChoice(own))
        assertNull("never twice", plan.attemptChoice(own))

        val passive = HopCapsPlan(HopCapsPlan.HopRole.STREAM_CHILD, HopWifiGate.Mode.PASSIVE, peerProvenCapable = true)
        passive.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true)
        passive.onPeerCaps(peer)
        assertNull("PASSIVE never attempts", passive.attemptChoice(own))
    }

    @Test
    fun twoPlansExchangeTheirCapsAndReachOppositeRoles() {
        val child = HopCapsPlan(HopCapsPlan.HopRole.STREAM_CHILD, HopWifiGate.Mode.ACTIVE, peerProvenCapable = true)
        val parent = HopCapsPlan(HopCapsPlan.HopRole.STREAM_PARENT, HopWifiGate.Mode.ACTIVE, peerProvenCapable = true)
        val childCaps = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1)
        val parentCaps = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1)

        assertTrue("the child sends after its header", child.shouldSendOwn(true, false, false))
        val peerCapsForParent = WifiWire.decodeBwf1(childCaps.encode()) as Bwf.Caps
        assertTrue(parent.onPeerCaps(peerCapsForParent))
        assertTrue("the parent answers", parent.shouldSendOwn(false, false, true))
        val peerCapsForChild = WifiWire.decodeBwf1(parentCaps.encode()) as Bwf.Caps
        assertTrue(child.onPeerCaps(peerCapsForChild))

        val childChoice = assertNotNull(child.attemptChoice(childCaps))
        val parentChoice = assertNotNull(parent.attemptChoice(parentCaps))
        assertEquals(WifiBearer.AWARE, childChoice.bearer)
        assertEquals(WifiBearer.AWARE, parentChoice.bearer)
        assertEquals(childChoice.localIsHost, !parentChoice.localIsHost)
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertTrue("expected non-null", value != null)
        return value!!
    }
}
