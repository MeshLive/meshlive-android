package com.example.bluetoothtest

import java.text.Normalizer
import java.util.Locale

/**
 * Reserved forms for a person name (Design G review F13; PROTOCOL "Naming v2 / Text rules"). The earlier text rules do not
 * stop these characters (`LabelText` ignores the Common script, which holds `~` and its look-alikes), so the rule is
 * explicit and runs on the canonical text and on its UTS #39 skeleton with spaces and punctuation removed and case folded:
 *
 * 1. a name may not start or end with `~`, U+223C, U+FF5E, U+02DC, U+2053 or U+301C (the mark the screens draw for a
 *    self-claimed name);
 * 2. it may not contain `~` anywhere, U+1F6E1 (shield), the lock symbols U+1F512, U+1F513, U+1F510, U+1F50F, or the
 *    separators the app uses to join name parts (" - ", and a comma or a middle dot followed by "seen": U+00B7 or a
 *    look-alike dot U+0387, U+2027, U+2219, U+22C5, U+30FB, U+FF65; the dot is design decision 2026-11-06, AB3 R7);
 * 3. its reduced skeleton may not equal the reduced skeleton of any generated name (so "BlueOtter", "Blue 0tter" and
 *    "blue otter" are refused, not only "Blue Otter").
 *
 * Rule 3 needs the generated-name lists; when they are not installed only rules 1 and 2 apply. A receiver drops a label
 * that breaks a rule exactly as for any other rule (reason `reserved_form`, no text logged). Applies to person names only.
 */
internal object NameTextRules {
    const val REASON = "reserved_form"

    private val TILDES = setOf(0x007E, 0x223C, 0xFF5E, 0x02DC, 0x2053, 0x301C)
    private val SYMBOLS = setOf(0x1F6E1, 0x1F512, 0x1F513, 0x1F510, 0x1F50F)
    private val SEEN_SEPARATOR = Regex("[,\u00B7\u0387\u2027\u2219\u22C5\u30FB\uFF65]\\s*seen", RegexOption.IGNORE_CASE)

    private class SkeletonCache(val table: ConfusableTable, val adjectives: List<String>, val nouns: Set<String>)

    @Volatile private var cache: SkeletonCache? = null
    @Volatile private var cacheLists: StrangerNameLists? = null

    /** True when [text] (already canonical) uses a reserved form. */
    fun isReserved(text: String, lists: StrangerNameLists? = currentLists(), table: ConfusableTable = Confusables.table): Boolean =
        reason(text, lists, table) != null

    /** A short reason (never the text), or null. */
    fun reason(text: String, lists: StrangerNameLists? = currentLists(), table: ConfusableTable = Confusables.table): String? {
        val first = text.codePointAt(0)
        val last = text.codePointBefore(text.length)
        if (first in TILDES || last in TILDES) return "tilde at an end"
        if (text.codePoints().anyMatch { it == 0x007E || it in SYMBOLS }) return "mark character"
        if (" - " in text || SEEN_SEPARATOR.containsMatchIn(text)) return "separator"
        if (lists != null && matchesGeneratedName(text, lists, table)) return "generated name"
        return null
    }

    /** Rule 3 alone: the reduced skeleton equals that of a generated name. False when the lists are not installed. */
    fun looksLikeGeneratedName(
        text: String,
        lists: StrangerNameLists? = currentLists(),
        table: ConfusableTable = Confusables.table,
    ): Boolean = lists != null && matchesGeneratedName(text, lists, table)

    /**
     * The readings of [text] that count as "the same word" for look-alike checks: its UTS #39 skeleton folded to lower
     * case, and the skeleton of its lower-cased text. Both are needed: folding after the skeleton keeps capital I and
     * lower-case l confusable, folding before it makes "Mum" and "mum" one reading (the table maps lower-case m to "rn").
     */
    fun readings(text: String, table: ConfusableTable = Confusables.table): Set<String> {
        val trimmed = text.trim()
        return setOf(
            table.skeleton(trimmed).lowercase(Locale.ROOT),
            table.skeleton(trimmed.lowercase(Locale.ROOT)).lowercase(Locale.ROOT),
        )
    }

    /** True when [a] and [b] share a reading (case- and skeleton-insensitive sameness). */
    fun sameReading(a: String, b: String, table: ConfusableTable = Confusables.table): Boolean =
        a.isNotBlank() && b.isNotBlank() && readings(a, table).intersect(readings(b, table)).isNotEmpty()

    /** The reduced readings (spaces and punctuation removed) compared in rule 3. */
    fun reducedReadings(text: String, table: ConfusableTable = Confusables.table): Set<String> {
        val trimmed = text.trim()
        return setOf(reducedSkeleton(trimmed, table), reducedSkeleton(trimmed.lowercase(Locale.ROOT), table))
    }

    /** Skeleton with spaces and punctuation removed and case folded: one form compared in rule 3. */
    fun reducedSkeleton(text: String, table: ConfusableTable = Confusables.table): String {
        val skeleton = table.skeleton(text.trim()).lowercase(Locale.ROOT)
        val out = StringBuilder(skeleton.length)
        Normalizer.normalize(skeleton, Normalizer.Form.NFC).codePoints().forEach { cp ->
            val type = Character.getType(cp)
            val punctuationOrSpace = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || when (type) {
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(), Character.MATH_SYMBOL.toInt(), Character.MODIFIER_SYMBOL.toInt(),
                -> true
                else -> false
            }
            if (!punctuationOrSpace) out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun currentLists(): StrangerNameLists? = StrangerNames.currentListsForRules()

    private fun matchesGeneratedName(text: String, lists: StrangerNameLists, table: ConfusableTable): Boolean {
        val skeletons = skeletonsFor(lists, table)
        // reduced == adjective + noun, for any pair
        return reducedReadings(text, table).any { reduced ->
            reduced.isNotEmpty() && skeletons.adjectives.any { adjective ->
                reduced.length > adjective.length && reduced.startsWith(adjective) &&
                    reduced.substring(adjective.length) in skeletons.nouns
            }
        }
    }

    private fun skeletonsFor(lists: StrangerNameLists, table: ConfusableTable): SkeletonCache {
        val existing = cache
        if (existing != null && cacheLists === lists && existing.table === table) return existing
        return synchronized(this) {
            val again = cache
            if (again != null && cacheLists === lists && again.table === table) {
                again
            } else {
                SkeletonCache(
                    table,
                    lists.adjectives.map { reducedSkeleton(it, table) },
                    lists.nouns.map { reducedSkeleton(it, table) }.toSet(),
                ).also {
                    cache = it
                    cacheLists = lists
                }
            }
        }
    }
}
