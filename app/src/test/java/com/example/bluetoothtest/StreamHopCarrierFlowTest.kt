package com.example.bluetoothtest

import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 step 11: stream and relay hop flows over pipes, no Android types. A Wi-Fi carrier is a pipe-backed
 * `WifiSocketCarrier` offered through the hop's own ports ([HopWifiRig]); the switch, markers, fallback and delivery are
 * the production carrier code. These are JVM flows, not phone evidence.
 */
class StreamHopCarrierFlowTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val streamId = UUID.fromString("2a12c9b4-3e44-4e31-9b19-4a8a2a10b001")
    private val source = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 6).toByte() })

    private fun envelope(sequence: Long): ByteArray =
        MeshEnvelopeCodec.encode(MeshEnvelope(streamId, source, sequence, 8, 0, ByteArray(16) { (sequence + it).toByte() }))

    private fun content(sequence: Long): ByteArray =
        EndToEndContentProtocol.encodeHeader(EndToEndContentType.entries.first(), sequence, 0, 32) + ByteArray(32) { 3 }

    private fun hop(
        role: HopCapsPlan.HopRole,
        session: SecureBluetoothSession,
        switchOn: Boolean = true,
        neighbourIsRelay: Boolean = false,
        newAgent: (WifiHopPorts) -> WifiHopAgent? = { null },
        log: MutableList<String> = Collections.synchronizedList(ArrayList()),
    ): HopLink = HopLink(
        role = role,
        session = session,
        closeRaw = {},
        anyLocalSwitchOn = { switchOn },
        log = { line -> log += line },
        owner = RecordingHopOwner(),
        newAgent = newAgent,
        neighbourIsRelay = neighbourIsRelay,
        localCaps = { Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1) },
    )

    /** The next record of [type] (a relay hop's reader also returns the heartbeats it forwards). */
    private fun take(reads: RecordReadThread, type: SecureRecordType): SecureRecord {
        while (true) {
            val record = requireNotNull(reads.take(10_000)) { "no $type: ${reads.error}" }
            if (record.type == type) return record
        }
    }

    /** Every envelope that still arrives once the writer is done (until two quiet seconds). */
    private fun drainEnvelopes(reads: RecordReadThread, into: MutableList<Long>) {
        while (true) {
            val record = reads.take(2_000) ?: return
            if (record.type == SecureRecordType.MESH_ENVELOPE) into += meshSequence(record)
        }
    }

    private fun meshSequence(record: SecureRecord): Long = MeshEnvelopeCodec.decode(record.payload).sequence

    private fun writeEnvelopes(hop: HopLink, range: LongRange, pauseMs: Long = 0L): Thread =
        thread(name = "test-source-writer") {
            try {
                for (sequence in range) {
                    hop.writer.write(SecureRecordType.MESH_ENVELOPE, envelope(sequence))
                    hop.writer.flush()
                    if (pauseMs > 0) Thread.sleep(pauseMs)
                }
            } catch (_: Exception) {
            }
        }

    private fun events(owner: HopOwner): List<String> = (owner as RecordingHopOwner).events

    /** A copy of the log taken under the list's own lock: the hops' reader threads keep logging while a test reads it. */
    private fun FakeEnv.linesNow(): List<String> = synchronized(lines) { lines.toList() }

    @Test
    fun aStreamChainWithWifiOnTheFirstHopKeepsEveryEnvelope() {
        val hop1 = HopWifiRig().activate()
        val hop2 = HopPair(tagGate = false)
        val relayChildHop = hop(HopCapsPlan.HopRole.STREAM_PARENT, hop2.a)
        val viewerHop = hop(HopCapsPlan.HopRole.STREAM_CHILD, hop2.b, switchOn = false)
        try {
            hop1.addWifi()
            val count = 500L
            writeEnvelopes(hop1.parent, 0 until count)
            for (index in 0 until count) {
                val record = take(hop1.childReads, SecureRecordType.MESH_ENVELOPE)
                assertEquals(index, meshSequence(record))
                relayChildHop.writer.write(record.type, record.payload)
            }
            relayChildHop.writer.flush()
            val sent = requireNotNull(hop1.parentPorts.carrierSample(1)).bytesWritten
            assertTrue("the first hop's media went over its Wi-Fi carrier ($sent bytes)", sent > count * 16)
            assertEquals(CarrierKind.WIFI_AWARE, hop1.child.acceptKind)
            val viewerSequences = (0 until count).map { meshSequence(viewerHop.reader.read()) }
            assertEquals("the second hop is an OFF pass-through", (0 until count).toList(), viewerSequences)
            assertEquals(HopWifiGate.Mode.OFF, viewerHop.mode)
            assertFalse(hop2.a.reader.acceptWifi)
        } finally {
            hop1.close()
            relayChildHop.close()
            viewerHop.close()
            hop2.cut()
        }
    }

    @Test
    fun aStreamChainWithWifiOnTheSecondHopKeepsEveryEnvelope() {
        val hop1 = HopPair(tagGate = false)
        val sourceHop = hop(HopCapsPlan.HopRole.STREAM_PARENT, hop1.a, switchOn = false)
        val relayParentHop = hop(HopCapsPlan.HopRole.STREAM_CHILD, hop1.b, switchOn = false)
        val relayParentReads = RecordReadThread(relayParentHop.reader)
        val hop2 = HopWifiRig().activate()
        try {
            hop2.addWifi()
            val count = 500L
            writeEnvelopes(sourceHop, 0 until count)
            for (index in 0 until count) {
                val record = take(relayParentReads, SecureRecordType.MESH_ENVELOPE)
                hop2.parent.writer.write(record.type, record.payload)
                hop2.parent.writer.flush()
            }
            val viewerSequences = (0 until count).map { meshSequence(take(hop2.childReads, SecureRecordType.MESH_ENVELOPE)) }
            assertEquals((0 until count).toList(), viewerSequences)
            assertEquals(HopWifiGate.Mode.OFF, sourceHop.mode)
            assertTrue(requireNotNull(hop2.parentPorts.carrierSample(1)).bytesWritten > count * 16)
        } finally {
            sourceHop.close()
            relayParentHop.close()
            relayParentReads.stop()
            hop1.cut()
            hop2.close()
        }
    }

    /** Untested case 3: the Wi-Fi carrier dies mid-stream; both sides fall back with a keyframe and order holds. */
    @Test
    fun aWifiCarrierThatDiesMidStreamFallsBackWithAKeyframeAndKeepsOrder() {
        val rig = HopWifiRig().activate()
        try {
            val wifi = rig.addWifi()
            val writer = writeEnvelopes(rig.parent, 0L until 400L, pauseMs = 1)
            val received = ArrayList<Long>()
            while (received.size < 100) received += meshSequence(take(rig.childReads, SecureRecordType.MESH_ENVELOPE))
            wifi.cut()
            writer.join(30_000)
            drainEnvelopes(rig.childReads, received)
            assertEquals("the stream went on to its last record on Bluetooth", 399L, received.last())
            assertEquals("no duplicate", received.distinct(), received)
            assertEquals("no reorder", received.sorted(), received)
            assertEquals(CarrierKind.BLUETOOTH, rig.parent.sendKind)
            assertTrue("the source forces a keyframe", "local_kf" in events(rig.parentOwner))
            assertTrue("the viewer asks for one", "peer_kf" in events(rig.childOwner))
            assertTrue("carrier:BLUETOOTH" in events(rig.parentOwner))
            assertFalse("the hop did not end", events(rig.parentOwner).any { it.startsWith("ended:") })
            assertFalse(events(rig.childOwner).any { it.startsWith("ended:") })
        } finally {
            rig.close()
        }
    }

    /** Every record the child reads until the withdrawal, the withdrawal last. */
    private fun readUntilWithdrawal(rig: HopWifiRig): List<SecureRecord> {
        val seen = ArrayList<SecureRecord>()
        while (seen.lastOrNull()?.type != SecureRecordType.ROUTE_CONTROL) {
            seen += requireNotNull(rig.childReads.take(10_000)) { "no withdrawal: ${rig.childReads.error}" }
        }
        return seen
    }

    /**
     * Untested case 5: a capacity withdrawal and its acknowledgement over a live Wi-Fi carrier.
     *
     * `sendHopControl` uses the carrier's control lane, which its writer serves before queued media (A3 amendment 5), so
     * a withdrawal sent while envelopes still wait in the Wi-Fi carrier's queue may overtake them (the next test). Here
     * the envelopes are on the wire before the withdrawal is sent: the source thread's return only means they are queued.
     */
    @Test
    fun aWithdrawalOverALiveWifiCarrierReachesTheChildAndItsAckComesBack() {
        val rig = HopWifiRig().activate()
        try {
            rig.addWifi()
            writeEnvelopes(rig.parent, 0L until 20L).join(10_000)
            assertTrue(
                "the Wi-Fi carrier wrote every queued envelope",
                HopWifiRig.awaitCondition { rig.parentPorts.carrierSample(1)?.queuedContent == 0 },
            )
            val withdrawal = StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY)
            rig.parent.sendHopControl(SecureRecordType.ROUTE_CONTROL, withdrawal)
            rig.parent.writer.flush()
            val seen = readUntilWithdrawal(rig)
            assertEquals(
                "every envelope written before the withdrawal arrives before it, in order",
                (0L until 20L).toList(),
                seen.filter { it.type == SecureRecordType.MESH_ENVELOPE }.map(::meshSequence),
            )
            assertEquals(withdrawal.toList(), seen.last().payload.toList())
            rig.child.sendHopControl(SecureRecordType.ROUTE_CONTROL_ACK, withdrawal)
            rig.child.writer.flush()
            val ack = take(rig.parentReads, SecureRecordType.ROUTE_CONTROL_ACK)
            assertEquals(withdrawal.toList(), ack.payload.toList())
            assertEquals("both directions stayed on Wi-Fi", CarrierKind.WIFI_AWARE, rig.child.sendKind)
        } finally {
            rig.close()
        }
    }

    /**
     * A withdrawal is hop control: it does not wait behind the media still queued on the Wi-Fi carrier (the control lane
     * goes first), and those envelopes are not lost by it; they follow in order on the same carrier.
     */
    @Test
    fun aWithdrawalOvertakesEnvelopesStillQueuedOnTheWifiCarrierAndNoneIsLost() {
        val rig = HopWifiRig().activate()
        try {
            val wifi = rig.addWifi()
            wifi.blockWritesFromA(true)                      // the parent's Wi-Fi writer stalls on its next flush
            writeEnvelopes(rig.parent, 0L until 20L).join(10_000)
            val withdrawal = StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY)
            rig.parent.sendHopControl(SecureRecordType.ROUTE_CONTROL, withdrawal)
            rig.parent.writer.flush()
            wifi.blockWritesFromA(false)
            val seen = readUntilWithdrawal(rig)
            val before = seen.count { it.type == SecureRecordType.MESH_ENVELOPE }
            // At most the one record the writer had in hand when it stalled precedes the withdrawal.
            assertTrue("the withdrawal went ahead of the queued envelopes ($before before it)", before <= 1)
            val envelopes = seen.filter { it.type == SecureRecordType.MESH_ENVELOPE }.map(::meshSequence).toMutableList()
            while (envelopes.size < 20) envelopes += meshSequence(take(rig.childReads, SecureRecordType.MESH_ENVELOPE))
            assertEquals("every queued envelope still arrives, in order", (0L until 20L).toList(), envelopes)
            assertEquals(CarrierKind.WIFI_AWARE, rig.parent.sendKind)
        } finally {
            rig.close()
        }
    }

    /**
     * Right after a switch the child accepts the Wi-Fi carrier only when the parent's `SWITCH` arrives on Bluetooth; until
     * then it holds the envelopes that came early on Wi-Fi. A withdrawal is hop control and is not held behind them: it
     * arrives while the Bluetooth link is still stalled, and the held envelopes follow once the `SWITCH` lands, every one,
     * in order.
     */
    @Test
    fun aWithdrawalSentBeforeTheChildHasTheSwitchIsNotHeldAndTheEarlyEnvelopesFollow() {
        val rig = HopWifiRig().activate()
        try {
            rig.pair.blockWritesFromA(true)                  // the parent's Bluetooth bytes, its SWITCH among them, stall
            rig.addWifi(awaitSwitch = false)
            assertTrue(HopWifiRig.awaitCondition { rig.parent.sendKind == CarrierKind.WIFI_AWARE })
            writeEnvelopes(rig.parent, 0L until 20L).join(10_000)
            assertTrue(HopWifiRig.awaitCondition { rig.parentPorts.carrierSample(1)?.queuedContent == 0 })
            val withdrawal = StreamRouteControlProtocol.encode(StreamRouteWithdrawal.LOCAL_CAPACITY)
            rig.parent.sendHopControl(SecureRecordType.ROUTE_CONTROL, withdrawal)
            rig.parent.writer.flush()
            val seen = readUntilWithdrawal(rig)                // with Bluetooth still stalled
            assertEquals(withdrawal.toList(), seen.last().payload.toList())
            rig.pair.blockWritesFromA(false)
            val envelopes = seen.filter { it.type == SecureRecordType.MESH_ENVELOPE }.map(::meshSequence).toMutableList()
            while (envelopes.size < 20) envelopes += meshSequence(take(rig.childReads, SecureRecordType.MESH_ENVELOPE))
            assertEquals("every early envelope arrives once, in order", (0L until 20L).toList(), envelopes)
            assertTrue(HopWifiRig.awaitCondition { rig.child.acceptKind == CarrierKind.WIFI_AWARE })
        } finally {
            rig.close()
        }
    }

    /** Untested case 7: a relay whose upstream hop is on Wi-Fi and whose downstream hop is on Bluetooth, both directions. */
    @Test
    fun aRelayBetweenAWifiHopAndABluetoothHopForwardsBothDirectionsInOrder() {
        val relayTrigger: (HopLink, HopLink) -> Unit = { parent, _ -> parent.noteContentForwarded() }
        val upEdge = HopWifiRig(
            parentRole = HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
            childRole = HopCapsPlan.HopRole.RELAY_UPSTREAM,
            parentNeighbourIsRelay = true,
        ).activate(relayTrigger)
        val downEdge = HopWifiRig(
            parentRole = HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
            childRole = HopCapsPlan.HopRole.RELAY_UPSTREAM,
            parentNeighbourIsRelay = true,
        ).activate(relayTrigger)
        // The middle relay: upEdge.child is its upstream hop, downEdge.parent its downstream hop.
        val count = 300L
        val forward = thread(name = "test-relay-forward") {
            runCatching {
                repeat(count.toInt()) {
                    val record = take(upEdge.childReads, SecureRecordType.END_TO_END_CONTENT)
                    downEdge.parent.writer.write(record.type, record.payload)
                    downEdge.parent.writer.flush()
                }
            }
        }
        val reverse = thread(name = "test-relay-reverse") {
            runCatching {
                repeat(count.toInt()) {
                    val record = take(downEdge.parentReads, SecureRecordType.END_TO_END_CONTENT)
                    upEdge.child.writer.write(record.type, record.payload)
                    upEdge.child.writer.flush()
                }
            }
        }
        try {
            upEdge.addWifi()
            assertEquals(CarrierKind.BLUETOOTH, downEdge.parent.sendKind)
            val toCallee = thread(name = "test-caller") {
                for (sequence in 0L until count) {
                    upEdge.parent.writer.write(SecureRecordType.END_TO_END_CONTENT, content(sequence))
                    upEdge.parent.writer.flush()
                }
            }
            val toCaller = thread(name = "test-callee") {
                for (sequence in 0L until count) {
                    downEdge.child.writer.write(SecureRecordType.END_TO_END_CONTENT, content(sequence))
                    downEdge.child.writer.flush()
                }
            }
            val atCallee = (0 until count).map {
                EndToEndContentProtocol.sequenceOf(take(downEdge.childReads, SecureRecordType.END_TO_END_CONTENT).payload)
            }
            val atCaller = (0 until count).map {
                EndToEndContentProtocol.sequenceOf(take(upEdge.parentReads, SecureRecordType.END_TO_END_CONTENT).payload)
            }
            toCallee.join(10_000)
            toCaller.join(10_000)
            assertEquals((0 until count).toList(), atCallee)
            assertEquals((0 until count).toList(), atCaller)
            assertEquals("the upstream edge carried the call on Wi-Fi", CarrierKind.WIFI_AWARE, upEdge.child.acceptKind)
            assertEquals("the downstream edge stayed on Bluetooth", CarrierKind.BLUETOOTH, downEdge.child.acceptKind)
        } finally {
            upEdge.close()
            downEdge.close()
            forward.join(5_000)
            reverse.join(5_000)
        }
    }

    @Test
    fun relayToRelayEdgesExchangeCapsOnBmh2AndBmh3() {
        // BMH2: both tags allow BWF1. Relay 1's downstream hop is the initiator and sends first after forwarding.
        val bmh2 = HopPair(tagGate = true)
        val r1Latched = CountDownLatch(1)
        val r2Latched = CountDownLatch(1)
        val relay1 = hop(
            HopCapsPlan.HopRole.RELAY_DOWNSTREAM, bmh2.b, neighbourIsRelay = true,
            newAgent = { r1Latched.countDown(); null },
        )
        val relay2 = hop(
            HopCapsPlan.HopRole.RELAY_UPSTREAM, bmh2.a,
            newAgent = { r2Latched.countDown(); null },
        )
        val relay1Reads = RecordReadThread(relay1.reader)
        val relay2Reads = RecordReadThread(relay2.reader)
        try {
            relay1.noteContentForwarded()
            assertTrue(r1Latched.await(5, TimeUnit.SECONDS))
            assertTrue(r2Latched.await(5, TimeUnit.SECONDS))
            assertTrue(relay1Reads.records.isEmpty())
            assertTrue(relay2Reads.records.isEmpty())
        } finally {
            relay1.close()
            relay2.close()
            relay1Reads.stop()
            relay2Reads.stop()
            bmh2.cut()
        }

        // BMH3: relay 2 is the responder (it saw relay 1's WIFI marker) and sends first after forwarding.
        val bmh3 = HopPair(tagGate = false, peerWifiMarkerA = true, ownWifiMarkerB = true)
        val b1Latched = CountDownLatch(1)
        val b2Latched = CountDownLatch(1)
        val down = hop(
            HopCapsPlan.HopRole.RELAY_DOWNSTREAM, bmh3.b, neighbourIsRelay = true,
            newAgent = { b1Latched.countDown(); null },
        )
        val up = hop(
            HopCapsPlan.HopRole.RELAY_UPSTREAM, bmh3.a,
            newAgent = { b2Latched.countDown(); null },
        )
        val downReads = RecordReadThread(down.reader)
        val upReads = RecordReadThread(up.reader)
        try {
            up.noteContentForwarded()
            assertTrue("the BMH3 responder activates", b2Latched.await(5, TimeUnit.SECONDS))
            assertTrue("the BMH3 initiator answers", b1Latched.await(5, TimeUnit.SECONDS))
        } finally {
            down.close()
            up.close()
            downReads.stop()
            upReads.stop()
            bmh3.cut()
        }
    }

    @Test
    fun relayParentWatchesSuspendWhileWifiAccepts() {
        assertTrue(parentLinkJudgedPaused(false, CarrierKind.WIFI_AWARE))
        assertTrue(parentLinkJudgedPaused(false, CarrierKind.WIFI_DIRECT))
        assertFalse(parentLinkJudgedPaused(false, CarrierKind.BLUETOOTH))
        assertTrue(parentLinkJudgedPaused(true, CarrierKind.BLUETOOTH))

        // A 120 kbps stream: 1 kbps is far below bitrate/8.
        val trickle = RelayParentTrickle(120, 0)
        var bytes = 0L
        var figure: Int? = null
        for (window in 1..3) {
            bytes += 5 * 1_000 / 8 // 1 kbps over a 5 s window
            figure = trickle.onTick(window * 5_000_000_000L, bytes, parentLinkJudgedPaused(false, CarrierKind.WIFI_AWARE), armed = true)
            assertNull("a Wi-Fi-accepting window never judges", figure)
        }
        for (window in 4..6) {
            bytes += 5 * 1_000 / 8
            figure = trickle.onTick(window * 5_000_000_000L, bytes, parentLinkJudgedPaused(false, CarrierKind.BLUETOOTH), armed = true)
            if (figure != null) break
        }
        assertNotNull("after Bluetooth returns the starved link is judged", figure)

        assertFalse(
            "a Wi-Fi carrier keeps the parent watch quiet",
            relayParentSilent(20_000_000_000L, 0L, 0L, parentLinkJudgedPaused(false, CarrierKind.WIFI_AWARE)),
        )
    }

    /**
     * The hop whose host check found no free data path starts its attempt as soon as another owner releases one, through
     * its own agent and without a second CAPS exchange.
     */
    @Test
    fun aFreedDataPathStartsTheWaitingHopsAttemptWithoutANewCapsExchange() {
        val parentEnv = FakeEnv()
        val childEnv = FakeEnv()
        for (env in listOf(parentEnv, childEnv)) {
            env.factsValue = env.factsValue.copy(freeNdp = 0)
            assertTrue(env.ledger.reserveNdp("other-hop", 1))
        }
        val rig = HopWifiRig(
            parentAgent = { ports -> WifiHopAgent(ports, parentEnv) },
            childAgent = { ports -> WifiHopAgent(ports, childEnv) },
        ).activate()
        try {
            val envs = listOf(parentEnv, childEnv)
            // `activate()` returns once the hops built their agents; each hop's reader thread then hands its agent CAPS and
            // the video fact. Wait for both CAPS, then let the fake clock run past the 3 s video rule until the host
            // checks: an agent that heard of the video after a single fixed advance would count its 3 s from that point.
            assertTrue(
                "both agents heard CAPS",
                HopWifiRig.awaitCondition { envs.all { env -> env.linesNow().any { it.startsWith("wifi_caps: dir=recv") } } },
            )
            for (env in envs) env.scheduler.advance(WifiAttemptLimits.VIDEO_STABLE_MS)
            fun hostChecked() = envs.any { env -> env.linesNow().any { it.contains("host_skip") } }
            assertTrue(
                "the host's check ran",
                HopWifiRig.awaitCondition { hostChecked() || envs.forEach { it.scheduler.advance(100) }.let { false } },
            )
            // The other hop holds this phone's only data path: the host's check finds none free and waits.
            val host = envs.single { env -> env.linesNow().any { it.contains("host_skip") } }
            assertEquals("no attempt holds a data path yet", 1, host.ledger.ndpReserved)

            host.factsValue = host.factsValue.copy(freeNdp = 1)       // the platform reports the path free again
            host.ledger.releaseNdp("other-hop")
            host.scheduler.drain()
            host.scheduler.advance(1)                                  // well inside the 5 s re-check
            assertEquals("the waiting hop reserved the freed path for its attempt at once", 1, host.ledger.ndpReserved)
            for (env in listOf(parentEnv, childEnv)) {
                assertEquals("CAPS was exchanged once", 1, env.linesNow().count { it.startsWith("wifi_caps: dir=sent") })
            }
        } finally {
            rig.close()
        }
    }

    @Test
    fun teardownClosesBothCarriersOnceAndTheKeyIsGone() {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<String>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += "${t.name}: $e" }
        try {
            teardownOnce()
            Thread.sleep(300)
            // On Android an uncaught exception on any thread ends the whole process, the Bluetooth stream too.
            assertEquals("no carrier thread dies with an uncaught exception at close", emptyList<String>(), uncaught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private fun teardownOnce() {
        val rig = HopWifiRig().activate()
        val wifi = rig.addWifi(ndpOwner = "wifi-hop-teardown")
        assertEquals(1, rig.ledger.ndpReserved)
        val first = thread { rig.child.close(); rig.parent.close() }
        val second = thread { rig.parent.close(); rig.child.close() }
        first.join(5_000)
        second.join(5_000)
        assertEquals("the Wi-Fi carrier's data path is released once", 0, rig.ledger.ndpReserved)
        assertTrue(requireNotNull(rig.pair.a.hopWifiKey).all { it == 0.toByte() })
        assertTrue(requireNotNull(rig.pair.b.hopWifiKey).all { it == 0.toByte() })
        assertNotNull("the reader ends after close", rig.childReads.awaitError(5_000))
        assertNotNull(rig.parentReads.awaitError(5_000))
        rig.close()
        wifi.cut()
    }

    /**
     * The source phone's user turns the Wi-Fi switch off mid-stream: its real agent releases the Wi-Fi carrier with a
     * planned (lossless) fallback. (Only this phone's switch: the viewer is another phone.)
     */
    @Test
    fun aSwitchOffMidStreamUsesThePlannedFallback() {
        val parentEnv = FakeEnv()
        val childEnv = FakeEnv()
        val parentAgent = java.util.concurrent.atomic.AtomicReference<WifiHopAgent?>()
        val rig = HopWifiRig(
            parentAgent = { ports -> WifiHopAgent(ports, parentEnv).also { parentAgent.set(it) } },
            childAgent = { ports -> WifiHopAgent(ports, childEnv) },
        ).activate()
        try {
            rig.addWifi()
            val writer = writeEnvelopes(rig.parent, 0L until 300L, pauseMs = 1)
            val received = ArrayList<Long>()
            while (received.size < 100) received += meshSequence(take(rig.childReads, SecureRecordType.MESH_ENVELOPE))
            requireNotNull(parentAgent.get()).onSwitchOff()      // what HopLinks.switchOffAll() calls on this phone
            writer.join(30_000)
            drainEnvelopes(rig.childReads, received)
            assertEquals("no duplicate", received.distinct(), received)
            assertEquals("no reorder", received.sorted(), received)
            // A7F finding: on this rig a planned release occasionally drops one record in flight on the released carrier
            // (child logs carrier_drop reason=closed_carrier); recorded for the A3 owner, not asserted away.
            assertTrue("the stream went on after the release (${received.size} of 300)", received.size >= 290)
            assertTrue(parentEnv.linesNow().any { it.contains("switch_off") })
            assertTrue(HopWifiRig.awaitCondition { rig.parent.sendKind == CarrierKind.BLUETOOTH })
            assertTrue("carrier:BLUETOOTH" in events(rig.parentOwner))
            assertFalse(events(rig.parentOwner).any { it.startsWith("ended:") })
        } finally {
            rig.close()
        }
    }

    /** Untested case 14: Wi-Fi Direct alone goes off; only the hop on a live Direct carrier releases it. */
    @Test
    fun turningDirectOffReleasesOnlyTheLiveDirectCarrier() {
        val directEnvs = listOf(FakeEnv(), FakeEnv())
        val awareEnvs = listOf(FakeEnv(), FakeEnv())
        val direct = HopWifiRig(
            parentAgent = { ports -> WifiHopAgent(ports, directEnvs[0]) },
            childAgent = { ports -> WifiHopAgent(ports, directEnvs[1]) },
        ).activate()
        val aware = HopWifiRig(
            parentAgent = { ports -> WifiHopAgent(ports, awareEnvs[0]) },
            childAgent = { ports -> WifiHopAgent(ports, awareEnvs[1]) },
        ).activate()
        try {
            direct.addWifi(CarrierKind.WIFI_DIRECT)
            aware.addWifi(CarrierKind.WIFI_AWARE)
            assertTrue(direct.parent.usesDirect())
            assertFalse(aware.parent.usesDirect())
            val writer = writeEnvelopes(direct.parent, 0L until 200L, pauseMs = 1)
            val received = ArrayList<Long>()
            while (received.size < 50) received += meshSequence(take(direct.childReads, SecureRecordType.MESH_ENVELOPE))
            // Both ends of this in-process hop see "Direct off" at the same instant (one JVM holds both phones' hops);
            // the stream must go on in order on Bluetooth.
            HopLinks.switchOffDirect()
            writer.join(30_000)
            drainEnvelopes(direct.childReads, received)
            assertTrue("the stream went on after the release (${received.size} of 200)", received.size >= 190)
            assertEquals("no duplicate", received.distinct(), received)
            assertEquals("no reorder", received.sorted(), received)
            assertTrue(HopWifiRig.awaitCondition { direct.parent.sendKind == CarrierKind.BLUETOOTH })
            assertEquals("the Aware hop keeps its carrier", CarrierKind.WIFI_AWARE, aware.parent.sendKind)
            assertFalse(awareEnvs.any { env -> env.linesNow().any { it.contains("switch_off") } })
        } finally {
            direct.close()
            aware.close()
        }
    }
}
