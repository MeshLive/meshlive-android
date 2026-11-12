package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSetCallback
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.TreeMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GattVideoConnection(
    val inputStream: InputStream,
    val outputStream: OutputStream,
    val phySummary: () -> String,
    val receiveQueueState: () -> Pair<Int, Int>,
    val requestConnectionPriority: (BleConnectionPriority) -> Boolean = { false },
    /** GATT status of the link's disconnect callback, -1 while no disconnect was reported (see [StreamPhyLoss]). */
    val disconnectStatus: () -> Int = { -1 },
    /** Asks the link for [BlePhyPreference] now (used when the setup deferred the PHY request, `test_phy_after_auth`). */
    val requestPhy: (BlePhyPreference) -> Boolean = { false },
    /** Reads the link's PHY (starts no LL procedure; the result updates [phySummary]); false when it could not be asked. */
    val readPhy: () -> Boolean = { false },
    private val closeAction: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        closeAction()
    }
}

internal class GattBytePipe(
    capacity: Int = PIPE_CHUNKS,
    /**
     * Unread bytes the pipe holds before it overflows. The queue is bounded by bytes first: a chunk is one GATT value, and a
     * backlog of small audio records (about 80 B each) used to hit the old 512-chunk bound after only 40 KB.
     */
    private val maxQueuedBytes: Int = MAX_QUEUED_BYTES,
    /** [ChunkGapPolicy] switch (default on; DEBUG control arm `test_gatt_gap_fast_fail=false`). */
    private val gapFastFail: () -> Boolean = { DebugTunables.gattGapFastFail },
    private val nanoTime: () -> Long = System::nanoTime,
    /** Experimental lost-chunk recovery for this link ([GattChunkRecovery], `test_gatt_chunk_recovery`); null when off. */
    val recovery: GattChunkRecovery? = null,
    /** DEBUG `test_gatt_drop_received_every=N`: each received data chunk whose sequence is N-1 mod N is dropped once. */
    private val dropEvery: Int = DebugTunables.gattDropReceivedEvery,
    /** Experimental diagnostic `test_hop_integrity_resend_diff` ([HopIntegrityResendDiff]); default off. */
    resendDiff: Boolean = DebugTunables.hopIntegrityResendDiff,
    private val debugLog: (String) -> Unit = { line -> Log.i("BluetoothVideo", line) },
    private val onFailure: (String) -> Unit,
) : InputStream() {
    private data class Chunk(val bytes: ByteArray, val start: Int, val end: Int)

    private val chunks = LinkedBlockingQueue<Chunk>(capacity)
    private val capacity = capacity
    private val queuedBytes = java.util.concurrent.atomic.AtomicInteger()
    private val closed = AtomicBoolean(false)
    private var current = CLOSED_CHUNK
    private var offset = 0
    private var nextPacketSequence = 0
    private val recentPacketSequences = LinkedHashSet<Int>()
    private val pendingPackets = TreeMap<Int, ByteArray>()
    /** When the current sequence gap opened (the first chunk parked behind a missing one); 0 while there is none. */
    private var gapOpenedNs = 0L
    /** Set when [ChunkGapPolicy] closed the pipe: the reader gets it as an IOException instead of a bare EOF. */
    @Volatile private var lostChunkReason: String? = null
    /** Bytes of the chunks held in [pendingPackets]. */
    private var parkedBytes = 0
    private val recoveryReader: GattRecoveryReader? = recovery?.let { link ->
        link.attachReader(this)
        GattRecoveryReader(link)
    }
    private val droppedOnce = LinkedHashSet<Int>()
    /** [HopIntegrityResendDiff] for this link: null with the switch off or without GR1 (then nothing below runs). */
    private val diffTap: ResendDiffTap? = if (resendDiff && recovery != null) {
        ResendDiffTap(recovery.link, recoveryActive = { recoveryReader?.active == true }, sendNack = recovery::sendNack)
            .also(HopIntegrityResendDiff::register)
    } else {
        null
    }

    @Volatile private var lastInboundNs = 0L

    /** True once any data chunk arrived from the peer (type-2 control values do not count). */
    fun heardAny(): Boolean = lastInboundNs != 0L

    /** True when data arrived from the peer within [windowMs] (any record, so a quiet peer's heartbeats count). */
    fun heardWithin(windowMs: Long): Boolean {
        val last = lastInboundNs
        return last != 0L && System.nanoTime() - last <= windowMs * 1_000_000L
    }

    /** Milliseconds since the peer's last data chunk, or -1 before any (Experimental `test_phy_direction_split`). */
    fun quietMs(): Long {
        val last = lastInboundNs
        return if (last == 0L) -1L else (System.nanoTime() - last) / 1_000_000L
    }

    fun offer(bytes: ByteArray): Boolean = offer(bytes, 0, bytes.size)

    fun offer(bytes: ByteArray, start: Int, length: Int): Boolean {
        require(start >= 0 && length >= 0 && start + length <= bytes.size)
        if (closed.get() || length == 0) return false
        lastInboundNs = System.nanoTime()
        val ownedBytes = bytes.copyOfRange(start, start + length)
        val queuedNow = queuedBytes.addAndGet(ownedBytes.size)
        if (queuedNow > maxQueuedBytes || !chunks.offer(Chunk(ownedBytes, 0, ownedBytes.size))) {
            queuedBytes.addAndGet(-ownedBytes.size)
            onFailure("input queue overflowed")
            close()
            return false
        }
        return true
    }

    @Synchronized
    fun offerPacket(packet: ByteArray): Boolean {
        if (closed.get()) return false
        if (packet.size < GATT_CHUNK_HEADER_BYTES || packet[0] != TYPE_DATA) {
            lastInboundNs = System.nanoTime()
            onFailure("received an invalid chunk header")
            close()
            return false
        }
        val sequence = decodeChunkSequence(packet)
        if (dropEvery > 0 && Math.floorMod(sequence, dropEvery) == dropEvery - 1 && droppedOnce.add(sequence)) {
            // DEBUG: behave as if the host had lost this value (the first copy only, so a resend gets through).
            if (droppedOnce.size > RECENT_PACKET_HISTORY) droppedOnce.remove(droppedOnce.first())
            debugLog("BENCHMARK gatt_debug_drop: seq=$sequence | every=$dropEvery")
            return true
        }
        lastInboundNs = System.nanoTime()
        if (sequence in recentPacketSequences || sequence in pendingPackets) {
            // A duplicate is dropped as always; the diagnostic only compares it (never delivers it).
            if (diffTap != null && sequence in recentPacketSequences) diffTap.onDuplicate(sequence, packet)
            return true
        }
        if (seqAfter(sequence, nextPacketSequence) > 0) {
            if (
                seqAfter(sequence, nextPacketSequence) > MAX_REORDERED_PACKET_DISTANCE ||
                pendingPackets.size >= MAX_REORDERED_PACKETS
            ) {
                onFailure("received chunk $sequence; expected $nextPacketSequence")
                close()
                return false
            }
            if (pendingPackets.isEmpty()) gapOpenedNs = nanoTime()
            pendingPackets[sequence] = packet.copyOf()
            parkedBytes += packet.size
            val reader = recoveryReader
            // With the peer's HELLO the reader asks for the chunk; its deadline replaces option A's limits.
            if (reader != null && reader.active) return updateRecovery(reader)
            val heldMs = (nanoTime() - gapOpenedNs) / 1_000_000L
            if (gapFastFail() && ChunkGapPolicy.lost(heldMs, pendingPackets.size)) {
                // The sender never reorders; a chunk this late was lost below the app, and waiting only delays the end.
                val reason = ChunkGapPolicy.reason(nextPacketSequence, pendingPackets.size, heldMs)
                lostChunkReason = reason
                onFailure(reason)
                close()
                return false
            }
            return true
        }
        if (sequence != nextPacketSequence) {
            onFailure("received chunk $sequence; expected $nextPacketSequence")
            close()
            return false
        }
        if (!acceptNextPacket(packet)) return false
        while (true) {
            val pending = pendingPackets.remove(nextPacketSequence) ?: break
            parkedBytes -= pending.size
            if (!acceptNextPacket(pending)) return false
        }
        // The missing chunk arrived: a gap still left behind it is a new one with its own clock.
        gapOpenedNs = if (pendingPackets.isEmpty()) 0L else nanoTime()
        val reader = recoveryReader
        if (reader != null && reader.active) return updateRecovery(reader)
        return true
    }

    private fun updateRecovery(reader: GattRecoveryReader): Boolean {
        val failure = reader.update(
            nextPacketSequence,
            pendingPackets.navigableKeySet(),
            pendingPackets.size,
            parkedBytes,
            nanoTime(),
        ) ?: return true
        return failLost(failure)
    }

    private fun failLost(reason: String): Boolean {
        lostChunkReason = reason
        recovery?.note("event=failed | reason=$reason")
        onFailure(reason)
        close()
        return false
    }

    /** A `GR1` HIGH from the sender (callback thread; no I/O). */
    @Synchronized
    fun onRecoveryHigh(sequence: Int) {
        val reader = recoveryReader ?: return
        if (closed.get() || !reader.onHigh(sequence, nextPacketSequence)) return
        if (gapOpenedNs == 0L) gapOpenedNs = nanoTime()
        updateRecovery(reader)
    }

    /** A `GR1` GONE: the sender no longer holds these chunks (callback thread; no I/O). */
    @Synchronized
    fun onRecoveryGone(range: Gr1.SeqRange) {
        val reader = recoveryReader ?: return
        if (closed.get()) return
        diffTap?.onGone(range)
        val lost = reader.goneHit(range)
        if (lost == null) {
            recovery?.note("event=ignored | reason=gone_not_missing | seq=${range.first}")
            return
        }
        failLost("GATT chunk $lost was lost and the peer no longer holds it")
    }

    /** Request rounds and the deadline while nothing arrives (the link's recovery writer thread). */
    @Synchronized
    fun recoveryTick() {
        val reader = recoveryReader ?: return
        if (closed.get() || !reader.active) return
        updateRecovery(reader)
    }

    private fun acceptNextPacket(packet: ByteArray): Boolean {
        if (recentPacketSequences.size >= RECENT_PACKET_HISTORY) {
            recentPacketSequences.remove(recentPacketSequences.first())
        }
        recentPacketSequences += nextPacketSequence
        diffTap?.onAccepted(nextPacketSequence, packet)
        nextPacketSequence++
        return offer(packet, GATT_CHUNK_HEADER_BYTES, packet.size - GATT_CHUNK_HEADER_BYTES)
    }

    fun queueState(): Pair<Int, Int> = chunks.size to capacity

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        require(targetOffset >= 0 && length >= 0 && targetOffset + length <= target.size)
        if (length == 0) return 0
        while (offset >= current.end) {
            current = try {
                chunks.take()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("GATT input interrupted", interrupted)
            }
            offset = current.start
            if (current === CLOSED_CHUNK) {
                lostChunkReason?.let { throw IOException(it) }
                return -1
            }
            queuedBytes.addAndGet(-(current.end - current.start))
        }
        val count = minOf(length, current.end - offset)
        current.bytes.copyInto(target, targetOffset, offset, offset + count)
        offset += count
        return count
    }

    /** Bytes already received but not yet read (lets link self-measurement exclude buffered data). */
    override fun available(): Int = (current.end - offset).coerceAtLeast(0) + queuedBytes.get().coerceAtLeast(0)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        diffTap?.let { tap ->
            tap.close()
            HopIntegrityResendDiff.unregister(tap)
        }
        recovery?.close()
        synchronized(this) {
            pendingPackets.clear()
            parkedBytes = 0
        }
        chunks.clear()
        chunks.offer(CLOSED_CHUNK)
    }

    private companion object {
        const val PIPE_CHUNKS = 4_096

        /** About 16 s of a 500 kbps stream, or 1 MiB of small records: far above a healthy reader's backlog. */
        const val MAX_QUEUED_BYTES = 1_048_576
        const val RECENT_PACKET_HISTORY = 4_096
        const val MAX_REORDERED_PACKETS = 4_096
        const val MAX_REORDERED_PACKET_DISTANCE = 4_096
        val CLOSED_CHUNK = Chunk(ByteArray(0), 0, 0)
    }
}

