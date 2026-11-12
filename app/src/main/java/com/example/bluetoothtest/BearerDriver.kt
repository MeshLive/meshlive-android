package com.example.bluetoothtest

import java.net.Socket

/** What a driver reports: [decline] is a [WifiEligibility.DeclineReason.wire] value (1..7). */
internal class BearerFailure(val reason: String, val decline: Int, val local: Boolean)

/** Driver callbacks: all on the wifi thread, generation-stamped, never inside a driver method. */
internal interface BearerEvents {
    /** Group up AND the server socket listening and accepting; [freqMhz] 0 = unknown. */
    fun onHostReady(gen: Long, freqMhz: Int, ipv4: ByteArray, port: Int)

    /** The guest joined the group; the frequency is read after the join (null = unreadable). */
    fun onJoined(gen: Long, freqMhz: Int?)

    fun onFailed(gen: Long, failure: BearerFailure)
    fun onSocketAccepted(gen: Long, socket: Socket, ordinal: Int, acceptedAtNs: Long)
    fun onSocketRejected(gen: Long, reason: String)
    fun onConnected(gen: Long, socket: Socket, connectedAtNs: Long)
    fun onConnectFailed(gen: Long, reason: String, refusedOnly: Boolean)
    fun onLost(gen: Long, reason: AwareDownReason)
}

/**
 * The A5/A9 seam for a Wi-Fi Direct bearer. A5 drives it; A9 implements it (`DirectBearerDriver`). The driver owns the
 * group, its sockets, its accept loop and its discovery-pause lease; the executor derives the credentials and never
 * sends them. `prepare()` returns null on success, a [BearerFailure] otherwise (BUSY = `BearerFailure("busy", 2, false)`).
 */
internal interface BearerDriver {
    val bearer: WifiBearer

    /** Wifi thread. Takes the bearer's discovery pause and registers for shutdown. Claims nothing in the ledger. */
    fun prepare(): BearerFailure?

    fun startHost(ssid: String, psk: String)
    fun startGuest(ssid: String, psk: String, freqMhz: Int)
    fun connect(ipv4: ByteArray, port: Int)
    fun handOff(lossListener: (AwareDownReason) -> Unit): Boolean

    /** Idempotent, any thread; also called by [WifiSocketCarrier.close]. */
    fun close(reason: String)
}
