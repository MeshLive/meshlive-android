package com.example.bluetoothtest

/**
 * Design WF revision 3 limits (PROTOCOL "Wi-Fi transport", "Deadlines and limits"). One place for every number, so a
 * later package never hard-codes one. Milliseconds are Long, counts and sizes are Int, except [HOST_VALID_MS] and
 * [SOCKET_CONNECT_TIMEOUT_MS] (Int milliseconds: a wire u16 and `Socket.connect(timeout: Int)`).
 */
internal object WifiLimits {
    /** `HOST` validity; publish and subscribe TTL equal. */
    const val HOST_VALID_MS = 20_000
    const val DISCOVERY_TO_PICK_MS = 8_000L
    const val MAX_KNOCKS_PER_ATTEMPT = 4          // handles knocked at the same time [F-1]
    const val MAX_HANDLES_PER_ATTEMPT = 16        // handles knocked in total [F-1]
    const val MAX_SPOOFED_ATTEMPTS_PER_HOP = 2   // FAILED_SPOOFED attempts before Wi-Fi stops for the hop [F-1]
    const val MAX_CHALLENGES_PER_ATTEMPT = 8
    /** `requestNetwork` timeout overload, both sides. */
    const val DATA_PATH_TIMEOUT_MS = 12_000L
    /** Own wait for the IPv6 address after the data path. */
    const val ADDRESS_WAIT_MS = 5_000L
    const val BIND_FRAME2_DEADLINE_MS = 2_000L
    const val BIND_READY_DEADLINE_MS = 5_000L
    const val MAX_ACCEPTED_SOCKETS_PER_ATTEMPT = 4
    /** `HOST` to the first record on the carrier. */
    const val ATTEMPT_MAX_MS = 25_000L
    const val RETRY_DELAY_MS = 30_000L
    const val MAX_FAILED_ATTEMPTS_PER_HOP = 2
    /** 3 local platform failures within 60 minutes pause every attempt for 60 minutes. */
    const val LOCAL_FAULT_LIMIT = 3
    const val LOCAL_FAULT_WINDOW_MS = 60L * 60_000L
    const val LOCAL_FAULT_PAUSE_MS = 60L * 60_000L
    const val HEARTBEAT_IDLE_MS = 500L
    const val CARRIER_DEAD_SILENCE_MS = 2_500L
    const val CARRIER_DEAD_STALL_MS = 2_500L
    const val CUTOVER_HOLD_MS = 1_000L
    const val CUTOVER_HOLD_BYTES = 5L * 1024 * 1024
    const val CUTOVER_OLD_QUEUE_GATE_MS = 500L
    const val COOLDOWN_AFTER_FALLBACK_MS = 30_000L
    const val MAX_FALLBACKS_PER_HOP = 2
    /** Upper bound of simultaneous data paths: the smallest of the census value, the platform characteristic and this. */
    const val MAX_DATA_PATHS = 4
    const val WIDER_KNOCK_INTERVAL_MS = 10_000L
    const val NATIVE_FOLLOW_UP_RETRIES = 3
    const val NATIVE_FOLLOW_UP_SPACING_MS = 1_000L
    const val SOCKET_SEND_BUFFER_BYTES = 256 * 1024
    /** `Socket.connect` timeout; the guest retries a refused connect every 250 ms inside it. [R S18] */
    const val SOCKET_CONNECT_TIMEOUT_MS = 5_000

    /** A frequency at or above this is 5 GHz or higher (eligibility rule 4, `HOST` of a Wi-Fi Direct group, the Direct guest's band check after joining). [R M-11] */
    const val MIN_5GHZ_MHZ = 4_900
    const val MIN_BATTERY_PERCENT = 15
    /** `PowerManager.THERMAL_STATUS_SEVERE`. */
    const val THERMAL_SEVERE = 3

    /** Longest `BWF1` plaintext (kind 8 and 9 are the longest: 4 + 1 + 28 = 33; the cap leaves room). */
    const val MAX_BWF1_PLAINTEXT_BYTES = 96
    const val MIN_BWF1_PLAINTEXT_BYTES = 5
    /** `CAPS.freeHost` and `CAPS.freeGuest` are 0 to 7. */
    const val MAX_FREE_PATHS_IN_CAPS = 7

    // Wider reach (A8) [GAP+]
    /** A Wi-Fi leg that was not reported again, not lost and not ended with its subscribe session for this long leaves the row. */
    const val SIGHTING_STALE_MS = 120_000L
    /** Unauthenticated native responder reservations at a time (F-3). */
    const val NATIVE_RESPONDERS_MAX = 1
    /** From `MWA1` to the accepted socket (17 s). */
    const val NATIVE_ACCEPT_MS = DATA_PATH_TIMEOUT_MS + ADDRESS_WAIT_MS
    const val DIAL_WAIT_FOR_SIGHTING_MS = 4_000L
    /** From the accepted native socket to completed BMH3/BMH2 admission; then the connection is closed and its data path released (F-3). */
    const val NATIVE_ADMISSION_MS = 10_000L
    /** The Wider-reach subscribe session restarts this often, so a peer the platform reported once is reported again. */
    const val WIDER_SUBSCRIBE_REFRESH_MS = 60_000L
    /** A source's dial of a Wi-Fi-only viewer ends before the broadcaster's 12 s connection timeout. */
    const val WIDER_VIEWER_DIAL_MS = 11_000L
}
