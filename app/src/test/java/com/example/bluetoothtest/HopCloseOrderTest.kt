package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 review P1-1: closing a hop never lets a record leave sealed under a wiped (all-zero) key, and with every Wi-Fi switch
 * off a hop's bytes are exactly a raw session's bytes.
 */
class HopCloseOrderTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val key = ByteArray(32) { (it + 1).toByte() }
    private val streamId = UUID.fromString("5b7d1c34-9b2e-4f0a-8e61-1d2c3b4a5f60")
    private val sourceId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 3).toByte() })
    private val peerId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 0x30).toByte() })

    private fun mesh(sequence: Long): ByteArray =
        MeshEnvelopeCodec.encode(MeshEnvelope(streamId, sourceId, sequence, 8, 0, ByteArray(32) { (sequence + it).toByte() }))

    /** What a Bluetooth socket's output does: it records what reached the air and fails every write once closed. */
    private class AirOutput : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        @Volatile var closed = false

        @Synchronized
        override fun write(b: Int) {
            if (closed) throw IOException("socket closed")
            bytes.write(b)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("socket closed")
            bytes.write(b, off, len)
        }

        @Synchronized
        fun aired(): ByteArray = bytes.toByteArray()
    }

    private fun session(output: OutputStream, writerKey: ByteArray = key) = SecureBluetoothSession(
        peerNodeId = peerId,
        peerCapabilities = NodeCapabilities(
            transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
            encodeCodecMask = NodeCapabilities.CODEC_VP9,
            decodeCodecMask = NodeCapabilities.CODEC_VP9,
            maxBitrateKbps = 1_100,
            maxChildren = 0,
        ),
        reader = SecureRecordReader(ByteArrayInputStream(ByteArray(0)), ByteArray(32) { 9 }),
        writer = SecureRecordWriter(output, writerKey),
        hopWifiKey = ByteArray(32) { 4 },
    )

    /** Every complete record on the air opens with the real key; a record sealed under a zero key would not. */
    private fun assertEveryAiredRecordOpensWithTheLinkKey(aired: ByteArray): Int {
        val reader = SecureRecordReader(ByteArrayInputStream(aired), key)
        var records = 0
        while (true) {
            try {
                reader.read()
                records++
            } catch (_: EOFException) {
                return records                  // a header whose body never reached the air ends the capture
            } catch (error: GeneralSecurityException) {
                throw AssertionError("record $records left sealed under a key other than the link key", error)
            }
        }
    }

    @Test
    fun aClosedWriterRefusesToSealAndPutsNothingOnTheAir() {
        val air = AirOutput()
        val writer = SecureRecordWriter(air, key)
        writer.write(SecureRecordType.MESH_ENVELOPE, mesh(1))
        val before = air.aired().size
        writer.close()
        val error = assertThrows(IOException::class.java) { writer.write(SecureRecordType.MESH_ENVELOPE, mesh(2)) }
        assertEquals(SecureRecordWriter.CLOSED_MESSAGE, error.message)
        assertEquals("nothing reached the air after close", before, air.aired().size)
        assertEquals(1, assertEveryAiredRecordOpensWithTheLinkKey(air.aired()))
    }

    @Test
    fun aClosedReaderRefusesARecordThatArrivesLater() {
        val air = AirOutput()
        SecureRecordWriter(air, key).write(SecureRecordType.MESH_ENVELOPE, mesh(1))
        val reader = SecureRecordReader(ByteArrayInputStream(air.aired()), key)
        reader.close()
        val error = assertThrows(IOException::class.java) { reader.read() }
        assertEquals(SecureRecordWriter.CLOSED_MESSAGE, error.message)
    }

    @Test
    fun aWriteRacingHopCloseIsNeverSealedWithAWipedKey() {
        // Every switch off: the hop is the plain Bluetooth path (mode OFF), the path every phone takes today.
        repeat(150) { round ->
            val air = AirOutput()
            val raw = session(air)
            val hop = HopLink(
                role = if (round % 2 == 0) HopCapsPlan.HopRole.STREAM_PARENT else HopCapsPlan.HopRole.RELAY_DOWNSTREAM,
                session = raw,
                closeRaw = { air.closed = true },
                anyLocalSwitchOn = { false },
                log = {},
                owner = RecordingHopOwner(),
                newAgent = { null },
            )
            assertEquals(HopWifiGate.Mode.OFF, hop.mode)
            val writing = CountDownLatch(1)
            val writer = thread(name = "hop-close-race-writer") {
                var sequence = 0L
                try {
                    while (true) {
                        val type = if (round % 2 == 0) SecureRecordType.MESH_ENVELOPE else SecureRecordType.ROUTE_CONTROL
                        hop.writer.write(type, mesh(sequence++))
                        if (sequence == 8L) writing.countDown()
                    }
                } catch (_: Exception) {
                    writing.countDown()                 // the closed link or the closed writer ended the loop
                }
            }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            if (round % 3 == 0) Thread.yield()
            hop.close()
            writer.join(5_000)
            assertTrue("the writer stopped after close", !writer.isAlive)
            assertThrows(IOException::class.java) { raw.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(0)) }
            assertEveryAiredRecordOpensWithTheLinkKey(air.aired())
        }
    }

    @Test
    fun withEverySwitchOffAHopPutsExactlyTheRawSessionsBytesOnTheAir() {
        val viaHop = AirOutput()
        val viaRaw = AirOutput()
        val hop = HopLink(
            role = HopCapsPlan.HopRole.STREAM_PARENT,
            session = session(viaHop),
            closeRaw = { viaHop.closed = true },
            anyLocalSwitchOn = { false },
            log = {},
            owner = RecordingHopOwner(),
            newAgent = { null },
        )
        val raw = session(viaRaw)
        for (sequence in 0L until 40L) {
            hop.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence))
            raw.writer.write(SecureRecordType.MESH_ENVELOPE, mesh(sequence))
            if (sequence % 10 == 0L) {
                hop.sendHopControl(SecureRecordType.ROUTE_CONTROL, byteArrayOf(1))
                raw.writer.write(SecureRecordType.ROUTE_CONTROL, byteArrayOf(1))
            }
        }
        hop.writer.flush()
        raw.writer.flush()
        hop.close()
        assertArrayEquals(viaRaw.aired(), viaHop.aired())
        assertEquals(44, assertEveryAiredRecordOpensWithTheLinkKey(viaHop.aired()))
    }
}
