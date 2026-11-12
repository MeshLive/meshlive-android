package com.example.bluetoothtest

/** Why an attach did not happen or ended. [declineReason] equals `WifiEligibility.DeclineReason.wire`; [localFault] marks the failures that count toward the phone-wide pause. */
internal enum class AttachFailure(val declineReason: Int, val localFault: Boolean) {
    BUSY_DIRECT(2, false),
    BACKOFF(4, false),
    DISABLED(4, false),
    NO_MANAGER(1, false),
    NO_PERMISSION(1, false),
    WIFI_OFF(3, false),
    UNAVAILABLE(3, false),
    ATTACH_REFUSED(6, true),
    ATTACH_TIMEOUT(6, true),
    EXCEPTION(6, true),
}

internal enum class AwareDownReason { UNAVAILABLE, WIFI_OFF, SESSION_TERMINATED, SHUTDOWN }

internal interface AwareHolder<S : Any> {
    fun onAttached(session: S) // always posted, never inside acquire()
    fun onAttachFailed(reason: AttachFailure) // the hold is already removed
    fun onAwareDown(reason: AwareDownReason) // the hold is already removed; reason SHUTDOWN is terminal: the holder must never acquire again (A8 stays detached)
}

internal class AwareHold(val id: Long, val owner: String)

internal interface AwareAttachPort<S : Any> {
    fun precheck(): AttachFailure? // null = go ahead
    fun deviceAttachedByAnyone(): Boolean?
    fun startAttach(token: Long) // real: manager.attach(callback(token), handler); may throw RuntimeException
    fun closeSession(session: S) // must swallow its own exceptions
    fun registerReceivers()
    fun unregisterReceivers() // idempotent
    fun post(block: () -> Unit)
    fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit)
    fun cancel(tag: Any)
    fun log(line: String)
}

/**
 * Ref-counted Wi-Fi Aware attach: one session serves every holder, the platform edge cases of `platform.md` are the
 * whole point (a failed attach leaks a binder and `isAvailable()` stays true, so retries back off and stop; a grant
 * after a timeout must be closed; release while attaching must not drop the pending attach). All calls are on the
 * wifi thread; holder callbacks are always posted, never run inside `acquire`/`release`.
 */
