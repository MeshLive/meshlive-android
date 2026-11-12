package com.example.bluetoothtest

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Every link measures its own capacity from the media it already carries (root-cause workstream B,
 * 2026-10-07). No Android API reports what a Bluetooth link can carry; it depends on both phones'
 * controllers, Wi-Fi coexistence and the other links sharing the radio, so any fixed number is
 * wrong for some phone. A sealed record is written back-to-back, so a large record crosses the link
 * as a packet train: bytes / arrival time is the rate the link delivered at that moment (packet-pair
 * dispersion). Keyframes and large frames make every session a speed test without extra traffic.
 *
 * Only bytes that arrive after the reader started waiting count; bytes already buffered when the
 * train began (the reader was busy) are excluded so a backlog cannot look like a fast link.
 */
internal class ArrivalTrainMeter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val windowNs: Long = WINDOW_NS,
) {
    private class Sample(val atNs: Long, val kbps: Int, val join: Boolean)

    private var inRecord = false
    private var started = false
    private var startNs = 0L
    private var lastNs = 0L
    private var bytes = 0L
    private var bufferedAtStart = 0L
    private val samples = ArrayDeque<Sample>()

    /** Every byte read in the current record, and when the last of them was read (join burst only). */
    private var recordTotal = 0L
    private var recordLastNs = 0L

    /** Experimental join burst ([enableJoinBurst]); 0 = off. */
    private var joinUntilNs = 0L
    private var joinDone = false
    private var chainOpen = false
    private var chainStartNs = 0L
    private var chainLastNs = 0L
    private var chainBytes = 0L

    /** Every sample, including a join burst sample. */
    @Volatile var trains = 0L
        private set

    /** Genuine trains only (a record or burst of at least [MIN_TRAIN_BYTES]); a join burst sample is not one. */
    @Volatile var genuineTrains = 0L
        private set

    /**
     * Experimental join-time burst measurement (DebugTunables.joinProbeSeed, stream receivers only).
     * For [windowNs] from now, consecutive records that arrive back to back (no idle gap beyond
     * [JOIN_MAX_GAP_NS]) are measured as one train even when each is below [MIN_TRAIN_BYTES]: a
     * 240p-360p stream's first records are 2-8 KB, so the link was never timed and its sender kept the
     * cautious prior. The one sample is bytes over time across the burst and is therefore the smaller of
     * what the sender offered and what the link carried; it exists only inside the window and a
     * genuine train replaces it. No wire change: the sample leaves in the ordinary link report.
     */
    @Synchronized
    fun enableJoinBurst(windowNs: Long = JOIN_WINDOW_NS) {
        joinUntilNs = nanoTime() + windowNs
        joinDone = false
        chainOpen = false
    }

    @Synchronized
    fun beginRecord() {
        inRecord = true
        started = false
        bytes = 0
        bufferedAtStart = 0
        recordTotal = 0
    }

    /** [waited]: nothing was buffered before this read; [availableAfter]: bytes buffered after it. */
    @Synchronized
    fun onRead(count: Int, waited: Boolean, availableAfter: Int) {
        if (!inRecord || count <= 0) return
        val now = nanoTime()
        recordTotal += count
        recordLastNs = now
        if (!started) {
            if (!waited) return
            started = true
            startNs = now
            lastNs = now
            bufferedAtStart = availableAfter.toLong()
            return
        }
        bytes += count
        lastNs = now
    }

    /** Returns this record's train rate in kbps when it was long enough to measure. */
    @Synchronized
    fun endRecord(): Int? {
        inRecord = false
        val genuine = endTrain()
        if (joinUntilNs != 0L) joinChain(genuine != null)
        return genuine
    }

    private fun endTrain(): Int? {
        if (!started) return null
        val durationNs = lastNs - startNs
        val trainBytes = bytes - bufferedAtStart
        if (trainBytes < MIN_TRAIN_BYTES || durationNs < MIN_TRAIN_NS) return null
        val kbps = (trainBytes * 8_000_000L / durationNs).toInt()
        // A genuine train supersedes the join burst sample (which is only a lower bound).
        samples.removeAll { it.join }
        samples.addLast(Sample(lastNs, kbps, join = false))
        trains++
        genuineTrains++
        return kbps
    }

    /**
     * The join burst sample has left in a link report: drop it, so later reports do not repeat a lower
     * bound the parent already took as its one probe result.
     */
    @Synchronized
    fun joinReported() {
        samples.removeAll { it.join }
    }

    /** Chains back-to-back records of the join window into one burst (see [enableJoinBurst]). */
    private fun joinChain(genuineTrain: Boolean) {
        if (joinDone || recordTotal <= 0L || recordLastNs > joinUntilNs) {
            chainOpen = false
            return
        }
        if (genuineTrain) {
            joinDone = true
            chainOpen = false
            return
        }
        if (chainOpen && started && startNs - chainLastNs > JOIN_MAX_GAP_NS) chainOpen = false
        if (!chainOpen) {
            // Only a record whose first byte the reader waited for proves the link was idle before it.
            if (!started) return
            chainOpen = true
            chainStartNs = startNs
            chainBytes = (bytes - bufferedAtStart).coerceAtLeast(0L)
            chainLastNs = lastNs
        } else {
            chainBytes += recordTotal
            chainLastNs = recordLastNs
        }
        val spanNs = chainLastNs - chainStartNs
        if (chainBytes >= JOIN_MIN_BYTES && spanNs >= JOIN_MIN_SPAN_NS) {
            samples.addLast(Sample(chainLastNs, (chainBytes * 8_000_000L / spanNs).toInt(), join = true))
            trains++
            joinDone = true
            chainOpen = false
        }
    }

    /**
     * Capacity this link delivered recently: the median train in the window. The upper tail is
     * biased high on GATT, where a short train can fall inside a few connection events (the M31
     * reported 1107-1140 kbps against an 892 kbps integrity-checked probe with the old
     * second-highest rule).
     */
    @Synchronized
    fun estimateKbps(): Int? {
        val now = nanoTime()
        if (joinUntilNs != 0L && now > joinUntilNs) samples.removeAll { it.join }
        while (samples.isNotEmpty() && now - samples.first().atNs > windowNs) samples.removeFirst()
        val sorted = samples.map { it.kbps }.sorted()
        return if (sorted.isEmpty()) null else sorted[(sorted.size - 1) / 2]
    }

    companion object {
        /** Enough chunks to average out L2CAP/RFCOMM chunking and GATT notification batching. */
        const val MIN_TRAIN_BYTES = 8_000L
        /** At least several BLE connection events (7.5-15 ms each). */
        const val MIN_TRAIN_NS = 60_000_000L
        const val WINDOW_NS = 10_000_000_000L
        /** Join burst ([enableJoinBurst]): the window, the idle gap that ends a burst, and its minimum size and span. */
        const val JOIN_WINDOW_NS = 3_000_000_000L
        const val JOIN_MAX_GAP_NS = 200_000_000L
        const val JOIN_MIN_BYTES = 8_000L
        const val JOIN_MIN_SPAN_NS = 150_000_000L
    }
}

