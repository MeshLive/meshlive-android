package com.example.bluetoothtest

import java.util.Locale

/**
 * Experimental airtime-sized call video fragments (default off; DEBUG `test_call_fragment_airtime_ms`,
 * benchmark tunable `call_fragment_airtime_ms`; 0 = off = the fixed 2 KB fragments of
 * [CallVideoFragments]). Needs `test_call_video_fragments`.
 *
 * Why (external speech review 2026-10-11, calls-noloss-m31asus-51cc8028): a GATT write blocks for the
 * air time of its whole record, so call audio waited 620-1193 ms behind one video frame, and a larger
 * audio headroom cannot shorten a video write. Fixed 2 KB fragments still occupy about 80-160 ms of a
 * slow GATT link per write. Here each fragment carries about `rate x targetAirtime` payload bytes, so
 * the time call audio waits behind one video write stays near the target at any link rate.
 *
 * No wire change: a fragment already names its frame total and offset, and the receiver
 * ([CallVideoReassembler]) accepts any positive chunk length, so the sender alone picks sizes. Chunk
 * sizes differ per frame (and per fragment while the rate estimate changes), which is traffic
 * metadata exactly as fixed sizes were.
 *
 * Overhead: every fragment adds [CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES] bytes of hop and
 * endpoint record framing and authentication tags. [CallFragmentAirtime.MIN_CHUNK_BYTES] keeps that
 * at or below [CallFragmentAirtime.MAX_OVERHEAD_PERCENT] percent of every fragment, so a very slow
 * link gets fragments longer than the target airtime rather than more overhead.
 *
 * Rate: this call link's own evidence only, never a guess (see [CallFragmentAirtime.pickRate]).
 * Without any, fragments stay at the fixed 2 KB.
 */
internal enum class FragmentRateSource(val label: String) {
    NONE("none"),

    /** Peak goodput the peer reported receiving on this call link (link report version 3), recent 10 s. */
    DELIVERED("delivered"),

    /** The peer's arrival-train estimate for this link (only exists before fragments start). */
    ARRIVAL("arrival"),

    /** This phone's learned single-link probe; unverified for this peer, so it may only shrink fragments. */
    PROBE("probe"),
}

internal data class FragmentRate(val kbps: Int, val source: FragmentRateSource) {
    companion object {
        val UNKNOWN = FragmentRate(0, FragmentRateSource.NONE)
    }
}

internal object CallFragmentAirtime {
    /** The A/B arm's target: about 40 ms of link time per fragment. */
    const val DEFAULT_TARGET_MS = 40

    /** Sanity cap for the launch extra (a 250 ms write is already a fixed-2 KB-on-GATT result). */
    const val MAX_TARGET_MS = 250

    /** Hop record (`BMS1`): magic 4, type 1, sequence 8, ciphertext length 4. Verified against the writer in tests. */
    const val HOP_RECORD_HEADER_BYTES = 17

    /**
     * Bytes one fragment adds around its chunk: hop record header and tag, endpoint record (`BME1`)
     * header and tag, and the 8-byte fragment header. (The 13-byte `BTV3` packet header is paid once per frame.)
     */
    const val PER_FRAGMENT_OVERHEAD_BYTES =
        HOP_RECORD_HEADER_BYTES + MeshAead.TAG_BYTES + EndToEndContentProtocol.HEADER_BYTES + MeshAead.TAG_BYTES +
            CallVideoFragments.HEADER_BYTES

    /** Largest share of a fragment's wire bytes that framing and tags may take. */
    const val MAX_OVERHEAD_PERCENT = 10

    /** Smallest chunk that keeps [MAX_OVERHEAD_PERCENT]: overhead / (chunk + overhead) <= 10% needs chunk >= 9 x overhead. */
    const val MIN_CHUNK_BYTES =
        (PER_FRAGMENT_OVERHEAD_BYTES * (100 - MAX_OVERHEAD_PERCENT) + MAX_OVERHEAD_PERCENT - 1) / MAX_OVERHEAD_PERCENT

    /** Largest chunk: a fast link needs no more than this per write (about 23 ms at 1.4 Mbps). */
    const val MAX_CHUNK_BYTES = 4_096

    /** Window of the delivered-rate peak. */
    const val RATE_WINDOW_S = 10

    fun clampTargetMs(requested: Int): Int = requested.coerceIn(0, MAX_TARGET_MS)

