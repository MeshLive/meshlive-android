package com.example.bluetoothtest

import java.net.Socket
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two connected raw sessions over byte pipes, with per-direction write gates (A7 HopLink tests). */
internal class HopPair(
    tagGate: Boolean = false,
    ownWifiMarkerA: Boolean = false,
    ownWifiMarkerB: Boolean = false,
    peerWifiMarkerA: Boolean = false,
    peerWifiMarkerB: Boolean = false,
    hopWifiKey: ByteArray? = ByteArray(32) { 4 },
) {
    val aToB = BytePipe(1 shl 20)
    val bToA = BytePipe(1 shl 20)
    private val gateA = GateOutputStream(aToB)
    private val gateB = GateOutputStream(bToA)
    private val keyAB = ByteArray(32) { (it + 1).toByte() }
    private val keyBA = ByteArray(32) { (it + 101).toByte() }
    val idA = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 0x10).toByte() })
    val idB = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 0x40).toByte() })

    private fun caps() = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_GATT,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_100,
        maxChildren = 0,
    )

    val a = SecureBluetoothSession(
        peerNodeId = idB,
        peerCapabilities = caps(),
        reader = SecureRecordReader(bToA.input(), keyBA),
        writer = SecureRecordWriter(gateA, keyAB),
        ownWifiMarker = ownWifiMarkerA,
        peerWifiMarker = peerWifiMarkerA,
        wifiBuildTagGate = tagGate,
        hopWifiKey = hopWifiKey,
    )
    val b = SecureBluetoothSession(
        peerNodeId = idA,
        peerCapabilities = caps(),
        reader = SecureRecordReader(aToB.input(), keyAB),
        writer = SecureRecordWriter(gateB, keyBA),
        ownWifiMarker = ownWifiMarkerB,
        peerWifiMarker = peerWifiMarkerB,
        wifiBuildTagGate = tagGate,
        hopWifiKey = hopWifiKey,
    )

    fun blockWritesFromA(blocked: Boolean) = gateA.setOpen(!blocked)

    fun blockWritesFromB(blocked: Boolean) = gateB.setOpen(!blocked)

    fun cut() {
        gateA.setOpen(true)
        gateB.setOpen(true)
        aToB.close()
        bToA.close()
    }
}

internal class RecordingHopOwner : HopOwner {
    val events = CopyOnWriteArrayList<String>()

    @Volatile var endedThread: Thread? = null
    @Volatile var endedReason: String? = null

    override fun sendCarrierChanged(kind: CarrierKind) {
        events += "carrier:${kind.name}"
    }

    override fun localKeyframeNeeded() {
        events += "local_kf"
    }

    override fun peerKeyframeNeeded() {
        events += "peer_kf"
    }

    override fun reassertState() {
        events += "reassert"
    }

    override fun hopEnded(reason: String) {
        endedThread = Thread.currentThread()
        endedReason = reason
        events += "ended:$reason"
    }
}

/** Reads a hop source on its own thread and collects everything it returns. */
internal class RecordReadThread(private val source: HopRecordSource, name: String = "hop-reader-test") {
    val records = LinkedBlockingQueue<SecureRecord>()

    @Volatile var error: Exception? = null

    private val worker = thread(name = name, isDaemon = true) {
        try {
            while (true) records.put(source.read())
        } catch (error: Exception) {
            this.error = error
        }
    }

    fun take(timeoutMs: Long = 5_000): SecureRecord? = records.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitError(timeoutMs: Long = 5_000): Exception? {
        val end = System.currentTimeMillis() + timeoutMs
        while (error == null && System.currentTimeMillis() < end) Thread.sleep(10)
        return error
    }

    fun stop() {
        worker.interrupt()
    }
}

class HopLinkTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val streamId = java.util.UUID.fromString("0f0fc6e2-6b58-4c47-9e40-0e73f6a1c111")
    private val sourceId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 5).toByte() })

    private fun mesh(sequence: Long): ByteArray = MeshEnvelopeCodec.encode(
        MeshEnvelope(streamId, sourceId, sequence, 8, 0, byteArrayOf(sequence.toByte())),
    )

    private fun makeHop(
        role: HopCapsPlan.HopRole,
        session: SecureBluetoothSession,
        switchOn: Boolean = true,
        neighbourIsRelay: Boolean = false,
        carrier0IsBluetooth: Boolean = true,
        owner: RecordingHopOwner = RecordingHopOwner(),
        log: MutableList<String> = Collections.synchronizedList(ArrayList()),
        caps: Bwf.Caps = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1),
        newAgent: (WifiHopPorts) -> WifiHopAgent? = { null },
        nanoClock: (() -> Long)? = null,
        closeRaw: () -> Unit = {},
    ): HopLink {
        val logSink: (String) -> Unit = { line -> log += line }
        return if (nanoClock == null) {
            HopLink(
                role = role, session = session, closeRaw = closeRaw,
                anyLocalSwitchOn = { switchOn }, log = logSink, owner = owner, newAgent = newAgent,
                neighbourIsRelay = neighbourIsRelay, carrier0IsBluetooth = carrier0IsBluetooth,
                localCaps = { caps },
            )
        } else {
            HopLink(
                role = role, session = session, closeRaw = closeRaw,
                anyLocalSwitchOn = { switchOn }, log = logSink, owner = owner, newAgent = newAgent,
                neighbourIsRelay = neighbourIsRelay, carrier0IsBluetooth = carrier0IsBluetooth,
                localCaps = { caps }, nanoClock = nanoClock,
            )
        }
    }

    /** Starts a reader thread on [hop], sends the peer's CAPS and waits for this hop to activate. */
    private fun soloActivate(
        hop: HopLink,
        peer: SecureBluetoothSession,
        activated: CountDownLatch,
        peerCaps: Bwf.Caps = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1),
    ): RecordReadThread {
        val reads = RecordReadThread(hop.reader)
        peer.writer.write(SecureRecordType.BWF1, peerCaps.encode())
        peer.writer.flush()
        assertTrue("the hop activated on its reader thread", activated.await(5, TimeUnit.SECONDS))
        return reads
    }

    @Test
    fun offIsInvisible() {
        val pair = HopPair(tagGate = false)
        try {
            val hop = makeHop(HopCapsPlan.HopRole.STREAM_PARENT, pair.a, switchOn = true)
            assertEquals(HopWifiGate.Mode.OFF, hop.mode)
            assertFalse(pair.a.reader.acceptWifi)
            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(1))
            hop.writer.flush()
            val record = pair.b.reader.read()
            assertEquals(SecureRecordType.MESH_ENVELOPE, record.type)
            assertArrayEquals(mesh(1), record.payload)

            pair.b.writer.write(SecureRecordType.BWF1, Bwf.Caps(1, 0, 0).encode())
            pair.b.writer.flush()
            val error = assertThrows(IllegalStateException::class.java) { hop.reader.read() }
            assertTrue(error.message.orEmpty().contains("Unknown secure record type"))
        } finally {
            pair.cut()
        }
    }

    @Test
    fun passiveAnswersWithAnAllClearCaps() {
        val pair = HopPair(tagGate = false, ownWifiMarkerA = true)
        try {
            val hop = makeHop(HopCapsPlan.HopRole.STREAM_PARENT, pair.a, switchOn = false)
            assertEquals(HopWifiGate.Mode.PASSIVE, hop.mode)
            pair.b.reader.acceptWifi = true
            pair.b.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.b.writer.write(SecureRecordType.END_TO_END_CONTENT, byteArrayOf(9, 9))
            pair.b.writer.flush()

            val record = hop.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, record.type)
            assertArrayEquals(byteArrayOf(9, 9), record.payload)
            val answer = WifiWire.decodeBwf1(pair.b.reader.read().payload) as Bwf.Caps
            assertEquals("all bits clear", 0, answer.bearers)
            assertEquals(0, answer.freeHost)
            assertEquals(0, answer.freeGuest)
            hop.close()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun aParentAndChildExchangeCapsAndBothActivateOnce() {
        val pair = HopPair(tagGate = true)
        val childEnv = FakeEnv()
        val parentEnv = FakeEnv()
        val childActivated = CountDownLatch(1)
        val parentActivated = CountDownLatch(1)
        val childAgentThreads = CopyOnWriteArrayList<Thread>()
        val parentAgentThreads = CopyOnWriteArrayList<Thread>()
        val child = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
            newAgent = { ports -> childAgentThreads += Thread.currentThread(); childActivated.countDown(); WifiHopAgent(ports, childEnv) },
        )
        val parent = makeHop(
            HopCapsPlan.HopRole.STREAM_PARENT, pair.a,
            newAgent = { ports -> parentAgentThreads += Thread.currentThread(); parentActivated.countDown(); WifiHopAgent(ports, parentEnv) },
        )
        val childReads = RecordReadThread(child.reader)
        val parentReads = RecordReadThread(parent.reader)
        try {
            child.noteFirstHeader()
            assertTrue(childActivated.await(5, TimeUnit.SECONDS))
            assertTrue(parentActivated.await(5, TimeUnit.SECONDS))
            assertEquals(1, childAgentThreads.size)
            assertEquals(1, parentAgentThreads.size)
            assertTrue("the child logged its own CAPS", awaitCaps(childEnv, "sent"))
            assertTrue("the child logged the peer's CAPS", awaitCaps(childEnv, "recv"))
            assertTrue("the parent logged its own CAPS", awaitCaps(parentEnv, "sent"))
            assertTrue("the parent logged the peer's CAPS", awaitCaps(parentEnv, "recv"))
        } finally {
            child.close()
            parent.close()
            childReads.stop()
            parentReads.stop()
            pair.cut()
        }
    }

    private fun awaitCaps(env: FakeEnv, dir: String, timeoutMs: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (env.linesNow().count { it.startsWith("wifi_caps: dir=$dir") } == 1) return true
            Thread.sleep(10)
        }
        return env.linesNow().count { it.startsWith("wifi_caps: dir=$dir") } == 1
    }

    /**
     * A copy of the log lines taken under the list's own lock: the hop's reader thread keeps logging while a test reads
     * them, and iterating a synchronized list without its lock threw ConcurrentModificationException (a test flake).
     */
    private fun FakeEnv.linesNow(): List<String> = synchronized(lines) { lines.toList() }

    @Test
    fun activationHandsOverReadingWithoutLossOrDuplication() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
            newAgent = { _ -> activated.countDown(); null },
        )
        val reads = RecordReadThread(hop.reader)
        try {
            pair.a.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(0))
            pair.a.writer.flush()
            hop.noteFirstHeader()                     // child CAPS
            pair.a.reader.acceptWifi = true
            val childCaps = pair.a.reader.read()
            assertEquals(SecureRecordType.BWF1, childCaps.type)
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(activated.await(5, TimeUnit.SECONDS))

            for (sequence in 1L..5L) {
                pair.a.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence))
            }
            pair.a.writer.flush()
            val received = ArrayList<Long>()
            repeat(6) {
                val record = requireNotNull(reads.take())
                assertEquals(SecureRecordType.MESH_ENVELOPE, record.type)
                received += MeshEnvelopeCodec.decode(record.payload).sequence
            }
            assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), received)
        } finally {
            hop.close()
            reads.stop()
            pair.cut()
        }
    }

    @Test
    fun noRawWriteIsConcurrentWithActivation() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
            newAgent = { _ -> activated.countDown(); null },
        )
        val peerReads = RecordReadThread(pair.a.reader)
        try {
            val hopReads = RecordReadThread(hop.reader)
            pair.a.reader.acceptWifi = true
            hop.noteFirstHeader()
            assertEquals(SecureRecordType.BWF1, requireNotNull(peerReads.take()).type)
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(activated.await(5, TimeUnit.SECONDS))

            val count = 200
            val writer = thread(name = "hop-test-writer") {
                for (sequence in 0 until count) {
                    hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence.toLong()))
                }
                hop.writer.flush()
            }
            writer.join(30_000)
            val sequences = ArrayList<Long>()
            repeat(count) {
                val record = requireNotNull(peerReads.take())
                assertEquals(SecureRecordType.MESH_ENVELOPE, record.type)
                sequences += MeshEnvelopeCodec.decode(record.payload).sequence
            }
            assertEquals((0 until count).map { it.toLong() }, sequences)
            hopReads.stop()
        } finally {
            hop.close()
            peerReads.stop()
            pair.cut()
        }
    }

    @Test
    fun hopControlIsUngovernedAndReachesThePeer() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val log = Collections.synchronizedList(ArrayList<String>())
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b, log = log,
            newAgent = { _ -> activated.countDown(); null },
        )
        val peerReads = RecordReadThread(pair.a.reader)
        try {
            val hopReads = RecordReadThread(hop.reader)
            pair.a.reader.acceptWifi = true
            hop.sendHopControl(SecureRecordType.ROUTE_CONTROL, byteArrayOf(1))
            hop.writer.flush()
            assertEquals(SecureRecordType.ROUTE_CONTROL, requireNotNull(peerReads.take()).type)

            hop.noteFirstHeader()
            assertEquals(SecureRecordType.BWF1, requireNotNull(peerReads.take()).type)
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(activated.await(5, TimeUnit.SECONDS))

            hop.sendHopControl(SecureRecordType.ROUTE_CONTROL_ACK, byteArrayOf(2))
            hop.writer.flush()
            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(7))
            hop.writer.flush()
            val types = listOf(requireNotNull(peerReads.take()).type, requireNotNull(peerReads.take()).type)
            assertEquals(listOf(SecureRecordType.ROUTE_CONTROL_ACK, SecureRecordType.MESH_ENVELOPE), types)
            hopReads.stop()
        } finally {
            hop.close()
            peerReads.stop()
            pair.cut()
        }
    }

    @Test
    fun aMixedGovernedSpaceIsSentUngovernedAndLogged() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val log = Collections.synchronizedList(ArrayList<String>())
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b, log = log,
            newAgent = { _ -> activated.countDown(); null },
        )
        val peerReads = RecordReadThread(pair.a.reader)
        try {
            val hopReads = RecordReadThread(hop.reader)
            pair.a.reader.acceptWifi = true
            hop.noteFirstHeader()
            assertEquals(SecureRecordType.BWF1, requireNotNull(peerReads.take()).type)
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(activated.await(5, TimeUnit.SECONDS))

            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(1))
            hop.writer.write(SecureRecordType.END_TO_END_CONTENT, byteArrayOf(1, 2, 3))
            hop.writer.flush()
            assertEquals(SecureRecordType.MESH_ENVELOPE, requireNotNull(peerReads.take()).type)
            assertEquals(SecureRecordType.END_TO_END_CONTENT, requireNotNull(peerReads.take()).type)
            assertTrue(log.any { it.contains("hop_seq_mixed") })
            hopReads.stop()
        } finally {
            hop.close()
            peerReads.stop()
            pair.cut()
        }
    }

    @Test
    fun closeIsIdempotentAndZeroesTheHopKey() {
        val pair = HopPair(tagGate = true)
        try {
            val hop = makeHop(HopCapsPlan.HopRole.STREAM_PARENT, pair.a)
            val threads = (0 until 2).map { thread { hop.close() } }
            threads.forEach { it.join(5_000) }
            assertTrue(requireNotNull(pair.a.hopWifiKey).all { it == 0.toByte() })
            hop.close()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun aBmh3InitiatorStaysSilentUntilThePeerSendsCaps() {
        val pair = HopPair(tagGate = false, ownWifiMarkerA = true)
        try {
            val activated = CountDownLatch(1)
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.a,
                newAgent = { _ -> activated.countDown(); null },
            )
            val hopReads = RecordReadThread(hop.reader)
            val peerReads = RecordReadThread(pair.b.reader)
            pair.b.reader.acceptWifi = true
            hop.noteFirstHeader()
            assertNull("nothing is written before the peer proves it reads BWF1", peerReads.take(300))
            assertFalse(activated.await(100, TimeUnit.MILLISECONDS))

            pair.b.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.b.writer.flush()
            assertEquals(SecureRecordType.BWF1, requireNotNull(peerReads.take()).type)
            assertTrue(activated.await(5, TimeUnit.SECONDS))
            hop.close()
            hopReads.stop()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun switchOffAllReachesEveryRegisteredHop() {
        val firstEnv = FakeEnv()
        val secondEnv = FakeEnv()
        val first = activatedHopWithAgent(firstEnv)
        val second = activatedHopWithAgent(secondEnv)
        try {
            HopLinks.switchOffAll()
            assertTrue(firstEnv.linesNow().any { it.contains("switch_off") })
            assertTrue(secondEnv.linesNow().any { it.contains("switch_off") })

            val firstCount = firstEnv.linesNow().count { it.contains("switch_off") }
            first.hop.close()
            HopLinks.switchOffAll()
            assertEquals("a closed hop is no longer called", firstCount, firstEnv.linesNow().count { it.contains("switch_off") })
            assertTrue(secondEnv.linesNow().count { it.contains("switch_off") } >= 1)
        } finally {
            first.hop.close()
            second.hop.close()
            first.pair.cut()
            second.pair.cut()
        }
    }

    private class ActivatedHop(val hop: HopLink, val pair: HopPair, val reads: RecordReadThread)

    private fun activatedHopWithAgent(env: FakeEnv): ActivatedHop {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
            newAgent = { ports -> activated.countDown(); WifiHopAgent(ports, env) },
        )
        val reads = soloActivate(hop, pair.a, activated)
        // The latch fires inside newAgent, before the hop stores the agent; its caps line comes after, so wait for it
        // (a switch-off sent in between reaches no agent; the attempt itself still reads the live switch).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (env.linesNow().none { it.contains("wifi_caps") } && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("the agent exchanged caps", env.linesNow().any { it.contains("wifi_caps") })
        return ActivatedHop(hop, pair, reads)
    }

    @Test
    fun aWindowsCallerAnswersTheRelaysCapsAllClearAndTheEdgeStaysRaw() {
        val pair = HopPair(tagGate = false, peerWifiMarkerA = true)
        try {
            val activated = CountDownLatch(1)
            val hop = makeHop(
                HopCapsPlan.HopRole.RELAY_UPSTREAM, pair.a,
                newAgent = { _ -> activated.countDown(); null },
            )
            pair.b.reader.acceptWifi = true
            hop.noteContentForwarded()
            val relayCaps = pair.b.reader.read()
            assertEquals(SecureRecordType.BWF1, relayCaps.type)

            // The Windows caller (BMH3 initiator) answers with every bit clear.
            pair.b.writer.write(SecureRecordType.BWF1, Bwf.Caps(0, 0, 0).encode())
            pair.b.writer.write(SecureRecordType.END_TO_END_CONTENT, byteArrayOf(4, 4))
            pair.b.writer.flush()
            val content = hop.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, content.type)
            assertFalse("no carrier set: the peer offers no bearer", activated.await(200, TimeUnit.MILLISECONDS))

            // An all-clear CAPS that arrives before this side sent is answered once and also leaves the edge raw.
            val second = HopPair(tagGate = false, peerWifiMarkerA = true)
            val secondActivated = CountDownLatch(1)
            val secondHop = makeHop(
                HopCapsPlan.HopRole.RELAY_UPSTREAM, second.a,
                newAgent = { _ -> secondActivated.countDown(); null },
            )
            val secondReads = RecordReadThread(secondHop.reader)
            second.b.reader.acceptWifi = true
            second.b.writer.write(SecureRecordType.BWF1, Bwf.Caps(0, 0, 0).encode())
            second.b.writer.write(SecureRecordType.END_TO_END_CONTENT, byteArrayOf(5))
            second.b.writer.flush()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, requireNotNull(secondReads.take()).type)
            assertEquals(SecureRecordType.BWF1, second.b.reader.read().type)
            assertFalse(secondActivated.await(200, TimeUnit.MILLISECONDS))
            secondHop.close()
            secondReads.stop()
            second.cut()
            hop.close()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun activationRunsOnlyOnTheReadingThread() {
        val pair = HopPair(tagGate = true)
        try {
            val activated = CountDownLatch(1)
            val agentThread = AtomicReference<Thread?>()
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
                newAgent = { _ ->
                    agentThread.set(Thread.currentThread())
                    activated.countDown()
                    null
                },
            )
            val reads = RecordReadThread(hop.reader, name = "the-hop-reader")
            hop.noteFirstHeader()
            pair.a.reader.acceptWifi = true
            val childCaps = pair.a.reader.read()
            assertEquals(SecureRecordType.BWF1, childCaps.type)
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(activated.await(5, TimeUnit.SECONDS))
            assertEquals("the-hop-reader", requireNotNull(agentThread.get()).name)
            hop.close()
            reads.stop()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun switchOffDirectReachesOnlyDirectHops() {
        val directEnv = FakeEnv()
        val awareEnv = FakeEnv()
        val direct = activatedHopWithCaps(
            directEnv,
            localCaps = Bwf.Caps(Bwf.Caps.BEARER_DIRECT_GO, 0, 0),
            peerCaps = Bwf.Caps(Bwf.Caps.BEARER_DIRECT_CLIENT, 0, 0),
        )
        val aware = activatedHopWithCaps(
            awareEnv,
            localCaps = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1),
            peerCaps = Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1),
        )
        try {
            assertTrue(direct.hop.usesDirect())
            assertFalse(aware.hop.usesDirect())
            HopLinks.switchOffDirect()
            assertTrue(directEnv.linesNow().any { it.contains("switch_off") })
            assertFalse("an Aware hop keeps its carrier", awareEnv.linesNow().any { it.contains("switch_off") })
        } finally {
            direct.hop.close()
            aware.hop.close()
            direct.pair.cut()
            aware.pair.cut()
        }
    }

    private fun activatedHopWithCaps(env: FakeEnv, localCaps: Bwf.Caps, peerCaps: Bwf.Caps): ActivatedHop {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b, caps = localCaps,
            newAgent = { ports -> activated.countDown(); WifiHopAgent(ports, env) },
        )
        val reads = soloActivate(hop, pair.a, activated, peerCaps)
        return ActivatedHop(hop, pair, reads)
    }

    @Test
    fun aWifiProtocolViolationNeverClosesTheBluetoothCarrier() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val portsRef = AtomicReference<WifiHopPorts?>()
        val lines = CopyOnWriteArrayList<String>()
        val previousSink = WifiLog.sink
        WifiLog.sink = { body -> lines += body }
        try {
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
                newAgent = { ports -> portsRef.set(ports); activated.countDown(); null },
            )
            val reads = soloActivate(hop, pair.a, activated)
            requireNotNull(portsRef.get()).protocolViolation(0, "record_on_carrier")
            assertTrue(lines.any { it.startsWith("bwf1_unexpected: carrier=0") })

            pair.a.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(9))
            pair.a.writer.flush()
            assertEquals(SecureRecordType.MESH_ENVELOPE, requireNotNull(reads.take()).type)
            hop.close()
            reads.stop()
        } finally {
            WifiLog.sink = previousSink
            pair.cut()
        }
    }

    @Test
    fun aLiveStreamHopIsOtherBluetoothMediaForACallAndKeepsTheFiveGhzRuleItself() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val portsRef = AtomicReference<WifiHopPorts?>()
        try {
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
                newAgent = { ports -> portsRef.set(ports); activated.countDown(); null },
            )
            val reads = soloActivate(hop, pair.a, activated)
            // AP10: a call on this phone sees the stream hop (here the parent this phone watches) as other Bluetooth media.
            assertTrue(OtherBluetoothMedia.forCall(relayDuty = false))
            // The hop itself cannot see whether this phone is in a call, so it keeps the 5 GHz rule.
            assertTrue(requireNotNull(portsRef.get()).otherBluetoothMedia())
            hop.close()
            reads.stop()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun aMalformedRawBwf1EndsWifiButNotTheHop() {
        val pair = HopPair(tagGate = false, ownWifiMarkerA = true)
        try {
            val log = Collections.synchronizedList(ArrayList<String>())
            val hop = makeHop(HopCapsPlan.HopRole.STREAM_PARENT, pair.a, log = log)
            pair.b.reader.acceptWifi = true
            // Correct magic and kind, a body that cannot be a CAPS; an unknown kind is ignored first.
            pair.b.writer.write(SecureRecordType.BWF1, byteArrayOf('B'.code.toByte(), 'W'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte(), 99, 0))
            pair.b.writer.write(SecureRecordType.BWF1, byteArrayOf('B'.code.toByte(), 'W'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte(), 1, 0, 0))
            pair.b.writer.write(SecureRecordType.END_TO_END_CONTENT, byteArrayOf(7))
            pair.b.writer.flush()

            val content = hop.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, content.type)
            assertTrue(log.any { it.startsWith("bwf1_unexpected: kind=?") })
            assertTrue(log.any { it.startsWith("bwf1_bad: hop=STREAM_PARENT") })
            assertNull("no CAPS is sent after the bad record", pair.b.reader.pollAvailable())
            hop.close()
        } finally {
            pair.cut()
        }
    }

    private fun SecureRecordReader.pollAvailable(): SecureRecord? {
        // The pipe has an in-process reader; a short blocking read distinguishes "nothing" from a record.
        val result = AtomicReference<SecureRecord?>()
        val t = thread(isDaemon = true) { result.set(runCatching { read() }.getOrNull()) }
        t.join(300)
        return result.get()
    }

    @Test
    fun carrierSampleReportsTheOldestQueuedAge() {
        val pair = HopPair(tagGate = true)
        val clock = AtomicLong(0L)
        val activated = CountDownLatch(1)
        val portsRef = AtomicReference<WifiHopPorts?>()
        var wifi: PipePair? = null
        try {
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
                newAgent = { ports -> portsRef.set(ports); activated.countDown(); null },
                nanoClock = { clock.get() },
            )
            val reads = soloActivate(hop, pair.a, activated)
            clock.set(3_100_000_000L)                 // past the 3 s active-before-switch bound
            val carrierPair = PipePair(kind = CarrierKind.WIFI_AWARE, label = "wifi", liveness = CarrierLivenessPolicy.WIFI)
            wifi = carrierPair
            carrierPair.blockWritesFromA(true)
            val carrier = WifiSocketCarrier(
                kind = CarrierKind.WIFI_AWARE,
                socket = Socket(),
                link = carrierPair.a.link,
                releaseLink = { carrierPair.cut() },
                ledger = WifiLedger { clock.get() / 1_000_000L },
                ndpOwner = null,
                ticket = ByteArray(8) { 1 },
                peerReady = Bwf.Ready(0xFFFF, 0, 0xFFFF),
                bandVerified = false,
                channelMhz = null,
            )
            val id = requireNotNull(portsRef.get()).offerCarrier(carrier)
            assertTrue(id in 1..62)

            // The switch happens on the next monitor tick; write once it is on the Wi-Fi carrier.
            val deadline = System.currentTimeMillis() + 5_000
            var sample: CarrierSample? = null
            var sequence = 1L
            while (System.currentTimeMillis() < deadline) {
                hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence))
                sequence++
                hop.writer.flush()
                sample = requireNotNull(portsRef.get()).carrierSample(id)
                if ((sample?.queuedContent ?: 0) > 0 || (sample?.oldestQueuedAgeMs ?: 0) > 0) break
                Thread.sleep(50)
            }
            assertTrue("the record reached the blocked Wi-Fi carrier", (sample?.queuedContent ?: 0) > 0)
            clock.addAndGet(400_000_000L)
            val aged = requireNotNull(portsRef.get()).carrierSample(id)
            assertTrue("oldest queued age ${aged?.oldestQueuedAgeMs}", (aged?.oldestQueuedAgeMs ?: 0) >= 300)
            hop.close()
            reads.stop()
        } finally {
            wifi?.cut()
            pair.cut()
        }
    }

    @Test
    fun hopEndedNeverRunsOnACarrierSetThread() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        try {
            val owner = RecordingHopOwner()
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.b, owner = owner,
                newAgent = { _ -> activated.countDown(); null },
            )
            val reads = soloActivate(hop, pair.a, activated)
            pair.cut()
            val deadline = System.currentTimeMillis() + 5_000
            while (owner.endedThread == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            val thread = requireNotNull(owner.endedThread)
            assertTrue("thread name ${thread.name}", thread.name.startsWith("hop-"))
            assertTrue("thread name ${thread.name}", thread.name.endsWith("-end"))
            assertEquals(1, owner.events.count { it.startsWith("ended:") })
            hop.close()
            reads.stop()
        } finally {
            pair.cut()
        }
    }

    @Test
    fun aLiveStreamRelayChildsCapsNeverEndsTheRelayLink() {
        val pair = HopPair(tagGate = true)
        try {
            pair.a.reader.acceptWifi = true
            pair.b.reader.acceptWifi = true
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            SecureLinkControlProtocol.writeHeartbeat(pair.a.writer)
            pair.a.writer.flush()
            val record = readSkippingBwf1(pair.b.reader)
            assertEquals(SecureRecordType.LINK_HEARTBEAT, record.type)

            // Without the helper the same reader fails on the child's CAPS exactly as an old build would.
            val other = HopPair(tagGate = true)
            try {
                other.b.reader.acceptWifi = false
                other.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(1, 0, 0).encode())
                other.a.writer.flush()
                val error = assertThrows(IllegalStateException::class.java) { other.b.reader.read() }
                assertTrue(error.message.orEmpty().contains("Unknown secure record type"))
            } finally {
                other.cut()
            }
        } finally {
            pair.cut()
        }
    }

    @Test
    fun capacityIsLearnedOnlyWhenBothDirectionsAreBluetooth() {
        assertTrue(learnsBluetoothCapacity(CarrierKind.BLUETOOTH, CarrierKind.BLUETOOTH))
        assertFalse(learnsBluetoothCapacity(CarrierKind.WIFI_AWARE, CarrierKind.BLUETOOTH))
        assertFalse(learnsBluetoothCapacity(CarrierKind.BLUETOOTH, CarrierKind.WIFI_DIRECT))
        assertFalse(learnsBluetoothCapacity(CarrierKind.WIFI_AWARE, CarrierKind.WIFI_DIRECT))
    }

    @Test
    fun aRegressedSequenceIsSentUngovernedAndNeverEndsTheHop() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        val log = Collections.synchronizedList(ArrayList<String>())
        val hop = makeHop(
            HopCapsPlan.HopRole.STREAM_CHILD, pair.b, log = log,
            newAgent = { _ -> activated.countDown(); null },
        )
        val peerReads = RecordReadThread(pair.a.reader)
        try {
            val hopReads = RecordReadThread(hop.reader)
            pair.a.reader.acceptWifi = true
            hop.noteFirstHeader()
            assertEquals(SecureRecordType.BWF1, requireNotNull(peerReads.take()).type)
            pair.a.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            pair.a.writer.flush()
            assertTrue(activated.await(5, TimeUnit.SECONDS))

            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(5))
            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(3))
            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(6))
            hop.writer.flush()
            assertEquals(SecureRecordType.MESH_ENVELOPE, requireNotNull(peerReads.take()).type)
            assertEquals(SecureRecordType.MESH_ENVELOPE, requireNotNull(peerReads.take()).type)
            assertEquals(SecureRecordType.MESH_ENVELOPE, requireNotNull(peerReads.take()).type)
            assertTrue(log.any { it.contains("hop_seq_regress") })
            hopReads.stop()
        } finally {
            hop.close()
            peerReads.stop()
            pair.cut()
        }
    }

    @Test
    fun theDeliveredQueueIsBoundedAndTheEndFollowsQueuedRecords() {
        val pair = HopPair(tagGate = true)
        val activated = CountDownLatch(1)
        try {
            val hop = makeHop(
                HopCapsPlan.HopRole.STREAM_CHILD, pair.b,
                newAgent = { _ -> activated.countDown(); null },
            )
            val reads = soloActivate(hop, pair.a, activated)
            val count = HopLink.HOP_DELIVERED_CAPACITY + 44
            for (sequence in 0 until count) {
                pair.a.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence.toLong()))
            }
            pair.a.writer.flush()
            pair.cut()
            val received = ArrayList<Long>()
            repeat(count) {
                val record = requireNotNull(reads.take(10_000))
                received += MeshEnvelopeCodec.decode(record.payload).sequence
            }
            assertEquals((0 until count).map { it.toLong() }, received)
            assertTrue(requireNotNull(reads.awaitError(10_000)) is java.io.IOException)
            hop.close()
            reads.stop()
        } finally {
            pair.cut()
        }
    }
}
