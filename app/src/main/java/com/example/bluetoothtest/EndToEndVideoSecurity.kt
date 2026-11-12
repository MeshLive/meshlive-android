package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.ArrayDeque

enum class EndToEndContentType(val wireValue: Int) {
    STREAM_HEADER(1),
    MEDIA_PACKET(2),
    STREAM_STATE(4),
    KEY_FRAME_REQUEST(5),
    PRIVATE_CALL_SIGNAL(6),
    GROUP_KEY(7),
    STREAM_END_ACK(8),

    /** Experimental call video fragments (CallVideoFragments); only after both endpoints advertised them. */
    VIDEO_FRAGMENT(9),

    /** Experimental peer labels (PeerLabelFeature): one `BLB1` container; only after both endpoint hellos set bit 5. */
    LABELS(10),

    /**
     * Experimental rotating discovery IDs (RotatingDiscovery): one `BDX1` container (OPENING, RECOGNITION_OFFER,
     * PRESENCE_LEASE); only after both call endpoint hellos set bit 3 (0x08).
     */
    DISCOVERY(11),

    /**
     * Experimental in-call link renewal (Design SR, `test_call_link_renewal`): one `RENEW` record (OFFER, ARMED, REFUSED,
     * BIND); only after this endpoint signed end-to-end hello bit 4 ([CallRenewalWire]).
     */
    RENEW(12),
}

enum class StreamLifecycleState(val wireValue: Int) {
    LIVE(1),
    PAUSED(2),
    ENDED(3),

    /**
     * Media-state records (experimental, `live_media_state`; PROTOCOL "Experimental live media state"): the
     * stream is live, but the broadcaster has muted the microphone and/or turned the camera off. One record per
     * change, carried like PAUSED/LIVE; media packets are untouched. Older builds reject the unknown value, so a
     * broadcaster sends them only with the switch on.
     */
    LIVE_MUTED(4),
    LIVE_CAMERA_OFF(5),
    LIVE_MUTED_CAMERA_OFF(6),
    ;

    val micMuted: Boolean get() = this == LIVE_MUTED || this == LIVE_MUTED_CAMERA_OFF
    val cameraOff: Boolean get() = this == LIVE_CAMERA_OFF || this == LIVE_MUTED_CAMERA_OFF

    /** The stream is not paused or ended (plain LIVE or a media-state record). */
    val isLiveFamily: Boolean get() = this != PAUSED && this != ENDED

    companion object {
        /** The live-family record that describes which of microphone/camera are off. */
        fun live(micMuted: Boolean, cameraOff: Boolean): StreamLifecycleState = when {
            micMuted && cameraOff -> LIVE_MUTED_CAMERA_OFF
            micMuted -> LIVE_MUTED
            cameraOff -> LIVE_CAMERA_OFF
            else -> LIVE
        }

        fun decode(payload: ByteArray): StreamLifecycleState {
            require(payload.size == 1) { "Invalid stream-state message length" }
            val wireValue = payload[0].toInt() and 0xff
            return entries.firstOrNull { it.wireValue == wireValue }
                ?: error("Unknown stream state $wireValue")
        }
    }

    fun encode(): ByteArray = byteArrayOf(wireValue.toByte())
}

sealed interface ReceivedStreamContent {
    /** [recordUnits]: units of the record this packet came in (1, 2 a pair, 3..20 a batch), 0 for its later units. */
    data class Media(val packet: StreamProtocol.Packet, val recordUnits: Int = 1) : ReceivedStreamContent
    data class State(val state: StreamLifecycleState) : ReceivedStreamContent
}

enum class StreamRouteWithdrawal(val wireValue: Int) {
    LOCAL_CAPACITY(1),
}

object StreamRouteControlProtocol {
    fun encode(reason: StreamRouteWithdrawal): ByteArray = byteArrayOf(reason.wireValue.toByte())

    fun decode(payload: ByteArray): StreamRouteWithdrawal {
        require(payload.size == 1) { "Invalid route-control message length" }
        val wireValue = payload[0].toInt() and 0xff
        return StreamRouteWithdrawal.entries.firstOrNull { it.wireValue == wireValue }
            ?: error("Unknown route withdrawal $wireValue")
    }
}

class StreamRouteWithdrawnException(val reason: StreamRouteWithdrawal) :
    IOException("Immediate parent withdrew this stream route: ${reason.name}")

enum class SourceControlMessage {
    LINK_HEARTBEAT,
    KEY_FRAME_REQUEST,
    STREAM_END_ACK,
    ROUTE_WITHDRAWAL_ACK,
}

data class EndToEndContentMetadata(
    val type: EndToEndContentType,
    val sequence: Long,
    val flags: Int,
    val ciphertextBytes: Int,
)

data class EndToEndContentRecord(
    val type: EndToEndContentType,
    val flags: Int,
    val payload: ByteArray,
    /** The record's `BME1` sequence when it came from an opener (link renewal checks BIND against it); -1 otherwise. */
    val sequence: Long = -1L,
)

