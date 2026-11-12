package com.example.bluetoothtest

import android.media.AudioManager
import android.telephony.TelephonyManager

/** Registry entry (AudioPolicyEntries) that switches the refusal classification and delayed focus. */
internal const val CALL_FOCUS_ENTRY_ID = "android15-audio-focus-requires-top-or-fgs"

internal enum class FocusRefusal { CELLULAR_CALL, NOT_FOREGROUND }

/**
 * Why a focus request was refused. A real cellular call is recognised by the platform audio mode the call
 * app set (MODE_IN_CALL, MODE_RINGTONE for a ringing call, MODE_CALL_SCREENING) or by a non-idle telephony
 * call state; neither needs a permission for the mode. Unknown (mode unreadable, no telephony answer) is NOT a
 * call: a wrong hold cannot be released (a refused request gets no later callback), so only evidence holds.
 */
internal object CallFocusRefusalPolicy {
    private val CALL_MODES = setOf(
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_CALL_SCREENING,
    )

    fun classify(audioMode: Int?, telephonyCallState: Int?): FocusRefusal = when {
        audioMode != null && audioMode in CALL_MODES -> FocusRefusal.CELLULAR_CALL
        telephonyCallState != null && telephonyCallState != TelephonyManager.CALL_STATE_IDLE -> FocusRefusal.CELLULAR_CALL
        else -> FocusRefusal.NOT_FOREGROUND
    }

    fun resultLabel(result: Int?): String = when (result) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
        null -> "error"
        else -> "unknown_$result"
    }
}

/** What [CallFocusArbiter] needs from the platform; faked in tests. */
internal interface CallFocusApi {
    /** One requestAudioFocus call; null when the call threw. */
    fun requestFocus(): Int?
    fun audioMode(): Int?
    fun telephonyCallState(): Int?
    fun foregroundServiceHeld(): Boolean

    /** Runs [block] after [delayMs] on the main thread; the returned function cancels it. */
    fun postDelayed(delayMs: Long, block: () -> Unit): () -> Unit
    fun log(event: String, detail: String)
}

/**
 * The call's focus decisions without Android types (JVM-tested). [classifyRefusals] false is the registry
 * entry's off state and restores the former rule: every refusal holds. [onAction] runs on the thread that
 * calls [request], [onForegroundHeld] or the retry timer (the main thread in the app).
 */
internal class CallFocusArbiter(
    private val api: CallFocusApi,
    private val classifyRefusals: Boolean,
    private val onAction: (FocusAction) -> Unit,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) {
    @Volatile private var retryUsed = false
    @Volatile private var cancelRetry: (() -> Unit)? = null

    /** Requests focus; true when it was granted right away. */
    fun request(): Boolean {
        val result = api.requestFocus()
        return handle(result, isRetry = false)
    }

    /** The call's foreground service now holds its types: a refused request is retried at once. */
    fun onForegroundHeld() {
        val pending = cancelRetry ?: return
        pending()
        retry()
    }

    fun cancel() {
        cancelRetry?.invoke()
        cancelRetry = null
    }

    private fun retry() {
        cancelRetry = null
        val result = api.requestFocus()
        handle(result, isRetry = true)
    }

    private fun handle(result: Int?, isRetry: Boolean): Boolean {
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        val label = CallFocusRefusalPolicy.resultLabel(result)
        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED, AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                val mode = api.audioMode()
                val kind = if (classifyRefusals) {
                    CallFocusRefusalPolicy.classify(mode, api.telephonyCallState())
                } else {
                    FocusRefusal.CELLULAR_CALL
                }
                val common = "result=$label | granted=false | audioMode=${mode ?: "?"} | " +
                    "fgsHeld=${api.foregroundServiceHeld()} | retry=$isRetry"
                if (kind == FocusRefusal.CELLULAR_CALL) {
                    api.log("request", "$common | focus=${label}_cellular_call | action=HOLD")
                    onAction(FocusAction.HOLD)
                } else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
                    api.log("request", "$common | focus=delayed_wait | action=NONE")
                } else if (!isRetry && !retryUsed) {
                    retryUsed = true
                    api.log("request", "$common | focus=failed_not_foreground | action=NONE | next=retry_${retryDelayMs}ms")
                    cancelRetry = api.postDelayed(retryDelayMs, ::retry)
                } else {
                    api.log("request", "$common | focus=failed_not_foreground_final | action=NONE")
                }
            }
            else -> api.log("request", "result=$label | granted=$granted | retry=$isRetry")
        }
        return granted
    }

    companion object {
        /** One retry after the foreground service had time to start (startForeground follows startForegroundService). */
        const val RETRY_DELAY_MS = 1_000L
    }
}
