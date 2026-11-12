package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental call fast recovery: a call whose sender paused once (b12sm: 526 ms, +303 ms added delay for the rest of
 * the call) returns to its earlier delay by leaving out quiet audio in pauses; sustained lateness, speech, streams and
 * the switch off keep today's clock.
 */
class CallFastRecoveryTest {
    private val baseNs = 10_000_000_000L

    /** One call audio packet every 20 ms; the sender pauses for [gapMs] at [gapAtUs], then its backlog bursts in. */
    private fun arrivalNs(ptsUs: Long, gapAtUs: Long = 1_000_000L, gapMs: Long = 526L): Long = when {
        ptsUs < gapAtUs -> baseNs + ptsUs * 1_000L
        ptsUs < gapAtUs + gapMs * 1_000L -> baseNs + gapAtUs * 1_000L + gapMs * 1_000_000L + (ptsUs - gapAtUs) * 10L
        else -> baseNs + ptsUs * 1_000L
    }

    private fun callClock(recovery: CallFastRecovery?) = PlaybackDelayProfile.CALL.newPlayoutClock(fastRecovery = recovery)

    /** Feeds every packet with PTS in [fromUs, toUs) that has arrived by [untilNs]; returns the next PTS. */
    private fun feed(clock: MediaPlayoutClock, fromUs: Long, untilNs: Long, arrival: (Long) -> Long = ::arrivalNs): Long {
        var pts = fromUs
        while (arrival(pts) <= untilNs) {
            clock.timing(pts, arrival(pts))
            pts += 20_000L
        }
        return pts
    }

    @Test
    fun isolatedSenderPauseReturnsTheCallDelayWithinSecondsByLeavingOutQuietAudio() {
        val lines = mutableListOf<String>()
        val clock = callClock(CallFastRecovery { lines += it })
        var pts = feed(clock, 0L, baseNs + 1_600_000_000L)
        val grownMs = clock.addedDelayMs
        assertTrue("the pause grows the call delay ($grownMs ms)", grownMs >= 250L)

        // Before two calm seconds nothing may be removed.
        assertFalse(clock.consumeFastRecovery(20L, pts, baseNs + 3_000_000_000L))
        assertEquals(grownMs, clock.addedDelayMs)

        // Calm for 2 s after the burst: the audio writer leaves out one quiet 20 ms chunk per 80 ms of pause.
        var nowNs = baseNs + 3_600_000_000L
        pts = feed(clock, pts, nowNs)
        var removedMs = 0L
        while (clock.consumeFastRecovery(20L, pts + 300_000L, nowNs)) {
            removedMs += 20L
            nowNs += 80_000_000L
            pts = feed(clock, pts, nowNs)
        }
        assertTrue("back within one chunk of the delay before the pause: ${clock.addedDelayMs} ms", clock.addedDelayMs < 20L)
        assertEquals(grownMs - clock.addedDelayMs, removedMs)
        assertTrue("recovered in a few seconds of pause", nowNs - baseNs - 3_600_000_000L <= 2_000_000_000L)
        assertTrue(lines.any { "event=armed" in it })
        assertTrue(lines.any { "event=consumed" in it && "droppedAudioMs=20 " in it })
        assertTrue(lines.any { "event=done" in it && "droppedAudioMs=$removedMs " in it })
        // Media keeps arriving on time against the lower delay: no new lateness, no new growth.
        feed(clock, pts, nowNs + 2_000_000_000L)
        assertTrue(clock.addedDelayMs < 20L)
    }

    @Test
    fun aLeftOutChunkMovesMediaEarlierOnlyFromThePointWhereAudioSkips() {
        val clock = callClock(CallFastRecovery())
        val nowNs = baseNs + 3_600_000_000L
        val pts = feed(clock, 0L, nowNs)
        val skipUs = pts + 200_000L
        val beforeSkip = clock.targetFor(skipUs - 20_000L, nowNs).targetTimeNs
        val atSkip = clock.targetFor(skipUs, nowNs).targetTimeNs
        val later = clock.targetFor(skipUs + 500_000L, nowNs).targetTimeNs

        assertTrue(clock.consumeFastRecovery(20L, skipUs, nowNs))

        // Video and audio before the skip point keep their times; from it on both play 20 ms earlier (lip sync holds).
        assertEquals(beforeSkip, clock.targetFor(skipUs - 20_000L, nowNs).targetTimeNs)
        assertEquals(atSkip - 20_000_000L, clock.targetFor(skipUs, nowNs).targetTimeNs)
        assertEquals(later - 20_000_000L, clock.targetFor(skipUs + 500_000L, nowNs).targetTimeNs)
    }

