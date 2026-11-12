package com.example.bluetoothtest

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental test_fast_first_redial and test_coc_psm_reuse_retry (join-latency P4). */
class FastFirstRedialTest {
    private val on = ConnectRetryConfig(fastFirstRedial = true)

    private fun attempts(link: ConnectAttempts.Link, config: ConnectRetryConfig) =
        ConnectAttempts(link, config, null, 0L, false, "AA:BB") { 0L }

    /** One failed attempt that started at [startedMs] and failed [elapsedMs] later; returns the redial delay. */
    private fun ConnectAttempts.redialDelay(message: String, startedMs: Long, elapsedMs: Long): Long {
        val error = IOException(message)
        val now = startedMs + elapsedMs
        val decision = onFailure(error, classify(message, elapsedMs), startedMs, now, classifiedRetryable = true, linkReached = false)
        return (decision as ConnectAttempts.Decision.Dial).delayMs
    }

    @Test
    fun aFastFirst133IsRedialedAfter200MsOnBothLeClients() {
        for (link in ConnectAttempts.Link.values()) {
            assertEquals(link.name, 200L, attempts(link, on).redialDelay("${link.notice} connection failed (status 133)", 0L, 420L))
        }
        // The BLE control client has 3 attempts: its second redial keeps the ordinary spacing.
        val control = attempts(ConnectAttempts.Link.BLE_CONTROL, on)
        assertEquals(200L, control.redialDelay("BLE control connection failed (status 133)", 0L, 420L))
        assertEquals(500L, control.redialDelay("BLE control connection failed (status 133)", 620L, 420L))
    }

    @Test
    fun slowFailuresOtherStatusesAndTheOffSwitchKeep500Ms() {
        val link = ConnectAttempts.Link.BLE_CONTROL
        assertEquals("a 133 after 1.6 s is not the fast 0x3E", 500L,
            attempts(link, on).redialDelay("BLE control connection failed (status 133)", 0L, 1_600L))
        assertEquals("0x3E itself (62) qualifies", 200L,
            attempts(link, on).redialDelay("BLE control connection failed (status 62)", 0L, 300L))
        assertEquals("a supervision timeout keeps the ordinary spacing", 500L,
            attempts(link, on).redialDelay("BLE control connection failed (status 8)", 0L, 300L))
        assertEquals("off: unchanged", 500L,
            attempts(link, ConnectRetryConfig.DEFAULT).redialDelay("BLE control connection failed (status 133)", 0L, 420L))
    }

    @Test
    fun aFirstConnect133At1326MsUsesTheSharedFastClassAndRedialsAfter200Ms() {
        val gatt = ConnectAttempts.Link.GATT_VIDEO
        val message = "GATT video connection failed (status 133)"
        assertEquals(200L, attempts(gatt, on).redialDelay(message, 0L, 1_326L))
        assertEquals("the class edge is 1.5 s", 200L, attempts(gatt, on).redialDelay(message, 0L, 1_500L))
        assertEquals(500L, attempts(gatt, on).redialDelay(message, 0L, 1_600L))
        assertEquals("off: unchanged", 500L, attempts(gatt, ConnectRetryConfig.DEFAULT).redialDelay(message, 0L, 1_326L))
        // A second attempt keeps the ordinary spacing.
        val control = attempts(ConnectAttempts.Link.BLE_CONTROL, on)
        assertEquals(200L, control.redialDelay("BLE control connection failed (status 133)", 0L, 1_326L))
        assertEquals(500L, control.redialDelay("BLE control connection failed (status 133)", 2_000L, 1_326L))
    }

    @Test
    fun theFastRedialIsLoggedAndDoesNotAddAttempts() {
        val attempts = attempts(ConnectAttempts.Link.GATT_VIDEO, on)
        val error = IOException("GATT video connection failed (status 133)")
        val first = attempts.onFailure(error, attempts.classify(error.message, 400L), 0L, 400L, true, false)
        assertTrue(first.lines.single().startsWith("BENCHMARK fast_first_redial:"))
        val second = attempts.onFailure(error, attempts.classify(error.message, 400L), 600L, 1_000L, true, false)
        assertTrue("GATT video keeps its 2 attempts", second is ConnectAttempts.Decision.GiveUp)
    }

    @Test
    fun aCocConnectRetriesWithTheSamePsmOnlyAfterAFastFailureWithRoomLeft() {
        val refused = IOException("read failed, socket might closed or timeout, read ret: -1")
        assertTrue(CocPsmReuseRetry.shouldRetry(refused, 480L, null))
        assertTrue(CocPsmReuseRetry.shouldRetry(refused, 480L, 3_500L))
        assertFalse("not enough of the setup deadline for the ACL wait and a connect", CocPsmReuseRetry.shouldRetry(refused, 480L, 2_000L))
        assertFalse("a slow failure is not the fast refusal", CocPsmReuseRetry.shouldRetry(refused, 8_000L, null))
        assertFalse("the app-side connect bound already took its time", CocPsmReuseRetry.shouldRetry(CocConnectTimeoutException(12_000L), 500L, null))
        assertFalse("not an I/O failure", CocPsmReuseRetry.shouldRetry(IllegalStateException("x"), 100L, null))
    }

    @Test
    fun thePsmReuseSwitchIsDebugOnlyAndOffByDefault() {
        try {
            CocSwitches.configure()
            assertFalse(CocSwitches.psmReuseRetry)
            CocSwitches.configure(psmReuseRetry = true, debugBuild = false)
            assertFalse(CocSwitches.psmReuseRetry)
            CocSwitches.configure(psmReuseRetry = true, debugBuild = true)
            assertTrue(CocSwitches.psmReuseRetry)
        } finally {
            CocSwitches.configure()
        }
    }
}
