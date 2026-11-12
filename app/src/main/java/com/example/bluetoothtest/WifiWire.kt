package com.example.bluetoothtest

import java.nio.ByteBuffer

/** Why a Wi-Fi wire message was refused. Decoder check order: LENGTH, MAGIC, KIND, BODY_LENGTH, FIELD (VERSION for `MWD1`). */
internal enum class WifiWireError { LENGTH, MAGIC, KIND, BODY_LENGTH, FIELD, VERSION }

internal class WifiWireException(val error: WifiWireError, message: String) : IllegalArgumentException(message)

private fun wireFail(error: WifiWireError, message: String): Nothing = throw WifiWireException(error, message)
private fun fieldFail(message: String): Nothing = throw WifiWireException(WifiWireError.FIELD, message)

private fun needSize(bytes: ByteArray, size: Int, what: String) {
    if (bytes.size != size) fieldFail("$what must be $size bytes")
}

private fun needRange(value: Int, range: IntRange, what: String) {
    if (value !in range) fieldFail("$what out of range")
}

private fun needRange(value: Long, range: LongRange, what: String) {
    if (value !in range) fieldFail("$what out of range")
}

private fun ByteBuffer.u8(): Int = get().toInt() and 0xff
private fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
private fun ByteBuffer.u32(): Long = int.toLong() and 0xffffffffL
private fun ByteBuffer.bytes(count: Int): ByteArray = ByteArray(count).also { get(it) }

/**
 * A `BWF1` hop record (hop type 16): plaintext `"BWF1" | kind | body`, big-endian. PROTOCOL "BWF1 records".
 * Equality is equality of the encoded bytes.
 */
internal sealed class Bwf {
    abstract val kind: Int
    protected abstract fun body(): ByteArray

    fun encode(): ByteArray = WifiWire.MAGIC_BWF1 + byteArrayOf(kind.toByte()) + body()

    override fun equals(other: Any?): Boolean = other is Bwf && encode().contentEquals(other.encode())
    override fun hashCode(): Int = encode().contentHashCode()
    override fun toString(): String = "Bwf(kind=$kind)"

    /**
     * Kind 1. `bearers` bit 0 Aware, 1 Aware instant mode, 2 Direct group owner, 3 Direct client. The fourth body byte is
     * reserved: the encoder writes 0 and the decoder ignores the received value (forward compatibility, X5); a constructor
     * argument other than 0 is a FIELD error.
     */
    class Caps(val bearers: Int, val freeHost: Int, val freeGuest: Int, reserved: Int = 0) : Bwf() {
        init {
            needRange(bearers, 0..0x0f, "CAPS bearers")
            needRange(freeHost, 0..WifiLimits.MAX_FREE_PATHS_IN_CAPS, "CAPS freeHost")
            needRange(freeGuest, 0..WifiLimits.MAX_FREE_PATHS_IN_CAPS, "CAPS freeGuest")
            if (reserved != 0) fieldFail("CAPS reserved")
        }

        override val kind: Int get() = WifiWire.KIND_CAPS
        override fun body(): ByteArray = byteArrayOf(bearers.toByte(), freeHost.toByte(), freeGuest.toByte(), 0)

        val awareEligible: Boolean get() = bearers and BEARER_AWARE != 0
        val awareInstantMode: Boolean get() = bearers and BEARER_AWARE_ICM != 0
        val directGroupOwner: Boolean get() = bearers and BEARER_DIRECT_GO != 0
        val directClient: Boolean get() = bearers and BEARER_DIRECT_CLIENT != 0

        companion object {
            const val BEARER_AWARE = 1
            const val BEARER_AWARE_ICM = 2
            const val BEARER_DIRECT_GO = 4
            const val BEARER_DIRECT_CLIENT = 8
        }
    }

