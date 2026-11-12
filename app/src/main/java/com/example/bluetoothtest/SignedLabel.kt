package com.example.bluetoothtest

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Peer labels: optional names and stream titles (the kind 3 call subject is retired, 2026-11-05). Promoted 2026-10-11; since 2026-10-17 a
 * constant with no Settings switch, [CallFeatureDefaults.NAMES_AND_TITLES]. A
 * DEBUG benchmark launch uses only `test_peer_labels`. Naming design phase 1 with review fixes R1-R10
 * (PROTOCOL "Peer labels").
 *
 * Off: no hello bit, no `LABELS` record, no title pages; every byte on air and on the link is
 * unchanged. On: the end-to-end hello sets transport-mask bit 5 and, only when both endpoint hellos
 * carry it, exactly one `LABELS` record travels at a fixed point (calls: before Invite / before
 * Accept; streams: before the header, or after `GROUP_KEY` on a forwarded hop).
 */
object PeerLabelFeature {
    const val EXTRA = "test_peer_labels"
    const val PREF_NAME = "peer_label_name"

    /** End-to-end hello transport-mask bit 5: "accepts and sends LABELS (BME1 type 10)". Never in link hellos. */
    const val CAPABILITY_BIT = 1 shl 5

    @Volatile var enabled = false

    /**
     * Naming v2 (Design G, G1): the PUBLIC stream title pages are a separate switch from the in-session names and titles
     * ([enabled]). A title is a title, not a person, and the Go-live dialog says it is public. The in-session stream title
     * (the signed label a joining viewer receives) still needs [enabled]; the advert pages and reading others' pages need
     * this. A constant like [enabled] ([NamingFeatures.TITLE_PAGES]); a DEBUG benchmark launch with `test_peer_labels`
     * keeps both.
     */
    @Volatile var titlePagesEnabled = false

    /** Who sees the name ("Everyone I call or answer", "Only people I verified", "Nobody"). */
    @Volatile internal var audience: NamesAudience = NamesAudience.EVERYONE

    /**
     * Experimental "Show my name before they answer" (`names_before_answer`, off since 2026-11-06): true queues the
     * caller's name before `INVITE` in a stranger (pseudonym) session. False, the default of every launch, holds it until
     * the callee's verified REVEAL passes the block check ([CallerNameRelease]).
     */
    @Volatile var nameBeforeAnswer = false

    /** Your optional display name (already normalized), or null for anonymous (handle only). */
    @Volatile var localName: String? = null

    /** Title of the stream this phone is broadcasting (fixed for the stream's lifetime). */
    @Volatile var localStreamTitle: String? = null

    internal fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): Boolean = CallFeatureResolver.enabled(CallFeature.NAMES_AND_TITLES, benchmarkLaunch, extra, stored)

    fun advertise(capabilities: NodeCapabilities, enabled: Boolean = this.enabled): NodeCapabilities =
        if (enabled) capabilities.copy(transportMask = capabilities.transportMask or CAPABILITY_BIT) else capabilities

    /** True only when this endpoint advertised labels and the peer's endpoint hello did too. */
    fun negotiated(localEnabled: Boolean, peerCapabilities: NodeCapabilities): Boolean =
        localEnabled && peerCapabilities.transportMask and CAPABILITY_BIT != 0
}

/** What a label is bound to (R2): a `BMC2` call ID or a stream ID; never free-floating. */
internal data class LabelBinding(val type: Int, val id: UUID) {
    fun bytes(): ByteArray = java.nio.ByteBuffer.allocate(16)
        .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    companion object {
        const val CALL = 1
        const val STREAM = 2

        /** A direct stream without a mesh stream ID: accepted only from the endpoint peer itself. */
        val DIRECT_STREAM = LabelBinding(STREAM, UUID(0L, 0L))

        fun call(callId: UUID) = LabelBinding(CALL, callId)
        fun stream(streamId: UUID?) = if (streamId == null) DIRECT_STREAM else LabelBinding(STREAM, streamId)
    }
}

