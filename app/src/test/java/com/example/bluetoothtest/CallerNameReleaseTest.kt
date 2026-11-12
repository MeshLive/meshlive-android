package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Design decision 2026-11-06 ("A caller's name waits for the reveal"), driven through [CallerNameRelease] in the order
 * AndroidPrivateVideoCall calls it: the plan before the Invite ([CallerNameRelease.plan], [CallerNameRelease.start]), then
 * the OPENING, the REVEAL or the block. What the test collects is exactly what the call would offer to its control queue,
 * signed by the production signer, and the callee side reads it with the receiver's own [LabelInbox].
 */
class CallerNameReleaseTest {
    /** The caller's attempt throwaway: the key its endpoint handshake presented to a stranger, so it signs the name. */
    private val throwaway = KeyPairSigningIdentity(generateEcKeyPair(SecureRandom()))
    private val callId = UUID.fromString("0a0b0c0d-0102-0304-0506-0708090a0b0c")
    /** The callee's real identity, as its REVEAL shows it. */
    private val callee = IdentityFingerprint.fromPublicKey(byteArrayOf(1, 7, 7, 7, 3))
    private val someoneElse = IdentityFingerprint.fromPublicKey(byteArrayOf(2, 7, 7, 7, 6))
    private val queued = mutableListOf<ByteArray>()
    private val withheld = mutableListOf<CallerNameRelease.Withheld>()
    private var name: String? = "Rahul"

    private val release = CallerNameRelease(
        signName = { LocalLabels.signed(throwaway, LabelKind.PERSON_NAME, LabelBinding.call(callId), name) },
        offerLabels = { container -> queued += container },
        onWithheld = { reason -> withheld += reason },
    )

    @Before
    fun setUp() {
        LocalLabels.clear()
        PersonPolicy.resetForTest()
    }

    @After
    fun tearDown() {
        PersonPolicy.resetForTest()
        BlockedPeople.installForTest(null)
        LocalLabels.clear()
    }

    /** The plan exactly as the call site makes it for a callee behind a throwaway key (a stranger). */
    private fun strangerPlan(
        nameBeforeAnswer: Boolean = NamingSettings.nameBeforeAnswer(false, { false }, { null }),
        audience: NamesAudience = NamesAudience.EVERYONE,
        awaitedReveal: Boolean = true,
    ) = CallerNameRelease.plan(
        labelsNegotiated = true,
        hasName = true,
        audience = audience,
        nameBeforeAnswer = nameBeforeAnswer,
        awaitedReveal = awaitedReveal,
        peerIsPseudonym = true,
        relayedAwaitsOpening = false,
        calleeIsVerifiedContact = false,
    )

    /** The plan for a callee whose endpoint handshake showed its real key. */
    private fun realKeyPlan(relayed: Boolean = false) = CallerNameRelease.plan(
        labelsNegotiated = true,
        hasName = true,
        audience = NamesAudience.EVERYONE,
        nameBeforeAnswer = false,
        awaitedReveal = false,
        peerIsPseudonym = false,
        relayedAwaitsOpening = relayed,
        calleeIsVerifiedContact = false,
    )

    /** What the callee's phone shows from everything queued so far, after the receiver's own checks (signer, call ID). */
    private fun calleeSees(): String? {
        var now = 0L
        val inbox = LabelInbox(nowMs = { now += 10_000L; now })
        queued.forEach { container -> inbox.accept(container, throwaway.nodeId, LabelBinding.call(callId), "test") }
        return inbox.text(LabelKind.PERSON_NAME)
    }

    @Test
    fun aStrangerCalleeSeesNoNameWhileItRingsAndTheNameOnlyAfterItsVerifiedReveal() {
        val plan = strangerPlan()
        assertEquals(LabelSendMoment.AFTER_REVEAL, plan.name)
        release.start(plan, realPeer = null)
        assertTrue("nothing is queued before the Invite or while it rings", queued.isEmpty())
        assertNull(calleeSees())
        release.onOpeningVerified(NamesAudience.EVERYONE, verifiedContactNow = false)
        assertTrue("an OPENING is not a stranger's moment", queued.isEmpty())
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertEquals("Rahul", calleeSees())
        // The same reveal again after a link renewal: the name does not go twice.
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertEquals(1, queued.size)
    }

    @Test
    fun aCalleeRevealedAsSomeoneIBlockedNeverGetsTheName() {
        PersonPolicy.installForTest(setOf(callee))
        release.start(strangerPlan(), realPeer = null)
        // The call site checks the block first and tells the release; a later (repeated) reveal releases nothing.
        release.onPeerBlocked()
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
        assertNull(calleeSees())
        assertEquals(listOf(CallerNameRelease.Withheld.BLOCKED), withheld)
    }