    /** Kind 2. [status] 1 offered, 0 nothing; [bearer] 1 Aware, 2 Direct. 16 bytes; `reserved` is written 0 and ignored on receipt. */
    class Host(
        val status: Int,
        val bearer: Int,
        ticket: ByteArray,
        val validMs: Int,
        val freqMhz: Int,
        reserved: Int = 0,
    ) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "HOST ticket")
            if (status != 0 && status != 1) fieldFail("HOST status")
            if (bearer != WifiWire.BEARER_AWARE && bearer != WifiWire.BEARER_DIRECT) fieldFail("HOST bearer")
            needRange(validMs, 0..0xffff, "HOST validMs")
            needRange(freqMhz, 0..0xffff, "HOST freqMhz")
            if (reserved != 0) fieldFail("HOST reserved")
            if (status == 1 && validMs !in 1..WifiLimits.HOST_VALID_MS) fieldFail("HOST validMs")
            if (status == 0 && (validMs != 0 || freqMhz != 0)) fieldFail("HOST status 0 offers nothing")
            if (bearer == WifiWire.BEARER_AWARE && freqMhz != 0) fieldFail("HOST aware frequency")
            if (bearer == WifiWire.BEARER_DIRECT && freqMhz != 0 && freqMhz < WifiLimits.MIN_5GHZ_MHZ) fieldFail("HOST direct frequency") // kept: the shared vector "host direct on 2.4 GHz" is FIELD (03-CONFLICTS C-A0-2)
        }

        override val kind: Int get() = WifiWire.KIND_HOST
        override fun body(): ByteArray = ByteBuffer.allocate(16)
            .put(status.toByte()).put(bearer.toByte()).put(ticket)
            .putShort(validMs.toShort()).putShort(freqMhz.toShort()).putShort(0)
            .array()
    }

    /** Kind 3. [reason] 1..7 (PROTOCOL). */
    class Decline(ticket: ByteArray, val reason: Int) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "DECLINE ticket")
            needRange(reason, 1..7, "DECLINE reason")
        }

        override val kind: Int get() = WifiWire.KIND_DECLINE
        override fun body(): ByteArray = ByteBuffer.allocate(9).put(ticket).put(reason.toByte()).array()
    }

    /** Kind 4. */
    class Pick(ticket: ByteArray, challenge: ByteArray) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()
        private val challengeBytes: ByteArray = challenge.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val challenge: ByteArray get() = challengeBytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "PICK ticket")
            needSize(this.challenge, WifiWire.CHALLENGE_BYTES, "PICK challenge")
        }

        override val kind: Int get() = WifiWire.KIND_PICK
        override fun body(): ByteArray = this.ticket + this.challenge
    }

    /** Kind 5. */
    class Armed(ticket: ByteArray) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "ARMED ticket")
        }

        override val kind: Int get() = WifiWire.KIND_ARMED
        override fun body(): ByteArray = ticket.copyOf()
    }

    /** Kind 6. [role] 1 guest, 2 host. */
    class Bind(val role: Int, ticket: ByteArray) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()

        init {
            needRange(role, WifiWire.ROLE_GUEST..WifiWire.ROLE_HOST, "BIND role") // [GAP+]
            needSize(this.ticket, WifiWire.TICKET_BYTES, "BIND ticket")
        }

        override val kind: Int get() = WifiWire.KIND_BIND
        override fun body(): ByteArray = byteArrayOf(role.toByte()) + ticket
    }

    /** Kind 7. Three u16 and 2 reserved bytes (written 0 and ignored on receipt). */
    class Ready(val recvCeilingKbps: Int, val familyCapKbps: Int, val transportBudgetKbps: Int, reserved: Int = 0) : Bwf() {
        init {
            needRange(recvCeilingKbps, 0..0xffff, "READY recvCeilingKbps")
            needRange(familyCapKbps, 0..0xffff, "READY familyCapKbps")
            needRange(transportBudgetKbps, 0..0xffff, "READY transportBudgetKbps")
            if (reserved != 0) fieldFail("READY reserved")
        }

        override val kind: Int get() = WifiWire.KIND_READY
        override fun body(): ByteArray = ByteBuffer.allocate(8)
            .putShort(recvCeilingKbps.toShort()).putShort(familyCapKbps.toShort())
            .putShort(transportBudgetKbps.toShort()).putShort(0)
            .array()
    }

    /** Kind 8. [gen] is an unsigned 32-bit value held in a Long (1 to 0xffffffff); boundaries are 0 to 2^63 - 1. */
    class Switch(val gen: Long, ticket: ByteArray, val e2eBoundary: Long, val meshBoundary: Long) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "SWITCH ticket")
            needRange(gen, 1L..0xffffffffL, "SWITCH gen")
            needRange(e2eBoundary, 0L..Long.MAX_VALUE, "SWITCH e2eBoundary")
            needRange(meshBoundary, 0L..Long.MAX_VALUE, "SWITCH meshBoundary")
        }

        override val kind: Int get() = WifiWire.KIND_SWITCH
        override fun body(): ByteArray = ByteBuffer.allocate(28)
            .putInt(gen.toInt()).put(ticket).putLong(e2eBoundary).putLong(meshBoundary)
            .array()
    }

    /** Kind 9. Same field limits as [Switch]. */
    class Resume(val gen: Long, ticket: ByteArray, val e2eNext: Long, val meshNext: Long) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "RESUME ticket")
            needRange(gen, 1L..0xffffffffL, "RESUME gen")
            needRange(e2eNext, 0L..Long.MAX_VALUE, "RESUME e2eNext")
            needRange(meshNext, 0L..Long.MAX_VALUE, "RESUME meshNext")
        }

        override val kind: Int get() = WifiWire.KIND_RESUME
        override fun body(): ByteArray = ByteBuffer.allocate(28)
            .putInt(gen.toInt()).put(ticket).putLong(e2eNext).putLong(meshNext)
            .array()
    }

    /** Kind 10. [reason] 1 hang-up; any byte is accepted and logged. */
    class Close(val reason: Int) : Bwf() {
        init {
            needRange(reason, 0..0xff, "CLOSE reason")
        }

        override val kind: Int get() = WifiWire.KIND_CLOSE
        override fun body(): ByteArray = byteArrayOf(reason.toByte())
    }

    /** Kind 11. Wi-Fi Direct only: where the guest connects. [ipv4] is 4 bytes, not 0.0.0.0; [port] 1..65535. */
    class Creds(ticket: ByteArray, val port: Int, ipv4: ByteArray) : Bwf() {
        private val ticketBytes: ByteArray = ticket.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ticket: ByteArray get() = ticketBytes.copyOf()
        private val ipv4Bytes: ByteArray = ipv4.copyOf()
        /** A copy: callers can never change a validated record (review A0: getters copy). */
        val ipv4: ByteArray get() = ipv4Bytes.copyOf()

        init {
            needSize(this.ticket, WifiWire.TICKET_BYTES, "CREDS ticket")
            needRange(port, 1..0xffff, "CREDS port")
            needSize(this.ipv4, 4, "CREDS ipv4")
            if (this.ipv4.all { it.toInt() == 0 }) fieldFail("CREDS ipv4 is 0.0.0.0")
        }

        override val kind: Int get() = WifiWire.KIND_CREDS
        override fun body(): ByteArray = ByteBuffer.allocate(14).put(ticket).putShort(port.toShort()).put(ipv4).array()
        val ipv4Text: String get() = ipv4.joinToString(".") { (it.toInt() and 0xff).toString() }
    }
}

