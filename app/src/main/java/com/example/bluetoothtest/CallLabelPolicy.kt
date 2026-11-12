package com.example.bluetoothtest

/**
 * When a name is sent in a call, and to whom (Design G, G1; PROTOCOL "Naming v2 / Label timing and audience").
 * No new bytes: `BNL1`, the `LABELS` container, bit 5 and the receiver checks are unchanged. What changes is the moment a
 * sender queues them, so this file is the single decision and the call code only carries it out. Pure Kotlin.
 *
 * - Audience ("Who sees my name"): [NamesAudience.EVERYONE] (default), [NamesAudience.VERIFIED_ONLY], [NamesAudience.NOBODY].
 *   It decides whether the kind 1 name is sent at all. No call subject (kind 3) is sent: retired 2026-11-05.
 * - Caller, stranger session (the callee presented a throwaway key): the name waits for the callee's verified `REVEAL`
 *   and goes only if the revealed person is not blocked ([CallerNameRelease]; design decision 2026-11-06). The
 *   Experimental arm "Show my name before they answer" (`names_before_answer`, off, DEBUG extra only) queues it before
 *   `INVITE`, signed by the attempt pseudonym.
 * - Callee: for "Everyone I call or answer" the name goes at Ringing to a verified contact, else with Accept; with the
 *   `test_callee_name_after_reveal` (audit CALL-06, on since 2026-11-10) a stranger caller behind a throwaway gets it only
 *   after its REVEAL verified and the person is not blocked ([CalleeNameRelease]), like the callee's camera and microphone.
 *   For
 *   "Only people I verified" the callee cannot tell a stranger from a contact whose lease expired before the caller's
 *   reveal, so its name is queued only after that reveal is verified and found to be a verified contact (review F11).
 *   Declining or letting it ring reveals nothing.
 * - A peer without bit 5 (a 0.2.0-beta phone with names off, which is its default) gets nothing; nothing breaks.
 */
internal enum class NamesAudience(val prefValue: String) {
    EVERYONE("everyone"),
    VERIFIED_ONLY("verified"),
    NOBODY("nobody"),
    ;

    companion object {
        fun fromPref(value: String?): NamesAudience = entries.firstOrNull { it.prefValue == value } ?: EVERYONE
    }
}

/** When one label is queued. */
internal enum class LabelSendMoment {
    /** Never sent in this call. */
    NEVER,

    /** Immediately: before `INVITE` (caller) or as soon as the session allows. */
    NOW,

    /** After a relayed callee proved it owns the offer by its `OPENING` (existing behaviour for relays). */
    AFTER_OPENING,

    /** After the callee's `REVEAL` is verified (the Design F rule). */
    AFTER_REVEAL,

    /** After the peer's `REVEAL` is verified AND it is a verified contact; otherwise never. */
    AFTER_REVEAL_IF_VERIFIED,

    /** Callee: with Ringing (the caller is already known to be a verified contact). */
    AT_RINGING,

    /** Callee: with Accept. */
    AT_ACCEPT,
}

internal data class CallerLabelPlan(val name: LabelSendMoment)

internal data class CallerLabelContext(
    /** Both endpoint hellos carry bit 5. */
    val labelsNegotiated: Boolean,
    val hasName: Boolean,
    val audience: NamesAudience,
    /** "Show my name before they answer". */
    val nameBeforeAnswer: Boolean,
    /** The callee presented a throwaway key and its reveal is still awaited. */
    val calleeAwaitsReveal: Boolean,
    /** A relayed callee that is proven only by its `OPENING`, which follows the Invite. */
    val relayedAwaitsOpening: Boolean,
    /** The callee's real key is known now and equals a verified contact's. */
    val calleeIsVerifiedContact: Boolean,
)

