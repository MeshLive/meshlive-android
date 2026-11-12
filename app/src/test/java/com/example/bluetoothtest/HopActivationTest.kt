package com.example.bluetoothtest

import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 review P2-1, P3-4, P3-5, P3-6 and P3-7: a hop's activation never outlives its close, never takes the Bluetooth
 * stream down with it, and a switch turned off before CAPS keeps both sides raw.
 */
class HopActivationTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val streamId = UUID.fromString("3d4c5b6a-7980-4a1b-8c2d-3e4f5a6b7c8d")
    private val sourceId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 11).toByte() })

    private fun mesh(sequence: Long): ByteArray =
        MeshEnvelopeCodec.encode(MeshEnvelope(streamId, sourceId, sequence, 8, 0, byteArrayOf(sequence.toByte())))

    private fun content(sequence: Long): ByteArray =
        EndToEndContentProtocol.encodeHeader(EndToEndContentType.entries.first(), sequence, 0, 16) + ByteArray(16) { 5 }

    private fun hop(
        role: HopCapsPlan.HopRole,
        session: SecureBluetoothSession,
        switchOn: () -> Boolean = { true },
        newAgent: (WifiHopPorts) -> WifiHopAgent? = { null },
        log: MutableList<String> = Collections.synchronizedList(ArrayList()),
    ) = HopLink(
        role = role,
        session = session,
        closeRaw = {},
        anyLocalSwitchOn = switchOn,
        log = { line -> log += line },
        owner = RecordingHopOwner(),
        newAgent = newAgent,
        localCaps = { Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1) },
    )

    /** The next end-to-end record (a relay hop's reader also returns the link heartbeats it forwards). */
    private fun takeContent(reads: RecordReadThread): SecureRecord {
        while (true) {
            val record = requireNotNull(reads.take()) { "no record: ${reads.error}" }
            if (record.type == SecureRecordType.END_TO_END_CONTENT) return record
        }
    }

    private fun agentClosed(agent: WifiHopAgent): Boolean =
        WifiHopAgent::class.java.getDeclaredField("closed").also { it.isAccessible = true }.getBoolean(agent)

    @Test
    fun aHopClosedWhileItsPeersCapsActivatesItLeavesNoLiveAgentBehind() {
        val pair = HopPair(tagGate = true)
        val env = FakeEnv()
        val inFactory = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val created = AtomicReference<WifiHopAgent?>()
        val child = hop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
            newAgent = { ports ->
                inFactory.countDown()
                closeDone.await(5, TimeUnit.SECONDS)        // close() runs on another thread right now
                WifiHopAgent(ports, env).also { created.set(it) }
            },
        )
        val reads = RecordReadThread(child.reader)
        try {
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(inFactory.await(5, TimeUnit.SECONDS))
            child.close()
            closeDone.countDown()
            val agent = HopWifiRig.awaitCondition { created.get() != null }.let { requireNotNull(created.get()) }
            assertTrue("the agent made after close was ended", HopWifiRig.awaitCondition { agentClosed(agent) })
            assertFalse("it never logged the CAPS exchange", env.lines.any { it.startsWith("wifi_caps: dir=sent") })
            HopLinks.switchOffAll()
            assertFalse("the closed hop is unregistered", env.lines.any { it.contains("switch_off") })
            assertNotNull("the reader ends", reads.awaitError())
        } finally {
            child.close()
            reads.stop()
            pair.cut()
        }
    }

    @Test
    fun aHopClosedWhileItAnswersThePeersCapsNeverActivates() {
        val pair = HopPair(tagGate = true)
        val factoryCalls = AtomicInteger(0)
        val answering = CountDownLatch(1)
        val closeDone = CountDownLatch(1)
        val child = HopLink(
            role = HopCapsPlan.HopRole.STREAM_CHILD,
            session = pair.b,
            closeRaw = {},
            anyLocalSwitchOn = { true },
            log = {},
            owner = RecordingHopOwner(),
            newAgent = { factoryCalls.incrementAndGet(); null },
            localCaps = {
                answering.countDown()                        // the reading thread is between the peer's CAPS and activation
                closeDone.await(5, TimeUnit.SECONDS)
                Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1)
            },
        )
        val reads = RecordReadThread(child.reader)
        try {
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(answering.await(5, TimeUnit.SECONDS))
            child.close()
            closeDone.countDown()
            assertNotNull("the reader ends", reads.awaitError())
            assertEquals("no CarrierSet or agent after close", 0, factoryCalls.get())
        } finally {
            reads.stop()
            pair.cut()
        }
    }

    @Test
    fun anAgentThatFailsToStartKeepsTheBluetoothStream() {
        val pair = HopPair(tagGate = true)
        val log = Collections.synchronizedList(ArrayList<String>())
        val child = hop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b, log = log,
            newAgent = { throw IllegalStateException("agent construction failed") },
        )
        val reads = RecordReadThread(child.reader)
        try {
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(HopWifiRig.awaitCondition { log.any { it.contains("event=internal_error") } })
            assertTrue(log.single { it.contains("event=internal_error") }.contains("stage=agent"))
            for (sequence in 1L..20L) pair.a.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence))
            pair.a.writer.flush()
            val received = (1..20).map { MeshEnvelopeCodec.decode(requireNotNull(reads.take()).payload).sequence }
            assertEquals((1L..20L).toList(), received)
            assertNull("the stream did not end", reads.error)
        } finally {
            child.close()
            reads.stop()
            pair.cut()
        }
    }

    @Test
    fun theHopKeyIsNotOfferedAfterClose() {
        val rig = HopWifiRig().activate()
        try {
            val ports = rig.childPorts
            val before = requireNotNull(ports.uwCopy())
            assertFalse(before.all { it == 0.toByte() })
            rig.child.close()
            assertNull("no key, not a copy of zeros", ports.uwCopy())
        } finally {
            rig.close()
        }
    }

    @Test
    fun aSwitchTurnedOffBeforeCapsKeepsBothSidesRaw() {
        val pair = HopPair(tagGate = true)
        val childSwitch = AtomicBoolean(true)
        val childFactory = AtomicInteger(0)
        val parentFactory = AtomicInteger(0)
        val child = hop(HopCapsPlan.HopRole.STREAM_CHILD, pair.b, switchOn = childSwitch::get, newAgent = { childFactory.incrementAndGet(); null })
        val parent = hop(HopCapsPlan.HopRole.STREAM_PARENT, pair.a, newAgent = { parentFactory.incrementAndGet(); null })
        assertEquals("built while the switch was on", HopWifiGate.Mode.ACTIVE, child.mode)
        val childReads = RecordReadThread(child.reader)
        val parentReads = RecordReadThread(parent.reader)
        try {
            childSwitch.set(false)
            child.noteFirstHeader()                         // all-clear CAPS; the parent answers with its own
            Thread.sleep(300)
            assertEquals(Bwf.Caps(0, 0, 0).encode().toList(), child.ownCaps.encode().toList())
            assertEquals("the child never activates", 0, childFactory.get())
            assertEquals("the parent never activates", 0, parentFactory.get())
            parent.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(1))
            parent.writer.flush()
            assertEquals(1L, MeshEnvelopeCodec.decode(requireNotNull(childReads.take()).payload).sequence)
            assertEquals(CarrierKind.BLUETOOTH, child.sendKind)
        } finally {
            child.close()
            parent.close()
            childReads.stop()
            parentReads.stop()
            pair.cut()
        }
    }

    @Test
    fun aMalformedEndpointRecordIsRelayedUngovernedAndTheCallGoesOn() {
        val rig = HopWifiRig(
            parentRole = HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
            childRole = HopCapsPlan.HopRole.RELAY_UPSTREAM,
            parentNeighbourIsRelay = true,
        ).activate { parent, _ -> parent.noteContentForwarded() }
        try {
            rig.parent.writer.write(SecureRecordType.END_TO_END_CONTENT, content(1))
            val truncated = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte())
            assertTrue("the relay forwards it", rig.parent.writer.write(SecureRecordType.END_TO_END_CONTENT, truncated) > 0)
            rig.parent.writer.write(SecureRecordType.END_TO_END_CONTENT, content(2))
            rig.parent.writer.flush()
            val first = takeContent(rig.childReads)
            val second = takeContent(rig.childReads)
            val third = takeContent(rig.childReads)
            assertEquals(EndToEndContentProtocol.sequenceOf(content(1)), EndToEndContentProtocol.sequenceOf(first.payload))
            assertEquals(truncated.toList(), second.payload.toList())
            assertEquals(2L, EndToEndContentProtocol.sequenceOf(third.payload))
            assertTrue(rig.parentLog.any { it.startsWith("hop_seq_bad: hop=RELAY_DOWNSTREAM") })
            assertNull("the relayed call did not end", rig.childReads.error)
        } finally {
            rig.close()
        }
    }
}
