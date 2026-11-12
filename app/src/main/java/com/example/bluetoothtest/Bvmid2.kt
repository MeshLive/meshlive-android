package com.example.bluetoothtest

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * `BVMID2` in-person verification (Design G, G4; PROTOCOL "Verification code BVMID2"). Pure Kotlin: the code format, the
 * displaying phone's single-use state machine, the verify-only session scope, the scanner's checks, the mutual outcome and
 * the BVMID1 and lease rules. The Android binding (Meet screen, scanner connect loop) is a thin layer over this file.
 *
 * What a scan proves: the scanner reached a phone that holds the private key whose fingerprint is in the code, through a
 * secret that was on the displaying screen. It does NOT prove physical presence (a current photograph or a live relay of
 * the screen works within the code's life): the scanner must confirm "Is this Rahul's screen you scanned?" before the
 * shield is set ([MeetSession.confirmScreen]), and the wording is "scanned", never "was here".
 *
 * Single use (independent review of 2026-10-12, finding 7): the code is RESERVED atomically for one logical attempt when the
 * link handshake's tag matches ([QrCodeDisplay.reserve]); the end-to-end handshake of the SAME connection continues under
 * that reservation; a failure releases it (counted against a per-code attempt cap); only a completed end-to-end handshake
 * commits it (CONSUMED). Consumed-attempt state is pinned until the code expires, so cache eviction cannot manufacture a
 * reuse alert (finding 8). The signing budget is spent for every reserved attempt.
 */
class Bvmid2FormatException(val reason: String) : IllegalArgumentException(reason)

internal class Bvmid2Payload(val fingerprint: IdentityFingerprint, kQr: ByteArray, rdi: ByteArray) {
    private val secret = kQr.copyOf()
    private val id = rdi.copyOf()

    init {
        require(secret.size == 32 && id.size == RotatingDiscoveryIds.ID_BYTES)
    }

    val kQr: ByteArray get() = secret.copyOf()
    val rdi: ByteArray get() = id.copyOf()
    val rdiHex: String get() = id.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        other is Bvmid2Payload && fingerprint == other.fingerprint && secret.contentEquals(other.secret) && id.contentEquals(other.id)

    override fun hashCode(): Int = (fingerprint.hashCode() * 31 + secret.contentHashCode()) * 31 + id.contentHashCode()
}

internal object Bvmid2 {
    const val MAGIC = "BVMID2"

    fun encode(payload: Bvmid2Payload): String =
        listOf(MAGIC, payload.fingerprint.hex.uppercase(), hex(payload.kQr).uppercase(), hex(payload.rdi).uppercase()).joinToString(".")

    fun parse(text: String): Bvmid2Payload {
        val parts = text.trim().split('.')
        if (parts.firstOrNull() != MAGIC) throw Bvmid2FormatException(if (parts.firstOrNull()?.startsWith("BVMID") == true) "other version" else "other magic")
        if (parts.size != 4) throw Bvmid2FormatException("field count")
        val (fingerprintHex, secretHex, rdiHex) = parts.drop(1)
        if (fingerprintHex.length != 64 || secretHex.length != 64 || rdiHex.length != 12) throw Bvmid2FormatException("length")
        val all = fingerprintHex + secretHex + rdiHex
        if (all.any { !(it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f') }) throw Bvmid2FormatException("alphabet")
        val upper = all.any { it in 'A'..'F' }
        val lower = all.any { it in 'a'..'f' }
        if (upper && lower) throw Bvmid2FormatException("mixed case")
        val secret = unhex(secretHex)
        if (secret.all { it.toInt() == 0 }) throw Bvmid2FormatException("zero secret")
        return Bvmid2Payload(IdentityFingerprint.fromHex(fingerprintHex.lowercase()), secret, unhex(rdiHex))
    }

    fun tryParse(text: String): Bvmid2Payload? = runCatching { parse(text) }.getOrNull()

    /** True for text that begins with the version 2 magic (so a screen can say "unreadable" instead of "not a code"). */
    fun looksLikeBvmid2(text: String): Boolean = text.trim().startsWith("$MAGIC.")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** What happened to the code on the displaying phone's screen. */
internal interface QrDisplayEvents {
    /** A phone completed a scan of the live code (the code is consumed): "Rahul scanned your code". */
    fun onScanCompleted(peer: IdentityFingerprint)

    /** A genuinely new attempt used a consumed code: "Two phones used your code ...". */
    fun onReuseAlert()

    /** The code is no longer valid (expired, screen left, ID changed, attempt cap): the screen draws a new one. */
    fun onCodeEnded(reason: String)

    companion object {
        val NONE = object : QrDisplayEvents {
            override fun onScanCompleted(peer: IdentityFingerprint) = Unit
            override fun onReuseAlert() = Unit
            override fun onCodeEnded(reason: String) = Unit
        }
    }
}

internal class QrCodeDisplay(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: (ByteArray) -> Unit = SecureRandom()::nextBytes,
    /** The Meet screen's own signing bucket (review 2026-10-27, finding 13), not the one strangers share. */
    private val budget: Bmh3SigningBudget = Bmh3Policy.meetBudget,
    var events: QrDisplayEvents = QrDisplayEvents.NONE,
) {
    enum class Phase { LIVE, RESERVED, CONSUMED }

    private class Code(val payload: Bvmid2Payload, val createdMs: Long) {
        var phase = Phase.LIVE
        var owner: QrConnectionGate? = null
        var reservedAtMs = 0L
        var attempts = 0
        val seenEphemerals = HashSet<String>()
        val tagKey: ByteArray = Bmh3Crypto.qrTagKey(payload.kQr)
        val binding: ByteArray = Bmh3Crypto.advertBinding(payload.rdi, RotatingDiscoveryIds.ROLE_CALL_OFFER, null)
    }

    private var current: Code? = null

    /** Screen-only reachability (finding F10): the phone is call-ready only because this screen is open. */
    @Volatile var screenOnlyReachable = false

    /** A new live code for [fingerprint] on the phone's current role-0 [rdi]; any earlier code ends (one live code at a time). */
    @Synchronized
    fun show(fingerprint: IdentityFingerprint, rdi: ByteArray): Bvmid2Payload {
        end("replaced")
        val secret = ByteArray(32)
        do {
            random(secret)
        } while (secret.all { it.toInt() == 0 })
        val payload = Bvmid2Payload(fingerprint, secret, rdi)
        secret.fill(0)
        current = Code(payload, clockMs())
        return payload
    }

    /** The screen was left (screen off, app backgrounded, activity closed). */
    @Synchronized
    fun leave() = end("left")

    /** The phone's role-0 ID changed: the code is dead and the screen redraws a new one. */
    @Synchronized
    fun onRdiChanged(newRdi: ByteArray) {
        val code = current ?: return
        if (!code.payload.rdi.contentEquals(newRdi)) end("id_changed")
    }

    /** The live code or null (expired codes are ended on the way). */
    @Synchronized
    fun livePayload(): Bvmid2Payload? {
        expireIfDue()
        return current?.takeIf { it.phase == Phase.LIVE || it.phase == Phase.RESERVED }?.payload
    }

    /** True while a code can still be matched (live, reserved, or consumed and pinned for the reuse alert). */
    @Synchronized
    fun hasCode(): Boolean {
        expireIfDue()
        return current != null
    }

    @Synchronized
    fun phase(): Phase? {
        expireIfDue()
        return current?.phase
    }

    fun newGate(): QrConnectionGate = QrConnectionGate(this)

    /** The tag check the responder of [gate]'s connection adds, or null without a code. */
    @Synchronized
    fun tagCheck(gate: QrConnectionGate): Bmh3ContactTag? {
        expireIfDue()
        val code = current ?: return null
        return Bmh3ContactTag(code.tagKey, code.binding, qrGate = gate.bind(code.createdMs, code.payload.rdiHex))
    }

    @Synchronized
    internal fun reserve(gate: QrConnectionGate, codeId: Long, purpose: Bmh3Purpose, ephemeral: ByteArray) {
        expireIfDue()
        val code = current?.takeIf { it.createdMs == codeId } ?: throw Bmh3Failure("qr expired")
        val key = MessageDigest.getInstance("SHA-256").digest(ephemeral).joinToString("") { "%02x".format(it) }
        // A repeated msg1 (a replay of a sniffed first message) is closed silently and never raises the reuse alert.
        if (!code.seenEphemerals.add(key)) throw Bmh3Failure("replay")
        if (code.seenEphemerals.size > MAX_SEEN) throw Bmh3Failure("qr attempts")
        when (code.phase) {
            Phase.CONSUMED -> {
                // A genuinely new attempt on a consumed code: someone else holds it. Cancel the pending scan, alert.
                // Design QL (review R1): a lease that has not left yet is withheld for the rest of this visit.
                QrMeet.visit.onReuseAlert()
                events.onReuseAlert()
                throw Bmh3Failure("qr consumed")
            }
            Phase.RESERVED -> {
                if (code.owner === gate && purpose == Bmh3Purpose.END_TO_END && gate.linkReserved) {
                    return
                }
                if (clockMs() - code.reservedAtMs < RESERVATION_MS) throw Bmh3Failure("qr busy")
                // The earlier reservation timed out: fall through as a new attempt.
                code.owner = null
                code.phase = Phase.LIVE
            }
            Phase.LIVE -> Unit
        }
        if (purpose != Bmh3Purpose.LINK) throw Bmh3Failure("qr end-to-end without link")
        if (code.attempts >= MAX_ATTEMPTS) {
            end("attempt_cap")
            throw Bmh3Failure("qr attempts")
        }
        // The global signing budget still applies to every reserved attempt (it throws Bmh3Failure when exhausted).
        budget.spend()
        code.attempts++
        code.phase = Phase.RESERVED
        code.owner = gate
        code.reservedAtMs = clockMs()
        gate.linkReserved = true
    }

    /** The attempt of [gate] completed its end-to-end handshake with [peer]: commit (CONSUMED). */
    @Synchronized
    internal fun commit(gate: QrConnectionGate, codeId: Long, peer: IdentityFingerprint): Boolean {
        val code = current?.takeIf { it.createdMs == codeId } ?: return false
        if (code.phase != Phase.RESERVED || code.owner !== gate) return false
        code.phase = Phase.CONSUMED
        code.owner = null
        events.onScanCompleted(peer)
        return true
    }

    /** The attempt of [gate] failed or its connection ended before commit: the reservation is released (attempt counted). */
    @Synchronized
    internal fun release(gate: QrConnectionGate, codeId: Long) {
        val code = current?.takeIf { it.createdMs == codeId } ?: return
        if (code.phase == Phase.RESERVED && code.owner === gate) {
            code.phase = Phase.LIVE
            code.owner = null
        }
    }

    private fun expireIfDue() {
        val code = current ?: return
        if (clockMs() - code.createdMs >= LIFETIME_MS) end("expired")
    }

    private fun end(reason: String) {
        val code = current ?: return
        current = null
        code.tagKey.fill(0)
        events.onCodeEnded(reason)
    }

    companion object {
        /** A code lives 300 s, until its first completed use, its RDI leaving the air or the screen being left. */
        const val LIFETIME_MS = 300_000L

        /** An attempt that neither commits nor releases within this time loses its reservation. */
        const val RESERVATION_MS = 45_000L

        /** Reserved attempts per code (a photographed code cannot drive unlimited real-key signatures). */
        const val MAX_ATTEMPTS = 3

        /** Distinct first messages pinned per code until it expires (bounded). */
        const val MAX_SEEN = 32
    }
}

/** One incoming connection's view of the displayed code (the link and the end-to-end handshake share it). */
internal class QrConnectionGate(private val display: QrCodeDisplay) {
    @Volatile internal var linkReserved = false
    private var boundCodeId = 0L
    private var peerCommitted = false

    internal fun bind(codeId: Long, @Suppress("UNUSED_PARAMETER") rdiHex: String): QrTagGate {
        boundCodeId = codeId
        return object : QrTagGate {
            override fun onTagMatch(purpose: Bmh3Purpose, msg1Ephemeral: ByteArray) =
                display.reserve(this@QrConnectionGate, codeId, purpose, msg1Ephemeral)
        }
    }

    /** The end-to-end handshake finished: the verification is committed and the code consumed. */
    fun complete(peer: IdentityFingerprint): Boolean {
        if (peerCommitted) return false
        peerCommitted = display.commit(this, boundCodeId, peer)
        return peerCommitted
    }

    /** The connection ended or failed before [complete]. */
    fun fail() {
        if (!peerCommitted) display.release(this, boundCodeId)
    }
}

/**
 * The exact scope of a session opened with a verification code (finding 9). It exists only to verify:
 *
 * - link records: the end-to-end handshake, end-to-end content, link heartbeats and the link opening `BDO1`; everything
 *   else (store-and-forward, relay setup, mesh envelopes, group and route control) closes it;
 * - content: `LABELS` (the scanner's optional name, kind 1) and, when both endpoint hellos carry bit 3, one `BDX1`
 *   `RECOGNITION_OFFER` and one `PRESENCE_LEASE` per direction (Design QL); `INVITE`/call signals, other discovery records
 *   (`OPENING`, `IDENTITY_REVEAL`, a second OFFER or LEASE), streams, media, key frames and group keys close it;
 * - completion: the successful end-to-end handshake IS the verification event; the app then ends the session, after the
 *   presence exchange when this side takes part in one. No extra control message exists;
 * - no ordinary contact side effect: no ring, recent-call entry, label persistence or message exchange;
 * - at most [MAX_SESSION_MS] after the handshake, then it closes. A violation closes it and is counted.
 */
internal class QrSessionScope(private val startMs: Long) {
    var violations = 0
        private set

    private val discoveryKindsSeen = HashSet<Int>()

    fun allowsLinkRecord(type: SecureRecordType): Boolean = type in LINK_RECORDS

    fun allowsContent(type: EndToEndContentType): Boolean = type == EndToEndContentType.LABELS

    /** True when [type] is refused; counts the violation. */
    fun refuseLinkRecord(type: SecureRecordType): Boolean = (!allowsLinkRecord(type)).also { if (it) violations++ }

    fun refuseContent(type: EndToEndContentType): Boolean = (!allowsContent(type)).also { if (it) violations++ }

    /**
     * Design QL: a `BDX1` record is allowed only as the first OFFER or the first LEASE from the peer, and only when both
     * endpoint hellos carried bit 3 ([negotiated]). True when refused; counts the violation.
     */
    @Synchronized
    fun refuseDiscovery(payload: ByteArray, negotiated: Boolean): Boolean {
        val magic = DiscoveryExchange.MAGIC
        val kind = if (payload.size > magic.size && payload.copyOfRange(0, magic.size).contentEquals(magic)) {
            payload[magic.size].toInt() and 0xff
        } else {
            -1
        }
        val allowed = negotiated && kind in LEASE_KINDS && discoveryKindsSeen.add(kind)
        if (!allowed) violations++
        return !allowed
    }

    fun expired(nowMs: Long): Boolean = nowMs - startMs >= MAX_SESSION_MS

    companion object {
        const val MAX_SESSION_MS = 60_000L

        /** After the verification event the session lingers briefly (the scanner's name label may still be in flight). */
        const val CLOSE_GRACE_MS = 2_000L

        /**
         * Design QL (review R5): how long a session waits for the scanner's confirmation (scanner) or its OFFER (displayer)
         * before it ends without a lease; the endpoint is busy meanwhile, so this stays well below [MAX_SESSION_MS].
         */
        const val CONFIRM_WAIT_MS = 20_000L
        val LINK_RECORDS = setOf(
            SecureRecordType.END_TO_END_HANDSHAKE,
            SecureRecordType.END_TO_END_CONTENT,
            SecureRecordType.LINK_HEARTBEAT,
            SecureRecordType.DISCOVERY_OPENING,
        )
        val LEASE_KINDS = setOf(DiscoveryExchange.KIND_RECOGNITION_OFFER, DiscoveryExchange.KIND_PRESENCE_LEASE)
    }
}

/** The scanner side: how a parsed code becomes handshake options and which answers are refused. */
internal object QrScanPolicy {
    /** The tag toward the displayer: `K_tag` of the code bound to its RDI, and the full node ID the answerer must have. */
    fun tag(payload: Bvmid2Payload): Bmh3ContactTag = Bmh3ContactTag(
        Bmh3Crypto.qrTagKey(payload.kQr),
        Bmh3Crypto.advertBinding(payload.rdi, RotatingDiscoveryIds.ROLE_CALL_OFFER, null),
        expectedPeer = payload.fingerprint.nodeId,
    )

    /**
     * The checks before this phone presents its real key (PROTOCOL "Scanner"): a `PSEUDONYM` answer is refused (a real
     * answer was expected), and the handshake key's fingerprint must equal the code's.
     */
    fun checkResponder(payload: Bvmid2Payload, peer: Bmh3Peer) {
        if (peer.pseudonymous) throw Bmh3Failure("pseudonym answer to a verification code")
        if (IdentityFingerprint.fromPublicKey(peer.spki) != payload.fingerprint) throw Bmh3Failure("not the code's key")
    }

    /** Only adverts carrying the code's RDI, at route cost 0, are tried; every address is tried within [SEARCH_MS]. */
    fun addressesToTry(payload: Bvmid2Payload, seen: List<Pair<String, DiscoveryAnnouncement>>): List<String> =
        seen.filter { (_, ad) -> ad.rotating && ad.routeCost == 0 && ad.nodeIdPrefix.contentEquals(payload.rdi) }
            .map { it.first }.distinct()

    const val SEARCH_MS = 10_000L
}

/**
 * The Meet-in-person screen model (Design G, G4 items 3-4). Each scan is its own tagged session, so each phone learns of the
 * other's scan from a real authenticated event. "Verified each other" needs both events for the same fingerprint during one
 * visit; the shield ("I scanned them") is set only after the user confirmed whose screen was scanned.
 */
internal class MeetSession {
    enum class Outcome { NONE, YOU_VERIFIED, THEY_SCANNED_YOU, VERIFIED_EACH_OTHER }

    private val scannedByMe = HashSet<IdentityFingerprint>()
    private val pendingConfirm = HashMap<IdentityFingerprint, String>()
    private val scannedMe = HashSet<IdentityFingerprint>()
    private val recognised = HashSet<IdentityFingerprint>()
    /** Design QL revision 2: the people this phone's user unchecked "Let <name>'s phone recognise yours nearby" for. */
    private val declined = HashSet<IdentityFingerprint>()
    /** When a lease for each person was stored in this visit (elapsed ms), for Call now's 60 s. */
    private val storedAtMs = HashMap<IdentityFingerprint, Long>()
    var reuseAlert = false
        private set

    /** A scan reached [peer]: the screen asks "Is this [claimedOrHandle]'s screen you scanned?" before anything is marked. */
    fun scanReached(peer: IdentityFingerprint, claimedOrHandle: String) {
        pendingConfirm[peer] = claimedOrHandle
    }

    /** What the confirmation asks, or null when nothing is pending for [peer]. */
    fun confirmationText(peer: IdentityFingerprint): String? =
        pendingConfirm[peer]?.let { "Is this $it's screen you scanned?" }

    /** The user answered. True: the shield may be set (`markVerified` with `QR_SCAN`). False: nothing is marked. */
    fun confirmScreen(peer: IdentityFingerprint, yes: Boolean): Boolean {
        pendingConfirm.remove(peer) ?: return false
        if (yes) scannedByMe += peer
        return yes
    }

    /** An inbound verification session on the displayed code completed with [peer]'s real key. */
    fun scannedByPeer(peer: IdentityFingerprint) {
        scannedMe += peer
    }

    /** Design QL: a verification session in this visit stored [peer]'s presence lease. */
    fun leaseStored(peer: IdentityFingerprint, nowMs: Long = 0L) {
        recognised += peer
        storedAtMs[peer] = nowMs
    }

    /** Design QL revision 2: the user unchecked recognition for [peer] at the scan confirmation. */
    fun declinedRecognition(peer: IdentityFingerprint) {
        declined += peer
    }

    /** Revision 2's plain result for [peer]; null before any scan. */
    fun qlResult(peer: IdentityFingerprint): QlResult? = when (outcome(peer)) {
        Outcome.NONE -> null
        Outcome.YOU_VERIFIED, Outcome.THEY_SCANNED_YOU -> QlResult.ONE_SCAN
        Outcome.VERIFIED_EACH_OTHER -> when {
            peer in recognised -> QlResult.RECOGNISED
            peer in declined -> QlResult.DECLINED
            else -> QlResult.NOT_FINISHED
        }
    }

    /** Call now: only with result (a), for [CALL_NOW_MS] after the lease was stored in this visit. */
    fun callNowVisible(peer: IdentityFingerprint, nowMs: Long): Boolean {
        if (qlResult(peer) != QlResult.RECOGNISED) return false
        val at = storedAtMs[peer] ?: return false
        return nowMs - at in 0 until CALL_NOW_MS
    }

    /** (a) callable from Contacts, (b) this phone declined, (c) not finished (no reason guessed), (d) one scan so far. */
    enum class QlResult { RECOGNISED, DECLINED, NOT_FINISHED, ONE_SCAN }

    companion object {
        const val CALL_NOW_MS = 60_000L
    }

    fun recognises(peer: IdentityFingerprint): Boolean = peer in recognised

    fun onReuseAlert() {
        reuseAlert = true
        pendingConfirm.clear()
    }

    fun outcome(peer: IdentityFingerprint): Outcome = when {
        peer in scannedByMe && peer in scannedMe -> Outcome.VERIFIED_EACH_OTHER
        peer in scannedByMe -> Outcome.YOU_VERIFIED
        peer in scannedMe -> Outcome.THEY_SCANNED_YOU
        else -> Outcome.NONE
    }

    fun message(peer: IdentityFingerprint, name: String): String? = when (outcome(peer)) {
        Outcome.NONE -> null
        Outcome.YOU_VERIFIED -> "You scanned $name. $name has not scanned your code yet."
        Outcome.THEY_SCANNED_YOU -> "$name scanned your code. Now scan $name's code."
        Outcome.VERIFIED_EACH_OTHER -> if (peer in recognised) {
            "You and $name scanned each other. Your phones now recognise each other, so you can call from Contacts."
        } else {
            // A late confirmation leaves no lease in this visit: "after your next call" was not true (Design QL revision 2).
            "You and $name scanned each other. Scan each other's codes again, or call once from Nearby, so your phones recognise each other."
        }
    }
}

/**
 * How a BVMID1 scan counts (G-Q5 and the 2026-10-12 compat decision). While `bvmid2` is OFF nothing changes: a BVMID1 scan
 * records a QR verification (shield). With `bvmid2` ON a static BVMID1 code has no freshness, so it records only "Checked by
 * code" (outline) unless "Accept older verification codes" (`bvmid1_compat`) is on.
 */
internal object Bvmid1Policy {
    fun methodForScan(bvmid2On: Boolean, bvmid1Compat: Boolean): VerificationMethod =
        if (!bvmid2On || bvmid1Compat) VerificationMethod.QR_SCAN else VerificationMethod.SAFETY_CODE_CONFIRMED
}

/** The verified-contact display basis: scanned (shield) or only checked by code (outline). */
internal fun VerifiedContact.basis(): VerificationBasis =
    if (method == VerificationMethod.QR_SCAN) VerificationBasis.SCANNED else VerificationBasis.CHECKED_BY_CODE

/**
 * Presence-lease eligibility for a contact that was only checked by code (review F18). With `bvmid2` ON such a contact
 * neither receives nor offers NEW leases until both phones scanned each other; one that already holds a lease keeps it
 * until the next generation change. With `bvmid2` OFF nothing changes.
 */
internal object CodeOnlyLeaseRule {
    fun blocked(bvmid2On: Boolean, method: VerificationMethod?, scannedBothWays: Boolean, holdsLease: Boolean): Boolean =
        bvmid2On && method == VerificationMethod.SAFETY_CODE_CONFIRMED && !scannedBothWays && !holdsLease
}

/** The process-wide displayed code and Meet-screen events (the Android binding sets [QrCodeDisplay.events]). */
internal object QrMeet {
    val display = QrCodeDisplay()
    val session = MeetSession()

    /** Design QL: a verification session stored [peer]'s presence lease (the Meet screen says the phones now recognise each other). */
    @Volatile var onLeaseStored: ((IdentityFingerprint) -> Unit)? = null

    val visit = MeetVisit()
}

/**
 * Design QL (review R1): who this phone's own user confirmed ("Is this X's screen you scanned?" answered yes, shield stored)
 * during the current visit of the Add contact screen, and whether a reuse alert fired in it. A displayer takes part in the
 * lease exchange only with a scanner in [confirmed], so a race winner that happens to be an older contact gets nothing.
 */
internal class MeetVisit {
    private val confirmed = HashSet<IdentityFingerprint>()
    private var reuse = false
    private var open = false
    private var token = 0L

    /** Starts a new visit and returns its token; only that token can end it ([end]) or confirm in it ([confirm]). */
    @Synchronized
    fun begin(): Long {
        confirmed.clear()
        reuse = false
        open = true
        return ++token
    }

    /**
     * The user confirmed [peer]'s screen and answered "Let <name>'s phone recognise yours nearby" with [recognise] (true
     * also when no choice was shown and nothing needed storing). Only a yes lets this visit's sessions with [peer] offer
     * and lease; a later answer for the same person replaces an earlier one.
     */
    @Synchronized
    fun confirm(peer: IdentityFingerprint, recognise: Boolean = true, visit: Long? = null) {
        // An answer given in a visit that has since ended does not carry into a later one.
        if (visit != null && (visit != token || !open)) return
        if (recognise) confirmed += peer else confirmed -= peer
    }

    /**
     * The screen was left (closed or sent to the background): nothing more leaves until a new visit begins. With [visit],
     * only that visit ends (a second instance of the screen may have begun its own).
     */
    @Synchronized
    fun end(visit: Long? = null) {
        if (visit != null && visit != token) return
        confirmed.clear()
        open = false
    }

    /** [visit] is the current visit and it is open. */
    @Synchronized
    fun isOpen(visit: Long): Boolean = open && visit == token

    @Synchronized
    fun onReuseAlert() {
        reuse = true
    }

    /** Either role of a verification session may offer and lease to [peer] now. */
    @Synchronized
    fun mayLease(peer: IdentityFingerprint): Boolean = open && !reuse && peer in confirmed

    /** Any lease of a verification session is withheld after a reuse alert in this visit. */
    @Synchronized
    fun reuseAlerted(): Boolean = reuse
}
