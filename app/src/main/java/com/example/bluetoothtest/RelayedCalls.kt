package com.example.bluetoothtest

/**
 * Experimental relayed private calls and multi-hop discovery (off by default; DEBUG
 * `test_relayed_calls`, Advanced setting "Experimental: relayed calls" otherwise). DECISIONS "Design C"
 * owns the design and PROTOCOL "Experimental relayed calls and multi-hop discovery" the wire
 * rules; this file holds the switches, constants and the pure capability/offer helpers.
 *
 * Off: every byte on air and on the link is unchanged (no consent bit, no relay flag, no reach
 * page, no link-hello bit; hop type 12 is rejected as unknown by every reader).
 */
object RelayedCallsFeature {
    const val EXTRA = "test_relayed_calls"
    const val PREF = "experimental_relayed_calls"

    /** Sub-switches, meaningful only while the experiment is on; both preselected on (design decision 2 and 3). */
    const val PREF_HELP = "relayed_calls_help"
    const val PREF_CONSENT = "relayed_calls_consent"
    /** The turn-on dialog (which preselects both sub-switches) was shown once. */
    const val PREF_PROMPTED = "relayed_calls_prompted"

    /** DEBUG launch extras: the sub-switches, the cost cap (1..4) and the benchmark chain requirement. */
    const val EXTRA_HELP = "test_relayed_calls_help"
    const val EXTRA_CONSENT = "test_relayed_calls_consent"
    const val EXTRA_MAX_RELAYS = "test_relayed_calls_max_relays"
    const val EXTRA_REQUIRE = "test_relayed_calls_require"

    /**
     * AP8 DEBUG benchmark pins (default off, null): the caller uses only a route whose first relay has
     * [EXTRA_HELPER_PIN]'s signed-node prefix; a helper accepts only [EXTRA_NEXT_HOP_PIN]'s next relay or target
     * prefix. Exactly 12 hex digits; absent = off, malformed = a pin that matches no route.
     */
    const val EXTRA_HELPER_PIN = "test_relayed_calls_helper"
    const val EXTRA_NEXT_HOP_PIN = "test_relayed_calls_next_hop"

    /**
     * Experimental DEBUG topology mask, default off (SWITCHES register): `test_relayed_calls_no_direct=true` makes this
     * phone's reach tables ignore every target's own offer (see [ReachTable]), as if no target were in its radio
     * range. A co-located lab needs it for any chain of two or more relays: the caller and every helper except
     * the last mark themselves blind, the last helper stays normal and dials the target.
     */
    const val EXTRA_NO_DIRECT = "test_relayed_calls_no_direct"
    @Volatile var noDirectSightings = false

    /**
     * Which routed people the Nearby list shows (design decision 2026-10-26). Default: only saved contacts reached through
     * other phones; a stranger several phones away is never listed, and a Contacts call searches every route on tap.
     * `test_relayed_list_strangers=true` (DEBUG) is the earlier arm that lists everyone reachable through helpers.
     */
    const val EXTRA_LIST_STRANGERS = "test_relayed_list_strangers"
    @Volatile var listRoutedStrangers = false

    /** Whether the Nearby list shows a routed row ([isContact] = the row is a saved contact). Direct rows always show. */
    fun showsRoutedRow(isContact: Boolean): Boolean = isContact || listRoutedStrangers

    /**
     * Resolved by the shared rule ([FeatureCatalog.resolve]). Design decision 2026-11-06 (Apple gap audit 2, Q2): the switch
     * is an Experiments row shown with or without Manual video settings, so its stored choice counts either way (until then
     * it was an Advanced-only row, DECISIONS "Known interactions", B14). Default off.
     */
    val spec = FeatureSpec(
        id = "relayed_calls",
        prefKey = PREF,
        defaultEnabled = false,
        benchmarkExtra = EXTRA,
        group = ConnectivityFeatures.GROUP,
        phoneTest = "Design C",
    )

    @Volatile var enabled = false

    /** This phone may relay other people's calls (sub-switch "Help calls nearby"). */
    @Volatile var helpEnabled = true

    /** This phone's call offer tells others they may relay calls to it ("Let phones nearby relay calls to me"). */
    @Volatile var consentEnabled = true

    /** Relays the table accepts and a caller asks for (design decision 6: 2; the chain limit is 4). */
    @Volatile var maxRelays = RelayedCalls.DEFAULT_MAX_RELAYS

    /** Benchmark rule: only a chain of exactly this many relays counts (0 = normal use). Direct fallback fails. */
    @Volatile var requireRelays = 0

    /**
     * AP8 DEBUG benchmark pin: the caller uses only a route whose first relay has this signed-node prefix. Null = off;
     * an empty array is a rejected (malformed) pin and matches no route.
     */
    @Volatile var helperPin: ByteArray? = null

