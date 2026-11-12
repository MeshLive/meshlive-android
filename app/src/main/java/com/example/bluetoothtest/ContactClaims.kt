package com.example.bluetoothtest

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What a verified contact says about themselves (Design G, G3; PROTOCOL "Local stores added"). A sealed file
 * `identity-verification/contact-claims.bin` maps a contact's full fingerprint to the last verified claimed name (at most
 * 32 bytes, NFC), the time, and the fact "scanned both ways" (the two `K_qr` events of BVMID2). Only contacts get an entry;
 * a stranger's name stays in memory for the session (never persisted, review F20).
 *
 * It is separate from `verified-contacts.bin` (whose format is unchanged), so an older build ignores it and a downgrade
 * loses only the note and the "verified each other" wording, never a contact or its shield. The claimed name is NEVER the
 * title of a contact: YOUR label wins ([DisplayIdentityResolver]); the claim is shown in the contact's detail and once, as a
 * note, when it changes ("Sunita now calls herself 'Sunita K'. [Use it as your label] [Ignore]").
 *
 * Sealed with the verified-contacts Keystore key and [AtRestCipher], atomic writes, never overwritten while unreadable.
 * Forget and the emergency wipe delete it (it lives under the app's no-backup directory, which the wipe empties; the
 * in-memory copy is dropped by [ContactClaims.clearForWipe]).
 */
internal data class ContactClaim(
    val fingerprint: IdentityFingerprint,
    /** The last verified claimed name, empty when only the mutual-scan fact is known. */
    val claimedName: String,
    val claimedAtMs: Long,
    /** The name the user has already seen (or ignored); a difference from [claimedName] is a pending note. */
    val acknowledgedName: String,
    /** Both phones scanned each other's BVMID2 code ("verified each other"). */
    val scannedBothWays: Boolean,
) {
    val hasPendingChange: Boolean get() = claimedName.isNotEmpty() && claimedName != acknowledgedName
}

internal enum class ClaimRecordResult { FIRST, UNCHANGED, CHANGED, INVALID_NAME, STORE_UNREADABLE, STORE_FULL }

