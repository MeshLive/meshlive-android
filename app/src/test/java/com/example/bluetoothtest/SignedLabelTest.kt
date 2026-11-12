package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

class SignedLabelTest {
    private val alice = KeyPairSigningIdentity(generateEcKeyPair(SecureRandom()))
    private val mallory = KeyPairSigningIdentity(generateEcKeyPair(SecureRandom()))
    private val callId = UUID.fromString("01020304-0506-0708-090a-0b0c0d0e0f10")
    private val otherCall = UUID.fromString("11111111-2222-3333-4444-555555555555")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun reason(block: () -> Unit): String = try {
        block()
        fail("expected a rejection")
        ""
    } catch (error: LabelRejected) {
        error.reason
    }

    /** A kind 3 call subject exactly as an older build (before 2026-11-05) signs it. */
    private fun olderBuildSubject(identity: SigningIdentity, binding: LabelBinding, text: String): ByteArray {
        val unsigned = SignedLabelCodec.signedPart(LabelKind.RETIRED_CALL_SUBJECT, identity.publicKeyEncoded, 0, binding, text)
        val signature = identity.sign(SignedLabelCodec.DOMAIN + unsigned)
        return unsigned + byteArrayOf((signature.size shr 8).toByte(), signature.size.toByte()) + signature
    }

    @Test
    fun signedPartMatchesTheProtocolVector() {
        // PROTOCOL "Experimental peer labels" vector: kind 3 (the retired call subject), SPKI 30 01, sequence 0, call binding, "Hi".
        val unsigned = SignedLabelCodec.signedPart(3, byteArrayOf(0x30, 0x01), 0, LabelBinding.call(callId), "Hi")
        assertEquals(
            "424e4c31" + "01" + "03" + "0002" + "3001" + "00000000" + "01" +
                "0102030405060708090a0b0c0d0e0f10" + "02" + "4869",
            hex(unsigned),
        )
        assertEquals("426c7565746f6f7468566964656f4d6573682f4c6162656c2f3100", hex(SignedLabelCodec.DOMAIN))
        assertEquals(
            "424c423100",
            hex(SignedLabelCodec.encodeContainer(emptyList())),
        )
    }

    @Test
    fun roundTripVerifiesSignerBindingAndText() {
        val encoded = SignedLabelCodec.sign(alice, LabelKind.STREAM_TITLE, LabelBinding.call(callId), "Water at the school")
        val label = SignedLabelCodec.decode(encoded)
        assertEquals(LabelKind.STREAM_TITLE, SignedLabelCodec.verify(label, alice.nodeId, LabelBinding.call(callId)))
        assertEquals("Water at the school", label.text)
        assertArrayEquals(encoded, label.encoded)
        // At most 259 bytes with a 91-byte P-256 SPKI and a 64-byte text (design C.2), which an older build's subject can be.
        val longest = olderBuildSubject(alice, LabelBinding.call(callId), "a".repeat(64))
        assertTrue(longest.size <= 259)
        assertEquals("a".repeat(64), SignedLabelCodec.decode(longest).text)
    }