/** `BWU1` frame 1 (host to guest, on accept): 21 bytes. */
internal class Bwu1Frame1(challenge: ByteArray) {
    private val challengeBytes: ByteArray = challenge.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val challenge: ByteArray get() = challengeBytes.copyOf()

    init {
        needSize(this.challenge, WifiWire.CHALLENGE_BYTES, "BWU1 challenge")
    }

    fun encode(): ByteArray = WifiWire.MAGIC_BWU1 + byteArrayOf(1) + challenge
}

/** `BWU1` frame 2 (guest to host): 126 bytes. [ephemeral] is a 65-byte uncompressed point (only the `0x04` prefix is checked here). */
internal class Bwu1Frame2(ticket: ByteArray, ephemeral: ByteArray, nonce: ByteArray, pre: ByteArray) {
    private val ticketBytes: ByteArray = ticket.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val ticket: ByteArray get() = ticketBytes.copyOf()
    private val ephemeralBytes: ByteArray = ephemeral.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val ephemeral: ByteArray get() = ephemeralBytes.copyOf()
    private val nonceBytes: ByteArray = nonce.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val nonce: ByteArray get() = nonceBytes.copyOf()
    private val preBytes: ByteArray = pre.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val pre: ByteArray get() = preBytes.copyOf()

    init {
        needSize(this.ticket, WifiWire.TICKET_BYTES, "BWU1 ticket")
        needSize(this.ephemeral, WifiWire.POINT_BYTES, "BWU1 e_G")
        needSize(this.nonce, WifiWire.NONCE_BYTES, "BWU1 n_G")
        needSize(this.pre, WifiWire.PRE_BYTES, "BWU1 pre")
        if (this.ephemeral[0].toInt() != 4) fieldFail("BWU1 e_G is not an uncompressed point")
    }

