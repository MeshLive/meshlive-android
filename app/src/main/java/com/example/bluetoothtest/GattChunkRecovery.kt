package com.example.bluetoothtest

import android.util.Log
import java.io.IOException
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * GATT chunk sequence arithmetic modulo 2^32 (PROTOCOL "GATT byte stream"): positive when [a] comes after [b], valid while
 * the two are less than 2^31 apart. A connection would need 2^31 values (about 1 TB at 512 bytes) to reach the wrap.
 */
internal fun seqAfter(a: Int, b: Int): Int = a - b

/**
 * Experimental `GR1` lost-chunk recovery wire format (DECISIONS "Design GR" option C, PROTOCOL "Draft: lost-chunk
 * recovery"). Every message is a type-2 GATT value, which every older reader (Android, Windows, iOS) ignores.
 * The constants are part of the contract: Windows and iOS must use the same values.
 */
internal object Gr1 {
    const val TYPE: Byte = 2
    private const val G: Byte = 0x47
    private const val R: Byte = 0x52
    const val OP_HELLO = 1
    const val OP_NACK = 2
    const val OP_GONE = 3
    const val OP_HIGH = 4
    const val VERSION = 1

    /** Sender history: values sent in the last [HISTORY_MS], at most [HISTORY_VALUES] values and [HISTORY_BYTES] bytes. */
    const val HISTORY_MS = 2_000
    const val HISTORY_VALUES = 1_024
    const val HISTORY_BYTES = 262_144
    const val HELLO_REPEAT_MS = 1_000L
    const val ROUND_MS = 250L
    const val MAX_REQUESTS = 3
    const val DEADLINE_MS = 1_000L
    const val READER_NACKS_PER_SECOND = 20
    const val RESENDS_PER_SEQUENCE = 3

    /** Resends per rolling second: this share of the data bytes the link carried in the last second (the measured budget). */
    const val RESEND_BUDGET_PERCENT = 25

    /** So a quiet link can still resend one largest value (512 bytes) twice a second. */
    const val RESEND_FLOOR_BYTES_PER_SECOND = 1_024
    const val HIGH_IDLE_MS = 200L
    const val HIGH_PER_SECOND = 2
    const val MAX_OUT_OF_RANGE_NACK_RANGES = 8
    const val MAX_RANGES = 8
    const val MAX_RUN = 255
    const val TICK_MS = 50L
    private const val PREFIX_BYTES = 4

    data class SeqRange(val first: Int, val run: Int) {
        val last: Int get() = first + run - 1
        fun overlaps(otherFirst: Int, otherLast: Int): Boolean = seqAfter(last, otherFirst) >= 0 && seqAfter(otherLast, first) >= 0
    }

    sealed class Message {
        data class Hello(val historyValues: Int, val historyBytes: Int, val historyMs: Int) : Message()
        data class Nack(val ranges: List<SeqRange>) : Message()
        data class Gone(val range: SeqRange) : Message()
        data class High(val sequence: Int) : Message()
    }

    fun isGr1(value: ByteArray): Boolean = value.size >= PREFIX_BYTES && value[0] == TYPE && value[1] == G && value[2] == R

    fun hello(values: Int = HISTORY_VALUES, bytes: Int = HISTORY_BYTES, ms: Int = HISTORY_MS): ByteArray =
        header(OP_HELLO, 9).also { value ->
            value[4] = VERSION.toByte()
            putU16(value, 5, values)
            putU32(value, 7, bytes)
            putU16(value, 11, ms)
        }

    fun nack(ranges: List<SeqRange>): ByteArray {
        require(ranges.size in 1..MAX_RANGES && ranges.all { it.run in 1..MAX_RUN })
        return header(OP_NACK, 1 + 5 * ranges.size).also { value ->
            value[4] = ranges.size.toByte()
            ranges.forEachIndexed { index, range ->
                putU32(value, 5 + 5 * index, range.first)
                value[9 + 5 * index] = range.run.toByte()
            }
        }
    }

    fun gone(range: SeqRange): ByteArray {
        require(range.run in 1..MAX_RUN)
        return header(OP_GONE, 5).also { value ->
            putU32(value, 4, range.first)
            value[8] = range.run.toByte()
        }
    }

