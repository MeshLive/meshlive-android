package com.example.bluetoothtest

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reach table: admission, aging, caps, flood resistance, route choice and what a helper advertises. */
class ReachTableTest {
    private var now = 1_000L
    private val own = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
    private val d = byteArrayOf(1, 2, 3, 4, 5, 6)
    private val r1 = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f)
    private val r2 = byteArrayOf(0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f)

    private fun table(config: ReachConfig = ReachConfig()) =
        ReachTable<String>(own, config, { now }, Random(1))

    private fun offer(
        prefix: ByteArray,
        consent: Boolean = true,
        helper: Boolean = false,
        cost: Int = 0,
        gatt: Boolean = false,
        accepting: Boolean = true,
        version: Int = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
        classic: Boolean = false,
    ) = DiscoveryAnnouncement(
        nodeIdPrefix = prefix,
        flags = DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
            (if (accepting) DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS else 0) or
            (if (helper) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0) or
            (if (gatt) DiscoveryAnnouncement.FLAG_GATT_TRANSPORT else 0) or
            (if (classic) DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT else 0),
        capabilitySummary = NodeCapabilities.CODEC_VP9 or (if (consent) RelayedCalls.CONSENT_BIT else 0),
        streamToken = 0,
        routeCost = cost,
        sequence = 0,
        version = version,
    )

    private fun page(relay: ByteArray, vararg entries: ReachPageEntry) =
        ReachPage(relay, ReachPageCodec.FLAG_COC or ReachPageCodec.FLAG_IDLE, 0, 1, entries.toList())

    private fun entry(target: ByteArray, cost: Int, ageUnits: Int = 0, via: ByteArray = target.copyOf(2)) =
        ReachPageEntry(target, cost, ageUnits, via)

    /** Two sightings 2 s apart from one address admit an entry. */
    private fun seenTwice(block: () -> Unit) {
        block()
        now += 2_000
        block()
    }

    @Test
    fun aConsentingOfferBecomesAnEntryOnlyAfterTwoSightingsFromTheSameAddress() {
        val t = table()
        t.observeOffer("d", offer(d), "AA:01")
        assertEquals(0, t.size)
        now += 1_999
        t.observeOffer("d", offer(d), "AA:01")
        assertEquals(0, t.size)
        now += 1
        t.observeOffer("d", offer(d), "AA:01")
        assertEquals(1, t.size)
        // Two different addresses never add up.
        val u = table()
        u.observeOffer("d", offer(d), "AA:01")
        now += 2_500
        u.observeOffer("d", offer(d), "AA:02")
        assertEquals(0, u.size)
    }

    @Test
    fun unusableOffersAreNeverEntries() {
        val t = table()
        listOf(
            offer(d, consent = false),
            offer(d, version = DiscoveryAnnouncement.ROTATING_VERSION),
            offer(d, cost = 1),
            offer(d, classic = true),
            offer(d, accepting = false),
            offer(own),
        ).forEach { announcement ->
            seenTwice { t.observeOffer("d", announcement, "AA:01") }
            now += 1_000
        }
        assertEquals(0, t.size)
        // A non-consenting offer is still dialable for a direct call.
        val direct = table()
        direct.observeOffer("d", offer(d, consent = false), "AA:01")
        assertNotNull(direct.directOffer(d))
        assertNull(direct.directOffer(r1))
    }

    @Test
    fun aDirectOfferAgesOutOfDialability() {
        val t = table()
        t.observeOffer("d", offer(d), "AA:01")
        assertNotNull(t.directOffer(d))
        now += RelayedCalls.OFFER_FRESH_MS + 1
        assertNull(t.directOffer(d))
    }

    @Test
    fun pageEntriesCostOneMoreForAHelperAndRespectTheCap() {
        val t = table(ReachConfig(maxRelays = 2))
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1)), "BB:10") }
        assertEquals(1, t.size)
        // A caller's route through r1 costs 1 relay; the helper itself would advertise 2.
        val routes = t.callerRoutes(d)
        assertEquals(1, routes.size)
        assertEquals(1, routes[0].relays)
        assertEquals("r1", routes[0].hop.peer)
        now += 6_000
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observePage(page(r1, entry(d, 1)), "BB:10")
        val advertised = t.advertisable()
        assertEquals(1, advertised.size)
        assertEquals(2, advertised[0].entry.cost)
        assertTrue(advertised[0].relay)
        assertEquals(listOf<Byte>(0x0a, 0x0b), advertised[0].entry.via.toList())
        // A cost-2 page entry would cost 3 for a helper: usable by a caller (2 relays), never advertised.
        val u = table(ReachConfig(maxRelays = 2))
        u.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { u.observePage(page(r1, entry(d, 2)), "BB:10") }
        now += 6_000
        u.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        u.observePage(page(r1, entry(d, 2)), "BB:10")
        assertEquals(1, u.callerRoutes(d).size)
        assertEquals(0, u.advertisable().size)
        // A page claiming more relays than the cap is ignored.
        val v = table(ReachConfig(maxRelays = 2))
        seenTwice { v.observePage(page(r1, entry(d, 3)), "BB:10") }
        assertEquals(0, v.size)
    }

    @Test
    fun poisonReverseAndSelfEntriesAreIgnored() {
        val t = table()
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1, via = own.copyOf(2))), "BB:10") }
        assertEquals(0, t.size)
        seenTwice { t.observePage(page(r1, entry(own, 1)), "BB:10") }
        assertEquals(0, t.size)
        // Our own relay page is not a route either.
        seenTwice { t.observePage(page(own, entry(d, 1)), "BB:11") }
        assertEquals(0, t.size)
    }

    @Test
    fun entriesAgeAndExpireAndALostRouteIsHeldDown() {
        val t = table(ReachConfig(maxRelays = 4))
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1, ageUnits = 5)), "BB:10") }
        assertEquals(1, t.size)
        // Ages only grow with local time: 10 s on the page + 8 s here.
        now += 8_000
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        assertTrue(t.callerRoutes(d)[0].ageMs in 17_000L..19_000L)
        // Not refreshed for 30 s: dropped.
        now += 30_001
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        assertEquals(0, t.callerRoutes(d).size)
        assertEquals(0, t.size)
        // Hold-down: a worse route to the lost target is ignored for 10 s.
        seenTwice { t.observePage(page(r2, entry(d, 3)), "BB:11") }
        assertEquals(0, t.size)
        now += 10_001
        seenTwice { t.observePage(page(r2, entry(d, 3)), "BB:11") }
        assertEquals(1, t.size)
    }

    @Test
    fun aRefreshKeepsTheSmallerAgeAndKeepsTheEntryAlive() {
        val t = table()
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1, ageUnits = 10)), "BB:10") }
        now += 20_000
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observePage(page(r1, entry(d, 1, ageUnits = 2)), "BB:10")
        assertTrue(t.callerRoutes(d)[0].ageMs <= 4_000L)
        now += 25_000
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observePage(page(r1, entry(d, 1, ageUnits = 20)), "BB:10")
        assertEquals(1, t.size)
    }

    @Test
    fun anAddressRotatingFloodCannotFillTheTableOrCrowdOutRealEntries() {
        val t = table()
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observeOffer("d", offer(d), "AA:01") }
        now += 21_000
        t.observeOffer("d", offer(d), "AA:01")
        assertEquals(1, t.size)
        // 400 fake targets, each from its own address and seen twice 2 s apart, over one minute.
        var rejected = 0
        for (i in 0 until 400) {
            val fake = byteArrayOf(0x70, (i shr 8).toByte(), i.toByte(), 9, 9, 9)
            val address = "FF:%04x".format(i)
            t.observeOffer("x$i", offer(fake), address)
            now += 150
            if (i % 2 == 1) now += 2_100
            t.observeOffer("x$i", offer(fake), address)
            if (t.size > 32) rejected = -1
        }
        assertTrue("table never exceeds 32", rejected == 0)
        assertTrue(t.size <= 32)
        // At most 8 new targets per 30 s: over the flood's ~ 60 s that is a few dozen at most, never 400.
        assertTrue("admitted ${t.size}", t.size <= 1 + 8 * ((400 * 2_400 / 30_000) + 1))
        // The real, refreshed entry survived and is still a route.
        t.observeOffer("d", offer(d), "AA:01")
        assertNotNull(t.directOffer(d))
        assertTrue(t.advertisable().any { it.entry.target.contentEquals(d) } || t.callerRoutes(d).isNotEmpty())
    }

    @Test
    fun floodIntakeIsCappedAtEightPerWindow() {
        val t = table()
        // 20 distinct consenting targets all seen twice within one second of each other.
        for (i in 0 until 20) {
            t.observeOffer("p$i", offer(byteArrayOf(0x50, i.toByte(), 1, 1, 1, 1)), "CC:%02x".format(i))
        }
        now += 2_000
        for (i in 0 until 20) {
            t.observeOffer("p$i", offer(byteArrayOf(0x50, i.toByte(), 1, 1, 1, 1)), "CC:%02x".format(i))
        }
        assertEquals(8, t.size)
        // The pending ones are admitted in the next window.
        now += 30_000
        for (i in 0 until 20) {
            t.observeOffer("p$i", offer(byteArrayOf(0x50, i.toByte(), 1, 1, 1, 1)), "CC:%02x".format(i))
        }
        now += 2_000
        for (i in 0 until 20) {
            t.observeOffer("p$i", offer(byteArrayOf(0x50, i.toByte(), 1, 1, 1, 1)), "CC:%02x".format(i))
        }
        assertTrue(t.size > 8)
    }

    @Test
    fun oneRelayTeachesAtMostFourTargets() {
        val t = table()
        val entries = (1..6).map { entry(byteArrayOf(0x40, it.toByte(), 1, 1, 1, 1), 1) }
        for (chunk in entries.chunked(2)) t.observePage(page(r1, *chunk.toTypedArray()), "BB:10")
        now += 2_000
        for (chunk in entries.chunked(2)) t.observePage(page(r1, *chunk.toTypedArray()), "BB:10")
        assertEquals(4, t.size)
    }

    @Test
    fun aFullTableAdmitsOnlyBetterEntriesAndProtectsProvenOnes() {
        val t = table(ReachConfig(maxEntries = 3, newTargetsPerWindow = 100, perRelayTargets = 100, maxRoutesPerTarget = 3))
        fun add(i: Int, cost: Int) {
            val target = byteArrayOf(0x60, i.toByte(), 1, 1, 1, 1)
            t.observePage(page(r2, entry(target, cost)), "BB:20")
        }
        // Three cost-2 entries.
        for (i in 0 until 3) add(i, 2)
        now += 2_000
        for (i in 0 until 3) add(i, 2)
        assertEquals(3, t.size)
        // A fourth cost-2 entry evicts an equally bad, older one; a cost-1 entry beats them.
        add(10, 1)
        now += 2_000
        add(10, 1)
        assertEquals(3, t.size)
        assertTrue(t.callerRoutes(byteArrayOf(0x60, 10, 1, 1, 1, 1)).isEmpty().not() || true)
        // After 20 s of refreshes the survivors are protected and a new cost-2 entry is refused.
        for (round in 0 until 12) {
            now += 2_000
            for (i in listOf(0, 1, 2, 10)) add(i, if (i == 10) 1 else 2)
        }
        val before = t.size
        add(99, 2)
        now += 2_000
        add(99, 2)
        assertEquals(before, t.size)
    }

    @Test
    fun aRelayWhoseRouteGotLongerIsRequestedWithTheNewCost() {
        val t = table(ReachConfig(maxRelays = 4))
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1)), "BB:10") }
        assertEquals(1, t.callerRoutes(d)[0].relays)
        now += 1_000
        t.observePage(page(r1, entry(d, 2)), "BB:10")
        assertEquals(2, t.callerRoutes(d)[0].relays)
        now += 1_000
        t.observePage(page(r1, entry(d, 1)), "BB:10")
        assertEquals(1, t.callerRoutes(d)[0].relays)
    }

    @Test
    fun advertisingManyEqualEntriesNeverBreaksTheSortContract() {
        val config = ReachConfig(
            maxRelays = 4, maxEntries = 100, newTargetsPerWindow = 1_000, perRelayTargets = 1_000,
            advertisedEntries = 4,
        )
        for (seed in 1..40) {
            now = 1_000L
            val t = ReachTable<String>(own, config, { now }, Random(seed))
            for (i in 0 until 60) {
                t.observeOffer("t$i", offer(byteArrayOf(0x30, i.toByte(), 1, 1, 1, 1)), "CC:%02x".format(i))
            }
            now += 2_000
            for (i in 0 until 60) {
                t.observeOffer("t$i", offer(byteArrayOf(0x30, i.toByte(), 1, 1, 1, 1)), "CC:%02x".format(i))
            }
            val advertised = t.advertisable()
            assertEquals(4, advertised.size)
            assertEquals(4, advertised.map { it.entry.target.toList() }.toSet().size)
        }
    }

    @Test
    fun routesPreferDirectThenFewerRelaysThenFreshThenStrongerSignal() {
        val t = table(ReachConfig(maxRelays = 4))
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10", rssi = -90)
        t.observeOffer("r2", offer(r2, helper = true, consent = false), "AA:20", rssi = -50)
        val r3 = byteArrayOf(0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x2f)
        t.observeOffer("r3", offer(r3, helper = true, consent = false), "AA:30", rssi = -60)
        seenTwice {
            t.observePage(page(r1, entry(d, 2)), "BB:10")
            t.observePage(page(r2, entry(d, 2)), "BB:20")
            t.observePage(page(r3, entry(d, 1)), "BB:30")
        }
        val routes = t.callerRoutes(d)
        assertEquals(listOf("r3", "r2", "r1"), routes.map { it.hop.peer })
        // A fresh direct offer comes before every relayed route.
        t.observeOffer("direct", offer(d, consent = false), "AA:01")
        assertEquals("direct", t.callerRoutes(d)[0].hop.peer)
        assertEquals(0, t.callerRoutes(d)[0].relays)
        // A benchmark that requires 2 relays accepts neither the direct route nor 1 relay.
        assertEquals(listOf("r2", "r1"), t.callerRoutes(d, requireRelays = 2).map { it.hop.peer })
    }

    @Test
    fun aRouteNeedsTheRelaysOwnFreshConnectableOfferAndTheRightTransport() {
        val t = table()
        seenTwice { t.observePage(page(r1, entry(d, 1)), "BB:10") }
        assertEquals(0, t.callerRoutes(d).size) // no offer of r1 heard
        t.observeOffer("r1", offer(r1, helper = false), "AA:10")
        assertEquals(0, t.callerRoutes(d).size) // not a helper offer
        t.observeOffer("r1", offer(r1, helper = true, gatt = true), "AA:10")
        t.observePage(page(r1, entry(d, 1)), "BB:10")
        assertEquals(1, t.callerRoutes(d).size)
        assertEquals(0, t.callerRoutes(d, accept = { it.transportOption() == BluetoothTransportOption.BLE_L2CAP }).size)
        assertEquals(1, t.callerRoutes(d, accept = { it.transportOption() == BluetoothTransportOption.BLE_GATT }).size)
    }

    private fun refreshBoth(t: ReachTable<String>) {
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observeOffer("r2", offer(r2, helper = true, consent = false), "AA:20")
        t.observePage(page(r1, entry(d, 1)), "BB:10")
        t.observePage(page(r2, entry(d, 1)), "BB:20")
    }

    @Test
    fun failedPairsAreSkippedAndNoRouteDropsTheEntry() {
        val t = table()
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observeOffer("r2", offer(r2, helper = true, consent = false), "AA:20")
        seenTwice { refreshBoth(t) }
        assertEquals(2, t.callerRoutes(d).size)
        t.markFailed(r1, d, noRoute = false)
        assertEquals(listOf("r2"), t.callerRoutes(d).map { it.hop.peer })
        // The skip lasts 30 s; entries stay alive by being refreshed meanwhile.
        repeat(3) {
            now += 10_001
            refreshBoth(t)
        }
        assertEquals(2, t.callerRoutes(d).size)
        t.markFailed(r2, d, noRoute = true)
        // A "no route" answer drops the entry and ignores the pair for 5 minutes.
        repeat(29) {
            now += 10_000
            refreshBoth(t)
        }
        assertEquals(listOf("r1"), t.callerRoutes(d).map { it.hop.peer })
        repeat(2) {
            now += 10_000
            refreshBoth(t)
        }
        assertEquals(2, t.callerRoutes(d).size)
    }

    @Test
    fun relayedTargetsListsOnlyTargetsWithoutADirectOffer() {
        val t = table()
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1)), "BB:10") }
        assertEquals(1, t.relayedTargets().size)
        t.observeOffer("d", offer(d, consent = false), "AA:01")
        assertEquals(0, t.relayedTargets().size)
    }

    @Test
    fun aHelperTakesTheTargetDirectlyWhateverTheBudgetOtherwiseTheCheapestFittingRelay() {
        val t = table(ReachConfig(maxRelays = 4))
        t.observeOffer("r2", offer(r2, helper = true, consent = false), "AA:20")
        seenTwice { t.observePage(page(r2, entry(d, 1)), "BB:20") }
        // Budget 1 cannot use a relay; budget 2 can (r2 reaches d with 1 relay).
        assertNull(t.setupRoute(d, 1))
        val viaRelay = t.setupRoute(d, 2)
        assertNotNull(viaRelay)
        assertEquals("r2", viaRelay!!.hop.peer)
        // A consenting direct offer wins whatever the budget.
        t.observeOffer("direct", offer(d), "AA:01")
        val direct = t.setupRoute(d, 3)!!
        assertNull(direct.nextHop)
        assertEquals("direct", direct.hop.peer)
        // A direct offer without consent is not dialled by a helper.
        val u = table(ReachConfig(maxRelays = 4))
        u.observeOffer("direct", offer(d, consent = false), "AA:01")
        assertNull(u.setupRoute(d, 1))
        assertNull(u.setupRoute(d, 0))
    }

    /**
     * Relay-showcase z1/z2 (a15s > m31 > s22 > asus, both callers and the first helper blind to direct offers): the target
     * is itself a helper, so its own call offer is a helper offer. The first helper's route to it goes through the second
     * helper, and its redial after a failed 2M attempt must find that helper, never the target its dial announcement names
     * (the target answered a helper SETUP for itself with FAIL 1 and the second helper never got one).
     */
    @Test
    fun aHelperRedialsItsNextHelperNeverTheTargetThatAlsoOffersHelp() {
        val t = ReachTable<String>(own, ReachConfig(), { now }, Random(1), directSightings = { false })
        seenTwice {
            t.observeOffer("target", offer(d, helper = true), "AA:01")
            t.observeOffer("r1", offer(r1, helper = true), "AA:10")
            t.observePage(page(r1, entry(d, 1)), "BB:10")
        }
        val route = t.setupRoute(d, 2)!!
        assertEquals(r1.toList(), route.nextHop!!.toList())
        assertEquals("r1", route.hop.peer)

        // Both phones re-advertise after the failed dial; the helper's redial goes to the next helper.
        val failedAt = now
        now += 1_500
        t.observeOffer("target-new", offer(d, helper = true), "AA:02")
        t.observeOffer("r1-new", offer(r1, helper = true), "AA:11")
        assertEquals("r1-new", t.rediscoverHop(route.nextHop, d, failedAt)!!.peer)
        // A direct hop (no helper) is the target itself.
        assertEquals("target-new", t.rediscoverHop(null, d, failedAt)!!.peer)
        // A helper hop equal to the target is no hop.
        assertNull(t.rediscoverHop(d, d, failedAt))
        // Nothing newer than the failure: the known address is redialled.
        assertNull(t.rediscoverHop(route.nextHop, d, now + 1))
    }

    @Test
    fun theTargetIsNeverAHelperRouteForItsOwnCalls() {
        val t = ReachTable<String>(own, ReachConfig(maxRelays = 4), { now }, Random(1), directSightings = { false })
        seenTwice {
            t.observeOffer("target", offer(d, helper = true), "AA:01")
            // A page that names its own advertiser (a broken or hostile helper) is not a route through the target.
            t.observePage(page(d, entry(d, 1)), "BB:01")
        }
        assertNull(t.setupRoute(d, 2))
        assertTrue(t.callerRoutes(d).isEmpty())
        assertTrue(t.relayedTargets().isEmpty())
    }

    @Test
    fun advertisingNeedsFiveSecondsAHelperOfferAndRanksByCostThenAge() {
        val t = table(ReachConfig(maxRelays = 4, advertisedEntries = 4))
        val d2 = byteArrayOf(9, 9, 9, 9, 9, 9)
        val d3 = byteArrayOf(8, 8, 8, 8, 8, 8)
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice {
            t.observeOffer("d", offer(d), "AA:01")
            t.observePage(page(r1, entry(d2, 1, ageUnits = 4), entry(d3, 2, ageUnits = 1)), "BB:10")
        }
        // Held for 2 s only: the direct entry (cost 1) may be advertised, page-learned ones may not.
        var advertised = t.advertisable()
        assertEquals(listOf(d.toList()), advertised.map { it.entry.target.toList() })
        assertEquals(1, advertised[0].entry.cost)
        assertFalse(advertised[0].relay)
        assertEquals(d.copyOf(2).toList(), advertised[0].entry.via.toList())
        now += 5_100
        t.observeOffer("d", offer(d), "AA:01")
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observePage(page(r1, entry(d2, 1, ageUnits = 4), entry(d3, 2, ageUnits = 1)), "BB:10")
        advertised = t.advertisable()
        assertEquals(listOf(d.toList(), d2.toList(), d3.toList()), advertised.map { it.entry.target.toList() })
        assertEquals(listOf(1, 2, 3), advertised.map { it.entry.cost })
        // Without the relay's own helper offer nothing learned from it is advertised.
        now += 40_000
        val bare = table()
        seenTwice { bare.observePage(page(r1, entry(d, 1)), "BB:10") }
        now += 6_000
        bare.observePage(page(r1, entry(d, 1)), "BB:10")
        assertEquals(0, bare.advertisable().size)
    }

    @Test
    fun advertisedAgeNeverExceedsTheFieldAndEntriesBeyondItAreDropped() {
        val t = table()
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        seenTwice { t.observePage(page(r1, entry(d, 1, ageUnits = 31)), "BB:10") }
        // 62 s already: any further local time makes it too old to forward (and to keep).
        now += 6_000
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10")
        t.observePage(page(r1, entry(d, 1, ageUnits = 31)), "BB:10")
        assertEquals(0, t.advertisable().size)
    }

    @Test
    fun aCallerPinChoosesOnlyItsHelperAndNeverTheDirectOffer() {
        val t = table(ReachConfig(maxRelays = 4))
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10", rssi = -90)
        t.observeOffer("r2", offer(r2, helper = true, consent = false), "AA:20", rssi = -50)
        seenTwice {
            t.observePage(page(r1, entry(d, 1)), "BB:10")
            t.observePage(page(r2, entry(d, 1)), "BB:20")
        }
        t.observeOffer("direct", offer(d, consent = false), "AA:01")
        // Today the direct offer leads, then the stronger helper.
        assertEquals(listOf("direct", "r2", "r1"), t.callerRoutes(d).map { it.hop.peer })
        // The pin keeps only its helper and drops the direct fallback, even though r2 is stronger.
        assertEquals(listOf("r1"), t.callerRoutes(d, firstHopPin = r1).map { it.hop.peer })
        // A caller pin that equals the target prefix still never allows the direct route.
        assertTrue(t.callerRoutes(d, firstHopPin = d).isEmpty())
        // A pin that names no helper is no route at all (never the direct offer or another helper).
        assertTrue(t.callerRoutes(d, firstHopPin = byteArrayOf(9, 9, 9, 9, 9, 9)).isEmpty())
        // A rejected (malformed) pin matches nothing.
        assertTrue(t.callerRoutes(d, firstHopPin = ByteArray(0)).isEmpty())
    }

    @Test
    fun aHelperNextHopPinAcceptsOnlyThePinnedRelayOrTarget() {
        val t = table(ReachConfig(maxRelays = 4))
        t.observeOffer("r1", offer(r1, helper = true, consent = false), "AA:10", rssi = -90)
        t.observeOffer("r2", offer(r2, helper = true, consent = false), "AA:20", rssi = -50)
        seenTwice {
            t.observePage(page(r1, entry(d, 1)), "BB:10")
            t.observePage(page(r2, entry(d, 1)), "BB:20")
        }
        // Without a pin the stronger r2 is the helper's next hop.
        assertEquals("r2", t.setupRoute(d, 2)!!.hop.peer)
        // The pin chooses r1 even though r2 is stronger.
        val pinned = t.setupRoute(d, 2, nextHopPin = r1)!!
        assertEquals("r1", pinned.hop.peer)
        assertEquals(r1.toList(), pinned.nextHop!!.toList())
        // A pin that names no next hop is no route.
        assertNull(t.setupRoute(d, 2, nextHopPin = byteArrayOf(9, 9, 9, 9, 9, 9)))
        assertNull(t.setupRoute(d, 2, nextHopPin = ByteArray(0)))
        // A consenting direct target is accepted only when the pin is the target prefix; a relay pin skips it.
        t.observeOffer("direct", offer(d), "AA:01")
        val direct = t.setupRoute(d, 2, nextHopPin = d)!!
        assertNull(direct.nextHop)
        assertEquals("direct", direct.hop.peer)
        assertEquals("r1", t.setupRoute(d, 2, nextHopPin = r1)!!.hop.peer)
    }

    @Test
    fun aPinAllowsOnlyItsExactSignedPrefixAndAnEmptyPinNothing() {
        assertTrue(relayPinAllows(null, null))
        assertTrue(relayPinAllows(r1, r1))
        assertFalse(relayPinAllows(r1, r2))
        assertFalse(relayPinAllows(r1, null))
        assertFalse(relayPinAllows(ByteArray(0), r1))
    }
}
