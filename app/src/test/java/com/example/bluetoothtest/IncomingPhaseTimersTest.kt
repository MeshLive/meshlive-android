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

/**
 * Design PP package A: the incoming-only hop and pre-INVITE timers, as an incoming connection experiences them. The
 * simulated connection uses the real milestone/timer objects with a fake monotonic clock; "closed" is what the endpoint's
 * timer thread does when [PhaseTimer.expireIfDue] returns true.
 */
class IncomingPhaseTimersTest {
    private class Connection(
        hopSwitch: Boolean,
        preInviteSwitch: Boolean,
        private val transport: BluetoothTransportOption = BluetoothTransportOption.BLE_GATT,
        private val carrier: CarrierKind = CarrierKind.BLUETOOTH,
        generation: Long = 1L,
    ) {
        var nowNs = 0L
        val phases = IncomingPhaseTimers(generation, hopSwitch, preInviteSwitch) { nowNs }
        var closedBy: String? = null
            private set
        val hopBoundArmed: Long? = phases.accepted(transport, carrier)

        fun advanceMs(ms: Long) {
            nowNs += ms * 1_000_000L
            for (timer in listOf(phases.hop, phases.preInvite)) {
                if (timer.expireIfDue() && closedBy == null) closedBy = timer.name
            }
        }

        fun hopVerified(): Boolean = phases.hopVerified()

        fun handshakesDone(
            session: IncomingSessionKind = IncomingSessionKind.CALL,
            viaRelay: Boolean = false,
        ): Long? {
            phases.endpointAuthenticated()
            return phases.readerStarting(session, transport, carrier, viaRelay)
        }
    }

    // --- hop authentication timer -------------------------------------------------------------------------------

    @Test
    fun aHopThatNeverAuthenticatesIsClosedAtTheShortBoundWithTheSwitchOnAndNeverByThisTimerWithItOff() {
        val on = Connection(hopSwitch = true, preInviteSwitch = false)
        assertEquals(IncomingPhaseBounds.HOP_AUTH_MS, on.hopBoundArmed)
        on.advanceMs(IncomingPhaseBounds.HOP_AUTH_MS - 1)
        assertNull(on.closedBy)
        on.advanceMs(1)
        assertEquals("hop_auth", on.closedBy)
        // The 20 s watchdog that closes the same silent connection with the switch off is unchanged and separate.
        assertEquals(20_000L, DualSetupBudget.INCOMING_HANDSHAKE_MS)
        assertTrue(IncomingPhaseBounds.HOP_AUTH_MS < DualSetupBudget.INCOMING_HANDSHAKE_MS)

        val off = Connection(hopSwitch = false, preInviteSwitch = true)
        assertNull(off.hopBoundArmed)
        off.advanceMs(DualSetupBudget.INCOMING_HANDSHAKE_MS * 3)
        assertNull(off.closedBy)
    }

    @Test
    fun aHopThatAuthenticatesInTimeIsNeverClosedByTheHopTimerWhateverFollows() {
        val connection = Connection(hopSwitch = true, preInviteSwitch = false)
        connection.advanceMs(250)
        assertTrue(connection.hopVerified())
        connection.advanceMs(10 * 60 * 1_000L)
        assertNull(connection.closedBy)
        assertFalse(connection.phases.hop.armed)
    }

    @Test
    fun theHopTimerEndsAtTheHopMilestoneNotAtTheEndToEndSessionSoADownstreamRelayWaitingForItsCallerKeepsItsBudget() {
        // A helper's or a target's hop is proven long before the caller's end-to-end handshake comes back through the chain
        // (READY travels upstream first); `contentSession` would still be null here, and must not be the hop timer's predicate.
        val connection = Connection(hopSwitch = true, preInviteSwitch = true)
        connection.advanceMs(120)
        assertTrue(connection.hopVerified())
        connection.advanceMs(15_000) // waiting for upstream READY and the caller's handshake: no new timer owns this wait
        assertNull(connection.closedBy)
        assertFalse(connection.phases.preInvite.armed)
        // The existing relay budgets are untouched constants of their own owners.
        assertEquals(20_000L, RelayedCalls.PRE_SETUP_WAIT_MS)
        assertEquals(20_000, RelayedCalls.SETUP_DEADLINE_MS)
        // Once that handshake completes at a relayed endpoint, the pre-INVITE wait is the conservative one, not the direct bound.
        val armed = connection.handshakesDone(viaRelay = true)
        assertEquals(IncomingPhaseBounds.CONSERVATIVE_MS, armed)
        connection.advanceMs(IncomingPhaseBounds.PRE_INVITE_MS + 1_000)
        assertNull(connection.closedBy)
    }

