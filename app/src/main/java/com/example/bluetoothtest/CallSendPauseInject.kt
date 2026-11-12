package com.example.bluetoothtest

import android.content.Intent
import android.util.Log

/**
 * DEBUG-only controlled impairment `test_call_inject_send_pause=AFTER_MS:DURATION_MS[,AFTER_MS:DURATION_MS...]` (runner
 * tunable `call_inject_send_pause`, default none): the call writer holds every media record, audio and video, for
 * DURATION_MS starting AFTER_MS after it wrote its first media record, then writes the backlog as it would after a short
 * link gap. Controls and heartbeats keep flowing, so the liveness, stall and renewal rules do not see a dead link.
 *
 * Why: the playout A/B of the Experimental `test_call_fast_recovery` needs the same isolated late burst in both arms; natural
 * receive gaps came in only 2 of 12 control calls and 0 of 12 test calls (cfr/cfr2-20261111, B14). It validates its declared
 * simulation, not streaming performance. Release builds never read it.
 *
 * Log (DEBUG): `BENCHMARK call_inject: kind=send_pause | afterMs= | durationMs= | index=` when a window starts.
 */
internal object CallSendPauseInject {
    const val EXTRA = "test_call_inject_send_pause"
    const val MAX_WINDOWS = 4
    const val MAX_DURATION_MS = 5_000L

    data class Window(val afterMs: Long, val durationMs: Long)

    @Volatile var windows: List<Window> = emptyList()
        private set

    fun readLaunchExtras(intent: Intent) {
        windows = if (BuildConfig.DEBUG) parse(intent.getStringExtra(EXTRA)) else emptyList()
        if (BuildConfig.DEBUG && windows.isNotEmpty()) {
            Log.i("BluetoothVideo", "BENCHMARK call_inject: kind=send_pause | armed=${windows.joinToString(",") { "${it.afterMs}:${it.durationMs}" }}")
        }
    }

    /** "after:duration" entries, comma-separated; malformed ones are left out, durations capped, at most [MAX_WINDOWS]. */
    fun parse(value: String?): List<Window> = value.orEmpty().split(',').mapNotNull { entry ->
        val parts = entry.trim().split(':')
        val afterMs = parts.getOrNull(0)?.toLongOrNull()
        val durationMs = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size != 2 || afterMs == null || durationMs == null || afterMs < 0L || durationMs <= 0L) {
            null
        } else {
            Window(afterMs, minOf(durationMs, MAX_DURATION_MS))
        }
    }.sortedBy { it.afterMs }.take(MAX_WINDOWS)

    /** A schedule for one call writer, or null when nothing is injected (the writer is then unchanged). */
    fun newSchedule(log: (String) -> Unit): Schedule? = windows.takeIf { it.isNotEmpty() }?.let { Schedule(it, log) }

    /** One writer's pauses; not thread-safe (the writer thread alone calls it). */
    class Schedule(private val windows: List<Window>, private val log: (String) -> Unit) {
        private var anchored = false
        private var firstMediaNs = 0L
        private var loggedIndex = -1

        /** The writer wrote a media record at [nowNs]; the first one anchors every window. */
        fun onMediaWritten(nowNs: Long) {
            if (anchored) return
            anchored = true
            firstMediaNs = nowNs
        }

        /** Nanoseconds the writer must still hold media at [nowNs]; 0 before the first media record and outside every window. */
        fun holdNs(nowNs: Long): Long {
            if (!anchored) return 0L
            val sinceMs = (nowNs - firstMediaNs) / NANOS_PER_MS
            windows.forEachIndexed { index, window ->
                if (sinceMs >= window.afterMs && sinceMs < window.afterMs + window.durationMs) {
                    if (index > loggedIndex) {
                        loggedIndex = index
                        log(
                            "BENCHMARK call_inject: kind=send_pause | afterMs=${window.afterMs} | " +
                                "durationMs=${window.durationMs} | index=$index",
                        )
                    }
                    return (firstMediaNs + (window.afterMs + window.durationMs) * NANOS_PER_MS - nowNs).coerceAtLeast(1L)
                }
            }
            return 0L
        }
    }

    private const val NANOS_PER_MS = 1_000_000L
}