/**
 * Lost GATT chunks (default on; DEBUG control arm `test_gatt_gap_fast_fail=false`).
 *
 * 2026-10-20 (win-userflow qw-packaged redmi-stranger-3, -5, -12): a Redmi Note 9S (Android 11, legacy stack) receiving
 * notifications from a Windows PC logged "packet_fragmenter: L2CAP packet too small (6 < 4). Dropping it" then "got
 * continuation for unknown packet. Dropping it": the PC sent an ACL start fragment shorter than the 4-byte L2CAP header
 * (allowed since Core spec erratum 10216), and the host dropped the whole notification. AOSP's legacy fragmenter
 * (system/bt hci/src/packet_fragmenter.cc, android10/11/12 release branches) drops only a 0-1 byte start ("invalid
 * acl_length"); the Redmi's "< 4" check is vendor code. The GD assembler (system/gd/hci/acl_manager/assembler.h) waits
 * for the rest of the header. The pipe treated the gap as reordering and parked every later chunk, so nothing was
 * delivered until the 8 s liveness check ended the call. The sender never reorders (a busy write retries in place); only
 * several ATT bearers (EATT) could, briefly. So a gap that lasts [GAP_MS] or holds [GAP_CHUNKS] later chunks is a loss,
 * and the pipe fails at once with this reason. Recovery: DECISIONS "Design GR".
 */
internal object ChunkGapPolicy {
    const val GAP_MS = 1_000L
    const val GAP_CHUNKS = 128

    fun lost(heldMs: Long, parkedChunks: Int): Boolean = heldMs >= GAP_MS || parkedChunks >= GAP_CHUNKS

    fun reason(missing: Int, parkedChunks: Int, heldMs: Long): String =
        "GATT chunk $missing was lost ($parkedChunks later chunks waited $heldMs ms)"
}

internal abstract class ChunkedGattOutputStream : OutputStream() {
    protected abstract val payloadBytes: Int
    protected abstract fun sendPacket(value: ByteArray)
    private var nextPacketSequence = 0

    /** Experimental lost-chunk recovery for this link ([GattChunkRecovery]); null when off. */
    protected open val recovery: GattChunkRecovery? = null

    /** The ATT value limit: payload plus the 5-byte chunk header. */
    internal val valueLimitBytes: Int get() = payloadBytes + GATT_CHUNK_HEADER_BYTES

    /** Sends queued recovery values (control values, resends) while the data writer is idle; never on a callback thread. */
    @Synchronized
    internal fun sendPriority() {
        recovery?.drain(::sendPacket)
    }

    /**
     * SDK whose LE ACL segment miscount the packet sizes must avoid (Experimental `test_gatt_payload`, SDK 31-32), or 0
     * for no guard. See [gattMiscountSafeValueBytes].
     */
    protected open val miscountGuardSdk: Int = 0

    @Synchronized
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val wanted = minOf(remaining, payloadBytes)
            val count = if (miscountGuardSdk == 0) wanted else {
                (gattMiscountSafeValueBytes(wanted + GATT_CHUNK_HEADER_BYTES, miscountGuardSdk) - GATT_CHUNK_HEADER_BYTES)
                    .coerceIn(1, wanted)
            }
            val packet = ByteArray(count + GATT_CHUNK_HEADER_BYTES)
            packet[0] = TYPE_DATA
            encodeChunkSequence(nextPacketSequence, packet)
            bytes.copyInto(
                packet,
                GATT_CHUNK_HEADER_BYTES,
                sourceOffset,
                sourceOffset + count,
            )
            val recovering = recovery
            // Requests and resends go before new data; the history keeps its own copy of every data value.
            recovering?.drain(::sendPacket)
            recovering?.onDataSent(nextPacketSequence, packet)
            sendPacket(packet)
            nextPacketSequence++
            sourceOffset += count
            remaining -= count
        }
    }

    override fun flush() = Unit

    protected companion object {
        const val TYPE_DATA: Byte = 1
    }
}

private class GattClientOutputStream(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    mtu: Int,
    private val acknowledgedWrites: Boolean,
    onWriteTiming: ((String) -> Unit)?,
    private val peerAddress: String? = null,
    override val recovery: GattChunkRecovery? = null,
    /** Called once when a busy answer with nothing in flight shows the client is wedged (see [BusyWedgeCheck]). */
    private val onWedged: (String) -> Unit = {},
) : ChunkedGattOutputStream() {
    override val payloadBytes = gattChunkPayloadBytes(mtu, DebugTunables.experimentalAlignedGattWrites, GattSwitches.payload)
    override val miscountGuardSdk: Int =
        if (GattSwitches.payload != GattSwitches.Payload.DEFAULT) Build.VERSION.SDK_INT else 0
    /** Writes the stack accepted whose callback has not arrived: this client's own share of the write permit. */
    private val inFlight = AtomicInteger()
    private val byteWindow = GattByteWindow.fromSwitches()
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeStatus = BluetoothGatt.GATT_FAILURE
    private val closed = AtomicBoolean(false)
    private var nextWriteAtNs = 0L
    private val writeTiming = onWriteTiming?.takeIf { BuildConfig.DEBUG }?.let(::GattWriteTiming)
    @Volatile private var writeCallbackAtNs = 0L

    fun onWrite(status: Int) {
        inFlight.decrementAndGet()
        if (writeTiming != null) writeCallbackAtNs = System.nanoTime()
        writeStatus = status
        writeLatch?.countDown()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun sendPacket(value: ByteArray) {
        if (closed.get()) throw IOException(GATT_OUTPUT_CLOSED_MESSAGE)
        val writeType = if (acknowledgedWrites) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        var status = writeOnce(value, writeType)
        gattWriteFallbackType(acknowledgedWrites, status)?.let { fallbackType ->
            status = writeOnce(value, fallbackType)
        }
        gattSendFailure("GATT video write", status, closed.get())?.let { throw IOException(it) }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeOnce(value: ByteArray, writeType: Int): Int {
        paceWrite(value.size)
        byteWindow?.acquire(value.size)
        val latch = CountDownLatch(1)
        writeLatch = latch
        writeStatus = BluetoothGatt.GATT_FAILURE
        val timing = writeTiming
        val startedNs = if (timing != null) System.nanoTime() else 0L
        if (timing != null) writeCallbackAtNs = 0L
        var apiReturnedNs: Long? = null
        var outcome = "aborted"
        try {
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeWhenNotBusy(value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic).also { accepted ->
                    if (accepted) {
                        inFlight.incrementAndGet()
                    } else if (wedgeCheck?.wedged() == true) {
                        // Before API 33 a refused write is a bare false; with nothing in flight and no other local holder
                        // the client's busy flag is stuck (aosp-bt-versions GATT-02). The link ends either way.
                        onWedged("false-return inFlight=${inFlight.get()} otherHolders=${otherLocalClients()}")
                        throw IOException(GATT_CLIENT_WEDGED_MESSAGE)
                    }
                }
            }
            if (timing != null) apiReturnedNs = System.nanoTime()
            if (!queued) {
                outcome = "rejected"
                throw IOException("GATT video write queue rejected a packet")
            }
            if (!latch.await(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                outcome = "timeout"
                throw IOException("GATT video write timed out")
            }
            writeLatch = null
            byteWindow?.onSent(value.size)
            outcome = if (writeStatus == BluetoothGatt.GATT_SUCCESS) "success" else "status=$writeStatus"
            return writeStatus
        } finally {
            timing?.record(value.size, startedNs, apiReturnedNs, writeCallbackAtNs.takeIf { it != 0L },
                System.nanoTime(), outcome)
        }
    }

    /**
     * API 33+: the stack holds one write permit per peer address for every client and app
     * (GattService.writeCharacteristic). A busy return is retried briefly (registry entry
     * gatt-write-busy-retry) instead of failing the link; the write is the same, so order holds.
     */
    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun writeWhenNotBusy(value: ByteArray, writeType: Int): Int {
        val outcome = retryBusyGattWrite(
            busyCode = BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
            retry = busyRetry,
            write = { gatt.writeCharacteristic(characteristic, value, writeType) },
            sleepMs = { ms -> Thread.sleep(ms) },
            nowNs = System::nanoTime,
            closed = closed::get,
            wedge = wedgeCheck,
        )
        if (outcome.retries > 0) Log.i("BluetoothVideo", "GATT write busy: retries=${outcome.retries} | result=${outcome.result}")
        if (outcome.wedged) {
            onWedged("busy=${outcome.result} inFlight=${inFlight.get()} otherHolders=${otherLocalClients()}")
            throw IOException(GATT_CLIENT_WEDGED_MESSAGE)
        }
        if (outcome.result == BluetoothStatusCodes.SUCCESS) inFlight.incrementAndGet()
        return outcome.result
    }

    private fun otherLocalClients(): Int = (GattResourceRegistry.shared.liveClientsFor(peerAddress) - 1).coerceAtLeast(0)

    /**
     * The registry step (the pre-batch 2 ms), or 25 ms in the Experimental `test_gatt_busy_wedge=recover` arm. The
     * framework already retried 5 x 10 ms inside the call, so the faster loop only adds binder load.
     */
    private val busyRetry: GattWriteBusyRetry? =
        runCatching { BluetoothTransportPolicy.writeBusyRetry() }.getOrNull()
            ?.let { if (GattSwitches.busyWedgeRecovery) it.copy(stepMs = GattWriteBusyPolicy.STEP_MS) else it }

    /** Wedge classification: registry entry android13-14-gatt-client-wedged-after-busy (SDK 33-34) and the switch. */
    private val wedgeCheck: BusyWedgeCheck? =
        if (!GattSwitches.busyWedgeRecovery) null
        else runCatching { GattLinkPolicy.wedgeRecovery() }.getOrNull()?.let {
            BusyWedgeCheck(
                ownOutstanding = { inFlight.get() > 0 },
                otherLocalHolder = { otherLocalClients() > 0 },
            )
        }

    private fun paceWrite(packetBytes: Int) {
        if (!acknowledgedWrites) return
        val nowNs = System.nanoTime()
        val waitNs = nextWriteAtNs - nowNs
        if (waitNs > 0L) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("GATT video write pacing interrupted", interrupted)
            }
        }
        nextWriteAtNs = maxOf(System.nanoTime(), nextWriteAtNs) +
            gattWritePacingIntervalNs(packetBytes, LEGACY_GATT_CALL_BYTES_PER_SECOND)
    }

    override fun close() {
        closed.set(true)
        recovery?.close()
        writeLatch?.countDown()
    }
}

/**
 * ATT value bytes per GATT write or notification. By default the largest value the MTU allows,
 * capped at the 512-byte attribute maximum (MTU 517 -> 512). The ATT PDU (3-byte opcode/handle +
 * value) travels in an L2CAP PDU with a 4-byte header, and the link layer carries at most 251 octets
 * per data PDU with data length extension (Core Vol 6 Part B 4.5.10); an LL PDU never carries parts
 * of two L2CAP PDUs. A 512-byte value is a 519-byte L2CAP PDU: three LL PDUs of 251 + 251 + 17
 * octets, the last nearly empty but costing a full PDU slot and inter-frame spacing. Android 14+
 * always negotiates MTU 517 and ignores later MTU requests (Android 14 behaviour changes), so the
 * request MTU cannot fix this on newer clients. Android CoC is already aligned: senders cut K-frames
 * to the peer MPS - 4 (AOSP l2c_fcr.cc), and the MPS is one 251-octet LE buffer.
 *
 * Experimental (`test_gatt_aligned_writes`, off): size each value so its L2CAP PDU fills whole
 * 251-octet PDUs (495 bytes = 2 PDUs at MTU >= 498, 244 bytes = 1 PDU at MTU >= 247): 245 instead of
 * about 170 payload bytes per LL PDU, +12% airtime efficiency at 2M, up to +44% where a controller
 * caps PDUs per connection event. Links without data length extension (27-octet PDUs) are unaffected.
 */
internal fun gattValueBytes(
    mtu: Int,
    alignToLePdus: Boolean,
    payload: GattSwitches.Payload = GattSwitches.Payload.DEFAULT,
): Int {
    val maxValue = (mtu - ATT_OVERHEAD).coerceIn(1, MAX_ATTRIBUTE_BYTES)
    // Experimental `test_gatt_payload` (G-09): 244 = one full 251-octet LE data PDU, 495 = two; never above the MTU's value.
    when (payload) {
        GattSwitches.Payload.ONE_PDU ->
            return minOf(maxValue, LE_MAX_DATA_PDU_OCTETS - ATT_OVERHEAD - L2CAP_BASIC_HEADER_BYTES)
        GattSwitches.Payload.TWO_PDUS ->
            if (maxValue >= 2 * LE_MAX_DATA_PDU_OCTETS - ATT_OVERHEAD - L2CAP_BASIC_HEADER_BYTES) {
                return 2 * LE_MAX_DATA_PDU_OCTETS - ATT_OVERHEAD - L2CAP_BASIC_HEADER_BYTES
            }
        GattSwitches.Payload.DEFAULT -> Unit
    }
    if (!alignToLePdus && payload == GattSwitches.Payload.DEFAULT) return maxValue
    val wholePdus = (maxValue + ATT_OVERHEAD + L2CAP_BASIC_HEADER_BYTES) / LE_MAX_DATA_PDU_OCTETS
    return if (wholePdus >= 1) {
        wholePdus * LE_MAX_DATA_PDU_OCTETS - ATT_OVERHEAD - L2CAP_BASIC_HEADER_BYTES
    } else {
        maxValue
    }
}

