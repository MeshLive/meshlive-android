package com.example.bluetoothtest

/**
 * How long a viewer searches for a lost stream (audit 2026-11-10 CALL-04; owner answer: 60 s). When the source walks out
 * of range, its app is killed or its battery dies, the viewer shows "Connection lost" and scans for another route. Without a
 * bound that lasts until the user leaves, keeps the scan running and keeps the phone closed to calls. Default on; DEBUG
 * control arm `test_stream_search_bound=false` restores the unbounded search. Pure Kotlin: the screen calls [begin] at every
 * unexpected loss (the first one starts the clock, later failed attempts of the same search do not restart it), [end] when
 * the stream plays again or the search is over, and asks [expired] / [remainingMs] to decide when to return to discovery.
 */
internal class StreamSearchBound(
    private val boundMs: Long = DEFAULT_BOUND_MS,
    private val enabled: () -> Boolean = { DebugTunables.streamSearchBound },
) {
    private var startedAtMs: Long? = null

    /** An unexpected loss (or a failed reconnect attempt) at [nowMs]: starts the clock only if no search is running. */
    @Synchronized
    fun begin(nowMs: Long) {
        if (startedAtMs == null) startedAtMs = nowMs
    }

    /** The search is over: the stream is back, or the viewer returned to discovery. The next loss starts a new clock. */
    @Synchronized
    fun end() {
        startedAtMs = null
    }

    /** Milliseconds left, or null when no search is running or the bound is off. */
    @Synchronized
    fun remainingMs(nowMs: Long): Long? {
        val started = startedAtMs ?: return null
        if (!enabled()) return null
        return (started + boundMs - nowMs).coerceAtLeast(0L)
    }

    @Synchronized
    fun expired(nowMs: Long): Boolean = remainingMs(nowMs) == 0L

    companion object {
        const val DEFAULT_BOUND_MS = 60_000L
    }
}
