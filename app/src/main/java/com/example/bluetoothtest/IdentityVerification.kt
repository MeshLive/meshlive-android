package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/*
 * Full-fingerprint identity verification (PROTOCOL "Identity verification").
 *
 * API contract for the UI (all names are stable; nothing here draws a screen):
 *
 *   IdentityFingerprint                      SHA-256 over the identity key's DER SubjectPublicKeyInfo
 *     .fromPublicKey(spki: ByteArray)        (exactly the bytes NodeId.fromPublicKey hashes)
 *     .fromHex(hex: String) / .hex / .bytes() / .nodeId / .nodePrefixHex / .grouped()
 *
 *   IdentityVerification.get(context): IdentityVerification      process-wide facade
 *     ownFingerprint(): IdentityFingerprint
 *     safetyCode(peer: IdentityFingerprint): String              60 digits, 12 groups of five
 *     qrPayload(displayName: String): String                     "BVMID1.<64 HEX>.<base64url name>"
 *     parseQr(text: String): IdentityQrPayload                   throws IdentityQrFormatException
 *     authenticatedPeerFingerprint(peerNodeId: NodeId): IdentityFingerprint?
 *         the full fingerprint proven by an end-to-end handshake in this process
 *     markVerified(peerNodeId: NodeId, confirmed: IdentityFingerprint,
 *                  displayName: String, method: VerificationMethod): MarkVerifiedResult
 *         VERIFIED only when [confirmed] equals the authenticated session's fingerprint
 *     verificationState(peerNodeId: NodeId, peerFingerprint: IdentityFingerprint): VerificationState
 *         VERIFIED / UNVERIFIED / MISMATCH (possible impersonation, show it loudly) /
 *         STORE_UNREADABLE (offer resetVerifiedContacts())
 *     verifiedContacts(): List<VerifiedContact>
 *     rename(fingerprint: IdentityFingerprint, displayName: String): RenameResult
 *         label only; fingerprint, time and method stay (canonical person names only, never blank)
 *     forget(fingerprint: IdentityFingerprint): Boolean
 *     storeUnreadable(): Boolean / storeUnreadableCause(): StoreUnreadableCause? /
 *     resetVerifiedContacts(): Boolean                       recovery from a damaged store
 *     addStoreUnreadableListener(listener) / removeStoreUnreadableListener(listener)
 *     setMismatchPolicy(policy: MismatchPolicy)               REFUSE (default) or WARN
 *     addMismatchListener(listener) / removeMismatchListener(listener)
 *
 *   SafetyCode.derive(a, b) / SafetyCode.half(fp), IdentityQrCodec.encode/parse and
 *   VerifiedContactStore are the pure, unit-tested pieces behind the facade.
 */

/** SHA-256 over the identity public key's DER SubjectPublicKeyInfo; its first 16 bytes are the node ID. */
class IdentityFingerprint private constructor(private val value: ByteArray) {
    fun bytes(): ByteArray = value.copyOf()

    val hex: String get() = value.joinToString("") { "%02x".format(it) }

    /** The 16-byte node ID this fingerprint extends (NodeId.fromPublicKey of the same key). */
    val nodeId: NodeId get() = NodeId.fromBytes(value.copyOf(NodeId.BYTE_COUNT))

    /** The 12-hex (48-bit) discovery announcement prefix. */
    val nodePrefixHex: String get() = hex.take(DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2)

    fun nodePrefix(): ByteArray = value.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)

    /** Human-readable full fingerprint: 16 groups of four hex digits. */
    fun grouped(): String = hex.chunked(4).joinToString(" ")

    override fun equals(other: Any?): Boolean =
        other is IdentityFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = hex

    companion object {
        const val BYTE_COUNT = 32

        fun fromPublicKey(publicKeyEncoded: ByteArray): IdentityFingerprint {
            require(publicKeyEncoded.isNotEmpty()) { "Empty identity key" }
            return IdentityFingerprint(MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded))
        }

        fun fromBytes(bytes: ByteArray): IdentityFingerprint {
            require(bytes.size == BYTE_COUNT) { "A fingerprint must be $BYTE_COUNT bytes" }
            return IdentityFingerprint(bytes.copyOf())
        }

        /** Exactly 64 lowercase hex digits. */
        fun fromHex(hex: String): IdentityFingerprint {
            require(hex.length == BYTE_COUNT * 2 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                "A fingerprint must be ${BYTE_COUNT * 2} lowercase hex digits"
            }
            return IdentityFingerprint(ByteArray(BYTE_COUNT) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        }

        fun of(identity: SigningIdentity): IdentityFingerprint = fromPublicKey(identity.publicKeyEncoded)
    }
}

