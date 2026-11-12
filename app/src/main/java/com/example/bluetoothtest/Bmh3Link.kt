package com.example.bluetoothtest

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The BMH3 I/O drivers (PROTOCOL "Experimental BMH3 identity-hiding link handshake"). The state machines in
 * Bmh3.kt know nothing about transports; these drivers move their messages over the two carriers BMH2
 * already uses: `u32 length | message` frames on the raw link stream, and `BMS1` type-4 records inside an
 * established link session. They return the same session objects as the BMH2 paths, so everything behind a
 * handshake (records, labels, openings, admission) is unchanged.
 */

/** What an initiator needs besides its identity (all optional; the defaults are a plain Everyone-mode caller). */
class Bmh3InitiatorOptions(
    /** The tag for the selected contact (from its lease key, bound to the selected advert), or null for a random tag. */
    val contactTag: Bmh3ContactTag? = null,
    /**
     * Runs after the responder's block verified and before this phone reveals itself; throws to abort. This is
     * where the discovery binding of the selected advert and the verified-contact check belong.
     */
    val checkResponder: (Bmh3Peer) -> Unit = {},
    val entropy: Bmh3Entropy = SecureBmh3Entropy,
    /**
     * The identity this phone presents at the LINK layer instead of its own. Toward a first hop that is a relay
     * (route cost above 0) the real key is not needed and a fake relay must not learn it, so the caller passes a
     * throwaway software key; the end-to-end handshake still proves the real key to the callee.
     */
    val identityOverride: SigningIdentity? = null,
    /**
     * The throwaway this caller presents to anyone it did not recognise as a contact (PROTOCOL "Phase 2: pseudonymous
     * stranger identities", rule P): a fresh key for this attempt. Null: the real identity to everyone (BMH3 Phase 1).
     */
    val strangerIdentity: SigningIdentity? = null,
    /** One log line per handshake, no identifiers (see [Bmh3Log]). */
    val log: (String) -> Unit = {},
    /**
     * Wi-Fi upgrade: sign the `WIFI` build marker in this initiator's LINK block. Default false (the bytes of every
     * handshake are unchanged). Set by [Bmh3AttemptPlan] (step 4) and by the relay-duty call sites through
     * [Bmh3Wiring.initiator] / [Bmh3Wiring.relayHop] (package A7), never for an end-to-end handshake. [R M-3]
     */
    val wifiMarker: Boolean = false,
) {
    /** The same options with the marker set as asked; every other field is shared. */
    fun withWifiMarker(on: Boolean): Bmh3InitiatorOptions = if (on == wifiMarker) this else Bmh3InitiatorOptions(
        contactTag, checkResponder, entropy, identityOverride, strangerIdentity, log, on,
    )
}

/** What a responder needs besides its identity. */
class Bmh3ResponderOptions(
    val mode: Bmh3ResponderMode = Bmh3ResponderMode.EVERYONE,
    /** `(K_tag, binding)` of this phone's own lease keys and the adverts currently or just on air. */
    val tagChecks: List<Bmh3ContactTag> = emptyList(),
    /** The `OPENING` value (`count | entries`) this phone proves in its block, or null. */
    val opening: ByteArray? = null,
    val replayCache: Bmh3ReplayCache? = null,
    /** For an R_FIRST msg1 without a matching tag, before any key is drawn or signature made (the signing budget). */
    val beforeReply: (tagMatched: Boolean) -> Unit = {},
    /** After the initiator's block verified: false refuses it (R_FIRST) or withholds this phone's identity (I_FIRST). */
    val admitInitiator: (Bmh3Peer, tagMatched: Boolean) -> Boolean = { _, _ -> true },
    /** False: a BMH2 first message is a silent close (a Contacts-only responder: a replayed BMH2 hello would draw it out). */
    val acceptBmh2: Boolean = true,
    val entropy: Bmh3Entropy = SecureBmh3Entropy,
    /**
     * The epoch pseudonym this phone answers a msg1 without a matching tag with (rule P); null: the real identity to
     * everyone. The same key serves the link and the end-to-end handshake of one connection.
     */
    val strangerIdentity: SigningIdentity? = null,
    val log: (String) -> Unit = {},
) {
    /**
     * Design OR (DEBUG prototype): the `RELAY_SEEK` bits to sign in this LINK responder block, read when the block is
     * built (a helper that would admit a search now); null signs nothing.
     */
    var relaySeek: () -> Int? = { null }
}

