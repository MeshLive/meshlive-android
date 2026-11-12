package com.example.bluetoothtest

/**
 * Experimental `test_own_2m_memory` ([PhySwitches.own2mMemory], default off): a memory about THIS phone's own Bluetooth
 * controller, kept beside the per-peer bad-2M marks of [AdaptivePhyMemory] (the same sealed local store, expiry, DEBUG reset
 * and launch summary). It is never sent, and none of its keys or values names a peer.
 *
 * Why: the per-peer memory is keyed by the direct peer's node, and an unrecognised rotating offer (a stranger in a user launch,
 * where IDs rotate) has no key, so [AdaptivePhyMemory.phyFor] cannot consult it and every such call starts on symmetric 2M
 * again. Measured (release-gate lane3, B10, 2026-11-11, user launch): the OPPO A15s (MT6765, Android 10) as caller stalled on
 * symmetric 2M (`reason=setup_stall | action=close` after 3 s), retried on 1M and had to rediscover the ASUS callee, which
 * often never saw the close and did not re-advertise in time: 4 of 18 A15s -> ASUS calls FAILED_JOIN and the others were
 * slow, while ASUS -> A15s was fast in 6 of 6. Earlier A15s -> ASUS runs: every symmetric 2M link died or crawled, 5 of 6 one-direction-2M links and 3 of 3 1M links were clean (DECISIONS "A15s as a
 * sender, phone results").
 *
 * Rule: when this phone, as the central dialing a call, has its symmetric 2M setup stall [STALLS_TO_MARK] times in a row
 * (no clean symmetric 2M setup in between) within [STALL_WINDOW_MS], it writes the mark. While the
 * mark is fresh ([AdaptivePhyMemory.ttlMs], the per-peer marks' expiry: 7 days, 24 h with the learned-limit recovery), a link
 * to a peer with NO memory key starts on full 1M ([BlePhyPreference.ONE_M_ADAPTIVE]) and logs
 * `adaptive_phy: ... reason=own_controller_2m`. Peers with a memory key (stable IDs, recognised contacts) and relayed offers
 * keep their per-peer rules unchanged. A further stall refreshes the mark; [SUCCESSES_TO_CLEAR] clean symmetric 2M setups
 * as central in a row clear it.
 *
 * The numbers:
 * - [STALLS_TO_MARK] = 2: a healthy setup authenticates within 1.05 s (353 saved setups, p90 at most 1050 ms, none near the
 *   3 s guard), so one stall is already rare on a healthy controller and two in a row are rarer still, while one stall alone
 *   can be one crowded moment or one bad peer. The A15s stalled in most lane3 calls, so it is marked after its second call.
 * - [STALL_WINDOW_MS] = 24 h ([AdaptivePhyMemory.RECOVERY_TTL_MS]): two stalls a day apart are more likely different places,
 *   crowds or Wi-Fi conditions than one controller property.
 * - Clearing needs the same weight of evidence the mark needed ([SUCCESSES_TO_CLEAR] = [STALLS_TO_MARK]) instead of a single
 *   success: a wrong clear costs two more stalled calls (in lane3 about one in five stalled calls never rang), a wrong hold
 *   costs strangers the lower 1M ceiling, which carried every A15s call and stream measured on it.
 *
 * Safe choice, full 1M rather than a one-direction split ([PhyDirectionSplit], `test_phy_direction_split`): the clean split
 * results come from streams, where one direction carries the media; a call carries media both ways, and the split leaves the
 * A15s receive path on 2M, which was slow under load. The split machinery is also GATT-only and per peer, so it does not
 * cover CoC calls or strangers. 1M was clean in every A15s attempt, and the remembered-1M calls authenticated in 0.18-0.23 s.
 *
 * Risk: the measured fault is pair-specific, but the mark applies to every unrecognised peer. A15s -> S22 (B10, user launch,
 * 2026-11-11) rang in 4 of 4 calls with no stall and reached symmetric 2M on CoC, so the stalls are A15s central -> ASUS
 * peripheral, not the A15s controller in general; while the mark is fresh an A15s -> S22 call would drop to 1M needlessly. A
 * phone with a transient bad 2M also stays on 1M for strangers until the mark expires or is cleared (cost: the 1M throughput
 * ceiling). Two stalls with the same bad peer count, since a stranger cannot be told apart. Experimental arm only.
 */
