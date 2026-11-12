package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.util.Collections
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Experimental test_coc_prime_async: the socket is used before the prime ends; bulk writes wait for it, bounded. */
class CocPrimeGateTest {
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun gate(boundMs: Long = 2_000L, allowanceBytes: Long = 100L) = CocPrimeGate(
        owner = "test",
        nowMs = System::currentTimeMillis,
        log = { lines += it },
        boundMs = boundMs,
        allowanceBytes = allowanceBytes,
    )

    @After
    fun defaults() = CocSwitches.configure()

    @Test
    fun handshakeSizedWritesGoOutAtOnceWhileThePrimeRuns() {
        val gate = gate()
        val raw = ByteArrayOutputStream()
        var lateResolved = false
        val output = PrimeGatedOutputStream(gate, raw) { lateResolved = true; raw }
        val started = System.currentTimeMillis()
        output.write(ByteArray(60))
        output.write(ByteArray(40))
        assertTrue("records within the allowance never wait", System.currentTimeMillis() - started < 500L)
        assertEquals(100, raw.size())
        assertFalse(lateResolved)
        assertFalse(lines.any { "writer_wait" in it })
    }

    @Test
    fun theFirstBulkWriteWaitsUntilThePrimeIsReleased() {
        val gate = gate()
        val raw = ByteArrayOutputStream()
        val late = ByteArrayOutputStream()
        val output = PrimeGatedOutputStream(gate, raw) { late }
        output.write(ByteArray(80))
        val releasedAt = longArrayOf(0L)
        val primer = thread {
            Thread.sleep(300L)
            releasedAt[0] = System.currentTimeMillis()
            gate.release("ok", primeMs = 250L, attachMs = 50L, attached = true)
        }
        output.write(ByteArray(500))
        val wroteAt = System.currentTimeMillis()
        primer.join()
        assertTrue("the bulk write went out only after the prime", wroteAt >= releasedAt[0])
        assertEquals("handshake bytes on the raw stream", 80, raw.size())
        assertEquals("bulk bytes on the stream resolved after the reattach (adaptive PHY)", 500, late.size())
        assertTrue(lines.any { "phase=writer_wait" in it && "released=true" in it })
        assertTrue(lines.any { "phase=primed" in it && "writerWaited=waiting" in it && "primeMs=250" in it })
    }

    @Test
    fun aPrimeThatNeverEndsHoldsTheWriterOnlyForTheBound() {
        val gate = gate(boundMs = 200L)
        val late = ByteArrayOutputStream()
        val output = PrimeGatedOutputStream(gate, ByteArrayOutputStream()) { late }
        val started = System.currentTimeMillis()
        output.write(ByteArray(500))
        val waited = System.currentTimeMillis() - started
        assertTrue("waited about the bound, $waited ms", waited in 150L..1_500L)
        assertEquals("the write proceeds as without the prime", 500, late.size())
        assertTrue(lines.any { "phase=writer_wait" in it && "released=false" in it })
        // Later writes do not wait again.
        val again = System.currentTimeMillis()
        output.write(ByteArray(500))
        assertTrue(System.currentTimeMillis() - again < 100L)
        assertEquals(1000, late.size())
    }

    @Test
    fun aPrimeDoneBeforeTheFirstBulkWriteCostsNothingAndReportsNoWait() {
        val gate = gate()
        gate.release("ok", primeMs = 500L, attachMs = 170L, attached = true)
        val late = ByteArrayOutputStream()
        val output = PrimeGatedOutputStream(gate, ByteArrayOutputStream()) { late }
        val started = System.currentTimeMillis()
        output.write(ByteArray(5_000))
        assertTrue(System.currentTimeMillis() - started < 100L)
        assertEquals(5_000, late.size())
        assertTrue(lines.single().contains("writerWaited=false"))
    }

    @Test
    fun theSwitchIsDebugOnlyAndOffByDefault() {
        CocSwitches.configure()
        assertFalse(CocSwitches.primeAsync)
        CocSwitches.configure(primeAsync = true, debugBuild = false)
        assertFalse("a release build ignores the launch extra", CocSwitches.primeAsync)
        CocSwitches.configure(primeAsync = true, debugBuild = true)
        assertTrue(CocSwitches.primeAsync)
    }
}