    fun encode(): ByteArray = WifiWire.MAGIC_BWU1 + byteArrayOf(2) + ticket + ephemeral + nonce + pre
}

/** `BWU1` frame 3 (host to guest): 102 bytes. */
internal class Bwu1Frame3(ephemeral: ByteArray, nonce: ByteArray) {
    private val ephemeralBytes: ByteArray = ephemeral.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val ephemeral: ByteArray get() = ephemeralBytes.copyOf()
    private val nonceBytes: ByteArray = nonce.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val nonce: ByteArray get() = nonceBytes.copyOf()

    init {
        needSize(this.ephemeral, WifiWire.POINT_BYTES, "BWU1 e_H")
        needSize(this.nonce, WifiWire.NONCE_BYTES, "BWU1 n_H")
        if (this.ephemeral[0].toInt() != 4) fieldFail("BWU1 e_H is not an uncompressed point")
    }

    fun encode(): ByteArray = WifiWire.MAGIC_BWU1 + byteArrayOf(3) + ephemeral + nonce
}

/** Wi-Fi Aware service-specific info of Wider reach discovery: `"MWD1" | 1 | announcement (22)` = 27 bytes. */
internal class Mwd1(announcement: ByteArray) {
    private val announcementBytes: ByteArray = announcement.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val announcement: ByteArray get() = announcementBytes.copyOf()

    init {
        needSize(this.announcement, WifiWire.ANNOUNCEMENT_BYTES, "MWD1 announcement")
        require(WifiWire.isRotatingAnnouncement(announcementBytes)) { "MWD1 carries only a rotating-ID announcement" }
    }

    fun encode(): ByteArray = WifiWire.MAGIC_MWD1 + byteArrayOf(1) + announcement
}

/** Follow-up message that asks a publisher for a Wi-Fi-native hop: `"MWK1" | nonce (8)` = 12 bytes. */
internal class Mwk1(nonce: ByteArray) {
    private val nonceBytes: ByteArray = nonce.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val nonce: ByteArray get() = nonceBytes.copyOf()

    init {
        needSize(this.nonce, WifiWire.NATIVE_NONCE_BYTES, "MWK1 nonce")
    }

    fun encode(): ByteArray = WifiWire.MAGIC_MWK1 + nonce
}

/** The publisher's answer: `"MWA1" | nonce (8) | port (2)` = 14 bytes, [port] 1..65535. */
internal class Mwa1(nonce: ByteArray, val port: Int) {
    private val nonceBytes: ByteArray = nonce.copyOf()
    /** A copy: callers can never change a validated record (review A0: getters copy). */
    val nonce: ByteArray get() = nonceBytes.copyOf()

    init {
        needSize(this.nonce, WifiWire.NATIVE_NONCE_BYTES, "MWA1 nonce")
        needRange(port, 1..0xffff, "MWA1 port")
    }

    fun encode(): ByteArray = WifiWire.MAGIC_MWA1 + nonce + byteArrayOf((port ushr 8).toByte(), port.toByte())
}

internal object WifiWire {
    val MAGIC_BWF1: ByteArray get() = "BWF1".toByteArray(Charsets.US_ASCII)
    val MAGIC_BWU1: ByteArray get() = "BWU1".toByteArray(Charsets.US_ASCII)
    val MAGIC_MWD1: ByteArray get() = "MWD1".toByteArray(Charsets.US_ASCII)
    val MAGIC_MWK1: ByteArray get() = "MWK1".toByteArray(Charsets.US_ASCII)
    val MAGIC_MWA1: ByteArray get() = "MWA1".toByteArray(Charsets.US_ASCII)

    const val KIND_CAPS = 1
    const val KIND_HOST = 2
    const val KIND_DECLINE = 3
    const val KIND_PICK = 4
    const val KIND_ARMED = 5
    const val KIND_BIND = 6
    const val KIND_READY = 7
    const val KIND_SWITCH = 8
    const val KIND_RESUME = 9
    const val KIND_CLOSE = 10
    const val KIND_CREDS = 11

    const val BEARER_AWARE = 1
    const val BEARER_DIRECT = 2

    /** `BIND.role`. [GAP+] */
    const val ROLE_GUEST = 1
    const val ROLE_HOST = 2

    const val TICKET_BYTES = 8
    const val CHALLENGE_BYTES = 16
    const val POINT_BYTES = 65
    const val NONCE_BYTES = 32
    const val PRE_BYTES = 16
    const val ANNOUNCEMENT_BYTES = 22 // DiscoveryAnnouncementCodec.ENCODED_BYTES
    const val NATIVE_NONCE_BYTES = 8