/**
 * Receiver side of the version 3 delivery report (2026-10-09). The arrival-train estimate above is
 * the rate one record crossed the link at; on a shared, two-way GATT link it overstated what the
 * link sustains (regress-ff0e24e1 ASUS->S24 900 kbps call: trains 745-875 kbps, while the S24
 * received 588-696 kbps), so a sender that cut to it stayed above the link and kept cutting. This
 * counts what actually arrived (media payload bytes, exactly the bytes the sender counts as written)
 * and when the last of them arrived on this link's clock, so the sender can time that byte's trip.
 */
internal class LinkDeliveryCounter(private val nanoTime: () -> Long = System::nanoTime) {
    private val startNs = nanoTime()
    private var receivedBytes = 0L
    private var lastArrivalMs = 0L

    @Synchronized
    fun add(bytes: Int) {
        if (bytes <= 0) return
        receivedBytes += bytes
        lastArrivalMs = (nanoTime() - startNs) / 1_000_000L
    }

    @Synchronized
    fun snapshot(): LinkDelivery = LinkDelivery(receivedBytes, lastArrivalMs)
}

/**
 * Whether a new sustained estimate is worth pushing to the time queue: an estimate appearing or being
 * dropped, or moving by 5% or more (the queue's target does not need every cycle's jitter).
 */
internal fun sustainedDrainChanged(pushedKbps: Int, newKbps: Int): Boolean =
    if (pushedKbps <= 0 || newKbps <= 0) pushedKbps != newKbps
    else kotlin.math.abs(newKbps - pushedKbps) * 20L >= pushedKbps

/** Feeds read timing to [meter] without changing the bytes read. */
internal class ArrivalTimedInputStream(
    input: InputStream,
    private val meter: ArrivalTrainMeter,
) : FilterInputStream(input) {
    override fun read(): Int {
        val waited = bufferedBytes() == 0
        val value = super.read()
        if (value >= 0) meter.onRead(1, waited, if (waited) bufferedBytes() else 0)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val waited = bufferedBytes() == 0
        val count = super.read(b, off, len)
        if (count > 0) meter.onRead(count, waited, if (waited) bufferedBytes() else 0)
        return count
    }

    private fun bufferedBytes(): Int = try {
        `in`.available()
    } catch (_: IOException) {
        0
    }
}

