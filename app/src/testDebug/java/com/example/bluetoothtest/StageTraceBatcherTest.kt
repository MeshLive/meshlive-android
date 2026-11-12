package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The v2 stage-trace batch line: nothing is lost, order and sequence survive, every line fits logcat. */
class StageTraceBatcherTest {
    private class Rig(maxLineBytes: Int = StageTraceBatcher.DEFAULT_MAX_LINE_BYTES, withTimer: Boolean = false) {
        var now = 1_000_000_000L
        val lines = ArrayList<String>()
        val timers = ArrayList<Pair<Long, Runnable>>()
        val batcher = StageTraceBatcher(
            session = "bench-1", scope = "317644621801431", role = "video_decoder", pid = 4242,
            sink = { lines.add(it) }, nowNs = { now },
            schedule = if (withTimer) { delay, task -> timers.add(delay to task) } else null,
            maxLineBytes = maxLineBytes,
        )

        /** Every row of every line, in the order logcat would show them, with the header's constants. */
        fun rows(): List<List<Any?>> = lines.flatMap { line ->
            val body = parse(line.removePrefix(StageTraceBatcher.PREFIX)) as Map<*, *>
            assertEquals("bench-1", body["session"])
            assertEquals("317644621801431", body["scope"])
            assertEquals("video_decoder", body["role"])
            assertEquals(4242L, body["pid"])
            assertEquals("System.nanoTime", body["clock"])
            @Suppress("UNCHECKED_CAST")
            body["rows"] as List<List<Any?>>
        }
    }

    @Test
    fun everyRecordSurvivesBatchingInOrderWithContiguousSequencesAndFitsLogcat() {
        val rig = Rig()
        repeat(500) { i ->
            rig.now += 1_000_000L
            rig.batcher.record("decoder_output", 1_000L * i, 1_000 + i, 5_000_000_000L + i, null)
        }
        rig.batcher.close()
        val rows = rig.rows()
        assertEquals(501, rows.size)
        assertEquals((1L..501L).toList(), rows.map { it[0] })
        assertEquals(500, rows.count { it[1] == "decoder_output" })
        assertEquals(5_000L, rows[5][2])
        assertEquals(1_007L, rows[7][4])
        assertEquals(5_000_000_007L, rows[7][3])
        assertTrue(rig.lines.size > 1)
        rig.lines.forEach { assertTrue(StageTraceBatcher.utf8Length(it) <= StageTraceBatcher.DEFAULT_MAX_LINE_BYTES) }
        // The close sentinel is last, flagged final, and carries no extra.
        assertEquals(listOf(501L, "final", 0L), listOf(rows.last()[0], rows.last()[1], rows.last()[2]))
        assertEquals(1L, rows.last()[5])
        assertEquals(0L, rows.first()[5])
    }

    @Test
    fun extraFieldsRideAsTheLastColumnAndNeverOverrideBaseFields() {
        val rig = Rig()
        rig.batcher.record(
            "encode_output", 33_333, 4_000, 7L,
            linkedMapOf("q_idx" to 87, "frame_type" to "key", "ratio" to 0.5, "bytes" to 1, "stage" to "x", "note" to "a\"b\\cé\n"),
        )
        rig.batcher.record("receive", 1, 2, 3L, null)
        rig.batcher.close()
        val rows = rig.rows()
        assertEquals(
            mapOf("q_idx" to 87L, "frame_type" to "key", "ratio" to 0.5, "note" to "a\"b\\cé\n"),
            rows[0][6],
        )
        assertEquals("encode_output", rows[0][1])
        assertEquals(4_000L, rows[0][4])
        assertEquals(null, rows[1][6])
    }

    @Test
    fun aBatchIsWrittenOnceItsFirstRowIsOldEnoughEvenWhileTheStreamKeepsRecording() {
        val rig = Rig()
        rig.batcher.record("receive", 1, 10, 1L, null)
        rig.now += 100_000_000L
        rig.batcher.record("receive", 2, 10, 2L, null)
        assertTrue("younger than 250 ms stays pending", rig.lines.isEmpty())
        rig.now += 150_000_000L
        rig.batcher.record("receive", 3, 10, 3L, null)
        assertEquals(1, rig.lines.size)
        assertEquals(listOf(1L, 2L, 3L), rig.rows().map { it[0] })
    }