/** One decoded `BNL1` label; [encoded] is the exact wire bytes, forwarded unchanged. */
internal class SignedLabel(
    val kindValue: Int,
    val spki: ByteArray,
    val sequence: Long,
    val binding: LabelBinding,
    val text: String,
    val signature: ByteArray,
    val encoded: ByteArray,
    internal val signedBytes: ByteArray,
) {
    val kind: LabelKind? get() = LabelKind.fromWire(kindValue)
}

internal class LabelRejected(val reason: String) : IllegalArgumentException(reason)

internal object SignedLabelCodec {
    private val MAGIC = "BNL1".toByteArray(Charsets.US_ASCII)
    private val CONTAINER_MAGIC = "BLB1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val MAX_SPKI_BYTES = 512
    /** Still 64: a retired kind 3 call subject from an older build (up to 64 bytes) must decode so it can be skipped. */
    const val MAX_TEXT_BYTES = 64
    const val MAX_SIGNATURE_BYTES = 256
    const val MAX_LABELS = 3

    /** R8: terminated domain, so no other signing context can be a prefix of it. */
    val DOMAIN: ByteArray = "BluetoothVideoMesh/Label/1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    fun signedPart(
        kind: Int,
        spki: ByteArray,
        sequence: Long,
        binding: LabelBinding,
        text: String,
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(spki.size in 1..MAX_SPKI_BYTES) { "SPKI length" }
        require(textBytes.size in 1..MAX_TEXT_BYTES) { "text length" }
        require(sequence in 0..0xffff_ffffL) { "sequence" }
        require(binding.type == LabelBinding.CALL || binding.type == LabelBinding.STREAM) { "binding type" }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(MAGIC)
                writeByte(VERSION)
                writeByte(kind)
                writeShort(spki.size)
                write(spki)
                writeInt(sequence.toInt())
                writeByte(binding.type)
                write(binding.bytes())
                writeByte(textBytes.size)
                write(textBytes)
            }
        }.toByteArray()
    }

    /** Signs one label with the identity key ([identity].sign is the Keystore key on phones). */
    fun sign(
        identity: SigningIdentity,
        kind: LabelKind,
        binding: LabelBinding,
        text: String,
        sequence: Long = 0,
    ): ByteArray {
        val canonical = LabelText.normalize(kind, text)
        val unsigned = signedPart(kind.wireValue, identity.publicKeyEncoded, sequence, binding, canonical)
        val signature = identity.sign(DOMAIN + unsigned)
        require(signature.size in 1..MAX_SIGNATURE_BYTES) { "signature length" }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(unsigned)
                writeShort(signature.size)
                write(signature)
            }
        }.toByteArray()
    }

    /** Structural decode only (no signature or binding check). */
    fun decode(encoded: ByteArray): SignedLabel {
        val input = DataInputStream(ByteArrayInputStream(encoded))
        val magic = ByteArray(4).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw LabelRejected("magic")
        if (input.readUnsignedByte() != VERSION) throw LabelRejected("version")
        val kind = input.readUnsignedByte()
        val spkiLength = input.readUnsignedShort()
        if (spkiLength !in 1..MAX_SPKI_BYTES) throw LabelRejected("spki length")
        val spki = ByteArray(spkiLength).also { input.readFully(it) }
        val sequence = input.readInt().toLong() and 0xffff_ffffL
        val bindingType = input.readUnsignedByte()
        if (bindingType != LabelBinding.CALL && bindingType != LabelBinding.STREAM) throw LabelRejected("binding type")
        val binding = LabelBinding(bindingType, UUID(input.readLong(), input.readLong()))
        val textLength = input.readUnsignedByte()
        if (textLength !in 1..MAX_TEXT_BYTES) throw LabelRejected("text length")
        val textBytes = ByteArray(textLength).also { input.readFully(it) }
        val signedLength = encoded.size - input.available()
        val signatureLength = input.readUnsignedShort()
        if (signatureLength !in 1..MAX_SIGNATURE_BYTES) throw LabelRejected("signature length")
        val signature = ByteArray(signatureLength).also { input.readFully(it) }
        if (input.available() != 0) throw LabelRejected("trailing bytes")
        val decoder = Charsets.UTF_8.newDecoder()
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(textBytes)).toString() }
            .getOrElse { throw LabelRejected("utf8") }
        return SignedLabel(kind, spki, sequence, binding, text, signature, encoded.copyOf(), encoded.copyOf(signedLength))
    }

    /**
     * Receiver checks in PROTOCOL order: known kind, SPKI hashes to [expectedSigner], signature,
     * binding equals [expectedBinding], text canonical. Throws [LabelRejected] with a reason.
     */
    fun verify(label: SignedLabel, expectedSigner: NodeId, expectedBinding: LabelBinding): LabelKind {
        val kind = label.kind ?: throw LabelRejected("unknown kind")
        val signer = NodeId.fromBytes(MessageDigest.getInstance("SHA-256").digest(label.spki).copyOf(NodeId.BYTE_COUNT))
        if (signer != expectedSigner) throw LabelRejected("signer")
        val key = runCatching { decodeEcPublicKey(label.spki) }.getOrElse { throw LabelRejected("key") }
        // A malformed DER signature makes some providers throw instead of returning false; either
        // way the label is dropped, never the call or stream (failure rule).
        val valid = runCatching { verifyEcdsa(key, DOMAIN + label.signedBytes, label.signature) }.getOrDefault(false)
        if (!valid) throw LabelRejected("signature")
        if (label.binding != expectedBinding) throw LabelRejected("binding")
        LabelText.rejection(kind, label.text)?.let { throw LabelRejected("text $it") }
        return kind
    }

    /** `BLB1` container: count 0..3, each label length-prefixed (R4: count 0 is valid). */
    fun encodeContainer(labels: List<ByteArray>): ByteArray {
        require(labels.size <= MAX_LABELS) { "Too many labels" }
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(CONTAINER_MAGIC)
                writeByte(labels.size)
                labels.forEach { label ->
                    require(label.size in 1..0xffff)
                    writeShort(label.size)
                    write(label)
                }
            }
        }.toByteArray()
    }

    /** Label count of a container for logs, or null when malformed. */
    fun decodeContainerCountOrNull(payload: ByteArray): Int? = runCatching { decodeContainer(payload).size }.getOrNull()

    /** Raw label byte strings from a container; a malformed container throws [LabelRejected]. */
    fun decodeContainer(payload: ByteArray): List<ByteArray> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val magic = runCatching { ByteArray(4).also { input.readFully(it) } }.getOrElse { throw LabelRejected("container") }
        if (!magic.contentEquals(CONTAINER_MAGIC)) throw LabelRejected("container magic")
        val count = runCatching { input.readUnsignedByte() }.getOrElse { throw LabelRejected("container") }
        if (count > MAX_LABELS) throw LabelRejected("container count")
        val labels = (0 until count).map {
            runCatching {
                val length = input.readUnsignedShort()
                ByteArray(length).also { input.readFully(it) }
            }.getOrElse { throw LabelRejected("container truncated") }
        }
        if (input.available() != 0) throw LabelRejected("container trailing bytes")
        return labels
    }
}

