package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wi-Fi policy area: a sourced, inert Exynos proposal and a resolvable off-switch. */
class WifiFamilyPolicyTest {
    private val exynos = DeviceTraits(hardware = "s5e9945", board = "exynos990", sdkInt = 33, socManufacturer = "Samsung")
    private val qualcomm = DeviceTraits(hardware = "qcom", board = "msmnile", sdkInt = 30)

    @Test
    fun everyEntryMeetsTheStrictEntryRules() {
        val problems = WifiFamilyPolicy.entries.flatMap { entry ->
            DevicePolicyEntryRules.violations(entry, strict = true).map { "${entry.id}: $it" }
        }
        assertEquals(problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun theShippedListHasNoActiveEffectAndTheProposalMatchesExynos() {
        val resolved = DeviceFamilyPolicy.resolve(exynos, emptySet())
        assertFalse(resolved.has<WifiAwareOff>())
        assertFalse(resolved.has<WifiDirectOff>())
        assertTrue(resolved.effects<WifiAwareMaxNdp>().isEmpty())
        assertTrue(
            "the Exynos proposal is reported",
            resolved.matchingProposals.any { it.id == "wifi-aware-exynos-one-ndp" },
        )
        val other = DeviceFamilyPolicy.resolve(qualcomm, emptySet())
        assertFalse(other.matchingProposals.any { it.id == "wifi-aware-exynos-one-ndp" })
    }

    @Test
    fun anAwareOffEntrySwitchesThePolicyViewAndItsOffSwitchDisablesIt() {
        val entry = DevicePolicyEntry(
            id = "wifi-aware-off-test-family",
            title = "Synthetic Aware off entry",
            matcher = DeviceMatcher(),
            effects = listOf(WifiAwareOff),
            behaviour = "Would not offer Wi-Fi Aware on a matching family.",
            sources = emptyList(),
            sourceStatus = PolicySourceStatus.EVIDENCE_ONLY,
            evidence = listOf("none"),
            confidence = PolicyConfidence.LOW,
            reviewed = "2026-11-04",
            origin = "test",
        )
        val active = ResolvedWifiPolicyView { DevicePolicyOff.resolve(listOf(entry), exynos, emptySet()) }
        assertTrue(active.awareOff())
        assertFalse(active.directOff())
        val switchedOff = ResolvedWifiPolicyView { DevicePolicyOff.resolve(listOf(entry), exynos, setOf(entry.id)) }
        assertFalse(switchedOff.awareOff())
    }
}
