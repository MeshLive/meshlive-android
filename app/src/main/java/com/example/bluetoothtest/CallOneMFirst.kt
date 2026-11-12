package com.example.bluetoothtest

/**
 * Experimental `test_call_1m_first` (JT2-P3, default off, [PhySwitches.call1mFirst]): a direct outgoing GATT call opens on
 * LE 1M and asks for 2M only after the call is running and its first remote picture was shown, and only when the call
 * needs it ([CallPhyUpgradeGate]).
 *
 * Why (u6fc-20261110t, A15s -> ASUS user launch, 3 of 6 slow): the link came up on 2M in 1.1-2.2 s, then the end-to-end
 * handshake got no reply on 2M (supervision timeout or the 3 s setup stall), the 2M -> 1M setup retry waited 6.7-8.8 s for
 * the ASUS's new advertising address, and the same call on 1M authenticated in 0.1-0.2 s. 2M was requested at subscribe,
 * before hop authentication, and a rotating ID never matches the 2M memory of a stranger (AdaptivePhyMemory.offerMemoryNode).
 *
 * Scope: the dialing central of a direct GATT call only. Relays, streams, CoC, Classic, Wi-Fi and the callee side are
 * unchanged (CoC has its own switch, below); the hop-local authentication, encryption and identity checks are unchanged (the upgrade is one more
 * `setPreferredPhy` on the live, authenticated link, through the same [LlProcedureGate] lambda streams use). Off: the old
 * path (2M at subscribe, 2M -> 1M setup retry) runs byte for byte.
 *
 * Experimental `test_call_1m_first_coc` ([PhySwitches.call1mFirstCoc], default off): the same start for a direct outgoing
 * BLE L2CAP CoC call. Why (s2m-20261111, A15s -> ASUS user launch on CoC, B14): with the switch off the first 2M link stalled
 * in all 3 calls and the 1M setup retry then failed too (GATT status 133 on the fresh control link, "Timed out reading the
 * receiver's BLE video channel"): 0 of 3 rang; on GATT the 1M-first start had taken the same pair from 4/6 rang in 15-21 s to
 * 6/6 in ~2 s (p3a). The CoC control link asks for 1M after the PSM read (the sequenced request a peer remembered bad on 2M
 * already uses; the control server never asks), and the upgrade after the first picture and the early-loss fallback ask the
 * same live control link through its LL procedure gate. The upgrade budget stays the GATT figure: CoC on 1M carries more,
 * so a CoC call may ask for 2M a little earlier than it needs to, never later.
 */
internal object CallOneMFirst {
    /**
     * What a GATT link on LE 1M carries: "GATT on 1M PHY measured 0.320 Mbps" (LinkSelfMeasurement.kt LinkPacerSeed.priorKbps,
     * LinkCapacity.priorKbps, DECISIONS "Bounded payload"). The per-direction call start budget is 180 kbps video
     * ([privateCallVideoSettings]); a call is planned against this wire figure with the planner's own margin.
     */
    const val ONE_M_GATT_KBPS = 320

    /** A mid-call 2M upgrade that loses the peer within this time falls back to 1M in place. */
    const val EARLY_LOSS_AFTER_UPGRADE_MS = 5_000L

    /** The peer counts as lost after this much silence (heartbeats come about every second). */
    const val SILENCE_MS = 2_000L

    /** Consecutive congested sample windows that count as the rate controller reporting a link limit. */
    const val LIMIT_WINDOWS = 2

    /**
     * Whether this dial starts 1M-first: the switch is on, the link is a direct GATT dial of a person's own offer (no relayed
     * or helper route, not Wi-Fi), the setup would have asked for 2M ([firstPhy]: a peer remembered bad on 2M already starts
     * on 1M and keeps the old path), and this phone's radio has 2M at all.
     */
    fun appliesToDial(
        enabled: Boolean,
        gatt: Boolean,
        dualListening: Boolean,
        direct: Boolean,
        wifiOnly: Boolean,
        relayedRoute: Boolean,
        firstPhy: BlePhyPreference,
        le2MSupported: Boolean,
        cocEnabled: Boolean = false,
        coc: Boolean = false,
    ): Boolean = ((enabled && gatt) || (cocEnabled && coc)) && !dualListening && direct && !wifiOnly && !relayedRoute &&
        firstPhy == BlePhyPreference.TWO_M && le2MSupported

    /**
     * The PHY the setup retry rules see for this dial: 1M for a first-1M dial, so the 2M -> 1M setup retry, the 3 s setup
     * stall guard and the early-loss window bound never apply to it ([adaptivePhySetupRetryEligible] needs a first 2M link).
     */
    fun setupPhy(firstPhy: BlePhyPreference, oneMFirst: Boolean): BlePhyPreference =
        if (oneMFirst) BlePhyPreference.ONE_M_ADAPTIVE else firstPhy

    /** The planned wire load of a call video stream: video, audio and headers with the planner's margin ([MeshBandwidthEstimator]). */
    fun plannedKbps(videoBitRateBps: Int, fps: Int): Double =
        MeshBandwidthEstimator.perLinkKbps(videoBitRateBps / 1_000, fps)

