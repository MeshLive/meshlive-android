package com.example.bluetoothtest

import android.content.Context
import android.os.PowerManager
import java.net.Socket
import java.security.SecureRandom

/** The environment of an attempt: the wifi scheduler, the ledger, the platform facts and the radio seams. */
internal interface WifiEnv {
    val scheduler: WifiScheduler
    val ledger: WifiLedger

    /** The facts AFTER [WifiDebugArms.adjust] (A4 applies it inside `WifiRadio.facts()`); A5 never reads `DebugTunables.wifiIcm`. */
    fun facts(): WifiFacts

    fun decide(facts: WifiFacts): WifiEligibility.Decision = WifiEligibility.decide(facts)
    fun newLink(generation: Long, events: AwareLinkEvents): AwareLink
    fun screenOn(): Boolean
    fun random(): SecureRandom
    fun binder(): WifiBinder
    fun log(body: String)
    fun supportsBearer(bearer: WifiBearer): Boolean = bearer == WifiBearer.AWARE

    fun newBearerDriver(bearer: WifiBearer, generation: Long, ownerId: String, events: BearerEvents): BearerDriver? = null
}

internal interface WifiBinder {
    fun host(
        socket: Socket,
        acceptedAtNs: Long,
        uw: ByteArray,
        gate: TicketGate,
        ticket: ByteArray,
        ready: Bwf.Ready,
    ): WifiBinding.HostResult

    fun guest(
        socket: Socket,
        connectedAtNs: Long,
        uw: ByteArray,
        ticket: ByteArray,
        ready: Bwf.Ready,
    ): WifiBinding.GuestResult
}

internal object RealBinder : WifiBinder {
    override fun host(
        socket: Socket,
        acceptedAtNs: Long,
        uw: ByteArray,
        gate: TicketGate,
        ticket: ByteArray,
        ready: Bwf.Ready,
    ): WifiBinding.HostResult = WifiBinding.host(socket, acceptedAtNs, uw, gate, ticket, ready)

    override fun guest(
        socket: Socket,
        connectedAtNs: Long,
        uw: ByteArray,
        ticket: ByteArray,
        ready: Bwf.Ready,
    ): WifiBinding.GuestResult = WifiBinding.guest(socket, connectedAtNs, uw, ticket, ready)
}

internal object RealWifiEnv : WifiEnv {
    private val randomSource = SecureRandom()
    @Volatile private var appContext: Context? = null
    @Volatile private var powerManager: PowerManager? = null

    override val scheduler: WifiScheduler get() = WifiRadio.scheduler
    override val ledger: WifiLedger get() = WifiRadio.ledger

    override fun facts(): WifiFacts = WifiRadio.facts()
    override fun newLink(generation: Long, events: AwareLinkEvents): AwareLink = WifiRadio.newLink(generation, events)

    override fun supportsBearer(bearer: WifiBearer): Boolean =
        bearer == WifiBearer.AWARE || (bearer == WifiBearer.DIRECT && DirectBearerFactory.available())

    override fun newBearerDriver(bearer: WifiBearer, generation: Long, ownerId: String, events: BearerEvents): BearerDriver? =
        if (bearer == WifiBearer.DIRECT) DirectBearerFactory.create(generation, events, facts()) else null

    override fun screenOn(): Boolean = try {
        powerManager()?.isInteractive ?: true
    } catch (_: Exception) {
        true
    }

    /** Looked up on first use: only a non-inert agent asks, so a process with every switch off never does (X4). */
    private fun powerManager(): PowerManager? = powerManager ?: appContext
        ?.let { runCatching { it.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull() }
        ?.also { powerManager = it }

    override fun random(): SecureRandom = randomSource
    override fun binder(): WifiBinder = RealBinder
    override fun log(body: String) = WifiLog.emit(body)

    /** Stores the application context only (no platform call); A5.8 calls it right after `WifiRadio.attach(...)`. [X4] */
    fun attach(app: Context) {
        appContext = app.applicationContext
    }
}
