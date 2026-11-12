package com.example.bluetoothtest

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Experimental diagnostic `test_hop_integrity_resend_diff` ([DebugTunables.hopIntegrityResendDiff], DEBUG only, default
 * off; runner tunable `hop_integrity_resend_diff`).
 *
 * Why (defect A, w2v3-20261110g/0003): a `BMS1` record failed its tag on a GATT link with its header and length intact,
 * the next header not yet received and no GR1 gap: the bytes most likely changed, at the same length, below the app on
 * one phone. This asks the peer for the same chunks again and compares them, so a run can tell which bytes differ.
 *
 * How: each live GATT pipe with GR1 keeps a [ResendDiffTap] of its last [ResendDiffTap.RING_CHUNKS] accepted values,
 * registered here. On a `kind=tag` failure the record reader passes the failed record's received bytes (header and
 * ciphertext) to [examine]; the tap whose ring holds exactly those bytes (a content match: no stream offsets or reader
 * plumbing are needed, and keys differ per link so no other link matches) sends one NACK for the chunks that carried the
 * record. The sender resends the values it still holds unchanged (PROTOCOL GR1 sender rule 2); the pipe drops them as
 * duplicates as always and only shows them to the tap, which compares each one byte for byte with what was received.
 * The reader waits at most [WAIT_MS], logs, and then ends the link exactly as before.
 *
 * Nothing from a resent copy is decrypted, delivered or used to repair anything: authentication, exact-next ordering
 * and the fail-closed end are unchanged. The log lines carry sequences, offsets, counts and a pattern name, never bytes.
 * With the switch off no tap exists and [examine] is never called.
 */
internal object HopIntegrityResendDiff {
    const val WAIT_MS = 300L

    /** Replaced by JVM tests. */
    @Volatile var sink: (String) -> Unit = { line -> runCatching { android.util.Log.i("BluetoothVideo", line) } }

    private val taps = LinkedHashSet<ResendDiffTap>()

    fun register(tap: ResendDiffTap) = synchronized(taps) { taps += tap }

    fun unregister(tap: ResendDiffTap) = synchronized(taps) { taps -= tap }

    /**
     * Called on the reader thread after a tag failure, before the reader throws. Blocks at most about [WAIT_MS].
     * Never throws: a diagnostic must not change how the failure ends.
     */
    fun examine(record: ByteArray, waitMs: Long = WAIT_MS) {
        runCatching {
            val live = synchronized(taps) { taps.toList() }
            if (live.isEmpty()) return
            val tap = live.firstOrNull { it.holds(record) }
            if (tap == null) {
                sink("BENCHMARK hop_integrity_diff_summary: located=false | links=${live.size} | reason=not_in_recent_chunks")
                return
            }
            tap.examine(record, waitMs).forEach(sink)
        }
    }

    /** One compared chunk; offsets are in the ATT value (the 5-byte chunk header first). */
    data class ChunkDiff(val firstOffset: Int, val bytes: Int, val pattern: String)

    /** LE link-layer fragments of an ATT value: the first carries 244 value bytes (251 - 4 L2CAP - 3 ATT), then 251. */
    private const val FIRST_FRAGMENT_VALUE_BYTES = 244
    private const val FRAGMENT_BYTES = 251
    private const val FRAGMENT_MIN_SPAN = 16

    private fun fragmentOf(offset: Int): Int =
        if (offset < FIRST_FRAGMENT_VALUE_BYTES) 0 else 1 + (offset - FIRST_FRAGMENT_VALUE_BYTES) / FRAGMENT_BYTES

    /**
     * none: identical. whole_value: another length, or more than half of the bytes differ. bitflip: one or two bytes,
     * each differing in a single bit. fragment251: every difference inside one LE link-layer fragment of the value, over
     * a span of at least 16 bytes (a fragment replaced or garbled below L2CAP). run: any other difference.
     */
    fun classify(received: ByteArray, resent: ByteArray): ChunkDiff {
        if (received.size != resent.size) {
            val shorter = minOf(received.size, resent.size)
            val first = (0 until shorter).firstOrNull { received[it] != resent[it] } ?: shorter
            return ChunkDiff(first, maxOf(received.size, resent.size) - first, "whole_value")
        }
        val differing = received.indices.filter { received[it] != resent[it] }
        if (differing.isEmpty()) return ChunkDiff(-1, 0, "none")
        val first = differing.first()
        val last = differing.last()
        val pattern = when {
            differing.size * 2 > received.size -> "whole_value"
            differing.size <= 2 && differing.all { Integer.bitCount((received[it].toInt() xor resent[it].toInt()) and 0xff) == 1 } -> "bitflip"
            last - first + 1 >= FRAGMENT_MIN_SPAN && fragmentOf(first) == fragmentOf(last) -> "fragment251"
            else -> "run"
        }
        return ChunkDiff(first, differing.size, pattern)
    }
}

/**
 * One GATT pipe's side of [HopIntegrityResendDiff]. The pipe calls [onAccepted], [onDuplicate] and [onGone] on its
 * callback path (under its own lock; this class takes only its own [lock]); [examine] runs on the record reader's thread.
 */
