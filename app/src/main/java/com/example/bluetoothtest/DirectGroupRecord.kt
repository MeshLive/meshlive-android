package com.example.bluetoothtest

import android.content.Context
import java.io.File

/**
 * The sealed list of Wi-Fi Direct network names this app created, so a group stranded by a crash can be removed
 * without ever touching another app's group. Names only: no passphrase, ticket, key or address. [AM 9]
 */
internal class DirectGroupRecord(
    private val storage: Storage,
    private val maxNames: Int = DirectLimits.RECORD_MAX_NAMES,
    private val onError: (op: String) -> Unit = {},
) {
    interface Storage {
        fun read(): String?
        fun write(text: String)
        fun delete()
    }

    /** Appends a name, de-duplicates, keeps the newest [maxNames] (drops the oldest). */
    @Synchronized
    fun add(name: String) {
        val current = namesLocked()
        if (name in current) return
        val updated = (current + name).takeLast(maxNames)
        storage.write(updated.joinToString("\n"))
    }

    @Synchronized
    fun contains(name: String): Boolean = name in namesLocked()

    /** The last name gone deletes the file, so "nothing recorded" means "no file". [AM 9] */
    @Synchronized
    fun remove(name: String) {
        val updated = namesLocked() - name
        if (updated.isEmpty()) storage.delete() else storage.write(updated.joinToString("\n"))
    }

    @Synchronized
    fun names(): List<String> = namesLocked()

    @Synchronized
    fun clear() {
        storage.delete()
    }

    // A9 review P1-3: the accessors below never throw. They are the only ones used from asynchronous P2P callbacks and
    // timers, where a SealedTextUnavailable (Keystore, disk; a deleted key during an emergency wipe) or a WipeGuard
    // refusal would otherwise kill the process. A read failure means "cannot tell": never a reason to remove a group or
    // to prune the record. A write failure is reported to [onError] and leaves the record as it is.

    /** null = the record cannot be read now. */
    fun containsOrNull(name: String): Boolean? = attempt("read") { contains(name) }

    /** null = the record cannot be read now. */
    fun namesOrNull(): List<String>? = attempt("read") { names() }

    /** false = not removed (the record is left as it is). */
    fun tryRemove(name: String): Boolean = attempt("remove") { remove(name) } != null

    fun tryClear(): Boolean = attempt("clear") { clear() } != null

    private inline fun <T> attempt(op: String, block: () -> T): T? =
        try {
            block()
        } catch (error: Exception) {
            runCatching { onError(op) }
            null
        }

    private fun namesLocked(): List<String> =
        storage.read()?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
}

/** Production storage: the same sealed-file pattern as the BMH3 pins. The wipe deletes noBackupFilesDir. */
internal fun directGroupRecord(context: Context): DirectGroupRecord {
    val sealed = SealedTextFile(
        File(context.applicationContext.noBackupFilesDir, "direct-groups.sealed"),
        { IdentityVerification.atRestCipher() },
    )
    return DirectGroupRecord(
        object : DirectGroupRecord.Storage {
            override fun read() = sealed.read()
            override fun write(text: String) = sealed.write(text) // WipeGuard.check inside: refused during an emergency wipe
            override fun delete() = sealed.delete()
        },
        onError = { op ->
            WifiLog.emit(
                WifiLog.upgrade(
                    "abort",
                    "carrier" to "direct",
                    "reason" to if (WipeGuard.active) "record_${op}_wipe" else "record_${op}_unavailable",
                ),
            )
        },
    )
}
