package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BMH3, the experimental identity-hiding handshake (PROTOCOL "Experimental BMH3 identity-hiding link
 * handshake"; executable specification `tools/bmh3_reference.py`). Pure state machines over byte arrays: no
 * I/O, no Android types, so every rule is JVM-tested against the reference vectors.
 *
 * SIGMA in both reveal orders. R_FIRST (three messages): the responder's identity block travels in msg2 and
 * the initiator reveals itself only after the responder's signature, key confirmation and the caller's own
 * checks passed. I_FIRST (four messages): the initiator reveals first and the responder only to an admitted
 * peer. The identity key only signs; the key agreement is ephemeral P-256, the schedule HKDF-SHA-256 with a
 * TLS 1.3 shape, the handshake messages AES-256-GCM under one-use keys.
 */
enum class Bmh3Purpose(val wire: Int, internal val domain: ByteArray) {
    LINK(1, "BluetoothVideoMesh/BMH3/Link".toByteArray(Charsets.US_ASCII)),
    END_TO_END(2, "BluetoothVideoMesh/BMH3/EndToEnd".toByteArray(Charsets.US_ASCII)),
}

enum class Bmh3Order { R_FIRST, I_FIRST }

/** What a responder is willing to reveal: everyone (R_FIRST) or only a holder of its lease key (PROTOCOL "Order"). */
enum class Bmh3ResponderMode { EVERYONE, CONTACTS_ONLY }

/** A handshake failure. The connection closes without any message; [reason] is a fixed class, never an identifier. */
class Bmh3Failure(val reason: String) : GeneralSecurityException("BMH3 handshake failed: $reason")

object Bmh3Wire {
    const val VERSION = 1
    const val OFFER_P256 = 1
    const val HEADER_BYTES = 8
    const val EPHEMERAL_BYTES = 65
    const val NONCE_BYTES = 32
    const val TAG_BYTES = 16
    const val BINDING_BYTES = 11
    const val SPKI_BYTES = 91
    const val EXTENSION_BYTES = 112
    const val SIGNATURE_BYTES = 64
    const val MAC_BYTES = 32
    const val MSG1_BYTES = HEADER_BYTES + 1 + EPHEMERAL_BYTES + NONCE_BYTES + TAG_BYTES
    const val MSG2_CLEAR_BYTES = HEADER_BYTES + EPHEMERAL_BYTES + NONCE_BYTES
    const val BLOCK_BYTES = 2 + SPKI_BYTES + NodeCapabilitiesCodec.ENCODED_BYTES + 2 + EXTENSION_BYTES + SIGNATURE_BYTES + MAC_BYTES
    const val MSG3_BYTES = HEADER_BYTES + BLOCK_BYTES + MeshAead.TAG_BYTES
    const val MSG2_R_FIRST_BYTES = MSG2_CLEAR_BYTES + BLOCK_BYTES + MeshAead.TAG_BYTES
    const val FLAG_MAY_REVEAL_FIRST = 0x01
    const val FLAG_R_FIRST = 0x01
    const val OPENING_ENTRY_BYTES = 43
    val MAGIC: ByteArray = "BMH3".toByteArray(Charsets.US_ASCII)
    val P256_SPKI_PREFIX: ByteArray = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08,
        0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )

    /** True when [frame] starts with the BMH3 magic. */
    fun isBmh3(frame: ByteArray): Boolean = frame.size >= MAGIC.size && frame.copyOf(MAGIC.size).contentEquals(MAGIC)
}

/** Fresh randomness of one handshake. Production draws every value from the platform CSPRNG; tests inject vectors. */
interface Bmh3Entropy {
    fun ephemeral(): Bmh3Ephemeral
    fun nonce(): ByteArray
    fun randomTag(): ByteArray
}

class Bmh3Ephemeral(val publicBytes: ByteArray, internal val privateKey: PrivateKey) {
    init {
        require(publicBytes.size == Bmh3Wire.EPHEMERAL_BYTES && publicBytes[0].toInt() == 4)
    }
}

object SecureBmh3Entropy : Bmh3Entropy {
    private val random = SecureRandom()

    override fun ephemeral(): Bmh3Ephemeral {
        val pair: KeyPair = generateEcKeyPair(random)
        return Bmh3Ephemeral(Bmh3Crypto.encodePoint(pair.public as ECPublicKey), pair.private)
    }

    override fun nonce(): ByteArray = ByteArray(Bmh3Wire.NONCE_BYTES).also(random::nextBytes)
    override fun randomTag(): ByteArray = ByteArray(Bmh3Wire.TAG_BYTES).also(random::nextBytes)
}

/**
 * A key and capability set a phone signs one block with. [pseudonym] declares the key a throwaway
 * (extension `PSEUDONYM`, PROTOCOL "Phase 2: pseudonymous stranger identities"): the block then carries no opening.
 */
class Bmh3Presentation(
    val identity: SigningIdentity,
    val capabilities: NodeCapabilities,
    val pseudonym: Boolean = false,
) {
    init {
        if (!Bmh3Crypto.isCanonicalSpki(identity.publicKeyEncoded)) throw Bmh3Failure("identity key is not the canonical P-256 SPKI")
    }

    companion object {
        /**
         * The one place that decides what a throwaway presentation signs (review 2026-10-27, S2). A LINK block of a
         * **responder** clears link-hello bit 3, so no `BDO1` (and no salt) follows until the user accepts; an initiator's
         * keeps it, because a responder that answers with its real key still exchanges the opening
         * ([RelayIdentityPolicy.skipsLinkOpening]). End-to-end blocks keep bit 3 (the reveal uses `BDX1`).
         */
        fun stranger(identity: SigningIdentity, capabilities: NodeCapabilities, purpose: Bmh3Purpose, responder: Boolean): Bmh3Presentation {
            val signed = if (purpose == Bmh3Purpose.LINK && responder) {
                capabilities.copy(transportMask = capabilities.transportMask and RotatingDiscoveryIds.LINK_HELLO_BIT.inv())
            } else {
                capabilities
            }
            return Bmh3Presentation(identity, signed, pseudonym = true)
        }
    }
}