/**
 * Pair safety code, Signal style: each party has its own 30-digit iterated fingerprint code (six
 * groups of five, about 99.6 bits); the pair code is the two halves in sorted order, 60 digits.
 * Both phones show the same code for the same pair. Because each half depends on one key only,
 * a man in the middle must find a key whose half equals the victim's: a second preimage of about
 * 2^100 work per half (each try also costs [ITERATIONS] hashes), not a birthday search.
 */
object SafetyCode {
    const val HALF_GROUPS = 6
    const val DIGITS_PER_GROUP = 5
    const val ITERATIONS = 5200
    private val CONTEXT = "BluetoothVideoMesh/IdentityVerification/SafetyCode/2".toByteArray(Charsets.US_ASCII)

    /** One party's 30 digits: SHA-512 iterated [ITERATIONS] times over the context and fingerprint. */
    fun half(fingerprint: IdentityFingerprint): String {
        val digest = MessageDigest.getInstance("SHA-512")
        val key = fingerprint.bytes()
        var state = digest.digest(CONTEXT + key)
        repeat(ITERATIONS - 1) {
            digest.update(state)
            state = digest.digest(key)
        }
        // Each five-digit group comes from 40 bits, so the modulo bias is negligible.
        return (0 until HALF_GROUPS).joinToString(" ") { group ->
            var chunk = 0L
            for (i in 0 until 5) chunk = (chunk shl 8) or (state[group * 5 + i].toLong() and 0xff)
            (chunk % 100_000L).toString().padStart(DIGITS_PER_GROUP, '0')
        }
    }

    fun derive(a: IdentityFingerprint, b: IdentityFingerprint): String =
        listOf(half(a), half(b)).sorted().joinToString(" ")
}

class IdentityQrFormatException(message: String) : IllegalArgumentException(message)

data class IdentityQrPayload(val fingerprint: IdentityFingerprint, val displayName: String)

/**
 * QR text `BVMID1.<64 hex fingerprint>.<base64url (no padding) UTF-8 NFC display name>`. The
 * encoder writes uppercase hex (QR alphanumeric mode); the parser accepts all-uppercase or
 * all-lowercase hex. Everything else is strict: exact magic/version, lengths, alphabets,
 * canonical base64url, valid UTF-8 and the rules of [IdentityQrCodec.normalizeName].
 */
object IdentityQrCodec {
    const val MAGIC = "BVMID1"
    const val MAX_NAME_BYTES = 64
    private const val MAX_TEXT_CHARS = MAGIC.length + 2 + 64 + 88

    fun encode(payload: IdentityQrPayload): String {
        val name = normalizeName(payload.displayName)
        val encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))
        return "$MAGIC.${payload.fingerprint.hex.uppercase()}.$encodedName"
    }

    fun parse(text: String): IdentityQrPayload {
        if (text.length > MAX_TEXT_CHARS) throw IdentityQrFormatException("QR text is too long")
        val parts = text.split('.')
        if (parts.size != 3) throw IdentityQrFormatException("QR text must have three fields")
        if (parts[0] != MAGIC) throw IdentityQrFormatException("Not an identity QR code or unsupported version")
        val hex = parts[1]
        val upper = hex.all { it in '0'..'9' || it in 'A'..'F' }
        val lower = hex.all { it in '0'..'9' || it in 'a'..'f' }
        if (!upper && !lower) throw IdentityQrFormatException("Malformed fingerprint")
        val fingerprint = runCatching { IdentityFingerprint.fromHex(hex.lowercase()) }
            .getOrElse { throw IdentityQrFormatException("Malformed fingerprint") }
        val nameField = parts[2]
        if (!nameField.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            throw IdentityQrFormatException("Malformed display name encoding")
        }
        val nameBytes = runCatching { Base64.getUrlDecoder().decode(nameField) }
            .getOrElse { throw IdentityQrFormatException("Malformed display name encoding") }
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(nameBytes) != nameField) {
            throw IdentityQrFormatException("Non-canonical display name encoding")
        }
        val name = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(nameBytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw IdentityQrFormatException("Display name is not valid UTF-8")
        }
        return IdentityQrPayload(
            fingerprint,
            runCatching { normalizeName(name) }.getOrElse { throw IdentityQrFormatException(it.message ?: "Bad name") },
        )
    }

    fun tryParse(text: String): IdentityQrPayload? = runCatching { parse(text) }.getOrNull()

    /**
     * NFC-normalizes [name] and rejects blank names, names over [MAX_NAME_BYTES] UTF-8 bytes, and
     * any control, format (bidi embeddings, overrides and isolates U+202A-202E/U+2066-2069, marks,
     * zero-width characters, BOM), private-use, surrogate, unassigned or line/paragraph separator
     * code point.
     */
    fun normalizeName(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        require(normalized.isNotBlank()) { "Display name is empty" }
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) {
            "Display name exceeds $MAX_NAME_BYTES bytes"
        }
        normalized.codePoints().forEach { codePoint ->
            require(Character.getType(codePoint) !in REJECTED_TYPES) {
                "Display name contains a control, format, private-use or unassigned character"
            }
        }
        return normalized
    }

    private val REJECTED_TYPES: Set<Int> = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )
}

