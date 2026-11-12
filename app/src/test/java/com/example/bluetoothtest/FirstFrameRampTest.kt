package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental test_call_first_frame_ramp: first frame at once, then a bounded slow-down into the playout target. */
class FirstFrameRampTest {
    private val lines = ArrayList<String>()
    private val ms = 1_000_000L

    /**
     * A 30 FPS call whose frames arrive and decode on time: frame i is decoded at i x 33 ms (ms), the shared clock
     * wants it at [leadMs] + i x 33 ms. Returns each frame's render time in ms.
     */
    private fun play(ramp: FirstFrameRamp, leadMs: Long, frames: Int): List<Long> = (0 until frames).map { i ->
        val ptsUs = i * 33_333L
        val nowNs = i * 33_333_000L
        val clockNs = leadMs * ms + nowNs
        ramp.renderTimeNs(ptsUs, clockNs, nowNs) / ms
    }

    @Test
    fun theFirstFrameShowsAtOnceAndLaterFramesNeverFreezeWhileTheDelayGrowsToTheTarget() {
        val ramp = FirstFrameRamp(log = { lines += it })
        val renders = play(ramp, leadMs = 500L, frames = 120)
        assertEquals("first frame at arrival, not 500 ms later", 0L, renders.first())
        val gaps = renders.zipWithNext { a, b -> b - a }
        assertTrue("no gap longer than a slowed frame interval (no freeze): ${gaps.max()}", gaps.max() <= 50L)
        assertTrue("never later than the playout target", renders.withIndex().all { (i, r) -> r <= 500L + i * 33_333L / 1_000L + 1 })
        assertEquals(FirstFrameRamp.State.DONE, ramp.state)
        val end = lines.single { "phase=end" in it }
        assertTrue(end, "reason=caught_up" in end)
        val handoverMs = Regex("handoverRenderMs=(\\d+)").find(end)!!.groupValues[1].toLong()
        assertTrue("the ramp meets the target about 2 s after the first frame, was $handoverMs", handoverMs in 1_800L..2_200L)
        // From then on the shared timeline applies unchanged.
        assertEquals(500L + 119L * 33_333L / 1_000L, renders.last())
    }

    @Test
    fun theSlowdownIsBoundedSoALongLeadTakesLongerInsteadOfPlayingSlower() {
        assertEquals(4.0 / 3.0, FirstFrameRamp.slowdownFor(500L), 1e-9)
        assertEquals(FirstFrameRamp.MAX_SLOWDOWN, FirstFrameRamp.slowdownFor(1_500L), 1e-9)
        assertEquals(FirstFrameRamp.MAX_SLOWDOWN, FirstFrameRamp.slowdownFor(5_000L), 1e-9)
        val ramp = FirstFrameRamp(log = { lines += it })
        val renders = play(ramp, leadMs = 900L, frames = 150)
        val gaps = renders.zipWithNext { a, b -> b - a }
        assertTrue("never slower than 1/1.5 speed: ${gaps.max()}", gaps.max() <= 51L)
        assertEquals(FirstFrameRamp.State.DONE, ramp.state)
    }

    @Test
    fun aFirstFrameAlreadyAtItsTargetSkipsTheRamp() {
        val ramp = FirstFrameRamp(log = { lines += it })
        assertEquals(5L * ms, ramp.renderTimeNs(0L, clockTargetNs = 5L * ms, nowNs = 0L))
        assertEquals(FirstFrameRamp.State.DONE, ramp.state)
        assertTrue(lines.single().contains("phase=skipped"))
        assertEquals(40L * ms, ramp.feedTargetNs(33_333L, 40L * ms))
    }

    @Test
    fun aTargetThatKeepsGrowingHandsOverAfterTheMaximumRampTime() {
        val ramp = FirstFrameRamp(log = { lines += it })
        // The clock's lead grows by 20 ms every frame, faster than the ramp gains: only the time bound ends it.
        val renders = (0 until 200).map { i ->
            val nowNs = i * 33_333_000L
            ramp.renderTimeNs(i * 33_333L, nowNs + (500L + 20L * i) * ms, nowNs)
        }
        assertEquals(FirstFrameRamp.State.DONE, ramp.state)
        assertTrue(lines.any { "reason=timeout" in it })
        assertTrue(renders.size == 200)
    }

    @Test
    fun feedingFollowsTheRampOnceItRunsAndIsImmediateBeforeTheFirstOutput() {
        val ramp = FirstFrameRamp(log = { lines += it })
        assertNull("before the first frame is decoded, frames are fed at once", ramp.feedTargetNs(0L, 500L * ms))
        ramp.renderTimeNs(0L, 500L * ms, 0L)
        val factor = ramp.factor
        val feed = ramp.feedTargetNs(330_000L, 830L * ms)!!
        assertEquals((330_000L * 1_000L * factor).toLong(), feed)
        assertTrue("earlier than the clock's target while ramping", feed < 830L * ms)
    }
}
