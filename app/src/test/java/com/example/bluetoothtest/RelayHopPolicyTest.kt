package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Relay-showcase fixes: the helper's one fast connect retry, the caller's helper rescan rule and the attribution lines. */
class RelayHopPolicyTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val target = bytes("ed12a4dc1137")
    private val helper = bytes("10936433949f")
    private val other = bytes("aea8904b0bcb")

    private fun callOffer(prefix: ByteArray, cost: Int = 0, version: Int = DiscoveryAnnouncement.ROTATING_VERSION) =
        DiscoveryAnnouncement(
            prefix,
            DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
                DiscoveryAnnouncement.FLAG_RELAY_CAPABLE,
            NodeCapabilities.CODEC_VP9, 0, cost, 0, version = version,
        )

    /** A fake clock the connect attempts advance; sleeps advance it too. */
    private class Clock(var nowMs: Long = 0L) {
        fun ns() = nowMs * 1_000_000L
    }

    private fun run(
        clock: Clock,
        attempts: List<() -> String>,
        remainingMs: Int = 16_000,
        enabled: Boolean = true,
        stopped: () -> Boolean = { false },
        log: MutableList<String> = mutableListOf(),
        closed: MutableList<Int> = mutableListOf(),
        seen: MutableList<Int> = mutableListOf(),
    ): String {
        val startedNs = clock.ns()
        var index = 0
        return RelayHopPolicy.connectWithOneRetry(
            enabled = enabled,
            startedNs = startedNs,
            remainingMs = remainingMs,
            nowNs = clock::ns,
            stopped = stopped,
            sleep = { clock.nowMs += it },
            log = { log += it },
            debug = true,
            beforeRetry = { closed += index },
            onAttempt = { seen += it },
        ) { attempts[index++]() }
    }

    @Test
    fun aFastConnectFailureIsRetriedOnceAndTheSecondLinkIsUsed() {
        val clock = Clock()
        val log = mutableListOf<String>()
        val closed = mutableListOf<Int>()
        val seen = mutableListOf<Int>()
        val link = run(
            clock,
            listOf(
                { clock.nowMs += 341; throw IOException("read failed, socket might closed or timeout, read ret: -1") },
                { clock.nowMs += 900; "link" },
            ),
            log = log, closed = closed, seen = seen,
        )
        assertEquals("link", link)
        assertEquals(listOf(1, 2), seen)
        assertEquals("the failed attempt's control link is closed before the retry", 1, closed.size)
        assertEquals(1, log.size)
        assertTrue(log.single().startsWith("BENCHMARK relayed_hop: role=helper | event=connect_retry | attempt=2 | failedAfterMs=341"))
    }

    @Test
    fun aSecondFailureEndsTheSetupWithItsOwnError() {
        val clock = Clock()
        val seen = mutableListOf<Int>()
        try {
            run(
                clock,
                listOf(
                    { clock.nowMs += 300; throw IOException("first") },
                    { clock.nowMs += 300; throw IOException("second") },
                ),
                seen = seen,
            )
            fail("expected the second failure")
        } catch (error: IOException) {
            assertEquals("second", error.message)
        }
        assertEquals(listOf(1, 2), seen)
    }

    @Test
    fun aSlowFailureFormedALinkAndIsNotRepeated() {
        // A connect that failed after the link existed (supervision timeout, EOF in the stack) is not the same request again.
        val clock = Clock()
        val seen = mutableListOf<Int>()
        try {
            run(clock, listOf({ clock.nowMs += 4_026; throw IOException("EOF") }, { "never" }), seen = seen)
            fail("expected the failure")
        } catch (error: IOException) {
            assertEquals("EOF", error.message)
        }
        assertEquals(listOf(1), seen)
    }

    @Test
    fun noRetryWithoutTimeLeftWhenSwitchedOffOrWhenTheDutyWasClosed() {
        for ((remaining, enabled, stopped) in listOf(Triple(5_000, true, false), Triple(16_000, false, false), Triple(16_000, true, true))) {
            val clock = Clock()
            val seen = mutableListOf<Int>()
            // The duty is closed while its first connect is failing (SETUP expiry or the endpoint's close).
            var closed = false
            try {
                run(
                    clock,
                    listOf({ clock.nowMs += 200; closed = stopped; throw IOException("fast") }, { "never" }),
                    remainingMs = remaining, enabled = enabled, stopped = { closed }, seen = seen,
                )
                fail("expected the failure")
            } catch (_: IOException) {
            }
            assertEquals(listOf(1), seen)
        }
    }

    @Test
    fun theCallerRetryFindsOnlyTheSelectedHelpersOwnOffer() {
        val route = RelayRoute(target, helper, 1, rotating = true)
        val version = DiscoveryAnnouncement.ROTATING_VERSION
        assertTrue(RelayHopPolicy.helperRetryMatches(route, version, callOffer(helper)))
        // The routed row (target at cost 1) is advertised by nobody on an on-demand route; the target's own offer is not the hop.
        assertFalse(RelayHopPolicy.helperRetryMatches(route, version, callOffer(target, cost = 1)))
        assertFalse(RelayHopPolicy.helperRetryMatches(route, version, callOffer(target)))
        // Another helper is never swapped in for the selected one, nor a fixed-prefix advert with the same bytes.
        assertFalse(RelayHopPolicy.helperRetryMatches(route, version, callOffer(other)))
        assertFalse(RelayHopPolicy.helperRetryMatches(route, version, callOffer(helper, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION)))
        assertFalse(RelayHopPolicy.helperRetryMatches(route, version, callOffer(helper, cost = 1)))
    }

    @Test
    fun aFixedPrefixRouteMatchesTheHelpersFixedPrefixOffer() {
        val route = RelayRoute(target, helper, 1, rotating = false)
        val version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION
        assertTrue(RelayHopPolicy.helperRetryMatches(route, version, callOffer(helper, version = version)))
        assertFalse(RelayHopPolicy.helperRetryMatches(route, version, callOffer(helper)))
    }

    @Test
    fun theCallerLineNamesTheSelectedHelperAndAReleaseLineCarriesNoAddress() {
        val route = RelayRoute(target, helper, 1, rotating = true)
        val debug = RelayHopPolicy.callerRouteLine(route, "64:2E:57:9F:CB:D5", 1, debug = true)
        assertEquals(
            "BENCHMARK relayed_route: role=caller | target=ed12a4dc1137 | helper=10936433949f | id=rdi | budget=1 | " +
                "addressType=random | hopAddress=...CB:D5",
            debug,
        )
        val release = RelayHopPolicy.callerRouteLine(route, "64:2E:57:9F:CB:D5", null, debug = false)
        assertTrue(release.startsWith("relayed_route: role=caller | target=ed12a4dc1137 | helper=10936433949f"))
        assertFalse(release.contains("CB:D5"))
        assertFalse(release.contains("64:2E"))
        assertTrue(release.contains("addressType=unknown"))
    }

    @Test
    fun theHelperNamesItsNextHopAndTheStageAHopFailedIn() {
        assertEquals("target:ed12a4dc1137", RelayHopPolicy.helperNextHop(0, target, null))
        assertEquals("helper:aea8904b0bcb", RelayHopPolicy.helperNextHop(1, target, other))
        assertEquals(
            "BENCHMARK relayed_hop: role=helper | event=failed | stage=authenticating | error=EOFException | elapsedMs=13646 | attempts=1",
            RelayHopPolicy.hopFailureLine("AUTHENTICATING", java.io.EOFException(), 13_646, 1, debug = true),
        )
    }
    // ----- candidate iteration inside one call attempt -----

    @Test
    fun aFailedRouteHandsOverOnlyBeforeReadyWhileDialingAndInsideTheWindow() {
        val window = RelayedCalls.CANDIDATE_WINDOW_MS - RelayedCalls.SETUP_DEADLINE_MS
        assertTrue(RelayCandidatePolicy.mayTryNext(true, attempts = 1, elapsedMs = 15_000, readyReached = false, stillDialing = true))
        assertTrue(RelayCandidatePolicy.mayTryNext(true, attempts = 2, elapsedMs = window, readyReached = false, stillDialing = true))
        // READY was reached: the failure belongs to the call (end-to-end handshake, link loss), never to the route choice.
        assertFalse(RelayCandidatePolicy.mayTryNext(true, 1, 15_000, readyReached = true, stillDialing = true))
        // The user cancelled, or the callee already answered something: no silent redial.
        assertFalse(RelayCandidatePolicy.mayTryNext(true, 1, 15_000, readyReached = false, stillDialing = false))
        assertFalse(RelayCandidatePolicy.mayTryNext(true, RelayedCalls.MAX_CANDIDATES, 15_000, false, true))
        assertFalse(RelayCandidatePolicy.mayTryNext(true, 1, window + 1, false, true))
        assertFalse(RelayCandidatePolicy.mayTryNext(false, 1, 15_000, false, true))
    }

    @Test
    fun theNextCandidateIsTheTablesNextRouteThroughAnotherHelper() {
        var now = 1_000L
        val helper2 = bytes("aea8904b0bcb")
        val callerPrefix = bytes("9f3b7592586c")
        // The caller cannot hear the target (the relay-showcase no_direct topology); two helpers page it at cost 1.
        val table = ReachTable<String>(callerPrefix, ReachConfig(), { now }, kotlin.random.Random(1), directSightings = { false })
        fun hear() {
            for ((name, prefix) in listOf("h1" to helper, "h2" to helper2)) {
                table.observeOffer(name, callOffer(prefix, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION), "AA:$name", rssi = if (name == "h1") -50 else -70)
                table.observePage(ReachPage(prefix, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 1, 0, target.copyOf(2)))), "BB:$name")
            }
        }
        hear()
        now += 2_500
        hear()
        val first = table.callerRoutes(target, requireRelays = 1).first()
        assertEquals("the stronger first hop is tried first", "h1", first.hop.peer)
        val firstRoute = RelayRoute(target, first.nextHop!!, first.relays)
        val candidates = RelayCandidates(firstRoute, startedMs = now)

        // The first route failed before READY: the caller marks the pair, and the table's best route is now the other helper.
        table.markFailed(first.nextHop, target, noRoute = false)
        val second = table.callerRoutes(target, requireRelays = 1).first()
        val secondRoute = RelayRoute(target, second.nextHop!!, second.relays)
        assertEquals("h2", second.hop.peer)
        assertEquals("exactly one relay, as require=1 asked", 1, second.relays)
        assertTrue(RelayCandidatePolicy.acceptable(firstRoute, secondRoute, candidates.triedHelpers))
        candidates.tried(secondRoute.helperPrefix)
        assertEquals(2, candidates.attempts)
        // A helper already tried is never taken again in this attempt, nor a route to another target.
        assertFalse(RelayCandidatePolicy.acceptable(firstRoute, firstRoute, candidates.triedHelpers))
        assertFalse(RelayCandidatePolicy.acceptable(firstRoute, RelayRoute(other, bytes("0102030405ff"), 1), emptyList()))
        assertTrue(
            RelayCandidatePolicy.nextLine(2, secondRoute, 18_000, java.io.EOFException(), debug = true)
                .startsWith("BENCHMARK relayed_route: role=caller | attempt=2 | helper=aea8904b0bcb | target=ed12a4dc1137"),
        )
    }
    // ----- per-link 2M -> 1M retry of a helper's next hop -----

    @Test
    fun aStalledHopIsClosedOnceAndTheAuthenticationThatFinishesFirstWins() {
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val stalled = RelayHopPolicy.HopSetupStallGuard(50) { closed.incrementAndGet() }
        stalled.start()
        Thread.sleep(400)
        assertFalse("the guard already closed the hop: a late authentication must not be used", stalled.settle())
        assertTrue(stalled.stalled)
        assertEquals(1, closed.get())

        val inTime = RelayHopPolicy.HopSetupStallGuard(5_000) { closed.incrementAndGet() }
        inTime.start()
        assertTrue(inTime.settle())
        Thread.sleep(100)
        assertFalse(inTime.stalled)
        assertEquals("a settled hop is never closed by its guard", 1, closed.get())
    }

    @Test
    fun onlyAFirstAttemptOn2mIsRetriedOn1mAndTheRescanLeavesRoomForTheRetry() {
        assertTrue(RelayHopPolicy.phyRetryEligible(enabled = true, firstOnTwoM = true))
        // Already on 1M (remembered bad pair, or the forcing switch test_relayed_hop_1m): nothing to fall back to.
        assertFalse(RelayHopPolicy.phyRetryEligible(enabled = true, firstOnTwoM = false))
        assertFalse(RelayHopPolicy.phyRetryEligible(enabled = false, firstOnTwoM = true))
        assertEquals(RelayHopPolicy.RESCAN_MAX_MS, RelayHopPolicy.rescanBudgetMs(16_000))
        assertEquals(1_000L, RelayHopPolicy.rescanBudgetMs(RelayHopPolicy.RETRY_MIN_LEFT_MS + 1_000))
        assertEquals(0L, RelayHopPolicy.rescanBudgetMs(3_000))
        assertEquals(
            "BENCHMARK adaptive_phy: link=call-relay | node=ed12a4dc1137 | from=2M to=1M | reason=setup_stall | attempt=2 | " +
                "leftMs=12000 | error=IOException",
            RelayHopPolicy.phyRetryLine("ed12a4dc1137", stalled = true, leftMs = 12_000, error = IOException("x"), debug = true),
        )
    }
    // ----- review P2: a next-hop connect must not outlive the SETUP deadline -----

    @Test
    fun aRetryThatConnectsAfterTheSetupEndedIsClosedAndNeverHandedToTheHandshake() {
        val clock = Clock()
        var stopped = false
        val discarded = mutableListOf<String>()
        try {
            RelayHopPolicy.connectWithOneRetry(
                enabled = true, startedNs = 0L, remainingMs = 7_000, nowNs = clock::ns, stopped = { stopped },
                sleep = { clock.nowMs += it }, log = {}, debug = true, beforeRetry = {}, onAttempt = {},
                discard = { discarded += it },
            ) {
                if (discarded.isEmpty() && clock.nowMs == 0L) {
                    clock.nowMs += 300
                    throw IOException("read failed")
                }
                // The retry's connect outlives the SETUP deadline (expiry closed the duty while it was connecting).
                clock.nowMs += 12_000
                stopped = true
                "late-link"
            }
            fail("a link that connected after the deadline must not be returned")
        } catch (error: IOException) {
            assertEquals(RelayHopPolicy.STOPPED_MESSAGE, error.message)
        }
        assertEquals(listOf("late-link"), discarded)
    }

    @Test
    fun noConnectStartsOnceTheDutyIsStopped() {
        var connects = 0
        try {
            RelayHopPolicy.connectWithOneRetry(
                enabled = true, startedNs = 0L, remainingMs = 16_000, nowNs = { 0L }, stopped = { true },
                sleep = {}, log = {}, debug = true, beforeRetry = {}, onAttempt = {},
            ) { connects++; "link" }
            fail("expected the stop")
        } catch (error: IOException) {
            assertEquals(RelayHopPolicy.STOPPED_MESSAGE, error.message)
        }
        assertEquals(0, connects)
    }

    @Test
    fun aStaticRelayChildOn2mRetriesOnceOnlyBehindItsSwitch() {
        // AP7 F3: the DEBUG static relay (test_private_call_relay) gets the on-demand hop's one 1M retry.
        assertTrue(RelayHopPolicy.startHopRetry(enabled = true, firstOnTwoM = true, retried = false))
        assertFalse(RelayHopPolicy.startHopRetry(enabled = false, firstOnTwoM = true, retried = false))
        assertFalse(RelayHopPolicy.startHopRetry(enabled = true, firstOnTwoM = false, retried = false))
        assertFalse(RelayHopPolicy.startHopRetry(enabled = true, firstOnTwoM = true, retried = true))
    }

    @Test
    fun theStaticRelayRetryKeepsTheOnDemandHopGuardTiming() {
        assertEquals(AdaptivePhyMemory.SETUP_STALL_MS, RelayHopPolicy.HOP_SETUP_STALL_MS)
        assertTrue(RelayHopPolicy.HOP_SETUP_STALL_MS > 0L)
    }
}
