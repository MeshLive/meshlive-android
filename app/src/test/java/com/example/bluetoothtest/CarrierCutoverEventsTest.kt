package com.example.bluetoothtest

import com.example.bluetoothtest.CarrierCutover.Action
import com.example.bluetoothtest.CarrierCutover.CloseReason
import com.example.bluetoothtest.CarrierCutover.Rec
import com.example.bluetoothtest.CarrierCutover.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directed scenarios for the Design WF events of the cutover machine; the interleavings are in CarrierCutoverModelTest. */
class CarrierCutoverEventsTest {
    private val cfg = CarrierCutover.Config()
    private val n = 1
    private val old = CarrierCutover.OLD
    private val none = CarrierCutover.NONE

    private fun rec(seq: Long) = Rec(seq, 100, 0)

    /** Old link plus a new carrier that finished BIND and READY on this side; the call became active at 0. */
    private fun upMachine(config: CarrierCutover.Config = cfg): CarrierCutover {
        val m = CarrierCutover(config)
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        m.onCarrierUp(n, 0)
        return m
    }

    /** As [upMachine], and this direction has moved to the new carrier (generation 1, boundary 0). */
    private fun switched(config: CarrierCutover.Config = cfg): CarrierCutover = upMachine(config).also { it.onTick(3_000) }

    private fun List<Action>.writes() = filterIsInstance<Action.Write>()
    private fun List<Action>.closes() = filterIsInstance<Action.CloseCarrier>()
    private fun List<Action>.ended() = any { it is Action.EndSession }

    // ---------------------------------------------------------------- standby replaced

    @Test
    fun aRestoredStandbyKeepsTheCallOnWifiRewritesTheSwitchAndBecomesTheFallback() {
        val m = switched()
        m.onCarrierDied(old, 3_100) // the old link is lost, Wi-Fi carries the call
        assertFalse(m.oldIsAlive)
        assertEquals(n, m.sendOn)
        assertEquals(n, m.accept)
        val out = m.onStandbyReplaced(4_000)
        assertTrue(m.oldIsAlive)
        assertEquals(n, m.sendOn)
        assertEquals(n, m.accept)
        assertFalse(out.ended())
        val write = out.writes().single()
        assertEquals(old, write.carrier)
        val marker = write.wire as Wire.Switch
        assertEquals(1L, marker.gen)
        assertEquals(0L, marker.boundary)
        assertEquals(n, marker.carrier)
        // Wi-Fi dies later: the call falls back to the restored standby and lives
        val after = m.onCarrierDied(n, 5_000)
        assertFalse(after.ended())
        assertFalse(m.ended)
        assertEquals(old, m.sendOn)
        assertEquals(old, m.accept)
        assertEquals(1, m.fallbackCount)
    }

    @Test
    fun theFirstDraftNeverHearsOfTheRenewalAndEndsTheCallWhenWifiDies() {
        val m = switched(cfg.copy(standbyReplacedEvent = false))
        m.onCarrierDied(old, 3_100)
        assertTrue(m.onStandbyReplaced(4_000).isEmpty())
        assertFalse(m.oldIsAlive)
        val after = m.onCarrierDied(n, 5_000)
        assertTrue(after.ended())
        assertTrue(m.ended)
    }

    @Test
    fun aStandbyReplacementAlwaysReassertsState() {
        // A1 report 5.2: a state record sealed on the old instance is lost with it, so a replacement re-seals state even
        // when this direction already lives on Wi-Fi
        val moving = switched()
        val movedOut = moving.onStandbyReplaced(4_000)
        assertEquals(1, movedOut.count { it === Action.ReassertState })
        val marker = movedOut.writes().single()
        assertTrue(
            "the re-sealed state follows the re-asserted marker",
            movedOut.indexOf(Action.ReassertState) > movedOut.indexOf(marker),
        )
        val idle = upMachine() // the new carrier is up, this direction never moved
        val idleOut = idle.onStandbyReplaced(4_000)
        assertEquals(1, idleOut.count { it === Action.ReassertState })
    }

    @Test
    fun aFallbackThatHappenedIsAnnouncedAgainOnTheNewStandby() {
        val m = switched()
        m.onCarrierDied(n, 4_000) // fall back: RESUME(2, 0, n) on the old link
        assertEquals(old, m.sendOn)
        val out = m.onStandbyReplaced(5_000)
        val write = out.writes().single()
        assertEquals(old, write.carrier)
        val resume = write.wire as Wire.Resume
        assertEquals(2L, resume.gen)
        assertEquals(0L, resume.next)
        assertEquals(n, resume.carrier)
        assertEquals(old, m.sendOn)
    }

