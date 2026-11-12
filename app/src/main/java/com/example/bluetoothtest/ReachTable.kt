package com.example.bluetoothtest

import kotlin.random.Random

/** Limits of the reach table (DECISIONS Design C, C3). The defaults are the reviewed values. */
internal data class ReachConfig(
    /** Relays a route may cross: 2 by default (design decision 6), at most the chain limit of 4. */
    val maxRelays: Int = RelayedCalls.DEFAULT_MAX_RELAYS,
    val maxEntries: Int = 32,
    val maxRoutesPerTarget: Int = 3,
    /** Targets learned from one relay's pages. */
    val perRelayTargets: Int = 4,
    /** New targets admitted per [intakeWindowMs], whatever their source (address-rotating floods). */
    val newTargetsPerWindow: Int = 8,
    val intakeWindowMs: Long = 30_000L,
    /** Two sightings from one advertiser address at least this far apart admit an entry. */
    val minSightingGapMs: Long = 2_000L,
    val pendingWindowMs: Long = 30_000L,
    val maxPending: Int = 96,
    val staleMs: Long = 30_000L,
    val maxAgeMs: Long = 62_000L,
    /** An entry refreshed this long since it was first seen is never evicted by a newer one. */
    val protectedAfterMs: Long = 20_000L,
    val holdDownMs: Long = 10_000L,
    /** A page-learned entry is advertised only after this long in the table. */
    val advertiseAfterMs: Long = 5_000L,
    val failSkipMs: Long = 30_000L,
    val noRouteSkipMs: Long = 300_000L,
    val offerFreshMs: Long = RelayedCalls.OFFER_FRESH_MS,
    val maxOffers: Int = 64,
    val advertisedEntries: Int = 4,
) {
    init {
        require(maxRelays in 1..PrivateCallRelayChain.MAX_RELAYS) { "maxRelays" }
    }
}

/** The latest connectable cost-0 call offer heard from one node prefix. */
internal class OfferSighting<P : Any>(
    val peer: P,
    val announcement: DiscoveryAnnouncement,
    val seenMs: Long,
    val address: String,
    val rssi: Int?,
)

/** A usable way to reach [target]: directly, or through the first relay [nextHop]. */
internal class ReachRoute<P : Any>(
    val target: ByteArray,
    /** Null for a direct sighting of the target's own offer; else the first relay's prefix. */
    val nextHop: ByteArray?,
    /** Relays between the caller and the target: 0 direct, else the cost on the first relay's page. */
    val relays: Int,
    val ageMs: Long,
    /** The connectable offer to dial: the target's own (direct) or the first relay's. */
    val hop: OfferSighting<P>,
)

/**
 * AP8 DEBUG pin: true when [pin] is absent (off) or [firstHop] is exactly the pinned signed-node prefix. A caller's
 * direct route passes no first hop and is never allowed while a pin is set; a helper passes its direct target's prefix.
 * An empty (malformed) pin matches nothing.
 */
internal fun relayPinAllows(pin: ByteArray?, firstHop: ByteArray?): Boolean =
    pin == null || (pin.isNotEmpty() && firstHop != null && firstHop.contentEquals(pin))

/** What a helper advertises about one target. */
internal class AdvertisedReach(val entry: ReachPageEntry, val relay: Boolean)

/**
 * The reach table of DECISIONS Design C: targets this phone has learned only from adverts it scanned
 * itself, a distance vector with an age and no sequence numbers. It is never persisted and holds no
 * identity beyond node prefixes. All times come from [clock] (elapsed milliseconds); the type [P] is
 * the dialable peer (a BleL2capPeer in the app, a plain value in tests).
 *
 * Cost bookkeeping: an observation's `rawCost` is what the observer heard (0 for a target's own offer,
 * the page cost for a relay's page). A helper's own cost for the target is `rawCost + 1` (it is a relay
 * itself); a caller's route to the target costs `rawCost` relays through the advertising relay.
 */
