package com.example.bluetoothtest

import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Experimental relay_establish_keep_2m: which failed relay child setups pin the child to 1M. */
class RelayEstablishKeep2MTest {
    @After
    fun resetSwitch() {
        DebugTunables.relayEstablishKeep2M = false
    }

    private fun decide(keep: Boolean, reached: Boolean, hopAuthenticated: Boolean = false, phy: BlePhyPreference? = BlePhyPreference.TWO_M) =
        relayChildSetupPhyDecision(
            guardOn = true,
            attemptPhy = phy,
            hopAuthenticated = hopAuthenticated,
            keepOnEstablishment = keep,
            reachedConnection = reached,
        )

    @Test
    fun switchOffIsTheOldRuleForEveryReachState() {
        for (reached in listOf(false, true)) {
            assertEquals(RelayChildSetupPhy.PIN_1M, decide(keep = false, reached = reached))
            assertTrue(relayChildSetupRetriesOn1M(true, BlePhyPreference.TWO_M, false, keepOnEstablishment = false, reachedConnection = reached))
        }
        // The three-argument form used before the switch existed.
        assertTrue(relayChildSetupRetriesOn1M(true, BlePhyPreference.TWO_M, false))
        assertFalse(relayChildSetupRetriesOn1M(true, BlePhyPreference.TWO_M, true))
        assertFalse(relayChildSetupRetriesOn1M(false, BlePhyPreference.TWO_M, false))
    }

    @Test
    fun switchOnEstablishmentFailureKeepsTwoM() {
        // lane15 chain2 19:37:46: status 133 at the connection callback, state 0, no link ever existed.
        assertEquals(RelayChildSetupPhy.KEEP_2M_ESTABLISHMENT, decide(keep = true, reached = false))
        assertFalse(relayChildSetupRetriesOn1M(true, BlePhyPreference.TWO_M, false, keepOnEstablishment = true, reachedConnection = false))
    }

    @Test
    fun switchOnFailureAfterTheLinkExistedStillPins1M() {
        // mesh4-c0200edf-loop7-1 0004: CoC connect failed 6 ms after the 2M request while the PHY update was in flight.
        assertEquals(RelayChildSetupPhy.PIN_1M, decide(keep = true, reached = true))
        assertTrue(relayChildSetupRetriesOn1M(true, BlePhyPreference.TWO_M, false, keepOnEstablishment = true, reachedConnection = true))
    }

    @Test
    fun anAuthenticatedHopA1MAttemptNoAttemptAndTheGuardOffAreUnchangedWithTheSwitchOn() {
        for (reached in listOf(false, true)) {
            assertEquals(RelayChildSetupPhy.UNCHANGED, decide(keep = true, reached = reached, hopAuthenticated = true))
            assertEquals(RelayChildSetupPhy.UNCHANGED, decide(keep = true, reached = reached, phy = BlePhyPreference.ONE_M_ADAPTIVE))
            assertEquals(RelayChildSetupPhy.UNCHANGED, decide(keep = true, reached = reached, phy = null))
            assertEquals(
                RelayChildSetupPhy.UNCHANGED,
                relayChildSetupPhyDecision(false, BlePhyPreference.TWO_M, false, keepOnEstablishment = true, reachedConnection = reached),
            )
        }
    }

    @Test
    fun theTransportMarkIsOnlyAppliedWithTheSwitchOnAndALinkThatExisted() {
        val failure = IOException("GATT video connection failed (status 133)")
        // Off: the exception is passed through untouched, so the old log text and types are unchanged.
        assertSame(failure, carryLinkReached(failure, reached = true))
        DebugTunables.relayEstablishKeep2M = true
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extra: the exception is never marked.
            assertSame(failure, carryLinkReached(failure, reached = true))
            return
        }
        assertSame(failure, carryLinkReached(failure, reached = false))
        val marked = carryLinkReached(failure, reached = true)
        assertTrue(marked is LinkReachedSetupFailure)
        assertEquals(failure.message, marked.message)
        assertSame(failure, marked.cause)
        // Never marked twice, and an exhausted establishment budget keeps its own type.
        assertSame(marked, carryLinkReached(marked, reached = true))
        val exhausted = EstablishmentBudgetExhausted(failure.message, 3, 5_000L, failure)
        assertSame(exhausted, carryLinkReached(exhausted, reached = true))
    }

    @Test
    fun markLinkReachedWrapsOnlyAFailureThatHappenedAfterTheLinkExisted() {
        // The keep-2M switch is an Experimental DEBUG arm; a release build never marks a failure (the test above).
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        DebugTunables.relayEstablishKeep2M = true
        val before = runCatching { markLinkReached({ false }) { throw IOException("connect failed") } }.exceptionOrNull()
        assertFalse(before is LinkReachedSetupFailure)
        val after = runCatching { markLinkReached({ true }) { throw IOException("connect failed") } }.exceptionOrNull()
        assertTrue(after is LinkReachedSetupFailure)
        assertEquals("ok", markLinkReached({ true }) { "ok" })
    }
}
