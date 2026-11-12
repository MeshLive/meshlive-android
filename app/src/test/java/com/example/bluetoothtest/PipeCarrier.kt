package com.example.bluetoothtest

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A3 test carriers: two connected ends of one carrier over in-process pipes with independent keys per direction; either
 * side's writes can be blocked or the pair cut. No Android types (a JVM test needs no phone, socket or Bluetooth stack).
 */
internal class BytePipe(private val capacityBytes: Int = 1 shl 20) {
    private val lock = Object()
    private val buffer = ByteArray(capacityBytes)
    private var readPos = 0
    private var writePos = 0
    private var size = 0
    private var closed = false

    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (size == 0 && !closed) lock.wait()
            if (size == 0) return -1
            val count = minOf(len, size, capacityBytes - readPos)
            System.arraycopy(buffer, readPos, b, off, count)
            readPos = (readPos + count) % capacityBytes
            size -= count
            lock.notifyAll()
            return count
        }
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        var written = 0
        while (written < len) {
            synchronized(lock) {
                while (size == capacityBytes && !closed) lock.wait()
                if (closed) throw IOException("pipe closed")
                val count = minOf(len - written, capacityBytes - size, capacityBytes - writePos)
                System.arraycopy(b, off + written, buffer, writePos, count)
                writePos = (writePos + count) % capacityBytes
                size += count
                written += count
                lock.notifyAll()
            }
        }
    }

    /** Wakes both sides: a reader sees EOF, a writer an [IOException]. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun input(): InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = this@BytePipe.read(b, off, len)

        override fun close() = this@BytePipe.close()
    }
}

/** Blocks every write while its gate is closed (a stalled link), and fails once the pipe is cut. */
internal class GateOutputStream(private val pipe: BytePipe) : OutputStream() {
    private val lock = Object()
    private var open = true

    fun setOpen(value: Boolean) {
        synchronized(lock) {
            open = value
            lock.notifyAll()
        }
    }

    private fun awaitOpen() {
        synchronized(lock) {
            while (!open) lock.wait()
        }
    }

    override fun write(b: Int) {
        awaitOpen()
        pipe.write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        awaitOpen()
        pipe.write(b, off, len)
    }

    override fun close() = setOpen(true)
}

/** One end of a [PipePair]; its link is a real `BMS1` session over the pipe. */
internal class PipeCarrier(
    override val kind: CarrierKind,
    override val label: String,
    override val link: SecureBluetoothSession,
    override val liveness: CarrierLivenessPolicy,
    private val closeAction: () -> Unit = {},
) : Carrier {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) closeAction()
    }
}

/** Two connected carriers: `a` writes with `keyAB`, `b` reads it; `b` writes with `keyBA`, `a` reads it. */
internal class PipePair(
    kind: CarrierKind = CarrierKind.BLUETOOTH,
    label: String = "pipe",
    liveness: CarrierLivenessPolicy = CarrierLivenessPolicy.WIFI,
    keyAB: ByteArray = ByteArray(32) { (it + 1).toByte() },
    keyBA: ByteArray = ByteArray(32) { (it + 101).toByte() },
) {
    private val aToB = BytePipe()
    private val bToA = BytePipe()
    private val gateA = GateOutputStream(aToB)
    private val gateB = GateOutputStream(bToA)

    val a: PipeCarrier
    val b: PipeCarrier

    init {
        val peer = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { (it + 7).toByte() })
        val caps = NodeCapabilities(
            transportMask = NodeCapabilities.TRANSPORT_BLE_GATT,
            encodeCodecMask = NodeCapabilities.CODEC_VP9,
            decodeCodecMask = NodeCapabilities.CODEC_VP9,
            maxBitrateKbps = 1_100,
            maxChildren = 0,
        )
        val sessionA = SecureBluetoothSession(
            peerNodeId = peer,
            peerCapabilities = caps,
            reader = SecureRecordReader(BufferedInputStream(bToA.input()), keyBA),
            writer = SecureRecordWriter(BufferedOutputStream(gateA), keyAB),
        )
        val sessionB = SecureBluetoothSession(
            peerNodeId = peer,
            peerCapabilities = caps,
            reader = SecureRecordReader(BufferedInputStream(aToB.input()), keyAB),
            writer = SecureRecordWriter(BufferedOutputStream(gateB), keyBA),
        )
        // A test carrier carries BWF1 records: the reader opt-in the call sets from the link handshake.
        sessionA.reader.acceptWifi = true
        sessionB.reader.acceptWifi = true
        a = PipeCarrier(kind, label, sessionA, liveness) {
            gateA.setOpen(true)
            aToB.close()
            bToA.close()
        }
        b = PipeCarrier(kind, label, sessionB, liveness) {
            gateB.setOpen(true)
            aToB.close()
            bToA.close()
        }
    }

    /** Both directions fail: readers see EOF, a blocked writer an [IOException]; everything is unblocked. */
    fun cut() {
        gateA.setOpen(true)
        gateB.setOpen(true)
        aToB.close()
        bToA.close()
    }

    fun blockWritesFromA(on: Boolean) = gateA.setOpen(!on)

    fun blockWritesFromB(on: Boolean) = gateB.setOpen(!on)
}

