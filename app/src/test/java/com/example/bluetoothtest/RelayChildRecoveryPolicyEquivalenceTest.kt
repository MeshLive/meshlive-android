package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * RelayChildRecoveryPolicy (switch audit F3) must reproduce what the scattered call sites did. [Legacy] is a transcription of
 * the relay-side code it replaced (ReceiverStreamForwarder.deferChildSighting, the start of connectChild, the stall guard's
 * close, the two success points and the failure block, as of integration/20261027), switch by switch. Every combination of
 * the eight relay-side switches replays the same random event sequences through both, each with its own stores, and every
 * decision must match: the dial verdicts and hold-off times, the classification, the tracker marks and mark lengths, the
 * count-stall line and the PHY after the failure. The one intended difference is named in [phyMatches].
 */
class RelayChildRecoveryPolicyEquivalenceTest {
    private data class Switches(
        val stallSeparate: Boolean,
        val stallHoldoff: Boolean,
        val refusalEscalation: Boolean,
        val establishFallback: Boolean,
        val establishBudget: Boolean,
        val holdInPlace: Boolean,
        val keep2M: Boolean,
        val phySetupStall: Boolean,
    ) {
        fun config() = RelayRecoveryConfig.DEFAULTS.copy(
            stallSeparate = stallSeparate,
            stallHoldoff = stallHoldoff,
            refusalEscalation = refusalEscalation,
            establishFallback = establishFallback,
            establishBudget = establishBudget,
            holdInPlace = holdInPlace,
            keep2M = keep2M,
            phySetupStall = phySetupStall,
        )
    }

    private data class Failure(
        val kind: RelayChildFailureKind,
        val marked: Boolean,
        val markMs: Long,
        val countStallLine: Boolean,
        val phy: RelayChildSetupPhy,
    )

    /** The earlier call-site logic, with its own stores. */
    private class Legacy(val sw: Switches) {
        val tracker = EstablishmentFailureTracker()
        val holdoff = RelayStallHoldoff()
        val refusalHoldoff = RelayStallHoldoff()

        private fun anyOn() = sw.stallHoldoff || sw.refusalEscalation

        private fun remainingMs(key: String, nowMs: Long) = maxOf(
            if (sw.stallHoldoff) holdoff.remainingMs(key, nowMs) else 0L,
            if (sw.refusalEscalation) refusalHoldoff.remainingMs(key, nowMs) else 0L,
        )

        /** deferChildSighting behind `if (DebugTunables.relayHoldInPlace && ...)`: null = not deferred. */
        fun sighting(key: String, nowMs: Long): String? {
            if (!sw.holdInPlace) return null
            val heldMs = remainingMs(key, nowMs)
            val verdict = RelayChildDialGate.verdict(
                fallbackOn = sw.establishFallback,
                marked = sw.establishFallback && tracker.isMarked(key, nowMs),
                holdoffOn = anyOn(),
                heldMs = heldMs,
            )
            return when (verdict) {
                RelayChildDialGate.Verdict.DIAL -> null
                RelayChildDialGate.Verdict.DEFER_MARKED -> "marked"
                RelayChildDialGate.Verdict.DEFER_HOLDOFF -> "holdoff:$heldMs"
            }
        }

        /** The start of connectChild: null = dial. */
        fun connect(key: String, nowMs: Long): String? {
            if (sw.establishFallback && tracker.isMarked(key, nowMs)) return "marked"
            if (anyOn()) {
                val heldMs = remainingMs(key, nowMs)
                if (heldMs > 0L) return "holdoff:$heldMs"
            }
            return null
        }

        fun stallClosed(key: String, nowMs: Long) = holdoff.onStall(key, nowMs)

        fun hopAuthenticated(key: String) = tracker.onSuccess(key)

        fun handshakeComplete(key: String) {
            holdoff.onSuccess(key)
            refusalHoldoff.onSuccess(key)
        }

