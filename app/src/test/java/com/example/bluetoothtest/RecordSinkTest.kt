package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 step 1: `EndToEndStreamProtocol.write*` seal a `BME1` record into a [RecordSink], so a call can route each sealed
 * record to a carrier instead of one link writer. The bytes and the seal order stay exactly as before.
 */
class RecordSinkTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val settings = VideoSettings(VideoResolution(854, 480, "480p"), fps = 30, bitRate = 200_000, latencyMs = 500)

    /** A sink that records what reached it, standing in for the carrier router. */
    private class CollectingSink : RecordSink {
        val records = ArrayList<SecureRecord>()
        override fun write(type: SecureRecordType, payload: ByteArray): Int {
            records += SecureRecord(type, payload)
            return payload.size + SECURE_RECORD_OVERHEAD_BYTES
        }
    }

    private fun writeSequence(sink: RecordSink, sealer: EndToEndContentSealer): List<Int> {
        val sizes = ArrayList<Int>()
        sizes += EndToEndStreamProtocol.writeHeader(sink, sealer, StreamProtocol.CODEC_H264, settings, 0, includeAudio = true)
        sizes += EndToEndStreamProtocol.writePacket(sink, sealer, 0, 1_000L, ByteArray(64) { it.toByte() })
        sizes += EndToEndStreamProtocol.writeVideoFragment(sink, sealer, 0, ByteArray(40) { (it * 3).toByte() })
        sizes += EndToEndStreamProtocol.writeKeyFrameRequest(sink, sealer)
        sizes += EndToEndStreamProtocol.writeState(sink, sealer, StreamLifecycleState.ENDED)
        return sizes
    }

    @Test
    fun sealedRecordsReachAnySinkInSealOrder() {
        val sink = CollectingSink()
        val sealer = EndToEndContentSealer(key)
        writeSequence(sink, sealer)
        assertEquals(5, sink.records.size)
        assertTrue(sink.records.all { it.type == SecureRecordType.END_TO_END_CONTENT })
        // The seal order is the stream's sequence order.
        val sequences = sink.records.map { EndToEndContentProtocol.inspect(it.payload, acceptVideoFragments = true).sequence }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), sequences)
        val opener = EndToEndContentOpener(key).apply { acceptVideoFragments = true }
        val types = sink.records.map { opener.open(it.payload).type }
        assertEquals(
            listOf(
                EndToEndContentType.STREAM_HEADER,
                EndToEndContentType.MEDIA_PACKET,
                EndToEndContentType.VIDEO_FRAGMENT,
                EndToEndContentType.KEY_FRAME_REQUEST,
                EndToEndContentType.STREAM_STATE,
            ),
            types,
        )
    }

    @Test
    fun aLinkWriterStillWorksAsASink() {
        val out = ByteArrayOutputStream()
        val writer = SecureRecordWriter(out, key)
        val sealer = EndToEndContentSealer(key)
        writeSequence(writer, sealer)
        writer.flush()
        val reader = SecureRecordReader(ByteArrayInputStream(out.toByteArray()), key)
        val opener = EndToEndContentOpener(key).apply { acceptVideoFragments = true }
        val types = (0 until 5).map {
            val record = reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, record.type)
            opener.open(record.payload).type
        }
        assertEquals(
            listOf(
                EndToEndContentType.STREAM_HEADER,
                EndToEndContentType.MEDIA_PACKET,
                EndToEndContentType.VIDEO_FRAGMENT,
                EndToEndContentType.KEY_FRAME_REQUEST,
                EndToEndContentType.STREAM_STATE,
            ),
            types,
        )
    }

    @Test
    fun aBindOpenedOutOfBandLeavesTheStreamOrderAlone() {
        val sealer = EndToEndContentSealer(key)
        // r0: ordinary content; r1: the renewal BIND (BME1 type RENEW); r2: the first record after the switch.
        val r0 = sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, ByteArray(8) { 7 })
        val r1 = sealer.seal(EndToEndContentType.RENEW, 0, ByteArray(16) { 9 })
        val r2 = sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(32) { 11 })
        val opener = EndToEndContentOpener(key).apply { acceptRenewal = true }
        assertEquals(EndToEndContentType.PRIVATE_CALL_SIGNAL, opener.open(r0).type)
        // The BIND is opened without touching the stream's next sequence: the records sealed before and after it
        // still open in order (in fan-out the producer keeps sealing across the renewal).
        assertEquals(EndToEndContentType.RENEW, opener.openOutOfBand(r1).type)
        assertEquals(EndToEndContentType.MEDIA_PACKET, opener.open(r2).type)
        // Its single use was proved by the BMS1 layer: a repeat is a replay to the end-to-end order check.
        val replay = assertThrows(IllegalArgumentException::class.java) { opener.open(r1) }
        assertTrue(replay.message.orEmpty().contains("Replayed or reordered"))

        // A changed ciphertext byte never opens, and does not move the stream either.
        val broken = r1.copyOf()
        broken[broken.lastIndex] = (broken.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) { opener.openOutOfBand(broken) }
        // Only a RENEW record may take the out-of-band path, and the refusal happens before any decryption.
        val probe = EndToEndContentOpener(key).apply { acceptRenewal = true }
        assertThrows(IllegalArgumentException::class.java) { probe.openOutOfBand(r0) }
        assertEquals(EndToEndContentType.PRIVATE_CALL_SIGNAL, probe.open(r0).type)
    }
}
