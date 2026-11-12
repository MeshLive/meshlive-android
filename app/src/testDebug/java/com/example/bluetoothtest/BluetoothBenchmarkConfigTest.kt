package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BluetoothBenchmarkConfigTest {
    @Test
    fun acceptsConfiguredTuningRanges() {
        val raw = RawBenchmarkConfig(
            payloadBytes = 8 * 1024,
            windowPackets = 16,
            readBufferBytes = 4 * 1024,
            writeBufferBytes = 64 * 1024,
            cocChannels = 4,
            acknowledgeWindows = false,
        )
        val gatt = GattBenchmarkConfig(dataPacketsPerFence = 2048)

        assertEquals(8 * 1024, raw.payloadBytes)
        assertEquals(16, raw.windowPackets)
        assertEquals(4, raw.cocChannels)
        assertEquals(false, raw.acknowledgeWindows)
        assertEquals(2048, gatt.dataPacketsPerFence)
        assertEquals(2048, GattBenchmarkConfig().dataPacketsPerFence)
        assertEquals(true, RawBenchmarkConfig().acknowledgeWindows)
        assertEquals(false, RawBenchmarkConfig().primeGattMtuForCoc)
        assertEquals(true, RawBenchmarkConfig().keepControlGattForCoc)
    }

    @Test
    fun rejectsUnboundedBenchmarkQueues() {
        assertThrows(IllegalArgumentException::class.java) {
            RawBenchmarkConfig(windowPackets = RawBenchmarkConfig.MAX_WINDOW_PACKETS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawBenchmarkConfig(cocChannels = RawBenchmarkConfig.MAX_COC_CHANNELS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GattBenchmarkConfig(
                dataPacketsPerFence = GattBenchmarkConfig.MAX_DATA_PACKETS_PER_FENCE + 1,
            )
        }
    }
}
