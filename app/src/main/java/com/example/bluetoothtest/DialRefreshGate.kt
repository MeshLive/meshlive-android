package com.example.bluetoothtest

/**
 * Security review SEC-B3 (reviewer finding 1): the address refresh behind a user's tap follows the dialled phone to its newest
 * advertisement. That refresh must keep what the user selected. A caller who selected a direct offer (route cost 0) is
 * never moved onto a relayed offer (cost above 0) of the same prefix: a relayed call needs the caller's own relayed-call
 * choice, made on a relayed row, not a silent replacement by whoever advertises that prefix. Without this a phone could
 * answer the refresh with a cost 1 copy of the callee's advert and take the relayed-call exemption of
 * [DirectCallPeerBinding]. A refresh that is not accepted leaves the dial waiting for a matching sighting.
 */
internal object DialRefreshGate {
    /** Whether [candidate] may replace the offer selected with [selectedVersion] and [selectedRouteCost]. */
    fun accepts(selectedVersion: Int, selectedRouteCost: Int, candidate: DiscoveryAnnouncement): Boolean =
        candidate.version == selectedVersion && (selectedRouteCost > 0 || candidate.routeCost == 0)
}
