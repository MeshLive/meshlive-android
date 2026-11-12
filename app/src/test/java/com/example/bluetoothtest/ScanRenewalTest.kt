package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A manual clock that doubles as the [ScanTimer]; `advance` runs due tasks in order. */
internal class FakeScanClock : ScanTimer {
    var now = 1_000_000L
    private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
    private val tasks = ArrayList<Task>()

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val t = Task(now + delayMs, task)
        tasks += t
        return { t.cancelled = true }
    }

    fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = maxOf(now, next.at)
            next.run()
        }
        now = end
    }

    val pendingTasks: Int get() = tasks.count { !it.cancelled }
}

class ScanRenewalTest {
    private val budget = LeScanStartBudget(maxStarts = 5, windowMs = 30_000L)
    private val clock = FakeScanClock()
    private val gate = ScanStartGate(clock = { clock.now })
    private val registry = ScanRegistryImpl(clock = { clock.now })
    private val events = ArrayList<String>()
    private var dial = false

    @After
    fun resetSwitches() = ScanSwitches.reset()

    /** A fake scanner whose start reserves a gate slot exactly like BleL2capDiscovery. */
    private fun scanner(name: String, renewable: Boolean = true) {
        registry.register(
            name,
            stop = { events += "stop:$name" },
            start = {
                assertEquals("start must find a free slot", 0L, gate.reserve(budget))
                events += "start:$name"
            },
            renewable = renewable,
        )
    }

    private fun renewal(sdk: Int = 34, enabled: Boolean = true) = ScanRenewal(
        registry, gate, { budget }, clock, { clock.now }, sdk,
        enabled = { enabled }, legacy = { false }, dialInProgress = { dial },
    )

    @Test
    fun intervalIsSdkAwareAndLegacyRestoresTwentyMinutes() {
        assertEquals(8 * 60_000L, ScanRenewalPolicy.intervalMs(34, legacy = false))
        assertEquals(8 * 60_000L, ScanRenewalPolicy.intervalMs(36, legacy = false))
        assertEquals(25 * 60_000L, ScanRenewalPolicy.intervalMs(33, legacy = false))
        assertEquals(25 * 60_000L, ScanRenewalPolicy.intervalMs(29, legacy = false))
        assertEquals(20 * 60_000L, ScanRenewalPolicy.intervalMs(34, legacy = true))
        assertEquals(20 * 60_000L, ScanRenewalPolicy.intervalMs(29, legacy = true))
        // Each interval stays inside its stack window (10 min on Android 14+, 30 min before).
        assertTrue(ScanRenewalPolicy.intervalMs(34, false) < ScanRenewalPolicy.windowMs(34))
        assertTrue(ScanRenewalPolicy.intervalMs(33, false) < ScanRenewalPolicy.windowMs(33))
        // The old constant was the bug: longer than the Android 14+ window.
        assertTrue(ScanRenewalPolicy.LEGACY_INTERVAL_MS > ScanRenewalPolicy.windowMs(34))
    }

