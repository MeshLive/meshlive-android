package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdmissionTest {
    private val released = ArrayList<String>()
    private var closed = 0
    private val scheduler = ManualScheduler()
    private val admission = NativeAdmission(scheduler, release = { owner -> released += owner })

    @Test
    fun anUnfinishedStrangerIsReleasedAtTenSeconds() {
        admission.accepted(1, "native-1") { closed++ }
        scheduler.advance(9_999)
        assertEquals(0, closed)
        assertTrue(released.isEmpty())
        scheduler.advance(1)
        assertEquals(1, closed)
        assertEquals(listOf("native-1"), released)
        scheduler.advance(60_000)
        assertEquals(1, closed) // once
    }

    @Test
    fun anAdmittedLinkIsNeverClosedByTheTimer() {
        admission.accepted(1, "native-1") { closed++ }
        admission.admitted(1)
        scheduler.advance(60_000)
        assertEquals(0, closed)
        assertTrue(released.isEmpty())
    }

    @Test
    fun anAuthenticatedUpgradePreemptsAPendingStranger() {
        val ledger = WifiLedger { 0L }
        assertTrue(ledger.reserveUntrustedNdp("native-1", 1) { admission.preemptPending() })
        admission.accepted(1, "native-1") { closed++ }
        assertTrue(ledger.reserveNdp("wifi-1", 1))
        assertEquals(1, closed)
        assertEquals(1, ledger.ndpReserved)
        scheduler.advance(60_000)
        assertEquals(1, closed) // the preemption cancelled the timer
    }

    @Test
    fun aTrustedNativeLinkIsNotPreempted() {
        val ledger = WifiLedger { 0L }
        assertTrue(ledger.reserveUntrustedNdp("native-1", 1) { admission.preemptPending() })
        admission.accepted(1, "native-1") { closed++ }
        admission.admitted(1)
        ledger.trustNdp("native-1")
        assertFalse(ledger.reserveNdp("wifi-1", 1))
        assertEquals(0, closed)
        assertTrue(released.isEmpty())
    }

    /**
     * A8 review P1-3: our own native dial reserves on its connect thread and finds the budget full while a stranger's
     * exchange is still pending (no socket yet). A4's ledger runs the preemption on that thread; ending the stranger
     * (which touches WifiNativeLink's wifi-thread maps through the exchange callbacks) must wait for the wifi thread.
     */
    @Test
    fun aPreemptionFromTheDialThreadEndsTheStrangerOnlyOnTheWifiThread() {
        scheduler.strictThreads = true
        val ledger = WifiLedger { 0L }
        val endedOn = ArrayList<Boolean>()
        scheduler.post {   // onKnock runs on the wifi thread: register, then reserve
            admission.reserved(1, "native-1") { endedOn += scheduler.isWifiThread() }
            assertTrue(ledger.reserveUntrustedNdp("native-1", 1) { admission.preemptPending() })
        }
        scheduler.drain()

        var reserved = false
        val dial = Thread { reserved = ledger.reserveNdp("native-2", 1) }   // the call's connect thread
        dial.start()
        dial.join()
        assertTrue(reserved)
        assertTrue(endedOn.isEmpty())                 // nothing of the stranger was touched on the dial thread
        assertEquals(0, admission.pending())          // but it was taken at once: a second preemption finds nothing
        assertEquals(1, ledger.ndpReserved)

        scheduler.drain()
        assertEquals(listOf(true), endedOn)           // ended exactly once, on the wifi thread
        assertEquals(listOf("native-1"), released)
        scheduler.advance(60_000)
        assertEquals(1, endedOn.size)
    }

    @Test
    fun aPreemptionOnTheWifiThreadEndsTheStrangerAtOnce() {
        scheduler.strictThreads = true
        val ledger = WifiLedger { 0L }
        var ended = 0
        scheduler.post {
            admission.reserved(1, "native-1") { ended++ }
            assertTrue(ledger.reserveUntrustedNdp("native-1", 1) { admission.preemptPending() })
            assertTrue(ledger.reserveNdp("wifi-1", 1))   // an A5/A7 agent reserving on the wifi thread
            assertEquals(1, ended)
        }
        scheduler.drain()
        assertEquals(1, ended)
    }

    @Test
    fun aSocketAcceptedBetweenThePreemptionAndItsRunIsClosedAndItsTimerCancelled() {
        scheduler.strictThreads = true
        val ledger = WifiLedger { 0L }
        var ended = 0
        var deadlineClosed = 0
        scheduler.post {
            admission.reserved(1, "native-1") { ended++ }
            ledger.reserveUntrustedNdp("native-1", 1) { admission.preemptPending() }
        }
        scheduler.drain()
        // The socket is accepted (queued on the wifi thread) just before our dial preempts the stranger.
        scheduler.post { admission.accepted(1, "native-1") { deadlineClosed++ } }
        val dial = Thread { ledger.reserveNdp("native-2", 1) }
        dial.start()
        dial.join()
        scheduler.drain()
        scheduler.advance(60_000)
        assertEquals(1, ended)
        assertEquals(0, deadlineClosed)
    }
}
