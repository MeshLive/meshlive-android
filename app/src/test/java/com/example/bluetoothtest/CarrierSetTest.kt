package com.example.bluetoothtest

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * A3 steps 3-5: the dual-carrier core. Inline mode (no second carrier) is exactly today's call; fan-out (one more carrier)
 * adds one writer per carrier, one consumer and the [CarrierCutover] over `PipeCarrier`s, so no Wi-Fi is needed here.
 */
class CarrierSetTest {
    @get:Rule
    val perTestTimeout: Timeout = Timeout.seconds(60)

    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun newSet(
        host: CarrierSet.Host = RecordingHost(),
        prefix: String = "CS-" + System.nanoTime(),
        bluetoothQueue: Int = CarrierSet.BLUETOOTH_QUEUE_RECORDS,
        capacity: () -> Int = { 64 },
        nano: () -> Long = System::nanoTime,
        markers: CarrierMarkerCodec = TestMarkerCodec(),
        cutoverConfig: CarrierCutover.Config = testCutoverConfig(),
        keepFifoSlot: Boolean = false,
    ): CarrierSet = CarrierSet(
        cutover = CarrierCutover(cutoverConfig),
        markers = markers,
        host = host,
        threadPrefix = prefix,
        queueCapacityRecords = capacity,
        bluetoothQueueRecords = bluetoothQueue,
        flushIntervalNs = { 0L },
        nanoClock = nano,
        keepFifoSlot = { keepFifoSlot },
    )

    private fun sealer() = EndToEndContentSealer(key)

    private fun packet(set: CarrierSet, sealer: EndToEndContentSealer, pts: Long, size: Int = 32): Int =
        EndToEndStreamProtocol.writePacket(set.sink, sealer, 0, pts, ByteArray(size) { (it + pts.toInt()).toByte() })

    private fun sequenceOf(record: SecureRecord): Long =
        EndToEndContentProtocol.inspect(record.payload, true, true, true, true).sequence

    /** Reads from [carrier] until [done] or the timeout; leaves the reader thread daemon (a pipe read blocks). */
    private fun collectUntil(
        carrier: PipeCarrier,
        timeoutMs: Long,
        done: (List<SecureRecord>) -> Boolean,
    ): List<SecureRecord> {
        val out = CopyOnWriteArrayList<SecureRecord>()
        val reader = thread(isDaemon = true) {
            runCatching {
                while (!done(out)) out += carrier.link.reader.read()
            }
        }
        reader.join(timeoutMs)
        if (reader.isAlive) reader.interrupt()
        return out
    }