    @Test
    fun theReleaseChecksTheBlockListItselfEvenIfTheCallSiteDidNot() {
        PersonPolicy.installForTest(setOf(callee))
        release.start(strangerPlan(), realPeer = null)
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
        // The block keys on the full fingerprint: someone else who reveals in the next call gets the name.
        release.reset()
        release.start(strangerPlan(), realPeer = null)
        release.onRevealVerified(someoneElse, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertEquals("Rahul", calleeSees())
    }

    @Test
    fun aPersonBlockedWhileThePhoneRingsNeverGetsTheName() {
        release.start(strangerPlan(), realPeer = null)
        PersonPolicy.installForTest(setOf(callee))
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun theHeldNameFollowsTheNewestWhoSeesMyName() {
        release.start(strangerPlan(), realPeer = null)
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.NOBODY)
        assertTrue("Nobody chosen while it rang", queued.isEmpty())
        release.reset()
        release.start(strangerPlan(), realPeer = null)
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.VERIFIED_ONLY)
        assertTrue("Only my contacts chosen while it rang, and the stranger is no contact", queued.isEmpty())
        release.reset()
        release.start(strangerPlan(), realPeer = null)
        release.onRevealVerified(callee, revealedVerifiedContact = true, audienceNow = NamesAudience.VERIFIED_ONLY)
        assertEquals("Rahul", calleeSees())
    }

    @Test
    fun withOnlyMyContactsAStrangerWhoRevealsAsNoContactGetsNothing() {
        val plan = strangerPlan(audience = NamesAudience.VERIFIED_ONLY)
        assertEquals(LabelSendMoment.AFTER_REVEAL_IF_VERIFIED, plan.name)
        release.start(plan, realPeer = null)
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.VERIFIED_ONLY)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun aThrowawayCalleeWithoutASelectedOfferStillWaitsForItsReveal() {
        val plan = strangerPlan(awaitedReveal = false)
        assertEquals(LabelSendMoment.AFTER_REVEAL, plan.name)
        release.start(plan, realPeer = null)
        assertTrue(queued.isEmpty())
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertEquals("Rahul", calleeSees())
    }

    @Test
    fun aNameRemovedWhileItRangIsNotSent() {
        release.start(strangerPlan(), realPeer = null)
        name = null
        release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun onlyTheExperimentalArmSendsAStrangersNameBeforeTheInvite() {
        // Every launch holds the name by default; a choice stored by an older build is ignored.
        assertFalse(NamingSettings.nameBeforeAnswer(false, { false }, { null }))
        assertFalse(NamingSettings.nameBeforeAnswer(false, { false }, { true }))
        assertFalse(NamingSettings.nameBeforeAnswer(true, { false }, { null }))
        // The DEBUG extra turns the Experimental arm on: the name is then queued at start, before the Invite.
        val experimental = NamingSettings.nameBeforeAnswer(true, { it == NamingSettings.EXTRA_BEFORE_ANSWER }, { null })
        assertTrue(experimental)
        release.start(strangerPlan(nameBeforeAnswer = experimental), realPeer = null)
        assertEquals("Rahul", calleeSees())
    }

    @Test
    fun aRealKeyCalleeGetsTheNameBeforeTheInviteUnlessItIsBlocked() {
        val plan = realKeyPlan()
        assertEquals(LabelSendMoment.NOW, plan.name)
        PersonPolicy.installForTest(setOf(callee))
        release.start(plan, realPeer = callee)
        assertTrue(queued.isEmpty())
        release.reset()
        release.start(plan, realPeer = someoneElse)
        assertEquals("Rahul", calleeSees())
    }

    @Test
    fun aRelayedRealKeyCalleeGetsTheNameOnlyAfterItsOpeningAndOnlyIfStillAllowed() {
        val plan = realKeyPlan(relayed = true)
        assertEquals(LabelSendMoment.AFTER_OPENING, plan.name)
        release.start(plan, realPeer = callee)
        assertTrue("nothing before the OPENING", queued.isEmpty())
        PersonPolicy.installForTest(setOf(callee))
        release.onOpeningVerified(NamesAudience.EVERYONE, verifiedContactNow = false)
        assertTrue("blocked before its OPENING arrived", queued.isEmpty())
        PersonPolicy.resetForTest()
        release.reset()
        release.start(plan, realPeer = callee)
        release.onOpeningVerified(NamesAudience.NOBODY, verifiedContactNow = false)
        assertTrue("Nobody chosen before its OPENING arrived", queued.isEmpty())
        release.reset()
        release.start(plan, realPeer = callee)
        release.onOpeningVerified(NamesAudience.EVERYONE, verifiedContactNow = false)
        assertEquals("Rahul", calleeSees())
    }

    @Test
    fun anUnreadableBlockListBlocksNobodySoTheNameGoesAfterTheRevealAsForAnyoneElse() {
        // CF-CALL-24, kept by the design decision of 2026-11-06: fail open (shown and logged, BlockListUnreadableTest).
        val phone = FakePhone(Files.createTempDirectory("caller-name-unreadable").toFile())
        try {
            val context = phone.start()
            val file = File(File(phone.noBackupDir, IdentityVerification.DIRECTORY), BlockedPeople.FILE)
            BlockedPeopleStore(file, AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 1 }, "AES") }).add(callee)
            BlockedPeople.installForTest(
                BlockedPeopleStore(file, AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 2 }, "AES") }),
            )
            PersonPolicy.install(context)
            release.start(strangerPlan(), realPeer = null)
            release.onRevealVerified(callee, revealedVerifiedContact = false, audienceNow = NamesAudience.EVERYONE)
            assertEquals("Rahul", calleeSees())
        } finally {
            phone.root.deleteRecursively()
        }
    }
}