/** A trivial `BWF1` marker codec: `'M' | kind | gen | second | carrier`; anything whose first byte is not `'M'` is not a marker. */
internal class TestMarkerCodec : CarrierMarkerCodec {
    override fun encode(wire: CarrierCutover.Wire): ByteArray = when (wire) {
        is CarrierCutover.Wire.Switch -> payload(KIND_SWITCH, wire.gen, wire.boundary, wire.carrier)
        is CarrierCutover.Wire.Resume -> payload(KIND_RESUME, wire.gen, wire.next, wire.carrier)
        is CarrierCutover.Wire.Content -> throw IllegalArgumentException("Content is not a marker")
    }

    override fun decode(payload: ByteArray): CarrierCutover.Wire? {
        if (payload.isEmpty() || payload[0] != 'M'.code.toByte()) return null
        if (payload.size < 2) throw IllegalArgumentException("Short marker")
        return when (payload[1].toInt() and 0xff) {
            KIND_SWITCH, KIND_RESUME -> {
                if (payload.size != 2 + 24) throw IllegalArgumentException("Bad marker length")
                val body = ByteBuffer.wrap(payload, 2, 24)
                val gen = body.long
                val second = body.long
                val carrier = body.int
                if ((payload[1].toInt() and 0xff) == KIND_SWITCH) {
                    CarrierCutover.Wire.Switch(gen, second, carrier)
                } else {
                    CarrierCutover.Wire.Resume(gen, second, carrier)
                }
            }
            else -> throw IllegalArgumentException("Unknown marker kind")
        }
    }

    fun wasSwitch(payload: ByteArray): CarrierCutover.Wire.Switch? = decode(payload) as? CarrierCutover.Wire.Switch

    fun wasResume(payload: ByteArray): CarrierCutover.Wire.Resume? = decode(payload) as? CarrierCutover.Wire.Resume

    private fun payload(kind: Int, gen: Long, second: Long, carrier: Int): ByteArray =
        byteArrayOf('M'.code.toByte(), kind.toByte()) +
            ByteBuffer.allocate(24).putLong(gen).putLong(second).putInt(carrier).array()

    companion object {
        const val KIND_SWITCH = 1
        const val KIND_RESUME = 2
    }
}

/** A [CarrierSet.Host] that records what reached it; the tests assert on these lists. */
internal open class RecordingHost : CarrierSet.Host {
    private val lock = Object()

    val delivered = CopyOnWriteArrayList<Pair<Int, Long>>()
    val deliveredTypes = CopyOnWriteArrayList<Triple<Int, SecureRecordType, ByteArray>>()
    val actions = CopyOnWriteArrayList<CarrierCutover.Action>()
    val sendCarrierChanges = CopyOnWriteArrayList<Triple<Int, CarrierKind?, String>>()
    val acceptChanges = CopyOnWriteArrayList<Int>()
    val endSessions = CopyOnWriteArrayList<String>()
    val wifiRecords = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    val requeued = CopyOnWriteArrayList<Any>()
    val consumerErrors = CopyOnWriteArrayList<Exception>()
    val carrierEvents = CopyOnWriteArrayList<Quad<CarrierEvent, Int, CarrierKind?, String>>()
    val hopRecords = CopyOnWriteArrayList<SecureRecord>()
    val heartbeatIds = CopyOnWriteArrayList<Int>()
    val heartbeatGenerations = CopyOnWriteArrayList<Pair<Int, Int>>()
    val heartbeatPayloadRequests = CopyOnWriteArrayList<Int>()
    val progress = CopyOnWriteArrayList<Pair<Int, Long>>()
    val protocolErrors = CopyOnWriteArrayList<String>()
    val logs = CopyOnWriteArrayList<String>()
    val deliverThreads = CopyOnWriteArrayList<Thread>()