/** The identity and capabilities this phone presents in one handshake. */
class Bmh3Config(
    val purpose: Bmh3Purpose,
    val identity: SigningIdentity,
    val capabilities: NodeCapabilities,
    val entropy: Bmh3Entropy = SecureBmh3Entropy,
    /**
     * The updater's build tag (PROTOCOL "Build tag in the link hello"), carried in this phone's encrypted block of a LINK
     * handshake instead of the cleartext BMH2 hello. Ignored for the end-to-end purpose.
     */
    buildTag: BuildTag? = null,
    /**
     * What this phone presents to a peer that is not a recognised contact (rule P): a responder uses it when msg1's
     * tag did not match, an initiator when it sent no contact tag. Null (the default): the real identity to everyone,
     * BMH3 Phase 1 exactly.
     */
    val stranger: Bmh3Presentation? = null,
    /**
     * Design OR (DEBUG prototype): the `RELAY_SEEK` feature bits this phone signs in a responder block of a LINK
     * handshake (a listener that would admit a search now); null signs nothing, exactly as before.
     */
    relaySeek: Int? = null,
    /**
     * Wi-Fi upgrade: the initiator signs the `WIFI` build marker in its LINK block (msg3). Default false: the signed
     * bytes, and therefore every Bmh3 test vector, are exactly as before. Only [Bmh3Driver] sets it, from
     * [Bmh3InitiatorOptions.wifiMarker].
     */
    wifiMarker: Boolean = false,
) {
    val buildTag: BuildTag? = if (purpose == Bmh3Purpose.LINK) buildTag else null
    val relaySeek: Int? = if (purpose == Bmh3Purpose.LINK) relaySeek else null
    val wifiMarker: Boolean = purpose == Bmh3Purpose.LINK && wifiMarker

    /** The presentation that carries the real identity. */
    val real: Bmh3Presentation = Bmh3Presentation(identity, capabilities, pseudonym = false)

    init {
        if (!Bmh3Crypto.isCanonicalSpki(identity.publicKeyEncoded)) throw Bmh3Failure("identity key is not the canonical P-256 SPKI")
    }
}