    @Test
    fun aRenewalWithNothingToAnnounceWritesNothing() {
        val m = CarrierCutover()
        m.onCallActive(0)
        val out = m.onStandbyReplaced(10)
        // A1 report 5.2: the replacement still re-seals indispensable state; what it does not do is write a marker
        assertTrue(out.writes().isEmpty())
        assertEquals(1, out.count { it === Action.ReassertState })
        assertEquals(old, m.sendOn)
        assertEquals(old, m.accept)
        // an unmoved direction with a new carrier up has no marker to repeat either
        assertTrue(upMachine().onStandbyReplaced(10).writes().isEmpty())
    }

    @Test
    fun theFirstDraftDoesNotRepeatTheMarker() {
        val m = switched(cfg.copy(reassertMarkerAfterReplace = false))
        m.onCarrierDied(old, 3_100)
        val out = m.onStandbyReplaced(4_000)
        assertTrue(m.oldIsAlive)
        assertTrue(out.writes().isEmpty())
    }

    @Test
    fun aRepeatedOrStaleMarkerOnTheStandbyNeverChangesWhatTheReceiverAccepts() {
        val rx = upMachine()
        rx.onArrive(old, Wire.Switch(1, 5, n), 10)
        assertEquals(n, rx.accept)
        val again = rx.onArrive(old, Wire.Switch(1, 5, n), 20) // the re-assertion after the sender's renewal
        assertTrue(again.isEmpty())
        assertEquals(n, rx.accept)
        rx.onArrive(old, Wire.Resume(2, 3, n), 30) // the sender fell back: this side takes the old link again
        assertEquals(old, rx.accept)
        val staleSwitch = rx.onArrive(old, Wire.Switch(1, 5, n), 40)
        assertTrue(staleSwitch.all { it is Action.Drop })
        assertEquals(old, rx.accept)
        val staleResume = rx.onArrive(old, Wire.Resume(2, 3, n), 50)
        assertTrue(staleResume.all { it is Action.Drop })
        assertEquals(old, rx.accept)
        assertEquals(2L, rx.generationSeen)
    }

    @Test
    fun aRenewalAfterTheOldLinkWasLostWhileAnAttemptWasPendingReturnsSendingToTheStandby() {
        val m = CarrierCutover()
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        m.onCarrierDied(old, 100)
        assertEquals(none, m.sendOn)
        assertEquals(none, m.accept)
        val out = m.onStandbyReplaced(200)
        assertEquals(old, m.sendOn)
        assertEquals(old, m.accept)
        for (a in listOf(Action.ResetSendFragments, Action.ForceLocalKeyframe, Action.ReassertState, Action.SuppressFragments, Action.RequestPeerKeyframe)) {
            assertTrue("$a", out.contains(a))
        }
        assertEquals(old, m.onSealed(rec(0), 300).writes().single().carrier)
        // the attempt that was pending times out: the call lives on the standby
        m.onTick(5_000)
        assertFalse(m.ended)
        assertEquals(old, m.sendOn)
    }

    @Test
    fun anEndedMachineIgnoresEveryNewEvent() {
        val m = CarrierCutover()
        m.onCallActive(0)
        assertTrue(m.onCarrierDied(old, 10).ended())
        assertTrue(m.onStandbyReplaced(20).isEmpty())
        assertTrue(m.onSwitchOff(30).isEmpty())
        assertTrue(m.onQualityFallback(40).isEmpty())
        assertTrue(m.onStandbyFreshness(false, 50).isEmpty())
        assertTrue(m.ended)
    }

    // ---------------------------------------------------------------- A1F2: a RESUME naming the carrier this side reads from

