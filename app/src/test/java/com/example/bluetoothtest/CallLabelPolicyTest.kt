package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a name is sent (Design G, G1): audience x contact/stranger x pseudonym x timing, and the callee rule. */
class CallLabelPolicyTest {
    private fun caller(
        audience: NamesAudience = NamesAudience.EVERYONE,
        negotiated: Boolean = true,
        name: Boolean = true,
        beforeAnswer: Boolean = false,
        awaitsReveal: Boolean = false,
        relayed: Boolean = false,
        verifiedContact: Boolean = false,
    ) = CallLabelPolicy.callerPlan(
        CallerLabelContext(negotiated, name, audience, beforeAnswer, awaitsReveal, relayed, verifiedContact),
    )

    private fun callee(
        audience: NamesAudience = NamesAudience.EVERYONE,
        negotiated: Boolean = true,
        name: Boolean = true,
        ownPseudonymous: Boolean = false,
        callerVerified: Boolean = false,
        callerPseudonymous: Boolean = false,
        holdUntilReveal: Boolean = false,
    ) = CallLabelPolicy.calleeMoment(
        CalleeLabelContext(negotiated, name, audience, ownPseudonymous, callerVerified, callerPseudonymous, holdUntilReveal),
    )

    // --- caller ---

    @Test
    fun aPeerWithoutBit5GetsNothingFromEitherSide() {
        // A 0.2.0-beta phone with names off (its default) never advertises bit 5: nothing is sent, nothing breaks.
        assertEquals(CallerLabelPlan(LabelSendMoment.NEVER), caller(negotiated = false))
        assertEquals(LabelSendMoment.NEVER, callee(negotiated = false))
    }

    @Test
    fun onlyTheExperimentalNameBeforeAnswerArmSendsAStrangersNameNow() {
        assertEquals(LabelSendMoment.NOW, caller(awaitsReveal = true, beforeAnswer = true).name)
    }

    @Test
    fun aStrangerSessionHoldsTheNameForTheVerifiedRevealByDefault() {
        assertEquals(LabelSendMoment.AFTER_REVEAL, caller(awaitsReveal = true).name)
    }

    @Test
    fun aRealKeySessionSendsTheNameNow() {
        assertEquals(CallerLabelPlan(LabelSendMoment.NOW), caller())
    }

    @Test
    fun aRelayedCalleeWaitsForItsOpeningAsBefore() {
        assertEquals(CallerLabelPlan(LabelSendMoment.AFTER_OPENING), caller(relayed = true))
    }

    @Test
    fun nothingIsSentWhenThereIsNoName() {
        assertEquals(CallerLabelPlan(LabelSendMoment.NEVER), caller(name = false))
    }

    @Test
    fun audienceNobodySendsNoName() {
        assertEquals(LabelSendMoment.NEVER, caller(audience = NamesAudience.NOBODY).name)
        assertEquals(LabelSendMoment.NEVER, caller(audience = NamesAudience.NOBODY, awaitsReveal = true).name)
    }

    @Test
    fun audienceVerifiedOnlyGivesTheNameToAVerifiedContactAndToNoStranger() {
        assertEquals(LabelSendMoment.NOW, caller(audience = NamesAudience.VERIFIED_ONLY, verifiedContact = true).name)
        assertEquals(LabelSendMoment.NEVER, caller(audience = NamesAudience.VERIFIED_ONLY, verifiedContact = false).name)
        // A pseudonymous callee might be a contact whose lease expired: decided only after its verified reveal.
        assertEquals(
            LabelSendMoment.AFTER_REVEAL_IF_VERIFIED,
            caller(audience = NamesAudience.VERIFIED_ONLY, awaitsReveal = true).name,
        )
        assertEquals(
            LabelSendMoment.AFTER_OPENING,
            caller(audience = NamesAudience.VERIFIED_ONLY, relayed = true, verifiedContact = true).name,
        )
    }

