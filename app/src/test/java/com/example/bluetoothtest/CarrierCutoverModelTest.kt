package com.example.bluetoothtest

import com.example.bluetoothtest.CutoverSim.Companion.CLOSE_BY
import com.example.bluetoothtest.CutoverSim.Companion.DELIVER
import com.example.bluetoothtest.CutoverSim.Companion.DETECT
import com.example.bluetoothtest.CutoverSim.Companion.FAULT
import com.example.bluetoothtest.CutoverSim.Companion.NEW_ATTEMPT
import com.example.bluetoothtest.CutoverSim.Companion.NEW_UP
import com.example.bluetoothtest.CutoverSim.Companion.QUALITY
import com.example.bluetoothtest.CutoverSim.Companion.QUIESCE
import com.example.bluetoothtest.CutoverSim.Companion.RENEWAL_FAILED
import com.example.bluetoothtest.CutoverSim.Companion.REPLACE_STANDBY
import com.example.bluetoothtest.CutoverSim.Companion.STANDBY_EVENT
import com.example.bluetoothtest.CutoverSim.Companion.STANDBY_STALE
import com.example.bluetoothtest.CutoverSim.Companion.SWITCH_OFF
import com.example.bluetoothtest.CutoverSim.Companion.TICK
import com.example.bluetoothtest.CutoverSim.Companion.code
import com.example.bluetoothtest.CutoverSim.Companion.kind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.SplittableRandom

/**
 * T-CX-M, the Design CX stage-0 gate: model checking of [CarrierCutover] (PROTOCOL "Implementation
 * stage 0", DECISIONS "Stage 0 model findings"). [CutoverSim] checks, at every step, invariant 1
 * (the session never ends while a carrier lives; a policy close never takes the last live carrier),
 * 2 (the opener is never fed a record not above the last opened one), 3 (delivered records are a
 * strictly increasing subsequence of the sealed ones), 6 (a closed carrier never delivers) and 7
 * (nothing is written to a carrier this phone already closed; the design says RESUME is never
 * written to a dead one), and at quiescence invariant 4 (indispensable state equal on both sides)
 * and 5 (SEND_ON and ACCEPT_FROM name the same live carrier, or both sides have ended).
 *
 * Kinds of run: bounded exhaustive searches of state-hashed depth-first over small configurations
 * (1 s ticks, hold 1 s, detection up to 2 s, the PROTOCOL ratios), seeded random runs with the real
 * constants, runs beyond the transport contract (duplicated and dropped records: only the safety
 * invariants), the half-open measurement, and the counterexamples of the first PROTOCOL draft,
 * which prove that the checker catches each defect it found. A1 adds invariants 9 (after a
 * switch-off no attempt produces an action), 10 (a standby replacement never ends the session),
 * 11 (a repeated or stale marker on the standby changes nothing) and 12 (no policy close of the
 * last carrier alive on the ground once the standby is known stale), and the events link renewal,
 * switch-off, quality fallback and stale standby.
 */
class CarrierCutoverModelTest {

    // ------------------------------------------------------------ configurations
    private fun smallMachine(
        holdExpiry: CarrierCutover.HoldExpiry = CarrierCutover.HoldExpiry.PROMOTE,
        pendingKeepsSession: Boolean = true,
    ) = CarrierCutover.Config(
        holdMs = 1_000, holdBytes = 3, activeBeforeSwitchMs = 1_000, cooldownMs = 2_000,
        maxFallbacks = 2, pendingMaxMs = 2_000, holdExpiry = holdExpiry, pendingKeepsSession = pendingKeepsSession,
    )

    /** One exhaustive configuration. [up]: BIND/READY complete the moment the attempt starts. */
    private fun exhaustiveCfg(
        horizon: Int,
        media: Int,
        machine: CarrierCutover.Config = smallMachine(),
        changes: Int = 0,
        faults: Int = 1,
        attempts: Int = 1,
        up: Boolean = true,
        delay: Int = 2,
        duplicates: Boolean = false,
        drops: Boolean = false,
        readerQueue: Boolean = false,
        lossless: Boolean = false,
        frameEvery: Int = 1,
        replacements: Int = 0,
        switchOffs: Int = 0,
        quality: Int = 0,
        stale: Int = -1,
    ) = CutoverSim.Cfg(
        tickMs = 1_000, machine = machine, horizonTicks = horizon, quietTicks = 9, detectMaxTicks = 2,
        mediaPerEndpoint = media, mediaBytes = 1, stateChangesPerEndpoint = changes, faultBudget = faults,
        maxAttempts = attempts, duplicates = duplicates, drops = drops, keys = 1, maxDelayTicks = delay,
        quiesceAnywhere = false, closeBy = false, attemptByTick = 1, faultByTick = horizon - 1,
        upImmediately = up, autoQuiesce = true, readerQueue = readerQueue, maxConsumeLagTicks = 0,
        frameEvery = frameEvery, requireLossless = lossless,
        replacements = replacements, switchOffs = switchOffs, qualityFallbacks = quality, staleMaxTicks = stale,
    )