class EndToEndContentSealer internal constructor(key: ByteArray) : Closeable {
    private val key = key.copyOf()
    private var nextSequence = 0L
    private var corruptNextRecord = false
    /** Experimental AEAD reuse (MeshAead.Context), null when off. */
    private val aead = if (DebugTunables.aeadCipherReuse) MeshAead.Context() else null

    @Synchronized
    fun seal(type: EndToEndContentType, flags: Int, payload: ByteArray): ByteArray {
        require(flags in 0..0xff) { "Invalid end-to-end content flags" }
        require(payload.size <= EndToEndContentProtocol.MAX_PLAINTEXT_BYTES) {
            "End-to-end content is too large"
        }
        val header = EndToEndContentProtocol.encodeHeader(
            type,
            nextSequence,
            flags,
            payload.size + MeshAead.TAG_BYTES,
        )
        if (aead != null) {
            // Header and ciphertext sealed straight into one array (the same bytes as `header + ciphertext`).
            val record = aead.sealAfter(key, nextSequence, header, payload)
            if (corruptNextRecord && record.size > header.size) {
                record[record.lastIndex] = (record.last().toInt() xor 1).toByte()
                corruptNextRecord = false
            }
            nextSequence++
            return record
        }
        val ciphertext = MeshAead.encrypt(key, nextSequence, payload, header)
        if (corruptNextRecord && ciphertext.isNotEmpty()) {
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            corruptNextRecord = false
        }
        nextSequence++
        return header + ciphertext
    }

    /** Seals [payloadFor] of the sequence it gets, atomically (link renewal's BIND names its own next sequence). */
    @Synchronized
    internal fun sealNext(type: EndToEndContentType, flags: Int, payloadFor: (Long) -> ByteArray): ByteArray =
        seal(type, flags, payloadFor(nextSequence))

    internal fun corruptNextRecordForTest() {
        corruptNextRecord = true
    }

    /** The forwarded-stream key this sealer's receiver passes on in its `BFG1` grant (recovery hint, RV18). */
    internal fun copyKeyForForwarding(): ByteArray = key.copyOf()

    override fun close() {
        key.fill(0)
        aead?.release()
    }
}

class EndToEndContentOpener internal constructor(key: ByteArray) : Closeable {
    private val key = key.copyOf()
    private var nextSequence = 0L
    /** Experimental AEAD reuse (MeshAead.Context), null when off. */
    private val aead = if (DebugTunables.aeadCipherReuse) MeshAead.Context() else null

    /**
     * Experimental call video fragments: only a call endpoint that advertised them sets this. Every
     * other opener rejects content type 9 exactly as an unknown type, as before.
     */
    @Volatile internal var acceptVideoFragments = false

    /**
     * Experimental peer labels: only an endpoint that advertised labels (hello bit 5) sets this.
     * Every other opener rejects content type 10 exactly as an unknown type, as before.
     */
    @Volatile internal var acceptLabels = false

    /**
     * Experimental rotating discovery IDs: only a call endpoint that advertised hello bit 3 sets this.
     * Every other opener rejects content type 11 exactly as an unknown type, as before.
     */
    @Volatile internal var acceptDiscovery = false

    /**
     * Experimental in-call link renewal: only a call endpoint that signed end-to-end hello bit 4 sets this. Every other
     * opener rejects content type 12 exactly as an unknown type, as before.
     */
    @Volatile internal var acceptRenewal = false

    internal fun copyKeyForForwarding(): ByteArray = key.copyOf()

    @Synchronized
    fun open(encoded: ByteArray): EndToEndContentRecord {
        val metadata = EndToEndContentProtocol.inspect(encoded, acceptVideoFragments, acceptLabels, acceptDiscovery, acceptRenewal)
        require(metadata.sequence >= nextSequence) {
            "Replayed or reordered end-to-end sequence ${metadata.sequence}; expected at least $nextSequence"
        }
        val plaintext = try {
            val headerBytes = EndToEndContentProtocol.HEADER_BYTES
            // Experimental AEAD reuse opens the record in place: the same header as associated data, the same body.
            aead?.decryptSlice(key, metadata.sequence, encoded, headerBytes, headerBytes, encoded.size - headerBytes)
                ?: MeshAead.decrypt(
                    key,
                    metadata.sequence,
                    encoded.copyOfRange(headerBytes, encoded.size),
                    encoded.copyOfRange(0, headerBytes),
                )
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException(
                "End-to-end decrypt failed: type=${metadata.type} sequence=${metadata.sequence} " +
                    "ciphertextBytes=${metadata.ciphertextBytes}",
                error,
            )
        }
        nextSequence = metadata.sequence + 1
        return EndToEndContentRecord(metadata.type, metadata.flags, plaintext, metadata.sequence)
    }