    @Test
    fun repeatedLateBurstsKeepTodaysBehaviour() {
        val lines = mutableListOf<String>()
        val withRecovery = callClock(CallFastRecovery { lines += it })
        val today = callClock(null)
        // A second pause 1.5 s after the first: sustained lateness, not one isolated glitch.
        val arrival = { ptsUs: Long ->
            if (ptsUs < 2_500_000L) arrivalNs(ptsUs) else arrivalNs(ptsUs, gapAtUs = 2_500_000L, gapMs = 690L)
        }
        val untilNs = baseNs + 6_500_000_000L
        feed(withRecovery, 0L, untilNs, arrival)
        feed(today, 0L, untilNs, arrival)

        assertEquals(0L, withRecovery.fastRecoveryPendingMs(untilNs))
        assertFalse(withRecovery.consumeFastRecovery(20L, 7_000_000L, untilNs))
        assertEquals(today.addedDelayMs, withRecovery.addedDelayMs)
        assertEquals(today.targetFor(7_000_000L, untilNs), withRecovery.targetFor(7_000_000L, untilNs))
        assertTrue(lines.any { "event=sustained" in it })
        assertFalse(lines.any { "event=armed" in it })
    }

    @Test
    fun lateMediaSoonAfterRecoveryArmedStopsIt() {
        val clock = callClock(CallFastRecovery())
        var nowNs = baseNs + 3_600_000_000L
        val pts = feed(clock, 0L, nowNs)
        assertTrue(clock.consumeFastRecovery(20L, pts, nowNs))
        // One more late arrival 3 s after the first burst: repeated lateness, back to today's slow shrink.
        nowNs += 1_000_000_000L
        clock.timing(pts, nowNs)
        assertEquals(0L, clock.fastRecoveryPendingMs(nowNs + 3_000_000_000L))
        assertFalse(clock.consumeFastRecovery(20L, pts + 1_000_000L, nowNs + 3_000_000_000L))
    }

    @Test
    fun withoutConsumptionTheClockTimesEveryPacketExactlyAsToday() {
        val withRecovery = callClock(CallFastRecovery())
        val today = callClock(null)
        var pts = 0L
        while (pts < 9_000_000L) {
            val arrival = arrivalNs(pts)
            assertEquals(today.timing(pts, arrival), withRecovery.timing(pts, arrival))
            assertEquals(today.targetFor(pts + 100_000L, arrival), withRecovery.targetFor(pts + 100_000L, arrival))
            assertEquals(today.addedDelayMs, withRecovery.addedDelayMs)
            pts += 20_000L
        }
    }

    @Test
    fun streamsNeverGetFastRecovery() {
        assertNull(CallFastRecovery.forProfile(PlaybackDelayProfile.BALANCED, enabled = true))
        assertNull(CallFastRecovery.forProfile(PlaybackDelayProfile.CALL, enabled = false))
        val stream = PlaybackDelayProfile.BALANCED.newPlayoutClock(fastRecovery = CallFastRecovery())
        val nowNs = baseNs + 3_600_000_000L
        val pts = feed(stream, 0L, nowNs)
        assertTrue(stream.addedDelayMs > 0L)
        val before = stream.addedDelayMs
        assertFalse(stream.consumeFastRecovery(20L, pts, nowNs))
        assertEquals(before, stream.addedDelayMs)
    }

    @Test
    fun aPersonsLaunchKeepsFastRecoveryOnAndOnlyADebugLaunchHasTheControlArm() {
        try {
            DebugTunables.callFastRecovery = false
            assertEquals("the off control arm exists only in DEBUG", !BuildConfig.DEBUG, DebugTunables.callFastRecovery)
        } finally {
            DebugTunables.callFastRecovery = true
        }
        assertTrue(DebugTunables.callFastRecovery)
    }

    // --- The audio writer's choice of chunk ---

    private val loud = 4_000.0
    private val quiet = 50.0

    @Test
    fun speechIsNeverLeftOut() {
        val skipper = QuietAudioSkipper()
        repeat(200) {
            assertEquals(QuietAudioSkipper.Verdict.PLAY_SPEECH, skipper.consider(loud, 20L, 300L, 250L))
            skipper.onPlayed(loud, 20L)
        }
    }

