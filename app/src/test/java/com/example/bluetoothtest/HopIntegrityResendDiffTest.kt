package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Experimental diagnostic `test_hop_integrity_resend_diff`: after a tag failure, compare the peer's resent chunks. */
class HopIntegrityResendDiffTest {
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val pipes = ArrayList<GattBytePipe>()
    private var savedSink: (String) -> Unit = {}

    @Before
    fun captureLog() {
        savedSink = HopIntegrityResendDiff.sink
        HopIntegrityResendDiff.sink = { lines += it }
    }

    @After
    fun closePipes() {
        pipes.forEach { it.close() }
        HopIntegrityResendDiff.sink = savedSink
    }

    private inner class Side(name: String, resendDiff: Boolean) {
        val recovery = GattChunkRecovery(name, log = {}, startThread = false)
        val air: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        val failures = mutableListOf<String>()
        val output = object : ChunkedGattOutputStream() {
            override val payloadBytes = 16
            override val recovery: GattChunkRecovery? = this@Side.recovery
            override fun sendPacket(value: ByteArray) {
                air += value.copyOf()
            }
        }
        val pipe = GattBytePipe(
            capacity = 8_192,
            gapFastFail = { true },
            recovery = recovery,
            dropEvery = 0,
            resendDiff = resendDiff,
            debugLog = {},
            onFailure = failures::add,
        ).also { pipes += it }

        fun flush() = output.sendPriority()
    }

    private fun isData(value: ByteArray) = value[0] == 1.toByte()

    private fun seq(value: ByteArray): Int =
        ((value[1].toInt() and 0xff) shl 24) or ((value[2].toInt() and 0xff) shl 16) or
            ((value[3].toInt() and 0xff) shl 8) or (value[4].toInt() and 0xff)

    private fun pump(from: Side, to: Side, change: (ByteArray) -> ByteArray = { it }) {
        val values = synchronized(from.air) { from.air.toList().also { from.air.clear() } }
        for (value in values) handleIncoming(to.pipe, change(value))
    }

    private fun readAll(pipe: GattBytePipe): ByteArray {
        val out = ByteArrayOutputStream()
        while (pipe.available() > 0) {
            val buffer = ByteArray(pipe.available())
            out.write(buffer, 0, pipe.read(buffer, 0, buffer.size))
        }
        return out.toByteArray()
    }

    private fun connected(resendDiff: Boolean = true): Pair<Side, Side> {
        val sender = Side("sender", resendDiff = false)
        val reader = Side("reader", resendDiff)
        sender.recovery.start(sender.output)
        reader.recovery.start(reader.output)
        sender.flush()
        reader.flush()
        pump(sender, reader)
        pump(reader, sender)
        return sender to reader
    }

    /** A record-sized message (a 17-byte header plus ciphertext stand-in); its bytes are what the reader received. */
    private fun record(size: Int = 120) = ByteArray(size) { (it * 13 + 5).toByte() }

    /** Sends [data] with one bit of chunk [badSeq] flipped on the way (below the app), and returns what was read. */
    private fun deliverWithFlip(sender: Side, reader: Side, data: ByteArray, badSeq: Int, offset: Int = 7): ByteArray {
        sender.output.write(data)
        pump(sender, reader) { value ->
            if (isData(value) && seq(value) == badSeq) value.copyOf().also { it[offset] = (it[offset].toInt() xor 0x10).toByte() } else value
        }
        return readAll(reader.pipe)
    }

    /** Runs [examine] on its own thread (it blocks like the record reader) and plays the peer until it returns. */
    private fun examineWithPeer(sender: Side, reader: Side, received: ByteArray, waitMs: Long = 5_000): List<ByteArray> {
        val worker = thread { HopIntegrityResendDiff.examine(received, waitMs) }
        val nacks = ArrayList<ByteArray>()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (worker.isAlive && System.nanoTime() < until) {
            reader.flush()
            synchronized(reader.air) { nacks += reader.air.filter { !isData(it) } }
            pump(reader, sender)
            sender.flush()
            pump(sender, reader)
            Thread.sleep(2)
        }
        worker.join(1_000)
        assertFalse("examine did not return", worker.isAlive)
        return nacks
    }

