package com.example.bluetoothtest

import com.example.bluetoothtest.CarrierCutover.Action
import com.example.bluetoothtest.CarrierCutover.Rec
import com.example.bluetoothtest.CarrierCutover.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Directed scenarios for the pure cutover machine; the interleavings are in CarrierCutoverModelTest. */
class CarrierCutoverTest {
    private val cfg = CarrierCutover.Config()
    private val n = 1

    private fun rec(seq: Long) = Rec(seq, 100, 0)

    private fun upMachine(config: CarrierCutover.Config = cfg): CarrierCutover {
        val m = CarrierCutover(config)
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        m.onCarrierUp(n, 0)
        return m
    }

    private fun List<Action>.writes() = filterIsInstance<Action.Write>()
    private fun List<Action>.delivered() = filterIsInstance<Action.Deliver>().map { it.rec.seq }

    @Test
    fun senderSwitchesOnlyAfterTheCallWasActiveForThreeSeconds() {
        val m = upMachine()
        assertTrue(m.onTick(2_999).none { it is Action.Write })
        val out = m.onTick(3_000)
        val sw = out.writes().single()
        assertEquals(CarrierCutover.OLD, sw.carrier)
        assertEquals(1L, (sw.wire as Wire.Switch).gen)
        assertTrue(out.contains(Action.ReassertState))
        assertEquals(n, m.sendOn)
        // the marker is in order on the old link, later records go to the new one
        assertEquals(n, m.onSealed(rec(0), 3_000).writes().single().carrier)
    }