internal class ResendDiffTap(
    private val link: String,
    /** True while the peer's GR1 HELLO is known (its sender keeps a history and answers NACKs). */
    private val recoveryActive: () -> Boolean,
    /** Enqueues one NACK on the link's GR1 writer (never sends on this thread). */
    private val sendNack: (List<Gr1.SeqRange>) -> Unit,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    companion object {
        const val RING_CHUNKS = 64
    }

    private class Held(val sequence: Int, val value: ByteArray)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ring = ArrayDeque<Held>()
    private var awaiting: Map<Int, ByteArray>? = null
    private val resent = HashMap<Int, ByteArray>()
    private val gone = HashSet<Int>()
    private var closed = false

    /** A data value delivered in order: [value] is the whole ATT value, header included (copied here). */
    fun onAccepted(sequence: Int, value: ByteArray) = lock.withLock {
        if (closed) return@withLock
        ring.addLast(Held(sequence, value.copyOf()))
        while (ring.size > RING_CHUNKS) ring.removeFirst()
    }

    /** A copy of an already delivered value; kept only while [examine] waits for exactly that sequence. */
    fun onDuplicate(sequence: Int, value: ByteArray) = lock.withLock {
        val wanted = awaiting ?: return@withLock
        if (sequence !in wanted || sequence in resent) return@withLock
        resent[sequence] = value.copyOf()
        changed.signalAll()
    }

    /** GR1 GONE: the sender no longer holds these sequences. */
    fun onGone(range: Gr1.SeqRange) = lock.withLock {
        val wanted = awaiting ?: return@withLock
        for (index in 0 until range.run) {
            val sequence = range.first + index
            if (sequence in wanted) gone += sequence
        }
        changed.signalAll()
    }

    fun close() = lock.withLock {
        closed = true
        ring.clear()
        changed.signalAll()
    }

    fun holds(record: ByteArray): Boolean = lock.withLock { locate(record) != null }

    /** The held values that carried [record] (an exact match of its bytes across consecutive payloads), oldest first. */
    private fun locate(record: ByteArray): List<Held>? {
        if (record.isEmpty() || ring.isEmpty()) return null
        val starts = IntArray(ring.size)
        var total = 0
        ring.forEachIndexed { index, held ->
            starts[index] = total
            total += (held.value.size - GATT_CHUNK_HEADER_BYTES).coerceAtLeast(0)
        }
        if (total < record.size) return null
        val stream = ByteArray(total)
        ring.forEachIndexed { index, held ->
            val payload = held.value.size - GATT_CHUNK_HEADER_BYTES
            if (payload > 0) held.value.copyInto(stream, starts[index], GATT_CHUNK_HEADER_BYTES, held.value.size)
        }
        var at = total - record.size
        while (at >= 0) {
            if (matchesAt(stream, at, record)) {
                val end = at + record.size
                return ring.filterIndexed { index, held ->
                    val start = starts[index]
                    val stop = start + (held.value.size - GATT_CHUNK_HEADER_BYTES)
                    stop > at && start < end
                }
            }
            at--
        }
        return null
    }

    private fun matchesAt(stream: ByteArray, at: Int, record: ByteArray): Boolean {
        for (index in record.indices) if (stream[at + index] != record[index]) return false
        return true
    }

    /** Sends the NACK, waits up to [waitMs] for the resends and returns the log lines (no payload bytes). */
    fun examine(record: ByteArray, waitMs: Long): List<String> {
        val startedMs = nowMs()
        val carried: List<Held> = lock.withLock {
            if (closed) return listOf(summary(false, 0, 0, 0, 0, 0, "link_closed"))
            if (!recoveryActive()) return listOf(summary(true, 0, 0, 0, 0, 0, "gr1_inactive"))
            val found = locate(record) ?: return listOf(summary(false, 0, 0, 0, 0, 0, "not_in_recent_chunks"))
            resent.clear()
            gone.clear()
            awaiting = found.associate { it.sequence to it.value }
            found
        }
        // Consecutive by construction (the pipe accepts in order); one range of at most RING_CHUNKS values.
        sendNack(listOf(Gr1.SeqRange(carried.first().sequence, carried.size)))
        val lines = ArrayList<String>()
        lock.withLock {
            val deadline = startedMs + waitMs
            while (!closed && resent.size + gone.size < carried.size) {
                val left = deadline - nowMs()
                if (left <= 0) break
                changed.await(left, TimeUnit.MILLISECONDS)
            }
            var differing = 0
            for (held in carried) {
                val copy = resent[held.sequence] ?: continue
                val diff = HopIntegrityResendDiff.classify(held.value, copy)
                if (diff.pattern != "none") differing++
                lines += "BENCHMARK hop_integrity_diff: chunk=${held.sequence} | firstOffset=${diff.firstOffset} | " +
                    "bytes=${diff.bytes} | pattern=${diff.pattern}"
            }
            lines += summary(true, carried.size, resent.size, gone.size, differing, nowMs() - startedMs,
                if (resent.size + gone.size < carried.size) "timeout" else "complete")
            awaiting = null
            resent.clear()
            gone.clear()
        }
        return lines
    }

    private fun summary(located: Boolean, chunks: Int, resentCount: Int, goneCount: Int, differing: Int, waitedMs: Long, reason: String) =
        "BENCHMARK hop_integrity_diff_summary: located=$located | link=$link | chunks=$chunks | resent=$resentCount | " +
            "gone=$goneCount | differing=$differing | waitedMs=$waitedMs | reason=$reason"
}