/**
 * Keeps the backlog where the app can see and order it (root-cause program, 2026-10-07). In
 * layers-diag-c4db9240 the M31's GATT sender queue read "0/33, 0 ms" while frames reached the ASUS
 * up to 2.2 s late: the Android Bluetooth stack accepted bursts faster than the link drained them,
 * so rate control and audio priority never saw the backlog. Writes are paced at the child-measured
 * delivery rate, allowing only [burstNs] of data ahead of the link; the rest waits, in order and
 * without loss, in the app queue where audio goes first and the backlog drives rate control.
 *
 * Until the child's first arrival report the link used to be written unpaced: in mesh4-57484598 the
 * A15s fed by the ASUS (about 65 kbps) hid 15 s of media in the Android stack, invisible to rate
 * control and route withdrawal, and the report itself queued behind that backlog. [seed] paces the
 * link from join at the learned capacity (or a conservative prior). With no report the seed is
 * halved every [SILENCE_STEP_NS] after [SILENCE_GRACE_NS], down to [MIN_SEED_KBPS], so a link whose
 * reports never arrive still shows its backlog in the app queue. The first report replaces it.
 */
internal class LinkPacer(
    private val nanoTime: () -> Long = System::nanoTime,
    private val burstNs: Long = BURST_NS,
) {
    private var measuredBytesPerSecond = 0L
    private var seedBytesPerSecond = 0L
    private var seededAtNs = 0L
    private var virtualDrainNs = 0L
    /**
     * When a link that drains exactly at the un-gained rate would be empty. [virtualDrainNs] runs at
     * [PACE_GAIN] times that rate, so against a link that drains at the measured rate it understates
     * what is still in the stack by up to 20% of everything written while a backlog stands.
     */
    private var modelDrainNs = 0L
    /** Experimental in-flight cap, see [setLeadCapNs]; 0 = off. */
    private var leadCapNs = 0L

    /** Experimental join probe, see [beginJoinProbe]. */
    private var joinProbeStartNs = 0L
    private var joinProbeBytes = 0L
    private var joinProbeOpen = false

    /** Experimental raise drain guard, see [holdSeed]. */
    private var guardEvalNs = 0L
    private var guardHeldNs = 0L
    private var guardWriteNs = 0L
    private var guardBacklogMs = -1L
    /** Media the queue had dropped (shed, thinned) by the previous report, -1 when the caller does not count drops. */
    private var guardDroppedMedia = -1L
    private var guardBlock: DrainGuardBlock? = null

    /** 0 = unpaced (nothing measured or seeded). The current pace, including seed decay. */
    val bytesPerSecond: Long
        @Synchronized get() = currentRate()

    /** True once the child has reported a measurement; the seed no longer applies. */
    val measured: Boolean
        @Synchronized get() = measuredBytesPerSecond > 0L

    @Synchronized
    fun setMeasuredLinkKbps(kbps: Int) {
        val seededRate = if (measuredBytesPerSecond == 0L) currentRate() else 0L
        val rate = (kbps * 1_000L / 8 * PACE_GAIN).toLong()
        if (seededRate > 0L && rate > 0L) {
            // The first report replaces the seed: the bytes already charged drain at the measured rate.
            val now = nanoTime()
            if (virtualDrainNs > now) virtualDrainNs = now + (virtualDrainNs - now) * seededRate / rate
            if (modelDrainNs > now) modelDrainNs = now + (modelDrainNs - now) * seededRate / rate
        }
        measuredBytesPerSecond = rate
        joinProbeOpen = false
    }

    /**
     * Experimental relay in-flight cap (DebugTunables.relayPaceLeadCap, set by the relay only while
     * its child reports late media): writes may run at most [capNs] of un-gained link time ahead of a
     * link draining at the measured rate, whatever the pace gain allows. The 20% gain lets a link
     * be probed upward, but while a backlog stands every gained second hides another 0.2 s in the
     * Android socket buffer where no queue policy can see it (diagM1: 57-80 KB, 3.2-4.4 s on a
     * 144 kbps link). 0 turns the cap off. Audio is still never held (the caller exempts it).
     */
    @Synchronized
    fun setLeadCapNs(capNs: Long) {
        leadCapNs = capNs.coerceAtLeast(0L)
    }

    /** Un-gained link time still ahead of the writes, for logs: what the stack is assumed to hold. */
    @Synchronized
    fun modelLeadMs(): Long = ((modelDrainNs - nanoTime()).coerceAtLeast(0L)) / 1_000_000L

    /** The same at the gained pace, for logs. */
    @Synchronized
    fun paceLeadMs(): Long = ((virtualDrainNs - nanoTime()).coerceAtLeast(0L)) / 1_000_000L

    @Synchronized
    fun leadCapMs(): Long = leadCapNs / 1_000_000L

    /**
     * Paces this link at [kbps] until the first measurement; ignored once measured or already seeded.
     * Returns true when the seed was applied.
     */
    @Synchronized
    fun seedKbps(kbps: Int): Boolean {
        if (kbps <= 0 || measuredBytesPerSecond > 0L || seedBytesPerSecond > 0L) return false
        seedBytesPerSecond = (kbps * 1_000L / 8 * PACE_GAIN).toLong()
        seededAtNs = nanoTime()
        return true
    }

    /**
     * The child reports media arriving on time but has no measurement yet ([onTimeReportDue]): the
     * link carries what is offered, so silence decay would be evidence-free. Restarts the silence
     * clock, restoring a decayed seed; a child whose link backs up reports late (or nothing) and the
     * seed decays as before. Returns true when the seed applies.
     *
     * Experimental seed raise (DebugTunables.onTimeSeedRaise): pass the sender's own queue age as
     * [raiseBacklogMs] (negative = off). On time at the child while this side holds at least
     * [SEED_RAISE_BACKLOG_MS] means the pace, not the link, is the limit: a fresh install has no
     * measured link, so the cold prior (GATT 350 kbps, 420 paced) sat below a 400 kbps stream plus
     * audio and the held seed grew a 14 s queue (lane15 pairB2, A55 -> ASUS). The seed then grows by
     * [SEED_RAISE_NUM]/[SEED_RAISE_DEN] per report up to [SEED_RAISE_CAP_KBPS]; a link that cannot
     * carry it makes the child report late, and the raise stops and the seed decays as before.
     *
     * Experimental drain guard ([drainGuard], DebugTunables.seedRaiseDrainGuard): the raise only helps
     * while the pace is the limit. A link-limited path (M31 -> A55 at 1200 kbps carried 800-950) kept
     * climbing while the queue grew to 14-31 s. The raise is skipped when the sender's queue age has
     * not fallen by [DRAIN_GUARD_MIN_DROP_MS] since the previous report AND the writer spent at least
     * [DRAIN_GUARD_BLOCKED_PERCENT]% of the time since then inside blocking write calls, more than it
     * was held by the pacer ([noteWrite]). A pacer-limited writer sleeps in the pacer instead, so the
     * raise continues. The seed is still held (not decayed) either way.
     *
     * Shed-aware (switch audit B3): [droppedMedia] is the queue's running count of media it dropped (a
     * bounded-age shed to the next keyframe, arriving video dropped while that keyframe is awaited,
     * thinning). A fall in queue age across reports that also saw a drop is the drop, not the link
     * draining (on a relay the relay-child media age sheds the very queue the guard reads), so it does
     * not count as draining. -1 (a caller that does not count drops) keeps the age test alone.
     */
    @Synchronized
    fun holdSeed(raiseBacklogMs: Long = -1L, drainGuard: Boolean = false, droppedMedia: Long = -1L): Boolean {
        if (measuredBytesPerSecond > 0L || seedBytesPerSecond <= 0L) return false
        val now = nanoTime()
        guardBlock = null
        if (raiseBacklogMs >= SEED_RAISE_BACKLOG_MS && !(drainGuard && raiseStalled(now, raiseBacklogMs, droppedMedia))) {
            val previous = currentRate()
            val cap = (SEED_RAISE_CAP_KBPS * 1_000L / 8 * PACE_GAIN).toLong()
            val raised = minOf(cap, seedBytesPerSecond * SEED_RAISE_NUM / SEED_RAISE_DEN)
            if (raised > seedBytesPerSecond) {
                seedBytesPerSecond = raised
                // Bytes already charged drain at the new pace, as when a measurement replaces the seed.
                if (previous > 0L) {
                    if (virtualDrainNs > now) virtualDrainNs = now + (virtualDrainNs - now) * previous / raised
                    if (modelDrainNs > now) modelDrainNs = now + (modelDrainNs - now) * previous / raised
                }
            }
        }
        if (drainGuard) {
            guardEvalNs = now
            guardHeldNs = 0L
            guardWriteNs = 0L
            guardBacklogMs = raiseBacklogMs
            guardDroppedMedia = droppedMedia
        }
        seededAtNs = now
        return true
    }

    /** What a refused raise saw, for the log line; null when the last [holdSeed] was not refused by the guard. */
    val lastDrainGuardBlock: DrainGuardBlock?
        @Synchronized get() = guardBlock

    /**
     * Experimental drain-guard input (DebugTunables.seedRaiseDrainGuard): one write cycle of the
     * writer. [heldNs] is how long the pacer held it before the write, [writeNs] how long the write
     * call itself took. A GATT write blocks until the stack accepts the bytes, so a writer that spends
     * its time inside write calls rather than held by the pacer is limited by the link.
     */
    @Synchronized
    fun noteWrite(heldNs: Long, writeNs: Long) {
        guardHeldNs += heldNs.coerceAtLeast(0L)
        guardWriteNs += writeNs.coerceAtLeast(0L)
    }

    private fun raiseStalled(nowNs: Long, backlogMs: Long, droppedMedia: Long): Boolean {
        val previous = guardBacklogMs
        val elapsedNs = nowNs - guardEvalNs
        if (previous < 0L || elapsedNs <= 0L) return false
        // A drop since the previous report explains any fall in queue age (B3): it is not draining.
        val dropped = guardDroppedMedia >= 0L && droppedMedia > guardDroppedMedia
        val notDraining = dropped || backlogMs + DRAIN_GUARD_MIN_DROP_MS > previous
        val blocking = guardWriteNs * 100L >= elapsedNs * DRAIN_GUARD_BLOCKED_PERCENT && guardWriteNs > guardHeldNs
        if (!notDraining || !blocking) return false
        guardBlock = DrainGuardBlock(
            previous, backlogMs, guardWriteNs * 100L / elapsedNs, guardHeldNs * 100L / elapsedNs, shed = dropped,
        )
        return true
    }

    /**
     * Experimental join probe (DebugTunables.joinProbeSeed). Opens a bounded window at link start in
     * which the pacer lets the writer run unpaced, up to [JOIN_PROBE_BYTES] or [JOIN_PROBE_BYPASS_NS],
     * so the child can time the link on real media records (its join burst, see
     * [ArrivalTrainMeter.enableJoinBurst]); under the cold prior the writer's own pace was the
     * ceiling the child measured. The first child report with a rate within [JOIN_PROBE_RESOLVE_NS]
     * seeds the pace ([resolveJoinProbe]); otherwise the prior stands ([expireJoinProbe]). Needs a
     * seed; returns true when the probe started.
     */
    @Synchronized
    fun beginJoinProbe(): Boolean {
        if (seedBytesPerSecond <= 0L || measuredBytesPerSecond > 0L || joinProbeOpen) return false
        joinProbeStartNs = nanoTime()
        joinProbeBytes = 0L
        joinProbeOpen = true
        return true
    }

    /** True while the unpaced probe window is open. */
    val joinProbeBypassing: Boolean
        @Synchronized get() = joinProbeBypass(nanoTime())

    private fun joinProbeBypass(nowNs: Long): Boolean =
        joinProbeOpen && nowNs - joinProbeStartNs < JOIN_PROBE_BYPASS_NS && joinProbeBytes < JOIN_PROBE_BYTES

    /**
     * The child's first rate report arrived. [linkBound]: the sender's own queue held at least
     * [SEED_RAISE_BACKLOG_MS], so the writer was offering more than the link carried and the burst
     * rate is the link's. Then the caller applies it as an ordinary measurement ([setMeasuredLinkKbps]).
     * Otherwise the burst only shows the offered load was carried (a lower bound): the held seed is
     * replaced by it when higher (a lower prior is never kept below what the link just carried) and
     * stays a seed, so the on-time raise and the silence decay still apply. Null outside the window.
     */
    @Synchronized
    fun resolveJoinProbe(measuredKbps: Int, linkBound: Boolean): JoinProbeResult? {
        if (!joinProbeOpen || measuredKbps <= 0) return null
        val now = nanoTime()
        val elapsedNs = now - joinProbeStartNs
        if (elapsedNs > JOIN_PROBE_RESOLVE_NS) return null
        joinProbeOpen = false
        val probeMs = elapsedNs / 1_000_000L
        if (linkBound) return JoinProbeResult(measuredKbps, measuredKbps, probeMs, fallback = false, linkBound = true)
        val before = seedKbps
        val target = minOf(maxOf(before, measuredKbps), SEED_RAISE_CAP_KBPS)
        if (target > before && measuredBytesPerSecond == 0L) {
            val previous = currentRate()
            seedBytesPerSecond = (target * 1_000L / 8 * PACE_GAIN).toLong()
            seededAtNs = now
            val raised = seedBytesPerSecond
            if (previous > 0L && raised > 0L) {
                if (virtualDrainNs > now) virtualDrainNs = now + (virtualDrainNs - now) * previous / raised
                if (modelDrainNs > now) modelDrainNs = now + (modelDrainNs - now) * previous / raised
            }
        }
        return JoinProbeResult(measuredKbps, seedKbps, probeMs, fallback = false, linkBound = false)
    }

    /** Once, after [JOIN_PROBE_RESOLVE_NS] without a usable report: the prior stands. Null otherwise. */
    @Synchronized
    fun expireJoinProbe(): JoinProbeResult? {
        if (!joinProbeOpen) return null
        val elapsedNs = nanoTime() - joinProbeStartNs
        if (elapsedNs < JOIN_PROBE_RESOLVE_NS && measuredBytesPerSecond == 0L) return null
        joinProbeOpen = false
        return JoinProbeResult(0, seedKbps, elapsedNs / 1_000_000L, fallback = true, linkBound = false)
    }

    /** The current undecayed seed in link kbps (without [PACE_GAIN]), 0 when none; for logs. */
    val seedKbps: Int
        @Synchronized get() = (seedBytesPerSecond * 8 / 1_000L / PACE_GAIN).toInt()

    private fun currentRate(): Long {
        if (measuredBytesPerSecond > 0L) return measuredBytesPerSecond
        if (seedBytesPerSecond <= 0L) return 0L
        return decayedSeed(seedBytesPerSecond, nanoTime() - seededAtNs)
    }

    /** Nanoseconds to wait before the next write may start. */
    @Synchronized
    fun waitNs(): Long {
        if (currentRate() <= 0L) return 0L
        val nowNs = nanoTime()
        if (joinProbeBypass(nowNs)) return 0L
        var holdNs = virtualDrainNs - burstNs - nowNs
        if (leadCapNs > 0L) holdNs = maxOf(holdNs, modelDrainNs - leadCapNs - nowNs)
        return holdNs.coerceAtLeast(0L)
    }

    /**
     * Charges [bytes] from [writeStartedNs], not from the write's return. A GATT write blocks for its
     * own transmission time; charging from the return double-counted it (lane-b-pacing-529f36cc:
     * 135 KB took 1086 ms to write, then the pacer idled another 1287 ms). Non-blocking CoC/Classic
     * writes return immediately, so their charge is unchanged.
     */
    @Synchronized
    fun onSent(bytes: Int, writeStartedNs: Long = nanoTime()) {
        val rate = currentRate()
        if (rate <= 0L) return
        if (joinProbeBypass(nanoTime())) {
            // The probe window's writes are uncharged: they are the burst the child times.
            joinProbeBytes += bytes
            return
        }
        virtualDrainNs = maxOf(virtualDrainNs, writeStartedNs) + bytes * 1_000_000_000L / rate
        val baseRate = (rate / PACE_GAIN).toLong().coerceAtLeast(1L)
        modelDrainNs = maxOf(modelDrainNs, writeStartedNs) + bytes * 1_000_000_000L / baseRate
    }

    companion object {
        const val BURST_NS = 50_000_000L
        /** The train median read 10-30% below integrity probes; pacing must not cap below the link. */
        const val PACE_GAIN = 1.2
        /** A report normally arrives within a few heartbeats; the seed holds this long unchanged. */
        const val SILENCE_GRACE_NS = 4_000_000_000L
        const val SILENCE_STEP_NS = 2_000_000_000L
        /** The decayed seed never drops below this: a link slower than this shows as backlog anyway. */
        const val MIN_SEED_KBPS = 150
        /** Experimental seed raise ([holdSeed]): sender backlog that marks the pace as the limit. */
        const val SEED_RAISE_BACKLOG_MS = 500L
        const val SEED_RAISE_NUM = 5L
        const val SEED_RAISE_DEN = 4L
        /** Highest GATT probe measured on these phones (ASUS 1488 kbps); above it a link forms trains. */
        const val SEED_RAISE_CAP_KBPS = 1500
        /** Experimental drain guard ([holdSeed]): the least queue-age fall that counts as draining, and the write-blocked share. */
        const val DRAIN_GUARD_MIN_DROP_MS = 100L
        const val DRAIN_GUARD_BLOCKED_PERCENT = 40L
        /** Experimental join probe ([beginJoinProbe]): unpaced window and byte cap, and how long a child report may take. */
        const val JOIN_PROBE_BYPASS_NS = 1_500_000_000L
        const val JOIN_PROBE_BYTES = 32_768L
        const val JOIN_PROBE_RESOLVE_NS = 3_000_000_000L

        /** Seed pace after [silenceNs] without any report: halved per step beyond the grace, with a floor. */
        fun decayedSeed(seedBytesPerSecond: Long, silenceNs: Long): Long {
            if (silenceNs <= SILENCE_GRACE_NS) return seedBytesPerSecond
            val steps = ((silenceNs - SILENCE_GRACE_NS) / SILENCE_STEP_NS + 1).coerceAtMost(16).toInt()
            val floor = (MIN_SEED_KBPS * 1_000L / 8 * PACE_GAIN).toLong()
            return maxOf(minOf(seedBytesPerSecond, floor), seedBytesPerSecond shr steps)
        }
    }
}