internal data class CalleeLabelContext(
    val labelsNegotiated: Boolean,
    val hasName: Boolean,
    val audience: NamesAudience,
    /** This phone presented a throwaway key: the caller's identity is unknown until its reveal. */
    val ownPseudonymous: Boolean,
    /** The caller's real key is known now and equals a verified contact's (a real-key session). */
    val callerIsVerifiedContact: Boolean,
    /** The caller presented a throwaway key too, so its REVEAL (and the block check on the person) is still to come. */
    val callerPseudonymous: Boolean = false,
    /** `test_callee_name_after_reveal` (audit CALL-06, default on): hold the name for the caller's reveal, as media is held. */
    val holdUntilReveal: Boolean = false,
)

internal object CallLabelPolicy {
    fun callerPlan(c: CallerLabelContext): CallerLabelPlan {
        if (!c.labelsNegotiated) return CallerLabelPlan(LabelSendMoment.NEVER)
        val name = when {
            !c.hasName || c.audience == NamesAudience.NOBODY -> LabelSendMoment.NEVER
            c.audience == NamesAudience.VERIFIED_ONLY -> when {
                c.calleeAwaitsReveal -> LabelSendMoment.AFTER_REVEAL_IF_VERIFIED
                !c.calleeIsVerifiedContact -> LabelSendMoment.NEVER
                c.relayedAwaitsOpening -> LabelSendMoment.AFTER_OPENING
                else -> LabelSendMoment.NOW
            }
            c.calleeAwaitsReveal -> if (c.nameBeforeAnswer) LabelSendMoment.NOW else LabelSendMoment.AFTER_REVEAL
            c.relayedAwaitsOpening -> LabelSendMoment.AFTER_OPENING
            else -> LabelSendMoment.NOW
        }
        return CallerLabelPlan(name)
    }

    fun calleeMoment(c: CalleeLabelContext): LabelSendMoment {
        if (!c.labelsNegotiated || !c.hasName) return LabelSendMoment.NEVER
        return when (c.audience) {
            NamesAudience.NOBODY -> LabelSendMoment.NEVER
            NamesAudience.EVERYONE -> when {
                c.callerIsVerifiedContact -> LabelSendMoment.AT_RINGING
                // Both sides behind throwaways: the block check on the caller happens at its REVEAL, after Accept.
                c.holdUntilReveal && c.ownPseudonymous && c.callerPseudonymous -> LabelSendMoment.AFTER_REVEAL
                else -> LabelSendMoment.AT_ACCEPT
            }
            NamesAudience.VERIFIED_ONLY -> when {
                c.callerIsVerifiedContact -> LabelSendMoment.AT_RINGING
                c.ownPseudonymous -> LabelSendMoment.AFTER_REVEAL_IF_VERIFIED
                else -> LabelSendMoment.NEVER
            }
        }
    }
}

/**
 * The stored settings of Naming v2 labels. The audience is a three-way choice (Settings > Your ID, shown only when a name is
 * set) and so is not a [FeatureSpec]; "Show my name before they answer" is a switch under it.
 *
 * A DEBUG benchmark launch (any `test_` extra) reads only its extras: `test_names_audience=everyone|verified|nobody`
 * (default everyone) and `test_names_before_answer` (default off, which is the Design F behaviour the measured cases used).
 */
internal object NamingSettings {
    const val PREF_AUDIENCE = "names_audience"
    const val PREF_BEFORE_ANSWER = "feature_names_before_answer"
    const val EXTRA_AUDIENCE = "test_names_audience"
    const val EXTRA_BEFORE_ANSWER = "test_names_before_answer"

    fun audience(benchmarkLaunch: Boolean, extra: (String) -> String?, stored: (String) -> String?): NamesAudience =
        NamesAudience.fromPref(if (benchmarkLaunch) extra(EXTRA_AUDIENCE) else stored(PREF_AUDIENCE))

    /** The spec's one default ([NamingFeatureDefaults.NAMES_BEFORE_ANSWER]: Experimental, off since 2026-11-06) by the shared rule. */
    fun nameBeforeAnswer(benchmarkLaunch: Boolean, extra: (String) -> Boolean, stored: (String) -> Boolean?): Boolean =
        FeatureCatalog.resolve(NamingFeatures.NAMES_BEFORE_ANSWER, benchmarkLaunch, extra, stored)
}