    @Test
    fun aResumeNamingTheCarrierStillBeingReadIsHonoured() {
        val m = CarrierCutover()
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        m.onCarrierUp(n, 0)
        m.onArrive(old, Wire.Switch(1, 5, n), 10) // the peer's SWITCH: this side reads N from boundary 5 on
        assertEquals(n, m.accept)
        // a later attempt took this side's newId slot and then died, leaving this side still reading from N
        m.onCarrierPending(2, 20)
        m.onCarrierUp(2, 30)
        m.onCarrierDied(2, 40)
        assertEquals(none, m.newCarrier)
        assertEquals(n, m.accept)
        val out = m.onArrive(old, Wire.Resume(2, 7, n), 50)
        assertEquals(old, m.accept)
        val close = out.closes().single()
        assertEquals(n, close.carrier)
        assertEquals(CloseReason.PEER_RESUMED, close.reason)
        // it accepts from the old carrier from next (7) on
        assertTrue(m.onArrive(old, Wire.Content(rec(6)), 60).all { it is Action.Drop })
        assertEquals(
            listOf(7L),
            m.onArrive(old, Wire.Content(rec(7)), 70).filterIsInstance<Action.Deliver>().map { it.rec.seq },
        )
    }

    // ---------------------------------------------------------------- review F-6: a delayed SWITCH never closes a renewed standby

    @Test
    fun aDelayedSwitchArrivingAfterARenewalNeverClosesTheNewStandby() {
        val m = upMachine() // this phone has not moved; the peer is about to
        m.onCarrierDied(old, 100) // the old link is lost here too: Wi-Fi carries both directions
        m.onStandbyReplaced(200) // the in-call renewal installs a new standby
        assertTrue(m.oldIsAlive)
        // the peer's SWITCH, written on Wi-Fi when it lost the old link before the renewal, arrives late
        val late = m.onArrive(n, Wire.Switch(1, 0, n), 300)
        assertTrue(late.none { it is Action.CloseCarrier })
        assertTrue(m.oldIsAlive)
        // Wi-Fi then dies: the session continues on the new standby
        val after = m.onCarrierDied(n, 400)
        assertFalse(after.ended())
        assertFalse(m.ended)
        assertEquals(old, m.sendOn)
        assertEquals(old, m.accept)
    }

    // ---------------------------------------------------------------- Opus A1 2: standby freshness

    @Test
    fun switchingWifiOffWhileTheStandbyIsStaleWaitsAndCompletesWhenItIsHeardAgain() {
        val m = switched()
        assertTrue(m.onStandbyFreshness(false, 3_100).isEmpty())
        assertTrue(m.onSwitchOff(3_200).isEmpty())
        assertTrue(m.switchOffPendingNow)
        assertEquals(n, m.sendOn)
        assertEquals(n, m.newCarrier)
        val back = m.onStandbyFreshness(true, 4_000)
        val resume = back.writes().single().wire as Wire.Resume
        assertEquals(2L, resume.gen)
        val close = back.closes().single()
        assertEquals(n, close.carrier)
        assertEquals(CloseReason.SWITCH_OFF, close.reason)
        assertEquals(old, m.sendOn)
        assertFalse(m.switchOffPendingNow)
        assertEquals(0, m.fallbackCount)
    }

    @Test
    fun aWifiDeathWhileASwitchOffWaitsForAStaleStandbyFallsBackAndClearsTheWait() {
        val m = switched()
        m.onStandbyFreshness(false, 3_100)
        m.onSwitchOff(3_200)
        val after = m.onCarrierDied(n, 3_300)
        assertFalse(after.ended())
        assertEquals(old, m.sendOn)
        assertFalse(m.switchOffPendingNow)
        assertTrue(m.onStandbyFreshness(true, 4_000).closes().isEmpty())
    }

    @Test
    fun aQualityFallbackWithAStaleStandbyDoesNothing() {
        val m = switched()
        m.onStandbyFreshness(false, 3_100)
        assertTrue(m.onQualityFallback(3_200).isEmpty())
        assertEquals(n, m.newCarrier)
        assertEquals(n, m.sendOn)
        assertEquals(0, m.fallbackCount)
    }

    @Test
    fun theFirstDraftIgnoresStandbyFreshness() {
        val m = switched(cfg.copy(standbyFreshnessGates = false))
        m.onStandbyFreshness(false, 3_100)
        val out = m.onSwitchOff(3_200)
        assertEquals(n, out.closes().single().carrier)
        assertFalse(m.switchOffPendingNow)
    }

    @Test
    fun aRenewedStandbyIsFresh() {
        val m = switched()
        m.onCarrierDied(old, 3_100)
        m.onStandbyFreshness(false, 3_150)
        m.onStandbyReplaced(4_000)
        assertTrue(m.standbyFreshNow)
    }