/** What the experimental drain guard saw when it refused a seed raise (percentages of the time since the previous report). */
internal data class DrainGuardBlock(
    val previousBacklogMs: Long,
    val backlogMs: Long,
    val writeBlockedPercent: Long,
    val heldPercent: Long,
    /** The queue dropped media since the previous report, so its age fall was not counted as draining (B3). */
    val shed: Boolean = false,
)

/**
 * The experimental join probe's outcome ([LinkPacer.resolveJoinProbe], [LinkPacer.expireJoinProbe]):
 * [measuredKbps] what the child timed (0 = none), [seedKbps] the pace seed afterwards, [probeMs] the time
 * from link start, [fallback] the prior stood, [linkBound] the sender was backlogged so the rate is the link's.
 */
internal data class JoinProbeResult(
    val measuredKbps: Int,
    val seedKbps: Int,
    val probeMs: Long,
    val fallback: Boolean,
    val linkBound: Boolean,
) {
    fun benchmarkLine(role: String, peer: String): String =
        "BENCHMARK join_probe_seed: role=$role | peer=$peer | measuredKbps=$measuredKbps | seedKbps=$seedKbps | " +
            "probeMs=$probeMs | fallback=$fallback | linkBound=$linkBound"
}

internal fun DrainGuardBlock.benchmarkLine(role: String, peer: String, seedKbps: Int): String =
    "BENCHMARK pacer_seed_raise_blocked: role=$role | peer=$peer | seedKbps=$seedKbps | backlogMs=$backlogMs | " +
        "previousBacklogMs=$previousBacklogMs | writeBlockedPct=$writeBlockedPercent | heldPct=$heldPercent | shed=$shed"

