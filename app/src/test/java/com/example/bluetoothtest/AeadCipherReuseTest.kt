package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Experimental AEAD reuse (`test_aead_cipher_reuse`): hop and endpoint records must stay byte-for-byte the same,
 * open across mixed builds in both directions, and fail closed exactly as before.
 */
class AeadCipherReuseTest {
    private val random = Random(20261026)
    private val linkKey = random.nextBytes(32)
    private val mediaKey = random.nextBytes(32)
    private val payloads = listOf(0, 1, 80, 173, 2_500, 9_000).map { random.nextBytes(it) }

    private fun <T> withReuse(on: Boolean, block: () -> T): T {
        val before = DebugTunables.aeadCipherReuse
        DebugTunables.aeadCipherReuse = on
        try {
            return block()
        } finally {
            DebugTunables.aeadCipherReuse = before
        }
    }

    private class CountingStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var writes = 0
        override fun write(b: Int) {
            writes++
            bytes.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            writes++
            bytes.write(b, off, len)
        }
    }

    /** Hop records carrying endpoint media, as a call or stream writer sends them. */
    private fun send(reuse: Boolean): CountingStream = withReuse(reuse) {
        val out = CountingStream()
        val writer = SecureRecordWriter(out, linkKey)
        val sealer = EndToEndContentSealer(mediaKey)
        payloads.forEachIndexed { index, payload ->
            EndToEndStreamProtocol.writePacket(writer, sealer, StreamProtocol.FLAG_AUDIO, index * 20_000L, payload)
        }
        writer.write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
        out
    }

    private fun receive(reuse: Boolean, bytes: ByteArray): List<ByteArray> = withReuse(reuse) {
        val reader = SecureRecordReader(ByteArrayInputStream(bytes), linkKey)
        val opener = EndToEndContentOpener(mediaKey)
        val media = payloads.map { EndToEndStreamProtocol.readPacket(reader, opener).payload }
        assertArrayEquals(byteArrayOf(1), reader.read().payload)
        media
    }

    @Test
    fun recordsAreByteForByteTheSameAndTheHopRecordIsOneWrite() {
        val before = send(reuse = false)
        val after = send(reuse = true)
        assertArrayEquals(before.bytes.toByteArray(), after.bytes.toByteArray())
        val records = payloads.size + 1
        assertEquals(2 * records, before.writes)
        // One write per hop record: a buffered link no longer flushes a large record's header on its own. The reuse is an
        // Experimental arm (value && BuildConfig.DEBUG): a release build keeps the shipped two writes per record.
        assertEquals(if (BuildConfig.DEBUG) records else 2 * records, after.writes)
    }

    @Test
    fun mixedBuildsOpenEachOthersRecordsInBothDirections() {
        for (sender in listOf(false, true)) {
            val bytes = send(sender).bytes.toByteArray()
            for (receiver in listOf(false, true)) {
                val got = receive(receiver, bytes)
                payloads.zip(got).forEach { (sent, opened) -> assertArrayEquals("sender=$sender receiver=$receiver", sent, opened) }
            }
        }
    }

    @Test
    fun alteredRecordsStillFailClosedAndALaterRecordOpensCleanly() {
        val bytes = send(reuse = true).bytes.toByteArray()
        for (reuse in listOf(false, true)) {
            val tampered = bytes.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
            withReuse(reuse) {
                val reader = SecureRecordReader(ByteArrayInputStream(tampered), linkKey)
                assertThrows(GeneralSecurityException::class.java) { reader.read() }
            }
        }
        // A context that saw a failure keeps nothing of it: the next record under a fresh nonce opens normally.
        val context = MeshAead.Context()
        val good = MeshAead.encrypt(linkKey, 5, payloads[3], byteArrayOf(9))
        val bad = good.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { context.decrypt(linkKey, 5, bad, byteArrayOf(9)) }
        assertArrayEquals(payloads[3], context.decrypt(linkKey, 5, good, byteArrayOf(9)))
        val next = MeshAead.encrypt(linkKey, 6, payloads[4], byteArrayOf(9))
        assertArrayEquals(payloads[4], context.decrypt(linkKey, 6, next, byteArrayOf(9)))
    }

    @Test
    fun endpointSealerTestCorruptionStillBreaksExactlyOneRecord() {
        for (reuse in listOf(false, true)) {
            val (first, second) = withReuse(reuse) {
                val sealer = EndToEndContentSealer(mediaKey)
                sealer.corruptNextRecordForTest()
                sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, payloads[2]) to
                    sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, payloads[2])
            }
            withReuse(!reuse) {
                val opener = EndToEndContentOpener(mediaKey)
                assertThrows(GeneralSecurityException::class.java) { opener.open(first) }
                assertArrayEquals(payloads[2], opener.open(second).payload)
            }
        }
    }
}