    /**
     * AP8 DEBUG benchmark pin: a helper accepts only this next relay or target prefix; anything else is the existing
     * NO_ROUTE FAIL. Null = off; an empty array is a rejected (malformed) pin and matches nothing.
     */
    @Volatile var nextHopPin: ByteArray? = null

    /** The cost cap from a DEBUG launch extra; the product value everywhere else. */
    fun resolveMaxRelays(benchmarkLaunch: Boolean, extraValue: Int): Int =
        if (benchmarkLaunch && extraValue in 1..PrivateCallRelayChain.MAX_RELAYS) extraValue else RelayedCalls.DEFAULT_MAX_RELAYS

    /** The lab route controls one launch sets ([resolveRouteControls]); all off by default. */
    class RouteControls(
        val requireRelays: Int = 0,
        val noDirectSightings: Boolean = false,
        val helperPin: ByteArray? = null,
        val nextHopPin: ByteArray? = null,
    )

    /**
     * The lab route controls [EXTRA_REQUIRE], [EXTRA_NO_DIRECT], [EXTRA_HELPER_PIN] and [EXTRA_NEXT_HOP_PIN] are test
     * geometry, not product features: like the Wi-Fi extras ([FeatureSpec.extraInAnyDebugLaunch]) a [debug] build reads
     * them in every launch, a person's launch (`test_user_launch=true`) included, so co-located phones can be made to
     * use a relayed chain (design decision on the UL1 proposal, 2026-11-10). A release build never reads them, and an
     * absent extra leaves its control off.
     */
    fun resolveRouteControls(
        debug: Boolean,
        int: (String, Int) -> Int,
        flag: (String, Boolean) -> Boolean,
        string: (String) -> String?,
    ): RouteControls = if (!debug) {
        RouteControls()
    } else {
        RouteControls(
            requireRelays = int(EXTRA_REQUIRE, 0).coerceIn(0, PrivateCallRelayChain.MAX_RELAYS),
            noDirectSightings = flag(EXTRA_NO_DIRECT, false),
            helperPin = parsePin(string(EXTRA_HELPER_PIN)),
            nextHopPin = parsePin(string(EXTRA_NEXT_HOP_PIN)),
        )
    }

    /** AP8: the pin exists only in a DEBUG benchmark launch; a release or ordinary launch always resolves to null. */
    fun resolvePin(benchmarkLaunch: Boolean, raw: String?): ByteArray? =
        if (benchmarkLaunch) parsePin(raw) else null

    /**
     * AP8: a 12-hex-digit node prefix as its 6 bytes. Null = the extra is absent (no pin, today's choice); a malformed
     * value is an empty array, a pin that matches no route, so a typo cannot silently select another helper.
     */
    fun parsePin(raw: String?): ByteArray? {
        if (raw == null) return null
        val hex = raw.takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
            ?: return ByteArray(0)
        return ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES) { index ->
            hex.substring(2 * index, 2 * index + 2).toInt(16).toByte()
        }
    }

    /** The launch line's pin label: `none` (off), `invalid` (rejected), or the lowercase 12-hex prefix. */
    fun pinLabel(pin: ByteArray?): String = when {
        pin == null -> "none"
        pin.isEmpty() -> "invalid"
        else -> pin.joinToString("") { "%02x".format(it) }
    }

    /**
     * Rotating IDs and relayed calls together (PLAN C4, reach pages version 6): helping and consent use role-0 RDIs
     * only, so a phone with rotating IDs on may relay. DEBUG `test_relayed_rotating=false` restores the earlier
     * exclusion (a phone that uses rotating IDs then neither helps nor is relayed to).
     */
    @Volatile var rotatingCompat = true

    const val EXTRA_ROTATING_COMPAT = "test_relayed_rotating"

    /** This phone uses the rotating (version 5 / version 6) population of the relayed-call tables. */
    fun rotatingActive(rotatingIdsOn: Boolean): Boolean = enabled && rotatingIdsOn && rotatingCompat

    fun helperAllowed(rotatingIdsOn: Boolean): Boolean = enabled && helpEnabled && (!rotatingIdsOn || rotatingCompat)

    fun consentAllowed(rotatingIdsOn: Boolean): Boolean = enabled && consentEnabled && (!rotatingIdsOn || rotatingCompat)
}

object RelayedCalls {
    const val DEFAULT_MAX_RELAYS = 2

    /** Call-offer `capabilitySummary` bit 4: the target may be found and called through other phones. */
    const val CONSENT_BIT = 1 shl 4

