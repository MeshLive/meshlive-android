package com.example.bluetoothtest

/**
 * Limits of the Android Wi-Fi carrier (Design WF revision 3, PROTOCOL "Wi-Fi transport"). One place for every
 * number of the radio services, so no later package hard-codes one.
 */
internal object WifiRadioLimits {
    const val ATTACH_TIMEOUT_MS = 8_000L // platform HAL command timeout is 5 s (platform 1.1)
    const val ATTACH_BACKOFF_BASE_MS = 2_000L // 2 s, 4 s, 8 s ... doubling
    const val ATTACH_BACKOFF_CAP_MS = 300_000L
    const val ATTACH_FAILURE_LIMIT = 6 // consecutive failed attaches before attach is disabled
    const val ATTACH_DISABLED_MS = 3_600_000L // for 1 h (each failed attach leaks a binder, platform 1)
    const val MAX_MESSAGES_IN_FLIGHT = 2 // platform 1.4: never use follow-ups as a bulk channel
    const val MAX_MESSAGES_QUEUED = 4
    const val MESSAGE_WATCHDOG_MS = 12_000L // platform per-message timeout is 10 s
    const val NETWORK_WATCHDOG_EXTRA_MS = 2_000L // after requestNetwork's own timeout
    const val CONNECT_WINDOW_MS = 5_000 // guest keeps retrying a refused connect every CONNECT_RETRY_MS inside this window; Int (AwareLink.listen/connect take Int); equals WifiLimits.SOCKET_CONNECT_TIMEOUT_MS [R A4-1][S18]
    const val CONNECT_RETRY_MS = 250L
    const val ACCEPT_POLL_MS = 500 // ServerSocket.soTimeout slice
    const val CHANNEL_HINT_MHZ = 5_745 // rev 2 step 5; a hint, never required
    // EARLY_REFUSAL_MARGIN_MS removed (F-2, [Am 3]): elapsed time never classifies a refusal
    const val DISCOVERY_PAUSE_AWAIT_MS = 3_000L // suggested DiscoveryPauseLease.awaitPaused timeout for a caller on a worker thread [R M-10]

    /**
     * Whether a `requestNetwork` throw may lower the learned data-path cap ([Am 3]). Not the per-app network-callback
     * limit (`ConnectivityManager.TooManyRequestsException`, hidden, so matched by name) and not an invalid request
     * (`IllegalArgumentException`): neither says anything about the chip's data paths. [A4 review P3-10]
     */
    fun lowersLearnedNdpCap(e: RuntimeException): Boolean =
        e !is IllegalArgumentException && e.javaClass.simpleName != "TooManyRequestsException"
}
