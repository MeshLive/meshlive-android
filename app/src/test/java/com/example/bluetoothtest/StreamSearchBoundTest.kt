package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A viewer's search for a lost stream is bounded (audit CALL-04): after 60 s it ends and the viewer returns to discovery. */
class StreamSearchBoundTest {
    private val bound = StreamSearchBound.DEFAULT_BOUND_MS

    @Test
    fun aSearchWithNoSightingEndsAtTheBoundAndNotBefore() {
        val search = StreamSearchBound { true }
        search.begin(1_000L)
        assertFalse(search.expired(1_000L + bound - 1))
        assertEquals(1L, search.remainingMs(1_000L + bound - 1))
        assertTrue(search.expired(1_000L + bound))
    }

    @Test
    fun failedAttemptsInsideTheSearchDoNotRestartItsClock() {
        val search = StreamSearchBound { true }
        search.begin(0L)
        // Two reconnect attempts fail 20 s and 45 s in: each re-enters the search, none gets a fresh 60 s.
        search.begin(20_000L)
        search.begin(45_000L)
        assertFalse(search.expired(59_999L))
        assertTrue(search.expired(60_000L))
    }

    @Test
    fun aReconnectThatCameBackEndsTheSearchAndTheNextLossGetsAFullBound() {
        val search = StreamSearchBound { true }
        search.begin(0L)
        search.end() // the stream is playing again
        assertNull(search.remainingMs(10_000L))
        assertFalse(search.expired(500_000L))
        search.begin(500_000L)
        assertFalse(search.expired(500_000L + bound - 1))
        assertTrue(search.expired(500_000L + bound))
    }

    @Test
    fun theControlArmNeverExpires() {
        val search = StreamSearchBound { false }
        search.begin(0L)
        assertFalse(search.expired(10 * bound))
        assertNull(search.remainingMs(0L))
    }

    @Test
    fun noSearchMeansNothingToExpire() {
        val search = StreamSearchBound { true }
        assertFalse(search.expired(1_000_000L))
        assertNull(search.remainingMs(0L))
    }
}