/** Log lines of the experiment: a fixed vocabulary, never a node ID, prefix, RDI or key. */
object Bmh3Log {
    fun handshake(
        layer: String,
        role: String,
        order: Bmh3Order?,
        result: String,
        elapsedMs: Long,
        hint: Boolean? = null,
        pinned: Boolean? = null,
    ): String = buildString {
        append("bmh3: event=").append(layer)
        append(" | role=").append(role)
        append(" | order=").append(order?.name ?: "none")
        append(" | result=").append(result)
        append(" | ms=").append(elapsedMs)
        if (hint != null) append(" | hint=").append(hint)
        if (pinned != null) append(" | pinned=").append(pinned)
    }

    fun choice(version: Int, reason: String): String = "bmh3: event=choice | version=$version | reason=$reason"

    /** Which key a phone presented (rule P). [peer4] is DEBUG only: the first four bytes of the peer's presented node ID. */
    fun present(layer: String, role: String, pseudonym: Boolean, reason: String, peer4: String? = null): String = buildString {
        append("bmh3: event=present | layer=").append(layer)
        append(" | role=").append(role)
        append(" | key=").append(if (pseudonym) "pseudonym" else "real")
        append(" | reason=").append(reason)
        if (peer4 != null) append(" | peer4=").append(peer4)
    }
}

/** A message carrier: link frames or BMS1 type-4 records. */
internal interface Bmh3Channel {
    fun send(message: ByteArray)
    fun receive(): ByteArray
}

internal class Bmh3LinkChannel(input: InputStream, output: OutputStream) : Bmh3Channel {
    private val data = DataInputStream(input)
    private val sink = DataOutputStream(output)

    override fun send(message: ByteArray) {
        require(message.size in 1..MAX_FRAME_BYTES) { "Handshake message is too large" }
        sink.writeInt(message.size)
        sink.write(message)
        sink.flush()
    }

    override fun receive(): ByteArray = receiveFrame(data)

    companion object {
        const val MAX_FRAME_BYTES = 2_048

        /** Reads one `u32 length | bytes` frame; the shared first read of the BMH2 and BMH3 responders. */
        fun receiveFrame(data: DataInputStream): ByteArray {
            val size = data.readInt()
            require(size in 1..MAX_FRAME_BYTES) { "Invalid handshake message size" }
            return ByteArray(size).also(data::readFully)
        }
    }
}

internal class Bmh3RecordChannel(private val link: SecureBluetoothSession) : Bmh3Channel {
    override fun send(message: ByteArray) {
        link.writer.write(SecureRecordType.END_TO_END_HANDSHAKE, message)
        link.writer.flush()
    }

    override fun receive(): ByteArray {
        val record = link.reader.read()
        require(record.type == SecureRecordType.END_TO_END_HANDSHAKE) { "Expected an end-to-end handshake" }
        return record.payload
    }
}

internal object Bmh3Driver {
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    fun initiate(
        purpose: Bmh3Purpose,
        channel: Bmh3Channel,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        options: Bmh3InitiatorOptions,
        layer: String,
        intent: Int? = null,
        buildTag: BuildTag? = null,
    ): Bmh3Result {
        val started = nowMs()
        val config = Bmh3Config(
            purpose, identity, capabilities, options.entropy, buildTag,
            stranger = options.strangerIdentity?.let { Bmh3Presentation.stranger(it, capabilities, purpose, responder = false) },
            wifiMarker = options.wifiMarker && purpose == Bmh3Purpose.LINK,
        )
        val expected = options.contactTag?.expectedPeer
        val initiator = Bmh3Initiator(
            config,
            mayRevealFirst = false,
            contactTag = options.contactTag,
            intent = intent,
            checkResponder = { peer ->
                // A recognised contact: the answerer must be that contact (its full node ID), not just any key
                // whose 48-bit prefix or RDI matches.
                if (expected != null && peer.nodeId != expected) throw Bmh3Failure("not the expected contact")
                options.checkResponder(peer)
            },
        )
        try {
            channel.send(initiator.message1())
            channel.send(initiator.onMessage2(channel.receive()))
            if (!initiator.isComplete) initiator.onMessage4(channel.receive())
            val result = initiator.result()
            options.log(Bmh3Log.handshake(layer, "initiator", result.order, "ok", nowMs() - started))
            if (options.strangerIdentity != null) {
                options.log(Bmh3Log.present(layer, "initiator", result.ownPseudonymous, if (result.ownPseudonymous) "stranger" else "contact"))
            }
            return result
        } catch (error: Exception) {
            options.log(Bmh3Log.handshake(layer, "initiator", initiator.chosenOrder, failureClass(error), nowMs() - started))
            throw error
        }
    }

