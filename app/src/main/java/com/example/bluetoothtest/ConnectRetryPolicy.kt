package com.example.bluetoothtest

import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom

/**
 * The one absolute deadline of a connection setup, created once per user action (a call dial, a message dial, the CoC phase
 * of a dual attempt, a relay helper's next hop) and passed down explicitly to the LE clients
 * ([GattVideoClient.open], [BleL2capPsmReader.open], [BleCocClient.connect]).
 *
 * Simplification pass A (2026-10-27) replaced three thread-locals with it: `LeSetupBudget` (the ordinary retry rule must
 * leave a whole further attempt before the deadline), `StallRetryDials.within` and `FastEstablishDials.within` (which
 * extra fast-0x3E dials the GATT video client may make before the deadline). The values are the same ones those scopes
 * carried, so every default behaves as before: [ordinaryAtMs] is what `LeSetupBudget` was, [atMs] with [extraDials] is
 * which of the two dial scopes was open and until when. The two can differ: the CoC phase of a dual attempt narrows only
 * the ordinary budget ([narrowed]), exactly as the nested `LeSetupBudget.within` did inside an open dial scope. Clipping a wait to [atMs] happens only under the Experimental unified policy
 * ([ConnectRetryConfig.unified]); by default every per-attempt timeout keeps its own length.
 */
class SetupDeadline(
    /** elapsedRealtime by which the setup must be done (the dial scopes' deadline). */
    val atMs: Long,
    /** The ordinary retry rule needs a whole further attempt to fit before this (the former `LeSetupBudget`); null = unbounded. */
    val ordinaryAtMs: Long? = atMs,
    /** Extra fast-0x3E dials of the GATT video client inside [atMs] (the former `StallRetryDials`/`FastEstablishDials` scopes). */
    val extraDials: ExtraDials = ExtraDials.NONE,
    private val jitter: () -> Long = { (Math.random() * (FastEstablishDials.JITTER_MS + 1)).toLong() },
) {
    enum class ExtraDials { NONE, STALL_RETRY, FAST_FIRST_DIAL }

    fun remainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long = atMs - nowMs

    /** What the ordinary retry rule may still use, or null when it is unbounded (the former `LeSetupBudget.remainingMs`). */
    fun ordinaryRemainingMs(nowMs: Long = SystemClock.elapsedRealtime()): Long? = ordinaryAtMs?.let { it - nowMs }

    /** The earliest bound of this setup: what the Experimental unified policy clips to. */
    val endMs: Long get() = minOf(atMs, ordinaryAtMs ?: atMs)

    fun jitterMs(): Long = jitter()

    /** A narrower cut inside this setup (the CoC phase of a dual attempt): it bounds the ordinary rule; the dial scope stays. */
    fun narrowed(narrowAtMs: Long): SetupDeadline = SetupDeadline(atMs, narrowAtMs, extraDials, jitter)

    override fun toString(): String = "SetupDeadline(atMs=$atMs, ordinaryAtMs=$ordinaryAtMs, extra=$extraDials)"

    companion object {
        /**
         * A call's or message's first dial on its first PHY: [boundsOrdinary] when the 2M -> 1M retry is still possible
         * (the inner retries must leave it room), [fastDials] with the Experimental `test_fast_0x3e_dials`. Null when
         * neither applies (the dial is unbounded, as before).
         */
        fun forFirstDial(earlyLossDeadlineMs: Long, boundsOrdinary: Boolean, fastDials: Boolean): SetupDeadline? =
            if (!boundsOrdinary && !fastDials) {
                null
            } else {
                SetupDeadline(
                    earlyLossDeadlineMs,
                    ordinaryAtMs = if (boundsOrdinary) earlyLossDeadlineMs else null,
                    extraDials = if (fastDials) ExtraDials.FAST_FIRST_DIAL else ExtraDials.NONE,
                )
            }

        /** The 1M retry after a 2M setup stall with `test_stall_retry_dials` (default on): stall dials only. */
        fun forStallRetry(atMs: Long): SetupDeadline =
            SetupDeadline(atMs, ordinaryAtMs = null, extraDials = ExtraDials.STALL_RETRY)
    }
}