internal class ReachTable<P : Any>(
    ownPrefix: ByteArray,
    private val config: ReachConfig = ReachConfig(),
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    /**
     * Rotating IDs: this table holds version 5 targets and version 6 pages only (the other population has its
     * own table), and the caller flushes it at every boundary (RV6). [ownIds] are this phone's current and
     * previous role-0 RDIs: never a target, never a poison-reverse route.
     */
    val rotating: Boolean = false,
    ownIds: (() -> List<ByteArray>)? = null,
    /**
     * Experimental DEBUG topology mask (`test_relayed_calls_no_direct`, default on = normal behaviour). Off, this
     * phone behaves as if no target were within its own radio range: a target's own cost-0 offer is neither a
     * direct observation, nor a direct route, nor a direct SETUP next hop; only reach pages name targets. Offers
     * are still kept, because a helper's connectable offer is how a hop is dialled. A co-located lab otherwise
     * collapses every chain to one relay (each phone hears the target and the table prefers the shortest path), so
     * a two-relay test marks the caller and the first helper blind and leaves the second helper normal.
     */
    private val directSightings: () -> Boolean = { true },
) {
    private val ownPrefix = ownPrefix.copyOf()
    private val ownIdsNow: () -> List<ByteArray> = ownIds ?: { listOf(this.ownPrefix) }

    private fun isOwn(bytes: ByteArray): Boolean = ownIdsNow().any { it.contentEquals(bytes) }
    private fun isOwnVia(via: ByteArray): Boolean = ownIdsNow().any { it.copyOf(ReachPageCodec.VIA_BYTES).contentEquals(via) }

    private class Observation(
        val target: ByteArray,
        val targetKey: String,
        val relay: ByteArray?,
        val relayKey: String?,
        var rawCost: Int,
        var ageAtObservationMs: Long,
        var observedMs: Long,
        val firstSeenMs: Long,
        var lastRefreshMs: Long,
        var used: Boolean = false,
    ) {
        val routeKey: String get() = "$targetKey|${relayKey ?: "-"}"
        fun ageMs(now: Long): Long = ageAtObservationMs + (now - observedMs)
    }

    private class Pending(val firstMs: Long)

    private val observations = LinkedHashMap<String, Observation>()
    private val pending = LinkedHashMap<String, Pending>()
    private val offers = LinkedHashMap<String, OfferSighting<P>>()
    private val skipUntil = HashMap<String, Long>()
    private val intake = ArrayDeque<Long>()
    private val holdDown = HashMap<String, Pair<Int, Long>>()

    val size: Int
        @Synchronized get() = observations.size

    // ----- sightings -----

    /**
     * A scanned call offer. Only fixed-prefix, cost-0, LE, connectable call offers matter: the offer is
     * remembered (to dial it later) and, when it carries the consent bit, becomes a direct observation.
     */
    @Synchronized
    fun observeOffer(peer: P, announcement: DiscoveryAnnouncement, address: String, rssi: Int? = null) {
        if (!announcement.isPrivateCallOffer() || announcement.rotating != rotating || announcement.routeCost != 0) return
        if (announcement.usesClassicTransport()) return
        if (announcement.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS == 0) return
        val now = clock()
        val key = announcement.nodeIdPrefix.prefixHex()
        if (isOwn(announcement.nodeIdPrefix)) return
        offers.remove(key)
        offers[key] = OfferSighting(peer, announcement, now, address, rssi)
        while (offers.size > config.maxOffers) offers.remove(offers.keys.first())
        if (directSightings() && RelayedCalls.consents(announcement, rotating)) {
            observe(announcement.nodeIdPrefix, relay = null, rawCost = 0, ageMs = 0L, address = address, now = now)
        }
    }

    /** A scanned reach page from the advertiser at [address]. */
    @Synchronized
    fun observePage(page: ReachPage, address: String) {
        val now = clock()
        if (page.rotating != rotating) return
        if (isOwn(page.relayPrefix)) return
        page.entries.forEach { entry ->
            if (isOwn(entry.target)) return@forEach
            // Poison reverse: a relay that reached the target through this phone is not a route back. A
            // rotating phone compares with its current and previous RDI (a boundary may have passed).
            if (isOwnVia(entry.via)) return@forEach
            if (entry.cost > config.maxRelays) return@forEach
            // A page naming its own advertiser is no relay route: that phone is the target, dialled as the target only.
            if (entry.target.contentEquals(page.relayPrefix)) return@forEach
            observe(entry.target, page.relayPrefix, entry.cost, entry.ageMs, address, now)
        }
    }

    private fun observe(target: ByteArray, relay: ByteArray?, rawCost: Int, ageMs: Long, address: String, now: Long) {
        prune(now)
        val targetKey = target.prefixHex()
        val relayKey = relay?.prefixHex()
        val routeKey = "$targetKey|${relayKey ?: "-"}"
        observations[routeKey]?.let { existing ->
            // Ages only grow along a path, so a refresh keeps the smaller of the two ages. A pair that
            // failed recently stays alive (it is only left out of the candidate list), so it is back
            // as soon as its skip ends.
            existing.ageAtObservationMs = minOf(existing.ageMs(now), ageMs)
            existing.observedMs = now
            existing.lastRefreshMs = now
            // The relay's cost for this target can change (its own route got longer or shorter).
            existing.rawCost = rawCost
            return
        }
        // Only a new entry is held back by a failure skip or a hold-down.
        if ((skipUntil[routeKey] ?: 0L) > now) return
        holdDown[targetKey]?.let { (lostCost, until) ->
            if (until > now && rawCost + 1 > lostCost) return
        }
        val pendingKey = "$routeKey@$address"
        val first = pending[pendingKey]
        if (first == null || now - first.firstMs > config.pendingWindowMs) {
            pending.remove(pendingKey)
            pending[pendingKey] = Pending(now)
            while (pending.size > config.maxPending) pending.remove(pending.keys.first())
            return
        }
        if (now - first.firstMs < config.minSightingGapMs) return
        // Admission: global intake, per-relay and per-target caps, then room (evicting only a worse entry).
        while (intake.isNotEmpty() && now - intake.first() >= config.intakeWindowMs) intake.removeFirst()
        if (intake.size >= config.newTargetsPerWindow) return
        if (relayKey != null && observations.values.count { it.relayKey == relayKey } >= config.perRelayTargets) return
        if (observations.values.count { it.targetKey == targetKey } >= config.maxRoutesPerTarget) return
        if (observations.size >= config.maxEntries && !evictWorseThan(rawCost, now)) return
        observations[routeKey] = Observation(target.copyOf(), targetKey, relay?.copyOf(), relayKey, rawCost, ageMs, now, now, now)
        intake.addLast(now)
        pending.remove(pendingKey)
    }

    private fun isProtected(o: Observation, now: Long): Boolean =
        o.used || now - o.firstSeenMs >= config.protectedAfterMs

    private fun evictWorseThan(rawCost: Int, now: Long): Boolean {
        val victim = observations.values
            .filterNot { isProtected(it, now) }
            .maxWithOrNull(compareBy<Observation> { it.rawCost }.thenBy { -it.lastRefreshMs })
            ?: return false
        if (victim.rawCost < rawCost) return false
        observations.remove(victim.routeKey)
        return true
    }

    private fun prune(now: Long) {
        val iterator = observations.values.iterator()
        val lost = ArrayList<Observation>()
        while (iterator.hasNext()) {
            val o = iterator.next()
            if (o.ageMs(now) > config.maxAgeMs || now - o.lastRefreshMs > config.staleMs) {
                iterator.remove()
                lost += o
            }
        }
        lost.forEach { gone ->
            if (observations.values.none { it.targetKey == gone.targetKey }) {
                // After the best route to a target is lost, worse routes are ignored briefly (damps loops).
                holdDown[gone.targetKey] = (gone.rawCost + 1) to (now + config.holdDownMs)
            }
        }
        holdDown.entries.removeAll { it.value.second <= now }
        skipUntil.entries.removeAll { it.value <= now }
        pending.entries.removeAll { now - it.value.firstMs > config.pendingWindowMs }
        offers.entries.removeAll { now - it.value.seenMs > config.offerFreshMs * 2 }
    }

    // ----- outcomes -----

    /** A setup through [relay] (null: a direct call) to [target] failed; skip that pair briefly. */
    @Synchronized
    fun markFailed(relay: ByteArray?, target: ByteArray, noRoute: Boolean) {
        val now = clock()
        val key = "${target.prefixHex()}|${relay?.prefixHex() ?: "-"}"
        skipUntil[key] = now + if (noRoute) config.noRouteSkipMs else config.failSkipMs
        if (noRoute) observations.remove(key)
    }

    /** A setup through [relay] to [target] reached READY: the entry survives eviction. */
    @Synchronized
    fun markUsed(relay: ByteArray?, target: ByteArray) {
        observations["${target.prefixHex()}|${relay?.prefixHex() ?: "-"}"]?.used = true
    }

    // ----- queries -----

    @Synchronized
    fun directOffer(target: ByteArray, maxAgeMs: Long = config.offerFreshMs): OfferSighting<P>? {
        if (!directSightings()) return null
        val now = clock()
        return offers[target.prefixHex()]?.takeIf { now - it.seenMs <= maxAgeMs }
    }

    @Synchronized
    fun offer(prefix: ByteArray): OfferSighting<P>? = offers[prefix.prefixHex()]

    /** The connectable offer of relay [prefix] if it is fresh and a helper's (FLAG_RELAY_CAPABLE). */
    @Synchronized
    fun helperOffer(prefix: ByteArray, accept: (DiscoveryAnnouncement) -> Boolean = { true }): OfferSighting<P>? {
        val now = clock()
        return offers[prefix.prefixHex()]?.takeIf {
            now - it.seenMs <= config.offerFreshMs && RelayedCalls.isRelayHelperOffer(it.announcement, rotating) && accept(it.announcement)
        }
    }

    /**
     * The freshest offer of the next hop after a failed dial, heard at or after [seenAfterMs]: the helper [hopPrefix] of a
     * relayed route, else (a direct hop) the [target] itself. A routed hop's dial announcement carries the target's ID, so
     * a lookup by that ID would find the target's own offer and send it a helper SETUP for itself (relay-showcase z1/z2:
     * M31 redialled the ASUS, the target, instead of the S22 helper). A helper hop equal to the target is no hop.
     */
    @Synchronized
    fun rediscoverHop(hopPrefix: ByteArray?, target: ByteArray, seenAfterMs: Long): OfferSighting<P>? {
        if (hopPrefix != null && hopPrefix.contentEquals(target)) return null
        return offers[(hopPrefix ?: target).prefixHex()]?.takeIf { it.seenMs >= seenAfterMs }
    }

    /**
     * Routes a caller can try for [target], best first (PLAN C6): direct, then fewest relays, then age
     * under 20 s before older, then the stronger first hop, then the freshest. Failed pairs are excluded.
     */
    @Synchronized
    fun callerRoutes(
        target: ByteArray,
        accept: (DiscoveryAnnouncement) -> Boolean = { true },
        requireRelays: Int = 0,
        /** AP8 DEBUG pin: keep only routes whose first relay is exactly this signed-node prefix (null = off). */
        firstHopPin: ByteArray? = null,
    ): List<ReachRoute<P>> {
        val now = clock()
        prune(now)
        val targetKey = target.prefixHex()
        val routes = ArrayList<ReachRoute<P>>()
        if (requireRelays == 0) {
            directOffer(target)?.takeIf { accept(it.announcement) }?.let { hop ->
                if (relayPinAllows(firstHopPin, null)) {
                    routes += ReachRoute(target.copyOf(), null, 0, 0L, hop)
                }
            }
        }
        observations.values.filter { it.targetKey == targetKey && it.relay != null }.forEach { o ->
            if (requireRelays != 0 && o.rawCost != requireRelays) return@forEach
            if ((skipUntil[o.routeKey] ?: 0L) > now) return@forEach
            // The target is never a helper for its own call (its helper offer is its own call offer).
            if (o.relay!!.contentEquals(target)) return@forEach
            if (!relayPinAllows(firstHopPin, o.relay)) return@forEach
            val hop = helperOffer(o.relay, accept) ?: return@forEach
            routes += ReachRoute(target.copyOf(), o.relay.copyOf(), o.rawCost, o.ageMs(now), hop)
        }
        return routes.sortedWith(
            compareBy<ReachRoute<P>> { it.relays }
                .thenBy { if (it.ageMs < FRESH_AGE_MS) 0 else 1 }
                .thenBy { -(it.hop.rssi ?: -127) / BAND_DB }
                .thenBy { it.ageMs },
        )
    }

    /** Targets with a relayed route and no fresh direct offer: the extra rows of the people list. */
    @Synchronized
    fun relayedTargets(
        accept: (DiscoveryAnnouncement) -> Boolean = { true },
        /** A benchmark that requires a chain lists targets heard directly too. */
        includeDirect: Boolean = false,
    ): List<ByteArray> {
        val now = clock()
        prune(now)
        return observations.values.filter { it.relay != null }.map { it.target }
            .distinctBy { it.prefixHex() }
            .filter { includeDirect || directOffer(it) == null }
            .filter { callerRoutes(it, accept, requireRelays = 0).any { route -> route.nextHop != null } }
    }

    /**
     * The route a helper takes for a SETUP with [budget]: a fresh direct offer of the target whatever the
     * budget (one relay), else the cheapest page-learned route whose cost fits the budget left for the
     * next hop. Null when none.
     */
    @Synchronized
    fun setupRoute(
        target: ByteArray,
        budget: Int,
        accept: (DiscoveryAnnouncement) -> Boolean = { true },
        /** AP8 DEBUG pin: accept only this next relay or target prefix (null = off). */
        nextHopPin: ByteArray? = null,
    ): ReachRoute<P>? {
        if (budget < 1) return null
        val direct = directOffer(target)?.takeIf { accept(it.announcement) }
        if (direct != null && relayPinAllows(nextHopPin, direct.announcement.nodeIdPrefix)) {
            val consented = RelayedCalls.consents(direct.announcement, rotating)
            if (consented) return ReachRoute(target.copyOf(), null, 0, 0L, direct)
        }
        if (budget == 1) return null
        return callerRoutes(target, accept, firstHopPin = nextHopPin)
            .firstOrNull { it.nextHop != null && it.relays <= budget - 1 }
    }

    /**
     * What this helper advertises, best first: its own cost is `rawCost + 1` within the cap, a
     * page-learned entry only after [ReachConfig.advertiseAfterMs] and only while its relay's connectable
     * helper offer is also fresh (a cheap consistency check). One entry per target.
     */
    @Synchronized
    fun advertisable(): List<AdvertisedReach> {
        val now = clock()
        prune(now)
        val best = HashMap<String, Triple<Observation, Int, Int>>()
        observations.values.forEach { o ->
            val ownCost = o.rawCost + 1
            if (ownCost > config.maxRelays) return@forEach
            val units = ReachPageCodec.ageUnitsFor(o.ageMs(now)) ?: return@forEach
            if (o.relay != null) {
                if (now - o.firstSeenMs < config.advertiseAfterMs) return@forEach
                if (helperOffer(o.relay) == null) return@forEach
            } else if (directOffer(o.target) == null) {
                return@forEach
            }
            val current = best[o.targetKey]
            if (current == null || ownCost < current.second || (ownCost == current.second && units < current.third)) {
                best[o.targetKey] = Triple(o, ownCost, units)
            }
        }
        // One random tie-break key per entry, drawn before sorting (a random comparator breaks the sort contract).
        return best.values
            .map { it to random.nextInt() }
            .sortedWith(compareBy<Pair<Triple<Observation, Int, Int>, Int>> { it.first.second }.thenBy { it.first.third }.thenBy { it.second })
            .map { it.first }
            .take(config.advertisedEntries)
            .map { (o, ownCost, units) ->
                val via = (o.relay ?: o.target).copyOf(ReachPageCodec.VIA_BYTES)
                AdvertisedReach(ReachPageEntry(o.target.copyOf(), ownCost, units, via), relay = o.relay != null)
            }
    }

    /**
     * Entries by the cost heard (`c0` = a target's own offer, `cN` = a page naming N relays), for the DEBUG table
     * line: with the total alone, "no two-relay route" could not be told from "routes exist but are filtered".
     */
    @Synchronized
    fun costSummary(): String {
        val counts = observations.values.groupingBy { it.rawCost }.eachCount().toSortedMap()
        return if (counts.isEmpty()) "none" else counts.entries.joinToString(",") { "c${it.key}=${it.value}" }
    }

    @Synchronized
    fun clear() {
        observations.clear()
        pending.clear()
        offers.clear()
        skipUntil.clear()
        intake.clear()
        holdDown.clear()
    }

    private companion object {
        const val FRESH_AGE_MS = 20_000L
        const val BAND_DB = 15
    }
}