    @Test
    fun aDepletedSigningBudgetWaitFitsTheHopBoundWithMargin() {
        // The unchanged responder budget makes an untagged stranger wait for a token, at most 4 s (then it closes silently:
        // Bmh3Failure("budget")). Both the wait and the unchanged budget stay inside the hop bound.
        var fakeMs = 0L
        val slept = mutableListOf<Long>()
        val budget = Bmh3SigningBudget(clockMs = { fakeMs }, sleep = { ms -> slept += ms; fakeMs += ms })
        repeat(8) { budget.spend(); fakeMs += 100 } // the burst of eight is free
        assertTrue(slept.isEmpty())
        budget.spend() // the ninth waits for a refill
        assertEquals(1, slept.size)
        val waitMs = slept.single()
        assertTrue("wait $waitMs", waitMs in 1L..4_000L + 1L)

        val connection = Connection(hopSwitch = true, preInviteSwitch = false)
        connection.advanceMs(waitMs + 700) // the token wait, then the handshake and the opening
        assertTrue(connection.hopVerified())
        assertNull(connection.closedBy)
        assertTrue("margin over the longest allowed wait", IncomingPhaseBounds.HOP_AUTH_MS >= 2 * 4_000L)
        // Beyond the 4 s wait the budget refuses outright: that close is the budget's, not this timer's.
        val tight = Bmh3SigningBudget(capacity = 1, clockMs = { fakeMs }, sleep = { ms -> fakeMs += ms })
        tight.spend()
        var refusal: Bmh3Failure? = null
        try {
            tight.spend()
        } catch (error: Bmh3Failure) {
            refusal = error
        }
        assertNotNull(refusal)
    }

    @Test
    fun aSlowLegacyPhoneAtTheChosenBoundIsKeptWhileASilentOneIsNot() {
        // The slowest honest accept-to-hop time on record is 6.2 s (an Android 11 legacy-Qualcomm phone whose GATT stack
        // recovered lost handshake chunks); typical phones need 0.1-0.4 s. That honest link is kept, with the bound as the
        // caller's own: no honest initiator is still waiting when this side closes.
        assertEquals(CallSetupDeadlines.HOP_AUTH_MS, IncomingPhaseBounds.HOP_AUTH_MS)
        val slow = Connection(hopSwitch = true, preInviteSwitch = true)
        slow.advanceMs(6_200)
        assertTrue(slow.hopVerified())
        slow.advanceMs(1)
        assertNull(slow.closedBy)
        // The same slow stack's INVITE after a recovered lost chunk is kept by the pre-INVITE timer too.
        slow.handshakesDone()
        slow.advanceMs(6_000)
        assertTrue(slow.phases.inviteArrived())
        slow.advanceMs(60_000)
        assertNull(slow.closedBy)
        // Exactly at the bound an unauthenticated hop is closed.
        val silent = Connection(hopSwitch = true, preInviteSwitch = true)
        silent.advanceMs(IncomingPhaseBounds.HOP_AUTH_MS)
        assertEquals("hop_auth", silent.closedBy)
        assertFalse("a hop verified after the close must not carry on", silent.hopVerified())
    }

    @Test
    fun aLegacyBmh2HelloWithItsTarpitDelayKeepsOnlyTheConservativeBound() {
        // RV8 delays a BMH2 stranger's hello by up to 30 s: that hop is not measured, so the short timer is withdrawn the
        // moment a BMH2 first frame is seen, and only today's 20 s watchdog (a separate owner) bounds it.
        val connection = Connection(hopSwitch = true, preInviteSwitch = false)
        connection.advanceMs(300)
        assertTrue(connection.phases.legacyFirstFrame())
        assertTrue(connection.phases.legacyHop)
        connection.advanceMs(IncomingPhaseBounds.HOP_AUTH_MS * 2)
        assertNull(connection.closedBy)
        assertFalse(connection.phases.hop.armed)
        assertNotNull(connection.phases.sinceAcceptMs(IncomingMilestone.FIRST_HANDSHAKE_FRAME))
    }

    @Test
    fun unmeasuredTransportsGetNoShortHopTimerAndTheConservativePreInviteBound() {
        for (transport in listOf(BluetoothTransportOption.CLASSIC_RFCOMM)) {
            val classic = Connection(hopSwitch = true, preInviteSwitch = true, transport = transport)
            assertNull(classic.hopBoundArmed)
            assertEquals(IncomingPhaseBounds.CONSERVATIVE_MS, classic.handshakesDone())
        }
        val wifi = Connection(
            hopSwitch = true, preInviteSwitch = true,
            transport = BluetoothTransportOption.BLE_GATT, carrier = CarrierKind.WIFI_AWARE,
        )
        assertNull(wifi.hopBoundArmed)
        assertEquals(IncomingPhaseBounds.CONSERVATIVE_MS, wifi.handshakesDone())
        val coc = Connection(hopSwitch = true, preInviteSwitch = true, transport = BluetoothTransportOption.BLE_L2CAP)
        assertEquals(IncomingPhaseBounds.HOP_AUTH_MS, coc.hopBoundArmed)
        assertEquals(IncomingPhaseBounds.PRE_INVITE_MS, coc.handshakesDone())
    }

