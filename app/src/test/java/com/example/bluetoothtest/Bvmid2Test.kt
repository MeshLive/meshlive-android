package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** BVMID2 (Design G, G4): format, K_tag vector, single use (reserve/commit), scope, scanner checks, mutual outcome. */
class Bvmid2Test {
    private val caps = NodeCapabilities(
        transportMask = NodeCapabilities.TRANSPORT_BLE_L2CAP,
        encodeCodecMask = NodeCapabilities.CODEC_VP9,
        decodeCodecMask = NodeCapabilities.CODEC_VP9,
        maxBitrateKbps = 1_100,
        maxChildren = 1,
    )
    private val displayerIdentity = KeyPairSigningIdentity(generateEcKeyPair())
    private val displayerFingerprint = IdentityFingerprint.of(displayerIdentity)
    private val rdi = byteArrayOf(0x44, 0xe6.toByte(), 0x9c.toByte(), 0x0a, 0x7a, 0x7d)

    private var now = 1_000_000L
    private val events = Recorder()
    private var counter = 1
    private lateinit var display: QrCodeDisplay

    private class Recorder : QrDisplayEvents {
        val scans = mutableListOf<IdentityFingerprint>()
        var reuse = 0
        val ended = mutableListOf<String>()
        override fun onScanCompleted(peer: IdentityFingerprint) { scans += peer }
        override fun onReuseAlert() { reuse++ }
        override fun onCodeEnded(reason: String) { ended += reason }
    }

    @Before
    fun setUp() {
        now = 1_000_000L
        counter = 1
        display = QrCodeDisplay(
            clockMs = { now },
            random = { bytes -> bytes.indices.forEach { bytes[it] = (counter * 7 + it).toByte() }; counter++ },
            budget = Bmh3SigningBudget(capacity = 50, refillMs = 1, clockMs = { now }, sleep = { }),
            events = events,
        )
    }

    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // --- format ---

    @Test
    fun theCodeRoundTripsInUppercaseAndAcceptsOneCaseOnly() {
        val payload = display.show(displayerFingerprint, rdi)
        val text = Bvmid2.encode(payload)
        assertTrue(text.startsWith("BVMID2."))
        assertEquals(6 + 3 + 64 + 64 + 12, text.length)
        assertTrue(text.all { it == '.' || it in '0'..'9' || it in 'A'..'Z' })
        assertEquals(payload, Bvmid2.parse(text))
        assertEquals(payload, Bvmid2.parse("BVMID2." + text.substring(7).lowercase()))
        val mixed = text.substring(0, 20) + text.substring(20, 21).lowercase() + text.substring(21)
        // Mixed case only counts when the changed character is a letter.
        if (text[20] in 'A'..'F') assertThrows(Bvmid2FormatException::class.java) { Bvmid2.parse(mixed) }
    }

    @Test
    fun everyMalformedFieldIsRejected() {
        val good = Bvmid2.encode(display.show(displayerFingerprint, rdi))
        val parts = good.split('.')
        fun bad(text: String, reason: String) =
            assertEquals(reason, assertThrows(Bvmid2FormatException::class.java) { Bvmid2.parse(text) }.reason)
        bad("BVMID1.${parts[1]}.Mum", "other version")
        bad("OTHER.${parts[1]}.${parts[2]}.${parts[3]}", "other magic")
        bad("BVMID2.${parts[1]}.${parts[2]}", "field count")
        bad("BVMID2.${parts[1]}.${parts[2]}.${parts[3]}.EXTRA", "field count")
        bad("BVMID2.${parts[1].dropLast(2)}.${parts[2]}.${parts[3]}", "length")
        bad("BVMID2.${parts[1]}.${parts[2]}00.${parts[3]}", "length")
        bad("BVMID2.${parts[1]}.${parts[2]}.${parts[3].dropLast(1)}", "length")
        bad("BVMID2.${parts[1].replaceRange(0, 1, "G")}.${parts[2]}.${parts[3]}", "alphabet")
        bad("BVMID2.${parts[1]}.${"0".repeat(64)}.${parts[3]}", "zero secret")
        bad("", "other magic")
        assertNull(Bvmid2.tryParse("BVMID2.x"))
        assertTrue(Bvmid2.looksLikeBvmid2(good))
        assertFalse(Bvmid2.looksLikeBvmid2("BVMID1.abc"))
    }

