package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    private fun v(text: String) = ReleaseVersion.parse(text)!!

    @Test
    fun parsesTagsAndVersionNamesAndRejectsEverythingElse() {
        assertEquals("0.1.0-beta", v("v0.1.0-beta").toString())
        assertEquals("1.2.3", v("1.2.3+build.5").toString())
        assertEquals("1.0.0-beta.2", v(" v1.0.0-beta.2 ").toString())
        for (bad in listOf("", "1", "1.2", "latest", "v1.2.3.4", "1.2.x", "nightly-2026-10-11", "1.2.3-", "1.2.3-a..b", "-1.0.0", "1.2.1234567")) {
            assertNull("'$bad' must not parse", ReleaseVersion.parse(bad))
        }
    }

    @Test
    fun aTagCannotCarryAMessage() {
        // The version text is shown to the user (home note, update screen): short, bounded parts only.
        assertNull(ReleaseVersion.parse("v9.9.9-urgent.install.from.evil.example.now"))
        assertNull(ReleaseVersion.parse("1.0.0-" + "a".repeat(21)))
        assertNull(ReleaseVersion.parse("1.0.0+" + "m".repeat(41)))
        assertNull(ReleaseVersion.parse("1.0.0-" + "a".repeat(100)))
        assertEquals("1.0.0-a.b.c.d", v("1.0.0-a.b.c.d").toString())
    }

    @Test
    fun ordersByPrecedenceLikeSemver() {
        val ascending = listOf(
            "0.1.0-alpha", "0.1.0-alpha.1", "0.1.0-alpha.beta", "0.1.0-beta", "0.1.0-beta.2", "0.1.0-beta.11",
            "0.1.0-rc.1", "0.1.0", "0.1.1", "0.2.0", "1.0.0",
        ).map(::v)
        for (i in ascending.indices) {
            for (j in ascending.indices) {
                val expected = i.compareTo(j).coerceIn(-1, 1)
                assertEquals("${ascending[i]} vs ${ascending[j]}", expected, ascending[i].compareTo(ascending[j]).coerceIn(-1, 1))
            }
        }
        assertTrue(v("0.1.0-beta") < v("0.1.0"))
        assertTrue(v("v0.2.0") > v("0.1.9"))
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertEquals(v("1.0.0"), v("1.0.0+meta"))
    }

    @Test
    fun releaseNumberIsTheBaseVersionCodeOfASplitRelease() {
        assertEquals(1, ReleaseNumber.fromVersionCode(10))
        assertEquals(1, ReleaseNumber.fromVersionCode(12))
        assertEquals(23, ReleaseNumber.fromVersionCode(231))
        assertNull("debug and non-split builds", ReleaseNumber.fromVersionCode(1))
        assertNull(ReleaseNumber.fromVersionCode(9))
        assertNull(ReleaseNumber.fromVersionCode(0))
        assertNull(ReleaseNumber.fromVersionCode(-12))
        assertNull("beyond the 15-bit tag", ReleaseNumber.fromVersionCode((ReleaseNumber.MAX + 1) * 10L))
        assertEquals(ReleaseNumber.MAX, ReleaseNumber.fromVersionCode(ReleaseNumber.MAX * 10L + 2))
        assertEquals(2, ReleaseNumber.abiDigit(12))
        assertEquals("arm64-v8a", ReleaseNumber.abiName(2))
        assertEquals("armeabi-v7a", ReleaseNumber.abiName(1))
        assertEquals("universal", ReleaseNumber.abiName(0))
        assertNull(ReleaseNumber.abiName(5))
    }

    @Test
    fun theBaseVersionCodeInGradleMatchesTheRulesAboveAndTheCurrentRelease() {
        val gradle = java.io.File("build.gradle.kts").takeIf { it.isFile } ?: java.io.File("app/build.gradle.kts")
        val text = gradle.readText()
        val code = Regex("""versionCode\s*=\s*(\d+)""").find(text)!!.groupValues[1].toInt()
        val abiDigits = Regex("""mapOf\("armeabi-v7a" to (\d), "arm64-v8a" to (\d)\)""").find(text)!!.groupValues
        assertEquals("1", abiDigits[1])
        assertEquals("2", abiDigits[2])
        // A split release of this base has release number = base.
        assertEquals(code, ReleaseNumber.fromVersionCode(code * 10L + 2))
        assertFalse(code <= 0)
        // A release built from here must be able to use Wi-Fi with a phone on the same release (WifiWire.buildTagAllowsBwf1).
        assertTrue("WIFI_FIRST_RELEASE ${WifiWire.WIFI_FIRST_RELEASE} is after this release $code", WifiWire.WIFI_FIRST_RELEASE <= code)
        assertTrue(WifiWire.buildTagAllowsBwf1(BuildTag(code, false)))
    }
}
