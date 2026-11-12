package com.example.bluetoothtest

/**
 * Relayed calls: the call offer on air carries the helper flag and the consent bit from when it opened. When the
 * live helper or consent state differs, an idle endpoint is rebuilt so callers see the truth (RelayedCallsController).
 *
 * The restart guard (DEBUG `test_relayed_restart_guard=false` is the control arm, [DebugTunables.relayedRestartGuard]):
 * the remembered offer is forgotten only when the restart really happened, so a restart refused because the endpoint
 * is busy (an accepted link, a relay duty, an exchange) is asked again at the next tick instead of being lost. Off
 * forgets it before asking, as before.
 */
internal class RelayedOfferTracker(private val guardOn: () -> Boolean = { DebugTunables.relayedRestartGuard }) {
    /** The bits of the call offer currently on air (helping, consent); null when none is known. */
    @Volatile private var advertised: Pair<Boolean, Boolean>? = null

    /** The endpoint opened (or reopened) its offer with these bits. */
    fun opened(helping: Boolean, consent: Boolean) {
        advertised = helping to consent
    }

    /**
     * One tick: when an offer is on air, the endpoint is call-ready and no relay runs, compares the offer with the live
     * bits ([liveHelping] is evaluated only then) and asks [restart] (true when the endpoint was really stopped for a
     * rebuild). Returns whether a restart was asked.
     */
    fun check(
        callReady: Boolean,
        relaying: Boolean,
        liveHelping: () -> Boolean,
        consent: () -> Boolean,
        restart: () -> Boolean,
    ): Boolean {
        val onAir = advertised ?: return false
        if (!callReady || relaying) return false
        if (onAir.first == liveHelping() && onAir.second == consent()) return false
        if (!guardOn()) {
            advertised = null
            restart()
            return true
        }
        // A new endpoint opens its offer after the restart delay; never forget an offer it already reported.
        if (restart() && advertised === onAir) advertised = null
        return true
    }
}

/** Whether the idle call endpoint may be rebuilt for a stale relayed-calls offer (MainActivity.restartIdleCallEndpointForRelayedCalls). */
internal object RelayedEndpointRestart {
    /**
     * [endpointIdle]: no call ringing or connected and not on the call screen. [acceptedLink]: the endpoint holds an
     * incoming link that has not signalled a call yet (a relay's hop or a caller still setting up); rebuilding the
     * endpoint would close it under them. With the guard off such a link counted as idle, as before.
     */
    fun allowed(
        endpointIdle: Boolean,
        relaying: Boolean,
        messageSync: Boolean,
        acceptedLink: Boolean,
        guardOn: Boolean = DebugTunables.relayedRestartGuard,
    ): Boolean = endpointIdle && !relaying && !messageSync && !(guardOn && acceptedLink)
}
