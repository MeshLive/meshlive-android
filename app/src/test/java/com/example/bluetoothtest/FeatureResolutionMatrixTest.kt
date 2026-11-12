package com.example.bluetoothtest

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The features moved onto the catalog by the 2026-10-27 switch audit (F6) resolve exactly as their own rules did before.
 * Each reference below is the earlier per-feature rule, written out with the call site's inputs, and is compared with
 * [FeatureCatalog.resolve] for every launch a phone can have (release person, DEBUG person, DEBUG benchmark), every extra
 * (absent, false, true), every stored choice (unset, off, on) and both positions of the Advanced toggle.
 *
 * Constants (no Settings row) read a stored key that no build ever wrote; for them the old rule is compared with the
 * key unset, and the catalog must ignore any stored value.
 */
class FeatureResolutionMatrixTest {
    private data class Launch(val debug: Boolean, val benchmark: Boolean)

    private val launches = listOf(Launch(debug = false, benchmark = false), Launch(true, false), Launch(true, true))
    private val extras = listOf<Boolean?>(null, false, true)
    private val storedChoices = listOf<Boolean?>(null, false, true)

    /** The earlier rule: (launch, extra or null when absent, stored or null when unset, Advanced) -> on. */
    private class Case(
        val spec: FeatureSpec,
        val constantKey: Boolean = false,
        val old: (Launch, Boolean?, Boolean?, Boolean) -> Boolean,
    )

    private val cases = listOf(
        // No user switch since 2026-11-04: a stored choice is ignored.
        Case(RotatingDiscoveryFeature.spec, constantKey = true) { l, x, s, _ -> if (l.benchmark) x == true else s ?: true },
        Case(NeutralNameOption.spec) { l, x, s, _ -> if (l.benchmark) x == true else s ?: true }, // default on since 2026-10-27
        // MessagingV2Feature.resolve (inside Messages): `messages && (bench ? extra : stored) ?: default`.
        Case(MessagingV2Feature.spec, constantKey = true) { l, x, s, _ -> (if (l.benchmark) x else s) ?: true },
        // MessagingV2ChatFeature.resolve: bench ? getBooleanExtra(EXTRA, true) : stored ?: true.
        Case(MessagingV2ChatFeature.spec, constantKey = true) { l, x, s, _ -> if (l.benchmark) x ?: true else s ?: true },
        // RelayedCallsFeature.resolve with getBoolean(PREF, false); since 2026-11-06 (owner, GAP-AUDIT-2 Q2) the stored
        // choice counts with or without Manual video settings.
        Case(RelayedCallsFeature.spec) { l, x, s, _ -> if (l.benchmark) x == true else s ?: false },
        // applyCallReachability with getBoolean(PREF_REACHABLE_FOR_CALLS, true).
        Case(CallReachabilityFeature.spec) { l, x, s, _ -> if (l.benchmark) x == true else s ?: true },
        // hasExtra = BuildConfig.DEBUG && extra present.
        Case(ReachableWhileBrowsingFeature.spec) { l, x, _, _ ->
            if (l.debug && x != null) x else !l.benchmark
        },
        Case(CallResolutionLadderFeature.spec) { l, x, _, _ -> if (l.debug && x != null) x else !l.benchmark },
        Case(AutoQualityFeature.spec) { l, x, _, _ -> l.debug && x == true },
        Case(GpuScaleFeature.spec) { l, x, _, _ -> l.debug && x == true },
        // NamingSettings.nameBeforeAnswer: Experimental and off for every user launch since 2026-11-06 (design decision).
        Case(NamingFeatures.NAMES_BEFORE_ANSWER, constantKey = true) { l, x, s, _ -> if (l.benchmark) x == true else s ?: false },
        Case(NamingFeatures.TITLE_PAGES, constantKey = true) { l, x, s, _ -> if (l.benchmark) x == true else s ?: true },
        Case(AudioLiveFeatures.audioLive, constantKey = true) { l, x, s, _ -> if (l.benchmark) x == true else s ?: true },
    ) + CallFeature.entries.map { feature ->
        Case(CallFeatureSpecs.specOf(feature), constantKey = feature.prefKey == null) { l, x, s, _ ->
            if (l.benchmark) x == true else s ?: feature.defaultOn
        }
    }