    @Test
    fun quietRightAfterSpeechIsKeptUntilTheHangoverPasses() {
        val skipper = QuietAudioSkipper()
        skipper.onPlayed(loud, 20L)
        repeat((QuietAudioSkipper.HANGOVER_MS / 20L).toInt()) {
            assertEquals(QuietAudioSkipper.Verdict.PLAY_SPEECH, skipper.consider(quiet, 20L, 300L, 250L))
            skipper.onPlayed(quiet, 20L)
        }
        assertEquals(QuietAudioSkipper.Verdict.CANDIDATE, skipper.consider(quiet, 20L, 300L, 250L))
        // A speech onset next keeps the quiet lead-in.
        assertFalse(skipper.nextAllowsDrop(loud))
        assertTrue(skipper.nextAllowsDrop(quiet))
    }

    @Test
    fun atMostOneChunkInEveryEightyMillisecondsOfPauseIsLeftOut() {
        val skipper = QuietAudioSkipper()
        repeat(10) { skipper.onPlayed(quiet, 20L) }
        var left = 0
        var played = 0
        repeat(100) {
            if (skipper.consider(quiet, 20L, 10_000L, 250L) == QuietAudioSkipper.Verdict.CANDIDATE) {
                skipper.onDropped()
                left++
            } else {
                skipper.onPlayed(quiet, 20L)
                played++
            }
        }
        assertEquals(25, left)
        assertEquals(75, played)
    }

    @Test
    fun nothingIsLeftOutWhenRecoveryIsNotArmedOrTheOutputIsShallow() {
        val skipper = QuietAudioSkipper()
        repeat(10) { skipper.onPlayed(quiet, 20L) }
        assertEquals(QuietAudioSkipper.Verdict.PLAY, skipper.consider(quiet, 20L, 0L, 250L))
        assertEquals(QuietAudioSkipper.Verdict.PLAY, skipper.consider(quiet, 20L, 10L, 250L))
        assertEquals(QuietAudioSkipper.Verdict.PLAY, skipper.consider(quiet, 20L, 300L, 60L))
        assertEquals(QuietAudioSkipper.Verdict.PLAY, skipper.consider(quiet, 0L, 300L, 250L))
    }

    @Test
    fun aConversationRecoversOnlyInItsPausesAndNeverTouchesSpeech() {
        val clock = callClock(CallFastRecovery())
        val skipper = QuietAudioSkipper()
        var nowNs = baseNs + 3_600_000_000L
        var pts = feed(clock, 0L, nowNs)
        val excessMs = clock.addedDelayMs
        // 2 s of continuous speech, then a 2 s pause, written one 20 ms chunk at a time.
        val levels = List(100) { loud } + List(100) { quiet }
        var speechLeftOut = 0
        var leftOutMs = 0L
        var duringSpeechAddedMs = -1L
        var i = 0
        while (i < levels.size) {
            val level = levels[i]
            val verdict = skipper.consider(level, 20L, clock.fastRecoveryPendingMs(nowNs), 250L)
            val next = levels.getOrNull(i + 1)
            if (verdict == QuietAudioSkipper.Verdict.CANDIDATE && next != null && skipper.nextAllowsDrop(next) &&
                clock.consumeFastRecovery(20L, pts + 20_000L, nowNs)
            ) {
                if (level >= SilenceGatedCatchUp.SPEECH_RMS) speechLeftOut++
                skipper.onDropped()
                leftOutMs += 20L
                skipper.onPlayed(next, 20L)
                i += 2
                pts += 40_000L
            } else {
                skipper.onPlayed(level, 20L)
                i++
                pts += 20_000L
            }
            nowNs += 20_000_000L
            if (i == 100) duringSpeechAddedMs = clock.addedDelayMs
        }
        assertEquals(0, speechLeftOut)
        assertEquals("speech waits: no delay removed while talking", excessMs, duringSpeechAddedMs)
        assertTrue("the pause removes the excess ($leftOutMs of $excessMs ms)", clock.addedDelayMs < 20L)
    }

    @Test
    fun theCrossfadeContinuesTheEarlierAudioAndEndsOnTheNextChunk() {
        val dropped = ShortArray(960) { 100 }
        val next = ShortArray(960) { (-100).toShort() }
        val merged = crossfadeOverDroppedChunk(dropped, next, channels = 1, fadeFrames = 240)
        assertEquals(960, merged.size)
        assertTrue("starts where the previous chunk led", merged[0] > 95)
        assertTrue("no step inside the fade", (1 until 240).all { kotlin.math.abs(merged[it] - merged[it - 1]) <= 2 })
        assertTrue((240 until 960).all { merged[it] == next[it] })
    }
}