/**
 * The connect-retry switches as one configuration (simplification pass A, 2026-10-27). [DEFAULT] is today's shipped
 * behaviour; each Experimental switch is one field and keeps its launch extra and meaning:
 *
 * | field | launch extra | meaning |
 * | --- | --- | --- |
 * | [fastFirstDial] | `test_fast_0x3e_dials` | a call's first GATT dial gets up to 4 jittered fast-0x3E dials ([FastEstablishDials]) |
 * | [fastFailRedialControl] | `test_fast_fail_redial_all` | the BLE control client redials fast 0x3E inside 11 s ([FastFailRedial]) |
 * | [stallRetryDials] | `test_stall_retry_dials` (on) | the 1M retry after a 2M stall gets 4 dials 1.5 s apart ([StallRetryDials]) |
 * | [stallRetryOwnBudget] | `test_stall_retry_own_budget` | that retry gets its own 10 s and no ACL wait ([StallRetryOwnBudget]) |
 * | [setupRetryAclWait] | `test_setup_retry_acl_wait` (on) | that retry waits for the stalled link's ACL ([SetupRetryAclWait]) |
 * | [setupStallMs] | `test_setup_stall_ms` | the first-2M stall guard's arm (0 = [AdaptivePhyMemory.SETUP_STALL_MS]) |
 * | [relayedBudgetFromHopAuth] | `test_relayed_budget_from_hop_auth` | the relayed SETUP budget starts at hop authentication |
 * | [unified] | `test_unified_connect_policy` | the proposed single rule ([UnifiedConnectRule]); replaces the four dial rules above |
 * | [fastFirstRedial] | `test_fast_first_redial` | the ordinary rule's first redial after a fast 133/0x3E waits 200 ms, not 500 ([FastFirstRedial]) |
 *
 * `test_relay_establish_budget` ([RelayEstablishBudget]) stays a per-open request (`establishBudget`), because only relay
 * child connects ask for it; [ConnectAttempts] applies it first, exactly as before, also under [unified].
 */
data class ConnectRetryConfig(
    val fastFirstDial: Boolean = false,
    val fastFailRedialControl: Boolean = false,
    val stallRetryDials: Boolean = true,
    val stallRetryOwnBudget: Boolean = false,
    val setupRetryAclWait: Boolean = true,
    val setupStallMs: Long = 0L,
    val relayedBudgetFromHopAuth: Boolean = false,
    val unified: Boolean = false,
    val fastFirstRedial: Boolean = false,
) {
    /** The first-2M stall guard's arm when the guard is on. */
    fun stallGuardMs(): Long = setupStallMs.takeIf { it > 0L } ?: AdaptivePhyMemory.SETUP_STALL_MS

    companion object {
        val DEFAULT = ConnectRetryConfig()

        fun current(): ConnectRetryConfig = ConnectRetryConfig(
            fastFirstDial = DebugTunables.fastEstablishDials,
            fastFailRedialControl = DebugTunables.fastFailRedialAll,
            stallRetryDials = DebugTunables.stallRetryDials,
            stallRetryOwnBudget = DebugTunables.stallRetryOwnBudget,
            setupRetryAclWait = DebugTunables.setupRetryAclWait,
            setupStallMs = DebugTunables.setupStallMs,
            relayedBudgetFromHopAuth = DebugTunables.relayedBudgetFromHopAuth,
            unified = DebugTunables.unifiedConnectPolicy,
            fastFirstRedial = DebugTunables.fastFirstRedial,
        )
    }
}

