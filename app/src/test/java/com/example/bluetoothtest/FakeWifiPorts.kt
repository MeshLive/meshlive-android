package com.example.bluetoothtest

import java.security.SecureRandom
import java.util.Collections

/** A scripted [WifiHopPorts] with the counters the tests inspect. */
internal class FakeWifiPorts(
    override val isLinkInitiator: Boolean = false,
    override val hopKind: WifiHopKind = WifiHopKind.CALL,
) : WifiHopPorts {
    override val tickets = CarrierTickets()
    val sent: MutableList<Bwf> = Collections.synchronizedList(ArrayList())
    val issuedCopies: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
    val carriers: MutableList<WifiSocketCarrier> = Collections.synchronizedList(ArrayList())
    val carrierSamples = HashMap<Int, CarrierSample>()
    val protocolViolations: MutableList<Pair<Int, String>> = Collections.synchronizedList(ArrayList())

    var sendResult = true
    var noKey = false
    var offerResult = 1
    var btBudgetKbps: Int? = null
    var requestedKbps: Int? = null

    /** AP10: the owner's answer; true (the production default) keeps the 5 GHz rule. */
    var otherBluetoothMediaValue = true

    /** False = the standby is stale, so `qualityFallback()` does nothing (as `CarrierCutover.onQualityFallback`). */
    var qualityFallbackHappens = true
    var qualityFallbackCalls = 0
    var carrierDiedCalls = 0
    var carrierSwitchOffCalls = 0
    var renewalRunningValue = false
    var hasWifiCarrierValue = false
    var fallbackCountValue = 0
    var callActiveValue = true
    var revealsDoneValue = true
    var optedInValue = true

    /** When set, [offerCarrier] / [revealsDone] throw it (an A3 or owner bug reaching the wifi thread). */
    var offerError: RuntimeException? = null
    var revealsError: RuntimeException? = null

    /** Delivers our outgoing BWF1 records to the other end (set by [connectTo]). */
    var uplink: ((Bwf) -> Unit)? = null

    /** When set, [connectTo] delivers through this scheduler (a non-blocking port, as in production). */
    var deliverScheduler: ManualScheduler? = null

    override fun optedIn(): Boolean = optedInValue

    override fun callActive(): Boolean = callActiveValue

    override fun revealsDone(): Boolean {
        revealsError?.let { throw it }
        return revealsDoneValue
    }

    override fun renewalRunning(): Boolean = renewalRunningValue

    override fun hasWifiCarrier(): Boolean = hasWifiCarrierValue

    override fun fallbackCount(): Int = fallbackCountValue

    override fun sendRecord(record: Bwf): Boolean {
        sent += record
        if (sendResult) uplink?.invoke(record)
        return sendResult
    }

    override fun uwCopy(): ByteArray? {
        if (noKey) return null
        val key = ByteArray(32) { (it + 3).toByte() }
        issuedCopies += key
        return key
    }

    override fun offerCarrier(carrier: WifiSocketCarrier): Int {
        offerError?.let { throw it }
        val id = offerResult
        if (id >= 0) {
            tickets.register(id, carrier.ticket)
            carriers += carrier
        }
        return id
    }

    override fun carrierLink(reader: SecureRecordReader, writer: SecureRecordWriter): SecureBluetoothSession =
        SecureBluetoothSession(
            peerNodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 1 }),
            peerCapabilities = NodeCapabilities(0, 0, 0, 0, 0),
            reader = reader,
            writer = writer,
            hopWifiKey = null,
        )

    override fun carrierDied(id: Int, reason: String) {
        carrierDiedCalls++
    }

    override fun localReady(): Bwf.Ready = Bwf.Ready(0xFFFF, 0, 0xFFFF)

    override fun protocolViolation(carrierId: Int, reason: String) {
        protocolViolations += carrierId to reason
    }

    override fun carrierSwitchOff() {
        carrierSwitchOffCalls++
    }

    override fun carrierSample(id: Int): CarrierSample? = carrierSamples[id]

    override fun bluetoothBudgetKbps(): Int? = btBudgetKbps

    override fun requestedMediaKbps(): Int? = requestedKbps

    override fun otherBluetoothMedia(): Boolean = otherBluetoothMediaValue

    override fun qualityFallback() {
        qualityFallbackCalls++
        // The cutover machine counts a fallback that really moved the hop; a stale standby leaves everything as it was.
        if (qualityFallbackHappens) fallbackCountValue++
    }

    /** Feeds our BWF1 records to [otherAgent] as if they arrived on carrier 0. */
    fun connectTo(otherAgent: WifiHopAgent) {
        uplink = { record ->
            val deliver = { otherAgent.onRecord(0, record.encode()) }
            val scheduler = deliverScheduler
            if (scheduler != null) scheduler.post(deliver) else deliver()
        }
    }
}

