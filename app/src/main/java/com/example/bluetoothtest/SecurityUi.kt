package com.example.bluetoothtest

import android.content.Context
import android.util.Log

/** Verification state of a peer identity as the UI shows it. */
internal enum class ContactVerification { UNVERIFIED, VERIFIED, MISMATCH }

/** A peer identity read from a scanned verification QR code. */
internal data class ScannedIdentity(val nodeIdHex: String, val fingerprintHex: String, val displayName: String = "")

/** Outcome of "Mark verified"; each has its own plain-language message. */
internal enum class MarkVerifiedOutcome {
    VERIFIED,
    /** No encrypted connection with this person right now (verify during a call). */
    NO_AUTHENTICATED_SESSION,
    /** The code/QR does not match the phone you are connected to. */
    FINGERPRINT_MISMATCH,
    /** Another verified person already uses this short ID; forget them first. */
    PREFIX_CONFLICT,
    STORE_FULL,
    /** The verified-contact store cannot be read; nothing was saved. */
    STORE_UNREADABLE,
    UNAVAILABLE,
}

/** Why the verified-contact store cannot be read right now. */
internal enum class StoreProblem {
    /** Damaged, or its key was lost: only a reset (forgetting verified contacts) recovers it. */
    CORRUPT,
    /** Keystore or storage hiccup: usually clears by itself; try again later. */
    TEMPORARY,
}

/**
 * Thin adapter between the screens and the security core. The UI calls only this interface, so the
 * identity-verification and emergency-wipe implementations (IdentityVerification and EmergencyWipe,
 * built separately) are wired in one place: assign [SecurityUiBackend.current] at start-up, or
 * replace the bodies of [CoreSecurityUi]. Node IDs and fingerprints are lowercase hex strings.
 */
internal interface SecurityUi {
    /** True when verification (QR, safety codes, verified marks) is backed by the security core. */
    val verificationAvailable: Boolean

    /** This phone's identity fingerprint (hex), or null before an identity exists. */
    fun ownFingerprint(context: Context): String?

    /** Safety code for the active authenticated session with [peerNodeIdHex], or null. */
    fun safetyCode(context: Context, peerNodeIdHex: String): String?

    /** Text encoded in this phone's verification QR code, or null. */
    fun qrPayload(context: Context): String?

    /** Parses a scanned QR text; null when it is not a verification code of this app. */
    fun parseQr(payload: String): ScannedIdentity?

    /**
     * Records [peerNodeIdHex] as verified in person. [fingerprintHex] is the scanned fingerprint
     * (QR), or null when the user confirmed the session's safety code ([viaQr] false).
     */
    fun markVerified(
        context: Context,
        peerNodeIdHex: String,
        fingerprintHex: String?,
        displayName: String,
        viaQr: Boolean,
        /** A BVMID2 scan the user confirmed ("Is this ...'s screen you scanned?"): always the scanned (shield) method. */
        bvmid2Scan: Boolean = false,
    ): MarkVerifiedOutcome

    /**
     * Verification state of an authenticated peer; [peerIdHex] is its full node ID. The UI never
     * asks about an unauthenticated advertisement prefix (people list rows carry no badge).
     * Call it off the main thread: it may read the verified-contact store.
     */
    fun verificationState(context: Context, peerIdHex: String): ContactVerification

    /**
     * Names saved for verified contacts, by 12-hex node-ID prefix, used only to name and group
     * people in lists. An advert matching a prefix is not proof: identity is checked on connect.
     */
    fun contactNames(context: Context): Map<String, String>

    /** Every verified contact, for the Contacts screen. Empty when the store cannot be read. Off the main thread. */
    fun contacts(context: Context): List<ContactEntry>

    /** Gives the verified contact with [fingerprintHex] your own label (a canonical person name, never blank). */
    fun renameContact(context: Context, fingerprintHex: String, name: String): RenameOutcome

