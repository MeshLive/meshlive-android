package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A wider-reach (native) initiator connects to the port its responder sent in `MWA1` when the platform reports none for the
 * data path (`WifiAwareNetworkInfo.getPort()` 0, the S22 with an S24 responder); a nonzero platform port is used as given.
 */
class NativeExchangePortTest {
    private val lines = CopyOnWriteArrayList<String>()
    private var previousSink: ((String) -> Unit)? = null

    @Before
    fun captureLogs() {
        previousSink = WifiLog.sink
        WifiLog.sink = { lines += it }
    }

    @After
    fun restoreLogs() {
        previousSink?.let { WifiLog.sink = it }
    }

    private val callbacks = object : NativeExchange.Callbacks {
        override fun onArmed(gen: Long, port: Int) = Unit
        override fun onConnected(gen: Long, socket: Socket, info: AwareDataPathInfo) = Unit
        override fun onFailed(gen: Long, reason: String) = Unit
        override fun onLost(gen: Long) = Unit
        override fun onClosed(gen: Long) = Unit
    }

    /** An initiator whose responder answered with [answerPort], given a 5 GHz data path that reports [platformPort]. */
    private fun initiatorConnectsTo(answerPort: Int, platformPort: Int): FakeAware {
        val exchange = NativeExchange(9L, true, Any(), null, null, ByteArray(32), callbacks, answerPort)
        val link = FakeAware(9L, exchange, ManualScheduler())
        exchange.link = link
        val loopback = InetAddress.getByName("::1") as Inet6Address
        exchange.onDataPathReady(9L, AwareDataPathInfo("aware-test", 1, loopback, loopback, platformPort, listOf(5745)))
        return link
    }

    @Test
    fun anInitiatorWhosePlatformReportsNoPortConnectsToTheMwa1Port() {
        val link = initiatorConnectsTo(answerPort = 40123, platformPort = 0)
        assertEquals(listOf("connect"), link.calls.filter { it == "connect" })
        assertEquals(40123, link.lastConnectPort)
        assertTrue(lines.toString(), lines.any { it.contains("event=native_port") && it.contains("source=mwa1") })
        assertTrue(lines.none { it.contains("40123") })
    }

    @Test
    fun aPortThePlatformReportsIsUsedAsGiven() {
        val link = initiatorConnectsTo(answerPort = 40123, platformPort = 4242)
        assertEquals(4242, link.lastConnectPort)
        assertTrue(lines.any { it.contains("event=native_port") && it.contains("source=platform") && it.contains("match=false") })
        val agreeing = initiatorConnectsTo(answerPort = 4242, platformPort = 4242)
        assertEquals(4242, agreeing.lastConnectPort)
        assertTrue(lines.any { it.contains("event=native_port") && it.contains("source=platform") && it.contains("match=true") })
    }
}