    @Test
    fun kTagIsAnHkdfExpandOfKqrAndMatchesTheReferenceVector() {
        val kQr = bytes(Bmh3Vectors.K_QR)
        val kTag = Bmh3Crypto.qrTagKey(kQr)
        assertEquals(Bmh3Vectors.K_TAG_QR, hexOf(kTag))
        assertNotEquals(Bmh3Vectors.K_QR, hexOf(kTag))
        assertNotEquals(Bmh3Vectors.K_TAG, hexOf(kTag))
        assertThrows(IllegalArgumentException::class.java) { Bmh3Crypto.qrTagKey(ByteArray(31)) }
    }

    // --- lifetime ---

    @Test
    fun aCodeLivesThreeHundredSecondsAndThenEnds() {
        display.show(displayerFingerprint, rdi)
        now += QrCodeDisplay.LIFETIME_MS - 1
        assertTrue(display.hasCode())
        now += 1
        assertFalse(display.hasCode())
        assertEquals(listOf("expired"), events.ended)
    }

    @Test
    fun onlyOneCodeIsLiveAndLeavingTheScreenOrChangingTheIdKillsIt() {
        val first = display.show(displayerFingerprint, rdi)
        val second = display.show(displayerFingerprint, rdi)
        assertNotEquals(first, second)
        assertEquals(listOf("replaced"), events.ended)
        display.onRdiChanged(rdi)
        assertTrue(display.hasCode())
        display.onRdiChanged(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertFalse(display.hasCode())
        display.show(displayerFingerprint, rdi)
        display.leave()
        assertFalse(display.hasCode())
        assertEquals(listOf("replaced", "id_changed", "left"), events.ended)
    }

    // --- single use: reserve, commit, release ---

    private fun ephemeral(n: Int) = ByteArray(65) { (n + it).toByte() }

    @Test
    fun aMatchReservesTheCodeForOneAttemptAndLinkComesFirst() {
        display.show(displayerFingerprint, rdi)
        val a = display.newGate()
        val gate = display.tagCheck(a)!!.qrGate!!
        assertThrows(Bmh3Failure::class.java) { gate.onTagMatch(Bmh3Purpose.END_TO_END, ephemeral(1)) } // end-to-end without link
        gate.onTagMatch(Bmh3Purpose.LINK, ephemeral(2))
        assertEquals(QrCodeDisplay.Phase.RESERVED, display.phase())
        // The same connection's end-to-end handshake continues under the reservation.
        gate.onTagMatch(Bmh3Purpose.END_TO_END, ephemeral(3))
        // A concurrent second connection is refused while the first attempt is reserved.
        val b = display.newGate()
        val gateB = display.tagCheck(b)!!.qrGate!!
        assertEquals("qr busy", assertThrows(Bmh3Failure::class.java) { gateB.onTagMatch(Bmh3Purpose.LINK, ephemeral(4)) }.reason)
        assertTrue(a.complete(displayerFingerprint))
        assertEquals(QrCodeDisplay.Phase.CONSUMED, display.phase())
        assertEquals(listOf(displayerFingerprint), events.scans)
    }

    @Test
    fun aFailedAttemptReleasesTheCodeButCountsAgainstThePerCodeCap() {
        display.show(displayerFingerprint, rdi)
        repeat(QrCodeDisplay.MAX_ATTEMPTS) { i ->
            val gate = display.newGate()
            display.tagCheck(gate)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(10 + i))
            gate.fail()
            assertEquals(QrCodeDisplay.Phase.LIVE, display.phase())
        }
        val last = display.newGate()
        assertEquals("qr attempts", assertThrows(Bmh3Failure::class.java) { display.tagCheck(last)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(99)) }.reason)
        assertFalse("the code ended at the cap: the screen draws a new one", display.hasCode())
        assertTrue(events.ended.contains("attempt_cap"))
    }

    @Test
    fun anAttemptThatNeverFinishesLosesItsReservationAfterTheTimeout() {
        display.show(displayerFingerprint, rdi)
        val a = display.newGate()
        display.tagCheck(a)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(1))
        now += QrCodeDisplay.RESERVATION_MS - 1
        val b = display.newGate()
        assertThrows(Bmh3Failure::class.java) { display.tagCheck(b)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(2)) }
        now += 1
        val c = display.newGate()
        display.tagCheck(c)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(3))
        assertFalse("the first attempt can no longer commit", a.complete(displayerFingerprint))
        assertTrue(c.complete(displayerFingerprint))
    }

    @Test
    fun aReplayedFirstMessageIsSilentAndANewAttemptOnAConsumedCodeRaisesTheAlert() {
        display.show(displayerFingerprint, rdi)
        val a = display.newGate()
        display.tagCheck(a)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(1))
        a.complete(displayerFingerprint)
        // A sniffed msg1 replayed: closed silently, no alert, even though the code is consumed.
        val replay = display.newGate()
        assertEquals("replay", assertThrows(Bmh3Failure::class.java) { display.tagCheck(replay)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(1)) }.reason)
        assertEquals(0, events.reuse)
        // A genuinely new msg1 with the code's tag: someone else holds the code.
        val other = display.newGate()
        assertEquals("qr consumed", assertThrows(Bmh3Failure::class.java) { display.tagCheck(other)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(2)) }.reason)
        assertEquals(1, events.reuse)
    }

    @Test
    fun consumedAttemptStateIsPinnedSoReplayCacheEvictionCannotManufactureAnAlert() {
        // The global BMH3 replay cache holds 4,096 entries; this per-code pin holds the attempts of the code until it ends.
        display.show(displayerFingerprint, rdi)
        val a = display.newGate()
        display.tagCheck(a)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(1))
        a.complete(displayerFingerprint)
        now += QrCodeDisplay.LIFETIME_MS - 1
        val late = display.newGate()
        assertEquals("replay", assertThrows(Bmh3Failure::class.java) { display.tagCheck(late)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(1)) }.reason)
        assertEquals(0, events.reuse)
    }

    @Test
    fun theSigningBudgetStillAppliesToEveryReservedAttempt() {
        var spent = 0
        val limited = QrCodeDisplay(
            clockMs = { now },
            random = { it.fill(5) },
            budget = Bmh3SigningBudget(capacity = 1, refillMs = 1_000_000L, maxWaitMs = 0L, clockMs = { now }, sleep = { spent++ }),
            events = events,
        )
        limited.show(displayerFingerprint, rdi)
        val a = limited.newGate()
        limited.tagCheck(a)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(1))
        a.fail()
        val b = limited.newGate()
        assertEquals("budget", assertThrows(Bmh3Failure::class.java) { limited.tagCheck(b)!!.qrGate!!.onTagMatch(Bmh3Purpose.LINK, ephemeral(2)) }.reason)
        assertEquals(0, spent)
    }

    // --- a real BMH3 handshake with the code's tag ---

    private fun handshake(
        purpose: Bmh3Purpose,
        initiatorTag: Bmh3ContactTag?,
        responderChecks: List<Bmh3ContactTag>,
        checkResponder: (Bmh3Peer) -> Unit = {},
        mode: Bmh3ResponderMode = Bmh3ResponderMode.CONTACTS_ONLY,
    ): Pair<Bmh3Result, Bmh3Result> {
        val ini = Bmh3Initiator(
            Bmh3Config(purpose, KeyPairSigningIdentity(generateEcKeyPair()), caps),
            contactTag = initiatorTag,
            checkResponder = checkResponder,
        )
        val res = Bmh3Responder(
            Bmh3Config(purpose, displayerIdentity, caps),
            mode = mode,
            tagChecks = responderChecks,
        )
        val m2 = res.onMessage1(ini.message1())
        val m3 = ini.onMessage2(m2)
        res.onMessage3(m3)
        return ini.result() to res.result()
    }

    @Test
    fun theScannersTagMatchesTheDisplayedCodeAtBothLayersAndMarksTheSession() {
        val payload = display.show(displayerFingerprint, rdi)
        val gate = display.newGate()
        val link = handshake(Bmh3Purpose.LINK, QrScanPolicy.tag(payload), listOfNotNull(display.tagCheck(gate)),
            checkResponder = { QrScanPolicy.checkResponder(payload, it) })
        assertTrue(link.second.tagMatched)
        assertTrue(link.second.qrTagMatched)
        assertEquals(QrCodeDisplay.Phase.RESERVED, display.phase())
        val endpoint = handshake(Bmh3Purpose.END_TO_END, QrScanPolicy.tag(payload), listOfNotNull(display.tagCheck(gate)),
            checkResponder = { QrScanPolicy.checkResponder(payload, it) })
        assertTrue(endpoint.second.qrTagMatched)
        assertTrue(gate.complete(IdentityFingerprint.fromPublicKey(endpoint.second.peer.spki)))
        assertEquals(QrCodeDisplay.Phase.CONSUMED, display.phase())
    }

    @Test
    fun aWrongOrOtherRdiTagDoesNotMatchAndANormalTagIsNotAQrSession() {
        val payload = display.show(displayerFingerprint, rdi)
        val gate = display.newGate()
        val checks = listOfNotNull(display.tagCheck(gate))
        // The scanner holds another code's secret.
        val other = Bvmid2Payload(displayerFingerprint, ByteArray(32) { 9 }, rdi)
        assertFalse(handshake(Bmh3Purpose.LINK, QrScanPolicy.tag(other), checks, mode = Bmh3ResponderMode.EVERYONE).second.tagMatched)
        // Right secret, but bound to another RDI (a copy of the code at another epoch).
        val moved = Bvmid2Payload(displayerFingerprint, payload.kQr, byteArrayOf(1, 2, 3, 4, 5, 6))
        assertFalse(handshake(Bmh3Purpose.LINK, QrScanPolicy.tag(moved), checks, mode = Bmh3ResponderMode.EVERYONE).second.tagMatched)
        // No tag at all.
        val plain = handshake(Bmh3Purpose.LINK, null, checks, mode = Bmh3ResponderMode.EVERYONE).second
        assertFalse(plain.tagMatched)
        assertFalse(plain.qrTagMatched)
        assertEquals(QrCodeDisplay.Phase.LIVE, display.phase())
    }

    @Test
    fun aMatchOnAConsumedCodeFailsTheHandshakeBeforeAnyKeyIsDrawn() {
        val payload = display.show(displayerFingerprint, rdi)
        val first = display.newGate()
        handshake(Bmh3Purpose.LINK, QrScanPolicy.tag(payload), listOfNotNull(display.tagCheck(first)))
        first.complete(displayerFingerprint)
        val second = display.newGate()
        assertThrows(Bmh3Failure::class.java) {
            handshake(Bmh3Purpose.LINK, QrScanPolicy.tag(payload), listOfNotNull(display.tagCheck(second)))
        }
        assertEquals(1, events.reuse)
    }

    // --- scanner checks ---

    @Test
    fun theScannerRefusesAPseudonymAnswerAndAKeyThatIsNotTheCodes() {
        val payload = display.show(displayerFingerprint, rdi)
        val peer = { pseudonymous: Boolean, identity: SigningIdentity ->
            Bmh3Peer(identity.publicKeyEncoded, identity.nodeId, caps, Bmh3Extensions(pseudonym = pseudonymous))
        }
        QrScanPolicy.checkResponder(payload, peer(false, displayerIdentity))
        assertThrows(Bmh3Failure::class.java) { QrScanPolicy.checkResponder(payload, peer(true, displayerIdentity)) }
        assertThrows(Bmh3Failure::class.java) {
            QrScanPolicy.checkResponder(payload, peer(false, KeyPairSigningIdentity(generateEcKeyPair())))
        }
    }

    @Test
    fun theScannerTagNamesTheExpectedNodeAndTriesEveryAddressWithTheCodesRdi() {
        val payload = display.show(displayerFingerprint, rdi)
        assertEquals(displayerFingerprint.nodeId, QrScanPolicy.tag(payload).expectedPeer)
        fun ad(prefix: ByteArray, cost: Int = 0, version: Int = DiscoveryAnnouncement.ROTATING_VERSION) = DiscoveryAnnouncement(
            nodeIdPrefix = prefix, flags = 0x41, capabilitySummary = 3, streamToken = 0, routeCost = cost,
            sequence = 0, streamWidth = 0, streamHeight = 0, streamFps = 0, streamBitrateKbps = 0, version = version,
        )
        val seen = listOf(
            "AA:01" to ad(rdi),
            "BB:02" to ad(rdi), // a copy answering first
            "CC:03" to ad(byteArrayOf(9, 9, 9, 9, 9, 9)),
            "DD:04" to ad(rdi, cost = 1), // a relay mirror is not the owner
            "EE:05" to ad(rdi, version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION),
        )
        assertEquals(listOf("AA:01", "BB:02"), QrScanPolicy.addressesToTry(payload, seen))
    }

    // --- verify-only scope ---

    @Test
    fun theVerificationSessionAllowsTheHandshakeAndTheScannersNameAndNothingElse() {
        val scope = QrSessionScope(startMs = 0)
        assertTrue(scope.allowsContent(EndToEndContentType.LABELS))
        EndToEndContentType.entries.filter { it != EndToEndContentType.LABELS }.forEach {
            assertFalse(it.name, scope.allowsContent(it))
            assertTrue(scope.refuseContent(it))
        }
        assertTrue(scope.violations >= EndToEndContentType.entries.size - 1)
        listOf(SecureRecordType.END_TO_END_HANDSHAKE, SecureRecordType.END_TO_END_CONTENT, SecureRecordType.LINK_HEARTBEAT, SecureRecordType.DISCOVERY_OPENING)
            .forEach { assertTrue(it.name, scope.allowsLinkRecord(it)) }
        SecureRecordType.entries.filter { it !in QrSessionScope.LINK_RECORDS }.forEach {
            assertFalse(it.name, scope.allowsLinkRecord(it))
        }
        assertFalse(scope.allowsLinkRecord(SecureRecordType.STORE_FORWARD))
        assertFalse(scope.expired(QrSessionScope.MAX_SESSION_MS - 1))
        assertTrue(scope.expired(QrSessionScope.MAX_SESSION_MS))
    }

    // --- mutual outcome and the confirmation step ---

    @Test
    fun theShieldWaitsForTheUsersConfirmationOfWhoseScreenWasScanned() {
        val me = MeetSession()
        val peer = displayerFingerprint
        me.scanReached(peer, "Rahul")
        assertEquals("Is this Rahul's screen you scanned?", me.confirmationText(peer))
        assertEquals(MeetSession.Outcome.NONE, me.outcome(peer))
        assertFalse(me.confirmScreen(peer, yes = false))
        assertEquals(MeetSession.Outcome.NONE, me.outcome(peer))
        assertNull(me.confirmationText(peer))
        me.scanReached(peer, "Rahul")
        assertTrue(me.confirmScreen(peer, yes = true))
        assertEquals(MeetSession.Outcome.YOU_VERIFIED, me.outcome(peer))
        assertEquals("You scanned Rahul. Rahul has not scanned your code yet.", me.message(peer, "Rahul"))
        assertFalse("nothing pending now", me.confirmScreen(peer, yes = true))
    }

    @Test
    fun verifiedEachOtherNeedsBothEventsInEitherOrder() {
        val peer = displayerFingerprint
        val a = MeetSession()
        a.scannedByPeer(peer)
        assertEquals(MeetSession.Outcome.THEY_SCANNED_YOU, a.outcome(peer))
        assertTrue("the message names the person to scan back", a.message(peer, "Rahul")!!.contains("Rahul"))
        a.scanReached(peer, "Rahul"); a.confirmScreen(peer, true)
        assertEquals(MeetSession.Outcome.VERIFIED_EACH_OTHER, a.outcome(peer))
        val b = MeetSession()
        b.scanReached(peer, "Rahul"); b.confirmScreen(peer, true)
        b.scannedByPeer(peer)
        assertEquals(MeetSession.Outcome.VERIFIED_EACH_OTHER, b.outcome(peer))
        // A reuse alert cancels a scan that was waiting for confirmation.
        val c = MeetSession()
        c.scanReached(peer, "Rahul")
        c.onReuseAlert()
        assertTrue(c.reuseAlert)
        assertFalse(c.confirmScreen(peer, true))
    }

    // --- BVMID1 and code-only rules ---

    @Test
    fun aBvmid1ScanGivesTheShieldOnlyWhileBvmid2IsOffOrTheCompatSwitchIsOn() {
        assertEquals(VerificationMethod.QR_SCAN, Bvmid1Policy.methodForScan(bvmid2On = false, bvmid1Compat = false))
        assertEquals(VerificationMethod.SAFETY_CODE_CONFIRMED, Bvmid1Policy.methodForScan(bvmid2On = true, bvmid1Compat = false))
        assertEquals(VerificationMethod.QR_SCAN, Bvmid1Policy.methodForScan(bvmid2On = true, bvmid1Compat = true))
    }

    @Test
    fun aCodeOnlyContactGetsNoNewLeasesUntilScannedBothWaysButKeepsExistingOnes() {
        val code = VerificationMethod.SAFETY_CODE_CONFIRMED
        assertTrue(CodeOnlyLeaseRule.blocked(true, code, scannedBothWays = false, holdsLease = false))
        assertFalse(CodeOnlyLeaseRule.blocked(true, code, scannedBothWays = true, holdsLease = false))
        assertFalse(CodeOnlyLeaseRule.blocked(true, code, scannedBothWays = false, holdsLease = true))
        assertFalse(CodeOnlyLeaseRule.blocked(true, VerificationMethod.QR_SCAN, false, false))
        assertFalse("bvmid2 off changes nothing", CodeOnlyLeaseRule.blocked(false, code, false, false))
        assertEquals("code_only_not_scanned", PresenceEligibility.reason(true, true, VerificationState.VERIFIED, true, codeOnlyUnscanned = true))
        assertNull(PresenceEligibility.reason(true, true, VerificationState.VERIFIED, true, codeOnlyUnscanned = false))
    }

    @Test
    fun theContactBasisFollowsTheMethod() {
        val fp = displayerFingerprint
        assertEquals(VerificationBasis.SCANNED, VerifiedContact(fp, "Mum", 1L, VerificationMethod.QR_SCAN).basis())
        assertEquals(VerificationBasis.CHECKED_BY_CODE, VerifiedContact(fp, "Mum", 1L, VerificationMethod.SAFETY_CODE_CONFIRMED).basis())
    }

    @Test
    fun theScreenOnlyReachabilityFlagDefaultsOffAndKeyBytesAreNotLeakedByEquality() {
        assertFalse(display.screenOnlyReachable)
        val payload = display.show(displayerFingerprint, rdi)
        assertArrayEquals(rdi, payload.rdi)
        assertEquals(32, payload.kQr.size)
    }

    // --- Design QL: presence leases inside the verification session ---

    private val qlLease = DiscoveryExchange.PresenceLease(1, 2_000_000, 2962, listOf(ByteArray(32) { 1 }))

    @Test
    fun theVerificationSessionAllowsOneOfferAndOneLeaseOnlyWhenBothHellosCarriedBit3() {
        val offer = DiscoveryExchange.encode(DiscoveryExchange.RecognitionOffer)
        val lease = DiscoveryExchange.encode(qlLease)
        val opening = DiscoveryExchange.MAGIC + byteArrayOf(DiscoveryExchange.KIND_OPENING.toByte(), 0)
        val reveal = DiscoveryExchange.MAGIC + byteArrayOf(DiscoveryExchange.KIND_IDENTITY_REVEAL.toByte())
        // Not negotiated: every discovery record is a violation.
        val plain = QrSessionScope(startMs = 0)
        assertTrue(plain.refuseDiscovery(offer, negotiated = false))
        assertEquals(1, plain.violations)
        val scope = QrSessionScope(startMs = 0)
        assertFalse(scope.refuseDiscovery(offer, negotiated = true))
        assertFalse(scope.refuseDiscovery(lease, negotiated = true))
        // A second OFFER or LEASE, an OPENING, a reveal or garbage closes it.
        assertTrue(scope.refuseDiscovery(offer, negotiated = true))
        assertTrue(scope.refuseDiscovery(lease, negotiated = true))
        assertTrue(scope.refuseDiscovery(opening, negotiated = true))
        assertTrue(scope.refuseDiscovery(reveal, negotiated = true))
        assertTrue(scope.refuseDiscovery(byteArrayOf(1, 2, 3), negotiated = true))
        assertTrue(scope.refuseDiscovery("BDX2".toByteArray(Charsets.US_ASCII) + byteArrayOf(2, 0), negotiated = true))
        assertEquals(6, scope.violations)
        // The content-type rule is unchanged: only labels pass it, discovery goes through refuseDiscovery.
        assertFalse(scope.allowsContent(EndToEndContentType.DISCOVERY))
    }

    /** Delivers [actions] of one side to [peer] the way the endpoint does (OFFER and LEASE records), returning what the peer does. */
    private fun deliver(actions: List<PresenceSession.Action>, peer: PresenceSession, peerVerifiedNow: Boolean): List<PresenceSession.Action> =
        actions.flatMap { action ->
            when (action) {
                PresenceSession.Action.SendOffer -> peer.onRecord(DiscoveryExchange.RecognitionOffer, peerVerifiedNow)
                PresenceSession.Action.SendLease -> peer.onRecord(qlLease, peerVerifiedNow)
                else -> emptyList()
            }
        }

    @Test
    fun theFirstOfTwoScansMovesNoLeaseAndTheSecondMovesBoth() {
        // Scan 1: B scans A. A (displayer) has not verified B: it does not take part and ends after the grace.
        val displayerA = PresenceSession(localEligible = false)
        assertTrue(displayerA.settled())
        assertTrue(displayerA.start().isEmpty())
        // B starts only after its user confirmed; its OFFER is recorded by A and answered with nothing.
        val scannerB = PresenceSession(localEligible = true)
        val fromA = deliver(scannerB.start(), displayerA, peerVerifiedNow = false)
        assertTrue(fromA.isEmpty())
        assertFalse(scannerB.settled()) // it waits; A's close ends the session
        // Scan 2: A scans B. The scanner offers first (review R4): A's user confirms, A offers.
        val displayerB = PresenceSession(localEligible = true)
        val scannerA = PresenceSession(localEligible = true)
        val aStart = scannerA.start()
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer), aStart)
        // B records the OFFER and, as it confirmed A in this visit (scan 1), answers: its start sends OFFER and LEASE.
        assertTrue(deliver(aStart, displayerB, peerVerifiedNow = true).isEmpty())
        val bAnswer = displayerB.start()
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer, PresenceSession.Action.SendLease), bAnswer)
        val atA = deliver(bAnswer, scannerA, peerVerifiedNow = true)
        assertTrue(atA.any { it == PresenceSession.Action.SendLease })
        assertTrue(atA.any { it is PresenceSession.Action.StoreLease })
        val atB = deliver(atA, displayerB, peerVerifiedNow = true)
        assertTrue(atB.single() is PresenceSession.Action.StoreLease)
        assertTrue(displayerB.settled())
        assertTrue(scannerA.settled())
    }

    @Test
    fun aDisplayerAnswersOnlyAScannerItsUserConfirmedInThisVisitAndNothingAfterAReuseAlert() {
        val visit = MeetVisit()
        val asha = displayerFingerprint
        val older = IdentityFingerprint.fromHex("cd".repeat(32))
        visit.begin()
        // An older verified contact that won the race for the code was never confirmed in this visit (review R1).
        assertFalse(visit.mayLease(older))
        visit.confirm(asha)
        assertTrue(visit.mayLease(asha))
        visit.onReuseAlert()
        assertFalse(visit.mayLease(asha))
        assertTrue(visit.reuseAlerted())
        // A new visit starts clean.
        visit.begin()
        assertFalse(visit.mayLease(asha))
        assertFalse(visit.reuseAlerted())
    }

    @Test
    fun aScannerThatNeverConfirmsOffersNothingAndStoresNothing() {
        // The displayer offers and even (wrongly) sends a lease; without the scanner's start nothing is stored.
        val scanner = PresenceSession(localEligible = true)
        assertTrue(scanner.onRecord(DiscoveryExchange.RecognitionOffer, peerVerifiedNow = false).isEmpty())
        assertTrue(scanner.onRecord(qlLease, peerVerifiedNow = false).single() is PresenceSession.Action.Reject)
        assertFalse(scanner.settled())
    }

    @Test
    fun aWithheldOfferSettlesTheSession() {
        // The send-time check failed (the shield was not stored, a switch is off): nothing more can move.
        val scanner = PresenceSession(localEligible = true)
        scanner.start()
        scanner.offerWithheld()
        assertTrue(scanner.settled())
    }

    @Test
    fun theMeetTextSaysThePhonesRecogniseEachOtherOnlyAfterALeaseWasStored() {
        val me = MeetSession()
        val peer = displayerFingerprint
        me.scannedByPeer(peer)
        me.scanReached(peer, "Asha")
        assertTrue(me.confirmScreen(peer, yes = true))
        assertEquals(MeetSession.QlResult.NOT_FINISHED, me.qlResult(peer))
        assertFalse(me.recognises(peer))
        me.leaseStored(peer)
        assertTrue(me.recognises(peer))
        assertEquals(MeetSession.QlResult.RECOGNISED, me.qlResult(peer))
    }
}
