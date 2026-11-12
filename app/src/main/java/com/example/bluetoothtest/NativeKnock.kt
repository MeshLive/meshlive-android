package com.example.bluetoothtest

/** At most one knock cycle per peer key per [minIntervalMs] (master 5.5). */
internal class KnockThrottle(private val minIntervalMs: Long = WifiLimits.WIDER_KNOCK_INTERVAL_MS) {   // [API]
    private val lastStart = HashMap<String, Long>()

    @Synchronized
    fun tryStart(peerKey: String, nowMs: Long): Boolean {
        val last = lastStart[peerKey]
        if (last != null && nowMs - last < minIntervalMs) return false
        lastStart[peerKey] = nowMs
        if (lastStart.size > 64) lastStart.entries.removeAll { nowMs - it.value >= minIntervalMs }
        return true
    }

    @Synchronized fun forget(peerKey: String) { lastStart.remove(peerKey) }
}

/** One initiator attempt: knock at 0, 1, 2 and 4 s with one nonce, fail no_answer at 8 s, take the first matching answer [AM 11]. No clocks inside: callers pass time. */
internal class NativeKnockMachine(
    val nonce: ByteArray,
    private val startedAtMs: Long,
    private val sendAtMs: LongArray = SEND_AT_MS,                         // [AM 11]
    private val noAnswerMs: Long = NO_ANSWER_MS,                          // [AM 11]
    private val attemptMs: Long = WifiLimits.ATTEMPT_MAX_MS,              // [API]
) {
    sealed interface Action {
        object None : Action
        data class SendKnock(val attempt: Int) : Action
        data class RequestDataPath(val port: Int) : Action
        data class Fail(val reason: String) : Action
    }

    private enum class State { KNOCKING, PATH, DONE }
    private var state = State.KNOCKING
    private var sends = 0
    private var failedSends = 0
    private var lastSendAtMs = startedAtMs
    var ignoredAnswers = 0
        private set

    fun begin(): Action { sends = 1; lastSendAtMs = startedAtMs; return Action.SendKnock(1) }

    fun onSendResult(ok: Boolean): Action {
        if (ok || state != State.KNOCKING) return Action.None
        failedSends++
        return if (failedSends >= sendAtMs.size) fail("send_failed") else Action.None
    }

    fun onAnswer(answerNonce: ByteArray, port: Int): Action {
        if (state != State.KNOCKING) return Action.None
        if (!answerNonce.contentEquals(nonce)) { ignoredAnswers++; return Action.None }
        if (port !in 1..65535) return fail("bad_port")
        state = State.PATH
        return Action.RequestDataPath(port)
    }

    fun onTick(nowMs: Long): Action {
        if (state == State.DONE) return Action.None
        if (nowMs - startedAtMs >= attemptMs) return fail("attempt_timeout")
        if (state != State.KNOCKING) return Action.None
        if (nowMs - startedAtMs >= noAnswerMs) return fail("no_answer")
        if (sends >= sendAtMs.size || nowMs - startedAtMs < sendAtMs[sends]) return Action.None
        sends++; lastSendAtMs = nowMs
        return Action.SendKnock(sends)
    }

    fun finished() { state = State.DONE }

    private fun fail(reason: String): Action { state = State.DONE; return Action.Fail(reason) }

    companion object {
        val SEND_AT_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L)   // [AM 11]
        const val NO_ANSWER_MS = 8_000L
    }
}

/**
 * The initiator's wait for its native connection (A8 review P3-7): short slices until the result arrives, the call's
 * setup deadline passes or the caller cancels (the user's Cancel, or the call ending), so a cancelled call no longer
 * blocks its thread for the whole attempt. Pure: the clock and the slice wait are the caller's.
 */
internal object NativeDialWait {
    sealed interface Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>
        data class Failed(val cause: Throwable?) : Outcome<Nothing>
        object TimedOut : Outcome<Nothing>
        object Cancelled : Outcome<Nothing>
    }

    const val SLICE_MS = 100L

    fun <T> await(
        result: java.util.concurrent.CompletableFuture<T>,
        deadlineAtMs: Long,
        nowMs: () -> Long,
        cancelled: () -> Boolean,
        sliceMs: Long = SLICE_MS,
    ): Outcome<T> {
        while (true) {
            if (cancelled()) return Outcome.Cancelled
            val remaining = deadlineAtMs - nowMs()
            if (remaining <= 0) return Outcome.TimedOut
            try {
                return Outcome.Done(result.get(minOf(remaining, sliceMs), java.util.concurrent.TimeUnit.MILLISECONDS))
            } catch (e: java.util.concurrent.TimeoutException) {
                // next slice
            } catch (e: java.util.concurrent.ExecutionException) {
                return Outcome.Failed(e.cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome.Cancelled
            }
        }
    }
}
