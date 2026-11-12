package com.example.bluetoothtest

import android.os.SystemClock
import java.util.EnumMap

/** The three users of the phone-wide GATT/scanner registration pool (32 apps, 2 used by the stack). */
internal enum class PoolSource(val label: String) {
    SERVER("server"),
    CLIENT("client"),
    SCANNER("scanner"),
}

/**
 * Back-off for registration failures of the phone-wide pool (ASUS Android 11 lane11: `gatt_api.cc(975) can't Register
 * GATT client, MAX client reached: 32`, about 1,500 stack errors per 3-minute run). The pool does not clear within
 * seconds, so every automatic retry of a client connect, a GATT server open or a scanner start waits for a hold that
 * doubles from [baseMs] to [capMs] and ends with the first success. Failures that arrive while a hold is running (a
 * second attempt already in flight) do not take a step.
 *
 * Pure decision logic with an injected clock; [GattPoolBackoff] owns the shared instance, the log line and the
 * reliability-card feed. Limits, advertisers and requested settings are not touched: this only decides when an
 * automatic retry is worth trying again.
 */
internal class PoolBackoff(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val baseMs: Long = BASE_MS,
    private val capMs: Long = CAP_MS,
) {
    /** One backoff step: the [failures]-th consecutive failure of [source], held for [delayMs]. */
    data class Step(val source: PoolSource, val failures: Int, val delayMs: Long, val code: Int?, val skipped: Int)

    private class State {
        var failures = 0
        var holdUntilMs = 0L
        var lastFailureMs = 0L
        var lastCode: Int? = null
        var skipped = 0
    }

    private val states = EnumMap<PoolSource, State>(PoolSource::class.java)

    private fun state(source: PoolSource) = states.getOrPut(source) { State() }

    fun delayFor(failures: Int): Long {
        if (failures <= 0) return 0L
        val shift = (failures - 1).coerceAtMost(MAX_SHIFT)
        return minOf(baseMs shl shift, capMs)
    }

    /** A registration failure of [source] with [code] (scan error, GATT status or null). Null: inside a running hold. */
    @Synchronized
    fun onFailure(source: PoolSource, code: Int?): Step? {
        val s = state(source)
        val now = clock()
        s.lastFailureMs = now
        if (code != null) s.lastCode = code
        if (s.failures > 0 && now < s.holdUntilMs) return null
        s.failures++
        val delay = delayFor(s.failures)
        s.holdUntilMs = now + delay
        val step = Step(source, s.failures, delay, s.lastCode, s.skipped)
        s.skipped = 0
        return step
    }

    /** First success of [source]: the pool has a slot again. Returns how many failures that ends, 0 when none. */
    @Synchronized
    fun onSuccess(source: PoolSource): Int {
        val s = states[source] ?: return 0
        val ended = s.failures
        if (ended > 0 || s.skipped > 0) states[source] = State()
        return ended
    }

    /** Milliseconds an automatic retry of [source] must still wait; 0 when it may try. */
    @Synchronized
    fun holdRemainingMs(source: PoolSource): Long {
        val s = states[source] ?: return 0L
        if (s.failures == 0) return 0L
        return (s.holdUntilMs - clock()).coerceAtLeast(0L)
    }

    /** An automatic attempt of [source] was not made because of the hold (reported with the next step). */
    @Synchronized
    fun noteSkipped(source: PoolSource) {
        state(source).skipped++
    }

    /** Consecutive failures of [source] since its last success. */
    @Synchronized
    fun failures(source: PoolSource): Int = states[source]?.failures ?: 0

    /** The last failure code of [source] when it failed within [windowMs] and has not succeeded since, else null. */
    @Synchronized
    fun recentFailureCode(source: PoolSource, windowMs: Long): Int? {
        val s = states[source] ?: return null
        if (s.failures == 0 || clock() - s.lastFailureMs > windowMs) return null
        return s.lastCode ?: NO_CODE
    }

    /** True when [source] failed within [windowMs] and has not succeeded since. */
    @Synchronized
    fun inBackoff(source: PoolSource, windowMs: Long): Boolean = recentFailureCode(source, windowMs) != null

    @Synchronized
    fun clearForTest() {
        states.clear()
    }

    companion object {
        const val BASE_MS = 1_000L
        const val CAP_MS = 30_000L
        private const val MAX_SHIFT = 10

        /** [recentFailureCode] result for a failure that carried no code. */
        const val NO_CODE = -1
    }
}

/**
 * The shared pool back-off: records registration failures of the server, client and scanner paths, logs one
 * `BENCHMARK gatt_pool_exhausted` line per backoff step (not per attempt), feeds [PoolExhaustionPolicy] so the B2
 * reliability card shows the Bluetooth off/on guidance, and answers the two questions callers ask: "may I retry
 * now" ([holdRemainingMs]) and, for a debug dial that expired, "was the scanner failing" ([scannerFailureCode]).
 */
