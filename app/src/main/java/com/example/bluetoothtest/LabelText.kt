package com.example.bluetoothtest

import java.text.Normalizer
import java.util.Locale

/**
 * Text rules for peer labels (names and stream titles), naming design 3.2 with review
 * fixes R6/R7. One function for the sender ([normalize]) and the receiver ([isCanonical]): a
 * received label is shown only when it is already in the form [normalize] would produce.
 */
internal enum class LabelKind(val wireValue: Int, val maxBytes: Int) {
    PERSON_NAME(1, 32),
    STREAM_TITLE(2, 48),
    ;

    companion object {
        /**
         * Kind 3 was the call subject, retired 2026-11-05 (PROTOCOL "Peer labels"): never sent, never shown, never
         * reused. An older build may still send one; [fromWire] returns null for it, so [LabelInbox] skips it silently
         * like any unknown kind and the call goes on.
         */
        const val RETIRED_CALL_SUBJECT = 3

        fun fromWire(value: Int): LabelKind? = entries.firstOrNull { it.wireValue == value }
    }
}

internal object LabelText {
    /** Longest run of combining marks (Mn/Me) allowed after one base character (R7, stops "Zalgo"). */
    const val MAX_COMBINING_RUN = 3

    private val CHECK_MARKS = setOf(0x2713, 0x2714, 0x2705, 0x2611, 0x1F5F8)

    private val REJECTED_TYPES: Set<Int> = setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )

    private val HANDLE_SHAPED = Regex("^[0-9a-z]{5}-[0-9a-z]{5}$")

    /** Sender side: the canonical label text, or [IllegalArgumentException] with a short reason. */
    fun normalize(kind: LabelKind, text: String): String {
        val collapsed = collapseWhitespace(text)
        val normalized = Normalizer.normalize(collapsed, Normalizer.Form.NFC)
        require(normalized.isNotEmpty()) { "empty" }
        require(normalized.toByteArray(Charsets.UTF_8).size <= kind.maxBytes) { "too long" }
        var combiningRun = 0
        normalized.codePoints().forEach { codePoint ->
            require(Character.getType(codePoint) !in REJECTED_TYPES) { "control, format or unassigned character" }
            require(codePoint !in CHECK_MARKS) { "check mark" }
            val type = Character.getType(codePoint)
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) {
                combiningRun++
                require(combiningRun <= MAX_COMBINING_RUN) { "stacked combining marks" }
            } else {
                combiningRun = 0
            }
        }
        require(scriptsAllowed(normalized)) { "mixed scripts" }
        require(!looksLikeHandle(normalized)) { "looks like a handle" }
        // Naming v2 (Design G, review F13): a person name may not imitate the marks or a generated name.
        if (kind == LabelKind.PERSON_NAME) require(!NameTextRules.isReserved(normalized)) { NameTextRules.REASON }
        return normalized
    }

    /** Receiver side: true only when [text] is valid and already canonical. */
    fun isCanonical(kind: LabelKind, text: String): Boolean =
        runCatching { normalize(kind, text) == text }.getOrDefault(false)

    /** The rejection reason for [text], or null when it is canonical. Never contains the text. */
    fun rejection(kind: LabelKind, text: String): String? = try {
        if (normalize(kind, text) == text) null else "not canonical"
    } catch (error: IllegalArgumentException) {
        error.message ?: "invalid"
    }

    private fun collapseWhitespace(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        text.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) }.codePoints().forEach { codePoint ->
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(codePoint)
            }
        }
        return out.toString()
    }

    /**
     * UTS #39 Moderately Restrictive with java.lang.Character.UnicodeScript: one script (plus
     * Common/Inherited), or Latin plus one other script except Cyrillic and Greek, or Latin with
     * Han+Hiragana/Katakana, Han+Bopomofo or Han+Hangul.
     */
    internal fun scriptsAllowed(text: String): Boolean {
        val scripts = mutableSetOf<Character.UnicodeScript>()
        text.codePoints().forEach { codePoint ->
            val script = Character.UnicodeScript.of(codePoint)
            if (script != Character.UnicodeScript.COMMON &&
                script != Character.UnicodeScript.INHERITED &&
                script != Character.UnicodeScript.UNKNOWN
            ) {
                scripts += script
            }
        }
        if (scripts.size <= 1) return true
        val latin = Character.UnicodeScript.LATIN
        val han = Character.UnicodeScript.HAN
        val cjkSets = listOf(
            setOf(latin, han, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA),
            setOf(latin, han, Character.UnicodeScript.BOPOMOFO),
            setOf(latin, han, Character.UnicodeScript.HANGUL),
        )
        if (cjkSets.any { it.containsAll(scripts) }) return true
        if (latin in scripts && scripts.size == 2) {
            val other = scripts.first { it != latin }
            return other != Character.UnicodeScript.CYRILLIC && other != Character.UnicodeScript.GREEK
        }
        return false
    }

    /**
     * R6: only handle-shaped text is rejected. Plain words that happen to parse under the
     * Crockford aliases ("Brave Tiger", "Mike Foster") stay valid because they contain no digit.
     */
    internal fun looksLikeHandle(text: String): Boolean {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (HANDLE_SHAPED.matches(folded)) return true
        if (folded.none { it in '0'..'9' }) return false
        return IdentityHandle.parse(folded) != null || IdentityHandle.parseShort(folded) != null
    }
}