    @Test
    fun legacySwitchIsDebugOnlyAndOffByDefault() {
        ScanSwitches.reset()
        assertFalse(ScanSwitches.renewalLegacy)
        ScanSwitches.apply { key, default -> if (key == "test_scan_renewal_legacy") true else default }
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extra: the legacy arm stays off.
            assertFalse(ScanSwitches.renewalLegacy)
            return
        }
        assertTrue(ScanSwitches.renewalLegacy)
        assertEquals(20 * 60_000L, ScanRenewalPolicy.intervalMs(34))
        assertFalse(ScanSwitches.renewalAll)
    }

    @Test
    fun helperAgeRestartNeedsAFreeSlotAndDefersInsteadOfStacking() {
        val interval = ScanRenewalPolicy.ANDROID14_INTERVAL_MS
        assertFalse(ScanRenewalPolicy.ageRestartDue(interval - 1, interval, gateHasSlot = true, appWideRenewal = false))
        assertTrue(ScanRenewalPolicy.ageRestartDue(interval, interval, gateHasSlot = true, appWideRenewal = false))
        // No slot: the running scan stays; the next tick asks again.
        assertFalse(ScanRenewalPolicy.ageRestartDue(interval * 2, interval, gateHasSlot = false, appWideRenewal = false))
        // App-wide renewal owns the age.
        assertFalse(ScanRenewalPolicy.ageRestartDue(interval * 2, interval, gateHasSlot = true, appWideRenewal = true))
    }

    @Test
    fun renewalDoesNothingWhileItsSwitchIsOff() {
        scanner("a")
        clock.advance(60 * 60_000L)
        assertEquals(RenewalOutcome.DISABLED, renewal(enabled = false).tick())
        assertTrue(events.isEmpty())
        assertFalse(ScanSwitches.renewalAll) // the shipped default
    }

    @Test
    fun nothingToRenewWithoutRenewableScanners() {
        assertEquals(RenewalOutcome.NOTHING_TO_RENEW, renewal().renewNow("x", force = true))
        scanner("probe", renewable = false)
        assertEquals(RenewalOutcome.NOTHING_TO_RENEW, renewal().renewNow("x", force = true))
        assertTrue(events.isEmpty())
    }

    @Test
    fun allScannersStopBeforeAnyStartAndQuotaIsNeverExceeded() {
        scanner("relay-helper")
        scanner("viewer-discovery")
        scanner("stream-scanner")
        val renewal = renewal()
        assertEquals(RenewalOutcome.NOT_DUE, renewal.tick())
        clock.advance(ScanRenewalPolicy.ANDROID14_INTERVAL_MS)
        assertEquals(RenewalOutcome.STARTED, renewal.tick())
        assertTrue(renewal.pending)
        // All three are stopped, none restarted yet.
        assertEquals(listOf("stop:relay-helper", "stop:viewer-discovery", "stop:stream-scanner"), events)
        assertEquals(RenewalOutcome.PENDING, renewal.tick())
        clock.advance(ScanRenewalPolicy.STOP_GAP_MS - 1)
        assertEquals(3, events.size)
        clock.advance(1)
        assertEquals(
            listOf(
                "stop:relay-helper", "stop:viewer-discovery", "stop:stream-scanner",
                "start:relay-helper", "start:viewer-discovery", "start:stream-scanner",
            ),
            events,
        )
        assertFalse(renewal.pending)
        assertEquals(1, renewal.renewals)
        assertEquals(3, gate.used(budget))
        // The per-app clock restarts: nothing is due for another full interval.
        assertEquals(RenewalOutcome.NOT_DUE, renewal.tick())
        clock.advance(ScanRenewalPolicy.ANDROID14_INTERVAL_MS - 1)
        assertEquals(RenewalOutcome.NOT_DUE, renewal.tick())
    }

    @Test
    fun aCallDiscoveryOrDialDefersRenewalWithoutStackingIt() {
        scanner("relay-helper")
        scanner("call-list", renewable = false)
        val renewal = renewal()
        clock.advance(ScanRenewalPolicy.ANDROID14_INTERVAL_MS)
        assertEquals(RenewalOutcome.DEFERRED_CALL, renewal.tick())
        assertTrue(events.isEmpty())
        registry.unregister("call-list")
        dial = true
        assertEquals(RenewalOutcome.DEFERRED_CALL, renewal.tick())
        assertTrue(events.isEmpty())
        dial = false
        assertEquals(RenewalOutcome.STARTED, renewal.tick())
        clock.advance(ScanRenewalPolicy.STOP_GAP_MS)
        assertEquals(listOf("stop:relay-helper", "start:relay-helper"), events)
        assertEquals(1, renewal.renewals)
    }

    @Test
    fun noRenewalWithoutRoomInTheScanQuotaAndSlotsStayFreeForACall() {
        scanner("a")
        scanner("b")
        val renewal = renewal()
        clock.advance(ScanRenewalPolicy.ANDROID14_INTERVAL_MS)
        // Four slots used: 1 free, two scanners need 2 (+1 kept for a call).
        repeat(4) { assertEquals(0L, gate.reserve(budget)) }
        assertEquals(RenewalOutcome.DEFERRED_QUOTA, renewal.tick())
        assertTrue(events.isEmpty())
        // After the window passes, slots are free again and the renewal runs.
        clock.advance(30_001L)
        assertEquals(RenewalOutcome.STARTED, renewal.tick())
        clock.advance(ScanRenewalPolicy.STOP_GAP_MS)
        assertEquals(listOf("stop:a", "stop:b", "start:a", "start:b"), events)
        assertTrue(gate.used(budget) <= budget.maxStarts)
    }

    @Test
    fun aRenewalNeverRunsTwiceAtOnceAndForcedRestartsSkipTheCallDeferral() {
        scanner("a")
        dial = true
        val renewal = renewal()
        assertEquals(RenewalOutcome.DEFERRED_CALL, renewal.renewNow("manual", force = false))
        assertEquals(RenewalOutcome.STARTED, renewal.restartAfterEnvironmentChange())
        assertEquals(RenewalOutcome.PENDING, renewal.renewNow("again", force = true))
        clock.advance(ScanRenewalPolicy.STOP_GAP_MS)
        assertEquals(listOf("stop:a", "start:a"), events)
    }

    @Test
    fun aScannerThatLeavesDuringTheGapIsNotRestarted() {
        scanner("a")
        scanner("b")
        val renewal = renewal()
        assertEquals(RenewalOutcome.STARTED, renewal.renewNow("x", force = true))
        registry.unregister("b")
        clock.advance(ScanRenewalPolicy.STOP_GAP_MS)
        assertEquals(listOf("stop:a", "stop:b", "start:a"), events)
    }

    @Test
    fun stopAllStopsEveryScannerOnceAndNothingBringsThemBack() {
        scanner("a")
        scanner("probe", renewable = false)
        assertEquals(2, registry.stopAll())
        assertEquals(0, registry.stopAll()) // idempotent
        assertEquals(listOf("stop:a", "stop:probe"), events)
        assertEquals(ScanState.HALTED, registry.state("a"))
        registry.resume() // Bluetooth-on or renewal cannot restart a halted scanner
        val renewal = renewal()
        assertEquals(RenewalOutcome.NOTHING_TO_RENEW, renewal.renewNow("x", force = true))
        assertEquals(2, events.size)
        // The owner's own close still unregisters it.
        registry.unregister("a")
        registry.unregister("probe")
        assertEquals(0, registry.size)
    }

    @Test
    fun registryTracksTheAppScanClockAndOccupancy() {
        val seen = ArrayList<Boolean>()
        registry.onOccupancyChanged = { seen += it }
        assertEquals(0L, registry.activeSinceMs)
        scanner("a")
        val started = clock.now
        assertEquals(started, registry.activeSinceMs)
        clock.advance(5_000L)
        scanner("b")
        assertEquals(started, registry.activeSinceMs)
        assertEquals(5_000L, registry.oldestRunningAgeMs())
        registry.unregister("a")
        registry.unregister("b")
        assertEquals(0L, registry.activeSinceMs)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun workerThreadsWaitForAGateSlotInsteadOfStartingOverTheQuota() {
        val sleeps = ArrayList<Long>()
        repeat(5) { assertEquals(0L, gate.reserve(budget)) }
        val ok = ScanPolicy.reserveBlocking(gate, budget, maxWaitMs = 40_000L, sleep = { ms -> sleeps += ms; clock.advance(ms) })
        assertTrue(ok)
        assertEquals(1, sleeps.size)
        assertTrue(sleeps[0] > 29_000L) // waited for the oldest start to leave the 30 s window
        assertEquals(1, gate.used(budget))
        // Too long a wait gives up without recording a start.
        repeat(4) { assertEquals(0L, gate.reserve(budget)) }
        val before = gate.used(budget)
        assertFalse(ScanPolicy.reserveBlocking(gate, budget, maxWaitMs = 1_000L, sleep = { clock.advance(it) }))
        assertEquals(before, gate.used(budget))
        // A null budget (entry off) never waits.
        assertTrue(ScanPolicy.reserveBlocking(gate, null))
    }

}
