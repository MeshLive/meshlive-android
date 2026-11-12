package com.example.bluetoothtest

import android.content.Context

/**
 * One remembered call: who (12-hex node prefix), voice or video, and when it ended. [fingerprintHex] is the
 * caller's full identity fingerprint (64 hex) when known, so a row can block the person (CallBlocking.kt).
 * [blocked] marks a call that was declined without ringing because "Block unknown callers" is on; nobody is named
 * (the caller's identity was never revealed), so its prefix is empty.
 */
internal enum class CallDirection { UNKNOWN, INCOMING, OUTGOING }
internal enum class CallResult(val labelRes: Int) {
    ANSWERED(R.string.recent_answered), MISSED(R.string.recent_missed), DECLINED(R.string.recent_declined),
    NO_ANSWER(R.string.recent_no_answer), FAILED(R.string.recent_failed),
}

internal fun recentCallResult(direction: CallDirection, answered: Boolean, declined: Boolean, message: String): CallResult = when {
    answered -> CallResult.ANSWERED
    direction == CallDirection.INCOMING && MissedCallNotification.isMissed(message) -> CallResult.MISSED
    declined || message == "Call declined" || message == BUSY_DECLINE_MESSAGE -> CallResult.DECLINED
    message == CallRingTimeout.NO_ANSWER_MESSAGE || message == "Call cancelled" -> CallResult.NO_ANSWER
    else -> CallResult.FAILED
}

internal data class RecentCall(
    val nodePrefixHex: String,
    val video: Boolean,
    val atMs: Long,
    val fingerprintHex: String = "",
    val blocked: Boolean = false,
    /**
     * Naming v2 (review F20): an unverified stranger's entry holds only the generated name they showed and the time. Their
     * node ID and fingerprint stay in memory for the call and are persisted only if the person is then verified, so a seized
     * phone does not hold a list of stranger keys. Such an entry has an empty [nodePrefixHex] and cannot be called back.
     */
    val strangerName: String = "",
    val direction: CallDirection = CallDirection.UNKNOWN,
    val result: CallResult = CallResult.ANSWERED,
)

/**
 * Home "Recent": the last 50 ended calls, newest first. Legacy entries keep unknown directions.
 * Stored only on this phone (app-private preferences, cleared by Emergency wipe). Names are not
 * stored: the row asks PeerLabels at display time (contact label or handle).
 *
 * Review 2026-10-27 (finding 6): the list holds node prefixes and, for a caller that showed its real key, the full
 * fingerprint (so its row can block the person), also for people who are not verified contacts. It was plain text in the
 * preferences file. It is sealed now with the verified-contacts Keystore key ([cipher]); the plain `calls_v2`/`calls_v1`
 * values of earlier builds are read once, written back sealed and removed. A sealed value that fails authentication (damage,
 * another phone's key) is replaced by the next write; a temporary Keystore or storage failure is never written over (Recent
 * reads as empty and records nothing until it opens again, or until the user clears the history, [clear]). Every write is
 * non-throwing (review 2026-10-27, finding 1: a throw on the call-end path crashed the app, also during an emergency wipe).
 */
internal object RecentCalls {
    /** Listed in EmergencyWipe.KNOWN_PREFERENCES. */
    const val PREFERENCES = "recent_calls"
    private const val SEALED_KEY = "calls_sealed_v1"
    private const val KEY = "calls_v2"
    private const val OLD_KEY = "calls_v1"
    const val MAX_ENTRIES = 50

    /** Production: the verified-contacts at-rest cipher; JVM tests install their own. */
    @Volatile internal var cipher: () -> AtRestCipher = { IdentityVerification.atRestCipher() }

    /** A sealed value that could not be opened for a reason that may pass (Keystore busy, storage error). */
    private class TemporarilyUnreadable : IllegalStateException("Recent calls cannot be opened now")

    /** "gold fox", "gold fox 42", "gold fox-7a7d" and "gold fox 42-7a7d": a generated name, lower case, no commas. */
    private val STRANGER_NAME = Regex("[a-z]{3,7} [a-z]{3,7}( [0-9]{2})?(-[0-9a-f]{4})?")

    @Synchronized
    fun load(context: Context): List<RecentCall> = project(loadStored(context), DebugTunables.completeRecentCalls)

    /** True while a stored history exists that this phone cannot open now (the screen offers to clear it). */
    @Synchronized
    fun isUnreadable(context: Context): Boolean = runCatching { readStored(context) }.exceptionOrNull() is TemporarilyUnreadable

    /** Deletes the whole history, including one that cannot be opened ("Clear history"). */
    @Synchronized
    fun clear(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }.getOrDefault(false)