    /**
     * Opens one record without the stream's order check and without moving it: only for the first record of a freshly proved
     * renewal link (BIND), whose BMS1 layer already makes it fresh and single. Every other record goes through [open].
     */
    @Synchronized
    internal fun openOutOfBand(encoded: ByteArray): EndToEndContentRecord {
        val metadata = EndToEndContentProtocol.inspect(encoded, acceptVideoFragments, acceptLabels, acceptDiscovery, acceptRenewal)
        require(metadata.type == EndToEndContentType.RENEW) { "Out-of-band open is for RENEW only" }
        val plaintext = try {
            val headerBytes = EndToEndContentProtocol.HEADER_BYTES
            // Experimental AEAD reuse opens the record in place: the same header as associated data, the same body.
            aead?.decryptSlice(key, metadata.sequence, encoded, headerBytes, headerBytes, encoded.size - headerBytes)
                ?: MeshAead.decrypt(
                    key,
                    metadata.sequence,
                    encoded.copyOfRange(headerBytes, encoded.size),
                    encoded.copyOfRange(0, headerBytes),
                )
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException(
                "End-to-end decrypt failed: type=${metadata.type} sequence=${metadata.sequence} " +
                    "ciphertextBytes=${metadata.ciphertextBytes}",
                error,
            )
        }
        return EndToEndContentRecord(metadata.type, metadata.flags, plaintext, metadata.sequence)
    }

    override fun close() {
        key.fill(0)
        aead?.release()
    }
}

object EndToEndContentProtocol {
    private val magic = byteArrayOf('B'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    const val HEADER_BYTES = 18
    const val MAX_PLAINTEXT_BYTES = StreamProtocol.MAX_PAYLOAD_BYTES + StreamProtocol.PACKET_HEADER_BYTES

    internal fun encodeHeader(
        type: EndToEndContentType,
        sequence: Long,
        flags: Int,
        ciphertextBytes: Int,
    ): ByteArray = ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
        DataOutputStream(bytes).apply {
            write(magic)
            writeByte(type.wireValue)
            writeLong(sequence)
            writeByte(flags)
            writeInt(ciphertextBytes)
        }
    }.toByteArray()

    fun inspect(
        encoded: ByteArray,
        acceptVideoFragments: Boolean = false,
        acceptLabels: Boolean = false,
        acceptDiscovery: Boolean = false,
        acceptRenewal: Boolean = false,
    ): EndToEndContentMetadata {
        require(encoded.size >= HEADER_BYTES) { "Truncated end-to-end content" }
        val input = DataInputStream(ByteArrayInputStream(encoded, 0, HEADER_BYTES))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid end-to-end content magic" }
        val typeValue = input.readUnsignedByte()
        val type = EndToEndContentType.entries.firstOrNull { it.wireValue == typeValue }
            ?.takeIf { it != EndToEndContentType.VIDEO_FRAGMENT || acceptVideoFragments }
            ?.takeIf { it != EndToEndContentType.LABELS || acceptLabels }
            ?.takeIf { it != EndToEndContentType.DISCOVERY || acceptDiscovery }
            ?.takeIf { it != EndToEndContentType.RENEW || acceptRenewal }
            ?: error("Unknown end-to-end content type $typeValue")
        val sequence = input.readLong()
        require(sequence >= 0) { "Invalid end-to-end content sequence" }
        val flags = input.readUnsignedByte()
        val ciphertextBytes = input.readInt()
        require(ciphertextBytes in MeshAead.TAG_BYTES..MAX_PLAINTEXT_BYTES + MeshAead.TAG_BYTES) {
            "Invalid end-to-end content size $ciphertextBytes"
        }
        require(encoded.size == HEADER_BYTES + ciphertextBytes) { "Truncated end-to-end content payload" }
        require(type == EndToEndContentType.MEDIA_PACKET || type == EndToEndContentType.VIDEO_FRAGMENT || flags == 0) {
            "Non-media end-to-end content cannot carry packet flags"
        }
        return EndToEndContentMetadata(type, sequence, flags, ciphertextBytes)
    }

    /**
     * The sequence of an end-to-end content record, without interpreting its type byte (A7 Am 12: a relay forwards
     * content types it does not know). Same magic check as [inspect].
     */
    fun sequenceOf(encoded: ByteArray): Long {
        require(encoded.size >= HEADER_BYTES) { "Truncated end-to-end content" }
        val input = DataInputStream(ByteArrayInputStream(encoded, 0, HEADER_BYTES))
        val receivedMagic = ByteArray(magic.size).also(input::readFully)
        require(receivedMagic.contentEquals(magic)) { "Invalid end-to-end content magic" }
        input.skipBytes(1) // The type byte is never interpreted here.
        val sequence = input.readLong()
        require(sequence >= 0) { "Invalid end-to-end content sequence" }
        return sequence
    }
}