    private fun format(cfg: CutoverSim.Cfg, codes: List<Int>): String {
        val r = replayCutover(cfg, codes, trace = true)!!
        val noise = Regex("^\\s+[AB]: write rec#\\d+ on .*")
        return r.log!!.filterNot { it.endsWith(" tick") || noise.matches(it) }.joinToString("\n")
    }

    private fun report(line: String) = println("T-CX-M $line")

    // ------------------------------------------------------------ exhaustive search
    private fun checkExplored(
        name: String,
        cfg: CutoverSim.Cfg,
        mustReach: List<String> = emptyList(),
        budget: Long = 6_000_000L,
        allowCapped: Boolean = false,
    ): Explored {
        val t0 = System.nanoTime()
        val r = exploreCutover(cfg, budget)
        report(
            "exhaustive $name: states=${r.states} terminals=${r.terminals} ms=${(System.nanoTime() - t0) / 1_000_000}" +
                if (r.capped) " capped=true" else "",
        )
        report("  coverage of $name: " + r.coverage.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" })
        for (k in mustReach) assertTrue("$name never reached '$k'", (r.coverage[k] ?: 0L) > 0L)
        if (r.violation != null) {
            val min = shrinkCutover(cfg, r.trace!!, r.violation.substringBefore(' '))
            report("VIOLATION in $name: ${r.violation}\n" + format(cfg, min))
        }
        assertNull("$name: ${r.violation}", r.violation)
        if (!allowCapped) assertFalse("$name: state budget exhausted before the search finished (${r.states} states)", r.capped)
        return r
    }

    @Test
    fun exhaustiveSwitchHoldAndFallbackWithOneFault() {
        // both directions switch at tick 1; either carrier dies at any later tick, seen at different
        // times by the two sides; a record may be delayed up to two ticks, so SWITCH can trail the
        // first new-carrier records past the 1 s hold
        checkExplored(
            "switch/hold/fallback, 1 fault", exhaustiveCfg(horizon = 6, media = 3),
            mustReach = listOf(
                "write-switch-on-old", "write-switch-on-new", "write-resume-on-old", "write-switch-on-old-into-dead",
                "switch-accepted-from-old", "old-dead-hint", "new-died-resume-notice", "resume-closed-new-not-accepted",
                "switch-already-accepted", "hold-exhausted",
                "old-died-new-left", "new-died-fell-back", "resume-closed-new", "resume-burned", "end-session",
                "drop-fenced", "drop-stale", "reassert",
            ),
        )
    }

    @Test
    fun exhaustiveWithTheReaderQueueSeparateFromTheConsumer() {
        // a record read from a carrier waits in the ordered content queue while carrier events are
        // handled, so the consumer meets records of carriers closed in the meantime (invariant 6)
        checkExplored(
            "reader queue, 1 fault", exhaustiveCfg(horizon = 4, media = 1, readerQueue = true),
            mustReach = listOf("drop-closed_carrier", "write-switch-on-new", "hold-exhausted", "new-died-fell-back"),
        )
    }

    @Test
    fun exhaustiveWithTwoFaults() {
        checkExplored("2 faults (both carriers, either order)", exhaustiveCfg(horizon = 4, media = 1, faults = 2))
    }

    @Test
    fun exhaustiveWhileTheNewCarrierIsStillBeingSetUp() {
        checkExplored(
            "handshake pending, READY at any time", exhaustiveCfg(horizon = 5, media = 1, up = false),
            mustReach = listOf("switch-kept-for-pending", "switch-applied-at-ready", "old-died-waiting-for-pending", "close-handshake_timeout"),
        )
    }

    @Test
    fun exhaustiveWithASecondAttemptAfterTheFirstDied() {
        checkExplored(
            "two attempts", exhaustiveCfg(horizon = 7, media = 1, attempts = 2),
            mustReach = listOf("switch-burned", "write-switch-on-old", "new-died-fell-back"),
        )
    }

    @Test
    fun exhaustiveWithIndispensableStateChanges() {
        checkExplored("state changes at any time", exhaustiveCfg(horizon = 3, media = 1, changes = 1))
    }

    @Test
    fun exhaustiveWithDuplicatedRecords() {
        checkExplored("duplicated records", exhaustiveCfg(horizon = 3, media = 1, duplicates = true))
    }

    @Test
    fun exhaustiveWithDroppedRecords() {
        checkExplored("dropped records (safety only)", exhaustiveCfg(horizon = 3, media = 1, drops = true))
    }

    /**
     * Hold 2 s and records delayed at most one tick: the switch must not lose a single media
     * record. The switch comes at 2 s, so records sealed at 1 s may still be queued on the old link.
     */
    private fun losslessMachine(machine: CarrierCutover.Config = smallMachine()) =
        machine.copy(holdMs = 2_000, holdBytes = 100, activeBeforeSwitchMs = 2_000)

    @Test
    fun exhaustiveNormalSwitchWithAnOldLinkBacklogLosesNothing() {
        // no fault, no duplicate: whatever the old link still has queued when the sender switches
        // must still be delivered (invariant 8); the switch also waits for a frame boundary
        for (frameEvery in listOf(1, 2)) {
            checkExplored(
                "lossless normal switch, frames of $frameEvery",
                exhaustiveCfg(horizon = 6, media = 4, machine = losslessMachine(), faults = 0, delay = 1, lossless = true, frameEvery = frameEvery),
                mustReach = listOf("write-switch-on-old", "switch-accepted-from-old", "switch-waits-for-old-queue") +
                    if (frameEvery > 1) listOf("switch-waits-for-frame") else emptyList(),
            )
        }
        // the FIFO position alone is enough here, because the delay is below the hold; the gate is
        // what keeps a long backlog from outliving the hold
        checkExplored(
            "lossless normal switch, FIFO without the gate",
            exhaustiveCfg(horizon = 6, media = 4, machine = losslessMachine(smallMachine().copy(gateSwitchOnOldQueue = false)), faults = 0, delay = 1, lossless = true),
            mustReach = listOf("write-switch-on-old", "switch-accepted-from-old"),
        )
    }

    @Test
    fun exhaustiveNormalSwitchWithTheReaderQueueLosesNothing() {
        checkExplored(
            "lossless normal switch, reader queue",
            exhaustiveCfg(horizon = 5, media = 3, machine = losslessMachine(), faults = 0, delay = 1, lossless = true, readerQueue = true),
            mustReach = listOf("write-switch-on-old", "switch-accepted-from-old"),
        )
    }

    // ------------------------------------------------------------ A1: link renewal, switch-off, quality fallback
    @Test
    fun exhaustiveRenewalReplacesTheDeadOldLinkWhileWifiLives() {
        checkExplored(
            "renewal after the old link died", exhaustiveCfg(horizon = 2, media = 1, faults = 2, replacements = 1),
            mustReach = listOf("standby-replaced", "reassert-switch", "old-died-new-left", "new-died-fell-back"),
        )
    }

    @Test
    fun exhaustiveRenewalWithTheReaderQueue() {
        checkExplored(
            "renewal, reader queue",
            exhaustiveCfg(horizon = 2, media = 1, faults = 2, replacements = 1, readerQueue = true),
            mustReach = listOf("standby-replaced", "heldDeaths", "droppedHeldDeaths"),
            budget = 30_000_000L,
            allowCapped = true,
        )
    }

    @Test
    fun exhaustiveSwitchOffAtAnyTimeByOnePhone() {
        checkExplored(
            "switch-off by one phone", exhaustiveCfg(horizon = 6, media = 2, faults = 1, switchOffs = 1),
            mustReach = listOf("switch-off", "switch-off-fell-back", "pending-ignored-after-switch-off", "write-resume-on-old"),
        )
    }

    @Test
    fun exhaustiveSwitchOffByBothPhones() {
        checkExplored(
            "switch-off by both phones", exhaustiveCfg(horizon = 5, media = 1, faults = 0, switchOffs = 2),
            mustReach = listOf("switch-off", "switch-off-fell-back"),
        )
    }

    @Test
    fun exhaustiveSwitchOffWhileWifiIsTheOnlyCarrierCompletesWhenTheStandbyIsBack() {
        // A1F2: after a failed renewal no replacement may bind (the 5.1 rule), so the switch-off's waiting state is
        // reached through the owner reporting a stale standby; the replacement then completes the release.
        checkExplored(
            "switch-off with Wi-Fi only, then renewal",
            exhaustiveCfg(horizon = 2, media = 1, faults = 1, switchOffs = 1, replacements = 1, stale = 0),
            mustReach = listOf("switch-off-wifi-only", "switch-off-standby-stale", "switch-off-completed-on-replace"),
        )
    }

    @Test
    fun exhaustiveQualityFallbackAtAnyTime() {
        checkExplored(
            "quality fallback", exhaustiveCfg(horizon = 7, media = 1, faults = 1, quality = 2),
            mustReach = listOf("quality-fallback"),
        )
    }

    @Test
    fun exhaustiveDelayedSwitchAfterRenewalNeverClosesTheStandby() {
        // review F-6: a SWITCH written on Wi-Fi before a renewal may arrive after it; the new standby survives and takes the call
        checkExplored(
            "delayed SWITCH after a renewal", exhaustiveCfg(horizon = 2, media = 1, faults = 2, replacements = 1, delay = 2),
            mustReach = listOf("standby-replaced", "old-dead-hint", "new-died-fell-back"),
        )
    }

    @Test
    fun exhaustiveSwitchOffAndQualityWithAStaleStandby() {
        // Opus A1 2: invariant 12 holds whenever the owner reports the stale standby
        checkExplored(
            "stale standby", exhaustiveCfg(horizon = 3, media = 1, faults = 1, switchOffs = 1, quality = 1, stale = 0),
            mustReach = listOf("standby-stale", "switch-off-standby-stale"),
        )
    }

    // ------------------------------------------------------------ seeded random runs
    private fun randomCfg(duplicates: Boolean = false, drops: Boolean = false, readerQueue: Boolean = true) = CutoverSim.Cfg(
        tickMs = 100, machine = CarrierCutover.Config(cooldownMs = 4_000), horizonTicks = 150,
        quietTicks = 150, detectMaxTicks = 20, mediaPerEndpoint = 150, mediaBytes = 20_000,
        stateChangesPerEndpoint = 4, faultBudget = 3, maxAttempts = 2,
        duplicates = duplicates, drops = drops, readerQueue = readerQueue,
    )

    private class Tally {
        var runs = 0; var switched = 0; var resumed = 0; var promoted = 0; var ended = 0
        var withFaults = 0; var staleDrops = 0; var voidWrites = 0; var keyframes = 0
        override fun toString() =
            "runs=$runs switched=$switched resumed=$resumed holdExhaustions=$promoted sessionsEndedLegitimately=$ended " +
                "runsWithFault=$withFaults staleDrops=$staleDrops writesIntoDeadCarrier=$voidWrites keyframeRequests=$keyframes"
    }

    /**
     * One random run. Everything is drawn from [seed], so a failure reproduces: attempt times,
     * READY delays, up to three faults (a carrier dies or a side's watchdog closes it) with
     * detection delays of 0, the 2 s maximum or in between per side, state changes, per-channel
     * delivery rates and stalls of up to 3 s (arbitrary delay between carriers), media sizes from
     * 1 KB to 3 MB (the 5 MiB hold).
     */
    private fun runSeed(
        seed: Long, cfg: CutoverSim.Cfg, trace: Boolean = false, record: Boolean = false, variableBytes: Boolean = true,
    ): CutoverSim {
        val rnd = SplittableRandom(seed)
        val s = CutoverSim(
            if (variableBytes) cfg.copy(mediaBytesOf = { x, seq ->
                // sizes depend only on (seed, endpoint, sequence), so a recorded run replays exactly
                val h = (seed * 1_000_003L + x * 7_919L + seq) * -7_046_029_254_386_353_131L
                val r = (h ushr 33).toInt() and 0x7fffffff
                if (r % 100 < 3) 1_000_000 + r % 2_000_000 else 1_000 + r % 40_000
            }) else cfg,
        )
        if (trace) s.log = ArrayList()
        if (record) s.record = ArrayList()
        val attemptTick = if (rnd.nextInt(10) < 9) rnd.nextInt(60) else -1
        val attempt2Tick = if (attemptTick >= 0) attemptTick + 10 + rnd.nextInt(80) else -1
        val nFaults = when (rnd.nextInt(100)) { in 0..24 -> 0; in 25..59 -> 1; in 60..84 -> 2; else -> 3 }
        class Fault(val tick: Int, val onNew: Boolean, val by: Int) // by -1: the carrier dies
        val faults = (0 until nFaults).map {
            Fault(
                10 + rnd.nextInt(cfg.horizonTicks - 40), rnd.nextBoolean(),
                if (rnd.nextInt(10) < 6) -1 else rnd.nextInt(2),
            )
        }
        class Change(val tick: Int, val x: Int, val key: Int)
        val changes = (0 until 2).flatMap { x ->
            (0 until rnd.nextInt(cfg.stateChangesPerEndpoint + 1)).map {
                Change(rnd.nextInt(cfg.horizonTicks - 5), x, rnd.nextInt(CutoverSim.KEYS))
            }
        }
        val slots = 1 + cfg.maxAttempts
        val stallFrom = Array(2) { IntArray(slots) { -1 } }
        val stallTo = Array(2) { IntArray(slots) { -1 } }
        val pDeliver = Array(2) { DoubleArray(slots) { doubleArrayOf(0.25, 0.6, 1.0)[rnd.nextInt(3)] } }
        for (f in 0..1) for (c in 0 until slots) if (rnd.nextInt(100) < 30) {
            stallFrom[f][c] = rnd.nextInt(cfg.horizonTicks - 30)
            stallTo[f][c] = stallFrom[f][c] + 1 + rnd.nextInt(30)
        }
        val upAt = Array(2) { IntArray(slots) { Int.MAX_VALUE } }
        val detectAt = Array(2) { IntArray(slots) { Int.MAX_VALUE } }
        val extra = cfg.replacements + cfg.switchOffs + cfg.qualityFallbacks > 0
        val ext = SplittableRandom(seed xor 0x5DEECE66DL)
        val replaceTicks = if (extra) (0 until cfg.replacements).map { 20 + ext.nextInt(cfg.horizonTicks - 60) } else emptyList()
        val switchOffTick = if (extra && cfg.switchOffs > 0 && ext.nextInt(100) < 50) 10 + ext.nextInt(cfg.horizonTicks - 40) else -1
        val switchOffBy = if (extra) ext.nextInt(2) else 0
        val qualityTicks = if (extra) (0 until cfg.qualityFallbacks).map { 15 + ext.nextInt(cfg.horizonTicks - 50) } else emptyList()
        val qualityBy = if (extra) ext.nextInt(2) else 0
        fun upDelay() = when (rnd.nextInt(10)) { in 0..6 -> 1 + rnd.nextInt(10); in 7..8 -> 10 + rnd.nextInt(60); else -> 400 }
        fun detectDelay() = when (rnd.nextInt(10)) { in 0..2 -> 0; in 3..5 -> cfg.detectMaxTicks; else -> rnd.nextInt(cfg.detectMaxTicks + 1) }

        fun applyIfEnabled(c: Int): Boolean {
            if (!s.isEnabled(c)) return false
            s.apply(c)
            return true
        }

        var guard = 0
        val buf = ArrayList<Int>()
        val shuffler = java.util.Random(seed)
        while (s.violation == null && guard++ < 2_000_000) {
            val t = s.tick
            if (!s.phase2) {
                if (t == attemptTick || (t >= attempt2Tick && attempt2Tick >= 0 && s.attemptsStarted == 1)) {
                    if (applyIfEnabled(code(NEW_ATTEMPT))) {
                        val a = s.attemptsStarted
                        for (x in 0..1) upAt[x][a] = t + upDelay()
                    }
                }
                for (x in 0..1) {
                    val a = s.attemptsStarted
                    if (a > 0 && t >= upAt[x][a]) applyIfEnabled(code(NEW_UP, x))
                }
                for (f in faults) if (f.tick == t) {
                    val c = if (f.onNew) s.attemptsStarted else 0
                    val ok = if (f.by < 0) applyIfEnabled(code(FAULT, c)) else applyIfEnabled(code(CLOSE_BY, f.by, c))
                    if (ok) for (x in 0..1) detectAt[x][c] = t + detectDelay()
                }
                for (ch in changes) if (ch.tick == t) applyIfEnabled(code(CutoverSim.STATE_CHANGE, ch.x, ch.key))
                for (rt in replaceTicks) if (rt == t) applyIfEnabled(code(REPLACE_STANDBY))
                if (t == switchOffTick) applyIfEnabled(code(SWITCH_OFF, switchOffBy))
                for (qt in qualityTicks) if (qt == t) applyIfEnabled(code(QUALITY, qualityBy))
            }
            // deliveries in random channel order, with stalls; delay between carriers is arbitrary
            val order = (0 until 2 * slots).toMutableList().also { it.shuffle(shuffler) }
            for (o in order) {
                val f = o % 2
                val c = o / 2
                val stalled = !s.phase2 && t >= stallFrom[f][c] && t < stallTo[f][c]
                while (!stalled && s.canDeliver(f, c) && (s.phase2 || rnd.nextDouble() < pDeliver[f][c])) {
                    val roll = rnd.nextInt(100)
                    when {
                        cfg.duplicates && roll < 4 -> s.apply(code(CutoverSim.DUPLICATE, f, c))
                        cfg.drops && roll in 4..5 -> s.apply(code(CutoverSim.DROP, f, c))
                        else -> s.apply(code(DELIVER, f, c))
                    }
                    if (s.violation != null) break
                }
            }
            for (x in 0..1) {
                while (s.violation == null && s.canConsume(x) && (s.phase2 || rnd.nextDouble() < 0.6)) s.apply(code(CutoverSim.CONSUME, x))
            }
            for (x in 0..1) {
                if (s.isEnabled(code(STANDBY_EVENT, x)) && (s.phase2 || ext.nextDouble() < 0.5)) s.apply(code(STANDBY_EVENT, x))
            }
            if (cfg.staleMaxTicks >= 0) for (x in 0..1) {
                if (s.isEnabled(code(STANDBY_STALE, x)) && (s.phase2 || ext.nextDouble() < 0.2)) s.apply(code(STANDBY_STALE, x))
            }
            for (x in 0..1) {
                if (s.isEnabled(code(RENEWAL_FAILED, x)) && (s.phase2 || ext.nextDouble() < 0.5)) s.apply(code(RENEWAL_FAILED, x))
            }
            s.enabled(buf)
            for (cd in buf.toList()) {
                if (kind(cd) == DETECT && t >= detectAt[CutoverSim.a(cd)][CutoverSim.b(cd)]) applyIfEnabled(cd)
            }
            s.enabled(buf)
            if (TICK in buf) {
                s.apply(code(TICK))
                if (!s.phase2 && s.tick >= cfg.horizonTicks) s.apply(code(QUIESCE))
            } else {
                val forced = buf.firstOrNull { kind(it) == DETECT || kind(it) == DELIVER || kind(it) == CutoverSim.CONSUME || kind(it) == NEW_UP || kind(it) == STANDBY_EVENT || kind(it) == STANDBY_STALE || kind(it) == RENEWAL_FAILED }
                if (forced == null) {
                    if (QUIESCE in buf) s.apply(code(QUIESCE)) else break
                } else {
                    s.apply(forced)
                }
            }
        }
        s.checkFinal()
        return s
    }

    private fun randomSeeds(name: String, seeds: LongRange, cfg: CutoverSim.Cfg) {
        val tally = Tally()
        val t0 = System.nanoTime()
        for (seed in seeds) {
            val s = runSeed(seed, cfg)
            tally.runs += 1
            if (s.switchWrites > 0) tally.switched += 1
            if (s.resumeWrites > 0) tally.resumed += 1
            if (s.m.any { it.holdExhaustions > 0 }) tally.promoted += 1
            if (s.ended.any { it }) tally.ended += 1
            if (s.voidWrites > 0) tally.withFaults += 1
            tally.staleDrops += s.droppedStale
            tally.voidWrites += s.voidWrites
            tally.keyframes += s.keyframes
            if (s.violation != null) {
                val again = runSeed(seed, cfg, trace = true)
                val lines = again.log!!.filterNot { it.endsWith(" tick") }.takeLast(400)
                report("RANDOM $name seed $seed: ${s.violation}\n" + lines.joinToString("\n"))
                assertNull("$name seed $seed: ${s.violation}", s.violation)
            }
        }
        report("random $name seeds ${seeds.first}..${seeds.last}: $tally ms=${(System.nanoTime() - t0) / 1_000_000}")
    }

    @Test
    fun randomSeeds() = randomSeeds("contract", 1L..20_000L, randomCfg())

    @Test
    fun randomSeedsWithDuplicatedRecords() = randomSeeds("duplicates", 100_001L..105_000L, randomCfg(duplicates = true))

    @Test
    fun randomSeedsWithDroppedRecords() = randomSeeds("drops", 200_001L..205_000L, randomCfg(drops = true))

    private fun renewalCfg() = randomCfg().copy(replacements = 2, switchOffs = 1, qualityFallbacks = 2, staleMaxTicks = 10)

    @Test
    fun randomSeedsWithRenewalSwitchOffAndQualityFallback() {
        var replaced = 0
        var switchedOff = 0
        var quality = 0
        val seeds = 300_001L..310_000L
        val t0 = System.nanoTime()
        for (seed in seeds) {
            val s = runSeed(seed, renewalCfg())
            if (s.replacementsDone > 0) replaced += 1
            if (s.switchOffsDone > 0) switchedOff += 1
            if (s.qualityDone > 0) quality += 1
            if (s.violation != null) {
                val again = runSeed(seed, renewalCfg(), trace = true)
                report(
                    "RANDOM renewal seed $seed: ${s.violation}\n" +
                        again.log!!.filterNot { it.endsWith(" tick") }.takeLast(400).joinToString("\n"),
                )
                assertNull("renewal seed $seed: ${s.violation}", s.violation)
            }
        }
        report(
            "random renewal seeds ${seeds.first}..${seeds.last}: replaced=$replaced switchedOff=$switchedOff quality=$quality" +
                " ms=${(System.nanoTime() - t0) / 1_000_000}",
        )
        assertTrue("no run replaced a standby", replaced > 0)
        assertTrue("no run switched Wi-Fi off", switchedOff > 0)
        assertTrue("no run fired the quality rule", quality > 0)
    }

    // ------------------------------------------------------------ the first draft is caught
    private fun firstDraftViolation(name: String, cfg: CutoverSim.Cfg): Pair<String, List<Int>> {
        val r = exploreCutover(cfg)
        val v = r.violation ?: error("$name: the model did not find the known defect")
        val min = shrinkCutover(cfg, r.trace!!, v.substringBefore(' '))
        report("first-draft counterexample [$name] after ${r.states} states: $v (shrunk from ${r.trace.size} to ${min.size} steps)\n" + format(cfg, min))
        return v to min
    }

    @Test
    fun firstDraftHoldExpiryClosingTheNewCarrierIsCaught() {
        val machine = smallMachine(holdExpiry = CarrierCutover.HoldExpiry.CLOSE_NEW)
        val (v, trace) = firstDraftViolation("hold expiry closes N", exhaustiveCfg(horizon = 5, media = 3, machine = machine))
        assertTrue(v, v.startsWith("1 "))
        assertTrue(trace.any { kind(it) == FAULT })
    }

    @Test
    fun firstDraftSwitchOvertakingTheOldLinkBacklogIsCaught() {
        // control lanes served before media: the SWITCH passes the records queued on the old link,
        // the new carrier's records pass them too, and the backlog arrives stale
        val machine = losslessMachine(smallMachine().copy(switchThroughMediaFifo = false, gateSwitchOnOldQueue = false))
        val (v, _) = firstDraftViolation(
            "SWITCH overtakes the old link's backlog",
            exhaustiveCfg(horizon = 6, media = 4, machine = machine, faults = 0, delay = 1, lossless = true),
        )
        assertTrue(v, v.startsWith("8 "))
    }

    @Test
    fun firstDraftEndingTheSessionWhileTheNewCarrierIsBeingSetUpIsCaught() {
        val machine = smallMachine(pendingKeepsSession = false)
        val (v, _) = firstDraftViolation("O lost during the N handshake", exhaustiveCfg(horizon = 4, media = 1, machine = machine, up = false))
        assertTrue(v, v.startsWith("1 "))
    }

    @Test
    fun firstDraftSwitchOffClosingTheOnlyCarrierIsCaught() {
        val machine = smallMachine().copy(switchOffKeepsLastCarrier = false)
        val (v, trace) = firstDraftViolation("switch-off closes the last carrier", exhaustiveCfg(horizon = 6, media = 1, faults = 1, switchOffs = 1, machine = machine))
        assertTrue(v, v.startsWith("1 "))
        assertTrue(trace.any { kind(it) == SWITCH_OFF })
    }

    @Test
    fun firstDraftAcceptingAttemptsAfterTheSwitchOffIsCaught() {
        val machine = smallMachine().copy(switchOffBlocksAttempts = false)
        val (v, _) = firstDraftViolation("attempt after the switch-off", exhaustiveCfg(horizon = 4, media = 1, faults = 0, switchOffs = 1, machine = machine))
        assertTrue(v, v.startsWith("9 "))
    }

    @Test
    fun firstDraftSwitchOffIgnoringAStaleStandbyIsCaught() {
        val machine = smallMachine().copy(standbyFreshnessGates = false)
        val (v, trace) = firstDraftViolation("switch-off with a stale standby", exhaustiveCfg(horizon = 6, media = 1, faults = 1, switchOffs = 1, stale = 0, machine = machine))
        assertTrue(v, v.startsWith("12 "))
        assertTrue(trace.any { kind(it) == SWITCH_OFF })
    }

    /**
     * Random search with the real constants for the first run that breaks an invariant when the
     * markers name no carrier instance, then shrink it. (The exhaustive bound of two ticks of delay
     * cannot hold a marker back through a whole second attempt.)
     */
    @Test
    fun firstDraftMarkersWithoutACarrierNameMisleadALaterAttemptAndAreCaught() {
        val cfg = randomCfg().copy(machine = CarrierCutover.Config(cooldownMs = 4_000, markersNameCarrier = false))
        // 27889 found it last time; the full search below only runs if the machine changed under it
        for (seed in listOf(27889L) + (1L..100_000L)) {
            val s = runSeed(seed, cfg, record = true, variableBytes = false)
            val v = s.violation ?: continue
            val min = shrinkCutover(cfg, s.record!!, v.substringBefore(' '))
            report(
                "first-draft counterexample [SWITCH/RESUME name no carrier] from random seed $seed: $v " +
                    "(shrunk from ${s.record!!.size} to ${min.size} steps)\n" + format(cfg, min),
            )
            assertTrue(v, v.startsWith("4 ") || v.startsWith("5 "))
            assertTrue(min.count { kind(it) == NEW_ATTEMPT } >= 2)
            return
        }
        error("no seed found the known defect")
    }

    // ------------------------------------------------------------ the half-open case, measured
    private class HalfOpen(
        val scenario: String, val sender: Char, val detectTicks: Int, val gapMs: Long, val sealed: Int, val opened: Int,
    )

    /**
     * One carrier dies; phone A notices it after [dA] ticks and phone B after [dB] ticks (50 ms
     * each, one media record per tick and direction, no network delay, so the numbers are the
     * cutover's own). [bSwitches] false keeps B's own direction on the old link. [lag] ticks is the
     * writer backlog of the carrier: with a lag, what is still queued when the carrier dies is lost.
     */
    private fun halfOpenRun(
        machine: CarrierCutover.Config, killOld: Boolean, afterSwitch: Boolean, dA: Int, dB: Int,
        bSwitches: Boolean, lag: Int = 0, tickMs: Long = 50,
    ): List<HalfOpen> {
        val cfg = CutoverSim.Cfg(
            tickMs = tickMs, machine = machine, horizonTicks = 600, quietTicks = 400,
            detectMaxTicks = 40, mediaPerEndpoint = 600, mediaBytes = 1_000, stateChangesPerEndpoint = 0,
            faultBudget = 1, maxAttempts = 1, faultDropsInFlight = lag > 0,
            machineB = if (bSwitches) null else machine.copy(activeBeforeSwitchMs = Long.MAX_VALUE / 2),
        )
        val s = CutoverSim(cfg)
        s.apply(code(NEW_ATTEMPT))
        val faultTick = if (afterSwitch) 100 else 20 // the switch happens at 3 s = tick 60
        val victim = if (killOld) 0 else 1
        var detectAt = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE)
        val buf = ArrayList<Int>()
        var guard = 0
        while (s.violation == null && guard++ < 100_000) {
            if (s.tick == 1) for (x in 0..1) if (s.isEnabled(code(NEW_UP, x))) s.apply(code(NEW_UP, x))
            if (s.tick == faultTick && !s.phase2 && s.isEnabled(code(FAULT, victim))) {
                s.apply(code(FAULT, victim)); detectAt = intArrayOf(s.tick + dA, s.tick + dB)
            }
            for (c in 0..1) for (f in 0..1) while (s.canDeliver(f, c) && s.headAgeTicks(f, c) >= lag) s.apply(code(DELIVER, f, c))
            s.enabled(buf)
            for (cd in buf.toList()) if (kind(cd) == DETECT && s.tick >= detectAt[CutoverSim.a(cd)]) s.apply(cd)
            s.enabled(buf)
            if (TICK in buf) {
                s.apply(code(TICK))
                if (!s.phase2 && s.tick >= cfg.horizonTicks) s.apply(code(QUIESCE))
            } else {
                val forced = buf.firstOrNull { kind(it) == DETECT || kind(it) == DELIVER || kind(it) == CutoverSim.CONSUME || kind(it) == NEW_UP }
                    ?: if (QUIESCE in buf) code(QUIESCE) else break
                s.apply(forced)
            }
        }
        s.checkFinal()
        assertNull("old=$killOld afterSwitch=$afterSwitch dA=$dA dB=$dB bSwitches=$bSwitches: ${s.violation}", s.violation)
        val what = (if (killOld) "O" else "N") + (if (afterSwitch) " dies after the switch" else " dies before the switch") +
            (if (bSwitches) "" else ", B stays on O")
        return (0..1).map { x ->
            val st = s.mediaStats(x)
            HalfOpen(what, 'A' + x, if (x == 0) dA else dB, st.maxGapTicks * tickMs, st.sealed, st.opened)
        }
    }