enum class VerificationMethod(val wireValue: Int) {
    QR_SCAN(1),
    SAFETY_CODE_CONFIRMED(2),
    ;

    companion object {
        fun fromWire(value: Int): VerificationMethod =
            values().firstOrNull { it.wireValue == value } ?: error("Unknown verification method $value")
    }
}

enum class VerificationState {
    /** The authenticated full fingerprint (for a forwarded source, the node ID) equals a verified contact's. */
    VERIFIED,

    /** No verified contact shares this peer's node prefix. */
    UNVERIFIED,

    /**
     * A verified contact has the same 48-bit node prefix but a different key, or the fingerprint
     * does not belong to the node ID: possible impersonation.
     */
    MISMATCH,

    /** The verified-contact store exists but cannot be opened; nothing is compared until it is reset. */
    STORE_UNREADABLE,
}

enum class MarkVerifiedResult {
    VERIFIED,
    /** No end-to-end handshake with this node ID has completed in this process. */
    NO_AUTHENTICATED_SESSION,
    /** The scanned/confirmed fingerprint differs from the authenticated session's. */
    FINGERPRINT_MISMATCH,
    /** A different verified key already owns this node prefix; forget it explicitly first. */
    PREFIX_CONFLICT,
    STORE_FULL,
    /** The store cannot be opened; it is never overwritten implicitly. Reset it first. */
    STORE_UNREADABLE,
}

enum class MismatchPolicy { REFUSE, WARN }

enum class RenameResult {
    RENAMED,
    /** No verified contact has this fingerprint. */
    NOT_FOUND,
    /** Blank, too long or not a canonical person name (see [LabelText]); nothing changed. */
    INVALID_NAME,
    /** The store cannot be opened; it is never overwritten implicitly. Reset it first. */
    STORE_UNREADABLE,
}

data class VerifiedContact(
    val fingerprint: IdentityFingerprint,
    val displayName: String,
    val verifiedAtMs: Long,
    val method: VerificationMethod,
)

/**
 * Why the verified-contact store could not be opened. A transient cause is retried on the next
 * access; a corrupt file stays unreadable until the user resets the store.
 */
enum class StoreUnreadableCause(val transient: Boolean) {
    /** The file fails authentication or parsing: damaged, or sealed under a Keystore key that was lost. */
    CORRUPT_FILE(false),

    /** The Keystore failed to provide or use the key (for example a busy or restarting keystore daemon). */
    KEYSTORE_ERROR(true),

    /** The file could not be read. */
    IO_ERROR(true),
}

/** Raised from an end-to-end handshake when [MismatchPolicy.REFUSE] rejects a mismatching peer. */
class IdentityMismatchException(message: String) : SecurityException(message)

/**
 * What the app shows for the verified-contact store (design decision Q15, 2026-10-14: fail open with a banner and an automatic
 * retry). Calls and streams always continue; the store being unreadable only means nobody shows as verified.
 */
enum class StoreBanner {
    /** Readable: no banner. */
    NONE,

    /** A temporary Keystore or I/O error: "Verified contacts couldn't be loaded", retried automatically with backoff. */
    RETRYING,

    /** A corrupt file (kept untouched): the same text, with a "Reset verified contacts" action. */
    RESET_REQUIRED,
}

/** The pure rules behind [StoreBanner] and the retry schedule of [VerifiedContactStore]. */
object VerifiedStoreRecovery {
    const val FIRST_RETRY_MS = 2_000L
    const val MAX_RETRY_MS = 60_000L

    /** Wait before the next load attempt after [consecutiveFailures] temporary failures: 2 s, 4 s, 8 s ... capped at 60 s. */
    fun retryDelayMs(consecutiveFailures: Int): Long {
        val doublings = (consecutiveFailures - 1).coerceIn(0, 16)
        return minOf(MAX_RETRY_MS, FIRST_RETRY_MS shl doublings)
    }

    fun banner(cause: StoreUnreadableCause?): StoreBanner = when {
        cause == null -> StoreBanner.NONE
        cause.transient -> StoreBanner.RETRYING
        else -> StoreBanner.RESET_REQUIRED
    }

    /**
     * When the screen should look at the store again: after the store's own retry is due (plus a little, so the attempt has run),
     * or never for a readable or corrupt store (a corrupt one changes only through the user's reset, which re-reads at once).
     */
    fun nextCheckInMs(banner: StoreBanner, retryDueInMs: Long?): Long? =
        if (banner == StoreBanner.RETRYING) (retryDueInMs ?: 0L).coerceAtLeast(0L) + 250L else null
}

