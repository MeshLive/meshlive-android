package com.example.bluetoothtest

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFamilyPolicyTest {
    private val known = KnownDeviceTraits.load().associateBy { it.alias }

    @After
    fun restoreSwitches() {
        DebugTunables.devicePolicyOff = emptySet()
    }

    @Test
    fun everyEntryHasAnAreaAValidationStatusAndEvidenceForLocalClaims() {
        val ids = DeviceFamilyPolicy.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (entry in DeviceFamilyPolicy.entries) {
            if (entry.validation == PolicyValidation.LOCALLY_REPRODUCED) {
                assertTrue(entry.id, entry.evidence.any { it.startsWith("benchmarks/") || it.startsWith("docs/") })
            }
            if (entry.validation == PolicyValidation.UPSTREAM_DOCUMENTED) {
                assertTrue(entry.id, entry.sources.isNotEmpty())
            }
        }
        // The per-area lists are part of the one registry.
        assertTrue(DeviceFamilyPolicy.entries.containsAll(CodecPolicy.entries + YuvPolicy.entries +
            BluetoothTransportPolicy.entries + BackgroundPolicy.entries + MemoryThermalPolicy.entries +
            NativeCompat.policyEntries + ScanPolicy.entries + ServiceFamilyPolicy.entries + AudioPolicyEntries.entries +
            CocPolicy.entries + CameraPolicy.entries + AdvertisingPolicy.entries + PhyLinkPolicy.entries))
    }

    @Test
    fun benchmarkLineNamesActiveEntriesNoLocalPhoneHasReproduced() {
        val line = DeviceFamilyPolicy.benchmarkLine(DeviceFamilyPolicy.resolve(DeviceTraits("qcom", "msmnile", 30), emptySet()))
        val unconfirmed = line.substringAfter("unconfirmed=").substringBefore(" |").split(',')
        assertTrue(line, "app-bluetooth-toggle-allowed" in unconfirmed) // upstream-documented only
        // Experimental and waiting for its default-off arm: reported under armOff, not as something the phone runs.
        assertFalse(line, "qcom-legacy-call-relay-gatt-acknowledged-writes" in unconfirmed)
        assertFalse(line, "qcom-msmnile-call" in unconfirmed) // locally reproduced
    }

    @Test
    fun benchmarkPhonesActivateTheExpectedDeviceEntries() {
        val expected = mapOf(
            "s24" to emptyList(),
            "s22" to emptyList(),
            "a55" to emptyList(),
            "xiaomi" to emptyList(),
            "realme" to emptyList(),
            "m31" to listOf("le-coc-eatt-stranded-channels", "app-bluetooth-toggle-allowed"),
            "a15s" to listOf("gatt-context-map-race", "le-coc-server-leak", "app-bluetooth-toggle-allowed"),
            "asus" to listOf(
                "qcom-legacy-gatt-call",
                "qcom-legacy-coc-call",
                "qcom-legacy-classic-call",
                "qcom-msmnile-call",
                "qcom-legacy-call-vp9-software-decoder",
                "qcom-legacy-call-relay-gatt-acknowledged-writes",
                "qcom-msmnile-classic-source-balanced-scan",
                "gatt-context-map-race",
                "app-bluetooth-toggle-allowed",
            ),
        )
        assertEquals(expected.keys, known.keys)
        for ((alias, ids) in expected) {
            val resolved = DeviceFamilyPolicy.resolve(known.getValue(alias).traits, emptySet())
            // Universal entries (memory, thermal, native build) and area-owned transport/background rules have their own tests.
            val areaOwned = (BluetoothTransportPolicy.entries + BackgroundPolicy.entries + ScanPolicy.entries +
                ServiceFamilyPolicy.entries + AudioPolicyEntries.entries + CocPolicy.entries + CameraPolicy.entries +
                AdvertisingPolicy.entries + PhyLinkPolicy.entries).map { it.id }.toSet()
            val familyRules = resolved.active.filter { it.component == null && !it.matcher.universal && it.id !in areaOwned }
            assertEquals(alias, ids, familyRules.map { it.id })
        }
    }

    @Test
    fun entriesCarryTheRequiredMetadata() {
        val ids = DeviceFamilyPolicy.entries.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        for (entry in DeviceFamilyPolicy.entries) {
            assertTrue(entry.id, entry.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
            assertTrue(entry.id, entry.reviewed.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue(entry.id, entry.evidence.isNotEmpty())
            assertTrue(entry.id, entry.behaviour.isNotBlank() && entry.title.isNotBlank() && entry.origin.isNotBlank())
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            if (entry.sourceStatus == PolicySourceStatus.EVIDENCE_ONLY) {
                // Supporting sources may back part of the mechanism; the family scope itself has none.
                assertTrue("${entry.id}: an evidence-only rule cannot claim high confidence", entry.confidence != PolicyConfidence.HIGH)
            } else {
                assertTrue("${entry.id} needs at least one source", entry.sources.isNotEmpty())
            }
            if (entry.proposed) {
                assertTrue("${entry.id}: proposals must be sourced", entry.sourceStatus != PolicySourceStatus.EVIDENCE_ONLY)
            } else {
                assertTrue("${entry.id}: implemented entries change behaviour", entry.effects.isNotEmpty())
            }
            entry.sources.forEach { source ->
                assertTrue("${entry.id}: ${source.reference}", source.reference.isNotBlank() && source.note.isNotBlank())
            }
        }
    }

    @Test
    fun offSwitchRemovesOnlyThatEntryAndAllRemovesEverything() {
        val asus = known.getValue("asus").traits
        val gattOff = DeviceFamilyPolicy.resolve(asus, setOf("qcom-legacy-gatt-call"))
        assertFalse(gattOff.isActive("qcom-legacy-gatt-call"))
        assertTrue(gattOff.isActive("qcom-msmnile-call"))
        assertEquals(listOf("qcom-legacy-gatt-call"), gattOff.switchedOff.map { it.id })
        // msmnile still caps GATT geometry/FPS, but the GATT-only 150 kbps rate and Opus 16 are gone.
        val caps = gattOff.callVideoCaps(BluetoothTransportOption.BLE_GATT)
        assertEquals(listOf(null), caps.map { it.maxVideoKbps })
        assertEquals(null, gattOff.callAudioBitRate(BluetoothTransportOption.BLE_GATT))

        val allOff = DeviceFamilyPolicy.resolve(asus, setOf("all"))
        assertTrue(allOff.active.isEmpty())

        val selected = VideoSettings(VideoResolution(640, 480, "480p"), 30, 1_000_000, 250)
        DebugTunables.devicePolicyOff = setOf("all")
        if (!BuildConfig.DEBUG) {
            // test_device_policy_off is DEBUG-only: a release build always keeps every entry.
            assertTrue(GattClientTeardown.deferredFor(DeviceTraits("", "", 29), null))
            assertEquals(0, encoderTieBreakRank("c2.exynos.vp9.encoder"))
            return
        }
        assertEquals(
            privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, null),
            privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, asus),
        )
        assertFalse(GattClientTeardown.deferredFor(DeviceTraits("", "", 29), null))
        assertFalse(canRecoverPrivateCallVideoDecoder("c2.android.vp9.decoder", 0))
        assertEquals(1, encoderTieBreakRank("c2.exynos.vp9.encoder"))
        DebugTunables.devicePolicyOff = setOf("c2-android-vp9-decoder-in-place-recovery")
        assertFalse(canRecoverPrivateCallVideoDecoder("c2.android.vp9.decoder", 0))
        assertEquals(0, encoderTieBreakRank("c2.exynos.vp9.encoder"))
        assertTrue(GattClientTeardown.deferredFor(DeviceTraits("", "", 29), null))
    }

    @Test
    fun legacyReliefSwitchesOffExactlyTheLegacyCallEntriesOnCallPaths() {
        val asus = known.getValue("asus").traits
        val relief = DeviceFamilyPolicy.resolve(privateCallStackFingerprint(asus, relief = true), emptySet())
        val normal = DeviceFamilyPolicy.resolve(privateCallStackFingerprint(asus, relief = false), emptySet())
        assertEquals(
            DevicePolicyRegistry.LEGACY_QUALCOMM_CALL.filter(normal::isActive).toSet(),
            relief.switchedOff.map { it.id }.toSet(),
        )
        assertTrue("relay and stream entries stay", relief.isActive("qcom-legacy-call-relay-gatt-acknowledged-writes"))
        assertTrue(relief.isActive("qcom-msmnile-classic-source-balanced-scan"))
        assertTrue(relief.isActive("gatt-context-map-race"))
        assertEquals(normal.active.map { it.id } - DevicePolicyRegistry.LEGACY_QUALCOMM_CALL, relief.active.map { it.id })
        val ids = DeviceFamilyPolicy.entries.map { it.id }.toSet()
        assertTrue(ids.containsAll(DevicePolicyRegistry.LEGACY_QUALCOMM_CALL))
    }

    @Test
    fun firstRegistryEntriesKeepTheirIdsScopesAndNotes() {
        val leak = DeviceFamilyPolicy.LE_COC_SERVER_LEAK
        val race = DeviceFamilyPolicy.GATT_CONTEXT_MAP_RACE
        assertEquals("le-coc-server-leak", leak.id)
        assertEquals("gatt-context-map-race", race.id)
        assertEquals(DeviceMatcher(sdkMax = 29), leak.matcher)
        assertEquals(DeviceMatcher(sdkMax = 30), race.matcher)
        assertTrue(race.confidenceNote.contains("unproven"))
        assertTrue(leak.confidenceNote.contains("backports"))
    }

    @Test
    fun switchListParsesIdsAndAll() {
        assertEquals(emptySet<String>(), parseDevicePolicySwitches(null))
        assertEquals(emptySet<String>(), parseDevicePolicySwitches(" , "))
        assertEquals(setOf("a-b", "c"), parseDevicePolicySwitches("a-b, c;"))
        assertEquals(setOf("all"), parseDevicePolicySwitches("all"))
    }

    @Test
    fun benchmarkLineListsTraitsActiveEntriesAndSwitches() {
        val asus = known.getValue("asus").traits.copy(codecComponents = listOf("c2.android.vp9.decoder", "OMX.qcom.video.decoder.vp9"))
        val line = DeviceFamilyPolicy.benchmarkLine(DeviceFamilyPolicy.resolve(asus, setOf("qcom-atoll-coc-call", "qcom-legacy-coc-call")))
        assertTrue(line, line.startsWith("BENCHMARK device_policy: revision=${DeviceFamilyPolicy.REVISION} | traits="))
        assertTrue(line, "hardware=qcom;board=msmnile;" in line && "sdk=30" in line && "soc=QUALCOMM" in line)
        assertTrue(line, "| active=qcom-legacy-gatt-call,qcom-legacy-classic-call,qcom-msmnile-call," in line)
        assertTrue(line, "switchedOff=qcom-legacy-coc-call |" in line)
        assertTrue(line, "componentRules=" in line && "c2-android-vp9-decoder-in-place-recovery[1]" in line)
        assertTrue(line, "codec2-encoder-tie-break[1]" in line && "aosp-codec2-opus-decoder-first[1]" in line)
    }

    @Test
    fun matchersFollowTheirDeclaredSemantics() {
        assertTrue(DeviceMatcher().universal)
        assertTrue(DeviceMatcher().matches(DeviceTraits("", "", 1)))
        val msmnile = DevicePolicyRegistry.QCOM_MSMNILE_FAMILY
        assertTrue(msmnile.matches(DeviceTraits("QCOM", "SM8150-AC", 32)))
        assertFalse(msmnile.matches(DeviceTraits("qcom", "msmnile", 33)))
        assertFalse(msmnile.matches(DeviceTraits("mt6765", "msmnile", 29)))
        val extAdv = DeviceMatcher(controller = ControllerMatcher(leExtendedAdvertising = false))
        assertFalse("an unread controller never matches", extAdv.matches(DeviceTraits("", "", 30)))
        val noExt = BluetoothControllerTraits(true, false, false, false, 31, true, true)
        assertTrue(extAdv.matches(DeviceTraits("", "", 30, controller = noExt)))
        assertFalse(extAdv.matches(DeviceTraits("", "", 30, controller = noExt.copy(leExtendedAdvertising = true))))
        val omx = DeviceMatcher(codecComponentPrefixes = setOf("OMX.Exynos."))
        assertTrue(omx.matches(known.getValue("m31").traits))
        assertFalse(omx.matches(known.getValue("s24").traits))
        assertTrue(DeviceMatcher(skins = setOf(OemSkin.ONE_UI), sdkMin = 31).matches(known.getValue("m31").traits))
        assertFalse(DeviceMatcher(skins = setOf(OemSkin.ONE_UI)).matches(known.getValue("asus").traits))
        assertTrue(DeviceMatcher(socModelPrefixes = setOf("mt6769")).matches(known.getValue("xiaomi").traits))
        assertTrue(DeviceMatcher(stackLineages = setOf(BluetoothStackLineage.BLUETOOTH_MODULE)).matches(known.getValue("s22").traits))
        assertFalse(DeviceMatcher(stackLineages = setOf(BluetoothStackLineage.BLUETOOTH_MODULE)).matches(known.getValue("m31").traits))
    }

    @Test
    fun socSkinAndStackDerivationsUseOnlyDocumentedFields() {
        assertEquals(SocFamily.QUALCOMM, deriveSocFamily("QTI", "anything"))
        assertEquals(SocFamily.QUALCOMM, deriveSocFamily("", "qcom"))
        assertEquals(SocFamily.MEDIATEK, deriveSocFamily("", "mt6765"))
        assertEquals(SocFamily.MEDIATEK, deriveSocFamily("Mediatek", "mt6768"))
        assertEquals(SocFamily.EXYNOS, deriveSocFamily("", "exynos9611"))
        assertEquals(SocFamily.EXYNOS, deriveSocFamily("Samsung", "s5e9945"))
        assertEquals(SocFamily.TENSOR, deriveSocFamily("Google", "zuma"))
        assertEquals(SocFamily.UNISOC, deriveSocFamily("Spreadtrum", "ums9230"))
        assertEquals(SocFamily.UNKNOWN, deriveSocFamily("", "gs101"))
        assertEquals(OemSkin.ONE_UI, deriveOemSkin("samsung"))
        assertEquals(OemSkin.COLOROS, deriveOemSkin("OPPO"))
        assertEquals(OemSkin.ASUS, deriveOemSkin("asus"))
        assertEquals(OemSkin.OTHER, deriveOemSkin(""))
        // Five eras (bluetooth.md entry 29); the two old values remain as umbrellas (see DeviceStackLineageTest).
        assertEquals(BluetoothStackLineage.LEGACY_GD_OPTIONAL_31_32, deriveStackLineage(32))
        assertEquals(BluetoothStackLineage.PLATFORM_SYSTEM_BT, deriveStackLineage(32).umbrella)
        assertEquals(BluetoothStackLineage.GD_CORE_33, deriveStackLineage(33))
        assertEquals(BluetoothStackLineage.BLUETOOTH_MODULE, deriveStackLineage(33).umbrella)
    }

    /**
     * tools/device_policy_registry.json is this registry exported for tools/device_policy_report.py.
     * After changing the registry run the JVM tests with DEVICE_POLICY_UPDATE=1 to rewrite it.
     */
    @Test
    fun exportedRegistryJsonIsCurrent() {
        val root = projectRoot()
        val target = File(root, "tools/device_policy_registry.json")
        val exported = DevicePolicyExport.json(KnownDeviceTraits.load(root)) + "\n"
        if (System.getenv("DEVICE_POLICY_UPDATE") == "1") target.writeText(exported, Charsets.UTF_8)
        val current = if (target.isFile) target.readText(Charsets.UTF_8).replace("\r\n", "\n") else ""
        if (current != exported) {
            val fresh = File(root, "app/build/device-policy/device_policy_registry.json")
            fresh.parentFile.mkdirs()
            fresh.writeText(exported, Charsets.UTF_8)
            throw AssertionError("tools/device_policy_registry.json is stale; rerun with DEVICE_POLICY_UPDATE=1 or copy $fresh")
        }
    }
}