/**
 * Whether a viewer that has not measured its parent link should report that media still arrives on
 * time (DebugTunables.onTimeReports). Records under [ArrivalTrainMeter.MIN_TRAIN_BYTES] never form
 * a train (640x360 at 500 kbps: keyframes about 8.3 KB), so such a stream is never measured; without
 * a report its parent decays the seeded pace blind (see [LinkPacer.holdSeed]). Due every
 * [ON_TIME_REPORT_INTERVAL_NS], inside the parent's silence grace, while new media arrived and the
 * playout is not pinned late.
 */
internal fun onTimeReportDue(
    enabled: Boolean,
    trainsMeasured: Long,
    tight: Boolean,
    mediaArrived: Boolean,
    sinceLastReportNs: Long,
): Boolean = enabled && trainsMeasured == 0L && !tight && mediaArrived && sinceLastReportNs >= ON_TIME_REPORT_INTERVAL_NS

/** Half the parent's [LinkPacer.SILENCE_GRACE_NS], so one lost report does not start the decay. */
internal const val ON_TIME_REPORT_INTERVAL_NS = 2_000_000_000L

/** Where a child link's initial pace comes from (see [LinkPacer]). */
internal object LinkPacerSeed {
    /**
     * Conservative per-link prior used when this phone has not measured the transport yet: GATT on 1M
     * PHY measured 0.320 Mbps and unprimed CoC 1M 0.193 Mbps, 2M PHY and Classic carry more
     * (DECISIONS). Deliberately far below the fan-out aggregate priors (700-800 kbps).
     */
    fun priorKbps(
        transport: BluetoothTransportOption,
        le2MPhy: Boolean?,
        experimentalPhy: ExperimentalBlePhy? = DebugTunables.experimentalBlePhy,
    ): Int = experimentalPhy?.pacerPriorKbps(transport) ?: when (transport) {
        BluetoothTransportOption.CLASSIC_RFCOMM -> 400
        BluetoothTransportOption.BLE_GATT -> if (le2MPhy == false) 250 else 350
        BluetoothTransportOption.BLE_L2CAP -> if (le2MPhy == false) 200 else 350
    }