    // --- pre-INVITE timer ---------------------------------------------------------------------------------------

    @Test
    fun anAuthenticatedPeerThatOnlySendsHeartbeatsIsClosedAtThePreInviteBoundAndNotBefore() {
        // SEC-06: the heartbeat refreshes the writer's liveness only; it is not an INVITE, and nothing else bounds this phase.
        val connection = Connection(hopSwitch = true, preInviteSwitch = true)
        connection.advanceMs(200)
        assertTrue(connection.hopVerified())
        assertEquals(IncomingPhaseBounds.PRE_INVITE_MS, connection.handshakesDone())
        connection.advanceMs(IncomingPhaseBounds.PRE_INVITE_MS - 1)
        assertNull(connection.closedBy)
        connection.advanceMs(1)
        assertEquals("pre_invite", connection.closedBy)
        assertTrue(IncomingPhaseBounds.PRE_INVITE_MS < DualSetupBudget.INCOMING_HANDSHAKE_MS)
    }

    @Test
    fun withTheSwitchOffTheSameHeartbeatOnlyPeerIsNotBoundedByThisTimer() {
        val connection = Connection(hopSwitch = false, preInviteSwitch = false)
        assertTrue(connection.hopVerified())
        assertNull(connection.handshakesDone())
        connection.advanceMs(60 * 60 * 1_000L)
        assertNull(connection.closedBy)
        assertFalse(connection.phases.preInvite.armed)
    }

    @Test
    fun aPromptInviteIsNeverClosedAndKeepsGoingForever() {
        val connection = Connection(hopSwitch = true, preInviteSwitch = true)
        connection.advanceMs(180)
        assertTrue(connection.hopVerified())
        connection.handshakesDone()
        connection.advanceMs(650) // the caller's own set-up, then its INVITE
        assertTrue(connection.phases.inviteArrived())
        connection.advanceMs(60 * 60 * 1_000L)
        assertNull(connection.closedBy)
        assertEquals(650L, connection.phases.sinceAcceptMs(IncomingMilestone.FIRST_INVITE)!! - 180L)
        // An INVITE that arrives before the reader's arm call (a racing milestone) is never charged a deadline later.
        val early = Connection(hopSwitch = true, preInviteSwitch = true)
        assertTrue(early.hopVerified())
        assertTrue(early.phases.inviteArrived())
        assertNull(early.handshakesDone())
        early.advanceMs(IncomingPhaseBounds.CONSERVATIVE_MS)
        assertNull(early.closedBy)
    }

    @Test
    fun anInviteAtTheDeadlineInstantHasExactlyOneOutcomeInEitherOrder() {
        // Order 1: the INVITE settles the timer first, the expiry finds nothing to do.
        val inviteFirst = Connection(hopSwitch = false, preInviteSwitch = true)
        inviteFirst.handshakesDone()
        inviteFirst.nowNs += IncomingPhaseBounds.PRE_INVITE_MS * 1_000_000L
        assertTrue(inviteFirst.phases.inviteArrived())
        assertFalse(inviteFirst.phases.preInvite.expireIfDue())
        assertFalse(inviteFirst.phases.preInvite.expired)
        // Order 2: the expiry wins, the connection is closed, the late INVITE must not ring.
        val expiryFirst = Connection(hopSwitch = false, preInviteSwitch = true)
        expiryFirst.handshakesDone()
        expiryFirst.nowNs += IncomingPhaseBounds.PRE_INVITE_MS * 1_000_000L
        assertTrue(expiryFirst.phases.preInvite.expireIfDue())
        assertFalse(expiryFirst.phases.inviteArrived())
        assertFalse(expiryFirst.phases.preInvite.expireIfDue()) // once
    }

    @Test
    fun theDeadlineAndTheInviteRaceOnTwoThreadsWithExactlyOneWinnerEveryTime() {
        val outcomes = AtomicInteger()
        repeat(500) {
            val connection = Connection(hopSwitch = false, preInviteSwitch = true)
            connection.handshakesDone()
            connection.nowNs += IncomingPhaseBounds.PRE_INVITE_MS * 1_000_000L
            val start = CountDownLatch(1)
            val inviteWon = AtomicInteger()
            val expiryWon = AtomicInteger()
            val a = thread { start.await(); if (connection.phases.inviteArrived()) inviteWon.incrementAndGet() }
            val b = thread { start.await(); if (connection.phases.preInvite.expireIfDue()) expiryWon.incrementAndGet() }
            start.countDown()
            a.join()
            b.join()
            if (inviteWon.get() + expiryWon.get() == 1) outcomes.incrementAndGet()
            // The loser's view is consistent: an INVITE that lost reports false, so it can never ring a closed link.
            assertEquals(if (expiryWon.get() == 1) 0 else 1, inviteWon.get())
        }
        assertEquals(500, outcomes.get())
    }