/**
 * Verified contacts, sealed at rest with [AtRestCipher]. The file is absent until the first
 * contact is verified, so an empty store needs no key access. A store that exists but cannot be
 * opened is reported as unreadable (never silently empty, never overwritten). A corrupt file stays
 * unreadable until [reset]; a Keystore or I/O failure is retried with backoff ([VerifiedStoreRecovery.retryDelayMs]),
 * and nothing touches the Keystore again until the backoff has passed.
 */
class VerifiedContactStore(
    private val file: File,
    private val cipher: AtRestCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cache: List<VerifiedContact>? = null
    /** A non-transient failure; no load is attempted again until [reset]. */
    private var latched: StoreUnreadableCause? = null
    /** The most recent load failure, cleared by a successful load. */
    private var lastFailure: StoreUnreadableCause? = null
    /** Consecutive temporary failures, and when the next attempt is allowed ([clock] time). */
    private var transientFailures = 0
    private var retryAtMs = 0L

    /** Called, with the store locked, when it becomes unreadable or the cause changes; keep it short. */
    @Volatile var unreadableListener: ((StoreUnreadableCause) -> Unit)? = null

    /** Readable contacts; empty when the store is unreadable (check [isUnreadable]). */
    @Synchronized
    fun all(): List<VerifiedContact> = loadOrNull().orEmpty()

    @Synchronized
    fun isUnreadable(): Boolean = unreadableCause() != null

    /** Attempts a load (retrying a transient failure) and returns why it failed, or null when readable. */
    @Synchronized
    fun unreadableCause(): StoreUnreadableCause? {
        loadOrNull()
        return latched ?: lastFailure
    }

    @Synchronized
    fun hasAny(): Boolean = cache?.isNotEmpty() ?: (latched != null || file.exists())

    /** What to show for the store right now (attempts a load when a retry is due). */
    @Synchronized
    fun banner(): StoreBanner = VerifiedStoreRecovery.banner(unreadableCause())

    private fun retryWaitMs(): Long {
        val wait = retryAtMs - clock()
        return if (wait in 1L..VerifiedStoreRecovery.MAX_RETRY_MS) wait else 0L
    }

    /** Milliseconds until the next automatic retry of a temporary failure, or null when none is pending. */
    @Synchronized
    fun retryDueInMs(): Long? {
        val failure = lastFailure
        if (latched != null || failure == null || !failure.transient) return null
        return retryWaitMs()
    }

    @Synchronized
    fun markVerified(
        authenticated: IdentityFingerprint?,
        confirmed: IdentityFingerprint,
        displayName: String,
        method: VerificationMethod,
    ): MarkVerifiedResult {
        if (authenticated == null) return MarkVerifiedResult.NO_AUTHENTICATED_SESSION
        if (authenticated != confirmed) return MarkVerifiedResult.FINGERPRINT_MISMATCH
        val current = loadOrNull() ?: return MarkVerifiedResult.STORE_UNREADABLE
        // A blank name keeps the label the user already gave this contact (re-verifying never erases it);
        // such a contact shows by its handle until it is named.
        val name = if (displayName.isBlank()) {
            current.firstOrNull { it.fingerprint == confirmed }?.displayName.orEmpty()
        } else {
            IdentityQrCodec.normalizeName(displayName)
        }
        if (current.any { it.fingerprint != confirmed && samePrefix(it.fingerprint, confirmed) }) {
            return MarkVerifiedResult.PREFIX_CONFLICT
        }
        val others = current.filterNot { it.fingerprint == confirmed }
        if (others.size >= MAX_CONTACTS) return MarkVerifiedResult.STORE_FULL
        save(others + VerifiedContact(confirmed, name, clock(), method))
        return MarkVerifiedResult.VERIFIED
    }

    /**
     * Gives a verified contact a new label. Only the label changes: the fingerprint, the verification
     * time and the method stay. The label must already be a canonical person name ([LabelText]); a blank
     * label is rejected (to drop a name, forget the contact and verify again).
     */
    @Synchronized
    fun rename(fingerprint: IdentityFingerprint, displayName: String): RenameResult {
        val name = runCatching { LabelText.normalize(LabelKind.PERSON_NAME, displayName) }
            .getOrElse { return RenameResult.INVALID_NAME }
        val current = loadOrNull() ?: return RenameResult.STORE_UNREADABLE
        val contact = current.firstOrNull { it.fingerprint == fingerprint } ?: return RenameResult.NOT_FOUND
        if (contact.displayName == name) return RenameResult.RENAMED
        save(current.map { if (it.fingerprint == fingerprint) it.copy(displayName = name) else it })
        return RenameResult.RENAMED
    }

    @Synchronized
    fun forget(fingerprint: IdentityFingerprint): Boolean {
        val current = loadOrNull() ?: return false
        val remaining = current.filterNot { it.fingerprint == fingerprint }
        if (remaining.size == current.size) return false
        save(remaining)
        return true
    }

    /** Deletes every verified contact, including an unreadable store. The user must re-verify. */
    @Synchronized
    fun reset(): Boolean {
        val removed = !file.exists() || file.delete()
        cache = null
        latched = null
        lastFailure = if (removed) null else StoreUnreadableCause.IO_ERROR
        transientFailures = 0
        retryAtMs = 0L
        return removed
    }

    @Synchronized
    fun state(peerNodeId: NodeId, peerFingerprint: IdentityFingerprint): VerificationState {
        if (peerFingerprint.nodeId != peerNodeId) return VerificationState.MISMATCH
        if (!hasAny()) return VerificationState.UNVERIFIED
        val contacts = loadOrNull() ?: return VerificationState.STORE_UNREADABLE
        if (contacts.any { it.fingerprint == peerFingerprint }) return VerificationState.VERIFIED
        return if (contacts.any { samePrefix(it.fingerprint, peerFingerprint) }) {
            VerificationState.MISMATCH
        } else {
            VerificationState.UNVERIFIED
        }
    }

    /**
     * State of a source known only by its 128-bit node ID (a forwarded stream's `BFG1` grant):
     * VERIFIED when it equals a verified contact's node ID, MISMATCH when a verified contact has
     * the same 48-bit prefix but another node ID.
     */
    @Synchronized
    fun stateForNodeId(nodeId: NodeId): VerificationState {
        if (!hasAny()) return VerificationState.UNVERIFIED
        val contacts = loadOrNull() ?: return VerificationState.STORE_UNREADABLE
        val prefix = nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        val samePrefix = contacts.filter { it.fingerprint.nodePrefix().contentEquals(prefix) }
        return when {
            samePrefix.isEmpty() -> VerificationState.UNVERIFIED
            samePrefix.any { it.fingerprint.nodeId == nodeId } -> VerificationState.VERIFIED
            else -> VerificationState.MISMATCH
        }
    }

    /** The verified contact that owns [prefix], if any. */
    @Synchronized
    fun contactForPrefix(prefix: ByteArray): VerifiedContact? =
        if (!hasAny()) null else loadOrNull()?.firstOrNull { it.fingerprint.nodePrefix().contentEquals(prefix) }

    @Synchronized
    internal fun dropCache() {
        cache = null
        latched = null
        lastFailure = null
        transientFailures = 0
        retryAtMs = 0L
    }

    private class LoadFailure(val reason: StoreUnreadableCause, error: Throwable) : Exception(error)

    private fun loadOrNull(): List<VerifiedContact>? {
        cache?.let { return it }
        if (latched != null) return null
        // Backoff: after a temporary failure the Keystore is not asked again until the retry is due.
        // (A wall clock that moved back by more than the longest backoff counts as due, so a retry can never be stuck.)
        if (lastFailure?.transient == true && retryWaitMs() > 0L) return null
        return try {
            load().also {
                cache = it
                lastFailure = null
                transientFailures = 0
                retryAtMs = 0L
            }
        } catch (failure: LoadFailure) {
            if (!failure.reason.transient) {
                latched = failure.reason
            } else {
                transientFailures++
                retryAtMs = clock() + VerifiedStoreRecovery.retryDelayMs(transientFailures)
            }
            if (lastFailure != failure.reason) {
                lastFailure = failure.reason
                runCatching {
                    Log.w(
                        "BluetoothVideo",
                        "SECURITY verified-contact store unreadable: cause=${failure.reason} | " +
                            "error=${failure.cause?.javaClass?.simpleName} | " +
                            if (failure.reason.transient) "retried with backoff" else "reset required",
                    )
                }
                unreadableListener?.let { listener -> runCatching { listener(failure.reason) } }
            }
            null
        }
    }

    /**
     * Classifies a failure: unreadable bytes are I/O, a failed AES-GCM tag or bad structure is a
     * corrupt file (or one sealed under a lost key), anything else from the cipher is the Keystore.
     */
    private fun load(): List<VerifiedContact> {
        if (!file.exists()) return emptyList()
        val sealed = try {
            file.readBytes()
        } catch (error: IOException) {
            throw LoadFailure(StoreUnreadableCause.IO_ERROR, error)
        }
        val plain = try {
            cipher.open(sealed)
        } catch (error: AEADBadTagException) {
            throw LoadFailure(StoreUnreadableCause.CORRUPT_FILE, error)
        } catch (error: IllegalArgumentException) {
            throw LoadFailure(StoreUnreadableCause.CORRUPT_FILE, error)
        } catch (error: Exception) {
            throw LoadFailure(StoreUnreadableCause.KEYSTORE_ERROR, error)
        }
        return try {
            parse(plain)
        } catch (error: Exception) {
            throw LoadFailure(StoreUnreadableCause.CORRUPT_FILE, error)
        }
    }

    private fun parse(plain: ByteArray): List<VerifiedContact> {
        DataInputStream(plain.inputStream()).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a verified-contact store" }
            val count = input.readUnsignedShort()
            require(count <= MAX_CONTACTS) { "Too many verified contacts" }
            val contacts = List(count) {
                val fingerprint = IdentityFingerprint.fromBytes(ByteArray(IdentityFingerprint.BYTE_COUNT).also(input::readFully))
                val verifiedAt = input.readLong()
                val method = VerificationMethod.fromWire(input.readUnsignedByte())
                val nameBytes = ByteArray(input.readUnsignedByte()).also(input::readFully)
                VerifiedContact(fingerprint, nameBytes.toString(Charsets.UTF_8), verifiedAt, method)
            }
            require(input.read() == -1) { "Trailing bytes in verified-contact store" }
            return contacts
        }
    }

    private fun save(contacts: List<VerifiedContact>) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeShort(contacts.size)
            contacts.forEach { contact ->
                output.write(contact.fingerprint.bytes())
                output.writeLong(contact.verifiedAtMs)
                output.writeByte(contact.method.wireValue)
                val name = contact.displayName.toByteArray(Charsets.UTF_8)
                output.writeByte(name.size)
                output.write(name)
            }
        }
        file.parentFile?.mkdirs()
        WipeGuard.guardedWrite("verified contacts") { atomicWrite(file, cipher.seal(bytes.toByteArray())) }
        cache = contacts
        latched = null
        lastFailure = null
        transientFailures = 0
        retryAtMs = 0L
    }

    companion object {
        const val MAX_CONTACTS = 1024
        private val MAGIC = "BVC1".toByteArray(Charsets.US_ASCII)

        internal fun samePrefix(a: IdentityFingerprint, b: IdentityFingerprint): Boolean =
            a.nodePrefix().contentEquals(b.nodePrefix())
    }
}

