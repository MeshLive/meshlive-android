package com.example.bluetoothtest

import android.content.Intent
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Call availability hardening (coc-listener-exhaustion report, 2026-11-05). Four independent switches, all default
 * off and DEBUG launch extras only (docs/dev/switches.md). With every switch off nothing here changes behaviour.
 *
 * - `test_listen_partial_cleanup=true`: a call listener whose open fails after the LE CoC listen succeeded releases what
 *   it opened (lease, control server), and a failed rotating-ID or Bluetooth-on reopen reports the endpoint unavailable
 *   instead of only posting a status line ([PartialOpen], [CallAvailabilityFaults]).
 * - `test_coc_ledger_persist=true`: the Android 10 CoC registration budget survives app processes ([CocBudget]).
 * - `test_call_listen_watchdog_ms=<ms>` (proposed 5000, 0 = off): an LE CoC listen that has not returned by then is an
 *   availability failure (`reason=stalled`) and a socket that returns late is closed ([ListenWatchdog]).
 * - `test_calls_unavailable_status=true`: a failed or stalled call availability shows "Calls unavailable" with a
 *   Bluetooth settings action instead of a green Ready ([CallsAvailabilityTracker]).
 */
internal object CocAvailabilitySwitches {
    const val EXTRA_PARTIAL_CLEANUP = "test_listen_partial_cleanup"
    const val EXTRA_LEDGER_PERSIST = "test_coc_ledger_persist"
    const val EXTRA_LISTEN_WATCHDOG_MS = "test_call_listen_watchdog_ms"
    const val EXTRA_CALLS_UNAVAILABLE = "test_calls_unavailable_status"
    const val MAX_WATCHDOG_MS = 60_000L

    /** When a STARTING endpoint counts as stalled for the status while the watchdog is off. */
    const val DEFAULT_STALL_MS = 5_000L

    @Volatile var partialCleanup = false
    @Volatile var ledgerPersist = false

    /** Owner 2026-11-05: the listen watchdog and the honest status are ON by default; 0 / false (DEBUG extras) turn them off. */
    const val DEFAULT_WATCHDOG_MS = 5_000L

    /** 0 = off (the listen blocks as long as the stack blocks it, as before). */
    @Volatile var listenWatchdogMs = DEFAULT_WATCHDOG_MS
    @Volatile var callsUnavailableStatus = true

    /** STARTING longer than this shows as stalled: the watchdog deadline when it is on. */
    val stallMs: Long get() = listenWatchdogMs.takeIf { it > 0 } ?: DEFAULT_STALL_MS

    fun readLaunchExtras(intent: Intent) {
        val debug = BuildConfig.DEBUG
        partialCleanup = debug && intent.getBooleanExtra(EXTRA_PARTIAL_CLEANUP, false)
        ledgerPersist = debug && intent.getBooleanExtra(EXTRA_LEDGER_PERSIST, false)
        listenWatchdogMs = if (debug) watchdogMs(intent.getIntExtra(EXTRA_LISTEN_WATCHDOG_MS, DEFAULT_WATCHDOG_MS.toInt()).toLong()) else DEFAULT_WATCHDOG_MS
        callsUnavailableStatus = !debug || intent.getBooleanExtra(EXTRA_CALLS_UNAVAILABLE, true)
        CallListenInject.value = if (debug) intent.getStringExtra(CallListenInject.EXTRA) else null
        if (debug) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK call_availability_switches: partialCleanup=$partialCleanup | ledgerPersist=$ledgerPersist | " +
                    "listenWatchdogMs=$listenWatchdogMs | callsUnavailableStatus=$callsUnavailableStatus | " +
                    "inject=${CallListenInject.value ?: "none"}",
            )
        }
    }

    /** Negative or zero is off; larger values are clamped so a typo cannot hide a stall for minutes. */
    fun watchdogMs(requested: Long): Long = if (requested <= 0) 0L else requested.coerceAtMost(MAX_WATCHDOG_MS)
}

