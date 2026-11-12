package com.example.bluetoothtest

/**
 * The callee's name held for the caller's REVEAL (audit 2026-11-10 CALL-06; owner answer: hold it). Two cases wait here:
 * audience "Everyone" with `test_callee_name_after_reveal` (default on) toward a stranger caller
 * ([LabelSendMoment.AFTER_REVEAL]), and audience "Only people I verified" in a stranger session
 * ([LabelSendMoment.AFTER_REVEAL_IF_VERIFIED], always held). [AndroidPrivateVideoCall] gives it the signed container at the
 * Invite ([hold]), then only reports the REVEAL ([onRevealVerified], after the call's own block check) or a blocked end
 * ([onPeerBlocked]); only this class offers the callee's held `LABELS` container, so a test sees what would be queued.
 * Pure Kotlin; no byte on the wire changes, only the moment the existing container is queued.
 */
internal class CalleeNameRelease(
    /** Offers one `BLB1` container to the call's ordered control queue. */
    private val offerLabels: (ByteArray) -> Unit,
    /** Whether this full fingerprint is on the phone's block list now ([PersonPolicy]; it fails open). */
    private val isBlocked: (IdentityFingerprint) -> Boolean = { fingerprint -> PersonPolicy.blocked(fingerprint) },
    /** A held name that will not go in this call, and why (a DEBUG line for the phone checks). */
    private val onWithheld: (CallerNameRelease.Withheld) -> Unit = {},
) {
    private var held: ByteArray? = null
    private var moment: LabelSendMoment = LabelSendMoment.NEVER

    /** Holds [container] for [moment]; any other moment holds nothing. */
    @Synchronized
    fun hold(moment: LabelSendMoment, container: ByteArray) {
        if (moment != LabelSendMoment.AFTER_REVEAL && moment != LabelSendMoment.AFTER_REVEAL_IF_VERIFIED) return
        this.moment = moment
        held = container
    }

    /** A new connection on this endpoint, or the call ended: nothing is held. */
    @Synchronized
    fun reset() {
        held = null
        moment = LabelSendMoment.NEVER
    }

    /**
     * The caller's REVEAL verified and was admitted; [revealed] is its real, full fingerprint. The name goes once, only if
     * [revealed] is not blocked, only to a verified contact when the moment asks for one, and as the newest audience allows.
     */
    @Synchronized
    fun onRevealVerified(revealed: IdentityFingerprint, revealedVerifiedContact: Boolean, audienceNow: NamesAudience) {
        val container = held ?: return
        val asked = moment
        held = null
        moment = LabelSendMoment.NEVER
        when {
            isBlocked(revealed) -> onWithheld(CallerNameRelease.Withheld.BLOCKED)
            asked == LabelSendMoment.AFTER_REVEAL_IF_VERIFIED && !revealedVerifiedContact ->
                onWithheld(CallerNameRelease.Withheld.NOT_VERIFIED)
            audienceNow == NamesAudience.NOBODY ||
                (audienceNow == NamesAudience.VERIFIED_ONLY && !revealedVerifiedContact) ->
                onWithheld(CallerNameRelease.Withheld.AUDIENCE)
            else -> offerLabels(container)
        }
    }

    /** The person of this call is blocked: a held name never goes in this call. */
    @Synchronized
    fun onPeerBlocked() {
        if (held != null) onWithheld(CallerNameRelease.Withheld.BLOCKED)
        reset()
    }
}