    /**
     * The best rate evidence for this call link: the recent peak goodput the peer reported (counted in
     * bytes, so it survives small fragments), else the peer's arrival-train estimate, else this phone's
     * learned probe. [FragmentRate.UNKNOWN] when there is none.
     */
    fun pickRate(peakDeliveredKbps: Int, arrivalKbps: Int, probeKbps: Int?): FragmentRate = when {
        peakDeliveredKbps > 0 -> FragmentRate(peakDeliveredKbps, FragmentRateSource.DELIVERED)
        arrivalKbps > 0 -> FragmentRate(arrivalKbps, FragmentRateSource.ARRIVAL)
        probeKbps != null && probeKbps > 0 -> FragmentRate(probeKbps, FragmentRateSource.PROBE)
        else -> FragmentRate.UNKNOWN
    }

    /**
     * Payload bytes per fragment for [targetMs] of link time at [rate]: `kbps x ms / 8`, clamped to
     * [MIN_CHUNK_BYTES]..[MAX_CHUNK_BYTES]. Off ([targetMs] 0) or without a rate: the fixed
     * [CallVideoFragments.FRAGMENT_BYTES]. A probe is not this peer's rate, so it can only shrink the
     * fixed size.
     */
    fun targetChunkBytes(targetMs: Int, rate: FragmentRate): Int {
        if (targetMs <= 0 || rate.kbps <= 0) return CallVideoFragments.FRAGMENT_BYTES
        val raw = (rate.kbps.toLong() * targetMs / 8).coerceAtMost(MAX_CHUNK_BYTES.toLong()).toInt()
        val bounded = if (rate.source == FragmentRateSource.PROBE) minOf(raw, CallVideoFragments.FRAGMENT_BYTES) else raw
        return bounded.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
    }

    /** Packets up to this size go whole: 1.5 x the chunk, which is today's 3 KB for the fixed 2 KB. */
    fun wholePacketLimitBytes(targetChunk: Int): Int = targetChunk + targetChunk / 2

    /** True when [packetPlaintextBytes] of a video packet should be fragmented. Off: exactly [CallVideoFragments.shouldFragment]. */
    fun shouldFragment(packetPlaintextBytes: Int, flags: Int, targetMs: Int, rate: FragmentRate): Boolean =
        if (targetMs <= 0) {
            CallVideoFragments.shouldFragment(packetPlaintextBytes, flags)
        } else {
            flags and (StreamProtocol.FLAG_AUDIO or StreamProtocol.FLAG_CODEC_CONFIG) == 0 &&
                packetPlaintextBytes > wholePacketLimitBytes(targetChunkBytes(targetMs, rate))
        }

    /**
     * The next chunk of a frame with [remaining] bytes left, aiming at [target]: the rest in one chunk
     * when it fits, else an even split of the rest into the fewest chunks of at most [target]. Even
     * splitting leaves no runt tail. Each chunk stays at or above [MIN_CHUNK_BYTES] (fewer, larger
     * chunks win over a small one), so chunks are at most max([target], 2 x [MIN_CHUNK_BYTES] - 1).
     */
    fun nextChunkBytes(remaining: Int, target: Int): Int {
        require(remaining > 0 && target > 0) { "Invalid chunk request $remaining/$target" }
        if (remaining <= target) return remaining
        var parts = (remaining + target - 1) / target
        if (remaining / parts < MIN_CHUNK_BYTES) parts = maxOf(1, remaining / MIN_CHUNK_BYTES)
        return (remaining + parts - 1) / parts
    }

    /** Share of a fragment's wire bytes that is framing and tags, in percent. */
    fun overheadPercent(chunkBytes: Int): Double =
        100.0 * PER_FRAGMENT_OVERHEAD_BYTES / (chunkBytes + PER_FRAGMENT_OVERHEAD_BYTES)
}

/**
 * Peak of the delivery goodput the peer reported over the last [CallFragmentAirtime.RATE_WINDOW_S]
 * seconds. Goodput is the rate the link actually delivered, so while a large frame drains back to
 * back its peak is the link's rate; between bursts a plain latest value would read only the offered
 * load. Fed from the reader thread (each peer report), read by the writer: synchronized.
 */
internal class CallFragmentRateEstimator(private val nanoTime: () -> Long = System::nanoTime) {
    private val bucketKbps = IntArray(CallFragmentAirtime.RATE_WINDOW_S)
    private val bucketSecond = LongArray(CallFragmentAirtime.RATE_WINDOW_S) { Long.MIN_VALUE }