/** App payload bytes per GATT packet after the 5-byte chunk header. */
internal fun gattChunkPayloadBytes(
    mtu: Int,
    alignToLePdus: Boolean,
    payload: GattSwitches.Payload = GattSwitches.Payload.DEFAULT,
): Int = (gattValueBytes(mtu, alignToLePdus, payload) - GATT_CHUNK_HEADER_BYTES).coerceAtLeast(1)

/**
 * Android 12 (SDK 31-32) counts the LE ACL segments of an L2CAP PDU from the buffer size including the 4-byte HCI
 * header instead of the data size (AOSP ab2c1e84fc; android12 l2c_link.cc:1097-1135), so a PDU just over a multiple of
 * the LE data buffer is counted one segment short and the host's controller-credit accounting drifts. Counting by D + 4 instead of D
 * undercounts a PDU of length n whenever k*D < n <= k*(D+4) (252-255, then 503-510 octets for D = 251). An ATT value whose
 * L2CAP PDU (value + 3 + 4) lands in such a window is shortened to k*D; the rest goes in the next packet. Other SDKs and
 * the default sizes are untouched (Experimental `test_gatt_payload`).
 */
internal fun gattMiscountSafeValueBytes(valueBytes: Int, sdk: Int, dataPduOctets: Int = LE_MAX_DATA_PDU_OCTETS): Int {
    if (sdk !in 31..32 || valueBytes <= 0) return valueBytes
    val pdu = valueBytes + ATT_OVERHEAD + L2CAP_BASIC_HEADER_BYTES
    if (pdu <= dataPduOctets) return valueBytes
    val k = (pdu - 1) / dataPduOctets // pdu in (k*D, (k+1)*D]
    return if (pdu <= k * (dataPduOctets + HCI_DATA_PREAMBLE_OCTETS)) {
        (k * dataPduOctets - ATT_OVERHEAD - L2CAP_BASIC_HEADER_BYTES).coerceAtLeast(1)
    } else {
        valueBytes
    }
}

/**
 * The IOException message for a GATT write or notification whose completion latch was released, or null when it succeeded.
 * close() releases the latch without a stack status, so the status field still holds its GATT_FAILURE (257) placeholder.
 * Reporting that as "status 257" made a local close look like a stack failure: relay-showcase-20261026 trace-u1 (A15s relay,
 * GATT) logged "GATT notification failed (status 257)" on the A15s and the S24 only after the M31 had ended the call over a
 * corrupt record and the links were closing.
 */
internal fun gattSendFailure(what: String, status: Int, closedLocally: Boolean): String? = when {
    closedLocally -> GATT_OUTPUT_CLOSED_MESSAGE
    status == BluetoothGatt.GATT_SUCCESS -> null
    else -> "$what failed (status $status)"
}

internal const val GATT_OUTPUT_CLOSED_MESSAGE = "GATT output is closed"

internal fun gattWriteFallbackType(acknowledgedWrites: Boolean, status: Int): Int? =
    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE.takeIf {
        acknowledgedWrites && status == BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
    }

internal fun gattWritePacingIntervalNs(packetBytes: Int, bytesPerSecond: Int): Long {
    require(packetBytes >= 0)
    require(bytesPerSecond > 0)
    return (packetBytes * 1_000_000_000L + bytesPerSecond - 1L) / bytesPerSecond
}