    /**
     * Waits until the machine has moved sending to carrier [id] (a carrier up is consumed asynchronously). It returns as soon
     * as that holds; the bound only decides how long a run on a saturated host may take before it is a failure.
     */
    private fun waitForSend(set: CarrierSet, id: Int, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (set.sendCarrier == id) return true
            Thread.sleep(5)
        }
        return set.sendCarrier == id
    }

    private fun noThreadsWithPrefix(prefix: String): List<String> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith(prefix) }.map { it.name }

    // ------------------------------------------------------------------------------------- step 3: outbound

    @Test
    fun aSingleCarrierSetWritesInlineAndStartsNoThread() {
        val prefix = "CS-inline-" + System.nanoTime()
        val pair = PipePair()
        val set = newSet(prefix = prefix)
        set.attachPrimary(pair.a, 0)
        val sealer = sealer()
        assertTrue(packet(set, sealer, 0) > 0)
        repeat(49) { packet(set, sealer, (it + 1).toLong()) }
        set.requestFlush()

        val throughSet = collectUntil(pair.b, 5_000) { it.size >= 50 }

        // The same 50 records written straight to a link writer: identical bytes on the wire.
        val direct = PipePair()
        val directSealer = sealer()
        repeat(50) { index ->
            EndToEndStreamProtocol.writePacket(direct.a.link.writer, directSealer, 0, index.toLong(), ByteArray(32) { (it + index).toByte() })
        }
        direct.a.link.writer.flush()
        val writtenDirectly = collectUntil(direct.b, 5_000) { it.size >= 50 }

        assertEquals(50, throughSet.size)
        assertEquals(50, writtenDirectly.size)
        throughSet.zip(writtenDirectly).forEach { (a, b) ->
            assertEquals(a.type, b.type)
            assertTrue(a.payload.contentEquals(b.payload))
        }
        assertFalse(set.fanout)
        assertTrue("no set thread in inline mode: ${noThreadsWithPrefix(prefix)}", noThreadsWithPrefix(prefix).isEmpty())
        set.close()
    }

    @Test
    fun writesBlockLikeTodayWhenTheOnlyCarrierIsStalled() {
        val pair = PipePair()
        val set = newSet()
        set.attachPrimary(pair.a, 0)
        val sealer = sealer()
        val read = CopyOnWriteArrayList<Long>()
        val reader = thread(isDaemon = true) {
            runCatching { repeat(20) { read += sequenceOf(pair.b.link.reader.read()) } }
        }
        pair.blockWritesFromA(true)
        val producer = thread {
            repeat(20) { packet(set, sealer, it.toLong(), size = 4_096) }
            set.requestFlush()
        }
        Thread.sleep(300)
        assertTrue("a stalled sole carrier blocks the producer, as a blocked write does today", producer.isAlive)
        pair.blockWritesFromA(false)
        producer.join(5_000)
        assertFalse(producer.isAlive)
        reader.join(5_000)
        assertEquals((0 until 20).map { it.toLong() }, read)
        set.close()
    }

    @Test
    fun fanOutStartsPerCarrierWritersAndRoutesToTheSendCarrier() {
        val host = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(host)
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        repeat(10) { packet(set, sealer, it.toLong()) }
        set.requestFlush()
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        repeat(10) { packet(set, sealer, (it + 10).toLong()) }

        val onZero = collectUntil(pair0.b, 5_000) { records ->
            records.any { it.type == SecureRecordType.BWF1 }
        }
        val onOne = collectUntil(pair1.b, 5_000) { records ->
            records.count { it.type == SecureRecordType.END_TO_END_CONTENT } >= 10
        }
        val zeroContent = onZero.filter { it.type == SecureRecordType.END_TO_END_CONTENT }.map(::sequenceOf)
        val oneContent = onOne.filter { it.type == SecureRecordType.END_TO_END_CONTENT }.map(::sequenceOf)
        assertEquals((0 until 10).map { it.toLong() }, zeroContent)
        assertEquals((10 until 20).map { it.toLong() }, oneContent)
        // The SWITCH marker follows every record sealed before it on the same FIFO, and names carrier 1.
        val switchIndex = onZero.indexOfFirst { it.type == SecureRecordType.BWF1 }
        val lastOldContent = onZero.indexOfLast { it.type == SecureRecordType.END_TO_END_CONTENT }
        assertTrue("switch at $switchIndex after content at $lastOldContent", switchIndex > lastOldContent)
        val switch = TestMarkerCodec().wasSwitch(onZero[switchIndex].payload)
        assertEquals(1, switch?.carrier)
        assertTrue(set.fanout)
        set.close()
    }

    @Test
    fun aBlockedCarrierZeroWriterDoesNotDelayCarrierOne() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet()
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        pair0.blockWritesFromA(true)
        repeat(200) { packet(set, sealer, (it + 10).toLong()) }
        assertTrue("carrier 1 drains while carrier 0 is blocked", set.awaitDrained(1, 1_000))
        val onOne = collectUntil(pair1.b, 5_000) { records ->
            records.count { it.type == SecureRecordType.END_TO_END_CONTENT } >= 200
        }
        assertEquals("stats0=${set.stats(0)} stats1=${set.stats(1)} types=${onOne.map { it.type.toString() }}", 200, onOne.count { it.type == SecureRecordType.END_TO_END_CONTENT })
        pair0.blockWritesFromA(false)
        set.close()
    }

    @Test
    fun aFullQueueBlocksTheProducerAndDoesNotDeadlockTheCutoverLock() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        // No onCallActive: sending stays on carrier 0.
        pair0.blockWritesFromA(true)
        val producer = thread {
            repeat(200) { packet(set, sealer, it.toLong()) }
            set.requestFlush()
        }
        Thread.sleep(400)
        assertTrue("the producer blocks on a full carrier-0 queue", producer.isAlive)
        val lockFree = java.util.concurrent.atomic.AtomicBoolean(false)
        val other = thread {
            set.carrierPending(1)
            set.carrierUp(1)
            lockFree.set(true)
        }
        other.join(100)
        assertFalse("a cutover entry point waits for the producer's queue wait", other.isAlive)
        assertTrue(lockFree.get())
        pair0.blockWritesFromA(false)
        producer.join(10_000)
        assertFalse(producer.isAlive)
        set.close()
    }

    @Test
    fun controlRecordsOvertakeMediaOnTheirCarrier() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        pair0.blockWritesFromA(true)
        repeat(50) { packet(set, sealer, it.toLong()) }
        val control = byteArrayOf('C'.code.toByte(), 7, 7)
        assertTrue(set.sendHop(0, SecureRecordType.BWF1, control))
        pair0.blockWritesFromA(false)
        val records = collectUntil(pair0.b, 5_000) { collected ->
            collected.count { it.type == SecureRecordType.END_TO_END_CONTENT } >= 50
        }
        val controlIndex = records.indexOfFirst { it.type == SecureRecordType.BWF1 && it.payload.contentEquals(control) }
        assertTrue("the control lane was served", controlIndex >= 0)
        // Only the record already in flight may precede it.
        val contentBefore = records.take(controlIndex).count { it.type == SecureRecordType.END_TO_END_CONTENT }
        assertTrue("control overtook the queued media (content before it: $contentBefore)", contentBefore <= 1)
        set.close()
    }

    @Test
    fun cookiesComeBackWhenACarrierDies() {
        val host = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        pair0.blockWritesFromA(true)
        val cookies = listOf("c1", "c2", "c3", "c4")
        for (cookie in cookies) set.withCookie(cookie) { packet(set, sealer, 0) }
        Thread.sleep(100)
        set.carrierDied(0, "test")
        assertTrue(host.awaitUntil(2_000) { host.requeued.size >= 1 })
        // Every queued record with a cookie comes back in order, once (the one already being written is lost).
        assertEquals(1, host.requeued.size)
        val requeued = host.requeued.first() as List<*>
        assertTrue(requeued.isNotEmpty())
        assertEquals(cookies.takeLast(requeued.size), requeued)
        assertFalse(set.carrierDied(0, "test"))
        Thread.sleep(100)
        assertEquals(1, host.requeued.size)
        pair0.blockWritesFromA(false)
        set.close()
    }

    @Test
    fun aQueuedWriteReturnsTheWireSizeOfTheRecord() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet()
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        val sealed = sealer().seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(40))
        assertEquals(sealed.size + SECURE_RECORD_OVERHEAD_BYTES, set.sink.write(SecureRecordType.END_TO_END_CONTENT, sealed))
        set.close()
        assertEquals(0, set.sink.write(SecureRecordType.END_TO_END_CONTENT, sealed))
    }

    @Test
    fun sendHopToSenderUsesAnyLiveCarrierWhenTheSendCarrierIsDead() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet()
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        pair1.cut()
        set.carrierDied(1, "test")
        assertTrue(waitForSend(set, 0))
        val payload = byteArrayOf('H'.code.toByte(), 1, 2)
        assertTrue(set.sendHopToSender(SecureRecordType.BWF1, payload))
        val records = collectUntil(pair0.b, 5_000) { collected ->
            collected.any { it.type == SecureRecordType.BWF1 && it.payload.contentEquals(payload) }
        }
        assertTrue(records.any { it.type == SecureRecordType.BWF1 && it.payload.contentEquals(payload) })
        set.close()
    }

    @Test
    fun requestFlushNeverBlocks() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        val sealer = sealer()
        pair0.blockWritesFromA(true)
        repeat(5) { packet(set, sealer, it.toLong()) }
        val started = System.nanoTime()
        set.requestFlush()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("requestFlush waited ${elapsedMs}ms on a blocked carrier", elapsedMs < 200)
        pair0.blockWritesFromA(false)
        set.close()
    }

    @Test
    fun markersGoThroughTheMediaFifoNotTheControlLane() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        pair0.blockWritesFromA(true)
        repeat(50) { packet(set, sealer, it.toLong()) }
        // The switch happens now: its SWITCH marker queues behind the 50 media records.
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        val control = byteArrayOf('C'.code.toByte(), 9, 9)
        assertTrue(set.sendHop(0, SecureRecordType.BWF1, control))
        pair0.blockWritesFromA(false)
        val records = collectUntil(pair0.b, 5_000) { collected ->
            collected.any { it.type == SecureRecordType.BWF1 && TestMarkerCodec().wasSwitch(it.payload) != null }
        }
        val switchIndex = records.indexOfFirst { it.type == SecureRecordType.BWF1 && TestMarkerCodec().wasSwitch(it.payload) != null }
        val controlIndex = records.indexOfFirst { it.type == SecureRecordType.BWF1 && it.payload.contentEquals(control) }
        assertTrue("the SWITCH marker was written", switchIndex >= 0)
        assertTrue("the control lane was served", controlIndex >= 0)
        assertTrue("the control overtakes the queued media", controlIndex < switchIndex)
        val contentBeforeSwitch = records.take(switchIndex).count { it.type == SecureRecordType.END_TO_END_CONTENT }
        assertEquals("the SWITCH waits behind every media record sealed before it", 50, contentBeforeSwitch)
        set.close()
    }

    @Test
    fun aCallSetRejectsAContentTypeItDoesNotCarry() {
        val set = newSet()
        val error = assertThrows(IllegalArgumentException::class.java) {
            set.sink.write(SecureRecordType.MESH_ENVELOPE, ByteArray(8))
        }
        assertEquals("Only end-to-end content goes through the sink", error.message)
        set.close()
    }

    @Test
    fun twoReadersGetDistinctTagsAndEveryPayloadIsTakenOnce() {
        val store = PayloadStore()
        val tags = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val payloads = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
        val start = CountDownLatch(1)
        val writers = (0 until 2).map { readerIndex ->
            thread {
                start.await()
                repeat(10_000) { index ->
                    val payload = ByteArray(4) { ((readerIndex * 7 + index) and 0xff).toByte() }
                    val tag = store.put(SecureRecordType.END_TO_END_CONTENT, payload)
                    tags += tag
                    payloads[tag] = payload
                }
            }
        }
        start.countDown()
        writers.forEach { it.join(10_000) }
        assertEquals(20_000, tags.size)
        assertTrue(tags.all { it > 0 })
        for (tag in tags) {
            val taken = store.take(tag)
            assertTrue("every payload is consumed exactly once at tag $tag", taken != null)
            assertTrue(taken!!.second.contentEquals(payloads.getValue(tag)))
        }
        assertTrue(tags.all { store.take(it) == null })
        store.clear()
    }

    @Test
    fun aRecordWrittenInlineBeforeFanOutIsNeverOvertakenByTheSwitch() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet()
        set.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        pair0.blockWritesFromA(true)
        val producer = thread { packet(set, sealer, 0, size = 9_000) }
        Thread.sleep(100)
        assertTrue(producer.isAlive)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        pair0.blockWritesFromA(false)
        producer.join(5_000)
        val records = collectUntil(pair0.b, 5_000) { collected ->
            collected.any { it.type == SecureRecordType.BWF1 && TestMarkerCodec().wasSwitch(it.payload) != null }
        }
        val contentIndex = records.indexOfFirst { it.type == SecureRecordType.END_TO_END_CONTENT }
        val switchIndex = records.indexOfFirst { it.type == SecureRecordType.BWF1 && TestMarkerCodec().wasSwitch(it.payload) != null }
        assertTrue(contentIndex >= 0)
        assertTrue("the inline record precedes the switch ($contentIndex < $switchIndex)", contentIndex < switchIndex)
        assertNotEquals(-1L, sequenceOf(records[contentIndex]))
        set.close()
    }

    /** A hold ceiling long enough that the 400 ms stall does not make the cutover wait for the old queue to drain (it waits at holdMs / 2). */
    private val stalledQueueConfig = CarrierCutover.Config(activeBeforeSwitchMs = 0L, holdMs = 5_000L, cooldownMs = 500L)

    /**
     * The SW1 race: fan-out is on, carrier 0 (Bluetooth, queue capacity 2) is stalled, the producer is blocked on record 2 (already
     * sealed, so inside the switch boundary) and the switch to carrier 1 happens before the producer gets room.
     * Returns the running producer thread; the caller reads the peer's ends.
     */
    private fun startBlockedProducerThenSwitch(
        a: CarrierSet,
        pair0: PipePair,
        pair1: PipePair,
        total: Int,
        onBeforeUnblock: () -> Unit = {},
    ): Thread {
        a.attachPrimary(pair0.a, 0)
        assertEquals(1, a.addCarrier(pair1.a))
        onBeforeUnblock()
        val sealer = sealer()
        pair0.blockWritesFromA(true)
        val producer = thread {
            try {
                repeat(total) { packet(a, sealer, it.toLong()) }
            } catch (error: Throwable) {
                println("SW1 producer failed: $error")
            }
        }
        Thread.sleep(400) // the writer holds a blocked write, two records queue; the producer waits on a record sealed past the full queue
        println("SW1 before switch: producerAlive=${producer.isAlive} oldQueueAgeMs=${a.oldQueueAgeMs()} snapshot=${a.snapshot()}")
        a.carrierPending(1)
        a.carrierUp(1)
        a.onCallActive()
        assertTrue("the switch happened (send carrier ${a.sendCarrier}; oldQueueAgeMs=${a.oldQueueAgeMs()})", waitForSend(a, 1))
        Thread.sleep(100)
        pair0.blockWritesFromA(false)
        return producer
    }

    @Test
    fun aSealedRecordKeepsItsPlaceBeforeTheSwitchMarkerWhenTheQueueWasFull() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val a = newSet(keepFifoSlot = true, cutoverConfig = stalledQueueConfig)
        val producer = startBlockedProducerThenSwitch(a, pair0, pair1, total = 6)
        producer.join(10_000)
        assertFalse(producer.isAlive)
        val onZero = collectUntil(pair0.b, 5_000) { it.any { r -> r.type == SecureRecordType.BWF1 } }
        val zeroContent = onZero.filter { it.type == SecureRecordType.END_TO_END_CONTENT }.map(::sequenceOf)
        val onOne = collectUntil(pair1.b, 5_000) { it.count { r -> r.type == SecureRecordType.END_TO_END_CONTENT } >= 6 - zeroContent.size }
        val oneContent = onOne.filter { it.type == SecureRecordType.END_TO_END_CONTENT }.map(::sequenceOf)
        // Which record the stalled queue held back depends on whether the writer wrote its first heartbeat before or after record 0;
        // either way every record sealed before the switch (at least records 0..2) stays ahead of the marker on carrier 0.
        assertTrue("at least three records on carrier 0 (read ${onZero.map { it.type }})", zeroContent.size >= 3)
        assertEquals("a contiguous prefix on carrier 0", (0 until zeroContent.size).map { it.toLong() }, zeroContent)
        assertEquals("the SWITCH marker is the last thing read, after the last record of the prefix", onZero.size - 1, onZero.indexOfFirst { it.type == SecureRecordType.BWF1 })
        assertEquals("everything sealed after the switch is on carrier 1", (zeroContent.size until 6).map { it.toLong() }, oneContent)
        a.close()
    }

    @Test
    fun theReceiverDeliversEveryRecordWhenTheSwitchHappensWhileTheProducerWaitsForRoom() {
        val hostB = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val a = newSet(keepFifoSlot = true, cutoverConfig = stalledQueueConfig)
        val b = receiver("CS-keep-" + System.nanoTime(), pair0, hostB, cutoverConfig = stalledQueueConfig)
        val producer = startBlockedProducerThenSwitch(a, pair0, pair1, total = 6) {
            assertEquals(1, b.addCarrier(pair1.b))
            b.carrierPending(1)
            b.carrierUp(1)
        }
        assertTrue(hostB.await(6, 10_000))
        producer.join(5_000)
        assertEquals((0 until 6).map { it.toLong() }, hostB.sequences())
        assertTrue("no record was dropped: ${hostB.logs}", hostB.logs.none { it.startsWith("carrier_drop") })
        a.close()
        b.close()
    }

    @Test
    fun withoutTheSwitchTheBlockedRecordStillGoesOutOnTheNewCarrierBelowItsBoundary() {
        // The control arm (today's behaviour): the sealed record retries on the new carrier and the receiver drops it as stale.
        val hostB = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val a = newSet(keepFifoSlot = false, cutoverConfig = stalledQueueConfig)
        val b = receiver("CS-keepoff-" + System.nanoTime(), pair0, hostB, cutoverConfig = stalledQueueConfig)
        val producer = startBlockedProducerThenSwitch(a, pair0, pair1, total = 6) {
            assertEquals(1, b.addCarrier(pair1.b))
            b.carrierPending(1)
            b.carrierUp(1)
        }
        assertTrue(hostB.await(5, 10_000))
        producer.join(5_000)
        assertEquals("exactly the record that waited for room is missing: ${hostB.sequences()}", 5, hostB.sequences().distinct().size)
        assertTrue(hostB.awaitUntil(2_000) { hostB.logs.any { it.startsWith("carrier_drop reason=stale") } })
        a.close()
        b.close()
    }

    // -------------------------------------------------------------------------------------- step 4: inbound

    /** A receiving set: carrier 0 over [pair.b] (the end whose reader reads the peer's writes on [pair.a]). */
    private fun receiver(
        prefix: String,
        pair: PipePair,
        host: RecordingHost = RecordingHost(),
        cutoverConfig: CarrierCutover.Config = testCutoverConfig(),
        nano: () -> Long = System::nanoTime,
        markers: CarrierMarkerCodec = TestMarkerCodec(),
    ): CarrierSet {
        val set = newSet(host, prefix, cutoverConfig = cutoverConfig, nano = nano, markers = markers)
        set.attachPrimary(pair.b, 0)
        thread(isDaemon = true) {
            try {
                while (!set.ended) set.onInbound(0, 0, pair.b.link.reader.read())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                // The call's carrier-0 reader reports its own failure; a test carrier does the same.
                if (!set.ended && set.isLive(0)) set.carrierDied(0, "read failed: ${error.javaClass.simpleName}")
            }
        }
        return set
    }

    @Test
    fun recordsAreDeliveredOnceInSequenceAcrossASwitch() {
        val hostA = RecordingHost()
        val hostB = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val a = newSet(hostA, bluetoothQueue = 64)
        val b = receiver("CS-b-" + System.nanoTime(), pair0, hostB)
        a.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        repeat(150) { packet(a, sealer, it.toLong()) }
        a.requestFlush()
        // Both sides learn about carrier 1 before the switch, so the marker names a known carrier.
        assertEquals(1, a.addCarrier(pair1.a))
        assertEquals(1, b.addCarrier(pair1.b))
        b.carrierPending(1)
        b.carrierUp(1)
        a.carrierPending(1)
        a.carrierUp(1)
        a.onCallActive()
        assertTrue(waitForSend(a, 1))
        repeat(150) { packet(a, sealer, (it + 150).toLong()) }
        assertTrue("carrier 1 drained", a.awaitDrained(1, 5_000))
        assertTrue(hostB.await(300, 10_000))
        assertEquals(300, hostB.delivered.size)
        assertEquals((0 until 300).map { it.toLong() }, hostB.sequences())
        val onNew = hostB.delivered.filter { it.first == 1 }
        assertTrue("the last records came on carrier 1 (${onNew.size})", onNew.size >= 100)
        a.close()
        b.close()
    }

    /** A receiver-side codec whose first SWITCH names an unknown ticket (decodes to the burned carrier), then behaves normally. */
    private class BurnFirstSwitchCodec : CarrierMarkerCodec {
        private val plain = TestMarkerCodec()
        private var burned = false

        override fun encode(wire: CarrierCutover.Wire): ByteArray = plain.encode(wire)

        override fun decode(payload: ByteArray): CarrierCutover.Wire? {
            val wire = plain.decode(payload)
            if (wire is CarrierCutover.Wire.Switch && !burned) {
                burned = true
                return CarrierCutover.Wire.Switch(wire.gen, wire.boundary, CarrierCutover.NONE)
            }
            return wire
        }
    }

    /**
     * Repro for the 0007 report: a SWITCH whose ticket the receiver does not know yet (the call registers the ticket only after READY)
     * is burned; the records the sender then writes on the new carrier must still reach the receiver, held at most the hold
     * ceiling and promoted. Passes at the carrier-set level: the receiver's hold promotes and delivers all of them.
     */
    @Test
    fun recordsOnTheNewCarrierAfterABurnedSwitchAreDeliveredAfterTheHold() {
        val hostB = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val config = CarrierCutover.Config(activeBeforeSwitchMs = 0L, holdMs = 1_000L, cooldownMs = 500L)
        val a = newSet(RecordingHost(), bluetoothQueue = 64)
        val set = newSet(hostB, "CS-burn-" + System.nanoTime(), cutoverConfig = config, markers = BurnFirstSwitchCodec())
        set.attachPrimary(pair0.b, 0)
        thread(isDaemon = true) {
            runCatching { while (!set.ended) set.onInbound(0, 0, pair0.b.link.reader.read()) }
        }
        a.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        repeat(10) { packet(a, sealer, it.toLong()) }
        assertEquals(1, a.addCarrier(pair1.a))
        a.carrierPending(1)
        a.carrierUp(1)
        a.onCallActive()
        assertTrue(waitForSend(a, 1))
        assertTrue(hostB.await(10, 5_000))
        Thread.sleep(300) // the SWITCH on carrier 0 was read and burned: the receiver does not know carrier 1 yet
        assertEquals(1, set.addCarrier(pair1.b))
        set.carrierPending(1)
        set.carrierUp(1)
        repeat(16) { packet(a, sealer, (10 + it).toLong()) }
        assertTrue("every record is delivered after the hold promotes: ${hostB.sequences()} logs=${hostB.logs}", hostB.await(26, 6_000))
        assertEquals((0 until 26).map { it.toLong() }, hostB.sequences())
        a.close()
        set.close()
        if (BuildConfig.DEBUG) {
            // The DEBUG count the 0007 analysis needs: every record read on the new carrier after the burn was held, then released.
            val summary = hostB.logs.firstOrNull { it.startsWith("BENCHMARK switch_burned_summary") }
            assertTrue("a summary line follows the burned SWITCH: ${hostB.logs}", summary != null)
            assertTrue(summary!!, "read=16 | held=16 | released=16 | delivered=16 | dropped=0" in summary)
            assertTrue(hostB.logs.any { it.startsWith("BENCHMARK switch_burned:") })
        }
    }

    @Test
    fun aSwitchMarkerThatArrivesLateIsHandledByTheMachine() {
        val hostB = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val a = newSet(RecordingHost(), bluetoothQueue = 64)
        val b = receiver(
            prefix = "CS-late-" + System.nanoTime(),
            pair = pair0,
            host = hostB,
            cutoverConfig = CarrierCutover.Config(activeBeforeSwitchMs = 0L, holdMs = 5_000L, cooldownMs = 500L),
        )
        a.attachPrimary(pair0.a, 0)
        val sealer = sealer()
        assertEquals(1, a.addCarrier(pair1.a))
        assertEquals(1, b.addCarrier(pair1.b))
        b.carrierPending(1)
        b.carrierUp(1)
        a.carrierPending(1)
        a.carrierUp(1)
        // The marker's carrier is blocked, so the new carrier's records arrive first and are held.
        pair0.blockWritesFromA(true)
        a.onCallActive()
        assertTrue(waitForSend(a, 1))
        repeat(20) { packet(a, sealer, it.toLong()) }
        assertTrue(hostB.awaitUntil(2_000) { b.heldCount > 0 })
        pair0.blockWritesFromA(false)
        assertTrue(hostB.await(20, 5_000))
        assertEquals((0 until 20).map { it.toLong() }, hostB.sequences())
        a.close()
        b.close()
    }

    @Test
    fun bwfRecordsThatAreNotMarkersGoToTheHost() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val b = receiver("CS-bwf-" + System.nanoTime(), pair0, hostB)
        val payload = byteArrayOf('X'.code.toByte(), 1, 2, 3)
        pair0.a.link.writer.write(SecureRecordType.BWF1, payload)
        pair0.a.link.writer.flush()
        assertTrue(hostB.awaitWifi(1, 3_000))
        assertEquals(1, hostB.wifiRecords.size)
        assertTrue(hostB.wifiRecords.first().second.contentEquals(payload))
        assertEquals(0, hostB.delivered.size)
        b.close()
    }

    @Test
    fun anUnexpectedRecordTypeIsAProtocolError() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val b = receiver("CS-proto-" + System.nanoTime(), pair0, hostB)
        pair0.a.link.writer.write(SecureRecordType.ROUTE_CONTROL, byteArrayOf(1, 2))
        pair0.a.link.writer.flush()
        assertTrue(hostB.awaitUntil(3_000) { hostB.protocolErrors.isNotEmpty() })
        assertEquals("Unexpected private-call record ${SecureRecordType.ROUTE_CONTROL}", hostB.protocolErrors.first())
        assertEquals(0, hostB.delivered.size)
        b.close()
    }

    @Test
    fun aStaleGenerationIsIgnored() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val b = receiver("CS-gen-" + System.nanoTime(), pair0, hostB)
        val replacement = PipePair()
        b.replacePrimary(replacement.a, 1)
        pair0.a.link.writer.write(
            SecureRecordType.END_TO_END_CONTENT,
            sealer().seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8)),
        )
        pair0.a.link.writer.flush()
        Thread.sleep(200)
        assertEquals(0, hostB.delivered.size)
        b.close()
    }

    @Test
    fun consumerErrorsReachTheHost() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val b = receiver("CS-err-" + System.nanoTime(), pair0, hostB)
        assertEquals(1, b.addCarrier(pair1.b))
        b.carrierPending(1)
        b.carrierUp(1)
        val sealer = sealer()
        hostB.deliverError = IllegalStateException("consumer boom")
        pair0.a.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8)))
        pair0.a.link.writer.flush()
        assertTrue(hostB.awaitUntil(3_000) { hostB.consumerErrors.isNotEmpty() })
        assertEquals("consumer boom", hostB.consumerErrors.first().message)
        hostB.deliverError = null
        pair0.a.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8)))
        pair0.a.link.writer.flush()
        assertTrue("the consumer keeps running after an error", hostB.await(1, 3_000))
        b.close()
    }

    @Test
    fun inlineModeConsumesOnTheReaderThread() {
        val host = RecordingHost()
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        val sealed = sealer().seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8))
        val caller = Thread.currentThread()
        set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, sealed))
        assertEquals(1, host.delivered.size)
        assertSame(caller, host.deliverThreads.first())
        set.close()
    }

    @Test
    fun statsCountBytesAndRecordsReadAndWritten() {
        val host = RecordingHost()
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        val sealer = sealer()
        val sealed = sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(16))
        set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, sealed))
        val read = requireNotNull(set.stats(0))
        assertEquals(1, read.recordsRead.toInt())
        assertEquals((sealed.size + SECURE_RECORD_OVERHEAD_BYTES).toLong(), read.bytesRead)
        // A fan-out writer counts what it wrote.
        assertEquals(1, set.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        repeat(3) { packet(set, sealer, (it + 1).toLong()) }
        assertTrue(set.awaitDrained(1, 2_000))
        val written = requireNotNull(set.stats(1))
        assertTrue(written.recordsWritten >= 3)
        assertTrue(written.bytesWritten > 0)
        set.close()
    }

    @Test
    fun setMaxCiphertextBytesReachesEveryCarrierReader() {
        val pair0 = PipePair()
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet()
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.setMaxCiphertextBytes(4_096)
        assertEquals(4_096, pair0.a.link.reader.maxCiphertextBytes)
        assertEquals(4_096, pair1.a.link.reader.maxCiphertextBytes)
        val later = PipePair(CarrierKind.WIFI_AWARE)
        pair1.cut()
        set.carrierDied(1, "test")
        Thread.sleep(200)
        val id = set.addCarrier(later.a)
        assertTrue(id > 0)
        assertEquals(4_096, later.a.link.reader.maxCiphertextBytes)
        set.close()
    }

    @Test
    fun aCredsOrCloseRecordOnCarrierZeroIsNotAProtocolViolation() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val b = receiver("CS-creds-" + System.nanoTime(), pair0, hostB)
        pair0.a.link.writer.write(SecureRecordType.BWF1, byteArrayOf(10))
        pair0.a.link.writer.write(SecureRecordType.BWF1, byteArrayOf(11))
        pair0.a.link.writer.flush()
        assertTrue(hostB.awaitWifi(2, 3_000))
        assertEquals(listOf(10, 11), hostB.wifiRecords.map { it.second.first().toInt() })
        assertEquals(0, hostB.protocolErrors.size)
        b.close()
    }

    @Test
    fun anOldGenerationItemQueuedBeforeAReplacementIsDropped() {
        val host = RecordingHost()
        val pair0 = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        set.carrierPending(1)
        set.carrierUp(1)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        host.onDeliver = {
            blocked.countDown()
            release.await()
        }
        val sealer = sealer()
        set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8))))
        assertTrue(blocked.await(3, TimeUnit.SECONDS))
        // A second item read under the old generation is queued while the consumer is busy.
        set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8))))
        val replacement = PipePair()
        set.replacePrimary(replacement.a, 1)
        release.countDown()
        assertTrue(host.awaitLog("carrier_drop reason=old_generation", 3_000))
        set.close()
    }

    @Test
    fun inlineModeHandsAReplayedRecordToTheOpener() {
        val key = ByteArray(32) { (it + 9).toByte() }
        val opener = EndToEndContentOpener(key)
        val delivered = CopyOnWriteArrayList<Long>()
        val host = object : RecordingHost() {
            override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
                delivered += opener.open(payload).sequence
            }
        }
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        val sealed = EndToEndContentSealer(key).seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8))
        set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, sealed))
        assertEquals(listOf(0L), delivered.toList())
        val error = assertThrows(IllegalArgumentException::class.java) {
            set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, sealed))
        }
        assertTrue(error.message.orEmpty().contains("Replayed or reordered"))
        set.close()
    }

    @Test
    fun inlineModeKeepsTheOpenersErrorForMalformedContent() {
        val host = RecordingHost()
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        set.onInbound(0, 0, SecureRecord(SecureRecordType.END_TO_END_CONTENT, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals(1, host.delivered.size)
        assertEquals(0, host.protocolErrors.size)
        set.close()
    }

    @Test
    fun theHeartbeatCarriesTheGenerationItWasReadUnder() {
        val host = RecordingHost()
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        set.onInbound(0, 0, SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1)))
        assertEquals(listOf(0 to 0), host.heartbeatGenerations.toList())
        val replacement = PipePair()
        set.replacePrimary(replacement.a, 7)
        set.onInbound(0, 7, SecureRecord(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1)))
        assertEquals(listOf(0 to 0, 0 to 7), host.heartbeatGenerations.toList())
        set.close()
    }

    @Test
    fun aMarkerTheCodecCannotDecodeGoesToTheAttemptOwner() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val b = receiver("CS-codec-" + System.nanoTime(), pair0, hostB)
        val payload = byteArrayOf('M'.code.toByte(), 0x7F)
        pair0.a.link.writer.write(SecureRecordType.BWF1, payload)
        pair0.a.link.writer.flush()
        assertTrue(hostB.awaitWifi(1, 3_000))
        assertTrue(hostB.wifiRecords.first().second.contentEquals(payload))
        assertEquals(0, hostB.protocolErrors.size)
        b.close()
    }

    @Test
    fun anAddedCarriersReaderStartsOnlyAfterItsUp() {
        val hostB = RecordingHost()
        val pair0 = PipePair()
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val b = receiver("CS-up-" + System.nanoTime(), pair0, hostB)
        assertEquals(1, b.addCarrier(pair1.b))
        val sealed = sealer().seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8))
        // The peer's record is already on the new carrier before this side's READY/BIND exchange finished.
        pair1.a.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealed)
        pair1.a.link.writer.flush()
        Thread.sleep(200)
        assertEquals(0, hostB.delivered.size)
        b.carrierPending(1)
        b.carrierUp(1)
        assertTrue(hostB.await(1, 3_000))
        assertEquals(listOf(1 to 0L), hostB.delivered.toList())
        b.close()
    }

    @Test
    fun anExtraContentTypeIsRoutedAndDeliveredWithItsSequence() {
        val received = CopyOnWriteArrayList<Pair<SecureRecordType, Long>>()
        val host = object : RecordingHost() {
            override fun deliverRecord(carrierId: Int, generation: Int, type: SecureRecordType, payload: ByteArray) {
                received += type to java.nio.ByteBuffer.wrap(payload).getLong(0)
            }
        }
        val pair0 = PipePair()
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = CarrierSet(
            cutover = CarrierCutover(testCutoverConfig()),
            markers = TestMarkerCodec(),
            host = host,
            threadPrefix = "CS-extra-" + System.nanoTime(),
            bluetoothQueueRecords = 64,
            flushIntervalNs = { 0L },
            contentTypes = setOf(SecureRecordType.END_TO_END_CONTENT, SecureRecordType.MESH_ENVELOPE),
            sequenceOf = { _, bytes -> java.nio.ByteBuffer.wrap(bytes).getLong(0) },
        )
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        val reader = thread(isDaemon = true) {
            runCatching { while (true) set.onInbound(1, 0, pair1.b.link.reader.read()) }
        }
        repeat(4) { index ->
            val type = if (index % 2 == 0) SecureRecordType.END_TO_END_CONTENT else SecureRecordType.MESH_ENVELOPE
            val payload = java.nio.ByteBuffer.allocate(8).putLong(index.toLong()).array()
            assertTrue(set.sink.write(type, payload) > 0)
        }
        assertTrue(set.awaitDrained(1, 3_000))
        assertTrue(host.awaitUntil(3_000) { received.size >= 4 })
        assertEquals(
            listOf(
                SecureRecordType.END_TO_END_CONTENT to 0L,
                SecureRecordType.MESH_ENVELOPE to 1L,
                SecureRecordType.END_TO_END_CONTENT to 2L,
                SecureRecordType.MESH_ENVELOPE to 3L,
            ),
            received.toList(),
        )
        assertEquals(0, host.protocolErrors.size)
        reader.interrupt()
        set.close()
    }

    @Test
    fun passTypesReachOnHopRecordInArrivalOrder() {
        val host = RecordingHost()
        val pair0 = PipePair()
        val set = CarrierSet(
            cutover = CarrierCutover(testCutoverConfig()),
            markers = TestMarkerCodec(),
            host = host,
            threadPrefix = "CS-pass-" + System.nanoTime(),
            flushIntervalNs = { 0L },
            passTypes = setOf(SecureRecordType.ROUTE_CONTROL),
        )
        set.attachPrimary(pair0.a, 0)
        val writer = pair0.b.link.writer
        repeat(3) { index ->
            writer.write(SecureRecordType.ROUTE_CONTROL, byteArrayOf(index.toByte()))
            writer.flush()
            set.onInbound(0, 0, pair0.a.link.reader.read())
        }
        assertEquals(listOf(0, 1, 2), host.hopRecords.map { it.payload[0].toInt() })
        assertEquals(0, host.protocolErrors.size)
        assertEquals(0, host.delivered.size)
        set.close()
    }

    @Test
    fun closeFromAHostCallbackDoesNotInterruptTheCallingThread() {
        val pair0 = PipePair()
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val delivered = java.util.concurrent.CountDownLatch(1)
        var setRef: CarrierSet? = null
        val host = object : RecordingHost() {
            override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
                setRef?.close()
                if (Thread.currentThread().isInterrupted) interrupted.set(true)
                delivered.countDown()
            }
        }
        val prefix = "CS-close-" + System.nanoTime()
        val set = newSet(host, prefix)
        setRef = set
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        val sealer = sealer()
        pair0.b.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8)))
        pair0.b.link.writer.flush()
        set.onInbound(0, 0, pair0.a.link.reader.read())
        assertTrue("a host callback ran", delivered.await(3, TimeUnit.SECONDS))
        assertFalse("a host callback never interrupts the thread that closed the set", interrupted.get())
        Thread.sleep(200)
        assertTrue("every set thread ends within 2 s: ${noThreadsWithPrefix(prefix)}", noThreadsWithPrefix(prefix).isEmpty())
        set.close()
    }

    @Test
    fun carrierEventsNeverBlockTheCaller() {
        val pair0 = PipePair()
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val deliverBlocked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val host = object : RecordingHost() {
            override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
                deliverBlocked.countDown()
                release.await()
                super.deliver(carrierId, generation, payload)
            }
        }
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        val sealer = sealer()
        pair0.b.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8)))
        pair0.b.link.writer.flush()
        set.onInbound(0, 0, pair0.a.link.reader.read())
        assertTrue(deliverBlocked.await(3, TimeUnit.SECONDS))
        // The consumer is stuck inside deliver; a death from another thread must not wait for it.
        val started = System.nanoTime()
        set.carrierDied(1, "x")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue("carrierDied waited ${elapsedMs}ms behind a busy consumer", elapsedMs < 100)
        release.countDown()
        set.close()
    }

    @Test
    fun aSignalDroppedAtADyingCarrierIsRequeued() {
        val deliverBlocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val host = object : RecordingHost() {
            override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
                deliverBlocked.countDown()
                release.await(10, TimeUnit.SECONDS)
                super.deliver(carrierId, generation, payload)
            }
        }
        // Carrier 0 is Bluetooth with its own liveness: under the Wi-Fi default a slow host would let it die of silence.
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        val sealer = sealer()
        // Hold the consumer inside a delivery so the machine cannot consume the death below. Without the hold, a delay of
        // this thread lets the consumer move sending to carrier 0 first, and the write is then simply sent there
        // (aSignalSentAfterTheDeathWasConsumedGoesToTheLiveCarrier).
        pair0.b.link.writer.write(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.MEDIA_PACKET, 0, ByteArray(8)))
        pair0.b.link.writer.flush()
        set.onInbound(0, 0, pair0.a.link.reader.read())
        assertTrue("the consumer is held", deliverBlocked.await(3, TimeUnit.SECONDS))
        // The death is marked now, but the machine has not consumed it yet: a write still routes to carrier 1.
        pair1.cut()
        set.carrierDied(1, "test")
        assertEquals(1, set.sendCarrier)
        val cookie = "signal"
        assertEquals("the write was dropped", 0, set.withCookie(cookie) { packet(set, sealer, 1) })
        assertTrue(host.awaitUntil(3_000) { host.requeued.isNotEmpty() })
        assertEquals(listOf(cookie), host.requeued.first() as List<*>)
        release.countDown()
        set.close()
    }

    @Test
    fun aSignalSentAfterTheDeathWasConsumedGoesToTheLiveCarrier() {
        val host = RecordingHost()
        // Carrier 0 is Bluetooth with its own liveness: under the Wi-Fi default a slow host would let it die of silence.
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        val sealer = sealer()
        pair1.cut()
        set.carrierDied(1, "test")
        assertTrue("the machine consumed the death", waitForSend(set, 0))
        val cookie = "signal"
        assertTrue("the write was queued on carrier 0", set.withCookie(cookie) { packet(set, sealer, 0) } > 0)
        val records = collectUntil(pair0.b, 3_000) { got -> got.any { it.type == SecureRecordType.END_TO_END_CONTENT } }
        assertEquals(listOf(0L), records.filter { it.type == SecureRecordType.END_TO_END_CONTENT }.map(::sequenceOf))
        assertTrue("a sent record is not requeued: ${host.requeued}", host.requeued.isEmpty())
        set.close()
    }

    // ------------------------------------------------------------------------------ step 5: events, liveness

    /** Records every marker the machine asks for, and still encodes it (a test reads the wire back). */
    private class RecordingMarkerCodec : CarrierMarkerCodec {
        val encoded = CopyOnWriteArrayList<CarrierCutover.Wire>()
        private val delegate = TestMarkerCodec()

        override fun encode(wire: CarrierCutover.Wire): ByteArray {
            encoded += wire
            return delegate.encode(wire)
        }

        override fun decode(payload: ByteArray): CarrierCutover.Wire? = delegate.decode(payload)
    }

    private class FakeClock(var nowNs: Long = 1_000_000_000L) {
        fun nano(): Long = nowNs
    }

    private class Switched(
        val a: CarrierSet,
        val b: CarrierSet,
        val pair0: PipePair,
        val pair1: PipePair,
        val hostA: RecordingHost,
        val hostB: RecordingHost,
    )

    /** Two calls over two carriers, already switched to carrier 1, both reading carrier 0 and 1. */
    private fun switched(
        wifiLiveness: CarrierLivenessPolicy = CarrierLivenessPolicy.WIFI,
        nano: () -> Long = System::nanoTime,
        codecA: CarrierMarkerCodec = TestMarkerCodec(),
        hostA: RecordingHost = RecordingHost(),
        /** false: A's Wi-Fi carrier leaves its pipe open when it is closed (a write in progress still completes). */
        wifiCloseCutsPipe: Boolean = true,
    ): Switched {
        val hostB = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE, liveness = wifiLiveness)
        val a = newSet(hostA, bluetoothQueue = 64, nano = nano, markers = codecA)
        val b = receiver("CS-sw-b-" + System.nanoTime(), pair0, hostB, nano = nano)
        a.attachPrimary(pair0.a, 0)
        thread(isDaemon = true) {
            try {
                while (!a.ended) a.onInbound(0, 0, pair0.a.link.reader.read())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!a.ended && a.isLive(0)) a.carrierDied(0, "read failed: ${error.javaClass.simpleName}")
            }
        }
        val wifiA = if (wifiCloseCutsPipe) pair1.a else PipeCarrier(pair1.a.kind, pair1.a.label, pair1.a.link, pair1.a.liveness)
        assertEquals(1, a.addCarrier(wifiA))
        assertEquals(1, b.addCarrier(pair1.b))
        b.carrierPending(1)
        b.carrierUp(1)
        a.carrierPending(1)
        a.carrierUp(1)
        a.onCallActive()
        assertTrue("the switch to carrier 1 happened", waitForSend(a, 1))
        return Switched(a, b, pair0, pair1, hostA, hostB)
    }

    @Test
    fun carrierOneDeathFallsBackToCarrierZeroAndTheCallContinues() {
        val s = switched()
        val sealer = sealer()
        s.pair1.cut()
        assertTrue("fallback to carrier 0", waitForSend(s.a, 0))
        assertEquals(1, s.a.fallbackCount)
        assertTrue(
            "the sender is told to produce a keyframe",
            s.hostA.awaitUntil(2_000) { s.hostA.actions.any { it is CarrierCutover.Action.ForceLocalKeyframe } },
        )
        assertTrue(s.hostA.endSessions.isEmpty())
        repeat(3) { packet(s.a, sealer, 100L + it) }
        assertTrue(s.hostB.await(3, 5_000))
        assertEquals((0L..2L).toList(), s.hostB.sequences())
        s.a.close()
        s.b.close()
    }

    @Test
    fun bothCarriersDyingEndsTheSessionExactlyOnce() {
        val s = switched()
        s.pair1.cut()
        s.pair0.cut()
        assertTrue(s.hostA.awaitEnds(1, 5_000))
        assertTrue(s.hostB.awaitEnds(1, 5_000))
        Thread.sleep(300)
        assertEquals(1, s.hostA.endSessions.size)
        assertEquals(1, s.hostB.endSessions.size)
        assertTrue("nothing is left", s.a.carrierDied(0, "again"))
        Thread.sleep(100)
        assertEquals(1, s.hostA.endSessions.size)
        s.a.close()
        s.b.close()
    }

    @Test
    fun carrierZeroDeathWhileCarrierOneSendsKeepsTheCall() {
        val s = switched()
        val sealer = sealer()
        s.pair0.cut()
        Thread.sleep(300)
        assertEquals(1, s.a.sendCarrier)
        assertEquals(1, s.a.liveCount())
        assertTrue(s.hostA.endSessions.isEmpty())
        repeat(3) { packet(s.a, sealer, 200L + it) }
        assertTrue(
            "delivered=${s.hostB.delivered} accept=${s.b.acceptCarrier}",
            s.hostB.awaitUntil(3_000) { s.hostB.delivered.size >= 3 },
        )
        s.a.close()
        s.b.close()
    }

    @Test
    fun standbyReplacedKeepsTheWifiCarrierAndReassertsTheMarker() {
        val codec = RecordingMarkerCodec()
        val s = switched(codecA = codec)
        val sealer = sealer()
        val replacement = PipePair(CarrierKind.BLUETOOTH)
        s.a.replacePrimary(replacement.a, 1)
        s.b.replacePrimary(replacement.b, 1)
        assertTrue(s.hostA.endSessions.isEmpty())
        assertTrue(s.hostB.endSessions.isEmpty())
        val switches = codec.encoded.filterIsInstance<CarrierCutover.Wire.Switch>()
        assertTrue("a marker was re-asserted (${codec.encoded})", switches.size >= 2)
        assertEquals(switches.first().gen, switches.last().gen)
        assertEquals(switches.first().boundary, switches.last().boundary)
        assertEquals(1, switches.last().carrier)
        val reassorted = collectUntil(replacement.b, 3_000) { records ->
            records.any { it.type == SecureRecordType.BWF1 }
        }
        val marker = reassorted.filter { it.type == SecureRecordType.BWF1 }
            .mapNotNull { TestMarkerCodec().wasSwitch(it.payload) }
            .firstOrNull()
        assertEquals(switches.last().gen, marker?.gen)
        assertEquals(switches.last().boundary, marker?.boundary)
        // Media still flows on carrier 1.
        repeat(3) { packet(s.a, sealer, 300L + it) }
        assertTrue(
            "delivered=${s.hostB.delivered} send=${s.a.sendCarrier} accept=${s.b.acceptCarrier}",
            s.hostB.awaitUntil(3_000) { s.hostB.delivered.size >= 3 },
        )
        s.a.close()
        s.b.close()
    }

    @Test
    fun switchOffWithAStandbyFallsBackAndClosesTheWifiCarrier() {
        val s = switched()
        assertNull("a usable standby takes the call back: no D6 notice", WifiCallNotices.afterSwitchOff(s.a.switchOff()))
        assertTrue("the switch-off fell back to carrier 0", waitForSend(s.a, 0))
        assertTrue(s.a.switchedOff)
        assertFalse(s.a.isLive(1))
        assertTrue(s.hostA.endSessions.isEmpty())
        assertTrue(
            s.hostA.awaitUntil(3_000) {
                s.hostA.carrierEvents.any { it.a == CarrierEvent.ENDED && it.b == 1 && it.d.contains("SWITCH_OFF") }
            },
        )
        assertEquals(-1, s.a.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        s.a.close()
        s.b.close()
    }

    @Test
    fun switchOffWithWifiAsTheOnlyCarrierKeepsSending() {
        val s = switched()
        s.pair0.cut()
        Thread.sleep(300)
        assertEquals(1, s.a.sendCarrier)
        assertEquals(WifiCallNotices.SWITCH_OFF_WIFI_ONLY, WifiCallNotices.afterSwitchOff(s.a.switchOff()))
        assertFalse("a second switch-off shows the notice only once", s.a.switchOff())
        Thread.sleep(300)
        assertEquals("a switch-off never closes the last carrier", 1, s.a.sendCarrier)
        assertTrue(s.a.hasWifiCarrier())
        assertEquals(-1, s.a.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        assertTrue(s.hostA.endSessions.isEmpty())
        s.a.close()
        s.b.close()
    }

    @Test
    fun aSilentWifiCarrierDiesAfterItsPolicy() {
        val fast = CarrierLivenessPolicy(200_000_000L, 600_000_000L, 2_500_000_000L, 0L)
        val s = switched(wifiLiveness = fast)
        // Its peer's writer keeps it alive while it idles (heartbeats every 200 ms).
        Thread.sleep(1_500)
        assertEquals(1, s.a.sendCarrier)
        s.pair1.cut()
        assertTrue("a silent Wi-Fi carrier dies by its own policy", waitForSend(s.a, 0, 3_000))
        assertTrue(s.hostA.endSessions.isEmpty())
        s.a.close()
        s.b.close()
    }

    @Test
    fun aStalledWifiWriteKillsOnlyThatCarrier() {
        val fast = CarrierLivenessPolicy(500_000_000L, 2_500_000_000L, 500_000_000L, 0L)
        val s = switched(wifiLiveness = fast)
        s.pair1.blockWritesFromA(true)
        // Enough media to keep a write in progress.
        val sealer = sealer()
        val producer = thread {
            runCatching { repeat(40) { packet(s.a, sealer, 400L + it, size = 4_096) } }
        }
        assertTrue("the stalled carrier falls back", waitForSend(s.a, 0, 4_000))
        assertTrue(s.hostA.endSessions.isEmpty())
        s.pair1.blockWritesFromA(false)
        producer.join(2_000)
        s.a.close()
        s.b.close()
    }

    @Test
    fun aWriteThatJustFinishedIsNeverJudgedStalledHoweverLateTheClockIs() {
        val wifi = CarrierLivenessPolicy.WIFI
        val uptimeNs = 3_600_000_000_000L
        // Write-start reset to 0 (no write in progress) with a huge clock: healthy.
        assertNull(CarrierSet.livenessVerdict(uptimeNs, uptimeNs - 10_000_000L, 0L, wifi))
        // A write that began a moment ago: healthy. One that has lasted the bound: stalled.
        assertNull(CarrierSet.livenessVerdict(uptimeNs, uptimeNs - 10_000_000L, uptimeNs - 100_000_000L, wifi))
        assertEquals(
            "write stalled",
            CarrierSet.livenessVerdict(uptimeNs, uptimeNs - 10_000_000L, uptimeNs - wifi.deadAfterStalledWriteNs, wifi),
        )
        // Silence is unchanged, and it wins when both hold.
        assertEquals("silence", CarrierSet.livenessVerdict(uptimeNs, uptimeNs - wifi.deadAfterSilenceNs, 0L, wifi))
        assertEquals("silence", CarrierSet.livenessVerdict(uptimeNs, 0L, uptimeNs - wifi.deadAfterStalledWriteNs, wifi))
        // A Bluetooth policy has no verdict from this rule.
        assertNull(CarrierSet.livenessVerdict(uptimeNs, 0L, 1L, CarrierLivenessPolicy.BLUETOOTH))
    }

    @Test
    fun aWifiCarrierDeathLogsItsReason() {
        org.junit.Assume.assumeTrue(BuildConfig.DEBUG)
        val fast = CarrierLivenessPolicy(200_000_000L, 600_000_000L, 2_500_000_000L, 0L)
        val s = switched(wifiLiveness = fast)
        Thread.sleep(1_000)
        s.pair1.cut()
        assertTrue("falls back", waitForSend(s.a, 0, 3_000))
        assertTrue(s.hostA.awaitUntil(3_000) { s.hostA.logs.any { it.startsWith("BENCHMARK carrier_death: carrier=WIFI_AWARE | reason=") } })
        val line = s.hostA.logs.firstOrNull { it.startsWith("BENCHMARK carrier_death: carrier=WIFI_AWARE | reason=") }
        assertTrue("the death reason is logged: ${s.hostA.logs}", line != null)
        assertTrue(line!!.contains(" | ageMs="))
        s.a.close()
        s.b.close()
    }

    @Test
    fun aHealthyWifiCarrierWritingSmallRecordsIsNeverDeclaredDead() {
        // A phone's uptime clock is far past the stall bound; a write that just finished must not look like one that started
        // at time 0. The writer finishes tiny writes in a tight loop while the peer's records keep arriving.
        val s = switched(nano = { System.nanoTime() + 10_000_000_000L })
        val sealer = sealer()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val producer = thread(isDaemon = true) {
            var pts = 0L
            while (!stop.get()) packet(s.a, sealer, pts++, size = 16)
        }
        val peer = thread(isDaemon = true) {
            var pts = 0L
            while (!stop.get()) {
                packet(s.b, sealer(), pts++, size = 16)
                Thread.sleep(20)
            }
        }
        Thread.sleep(6_000)
        stop.set(true)
        producer.join(2_000)
        peer.join(2_000)
        val ended = s.hostA.carrierEvents.filter { it.a == CarrierEvent.ENDED }
        assertTrue("a healthy Wi-Fi carrier was declared dead: ${ended.map { it.d }}", ended.isEmpty())
        assertEquals(1, s.a.sendCarrier)
        assertTrue(s.hostA.endSessions.isEmpty())
        s.a.close()
        s.b.close()
    }

    @Test
    fun teardownIsIdempotentFromEveryThread() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val prefix = "CS-tear-" + System.nanoTime()
        val set = newSet(prefix = prefix, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        pair0.blockWritesFromA(true)
        val sealer = sealer()
        thread { runCatching { repeat(40) { packet(set, sealer, it.toLong(), size = 4_096) } } }
        val failures = CopyOnWriteArrayList<Throwable>()
        val closers = (0 until 3).map {
            thread {
                runCatching { set.close() }.onFailure(failures::add)
            }
        }
        closers.forEach { it.join(5_000) }
        assertTrue("no close threw: $failures", failures.isEmpty())
        Thread.sleep(300)
        assertTrue("every set thread ends: ${noThreadsWithPrefix(prefix)}", noThreadsWithPrefix(prefix).isEmpty())
        set.close()
    }

    @Test
    fun qualityFallbackCountsLikeAnyFallback() {
        val s = switched()
        val second = PipePair(CarrierKind.WIFI_AWARE)
        s.a.qualityFallback()
        assertTrue("the first quality fallback returned to carrier 0", waitForSend(s.a, 0))
        assertEquals(1, s.a.fallbackCount)
        assertEquals(2, s.a.addCarrier(second.a))
        s.a.carrierPending(2)
        s.a.carrierUp(2)
        assertTrue(waitForSend(s.a, 2))
        s.a.qualityFallback()
        assertTrue(waitForSend(s.a, 0))
        assertEquals(2, s.a.fallbackCount)
        // The machine refuses a third switch: the hop has used its two fallbacks.
        val third = PipePair(CarrierKind.WIFI_AWARE)
        assertEquals(3, s.a.addCarrier(third.a))
        s.a.carrierPending(3)
        s.a.carrierUp(3)
        Thread.sleep(400)
        assertEquals(0, s.a.sendCarrier)
        assertEquals(2, s.a.fallbackCount)
        s.a.close()
        s.b.close()
    }

    @Test
    fun snapshotReportsSendAcceptLiveHeldAndFallbackCounts() {
        val s = switched()
        val snap = s.a.snapshot()
        assertTrue(snap.callActive)
        assertEquals(1, snap.sendCarrier)
        assertEquals(2, snap.liveCount)
        assertEquals(CarrierKind.WIFI_AWARE, snap.sendKind)
        assertTrue(snap.hasWifiCarrier)
        assertTrue(snap.bluetoothAlive)
        assertEquals(2, snap.kinds.size)
        assertEquals(2, snap.stats.size)
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        val after = s.a.snapshot()
        assertEquals(0, after.sendCarrier)
        assertEquals(1, after.fallbackCount)
        assertFalse(after.hasWifiCarrier)
        assertFalse(after.ended)
        assertFalse(after.endDeferred)
        s.a.close()
        s.b.close()
    }

    @Test
    fun carrierEventsReportAddedEndedAndPrimaryReplaced() {
        val s = switched()
        assertTrue(
            s.hostA.carrierEvents.any { it.a == CarrierEvent.ADDED && it.b == 1 && it.c == CarrierKind.WIFI_AWARE },
        )
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        assertTrue(s.hostA.awaitUntil(3_000) { s.hostA.carrierEvents.any { it.a == CarrierEvent.ENDED && it.b == 1 } })
        val replacement = PipePair(CarrierKind.BLUETOOTH)
        s.a.replacePrimary(replacement.a, 1)
        assertTrue(
            s.hostA.awaitUntil(3_000) {
                s.hostA.carrierEvents.any { it.a == CarrierEvent.PRIMARY_REPLACED && it.b == 0 }
            },
        )
        s.a.close()
        s.b.close()
    }

    @Test
    fun theAcceptCarrierChangeIsReportedOncePerChange() {
        val s = switched()
        assertTrue(
            "accepting carrier 1 is reported",
            s.hostB.awaitUntil(3_000) { s.hostB.carrierEvents.any { it.a == CarrierEvent.ACCEPT_CHANGED && it.b == 1 } },
        )
        val countAfterSwitch = s.hostB.carrierEvents.count { it.a == CarrierEvent.ACCEPT_CHANGED && it.b == 1 }
        assertEquals(1, countAfterSwitch)
        s.pair1.cut()
        assertTrue(
            s.hostB.awaitUntil(3_000) { s.hostB.carrierEvents.any { it.a == CarrierEvent.ACCEPT_CHANGED && it.b == 0 } },
        )
        s.a.close()
        s.b.close()
    }

    @Test
    fun aCarrierTheMachineIgnoresAfterASwitchOffIsClosed() {
        val host = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        val carrier = PipePair(CarrierKind.WIFI_AWARE).a
        assertEquals(1, set.addCarrier(carrier))
        set.switchOff()
        set.carrierPending(1)
        assertTrue(host.awaitUntil(3_000) { !set.isLive(1) })
        assertTrue(host.carrierEvents.any { it.a == CarrierEvent.ENDED && it.b == 1 && it.d == "ignored" })
        assertEquals(1, host.carrierEvents.count { it.a == CarrierEvent.ENDED && it.b == 1 })
        assertTrue(host.logs.contains("carrier_ignored kind=WIFI_AWARE"))
        set.close()
    }

    @Test
    fun switchOffWithAStaleStandbyWaitsUntilTheStandbyIsHeardAgain() {
        val s = switched()
        // Carrier 0 hears nothing for 3.5 s: alive in this phone's view, but stale.
        s.pair0.blockWritesFromB(true)
        Thread.sleep(3_500)
        assertTrue("Bluetooth is still alive in this phone's view", s.a.snapshot().bluetoothAlive)
        // Review A6 P2-1: the call stays on Wi-Fi, so the user is told, although Bluetooth is not dead.
        assertEquals(WifiCallNotices.SWITCH_OFF_WIFI_ONLY, WifiCallNotices.afterSwitchOff(s.a.switchOff()))
        Thread.sleep(300)
        assertEquals("Wi-Fi keeps sending while the standby is stale", 1, s.a.sendCarrier)
        assertTrue(s.a.isLive(1))
        s.pair0.blockWritesFromB(false)
        assertTrue("events=${s.hostA.carrierEvents} snap=${s.a.snapshot()}", waitForSend(s.a, 0, 3_000))
        assertTrue(s.hostA.endSessions.isEmpty())
        s.a.close()
        s.b.close()
    }

    @Test
    fun aQualityFallbackWithAStaleStandbyKeepsWifi() {
        val s = switched()
        s.pair0.blockWritesFromB(true)
        Thread.sleep(3_500)
        s.a.qualityFallback()
        Thread.sleep(400)
        assertEquals(1, s.a.sendCarrier)
        assertEquals(0, s.a.fallbackCount)
        assertTrue(s.a.hasWifiCarrier())
        s.pair0.blockWritesFromB(false)
        s.a.close()
        s.b.close()
    }

    @Test
    fun oldestQueuedAgeReportsABlockedCarriersBacklog() {
        val s = switched()
        val sealer = sealer()
        s.pair1.blockWritesFromA(true)
        repeat(10) { packet(s.a, sealer, 500L + it, size = 1_024) }
        Thread.sleep(500)
        assertTrue("a blocked carrier's backlog is visible", (s.a.stats(1)?.oldestQueuedAgeMs ?: 0L) >= 300L)
        s.pair1.blockWritesFromA(false)
        assertTrue(s.a.awaitDrained(1, 2_000))
        Thread.sleep(150)
        assertTrue((s.a.stats(1)?.oldestQueuedAgeMs ?: 0L) < 50L)
        s.a.close()
        s.b.close()
    }

    // ---------------------------------------------------------------------- step 5: renewal hold of the end

    @Test
    fun endSessionIsDeferredWhileARenewalRunsAndTheReplacementKeepsTheCall() {
        val s = switched()
        s.hostA.renewalBusy = true
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        s.pair0.cut()
        Thread.sleep(1_000)
        assertTrue(s.hostA.endSessions.isEmpty())
        assertTrue(s.a.endDeferred)
        assertFalse(s.a.carrierDied(0, "again"))
        val replacement = PipePair(CarrierKind.BLUETOOTH)
        s.a.replacePrimary(replacement.a, 1)
        assertEquals(0, s.a.sendCarrier)
        assertFalse(s.a.endDeferred)
        assertTrue(s.hostA.endSessions.isEmpty())
        s.a.close()
        s.b.close()
    }

    @Test
    fun endSessionIsAppliedWhenTheRenewalEndsWithoutReplacingCarrierZero() {
        val s = switched()
        s.hostA.renewalBusy = true
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        s.pair0.cut()
        Thread.sleep(500)
        assertTrue(s.a.endDeferred)
        s.hostA.renewalBusy = false
        assertTrue(s.hostA.awaitEnds(1, 2_000))
        assertEquals(1, s.hostA.endSessions.size)
        s.a.close()
        s.b.close()
    }

    @Test
    fun endSessionIsAppliedAtTheTwelveSecondBound() {
        val clock = FakeClock()
        val s = switched(nano = clock::nano)
        s.hostA.renewalBusy = true
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        s.pair0.cut()
        Thread.sleep(500)
        assertTrue(s.a.endDeferred)
        clock.nowNs += 12_000_000_000L
        assertTrue(s.hostA.awaitEnds(1, 3_000))
        assertEquals(1, s.hostA.endSessions.size)
        s.a.close()
        s.b.close()
    }

    @Test
    fun aSingleCarrierDeathEndsAtOnceEvenWhenARenewalIsBusy() {
        val host = RecordingHost()
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        thread(isDaemon = true) {
            runCatching {
                while (!set.ended) set.onInbound(0, 0, pair.a.link.reader.read())
            }
        }
        host.renewalBusy = true
        assertTrue(set.carrierDied(0, "test"))
        assertEquals(1, host.endSessions.size)
        set.close()
    }

    @Test
    fun closeDropsADeferredEndWithoutCallingTheHost() {
        val s = switched()
        s.hostA.renewalBusy = true
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        s.pair0.cut()
        Thread.sleep(500)
        assertTrue(s.a.endDeferred)
        s.a.close()
        Thread.sleep(200)
        assertTrue(s.hostA.endSessions.isEmpty())
        s.b.close()
    }

    @Test
    fun aHeldDeathOfTheReplacedCarrierZeroNeverEndsTheRenewedCall() {
        val s = switched()
        s.hostA.renewalBusy = true
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        s.pair0.cut()
        Thread.sleep(500)
        assertTrue(s.a.endDeferred)
        val replacement = PipePair(CarrierKind.BLUETOOTH)
        s.a.replacePrimary(replacement.a, 1)
        Thread.sleep(300)
        assertTrue(s.hostA.endSessions.isEmpty())
        assertEquals(0, s.a.sendCarrier)
        assertFalse(s.a.endDeferred)
        // The new carrier 0 delivers.
        val sealer = sealer()
        packet(s.a, sealer, 600L)
        val records = collectUntil(replacement.b, 3_000) { collected ->
            collected.count { it.type == SecureRecordType.END_TO_END_CONTENT } >= 1
        }
        assertTrue(records.any { it.type == SecureRecordType.END_TO_END_CONTENT })
        s.a.close()
        s.b.close()
    }

    @Test
    fun twoCarriersDyingTogetherDuringARenewalHoldTheEnd() {
        val s = switched()
        s.hostA.renewalBusy = true
        s.pair0.cut()
        s.pair1.cut()
        Thread.sleep(1_000)
        assertTrue(s.a.endDeferred)
        assertTrue(s.hostA.endSessions.isEmpty())
        s.a.close()
        s.b.close()
    }

    @Test
    fun addCarrierIsRefusedFromTheMomentSwitchOffStarts() {
        val host = RecordingHost()
        val pair0 = PipePair(CarrierKind.BLUETOOTH)
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        set.switchOff()
        assertTrue(set.switchedOff)
        assertEquals(-1, set.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        assertEquals(-1, set.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        set.close()
    }

    @Test
    fun aStandbyReplacementIsNotFedToAnEndedMachine() {
        val s = switched()
        s.pair1.cut()
        s.pair0.cut()
        assertTrue(s.hostA.awaitEnds(1, 5_000))
        val endEventsBefore = s.hostA.endSessions.size
        val replacement = PipePair(CarrierKind.BLUETOOTH)
        s.a.replacePrimary(replacement.a, 5)
        Thread.sleep(200)
        assertEquals(endEventsBefore, s.hostA.endSessions.size)
        assertTrue(s.a.ended)
        s.a.close()
        s.b.close()
    }

    @Test
    fun aFallbackInTheMiddleOfAFragmentedFrameLosesOnlyThatFrame() {
        val frameKey = ByteArray(32) { (it + 21).toByte() }
        val opener = EndToEndContentOpener(frameKey)
        opener.acceptVideoFragments = true
        val reassembler = CallVideoReassembler()
        val fragments = CopyOnWriteArrayList<ByteArray>()
        val frames = CopyOnWriteArrayList<ByteArray>()
        val fragmentLock = Object()
        val hostB = object : RecordingHost() {
            override fun onCutoverAction(action: CarrierCutover.Action) {
                if (action == CarrierCutover.Action.SuppressFragments) reassembler.discardUntilFrameStart()
                super.onCutoverAction(action)
            }

            override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
                val content = opener.open(payload)
                synchronized(fragmentLock) {
                    val complete = reassembler.accept(content.flags, content.payload)
                    fragments += content.payload
                    if (complete != null) frames += complete
                }
            }
        }
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val a = newSet(bluetoothQueue = 64)
        val b = receiver("CS-frag-" + System.nanoTime(), pair0, hostB)
        a.attachPrimary(pair0.a, 0)
        assertEquals(1, a.addCarrier(pair1.a))
        assertEquals(1, b.addCarrier(pair1.b))
        b.carrierPending(1)
        b.carrierUp(1)
        a.carrierPending(1)
        a.carrierUp(1)
        a.onCallActive()
        assertTrue(waitForSend(a, 1))
        val sealerB = EndToEndContentSealer(frameKey)
        val frameA = EndToEndStreamProtocol.encodePacket(0, 1L, ByteArray(12_000) { it.toByte() })
        val partsA = CallVideoFragments.split(frameA)
        assertTrue(partsA.size >= 4)
        // A frame is cut after its second fragment: the carrier dies mid-frame.
        repeat(2) { index ->
            EndToEndStreamProtocol.writeVideoFragment(a.sink, sealerB, 0, partsA[index])
        }
        a.requestFlush()
        assertTrue(hostB.awaitUntil(3_000) { fragments.size >= 2 })
        pair1.cut()
        assertTrue("send=${a.sendCarrier} snap=${a.snapshot()}", waitForSend(a, 0, 3_000))
        val frameB = EndToEndStreamProtocol.encodePacket(0, 2L, ByteArray(9_000) { (it + 3).toByte() })
        CallVideoFragments.split(frameB).forEach { fragment ->
            EndToEndStreamProtocol.writeVideoFragment(a.sink, sealerB, 0, fragment)
        }
        a.requestFlush()
        assertTrue("the frame after the fallback reassembles", hostB.awaitUntil(4_000) { frames.any { it.contentEquals(frameB) } })
        assertFalse(reassembler.inProgress)
        a.close()
        b.close()
    }

    @Test
    fun everyEntryPointThatCallsTheMachineHoldsTheCutoverLock() {
        val s = switched()
        val sealer = sealer()
        val failures = CopyOnWriteArrayList<Throwable>()
        val producers = (0 until 2).map { index ->
            thread {
                runCatching {
                    repeat(60) { packet(s.a, sealer, 1_000L + index * 100L + it, size = 256) }
                }.onFailure(failures::add)
            }
        }
        val controls = thread {
            runCatching {
                s.a.qualityFallback()
                s.pair0.blockWritesFromB(true)
                Thread.sleep(50)
                s.pair0.blockWritesFromB(false)
            }.onFailure(failures::add)
        }
        producers.forEach { it.join(10_000) }
        controls.join(10_000)
        assertTrue("no entry point raced the machine into an exception: $failures", failures.isEmpty())
        // Marker generations never go backwards.
        assertTrue(s.a.snapshot().fallbackCount <= 2)
        s.a.close()
        s.b.close()
    }

    // ------------------------------------------------------------------------------ step 7: carrier 0 attached

    @Test
    fun inlineModeMatchesAPlainWriterForTheRealCallSequence() {
        val host = RecordingHost()
        val pair = PipePair()
        val set = newSet(host)
        set.attachPrimary(pair.a, 0)
        val key = ByteArray(32) { (it + 5).toByte() }
        val settings = VideoSettings(VideoResolution(854, 480, "480p"), fps = 30, bitRate = 200_000, latencyMs = 500)
        val signal = byteArrayOf(9, 8, 7, 6)
        val sealer = EndToEndContentSealer(key)
        EndToEndStreamProtocol.writeHeader(set.sink, sealer, StreamProtocol.CODEC_H264, settings, 0, includeAudio = true)
        repeat(20) { index ->
            EndToEndStreamProtocol.writePacket(set.sink, sealer, 0, index.toLong(), ByteArray(48) { (it + index).toByte() })
        }
        set.sink.write(SecureRecordType.END_TO_END_CONTENT, sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, signal))
        EndToEndStreamProtocol.writeKeyFrameRequest(set.sink, sealer)
        assertTrue(set.sendHop(0, SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1)))
        set.requestFlush()

        val direct = PipePair()
        val directSealer = EndToEndContentSealer(key)
        EndToEndStreamProtocol.writeHeader(direct.a.link.writer, directSealer, StreamProtocol.CODEC_H264, settings, 0, includeAudio = true)
        repeat(20) { index ->
            EndToEndStreamProtocol.writePacket(direct.a.link.writer, directSealer, 0, index.toLong(), ByteArray(48) { (it + index).toByte() })
        }
        direct.a.link.writer.write(SecureRecordType.END_TO_END_CONTENT, directSealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, signal))
        EndToEndStreamProtocol.writeKeyFrameRequest(direct.a.link.writer, directSealer)
        direct.a.link.writer.write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
        direct.a.link.writer.flush()

        val throughSet = collectUntil(pair.b, 5_000) { it.size >= 24 }
        val writtenDirectly = collectUntil(direct.b, 5_000) { it.size >= 24 }
        assertEquals(24, throughSet.size)
        assertEquals(24, writtenDirectly.size)
        throughSet.zip(writtenDirectly).forEach { (a, b) ->
            assertEquals(a.type, b.type)
            assertTrue("byte-identical record", a.payload.contentEquals(b.payload))
        }
        assertFalse(set.fanout)
        set.close()
    }

    @Test
    fun onWrittenReportsTheWritingCarrier() {
        val s = switched()
        val reported = CopyOnWriteArrayList<Int>()
        val written = CountDownLatch(1)
        s.a.withCookie(null, { id, _ ->
            reported += id
            written.countDown()
        }) {
            packet(s.a, sealer(), 0)
        }
        assertTrue("the carrier writer reported the write", written.await(3, TimeUnit.SECONDS))
        assertEquals(listOf(1), reported.toList())
        s.a.close()
        s.b.close()
    }

    @Test
    fun anInlineCallCountsEveryWrittenRecordForItsDeliveryMeasurement() {
        // Review A3 P0-1: a Bluetooth-only call (no second carrier) feeds the v3 delivery estimate exactly as before carriers.
        val pair = PipePair()
        val set = newSet()
        set.attachPrimary(pair.a, 0)
        val sealer = sealer()
        val estimate = PeerDeliveryEstimate()
        val reported = CopyOnWriteArrayList<Int>()
        repeat(10) { index ->
            set.withCookie(null, { id, ns ->
                reported += id
                estimate.onSent(100, ns)
            }) {
                packet(set, sealer, index.toLong(), size = 100)
            }
            assertEquals("record $index is counted once, before its write call returns", index + 1, reported.size)
        }
        assertEquals("every record was written on carrier 0", List(10) { 0 }, reported.toList())
        // The peer reports that every counted byte arrived: the queue in front of the media is measurable (-1 when the
        // writes were never counted, which sent the rate controller back to the backlog-only rules).
        assertTrue(estimate.onReport(LinkDelivery(1_000L, 5_000L)))
        assertTrue("queue=${estimate.queueMs()}", estimate.queueMs() >= 0)
        // A write outside withCookie reports nothing.
        packet(set, sealer, 99)
        assertEquals(10, reported.size)
        assertFalse(set.fanout)
        set.close()
    }

    /** The call's carrier-0 view (heard, writes progressed) and its stall rule, on a clock the test can move forward. */
    private class StallRuleHost : RecordingHost() {
        @Volatile var offsetNs = 0L
        @Volatile var lastHeard0 = clock()
        @Volatile var lastProgress0 = clock()

        fun clock(): Long = System.nanoTime() + offsetNs

        override fun heard(carrierId: Int, generation: Int): Boolean {
            if (carrierId == 0) lastHeard0 = clock()
            return true
        }

        override fun onProgress(carrierId: Int, nowNs: Long) {
            if (carrierId == 0) lastProgress0 = clock()
            super.onProgress(carrierId, nowNs)
        }

        override fun bluetoothSilent(carrierId: Int): Boolean =
            CarrierLossPolicy.bluetoothSilent(false, clock(), lastHeard0, lastProgress0, renewalOwnsLiveness = false)
    }

    @Test
    fun aSilentBluetoothStandbyIsDeclaredDeadWhileWifiCarriesTheCall() {
        // Review A3 P1-2: the standby stops answering without a socket error; the monitor ends carrier 0, Wi-Fi goes on.
        val host = StallRuleHost()
        val s = switched(hostA = host)
        Thread.sleep(300)
        assertTrue("carrier 0 is alive while it answers", s.a.isLive(0))
        s.pair0.blockWritesFromB(true)
        Thread.sleep(300)
        host.offsetNs = 9_000_000_000L
        assertTrue(
            "the standby is declared dead: ${host.carrierEvents}",
            host.awaitUntil(3_000) { host.carrierEvents.any { it.a == CarrierEvent.ENDED && it.b == 0 } },
        )
        assertFalse(s.a.isLive(0))
        assertEquals(1, s.a.sendCarrier)
        assertTrue(host.endSessions.isEmpty())
        s.pair0.blockWritesFromB(false)
        s.a.close()
        s.b.close()
    }

    @Test
    fun recordsReadBeforeACarriersEndAreDeliveredBeforeItsDeath() {
        // Review A3 P1-3: the peer writes its last records (a HANG_UP) on Wi-Fi and closes it. The receiver's reader queued
        // them before it saw the end; the death must not overtake them, or the hang-up is dropped as a closed carrier's.
        val s = switched()
        val release = CountDownLatch(1)
        val firstHeld = CountDownLatch(1)
        s.hostB.onDeliver = {
            firstHeld.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val sealer = sealer()
        repeat(20) { packet(s.a, sealer, 400L + it) }
        assertTrue("the consumer holds the first record", firstHeld.await(3, TimeUnit.SECONDS))
        assertTrue(s.a.awaitDrained(1, 3_000))
        Thread.sleep(200) // the receiver's reader has read them: they wait in its queue behind the held record
        val before = "readB=${s.b.stats(1)?.recordsRead} wroteA=${s.a.stats(1)?.recordsWritten} send=${s.a.sendCarrier} acceptB=${s.b.acceptCarrier} held=${s.b.heldCount}"
        s.pair1.cut()
        // The receiver's reader now reads the end of the carrier.
        assertTrue(s.hostB.awaitUntil(3_000) { s.hostB.carrierEvents.any { it.a == CarrierEvent.ENDED && it.b == 1 } })
        s.hostB.onDeliver = null
        release.countDown()
        assertTrue(
            "$before delivered=${s.hostB.delivered} logs=${s.hostB.logs} events=${s.hostB.carrierEvents}",
            s.hostB.awaitUntil(3_000) { s.hostB.delivered.count { it.first == 1 } >= 20 },
        )
        assertEquals((0L..19L).toList(), s.hostB.delivered.filter { it.first == 1 }.map { it.second })
        assertFalse(s.hostB.logs.any { it.startsWith("carrier_drop") })
        s.a.close()
        s.b.close()
    }

    @Test
    fun aSignalIsNeverStrandedInADyingCarriersQueue() {
        // Review A3 P2-2: every record with a cookie is either written (onWritten) or handed back (requeueUndelivered),
        // however its enqueue interleaves with the carrier's death.
        repeat(5) { round ->
            val s = switched(wifiCloseCutsPipe = false)
            val written = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val sealer = sealer()
            val producer = thread {
                for (i in 0 until 400) {
                    s.a.withCookie(i, { _, _ -> written += i }) { packet(s.a, sealer, i.toLong()) }
                }
            }
            Thread.sleep(5L + round * 3)
            s.a.carrierDied(1, "test")
            producer.join(10_000)
            assertTrue(s.a.awaitDrained(0, 3_000))
            Thread.sleep(100)
            val requeued = s.hostA.requeued.flatMap { it as List<*> }.toSet()
            val lost = (0 until 400).filter { it !in written && it !in requeued }
            assertTrue("round $round: lost cookies $lost", lost.isEmpty())
            s.a.close()
            s.b.close()
        }
    }

    @Test
    fun aRecordSealedOutOfOrderIsStillWrittenOnTheSendCarrier() {
        // Review A3 P2-3: a second sealer thread (the liveness hang-up helper) can hand its record over late; the set routes
        // it on the current carrier instead of dropping it. Inline mode writes both, in write order, as before carriers.
        val pair = PipePair()
        val set = newSet()
        set.attachPrimary(pair.a, 0)
        val sealer = sealer()
        val first = sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, byteArrayOf(1))
        val second = sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, byteArrayOf(2))
        assertTrue(set.sink.write(SecureRecordType.END_TO_END_CONTENT, second) > 0)
        assertTrue("the late record is written", set.sink.write(SecureRecordType.END_TO_END_CONTENT, first) > 0)
        set.requestFlush()
        val records = collectUntil(pair.b, 3_000) { it.size >= 2 }
        assertEquals(listOf(1L, 0L), records.map { sequenceOf(it) })
        set.close()
    }

    /** A codec whose decoder fails with something other than IllegalArgumentException (a future codec's bug). */
    private class BrokenDecoder : CarrierMarkerCodec {
        override fun encode(wire: CarrierCutover.Wire): ByteArray = TestMarkerCodec().encode(wire)

        override fun decode(payload: ByteArray): CarrierCutover.Wire? =
            if (payload.first() == 'M'.code.toByte()) throw IllegalStateException("codec bug") else null
    }

    @Test
    fun aWifiCodecOrHandlerFailureNeverEndsTheBluetoothCall() {
        // Review A3 P2-4 (X5): a Wi-Fi decode or handler error on carrier 0 ends Wi-Fi for the call, never the call.
        val hostB = object : RecordingHost() {
            override fun onWifiRecord(carrierId: Int, payload: ByteArray) {
                super.onWifiRecord(carrierId, payload)
                if (payload.first() == 'X'.code.toByte()) throw NullPointerException("handler bug")
            }
        }
        val pair0 = PipePair()
        val b = receiver("CS-x5-" + System.nanoTime(), pair0, hostB, markers = BrokenDecoder())
        pair0.a.link.writer.write(SecureRecordType.BWF1, byteArrayOf('M'.code.toByte(), 1))
        pair0.a.link.writer.write(SecureRecordType.BWF1, byteArrayOf('X'.code.toByte(), 2))
        val sealer = sealer()
        EndToEndStreamProtocol.writePacket(pair0.a.link.writer, sealer, 0, 0L, ByteArray(16))
        pair0.a.link.writer.flush()
        assertTrue("the record after both failures arrives", hostB.await(1, 3_000))
        assertEquals("the undecodable marker went to the attempt owner", 2, hostB.wifiRecords.size)
        assertTrue(hostB.endSessions.isEmpty())
        assertTrue(b.isLive(0))
        assertTrue("Wi-Fi ends for this call", b.switchedOff)
        assertTrue(hostB.logs.any { it.startsWith("wifi_record_failed") && it.contains("NullPointerException") })
        b.close()
    }

    @Test
    fun anInlineRenewalLeavesTheReassertToTheCallAndWritesOnTheNewLink() {
        // Review A3 P1-1: a Bluetooth-only renewal re-asserts mute, camera and labels once (the call does it), never twice.
        val host = RecordingHost()
        val old = PipePair()
        val set = newSet(host)
        set.attachPrimary(old.a, 0)
        val renewed = PipePair()
        assertFalse("the call re-asserts itself", set.replacePrimary(renewed.a, 1))
        Thread.sleep(100)
        assertTrue("the machine asked for nothing: ${host.actions}", host.actions.isEmpty())
        val sealer = sealer()
        repeat(3) { packet(set, sealer, it.toLong()) }
        set.requestFlush()
        assertEquals(3, collectUntil(renewed.b, 3_000) { it.size >= 3 }.size)
        assertFalse(set.fanout)
        set.close()
    }

    @Test
    fun aFanOutRenewalReassertsTheStateExactlyOnce() {
        val s = switched()
        s.hostA.actions.clear()
        assertTrue("the machine re-asserts behind its marker", s.a.replacePrimary(PipePair(CarrierKind.BLUETOOTH).a, 1))
        assertTrue(s.hostA.awaitUntil(2_000) { s.hostA.actions.contains(CarrierCutover.Action.ReassertState) })
        Thread.sleep(200)
        assertEquals(1, s.hostA.actions.count { it == CarrierCutover.Action.ReassertState })
        s.a.close()
        s.b.close()
    }

    // --------------------------------------------------------------------- step 10: teardown, listeners, API

    @Test
    fun closeReleasesEveryCarrierAndRequeuesNothing() {
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val closes0 = java.util.concurrent.atomic.AtomicInteger()
        val closes1 = java.util.concurrent.atomic.AtomicInteger()
        val carrier0 = SessionCarrier(
            CarrierKind.BLUETOOTH,
            "BLE GATT",
            pair0.a.link,
            CarrierLivenessPolicy.BLUETOOTH,
            closesLink = false,
        ) { closes0.incrementAndGet() }
        val carrier1 = SessionCarrier(
            CarrierKind.WIFI_AWARE,
            "Wi-Fi Aware",
            pair1.a.link,
            CarrierLivenessPolicy.WIFI,
            closesLink = false,
        ) { closes1.incrementAndGet() }
        val prefix = "CS-teardown-" + System.nanoTime()
        val host = RecordingHost()
        val set = newSet(host, prefix, bluetoothQueue = 64)
        set.attachPrimary(carrier0, 0)
        assertEquals(1, set.addCarrier(carrier1))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        set.close()
        assertEquals(1, closes0.get())
        assertEquals(1, closes1.get())
        assertTrue("close never requeues", host.requeued.isEmpty())
        assertTrue("close never ends the call through the host", host.endSessions.isEmpty())
        Thread.sleep(200)
        assertTrue("every set thread ends: ${noThreadsWithPrefix(prefix)}", noThreadsWithPrefix(prefix).isEmpty())
        set.close()
        assertEquals(1, closes0.get())
    }

    @Test
    fun hostCallbacksNeverRunUnderTheCutoverLock() {
        val delegate = RecordingHost()
        val results = CopyOnWriteArrayList<Boolean>()
        var setRef: CarrierSet? = null
        fun probe() {
            val s = setRef ?: return
            val done = CountDownLatch(1)
            thread(isDaemon = true) {
                s.carrierDied(99, "probe")
                s.liveCount()
                done.countDown()
            }
            results += done.await(200, TimeUnit.MILLISECONDS)
        }
        val host = object : CarrierSet.Host by delegate {
            override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
                delegate.deliver(carrierId, generation, payload)
                probe()
            }

            override fun onProgress(carrierId: Int, nowNs: Long) {
                delegate.onProgress(carrierId, nowNs)
                probe()
            }

            override fun onSendCarrierChanged(sendOn: Int, kind: CarrierKind?, reason: String) {
                delegate.onSendCarrierChanged(sendOn, kind, reason)
                probe()
            }

            override fun onCarrierEvent(event: CarrierEvent, carrierId: Int, kind: CarrierKind?, reason: String) {
                delegate.onCarrierEvent(event, carrierId, kind, reason)
                probe()
            }

            override fun onEndSession(reason: String) {
                delegate.onEndSession(reason)
                probe()
            }

            override fun log(line: String) {
                delegate.log(line)
                probe()
            }
        }
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(host, bluetoothQueue = 64)
        setRef = set
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.carrierUp(1)
        set.onCallActive()
        assertTrue(waitForSend(set, 1))
        val sealer = sealer()
        assertTrue(packet(set, sealer, 0) > 0)
        pair1.cut()
        assertTrue(waitForSend(set, 0))
        set.close()
        assertTrue("callbacks ran ($results)", results.size >= 6)
        assertTrue("no host callback ran under the cutover lock: $results", results.all { it })
    }

    @Test
    fun aThrowingCarrierListenerDoesNotStopTheOthers() {
        val failures = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<String>()
        val listeners = CarrierListeners()
        listeners.add(CarrierListener { throw IllegalStateException("boom") })
        listeners.add(CarrierListener { received += it.reason })
        val change = CarrierChange(CarrierEvent.ADDED, 1, CarrierKind.WIFI_AWARE, "added", CarrierSnapshot.EMPTY)
        listeners.notify(change) { failures += it }
        assertEquals(listOf("added"), received.toList())
        assertEquals(listOf("IllegalStateException"), failures.toList())
    }

    @Test
    fun theSendCarrierChangeIsReportedOncePerChange() {
        val s = switched()
        s.pair1.cut()
        assertTrue(waitForSend(s.a, 0))
        val second = PipePair(CarrierKind.WIFI_AWARE)
        // The peer takes the new carrier too (once it has seen carrier 1 end): its heartbeats keep carrier 2 alive under
        // the Wi-Fi silence rule.
        val peerSawTheEnd = System.nanoTime() + 10_000_000_000L
        while (s.b.isLive(1) && System.nanoTime() < peerSawTheEnd) Thread.sleep(5)
        assertFalse("the peer saw carrier 1 end", s.b.isLive(1))
        assertEquals(2, s.b.addCarrier(second.b))
        s.b.carrierPending(2)
        s.b.carrierUp(2)
        assertEquals(2, s.a.addCarrier(second.a))
        s.a.carrierPending(2)
        s.a.carrierUp(2)
        assertTrue(waitForSend(s.a, 2))
        // sendCarrier changes under the cutover lock; the host hears of it after the deciding thread (here the monitor, once
        // the fallback cooldown ends) has left the lock. Wait for the report itself, then for a few more monitor ticks, which
        // must not report the same carrier again.
        assertTrue(s.hostA.awaitUntil(3_000) { s.hostA.sendCarrierChanges.size >= 4 })
        Thread.sleep(300)
        assertEquals(listOf(0, 1, 0, 2), s.hostA.sendCarrierChanges.map { it.first })
        assertEquals("attach", s.hostA.sendCarrierChanges.first().third)
        assertEquals(listOf("switch", "switch", "switch"), s.hostA.sendCarrierChanges.drop(1).map { it.third })
        s.a.close()
        s.b.close()
    }

    @Test
    fun aChangeDecidedOnAnotherThreadIsNeverReportedAfterALaterOne() {
        val testThread = Thread.currentThread()
        val consumerHeld = CountDownLatch(1)
        val release = CountDownLatch(1)
        val host = object : RecordingHost() {
            override fun onCutoverAction(action: CarrierCutover.Action) {
                // The consumer switches to carrier 1 and runs its deferred list after the lock: the ReassertState callback
                // comes before its send-carrier report. Hold it there, between the decision and the report.
                if (action == CarrierCutover.Action.ReassertState && Thread.currentThread() !== testThread && consumerHeld.count > 0) {
                    consumerHeld.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                super.onCutoverAction(action)
            }
        }
        val pair0 = PipePair(CarrierKind.BLUETOOTH, liveness = CarrierLivenessPolicy.BLUETOOTH)
        val pair1 = PipePair(CarrierKind.WIFI_AWARE)
        val set = newSet(host, bluetoothQueue = 64)
        set.attachPrimary(pair0.a, 0)
        assertEquals(1, set.addCarrier(pair1.a))
        set.carrierPending(1)
        set.onCallActive()
        set.carrierUp(1) // consumed by the consumer thread, which makes the switch
        assertTrue("the consumer switched and is held before its report", consumerHeld.await(3, TimeUnit.SECONDS))
        assertEquals(1, set.sendCarrier)
        // A later change on this thread: the quality rule moves sending back to carrier 0.
        set.qualityFallback()
        assertEquals(0, set.sendCarrier)
        release.countDown()
        assertTrue(host.awaitUntil(3_000) { host.sendCarrierChanges.size >= 3 })
        Thread.sleep(200)
        assertEquals("reports in the order of the changes", listOf(0, 1, 0), host.sendCarrierChanges.map { it.first })
        assertEquals("the host's last report is where sending is", set.sendCarrier, host.sendCarrierChanges.last().first)
        set.close()
    }

    private fun switchNaming(carrier: Int) =
        SecureRecord(SecureRecordType.BWF1, TestMarkerCodec().encode(CarrierCutover.Wire.Switch(1, 0, carrier)))

    @Test
    fun aSwitchThatBeatsTheLocalOfferLogsOneBurnedLineAndALaterSwitchDoesNot() {
        org.junit.Assume.assumeTrue(BuildConfig.DEBUG)
        val host = RecordingHost()
        val set = newSet(host)
        set.attachPrimary(PipePair().a, 0)
        // The peer's SWITCH arrives on the old link before this side added or offered carrier 1.
        set.onInbound(0, 0, switchNaming(1))
        val burned = host.logs.filter { it.startsWith("BENCHMARK switch_burned: ") }
        assertEquals(host.logs.toString(), 1, burned.size)
        assertTrue(burned[0], burned[0].contains("hop=unlabelled"))
        assertTrue(burned[0], burned[0].contains("carrier=BLUETOOTH"))
        assertTrue(burned[0], burned[0].contains("sinceOfferMs=none"))
        assertTrue(burned[0], burned[0].contains("count=1"))
        set.close()
    }

    @Test
    fun aSwitchAfterTheLocalOfferIsNotBurnedAndLogsNothing() {
        org.junit.Assume.assumeTrue(BuildConfig.DEBUG)
        val host = RecordingHost()
        val set = newSet(host)
        set.attachPrimary(PipePair().a, 0)
        assertEquals(1, set.addCarrier(PipePair(CarrierKind.WIFI_AWARE).a))
        set.carrierPending(1)
        set.onInbound(0, 0, switchNaming(1))
        assertTrue(host.logs.toString(), host.logs.none { it.startsWith("BENCHMARK switch_burned") })
        set.close()
    }
}