internal class ContactClaimsStore(
    private val file: File,
    private val cipher: AtRestCipher,
) {
    private var cache: List<ContactClaim>? = null
    private var latched = false

    /** Readable entries; empty when the file cannot be read (see [isUnreadable]). */
    @Synchronized
    fun all(): List<ContactClaim> = loadOrNull().orEmpty()

    @Synchronized
    fun isUnreadable(): Boolean = loadOrNull() == null

    @Synchronized
    fun get(fingerprint: IdentityFingerprint): ContactClaim? = loadOrNull()?.firstOrNull { it.fingerprint == fingerprint }

    /**
     * Records [name] as the last verified claimed name of the contact [fingerprint]. The caller guarantees that the name came
     * from a signed label that passed the receiver checks and that [fingerprint] is a verified contact. A name that is not
     * canonical person text is refused (nothing changes). The first name is acknowledged at once; a different later name
     * becomes a pending change until [acknowledge].
     */
    @Synchronized
    fun recordClaim(fingerprint: IdentityFingerprint, name: String, nowMs: Long): ClaimRecordResult {
        if (!LabelText.isCanonical(LabelKind.PERSON_NAME, name)) return ClaimRecordResult.INVALID_NAME
        val current = loadOrNull() ?: return ClaimRecordResult.STORE_UNREADABLE
        val existing = current.firstOrNull { it.fingerprint == fingerprint }
        if (existing == null) {
            if (current.size >= MAX_CLAIMS) return ClaimRecordResult.STORE_FULL
            save(current + ContactClaim(fingerprint, name, nowMs, acknowledgedName = name, scannedBothWays = false))
            return ClaimRecordResult.FIRST
        }
        if (existing.claimedName == name) return ClaimRecordResult.UNCHANGED
        val first = existing.claimedName.isEmpty()
        save(
            current.map {
                if (it.fingerprint != fingerprint) it else it.copy(
                    claimedName = name,
                    claimedAtMs = nowMs,
                    acknowledgedName = if (first) name else it.acknowledgedName,
                )
            },
        )
        return if (first) ClaimRecordResult.FIRST else ClaimRecordResult.CHANGED
    }

    /** The user saw or ignored the latest claim (the note is not shown again until it changes). */
    @Synchronized
    fun acknowledge(fingerprint: IdentityFingerprint): Boolean {
        val current = loadOrNull() ?: return false
        val existing = current.firstOrNull { it.fingerprint == fingerprint } ?: return false
        if (existing.acknowledgedName == existing.claimedName) return false
        save(current.map { if (it.fingerprint == fingerprint) it.copy(acknowledgedName = it.claimedName) else it })
        return true
    }

    /** Records the "scanned both ways" fact for a contact (creates an entry with no claimed name when needed). */
    @Synchronized
    fun markScannedBothWays(fingerprint: IdentityFingerprint, nowMs: Long): Boolean {
        val current = loadOrNull() ?: return false
        val existing = current.firstOrNull { it.fingerprint == fingerprint }
        return when {
            existing?.scannedBothWays == true -> true
            existing != null -> {
                save(current.map { if (it.fingerprint == fingerprint) it.copy(scannedBothWays = true) else it })
                true
            }
            current.size >= MAX_CLAIMS -> false
            else -> {
                save(current + ContactClaim(fingerprint, "", nowMs, "", scannedBothWays = true))
                true
            }
        }
    }

    /** Forget deletes this contact's entry. */
    @Synchronized
    fun forget(fingerprint: IdentityFingerprint): Boolean {
        val current = loadOrNull() ?: return false
        val remaining = current.filterNot { it.fingerprint == fingerprint }
        if (remaining.size == current.size) return false
        if (remaining.isEmpty()) deleteFile() else save(remaining)
        return true
    }

    /** Deletes the file (also the recovery for an unreadable one). */
    @Synchronized
    fun reset(): Boolean {
        deleteFile()
        return true
    }

    @Synchronized
    internal fun dropCache() {
        cache = null
        latched = false
    }

    private fun deleteFile() {
        runCatching { file.delete() }
        cache = emptyList()
        latched = false
    }

    private fun loadOrNull(): List<ContactClaim>? {
        cache?.let { return it }
        if (latched) return null
        return try {
            load().also { cache = it }
        } catch (_: Exception) {
            // Corrupt, wrong key or unreadable: never silently empty and never overwritten; reset() is the recovery.
            latched = true
            null
        }
    }

    private fun load(): List<ContactClaim> {
        if (!file.exists()) return emptyList()
        val plain = cipher.open(file.readBytes())
        DataInputStream(plain.inputStream()).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a contact-claims store" }
            val count = input.readUnsignedShort()
            require(count <= MAX_CLAIMS) { "Too many claims" }
            val claims = List(count) {
                val fingerprint = IdentityFingerprint.fromBytes(ByteArray(IdentityFingerprint.BYTE_COUNT).also(input::readFully))
                val at = input.readLong()
                val flags = input.readUnsignedByte()
                val name = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                val ack = ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                require(name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES && ack.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
                ContactClaim(fingerprint, name, at, ack, scannedBothWays = flags and FLAG_BOTH_WAYS != 0)
            }
            require(input.read() == -1) { "Trailing bytes in contact-claims store" }
            return claims
        }
    }

    private fun save(claims: List<ContactClaim>) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeShort(claims.size)
            claims.forEach { claim ->
                output.write(claim.fingerprint.bytes())
                output.writeLong(claim.claimedAtMs)
                output.writeByte(if (claim.scannedBothWays) FLAG_BOTH_WAYS else 0)
                val name = claim.claimedName.toByteArray(Charsets.UTF_8)
                output.writeByte(name.size)
                output.write(name)
                val ack = claim.acknowledgedName.toByteArray(Charsets.UTF_8)
                output.writeByte(ack.size)
                output.write(ack)
            }
        }
        file.parentFile?.mkdirs()
        WipeGuard.guardedWrite("contact claims") { atomicWrite(file, cipher.seal(bytes.toByteArray())) }
        cache = claims
        latched = false
    }

    companion object {
        const val MAX_CLAIMS = 1024
        const val MAX_NAME_BYTES = 32
        private const val FLAG_BOTH_WAYS = 1
        private val MAGIC = "BCC1".toByteArray(Charsets.US_ASCII)
    }
}

/** The process-wide claims store on the phone (file next to the verified contacts, same Keystore key). */
internal object ContactClaims {
    const val FILE = "contact-claims.bin"

    @Volatile private var store: ContactClaimsStore? = null

    fun store(context: Context): ContactClaimsStore = store ?: synchronized(this) {
        store ?: run {
            WipeGuard.check("contact claims")
            val file = File(File(context.applicationContext.noBackupFilesDir, IdentityVerification.DIRECTORY), FILE)
            ContactClaimsStore(file, IdentityVerification.atRestCipher()).also { store = it }
        }
    }

    internal fun installForTest(testStore: ContactClaimsStore?) {
        synchronized(this) { store = testStore }
    }

    /** Forget deletes the entry together with the contact. */
    fun forget(context: Context, fingerprint: IdentityFingerprint): Boolean =
        runCatching { store(context).forget(fingerprint) }.getOrDefault(false)

    /** Emergency wipe: forget the in-memory copy (the file and its Keystore key are deleted by the wipe itself). */
    internal fun clearForWipe() {
        synchronized(this) {
            store?.dropCache()
            store = null
        }
    }
}