    const val BWU1_FRAME1_BYTES = 21
    const val BWU1_FRAME2_BYTES = 126
    const val BWU1_FRAME3_BYTES = 102
    const val MWD1_BYTES = 27
    const val MWK1_BYTES = 12
    const val MWA1_BYTES = 14

    /**
     * Release number of the first build that ships `BWF1` (PROTOCOL "BMH2 hops"). 0.4.4 is release 10; 0.5.0-beta, release
     * 11, is the first shipping release. Debug builds use this number as their own build tag on BMH2 links while any Wi-Fi
     * switch is on. [R S2, M-4]
     */
    const val WIFI_FIRST_RELEASE = 11

    private val BODY_BYTES: Map<Int, Int> = mapOf(
        KIND_CAPS to 4, KIND_HOST to 16, KIND_DECLINE to 9, KIND_PICK to 24, KIND_ARMED to 8, KIND_BIND to 9,
        KIND_READY to 8, KIND_SWITCH to 28, KIND_RESUME to 28, KIND_CLOSE to 1, KIND_CREDS to 14,
    )

    /**
     * True when a `BMS1` header may announce a type-16 record of this ciphertext length (plaintext 5..96 plus the 16-byte
     * tag). The reader checks this BEFORE it allocates the ciphertext (the ordinary reader would accept about 4 MiB).
     */
    fun acceptsBwf1CiphertextLength(ciphertextBytes: Int): Boolean =
        ciphertextBytes in (WifiLimits.MIN_BWF1_PLAINTEXT_BYTES + MeshAead.TAG_BYTES)..(WifiLimits.MAX_BWF1_PLAINTEXT_BYTES + MeshAead.TAG_BYTES)

    /** BMH2 stream hops may carry `BWF1` only when the peer's signed hello has a build tag at or above [WIFI_FIRST_RELEASE]. */
    fun buildTagAllowsBwf1(tag: BuildTag?): Boolean = tag != null && tag.releaseNumber >= WIFI_FIRST_RELEASE

