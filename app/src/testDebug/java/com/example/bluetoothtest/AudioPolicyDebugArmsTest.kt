package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio policy arms a DEBUG launch can set (launch extras, `test_device_policy_off`). A release build ignores both
 * (the setters keep the default), so these run on the debug unit-test variants only; the release side is
 * SwitchDefaultsSurviveALaunchTest and ReleaseLaunchExtrasTest.
 */
class AudioPolicyDebugArmsTest {
    @After
    fun restore() {
        DebugTunables.devicePolicyOff = emptySet()
        AudioSwitches.applyExtras { _, default -> default }
    }

    @Test
    fun launchExtrasTurnTheArmsOnAndOffAgain() {
        val extras = mapOf(
            "test_capture_source_fallback" to true,
            "test_audio_off_main" to false,
            "test_audio_mode_change_wait" to true,
            "test_audio_usb_headset_mode_normal" to true,
            "test_audio_complexity_governor" to true,
            "test_audio_route_reassert" to false,
        )
        AudioSwitches.applyExtras { key, default -> extras[key] ?: default }
        assertTrue(AudioSwitches.captureSourceFallback)
        assertFalse("false is the control arm", AudioSwitches.offMainThread)
        assertTrue(AudioSwitches.modeChangeWait)
        assertTrue(AudioSwitches.usbHeadsetModeNormal)
        assertTrue(AudioSwitches.complexityGovernor)
        assertFalse(AudioSwitches.routeReassert)
        AudioSwitches.applyExtras { _, default -> default }
        assertFalse(AudioSwitches.captureSourceFallback)
        assertTrue(AudioSwitches.routeReassert)
    }

    @Test
    fun theSharedOffSwitchTurnsOneEntryOrAllOff() {
        assertTrue(AudioSwitches.policyActive(CALL_FOCUS_ENTRY_ID))
        DebugTunables.devicePolicyOff = setOf(CALL_FOCUS_ENTRY_ID)
        assertFalse(AudioSwitches.policyActive(CALL_FOCUS_ENTRY_ID))
        assertTrue(AudioSwitches.policyActive(CAPTURE_WATCHDOG_ENTRY_ID))
        DebugTunables.devicePolicyOff = setOf("all")
        assertFalse(AudioSwitches.policyActive(CAPTURE_WATCHDOG_ENTRY_ID))
    }
}
