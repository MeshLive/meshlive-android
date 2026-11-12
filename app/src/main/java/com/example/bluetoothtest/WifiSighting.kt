package com.example.bluetoothtest

/**
 * One Wi-Fi Aware sighting of a discovery announcement. [handle] is the PeerHandle of subscribe session [sessionGeneration] (valid only there).
 * Public on purpose: it is a property of the public data class BleL2capPeer (an internal type there would not compile).
 */
class WifiSighting(
    val key: String,                       // announced 6 bytes as lower-case hex: the same key as privateCallPeersLastSeen
    val announcement: DiscoveryAnnouncement,
    val handle: Any,                       // android.net.wifi.aware.PeerHandle (Any keeps this class JVM-testable)
    val sessionGeneration: Long,
    val epoch: Long,                       // rotating-ID epoch of the service name it was heard under
    val seenMs: Long,                      // SystemClock.elapsedRealtime()
) {
    companion object {
        fun keyOf(announcement: DiscoveryAnnouncement): String =
            announcement.nodeIdPrefix.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Per person key: the freshest Bluetooth sighting and the freshest Wi-Fi sighting, each with its own staleness. The call list shows
 * one row per key (master section 8 "Dedupe"). Generic in the payload so JVM tests need no Bluetooth types.
 */
internal class PeerSightings<B : Any, W : Any>(
    private val bleFresh: (key: String, seenMs: Long, nowMs: Long) -> Boolean,
    private val wifiWindowMs: Long = WifiLimits.SIGHTING_STALE_MS,
) {
    enum class Leg { BLE, WIFI }

    data class Entry<B, W>(val ble: B?, val bleSeenMs: Long, val wifi: W?, val wifiSeenMs: Long) {
        val seenMs: Long get() = maxOf(if (ble != null) bleSeenMs else Long.MIN_VALUE, if (wifi != null) wifiSeenMs else Long.MIN_VALUE)
        val legs: Set<Leg> get() = buildSet { if (ble != null) add(Leg.BLE); if (wifi != null) add(Leg.WIFI) }
    }

    /** [lost] legs were removed by [expire]/[dropWifi]; [remaining] are the legs still present (empty: the key is gone). */
    data class Change(val key: String, val lost: Set<Leg>, val remaining: Set<Leg>)

    private val entries = LinkedHashMap<String, Entry<B, W>>()

    @Synchronized
    fun observeBle(key: String, peer: B, nowMs: Long): Entry<B, W> {
        val old = entries[key]
        return Entry(peer, nowMs, old?.wifi, old?.wifiSeenMs ?: 0L).also { entries[key] = it }
    }

    @Synchronized
    fun observeWifi(key: String, sighting: W, nowMs: Long): Entry<B, W> {
        val old = entries[key]
        return Entry(old?.ble, old?.bleSeenMs ?: 0L, sighting, nowMs).also { entries[key] = it }
    }

    /** The platform said the Wi-Fi peer is gone (`onServiceLost`). Null when there was no Wi-Fi leg. */
    @Synchronized
    fun dropWifi(key: String): Change? {
        val old = entries[key] ?: return null
        if (old.wifi == null) return null
        val next = old.copy(wifi = null, wifiSeenMs = 0L)
        if (next.ble == null) entries.remove(key) else entries[key] = next
        return Change(key, setOf(Leg.WIFI), next.legs)
    }

    /** Drops every Wi-Fi leg (the discovery plane stopped for good). */
    @Synchronized
    fun dropAllWifi(): List<Change> = entries.keys.toList().mapNotNull { dropWifi(it) }

    @Synchronized
    fun entry(key: String): Entry<B, W>? = entries[key]

    /** Removes stale legs; returns what changed (one Change per key that lost a leg). */
    @Synchronized
    fun expire(nowMs: Long): List<Change> {
        val changes = ArrayList<Change>()
        for (key in entries.keys.toList()) {
            val old = entries[key] ?: continue
            val lost = buildSet {
                if (old.ble != null && !bleFresh(key, old.bleSeenMs, nowMs)) add(Leg.BLE)
                if (old.wifi != null && nowMs - old.wifiSeenMs > wifiWindowMs) add(Leg.WIFI)
            }
            if (lost.isEmpty()) continue
            val next = Entry(
                if (Leg.BLE in lost) null else old.ble, old.bleSeenMs,
                if (Leg.WIFI in lost) null else old.wifi, old.wifiSeenMs,
            )
            if (next.legs.isEmpty()) entries.remove(key) else entries[key] = next
            changes += Change(key, lost, next.legs)
        }
        return changes
    }

    @Synchronized fun forget(key: String) { entries.remove(key) }
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun keys(): Set<String> = entries.keys.toSet()
}