internal object GattPoolBackoff {
    @Volatile var shared = PoolBackoff()

    /** The policy the reliability card reads; unit tests replace it with one on a fake clock. */
    @Volatile var policy: PoolExhaustionPolicy = PoolExhaustionPolicy.shared

    /** Hot-path flag for the scan callback: true between a scanner registration failure and the next scan result. */
    @Volatile var scannerFailing = false
        private set

    /** A scanner failure older than this no longer explains a missing sighting (two caps of the scanner's own 30 s back-off). */
    const val SCANNER_RECENT_MS = 60_000L

    /** Connect failure statuses that name the pool: 257 (registration, `onClientRegistered` failed) and 128 (client blocks). */
    fun isPoolStatus(status: Int?): Boolean =
        status == GattResourceRegistry.STATUS_REGISTRATION_EXHAUSTED || status == GattResourceRegistry.STATUS_NO_RESOURCES

    /**
     * Called (on the caller's thread) after a scanner back-off step or the scanner's first success, so a screen that
     * shows the Nearby list can refresh [ScanRestartHint]. The activity sets it and posts to its own thread.
     */
    @Volatile var onScannerChanged: (() -> Unit)? = null

    /** Records a registration failure of [source]; logs and feeds the card when it opened a new backoff step. */
    fun recordFailure(source: PoolSource, code: Int?, owner: String): PoolBackoff.Step? {
        if (source == PoolSource.SCANNER) scannerFailing = true
        val step = shared.onFailure(source, code) ?: return null
        val card = policy
        card.onRegistrationFailure(source)
        GattResourceRegistry.logQuietly(stepLine(step, owner, GattResourceRegistry.shared.snapshot().registrations))
        if (card.restartGuidanceDue()) {
            GattResourceRegistry.logQuietly("BENCHMARK gatt_restart_guidance: ${PoolExhaustionPolicy.GUIDANCE}")
        }
        if (source == PoolSource.SCANNER) runCatching { onScannerChanged?.invoke() }
        return step
    }

    /** A connect that failed with [message]: only a 257/128 status counts as pool exhaustion. */
    fun noteConnectFailure(message: String?, owner: String): PoolBackoff.Step? {
        val status = leSetupStatus(message)
        return if (isPoolStatus(status)) recordFailure(PoolSource.CLIENT, status, owner) else null
    }

    fun recordSuccess(source: PoolSource) {
        if (source == PoolSource.SCANNER) scannerFailing = false
        if (source == PoolSource.CLIENT) GattNativeClientWedge.onCentralConnectSuccess()
        val ended = shared.onSuccess(source)
        policy.onRegistrationSuccess(source)
        if (ended > 0) {
            GattResourceRegistry.logQuietly(
                "BENCHMARK gatt_pool_exhausted: source=${source.label} | event=recovered | failures=$ended",
            )
            if (source == PoolSource.SCANNER) runCatching { onScannerChanged?.invoke() }
        }
    }

    /** True when an automatic connect attempt must wait (and counts it for the next line). */
    fun holdsClientConnect(): Boolean {
        if (!GattSwitches.poolBackoff) return false
        if (shared.holdRemainingMs(PoolSource.CLIENT) <= 0L) return false
        shared.noteSkipped(PoolSource.CLIENT)
        return true
    }

    fun holdRemainingMs(source: PoolSource): Long = shared.holdRemainingMs(source)

    /** The scan failure code when the scanner is in registration-failure back-off, else null. */
    fun scannerFailureCode(): Int? {
        val code = shared.recentFailureCode(PoolSource.SCANNER, SCANNER_RECENT_MS) ?: return null
        return code.takeIf { it != PoolBackoff.NO_CODE } ?: ScanFailurePolicy.APPLICATION_REGISTRATION_FAILED
    }

    /** Unit tests: fresh state on the given fake-clock instances. */
    fun resetForTest(backoff: PoolBackoff = PoolBackoff(), policy: PoolExhaustionPolicy = PoolExhaustionPolicy.shared) {
        shared = backoff
        this.policy = policy
        scannerFailing = false
    }

    fun stepLine(step: PoolBackoff.Step, owner: String, held: Int): String =
        "BENCHMARK gatt_pool_exhausted: source=${step.source.label} | event=backoff | owner=$owner | failures=${step.failures} | " +
            "backoffMs=${step.delayMs} | code=${step.code ?: "none"} | skippedAttempts=${step.skipped} | " +
            "heldRegistrations=$held/${GattResourceRegistry.APP_POOL}"
}