    @Synchronized
    fun onDelivered(kbps: Int) {
        if (kbps <= 0) return
        val second = Math.floorDiv(nanoTime(), 1_000_000_000L)
        val index = Math.floorMod(second, CallFragmentAirtime.RATE_WINDOW_S.toLong()).toInt()
        bucketKbps[index] = if (bucketSecond[index] == second) maxOf(bucketKbps[index], kbps) else kbps
        bucketSecond[index] = second
    }

    /** Largest value reported in the window, 0 when none. */
    @Synchronized
    fun peakKbps(): Int {
        val second = Math.floorDiv(nanoTime(), 1_000_000_000L)
        var peak = 0
        for (index in bucketKbps.indices) {
            if (bucketKbps[index] > 0 && second - bucketSecond[index] < CallFragmentAirtime.RATE_WINDOW_S) {
                peak = maxOf(peak, bucketKbps[index])
            }
        }
        return peak
    }
}

/** One reporting window of [CallFragmentStats]. */
internal data class CallFragmentWindow(
    val windowMs: Long,
    val fragments: Long,
    val frames: Long,
    val payloadBytes: Long,
    val chunkMin: Int,
    val chunkMax: Int,
    val chunkLast: Int,
    val audioInterleaved: Long,
    val audioWaitMaxMs: Long,
    val audioWaitSumMs: Long,
    val rate: FragmentRate,
) {
    val overheadBytes: Long get() = fragments * CallFragmentAirtime.PER_FRAGMENT_OVERHEAD_BYTES

    /** Framing and tags as a share of the window's fragment wire bytes. */
    val overheadPercent: Double
        get() = if (payloadBytes + overheadBytes == 0L) 0.0 else 100.0 * overheadBytes / (payloadBytes + overheadBytes)

    val fragmentsPerSecond: Double get() = if (windowMs <= 0L) 0.0 else fragments * 1000.0 / windowMs

    fun benchmarkLine(callId: String?, targetMs: Int): String = String.format(
        Locale.ROOT,
        "BENCHMARK call_fragment_airtime: role=call_tx | callId=%s | mode=%s | targetMs=%d | windowMs=%d | " +
            "rateKbps=%d | rateSource=%s | fragments=%d | fragmentsPerSec=%.1f | frames=%d | chunkBytesLast=%d | " +
            "chunkBytesMin=%d | chunkBytesMax=%d | payloadBytes=%d | overheadBytes=%d | overheadPct=%.1f | " +
            "audioInterleaved=%d | audioWaitMaxMs=%d | audioWaitAvgMs=%d",
        callId, if (targetMs > 0) "airtime" else "fixed", targetMs, windowMs,
        rate.kbps, rate.source.label, fragments, fragmentsPerSecond, frames, chunkLast,
        chunkMin, chunkMax, payloadBytes, overheadBytes, overheadPercent,
        audioInterleaved, audioWaitMaxMs, if (audioInterleaved == 0L) 0L else audioWaitSumMs / audioInterleaved,
    )
}

/**
 * Per-window counters of the fragment writer (one window about a second): fragment sizes used,
 * fragments, framing overhead and how long queued call audio waited behind video before it was
 * written between two fragments. Only the writer thread records; the call teardown may also drain.
 */
internal class CallFragmentStats(private val nanoTime: () -> Long = System::nanoTime) {
    private var windowStartNs = nanoTime()
    private var fragments = 0L
    private var frames = 0L
    private var payloadBytes = 0L
    private var chunkMin = Int.MAX_VALUE
    private var chunkMax = 0
    private var chunkLast = 0
    private var audioInterleaved = 0L
    private var audioWaitMaxMs = 0L
    private var audioWaitSumMs = 0L
    private var rate = FragmentRate.UNKNOWN

    @Synchronized
    fun onFragment(chunkBytes: Int, rate: FragmentRate) {
        fragments++
        payloadBytes += chunkBytes
        chunkMin = minOf(chunkMin, chunkBytes)
        chunkMax = maxOf(chunkMax, chunkBytes)
        chunkLast = chunkBytes
        this.rate = rate
    }

    @Synchronized
    fun onFrameDone() {
        frames++
    }

    @Synchronized
    fun onAudio(waitMs: Long) {
        audioInterleaved++
        audioWaitSumMs += waitMs
        audioWaitMaxMs = maxOf(audioWaitMaxMs, waitMs)
    }

