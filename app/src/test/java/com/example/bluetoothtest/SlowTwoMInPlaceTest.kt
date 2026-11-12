package com.example.bluetoothtest

import com.example.bluetoothtest.SlowTwoMInPlace.Action
import com.example.bluetoothtest.SlowTwoMInPlace.LinkPhyView
import com.example.bluetoothtest.SlowTwoMInPlace.Observation
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SlowTwoMInPlaceTest {
    private val guardMs = AdaptivePhyMemory.SETUP_STALL_MS

    /** What happened on one attempt, in ms since the link opened. */
    private data class Event(val atMs: Long, val action: Action, val cause: String)

    /** One attempt as the call's watcher thread sees it: [scenario] gives the link's state at each poll. */
    private fun run(
        untilMs: Long,
        queued: Boolean = true,
        scenario: (nowMs: Long, requestedAtMs: Long?) -> Observation,
    ): List<Event> {
        val watch = SlowTwoMInPlace.Watch(guardMs)
        val events = mutableListOf<Event>()
        var requestedAt: Long? = null
        var now = SlowTwoMInPlace.POLL_MS
        while (now <= untilMs && !watch.done) {
            val observation = scenario(now, requestedAt)
            var decision = watch.decide(observation)
            if (decision.action == Action.REQUEST_1M) {
                events += Event(now, decision.action, decision.cause)
                requestedAt = now
                decision = watch.onRequest(queued, observation)
            }
            if (decision.action != Action.NONE) events += Event(now, decision.action, decision.cause)
            now += SlowTwoMInPlace.POLL_MS
        }
        return events
    }

    private fun obs(
        nowMs: Long,
        hopAt: Long? = null,
        progressAt: Long = 0L,
        phy: LinkPhyView = LinkPhyView.TWO_M,
        finished: Boolean = false,
    ) = Observation(nowMs, hopAt?.takeIf { nowMs >= it }, progressAt.coerceAtMost(nowMs), phy, finished)

    private fun List<Event>.first(action: Action): Event? = firstOrNull { it.action == action }

    @After
    fun resetSwitch() {
        PhySwitches.slow2mInPlace = false
    }

    @Test
    fun aHealthySetupIsNeverTouched() {
        // A15s -> S22 on 2M (B10): hop authentication 217 ms, end-to-end done about 0.5 s after the link opened.
        val events = run(untilMs = 10_000L) { now, _ ->
            obs(now, hopAt = 217L, progressAt = minOf(now, 500L), finished = now >= 520L)
        }
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun theSlowestCleanSetupOfThePairIsNotTouched() {
        // A15s -> ASUS 0007: hop 471 ms, end-to-end 1108 ms later, bytes every few hundred ms.
        val events = run(untilMs = 10_000L) { now, _ ->
            obs(now, hopAt = 471L, progressAt = now - now % 300L, finished = now >= 1_579L)
        }
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun aStalledHopHandshakeMovesInPlaceAndContinuesWhenOneMIsConfirmed() {
        // The stall: the peer goes quiet after the first fragment; 1M is reported 400 ms after the request, then the hop
        // handshake resumes and authenticates, and the end-to-end layer finishes: no close, no retry, no rediscovery.
        val events = run(untilMs = 10_000L) { now, requestedAt ->
            val confirmed = requestedAt != null && now >= requestedAt + 400L
            val resumedAt = (requestedAt ?: 100_000L) + 600L
            obs(
                now,
                hopAt = resumedAt + 200L,
                progressAt = if (now >= resumedAt) now else 120L,
                phy = if (confirmed) LinkPhyView.ONE_M else LinkPhyView.TWO_M,
                finished = now >= resumedAt + 500L,
            )
        }
        val request = events.first(Action.REQUEST_1M)!!
        assertEquals(SlowTwoMInPlace.HOP_CRAWL_MS, request.atMs)
        assertEquals("hop_crawl", request.cause)
        assertTrue(events.first(Action.CONFIRMED)!!.atMs in 1_900L..2_000L)
        assertNull(events.first(Action.FALLBACK_CLOSE))
        // The PHY is read back while the move is pending (the Android 13 backstop plan's first look at 200 ms).
        assertEquals(1_700L, events.first(Action.READ_PHY)!!.atMs)
    }

    @Test
    fun anUnconfirmedMoveFallsBackToTodaysCloseButNeverBeforeTheGuard() {
        val events = run(untilMs = 10_000L) { now, _ -> obs(now, progressAt = 120L) }
        val close = events.first(Action.FALLBACK_CLOSE)!!
        assertEquals("unconfirmed", close.cause)
        // Requested at 1.5 s, the 2 s confirmation bound ends at 3.5 s: after the 3 s guard, never before it.
        assertEquals(SlowTwoMInPlace.HOP_CRAWL_MS + SlowTwoMInPlace.CONFIRM_MS, close.atMs)
        assertTrue(close.atMs >= guardMs)
    }

    @Test
    fun aConfirmedMoveWithoutProgressFallsBackAfterTheGuard() {
        val events = run(untilMs = 10_000L) { now, requestedAt ->
            obs(now, progressAt = 120L, phy = if (requestedAt != null && now >= requestedAt + 300L) LinkPhyView.ONE_M else LinkPhyView.TWO_M)
        }
        assertEquals(1_800L, events.first(Action.CONFIRMED)!!.atMs)
        val close = events.first(Action.FALLBACK_CLOSE)!!
        assertEquals("no_progress", close.cause)
        assertEquals(1_800L + SlowTwoMInPlace.PROGRESS_AFTER_MOVE_MS, close.atMs)
    }

    @Test
    fun aConfirmedMoveThatTricklesButNeverAuthenticatesIsBounded() {
        val events = run(untilMs = 20_000L) { now, requestedAt ->
            obs(now, progressAt = now - now % 500L, phy = if (requestedAt != null) LinkPhyView.ONE_M else LinkPhyView.TWO_M)
        }
        val close = events.first(Action.FALLBACK_CLOSE)!!
        assertEquals("no_auth", close.cause)
        assertEquals(guardMs + SlowTwoMInPlace.EXTENSION_MS, close.atMs)
    }

    @Test
    fun aCrawlAfterHopAuthenticationMovesButNeverCloses() {
        // Call 11 shape: hop authenticated at 1363 ms, then the end-to-end layer trickles one record a second.
        val events = run(untilMs = 20_000L) { now, _ ->
            obs(now, hopAt = 1_363L, progressAt = now - now % 1_000L)
        }
        val request = events.first(Action.REQUEST_1M)!!
        assertEquals("endpoint_crawl", request.cause)
        assertEquals(3_400L, request.atMs)
        assertNull(events.first(Action.FALLBACK_CLOSE))
        assertEquals("unconfirmed", events.first(Action.UNRESOLVED)!!.cause)
    }

    @Test
    fun peerSilenceAfterHopAuthenticationCountsAsACrawl() {
        val events = run(untilMs = 5_000L) { now, _ -> obs(now, hopAt = 300L, progressAt = 400L) }
        val request = events.first(Action.REQUEST_1M)!!
        assertEquals("no_progress", request.cause)
        assertEquals(1_900L, request.atMs)
    }

    @Test
    fun aRefusedRequestKeepsTodaysGuardExactly() {
        val events = run(untilMs = 10_000L, queued = false) { now, _ -> obs(now, progressAt = 120L) }
        assertEquals(1_500L, events.first(Action.REQUEST_1M)!!.atMs)
        val close = events.first(Action.FALLBACK_CLOSE)!!
        assertEquals("not_queued", close.cause)
        assertEquals(guardMs, close.atMs)
    }

    @Test
    fun aLinkTheStackAlreadyReportsOnOneMIsNotMovedAndKeepsTheGuard() {
        val events = run(untilMs = 10_000L) { now, _ -> obs(now, progressAt = 120L, phy = LinkPhyView.ONE_M) }
        assertNull(events.first(Action.REQUEST_1M))
        assertEquals(Action.NOT_TWO_M, events.first().action)
        assertEquals(guardMs, events.first(Action.FALLBACK_CLOSE)!!.atMs)
    }

    @Test
    fun aSetupThatFinishesWhileMovingStopsTheWatch() {
        val events = run(untilMs = 10_000L) { now, _ -> obs(now, progressAt = 120L, finished = now >= 1_800L) }
        assertEquals(listOf(Action.REQUEST_1M, Action.READ_PHY), events.map { it.action })
    }

    @Test
    fun theSummaryShowsOneMOnlyWhenBothDirectionsReportIt() {
        assertEquals(LinkPhyView.ONE_M, LinkPhyView.of("PHY TX 1M / RX 1M"))
        assertEquals(LinkPhyView.TWO_M, LinkPhyView.of("PHY TX 2M / RX 2M"))
        assertEquals(LinkPhyView.TWO_M, LinkPhyView.of("PHY TX 1M / RX 2M"))
        assertEquals(LinkPhyView.OTHER, LinkPhyView.of("PHY pending"))
        assertEquals(LinkPhyView.OTHER, LinkPhyView.of(""))
    }

    @Test
    fun onlyAnUnrecognisedDirectSymmetricTwoMDialIsWatched() {
        fun applies(
            enabled: Boolean = true,
            setupPhy: BlePhyPreference = BlePhyPreference.TWO_M,
            le: Boolean = true,
            direct: Boolean = true,
            hasMemoryKey: Boolean = false,
            splitApplied: Boolean = false,
            blePhyArm: Boolean = false,
            staticSplit: Boolean = false,
        ) = SlowTwoMInPlace.applies(enabled, setupPhy, le, direct, hasMemoryKey, splitApplied, blePhyArm, staticSplit)
        assertTrue(applies())
        assertFalse("switch off: unchanged", applies(enabled = false))
        assertFalse("recognised peer: unchanged", applies(hasMemoryKey = true))
        assertFalse("remembered 1M", applies(setupPhy = BlePhyPreference.ONE_M_ADAPTIVE))
        assertFalse("relayed offer", applies(direct = false))
        assertFalse("Classic or Wi-Fi", applies(le = false))
        assertFalse(applies(splitApplied = true))
        assertFalse(applies(blePhyArm = true))
        assertFalse(applies(staticSplit = true))
    }

    @Test
    fun theLaunchExtraTurnsTheSwitchOnInDebugBuildsOnly() {
        PhySwitches.applyFlags { it == "test_slow_2m_in_place" }
        // DEBUG builds follow the extra; a release build keeps the Experimental arm off whatever it is given.
        assertEquals(BuildConfig.DEBUG, PhySwitches.slow2mInPlace)
        PhySwitches.applyFlags { false }
        assertFalse(PhySwitches.slow2mInPlace)
    }

    @Test
    fun theRetryScanCoversTheCalleesIncomingDeadline() {
        val gattBase = StallRediscover.budgetMs(StallRediscover.DEFAULT_MS, true, 10_000L, true, StallRediscover.CALLEE_GATT_RESTART_MS)
        val cocBase = StallRediscover.budgetMs(StallRediscover.DEFAULT_MS, true, 10_000L, true, 0L)
        assertEquals(18_500L, gattBase)
        assertEquals(17_000L, cocBase)
        // A close at the guard: the callee is released by its 20 s deadline 17 s later, then restarts and advertises.
        assertEquals(20_500L, SlowTwoMInPlace.rediscoverBudgetMs(gattBase, 3_000L, StallRediscover.CALLEE_GATT_RESTART_MS))
        assertEquals(19_000L, SlowTwoMInPlace.rediscoverBudgetMs(cocBase, 3_000L, 0L))
        // A later close leaves less of the callee's deadline: never shorter than today's window.
        assertEquals(gattBase, SlowTwoMInPlace.rediscoverBudgetMs(gattBase, 6_000L, StallRediscover.CALLEE_GATT_RESTART_MS))
        // Bounded, and unchanged without an in-place close.
        assertEquals(SlowTwoMInPlace.WINDOW_CAP_MS, SlowTwoMInPlace.rediscoverBudgetMs(gattBase, 0L, StallRediscover.CALLEE_GATT_RESTART_MS))
        assertEquals(gattBase, SlowTwoMInPlace.rediscoverBudgetMs(gattBase, null, StallRediscover.CALLEE_GATT_RESTART_MS))
        // The window covers the callee: its release (20 s after accept, at or before our open) + restart + advertisement reach.
        val closeAt = 3_000L
        val advertReachedBy = DualSetupBudget.INCOMING_HANDSHAKE_MS + StallRediscover.CALLEE_GATT_RESTART_MS + StallRediscover.SCAN_SLACK_MS
        assertTrue(closeAt + SlowTwoMInPlace.rediscoverBudgetMs(gattBase, closeAt, StallRediscover.CALLEE_GATT_RESTART_MS) >= advertReachedBy)
        assertTrue("today's window ends first", closeAt + gattBase < advertReachedBy)
    }

    @Test
    fun theProgressStreamPassesBytesUnchangedAndNotesArrivals() {
        var clock = 1_000L
        val progress = SlowTwoMInPlace.Progress { clock }
        val bytes = ByteArray(300) { it.toByte() }
        val input = SlowTwoMInPlace.ProgressInputStream(ByteArrayInputStream(bytes), progress)
        assertEquals(0L, progress.sinceOpen(900L))
        clock = 1_250L
        val first = ByteArray(200)
        assertEquals(200, input.read(first))
        assertEquals(350L, progress.sinceOpen(900L))
        clock = 1_400L
        assertEquals(200, input.read())
        val rest = input.readBytes()
        assertArrayEquals(bytes, first + byteArrayOf(200.toByte()) + rest)
        assertEquals(-1, input.read())
        assertEquals(1_400L, progress.lastAtMs)
    }
}
