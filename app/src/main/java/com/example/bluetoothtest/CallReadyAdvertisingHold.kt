package com.example.bluetoothtest

/**
 * `test_call_adv_idle_fast` (promoted 2026-11-12 on mechanism, default on; join-time JT2-P2). A call endpoint's GATT advertising set starts at
 * low latency (100 ms, high power) and takes its balanced step (250 ms, medium power) 10 s later, so an idle callee that a
 * caller dials after the first 10 s answers the connect request slowly (idle connects 0.27-1.08 s against 0.16-0.30 s in
 * the burst). With the switch on, the receiver set of a call endpoint keeps its fast interval while no link is up and is
 * not stepped down. Nothing else changes: no new advertising-set operation (the set simply is not asked to move), and
 * streams, relays, the in-call renewal advert and every other role keep the step. The ASUS already gets the same outcome
 * through [InPlaceStepHealth.skipBalanced], so this is a switch for the other phones; the price is the extra idle
 * advertising power, measured through the DEBUG `adv_hold` line.
 *
 * The decision is a pure function so the transport only executes its answer.
 */
internal object CallReadyAdvertisingHold {
    enum class Action {
        /** Nothing: the server is closed, or a link is up and the set is not running. */
        IGNORE,

        /** Look again after the next interval: a link is up or a verification code holds the boost (as before the switch). */
        RETRY_LATER,

        /** Keep the set as it is: no step, no new operation. */
        HOLD,

        /** The ordinary balanced step. */
        STEP,
    }

    fun decide(
        enabled: Boolean,
        callEndpoint: Boolean,
        closed: Boolean,
        advertising: Boolean,
        linkActive: Boolean,
        boostHolding: Boolean,
    ): Action = when {
        closed -> Action.IGNORE
        // A set held through an admission can outlive the link: try the step again later (today's behaviour).
        linkActive -> if (advertising) Action.RETRY_LATER else Action.IGNORE
        boostHolding -> Action.RETRY_LATER
        enabled && callEndpoint -> Action.HOLD
        else -> Action.STEP
    }

    /**
     * The BLE control listener's balanced step (the LE CoC call endpoint): only a closed server or a live verification
     * code ([AdvertisingBoost]) changes it; the idle hold above is a GATT-set measure and does not apply here.
     */
    fun decideControlListener(closed: Boolean, boostHolding: Boolean): Action = when {
        closed -> Action.IGNORE
        boostHolding -> Action.RETRY_LATER
        else -> Action.STEP
    }

    /** How often the DEBUG `adv_hold` line repeats while a set is held (battery measurement). */
    const val LOG_INTERVAL_MS = 60_000L

    fun line(heldS: Long, elapsedMs: Long): String =
        "BENCHMARK adv_hold: role=call | held_s=$heldS | elapsedMs=$elapsedMs"
}
