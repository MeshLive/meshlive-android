package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeFlowTest {
    private val nonce = ByteArray(8) { 1 }
    private val otherNonce = ByteArray(8) { 2 }
    private val t0 = 1_000_000L
    private val hex = nonce.joinToString("") { "%02x".format(it) }
    private val context = NativeResponderPolicy.Context(eligible = true, accepting = true, freeDataPaths = 2, upgradeBusy = false)

    @Test
    fun aLostFirstAnswerIsRecoveredByTheNextKnock() {
        val responder = NativeResponderPolicy()
        val initiator = NativeKnockMachine(nonce, t0)

        assertEquals(NativeKnockMachine.Action.SendKnock(1), initiator.begin())
        assertEquals(NativeResponderPolicy.Decision.Serve(hex), responder.onKnock("handle", nonce, t0, context))
        // The first answer is lost in the air: the responder is still pending.
        assertEquals(NativeResponderPolicy.Decision.Refuse("pending"), responder.onKnock("handle", nonce, t0 + 100, context))
        // The initiator knocks again at +1 s.
        assertEquals(NativeKnockMachine.Action.SendKnock(2), initiator.onTick(t0 + 1_000))
        assertEquals(NativeResponderPolicy.Decision.Refuse("pending"), responder.onKnock("handle", nonce, t0 + 1_000, context))
        // The responder's request is filed: MWA1 is (re)sent and the initiator proceeds.
        responder.armed(hex, 41_000)
        val resend = responder.onKnock("handle", nonce, t0 + 1_100, context)
        assertEquals(NativeResponderPolicy.Decision.Resend(41_000), resend)
        assertEquals(NativeKnockMachine.Action.RequestDataPath(41_000), initiator.onAnswer(nonce, 41_000))
    }

    @Test
    fun aHostileHandleCannotTakeTheGenuineSlot() {
        val responder = NativeResponderPolicy()
        val initiator = NativeKnockMachine(nonce, t0)

        initiator.begin()
        assertEquals(NativeResponderPolicy.Decision.Serve(hex), responder.onKnock("genuine", nonce, t0, context))
        // A stranger's own nonce while one native request is pending: refused, and the genuine retry is still served.
        assertEquals(NativeResponderPolicy.Decision.Refuse("busy"), responder.onKnock("hostile", otherNonce, t0 + 10, context))
        assertEquals(NativeResponderPolicy.Decision.Refuse("pending"), responder.onKnock("genuine", nonce, t0 + 20, context))
        responder.armed(hex, 42_000)
        assertEquals(NativeResponderPolicy.Decision.Resend(42_000), responder.onKnock("genuine", nonce, t0 + 30, context))
        assertEquals(NativeKnockMachine.Action.RequestDataPath(42_000), initiator.onAnswer(nonce, 42_000))
    }
}