    @Test
    fun theNameBeforeAnswerSettingChangesNothingForAVerifiedOrRealKeyCall() {
        for (before in listOf(true, false)) {
            assertEquals(LabelSendMoment.NOW, caller(beforeAnswer = before).name)
            assertEquals(LabelSendMoment.NOW, caller(audience = NamesAudience.VERIFIED_ONLY, verifiedContact = true, beforeAnswer = before).name)
        }
    }

    // --- callee ---

    @Test
    fun theCalleeKeepsTheExistingRuleForEveryone() {
        assertEquals(LabelSendMoment.AT_RINGING, callee(callerVerified = true))
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(callerVerified = false))
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(ownPseudonymous = true))
    }

    @Test
    fun withVerifiedOnlyAStrangerCallerGetsNoNameAndAPseudonymSessionWaitsForTheCallersReveal() {
        // A real key, not verified: a stranger. Nothing is ever sent.
        assertEquals(LabelSendMoment.NEVER, callee(NamesAudience.VERIFIED_ONLY, callerVerified = false))
        // A real-key verified contact: at Ringing.
        assertEquals(LabelSendMoment.AT_RINGING, callee(NamesAudience.VERIFIED_ONLY, callerVerified = true))
        // The callee presented a throwaway (lease-expired contacts look like strangers): decided at the caller's reveal.
        assertEquals(
            LabelSendMoment.AFTER_REVEAL_IF_VERIFIED,
            callee(NamesAudience.VERIFIED_ONLY, ownPseudonymous = true, callerVerified = false),
        )
    }

    @Test
    fun theCalleeSendsNothingForNobodyOrWithoutAName() {
        assertEquals(LabelSendMoment.NEVER, callee(NamesAudience.NOBODY, callerVerified = true))
        assertEquals(LabelSendMoment.NEVER, callee(name = false, callerVerified = true))
    }

    @Test
    fun decliningOrLettingItRingRevealsNothingBecauseNoMomentIsBeforeAcceptForAStranger() {
        // The only moment before Accept is AT_RINGING, which is reachable only with callerIsVerifiedContact.
        for (audience in NamesAudience.entries) for (pseudo in listOf(true, false)) {
            assertTrue(callee(audience, ownPseudonymous = pseudo, callerVerified = false) != LabelSendMoment.AT_RINGING)
        }
    }

    // --- audience / settings storage ---

    @Test
    fun audienceDefaultsToEveryoneAndParsesTheStoredValue() {
        assertEquals(NamesAudience.EVERYONE, NamesAudience.fromPref(null))
        assertEquals(NamesAudience.EVERYONE, NamesAudience.fromPref("garbage"))
        assertEquals(NamesAudience.VERIFIED_ONLY, NamesAudience.fromPref("verified"))
        assertEquals(NamesAudience.NOBODY, NamesAudience.fromPref("nobody"))
        for (a in NamesAudience.entries) assertEquals(a, NamesAudience.fromPref(a.prefValue))
    }

    @Test
    fun aBenchmarkLaunchReadsOnlyItsExtrasAndKeepsTheMeasuredBehaviour() {
        // Stored choices never reach a measured case; the default of the timing switch there is the Design F arm (off).
        val storedAudience = { _: String -> "nobody" }
        assertEquals(NamesAudience.EVERYONE, NamingSettings.audience(true, { null }, storedAudience))
        assertEquals(NamesAudience.NOBODY, NamingSettings.audience(true, { "nobody" }, { null }))
        assertFalse(NamingSettings.nameBeforeAnswer(true, { false }, { true }))
        assertTrue(NamingSettings.nameBeforeAnswer(true, { it == NamingSettings.EXTRA_BEFORE_ANSWER }, { false }))
    }

    @Test
    fun aUserLaunchHoldsAStrangersNameForTheRevealWhateverAnOlderBuildStoredAndKeepsTheAudienceChoice() {
        // Design decision 2026-11-06: "Show my name before they answer" is Experimental and off for every user launch.
        assertFalse(NamingSettings.nameBeforeAnswer(false, { false }, { null }))
        // No switch since 2026-11-04: a choice stored by an older build (on or off) is ignored.
        assertFalse(NamingSettings.nameBeforeAnswer(false, { false }, { true }))
        assertFalse(NamingSettings.nameBeforeAnswer(false, { false }, { false }))
        assertEquals(
            LabelSendMoment.AFTER_REVEAL,
            caller(awaitsReveal = true, beforeAnswer = NamingSettings.nameBeforeAnswer(false, { false }, { null })).name,
        )
        assertEquals(NamesAudience.VERIFIED_ONLY, NamingSettings.audience(false, { null }, { "verified" }))
        assertEquals(NamesAudience.EVERYONE, NamingSettings.audience(false, { null }, { null }))
    }

    // --- the labels switch is split from the public title pages ---

    @Test
    fun theTitlePagesSwitchIsItsOwnAndOnByDefault() {
        assertTrue(NamingFeatureDefaults.TITLE_PAGES)
        assertTrue(NamingFeatures.resolveTitlePages(false, { false }, { null }))
        // A constant: no stored choice reaches it (its key was never written by any build).
        assertTrue(NamingFeatures.TITLE_PAGES.constant)
        assertTrue(NamingFeatures.resolveTitlePages(false, { false }, { false }))
        // The old combined extra of benchmark launches still switches the pages, so measured title-page cases stay valid.
        assertTrue(NamingFeatures.resolveTitlePages(true, { it == PeerLabelFeature.EXTRA }, { null }))
        assertTrue(NamingFeatures.resolveTitlePages(true, { it == NamingFeatures.TITLE_PAGES.benchmarkExtra }, { null }))
        assertFalse(NamingFeatures.resolveTitlePages(true, { false }, { true }))
    }

    @Test
    fun theInSessionNamesAreAConstantOnWithTheOldExtra() {
        assertNull("a constant since 2026-10-17", CallFeature.NAMES_AND_TITLES.prefKey)
        assertEquals("test_peer_labels", CallFeature.NAMES_AND_TITLES.benchmarkExtra)
        assertTrue(CallFeatureDefaults.NAMES_AND_TITLES)
    }

    // --- callee name timing (audit CALL-06): the name waits for the caller's block check, as camera and microphone do ---

    @Test
    fun aStrangerCallersCalleeNameWaitsForTheirRevealWhenTheHoldIsOn() {
        assertEquals(
            LabelSendMoment.AFTER_REVEAL,
            callee(ownPseudonymous = true, callerPseudonymous = true, holdUntilReveal = true),
        )
    }

    @Test
    fun withTheControlArmTheCalleeNameStillGoesWithAccept() {
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(ownPseudonymous = true, callerPseudonymous = true))
    }

    @Test
    fun theHoldAppliesOnlyWhereARevealIsComing() {
        // A real-key caller was already checked against the block list before ringing; no reveal follows.
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(ownPseudonymous = true, holdUntilReveal = true))
        assertEquals(LabelSendMoment.AT_ACCEPT, callee(callerPseudonymous = true, holdUntilReveal = true))
        // A verified contact still gets it at Ringing, and the audience rules are unchanged.
        assertEquals(
            LabelSendMoment.AT_RINGING,
            callee(ownPseudonymous = true, callerPseudonymous = true, callerVerified = true, holdUntilReveal = true),
        )
        assertEquals(
            LabelSendMoment.NEVER,
            callee(audience = NamesAudience.NOBODY, ownPseudonymous = true, callerPseudonymous = true, holdUntilReveal = true),
        )
        assertEquals(
            LabelSendMoment.AFTER_REVEAL_IF_VERIFIED,
            callee(audience = NamesAudience.VERIFIED_ONLY, ownPseudonymous = true, callerPseudonymous = true, holdUntilReveal = true),
        )
    }
}
