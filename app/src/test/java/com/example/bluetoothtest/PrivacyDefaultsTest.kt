package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 0.2.0-beta ships the three privacy features ON for user launches (design decision 2026-10-12): rotating discovery IDs,
 * the BMH3 identity-hiding handshake and stranger pseudonyms. A DEBUG benchmark launch (any `test_` extra) still reads
 * only the extras, so frozen benchmark cases keep their exact switches. The neutral Bluetooth name is NOT part of the
 * default (opt-in only). Each Settings switch stays the off-switch: a stored choice always wins.
 */
class PrivacyDefaultsTest {
    @Before
    fun register() {
        FeatureCatalog.clearForTest()
        ConnectivityFeatures.registerAll()
    }

    @After
    fun clean() = FeatureCatalog.clearForTest()

    private fun userLaunch(stored: Map<String, Boolean> = emptyMap(), extras: Set<String> = emptySet()) =
        FeatureCatalog.resolveAll(false, { it in extras }) { stored[it] }

    private fun benchmarkLaunch(extras: Set<String> = emptySet(), stored: Map<String, Boolean> = emptyMap()) =
        FeatureCatalog.resolveAll(true, { it in extras }) { stored[it] }

    @Test
    fun aUserLaunchWithNothingStoredHasAllThreePrivacyFeaturesOn() {
        val snapshot = userLaunch()
        assertTrue(snapshot.isOn(ConnectivityFeatures.bmh3.id))
        assertTrue(snapshot.isOn(ConnectivityFeatures.bmh3Pseudonyms.id))
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, false, { false }) { null })
        // Extras are never read by a user launch.
        assertTrue(userLaunch(extras = emptySet()).isOn("bmh3"))
    }

    @Test
    fun aBenchmarkLaunchReadsOnlyItsExtrasSoEveryFrozenCaseKeepsItsSwitches() {
        val plain = benchmarkLaunch()
        assertFalse(plain.isOn("bmh3"))
        assertFalse(plain.isOn("bmh3_pseudonyms"))
        assertFalse(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, true, { false }) { null })
        assertFalse(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, true, { false }) { true })
        // The pseudonym extra alone is not enough: it is a child of bmh3.
        assertFalse(benchmarkLaunch(extras = setOf(Bmh3PseudonymFeature.EXTRA)).isOn("bmh3_pseudonyms"))
        // Pseudonyms also need rotating IDs (a dependency of the catalog since the 2026-10-27 switch audit).
        assertFalse(benchmarkLaunch(extras = setOf(Bmh3Feature.EXTRA, Bmh3PseudonymFeature.EXTRA)).isOn("bmh3_pseudonyms"))
        val both = benchmarkLaunch(extras = setOf(Bmh3Feature.EXTRA, Bmh3PseudonymFeature.EXTRA, RotatingDiscoveryFeature.EXTRA))
        assertTrue(both.isOn("bmh3"))
        assertTrue(both.isOn("bmh3_pseudonyms"))
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, true, { it == RotatingDiscoveryFeature.EXTRA }) { null })
        // A stored user choice never leaks into a benchmark launch.
        assertFalse(benchmarkLaunch(stored = mapOf(Bmh3Feature.PREF to true)).isOn("bmh3"))
    }

    @Test
    fun anOffStoredByAnOlderBuildNoLongerTurnsHiddenIdentitiesOrPseudonymsOff() {
        // No Settings switch since 2026-11-04 (owner): a person who once turned them off is protected again.
        val off = userLaunch(stored = mapOf(Bmh3Feature.PREF to false, Bmh3PseudonymFeature.PREF to false))
        assertTrue(off.isOn("bmh3"))
        assertTrue(off.isOn("bmh3_pseudonyms"))
        // The DEBUG launch extra still runs either arm of an A/B.
        assertTrue(benchmarkLaunch(extras = setOf(Bmh3Feature.EXTRA)).isOn("bmh3"))
        // Private Bluetooth ID has no switch: what an older build stored is ignored.
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, false, { false }) { if (it == "experimental_rotating_ids") false else null })
    }

    @Test
    fun pseudonymsNeedRotatingIdsAtRunTime() {
        FeatureCatalog.refresh(false, { false }) { null }
        assertTrue(Bmh3PseudonymFeature.active(rotatingIds = true))
        assertFalse("a fixed prefix on air would make a pseudonym hide nothing", Bmh3PseudonymFeature.active(rotatingIds = false))
    }

    @Test
    fun theNeutralBluetoothNameIsPartOfTheDefaultButNotOfABenchmarkLaunch() {
        // On by default since 2026-10-27 (the rename itself waits for the user's answer, NeutralNameDefaultTest).
        assertTrue(FeatureCatalog.resolve(NeutralNameOption.spec, false, { false }) { null })
        assertEquals("neutral_bluetooth_name", NeutralNameOption.PREF)
        assertFalse(FeatureCatalog.resolve(NeutralNameOption.spec, true, { false }) { true })
    }

    @Test
    fun theThreeDefaultsAreTheDocumentedConstants() {
        assertTrue(RotatingDiscoveryFeature.DEFAULT_ENABLED)
        assertTrue(ConnectivityFeatureDefaults.BMH3)
        assertTrue(ConnectivityFeatureDefaults.BMH3_PSEUDONYMS)
        assertEquals(ConnectivityFeatureDefaults.BMH3, ConnectivityFeatures.bmh3.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.BMH3_PSEUDONYMS, ConnectivityFeatures.bmh3Pseudonyms.defaultEnabled)
    }

    @Test
    fun friendlyNamesAreOnForUsersEvenWhenAnOlderBuildStoredOffAndTheBenchmarkArmStays() {
        val names = NamingFeatures.GENERATED_NAMES
        assertTrue(FeatureCatalog.resolve(names, false, { null }) { if (it == "feature_generated_names") false else null })
        assertFalse(FeatureCatalog.resolve(names, true, { null }) { true })
        assertTrue(FeatureCatalog.resolve(names, true, { if (it == names.benchmarkExtra) true else null }) { false })
    }
}