    /** [first] is the already-read first message (the dispatching responder read it to see the magic). */
    fun respond(
        purpose: Bmh3Purpose,
        channel: Bmh3Channel,
        first: ByteArray,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        options: Bmh3ResponderOptions,
        layer: String,
        buildTag: BuildTag? = null,
    ): Bmh3Result {
        val started = nowMs()
        // A pseudonymous LINK block clears link-hello bit 3: no BDO1 follows, so the salt of a rotating ID stays with
        // the phone until the user accepts a call (PROTOCOL "What the pseudonymous responder withholds").
        val config = Bmh3Config(
            purpose, identity, capabilities, options.entropy, buildTag,
            stranger = options.strangerIdentity?.let { Bmh3Presentation.stranger(it, capabilities, purpose, responder = true) },
            relaySeek = if (purpose == Bmh3Purpose.LINK) options.relaySeek() else null,
        )
        val responder = Bmh3Responder(
            config,
            mode = options.mode,
            tagChecks = options.tagChecks,
            opening = options.opening,
            replayCache = options.replayCache,
            beforeReply = options.beforeReply,
            admitInitiator = options.admitInitiator,
        )
        try {
            channel.send(responder.onMessage1(first))
            val reply = responder.onMessage3(channel.receive())
            if (reply != null) channel.send(reply)
            val result = responder.result()
            options.log(Bmh3Log.handshake(layer, "responder", result.order, "ok", nowMs() - started))
            if (options.strangerIdentity != null) {
                options.log(Bmh3Log.present(layer, "responder", result.ownPseudonymous, if (result.ownPseudonymous) "stranger" else "tag"))
            }
            return result
        } catch (error: Exception) {
            options.log(Bmh3Log.handshake(layer, "responder", responder.chosenOrder, failureClass(error), nowMs() - started))
            throw error
        }
    }

    /** A fixed vocabulary for logs: the [Bmh3Failure] reason, or the exception class of an I/O or policy failure. */
    private fun failureClass(error: Exception): String =
        if (error is Bmh3Failure) error.reason.replace(' ', '_') else error.javaClass.simpleName
}

/** Link layer: the BMH3 counterparts of [SecureBluetoothHandshake.initiate] and `respond`. */
internal object Bmh3LinkHandshake {
    fun initiate(
        input: InputStream,
        output: OutputStream,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        options: Bmh3InitiatorOptions,
        buildTag: BuildTag? = null,
    ): SecureBluetoothSession {
        val result = Bmh3Driver.initiate(
            Bmh3Purpose.LINK,
            Bmh3LinkChannel(input, output),
            options.identityOverride ?: identity,
            capabilities,
            options,
            layer = "link",
            buildTag = buildTag,
        )
        return session(input, output, result, initiator = true, ownWifiMarker = options.wifiMarker)
    }

    fun respond(
        input: InputStream,
        output: OutputStream,
        firstFrame: ByteArray,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        options: Bmh3ResponderOptions,
        buildTag: BuildTag? = null,
    ): SecureBluetoothSession {
        val result = Bmh3Driver.respond(
            Bmh3Purpose.LINK,
            Bmh3LinkChannel(input, output),
            firstFrame,
            identity,
            capabilities,
            options,
            layer = "link",
            buildTag = buildTag,
        )
        return session(input, output, result, initiator = false, ownWifiMarker = false)
    }

    private fun session(input: InputStream, output: OutputStream, result: Bmh3Result, initiator: Boolean, ownWifiMarker: Boolean): SecureBluetoothSession {
        // As in BMH2 (SecureBluetoothHandshake.session): the tag reaches the notice only from a link THIS phone opened,
        // and bit 7 never reaches the rest of the app. Here the tag came from the peer's encrypted, signed block.
        val tag = result.peer.buildTag
        val visible = result.peer.capabilities.copy(
            transportMask = result.peer.capabilities.transportMask and BuildTag.LINK_HELLO_BIT.inv(),
        )
        if (initiator) tag?.let(LinkBuildTags::report)
        return SecureBluetoothSession(
            peerNodeId = result.peer.nodeId,
            peerCapabilities = visible,
            reader = SecureRecordReader(input, result.receiveKey),
            writer = SecureRecordWriter(output, result.sendKey),
            peerBuildTag = tag,
            peerPseudonymous = result.peer.pseudonymous,
            ownPseudonymous = result.ownPseudonymous,
            ownPseudonymNodeId = result.ownSpki.takeIf { result.ownPseudonymous && it.isNotEmpty() }?.let(NodeId::fromPublicKey),
            qrTagMatched = result.qrTagMatched,
            peerRelaySeek = result.peer.relaySeek,
            ownWifiMarker = ownWifiMarker,
            peerWifiMarker = result.peer.wifiMarker,
            hopWifiKey = WifiHopPlumbing.hopUwOrNull(result.sendKey, result.receiveKey),
        ).also { result.wipe() }
    }
}

