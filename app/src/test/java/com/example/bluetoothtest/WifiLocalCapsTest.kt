package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class WifiLocalCapsTest {
    private fun env(
        carrier: Boolean = true,
        widerReach: Boolean = false,
        direct: Boolean = false,
        featureDirect: Boolean = false,
        freeNdp: Int = 2,
        supportedDirect: Boolean = false,
    ): FakeEnv {
        val env = FakeEnv()
        env.factsValue = env.factsValue.copy(
            featureDirect = featureDirect,
            switches = WifiSwitches(carrier = carrier, widerReach = widerReach, direct = direct),
            freeNdp = freeNdp,
        )
        if (supportedDirect) env.supported += WifiBearer.DIRECT
        return env
    }

    private fun withArms(probe: Boolean = false, force: String = "", block: () -> Unit) {
        // The probe and the forced carrier are DEBUG arms; a release build cannot set either.
        if (probe || force.isNotEmpty()) assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        val oldProbe = DebugTunables.wifiProbe
        val oldForce = DebugTunables.wifiForceCarrier
        DebugTunables.wifiProbe = probe
        DebugTunables.wifiForceCarrier = force
        try {
            block()
        } finally {
            DebugTunables.wifiProbe = oldProbe
            DebugTunables.wifiForceCarrier = oldForce
        }
    }

    @Test
    fun besideATwoPointFourClientConnectionAwareIsOfferedOnlyUnderTheSwitchWithNoOtherBluetoothMedia() {
        fun aware(switchOn: Boolean, otherBt: Boolean?): Int {
            val env = env()
            env.factsValue = env.factsValue.copy(staFrequencyMhz = 2437, aware24GhzAlone = switchOn)
            val caps = if (otherBt == null) {
                WifiLocalCaps.build(env, keyAvailable = true)
            } else {
                WifiLocalCaps.build(env, keyAvailable = true) { otherBt }
            }
            return caps.bearers and Bwf.Caps.BEARER_AWARE
        }
        // AP10, switch on: a hop whose phone carries no other Bluetooth media link offers Aware.
        assertEquals(Bwf.Caps.BEARER_AWARE, aware(switchOn = true, otherBt = false))
        // Other Bluetooth media, or a caller that cannot tell (a stream or relay hop): the 5 GHz rule.
        assertEquals(0, aware(switchOn = true, otherBt = true))
        assertEquals(0, aware(switchOn = true, otherBt = null))
        // Switch off (the default): the 5 GHz rule whatever the hop says.
        assertEquals(0, aware(switchOn = false, otherBt = false))
    }

    @Test
    fun onFiveGhzOrWithoutAClientConnectionTheOfferDoesNotDependOnOtherBluetoothMedia() {
        for (sta in listOf<Int?>(5180, null)) {
            for (otherBt in listOf(true, false)) {
                val env = env()
                env.factsValue = env.factsValue.copy(staFrequencyMhz = sta, aware24GhzAlone = true)
                val caps = WifiLocalCaps.build(env, keyAvailable = true) { otherBt }
                assertEquals("sta=$sta otherBt=$otherBt", Bwf.Caps.BEARER_AWARE, caps.bearers and Bwf.Caps.BEARER_AWARE)
            }
        }
    }

    @Test
    fun aSwitchOffPhoneBuildsAllClearCaps() {
        val caps = WifiLocalCaps.build(env(carrier = false), keyAvailable = true)
        assertEquals(0, caps.bearers)
        assertEquals(0, caps.freeHost)
        assertEquals(0, caps.freeGuest)
    }

    @Test
    fun probeAndForceBluetoothClearEverything() {
        withArms(probe = true) {
            assertEquals(0, WifiLocalCaps.build(env(), keyAvailable = true).bearers)
        }
        withArms(force = "bluetooth") {
            assertEquals(0, WifiLocalCaps.build(env(), keyAvailable = true).bearers)
        }
    }

    @Test
    fun forceDirectKeepsOnlyDirectBitsWhenADriverExists() {
        withArms(force = "direct") {
            val caps = WifiLocalCaps.build(
                env(carrier = true, direct = true, featureDirect = true, supportedDirect = true),
                keyAvailable = true,
            )
            assertEquals(0, caps.bearers and Bwf.Caps.BEARER_AWARE)
            assertEquals(Bwf.Caps.BEARER_DIRECT_GO, caps.bearers and Bwf.Caps.BEARER_DIRECT_GO)
            assertEquals(Bwf.Caps.BEARER_DIRECT_CLIENT, caps.bearers and Bwf.Caps.BEARER_DIRECT_CLIENT)
        }
    }

    @Test
    fun forceAwareClearsTheDirectBits() {
        withArms(force = "aware") {
            val caps = WifiLocalCaps.build(
                env(carrier = true, direct = true, featureDirect = true, supportedDirect = true),
                keyAvailable = true,
            )
            assertEquals(Bwf.Caps.BEARER_AWARE, caps.bearers and Bwf.Caps.BEARER_AWARE)
            assertEquals(0, caps.bearers and Bwf.Caps.BEARER_DIRECT_GO)
            assertEquals(0, caps.bearers and Bwf.Caps.BEARER_DIRECT_CLIENT)
        }
    }

    @Test
    fun directBitsAreMaskedWithoutADriver() {
        val caps = WifiLocalCaps.build(
            env(carrier = true, direct = true, featureDirect = true, supportedDirect = false),
            keyAvailable = true,
        )
        assertEquals(0, caps.bearers and Bwf.Caps.BEARER_DIRECT_GO)
        assertEquals(0, caps.bearers and Bwf.Caps.BEARER_DIRECT_CLIENT)
        assertEquals(Bwf.Caps.BEARER_AWARE, caps.bearers and Bwf.Caps.BEARER_AWARE)
    }

    @Test
    fun noKeyClearsEverything() {
        val caps = WifiLocalCaps.build(env(), keyAvailable = false)
        assertEquals(0, caps.bearers)
    }

    @Test
    fun countsAreClampedAndZeroWithoutBitZero() {
        val aware = WifiLocalCaps.build(env(freeNdp = 20), keyAvailable = true)
        assertEquals(7, aware.freeHost)
        assertEquals(7, aware.freeGuest)
        val switchedOff = WifiLocalCaps.build(env(carrier = false, freeNdp = 20), keyAvailable = true)
        assertEquals(0, switchedOff.freeHost)
        assertEquals(0, switchedOff.freeGuest)
    }

    @Test
    fun theBuilderLogsTheLocalDecisionOnce() {
        val e = env()
        WifiLocalCaps.build(e, keyAvailable = true)
        val lines = e.lines.filter { it.startsWith("wifi_caps: dir=local") }
        assertEquals(1, lines.size)
        assertEquals(true, lines.single().contains("aware=true"))
    }
}