/**
 * Hook run by every end-to-end handshake (calls, streams, forwarded and group endpoints) right
 * after the peer's signature over its identity key verified, and by a receiver for a forwarded
 * stream's original source. It records the full fingerprint for the UI and, when a verified
 * contact shares the peer's node prefix with a different key, logs loudly, notifies listeners and
 * (policy REFUSE) fails. An unreadable store is logged but refuses nobody. No wire bytes change.
 */
object PeerIdentityGate {
    /** Fingerprints by node ID, least recently used evicted first; sized for a busy mesh. */
    internal const val MAX_RECENT = 1024
    private val recent = object : LinkedHashMap<NodeId, IdentityFingerprint>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeId, IdentityFingerprint>?): Boolean =
            size > MAX_RECENT
    }

    @Volatile internal var store: VerifiedContactStore? = null
        set(value) {
            field = value
            value?.unreadableListener = { cause -> storeListeners.forEach { runCatching { it(cause) } } }
        }
    private val storeListeners = java.util.concurrent.CopyOnWriteArrayList<(StoreUnreadableCause) -> Unit>()

    /** Told when the verified-contact store becomes unreadable (fail-open: nobody is refused because of it). */
    fun addStoreUnreadableListener(listener: (StoreUnreadableCause) -> Unit) {
        storeListeners += listener
    }

    fun removeStoreUnreadableListener(listener: (StoreUnreadableCause) -> Unit) {
        storeListeners -= listener
    }
    @Volatile var policy: MismatchPolicy = MismatchPolicy.REFUSE
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(NodeId, IdentityFingerprint?, VerifiedContact?) -> Unit>()

    /** Called with the peer node ID, its full fingerprint (null for a forwarded source) and the verified contact. */
    fun addMismatchListener(listener: (NodeId, IdentityFingerprint?, VerifiedContact?) -> Unit) {
        listeners += listener
    }

    fun removeMismatchListener(listener: (NodeId, IdentityFingerprint?, VerifiedContact?) -> Unit) {
        listeners -= listener
    }

    fun authenticatedFingerprint(peerNodeId: NodeId): IdentityFingerprint? = synchronized(recent) { recent[peerNodeId] }

    /** Called with the verified remote hello's identity key; throws [IdentityMismatchException] to refuse. */
    fun admit(peerNodeId: NodeId, peerIdentityPublicKey: ByteArray): IdentityFingerprint {
        WipeGuard.check("end-to-end session")
        val fingerprint = IdentityFingerprint.fromPublicKey(peerIdentityPublicKey)
        check(fingerprint.nodeId == peerNodeId) { "Node ID does not match its identity key" }
        synchronized(recent) { recent[peerNodeId] = fingerprint }
        val current = store ?: return fingerprint
        enforce(peerNodeId, fingerprint, current.state(peerNodeId, fingerprint), current, "peer")
        return fingerprint
    }

    /**
     * A forwarded stream's original source, known by the 128-bit node ID its authenticated
     * forwarder states in the `BFG1` grant. Throws [IdentityMismatchException] to refuse.
     */
    fun admitForwardedSource(sourceNodeId: NodeId): VerificationState {
        WipeGuard.check("forwarded stream")
        val current = store ?: return VerificationState.UNVERIFIED
        return current.stateForNodeId(sourceNodeId).also {
            enforce(sourceNodeId, null, it, current, "forwarded source")
        }
    }

    private fun enforce(
        nodeId: NodeId,
        fingerprint: IdentityFingerprint?,
        state: VerificationState,
        current: VerifiedContactStore,
        role: String,
    ) {
        val prefixHex = nodeId.toString().take(DiscoveryAnnouncement.NODE_PREFIX_BYTES * 2)
        when (state) {
            VerificationState.STORE_UNREADABLE ->
                // Comparing is impossible, but unverified peers were never refused: keep accepting.
                logSecurity(securityLine(SecurityEvent.STORE_UNREADABLE, role, prefixHex, policy, BuildConfig.DEBUG))
            VerificationState.MISMATCH -> {
                val contact = current.contactForPrefix(nodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES))
                logSecurity(securityLine(SecurityEvent.MISMATCH, role, prefixHex, policy, BuildConfig.DEBUG))
                listeners.forEach { listener -> runCatching { listener(nodeId, fingerprint, contact) } }
                if (policy == MismatchPolicy.REFUSE) {
                    // Review 2026-10-27 (finding 7): the message reaches release logs through the callers' error
                    // lines, so a release build names no peer here either.
                    throw IdentityMismatchException(mismatchMessage(role, prefixHex, BuildConfig.DEBUG))
                }
            }
            else -> Unit
        }
    }

    internal enum class SecurityEvent { STORE_UNREADABLE, MISMATCH }

    /**
     * The gate's log line. Review 2026-10-27 (finding 12): a release log names no peer (a bug report or a shared log
     * would otherwise list who tried to impersonate whom); the event, the role and the policy stay. DEBUG builds keep
     * the 12-hex prefix for the benchmark tools.
     */
    internal fun securityLine(event: SecurityEvent, role: String, prefixHex: String, policy: MismatchPolicy, debug: Boolean): String {
        val peer = if (debug) " $prefixHex" else ""
        return when (event) {
            SecurityEvent.STORE_UNREADABLE -> "verified-contact store unreadable; $role$peer not compared (fail-open)"
            SecurityEvent.MISMATCH ->
                "IDENTITY MISMATCH: $role node prefix$peer matches a verified contact but the key differs; " +
                    "possible impersonation | policy=$policy"
        }
    }

    internal fun mismatchMessage(role: String, prefixHex: String, debug: Boolean): String =
        "The $role${if (debug) " $prefixHex" else ""} uses a different key than the verified contact; possible impersonation"

    internal fun clearForWipe() {
        synchronized(recent) { recent.clear() }
        store?.dropCache()
        store = null
        listeners.clear()
        storeListeners.clear()
    }

    internal fun resetForTest() {
        clearForWipe()
        policy = MismatchPolicy.REFUSE
    }

    private fun logSecurity(message: String) {
        runCatching { Log.w("BluetoothVideo", "SECURITY $message") }
    }
}

