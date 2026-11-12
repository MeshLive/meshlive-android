package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The attach coordinator's leak rules: one attach, backoff then stop, stale grants closed, holders never called inline. */
class AwareAttachCoordinatorTest {
    private class FakeHolder(private val label: String) : AwareHolder<String> {
        val attached = mutableListOf<String>()
        val failed = mutableListOf<AttachFailure>()
        val down = mutableListOf<AwareDownReason>()
        override fun onAttached(session: String) {
            attached += session
        }
        override fun onAttachFailed(reason: AttachFailure) {
            failed += reason
        }
        override fun onAwareDown(reason: AwareDownReason) {
            down += reason
        }
        override fun toString() = label
    }

    private class FakePort : AwareAttachPort<String> {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var precheckResult: AttachFailure? = null
        var deviceAttached: Boolean? = null
        var startThrows = false
        var startCount = 0
        var closeCount = 0
        var unregisterCount = 0
        val closed = mutableListOf<String>()
        private val posted = ArrayDeque<() -> Unit>()
        private val delayed = LinkedHashMap<Any, () -> Unit>()

        override fun precheck(): AttachFailure? {
            calls += "precheck"
            return precheckResult
        }

        override fun deviceAttachedByAnyone(): Boolean? {
            calls += "deviceAttached"
            return deviceAttached
        }

        override fun startAttach(token: Long) {
            calls += "start"
            startCount++
            if (startThrows) throw RuntimeException("attach refused")
        }

        override fun closeSession(session: String) {
            calls += "close"
            closeCount++
            closed += session
        }

        override fun registerReceivers() {
            calls += "register"
        }

        override fun unregisterReceivers() {
            calls += "unregister"
            unregisterCount++
        }

        override fun post(block: () -> Unit) {
            posted.addLast(block)
        }

        override fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit) {
            calls += "postDelayed"
            delayed[tag] = block
        }

        override fun cancel(tag: Any) {
            calls += "cancel"
            delayed.remove(tag)
        }

        override fun log(line: String) {
            logs += line
        }

        fun drain() {
            while (posted.isNotEmpty()) posted.removeFirst().invoke()
        }

