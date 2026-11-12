package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Realme RMX5256 lane (2026-11-06): a MediaTek Android 16 stack that stopped opening central connections (status 128
 * about 50 ms after the dial, no client held) until Bluetooth restarted. On by default (owner, 2026-11-10), the matched
 * family gets the reliability card's Bluetooth off/on guidance at once; nothing else changes anywhere.
 */
class GattNativeClientWedgeTest {
    private var now = 1_000L
    private var held = 0
    private lateinit var card: PoolExhaustionPolicy
    private val stuck = "BLE control connection failed (status 128)"
    private val threshold get() = GattNativeClientWedgeGuard.THRESHOLD

    /** The Realme's own device_policy traits (run p2b-20261106). */
    private val realme = DeviceTraits(
        hardware = "mt6835", board = "k6835v2_64", sdkInt = 36, manufacturer = "realme", brand = "realme",
        socManufacturer = "Mediatek", socModel = "MT6835", securityPatch = "2026-08-01",
    )

    @Before
    fun setUp() {
        now = 1_000L
        GattSwitches.resetToDefaults()
        card = PoolExhaustionPolicy { now }
        held = 0
        GattPoolBackoff.resetForTest(PoolBackoff({ now }), card)
        GattNativeClientWedge.resetForTest()
        GattNativeClientWedge.detector = NativeClientWedgeDetector { now }
        GattNativeClientWedge.heldClients = { held }
        onPhone(realme)
    }

    @After
    fun tearDown() {
        GattSwitches.resetToDefaults()
        GattPoolBackoff.resetForTest()
        GattNativeClientWedge.resetForTest()
    }

    private fun onPhone(traits: DeviceTraits, off: Set<String> = emptySet()) {
        GattNativeClientWedge.policySource = { DeviceFamilyPolicy.resolve(traits, off) }
    }

    /** One failed central dial, exactly as the BLE control loop reports it: [elapsedMs] after the dial, [gapMs] after the last. */
    private fun failedDial(message: String = stuck, elapsedMs: Long = 40L, gapMs: Long = 100L) {
        now += gapMs
        GattPoolBackoff.noteConnectFailure(message, "ble-control-client")
        GattNativeClientWedge.onCentralConnectFailure(message, elapsedMs, "ble-control-client")
    }

    @Test
    fun immediateNoResourcesFailuresWithNoClientHeldShowTheRestartGuidanceAtOnce() {
        GattSwitches.nativeClientWedgeGuard = true
        repeat(threshold - 1) { failedDial() }
        assertFalse("not before the threshold", card.restartGuidanceDue())
        failedDial()
        assertTrue(card.restartGuidanceDue())
    }

    @Test
    fun theSameFailuresChangeNothingWithTheArmOffOnAnotherFamilyOrWithTheEntrySwitchedOff() {
        val samsung = DeviceTraits(
            hardware = "s5e9945", board = "s5e9945", sdkInt = 36, manufacturer = "samsung", brand = "samsung",
            socManufacturer = "Samsung", socModel = "s5e9945",
        )
        val cases = listOf(
            Triple(false, realme, emptySet<String>()),
            Triple(true, samsung, emptySet<String>()),
            Triple(true, realme.copy(sdkInt = 35), emptySet<String>()),
            Triple(true, realme, setOf("mediatek-coloros-native-gatt-client-wedge")),
        )
        for ((arm, traits, off) in cases) {
            if (!arm) assumeTrue("the control arm exists in DEBUG builds only", BuildConfig.DEBUG)
            setUp()
            GattSwitches.nativeClientWedgeGuard = arm
            onPhone(traits, off)
            repeat(threshold + 1) { failedDial() }
            assertFalse("arm=$arm ${traits.manufacturer} sdk=${traits.sdkInt} off=$off", card.restartGuidanceDue())
        }
    }

    @Test
    fun slowFailuresOtherStatusesAndAHeldClientEndTheRun() {
        GattSwitches.nativeClientWedgeGuard = true
        repeat(threshold - 1) { failedDial() }
        failedDial(elapsedMs = 4_000L) // the stack tried to connect: not this wedge
        repeat(threshold - 1) { failedDial() }
        failedDial(message = "BLE control connection failed (status 133)")
        held = 1 // this app holds a GATT client: real pool use, not a native wedge
        repeat(threshold + 1) { failedDial() }
        assertFalse(card.restartGuidanceDue())
        held = 0
        repeat(threshold) { failedDial() }
        assertTrue(card.restartGuidanceDue())
    }

    @Test
    fun failuresFurtherApartThanTheWindowDoNotAddUp() {
        GattSwitches.nativeClientWedgeGuard = true
        repeat(threshold + 1) {
            now += GattNativeClientWedgeGuard.WINDOW_MS + 1
            GattNativeClientWedge.onCentralConnectFailure(stuck, 40L, "ble-control-client")
        }
        assertFalse(card.restartGuidanceDue())
    }

    @Test
    fun aSuccessfulCentralConnectClearsTheGuidanceAndCountingStartsAgain() {
        GattSwitches.nativeClientWedgeGuard = true
        repeat(threshold) { failedDial() }
        assertTrue(card.restartGuidanceDue())
        now += 5_000L
        GattPoolBackoff.recordSuccess(PoolSource.CLIENT)
        assertFalse(card.restartGuidanceDue())
        repeat(threshold - 1) { failedDial() }
        assertFalse(card.restartGuidanceDue())
        failedDial()
        assertTrue(card.restartGuidanceDue())
    }

    @Test
    fun theGuidanceIsOnByDefaultForTheMatchedFamilyAndTheDebugControlArmTurnsItOff() {
        // A user's launch (defaults, and every release build): the matched Realme family gets the card with no switch set.
        assertTrue(GattSwitches.nativeClientWedgeGuard)
        assertNotNull(GattNativeClientWedge.guard())
        repeat(threshold) { failedDial() }
        assertTrue(card.restartGuidanceDue())
        onPhone(realme.copy(manufacturer = "OPPO", brand = "OPPO")) // ColorOS on the same SoC family
        assertNotNull(GattNativeClientWedge.guard())
        onPhone(realme.copy(socManufacturer = "QTI", hardware = "qcom")) // Realme UI on Qualcomm: not this family
        assertNull(GattNativeClientWedge.guard())
        onPhone(realme.copy(sdkInt = 35))
        assertNull(GattNativeClientWedge.guard())
    }

    @Test
    fun onlyDebugBuildsHonourTheControlArmExtra() {
        GattSwitches.applyExtras(
            boolExtra = { key, default -> if (key == "test_gatt_native_wedge_guard") false else default },
            intExtra = { _, default -> default },
            stringExtra = { null },
        )
        if (!BuildConfig.DEBUG) {
            // A release build ignores the launch extra: the guard stays on.
            assertTrue(GattSwitches.nativeClientWedgeGuard)
            assertNotNull(GattNativeClientWedge.guard())
            return
        }
        assertFalse(GattSwitches.nativeClientWedgeGuard)
        assertNull(GattNativeClientWedge.guard())
        GattSwitches.resetToDefaults()
        assertTrue(GattSwitches.nativeClientWedgeGuard)
    }
}
