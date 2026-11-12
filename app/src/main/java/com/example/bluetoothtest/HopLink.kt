package com.example.bluetoothtest

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Callbacks into the file that created a stream or relay hop (A7). */
internal interface HopOwner {
    /** Reseed the pacer, the NodeLinkLoad accounting and `LinkChangeEvents.carrierChanged()` (the `carrier:` line is HopLink's). */
    fun sendCarrierChanged(kind: CarrierKind)

    fun localKeyframeNeeded()

    fun peerKeyframeNeeded()

    fun reassertState()

    /** `EndSession`: close the hop (the owner closes this HopLink and its own link). Never runs on a CarrierSet thread. */
    fun hopEnded(reason: String)

    /** Interim "no ceiling known", equal to A5's FakeWifiPorts value. */
    fun wifiReady(): Bwf.Ready = Bwf.Ready(0xFFFF, 0, 0xFFFF)

    fun bluetoothBudgetKbps(): Int? = null

    /** Hop target rate for the carrier queue size: sender settings.bitRate / 1_000, receiver and forwarder streamBitrateKbps, relay null. */
    fun targetKbps(): Int? = null
}

/** The agent factory the creation sites pass. Reads the live environment. */
internal fun newHopAgent(ports: WifiHopPorts): WifiHopAgent? = WifiHopAgent(ports, RealWifiEnv)

/** Live-stream relays (BluetoothVideoRelay) read their children through this: a child's BWF1 is skipped, never an error. */
internal fun readSkippingBwf1(reader: SecureRecordReader): SecureRecord {
    while (true) {
        val record = reader.read()
        if (record.type != SecureRecordType.BWF1) return record
    }
}

/** Capacity is learned into the Bluetooth keys only while both directions of the hop are on Bluetooth. */
internal fun learnsBluetoothCapacity(acceptKind: CarrierKind, sendKind: CarrierKind): Boolean =
    acceptKind == CarrierKind.BLUETOOTH && sendKind == CarrierKind.BLUETOOTH

/**
 * A7 review P1-2: a hop's send-side [NodeLinkLoad] accounting, counted only while media is sent on Bluetooth. The owner's
 * writer and the hop's carrier callback share this one object, so Wi-Fi bytes never count as Bluetooth load, every handle
 * it opens is closed exactly once, and nothing reopens after [close]. With every Wi-Fi switch off it is one handle from
 * [start] to [close], exactly the handle the writer opened before A7.
 */
internal class HopSendLoad(private val open: () -> NodeLinkLoad.Handle) : Closeable {
    private var handle: NodeLinkLoad.Handle? = null
    private var started = false
    private var closed = false
    private var onWifi = false

    /** The writer starts sending media: count it from now (unless media already goes over Wi-Fi). */
    @Synchronized
    fun start() {
        if (closed || started) return
        started = true
        if (!onWifi) handle = open()
    }

    /** The hop's send carrier changed (`HopOwner.sendCarrierChanged`). */
    @Synchronized
    fun onSendCarrier(kind: CarrierKind) {
        onWifi = kind.isWifi
        if (!started || closed) return
        if (onWifi) {
            handle?.close()
            handle = null
        } else if (handle == null) {
            handle = open()
        }
    }

    @Synchronized
    fun add(bytes: Int) {
        handle?.add(bytes)
    }

    @Synchronized
    fun saturated() {
        handle?.saturated()
    }

    @Synchronized
    fun withdrawnForCongestion() {
        handle?.withdrawnForCongestion()
    }

    @Synchronized
    fun withdrawnForLocalCapacity() {
        handle?.withdrawnForLocalCapacity()
    }

    @Synchronized
    override fun close() {
        closed = true
        handle?.close()
        handle = null
    }
}

/** Every live HopLink, for the switch-off observers. */
internal object HopLinks {
    private val hops = ConcurrentHashMap.newKeySet<HopLink>()

    fun register(hop: HopLink) {
        hops.add(hop)
    }

    fun unregister(hop: HopLink) {
        hops.remove(hop)
    }

    /** AP10: the live stream and relayed-call hops of this process (read-only; any thread). */
    fun liveCount(): Int = hops.size

    /** The `wifi_carrier` switch went off: release the Wi-Fi carrier of every stream and relay hop. */
    fun switchOffAll() {
        hops.toList().forEach { runCatching { it.onSwitchOff() } }
    }