    /** Storage always retains the full history, even while displaying the five-person control arm. */
    private fun loadStored(context: Context): List<RecentCall> = runCatching { readStored(context) }.getOrDefault(emptyList())

    /**
     * The stored calls. A value that fails authentication reads as empty (the next write replaces it); any other failure to
     * open throws [TemporarilyUnreadable] (nothing is written over it).
     */
    private fun readStored(context: Context): List<RecentCall> {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        prefs.getString(SEALED_KEY, null)?.let { stored ->
            val bytes = runCatching { java.util.Base64.getDecoder().decode(stored) }.getOrNull() ?: return emptyList()
            val plain = try {
                cipher().open(bytes)
            } catch (damaged: javax.crypto.AEADBadTagException) {
                return emptyList()
            } catch (tooShort: IllegalArgumentException) {
                return emptyList()
            } catch (other: Exception) {
                throw TemporarilyUnreadable()
            }
            return decode(String(plain, Charsets.UTF_8))
        }
        if (!prefs.contains(KEY) && !prefs.contains(OLD_KEY)) return emptyList()
        // An earlier build's plain text: written back sealed, then removed (kept only if sealing fails now).
        val legacy = if (prefs.contains(KEY)) decode(prefs.getString(KEY, null)) else decode(prefs.getString(OLD_KEY, null))
        store(context, legacy)
        return legacy
    }

    /** Seals and stores [calls], removing any plain value. False (nothing written) during an emergency wipe or on failure. */
    private fun store(context: Context, calls: List<RecentCall>): Boolean {
        if (WipeGuard.active) return false
        return runCatching {
            WipeGuard.guardedWrite("recent calls") {
                val sealed = cipher().seal(encode(calls).toByteArray(Charsets.UTF_8))
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(SEALED_KEY, java.util.Base64.getEncoder().encodeToString(sealed))
                    .remove(KEY)
                    .remove(OLD_KEY)
                    .commit()
            }
        }.getOrElse { error ->
            runCatching { android.util.Log.w("BluetoothVideo", "recent_calls: not stored (${error.javaClass.simpleName})") }
            false
        }
    }

    /** Applies [change] to the stored list and stores it; false when nothing was stored. Never throws. */
    private fun update(context: Context, change: (List<RecentCall>) -> List<RecentCall>?): Boolean {
        if (WipeGuard.active) return false
        val current = try {
            readStored(context)
        } catch (error: Exception) {
            runCatching { android.util.Log.w("BluetoothVideo", "recent_calls: not recorded (${error.javaClass.simpleName})") }
            return false
        }
        val updated = change(current) ?: return false
        return store(context, updated)
    }

    /** Records one ended call; false when it could not be stored (never throws: this runs as a call ends). */
    @Synchronized
    fun record(context: Context, call: RecentCall): Boolean = update(context) { add(it, call) }

    /**
     * The stranger shown as [strangerName] has just been verified as [fingerprintHex]: their entry becomes an ordinary entry
     * for that person (prefix and fingerprint, named by the contact label at display time, callable like a contact). False
     * when no such stranger entry is stored.
     */
    @Synchronized
    fun promoteStranger(context: Context, strangerName: String, fingerprintHex: String): Boolean =
        update(context) { promote(it, strangerName, fingerprintHex) }

