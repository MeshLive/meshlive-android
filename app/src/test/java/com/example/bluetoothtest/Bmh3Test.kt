package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** BMH3 behaviour tests: orders, active probes, tampering, replay, negotiation, link and endpoint drivers, relays. */
class Bmh3Test {
    /** Safety net: a test that hangs fails after this instead of holding the whole run (a legitimate run on a loaded host takes seconds to a minute or two). */
    @get:org.junit.Rule
    val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(600)

    private val caps = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
        maxBitrateKbps = 1_100,
        maxChildren = 0,
    )
    private val responderCaps = caps.copy(maxChildren = 1)

    private val binding = Bmh3Crypto.advertBinding(byteArrayOf(1, 2, 3, 4, 5, 6), 0, null)
    private fun tag(key: ByteArray, bound: ByteArray = binding) = Bmh3ContactTag(key, bound)

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

    private fun config(purpose: Bmh3Purpose, identity: SigningIdentity, capabilities: NodeCapabilities = caps) =
        Bmh3Config(purpose, identity, capabilities)

    private class Run(val messages: MutableList<ByteArray>, val initiator: Bmh3Initiator, val responder: Bmh3Responder)

    /** Runs one handshake in memory and records every message in order. */
    private fun run(
        initiator: Bmh3Initiator,
        responder: Bmh3Responder,
        mutate: (index: Int, message: ByteArray) -> ByteArray = { _, message -> message },
    ): Run {
        val wire = mutableListOf<ByteArray>()
        fun carry(message: ByteArray): ByteArray = mutate(wire.size, message).also { wire += it }
        val m2 = responder.onMessage1(carry(initiator.message1()))
        val m3 = initiator.onMessage2(carry(m2))
        val m4 = responder.onMessage3(carry(m3))
        if (m4 != null) initiator.onMessage4(carry(m4))
        return Run(wire, initiator, responder)
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (i in needle.indices) if (haystack[start + i] != needle[i]) continue@outer
            return true
        }
        return false
    }

    // ------------------------------------------------------------------------------------ orders and keys

    @Test
    fun everyoneModeRunsRFirstAndBothSidesAgreeOnKeysAndPeers() {
        val i = newIdentity()
        val r = newIdentity()
        for (purpose in Bmh3Purpose.entries) {
            val run = run(
                Bmh3Initiator(config(purpose, i)),
                Bmh3Responder(config(purpose, r, responderCaps)),
            )
            assertEquals(listOf(122, 431, 334), run.messages.map { it.size })
            val ini = run.initiator.result()
            val res = run.responder.result()
            assertEquals(Bmh3Order.R_FIRST, ini.order)
            assertArrayEquals(ini.sendKey, res.receiveKey)
            assertArrayEquals(ini.receiveKey, res.sendKey)
            assertFalse(ini.sendKey.contentEquals(ini.receiveKey))
            assertEquals(r.nodeId, ini.peer.nodeId)
            assertEquals(i.nodeId, res.peer.nodeId)
            assertEquals(responderCaps, ini.peer.capabilities)
            assertEquals(caps, res.peer.capabilities)
            assertFalse(res.tagMatched)
        }
    }

    @Test
    fun theSessionKeysDifferBetweenHandshakesAndBetweenPurposes() {
        val i = newIdentity()
        val r = newIdentity()
        val a = run(Bmh3Initiator(config(Bmh3Purpose.LINK, i)), Bmh3Responder(config(Bmh3Purpose.LINK, r))).initiator.result()
        val b = run(Bmh3Initiator(config(Bmh3Purpose.LINK, i)), Bmh3Responder(config(Bmh3Purpose.LINK, r))).initiator.result()
        val c = run(Bmh3Initiator(config(Bmh3Purpose.END_TO_END, i)), Bmh3Responder(config(Bmh3Purpose.END_TO_END, r))).initiator.result()
        assertFalse(a.sendKey.contentEquals(b.sendKey))
        assertFalse(a.sendKey.contentEquals(c.sendKey))
        assertFalse(a.exporter.contentEquals(b.exporter))
    }

    // ------------------------------------------------------------------------------- what a sniffer learns

    @Test
    fun aPassiveSnifferSeesNeitherIdentityNorCapabilitiesInEitherOrder() {
        val i = newIdentity()
        val r = newIdentity()
        val lease = ByteArray(32) { (it + 9).toByte() }
        val tagKey = Bmh3Crypto.contactTagKey(lease)
        val runs = listOf(
            run(Bmh3Initiator(config(Bmh3Purpose.LINK, i)), Bmh3Responder(config(Bmh3Purpose.LINK, r, responderCaps))),
            run(
                Bmh3Initiator(config(Bmh3Purpose.END_TO_END, i), mayRevealFirst = true),
                Bmh3Responder(config(Bmh3Purpose.END_TO_END, r, responderCaps), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(tag(tagKey))),
            ),
            run(
                Bmh3Initiator(config(Bmh3Purpose.LINK, i), contactTag = tag(tagKey)),
                Bmh3Responder(config(Bmh3Purpose.LINK, r, responderCaps), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(tag(tagKey))),
            ),
        )
        assertEquals(listOf(Bmh3Order.R_FIRST, Bmh3Order.I_FIRST, Bmh3Order.R_FIRST), runs.map { it.initiator.result().order })
        for (run in runs) {
            val wire = run.messages.fold(ByteArray(0)) { acc, m -> acc + m }
            for (identity in listOf(i, r)) {
                assertFalse("SPKI on the wire", contains(wire, identity.publicKeyEncoded))
                assertFalse("node ID on the wire", contains(wire, identity.nodeId.toByteArray()))
                assertFalse("node prefix on the wire", contains(wire, identity.nodeId.toByteArray().copyOf(6)))
                assertFalse("EC point on the wire", contains(wire, identity.publicKeyEncoded.copyOfRange(26, 91)))
            }
            assertFalse("capabilities on the wire", contains(wire, NodeCapabilitiesCodec.encode(caps)))
            assertFalse("capabilities on the wire", contains(wire, NodeCapabilitiesCodec.encode(responderCaps)))
        }
        // All blocks are the same size, so a sniffer cannot tell an opening or an intent from none.
        val withOpening = run(
            Bmh3Initiator(config(Bmh3Purpose.LINK, i)),
            Bmh3Responder(config(Bmh3Purpose.LINK, r), opening = openingValue(1)),
        )
        assertEquals(runs[0].messages.map { it.size }, withOpening.messages.map { it.size })
        val withIntent = run(
            Bmh3Initiator(config(Bmh3Purpose.END_TO_END, i), mayRevealFirst = true, intent = 1),
            Bmh3Responder(config(Bmh3Purpose.END_TO_END, r), mode = Bmh3ResponderMode.CONTACTS_ONLY),
        )
        assertEquals(runs[1].messages.map { it.size }, withIntent.messages.map { it.size })
    }

    private fun openingValue(count: Int): ByteArray = byteArrayOf(count.toByte()) + ByteArray(Bmh3Wire.OPENING_ENTRY_BYTES * count) { (it + 1).toByte() }

    // ------------------------------------------------------------------------ active probes of a responder

    @Test
    fun aContactsOnlyResponderRevealsNothingToAStrangerWhoConnects() {
        val r = newIdentity()
        val lease = ByteArray(32) { (it + 3).toByte() }
        val tagKey = Bmh3Crypto.contactTagKey(lease)
        val probe = newIdentity()

        // (a) The prober does not offer to reveal first: the responder closes without sending anything.
        val a = Bmh3Responder(config(Bmh3Purpose.LINK, r), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(tag(tagKey)))
        val msg1 = Bmh3Initiator(config(Bmh3Purpose.LINK, probe)).message1()
        assertEquals("order conflict", assertThrows(Bmh3Failure::class.java) { a.onMessage1(msg1) }.reason)

        // (b) The prober offers to reveal first: the responder sends only its one-time key and nonce (105 bytes),
        // then closes after the stranger's block without ever sending its own.
        val emitted = ByteArrayOutputStream()
        val b = Bmh3Responder(
            config(Bmh3Purpose.LINK, r),
            mode = Bmh3ResponderMode.CONTACTS_ONLY,
            tagChecks = listOf(tag(tagKey)),
            admitInitiator = { _, _ -> false },
        )
        val prober = Bmh3Initiator(config(Bmh3Purpose.LINK, probe), mayRevealFirst = true)
        val m2 = b.onMessage1(prober.message1()).also { emitted.write(it) }
        assertEquals(Bmh3Wire.MSG2_CLEAR_BYTES, m2.size)
        assertEquals(Bmh3Order.I_FIRST, b.chosenOrder)
        val m3 = prober.onMessage2(m2)
        assertEquals("stranger gets a silent close", assertThrows(Bmh3Failure::class.java) { b.onMessage3(m3) }.reason)
        assertFalse(contains(emitted.toByteArray(), r.publicKeyEncoded))
        assertFalse(contains(emitted.toByteArray(), r.nodeId.toByteArray()))
        assertFalse(b.isComplete)

        // (c) A random tag is not a tag: 16 random bytes never match.
        repeat(50) {
            val responder = Bmh3Responder(config(Bmh3Purpose.LINK, r), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(tag(tagKey)))
            responder.onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, probe), mayRevealFirst = true).message1())
            assertFalse(responder.contactTagMatched)
        }
    }

    @Test
    fun aLeaseHolderGetsRFirstFromAContactsOnlyResponderAndSeesItsOpening() {
        val i = newIdentity()
        val r = newIdentity()
        val tagKey = Bmh3Crypto.contactTagKey(ByteArray(32) { (it + 5).toByte() })
        val opening = openingValue(2)
        var seen: Bmh3Peer? = null
        val run = run(
            Bmh3Initiator(config(Bmh3Purpose.LINK, i), contactTag = tag(tagKey), checkResponder = { seen = it }),
            Bmh3Responder(
                config(Bmh3Purpose.LINK, r),
                mode = Bmh3ResponderMode.CONTACTS_ONLY,
                tagChecks = listOf(tag(Bmh3Crypto.contactTagKey(ByteArray(32))), tag(tagKey)),
                opening = opening,
            ),
        )
        assertEquals(Bmh3Order.R_FIRST, run.initiator.result().order)
        assertTrue(run.responder.contactTagMatched)
        assertArrayEquals(opening, requireNotNull(seen).extensions.opening)
        assertEquals(listOf(122, 431, 334), run.messages.map { it.size })
    }

    @Test
    fun anInitiatorThatSentATagRefusesIFirstAndRevealsNothing() {
        val i = newIdentity()
        val r = newIdentity()
        val wrong = Bmh3Crypto.contactTagKey(ByteArray(32) { 1 })
        val right = Bmh3Crypto.contactTagKey(ByteArray(32) { 2 })
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, i), mayRevealFirst = true, contactTag = tag(wrong))
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, r), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(tag(right)))
        val m2 = responder.onMessage1(initiator.message1())
        assertEquals(Bmh3Wire.MSG2_CLEAR_BYTES, m2.size)
        assertEquals("tag sent, I_FIRST answered", assertThrows(Bmh3Failure::class.java) { initiator.onMessage2(m2) }.reason)
        assertEquals(0, i.signed.get())
    }

    @Test
    fun aDefaultInitiatorRefusesAnAttackersDemandForIFirst() {
        val i = newIdentity()
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, i))
        initiator.message1()
        // An attacker that answers a forged or replayed advert picks its own one-time key and demands I_FIRST.
        val attacker = SecureBmh3Entropy.ephemeral()
        val demand = Bmh3Wire.MAGIC + byteArrayOf(1, 1, 2, 0) + attacker.publicBytes + SecureBmh3Entropy.nonce()
        assertEquals(Bmh3Wire.MSG2_CLEAR_BYTES, demand.size)
        assertEquals("I_FIRST refused", assertThrows(Bmh3Failure::class.java) { initiator.onMessage2(demand) }.reason)
        assertEquals("nothing was signed or sent", 0, i.signed.get())
    }

    // -------------------------------------------------------------------------- checks before revealing

    @Test
    fun aFailingCheckStopsTheInitiatorBeforeItSignsOrRevealsAnything() {
        val i = newIdentity()
        val r = newIdentity()
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, r))
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, i), checkResponder = { throw IllegalArgumentException("binding") })
        val m2 = responder.onMessage1(initiator.message1())
        assertThrows(IllegalArgumentException::class.java) { initiator.onMessage2(m2) }
        assertEquals(0, i.signed.get())
        assertEquals(1, r.signed.get())
        assertFalse(initiator.isComplete)
    }

    @Test
    fun theResponderRefusalOfAnInitiatorEndsWithoutSessionKeys() {
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), admitInitiator = { _, _ -> false })
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity()))
        val m3 = initiator.onMessage2(responder.onMessage1(initiator.message1()))
        assertEquals("initiator refused", assertThrows(Bmh3Failure::class.java) { responder.onMessage3(m3) }.reason)
        assertFalse(responder.isComplete)
    }

    // --------------------------------------------------------------------------- tampering, replay, reflection

    private fun survives(i: SigningIdentity, r: SigningIdentity, index: Int, position: Int, purpose: Bmh3Purpose, contactsOnly: Boolean): Boolean {
        val tagKey = Bmh3Crypto.contactTagKey(ByteArray(32) { 7 })
        return try {
            run(
                Bmh3Initiator(config(purpose, i), mayRevealFirst = contactsOnly),
                Bmh3Responder(
                    config(purpose, r),
                    mode = if (contactsOnly) Bmh3ResponderMode.CONTACTS_ONLY else Bmh3ResponderMode.EVERYONE,
                    tagChecks = listOf(tag(tagKey)),
                ),
            ) { n, message ->
                if (n == index) message.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() } else message
            }
            true
        } catch (error: Bmh3Failure) {
            false
        }
    }

    @Test
    fun noSingleBitChangeInAnyMessageOfEitherOrderGoesUnnoticed() {
        val i = newIdentity()
        val r = newIdentity()
        for (contactsOnly in listOf(false, true)) {
            val sizes = if (contactsOnly) listOf(122, 105, 334, 334) else listOf(122, 431, 334)
            for ((index, size) in sizes.withIndex()) {
                for (position in 0 until size) {
                    assertFalse(
                        "order=${if (contactsOnly) "I_FIRST" else "R_FIRST"} message ${index + 1} byte $position",
                        survives(i, r, index, position, Bmh3Purpose.LINK, contactsOnly),
                    )
                }
            }
        }
    }

    @Test
    fun aMessageOfAnotherPurposeOrNumberIsRejectedAtTheHeader() {
        val link = Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity())).message1()
        val endpoint = Bmh3Responder(config(Bmh3Purpose.END_TO_END, newIdentity()))
        assertEquals("purpose", assertThrows(Bmh3Failure::class.java) { endpoint.onMessage1(link) }.reason)
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()))
        assertEquals("magic", assertThrows(Bmh3Failure::class.java) { responder.onMessage1(ByteArray(122)) }.reason)
        val short = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()))
        assertEquals("msg1 length", assertThrows(Bmh3Failure::class.java) { short.onMessage1(link.copyOf(121)) }.reason)
    }

    @Test
    fun aReplayedMessage1CostsTheResponderOnlyAHash() {
        val cache = Bmh3ReplayCache()
        val i = newIdentity()
        val first = Bmh3Initiator(config(Bmh3Purpose.LINK, i)).message1()
        val a = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), replayCache = cache)
        a.onMessage1(first)
        val r2 = newIdentity()
        val b = Bmh3Responder(config(Bmh3Purpose.LINK, r2), replayCache = cache)
        assertEquals("replay", assertThrows(Bmh3Failure::class.java) { b.onMessage1(first) }.reason)
        assertEquals("no signature was made for the replay", 0, r2.signed.get())
        // A fresh msg1 from the same phone is a new handshake.
        Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), replayCache = cache)
            .onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, i)).message1())
    }

    @Test
    fun theReplayCacheForgetsTheOldestEntries() {
        val cache = Bmh3ReplayCache(capacity = 3)
        val keys = (0 until 5).map { ByteArray(65) { _ -> it.toByte() } }
        keys.forEach { assertTrue(cache.firstUse(it)) }
        assertTrue("evicted", cache.firstUse(keys[0]))
        assertFalse("kept", cache.firstUse(keys[4]))
    }

    @Test
    fun aReplayedMessage3IntoAFreshSessionFails() {
        val i = newIdentity()
        val first = run(
            Bmh3Initiator(config(Bmh3Purpose.LINK, i), mayRevealFirst = true),
            Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), mode = Bmh3ResponderMode.CONTACTS_ONLY),
        )
        val m3 = first.messages[2]
        val second = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), mode = Bmh3ResponderMode.CONTACTS_ONLY)
        second.onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, i), mayRevealFirst = true).message1())
        assertEquals("aead", assertThrows(Bmh3Failure::class.java) { second.onMessage3(m3) }.reason)
    }

    @Test
    fun aReflectedBlockFailsEvenWhenResealedUnderTheRightKeys() {
        val i = newIdentity()
        val r = newIdentity()
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, i))
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, r))
        val m2 = responder.onMessage1(initiator.message1())
        initiator.onMessage2(m2)
        // The responder's own msg2 returned to it as msg3 (the AEAD key differs).
        val reflected = Bmh3Wire.MAGIC + byteArrayOf(1, 1, 3, 0) + m2.copyOfRange(Bmh3Wire.MSG2_CLEAR_BYTES, m2.size)
        assertEquals("aead", assertThrows(Bmh3Failure::class.java) { responder.onMessage3(reflected) }.reason)
    }

    @Test
    fun aSelfConnectionIsRejectedOnBothSides() {
        val same = newIdentity()
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, same))
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, same))
        val m2 = responder.onMessage1(initiator.message1())
        assertEquals("self-connection", assertThrows(Bmh3Failure::class.java) { initiator.onMessage2(m2) }.reason)
        assertEquals("only the responder's block was signed", 1, same.signed.get())
    }

    @Test
    fun theResponderNeverReusesItsOneTimeValuesAcrossHandshakes() {
        val seen = HashSet<String>()
        repeat(40) {
            val responder = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()))
            val m2 = responder.onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity())).message1())
            assertTrue(seen.add(m2.copyOfRange(8, 105).joinToString("") { "%02x".format(it) }))
        }
    }

    // ------------------------------------------------------------------------------------------ extensions

    @Test
    fun extensionAreasAreAlways112BytesAndRoundTrip() {
        for (extensions in listOf(Bmh3Extensions.NONE, Bmh3Extensions(opening = openingValue(1)), Bmh3Extensions(opening = openingValue(2)), Bmh3Extensions(intent = 4))) {
            val area = extensions.encodeArea()
            assertEquals(112, area.size)
            val parsed = Bmh3Extensions.parse(area)
            assertEquals(extensions.intent, parsed.intent)
            assertEquals(extensions.opening?.toList(), parsed.opening?.toList())
        }
        val twoEntries = Bmh3Extensions(opening = openingValue(2)).encodeArea()
        assertEquals(0, twoEntries[0].toInt()) // the PAD comes first
    }

    @Test
    fun malformedExtensionAreasAreRejected() {
        fun pad(n: Int) = byteArrayOf(0, (n ushr 8).toByte(), n.toByte()) + ByteArray(n)
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(ByteArray(111)) }
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(ByteArray(113)) }
        // non-zero padding
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(byteArrayOf(0, 0, 109, 1) + ByteArray(108)) }
        // unknown critical type
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(pad(106) + byteArrayOf(0x85.toByte(), 0, 0)) }
        // duplicate type
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(pad(101) + byteArrayOf(2, 0, 1, 1, 2, 0, 1, 1)) }
        // OPENING with the wrong length for its count
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(pad(103) + byteArrayOf(1, 0, 3, 1, 0, 0)) }
        // INTENT with two bytes
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(pad(104) + byteArrayOf(2, 0, 2, 1, 1)) }
        // BUILD_TAG with three bytes
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(pad(103) + byteArrayOf(3, 0, 3, 0, 0, 6)) }
        // a length that runs past the area
        assertThrows(Bmh3Failure::class.java) { Bmh3Extensions.parse(byteArrayOf(0, 0, 200.toByte()) + ByteArray(109)) }
        // an unknown non-critical type is skipped
        val parsed = Bmh3Extensions.parse(pad(106) + byteArrayOf(7, 0, 0))
        assertNull(parsed.intent)
        assertNull(parsed.opening)
        assertNull(parsed.buildTag)
        // a build tag with release 0 is no tag (and no failure), but it is still "seen" for the placement rule
        val noTag = Bmh3Extensions.parse(pad(104) + byteArrayOf(3, 0, 2, 0, 0))
        assertNull(noTag.buildTag)
        assertTrue(noTag.buildTagSeen)
        // everything the app can send fits together: two opening entries, an intent and a build tag
        val full = Bmh3Extensions(opening = openingValue(2), intent = 1, buildTag = BuildTag(7, true))
        assertEquals(112, full.encodeArea().size)
        val back = Bmh3Extensions.parse(full.encodeArea())
        assertEquals(BuildTag(7, true), back.buildTag)
        assertEquals(1, back.intent)
    }

    @Test
    fun knownExtensionsInTheWrongPlaceFailTheHandshake() {
        val i = newIdentity()
        val r = newIdentity()
        // INTENT in the msg3 of an R_FIRST handshake.
        val intentInR = assertThrows(Bmh3Failure::class.java) {
            run(
                Bmh3Initiator(config(Bmh3Purpose.LINK, i), testMsg3Extensions = Bmh3Extensions(intent = 1)),
                Bmh3Responder(config(Bmh3Purpose.LINK, r)),
            )
        }
        assertEquals("intent outside msg3 of an I_FIRST handshake", intentInR.reason)
        // OPENING in an initiator block.
        val openingInInitiator = assertThrows(Bmh3Failure::class.java) {
            run(
                Bmh3Initiator(config(Bmh3Purpose.LINK, i), testMsg3Extensions = Bmh3Extensions(opening = openingValue(1))),
                Bmh3Responder(config(Bmh3Purpose.LINK, r)),
            )
        }
        assertEquals("opening outside a responder block", openingInInitiator.reason)
        // INTENT in an I_FIRST msg3 is fine.
        run(
            Bmh3Initiator(config(Bmh3Purpose.LINK, i), mayRevealFirst = true, intent = 3),
            Bmh3Responder(config(Bmh3Purpose.LINK, r), mode = Bmh3ResponderMode.CONTACTS_ONLY),
        ).also { assertEquals(Bmh3Order.I_FIRST, it.responder.result().order) }
    }

    @Test
    fun theWifiMarkerRoundTripsAndFitsWithEverythingElse() {
        val marked = Bmh3Extensions(wifi = true).encodeArea()
        assertEquals(Bmh3Wire.EXTENSION_BYTES, marked.size)
        assertTrue(Bmh3Extensions.parse(marked).wifi)
        assertArrayEquals(Bmh3Extensions.NONE.encodeArea(), Bmh3Extensions().encodeArea())
        assertArrayEquals(Bmh3Extensions.NONE.encodeArea(), Bmh3Extensions(wifi = false).encodeArea())
        // The fullest initiator area: intent, build tag, pseudonym marker and the Wi-Fi marker (16 bytes of TLVs).
        val initiator = Bmh3Extensions(intent = 1, buildTag = BuildTag(7, true), pseudonym = true, wifi = true)
        val initiatorArea = initiator.encodeArea()
        assertEquals(Bmh3Wire.EXTENSION_BYTES, initiatorArea.size)
        val initiatorBack = Bmh3Extensions.parse(initiatorArea)
        assertEquals(1, initiatorBack.intent)
        assertEquals(BuildTag(7, true), initiatorBack.buildTag)
        assertTrue(initiatorBack.pseudonym)
        assertTrue(initiatorBack.wifi)
        // The fullest responder area: two opening entries, a build tag and relay seek (99 bytes of TLVs).
        val responder = Bmh3Extensions(opening = openingValue(2), buildTag = BuildTag(7, true), relaySeek = 1)
        val responderArea = responder.encodeArea()
        assertEquals(Bmh3Wire.EXTENSION_BYTES, responderArea.size)
        val responderBack = Bmh3Extensions.parse(responderArea)
        assertArrayEquals(openingValue(2), responderBack.opening)
        assertEquals(BuildTag(7, true), responderBack.buildTag)
        assertEquals(1, responderBack.relaySeek)
        assertFalse(responderBack.wifi)
        // Every extension at once needs 110 bytes, leaving 2: below the 3-byte PAD header, so it cannot be carried.
        val everything = Bmh3Extensions(
            opening = openingValue(2), intent = 1, buildTag = BuildTag(7, true), pseudonym = true,
            relaySeek = 1, wifi = true,
        )
        val failure = assertThrows(IllegalArgumentException::class.java) { everything.encodeArea() }
        assertEquals("Extensions do not fit the 112-byte area", failure.message)
    }

    @Test
    fun theWifiMarkerIsStrict() {
        fun area(vararg tail: Int): ByteArray {
            val pad = Bmh3Wire.EXTENSION_BYTES - 3 - tail.size
            return byteArrayOf(0, (pad ushr 8).toByte(), pad.toByte()) + ByteArray(pad) + ByteArray(tail.size) { tail[it].toByte() }
        }
        assertEquals("wifi marker malformed", assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_WIFI, 0, 0))
        }.reason)
        assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_WIFI, 0, 2, 1, 1))
        }
        // Another version is a peer without the marker, not a failure; it is still seen by the placement rule.
        val otherVersion = Bmh3Extensions.parse(area(Bmh3Extensions.TYPE_WIFI, 0, 1, 2))
        assertFalse(otherVersion.wifi)
        assertTrue(otherVersion.wifiSeen)
        // Types must ascend: the marker (8) before RELAY_SEEK (5) is rejected.
        assertEquals("extensions not ascending", assertThrows(Bmh3Failure::class.java) {
            Bmh3Extensions.parse(
                area(
                    Bmh3Extensions.TYPE_WIFI, 0, 1, Bmh3Extensions.WIFI_VERSION,
                    Bmh3Extensions.TYPE_RELAY_SEEK, 0, 1, 1,
                ),
            )
        }.reason)
    }

    @Test
    fun theWifiMarkerIsAcceptedOnlyInAnInitiatorLinkBlock() {
        val i = newIdentity()
        val r = newIdentity()
        // An initiator signs it; the responder reads it and never sends one back.
        val marked = run(
            Bmh3Initiator(Bmh3Config(Bmh3Purpose.LINK, i, caps, wifiMarker = true)),
            Bmh3Responder(config(Bmh3Purpose.LINK, r, responderCaps)),
        )
        assertTrue(marked.responder.result().peer.wifiMarker)
        assertFalse(marked.initiator.result().peer.wifiMarker)
        val plain = run(Bmh3Initiator(config(Bmh3Purpose.LINK, i)), Bmh3Responder(config(Bmh3Purpose.LINK, r, responderCaps)))
        assertFalse(plain.responder.result().peer.wifiMarker)
        assertFalse(plain.initiator.result().peer.wifiMarker)
        // A responder block never carries it.
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, r))
        responder.testWifiMarkerOverride = true
        val responderFailure = assertThrows(Bmh3Failure::class.java) {
            run(Bmh3Initiator(config(Bmh3Purpose.LINK, i)), responder)
        }
        assertEquals("wifi marker outside an initiator link block", responderFailure.reason)
        // The end-to-end purpose never carries it, even when asked for.
        assertFalse(Bmh3Config(Bmh3Purpose.END_TO_END, i, caps, wifiMarker = true).wifiMarker)
        val endToEndInitiator = Bmh3Initiator(config(Bmh3Purpose.END_TO_END, i))
        endToEndInitiator.testWifiMarkerOverride = true
        val endToEndFailure = assertThrows(Bmh3Failure::class.java) {
            run(endToEndInitiator, Bmh3Responder(config(Bmh3Purpose.END_TO_END, r)))
        }
        assertEquals("wifi marker outside an initiator link block", endToEndFailure.reason)
    }

    // ----------------------------------------------------------------------- signature encoding and the curve

    @Test
    fun derAndRawSignaturesConvertBothWays() {
        val identity = KeyPairSigningIdentity(generateEcKeyPair())
        repeat(30) {
            val der = identity.sign(byteArrayOf(it.toByte(), 1, 2, 3))
            val raw = Bmh3Crypto.derToRaw(der)
            assertEquals(64, raw.size)
            val back = Bmh3Crypto.rawToDer(raw)
            assertTrue(verifyEcdsa(decodeEcPublicKey(identity.publicKeyEncoded), byteArrayOf(it.toByte(), 1, 2, 3), back))
        }
        assertThrows(Bmh3Failure::class.java) { Bmh3Crypto.rawToDer(ByteArray(64)) } // r = s = 0
        assertThrows(Bmh3Failure::class.java) { Bmh3Crypto.rawToDer(ByteArray(63)) }
        assertThrows(Bmh3Failure::class.java) { Bmh3Crypto.derToRaw(byteArrayOf(0x30, 0x02, 0x02, 0x00)) }
    }

    @Test
    fun theCurveCheckRejectsOffCurveAndOutOfRangeCoordinates() {
        val good = SecureBmh3Entropy.ephemeral().publicBytes
        assertTrue(Bmh3Crypto.isOnCurve(good))
        assertFalse(Bmh3Crypto.isOnCurve(good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }))
        assertFalse(Bmh3Crypto.isOnCurve(good.copyOf().also { it[0] = 2 }))
        assertFalse(Bmh3Crypto.isOnCurve(good.copyOf(64)))
        val p = byteArrayOf(
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        )
        assertFalse("x = p", Bmh3Crypto.isOnCurve(byteArrayOf(4) + p + good.copyOfRange(33, 65)))
        assertFalse(Bmh3Crypto.isCanonicalSpki(ByteArray(91)))
        assertTrue(Bmh3Crypto.isCanonicalSpki(newIdentity().publicKeyEncoded))
    }

    // ------------------------------------------------------------------------------- negotiation and hints

    private fun callOffer(summary: Int = 3, version: Int = 2, routeCost: Int = 0, flags: Int = 0x41) = DiscoveryAnnouncement(
        nodeIdPrefix = byteArrayOf(1, 2, 3, 4, 5, 6),
        flags = flags,
        capabilitySummary = summary,
        streamToken = 0,
        routeCost = routeCost,
        sequence = 0,
        version = version,
    )


    // ---------------------------------------------------------------------------- discovery binding checks

    @Test
    fun theDiscoveryBindingRunsBeforeTheCallerRevealsItself() {
        val r = newIdentity()
        val i = newIdentity()
        val prefix = r.nodeId.toByteArray().copyOf(6)
        val matching = callOffer().copy(nodeIdPrefix = prefix)
        val other = callOffer().copy(nodeIdPrefix = byteArrayOf(9, 9, 9, 9, 9, 9))
        fun attempt(selected: DiscoveryAnnouncement, layer: Bmh3Policy.Layer): Run {
            val options = Bmh3Policy.initiatorOptions(selected, layer, log = {})
            return run(
                Bmh3Initiator(config(Bmh3Purpose.LINK, i), checkResponder = options.checkResponder),
                Bmh3Responder(config(Bmh3Purpose.LINK, r)),
            )
        }
        attempt(matching, Bmh3Policy.Layer.LINK)
        val signedBefore = i.signed.get()
        assertThrows(IllegalArgumentException::class.java) { attempt(other, Bmh3Policy.Layer.LINK) }
        assertEquals("the caller never signed for the wrong answerer", signedBefore, i.signed.get())
        // A relay (route cost above 0) is not the callee: the link layer skips the prefix check, the endpoint does not.
        attempt(other.copy(routeCost = 1), Bmh3Policy.Layer.LINK)
        assertThrows(IllegalArgumentException::class.java) { attempt(other.copy(routeCost = 1), Bmh3Policy.Layer.ENDPOINT) }
    }

    @Test
    fun aRotatingOfferNeedsTheOpeningInTheCalleesBlock() {
        val r = newIdentity()
        val i = newIdentity()
        val epoch = 1_990_656L
        val lease = ByteArray(32) { (it + 1).toByte() }
        val entry = RotatingDiscoveryIds.entry(lease, r.nodeId.toByteArray(), epoch, RotatingDiscoveryIds.ROLE_CALL_OFFER)
        val rdi = entry.rdi
        val selected = callOffer(version = 5).copy(nodeIdPrefix = rdi)
        val savedClock = DiscoverySelectionCheck.unixSeconds
        DiscoverySelectionCheck.unixSeconds = { epoch * RotatingDiscoveryIds.EPOCH_SECONDS }
        try {
            fun attempt(opening: List<OpeningEntry>): Run {
                val options = Bmh3Policy.initiatorOptions(selected, Bmh3Policy.Layer.ENDPOINT, log = {})
                return run(
                    Bmh3Initiator(config(Bmh3Purpose.END_TO_END, i), checkResponder = options.checkResponder),
                    Bmh3Responder(config(Bmh3Purpose.END_TO_END, r), opening = Bmh3Policy.openingValue(opening)),
                )
            }
            attempt(listOf(entry))
            assertThrows(IllegalArgumentException::class.java) { attempt(emptyList()) }
            assertThrows(IllegalArgumentException::class.java) {
                attempt(listOf(OpeningEntry(rdi, epoch, RotatingDiscoveryIds.ROLE_CALL_OFFER, ByteArray(32) { 5 })))
            }
        } finally {
            DiscoverySelectionCheck.unixSeconds = savedClock
        }
    }

    // ------------------------------------------------------------------------------ link and endpoint drivers

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

    private class Pair<T>(val initiator: T, val responder: T)

    private fun <T> both(initiator: () -> T, responder: () -> T, onResponderFailure: () -> Unit = {}, onInitiatorFailure: () -> Unit = {}): Pair<Result<T>> {
        val responderResult = AtomicReference<Result<T>>()
        val worker = thread(name = "responder", isDaemon = true) {
            responderResult.set(runCatching(responder).onFailure { onResponderFailure() })
        }
        // A failed initiator closes its outgoing pipe, so the responder fails at once instead of waiting out the read deadline.
        val initiatorResult = runCatching(initiator).onFailure { onInitiatorFailure() }
        worker.join(120_000)
        return Pair(initiatorResult, responderResult.get() ?: Result.failure(IllegalStateException("responder timed out")))
    }

    @Test
    fun linkHandshakeProducesWorkingSessionsAndNegotiatesByMagic() {
        val i = newIdentity()
        val r = newIdentity()
        val pipes = Duplex()
        val log = mutableListOf<String>()
        val out = both(
            {
                SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, i, caps, bmh3 = Bmh3InitiatorOptions(log = { synchronized(log) { log += it } }))
            },
            {
                SecureBluetoothHandshake.respond(
                    pipes.aToB.input, pipes.bToA.output, r, responderCaps,
                    bmh3 = Bmh3ResponderOptions(log = { synchronized(log) { log += it } }),
                )
            },
        )
        val ini = out.initiator.getOrThrow()
        val res = out.responder.getOrThrow()
        assertEquals(r.nodeId, ini.peerNodeId)
        assertEquals(i.nodeId, res.peerNodeId)
        assertEquals(responderCaps, ini.peerCapabilities)
        ini.writer.write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
        ini.writer.flush()
        res.writer.write(SecureRecordType.LINK_HEARTBEAT, byteArrayOf(1))
        res.writer.flush()
        assertEquals(SecureRecordType.LINK_HEARTBEAT, res.reader.read().type)
        assertEquals(SecureRecordType.LINK_HEARTBEAT, ini.reader.read().type)
        assertEquals(2, log.size)
        assertTrue(log.all { it.startsWith("bmh3: event=link") && "result=ok" in it })
        assertTrue("no identifiers in the log", log.none { it.contains(i.nodeId.toString().take(8)) || it.contains(r.nodeId.toString().take(8)) })
    }

    @Test
    fun aHelperSignsRelaySeekInItsLinkResponderBlockOnlyAndTheInitiatorReadsIt() {
        // Design OR (DEBUG prototype): RELAY_SEEK (type 5) rides in the responder's signed LINK block; none by default.
        for (bits in listOf<Int?>(null, RelaySeek.FEATURE_SEEK_V1)) {
            val pipes = Duplex()
            val out = both(
                { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, newIdentity(), caps, bmh3 = Bmh3InitiatorOptions()) },
                {
                    SecureBluetoothHandshake.respond(
                        pipes.aToB.input, pipes.bToA.output, newIdentity(), responderCaps,
                        bmh3 = Bmh3ResponderOptions().also { options -> options.relaySeek = { bits } },
                    )
                },
            )
            assertEquals(bits ?: 0, out.initiator.getOrThrow().peerRelaySeek)
            assertEquals("an initiator never signs it", 0, out.responder.getOrThrow().peerRelaySeek)
        }
        // Never in an end-to-end block, even when asked for.
        val endToEnd = Bmh3Config(Bmh3Purpose.END_TO_END, newIdentity(), caps, relaySeek = 1)
        assertEquals(null, endToEnd.relaySeek)
    }

    @Test
    fun aMarkedInitiatorTellsItsResponderAndOnlyThatOneAndTheSessionsSayWhoMayReadBwf1() {
        val pipes = Duplex()
        val out = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, newIdentity(), caps, bmh3 = Bmh3InitiatorOptions(wifiMarker = true)) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, newIdentity(), responderCaps, bmh3 = Bmh3ResponderOptions()) },
        )
        val ini = out.initiator.getOrThrow()
        val res = out.responder.getOrThrow()
        assertTrue(ini.ownWifiMarker)
        assertFalse(ini.peerWifiMarker)
        assertTrue(ini.wifiReadOptIn)
        assertFalse(res.ownWifiMarker)
        assertTrue(res.peerWifiMarker)
        assertTrue(res.wifiReadOptIn)
        // Default options: no marker, no opt-in on either side.
        val quiet = Duplex()
        val quietOut = both(
            { SecureBluetoothHandshake.initiate(quiet.bToA.input, quiet.aToB.output, newIdentity(), caps, bmh3 = Bmh3InitiatorOptions()) },
            { SecureBluetoothHandshake.respond(quiet.aToB.input, quiet.bToA.output, newIdentity(), responderCaps, bmh3 = Bmh3ResponderOptions()) },
        )
        for (session in listOf(quietOut.initiator.getOrThrow(), quietOut.responder.getOrThrow())) {
            assertFalse(session.ownWifiMarker)
            assertFalse(session.peerWifiMarker)
            assertFalse(session.wifiReadOptIn)
        }
        // The owner's opt-in makes the real records flow in both directions.
        ini.reader.acceptWifi = ini.wifiReadOptIn
        res.reader.acceptWifi = res.wifiReadOptIn
        res.writer.write(SecureRecordType.BWF1, byteArrayOf(9, 8))
        res.writer.flush()
        val record = ini.reader.read()
        assertEquals(SecureRecordType.BWF1, record.type)
        assertArrayEquals(byteArrayOf(9, 8), record.payload)
    }

    @Test
    fun anOldStyleResponderIgnoresTheMarker() {
        val pipes = Duplex()
        val out = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, newIdentity(), caps, bmh3 = Bmh3InitiatorOptions(wifiMarker = true)) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, newIdentity(), responderCaps, bmh3 = Bmh3ResponderOptions()) },
        )
        val ini = out.initiator.getOrThrow()
        val old = out.responder.getOrThrow()
        // The old build never opts in: the non-critical extension is skipped and the handshake completes.
        old.reader.acceptWifi = false
        ini.writer.write(SecureRecordType.BWF1, byteArrayOf(1))
        ini.writer.flush()
        val failure = assertThrows(IllegalStateException::class.java) { old.reader.read() }
        assertEquals("Unknown secure record type 16", failure.message)
    }

    @Test
    fun theMarkerChangesNoOtherHandshakeByte() {
        // No fixed-entropy helper exists in this class: the sizes (equal for every identity) are the available proof
        // that the marker changes no other handshake byte than its own encrypted extension.
        val sizes = listOf(false, true).map { marker ->
            run(
                Bmh3Initiator(Bmh3Config(Bmh3Purpose.LINK, newIdentity(), caps, wifiMarker = marker)),
                Bmh3Responder(Bmh3Config(Bmh3Purpose.LINK, newIdentity(), responderCaps)),
            ).messages.map { it.size }
        }
        assertEquals(sizes[0], sizes[1])
        assertEquals(listOf(Bmh3Wire.MSG1_BYTES, Bmh3Wire.MSG2_R_FIRST_BYTES, Bmh3Wire.MSG3_BYTES), sizes[0])
        assertEquals(Bmh3Wire.MSG3_BYTES, sizes[1][2])
    }

    @Test
    fun aBmh3InitiatorFailsClosedAgainstAnOldResponderAndNeverFallsBack() {
        val pipes = Duplex()
        val written = ByteArrayOutputStream()
        val tee = object : OutputStream() {
            override fun write(b: Int) {
                written.write(b)
                pipes.aToB.output.write(b)
            }
        }
        val out = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, tee, newIdentity(), caps, bmh3 = Bmh3InitiatorOptions()) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, newIdentity(), responderCaps) },
            onResponderFailure = { pipes.bToA.close() },
            onInitiatorFailure = { pipes.aToB.close() },
        )
        assertTrue(out.responder.isFailure)
        assertEquals("Invalid handshake message", out.responder.exceptionOrNull()?.message)
        assertTrue(out.initiator.isFailure)
        // The initiator sent exactly one frame, msg1; no BMH2 hello followed.
        assertEquals(4 + Bmh3Wire.MSG1_BYTES, written.size())
    }

    @Test
    fun aBmh2InitiatorStillWorksAgainstABmh3ResponderUnlessItRefusesBmh2() {
        val i = newIdentity()
        val r = newIdentity()
        for (accept in listOf(true, false)) {
            val pipes = Duplex()
            val out = both(
                { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, i, caps) },
                {
                    SecureBluetoothHandshake.respond(
                        pipes.aToB.input, pipes.bToA.output, r, responderCaps,
                        bmh3 = Bmh3ResponderOptions(acceptBmh2 = accept),
                    )
                },
                onResponderFailure = { pipes.bToA.close() },
                onInitiatorFailure = { pipes.aToB.close() },
            )
            if (accept) {
                assertEquals(r.nodeId, out.initiator.getOrThrow().peerNodeId)
                assertEquals(i.nodeId, out.responder.getOrThrow().peerNodeId)
            } else {
                assertEquals("bmh2 refused", (out.responder.exceptionOrNull() as Bmh3Failure).reason)
                assertTrue(out.initiator.isFailure)
            }
        }
    }

    @Test
    fun theEndpointHandshakeRunsInsideALinkSessionAndChecksTheVerifiedContactStoreBeforeRevealing() {
        val i = newIdentity()
        val r = newIdentity()
        val linkPipes = Duplex()
        val links = both(
            { SecureBluetoothHandshake.initiate(linkPipes.bToA.input, linkPipes.aToB.output, i, caps) },
            { SecureBluetoothHandshake.respond(linkPipes.aToB.input, linkPipes.bToA.output, r, responderCaps) },
        )
        val linkI = links.initiator.getOrThrow()
        val linkR = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(linkI, i, caps, bmh3 = Bmh3InitiatorOptions()) },
            { EndToEndVideoHandshake.respond(linkR, r, responderCaps, bmh3 = Bmh3ResponderOptions()) },
        )
        val initiator = ends.initiator.getOrThrow()
        val responder = ends.responder.getOrThrow()
        assertEquals(r.nodeId, initiator.peerNodeId)
        assertEquals(i.nodeId, responder.peerNodeId)
        assertEquals(IdentityFingerprint.fromPublicKey(r.publicKeyEncoded), initiator.peerFingerprint)
        assertEquals(IdentityFingerprint.fromPublicKey(i.publicKeyEncoded), responder.peerFingerprint)
        // The content keys work end to end.
        val sealed = initiator.sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), responder.opener.open(sealed).payload)
        val back = responder.sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), initiator.opener.open(back).payload)
    }

    @Test
    fun anEndpointBmh3InitiatorFailsClosedAgainstAnOldEndpointResponder() {
        val i = newIdentity()
        val r = newIdentity()
        val linkPipes = Duplex()
        val links = both(
            { SecureBluetoothHandshake.initiate(linkPipes.bToA.input, linkPipes.aToB.output, i, caps) },
            { SecureBluetoothHandshake.respond(linkPipes.aToB.input, linkPipes.bToA.output, r, responderCaps) },
        )
        val linkI = links.initiator.getOrThrow()
        val linkR = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(linkI, i, caps, bmh3 = Bmh3InitiatorOptions()) },
            { EndToEndVideoHandshake.respond(linkR, r, responderCaps) },
            onResponderFailure = { linkPipes.bToA.close() },
            onInitiatorFailure = { linkPipes.aToB.close() },
        )
        assertEquals("Invalid handshake message", ends.responder.exceptionOrNull()?.message)
        assertTrue(ends.initiator.isFailure)
    }

    @Test
    fun threeAndFourMessageEndpointHandshakesPassAnOpaqueRelay() {
        // caller <-> relay <-> callee, each hop its own BMH3 link; the relay copies type-4 and type-5 records.
        val caller = newIdentity()
        val relay = newIdentity()
        val callee = newIdentity()
        for (contactsOnly in listOf(false, true)) {
            val callerRelay = Duplex()
            val relayCallee = Duplex()
            val hop1 = both(
                { Bmh3LinkHandshake.initiate(callerRelay.bToA.input, callerRelay.aToB.output, caller, caps, Bmh3InitiatorOptions()) },
                { Bmh3LinkHandshake.respond(callerRelay.aToB.input, callerRelay.bToA.output, readFirst(callerRelay.aToB.input), relay, responderCaps, Bmh3ResponderOptions()) },
            )
            val hop2 = both(
                { Bmh3LinkHandshake.initiate(relayCallee.bToA.input, relayCallee.aToB.output, relay, caps, Bmh3InitiatorOptions()) },
                { Bmh3LinkHandshake.respond(relayCallee.aToB.input, relayCallee.bToA.output, readFirst(relayCallee.aToB.input), callee, responderCaps, Bmh3ResponderOptions()) },
            )
            val callerLink = hop1.initiator.getOrThrow()
            val relayUp = hop1.responder.getOrThrow()
            val relayDown = hop2.initiator.getOrThrow()
            val calleeLink = hop2.responder.getOrThrow()
            val copiers = listOf(
                thread(isDaemon = true) { copyRecords(relayUp, relayDown) },
                thread(isDaemon = true) { copyRecords(relayDown, relayUp) },
            )
            val tagKey = Bmh3Crypto.contactTagKey(ByteArray(32) { 4 })
            val ends = both(
                {
                    EndToEndVideoHandshake.initiate(
                        callerLink, caller, caps,
                        bmh3 = Bmh3InitiatorOptions(contactTag = if (contactsOnly) tag(tagKey) else null),
                    )
                },
                {
                    EndToEndVideoHandshake.respond(
                        calleeLink, callee, responderCaps,
                        bmh3 = Bmh3ResponderOptions(
                            mode = if (contactsOnly) Bmh3ResponderMode.CONTACTS_ONLY else Bmh3ResponderMode.EVERYONE,
                            tagChecks = listOf(tag(tagKey)),
                        ),
                    )
                },
            )
            val callerEnd = ends.initiator.getOrThrow()
            val calleeEnd = ends.responder.getOrThrow()
            assertEquals(callee.nodeId, callerEnd.peerNodeId)
            assertEquals(caller.nodeId, calleeEnd.peerNodeId)
            // The relay's links name only its neighbours.
            assertEquals(caller.nodeId, relayUp.peerNodeId)
            assertEquals(callee.nodeId, relayDown.peerNodeId)
            val sealed = callerEnd.sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, byteArrayOf(9))
            callerLink.writer.write(SecureRecordType.END_TO_END_CONTENT, sealed)
            callerLink.writer.flush()
            val delivered = calleeLink.reader.read()
            assertEquals(SecureRecordType.END_TO_END_CONTENT, delivered.type)
            assertArrayEquals(byteArrayOf(9), calleeEnd.opener.open(delivered.payload).payload)
            callerRelay.aToB.close(); callerRelay.bToA.close(); relayCallee.aToB.close(); relayCallee.bToA.close()
            copiers.forEach { it.join(2_000) }
        }
    }

    /** Reads the first frame of a link, as the dispatching responder does before it picks the handshake. */
    private fun readFirst(input: InputStream): ByteArray = Bmh3LinkChannel.receiveFrame(java.io.DataInputStream(input))

    private fun copyRecords(from: SecureBluetoothSession, to: SecureBluetoothSession) {
        try {
            while (true) {
                val record = from.reader.read()
                if (record.type != SecureRecordType.END_TO_END_HANDSHAKE && record.type != SecureRecordType.END_TO_END_CONTENT) continue
                to.writer.write(record.type, record.payload)
                to.writer.flush()
            }
        } catch (error: Exception) {
            // closed
        }
    }

    // ----------------------------------------------------------------------------------- options and logs

    @Test
    fun theHintBitIsSetOnlyOnVersion2CallOffersAtRouteCostZeroWithTheSwitchOn() {
        val base = callOffer()
        assertSame(base, Bmh3Hint.offerFor(base, enabled = false))
        val hinted = Bmh3Hint.offerFor(base, enabled = true)
        assertEquals(0x23, hinted.capabilitySummary)
        assertTrue(Bmh3Hint.hasHint(hinted))
        assertFalse(Bmh3Hint.hasHint(base))
        assertEquals(0x02, hinted.version)
        val relayed = callOffer(routeCost = 1)
        assertSame(relayed, Bmh3Hint.offerFor(relayed, enabled = true))
        val helper = callOffer(flags = 0x41 or DiscoveryAnnouncement.FLAG_RELAY_CAPABLE)
        assertSame(helper, Bmh3Hint.offerFor(helper, enabled = true))
        // A stream offer never carries nor reads it.
        val stream = callOffer(flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS)
        assertSame(stream, Bmh3Hint.offerFor(stream, enabled = true))
        assertFalse(Bmh3Hint.hasHint(stream.copy(capabilitySummary = 0x20)))
        // A rotating (version 5) offer carries none: BMH3 initiators always speak BMH3 to it.
        val rotating = callOffer(version = 5)
        assertSame(rotating, Bmh3Hint.offerFor(rotating, enabled = true))
        assertFalse(Bmh3Hint.hasHint(rotating.copy(capabilitySummary = 0x23)))
        assertEquals(hinted.capabilitySummary, DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(hinted)).capabilitySummary)
    }

    private fun assertSame(expected: Any?, actual: Any?) = org.junit.Assert.assertSame(expected, actual)

    @Test
    fun theInitiatorChoosesBmh3ForRotatingOffersAndForVersion2OnlyWithAHintAPinOrTheOnlyOption() {
        val plain = callOffer()
        val hinted = callOffer(summary = 0x23)
        val rotating = callOffer(version = 5)
        assertEquals(Bmh3Choice.Reason.OFF, Bmh3Choice.decide(false, true, hinted, true).reason)
        assertFalse(Bmh3Choice.decide(false, true, rotating, true).bmh3)
        assertEquals(2, Bmh3Choice.decide(false, false, hinted, false).version)
        // A fake or replayed rotating advert cannot talk the caller into BMH2 by leaving the hint out.
        assertEquals(Bmh3Choice.Reason.ROTATING, Bmh3Choice.decide(true, false, rotating, false).reason)
        assertTrue(Bmh3Choice.decide(true, false, rotating, false).bmh3)
        assertEquals(Bmh3Choice.Reason.ROTATING, Bmh3Choice.decide(true, true, rotating, false).reason)
        assertEquals(Bmh3Choice.Reason.ONLY, Bmh3Choice.decide(true, true, plain, false).reason)
        assertEquals(Bmh3Choice.Reason.HINT, Bmh3Choice.decide(true, false, hinted, false).reason)
        assertEquals(Bmh3Choice.Reason.PIN, Bmh3Choice.decide(true, false, plain, true).reason)
        assertEquals(Bmh3Choice.Reason.NONE, Bmh3Choice.decide(true, false, plain, false).reason)
        assertFalse(Bmh3Choice.decide(true, false, null, false).bmh3)
        assertEquals(3, Bmh3Choice.decide(true, false, hinted, false).version)
    }

    private class MemoryPinStore : Bmh3Pins.Storage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    @Test
    fun pinsRememberPrefixesNewestLastExpireAndSurviveAReload() {
        val store = MemoryPinStore()
        var now = 1_000_000_000_000L
        val pins = Bmh3Pins(store, capacity = 3, ttlMs = 10_000L, clockMs = { now })
        assertFalse(pins.isPinned(byteArrayOf(1, 2, 3, 4, 5, 6)))
        (1..4).forEach { pins.pin(byteArrayOf(it.toByte(), 2, 3, 4, 5, 6, 7, 8)) }
        assertFalse("oldest dropped", pins.isPinned(byteArrayOf(1, 2, 3, 4, 5, 6)))
        assertTrue(pins.isPinned(byteArrayOf(4, 2, 3, 4, 5, 6)))
        pins.pin(byteArrayOf(2, 2, 3, 4, 5, 6)) // moved to newest
        pins.pin(byteArrayOf(9, 2, 3, 4, 5, 6))
        assertTrue(pins.isPinned(byteArrayOf(2, 2, 3, 4, 5, 6)))
        assertFalse(pins.isPinned(byteArrayOf(3, 2, 3, 4, 5, 6)))
        val reloaded = Bmh3Pins(store, capacity = 3, ttlMs = 10_000L, clockMs = { now })
        assertTrue(reloaded.isPinned(byteArrayOf(9, 2, 3, 4, 5, 6)))
        now += 10_001L
        assertFalse("a pin expires, so a peer that turned the feature off is reachable again", reloaded.isPinned(byteArrayOf(9, 2, 3, 4, 5, 6)))
        reloaded.pin(byteArrayOf(7, 2, 3, 4, 5, 6))
        reloaded.clear()
        assertFalse(Bmh3Pins(store, clockMs = { now }).isPinned(byteArrayOf(7, 2, 3, 4, 5, 6)))
        store.value = "zz 1\n0102030405 1\n0a0b0c0d0e0f ${now}\n0a0b0c0d0e10 ${now + 999_999_999_999L}"
        val garbage = Bmh3Pins(store, clockMs = { now })
        assertTrue(garbage.isPinned(byteArrayOf(10, 11, 12, 13, 14, 15)))
        assertFalse("a pin dated in the future is dropped", garbage.isPinned(byteArrayOf(10, 11, 12, 13, 14, 16)))
    }

    @Test
    fun theSigningBudgetAllowsABurstThenMetersAndClosesWhenFlooded() {
        var now = 0L
        val slept = mutableListOf<Long>()
        // Concurrent callers wait without the clock moving for the others, which is what a flood looks like.
        val budget = Bmh3SigningBudget(capacity = 3, refillMs = 1_000L, maxWaitMs = 2_500L, clockMs = { now }, sleep = { slept += it })
        repeat(3) { budget.spend() }
        assertTrue("a burst of the capacity is free", slept.isEmpty())
        budget.spend()
        assertTrue("the first waiter waits for one token", slept[0] in 1_000L..1_002L)
        budget.spend()
        assertTrue("the second waiter waits for the next", slept[1] in 2_000L..2_002L)
        assertEquals("a third waiter would wait 3 s, past the limit", "budget", assertThrows(Bmh3Failure::class.java) { budget.spend() }.reason)
        assertEquals(2, slept.size)
        // Time heals it (the debt is paid, the bucket refills to its capacity).
        now += 60_000L
        repeat(3) { budget.spend() }
        assertEquals(2, slept.size)
    }

    @Test
    fun theListenerOptionsFollowTheDebugSwitches() {
        Bmh3Feature.configureDebug(only = false, contactsOnly = false, testLeaseHex = null)
        val prefix = ByteArray(6) { (it + 1).toByte() }
        val everyone = Bmh3Policy.responderOptions(emptyList(), prefix, { _, _ -> true }, {})
        assertEquals(Bmh3ResponderMode.EVERYONE, everyone.mode)
        assertTrue("an Everyone responder accepts BMH2: refusing it would protect nobody", everyone.acceptBmh2)
        assertTrue(everyone.tagChecks.isEmpty())
        assertNull(everyone.opening)
        val lease = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        Bmh3Feature.configureDebug(only = true, contactsOnly = true, testLeaseHex = lease)
        try {
            val entry = RotatingDiscoveryIds.entry(ByteArray(32) { 3 }, ByteArray(16) { 5 }, 1_990_656L, 0)
            val contacts = Bmh3Policy.responderOptions(listOf(entry), prefix, { _, _ -> true }, {}, wallEpoch = 1_990_656L)
            assertEquals(Bmh3ResponderMode.CONTACTS_ONLY, contacts.mode)
            assertFalse("a Contacts-only responder never accepts BMH2", contacts.acceptBmh2)
            // One check per advert on air: the RDI, and the fixed prefix at the wall epochs -1, 0 and +1.
            assertEquals(4, contacts.tagChecks.size)
            assertArrayEquals(Bmh3Crypto.advertBinding(entry.rdi, 0, null), contacts.tagChecks[0].binding)
            assertNotNull(contacts.opening)
            assertNotNull(Bmh3ContactTags.initiatorTag(callOffer(), 1_990_656L))
            Bmh3Feature.configureDebug(false, false, "not hex")
            assertNull(Bmh3Feature.testLeaseKey)
            assertNull(Bmh3ContactTags.initiatorTag(callOffer(), 1_990_656L))
        } finally {
            Bmh3Feature.configureDebug(false, false, null)
        }
    }

    @Test
    fun aRecordedTagIsUselessAgainstTheNextAdvert() {
        // BR3: the tag is bound to the advert the initiator selected. The same msg1 replayed in the next epoch,
        // when the responder advertises a new RDI, does not match, so the responder stays at I_FIRST.
        val lease = Bmh3Crypto.contactTagKey(ByteArray(32) { 11 })
        val rdi1 = Bmh3Crypto.advertBinding(byteArrayOf(1, 1, 1, 1, 1, 1), 0, null)
        val rdi2 = Bmh3Crypto.advertBinding(byteArrayOf(2, 2, 2, 2, 2, 2), 0, null)
        val recorded = Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity()), mayRevealFirst = true, contactTag = Bmh3ContactTag(lease, rdi1)).message1()
        val now = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(Bmh3ContactTag(lease, rdi1)))
        now.onMessage1(recorded)
        assertTrue(now.contactTagMatched)
        assertEquals(Bmh3Order.R_FIRST, now.chosenOrder)
        val later = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), mode = Bmh3ResponderMode.CONTACTS_ONLY, tagChecks = listOf(Bmh3ContactTag(lease, rdi2)))
        // The replayer set the reveal-first hint, so a reply is sent; it is the 105-byte I_FIRST msg2 and says nothing.
        assertEquals(Bmh3Wire.MSG2_CLEAR_BYTES, later.onMessage1(recorded).size)
        assertFalse(later.contactTagMatched)
        assertEquals(Bmh3Order.I_FIRST, later.chosenOrder)
        // The binding also separates version 2 epochs and the two purposes.
        assertFalse(Bmh3Crypto.advertBinding(ByteArray(6), 0, 5L).contentEquals(Bmh3Crypto.advertBinding(ByteArray(6), 0, 6L)))
        val body = ByteArray(Bmh3Wire.MSG1_BYTES)
        assertFalse(
            Bmh3Crypto.contactTag(Bmh3Purpose.LINK, lease, body, rdi1).contentEquals(Bmh3Crypto.contactTag(Bmh3Purpose.END_TO_END, lease, body, rdi1)),
        )
    }

    @Test
    fun anAnswererThatIsNotTheRecognisedContactIsRefusedBeforeTheCallerReveals() {
        val i = newIdentity()
        val r = newIdentity()
        val stranger = newIdentity()
        val key = Bmh3Crypto.contactTagKey(ByteArray(32) { 13 })
        val pipes = Duplex()
        val out = both(
            {
                Bmh3LinkHandshake.initiate(
                    pipes.bToA.input, pipes.aToB.output, i, caps,
                    Bmh3InitiatorOptions(contactTag = Bmh3ContactTag(key, binding, expectedPeer = r.nodeId)),
                )
            },
            {
                Bmh3LinkHandshake.respond(
                    pipes.aToB.input, pipes.bToA.output, readFirst(pipes.aToB.input), stranger, responderCaps,
                    Bmh3ResponderOptions(),
                )
            },
            onResponderFailure = { pipes.bToA.close() },
            onInitiatorFailure = { pipes.aToB.close() },
        )
        assertEquals("not the expected contact", (out.initiator.exceptionOrNull() as Bmh3Failure).reason)
        assertEquals("the caller never revealed itself", 0, i.signed.get())
        assertTrue(out.responder.isFailure)
    }

    @Test
    fun aContactsOnlyResponderChecksThatATaggedInitiatorIsALeaseRecipient() {
        val tagKey = Bmh3Crypto.contactTagKey(ByteArray(32) { 17 })
        var sawMatched: Boolean? = null
        val responder = Bmh3Responder(
            config(Bmh3Purpose.LINK, newIdentity()),
            mode = Bmh3ResponderMode.CONTACTS_ONLY,
            tagChecks = listOf(tag(tagKey)),
            admitInitiator = { _, matched -> sawMatched = matched; false },
        )
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity()), contactTag = tag(tagKey))
        val m3 = initiator.onMessage2(responder.onMessage1(initiator.message1()))
        assertEquals("initiator refused", assertThrows(Bmh3Failure::class.java) { responder.onMessage3(m3) }.reason)
        assertEquals(true, sawMatched)
    }

    @Test
    fun inIFirstTheCheckOnTheResponderStillAbortsAfterMessage4() {
        val checked = AtomicInteger()
        val initiator = Bmh3Initiator(
            config(Bmh3Purpose.LINK, newIdentity()),
            mayRevealFirst = true,
            checkResponder = { checked.incrementAndGet(); throw Bmh3Failure("binding") },
        )
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), mode = Bmh3ResponderMode.CONTACTS_ONLY)
        val m4 = responder.onMessage3(initiator.onMessage2(responder.onMessage1(initiator.message1())))
        assertEquals("binding", assertThrows(Bmh3Failure::class.java) { initiator.onMessage4(requireNotNull(m4)) }.reason)
        assertEquals(1, checked.get())
        assertFalse(initiator.isComplete)
    }

    @Test
    fun everyMessageIsBuiltOnceAndAStateMachineIsSingleShot() {
        val initiator = Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity()))
        val responder = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()))
        val m1 = initiator.message1()
        assertThrows(IllegalStateException::class.java) { initiator.message1() }
        val m2 = responder.onMessage1(m1)
        assertThrows(IllegalStateException::class.java) { responder.onMessage1(m1) }
        initiator.onMessage2(m2)
        // A second msg2 (a retransmission, or an attacker's second answer) cannot make the initiator sign again.
        assertThrows(IllegalStateException::class.java) { initiator.onMessage2(m2) }
    }

    @Test
    fun theLinkToARelayPresentsAThrowawayKeyAndTheEndpointKeepsTheRealOne() {
        val relayOffer = callOffer(routeCost = 1)
        val direct = callOffer()
        val link = Bmh3Policy.initiatorOptions(relayOffer, Bmh3Policy.Layer.LINK, log = {})
        assertNotNull(link.identityOverride)
        assertNull(Bmh3Policy.initiatorOptions(relayOffer, Bmh3Policy.Layer.ENDPOINT, log = {}).identityOverride)
        assertNull(Bmh3Policy.initiatorOptions(direct, Bmh3Policy.Layer.LINK, log = {}).identityOverride)
        assertNull(Bmh3Policy.initiatorOptions(null, Bmh3Policy.Layer.LINK, log = {}).identityOverride)
        // Over a real link handshake the answering relay learns the throwaway key, not the caller's.
        val real = newIdentity()
        val relay = newIdentity()
        val pipes = Duplex()
        val out = both(
            { Bmh3LinkHandshake.initiate(pipes.bToA.input, pipes.aToB.output, real, caps, link) },
            { Bmh3LinkHandshake.respond(pipes.aToB.input, pipes.bToA.output, readFirst(pipes.aToB.input), relay, responderCaps, Bmh3ResponderOptions()) },
        )
        val callerSession = out.initiator.getOrThrow()
        val relaySession = out.responder.getOrThrow()
        assertFalse(relaySession.peerNodeId == real.nodeId)
        assertEquals(relay.nodeId, callerSession.peerNodeId)
        assertEquals("the real key never signed", 0, real.signed.get())
    }

    @Test
    fun theSigningBudgetRunsBeforeAKeyIsDrawnAndAMatchingTagSkipsIt() {
        var spent = 0
        val beforeReply: (Boolean) -> Unit = { matched -> if (!matched) spent++ }
        val tagKey = Bmh3Crypto.contactTagKey(ByteArray(32) { 6 })
        val stranger = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), beforeReply = beforeReply)
        stranger.onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity())).message1())
        assertEquals(1, spent)
        val matched = Bmh3Responder(config(Bmh3Purpose.LINK, newIdentity()), tagChecks = listOf(tag(tagKey)), beforeReply = beforeReply)
        matched.onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity()), contactTag = tag(tagKey)).message1())
        assertEquals("no token for a lease holder", 1, spent)
        // A throwing hook means the entropy (and so every signature) was never touched.
        val entropy = CountingEntropy()
        val refusing = Bmh3Responder(Bmh3Config(Bmh3Purpose.LINK, newIdentity(), caps, entropy), beforeReply = { throw Bmh3Failure("budget") })
        assertThrows(Bmh3Failure::class.java) { refusing.onMessage1(Bmh3Initiator(config(Bmh3Purpose.LINK, newIdentity())).message1()) }
        assertEquals(0, entropy.ephemerals)
    }

    @Test
    fun theSwitchIsOnByDefaultSinceTheBetaAndNamedAsDocumented() {
        assertTrue("one constant holds the default (on since 0.2.0-beta, after phone test T14)", ConnectivityFeatureDefaults.BMH3)
        val spec = ConnectivityFeatures.bmh3
        assertEquals("bmh3", spec.id)
        assertEquals("test_bmh3", spec.benchmarkExtra)
        assertEquals(Bmh3Feature.EXTRA, spec.benchmarkExtra)
        assertTrue("no Settings switch since 2026-11-04", spec.constant)
        assertEquals("T14", spec.phoneTest)
        assertTrue(spec.defaultEnabled)
        assertEquals(ConnectivityFeatures.GROUP, spec.group)
        assertTrue(ConnectivityFeatures.all.contains(spec))
        // The sub-options are DEBUG extras only and start off.
        Bmh3Feature.configureDebug(false, false, null)
        assertFalse(Bmh3Feature.only)
        assertFalse(Bmh3Feature.contactsOnly)
        assertNull(Bmh3Feature.testLeaseKey)
        assertEquals("test_bmh3_only", Bmh3Feature.EXTRA_ONLY)
        assertEquals("test_bmh3_contacts_only", Bmh3Feature.EXTRA_CONTACTS_ONLY)
        assertEquals("test_bmh3_test_lease", Bmh3Feature.EXTRA_TEST_LEASE)
    }

    @Test
    fun withTheSwitchOffTheOldPathsAreUntouched() {
        // No options at all: the BMH2 entry points take their old arguments and reject a BMH3 first frame as before.
        val offer = callOffer()
        assertSame(offer, Bmh3Hint.offerFor(offer, enabled = false))
        assertFalse(Bmh3Choice.decide(false, true, callOffer(version = 5), true).bmh3)
        val frame = Bmh3Wire.MAGIC + ByteArray(Bmh3Wire.MSG1_BYTES - 4)
        assertEquals("Invalid handshake message", assertThrows(IllegalArgumentException::class.java) { HandshakeHelloCodec.decode(frame) }.message)
    }

    @Test
    fun theBuildTagRidesInTheEncryptedLinkBlockAndTheEndpointNeverCarriesOne() {
        val i = newIdentity()
        val r = newIdentity()
        val plain = run(Bmh3Initiator(config(Bmh3Purpose.LINK, i)), Bmh3Responder(config(Bmh3Purpose.LINK, r)))
        val tagged = run(
            Bmh3Initiator(Bmh3Config(Bmh3Purpose.LINK, i, caps, buildTag = BuildTag(6, false))),
            Bmh3Responder(Bmh3Config(Bmh3Purpose.LINK, r, caps, buildTag = BuildTag(7, true))),
        )
        assertEquals("a tag changes no message size, so a sniffer cannot tell", plain.messages.map { it.size }, tagged.messages.map { it.size })
        assertEquals(BuildTag(7, true), tagged.initiator.result().peer.buildTag)
        assertEquals(BuildTag(6, false), tagged.responder.result().peer.buildTag)
        assertNull(plain.initiator.result().peer.buildTag)
        // The end-to-end purpose ignores a tag: bit 7 there means call video fragments, never a build.
        assertNull(Bmh3Config(Bmh3Purpose.END_TO_END, i, caps, buildTag = BuildTag(7, true)).buildTag)
        val endpoint = run(
            Bmh3Initiator(Bmh3Config(Bmh3Purpose.END_TO_END, i, caps, buildTag = BuildTag(6, false))),
            Bmh3Responder(Bmh3Config(Bmh3Purpose.END_TO_END, r, caps, buildTag = BuildTag(7, true))),
        )
        assertNull(endpoint.initiator.result().peer.buildTag)
        assertNull(endpoint.responder.result().peer.buildTag)
    }

    @Test
    fun aBmh3LinkCarriesTheBuildTagLikeBmh2DoesAndReportsOnlyOnTheInitiatingSide() {
        val seen = ArrayList<BuildTag>()
        val saved = LinkBuildTags.sink
        LinkBuildTags.sink = { synchronized(seen) { seen += it } }
        try {
            val i = newIdentity()
            val r = newIdentity()
            val pipes = Duplex()
            val out = both(
                {
                    SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, i, caps, buildTag = BuildTag(6, false), bmh3 = Bmh3InitiatorOptions())
                },
                {
                    SecureBluetoothHandshake.respond(
                        pipes.aToB.input, pipes.bToA.output, r, responderCaps,
                        buildTag = BuildTag(7, true), bmh3 = Bmh3ResponderOptions(),
                    )
                },
            )
            val initiator = out.initiator.getOrThrow()
            val responder = out.responder.getOrThrow()
            assertEquals(BuildTag(7, true), initiator.peerBuildTag)
            assertEquals(BuildTag(6, false), responder.peerBuildTag)
            // Only the phone that opened the link reports what it learned (a stranger cannot plant a notice).
            assertEquals(listOf(BuildTag(7, true)), seen)
            // Bit 7 never reaches the rest of the app.
            assertEquals(0, initiator.peerCapabilities.transportMask and BuildTag.LINK_HELLO_BIT)
            assertEquals(responderCaps, initiator.peerCapabilities)
            // Without a tag on either side nothing is reported and nothing is carried.
            seen.clear()
            val quiet = Duplex()
            val none = both(
                { SecureBluetoothHandshake.initiate(quiet.bToA.input, quiet.aToB.output, i, caps, buildTag = null, bmh3 = Bmh3InitiatorOptions()) },
                { SecureBluetoothHandshake.respond(quiet.aToB.input, quiet.bToA.output, r, responderCaps, buildTag = null, bmh3 = Bmh3ResponderOptions()) },
            )
            assertNull(none.initiator.getOrThrow().peerBuildTag)
            assertTrue(seen.isEmpty())
        } finally {
            LinkBuildTags.sink = saved
        }
    }

    private class CountingEntropy : Bmh3Entropy {
        var ephemerals = 0
        override fun ephemeral(): Bmh3Ephemeral = SecureBmh3Entropy.ephemeral().also { ephemerals++ }
        override fun nonce(): ByteArray = SecureBmh3Entropy.nonce()
        override fun randomTag(): ByteArray = SecureBmh3Entropy.randomTag()
    }

    @Test
    fun theLogVocabularyNeverNamesAnIdentifier() {
        val line = Bmh3Log.handshake("link", "initiator", Bmh3Order.R_FIRST, "ok", 123, hint = true, pinned = false)
        assertEquals("bmh3: event=link | role=initiator | order=R_FIRST | result=ok | ms=123 | hint=true | pinned=false", line)
        assertEquals("bmh3: event=choice | version=3 | reason=HINT", Bmh3Log.choice(3, "HINT"))
    }

    @Test
    fun theCalleesLoggedPseudonymIsTheChildIdTheRelayLogs() {
        // relay <-> callee hop: the callee answers behind a throwaway; the relay names it "child" in its relaying line.
        val relay = newIdentity()
        val callee = newIdentity()
        val throwaway = newIdentity()
        val relayThrowaway = newIdentity()
        val hop = Duplex()
        val out = both(
            { Bmh3LinkHandshake.initiate(hop.bToA.input, hop.aToB.output, relay, caps, Bmh3InitiatorOptions(strangerIdentity = relayThrowaway)) },
            {
                Bmh3LinkHandshake.respond(
                    hop.aToB.input, hop.bToA.output, readFirst(hop.aToB.input), callee, responderCaps,
                    Bmh3ResponderOptions(strangerIdentity = throwaway),
                )
            },
        )
        val relayDown = out.initiator.getOrThrow()
        val calleeLink = out.responder.getOrThrow()
        assertTrue(calleeLink.ownPseudonymous)
        val childAsTheRelayLogsIt = relayDown.peerNodeId.shortLabel()
        assertEquals(throwaway.nodeId.shortLabel(), childAsTheRelayLogsIt)
        assertEquals(
            "BENCHMARK pseudonym_own: role=call | link=${relayThrowaway.nodeId.shortLabel()} | id=$childAsTheRelayLogsIt",
            PseudonymOwnLog.line(calleeLink),
        )
        // Symmetric: the callee would log the relay's throwaway the same way.
        assertEquals(calleeLink.peerNodeId.shortLabel(), relayThrowaway.nodeId.shortLabel())
        assertTrue(PseudonymOwnLog.line(relayDown)!!.endsWith("| id=${relayThrowaway.nodeId.shortLabel()}"))
    }
}