    @Test
    fun wrongSignerForeignSignatureAndRebindingAreRejected() {
        val encoded = SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, LabelBinding.call(callId), "Asha")
        val label = SignedLabelCodec.decode(encoded)
        assertEquals("signer", reason { SignedLabelCodec.verify(label, mallory.nodeId, LabelBinding.call(callId)) })
        // Replay into another call (R2): the binding no longer matches.
        assertEquals("binding", reason { SignedLabelCodec.verify(label, alice.nodeId, LabelBinding.call(otherCall)) })
        assertEquals("binding", reason { SignedLabelCodec.verify(label, alice.nodeId, LabelBinding.stream(callId)) })
        // Mallory claims Alice's key but signs with her own: the signature check fails.
        val forged = SignedLabelCodec.signedPart(1, alice.publicKeyEncoded, 0, LabelBinding.call(callId), "Asha")
        val signature = mallory.sign(SignedLabelCodec.DOMAIN + forged)
        val forgedBytes = forged + byteArrayOf((signature.size shr 8).toByte(), signature.size.toByte()) + signature
        assertEquals(
            "signature",
            reason { SignedLabelCodec.verify(SignedLabelCodec.decode(forgedBytes), alice.nodeId, LabelBinding.call(callId)) },
        )
        // A forwarder editing the binding bytes breaks the signature.
        val rebound = encoded.copyOf()
        val bindingOffset = 4 + 1 + 1 + 2 + alice.publicKeyEncoded.size + 4 + 1
        rebound[bindingOffset] = (rebound[bindingOffset].toInt() xor 1).toByte()
        val reboundLabel = SignedLabelCodec.decode(rebound)
        assertEquals("signature", reason { SignedLabelCodec.verify(reboundLabel, alice.nodeId, reboundLabel.binding) })
    }

    @Test
    fun nonCanonicalTextIsDroppedEvenWhenSigned() {
        val unsigned = SignedLabelCodec.signedPart(1, alice.publicKeyEncoded, 0, LabelBinding.call(callId), " Asha")
        val signature = alice.sign(SignedLabelCodec.DOMAIN + unsigned)
        val bytes = unsigned + byteArrayOf((signature.size shr 8).toByte(), signature.size.toByte()) + signature
        assertTrue(reason { SignedLabelCodec.verify(SignedLabelCodec.decode(bytes), alice.nodeId, LabelBinding.call(callId)) }.startsWith("text"))
    }

    @Test
    fun inboxKeepsHigherSequenceSkipsUnknownKindsAndNeverThrows() {
        var now = 0L
        val inbox = LabelInbox { now }
        val binding = LabelBinding.call(callId)
        val v1 = SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, binding, "Asha", sequence = 1)
        val v0 = SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, binding, "Old", sequence = 0)
        assertEquals(setOf(LabelKind.PERSON_NAME), inbox.accept(SignedLabelCodec.encodeContainer(listOf(v1)), alice.nodeId, binding, "test"))
        now += 3_000
        assertTrue(inbox.accept(SignedLabelCodec.encodeContainer(listOf(v0)), alice.nodeId, binding, "test").isEmpty())
        assertEquals("Asha", inbox.text(LabelKind.PERSON_NAME))
        // Unknown kind 9: structurally valid, skipped for later versions.
        now += 3_000
        val unknownUnsigned = SignedLabelCodec.signedPart(9, alice.publicKeyEncoded, 0, binding, "x")
        val sig = alice.sign(SignedLabelCodec.DOMAIN + unknownUnsigned)
        val unknown = unknownUnsigned + byteArrayOf((sig.size shr 8).toByte(), sig.size.toByte()) + sig
        assertTrue(inbox.accept(SignedLabelCodec.encodeContainer(listOf(unknown)), alice.nodeId, binding, "test").isEmpty())
        // Truncated container or label: ignored, the session continues.
        now += 3_000
        val container = SignedLabelCodec.encodeContainer(listOf(v1))
        assertTrue(inbox.accept(container.copyOf(container.size - 5), alice.nodeId, binding, "test").isEmpty())
        now += 3_000
        assertTrue(inbox.accept(byteArrayOf(1, 2, 3), alice.nodeId, binding, "test").isEmpty())
        assertEquals("Asha", inbox.text(LabelKind.PERSON_NAME))
        assertArrayEquals(v1, inbox.verifiedEncoded().single())
    }

    @Test
    fun containerCountAndRateLimits() {
        assertTrue(SignedLabelCodec.decodeContainer(SignedLabelCodec.encodeContainer(emptyList())).isEmpty())
        val four = "BLB1".toByteArray() + byteArrayOf(4)
        assertEquals("container count", reason { SignedLabelCodec.decodeContainer(four) })
        var now = 0L
        val inbox = LabelInbox { now }
        val binding = LabelBinding.call(callId)
        val name = SignedLabelCodec.encodeContainer(listOf(SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, binding, "Asha")))
        assertEquals(1, inbox.accept(name, alice.nodeId, binding, "test").size)
        now += 500
        val renamed = SignedLabelCodec.encodeContainer(listOf(SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, binding, "Ravi", 1)))
        assertTrue(inbox.accept(renamed, alice.nodeId, binding, "test").isEmpty()) // second within 2 s ignored
        assertEquals("Asha", inbox.text(LabelKind.PERSON_NAME))
    }

    @Test
    fun aCallSubjectFromAnOlderBuildIsDroppedSilentlyAndTheCallsNameStillArrives() {
        // Kind 3 was retired on 2026-11-05: an older build may still send its subject beside its name.
        val binding = LabelBinding.call(callId)
        val inbox = LabelInbox()
        val container = SignedLabelCodec.encodeContainer(
            listOf(
                olderBuildSubject(alice, binding, "Water at the school"),
                SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, binding, "Asha"),
            ),
        )
        assertEquals(setOf(LabelKind.PERSON_NAME), inbox.accept(container, alice.nodeId, binding, "test"))
        assertEquals("Asha", inbox.text(LabelKind.PERSON_NAME))
        // Never shown, never kept for forwarding: only the name's bytes are held.
        assertEquals(1, inbox.verifiedEncoded().size)
        assertEquals(LabelKind.PERSON_NAME, SignedLabelCodec.decode(inbox.verifiedEncoded().single()).kind)
        // A container holding only a subject changes nothing and is no error.
        val later = LabelInbox()
        val subjectOnly = SignedLabelCodec.encodeContainer(listOf(olderBuildSubject(alice, binding, "Hi")))
        assertTrue(later.accept(subjectOnly, alice.nodeId, binding, "test").isEmpty())
        assertTrue(later.verifiedEncoded().isEmpty())
    }

    @Test
    fun forwardedLabelsVerifyAgainstTheGrantSourceAndStream() {
        val streamId = UUID.randomUUID()
        val title = SignedLabelCodec.sign(alice, LabelKind.STREAM_TITLE, LabelBinding.stream(streamId), "Flood update")
        // The forwarder verified it and passes the exact bytes on.
        val forwarder = LabelInbox()
        forwarder.accept(SignedLabelCodec.encodeContainer(listOf(title)), alice.nodeId, LabelBinding.stream(streamId), "fwd")
        val child = LabelInbox()
        child.accept(SignedLabelCodec.encodeContainer(forwarder.verifiedEncoded()), alice.nodeId, LabelBinding.stream(streamId), "child")
        assertEquals("Flood update", child.text(LabelKind.STREAM_TITLE))
        // Replayed into a different stream, or claimed by the forwarder as source: dropped.
        val other = LabelInbox()
        other.accept(SignedLabelCodec.encodeContainer(listOf(title)), alice.nodeId, LabelBinding.stream(UUID.randomUUID()), "x")
        assertNull(other.text(LabelKind.STREAM_TITLE))
        val wrongSource = LabelInbox()
        wrongSource.accept(SignedLabelCodec.encodeContainer(listOf(title)), mallory.nodeId, LabelBinding.stream(streamId), "x")
        assertNull(wrongSource.text(LabelKind.STREAM_TITLE))
        // A direct stream without a mesh ID binds to the zero stream ID.
        assertEquals(LabelBinding.DIRECT_STREAM, LabelBinding.stream(null))
    }

    @Test
    fun oldPeersNeverReceiveOrParseLabels() {
        val base = NodeCapabilities(
            transportMask = 0x01,
            decodeCodecMask = 0x01,
            encodeCodecMask = 0x01,
            maxBitrateKbps = 500,
            maxChildren = 0,
        )
        // Off: the hello is byte-for-byte unchanged.
        assertEquals(base, PeerLabelFeature.advertise(base, enabled = false))
        val withBit = PeerLabelFeature.advertise(base, enabled = true)
        assertEquals(0x21, withBit.transportMask)
        assertFalse(PeerLabelFeature.negotiated(true, base))
        assertFalse(PeerLabelFeature.negotiated(false, withBit))
        assertTrue(PeerLabelFeature.negotiated(true, withBit))
        // A stream hop through an opaque relay (link peer != endpoint peer) sends no LABELS.
        assertFalse(LabelRecords.streamHopNegotiated(true, mallory.nodeId, alice.nodeId, withBit))
        assertTrue(LabelRecords.streamHopNegotiated(true, alice.nodeId, alice.nodeId, withBit))
        // An opener that did not advertise labels rejects type 10 exactly like an unknown type.
        val key = ByteArray(MeshAead.KEY_BYTES) { 7 }
        val sealer = EndToEndContentSealer(key)
        val record = sealer.seal(EndToEndContentType.LABELS, 0, SignedLabelCodec.encodeContainer(emptyList()))
        val oldOpener = EndToEndContentOpener(key)
        val error = runCatching { oldOpener.open(record) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Unknown end-to-end content type 10"))
        val newOpener = EndToEndContentOpener(key).also { it.acceptLabels = true }
        assertEquals(EndToEndContentType.LABELS, newOpener.open(record).type)
    }

    @Test
    fun malformedSignatureBytesAreDroppedNotThrown() {
        // A peer or forwarder can put any bytes in the signature field; a provider that throws on
        // bad DER must not fail the call or stream (failure rule): the label is just dropped.
        val binding = LabelBinding.call(callId)
        val encoded = SignedLabelCodec.sign(alice, LabelKind.PERSON_NAME, binding, "Asha")
        val unsigned = SignedLabelCodec.decode(encoded).signedBytes
        listOf(byteArrayOf(0x01), byteArrayOf(0x30, 0x00), ByteArray(72) { 0x30 }).forEach { junk ->
            val forged = unsigned + byteArrayOf((junk.size shr 8).toByte(), junk.size.toByte()) + junk
            assertEquals("signature", reason { SignedLabelCodec.verify(SignedLabelCodec.decode(forged), alice.nodeId, binding) })
            val inbox = LabelInbox()
            assertTrue(inbox.accept(SignedLabelCodec.encodeContainer(listOf(forged)), alice.nodeId, binding, "test").isEmpty())
            assertNull(inbox.text(LabelKind.PERSON_NAME))
        }
    }
}