    /** Link-hello transport-mask bit 4: "understands RELAY_SETUP (hop type 12)". */
    const val LINK_HELLO_SETUP_BIT = 1 shl 4

    /** Overall setup deadline from the caller's first SETUP, and what each hop keeps back for itself. */
    const val SETUP_DEADLINE_MS = 20_000
    const val MIN_REMAINING_MS = 2_000
    const val PER_HOP_RESERVE_MS = 1_000

    /** A helper closes an accepted link that has not finished the handshake and delivered its first record by then. */
    const val PRE_SETUP_WAIT_MS = 20_000L

    /** A relayed incoming call that rings this long unanswered is declined by the callee (PLAN C3). */
    const val RELAYED_RINGING_CAP_MS = 60_000L

    /**
     * Review A4 (2026-10-27): the cap never fires before the callee's own ring timeout ([CallRingTimeout.CALLEE_MS], 65 s).
     * At 60 s it declined before the caller's 60 s "No answer" (which starts later, at the RINGING the relay delivered), so
     * the caller saw "Call declined" and this phone recorded nothing missed.
     */
    fun relayedRingingCapMs(): Long = maxOf(RELAYED_RINGING_CAP_MS, CallRingTimeout.CALLEE_MS)

    /** A connectable offer older than this is not used to open a link (it may have a new address). */
    const val OFFER_FRESH_MS = 30_000L

    /** Candidate chains a caller tries, and the window they must fit in. */
    const val MAX_CANDIDATES = 3
    const val CANDIDATE_WINDOW_MS = 60_000L

    /**
     * Consent for relayed discovery as seen in a (decoded) call offer. [rotating] selects the population: a
     * version 5 offer counts only for the rotating table, a version 2 offer only for the fixed-prefix one.
     */
    fun consents(offer: DiscoveryAnnouncement, rotating: Boolean = false): Boolean =
        offer.isPrivateCallOffer() && offer.rotating == rotating && offer.routeCost == 0 &&
            offer.capabilitySummary and CONSENT_BIT != 0

    /**
     * DeviceFamilyPolicy `mediatek-call-relay-gatt-helper-off` (off with `test_device_policy_off=<id>`): this phone takes
     * helper duty only over LE CoC, so over [transport] GATT it neither offers nor accepts it.
     */
    internal fun gattHelperDutyBlocked(
        transport: BluetoothTransportOption?,
        policy: () -> ResolvedDevicePolicy = { DeviceFamilyPolicy.current() },
    ): Boolean = transport == BluetoothTransportOption.BLE_GATT && policy().has<CallRelayHelperCocOnly>()

    /** True for a connectable idle helper offer: callers may open its listener and send SETUP. */
    fun isRelayHelperOffer(offer: DiscoveryAnnouncement, rotating: Boolean = false): Boolean =
        offer.isPrivateCallOffer() && offer.rotating == rotating && offer.routeCost == 0 &&
            offer.flags and DiscoveryAnnouncement.FLAG_RELAY_CAPABLE != 0 &&
            offer.flags and DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS != 0

    /** The own cost-0 call offer's bits for this phone's current relayed-call state (identity when off). */
    fun offerFor(
        base: DiscoveryAnnouncement,
        consentOn: Boolean,
        helperActive: Boolean,
        /** A version 5 offer carries the bits only with rotating + relayed calls both on (RelayedCallsFeature.rotatingCompat). */
        rotatingCompat: Boolean = false,
    ): DiscoveryAnnouncement {
        if ((base.rotating && !rotatingCompat) || base.routeCost != 0 || !base.isPrivateCallOffer()) return base
        if (!consentOn && !helperActive) return base
        return base.copy(
            capabilitySummary = if (consentOn) base.capabilitySummary or CONSENT_BIT else base.capabilitySummary,
            flags = if (helperActive) base.flags or DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else base.flags,
        )
    }

    /**
     * The link hello this phone signs. Feature off: [base] unchanged. On: bit 4 (understands SETUP). An
     * active helper additionally signs the call-relay bit and relay capacity, the existing marks that a
     * phone relays calls ([PrivateCallRelayChain.childProblem]); a paused or busy phone signs neither.
     */
    fun linkCapabilities(base: NodeCapabilities, featureOn: Boolean, helperActive: Boolean): NodeCapabilities {
        if (!featureOn) return base
        var mask = base.transportMask or LINK_HELLO_SETUP_BIT
        var children = base.maxChildren
        if (helperActive) {
            mask = mask or PrivateCallRelayChain.CALL_RELAY_CAPABILITY
            children = maxOf(children, 1)
        }
        return base.copy(transportMask = mask, maxChildren = children)
    }

