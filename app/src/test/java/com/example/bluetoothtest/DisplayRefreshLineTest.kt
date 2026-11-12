package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayRefreshLineTest {
    /** Reads a field the way the scorer reads `key=value` fields of a BENCHMARK line. */
    private fun field(line: String, key: String): String =
        Regex("""(?:^|[ |])${Regex.escape(key)}=([^ |]+)""").find(line.substringAfter("BENCHMARK display: "))!!.groupValues[1]

    @Test
    fun aSixtyHertzPhoneReportsSixty() {
        val line = DisplayRefreshLine.line(60.000004f, listOf(60f))
        assertEquals(60.0, field(line, "refreshHz").toDouble(), 0.05)
        assertEquals(60.0, field(line, "maxHz").toDouble(), 0.05)
    }

    @Test
    fun anAdaptivePanelReportsItsLaunchRateAndItsFastestMode() {
        val line = DisplayRefreshLine.line(60f, listOf(120f, 60f, 120f, 96f))
        assertEquals(60.0, field(line, "refreshHz").toDouble(), 0.05)
        assertEquals(120.0, field(line, "maxHz").toDouble(), 0.05)
        assertEquals(listOf(60.0, 96.0, 120.0), field(line, "modes").split("/").map { it.toDouble() })
    }

    @Test
    fun aDisplayThatCannotSayIsUnknownNotSixty() {
        val line = DisplayRefreshLine.line(null, emptyList())
        assertEquals("unknown", field(line, "refreshHz"))
        assertEquals("unknown", field(line, "maxHz"))
    }
}
