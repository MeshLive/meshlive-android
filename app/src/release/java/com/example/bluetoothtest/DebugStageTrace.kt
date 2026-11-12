package com.example.bluetoothtest

import java.io.Closeable

class DebugStageTrace private constructor() : Closeable {
    fun record(stage: String, ptsUs: Long, bytes: Int = 0, atNs: Long = 0L, extra: Map<String, Any>? = null) = Unit
    override fun close() = Unit
    companion object {
        /** Release builds have no stage trace; the DEBUG `test_stage_trace_v1` switch is accepted and ignored. */
        var legacyV1: Boolean = false
        fun startIfRequested(session: String?, role: String): DebugStageTrace? = null
    }
}
