package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraPolicyTest {
    @Before
    fun reset() = CameraSwitches.resetForTest()

    @After
    fun resetAfter() = CameraSwitches.resetForTest()

    private fun traits(
        hardware: String = "qcom",
        board: String = "msmnile",
        sdk: Int = 30,
        manufacturer: String = "asus",
        socManufacturer: String = "",
        codecs: List<String> = emptyList(),
    ) = DeviceTraits(hardware, board, sdk, manufacturer = manufacturer, socManufacturer = socManufacturer, codecComponents = codecs)

    @Test
    fun entriesCarryTheRequiredMetadataAndAreCameraOrCodecArea() {
        val ids = CameraPolicy.entries.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        for (entry in CameraPolicy.entries) {
            assertTrue(entry.id, entry.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
            assertTrue(entry.id, entry.reviewed.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            assertTrue(entry.id, entry.evidence.isNotEmpty())
            assertTrue(entry.id, entry.behaviour.isNotBlank() && entry.title.isNotBlank() && entry.origin.isNotBlank())
            assertTrue("${entry.id} needs a confidence note", entry.confidenceNote.isNotBlank())
            assertTrue("${entry.id} needs a fallback", entry.fallback.isNotBlank())
            assertEquals("test_device_policy_off=${entry.id}", entry.offSwitch)
            assertTrue("${entry.id} needs sources", entry.sources.isNotEmpty())
            assertNotEquals(PolicySourceStatus.EVIDENCE_ONLY, entry.sourceStatus)
            entry.sources.forEach { source ->
                assertTrue("${entry.id}: ${source.reference}", source.reference.startsWith("https://") && source.note.isNotBlank())
            }
            assertTrue(entry.id, entry.effects.isNotEmpty())
            assertTrue(entry.id, entry.area == PolicyArea.CAMERA || entry.area == PolicyArea.CODEC)
            if (entry.proposed) assertEquals(entry.id, PolicyValidation.EXPERIMENTAL, entry.validation)
        }
    }

    @Test
    fun cameraEntriesAreListedExactlyOnceInTheRegistry() {
        val registry = DevicePolicyRegistry.entries.map { it.id }
        CameraPolicy.entries.forEach { entry -> assertEquals(entry.id, 1, registry.count { it == entry.id }) }
    }

    @Test
    fun waitForOnClosedMatchesQualcommHardwareUpToAndroid12Only() {
        val asus = traits(hardware = "qcom", board = "msmnile", sdk = 30)
        val xiaomi = traits(hardware = "qcom", board = "atoll", sdk = 31, manufacturer = "xiaomi")
        val s22Android13 = traits(hardware = "qcom", board = "taro", sdk = 33, manufacturer = "samsung")
        val m31 = traits(hardware = "exynos9611", board = "universal9611", sdk = 31, manufacturer = "samsung")
        assertEquals(CameraRebuildMode.WAIT_SESSION_CLOSED, CameraRebuildPolicy.modeFor(asus, disabled = emptySet()))
        assertEquals(CameraRebuildMode.WAIT_SESSION_CLOSED, CameraRebuildPolicy.modeFor(xiaomi, disabled = emptySet()))
        assertEquals(CameraRebuildMode.SESSION_ONLY, CameraRebuildPolicy.modeFor(s22Android13, disabled = emptySet()))
        assertEquals(CameraRebuildMode.SESSION_ONLY, CameraRebuildPolicy.modeFor(m31, disabled = emptySet()))
    }

    @Test
    fun offSwitchRestoresTheImmediateRecreate() {
        val asus = traits()
        assertEquals(
            CameraRebuildMode.SESSION_ONLY,
            CameraRebuildPolicy.modeFor(asus, disabled = setOf("camera-session-wait-closed")),
        )
        assertEquals(CameraRebuildMode.SESSION_ONLY, CameraRebuildPolicy.modeFor(asus, disabled = setOf("all")))
    }

    @Test
    fun deviceReopenNeedsTheExperimentalSwitchAndAMatchingFamily() {
        val s24Snapdragon = traits(hardware = "qcom", board = "kalama", sdk = 36, manufacturer = "samsung", socManufacturer = "QTI")
        val s24Exynos = traits(hardware = "s5e9945", board = "erd9945", sdk = 36, manufacturer = "samsung", socManufacturer = "Samsung")
        val vivo = traits(hardware = "mt6785", board = "k6785", sdk = 33, manufacturer = "vivo")
        val oneplus = traits(hardware = "qcom", board = "lahaina", sdk = 31, manufacturer = "oneplus")
        // Default off: no reopen anywhere.
        listOf(s24Snapdragon, s24Exynos, vivo, oneplus).forEach {
            assertNotEquals(CameraRebuildMode.REOPEN_DEVICE, CameraRebuildPolicy.modeFor(it, reopenSwitch = false, disabled = emptySet()))
        }
        assertEquals(CameraRebuildMode.REOPEN_DEVICE, CameraRebuildPolicy.modeFor(s24Snapdragon, reopenSwitch = true, disabled = emptySet()))
        assertEquals(CameraRebuildMode.REOPEN_DEVICE, CameraRebuildPolicy.modeFor(vivo, reopenSwitch = true, disabled = emptySet()))
        assertEquals(CameraRebuildMode.REOPEN_DEVICE, CameraRebuildPolicy.modeFor(oneplus, reopenSwitch = true, disabled = emptySet()))
        // The Exynos S24 is not in the CameraX list: the switch changes nothing.
        assertNotEquals(CameraRebuildMode.REOPEN_DEVICE, CameraRebuildPolicy.modeFor(s24Exynos, reopenSwitch = true, disabled = emptySet()))
        // A proposal's off-switch id also wins.
        assertNotEquals(
            CameraRebuildMode.REOPEN_DEVICE,
            CameraRebuildPolicy.modeFor(vivo, reopenSwitch = true, disabled = setOf("camera-device-reopen-vivo")),
        )
    }

    @Test
    fun launchExtraSwitchesAreOffByDefaultAndParse() {
        assertFalse(CameraSwitches.reopenDevice || CameraSwitches.sessionConfiguration || CameraSwitches.legacyAeRange ||
            CameraSwitches.starvationFallback || CameraSwitches.capabilityProbeConfigure || CameraSwitches.encoderColourKeys ||
            CameraSwitches.encoderKeyLatency || CameraSwitches.releaseCaptureTiming || CameraSwitches.rotateAndCropNone ||
            CameraSwitches.yuvTailClamp || CameraSwitches.proximityPauseHysteresis)
        assertEquals(null, CameraSwitches.exynosQpRange)
        CameraSwitches.applyExtras(
            { key, default -> if (key in setOf("test_camera_reopen", "test_encoder_colour_keys")) true else default },
            { key -> if (key == "test_exynos_qp_range") "20,50" else null },
        )
        if (!BuildConfig.DEBUG) {
            // A release build ignores launch extras: the experiments stay off.
            assertFalse(CameraSwitches.reopenDevice || CameraSwitches.encoderColourKeys)
            assertEquals(null, CameraSwitches.exynosQpRange)
            return
        }
        assertTrue(CameraSwitches.reopenDevice)
        assertTrue(CameraSwitches.encoderColourKeys)
        assertFalse(CameraSwitches.sessionConfiguration)
        assertFalse(CameraSwitches.proximityPauseHysteresis)
        CameraSwitches.applyExtras({ key, default -> if (key == "test_proximity_pause_hysteresis") true else default }, { null })
        assertTrue("Experimental arm reachable by its launch extra", CameraSwitches.proximityPauseHysteresis)
        CameraSwitches.applyExtras(
            { key, default -> if (key in setOf("test_camera_reopen", "test_encoder_colour_keys")) true else default },
            { key -> if (key == "test_exynos_qp_range") "20,50" else null },
        )
        assertEquals(20..50, CameraSwitches.exynosQpRange)
        CameraSwitches.applyExtras({ _, default -> default }, { null })
        assertFalse(CameraSwitches.reopenDevice)
        assertEquals(null, CameraSwitches.exynosQpRange)
    }

    @Test
    fun qpRangeRejectsMalformedValues() {
        assertEquals(10..40, CameraSwitches.parseQpRange("10;40"))
        assertEquals(null, CameraSwitches.parseQpRange("40,10"))
        assertEquals(null, CameraSwitches.parseQpRange("a,b"))
        assertEquals(null, CameraSwitches.parseQpRange("10"))
        assertEquals(null, CameraSwitches.parseQpRange("0,300"))
        assertEquals(null, CameraSwitches.parseQpRange(null))
    }

    @Test
    fun encoderInitFallbackIsOnForUsersAndOffForBenchmarksBenchAndTheOffSwitch() {
        val phone = traits()
        assertTrue(CameraPolicy.encoderInitFallbackEnabled(phone, benchmarkLaunch = false, bench = false, disabled = emptySet()))
        assertFalse(CameraPolicy.encoderInitFallbackEnabled(phone, benchmarkLaunch = true, bench = false, disabled = emptySet()))
        assertFalse(CameraPolicy.encoderInitFallbackEnabled(phone, benchmarkLaunch = false, bench = true, disabled = emptySet()))
        assertFalse(CameraPolicy.encoderInitFallbackEnabled(phone, benchmarkLaunch = false, bench = false, disabled = setOf("encoder-init-fallback")))
        assertFalse(CameraPolicy.encoderInitFallbackEnabled(phone, benchmarkLaunch = false, bench = false, disabled = setOf("all")))
    }

    @Test
    fun diagnosticEntriesAreOnForEveryPhoneAndProposalsAreInert() {
        val phone = traits()
        val resolved = CameraPolicy.resolve(phone, emptySet())
        assertTrue(resolved.isActive("camera-stream-combination-validation"))
        assertTrue(resolved.isActive("camera-open-error-retry"))
        assertTrue(resolved.isActive("camera-frame-stall-watchdog"))
        assertTrue(resolved.isActive("encoder-init-fallback"))
        val proposalIds = CameraPolicy.entries.filter { it.proposed }.map { it.id }.toSet()
        assertTrue(resolved.active.none { it.id in proposalIds })
        // Universal proposals are reported as matching; family seeds are not on this phone.
        val reported = resolved.matchingProposals.map { it.id }
        assertTrue("encoder-explicit-colour-keys" in reported)
        assertFalse("camera-starvation-template-preview-huawei" in reported)
        val huawei = CameraPolicy.resolve(traits(manufacturer = "huawei", hardware = "kirin980", sdk = 29), emptySet())
        assertTrue("camera-starvation-template-preview-huawei" in huawei.matchingProposals.map { it.id })
    }

    @Test
    fun exynosQpRangeProposalNeedsAnExynosComponent() {
        val withComponent = CameraPolicy.resolve(traits(codecs = listOf("OMX.Exynos.VP9.Encoder")), emptySet())
        val without = CameraPolicy.resolve(traits(codecs = listOf("c2.qti.av1.encoder")), emptySet())
        assertTrue("exynos-vendor-qp-range" in withComponent.matchingProposals.map { it.id })
        assertFalse("exynos-vendor-qp-range" in without.matchingProposals.map { it.id })
    }

    @Test
    fun privacyToggleNoticeNeedsAndroid12() {
        assertFalse(CameraPolicy.isOn("camera-privacy-toggle-notice", traits(sdk = 30), emptySet()))
        assertTrue(CameraPolicy.isOn("camera-privacy-toggle-notice", traits(sdk = 31), emptySet()))
    }

    @Test
    fun noSourceUsesAPhysicalCameraIdOrTheOperatingRate() {
        // V-17: physical camera ids make some HALs fail with ERROR_CAMERA_DEVICE; the owned phones use logical ids.
        // KEY_OPERATING_RATE stays unset (a huge value crashes configure on several parts).
        val root = java.io.File("src/main/java/com/example/bluetoothtest")
        if (!root.isDirectory) return
        val code = root.listFiles { file -> file.extension == "kt" }.orEmpty().filterNot { it.name == "CameraPolicy.kt" }
        val offenders = code.filter { file ->
            file.readLines().any { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*") &&
                    (Regex("setPhysicalCameraId\\s*\\(").containsMatchIn(trimmed) ||
                        Regex("setInteger\\s*\\(\\s*MediaFormat\\.KEY_OPERATING_RATE").containsMatchIn(trimmed) ||
                        Regex("setFloat\\s*\\(\\s*MediaFormat\\.KEY_OPERATING_RATE").containsMatchIn(trimmed))
            }
        }
        assertEquals(emptyList<String>(), offenders.map { it.name })
    }
}
