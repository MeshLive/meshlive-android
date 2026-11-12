package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire formats of relayed calls: reach pages, relay setup records, capability bits. */
class RelayedCallsWireTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val target = bytes("010203040506")
    private val relay = bytes("0a0b0c0d0e0f")
    private val id = bytes("0102030405060708")

    private fun callOffer(
        prefix: ByteArray = target,
        flags: Int = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        summary: Int = NodeCapabilities.CODEC_VP9,
        cost: Int = 0,
        version: Int = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
    ) = DiscoveryAnnouncement(prefix, flags, summary, 0, cost, 0, version = version)

    // ----- reach page -----

    @Test
    fun reachPageHasTheDocumentedLayout() {
        val page = ReachPage(relay, ReachPageCodec.FLAG_COC or ReachPageCodec.FLAG_IDLE, 0, 1, listOf(ReachPageEntry(target, 1, 3, bytes("0102"))))
        val encoded = ReachPageCodec.encode(page)
        assertEquals("040a0b0c0d0e0f0901010203040506230102", hex(encoded))
        assertEquals(18, encoded.size)
        assertEquals(page, ReachPageCodec.decode(encoded))
        assertEquals(6_000L, ReachPageCodec.decode(encoded).entries[0].ageMs)
        // Old scanners decode only 22-byte announcements, so they reject a reach page.
        assertTrue(runCatching { DiscoveryAnnouncementCodec.decode(encoded) }.isFailure)
        assertTrue(ReachPageCodec.isReachPage(encoded))
    }

    @Test
    fun twoEntriesFillTheAdvertisement() {
        val second = ReachPageEntry(bytes("a1a2a3a4a5a6"), 2, 31, bytes("0a0b"))
        val page = ReachPage(relay, ReachPageCodec.FLAG_GATT, 1, 2, listOf(ReachPageEntry(target, 1, 0, bytes("0102")), second))
        val encoded = ReachPageCodec.encode(page)
        assertEquals(27, encoded.size)
        // 27 bytes + the 4-byte manufacturer-data header fill a 31-byte legacy scan response.
        assertEquals(31, encoded.size + 4)
        assertEquals(page, ReachPageCodec.decode(encoded))
        assertEquals(62_000L, ReachPageCodec.decode(encoded).entries[1].ageMs)
    }

    @Test
    fun reachPageDecoderRejectsMalformedPages() {
        val good = ReachPageCodec.encode(
            ReachPage(relay, ReachPageCodec.FLAG_COC, 0, 1, listOf(ReachPageEntry(target, 1, 0, bytes("0102")))),
        )
        fun mutate(index: Int, value: Int) = good.copyOf().also { it[index] = value.toByte() }
        assertTrue(runCatching { ReachPageCodec.decode(good.copyOf(17)) }.isFailure)
        assertTrue(runCatching { ReachPageCodec.decode(good + byteArrayOf(0)) }.isFailure)
        assertTrue(runCatching { ReachPageCodec.decode(mutate(0, 3)) }.isFailure) // title page version
        assertTrue(runCatching { ReachPageCodec.decode(mutate(7, 0x04)) }.isFailure) // reserved flag bit 2
        assertTrue(runCatching { ReachPageCodec.decode(mutate(7, 0x10)) }.isFailure) // reserved flag bit 4
        assertTrue(runCatching { ReachPageCodec.decode(mutate(8, 0x00)) }.isFailure) // count 0
        assertTrue(runCatching { ReachPageCodec.decode(mutate(8, 0x15)) }.isFailure) // count 5
        assertTrue(runCatching { ReachPageCodec.decode(mutate(8, 0x11)) }.isFailure) // index == count
        assertTrue(runCatching { ReachPageCodec.decode(mutate(15, 0x03)) }.isFailure) // cost 0
        assertTrue(runCatching { ReachPageCodec.decode(mutate(15, (5 shl 5))) }.isFailure) // cost 5
        // A target equal to the relay prefix is rejected.
        val self = good.copyOf().also { relay.copyInto(it, 9) }
        assertTrue(runCatching { ReachPageCodec.decode(self) }.isFailure)
        // Duplicate targets inside one page are rejected.
        val twoSame = ReachPageCodec.encode(
            ReachPage(relay, 1, 0, 1, listOf(ReachPageEntry(target, 1, 0, bytes("0102")), ReachPageEntry(bytes("a1a2a3a4a5a6"), 1, 0, bytes("0102")))),
        ).also { target.copyInto(it, 18) }
        assertTrue(runCatching { ReachPageCodec.decode(twoSame) }.isFailure)
    }

    @Test
    fun pagesSplitTwoEntriesPerPageAndStopAtTwoPages() {
        val entries = (1..6).map { ReachPageEntry(byteArrayOf(it.toByte(), 2, 3, 4, 5, 6), 1, it, bytes("0102")) }
        val pages = ReachPageCodec.pages(relay, ReachPageCodec.FLAG_COC, entries)
        assertEquals(2, pages.size)
        val decoded = pages.map(ReachPageCodec::decode)
        assertEquals(listOf(0, 1), decoded.map { it.index })
        assertTrue(decoded.all { it.count == 2 && it.entries.size == 2 })
        assertEquals(1, ReachPageCodec.pages(relay, ReachPageCodec.FLAG_COC, entries.take(1)).size)
    }

    @Test
    fun ageUnitsRoundUpToTwoSecondsAndCapAtSixtyTwo() {
        assertEquals(0, ReachPageCodec.ageUnitsFor(0))
        assertEquals(1, ReachPageCodec.ageUnitsFor(1))
        assertEquals(1, ReachPageCodec.ageUnitsFor(2_000))
        assertEquals(2, ReachPageCodec.ageUnitsFor(2_001))
        assertEquals(31, ReachPageCodec.ageUnitsFor(62_000))
        assertNull(ReachPageCodec.ageUnitsFor(62_001))
    }

    // ----- relay setup records -----

    @Test
    fun setupReadyAndFailHaveTheDocumentedLayout() {
        val setup = RelaySetupCodec.encode(RelaySetupMessage.Setup(target, 2, 20_000, id))
        assertEquals("42525331" + "01" + "010203040506" + "02" + "00004e20" + "0102030405060708", hex(setup))
        assertEquals(RelaySetupCodec.SETUP_BYTES, setup.size)
        val ready = RelaySetupCodec.encode(RelaySetupMessage.Ready(id, 2))
        assertEquals("42525331" + "02" + "0102030405060708" + "02", hex(ready))
        assertEquals(RelaySetupCodec.REPLY_BYTES, ready.size)
        val fail = RelaySetupCodec.encode(RelaySetupMessage.Fail(id, RelaySetupFailure.REFUSED))
        assertEquals("42525331" + "03" + "0102030405060708" + "05", hex(fail))

        val decodedSetup = RelaySetupCodec.decode(setup) as RelaySetupMessage.Setup
        assertArrayEquals(target, decodedSetup.target)
        assertEquals(2, decodedSetup.budget)
        assertEquals(20_000, decodedSetup.remainingMs)
        assertArrayEquals(id, decodedSetup.setupId)
        val decodedReady = RelaySetupCodec.decode(ready) as RelaySetupMessage.Ready
        assertEquals(2, decodedReady.relays)
        val decodedFail = RelaySetupCodec.decode(fail) as RelaySetupMessage.Fail
        assertEquals(RelaySetupFailure.REFUSED, decodedFail.reason)
    }

    @Test
    fun setupDecoderRejectsEverythingOutsideTheRules() {
        val setup = RelaySetupCodec.encode(RelaySetupMessage.Setup(target, 2, 20_000, id))
        fun mutate(index: Int, value: Int) = setup.copyOf().also { it[index] = value.toByte() }
        fun bad(bytes: ByteArray) = runCatching { RelaySetupCodec.decode(bytes) }.isFailure
        assertTrue(bad(ByteArray(0)))
        assertTrue(bad(setup.copyOf(23)))
        assertTrue(bad(setup + byteArrayOf(0)))
        assertTrue(bad(mutate(0, 0x43))) // magic
        assertTrue(bad(mutate(4, 4))) // message 4
        assertTrue(bad(mutate(11, 0))) // budget 0
        assertTrue(bad(mutate(11, 5))) // budget 5
        assertTrue(bad(setup.copyOf().also { ByteArray(6).copyInto(it, 5) })) // zero target
        assertTrue(bad(setup.copyOf().also { it[12] = 0; it[13] = 0; it[14] = 0x03; it[15] = 0xe7.toByte() })) // 999 ms
        assertTrue(bad(setup.copyOf().also { it[12] = 0; it[13] = 0; it[14] = 0x4e; it[15] = 0x21 })) // 20001 ms
        val ready = RelaySetupCodec.encode(RelaySetupMessage.Ready(id, 2))
        assertTrue(bad(ready.copyOf().also { it[13] = 0 })) // relays 0
        assertTrue(bad(ready.copyOf().also { it[13] = 5 })) // relays 5
        assertTrue(bad(ready + byteArrayOf(0)))
        val fail = RelaySetupCodec.encode(RelaySetupMessage.Fail(id, RelaySetupFailure.LOOP))
        assertTrue(bad(fail.copyOf().also { it[13] = 7 })) // unknown reason
        assertTrue(bad(fail.copyOf().also { it[13] = 0 }))
        assertTrue(runCatching { RelaySetupCodec.encode(RelaySetupMessage.Setup(target, 0, 20_000, id)) }.isFailure)
        assertTrue(runCatching { RelaySetupCodec.encode(RelaySetupMessage.Setup(target, 1, 1_999, id)) }.isFailure)
    }

    @Test
    fun relaySetupIsAnOptInRecordType() {
        // A reader that has not opted in rejects type 12 as unknown, exactly as an old build does.
        val key = ByteArray(32) { 5 }
        val bytes = java.io.ByteArrayOutputStream()
        SecureRecordWriter(bytes, key).apply {
            write(SecureRecordType.RELAY_SETUP, RelaySetupCodec.encode(RelaySetupMessage.Ready(id, 1)))
            flush()
        }
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            SecureRecordReader(java.io.ByteArrayInputStream(bytes.toByteArray()), key).read()
        }
        assertEquals("Unknown secure record type 12", error.message)
        val optedIn = SecureRecordReader(java.io.ByteArrayInputStream(bytes.toByteArray()), key).apply { acceptRelaySetup = true }
        assertEquals(SecureRecordType.RELAY_SETUP, optedIn.read().type)
        assertEquals(12, SecureRecordType.RELAY_SETUP.wireValue)
    }

    // ----- capability bits and offers -----

    @Test
    fun switchedOffNothingOnAirOrLinkChanges() {
        val base = callOffer()
        assertEquals(base, RelayedCalls.offerFor(base, consentOn = false, helperActive = false))
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0)
        assertEquals(caps, RelayedCalls.linkCapabilities(caps, featureOn = false, helperActive = true))
        assertFalse(RelayedCallsFeature.enabled)
    }

    @Test
    fun consentAndHelperBitsAreExactlyDocumented() {
        val base = callOffer(summary = 0x03)
        val consenting = RelayedCalls.offerFor(base, consentOn = true, helperActive = false)
        assertEquals(0x13, consenting.capabilitySummary)
        assertEquals(base.flags, consenting.flags)
        val helper = RelayedCalls.offerFor(base, consentOn = false, helperActive = true)
        assertEquals(0x03, helper.capabilitySummary)
        assertEquals(base.flags or DiscoveryAnnouncement.FLAG_RELAY_CAPABLE, helper.flags)
        assertTrue(RelayedCalls.consents(consenting))
        assertFalse(RelayedCalls.consents(base))
        assertTrue(RelayedCalls.isRelayHelperOffer(helper))
        assertFalse(RelayedCalls.isRelayHelperOffer(consenting))
        // The bit survives the 22-byte announcement codec unchanged.
        val roundTrip = DiscoveryAnnouncementCodec.decode(DiscoveryAnnouncementCodec.encode(consenting))
        assertEquals(0x13, roundTrip.capabilitySummary)
        assertEquals(consenting.flags, roundTrip.flags)
        assertTrue(RelayedCalls.consents(roundTrip))
    }

    @Test
    fun consentIsNeverSetOnRotatingOrRelayedOrStreamOffers() {
        val rotating = callOffer(version = DiscoveryAnnouncement.ROTATING_VERSION)
        assertEquals(rotating, RelayedCalls.offerFor(rotating, consentOn = true, helperActive = true))
        val relayed = callOffer(cost = 1)
        assertEquals(relayed, RelayedCalls.offerFor(relayed, consentOn = true, helperActive = true))
        val stream = DiscoveryAnnouncement(
            target, DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE or DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS,
            3, -0x7ffffff0, 0, 1,
        )
        assertEquals(stream, RelayedCalls.offerFor(stream, consentOn = true, helperActive = true))
        assertFalse(RelayedCalls.consents(callOffer(summary = 0x10, version = DiscoveryAnnouncement.ROTATING_VERSION)))
    }

    @Test
    fun linkHelloBitsFollowTheHelperState() {
        val caps = NodeCapabilities(NodeCapabilities.TRANSPORT_BLE_L2CAP, 3, 3, 500, 0)
        val caller = RelayedCalls.linkCapabilities(caps, featureOn = true, helperActive = false)
        assertEquals(caps.transportMask or 0x10, caller.transportMask)
        assertEquals(0, caller.maxChildren)
        assertTrue(RelayedCalls.linkUnderstandsSetup(caller))
        assertFalse(RelayedCalls.linkAcceptsSetup(caller))
        val helper = RelayedCalls.linkCapabilities(caps, featureOn = true, helperActive = true)
        assertEquals(caps.transportMask or 0x10 or 0x40, helper.transportMask)
        assertEquals(1, helper.maxChildren)
        assertTrue(RelayedCalls.linkAcceptsSetup(helper))
        // A DEBUG relay admits a helper as a relay child (existing rule) and rejects a plain caller.
        val offer = RelayedCalls.routedAnnouncement(target, 1, callOffer(prefix = relay))
        assertNull(PrivateCallRelayChain.childProblem(offer, relay, helper.maxChildren, bytes("111111111111"), true, helper.transportMask))
        assertTrue(PrivateCallRelayChain.childProblem(offer, relay, caller.maxChildren, bytes("111111111111"), true, caller.transportMask) != null)
    }

    @Test
    fun routedAnnouncementMatchesAMirroredRelayOffer() {
        val gattHop = callOffer(prefix = relay, flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
            DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or DiscoveryAnnouncement.FLAG_GATT_TRANSPORT)
        val routed = RelayedCalls.routedAnnouncement(target, 2, gattHop)
        assertArrayEquals(target, routed.nodeIdPrefix)
        assertEquals(2, routed.routeCost)
        assertTrue(routed.isPrivateCallOffer())
        assertEquals(BluetoothTransportOption.BLE_GATT, routed.transportOption())
        assertTrue(routed.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0)
        assertEquals(2, PrivateCallRelayChain.callerRelayHops(routed.routeCost))
        // The target's codecs are unknown along a route; both are assumed and the hello decides.
        assertEquals(NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1, routed.capabilitySummary)
        val direct = RelayedCalls.routedAnnouncement(target, 0, callOffer(prefix = target))
        assertEquals(0, direct.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE)
    }

    @Test
    fun nextHopDeadlineShrinksAndEndsBelowTwoSeconds() {
        assertEquals(18_500, RelayedCalls.nextHopRemainingMs(20_000, 500))
        assertEquals(2_000, RelayedCalls.nextHopRemainingMs(5_000, 2_000))
        assertNull(RelayedCalls.nextHopRemainingMs(5_000, 2_001))
        assertNull(RelayedCalls.nextHopRemainingMs(2_000, 0))
    }

    @Test
    fun featureResolutionFollowsTheOtherExperiments() {
        val spec = RelayedCallsFeature.spec
        assertTrue(FeatureCatalog.resolve(spec, true, { it == "test_relayed_calls" }, { false }, advancedSettings = false))
        assertFalse(FeatureCatalog.resolve(spec, true, { false }, { true }, advancedSettings = true))
        assertTrue(FeatureCatalog.resolve(spec, false, { false }, { if (it == RelayedCallsFeature.PREF) true else null }, advancedSettings = true))
        // Design decision 2026-11-06 (Apple gap audit 2, Q2): the switch is an Experiments row shown with or without Manual
        // video settings, so a stored choice counts either way; a stored off stays off.
        assertTrue(FeatureCatalog.resolve(spec, false, { true }, { true }, advancedSettings = false))
        assertFalse(FeatureCatalog.resolve(spec, false, { true }, { if (it == RelayedCallsFeature.PREF) false else null }, advancedSettings = false))
        assertEquals(2, RelayedCallsFeature.resolveMaxRelays(false, 4))
        assertEquals(4, RelayedCallsFeature.resolveMaxRelays(true, 4))
        assertEquals(2, RelayedCallsFeature.resolveMaxRelays(true, 5))
        assertEquals(2, RelayedCallsFeature.resolveMaxRelays(true, 0))
    }

    @Test
    fun rotatingIdsExcludeHelpingAndConsentOnlyWithoutTheCompatibility() {
        // `test_relayed_rotating=false` keeps the earlier exclusion; the default lets both experiments work
        // together on role-0 RDIs (ReachRotatingTest covers the default).
        val before = Triple(RelayedCallsFeature.enabled, RelayedCallsFeature.helpEnabled, RelayedCallsFeature.consentEnabled)
        val compat = RelayedCallsFeature.rotatingCompat
        try {
            RelayedCallsFeature.enabled = true
            RelayedCallsFeature.helpEnabled = true
            RelayedCallsFeature.consentEnabled = true
            RelayedCallsFeature.rotatingCompat = false
            assertTrue(RelayedCallsFeature.helperAllowed(rotatingIdsOn = false))
            assertTrue(RelayedCallsFeature.consentAllowed(rotatingIdsOn = false))
            assertFalse(RelayedCallsFeature.helperAllowed(rotatingIdsOn = true))
            assertFalse(RelayedCallsFeature.consentAllowed(rotatingIdsOn = true))
            RelayedCallsFeature.helpEnabled = false
            assertFalse(RelayedCallsFeature.helperAllowed(rotatingIdsOn = false))
        } finally {
            RelayedCallsFeature.enabled = before.first
            RelayedCallsFeature.helpEnabled = before.second
            RelayedCallsFeature.consentEnabled = before.third
            RelayedCallsFeature.rotatingCompat = compat
        }
    }
}