private class GattServerOutputStream(
    private val server: BluetoothGattServer,
    private val device: BluetoothDevice,
    private val characteristic: BluetoothGattCharacteristic,
    mtu: Int,
    override val recovery: GattChunkRecovery? = null,
) : ChunkedGattOutputStream() {
    override val payloadBytes = gattChunkPayloadBytes(mtu, DebugTunables.experimentalAlignedGattWrites, GattSwitches.payload)
    override val miscountGuardSdk: Int =
        if (GattSwitches.payload != GattSwitches.Payload.DEFAULT) Build.VERSION.SDK_INT else 0
    private val byteWindow = GattByteWindow.fromSwitches()
    @Volatile private var notifyLatch: CountDownLatch? = null
    @Volatile private var notifyStatus = BluetoothGatt.GATT_FAILURE
    private val closed = AtomicBoolean(false)

    fun onNotificationSent(status: Int) {
        notifyStatus = status
        notifyLatch?.countDown()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override fun sendPacket(value: ByteArray) {
        if (closed.get()) throw IOException(GATT_OUTPUT_CLOSED_MESSAGE)
        byteWindow?.acquire(value.size)
        val latch = CountDownLatch(1)
        notifyLatch = latch
        notifyStatus = BluetoothGatt.GATT_FAILURE
        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, value) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = value
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
        if (!queued) throw IOException("GATT notification queue rejected a packet")
        if (!latch.await(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("GATT notification timed out")
        notifyLatch = null
        gattSendFailure("GATT notification", notifyStatus, closed.get())?.let { throw IOException(it) }
        byteWindow?.onSent(value.size)
    }

    override fun close() {
        closed.set(true)
        recovery?.close()
        notifyLatch?.countDown()
    }
}

object GattVideoClient {
    fun open(
        context: Context,
        device: BluetoothDevice,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
        onStatus: (String) -> Unit,
        acknowledgedWrites: Boolean = false,
        onWriteTiming: ((String) -> Unit)? = null,
        deferConnectionPriority: Boolean = false,
        /** Relay child connect only: Experimental RelayEstablishBudget (test_relay_establish_budget). */
        establishBudget: Boolean = false,
        /** Experimental `test_phy_after_auth`: no PHY request at setup; the caller asks through [GattVideoConnection.requestPhy]. */
        deferPhy: Boolean = false,
        /** Experimental `test_phy_move_skip_backlog`: runs after this link moved itself from 2M to 1M (stream sender only). */
        onPhyMove: () -> Unit = {},
        /** The caller's setup deadline (a call's or message's dial), null when unbounded. */
        deadline: SetupDeadline? = null,
        config: ConnectRetryConfig = ConnectRetryConfig.current(),
        /** Experimental `test_phy_direction_split`: the split remembered for this peer, asked for at setup instead of 2M/2M. */
        phySplit: GattPhySplit? = null,
    ): GattVideoConnection {
        var linkReached = false
        val startedMs = SystemClock.elapsedRealtime()
        val peerTail = device.address.takeLast(5)
        val attempts = ConnectAttempts(ConnectAttempts.Link.GATT_VIDEO, config, deadline, startedMs, establishBudget, peerTail)
        while (true) {
            val attempt = attempts.attempt
            // Break-before-make: a wedged client's peer (always) or a recent close (test_gatt_acl_wait) waits for the ACL.
            awaitPreviousAcl(context, device)
            // Review T4: the previous attempt's deferred close to this remote finishes first (bounded), so a retry never
            // opens a second client to the same remote.
            if (GattClientTeardown.pendingCloses.pending(device.address)) {
                val closed = GattClientTeardown.pendingCloses.await(device.address, GattClientTeardown.PREVIOUS_CLOSE_WAIT_MS)
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK gatt_previous_close_wait: peer=...$peerTail | attempt=$attempt | closed=$closed",
                )
            }
            // One BluetoothGatt per remote and no crossed connections (GATT-07/09: stack bugs fixed only in 15 QPR1+).
            GattResourceRegistry.shared.dialWarnings(device.address).takeIf { it.isNotEmpty() }?.let { warnings ->
                Log.i(
                    "BluetoothVideo",
                    "BENCHMARK gatt_dial_check: peer=...$peerTail | warnings=${warnings.joinToString(",")} | sdk=${Build.VERSION.SDK_INT}",
                )
            }
            val attemptStartedMs = SystemClock.elapsedRealtime()
            try {
                val connection = openOnce(
                    context,
                    device,
                    phyPreference,
                    connectionPriority,
                    onStatus,
                    acknowledgedWrites,
                    onWriteTiming,
                    deferConnectionPriority,
                    deferPhy,
                    onPhyMove,
                    attemptTimeoutMs = attempts.attemptTimeoutMs(attemptStartedMs),
                    connectPhaseMs = attempts.connectPhaseMs(),
                    phySplit = phySplit,
                )
                attempts.successLines(attemptStartedMs, SystemClock.elapsedRealtime()).forEach { Log.i("BluetoothVideo", it) }
                PoolExhaustionPolicy.shared.onConnectSuccess()
                GattPoolBackoff.recordSuccess(PoolSource.CLIENT)
                return connection
            } catch (error: IOException) {
                if (error is LinkReachedSetupFailure) linkReached = true
                val attemptElapsedMs = SystemClock.elapsedRealtime() - attemptStartedMs
                val failureClass = attempts.classify(error.message, attemptElapsedMs)
                if (failureClass != null) {
                    Log.i(
                        "BluetoothVideo",
                        "BENCHMARK gatt_connect_failure_class: peer=...$peerTail | status=${leSetupStatus(error.message)} | " +
                            "elapsedMs=$attemptElapsedMs | class=${failureClass.label} | sdk=${Build.VERSION.SDK_INT}",
                    )
                }
                // A refused registration opens one backoff step (one log line); automatic callers consult the hold.
                GattPoolBackoff.noteConnectFailure(error.message, "gatt-video-client")
                GattNativeClientWedge.onCentralConnectFailure(error.message, attemptElapsedMs, "gatt-video-client")
                if (poolFailureRetry(device, error.message, attempt, onStatus)) {
                    attempts.countPoolRetry()
                    Thread.sleep(ConnectAttempts.Link.GATT_VIDEO.retryDelayMs)
                    continue
                }
                val retryable = runCatching { BluetoothTransportPolicy.retryableConnectStatuses() }
                    .getOrDefault(setOf(BluetoothTransportPolicy.GATT_ERROR_133))
                // One decision (ConnectAttempts): relay establish budget, stall-retry dials, fast first-dial dials, ordinary rule.
                val decision = attempts.onFailure(
                    error,
                    failureClass,
                    attemptStartedMs,
                    SystemClock.elapsedRealtime(),
                    classifiedRetryable = isRetryableGattVideoFailure(error.message, retryable),
                    linkReached = linkReached,
                )
                decision.lines.forEach { Log.i("BluetoothVideo", it) }
                when (decision) {
                    is ConnectAttempts.Decision.GiveUp -> throw decision.error
                    is ConnectAttempts.Decision.Dial -> {
                        onStatus(decision.notice)
                        Thread.sleep(decision.delayMs)
                    }
                }
            }
        }
    }

    /**
     * Break-before-make (G-04a): waits (bounded 2 s, off the main thread) for the previous ACL to this peer to go down,
     * when a wedged client or a recent close asks for it. Nothing to wait for costs nothing.
     */
    @SuppressLint("MissingPermission")
    private fun awaitPreviousAcl(context: Context, device: BluetoothDevice) {
        val reason = GattLinkLifetime.redialWaitReason(device.address) ?: return
        val manager = runCatching { context.applicationContext.getSystemService(BluetoothManager::class.java) }.getOrNull()
        GattLinkLifetime.awaitAclDown(
            watch = AclDownWatch.forDevice(context, device),
            mayBlock = Looper.myLooper() != Looper.getMainLooper(),
            stillUp = { GattLinkLifetime.isGattConnected(manager, device.address) },
            reason = reason,
        )
    }

    /**
     * Classifies a connect failure that names a pool (257: registration pool, 128: client pool) and feeds the
     * consecutive-failure streak. Returns true when one retry is justified because idle clients of this app were closed
     * (G-02); otherwise the failure is surfaced by the caller. Registry entry gatt-registration-pool-exhaustion.
     */
    private fun poolFailureRetry(device: BluetoothDevice, message: String?, attempt: Int, onStatus: (String) -> Unit): Boolean {
        if (runCatching { GattLinkPolicy.poolGuard() }.getOrNull() == null) return false
        val status = leSetupStatus(message) ?: return false
        val registry = GattResourceRegistry.shared
        val policy = PoolExhaustionPolicy.shared
        val verdict = if (isRetryableLeSetupStatus(message, POOL_STATUSES, GATT_VIDEO_CONNECTION_FAILURES)) {
            GattResourceRegistry.classifyStatus(status)
        } else {
            PoolVerdict.NONE
        }
        val action = policy.onConnectFailure(
            status = if (verdict == PoolVerdict.NONE) -1 else status,
            peer = device.address,
            peerInRange = true,
            ownIdleClients = registry.idleClosable(IDLE_CLIENT_AGE_MS),
        )
        if (verdict != PoolVerdict.NONE) {
            Log.i("BluetoothVideo", registry.line("pool_failure") + " | verdict=${verdict.label} | status=$status | action=$action")
        }
        if (policy.restartGuidanceDue()) {
            Log.i("BluetoothVideo", "BENCHMARK gatt_restart_guidance: ${PoolExhaustionPolicy.GUIDANCE}")
        }
        if (action != PoolExhaustionPolicy.Action.RETRY_AFTER_CLOSING_IDLE || attempt >= ConnectAttempts.Link.GATT_VIDEO.maxAttempts) return false
        val closedCount = registry.closeIdle(IDLE_CLIENT_AGE_MS)
        Log.i("BluetoothVideo", "GATT pool exhausted: closed $closedCount idle client(s) of this app before one retry")
        if (closedCount > 0) onStatus("GATT video: ${verdict.label}; retrying once after closing idle clients")
        return closedCount > 0
    }

    /** A write came back busy with nothing in flight: end this client, the next connect to the peer waits for the ACL. */
    @SuppressLint("MissingPermission")
    private fun onClientWedged(gatt: BluetoothGatt, reason: String) {
        val address = gatt.device?.address
        GattLinkLifetime.requireAclDrop(address, "wedged-client")
        Log.i(
            "BluetoothVideo",
            "BENCHMARK gatt_client_wedged: peer=...${address?.takeLast(5) ?: "?"} | $reason | sdk=${Build.VERSION.SDK_INT} | " +
                "action=end_link_then_wait_for_acl_drop_before_reconnect",
        )
        runCatching { gatt.disconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun openOnce(
        context: Context,
        device: BluetoothDevice,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
        onStatus: (String) -> Unit,
        acknowledgedWrites: Boolean,
        onWriteTiming: ((String) -> Unit)?,
        deferConnectionPriority: Boolean = false,
        deferPhy: Boolean = false,
        onPhyMove: () -> Unit = {},
        /** This attempt's bound ([ConnectAttempts.attemptTimeoutMs]: 12 s unless the unified policy clips it). */
        attemptTimeoutMs: Long = ConnectAttempts.Link.GATT_VIDEO.attemptTimeoutMs,
        /** Experimental unified policy only: the attempt ends if not connected after this long. */
        connectPhaseMs: Long? = null,
        /** Experimental `test_phy_direction_split` ([GattVideoClient.open]). */
        phySplit: GattPhySplit? = null,
    ): GattVideoConnection {
        val result = AtomicReference<GattVideoConnection?>()
        val error = AtomicReference<String?>()
        val complete = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val connectionSetupStarted = AtomicBoolean(false)
        val serviceDiscoveryStarted = AtomicBoolean(false)
        val serviceConfigured = AtomicBoolean(false)
        val cacheRefreshAttempted = AtomicBoolean(false)
        val deferPriority = BuildConfig.DEBUG && deferConnectionPriority
        val deferredPriorityRequested = if (deferPriority) AtomicBoolean(false) else null
        val setupStartedNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val setupAttemptId = if (BuildConfig.DEBUG) UUID.randomUUID().toString() else ""
        val setupStage = if (BuildConfig.DEBUG) AtomicReference("created") else null
        val setupLogCount = if (BuildConfig.DEBUG) AtomicInteger(0) else null
        val latch = CountDownLatch(1)
        val mtuRequestedAtNs = AtomicLong(0L)
        // MTU wait verdicts (GattMtuWait): queued behind the platform's discovery vs dropped.
        val mtuWait = GattMtuWait(
            probeEnabled = DebugTunables.gattMtuProbe && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        )
        val teardown = GattTeardownSignal()
        val silence = LinkSilence()
        val lastNonSuccessStatus = AtomicInteger(-1)
        /** The GATT status of the disconnect callback (8 = link-layer connection timeout); -1 until one arrives. */
        val disconnectStatus = AtomicInteger(-1)
        val recovery = GattChunkRecovery.forLink("client")
        val input = GattBytePipe(recovery = recovery) { failure -> onStatus("GATT video input $failure") }
        var mtu = DEFAULT_MTU
        var phySummary = "PHY pending"
        var gatt: BluetoothGatt? = null
        var output: GattClientOutputStream? = null
        val adaptivePhyRef = AtomicReference<AdaptivePhyLink?>()
        // Experimental test_phy_direction_split: a remembered split, only where the setup request carries it (the 2M default).
        val startSplit = phySplit?.takeIf { split ->
            !deferPhy && phyRequestMasks(phyPreference, split).let { it.txMask != it.rxMask }
        }
        // Experimental test_ll_gate (P-01): one MTU, discovery, PHY or connection-update request in flight on this link.
        // A pass-through (requests start at once, as before) unless the switch is on.
        val gate = newLinkGate("gatt-video", LeLinkRole.CENTRAL, device.address)
        fun adaptivePhy(gatt: BluetoothGatt): AdaptivePhyLink {
            adaptivePhyRef.get()?.let { return it }
            adaptivePhyRef.compareAndSet(
                null,
                AdaptivePhyLink(
                    "gatt", gatt.device?.address, gatt, restorePriority = connectionPriority,
                    peerHeard = { input.heardWithin(AdaptivePhyLink.PEER_HEARD_MS) },
                    onMovedTo1M = onPhyMove,
                    gate = gate,
                    peerQuietMs = { input.quietMs() },
                    startSplit = startSplit,
                ),
            )
            LeChildInterval.register(gatt.device?.address, gatt)
            return adaptivePhyRef.get()!!
        }
        val activeGattRef = AtomicReference<BluetoothGatt?>()
        // One requested-vs-observed PHY/MTU/priority/interval line (BluetoothTransportPolicy).
        val linkDiag = LeLinkDiagnostics.create("gatt-video", device.address).also { it.requestedPhy = phyPreference.label }
        // Experimental phy_after_priority (off): the setup PHY waits while the link sits outside the requested priority window.
        val phyHold = PhyAfterPriority(
            link = "gatt-video",
            schedule = { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
            nowMs = SystemClock::elapsedRealtime,
            log = { line -> Log.i("BluetoothVideo", line) },
        )
        val priorityKeeper = LeConnectionPriorityKeeper(
            link = "gatt-video",
            sendRequest = { priority -> activeGattRef.get()?.requestConnectionPriority(priority.requestValue) == true },
            schedule = { delayMs, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delayMs) },
            active = { !cancelled.get() && !teardown.disconnected },
            log = { line -> Log.i("BluetoothVideo", line) },
            gate = gate,
            phyHold = phyHold,
        )

        fun logSetup(stage: String, details: String = "", terminal: Boolean = false) {
            val lastStage = setupStage ?: return
            if (!terminal && complete.get()) return
            val previousStage = lastStage.getAndSet(stage)
            if (!terminal && setupLogCount!!.getAndIncrement() >= 64) return
            Log.i(
                "GattVideoSetup",
                "attempt=$setupAttemptId elapsedMs=${(System.nanoTime() - setupStartedNs) / 1_000_000L} " +
                    "stage=$stage last=$previousStage $details",
            )
        }

        fun finish(connection: GattVideoConnection? = null, message: String? = null) {
            if (cancelled.get()) return
            if (!complete.compareAndSet(false, true)) return
            result.set(connection)
            error.set(message)
            logSetup("finish", "success=${connection != null} failure=${message ?: "none"}", terminal = true)
            latch.countDown()
        }

        /** G-07: a CONNECTED callback for an attempt that was already cancelled must not leave a live client behind. */
        fun cleanUpStaleConnected(stale: BluetoothGatt) {
            if (!runCatching { GattLinkPolicy.staleConnectedCleanup() }.getOrDefault(false)) return
            if (!GattResourceRegistry.shared.needsStaleCleanup(stale)) return
            Log.i("BluetoothVideo", "GATT stale CONNECTED after cancel: closing the client | peer=...${stale.device?.address?.takeLast(5)}")
            GattClientTeardown.disconnectAndClose(stale, teardown, "gatt-video-stale")
        }

        fun discoverServicesOnce(activeGatt: BluetoothGatt) {
            if (cancelled.get()) return
            if (serviceDiscoveryStarted.compareAndSet(false, true)) {
                if (GattSwitches.gattRefresh) {
                    // Experimental `test_gatt_refresh` (G-15): hidden refresh() before the first discovery; fails closed.
                    val refreshed = refreshGattServices(activeGatt)
                    Log.i("BluetoothVideo", "GATT cache refresh before discovery: sdk=${Build.VERSION.SDK_INT} | refreshed=$refreshed")
                }
                silence.begin(LinkSilence.Stage.DISCOVERY)
                logSetup("services_request", "cacheRefresh=false")
                val queued = gate.submit(LlProcedure.SERVICE_DISCOVERY, "gatt_video_discovery") { activeGatt.discoverServices() }.accepted
                logSetup("services_return", "cacheRefresh=false queued=$queued")
                if (!queued) finish(message = "Could not discover GATT video service")
            }
        }

        val callback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(activeGatt: BluetoothGatt, status: Int, newState: Int) {
                teardown.onCallback()
                if (newState == BluetoothProfile.STATE_CONNECTED) teardown.onConnected()
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    disconnectStatus.compareAndSet(-1, status)
                    teardown.onDisconnected()
                    gate.onLinkClosed(status)
                }
                if (status != BluetoothGatt.GATT_SUCCESS) lastNonSuccessStatus.set(status)
                if (cancelled.get()) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) cleanUpStaleConnected(activeGatt)
                    return
                }
                logSetup("connection_callback", "status=$status state=$newState")
                when {
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        input.close()
                        output?.close()
                        if (!complete.get()) {
                            finish(message = "GATT video receiver disconnected (status $status)")
                        }
                    }
                    status != BluetoothGatt.GATT_SUCCESS -> finish(message = "GATT video connection failed (status $status)")
                    newState == BluetoothProfile.STATE_CONNECTED -> {
                        if (!connectionSetupStarted.compareAndSet(false, true)) return
                        activeGattRef.set(activeGatt)
                        if (!deferPriority) {
                            logSetup("priority_request", "value=${connectionPriority.requestValue} placement=connected")
                            val priorityQueued = priorityKeeper.request(connectionPriority)
                            linkDiag.onPriority(connectionPriority, priorityQueued)
                            logSetup("priority_return", "queued=$priorityQueued")
                            onStatus(
                                "GATT video requested ${connectionPriority.label} priority " +
                                    if (priorityQueued) "queued" else "failed",
                            )
                        }
                        // PHY update is deferred until the subscription completes: a PHY procedure
                        // started beside the connection update and the peer's own PHY request
                        // collided (status 42) and left a legacy stack without any callback.
                        silence.onConnected()
                        val startAttSetup = {
                            if (!cancelled.get() && !complete.get()) {
                                // Experimental DEBUG `test_gatt_request_mtu` (default 517).
                                // A peer whose last MTU change was followed by a lost link is asked for the smaller MTU (test_link_change_rollback).
                                val requestedMtu = (DebugTunables.gattRequestMtu ?: MAX_MTU).let { wanted ->
                                    LinkChangeRollbacks.mtuCap(AdaptivePhyMemory.peerKey(device.address))?.coerceAtMost(wanted) ?: wanted
                                }
                                logSetup("mtu_request", "requested=$requestedMtu")
                                linkDiag.requestedMtu = requestedMtu
                                mtuRequestedAtNs.set(System.nanoTime())
                                mtuWait.onRequested(System.nanoTime() / 1_000_000L)
                                silence.begin(LinkSilence.Stage.MTU)
                                val mtuQueued = gate.submit(LlProcedure.MTU, "gatt_video_mtu") { activeGatt.requestMtu(requestedMtu) }.accepted
                                logSetup("mtu_return", "queued=$mtuQueued")
                                if (!mtuQueued) {
                                    mtuRequestedAtNs.set(0L)
                                    discoverServicesOnce(activeGatt)
                                }
                            }
                        }
                        // STATE_CONNECTED is posted before encryption and Service Changed finish (Gadgetbridge BtLEQueue):
                        // Experimental `test_gatt_settle_ms` waits before the first GATT operation (default none).
                        val settleMs = GattSwitches.settleMs
                        if (settleMs > 0) {
                            logSetup("settle_wait", "ms=$settleMs")
                            Handler(Looper.getMainLooper()).postDelayed({ startAttSetup() }, settleMs.toLong())
                        } else {
                            startAttSetup()
                        }
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(activeGatt: BluetoothGatt, newMtu: Int, status: Int) {
                if (cancelled.get()) return
                gate.onMtuChanged(status)
                mtuRequestedAtNs.set(0L)
                mtuWait.onMtuCallback()
                silence.complete(LinkSilence.Stage.MTU)
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else DEFAULT_MTU
                linkDiag.onMtu(mtu, status)
                logSetup("mtu_callback", "status=$status reported=$newMtu effective=$mtu")
                onStatus("GATT video MTU $mtu")
                discoverServicesOnce(activeGatt)
            }

            override fun onPhyUpdate(activeGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                if (cancelled.get()) return
                mtuWait.onPlatformCallback(System.nanoTime() / 1_000_000L)
                logSetup("phy_update_callback", "status=$status tx=$txPhy rx=$rxPhy")
                linkDiag.onPhy(txPhy, rxPhy, status)
                if (complete.get()) linkDiag.log("phy_update")
                phySummary = phySummary(txPhy, rxPhy, status)
                adaptivePhy(activeGatt).onPhyUpdate(txPhy, status)
                onStatus("GATT video $phySummary")
            }

            override fun onPhyRead(activeGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
                if (cancelled.get()) return
                mtuWait.onPlatformCallback(System.nanoTime() / 1_000_000L)
                logSetup("phy_read_callback", "status=$status tx=$txPhy rx=$rxPhy")
                linkDiag.onPhy(txPhy, rxPhy, status)
                if (complete.get()) linkDiag.log("phy_read")
                phySummary = phySummary(txPhy, rxPhy, status)
                adaptivePhy(activeGatt).onPhyRead(txPhy, status)
                onStatus("GATT video $phySummary")
            }

            /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
            @Suppress("unused")
            fun onConnectionUpdated(activeGatt: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
                LeConnectionParameterLog.record(
                    "gatt-video", LeLinkRole.CENTRAL, activeGatt.device?.address, interval, latency, timeout, status,
                )
                if (!cancelled.get()) {
                    logSetup("conn_params_callback", "intervalUnits=$interval latency=$latency timeout=$timeout status=$status")
                    linkDiag.onParams(interval, latency, timeout, status)
                    if (complete.get()) linkDiag.log("conn_params")
                }
                priorityKeeper.onConnectionUpdated(status, interval)
            }

            @Suppress("DEPRECATION")
            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(activeGatt: BluetoothGatt, status: Int) {
                if (cancelled.get()) return
                gate.onServicesDiscovered(status)
                logSetup("services_callback", "status=$status")
                silence.complete(LinkSilence.Stage.DISCOVERY)
                if (!serviceConfigured.compareAndSet(false, true)) return
                val service = activeGatt.getService(GATT_DATA_SERVICE_UUID)
                val writeCharacteristic = service?.getCharacteristic(WRITE_UUID)
                val notifyCharacteristic = service?.getCharacteristic(NOTIFY_UUID)
                val descriptor = notifyCharacteristic?.getDescriptor(CCCD_UUID)
                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    (writeCharacteristic == null || notifyCharacteristic == null || descriptor == null) &&
                    cacheRefreshAttempted.compareAndSet(false, true) &&
                    refreshGattServices(activeGatt)
                ) {
                    onStatus("Refreshing a stale GATT service cache")
                    logSetup("cache_refresh", "scheduled=true")
                    serviceConfigured.set(false)
                    Handler(Looper.getMainLooper()).postDelayed(
                        {
                            if (!cancelled.get() && !complete.get()) {
                                logSetup("services_request", "cacheRefresh=true")
                                silence.begin(LinkSilence.Stage.DISCOVERY)
                                val queued = gate.submit(LlProcedure.SERVICE_DISCOVERY, "gatt_video_rediscovery") { activeGatt.discoverServices() }.accepted
                                logSetup("services_return", "cacheRefresh=true queued=$queued")
                                if (!queued) finish(message = "Could not rediscover the GATT video service")
                            }
                        },
                        GATT_CACHE_REFRESH_DELAY_MS,
                    )
                    return
                }
                if (
                    status != BluetoothGatt.GATT_SUCCESS ||
                    writeCharacteristic == null ||
                    notifyCharacteristic == null ||
                    descriptor == null
                ) {
                    finish(message = "GATT video service discovery failed (status $status)")
                    return
                }
                output = GattClientOutputStream(
                    activeGatt, writeCharacteristic, mtu, acknowledgedWrites, onWriteTiming,
                    activeGatt.device?.address,
                    recovery = recovery,
                ) { reason -> onClientWedged(activeGatt, reason) }
                logSetup("notification_request", "enabled=true")
                val notificationsEnabled = activeGatt.setCharacteristicNotification(notifyCharacteristic, true)
                logSetup("notification_return", "enabled=$notificationsEnabled")
                if (!notificationsEnabled) {
                    finish(message = "Could not enable GATT video notifications")
                    return
                }
                logSetup("descriptor_request", "cccd=true")
                silence.begin(LinkSilence.Stage.SUBSCRIBE)
                val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activeGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    activeGatt.writeDescriptor(descriptor)
                }
                logSetup("descriptor_return", "queued=$queued")
                if (!queued) finish(message = "Could not subscribe to GATT video data")
            }

            override fun onDescriptorWrite(
                activeGatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (cancelled.get()) return
                logSetup("descriptor_callback", "cccd=${descriptor.uuid == CCCD_UUID} status=$status")
                silence.complete(LinkSilence.Stage.SUBSCRIBE)
                if (descriptor.uuid != CCCD_UUID || status != BluetoothGatt.GATT_SUCCESS) {
                    finish(message = "GATT video subscription failed (status $status)")
                    return
                }
                val activeOutput = output ?: return
                if (!complete.get() && deferredPriorityRequested?.compareAndSet(false, true) == true) {
                    logSetup("priority_request", "value=${connectionPriority.requestValue} placement=subscribed")
                    val priorityQueued = priorityKeeper.request(connectionPriority)
                    linkDiag.onPriority(connectionPriority, priorityQueued)
                    logSetup("priority_return", "queued=$priorityQueued")
                    onStatus(
                        "GATT video requested ${connectionPriority.label} priority " +
                            if (priorityQueued) "queued" else "failed",
                    )
                }
                if (!complete.get()) {
                    if (deferPhy) {
                        logSetup("phy_update_deferred", "mask=${phyPreference.mask} placement=after_auth")
                    } else {
                        logSetup("phy_update_request", "mask=${phyPreference.mask} option=${phyPreference.option} placement=subscribed")
                        val requestedSplit = startSplit ?: DebugTunables.experimentalPhySplit
                        val phyMasks = phyRequestMasks(phyPreference, requestedSplit)
                        if (phyMasks.txMask != phyMasks.rxMask) {
                            val source = if (startSplit != null) " | source=memory" else ""
                            Log.i("BluetoothVideo", "BENCHMARK gatt_phy_split: link=gatt-video | requested=${requestedSplit?.debugValue} | txMask=${phyMasks.txMask} | rxMask=${phyMasks.rxMask}$source")
                        }
                        phyHold.submit("gatt_video_phy", isActive = { !cancelled.get() && !teardown.disconnected }) {
                            gate.submit(
                                LlProcedure.PHY,
                                "gatt_video_phy",
                                expectedTx = LePhy.valueForMask(phyMasks.txMask),
                                fallback = { activeGatt.setPreferredPhy(LePhy.MASK_1M, LePhy.MASK_1M, phyMasks.option); true },
                            ) {
                                activeGatt.setPreferredPhy(phyMasks.txMask, phyMasks.rxMask, phyMasks.option)
                                true
                            }
                        }
                    }
                    activeGatt.readPhy()
                }
                linkDiag.log("setup")
                GattResourceRegistry.shared.setActive(activeGatt, true)
                recovery?.start(activeOutput)
                finish(
                    GattVideoConnection(
                        input,
                        AdaptivePhyOutputStream(activeOutput, adaptivePhy(activeGatt)),
                        { phySummary },
                        input::queueState,
                        { priority -> priorityKeeper.request(priority) },
                        { disconnectStatus.get() },
                        { preference ->
                            val masks = phyRequestMasks(preference, DebugTunables.experimentalPhySplit)
                            gate.submit(LlProcedure.PHY, "gatt_video_phy_change", expectedTx = LePhy.valueForMask(masks.txMask)) {
                                runCatching {
                                    activeGatt.setPreferredPhy(masks.txMask, masks.rxMask, masks.option)
                                }.isSuccess
                            }.accepted
                        },
                        { !cancelled.get() && runCatching { activeGatt.readPhy() }.isSuccess },
                    ) {
                        cancelled.set(true)
                        GattResourceRegistry.shared.setActive(activeGatt, false)
                        // Android 10/11: close only after the disconnect finished (GattClientTeardown).
                        GattClientTeardown.disconnectAndClose(activeGatt, teardown, "gatt-video")
                    },
                )
            }

            override fun onCharacteristicWrite(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (cancelled.get()) return
                if (characteristic.uuid == WRITE_UUID) output?.onWrite(status)
            }

            @Deprecated("Deprecated in Android")
            override fun onCharacteristicChanged(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (cancelled.get()) return
                if (characteristic.uuid != NOTIFY_UUID) return
                val callbackValue = characteristic.value.copyOf()
                handleIncoming(input, callbackValue)
            }

            override fun onCharacteristicChanged(
                activeGatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (cancelled.get()) return
                if (characteristic.uuid == NOTIFY_UUID) handleIncoming(input, value)
            }
        }

        return awaitGattSetup(
            awaitCompletion = {
                logSetup("connect_request", "deferPriority=$deferPriority")
                EncoderBenchGuard.check("GattVideoTransport.connectGatt")
                gatt = GattConnectCompat.connect(device, context, callback, BluetoothDevice.TRANSPORT_LE, owner = "gatt-video")
                    ?: run {
                        logSetup("connect_return", "created=false")
                        throw IOException("Could not start GATT video connection")
                    }
                logSetup("connect_return", "created=true")
                        val connectStartedNs = System.nanoTime()
                val deadlineNs = connectStartedNs + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs)
                var completed = false
                while (!completed && System.nanoTime() < deadlineNs) {
                    // Experimental test_unified_connect_policy: an attempt not connected after the connect phase ends here.
                    if (connectPhaseMs != null && !connectionSetupStarted.get() &&
                        System.nanoTime() - connectStartedNs >= TimeUnit.MILLISECONDS.toNanos(connectPhaseMs)
                    ) {
                        logSetup("connect_phase_timeout", "phaseMs=$connectPhaseMs")
                        break
                    }
                    completed = latch.await(SETUP_POLL_MS, TimeUnit.MILLISECONDS)
                    if (!completed) {
                        silence.check()?.let { verdict ->
                            val fingerprint = runCatching { LinkSilence.fingerprint(DeviceTraits.current()) }
                                .getOrDefault("fingerprint=unavailable")
                            Log.i(
                                "BluetoothVideo",
                                LinkSilence.logLine(
                                    verdict, "gatt-video", device.address.takeLast(5),
                                    lastNonSuccessStatus.get().takeIf { it >= 0 }, fingerprint, Build.VERSION.SDK_INT,
                                ),
                            )
                            logSetup("link_silence", verdict.describe())
                            if (verdict.kind == LinkSilence.Kind.REMOTE_GATT_FULL && GattSwitches.remoteFullFailFast) {
                                finish(message = GATT_REMOTE_FULL_MESSAGE)
                                completed = true
                            }
                        }
                    }
                    val mtuRequested = mtuRequestedAtNs.get()
                    if (!completed && mtuRequested != 0L) {
                        val nowMs = System.nanoTime() / 1_000_000L
                        val waitedMs = (System.nanoTime() - mtuRequested) / 1_000_000L
                        when (val verdict = mtuWait.poll(nowMs)) {
                            GattMtuWait.Verdict.WAIT -> Unit
                            GattMtuWait.Verdict.SEND_PROBE -> {
                                // A controller read: it bypasses the platform's queued discovery, and its
                                // callback needs the same client record the MTU request needs.
                                val probing = activeGattRef.get()
                                if (probing == null) {
                                    mtuWait.onProbeUnavailable()
                                } else {
                                    logSetup("mtu_probe_request", "waitedMs=$waitedMs")
                                    mtuWait.onProbeSent(nowMs)
                                    runCatching { probing.readPhy() }.onFailure { mtuWait.onProbeUnavailable() }
                                }
                            }
                            else -> {
                                logSetup(
                                    "mtu_timeout",
                                    "waitedMs=$waitedMs reason=$verdict platformAlive=${mtuWait.platformAlive} " +
                                        "probeSent=${mtuWait.probeSent}",
                                )
                                finish(message = GATT_MTU_STALLED_MESSAGE)
                                completed = true
                            }
                        }
                    }
                }
                if (!completed) logSetup("await_timeout", "deadlineSeconds=${attemptTimeoutMs / 1_000L}")
                completed
            },
            connection = result::get,
            failure = error::get,
            // Experimental relay_establish_keep_2m: the link existed once the connection callback reported connected.
            linkReached = { connectionSetupStarted.get() },
            closeFailedSetup = {
                cancelled.set(true)
                complete.set(true)
                logSetup("cancel", "connectionPresent=${result.get() != null}", terminal = true)
                input.close()
                output?.close()
                gatt?.let { GattClientTeardown.disconnectAndClose(it, teardown, "gatt-video-setup") }
            },
        )
    }

    private val POOL_STATUSES = setOf(GattResourceRegistry.STATUS_REGISTRATION_EXHAUSTED, GattResourceRegistry.STATUS_NO_RESOURCES)
}

