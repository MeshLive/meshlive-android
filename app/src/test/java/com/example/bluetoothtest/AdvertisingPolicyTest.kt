package com.example.bluetoothtest

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The batch A policy entries, switches and the no-gating guard (AdvertisingPolicy.kt, AdvertisingSwitches.kt). */
class AdvertisingPolicyTest {
    @After
    fun restore() {
        DebugTunables.devicePolicyOff = emptySet()
        AdvertisingSwitches.reset()
    }

    @Test
    fun everyEntryMeetsTheStrictRules() {
        val problems = AdvertisingPolicy.entries.flatMap { entry ->
            DevicePolicyEntryRules.violations(entry, strict = true).map { "${entry.id}: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun entryIdsAreUniqueAndEachIsListedExactlyOnceInTheRegistry() {
        val ids = AdvertisingPolicy.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val registry = DeviceFamilyPolicy.entries.map { it.id }
        ids.forEach { id -> assertEquals(id, 1, registry.count { it == id }) }
    }

    @Test
    fun everyEntryNamesItsSourcesConfidenceAndOffSwitch() {
        AdvertisingPolicy.entries.forEach { entry ->
            assertTrue(entry.id, entry.sources.isNotEmpty())
            assertTrue(entry.id, entry.confidenceNote.isNotBlank())
            assertTrue(entry.id, entry.fallback.isNotBlank())
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertTrue(entry.id, entry.matcher.universal || entry.proposed)
        }
    }

    @Test
    fun theSlotLeakMitigationIsAProposalForAndroid15OnlyAndShipsAsAnExperimentalSwitch() {
        val entry = AdvertisingPolicy.A15_QUIESCE
        assertTrue(entry.proposed)
        assertTrue(entry.effects.isEmpty())
        assertTrue(entry.matcher.matches(DeviceTraits("qcom", "taro", 35)))
        assertFalse(entry.matcher.matches(DeviceTraits("qcom", "taro", 34)))
        assertFalse(entry.matcher.matches(DeviceTraits("qcom", "taro", 36)))
        assertTrue("test_adv_quiesce_stop" in entry.behaviour)
        assertFalse("off by default", AdvertisingSwitches.quiesceStop)
    }

    @Test
    fun theLowConfidenceEntriesSayWhy() {
        val stuck = AdvertisingPolicy.STUCK_REPORT
        assertEquals(PolicyConfidence.LOW, stuck.confidence)
        assertEquals(PolicySourceStatus.PARTIAL, stuck.sourceStatus)
        assertTrue(stuck.confidenceNote.contains("third-party"))
    }

    @Test
    fun theOffSwitchAndTheAllSwitchTurnTheBehaviourOff() {
        assertTrue(AdvertisingPolicy.nonThrowingStart())
        assertTrue(AdvertisingPolicy.budgetOn())
        assertTrue(AdvertisingPolicy.preemptOn())
        assertTrue(AdvertisingPolicy.stopSettleOn())
        assertTrue(AdvertisingPolicy.watchdogOn())
        assertTrue(AdvertisingPolicy.stuckReportOn())

        if (!BuildConfig.DEBUG) {
            // test_device_policy_off is DEBUG-only: a release build always keeps every entry.
            DebugTunables.devicePolicyOff = setOf("all")
            assertTrue(AdvertisingPolicy.watchdogOn() && AdvertisingPolicy.budgetOn() && AdvertisingPolicy.nonThrowingStart())
            return
        }
        DebugTunables.devicePolicyOff = setOf(AdvertisingPolicy.ID_WATCHDOG)
        assertFalse(AdvertisingPolicy.watchdogOn())
        assertTrue("only the named entry", AdvertisingPolicy.budgetOn())

        DebugTunables.devicePolicyOff = setOf(AdvertisingPolicy.ID_BUDGET)
        assertFalse(AdvertisingPolicy.budgetOn())
        assertFalse(AdvertisingPolicy.preemptOn())
        assertFalse(AdvertisingPolicy.stopSettleOn())
        assertTrue(AdvertisingPolicy.nonThrowingStart())

        DebugTunables.devicePolicyOff = setOf(AdvertisingPolicy.ID_NON_THROWING_START)
        assertFalse(AdvertisingPolicy.nonThrowingStart())

        DebugTunables.devicePolicyOff = setOf("all")
        assertFalse(AdvertisingPolicy.nonThrowingStart())
        assertFalse(AdvertisingPolicy.budgetOn())
        assertFalse(AdvertisingPolicy.watchdogOn())
        assertFalse(AdvertisingPolicy.stuckReportOn())
    }

    @Test
    fun theKillSwitchesTurnTheShippedArmsOffButNeverTheExperimentsOn() {
        val extras = mutableMapOf<String, Any>()
        val bool = { name: String, default: Boolean -> extras[name] as? Boolean ?: default }
        val int = { name: String, default: Int -> extras[name] as? Int ?: default }

        AdvertisingSwitches.applyExtras(bool, int)
        assertTrue(AdvertisingSwitches.watchdogRestart)
        assertTrue(AdvertisingSwitches.budgetPreempt)
        assertTrue(AdvertisingSwitches.stopSettle)
        assertEquals("no simulated cap by default", 0, AdvertisingSwitches.budgetCap)
        assertFalse(AdvertisingSwitches.quiesceStop)

        extras["test_adv_watchdog"] = false
        extras["test_adv_preempt"] = false
        extras["test_adv_stop_settle"] = false
        extras["test_adv_budget_cap"] = 1
        extras["test_adv_quiesce_stop"] = true
        AdvertisingSwitches.applyExtras(bool, int)
        if (!BuildConfig.DEBUG) {
            // A release build ignores launch extras: the shipped arms stay on and the experiments stay off.
            assertTrue(AdvertisingPolicy.watchdogOn() && AdvertisingPolicy.preemptOn() && AdvertisingPolicy.stopSettleOn())
            assertEquals(0, AdvertisingSwitches.budgetCap)
            assertFalse(AdvertisingSwitches.quiesceStop)
            return
        }
        assertFalse(AdvertisingPolicy.watchdogOn())
        assertFalse(AdvertisingPolicy.preemptOn())
        assertFalse(AdvertisingPolicy.stopSettleOn())
        assertEquals(1, AdvertisingSwitches.budgetCap)
        assertTrue(AdvertisingSwitches.quiesceStop)

        extras["test_adv_budget_cap"] = 99
        AdvertisingSwitches.applyExtras(bool, int)
        assertEquals("out-of-range caps are ignored", 0, AdvertisingSwitches.budgetCap)
        extras["test_adv_budget_cap"] = 0
        AdvertisingSwitches.applyExtras(bool, int)
        assertEquals(0, AdvertisingSwitches.budgetCap)

        AdvertisingSwitches.reset()
        assertTrue(AdvertisingSwitches.watchdogRestart && AdvertisingSwitches.budgetPreempt && AdvertisingSwitches.stopSettle)
        assertFalse(AdvertisingSwitches.quiesceStop)
    }

    @Test
    fun advertisingIsNeverGatedOnTheMultipleAdvertisementFlag() {
        val root = File("src/main/java/com/example/bluetoothtest").takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/example/bluetoothtest")
        val files = listOf(
            "StableAdvertisingSet.kt", "ReachAdvertiser.kt", "TitleAdvertiser.kt", "BleDiscoveryAdvertiser.kt",
            "PeerLabelPolicy.kt", "AdvertisingBudget.kt", "AdvertisingWatchdog.kt", "AdvertisingSwitches.kt",
        )
        val offenders = files.flatMap { name ->
            File(root, name).readLines().withIndex().filter { (_, line) ->
                val code = line.trim()
                !code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") &&
                    "isMultipleAdvertisementSupported" in code && "multiAdv=" !in code && "https://" !in code
            }.map { "$name:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals("the flag may only appear in a log line:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun theBatchAddsNoWireOrAdvertisementFormatChange() {
        // The service UUID stays the only primary data of every set; the pages keep their sizes (AdvertisingPayloadTest).
        assertEquals(22, DiscoveryAnnouncementCodec.ENCODED_BYTES)
        assertEquals(8, TitlePageCodec.HEADER_BYTES)
        assertEquals(19, TitlePageCodec.MAX_FRAGMENT_BYTES)
        assertEquals(9, ReachPageCodec.HEADER_BYTES)
    }
}
