package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone-wide Wi-Fi bookkeeping: fault window, NDP reservations (including the untrusted one), Direct exclusion. */
class WifiLedgerTest {
    private var now = 0L

    private fun ledger() = WifiLedger { now }

    private val minute = 60_000L

    @Test
    fun threeLocalFaultsInAnHourPauseAttemptsForAnHour() {
        val ledger = ledger()
        now = 0
        assertFalse(ledger.recordLocalFault("a"))
        now = 20 * minute
        assertFalse(ledger.recordLocalFault("b"))
        now = 40 * minute
        assertTrue(ledger.recordLocalFault("c"))
        assertEquals(100 * minute, ledger.pauseUntilMs)
        assertFalse("the history starts again after a pause", ledger.recordLocalFault("d"))
    }

    @Test
    fun aFaultOlderThanTheWindowDoesNotCount() {
        val ledger = ledger()
        now = 0
        assertFalse(ledger.recordLocalFault("a"))
        now = 30 * minute
        assertFalse(ledger.recordLocalFault("b"))
        now = 61 * minute
        assertFalse(ledger.recordLocalFault("c"))
        assertEquals(0L, ledger.pauseUntilMs)
    }

    @Test
    fun faultsThatAgedOutBeforeTheThirdDoNotPause() {
        val ledger = ledger()
        assertFalse(ledger.recordLocalFault("a"))
        now = 1 * minute
        assertFalse(ledger.recordLocalFault("b"))
        now = 61 * minute
        assertFalse(ledger.recordLocalFault("c"))
        now = 62 * minute
        assertFalse(ledger.recordLocalFault("d"))
        assertEquals(0L, ledger.pauseUntilMs)
    }

    @Test
    fun ndpReservationsAreIdempotentPerOwnerAndRespectTheCapacity() {
        val ledger = ledger()
        assertTrue(ledger.reserveNdp("a", 1))
        assertFalse(ledger.reserveNdp("b", 1))
        assertTrue("the same owner twice is idempotent", ledger.reserveNdp("a", 1))
        assertEquals(1, ledger.ndpReserved)
        assertTrue(ledger.releaseNdp("a"))
        assertEquals(0, ledger.ndpReserved)
        assertTrue(ledger.reserveNdp("b", 1))
    }

    @Test
    fun aLocalRequestErrorLowersTheLearnedCapAndZeroChangesNothing() {
        val ledger = ledger()
        ledger.noteNdpRefusedWhileActive(2)
        assertEquals(2, ledger.observedMaxNdp)
        ledger.noteNdpRefusedWhileActive(1)
        assertEquals(1, ledger.observedMaxNdp)
        ledger.noteNdpRefusedWhileActive(0)
        assertEquals(1, ledger.observedMaxNdp)
    }

    /** Same simple name as the hidden `ConnectivityManager.TooManyRequestsException` (the per-app callback limit). */
    private class TooManyRequestsException : RuntimeException()

    @Test
    fun onlyADataPathRefusalMayLowerTheLearnedCap() {
        // A4 review P3-10: running out of network callbacks or filing an invalid request says nothing about the chip.
        assertFalse(WifiRadioLimits.lowersLearnedNdpCap(TooManyRequestsException()))
        assertFalse(WifiRadioLimits.lowersLearnedNdpCap(IllegalArgumentException("bad request")))
        assertTrue(WifiRadioLimits.lowersLearnedNdpCap(IllegalStateException("no resources")))
        assertTrue(WifiRadioLimits.lowersLearnedNdpCap(UnsupportedOperationException()))
    }

    @Test
    fun directReservationFollowsTheExclusionOrder() {
        val ledger = ledger()
        ledger.awareHeld = true
        assertEquals(DirectReserve.BUSY_AWARE, ledger.reserveDirect("d1"))
        ledger.awareHeld = false
        ledger.foreignDirectGroup = true
        assertEquals(DirectReserve.BUSY_FOREIGN, ledger.reserveDirect("d1"))
        ledger.foreignDirectGroup = false
        assertEquals(DirectReserve.OK, ledger.reserveDirect("d1"))
        assertEquals(DirectReserve.BUSY_OWN, ledger.reserveDirect("d2"))
        ledger.releaseDirect("d2")
        assertEquals("only the owner may release it", DirectReserve.BUSY_OWN, ledger.reserveDirect("d2"))
        ledger.releaseDirect("d1")
        assertEquals(DirectReserve.OK, ledger.reserveDirect("d2"))
    }

    @Test
    fun directGroupActiveCoversAnOwnAndAForeignGroup() {
        val ledger = ledger()
        assertFalse(ledger.directGroupActive)
        assertEquals(DirectReserve.OK, ledger.reserveDirect("d1"))
        assertTrue(ledger.directGroupActive)
        ledger.releaseDirect("d1")
        assertFalse(ledger.directGroupActive)
        ledger.foreignDirectGroup = true
        assertTrue(ledger.directGroupActive)
    }