    /**
     * The finished window once [minWindowNs] has passed (or [force]), and starts the next. Null when
     * the window is too young, or when it saw no fragment (an absent line means no fragmented frame).
     */
    @Synchronized
    fun takeWindow(minWindowNs: Long, force: Boolean = false): CallFragmentWindow? {
        val now = nanoTime()
        if (!force && now - windowStartNs < minWindowNs) return null
        val window = if (fragments == 0L) {
            null
        } else {
            CallFragmentWindow(
                windowMs = (now - windowStartNs) / 1_000_000L,
                fragments = fragments,
                frames = frames,
                payloadBytes = payloadBytes,
                chunkMin = chunkMin,
                chunkMax = chunkMax,
                chunkLast = chunkLast,
                audioInterleaved = audioInterleaved,
                audioWaitMaxMs = audioWaitMaxMs,
                audioWaitSumMs = audioWaitSumMs,
                rate = rate,
            )
        }
        windowStartNs = now
        fragments = 0L
        frames = 0L
        payloadBytes = 0L
        chunkMin = Int.MAX_VALUE
        chunkMax = 0
        chunkLast = 0
        audioInterleaved = 0L
        audioWaitMaxMs = 0L
        audioWaitSumMs = 0L
        return window
    }
}

/**
 * Writes one large call video frame as ordered fragments and queued call audio between them (the
 * single path of both the fixed 2 KB arm and the airtime arm). Pure over its callbacks so a fake
 * writer that models airtime can test it.
 *
 * Fixed arm ([targetMs] 0): chunks of [CallVideoFragments.FRAGMENT_BYTES] (the last one shorter, as
 * [CallVideoFragments.split]); after every fragment but the last, flush, then write all queued audio
 * (and flush again when any was written). Airtime arm: the same, plus all queued audio is written
 * before every fragment starts (also the first), so no fragment ever starts while audio is waiting,
 * and each chunk follows the current rate estimate (re-read per fragment, so a long keyframe adapts).
 */
internal class CallFragmentWriter<A : Any>(
    private val targetMs: Int,
    private val rate: () -> FragmentRate,
    private val stats: CallFragmentStats,
    private val nanoTime: () -> Long,
    private val running: () -> Boolean,
    private val writeFragment: (flags: Int, fragment: ByteArray) -> Unit,
    private val takeAudio: () -> A?,
    private val audioQueuedAtNs: (A) -> Long,
    private val writeAudio: (A) -> Long,
    private val flushBetween: () -> Unit,
    private val afterFragment: () -> Unit = {},
    /** Wi-Fi upgrade cutover: true stops the frame after the fragment already written (`ResetSendFragments`). */
    private val abandon: () -> Boolean = { false },
) {
    /** Writes [packetPlaintext] (an encoded `BTV3` media packet); returns the call audio payload bytes written between fragments. */
    fun writeFrame(packetPlaintext: ByteArray, flags: Int): Long {
        require(packetPlaintext.isNotEmpty() && packetPlaintext.size <= CallVideoFragments.MAX_PACKET_BYTES) {
            "Invalid fragmented packet size ${packetPlaintext.size}"
        }
        val airtime = targetMs > 0
        var audioBytes = 0L
        var offset = 0
        while (offset < packetPlaintext.size) {
            if (offset > 0 && abandon()) break
            if (airtime) audioBytes += writeQueuedAudio()
            val current = if (airtime) rate() else FragmentRate.UNKNOWN
            val remaining = packetPlaintext.size - offset
            val chunk = if (airtime) {
                CallFragmentAirtime.nextChunkBytes(remaining, CallFragmentAirtime.targetChunkBytes(targetMs, current))
            } else {
                minOf(CallVideoFragments.FRAGMENT_BYTES, remaining)
            }
            writeFragment(flags, CallVideoFragments.fragmentAt(packetPlaintext, offset, chunk))
            stats.onFragment(chunk, current)
            afterFragment()
            offset += chunk
            if (offset < packetPlaintext.size && running()) {
                flushBetween()
                audioBytes += writeQueuedAudio()
            }
        }
        stats.onFrameDone()
        return audioBytes
    }

    /** All queued call audio, oldest first; one flush afterwards when any was written. */
    private fun writeQueuedAudio(): Long {
        var written = 0L
        while (running()) {
            val audio = takeAudio() ?: break
            stats.onAudio((nanoTime() - audioQueuedAtNs(audio)).coerceAtLeast(0L) / 1_000_000L)
            written += writeAudio(audio)
        }
        if (written > 0L) flushBetween()
        return written
    }
}