data class EndToEndVideoSession(
    val peerNodeId: NodeId,
    val peerCapabilities: NodeCapabilities,
    val sealer: EndToEndContentSealer,
    val opener: EndToEndContentOpener,
    /** Full SHA-256 fingerprint of the peer's authenticated identity key (PROTOCOL "Identity verification"). */
    val peerFingerprint: IdentityFingerprint,
    /**
     * BMH3 pseudonyms (PROTOCOL "Phase 2: pseudonymous stranger identities"): non-null when either end presented a
     * throwaway key; it holds the reveal rules and, once the peer's reveal verified, the peer's real identity.
     */
    val reveal: Bmh3RevealSession? = null,
    /** BVMID2 (responder): the handshake's tag was the displayed verification code's; the session is verify-only. */
    val qrTagMatched: Boolean = false,
    /** Experimental in-call link renewal (Design SR): `K_renew`, derived from the two content keys; zeroed at close. */
    val renewalKey: ByteArray? = null,
    /**
     * Wi-Fi upgrade (Design WF): `UW` of this call, `HKDF-Expand(EXP_e2e, "BluetoothVideoMesh/WifiUpgrade/1/key", 32)`, derived
     * from the BMH3 end-to-end exporter before the handshake keys were erased. Null for a BMH2 end-to-end handshake
     * (package A5 declines such a call) and after a failed derivation. Zeroed by [close] and [dropWifiKey] (call end,
     * emergency wipe; never on a switch change); survives an in-call link renewal. [R M-5]
     */
    val wifiKey: ByteArray? = null,
) : Closeable {
    /** True while the peer is only a declared throwaway key: show "unverified stranger", never a node ID. */
    val peerIsPseudonym: Boolean get() = reveal?.peerPseudonymous == true && reveal.revealed == null

    /** The peer's real node ID once revealed, else the handshake's (a pseudonym's, or the real one for a contact). */
    val effectivePeerNodeId: NodeId get() = reveal?.revealed?.nodeId ?: peerNodeId

    /** The fingerprint [effectivePeerNodeId] corresponds to. */
    val effectivePeerFingerprint: IdentityFingerprint
        get() = reveal?.revealed?.let { IdentityFingerprint.fromPublicKey(it.spki) } ?: peerFingerprint

    /**
     * Whether the person of this session is a verified contact in [stateOf] (a verified-contact store lookup). Judged on the
     * real identity ([effectivePeerNodeId]): after a BMH3 reveal the revealed person, never the throwaway, which is never a
     * contact; a peer that is still a throwaway is never verified. Presence records, labels and claims all use this rule.
     */
    fun peerVerifiedIn(stateOf: (NodeId, IdentityFingerprint) -> VerificationState?): Boolean =
        !peerIsPseudonym && stateOf(effectivePeerNodeId, effectivePeerFingerprint) == VerificationState.VERIFIED

    /** Idempotent; called at call end and by the emergency wipe, never on a switch change. [R M-5] */
    fun dropWifiKey() {
        wifiKey?.fill(0)
    }

    override fun close() {
        sealer.close()
        opener.close()
        reveal?.close()
        renewalKey?.fill(0)
        dropWifiKey()
    }
}

internal fun meshPacketFlags(mediaFlags: Int): Int =
    mediaFlags and (MeshEnvelope.FLAG_CONTROL or MeshEnvelope.FLAG_TERMINAL).inv()

class EndToEndStreamWriter(
    private val linkWriter: HopRecordSink,
    private val contentSealer: EndToEndContentSealer,
    sourceNodeId: NodeId,
    useMeshEnvelope: Boolean,
    initialTtl: Int = DEFAULT_MESH_TTL,
    meshStreamId: UUID? = null,
) {
    private val streamId = if (useMeshEnvelope) meshStreamId ?: UUID.randomUUID() else null
    private val sourceNodeId = sourceNodeId
    private val initialTtl = initialTtl
    private var nextMeshSequence = 0L

    init {
        require(initialTtl in 1..0xff)
        require(useMeshEnvelope || meshStreamId == null) { "A direct stream cannot set a mesh stream ID" }
    }

    val meshStreamId: UUID?
        get() = streamId

    fun writeHeader(
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int,
        includeAudio: Boolean,
    ): Int = writeSealed(
        EndToEndContentType.STREAM_HEADER,
        0,
        EndToEndStreamProtocol.encodeHeader(codecId, settings, rotationDegrees, includeAudio),
    )

    /**
     * Audio-only live stream header (BTL1, with the signer's key block), in place of [writeHeader]; exactly one per
     * stream. After it the writer refuses [writePacket] and only sends [writeAudioLiveRecord]: every record of the
     * stream is signed by the source.
     */
    fun writeAudioLiveHeader(signer: AudioLiveSigner): Int {
        require(signer.streamId == (streamId ?: AudioLive.ZERO_STREAM_ID)) {
            "The audio signer is bound to another stream ID"
        }
        audioLive = true
        return writeSealed(EndToEndContentType.STREAM_HEADER, 0, signer.headerBytes)
    }

    /** One record signed once by the source ([AudioLiveSigner.sign]); the signature is shared by every child. */
    fun writeAudioLiveRecord(record: AudioLiveSignedRecord): Int {
        require(audioLive) { "The audio-only header has not been written" }
        return writeSealed(
            EndToEndContentType.MEDIA_PACKET,
            record.flags,
            EndToEndStreamProtocol.encodePacket(record.flags, record.presentationTimeUs, record.payload),
        )
    }

    @Volatile private var audioLive = false

    fun writePacket(flags: Int, presentationTimeUs: Long, payload: ByteArray): Int {
        require(!audioLive) { "An audio-only stream sends only signed records" }
        return writeSealed(
            EndToEndContentType.MEDIA_PACKET,
            flags,
            EndToEndStreamProtocol.encodePacket(flags, presentationTimeUs, payload),
        )
    }

    fun writeState(state: StreamLifecycleState): Int = writeSealed(
        EndToEndContentType.STREAM_STATE,
        0,
        state.encode(),
        if (state == StreamLifecycleState.ENDED) MeshEnvelope.FLAG_TERMINAL else 0,
    )

    private fun writeSealed(
        type: EndToEndContentType,
        flags: Int,
        plaintext: ByteArray,
        additionalMeshFlags: Int = 0,
    ): Int {
        val sealed = contentSealer.seal(type, flags, plaintext)
        val activeStreamId = streamId
            ?: return linkWriter.write(SecureRecordType.END_TO_END_CONTENT, sealed)
        val meshFlags = meshPacketFlags(flags) or additionalMeshFlags or if (type == EndToEndContentType.STREAM_STATE) {
            MeshEnvelope.FLAG_CONTROL
        } else {
            0
        }
        val envelope = MeshEnvelope(
            streamId = activeStreamId,
            sourceNodeId = sourceNodeId,
            sequence = nextMeshSequence++,
            ttl = initialTtl,
            flags = meshFlags,
            payload = sealed,
        )
        return linkWriter.write(SecureRecordType.MESH_ENVELOPE, MeshEnvelopeCodec.encode(envelope))
    }

    companion object {
        const val DEFAULT_MESH_TTL = 8
    }
}