    fun high(sequence: Int): ByteArray = header(OP_HIGH, 4).also { value -> putU32(value, 4, sequence) }

    /** NACK ranges one value can carry at this ATT value limit (0 while the link has no output yet). */
    fun nackRangesFitting(valueLimit: Int): Int = if (valueLimit < 10) 0 else minOf(MAX_RANGES, (valueLimit - 5) / 5)

    /** Null for anything that is not a well-formed `GR1` message (it is then ignored, like any other type-2 value). */
    fun decode(value: ByteArray): Message? {
        if (!isGr1(value)) return null
        return when (value[3].toInt() and 0xff) {
            OP_HELLO -> {
                if (value.size != 13 || (value[4].toInt() and 0xff) != VERSION) return null
                val values = u16(value, 5)
                val bytes = u32(value, 7)
                if (values == 0 || bytes == 0L) return null
                // Clamped to this build's own limits (review 1 item 5): a larger claim cannot widen what the reader waits for.
                Message.Hello(minOf(values, HISTORY_VALUES), minOf(bytes, HISTORY_BYTES.toLong()).toInt(), minOf(u16(value, 11), HISTORY_MS))
            }
            OP_NACK -> {
                if (value.size < 5) return null
                val count = value[4].toInt() and 0xff
                if (count !in 1..MAX_RANGES || value.size != 5 + 5 * count) return null
                val ranges = (0 until count).map { index ->
                    SeqRange(u32(value, 5 + 5 * index).toInt(), value[9 + 5 * index].toInt() and 0xff)
                }
                if (ranges.any { it.run == 0 }) null else Message.Nack(ranges)
            }
            OP_GONE -> {
                if (value.size != 9) return null
                val run = value[8].toInt() and 0xff
                if (run == 0) null else Message.Gone(SeqRange(u32(value, 4).toInt(), run))
            }
            OP_HIGH -> if (value.size != 8) null else Message.High(u32(value, 4).toInt())
            else -> null
        }
    }

    private fun header(op: Int, bodyBytes: Int): ByteArray = ByteArray(PREFIX_BYTES + bodyBytes).also { value ->
        value[0] = TYPE
        value[1] = G
        value[2] = R
        value[3] = op.toByte()
    }

    private fun putU16(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 8).toByte()
        target[at + 1] = value.toByte()
    }

    private fun putU32(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }

    private fun u16(source: ByteArray, at: Int): Int = ((source[at].toInt() and 0xff) shl 8) or (source[at + 1].toInt() and 0xff)

    private fun u32(source: ByteArray, at: Int): Long =
        ((source[at].toLong() and 0xff) shl 24) or ((source[at + 1].toLong() and 0xff) shl 16) or
            ((source[at + 2].toLong() and 0xff) shl 8) or (source[at + 3].toLong() and 0xff)
}

/** Data values this side sent, byte for byte (copies, never mutated after the send), for resends. */
internal class ResendHistory(
    private val maxValues: Int = Gr1.HISTORY_VALUES,
    private val maxBytes: Int = Gr1.HISTORY_BYTES,
    private val maxAgeNs: Long = Gr1.HISTORY_MS * 1_000_000L,
) {
    class Entry(val sequence: Int, value: ByteArray, val sentNs: Long) {
        private val stored = value.copyOf()
        val size: Int get() = stored.size
        var resends = 0

        /** A fresh copy for one send: the stack or a test may write into the array it is given. */
        fun copy(): ByteArray = stored.copyOf()
    }

    private val entries = ArrayDeque<Entry>()
    var heldBytes = 0
        private set
    var lastSent: Int? = null
        private set
    val heldValues: Int get() = entries.size

    fun record(sequence: Int, value: ByteArray, nowNs: Long) {
        entries.addLast(Entry(sequence, value, nowNs))
        heldBytes += value.size
        lastSent = sequence
        while (entries.size > maxValues || heldBytes > maxBytes) dropOldest()
        prune(nowNs)
    }

    fun find(sequence: Int, nowNs: Long): Entry? {
        prune(nowNs)
        val first = entries.firstOrNull() ?: return null
        val offset = seqAfter(sequence, first.sequence)
        if (offset < 0 || offset >= entries.size) return null
        val entry = entries[offset]
        return if (entry.sequence == sequence) entry else entries.firstOrNull { it.sequence == sequence }
    }

    private fun prune(nowNs: Long) {
        while (entries.isNotEmpty() && nowNs - entries.first().sentNs > maxAgeNs) dropOldest()
    }

    private fun dropOldest() {
        heldBytes -= entries.removeFirst().size
    }
}

