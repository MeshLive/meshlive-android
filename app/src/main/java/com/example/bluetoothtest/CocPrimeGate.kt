package com.example.bluetoothtest

import android.bluetooth.BluetoothSocket
import java.io.OutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Experimental `test_coc_prime_async` ([CocSwitches.primeAsync], default off; join-latency P1).
 *
 * A fresh-ACL LE CoC client ([BleCocClient]) used to run the MTU prime ([CocMtuPrime], 0.54-0.58 s on the M31) and the
 * GATT control reattach (~0.17 s) before it returned the connected socket, so the hop authentication started only after
 * both. With the switch on, the socket is returned at once and both run on a background thread; this gate holds the
 * first bulk write on the socket until they are done.
 *
 * What may pass before the gate opens: the hop handshake's own records, counted in bytes ([allowanceBytes], far above a
 * handshake's size: BMH2 hellos are at most 2 KB). Nothing is reordered: every write still goes through the one socket
 * stream in the caller's order, and the handshake still completes before any media byte is written (the callers'
 * order is unchanged). The first write past the allowance waits for the prime at most [boundMs], then goes on as
 * before the prime existed (the CoC traffic then starts at the controller's default data length, as on a stack without
 * the prime). The prime itself always runs (Q49: 632 vs 325 kbps on M31 -> ASUS).
 *
 * JVM-testable: the clock and the log are injected.
 */
internal class CocPrimeGate(
    private val owner: String,
    private val nowMs: () -> Long,
    private val log: (String) -> Unit,
    private val boundMs: Long = WAIT_BOUND_MS,
    private val allowanceBytes: Long = HANDSHAKE_ALLOWANCE_BYTES,
) {
    private val done = CountDownLatch(1)
    private val startedMs = nowMs()
    private val bytesBeforeOpen = AtomicLong()
    private val waiting = AtomicBoolean(false)
    private val waited = AtomicBoolean(false)

    /** True once the background work finished, or a writer stopped waiting for it (bound reached). */
    @Volatile private var passed = false

    /** The background work (prime and reattach) is done. */
    val released: Boolean get() = done.count == 0L

    /**
     * Called by the background thread when the prime and the reattach are done (also on failure: a failed prime never
     * holds the link). Logs the one `coc_prime_async` line with the durations and whether a writer waited.
     */
    fun release(primeOutcome: String, primeMs: Long, attachMs: Long, attached: Boolean) {
        val totalMs = nowMs() - startedMs
        val writer = when {
            waiting.get() -> "waiting"
            waited.get() -> "true"
            else -> "false"
        }
        passed = true
        done.countDown()
        log(
            "BENCHMARK coc_prime_async: owner=$owner | phase=primed | primeOutcome=$primeOutcome | primeMs=$primeMs | " +
                "attachMs=$attachMs | attached=$attached | totalMs=$totalMs | writerWaited=$writer | " +
                "allowanceBytes=$allowanceBytes | bytesBeforeOpen=${bytesBeforeOpen.get()}",
        )
    }

    /**
     * Called before a write of [length] bytes. Returns false while the write is within the handshake allowance (it goes
     * out at once, before the gate opens); true once the gate is open, after waiting (bounded) when needed.
     */
    fun beforeWrite(length: Int): Boolean {
        if (passed) return true
        if (bytesBeforeOpen.addAndGet(length.toLong()) <= allowanceBytes) return false
        if (!waited.compareAndSet(false, true)) {
            // Another writer is already waiting (or gave up): wait out the same bound without a second line.
            runCatching { done.await(boundMs, TimeUnit.MILLISECONDS) }
            passed = true
            return true
        }
        val waitStartedMs = nowMs()
        waiting.set(true)
        val opened = try {
            runCatching { done.await(boundMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        } finally {
            waiting.set(false)
        }
        passed = true
        log(
            "BENCHMARK coc_prime_async: owner=$owner | phase=writer_wait | waitedMs=${nowMs() - waitStartedMs} | " +
                "released=$opened | boundMs=$boundMs | sinceConnectMs=${nowMs() - startedMs}",
        )
        return true
    }

    companion object {
        /** The longest a writer waits for the prime and reattach (they normally end 0.7 s after the connect). */
        const val WAIT_BOUND_MS = 1_500L

        /** Bytes that may go out before the gate opens: the hop handshake (hellos at most 2 KB each, plus the confirms). */
        const val HANDSHAKE_ALLOWANCE_BYTES = 8_192L
    }
}

/** The open gates by socket (weak: a dropped socket never pins its gate). */
internal object CocPrimeGates {
    private val gates: MutableMap<BluetoothSocket, CocPrimeGate> = Collections.synchronizedMap(WeakHashMap())

    fun register(socket: BluetoothSocket, gate: CocPrimeGate) {
        gates[socket] = gate
    }

    fun remove(socket: BluetoothSocket) {
        gates.remove(socket)
    }

    /** The gate of [socket] while its prime is still running, else null. */
    fun pending(socket: BluetoothSocket): CocPrimeGate? = gates[socket]?.takeUnless { it.released }
}

/**
 * The socket output while a [CocPrimeGate] is pending: writes within the allowance go to [early] (the raw socket
 * stream); from the first write past it, after the gate, to [resolveLate] (resolved then, so the adaptive-PHY measurement
 * that the reattached control connection registers is picked up). Both wrap the same socket stream.
 */
internal class PrimeGatedOutputStream(
    private val gate: CocPrimeGate,
    private val early: OutputStream,
    private val resolveLate: () -> OutputStream,
) : OutputStream() {
    @Volatile private var late: OutputStream? = null

    private fun target(length: Int): OutputStream {
        late?.let { return it }
        return if (gate.beforeWrite(length)) resolveLate().also { late = it } else early
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        target(len).write(b, off, len)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun flush() {
        (late ?: early).flush()
    }

    override fun close() {
        early.close()
    }
}