    // ---------------------------------------------------------------- switch off

    @Test
    fun switchingWifiOffWithAStandbyFallsBackWithoutCountingOrCoolingDownThenClosesTheCarrier() {
        val m = switched()
        val out = m.onSwitchOff(3_500)
        val write = out.writes().single()
        assertEquals(old, write.carrier)
        val resume = write.wire as Wire.Resume
        assertEquals(2L, resume.gen)
        assertEquals(0L, resume.next)
        assertEquals(n, resume.carrier)
        for (a in listOf(Action.ResetSendFragments, Action.ForceLocalKeyframe, Action.ReassertState)) assertTrue("$a", out.contains(a))
        val close = out.closes().single()
        assertEquals(n, close.carrier)
        assertEquals(CloseReason.SWITCH_OFF, close.reason)
        assertTrue("the carrier is closed after the RESUME is written", out.indexOf(close) > out.indexOf(write))
        assertEquals(close, out.last())
        assertEquals(old, m.sendOn)
        assertEquals(old, m.accept)
        assertEquals(none, m.newCarrier)
        assertEquals(0, m.fallbackCount)
        assertTrue(m.switchedOffNow)
        assertFalse(m.switchOffPendingNow)
    }

    @Test
    fun afterTheSwitchIsOffNoAttemptIsAcceptedAndNothingSwitchesAgain() {
        val m = switched()
        m.onSwitchOff(3_500)
        assertTrue(m.onCarrierPending(2, 3_600).isEmpty())
        assertEquals(none, m.pendingCarrier)
        assertTrue(m.onCarrierUp(2, 3_700).isEmpty())
        assertEquals(none, m.newCarrier)
        assertTrue(m.onTick(60_000).writes().isEmpty())
        assertTrue(m.onSwitchOff(70_000).isEmpty()) // idempotent
    }

    @Test
    fun theFirstDraftKeepsAcceptingAttemptsAfterTheSwitchIsOff() {
        val m = CarrierCutover(cfg.copy(switchOffBlocksAttempts = false))
        m.onCallActive(0)
        m.onSwitchOff(1)
        m.onCarrierPending(n, 2)
        assertEquals(n, m.pendingCarrier)
    }

    @Test
    fun switchingWifiOffWhileWifiIsTheOnlyCarrierKeepsTheCallAndCompletesWhenAStandbyIsBack() {
        val m = switched()
        m.onCarrierDied(old, 3_100)
        val out = m.onSwitchOff(3_200)
        assertTrue(out.isEmpty())
        assertEquals(n, m.sendOn)
        assertTrue(m.switchedOffNow)
        assertTrue(m.switchOffPendingNow)
        assertFalse(m.ended)
        val back = m.onStandbyReplaced(4_000)
        val resume = back.writes().single().wire as Wire.Resume
        assertEquals(2L, resume.gen)
        val close = back.closes().single()
        assertEquals(n, close.carrier)
        assertEquals(CloseReason.SWITCH_OFF, close.reason)
        assertTrue(back.none { it is Action.Write && it.wire is Wire.Switch })
        assertEquals(old, m.sendOn)
        assertEquals(old, m.accept)
        assertFalse(m.switchOffPendingNow)
        assertEquals(0, m.fallbackCount)
        assertFalse(m.ended)
    }

    @Test
    fun ifWifiDiesBeforeAStandbyIsBackTheSessionEndsOnlyThen() {
        val m = switched()
        m.onCarrierDied(old, 3_100)
        m.onSwitchOff(3_200)
        assertTrue(m.onCarrierDied(n, 3_300).ended())
    }

    @Test
    fun theFirstDraftClosesTheOnlyLiveCarrierWhenTheSwitchIsTurnedOff() {
        val m = switched(cfg.copy(switchOffKeepsLastCarrier = false))
        m.onCarrierDied(old, 3_100)
        val out = m.onSwitchOff(3_200)
        assertTrue(out.ended())
        assertTrue(m.ended)
    }

    @Test
    fun switchingWifiOffCancelsAnAttemptInProgressWhenAStandbyIsAlive() {
        val m = CarrierCutover()
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        val out = m.onSwitchOff(100)
        val close = out.closes().single()
        assertEquals(n, close.carrier)
        assertEquals(CloseReason.SWITCH_OFF, close.reason)
        assertEquals(none, m.pendingCarrier)
        assertFalse(m.ended)
        assertEquals(old, m.sendOn)
    }

