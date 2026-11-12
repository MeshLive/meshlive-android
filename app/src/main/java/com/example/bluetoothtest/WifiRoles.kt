package com.example.bluetoothtest

/** The bearer of a Wi-Fi attempt (wire values of `HOST.bearer`). [CarrierKind] of the machine is a later package. */
internal enum class WifiBearer(val wire: Int) {
    AWARE(WifiWire.BEARER_AWARE),
    DIRECT(WifiWire.BEARER_DIRECT),
    ;

    companion object {
        fun fromWire(value: Int): WifiBearer? = entries.firstOrNull { it.wire == value }
    }
}

/** What this phone does in the attempt: [localIsHost] true = host (listens), false = guest. */
internal data class RoleTuple(val bearer: WifiBearer, val localIsHost: Boolean)

internal object WifiRoles {
    /**
     * PROTOCOL "Bearer and roles". Deterministic from the two `CAPS`, the same answer on both phones:
     * - Wi-Fi Aware if both set bit 0: host = link responder when the responder's `freeHost` and the initiator's `freeGuest` are
     *   both nonzero; else host = link initiator when the initiator's `freeHost` and the responder's `freeGuest` are both nonzero;
     *   else no Aware tuple.
     * - otherwise Wi-Fi Direct (the free counts are not used): host = responder when the responder sets bit 2 and the initiator
     *   sets bit 3; else host = initiator when the initiator sets bit 2 and the responder sets bit 3.
     * - otherwise null. A Windows PC sets bit 2 only, so it is always the host. The fourth `CAPS` byte is reserved and never read. [R S1]
     */
    fun choose(local: Bwf.Caps, peer: Bwf.Caps, localIsLinkInitiator: Boolean): RoleTuple? {
        val initiator = if (localIsLinkInitiator) local else peer
        val responder = if (localIsLinkInitiator) peer else local
        if (initiator.awareEligible && responder.awareEligible) {
            val hostIsResponder: Boolean? = when {
                responder.freeHost > 0 && initiator.freeGuest > 0 -> true
                initiator.freeHost > 0 && responder.freeGuest > 0 -> false
                else -> null
            }
            if (hostIsResponder != null) return RoleTuple(WifiBearer.AWARE, hostIsResponder != localIsLinkInitiator)
        }
        val directHostIsResponder: Boolean? = when {
            responder.directGroupOwner && initiator.directClient -> true
            initiator.directGroupOwner && responder.directClient -> false
            else -> null
        }
        if (directHostIsResponder != null) return RoleTuple(WifiBearer.DIRECT, directHostIsResponder != localIsLinkInitiator)
        return null
    }

    /**
     * The `CAPS` this phone sends. A phone with nothing eligible sends all bits clear (it still sends one). [freeHost] and
     * [freeGuest] are clamped to 0..7 and are 0 unless Aware is eligible. The fourth byte is reserved
     * and always 0. [R S1]
     */
    fun capsFor(decision: WifiEligibility.Decision, freeHost: Int, freeGuest: Int): Bwf.Caps {
        var bearers = 0
        if (decision.aware) bearers = bearers or Bwf.Caps.BEARER_AWARE
        if (decision.aware && decision.awareIcm) bearers = bearers or Bwf.Caps.BEARER_AWARE_ICM
        if (decision.directGo) bearers = bearers or Bwf.Caps.BEARER_DIRECT_GO
        if (decision.directClient) bearers = bearers or Bwf.Caps.BEARER_DIRECT_CLIENT
        val limit = WifiLimits.MAX_FREE_PATHS_IN_CAPS
        return Bwf.Caps(
            bearers = bearers,
            freeHost = if (decision.aware) freeHost.coerceIn(0, limit) else 0,
            freeGuest = if (decision.aware) freeGuest.coerceIn(0, limit) else 0,
        )
    }
}