        fun fireDelayed() {
            val blocks = delayed.values.toList()
            delayed.clear()
            blocks.forEach { it() }
        }
    }

    private class Fixture {
        var now = 0L
        val ledger = WifiLedger { now }
        val port = FakePort()
        val coordinator = AwareAttachCoordinator(port, ledger) { now }
    }

    private fun hold(id: Long, owner: String = "owner") = AwareHold(id, owner)

    @Test
    fun firstAcquireStartsOneAttachAndServesBothHolders() {
        val f = Fixture()
        val first = FakeHolder("first")
        val second = FakeHolder("second")
        f.coordinator.acquire(hold(1), first)
        f.coordinator.acquire(hold(2), second)
        assertEquals(1, f.port.startCount)
        f.coordinator.onAttached(1, "s")
        assertTrue("holder callbacks are posted, never inline", first.attached.isEmpty() && second.attached.isEmpty())
        f.port.drain()
        assertEquals(listOf("s"), first.attached)
        assertEquals(listOf("s"), second.attached)
    }

    @Test
    fun lastReleaseClosesTheSessionOnce() {
        val f = Fixture()
        val first = FakeHolder("first")
        val second = FakeHolder("second")
        val h1 = hold(1)
        val h2 = hold(2)
        f.coordinator.acquire(h1, first)
        f.coordinator.acquire(h2, second)
        f.coordinator.onAttached(1, "s")
        f.port.drain()
        assertTrue(f.coordinator.release(h1))
        assertEquals(0, f.port.closeCount)
        assertTrue(f.coordinator.release(h2))
        assertEquals(1, f.port.closeCount)
        assertTrue(f.port.unregisterCount >= 1)
        assertFalse(f.coordinator.release(h2))
        assertEquals(1, f.port.closeCount)
        assertFalse(f.coordinator.heldOrAttaching)
    }

    @Test
    fun receiversAreRegisteredBeforePrecheck() {
        val f = Fixture()
        f.coordinator.acquire(hold(1), FakeHolder("h"))
        val register = f.port.calls.indexOf("register")
        val precheck = f.port.calls.indexOf("precheck")
        assertTrue("register ($register) must come before precheck ($precheck)", register >= 0 && register < precheck)
    }

    @Test
    fun precheckFailureUnregistersAndFailsHolderWithoutFault() {
        val f = Fixture()
        f.port.precheckResult = AttachFailure.WIFI_OFF
        val holder = FakeHolder("h")
        f.coordinator.acquire(hold(1), holder)
        f.port.drain()
        assertEquals(listOf(AttachFailure.WIFI_OFF), holder.failed)
        assertEquals(0L, f.ledger.pauseUntilMs)
        assertFalse(f.coordinator.heldOrAttaching)
        assertTrue(f.port.unregisterCount >= 1)
        f.port.precheckResult = null
        f.coordinator.acquire(hold(2), FakeHolder("again"))
        assertEquals("no backoff after a precheck failure", 1, f.port.startCount)
    }

    @Test
    fun attachRefusedBacksOffExponentially() {
        val f = Fixture()
        f.coordinator.acquire(hold(1), FakeHolder("1"))
        f.coordinator.onAttachFailed(1, AttachFailure.ATTACH_REFUSED)

        f.now = 1_000
        val early = FakeHolder("early")
        f.coordinator.acquire(hold(2), early)
        f.port.drain()
        assertEquals(listOf(AttachFailure.BACKOFF), early.failed)
        assertEquals(1, f.port.startCount)

        f.now = 2_000
        f.coordinator.acquire(hold(3), FakeHolder("3"))
        assertEquals("the retry happens when the backoff ends", 2, f.port.startCount)
        f.coordinator.onAttachFailed(2, AttachFailure.ATTACH_REFUSED)

        f.now = 5_000
        val secondBackoff = FakeHolder("secondBackoff")
        f.coordinator.acquire(hold(4), secondBackoff)
        f.port.drain()
        assertEquals(listOf(AttachFailure.BACKOFF), secondBackoff.failed)

        f.now = 6_000
        f.coordinator.acquire(hold(5), FakeHolder("5"))
        assertEquals(3, f.port.startCount)
        f.coordinator.onAttachFailed(3, AttachFailure.ATTACH_REFUSED)
        assertEquals("each failure is one ledger fault", 6_000 + WifiLimits.LOCAL_FAULT_PAUSE_MS, f.ledger.pauseUntilMs)
    }

    @Test
    fun sixConsecutiveFailuresDisableAttachForAnHour() {
        val f = Fixture()
        val starts = mutableListOf(0L)
        f.now = 0
        var token = 0L
        repeat(6) { index ->
            f.coordinator.acquire(hold(index + 1L), FakeHolder("h$index"))
            token++
            f.coordinator.onAttachFailed(token, AttachFailure.ATTACH_REFUSED)
            f.now += listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L)[index]
            if (index < 5) starts += f.now
        }
        assertEquals(6, f.port.startCount)

        val disabledAt = f.now
        f.now = disabledAt + 30 * 60_000L
        val late = FakeHolder("late")
        f.coordinator.acquire(hold(20), late)
        f.port.drain()
        assertEquals(listOf(AttachFailure.DISABLED), late.failed)
        assertEquals(6, f.port.startCount)

        f.now = disabledAt + WifiRadioLimits.ATTACH_DISABLED_MS
        f.coordinator.acquire(hold(21), FakeHolder("recovered"))
        assertEquals("attach is available again after the hour", 7, f.port.startCount)

        // A success resets the counter: three failures, one grant, one failure backs off 2 s again.
        val g = Fixture()
        g.now = 0
        var token2 = 0L
        repeat(3) { index ->
            g.coordinator.acquire(hold(index + 1L), FakeHolder("g$index"))
            token2++
            g.coordinator.onAttachFailed(token2, AttachFailure.ATTACH_REFUSED)
            g.now += listOf(2_000L, 4_000L, 8_000L)[index]
        }
        val successHold = hold(10)
        g.coordinator.acquire(successHold, FakeHolder("ok"))
        token2++
        g.coordinator.onAttached(token2, "s")
        g.port.drain()
        g.coordinator.release(successHold)
        g.coordinator.acquire(hold(11), FakeHolder("after"))
        token2++
        assertEquals("the success did not disable or refuse", 5, g.port.startCount)
        g.coordinator.onAttachFailed(token2, AttachFailure.ATTACH_REFUSED)
        val afterSuccess = FakeHolder("afterSuccess")
        val failedAt = g.now
        g.coordinator.acquire(hold(12), afterSuccess)
        g.port.drain()
        assertEquals(listOf(AttachFailure.BACKOFF), afterSuccess.failed)
        g.now = failedAt + WifiRadioLimits.ATTACH_BACKOFF_BASE_MS
        g.coordinator.acquire(hold(13), FakeHolder("retry"))
        assertEquals("a fresh failure backs off 2 s again", 6, g.port.startCount)
    }

    @Test
    fun timeoutInvalidatesLateGrant() {
        val f = Fixture()
        f.now = 0
        val first = FakeHolder("first")
        f.coordinator.acquire(hold(1), first)
        f.port.fireDelayed()
        f.port.drain()
        assertEquals(listOf(AttachFailure.ATTACH_TIMEOUT), first.failed)

        f.now = 2_000
        f.coordinator.acquire(hold(2), FakeHolder("second"))
        f.port.fireDelayed()
        f.port.drain()

        f.now = 6_000
        f.coordinator.acquire(hold(3), FakeHolder("third"))
        f.port.fireDelayed()
        f.port.drain()
        assertEquals("three timeouts are three ledger faults", 6_000 + WifiLimits.LOCAL_FAULT_PAUSE_MS, f.ledger.pauseUntilMs)

        f.coordinator.onAttached(1, "late")
        assertEquals(listOf("late"), f.port.closed)
        assertTrue(first.attached.isEmpty())
    }

    @Test
    fun releaseWhileAttachingNeverStartsASecondAttach() {
        val f = Fixture()
        val h1 = hold(1)
        f.coordinator.acquire(h1, FakeHolder("first"))
        assertTrue(f.coordinator.release(h1))
        val second = FakeHolder("second")
        f.coordinator.acquire(hold(2), second)
        assertEquals(1, f.port.startCount)
        f.coordinator.onAttached(1, "s")
        f.port.drain()
        assertEquals(listOf("s"), second.attached)

        val g = Fixture()
        val abandoned = hold(1)
        g.coordinator.acquire(abandoned, FakeHolder("abandoned"))
        assertTrue(g.coordinator.release(abandoned))
        g.coordinator.onAttached(1, "s")
        assertEquals("an abandoned grant is closed at once", listOf("s"), g.port.closed)
        assertTrue(g.port.calls.contains("close"))
    }

    @Test
    fun awareDownNotifiesHoldersAndClosesSession() {
        val f = Fixture()
        val first = FakeHolder("first")
        val second = FakeHolder("second")
        val h1 = hold(1)
        f.coordinator.acquire(h1, first)
        f.coordinator.acquire(hold(2), second)
        f.coordinator.onAttached(1, "s")
        f.port.drain()

        f.coordinator.onAwareUnavailable(AwareDownReason.UNAVAILABLE)
        f.port.drain()
        assertEquals(listOf(AwareDownReason.UNAVAILABLE), first.down)
        assertEquals(listOf(AwareDownReason.UNAVAILABLE), second.down)
        assertEquals(1, f.port.closeCount)
        assertTrue(f.port.unregisterCount >= 1)
        assertFalse("the holds are gone", f.coordinator.release(h1))
        assertEquals(0L, f.ledger.pauseUntilMs)
        f.coordinator.acquire(hold(3), FakeHolder("again"))
        assertEquals("an Aware down edge is not a backoff", 2, f.port.startCount)
    }

    @Test
    fun wifiOffWhileAttachingFailsHoldersAndStaleGrantIsClosed() {
        val f = Fixture()
        val holder = FakeHolder("h")
        f.coordinator.acquire(hold(1), holder)
        f.coordinator.onAwareUnavailable(AwareDownReason.WIFI_OFF)
        f.port.drain()
        assertEquals(listOf(AttachFailure.WIFI_OFF), holder.failed)
        f.coordinator.onAttached(1, "s")
        assertEquals(listOf("s"), f.port.closed)
    }

    @Test
    fun directGroupBlocksAttachAndAttachBlocksDirect() {
        val f = Fixture()
        f.ledger.foreignDirectGroup = true
        val holder = FakeHolder("h")
        f.coordinator.acquire(hold(1), holder)
        f.port.drain()
        assertEquals(listOf(AttachFailure.BUSY_DIRECT), holder.failed)
        assertEquals(0, f.port.startCount)
        assertTrue(f.ledger.directGroupActive)

        f.ledger.foreignDirectGroup = false
        f.coordinator.acquire(hold(2), FakeHolder("ok"))
        f.coordinator.onAttached(1, "s")
        f.port.drain()
        assertEquals(DirectReserve.BUSY_AWARE, f.ledger.reserveDirect("direct"))
    }

    @Test
    fun startAttachThrowingCountsAsFailure() {
        val f = Fixture()
        f.port.startThrows = true
        val holder = FakeHolder("h")
        f.coordinator.acquire(hold(1), holder)
        f.port.drain()
        assertEquals(listOf(AttachFailure.EXCEPTION), holder.failed)
        assertTrue(f.port.unregisterCount >= 1)
        f.coordinator.acquire(hold(2), FakeHolder("retry"))
        f.port.drain()
        assertEquals("the throw set a backoff", 1, f.port.startCount)
    }

    @Test
    fun otherClientIsReadBeforeTheAttach() {
        val f = Fixture()
        f.port.deviceAttached = true
        f.coordinator.acquire(hold(1), FakeHolder("h"))
        assertEquals(true, f.ledger.otherClientAtAttach)
        val read = f.port.calls.indexOf("deviceAttached")
        val start = f.port.calls.indexOf("start")
        assertTrue("read ($read) before start ($start)", read >= 0 && read < start)
    }

    @Test
    fun shutdownTellsEveryHolderShutdownInBothStates() {
        val f = Fixture()
        val first = FakeHolder("first")
        val second = FakeHolder("second")
        f.coordinator.acquire(hold(1), first)
        f.coordinator.acquire(hold(2), second)
        f.coordinator.onAttached(1, "s")
        f.port.drain()
        f.coordinator.shutdown()
        f.port.drain()
        assertEquals(listOf(AwareDownReason.SHUTDOWN), first.down)
        assertEquals(listOf(AwareDownReason.SHUTDOWN), second.down)
        assertTrue(first.failed.isEmpty() && second.failed.isEmpty())
        assertEquals(1, f.port.closeCount)
        assertFalse(f.coordinator.release(hold(1)))

        val g = Fixture()
        val attaching = FakeHolder("attaching")
        g.coordinator.acquire(hold(1), attaching)
        g.coordinator.shutdown()
        g.port.drain()
        assertEquals(listOf(AwareDownReason.SHUTDOWN), attaching.down)
        assertTrue(attaching.failed.isEmpty())
        g.coordinator.onAttached(1, "late")
        assertEquals(listOf("late"), g.port.closed)
    }

    @Test
    fun aStateChangeWhileAttachedRecyclesTheSession() {
        val f = Fixture()
        val holder = FakeHolder("h")
        f.coordinator.acquire(hold(1), holder)
        f.coordinator.onAttached(1, "s")
        f.port.drain()
        f.coordinator.onAwareUnavailable(AwareDownReason.UNAVAILABLE)
        f.port.drain()
        assertEquals(listOf(AwareDownReason.UNAVAILABLE), holder.down)
        assertEquals(1, f.port.closeCount)
        assertEquals(0L, f.ledger.pauseUntilMs)
        f.coordinator.acquire(hold(2), FakeHolder("second"))
        assertEquals("a fresh acquire starts a second attach", 2, f.port.startCount)
    }
}