/** Process-wide facade the UI binds to. */
/** Get-or-create of a shared key that never replaces an existing one: lookup and creation under one lock. */
internal object AddOnlyKey {
    private val lock = Any()

    fun <K : Any> getOrCreate(get: () -> K?, create: () -> K): K = synchronized(lock) {
        get() ?: create()
    }
}

class IdentityVerification internal constructor(
    private val ownIdentity: () -> SigningIdentity,
    val store: VerifiedContactStore,
) {
    fun ownFingerprint(): IdentityFingerprint = IdentityFingerprint.of(ownIdentity())

    fun safetyCode(peer: IdentityFingerprint): String = SafetyCode.derive(ownFingerprint(), peer)

    fun qrPayload(displayName: String): String = IdentityQrCodec.encode(IdentityQrPayload(ownFingerprint(), displayName))

    fun parseQr(text: String): IdentityQrPayload = IdentityQrCodec.parse(text)

    fun authenticatedPeerFingerprint(peerNodeId: NodeId): IdentityFingerprint? =
        PeerIdentityGate.authenticatedFingerprint(peerNodeId)

    fun markVerified(
        peerNodeId: NodeId,
        confirmed: IdentityFingerprint,
        displayName: String,
        method: VerificationMethod,
    ): MarkVerifiedResult = store.markVerified(
        authenticatedPeerFingerprint(peerNodeId)?.takeIf { it.nodeId == peerNodeId },
        confirmed,
        displayName,
        method,
    )

    fun verificationState(peerNodeId: NodeId, peerFingerprint: IdentityFingerprint): VerificationState =
        store.state(peerNodeId, peerFingerprint)

    fun verifiedContacts(): List<VerifiedContact> = store.all()

    fun rename(fingerprint: IdentityFingerprint, displayName: String): RenameResult = store.rename(fingerprint, displayName)

    /**
     * Naming v2: called after a contact (non-null) or every contact (null) was forgotten, so the claims file
     * ([ContactClaims]) drops the same entries. Set by [create]; never throws into the caller.
     */
    @Volatile internal var onContactsForgotten: ((IdentityFingerprint?) -> Unit)? = null

    fun forget(fingerprint: IdentityFingerprint): Boolean = store.forget(fingerprint).also { forgotten ->
        if (forgotten) runCatching { onContactsForgotten?.invoke(fingerprint) }
        // Rotating IDs, phase 2: a forgotten contact stops being a lease holder and, if it received a lease,
        // this phone starts a new presence key (RD1). Inert while the experiment is off.
        if (forgotten && RotatingDiscoveryFeature.enabled) RotatingDiscovery.presenceVault()?.let { vault ->
            runCatching { vault.forget(fingerprint.nodeId) }
            RotatingDiscovery.requestRecognitionRebuild()
        }
    }

    fun storeUnreadable(): Boolean = store.isUnreadable()

    fun storeUnreadableCause(): StoreUnreadableCause? = store.unreadableCause()

    fun storeBanner(): StoreBanner = store.banner()

    fun storeRetryDueInMs(): Long? = store.retryDueInMs()

    fun addStoreUnreadableListener(listener: (StoreUnreadableCause) -> Unit) =
        PeerIdentityGate.addStoreUnreadableListener(listener)

    fun removeStoreUnreadableListener(listener: (StoreUnreadableCause) -> Unit) =
        PeerIdentityGate.removeStoreUnreadableListener(listener)

    /** Deletes all verified contacts (the recovery for an unreadable store). False if the file stays. */
    fun resetVerifiedContacts(): Boolean = store.reset().also { removed ->
        if (removed) runCatching { onContactsForgotten?.invoke(null) }
        if (removed && RotatingDiscoveryFeature.enabled) RotatingDiscovery.presenceVault()?.let { vault ->
            runCatching { vault.resetContacts() }
            RotatingDiscovery.requestRecognitionRebuild()
        }
    }

    fun setMismatchPolicy(policy: MismatchPolicy) {
        PeerIdentityGate.policy = policy
    }

    fun addMismatchListener(listener: (NodeId, IdentityFingerprint?, VerifiedContact?) -> Unit) =
        PeerIdentityGate.addMismatchListener(listener)

    fun removeMismatchListener(listener: (NodeId, IdentityFingerprint?, VerifiedContact?) -> Unit) =
        PeerIdentityGate.removeMismatchListener(listener)

    companion object {
        /** App-private, excluded from backup and device transfer. */
        const val DIRECTORY = "identity-verification"
        const val FILE = "verified-contacts.bin"
        internal const val AT_REST_ALIAS = "bluetooth-video-mesh-verification-v1"

        @Volatile private var instance: IdentityVerification? = null

        fun get(context: Context): IdentityVerification = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** Installs the gate's store early (MainActivity.onCreate) so sessions check verified contacts. */
        fun attach(context: Context) {
            get(context)
        }

        private fun create(context: Context): IdentityVerification {
            WipeGuard.check("identity verification")
            val file = File(File(context.noBackupFilesDir, DIRECTORY), FILE)
            val store = VerifiedContactStore(file, AesGcmAtRestCipher { verificationAtRestKey() })
            PeerIdentityGate.store = store
            return IdentityVerification({ AndroidNodeIdentityStore().getOrCreate() }, store).also { verification ->
                verification.onContactsForgotten = { fingerprint ->
                    if (fingerprint == null) ContactClaims.store(context).reset() else ContactClaims.forget(context, fingerprint)
                }
            }
        }

        internal fun clearForWipe() {
            synchronized(this) { instance = null }
            PeerIdentityGate.clearForWipe()
        }

        /** The verified-contacts at-rest cipher, shared with the rotating-ID presence store (same Keystore key). */
        internal fun atRestCipher(): AtRestCipher = AesGcmAtRestCipher { verificationAtRestKey() }

        /**
         * Review 2026-10-27 (finding 3): several sealed stores share this alias, and two of them sealing for the
         * first time at once could each generate a key; the second generation replaces the alias (AOSP
         * AndroidKeyStoreKeyGeneratorSpi), so what the first had sealed could no longer be opened. Lookup and creation are
         * one step under one process-wide lock ([AddOnlyKey]): a key is created only when it is still absent.
         */
        private fun verificationAtRestKey(): SecretKey {
            WipeGuard.check("verification key")
            return AddOnlyKey.getOrCreate(
                get = {
                    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    keyStore.getKey(AT_REST_ALIAS, null) as? SecretKey
                },
                create = ::generateVerificationAtRestKey,
            )
        }

        private fun generateVerificationAtRestKey(): SecretKey {
            WipeGuard.check("verification key")
            return KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            ).run {
                init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        AT_REST_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }
    }
}