        fun failed(
            key: String,
            nowMs: Long,
            hopAuthenticated: Boolean,
            message: String?,
            stalled: Boolean,
            budgetError: Boolean,
            attemptPhy: BlePhyPreference?,
            reached: Boolean,
        ): Failure {
            val failureKind = classifyRelayChildFailure(hopAuthenticated, message, stalled, sw.stallSeparate)
            var marked = false
            var markMs = 0L
            var countStall = false
            if (sw.establishFallback && failureKind != RelayChildFailureKind.NONE) {
                val stallKind = failureKind == RelayChildFailureKind.STALL
                marked = if (stallKind) {
                    tracker.onStall(key, nowMs)
                } else {
                    if (sw.refusalEscalation) refusalHoldoff.onStall(key, nowMs)
                    tracker.onFailure(key, nowMs, sw.establishBudget || sw.refusalEscalation)
                }
                if (marked) markMs = tracker.remainingMs(key, nowMs) else if (stallKind) countStall = true
            }
            val budgetExhausted = sw.establishBudget && budgetError
            val phy = if (budgetExhausted) {
                RelayChildSetupPhy.UNCHANGED
            } else {
                relayChildSetupPhyDecision(sw.phySetupStall, attemptPhy, hopAuthenticated, sw.keep2M, reached)
            }
            return Failure(failureKind, marked, markMs, countStall, phy)
        }
    }

    private class Current(sw: Switches) {
        val policy = RelayChildRecoveryPolicy(sw.config(), EstablishmentFailureTracker(), RelayStallHoldoff(), RelayStallHoldoff())

        private fun label(decision: RelayChildRecoveryPolicy.DialDecision): String? = when (decision.verdict) {
            RelayChildDialGate.Verdict.DIAL -> null
            RelayChildDialGate.Verdict.DEFER_MARKED -> "marked"
            RelayChildDialGate.Verdict.DEFER_HOLDOFF -> "holdoff:${decision.heldMs}"
        }

        fun sighting(key: String, nowMs: Long): String? =
            if (!policy.config.holdInPlace) null else label(policy.dialDecision(key, nowMs))

        fun connect(key: String, nowMs: Long): String? = label(policy.dialDecision(key, nowMs))

        fun failed(
            key: String,
            nowMs: Long,
            hopAuthenticated: Boolean,
            message: String?,
            stalled: Boolean,
            budgetError: Boolean,
            attemptPhy: BlePhyPreference?,
            reached: Boolean,
        ): Failure {
            val outcome = policy.onAttemptFailed(key, nowMs, hopAuthenticated, message, stalled, budgetError, attemptPhy, reached)
            return Failure(
                outcome.kind,
                outcome.marked,
                outcome.markMs,
                outcome.counted && outcome.kind == RelayChildFailureKind.STALL && !outcome.marked,
                outcome.setupPhy,
            )
        }
    }

    /**
     * The one intended change (B1): an exhausted establishment budget used to skip the PHY decision silently; it now takes
     * the shared pre-link rule, which keeps 2M as before and logs it. The PHY the child gets is the same in every case.
     */
    private fun phyMatches(sw: Switches, budgetError: Boolean, legacy: RelayChildSetupPhy, current: RelayChildSetupPhy): Boolean =
        legacy == current || (
            sw.establishBudget && budgetError && legacy == RelayChildSetupPhy.UNCHANGED &&
                current == RelayChildSetupPhy.KEEP_2M_ESTABLISHMENT
            )

    private val messages = listOf(
        null,
        "GATT video connection failed (status 133)",
        "GATT video receiver disconnected (status 8)",
        "BLE control connection failed (status 62)",
        "GATT video receiver disconnected (status 133)",
        "Downstream flush failed",
        "Could not establish the hop",
        "Hop handshake ended: EOF",
    )
    private val keys = listOf("a15a15a15a15", "7a7a00stall0a4")
    private val phys = listOf(null, BlePhyPreference.TWO_M, BlePhyPreference.ONE_M_ADAPTIVE)

    private fun allSwitches(): List<Switches> = (0 until 256).map { bits ->
        fun bit(i: Int) = bits and (1 shl i) != 0
        Switches(bit(0), bit(1), bit(2), bit(3), bit(4), bit(5), bit(6), bit(7))
    }

