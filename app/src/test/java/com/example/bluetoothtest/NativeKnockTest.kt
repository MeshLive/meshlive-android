package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeKnockTest {
    private val nonce = ByteArray(8) { it.toByte() }
    private val t0 = 1_000_000L

    private fun machine(sendAt: LongArray = NativeKnockMachine.SEND_AT_MS) = NativeKnockMachine(nonce, t0, sendAt)

    @Test
    fun theKnockTimelineIsZeroOneTwoFourSecondsAndNoAnswerAtEight() {
        val machine = machine()
        assertEquals(NativeKnockMachine.Action.SendKnock(1), machine.begin())
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 999))
        assertEquals(NativeKnockMachine.Action.SendKnock(2), machine.onTick(t0 + 1_000))
        assertEquals(NativeKnockMachine.Action.SendKnock(3), machine.onTick(t0 + 2_000))
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 3_999))
        assertEquals(NativeKnockMachine.Action.SendKnock(4), machine.onTick(t0 + 4_000))
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 7_999))
        assertEquals(NativeKnockMachine.Action.Fail("no_answer"), machine.onTick(t0 + 8_000))
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 8_001))
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 30_000))
    }

    @Test
    fun anInTimeAnswerRequestsTheDataPathOnce() {
        val machine = machine()
        machine.begin()
        machine.onTick(t0 + 1_000)
        assertEquals(NativeKnockMachine.Action.RequestDataPath(41_000), machine.onAnswer(nonce, 41_000))
        assertEquals(NativeKnockMachine.Action.None, machine.onAnswer(nonce, 41_000))
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 2_000))
        assertEquals(NativeKnockMachine.Action.None, machine.onTick(t0 + 9_000))
    }

    @Test
    fun aWrongNonceIsIgnoredAndTheCycleContinues() {
        val machine = machine()
        machine.begin()
        assertEquals(NativeKnockMachine.Action.None, machine.onAnswer(ByteArray(8) { 9 }, 41_000))
        assertEquals(1, machine.ignoredAnswers)
        assertEquals(NativeKnockMachine.Action.SendKnock(2), machine.onTick(t0 + 1_000))
        assertEquals(NativeKnockMachine.Action.RequestDataPath(41_000), machine.onAnswer(nonce, 41_000))
    }

    @Test
    fun aBadPortFails() {
        for (port in listOf(0, 70_000)) {
            val machine = machine()
            machine.begin()
            assertEquals(NativeKnockMachine.Action.Fail("bad_port"), machine.onAnswer(nonce, port))
        }
    }

    @Test
    fun fourFailedSendsFailAndThreeThenSuccessKeepsGoing() {
        val all = machine()
        all.begin()
        assertEquals(NativeKnockMachine.Action.None, all.onSendResult(false))
        assertEquals(NativeKnockMachine.Action.None, all.onSendResult(false))
        assertEquals(NativeKnockMachine.Action.None, all.onSendResult(false))
        assertEquals(NativeKnockMachine.Action.Fail("send_failed"), all.onSendResult(false))

        val three = machine()
        three.begin()
        three.onSendResult(false)
        three.onSendResult(false)
        three.onSendResult(false)
        assertEquals(NativeKnockMachine.Action.None, three.onSendResult(true))
        assertEquals(NativeKnockMachine.Action.SendKnock(2), three.onTick(t0 + 1_000))
        assertEquals(NativeKnockMachine.Action.Fail("send_failed"), three.onSendResult(false))
    }

    @Test
    fun theAttemptDeadlineFailsEvenWhileKnocking() {
        val machine = machine()
        machine.begin()
        assertEquals(NativeKnockMachine.Action.Fail("attempt_timeout"), machine.onTick(t0 + 25_000))
    }

    @Test
    fun theThrottleAllowsOneCyclePerPeerInterval() {
        val throttle = KnockThrottle()
        assertTrue(throttle.tryStart("k", 0))
        assertFalse(throttle.tryStart("k", 9_999))
        assertTrue(throttle.tryStart("k", 10_000))
        assertTrue(throttle.tryStart("other", 10_000))
        assertFalse(throttle.tryStart("other", 10_001))
        throttle.forget("k")
        assertTrue(throttle.tryStart("k", 10_002))
    }
}