    @Test
    fun anIdleStreamFlushesThroughTheTimerAndAStaleTimerIsHarmless() {
        val rig = Rig(withTimer = true)
        rig.batcher.record("receive", 1, 10, 1L, null)
        assertEquals(1, rig.timers.size)
        assertEquals(StageTraceBatcher.DEFAULT_MAX_AGE_NS, rig.timers[0].first)
        rig.timers[0].second.run() // too early: nothing is due
        assertTrue(rig.lines.isEmpty())
        rig.now += 250_000_000L
        rig.timers[0].second.run()
        assertEquals(1, rig.lines.size)
        rig.timers[0].second.run() // nothing pending any more
        assertEquals(1, rig.lines.size)
        // The next row starts a fresh batch with its own timer.
        rig.batcher.record("receive", 2, 10, 2L, null)
        assertEquals(2, rig.timers.size)
        assertEquals(listOf(1L), rig.rows().map { it[0] })
    }

    @Test
    fun closeFlushesPendingRowsAndTheFinalSentinelImmediatelyAndOnlyOnce() {
        val rig = Rig()
        rig.batcher.record("receive", 1, 10, 1L, null)
        rig.batcher.record("receive", 2, 10, 2L, null)
        assertTrue(rig.lines.isEmpty())
        rig.batcher.close()
        assertEquals(1, rig.lines.size)
        assertEquals(listOf("receive", "receive", "final"), rig.rows().map { it[1] })
        rig.batcher.close()
        rig.batcher.record("receive", 3, 10, 3L, null)
        rig.batcher.flushIfDue()
        assertEquals(1, rig.lines.size)
    }

    @Test
    fun aRowLargerThanTheLimitIsStillLoggedAloneAndNothingElseIsLost() {
        val rig = Rig(maxLineBytes = 400)
        rig.batcher.record("receive", 1, 10, 1L, null)
        rig.batcher.record("receive", 2, 10, 2L, mapOf("big" to "x".repeat(600)))
        rig.batcher.record("receive", 3, 10, 3L, null)
        rig.batcher.close()
        assertEquals(listOf(1L, 2L, 3L, 4L), rig.rows().map { it[0] })
        assertTrue(rig.lines.any { StageTraceBatcher.utf8Length(it) > 400 && it.contains("big") })
        assertEquals(1, rig.lines.count { it.contains("big") })
    }

    @Test
    fun directionRuleIsTheLegacyOneReadersDerive() {
        assertEquals("outgoing", StageTraceBatcher.direction("video_encoder", "encode_output"))
        assertEquals("outgoing", StageTraceBatcher.direction("broadcast_transport", "final"))
        assertEquals("outgoing", StageTraceBatcher.direction("call_transport", "write_end"))
        assertEquals("outgoing", StageTraceBatcher.direction("call_transport", "send_enqueue"))
        assertEquals("incoming", StageTraceBatcher.direction("call_transport", "receive"))
        assertEquals("incoming", StageTraceBatcher.direction("video_decoder", "release"))
        assertEquals("incoming", StageTraceBatcher.direction("broadcast_receive", "receive"))
    }