class EndToEndStreamReader(
    private val linkReader: HopRecordSource,
    private val contentOpener: EndToEndContentOpener,
    private val expectedSourceNodeId: NodeId,
    private val onMeshEnvelope: (MeshEnvelope) -> Unit = {},
) {
    /** Clock for the audio-only arrival budgets; replaceable in tests (set before [readHeader]). */
    internal var audioClockMs: () -> Long = { System.nanoTime() / 1_000_000L }

    private enum class WireMode { DIRECT, MESH }

    /** Non-null once an audio-only (BTL1) header was read: every media record then passes it or the stream fails. */
    private var audioGuard: AudioLiveStreamGuard? = null

    private var wireMode: WireMode? = null
    private var streamId: UUID? = null
    private var lastMeshSequence = -1L
    private val duplicates = DuplicateWindow()
    private val pendingMedia = ArrayDeque<StreamProtocol.Packet>()

    /**
     * Audio-only live streams: the mesh envelope of the record just opened, held back from [onMeshEnvelope] (the
     * forwarder) until this reader's own checks of that record passed: the header's certificate, an audio record's
     * source signature, a lifecycle record's decoding. A forged or unsigned record is therefore never queued to a
     * child. Video streams forward right after the end-to-end open, as before.
     */
    private var heldEnvelope: MeshEnvelope? = null
    private var holdForwarding = false

    private fun forwardHeldEnvelope() {
        val envelope = heldEnvelope ?: return
        heldEnvelope = null
        onMeshEnvelope(envelope)
    }

    val meshStreamId: UUID?
        get() = streamId

    /** The TTL of the last mesh envelope read (null for a direct stream): the viewer's hop count is the initial TTL minus it. */
    @Volatile var lastMeshTtl: Int? = null
        private set

    /**
     * Media packet payload bytes read so far and when the last arrived, counted before an audio pair
     * is split: the bytes the parent counts as written (link report version 3 delivery).
     */
    internal val delivery = LinkDeliveryCounter()

    /**
     * [allowAudioOnly] false: a `BTL1` header fails as "Unsupported stream header" like a build without
     * audio-only streams. True: the returned header may be audio-only ([StreamProtocol.Header.isAudioOnly]) and
     * the reader then enforces the audio-only media rules and the media-clock bound.
     */
    fun readHeader(allowAudioOnly: Boolean = false): StreamProtocol.Header {
        // A reader that may meet an audio-only stream bounds its first record to a header-sized allocation.
        if (allowAudioOnly) linkReader.maxCiphertextBytes = AudioLive.MAX_HEADER_CIPHERTEXT_BYTES
        // A header that may be audio-only is forwarded only after its signer block verified (below).
        holdForwarding = allowAudioOnly
        val content = readContent()
        require(content.type == EndToEndContentType.STREAM_HEADER) {
            "Expected an end-to-end stream header"
        }
        return EndToEndStreamProtocol.decodeHeader(content.payload, allowAudioOnly).also { header ->
            audioGuard = if (header.isAudioOnly) {
                // Fails closed here when the certificate does not name the expected source or does not verify.
                AudioLiveStreamGuard(
                    AudioLiveVerifier.fromHeader(header, streamId ?: AudioLive.ZERO_STREAM_ID, expectedSourceNodeId),
                    audioClockMs,
                ).also {
                    it.label = (streamId ?: AudioLive.ZERO_STREAM_ID).toString().take(8)
                    linkReader.maxCiphertextBytes = AudioLive.MAX_RECORD_CIPHERTEXT_BYTES
                }
            } else {
                if (allowAudioOnly) linkReader.maxCiphertextBytes = Int.MAX_VALUE
                null
            }
            holdForwarding = audioGuard != null
            forwardHeldEnvelope()
        }
    }

    fun readStreamContent(): ReceivedStreamContent {
        pendingMedia.pollFirst()?.let { return ReceivedStreamContent.Media(it, recordUnits = 0) }
        val content = readContent()
        return when (content.type) {
            EndToEndContentType.MEDIA_PACKET -> {
                val guard = audioGuard
                if (guard != null) guard.checkSealedSize(content.payload.size)
                val received = EndToEndStreamProtocol.decodePacket(content)
                // Audio-only: verifies the source's signature and strips it; the packet then looks like any audio.
                val packet = guard?.open(received) ?: received
                // Audio-only: only now, after the source signature verified, may a child get this record.
                forwardHeldEnvelope()
                delivery.add(received.payload.size)
                if (packet.flags and StreamProtocol.FLAG_AUDIO_PAIR != 0) {
                    val split = StreamProtocol.splitAudioPair(packet)
                    split.drop(1).forEach(pendingMedia::addLast) // a pair has one more unit, a batch up to 19
                    ReceivedStreamContent.Media(split[0], recordUnits = split.size)
                } else {
                    ReceivedStreamContent.Media(packet)
                }
            }
            EndToEndContentType.STREAM_STATE -> {
                // A pause or resume may leave a timestamp gap; the media-clock baseline starts again.
                audioGuard?.onLifecycleRecord()
                ReceivedStreamContent.State(StreamLifecycleState.decode(content.payload)).also { forwardHeldEnvelope() }
            }
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                error("Private-call signals are not broadcast stream content")
            else -> error("Unexpected end-to-end stream content ${content.type}")
        }
    }

    private fun readContent(): EndToEndContentRecord {
        val record = linkReader.read()
        var authenticatedEnvelope: MeshEnvelope? = null
        val sealed = when (record.type) {
            SecureRecordType.END_TO_END_CONTENT -> {
                require(wireMode != WireMode.MESH) { "Mesh stream changed to direct records" }
                wireMode = WireMode.DIRECT
                record.payload
            }
            SecureRecordType.MESH_ENVELOPE -> {
                require(wireMode != WireMode.DIRECT) { "Direct stream changed to mesh envelopes" }
                wireMode = WireMode.MESH
                val envelope = MeshEnvelopeCodec.decode(record.payload)
                require(envelope.sourceNodeId == expectedSourceNodeId) {
                    "Mesh source identity does not match the endpoint handshake"
                }
                require(envelope.ttl > 0) { "Mesh packet arrived with expired TTL" }
                val activeStreamId = streamId
                if (activeStreamId == null) {
                    streamId = envelope.streamId
                } else {
                    require(envelope.streamId == activeStreamId) { "Mesh stream ID changed" }
                }
                require(envelope.sequence > lastMeshSequence) {
                    "Replayed or reordered mesh sequence ${envelope.sequence}"
                }
                require(duplicates.markIfNew(envelope)) { "Duplicate mesh packet" }
                lastMeshSequence = envelope.sequence
                lastMeshTtl = envelope.ttl
                authenticatedEnvelope = envelope
                envelope.payload
            }
            SecureRecordType.ROUTE_CONTROL ->
                throw StreamRouteWithdrawnException(StreamRouteControlProtocol.decode(record.payload))
            else -> error("Expected end-to-end stream content, got ${record.type}")
        }
        return contentOpener.open(sealed).also {
            val envelope = authenticatedEnvelope ?: return@also
            if (holdForwarding) heldEnvelope = envelope else onMeshEnvelope(envelope)
        }
    }
}

