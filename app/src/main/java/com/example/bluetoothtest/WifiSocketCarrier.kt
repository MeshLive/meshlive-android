package com.example.bluetoothtest

import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The hand-off object: A3's [Carrier] over the socket that passed the BWU1 binding. Its reader and writer are the
 * primary link's session copy (`hopWifiKey = null`), so closing the carrier never zeroes the primary's `UW`.
 * Liveness (heartbeat, 2.5 s silence) is A3's per-carrier rule; this class only releases.
 */
internal class WifiSocketCarrier(
    override val kind: CarrierKind,
    private val socket: Socket,
    override val link: SecureBluetoothSession,
    private val releaseLink: (reason: String) -> Unit,
    private val ledger: WifiLedger,
    private val ndpOwner: String?,
    val ticket: ByteArray,
    val peerReady: Bwf.Ready,
    val bandVerified: Boolean,
    val channelMhz: Int?,
) : Carrier {
    override val label: String get() = kind.label
    override val liveness: CarrierLivenessPolicy = CarrierLivenessPolicy.WIFI

    private val closed = AtomicBoolean(false)

    @Volatile
    private var pendingLoss: AwareDownReason? = null

    /**
     * The offering code sets the handler after `offerCarrier` returned an id; a loss that came first fires from the
     * setter. NOT a member of A3's [Carrier].
     */
    @Volatile
    var lossHandler: ((AwareDownReason) -> Unit)? = null
        set(value) {
            field = value
            val early = pendingLoss
            if (early != null && value != null) {
                pendingLoss = null
                value(early)
            }
        }

    /** Called on the wifi thread by the link or driver (network lost, Aware down, Wi-Fi off, group lost). */
    fun platformLoss(reason: AwareDownReason) {
        val handler = lossHandler
        if (handler != null) handler(reason) else pendingLoss = reason
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        AwareSockets.close(socket)
        runCatching { link.close() }
        runCatching { releaseLink("carrier_closed") }
        ndpOwner?.let { ledger.releaseNdp(it) }
        ticket.fill(0)
        WifiLog.emit(WifiLog.upgrade("release", "scope" to "carrier", "reason" to "carrier_closed"))
    }
}
