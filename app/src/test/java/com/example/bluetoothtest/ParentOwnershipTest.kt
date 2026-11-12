package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ParentOwnershipTest {
    @Test
    fun ungatedClaimsNeverBlock() {
        val ownership = ParentOwnership()
        // Wider reach off: every worker gets the shared token; claims never fail and release changes nothing.
        val a = ownership.claim()
        val b = ownership.claim()
        assertNotNull(a)
        assertNotNull(b)
        assertTrue(a === ParentOwnership.UNGATED)
        assertTrue(b === ParentOwnership.UNGATED)
        assertFalse(ownership.owned())
        ownership.release(a!!)
        assertFalse(ownership.owned())
        assertTrue(ownership.endJoinIfUnowned())   // the Bluetooth join wait alone decides, as before Wider reach
        assertTrue(ownership.claim() === ParentOwnership.UNGATED)
    }

    @Test
    fun theSecondRadiosParentIsRefusedWhileTheFirstOwnsTheJoin() {
        val ownership = ParentOwnership().apply { beginJoin(gated = true) }
        val bluetooth = ownership.claim()
        assertNotNull(bluetooth)
        assertNull(ownership.claim())             // the Wi-Fi parent is refused and closes only its own connection
        assertTrue(ownership.owned())
        ownership.release(bluetooth!!)            // e.g. the parent's handshake failed
        assertFalse(ownership.owned())
        assertNotNull(ownership.claim())          // the other radio's parent may now take the join
    }

    @Test
    fun releaseByANonOwnerIsIgnored() {
        val ownership = ParentOwnership().apply { beginJoin(gated = true) }
        val owner = ownership.claim()
        assertNotNull(owner)
        ownership.release(Any())
        assertTrue(ownership.owned())
        ownership.release(owner!!)
        assertFalse(ownership.owned())
    }

    @Test
    fun theJoinTimeoutKeepsWaitingWhileAWifiParentOwnsTheJoin() {
        val ownership = ParentOwnership().apply { beginJoin(gated = true) }
        val wifi = ownership.claim()!!
        assertFalse(ownership.endJoinIfUnowned()) // the Bluetooth accept timed out: keep waiting
        assertTrue(ownership.owned())
        ownership.release(wifi)
        assertTrue(ownership.endJoinIfUnowned())  // the next timeout with nobody owning the join ends it
    }

    @Test
    fun aWifiParentArrivingAfterTheJoinEndedIsRefused() {
        val ownership = ParentOwnership().apply { beginJoin(gated = true) }
        assertTrue(ownership.endJoinIfUnowned())
        assertNull(ownership.claim())             // A8 review P2-5: never starts on a released surface
        assertFalse(ownership.owned())
        assertTrue(ownership.endJoinIfUnowned())  // idempotent
    }

    @Test
    fun aNewJoinReopensAnEndedOne() {
        val ownership = ParentOwnership().apply { beginJoin(gated = true) }
        ownership.endJoinIfUnowned()
        ownership.beginJoin(gated = true)
        assertNotNull(ownership.claim())
    }

    /** The race of A8 review P2-5: a Wi-Fi claim and the Bluetooth timeout's end, at the same moment, many times. */
    @Test
    fun exactlyOneOfAConcurrentClaimAndJoinEndWins() {
        repeat(500) {
            val ownership = ParentOwnership().apply { beginJoin(gated = true) }
            val start = CountDownLatch(1)
            val outcomes = AtomicInteger(0)
            var claimed: Any? = null
            var ended = false
            val wifi = thread {
                start.await()
                claimed = ownership.claim()
                if (claimed != null) outcomes.incrementAndGet()
            }
            val bluetooth = thread {
                start.await()
                ended = ownership.endJoinIfUnowned()
                if (ended) outcomes.incrementAndGet()
            }
            start.countDown()
            wifi.join()
            bluetooth.join()
            assertEquals(1, outcomes.get())
            assertEquals(claimed != null, !ended)
        }
    }
}
