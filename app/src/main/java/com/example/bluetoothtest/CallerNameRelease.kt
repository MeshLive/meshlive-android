package com.example.bluetoothtest

/**
 * The caller's name in one call (Naming v2; PROTOCOL "Label timing and audience"). [AndroidPrivateVideoCall] makes the
 * plan with [plan] before the Invite, gives it to [start], and then only reports what happened: a relayed callee's
 * OPENING passed ([onOpeningVerified]), the callee's REVEAL verified ([onRevealVerified]), the person turned out to be
 * blocked ([onPeerBlocked]), a new connection began ([reset]). Only this class offers the caller's one `LABELS` container
 * to the call's ordered control queue, so a test drives the same sequence the call does and sees exactly what would be
 * queued. Pure Kotlin.
 *
 * Design decision 2026-11-06 ("A caller's name waits for the reveal"): a stranger (a callee behind a throwaway key) gets
 * the name only after its REVEAL verified and the revealed person is not on the block list; the newest "Who sees my
 * name" is applied again at that moment. A callee that presented its real key is checked against the block list before
 * the name goes. An unreadable block list blocks nobody (CF-CALL-24), so the name then goes as to anyone unblocked. The
 * Experimental arm `names_before_answer` (off) still sends a stranger's name at [start]. No byte on the wire changes:
 * only the moment the existing container is queued.
 */
internal class CallerNameRelease(
    /** This phone's name signed for the current call (`BNL1` kind 1, by the endpoint signer), or null when none is set now. */
    private val signName: () -> ByteArray?,
    /** Offers one `BLB1` container to the call's ordered control queue. */
    private val offerLabels: (ByteArray) -> Unit,
    /** Whether this full fingerprint is on the phone's block list now ([PersonPolicy]; it fails open). */
    private val isBlocked: (IdentityFingerprint) -> Boolean = { fingerprint -> PersonPolicy.blocked(fingerprint) },
    /** After a send: how many labels went (the existing `peer_labels call_tx` line). */
    private val onSent: (Int) -> Unit = {},
    /** A held name that will not go in this call, and why (a DEBUG line for the phone checks). */
    private val onWithheld: (Withheld) -> Unit = {},
) {
    /** Why a held name was dropped. */
    enum class Withheld { BLOCKED, AUDIENCE, NOT_VERIFIED, UNKNOWN_PEER }

    /** The moment the name still waits for; [LabelSendMoment.NEVER] when nothing is held. */
    @Volatile
    var held: LabelSendMoment = LabelSendMoment.NEVER
        private set

    /** The callee's real key when its endpoint handshake showed it (no reveal coming), else null. */
    private var realPeer: IdentityFingerprint? = null

    /** A new connection on this endpoint: nothing is held. */
    @Synchronized
    fun reset() {
        held = LabelSendMoment.NEVER
        realPeer = null
    }

    /**
     * The plan of this call, before the Invite is queued. [realPeer] is the callee's full fingerprint when its endpoint
     * handshake showed its real key, null for a throwaway. [LabelSendMoment.NOW] sends at once unless that key is blocked.
     */
    @Synchronized
    fun start(plan: CallerLabelPlan, realPeer: IdentityFingerprint?) {
        this.realPeer = realPeer
        held = plan.name
        if (plan.name != LabelSendMoment.NOW) return
        held = LabelSendMoment.NEVER
        // A throwaway (null) reaches NOW only in the Experimental arm `names_before_answer`, which accepts that exposure.
        if (realPeer != null && isBlocked(realPeer)) {
            onWithheld(Withheld.BLOCKED)
            return
        }
        send()
    }

    /** A relayed real-key callee proved it owns the offer by its OPENING. Checked again: block list, newest audience. */
    @Synchronized
    fun onOpeningVerified(audienceNow: NamesAudience, verifiedContactNow: Boolean) {
        if (held != LabelSendMoment.AFTER_OPENING) return
        held = LabelSendMoment.NEVER
        val peer = realPeer
        when {
            peer == null -> onWithheld(Withheld.UNKNOWN_PEER)
            isBlocked(peer) -> onWithheld(Withheld.BLOCKED)
            !allows(audienceNow, verifiedContactNow) -> onWithheld(Withheld.AUDIENCE)
            else -> send()
        }
    }

    /**
     * The callee's REVEAL verified and was admitted; [revealed] is its real, full fingerprint. The name goes only now, only
     * if [revealed] is not blocked, and only to a verified contact when the plan or the newest audience asks for one.
     */
    @Synchronized
    fun onRevealVerified(revealed: IdentityFingerprint, revealedVerifiedContact: Boolean, audienceNow: NamesAudience) {
        val moment = held
        if (moment != LabelSendMoment.AFTER_REVEAL && moment != LabelSendMoment.AFTER_REVEAL_IF_VERIFIED) return
        held = LabelSendMoment.NEVER
        when {
            isBlocked(revealed) -> onWithheld(Withheld.BLOCKED)
            moment == LabelSendMoment.AFTER_REVEAL_IF_VERIFIED && !revealedVerifiedContact -> onWithheld(Withheld.NOT_VERIFIED)
            !allows(audienceNow, revealedVerifiedContact) -> onWithheld(Withheld.AUDIENCE)
            else -> send()
        }
    }

    /** The person of this call is blocked: a held name never goes in this call (also not on a repeated REVEAL). */
    @Synchronized
    fun onPeerBlocked() {
        if (held != LabelSendMoment.NEVER) onWithheld(Withheld.BLOCKED)
        held = LabelSendMoment.NEVER
    }

    private fun allows(audience: NamesAudience, verifiedContact: Boolean): Boolean = when (audience) {
        NamesAudience.NOBODY -> false
        NamesAudience.VERIFIED_ONLY -> verifiedContact
        NamesAudience.EVERYONE -> true
    }

    private fun send() {
        val labels = listOfNotNull(signName())
        if (labels.isNotEmpty()) offerLabels(SignedLabelCodec.encodeContainer(labels))
        onSent(labels.size)
    }

    companion object {
        /**
         * The call site's inputs to [CallLabelPolicy.callerPlan]. A callee still behind a throwaway key counts as awaiting
         * its reveal whether or not a selected offer waits for the binding ([awaitedReveal]), so a throwaway never gets the
         * name before its reveal unless the Experimental arm is on ([nameBeforeAnswer]).
         */
        fun plan(
            labelsNegotiated: Boolean,
            hasName: Boolean,
            audience: NamesAudience,
            nameBeforeAnswer: Boolean,
            awaitedReveal: Boolean,
            peerIsPseudonym: Boolean,
            relayedAwaitsOpening: Boolean,
            calleeIsVerifiedContact: Boolean,
        ): CallerLabelPlan = CallLabelPolicy.callerPlan(
            CallerLabelContext(
                labelsNegotiated = labelsNegotiated,
                hasName = hasName,
                audience = audience,
                nameBeforeAnswer = nameBeforeAnswer,
                calleeAwaitsReveal = awaitedReveal || peerIsPseudonym,
                relayedAwaitsOpening = relayedAwaitsOpening,
                calleeIsVerifiedContact = calleeIsVerifiedContact,
            ),
        )
    }
}