object EndToEndVideoHandshake {
    /** [bmh3] null (default): BMH2, exactly as before; non-null: the experimental BMH3 end-to-end handshake. */
    fun initiate(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        bmh3: Bmh3InitiatorOptions? = null,
    ): EndToEndVideoSession {
        if (bmh3 != null) return Bmh3EndpointHandshake.initiate(link, identity, capabilities, bmh3)
        val pending = MeshHandshake.start(
            identity,
            HandshakeRole.INITIATOR,
            capabilities,
            purpose = HandshakePurpose.END_TO_END,
        )
        link.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, HandshakeHelloCodec.encode(pending.hello))
        link.writer.flush()
        val remote = readHello(link)
        val keys = MeshHandshake.finish(pending, remote)
        return session(remote, keys, renewal = CallRenewalWire.bothSigned(capabilities, remote.capabilities))
    }

    /**
     * [bmh3] null (default): BMH2 only, a BMH3 first record is rejected as an invalid handshake message exactly
     * as by an old build. Non-null: the first record's magic selects BMH3 or (when allowed) BMH2.
     */
    fun respond(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        /** A first record the caller already read from [link] (experimental store-and-forward dispatch). */
        firstRecord: SecureRecord? = null,
        bmh3: Bmh3ResponderOptions? = null,
    ): EndToEndVideoSession {
        val remote = if (bmh3 == null) {
            readHello(link, firstRecord)
        } else {
            val record = firstRecord ?: link.reader.read()
            require(record.type == SecureRecordType.END_TO_END_HANDSHAKE) { "Expected an end-to-end handshake" }
            if (Bmh3Wire.isBmh3(record.payload)) {
                return Bmh3EndpointHandshake.respond(link, record, identity, capabilities, bmh3)
            }
            if (!bmh3.acceptBmh2) {
                bmh3.log("bmh3: event=refused_bmh2 | layer=endpoint")
                throw Bmh3Failure("bmh2 refused")
            }
            HandshakeHelloCodec.decode(record.payload)
        }
        require(remote.role == HandshakeRole.INITIATOR) { "Expected an end-to-end initiator" }
        val pending = MeshHandshake.start(
            identity,
            HandshakeRole.RESPONDER,
            capabilities,
            purpose = HandshakePurpose.END_TO_END,
        )
        val keys = MeshHandshake.finish(pending, remote)
        link.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, HandshakeHelloCodec.encode(pending.hello))
        link.writer.flush()
        return session(remote, keys, renewal = CallRenewalWire.bothSigned(capabilities, remote.capabilities))
    }

    private fun readHello(link: SecureBluetoothSession, firstRecord: SecureRecord? = null): SignedHandshakeHello {
        val record = firstRecord ?: link.reader.read()
        require(record.type == SecureRecordType.END_TO_END_HANDSHAKE) {
            "Expected an end-to-end handshake"
        }
        return HandshakeHelloCodec.decode(record.payload)
    }

    /** [renewal]: both hellos signed the link-renewal bit (a call with renewal on); only then is `K_renew` derived. */
    private fun session(
        remote: SignedHandshakeHello,
        keys: MeshSessionKeys,
        renewal: Boolean,
    ): EndToEndVideoSession {
        // Throws before any content key exists when a verified contact's prefix shows another key.
        val fingerprint = try {
            PeerIdentityGate.admit(keys.peerNodeId, remote.identityPublicKey)
        } catch (error: RuntimeException) {
            keys.sendKey.fill(0)
            keys.receiveKey.fill(0)
            throw error
        }
        return sessionWithFingerprint(remote, keys, fingerprint, renewal)
    }

    private fun sessionWithFingerprint(
        remote: SignedHandshakeHello,
        keys: MeshSessionKeys,
        fingerprint: IdentityFingerprint,
        renewal: Boolean,
    ): EndToEndVideoSession = EndToEndVideoSession(
        peerNodeId = keys.peerNodeId,
        peerCapabilities = remote.capabilities,
        sealer = EndToEndContentSealer(keys.sendKey),
        opener = EndToEndContentOpener(keys.receiveKey),
        peerFingerprint = fingerprint,
        renewalKey = if (renewal) CallRenewalCrypto.renewalKey(keys.sendKey, keys.receiveKey) else null,
    ).also {
        keys.sendKey.fill(0)
        keys.receiveKey.fill(0)
    }
}

