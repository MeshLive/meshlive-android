package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hop plumbing of the Wi-Fi upgrade (PROTOCOL "Wi-Fi transport"): record type 16, the BMH3 marker and the hop `UW`. */
class WifiHopPlumbingTest {
    /** Safety net: a test that hangs fails after this instead of holding the whole run. */
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
    private val firstRelease = WifiWire.WIFI_FIRST_RELEASE

    private val offer = DiscoveryAnnouncement(
        nodeIdPrefix = ByteArray(6) { (it + 10).toByte() },
        flags = 0x41,
        capabilitySummary = 3,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
        version = DiscoveryAnnouncement.ROTATING_VERSION,
    )
    private val relayOffer = offer.copy(routeCost = 1)
    private val hopOffer = offer.copy(nodeIdPrefix = ByteArray(6) { (it + 20).toByte() })

    private fun newIdentity() = KeyPairSigningIdentity(generateEcKeyPair())

    // --------------------------------------------------------------------------------------------- step 4

    private fun plan(
        selected: DiscoveryAnnouncement?,
        wifiMarker: Boolean = true,
        relayRoute: Bmh3AttemptPlan.RelayRoute = Bmh3AttemptPlan.RelayRoute.NONE,
        qrScan: Bvmid2Payload? = null,
    ): Bmh3AttemptPlan = Bmh3AttemptPlan.create(
        enabled = true,
        only = true,
        selected = selected,
        pinned = false,
        pseudonymMode = false,
        qrScan = qrScan,
        wifiMarker = wifiMarker,
        relayRoute = relayRoute,
    )

    @Test
    fun aLinkAttemptSignsTheMarkerEvenTowardARelayAndAnEndpointAttemptNever() {
        assertTrue(plan(offer).initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true)
        // A direct selected advert and a link toward a relay edge both sign it at the LINK layer.
        assertTrue(plan(relayOffer).initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true)
        assertTrue(
            plan(offer, relayRoute = Bmh3AttemptPlan.RelayRoute(true, hopOffer, null))
                .initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true,
        )
        assertFalse(plan(offer).initiator(Bmh3Policy.Layer.ENDPOINT)?.wifiMarker == true)
        // A verification scan never carries it.
        val qrScan = Bvmid2Payload(
            IdentityFingerprint.fromPublicKey(newIdentity().publicKeyEncoded),
            ByteArray(32),
            ByteArray(RotatingDiscoveryIds.ID_BYTES),
        )
        assertFalse(plan(offer, qrScan = qrScan).initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true)
        // Default off, and BMH2 attempts have no options at all.
        assertFalse(plan(offer, wifiMarker = false).initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true)
        assertNull(Bmh3AttemptPlan.OFF.initiator(Bmh3Policy.Layer.LINK))
    }

    @Test
    fun theMarkerDoesNotChangeTheRestOfTheOptions() {
        val tag = Bmh3ContactTag(ByteArray(32) { (it + 1).toByte() }, ByteArray(Bmh3Wire.BINDING_BYTES))
        val check: (Bmh3Peer) -> Unit = {}
        val identity = newIdentity()
        val stranger = newIdentity()
        val log: (String) -> Unit = {}
        val base = Bmh3InitiatorOptions(
            contactTag = tag,
            checkResponder = check,
            entropy = SecureBmh3Entropy,
            identityOverride = identity,
            strangerIdentity = stranger,
            log = log,
        )
        val marked = base.withWifiMarker(true)
        assertTrue(marked.wifiMarker)
        assertSame(tag, marked.contactTag)
        assertSame(check, marked.checkResponder)
        assertSame(SecureBmh3Entropy, marked.entropy)
        assertSame(identity, marked.identityOverride)
        assertSame(stranger, marked.strangerIdentity)
        assertSame(log, marked.log)
        assertSame("an unmarked object is returned unchanged", base, base.withWifiMarker(false))
    }