    /** Wi-Fi Direct alone went off: only hops whose Wi-Fi carrier is Direct (chosen or live) release it. */
    fun switchOffDirect() {
        hops.toList().filter { it.usesDirect() }.forEach { runCatching { it.onSwitchOff() } }
    }
}

/**
 * AP10: "does this phone carry Bluetooth media on a link other than this call's own link?", for the 2.4 GHz-alone Aware
 * rule. Answered for a call only, from state that already exists (never a radio query), all of it safe to read on the
 * wifi thread:
 * - [HopLinks]: every stream hop (a viewer this phone serves, the parent it watches or forwards from, a forwarder
 *   child) and every relayed-call hop this phone relays, from link authentication to close, whatever carrier its
 *   media is on at the moment (a hop on Wi-Fi can return to Bluetooth at any time);
 * - [NodeLinkLoad]: the stream media links counted on Bluetooth right now, sending and receiving;
 * - the call endpoint's own relay duty (`AndroidPrivateVideoCall.relayingNow`), from before its hops exist;
 * - the manual stream relay ([BluetoothVideoRelay], the DEBUG `test_role=relay` role), which has no hop.
 * The call's own link is in none of them, so its Bluetooth standby never counts. A stream or relay hop cannot see
 * whether this phone is in a call, so `HopLink` keeps `WifiHopPorts.otherBluetoothMedia()` at its default, true.
 */
internal object OtherBluetoothMedia {
    private val manualRelays = AtomicInteger(0)

    /** A [BluetoothVideoRelay] worker started (paired with [manualRelayStopped] in its `finally`). */
    fun manualRelayStarted() {
        manualRelays.incrementAndGet()
    }

    fun manualRelayStopped() {
        manualRelays.decrementAndGet()
    }

    /** The rule itself, pure. */
    fun forCall(liveHops: Int, streamSendLinks: Int, streamReceiveLinks: Int, relayDuty: Boolean, manualRelays: Int): Boolean =
        liveHops > 0 || streamSendLinks > 0 || streamReceiveLinks > 0 || relayDuty || manualRelays > 0

    /** [relayDuty]: this call endpoint is relaying another call. Any thread. */
    fun forCall(relayDuty: Boolean): Boolean = forCall(
        liveHops = HopLinks.liveCount(),
        streamSendLinks = BluetoothTransportOption.entries.sumOf { NodeLinkLoad.sendLinks(it) },
        streamReceiveLinks = BluetoothTransportOption.entries.sumOf { NodeLinkLoad.receiveLinks(it) },
        relayDuty = relayDuty,
        manualRelays = manualRelays.get(),
    )
}

/**
 * A7: one stream or relayed-call hop's raw-to-carrier switch. It is a pass-through to the raw Bluetooth session until
 * both `CAPS` are known and an attempt is feasible, then it owns an A3 [CarrierSet]. One per hop per phone.
 *
 * Threads: activation runs only on the thread that calls [reader].read() (inside `onRawBwf1`, or at the top of
 * `Source.read` when an owner thread noted content). The carrier-0 pump is `hop-<id>-pump`; the end callback runs on
 * `hop-<id>-end` (never on a CarrierSet thread).
 */
