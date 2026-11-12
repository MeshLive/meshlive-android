package com.example.bluetoothtest

import android.util.Log

/**
 * Security review SEC-B2: on a live-stream hop the authenticated end-to-end peer must be the authenticated link peer.
 * Without this rule a phone that merely dials a viewer's request (or answers a source's offer) could relay the other
 * side's end-to-end handshake: both identity checks pass, and the relay owns every hop-local control on the hop (Wi-Fi
 * capabilities, route withdrawal, link reports) while the media stays end-to-end encrypted.
 *
 * The rule holds for every transport (GATT, CoC, Classic, Wi-Fi-native), every offer version (2 and 5), and both
 * sides (the source/forwarder that opens the hop, and the viewer that answers). A forwarded stream does not need the
 * exception: a forwarder runs its own end-to-end handshake with each child and hands it the key (ForwardedStreamGrant).
 *
 * The one exception is the DEBUG manual stream relay ([BluetoothVideoRelay], launch extra `test_stream_relay_passthrough`,
 * default off), which passes the end-to-end handshake through and so is a link-layer relay by design. It applies only
 * to a version 2 hop whose link peer signed `maxChildren > 0`, and never in a release build.
 */
object StreamHopPeerBinding {
    enum class Result { BOUND, RELAY_EXEMPT, MISMATCH, GROUP_REFUSED }

    /** The exemption in force for this hop: DEBUG build, switch on, a link peer that signed it relays, a version 2 hop. */
    fun relayExempt(
        linkPeerMaxChildren: Int,
        rotating: Boolean,
        debugRelay: Boolean = BuildConfig.DEBUG && DebugTunables.streamRelayPassthrough,
    ): Boolean = debugRelay && BuildConfig.DEBUG && !rotating && linkPeerMaxChildren > 0

    /** Whether the end-to-end peer [endpoint] is the link peer [link] (or the DEBUG relay exemption applies). */
    fun check(
        link: NodeId,
        endpoint: NodeId,
        linkPeerMaxChildren: Int,
        rotating: Boolean,
        debugRelay: Boolean = BuildConfig.DEBUG && DebugTunables.streamRelayPassthrough,
    ): Result = when {
        endpoint == link -> Result.BOUND
        relayExempt(linkPeerMaxChildren, rotating, debugRelay) -> Result.RELAY_EXEMPT
        else -> Result.MISMATCH
    }

    /**
     * A group (`BGK1`) hop is a manual relay with several children, so a link peer that signs `maxChildren > 1`
     * is refused before any end-to-end handshake unless the DEBUG relay exemption applies (never for a version 5 selection, [rotating]): otherwise any phone
     * could claim it and relay the source's handshake. Fails with an [IllegalArgumentException] like every other join check.
     */
    fun requireGroupAllowed(
        role: String,
        rotating: Boolean,
        linkPeerMaxChildren: Int,
        message: String,
        debugRelay: Boolean = BuildConfig.DEBUG && DebugTunables.streamRelayPassthrough,
        log: (String) -> Unit = ::logLine,
    ) {
        if (linkPeerMaxChildren <= 1) return
        // A group hop is never version 5: the exemption (and the group handshake, which has no endpoint == link check)
        // is refused before any group handshake when the selected offer or request is version 5.
        val allowed = relayExempt(linkPeerMaxChildren, rotating, debugRelay)
        if (BuildConfig.DEBUG) {
            log(
                "BENCHMARK stream_peer_binding: result=${if (allowed) "relay_exempt" else "group_refused"} | role=$role | " +
                    "version=${if (rotating) 5 else 2} | hop=group",
            )
        }
        require(allowed) { message }
    }

    /** Fails the join closed (an [IllegalArgumentException] with [message]) unless [endpoint] is [link]; logs in DEBUG builds. */
    fun requireBound(
        role: String,
        rotating: Boolean,
        link: NodeId,
        endpoint: NodeId,
        linkPeerMaxChildren: Int,
        message: String,
        debugRelay: Boolean = BuildConfig.DEBUG && DebugTunables.streamRelayPassthrough,
        log: (String) -> Unit = ::logLine,
    ) {
        val result = check(link, endpoint, linkPeerMaxChildren, rotating, debugRelay)
        if (BuildConfig.DEBUG && result != Result.BOUND) {
            log(
                "BENCHMARK stream_peer_binding: result=${result.name.lowercase()} | role=$role | " +
                    "version=${if (rotating) 5 else 2}",
            )
        }
        require(result != Result.MISMATCH) { message }
    }

    /**
     * The link peer's authenticated node must carry the prefix of the selected version 2 offer (the viewer side of
     * the same rule; the source side keeps its own prefix check). Skipped only under the DEBUG relay exemption,
     * where the offer belongs to the source behind the relay.
     */
    fun linkMatchesOffer(link: NodeId, offerPrefix: ByteArray, exempt: Boolean): Boolean =
        exempt || link.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(offerPrefix)

    private fun logLine(line: String) {
        Log.i("BluetoothVideo", line)
    }
}
