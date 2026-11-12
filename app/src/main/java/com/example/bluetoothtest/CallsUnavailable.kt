package com.example.bluetoothtest

import android.os.SystemClock
import android.util.Log

/** Why calls cannot reach this phone, as the status shows it. */
internal enum class CallsUnavailableReason(val label: String, val valueRes: Int) {
    /** The listen never returned: the phone's Bluetooth stack stopped responding (S22 main-thread wedge). */
    STALLED("stalled", R.string.status_value_calls_stalled),

    /** The listen threw on a stack that leaks CoC servers (Android 10): its registrations are used up. */
    EXHAUSTED("exhausted", R.string.status_value_calls_exhausted),

    /** Any other failed start. */
    FAILED("failed", R.string.status_value_calls_failed),
    ;

    companion object {
        /** [listenFailure]: [error] is what the platform LE CoC listen itself threw ([CocListeners.lastListenFailure]). */
        fun of(error: Throwable, leakyStack: Boolean, listenFailure: Boolean): CallsUnavailableReason {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is ListenStalledException) return STALLED
                cause = cause.cause
            }
            return if (leakyStack && listenFailure) EXHAUSTED else FAILED
        }
    }
}

/** What the status sheet and Home offer for a reason: every reason is fixed in Bluetooth settings (apps cannot toggle it). */
internal enum class CallsUnavailableAction { BLUETOOTH_SETTINGS }

/**
 * Proposal 4 (`test_calls_unavailable_status`, Experimental, off): the call endpoint's start as the status sees it.
 * Before this, a listen that never returned kept the endpoint STARTING for good, which the indicator showed as a green
 * Ready while nobody could call the phone; a thrown listen showed "Hidden", which reads like a user choice.
 *
 * Calls are unavailable while calls are wanted ([reason]'s `reachable`) and the last start failed, or the current start
 * has been STARTING for [CocAvailabilitySwitches.stallMs] or longer. A new start or a success clears it.
 */
internal class CallsAvailabilityTracker(private val nowMs: () -> Long = SystemClock::elapsedRealtime) {
    private var startingSinceMs: Long? = null
    private var fault: CallsUnavailableReason? = null

    @Synchronized
    fun starting() {
        startingSinceMs = nowMs()
        fault = null
    }

    @Synchronized
    fun available() {
        startingSinceMs = null
        fault = null
    }

    @Synchronized
    fun failed(reason: CallsUnavailableReason) {
        startingSinceMs = null
        fault = reason
    }

    /** Milliseconds until a STARTING endpoint counts as stalled (null when not starting). */
    @Synchronized
    fun msUntilStalled(stallMs: Long): Long? = startingSinceMs?.let { (it + stallMs - nowMs()).coerceAtLeast(0L) }

    @Synchronized
    fun reason(enabled: Boolean, reachable: Boolean, starting: Boolean, stallMs: Long): CallsUnavailableReason? {
        if (!enabled || !reachable) return null
        fault?.let { return it }
        val since = startingSinceMs ?: return null
        return if (starting && nowMs() - since >= stallMs) CallsUnavailableReason.STALLED else null
    }

    companion object {
        /** A call end this recent is the one the next listen belongs to. */
        const val CALL_END_WINDOW_MS = 60_000L
        private const val PEER_STOPPED = "peer stopped responding"

        @Volatile private var lastEndMessage: String? = null
        @Volatile private var lastEndAtMs = 0L

        /** True when a stalled listen shows the "peer stopped responding" end before it was this phone's own stack. */
        fun localStackEnd(lastEnd: String?, lastEndAtMs: Long, nowMs: Long): Boolean =
            lastEnd != null && lastEnd.contains(PEER_STOPPED, ignoreCase = true) &&
                nowMs - lastEndAtMs in 0..CALL_END_WINDOW_MS

        fun noteCallEnded(message: String) {
            lastEndMessage = message
            lastEndAtMs = SystemClock.elapsedRealtime()
        }

        /** A listen stalled: re-label the call end before it in the log when it blamed the peer. */
        fun noteListenStalled(role: String) {
            if (!CocAvailabilitySwitches.callsUnavailableStatus || role != CallListenInject.CALL_ROLE) return
            val now = SystemClock.elapsedRealtime()
            val end = lastEndMessage
            if (!localStackEnd(end, lastEndAtMs, now)) return
            lastEndMessage = null
            Log.w(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_end_cause: call_end_cause=local_stack | " +
                    "was=peer_stopped_responding | sinceEndMs=${now - lastEndAtMs} | " +
                    "detail=Bluetooth on this phone stopped responding",
            )
        }
    }
}
