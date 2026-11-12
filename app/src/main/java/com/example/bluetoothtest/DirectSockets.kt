package com.example.bluetoothtest

import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** The socket seam of A9's driver: production [DirectSockets], a scripted fake in the driver tests. [AM 15] */
internal interface DirectSocketPort {
    fun listen(local: DirectAddressRules.LocalAddress): ServerSocket
    fun admit(accepted: Socket, local: DirectAddressRules.LocalAddress): Boolean
    fun connect(local: DirectAddressRules.LocalAddress, host: Inet4Address, port: Int): Socket
}

/** Thin sockets over A4's options; no pure logic beyond [DirectAddressRules]. */
internal object DirectSockets : DirectSocketPort {
    /** Host: bound to the group interface's IPv4 only, ephemeral port, backlog 4. Never bound to 0.0.0.0. */
    override fun listen(local: DirectAddressRules.LocalAddress): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(local.address, 0), 4)
        }

    /** Host accept loop body for one socket: configure, or close if the remote is outside the group subnet. */
    override fun admit(accepted: Socket, local: DirectAddressRules.LocalAddress): Boolean {
        if (!DirectAddressRules.acceptRemote(local, accepted.inetAddress)) {
            runCatching { accepted.close() }
            return false
        }
        AwareSockets.applyOptions(accepted)
        return true
    }

    /** Guest: bound to its own group-interface address first, then connected with the master's 5 s timeout. */
    override fun connect(local: DirectAddressRules.LocalAddress, host: Inet4Address, port: Int): Socket =
        Socket().also { socket ->
            try {
                AwareSockets.applyOptions(socket)
                socket.bind(InetSocketAddress(local.address, 0))
                socket.connect(InetSocketAddress(host, port), WifiLimits.SOCKET_CONNECT_TIMEOUT_MS)
            } catch (error: IOException) {
                runCatching { socket.close() }
                throw error
            }
        }
}