    @Test
    fun everySwitchCombinationDecidesExactlyAsTheEarlierCallSites() {
        var budgetPhyDifferences = 0
        for (sw in allSwitches()) {
            for (seed in 0 until 12) {
                val random = Random(seed * 7_919 + 13)
                val legacy = Legacy(sw)
                val current = Current(sw)
                var nowMs = 1_000L
                repeat(120) { step ->
                    nowMs += random.nextLong(0L, 25_000L)
                    val key = keys[random.nextInt(keys.size)]
                    val where = "switches=$sw seed=$seed step=$step t=$nowMs key=$key"
                    when (random.nextInt(7)) {
                        0 -> assertEquals(where, legacy.sighting(key, nowMs), current.sighting(key, nowMs))
                        1 -> assertEquals(where, legacy.connect(key, nowMs), current.connect(key, nowMs))
                        2 -> {
                            legacy.stallClosed(key, nowMs)
                            current.policy.onStallClosed(key, nowMs)
                        }
                        3 -> {
                            legacy.hopAuthenticated(key)
                            current.policy.onHopAuthenticated(key)
                        }
                        4 -> {
                            legacy.handshakeComplete(key)
                            current.policy.onHandshakeComplete(key)
                        }
                        else -> {
                            val hopAuthenticated = random.nextInt(4) == 0
                            val message = messages[random.nextInt(messages.size)]
                            val stalled = random.nextBoolean()
                            val budgetError = random.nextInt(3) == 0
                            val phy = phys[random.nextInt(phys.size)]
                            val reached = random.nextBoolean()
                            val old = legacy.failed(key, nowMs, hopAuthenticated, message, stalled, budgetError, phy, reached)
                            val new = current.failed(key, nowMs, hopAuthenticated, message, stalled, budgetError, phy, reached)
                            assertEquals(where, old.copy(phy = RelayChildSetupPhy.UNCHANGED), new.copy(phy = RelayChildSetupPhy.UNCHANGED))
                            assertTrue("$where phy ${old.phy} -> ${new.phy}", phyMatches(sw, budgetError, old.phy, new.phy))
                            if (old.phy != new.phy) budgetPhyDifferences++
                            // The PHY the child gets (pinned to 1M or not) never differs.
                            assertEquals(where, old.phy == RelayChildSetupPhy.PIN_1M, new.phy == RelayChildSetupPhy.PIN_1M)
                        }
                    }
                }
            }
        }
        // The replay reached the one intended difference (so the comparison above is not vacuous for the budget arm).
        assertTrue(budgetPhyDifferences > 0)
    }

    @Test
    fun theDefaultsAreTheShippedConfigurationAndDecideWithoutAnyDifference() {
        assertEquals(RelayRecoveryConfig.DEFAULTS, RelayRecoveryConfig.current())
        val sw = Switches(
            stallSeparate = true, stallHoldoff = true, refusalEscalation = true, establishFallback = true,
            establishBudget = false, holdInPlace = true, keep2M = false, phySetupStall = true,
        )
        assertEquals(RelayRecoveryConfig.DEFAULTS, sw.config())
        val legacy = Legacy(sw)
        val current = Current(sw)
        val random = Random(42)
        var nowMs = 0L
        repeat(5_000) {
            nowMs += random.nextLong(0L, 8_000L)
            val key = keys[random.nextInt(keys.size)]
            val message = messages[random.nextInt(messages.size)]
            val stalled = random.nextBoolean()
            val phy = phys[random.nextInt(phys.size)]
            val reached = random.nextBoolean()
            assertEquals(legacy.connect(key, nowMs), current.connect(key, nowMs))
            // Bit for bit: no allowance for the budget arm here (it is off).
            assertEquals(
                legacy.failed(key, nowMs, false, message, stalled, true, phy, reached),
                current.failed(key, nowMs, false, message, stalled, true, phy, reached),
            )
        }
    }

    // ---- child side: the split of relay_child_reanchor (B2)

    @After
    fun restoreSwitches() {
        DebugTunables.relayChildReanchor = true
        DebugTunables.relayParentTrickle = true
        DebugTunables.relayRecoveryGrace = true
        DebugTunables.relayParentWatch = null
        DebugTunables.relayPreferParent = false
    }