object EndToEndStreamProtocol {
    fun encodeHeader(
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int,
        includeAudio: Boolean,
    ): ByteArray = ByteArrayOutputStream().also {
        StreamProtocol.writeHeader(it, codecId, settings, rotationDegrees, includeAudio)
    }.toByteArray()


    /** [allowAudioOnly] is true only for a broadcast-stream reader; calls and video-only callers keep the default. */
    fun decodeHeader(payload: ByteArray, allowAudioOnly: Boolean = false): StreamProtocol.Header {
        val input = ByteArrayInputStream(payload)
        return StreamProtocol.readHeader(input, allowAudioOnly).also {
            require(input.available() == 0) { "Trailing end-to-end stream header data" }
        }
    }

    fun encodePacket(
        flags: Int,
        presentationTimeUs: Long,
        payload: ByteArray,
    ): ByteArray = ByteArrayOutputStream(StreamProtocol.PACKET_HEADER_BYTES + payload.size).also {
        StreamProtocol.writePacket(it, flags, presentationTimeUs, payload)
    }.toByteArray()

    fun decodePacket(content: EndToEndContentRecord): StreamProtocol.Packet {
        require(content.type == EndToEndContentType.MEDIA_PACKET) { "Expected a media packet" }
        val input = ByteArrayInputStream(content.payload)
        return StreamProtocol.readPacket(input).also {
            require(input.available() == 0) { "Trailing end-to-end media packet data" }
            require(it.flags == content.flags) {
                "End-to-end packet flags do not match authenticated metadata"
            }
        }
    }

    fun writeHeader(
        linkWriter: RecordSink,
        contentSealer: EndToEndContentSealer,
        codecId: Int,
        settings: VideoSettings,
        rotationDegrees: Int,
        includeAudio: Boolean,
    ): Int {
        val plaintext = encodeHeader(codecId, settings, rotationDegrees, includeAudio)
        return writeContent(
            linkWriter,
            contentSealer.seal(EndToEndContentType.STREAM_HEADER, 0, plaintext),
        )
    }

    fun readHeader(
        linkReader: HopRecordSource,
        contentOpener: EndToEndContentOpener,
    ): StreamProtocol.Header {
        val content = readContent(linkReader, contentOpener, EndToEndContentType.STREAM_HEADER)
        return decodeHeader(content.payload)
    }

    fun writePacket(
        linkWriter: RecordSink,
        contentSealer: EndToEndContentSealer,
        flags: Int,
        presentationTimeUs: Long,
        payload: ByteArray,
    ): Int {
        val plaintext = encodePacket(flags, presentationTimeUs, payload)
        return writeContent(
            linkWriter,
            contentSealer.seal(EndToEndContentType.MEDIA_PACKET, flags, plaintext),
        )
    }

