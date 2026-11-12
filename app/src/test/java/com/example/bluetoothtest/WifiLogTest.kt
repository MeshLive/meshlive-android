package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The single Wi-Fi logger: no MAC, IPv6, ticket or key ever reaches a line, and the helper never throws. */
class WifiLogTest {
    private val redacted = "redacted"

    @Test
    fun addressNameAndKeyShapesAreRedacted() {
        val values = listOf(
            "AA:BB:CC:DD:EE:FF",
            "fe80::1ff:fe23:4567:890a%aware_data0",
            "0123456789abcdef0123456789abcdef",
            "0123456789abcdef",
        )
        values.forEach { value ->
            assertEquals("wifi_upgrade: event=x | k=$redacted", WifiLog.upgrade("x", "k" to value))
        }
    }

    @Test
    fun aMacWithoutSeparatorsIsRedactedButOrdinaryValuesSurvive() {
        listOf("aabbccddeeff", "AABBCCDDEEFF", "peer=001122334455", "mac_aabbccddeeff_x").forEach { value ->
            assertEquals(value, "wifi_upgrade: event=x | k=$redacted", WifiLog.upgrade("x", "k" to value))
        }
        listOf("feedface", "1099511627776", "attempt_failed", "deadbeef00").forEach { value ->
            assertEquals(value, "wifi_upgrade: event=x | k=$value", WifiLog.upgrade("x", "k" to value))
        }
    }

    @Test
    fun hashIsStableDistinctAndSurvivesTheScrubber() {
        val a = WifiLog.hash("a")
        assertEquals(a, WifiLog.hash("a"))
        assertNotEquals(a, WifiLog.hash("b"))
        assertTrue(a, Regex("^h:[0-9a-f]{16}$").matches(a))
        assertEquals("wifi_upgrade: event=x | addr=$a", WifiLog.upgrade("x", "addr" to a))
    }

    @Test
    fun aPipeInAValueCannotAddAField() {
        val line = WifiLog.upgrade("x", "a" to "1|b=2")
        assertEquals("wifi_upgrade: event=x | a=1_b=2", line)
        assertEquals(1, line.split(" | ").size - 1)
    }

    @Test
    fun nullFieldsAreOmitted() {
        assertEquals("wifi_upgrade: event=attach | holders=2", WifiLog.upgrade("attach", "holders" to 2, "reason" to null))
    }

    @Test
    fun anUpgradeLineStartsWithItsEvent() {
        assertTrue(WifiLog.upgrade("attach", "step" to "start").startsWith("wifi_upgrade: event=attach"))
    }

    @Test
    fun lineRedactsAForbiddenKey() {
        assertEquals("x: ssid=redacted | ok=1", WifiLog.line("x", "ssid" to "abc", "ok" to 1))
    }

    @Test
    fun lineKeepsAHashUnderAForbiddenKey() {
        val hashed = WifiLog.hash("a")
        assertEquals("x: addr=$hashed", WifiLog.line("x", "addr" to hashed))
        assertEquals("x: addr=redacted", WifiLog.line("x", "addr" to "fe80::1"))
    }

    @Test
    fun discoveryLineKeepsTheCallersFieldOrder() {
        assertEquals(
            "wifi_discovery: event=publish | epoch=7 | reason=boundary | n=2",
            WifiLog.discovery("publish", "epoch" to 7L, "reason" to "boundary", "n" to 2),
        )
    }

    @Test
    fun switchesLineFormat() {
        assertEquals("wifi_switches: carrier=on | wider=off | direct=on", WifiLog.switches(true, false, true))
    }

    @Test
    fun capsLineHasOneSpaceAfterTheColon() {
        assertEquals("wifi_caps: free=2", WifiLog.caps("free" to 2))
    }
}