    @Volatile var renewalBusy = false
    @Volatile var heartbeatPayload: ByteArray? = null
    @Volatile var bluetoothSilent = false
    @Volatile var frameBoundary = true
    @Volatile var deliverError: Exception? = null
    @Volatile var onDeliver: (() -> Unit)? = null

    data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    override fun heard(carrierId: Int, generation: Int): Boolean = true

    override fun onHeartbeat(carrierId: Int, generation: Int, record: SecureRecord) {
        heartbeatIds += carrierId
        heartbeatGenerations += carrierId to generation
    }

    open override fun deliver(carrierId: Int, generation: Int, payload: ByteArray) {
        deliverThreads += Thread.currentThread()
        val seq = runCatching { EndToEndContentProtocol.inspect(payload, true, true, true, true).sequence }.getOrDefault(-1L)
        synchronized(lock) {
            delivered += carrierId to seq
            deliveredTypes += Triple(carrierId, SecureRecordType.END_TO_END_CONTENT, payload)
            lock.notifyAll()
        }
        onDeliver?.invoke()
        deliverError?.let { throw it }
    }

    override fun onHopRecord(carrierId: Int, record: SecureRecord) {
        synchronized(lock) {
            hopRecords += record
            lock.notifyAll()
        }
    }

    override fun onWifiRecord(carrierId: Int, payload: ByteArray) {
        synchronized(lock) {
            wifiRecords += carrierId to payload
            lock.notifyAll()
        }
    }

    override fun onCutoverAction(action: CarrierCutover.Action) {
        synchronized(lock) {
            actions += action
            lock.notifyAll()
        }
    }

    override fun onSendCarrierChanged(sendOn: Int, kind: CarrierKind?, reason: String) {
        synchronized(lock) {
            sendCarrierChanges += Triple(sendOn, kind, reason)
            lock.notifyAll()
        }
    }

    override fun onEndSession(reason: String) {
        synchronized(lock) {
            endSessions += reason
            lock.notifyAll()
        }
    }

    override fun heartbeatPayload(carrierId: Int): ByteArray? {
        heartbeatPayloadRequests += carrierId
        return heartbeatPayload
    }

    override fun bluetoothSilent(carrierId: Int): Boolean = bluetoothSilent

    override fun onProgress(carrierId: Int, nowNs: Long) {
        progress += carrierId to nowNs
    }

    override fun onProtocolError(carrierId: Int, message: String) {
        synchronized(lock) {
            protocolErrors += message
            lock.notifyAll()
        }
    }

    override fun onConsumerError(error: Exception) {
        synchronized(lock) {
            consumerErrors += error
            lock.notifyAll()
        }
    }

    override fun requeueUndelivered(cookies: List<Any>) {
        synchronized(lock) {
            requeued.add(cookies)
            lock.notifyAll()
        }
    }

    override fun atFrameBoundary(): Boolean = frameBoundary

    override fun renewalBusy(): Boolean = renewalBusy

    override fun onCarrierEvent(event: CarrierEvent, carrierId: Int, kind: CarrierKind?, reason: String) {
        synchronized(lock) {
            carrierEvents += Quad(event, carrierId, kind, reason)
            lock.notifyAll()
        }
    }

    override fun log(line: String) {
        logs += line
    }

    /** Waits until [count] records were delivered (or the timeout); true when they were. */
    fun await(count: Int, timeoutMs: Long): Boolean = awaitUntil(timeoutMs) { delivered.size >= count }

    fun awaitEnds(count: Int, timeoutMs: Long): Boolean = awaitUntil(timeoutMs) { endSessions.size >= count }

    fun awaitWifi(count: Int, timeoutMs: Long): Boolean = awaitUntil(timeoutMs) { wifiRecords.size >= count }

    fun awaitLog(line: String, timeoutMs: Long): Boolean = awaitUntil(timeoutMs) { logs.contains(line) }

    fun sequences(): List<Long> = delivered.map { it.second }

    fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (!condition()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return condition()
                lock.wait(left)
            }
            return true
        }
    }
}

/** The cutover config the tests use: an immediate switch, a short hold and a short cooldown. */
internal fun testCutoverConfig(): CarrierCutover.Config =
    CarrierCutover.Config(activeBeforeSwitchMs = 0L, holdMs = 200L, cooldownMs = 500L)