internal class HopLink(
    val role: HopCapsPlan.HopRole,
    val session: SecureBluetoothSession,            // carrier 0's session, exactly as the link handshake returned it
    private val closeRaw: () -> Unit,               // closes the raw Bluetooth connection (socket / GATT / control)
    private val anyLocalSwitchOn: () -> Boolean,    // WifiSwitchHub::anyOn
    private val log: (String) -> Unit,              // WifiLog::emit: the line WITHOUT "BENCHMARK " (A4 adds it in DEBUG)
    private val owner: HopOwner,                    // callbacks into the file that created this hop
    private val newAgent: (WifiHopPorts) -> WifiHopAgent?,   // ::newHopAgent; null = no attempt owner
    neighbourIsRelay: Boolean = false,              // RELAY_DOWNSTREAM: the onward neighbour is a relay
    carrier0IsBluetooth: Boolean = true,            // A8's Wi-Fi-native hops pass false: mode OFF, no CAPS, no attempt
    private val localCaps: () -> Bwf.Caps = { WifiLocalCaps.build(RealWifiEnv, keyAvailable = session.hopWifiKey != null) },
    private val nanoClock: () -> Long = System::nanoTime,
) : Closeable {
    companion object {
        /** Capacity of the records an owner's reader may lag behind the merged carrier source. */
        const val HOP_DELIVERED_CAPACITY = 256

        private val nextId = AtomicInteger(0)
    }

    private class Ended(val reason: String, val cause: Exception?)

    val mode: HopWifiGate.Mode = HopWifiGate.mode(session.wifiReadOptIn, anyLocalSwitchOn(), carrier0IsBluetooth)

    private val plan = HopCapsPlan(
        role,
        mode,
        WifiHopGate.peerCapable(session),
        bmh2TagGate = session.wifiBuildTagGate,
        neighbourIsRelay = neighbourIsRelay,
    )

    /** 4 lowercase hex characters from a process-wide counter, never derived from a node id or address. */
    private val id: String = (nextId.incrementAndGet() and 0xffff).toString(16).padStart(4, '0')

    private val hopLog: String =
        if (role == HopCapsPlan.HopRole.STREAM_PARENT || role == HopCapsPlan.HopRole.STREAM_CHILD) {
            WifiHopKind.STREAM.log
        } else {
            WifiHopKind.RELAY.log
        }

    private val governedTypes = HopRecordTypes.content(role)
    private val tickets = CarrierTickets()

    private val hopLock = Any()
    @Volatile private var carriers: CarrierSet? = null
    @Volatile private var agent: WifiHopAgent? = null
    @Volatile private var space: SecureRecordType? = null
    @Volatile private var pendingActivation = false
    @Volatile private var wifiEnded = false
    @Volatile private var chosenBearer: WifiBearer? = null
    @Volatile private var ended: Ended? = null
    @Volatile private var pump: Thread? = null

    private val closed = AtomicBoolean(false)
    private val activated = AtomicBoolean(false)
    private val agentEnded = AtomicBoolean(false)
    private val carriersClosed = AtomicBoolean(false)
    private val endReported = AtomicBoolean(false)
    private val firstHeaderDone = AtomicBoolean(false)
    private val contentForwardedDone = AtomicBoolean(false)
    private val seqLock = Any()
    private var lastGovernedSeq = -1L

    private val delivered = LinkedBlockingQueue<SecureRecord>(HOP_DELIVERED_CAPACITY)

    @Volatile
    var ownCaps: Bwf.Caps = Bwf.Caps(0, 0, 0)
        private set

    val writer: HopRecordSink = Sink()
    val reader: HopRecordSource = Source()

    /** Capacity-learning guard: the carrier media currently arrives on. */
    val acceptKind: CarrierKind get() = carriers?.acceptKind ?: CarrierKind.BLUETOOTH

    /** Capacity-learning guard: the carrier media is currently sent on. */
    val sendKind: CarrierKind get() = carriers?.snapshot()?.sendKind ?: CarrierKind.BLUETOOTH

    init {
        if (mode != HopWifiGate.Mode.OFF) session.reader.acceptWifi = true
        HopLinks.register(this)
    }

    // -------------------------------------------------------------------------------------------- writer

    private inner class Sink : HopRecordSink {
        override fun write(type: SecureRecordType, payload: ByteArray): Int {
            synchronized(hopLock) {
                if (carriers == null) return session.writer.write(type, payload)
            }
            val set = carriers ?: return 0                 // never null again after activation
            if (type !in governedTypes) return sendHopRecord(set, type, payload)     // handshake leftovers, control, heartbeat
            val established = space ?: type.also { space = it }
            if (type != established) {
                log("hop_seq_mixed: hop=${role.name} | type=${type.wireValue}")
                return sendHopRecord(set, type, payload)
            }
            // A7 review P3-5: a relay forwards an endpoint's record opaquely; one whose sequence cannot be read (truncated,
            // bad magic) goes ungoverned like a mixed space instead of ending the relayed call.
            val seq = readableSequence(type, payload)
            if (seq == null) {
                log("hop_seq_bad: hop=${role.name} | type=${type.wireValue}")
                return sendHopRecord(set, type, payload)
            }
            synchronized(seqLock) {                        // A7 Am 11: a regressed sequence is never given to the machine
                if (seq <= lastGovernedSeq) {
                    log("hop_seq_regress: hop=${role.name} | type=${type.wireValue}")
                    return sendHopRecord(set, type, payload)
                }
                lastGovernedSeq = seq
            }
            return set.sink.write(type, payload)           // A3: wire bytes, 0 = dropped
        }

        override fun flush() {
            synchronized(hopLock) {
                if (carriers == null) {
                    session.writer.flush()
                    return
                }
            }
            carriers?.requestFlush()
        }
    }

    /** The cutover sequence of a governed record, or null when its cleartext header cannot be read (truncated, bad magic). */
    private fun readableSequence(type: SecureRecordType, payload: ByteArray): Long? = try {
        HopSequence.of(type, payload)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun sendHopRecord(set: CarrierSet, type: SecureRecordType, payload: ByteArray): Int =
        if (set.sendHopToSender(type, payload)) payload.size + SECURE_RECORD_OVERHEAD_BYTES else 0

    // -------------------------------------------------------------------------------------------- reader

    private inner class Source : HopRecordSource {
        override var maxCiphertextBytes: Int
            get() = session.reader.maxCiphertextBytes
            set(value) {
                synchronized(hopLock) {
                    session.reader.maxCiphertextBytes = value
                    carriers?.setMaxCiphertextBytes(value)
                }
            }

        override fun read(): SecureRecord {
            while (true) {
                if (carriers == null) {                                    // raw: this thread reads the session
                    if (pendingActivation) {
                        pendingActivation = false
                        activate()
                        continue
                    }
                    val record = session.reader.read()
                    if (record.type == SecureRecordType.BWF1) {
                        onRawBwf1(record.payload)
                        continue
                    }
                    return record
                }
                val item = try {
                    delivered.poll(100, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted")
                }
                if (item != null) return item                              // carrier mode never delivers BWF1 here
                ended?.let { throw IOException("Wi-Fi hop ended: ${it.reason}", it.cause) }
            }
        }
    }

    /**
     * A `BWF1` record read while still raw, on the reading thread. A `KIND` error is ignored (forward compatibility);
     * any other decode error ends Wi-Fi for this hop but never the Bluetooth hop.
     */
    private fun onRawBwf1(payload: ByteArray) {
        if (wifiEnded) return
        val record = try {
            WifiWire.decodeBwf1(payload)
        } catch (error: WifiWireException) {
            if (error.error == WifiWireError.KIND) {
                log("bwf1_unexpected: kind=?")
                return
            }
            wifiEnded = true
            log("bwf1_bad: hop=${role.name} | reason=${error.error}")
            return
        }
        when (record) {
            is Bwf.Caps -> {
                if (!plan.onPeerCaps(record)) {
                    log("bwf1_unexpected: kind=1 | reason=caps_repeat")
                    return
                }
                if (plan.shouldSendOwn(firstHeaderSeen = false, contentForwarded = false, peerCapsSeen = true)) {
                    sendOwnCapsRaw()
                }
                val tuple = plan.attemptChoice(ownCaps)
                if (tuple != null) {
                    chosenBearer = tuple.bearer
                    activate()
                }
            }
            else -> log("bwf1_unexpected: kind=${record.kind}")
        }
    }

    /** STREAM_CHILD: call once, after the first complete stream header was read. Writes CAPS from this thread; never activates. */
    fun noteFirstHeader() {
        if (wifiEnded) return
        if (!firstHeaderDone.compareAndSet(false, true)) return
        noted(firstHeaderSeen = true, contentForwarded = false)
    }

    /** Relay hops: after each `END_TO_END_CONTENT` written to this hop. Cheap and idempotent; never activates. */
    fun noteContentForwarded() {
        if (wifiEnded) return
        if (!contentForwardedDone.compareAndSet(false, true)) return
        noted(firstHeaderSeen = false, contentForwarded = true)
    }

    private fun noted(firstHeaderSeen: Boolean, contentForwarded: Boolean) {
        if (!plan.shouldSendOwn(firstHeaderSeen, contentForwarded, plan.peer != null)) return
        if (wifiEnded) return
        sendOwnCapsRaw()
        val tuple = plan.attemptChoice(ownCaps) ?: return
        chosenBearer = tuple.bearer
        pendingActivation = true
    }

    private fun sendOwnCapsRaw() {
        // A7 review P3-7: the mode is fixed when the hop is built; a switch turned off since then answers all-clear, so
        // no attempt tuple exists on either side and neither activates.
        val caps = if (mode == HopWifiGate.Mode.ACTIVE && anyLocalSwitchOn()) {
            localCaps()
        } else {
            Bwf.Caps(bearers = 0, freeHost = 0, freeGuest = 0)
        }
        ownCaps = caps
        val bytes = caps.encode()
        synchronized(hopLock) {
            session.writer.write(SecureRecordType.BWF1, bytes)
            session.writer.flush()
        }
    }

    // -------------------------------------------------------------------------------------------- activation

    /**
     * Only the thread that calls [reader].read() may run this. Once per hop; never deactivated.
     *
     * A7 review P2-1: never after [close]. `close()` sets `closed` before it reads `carriers` and `agent`; this publishes
     * each of them and then re-checks `closed`, so one of the two always tears the published object down (once).
     * A7 review P3-6: an exception here never reaches the owner's read; before the set is published the hop stays raw,
     * after it the carrier-0 pump still starts, so the Bluetooth stream goes on.
     */
    private fun activate() {
        if (closed.get()) return
        if (!activated.compareAndSet(false, true)) return
        pendingActivation = false
        val set = try {
            buildCarrierSet()
        } catch (error: Exception) {
            internalError("carrier_set", error)
            return
        }
        val published = try {
            synchronized(hopLock) {
                if (closed.get()) {
                    false
                } else {
                    set.attachPrimary(
                        SessionCarrier(
                            CarrierKind.BLUETOOTH,
                            "hop",
                            session,
                            CarrierLivenessPolicy.BLUETOOTH,
                            closesLink = false,
                        ) { },
                        0,
                    )
                    set.setMaxCiphertextBytes(session.reader.maxCiphertextBytes)
                    carriers = set
                    true
                }
            }
        } catch (error: Exception) {
            internalError("attach", error)
            false
        }
        if (!published) {
            runCatching { set.close() }
            return
        }
        if (closed.get()) {
            closeCarriers(set)
            return
        }
        try {
            set.onCallActive()                          // A1: no switch earlier than 3 s after this
            val a = newAgent(PortsImpl())
            agent = a
            if (closed.get()) {
                a?.let(::endAgent)
                return
            }
            a?.onCapsExchanged(ownCaps, plan.peer!!)
            a?.onVideo(remote = true)                   // every activated hop counts as video-bearing for A5's 3 s rule
        } catch (error: Exception) {
            internalError("agent", error)
            agent?.let(::endAgent)                      // no Wi-Fi attempt on this hop; carrier 0 keeps it
        }
        startPump(set)                                  // last: the agent is ready for the first inbound BWF1 record
    }

    private fun internalError(stage: String, error: Exception) {
        runCatching {
            log(WifiLog.upgrade("internal_error", "scope" to "hop", "stage" to stage, "error" to error.javaClass.simpleName))
        }
    }

    private fun endAgent(a: WifiHopAgent) {
        if (agentEnded.compareAndSet(false, true)) runCatching { a.onCallEnded() }
    }

    private fun closeCarriers(set: CarrierSet) {
        if (carriersClosed.compareAndSet(false, true)) runCatching { set.close() }
    }

    private fun buildCarrierSet(): CarrierSet =
        CarrierSet(
            cutover = CarrierCutover(),
            markers = WifiMarkerCodec(tickets),
            host = HostImpl(),
            threadPrefix = "hop-$id",
            hopLabel = if (role == HopCapsPlan.HopRole.STREAM_PARENT || role == HopCapsPlan.HopRole.STREAM_CHILD) "stream" else "relay",
            queueCapacityRecords = { maxOf(64, (owner.targetKbps() ?: 0) * 1000 / 8 * 2 / 1200) },
            flushIntervalNs = { 0L },                   // deliberate: hop writers flush explicitly (Sink.flush -> requestFlush)
            nanoClock = nanoClock,
            contentTypes = governedTypes,
            // A7 review P3-5: an unreadable sequence is A3's "invalid content" (inline: handed on as is), never an NPE.
            sequenceOf = { type, bytes ->
                readableSequence(type, bytes) ?: throw IllegalArgumentException("Invalid hop record sequence")
            },
            passTypes = HopRecordTypes.PASS,
        )

    private fun startPump(set: CarrierSet) {
        pump = thread(name = "hop-$id-pump", isDaemon = true) {
            try {
                while (!closed.get()) set.onInbound(0, 0, session.reader.read())
            } catch (error: Exception) {
                if (!closed.get()) set.carrierDied(0, "read failed: ${error.javaClass.simpleName}")
            }
        }
    }

    /** The link.reader of the carrier media currently arrives on. */
    fun measureReader(): SecureRecordReader =
        carriers?.let { it.linkOf(it.acceptCarrier)?.reader } ?: session.reader

    /** `ROUTE_CONTROL` / `ROUTE_CONTROL_ACK`: ungoverned, on the current send carrier. */
    fun sendHopControl(type: SecureRecordType, payload: ByteArray) {
        val set = synchronized(hopLock) {
            if (carriers == null) {
                session.writer.write(type, payload)
                return
            }
            carriers
        }
        set?.sendHopToSender(type, payload)
    }

    /** The user turned Wi-Fi off: A5's agent plans the release. */
    fun onSwitchOff() {
        agent?.onSwitchOff()
    }

    /** The hop's tuple is Direct, or a live carrier is WIFI_DIRECT. */
    fun usesDirect(): Boolean =
        chosenBearer == WifiBearer.DIRECT || carriers?.snapshot()?.kinds?.containsValue(CarrierKind.WIFI_DIRECT) == true

    // -------------------------------------------------------------------------------------------- ports

    private inner class PortsImpl : WifiHopPorts {
        override val isLinkInitiator: Boolean get() = plan.localIsInitiator

        override val hopKind: WifiHopKind
            get() = if (role == HopCapsPlan.HopRole.STREAM_PARENT || role == HopCapsPlan.HopRole.STREAM_CHILD) {
                WifiHopKind.STREAM
            } else {
                WifiHopKind.RELAY
            }

        override val tickets: CarrierTickets get() = this@HopLink.tickets

        override fun optedIn(): Boolean = session.reader.acceptWifi

        override fun callActive(): Boolean = true

        override fun revealsDone(): Boolean = true

        override fun renewalRunning(): Boolean = false

        override fun hasWifiCarrier(): Boolean = carriers?.hasWifiCarrier() ?: false

        override fun fallbackCount(): Int = carriers?.fallbackCount ?: 0

        override fun sendRecord(record: Bwf): Boolean {
            val bytes = record.encode()
            synchronized(hopLock) {
                if (carriers == null) {
                    session.writer.write(SecureRecordType.BWF1, bytes)
                    session.writer.flush()
                    return true
                }
            }
            return carriers?.sendHop(0, SecureRecordType.BWF1, bytes) ?: false
        }

        // A7 review P3-4: after close the key is zeroed; null, never a copy of zeros.
        override fun uwCopy(): ByteArray? =
            if (closed.get()) null else wifiKeyFor(true, null, session.hopWifiKey)?.copyOf()

        override fun offerCarrier(carrier: WifiSocketCarrier): Int {
            val set = carriers ?: return -1
            val id = set.addCarrier(carrier)
            if (id < 0) return -1
            tickets.register(id, carrier.ticket)
            set.carrierPending(id)
            set.carrierUp(id)
            return id
        }

        override fun carrierLink(reader: SecureRecordReader, writer: SecureRecordWriter): SecureBluetoothSession =
            session.copy(reader = reader, writer = writer, hopWifiKey = null)

        override fun carrierDied(id: Int, reason: String) {
            carriers?.carrierDied(id, reason)
        }

        override fun localReady(): Bwf.Ready = owner.wifiReady()

        override fun protocolViolation(carrierId: Int, reason: String) {
            WifiLog.emit("bwf1_unexpected: carrier=$carrierId | reason=$reason")
            if (carrierId != 0) carriers?.carrierDied(carrierId, "wifi_protocol_$reason")
        }

        override fun carrierSwitchOff() {
            carriers?.switchOff()
        }

        override fun carrierSample(id: Int): CarrierSample? {
            val snapshot = carriers?.snapshot() ?: return null
            val stats = snapshot.stats[id] ?: return null
            return CarrierSample(
                isSendCarrier = snapshot.sendCarrier == id,
                bytesWritten = stats.bytesWritten,
                queuedContent = stats.queuedContent,
                oldestQueuedAgeMs = stats.oldestQueuedAgeMs,
            )
        }

        override fun bluetoothBudgetKbps(): Int? = owner.bluetoothBudgetKbps()

        override fun requestedMediaKbps(): Int? = owner.targetKbps()

        // AP10: `otherBluetoothMedia()` keeps its default, true. A stream or relay hop could count the other hops of
        // this phone (HopLinks) but cannot see whether the phone is in a call, so it keeps the 5 GHz rule.

        override fun qualityFallback() {
            carriers?.qualityFallback()
        }
    }

    // -------------------------------------------------------------------------------------------- host

    private inner class HostImpl : CarrierSet.Host {
        override fun heard(carrierId: Int, generation: Int): Boolean = true

        override fun onHeartbeat(carrierId: Int, generation: Int, record: SecureRecord) {
            // The parent (and a relay) handle a child's heartbeats; a stream child's reader would reject one.
            if (role != HopCapsPlan.HopRole.STREAM_CHILD) offer(record)
        }

        override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) = Unit

        override fun deliverRecord(carrierId: Int, generation: Int, type: SecureRecordType, payload: ByteArray) {
            offer(SecureRecord(type, payload))
        }

        override fun onHopRecord(carrierId: Int, record: SecureRecord) {
            offer(record)
        }

        override fun onWifiRecord(carrierId: Int, payload: ByteArray) {
            agent?.onRecord(carrierId, payload)
        }

        override fun onCutoverAction(action: CarrierCutover.Action) {
            when (action) {
                CarrierCutover.Action.ForceLocalKeyframe -> owner.localKeyframeNeeded()
                CarrierCutover.Action.RequestPeerKeyframe -> owner.peerKeyframeNeeded()
                CarrierCutover.Action.ReassertState -> owner.reassertState()
                CarrierCutover.Action.ResetSendFragments,
                CarrierCutover.Action.SuppressFragments,
                -> Unit                                       // stream and relay hops have no fragments
                else -> Unit
            }
        }

        override fun onSendCarrierChanged(sendOn: Int, kind: CarrierKind?, reason: String) {
            if (reason == "attach") return
            owner.sendCarrierChanged(kind ?: CarrierKind.BLUETOOTH)
            val requested = DebugTunables.wifiForceCarrier.ifEmpty {
                if (WifiSwitchHub.current().carrier) "aware" else "bluetooth"
            }
            val effective = when (kind) {
                CarrierKind.WIFI_AWARE -> "aware"
                CarrierKind.WIFI_DIRECT -> "direct"
                else -> "bluetooth"
            }
            val why = when {
                kind == null -> "none"
                kind.isWifi -> "switch"
                else -> "fallback"
            }
            log(WifiLog.carrier(hopLog, requested, effective, why))
        }

        override fun onEndSession(reason: String) {
            ended = ended ?: Ended(reason, null)
            if (endReported.compareAndSet(false, true)) {
                thread(name = "hop-$id-end", isDaemon = true) { owner.hopEnded(reason) }
            }
        }

        override fun heartbeatPayload(carrierId: Int): ByteArray? = null

        override fun bluetoothSilent(carrierId: Int): Boolean = false

        override fun onProgress(carrierId: Int, nowNs: Long) = Unit

        override fun onProtocolError(carrierId: Int, message: String) {
            log("bwf1_unexpected: carrier=$carrierId | reason=$message")
            carriers?.carrierDied(carrierId, message)
        }

        override fun onConsumerError(error: Exception) {
            ended = ended ?: Ended(error.message ?: "consumer", error)
        }

        override fun requeueUndelivered(cookies: List<Any>) = Unit

        override fun atFrameBoundary(): Boolean = true

        override fun renewalBusy(): Boolean = false

        override fun log(line: String) {
            this@HopLink.log(line.removePrefix("BENCHMARK "))
        }

        private fun offer(record: SecureRecord) {
            // Back-pressure into A3's consumer; never a dead block after close.
            while (!closed.get() && !delivered.offer(record, 100, TimeUnit.MILLISECONDS)) { }
        }
    }

    // -------------------------------------------------------------------------------------------- close

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        HopLinks.unregister(this)
        // `closed` is set first: an activation publishing concurrently re-checks it and tears down what close() missed.
        agent?.let(::endAgent)
        carriers?.let(::closeCarriers)
        ended = ended ?: Ended("closed", null)
        pump?.interrupt()
        runCatching { session.dropWifiKey() }
        // A7 review P1-1: the raw link closes before the session wipes its keys, so a write racing close fails on the
        // closed link instead of reaching the air (and the writer refuses to seal after close in any case).
        runCatching { closeRaw() }
        runCatching { session.close() }
    }
}
