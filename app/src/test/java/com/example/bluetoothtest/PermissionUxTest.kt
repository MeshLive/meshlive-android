package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Android 16 emulator crawl, 2026-10-21: the QR scanner, the Go live gate and the first-run update question. */
class PermissionUxTest {
    @Before
    fun clean() = FeatureCatalog.clearForTest()

    @After
    fun cleanAfter() = FeatureCatalog.clearForTest()

    // ----- QR scanner camera check -----

    @Test
    fun theScannerOpensOnlyWithTheCameraGranted() {
        assertEquals(CameraScanGate.Step.LAUNCH, CameraScanGate.beforeScan(granted = true))
        assertEquals(CameraScanGate.Step.REQUEST, CameraScanGate.beforeScan(granted = false))
        assertEquals(CameraScanGate.Step.LAUNCH, CameraScanGate.afterRequest(granted = true, rationaleDue = false))
    }

    @Test
    fun aCameraDeniedForGoodLeadsToSettingsNotToZxingsCameraError() {
        // The request returns at once with no rationale due: the system will not ask again.
        assertEquals(CameraScanGate.Step.SETTINGS, CameraScanGate.afterRequest(granted = false, rationaleDue = false))
    }

    @Test
    fun aFirstRefusalExplainsAndAsksAgainNextTime() {
        assertEquals(CameraScanGate.Step.EXPLAIN, CameraScanGate.afterRequest(granted = false, rationaleDue = true))
        assertEquals(CameraScanGate.Step.REQUEST, CameraScanGate.beforeScan(granted = false))
    }

    @Test
    fun theScannerPromptClearsTheNavigationBarOnAndroid16() {
        // API 36 emulator: prompt bottom at 2400 px, navigation bar from 2337 px (63 px inset).
        val promptBottom = 2400
        val navigationTop = 2337
        val (_, _, bottomMargin) = ScannerInsets.statusMargins(baseBottomPx = 0, left = 0, right = 0, bottom = promptBottom - navigationTop)
        assertTrue(promptBottom - bottomMargin <= navigationTop)
        // Before Android 15 the window ends above the bar: no inset, the prompt stays where zxing puts it.
        assertEquals(Triple(0, 0, 12), ScannerInsets.statusMargins(baseBottomPx = 12, left = 0, right = 0, bottom = 0))
    }

    // ----- Go live gate -----

    @Test
    fun aMissingPermissionIsShownWithAWayToFixIt() {
        val block = LiveStartGate.block(sessionActive = false, transportReady = true, permissionsReady = false, bluetoothReady = true)
        assertEquals(LiveStartBlock.PERMISSIONS, block)
        assertEquals(LiveStartAction.ASK, LiveStartGate.action(block, askedThisProcess = false, rationaleDue = false))
        assertEquals(LiveStartAction.ASK, LiveStartGate.action(block, askedThisProcess = true, rationaleDue = true))
        assertEquals(LiveStartAction.OPEN_SETTINGS, LiveStartGate.action(block, askedThisProcess = true, rationaleDue = false))
    }

    @Test
    fun bluetoothOffIsItsOwnReason() {
        val block = LiveStartGate.block(sessionActive = false, transportReady = true, permissionsReady = true, bluetoothReady = false)
        assertEquals(LiveStartBlock.BLUETOOTH_OFF, block)
        assertEquals(LiveStartAction.TURN_ON_BLUETOOTH, LiveStartGate.action(block, askedThisProcess = true, rationaleDue = false))
    }

    @Test
    fun permissionsComeBeforeBluetoothAndNoBluetoothBeforeBoth() {
        assertEquals(
            LiveStartBlock.PERMISSIONS,
            LiveStartGate.block(sessionActive = false, transportReady = true, permissionsReady = false, bluetoothReady = false),
        )
        val none = LiveStartGate.block(sessionActive = false, transportReady = false, permissionsReady = false, bluetoothReady = false)
        assertEquals(LiveStartBlock.NO_BLUETOOTH, none)
        assertEquals(LiveStartAction.NONE, LiveStartGate.action(none, askedThisProcess = true, rationaleDue = false))
    }

    @Test
    fun aRunningSessionOrAReadyPhoneShowsNoNotice() {
        assertEquals(LiveStartBlock.NONE, LiveStartGate.block(sessionActive = true, transportReady = true, permissionsReady = false, bluetoothReady = false))
        assertEquals(LiveStartBlock.NONE, LiveStartGate.block(sessionActive = false, transportReady = true, permissionsReady = true, bluetoothReady = true))
    }

    // ----- first-run update question on a debug build -----

    @Test
    fun aTestExtraStillMakesABenchmarkLaunch() {
        assertTrue(BenchmarkLaunch.fromExtras(setOf(UpdateSettings.EXTRA_DEBUG_PIN_OWN_SIGNER), userLaunch = false, debug = true))
        // A release launch is never a benchmark launch, whatever extras another app sends.
        assertFalse(BenchmarkLaunch.fromExtras(setOf(UpdateSettings.EXTRA_DEBUG_PIN_OWN_SIGNER), userLaunch = false, debug = false))
        assertFalse(BenchmarkLaunch.fromExtras(emptySet(), userLaunch = false))
        assertFalse(BenchmarkLaunch.fromExtras(setOf("android.intent.extra.TEXT"), userLaunch = false))
    }

    @Test
    fun aUserLaunchWithTheUpdateStandInsReachesTheQuestionAndItsYesPath() {
        val extras = setOf(UpdateSettings.EXTRA_DEBUG_PIN_OWN_SIGNER, UpdateSettings.EXTRA_DEBUG_LATEST_URL, BenchmarkLaunch.EXTRA_USER_LAUNCH)
        val benchmark = BenchmarkLaunch.fromExtras(extras, userLaunch = true)
        assertFalse(benchmark)
        // The pinned own signer offers the online check; nobody asked yet; the home screen is idle.
        assertEquals(
            UpdateResumeAction.ASK,
            UpdateResume.action(
                updaterAvailable = true, onlineOffered = true, homeIdle = true, askedAtMs = 0L,
                benchmarkLaunch = benchmark, checkEnabled = false,
            ),
        )
        // Yes is stored as the explicit choice and the catalog reads it (a benchmark launch would read the extra).
        UpdateFeatures.registerAll(true)
        val stored = mapOf(UpdateSettings.PREF_CHECK_FOR_UPDATES to true)
        assertTrue(FeatureCatalog.resolveAll(benchmark, { it in extras }, { stored[it] }).isOn(UpdateFeatures.checkForUpdates.id))
        assertFalse(FeatureCatalog.resolveAll(true, { it in extras }, { stored[it] }).isOn(UpdateFeatures.checkForUpdates.id))
    }
}
