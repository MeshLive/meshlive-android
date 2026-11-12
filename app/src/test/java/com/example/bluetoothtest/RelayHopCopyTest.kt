package com.example.bluetoothtest

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 step 7: drives the production relay loop ([BluetoothPrivateCallRelay.forwardOnDemand]) over byte pipes, as
 * `RelayForwardingIntegrityTest` does. The relay creates its own two HopLinks, one per edge.
 */
class RelayHopCopyTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private class Direction {
        val sink = PipedOutputStream()
        val source = PipedInputStream(sink, 1 shl 20)
        val input: InputStream = source
        fun close() {
            runCatching { sink.close() }
            runCatching { source.close() }
        }
    }

    private class Link(val near: SecureBluetoothSession, val far: SecureBluetoothSession, val directions: List<Direction>) {
        fun close() = directions.forEach(Direction::close)
    }

    private fun caps() = NodeCapabilities(
        NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0,
    )

    private fun link(
        nearId: NodeId,
        farId: NodeId,
        ownMarkerNear: Boolean = false,
        peerMarkerNear: Boolean = false,
        tagGate: Boolean = false,
    ): Link {
        val toFar = Direction()
        val toNear = Direction()
        val keyUp = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyDown = ByteArray(32).also(SecureRandom()::nextBytes)
        val near = SecureBluetoothSession(
            farId, caps(),
            SecureRecordReader(toNear.input, keyDown), SecureRecordWriter(toFar.sink, keyUp),
            wifiBuildTagGate = tagGate,
        )
        val far = SecureBluetoothSession(
            nearId, caps(),
            SecureRecordReader(toFar.input, keyUp), SecureRecordWriter(toNear.sink, keyDown),
            peerWifiMarker = peerMarkerNear, wifiBuildTagGate = tagGate,
        )
        near.reader.acceptWifi = true
        far.reader.acceptWifi = true
        return Link(near, far, listOf(toFar, toNear))
    }

    private val caller = NodeId.fromBytes(ByteArray(16) { (0x70 + it).toByte() })
    private val helper = NodeId.fromBytes(ByteArray(16) { (0x10 + it).toByte() })
    private val callee = NodeId.fromBytes(ByteArray(16) { (0x30 + it).toByte() })

    private fun payload(index: Int): ByteArray = ByteArray(64) { (index * 7 + it).toByte() }

    private fun startDuty(
        parent: Link,
        child: Link,
        relaysBelow: Int = 0,
        onReady: (Int) -> Unit = {},
        done: CountDownLatch,
    ): BluetoothPrivateCallRelay {
        val relay = BluetoothPrivateCallRelay(onStatus = {}, onStopped = {})
        relay.markStartedForTest()
        thread(name = "test-relay-duty", isDaemon = true) {
            try {
                relay.forwardOnDemand(
                    BluetoothPrivateCallRelay.OnDemandParent(parent.far, { parent.close() }),
                    child.near,
                    relaysBelow,
                    AtomicBoolean(false),
                    onReady,
                )
            } finally {
                done.countDown()
            }
        }
        return relay
    }

    @Test
    fun aBmh3RelayResponderSendsCapsAfterForwardingContentAndTheDownstreamEdgeAnswers() {
        // The relay is the BMH3 responder on the upstream edge (it saw the caller's WIFI marker).
        val parent = link(caller, helper, peerMarkerNear = true)
        val child = link(helper, callee, tagGate = true)
        val dutyDone = CountDownLatch(1)
        startDuty(parent, child, done = dutyDone)
        try {
            // A callee record travels toward the caller: forwarding it is the relay's upstream CAPS trigger.
            child.far.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, byteArrayOf(1))
            child.far.writer.write(SecureRecordType.END_TO_END_CONTENT, payload(1))
            child.far.writer.flush()

            assertEquals(SecureRecordType.END_TO_END_HANDSHAKE, parent.near.reader.read().type)
            val content = parent.near.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, content.type)
            assertArrayEquals(payload(1), content.payload)
            assertEquals("the BMH3 relay responder sends CAPS after forwarding", SecureRecordType.BWF1, parent.near.reader.read().type)
            // The caller's answer leaves the edge raw; the relay needs no carrier set.
            parent.near.writer.write(SecureRecordType.BWF1, Bwf.Caps(0, 0, 0).encode())
            parent.near.writer.flush()

            // The downstream edge answers the callee's CAPS (it never sends one first toward an endpoint).
            child.far.writer.write(SecureRecordType.BWF1, Bwf.Caps(Bwf.Caps.BEARER_AWARE, 1, 1).encode())
            child.far.writer.flush()
            val answer = SecureRecordType.BWF1
            assertEquals(answer, child.far.reader.read().type)
        } finally {
            parent.close()
            child.close()
            dutyDone.await(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun aBmh2RelayNeverSendsCapsFirstTowardACallEndpoint() {
        // All switches off and BMH2: readOpen from the tag gate, but nothing is written before a peer CAPS.
        val parent = link(caller, helper, tagGate = true)
        val child = link(helper, callee, tagGate = true)
        val dutyDone = CountDownLatch(1)
        startDuty(parent, child, done = dutyDone)
        try {
            parent.near.writer.write(SecureRecordType.END_TO_END_CONTENT, payload(2))
            parent.near.writer.flush()
            val content = child.far.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, content.type)
            assertNull("the relay never sends BWF1 first toward an endpoint", pollRecord(child.far.reader, 300))
        } finally {
            parent.close()
            child.close()
            dutyDone.await(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun contentCrossesOnceInOrderAndHeartbeatsAreRegenerated() {
        val parent = link(caller, helper)
        val child = link(helper, callee)
        val dutyDone = CountDownLatch(1)
        startDuty(parent, child, done = dutyDone)
        val count = 40
        val failure = AtomicReference<Throwable?>(null)
        val received = CountDownLatch(1)
        val receiver = thread(name = "test-callee-reader", isDaemon = true) {
            try {
                var index = 0
                var heartbeats = 0
                while (index < count) {
                    val record = child.far.reader.read()
                    if (record.type == SecureRecordType.LINK_HEARTBEAT) {
                        heartbeats++
                        continue
                    }
                    assertEquals(SecureRecordType.END_TO_END_CONTENT, record.type)
                    assertArrayEquals(payload(index), record.payload)
                    index++
                }
                assertTrue("the relay regenerates heartbeats", heartbeats > 0)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                received.countDown()
            }
        }
        try {
            for (index in 0 until count) {
                parent.near.writer.write(SecureRecordType.END_TO_END_CONTENT, payload(index))
                if (index % 10 == 9) SecureLinkControlProtocol.writeHeartbeat(parent.near.writer)
            }
            parent.near.writer.flush()
            assertTrue(received.await(30, TimeUnit.SECONDS))
            assertNull(failure.get())
            receiver.join(5_000)
        } finally {
            parent.close()
            child.close()
            dutyDone.await(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun aRelayCloseKeepsTheDrainWithEverySwitchOff() {
        val parent = link(caller, helper)
        val child = link(helper, callee)
        val dutyDone = CountDownLatch(1)
        val count = 5
        startDuty(parent, child, done = dutyDone)
        try {
            for (index in 0 until count) {
                parent.near.writer.write(SecureRecordType.END_TO_END_CONTENT, payload(index))
            }
            parent.near.writer.flush()
            // The callee must receive every record before its link closes.
            for (index in 0 until count) {
                val record = child.far.reader.read()
                assertEquals(SecureRecordType.END_TO_END_CONTENT, record.type)
                assertArrayEquals(payload(index), record.payload)
            }
            val failedAt = System.nanoTime()
            parent.directions.forEach { runCatching { it.sink.close() } }
            parent.near.close()
            // The relay's close drain holds both links at least 400 ms after the parent side failed; the callee
            // received every record above, before the failure.
            assertTrue("the relay duty ended", dutyDone.await(5, TimeUnit.SECONDS))
            val endedAt = System.nanoTime()
            assertTrue(
                "relay close drain after ${(endedAt - failedAt) / 1_000_000} ms",
                endedAt - failedAt >= 400_000_000L,
            )
        } finally {
            parent.close()
            child.close()
        }
    }

    private fun pollRecord(reader: SecureRecordReader, timeoutMs: Long): SecureRecord? {
        val result = AtomicReference<SecureRecord?>()
        val t = thread(isDaemon = true) { result.set(runCatching { reader.read() }.getOrNull()) }
        t.join(timeoutMs)
        t.interrupt()
        return result.get()
    }
}
