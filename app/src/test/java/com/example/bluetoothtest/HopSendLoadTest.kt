package com.example.bluetoothtest

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A7 review P1-2 and P3-3: a hop's send-side Bluetooth load accounting follows the carrier the hop sends on. Wi-Fi bytes
 * never count as Bluetooth load, every handle is closed once, and a carrier change after the writer stopped reopens nothing.
 */
class HopSendLoadTest {
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val transport = BluetoothTransportOption.entries.first()
    private val streamId = UUID.fromString("7c1e0f52-0d3a-4b7e-9a51-6e2f4d3c2b10")
    private val sourceId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 2).toByte() })

    private fun load() = HopSendLoad { NodeLinkLoad.open(transport, LinkDirection.SEND) }

    private fun counted(bytes: Int, load: HopSendLoad): Long {
        val before = NodeLinkLoad.windowBytes(transport)
        load.add(bytes)
        return NodeLinkLoad.windowBytes(transport) - before
    }

    @Test
    fun bluetoothThenWifiThenBluetoothThenCloseLeavesTheLinkCountWhereItStarted() {
        val base = NodeLinkLoad.sendLinks(transport)
        val load = load()
        load.onSendCarrier(CarrierKind.BLUETOOTH)
        assertEquals("nothing is counted before the writer starts", base, NodeLinkLoad.sendLinks(transport))
        load.start()
        assertEquals(base + 1, NodeLinkLoad.sendLinks(transport))
        assertEquals(1_200L, counted(1_200, load))

        load.onSendCarrier(CarrierKind.WIFI_AWARE)
        assertEquals("media on Wi-Fi is not a Bluetooth link", base, NodeLinkLoad.sendLinks(transport))
        assertEquals("Wi-Fi bytes are not Bluetooth load", 0L, counted(50_000, load))
        load.saturated()

        load.onSendCarrier(CarrierKind.BLUETOOTH)
        assertEquals("the fallback counts one link again", base + 1, NodeLinkLoad.sendLinks(transport))
        load.onSendCarrier(CarrierKind.BLUETOOTH)
        assertEquals("a repeated report opens no second link", base + 1, NodeLinkLoad.sendLinks(transport))
        assertEquals(900L, counted(900, load))

        load.close()
        assertEquals(base, NodeLinkLoad.sendLinks(transport))
        load.close()
        assertEquals("closed once", base, NodeLinkLoad.sendLinks(transport))
    }

    @Test
    fun aCarrierChangeAfterTheWriterStoppedReopensNothing() {
        val base = NodeLinkLoad.sendLinks(transport)
        val load = load()
        load.start()
        load.onSendCarrier(CarrierKind.WIFI_AWARE)
        load.close()                                   // the writer's exit, while media was on Wi-Fi
        load.onSendCarrier(CarrierKind.BLUETOOTH)      // the hop's fallback reported late
        assertEquals(base, NodeLinkLoad.sendLinks(transport))
        assertEquals(0L, counted(700, load))
    }

    @Test
    fun aWriterThatStartsWhileMediaIsOnWifiCountsOnlyAfterTheFallback() {
        val base = NodeLinkLoad.sendLinks(transport)
        val load = load()
        load.onSendCarrier(CarrierKind.WIFI_DIRECT)
        load.start()
        assertEquals(base, NodeLinkLoad.sendLinks(transport))
        load.onSendCarrier(CarrierKind.BLUETOOTH)
        assertEquals(base + 1, NodeLinkLoad.sendLinks(transport))
        load.close()
        assertEquals(base, NodeLinkLoad.sendLinks(transport))
    }

    @Test
    fun aHopsRealSwitchToWifiAndItsFallbackMoveTheBluetoothLoadBothWays() {
        val base = NodeLinkLoad.sendLinks(transport)
        val sendLoad = load()
        val events = RecordingHopOwner()
        val owner = object : HopOwner by events {
            override fun sendCarrierChanged(kind: CarrierKind) {
                events.sendCarrierChanged(kind)
                sendLoad.onSendCarrier(kind)
            }
        }
        val rig = HopWifiRig(parentOwner = owner).activate()
        val writing = AtomicBoolean(true)
        var writer: Thread? = null
        try {
            sendLoad.start()
            assertEquals(base + 1, NodeLinkLoad.sendLinks(transport))
            writer = thread(name = "hop-send-load-writer") {
                var sequence = 0L
                try {
                    while (writing.get()) {
                        val wire = rig.parent.writer.write(
                            SecureRecordType.MESH_ENVELOPE,
                            MeshEnvelopeCodec.encode(MeshEnvelope(streamId, sourceId, sequence++, 8, 0, ByteArray(64))),
                        )
                        rig.parent.writer.flush()
                        sendLoad.add(wire)
                        Thread.sleep(2)
                    }
                } catch (_: Exception) {
                }
            }
            val wifi = rig.addWifi()
            assertTrue(HopWifiRig.awaitCondition { "carrier:WIFI_AWARE" in events.events })
            assertEquals("on Wi-Fi the hop holds no Bluetooth send link", base, NodeLinkLoad.sendLinks(transport))
            val wifiBytes = NodeLinkLoad.windowBytes(transport)
            Thread.sleep(100)
            assertEquals("records sent on Wi-Fi add no Bluetooth bytes", wifiBytes, NodeLinkLoad.windowBytes(transport))

            wifi.cut()                                   // the Wi-Fi carrier dies: planned fallback to Bluetooth
            assertTrue(HopWifiRig.awaitCondition { rig.parent.sendKind == CarrierKind.BLUETOOTH })
            assertTrue(HopWifiRig.awaitCondition { "carrier:BLUETOOTH" in events.events })
            assertEquals("after the fallback exactly one Bluetooth link", base + 1, NodeLinkLoad.sendLinks(transport))
            assertFalse("the hop did not end", events.events.any { it.startsWith("ended:") })
        } finally {
            writing.set(false)
            writer?.join(5_000)
            sendLoad.close()
            rig.close()
        }
        assertEquals("the link count is back where it started", base, NodeLinkLoad.sendLinks(transport))
    }
}
