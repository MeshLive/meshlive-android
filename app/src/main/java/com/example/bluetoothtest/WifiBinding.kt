package com.example.bluetoothtest

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/**
 * An input stream whose every read is bounded by one absolute deadline: before each read it sets `soTimeout` from
 * the remaining time. A peer that drips one byte per 400 ms cannot extend the 2 s and 5 s limits of A5.3.
 * After [disarm] every read is a plain pass-through (`soTimeout = 0`), so a handed-over carrier never times out.
 */
internal class DeadlineInputStream(private val socket: Socket, private val nanoTime: () -> Long) : InputStream() {
    private val inner = socket.getInputStream()

    @Volatile
    var deadlineNs: Long = 0L

    fun disarm() {
        deadlineNs = 0L
        socket.soTimeout = 0
    }

    private fun prepare() {
        val remaining = deadlineNs - nanoTime()
        if (remaining <= 0L) throw SocketTimeoutException("binding deadline passed")
        socket.soTimeout = maxOf(1L, remaining / 1_000_000L).toInt()
    }

    override fun read(): Int {
        if (deadlineNs == 0L) return inner.read()
        prepare()
        return inner.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (deadlineNs == 0L) return inner.read(b, off, len)
        prepare()
        return inner.read(b, off, len)
    }

    /** Reads exactly [size] bytes or throws. */
    fun readFully(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = read(bytes, read, size - read)
            if (n < 0) throw EOFException("stream ended after $read of $size bytes")
            read += n
        }
        return bytes
    }
}

/**
 * The BWU1 upgrade handshake on a plain TCP socket: 21-byte frame 1, 126-byte frame 2 with the pre, 102-byte frame 3,
 * then the `BIND`/`READY` exchange under `BWF1`. The ticket is consumed only after the pre verifies (A5.3).
 */
internal object WifiBinding {
    class Bound(
        val socket: Socket,
        val reader: SecureRecordReader,
        val writer: SecureRecordWriter,
        val peerReady: Bwf.Ready,
        private val input: DeadlineInputStream,
    ) {
        fun disarm() = input.disarm()
    }

    sealed interface HostResult {
        class Ok(val bound: Bound) : HostResult

        /** The ticket was not touched. */
        class Rejected(val stage: String) : HostResult

        /** The ticket was consumed and cannot be restored. */
        class FailedAfterTicket(val stage: String) : HostResult
    }

    sealed interface GuestResult {
        class Ok(val bound: Bound) : GuestResult
        class Failed(val stage: String) : GuestResult
    }

    fun host(
        socket: Socket,
        acceptedAtNs: Long,
        uw: ByteArray,
        gate: TicketGate,
        ticket: ByteArray,
        localReady: Bwf.Ready,
        entropy: Bmh3Entropy = SecureBmh3Entropy,
        nanoTime: () -> Long = System::nanoTime,
    ): HostResult {
        var reader: SecureRecordReader? = null
        var writer: SecureRecordWriter? = null
        var consumed = false
        var afterStage = "frame3"
        fun closeKeys() {
            runCatching { reader?.close() }
            runCatching { writer?.close() }
        }
        fun afterTicket(stage: String): HostResult {
            closeKeys()
            return HostResult.FailedAfterTicket(stage)
        }
        try {
            val t0 = acceptedAtNs
            val challenge = entropy.nonce().copyOf(WifiWire.CHALLENGE_BYTES)
            val f1 = Bwu1Frame1(challenge).encode()
            val rawOut = socket.getOutputStream()
            rawOut.write(f1)
            rawOut.flush()
            val input = DeadlineInputStream(socket, nanoTime)
            input.deadlineNs = t0 + WifiLimits.BIND_FRAME2_DEADLINE_MS * 1_000_000L
            val f2 = try {
                input.readFully(WifiWire.BWU1_FRAME2_BYTES)
            } catch (_: Exception) {
                return HostResult.Rejected("frame2")
            }
            val p = try {
                WifiWire.decodeBwu1Frame2(f2)
            } catch (_: WifiWireException) {
                return HostResult.Rejected("frame2_format")
            }
            if (!WifiKeys.preMatches(uw, p, challenge)) return HostResult.Rejected("pre")
            if (!gate.tryConsume(p.ticket)) return HostResult.Rejected("ticket")
            consumed = true // from here on every failure is after the ticket: the attempt cannot bind again [P3-1]
            if (!Bmh3Crypto.isOnCurve(p.ephemeral)) return afterTicket("point")
            val eph = entropy.ephemeral()
            val nH = entropy.nonce()
            val f3 = Bwu1Frame3(eph.publicBytes, nH).encode()
            rawOut.write(f3)
            rawOut.flush()
            val dh = try {
                WifiKeys.dh(eph.privateKey, p.ephemeral)
            } catch (_: Bmh3Failure) {
                return afterTicket("ecdh")
            }
            afterStage = "keys"
            val th = WifiKeys.transcriptHash(f1, f2, f3)
            val keys = try {
                WifiKeys.bindKeys(uw, p.ticket, th, dh)
            } finally {
                dh.fill(0)
            }
            val boundReader: SecureRecordReader
            val boundWriter: SecureRecordWriter
            try {
                boundWriter = SecureRecordWriter(
                    BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
                    keys.sendKey(false),
                )
                boundReader = SecureRecordReader(input, keys.receiveKey(false)).also { it.acceptWifi = true }
            } finally {
                keys.close()
            }
            reader = boundReader
            writer = boundWriter
            var stage = "bind_write"
            try {
                input.deadlineNs = t0 + WifiLimits.BIND_READY_DEADLINE_MS * 1_000_000L
                boundWriter.write(SecureRecordType.BWF1, Bwf.Bind(WifiWire.ROLE_HOST, ticket).encode())
                boundWriter.flush()
                stage = "bind_read"
                val record = boundReader.read()
                stage = "bind_check"
                if (record.type != SecureRecordType.BWF1) return afterTicket("bind_check")
                val bind = WifiWire.decodeBwf1(record.payload) as? Bwf.Bind ?: return afterTicket("bind_check")
                if (bind.role != WifiWire.ROLE_GUEST) return afterTicket("bind_check")
                if (!MessageDigest.isEqual(bind.ticket, ticket)) return afterTicket("bind_check")
                stage = "ready"
                boundWriter.write(SecureRecordType.BWF1, localReady.encode())
                boundWriter.flush()
                val readyRecord = boundReader.read()
                if (readyRecord.type != SecureRecordType.BWF1) return afterTicket("ready")
                val peerReady = WifiWire.decodeBwf1(readyRecord.payload) as? Bwf.Ready ?: return afterTicket("ready")
                return HostResult.Ok(Bound(socket, boundReader, boundWriter, peerReady, input))
            } catch (_: Exception) {
                return afterTicket(stage)
            }
        } catch (_: Exception) {
            return if (consumed) afterTicket(afterStage) else HostResult.Rejected("frame2")
        }
    }

