package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every launch assigns each switch a value, and a release launch (which ignores launch extras) assigns each switch its
 * own default. So assigning a switch the value it already holds must never change it.
 *
 * In a DEBUG build every setter satisfies this. In a release build it fails for a default-on switch whose setter still
 * gates on DEBUG (`value && BuildConfig.DEBUG`): that silently turned three promoted behaviours off for users (audit
 * 2026-11-10, BT-01: the first-frame ramp, audio work off the main thread, the first-write watch counting a control
 * write), and it fails the other way for a default-off switch that a release build would force on. The debug unit
 * suite cannot see either, so this class also runs on the release variant, alone and in a fresh JVM, where every
 * switch still holds its default: `:app:testGithubReleaseUnitTest --tests '*SwitchDefaultsSurviveALaunchTest'`.
 */
class SwitchDefaultsSurviveALaunchTest {
    private val holders: List<Any> = listOf(
        DebugTunables, AudioSwitches, AdvertisingSwitches, CameraSwitches, DecoderSwitches, GattSwitches, PhySwitches,
        ScanSwitches, ServiceSwitches, ThermalSwitches,
        // Other holders whose setters gate on BuildConfig.DEBUG.
        AudioLiveTunables, CodecPowerAware, CrashReports, DialPersonMatch, MemoryThermalPolicy, QlConsent, ScanRestartHint,
        CopiedIdPcPair, CopiedIdOverlapRule, UpdateRuntime, VideoCodecSwitches,
    )

    @Test
    fun assigningASwitchTheValueItHoldsNeverChangesIt() {
        val changed = ArrayList<String>()
        var seen = false
        holders.forEach { holder ->
            val methods = holder.javaClass.methods
            methods.filter { it.name.startsWith("set") && it.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE)) }
                .forEach { setter ->
                    val suffix = setter.name.removePrefix("set")
                    val getter = methods.firstOrNull {
                        (it.name == "get$suffix" || it.name == "is$suffix") && it.parameterCount == 0 &&
                            it.returnType == java.lang.Boolean.TYPE
                    } ?: return@forEach
                    val before = getter.invoke(holder) as Boolean
                    setter.invoke(holder, before)
                    val after = getter.invoke(holder) as Boolean
                    seen = true
                    if (after != before) changed += "${holder.javaClass.simpleName}.$suffix: $before became $after"
                }
        }
        assertTrue("no switch was found: the holders changed shape", seen)
        assertEquals("a launch that assigns these switches their own value changes them", emptyList<String>(), changed)
    }
}