    /**
     * The phone's own fresh single-link measurement when it has one, else the conservative prior.
     * With the experimental per-peer seed, the child's own last report for this very link comes first
     * ([peerLinkKbps]): the phone's best probe is measured with its best peer, so a weak peer (ASUS ->
     * A15s about 65 kbps against an ASUS probe near 1 Mbps) was still seeded at the strong rate.
     */
    fun kbps(transport: BluetoothTransportOption, probeKbps: Int?, le2MPhy: Boolean?, peerLinkKbps: Int? = null): Int =
        peerLinkKbps?.takeIf { it > 0 } ?: probeKbps?.takeIf { it > 0 } ?: priorKbps(transport, le2MPhy)

    fun seed(
        pacer: LinkPacer,
        transport: BluetoothTransportOption,
        peer: String? = null,
        /** A Wi-Fi carrier is not paced: it has no Bluetooth airtime to conserve. */
        carrier: CarrierKind = CarrierKind.BLUETOOTH,
    ): Int? {
        if (carrier.isWifi) return null
        if (!DebugTunables.linkPacing || !DebugTunables.linkPacerSeed) return null
        val peerKbps = peer?.takeIf { DebugTunables.experimentalPeerLinkSeed }
            ?.let { LinkCapacity.peerLinkKbps(transport, it) }
        val kbps = kbps(transport, LinkCapacity.measuredLinkKbps(transport), LinkCapacity.features?.le2MPhy, peerKbps)
        return kbps.takeIf { pacer.seedKbps(it) }
    }
}

