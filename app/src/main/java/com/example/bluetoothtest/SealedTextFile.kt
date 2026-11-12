package com.example.bluetoothtest

import java.io.File
import java.io.IOException
import javax.crypto.AEADBadTagException

/** The sealed file exists but cannot be opened now (Keystore busy or locked, a storage error): retry later, never write over it. */
internal class SealedTextUnavailable(cause: Throwable?) : IOException("Sealed memory cannot be opened now", cause)

/**
 * A small local memory sealed with the verified-contacts Keystore key ([IdentityVerification.atRestCipher]). Review
 * 2026-10-27 (finding 6): the BMH3 pins, the Classic address book and the bad-2M PHY memory each name peers this phone
 * met (node prefixes, Bluetooth addresses) and were plain text in no-backup storage, readable from an extracted file
 * system; the verified contacts, the block list and the presence keys were already sealed.
 *
 * Two kinds of failure, as [VerifiedContactStore] tells them apart (security review of 2026-10-27, item 6): a file that
 * fails authentication or is malformed (damage, another key) reads as empty and the next write replaces it; a failure that
 * may pass (the Keystore, the disk) throws [SealedTextUnavailable], so the caller keeps its memory unloaded, retries later
 * and never writes over the file in between.
 *
 * [legacyPlain] is the plaintext file an earlier build wrote: it is read once, written back sealed and deleted (also when
 * sealing fails then: its content stays in memory for this process instead of on the disk).
 */
internal class SealedTextFile(
    private val file: File,
    private val cipher: () -> AtRestCipher,
    private val legacyPlain: File? = null,
) {
    private var migratedText: String? = null

    /** The text, or null when there is none or the file is damaged; throws [SealedTextUnavailable] for a passing failure. */
    @Synchronized
    fun read(): String? {
        migrate()
        if (!file.isFile) return migratedText
        val sealed = try {
            file.readBytes()
        } catch (error: IOException) {
            throw SealedTextUnavailable(error)
        }
        val plain = try {
            cipher().open(sealed)
        } catch (damaged: AEADBadTagException) {
            return migratedText
        } catch (malformed: IllegalArgumentException) {
            return migratedText
        } catch (error: Exception) {
            throw SealedTextUnavailable(error)
        }
        return String(plain, Charsets.UTF_8)
    }

    /** Seals and replaces the file; refused once an emergency wipe started (it would recreate what the wipe deletes). */
    @Synchronized
    fun write(text: String) {
        // The wipe waits for a write that passed this check (WipeGuard.awaitWriters), so it never lands after the deletion.
        WipeGuard.guardedWrite("sealed memory ${file.name}") {
            atomicWrite(file, cipher().seal(text.toByteArray(Charsets.UTF_8)))
        }
        migratedText = null
    }

    @Synchronized
    fun delete() {
        migratedText = null
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
        legacyPlain?.let { plain ->
            runCatching { plain.delete() }
            runCatching { File(plain.parentFile, "${plain.name}.tmp").delete() }
        }
    }

    /** Moves an earlier build's plaintext into the sealed file (or memory) and deletes it; safe to call at any time. */
    @Synchronized
    fun migrate() {
        val plain = legacyPlain ?: return
        if (!plain.isFile) return
        val text = runCatching { plain.readText() }.getOrNull()
        if (text != null && !file.isFile) {
            if (runCatching { write(text) }.isFailure) migratedText = text
        }
        runCatching { plain.delete() }
        runCatching { File(plain.parentFile, "${plain.name}.tmp").delete() }
    }
}
