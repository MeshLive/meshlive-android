package com.example.bluetoothtest

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Bounded opt-in stage logging; scope/sequence/final distinguish incomplete or recycled traces.
 *
 * Default format is `BENCHMARK stage v2` (batched lines, see [StageTraceBatcher]); the launch extra
 * `test_stage_trace_v1=true` ([legacyV1]) keeps the original one-line-per-record `BENCHMARK stage v1` format so older
 * evidence can be reproduced. Both carry the same records and fields.
 */
class DebugStageTrace private constructor(private val session: String, private val role: String) : Closeable {
    private val scope = System.nanoTime().toString()
    private var sequence = 0L
    private var closed = false
    private val batcher: StageTraceBatcher? = if (legacyV1) null else StageTraceBatcher(
        session, scope, role, android.os.Process.myPid(),
        sink = { line -> Log.i("BluetoothVideo", line) },
        schedule = { delayNs, task -> flushTimer.schedule(task, delayNs, TimeUnit.NANOSECONDS) },
    )

    /**
     * [extra] adds diagnostic fields to the row (for example encode_output's parsed VP9 q_idx and
     * frame_type); the scorer keeps unknown fields and never needs them.
     */
    @Synchronized
    fun record(stage: String, ptsUs: Long, bytes: Int = 0, atNs: Long = System.nanoTime(), extra: Map<String, Any>? = null) {
        if (closed) return
        if (batcher != null) batcher.record(stage, ptsUs, bytes, atNs, extra)
        else emitV1(stage, ptsUs, bytes, atNs, false, extra)
    }

    private fun emitV1(stage: String, ptsUs: Long, bytes: Int, atNs: Long, final: Boolean, extra: Map<String, Any>? = null) {
        val row = JSONObject()
            .put("session", session).put("scope", scope).put("role", role)
            .put("direction", StageTraceBatcher.direction(role, stage))
            .put("pid", android.os.Process.myPid()).put("clock", "System.nanoTime")
            .put("sequence", ++sequence).put("stage", stage).put("pts_us", ptsUs)
            .put("at_ns", atNs).put("bytes", bytes).put("final", final)
        extra?.forEach { (key, value) -> if (!row.has(key)) row.put(key, value) }
        Log.i("BluetoothVideo", "BENCHMARK stage v1 " + row.toString())
    }

    @Synchronized override fun close() {
        if (closed) return
        if (batcher != null) batcher.close() else emitV1("final", 0, 0, System.nanoTime(), true)
        closed = true
    }

    companion object {
        /** DEBUG launch extra `test_stage_trace_v1=true`: write the legacy per-record v1 lines (set by MainActivity). */
        @Volatile var legacyV1: Boolean = false

        /** One shared daemon thread flushes batches whose stream went idle; it only runs while a batch is pending. */
        private val flushTimer: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { task -> Thread(task, "stage-trace-flush").apply { isDaemon = true } }
        }

        fun startIfRequested(session: String?, role: String): DebugStageTrace? = session?.takeIf(String::isNotBlank)
            ?.let { DebugStageTrace(it, role) }
    }
}
