package com.example.bluetoothtest

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGuardPolicyTest {
    @Test
    fun moderateThermalStressDoesNotBlockStreaming() {
        assertFalse(ThermalGuardPolicy.shouldBlockStreaming(PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test
    fun severeThermalStressDoesNotBlockStreaming() {
        assertFalse(ThermalGuardPolicy.shouldBlockStreaming(PowerManager.THERMAL_STATUS_SEVERE))
    }

    @Test
    fun criticalThermalStressBlocksStreaming() {
        assertTrue(ThermalGuardPolicy.shouldBlockStreaming(PowerManager.THERMAL_STATUS_CRITICAL))
    }

    @Test
    fun emergencyThermalStressBlocksStreaming() {
        assertTrue(ThermalGuardPolicy.shouldBlockStreaming(PowerManager.THERMAL_STATUS_EMERGENCY))
    }

    @Test
    fun shutdownThermalStressBlocksStreaming() {
        assertTrue(ThermalGuardPolicy.shouldBlockStreaming(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun lightThermalStressPreservesSelectedWorkload() {
        assertEquals(30, ThermalGuardPolicy.maximumFps(30, PowerManager.THERMAL_STATUS_LIGHT, true))
        assertEquals(
            600_000,
            ThermalGuardPolicy.maximumBitrate(600_000, PowerManager.THERMAL_STATUS_LIGHT, true),
        )
    }

    @Test
    fun moderateThermalStressReducesFpsAndBitrateByTenPercent() {
        assertEquals(27, ThermalGuardPolicy.maximumFps(30, PowerManager.THERMAL_STATUS_MODERATE, true))
        assertEquals(14, ThermalGuardPolicy.maximumFps(15, PowerManager.THERMAL_STATUS_MODERATE, true))
        assertEquals(
            540_000,
            ThermalGuardPolicy.maximumBitrate(600_000, PowerManager.THERMAL_STATUS_MODERATE, true),
        )
    }

    @Test
    fun severeThermalStressUsesEightyPercentWhenEnabled() {
        assertEquals(24, ThermalGuardPolicy.maximumFps(30, PowerManager.THERMAL_STATUS_SEVERE, true))
        assertEquals(12, ThermalGuardPolicy.maximumFps(15, PowerManager.THERMAL_STATUS_SEVERE, true))
        assertEquals(
            480_000,
            ThermalGuardPolicy.maximumBitrate(600_000, PowerManager.THERMAL_STATUS_SEVERE, true),
        )
    }

    @Test
    fun disablingAutomaticCoolingPreservesSelectedWorkload() {
        assertEquals(30, ThermalGuardPolicy.maximumFps(30, PowerManager.THERMAL_STATUS_SEVERE, false))
        assertEquals(
            600_000,
            ThermalGuardPolicy.maximumBitrate(600_000, PowerManager.THERMAL_STATUS_SEVERE, false),
        )
    }

    @Test
    fun atCriticalHeatAVideoCallGoesOnAsVoiceAndAVoiceCallIsUnchanged() {
        for (status in listOf(
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        )) {
            assertFalse(ThermalGuardPolicy.callVideoAllowed(status))
            assertEquals(CallType.VOICE, ThermalGuardPolicy.callTypeAtHeat(CallType.VIDEO, status))
            assertEquals(CallType.VOICE, ThermalGuardPolicy.callTypeAtHeat(CallType.VOICE, status))
        }
    }

    @Test
    fun belowCriticalHeatEveryCallKeepsTheTypeTheUserChose() {
        for (status in listOf(
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
        )) {
            assertTrue(ThermalGuardPolicy.callVideoAllowed(status))
            assertEquals(CallType.VIDEO, ThermalGuardPolicy.callTypeAtHeat(CallType.VIDEO, status))
            assertEquals(CallType.VOICE, ThermalGuardPolicy.callTypeAtHeat(CallType.VOICE, status))
        }
    }
}
