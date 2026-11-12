package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDeviceNameTest {
    private class Port : P2pPort {
        var openResult = true
        var openCalls = 0
        var closeCalls = 0
        var name: String? = "Phone"
        var answer = true
        var pending: ((String?) -> Unit)? = null

        override fun open(onChannelLost: () -> Unit): Boolean {
            openCalls++
            return openResult
        }

        override fun createGroup(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) = Unit

        override fun connect(ssid: String, psk: String, frequencyMhz: Int, done: (P2pResult) -> Unit) = Unit

        override fun requestGroup(done: (P2pGroup?) -> Unit) = done(null)

        override fun requestConnection(done: (P2pConnection?) -> Unit) = done(null)

        override fun removeGroup(done: (P2pResult) -> Unit) = done(P2pResult.Success)

        override fun requestDeviceName(done: (String?) -> Unit) {
            if (answer) done(name) else pending = done
        }

        override fun closeChannel() {
            closeCalls++
        }

        override fun localAddresses(): List<DirectAddressRules.LocalAddress> = emptyList()
    }

    private class Rig(canRead: Boolean = true) {
        val scheduler = ManualScheduler()
        val port = Port()
        val answers = ArrayList<String?>()
        val name = DirectDeviceName(
            canRead = { canRead },
            openPort = { port },
            scheduler = DirectSchedulerAdapter(scheduler),
            clock = { scheduler.nowMs },
        )
    }

    @Test
    fun anAnswerIsCachedAndASecondCallWithinTenMinutesOpensNoChannel() {
        val rig = Rig()
        rig.name.refresh { rig.answers += it }
        rig.scheduler.drain()
        assertEquals("Phone", rig.name.cached)
        assertEquals(listOf("Phone"), rig.answers)
        assertEquals(1, rig.port.openCalls)

        rig.scheduler.advance(5 * 60 * 1_000L)
        rig.name.refresh { rig.answers += it }
        rig.scheduler.drain()
        assertEquals(listOf("Phone", "Phone"), rig.answers)
        assertEquals(1, rig.port.openCalls)
        assertEquals(1, rig.port.closeCalls)
    }

    @Test
    fun aClosedGateAnswersNullWithoutOpeningAChannel() {
        val rig = Rig(canRead = false)
        rig.name.refresh { rig.answers += it }
        rig.scheduler.drain()
        assertEquals(listOf(null), rig.answers)
        assertEquals(0, rig.port.openCalls)
        assertEquals(0, rig.port.closeCalls)
    }

    @Test
    fun noAnswerTimesOutAndClosesTheChannelOnce() {
        val rig = Rig()
        rig.port.answer = false
        rig.name.refresh { rig.answers += it }
        rig.scheduler.drain()
        assertTrue(rig.answers.isEmpty())
        rig.scheduler.advance(DirectLimits.DEVICE_NAME_TIMEOUT_MS)
        assertEquals(listOf(null), rig.answers)
        assertEquals(1, rig.port.closeCalls)
        rig.port.pending?.invoke("Late")
        assertEquals(listOf(null), rig.answers)
    }

    @Test
    fun aBlankNameReadsAsNull() {
        val rig = Rig()
        rig.port.name = "  "
        rig.name.refresh { rig.answers += it }
        rig.scheduler.drain()
        assertEquals(listOf(null), rig.answers)
        assertNull(rig.name.cached)
        assertEquals(1, rig.port.closeCalls)
    }
}
