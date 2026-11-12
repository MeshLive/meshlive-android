package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DEBUG `test_call_inject_send_pause`: the call writer holds media for the declared windows after its first media record. */
class CallSendPauseInjectTest {
    private val ms = 1_000_000L

    @Test
    fun aDeclaredPauseHoldsMediaOnlyInsideItsWindowAfterTheFirstMediaRecord() {
        val lines = mutableListOf<String>()
        val schedule = CallSendPauseInject.Schedule(CallSendPauseInject.parse("30000:526"), lines::add)
        assertEquals("nothing is held before media flows", 0L, schedule.holdNs(40_000 * ms))
        schedule.onMediaWritten(1_000 * ms)
        assertEquals(0L, schedule.holdNs(30_999 * ms))
        assertEquals("held until the window ends", 526 * ms, schedule.holdNs(31_000 * ms))
        assertEquals(26 * ms, schedule.holdNs(31_500 * ms))
        assertEquals("media flows again after the window", 0L, schedule.holdNs(31_526 * ms))
        assertEquals(listOf("BENCHMARK call_inject: kind=send_pause | afterMs=30000 | durationMs=526 | index=0"), lines)
    }

    @Test
    fun laterMediaDoesNotMoveTheWindowsAndEachWindowLogsOnce() {
        val lines = mutableListOf<String>()
        val schedule = CallSendPauseInject.Schedule(CallSendPauseInject.parse("5000:200,2000:100"), lines::add)
        schedule.onMediaWritten(0L)
        schedule.onMediaWritten(4_000 * ms)
        assertTrue(schedule.holdNs(2_050 * ms) > 0L)
        assertTrue(schedule.holdNs(2_060 * ms) > 0L)
        assertEquals(0L, schedule.holdNs(3_000 * ms))
        assertTrue(schedule.holdNs(5_100 * ms) > 0L)
        assertEquals(2, lines.size)
    }

    @Test
    fun malformedEntriesAreLeftOutAndLongPausesAreCapped() {
        assertEquals(emptyList<CallSendPauseInject.Window>(), CallSendPauseInject.parse(null))
        assertEquals(emptyList<CallSendPauseInject.Window>(), CallSendPauseInject.parse("x:1,5:0,-1:5,7"))
        assertEquals(
            listOf(CallSendPauseInject.Window(10L, CallSendPauseInject.MAX_DURATION_MS)),
            CallSendPauseInject.parse(" 10:99999 "),
        )
    }

    @Test
    fun withNothingDeclaredTheWriterGetsNoSchedule() {
        assertNull(CallSendPauseInject.newSchedule {})
    }
}
