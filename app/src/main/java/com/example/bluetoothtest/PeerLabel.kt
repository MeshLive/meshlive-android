package com.example.bluetoothtest

/**
 * How a person (or a broadcast) is named on screen: one clean name line, never raw hex.
 *
 * Identity naming design, phase 1 (reviewed R1-R10):
 * - [handle]: Crockford Base32 of the 48-bit announcement prefix, `XXXXX-XXXXX`, shown everywhere.
 *   R5: the full handle is shown for everyone who is not a contact; [shortHandle] only beside a
 *   contact's own label. The handle is a UI rendering only: logs and host tools keep 12-hex (R10).
 * - [name]: your own label for a verified contact (CONTACT) or, after the signed-label wire change,
 *   a self-claimed name (SELF_NAMED); otherwise null and the handle is the name.
 * - [streamTitle]: optional signed label; a title read from an advert stays
 *   unconfirmed ([streamTitleConfirmed] false) until the stream is joined.
 * The OS Bluetooth name is never used as a name (it is public, forgeable and often a real name).
 */
internal data class PeerLabel(
    val handle: String,
    val name: String? = null,
    val nameKind: NameKind = NameKind.NONE,
    val streamTitle: String? = null,
    val streamTitleConfirmed: Boolean = false,
    /**
     * A self-claimed [name] that reads like this contact name (or your own) without being the same text
     * (UTS #39 confusable skeleton, [LookAlike]); the call screen warns. Null for everything else.
     */
    val lookAlikeOf: String? = null,
) {
    enum class NameKind { NONE, CONTACT, SELF_NAMED }

    val shortHandle: String get() = handle.substringBefore('-')

    /** The single name line for call screens. */
    val displayName: String get() = name ?: handle

    /**
     * The name line in lists. R5: strangers always show the full handle (a 25-bit short handle can
     * be ground in minutes); a self-claimed name is quoted and marked so it never looks like a contact.
     */
    @Suppress("UNUSED_PARAMETER")
    fun listName(fullHandle: Boolean = false): String = when (nameKind) {
        NameKind.CONTACT -> name ?: handle
        NameKind.SELF_NAMED -> name?.let { "\u201C$it\u201D \u00B7 $handle" } ?: handle
        NameKind.NONE -> handle
    }

    /** Ringing/in-call header: the contact label, the quoted self-claimed name, or the full handle. */
    fun callerLine(): String = when (nameKind) {
        NameKind.CONTACT -> name ?: handle
        NameKind.SELF_NAMED -> name?.let { "\u201C$it\u201D" } ?: handle
        NameKind.NONE -> handle
    }
}

internal fun interface PeerLabelProvider {
    /** [nodePrefixHex] is the 12-hex announcement prefix; [contactName] your label for a matching contact. */
    fun label(nodePrefixHex: String, contactName: String?): PeerLabel
}

internal object PeerLabels {
    /** Replaced by the naming implementation (signed labels, subjects, titles) after review. */
    @Volatile var provider: PeerLabelProvider = DefaultPeerLabelProvider

    /**
     * Names a self-claimed name is compared with for look-alikes: your contacts' labels and your own
     * name. Set by the activity; empty (no warning) until then.
     */
    @Volatile var knownNames: () -> Collection<String> = { emptyList() }

    fun forPeer(nodePrefixHex: String, contactName: String? = null): PeerLabel =
        provider.label(nodePrefixHex.lowercase(), contactName)

    /** Prefixes whose short handles collide among [prefixes] (those rows show the full handle). */
    fun collidingShortHandles(prefixes: Collection<String>): Set<String> =
        prefixes.groupBy { IdentityHandle.fromPrefixHex(it).substringBefore('-') }
            .values.filter { it.size > 1 }.flatten().toSet()
}

internal object DefaultPeerLabelProvider : PeerLabelProvider {
    override fun label(nodePrefixHex: String, contactName: String?): PeerLabel {
        val handle = IdentityHandle.fromPrefixHex(nodePrefixHex)
        val contact = contactName?.trim()?.takeIf { it.isNotEmpty() }
        val session = SessionPeerLabels.forPrefix(nodePrefixHex)
        val base = if (contact != null) {
            PeerLabel(handle, contact, PeerLabel.NameKind.CONTACT)
        } else if (session?.selfName != null) {
            PeerLabel(handle, session.selfName, PeerLabel.NameKind.SELF_NAMED)
        } else {
            PeerLabel(handle)
        }
        return base.copy(
            streamTitle = session?.streamTitle,
            streamTitleConfirmed = session?.streamTitle != null,
            lookAlikeOf = base.name.takeIf { base.nameKind == PeerLabel.NameKind.SELF_NAMED }
                ?.let { LookAlike.find(it, PeerLabels.knownNames()) },
        )
    }
}

