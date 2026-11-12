package com.example.bluetoothtest

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default-off LE experiments (2026-10-09): aligned GATT value sizes, LE Coded PHY,
 * and the learned-capacity separation that keeps experimental PHY measurements out of the 2M store.
 */
class LeTransportExperimentsTest {
    @Test
    fun defaultGattValueSizeIsUnchanged() {
        // 512-byte values (the ATT maximum) at MTU 517, 20 at the default MTU.
        assertEquals(512, gattValueBytes(517, alignToLePdus = false))
        assertEquals(507, gattChunkPayloadBytes(517, alignToLePdus = false))
        assertEquals(20, gattValueBytes(23, alignToLePdus = false))
        assertEquals(15, gattChunkPayloadBytes(23, alignToLePdus = false))
    }

    @Test
    fun alignedValuesFillWholeDataLengthPdus() {
        // value + 3 ATT + 4 L2CAP header = 502 = 2 x 251 octets.
        assertEquals(495, gattValueBytes(517, alignToLePdus = true))
        assertEquals(495, gattValueBytes(498, alignToLePdus = true))
        assertEquals(244, gattValueBytes(497, alignToLePdus = true))
        assertEquals(244, gattValueBytes(247, alignToLePdus = true))
        // Below one full PDU nothing can be aligned: the MTU limit stays.
        assertEquals(182, gattValueBytes(185, alignToLePdus = true))
        assertEquals(20, gattValueBytes(23, alignToLePdus = true))
        listOf(247, 300, 498, 517).forEach { mtu ->
            assertEquals(0, (gattValueBytes(mtu, true) + 3 + 4) % 251)
        }
    }

    @Test
    fun alignedWritesAreOffByDefault() {
        assertFalse(DebugTunables.experimentalAlignedGattWrites)
    }

    @Test
    fun codedPhyExperimentRequestsCodedButInitiatesOnLe1m() {
        try {
            DebugTunables.experimentalBlePhy = ExperimentalBlePhy.CODED_S8
            if (!BuildConfig.DEBUG) {
                // A release build ignores the experiment: 2M stays.
                assertEquals(BluetoothDevice.PHY_LE_2M_MASK, BlePhyPreference.TWO_M.mask)
                return
            }
            assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, BlePhyPreference.TWO_M.mask)
            assertEquals(BluetoothDevice.PHY_OPTION_S8, BlePhyPreference.TWO_M.option)
            assertEquals("Coded S8 (experimental)", BlePhyPreference.TWO_M.label)
            // Legacy advertisements are on LE 1M: never initiate on Coded.
            assertEquals(BluetoothDevice.PHY_LE_1M_MASK, controlInitiatingPhys(BlePhyPreference.TWO_M))
            assertFalse(DebugTunables.experimentalBlePhy == ExperimentalBlePhy.LE_1M)
        } finally {
            DebugTunables.experimentalBlePhy = null
        }
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, BlePhyPreference.TWO_M.mask)
        assertEquals(BluetoothDevice.PHY_OPTION_NO_PREFERRED, BlePhyPreference.TWO_M.option)
    }

    @Test
    fun experimentalPhyValuesParseAndSeedConservatively() {
        assertEquals(ExperimentalBlePhy.LE_1M, ExperimentalBlePhy.fromDebugValue("1M"))
        assertEquals(ExperimentalBlePhy.CODED_S2, ExperimentalBlePhy.fromDebugValue("coded-s2"))
        assertEquals(ExperimentalBlePhy.CODED_S8, ExperimentalBlePhy.fromDebugValue(" Coded-S8 "))
        assertNull(ExperimentalBlePhy.fromDebugValue("2m"))
        assertNull(ExperimentalBlePhy.fromDebugValue(null))
        assertEquals(40, LinkPacerSeed.priorKbps(BluetoothTransportOption.BLE_GATT, true, ExperimentalBlePhy.CODED_S8))
        assertEquals(70, LinkPacerSeed.priorKbps(BluetoothTransportOption.BLE_L2CAP, true, ExperimentalBlePhy.CODED_S2))
        assertEquals(400, LinkPacerSeed.priorKbps(BluetoothTransportOption.CLASSIC_RFCOMM, true, ExperimentalBlePhy.CODED_S8))
        assertEquals(350, LinkPacerSeed.priorKbps(BluetoothTransportOption.BLE_GATT, true, null))
    }

    @Test
    fun peerLinkReportSeedsBeforeThePhonesBestProbe() {
        // ASUS -> A15s: the child reported about 65 kbps; the ASUS's own best probe was near 1 Mbps.
        assertEquals(65, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_GATT, 980, true, peerLinkKbps = 65))
        assertEquals(980, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_GATT, 980, true, peerLinkKbps = null))
        assertEquals(350, LinkPacerSeed.kbps(BluetoothTransportOption.BLE_GATT, null, true, peerLinkKbps = 0))
        assertFalse(DebugTunables.experimentalPeerLinkSeed)
    }

    @Test
    fun peerLinkMemoryRewritesOnlyOnChangeOrAge() {
        assertTrue(LinkCapacity.peerLinkNeedsRewrite(0, 65, 0))
        assertFalse(LinkCapacity.peerLinkNeedsRewrite(65, 68, 5_000))
        assertTrue(LinkCapacity.peerLinkNeedsRewrite(65, 72, 5_000))
        assertTrue(LinkCapacity.peerLinkNeedsRewrite(65, 66, 61_000))
        assertTrue(LinkCapacity.peerLinkNeedsRewrite(65, 66, -1))
    }

    @Test
    fun experimentalPhyCapacityIsLearnedApart() {
        assertEquals("", phyCapacityKey(BluetoothTransportOption.BLE_GATT, null))
        assertEquals(".phy-coded-s2", phyCapacityKey(BluetoothTransportOption.BLE_GATT, ExperimentalBlePhy.CODED_S2))
        assertEquals(".phy-1m", phyCapacityKey(BluetoothTransportOption.BLE_L2CAP, ExperimentalBlePhy.LE_1M))
        assertEquals("", phyCapacityKey(BluetoothTransportOption.CLASSIC_RFCOMM, ExperimentalBlePhy.CODED_S8))
        assertTrue(ExperimentalBlePhy.CODED_S2.coded && !ExperimentalBlePhy.LE_1M.coded)
    }
}