    @Before
    fun clean() = FeatureCatalog.clearForTest()

    @After
    fun cleanAfter() = FeatureCatalog.clearForTest()

    @Test
    fun everyMovedFeatureResolvesAsItsEarlierRuleInEveryLaunch() {
        for (case in cases) for (launch in launches) for (extra in extras) for (stored in storedChoices) for (adv in listOf(false, true)) {
            val label = "${case.spec.id} $launch extra=$extra stored=$stored advanced=$adv"
            val now = FeatureCatalog.resolve(
                case.spec, launch.benchmark, { name -> if (name in case.spec.extraNames) extra else null }, { stored }, adv, launch.debug,
            )
            if (case.constantKey) {
                assertTrue("${case.spec.id} is a constant", case.spec.constant)
                assertEquals(label, case.old(launch, extra, null, adv), now)
            } else {
                assertEquals(label, case.old(launch, extra, stored, adv), now)
            }
        }
    }

    @Test
    fun theNeutralNameAliasStillSwitchesABenchmarkLaunch() {
        val spec = NeutralNameOption.spec
        assertTrue(FeatureCatalog.resolve(spec, true, { if (it == NeutralNameOption.EXTRA_ALIAS) true else null }, { null }))
        // A user launch ignores the extra: a stored off stays off (default on since 2026-10-27).
        assertFalse(FeatureCatalog.resolve(spec, false, { true }, { false }))
    }

    @Test
    fun everyFeatureIsRegisteredOnceWithOneKeyAndTheRotatingIdDependenciesAreModelled() {
        AppFeatures.registerAll(updaterAvailable = true)
        AppFeatures.registerAll(updaterAvailable = true) // idempotent: an activity can be recreated
        val all = FeatureCatalog.all()
        val ids = cases.map { it.spec.id }
        ids.forEach { assertTrue("$it is registered", FeatureCatalog.spec(it) != null) }
        val keys = all.mapNotNull { it.prefKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(RotatingDiscoveryFeature.spec.id in ConnectivityFeatures.findPcs.dependsOn)
        assertTrue(RotatingDiscoveryFeature.spec.id in ConnectivityFeatures.bmh3Pseudonyms.dependsOn)
        // Rotating IDs have no user switch: an "off" stored by 0.4.4 or older leaves them, and their children, on.
        val stored = mapOf("experimental_rotating_ids" to false, PcAnnouncementFeature.PREF to true)
        val snapshot = FeatureCatalog.resolveAll(false, { null }) { stored[it] }
        assertTrue(snapshot.isOn(RotatingDiscoveryFeature.spec))
        assertTrue(snapshot.isOn(ConnectivityFeatures.bmh3Pseudonyms))
        assertTrue(snapshot.isOn(ConnectivityFeatures.bmh3))
        // The run-time value of Find PCs keeps its own rule (it needs rotating IDs where it reads announcements).
        assertTrue(FeatureCatalog.resolve(ConnectivityFeatures.findPcs, false, { null }) { stored[it] })
    }

    @Test
    fun aFreshProcessHasThePersonsSnapshotBeforeAnyScreen() {
        // Switch audit B15: a receiver, provider or the boot-resume start used to read every feature as off.
        val phone = FakePhone(Files.createTempDirectory("feature-snapshot").toFile())
        try {
            val context = phone.start()
            MeshLiveApplication.snapshotFeatures(context)
            assertTrue(FeatureCatalog.isEnabled(ConnectivityFeatures.bmh3.id))
            assertTrue(FeatureCatalog.isEnabled(NamingFeatures.BVMID2.id))
            assertTrue(FeatureCatalog.isEnabled(AudioLiveFeatures.audioLive.id))
            // A stored choice of a switch that has a Settings row counts there too.
            phone.preferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES).edit().putBoolean(CompactDiscoveryFeature.PREF, false).apply()
            MeshLiveApplication.snapshotFeatures(context)
            assertFalse(FeatureCatalog.isEnabled(ConnectivityFeatures.iphoneDiscovery.id))
        } finally {
            phone.root.deleteRecursively()
        }
    }
}
