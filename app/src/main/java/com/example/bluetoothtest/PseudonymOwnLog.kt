package com.example.bluetoothtest

/**
 * DEBUG-only evidence line for the benchmark runner's relayed-call binding: the per-call pseudonym a callee presented to its
 * hop-local link peer, in the same 12-hex form a relay logs its child in "Relaying an endpoint-encrypted private call between
 * <parent> and <child>" (`NodeId.shortLabel()` of the key the child presented, derived by [NodeId.fromPublicKey] on both
 * ends). The caller prints it only under `BuildConfig.DEBUG`; a release build never calls this. No behaviour change.
 */
internal object PseudonymOwnLog {
    /**
     * `BENCHMARK pseudonym_own: role=call | link=<peer's label> | id=<own throwaway's label>`, or null when this hop link did
     * not present a throwaway.
     */
    fun line(link: SecureBluetoothSession): String? {
        val own = link.ownPseudonymNodeId?.takeIf { link.ownPseudonymous } ?: return null
        return "BENCHMARK pseudonym_own: role=call | link=${link.peerNodeId.shortLabel()} | id=${own.shortLabel()}"
    }
}
