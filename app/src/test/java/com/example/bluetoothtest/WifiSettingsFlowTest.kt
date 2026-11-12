package com.example.bluetoothtest

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Wi-Fi Settings switches as the user meets them: the choice survives a restart, the sub-switches follow the carrier
 * switch, and a permission the user refused never writes the choice. The card's own permission flow runs through
 * [WifiEnableDriver], which is pure; MainActivity only supplies the dialogs.
 */
class WifiSettingsFlowTest {
    private lateinit var phone: FakePhone

    @Before
    fun setUp() {
        phone = FakePhone(Files.createTempDirectory("wifi-settings-flow").toFile())
        FeatureCatalog.clearForTest()
    }

    @After
    fun tearDown() {
        FeatureCatalog.clearForTest()
        WifiSwitchHub.resetForTest()
        phone.root.deleteRecursively()
    }

    private fun settings() = phone.preferences(UpdateSettings.PREFERENCES_FILE)

    /** An app start: every feature registers (AppFeatures, as MainActivity and the Application do), then the stored choices are read. */
    private fun startApp(): FeatureSnapshot {
        FeatureCatalog.clearForTest()
        AppFeatures.registerAll(updaterAvailable = true)
        return FeatureCatalog.refresh(
            false,
            { null },
            { key -> if (settings().contains(key)) settings().getBoolean(key, false) else null },
            advancedSettings = settings().getBoolean(PREF_ADVANCED_SETTINGS, false),
        )
    }

    @Test
    fun turningTheCarrierOnSurvivesARestartAndOffTurnsTheSubSwitchesOff() {
        startApp()
        settings().edit()
            .putBoolean(WifiFeatures.ID_CARRIER, true)
            .putBoolean(WifiFeatures.ID_WIDER_REACH, true)
            .apply()
        val on = startApp()
        assertTrue(on.isOn(WifiFeatures.ID_CARRIER))
        assertTrue(on.isOn(WifiFeatures.ID_WIDER_REACH))

        settings().edit().putBoolean(WifiFeatures.ID_CARRIER, false).apply()
        val off = startApp()
        assertFalse(off.isOn(WifiFeatures.ID_WIDER_REACH))
        assertFalse(WifiSwitchHub.of(off).any)
    }

    @Test
    fun aDeniedPermissionNeverWritesTheChoice() {
        val preferences = settings()
        var proceeded = 0
        val driver = WifiEnableDriver(
            flow = WifiEnableFlow(33, { false }, { true }),
            sdkInt = 33,
            askPermissions = { _, answered -> answered() },
            showLocationOff = {},
            showDenied = {},
        )
        assertTrue(
            driver.confirm(WifiFeatures.ID_CARRIER) {
                proceeded++
                preferences.edit().putBoolean(WifiFeatures.ID_CARRIER, true).apply()
            },
        )
        assertEquals(0, proceeded)
        assertFalse(preferences.contains(WifiFeatures.ID_CARRIER))
        assertFalse(startApp().isOn(WifiFeatures.ID_CARRIER))
    }

    @Test
    fun aGrantedPermissionWritesTheChoiceOnce() {
        val preferences = settings()
        val granted = mutableSetOf<String>()
        var proceeded = 0
        val driver = WifiEnableDriver(
            flow = WifiEnableFlow(33, { it in granted }, { true }),
            sdkInt = 33,
            askPermissions = { permissions, answered -> granted += permissions; answered() },
            showLocationOff = {},
            showDenied = {},
        )
        assertTrue(
            driver.confirm(WifiFeatures.ID_CARRIER) {
                proceeded++
                preferences.edit().putBoolean(WifiFeatures.ID_CARRIER, true).apply()
            },
        )
        assertEquals(1, proceeded)
        assertTrue(preferences.getBoolean(WifiFeatures.ID_CARRIER, false))
        assertTrue(startApp().isOn(WifiFeatures.ID_CARRIER))
    }
}