    /** Forgets one verified contact; the person must be verified again in person. False when nothing changed. */
    fun forgetContact(context: Context, fingerprintHex: String): Boolean

    /** Current verified-contact store problem, or null when it reads normally. Poll on resume. */
    fun storeProblem(context: Context): StoreProblem?

    /** Forgets every verified contact so a corrupt store can be used again. */
    fun resetVerifiedContacts(context: Context): Boolean

    /** Milliseconds until the store retries a temporary failure by itself, or null when no retry is pending. */
    fun storeRetryDueInMs(context: Context): Long?

    /**
     * [onProblem] is called (possibly on another thread, possibly under a store lock) when the store
     * becomes unreadable; implementations must only post work from it. Returns an unregister call.
     */
    fun watchStoreProblems(context: Context, onProblem: () -> Unit): () -> Unit

    /**
     * Erases identity, contacts, messages and settings and normally ends the process. [panic] is
     * the no-confirmation gesture; otherwise [confirmationText] is what the user typed ("DELETE").
     * Returns null on success or an error text. Call it off the main thread.
     */
    fun wipe(context: Context, panic: Boolean, confirmationText: String?): String?
}

internal object SecurityUiBackend {
    @Volatile var current: SecurityUi = CoreSecurityUi
}

/**
 * A process-wide notice that this phone's user verified someone (safety code, BVMID1 or BVMID2 scan), from whichever
 * screen did it. MainActivity listens: a call with that person may now exchange presence leases, and a stranger's recent
 * entry becomes the contact's. [listener] may run on any thread; it must only post work.
 */
internal object VerificationEvents {
    @Volatile var listener: ((nodeIdHex: String, fingerprintHex: String) -> Unit)? = null

    fun verified(nodeIdHex: String, fingerprintHex: String) {
        runCatching { listener?.invoke(nodeIdHex.lowercase(), fingerprintHex.lowercase()) }
    }
}

/**
 * The security core (IdentityVerification, EmergencyWipe). Node IDs and fingerprints cross this
 * boundary as lowercase hex. Every call may touch the Keystore or the store file: callers stay off
 * the main thread.
 */
internal object CoreSecurityUi : SecurityUi {
    override val verificationAvailable: Boolean = true

    private fun core(context: Context) = IdentityVerification.get(context)

    private fun nodeId(hex: String): NodeId? = runCatching {
        require(hex.length == NodeId.BYTE_COUNT * 2)
        NodeId.fromBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }.getOrNull()

    override fun ownFingerprint(context: Context): String? =
        runCatching { core(context).ownFingerprint().hex }
            .onFailure { Log.w("BluetoothVideo", "Identity unavailable for display: ${it.message}") }
            .getOrNull()

    /** 60 digits: this phone's 30 and the peer's 30 (SafetyCode.derive), shown 12 x 5 like Signal. */
    override fun safetyCode(context: Context, peerNodeIdHex: String): String? {
        val id = nodeId(peerNodeIdHex) ?: return null
        val core = core(context)
        val peer = core.authenticatedPeerFingerprint(id) ?: return null
        return runCatching { core.safetyCode(peer) }.getOrNull()
    }

    override fun qrPayload(context: Context): String? = runCatching {
        // The QR is shown face to face to add a contact: it carries "Your name" (design decision 2026-11-04, one name)
        // unless "Who sees my name" is Nobody or no name is set, then the phone model as before. Never a contact's name.
        val ownName = PeerLabelFeature.localName?.takeIf { it.isNotBlank() && PeerLabelFeature.audience != NamesAudience.NOBODY }
        val name = runCatching { IdentityQrCodec.normalizeName((ownName ?: android.os.Build.MODEL.orEmpty()).take(32)) }
            .getOrDefault("")
        core(context).qrPayload(name)
    }.getOrNull()

