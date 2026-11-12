package com.example.bluetoothtest

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalBatteryFallbackTest {
    @Test
    fun batteryTemperatureMapsToStatusesAndNeverReachesCritical() {
        val f = ThermalBatteryFallback
        assertEquals(PowerManager.THERMAL_STATUS_NONE, f.statusFromBatteryTemperature(null))
        assertEquals(PowerManager.THERMAL_STATUS_NONE, f.statusFromBatteryTemperature(Float.NaN))
        assertEquals(PowerManager.THERMAL_STATUS_NONE, f.statusFromBatteryTemperature(39.9f))
        assertEquals(PowerManager.THERMAL_STATUS_LIGHT, f.statusFromBatteryTemperature(40f))
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, f.statusFromBatteryTemperature(43f))
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, f.statusFromBatteryTemperature(46f))
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, f.statusFromBatteryTemperature(80f))
        assertFalse(ThermalGuardPolicy.shouldBlockStreaming(f.statusFromBatteryTemperature(80f)))
    }

    @Test
    fun tenthsOfADegreeConvertAndMissingValuesStayMissing() {
        assertEquals(31.5f, ThermalBatteryFallback.celsiusFromTenths(315)!!, 0f)
        assertNull(ThermalBatteryFallback.celsiusFromTenths(Int.MIN_VALUE))
    }

    @Test
    fun fallbackDrivesTheLadderOnlyWhenTheSwitchIsOnAndTheSamplerFoundNoSkinThresholds() {
        val none = PowerManager.THERMAL_STATUS_NONE
        val moderate = PowerManager.THERMAL_STATUS_MODERATE
        // Switch off: the platform status stands.
        assertEquals(none, ThermalBatteryFallback.effectiveStatus(none, 47f, switchOn = false, noSkinThresholds = true))
        // Headroom works: the platform status stands.
        assertEquals(none, ThermalBatteryFallback.effectiveStatus(none, 47f, switchOn = true, noSkinThresholds = false))
        // No skin thresholds: the hotter of the two wins.
        assertEquals(
            PowerManager.THERMAL_STATUS_SEVERE,
            ThermalBatteryFallback.effectiveStatus(none, 47f, switchOn = true, noSkinThresholds = true),
        )
        assertEquals(
            PowerManager.THERMAL_STATUS_CRITICAL,
            ThermalBatteryFallback.effectiveStatus(PowerManager.THERMAL_STATUS_CRITICAL, 41f, true, true),
        )
        assertEquals(moderate, ThermalBatteryFallback.effectiveStatus(moderate, 20f, true, true))
    }

    @Test
    fun sourceNamesWhichSignalDrives() {
        assertEquals(ThermalBatteryFallback.Source.FALLBACK_OFF, ThermalBatteryFallback.source(false, true))
        assertEquals(ThermalBatteryFallback.Source.HEADROOM_FINE, ThermalBatteryFallback.source(true, false))
        assertEquals(ThermalBatteryFallback.Source.BATTERY_TEMPERATURE, ThermalBatteryFallback.source(true, true))
    }

    @Test
    fun aSilentStatusWhileTheBatteryIsHotIsDetectedWithoutTheExperiment() {
        assertTrue(ThermalBatteryFallback.statusLooksSilent(PowerManager.THERMAL_STATUS_NONE, 44f))
        assertFalse(ThermalBatteryFallback.statusLooksSilent(PowerManager.THERMAL_STATUS_NONE, 41f))
        assertFalse(ThermalBatteryFallback.statusLooksSilent(PowerManager.THERMAL_STATUS_MODERATE, 47f))
    }

    @Test
    fun detectionThroughTheSamplerNeedsThreeNanReadings() {
        var now = 0L
        val sampler = ThermalHeadroomSampler({ Float.NaN }, { now })
        now = 0
        sampler.tick()
        now = 2_000
        sampler.tick()
        assertFalse(ThermalBatteryFallback.source(true, sampler.noSkinThresholds) == ThermalBatteryFallback.Source.BATTERY_TEMPERATURE)
        now = 4_000
        sampler.tick()
        assertEquals(ThermalBatteryFallback.Source.BATTERY_TEMPERATURE, ThermalBatteryFallback.source(true, sampler.noSkinThresholds))
        val line = ThermalBatteryFallback.benchmarkLine(PowerManager.THERMAL_STATUS_NONE, 44.2f, true, sampler)
        assertTrue(
            line,
            line.startsWith(
                "BENCHMARK thermal_fallback: source=battery_temperature | reported=NONE | batteryC=44.2 | " +
                    "batteryStatus=MODERATE | statusSilent=true | samplerCalls=3 | samplerNan=3",
            ),
        )
    }

    @Test
    fun theSwitchIsDebugOnlyAndDefaultsOff() {
        assertFalse(ThermalSwitches.batteryFallback)
        assertFalse(MemoryThermalPolicy.headroomSamplerNeeded(true))
        ThermalSwitches.batteryFallback = true
        try {
            if (!BuildConfig.DEBUG) {
                // A release build ignores the switch: no sampler.
                assertFalse(MemoryThermalPolicy.headroomSamplerNeeded(true))
                return
            }
            assertTrue(MemoryThermalPolicy.headroomSamplerNeeded(true))
            // Benchmark profiles run with automatic cooling off: the sampler is never needed there.
            assertFalse(MemoryThermalPolicy.headroomSamplerNeeded(false))
        } finally {
            ThermalSwitches.batteryFallback = false
        }
    }
}
