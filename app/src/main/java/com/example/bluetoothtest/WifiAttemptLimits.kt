package com.example.bluetoothtest

/**
 * Limits of the per-hop Wi-Fi attempt owner (package A5). Timeouts are milliseconds and are Long unless a platform
 * signature takes an Int. One place for every number, so the agent and the model never hard-code one.
 */
internal object WifiAttemptLimits {
    /** publish/subscribe start: the platform's HAL command timeout is 5 s. */
    const val SESSION_START_MS = 6_000L
    const val REQUEST_WATCHDOG_MS = WifiLimits.DATA_PATH_TIMEOUT_MS + WifiRadioLimits.NETWORK_WATCHDOG_EXTRA_MS // 14 s [API][S19]
    /** 04 root follow-up: three sends take 1.5 s, so four spoofed handles are evicted inside the 8 s PICK window. [Am 2] */
    const val KNOCK_RESEND_MS = 500L
    const val MAX_KNOCK_SENDS_PER_HANDLE = 3
    /** 16 handles x 3 sends. [Am 2] */
    const val MAX_KNOCK_SENDS_TOTAL = WifiLimits.MAX_HANDLES_PER_ATTEMPT * MAX_KNOCK_SENDS_PER_HANDLE
    /** Challenge messages the guest verifies per knocked handle. [Am 2] */
    const val MAX_CHALLENGE_CHECKS_PER_HANDLE = 3
    const val MAX_CHALLENGE_SENDS_PER_HANDLE = 3
    /** Aware messages looked at per attempt (CPU bound against replay floods). */
    const val MAX_MESSAGES_SEEN = 32
    /** "call ACTIVE with video on for 3 s" (revision 2). */
    const val VIDEO_STABLE_MS = 3_000L
    /** Poll while the host waits for a condition. */
    const val RECHECK_MS = 5_000L
    /** Poll while CAPS cannot be sent yet. */
    const val CAPS_POLL_MS = 1_000L
    const val CAPS_POLL_BUDGET_MS = 60_000L
    const val MAX_NO_PENALTY_ENDS = 3
    const val GUEST_MAX_HOSTS_PER_HOP = 6
    const val GUEST_MIN_SPACING_MS = WifiLimits.RETRY_DELAY_MS - 1_000L
    const val LATE_TIMER_LOG_MS = 2_000L
    /** Cadence of the carrier monitor (M-9). */
    const val GOODPUT_POLL_MS = 1_000L
    const val GOODPUT_LOW_SECONDS = 5
    /** AP9: the Aware goodput guard's window and consecutive low windows. */
    const val GOODPUT_WINDOW_MS = 5_000L
    const val GOODPUT_LOW_WINDOWS = 3
    /** Threshold = factor x the standby's learned Bluetooth budget. */
    const val GOODPUT_BUDGET_FACTOR = 2
    /** A sample sooner than this is skipped, counters unchanged. */
    const val GOODPUT_SAMPLE_MIN_MS = 500L
    /** A gap above this resets both counters and reseeds. [Am 4] */
    const val GOODPUT_SAMPLE_MAX_MS = 3_000L
    /** F-9: a second is slow when the send carrier's oldest queued record is older than this. [Am 4] */
    const val QUEUE_SLOW_AGE_MS = 300L
    const val QUEUE_SLOW_SECONDS = 5
}
