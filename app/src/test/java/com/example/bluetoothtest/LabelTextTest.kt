package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelTextTest {
    private fun ok(kind: LabelKind, text: String) = LabelText.normalize(kind, text)
    private fun rejected(kind: LabelKind, text: String): String? = LabelText.rejection(kind, text)

    @Test
    fun ordinaryNamesThatParseUnderCrockfordAliasesStayValid() {
        // R6: these parse as 10-symbol handles under I/L/O aliases but contain no digit.
        assertEquals("Brave Tiger", ok(LabelKind.PERSON_NAME, "Brave Tiger"))
        assertEquals("Mike Foster", ok(LabelKind.PERSON_NAME, "Mike Foster"))
        assertNotNull(IdentityHandle.parse("Brave Tiger"))
        assertEquals("Asha", ok(LabelKind.PERSON_NAME, "Asha"))
    }

    @Test
    fun handleShapedTextIsRejected() {
        assertEquals("looks like a handle", rejected(LabelKind.PERSON_NAME, "M6SC7-N75YR"))
        assertEquals("looks like a handle", rejected(LabelKind.PERSON_NAME, "m6sc7n75yr"))
        assertEquals("looks like a handle", rejected(LabelKind.PERSON_NAME, "ab12c"))
        assertEquals("looks like a handle", rejected(LabelKind.PERSON_NAME, "abcde-fghij"))
        // Fullwidth forms fold under NFKC.
        assertEquals("looks like a handle", rejected(LabelKind.PERSON_NAME, "\uFF2D6SC7-N75YR"))
    }

    @Test
    fun checkMarksBidiControlsAndJoinersAreRejected() {
        assertEquals("check mark", rejected(LabelKind.PERSON_NAME, "Asha \u2713"))
        assertEquals("check mark", rejected(LabelKind.PERSON_NAME, "Asha \uD83D\uDDF8"))
        assertNotNull(rejected(LabelKind.PERSON_NAME, "Asha\u202Eevil"))
        assertNotNull(rejected(LabelKind.PERSON_NAME, "As\u2066ha"))
        assertNotNull(rejected(LabelKind.PERSON_NAME, "A\u200Dsha"))
        assertNotNull(rejected(LabelKind.PERSON_NAME, "A\u200Csha"))
    }

    @Test
    fun scriptMixingFollowsModeratelyRestrictive() {
        // Latin + Cyrillic look-alike ("pаypal" with a Cyrillic a) is rejected.
        assertEquals("mixed scripts", rejected(LabelKind.PERSON_NAME, "p\u0430ypal"))
        // Latin + Greek is rejected.
        assertEquals("mixed scripts", rejected(LabelKind.PERSON_NAME, "Al\u03B1n"))
        // Latin + Devanagari, single Cyrillic, and Japanese Han + Hiragana + Latin are allowed.
        assertNull(rejected(LabelKind.PERSON_NAME, "Ravi \u0930\u093E\u092E"))
        assertNull(rejected(LabelKind.PERSON_NAME, "\u0410\u043B\u0435\u043A\u0441"))
        assertNull(rejected(LabelKind.STREAM_TITLE, "Tokyo \u6771\u4EAC\u3067\u3059"))
        // Cyrillic + Devanagari (no Latin) is rejected.
        assertEquals("mixed scripts", rejected(LabelKind.PERSON_NAME, "\u0410\u0440 \u0930\u093E"))
    }

    @Test
    fun byteLimitsCountUtf8AtMultiByteBoundaries() {
        assertEquals(32, ok(LabelKind.PERSON_NAME, "\u00E9".repeat(16)).toByteArray(Charsets.UTF_8).size)
        assertEquals("too long", rejected(LabelKind.PERSON_NAME, "\u20AC".repeat(11))) // 33 bytes
        assertNull(rejected(LabelKind.STREAM_TITLE, "a".repeat(48)))
        assertEquals("too long", rejected(LabelKind.STREAM_TITLE, "a".repeat(49)))
    }

    @Test
    fun whitespaceCollapsesAndNfcIsIdempotent() {
        assertEquals("Asha Rao", ok(LabelKind.PERSON_NAME, "  Asha \t\n  Rao  "))
        assertEquals("\u00E9t\u00E9", ok(LabelKind.PERSON_NAME, "e\u0301te\u0301"))
        val once = ok(LabelKind.PERSON_NAME, "e\u0301te\u0301")
        assertEquals(once, ok(LabelKind.PERSON_NAME, once))
        assertTrue(LabelText.isCanonical(LabelKind.PERSON_NAME, once))
        // A receiver drops text that is valid only after normalization.
        assertFalse(LabelText.isCanonical(LabelKind.PERSON_NAME, " Asha"))
        assertFalse(LabelText.isCanonical(LabelKind.PERSON_NAME, "e\u0301"))
        assertEquals("empty", rejected(LabelKind.PERSON_NAME, "   "))
    }

    @Test
    fun stackedCombiningMarksAreCapped() {
        assertNull(rejected(LabelKind.PERSON_NAME, "Zx\u0300\u0301\u0302"))
        assertEquals("stacked combining marks", rejected(LabelKind.PERSON_NAME, "Zx\u0300\u0301\u0302\u0303"))
    }
}