/** Labels verified in one session (call or stream hop). Nothing outlives the session (R2, Q8). */
internal class LabelInbox(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val accepted = linkedMapOf<LabelKind, SignedLabel>()
    private val arrivals = ArrayDeque<Long>()

    /** Verified kinds by text. */
    fun text(kind: LabelKind): String? = synchronized(this) { accepted[kind]?.text }

    /** Exact verified bytes for forwarding unchanged (forwarders pass only what they verified). */
    fun verifiedEncoded(): List<ByteArray> = synchronized(this) { accepted.values.map { it.encoded.copyOf() } }

    /**
     * Applies one `LABELS` payload. Labels that fail any check are dropped (logged without text);
     * the session never fails because of a label. More than one container per 2 s or eight per
     * minute is ignored. Returns the kinds that changed.
     */
    fun accept(payload: ByteArray, expectedSigner: NodeId, expectedBinding: LabelBinding, context: String): Set<LabelKind> =
        synchronized(this) {
            val now = nowMs()
            while (arrivals.isNotEmpty() && now - arrivals.first() > 60_000L) arrivals.removeFirst()
            if (arrivals.isNotEmpty() && now - arrivals.last() < 2_000L || arrivals.size >= 8) {
                logRejected(context, null, "rate")
                return emptySet()
            }
            arrivals.addLast(now)
            val raw = try {
                SignedLabelCodec.decodeContainer(payload)
            } catch (error: LabelRejected) {
                logRejected(context, null, error.reason)
                return emptySet()
            }
            val changed = mutableSetOf<LabelKind>()
            raw.forEach { bytes ->
                val label = try {
                    SignedLabelCodec.decode(bytes)
                } catch (error: LabelRejected) {
                    logRejected(context, null, error.reason)
                    return@forEach
                } catch (error: java.io.IOException) {
                    logRejected(context, null, "truncated")
                    return@forEach
                } catch (error: Exception) {
                    logRejected(context, null, "malformed")
                    return@forEach
                }
                // Unknown kinds are skipped for later versions, and so is the retired kind 3 call subject from older ones.
                if (label.kind == null) return@forEach
                val kind = try {
                    SignedLabelCodec.verify(label, expectedSigner, expectedBinding)
                } catch (error: LabelRejected) {
                    logRejected(context, label.kind, error.reason)
                    return@forEach
                } catch (error: Exception) {
                    logRejected(context, label.kind, "invalid")
                    return@forEach
                }
                val previous = accepted[kind]
                if (previous != null && label.sequence <= previous.sequence) return@forEach
                accepted[kind] = label
                changed += kind
            }
            changed
        }

    private fun logRejected(context: String, kind: LabelKind?, reason: String) {
        runCatching {
            Log.w("BluetoothVideo", "SECURITY LABEL REJECTED kind=${kind?.name?.lowercase() ?: "-"} reason=$reason context=$context")
        }
    }
}

