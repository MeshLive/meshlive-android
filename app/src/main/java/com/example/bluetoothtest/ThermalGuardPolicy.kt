package com.example.bluetoothtest

import android.os.PowerManager

object ThermalGuardPolicy {
    fun shouldBlockStreaming(status: Int): Boolean =
        status >= PowerManager.THERMAL_STATUS_CRITICAL

    /**
     * Calls at critical heat (DECISIONS 2026-11-06): heat never refuses a call, only its video. At [status] a
     * phone places and answers voice calls, and its call camera stays off.
     */
    fun callVideoAllowed(status: Int): Boolean = !shouldBlockStreaming(status)

    /** What a call [requested] by the user becomes at [status]: a video call goes on as voice at critical heat. */
    fun callTypeAtHeat(requested: CallType, status: Int): CallType =
        if (callVideoAllowed(status)) requested else CallType.VOICE

    fun shouldReduceWorkload(status: Int, reductionEnabled: Boolean): Boolean =
        reductionEnabled && status >= PowerManager.THERMAL_STATUS_MODERATE &&
            !shouldBlockStreaming(status)

    fun workloadPercent(status: Int, reductionEnabled: Boolean): Int = when {
        !reductionEnabled -> 100
        status >= PowerManager.THERMAL_STATUS_SEVERE -> 80
        status >= PowerManager.THERMAL_STATUS_MODERATE -> 90
        else -> 100
    }

    fun maximumFps(selectedFps: Int, status: Int, reductionEnabled: Boolean): Int = scale(
        selectedFps,
        workloadPercent(status, reductionEnabled),
    )

    fun maximumBitrate(selectedBitrate: Int, status: Int, reductionEnabled: Boolean): Int = scale(
        selectedBitrate,
        workloadPercent(status, reductionEnabled),
    )

    private fun scale(selected: Int, percent: Int): Int =
        ((selected.toLong() * percent + 50L) / 100L).toInt().coerceIn(1, selected)
}
