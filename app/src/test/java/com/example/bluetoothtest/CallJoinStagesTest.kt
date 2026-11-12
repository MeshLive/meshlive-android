package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallJoinStagesTest {
    private var nowMs = 1_000L
    private val lines = mutableListOf<String>()
    private val timeline = CallJoinTimeline(enabled = true, clockMs = { nowMs }) { lines += it }

    private fun field(line: String, name: String): String? = Regex("""\b$name=([^ |]+)""").find(line)?.groupValues?.get(1)

    /** The per-stage lines only (a call's end adds one summary line). */
    private fun stageLines() = lines.filter { it.startsWith("BENCHMARK call_join_stage: ") }

    @Test
    fun aCallerDialToFirstPictureLogsEachStageOnceOnItsOwnClock() {
        timeline.begin("caller")
        timeline.callId("c0ffee")
        timeline.mark(CallJoinStage.DIAL)
        nowMs += 1_200
        timeline.mark(CallJoinStage.RINGING)
        nowMs += 300
        timeline.mark(CallJoinStage.RINGING) // a repeated Ringing signal is not a second stage
        timeline.mark(CallJoinStage.FIRST_FRAME_SHOWN, "black=false")

        assertEquals(listOf("dial", "ringing", "first_frame_shown"), lines.map { field(it, "stage") })
        assertEquals(listOf("0", "1200", "1500"), lines.map { field(it, "sinceStartMs") })
        assertTrue(lines.all { it.startsWith("BENCHMARK call_join_stage: ") && field(it, "role") == "caller" })
        assertEquals("c0ffee", field(lines[0], "callId"))
        assertEquals("false", field(lines[2], "black"))
        assertEquals("2500", field(lines[2], "atMs"))
    }

    @Test
    fun nothingIsLoggedOutsideACallAndTheNextCallStartsAFreshTimeline() {
        assertFalse(timeline.mark(CallJoinStage.FIRST_FRAME_DECODED)) // a stream viewer's decoder, no call
        timeline.begin("callee")
        timeline.mark(CallJoinStage.LINK_UP)
        timeline.end()
        assertFalse(timeline.pending(CallJoinStage.FIRST_FRAME_SHOWN))
        assertFalse(timeline.mark(CallJoinStage.ENCODER_STARTED)) // a late encoder restart after the end

        nowMs += 5_000
        timeline.begin("caller")
        assertTrue(timeline.pending(CallJoinStage.LINK_UP))
        timeline.mark(CallJoinStage.LINK_UP)
        assertEquals(listOf("callee", "caller"), stageLines().map { field(it, "role") })
        assertEquals("0", field(stageLines()[1], "sinceStartMs"))
        assertEquals(null, field(stageLines()[1], "callId"))
    }

    @Test
    fun aTapBeforeTheDialIsLoggedFirstAndTheDialKeepsItsMeaning() {
        nowMs = 10_000
        timeline.noteTap("user")
        assertTrue(lines.isEmpty()) // nothing is logged until a dial follows
        nowMs += 1_016
        timeline.begin("caller")
        timeline.mark(CallJoinStage.DIAL)
        assertEquals(listOf("tap", "dial"), lines.map { field(it, "stage") })
        assertEquals("-1016", field(lines[0], "sinceStartMs"))
        assertEquals("10000", field(lines[0], "atMs"))
        assertEquals("1016", field(lines[0], "tapToDialMs"))
        assertEquals("user", field(lines[0], "source"))
        assertEquals("0", field(lines[1], "sinceStartMs")) // the dial still starts the call's clock
        assertEquals("11016", field(lines[1], "atMs"))
    }

    @Test
    fun aStaleTapIsDroppedAndATapWithNoDialIsLoggedNowhere() {
        timeline.noteTap()
        nowMs += CallJoinTimeline.TAP_MAX_AGE_MS
        timeline.begin("caller")
        timeline.mark(CallJoinStage.DIAL)
        assertEquals(listOf("dial"), lines.map { field(it, "stage") })

        lines.clear()
        timeline.end()
        timeline.noteTap("debug")
        nowMs += 60_000 // the person was never reached: no dial follows
        assertTrue(stageLines().isEmpty())
        assertFalse(timeline.pending(CallJoinStage.DIAL))
    }

    @Test
    fun aTapIsUsedByTheNextCallOnlyAndNeverByACallee() {
        timeline.noteTap()
        nowMs += 200
        timeline.begin("callee") // an incoming call before the dial: the tap is not its tap
        timeline.mark(CallJoinStage.LINK_UP)
        nowMs += 200
        timeline.begin("caller")
        timeline.mark(CallJoinStage.DIAL)
        assertEquals(listOf("link_up", "dial"), lines.map { field(it, "stage") })

        lines.clear()
        timeline.noteTap()
        nowMs += 300
        timeline.begin("caller")
        nowMs += 5_000
        timeline.end()
        timeline.begin("caller") // the next call starts fresh: no second tap line
        timeline.mark(CallJoinStage.DIAL)
        assertEquals(listOf("tap", "dial"), stageLines().map { field(it, "stage") })
    }

    @Test
    fun theCallsEndRepeatsEveryStageInOneSummaryLineSoALostStageLineCanBeRecovered() {
        timeline.begin("callee")
        timeline.callId("c0ffee")
        timeline.mark(CallJoinStage.LINK_UP, "link=BLE GATT (PHY pending)")
        nowMs += 900
        timeline.mark(CallJoinStage.ACCEPT, "by=user")
        nowMs += 1_100
        timeline.mark(CallJoinStage.FIRST_FRAME_SHOWN, "black=false | meanLuma=40.2")
        nowMs += 30_000
        timeline.end()
        timeline.end() // a second end of the same call adds nothing

        val summaries = lines.filter { it.startsWith("BENCHMARK call_join_summary: ") }
        assertEquals(1, summaries.size)
        assertEquals("callee", field(summaries[0], "role"))
        assertEquals("c0ffee", field(summaries[0], "callId"))
        assertEquals("1000", field(summaries[0], "startMs"))
        assertEquals("link_up@1000,accept@1900[by=user],first_frame_shown@3000[black=false;meanLuma=40.2]",
            field(summaries[0], "stages"))
    }

    @Test
    fun aTimelineWithNoStageAddsNoSummaryAndReleaseBuildsNone() {
        timeline.begin("caller")
        timeline.end()
        val off = CallJoinTimeline(enabled = false, clockMs = { nowMs }) { lines += it }
        off.begin("caller")
        off.mark(CallJoinStage.DIAL)
        off.end()
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aOncePerCallLineIsClaimedOnceAndFreshInTheNextCall() {
        assertFalse(timeline.claimOnce("camera_caps")) // no call is timed
        timeline.begin("caller")
        assertTrue(timeline.claimOnce("camera_caps"))
        assertFalse(timeline.claimOnce("camera_caps"))
        timeline.begin("caller")
        assertTrue(timeline.claimOnce("camera_caps"))
    }

    @Test
    fun releaseBuildsLogNothing() {
        val off = CallJoinTimeline(enabled = false, clockMs = { nowMs }) { lines += it }
        off.noteTap()
        off.begin("caller")
        assertFalse(off.mark(CallJoinStage.DIAL))
        assertFalse(off.claimOnce("camera_caps"))
        assertFalse(off.pending(CallJoinStage.DIAL))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun aBlankFirstPictureIsBlackAndADarkSceneIsNot() {
        val blank = IntArray(64) { 0xff000000.toInt() }
        val dimRoom = IntArray(64) { 0xff141414.toInt() } // RGB 20: dark but a picture
        assertTrue(CallJoinStages.black(CallJoinStages.meanLuma(blank)!!))
        assertFalse(CallJoinStages.black(CallJoinStages.meanLuma(dimRoom)!!))
        assertEquals(null, CallJoinStages.meanLuma(IntArray(0)))
    }

    private val watch = FirstNonBlackWatch(timeline, { nowMs })

    private fun nonBlackLines() = lines.filter { field(it, "stage") == "first_nonblack_shown" }

    private fun shown(luma: Double?): Boolean {
        timeline.mark(CallJoinStage.FIRST_FRAME_SHOWN, "black=${luma?.let(CallJoinStages::black)}")
        return watch.onFirstShown(luma)
    }

    @Test
    fun aFirstPictureWithContentLogsTheNonBlackStageAtTheSameMoment() {
        timeline.begin("caller")
        timeline.mark(CallJoinStage.ACCEPT, "by=peer")
        nowMs += 900
        assertFalse(shown(40.0))
        val line = nonBlackLines().single()
        assertEquals("900", field(line, "sinceStartMs"))
        assertEquals("true", field(line, "reached"))
        assertEquals("false", field(line, "black"))
        assertEquals("40.0", field(line, "meanLuma"))
        assertEquals("0", field(line, "sinceFirstShownMs"))
        assertFalse(watch.onSample(90.0)) // nothing more to sample, nothing more logged
        assertEquals(1, nonBlackLines().size)
    }

    @Test
    fun aBlackFirstPictureIsSampledUntilTheFirstFrameWithContent() {
        timeline.begin("callee")
        timeline.mark(CallJoinStage.ACCEPT, "by=local")
        nowMs += 1_000
        assertTrue(shown(0.4))
        repeat(3) { nowMs += 200; assertTrue(watch.onSample(1.0)) }
        assertTrue(nonBlackLines().isEmpty())
        nowMs += 200
        assertFalse(watch.onSample(22.5))
        val line = nonBlackLines().single()
        assertEquals("true", field(line, "reached"))
        assertEquals("22.5", field(line, "meanLuma"))
        assertEquals("1800", field(line, "sinceStartMs"))
        assertEquals("800", field(line, "sinceFirstShownMs"))
        assertEquals("5", field(line, "samples"))
        assertFalse(watch.onSample(80.0))
        assertEquals(1, nonBlackLines().size)
    }

    @Test
    fun aPictureThatStaysBlackIsReportedNotReachedAtTheBoundAfterAccept() {
        timeline.begin("caller")
        timeline.mark(CallJoinStage.ACCEPT, "by=peer")
        nowMs += 3_000
        assertTrue(shown(0.0))
        nowMs += 11_000
        assertTrue(watch.onSample(0.0)) // 14 s after Accept: still inside the bound
        nowMs += 1_000
        assertFalse(watch.onSample(0.0)) // 15 s after Accept
        val line = nonBlackLines().single()
        assertEquals("false", field(line, "reached"))
        assertEquals("true", field(line, "black"))
        assertEquals("15000", field(line, "sinceStartMs"))
        assertFalse(watch.onSample(70.0)) // a later bright frame is not logged: the stage was already reported
        assertEquals(1, nonBlackLines().size)
    }

    @Test
    fun aCallThatEndsWhileBlackAndTheNextCallStartFresh() {
        timeline.begin("caller")
        timeline.mark(CallJoinStage.ACCEPT)
        assertTrue(shown(null)) // the copy was unavailable: unknown, still sampling
        watch.finish("call_ended")
        val line = nonBlackLines().single()
        assertEquals("false", field(line, "reached"))
        assertEquals("call_ended", field(line, "reason"))
        timeline.end()

        timeline.begin("callee")
        watch.reset()
        assertFalse(watch.onSample(90.0)) // the earlier call's sampler no longer logs anything
        assertEquals(1, nonBlackLines().size)
        timeline.mark(CallJoinStage.ACCEPT)
        assertFalse(shown(60.0))
        assertEquals(2, nonBlackLines().size)
    }
}