/** End-to-end layer: the BMH3 counterparts of [EndToEndVideoHandshake.initiate] and `respond`. */
internal object Bmh3EndpointHandshake {
    fun initiate(
        link: SecureBluetoothSession,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        options: Bmh3InitiatorOptions,
    ): EndToEndVideoSession {
        var fingerprint: IdentityFingerprint? = null
        // The verified-contact check runs once, before this phone reveals itself (PROTOCOL check 3); the
        // caller's discovery binding follows it.
        val wrapped = Bmh3InitiatorOptions(
            contactTag = options.contactTag,
            checkResponder = { peer ->
                // A throwaway key is not an authenticated person: it is neither admitted nor remembered (the reveal is).
                fingerprint = fingerprintOf(peer)
                options.checkResponder(peer)
            },
            entropy = options.entropy,
            strangerIdentity = options.strangerIdentity,
            log = options.log,
        )
        val result = Bmh3Driver.initiate(
            Bmh3Purpose.END_TO_END,
            Bmh3RecordChannel(link),
            identity,
            capabilities,
            wrapped,
            layer = "endpoint",
        )
        return session(result, requireNotNull(fingerprint) { "Peer fingerprint missing" }, identity, Bmh3Reveal.ROLE_CALLER, options.strangerIdentity, capabilities)
    }

    /** The peer's fingerprint; [PeerIdentityGate.admit] (verified-contact check, authenticated-peer memory) only for a real key. */
    private fun fingerprintOf(peer: Bmh3Peer): IdentityFingerprint =
        if (peer.pseudonymous) IdentityFingerprint.fromPublicKey(peer.spki) else PeerIdentityGate.admit(peer.nodeId, peer.spki)

    fun respond(
        link: SecureBluetoothSession,
        firstRecord: SecureRecord,
        identity: SigningIdentity,
        capabilities: NodeCapabilities,
        options: Bmh3ResponderOptions,
    ): EndToEndVideoSession {
        var fingerprint: IdentityFingerprint? = null
        val wrapped = Bmh3ResponderOptions(
            mode = options.mode,
            tagChecks = options.tagChecks,
            opening = options.opening,
            replayCache = options.replayCache,
            beforeReply = options.beforeReply,
            admitInitiator = { peer, matched ->
                // Throws before any content key exists when a verified contact's prefix shows another key.
                fingerprint = fingerprintOf(peer)
                options.admitInitiator(peer, matched)
            },
            acceptBmh2 = options.acceptBmh2,
            entropy = options.entropy,
            strangerIdentity = options.strangerIdentity,
            log = options.log,
        )
        val result = Bmh3Driver.respond(
            Bmh3Purpose.END_TO_END,
            Bmh3RecordChannel(link),
            firstRecord.payload,
            identity,
            capabilities,
            wrapped,
            layer = "endpoint",
        )
        return session(result, requireNotNull(fingerprint) { "Peer fingerprint missing" }, identity, Bmh3Reveal.ROLE_CALLEE, options.strangerIdentity, capabilities)
    }

    private fun session(
        result: Bmh3Result,
        fingerprint: IdentityFingerprint,
        real: SigningIdentity,
        localRole: Int,
        stranger: SigningIdentity?,
        localCapabilities: NodeCapabilities,
    ): EndToEndVideoSession {
        // The reveal exchange exists only when a pseudonym was involved; it keeps a copy of the exporter (wiped below).
        val reveal = if (result.ownPseudonymous || result.peer.pseudonymous) {
            Bmh3RevealSession(
                localRole = localRole,
                exporter = result.exporter,
                ownPseudonymSpki = result.ownSpki,
                peerPseudonymSpki = result.peer.spki,
                ownPseudonymous = result.ownPseudonymous,
                peerPseudonymous = result.peer.pseudonymous,
                ownSpkis = listOfNotNull(real.publicKeyEncoded, stranger?.publicKeyEncoded, result.ownSpki),
            )
        } else {
            null
        }
        return EndToEndVideoSession(
            peerNodeId = result.peer.nodeId,
            peerCapabilities = result.peer.capabilities,
            sealer = EndToEndContentSealer(result.sendKey),
            opener = EndToEndContentOpener(result.receiveKey),
            peerFingerprint = fingerprint,
            reveal = reveal,
            qrTagMatched = result.qrTagMatched,
            // Experimental in-call link renewal (Design SR): derived from the two content keys, nothing on the wire.
            renewalKey = if (CallRenewalWire.bothSigned(localCapabilities, result.peer.capabilities)) {
                CallRenewalCrypto.renewalKey(result.sendKey, result.receiveKey)
            } else {
                null
            },
            // Wi-Fi upgrade: derived here because the exporter is wiped on the next line; always derived (kept for the call,
            // zeroed at call end and by the emergency wipe). [R M-5][API]
            wifiKey = WifiHopPlumbing.callUwOrNull(result.exporter),
        ).also { result.wipe() }
    }
}