    override fun parseQr(payload: String): ScannedIdentity? = try {
        val parsed = IdentityQrCodec.parse(payload)
        ScannedIdentity(parsed.fingerprint.nodeId.toString(), parsed.fingerprint.hex, parsed.displayName)
    } catch (_: IllegalArgumentException) { // includes IdentityQrFormatException
        null
    }

    override fun markVerified(
        context: Context,
        peerNodeIdHex: String,
        fingerprintHex: String?,
        displayName: String,
        viaQr: Boolean,
        bvmid2Scan: Boolean,
    ): MarkVerifiedOutcome {
        val id = nodeId(peerNodeIdHex) ?: return MarkVerifiedOutcome.NO_AUTHENTICATED_SESSION
        val core = core(context)
        val confirmed = fingerprintHex?.let { runCatching { IdentityFingerprint.fromHex(it) }.getOrNull() }
            ?: core.authenticatedPeerFingerprint(id)
            ?: return MarkVerifiedOutcome.NO_AUTHENTICATED_SESSION
        // Naming v2: with `bvmid2` on, a static BVMID1 scan records only "Checked by code" unless `bvmid1_compat` is on.
        val method = if (bvmid2Scan) {
            VerificationMethod.QR_SCAN
        } else if (viaQr) {
            Bvmid1Policy.methodForScan(NamingFeatures.bvmid2On(), NamingFeatures.bvmid1CompatOn())
        } else {
            VerificationMethod.SAFETY_CODE_CONFIRMED
        }
        val name = runCatching { IdentityQrCodec.normalizeName(displayName) }.getOrDefault("")
        return when (core.markVerified(id, confirmed, name, method)) {
            MarkVerifiedResult.VERIFIED -> MarkVerifiedOutcome.VERIFIED.also { VerificationEvents.verified(id.toString(), confirmed.hex) }
            MarkVerifiedResult.NO_AUTHENTICATED_SESSION -> MarkVerifiedOutcome.NO_AUTHENTICATED_SESSION
            MarkVerifiedResult.FINGERPRINT_MISMATCH -> MarkVerifiedOutcome.FINGERPRINT_MISMATCH
            MarkVerifiedResult.PREFIX_CONFLICT -> MarkVerifiedOutcome.PREFIX_CONFLICT
            MarkVerifiedResult.STORE_FULL -> MarkVerifiedOutcome.STORE_FULL
            MarkVerifiedResult.STORE_UNREADABLE -> MarkVerifiedOutcome.STORE_UNREADABLE
        }
    }

    override fun verificationState(context: Context, peerIdHex: String): ContactVerification {
        // Only full, authenticated node IDs are answered; an advert prefix is never Verified.
        val id = nodeId(peerIdHex.lowercase()) ?: return ContactVerification.UNVERIFIED
        val core = core(context)
        val fingerprint = core.authenticatedPeerFingerprint(id) ?: return ContactVerification.UNVERIFIED
        return when (core.verificationState(id, fingerprint)) {
            VerificationState.VERIFIED -> ContactVerification.VERIFIED
            VerificationState.UNVERIFIED -> ContactVerification.UNVERIFIED
            VerificationState.MISMATCH -> ContactVerification.MISMATCH
            // Unknown while the store cannot be read: never shown as Verified; the store banner explains.
            VerificationState.STORE_UNREADABLE -> ContactVerification.UNVERIFIED
        }
    }

    override fun contactNames(context: Context): Map<String, String> =
        runCatching {
            core(context).verifiedContacts()
                .filter { it.displayName.isNotBlank() }
                .associate { it.fingerprint.nodePrefixHex to it.displayName }
        }.getOrDefault(emptyMap())

    override fun contacts(context: Context): List<ContactEntry> =
        runCatching {
            core(context).verifiedContacts().map {
                ContactEntry(it.fingerprint.hex, it.displayName, it.verifiedAtMs, it.method == VerificationMethod.QR_SCAN)
            }
        }.getOrDefault(emptyList())