    @Test
    fun aFlippedBitInOneChunkIsNamedWhileTheResentCopyIsNeverDelivered() {
        val (sender, reader) = connected()
        val data = record()
        val received = deliverWithFlip(sender, reader, data, badSeq = 3)
        assertFalse(received.contentEquals(data))

        val nacks = examineWithPeer(sender, reader, received)

        assertEquals(1, nacks.count { Gr1.decode(it) is Gr1.Message.Nack })
        val diffs = lines.filter { it.startsWith("BENCHMARK hop_integrity_diff: ") }
        val chunks = (data.size + 15) / 16
        assertEquals(chunks, diffs.size)
        val flipped = diffs.single { "pattern=none" !in it }
        assertTrue(flipped, "chunk=3 |" in flipped && "pattern=bitflip" in flipped && "firstOffset=7 |" in flipped)
        val summary = lines.single { it.startsWith("BENCHMARK hop_integrity_diff_summary: ") }
        assertTrue(summary, "located=true" in summary && "differing=1" in summary && "reason=complete" in summary)
        // The resent values were compared only: nothing more reached the byte stream and the link did not fail here.
        assertEquals(0, readAll(reader.pipe).size)
        assertTrue(reader.failures.isEmpty())
    }

    @Test
    fun withTheSwitchOffNothingIsAskedOrLogged() {
        val (sender, reader) = connected(resendDiff = false)
        val received = deliverWithFlip(sender, reader, record(), badSeq = 2)
        HopIntegrityResendDiff.examine(received, waitMs = 50)
        reader.flush()
        assertTrue(reader.air.none { Gr1.decode(it) is Gr1.Message.Nack })
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aRecordThatNoLinkCarriedIsReportedAsNotLocatedAndAsksForNothing() {
        val (sender, reader) = connected()
        deliverWithFlip(sender, reader, record(), badSeq = 1)
        HopIntegrityResendDiff.examine(ByteArray(60) { 9 }, waitMs = 50)
        reader.flush()
        assertTrue(reader.air.none { Gr1.decode(it) is Gr1.Message.Nack })
        assertTrue(lines.single().contains("located=false"))
    }

    @Test
    fun resendsThatNeverArriveEndTheWaitAtItsBound() {
        val (sender, reader) = connected()
        val received = deliverWithFlip(sender, reader, record(), badSeq = 1)
        val started = System.nanoTime()
        HopIntegrityResendDiff.examine(received, waitMs = 100)
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("waited $tookMs ms", tookMs in 90L..2_000L)
        val summary = lines.single { it.startsWith("BENCHMARK hop_integrity_diff_summary: ") }
        assertTrue(summary, "resent=0" in summary && "reason=timeout" in summary)
    }

    @Test
    fun patternsNameTheShapeOfTheDifference() {
        val value = ByteArray(300) { it.toByte() }
        fun changed(vararg offsets: Int, mask: Int = 0x01) =
            value.copyOf().also { copy -> offsets.forEach { copy[it] = (copy[it].toInt() xor mask).toByte() } }

        assertEquals("none", HopIntegrityResendDiff.classify(value, value.copyOf()).pattern)
        assertEquals("bitflip", HopIntegrityResendDiff.classify(value, changed(40)).pattern)
        assertEquals("run", HopIntegrityResendDiff.classify(value, changed(40, 41, 42, mask = 0xff)).pattern)
        // 16+ bytes inside the second link-layer fragment (value bytes 244..494).
        assertEquals("fragment251", HopIntegrityResendDiff.classify(value, changed(*(250..270).toList().toIntArray(), mask = 0xff)).pattern)
        // The same span across the fragment boundary at 244 is not one fragment.
        assertEquals("run", HopIntegrityResendDiff.classify(value, changed(*(235..255).toList().toIntArray(), mask = 0xff)).pattern)
        assertEquals("whole_value", HopIntegrityResendDiff.classify(value, ByteArray(300) { (it + 1).toByte() }).pattern)
        assertEquals("whole_value", HopIntegrityResendDiff.classify(value, value.copyOf(200)).pattern)
        val diff = HopIntegrityResendDiff.classify(value, changed(100, 101, mask = 0x03))
        assertEquals(100, diff.firstOffset)
        assertEquals(2, diff.bytes)
    }
}