    /** The hop node to remember as bad on 2M after an early loss, or null (no authenticated node, or a hop that relays). */
    fun nodeToRemember(node: String?, hopRelaysSkipsMemory: Boolean): String? =
        node?.takeIf { it.isNotEmpty() && !hopRelaysSkipsMemory }

    /** `BENCHMARK link_phy` for the call link, the same fields as the setup-retry line plus what the decision saw. */
    fun linkPhyLine(requested: String, effective: String, reason: String, node: String, detail: String = ""): String =
        "BENCHMARK link_phy requested=$requested effective=$effective reason=$reason source=experiment link=call node=$node" +
            if (detail.isEmpty()) "" else " $detail"
}

/** Why a first-1M call asked for 2M. */
internal enum class CallUpgradeTrigger(val label: String) {
    /** The call's planned load exceeds what LE 1M carries ([CallOneMFirst.ONE_M_GATT_KBPS]). */
    RATE("rate_above_budget"),

    /** The call's sender reported congested sample windows (backlog) on this link. */
    LINK_LIMIT("link_limit"),
}

/**
 * The decisions of one first-1M call, pure and clock-injected. The call code feeds it: the planned media
 * ([onMediaPlanned], when local media starts), the first shown remote picture ([onPictureShown]), the sender's sample
 * windows ([onLinkWindow]) and peer silence ([onSilence]); it returns what to ask the link for. Nothing here touches a link.
 */
internal class CallPhyUpgradeGate(
    private val budgetKbps: Int = CallOneMFirst.ONE_M_GATT_KBPS,
    /**
     * Experimental `test_call_1m_first_link_limit` ([PhySwitches.call1mFirstLinkLimit], default off): the sender's link limit
     * (two congested windows in a row) also asks for 2M, as the first version did. Off: the link limit holds the call on 1M
     * and the rate controller handles the congestion. Why: in 24 first-1M calls (p3a, p3b1-3, 2026-11-11) the link limit
     * fired only on A15s -> ASUS, the pair whose 2M stalls, and both calls it upgraded died within 5 s of the request
     * ("Call link lost: EOFException"; a 2M link stalled at the link layer cannot carry the in-place move back to 1M).
     * Congestion on 1M is no evidence that 2M works; it fired on no healthy pair.
     */
    private val linkLimitUpgrade: Boolean = false,
) {
    private var plannedKbps = 0.0
    private var heldReported = false
    private var pictureShown = false
    private var limitWindows = 0
    private var limited = false
    private var requestedAtMs = -1L
    private var suppressed = false
    private var fellBack = false

    /** The trigger was handed out: one request per link, however many frames or windows follow. */
    private var asked = false

    /** The call's planned wire load ([CallOneMFirst.plannedKbps]); a later change (a rate cut) is taken as given. */
    @Synchronized
    fun onMediaPlanned(plannedKbps: Double) {
        this.plannedKbps = plannedKbps
    }

    /** The first remote picture was shown: from here the call is running with media; the upgrade may be asked for. */
    @Synchronized
    fun onPictureShown(): CallUpgradeTrigger? {
        pictureShown = true
        return pending()
    }

    /** One sender sample window: [congested] when its backlog counted as congestion (the rate controller's link limit). */
    @Synchronized
    fun onLinkWindow(congested: Boolean): CallUpgradeTrigger? {
        limitWindows = if (congested) limitWindows + 1 else 0
        if (limitWindows >= CallOneMFirst.LIMIT_WINDOWS) limited = true
        return pending()
    }

    /** The upgrade will not be asked for (the peer is remembered bad on 2M, or the link cannot be asked). Late is fine: the trigger already handed out is simply not used. */
    @Synchronized
    fun suppress() {
        suppressed = true
    }

    /** The upgrade was asked for at [nowMs]; one request per link. */
    @Synchronized
    fun markRequested(nowMs: Long) {
        requestedAtMs = nowMs
    }

    @Synchronized
    fun requested(): Boolean = requestedAtMs >= 0

    /** True once: the peer went silent for [CallOneMFirst.SILENCE_MS] within [CallOneMFirst.EARLY_LOSS_AFTER_UPGRADE_MS] of the request. */
    @Synchronized
    fun onSilence(nowMs: Long, silentMs: Long): Boolean {
        if (requestedAtMs < 0 || fellBack) return false
        if (nowMs - requestedAtMs !in 0..CallOneMFirst.EARLY_LOSS_AFTER_UPGRADE_MS) return false
        if (silentMs < CallOneMFirst.SILENCE_MS) return false
        fellBack = true
        return true
    }

    @Synchronized
    private fun pending(): CallUpgradeTrigger? {
        if (!pictureShown || suppressed || asked || fellBack) return null
        val trigger = when {
            plannedKbps > budgetKbps -> CallUpgradeTrigger.RATE
            limited && linkLimitUpgrade -> CallUpgradeTrigger.LINK_LIMIT
            else -> null
        }
        if (trigger != null) asked = true
        return trigger
    }

    /** True once: the sender reported its link limit and the call stays on 1M (the default; see [linkLimitUpgrade]). */
    @Synchronized
    fun takeLinkLimitHeld(): Boolean {
        if (!limited || linkLimitUpgrade || heldReported || !pictureShown || suppressed || asked || fellBack) return false
        heldReported = true
        return true
    }
}