/**
 * Sustained delivery rate of one relay child link, measured by the relay itself (Q31 follow-up,
 * Experimental `test_relay_drain_sustained`, 2026-10-15). The child's arrival-train estimate
 * ([ArrivalTrainMeter]) needs a large record to cross the link as one back-to-back train of at least
 * [ArrivalTrainMeter.MIN_TRAIN_BYTES] bytes over [ArrivalTrainMeter.MIN_TRAIN_NS]; on a slow GATT
 * hop (about 100-270 kbps) records are paced by the connection events and never form one, so the
 * child reports "0 kbps (0 large-record trains)" and the relay's time queue had no drain rate in
 * 319 of 319 `relay_time_queue` lines (lane 9, benchmarks/runs/lane9-backlog-20261014). Hop-local and
 * with no wire field: the relay sees only its own writes to its own child.
 *
 * While media waits for the link the writer is link-bound: each write returns only when the stack
 * has accepted the bytes, so over several seconds the bytes written per unit time are what the link
 * drains. A write cycle (from the previous write's end to this write's end) counts only when
 * - the queue still held media when the previous write ended and when this one ended (the link was
 *   never starved for lack of offered media: an idle queue says nothing about capacity);
 * - the pacer held the writer for at most [maxHeldFraction] of it (a pacer-limited writer measures
 *   the pace, not the link);
 * - it began at least [warmupNs] into a continuous run of such cycles (the stack accepts about
 *   200 ATT PDUs, tens of kB, ahead of the radio before it pushes back).
 * The estimate is the bytes over the time of the qualifying cycles of the last [windowNs], and exists
 * once those cycles span [minBusyNs]. It is kept for [holdNs] after the last qualifying cycle (so a
 * shed queue that drains empty keeps its target) and is then dropped, never extrapolated.
 */
