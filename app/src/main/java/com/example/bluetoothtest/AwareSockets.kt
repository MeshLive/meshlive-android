package com.example.bluetoothtest

import java.io.Closeable
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Every Wi-Fi socket of this process, tracked by its link generation, so a close (or the shutdown residue) can reach
 * a socket that a short-lived accept/connect thread owns. A handed-off socket is excluded from its own generation's
 * sweep with [closeOwned]'s `except` argument. A generation closed with [closeGeneration] stays closed: a socket
 * that an accept or connect thread tracks for it afterwards is closed at once, so a close racing those threads
 * leaks nothing. [A4 review P2-3]
 */
internal object AwareSockets {
    private val open = ConcurrentHashMap<Closeable, Long>() // value = owner generation
    private val closedOwners = ConcurrentHashMap.newKeySet<Long>() // one entry per closed link (generations are unique)

    fun openCount(): Int = open.size

    /** Tracks first, then checks the closed mark (the reverse order of [closeGeneration]), so either side closes it. */
    fun <T : Closeable> track(owner: Long, closeable: T): T {
        open[closeable] = owner
        if (owner in closedOwners) close(closeable)
        return closeable
    }

    /** The link of [owner] closed: closes its sockets now and every socket tracked for it later. [A4 review P2-3] */
    fun closeGeneration(owner: Long) {
        closedOwners.add(owner)
        closeOwned(owner)
    }

    fun close(closeable: Closeable?) {
        closeable ?: return
        open.remove(closeable)
        runCatching { closeable.close() }
    }

    /** Every tracked item of that generation except [except]. */
    fun closeOwned(owner: Long, except: Closeable? = null) {
        open.entries.filter { it.value == owner && it.key !== except }.map { it.key }.forEach { close(it) }
    }

    /** Master 5.5: TCP_NODELAY, send buffer 256 KiB, keep-alive off. Called on every Wi-Fi socket before first use. */
    fun applyOptions(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = false
        socket.sendBufferSize = WifiLimits.SOCKET_SEND_BUFFER_BYTES
    }

    /**
     * Accept only the peer of the data path. [remote] must be an IPv6 address with exactly [expected]'s 16 bytes. A scope
     * id of 0 is accepted (some stacks omit it); a non-zero scope must equal [ifaceIndex]. Everything else (IPv4, other
     * bytes, other interface) is refused and, by the caller, closed without counting toward the attempt.
     */
    fun isExpectedPeer(remote: InetAddress?, expected: Inet6Address, ifaceIndex: Int): Boolean {
        val v6 = remote as? Inet6Address ?: return false
        if (!v6.address.contentEquals(expected.address)) return false
        val scope = v6.scopeId
        return scope == 0 || scope == ifaceIndex
    }

    /** The NDI's own link-local address with the interface scope (rev 2 step 8: never the wildcard). */
    fun scopedLocal(linkLocal: Inet6Address, iface: NetworkInterface): Inet6Address =
        Inet6Address.getByAddress(null, linkLocal.address, iface)

    /** Server bound to [local]:[port], backlog 2, SO_REUSEADDR, accept slice [WifiRadioLimits.ACCEPT_POLL_MS]. Throws IOException. */
    fun openServer(local: Inet6Address, port: Int): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(local, port), 2)
        server.soTimeout = WifiRadioLimits.ACCEPT_POLL_MS
        return server
    }
}