/** Known extensions of one identity block (PROTOCOL "Extensions"); the area itself is always 112 bytes. */
class Bmh3Extensions(
    /** The `OPENING` value: `count (1, 0..2) | count x entry (43)`, responder blocks only. */
    val opening: ByteArray? = null,
    /** The `INTENT` value, msg3 under I_FIRST only. */
    val intent: Int? = null,
    /** The build tag of the updater's "newer version nearby" notice; LINK handshakes only, either block. */
    val buildTag: BuildTag? = null,
    /** True when a `BUILD_TAG` extension was present on the wire, even one that decodes to no valid tag. */
    internal val buildTagSeen: Boolean = buildTag != null,
    /** The `PSEUDONYM` marker: the key of this block is a throwaway (PROTOCOL "Phase 2: pseudonymous stranger identities"). */
    val pseudonym: Boolean = false,
    /** Type 5 `RELAY_SEEK` (Design OR, DEBUG prototype): the feature bits of a helper that would admit a search; responder LINK blocks only. */
    val relaySeek: Int? = null,
    /** Type 8 `WIFI` (Wi-Fi upgrade): this build implements `BWF1`; version 1; initiator LINK blocks only. */
    val wifi: Boolean = false,
    /** True when a `WIFI` extension was present on the wire, even one with another version (placement rule only). */
    internal val wifiSeen: Boolean = wifi,
) {
    init {
        opening?.let { value ->
            require(value.isNotEmpty() && (value[0].toInt() and 0xff) <= 2 && value.size == 1 + Bmh3Wire.OPENING_ENTRY_BYTES * (value[0].toInt() and 0xff)) {
                "Invalid opening value"
            }
        }
        require(intent == null || intent in 0..0xff)
        require(relaySeek == null || relaySeek in 0..0xff)
    }

    /** The 112-byte extension area ([Bmh3Wire.EXTENSION_BYTES]): the extensions in ascending type order behind a `PAD` of zeros. */
    fun encodeArea(): ByteArray {
        val body = ByteArrayOutputStream()
        opening?.let { value ->
            body.write(TYPE_OPENING)
            body.write(value.size ushr 8)
            body.write(value.size)
            body.write(value)
        }
        intent?.let { value ->
            body.write(TYPE_INTENT)
            body.write(0)
            body.write(1)
            body.write(value)
        }
        buildTag?.let { tag ->
            val value = tag.encode()
            body.write(TYPE_BUILD_TAG)
            body.write(0)
            body.write(BUILD_TAG_BYTES)
            body.write(value ushr 8)
            body.write(value)
        }
        if (pseudonym) {
            body.write(TYPE_PSEUDONYM)
            body.write(0)
            body.write(0)
        }
        relaySeek?.let { value ->
            body.write(TYPE_RELAY_SEEK)
            body.write(0)
            body.write(1)
            body.write(value)
        }
        if (wifi) {
            body.write(TYPE_WIFI)
            body.write(0)
            body.write(1)
            body.write(WIFI_VERSION)
        }
        val used = body.size()
        val spare = Bmh3Wire.EXTENSION_BYTES - used
        require(spare >= 0 && (spare == 0 || spare >= 3)) { "Extensions do not fit the 112-byte area" }
        val area = ByteArrayOutputStream(Bmh3Wire.EXTENSION_BYTES)
        if (spare > 0) {
            area.write(TYPE_PAD)
            area.write((spare - 3) ushr 8)
            area.write(spare - 3)
            area.write(ByteArray(spare - 3))
        }
        area.write(body.toByteArray())
        return area.toByteArray()
    }

    companion object {
        const val TYPE_PAD = 0
        const val TYPE_OPENING = 1
        const val TYPE_INTENT = 2
        const val TYPE_BUILD_TAG = 3
        const val TYPE_PSEUDONYM = 4
        const val TYPE_RELAY_SEEK = 5
        const val TYPE_WIFI = 8
        const val WIFI_VERSION = 1
        const val BUILD_TAG_BYTES = 2
        const val CRITICAL_BIT = 0x80
        val NONE = Bmh3Extensions()

        /** Strict parse of a 112-byte area (ascending types, zero padding, no unknown critical type). */
        fun parse(area: ByteArray): Bmh3Extensions {
            if (area.size != Bmh3Wire.EXTENSION_BYTES) throw Bmh3Failure("extension area length")
            var position = 0
            var last = -1
            var opening: ByteArray? = null
            var intent: Int? = null
            var buildTag: BuildTag? = null
            var buildTagSeen = false
            var pseudonym = false
            var relaySeek: Int? = null
            var wifi = false
            var wifiSeen = false
            while (position < area.size) {
                if (position + 3 > area.size) throw Bmh3Failure("truncated extension")
                val type = area[position].toInt() and 0xff
                val length = ((area[position + 1].toInt() and 0xff) shl 8) or (area[position + 2].toInt() and 0xff)
                if (position + 3 + length > area.size) throw Bmh3Failure("truncated extension value")
                if (type <= last) throw Bmh3Failure("extensions not ascending")
                last = type
                val value = area.copyOfRange(position + 3, position + 3 + length)
                when (type) {
                    TYPE_PAD -> if (value.any { it.toInt() != 0 }) throw Bmh3Failure("non-zero padding")
                    TYPE_OPENING -> {
                        if (value.isEmpty() || (value[0].toInt() and 0xff) > 2 ||
                            value.size != 1 + Bmh3Wire.OPENING_ENTRY_BYTES * (value[0].toInt() and 0xff)
                        ) {
                            throw Bmh3Failure("opening malformed")
                        }
                        opening = value
                    }
                    TYPE_INTENT -> {
                        if (value.size != 1) throw Bmh3Failure("intent malformed")
                        intent = value[0].toInt() and 0xff
                    }
                    TYPE_BUILD_TAG -> {
                        if (value.size != BUILD_TAG_BYTES) throw Bmh3Failure("build tag malformed")
                        buildTagSeen = true
                        // A value that is not a valid tag (release 0) is a peer without one, never a failure.
                        buildTag = BuildTag.decode(((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff))
                    }
                    TYPE_PSEUDONYM -> {
                        if (value.isNotEmpty()) throw Bmh3Failure("pseudonym marker malformed")
                        pseudonym = true
                    }
                    TYPE_RELAY_SEEK -> {
                        if (value.size != 1) throw Bmh3Failure("relay seek malformed")
                        relaySeek = value[0].toInt() and 0xff
                    }
                    TYPE_WIFI -> {
                        if (value.size != 1) throw Bmh3Failure("wifi marker malformed")
                        wifiSeen = true
                        // Another version is a peer without the marker (never a failure): only version 1 counts.
                        wifi = value[0].toInt() == WIFI_VERSION
                    }
                    else -> if (type and CRITICAL_BIT != 0) throw Bmh3Failure("unknown critical extension")
                }
                position += 3 + length
            }
            return Bmh3Extensions(opening, intent, buildTag, buildTagSeen, pseudonym, relaySeek, wifi, wifiSeen)
        }
    }
}

/** The authenticated peer of a handshake, as the local checks see it. */
class Bmh3Peer internal constructor(
    /** The canonical 91-byte SPKI (also what [PeerIdentityGate.admit] and the fingerprint take). */
    val spki: ByteArray,
    val nodeId: NodeId,
    val capabilities: NodeCapabilities,
    val extensions: Bmh3Extensions,
) {
    /** The peer's build tag from its verified block, or null (switch off, older build, not a release). */
    val buildTag: BuildTag? get() = extensions.buildTag

    /** True when the peer's block declared its key a throwaway: never admit, pin or display it as an identity. */
    val pseudonymous: Boolean get() = extensions.pseudonym

    /** Design OR: the `RELAY_SEEK` feature bits the peer signed (0 when none). */
    val relaySeek: Int get() = extensions.relaySeek ?: 0

    /** True when the peer's verified initiator block carried the `WIFI` build marker (version 1). */
    val wifiMarker: Boolean get() = extensions.wifi

    /** The opening entries the peer proved in its block (rotating-ID discovery binding), or null when it sent none. */
    fun openingEntries(): List<OpeningEntry>? = extensions.opening?.let { value ->
        DiscoveryOpeningCodec.decode(
            DiscoveryOpeningCodec.MAGIC + byteArrayOf(DiscoveryOpeningCodec.VERSION.toByte()) + value,
        )
    }
}

/** Session keys and the peer after a completed handshake. The caller zeroes the keys once installed. */
class Bmh3Result(
    val order: Bmh3Order,
    val peer: Bmh3Peer,
    val sendKey: ByteArray,
    val receiveKey: ByteArray,
    val exporter: ByteArray,
    /** Responder only: msg1's tag matched one of its lease keys (or the live verification code). */
    val tagMatched: Boolean,
    /** True when this phone itself presented a pseudonym in this handshake. */
    val ownPseudonymous: Boolean = false,
    /** The SPKI this phone presented (what a later reveal names as its pseudonym). */
    val ownSpki: ByteArray = ByteArray(0),
    /** Responder only: the matching tag was the live verification code's (BVMID2), so the session is verify-only. */
    val qrTagMatched: Boolean = false,
) {
    fun wipe() {
        sendKey.fill(0)
        receiveKey.fill(0)
        exporter.fill(0)
    }
}

/** Per-process memory of the last msg1 ephemerals, so a replayed msg1 costs the responder one hash (SR11). */
class Bmh3ReplayCache(private val capacity: Int = 4_096) {
    private val seen = object : LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    /** True the first time [ephemeral] is seen, false for a repeat. */
    @Synchronized
    fun firstUse(ephemeral: ByteArray): Boolean {
        val key = Bmh3Crypto.sha256(ephemeral).joinToString("") { "%02x".format(it) }
        return seen.put(key, true) == null
    }
}

/**
 * One contact tag: [key] is `K_tag` (from the responder's day lease key) and [binding] the advert it is bound to
 * ([Bmh3Crypto.advertBinding]), so a recorded tag is useless against the responder's next advert. [expectedPeer]
 * (initiator side) is the contact the selected advert was recognised as: its full node ID must be the answerer's.
 */
class Bmh3ContactTag(
    val key: ByteArray,
    val binding: ByteArray,
    val expectedPeer: NodeId? = null,
    /**
     * Naming v2 / BVMID2: non-null when this tag key comes from the secret `K_qr` of a verification code on the responder's
     * screen, not from a presence lease. A match presents the real key like any tag, but the gate reserves the code for ONE
     * logical attempt (both handshake layers of the connection), and the session it opens is scoped to verifying.
     */
    val qrGate: QrTagGate? = null,
) {
    val isQr: Boolean get() = qrGate != null

    init {
        require(key.size == 32 && binding.size == Bmh3Wire.BINDING_BYTES)
    }
}

/**
 * The responder-side hook of a verification code ([QrCodeDisplay]; PROTOCOL "Verification code BVMID2"). It runs at msg1,
 * after the tag matched and before any key is drawn or signature made. [onTagMatch] atomically reserves the code for the
 * connection's attempt (link layer first, then the end-to-end layer of the SAME connection), spends the global signing
 * budget, enforces the per-code attempt cap and the replay pins. It throws [Bmh3Failure] to close silently.
 */
interface QrTagGate {
    fun onTagMatch(purpose: Bmh3Purpose, msg1Ephemeral: ByteArray)
}

internal object Bmh3Crypto {
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val THREE = BigInteger.valueOf(3)
    private val TAG_INFO = "BluetoothVideoMesh/BMH3/ContactTag/1".toByteArray(Charsets.US_ASCII)
    private val QR_TAG_INFO = "BluetoothVideoMesh/BMH3/QrTag/1".toByteArray(Charsets.US_ASCII)

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    /** One 32-byte HKDF-Expand block: `HMAC(prk, info | 0x01)`. */
    fun expand(prk: ByteArray, info: ByteArray): ByteArray = hmac(prk, info, byteArrayOf(1))

    fun label(purpose: Bmh3Purpose, secret: ByteArray, label: String): ByteArray =
        expand(secret, purpose.domain + '/'.code.toByte() + label.toByteArray(Charsets.US_ASCII))

    fun th(purpose: Bmh3Purpose, bytes: ByteArray): ByteArray = sha256(purpose.domain + byteArrayOf(0) + bytes)

    fun signatureContext(purpose: Bmh3Purpose, role: String, transcriptHash: ByteArray): ByteArray =
        purpose.domain + "/$role signature".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + transcriptHash

    fun contactTagKey(leaseKey: ByteArray): ByteArray {
        require(leaseKey.size == 32) { "A lease key is 32 bytes" }
        return expand(leaseKey, TAG_INFO)
    }

    /** BVMID2: `K_tag = HKDF-Expand(K_qr, "BluetoothVideoMesh/BMH3/QrTag/1", 32)` (never `K_qr` itself). */
    fun qrTagKey(kQr: ByteArray): ByteArray {
        require(kQr.size == 32) { "K_qr is 32 bytes" }
        return expand(kQr, QR_TAG_INFO)
    }

    fun contactTag(purpose: Bmh3Purpose, tagKey: ByteArray, msg1WithZeroTag: ByteArray, binding: ByteArray): ByteArray =
        hmac(tagKey, th(purpose, msg1WithZeroTag), binding).copyOf(Bmh3Wire.TAG_BYTES)

    /**
     * What a tag binds to: the selected advert's 6 bytes, the role and, for a version 2 (fixed prefix) advert only, the
     * wall epoch of the initiator's clock. A version 5 RDI changes every epoch by itself, so its epoch field is 0.
     */
    fun advertBinding(selected6: ByteArray, role: Int, wallEpoch: Long?): ByteArray {
        require(selected6.size == 6 && role in 0..0xff)
        val epoch = (wallEpoch ?: 0L) and 0xffffffffL
        return selected6 + byteArrayOf(
            role.toByte(),
            (epoch ushr 24).toByte(), (epoch ushr 16).toByte(), (epoch ushr 8).toByte(), epoch.toByte(),
        )
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun isCanonicalSpki(spki: ByteArray): Boolean {
        if (spki.size != Bmh3Wire.SPKI_BYTES) return false
        if (!spki.copyOf(Bmh3Wire.P256_SPKI_PREFIX.size).contentEquals(Bmh3Wire.P256_SPKI_PREFIX)) return false
        return isOnCurve(spki.copyOfRange(Bmh3Wire.P256_SPKI_PREFIX.size, spki.size))
    }

    /** Explicit, provider-independent check of a 65-byte SEC1 point: `04`, both coordinates below p, on the curve. */
    fun isOnCurve(point: ByteArray): Boolean {
        if (point.size != Bmh3Wire.EPHEMERAL_BYTES || point[0].toInt() != 4) return false
        val x = BigInteger(1, point.copyOfRange(1, 33))
        val y = BigInteger(1, point.copyOfRange(33, 65))
        if (x >= P || y >= P) return false
        val left = y.multiply(y).mod(P)
        val right = x.multiply(x).multiply(x).subtract(x.multiply(THREE)).add(B).mod(P)
        return left == right
    }

    fun encodePoint(key: ECPublicKey): ByteArray {
        val out = ByteArray(Bmh3Wire.EPHEMERAL_BYTES)
        out[0] = 4
        fixed32(key.w.affineX).copyInto(out, 1)
        fixed32(key.w.affineY).copyInto(out, 33)
        return out
    }

    /** Decodes a validated point as a JCA key through the canonical SPKI form. */
    fun decodePoint(point: ByteArray): PublicKey {
        if (!isOnCurve(point)) throw Bmh3Failure("point not on curve")
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Bmh3Wire.P256_SPKI_PREFIX + point))
    }

    fun agree(privateKey: PrivateKey, peerPoint: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(privateKey)
        doPhase(decodePoint(peerPoint), true)
        generateSecret()
    }

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0].toInt() == 0 -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("Coordinate too large")
        }
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` (what the Keystore and JCA produce) to the raw 64-byte `r | s`. */
    fun derToRaw(der: ByteArray): ByteArray {
        var position = 0
        fun readLength(): Int {
            val first = der[position++].toInt() and 0xff
            if (first < 0x80) return first
            val count = first and 0x7f
            if (count !in 1..2) throw Bmh3Failure("signature encoding")
            var length = 0
            repeat(count) { length = (length shl 8) or (der[position++].toInt() and 0xff) }
            return length
        }
        fun readInteger(): ByteArray {
            if (der[position++].toInt() != 0x02) throw Bmh3Failure("signature encoding")
            val length = readLength()
            if (length < 1 || position + length > der.size) throw Bmh3Failure("signature encoding")
            val value = BigInteger(1, der.copyOfRange(position, position + length))
            position += length
            return fixed32(value)
        }
        try {
            if (der[position++].toInt() != 0x30) throw Bmh3Failure("signature encoding")
            val total = readLength()
            if (position + total != der.size) throw Bmh3Failure("signature encoding")
            val r = readInteger()
            val s = readInteger()
            if (position != der.size) throw Bmh3Failure("signature encoding")
            return r + s
        } catch (error: IndexOutOfBoundsException) {
            throw Bmh3Failure("signature encoding")
        } catch (error: IllegalArgumentException) {
            throw Bmh3Failure("signature encoding")
        }
    }

    /** Raw `r | s` to DER for JCA verification, after the explicit `0 < r, s < n` range check. */
    fun rawToDer(raw: ByteArray): ByteArray {
        if (raw.size != Bmh3Wire.SIGNATURE_BYTES) throw Bmh3Failure("signature length")
        val r = BigInteger(1, raw.copyOf(32))
        val s = BigInteger(1, raw.copyOfRange(32, 64))
        if (r.signum() <= 0 || s.signum() <= 0 || r >= N || s >= N) throw Bmh3Failure("signature range")
        fun integer(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }
        val body = integer(r) + integer(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

/** State shared by both roles: transcript, handshake keys, identity block build and open. */
internal abstract class Bmh3Party(
    protected val config: Bmh3Config,
    protected val role: String,
) {
    protected val transcript = ByteArrayOutputStream()
    protected lateinit var ephemeral: Bmh3Ephemeral
    protected var hs: ByteArray = ByteArray(0)
    protected var keyHsI2r: ByteArray = ByteArray(0)
    protected var keyHsR2i: ByteArray = ByteArray(0)
    protected var finishedI: ByteArray = ByteArray(0)
    protected var finishedR: ByteArray = ByteArray(0)
    protected var derived: ByteArray = ByteArray(0)
    protected var peer: Bmh3Peer? = null
    protected var order: Bmh3Order? = null
    protected var result: Bmh3Result? = null

    /** Tests only: replaces the rule above to prove the receiver's placement check (null: the real rule). */
    internal var testWifiMarkerOverride: Boolean? = null

    /** What this phone presents in its block: the real identity until a role decides to present the stranger one. */
    protected var presented: Bmh3Presentation = config.real

    protected fun append(bytes: ByteArray) = transcript.write(bytes)

    protected fun transcriptBytes(): ByteArray = transcript.toByteArray()

    protected fun deriveHandshakeKeys(peerEphemeral: ByteArray) {
        val dh = Bmh3Crypto.agree(ephemeral.privateKey, peerEphemeral)
        val th2 = Bmh3Crypto.th(config.purpose, transcriptBytes())
        hs = Bmh3Crypto.extract(th2, dh)
        dh.fill(0)
        keyHsI2r = Bmh3Crypto.label(config.purpose, hs, "hs i2r")
        keyHsR2i = Bmh3Crypto.label(config.purpose, hs, "hs r2i")
        finishedI = Bmh3Crypto.label(config.purpose, hs, "finished i")
        finishedR = Bmh3Crypto.label(config.purpose, hs, "finished r")
        derived = Bmh3Crypto.label(config.purpose, hs, "derived")
    }

    /** Builds this phone's identity block over the transcript so far plus the carrying message's cleartext part. */
    protected fun buildBlock(carrierPrefix: ByteArray, extensions: Bmh3Extensions): ByteArray {
        val capabilities = NodeCapabilitiesCodec.encode(presented.capabilities)
        val prefix = ByteArrayOutputStream(Bmh3Wire.BLOCK_BYTES)
        prefix.write(byteArrayOf((Bmh3Wire.SPKI_BYTES ushr 8).toByte(), Bmh3Wire.SPKI_BYTES.toByte()))
        prefix.write(presented.identity.publicKeyEncoded)
        prefix.write(capabilities)
        prefix.write(byteArrayOf((Bmh3Wire.EXTENSION_BYTES ushr 8).toByte(), Bmh3Wire.EXTENSION_BYTES.toByte()))
        // The build tag rides in this phone's own encrypted block (LINK handshakes only), never in a cleartext field.
        // A pseudonymous block never carries an opening: the salt of a rotating ID confirms the owner of a node ID.
        val ownExtensions = Bmh3Extensions(
            if (presented.pseudonym) null else extensions.opening,
            extensions.intent,
            config.buildTag,
            pseudonym = presented.pseudonym,
            relaySeek = if (role == "Responder") config.relaySeek else null,
            wifi = testWifiMarkerOverride ?: (role == "Initiator" && config.wifiMarker),
        )
        prefix.write(ownExtensions.encodeArea())
        val prefixBytes = prefix.toByteArray()
        val thSig = Bmh3Crypto.th(config.purpose, transcriptBytes() + carrierPrefix + prefixBytes)
        val signature = Bmh3Crypto.derToRaw(presented.identity.sign(Bmh3Crypto.signatureContext(config.purpose, role, thSig)))
        val key = if (role == "Initiator") finishedI else finishedR
        val mac = Bmh3Crypto.hmac(key, Bmh3Crypto.th(config.purpose, transcriptBytes() + carrierPrefix + prefixBytes + signature))
        return (prefixBytes + signature + mac).also { check(it.size == Bmh3Wire.BLOCK_BYTES) }
    }

    /**
     * Opens the peer's block: structure, signature under the peer's role label, finished MAC, extension rules,
     * self-connection. [carrierPrefix] is the cleartext part of the carrying message.
     */
    protected fun openBlock(carrierPrefix: ByteArray, block: ByteArray, peerRole: String, message: Int, blockOrder: Bmh3Order): Bmh3Peer {
        if (block.size != Bmh3Wire.BLOCK_BYTES) throw Bmh3Failure("identity block length")
        val spkiLength = ((block[0].toInt() and 0xff) shl 8) or (block[1].toInt() and 0xff)
        if (spkiLength != Bmh3Wire.SPKI_BYTES) throw Bmh3Failure("identity length")
        var position = 2
        val spki = block.copyOfRange(position, position + Bmh3Wire.SPKI_BYTES)
        position += Bmh3Wire.SPKI_BYTES
        val capabilityBytes = block.copyOfRange(position, position + NodeCapabilitiesCodec.ENCODED_BYTES)
        position += NodeCapabilitiesCodec.ENCODED_BYTES
        val extensionLength = ((block[position].toInt() and 0xff) shl 8) or (block[position + 1].toInt() and 0xff)
        position += 2
        if (extensionLength != Bmh3Wire.EXTENSION_BYTES) throw Bmh3Failure("extension area length")
        val extensionBytes = block.copyOfRange(position, position + Bmh3Wire.EXTENSION_BYTES)
        position += Bmh3Wire.EXTENSION_BYTES
        val prefixBytes = block.copyOf(position)
        val signature = block.copyOfRange(position, position + Bmh3Wire.SIGNATURE_BYTES)
        position += Bmh3Wire.SIGNATURE_BYTES
        val mac = block.copyOfRange(position, position + Bmh3Wire.MAC_BYTES)
        if (!Bmh3Crypto.isCanonicalSpki(spki)) throw Bmh3Failure("identity key invalid")
        val capabilities = try {
            NodeCapabilitiesCodec.decode(capabilityBytes)
        } catch (error: IllegalArgumentException) {
            throw Bmh3Failure("capabilities")
        }
        val prefixTranscript = transcriptBytes() + carrierPrefix + prefixBytes
        val verified = try {
            verifyEcdsa(
                decodeEcPublicKey(spki),
                Bmh3Crypto.signatureContext(config.purpose, peerRole, Bmh3Crypto.th(config.purpose, prefixTranscript)),
                Bmh3Crypto.rawToDer(signature),
            )
        } catch (error: GeneralSecurityException) {
            if (error is Bmh3Failure) throw error
            false
        }
        if (!verified) throw Bmh3Failure("signature")
        val peerFinished = if (peerRole == "Initiator") finishedI else finishedR
        val expected = Bmh3Crypto.hmac(peerFinished, Bmh3Crypto.th(config.purpose, prefixTranscript + signature))
        if (!Bmh3Crypto.constantTimeEquals(expected, mac)) throw Bmh3Failure("finished mac")
        val extensions = Bmh3Extensions.parse(extensionBytes)
        checkPlacement(extensions, peerRole == "Responder", message, blockOrder)
        if (spki.contentEquals(config.identity.publicKeyEncoded) ||
            spki.contentEquals(config.stranger?.identity?.publicKeyEncoded ?: ByteArray(0))
        ) {
            throw Bmh3Failure("self-connection")
        }
        return Bmh3Peer(spki, NodeId.fromPublicKey(spki), capabilities, extensions)
    }

    private fun checkPlacement(extensions: Bmh3Extensions, responderBlock: Boolean, message: Int, blockOrder: Bmh3Order) {
        if (extensions.buildTagSeen && config.purpose != Bmh3Purpose.LINK) throw Bmh3Failure("build tag outside the link handshake")
        if (extensions.opening != null && !responderBlock) throw Bmh3Failure("opening outside a responder block")
        if (extensions.relaySeek != null && (!responderBlock || config.purpose != Bmh3Purpose.LINK)) {
            throw Bmh3Failure("relay seek outside a responder link block")
        }
        // A throwaway key must not open a rotating ID: the salt would let a prober confirm the owner of a node ID.
        if (extensions.opening != null && extensions.pseudonym) throw Bmh3Failure("opening in a pseudonymous block")
        // The build marker is an initiator's statement in a link handshake only (a responder tells an unadmitted
        // initiator nothing; PROTOCOL "Capability: the WIFI marker").
        if (extensions.wifiSeen && (responderBlock || config.purpose != Bmh3Purpose.LINK)) {
            throw Bmh3Failure("wifi marker outside an initiator link block")
        }
        if (extensions.intent != null && (responderBlock || message != 3 || blockOrder != Bmh3Order.I_FIRST)) {
            throw Bmh3Failure("intent outside msg3 of an I_FIRST handshake")
        }
    }

    protected fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        MeshAead.encrypt(key, 0, plaintext, aad)

    protected fun unseal(key: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray = try {
        MeshAead.decrypt(key, 0, ciphertext, aad)
    } catch (error: GeneralSecurityException) {
        throw Bmh3Failure("aead")
    }

    protected fun finishSession(
        peer: Bmh3Peer,
        order: Bmh3Order,
        initiator: Boolean,
        tagMatched: Boolean,
        qrTagMatched: Boolean = false,
    ): Bmh3Result {
        val thFinal = Bmh3Crypto.th(config.purpose, transcriptBytes())
        val ms = Bmh3Crypto.extract(thFinal, derived)
        val i2r = Bmh3Crypto.label(config.purpose, ms, "app i2r")
        val r2i = Bmh3Crypto.label(config.purpose, ms, "app r2i")
        val exporter = Bmh3Crypto.label(config.purpose, ms, "exporter")
        ms.fill(0)
        hs.fill(0)
        derived.fill(0)
        keyHsI2r.fill(0)
        keyHsR2i.fill(0)
        finishedI.fill(0)
        finishedR.fill(0)
        return Bmh3Result(
            order, peer, if (initiator) i2r else r2i, if (initiator) r2i else i2r, exporter, tagMatched,
            ownPseudonymous = presented.pseudonym, ownSpki = presented.identity.publicKeyEncoded.copyOf(),
            qrTagMatched = qrTagMatched,
        )
    }

    protected fun header(message: Int, flags: Int): ByteArray =
        Bmh3Wire.MAGIC + byteArrayOf(Bmh3Wire.VERSION.toByte(), config.purpose.wire.toByte(), message.toByte(), flags.toByte())

    /** Validates magic, version, purpose and message number; returns the flags byte. */
    protected fun parseHeader(bytes: ByteArray, message: Int): Int {
        if (bytes.size < Bmh3Wire.HEADER_BYTES || !Bmh3Wire.isBmh3(bytes)) throw Bmh3Failure("magic")
        if (bytes[4].toInt() != Bmh3Wire.VERSION) throw Bmh3Failure("version")
        if (bytes[5].toInt() != config.purpose.wire) throw Bmh3Failure("purpose")
        if (bytes[6].toInt() != message) throw Bmh3Failure("message number")
        return bytes[7].toInt() and 0xff
    }
}

/**
 * The initiator. [mayRevealFirst] stays false in Phase 1 (SR1): I_FIRST is refused. [contactTagKey] is the
 * selected contact's `K_tag` (else 16 random bytes are sent); with a key, only R_FIRST is acceptable.
 * [checkResponder] runs after the responder's block verified and before this side reveals anything: it throws
 * to abort (discovery binding, verified-contact mismatch, admission).
 */
internal class Bmh3Initiator(
    config: Bmh3Config,
    private val mayRevealFirst: Boolean = false,
    private val contactTag: Bmh3ContactTag? = null,
    private val intent: Int? = null,
    private val checkResponder: (Bmh3Peer) -> Unit = {},
    /** Tests only: sends these extensions in msg3 whatever the order, to prove the receiver's placement rules. */
    private val testMsg3Extensions: Bmh3Extensions? = null,
) : Bmh3Party(config, "Initiator") {
    private var msg1: ByteArray = ByteArray(0)

    init {
        // Rule P: the real key goes only to the contact the caller looked for (it sent a tag and the answerer must
        // prove that contact's node ID before msg3); anyone else meets the stranger presentation, if there is one.
        if (contactTag == null) config.stranger?.let { presented = it }
    }

    /** The order the responder chose, once msg2 arrived. */
    val chosenOrder: Bmh3Order? get() = order

    fun message1(): ByteArray {
        check(msg1.isEmpty()) { "message1 already produced" }
        ephemeral = config.entropy.ephemeral()
        val nonce = config.entropy.nonce()
        val flags = if (mayRevealFirst) Bmh3Wire.FLAG_MAY_REVEAL_FIRST else 0
        val body = header(1, flags) + byteArrayOf(Bmh3Wire.OFFER_P256.toByte()) + ephemeral.publicBytes + nonce
        val tag = contactTag?.let {
            Bmh3Crypto.contactTag(config.purpose, it.key, body + ByteArray(Bmh3Wire.TAG_BYTES), it.binding)
        } ?: config.entropy.randomTag()
        msg1 = body + tag
        check(msg1.size == Bmh3Wire.MSG1_BYTES)
        append(msg1)
        return msg1
    }

    /** Returns msg3. After it, [isComplete] is true for R_FIRST; for I_FIRST msg4 is still expected. */
    fun onMessage2(message: ByteArray): ByteArray {
        check(msg1.isNotEmpty() && order == null) { "unexpected message2" }
        val flags = parseHeader(message, 2)
        if (flags and Bmh3Wire.FLAG_R_FIRST.inv() != 0) throw Bmh3Failure("reserved msg2 flags")
        if (message.size < Bmh3Wire.MSG2_CLEAR_BYTES) throw Bmh3Failure("short msg2")
        val clear = message.copyOf(Bmh3Wire.MSG2_CLEAR_BYTES)
        val rest = message.copyOfRange(Bmh3Wire.MSG2_CLEAR_BYTES, message.size)
        val peerEphemeral = clear.copyOfRange(Bmh3Wire.HEADER_BYTES, Bmh3Wire.HEADER_BYTES + Bmh3Wire.EPHEMERAL_BYTES)
        if (!Bmh3Crypto.isOnCurve(peerEphemeral)) throw Bmh3Failure("ephemeral")
        if (peerEphemeral.contentEquals(ephemeral.publicBytes)) throw Bmh3Failure("reflected ephemeral")
        append(clear)
        deriveHandshakeKeys(peerEphemeral)
        if (flags and Bmh3Wire.FLAG_R_FIRST != 0) {
            order = Bmh3Order.R_FIRST
            if (rest.size != Bmh3Wire.BLOCK_BYTES + MeshAead.TAG_BYTES) throw Bmh3Failure("msg2 length")
            val block = unseal(keyHsR2i, clear, rest)
            val responder = openBlock(ByteArray(0), block, "Responder", 2, Bmh3Order.R_FIRST)
            append(block)
            peer = responder
            checkResponder(responder)
            // Defence in depth: a real key never answers a throwaway (a contact's node ID cannot be one).
            if (responder.pseudonymous && !presented.pseudonym) throw Bmh3Failure("pseudonymous answerer to a real caller")
            return buildMessage3(Bmh3Order.R_FIRST)
        }
        order = Bmh3Order.I_FIRST
        if (rest.isNotEmpty()) throw Bmh3Failure("I_FIRST msg2 carries ciphertext")
        if (contactTag != null) throw Bmh3Failure("tag sent, I_FIRST answered")
        if (!mayRevealFirst) throw Bmh3Failure("I_FIRST refused")
        return buildMessage3(Bmh3Order.I_FIRST)
    }

    private fun buildMessage3(chosen: Bmh3Order): ByteArray {
        val h3 = header(3, 0)
        val extensions = testMsg3Extensions
            ?: if (chosen == Bmh3Order.I_FIRST) Bmh3Extensions(intent = intent) else Bmh3Extensions.NONE
        val block = buildBlock(h3, extensions)
        val message3 = h3 + seal(keyHsI2r, h3, block)
        append(h3)
        append(block)
        if (chosen == Bmh3Order.R_FIRST) result = finishSession(requireNotNull(peer), chosen, initiator = true, tagMatched = false)
        return message3
    }

    /** True once the session keys exist: after msg3 in R_FIRST, after msg4 in I_FIRST. */
    val isComplete: Boolean get() = result != null

    fun onMessage4(message: ByteArray) {
        check(order == Bmh3Order.I_FIRST && result == null) { "unexpected message4" }
        val flags = parseHeader(message, 4)
        if (flags != 0) throw Bmh3Failure("reserved msg4 flags")
        if (message.size != Bmh3Wire.HEADER_BYTES + Bmh3Wire.BLOCK_BYTES + MeshAead.TAG_BYTES) throw Bmh3Failure("msg4 length")
        val h4 = message.copyOf(Bmh3Wire.HEADER_BYTES)
        val block = unseal(keyHsR2i, h4, message.copyOfRange(Bmh3Wire.HEADER_BYTES, message.size))
        val responder = openBlock(h4, block, "Responder", 4, Bmh3Order.I_FIRST)
        append(h4)
        append(block)
        peer = responder
        // I_FIRST: this phone revealed first, but the answerer is still bound to the selected advert before keys exist.
        checkResponder(responder)
        result = finishSession(responder, Bmh3Order.I_FIRST, initiator = true, tagMatched = false)
    }

    fun result(): Bmh3Result = requireNotNull(result) { "handshake not complete" }
}

/**
 * The responder. [mode] picks the order (SR4); [tagKeys] are the `K_tag` values of its own lease keys for the
 * current epoch window. [beforeReply] runs for every R_FIRST msg1 (told whether its tag matched), before a key is drawn or a
 * signature made (the stranger tarpit, SR11); it may sleep or throw. [admitInitiator] runs after the initiator's
 * block verified: false refuses it (R_FIRST) or withholds the responder's identity (I_FIRST).
 */
internal class Bmh3Responder(
    config: Bmh3Config,
    private val mode: Bmh3ResponderMode = Bmh3ResponderMode.EVERYONE,
    private val tagChecks: List<Bmh3ContactTag> = emptyList(),
    private val opening: ByteArray? = null,
    private val replayCache: Bmh3ReplayCache? = null,
    private val beforeReply: (tagMatched: Boolean) -> Unit = {},
    /** After the initiator's block verified: (peer, tagMatched) -> admit. A Contacts-only phone checks the lease recipients here. */
    private val admitInitiator: (Bmh3Peer, Boolean) -> Boolean = { _, _ -> true },
) : Bmh3Party(config, "Responder") {
    private var started = false
    private var tagMatched = false
    private var qrMatched = false
    private var msg2Clear: ByteArray = ByteArray(0)

    val chosenOrder: Bmh3Order? get() = order

    /** True when the matching tag was a verification code's (BVMID2; valid after [onMessage1]). */
    val qrTagMatched: Boolean get() = qrMatched

    /** True when msg1's tag matched one of the lease keys (valid after [onMessage1]). */
    val contactTagMatched: Boolean get() = tagMatched

    /** Returns msg2. A [Bmh3Failure] means: close silently, send nothing. */
    fun onMessage1(message: ByteArray): ByteArray {
        check(!started) { "message1 already handled" }
        started = true
        val flags = parseHeader(message, 1)
        if (flags and Bmh3Wire.FLAG_MAY_REVEAL_FIRST.inv() != 0) throw Bmh3Failure("reserved msg1 flags")
        if (message.size != Bmh3Wire.MSG1_BYTES) throw Bmh3Failure("msg1 length")
        if (message[Bmh3Wire.HEADER_BYTES].toInt() != Bmh3Wire.OFFER_P256) throw Bmh3Failure("suite")
        val peerEphemeral = message.copyOfRange(Bmh3Wire.HEADER_BYTES + 1, Bmh3Wire.HEADER_BYTES + 1 + Bmh3Wire.EPHEMERAL_BYTES)
        if (!Bmh3Crypto.isOnCurve(peerEphemeral)) throw Bmh3Failure("ephemeral")
        if (replayCache != null && !replayCache.firstUse(peerEphemeral)) throw Bmh3Failure("replay")
        val body = message.copyOf(Bmh3Wire.MSG1_BYTES - Bmh3Wire.TAG_BYTES)
        val tag = message.copyOfRange(Bmh3Wire.MSG1_BYTES - Bmh3Wire.TAG_BYTES, Bmh3Wire.MSG1_BYTES)
        // Evaluate every candidate (no early exit): the cost does not depend on which key matched.
        var matched = false
        var qrHit = false
        var qrGateHit: QrTagGate? = null
        for (check in tagChecks) {
            val expected = Bmh3Crypto.contactTag(config.purpose, check.key, body + ByteArray(Bmh3Wire.TAG_BYTES), check.binding)
            if (Bmh3Crypto.constantTimeEquals(expected, tag)) {
                matched = true
                if (check.qrGate != null && qrGateHit == null) qrGateHit = check.qrGate
            }
        }
        // A verification code: reserve it for this attempt (or fail silently) before anything else happens.
        qrGateHit?.let { gate ->
            gate.onTagMatch(config.purpose, peerEphemeral)
            qrHit = true
        }
        tagMatched = matched
        qrMatched = qrHit
        val chosen = if (mode == Bmh3ResponderMode.EVERYONE || tagMatched) Bmh3Order.R_FIRST else Bmh3Order.I_FIRST
        if (chosen == Bmh3Order.I_FIRST && flags and Bmh3Wire.FLAG_MAY_REVEAL_FIRST == 0) throw Bmh3Failure("order conflict")
        // Every R_FIRST msg1 passes the hook (a lease holder too: the Meet screen refuses everything but its code).
        if (chosen == Bmh3Order.R_FIRST) beforeReply(tagMatched)
        order = chosen
        // Rule P: an R_FIRST answer to a msg1 whose tag did not match is the stranger presentation, if there is one.
        // I_FIRST answers only an admitted initiator, so it stays real.
        if (chosen == Bmh3Order.R_FIRST && !tagMatched) config.stranger?.let { presented = it }
        // Fresh values for every handshake (SR5): one instance serves one msg1 only.
        ephemeral = config.entropy.ephemeral()
        if (ephemeral.publicBytes.contentEquals(peerEphemeral)) throw Bmh3Failure("reflected ephemeral")
        val nonce = config.entropy.nonce()
        append(message)
        msg2Clear = header(2, if (chosen == Bmh3Order.R_FIRST) Bmh3Wire.FLAG_R_FIRST else 0) + ephemeral.publicBytes + nonce
        append(msg2Clear)
        deriveHandshakeKeys(peerEphemeral)
        if (chosen == Bmh3Order.I_FIRST) return msg2Clear
        val block = buildBlock(ByteArray(0), Bmh3Extensions(opening = opening))
        append(block)
        return msg2Clear + seal(keyHsR2i, msg2Clear, block)
    }

    /** Returns msg4 for I_FIRST, null for R_FIRST (the handshake is complete). */
    fun onMessage3(message: ByteArray): ByteArray? {
        val chosen = checkNotNull(order) { "unexpected message3" }
        check(result == null) { "handshake already complete" }
        val flags = parseHeader(message, 3)
        if (flags != 0) throw Bmh3Failure("reserved msg3 flags")
        if (message.size != Bmh3Wire.MSG3_BYTES) throw Bmh3Failure("msg3 length")
        val h3 = message.copyOf(Bmh3Wire.HEADER_BYTES)
        val block = unseal(keyHsI2r, h3, message.copyOfRange(Bmh3Wire.HEADER_BYTES, message.size))
        val initiator = openBlock(h3, block, "Initiator", 3, chosen)
        append(h3)
        append(block)
        peer = initiator
        if (!admitInitiator(initiator, tagMatched)) throw Bmh3Failure(if (chosen == Bmh3Order.R_FIRST) "initiator refused" else "stranger gets a silent close")
        if (chosen == Bmh3Order.R_FIRST) {
            result = finishSession(initiator, chosen, initiator = false, tagMatched = tagMatched, qrTagMatched = qrMatched)
            return null
        }
        val h4 = header(4, 0)
        val block4 = buildBlock(h4, Bmh3Extensions(opening = opening))
        val message4 = h4 + seal(keyHsR2i, h4, block4)
        append(h4)
        append(block4)
        result = finishSession(initiator, chosen, initiator = false, tagMatched = tagMatched, qrTagMatched = qrMatched)
        return message4
    }

    val isComplete: Boolean get() = result != null

    fun result(): Bmh3Result = requireNotNull(result) { "handshake not complete" }
}