/**
 * Experimental (default off, DEBUG `test_unified_connect_policy=true`): the single connect-retry rule proposed by the
 * connection review (DECISIONS "Connection review 2026-10-27: design notes", simplification proposal).
 *
 * - Every LE connect (GATT video and BLE control client: calls, messages, streams, relay hops, any PHY) redials a fast
 *   HCI 0x3E failure ([ConnectDurationClass.isFastEstablishment]) after 300 ms + a random 0-500 ms while the dial still
 *   fits one absolute budget: the caller's [SetupDeadline], never more than [BUDGET_MS] after the first connect.
 * - Any other failure keeps the ordinary class and attempt rule (GATT 2 attempts, control 3, one extended-status retry),
 *   measured against the same budget.
 * - Every wait is clipped to the budget: an attempt's own timeout (GATT 12 s, control 6 s, the CoC socket bound) never
 *   runs past it (but gets at least [MIN_ATTEMPT_MS]), and an attempt that has not reached "connected" after
 *   [CONNECT_PHASE_MS] ends there (an absent peer costs 5.5 s per attempt, not 12 s).
 * - A relay helper's next hop has one budgeted layer: the same-connect retry (`test_relayed_hop_retry`) is off, the client
 *   loop runs inside the SETUP deadline minus [HELPER_REPLY_RESERVE_MS], and the 2M -> 1M retry follows only a link that
 *   connected (a stall or an authentication failure), not a connect failure.
 *
 * The four separate dial rules ([StallRetryDials], [FastEstablishDials], [FastFailRedial], the ordinary unbounded budget)
 * do not apply while this is on. Off: today's rules exactly ([ConnectRetryConfig.DEFAULT]).
 */
internal object UnifiedConnectRule {
    const val BUDGET_MS = 11_000L
    const val CONNECT_PHASE_MS = 5_500L
    const val CONNECT_ROOM_MS = FastFailRedial.CONNECT_ROOM_MS
    const val MIN_ATTEMPT_MS = 2_000L
    const val HELPER_REPLY_RESERVE_MS = 3_000L

    /** The absolute end of one connect's retries: the caller's deadline, at most [BUDGET_MS] after [startedMs]. */
    fun endMs(startedMs: Long, deadline: SetupDeadline?): Long =
        minOf(startedMs + BUDGET_MS, deadline?.endMs ?: Long.MAX_VALUE)

    /** [timeoutMs] clipped to [endMs] (at least [MIN_ATTEMPT_MS]); a non-positive [timeoutMs] means unbounded. */
    fun clipMs(timeoutMs: Long, endMs: Long, nowMs: Long): Long {
        val leftMs = (endMs - nowMs).coerceAtLeast(MIN_ATTEMPT_MS)
        return if (timeoutMs <= 0L) leftMs else minOf(timeoutMs, leftMs)
    }

    fun line(link: String, peerTail: String, attempt: Int, failureClass: ConnectDurationClass?, action: String, delayMs: Long, elapsedMs: Long, leftMs: Long): String =
        "BENCHMARK unified_connect_retry: link=$link | peer=...$peerTail | failedAttempt=$attempt | " +
            "class=${failureClass?.label ?: "none"} | action=$action | delayMs=$delayMs | elapsedMs=$elapsedMs | leftMs=$leftMs"
}

/**
 * One LE client open's retry state and decisions (simplification pass A, 2026-10-27): the rules that the GATT video client
 * and the BLE control client loops used to chain inline, in the same order, as one call. The loops keep only their own
 * side effects (the pool retry's idle-client close, the sleeps, the status notices).
 *
 * Order with [ConnectRetryConfig.DEFAULT]-style configs (unchanged):
 * GATT video: relay establish budget (when requested) -> stall-retry dials -> fast first-dial dials -> ordinary rule.
 * BLE control: relay establish budget (when requested) -> fast-fail redial -> ordinary rule.
 * With [ConnectRetryConfig.unified]: relay establish budget (when requested) -> [UnifiedConnectRule].
 */