    /** The helper-only link-hello evidence a caller requires before it sends SETUP. */
    fun linkAcceptsSetup(peer: NodeCapabilities): Boolean =
        peer.transportMask and LINK_HELLO_SETUP_BIT != 0 &&
            peer.transportMask and PrivateCallRelayChain.CALL_RELAY_CAPABILITY != 0 &&
            peer.maxChildren >= 1

    /** The caller's own hello must show bit 4 before a helper reads SETUP. */
    fun linkUnderstandsSetup(peer: NodeCapabilities): Boolean = peer.transportMask and LINK_HELLO_SETUP_BIT != 0

    /** Remaining time passed to the next hop; null when too little is left to try. */
    fun nextHopRemainingMs(remainingMs: Int, elapsedMs: Long): Int? {
        val left = remainingMs - elapsedMs - PER_HOP_RESERVE_MS
        return if (left >= MIN_REMAINING_MS) left.toInt() else null
    }

    /**
     * The offer a hop is checked against when it answers with a real key: [helperOfferFor] for a rotating route, a version 2
     * call offer with the fixed prefix otherwise.
     */
    fun hopOfferFor(prefix: ByteArray, rotating: Boolean): DiscoveryAnnouncement =
        if (rotating) helperOfferFor(prefix) else helperOfferFor(prefix).copy(version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION)

    /** A minimal version 5 helper offer carrying [rdi]: what a rotating hop's opening is checked against. */
    fun helperOfferFor(rdi: ByteArray): DiscoveryAnnouncement = DiscoveryAnnouncement(
        nodeIdPrefix = rdi.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
        version = DiscoveryAnnouncement.ROTATING_VERSION,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
            DiscoveryAnnouncement.FLAG_RELAY_CAPABLE,
        capabilitySummary = 0,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
    )

    /**
     * The announcement a caller (or a helper's next hop check) uses for a relayed route: the target's
     * prefix at the route cost, with the first hop's transport and the connectable flags, exactly like
     * a DEBUG relay's mirrored offer ([PrivateCallRelayChain.parentOffer]).
     */
    fun routedAnnouncement(target: ByteArray, cost: Int, hopOffer: DiscoveryAnnouncement): DiscoveryAnnouncement =
        DiscoveryAnnouncement(
            nodeIdPrefix = target.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
            version = hopOffer.version,
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or
                DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
                (if (cost > 0) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0) or
                (hopOffer.flags and (DiscoveryAnnouncement.FLAG_GATT_TRANSPORT or DiscoveryAnnouncement.FLAG_CLASSIC_TRANSPORT)),
            // The target's codecs are not advertised along a route (only its fixed prefix is); the
            // end-to-end hello decides ("Peer cannot decode any locally available call codec").
            capabilitySummary = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            streamToken = 0,
            routeCost = cost,
            sequence = 0,
        )
}

/** Lower-case hex of a node prefix (or any short id), the key relayed-call tables use. */
internal fun ByteArray.prefixHex(): String = joinToString("") { "%02x".format(it) }

/**
 * What the relay may see of a call it carries without reading it: record types only. After READY the
 * chain is torn down unless the end-to-end handshake crosses within [HANDSHAKE_MS] and the first
 * end-to-end content record from the callee (its Ringing answer to the Invite, which the caller cannot
 * forge) within [ANSWER_MS]. Heartbeats and caller-originated content prove nothing here: a caller could
 * otherwise hold a helper with liveness or an empty request alone (DECISIONS Design C, C3). A pair of
 * colluding phones can still hold one helper with a real call; that is the feature.
 */
internal class RelayChainGuard(private val readyNs: Long) {
    @Volatile private var handshakeSeen = false
    @Volatile private var answerSeen = false

    fun onRecord(fromCaller: Boolean, type: SecureRecordType) {
        when (type) {
            SecureRecordType.END_TO_END_HANDSHAKE -> handshakeSeen = true
            SecureRecordType.END_TO_END_CONTENT -> if (!fromCaller) answerSeen = true
            else -> Unit
        }
    }

    /** A reason to tear the chain down now, or null. */
    fun violation(nowNs: Long): String? {
        val elapsedMs = (nowNs - readyNs) / 1_000_000L
        return when {
            !handshakeSeen && elapsedMs > HANDSHAKE_MS -> "No end-to-end handshake within ${HANDSHAKE_MS / 1_000} s of the relay being ready"
            !answerSeen && elapsedMs > ANSWER_MS -> "No answer from the callee within ${ANSWER_MS / 1_000} s of the relay being ready"
            else -> null
        }
    }

    /** True once nothing is left to watch. */
    val satisfied: Boolean get() = handshakeSeen && answerSeen

    companion object {
        const val HANDSHAKE_MS = 10_000L
        const val ANSWER_MS = 20_000L
    }
}
