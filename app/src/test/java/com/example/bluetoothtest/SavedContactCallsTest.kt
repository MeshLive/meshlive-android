package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calling a saved contact: expiring codes by default, leases in the call that verifies, Recent rows, names, hints, Go live title. */
class SavedContactCallsTest {
    private val lease = DiscoveryExchange.PresenceLease(1, 1_990_656L, 2962, listOf(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
    private val fingerprint = "a15a15a15a15" + "ab".repeat(26)

    /** Delivers [actions] of one side to [peer]; returns the peer's resulting actions. */
    private fun deliver(actions: List<PresenceSession.Action>, peer: PresenceSession): List<PresenceSession.Action> =
        actions.flatMap { action ->
            when (action) {
                PresenceSession.Action.SendOffer -> peer.onRecord(DiscoveryExchange.RecognitionOffer, true)
                PresenceSession.Action.SendLease -> peer.onRecord(lease, true)
                else -> emptyList()
            }
        }

    // ----- expiring codes are the default (only a BVMID2 scan moves a lease) -----

    @Test
    fun expiringCodesShipOnAndOlderCodesStayACompatSwitch() {
        assertTrue(NamingFeatureDefaults.BVMID2)
        assertTrue(NamingFeatures.BVMID2.defaultEnabled)
        assertFalse(NamingFeatureDefaults.BVMID1_COMPAT)
        assertEquals(listOf("bvmid2"), NamingFeatures.BVMID1_COMPAT.dependsOn)
    }

    // ----- leases in the call in which the user verifies -----

    @Test
    fun aSideThatVerifiesDuringTheCallAnswersTheOfferItAlreadyReceived() {
        // A already verified B and offered at the answer; B had not verified A, so its session was built ineligible.
        val a = PresenceSession(localEligible = true)
        val b = PresenceSession(localEligible = false)
        assertTrue(deliver(a.start(), b).isEmpty())
        assertTrue(b.start().isEmpty())
        // B's user confirms the safety code in the call: B becomes eligible and starts, answering A's earlier OFFER.
        assertTrue(b.becomeEligible())
        val bStart = b.start()
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer, PresenceSession.Action.SendLease), bStart)
        val atA = deliver(bStart, a)
        assertTrue(atA.contains(PresenceSession.Action.SendLease))
        assertTrue(atA.any { it is PresenceSession.Action.StoreLease })
        assertTrue(deliver(atA, b).single() is PresenceSession.Action.StoreLease)
        assertTrue(a.settled() && b.settled())
    }

    @Test
    fun twoSidesThatBothVerifyDuringTheCallExchangeBothLeases() {
        val a = PresenceSession(localEligible = false)
        val b = PresenceSession(localEligible = false)
        assertTrue(a.start().isEmpty() && b.start().isEmpty())
        assertTrue(a.becomeEligible())
        assertTrue(deliver(a.start(), b).isEmpty()) // B records the OFFER, still ineligible
        assertTrue(b.becomeEligible())
        val atA = deliver(b.start(), a)
        assertTrue(atA.any { it is PresenceSession.Action.StoreLease })
        assertTrue(deliver(atA, b).single() is PresenceSession.Action.StoreLease)
    }

    @Test
    fun becomingEligibleChangesOnlyAnIneligibleSessionOnce() {
        val eligible = PresenceSession(localEligible = true)
        assertFalse(eligible.becomeEligible())
        val late = PresenceSession(localEligible = false)
        assertFalse(late.eligible)
        assertTrue(late.becomeEligible())
        assertTrue(late.eligible)
        assertFalse(late.becomeEligible())
        // Not yet answered: nothing is sent until the answer starts it, then the ordinary rules apply.
        assertEquals(listOf<Any>(PresenceSession.Action.SendOffer), late.start())
        assertTrue(late.start().isEmpty())
    }

    // ----- Recent: a stranger verified later becomes the contact's row -----

    @Test
    fun aVerifiedStrangersRowTakesTheirPrefixAndFingerprintAndKeepsItsPlace() {
        val prefix = fingerprint.take(12)
        val existing = listOf(
            RecentCall("", video = true, atMs = 9, strangerName = "gold fox"),
            RecentCall("9c8d7e6f5a40", video = false, atMs = 7),
            RecentCall(prefix, video = false, atMs = 3, fingerprintHex = fingerprint),
        )
        val promoted = requireNotNull(RecentCalls.promote(existing, "gold fox", fingerprint.uppercase()))
        assertEquals(listOf(prefix, "9c8d7e6f5a40", prefix), promoted.map { it.nodePrefixHex })
        assertEquals(RecentCall(prefix, video = true, atMs = 9, fingerprintHex = fingerprint), promoted.first())
        assertEquals(promoted, RecentCalls.decode(RecentCalls.encode(promoted)))
        assertNull(RecentCalls.promote(existing, "blue otter", fingerprint))
        assertNull(RecentCalls.promote(existing, "gold fox", "not-a-fingerprint"))
    }