    /**
     * Experimental call video fragments: writes one fragment payload (CallVideoFragments.split) of
     * a video packet with [flags]. Sealed exactly like a media packet, with its own sequence.
     */
    fun writeVideoFragment(
        linkWriter: RecordSink,
        contentSealer: EndToEndContentSealer,
        flags: Int,
        fragment: ByteArray,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(EndToEndContentType.VIDEO_FRAGMENT, flags, fragment),
    )

    /** Decodes a reassembled fragmented packet with the same checks as a whole media packet. */
    fun decodeReassembledPacket(flags: Int, plaintext: ByteArray): StreamProtocol.Packet =
        decodePacket(EndToEndContentRecord(EndToEndContentType.MEDIA_PACKET, flags, plaintext))

    fun readPacket(
        linkReader: HopRecordSource,
        contentOpener: EndToEndContentOpener,
    ): StreamProtocol.Packet {
        val content = readContent(linkReader, contentOpener, EndToEndContentType.MEDIA_PACKET)
        return decodePacket(content)
    }

    fun writeState(
        linkWriter: RecordSink,
        contentSealer: EndToEndContentSealer,
        state: StreamLifecycleState,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(EndToEndContentType.STREAM_STATE, 0, state.encode()),
    )

    fun readStreamContent(
        linkReader: HopRecordSource,
        contentOpener: EndToEndContentOpener,
    ): ReceivedStreamContent {
        val record = linkReader.read()
        if (record.type == SecureRecordType.ROUTE_CONTROL) {
            throw StreamRouteWithdrawnException(StreamRouteControlProtocol.decode(record.payload))
        }
        require(record.type == SecureRecordType.END_TO_END_CONTENT) { "Expected end-to-end content" }
        val content = contentOpener.open(record.payload)
        return when (content.type) {
            EndToEndContentType.MEDIA_PACKET -> {
                val input = ByteArrayInputStream(content.payload)
                val packet = StreamProtocol.readPacket(input)
                require(input.available() == 0) { "Trailing end-to-end media packet data" }
                require(packet.flags == content.flags) {
                    "End-to-end packet flags do not match authenticated metadata"
                }
                ReceivedStreamContent.Media(packet)
            }
            EndToEndContentType.STREAM_STATE ->
                ReceivedStreamContent.State(StreamLifecycleState.decode(content.payload))
            EndToEndContentType.PRIVATE_CALL_SIGNAL ->
                error("Private-call signals are not broadcast stream content")
            else -> error("Unexpected end-to-end stream content ${content.type}")
        }
    }

    fun writeKeyFrameRequest(
        linkWriter: RecordSink,
        contentSealer: EndToEndContentSealer,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(
            EndToEndContentType.KEY_FRAME_REQUEST,
            0,
            ByteArray(0),
        ),
    )

    fun writeStreamEndAck(
        linkWriter: RecordSink,
        contentSealer: EndToEndContentSealer,
    ): Int = writeContent(
        linkWriter,
        contentSealer.seal(
            EndToEndContentType.STREAM_END_ACK,
            0,
            ByteArray(0),
        ),
    )

    fun readSourceControl(
        linkReader: HopRecordSource,
        contentOpener: EndToEndContentOpener,
        onCapacityReport: (LinkCapacityReport) -> Unit = {},
    ): SourceControlMessage {
        val record = linkReader.read()
        return when (record.type) {
            SecureRecordType.END_TO_END_CONTENT -> {
                val content = contentOpener.open(record.payload)
                require(
                    content.type == EndToEndContentType.KEY_FRAME_REQUEST ||
                        content.type == EndToEndContentType.STREAM_END_ACK,
                ) {
                    "Expected an end-to-end receiver control"
                }
                require(content.payload.isEmpty()) { "Invalid receiver control" }
                if (content.type == EndToEndContentType.KEY_FRAME_REQUEST) {
                    SourceControlMessage.KEY_FRAME_REQUEST
                } else {
                    SourceControlMessage.STREAM_END_ACK
                }
            }
            SecureRecordType.LINK_HEARTBEAT -> {
                SecureLinkControlProtocol.requireHeartbeat(record)?.let(onCapacityReport)
                SourceControlMessage.LINK_HEARTBEAT
            }
            SecureRecordType.ROUTE_CONTROL_ACK -> {
                StreamRouteControlProtocol.decode(record.payload)
                SourceControlMessage.ROUTE_WITHDRAWAL_ACK
            }
            else -> error("Unexpected source control record ${record.type}")
        }
    }

    private fun writeContent(writer: RecordSink, encoded: ByteArray): Int =
        writer.write(SecureRecordType.END_TO_END_CONTENT, encoded)

    private fun readContent(
        reader: HopRecordSource,
        opener: EndToEndContentOpener,
        expectedType: EndToEndContentType,
    ): EndToEndContentRecord {
        val record = reader.read()
        require(record.type == SecureRecordType.END_TO_END_CONTENT) { "Expected end-to-end content" }
        return opener.open(record.payload).also {
            require(it.type == expectedType) { "Expected end-to-end $expectedType content" }
        }
    }
}