/**
 * DEBUG call-listen injection (`test_call_listen_inject`, off): `hang` blocks the private-call LE CoC listen the way the
 * wedged S22 stack did (never returns, never throws); `control_fail` throws after the listen succeeded, where the
 * control server starts. A release build ignores it.
 */
internal object CallListenInject {
    const val EXTRA = "test_call_listen_inject"
    const val HANG = "hang"
    const val CONTROL_FAIL = "control_fail"
    const val CALL_ROLE = "private-call"

    @Volatile var value: String? = null

    class InjectedControlFailure : IOException("injected control server failure ($EXTRA=$CONTROL_FAIL)")

    fun hangRequested(role: String): Boolean = BuildConfig.DEBUG && role == CALL_ROLE && value == HANG

    fun failControlIfRequested() {
        if (BuildConfig.DEBUG && value == CONTROL_FAIL) throw InjectedControlFailure()
    }

    /** Blocks the calling thread for good, as the stuck binder call did (it is a daemon worker or the old caller). */
    fun hangForever(): Nothing {
        Log.i("BluetoothVideo", "BENCHMARK call_listen_inject: mode=$HANG | role=$CALL_ROLE")
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE)
            } catch (_: InterruptedException) {
                // A stuck binder call ignores interrupts too.
            }
        }
    }
}

/**
 * Proposal 1: an open that fails part-way releases what it already opened. [release] closes the parts this open
 * created (and only those); with [enabled] false the failure propagates exactly as before and the parts stay open.
 */
internal object PartialOpen {
    fun <T> run(enabled: Boolean, release: () -> Unit, log: (String) -> Unit, body: () -> T): T {
        try {
            return body()
        } catch (error: Throwable) {
            if (enabled) {
                runCatching(release)
                log(
                    "BENCHMARK call_availability: state=partial_open_released | " +
                        "error=${error.message ?: error.javaClass.simpleName}",
                )
            }
            throw error
        }
    }
}

/**
 * Proposal 1 and 3: an endpoint whose availability ended without a call (a reopen failed or a listen stalled) says so,
 * so the activity can show it and the CoC -> GATT fallback can run. Process-wide, as the endpoint has no callback for it.
 */
internal object CallAvailabilityFaults {
    @Volatile var listener: ((source: Any, error: Throwable) -> Unit)? = null

    fun report(source: Any, error: Throwable) {
        listener?.invoke(source, error)
    }
}

/** A listen that the Bluetooth stack did not complete within the watchdog deadline. */
internal class ListenStalledException(val role: String, val waitedMs: Long) :
    IOException("LE CoC listen stalled in the Bluetooth stack (role=$role, waited $waitedMs ms)")

/**
 * Proposal 3: runs a blocking platform listen on a worker thread with a deadline. On time the caller gets the result or
 * the listen's own exception. Past the deadline the caller gets [ListenStalledException] and the worker keeps waiting;
 * whatever the stack returns later is closed by [closeLate] and never handed to anyone.
 */
internal class ListenWatchdog<T>(
    private val deadlineMs: Long,
    private val closeLate: (T) -> Unit,
    private val onLate: (lateMs: Long) -> Unit = {},
    private val startThread: (String, () -> Unit) -> Unit = { name, body -> thread(name = name, isDaemon = true) { body() } },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun run(role: String, open: () -> T): T {
        val lock = Any()
        val done = CountDownLatch(1)
        var result: Result<T>? = null
        var abandoned = false
        val startedMs = nowMs()
        startThread("CocListenWatchdog-$role") {
            val outcome = runCatching(open)
            val late = synchronized(lock) {
                if (abandoned) {
                    true
                } else {
                    result = outcome
                    false
                }
            }
            done.countDown()
            if (late) outcome.onSuccess { value ->
                runCatching { closeLate(value) }
                onLate(nowMs() - startedMs)
            }
        }
        try {
            done.await(deadlineMs, TimeUnit.MILLISECONDS)
        } finally {
            synchronized(lock) { if (result == null) abandoned = true }
        }
        val finished = synchronized(lock) { result }
            ?: throw ListenStalledException(role, nowMs() - startedMs)
        return finished.getOrThrow()
    }
}