    fun guest(
        socket: Socket,
        connectedAtNs: Long,
        uw: ByteArray,
        ticket: ByteArray,
        localReady: Bwf.Ready,
        entropy: Bmh3Entropy = SecureBmh3Entropy,
        nanoTime: () -> Long = System::nanoTime,
    ): GuestResult {
        var reader: SecureRecordReader? = null
        var writer: SecureRecordWriter? = null
        fun closeKeys() {
            runCatching { reader?.close() }
            runCatching { writer?.close() }
        }
        fun failed(stage: String): GuestResult {
            closeKeys()
            return GuestResult.Failed(stage)
        }
        try {
            val t0 = connectedAtNs
            val input = DeadlineInputStream(socket, nanoTime)
            input.deadlineNs = t0 + WifiLimits.BIND_FRAME2_DEADLINE_MS * 1_000_000L
            val f1 = try {
                input.readFully(WifiWire.BWU1_FRAME1_BYTES)
            } catch (_: Exception) {
                return GuestResult.Failed("frame1")
            }
            val challenge = try {
                WifiWire.decodeBwu1Frame1(f1).challenge
            } catch (_: WifiWireException) {
                return GuestResult.Failed("frame1")
            }
            val eph = entropy.ephemeral()
            val nG = entropy.nonce()
            val pre = WifiKeys.pre(uw, ticket, challenge, eph.publicBytes, nG)
            val f2 = Bwu1Frame2(ticket, eph.publicBytes, nG, pre).encode()
            val rawOut = socket.getOutputStream()
            rawOut.write(f2)
            rawOut.flush()
            input.deadlineNs = nanoTime() + WifiLimits.BIND_FRAME2_DEADLINE_MS * 1_000_000L
            val f3 = try {
                input.readFully(WifiWire.BWU1_FRAME3_BYTES)
            } catch (_: Exception) {
                return GuestResult.Failed("frame3")
            }
            val frame3 = try {
                WifiWire.decodeBwu1Frame3(f3)
            } catch (_: WifiWireException) {
                return GuestResult.Failed("frame3")
            }
            if (!Bmh3Crypto.isOnCurve(frame3.ephemeral)) return GuestResult.Failed("point")
            val dh = try {
                WifiKeys.dh(eph.privateKey, frame3.ephemeral)
            } catch (_: Bmh3Failure) {
                return GuestResult.Failed("ecdh")
            }
            val th = WifiKeys.transcriptHash(f1, f2, f3)
            val keys = try {
                WifiKeys.bindKeys(uw, ticket, th, dh)
            } finally {
                dh.fill(0)
            }
            val boundReader: SecureRecordReader
            val boundWriter: SecureRecordWriter
            try {
                boundWriter = SecureRecordWriter(
                    BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
                    keys.sendKey(true),
                )
                boundReader = SecureRecordReader(input, keys.receiveKey(true)).also { it.acceptWifi = true }
            } finally {
                keys.close()
            }
            reader = boundReader
            writer = boundWriter
            var stage = "bind_write"
            try {
                input.deadlineNs = t0 + WifiLimits.BIND_READY_DEADLINE_MS * 1_000_000L
                boundWriter.write(SecureRecordType.BWF1, Bwf.Bind(WifiWire.ROLE_GUEST, ticket).encode())
                boundWriter.flush()
                stage = "bind_read"
                val record = boundReader.read()
                stage = "bind_check"
                if (record.type != SecureRecordType.BWF1) return failed("bind_check")
                val bind = WifiWire.decodeBwf1(record.payload) as? Bwf.Bind ?: return failed("bind_check")
                if (bind.role != WifiWire.ROLE_HOST) return failed("bind_check")
                if (!MessageDigest.isEqual(bind.ticket, ticket)) return failed("bind_check")
                stage = "ready"
                boundWriter.write(SecureRecordType.BWF1, localReady.encode())
                boundWriter.flush()
                val readyRecord = boundReader.read()
                if (readyRecord.type != SecureRecordType.BWF1) return failed("ready")
                val peerReady = WifiWire.decodeBwf1(readyRecord.payload) as? Bwf.Ready ?: return failed("ready")
                return GuestResult.Ok(Bound(socket, boundReader, boundWriter, peerReady, input))
            } catch (_: Exception) {
                return failed(stage)
            }
        } catch (_: Exception) {
            return GuestResult.Failed("frame1")
        }
    }
}