    @Test
    fun earlyNewCarrierRecordsAreHeldUntilTheSwitchMarkerArrivesInOrderOnTheOldLink() {
        val m = upMachine()
        assertTrue(m.onArrive(n, Wire.Content(rec(5)), 10).delivered().isEmpty())
        assertEquals(listOf(5L), m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, n), 20).delivered())
        assertEquals(n, m.accept)
        assertEquals(listOf(6L), m.onArrive(n, Wire.Content(rec(6)), 30).delivered())
    }

    @Test
    fun oldTailBelowTheBoundaryIsOpenedAndRecordsAtOrAboveItAreFenced() {
        val m = upMachine()
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, n), 0)
        val late = m.onArrive(CarrierCutover.OLD, Wire.Content(rec(7)), 0)
        assertTrue(late.filterIsInstance<Action.Drop>().single().reason == CarrierCutover.DropReason.FENCED)
        assertEquals(listOf(3L), m.onArrive(CarrierCutover.OLD, Wire.Content(rec(3)), 0).delivered())
    }

    @Test
    fun aStaleOrRepeatedRecordIsDroppedNeverDelivered() {
        val m = upMachine()
        assertEquals(listOf(4L), m.onArrive(CarrierCutover.OLD, Wire.Content(rec(4)), 0).delivered())
        assertTrue(m.onArrive(CarrierCutover.OLD, Wire.Content(rec(4)), 0).delivered().isEmpty())
        assertTrue(m.onArrive(CarrierCutover.OLD, Wire.Content(rec(2)), 0).delivered().isEmpty())
    }

    @Test
    fun aStuckSwitchPromotesTheNewCarrierAfterTheHoldAndClosesNothing() {
        val m = upMachine()
        m.onArrive(n, Wire.Content(rec(5)), 100)
        m.onArrive(n, Wire.Content(rec(6)), 200)
        val out = m.onTick(1_100)
        assertEquals(listOf(5L, 6L), out.delivered())
        assertFalse(out.any { it is Action.CloseCarrier })
        assertEquals(n, m.accept)
        // the late marker changes nothing
        assertTrue(m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, n), 2_000).delivered().isEmpty())
    }

    @Test
    fun theHoldThatFollowsIsMeasuredWhetherItEndsByExpiryOrByTheSwitch() {
        val expired = upMachine()
        val stats = ArrayList<String>()
        expired.onStat = { stats += it }
        assertEquals(-1L, expired.lastHoldMs)
        expired.onArrive(n, Wire.Content(rec(5)), 100)
        expired.onTick(1_100)
        assertEquals(1_000L, expired.lastHoldMs)
        assertEquals("promoted_on_expiry", expired.lastHoldEnd)
        assertTrue(stats.contains("hold-ended"))

        val switched = upMachine()
        switched.onArrive(n, Wire.Content(rec(5)), 100)
        switched.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, n), 400)
        assertEquals(300L, switched.lastHoldMs)
        assertEquals("released_by_switch", switched.lastHoldEnd)
    }

    @Test
    fun theFirstDraftClosedTheNewCarrierOnHoldExpiryEvenWhenOldWasSilentlyDead() {
        val m = upMachine(cfg.asFirstDraft())
        m.onArrive(n, Wire.Content(rec(5)), 100)
        val out = m.onTick(1_100)
        assertTrue(out.any { it is Action.CloseCarrier && it.reason == CarrierCutover.CloseReason.HOLD_EXPIRED })
        assertTrue(out.delivered().isEmpty())
    }

    @Test
    fun overflowOfTheHoldPromotesLikeExpiry() {
        val m = upMachine(CarrierCutover.Config(holdBytes = 250))
        assertTrue(m.onArrive(n, Wire.Content(rec(1)), 0).delivered().isEmpty())
        assertTrue(m.onArrive(n, Wire.Content(rec(2)), 0).delivered().isEmpty())
        assertEquals(listOf(1L, 2L, 3L), m.onArrive(n, Wire.Content(rec(3)), 0).delivered())
    }

    @Test
    fun lossOfTheOldLinkMovesBothDirectionsToTheNewOneAtOnce() {
        val m = upMachine()
        val out = m.onCarrierDied(CarrierCutover.OLD, 500)
        assertEquals(n, m.sendOn)
        assertEquals(n, m.accept)
        val sw = out.writes().single()
        assertEquals(n, sw.carrier) // the marker goes on the new carrier, never to the dead one
        assertTrue(out.contains(Action.ReassertState))
        assertEquals(listOf(9L), m.onArrive(n, Wire.Content(rec(9)), 600).delivered())
        // losing the new carrier as well ends the session
        assertTrue(m.onCarrierDied(n, 700).any { it is Action.EndSession })
    }

    @Test
    fun aRepeatedSwitchOnTheNewCarrierAfterAnOldLinkLossIsIdempotent() {
        val m = upMachine()
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, n), 0)
        m.onArrive(n, Wire.Content(rec(5)), 0)
        assertTrue(m.onArrive(n, Wire.Switch(1, 5, n), 0).delivered().isEmpty())
        assertEquals(listOf(6L), m.onArrive(n, Wire.Content(rec(6)), 0).delivered())
    }

    @Test
    fun newCarrierFailureFallsBackWithResumeBeforeAnyContentAndReassertsState() {
        val m = upMachine()
        m.onTick(3_000)
        assertEquals(n, m.sendOn)
        m.onSealed(rec(0), 3_001)
        val out = m.onCarrierDied(n, 3_500)
        assertEquals(CarrierCutover.OLD, m.sendOn)
        val resume = out.writes().first()
        assertEquals(CarrierCutover.OLD, resume.carrier)
        assertTrue(resume.wire is Wire.Resume)
        assertEquals(1L, (resume.wire as Wire.Resume).next)
        assertTrue(out.contains(Action.ReassertState) && out.contains(Action.ForceLocalKeyframe) && out.contains(Action.ResetSendFragments))
        // 30 s cooldown: no new switch even with a new carrier ready
        m.onCarrierPending(2, 4_000)
        assertTrue(m.onCarrierUp(2, 4_100).none { it is Action.Write })
        assertTrue(m.onTick(33_000).none { it is Action.Write })
        assertTrue(m.onTick(33_600).writes().any { it.wire is Wire.Switch })
    }

    @Test
    fun resumeWhileAcceptingTheNewCarrierReturnsToOldAndClosesIt() {
        val m = upMachine()
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 0, n), 0)
        val out = m.onArrive(CarrierCutover.OLD, Wire.Resume(2, 3, n), 10)
        assertEquals(CarrierCutover.OLD, m.accept)
        assertTrue(out.any { it is Action.CloseCarrier })
        // a late record of the closed carrier never delivers
        assertTrue(m.onArrive(n, Wire.Content(rec(1)), 20).delivered().isEmpty())
    }

    @Test
    fun resumeNamingTheNewCarrierClosesItEvenWhileStillAcceptingOld() {
        // the peer's new carrier died before this phone noticed or switched: the notice tells it
        val m = upMachine()
        val out = m.onArrive(CarrierCutover.OLD, Wire.Resume(1, 3, n), 0)
        assertTrue(out.any { it is Action.CloseCarrier && it.carrier == n })
        assertEquals(CarrierCutover.OLD, m.accept)
        assertTrue(m.onArrive(n, Wire.Content(rec(9)), 5).delivered().isEmpty())
    }

    @Test
    fun theFirstDraftIgnoredResumeWhileAcceptingOld() {
        val m = upMachine(cfg.copy(resumeAlwaysWritten = false))
        assertTrue(m.onArrive(CarrierCutover.OLD, Wire.Resume(1, 3, n), 0).none { it is Action.CloseCarrier })
    }

    @Test
    fun resumeNamingAnotherCarrierIsIgnored() {
        val m = upMachine()
        assertTrue(m.onArrive(CarrierCutover.OLD, Wire.Resume(1, 3, 7), 0).none { it is Action.CloseCarrier })
    }

    @Test
    fun aNewCarrierDyingWhileOldLivesAlwaysTellsThePeerEvenBeforeAnySwitch() {
        val m = upMachine()
        val out = m.onCarrierDied(n, 100)
        val resume = out.writes().single()
        assertEquals(CarrierCutover.OLD, resume.carrier)
        assertEquals(n, (resume.wire as Wire.Resume).carrier)
        assertEquals(CarrierCutover.OLD, m.sendOn)
        assertTrue(out.none { it == Action.ReassertState })
        // the first draft told the peer only after a switch
        val draft = upMachine(cfg.copy(resumeAlwaysWritten = false))
        assertTrue(draft.onCarrierDied(n, 100).writes().isEmpty())
    }

    @Test
    fun resumeNextBecomesTheFloorOfWhatTheOldLinkMayDeliverAfterwards() {
        val m = upMachine()
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 0, n), 0)
        m.onArrive(CarrierCutover.OLD, Wire.Resume(2, 7, n), 10)
        assertEquals(CarrierCutover.OLD, m.accept)
        val low = m.onArrive(CarrierCutover.OLD, Wire.Content(rec(5)), 20)
        assertTrue(low.delivered().isEmpty())
        assertEquals(listOf(7L), m.onArrive(CarrierCutover.OLD, Wire.Content(rec(7)), 30).delivered())
        // the first draft zeroed it
        val draft = upMachine(cfg.copy(resumeSetsOldFloor = false))
        draft.onArrive(CarrierCutover.OLD, Wire.Switch(1, 0, n), 0)
        draft.onArrive(CarrierCutover.OLD, Wire.Resume(2, 7, n), 10)
        assertEquals(listOf(5L), draft.onArrive(CarrierCutover.OLD, Wire.Content(rec(5)), 20).delivered())
    }

    @Test
    fun aMarkerThatNamesNoCarrierIsBurnedWhenMarkersMustNameOne() {
        val m = upMachine()
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, CarrierCutover.ANY), 0)
        assertEquals(CarrierCutover.OLD, m.accept)
        val out = m.onArrive(CarrierCutover.OLD, Wire.Resume(2, 7, CarrierCutover.ANY), 10)
        assertTrue(out.none { it is Action.CloseCarrier })
        assertEquals(listOf(1L), m.onArrive(CarrierCutover.OLD, Wire.Content(rec(1)), 20).delivered())
    }

    @Test
    fun aSwitchOnTheNewCarrierIsCountedAndNeverClosesTheOldLink() {
        val m = upMachine()
        val stats = ArrayList<String>()
        m.onStat = { stats += it }
        val out = m.onArrive(n, Wire.Switch(1, 4, n), 50)
        // review F-6: the marker names no instance of the old link, so it never closes it (a delayed one could close the
        // standby an in-call renewal had just installed); only this phone's own detection closes the old link
        assertTrue(out.none { it is Action.CloseCarrier })
        assertTrue("old-dead-hint" in stats)
        assertTrue(m.oldIsAlive)
        assertEquals(n, m.accept)
        // this phone's own direction stays on the old link until its own detection or its own switch
        assertEquals(CarrierCutover.OLD, m.sendOn)
        assertTrue(out.writes().none { it.wire is Wire.Switch })
        // a repeat of the same SWITCH changes nothing further
        assertTrue(m.onArrive(n, Wire.Switch(1, 4, n), 60).none { it is Action.CloseCarrier })
        // the first draft does not even count it
        val off = upMachine(cfg.copy(oldDeadHint = false))
        val offStats = ArrayList<String>()
        off.onStat = { offStats += it }
        assertTrue(off.onArrive(n, Wire.Switch(1, 4, n), 50).none { it is Action.CloseCarrier })
        assertFalse("old-dead-hint" in offStats)
    }

    @Test
    fun switchWaitsBehindAnOldLinkBacklogAndForAFrameBoundary() {
        val m = upMachine()
        // 500 ms is half the hold: too old a queue means the receiver's hold could run out first
        assertTrue(m.onTick(3_000, oldQueueAgeMs = 500).none { it is Action.Write })
        assertTrue(m.onTick(3_100, oldQueueAgeMs = 0, atFrameBoundary = false).none { it is Action.Write })
        val sw = m.onTick(3_200, oldQueueAgeMs = 499, atFrameBoundary = true).writes().single()
        assertEquals(CarrierCutover.OLD, sw.carrier)
        // SWITCH waits in the ordinary media FIFO, behind the backlog
        assertFalse(sw.priority)
        // the first draft let it overtake the backlog
        val draft = upMachine(cfg.copy(switchThroughMediaFifo = false, gateSwitchOnOldQueue = false))
        val dsw = draft.onTick(3_000, oldQueueAgeMs = 5_000).writes().single()
        assertTrue(dsw.priority)
    }

    @Test
    fun receiverAsksThePeerForKeyframesAndTheSenderForcesItsOwn() {
        val m = upMachine()
        m.onTick(3_000)
        val fallback = m.onCarrierDied(n, 3_100)
        assertTrue(fallback.contains(Action.ForceLocalKeyframe))
        assertTrue(fallback.contains(Action.ResetSendFragments))
        assertFalse(fallback.contains(Action.RequestPeerKeyframe))
        val gap = upMachine().onCarrierDied(CarrierCutover.OLD, 100)
        assertTrue(gap.contains(Action.RequestPeerKeyframe))
        assertTrue(gap.contains(Action.ResetSendFragments))
        assertFalse(gap.contains(Action.ForceLocalKeyframe))
    }

    @Test
    fun losingTheOldLinkWhileTheNewOneIsStillBeingSetUpWaitsForIt() {
        val m = CarrierCutover(cfg)
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        val out = m.onCarrierDied(CarrierCutover.OLD, 100)
        assertFalse(out.any { it is Action.EndSession })
        val up = m.onCarrierUp(n, 200)
        assertEquals(n, m.sendOn)
        assertTrue(up.writes().any { it.wire is Wire.Switch && it.carrier == n })
        // the first draft ended the session here although the new carrier lived
        val draft = CarrierCutover(cfg.asFirstDraft())
        draft.onCarrierPending(n, 0)
        assertTrue(draft.onCarrierDied(CarrierCutover.OLD, 100).any { it is Action.EndSession })
    }

    @Test
    fun aSwitchMarkerThatArrivesBeforeThisSidesReadyIsKept() {
        val m = CarrierCutover(cfg)
        m.onCallActive(0)
        m.onCarrierPending(n, 0)
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 4, n), 10)
        m.onCarrierUp(n, 20)
        assertEquals(n, m.accept)
        assertEquals(listOf(4L), m.onArrive(n, Wire.Content(rec(4)), 30).delivered())
    }

    /** The peer's first attempt (carrier 1) died; its SWITCH and RESUME were still on the old link. */
    private fun staleMarkersMeetASecondAttempt(config: CarrierCutover.Config): Pair<CarrierCutover, List<Action>> {
        val m = CarrierCutover(config)
        m.onCallActive(0)
        m.onCarrierPending(1, 0)
        m.onCarrierUp(1, 0)
        m.onCarrierDied(1, 100) // this phone saw the first new carrier die before the markers came
        m.onCarrierPending(2, 200)
        m.onCarrierUp(2, 300)
        val sw = config.markersNameCarrier.let { named -> if (named) 1 else CarrierCutover.ANY }
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, sw), 400)
        val out = m.onArrive(CarrierCutover.OLD, Wire.Resume(2, 7, sw), 410)
        return m to out
    }

    @Test
    fun markersOfADeadAttemptDoNotMoveTheReceiverOntoALaterAttempt() {
        val (m, out) = staleMarkersMeetASecondAttempt(cfg)
        assertEquals(CarrierCutover.OLD, m.accept)
        assertTrue(out.none { it is Action.CloseCarrier })
    }

    @Test
    fun theFirstDraftAppliedAStaleSwitchToTheLaterAttemptAndThenClosedItOnTheStaleResume() {
        val draft = CarrierCutover.Config(markersNameCarrier = false)
        val m = CarrierCutover(draft)
        m.onCallActive(0)
        m.onCarrierPending(1, 0); m.onCarrierUp(1, 0)
        m.onCarrierDied(1, 100)
        m.onCarrierPending(2, 200); m.onCarrierUp(2, 300)
        m.onArrive(CarrierCutover.OLD, Wire.Switch(1, 5, CarrierCutover.ANY), 400)
        assertEquals(2, m.accept) // the sender is still on the old link: its records are now fenced
        val fenced = m.onArrive(CarrierCutover.OLD, Wire.Content(rec(6)), 405)
        assertTrue(fenced.filterIsInstance<Action.Drop>().single().reason == CarrierCutover.DropReason.FENCED)
        assertTrue(m.onArrive(CarrierCutover.OLD, Wire.Resume(2, 7, CarrierCutover.ANY), 410).any { it is Action.CloseCarrier })
    }

    @Test
    fun upgradeAttemptThatNeverBecomesReadyIsClosedAtItsCeiling() {
        val m = CarrierCutover(cfg)
        m.onCarrierPending(n, 0)
        assertTrue(m.onTick(4_999).isEmpty())
        assertTrue(m.onTick(5_000).any { it is Action.CloseCarrier })
    }
}