/** Bytes counted over a rolling window of [buckets] slots. */
internal class RollingBytes(windowNs: Long = 1_000_000_000L, private val buckets: Int = 10) {
    private val bucketNs = windowNs / buckets
    private val counts = LongArray(buckets)
    private val epochs = LongArray(buckets) { Long.MIN_VALUE }

    fun add(nowNs: Long, bytes: Long) {
        val epoch = Math.floorDiv(nowNs, bucketNs)
        val slot = Math.floorMod(epoch, buckets.toLong()).toInt()
        if (epochs[slot] != epoch) {
            epochs[slot] = epoch
            counts[slot] = 0
        }
        counts[slot] += bytes
    }

    fun sum(nowNs: Long): Long {
        val epoch = Math.floorDiv(nowNs, bucketNs)
        var total = 0L
        for (slot in 0 until buckets) if (epochs[slot] > epoch - buckets && epochs[slot] <= epoch) total += counts[slot]
        return total
    }
}

/**
 * Lost-chunk recovery for one GATT connection (default on since 2026-10-21, `test_gatt_chunk_recovery=false` is the control
 * arm; DECISIONS "Design
 * GR" option C after review 1). The sender keeps a [ResendHistory] whenever this switch is on and answers NACKs; the
 * reader ([GattRecoveryReader], owned by the [GattBytePipe]) asks for missing chunks only after the peer's HELLO.
 *
 * Threading (review 1 item 3): Bluetooth callbacks only parse and enqueue. A blocking send from a callback would deadlock:
 * a server's notification waits for onNotificationSent and a client's write for onCharacteristicWrite, both delivered on
 * the same callback path. Values leave through [ChunkedGattOutputStream]: the data writer drains the priority queue
 * before each new data chunk, and this link's writer thread drains it, under the stream's lock, while the data writer is
 * idle. A NACK cannot preempt a data value already handed to the stack (an outstanding acknowledged write finishes first).
 */