    /**
     * A realistic 45 s call at 15 fps: this phone encodes and sends, and receives and decodes. v1 wrote one line per
     * record (about 12 per frame, the measured 150-200 lines/s); v2 must cut the line count at least 8x and bytes 2x.
     */
    @Test
    fun aRealisticCallCutsLogLinesEightfoldAndBytesTwofold() {
        val roles = linkedMapOf(
            "video_encoder" to listOf("raw_capture", "encode_input", "encode_output"),
            "broadcast_transport" to listOf("send_enqueue", "write_begin", "write_end"),
            "broadcast_receive" to listOf("receive"),
            "video_decoder" to listOf("decoder_input", "decoder_output", "release", "surface_ack", "texture_notification"),
        )
        var clock = 314_000_000_000_000L
        val v2Lines = ArrayList<String>()
        val batchers = roles.keys.associateWith { role ->
            StageTraceBatcher("bench-1", "317644621801431", role, 17082, sink = { v2Lines.add(it) }, nowNs = { clock })
        }
        var v1Lines = 0
        var v1Bytes = 0L
        val sequences = HashMap<String, Long>()
        val frames = 45 * 15
        for (frame in 0 until frames) {
            val pts = 1_000_000L + frame * 66_666L
            clock = 314_000_000_000_000L + frame * 66_666_667L
            for ((role, stages) in roles) {
                stages.forEachIndexed { index, stage ->
                    val at = clock + index * 2_300_000L
                    batchers.getValue(role).record(stage, pts, if (stage == "encode_output" || stage == "receive") 2_850 else 0, at, null)
                    val sequence = (sequences[role] ?: 0L) + 1
                    sequences[role] = sequence
                    v1Lines++
                    v1Bytes += v1Line(role, stage, sequence, pts, at, if (stage == "encode_output" || stage == "receive") 2_850 else 0).length + 1
                }
            }
        }
        batchers.values.forEach { it.close() }
        val v2Bytes = v2Lines.sumOf { LOGCAT_PREFIX.length + StageTraceBatcher.utf8Length(it) + 1L }
        println("stage trace 45 s x 15 fps: v1 $v1Lines lines / $v1Bytes bytes; v2 ${v2Lines.size} lines / $v2Bytes bytes; " +
            "lines x%.1f bytes x%.2f".format(v1Lines.toDouble() / v2Lines.size, v1Bytes.toDouble() / v2Bytes))
        assertTrue("lines ${v1Lines}->${v2Lines.size}", v1Lines >= 8 * v2Lines.size)
        assertTrue("bytes ${v1Bytes}->${v2Bytes}", v1Bytes >= 2 * v2Bytes)
        assertFalse(v2Lines.any { StageTraceBatcher.utf8Length(it) > StageTraceBatcher.DEFAULT_MAX_LINE_BYTES })
    }

    /** What the v1 writer logged (org.json insertion order), for the comparison above. */
    private val LOGCAT_PREFIX = "10-09 19:11:21.821 17082 17139 I BluetoothVideo: "

    private fun v1Line(role: String, stage: String, sequence: Long, pts: Long, at: Long, bytes: Int): String =
        LOGCAT_PREFIX + "BENCHMARK stage v1 " +
            "{\"session\":\"bench-1\",\"scope\":\"317644621801431\",\"role\":\"$role\"," +
            "\"direction\":\"${StageTraceBatcher.direction(role, stage)}\",\"pid\":17082,\"clock\":\"System.nanoTime\"," +
            "\"sequence\":$sequence,\"stage\":\"$stage\",\"pts_us\":$pts,\"at_ns\":$at,\"bytes\":$bytes,\"final\":false}"

    // Minimal JSON reader for the tests: objects, arrays, strings, numbers (Long or Double), booleans, null.
    private companion object {
        fun parse(text: String): Any? = Reader(text).run { value().also { assertEquals("trailing text", text.length, pos) } }

        class Reader(private val s: String) {
            var pos = 0
            fun value(): Any? {
                return when (val c = s[pos]) {
                    '{' -> {
                        pos++
                        val map = LinkedHashMap<String, Any?>()
                        if (s[pos] == '}') pos++ else do {
                            val key = string()
                            expect(':')
                            map[key] = value()
                        } while (next() == ',')
                        map
                    }
                    '[' -> {
                        pos++
                        val list = ArrayList<Any?>()
                        if (s[pos] == ']') pos++ else do list.add(value()) while (next() == ',')
                        list
                    }
                    '"' -> string()
                    't' -> {
                        pos += 4
                        true
                    }
                    'f' -> {
                        pos += 5
                        false
                    }
                    'n' -> {
                        pos += 4
                        null
                    }
                    else -> {
                        check(c == '-' || c.isDigit()) { "bad json at $pos" }
                        val start = pos
                        while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
                        val token = s.substring(start, pos)
                        token.toLongOrNull() ?: token.toDouble()
                    }
                }
            }

            private fun next(): Char = s[pos++]
            private fun expect(c: Char) = check(next() == c) { "expected $c at ${pos - 1}" }
            private fun string(): String {
                expect('"')
                val out = StringBuilder()
                while (true) {
                    when (val c = next()) {
                        '"' -> return out.toString()
                        '\\' -> when (val e = next()) {
                            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> out.append(e)
                        }
                        else -> out.append(c)
                    }
                }
            }
        }
    }
}
