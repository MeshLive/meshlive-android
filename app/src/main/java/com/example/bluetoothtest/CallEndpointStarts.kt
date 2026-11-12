package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The private-call endpoint's asynchronous starts, as the activity sees them (STREAM-INTEROP block 1,
 * 2026-11-06). The endpoint starts on a worker thread (startAvailability opens its GATT server or CoC
 * listener and advertising) while the user may join a stream on the Live tab. Before this, a join that
 * overtook a start closed the endpoint object while the worker was still opening its listener; the
 * listener finished opening afterwards and stayed up next to the viewer's GATT server (same service
 * UUIDs), so a stream source's connect-back could land on the call listener and be refused
 * ("Incoming call failed", bmh2 refused).
 *
 * A stop cancels every start in flight; a cancelled start's completion closes its endpoint instead of
 * publishing "available"; work that must not overlap the call listener (the viewer's start) waits
 * until no start is in flight. Main-thread only: not thread-safe.
 */
internal class CallEndpointStartGate {
    class Token internal constructor(internal val id: Long)

    private var nextId = 0L
    /** Start id -> cancelled. */
    private val inFlight = LinkedHashMap<Long, Boolean>()
    private val waiters = ArrayList<() -> Unit>()

    val startInFlight: Boolean get() = inFlight.isNotEmpty()

    fun begin(): Token = Token(++nextId).also { inFlight[it.id] = false }

    /** A stop: every start in flight must close what it opens. Returns how many were in flight. */
    fun cancelAll(): Int {
        for (id in inFlight.keys) inFlight[id] = true
        return inFlight.size
    }

    /**
     * The start's worker returned. Returns true when the endpoint may publish "available"; otherwise
     * (cancelled by a stop, or no longer the activity's endpoint) [close] runs first. Waiting work runs
     * once no start remains in flight, after that close.
     */
    fun finish(token: Token, stillCurrent: Boolean, close: () -> Unit): Boolean {
        val cancelled = inFlight.remove(token.id) ?: true
        val publish = !cancelled && stillCurrent
        if (!publish) close()
        if (inFlight.isEmpty()) releaseWaiters()
        return publish
    }

    /** Runs [action] now when no start is in flight (returns false), else once the last one finishes (returns true). */
    fun whenSettled(action: () -> Unit): Boolean {
        if (inFlight.isEmpty()) {
            action()
            return false
        }
        waiters += action
        return true
    }

    /**
     * The settle timeout: a hung start must not hold the viewer. Starts in flight stop counting (their late finish
     * still closes the endpoint and never publishes) and the waiting work runs now. Returns how many waiters ran.
     */
    fun abandonInFlight(): Int {
        inFlight.clear()
        return releaseWaiters()
    }

    private fun releaseWaiters(): Int {
        if (waiters.isEmpty()) return 0
        val ready = waiters.toList()
        waiters.clear()
        ready.forEach { it() }
        return ready.size
    }
}

/**
 * The endpoint side of the same race: startAvailability opens its listener outside any lock (opening
 * waits for main-thread callbacks) while close() may run on the main thread. close() used to find
 * nothing to stop yet, and a second close() was a no-op, so the late listener stayed up for good. Now
 * the start publishes under this fence: if close() ran meanwhile, the start stops what it opened
 * itself; otherwise close()'s stop, under the same fence, sees everything the start registered.
 */
internal class ListenerStartFence(private val running: AtomicBoolean) {
    private val lock = Any()

    /** After the listener opened: [register] and true, or, when the endpoint closed meanwhile, [stopOpened] and false. */
    fun publish(stopOpened: () -> Unit, register: () -> Unit): Boolean = synchronized(lock) {
        if (!running.get()) {
            stopOpened()
            false
        } else {
            register()
            true
        }
    }

    /** close(): the caller cleared running first; [block] (the listener stop) runs under the fence. */
    fun stop(block: () -> Unit) = synchronized(lock) { block() }
}

/**
 * Diagnostics: the call listener accepted a connection while this phone is a viewer waiting for, or
 * joined to, a stream parent. With the endpoint stopped at join this should never happen; the line
 * makes a regression visible in one grep (`call_listener: event=accepted_while_viewing`).
 */
internal object CallListenerViewing {
    /** Set by the activity: true while a stream is selected or a viewer runs. */
    @Volatile var probe: (() -> Boolean)? = null

    fun viewing(): Boolean = runCatching { probe?.invoke() == true }.getOrDefault(false)

    fun logLine(transport: String): String = "call_listener: event=accepted_while_viewing | transport=$transport"
}
