package com.example.bluetoothtest

import android.net.wifi.WifiManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every switch off is inert (X4, [Am 2]): reading the facts, deciding, reading the residue and a shutdown start no
 * `meshlive-wifi` thread and make no platform call; the application start leaves the Wi-Fi band reader alone.
 * The reviewer runs this test.
 */
@RunWith(AndroidJUnit4::class)
class WifiRadioSwitchOffTest {
    @Test
    fun everySwitchOffStartsNoWifiThread() {
        Assume.assumeTrue(Thread.getAllStackTraces().keys.none { it.name == WifiRadio.THREAD_NAME })
        WifiRadio.facts()
        WifiRadio.decide()
        WifiRadio.residue()
        // A4 review P3-3: the radio's own readers are inert too, whoever calls them.
        assertTrue("no free data path is reported with the carrier switch off", WifiRadio.freeNdp() == 0)
        assertNull("no Aware characteristics are read with the carrier switch off", WifiRadio.characteristics())
        WifiRadio.shutdownAll("test")
        assertTrue(
            "no Wi-Fi thread may start while every switch is off",
            Thread.getAllStackTraces().keys.none { it.name == WifiRadio.THREAD_NAME },
        )
    }

    @Test
    fun theApplicationStartLeavesTheWifiBandReaderAlone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wifi = context.getSystemService(WifiManager::class.java)
        Assume.assumeTrue(wifi != null && wifi.connectionInfo.frequency > 0)
        assertNull(WifiCoexistence.connectedFrequencyMhz())
    }
}
