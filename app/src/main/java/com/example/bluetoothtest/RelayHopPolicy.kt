package com.example.bluetoothtest

/**
 * Experimental relayed calls (DECISIONS Design C), relay-showcase fixes of 2026-10-26: the attribution lines that name
 * the helper a caller selected and the stage a helper's hop failed in, the helper's one fast connect retry, and the
 * matching rules that let the caller's 1M retry find the selected helper's current advert. Pure, JVM-tested.
 */
internal object RelayHopPolicy {
    /**
     * A next-hop connect that failed this soon after it started never formed a link (M31 helper, relay-showcase b08:
     * the CoC connect on its fresh ACL failed after 341 ms with "read failed"); repeating it is the same request again.
     */
    const val FAST_FAIL_MS = 2_000L

    /** What a retry needs left of the SETUP deadline: a connect (0.03-4.1 s measured) plus hop authentication and READY. */
    const val RETRY_MIN_LEFT_MS = 6_000L

    /** Pause before the retry, so the stack can finish tearing down the failed attempt's ACL. */
    const val RETRY_GAP_MS = 500L

    const val STOPPED_MESSAGE = "Relay setup ended while connecting the next hop"

    /** A 2M next hop whose authentication has not finished this long after the link opened is a stalled setup (the call rule). */
    const val HOP_SETUP_STALL_MS = AdaptivePhyMemory.SETUP_STALL_MS

    /** Longest wait for the next hop's fresh advert before the 1M retry; what is left must still fit the retry itself. */
    const val RESCAN_MAX_MS = 5_000L

    /** The first next-hop attempt runs on 2M and the per-link 1M retry is on. */
    fun phyRetryEligible(enabled: Boolean, firstOnTwoM: Boolean): Boolean = enabled && firstOnTwoM

    /**
     * AP7 F3 (`test_relay_start_hop_guard`, Experimental, off): the DEBUG static relay's child hop gets the on-demand
     * hop's single 1M retry. True only for the first attempt on 2M while the switch is on and no retry has run.
     */
    fun startHopRetry(enabled: Boolean, firstOnTwoM: Boolean, retried: Boolean): Boolean =
        !retried && phyRetryEligible(enabled, firstOnTwoM)

    fun rescanBudgetMs(leftMs: Long): Long = (leftMs - RETRY_MIN_LEFT_MS).coerceIn(0L, RESCAN_MAX_MS)

    fun phyRetryLine(node: String, stalled: Boolean, leftMs: Long, error: Throwable, debug: Boolean): String =
        (if (debug) "BENCHMARK " else "") +
            "adaptive_phy: link=call-relay | node=$node | from=2M to=1M | reason=${if (stalled) "setup_stall" else "setup_retry"} | " +
            "attempt=2 | leftMs=$leftMs | error=${error.javaClass.simpleName}"

    /**
     * Closes a next hop whose authentication did not settle within [timeoutMs] ([onStall]); [settle] says whether the
     * authentication won. Exactly one of the two wins (the call setup's CallSetupStallState rule).
     */
    class HopSetupStallGuard(private val timeoutMs: Long, private val onStall: () -> Unit) {
        private val state = java.util.concurrent.atomic.AtomicInteger(0)
        private val settled = java.util.concurrent.CountDownLatch(1)

        val stalled: Boolean get() = state.get() == 2

        fun start() {
            kotlin.concurrent.thread(name = "RelayHopSetupStall", isDaemon = true) {
                val inTime = try {
                    settled.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!inTime && state.compareAndSet(0, 2)) runCatching(onStall)
            }
        }

        /** True when the authentication finished first; false when the guard had already closed the hop. */
        fun settle(): Boolean {
            val won = state.compareAndSet(0, 1) || state.get() == 1
            settled.countDown()
            return won
        }
    }

    /**
     * Experimental `test_unified_connect_policy`: the next hop's client loop gets the SETUP deadline minus
     * [UnifiedConnectRule.HELPER_REPLY_RESERVE_MS] (room for hop authentication and READY) as its [SetupDeadline]; null
     * (unbounded, as before) with the switch off.
     */
    fun unifiedHopDeadline(
        unified: Boolean,
        startedNs: Long,
        remainingMs: Int,
        nowNs: Long,
        nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): SetupDeadline? {
        if (!unified) return null
        val leftMs = remainingMs - (nowNs - startedNs) / 1_000_000L
        return SetupDeadline(nowElapsedMs + leftMs - UnifiedConnectRule.HELPER_REPLY_RESERVE_MS)
    }

