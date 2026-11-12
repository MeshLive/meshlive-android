package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NativeDialWaitTest {
    private fun now() = System.nanoTime() / 1_000_000

    @Test
    fun aCancelledCallStopsWaitingWithinASlice() {
        val result = CompletableFuture<String>()
        val cancelled = AtomicBoolean(false)
        thread { Thread.sleep(50); cancelled.set(true) }   // the user taps Cancel 50 ms into a 25 s dial
        val started = now()
        val outcome = NativeDialWait.await(result, started + 25_000, ::now, { cancelled.get() }, sliceMs = 20)
        assertEquals(NativeDialWait.Outcome.Cancelled, outcome)
        assertTrue(now() - started < 2_000)
    }

    @Test
    fun theCallsSetupDeadlineEndsTheWait() {
        val result = CompletableFuture<String>()
        val started = now()
        val outcome = NativeDialWait.await(result, started + 150, ::now, { false }, sliceMs = 20)
        assertEquals(NativeDialWait.Outcome.TimedOut, outcome)
        assertTrue(now() - started < 2_000)
    }

    @Test
    fun aConnectionThatArrivesIsReturnedAndAFailureKeepsItsCause() {
        val done = CompletableFuture<String>()
        thread { Thread.sleep(30); done.complete("socket") }
        assertEquals(NativeDialWait.Outcome.Done("socket"), NativeDialWait.await(done, now() + 5_000, ::now, { false }, sliceMs = 10))

        val failed = CompletableFuture<String>()
        val cause = IOException("native_fail:no_answer")
        failed.completeExceptionally(cause)
        val outcome = NativeDialWait.await(failed, now() + 5_000, ::now, { false })
        assertTrue(outcome is NativeDialWait.Outcome.Failed && outcome.cause === cause)
    }

    @Test
    fun anAlreadyCancelledDialNeverWaits() {
        val outcome = NativeDialWait.await(CompletableFuture<String>(), now() + 5_000, ::now, { true })
        assertEquals(NativeDialWait.Outcome.Cancelled, outcome)
    }
}