    @Test
    fun aRelayHopFactoryOptionsCanCarryTheMarkerAndKeepTheThrowaway() {
        val base = Bmh3Policy.relayHopOptions(hopOffer)
        val options = base.withWifiMarker(true)
        assertTrue(options.wifiMarker)
        assertNull(options.contactTag)
        assertTrue("the relay hop carries a fresh throwaway", options.strangerIdentity != null)
        assertSame("the marker keeps the throwaway of the relay-hop factory", base.strangerIdentity, options.strangerIdentity)
    }

    // --------------------------------------------------------------------------------------------- step 5

    @Test
    fun theBmh2GateNeedsBothSignedTagsAtOrAboveTheFirstRelease() {
        val f = firstRelease
        val rows = listOf(
            null to BuildTag(f, false),
            BuildTag(f, false) to null,
            BuildTag(f - 1, false) to BuildTag(f, false),
            BuildTag(f, false) to BuildTag(f, false),
            BuildTag(f + 2, false) to BuildTag(f + 1, false),
        )
        val expected = listOf(false, false, false, true, true)
        rows.forEachIndexed { index, (own, peer) ->
            assertEquals(expected[index], WifiHopGate.bmh2TagsAllow(own, peer))
            assertEquals(WifiWire.buildTagAllowsBwf1(own) && WifiWire.buildTagAllowsBwf1(peer), WifiHopGate.bmh2TagsAllow(own, peer))
        }
    }

    @Test
    fun aBmh2HopOpensTheReaderToBwf1OnlyWhenBothHellosCarryAllowedTags() {
        val f = firstRelease
        val allowed = bmh2Link(BuildTag(f, false), BuildTag(f, false))
        assertTrue(allowed.initiator.wifiReadOptIn)
        assertTrue(allowed.responder.wifiReadOptIn)
        val oneMissing = bmh2Link(BuildTag(f, false), null)
        assertFalse(oneMissing.initiator.wifiReadOptIn)
        assertFalse(oneMissing.responder.wifiReadOptIn)
        val oneTooOld = bmh2Link(BuildTag(f - 1, false), BuildTag(f, false))
        assertFalse(oneTooOld.initiator.wifiReadOptIn)
        assertFalse(oneTooOld.responder.wifiReadOptIn)
        // A tag does not open a BMH3 hop: its rule is the marker.
        val bmh3 = bmh3Link(initiatorTag = BuildTag(f, false), responderTag = BuildTag(f, false))
        assertFalse(bmh3.initiator.wifiReadOptIn)
        assertFalse(bmh3.responder.wifiReadOptIn)
    }

    @Test
    fun bothEndsOfAHopDeriveTheSameKeyAndItIsZeroedWhenTheSessionCloses() {
        for (pair in listOf(bmh2Link(), bmh3Link())) {
            val initiatorKey = pair.initiator.hopWifiKey
            val responderKey = pair.responder.hopWifiKey
            assertNotNull(initiatorKey)
            assertNotNull(responderKey)
            assertEquals(32, initiatorKey!!.size)
            assertArrayEquals(initiatorKey, responderKey)
            pair.initiator.close()
            assertTrue("close() zeroes this side's hop UW", initiatorKey.all { it == 0.toByte() })
            assertFalse("the other side's hop UW is untouched", responderKey!!.all { it == 0.toByte() })
            pair.initiator.close() // idempotent
            pair.responder.dropWifiKey()
            assertTrue(responderKey.all { it == 0.toByte() })
        }
    }

    @Test
    fun theHopKeyDiffersFromEveryDirectionalKeyAndBetweenHops() {
        val i = newIdentity()
        val r = newIdentity()
        val first = bmh2Link(initiatorId = i, responderId = r)
        val second = bmh2Link(initiatorId = i, responderId = r)
        assertFalse(first.initiator.hopWifiKey!!.contentEquals(second.initiator.hopWifiKey!!))
        assertThrows(IllegalArgumentException::class.java) { WifiKeys.uwForHop(ByteArray(32), ByteArray(32)) }
    }

