package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edgecases-20261020 m31-a15s-redmi-1: the Meet-code boost and its end each replaced the A15s's call-listener set (a new
 * address), and the second balanced step on a set found its one-time step used and replaced it again.
 */
class BoostInPlaceTest {
    private fun completeStep(steps: BalancedSwitchSteps): BalancedSwitchSteps.Action {
        assertEquals(BalancedSwitchSteps.Action.SET_PARAMETERS, steps.onEnabled(enable = false, success = true))
        assertEquals(BalancedSwitchSteps.Action.ENABLE, steps.onParametersUpdated(success = true))
        return steps.onEnabled(enable = true, success = true)
    }

    @Test
    fun boostAndItsEndAreStepsOnTheSameSetAnyNumberOfTimes() {
        val steps = BalancedSwitchSteps()
        assertTrue(steps.begin(lowLatency = false))
        assertEquals(BalancedSwitchSteps.Action.DONE, completeStep(steps))
        assertEquals("balanced_in_place", BalancedSwitchSteps.churnEvent(BalancedSwitchSteps.Action.DONE, steps.target))

        assertTrue("a boost after the balanced step", steps.begin(lowLatency = true))
        assertTrue(steps.target)
        assertEquals(BalancedSwitchSteps.Action.DONE, completeStep(steps))
        assertEquals("boost_in_place", BalancedSwitchSteps.churnEvent(BalancedSwitchSteps.Action.DONE, steps.target))

        assertTrue("the boost's end", steps.begin(lowLatency = false))
        assertFalse(steps.target)
        assertEquals(BalancedSwitchSteps.Action.DONE, completeStep(steps))
    }

    @Test
    fun aStepThatIsRunningOrFailedCannotStartAnother() {
        val steps = BalancedSwitchSteps()
        assertTrue(steps.begin(lowLatency = true))
        assertFalse("still disabling", steps.begin(lowLatency = false))
        assertEquals(BalancedSwitchSteps.Action.RESTART, steps.onTimeout())
        assertFalse("failed: the set is being replaced", steps.begin(lowLatency = false))
        assertTrue("the replacement keeps the failed step's target", steps.target)
    }

    @Test
    fun theControlArmStepRunsOncePerSetAsBefore() {
        val steps = BalancedSwitchSteps()
        assertTrue(steps.begin())
        assertEquals(BalancedSwitchSteps.Action.DONE, completeStep(steps))
        assertFalse(steps.begin())
        assertEquals("balanced_in_place", BalancedSwitchSteps.churnEvent(BalancedSwitchSteps.Action.DONE))
        assertEquals("stack_enable", BalancedSwitchSteps.churnEvent(BalancedSwitchSteps.Action.NONE, target = true))
    }

    @Test
    fun theModeDecisionKeepsTheSetWheneverItCan() {
        assertEquals(ModeSwitch.Decision.NO_SET, ModeSwitch.decide(running = false, currentLowLatency = false, target = true, unreliableInPlace = false))
        assertEquals(ModeSwitch.Decision.ALREADY, ModeSwitch.decide(running = true, currentLowLatency = false, target = false, unreliableInPlace = false))
        assertEquals(ModeSwitch.Decision.ALREADY, ModeSwitch.decide(running = true, currentLowLatency = true, target = true, unreliableInPlace = true))
        assertEquals(ModeSwitch.Decision.STEP, ModeSwitch.decide(running = true, currentLowLatency = false, target = true, unreliableInPlace = false))
        // The ASUS DeadObject case: the set stays low latency with its address, and nothing is replaced.
        assertEquals(ModeSwitch.Decision.KEEP_SET, ModeSwitch.decide(running = true, currentLowLatency = true, target = false, unreliableInPlace = true))
        assertEquals(ModeSwitch.Decision.KEEP_SET, ModeSwitch.decide(running = true, currentLowLatency = false, target = true, unreliableInPlace = true))
    }

    private fun source(name: String): String = listOf(
        File("src/main/java/com/example/bluetoothtest/$name"),
        File("app/src/main/java/com/example/bluetoothtest/$name"),
    ).first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun theCallListenerBoostsInPlaceAndKeepsTheReplacementAsTheControlArm() {
        val transport = source("GattVideoTransport.kt")
        val boost = transport.substringAfter("fun boostAdvertising() {").substringBefore("/** A failed fallback left nothing advertising")
        val inPlace = boost.indexOf("advertisement.switchMode(lowLatency = true)")
        val replacement = boost.indexOf("stopAdvertising()")
        assertTrue(inPlace in 0 until replacement)
        assertTrue(boost.contains("AdvertisingSwitches.inPlaceBoost && DebugTunables.stableAdvertisingAddress"))
        assertTrue(boost.contains("activeDevice == null"))
        assertFalse("one pending balanced step", transport.contains("postDelayed(::switchToBalancedAdvertising"))
    }

    @Test
    fun theSwitchDefaultsOnAndReadsItsLaunchExtra() {
        AdvertisingSwitches.reset()
        assertTrue(AdvertisingSwitches.inPlaceBoost)
        AdvertisingSwitches.applyExtras({ name, default -> if (name == "test_adv_inplace_boost") false else default }, { _, default -> default })
        // =false is the DEBUG control arm: a release build keeps the promoted boost on.
        assertEquals(!BuildConfig.DEBUG, AdvertisingSwitches.inPlaceBoost)
        AdvertisingSwitches.reset()
        assertTrue(AdvertisingSwitches.inPlaceBoost)
    }
}
