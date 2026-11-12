package com.example.bluetoothtest

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Marker bytes (`SWITCH`, `RESUME`) as they travel inside `BWF1` records. A5 supplies the real codec; tests a trivial one. */
internal interface CarrierMarkerCodec {
    /** Only [CarrierCutover.Wire.Switch] and [CarrierCutover.Wire.Resume] are ever passed. */
    fun encode(wire: CarrierCutover.Wire): ByteArray

    /** The marker in a `BWF1` payload, or null when the payload is another kind (CAPS, HOST, ...). Malformed: throws IllegalArgumentException. */
    fun decode(payload: ByteArray): CarrierCutover.Wire?
}

/** Payloads of content records between their reader thread and the single consumer (F-8). Thread-safe. */
internal class PayloadStore {
    private val nextTag = AtomicInteger(0)
    private val map = ConcurrentHashMap<Int, Pair<SecureRecordType, ByteArray>>()

    /** Called by a reader before it enqueues the item; returns a tag no other reader received. */
    fun put(type: SecureRecordType, payload: ByteArray): Int {
        val tag = nextTag.incrementAndGet()
        map[tag] = type to payload
        return tag
    }

    /** Called only by the single consumer (or the inline reader) for a Deliver or a Drop. */
    fun take(tag: Int): Pair<SecureRecordType, ByteArray>? = map.remove(tag)

    fun clear() = map.clear()
}

/** Immutable per-carrier measurements (A5's quality rules and the logs read them). */
internal data class CarrierStats(
    val kind: CarrierKind,
    val lastHeardNs: Long,
    val lastProgressNs: Long,
    val writeStartedNs: Long,
    val queuedContent: Int,
    val bytesWritten: Long,
    val recordsWritten: Long,
    val bytesRead: Long,
    val recordsRead: Long,
    /** F-9: the age of the oldest content record this carrier still has to send (0 = none, and always 0 inline). */
    val oldestQueuedAgeMs: Long = 0L,
)

/** One consistent view of a [CarrierSet] (`CarrierSnapshot.EMPTY` when a call has no set). */
internal data class CarrierSnapshot(
    val callActive: Boolean,
    val sendCarrier: Int,
    val acceptCarrier: Int,
    val liveCount: Int,
    val kinds: Map<Int, CarrierKind>,
    val stats: Map<Int, CarrierStats>,
    val fallbackCount: Int,
    val heldCount: Int,
    val switchedOff: Boolean,
    val ended: Boolean,
    val endDeferred: Boolean,
) {
    val sendKind: CarrierKind get() = kinds[sendCarrier] ?: CarrierKind.BLUETOOTH
    val acceptKind: CarrierKind get() = kinds[acceptCarrier] ?: CarrierKind.BLUETOOTH
    val hasWifiCarrier: Boolean get() = kinds.values.any { it.isWifi }
    val bluetoothAlive: Boolean get() = kinds.values.any { !it.isWifi }

    companion object {
        val EMPTY = CarrierSnapshot(false, -1, -1, 0, emptyMap(), emptyMap(), 0, 0, false, false, false)
    }
}

/**
 * One hop's carriers (Design WF, package A3): carrier 0 and at most one more, one writer and one reader thread per carrier
 * and one consumer. Inline mode (no second carrier ever added) is exactly today's call: one writer thread, one reader
 * thread, no set thread at all.
 *
 * Locks and threads:
 * - `cutoverLock` guards the [CarrierCutover] instance (both directions), the runtime table, `fanout` flips, the sealed
 *   bookkeeping and a held end. Every machine call and the enqueueing of every `Write` it returns run inside one
 *   `synchronized(cutoverLock)` block. Nothing that blocks runs under it: no host callback, no `Carrier.close`, no thread
 *   start or join, no blocking queue operation. Such work is collected and run after release ([runDeferred]).
 * - `recvLock` serialises the receive path ([consume]): the carrier-0 reader thread inline, or the consumer thread. Order:
 *   `recvLock` then `cutoverLock`, never the reverse. `Host.deliverRecord` runs under `recvLock` only.
 * - Threads (created only after fan-out starts): `"$threadPrefix-w<id>"` (per carrier), `"$threadPrefix-r<id>"` (an added
 *   carrier's reader), `"$threadPrefix-in"` (consumer), `"$threadPrefix-mon"` (monitor, daemon).
 */