    @Test
    fun theOwnTagIsTheBaseWhileNoWifiSwitchIsOn() {
        val off = WifiSwitches(carrier = false, widerReach = false, direct = false)
        for (base in listOf(null, BuildTag(3, false), BuildTag(firstRelease, true))) {
            for (realBuild in listOf(null, BuildTag(9, true))) {
                for (debugBuild in listOf(false, true)) {
                    assertEquals(base, WifiHopGate.effectiveOwnTag(base, realBuild, debugBuild, off, firstRelease, true))
                    assertEquals(base, WifiHopGate.effectiveOwnTag(base, realBuild, debugBuild, off, firstRelease, false))
                    assertEquals(base, WifiHopGate.ownTagFor(base, true, realBuild, debugBuild, off, firstRelease, true))
                }
            }
        }
    }

    @Test
    fun aDebugBuildSendsTheFirstReleaseWhileAnyWifiSwitchIsOn() {
        val f = firstRelease
        val oneAtATime = listOf(
            WifiSwitches(carrier = true, widerReach = false, direct = false),
            WifiSwitches(carrier = false, widerReach = true, direct = false),
            WifiSwitches(carrier = false, widerReach = false, direct = true),
        )
        for (switches in oneAtATime) {
            assertEquals(BuildTag(f, false), WifiHopGate.effectiveOwnTag(null, null, true, switches, f, true))
            assertEquals(BuildTag(f, false), WifiHopGate.ownTagFor(null, true, null, true, switches, f, true))
        }
        // A base with a higher number is kept.
        assertEquals(BuildTag(f + 2, true), WifiHopGate.effectiveOwnTag(BuildTag(f + 2, true), null, true, oneAtATime[0], f, true))
    }

    @Test
    fun aReleaseBuildSendsItsRealTagWhileTheCarrierSwitchIsOnEvenWithTheNoticeSwitchOff() {
        val f = firstRelease
        val real = BuildTag(f, true)
        assertEquals(real, WifiHopGate.effectiveOwnTag(null, real, false, WifiSwitches(true, false, false), f, true))
        assertEquals(null, WifiHopGate.effectiveOwnTag(null, real, false, WifiSwitches(false, true, false), f, true))
        assertEquals(null, WifiHopGate.effectiveOwnTag(null, real, false, WifiSwitches(false, false, true), f, true))
    }

    @Test
    fun noTagIsRaisedBeforeTheBuildHandlesBwf1() {
        val f = firstRelease
        for (base in listOf(null, BuildTag(3, false))) {
            for (switches in listOf(
                WifiSwitches(false, false, false), WifiSwitches(true, true, true),
            )) {
                for (debugBuild in listOf(false, true)) {
                    assertEquals(base, WifiHopGate.effectiveOwnTag(base, BuildTag(f + 1, true), debugBuild, switches, f, false))
                    assertEquals(base, WifiHopGate.ownTagFor(base, true, BuildTag(f + 1, true), debugBuild, switches, f, false))
                }
            }
        }
    }

    @Test
    fun aPairWithOneHiddenTagIsOffAtBothEnds() {
        val f = firstRelease
        // (a) The pure rule: the visible end raises its tag, the hidden end has none.
        val tagA = WifiHopGate.ownTagFor(
            null, true, BuildTag(f, true), false, WifiSwitches(carrier = true, widerReach = false, direct = false), f, true,
        )
        val tagB = WifiHopGate.ownTagFor(
            null, true, BuildTag(f, true), false, WifiSwitches(carrier = false, widerReach = false, direct = false), f, true,
        )
        assertEquals(BuildTag(f, true), tagA)
        assertEquals(null, tagB)
        assertFalse(WifiHopGate.bmh2TagsAllow(tagA, tagB))
        assertFalse(WifiHopGate.bmh2TagsAllow(tagB, tagA))
        assertTrue(WifiHopGate.bmh2TagsAllow(tagA, tagA))
        // (b) The real handshake: one hidden tag is OFF at both ends, both visible tags are ON.
        for ((initiatorTag, responderTag) in listOf(
            BuildTag(f, false) to null,
            null to BuildTag(f, false),
        )) {
            val pair = bmh2Link(initiatorTag, responderTag)
            assertFalse(pair.initiator.wifiReadOptIn)
            assertFalse(pair.responder.wifiReadOptIn)
        }
        val bothVisible = bmh2Link(BuildTag(f, false), BuildTag(f, false))
        assertTrue(bothVisible.initiator.wifiReadOptIn)
        assertTrue(bothVisible.responder.wifiReadOptIn)
    }

