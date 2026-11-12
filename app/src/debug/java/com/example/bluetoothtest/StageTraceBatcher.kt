package com.example.bluetoothtest

/**
 * Pure (JVM-testable) encoder for the `BENCHMARK stage v2` logcat batch line.
 *
 * Why: v1 logged one ~240-byte JSON line per pipeline stage per frame (~150-200 lines/s, ~50 KB/s), which overflowed
 * Android's logd and made liblog drop unrelated lines (call-end acks, join stage lines). v2 keeps every record and every
 * field but writes the per-stream constants once per line and the records as compact arrays:
 *
 *     BENCHMARK stage v2 {"session":S,"scope":C,"role":R,"pid":P,"clock":"System.nanoTime","rows":[ROW,...]}
 *
 * ROW column order (fixed; readers must not reorder):
 *     [sequence, stage, pts_us, at_ns, bytes, final, extra]
 *   sequence  1, 2, 3... per (scope, role) stream, contiguous across lines, so a missing record is detectable
 *   final     1 for the close sentinel (stage "final"), else 0
 *   extra     JSON object of diagnostic fields, or null
 * `direction` is not written: it is "outgoing" for role video_encoder/broadcast_transport or stage
 * send_enqueue/write_begin/write_end, else "incoming" (the exact v1 rule; readers derive it).
 *
 * Flush rules (all under one lock): the batch is written when adding the next row would push the whole message past
 * [maxLineBytes] (the row then starts the next batch), when [maxAgeNs] has passed since the batch's first row (checked on
 * every record and by the optional [schedule] timer, so an idle stream does not hold rows), and on [close] (the final
 * row is always flushed immediately with whatever is pending). A single row larger than the limit goes out alone.
 * Logcat's payload limit is 4068 bytes including tag/priority; the 3500-byte default leaves ample margin.
 */
internal class StageTraceBatcher(
    session: String,
    scope: String,
    role: String,
    pid: Int,
    private val sink: (String) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
    /** Runs the task once after the delay; used to flush a batch whose stream went idle. Null = no timer. */
    private val schedule: ((delayNs: Long, task: Runnable) -> Unit)? = null,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
    private val maxAgeNs: Long = DEFAULT_MAX_AGE_NS,
) {
    private val prefix = StringBuilder(PREFIX).append("{\"session\":").also { jsonString(it, session) }
        .append(",\"scope\":").also { jsonString(it, scope) }
        .append(",\"role\":").also { jsonString(it, role) }
        .append(",\"pid\":").append(pid)
        .append(",\"clock\":\"System.nanoTime\",\"rows\":[").toString()
    private val prefixBytes = utf8Length(prefix)
    private val rows = ArrayList<String>()
    private var rowBytes = 0
    private var firstRowNs = 0L
    private var sequence = 0L
    private var closed = false

    @Synchronized
    fun record(stage: String, ptsUs: Long, bytes: Int, atNs: Long, extra: Map<String, Any>?) {
        if (closed) return
        add(encodeRow(++sequence, stage, ptsUs, atNs, bytes, false, extra))
        if (rows.isNotEmpty() && nowNs() - firstRowNs >= maxAgeNs) flush()
    }

    /** Flushes the final sentinel together with every pending row; later records are ignored. */
    @Synchronized
    fun close() {
        if (closed) return
        add(encodeRow(++sequence, "final", 0, nowNs(), 0, true, null))
        flush()
        closed = true
    }

    /** Timer entry: writes the pending batch if it is old enough. Safe to call at any time. */
    @Synchronized
    fun flushIfDue() {
        if (rows.isNotEmpty() && nowNs() - firstRowNs >= maxAgeNs) flush()
    }

    private fun add(row: String) {
        val size = utf8Length(row)
        // message = prefix + rows joined by commas + "]}"
        if (rows.isNotEmpty() && prefixBytes + rowBytes + rows.size + size + SUFFIX.length > maxLineBytes) flush()
        if (rows.isEmpty()) {
            firstRowNs = nowNs()
            schedule?.invoke(maxAgeNs, Runnable { flushIfDue() })
        }
        rows.add(row)
        rowBytes += size
    }

    private fun flush() {
        if (rows.isEmpty()) return
        val line = StringBuilder(prefixBytes + rowBytes + rows.size + SUFFIX.length)
            .append(prefix).append(rows.joinToString(",")).append(SUFFIX).toString()
        rows.clear()
        rowBytes = 0
        sink(line)
    }

    private fun encodeRow(
        sequence: Long, stage: String, ptsUs: Long, atNs: Long, bytes: Int, final: Boolean, extra: Map<String, Any>?,
    ): String {
        val out = StringBuilder(96).append('[').append(sequence).append(',')
        jsonString(out, stage)
        out.append(',').append(ptsUs).append(',').append(atNs).append(',').append(bytes)
            .append(',').append(if (final) '1' else '0').append(',')
        val fields = extra?.filterKeys { it !in BASE_FIELDS }
        if (fields.isNullOrEmpty()) out.append("null") else {
            // v1 never let an extra field overwrite a base field; v2 drops such keys the same way.
            out.append('{')
            var first = true
            for ((key, value) in fields) {
                if (!first) out.append(',')
                first = false
                jsonString(out, key).append(':')
                jsonValue(out, value)
            }
            out.append('}')
        }
        return out.append(']').toString()
    }

    companion object {
        const val PREFIX = "BENCHMARK stage v2 "
        const val SUFFIX = "]}"
        const val DEFAULT_MAX_LINE_BYTES = 3500
        const val DEFAULT_MAX_AGE_NS = 250_000_000L
        private val BASE_FIELDS = setOf(
            "session", "scope", "role", "direction", "pid", "clock", "sequence", "stage", "pts_us", "at_ns", "bytes", "final",
        )

        /** The v1 direction rule, shared by the v1 writer and documented for readers of v2. */
        fun direction(role: String, stage: String): String =
            if (role == "video_encoder" || role == "broadcast_transport" ||
                stage == "send_enqueue" || stage == "write_begin" || stage == "write_end") "outgoing" else "incoming"

        fun utf8Length(text: CharSequence): Int {
            var n = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return n
        }

        private fun jsonValue(out: StringBuilder, value: Any?) {
            when (value) {
                null -> out.append("null")
                is Boolean -> out.append(value)
                is Double -> if (value.isFinite()) out.append(value) else out.append("null")
                is Float -> if (value.isFinite()) out.append(value) else out.append("null")
                is Number -> out.append(value)
                else -> jsonString(out, value.toString())
            }
        }

        private fun jsonString(out: StringBuilder, text: String): StringBuilder {
            out.append('"')
            for (c in text) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c.code < 0x20 -> out.append("\\u").append(String.format("%04x", c.code))
                    else -> out.append(c)
                }
            }
            return out.append('"')
        }
    }
}