private fun defaultFacts(now: Long = 0L): WifiFacts = WifiFacts(
    api = 33,
    featureAware = true,
    featureDirect = false,
    awareAvailable = true,
    otherAwareClientAttached = false,
    awareIcmSupported = false,
    awareAttachHeldByThisApp = false,
    wifiEnabled = true,
    staFrequencyMhz = 5180,
    locationEnabled = true,
    permissionNearby = true,
    permissionFineLocation = true,
    switches = WifiSwitches(carrier = true, widerReach = false, direct = false),
    batteryPercent = 80,
    charging = false,
    batterySaver = false,
    thermalStatus = 0,
    familyPolicyAwareOff = false,
    familyPolicyDirectOff = false,
    localFaultPauseUntilMs = 0L,
    nowMs = now,
    directGroupActive = false,
    hotspotActive = false,
    freeNdp = 2,
)

/** A [WifiEnv] with a real ledger, a [ManualScheduler], scripted facts and links, and a log collector. */
internal class FakeEnv(
    override val scheduler: ManualScheduler = ManualScheduler(),
) : WifiEnv {
    override val ledger = WifiLedger { scheduler.nowMs() }
    val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
    val links = HashMap<Long, FakeAware>()
    val supported = HashSet<WifiBearer>().apply { add(WifiBearer.AWARE) }

    var factsValue: WifiFacts = defaultFacts()
    var screenOnValue = true
    var binderValue: WifiBinder = RealBinder
    var driverFactory: ((Long, BearerEvents) -> BearerDriver?)? = null

    override fun facts(): WifiFacts = factsValue.copy(nowMs = scheduler.nowMs())

    override fun newLink(generation: Long, events: AwareLinkEvents): AwareLink =
        links.getOrPut(generation) { FakeAware(generation, events, scheduler) }

    override fun screenOn(): Boolean = screenOnValue

    override fun random(): SecureRandom = random

    override fun binder(): WifiBinder = binderValue

    override fun log(body: String) {
        lines += body
    }

    override fun supportsBearer(bearer: WifiBearer): Boolean = bearer in supported

    override fun newBearerDriver(
        bearer: WifiBearer,
        generation: Long,
        ownerId: String,
        events: BearerEvents,
    ): BearerDriver? = driverFactory?.invoke(generation, events)

    val random = SecureRandom()
}

/** A scripted Wi-Fi Direct driver: records calls, records its own faults only where a test asks, posts callbacks. */
internal class FakeBearerDriver(
    override val bearer: WifiBearer = WifiBearer.DIRECT,
    private val generation: Long,
    private val events: BearerEvents,
    private val scheduler: ManualScheduler,
) : BearerDriver {
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    var closeCount = 0
        private set
    var prepareResult: BearerFailure? = null
    var handOffResult = true
    private var lossListener: ((AwareDownReason) -> Unit)? = null

    override fun prepare(): BearerFailure? {
        calls += "prepare"
        return prepareResult
    }

    override fun startHost(ssid: String, psk: String) {
        calls += "startHost"
    }

    override fun startGuest(ssid: String, psk: String, freqMhz: Int) {
        calls += "startGuest:$freqMhz"
    }

    override fun connect(ipv4: ByteArray, port: Int) {
        calls += "connect:$port"
    }

    override fun handOff(lossListener: (AwareDownReason) -> Unit): Boolean {
        calls += "handOff"
        this.lossListener = lossListener
        return handOffResult
    }

    override fun close(reason: String) {
        calls += "close:$reason"
        closeCount++
    }

    fun hostReady(freqMhz: Int, port: Int, ipv4: ByteArray = byteArrayOf(192.toByte(), 168.toByte(), 49, 1)) {
        calls += "hostReady"
        scheduler.post { events.onHostReady(generation, freqMhz, ipv4, port) }
    }

    fun joined(freqMhz: Int?) {
        calls += "joined"
        scheduler.post { events.onJoined(generation, freqMhz) }
    }

    fun fail(reason: String, decline: Int, local: Boolean) {
        calls += "fail:$reason"
        scheduler.post { events.onFailed(generation, BearerFailure(reason, decline, local)) }
    }

    fun accept(socket: java.net.Socket, ordinal: Int = 1) {
        calls += "accept:$ordinal"
        scheduler.post { events.onSocketAccepted(generation, socket, ordinal, System.nanoTime()) }
    }

    fun connected(socket: java.net.Socket) {
        calls += "connected"
        scheduler.post { events.onConnected(generation, socket, System.nanoTime()) }
    }

    fun lose(reason: AwareDownReason) {
        calls += "lose"
        val listener = lossListener
        if (listener != null) listener(reason) else scheduler.post { events.onLost(generation, reason) }
    }
}
