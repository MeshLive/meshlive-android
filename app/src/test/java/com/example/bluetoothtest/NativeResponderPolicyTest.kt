package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeResponderPolicyTest {
    private val nonceA = ByteArray(8) { 1 }
    private val nonceB = ByteArray(8) { 2 }
    private val hexA = nonceA.joinToString("") { "%02x".format(it) }
    private val hexB = nonceB.joinToString("") { "%02x".format(it) }

    private fun context(
        eligible: Boolean = true,
        accepting: Boolean = true,
        freeDataPaths: Int = 2,
        upgradeBusy: Boolean = false,
    ) = NativeResponderPolicy.Context(eligible, accepting, freeDataPaths, upgradeBusy)

    @Test
    fun aRetryOfAPendingKnockIsRefusedUntilArmedThenResent() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Serve(hexA), policy.onKnock("h1", nonceA, 1_000, context()))
        assertEquals(NativeResponderPolicy.Decision.Refuse("pending"), policy.onKnock("h1", nonceA, 1_100, context()))
        policy.armed(hexA, 40_000)
        assertEquals(NativeResponderPolicy.Decision.Resend(40_000), policy.onKnock("h1", nonceA, 1_200, context()))
    }

    @Test
    fun everyContextFlagHasItsRefusalReason() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Refuse("not_eligible"), policy.onKnock("h1", nonceA, 1_000, context(eligible = false)))
        assertEquals(NativeResponderPolicy.Decision.Refuse("not_accepting"), policy.onKnock("h1", nonceA, 1_000, context(accepting = false)))
        assertEquals(NativeResponderPolicy.Decision.Refuse("no_free_path"), policy.onKnock("h1", nonceA, 1_000, context(freeDataPaths = 0)))
        assertEquals(NativeResponderPolicy.Decision.Refuse("no_free_path"), policy.onKnock("h1", nonceA, 1_000, context(upgradeBusy = true)))
    }

    @Test
    fun aSecondStrangerIsRefusedWhileOneIsPending() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Serve(hexA), policy.onKnock("h1", nonceA, 1_000, context()))
        assertEquals(NativeResponderPolicy.Decision.Refuse("busy"), policy.onKnock("h2", nonceB, 1_000, context()))
        assertEquals(1, policy.activeCount())
    }

    @Test
    fun admittedFreesTheSlot() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Serve(hexA), policy.onKnock("h1", nonceA, 1_000, context()))
        policy.admitted(hexA)
        assertEquals(0, policy.activeCount())
        assertEquals(NativeResponderPolicy.Decision.Serve(hexB), policy.onKnock("h2", nonceB, 1_000, context()))
    }

    @Test
    fun finishedFreesTheSlot() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Serve(hexA), policy.onKnock("h1", nonceA, 1_000, context()))
        policy.finished(hexA)
        assertEquals(0, policy.activeCount())
        assertEquals(NativeResponderPolicy.Decision.Serve(hexB), policy.onKnock("h2", nonceB, 1_000, context()))
    }

    @Test
    fun theSameHandleIsRateLimitedForTenSeconds() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Serve(hexA), policy.onKnock("h1", nonceA, 1_000, context()))
        policy.finished(hexA)
        assertEquals(NativeResponderPolicy.Decision.Refuse("rate"), policy.onKnock("h1", nonceB, 10_999, context()))
        assertEquals(NativeResponderPolicy.Decision.Serve(hexB), policy.onKnock("h1", nonceB, 11_000, context()))
    }

    @Test
    fun theCheckOrderReportsNotEligibleEvenWhenBusy() {
        val policy = NativeResponderPolicy()
        assertEquals(NativeResponderPolicy.Decision.Serve(hexA), policy.onKnock("h1", nonceA, 1_000, context()))
        assertEquals(NativeResponderPolicy.Decision.Refuse("not_eligible"), policy.onKnock("h2", nonceB, 1_000, context(eligible = false)))
    }

    @Test
    fun aWiderReachHopWhoseBandNeitherEndCanReadIsRefused() {
        // Android 11 and 12 cannot read a data path's band: the hop is refused before any data path (A8 review P2-3).
        for (sdk in 30..32) assertEquals(false, NativeBandRule.canVerify(sdk))
        assertEquals(true, NativeBandRule.canVerify(33))
        assertEquals("band_unverified", NativeBandRule.refusal(AwareBand.verdict(null)))
        assertEquals("band_low", NativeBandRule.refusal(AwareBand.verdict(listOf(2437))))
        assertEquals("band_low", NativeBandRule.refusal(AwareBand.verdict(emptyList())))
        assertEquals(null, NativeBandRule.refusal(AwareBand.verdict(listOf(5180, 5745))))
    }
}
