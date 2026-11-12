package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFragmentAirtimeTest {
    private val delivered = { kbps: Int -> FragmentRate(kbps, FragmentRateSource.DELIVERED) }

    @After
    fun resetTunable() {
        DebugTunables.callFragmentAirtimeMs = 0
    }

    // ---- sizing math -------------------------------------------------------------------------

    @Test
    fun chunkIsRateTimesAirtimeAndUnknownRateKeepsTheFixedSize() {
        // 400 kbps x 40 ms = 2,000 bytes.
        assertEquals(2_000, CallFragmentAirtime.targetChunkBytes(40, delivered(400)))
        // Unknown rate, zero rate, or the arm off: today's fixed 2 KB.
        assertEquals(CallVideoFragments.FRAGMENT_BYTES, CallFragmentAirtime.targetChunkBytes(40, FragmentRate.UNKNOWN))
        assertEquals(CallVideoFragments.FRAGMENT_BYTES, CallFragmentAirtime.targetChunkBytes(40, delivered(0)))
        assertEquals(CallVideoFragments.FRAGMENT_BYTES, CallFragmentAirtime.targetChunkBytes(0, delivered(900)))
        assertEquals(CallVideoFragments.FRAGMENT_BYTES, CallFragmentAirtime.targetChunkBytes(-5, delivered(900)))
    }

    @Test
    fun verySlowLinksClampToTheMinimumThatBoundsOverhead() {
        // 20 kbps x 40 ms = 100 bytes, far below the minimum; 65 kbps (a weak A15s link) = 325 bytes.
        assertEquals(CallFragmentAirtime.MIN_CHUNK_BYTES, CallFragmentAirtime.targetChunkBytes(40, delivered(20)))
        assertEquals(CallFragmentAirtime.MIN_CHUNK_BYTES, CallFragmentAirtime.targetChunkBytes(40, delivered(65)))
        assertEquals(CallFragmentAirtime.MIN_CHUNK_BYTES, CallFragmentAirtime.targetChunkBytes(1, delivered(1)))
    }

    @Test
    fun fastLinksClampToTheMaximumAndNeverOverflow() {
        assertEquals(CallFragmentAirtime.MAX_CHUNK_BYTES, CallFragmentAirtime.targetChunkBytes(40, delivered(2_000)))
        assertEquals(CallFragmentAirtime.MAX_CHUNK_BYTES, CallFragmentAirtime.targetChunkBytes(250, delivered(Int.MAX_VALUE)))
    }

    @Test
    fun chunkGrowsWithRateAndAirtimeWithinTheClamp() {
        var previous = 0
        for (kbps in 1..3_000 step 7) {
            val chunk = CallFragmentAirtime.targetChunkBytes(40, delivered(kbps))
            assertTrue("rate $kbps", chunk >= previous)
            assertTrue(chunk in CallFragmentAirtime.MIN_CHUNK_BYTES..CallFragmentAirtime.MAX_CHUNK_BYTES)
            previous = chunk
        }
        assertTrue(
            CallFragmentAirtime.targetChunkBytes(80, delivered(300)) > CallFragmentAirtime.targetChunkBytes(20, delivered(300)),
        )
    }

    @Test
    fun anUnverifiedProbeCanOnlyShrinkTheFixedSize() {
        fun probe(kbps: Int) = FragmentRate(kbps, FragmentRateSource.PROBE)
        // A fast probe (taken with the phone's best peer) never grows past today's 2 KB for this peer.
        assertEquals(CallVideoFragments.FRAGMENT_BYTES, CallFragmentAirtime.targetChunkBytes(40, probe(1_400)))
        // A slow probe shrinks it: 150 kbps x 40 ms = 750.
        assertEquals(750, CallFragmentAirtime.targetChunkBytes(40, probe(150)))
        // The same rate measured on this call's link may exceed 2 KB.
        assertEquals(4_096, CallFragmentAirtime.targetChunkBytes(40, delivered(1_400)))
    }

    @Test
    fun rateSourceOrderIsDeliveredThenArrivalThenProbeThenUnknown() {
        assertEquals(FragmentRate(500, FragmentRateSource.DELIVERED), CallFragmentAirtime.pickRate(500, 900, 300))
        assertEquals(FragmentRate(900, FragmentRateSource.ARRIVAL), CallFragmentAirtime.pickRate(0, 900, 300))
        assertEquals(FragmentRate(300, FragmentRateSource.PROBE), CallFragmentAirtime.pickRate(0, 0, 300))
        assertEquals(FragmentRate.UNKNOWN, CallFragmentAirtime.pickRate(0, 0, null))
        assertEquals(FragmentRate.UNKNOWN, CallFragmentAirtime.pickRate(0, 0, 0))
    }

    @Test
    fun targetIsCappedAndNegativeIsOff() {
        assertEquals(0, CallFragmentAirtime.clampTargetMs(-3))
        assertEquals(40, CallFragmentAirtime.clampTargetMs(40))
        assertEquals(CallFragmentAirtime.MAX_TARGET_MS, CallFragmentAirtime.clampTargetMs(100_000))
        DebugTunables.callFragmentAirtimeMs = 100_000
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extra: the experiment stays off (0, the fixed 2 KB fragments).
            assertEquals(0, DebugTunables.callFragmentAirtimeMs)
            return
        }
        assertEquals(CallFragmentAirtime.MAX_TARGET_MS, DebugTunables.callFragmentAirtimeMs)
        DebugTunables.callFragmentAirtimeMs = -1
        assertEquals(0, DebugTunables.callFragmentAirtimeMs)
    }

    // ---- overhead ----------------------------------------------------------------------------

    @Test
    fun perFragmentOverheadMatchesTheRealRecordsOnTheWire() {
        val key = ByteArray(32) { (it + 3).toByte() }
        val out = ByteArrayOutputStream()
        val writer = SecureRecordWriter(out, key)
        val sealer = EndToEndContentSealer(key)
        for (chunk in listOf(1, 675, 2_048, 4_096)) {
            val fragment = CallVideoFragments.fragmentAt(ByteArray(chunk) { it.toByte() }, 0, chunk)
            val wireBytes = EndToEndStreamProtocol.writeVideoFragment(writer, sealer, StreamProtocol.FLAG_KEY_FRAME, fragment)
            assertEquals("chunk $chunk", chunk + CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES, wireBytes)
        }
        assertEquals(out.size(), 4 * CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES + 1 + 675 + 2_048 + 4_096)
        // 17 hop header + 16 tag + 18 endpoint header + 16 tag + 8 fragment header.
        assertEquals(75, CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES)
    }

    @Test
    fun theMinimumChunkIsTheSmallestSizeThatKeepsOverheadAtOrBelowTenPercent() {
        val limit = CallFragmentAirtime.MAX_OVERHEAD_PERCENT.toDouble()
        assertTrue(CallFragmentAirtime.overheadPercent(CallFragmentAirtime.MIN_CHUNK_BYTES) <= limit)
        assertTrue(CallFragmentAirtime.overheadPercent(CallFragmentAirtime.MIN_CHUNK_BYTES - 1) > limit)
        assertEquals(675, CallFragmentAirtime.MIN_CHUNK_BYTES)
        // The fixed 2 KB costs 3.5%, the largest chunk 1.8%.
        assertEquals(3.5, CallFragmentAirtime.overheadPercent(CallVideoFragments.FRAGMENT_BYTES), 0.05)
        assertEquals(1.8, CallFragmentAirtime.overheadPercent(CallFragmentAirtime.MAX_CHUNK_BYTES), 0.05)
    }

    @Test
    fun everyChunkOfEveryFrameStaysWithinTheOverheadBoundAndTheAirtimeTarget() {
        val min = CallFragmentAirtime.MIN_CHUNK_BYTES
        for (target in min..CallFragmentAirtime.MAX_CHUNK_BYTES step 5) {
            var total = CallFragmentAirtime.wholePacketLimitBytes(target) + 1
            while (total < 12_000) {
                var remaining = total
                var parts = 0
                while (remaining > 0) {
                    val chunk = CallFragmentAirtime.nextChunkBytes(remaining, target)
                    assertTrue("target $target total $total chunk $chunk", chunk in min..remaining)
                    // At most the target, except near the minimum where fewer, larger chunks keep the overhead bound.
                    assertTrue("target $target total $total chunk $chunk", chunk <= maxOf(target, 2 * min - 1))
                    remaining -= chunk
                    parts++
                }
                // The fewest chunks of at most the target (or, near the minimum, fewer).
                assertTrue(parts <= (total + target - 1) / target)
                total += 97
            }
        }
    }

    @Test
    fun evenSplitLeavesNoRuntTail() {
        // 4,100 bytes at a 2,000-byte target: three near-equal chunks, not 2,000 + 2,000 + 100.
        val chunks = chunkSizes(4_100, 2_000)
        assertEquals(listOf(1_367, 1_367, 1_366), chunks)
        assertEquals(listOf(1_500), chunkSizes(1_500, 2_000))
    }

    // ---- when to fragment --------------------------------------------------------------------

    @Test
    fun offMatchesTheFixedArmsWholePacketRule() {
        for (size in listOf(1, 3_071, 3_072, 3_073, 9_000, 200_000)) {
            for (flags in listOf(0, StreamProtocol.FLAG_KEY_FRAME, StreamProtocol.FLAG_AUDIO, StreamProtocol.FLAG_CODEC_CONFIG)) {
                assertEquals(
                    "size $size flags $flags",
                    CallVideoFragments.shouldFragment(size, flags),
                    CallFragmentAirtime.shouldFragment(size, flags, 0, delivered(300)),
                )
            }
        }
        // An unknown rate with the arm on keeps the same 3 KB threshold (1.5 x the fixed 2 KB).
        assertEquals(CallVideoFragments.MIN_FRAGMENTED_PACKET_BYTES, CallFragmentAirtime.wholePacketLimitBytes(CallVideoFragments.FRAGMENT_BYTES))
        assertFalse(CallFragmentAirtime.shouldFragment(3_072, 0, 40, FragmentRate.UNKNOWN))
        assertTrue(CallFragmentAirtime.shouldFragment(3_073, 0, 40, FragmentRate.UNKNOWN))
    }

    @Test
    fun theWholePacketLimitFollowsTheChunkAndAudioIsNeverFragmented() {
        // Slow link: 675-byte chunks, so a 1.2 KB frame is already a long write and is cut.
        assertTrue(CallFragmentAirtime.shouldFragment(1_200, 0, 40, delivered(65)))
        assertFalse(CallFragmentAirtime.shouldFragment(1_000, 0, 40, delivered(65)))
        // Fast link: 4 KB chunks, so a 5 KB frame still goes whole.
        assertFalse(CallFragmentAirtime.shouldFragment(5_000, 0, 40, delivered(1_400)))
        assertTrue(CallFragmentAirtime.shouldFragment(6_200, 0, 40, delivered(1_400)))
        assertFalse(CallFragmentAirtime.shouldFragment(50_000, StreamProtocol.FLAG_AUDIO, 40, delivered(65)))
        assertFalse(CallFragmentAirtime.shouldFragment(50_000, StreamProtocol.FLAG_CODEC_CONFIG, 40, delivered(65)))
    }

    // ---- rate estimate -----------------------------------------------------------------------

    @Test
    fun deliveredRatePeakCoversTenSecondsThenExpires() {
        var nowNs = 5_000_000_000L
        val estimator = CallFragmentRateEstimator { nowNs }
        assertEquals(0, estimator.peakKbps())
        estimator.onDelivered(0)
        estimator.onDelivered(-4)
        assertEquals(0, estimator.peakKbps())
        estimator.onDelivered(420)
        nowNs += 1_000_000_000L
        estimator.onDelivered(150)
        assertEquals(420, estimator.peakKbps())
        nowNs += 8_000_000_000L // 9 s after the 420 sample
        assertEquals(420, estimator.peakKbps())
        nowNs += 1_000_000_000L // 10 s after it: gone, the 150 sample (9 s old) remains
        assertEquals(150, estimator.peakKbps())
        nowNs += 1_000_000_000L
        assertEquals(0, estimator.peakKbps())
    }

    // ---- statistics --------------------------------------------------------------------------

    @Test
    fun statsWindowReportsSizesOverheadAndAudioWaitThenResets() {
        var nowNs = 0L
        val stats = CallFragmentStats { nowNs }
        assertNull(stats.takeWindow(1_000_000_000L))
        stats.onFragment(1_000, delivered(300))
        stats.onFragment(1_200, delivered(310))
        stats.onFrameDone()
        stats.onAudio(12)
        stats.onAudio(30)
        nowNs = 500_000_000L
        assertNull("window too young", stats.takeWindow(1_000_000_000L))
        nowNs = 1_000_000_000L
        val window = assertNotNull(stats.takeWindow(1_000_000_000L))
        assertEquals(1_000L, window.windowMs)
        assertEquals(2L, window.fragments)
        assertEquals(1L, window.frames)
        assertEquals(1_000, window.chunkMin)
        assertEquals(1_200, window.chunkMax)
        assertEquals(1_200, window.chunkLast)
        assertEquals(2_200L, window.payloadBytes)
        assertEquals(150L, window.overheadBytes)
        assertEquals(100.0 * 150 / 2_350, window.overheadPercent, 0.001)
        assertEquals(30L, window.audioWaitMaxMs)
        val line = window.benchmarkLine("call-1", 40)
        for (field in listOf(
            "BENCHMARK call_fragment_airtime:", "mode=airtime", "targetMs=40", "rateKbps=310", "rateSource=delivered",
            "fragments=2", "fragmentsPerSec=2.0", "chunkBytesLast=1200", "overheadBytes=150", "overheadPct=6.4",
            "audioInterleaved=2", "audioWaitMaxMs=30", "audioWaitAvgMs=21",
        )) {
            assertTrue("$field in $line", field in line)
        }
        assertTrue("mode=fixed" in window.benchmarkLine("call-1", 0))
        // The next window starts empty: no fragmented frame, no line.
        nowNs = 2_100_000_000L
        assertNull(stats.takeWindow(1_000_000_000L))
    }

    // ---- writer: interleaving with a fake link that models airtime ---------------------------

    @Test
    fun noFragmentStartsWhileAudioWaitsAndAudioWaitStaysNearTheTarget() {
        for (kbps in listOf(100, 150, 300, 600, 1_000)) {
            val link = FakeLink(kbps)
            val writer = link.writer(targetMs = 40, rate = { delivered(kbps) }, assertNoAudioWaiting = true)
            writer.writeFrame(link.packet(60_000), StreamProtocol.FLAG_KEY_FRAME)
            val fragmentMs = (link.fragmentChunks.max() + CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES) * 8.0 / kbps
            assertTrue("rate $kbps: some audio was interleaved", link.audioWaitsMs.isNotEmpty())
            // Audio waits behind at most one fragment (plus the audio packets drained before it).
            val bound = fragmentMs + 15.0
            assertTrue("rate $kbps: max wait ${link.audioWaitsMs.max()} ms vs bound $bound", link.audioWaitsMs.max() <= bound)
            // Where the clamp does not bind, one fragment is about the 40 ms target of link time.
            if (kbps >= 150) assertTrue("rate $kbps: fragment $fragmentMs ms", fragmentMs <= 50.0)
            // Reassembles to the original frame.
            assertArrayEquals(link.lastPacket, link.reassemble())
        }
    }

    @Test
    fun airtimeSizingCutsTheAudioWaitOnASlowLinkVersusFixedTwoKilobyteFragments() {
        val fixed = FakeLink(100)
        fixed.writer(targetMs = 0, rate = { FragmentRate.UNKNOWN }).writeFrame(fixed.packet(40_000), 0)
        val airtime = FakeLink(100)
        airtime.writer(targetMs = 40, rate = { delivered(100) }, assertNoAudioWaiting = true).writeFrame(airtime.packet(40_000), 0)
        // Fixed: (2,048 + 75) bytes at 100 kbps is 170 ms per write. Airtime: the 675-byte minimum, 60 ms.
        assertTrue("fixed ${fixed.audioWaitsMs.max()}", fixed.audioWaitsMs.max() >= 150)
        assertTrue("airtime ${airtime.audioWaitsMs.max()}", airtime.audioWaitsMs.max() <= 75)
        // The price is bounded: framing overhead, as a share of this frame's wire bytes, stays within 10%.
        val overhead = airtime.fragmentChunks.size * CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES
        val wire = airtime.fragmentChunks.sum() + overhead
        assertTrue("overhead ${100.0 * overhead / wire}%", overhead * 100 <= wire * CallFragmentAirtime.MAX_OVERHEAD_PERCENT)
    }

    @Test
    fun audioIsWrittenBeforeTheFirstFragmentAndBetweenEveryPairInOrder() {
        val link = FakeLink(300)
        link.queueAudioNow(1)
        link.queueAudioNow(2)
        val writer = link.writer(targetMs = 40, rate = { delivered(300) }, assertNoAudioWaiting = true)
        writer.writeFrame(link.packet(9_000), 0)
        // Both packets queued before the frame go first, oldest first, then a flush, then fragment 0.
        assertEquals(listOf("A1", "A2", "flush", "F0"), link.events.take(4))
        val fragmentIndexes = link.events.indices.filter { link.events[it].startsWith("F") }
        assertTrue(fragmentIndexes.size >= 4)
        // Every gap between two fragments flushes first, and no flush follows the last fragment.
        fragmentIndexes.dropLast(1).forEach { assertEquals("flush", link.events[it + 1]) }
        assertEquals(fragmentIndexes.last(), link.events.lastIndex)
        // Audio ids stay in arrival order.
        val audioIds = link.events.filter { it.startsWith("A") }.map { it.drop(1).toInt() }
        assertEquals(audioIds.sorted(), audioIds)
        assertTrue(audioIds.size > 2)
    }

    @Test
    fun theRateIsReadForEveryFragmentSoALongKeyframeAdaptsMidFrame() {
        val link = FakeLink(400)
        var calls = 0
        val writer = link.writer(targetMs = 40, rate = {
            calls++
            if (calls <= 3) delivered(100) else delivered(1_000)
        })
        writer.writeFrame(link.packet(50_000), StreamProtocol.FLAG_KEY_FRAME)
        // The first fragments follow the slow estimate (about the 675-byte minimum); later ones the fast one (near 4 KB).
        val minimum = CallFragmentAirtime.MIN_CHUNK_BYTES
        assertTrue(link.fragmentChunks.take(3).all { it in minimum..minimum + 50 })
        assertTrue(link.fragmentChunks.drop(3).all { it > 2 * minimum })
        assertArrayEquals(link.lastPacket, link.reassemble())
    }

    @Test
    fun fixedModeReproducesTheOriginalLoopByteForByte() {
        for (size in listOf(3_073, 5_000, 30_000, 123_457)) {
            val newLink = FakeLink(250)
            val oldLink = FakeLink(250)
            val packet = newLink.packet(size)
            // The writer as it ran before the airtime arm existed, copied here as the reference.
            val fragments = CallVideoFragments.split(packet)
            fragments.forEachIndexed { index, fragment ->
                oldLink.writeFragment(StreamProtocol.FLAG_KEY_FRAME, fragment)
                if (index < fragments.lastIndex) {
                    oldLink.flush()
                    var written = 0L
                    while (true) {
                        val audio = oldLink.takeAudio() ?: break
                        written += oldLink.writeAudio(audio)
                    }
                    if (written > 0L) oldLink.flush()
                }
            }
            newLink.writer(targetMs = 0, rate = { FragmentRate.UNKNOWN }).writeFrame(packet, StreamProtocol.FLAG_KEY_FRAME)
            assertEquals("size $size events", oldLink.events, newLink.events)
            assertArrayEquals("size $size sealed bytes", oldLink.wireBytes(), newLink.wireBytes())
            assertEquals(fragments.map { it.size - CallVideoFragments.HEADER_BYTES }, newLink.fragmentChunks)
            assertTrue(newLink.fragmentChunks.dropLast(1).all { it == CallVideoFragments.FRAGMENT_BYTES })
        }
    }

    @Test
    fun airtimeRecordsOpenWithTheUnchangedEndpointOpenerAndReassembler() {
        val key = ByteArray(32) { (it * 5).toByte() }
        val sealer = EndToEndContentSealer(key)
        val opener = EndToEndContentOpener(key).also { it.acceptVideoFragments = true }
        val reassembler = CallVideoReassembler()
        val flags = StreamProtocol.FLAG_KEY_FRAME
        val plaintext = EndToEndStreamProtocol.encodePacket(flags, 77L, ByteArray(40_000) { (it * 13).toByte() })
        val sealed = ArrayList<ByteArray>()
        val link = FakeLink(500)
        var chunkSeen = 0
        link.writer(
            targetMs = 40,
            rate = { delivered(if (chunkSeen++ % 2 == 0) 300 else 800) },
            onFragment = { f, fragment -> sealed += sealer.seal(EndToEndContentType.VIDEO_FRAGMENT, f, fragment) },
        ).writeFrame(plaintext, flags)
        var complete: ByteArray? = null
        sealed.forEach { record ->
            val opened = opener.open(record)
            complete = reassembler.accept(opened.flags, opened.payload) ?: complete
        }
        assertArrayEquals(plaintext, complete)
        assertEquals(77L, EndToEndStreamProtocol.decodeReassembledPacket(flags, requireNotNull(complete)).presentationTimeUs)
        // Sizes differed between fragments, which the receiver never checks.
        assertTrue(sealed.map { it.size }.toSet().size > 2)
    }

    @Test
    fun afterTheCallStoppedNoAudioOrGapFlushIsWrittenAndTheFrameStaysWhole() {
        val link = FakeLink(300)
        link.queueAudioNow(1)
        val writer = link.writer(targetMs = 40, rate = { delivered(300) }, running = { false })
        writer.writeFrame(link.packet(20_000), 0)
        assertTrue(link.events.none { it.startsWith("A") || it == "flush" })
        assertTrue(link.events.count { it.startsWith("F") } >= 4)
        assertArrayEquals(link.lastPacket, link.reassemble())
    }

    @Test
    fun anAbandonedFrameStopsAfterTheCurrentFragment() {
        val link = FakeLink(300)
        var fragments = 0
        val writer = CallFragmentWriter<FakeAudio>(
            targetMs = 0,
            rate = { FragmentRate.UNKNOWN },
            stats = CallFragmentStats { link.nowNs },
            nanoTime = { link.nowNs },
            running = { true },
            writeFragment = { flags, fragment ->
                fragments++
                link.writeFragment(flags, fragment)
            },
            takeAudio = { link.takeAudio() },
            audioQueuedAtNs = FakeAudio::queuedAtNs,
            writeAudio = { link.writeAudio(it) },
            flushBetween = { link.flush() },
            abandon = { fragments >= 2 },
        )
        val packet = link.packet(20_000)
        writer.writeFrame(packet, 0)
        // The cutover dropped the rest of this frame: the next frame starts at offset 0.
        assertEquals(2, fragments)
        assertTrue(fragments < CallVideoFragments.split(packet).size)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun chunkSizes(total: Int, target: Int): List<Int> {
        val chunks = ArrayList<Int>()
        var remaining = total
        while (remaining > 0) {
            val chunk = CallFragmentAirtime.nextChunkBytes(remaining, target)
            chunks += chunk
            remaining -= chunk
        }
        return chunks
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private class FakeAudio(val id: Int, val queuedAtNs: Long, val payloadBytes: Int)

    /** A link whose writes take wire bytes x 8 / kbps of virtual time; 20 ms audio packets keep arriving. */
    private class FakeLink(private val kbps: Int) {
        var nowNs = 0L
        val events = ArrayList<String>()
        val audioWaitsMs = ArrayList<Long>()
        val fragmentChunks = ArrayList<Int>()
        var lastPacket = ByteArray(0)
        private val queue = ArrayDeque<FakeAudio>()
        private var nextArrivalNs = 10_000_000L
        private var nextAudioId = 100
        private val key = ByteArray(32) { (it + 1).toByte() }
        private val out = ByteArrayOutputStream()
        private val hop = SecureRecordWriter(out, key)
        private val sealer = EndToEndContentSealer(key.reversedArray())
        private val received = ByteArrayOutputStream()

        fun packet(size: Int): ByteArray {
            lastPacket = EndToEndStreamProtocol.encodePacket(
                StreamProtocol.FLAG_KEY_FRAME,
                1_000L,
                ByteArray(size) { (it * 31 + 7).toByte() },
            )
            return lastPacket
        }

        fun queueAudioNow(id: Int) {
            queue.addLast(FakeAudio(id, nowNs, 80))
        }

        private fun advance(wireBytes: Int) {
            nowNs += wireBytes * 8_000_000L / kbps
            while (nextArrivalNs <= nowNs) {
                queue.addLast(FakeAudio(nextAudioId++, nextArrivalNs, 80))
                nextArrivalNs += 20_000_000L
            }
        }

        fun writeFragment(flags: Int, fragment: ByteArray) {
            val offset = ByteBuffer.wrap(fragment, 4, 4).int
            val chunk = fragment.size - CallVideoFragments.HEADER_BYTES
            events += "F$offset"
            fragmentChunks += chunk
            received.write(fragment, CallVideoFragments.HEADER_BYTES, chunk)
            val wire = EndToEndStreamProtocol.writeVideoFragment(hop, sealer, flags, fragment)
            advance(wire)
        }

        fun takeAudio(): FakeAudio? = queue.removeFirstOrNull()

        fun writeAudio(audio: FakeAudio): Long {
            events += "A${audio.id}"
            val wire = EndToEndStreamProtocol.writePacket(hop, sealer, StreamProtocol.FLAG_AUDIO, 1L, ByteArray(audio.payloadBytes))
            advance(wire)
            return audio.payloadBytes.toLong()
        }

        fun flush() {
            events += "flush"
        }

        fun wireBytes(): ByteArray = out.toByteArray()

        fun reassemble(): ByteArray = received.toByteArray()

        fun writer(
            targetMs: Int,
            rate: () -> FragmentRate,
            assertNoAudioWaiting: Boolean = false,
            running: () -> Boolean = { true },
            onFragment: ((Int, ByteArray) -> Unit)? = null,
        ): CallFragmentWriter<FakeAudio> = CallFragmentWriter(
            targetMs = targetMs,
            rate = rate,
            stats = CallFragmentStats { nowNs },
            nanoTime = { nowNs },
            running = running,
            writeFragment = { flags, fragment ->
                if (assertNoAudioWaiting) {
                    // No fragment starts while call audio is queued.
                    org.junit.Assert.assertTrue("audio waiting at fragment start: ${queue.map { it.id }}", queue.isEmpty())
                }
                onFragment?.invoke(flags, fragment)
                writeFragment(flags, fragment)
            },
            takeAudio = {
                takeAudio()?.also { audioWaitsMs += (nowNs - it.queuedAtNs) / 1_000_000L }
            },
            audioQueuedAtNs = { it.queuedAtNs },
            writeAudio = { writeAudio(it) },
            flushBetween = { flush() },
        )
    }
}