internal class ConnectAttempts(
    private val link: Link,
    private val config: ConnectRetryConfig,
    private val deadline: SetupDeadline?,
    private val startedMs: Long,
    establishBudget: Boolean,
    private val peerTail: String,
    private val randomBelow: (Long) -> Long = { bound -> ThreadLocalRandom.current().nextLong(bound) },
) {
    enum class Link(
        val notice: String,
        val budgetLabel: String,
        val maxAttempts: Int,
        val retryDelayMs: Long,
        /** One attempt's own bound, also the [ConnectDurationClass.OWN_DEADLINE] reference. */
        val attemptTimeoutMs: Long,
    ) {
        GATT_VIDEO("GATT video", "gatt", 2, 500L, 12_000L),
        BLE_CONTROL("BLE control", "coc-control", 3, 500L, 6_000L),
    }

    sealed interface Decision {
        val lines: List<String>

        data class Dial(val delayMs: Long, val notice: String, override val lines: List<String>) : Decision

        data class GiveUp(val error: IOException, override val lines: List<String>) : Decision
    }

    var attempt: Int = 1
        private set
    private var extendedRetries = 0
    private val relayBudget = if (establishBudget) RelayEstablishBudget(startedMs, randomBelow = randomBelow) else null
    private val unifiedEndMs = UnifiedConnectRule.endMs(startedMs, deadline)

    /** The pool retry (GATT video only) used an attempt. */
    fun countPoolRetry() {
        attempt += 1
    }

    /** The duration class of a failed attempt that took [attemptElapsedMs]; null when the message has no status. */
    fun classify(message: String?, attemptElapsedMs: Long): ConnectDurationClass? =
        leSetupStatus(message)?.let { ConnectDurationClass.classify(it, attemptElapsedMs, link.attemptTimeoutMs) }

    /** This attempt's own bound: [Link.attemptTimeoutMs], clipped to the budget only under the unified rule. */
    fun attemptTimeoutMs(nowMs: Long): Long =
        if (config.unified) UnifiedConnectRule.clipMs(link.attemptTimeoutMs, unifiedEndMs, nowMs) else link.attemptTimeoutMs

    /** The connect-phase bound (unified rule only), else null. */
    fun connectPhaseMs(): Long? = if (config.unified) UnifiedConnectRule.CONNECT_PHASE_MS else null

    /** The relay establish budget's success line, when it is on. */
    fun successLines(attemptStartedMs: Long, nowMs: Long): List<String> =
        if (relayBudget == null) {
            emptyList()
        } else {
            listOf(RelayEstablishBudget.logLine(link.budgetLabel, attempt, nowMs - attemptStartedMs, null, null, nowMs - startedMs))
        }

    /**
     * After the attempt that started at [attemptStartedMs] failed with [error] ([failureClass] from [classify]):
     * [classifiedRetryable] is the link's ordinary class rule, [linkReached] whether any attempt got a link.
     */
    fun onFailure(
        error: IOException,
        failureClass: ConnectDurationClass?,
        attemptStartedMs: Long,
        nowMs: Long,
        classifiedRetryable: Boolean,
        linkReached: Boolean,
    ): Decision {
        val lines = ArrayList<String>(2)
        relayBudget?.let { budget ->
            val durationMs = nowMs - attemptStartedMs
            val decision = budget.onFailure(error.message, durationMs, nowMs)
            lines += RelayEstablishBudget.logLine(link.budgetLabel, attempt, durationMs, error.message, decision, nowMs - startedMs)
            if (decision.kind != RelayEstablishBudget.Kind.OTHER) {
                val delayMs = decision.retryAfterMs
                    ?: return Decision.GiveUp(EstablishmentBudgetExhausted(error.message, decision.attempt, decision.elapsedMs, error), lines)
                return dial(delayMs, error, "retrying in place", lines)
            }
        }
        if (config.unified) return unified(error, failureClass, nowMs, classifiedRetryable, linkReached, lines)
        when (link) {
            Link.GATT_VIDEO -> {
                // A call's 1M retry after a 2M stall: a fast 0x3E is the peer's old link still going down.
                val stallDeadline = deadline?.takeIf { it.extraDials == SetupDeadline.ExtraDials.STALL_RETRY }?.atMs
                when (val stallDial = StallRetryDials.next(failureClass, attempt, nowMs, stallDeadline)) {
                    is StallRetryDials.Verdict.Dial -> {
                        lines += "BENCHMARK stall_retry_dial: peer=...$peerTail | failedAttempt=$attempt | " +
                            "maxAttempts=${StallRetryDials.MAX_ATTEMPTS} | delayMs=${stallDial.delayMs} | remainingMs=${stallDial.remainingMs}"
                        return dial(stallDial.delayMs, error, "retrying", lines)
                    }
                    // Review T3: out of stall-retry dials or window, the ordinary rule still decides.
                    StallRetryDials.Verdict.Stop -> lines += "BENCHMARK stall_retry_dial: peer=...$peerTail | failedAttempt=$attempt | " +
                        "maxAttempts=${StallRetryDials.MAX_ATTEMPTS} | action=ordinary_rule"
                    StallRetryDials.Verdict.NotApplicable -> Unit
                }
                // Experimental test_fast_0x3e_dials: a call's first dial redials a fast 0x3E with jitter.
                val fastScope = deadline?.takeIf { it.extraDials == SetupDeadline.ExtraDials.FAST_FIRST_DIAL }
                when (val fastDial = FastEstablishDials.next(failureClass, attempt, nowMs, fastScope?.atMs, fastScope?.jitterMs())) {
                    is FastEstablishDials.Verdict.Dial -> {
                        lines += "BENCHMARK fast_0x3e_dial: peer=...$peerTail | failedAttempt=$attempt | " +
                            "maxAttempts=${FastEstablishDials.MAX_ATTEMPTS} | delayMs=${fastDial.delayMs} | remainingMs=${fastDial.remainingMs}"
                        return dial(fastDial.delayMs, error, "retrying", lines)
                    }
                    FastEstablishDials.Verdict.Stop -> {
                        lines += "BENCHMARK fast_0x3e_dial: peer=...$peerTail | failedAttempt=$attempt | " +
                            "maxAttempts=${FastEstablishDials.MAX_ATTEMPTS} | action=give_up"
                        return Decision.GiveUp(carryLinkReached(error, linkReached), lines)
                    }
                    FastEstablishDials.Verdict.NotApplicable -> Unit
                }
            }
            Link.BLE_CONTROL -> {
                // Experimental test_fast_fail_redial_all (review T2/A11).
                FastFailRedial.nextDelayMs(
                    config.fastFailRedialControl, failureClass, nowMs - startedMs, deadline?.ordinaryRemainingMs(nowMs),
                    random = { from, until -> from + randomBelow(until - from) },
                )?.let { delayMs ->
                    lines += FastFailRedial.line("ble-control", peerTail, attempt, delayMs, nowMs - startedMs)
                    return dial(delayMs, error, "retrying connection", lines)
                }
            }
        }
        val allowed = leSetupRetryAllowed(
            message = error.message,
            attemptsUsed = attempt,
            maxAttempts = link.maxAttempts,
            classifiedRetryable = classifiedRetryable,
            extendedRetriesUsed = extendedRetries,
            nextAttemptMs = link.retryDelayMs + link.attemptTimeoutMs,
            remainingBudgetMs = deadline?.ordinaryRemainingMs(nowMs),
        )
        // test_fast_first_redial (promoted 2026-11-12, default on): only the first attempt's fast 133/0x3E, only where the ordinary rule redials.
        val fastFirstMs = if (allowed) {
            FastFirstRedial.delayMs(config.fastFirstRedial, attempt, leSetupStatus(error.message), nowMs - attemptStartedMs)
        } else {
            null
        }
        if (fastFirstMs != null) {
            lines += FastFirstRedial.line(link.budgetLabel, peerTail, nowMs - attemptStartedMs, fastFirstMs, link.retryDelayMs)
        }
        return ordinary(allowed, error, linkReached, lines, fastFirstMs ?: link.retryDelayMs)
    }

    private fun unified(
        error: IOException,
        failureClass: ConnectDurationClass?,
        nowMs: Long,
        classifiedRetryable: Boolean,
        linkReached: Boolean,
        lines: MutableList<String>,
    ): Decision {
        val leftMs = unifiedEndMs - nowMs
        val elapsedMs = nowMs - startedMs
        if (failureClass == ConnectDurationClass.FAST_ESTABLISH_FAILURE) {
            val delayMs = FastEstablishDials.delayMs(randomBelow(FastEstablishDials.JITTER_MS + 1))
            if (delayMs + UnifiedConnectRule.CONNECT_ROOM_MS <= leftMs) {
                lines += UnifiedConnectRule.line(link.budgetLabel, peerTail, attempt, failureClass, "dial", delayMs, elapsedMs, leftMs)
                return dial(delayMs, error, "retrying", lines)
            }
            lines += UnifiedConnectRule.line(link.budgetLabel, peerTail, attempt, failureClass, "give_up", 0L, elapsedMs, leftMs)
            return Decision.GiveUp(carryLinkReached(error, linkReached), lines)
        }
        val allowed = leSetupRetryAllowed(
            message = error.message,
            attemptsUsed = attempt,
            maxAttempts = link.maxAttempts,
            classifiedRetryable = classifiedRetryable,
            extendedRetriesUsed = extendedRetries,
            // Attempts are clipped to the budget, so a further one needs only its delay and the shortest useful connect.
            nextAttemptMs = link.retryDelayMs + UnifiedConnectRule.CONNECT_ROOM_MS,
            remainingBudgetMs = leftMs,
        )
        lines += UnifiedConnectRule.line(
            link.budgetLabel, peerTail, attempt, failureClass, if (allowed) "ordinary" else "give_up",
            if (allowed) link.retryDelayMs else 0L, elapsedMs, leftMs,
        )
        return ordinary(allowed, error, linkReached, lines)
    }

    private fun ordinary(
        allowed: Boolean,
        error: IOException,
        linkReached: Boolean,
        lines: List<String>,
        delayMs: Long = link.retryDelayMs,
    ): Decision {
        if (!allowed) return Decision.GiveUp(carryLinkReached(error, linkReached), lines)
        if (leSetupStatus(error.message)?.let { it != BluetoothTransportPolicy.GATT_ERROR_133 } == true) extendedRetries++
        val notice = when {
            link == Link.BLE_CONTROL && error.message == BLE_CONTROL_READ_TIMEOUT -> "BLE control did not respond; retrying connection"
            link == Link.BLE_CONTROL -> leSetupRetryNotice(link.notice, error.message, "retrying connection")
            else -> leSetupRetryNotice(link.notice, error.message, "retrying once")
        }
        attempt += 1
        return Decision.Dial(delayMs, notice, lines)
    }

    private fun dial(delayMs: Long, error: IOException, retrying: String, lines: List<String>): Decision {
        attempt += 1
        return Decision.Dial(delayMs, leSetupRetryNotice(link.notice, error.message, retrying), lines)
    }
}

