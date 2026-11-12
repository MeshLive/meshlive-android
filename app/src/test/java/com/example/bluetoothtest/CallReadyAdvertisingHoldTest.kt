package com.example.bluetoothtest

import com.example.bluetoothtest.CallReadyAdvertisingHold.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental test_call_adv_idle_fast (JT2-P2): which advertising step an idle call endpoint takes. */
class CallReadyAdvertisingHoldTest {
    private fun decide(
        enabled: Boolean = true,
        callEndpoint: Boolean = true,
        closed: Boolean = false,
        advertising: Boolean = true,
        linkActive: Boolean = false,
        boostHolding: Boolean = false,
    ) = CallReadyAdvertisingHold.decide(enabled, callEndpoint, closed, advertising, linkActive, boostHolding)

    @Test
    fun anIdleCallEndpointKeepsItsFastIntervalWhenTheSwitchIsOn() {
        assertEquals(Action.HOLD, decide())
    }

    @Test
    fun switchOffStepsDownAsBefore() {
        assertEquals(Action.STEP, decide(enabled = false))
    }

    @Test
    fun streamsRelaysAndRenewalAdvertsKeepTheStepEvenWithTheSwitchOn() {
        assertEquals(Action.STEP, decide(callEndpoint = false))
    }

    @Test
    fun aLinkThatIsUpKeepsTodaysLaterRetryOrNothingWhenTheSetIsGone() {
        assertEquals(Action.RETRY_LATER, decide(linkActive = true))
        assertEquals(Action.RETRY_LATER, decide(enabled = false, linkActive = true))
        assertEquals(Action.IGNORE, decide(linkActive = true, advertising = false))
    }

    @Test
    fun aShownVerificationCodeStillHoldsTheBoostAndIsCheckedAgainLater() {
        assertEquals(Action.RETRY_LATER, decide(boostHolding = true))
        assertEquals(Action.RETRY_LATER, decide(enabled = false, boostHolding = true))
    }

    @Test
    fun aClosedServerDoesNothing() {
        assertEquals(Action.IGNORE, decide(closed = true))
        assertEquals(Action.IGNORE, decide(closed = true, enabled = false))
    }

    @Test
    fun theHeldLineNamesTheRoleAndTheSeconds() {
        val line = CallReadyAdvertisingHold.line(120L, 5_000L)
        assertTrue(line.startsWith("BENCHMARK adv_hold: role=call | held_s=120"))
    }
}