    @Test
    fun theTagIsRaisedOnlyWhereTheCallerAsks() {
        val f = firstRelease
        val all = WifiSwitches(carrier = true, widerReach = true, direct = true)
        assertEquals(null, WifiHopGate.ownTagFor(null, false, null, true, all, f, true))
        assertEquals(BuildTag(3, false), WifiHopGate.ownTagFor(BuildTag(3, false), false, null, true, all, f, true))
        assertEquals(BuildTag(f, false), WifiHopGate.ownTagFor(null, true, null, true, all, f, true))
    }

    @Test
    fun aBwf1RecordIsWrittenOnlyToAPeerProvenToReadIt() {
        // Review A3 P2-5 (R-1 at the write). The call opens its reader to BWF1 from the handshake, as establish does.
        val marked = bmh3Link(marker = true)
        marked.initiator.reader.acceptWifi = marked.initiator.wifiReadOptIn
        marked.responder.reader.acceptWifi = marked.responder.wifiReadOptIn
        assertTrue("the responder saw the marker", WifiHopGate.mayWriteBwf1(marked.responder, peerSentBwf1 = false))
        assertFalse("the initiator waits for the peer's first BWF1", WifiHopGate.mayWriteBwf1(marked.initiator, peerSentBwf1 = false))
        assertTrue(WifiHopGate.mayWriteBwf1(marked.initiator, peerSentBwf1 = true))
        // An older peer that ignored the marker never proves anything; with the reader closed nothing is written.
        val unmarked = bmh3Link()
        unmarked.initiator.reader.acceptWifi = unmarked.initiator.wifiReadOptIn
        unmarked.responder.reader.acceptWifi = unmarked.responder.wifiReadOptIn
        assertFalse(WifiHopGate.mayWriteBwf1(unmarked.responder, peerSentBwf1 = false))
        assertFalse(WifiHopGate.mayWriteBwf1(unmarked.initiator, peerSentBwf1 = false))
        marked.responder.reader.acceptWifi = false
        assertFalse(WifiHopGate.mayWriteBwf1(marked.responder, peerSentBwf1 = true))
    }

    @Test
    fun peerCapableIsProvenOnlyByTheHandshakeNotByTheOwnMarker() {
        val f = firstRelease
        val bothTags = bmh2Link(BuildTag(f, false), BuildTag(f, false))
        assertTrue(WifiHopGate.peerCapable(bothTags.initiator))
        assertTrue(WifiHopGate.peerCapable(bothTags.responder))
        val oneHidden = bmh2Link(BuildTag(f, false), null)
        assertFalse(WifiHopGate.peerCapable(oneHidden.initiator))
        assertFalse(WifiHopGate.peerCapable(oneHidden.responder))
        val marked = bmh3Link(marker = true)
        assertTrue(WifiHopGate.peerCapable(marked.responder))
        assertFalse(WifiHopGate.peerCapable(marked.initiator))
        assertTrue("the initiator may read BWF1 but has not proven the peer can", marked.initiator.wifiReadOptIn)
        val unmarked = bmh3Link()
        assertFalse(WifiHopGate.peerCapable(unmarked.initiator))
        assertFalse(WifiHopGate.peerCapable(unmarked.responder))
    }

    // --------------------------------------------------------------------------------------------- step 6

    @Test
    fun bothEndsOfACallDeriveTheSameUw() {
        val first = bmh3Call()
        val callerKey = first.caller.wifiKey
        val calleeKey = first.callee.wifiKey
        assertNotNull(callerKey)
        assertNotNull(calleeKey)
        assertEquals(32, callerKey!!.size)
        assertArrayEquals(callerKey, calleeKey)
        val second = bmh3Call()
        assertFalse("two calls derive different UW", callerKey.contentEquals(second.caller.wifiKey!!))
    }