internal object OwnControllerTwoM {
    const val STALLS_TO_MARK = 2
    const val STALL_WINDOW_MS = AdaptivePhyMemory.RECOVERY_TTL_MS
    const val SUCCESSES_TO_CLEAR = STALLS_TO_MARK

    /** Every key of this memory starts with this (no peer label): [AdaptivePhyMemory.isMark] counts them as learned marks. */
    const val PREFIX = "own2m2."
    const val MARK_KEY = "${PREFIX}mark"

    private fun stallKeys(n: Int) = (0 until n - 1).map { "${PREFIX}stall.$it" }
    private fun okKeys(n: Int) = (0 until n - 1).map { "${PREFIX}ok.$it" }

    /** The epoch-ms values of this memory, in the marks store or a test map. */
    interface Store {
        fun get(key: String): Long?
        fun put(key: String, value: Long)
        fun remove(keys: Collection<String>)
    }

    /** What one recorded stall did: [stalls] in the current run (capped at [STALLS_TO_MARK] once marked). */
    data class StallResult(val stalls: Int, val marked: Boolean, val refreshed: Boolean)

    enum class SuccessResult { NOT_MARKED, COUNTING, CLEARED }

    /**
     * The symmetric 2M setup this memory learns from: the dial requested [BlePhyPreference.TWO_M] with no `test_ble_phy` arm,
     * no static or remembered split and no 1M-first deferral, on a hop that carries nothing else (a direct offer, or the idle
     * helper of an on-demand relayed route): a relay hop's other link shares its radio, so its stall is no evidence here.
     */
    fun symmetricTwoMSetup(
        setupPhy: BlePhyPreference,
        le: Boolean,
        idleHop: Boolean,
        splitApplied: Boolean,
        blePhyArm: Boolean,
        staticSplit: Boolean,
    ): Boolean = le && idleHop && setupPhy == BlePhyPreference.TWO_M && !splitApplied && !blePhyArm && !staticSplit

    fun marked(store: Store, nowMs: Long, ttlMs: Long): Boolean =
        AdaptivePhyMemory.fresh(store.get(MARK_KEY) ?: 0L, nowMs, ttlMs)

    fun recordStall(
        store: Store,
        nowMs: Long,
        ttlMs: Long,
        n: Int = STALLS_TO_MARK,
        windowMs: Long = STALL_WINDOW_MS,
    ): StallResult {
        // A stall breaks a clearing run.
        store.remove(okKeys(SUCCESSES_TO_CLEAR))
        if (marked(store, nowMs, ttlMs)) {
            store.put(MARK_KEY, nowMs)
            return StallResult(n, marked = true, refreshed = true)
        }
        val run = chained(store, stallKeys(n), nowMs, windowMs) + nowMs
        store.remove(stallKeys(n))
        if (run.size >= n) {
            store.put(MARK_KEY, nowMs)
            return StallResult(run.size, marked = true, refreshed = false)
        }
        run.forEachIndexed { i, at -> store.put(stallKeys(n)[i], at) }
        return StallResult(run.size, marked = false, refreshed = false)
    }

    fun recordSuccess(
        store: Store,
        nowMs: Long,
        ttlMs: Long,
        n: Int = SUCCESSES_TO_CLEAR,
        windowMs: Long = STALL_WINDOW_MS,
    ): SuccessResult {
        // Stalls must come in a row: a clean setup in between breaks the run.
        store.remove(stallKeys(STALLS_TO_MARK))
        if (!marked(store, nowMs, ttlMs)) {
            store.remove(okKeys(n))
            return SuccessResult.NOT_MARKED
        }
        val run = chained(store, okKeys(n), nowMs, windowMs) + nowMs
        store.remove(okKeys(n))
        if (run.size >= n) {
            store.remove(listOf(MARK_KEY))
            return SuccessResult.CLEARED
        }
        run.forEachIndexed { i, at -> store.put(okKeys(n)[i], at) }
        return SuccessResult.COUNTING
    }

    /** DEBUG reset: removes the mark and both runs. */
    fun clear(store: Store) {
        store.remove(listOf(MARK_KEY) + stallKeys(STALLS_TO_MARK) + okKeys(SUCCESSES_TO_CLEAR))
    }

    /** The stored run, oldest first: only entries within [windowMs] before [nowMs] (not from the future after a clock change). */
    private fun chained(store: Store, keys: List<String>, nowMs: Long, windowMs: Long): List<Long> =
        keys.mapNotNull { store.get(it) }.filter { AdaptivePhyMemory.fresh(it, nowMs, windowMs) }.sorted()
}
