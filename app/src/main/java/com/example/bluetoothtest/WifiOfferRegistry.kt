package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream

/** A connected Wi-Fi-native byte stream, handed to whoever offered the thing the peer asked for. Public: a parameter type of the public BluetoothVideoSender.connect [AM 13]. */
class WifiNativeConnection(
    val input: InputStream,
    val output: OutputStream,
    val close: () -> Unit,
    /** `WifiLog.hash(peer address)`; the address itself never leaves the radio code. */
    val peerHash: String,
    /** The consumer calls this once the BMH3/BMH2 link handshake on these streams succeeded (F-3 admission) [AM 12]. */
    val admitted: () -> Unit = {},
)

/** What this phone currently offers, by role. Highest priority is published (issue 1). */
internal enum class WifiOfferRole(val priority: Int, val logName: String) {
    VIEWER_REQUEST(3, "viewer"), STREAM_OFFER(2, "stream"), CALL_OFFER(1, "call"),
}

/**
 * [acceptor] takes a native connection from a peer that asked for this offer: set for a call offer and a viewer request (they
 * listen), null for a stream offer (the source dials the viewer, never the other way round).
 */
internal class WifiOffer(val announcement: DiscoveryAnnouncement, val acceptor: ((WifiNativeConnection) -> Unit)?)

internal class WifiOfferRegistry {
    fun interface Listener { fun onOffersChanged() }

    private val offers = java.util.EnumMap<WifiOfferRole, WifiOffer>(WifiOfferRole::class.java)
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()
    @Volatile var version: Long = 0L
        private set

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    /** Only rotating announcements go on Wi-Fi (A0's Mwd1 decoder refuses others): a fixed-prefix offer clears the role and returns false [AM 9]. */
    fun publish(role: WifiOfferRole, announcement: DiscoveryAnnouncement, acceptor: ((WifiNativeConnection) -> Unit)? = null): Boolean {
        if (!announcement.rotating) { clear(role); return false }
        synchronized(this) { offers[role] = WifiOffer(announcement, acceptor); version++ }
        listeners.forEach { runCatching { it.onOffersChanged() } }
        return true
    }

    fun clear(role: WifiOfferRole) {
        val changed = synchronized(this) { (offers.remove(role) != null).also { if (it) version++ } }
        if (changed) listeners.forEach { runCatching { it.onOffersChanged() } }
    }

    /** Clears [role] only while its offer satisfies [owned] (an owner's stop never removes another owner's newer offer). */
    fun clearIf(role: WifiOfferRole, owned: (WifiOffer) -> Boolean) {
        val changed = synchronized(this) {
            val offer = offers[role]
            (offer != null && owned(offer)).also { if (it) { offers.remove(role); version++ } }
        }
        if (changed) listeners.forEach { runCatching { it.onOffersChanged() } }
    }

    @Synchronized
    fun current(): Pair<WifiOfferRole, WifiOffer>? =
        offers.entries.maxByOrNull { it.key.priority }?.let { it.key to it.value }

    /** The Aware service-specific info for the current offer: `MWD1 | 1 | the 22 announcement bytes`. Null when nothing is offered. */
    fun info(): ByteArray? = snapshot()?.info

    /** The offer to publish now: role, its key (`role:announcement key`, AM 9) and its info bytes. */
    class Snapshot(val role: WifiOfferRole, val key: String, val info: ByteArray)

    /** One read of the current offer, so the published bytes and the key always describe the same offer (A8 review P2-2). */
    fun snapshot(): Snapshot? = current()?.let { (role, offer) ->
        Snapshot(
            role,
            role.logName + ":" + WifiSighting.keyOf(offer.announcement),
            Mwd1(DiscoveryAnnouncementCodec.encode(offer.announcement)).encode(),   // [API]
        )
    }

    /** The acceptor of the offer currently published, or null. A knock is served only if this is non-null. */
    @Synchronized
    fun currentAcceptor(): ((WifiNativeConnection) -> Unit)? = current()?.second?.acceptor

    @Synchronized
    fun roleName(): String = current()?.first?.logName ?: "none"
}