/**
 * Signed labels verified in the current call or stream sessions, by 12-hex signer prefix
 * (experimental peer labels). Cleared when the session ends: strangers' names are never cached.
 */
internal object SessionPeerLabels {
    data class Entry(val selfName: String? = null, val streamTitle: String? = null)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun forPrefix(prefixHex: String): Entry? = entries[prefixHex.lowercase()]

    fun update(prefixHex: String, transform: (Entry) -> Entry) {
        entries.compute(prefixHex.lowercase()) { _, old -> transform(old ?: Entry()) }
    }

    fun clear(prefixHex: String) {
        entries.remove(prefixHex.lowercase())
    }

    /**
     * BMH3 reveal: what a throwaway [fromPrefixHex] said in this call now belongs to the revealed identity [toPrefixHex], the
     * key the call screen and "Save contact as" read. A value already held for [toPrefixHex] wins.
     */
    fun move(fromPrefixHex: String, toPrefixHex: String) {
        val from = fromPrefixHex.lowercase()
        val to = toPrefixHex.lowercase()
        if (from == to) return
        val moved = entries.remove(from) ?: return
        entries.compute(to) { _, old ->
            if (old == null) {
                moved
            } else {
                Entry(old.selfName ?: moved.selfName, old.streamTitle ?: moved.streamTitle)
            }
        }
    }

    fun clearAll() = entries.clear()
}

/**
 * The Go live title dialog ("Stream title (optional)"). Save goes live with the typed title, Skip goes live without one, and
 * Back or a tap outside does not go live. What the user typed prefills the next dialog, so a cancelled dialog loses nothing.
 */
internal object StreamTitleChoice {
    enum class Action { SAVE, SKIP, CANCEL }

    fun goesLive(action: Action): Boolean = action != Action.CANCEL

    /** The title the new stream carries: the saved one, or none after Skip. */
    fun streamTitle(action: Action, saved: String?): String? = if (action == Action.SAVE) saved else null

    /** The next dialog's prefill: the saved title after Save; what was typed (blank: nothing) after Skip or Back. */
    fun draftAfter(action: Action, typed: String, saved: String?): String? = when (action) {
        Action.SAVE -> saved
        Action.SKIP, Action.CANCEL -> typed.trim().ifEmpty { null }
    }
}

/** DESIGN.md 3.1: `prefix48 << 2` as 10 Crockford Base32 symbols, `SSSSS-SSSSS`. */
internal object IdentityHandle {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val PLACEHOLDER = "-----"

    fun fromPrefixHex(prefixHex: String): String {
        val hex = prefixHex.filter { it.isLetterOrDigit() }.take(12)
        if (hex.length != 12 || hex.any { it.digitToIntOrNull(16) == null }) return "$PLACEHOLDER-$PLACEHOLDER"
        val value = hex.toLong(16) shl 2
        val symbols = (9 downTo 0).map { index -> ALPHABET[((value ushr (index * 5)) and 0x1f).toInt()] }
        return symbols.take(5).joinToString("") + "-" + symbols.drop(5).joinToString("")
    }

    /**
     * Typed or read-aloud handle: case-insensitive, I/L -> 1, O -> 0, hyphens and spaces ignored,
     * exactly 10 symbols, last symbol's low 2 bits zero. Returns the 12-hex prefix or null.
     */
    fun parse(text: String): String? {
        val symbols = canonicalSymbols(text) ?: return null
        if (symbols.length != 10) return null
        var value = 0L
        symbols.forEach { value = (value shl 5) or ALPHABET.indexOf(it).toLong() }
        if (value and 0b11L != 0L) return null
        return "%012x".format(value ushr 2)
    }

    /** A five-symbol short handle in canonical form, or null. */
    fun parseShort(text: String): String? = canonicalSymbols(text)?.takeIf { it.length == 5 }

    /** Canonical display form (`SSSSS-SSSSS`) of a typed handle, or null. */
    fun canonical(text: String): String? = parse(text)?.let(::fromPrefixHex)

    private fun canonicalSymbols(text: String): String? {
        val out = StringBuilder()
        for (char in text) {
            when (val upper = char.uppercaseChar()) {
                '-', ' ' -> Unit
                'I', 'L' -> out.append('1')
                'O' -> out.append('0')
                else -> if (upper in ALPHABET) out.append(upper) else return null
            }
        }
        return out.toString()
    }
}