internal class CarrierSet(
    private val cutover: CarrierCutover,
    private val markers: CarrierMarkerCodec,
    private val host: Host,
    private val threadPrefix: String,
    /** Capacity of a carrier's content queue in records, read when its runtime is created (3.4). */
    private val queueCapacityRecords: () -> Int = { 64 },
    /** Content capacity of a carrier whose kind is not Wi-Fi (amendment 12): small, so the producer blocks on a congested Bluetooth carrier as it does inline. */
    private val bluetoothQueueRecords: Int = BLUETOOTH_QUEUE_RECORDS,
    /** Content flush interval in ns for a carrier kind (0 = after every record); read by a carrier writer at start and when the send carrier changes. */
    private val flushIntervalNs: (CarrierKind) -> Long = { 0L },
    private val nanoClock: () -> Long = System::nanoTime,
    /** Record types that [sink] accepts and the cutover orders by sequence. A call: end-to-end content only; a stream or relay hop adds `MESH_ENVELOPE` (A7). [X7] [GAP+] */
    private val contentTypes: Set<SecureRecordType> = setOf(SecureRecordType.END_TO_END_CONTENT),
    /** The sequence number of one content record of a type in [contentTypes] (cleartext header only). [X7] [GAP+] */
    private val sequenceOf: (SecureRecordType, ByteArray) -> Long = { _, bytes ->
        EndToEndContentProtocol.inspect(bytes, true, true, true, true).sequence
    },
    /** Inbound record types handed to [Host.onHopRecord] in arrival order with the content (the hop control records A7 forwards). [X7] [GAP+] */
    private val passTypes: Set<SecureRecordType> = emptySet(),
    /** DEBUG `switch_burned` log lines: which hop this set carries (`call`, `stream` or `relay`). */
    private val hopLabel: String = "unlabelled",
    /** `test_cutover_keep_fifo_slot` ([CutoverSwitches.keepFifoSlot], default off): a sealed record is queued on its carrier at once, never rerouted past its SWITCH boundary. */
    private val keepFifoSlot: () -> Boolean = { CutoverSwitches.keepFifoSlot },
) : java.io.Closeable {

    companion object {
        /** A carrier-death EndSession is held at most this long while a renewal runs (the renewal's own bound). [R A1-5] [GAP+] */
        const val RENEWAL_END_DEFER_MS = 12_000L

        /** How long a deferral waits after Host.renewalBusy() turned false, so that replacePrimary can still arrive first. [R A1-5] [GAP+] */
        const val RENEWAL_END_GRACE_MS = 500L

        /** Carrier ids 1..62, never reused (the machine's deadMask has one bit per id). [GAP+] */
        const val MAX_CARRIER_ID = 62

        /**
         * The liveness verdict of one Wi-Fi carrier from ONE snapshot of its fields: "silence" when no authenticated record
         * arrived for the policy's bound, "write stalled" when a write that started at [writeStartedNs] (0 = no write in
         * progress) has lasted the policy's bound, else null. A Bluetooth policy (no silence bound) never gets a verdict.
         */
        internal fun livenessVerdict(nowNs: Long, lastHeardNs: Long, writeStartedNs: Long, policy: CarrierLivenessPolicy): String? {
            if (policy.deadAfterSilenceNs <= 0) return null
            if (nowNs - lastHeardNs >= policy.deadAfterSilenceNs) return "silence"
            if (policy.deadAfterStalledWriteNs > 0 && writeStartedNs != 0L &&
                nowNs - writeStartedNs >= policy.deadAfterStalledWriteNs
            ) {
                return "write stalled"
            }
            return null
        }

        /** Content capacity of a Bluetooth carrier in fan-out (amendment 12). */
        const val BLUETOOTH_QUEUE_RECORDS = 2

        /** Carrier 0 is fresh while it delivered an authenticated record within this time (Stage A's soft-stall threshold; amendment 24). */
        const val STANDBY_FRESH_MS = 3_000L
    }

    /** Everything the call (or a test) supplies. Callbacks never run under [cutoverLock]. */
    interface Host {
        /** Reader thread, once per record before anything else. false: the record belongs to a replaced generation; the reader ends. */
        fun heard(carrierId: Int, generation: Int): Boolean

        /** `LINK_HEARTBEAT` from carrier [carrierId], read under link [generation]. Reader thread. */
        fun onHeartbeat(carrierId: Int, generation: Int, record: SecureRecord)

        /** Open and dispatch one `BME1` record that the cutover delivered. Called under no CarrierSet lock; the call takes its recordLock here. */
        fun deliver(carrierId: Int, generation: Int, payload: ByteArray)

        /** Same as [deliver] for a record of any type in `contentTypes`; the default forwards to [deliver]. Under recvLock only. [X7] [GAP+] */
        fun deliverRecord(carrierId: Int, generation: Int, type: SecureRecordType, payload: ByteArray) {
            deliver(carrierId, generation, payload)
        }

        /** A record of a type in `passTypes`, in arrival order on the consumer path; never reaches the machine. Default: a protocol error. [X7] [GAP+] */
        fun onHopRecord(carrierId: Int, record: SecureRecord) {
            onProtocolError(carrierId, "Unexpected private-call record ${record.type}")
        }

        /** A `BWF1` record that is not `SWITCH`/`RESUME`, and one the codec could not decode. Never a protocol violation of the set. [API] */
        fun onWifiRecord(carrierId: Int, payload: ByteArray)

        /** `ReassertState`, `ForceLocalKeyframe`, `RequestPeerKeyframe`, `ResetSendFragments`, `SuppressFragments`. */
        fun onCutoverAction(action: CarrierCutover.Action)

        /** The carrier that sends media changed (also at attach: id 0). [kind] null = no carrier. */
        fun onSendCarrierChanged(sendOn: Int, kind: CarrierKind?, reason: String)

        /** The cutover returned EndSession (no carrier left). Exactly once per set. [R A1-5] */
        fun onEndSession(reason: String)

        /** Heartbeat payload for carrier [carrierId]'s writer (a capacity report), or null for the plain version-1 liveness record. */
        fun heartbeatPayload(carrierId: Int): ByteArray?

        /** The host's liveness verdict for the Bluetooth carrier (its `CallStallTolerance` rule); Wi-Fi carriers use their own policy. */
        fun bluetoothSilent(carrierId: Int): Boolean

        /** A fan-out writer wrote and flushed up to now (amendment 8). */
        fun onProgress(carrierId: Int, nowNs: Long)

        /** A record the hop must not carry arrived. Throwing is allowed. */
        fun onProtocolError(carrierId: Int, message: String)

        /** An exception on the consumer thread (amendment 8). */
        fun onConsumerError(error: Exception)

        /** Records queued on a carrier that died or was replaced, in order. */
        fun requeueUndelivered(cookies: List<Any>)

        /** True when the next sealed record starts a frame (no fragmented frame is half written). */
        fun atFrameBoundary(): Boolean

        /** An in-call renewal is running (the call's renewalBusy()). Read only outside cutoverLock/recvLock. [R A1-5] [GAP+] */
        fun renewalBusy(): Boolean

        /** A carrier joined, left, was replaced or the accepting carrier changed. Deferred, never under cutoverLock. [GAP+] */
        fun onCarrierEvent(event: CarrierEvent, carrierId: Int, kind: CarrierKind?, reason: String) {}

        fun log(line: String)
    }

    /** The marker bytes the machine asked for, queued on its carrier. */
    private sealed class Out {
        class Content(
            val type: SecureRecordType,
            val bytes: ByteArray,
            val cookie: Any?,
            val onWritten: ((Int, Long) -> Unit)?,
            val queuedNs: Long,
        ) : Out()

        /** Markers and BWF1 controls; [control] = it travels the carrier's control lane, not its media FIFO. */
        class Hop(val type: SecureRecordType, val payload: ByteArray, val control: Boolean) : Out()
    }

    /** One received item; every item carries the runtime generation it was read or reported under (amendment 6). */
    private sealed class Item {
        abstract val id: Int
        abstract val generation: Int

        class Content(override val id: Int, override val generation: Int, val rec: CarrierCutover.Rec) : Item()
        class Marker(override val id: Int, override val generation: Int, val wire: CarrierCutover.Wire) : Item()
        class Hop(override val id: Int, override val generation: Int, val record: SecureRecord) : Item()
        class Up(override val id: Int, override val generation: Int) : Item()
        class Died(override val id: Int, override val generation: Int, val reason: String, val busy: Boolean) : Item()
    }

    /** A carrier-death `EndSession` held while a renewal runs (step 5). */
    private class HeldEnd(val id: Int, val reason: String, val heldAtNs: Long, val deadlineNs: Long) {
        var busyFalseSinceNs = 0L
    }

    /** One carrier of this hop: its queues, its counters and the instance's generation. */
    private inner class Runtime(
        val id: Int,
        val carrier: Carrier,
        val generation: Int,
        createdNs: Long,
    ) {
        /** The `sendHop` control lane: never blocks, served before media. */
        val control = ConcurrentLinkedQueue<Out.Hop>()

        /** Content and the machine's markers, FIFO. */
        val media = LinkedBlockingDeque<Out>()

        val contentCount = AtomicInteger(0)
        val dead = AtomicBoolean(false)
        val capacity: Int = if (carrier.kind.isWifi) maxOf(1, queueCapacityRecords()) else maxOf(1, bluetoothQueueRecords)
        val bytesWritten = AtomicLong()
        val recordsWritten = AtomicLong()
        val bytesRead = AtomicLong()
        val recordsRead = AtomicLong()

        @Volatile var lastHeardNs: Long = createdNs
        @Volatile var lastProgressNs: Long = 0L
        @Volatile var writeStartedNs: Long = 0L
        @Volatile var flushRequested: Boolean = false

        fun tryOfferContent(item: Out.Content): Boolean {
            while (true) {
                val count = contentCount.get()
                if (count >= capacity) return false
                if (contentCount.compareAndSet(count, count + 1)) break
            }
            media.offerLast(item)
            return true
        }

        /**
         * Queues a record that was already assigned to this carrier even when the queue is full (one over the capacity per
         * blocked producer); the producer then waits in [awaitRoom]. Keeps the record before any SWITCH marker queued later.
         */
        fun offerContentOverCapacity(item: Out.Content) {
            contentCount.incrementAndGet()
            media.offerLast(item)
        }

        /** Producer back-pressure after [offerContentOverCapacity]: until the queue is back at its capacity, or the carrier or set ends. */
        fun awaitRoom(closedFlag: AtomicBoolean) {
            while (contentCount.get() > capacity && !dead.get() && !closedFlag.get()) awaitSpace(5)
        }

        fun awaitSpace(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        fun stats(now: Long): CarrierStats {
            val writeStarted = writeStartedNs // one read: the writer clears it without a lock
            return CarrierStats(
            kind = carrier.kind,
            lastHeardNs = lastHeardNs,
            lastProgressNs = lastProgressNs,
            writeStartedNs = writeStarted,
            queuedContent = if (fanout) contentCount.get() else 0,
            bytesWritten = bytesWritten.get(),
            recordsWritten = recordsWritten.get(),
            bytesRead = bytesRead.get(),
            recordsRead = recordsRead.get(),
            oldestQueuedAgeMs = ageOfOldestContent(now, if (fanout) media else null, writeStarted),
            )
        }
    }

    private val cutoverLock = Any()
    private val recvLock = Any()
    private val closed = AtomicBoolean(false)
    private val hostEndReported = AtomicBoolean(false)
    private val nextId = AtomicInteger(1)
    private val runtimes = LinkedHashMap<Int, Runtime>()
    private val payloadStore = PayloadStore()
    private val threads = ArrayList<Thread>()
    private val inbound = java.util.concurrent.LinkedBlockingQueue<Item>(512)

    /** Carrier events, unbounded and non-blocking: up/died enter here so a busy consumer never blocks a reader (amendment 5). */
    private val controlEvents = ConcurrentLinkedQueue<Item>()

    private val pendingCookie = ThreadLocal<Pending>()
    private val inlineInFlight = AtomicInteger(0)

    @Volatile private var fanoutFlag = false
    @Volatile private var callActiveFlag = false
    @Volatile private var switchedOffFlag = false
    @Volatile private var heldEnd: HeldEnd? = null
    @Volatile private var maxCiphertextBytes: Int = Int.MAX_VALUE
    @Volatile private var reportedSendOn: Int = CarrierCutover.NONE
    @Volatile private var reportedAccept: Int = CarrierCutover.NONE
    @Volatile private var standbyFreshReported: Boolean = true

    /**
     * Send-carrier and accepting-carrier reports, queued under [cutoverLock] in the order the changes happened and run by
     * [deliverReports] outside every lock. A change computed on one thread (the consumer, the monitor, a caller) used to
     * be reported from that thread's own deferred list, so a thread paused after it left the lock could report an older
     * carrier after a newer one, leaving the host (call label, sender pacer seed) on a carrier it no longer sent on.
     */
    private val reports = ConcurrentLinkedQueue<() -> Unit>()
    private val reportsDelivering = AtomicBoolean(false)

    /** Guarded by [cutoverLock]; fed by `cutover.onStat` (amendment 3). */
    private var ignoredHints = 0

    /**
     * DEBUG measurement of a peer SWITCH that arrives before this side knows its carrier (the codec decodes an unknown
     * ticket to the burned carrier, or the attempt is not pending yet): the cutover drops it, and the early content of the
     * new carrier is then held for up to the hold ceiling and promoted. Guarded by [cutoverLock]; the lines are queued here
     * and logged by [flushHintLog].
     */
    private var lastOfferMs = -1L
    private var burnedSwitches = 0
    private var burnAwaitingHold = false
    private var burnAtMs = -1L
    private var burnSummaryLogged = false

    /** DEBUG, after the first burned SWITCH: what happened to the records read on any carrier but 0 (guarded by [cutoverLock]). */
    private var burnRead = 0
    private var burnHeld = 0
    private var burnReleased = 0
    private var burnDelivered = 0
    private var burnDropped = 0
    private var markerArrivedOn = CarrierCutover.NONE
    private val burnLines = ArrayList<String>()

    /** One cookie/onWritten pair for the calling thread's next [sink] write (amendment 20 e). */
    private class Pending(val cookie: Any?, val onWritten: ((Int, Long) -> Unit)?)

    init {
        cutover.onStat = { key ->
            when (key) {
                "old-dead-hint" -> ignoredHints += 1
                "switch-burned" -> noteSwitchBurned()
                "hold-ended" -> noteHoldAfterBurn()
            }
        }
    }

    /** The router for sealed `BME1` records: pass it wherever a [RecordSink] writer was passed. Thread-safe. */
    val sink: RecordSink = object : RecordSink {
        override fun write(type: SecureRecordType, payload: ByteArray): Int = sendContent(type, payload)
    }

    val fanout: Boolean get() = fanoutFlag
    val callActive: Boolean get() = callActiveFlag
    val switchedOff: Boolean get() = switchedOffFlag
    val endDeferred: Boolean get() = heldEnd != null
    val fallbackCount: Int get() = synchronized(cutoverLock) { cutover.fallbackCount }
    val heldCount: Int get() = synchronized(cutoverLock) { cutover.heldCount }

    val ended: Boolean
        get() = synchronized(cutoverLock) { hostEndReported.get() || cutover.ended && heldEnd == null }

    val sendCarrier: Int get() = synchronized(cutoverLock) { cutover.sendOn }
    val acceptCarrier: Int get() = synchronized(cutoverLock) { cutover.accept }
    val sendKind: CarrierKind get() = kindOf(sendCarrier) ?: CarrierKind.BLUETOOTH
    val acceptKind: CarrierKind get() = kindOf(acceptCarrier) ?: CarrierKind.BLUETOOTH

    fun kindOf(id: Int): CarrierKind? = synchronized(cutoverLock) {
        runtimes[id]?.takeIf { !it.dead.get() }?.carrier?.kind
    }

    fun isLive(id: Int): Boolean = synchronized(cutoverLock) {
        runtimes[id]?.let { !it.dead.get() } == true
    }

    fun liveCount(): Int = synchronized(cutoverLock) { runtimes.values.count { !it.dead.get() } }

    fun hasWifiCarrier(): Boolean = synchronized(cutoverLock) {
        runtimes.values.any { !it.dead.get() && it.carrier.kind.isWifi }
    }

    fun linkOf(id: Int): SecureBluetoothSession? = synchronized(cutoverLock) {
        runtimes[id]?.takeIf { !it.dead.get() }?.carrier?.link
    }

    fun stats(id: Int): CarrierStats? = synchronized(cutoverLock) {
        runtimes[id]?.takeIf { !it.dead.get() }?.stats(nanoClock())
    }

    fun snapshot(): CarrierSnapshot = synchronized(cutoverLock) {
        val now = nanoClock()
        val live = runtimes.values.filter { !it.dead.get() }
        CarrierSnapshot(
            callActive = callActiveFlag,
            sendCarrier = cutover.sendOn,
            acceptCarrier = cutover.accept,
            liveCount = live.size,
            kinds = live.associate { it.id to it.carrier.kind },
            stats = live.associate { it.id to it.stats(now) },
            fallbackCount = cutover.fallbackCount,
            heldCount = cutover.heldCount,
            switchedOff = switchedOffFlag,
            ended = hostEndReported.get() || cutover.ended && heldEnd == null,
            endDeferred = heldEnd != null,
        )
    }

    fun <T> withCookie(
        cookie: Any?,
        onWritten: ((carrierId: Int, nowNs: Long) -> Unit)? = null,
        block: () -> T,
    ): T {
        pendingCookie.set(Pending(cookie, onWritten))
        try {
            return block()
        } finally {
            pendingCookie.remove()
        }
    }

    fun attachPrimary(carrier: Carrier, generation: Int) {
        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            val existing = runtimes[0]
            if (existing == null || existing.dead.get()) {
                runtimes[0] = Runtime(0, carrier, generation, nanoClock())
            }
            reportedSendOn = cutover.sendOn
            reportedAccept = cutover.accept
            val sendOn = cutover.sendOn
            queueReportLocked(deferred) { host.onSendCarrierChanged(sendOn, carrier.kind, "attach") }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
    }

    fun addCarrier(carrier: Carrier): Int {
        var id = -1
        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            val secondAlive = runtimes.values.any { it.id != 0 && !it.dead.get() }
            if (!closed.get() && !cutover.ended && !switchedOffFlag && !secondAlive) {
                val candidate = nextId.get()
                if (candidate in 1..MAX_CARRIER_ID) {
                    nextId.incrementAndGet()
                    val rt = Runtime(candidate, carrier, 0, nanoClock())
                    rt.carrier.link.reader.maxCiphertextBytes = maxCiphertextBytes
                    runtimes[candidate] = rt
                    if (!fanoutFlag) startFanout(deferred)
                    deferred += { startWriter(candidate) }
                    deferred += { host.onCarrierEvent(CarrierEvent.ADDED, candidate, carrier.kind, "added") }
                    deferred += { host.log("carrier_added kind=${carrier.kind.name}") }
                    id = candidate
                }
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
        return id
    }

    /** Called under [cutoverLock]; the caller runs the deferred thread starts and the ADDED event after release. */
    private fun startFanout(deferred: MutableList<() -> Unit>) {
        if (fanoutFlag || runtimes[0] == null) return
        fanoutFlag = true
        deferred += { startConsumer() }
        deferred += { startWriter(0) }
        deferred += { startMonitor() }
    }

    fun carrierPending(id: Int) {
        val deferred = ArrayList<() -> Unit>()
        var ignored: Runtime? = null
        synchronized(cutoverLock) {
            val rt = runtimes[id]
            if (!closed.get() && !cutover.ended && rt != null && !rt.dead.get()) {
                lastOfferMs = nowMs()
                for (action in cutover.onCarrierPending(id, nowMs())) perform(action, deferred)
                if (cutover.pendingCarrier != id && cutover.newCarrier != id) ignored = rt
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
        ignored?.let { closeIgnoredCarrier(it) }
    }

    fun carrierUp(id: Int) {
        val rt = synchronized(cutoverLock) { runtimes[id] }
        if (rt == null || rt.dead.get()) return
        if (fanoutFlag) {
            controlEvents.offer(Item.Up(id, rt.generation))
            return
        }
        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            if (!closed.get() && !cutover.ended) {
                for (action in cutover.onCarrierUp(id, nowMs(), oldQueueAgeMs(), host.atFrameBoundary())) {
                    perform(action, deferred)
                }
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
    }

    fun onCallActive() {
        callActiveFlag = true
        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            if (!cutover.ended) {
                for (action in cutover.onCallActive(nowMs(), oldQueueAgeMs(), host.atFrameBoundary())) {
                    perform(action, deferred)
                }
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
    }

    fun onHopActive() = onCallActive()

    /**
     * A carrier failed or was closed, as seen by this phone. Idempotent, any thread; false for a stale generation.
     * Returns true when no live carrier is left and the session is ending now.
     *
     * [fromReader]: the caller is that carrier's own reader thread reporting its own end (EOF, read error). In fan-out
     * the death then queues behind the records the reader already queued, so a `HANG_UP` read just before the EOF is
     * still delivered (review A3 P1-3; a plan correction of amendment 5). Every other caller uses the control lane.
     */
    fun carrierDied(id: Int, reason: String, generation: Int = -1, fromReader: Boolean = false): Boolean {
        val rt = synchronized(cutoverLock) { runtimes[id] } ?: return liveCount() == 0 && !endDeferred
        if (generation != -1 && generation != rt.generation) return false
        // The death and the drain happen under the lock that sendContent checks `dead` and enqueues under, so no record
        // is queued on a carrier after its queue was drained (review A3 P2-2).
        val cookies = synchronized(cutoverLock) {
            if (!rt.dead.compareAndSet(false, true)) null else drainForDeath(rt)
        } ?: return liveCount() == 0 && !endDeferred
        val kind = rt.carrier.kind
        // Read once, before the lock (amendment 7): the hold is decided later, in consume(Died).
        val busy = host.renewalBusy()
        val deferred = ArrayList<() -> Unit>()
        deferred += { rt.carrier.close() }
        if (BuildConfig.DEBUG && kind.isWifi) {
            // Why a Wi-Fi carrier died, at the one place every death passes (the monitor's verdicts never reach the call's
            // own carrier_lost line). ageMs: since the write started for a stalled write, else since the last record heard.
            val logNs = nanoClock()
            val writeStarted = rt.writeStartedNs
            val sinceNs = if (reason == "write stalled" && writeStarted != 0L) logNs - writeStarted else logNs - rt.lastHeardNs
            val ageMs = (sinceNs / 1_000_000L).coerceAtLeast(0L)
            deferred += { host.log("BENCHMARK carrier_death: carrier=${kind.name} | reason=$reason | ageMs=$ageMs") }
        }
        if (cookies.isNotEmpty()) deferred += { host.requeueUndelivered(cookies) }
        deferred += { host.onCarrierEvent(CarrierEvent.ENDED, id, kind, reason) }
        if (fanoutFlag) {
            val died = Item.Died(id, rt.generation, reason, busy)
            if (fromReader) {
                try {
                    inbound.put(died) // blocking is back-pressure on the reader itself
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    controlEvents.offer(died)
                }
            } else {
                controlEvents.offer(died)
            }
            runDeferred(deferred)
            return liveCount() == 0 && !busy
        }
        var endNow = false
        synchronized(cutoverLock) {
            if (!cutover.ended) {
                for (action in cutover.onCarrierDied(id, nowMs())) {
                    if (action is CarrierCutover.Action.EndSession) endNow = true
                    perform(action, deferred)
                }
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
        return endNow || liveCount() == 0
    }

    /** Drops the dead carrier's queues and returns the cookies of its undelivered content, in queue order. */
    private fun drainForDeath(rt: Runtime): List<Any> {
        val cookies = ArrayList<Any>()
        val drained = ArrayList<Out>()
        rt.media.drainTo(drained)
        for (item in drained) {
            if (item is Out.Content) {
                rt.contentCount.decrementAndGet()
                item.cookie?.let(cookies::add)
            }
        }
        rt.control.clear()
        return cookies
    }

    /**
     * The user turned Wi-Fi off. Returns true when this switch-off left the call on Wi-Fi because the standby cannot take
     * it now (dead, or alive but not heard lately): the cutover's own outcome, from which the D6 notice is decided (review
     * A6 P2-1). False when the call moved back, nothing changed, or an earlier switch-off already decided it.
     */
    fun switchOff(): Boolean {
        val deferred = ArrayList<() -> Unit>()
        switchedOffFlag = true
        var keptOnWifi = false
        synchronized(cutoverLock) {
            if (!cutover.ended) {
                val waitingBefore = cutover.switchOffPendingNow
                for (action in cutover.onSwitchOff(nowMs())) perform(action, deferred)
                keptOnWifi = !waitingBefore && cutover.switchOffPendingNow
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
        return keptOnWifi
    }

    fun qualityFallback() {
        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            if (!cutover.ended) {
                for (action in cutover.onQualityFallback(nowMs())) perform(action, deferred)
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
    }

    /**
     * A renewal replaced carrier 0. Returns true when the machine re-asserts the call's state itself (fan-out: a
     * `ReassertState` queued behind the re-asserted marker); false in inline mode, where the caller re-asserts once,
     * exactly as before carriers (review A3 P1-1: one re-assert per renewal).
     */
    fun replacePrimary(carrier: Carrier, generation: Int): Boolean {
        val deferred = ArrayList<() -> Unit>()
        var machineReasserts = false
        synchronized(cutoverLock) {
            val old = runtimes[0]
            if (old != null && !old.dead.get()) {
                old.dead.set(true)
                val cookies = drainForDeath(old)
                if (cookies.isNotEmpty()) deferred += { host.requeueUndelivered(cookies) }
            }
            runtimes[0] = Runtime(0, carrier, generation, nanoClock()).also {
                it.carrier.link.reader.maxCiphertextBytes = maxCiphertextBytes
            }
            // Inline mode: the machine's only carrier is alive and fresh, so nothing it tracks changes; the caller re-asserts.
            if (!cutover.ended && fanoutFlag) {
                for (action in cutover.onStandbyReplaced(nowMs())) {
                    if (action == CarrierCutover.Action.ReassertState) machineReasserts = true
                    perform(action, deferred)
                }
            }
            heldEnd?.let { held ->
                heldEnd = null
                if (held.id != 0) {
                    for (action in cutover.onCarrierDied(held.id, nowMs())) perform(action, deferred)
                }
                deferred += { host.log("end_deferral_resolved result=replaced | ms=${heldAgeMs(held)}") }
            }
            if (fanoutFlag) deferred += { startWriter(0) }
            deferred += { host.onCarrierEvent(CarrierEvent.PRIMARY_REPLACED, 0, carrier.kind, "renewal") }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
        return machineReasserts
    }

    private fun heldAgeMs(held: HeldEnd): Long = ((nanoClock() - held.heldAtNs) / 1_000_000L).coerceAtLeast(0L)

    fun sendHop(id: Int, type: SecureRecordType, payload: ByteArray): Boolean {
        var inline = false
        var accepted = false
        synchronized(cutoverLock) {
            val rt = runtimes[id]
            if (!closed.get() && rt != null && !rt.dead.get()) {
                if (!fanoutFlag) {
                    inlineInFlight.incrementAndGet()
                    inline = true
                } else {
                    rt.control.offer(Out.Hop(type, payload, true))
                }
                accepted = true
            }
        }
        if (inline) {
            val rt = synchronized(cutoverLock) { runtimes[id] } ?: return false
            try {
                rt.carrier.link.writer.write(type, payload)
                rt.carrier.link.writer.flush()
            } finally {
                inlineInFlight.decrementAndGet()
            }
        }
        return accepted
    }

    fun sendHopToSender(type: SecureRecordType, payload: ByteArray): Boolean {
        val first = sendCarrier
        if (first != CarrierCutover.NONE && isLive(first) && sendHop(first, type, payload)) return true
        val candidates = synchronized(cutoverLock) {
            runtimes.values.filter { !it.dead.get() }.map { it.id }.sorted()
        }
        return candidates.any { it != first && sendHop(it, type, payload) }
    }

    fun requestFlush() {
        val inline = synchronized(cutoverLock) { runtimes[0]?.takeIf { !fanoutFlag && !it.dead.get() } }
        if (inline != null) {
            inline.carrier.link.writer.flush()
            return
        }
        synchronized(cutoverLock) {
            for (rt in runtimes.values) {
                if (!rt.dead.get()) rt.flushRequested = true
            }
        }
    }

    fun setMaxCiphertextBytes(n: Int) {
        maxCiphertextBytes = n
        synchronized(cutoverLock) {
            for (rt in runtimes.values) rt.carrier.link.reader.maxCiphertextBytes = n
        }
    }

    fun awaitDrained(id: Int, maxMs: Long): Boolean {
        val rt = synchronized(cutoverLock) { runtimes[id] } ?: return false
        if (!fanoutFlag) return true
        val deadline = System.currentTimeMillis() + maxMs
        while (true) {
            if (rt.contentCount.get() == 0 && rt.writeStartedNs == 0L) return true
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * The age of the oldest content record carrier 0 still has to send: the first queued record or the write in progress.
     * 0 when neither exists and always 0 in inline mode.
     */
    fun oldQueueAgeMs(): Long {
        if (!fanoutFlag) return 0L
        return synchronized(cutoverLock) {
            val rt = runtimes[0] ?: return 0L
            ageOfOldestContent(nanoClock(), rt.media, rt.writeStartedNs)
        }
    }

    /** The age of the oldest content record of one carrier (F-9; computed under [cutoverLock]). */
    private fun ageOfOldestContent(now: Long, queue: Collection<Out>?, writeStartedNs: Long): Long {
        var oldest = 0L
        if (queue != null) {
            for (item in queue) {
                if (item is Out.Content) {
                    oldest = ((now - item.queuedNs) / 1_000_000L).coerceAtLeast(0L)
                    break
                }
            }
        }
        if (writeStartedNs != 0L) {
            oldest = maxOf(oldest, ((now - writeStartedNs) / 1_000_000L).coerceAtLeast(0L))
        }
        return oldest
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val carriers = synchronized(cutoverLock) {
            for (rt in runtimes.values) rt.dead.set(true)
            runtimes.values.map { it.carrier }
        }
        for (carrier in carriers) runCatching { carrier.close() }
        val current = Thread.currentThread()
        synchronized(threads) {
            for (t in threads) if (t !== current) runCatching { t.interrupt() }
        }
        heldEnd = null
        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            noteBurnSummary(force = true)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
        payloadStore.clear()
    }

    // ---------------------------------------------------------------------------------------------- inbound

    /** One record read from a carrier's link (the call's carrier-0 reader, or a `-r<id>` thread of this set). */
    fun onInbound(id: Int, generation: Int, record: SecureRecord) {
        val rt = synchronized(cutoverLock) { runtimes[id] }
        if (rt == null || rt.dead.get() || rt.generation != generation) return
        val now = nanoClock()
        // Liveness never waits on recordLock or consumer lag (amendment 19).
        rt.lastHeardNs = now
        if (!host.heard(id, generation)) return
        rt.bytesRead.addAndGet((record.payload.size + SECURE_RECORD_OVERHEAD_BYTES).toLong())
        rt.recordsRead.incrementAndGet()
        when (record.type) {
            SecureRecordType.LINK_HEARTBEAT -> host.onHeartbeat(id, generation, record)
            SecureRecordType.BWF1 -> {
                val wire = try {
                    markers.decode(record.payload)
                } catch (_: RuntimeException) {
                    // A marker this codec cannot decode is the attempt owner's business, never a protocol violation (X5).
                    wifiRecord(id, record.payload)
                    return
                }
                if (wire == null) wifiRecord(id, record.payload) else ingest(Item.Marker(id, generation, wire))
            }
            in contentTypes -> {
                val seq = try {
                    sequenceOf(record.type, record.payload)
                } catch (error: RuntimeException) {
                    if (error !is IllegalArgumentException && error !is IllegalStateException) throw error
                    if (!fanoutFlag) {
                        // Inline mode hands the record to the opener, whose own error is today's text (amendment 21 ii).
                        host.deliverRecord(id, generation, record.type, record.payload)
                    } else {
                        host.onProtocolError(id, "Invalid end-to-end content")
                    }
                    return
                }
                val tag = payloadStore.put(record.type, record.payload)
                ingest(Item.Content(id, generation, CarrierCutover.Rec(seq, record.payload.size, tag)))
            }
            in passTypes -> ingest(Item.Hop(id, generation, record))
            else -> host.onProtocolError(id, "Unexpected private-call record ${record.type}")
        }
    }

    /**
     * A `BWF1` record for the attempt owner, on its reader thread. Its failure never ends the call (X5, amendment 22;
     * review A3 P2-4): on a Wi-Fi carrier that carrier is closed; on carrier 0 Wi-Fi ends for this call.
     */
    private fun wifiRecord(id: Int, payload: ByteArray) {
        try {
            host.onWifiRecord(id, payload)
        } catch (error: RuntimeException) {
            host.log("wifi_record_failed | carrier=$id | error=${error.javaClass.simpleName}")
            if (id != 0) carrierDied(id, "wifi_record_failed", fromReader = true) else switchOff()
        }
    }

    /** A record or event enters the receive path: directly on this thread in inline mode, else the consumer's queue. */
    private fun ingest(item: Item) {
        if (!fanoutFlag) {
            consume(item)
            return
        }
        inbound.put(item)
    }

    /** The single receive path. Inline mode runs it on the reader thread; fan-out on `-in`. */
    private fun consume(item: Item) {
        synchronized(recvLock) {
            val rt = synchronized(cutoverLock) { runtimes[item.id] }
            if (rt == null || rt.generation != item.generation) {
                if (item is Item.Content) {
                    payloadStore.take(item.rec.tag)
                    host.log("carrier_drop reason=old_generation")
                }
                return
            }
            val deferred = ArrayList<() -> Unit>()
            when (item) {
                is Item.Content -> synchronized(cutoverLock) {
                    if (!cutover.ended) {
                        val actions = cutover.onArrive(item.id, CarrierCutover.Wire.Content(item.rec), nowMs())
                        if (burnedSwitches > 0 && item.id != 0) {
                            burnRead += 1
                            if (actions.none { it is CarrierCutover.Action.Deliver || it is CarrierCutover.Action.Drop }) burnHeld += 1
                        }
                        for (action in actions) {
                            perform(action, deferred, item.generation, dropSource = "arrive")
                        }
                    }
                    reportCarrierChangesLocked(deferred)
                    flushHintLog(deferred)
                }
                is Item.Marker -> synchronized(cutoverLock) {
                    markerArrivedOn = item.id
                    if (!cutover.ended) {
                        for (action in cutover.onArrive(item.id, item.wire, nowMs())) {
                            perform(action, deferred, item.generation)
                        }
                    }
                    reportCarrierChangesLocked(deferred)
                    flushHintLog(deferred)
                }
                is Item.Hop -> Unit // arrival order only, never the machine
                is Item.Up -> consumeUp(item, deferred)
                is Item.Died -> consumeDied(item, deferred)
            }
            if (item is Item.Hop) deferred += { host.onHopRecord(item.id, item.record) }
            runDeferred(deferred)
        }
    }

    private fun consumeUp(item: Item.Up, deferred: MutableList<() -> Unit>) {
        val rt = synchronized(cutoverLock) { runtimes[item.id] }
        var tracked = false
        var ignored: Runtime? = null
        synchronized(cutoverLock) {
            if (!cutover.ended) {
                for (action in cutover.onCarrierUp(item.id, nowMs(), oldQueueAgeMs(), host.atFrameBoundary())) {
                    perform(action, deferred, item.generation)
                }
            }
            tracked = cutover.pendingCarrier == item.id || cutover.newCarrier == item.id
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
            if (tracked) {
                deferred += { startReader(item.id) }
            } else if (rt != null && !rt.dead.get()) {
                ignored = rt
            }
        }
        ignored?.let { closeIgnoredCarrier(it) }
    }

    /** Amendment 23 (X10): a carrier the machine tracks neither is closed like a cutover close, and reported. */
    private fun closeIgnoredCarrier(rt: Runtime) {
        val cookies = synchronized(cutoverLock) {
            if (!rt.dead.compareAndSet(false, true)) null else drainForDeath(rt)
        } ?: return
        val kind = rt.carrier.kind
        rt.carrier.close()
        if (cookies.isNotEmpty()) host.requeueUndelivered(cookies)
        host.onCarrierEvent(CarrierEvent.ENDED, rt.id, kind, "ignored")
        host.log("carrier_ignored kind=${kind.name}")
    }

    private fun consumeDied(item: Item.Died, deferred: MutableList<() -> Unit>) {
        var held = false
        synchronized(cutoverLock) {
            if (cutover.ended || heldEnd != null) return@synchronized
            if (fanoutFlag && item.busy) {
                // The probe sees every earlier queued event; the real machine is not fed a death that may still be undone.
                val probe = cutover.copy().also { it.onStat = null }.onCarrierDied(item.id, nowMs())
                held = probe.any { it is CarrierCutover.Action.EndSession }
            }
            if (held) {
                heldEnd = HeldEnd(item.id, item.reason, nanoClock(), nanoClock() + RENEWAL_END_DEFER_MS * 1_000_000L)
                val kindName = runtimes[item.id]?.carrier?.kind?.name ?: "none"
                deferred += { host.log("end_deferred carrier=$kindName | reason=${item.reason}") }
            } else {
                for (action in cutover.onCarrierDied(item.id, nowMs())) {
                    perform(action, deferred, item.generation)
                }
            }
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
    }

    private fun consumeCatching(item: Item) {
        try {
            consume(item)
        } catch (error: Exception) {
            runCatching { host.onConsumerError(error) }
        }
    }

    private fun startConsumer() {
        if (closed.get()) return
        val consumer = thread(name = "$threadPrefix-in", isDaemon = false, start = false) {
            while (!closed.get()) {
                val event = controlEvents.poll()
                if (event != null) {
                    // A death never overtakes the records already read from that carrier (review A3 P1-3): it waits
                    // behind them in the data lane, unless that lane is full.
                    if (event is Item.Died && inbound.any { it.id == event.id && it !is Item.Died } && inbound.offer(event)) {
                        continue
                    }
                    consumeCatching(event)
                    continue
                }
                // close() interrupts this thread: that is the end of the set, never an uncaught exception (which
                // ends the whole process on Android, the Bluetooth stream too).
                val item = try {
                    inbound.poll(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } ?: continue
                consumeCatching(item)
            }
        }
        register(consumer)
    }

    /** An added carrier's reader, started when its [Item.Up] was consumed (amendment 14). */
    private fun startReader(id: Int) {
        val rt = synchronized(cutoverLock) { runtimes[id] } ?: return
        if (rt.dead.get() || closed.get()) return
        val reader = thread(name = "$threadPrefix-r$id", isDaemon = false, start = false) {
            try {
                while (!closed.get() && !rt.dead.get()) {
                    val record = rt.carrier.link.reader.read()
                    onInbound(id, rt.generation, record)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!closed.get() && !rt.dead.get()) {
                    carrierDied(id, "read failed: ${error.javaClass.simpleName}", rt.generation, fromReader = true)
                }
            }
        }
        register(reader)
    }

    private fun register(t: Thread) {
        synchronized(threads) { threads += t }
        t.start()
    }

    /** The timers: per-carrier liveness, the standby's freshness, the cutover tick and a held end (step 5). */
    private fun startMonitor() {
        if (closed.get()) return
        val monitor = thread(name = "$threadPrefix-mon", isDaemon = true, start = false) {
            while (!closed.get() && fanoutFlag) {
                try {
                    monitorTick()
                } catch (_: Exception) {
                    // The monitor never exits because of one failed tick.
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
        register(monitor)
    }

    private fun monitorTick() {
        val nowNs = nanoClock()
        // Wi-Fi liveness: no authenticated record for the policy's silence, or a write stalled past its bound.
        val wifiDead = ArrayList<Pair<Int, String>>()
        val bluetoothIds = ArrayList<Int>()
        synchronized(cutoverLock) {
            for (rt in runtimes.values) {
                if (rt.dead.get()) continue
                val policy = rt.carrier.liveness
                if (policy.deadAfterSilenceNs > 0) {
                    // One snapshot of the volatile fields: the writer resets writeStartedNs to 0 without this lock, and a
                    // second read after the write finished would measure the phone's whole uptime as a stalled write.
                    val verdict = livenessVerdict(nowNs, rt.lastHeardNs, rt.writeStartedNs, policy)
                    if (verdict != null) wifiDead += rt.id to verdict
                } else {
                    bluetoothIds += rt.id
                }
            }
        }
        for ((id, reason) in wifiDead) carrierDied(id, reason)
        for (id in bluetoothIds) {
            if (host.bluetoothSilent(id)) carrierDied(id, "silence")
        }

        // Amendment 24: the owner's view of the standby's freshness, reported on a change only.
        val rt0 = synchronized(cutoverLock) { runtimes[0] }
        val fresh = rt0 != null && !rt0.dead.get() &&
            nanoClock() - rt0.lastHeardNs < STANDBY_FRESH_MS * 1_000_000L
        if (fresh != standbyFreshReported) {
            standbyFreshReported = fresh
            val deferred = ArrayList<() -> Unit>()
            synchronized(cutoverLock) {
                if (!cutover.ended) {
                    for (action in cutover.onStandbyFreshness(fresh, nowMs())) perform(action, deferred)
                }
                reportCarrierChangesLocked(deferred)
                flushHintLog(deferred)
            }
            runDeferred(deferred)
        }

        // A carrier-death end held while a renewal ran (amendment 7, [R A1-5]).
        if (heldEnd != null) {
            val busy = host.renewalBusy()
            var resolved: String? = null
            val deferred = ArrayList<() -> Unit>()
            synchronized(cutoverLock) {
                val held = heldEnd
                if (held != null) {
                    val heldNow = nanoClock()
                    if (heldNow >= held.deadlineNs) {
                        resolved = "expired"
                    } else if (!busy) {
                        if (held.busyFalseSinceNs == 0L) {
                            held.busyFalseSinceNs = heldNow
                        } else if (heldNow - held.busyFalseSinceNs >= RENEWAL_END_GRACE_MS * 1_000_000L) {
                            resolved = "renewal_failed"
                        }
                    } else {
                        held.busyFalseSinceNs = 0L
                    }
                    if (resolved != null) {
                        heldEnd = null
                        for (action in cutover.onCarrierDied(held.id, nowMs())) perform(action, deferred)
                        val result = resolved
                        deferred += { host.log("end_deferral_resolved result=$result | ms=${heldAgeMs(held)}") }
                    }
                }
                reportCarrierChangesLocked(deferred)
                flushHintLog(deferred)
            }
            runDeferred(deferred)
        }

        val deferred = ArrayList<() -> Unit>()
        synchronized(cutoverLock) {
            if (!cutover.ended) {
                for (action in cutover.onTick(nowMs(), oldQueueAgeMs(), host.atFrameBoundary())) {
                    perform(action, deferred)
                }
            }
            noteBurnSummary(force = false)
            reportCarrierChangesLocked(deferred)
            flushHintLog(deferred)
        }
        runDeferred(deferred)
    }

    private fun closeCarrierInternal(id: Int, reason: String) {
        val rt = synchronized(cutoverLock) { runtimes[id] } ?: return
        val cookies = synchronized(cutoverLock) {
            if (!rt.dead.compareAndSet(false, true)) null else drainForDeath(rt)
        } ?: return
        val kind = rt.carrier.kind
        rt.carrier.close()
        if (cookies.isNotEmpty()) host.requeueUndelivered(cookies)
        host.onCarrierEvent(CarrierEvent.ENDED, id, kind, reason)
    }

    // ---------------------------------------------------------------------------------------------- outbound

    private fun sendContent(type: SecureRecordType, bytes: ByteArray): Int {
        if (type !in contentTypes) {
            throw IllegalArgumentException(
                if (contentTypes == setOf(SecureRecordType.END_TO_END_CONTENT)) {
                    "Only end-to-end content goes through the sink"
                } else {
                    "Content type $type does not go through this sink"
                },
            )
        }
        val seq = sequenceOf(type, bytes)
        val pending = pendingCookie.get()
        pendingCookie.remove()
        val cookie = pending?.cookie
        val onWritten = pending?.onWritten
        val wireSize = bytes.size + SECURE_RECORD_OVERHEAD_BYTES
        var counted = false
        var target = CarrierCutover.NONE
        var rereads = 0
        val keepSlot = keepFifoSlot()
        while (true) {
            var inlineTarget: Runtime? = null
            var roomOn: Runtime? = null
            var done = false
            var result = 0
            val deferred = ArrayList<() -> Unit>()
            synchronized(cutoverLock) {
                if (closed.get()) {
                    done = true
                } else {
                    if (!counted) {
                        var outOfOrder = false
                        val actions = try {
                            cutover.onSealed(CarrierCutover.Rec(seq, bytes.size, 0), nowMs())
                        } catch (_: IllegalArgumentException) {
                            // Sealed out of order by a second thread (the liveness hang-up helper): route on sendOn
                            // without telling the machine (plan 3.4; review A3 P2-3).
                            outOfOrder = true
                            emptyList()
                        }
                        counted = true
                        var scheduled = false
                        for (action in actions) {
                            if (action is CarrierCutover.Action.Write && action.wire is CarrierCutover.Wire.Content) {
                                target = action.carrier
                                scheduled = true
                            } else {
                                perform(action, deferred, dropSource = "send")
                            }
                        }
                        if (!scheduled) target = if (outOfOrder) cutover.sendOn else CarrierCutover.NONE
                    } else if (cutover.sendOn != CarrierCutover.NONE) {
                        target = cutover.sendOn
                    }
                    if (target == CarrierCutover.NONE) {
                        done = true
                        result = 0
                    } else {
                        val rt = runtimes[target]
                        if (rt == null || rt.dead.get()) {
                            if (cutover.sendOn == target || rereads >= 3) {
                                done = true
                                result = 0
                            } else {
                                rereads++
                            }
                        } else if (!fanoutFlag) {
                            inlineTarget = rt
                            inlineInFlight.incrementAndGet()
                            done = true
                        } else if (rt.tryOfferContent(Out.Content(type, bytes, cookie, onWritten, nanoClock()))) {
                            done = true
                            result = wireSize
                        } else if (keepSlot) {
                            // Queue full: the record was assigned to this carrier when it was sealed (onSealed, under this
                            // lock hold or an earlier one), so it keeps its place in the FIFO, ahead of any SWITCH marker queued
                            // while the producer waits. It waits for room after the enqueue, not before.
                            rt.offerContentOverCapacity(Out.Content(type, bytes, cookie, onWritten, nanoClock()))
                            roomOn = rt
                            done = true
                            result = wireSize
                        }
                        // else queue full: wait outside the lock and retry
                    }
                }
                reportCarrierChangesLocked(deferred)
                flushHintLog(deferred)
            }
            runDeferred(deferred)
            if (done) {
                val inline = inlineTarget
                if (inline != null) {
                    return try {
                        val written = inline.carrier.link.writer.write(type, bytes)
                        // The same instant the call counted its delivery accounting before carriers existed (3.4).
                        onWritten?.invoke(inline.id, nanoClock())
                        written
                    } catch (error: IOException) {
                        if (fanoutFlag) {
                            carrierDied(0, "write failed: IOException", inline.generation)
                            0
                        } else {
                            throw error
                        }
                    } finally {
                        inlineInFlight.decrementAndGet()
                    }
                }
                if (result == 0 && cookie != null) requeueIfAnotherCarrier(cookie)
                roomOn?.awaitRoom(closed)
                return result
            }
            val rt = synchronized(cutoverLock) { runtimes[target] }
            rt?.awaitSpace(5)
            if (closed.get()) return 0
        }
    }

    /** Amendment 16: a dropped record with a cookie goes back to the call when another carrier can still carry it. */
    private fun requeueIfAnotherCarrier(cookie: Any) {
        val scheduled = synchronized(cutoverLock) { cutover.sendOn != CarrierCutover.NONE && !cutover.ended }
        if (scheduled) host.requeueUndelivered(listOf(cookie))
    }

    private fun startWriter(id: Int) {
        val rt = synchronized(cutoverLock) { runtimes[id] } ?: return
        val writer = thread(name = "$threadPrefix-w$id", isDaemon = false, start = false) {
            try {
                if (id == 0) {
                    while (inlineInFlight.get() > 0 && !closed.get() && !rt.dead.get()) Thread.sleep(1)
                }
                var lastWrittenNs = 0L
                var lastHeartbeatNs = 0L
                var lastFlushNs = 0L
                while (!closed.get() && !rt.dead.get()) {
                    val interval = rt.carrier.liveness.heartbeatIntervalNs
                    val periodic = rt.carrier.liveness.deadAfterSilenceNs == 0L
                    val now = nanoClock()
                    val heartbeatDue = if (periodic) {
                        lastHeartbeatNs == 0L || now - lastHeartbeatNs >= interval
                    } else {
                        lastWrittenNs == 0L || now - lastWrittenNs >= interval
                    }
                    if (rt.flushRequested) {
                        rt.flushRequested = false
                        rt.carrier.link.writer.flush()
                        rt.lastProgressNs = nanoClock()
                        host.onProgress(id, rt.lastProgressNs)
                    }
                    val dueFrom = if (periodic) lastHeartbeatNs else lastWrittenNs
                    val waitNs = if (heartbeatDue) 0L else ((dueFrom + interval - now).coerceAtLeast(0L))
                    val timeoutMs = minOf(20_000_000L, waitNs) / 1_000_000L
                    val item = rt.control.poll() ?: rt.media.poll(timeoutMs, TimeUnit.MILLISECONDS)
                    if (item == null) {
                        if (heartbeatDue) {
                            writeHeartbeat(rt)
                            lastHeartbeatNs = nanoClock()
                            lastWrittenNs = lastHeartbeatNs
                        }
                        continue
                    }
                    when (item) {
                        is Out.Content -> {
                            val started = nanoClock()
                            rt.writeStartedNs = started
                            val written = rt.carrier.link.writer.write(item.type, item.bytes)
                            rt.bytesWritten.addAndGet(written.toLong())
                            rt.recordsWritten.incrementAndGet()
                            rt.contentCount.decrementAndGet()
                            item.onWritten?.invoke(id, nanoClock())
                            val flushAfter = rt.carrier.liveness.flushIntervalNs ?: flushIntervalNs(rt.carrier.kind)
                            val nowAfter = nanoClock()
                            if (flushAfter == 0L || rt.media.isEmpty() || nowAfter - lastFlushNs >= flushAfter) {
                                rt.carrier.link.writer.flush()
                                lastFlushNs = nowAfter
                                rt.lastProgressNs = nanoClock()
                                host.onProgress(id, rt.lastProgressNs)
                            }
                            rt.writeStartedNs = 0L
                            lastWrittenNs = nanoClock()
                            if (heartbeatDue) {
                                writeHeartbeat(rt)
                                lastHeartbeatNs = nanoClock()
                                lastWrittenNs = lastHeartbeatNs
                            }
                        }
                        is Out.Hop -> {
                            val written = rt.carrier.link.writer.write(item.type, item.payload)
                            rt.carrier.link.writer.flush()
                            rt.bytesWritten.addAndGet(written.toLong())
                            rt.recordsWritten.incrementAndGet()
                            rt.lastProgressNs = nanoClock()
                            host.onProgress(id, rt.lastProgressNs)
                            lastWrittenNs = rt.lastProgressNs
                        }
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                if (!closed.get() && !rt.dead.get()) {
                    carrierDied(id, "write failed: ${error.javaClass.simpleName}", rt.generation)
                }
            }
        }
        synchronized(threads) { threads += writer }
        writer.start()
    }

    /** The `carrier_drop` line: which carrier, record and boundary the machine dropped, for the stale-at-switch analysis. Under [cutoverLock]. */
    private fun dropLine(action: CarrierCutover.Action.Drop, seq: Long?, source: String): String =
        "carrier_drop reason=${action.reason.name.lowercase()}" +
            if (BuildConfig.DEBUG) {
                " carrier=${action.carrier} seq=${seq ?: -1} boundary=${cutover.acceptBoundary} lastOpened=${cutover.lastOpened} source=$source"
            } else {
                ""
            }

    private fun writeHeartbeat(rt: Runtime) {
        val payload = host.heartbeatPayload(rt.id)
        val written = if (payload == null) {
            SecureLinkControlProtocol.writeHeartbeat(rt.carrier.link.writer)
        } else {
            rt.carrier.link.writer.write(SecureRecordType.LINK_HEARTBEAT, payload)
        }
        rt.carrier.link.writer.flush()
        rt.bytesWritten.addAndGet(written.toLong())
        rt.recordsWritten.incrementAndGet()
        rt.lastProgressNs = nanoClock()
        host.onProgress(rt.id, rt.lastProgressNs)
    }

    // ---------------------------------------------------------------------------------------------- actions

    /** [dropSource]: where a drop this action reports came from, for the DEBUG `carrier_drop` line: `arrive` (a record just read), `release` (a held record or a timer) or `send`. */
    private fun perform(action: CarrierCutover.Action, deferred: MutableList<() -> Unit>, deliveryGeneration: Int = -1, dropSource: String = "release") {
        when (action) {
            is CarrierCutover.Action.Write -> {
                when (val wire = action.wire) {
                    is CarrierCutover.Wire.Content -> Unit // content is queued by sendContent
                    is CarrierCutover.Wire.Switch, is CarrierCutover.Wire.Resume -> {
                        val rt = runtimes[action.carrier]
                        if (rt != null && !rt.dead.get()) {
                            val encoded = markers.encode(wire)
                            val hop = Out.Hop(SecureRecordType.BWF1, encoded, action.priority)
                            if (action.priority) rt.control.offer(hop) else rt.media.offerLast(hop)
                        }
                    }
                }
            }
            is CarrierCutover.Action.Deliver -> {
                if (burnedSwitches > 0 && action.carrier != 0) {
                    burnDelivered += 1
                    if (dropSource != "arrive") burnReleased += 1
                }
                val taken = payloadStore.take(action.rec.tag)
                if (taken != null) {
                    val generation = if (deliveryGeneration != -1) deliveryGeneration else generationOf(action.carrier)
                    deferred += { host.deliverRecord(action.carrier, generation, taken.first, taken.second) }
                }
            }
            is CarrierCutover.Action.Drop -> {
                if (burnedSwitches > 0 && action.carrier != 0) burnDropped += 1
                val wire = action.wire
                if (wire is CarrierCutover.Wire.Content) {
                    val taken = payloadStore.take(wire.rec.tag)
                    // Inline mode never makes a replayed or fenced record silent: the opener fails exactly as today.
                    if (!fanoutFlag && taken != null &&
                        (action.reason == CarrierCutover.DropReason.STALE || action.reason == CarrierCutover.DropReason.FENCED)
                    ) {
                        val generation = if (deliveryGeneration != -1) deliveryGeneration else generationOf(action.carrier)
                        deferred += { host.deliverRecord(action.carrier, generation, taken.first, taken.second) }
                    }
                    val line = dropLine(action, wire.rec.seq, dropSource)
                    deferred += { host.log(line) }
                } else {
                    val line = dropLine(action, null, dropSource)
                    deferred += { host.log(line) }
                }
            }
            is CarrierCutover.Action.CloseCarrier -> {
                deferred += { closeCarrierInternal(action.carrier, "cutover:${action.reason.name}") }
            }
            CarrierCutover.Action.ReassertState,
            CarrierCutover.Action.ForceLocalKeyframe,
            CarrierCutover.Action.RequestPeerKeyframe,
            CarrierCutover.Action.ResetSendFragments,
            CarrierCutover.Action.SuppressFragments,
            -> deferred += { host.onCutoverAction(action) }
            is CarrierCutover.Action.EndSession -> {
                deferred += { reportEnd(action.reason) }
            }
        }
    }

    private fun reportEnd(reason: String) {
        if (!hostEndReported.compareAndSet(false, true)) return
        host.onEndSession(reason)
    }

    private fun generationOf(id: Int): Int = synchronized(cutoverLock) { runtimes[id]?.generation ?: -1 }

    /** Under [cutoverLock]: the cutover dropped a SWITCH naming no carrier this side knows. No ticket bytes are logged. */
    private fun noteSwitchBurned() {
        if (!BuildConfig.DEBUG) return
        burnedSwitches += 1
        if (burnAtMs < 0L) burnAtMs = nowMs()
        burnAwaitingHold = true
        val sinceOffer = if (lastOfferMs < 0L) "none" else (nowMs() - lastOfferMs).coerceAtLeast(0L).toString()
        val kind = runtimes[markerArrivedOn]?.carrier?.kind?.name ?: "unknown"
        burnLines += "BENCHMARK switch_burned: hop=$hopLabel | carrier=$kind | sinceOfferMs=$sinceOffer | heldMs=pending | count=$burnedSwitches"
    }

    /** Under [cutoverLock]: the first hold of early records that ends after a burned SWITCH, with how long it lasted. */
    private fun noteHoldAfterBurn() {
        if (!BuildConfig.DEBUG || !burnAwaitingHold) return
        burnAwaitingHold = false
        burnLines += "BENCHMARK switch_burned_hold: hop=$hopLabel | heldMs=${cutover.lastHoldMs} | end=${cutover.lastHoldEnd} | count=$burnedSwitches"
    }

    /**
     * Under [cutoverLock], DEBUG: one `switch_burned_summary` line 3 s after the first burned SWITCH (or at close): records read,
     * held, released by the hold and delivered on the carriers that followed it. The 0007 report (`switch_burned` with no
     * `switch_burned_hold` and the Wi-Fi frames never seen) needs this count to tell "never read" from "held, then lost".
     */
    private fun noteBurnSummary(force: Boolean) {
        if (!BuildConfig.DEBUG || burnedSwitches == 0 || burnSummaryLogged) return
        val since = (nowMs() - burnAtMs).coerceAtLeast(0L)
        if (!force && since < 3_000L) return
        burnSummaryLogged = true
        burnLines += "BENCHMARK switch_burned_summary: hop=$hopLabel | sinceBurnMs=$since | read=$burnRead | held=$burnHeld | " +
            "released=$burnReleased | delivered=$burnDelivered | dropped=$burnDropped | accept=${cutover.accept} | send=${cutover.sendOn} | count=$burnedSwitches"
    }

    private fun flushHintLog(deferred: MutableList<() -> Unit>) {
        if (burnLines.isNotEmpty()) {
            val lines = ArrayList(burnLines)
            burnLines.clear()
            for (line in lines) deferred += { host.log(line) }
        }
        if (ignoredHints > 0) {
            val n = ignoredHints
            ignoredHints = 0
            deferred += { host.log("old_dead_hint | ignored=$n") }
        }
    }

    /** After an action batch: report a send-carrier change and an accepting-carrier change, once each (step 4/5). */
    private fun reportCarrierChangesLocked(deferred: MutableList<() -> Unit>) {
        val sendOn = cutover.sendOn
        if (sendOn != reportedSendOn) {
            reportedSendOn = sendOn
            val kind = runtimes[sendOn]?.takeIf { !it.dead.get() }?.carrier?.kind
            queueReportLocked(deferred) { host.onSendCarrierChanged(sendOn, kind, if (sendOn == CarrierCutover.NONE) "none" else "switch") }
        }
        val accept = cutover.accept
        if (accept != reportedAccept) {
            reportedAccept = accept
            val kind = runtimes[accept]?.takeIf { !it.dead.get() }?.carrier?.kind
            queueReportLocked(deferred) { host.onCarrierEvent(CarrierEvent.ACCEPT_CHANGED, accept, kind, "accept") }
        }
    }

    /** Called under [cutoverLock]: [report] takes its place in the report order now and is run after the lock is released. */
    private fun queueReportLocked(deferred: MutableList<() -> Unit>, report: () -> Unit) {
        reports.offer(report)
        deferred += { deliverReports() }
    }

    /**
     * Runs the queued reports one at a time, in queue order, on the calling thread and under no lock. A thread that finds
     * another one delivering leaves its reports to that thread, which re-checks the queue after it stops, so a report is
     * never stranded and never overtakes an earlier one (also when a host callback causes a change itself). A report
     * can therefore run on another set thread than the one whose change it reports. A throwing callback does not stop the
     * later reports; the first error is rethrown to the caller.
     */
    private fun deliverReports() {
        var error: Throwable? = null
        while (reports.isNotEmpty()) {
            if (!reportsDelivering.compareAndSet(false, true)) break
            try {
                while (true) {
                    val report = reports.poll() ?: break
                    try {
                        report()
                    } catch (failure: Throwable) {
                        if (error == null) error = failure
                    }
                }
            } finally {
                reportsDelivering.set(false)
            }
        }
        error?.let { throw it }
    }

    private fun runDeferred(deferred: List<() -> Unit>) {
        for (block in deferred) block()
    }

    private fun nowMs(): Long = nanoClock() / 1_000_000L
}
