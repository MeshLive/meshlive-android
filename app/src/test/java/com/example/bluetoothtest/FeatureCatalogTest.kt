package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FeatureCatalogTest {
    private fun spec(
        id: String = "sample",
        prefKey: String? = "feature_sample",
        default: Boolean = false,
        extra: String? = "test_sample",
        dependsOn: List<String> = emptyList(),
    ) = FeatureSpec(id, prefKey, default, extra, dependsOn = dependsOn)

    @Before
    fun clean() = FeatureCatalog.clearForTest()

    @After
    fun cleanAfter() = FeatureCatalog.clearForTest()

    @Test
    fun aBenchmarkLaunchReadsOnlyItsOwnExtraWhateverTheDefaultOrTheStoredChoiceSay() {
        val on = spec(default = true)
        assertFalse(FeatureCatalog.resolve(on, true, { false }, { true }))
        assertTrue(FeatureCatalog.resolve(spec(default = false), true, { it == "test_sample" }, { false }))
        // No extra declared: a benchmark launch always reads off.
        assertFalse(FeatureCatalog.resolve(spec(default = true, extra = null), true, { true }, { true }))
    }

    @Test
    fun aStoredChoiceChangedMidRunKeepsTheBenchmarkLaunchsExtras() {
        FeatureCatalog.register(spec(id = "kept", prefKey = "feature_kept", extra = "test_kept"))
        FeatureCatalog.register(spec(id = "stored", prefKey = "feature_stored", default = true, extra = null))
        FeatureCatalog.refresh(true, { it == "test_kept" }) { null }
        assertTrue(FeatureCatalog.isEnabled("kept"))
        // A screen without the launch intent (the update screen's "turn on" answer) re-resolves after saving a choice.
        FeatureCatalog.refreshStoredChoices { if (it == "feature_stored") true else null }
        assertTrue(FeatureCatalog.isEnabled("kept"))
        assertFalse(FeatureCatalog.isEnabled("stored"))
        // A user launch keeps reading stored choices.
        FeatureCatalog.refresh(false, { true }) { null }
        FeatureCatalog.refreshStoredChoices { if (it == "feature_kept") true else null }
        assertTrue(FeatureCatalog.isEnabled("kept"))
        assertTrue(FeatureCatalog.isEnabled("stored"))
    }

    @Test
    fun aNormalLaunchUsesTheExplicitChoiceElseTheDefaultAndIgnoresExtrasAndManualSettings() {
        assertTrue(FeatureCatalog.resolve(spec(default = true), false, { false }, { null }))
        assertFalse(FeatureCatalog.resolve(spec(default = false), false, { true }, { null }))
        assertFalse(FeatureCatalog.resolve(spec(default = true), false, { true }, { false }))
        assertTrue(FeatureCatalog.resolve(spec(default = false), false, { false }, { true }))
    }

    @Test
    fun aSubOptionIsOnOnlyWhileItsParentIsOn() {
        FeatureCatalog.register(spec("parent", "p"))
        FeatureCatalog.register(spec("child", "c", dependsOn = listOf("parent")))
        val stored = mapOf("p" to false, "c" to true)
        val off = FeatureCatalog.resolveAll(false, { false }) { stored[it] }
        assertFalse(off.isOn("parent"))
        assertFalse(off.isOn("child"))
        val on = FeatureCatalog.resolveAll(false, { false }) { mapOf("p" to true, "c" to true)[it] }
        assertTrue(on.isOn("child"))
        assertFalse(on.isOn("unregistered"))
    }

    @Test
    fun registrationIsIdempotentAndRejectsConflicts() {
        FeatureCatalog.register(spec())
        FeatureCatalog.register(spec())
        assertEquals(1, FeatureCatalog.all().size)
        expectFailure { FeatureCatalog.register(spec(default = true)) }
        expectFailure { FeatureCatalog.register(spec("other", "feature_sample")) }
        expectFailure { FeatureCatalog.register(spec("child", "c", dependsOn = listOf("missing"))) }
        expectFailure { spec("Bad Id") }
        expectFailure { spec("self", dependsOn = listOf("self")) }
    }

    @Test
    fun refreshRemembersTheSnapshotAndLabelsIt() {
        FeatureCatalog.register(spec("a", "pa", default = true))
        FeatureCatalog.register(spec("b", "pb"))
        val snapshot = FeatureCatalog.refresh(false, { false }) { null }
        assertEquals(snapshot, FeatureCatalog.current)
        assertTrue(FeatureCatalog.isEnabled("a"))
        assertFalse(FeatureCatalog.isEnabled("b"))
        assertEquals("a=on | b=off", snapshot.label())
        assertNotEquals(snapshot, FeatureCatalog.refresh(false, { false }) { if (it == "pb") true else null })
    }

    @Test
    fun theConnectivityFeaturesKeepTheirOldSwitchesAndAreAllDescribed() {
        val all = ConnectivityFeatures.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        val rows = all.filter { !it.constant }
        assertEquals(rows.size, rows.map { it.prefKey }.toSet().size)
        rows.forEach { assertTrue(it.id, it.titleRes != 0 && it.descriptionRes != 0 && it.iconRes != 0) }
        all.forEach {
            assertEquals(ConnectivityFeatures.GROUP, it.group)
            assertTrue(it.id, it.phoneTest != null)
        }
        // The old benchmark extras and preference keys stay: case IDs and earlier choices remain valid.
        assertEquals("test_coc_auto_gatt", ConnectivityFeatures.cocAutoGatt.benchmarkExtra)
        assertEquals("test_classic_bond_free", ConnectivityFeatures.classicWithoutPairing.benchmarkExtra)
        assertEquals("test_ios_compact_discovery", ConnectivityFeatures.iphoneDiscovery.benchmarkExtra)
        assertEquals("test_offline_app_share", ConnectivityFeatures.shareAppNearby.benchmarkExtra)
        assertEquals("test_call_native_opus", ConnectivityFeatures.nativeOpus.benchmarkExtra)
        assertEquals("test_dual_listening", ConnectivityFeatures.dualListening.benchmarkExtra)
        assertEquals("experimental_classic_bond_free", ConnectivityFeatures.classicWithoutPairing.prefKey)
        assertEquals("experimental_ios_compact_discovery_v2", ConnectivityFeatures.iphoneDiscovery.prefKey)
        assertEquals("experimental_offline_app_share", ConnectivityFeatures.shareAppNearby.prefKey)
        // No Settings switch since 2026-11-04 (owner): these follow their default; the extras above still run A/Bs.
        listOf(ConnectivityFeatures.cocAutoGatt, ConnectivityFeatures.dualListening, ConnectivityFeatures.nativeOpus,
            ConnectivityFeatures.bmh3, ConnectivityFeatures.bmh3Pseudonyms).forEach { assertTrue(it.id, it.constant) }
        // One default constant per feature.
        assertEquals(ConnectivityFeatureDefaults.COC_AUTO_GATT, ConnectivityFeatures.cocAutoGatt.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.CLASSIC_WITHOUT_PAIRING, ConnectivityFeatures.classicWithoutPairing.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.IPHONE_DISCOVERY, ConnectivityFeatures.iphoneDiscovery.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.SHARE_APP_NEARBY, ConnectivityFeatures.shareAppNearby.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.NATIVE_OPUS, ConnectivityFeatures.nativeOpus.defaultEnabled)
        assertEquals(ConnectivityFeatureDefaults.DUAL_LISTENING, ConnectivityFeatures.dualListening.defaultEnabled)
    }

    @Test
    fun theWifiSwitchesAreOffByDefaultAndKeepTheirStoredKeys() {
        FeatureCatalog.clearForTest()
        ConnectivityFeatures.registerAll()
        val snapshot = FeatureCatalog.refresh(false, { null }) { null }
        ConnectivityFeatures.wifi.forEach { assertFalse(it.id, snapshot.isOn(it)) }
        assertEquals("test_wifi_carrier", ConnectivityFeatures.wifiCarrier.benchmarkExtra)
        assertEquals(setOf("wifi_carrier", "wifi_wider_reach", "wifi_direct"), ConnectivityFeatures.wifi.mapNotNull { it.prefKey }.toSet())
    }

    @Test
    fun aStoredWifiSubSwitchCountsOnlyWhileTheCarrierSwitchIsOn() {
        FeatureCatalog.clearForTest(); ConnectivityFeatures.registerAll()
        val stored = mutableMapOf("wifi_wider_reach" to true)
        assertFalse(FeatureCatalog.refresh(false, { null }) { stored[it] }.isOn("wifi_wider_reach"))
        stored["wifi_carrier"] = true
        assertTrue(FeatureCatalog.refresh(false, { null }) { stored[it] }.isOn("wifi_wider_reach"))
        stored["wifi_carrier"] = false
        assertFalse(FeatureCatalog.refresh(false, { null }) { stored[it] }.isOn("wifi_wider_reach"))
    }

    @Test
    fun aDebugLaunchExtraTurnsAWifiSwitchOnAndAReleaseLaunchIgnoresIt() {
        FeatureCatalog.clearForTest(); ConnectivityFeatures.registerAll()
        val extras = mapOf("test_wifi_carrier" to true)
        assertTrue(FeatureCatalog.refresh(true, { extras[it] }, { null }, debugBuild = true).isOn("wifi_carrier"))
        // A person's DEBUG launch (not a benchmark launch) still reads the extra: extraInAnyDebugLaunch.
        assertTrue(FeatureCatalog.refresh(false, { extras[it] }, { null }, debugBuild = true).isOn("wifi_carrier"))
        assertFalse(FeatureCatalog.refresh(false, { extras[it] }, { null }, debugBuild = false).isOn("wifi_carrier"))
    }

    @Test
    fun theCallFeatureResolverIsTheCatalogRule() {
        CallFeatureSpecs.registerAll()
        ConnectivityFeatures.registerAll()
        assertEquals(CallFeature.entries.size, CallFeatureSpecs.all.size)
        // Rotating IDs are registered with the connectivity features (Find PCs and pseudonyms depend on them).
        assertEquals(CallFeatureSpecs.all.size + ConnectivityFeatures.all.size + 1, FeatureCatalog.all().size)
        val storedChoices = listOf<Boolean?>(null, true, false)
        for (feature in CallFeature.entries) {
            val spec = CallFeatureSpecs.specOf(feature)
            assertEquals(feature.prefKey, spec.prefKey)
            assertEquals(feature.defaultOn, spec.defaultEnabled)
            assertEquals(feature.benchmarkExtra, spec.benchmarkExtra)
            for (benchmark in listOf(false, true)) for (extra in listOf(false, true)) for (stored in storedChoices) {
                assertEquals(
                    "$feature benchmark=$benchmark extra=$extra stored=$stored",
                    CallFeatureResolver.enabled(feature, benchmark, { extra }, { stored }),
                    FeatureCatalog.resolve(spec, benchmark, { extra }, { stored }),
                )
            }
        }
        // The automatic sync is a sub-option of Messages in both.
        val stored = mapOf(CallFeature.AUTO_SYNC.prefKey to true, CallFeature.MESSAGES.prefKey to false)
        val snapshot = FeatureCatalog.resolveAll(false, { false }) { stored[it] }
        assertFalse(snapshot.isOn("messages_auto_sync"))
        assertEquals(
            CallFeatureResolver.resolve(false, { false }) { stored[it] }.autoSync,
            snapshot.isOn("messages_auto_sync"),
        )
        assertNull(FeatureCatalog.spec("messages_auto_sync")?.dependsOn?.takeIf { it != listOf("messages") })
    }

    @Test
    fun nativeOpusFollowsItsShippedDefaultAndTheDebugExtraOnly() {
        // No Settings switch since 2026-11-04: a stored choice is ignored.
        assertEquals(
            ConnectivityFeatureDefaults.NATIVE_OPUS,
            NativeOpusSettings.resolveEnabled(false, { false }) { if (it == NativeOpusSettings.PREF_ENABLED) !ConnectivityFeatureDefaults.NATIVE_OPUS else null },
        )
        assertFalse(NativeOpusSettings.resolveEnabled(true, { false }) { true })
        assertTrue(NativeOpusSettings.resolveEnabled(true, { it == NativeOpusSettings.EXTRA_ENABLED }) { false })
        assertEquals(
            ConnectivityFeatureDefaults.NATIVE_OPUS,
            NativeOpusSettings.resolveEnabled(false, { true }) { null },
        )
        // The main switch has no BuildConfig.DEBUG gate; DTX keeps one (it changes the packet cadence).
        DebugTunables.callNativeOpus = true
        assertTrue(DebugTunables.callNativeOpus)
        DebugTunables.callNativeOpus = false
        assertFalse(DebugTunables.nativeOpusSettings().dtx)
    }

    @Test
    fun aConstantHasNoStoredChoiceAndNoSettingsRow() {
        val constant = spec(prefKey = null, default = true)
        assertTrue(constant.constant)
        assertTrue(FeatureCatalog.resolve(constant, false, { false }, { false }))
        assertFalse("a benchmark launch still reads its extra", FeatureCatalog.resolve(constant, true, { false }, { true }))
        expectFailure { FeatureSpec("row", null, true, null, titleRes = 1) }
        expectFailure { FeatureSpec("gated", null, true, null, advancedOnly = true) }
        // Constants never collide on a preference key.
        FeatureCatalog.register(spec("c1", prefKey = null))
        FeatureCatalog.register(spec("c2", prefKey = null))
        assertEquals(2, FeatureCatalog.all().size)
    }

    @Test
    fun aBenchmarkDefaultAndAliasesApplyOnlyToBenchmarkLaunches() {
        val onInBenchmarks = spec(default = true).copy(benchmarkDefault = true)
        val absent = { _: String -> null as Boolean? }
        assertTrue("no extra: the benchmark default", FeatureCatalog.resolve(onInBenchmarks, true, absent, { false }))
        assertFalse("the extra set to false is the control arm", FeatureCatalog.resolve(onInBenchmarks, true, { false }, { true }))
        val aliased = spec().copy(extraAliases = listOf("test_old_name"))
        assertTrue(FeatureCatalog.resolve(aliased, true, { if (it == "test_old_name") true else null }, { null }))
        assertTrue(FeatureCatalog.resolve(aliased, true, { if (it == "test_sample") true else null }, { null }))
        assertFalse(FeatureCatalog.resolve(aliased, true, absent, { true }))
        assertFalse("a user launch ignores aliases too", FeatureCatalog.resolve(aliased, false, { true }, { null }))
        expectFailure { spec(extra = null).copy(extraAliases = listOf("test_x")) }
    }

    @Test
    fun anExtraInAnyDebugLaunchDecidesOnlyInDebugBuildsAndOnlyWhenPresent() {
        val ladder = spec(prefKey = null, default = true).copy(extraInAnyDebugLaunch = true)
        val absent = { _: String -> null as Boolean? }
        assertFalse(FeatureCatalog.resolve(ladder, false, { false }, { null }, debugBuild = true))
        assertTrue(FeatureCatalog.resolve(ladder, false, absent, { null }, debugBuild = true))
        assertTrue("release builds never read it", FeatureCatalog.resolve(ladder, false, { false }, { null }, debugBuild = false))
        assertFalse("a benchmark launch without it reads off", FeatureCatalog.resolve(ladder, true, absent, { null }, debugBuild = true))
    }

    @Test
    fun anAdvancedOnlyChoiceCountsOnlyWhileAdvancedIsOnAndARefreshKeepsTheToggle() {
        val gated = spec(default = false).copy(advancedOnly = true)
        assertTrue(FeatureCatalog.resolve(gated, false, { false }, { true }, advancedSettings = true))
        assertFalse(FeatureCatalog.resolve(gated, false, { false }, { true }, advancedSettings = false))
        FeatureCatalog.register(gated)
        FeatureCatalog.refresh(false, { null }, { true }, advancedSettings = true)
        assertTrue(FeatureCatalog.isEnabled("sample"))
        // A screen that only saved a choice keeps the launch's Advanced toggle.
        FeatureCatalog.refreshStoredChoices { true }
        assertTrue(FeatureCatalog.isEnabled("sample"))
        FeatureCatalog.refresh(false, { null }, { true }, advancedSettings = false)
        assertFalse(FeatureCatalog.isEnabled("sample"))
    }

    @Test
    fun aFeatureWithSeveralDependenciesIsOnOnlyWhileAllAreOn() {
        FeatureCatalog.register(spec("a", "pa", default = true))
        FeatureCatalog.register(spec("b", "pb", default = true))
        FeatureCatalog.register(spec("child", "pc", default = true, dependsOn = listOf("a", "b")))
        assertTrue(FeatureCatalog.resolveAll(false, { false }) { null }.isOn("child"))
        assertFalse(FeatureCatalog.resolveAll(false, { false }) { if (it == "pb") false else null }.isOn("child"))
        assertFalse(FeatureCatalog.resolveAll(false, { false }) { if (it == "pa") false else null }.isOn("child"))
    }

    @Test
    fun aDebugRefreshKeepsTheExtrasAndAReleaseRefreshKeepsNone() {
        val ladder = spec(prefKey = null, default = true).copy(extraInAnyDebugLaunch = true)
        FeatureCatalog.register(ladder)
        FeatureCatalog.refresh(false, { false }, { null }, debugBuild = true)
        FeatureCatalog.refreshStoredChoices { null }
        assertFalse("the DEBUG launch's control arm survives a stored-choice refresh", FeatureCatalog.isEnabled("sample"))
        FeatureCatalog.refresh(false, { false }, { null }, debugBuild = false)
        assertTrue(FeatureCatalog.isEnabled("sample"))
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