    fun decodeBwf1(bytes: ByteArray): Bwf {
        if (bytes.size < WifiLimits.MIN_BWF1_PLAINTEXT_BYTES || bytes.size > WifiLimits.MAX_BWF1_PLAINTEXT_BYTES) {
            wireFail(WifiWireError.LENGTH, "BWF1 record of ${bytes.size} bytes")
        }
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC_BWF1)) wireFail(WifiWireError.MAGIC, "not a BWF1 record")
        val kind = bytes[4].toInt() and 0xff
        val expected = BODY_BYTES[kind] ?: wireFail(WifiWireError.KIND, "unknown BWF1 kind $kind")
        if (bytes.size - 5 != expected) wireFail(WifiWireError.BODY_LENGTH, "BWF1 kind $kind body of ${bytes.size - 5} bytes")
        val b = ByteBuffer.wrap(bytes, 5, expected)
        return when (kind) {
            KIND_CAPS -> { // bits 4-7 and the reserved fourth byte are ignored, never an error (X5)
                val bearers = b.u8() and 0x0f
                val freeHost = b.u8()
                val freeGuest = b.u8()
                b.u8() // reserved: written 0, ignored when received (X5)
                Bwf.Caps(bearers, freeHost, freeGuest)
            }
            KIND_HOST -> {
                val status = b.u8()
                val bearer = b.u8()
                val ticket = b.bytes(TICKET_BYTES)
                val validMs = b.u16()
                val freqMhz = b.u16()
                b.u16() // reserved: written 0, ignored when received (X5)
                Bwf.Host(status, bearer, ticket, validMs, freqMhz)
            }
            KIND_DECLINE -> Bwf.Decline(b.bytes(TICKET_BYTES), b.u8())
            KIND_PICK -> Bwf.Pick(b.bytes(TICKET_BYTES), b.bytes(CHALLENGE_BYTES))
            KIND_ARMED -> Bwf.Armed(b.bytes(TICKET_BYTES))
            KIND_BIND -> Bwf.Bind(b.u8(), b.bytes(TICKET_BYTES))
            KIND_READY -> {
                val recv = b.u16()
                val family = b.u16()
                val budget = b.u16()
                b.u16() // reserved: written 0, ignored when received (X5)
                Bwf.Ready(recv, family, budget)
            }
            KIND_SWITCH -> {
                val gen = b.u32()
                val ticket = b.bytes(TICKET_BYTES)
                val e2e = b.long
                Bwf.Switch(gen, ticket, e2e, b.long)
            }
            KIND_RESUME -> {
                val gen = b.u32()
                val ticket = b.bytes(TICKET_BYTES)
                val e2e = b.long
                Bwf.Resume(gen, ticket, e2e, b.long)
            }
            KIND_CLOSE -> Bwf.Close(b.u8())
            KIND_CREDS -> {
                val ticket = b.bytes(TICKET_BYTES)
                val port = b.u16()
                Bwf.Creds(ticket, port, b.bytes(4))
            }
            else -> wireFail(WifiWireError.KIND, "unknown BWF1 kind $kind")
        }
    }

    private fun checkBwu1(bytes: ByteArray, type: Int, size: Int) {
        if (bytes.size != size) wireFail(WifiWireError.LENGTH, "BWU1 frame $type of ${bytes.size} bytes")
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC_BWU1)) wireFail(WifiWireError.MAGIC, "not a BWU1 frame")
        if ((bytes[4].toInt() and 0xff) != type) wireFail(WifiWireError.KIND, "BWU1 frame type ${bytes[4].toInt() and 0xff}, expected $type")
    }

    fun decodeBwu1Frame1(bytes: ByteArray): Bwu1Frame1 {
        checkBwu1(bytes, 1, BWU1_FRAME1_BYTES)
        return Bwu1Frame1(bytes.copyOfRange(5, 21))
    }

    fun decodeBwu1Frame2(bytes: ByteArray): Bwu1Frame2 {
        checkBwu1(bytes, 2, BWU1_FRAME2_BYTES)
        return Bwu1Frame2(
            ticket = bytes.copyOfRange(5, 13),
            ephemeral = bytes.copyOfRange(13, 78),
            nonce = bytes.copyOfRange(78, 110),
            pre = bytes.copyOfRange(110, 126),
        )
    }

    fun decodeBwu1Frame3(bytes: ByteArray): Bwu1Frame3 {
        checkBwu1(bytes, 3, BWU1_FRAME3_BYTES)
        return Bwu1Frame3(ephemeral = bytes.copyOfRange(5, 70), nonce = bytes.copyOfRange(70, 102))
    }

    fun decodeMwd1(bytes: ByteArray): Mwd1 {
        if (bytes.size != MWD1_BYTES) wireFail(WifiWireError.LENGTH, "MWD1 of ${bytes.size} bytes")
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC_MWD1)) wireFail(WifiWireError.MAGIC, "not an MWD1 message")
        if (bytes[4].toInt() != 1) wireFail(WifiWireError.VERSION, "MWD1 version ${bytes[4].toInt() and 0xff}")
        val announcement = bytes.copyOfRange(5, 27)
        // Only a rotating-ID announcement (version 5, or 8 = the Visible-by-name window offer) may travel in MWD1
        // (PROTOCOL "Wider reach"); a fixed-prefix or unknown one is refused.
        if (!isRotatingAnnouncement(announcement)) fieldFail("MWD1 announcement")
        return Mwd1(announcement)
    }

    /** True for a 22-byte announcement the Bluetooth codec accepts as a rotating ID (versions 5 and 8). */
    fun isRotatingAnnouncement(announcement: ByteArray): Boolean = try {
        DiscoveryAnnouncementCodec.decode(announcement, acceptRotating = true, acceptWindow = true).rotating
    } catch (_: IllegalArgumentException) {
        false
    }

    fun decodeMwk1(bytes: ByteArray): Mwk1 {
        if (bytes.size != MWK1_BYTES) wireFail(WifiWireError.LENGTH, "MWK1 of ${bytes.size} bytes")
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC_MWK1)) wireFail(WifiWireError.MAGIC, "not an MWK1 message")
        return Mwk1(bytes.copyOfRange(4, 12))
    }

    fun decodeMwa1(bytes: ByteArray): Mwa1 {
        if (bytes.size != MWA1_BYTES) wireFail(WifiWireError.LENGTH, "MWA1 of ${bytes.size} bytes")
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC_MWA1)) wireFail(WifiWireError.MAGIC, "not an MWA1 message")
        val port = ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff)
        return Mwa1(bytes.copyOfRange(4, 12), port)
    }
}
