package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 step 2: one carrier abstraction and the per-carrier policy values. Every Bluetooth result is exactly today's; a
 * Wi-Fi carrier has its own flush, pacing, seed, close-drain and liveness numbers (master 4.3, 4.4, 5.5).
 */
class CarrierPolicyTest {
    private val legacyQti = BluetoothStackFingerprint("qcom", "atoll", 30)
    private val modernQualcomm = BluetoothStackFingerprint("qcom", "taro", 36)
    private val samsung = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

    @Test
    fun aWifiCarrierFlushesAfterEveryRecordWhileBluetoothKeepsItsInterval() {
        // The values of the existing call-media test, with the carrier named explicitly.
        assertEquals(20L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.BLE_L2CAP, legacyQti, null, CarrierKind.BLUETOOTH))
        assertEquals(20L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.BLE_L2CAP, modernQualcomm, 160, CarrierKind.BLUETOOTH))
        assertEquals(0L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.BLE_L2CAP, modernQualcomm, 1_200, CarrierKind.BLUETOOTH))
        assertEquals(20L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.CLASSIC_RFCOMM, modernQualcomm, 250, CarrierKind.BLUETOOTH))
        assertEquals(0L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.CLASSIC_RFCOMM, samsung, 1_500, CarrierKind.BLUETOOTH))
        for (transport in BluetoothTransportOption.entries) {
            for (peerMaxKbps in listOf(null, 160, 1_200)) {
                // Naming the Bluetooth kind changes nothing: every existing caller keeps its result.
                assertEquals(
                    privateCallMediaFlushIntervalMs(transport, legacyQti, peerMaxKbps),
                    privateCallMediaFlushIntervalMs(transport, legacyQti, peerMaxKbps, CarrierKind.BLUETOOTH),
                )
                assertEquals(0L, privateCallMediaFlushIntervalMs(transport, legacyQti, peerMaxKbps, CarrierKind.WIFI_AWARE))
                assertEquals(0L, privateCallMediaFlushIntervalMs(transport, legacyQti, peerMaxKbps, CarrierKind.WIFI_DIRECT))
            }
        }
    }

    @Test
    fun aWifiCarrierIsNotPaced() {
        assertTrue("a CoC call with pacing on is paced today", callLinkPaced(BluetoothTransportOption.CLASSIC_RFCOMM, true, true))
        assertFalse(callLinkPaced(BluetoothTransportOption.CLASSIC_RFCOMM, true, true, CarrierKind.WIFI_AWARE))
        assertFalse(callLinkPaced(BluetoothTransportOption.CLASSIC_RFCOMM, true, true, CarrierKind.WIFI_DIRECT))
        // Bluetooth results are today's, including GATT's never-paced rule.
        for (transport in BluetoothTransportOption.entries) {
            assertEquals(callLinkPaced(transport, true, true), callLinkPaced(transport, true, true, CarrierKind.BLUETOOTH))
        }
        assertFalse(callLinkPaced(BluetoothTransportOption.BLE_GATT, true, true, CarrierKind.BLUETOOTH))
    }

    @Test
    fun noPacerSeedOnAWifiCarrier() {
        assertNull(LinkPacerSeed.seed(LinkPacer(), BluetoothTransportOption.BLE_GATT, null, CarrierKind.WIFI_AWARE))
        assertNull(LinkPacerSeed.seed(LinkPacer(), BluetoothTransportOption.BLE_GATT, "peer", CarrierKind.WIFI_DIRECT))
    }

    @Test
    fun audioHeadroomIsTheSameOnEveryCarrier() {
        assertEquals(privateCallAudioHeadroomPackets(), privateCallAudioHeadroomPackets(CarrierKind.WIFI_AWARE))
        assertEquals(privateCallAudioHeadroomPackets(), privateCallAudioHeadroomPackets(CarrierKind.WIFI_DIRECT))
    }

    @Test
    fun aWifiCarrierUsesTheFixedCloseDrain() {
        assertEquals(
            PrivateCallCloseWait(waitForPeer = false, maxMs = PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS),
            privateCallCloseWait(BluetoothTransportOption.CLASSIC_RFCOMM, true, CarrierKind.WIFI_DIRECT),
        )
        // Bluetooth keeps today's two branches.
        assertEquals(
            PrivateCallCloseWait(waitForPeer = false, maxMs = 0L),
            privateCallCloseWait(BluetoothTransportOption.BLE_GATT, true, CarrierKind.BLUETOOTH),
        )
        assertEquals(
            PrivateCallCloseWait(waitForPeer = true, maxMs = PRIVATE_CALL_PEER_CLOSE_WAIT_MS),
            privateCallCloseWait(BluetoothTransportOption.CLASSIC_RFCOMM, true, CarrierKind.BLUETOOTH),
        )
        assertEquals(
            PrivateCallCloseWait(waitForPeer = false, maxMs = PRIVATE_CALL_FIXED_CLOSE_DRAIN_MS),
            privateCallCloseWait(BluetoothTransportOption.CLASSIC_RFCOMM, false, CarrierKind.BLUETOOTH),
        )
    }

    @Test
    fun aWifiBearerMapsToItsCarrierKind() {
        assertEquals(CarrierKind.WIFI_AWARE, WifiBearer.AWARE.carrierKind())
        assertEquals(CarrierKind.WIFI_DIRECT, WifiBearer.DIRECT.carrierKind())
        assertFalse(CarrierKind.BLUETOOTH.isWifi)
        assertTrue(CarrierKind.WIFI_AWARE.isWifi)
        assertTrue(CarrierKind.WIFI_DIRECT.isWifi)
    }

    @Test
    fun aCallStartIsNotALinkChangeButACarrierSwitchIs() {
        // Review A3 P2-1: the rate controllers restart their climb when the epoch they saw changes.
        val seenByARunningStream = LinkChangeEvents.epoch
        LinkChangeEvents.sendCarrierChanged("attach")
        assertEquals("a call starting on its first carrier changes no running link", seenByARunningStream, LinkChangeEvents.epoch)
        LinkChangeEvents.sendCarrierChanged("switch")
        assertNotEquals("the media moved to another carrier", seenByARunningStream, LinkChangeEvents.epoch)
        val afterSwitch = LinkChangeEvents.epoch
        LinkChangeEvents.sendCarrierChanged("none")
        assertNotEquals("the media lost its carrier", afterSwitch, LinkChangeEvents.epoch)
    }

    @Test
    fun aSessionCarrierClosesItsResourcesOnceAndOnlyClosesTheLinkWhenItOwnsIt() {
        val borrowedLink = testLink()
        val closes = AtomicInteger()
        val borrowed = SessionCarrier(
            kind = CarrierKind.BLUETOOTH,
            label = "BLE GATT",
            link = borrowedLink,
            liveness = CarrierLivenessPolicy.BLUETOOTH,
            closesLink = false,
        ) { closes.incrementAndGet() }
        borrowed.close()
        borrowed.close()
        assertEquals(1, closes.get())
        // The call still owns its Bluetooth link and can write on it after the set closed carrier 0.
        assertTrue(borrowedLink.writer.write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1)) > 0)

        val ownedCloses = AtomicInteger()
        val owned = SessionCarrier(
            kind = CarrierKind.WIFI_AWARE,
            label = "Wi-Fi Aware",
            link = testLink(),
            liveness = CarrierLivenessPolicy.WIFI,
            closesLink = true,
        ) { ownedCloses.incrementAndGet() }
        owned.close()
        owned.close()
        assertEquals(1, ownedCloses.get())
    }

    private fun testLink(): SecureBluetoothSession {
        val key = ByteArray(32) { (it + 1).toByte() }
        return SecureBluetoothSession(
            peerNodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 3).toByte() }),
            peerCapabilities = NodeCapabilities(
                transportMask = NodeCapabilities.TRANSPORT_BLE_GATT,
                encodeCodecMask = NodeCapabilities.CODEC_VP9,
                decodeCodecMask = NodeCapabilities.CODEC_VP9,
                maxBitrateKbps = 1_100,
                maxChildren = 0,
            ),
            reader = SecureRecordReader(ByteArrayInputStream(ByteArray(0)), key),
            writer = SecureRecordWriter(ByteArrayOutputStream(), key),
        )
    }

    @Test
    fun aWifiReportNeverReachesTheBluetoothProbe() {
        // The tested table: a Wi-Fi carrier's arrival report goes to that carrier's own capacity key.
        assertEquals(ProbeTarget.CARRIER, probeTarget(CarrierKind.WIFI_AWARE, pacedWrites = true))
        assertEquals(ProbeTarget.CARRIER, probeTarget(CarrierKind.WIFI_DIRECT, pacedWrites = false))
        assertEquals(ProbeTarget.BLUETOOTH, probeTarget(CarrierKind.BLUETOOTH, pacedWrites = true))
        assertEquals(ProbeTarget.NONE, probeTarget(CarrierKind.BLUETOOTH, pacedWrites = false))
        // A Bluetooth report for a call that is not paced teaches nothing, exactly as before.
        assertEquals(ProbeTarget.NONE, probeTarget(CarrierKind.BLUETOOTH, pacedWrites = false))
    }
}
