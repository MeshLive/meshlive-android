package com.example.bluetoothtest

/**
 * The per-hop attempt limits: retries, cooldowns, guest service limits and the busy back-off. Pure; time arrives
 * through [clock]. The two-fallback rule: two quality fallbacks (counted even when this direction never moved) end
 * Wi-Fi attempts for the call; the count comes from the machine through the hop's ports.
 */
internal class WifiHopPlanner(private val clock: () -> Long) {
    var failedAttempts = 0
        private set
    var noPenaltyEnds = 0
        private set
    var guestHostsAccepted = 0
        private set
    var spoofedAttempts = 0
        private set
    var busyStreak = 0
        private set
    private var nextAtMs = 0L
    private var lastEndMs = 0L
    private var never: String? = null

    sealed interface Verdict {
        object Go : Verdict
        data class Wait(val untilMs: Long, val why: String) : Verdict
        data class Never(val why: String) : Verdict
    }

    sealed interface GuestVerdict {
        object Serve : GuestVerdict
        data class Decline(val reason: Int, val why: String) : GuestVerdict
    }

    /**
     * May a host attempt start now? Order: a final reason; the spoof cap; the two-failed-attempts cap; the
     * three-no-penalty cap; the hop's two fallbacks; the cooldown; else go. Two quality fallbacks (counted even when
     * this direction never moved) end Wi-Fi attempts for the call.
     */
    fun canStart(fallbacks: Int): Verdict {
        never?.let { return Verdict.Never(it) }
        if (spoofedAttempts >= WifiLimits.MAX_SPOOFED_ATTEMPTS_PER_HOP) return Verdict.Never("spoof_cap")
        if (failedAttempts >= WifiLimits.MAX_FAILED_ATTEMPTS_PER_HOP) return Verdict.Never("failed_twice")
        if (noPenaltyEnds >= WifiAttemptLimits.MAX_NO_PENALTY_ENDS) return Verdict.Never("declined_thrice")
        if (fallbacks >= WifiLimits.MAX_FALLBACKS_PER_HOP) return Verdict.Never("fallbacks")
        val now = clock()
        if (now < nextAtMs) return Verdict.Wait(nextAtMs, "cooldown")
        return Verdict.Go
    }

    fun onAttemptEnded(outcome: Outcome) {
        val now = clock()
        lastEndMs = now
        when (outcome.kind) {
            OutcomeKind.SUCCESS -> {
                nextAtMs = now
                busyStreak = 0
            }
            OutcomeKind.FAILED_LOCAL, OutcomeKind.FAILED_PEER -> {
                failedAttempts++
                nextAtMs = now + WifiLimits.RETRY_DELAY_MS
            }
            OutcomeKind.FAILED_SPOOFED -> {
                spoofedAttempts++
                nextAtMs = now + WifiLimits.RETRY_DELAY_MS
            }
            OutcomeKind.NO_PENALTY -> {
                if (outcome.busy) {
                    busyStreak++
                    nextAtMs = now + minOf(30_000L shl (busyStreak - 1), 240_000L)
                } else {
                    noPenaltyEnds++
                    nextAtMs = now + WifiLimits.RETRY_DELAY_MS
                }
            }
            OutcomeKind.CANCELLED -> nextAtMs = now + WifiLimits.RETRY_DELAY_MS
        }
    }

    /** A Wi-Fi carrier vanished: no new attempt for 30 s after this moment. */
    fun onCarrierLost() {
        nextAtMs = maxOf(nextAtMs, clock() + WifiLimits.RETRY_DELAY_MS)
    }

    /** switch off, violation, call ending: final for this hop. */
    fun neverBecause(why: String) {
        never = why
    }

    fun guestMayServe(): GuestVerdict {
        if (spoofedAttempts >= WifiLimits.MAX_SPOOFED_ATTEMPTS_PER_HOP) return GuestVerdict.Decline(1, "spoof_cap")
        if (guestHostsAccepted >= WifiAttemptLimits.GUEST_MAX_HOSTS_PER_HOP) return GuestVerdict.Decline(4, "host_limit")
        val now = clock()
        if (lastEndMs != 0L && now - lastEndMs < WifiAttemptLimits.GUEST_MIN_SPACING_MS) {
            return GuestVerdict.Decline(4, "host_spacing")
        }
        return GuestVerdict.Serve
    }

    fun onGuestHostAccepted() {
        guestHostsAccepted++
    }

    /** A data path was released: a host waiting on no_ndp tries at once and the busy back-off restarts. */
    fun onDataPathReleased() {
        busyStreak = 0
    }
}
