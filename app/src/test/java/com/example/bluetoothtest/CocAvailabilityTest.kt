package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Call availability hardening (coc-listener-exhaustion report): the partial-open cleanup, the persisted Android 10 CoC
 * budget, the listen watchdog and the honest "Calls unavailable" status, driven through the report's timelines.
 */
class CocAvailabilityTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    private class FakeSocket : Closeable {
        @Volatile var closed = false
        override fun close() {
            closed = true
        }
    }

    private class FakeServer(override val psm: Int) : CocServerHandle<FakeSocket> {
        private val incoming = LinkedBlockingQueue<Any>()
        override fun accept(): FakeSocket {
            val item = incoming.take()
            if (item is IOException) {
                incoming.offer(item)
                throw item
            }
            return item as FakeSocket
        }

        override fun close() {
            incoming.offer(IOException("closed"))
        }
    }

    private fun sharedListener() = SharedCocListener("private-call", open = { FakeServer(0x80) }, log = {})

    /** The endpoint's open: lease first, then the control server, which fails (injected `control_fail`). */
    private fun openWithFailingControl(listener: SharedCocListener<FakeSocket>, cleanup: Boolean) {
        var lease: CocListenLease<FakeSocket>? = null
        try {
            PartialOpen.run(cleanup, release = { lease?.close(); lease = null }, log = {}) {
                lease = listener.acquire("private-call")
                throw IOException("control server failed to start")
            }
        } catch (_: IOException) {
            return
        }
        fail("the open failure must still reach the caller")
    }

    // ---- 1. partial-open cleanup -----------------------------------------------------------------

    @Test
    fun failedOpenAfterListenReleasesTheSharedLeaseSoTheNextSessionReusesIt() {
        val listener = sharedListener()
        openWithFailingControl(listener, cleanup = true)
        val next = listener.acquire("private-call")
        assertNotNull("the next availability gets the shared listener, not a dedicated (leaking) one", next)
        assertTrue(next!!.shared)
        assertEquals(1, listener.listenCount)
    }

    @Test
    fun controlArmKeepsTheOrphanedLeaseAndTheRoleBusy() {
        val listener = sharedListener()
        openWithFailingControl(listener, cleanup = false)
        assertNull("off: the orphaned lease still holds the role", listener.acquire("private-call"))
    }

    @Test
    fun successfulOpenReleasesNothing() {
        val listener = sharedListener()
        var released = false
        val lease = PartialOpen.run(true, release = { released = true }, log = {}) { listener.acquire("private-call") }
        assertFalse(released)
        assertNotNull(lease)
    }

    // ---- 2. persisted Android 10 budget --------------------------------------------------------------

    private class AppProcesses(private val threshold: Int = CocAutoGattSettings.DEFAULT_THRESHOLD) {
        var record: CocBudgetRecord? = null
        val boot = 7

        /** One app process start (a runner force-stop and launch); returns the transport the endpoint would use. */
        fun launch(psm: Int, persist: Boolean = true): CocTransportChoice {
            val ledger = CocRegistrationLedger()
            if (persist) ledger.carry(CocBudgetDetector.carriedAtStart(record, boot))
            val settings = CocAutoGattSettings(enabled = true, threshold = threshold, thresholdActive = true)
            val choice = CocAutoGattSession(ledger).choose(BluetoothTransportOption.BLE_L2CAP, settings)
            if (choice.effective == BluetoothTransportOption.BLE_L2CAP) {
                ledger.record()
                if (persist && CocBudgetDetector.newBluetoothProcess(record, psm, boot)) ledger.carry(0)
                record = CocBudgetRecord(psm, ledger.count, boot)
            }
            return choice
        }
    }

    @Test
    fun a15sRunnerLaunchesMoveToGattBeforeTheThirteenthListenFails() {
        // rw-P1b*: one private-call-shared listen per app process, PSMs 128, 130, 133 ... after a Settings Bluetooth cycle;
        // the 13th process (P1bR7) failed with "Error: -1".
        val processes = AppProcesses()
        val psms = listOf(128, 130, 133, 135, 138, 140, 143, 145, 148, 150, 153, 155, 158, 160, 163)
        val transports = psms.map { processes.launch(it) }
        transports.take(12).forEach { assertEquals(BluetoothTransportOption.BLE_L2CAP, it.effective) }
        transports.drop(12).forEach {
            assertEquals(BluetoothTransportOption.BLE_GATT, it.effective)
            assertEquals(CocFallbackReason.THRESHOLD, it.reason)
            assertEquals(12, it.registrations)
        }
    }

    @Test
    fun controlArmCountsFromZeroInEveryProcessAndNeverMoves() {
        val processes = AppProcesses()
        (0 until 15).map { processes.launch(128 + 2 * it, persist = false) }
            .forEach { assertEquals(BluetoothTransportOption.BLE_L2CAP, it.effective) }
    }

    @Test
    fun aBluetoothRestartIsSeenByThePsmReturningToTheStart() {
        val processes = AppProcesses()
        listOf(128, 130, 133, 135, 138).forEach { processes.launch(it) }
        assertEquals(5, processes.record!!.count)
        // Settings Bluetooth cycle while the app was not running: the allocator starts again at 0x80.
        processes.launch(128)
        assertEquals(1, processes.record!!.count)
        processes.launch(129)
        assertEquals(2, processes.record!!.count)
    }

    @Test
    fun psmDetectorRules() {
        val record = CocBudgetRecord(lastPsm = 150, count = 6, bootCount = 3)
        assertFalse("a rise is the same Bluetooth process", CocBudgetDetector.newBluetoothProcess(record, 151, 3))
        assertTrue("a drop is a restart", CocBudgetDetector.newBluetoothProcess(record, 128, 3))
        assertTrue("the same PSM again cannot come from the round-robin allocator", CocBudgetDetector.newBluetoothProcess(record, 150, 3))
        assertTrue("a reboot restarts Bluetooth", CocBudgetDetector.newBluetoothProcess(record, 151, 4))
        assertTrue("nothing known", CocBudgetDetector.newBluetoothProcess(null, 200, 3))
        // Wrap at 0xFF: indistinguishable from a restart by the PSM; read as one (a leaking stack runs out long before).
        assertTrue(CocBudgetDetector.newBluetoothProcess(CocBudgetRecord(0xFF, 4, 3), 0x80, 3))
        assertEquals(6, CocBudgetDetector.carriedAtStart(record, 3))
        assertEquals(0, CocBudgetDetector.carriedAtStart(record, 4))
        assertEquals(0, CocBudgetDetector.carriedAtStart(null, 3))
        assertFalse(CocBudgetDetector.validPsm(-1))
        assertFalse(CocBudgetDetector.validPsm(0x100))
        assertTrue(CocBudgetDetector.validPsm(0x80))
    }

    @Test
    fun aFixedStackNeverMovesOnTheCountAlone() {
        val ledger = CocRegistrationLedger()
        ledger.carry(40)
        val choice = CocAutoGattSession(ledger).choose(
            BluetoothTransportOption.BLE_L2CAP,
            CocAutoGattSettings(enabled = true, thresholdActive = false),
        )
        assertEquals(BluetoothTransportOption.BLE_L2CAP, choice.effective)
    }

    @Test
    fun adapterResetDropsTheCarriedCount() {
        val ledger = CocRegistrationLedger()
        ledger.carry(11)
        assertEquals(12, ledger.record())
        ledger.reset()
        assertEquals(0, ledger.count)
    }

    // ---- 3. listen watchdog ---------------------------------------------------------------------------

    @Test
    fun aListenTheStackNeverCompletesFailsWithinTheDeadlineAndItsLateSocketIsClosed() {
        val release = CountDownLatch(1)
        val late = FakeSocket()
        val lateClosed = CountDownLatch(1)
        val watchdog = ListenWatchdog<FakeSocket>(
            deadlineMs = 200,
            closeLate = { it.close(); lateClosed.countDown() },
        )
        val started = System.nanoTime()
        try {
            watchdog.run("private-call") {
                release.await()
                late
            }
            fail("a stalled listen must fail")
        } catch (stalled: ListenStalledException) {
            assertEquals("private-call", stalled.role)
            assertTrue(stalled.waitedMs >= 200)
        }
        assertTrue("failed near the deadline, not after the 30 s runner wait", (System.nanoTime() - started) / 1_000_000 < 5_000)
        release.countDown()
        assertTrue(lateClosed.await(10, TimeUnit.SECONDS))
        assertTrue(late.closed)
    }

    @Test
    fun aHealthyListenReturnsItsSocketAndItsErrorsUnchanged() {
        val watchdog = ListenWatchdog<FakeSocket>(deadlineMs = 5_000, closeLate = { fail("nothing is late") })
        val socket = FakeSocket()
        assertSame(socket, watchdog.run("private-call") { socket })
        assertFalse(socket.closed)
        val error = IOException("Error: -1")
        try {
            watchdog.run("private-call") { throw error }
            fail()
        } catch (thrown: IOException) {
            assertSame("the stack's own failure (A15s exhaustion) passes through", error, thrown)
        }
    }

    @Test
    fun watchdogSwitchClamps() {
        assertEquals(0L, CocAvailabilitySwitches.watchdogMs(0))
        assertEquals(0L, CocAvailabilitySwitches.watchdogMs(-5))
        assertEquals(5_000L, CocAvailabilitySwitches.watchdogMs(5_000))
        assertEquals(CocAvailabilitySwitches.MAX_WATCHDOG_MS, CocAvailabilitySwitches.watchdogMs(10_000_000))
    }

    // ---- 4. honest call availability ------------------------------------------------------------------

    private var now = 0L
    private val tracker = CallsAvailabilityTracker { now }

    private fun status(enabled: Boolean = true, reachable: Boolean = true, starting: Boolean): StatusKind {
        val reason = tracker.reason(enabled, reachable, starting, stallMs = 5_000)
        return statusKind(HomeReadiness.READY, hidden = false, relayPaused = false, hot = false, callsUnavailable = reason != null)
    }

    @Test
    fun aStartThatNeverFinishesBecomesCallsUnavailableInsteadOfGreenReady() {
        // S22 iab-c2-r02: the listen after "Call peer stopped responding" never returned; the chip stayed green.
        tracker.starting()
        now = 4_999
        assertEquals(StatusKind.READY, status(starting = true))
        now = 5_000
        assertEquals(StatusKind.CALLS_UNAVAILABLE, status(starting = true))
        assertEquals(CallsUnavailableReason.STALLED, tracker.reason(true, true, true, 5_000))
        assertEquals("the switch off keeps today's status", StatusKind.READY, status(enabled = false, starting = true))
    }

    @Test
    fun aFailedStartIsCallsUnavailableUntilTheNextStartSucceeds() {
        tracker.starting()
        tracker.failed(CallsUnavailableReason.EXHAUSTED)
        assertEquals(StatusKind.CALLS_UNAVAILABLE, status(starting = false))
        tracker.starting()
        assertEquals("a new attempt is not judged before its own deadline", StatusKind.READY, status(starting = true))
        tracker.available()
        now = 60_000
        assertEquals(StatusKind.READY, status(starting = false))
    }

    @Test
    fun callsTheUserHidIsNotReportedAsUnavailable() {
        tracker.failed(CallsUnavailableReason.FAILED)
        assertNull(tracker.reason(enabled = true, reachable = false, starting = false, stallMs = 5_000))
    }

    @Test
    fun unavailableOutranksHiddenButNotBluetoothOrStreams() {
        assertEquals(StatusKind.CALLS_UNAVAILABLE, statusKind(HomeReadiness.READY, true, false, false, callsUnavailable = true))
        assertEquals(StatusKind.BLUETOOTH_OFF, statusKind(HomeReadiness.BLUETOOTH_OFF, false, false, false, callsUnavailable = true))
        assertEquals(StatusKind.WATCHING, statusKind(HomeReadiness.READY, false, false, false, ReachabilityScreen.WATCH, callsUnavailable = true))
        assertTrue(StatusKind.CALLS_UNAVAILABLE.problem)
    }

    @Test
    fun reasonFollowsWhatTheListenDid() {
        val stalled = ListenStalledException("private-call", 5_000)
        assertEquals(CallsUnavailableReason.STALLED, CallsUnavailableReason.of(stalled, leakyStack = true, listenFailure = false))
        assertEquals(CallsUnavailableReason.STALLED, CallsUnavailableReason.of(IllegalStateException(stalled), false, false))
        assertEquals(CallsUnavailableReason.EXHAUSTED, CallsUnavailableReason.of(IOException("Error: -1"), leakyStack = true, listenFailure = true))
        assertEquals(CallsUnavailableReason.FAILED, CallsUnavailableReason.of(IOException("Error: -1"), leakyStack = false, listenFailure = true))
        assertEquals(CallsUnavailableReason.FAILED, CallsUnavailableReason.of(IOException("control"), leakyStack = true, listenFailure = false))
    }

    @Test
    fun aStalledListenAfterAPeerTimeoutIsRelabelledAsThisPhonesStack() {
        // iab-c2-r02: "Call peer stopped responding" at 03:30:02.43, the next listen hung at 03:30:02.505.
        assertTrue(CallsAvailabilityTracker.localStackEnd("Call link lost: Call peer stopped responding", 1_000, 6_100))
        assertFalse(CallsAvailabilityTracker.localStackEnd("Peer ended the call", 1_000, 6_100))
        assertFalse(CallsAvailabilityTracker.localStackEnd("Call link lost: Call peer stopped responding", 1_000, 1_000 + 120_000))
        assertFalse(CallsAvailabilityTracker.localStackEnd(null, 0, 1))
    }
}