    @Test
    fun uwIsZeroedWhenTheCallSessionClosesAndOnDemand() {
        val call = bmh3Call()
        val key = call.caller.wifiKey!!
        call.caller.dropWifiKey()
        assertTrue(key.all { it == 0.toByte() })
        // dropWifiKey leaves the sealer usable: only the call's end closes it.
        assertTrue(call.caller.sealer.seal(EndToEndContentType.PRIVATE_CALL_SIGNAL, 0, byteArrayOf(1)).isNotEmpty())
        call.caller.close()
        call.caller.close() // idempotent
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun uwSurvivesTheLinkRenewalBecauseItBelongsToTheCallSession() {
        val call = bmh3Call()
        val key = call.caller.wifiKey!!
        val before = key.copyOf()
        assertTrue(before.any { it != 0.toByte() })
        // A renewal builds a second link session; the call's UW belongs to its end-to-end session and survives.
        SecureBluetoothSession(
            call.callerLink.peerNodeId,
            call.callerLink.peerCapabilities,
            SecureRecordReader(ByteArrayInputStream(ByteArray(0)), ByteArray(32)),
            SecureRecordWriter(ByteArrayOutputStream(), ByteArray(32)),
        )
        assertArrayEquals(before, call.caller.wifiKey)
        assertArrayEquals(before, key)
    }

    @Test
    fun aBmh2EndToEndCallHasNoUw() {
        val call = bmh2Call()
        assertNull(call.caller.wifiKey)
        assertNull(call.callee.wifiKey)
    }

    @Test
    fun uwIsNotTheExporterAndNotTheRenewalKey() {
        val renewalCaps = caps.copy(transportMask = caps.transportMask or CallRenewalWire.CAPABILITY_BIT)
        val call = bmh3Call(callerCaps = renewalCaps, calleeCaps = renewalCaps.copy(maxChildren = 1))
        val key = call.caller.wifiKey!!
        val renewal = call.caller.renewalKey
        assertNotNull(renewal)
        assertFalse(key.contentEquals(renewal))
        assertTrue(key.any { it != 0.toByte() })
        assertArrayEquals(renewal, call.callee.renewalKey)
    }

    // --------------------------------------------------------------------------------------------- step 7

    @Test
    fun aCallAttemptSignsTheMarkerForADirectAndARelayEdgeLink() {
        // The call's reader intercepts type 16 (A3-7): the BMH3 marker may be sent now. The attempt plan signs it for a
        // direct attempt and for a relay-edge attempt at the LINK layer [R M-3]; a verification scan never carries it.
        assertTrue(plan(offer).initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true)
        assertTrue(
            plan(offer, relayRoute = Bmh3AttemptPlan.RelayRoute(true, hopOffer, null))
                .initiator(Bmh3Policy.Layer.LINK)?.wifiMarker == true,
        )
    }

    @Test
    fun aHelloWithoutRaiseTagKeepsItsBaseTagAfterTheFlip() {
        assertTrue(WifiHopPlumbing.BUILD_HANDLES_BWF1)
        val savedSwitchSource = WifiHopGate.switchSource
        val savedDebugBuild = WifiHopGate.debugBuild
        try {
            WifiHopGate.switchSource = { WifiSwitches(carrier = true, widerReach = true, direct = true) }
            WifiHopGate.debugBuild = true
            // A3 passes no raiseTag: calls, messages and strangers keep their base tag (null here).
            val plain = bmh2Link(null, null, raiseTag = false)
            assertNull(plain.initiator.peerBuildTag)
            assertNull(plain.responder.peerBuildTag)
            // Only A7's hop sites raise it: both ends then see the first release.
            val raised = bmh2Link(null, null, raiseTag = true)
            assertEquals(BuildTag(WifiWire.WIFI_FIRST_RELEASE, false), raised.initiator.peerBuildTag)
            assertEquals(BuildTag(WifiWire.WIFI_FIRST_RELEASE, false), raised.responder.peerBuildTag)
        } finally {
            WifiHopGate.switchSource = savedSwitchSource
            WifiHopGate.debugBuild = savedDebugBuild
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private class LinkPair(val initiator: SecureBluetoothSession, val responder: SecureBluetoothSession)

    private class CallPair(
        val callerLink: SecureBluetoothSession,
        val calleeLink: SecureBluetoothSession,
        val caller: EndToEndVideoSession,
        val callee: EndToEndVideoSession,
    )

    private fun bmh2Link(
        initiatorTag: BuildTag? = null,
        responderTag: BuildTag? = null,
        initiatorId: SigningIdentity = newIdentity(),
        responderId: SigningIdentity = newIdentity(),
        raiseTag: Boolean = false,
    ): LinkPair {
        val pipes = Duplex()
        val out = both(
            {
                SecureBluetoothHandshake.initiate(
                    pipes.bToA.input, pipes.aToB.output, initiatorId, caps,
                    buildTag = initiatorTag, raiseTag = raiseTag,
                )
            },
            {
                SecureBluetoothHandshake.respond(
                    pipes.aToB.input, pipes.bToA.output, responderId, responderCaps,
                    buildTag = responderTag, raiseTag = raiseTag,
                )
            },
        )
        return LinkPair(out.initiator.getOrThrow(), out.responder.getOrThrow())
    }

    private fun bmh3Link(
        marker: Boolean = false,
        initiatorTag: BuildTag? = null,
        responderTag: BuildTag? = null,
        initiatorId: SigningIdentity = newIdentity(),
        responderId: SigningIdentity = newIdentity(),
    ): LinkPair {
        val pipes = Duplex()
        val out = both(
            {
                SecureBluetoothHandshake.initiate(
                    pipes.bToA.input, pipes.aToB.output, initiatorId, caps,
                    buildTag = initiatorTag, bmh3 = Bmh3InitiatorOptions(wifiMarker = marker),
                )
            },
            {
                SecureBluetoothHandshake.respond(
                    pipes.aToB.input, pipes.bToA.output, responderId, responderCaps,
                    buildTag = responderTag, bmh3 = Bmh3ResponderOptions(),
                )
            },
        )
        return LinkPair(out.initiator.getOrThrow(), out.responder.getOrThrow())
    }

    private fun bmh2Call(): CallPair {
        val pipes = Duplex()
        val callerId = newIdentity()
        val calleeId = newIdentity()
        val links = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, callerId, caps) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, calleeId, responderCaps) },
        )
        val callerLink = links.initiator.getOrThrow()
        val calleeLink = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(callerLink, callerId, caps) },
            { EndToEndVideoHandshake.respond(calleeLink, calleeId, responderCaps) },
        )
        return CallPair(callerLink, calleeLink, ends.initiator.getOrThrow(), ends.responder.getOrThrow())
    }

    private fun bmh3Call(callerCaps: NodeCapabilities = caps, calleeCaps: NodeCapabilities = responderCaps): CallPair {
        val pipes = Duplex()
        val callerId = newIdentity()
        val calleeId = newIdentity()
        val callerOptions = Bmh3InitiatorOptions()
        val calleeOptions = Bmh3ResponderOptions()
        val links = both(
            { SecureBluetoothHandshake.initiate(pipes.bToA.input, pipes.aToB.output, callerId, callerCaps, bmh3 = callerOptions) },
            { SecureBluetoothHandshake.respond(pipes.aToB.input, pipes.bToA.output, calleeId, calleeCaps, bmh3 = calleeOptions) },
        )
        val callerLink = links.initiator.getOrThrow()
        val calleeLink = links.responder.getOrThrow()
        val ends = both(
            { EndToEndVideoHandshake.initiate(callerLink, callerId, callerCaps, bmh3 = callerOptions) },
            { EndToEndVideoHandshake.respond(calleeLink, calleeId, calleeCaps, bmh3 = calleeOptions) },
        )
        return CallPair(callerLink, calleeLink, ends.initiator.getOrThrow(), ends.responder.getOrThrow())
    }

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

    private fun <T> both(initiator: () -> T, responder: () -> T): Both<T> {
        val responderResult = AtomicReference<Result<T>>()
        val worker = thread(name = "responder", isDaemon = true) {
            responderResult.set(runCatching(responder))
        }
        val initiatorResult = runCatching(initiator)
        worker.join(120_000)
        return Both(initiatorResult, responderResult.get() ?: Result.failure(IllegalStateException("responder timed out")))
    }
}
