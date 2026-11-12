package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch P entries: the strict metadata rules, the matchers, the off switches and the switch defaults. */
class PhyLinkPolicyTest {
    private val entries = PhyLinkPolicy.entries

    @Test
    fun everyEntryMeetsTheStrictMetadataRules() {
        val problems = entries.flatMap { entry ->
            DevicePolicyEntryRules.violations(entry, strict = true).map { "${entry.id}: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
    }

    @Test
    fun theOffSwitchOfEveryEntryIsTheStandardOne() {
        entries.forEach { assertEquals("test_device_policy_off=${it.id}", it.offSwitch) }
    }

    @Test
    fun entriesThatChangeBehaviourAreProposalsAndTheShippedOnesAreDiagnosticsOrBackstops() {
        val byId = entries.associateBy { it.id }
        listOf(
            "ll-procedure-collision-serialise",
            "link-lost-after-change-rollback",
            "le-priority-interval-reassert",
            "coded-phy-confirmed-callback-only",
            "qualcomm-wcn399x-dual-role-unvalidated",
        ).forEach { id ->
            val entry = requireNotNull(byId[id]) { id }
            assertTrue("$id is inert until promoted", entry.proposed && entry.effects.isEmpty())
        }
        listOf(PhyLinkPolicy.ANDROID13_BACKSTOP_ID, PhyLinkPolicy.PRIORITY_LOG_ID, PhyLinkPolicy.DUAL_ROLE_PROBE_ID).forEach { id ->
            val entry = requireNotNull(byId[id]) { id }
            assertFalse(entry.proposed)
            assertTrue(entry.effects.isNotEmpty())
        }
    }

    @Test
    fun theExperimentalProposalsNameTheirSwitches() {
        val byId = entries.associateBy { it.id }
        assertTrue("test_ll_gate" in byId.getValue("ll-procedure-collision-serialise").behaviour)
        assertTrue("test_link_change_rollback" in byId.getValue("link-lost-after-change-rollback").behaviour)
        assertTrue("test_priority_reassert" in byId.getValue("le-priority-interval-reassert").behaviour)
    }

    @Test
    fun theAndroid13BackstopMatchesExactlySdk33() {
        val entry = PhyLinkPolicy.ANDROID13_PHY_CALLBACK_BACKSTOP
        listOf(30 to false, 32 to false, 33 to true, 34 to false, 36 to false).forEach { (sdk, expected) ->
            assertEquals("sdk $sdk", expected, entry.matcher.matches(DeviceTraits("qcom", "taro", sdk)))
        }
    }

    @Test
    fun theBackstopActivityFollowsTheSdkAndTheOffSwitch() {
        assertTrue(PhyLinkPolicy.phyCallbackBackstopActive(33, emptySet()))
        assertFalse(PhyLinkPolicy.phyCallbackBackstopActive(34, emptySet()))
        assertFalse(PhyLinkPolicy.phyCallbackBackstopActive(33, setOf("android13-phy-update-callback-backstop")))
        assertFalse(PhyLinkPolicy.phyCallbackBackstopActive(33, setOf("all")))
        assertTrue(PhyLinkPolicy.dualRoleProbeLogActive(emptySet()))
        assertFalse(PhyLinkPolicy.dualRoleProbeLogActive(setOf("dual-role-probe-log")))
        assertTrue(PhyLinkPolicy.prioritySupervisionLogActive(emptySet()))
        assertFalse(PhyLinkPolicy.prioritySupervisionLogActive(setOf("all")))
    }

    @Test
    fun theQualcommDualRoleHintMatchesTheAsusBoardAndNotTheOtherPhones() {
        val entry = PhyLinkPolicy.QUALCOMM_WCN399X_DUAL_ROLE_UNVALIDATED
        assertTrue(entry.matcher.matches(DeviceTraits("qcom", "msmnile", 30)))
        assertFalse("the S22 (taro) has a validated-group chip", entry.matcher.matches(DeviceTraits("qcom", "taro", 34)))
        assertFalse(entry.matcher.matches(DeviceTraits("exynos9611", "universal9611", 31)))
        assertFalse("the Exynos 9611 entry stays its own", entry.matcher.matches(DeviceTraits("mt6765", "mt6765", 29)))
    }

    @Test
    fun theSerialiseProposalAppliesToEveryPhoneWithA2MController() {
        val entry = PhyLinkPolicy.LL_PROCEDURE_COLLISION_SERIALISE
        val with2m = DeviceTraits(
            "qcom", "taro", 34,
            controller = BluetoothControllerTraits(true, false, true, false, 1650, true, true),
        )
        val without = with2m.copy(controller = with2m.controller!!.copy(le2mPhy = false))
        assertTrue(entry.matcher.matches(with2m))
        assertFalse(entry.matcher.matches(without))
        assertFalse("an unread controller never satisfies a constraint", entry.matcher.matches(with2m.copy(controller = null)))
    }

    @Test
    fun noSourceIsTheExynos9611EntryOrTheDckDefault() {
        // Batch P leaves the M31 entry and the DCK arm alone: its entries never reuse their ids.
        assertFalse(entries.any { it.id == "exynos9611-dual-role-equal-interval-child-starvation" })
        assertFalse(PhySwitches.priorityReassert)
        assertFalse("DCK default is held off pending a full-chain retest (Q48 hold)", DebugTunables.relayUpstreamDck)
    }

    @Test
    fun theSwitchesAreAllOffByDefaultAndFollowTheLaunchFlags() {
        assertFalse(PhySwitches.llGate)
        assertFalse(PhySwitches.linkChangeRollback)
        assertFalse(PhySwitches.priorityReassert)
        try {
            PhySwitches.applyFlags { it == "test_ll_gate" || it == "test_priority_reassert" }
            // A release build ignores the launch flags: every experiment stays off.
            assertEquals(BuildConfig.DEBUG, PhySwitches.llGate)
            assertFalse(PhySwitches.linkChangeRollback)
            assertEquals(BuildConfig.DEBUG, PhySwitches.priorityReassert)
        } finally {
            PhySwitches.applyFlags { false }
        }
        assertFalse(PhySwitches.llGate)
    }
}
