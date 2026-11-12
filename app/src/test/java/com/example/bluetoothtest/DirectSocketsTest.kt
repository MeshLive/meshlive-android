package com.example.bluetoothtest

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSocketsTest {
    private fun addr(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address

    private fun local(name: String, text: String, prefix: Int) =
        DirectAddressRules.LocalAddress(name, addr(text), prefix)

    @Test
    fun listenBindsOnlyTheGivenAddress() {
        val server = DirectSockets.listen(local("lo", "127.0.0.1", 8))
        try {
            assertEquals("127.0.0.1", server.inetAddress.hostAddress)
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", server.localPort), 1_000) }
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.2", server.localPort), 500) }
            }.also { assertTrue("a connect to another loopback address fails", it.isFailure) }
        } finally {
            server.close()
        }
    }

    @Test
    fun admitKeepsAnInSubnetRemoteAndClosesAnOutsideOne() {
        val server = ServerSocket(0, 4, addr("127.0.0.1"))
        try {
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort), 1_000)
                val accepted = server.accept()
                try {
                    assertTrue(DirectSockets.admit(accepted, local("lo", "127.0.0.2", 8)))
                    assertFalse(accepted.isClosed)
                } finally {
                    accepted.close()
                }
            }
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", server.localPort), 1_000)
                val accepted = server.accept()
                assertFalse(DirectSockets.admit(accepted, local("lo", "127.0.0.2", 32)))
                assertTrue(accepted.isClosed)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun connectBindsTheLocalAddressBeforeConnecting() {
        val server = ServerSocket(0, 4, addr("127.0.0.1"))
        try {
            val socket = DirectSockets.connect(local("lo", "127.0.0.1", 8), addr("127.0.0.1"), server.localPort)
            try {
                val accepted = server.accept()
                accepted.close()
                assertEquals("127.0.0.1", socket.localAddress.hostAddress)
                assertTrue(socket.localPort > 0)
                assertTrue(socket.isConnected)
            } finally {
                socket.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun aRefusedConnectClosesTheSocketAndThrows() {
        val server = ServerSocket(0, 1, addr("127.0.0.1"))
        val port = server.localPort
        server.close()
        val error = runCatching { DirectSockets.connect(local("lo", "127.0.0.1", 8), addr("127.0.0.1"), port) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is java.io.IOException)
    }
}