    @Test
    fun reserveDirectIsAtomicAcrossThreads() {
        repeat(1_000) {
            val ledger = ledger()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val ok = AtomicInteger()
            val first = Thread {
                start.await()
                if (ledger.reserveDirect("a") == DirectReserve.OK) ok.incrementAndGet()
                done.countDown()
            }
            val second = Thread {
                start.await()
                if (ledger.reserveDirect("b") == DirectReserve.OK) ok.incrementAndGet()
                done.countDown()
            }
            first.start()
            second.start()
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, ok.get())
        }
    }

    @Test
    fun anUntrustedReservationIsCappedAtOneAndPreemptedByAnAuthenticatedReserve() {
        val ledger = ledger()
        var preempts = 0
        assertTrue(ledger.reserveUntrustedNdp("n1", 1) { preempts++ })
        assertEquals(1, ledger.untrustedCount)
        assertFalse("at most one untrusted reservation", ledger.reserveUntrustedNdp("n2", 1) { preempts++ })
        assertTrue(ledger.reserveNdp("wifi-1", 1))
        assertEquals(1, preempts)
        assertEquals(1, ledger.ndpReserved)
        assertEquals(0, ledger.untrustedCount)
        assertEquals("the preempted owner is gone", false, ledger.releaseNdp("n1"))
    }

    @Test
    fun anUntrustedReserveNeverTakesTheLastPathWhileAnAuthenticatedHopWants() {
        val ledger = ledger()
        assertTrue(ledger.reserveNdp("ordinary", 2))
        ledger.setAuthenticatedWant("hop-1", true)
        assertFalse(ledger.reserveUntrustedNdp("n1", 2) {})
        ledger.setAuthenticatedWant("hop-1", false)
        assertTrue(ledger.reserveUntrustedNdp("n1", 2) {})
    }

    @Test
    fun aTrustedReservationIsNoLongerPreempted() {
        val ledger = ledger()
        assertTrue(ledger.reserveUntrustedNdp("n1", 1) {})
        ledger.trustNdp("n1")
        assertEquals(0, ledger.untrustedCount)
        assertFalse("a trusted reservation is ordinary and full", ledger.reserveNdp("wifi-1", 1))
        assertEquals(1, ledger.ndpReserved)
    }

    @Test
    fun aReleaseCallsTheListenersOnceOutsideTheLock() {
        val ledger = ledger()
        var calls = 0
        ledger.addNdpReleaseListener {
            calls++
            ledger.ndpReserved // a listener that reads the ledger must not deadlock
        }
        assertTrue(ledger.reserveNdp("a", 1))
        assertFalse(ledger.releaseNdp("unknown"))
        assertEquals(0, calls)
        assertTrue(ledger.releaseNdp("a"))
        assertEquals(1, calls)
        assertEquals(0, ledger.ndpReserved)
    }

    @Test
    fun releaseAllClaimsFreesEveryReservation() {
        val ledger = ledger()
        var calls = 0
        ledger.addNdpReleaseListener { calls++ }
        assertTrue(ledger.reserveNdp("a", 2))
        assertEquals(DirectReserve.OK, ledger.reserveDirect("d1"))
        ledger.releaseAllClaims()
        assertEquals(0, ledger.ndpReserved)
        assertFalse(ledger.directGroupActive)
        assertEquals(1, calls)
        assertNull(ledger.observedMaxNdp)
    }

    @Test
    fun aShutdownReleaseLetsAListenerWaitOnAnotherThreadThatReadsTheLedger() {
        // A5's listener may take its own lock while another thread holding that lock reads the ledger: the listener
        // must run with the ledger lock free, or the wipe hangs (A4 review P2-1).
        val ledger = ledger()
        for ((name, release) in listOf<Pair<String, () -> Unit>>(
            "releaseNdp" to { ledger.releaseNdp("a") },
            "releaseAllClaims" to { ledger.releaseAllClaims() },
        )) {
            var readerFinished = false
            val listener: (String) -> Unit = {
                val reader = Thread { ledger.ndpReserved; ledger.untrustedCount }
                reader.start()
                reader.join(2_000)
                readerFinished = !reader.isAlive
            }
            ledger.addNdpReleaseListener(listener)
            assertTrue(ledger.reserveNdp("a", 1))
            release()
            ledger.removeNdpReleaseListener(listener)
            assertTrue("$name ran its listener with the ledger lock free", readerFinished)
        }
    }

    @Test
    fun releaseAllClaimsKeepsAQuarantinedDirectClaim() {
        val ledger = ledger()
        assertEquals(DirectReserve.OK, ledger.reserveDirect("d1"))
        ledger.directQuarantined = true
        ledger.releaseAllClaims()
        assertTrue("an unconfirmed Direct group stays claimed", ledger.directGroupActive)
        ledger.directQuarantined = false
        ledger.releaseAllClaims()
        assertFalse(ledger.directGroupActive)
    }

    @Test
    fun aTwoPointFourAwarePathIsLiveUntilItsLastHopLetsGo() {
        // AP10 (WIFI-19): the capacity key reads this while any hop carries on a 2.4 GHz-only Aware data path.
        val ledger = ledger()
        assertFalse(ledger.band24PathLive)
        ledger.setBand24Path("hop-1", true)
        ledger.setBand24Path("hop-2", true)
        ledger.setBand24Path("hop-1", true) // the same hop again is still one path
        assertTrue(ledger.band24PathLive)
        ledger.setBand24Path("hop-1", false)
        assertTrue("the second hop still carries on 2.4 GHz", ledger.band24PathLive)
        ledger.setBand24Path("hop-2", false)
        assertFalse(ledger.band24PathLive)
        ledger.setBand24Path("hop-3", false) // a hop that never had one changes nothing
        assertFalse(ledger.band24PathLive)
    }

    @Test
    fun aShutdownReleaseAlsoEndsTheTwoPointFourAwareMark() {
        val ledger = ledger()
        ledger.setBand24Path("hop-1", true)
        ledger.releaseAllClaims()
        assertFalse(ledger.band24PathLive)
    }
}