    /**
     * Pure part of [promoteStranger]: the stranger entry keeps its place, time and call type and takes the person's prefix
     * and fingerprint; earlier calls are preserved. Null when nothing matches or
     * the fingerprint is not 64 hex.
     */
    fun promote(existing: List<RecentCall>, strangerName: String, fingerprintHex: String): List<RecentCall>? {
        val fingerprint = fingerprintHex.lowercase()
        if (strangerName.isEmpty() || !fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
        val index = existing.indexOfFirst { it.strangerName == strangerName }
        if (index < 0) return null
        val prefix = fingerprint.take(12)
        val promoted = existing[index].copy(nodePrefixHex = prefix, fingerprintHex = fingerprint, strangerName = "")
        return existing.mapIndexedNotNull { i, call ->
            when {
                i == index -> promoted
                else -> call
            }
        }
    }

    /** A call declined by "Block unknown callers" (nobody is named). */
    @Synchronized
    fun recordBlocked(context: Context, atMs: Long): Boolean =
        update(context) { add(it, RecentCall("", false, atMs, blocked = true, direction = CallDirection.INCOMING, result = CallResult.DECLINED)) }

    /** A non-destructive display projection: the old Recent recorded active calls and anonymous blocked calls. */
    fun project(calls: List<RecentCall>, complete: Boolean): List<RecentCall> = if (complete) calls else
        calls.filter { it.blocked || it.result == CallResult.ANSWERED }.distinctBy {
            when {
                it.blocked -> "blocked"
                it.strangerName.isNotEmpty() -> "stranger:${it.strangerName}"
                else -> "person:${it.nodePrefixHex}"
            }
        }.take(5)

    /** Newest first, one entry per call, at most [MAX_ENTRIES]; control projection never alters stored calls. */
    fun add(existing: List<RecentCall>, call: RecentCall, complete: Boolean = true): List<RecentCall> =
        project((listOf(call) + existing).take(MAX_ENTRIES), complete)

    /**
     * One entry per line: `prefix,v|a,epochMs[,fingerprint[,b]]` (plain text; no JSON so it unit-tests on the JVM).
     * Older lines have three fields; v2 appends `|direction|result` to each identity record.
     */
    fun encode(calls: List<RecentCall>): String = calls.joinToString("\n") {
        val base = "${it.nodePrefixHex},${if (it.video) "v" else "a"},${it.atMs}"
        val identity = when {
            it.blocked -> "$base,,b"
            it.strangerName.isNotEmpty() -> "$base,,s,${it.strangerName}"
            it.fingerprintHex.isNotEmpty() -> "$base,${it.fingerprintHex}"
            else -> base
        }
        "$identity|${it.direction.name}|${it.result.name}"
    }

    fun decode(text: String?): List<RecentCall> = text.orEmpty().lines().mapNotNull { line ->
        val fields = line.trim().split('|')
        if (fields.size != 1 && fields.size != 3) return@mapNotNull null
        val direction = if (fields.size == 1) CallDirection.UNKNOWN else
            CallDirection.entries.firstOrNull { it.name == fields[1] } ?: return@mapNotNull null
        val result = if (fields.size == 1) CallResult.ANSWERED else
            CallResult.entries.firstOrNull { it.name == fields[2] } ?: return@mapNotNull null
        val parts = fields[0].split(',')
        if (parts.size !in 3..6) return@mapNotNull null
        val prefix = parts[0].lowercase()
        val at = parts[2].toLongOrNull()
        if (at == null || parts[1] !in setOf("v", "a")) return@mapNotNull null
        val fingerprint = parts.getOrNull(3).orEmpty().lowercase()
        // A stranger: `,v|a,epochMs,,s,<generated name>` (no prefix, no fingerprint).
        if (parts.getOrNull(4) == "s") {
            val name = parts.getOrNull(5).orEmpty()
            if (parts.size != 6 || prefix.isNotEmpty() || fingerprint.isNotEmpty() || !STRANGER_NAME.matches(name)) return@mapNotNull null
            return@mapNotNull RecentCall("", parts[1] == "v", at, strangerName = name, direction = direction, result = result)
        }
        if (parts.size == 6) return@mapNotNull null
        val blocked = parts.getOrNull(4) == "b"
        if (parts.size == 5 && !blocked) return@mapNotNull null
        if (fingerprint.isNotEmpty() && !fingerprint.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
        if (blocked) {
            if (prefix.isNotEmpty() || fingerprint.isNotEmpty()) return@mapNotNull null
            return@mapNotNull RecentCall("", parts[1] == "v", at, blocked = true, direction = direction, result = result)
        }
        if (!prefix.matches(Regex("[0-9a-f]{12}")) || (fingerprint.isNotEmpty() && !fingerprint.startsWith(prefix))) {
            return@mapNotNull null
        }
        RecentCall(prefix, parts[1] == "v", at, fingerprint, direction = direction, result = result)
    }.take(MAX_ENTRIES)
}

/**
 * Naming v2 (F20) and saved-contact calls: which real fingerprint revealed itself behind a stranger's recent entry, kept in
 * memory only (never persisted, so a seized phone holds no list of stranger keys), so the entry can become the contact's if
 * the user verifies that person after the call. At most [RecentCalls.MAX_ENTRIES] links, newest kept.
 */
internal object StrangerRecentLinks {
    private val byFingerprint = LinkedHashMap<String, String>()

    @Synchronized
    fun remember(fingerprintHex: String, strangerName: String) {
        if (strangerName.isEmpty()) return
        val key = fingerprintHex.lowercase()
        byFingerprint.remove(key)
        byFingerprint[key] = strangerName
        while (byFingerprint.size > RecentCalls.MAX_ENTRIES) byFingerprint.remove(byFingerprint.keys.first())
    }

    /** The stranger name linked to [fingerprintHex], removed from memory (it is used once). */
    @Synchronized
    fun take(fingerprintHex: String): String? = byFingerprint.remove(fingerprintHex.lowercase())

    @Synchronized
    fun clear() = byFingerprint.clear()
}