    /**
     * Whether a failed next-hop attempt may take the 2M -> 1M retry: always with the switch off; under the unified connect
     * policy only after the link [connected] (a stall or an authentication failure), since a connect failure already had
     * its budgeted retries inside the client.
     */
    fun phyRetryAfter(unified: Boolean, connected: Boolean): Boolean = !unified || connected

    /** One retry of the same connect: first attempt only, a fast failure, enough of the deadline left. */
    fun connectRetryAllowed(enabled: Boolean, attempt: Int, failedAfterMs: Long, leftMs: Long): Boolean =
        enabled && attempt == 1 && failedAfterMs in 0 until FAST_FAIL_MS && leftMs >= RETRY_MIN_LEFT_MS

    /**
     * Runs [connect]; when it throws an IOException that [connectRetryAllowed] admits, logs the retry, runs [beforeRetry]
     * (close what the failed attempt left open), waits [RETRY_GAP_MS] and runs it once more. [onAttempt] sees each attempt
     * number before it starts. [stopped] true (the duty was closed) rethrows at once.
     */
    fun <T> connectWithOneRetry(
        enabled: Boolean,
        startedNs: Long,
        remainingMs: Int,
        nowNs: () -> Long,
        stopped: () -> Boolean,
        sleep: (Long) -> Unit,
        log: (String) -> Unit,
        debug: Boolean,
        beforeRetry: () -> Unit,
        onAttempt: (Int) -> Unit,
        /** Closes a connection that completed after the duty was stopped (SETUP expiry or close). */
        discard: (T) -> Unit = {},
        connect: () -> T,
    ): T {
        var attempt = 1
        while (true) {
            if (stopped()) throw java.io.IOException(STOPPED_MESSAGE)
            onAttempt(attempt)
            val attemptNs = nowNs()
            try {
                val connection = connect()
                // A connect that finished after the SETUP deadline closed the duty must not reach the handshake.
                if (stopped()) {
                    runCatching { discard(connection) }
                    throw java.io.IOException(STOPPED_MESSAGE)
                }
                return connection
            } catch (error: java.io.IOException) {
                if (error.message == STOPPED_MESSAGE) throw error
                val failedAfterMs = (nowNs() - attemptNs) / 1_000_000L
                val leftMs = remainingMs - (nowNs() - startedNs) / 1_000_000L
                if (stopped() || !connectRetryAllowed(enabled, attempt, failedAfterMs, leftMs)) throw error
                log(connectRetryLine(failedAfterMs, leftMs, error, debug))
                runCatching(beforeRetry)
                sleep(RETRY_GAP_MS)
                attempt++
            }
        }
    }

