package com.example.bluetoothtest

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pseudonymous stranger identities on top of BMH3 (PROTOCOL "Phase 2: pseudonymous stranger identities"): which key is
 * presented to whom, what a prober can see, the reveal after an accepted call, the contact path, and the abuse cases.
 */
class Bmh3PseudonymTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val caps = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP or RotatingDiscoveryIds.LINK_HELLO_BIT,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        maxBitrateKbps = 1_100,
        maxChildren = 0,
    )
    private val responderCaps = caps.copy(maxChildren = 1)

    private class CountingIdentity(private val inner: SigningIdentity) : SigningIdentity {
        val signed = AtomicInteger()
        override val nodeId: NodeId get() = inner.nodeId
        override val publicKeyEncoded: ByteArray get() = inner.publicKeyEncoded
        override fun sign(message: ByteArray): ByteArray {
            signed.incrementAndGet()
            return inner.sign(message)
        }
    }

    private fun newIdentity() = CountingIdentity(KeyPairSigningIdentity(generateEcKeyPair()))

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    private class Run(val messages: List<ByteArray>, val ini: Bmh3Result, val res: Bmh3Result)

    /** One in-memory handshake; [real] identities are the phones' Keystore keys, [strangerI]/[strangerR] their throwaways. */
    private fun handshake(
        purpose: Bmh3Purpose,
        realI: SigningIdentity,
        realR: SigningIdentity,
        strangerI: SigningIdentity? = null,
        strangerR: SigningIdentity? = null,
        contactTag: Bmh3ContactTag? = null,
        tagChecks: List<Bmh3ContactTag> = emptyList(),
        opening: ByteArray? = null,
    ): Run {
        val initiator = Bmh3Initiator(
            Bmh3Config(purpose, realI, caps, stranger = strangerI?.let { Bmh3Presentation(it, caps, true) }),
            contactTag = contactTag,
        )
        val responder = Bmh3Responder(
            Bmh3Config(purpose, realR, responderCaps, stranger = strangerR?.let { Bmh3Presentation(it, responderCaps, true) }),
            tagChecks = tagChecks,
            opening = opening,
        )
        val wire = mutableListOf<ByteArray>()
        val m1 = initiator.message1().also { wire += it }
        val m2 = responder.onMessage1(m1).also { wire += it }
        val m3 = initiator.onMessage2(m2).also { wire += it }
        assertNull(responder.onMessage3(m3))
        return Run(wire, initiator.result(), responder.result())
    }

    // ------------------------------------------------------------------------------- rule P and what a prober sees

    @Test
    fun aStrangerHandshakeShowsOnlyThrowawaysAndTheRealKeysNeverSign() {
        for (purpose in Bmh3Purpose.entries) {
            val realI = newIdentity()
            val realR = newIdentity()
            val psI = newIdentity()
            val psR = newIdentity()
            val run = handshake(purpose, realI, realR, psI, psR)
            // Same sizes as ever: nothing tells a sniffer that this is a stranger session.
            assertEquals(listOf(122, 431, 334), run.messages.map { it.size })
            assertEquals(psR.nodeId, run.ini.peer.nodeId)
            assertEquals(psI.nodeId, run.res.peer.nodeId)
            assertTrue(run.ini.peer.pseudonymous && run.res.peer.pseudonymous)
            assertTrue(run.ini.ownPseudonymous && run.res.ownPseudonymous)
            assertEquals(0, realI.signed.get())
            assertEquals(0, realR.signed.get())
            assertEquals(1, psI.signed.get())
            assertEquals(1, psR.signed.get())
            val wire = run.messages.fold(ByteArray(0)) { acc, m -> acc + m }
            for (identity in listOf(realI, realR, psI, psR)) {
                assertFalse(contains(wire, identity.publicKeyEncoded))
                assertFalse(contains(wire, identity.nodeId.toByteArray()))
            }
            assertArrayEquals(run.ini.sendKey, run.res.receiveKey)
        }
    }

    @Test
    fun aRecognisedContactGetsTheRealKeysOnBothSidesAndNeverAThrowaway() {
        val realI = newIdentity()
        val realR = newIdentity()
        val psI = newIdentity()
        val psR = newIdentity()
        val key = Bmh3Crypto.contactTagKey(ByteArray(32) { (it + 1).toByte() })
        val binding = Bmh3Crypto.advertBinding(byteArrayOf(9, 8, 7, 6, 5, 4), 0, null)
        val run = handshake(
            Bmh3Purpose.LINK, realI, realR, psI, psR,
            contactTag = Bmh3ContactTag(key, binding),
            tagChecks = listOf(Bmh3ContactTag(key, binding)),
        )
        assertTrue(run.res.tagMatched)
        assertEquals(realR.nodeId, run.ini.peer.nodeId)
        assertEquals(realI.nodeId, run.res.peer.nodeId)
        assertFalse(run.ini.peer.pseudonymous || run.res.peer.pseudonymous)
        assertFalse(run.ini.ownPseudonymous || run.res.ownPseudonymous)
        assertEquals(1, realI.signed.get())
        assertEquals(1, realR.signed.get())
        assertEquals(0, psI.signed.get())
        assertEquals(0, psR.signed.get())
    }

    @Test
    fun aTagThatDoesNotMatchGivesTheStrangerPresentationEvenWhenTheWrongLeaseWasUsed() {
        val realR = newIdentity()
        val psR = newIdentity()
        val binding = Bmh3Crypto.advertBinding(byteArrayOf(1, 2, 3, 4, 5, 6), 0, null)
        val responder = Bmh3Responder(
            Bmh3Config(Bmh3Purpose.LINK, realR, responderCaps, stranger = Bmh3Presentation(psR, responderCaps, true)),
            tagChecks = listOf(Bmh3ContactTag(Bmh3Crypto.contactTagKey(ByteArray(32) { 7 }), binding)),
        )
        val initiator = Bmh3Initiator(
            Bmh3Config(Bmh3Purpose.LINK, newIdentity(), caps),
            contactTag = Bmh3ContactTag(Bmh3Crypto.contactTagKey(ByteArray(32) { 9 }), binding),
        )
        val m2 = responder.onMessage1(initiator.message1())
        // A caller that sent a tag and a real key refuses a throwaway answer before it reveals anything.
        val failure = assertThrows(Bmh3Failure::class.java) { initiator.onMessage2(m2) }
        assertEquals("pseudonymous answerer to a real caller", failure.reason)
        assertFalse(responder.contactTagMatched)
        assertEquals(0, realR.signed.get())
    }

    @Test
    fun aCallerWithoutThePseudonymFeatureCannotHandItsRealKeyToAThrowawayAnswerer() {
        val realI = newIdentity()
        val realR = newIdentity()
        val psR = newIdentity()
        val responder = Bmh3Responder(Bmh3Config(Bmh3Purpose.END_TO_END, realR, responderCaps, stranger = Bmh3Presentation(psR, responderCaps, true)))
        val initiator = Bmh3Initiator(Bmh3Config(Bmh3Purpose.END_TO_END, realI, caps))
        val m2 = responder.onMessage1(initiator.message1())
        val failure = assertThrows(Bmh3Failure::class.java) { initiator.onMessage2(m2) }
        assertEquals("pseudonymous answerer to a real caller", failure.reason)
        assertEquals("the real key never signed", 0, realI.signed.get())
    }

    @Test
    fun aPseudonymousCallerMeetingAnOlderResponderStaysAThrowawayAndSeesNoDeclaration() {
        val realI = newIdentity()
        val realR = newIdentity()
        val psI = newIdentity()
        val run = handshake(Bmh3Purpose.END_TO_END, realI, realR, strangerI = psI)
        assertEquals(realR.nodeId, run.ini.peer.nodeId)
        assertFalse(run.ini.peer.pseudonymous)
        assertTrue(run.res.peer.pseudonymous)
        assertEquals(0, realI.signed.get())
    }

    @Test
    fun aPseudonymousBlockNeverCarriesAnOpeningEvenWhenTheResponderHasEntriesPinned() {
        val rdi = ByteArray(6) { (it + 1).toByte() }
        val entry = OpeningEntry(rdi, 1_990_656L, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 3 })
        val opening = Bmh3Policy.openingValue(listOf(entry))
        val stranger = handshake(Bmh3Purpose.LINK, newIdentity(), newIdentity(), newIdentity(), newIdentity(), opening = opening)
        assertNull("no salt for a stranger", stranger.ini.peer.openingEntries())
        val key = Bmh3Crypto.contactTagKey(ByteArray(32) { 5 })
        val binding = Bmh3Crypto.advertBinding(rdi, 0, null)
        val contact = handshake(
            Bmh3Purpose.LINK, newIdentity(), newIdentity(), newIdentity(), newIdentity(),
            contactTag = Bmh3ContactTag(key, binding), tagChecks = listOf(Bmh3ContactTag(key, binding)), opening = opening,
        )
        assertEquals(listOf(entry), contact.ini.peer.openingEntries())
    }

    @Test
    fun nothingRepeatsBetweenTwoStrangerHandshakesOfTheSamePhone() {
        val realR = newIdentity()
        val realI = newIdentity()
        val a = handshake(Bmh3Purpose.END_TO_END, realI, realR, newIdentity(), newIdentity())
        val b = handshake(Bmh3Purpose.END_TO_END, realI, realR, newIdentity(), newIdentity())
        assertNotEquals(a.ini.peer.nodeId, b.ini.peer.nodeId)
        assertFalse(a.ini.exporter.contentEquals(b.ini.exporter))
        val wireA = a.messages.map { it.copyOfRange(Bmh3Wire.HEADER_BYTES, it.size) }
        val wireB = b.messages.map { it.copyOfRange(Bmh3Wire.HEADER_BYTES, it.size) }
        for (x in wireA) for (start in 0..x.size - 12) {
            val window = x.copyOfRange(start, start + 12)
            assertFalse("a 12-byte field repeats between two sessions", wireB.any { contains(it, window) })
        }
    }

    // --------------------------------------------------------------------------------------------- extension

    @Test
    fun theMarkerRoundTripsAndEveryMisplacementOrMalformedFormFails() {
        val marked = Bmh3Extensions(pseudonym = true)
        val area = marked.encodeArea()
        assertEquals(Bmh3Wire.EXTENSION_BYTES, area.size)
        assertTrue(Bmh3Extensions.parse(area).pseudonym)
        assertFalse(Bmh3Extensions.parse(Bmh3Extensions.NONE.encodeArea()).pseudonym)
        // Together with the largest things the app sends it still fits.
        val full = Bmh3Extensions(
            opening = ByteArray(87).also { it[0] = 2 }, intent = 1, buildTag = BuildTag(6, false), pseudonym = true,
        )
        assertTrue(Bmh3Extensions.parse(full.encodeArea()).pseudonym)
        // A PAD, then a marker with a value (types ascend, the area is 112 bytes).
        fun area(vararg tail: Int): ByteArray {
            val pad = Bmh3Wire.EXTENSION_BYTES - 3 - tail.size
            return byteArrayOf(0, (pad ushr 8).toByte(), pad.toByte()) + ByteArray(pad) + ByteArray(tail.size) { tail[it].toByte() }
        }
        assertEquals("pseudonym marker malformed", assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_PSEUDONYM, 0, 1, 1))
        }.reason)
        // The marker alone parses; an unknown non-critical type is skipped, an unknown critical one fails.
        assertTrue(Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_PSEUDONYM, 0, 0)).pseudonym)
        // Type 5 is RELAY_SEEK (Design OR) since the on-demand route prototype; 6 is still unknown and non-critical.
        assertFalse(Bmh3Extensions.parse(area(6, 0, 2, 0xaa, 0xbb)).pseudonym)
        assertEquals("unknown critical extension", assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(area(0x81, 0, 0))
        }.reason)
        // Types must ascend: a marker before a build tag is rejected.
        assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_PSEUDONYM, 0, 0, Bmh3Extensions.TYPE_BUILD_TAG, 0, 2, 0x80, 7))
        }
    }

    // ------------------------------------------------------------------------------------------ driver level

    private class Duplex {
        val aToB = QueuePipe()
        val bToA = QueuePipe()
    }

    private class QueuePipe {
        private val queue = LinkedBlockingQueue<Int>()
        @Volatile private var closed = false
        val output = object : OutputStream() {
            override fun write(b: Int) {
                if (closed) throw java.io.IOException("closed")
                queue.put(b and 0xff)
            }
        }
        val input = object : InputStream() {
            override fun read(): Int {
                val deadline = System.nanoTime() + 120_000_000_000L
                while (true) {
                    val value = queue.poll(20, TimeUnit.MILLISECONDS)
                    if (value != null) return value
                    if (closed && queue.isEmpty()) return -1
                    if (System.nanoTime() > deadline) throw java.io.IOException("test pipe read timed out")
                }
            }
        }
        fun close() {
            closed = true
        }
    }

    private class Both<T>(val initiator: Result<T>, val responder: Result<T>)

    private fun <T> both(initiator: () -> T, responder: () -> T, onResponderFailure: () -> Unit = {}, onInitiatorFailure: () -> Unit = {}): Both<T> {
        val responderResult = AtomicReference<Result<T>>()
        val worker = thread(name = "responder", isDaemon = true) {
            responderResult.set(runCatching(responder).onFailure { onResponderFailure() })
        }
        // A failed initiator closes its outgoing pipe, so the responder fails at once instead of waiting out the read deadline.
        val initiatorResult = runCatching(initiator).onFailure { onInitiatorFailure() }
        worker.join(120_000)
        return Both(initiatorResult, responderResult.get() ?: Result.failure(IllegalStateException("responder timed out")))
    }

    private class Sessions(
        val callerLink: SecureBluetoothSession,
        val calleeLink: SecureBluetoothSession,
        val caller: EndToEndVideoSession,
        val callee: EndToEndVideoSession,
    )

    /** Link plus end-to-end handshake of one call over in-memory pipes, with the given options on both layers. */
    private fun call(
        realI: SigningIdentity,
        realR: SigningIdentity,
        callerOptions: () -> Bmh3InitiatorOptions,
        calleeOptions: () -> Bmh3ResponderOptions,
        log: MutableList<String>? = null,
    ): Sessions {
        val linkPipes = Duplex()
        val callerOpts = callerOptions()
        val calleeOpts = calleeOptions()
        val links = both(
            { SecureBluetoothHandshake.initiate(linkPipes.bToA.input, linkPipes.aToB.output, realI, caps, bmh3 = callerOpts) },
            { SecureBluetoothHandshake.respond(linkPipes.aToB.input, linkPipes.bToA.output, realR, responderCaps, bmh3 = calleeOpts) },
        )
        val callerLink = links.initiator.getOrThrow()
        val calleeLink = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(callerLink, realI, caps, bmh3 = callerOpts) },
            { EndToEndVideoHandshake.respond(calleeLink, realR, responderCaps, bmh3 = calleeOpts) },
        )
        log?.let { it += "ok" }
        return Sessions(callerLink, calleeLink, ends.initiator.getOrThrow(), ends.responder.getOrThrow())
    }

    @Test
    fun aStrangerCallPublishesNothingAndAdmitsNoThrowawayAsAnAuthenticatedPerson() {
        val realI = newIdentity()
        val realR = newIdentity()
        val psI = newIdentity()
        val psR = newIdentity()
        val s = call(
            realI, realR,
            { Bmh3InitiatorOptions(strangerIdentity = psI) },
            { Bmh3ResponderOptions(strangerIdentity = psR, acceptBmh2 = false) },
        )
        assertTrue(s.callerLink.peerPseudonymous && s.calleeLink.peerPseudonymous)
        assertTrue(s.callerLink.ownPseudonymous && s.calleeLink.ownPseudonymous)
        // The link block of a pseudonymous responder does not carry the opening bit: no BDO1 follows.
        assertEquals(0, s.callerLink.peerCapabilities.transportMask and RotatingDiscoveryIds.LINK_HELLO_BIT)
        assertNotEquals(0, s.calleeLink.peerCapabilities.transportMask and RotatingDiscoveryIds.LINK_HELLO_BIT)
        assertEquals(psR.nodeId, s.caller.peerNodeId)
        assertEquals(psI.nodeId, s.callee.peerNodeId)
        assertEquals("a direct call reaches the same throwaway at both layers", s.callerLink.peerNodeId, s.caller.peerNodeId)
        assertTrue(s.caller.peerIsPseudonym && s.callee.peerIsPseudonym)
        assertEquals(s.caller.peerNodeId, s.caller.effectivePeerNodeId)
        // A throwaway is never recorded as an authenticated peer (no pollution of the 1,024-entry memory).
        assertNull(PeerIdentityGate.authenticatedFingerprint(psR.nodeId))
        assertNull(PeerIdentityGate.authenticatedFingerprint(psI.nodeId))
        assertEquals(0, realI.signed.get())
        assertEquals(0, realR.signed.get())
    }

    @Test
    fun aContactCallRunsRealToRealWithNoRevealSession() {
        val realI = newIdentity()
        val realR = newIdentity()
        val key = Bmh3Crypto.contactTagKey(ByteArray(32) { 42 })
        val binding = Bmh3Crypto.advertBinding(byteArrayOf(1, 1, 2, 3, 5, 8), 0, null)
        val s = call(
            realI, realR,
            { Bmh3InitiatorOptions(contactTag = Bmh3ContactTag(key, binding, expectedPeer = realR.nodeId), strangerIdentity = newIdentity()) },
            { Bmh3ResponderOptions(strangerIdentity = newIdentity(), tagChecks = listOf(Bmh3ContactTag(key, binding)), acceptBmh2 = false) },
        )
        assertFalse(s.callerLink.peerPseudonymous || s.calleeLink.peerPseudonymous)
        assertEquals(realR.nodeId, s.caller.peerNodeId)
        assertEquals(realI.nodeId, s.callee.peerNodeId)
        assertNull(s.caller.reveal)
        assertNull(s.callee.reveal)
        assertNotNull(PeerIdentityGate.authenticatedFingerprint(realR.nodeId))
        assertNotNull(PeerIdentityGate.authenticatedFingerprint(realI.nodeId))
    }

    @Test
    fun aCallerThatLookedForAContactFailsClosedWhenAThrowawayAnswers() {
        val realI = newIdentity()
        val realR = newIdentity()
        val key = Bmh3Crypto.contactTagKey(ByteArray(32) { 42 })
        val binding = Bmh3Crypto.advertBinding(byteArrayOf(1, 1, 2, 3, 5, 8), 0, null)
        val linkPipes = Duplex()
        val contactOptions = Bmh3InitiatorOptions(contactTag = Bmh3ContactTag(key, binding, expectedPeer = realR.nodeId), strangerIdentity = newIdentity())
        val out = both(
            { SecureBluetoothHandshake.initiate(linkPipes.bToA.input, linkPipes.aToB.output, realI, caps, bmh3 = contactOptions) },
            // The answerer holds no matching lease: it presents a throwaway.
            { SecureBluetoothHandshake.respond(linkPipes.aToB.input, linkPipes.bToA.output, realR, responderCaps, bmh3 = Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false)) },
            onResponderFailure = { linkPipes.bToA.close() },
            onInitiatorFailure = { linkPipes.aToB.close() },
        )
        assertTrue(out.initiator.isFailure)
        assertEquals(0, realI.signed.get())
    }

    @Test
    fun aBmh2HelloIsRefusedBySilentCloseInPseudonymModeAtTheLink() {
        val pipes = Duplex()
        val log = mutableListOf<String>()
        val out = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, newIdentity(), caps) },
            {
                SecureBluetoothHandshake.respond(
                    pipes.aToB.input, pipes.bToA.output, newIdentity(), responderCaps,
                    bmh3 = Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false, log = { synchronized(log) { log += it } }),
                )
            },
            onResponderFailure = { pipes.bToA.close() },
            onInitiatorFailure = { pipes.aToB.close() },
        )
        assertTrue(out.responder.isFailure)
        assertTrue(out.initiator.isFailure)
        assertTrue(log.any { "refused_bmh2" in it })
    }

    // ------------------------------------------------------------------------------------------------ the reveal

    private fun strangerCall(realI: SigningIdentity, realR: SigningIdentity) = call(
        realI, realR,
        { Bmh3InitiatorOptions(strangerIdentity = newIdentity()) },
        { Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false) },
    )

    private val entry = OpeningEntry(ByteArray(6) { (it + 1).toByte() }, 1_990_656L, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 3 })

    /**
     * Phone bug 2026-10-19 (userflow-a58be974, S24 log 19:26:52): in a Nearby call between mutual contacts, a pseudonym call,
     * the receiver judged the peer's PRESENCE_LEASE against the throwaway key and rejected it ("peer is not a verified
     * contact"). The lease is judged against the revealed person: stored after the reveal, rejected before it.
     */
    @Test
    fun aRevealedVerifiedContactsLeaseIsStoredAndAnUnrevealedOnesIsRejected() {
        val realI = newIdentity()
        val realR = newIdentity()
        val s = strangerCall(realI, realR)
        val contactR = IdentityFingerprint.fromPublicKey(realR.publicKeyEncoded)
        // The caller's store: realR is a verified contact; the throwaway is nobody.
        val store: (NodeId, IdentityFingerprint) -> VerificationState? = { node, fingerprint ->
            if (node == realR.nodeId && fingerprint == contactR) VerificationState.VERIFIED else VerificationState.UNVERIFIED
        }
        val lease = DiscoveryExchange.PresenceLease(1, 1_990_656L, 2962, listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        // Before the reveal: the peer is a throwaway, never a contact, so its lease is rejected.
        assertFalse(s.caller.peerVerifiedIn(store))
        val early = PresenceSession(localEligible = true)
        early.start()
        early.onRecord(DiscoveryExchange.RecognitionOffer, s.caller.peerVerifiedIn(store))
        assertTrue(early.onRecord(lease, s.caller.peerVerifiedIn(store)).single() is PresenceSession.Action.Reject)
        // The callee reveals realR: the same call now judges the lease against realR and stores it.
        val calleeRecord = DiscoveryExchange.decode(requireNotNull(s.callee.reveal).sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal
        requireNotNull(s.caller.reveal).onPeerReveal(calleeRecord)
        assertTrue(s.caller.peerVerifiedIn(store))
        assertFalse("the throwaway itself is still nobody", store(s.caller.peerNodeId, s.caller.peerFingerprint) == VerificationState.VERIFIED)
        val session = PresenceSession(localEligible = true)
        session.start()
        session.onRecord(DiscoveryExchange.RecognitionOffer, s.caller.peerVerifiedIn(store))
        assertTrue(session.onRecord(lease, s.caller.peerVerifiedIn(store)).single() is PresenceSession.Action.StoreLease)
    }

    @Test
    fun theRealIdentityAppearsOnlyAfterTheCalleeRevealedAndTheCallerVerifiedIt() {
        val realI = newIdentity()
        val realR = newIdentity()
        val s = strangerCall(realI, realR)
        val calleeReveal = requireNotNull(s.callee.reveal)
        val callerReveal = requireNotNull(s.caller.reveal)
        assertEquals("nothing signed by a real key before the accept", 0, realR.signed.get() + realI.signed.get())
        assertNull(callerReveal.revealed)
        assertTrue(callerReveal.peerRevealRequired())
        assertFalse(callerReveal.callerMayReveal())
        // The caller cannot reveal before it saw the callee's reveal.
        assertThrows(Bmh3Failure::class.java) { callerReveal.sendOwn(realI, emptyList()) }
        // Accept: the callee reveals first, with its opening entries.
        val calleeRecord = DiscoveryExchange.decode(calleeReveal.sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal
        assertEquals(1, realR.signed.get())
        val verifiedCallee = callerReveal.onPeerReveal(calleeRecord)
        assertEquals(realR.nodeId, verifiedCallee.nodeId)
        assertEquals(listOf(entry), verifiedCallee.entries)
        assertEquals(realR.nodeId, s.caller.effectivePeerNodeId)
        assertEquals(IdentityFingerprint.fromPublicKey(realR.publicKeyEncoded), s.caller.effectivePeerFingerprint)
        assertFalse(s.caller.peerIsPseudonym)
        // Only now may the caller reveal; the callee then confirms it.
        assertTrue(callerReveal.callerMayReveal())
        val callerRecord = DiscoveryExchange.decode(callerReveal.sendOwn(realI, emptyList())) as DiscoveryExchange.IdentityReveal
        assertEquals(realI.nodeId, calleeReveal.onPeerReveal(callerRecord).nodeId)
        assertEquals(realI.nodeId, s.callee.effectivePeerNodeId)
        assertEquals(1, realI.signed.get())
        assertEquals("the handshake identity stays the throwaway", s.callee.peerNodeId, s.callee.peerNodeId)
        assertNotEquals(realI.nodeId, s.callee.peerNodeId)
    }

    /**
     * Review 2026-10-27 (finding 1, high): the callee revealed itself at Accept but never required the caller's reveal, so a
     * caller could talk for the whole call as a throwaway. The callee now refuses the caller's media until the caller's
     * reveal verified, and hangs up when it has not arrived within the deadline.
     */
    @Test
    fun anAnsweredStrangerCallRefusesTheCallersMediaUntilTheCallerRevealedAndEndsWithoutIt() {
        val realI = newIdentity()
        val realR = newIdentity()
        val s = strangerCall(realI, realR)
        val callee = requireNotNull(s.callee.reveal)
        val caller = requireNotNull(s.caller.reveal)
        // Accept: the callee reveals itself; the caller is still a throwaway.
        val calleeRecord = DiscoveryExchange.decode(callee.sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal
        assertTrue(callee.peerRevealRequired())
        assertEquals(CallMediaGate.Decision.REFUSE, CallMediaGate.decide(PrivateCallPhase.NEGOTIATING_MEDIA, callee.peerRevealRequired()))
        assertEquals(CallMediaGate.Decision.REFUSE, CallMediaGate.decide(PrivateCallPhase.ACTIVE, callee.peerRevealRequired()))
        // A caller that never reveals: the callee ends the call at the deadline, not before.
        assertFalse(CallerRevealGate.expired(callee.peerRevealRequired(), acceptedAtMs = 1_000L, nowMs = 1_000L + CallerRevealGate.DEADLINE_MS - 1))
        assertTrue(CallerRevealGate.expired(callee.peerRevealRequired(), acceptedAtMs = 1_000L, nowMs = 1_000L + CallerRevealGate.DEADLINE_MS))
        // A genuine caller behind relays gets more time (its reveal crosses every hop), bounded whatever it claims.
        assertFalse(CallerRevealGate.expired(true, acceptedAtMs = 0L, nowMs = CallerRevealGate.DEADLINE_MS, relayHops = 2))
        assertTrue(CallerRevealGate.expired(true, acceptedAtMs = 0L, nowMs = CallerRevealGate.MAX_DEADLINE_MS, relayHops = 1_000))
        // An honest caller verifies the callee and reveals itself: media flows and the deadline no longer applies.
        caller.onPeerReveal(calleeRecord)
        val callerRecord = DiscoveryExchange.decode(caller.sendOwn(realI, emptyList())) as DiscoveryExchange.IdentityReveal
        callee.onPeerReveal(callerRecord)
        assertFalse(callee.peerRevealRequired())
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.ACTIVE, callee.peerRevealRequired()))
        assertFalse(CallerRevealGate.expired(callee.peerRevealRequired(), acceptedAtMs = 0L, nowMs = Long.MAX_VALUE))
    }

    /**
     * Review 2026-10-27 (S2): a real callee (pseudonyms off) answering a pseudonymous caller skipped the link opening while
     * the caller sent one and waited for the answer, so the call hung. Both sides now agree: a responder skips only when it
     * presented a throwaway itself.
     */
    @Test
    fun theLinkOpeningRunsOnBothSidesOrNeitherWhoeverPresentedAThrowaway() {
        fun openings(calleeOptions: Bmh3ResponderOptions): Pair<List<OpeningEntry>?, List<OpeningEntry>?> {
            val s = call(newIdentity(), newIdentity(), { Bmh3InitiatorOptions(strangerIdentity = newIdentity()) }, { calleeOptions })
            val result = both(
                {
                    if (RelayIdentityPolicy.skipsLinkOpening(true, s.callerLink.ownPseudonymous, s.callerLink.peerPseudonymous)) null
                    else LinkOpeningExchange.exchange(s.callerLink, caps, emptyList())
                },
                {
                    if (RelayIdentityPolicy.skipsLinkOpening(false, s.calleeLink.ownPseudonymous, s.calleeLink.peerPseudonymous)) null
                    else LinkOpeningExchange.exchange(s.calleeLink, responderCaps, listOf(entry))
                },
            )
            return result.initiator.getOrThrow() to result.responder.getOrThrow()
        }
        // Real callee: both exchange, and the caller gets the callee's opening.
        val real = openings(Bmh3ResponderOptions())
        assertEquals(listOf(entry), real.first)
        assertEquals(emptyList<OpeningEntry>(), real.second)
        // Pseudonymous callee: neither side sends one (the salt stays with the callee).
        val stranger = openings(Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false))
        assertNull(stranger.first)
        assertNull(stranger.second)
    }

    /**
     * Security review 2026-10-27 (item 2): the deadline trusted the caller's own hop claim even on a direct call, and a
     * reveal lost to a link renewal ended the call. A direct call gets no extra time; a resent identical reveal is ignored,
     * any other second reveal is still refused.
     */
    @Test
    fun aDirectCallGetsNoHopAllowanceAndARenewedLinkMayRepeatTheSameReveal() {
        assertEquals(0, CallerRevealGate.effectiveHops(direct = true, claimedHops = 3))
        assertEquals(1, CallerRevealGate.effectiveHops(direct = false, claimedHops = 0))
        assertEquals(3, CallerRevealGate.effectiveHops(direct = false, claimedHops = 9))
        val realI = newIdentity()
        val realR = newIdentity()
        val s = strangerCall(realI, realR)
        val callee = requireNotNull(s.callee.reveal)
        val caller = requireNotNull(s.caller.reveal)
        val calleeRecord = DiscoveryExchange.decode(callee.sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal
        assertFalse(caller.isRepeatOfVerified(calleeRecord))
        caller.onPeerReveal(calleeRecord)
        assertTrue("the same bytes again are a repeat", caller.isRepeatOfVerified(calleeRecord))
        val forged = DiscoveryExchange.IdentityReveal(calleeRecord.role, calleeRecord.spki, calleeRecord.entries, ByteArray(64) { 1 })
        assertFalse(caller.isRepeatOfVerified(forged))
        assertThrows(Bmh3Failure::class.java) { caller.onPeerReveal(forged) }
    }

    @Test
    fun aRealCalleeAnsweringAPseudonymousCallerExpectsNoRevealFromIt() {
        // Pseudonyms off on the callee: it never reveals, so it cannot require the caller's reveal either.
        val realI = newIdentity()
        val realR = newIdentity()
        val s = call(
            realI, realR,
            { Bmh3InitiatorOptions(strangerIdentity = newIdentity()) },
            { Bmh3ResponderOptions() },
        )
        val callee = requireNotNull(s.callee.reveal)
        assertTrue(callee.peerPseudonymous)
        assertFalse(callee.ownPseudonymous)
        assertFalse(callee.peerRevealRequired())
        assertEquals(CallMediaGate.Decision.ALLOW, CallMediaGate.decide(PrivateCallPhase.ACTIVE, callee.peerRevealRequired()))
    }

    @Test
    fun revealOrderRepeatsAndWrongPeersAreProtocolErrors() {
        val realI = newIdentity()
        val realR = newIdentity()
        val s = strangerCall(realI, realR)
        val callee = requireNotNull(s.callee.reveal)
        val caller = requireNotNull(s.caller.reveal)
        // The callee must not accept the caller's reveal before it sent its own.
        val early = DiscoveryExchange.IdentityReveal(Bmh3Reveal.ROLE_CALLER, realI.publicKeyEncoded, emptyList(), ByteArray(64) { 1 })
        assertEquals("caller reveal before the callee's", assertThrows(Bmh3Failure::class.java) { callee.onPeerReveal(early) }.reason)
        // Once an exchange failed it stays failed: no reveal is sent after it.
        assertThrows(Bmh3Failure::class.java) { callee.sendOwn(realR, listOf(entry)) }
        // A fresh session: send once, never twice; a second peer reveal is an error.
        val s2 = strangerCall(realI, realR)
        val callee2 = requireNotNull(s2.callee.reveal)
        val caller2 = requireNotNull(s2.caller.reveal)
        val calleeBytes = callee2.sendOwn(realR, listOf(entry))
        assertThrows(Bmh3Failure::class.java) { callee2.sendOwn(realR, listOf(entry)) }
        val record = DiscoveryExchange.decode(calleeBytes) as DiscoveryExchange.IdentityReveal
        caller2.onPeerReveal(record)
        assertThrows(Bmh3Failure::class.java) { caller2.onPeerReveal(record) }
        // A reveal carrying the wrong role is rejected (the caller expects the callee's).
        val s3 = strangerCall(realI, realR)
        val wrongRole = DiscoveryExchange.IdentityReveal(Bmh3Reveal.ROLE_CALLER, realR.publicKeyEncoded, emptyList(), ByteArray(64) { 1 })
        assertThrows(Bmh3Failure::class.java) { requireNotNull(s3.caller.reveal).onPeerReveal(wrongRole) }
        assertNotNull(caller)
    }

    @Test
    fun aRevealIsBoundToItsSessionAndToTheBytesItSigned() {
        val realR = newIdentity()
        val a = strangerCall(newIdentity(), realR)
        val b = strangerCall(newIdentity(), realR)
        val fromA = DiscoveryExchange.decode(requireNotNull(a.callee.reveal).sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal
        // Replayed (or forwarded by a man in the middle) into another session: the exporter differs.
        assertThrows(Bmh3Failure::class.java) { requireNotNull(b.caller.reveal).onPeerReveal(fromA) }
        // The salt is under the signature.
        val c = strangerCall(newIdentity(), realR)
        val real = DiscoveryExchange.decode(requireNotNull(c.callee.reveal).sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal
        val altered = DiscoveryExchange.IdentityReveal(
            real.role, real.spki, listOf(OpeningEntry(entry.rdi, entry.epoch, entry.role, ByteArray(32) { 4 })), real.signature,
        )
        assertThrows(Bmh3Failure::class.java) { requireNotNull(c.caller.reveal).onPeerReveal(altered) }
        // A reveal that names the sender's own throwaway as its real identity is refused before any signature check.
        val throwaway = newIdentity()
        val naming = DiscoveryExchange.IdentityReveal(Bmh3Reveal.ROLE_CALLEE, throwaway.publicKeyEncoded, emptyList(), ByteArray(64) { 1 })
        assertEquals(
            "reveal names the pseudonym",
            assertThrows(Bmh3Failure::class.java) {
                Bmh3Reveal.verify(naming, Bmh3Reveal.ROLE_CALLEE, ByteArray(32), throwaway.publicKeyEncoded, newIdentity().publicKeyEncoded, emptyList(), ByteArray(0))
            }.reason,
        )
        // The caller reveal commits to the callee record it verified: another record in its place does not verify.
        val e = strangerCall(newIdentity(), realR)
        val calleeSide = requireNotNull(e.callee.reveal)
        val callerSide = requireNotNull(e.caller.reveal)
        callerSide.onPeerReveal(DiscoveryExchange.decode(calleeSide.sendOwn(realR, listOf(entry))) as DiscoveryExchange.IdentityReveal)
        val callerReveal = DiscoveryExchange.decode(callerSide.sendOwn(newIdentity(), emptyList())) as DiscoveryExchange.IdentityReveal
        val f = strangerCall(newIdentity(), realR)
        val other = requireNotNull(f.callee.reveal)
        other.sendOwn(realR, listOf(entry))
        assertThrows(Bmh3Failure::class.java) { other.onPeerReveal(callerReveal) }
        calleeSide.onPeerReveal(callerReveal)
    }

    @Test
    fun noRevealIsSentToOrAcceptedFromAPeerThatDidNotDeclareAThrowaway() {
        // Two older BMH3 phones: no throwaway on either side, so no reveal session exists.
        val s = call(newIdentity(), newIdentity(), { Bmh3InitiatorOptions() }, { Bmh3ResponderOptions(acceptBmh2 = true) })
        assertNull("a plain BMH3 pair has no reveal session", s.caller.reveal)
        // A throwaway caller meets an older responder (real key, no declaration).
        val callerThrowaway = newIdentity()
        val realI = newIdentity()
        val realR = newIdentity()
        val pipes = Duplex()
        val links = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, realI, caps, bmh3 = Bmh3InitiatorOptions(strangerIdentity = callerThrowaway)) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, realR, responderCaps, bmh3 = Bmh3ResponderOptions()) },
        )
        val linkI = links.initiator.getOrThrow()
        val linkR = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(linkI, realI, caps, bmh3 = Bmh3InitiatorOptions(strangerIdentity = callerThrowaway)) },
            { EndToEndVideoHandshake.respond(linkR, realR, responderCaps, bmh3 = Bmh3ResponderOptions()) },
        )
        val caller = ends.initiator.getOrThrow()
        val reveal = requireNotNull(caller.reveal)
        assertTrue(reveal.ownPseudonymous && !reveal.peerPseudonymous)
        assertFalse(reveal.active)
        assertThrows(Bmh3Failure::class.java) { reveal.sendOwn(realI, emptyList()) }
        assertEquals("a throwaway caller meeting an older responder stays a throwaway", 0, realI.signed.get())
    }

    @Test
    fun theRevealRecordCodecIsStrict() {
        val real = newIdentity()
        val record = Bmh3Reveal.build(
            Bmh3Reveal.ROLE_CALLEE, real, ByteArray(32) { 1 }, newIdentity().publicKeyEncoded, newIdentity().publicKeyEncoded,
            listOf(entry), ByteArray(0),
        )
        val encoded = DiscoveryExchange.encode(record)
        assertEquals(4 + 1 + 1 + 2 + 91 + 1 + 43 + 64, encoded.size)
        val decoded = DiscoveryExchange.decode(encoded) as DiscoveryExchange.IdentityReveal
        assertEquals(record.role, decoded.role)
        assertArrayEquals(record.spki, decoded.spki)
        assertEquals(record.entries, decoded.entries)
        assertArrayEquals(record.signature, decoded.signature)
        assertThrows(IllegalArgumentException::class.java) { DiscoveryExchange.decode(encoded.copyOf(encoded.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryExchange.decode(encoded + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryExchange.decode(encoded.copyOf().also { it[5] = 3 }) }
        assertThrows(IllegalArgumentException::class.java) { DiscoveryExchange.decode(encoded.copyOf().also { it[7] = 90 }) }
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryExchange.IdentityReveal(Bmh3Reveal.ROLE_CALLER, record.spki, listOf(entry), record.signature)
        }
    }

    // ----------------------------------------------------------------------------- policy, leases and abuse limits

    private val rdi = ByteArray(6) { (it + 10).toByte() }
    private val offer = DiscoveryAnnouncement(
        nodeIdPrefix = rdi, flags = 0x41, capabilitySummary = 3, streamToken = 0, routeCost = 0, sequence = 0,
        version = DiscoveryAnnouncement.ROTATING_VERSION,
    )
    private val ownEntry = OpeningEntry(rdi, 1_990_656L, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 6 })

    private class FakeLeases(
        private val grant: Bmh3LeaseGrant?,
        private val dayKey: ByteArray?,
        private val delivered: Boolean,
    ) : Bmh3LeaseSource {
        override fun leaseFor(selected: DiscoveryAnnouncement): Bmh3LeaseGrant? = grant
        override fun ownDayKey(entry: OpeningEntry): ByteArray? = dayKey?.copyOf()
        override fun deliveredTo(peer: NodeId): Boolean = delivered
    }

    @Test
    fun realLeaseKeysMakeTheTagsOfBothContactsAgreeAndStrangersGetNoTag() {
        val lease = ByteArray(32) { (it * 3 + 1).toByte() }
        val contact = newIdentity().nodeId
        val source = FakeLeases(Bmh3LeaseGrant(contact, lease), lease, delivered = true)
        val tag = requireNotNull(Bmh3ContactTags.initiatorTag(offer, 0, source))
        assertEquals(contact, tag.expectedPeer)
        val checks = Bmh3ContactTags.responderChecks(listOf(ownEntry), ByteArray(6), 0, source)
        assertEquals(1, checks.size)
        assertArrayEquals(tag.key, checks.single().key)
        assertArrayEquals(tag.binding, checks.single().binding)
        // No lease for this advert, a relay link (the answerer is not the owner) or no source: a random tag.
        assertNull(Bmh3ContactTags.initiatorTag(offer, 0, FakeLeases(null, null, false)))
        assertNull(Bmh3ContactTags.initiatorTag(offer, 0, source, toAdvertOwner = false))
        assertNull(Bmh3ContactTags.initiatorTag(offer, 0, null))
        assertTrue(Bmh3ContactTags.responderChecks(listOf(ownEntry), ByteArray(6), 0, null).isEmpty())
        assertTrue(Bmh3ContactTags.responderChecks(listOf(ownEntry), ByteArray(6), 0, FakeLeases(null, null, false)).isEmpty())
        // End to end: the two derived tags match in the engine, so the contact gets the real key.
        val realI = newIdentity()
        val realR = newIdentity()
        val run = handshake(
            Bmh3Purpose.LINK, realI, realR, newIdentity(), newIdentity(),
            contactTag = tag, tagChecks = checks,
        )
        assertTrue(run.res.tagMatched)
        assertFalse(run.res.peer.pseudonymous)
    }

    @Test
    fun theCallerPresentsAThrowawayOnlyToTheAdvertOwnerAndOnlyInPseudonymMode() {
        val keys = Bmh3PseudonymKeys()
        val on = Bmh3Policy.initiatorOptions(offer, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true, pseudonymKeys = keys)
        assertNotNull(on.strangerIdentity)
        val same = newIdentity()
        assertSame(same, Bmh3Policy.initiatorOptions(offer, Bmh3Policy.Layer.ENDPOINT, log = {}, pseudonyms = true, strangerIdentity = same).strangerIdentity)
        // A version 2 offer too: the callee's throwaway is bound by the reveal there as well.
        val v2 = offer.copy(version = 2)
        assertNotNull(Bmh3Policy.initiatorOptions(v2, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true).strangerIdentity)
        // Toward a relay (route cost above 0) the link already uses a throwaway key of its own.
        val relayed = offer.copy(routeCost = 1)
        val toRelay = Bmh3Policy.initiatorOptions(relayed, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true)
        assertNull(toRelay.strangerIdentity)
        assertNotNull(toRelay.identityOverride)
        assertNotNull(Bmh3Policy.initiatorOptions(relayed, Bmh3Policy.Layer.ENDPOINT, log = {}, pseudonyms = true).strangerIdentity)
        // Off: BMH3 Phase 1 exactly.
        assertNull(Bmh3Policy.initiatorOptions(offer, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = false).strangerIdentity)
        assertNull(Bmh3Policy.initiatorOptions(null, Bmh3Policy.Layer.LINK, log = {}, pseudonyms = true).strangerIdentity)
    }

    /**
     * Review 2026-10-27 (finding 11, S1, finding 5): the link and end-to-end handshakes of one attempt decided separately;
     * a Classic connector's link got a throwaway of its own, so the callee met two strangers and took the call for a
     * relayed one. Built from one plan, both layers present the same throwaway and the callee sees one stranger.
     */
    @Test
    fun oneAttemptPlanShowsTheCalleeOneStrangerAtBothLayersWhoeverRunsTheLinkHandshake() {
        val realI = newIdentity()
        val realR = newIdentity()
        val plan = Bmh3AttemptPlan.create(enabled = true, only = false, selected = offer, pinned = false, pseudonymMode = true)
        assertTrue(plan.bmh3)
        assertNotNull(plan.callerAlias)
        val linkPipes = Duplex()
        val calleeOptions = Bmh3ResponderOptions(strangerIdentity = newIdentity(), acceptBmh2 = false)
        // The link options are built first (as a Classic connector does before the call endpoint runs), the end-to-end
        // options later, from the same plan.
        val linkOptions = requireNotNull(plan.initiator(Bmh3Policy.Layer.LINK))
        val links = both(
            { SecureBluetoothHandshake.initiate(linkPipes.bToA.input, linkPipes.aToB.output, realI, caps, bmh3 = linkOptions) },
            { SecureBluetoothHandshake.respond(linkPipes.aToB.input, linkPipes.bToA.output, realR, responderCaps, bmh3 = calleeOptions) },
        )
        val callerLink = links.initiator.getOrThrow()
        val calleeLink = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(callerLink, realI, caps, bmh3 = plan.initiator(Bmh3Policy.Layer.ENDPOINT)) },
            { EndToEndVideoHandshake.respond(calleeLink, realR, responderCaps, bmh3 = calleeOptions) },
        )
        val callee = ends.responder.getOrThrow()
        assertTrue(callee.peerIsPseudonym)
        assertEquals("the callee's link and end-to-end peers are one stranger (not a relay)", calleeLink.peerNodeId, callee.peerNodeId)
        assertEquals(requireNotNull(plan.callerAlias).nodeId, callee.peerNodeId)
        assertEquals(0, realI.signed.get())
    }

    /**
     * Review 2026-10-27 (finding 6): every BMH3 success was pinned in a plain file, in pseudonym mode a list of the contacts
     * this phone called. A pin only serves a version 2 advert whose hint can be stripped: nothing else is pinned.
     */
    @Test
    fun onlyAVersion2OfferOutsidePseudonymModeIsPinned() {
        val answerer = newIdentity()
        val prefix = answerer.nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        val v2 = offer.copy(nodeIdPrefix = prefix, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION)
        val peer = Bmh3Peer(answerer.publicKeyEncoded, answerer.nodeId, responderCaps, Bmh3Extensions.NONE)
        fun pinsAfterSuccess(selected: DiscoveryAnnouncement, pseudonymMode: Boolean): List<String> {
            val pins = mutableListOf<String>()
            val plan = Bmh3AttemptPlan.create(
                enabled = true, only = true, selected = selected, pinned = false, pseudonymMode = pseudonymMode,
                pin = { pins += it.joinToString("") { b -> "%02x".format(b) } },
            )
            runCatching { requireNotNull(plan.initiator(Bmh3Policy.Layer.LINK)).checkResponder(peer) }
            return pins
        }
        assertEquals(1, pinsAfterSuccess(v2, pseudonymMode = false).size)
        assertTrue("pseudonym mode pins nothing", pinsAfterSuccess(v2, pseudonymMode = true).isEmpty())
        assertFalse("a rotating offer is always BMH3: nothing to pin", Bmh3AttemptPlan.pinAllowed(offer, pseudonymMode = false))
        assertFalse(Bmh3AttemptPlan.pinAllowed(null, pseudonymMode = false))
    }

    /**
     * reviewer review 2026-10-27 (finding 8): a pin store that could not be read counted as "not pinned", so a copied version 2
     * offer with its hint stripped drew this phone's real key in a BMH2 hello. Unknown now counts as pinned: BMH3.
     */
    @Test
    fun anUnreadablePinStoreNeverLetsAStrippedHintDowngradeToBmh2() {
        val pins = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = throw SealedTextUnavailable(null)
            override fun write(value: String) = Unit
        })
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(Bmh3Pins.PinState.UNKNOWN, pins.pinState(prefix))
        val stripped = offer.copy(nodeIdPrefix = prefix, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION, capabilitySummary = 1)
        val plan = Bmh3AttemptPlan.create(
            enabled = true, only = false, selected = stripped,
            pinned = pins.pinState(prefix) != Bmh3Pins.PinState.UNPINNED, pseudonymMode = false,
        )
        assertTrue(plan.bmh3)
        assertNotNull(plan.initiator(Bmh3Policy.Layer.LINK))
    }

    /**
     * Review 2026-10-27 (finding 8): the verified-contact check ran only at the end-to-end layer, after this phone had
     * already presented its real key at the link. A direct link's real-key answerer that shows a verified contact's prefix
     * with another key now ends the handshake before this phone signs anything.
     */
    @Test
    fun aMismatchingRealKeyAtTheLinkEndsTheHandshakeBeforeTheCallerSignsAnything() {
        val realI = newIdentity()
        val realR = newIdentity()
        val mismatch = Bmh3Policy.initiatorOptions(
            null, Bmh3Policy.Layer.LINK, log = {},
            admitLinkPeer = { throw IdentityMismatchException("a verified contact's prefix with another key") },
        )
        val pipes = Duplex()
        val out = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, realI, caps, bmh3 = mismatch) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, realR, responderCaps, bmh3 = Bmh3ResponderOptions()) },
            onResponderFailure = { pipes.bToA.close() },
            onInitiatorFailure = { pipes.aToB.close() },
        )
        assertTrue(generateSequence(out.initiator.exceptionOrNull()) { it.cause }.any { it is IdentityMismatchException })
        assertEquals("the caller's real key signed nothing", 0, realI.signed.get())
        // Toward a relay (route cost above 0) the link peer is not the person called: no contact check there.
        val admitted = mutableListOf<NodeId>()
        val toRelay = Bmh3Policy.initiatorOptions(offer.copy(routeCost = 1), Bmh3Policy.Layer.LINK, log = {}, admitLinkPeer = { admitted += it.nodeId })
        toRelay.checkResponder(Bmh3Peer(realR.publicKeyEncoded, realR.nodeId, responderCaps, Bmh3Extensions.NONE))
        assertTrue(admitted.isEmpty())
    }

    /**
     * Security review 2026-10-27 (item 10): while only the Meet screen makes the phone reachable, a connection without the
     * code's tag is closed before the phone draws a key or signs anything, with pseudonyms on or off, and a BMH2 hello is
     * refused.
     */
    @Test
    fun theMeetScreenClosesEveryUntaggedConnectionBeforeSigning() {
        for (pseudonyms in listOf(true, false)) {
            val realR = newIdentity()
            val options = Bmh3Policy.responderOptions(
                opening = emptyList(), fixedPrefix = ByteArray(6), admitInitiator = { _, _ -> true }, log = {},
                pseudonyms = pseudonyms, screenOnlyReachable = true,
            )
            assertFalse(options.acceptBmh2)
            val pipes = Duplex()
            val out = both(
                { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, newIdentity(), caps, bmh3 = Bmh3InitiatorOptions()) },
                { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, realR, responderCaps, bmh3 = options) },
                onResponderFailure = { pipes.bToA.close() },
                onInitiatorFailure = { pipes.aToB.close() },
            )
            assertTrue(out.responder.isFailure)
            assertEquals("pseudonyms=$pseudonyms: nothing signed", 0, realR.signed.get())
        }
    }

    @Test
    fun theCalleeFailsClosedRefusesBmh2AndKeepsPerEpochGuards() {
        val keys = Bmh3PseudonymKeys()
        val guards = Bmh3EpochGuards()
        fun options(opening: List<OpeningEntry>, epoch: Long?, pseudonyms: Boolean = true) = Bmh3Policy.responderOptions(
            opening, ByteArray(6), admitInitiator = { _, _ -> true }, log = {}, pseudonyms = pseudonyms, pseudonymKeys = keys,
            epoch = epoch, guards = guards,
        )
        // An empty pin list (an advert set that stopped, a Classic listener) still gets a throwaway (PR4).
        val empty = options(emptyList(), 100)
        assertNotNull(empty.strangerIdentity)
        assertFalse("a BMH2 hello would be answered with the real key (PR1)", empty.acceptBmh2)
        // One key per connection (PR7).
        assertNotSame(empty.strangerIdentity, options(listOf(ownEntry), 100).strangerIdentity)
        // The replay cache and the signing budget are per epoch and dropped two epochs later (PR3).
        assertSame(options(emptyList(), 100).replayCache, options(emptyList(), 100).replayCache)
        assertNotSame(options(emptyList(), 100).replayCache, options(emptyList(), 101).replayCache)
        val firstEpochCache = options(emptyList(), 200).replayCache
        options(emptyList(), 201)
        options(emptyList(), 202)
        assertNotSame("epoch 200 was dropped", firstEpochCache, options(emptyList(), 200).replayCache)
        // A msg1 replayed after the boundary meets a fresh cache: no "same phone" signal.
        val ephemeral = ByteArray(65).also { it[0] = 4; it[1] = 9 }
        assertTrue(options(emptyList(), 300).replayCache!!.firstUse(ephemeral))
        assertFalse(options(emptyList(), 300).replayCache!!.firstUse(ephemeral))
        assertTrue(options(emptyList(), 301).replayCache!!.firstUse(ephemeral))
        // Off: Phase 1 exactly (real key, BMH2 accepted, the process-wide guards).
        val off = options(emptyList(), 100, pseudonyms = false)
        assertNull(off.strangerIdentity)
        assertTrue(off.acceptBmh2)
        assertSame(Bmh3Policy.replayCache, off.replayCache)
    }

    @Test
    fun aTaggedConnectionFromSomeoneNeverGivenALeaseIsClosedAndLogged() {
        val log = mutableListOf<String>()
        fun admit(delivered: Boolean, matched: Boolean): Boolean = Bmh3Policy.responderOptions(
            listOf(ownEntry), ByteArray(6), admitInitiator = { _, _ -> true }, log = { log += it }, pseudonyms = true,
            leaseSource = FakeLeases(null, ByteArray(32) { 1 }, delivered), epoch = 1,
        ).admitInitiator(Bmh3Peer(newIdentity().publicKeyEncoded, newIdentity().nodeId, caps, Bmh3Extensions.NONE), matched)
        assertTrue(admit(delivered = true, matched = true))
        assertTrue("an untagged connection is not a lease question", admit(delivered = false, matched = false))
        assertFalse(admit(delivered = false, matched = true))
        assertEquals(listOf("bmh3: event=lease_misuse"), log)
    }

    @Test
    fun theSwitchIsAChildOfHiddenIdentitiesAndOnByDefaultSinceTheBeta() {
        val spec = ConnectivityFeatures.bmh3Pseudonyms
        // A child of Hidden identities that also needs rotating IDs: greyed out while either is off.
        assertEquals(listOf("bmh3", RotatingDiscoveryFeature.spec.id), spec.dependsOn)
        assertTrue(spec.defaultEnabled)
        assertEquals("test_bmh3_pseudonyms", spec.benchmarkExtra)
        assertTrue(spec in ConnectivityFeatures.all)
        assertTrue(ConnectivityFeatureDefaults.BMH3_PSEUDONYMS)
        // No Settings switch since 2026-11-04: a stored choice no longer turns it off.
        assertTrue(spec.constant)
    }
}
