package com.example.bluetoothtest

import android.util.Log

/**
 * Security review SEC-B3 (found in the SEC-B2 review): a caller that selected a direct call offer (routeCost 0) must reach
 * the callee itself. For a non-pseudonymous version 2 offer the only identity check used to be the callee's end-to-end
 * prefix against the offer, so a phone advertising the callee's prefix at cost 0 could authenticate its own links and
 * relay the real callee's end-to-end handshake: the caller then accepted an undeclared link-layer relay as a direct route
 * (hop-local controls and link capacity; media and signalling stay end-to-end encrypted). A direct call therefore
 * requires the full authenticated end-to-end node to be the full authenticated link node, before any reader is installed
 * or any hop control is acted on, exactly as the pseudonymous branch (one throwaway at both layers) and the version 5
 * branch ([AwaitedOpening]) already do.
 *
 * A relayed offer (routeCost > 0) is not bound: its first hop is a helper by design and keeps its own consent gates.
 * Windows and Apple peers use one identity at both layers, so legitimate direct calls pass by construction.
 */
object DirectCallPeerBinding {
    enum class Result { BOUND, RELAYED, MISMATCH }

    fun check(routeCost: Int, link: NodeId, endpoint: NodeId): Result = when {
        routeCost > 0 -> Result.RELAYED
        endpoint == link -> Result.BOUND
        else -> Result.MISMATCH
    }

    /** Fails the call setup closed (an [IllegalArgumentException] with [message]) when a direct call's peers differ; DEBUG builds log it. */
    fun requireBound(
        selected: DiscoveryAnnouncement,
        link: NodeId,
        endpoint: NodeId,
        message: String,
        log: (String) -> Unit = ::logLine,
    ) {
        val result = check(selected.routeCost, link, endpoint)
        if (BuildConfig.DEBUG && result == Result.MISMATCH) {
            log("BENCHMARK call_peer_binding: result=mismatch | version=${if (selected.rotating) 5 else 2} | route=direct")
        }
        require(result != Result.MISMATCH) { message }
    }

    private fun logLine(line: String) {
        Log.i("BluetoothVideo", line)
    }
}