    @Test
    fun strangerLinksStayInMemoryOncePerFingerprint() {
        StrangerRecentLinks.clear()
        StrangerRecentLinks.remember(fingerprint.uppercase(), "gold fox")
        assertEquals("gold fox", StrangerRecentLinks.take(fingerprint))
        assertNull(StrangerRecentLinks.take(fingerprint))
        repeat(RecentCalls.MAX_ENTRIES + 2) { StrangerRecentLinks.remember("%064x".format(it), "gold fox") }
        assertNull("the oldest link is dropped", StrangerRecentLinks.take("%064x".format(0)))
        assertEquals("gold fox", StrangerRecentLinks.take("%064x".format(RecentCalls.MAX_ENTRIES + 1)))
        StrangerRecentLinks.clear()
    }

    // ----- names a stranger shared reach "Save contact as" after the reveal -----

    @Test
    fun aThrowawaysSessionLabelsMoveToTheRevealedIdentity() {
        val throwaway = "0a0b0c0d0e0f"
        val real = fingerprint.take(12)
        SessionPeerLabels.clearAll()
        SessionPeerLabels.update(throwaway) { it.copy(selfName = "Rahul", streamTitle = "Dinner") }
        SessionPeerLabels.update(real) { it.copy(streamTitle = "Later title") }
        SessionPeerLabels.move(throwaway, real)
        assertNull(SessionPeerLabels.forPrefix(throwaway))
        assertEquals(SessionPeerLabels.Entry(selfName = "Rahul", streamTitle = "Later title"), SessionPeerLabels.forPrefix(real))
        SessionPeerLabels.move(real, real) // no reveal: nothing changes
        assertEquals("Rahul", SessionPeerLabels.forPrefix(real)?.selfName)
        SessionPeerLabels.clearAll()
    }

    // ----- what Contacts says when it cannot find a contact -----

    @Test
    fun aContactWithoutALeaseGetsGuidanceInsteadOfAWaitThatCannotMatch() {
        val h = ContactReach::hint
        assertEquals(ContactReach.Hint.RECOGNISABLE, h(true, true, false, true))
        assertEquals("rotating IDs off here: old behaviour", ContactReach.Hint.RECOGNISABLE, h(false, false, false, true))
        assertEquals(ContactReach.Hint.ASK_TO_SCAN, h(true, false, false, true))
        assertEquals(ContactReach.Hint.SCAN_AGAIN, h(true, false, true, true))
        assertEquals(ContactReach.Hint.CALL_FROM_NEARBY, h(true, false, false, false))
        assertTrue(ContactReach.mayWaitForCallBack(ContactReach.Hint.RECOGNISABLE))
        ContactReach.Hint.entries.filter { it != ContactReach.Hint.RECOGNISABLE }
            .forEach { assertFalse(it.name, ContactReach.mayWaitForCallBack(it)) }
    }

    // ----- Go live title dialog -----

    @Test
    fun backDoesNotGoLiveAndKeepsTheTypedTitleSkipGoesLiveWithoutOne() {
        val c = StreamTitleChoice
        assertFalse(c.goesLive(StreamTitleChoice.Action.CANCEL))
        assertEquals("Market", c.draftAfter(StreamTitleChoice.Action.CANCEL, " Market ", null))
        assertTrue(c.goesLive(StreamTitleChoice.Action.SKIP))
        assertNull(c.streamTitle(StreamTitleChoice.Action.SKIP, null))
        assertEquals("Market", c.draftAfter(StreamTitleChoice.Action.SKIP, "Market", null))
        assertTrue(c.goesLive(StreamTitleChoice.Action.SAVE))
        assertEquals("Market", c.streamTitle(StreamTitleChoice.Action.SAVE, "Market"))
        assertEquals("Market", c.draftAfter(StreamTitleChoice.Action.SAVE, "Market", "Market"))
        assertNull(c.draftAfter(StreamTitleChoice.Action.CANCEL, "  ", null))
    }
}
