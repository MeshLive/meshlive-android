package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit CALL-06: a callee that answers a stranger sends its name only after the caller's REVEAL verified and the revealed
 * person is not blocked, in the order AndroidPrivateVideoCall calls [CalleeNameRelease]: [CalleeNameRelease.hold] at the
 * Invite, nothing at Accept, then the reveal or the block. What the test collects is what would reach the control queue.
 */
class CalleeNameReleaseTest {
    private val stranger = IdentityFingerprint.fromPublicKey(byteArrayOf(1, 9, 9, 9, 3))
    private val blockedPerson = IdentityFingerprint.fromPublicKey(byteArrayOf(2, 9, 9, 9, 6))
    private val container = byteArrayOf(7, 7, 7)
    private val queued = mutableListOf<ByteArray>()
    private val withheld = mutableListOf<CallerNameRelease.Withheld>()

    private val release = CalleeNameRelease(
        offerLabels = { queued += it },
        isBlocked = { it == blockedPerson },
        onWithheld = { withheld += it },
    )

    @Test
    fun nothingLeavesAtAcceptOnlyAfterTheVerifiedUnblockedReveal() {
        release.hold(LabelSendMoment.AFTER_REVEAL, container)
        // Accept: the call does not touch the held name; a held name is not a queued name.
        assertTrue(queued.isEmpty())
        release.onRevealVerified(stranger, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertEquals(1, queued.size)
        assertTrue(queued.single().contentEquals(container))
    }

    @Test
    fun aBlockedPersonNeverLearnsTheNameNotOnARepeatedRevealEither() {
        release.hold(LabelSendMoment.AFTER_REVEAL, container)
        release.onRevealVerified(blockedPerson, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        release.onRevealVerified(stranger, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
        assertEquals(listOf(CallerNameRelease.Withheld.BLOCKED), withheld)
    }

    @Test
    fun aCallEndedAsBlockedDropsTheHeldName() {
        release.hold(LabelSendMoment.AFTER_REVEAL, container)
        release.onPeerBlocked()
        release.onRevealVerified(stranger, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun aSecondRevealDoesNotSendTheNameAgain() {
        release.hold(LabelSendMoment.AFTER_REVEAL, container)
        release.onRevealVerified(stranger, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        release.onRevealVerified(stranger, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertEquals(1, queued.size)
    }

    @Test
    fun theOnlyPeopleIVerifiedRuleStillNeedsAVerifiedContact() {
        release.hold(LabelSendMoment.AFTER_REVEAL_IF_VERIFIED, container)
        release.onRevealVerified(stranger, revealedVerifiedContact = false, audienceNow = NamesAudience.VERIFIED_ONLY)
        assertTrue(queued.isEmpty())
        assertEquals(listOf(CallerNameRelease.Withheld.NOT_VERIFIED), withheld)
        release.hold(LabelSendMoment.AFTER_REVEAL_IF_VERIFIED, container)
        release.onRevealVerified(stranger, revealedVerifiedContact = true, audienceNow = NamesAudience.VERIFIED_ONLY)
        assertEquals(1, queued.size)
    }

    @Test
    fun theNewestAudienceIsAppliedAtReleaseAndAnyOtherMomentHoldsNothing() {
        release.hold(LabelSendMoment.AFTER_REVEAL, container)
        release.onRevealVerified(stranger, revealedVerifiedContact = true, audienceNow = NamesAudience.NOBODY)
        assertTrue(queued.isEmpty())
        release.hold(LabelSendMoment.AT_ACCEPT, container) // not this class's moment: ignored
        release.onRevealVerified(stranger, revealedVerifiedContact = true, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun theHoldIsOnForUsersAndOnlyADebugControlArmCanTurnItOff() {
        assertTrue(DebugTunables.calleeNameAfterReveal)
        DebugTunables.calleeNameAfterReveal = false
        try {
            // A release build ignores the control arm: it is forced back on.
            assertEquals(!BuildConfig.DEBUG, DebugTunables.calleeNameAfterReveal)
        } finally {
            DebugTunables.calleeNameAfterReveal = true
        }
    }
}