/**
 * Signs local labels once per binding (one Keystore signature per call or stream, not per record).
 * Forwarded streams never sign: they pass on the source's verified bytes.
 */
internal object LocalLabels {
    private val cache = object : LinkedHashMap<Pair<LabelKind, LabelBinding>, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<LabelKind, LabelBinding>, ByteArray>?) = size > 16
    }

    fun signed(identity: SigningIdentity, kind: LabelKind, binding: LabelBinding, text: String?): ByteArray? {
        val value = text?.takeIf { it.isNotBlank() } ?: return null
        val canonical = runCatching { LabelText.normalize(kind, value) }.getOrNull() ?: return null
        val key = kind to binding
        synchronized(cache) { cache[key] }?.let { cached ->
            val decoded = runCatching { SignedLabelCodec.decode(cached) }.getOrNull()
            // Same text and the current identity key (never a label signed before an identity change).
            if (decoded != null && decoded.text == canonical && decoded.spki.contentEquals(identity.publicKeyEncoded)) return cached
        }
        val encoded = runCatching { SignedLabelCodec.sign(identity, kind, binding, canonical) }.getOrNull() ?: return null
        synchronized(cache) { cache[key] = encoded }
        return encoded
    }

    internal fun clear() = synchronized(cache) { cache.clear() }
}

/** Writing and reading the single `LABELS` record at its fixed position on a stream hop. */
internal object LabelRecords {
    fun write(writer: HopRecordSink, sealer: EndToEndContentSealer, labels: List<ByteArray>) {
        writer.write(
            SecureRecordType.END_TO_END_CONTENT,
            sealer.seal(EndToEndContentType.LABELS, 0, SignedLabelCodec.encodeContainer(labels.take(SignedLabelCodec.MAX_LABELS))),
        )
    }

    /** Reads the promised `LABELS` record; any other record here is a framing error (fails closed). */
    fun read(reader: HopRecordSource, opener: EndToEndContentOpener): ByteArray {
        val record = reader.read()
        require(record.type == SecureRecordType.END_TO_END_CONTENT) { "Expected a LABELS record" }
        val opened = opener.open(record.payload)
        require(opened.type == EndToEndContentType.LABELS) { "Expected a LABELS record" }
        return opened.payload
    }

    /**
     * Streams: a `LABELS` record is exchanged only when both endpoint hellos carry bit 5 and the link
     * peer is the endpoint peer (no opaque stream relay in between, which inspects BME1 types).
     */
    fun streamHopNegotiated(localEnabled: Boolean, linkPeer: NodeId, endpointPeer: NodeId, peerCapabilities: NodeCapabilities): Boolean =
        PeerLabelFeature.negotiated(localEnabled, peerCapabilities) && linkPeer == endpointPeer
}