    override fun renameContact(context: Context, fingerprintHex: String, name: String): RenameOutcome {
        val fingerprint = runCatching { IdentityFingerprint.fromHex(fingerprintHex) }.getOrNull()
            ?: return RenameOutcome.NOT_FOUND
        return runCatching {
            // Naming v2: contact labels are unique (case- and look-alike-insensitive), so two contacts never read alike.
            val others = core(context).verifiedContacts().filter { it.fingerprint != fingerprint }.map { it.displayName }
            if (ContactLabelUniqueness.conflict(name, others) != null) return@runCatching RenameOutcome.NAME_TAKEN
            when (core(context).rename(fingerprint, name)) {
                RenameResult.RENAMED -> RenameOutcome.RENAMED
                RenameResult.NOT_FOUND -> RenameOutcome.NOT_FOUND
                RenameResult.INVALID_NAME -> RenameOutcome.INVALID_NAME
                RenameResult.STORE_UNREADABLE -> RenameOutcome.STORE_UNREADABLE
            }
        }.getOrDefault(RenameOutcome.UNAVAILABLE)
    }

    override fun forgetContact(context: Context, fingerprintHex: String): Boolean {
        val fingerprint = runCatching { IdentityFingerprint.fromHex(fingerprintHex) }.getOrNull() ?: return false
        return runCatching { core(context).forget(fingerprint) }.getOrDefault(false)
    }

    override fun storeProblem(context: Context): StoreProblem? =
        when (val cause = core(context).storeUnreadableCause()) {
            null -> null
            else -> if (cause.transient) StoreProblem.TEMPORARY else StoreProblem.CORRUPT
        }

    override fun resetVerifiedContacts(context: Context): Boolean = core(context).resetVerifiedContacts()

    override fun storeRetryDueInMs(context: Context): Long? = core(context).storeRetryDueInMs()

    override fun watchStoreProblems(context: Context, onProblem: () -> Unit): () -> Unit {
        val core = core(context)
        // Invoked under the store lock: the callback only posts (never blocks or reads the store).
        val listener: (StoreUnreadableCause) -> Unit = { onProblem() }
        core.addStoreUnreadableListener(listener)
        return { core.removeStoreUnreadableListener(listener) }
    }

    override fun wipe(context: Context, panic: Boolean, confirmationText: String?): String? {
        val report = if (panic) {
            EmergencyWipe.panic(context)
        } else {
            EmergencyWipe.wipeConfirmed(context, confirmationText.orEmpty())
        }
        // Normally unreachable: the wipe ends the process. An interrupted wipe resumes at the next
        // launch (WipeIncompleteActivity explains anything left).
        return when {
            report.alreadyRunning -> null
            report.complete -> null
            else -> EmergencyWipe.incompleteMessage(report)
        }
    }
}

/** Display helpers for identities (pure; unit tested). */
internal object IdentityFormat {
    /** "1a2b3c4d…" -> "1A2B 3C4D 5E6F 7A8B\n9C0D …": groups of 4, [groupsPerLine] per line. */
    fun groupedHex(hex: String, groupsPerLine: Int = 4): String {
        val clean = hex.filter { it.isLetterOrDigit() }.uppercase()
        return clean.chunked(4).chunked(groupsPerLine).joinToString("\n") { it.joinToString(" ") }
    }

    /** Short form shown in one line: first [groups] groups of four. */
    fun shortHex(hex: String, groups: Int = 2): String =
        hex.filter { it.isLetterOrDigit() }.uppercase().chunked(4).take(groups).joinToString(" ")

    /** Readable form of a safety code: groups of five digits, [groupsPerLine] groups per line. */
    fun groupedDigits(code: String, groupsPerLine: Int = 4): String {
        val digits = code.filter { it.isDigit() }
        return if (digits.length == code.filterNot { it.isWhitespace() }.length && digits.isNotEmpty()) {
            digits.chunked(5).chunked(groupsPerLine).joinToString("\n") { it.joinToString(" ") }
        } else {
            code
        }
    }
}
