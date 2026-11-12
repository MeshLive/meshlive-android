package com.example.bluetoothtest

/**
 * The call endpoint's epoch-boundary restart while a link may be in flight (R1 C9, 2026-11-10). A central that
 * connected but is not admitted yet is a connection in progress: the restart waits for it, bounded, instead of
 * closing the server under it. The old ID is off the air while it waits, so the boundary still changes the address.
 */
internal object BoundaryRestartPlan {
    /** Covers connect -> subscribe -> first record (an iPhone subscribes 0.6-2.5 s after it connects). */
    const val SETUP_GRACE_MS = 5_000L
    const val POLL_MS = 100L

    enum class Step { RESTART_NOW, STOP_ADVERT_AND_WAIT, SKIP, RESTART_AFTER_GRACE }

    /**
     * [callStarted]: the endpoint stopped running or a call (or exchange) owns it now, so the link is kept and the
     * next listener uses the current epoch (RV4). [linkInFlight]: a central is connected to the call server.
     */
    fun decide(callStarted: Boolean, linkInFlight: Boolean, waitedMs: Long, graceMs: Long = SETUP_GRACE_MS): Step =
        when {
            callStarted -> Step.SKIP
            !linkInFlight -> Step.RESTART_NOW
            waitedMs >= graceMs -> Step.RESTART_AFTER_GRACE
            else -> Step.STOP_ADVERT_AND_WAIT
        }

    /**
     * The centrals connected to a call server that is not admitted yet (LE CoC control server: its GATT control link is up from the
     * connect until the L2CAP channel is accepted, which only then marks the call as running). Thread-safe; a repeated event is harmless.
     */
    class ConnectedCentrals {
        private val addresses = HashSet<String>()

        @Synchronized fun connected(address: String) { addresses.add(address) }

        @Synchronized fun disconnected(address: String) { addresses.remove(address) }

        @Synchronized fun clear() { addresses.clear() }

        @Synchronized fun any(): Boolean = addresses.isNotEmpty()
    }

    fun outcome(step: Step): String = when (step) {
        Step.SKIP -> "call_started"
        Step.RESTART_NOW -> "link_gone"
        Step.RESTART_AFTER_GRACE -> "grace_expired"
        Step.STOP_ADVERT_AND_WAIT -> "waiting"
    }
}
