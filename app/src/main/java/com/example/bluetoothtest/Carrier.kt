package com.example.bluetoothtest

import java.io.Closeable

/** The kind of byte stream that carries the `BMS1` records of one hop. Never mixed into [BluetoothTransportOption]. */
enum class CarrierKind(val label: String, val isWifi: Boolean) {
    BLUETOOTH("Bluetooth", false),
    WIFI_AWARE("Wi-Fi Aware", true),
    WIFI_DIRECT("Wi-Fi Direct", true),
}

/** The carrier kind of an A0 Wi-Fi bearer: what A5's role choice (`RoleTuple.bearer`) becomes when the carrier is built. */
internal fun WifiBearer.carrierKind(): CarrierKind = when (this) {
    WifiBearer.AWARE -> CarrierKind.WIFI_AWARE
    WifiBearer.DIRECT -> CarrierKind.WIFI_DIRECT
}

/** What `CarrierSet.Host.onCarrierEvent` and an endpoint `CarrierListener` report about the set. SEND_CHANGED is reported by the endpoint from `onSendCarrierChanged`. */
enum class CarrierEvent { SEND_CHANGED, ACCEPT_CHANGED, ADDED, ENDED, PRIMARY_REPLACED }

/** Where a peer's arrival report for one carrier belongs: the Bluetooth probe, that carrier's own key, or nowhere. */
internal enum class ProbeTarget { NONE, BLUETOOTH, CARRIER }

/** The tested table (amendment 20 c): a Wi-Fi carrier's report never reaches the Bluetooth learned capacity. */
internal fun probeTarget(kind: CarrierKind, pacedWrites: Boolean): ProbeTarget = when {
    kind.isWifi -> ProbeTarget.CARRIER
    pacedWrites -> ProbeTarget.BLUETOOTH
    else -> ProbeTarget.NONE
}

/** One carrier change as the UI and A6 hear it; [snapshot] is consistent with the event. [GAP+] */
internal data class CarrierChange(
    val event: CarrierEvent,
    val carrierId: Int,
    val kind: CarrierKind?,
    val reason: String,
    val snapshot: CarrierSnapshot,
)

/** Receives carrier changes: reader, writer, consumer, monitor, UI or renewal thread; never under the cutover lock, possibly under the recv lock; must not block or throw. [GAP+] */
internal fun interface CarrierListener {
    fun onCarrierChange(change: CarrierChange)
}

/** The endpoint's listener list: a throwing listener never stops the others (logged by class name only). [GAP+] */
internal class CarrierListeners {
    private val list = java.util.concurrent.CopyOnWriteArrayList<CarrierListener>()

    fun add(l: CarrierListener) {
        list.addIfAbsent(l)
    }

    fun remove(l: CarrierListener) {
        list.remove(l)
    }

    fun notify(change: CarrierChange, onFailure: (String) -> Unit = {}) {
        for (l in list) runCatching { l.onCarrierChange(change) }.onFailure { onFailure(it.javaClass.simpleName) }
    }
}

/**
 * How one carrier dies. A Bluetooth carrier keeps today's rule (call-level 8 s/14 s silence rules through
 * `CallStallTolerance`, supplied by the call); a Wi-Fi carrier has its own numbers (master 4.3).
 */
class CarrierLivenessPolicy(
    /** An idle direction writes a heartbeat this often (Bluetooth 1 s as today, Wi-Fi 500 ms). */
    val heartbeatIntervalNs: Long,
    /** Wi-Fi: dead after this much time without a complete authenticated record; 0 = the host decides (Bluetooth). */
    val deadAfterSilenceNs: Long,
    /** Wi-Fi: dead after a write stalled this long; 0 = not monitored by [CarrierSet] (Bluetooth). */
    val deadAfterStalledWriteNs: Long,
    /** Flush after a content record at most this often; null = ask the host (Bluetooth), 0 = after every record (Wi-Fi). */
    val flushIntervalNs: Long?,
) {
    companion object {
        val BLUETOOTH = CarrierLivenessPolicy(1_000_000_000L, 0L, 0L, null)
        val WIFI = CarrierLivenessPolicy(500_000_000L, 2_500_000_000L, 2_500_000_000L, 0L)

        /** The policy of a carrier kind (a native Wi-Fi carrier 0 uses [WIFI]). */
        fun of(kind: CarrierKind): CarrierLivenessPolicy = if (kind.isWifi) WIFI else BLUETOOTH
    }
}

/**
 * One byte stream of a hop with the authenticated `BMS1` reader/writer built on it. Every method may be called from any thread.
 * Platform loss of a Wi-Fi carrier is reported by its owner with AndroidPrivateVideoCall.wifiCarrierDied(id, reason); a Carrier has no loss callback. [API]
 */
interface Carrier : Closeable {
    val kind: CarrierKind

    /** User/log label without any address: "BLE GATT", "Wi-Fi Aware". */
    val label: String

    /** The `BMS1` layer of this carrier (own keys, own sequence numbers). */
    val link: SecureBluetoothSession

    val liveness: CarrierLivenessPolicy

    /** Unblocks a blocked read or write of this carrier and releases its resources. Idempotent. Never throws. Never blocks: it may run on any thread, a thread of the CarrierSet included (X7, amendment 5). */
    override fun close()
}

/**
 * A carrier over an existing link session; [closeAction] closes the underlying connection or socket.
 * [closesLink] = false for carrier 0 of a call or hop: its owner closes the connection and then the link, in that order (key zeroing). [API]
 */class SessionCarrier(
    override val kind: CarrierKind,
    override val label: String,
    override val link: SecureBluetoothSession,
    override val liveness: CarrierLivenessPolicy,
    private val closesLink: Boolean = true,
    private val closeAction: () -> Unit,
) : Carrier {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { closeAction() }
        if (closesLink) runCatching { link.close() }
    }
}
