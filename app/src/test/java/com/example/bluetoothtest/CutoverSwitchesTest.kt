package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Package SW1 (promoted 2026-11-11): both carrier-switch fixes are on for a person's launch; only DEBUG can turn them off. */
class CutoverSwitchesTest {
    @Test
    fun aLaunchWithoutExtrasGetsBothFixesAndOnlyADebugLaunchCanTurnThemOff() {
        try {
            CutoverSwitches.applyExtras { _, default -> default }
            assertTrue(CutoverSwitches.keepFifoSlot)
            assertTrue(CutoverSwitches.livenessBeforeRecordLock)
            CutoverSwitches.applyExtras { _, _ -> false }
            assertEquals("the control arm exists only in DEBUG", !BuildConfig.DEBUG, CutoverSwitches.keepFifoSlot)
            assertEquals(!BuildConfig.DEBUG, CutoverSwitches.livenessBeforeRecordLock)
        } finally {
            CutoverSwitches.applyExtras { _, default -> default }
        }
    }
}