internal class GattChunkRecovery(
    val link: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = { line -> Log.i("BluetoothVideo", "BENCHMARK gatt_recovery: $line") },
    private val startThread: Boolean = true,
) {
    private class Outbound(val value: ByteArray, val kind: String, val sequence: Int, val enqueuedNs: Long)

    private val lock = Any()
    private val outbound = LinkedBlockingDeque<Outbound>()
    private val closed = AtomicBoolean(false)
    @Volatile var peerHello: Gr1.Message.Hello? = null
        private set
    @Volatile private var reader: GattBytePipe? = null
    @Volatile private var output: ChunkedGattOutputStream? = null

    /** The link's ATT value limit (payload plus the 5-byte chunk header); 0 until [start]. */
    @Volatile var valueLimit = 0
        private set

    // Sender state, guarded by [lock]; never held during a send.
    private val history = ResendHistory()
    private val sentData = RollingBytes()
    private val resent = RollingBytes()
    private var outOfRangeRanges = 0
    private var nacksIgnored = false
    private var lastDataSentNs = 0L
    private var highSentFor: Int? = null
    private val highTimes = ArrayDeque<Long>()
    private var helloSends = 0
    private var helloFirstNs = 0L
    private var writer: Thread? = null

    /** History size for tests and logs: values, bytes. */
    fun historyState(): Pair<Int, Int> = synchronized(lock) { history.heldValues to history.heldBytes }

    fun note(line: String) = log("$line | link=$link")

    fun attachReader(pipe: GattBytePipe) {
        reader = pipe
    }

    /** The link can send: announce HELLO and (in the app) start this link's writer thread. */
    fun start(out: ChunkedGattOutputStream) {
        if (closed.get()) return
        output = out
        valueLimit = out.valueLimitBytes
        enqueueHello()
        if (startThread) writer = thread(name = "gatt-recovery-$link", isDaemon = true) { runWriter() }
    }

    /** A type-2 value from the peer, on a Bluetooth callback thread: parse and enqueue only. */
    fun onControl(value: ByteArray) {
        if (closed.get()) return
        val message = Gr1.decode(value)
        if (message == null) {
            if (Gr1.isGr1(value)) note("event=ignored | reason=malformed | bytes=${value.size}")
            return
        }
        when (message) {
            is Gr1.Message.Hello -> {
                val first = peerHello == null
                peerHello = message
                if (first) note("event=hello | historyValues=${message.historyValues} | historyBytes=${message.historyBytes} | historyMs=${message.historyMs}")
            }
            is Gr1.Message.Nack -> onNack(message.ranges)
            is Gr1.Message.Gone -> reader?.onRecoveryGone(message.range)
            is Gr1.Message.High -> reader?.onRecoveryHigh(message.sequence)
        }
    }

    /** The reader asks for missing chunks (called under the pipe's lock; only enqueues). */
    fun sendNack(ranges: List<Gr1.SeqRange>) {
        if (closed.get()) return
        outbound.addFirst(Outbound(Gr1.nack(ranges), "nack", ranges.first().first, nanoTime()))
    }

    /** A data value left this side (called by the data writer under the stream's lock). */
    fun onDataSent(sequence: Int, value: ByteArray) {
        val now = nanoTime()
        synchronized(lock) {
            history.record(sequence, value, now)
            sentData.add(now, value.size.toLong())
            lastDataSentNs = now
        }
    }

    /** Sends every queued control value and resend ahead of new data; [send] is the stream's own (locked) send. */
    fun drain(send: (ByteArray) -> Unit) {
        while (!closed.get()) {
            val item = outbound.pollFirst() ?: return
            val startedNs = nanoTime()
            send(item.value)
            when (item.kind) {
                "resend" -> note("event=resend | seq=${item.sequence} | bytes=${item.value.size} | queuedAheadMs=${(startedNs - item.enqueuedNs) / 1_000_000L}")
                "gone", "high" -> note("event=${item.kind}_sent | seq=${item.sequence}")
            }
        }
    }

    /** Timers: the second HELLO, the idle HIGH probe and the reader's request rounds and deadline. */
    fun tick() {
        if (closed.get()) return
        val now = nanoTime()
        var helloAgain = false
        var high: Int? = null
        synchronized(lock) {
            if (helloSends == 1 && now - helloFirstNs >= Gr1.HELLO_REPEAT_MS * 1_000_000L) helloAgain = true
            val last = history.lastSent
            if (
                last != null && peerHello != null && highSentFor != last &&
                now - lastDataSentNs >= Gr1.HIGH_IDLE_MS * 1_000_000L
            ) {
                while (highTimes.isNotEmpty() && now - highTimes.first() >= 1_000_000_000L) highTimes.removeFirst()
                if (highTimes.size < Gr1.HIGH_PER_SECOND) {
                    highTimes.addLast(now)
                    highSentFor = last
                    high = last
                }
            }
        }
        if (helloAgain) enqueueHello()
        high?.let { outbound.addLast(Outbound(Gr1.high(it), "high", it, now)) }
        reader?.recoveryTick()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbound.clear()
    }

    private fun enqueueHello() {
        val count = synchronized(lock) {
            helloSends++
            if (helloSends == 1) helloFirstNs = nanoTime()
            helloSends
        }
        outbound.addFirst(Outbound(Gr1.hello(), "hello", -1, nanoTime()))
        note("event=hello_sent | n=$count")
    }

    private fun onNack(ranges: List<Gr1.SeqRange>) {
        val now = nanoTime()
        val queued = ArrayList<Outbound>()
        val gone = ArrayList<Int>()
        var capped = 0
        var exhausted = 0
        var budget = 0L
        synchronized(lock) {
            if (nacksIgnored) return
            budget = maxOf(sentData.sum(now) * Gr1.RESEND_BUDGET_PERCENT / 100, Gr1.RESEND_FLOOR_BYTES_PER_SECOND.toLong())
            for (range in ranges) {
                var outOfRange = false
                for (index in 0 until range.run) {
                    val sequence = range.first + index
                    val last = history.lastSent
                    if (last == null || seqAfter(sequence, last) > 0) {
                        outOfRange = true
                        continue
                    }
                    val entry = history.find(sequence, now)
                    when {
                        entry == null -> gone += sequence
                        entry.resends >= Gr1.RESENDS_PER_SEQUENCE -> exhausted++
                        resent.sum(now) + entry.size > budget -> capped++
                        else -> {
                            entry.resends++
                            resent.add(now, entry.size.toLong())
                            queued += Outbound(entry.copy(), "resend", sequence, now)
                        }
                    }
                }
                if (outOfRange && ++outOfRangeRanges > Gr1.MAX_OUT_OF_RANGE_NACK_RANGES) {
                    nacksIgnored = true
                    break
                }
            }
        }
        queued.forEach { outbound.addLast(it) }
        coalesce(gone).forEach { range -> outbound.addFirst(Outbound(Gr1.gone(range), "gone", range.first, now)) }
        note(
            "event=nack_received | seq=${ranges.first().first} | ranges=${ranges.size} | queued=${queued.size} | " +
                "gone=${gone.size} | exhausted=$exhausted",
        )
        if (capped > 0) note("event=capped | values=$capped | budgetBytesPerSecond=$budget")
        if (synchronized(lock) { nacksIgnored }) note("event=nacks_ignored | reason=out_of_range | ranges=$outOfRangeRanges")
    }

    private fun coalesce(sequences: List<Int>): List<Gr1.SeqRange> {
        val ranges = ArrayList<Gr1.SeqRange>()
        for (sequence in sequences) {
            val last = ranges.lastOrNull()
            if (last != null && seqAfter(sequence, last.last) == 1 && last.run < Gr1.MAX_RUN) {
                ranges[ranges.size - 1] = last.copy(run = last.run + 1)
            } else {
                ranges += Gr1.SeqRange(sequence, 1)
            }
        }
        return ranges
    }

    private fun runWriter() {
        try {
            while (!closed.get()) {
                outbound.pollFirst(Gr1.TICK_MS, TimeUnit.MILLISECONDS)?.let { outbound.addFirst(it) }
                tick()
                if (outbound.isNotEmpty()) output?.sendPriority()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: IOException) {
            // The data path sees the same link failure on its next write.
            note("event=writer_stopped | reason=${error.message}")
        }
    }

    companion object {
        /** Null when `test_gatt_chunk_recovery=false` turned the (default-on) recovery off. */
        fun forLink(link: String): GattChunkRecovery? = if (DebugTunables.gattChunkRecovery) GattChunkRecovery(link) else null
    }
}

/**
 * The reader half of [GattChunkRecovery], owned by a [GattBytePipe] and called under the pipe's lock. Missing chunks are
 * kept as ranges (review 1 item 4), only within [Gr1.HISTORY_VALUES] (and the peer's announced history) beyond the next
 * expected sequence.
 */
internal class GattRecoveryReader(private val recovery: GattChunkRecovery) {
    private class Hole(val first: Int, val last: Int, val firstMissingNs: Long, var requests: Int, var lastRequestNs: Long)

    private var holes: List<Hole> = emptyList()
    private var highHeard: Int? = null
    private val nackTimes = ArrayDeque<Long>()

    /** True once the peer's HELLO arrived: the reader then waits for resends instead of option A's fail-fast. */
    val active: Boolean get() = recovery.peerHello != null

    private fun limit(): Int = minOf(recovery.peerHello?.historyValues ?: 0, Gr1.HISTORY_VALUES)

    /** A HIGH from the sender; true when it may reveal a missing tail. */
    fun onHigh(sequence: Int, next: Int): Boolean {
        if (!active) return false
        val distance = seqAfter(sequence, next)
        if (distance < 0) return false
        if (distance >= limit()) {
            recovery.note("event=ignored | reason=high_out_of_range | seq=$sequence | next=$next")
            return false
        }
        val heard = highHeard
        if (heard == null || seqAfter(sequence, heard) > 0) highHeard = sequence
        return true
    }

    /** The first missing sequence a GONE covers, or null when it covers nothing this reader misses. */
    fun goneHit(range: Gr1.SeqRange): Int? {
        val hole = holes.firstOrNull { range.overlaps(it.first, it.last) } ?: return null
        return if (seqAfter(range.first, hole.first) > 0) range.first else hole.first
    }

    /**
     * Re-evaluates the missing ranges after any change and sends due requests. Returns a failure reason, or null.
     * [pending] are the held sequences in ascending order.
     */
    fun update(next: Int, pending: Iterable<Int>, parkedCount: Int, parkedBytes: Int, nowNs: Long): String? {
        val peer = recovery.peerHello ?: return null
        val limit = limit()
        val farthest = pending.lastOrNull()?.let { seqAfter(it, next) } ?: -1
        if (parkedCount > 0 && (farthest >= limit || parkedCount >= limit || parkedBytes > peer.historyBytes)) {
            return "GATT chunk $next was lost (the peer no longer holds it: $parkedCount later chunks, $parkedBytes bytes)"
        }
        highHeard?.let { if (seqAfter(it, next) < 0) highHeard = null }
        val fresh = ArrayList<Pair<Int, Int>>()
        var expected = next
        for (key in pending) {
            if (seqAfter(key, expected) > 0) fresh += expected to key - 1
            expected = key + 1
        }
        highHeard?.let { heard -> if (seqAfter(heard, expected) >= 0) fresh += expected to heard }
        val maxSequence = next + limit - 1
        val old = holes
        holes = fresh.mapNotNull { (first, last) ->
            if (seqAfter(first, next) >= limit) return@mapNotNull null
            val clipped = if (seqAfter(last, maxSequence) > 0) maxSequence else last
            val prior = old.firstOrNull { seqAfter(clipped, it.first) >= 0 && seqAfter(it.last, first) >= 0 }
            Hole(first, clipped, prior?.firstMissingNs ?: nowNs, prior?.requests ?: 0, prior?.lastRequestNs ?: 0L)
        }
        for (filled in old) {
            if (holes.none { seqAfter(it.last, filled.first) >= 0 && seqAfter(filled.last, it.first) >= 0 }) {
                recovery.note(
                    "event=recovered | seq=${filled.first} | count=${seqAfter(filled.last, filled.first) + 1} | " +
                        "waitedMs=${(nowNs - filled.firstMissingNs) / 1_000_000L} | rounds=${filled.requests}",
                )
            }
        }
        holes.firstOrNull { nowNs - it.firstMissingNs >= Gr1.DEADLINE_MS * 1_000_000L }?.let { hole ->
            return "GATT chunk ${hole.first} was lost (${hole.requests} requests, ${(nowNs - hole.firstMissingNs) / 1_000_000L} ms)"
        }
        requestDue(nowNs)
        return null
    }

    private fun requestDue(nowNs: Long) {
        val due = holes.filter {
            it.requests == 0 || (it.requests < Gr1.MAX_REQUESTS && nowNs - it.lastRequestNs >= Gr1.ROUND_MS * 1_000_000L)
        }
        if (due.isEmpty()) return
        val fit = Gr1.nackRangesFitting(recovery.valueLimit)
        if (fit == 0) return
        while (nackTimes.isNotEmpty() && nowNs - nackTimes.first() >= 1_000_000_000L) nackTimes.removeFirst()
        if (nackTimes.size >= Gr1.READER_NACKS_PER_SECOND) return
        val ranges = ArrayList<Gr1.SeqRange>()
        val asked = ArrayList<Hole>()
        for (hole in due) {
            if (ranges.size >= fit) break
            var start = hole.first
            var left = seqAfter(hole.last, hole.first) + 1
            while (left > 0 && ranges.size < fit) {
                val run = minOf(left, Gr1.MAX_RUN)
                ranges += Gr1.SeqRange(start, run)
                start += run
                left -= run
            }
            asked += hole
        }
        for (hole in asked) {
            hole.requests++
            hole.lastRequestNs = nowNs
        }
        nackTimes.addLast(nowNs)
        recovery.sendNack(ranges)
        recovery.note("event=nack | seq=${ranges.first().first} | ranges=${ranges.size} | round=${asked.maxOf { it.requests }}")
    }
}
