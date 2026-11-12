package com.example.bluetoothtest

import androidx.annotation.StringRes

/**
 * Why a call that is on Bluetooth is not on Wi-Fi, in the words Link health shows after "Wi-Fi not used:" (UI-W7, owner
 * decision 2026-11-10). One value per plain sentence; [res] is its string. The reason comes from the app's own eligibility
 * verdict ([WifiEligibility.decide], read as [WifiFacts]) and from what the other phone told this hop ([WifiPeerView]). It is
 * read-only display: nothing here is sent, logged or used to decide anything.
 */
internal enum class WifiNotUsedReason(@StringRes val res: Int) {
    SWITCHED_OFF(R.string.wifi_not_used_switched_off),
    WIDER_REACH_OFF(R.string.wifi_not_used_wider_reach_off),
    WIFI_OFF(R.string.wifi_not_used_wifi_off),
    HOTSPOT(R.string.wifi_not_used_hotspot),
    BATTERY_SAVER(R.string.wifi_not_used_battery_saver),
    BATTERY_LOW(R.string.wifi_not_used_battery_low),
    HEAT(R.string.wifi_not_used_heat),
    NETWORK_24GHZ(R.string.wifi_not_used_network_24ghz),
    NEEDS_PERMISSION(R.string.wifi_not_used_needs_permission),
    NEEDS_LOCATION(R.string.wifi_not_used_needs_location),
    THIS_PHONE_UNSUPPORTED(R.string.wifi_not_used_this_phone_unsupported),
    BUSY(R.string.wifi_not_used_busy),
    RECENT_PROBLEM(R.string.wifi_not_used_recent_problem),
    OTHER_NO_WIFI(R.string.wifi_not_used_other_no_wifi),
    OTHER_BUSY(R.string.wifi_not_used_other_busy),
    OTHER_WIFI_OFF(R.string.wifi_not_used_other_wifi_off),
    OTHER_BATTERY_OR_HEAT(R.string.wifi_not_used_other_battery_or_heat),
    OTHER_NETWORK_24GHZ(R.string.wifi_not_used_other_network_24ghz),
    OTHER_DECLINED(R.string.wifi_not_used_other_declined),
    NO_COMMON_WAY(R.string.wifi_not_used_no_common_way),
    DID_NOT_HOLD(R.string.wifi_not_used_did_not_hold),
}

/**
 * What the other phone said to this call's Wi-Fi hop: [peerBearers] is its CAPS bearer bits (0 = it offers no Wi-Fi way; null =
 * CAPS not exchanged yet), [peerDeclineWire] the code of its latest DECLINE (PROTOCOL; [WifiEligibility.DeclineReason.wire]),
 * [stoppedBecause] why this hop stopped trying (the planner's reason such as `failed_twice`), null while it still tries.
 */
internal data class WifiPeerView(
    val peerBearers: Int? = null,
    val peerDeclineWire: Int? = null,
    val stoppedBecause: String? = null,
)

internal object WifiNotUsed {
    /**
     * The reason this call is not on Wi-Fi, or null when nothing is known to stand in the way (a call that has only just
     * started, or one that is about to move). Local reasons come first, in the order a person can fix them: the switch, then
     * Wi-Fi itself, then what this phone is doing (hotspot, battery, heat, a 2.4 GHz network), then permissions, then the
     * other phone. [decision] is the verdict of [WifiEligibility.decide] for [facts].
     */
    fun reason(facts: WifiFacts, decision: WifiEligibility.Decision, peer: WifiPeerView?): WifiNotUsedReason? {
        if (!decision.aware && !decision.directGo) return localReason(facts)
        return peer?.let(::peerReason)
    }

    private fun localReason(f: WifiFacts): WifiNotUsedReason? = when {
        !f.switches.carrier -> WifiNotUsedReason.SWITCHED_OFF
        !f.wifiEnabled -> WifiNotUsedReason.WIFI_OFF
        f.hotspotActive -> WifiNotUsedReason.HOTSPOT
        f.batterySaver -> WifiNotUsedReason.BATTERY_SAVER
        f.batteryPercent < WifiLimits.MIN_BATTERY_PERCENT && !f.charging -> WifiNotUsedReason.BATTERY_LOW
        f.thermalStatus >= WifiLimits.THERMAL_SEVERE -> WifiNotUsedReason.HEAT
        f.staFrequencyMhz?.let { it < WifiLimits.MIN_5GHZ_MHZ } == true && !f.debugAllow24Ghz && !f.switches.direct -> WifiNotUsedReason.NETWORK_24GHZ
        missingPermission(f) -> WifiNotUsedReason.NEEDS_PERMISSION
        f.api < 33 && needsLocation(f) && !f.locationEnabled -> WifiNotUsedReason.NEEDS_LOCATION
        f.api in 30..32 && !f.switches.widerReach && !f.switches.direct -> WifiNotUsedReason.WIDER_REACH_OFF
        (!f.featureAware && !f.featureDirect) || (f.familyPolicyAwareOff && f.familyPolicyDirectOff) -> WifiNotUsedReason.THIS_PHONE_UNSUPPORTED
        f.nowMs < f.localFaultPauseUntilMs -> WifiNotUsedReason.RECENT_PROBLEM
        f.directGroupActive || f.freeNdp <= 0 || !f.awareAvailable || f.otherAwareClientAttached == true -> WifiNotUsedReason.BUSY
        else -> null
    }

    private fun missingPermission(f: WifiFacts): Boolean =
        if (f.api >= 33) !f.permissionNearby else !f.permissionFineLocation

    private fun needsLocation(f: WifiFacts): Boolean = f.switches.widerReach || f.switches.direct

    private fun peerReason(peer: WifiPeerView): WifiNotUsedReason? {
        peer.peerDeclineWire?.let { wire ->
            return when (wire) {
                WifiEligibility.DeclineReason.BUSY.wire -> WifiNotUsedReason.OTHER_BUSY
                WifiEligibility.DeclineReason.WIFI_UNAVAILABLE.wire -> WifiNotUsedReason.OTHER_WIFI_OFF
                WifiEligibility.DeclineReason.BATTERY_OR_THERMAL.wire -> WifiNotUsedReason.OTHER_BATTERY_OR_HEAT
                WifiEligibility.DeclineReason.NOT_5GHZ.wire -> WifiNotUsedReason.OTHER_NETWORK_24GHZ
                WifiEligibility.DeclineReason.SETUP_FAILED.wire, WifiEligibility.DeclineReason.LIMIT_OR_COOLDOWN.wire -> WifiNotUsedReason.DID_NOT_HOLD
                else -> WifiNotUsedReason.OTHER_DECLINED
            }
        }
        if (peer.peerBearers == 0) return WifiNotUsedReason.OTHER_NO_WIFI
        return when (peer.stoppedBecause) {
            null, "switch_off", "call_ended" -> null
            "no_tuple" -> WifiNotUsedReason.NO_COMMON_WAY
            else -> WifiNotUsedReason.DID_NOT_HOLD // failed_twice, declined_thrice, fallbacks, spoof_cap
        }
    }
}
