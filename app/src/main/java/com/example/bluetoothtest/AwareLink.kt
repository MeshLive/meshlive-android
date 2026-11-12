package com.example.bluetoothtest

import java.net.Inet6Address
import java.net.Socket

/**
 * What the app learned about Wi-Fi Aware from the platform. Read once per link in `AwareDataPath.onAttached`
 * (and by A8 through `WifiRadio.characteristics()`); the policy and learned caps are applied in the facts reader,
 * never here. The fifth parameter is defaulted so every existing construction site compiles. [GAP+]
 */
internal data class AwareCapsSummary(
    val maxServiceSpecificInfoLength: Int,
    val instantCommunicationSupported: Boolean,
    val setChannelOnDataPathSupported: Boolean,
    val maxNdp: Int,
    val maxServiceNameLength: Int = 255,
)

/** A discovered peer of one link generation; [handle] is the platform handle (never logged, never reused across links). */
internal class AwarePeer internal constructor(val ordinal: Int, internal val handle: Any?) {
    override fun equals(other: Any?) = other is AwarePeer && other.ordinal == ordinal
    override fun hashCode() = ordinal
    override fun toString() = "AwarePeer($ordinal)"
}

/** The addresses and channels of a ready data path; [channelsMhz] is null below API 33. */
internal data class AwareDataPathInfo(
    val interfaceName: String,
    val interfaceIndex: Int,
    val localAddress: Inet6Address, // our NDI link-local, scoped to the interface
    val peerAddress: Inet6Address, // WifiAwareNetworkInfo.peerIpv6Addr, scoped to the interface
    val port: Int, // WifiAwareNetworkInfo.port (guest: the host's published port; host: 0)
    val channelsMhz: List<Int>?, // API 33+; null below
)

/** Every call is made on the wifi thread, never inside an [AwareLink] method, and carries the generation. */
internal interface AwareLinkEvents {
    fun onAttached(gen: Long)
    fun onAttachFailed(gen: Long, reason: AttachFailure)
    fun onSessionStarted(gen: Long)
    fun onSessionFailed(gen: Long, reason: String, local: Boolean)
    fun onSessionTerminated(gen: Long)
    fun onPeerDiscovered(gen: Long, peer: AwarePeer)
    fun onMessage(gen: Long, peer: AwarePeer, bytes: ByteArray)
    fun onNetworkAvailable(gen: Long)
    fun onDataPathReady(gen: Long, info: AwareDataPathInfo)
    fun onNetworkUnavailable(gen: Long, elapsedMs: Long, timedOut: Boolean)
    fun onNetworkLost(gen: Long)
    fun onAwareDown(gen: Long, reason: AwareDownReason)
    fun onServerListening(gen: Long)
    fun onServerFailed(gen: Long, reason: String)
    fun onSocketAccepted(gen: Long, socket: Socket, ordinal: Int, acceptedAtNs: Long)
    fun onSocketRejected(gen: Long, reason: String)
    fun onConnected(gen: Long, socket: Socket, connectedAtNs: Long)
    fun onConnectFailed(gen: Long, reason: String, refusedOnly: Boolean)
}

/** Every method an empty body: an owner (A8) overrides only what it needs. [GAP+] */
internal open class AwareLinkEventsAdapter : AwareLinkEvents {
    override fun onAttached(gen: Long) {}
    override fun onAttachFailed(gen: Long, reason: AttachFailure) {}
    override fun onSessionStarted(gen: Long) {}
    override fun onSessionFailed(gen: Long, reason: String, local: Boolean) {}
    override fun onSessionTerminated(gen: Long) {}
    override fun onPeerDiscovered(gen: Long, peer: AwarePeer) {}
    override fun onMessage(gen: Long, peer: AwarePeer, bytes: ByteArray) {}
    override fun onNetworkAvailable(gen: Long) {}
    override fun onDataPathReady(gen: Long, info: AwareDataPathInfo) {}
    override fun onNetworkUnavailable(gen: Long, elapsedMs: Long, timedOut: Boolean) {}
    override fun onNetworkLost(gen: Long) {}
    override fun onAwareDown(gen: Long, reason: AwareDownReason) {}
    override fun onServerListening(gen: Long) {}
    override fun onServerFailed(gen: Long, reason: String) {}
    override fun onSocketAccepted(gen: Long, socket: Socket, ordinal: Int, acceptedAtNs: Long) {}
    override fun onSocketRejected(gen: Long, reason: String) {}
    override fun onConnected(gen: Long, socket: Socket, connectedAtNs: Long) {}
    override fun onConnectFailed(gen: Long, reason: String, refusedOnly: Boolean) {}
}

/** One attempt generation's Wi-Fi Aware link (A5 attempts and carriers, A8 native exchanges). */
internal interface AwareLink {
    val generation: Long

    /** Valid after `onAttached`; before that a conservative default (SSI 255, ICM false, hint false, maxNdp 1). */
    val caps: AwareCapsSummary

    /** For the shutdown residue. */
    val registeredNetworkCallbacks: Int

    fun acquire() // result: onAttached / onAttachFailed
    fun publish(serviceName: String, ttlSec: Int, icm: Boolean) // result: onSessionStarted / onSessionFailed
    fun subscribe(serviceName: String, ttlSec: Int, icm: Boolean)
    fun sendMessage(peer: AwarePeer, bytes: ByteArray): Boolean // false = refused locally (queue full, too long)
    fun requestResponder(peer: AwarePeer, pmk: ByteArray, port: Int, timeoutMs: Int): Boolean // pmk is copied; the caller may wipe its array on return
    fun requestInitiator(peer: AwarePeer, pmk: ByteArray, timeoutMs: Int): Boolean
    fun listen(info: AwareDataPathInfo, port: Int, maxAccepts: Int, windowMs: Int)
    fun connect(info: AwareDataPathInfo, connectTimeoutMs: Int, windowMs: Int)
    fun handOff(lossListener: (AwareDownReason) -> Unit): Boolean // success: the carrier now owns this link
    fun close(reason: String) // idempotent, ordered release

    /**
     * A8: use the caller's discovery sessions. Valid on the wifi thread after `onAttached(gen)` and before the request.
     * The link then does NOT install its own DiscoveryCallback, ignores their termination, `sendMessage` returns false
     * (the send callbacks belong to the caller), and `close()` never closes them. False when closed or the argument
     * types are wrong. Default body so FakeAware compiles unchanged. [GAP+][X13]
     */
    fun adoptSessions(publish: Any?, subscribe: Any?): Boolean = false

    /** A8: the AwarePeer for a PeerHandle of the caller's own discovery callback (same handle -> same AwarePeer, ordinals from 1). [GAP+] */
    fun peerFor(handle: Any): AwarePeer = AwarePeer(0, handle)
}
