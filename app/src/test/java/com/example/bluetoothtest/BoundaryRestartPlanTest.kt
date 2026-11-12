package com.example.bluetoothtest

import com.example.bluetoothtest.BoundaryRestartPlan.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** R1 C9: the epoch-boundary restart never closes the call server under a central that is still connecting. */
class BoundaryRestartPlanTest {
    /** Steps the rotation thread through a boundary the way holdBoundaryForLinkInFlight does. */
    private fun walk(callStartsAtMs: Long?, linkGoneAtMs: Long?): Pair<Step, Long> {
        var waited = 0L
        while (true) {
            val callStarted = callStartsAtMs != null && waited >= callStartsAtMs
            val inFlight = linkGoneAtMs == null || waited < linkGoneAtMs
            val step = BoundaryRestartPlan.decide(callStarted, inFlight, waited)
            if (step != Step.STOP_ADVERT_AND_WAIT) return step to waited
            waited += BoundaryRestartPlan.POLL_MS
        }
    }

    @Test
    fun anIdleListenerRestartsAtTheBoundaryAsBefore() {
        assertEquals(Step.RESTART_NOW to 0L, walk(callStartsAtMs = null, linkGoneAtMs = 0L))
    }

    @Test
    fun aCallerThatSubscribesDuringTheHoldKeepsItsCall() {
        // The iPhone of R1 C9 connected 1.1 s before the boundary and subscribed ~1.5 s after connecting.
        assertEquals(Step.SKIP to 500L, walk(callStartsAtMs = 500L, linkGoneAtMs = null))
    }

    @Test
    fun aCentralThatLeavesDuringTheHoldGetsTheNormalRestart() {
        assertEquals(Step.RESTART_NOW to 1_200L, walk(callStartsAtMs = null, linkGoneAtMs = 1_200L))
    }

    @Test
    fun aSilentConnectorDelaysTheRestartByTheBoundedGraceOnly() {
        assertEquals(Step.RESTART_AFTER_GRACE to BoundaryRestartPlan.SETUP_GRACE_MS, walk(callStartsAtMs = null, linkGoneAtMs = null))
    }

    @Test
    fun aCallAlreadyRunningAtTheBoundaryKeepsItsLink() {
        assertEquals(Step.SKIP to 0L, walk(callStartsAtMs = 0L, linkGoneAtMs = null))
        assertEquals(Step.SKIP, BoundaryRestartPlan.decide(callStarted = true, linkInFlight = false, waitedMs = 0L))
    }

    @Test
    fun anLeCocCallerConnectedToTheControlServerHoldsTheBoundaryUntilItsChannelIsAccepted() {
        // The LE CoC control server sees the caller's GATT control link before the L2CAP channel is accepted.
        val centrals = BoundaryRestartPlan.ConnectedCentrals()
        assertEquals(Step.RESTART_NOW, BoundaryRestartPlan.decide(callStarted = false, linkInFlight = centrals.any(), waitedMs = 0L))
        centrals.connected("AA:BB:CC:DD:EE:01")
        centrals.connected("AA:BB:CC:DD:EE:01")
        assertEquals(Step.STOP_ADVERT_AND_WAIT, BoundaryRestartPlan.decide(callStarted = false, linkInFlight = centrals.any(), waitedMs = 0L))
        // The accept marks the call running: the link is kept.
        assertEquals(Step.SKIP, BoundaryRestartPlan.decide(callStarted = true, linkInFlight = centrals.any(), waitedMs = 600L))
    }

    @Test
    fun theControlServerReleasesTheBoundaryWhenEveryCentralHasGone() {
        val centrals = BoundaryRestartPlan.ConnectedCentrals()
        centrals.connected("AA:BB:CC:DD:EE:01")
        centrals.connected("AA:BB:CC:DD:EE:02")
        centrals.disconnected("AA:BB:CC:DD:EE:01")
        assertEquals("one central is still connecting", true, centrals.any())
        centrals.disconnected("AA:BB:CC:DD:EE:02")
        centrals.disconnected("AA:BB:CC:DD:EE:02")
        assertEquals(Step.RESTART_NOW, BoundaryRestartPlan.decide(callStarted = false, linkInFlight = centrals.any(), waitedMs = 300L))
        centrals.connected("AA:BB:CC:DD:EE:03")
        centrals.clear()
        assertEquals(false, centrals.any())
    }

}