internal class SustainedDeliveryMeter(
    private val windowNs: Long = WINDOW_NS,
    private val minBusyNs: Long = MIN_BUSY_NS,
    private val warmupNs: Long = WARMUP_NS,
    private val holdNs: Long = HOLD_NS,
    private val maxHeldFraction: Double = MAX_HELD_FRACTION,
) {
    private class Cycle(val endNs: Long, val durationNs: Long, val bytes: Long)

    private val cycles = ArrayDeque<Cycle>()
    private var prevEndNs = Long.MIN_VALUE
    private var prevBacklogged = false
    private var streakStartNs = Long.MIN_VALUE
    private var lastKbps = 0
    private var lastAtNs = Long.MIN_VALUE

    /**
     * The writer finished handing [bytes] (wire bytes) to the link at [nowNs]. [backlogged]: media is
     * still queued for this child now; [heldNs]: how long the pacer held the writer before this write.
     * [leadCapped]: the Experimental relay lead cap was in force (the child reports late media). Such a
     * cycle is held by the relay's own cap, so it measures nothing, but the link is still the limit and
     * media still waits: the last estimate is kept instead of expiring (switch audit B3: the time queue
     * otherwise fell back to the fixed budget exactly while the child was late). It still ends the run.
     */
    @Synchronized
    fun onWritten(nowNs: Long, bytes: Int, backlogged: Boolean, heldNs: Long = 0L, leadCapped: Boolean = false) {
        val startNs = prevEndNs
        val startBacklogged = prevBacklogged
        prevEndNs = nowNs
        prevBacklogged = backlogged
        if (startNs == Long.MIN_VALUE || nowNs <= startNs || bytes <= 0) return
        val cycleNs = nowNs - startNs
        val linkBound = startBacklogged && backlogged && heldNs <= cycleNs * maxHeldFraction
        if (!linkBound) {
            streakStartNs = Long.MIN_VALUE
            if (leadCapped && startBacklogged && backlogged && lastAtNs != Long.MIN_VALUE && nowNs - lastAtNs <= holdNs) {
                lastAtNs = nowNs
            }
            return
        }
        if (streakStartNs == Long.MIN_VALUE) streakStartNs = startNs
        if (startNs - streakStartNs < warmupNs) return
        cycles.addLast(Cycle(nowNs, cycleNs, bytes.toLong()))
        while (cycles.isNotEmpty() && nowNs - cycles.first().endNs > windowNs) cycles.removeFirst()
        var busyNs = 0L
        var busyBytes = 0L
        for (cycle in cycles) {
            busyNs += cycle.durationNs
            busyBytes += cycle.bytes
        }
        if (busyNs < minBusyNs) return
        val kbps = (busyBytes * 8_000_000L / busyNs).toInt()
        if (kbps > 0) {
            lastKbps = kbps
            lastAtNs = nowNs
        }
    }

    /** The sustained rate in kbps, or null when no qualifying evidence exists or the last is older than the hold. */
    @Synchronized
    fun estimateKbps(nowNs: Long): Int? =
        lastKbps.takeIf { lastAtNs != Long.MIN_VALUE && nowNs - lastAtNs <= holdNs }

    companion object {
        const val WINDOW_NS = 10_000_000_000L
        const val MIN_BUSY_NS = 3_000_000_000L
        const val WARMUP_NS = 1_500_000_000L
        const val HOLD_NS = 30_000_000_000L
        const val MAX_HELD_FRACTION = 0.25
    }
}