internal class AwareAttachCoordinator<S : Any>(
    private val port: AwareAttachPort<S>,
    private val ledger: WifiLedger,
    private val clock: () -> Long,
) {
    private enum class State { IDLE, ATTACHING, ATTACHED }

    @Volatile
    var heldOrAttaching = false
        private set

    private val holders = LinkedHashMap<AwareHold, AwareHolder<S>>()
    private val timeoutTag = Any()
    private var state = State.IDLE
    private var session: S? = null
    private var token = 0L
    private var tokenSeq = 0L
    private var abandoned = false
    private var consecutiveFailures = 0
    private var backoffUntilMs = 0L
    private var disabledUntilMs = 0L

    fun acquire(hold: AwareHold, holder: AwareHolder<S>) {
        holders[hold] = holder
        when (state) {
            State.ATTACHED -> {
                val current = session
                port.post { if (hold in holders && current != null) holder.onAttached(current) }
            }
            State.ATTACHING -> abandoned = false
            State.IDLE -> beginAttach()
        }
    }

    private fun beginAttach() {
        if (ledger.directGroupActive) {
            failAll(AttachFailure.BUSY_DIRECT)
            return
        }
        val now = clock()
        if (now < disabledUntilMs) {
            failAll(AttachFailure.DISABLED)
            return
        }
        if (now < backoffUntilMs) {
            failAll(AttachFailure.BACKOFF)
            return
        }
        port.registerReceivers() // before the availability check (platform 1.1)
        val pre = port.precheck()
        if (pre != null) {
            port.unregisterReceivers()
            failAll(pre)
            return
        }
        ledger.otherClientAtAttach = port.deviceAttachedByAnyone() // read before our attach
        state = State.ATTACHING
        token = ++tokenSeq
        abandoned = false
        refreshFlags()
        log("start", null)
        val thisToken = token
        port.postDelayed(timeoutTag, WifiRadioLimits.ATTACH_TIMEOUT_MS) {
            log("timeout", "attach_timeout")
            onAttachFailed(thisToken, AttachFailure.ATTACH_TIMEOUT)
        }
        try {
            port.startAttach(token)
        } catch (e: RuntimeException) {
            onAttachFailed(token, AttachFailure.EXCEPTION)
        }
    }

    /** No platform state: the pending attach (if any) is still resolved by its callback or timeout, which see an empty holder set and close the grant. */
    private fun failAll(reason: AttachFailure) {
        val taken = holders.toMap()
        holders.clear()
        state = State.IDLE
        refreshFlags()
        taken.forEach { (_, holder) -> port.post { holder.onAttachFailed(reason) } }
    }

    fun onAttached(token: Long, session: S) {
        if (token != this.token || state != State.ATTACHING) {
            port.closeSession(session) // stale grant: closed at once
            return
        }
        port.cancel(timeoutTag)
        this.session = session
        state = State.ATTACHED
        consecutiveFailures = 0
        refreshFlags()
        log("ok", null)
        if (holders.isEmpty()) {
            dropAttachedSession()
            return
        }
        val taken = holders.toMap()
        taken.forEach { (hold, holder) ->
            port.post { if (hold in holders) holder.onAttached(session) }
        }
    }

    fun onAttachFailed(token: Long, reason: AttachFailure) {
        if (token != this.token || state != State.ATTACHING) return
        port.cancel(timeoutTag)
        this.token = 0L // invalidates a late onAttached
        consecutiveFailures++
        ledger.recordLocalFault("attach_" + reason.name.lowercase())
        backoffUntilMs = clock() + backoffMs(consecutiveFailures)
        log("failed", reason.name.lowercase())
        if (consecutiveFailures >= WifiRadioLimits.ATTACH_FAILURE_LIMIT) {
            disabledUntilMs = clock() + WifiRadioLimits.ATTACH_DISABLED_MS
            log("disabled", reason.name.lowercase())
        } else {
            log("backoff", reason.name.lowercase())
        }
        port.unregisterReceivers()
        failAll(reason)
    }

    private fun backoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 20)
        return minOf(WifiRadioLimits.ATTACH_BACKOFF_CAP_MS, WifiRadioLimits.ATTACH_BACKOFF_BASE_MS * (1L shl shift))
    }

    fun onSessionTerminated(token: Long) {
        if (token != this.token) return
        down(AwareDownReason.SESSION_TERMINATED)
    }

    fun onAwareUnavailable(reason: AwareDownReason) {
        down(reason)
    }

    fun shutdown(reason: AwareDownReason = AwareDownReason.SHUTDOWN) {
        down(reason)
    }

    /** Aware or Wi-Fi went away: not a failed attach (no backoff, no fault); the user switching Wi-Fi off is not a platform failure of ours. */
    private fun down(reason: AwareDownReason) {
        if (state == State.IDLE) return
        val wasAttached = state == State.ATTACHED
        val wasAttaching = state == State.ATTACHING
        val taken = holders.toMap()
        holders.clear()
        if (wasAttached) {
            session?.let { port.closeSession(it) }
            session = null
        }
        if (wasAttaching) {
            port.cancel(timeoutTag)
            this.token = 0L
        }
        state = State.IDLE
        port.unregisterReceivers()
        refreshFlags()
        taken.forEach { (_, holder) ->
            if (wasAttached || reason == AwareDownReason.SHUTDOWN) {
                port.post { holder.onAwareDown(reason) }
            } else {
                val failure = if (reason == AwareDownReason.WIFI_OFF) AttachFailure.WIFI_OFF else AttachFailure.UNAVAILABLE
                port.post { holder.onAttachFailed(failure) }
            }
        }
        log("down", reason.name.lowercase())
    }

    fun release(hold: AwareHold): Boolean {
        if (holders.remove(hold) == null) return false
        if (holders.isNotEmpty()) return true
        when (state) {
            State.ATTACHED -> dropAttachedSession()
            State.ATTACHING -> abandoned = true // keep waiting; a late grant is dropped by onAttached
            State.IDLE -> Unit
        }
        return true
    }

    private fun dropAttachedSession() {
        session?.let { port.closeSession(it) }
        session = null
        state = State.IDLE
        ledger.lastReleaseMs = clock()
        port.unregisterReceivers()
        refreshFlags()
        log("release", null)
    }

    private fun refreshFlags() {
        heldOrAttaching = state != State.IDLE
        ledger.awareHeld = state != State.IDLE
    }

    private fun log(step: String, reason: String?) {
        port.log(
            WifiLog.upgrade(
                "attach",
                "step" to step,
                "holders" to holders.size,
                "consecutive" to consecutiveFailures,
                "reason" to reason,
            ),
        )
    }
}