    @Test
    fun withTheNewSwitchesAbsentTheWatchFollowsRelayChildReanchorAndThePreferenceIsOff() {
        // These set DEBUG control arms; theDefaultsAreTheShippedConfiguration... covers a release build.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        for (reanchor in listOf(true, false)) for (trickle in listOf(true, false)) for (grace in listOf(true, false)) {
            DebugTunables.relayChildReanchor = reanchor
            DebugTunables.relayParentTrickle = trickle
            DebugTunables.relayRecoveryGrace = grace
            val config = RelayRecoveryConfig.current()
            val where = "reanchor=$reanchor trickle=$trickle grace=$grace"
            // Before: the watch thread ran under relay_child_reanchor and judged the trickle only inside it.
            assertEquals(where, reanchor, config.parentWatch)
            assertEquals(where, reanchor && trickle, config.parentTrickle)
            assertEquals(where, reanchor, config.starvationClose)
            // Build 4: the preference is off by default (it used relay_child_reanchor before), so neither it nor the grace acts.
            assertFalse(where, config.preferParent)
            assertFalse(where, config.recoveryGrace)
            // test_relay_prefer_parent=true brings back what relay_child_reanchor=true gave before.
            val oldPreferred = recoveryParentPreference(true, true, 1, prefix, enabled = true)
            DebugTunables.relayPreferParent = true
            val restored = RelayRecoveryConfig.current()
            DebugTunables.relayPreferParent = false
            val newPreferred = recoveryParentPreference(true, true, 1, prefix, enabled = restored.preferParent)
            assertEquals(where, oldPreferred?.toList(), newPreferred?.toList())
            assertEquals(
                where,
                preferredParentGraceDeadline(oldPreferred, 1_000L, grace),
                preferredParentGraceDeadline(newPreferred, 1_000L, restored.recoveryGrace),
            )
            assertNull(where, recoveryParentPreference(true, true, 1, prefix, enabled = config.preferParent))
        }
    }

    @Test
    fun theChildSideWatchAndPreferenceCanNowBeTestedApartFromTheRelaySideClose() {
        // These set DEBUG control arms; theDefaultsAreTheShippedConfiguration... covers a release build.
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        DebugTunables.relayChildReanchor = false
        DebugTunables.relayParentWatch = true
        DebugTunables.relayPreferParent = true
        val apart = RelayRecoveryConfig.current()
        assertFalse(apart.starvationClose)
        assertTrue(apart.parentWatch)
        assertTrue(apart.parentTrickle)
        assertTrue(apart.recoveryGrace)
        DebugTunables.relayChildReanchor = true
        DebugTunables.relayParentWatch = false
        DebugTunables.relayPreferParent = false
        val relayOnly = RelayRecoveryConfig.current()
        assertTrue(relayOnly.starvationClose)
        assertFalse(relayOnly.parentWatch)
        assertFalse(relayOnly.parentTrickle)
        assertFalse(relayOnly.recoveryGrace)
    }

    @Test
    fun aBudgetExhaustedFailureNowSharesThePreLinkRuleWithKeep2M() {
        val budget = RelayRecoveryConfig.DEFAULTS.copy(establishBudget = true)
        // Exhausted budget on a 2M attempt before hop authentication: keeps 2M (as before), now as the shared decision.
        assertEquals(
            RelayChildSetupPhy.KEEP_2M_ESTABLISHMENT,
            RelayChildRecoveryPolicy.setupPhyAfterFailure(budget, BlePhyPreference.TWO_M, false, budgetExhausted = true, reachedConnection = true),
        )
        // The same error with the budget off is an ordinary failure and pins 1M after a link existed, as before.
        assertEquals(
            RelayChildSetupPhy.PIN_1M,
            RelayChildRecoveryPolicy.setupPhyAfterFailure(
                RelayRecoveryConfig.DEFAULTS, BlePhyPreference.TWO_M, false, budgetExhausted = true, reachedConnection = true,
            ),
        )
        // Keep-2M alone: a failure before any link keeps 2M, one after a link pins 1M.
        val keep = RelayRecoveryConfig.DEFAULTS.copy(keep2M = true)
        assertEquals(
            RelayChildSetupPhy.KEEP_2M_ESTABLISHMENT,
            RelayChildRecoveryPolicy.setupPhyAfterFailure(keep, BlePhyPreference.TWO_M, false, false, reachedConnection = false),
        )
        assertEquals(
            RelayChildSetupPhy.PIN_1M,
            RelayChildRecoveryPolicy.setupPhyAfterFailure(keep, BlePhyPreference.TWO_M, false, false, reachedConnection = true),
        )
    }
}