internal fun awaitGattSetup(
    awaitCompletion: () -> Boolean,
    connection: () -> GattVideoConnection?,
    failure: () -> String?,
    closeFailedSetup: () -> Unit,
    linkReached: () -> Boolean = { false },
): GattVideoConnection {
    var handedOff = false
    try {
        if (!awaitCompletion()) throw IOException("Timed out connecting GATT video")
        val accepted = connection() ?: throw IOException(failure() ?: "Could not connect GATT video")
        handedOff = true
        return accepted
    } catch (error: IOException) {
        throw carryLinkReached(error, linkReached())
    } finally {
        if (!handedOff) closeFailedSetup()
    }
}

internal data class BusyWriteOutcome(val result: Int, val retries: Int, val wedged: Boolean = false)

/**
 * What decides a busy answer is a wedged client rather than contention (Android 13-14, registry
 * android13-14-gatt-client-wedged-after-busy). [ownOutstanding]: this client has a write the stack accepted whose callback
 * has not arrived, so the busy flag is its own and waiting for the callback is correct. [otherLocalHolder]: another
 * MeshLive client to the same peer exists and may hold the stack's per-peer write permit, so a short retry is right.
 * With neither, the BluetoothGatt object itself is stuck (mDeviceBusy never cleared, AOSP cf66648aa2; or a permit leaked
 * by a client that disconnected mid-write, 67158cb957) and no number of retries can succeed.
 */