    /** Who notices the death at once: both phones, one of them, or neither (both after the 2 s maximum). */
    private val halfOpenClasses = listOf(
        "both notice at once" to listOf(0 to 0),
        "one notices at once" to listOf(0 to 40, 40 to 0),
        "neither notices before 2 s" to listOf(40 to 40),
    )

    private val halfOpenVariants = listOf(
        "round 6 text" to CarrierCutover.Config().copy(oldDeadHint = false, resumeAlwaysWritten = false),
        "(b) RESUME always, the corrected text" to CarrierCutover.Config(),
    )

    @Test
    fun halfOpenMediaLossIsBoundedByTheSendersOwnDetectionDelay() {
        val worstByCell = HashMap<String, Long>()
        var worstNew = 0L
        for ((variantName, machine) in halfOpenVariants) {
            for ((className, delays) in halfOpenClasses) {
                var worst = 0L
                var worstLost = 0
                for (bSwitches in listOf(true, false)) for (killOld in listOf(true, false)) for (after in listOf(true, false)) {
                    for ((dA, dB) in delays) for (r in halfOpenRun(machine, killOld, after, dA, dB, bSwitches)) {
                        if (r.gapMs > worst) worst = r.gapMs
                        if (r.sealed - r.opened > worstLost) worstLost = r.sealed - r.opened
                        // the gap is the sender's own detection delay plus one 50 ms record; never more
                        assertTrue("$variantName / $className / ${r.scenario} ${r.sender}: gap ${r.gapMs} ms", r.gapMs <= r.detectTicks * 50L + 200L)
                    }
                }
                worstByCell["$variantName / $className"] = worst
                report("half-open [$variantName] $className: worst media gap $worst ms, worst loss $worstLost of 600 records per direction (20 per second)")
                if (variantName.startsWith("(b) RESUME always")) worstNew = maxOf(worstNew, worst)
            }
        }
        report("half-open worst media gap with the corrected text: $worstNew ms")
        // reduction (b) never makes a cell worse; reduction (a) was withdrawn by review F-6, so when only one phone
        // notices at once the other keeps writing into the dead old link until its own detection (at most 2 s)
        for ((className, _) in halfOpenClasses) {
            assertTrue(worstByCell["(b) RESUME always, the corrected text / $className"]!! <= worstByCell["round 6 text / $className"]!!)
        }
        assertTrue(worstByCell["(b) RESUME always, the corrected text / both notice at once"]!! <= 300L)
    }

    @Test
    fun halfOpenLossAddsTheDeadCarriersQueuedBacklog() {
        // 5 ticks = 250 ms of records still queued for the carrier when it dies are lost on top of the detection delay
        for (lag in listOf(0, 5)) {
            var worst = 0L
            for (killOld in listOf(true, false)) for (after in listOf(true, false)) {
                for (r in halfOpenRun(CarrierCutover.Config(), killOld, after, 40, 40, bSwitches = true, lag = lag)) {
                    if (r.gapMs > worst) worst = r.gapMs
                    assertTrue("lag $lag ${r.scenario} ${r.sender}: gap ${r.gapMs} ms", r.gapMs <= 2_000L + lag * 50L + 200L)
                }
            }
            report("half-open, neither notices before 2 s, carrier backlog ${lag * 50} ms: worst media gap $worst ms")
        }
    }
}