/**
 * `test_fast_first_redial` (promoted 2026-11-12 on mechanism, default on; join-latency P4a). The ordinary rule waits [ConnectAttempts.Link.retryDelayMs]
 * (500 ms) before every redial. That spacing is for a peer whose old link is still going down; on the FIRST dial of an
 * open there is no old link, and a GATT 133 / HCI 0x3E that comes back within the shared fast-establishment class
 * ([ConnectDurationClass.isFastEstablishment], 1.5 s; 0.40-0.45 s in rem-u/rem-s, 1.326 s in a JT2 first connect: the
 * peer missed the connect request) is redialed after [DELAY_MS]. (Until 2026-11-10 this rule had its own 1.0 s window.) Later attempts, slow failures and every other
 * status keep 500 ms; the attempt count and budget rules are unchanged. Line `BENCHMARK fast_first_redial`.
 */
internal object FastFirstRedial {
    const val DELAY_MS = 200L

    /** The redial delay for this failure, or null when the ordinary delay applies. */
    fun delayMs(enabled: Boolean, attempt: Int, status: Int?, attemptElapsedMs: Long): Long? =
        if (enabled && attempt == 1 && attemptElapsedMs >= 0 && ConnectDurationClass.isFastEstablishment(status, attemptElapsedMs)) {
            DELAY_MS
        } else {
            null
        }

    fun line(link: String, peerTail: String, attemptElapsedMs: Long, delayMs: Long, ordinaryDelayMs: Long): String =
        "BENCHMARK fast_first_redial: link=$link | peer=...$peerTail | failedAttempt=1 | attemptMs=$attemptElapsedMs | " +
            "delayMs=$delayMs | ordinaryDelayMs=$ordinaryDelayMs"
}
