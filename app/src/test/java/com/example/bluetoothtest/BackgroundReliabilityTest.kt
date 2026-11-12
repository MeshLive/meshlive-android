package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundReliabilityTest {
    private fun traits(manufacturer: String, sdk: Int = 34) =
        DeviceTraits("qcom", "taro", sdk, manufacturer = manufacturer, socManufacturer = "QTI")

    private fun snapshot(
        manufacturer: String,
        sdk: Int = 34,
        ignoringBatteryOptimizations: Boolean? = true,
        backgroundRestricted: Boolean? = false,
        standbyBucket: Int? = 10,
        powerSaveMode: Boolean? = false,
        autoRevokeExempt: Boolean? = true,
        staminaMode: Boolean? = null,
        killers: Set<String> = emptySet(),
        fgsFailure: String? = null,
    ) = BackgroundSnapshot(
        sdk, manufacturer, ignoringBatteryOptimizations, backgroundRestricted, standbyBucket, powerSaveMode,
        autoRevokeExempt, staminaMode, killers, fgsFailure,
    )

    private fun issues(s: BackgroundSnapshot, off: Set<String> = emptySet()) =
        BackgroundReliability.evaluate(s, DeviceFamilyPolicy.resolve(traits(s.manufacturer, s.sdkInt), off))

    @Test
    fun cleanStockPhoneShowsOnlyItsMakerAdvice() {
        val result = issues(snapshot("Google"))
        // The system Bluetooth app note is information until something points at trouble (an always-on role, an update).
        assertEquals(listOf("oem-stock-android", "system-bluetooth-app"), result.map { it.id })
        val oem = result.first()
        assertEquals(ReliabilitySeverity.ADVISORY, oem.severity)
        assertEquals(ReliabilitySeverity.INFO, result.last().severity)
        assertTrue(oem.actions.last().action == android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    fun restrictedStatesRaiseIssuesWithTheirSeverity() {
        val result = issues(
            snapshot(
                "Xiaomi", ignoringBatteryOptimizations = false, backgroundRestricted = true,
                standbyBucket = BackgroundReliability.STANDBY_BUCKET_RESTRICTED, powerSaveMode = true, autoRevokeExempt = false,
            ),
        ).associateBy { it.id }
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, result.getValue("background-restricted").severity)
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, result.getValue("standby-bucket").severity)
        assertEquals(ReliabilitySeverity.ADVISORY, result.getValue("battery-optimization").severity)
        // Q38: the one-tap prompt comes first, the list screen is the fallback.
        assertEquals(
            if (BuildConfig.DISTRIBUTION == "play") listOf(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ) else listOf(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            ),
            result.getValue("battery-optimization").actions.take(2).map { it.action },
        )
        assertEquals(BuildConfig.DISTRIBUTION != "play", result.getValue("battery-optimization").actions.first().withPackageUri)
        assertEquals(ReliabilitySeverity.ADVISORY, result.getValue("unused-app-restrictions").severity)
        assertEquals(ReliabilitySeverity.INFO, result.getValue("power-save-mode").severity)
        assertTrue(result.getValue("oem-xiaomi").steps.any { "Autostart" in it })
        assertTrue(result.values.all { it.validation == "upstream-documented" })
        assertEquals(
            ReliabilitySeverity.ADVISORY,
            issues(snapshot("Xiaomi", standbyBucket = BackgroundReliability.STANDBY_BUCKET_RARE)).single { it.id == "standby-bucket" }.severity,
        )
        assertTrue(issues(snapshot("Xiaomi", standbyBucket = 30)).none { it.id == "standby-bucket" })
    }

    @Test
    fun installedKillerAppMakesTheMakerAdviceActionNeeded() {
        val huawei = issues(snapshot("HUAWEI", killers = setOf("com.huawei.powergenie"))).single { it.id == "oem-huawei" }
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, huawei.severity)
        assertTrue(huawei.detail, "com.huawei.powergenie" in huawei.detail)
    }

    @Test
    fun unknownMakerGetsGenericAdviceAndOffSwitchesWork() {
        val zte = issues(snapshot("ZTE"))
        assertEquals(listOf("oem-generic", "system-bluetooth-app"), zte.map { it.id })
        val xiaomiOff = issues(snapshot("Xiaomi", ignoringBatteryOptimizations = false), setOf("bg-oem-xiaomi", "bg-battery-optimization"))
        assertEquals(listOf("system-bluetooth-app", "incoming-call-popup"), xiaomiOff.map { it.id })
        assertTrue(issues(snapshot("Xiaomi", ignoringBatteryOptimizations = false), setOf("bg-oem-xiaomi", "bg-battery-optimization", "bg-system-bluetooth-app", "bg-incoming-call-popup")).isEmpty())
        assertTrue(issues(snapshot("Xiaomi"), setOf("all")).isEmpty())
    }

    @Test
    fun foregroundRefusalAndStaminaAreReported() {
        val fgs = issues(snapshot("samsung", fgsFailure = "ForegroundServiceStartNotAllowedException"))
        assertEquals(ReliabilitySeverity.ACTION_NEEDED, fgs.single { it.id == "foreground-service-refused" }.severity)
        // Android 11 has no background-start refusal: the entry starts at API 31.
        assertTrue(issues(snapshot("samsung", sdk = 30, fgsFailure = "x")).none { it.id == "foreground-service-refused" })
        assertTrue(issues(snapshot("Sony", staminaMode = true)).any { it.id == "sony-stamina" })
    }

    @Test
    fun everyDontKillMyAppVendorIsAManufacturerMatchedGuidanceEntry() {
        val oem = BackgroundPolicy.OEM_GUIDANCE
        for (entry in oem) {
            assertTrue(entry.id, entry.id.startsWith("bg-oem-"))
            assertEquals(entry.id, PolicyArea.BACKGROUND, entry.area)
            assertEquals(entry.id, PolicyValidation.UPSTREAM_DOCUMENTED, entry.validation)
            assertTrue(entry.id, entry.matcher.manufacturers.isNotEmpty())
            assertEquals(entry.id, DeviceMatcher(manufacturers = entry.matcher.manufacturers), entry.matcher)
            val guidance = entry.effects.filterIsInstance<OemBackgroundGuidance>().single()
            assertTrue(entry.id, guidance.steps.size in 2..6 && guidance.steps.all { it.length <= 200 })
            assertTrue(entry.id, entry.sources.first().reference.contains(BackgroundPolicy.DKMA_COMMIT))
        }
        val makers = oem.flatMap { it.matcher.manufacturers }
        assertEquals(makers.size, makers.toSet().size)
        for (maker in listOf("samsung", "xiaomi", "huawei", "oneplus", "oppo", "vivo", "realme", "motorola", "sony", "nokia", "hmd global", "google", "tecno", "asus")) {
            assertTrue(maker, maker in makers)
        }
    }

    @Test
    fun benchmarkPhonesGetTheirMakersGuidance() {
        val expected = mapOf("s24" to "samsung", "s22" to "samsung", "m31" to "samsung", "a55" to "samsung",
            "asus" to "asus", "a15s" to "oppo", "xiaomi" to "xiaomi", "realme" to "realme")
        for (known in KnownDeviceTraits.load()) {
            val guidance = DeviceFamilyPolicy.resolve(known.traits, emptySet()).effects<OemBackgroundGuidance>()
            assertEquals(known.alias, listOf(expected.getValue(known.alias)), guidance.map { it.vendorKey })
        }
    }

    @Test
    fun statusLineIsNotABenchmarkContractLine() {
        val s = snapshot("Xiaomi", ignoringBatteryOptimizations = false)
        val line = BackgroundReliability.statusLine(BackgroundReliabilityStatus(s, issues(s)))
        assertTrue(line, line.startsWith("Background reliability: sdk=34 | manufacturer=Xiaomi | ignoringBatteryOpt=0"))
        assertTrue(line, line.endsWith("issues=battery-optimization:advisory,oem-xiaomi:advisory,system-bluetooth-app:info,incoming-call-popup:info"))
        assertFalse(line.contains("BENCHMARK"))
    }

    @Test
    fun foregroundRefusalClassificationAcceptsOnlyAndroidsTwoRefusals() {
        // AOSP android14 ActiveServices: the while-in-use type refusal.
        assertTrue(isForegroundServiceRefusal(SecurityException(
            "Starting FGS with type camera callerApp=x targetSDK=36 requires permissions: [...] and the app must be " +
                "in the eligible state/exemptions to access the foreground only permission",
        )))
        // Manifest or permission bugs must surface.
        assertFalse(isForegroundServiceRefusal(SecurityException("Permission Denial: startForeground requires FOREGROUND_SERVICE")))
        assertFalse(isForegroundServiceRefusal(IllegalStateException("other")))
        assertFalse(isForegroundServiceRefusal(InvalidForegroundServiceTypeException("Starting FGS with type camera has been prohibited")))
        assertTrue(isForegroundServiceRefusal(ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")))
    }

    @Test
    fun foregroundOutcomeReportsRequestedVersusEffectiveTypes() {
        val all = ActiveVideoSessionService.requestedTypes(usesCamera = true, usesMicrophone = true)
        val ok = ForegroundServiceOutcome(all, all, null)
        assertFalse(ok.mismatch)
        assertEquals(
            "BENCHMARK foreground_service: requested=connectedDevice+camera+microphone | " +
                "effective=connectedDevice+camera+microphone | refused=none",
            ok.benchmarkLine(),
        )
        val noCamera = ForegroundServiceOutcome(all, ActiveVideoSessionService.requestedTypes(false, false), "SecurityException: x | y")
        assertTrue(noCamera.mismatch)
        assertEquals(
            "BENCHMARK foreground_service: requested=connectedDevice+camera+microphone | effective=connectedDevice | " +
                "refused=SecurityException: x / y",
            noCamera.benchmarkLine(),
        )
        assertTrue(noCamera.userNotice(), noCamera.userNotice().isNotBlank())
        val refused = ForegroundServiceOutcome(ActiveVideoSessionService.requestedTypes(false, true), 0, "ForegroundServiceStartNotAllowedException")
        assertTrue(refused.benchmarkLine(), refused.benchmarkLine().contains("effective=none"))
        assertTrue(refused.userNotice().isNotBlank() && refused.userNotice() != noCamera.userNotice())
        assertEquals("connectedDevice+microphone", foregroundTypeLabels(ActiveVideoSessionService.requestedTypes(false, true)))
    }

    @Test
    fun scanQuotaDeferralIsShownAsInformation() {
        val deferred = issues(snapshot("Google").copy(scanDeferredMs = 4_200L)).single { it.id == "scan-quota-deferred" }
        assertEquals(ReliabilitySeverity.INFO, deferred.severity)
        assertTrue(deferred.detail, "5 s" in deferred.detail)
        assertTrue(issues(snapshot("Google").copy(scanDeferredMs = 4_200L), setOf("le-scan-start-quota")).none { it.id == "scan-quota-deferred" })
    }
}

/** Same class names as the platform exceptions; classification is by name (they are API 31+/34+ classes). */
private class ForegroundServiceStartNotAllowedException(message: String) : IllegalStateException(message)
private class InvalidForegroundServiceTypeException(message: String) : IllegalArgumentException(message)