    @Test
    fun switchingWifiOffWhileAnAttemptIsTheOnlyHopeKeepsTheAttempt() {
        val m = CarrierCutover()
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        m.onCarrierDied(old, 100) // sendOn and accept wait for the attempt
        val out = m.onSwitchOff(200)
        assertTrue(out.isEmpty())
        assertEquals(n, m.pendingCarrier)
        assertTrue(m.switchOffPendingNow)
        val up = m.onCarrierUp(n, 300) // it completes: Wi-Fi is now the only carrier
        assertFalse(up.ended())
        assertEquals(n, m.sendOn)
        val back = m.onStandbyReplaced(400)
        assertTrue(back.closes().any { it.carrier == n && it.reason == CloseReason.SWITCH_OFF })
        assertEquals(old, m.sendOn)
        assertFalse(m.ended)
    }

    @Test
    fun switchingOffBeforeAnythingHappenedOnlyBlocksFutureAttempts() {
        val m = CarrierCutover()
        m.onCallActive(0)
        assertTrue(m.onSwitchOff(0).isEmpty())
        assertTrue(m.onCarrierPending(n, 1).isEmpty())
        assertEquals(none, m.pendingCarrier)
        assertFalse(m.ended)
    }

    @Test
    fun switchingOffWhileTheNewCarrierIsUpButThisDirectionNeverMovedStillReleasesIt() {
        val m = upMachine() // up, not switched
        val out = m.onSwitchOff(1_000)
        assertEquals(n, out.closes().single().carrier)
        assertEquals(none, m.newCarrier)
        assertEquals(0, m.fallbackCount)
        assertEquals(old, m.sendOn)
        // the peer may be sending on the new carrier: the notice RESUME tells it
        assertTrue(out.writes().single().wire is Wire.Resume)
    }

    // ---------------------------------------------------------------- quality fallback

    @Test
    fun aQualityFallbackIsACountedPlannedFallbackWithCooldownThatEventuallyStopsWifi() {
        val m = switched()
        val out = m.onQualityFallback(4_000)
        val write = out.writes().single()
        val resume = write.wire as Wire.Resume
        assertEquals(2L, resume.gen)
        assertEquals(old, write.carrier)
        val close = out.closes().single()
        assertEquals(CloseReason.QUALITY, close.reason)
        assertTrue(out.indexOf(close) > out.indexOf(write))
        assertEquals(1, m.fallbackCount)
        assertEquals(old, m.sendOn)
        // a second attempt is up, but the 30 s cooldown holds the switch back
        m.onCarrierPending(2, 4_100)
        m.onCarrierUp(2, 4_200)
        assertTrue(m.onTick(33_999).writes().isEmpty())
        val again = m.onTick(34_000).writes().single()
        val marker = again.wire as Wire.Switch
        assertEquals(3L, marker.gen)
        assertEquals(2, marker.carrier)
        // the second quality fallback uses up the allowance: Wi-Fi is off for the rest of the hop
        m.onQualityFallback(40_000)
        assertEquals(2, m.fallbackCount)
        m.onCarrierPending(3, 41_000)
        m.onCarrierUp(3, 41_100)
        assertTrue(m.onTick(200_000).writes().isEmpty())
    }

    @Test
    fun aQualityFallbackCountsEvenWhenThisDirectionNeverMovedButNotInTheFirstDraft() {
        val counted = upMachine()
        val out = counted.onQualityFallback(1_000)
        assertEquals(1, counted.fallbackCount)
        assertEquals(CloseReason.QUALITY, out.closes().single().reason)
        assertTrue(out.writes().single().wire is Wire.Resume) // the notice RESUME of an unmoved direction (resumeAlwaysWritten) [API]
        val draft = upMachine(cfg.copy(qualityFallbackCounts = false))
        draft.onQualityFallback(1_000)
        assertEquals(0, draft.fallbackCount)
    }

    @Test
    fun aQualityFallbackWithNoStandbyDoesNothing() {
        val m = switched()
        m.onCarrierDied(old, 3_100)
        assertTrue(m.onQualityFallback(3_200).isEmpty())
        assertEquals(n, m.newCarrier)
        assertEquals(n, m.sendOn)
        assertFalse(m.ended)
    }
}