internal class BusyWedgeCheck(val ownOutstanding: () -> Boolean, val otherLocalHolder: () -> Boolean) {
    fun wedged(): Boolean = !ownOutstanding() && !otherLocalHolder()
}

/**
 * Issues one GATT write and repeats the same write while the stack answers [busyCode], every
 * [GattWriteBusyRetry.stepMs] until [GattWriteBusyRetry.maxWaitMs] (registry gatt-write-busy-retry).
 * Without [retry] the first answer is final (pre-audit behaviour). Never reorders or skips data.
 * With [wedge] a busy answer that [BusyWedgeCheck] calls wedged is returned at once with [BusyWriteOutcome.wedged] set
 * and no retry: the caller ends the link and a new client takes over.
 */
internal fun retryBusyGattWrite(
    busyCode: Int,
    retry: GattWriteBusyRetry?,
    write: () -> Int,
    sleepMs: (Long) -> Unit,
    nowNs: () -> Long,
    closed: () -> Boolean,
    wedge: BusyWedgeCheck? = null,
): BusyWriteOutcome {
    var result = write()
    if (result == busyCode && wedge != null && wedge.wedged()) return BusyWriteOutcome(result, 0, wedged = true)
    if (result != busyCode || retry == null) return BusyWriteOutcome(result, 0)
    val deadlineNs = nowNs() + TimeUnit.MILLISECONDS.toNanos(retry.maxWaitMs)
    var retries = 0
    while (result == busyCode && nowNs() < deadlineNs && !closed()) {
        try {
            sleepMs(retry.stepMs)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
        retries++
        result = write()
    }
    return BusyWriteOutcome(result, retries)
}

internal const val GATT_MTU_STALLED_MESSAGE = "GATT video MTU exchange stalled"

/** A write came back busy with nothing in flight on this client: the client is wedged (not retryable on the same ACL). */
internal const val GATT_CLIENT_WEDGED_MESSAGE = "GATT video client wedged (write busy with nothing in flight)"

/** Connected, but the peer never answered ATT: its GATT block pool is probably full (Experimental fail-fast only). */
internal const val GATT_REMOTE_FULL_MESSAGE = "GATT video remote GATT full (no ATT answer)"

/**
 * [retryableStatuses] defaults to the pre-audit rule (133 only); production passes
 * BluetoothTransportPolicy.retryableConnectStatuses() (registry entry le-connect-retry-link-statuses).
 */
internal fun isRetryableGattVideoFailure(
    message: String?,
    retryableStatuses: Set<Int> = setOf(BluetoothTransportPolicy.GATT_ERROR_133),
): Boolean =
    message == GATT_MTU_STALLED_MESSAGE ||
        isRetryableLeSetupStatus(message, retryableStatuses, GATT_VIDEO_CONNECTION_FAILURES)

private val GATT_VIDEO_CONNECTION_FAILURES =
    listOf("GATT video connection failed (status ", "GATT video receiver disconnected (status ")

class GattVideoServer(
    context: Context,
    private val adapter: BluetoothAdapter,
    private val announcement: DiscoveryAnnouncement,
    private val phyPreference: BlePhyPreference,
    private val onStatus: (String) -> Unit,
    private val onConnection: (GattVideoConnection) -> Unit,
) : Closeable {
    /** True only for a call endpoint's own receiver set (not a relay, stream or renewal): [CallReadyAdvertisingHold]. Set before [start]. */
    @Volatile internal var callEndpoint: Boolean = false

    /**
     * Experimental in-call link renewal (Design SR): the set advertises only this 128-bit service UUID (no announcement,
     * no manufacturer data, an empty scan response) instead of [announcement]. Null: the normal call or stream advert.
     * Set through [useRenewalAdvert] before [start].
     */
    @Volatile private var renewalServiceUuid: java.util.UUID? = null

    /**
     * Dual listening (DualListening.kt): false registers the service without any advertising set, so a CoC
     * endpoint's one connectable set stays the only one and a GATT central reaches this service at that
     * address. True (the default, every other caller): advertises as always. Set before [start].
     */
    @Volatile internal var advertise: Boolean = true

    /**
     * Review T10: advertise again when this phone closes the admitted connection itself (stream viewers set it; a call
     * endpoint manages its own advertising and keeps it off).
     */
    @Volatile internal var readvertiseOnLocalClose: Boolean = false

    /** Review B3: a central that connected and is not admitted yet (in setup), and since when (elapsedRealtime). */
    @Volatile private var setupCentral: String? = null
    @Volatile private var setupCentralAtMs = 0L

    /** Review B3: since when a central has been connected but not yet admitted, or null. */
    fun setupCentralSinceMs(): Long? = if (setupCentral != null && activeDevice == null) setupCentralAtMs else null

    /**
     * R1 C9 (2026-11-10): a central is connected, in setup or admitted, so closing this server now would orphan its
     * link (the call endpoint's epoch-boundary restart waits for it, BoundaryRestartPlan).
     */
    fun linkInFlight(): Boolean = !closed.get() && (setupCentral != null || activeDevice != null)

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val advertiseHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var serviceLatch: CountDownLatch? = null
    private var serviceStatus = BluetoothGatt.GATT_FAILURE
    @Volatile private var server: BluetoothGattServer? = null
    @Volatile private var activeDevice: BluetoothDevice? = null
    @Volatile private var input: GattBytePipe? = null
    @Volatile private var output: GattServerOutputStream? = null
    @Volatile private var mtu = DEFAULT_MTU
    @Volatile private var currentPhySummary = "PHY pending"
    @Volatile private var advertising = false

    /** Keeps its random address through the low-latency -> balanced step (StableAdvertisingSet). */
    @Volatile private var advertisement = newAdvertisement(announcement)

    /** Experimental in-call link renewal: advertise only [uuid] (call before [start]). */
    internal fun useRenewalAdvert(uuid: java.util.UUID) {
        synchronized(this) {
            check(!advertising) { "The renewal advert is chosen before the server starts" }
            renewalServiceUuid = uuid
            advertisement = newAdvertisement(announcement)
        }
    }

    private fun newAdvertisement(value: DiscoveryAnnouncement) = StableAdvertisingSet(
        adapter,
        connectable = true,
        data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(renewalServiceUuid ?: BleL2capControlProtocol.SERVICE_UUID))
            .build(),
        scanResponse = if (renewalServiceUuid != null) {
            AdvertiseData.Builder().build()
        } else {
            AdvertiseData.Builder()
                .addManufacturerData(
                    BleL2capControlProtocol.MESH_MANUFACTURER_ID,
                    DiscoveryAnnouncementCodec.encode(value),
                )
                .build()
        },
        label = if (renewalServiceUuid != null) "GATT renewal" else "GATT video",
        onStatus = onStatus,
        onLost = ::onAdvertisingLost,
        nonDiscoverable = NonDiscoverableAdvertising.forNewSet(connectable = true),
        // The in-call renewal advert is not covered by B2 (DiscoverableWhenNeutral).
        neutralRole = if (renewalServiceUuid != null) null else DiscoverableWhenNeutral.roleOf(value),
    )

    /**
     * Rotating IDs (RV6): stop the running set (also one kept on through an admission, [ReannounceStep]: an address never
     * carries into the new epoch); while no peer is connected stay silent for [silenceMs] and advertise [next] on a new
     * set (new address). The GATT service stays.
     */
    @SuppressLint("MissingPermission")
    fun reannounce(next: DiscoveryAnnouncement, silenceMs: Long) {
        if (closed.get() || !advertise) return
        val restart = synchronized(this) {
            val plan = ReannounceStep.plan(advertising, linkAdmitted = activeDevice != null)
            if (plan.stopRunningSet) stopAdvertising()
            admissionHold.clear()
            advertisement = newAdvertisement(next)
            if (plan.restartAfterSilence) epochSilence = true
            plan.restartAfterSilence
        }
        if (!restart) return
        try {
            try {
                Thread.sleep(silenceMs)
            } catch (_: InterruptedException) {
                return
            }
            if (closed.get() || activeDevice != null) return
        } finally {
            epochSilence = false
        }
        runCatching { startAdvertising(lowLatency = false) }
            .onFailure { onStatus("GATT video could not advertise the new ID: ${it.message}") }
    }

    private val mtuByAddress = HashMap<String, Int>()

    private fun rememberMtu(address: String, value: Int) = synchronized(mtuByAddress) {
        if (mtuByAddress.size >= MAX_REMEMBERED_MTUS) mtuByAddress.clear()
        mtuByAddress[address] = value
    }

    private fun rememberedMtu(address: String): Int = synchronized(mtuByAddress) { mtuByAddress[address] } ?: DEFAULT_MTU

    /**
     * Android 10-11 entry `android11-gatt-server-connection-untracked` ([GattServerConnectionWatch]): the decision lives in
     * [ServerConnectionWatch]; null where the entry does not apply.
     */
    private val connectionWatch: ServerConnectionWatch? by lazy {
        GattLinkPolicy.serverConnectionWatch()?.let {
            ServerConnectionWatch(it.timeoutMs, SystemClock::elapsedRealtime, GattLinkPolicy.serverFirstWriteWatch()?.timeoutMs)
        }
    }

    /** AP7 F1 (`test_gatt_first_write_counts_control`): a decoded control write arrived on the current connection. */
    @Volatile private var controlWriteDelivered = false

    /**
     * Keep-address part of the same entry ([GattServerReregisterKeepsAddress]; control arm
     * `test_gatt_reregister_keep_address=false`): a re-registration keeps the running advertising set.
     */
    private val keepAddressPolicy: Boolean by lazy { connectionWatch != null && GattLinkPolicy.serverReregisterKeepsAddress() }

    private fun keepAddress(): Boolean = keepAddressPolicy && DebugTunables.gattReregisterKeepAddress

    /** Stage 2 with keep-address: the set stays on after an admission until the first data write. */
    private val admissionHold = AdmissionAdvertisingHold()

    /** A rotating-ID silence runs ([reannounce]): it starts the new ID's set itself when the silence ends. */
    @Volatile private var epochSilence = false

    /** When this server registered, and when its last admitted link ended (elapsed realtime, ms). */
    @Volatile private var registeredAtMs = 0L
    @Volatile private var lastLinkEndedAtMs = 0L

    private fun holdSetUntilFirstWrite(): Boolean = advertise && connectionWatch?.firstWriteTimeoutMs != null && keepAddress()

    /** The held set of [device] stops now unless it was released already (first write, link end, epoch change). */
    private fun releaseAdmissionHold(device: BluetoothDevice) {
        if (admissionHold.onWatchEnded(device.address)) stopAdvertising()
    }

    /** Under the lock: what the re-registration does with the advertising set; the earlier behaviour stops it here. */
    private fun prepareReregisterAdvertising(): ReregisterAdvertising.Action {
        val action = ReregisterAdvertising.decide(keepAddress(), setRunning = advertising, epochSilence = epochSilence)
        if (action == ReregisterAdvertising.Action.REPLACE_SET) stopAdvertising()
        return action
    }

    /**
     * What this process can see of the phone's GATT registration table when a watch fires. Other apps' registrations
     * (and those leaked by this app's killed processes) are not visible to an app: `dumpsys bluetooth_manager` needs the
     * DUMP permission and the stack's log lines READ_LOGS, so the runner's dumpsys capture is the source for them.
     */
    private fun registrationSummary(): String {
        val own = GattResourceRegistry.shared.snapshot()
        val now = SystemClock.elapsedRealtime()
        val registered = registeredAtMs
        val idleSince = maxOf(registered, lastLinkEndedAtMs)
        return "ownRegistrations=${own.registrations} | ownServers=${own.servers} | ownClients=${own.clients} | " +
            "ownScanners=${own.scanners} | foreignRegistrations=unavailable_in_app | " +
            "serverAgeMs=${if (registered == 0L) -1 else now - registered} | " +
            "serverIdleMs=${if (idleSince == 0L) -1 else now - idleSince}"
    }

    /** Stage 2: after the subscribe is accepted, the first data write must reach the app ([GattServerFirstWriteWatch]). */
    private fun armFirstWriteWatch(device: BluetoothDevice) {
        val watch = connectionWatch ?: return
        if (!watch.onSubscribed(device.address, advertisingListener = advertise)) return
        val timeout = watch.firstWriteTimeoutMs ?: return
        advertiseHandler.postDelayed({ checkFirstWriteWatch(device) }, timeout)
    }

    /** AP7 F1: a delivered data write, or (with the switch on) a delivered decoded control write on this connection. */
    private fun firstWriteReceived(): Boolean =
        input?.heardAny() == true || (DebugTunables.gattFirstWriteCountsControl && controlWriteDelivered)

    private fun checkFirstWriteWatch(device: BluetoothDevice) {
        val watch = connectionWatch ?: return
        if (closed.get()) return
        val missing = watch.firstWriteExpired(dataReceived = firstWriteReceived())
        if (
            missing == null || !missing.equals(device.address, ignoreCase = true) ||
            !missing.equals(activeDevice?.address, ignoreCase = true)
        ) {
            releaseAdmissionHold(device)
            return
        }
        Log.i(
            "BluetoothVideo",
            "BENCHMARK gatt_server_first_write_missing: peer=...${missing.takeLast(5)} | waitedMs=${watch.firstWriteTimeoutMs} | " +
                "action=cancel_and_reregister | ${registrationSummary()}",
        )
        thread(name = "GattVideoServerReregister") { reregisterAfterMissingFirstWrite(device) }
    }

    /**
     * Stage 2: the subscribed client's writes never reach this server (AOSP drops them when the handle or server map
     * misses): end the admitted connection, which has received nothing, and register the server again.
     */
    @SuppressLint("MissingPermission")
    private fun reregisterAfterMissingFirstWrite(device: BluetoothDevice) {
        val (old, action) = synchronized(this) {
            if (closed.get() || activeDevice?.address != device.address || firstWriteReceived()) {
                releaseAdmissionHold(device)
                return
            }
            // Ends the hold too: a held set keeps advertising (same address) through the re-registration.
            closeConnection()
            val action = prepareReregisterAdvertising()
            val old = server ?: return
            server = null
            old to action
        }
        replaceServer(old, device, "gatt_server_first_write_missing", action)
    }

    /** Arms the watch for [device] after its CONNECTED callback; its check runs once the timeout has passed. */
    private fun armConnectionWatch(device: BluetoothDevice) {
        val watch = connectionWatch ?: return
        if (!watch.onConnected(device.address, advertisingListener = advertise, linkAdmitted = activeDevice != null)) return
        advertiseHandler.postDelayed({ checkConnectionWatch(device) }, watch.timeoutMs)
    }

    private fun checkConnectionWatch(device: BluetoothDevice) {
        val watch = connectionWatch ?: return
        if (closed.get()) return
        val untracked = watch.expired(linkAdmitted = activeDevice != null) ?: return
        if (!untracked.equals(device.address, ignoreCase = true)) return
        Log.i(
            "BluetoothVideo",
            "BENCHMARK gatt_server_connection_untracked: peer=...${untracked.takeLast(5)} | waitedMs=${watch.timeoutMs} | " +
                "action=cancel_and_reregister | ${registrationSummary()}",
        )
        thread(name = "GattVideoServerReregister") { reregisterAfterUntrackedConnection(device) }
    }

    /**
     * The stack has no record of the connection for this server (every response would go to connection 0): drop the
     * connection and register the server again, so the client's retry reaches a fresh registration.
     */
    @SuppressLint("MissingPermission")
    private fun reregisterAfterUntrackedConnection(device: BluetoothDevice) {
        val (old, action) = synchronized(this) {
            if (closed.get() || activeDevice != null) return
            val action = prepareReregisterAdvertising()
            val old = server ?: return
            server = null
            old to action
        }
        replaceServer(old, device, "gatt_server_connection_untracked", action)
    }

    /**
     * Cancels [device] on the [old] registration, closes it and registers a fresh server and service. The advertising
     * set follows [action]: kept (same address), started after, or left to a running rotating-ID silence.
     */
    @SuppressLint("MissingPermission")
    private fun replaceServer(old: BluetoothGattServer, device: BluetoothDevice, event: String, action: ReregisterAdvertising.Action) {
        runCatching { old.cancelConnection(device) }
        GattResourceRegistry.shared.releaseObject(old)
        runCatching { old.close() }
        try {
            if (closed.get()) return
            openAndStart(advertiseAfter = action != ReregisterAdvertising.Action.LEAVE_TO_REANNOUNCE)
            Log.i(
                "BluetoothVideo",
                "BENCHMARK $event: event=reregistered | advertising=${action.name.lowercase()} | advertisingNow=$advertising",
            )
        } catch (error: Exception) {
            Log.w("BluetoothVideo", "BENCHMARK $event: event=reregister_failed | reason=${error.message}")
            onStatus("GATT video receiver could not register again: ${error.message}")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != GATT_DATA_SERVICE_UUID) return
            serviceStatus = status
            serviceLatch?.countDown()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (closed.get()) return
            if (newState == BluetoothProfile.STATE_DISCONNECTED) connectionWatch?.onDisconnected(device.address)
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED && activeDevice == null) {
                setupCentral = device.address
                setupCentralAtMs = SystemClock.elapsedRealtime()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && setupCentral == device.address) {
                setupCentral = null
            }
            if (
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED &&
                gattServerOwnsCallback(activeDevice?.address, device.address)
            ) {
                // The client requests the preferred PHY after subscribing; a simultaneous
                // server-initiated update collided with it (status 42).
                server?.readPhy(device)
                // Android 10-11 (policy entry): no PHY answer means the stack lost this connection's record for the server.
                armConnectionWatch(device)
            } else if (
                newState == BluetoothProfile.STATE_DISCONNECTED &&
                activeDevice?.address == device.address
            ) {
                closeConnection()
                if (advertise && !closed.get() && !advertising) thread(name = "GattVideoReadvertise") {
                    runCatching { startAdvertising() }
                        .onFailure { onStatus("GATT video could not advertise again: ${it.message}") }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            if (closed.get()) return
            // A non-advertising second server hears every central that connects to the phone (AOSP tells all
            // registered apps, gatt_send_conn_cback), so it keeps the MTU per device and takes the admitted one.
            if (!advertise) rememberMtu(device.address, newMtu)
            if (!gattServerOwnsCallback(activeDevice?.address, device.address)) return
            mtu = newMtu
            onStatus("GATT video RX MTU $newMtu")
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            connectionWatch?.onPhyAnswer(device.address)
            if (closed.get() || !gattServerOwnsCallback(activeDevice?.address, device.address)) return
            currentPhySummary = phySummary(txPhy, rxPhy, status)
            onStatus("GATT video RX $currentPhySummary")
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) =
            onPhyUpdate(device, txPhy, rxPhy, status)

        /** Hidden framework callback (not in the SDK stubs); see [LeConnectionParameterLog]. */
        @Suppress("unused")
        fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
            if (closed.get()) return
            LeConnectionParameterLog.record(
                "gatt-video", LeLinkRole.PERIPHERAL, device.address, interval, latency, timeout, status,
            )
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val valid = !closed.get() && activeDevice == null && descriptor.uuid == CCCD_UUID &&
                descriptor.characteristic.uuid == NOTIFY_UUID &&
                !preparedWrite && offset == 0 &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0,
                    null,
                )
            }
            if (!valid) return
            activeDevice = device
            setupCentral = null
            GattResourceRegistry.shared.noteServerLink(device.address)
            // Keep-address (stage 2): the set stays on until this client's first write, so a re-registration keeps it.
            if (admissionHold.onAdmitted(device.address, hold = holdSetUntilFirstWrite())) stopAdvertising()
            if (!advertise) {
                // Events heard before admission belonged to other centrals: start from this device's own state.
                mtu = rememberedMtu(device.address)
                currentPhySummary = "PHY pending"
                server?.readPhy(device)
            }
            val characteristic = server?.getService(GATT_DATA_SERVICE_UUID)
                ?.getCharacteristic(NOTIFY_UUID) ?: return releaseAdmissionHold(device)
            val activeServer = server ?: return releaseAdmissionHold(device)
            // Experimental `test_gatt_server_hold` (G-04b): mark the admitted link as in use by this app's server.
            GattLinkLifetime.holdServerLink(activeServer, device)
            val recovery = GattChunkRecovery.forLink("server")
            val activeInput = GattBytePipe(recovery = recovery) { failure ->
                onStatus("GATT video receiver $failure")
                // A dead input on a live link leaves the sender writing into nothing while this phone's heartbeats keep telling
                // it the child is fine (b1-a5a8f056 stalled2m d2: input queue overflowed, then 70 s without video or recovery).
                // Ending the link lets the sender see the loss and the existing redial and re-request recover.
                if (DebugTunables.gattInputFailureEndsLink) runCatching { activeServer.cancelConnection(device) }
            }
            val activeOutput = GattServerOutputStream(activeServer, device, characteristic, mtu, recovery)
            input = activeInput
            output = activeOutput
            recovery?.start(activeOutput)
            controlWriteDelivered = false
            armFirstWriteWatch(device)
            onConnection(
                GattVideoConnection(
                    activeInput,
                    activeOutput,
                    { currentPhySummary },
                    activeInput::queueState,
                ) {
                    runCatching { activeServer.cancelConnection(device) }
                    onLocalClose(device.address)
                },
            )
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val peerMatches = activeDevice?.address == device.address
            val requestSupported = characteristic.uuid == WRITE_UUID && !preparedWrite && offset == 0
            val activeInput = input
            val valid = peerMatches && requestSupported && handleIncoming(activeInput, value)
            val heard = valid && activeInput?.heardAny() == true
            // AP7 F1 (Experimental `test_gatt_first_write_counts_control`): a decoded GR1 hello/high proves the stack
            // delivers this connection's writes, so the stage-2 first-write watch must not cancel and re-register the server.
            val controlDelivered = valid && controlCountsAsFirstWrite(DebugTunables.gattFirstWriteCountsControl, value)
            if (controlDelivered) controlWriteDelivered = true
            if (heard || controlDelivered) connectionWatch?.onDataReceived(device.address)
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    0,
                    null,
                )
            }
            // Keep-address: the set held since the subscribe stops once this client's writes arrive (after the response).
            if (heard && admissionHold.onFirstWrite(device.address)) stopAdvertising()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (activeDevice?.address == device.address) output?.onNotificationSent(status)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        check(!closed.get()) { "GATT video server is closed" }
        openAndStart()
    }

    /**
     * Opens and registers the server, adds the service and advertises (a set that already runs is kept; none when
     * [advertiseAfter] is false). On failure the whole server closes.
     */
    private fun openAndStart(advertiseAfter: Boolean = true) {
        EncoderBenchGuard.check("GattVideoTransport.gattServer")
        val activeServer = bluetoothManager.openGattServer(appContext, callback)
        if (activeServer == null) {
            // With Bluetooth on, a null server is the stack refusing the app registration (pool exhausted).
            if (adapter.isEnabled) GattPoolBackoff.recordFailure(PoolSource.SERVER, null, "gatt-video-server")
            throw IOException("Could not open GATT video server")
        }
        GattPoolBackoff.recordSuccess(PoolSource.SERVER)
        server = activeServer
        registeredAtMs = SystemClock.elapsedRealtime()
        GattResourceRegistry.shared.registerObject(activeServer, "gatt-video-server", GattResourceRegistry.Kind.SERVER)
        try {
            startOpened(activeServer, advertiseAfter)
        } catch (error: Throwable) {
            // The caller never receives this instance when start() throws (`gattServer = GattVideoServer(..).also(start)`),
            // so a server opened here would keep its registration until process death. close() is idempotent.
            close()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun startOpened(activeServer: BluetoothGattServer, advertiseAfter: Boolean) {
        val writeCharacteristic = BluetoothGattCharacteristic(
                WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val notifyCharacteristic = BluetoothGattCharacteristic(
            NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val service = BluetoothGattService(
            GATT_DATA_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            addCharacteristic(writeCharacteristic)
            addCharacteristic(notifyCharacteristic)
        }
        val latch = CountDownLatch(1)
        serviceLatch = latch
        if (!activeServer.addService(service) || !latch.await(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("Could not register GATT video service")
        }
        serviceLatch = null
        if (serviceStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IOException("GATT video service failed to register (status $serviceStatus)")
        }
        if (!advertise) {
            onStatus("GATT video service is ready (the CoC control server advertises)")
            return
        }
        if (!advertiseAfter) {
            onStatus("GATT video service is registered again; the new ID is advertised after its silence")
            return
        }
        startAdvertising()
        onStatus("GATT video receiver is advertising")
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() = startAdvertising(lowLatency = true)

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun startAdvertising(lowLatency: Boolean) {
        // The one funnel of every advertising entry point: a dual-listening second server never advertises.
        if (closed.get() || advertising || !advertise) return
        val status = advertisement.start(lowLatency, CONTROL_TIMEOUT_SECONDS)
            ?: throw IOException("Timed out advertising GATT video")
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            throw IOException("GATT video advertising failed (code $status)")
        }
        if (closed.get()) {
            advertisement.stop()
            return
        }
        advertising = true
        if (lowLatency && !closed.get()) scheduleBalancedStep()
    }

    /** One pending balanced step at a time (a boost reschedules it instead of adding a second one). */
    private val balancedStep = Runnable { switchToBalancedAdvertising() }

    private fun scheduleBalancedStep() {
        advertiseHandler.removeCallbacks(balancedStep)
        advertiseHandler.postDelayed(balancedStep, LOW_LATENCY_ADVERTISE_BURST_MS)
    }

    /**
     * F11: a verification code was just shown. A set that already took its balanced step (250 ms, medium power) is
     * started again at low latency (100 ms, high power) and keeps it while [AdvertisingBoost.holding]. The announcement and
     * its ID are unchanged (the code names the ID, not the address). Nothing happens while a peer is connected.
     */
    fun boostAdvertising() {
        if (closed.get() || !advertise || activeDevice != null || !AdvertisingBoost.holding()) return
        // Same set, same address (switchMode): a caller that just saw the advert still reaches it. The old replacement
        // below stays the control arm (test_adv_inplace_boost=false) and the fallback when no set runs.
        if (AdvertisingSwitches.inPlaceBoost && DebugTunables.stableAdvertisingAddress) {
            val boosted = synchronized(this) {
                !closed.get() && activeDevice == null && advertising && advertisement.switchMode(lowLatency = true)
            }
            if (boosted) {
                scheduleBalancedStep()
                return
            }
        }
        thread(name = "GattVideoAdvertiseBoost") {
            runCatching {
                synchronized(this) {
                    if (closed.get() || activeDevice != null || !advertising) return@synchronized
                    stopAdvertising()
                    startAdvertising(lowLatency = true)
                }
            }.onFailure { onStatus("GATT video boosted advertising failed: ${it.message}") }
        }
    }

    /** A failed fallback left nothing advertising: clear the state and start once more. */
    private fun onAdvertisingLost(reason: String) {
        advertising = false
        if (closed.get() || activeDevice != null) return
        thread(name = "GattVideoReadvertise") {
            runCatching { startAdvertising(lowLatency = false) }
                .onFailure { onStatus("GATT video is not advertising ($reason); restart failed: ${it.message}") }
        }
    }

    private fun switchToBalancedAdvertising() {
        // A link up (a set held through an admission, [AdmissionAdvertisingHold], can outlive it) or an F11 verification
        // code (the set stays low-latency / high power): look again later. JT2-P2: an idle call endpoint's receiver set is held.
        when (
            CallReadyAdvertisingHold.decide(
                enabled = DebugTunables.callAdvIdleFast,
                callEndpoint = callEndpoint,
                closed = closed.get(),
                advertising = advertising,
                linkActive = activeDevice != null,
                boostHolding = AdvertisingBoost.holding(),
            )
        ) {
            CallReadyAdvertisingHold.Action.IGNORE -> return
            CallReadyAdvertisingHold.Action.RETRY_LATER -> {
                scheduleBalancedStep()
                return
            }
            CallReadyAdvertisingHold.Action.HOLD -> {
                advertisement.noteBalancedHeld()
                armHoldLog()
                return
            }
            CallReadyAdvertisingHold.Action.STEP -> Unit
        }
        // Same set, same address: a peer that just discovered it can still connect.
        if (DebugTunables.stableAdvertisingAddress && advertisement.switchToBalanced()) return
        thread(name = "GattVideoAdvertiseBalance") {
            runCatching {
                stopAdvertising()
                startAdvertising(lowLatency = false)
            }.onFailure { onStatus("GATT video balanced advertising failed: ${it.message}") }
        }
    }

    /** DEBUG: when the set was first held (elapsedRealtime), 0 when not held; the 60 s `adv_hold` line repeats while it is. */
    @Volatile private var heldSinceMs = 0L

    private val holdLog: Runnable = Runnable {
        if (closed.get() || !advertising || activeDevice != null) {
            heldSinceMs = 0L
            return@Runnable
        }
        val now = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("BluetoothVideo", CallReadyAdvertisingHold.line((now - heldSinceMs) / 1_000L, now))
        advertiseHandler.postDelayed(holdLog, CallReadyAdvertisingHold.LOG_INTERVAL_MS)
    }

    private fun armHoldLog() {
        if (!BuildConfig.DEBUG || heldSinceMs != 0L) return
        heldSinceMs = android.os.SystemClock.elapsedRealtime()
        advertiseHandler.postDelayed(holdLog, CallReadyAdvertisingHold.LOG_INTERVAL_MS)
    }

    /** Experimental in-call link renewal: the armed phone's window ended or the renewal bound; the server stays. */
    internal fun stopAdvertisingNow() {
        advertiseHandler.removeCallbacks(balancedStep)
        stopAdvertising()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun stopAdvertising() {
        if (!advertising) return
        advertisement.stop()
        advertising = false
    }

    /** Review T10: this phone closed the admitted connection: free the slot and advertise again without the callback. */
    private fun onLocalClose(address: String) {
        val steps = synchronized(this) {
            GattServerLocalClose.steps(activeDevice?.address, address, advertise && readvertiseOnLocalClose, closed.get(), advertising).also {
                if (it.closeConnection) closeConnection()
            }
        }
        if (steps.readvertise) thread(name = "GattVideoReadvertise") {
            runCatching { startAdvertising() }
                .onFailure { onStatus("GATT video could not advertise again: ${it.message}") }
        }
    }

    private fun closeConnection() {
        activeDevice?.let {
            admissionHold.onLinkEnded(it.address)
            lastLinkEndedAtMs = SystemClock.elapsedRealtime()
        }
        GattResourceRegistry.shared.dropServerLink(activeDevice?.address)
        input?.close()
        input = null
        output?.close()
        output = null
        activeDevice = null
        mtu = DEFAULT_MTU
        currentPhySummary = "PHY pending"
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        advertiseHandler.removeCallbacksAndMessages(null)
        stopAdvertising()
        activeDevice?.let { device -> runCatching { server?.cancelConnection(device) } }
        closeConnection()
        server?.let { GattResourceRegistry.shared.releaseObject(it) }
        runCatching { server?.close() }
        server = null
    }
}

internal fun gattServerOwnsCallback(admittedPeerAddress: String?, callbackPeerAddress: String): Boolean =
    admittedPeerAddress == null || admittedPeerAddress == callbackPeerAddress

internal fun handleIncoming(pipe: GattBytePipe?, value: ByteArray): Boolean {
    if (pipe == null || value.isEmpty()) return false
    return when (value[0]) {
        TYPE_DATA -> pipe.offerPacket(value)
        // Type 2: ignored by every older reader; with recovery on, a `GR1` message (parsed and queued, never sent here).
        Gr1.TYPE -> {
            pipe.recovery?.onControl(value)
            true
        }
        else -> false
    }
}

internal fun refreshGattServices(gatt: BluetoothGatt): Boolean = runCatching {
    BluetoothGatt::class.java.getMethod("refresh").invoke(gatt) as? Boolean ?: false
}.getOrDefault(false)

private fun encodeChunkSequence(sequence: Int, target: ByteArray) {
    target[1] = (sequence ushr 24).toByte()
    target[2] = (sequence ushr 16).toByte()
    target[3] = (sequence ushr 8).toByte()
    target[4] = sequence.toByte()
}

private fun decodeChunkSequence(source: ByteArray): Int =
    ((source[1].toInt() and 0xff) shl 24) or
        ((source[2].toInt() and 0xff) shl 16) or
        ((source[3].toInt() and 0xff) shl 8) or
        (source[4].toInt() and 0xff)

private fun phySummary(txPhy: Int, rxPhy: Int, status: Int): String =
    if (status == BluetoothGatt.GATT_SUCCESS) {
        "PHY TX ${phyName(txPhy)} / RX ${phyName(rxPhy)}"
    } else {
        "PHY unavailable (status $status)"
    }

private fun phyName(phy: Int): String = when (phy) {
    BluetoothDevice.PHY_LE_1M -> "1M"
    BluetoothDevice.PHY_LE_2M -> "2M"
    BluetoothDevice.PHY_LE_CODED -> "Coded"
    else -> "unknown($phy)"
}

private val WRITE_UUID: UUID = UUID.fromString("c8d5a103-9f88-4f65-a03d-7f5a2fb921fa")
private val NOTIFY_UUID: UUID = UUID.fromString("c8d5a104-9f88-4f65-a03d-7f5a2fb921fa")
private val GATT_DATA_SERVICE_UUID: UUID = UUID.fromString("c8d5a105-9f88-4f65-a03d-7f5a2fb921fa")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val DEFAULT_MTU = 23
private const val MAX_REMEMBERED_MTUS = 8
private const val MAX_MTU = 517
private const val ATT_OVERHEAD = 3
private const val MAX_ATTRIBUTE_BYTES = 512
private const val L2CAP_BASIC_HEADER_BYTES = 4
/** Largest LE data PDU payload with data length extension (Core Vol 6 Part B 4.5.10). */
private const val LE_MAX_DATA_PDU_OCTETS = 251
internal const val GATT_CHUNK_HEADER_BYTES = 5
private const val TYPE_DATA: Byte = 1
private const val IO_TIMEOUT_SECONDS = 8L
private const val SETUP_POLL_MS = 250L
private const val CONTROL_TIMEOUT_SECONDS = 8L
private const val LOW_LATENCY_ADVERTISE_BURST_MS = 10_000L
private const val LEGACY_GATT_CALL_BYTES_PER_SECOND = 24_000
private const val GATT_CACHE_REFRESH_DELAY_MS = 300L
private const val HCI_DATA_PREAMBLE_OCTETS = 4

/** Registrations of this process idle longer than this are leaked probes or abandoned setups (pool-exhaustion retry). */
private const val IDLE_CLIENT_AGE_MS = 15_000L