    @Test
    fun aVerificationOnlySessionIsNeverClosedByThePreInviteTimer() {
        // A BVMID2 scan arrives through the same listener, completes both handshakes and never sends an INVITE; its
        // confirmation may be read for 20 s under its own 60 s absolute limit. No timer of this design exists for it.
        val connection = Connection(hopSwitch = true, preInviteSwitch = true)
        connection.advanceMs(150)
        assertTrue(connection.hopVerified())
        assertNull(connection.handshakesDone(session = IncomingSessionKind.VERIFICATION_ONLY))
        for (seconds in listOf(6L, 10L, 21L, 61L)) {
            connection.advanceMs(seconds * 1_000L)
            assertNull(connection.closedBy)
        }
        assertFalse(connection.phases.preInvite.armed)
        assertNotNull(connection.phases.sinceAcceptMs(IncomingMilestone.READER_STARTED))
    }

    @Test
    fun withBothSwitchesOffNoTimerIsEverArmedAndEveryMilestoneStillRecords() {
        val connection = Connection(hopSwitch = false, preInviteSwitch = false)
        assertNull(connection.hopBoundArmed)
        connection.advanceMs(500)
        assertTrue(connection.hopVerified())
        assertNull(connection.handshakesDone())
        connection.advanceMs(500)
        assertTrue(connection.phases.inviteArrived())
        connection.advanceMs(24 * 60 * 60 * 1_000L)
        assertNull(connection.closedBy)
        for (milestone in listOf(
            IncomingMilestone.TRANSPORT_ACCEPT, IncomingMilestone.HOP_VERIFIED, IncomingMilestone.ENDPOINT_AUTHENTICATED,
            IncomingMilestone.READER_STARTED, IncomingMilestone.FIRST_INVITE,
        )) {
            assertNotNull(milestone.label, connection.phases.sinceAcceptMs(milestone))
        }
        assertEquals(0L, connection.phases.sinceAcceptMs(IncomingMilestone.TRANSPORT_ACCEPT))
        assertEquals(500L, connection.phases.sinceAcceptMs(IncomingMilestone.HOP_VERIFIED))
    }

    @Test
    fun aStaleConnectionsTimerCannotCloseOrDisarmALaterConnection() {
        val old = Connection(hopSwitch = true, preInviteSwitch = true, generation = 1L)
        val later = Connection(hopSwitch = true, preInviteSwitch = true, generation = 2L)
        old.advanceMs(IncomingPhaseBounds.HOP_AUTH_MS)
        assertEquals("hop_auth", old.closedBy)
        assertTrue(later.phases.hop.armed)
        assertTrue(later.hopVerified())
        old.phases.cancelAll() // the old connection ending changes nothing for the new one
        later.handshakesDone()
        assertTrue(later.phases.preInvite.armed)
        assertTrue(old.phases.deadlineLine(old.phases.hop, BluetoothTransportOption.BLE_GATT, CarrierKind.BLUETOOTH).contains("gen=1"))
    }

    @Test
    fun aConnectionThatEndsCancelsBothTimersSoNeitherClosesAnythingLater() {
        val connection = Connection(hopSwitch = true, preInviteSwitch = true)
        connection.handshakesDone()
        connection.phases.cancelAll()
        connection.advanceMs(IncomingPhaseBounds.CONSERVATIVE_MS * 2)
        assertNull(connection.closedBy)
        assertFalse(connection.phases.hop.armed)
        assertFalse(connection.phases.preInvite.armed)
    }

    @Test
    fun theDeadlineAndMilestoneLinesCarryNoIdentifier() {
        val connection = Connection(hopSwitch = true, preInviteSwitch = true)
        connection.advanceMs(300)
        connection.hopVerified()
        val line = connection.phases.milestoneLine(IncomingMilestone.HOP_VERIFIED)
        assertEquals("incoming_phase: milestone=hop_verified | gen=1 | sinceAcceptMs=300", line)
        val deadline = connection.phases.deadlineLine(connection.phases.preInvite, BluetoothTransportOption.BLE_L2CAP, CarrierKind.BLUETOOTH)
        assertTrue(deadline.startsWith("incoming_phase: event=deadline | timer=pre_invite | gen=1 | closed=true"))
    }
}