    fun hex(prefix: ByteArray): String =
        prefix.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).joinToString("") { "%02x".format(it) }

    /** BluetoothDevice.getAddressType (API 35): 0 public, 1 random; anything else (or an older phone) is unknown. */
    fun addressTypeLabel(type: Int?): String = when (type) {
        0 -> "public"
        1 -> "random"
        else -> "unknown"
    }

    /** The last two address bytes, as the other DEBUG link lines print them (`...C6:07`); never the full address. */
    fun addressSuffix(address: String?): String =
        address?.takeIf { it.length >= 5 }?.let { "..." + it.takeLast(5).uppercase() } ?: "unknown"

    /**
     * The caller's choice of helper for a relayed dial ("Calling relayed [target] via N" names only the target). The helper ID
     * is the selected helper's offer prefix: a role-0 rotating ID (`id=rdi`, changes every epoch) or the fixed node prefix
     * (`id=node`). A release build logs no address; a DEBUG build adds its last two bytes for the run's attribution.
     */
    fun callerRouteLine(route: RelayRoute, hopAddress: String?, addressType: Int?, debug: Boolean): String =
        (if (debug) "BENCHMARK " else "") +
            "relayed_route: role=caller | target=${hex(route.target)} | helper=${hex(route.helperPrefix)} | " +
            "id=${if (route.rotating) "rdi" else "node"} | budget=${route.relays} | addressType=${addressTypeLabel(addressType)}" +
            (if (debug) " | hopAddress=${addressSuffix(hopAddress)}" else "")

    /** The next hop a helper accepted to dial: the target itself (budget 0) or the next helper's offer prefix. */
    fun helperNextHop(nextBudget: Int, nextPrefix: ByteArray, hopPrefix: ByteArray?): String =
        if (nextBudget > 0 && hopPrefix != null) "helper:${hex(hopPrefix)}" else "target:${hex(nextPrefix)}"

    /**
     * Why a helper's setup ended before READY. The FAIL code on the wire stays coarse (PROTOCOL: an EOF while dialing the
     * next hop is FAIL 1 "no route"); this line keeps the stage and the error so "no route" right after an accepted SETUP
     * can be told apart from a missing reach entry (a refused SETUP logs `result=refused`).
     */
    fun hopFailureLine(stage: String, error: Throwable, elapsedMs: Long, attempts: Int, debug: Boolean): String =
        (if (debug) "BENCHMARK " else "") +
            "relayed_hop: role=helper | event=failed | stage=${stage.lowercase()} | error=${error.javaClass.simpleName} | " +
            "elapsedMs=$elapsedMs | attempts=$attempts"

    fun connectRetryLine(failedAfterMs: Long, leftMs: Long, error: Throwable, debug: Boolean): String =
        (if (debug) "BENCHMARK " else "") +
            "relayed_hop: role=helper | event=connect_retry | attempt=2 | failedAfterMs=$failedAfterMs | leftMs=$leftMs | " +
            "error=${error.javaClass.simpleName}"

    /**
     * What the caller's 1M retry scans for on an on-demand relayed route: the selected helper's own cost-0 call offer
     * (its prefix is the route's helper ID, the same population version as the route). The routed announcement names the
     * target at route cost 1+, which nobody advertises for an on-demand route, so the old rescan rule never applied.
     */
    fun helperRetryMatches(route: RelayRoute, routedVersion: Int, offer: DiscoveryAnnouncement): Boolean =
        offer.routeCost == 0 && offer.version == routedVersion && offer.isPrivateCallOffer() &&
            offer.rotating == route.rotating &&
            offer.nodeIdPrefix.contentEquals(route.helperPrefix.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES))

    /**
     * The retry dial keeps the original row (target announcement and route: the requested chain) and takes only the device
     * (current address) from the helper's fresh offer. The hop opening still has to prove the selected helper.
     */
    fun retryPeer(original: BleL2capPeer, freshHelper: BleL2capPeer?): BleL2capPeer =
        freshHelper?.let { original.copy(device = it.device) } ?: original
}

/** The relayed routes one outgoing call attempt has tried, in order (the first is the user's selected row). */
internal class RelayCandidates(first: RelayRoute?, val startedMs: Long) {
    private val tried = ArrayList<ByteArray>()

    init {
        first?.let { tried += it.helperPrefix.copyOf() }
    }

    val triedHelpers: List<ByteArray>
        @Synchronized get() = tried.map { it.copyOf() }

    /** Routes tried so far, the current one included. */
    val attempts: Int
        @Synchronized get() = maxOf(1, tried.size)

    @Synchronized
    fun tried(helper: ByteArray) {
        tried += helper.copyOf()
    }
}

/**
 * Automatic candidate iteration inside one call attempt (DECISIONS Design C, deferred item; `test_relayed_candidates`):
 * a relayed route that failed before READY hands over to the next-best route through another helper, at most
 * [RelayedCalls.MAX_CANDIDATES] routes, each started while a full SETUP deadline still fits in
 * [RelayedCalls.CANDIDATE_WINDOW_MS]. A failure after READY is the call's and never moves to another route.
 */
internal object RelayCandidatePolicy {
    fun mayTryNext(enabled: Boolean, attempts: Int, elapsedMs: Long, readyReached: Boolean, stillDialing: Boolean): Boolean =
        enabled && !readyReached && stillDialing && attempts < RelayedCalls.MAX_CANDIDATES &&
            elapsedMs in 0..(RelayedCalls.CANDIDATE_WINDOW_MS - RelayedCalls.SETUP_DEADLINE_MS)

    /** The same target and ID population, through a helper this attempt has not tried. */
    fun acceptable(failed: RelayRoute, next: RelayRoute, triedHelpers: List<ByteArray>): Boolean =
        next.rotating == failed.rotating &&
            next.target.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
                .contentEquals(failed.target.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)) &&
            triedHelpers.none { it.contentEquals(next.helperPrefix) }

    fun nextLine(attempt: Int, route: RelayRoute, elapsedMs: Long, previous: Throwable, debug: Boolean): String =
        (if (debug) "BENCHMARK " else "") +
            "relayed_route: role=caller | attempt=$attempt | helper=${RelayHopPolicy.hex(route.helperPrefix)} | " +
            "target=${RelayHopPolicy.hex(route.target)} | budget=${route.relays} | afterMs=$elapsedMs | " +
            "previousError=${previous.javaClass.simpleName}"
}
