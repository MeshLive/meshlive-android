package com.example.bluetoothtest

/** The three Wi-Fi Settings switches (`wifi_carrier`, `wifi_wider_reach`, `wifi_direct`), each already combined with its dependsOn. */
internal data class WifiSwitches(val carrier: Boolean, val widerReach: Boolean, val direct: Boolean)

/**
 * Everything [WifiEligibility.decide] looks at. A snapshot taken by the caller; this file reads no clock and no Android
 * state. [otherAwareClientAttached] is null below API 31 or when it could not be read. The three `debug*` fields and [derivedPort] are filled
 * only by `WifiDebugArms.adjust` (A4) from the DEBUG launch extras; a release build never sets them, so [derivedPort] keeps its
 * default (on). `nowMs` and
 * `localFaultPauseUntilMs` are `SystemClock.elapsedRealtime()` milliseconds. [R S4, M-7]
 */
internal data class WifiFacts(
    val api: Int,
    val featureAware: Boolean,
    val featureDirect: Boolean,
    val awareAvailable: Boolean,
    val otherAwareClientAttached: Boolean?,
    /** Hardware supports Aware instant communication mode; the reader sets it false below API 33. */
    val awareIcmSupported: Boolean,
    /**
     * True while an Aware attempt or carrier of this app holds the attach. The Wider reach discovery-plane hold does NOT count
     * (a Direct attempt releases it first with `WifiRadio.pauseDiscoveryLease("direct")`). [R M-10]
     */
    val awareAttachHeldByThisApp: Boolean,
    val wifiEnabled: Boolean,
    /** Frequency of this phone's own Wi-Fi client connection, null when it is not connected. */
    val staFrequencyMhz: Int?,
    val locationEnabled: Boolean,
    val permissionNearby: Boolean,
    val permissionFineLocation: Boolean,
    val switches: WifiSwitches,
    val batteryPercent: Int,
    val charging: Boolean,
    val batterySaver: Boolean,
    /** `PowerManager` thermal status (0 none .. 3 severe .. 6 shutdown). */
    val thermalStatus: Int,
    val familyPolicyAwareOff: Boolean,
    val familyPolicyDirectOff: Boolean,
    val localFaultPauseUntilMs: Long,
    val nowMs: Long,
    val directGroupActive: Boolean,
    val hotspotActive: Boolean,
    val freeNdp: Int,
    /** DEBUG `test_wifi_allow_24ghz`. */
    val debugAllow24Ghz: Boolean = false,
    /** DEBUG `test_wifi_share_attach`. */
    val debugShareAttach: Boolean = false,
    /** DEBUG `test_wifi_icm`. [R S4, M-8] */
    val debugIcm: Boolean = false,
    /**
     * PROTOCOL `port` (promoted 2026-11-10, W5): the Aware host's port is derived from UW and the ticket, and a guest whose platform
     * reports none connects to it. On by default and in every release build; only a DEBUG `test_wifi_aware_derived_port=false`
     * launch turns it off (the control arm), through `WifiDebugArms.adjust`.
     */
    val derivedPort: Boolean = true,
    /**
     * AP10 (Experimental, off by default; DEBUG `test_aware_24ghz_alone`): a 2.4 GHz-only Aware data path, and an Aware
     * attempt beside a 2.4 GHz client connection, are accepted when the phone carries no other Bluetooth media link.
     * Filled only by `WifiDebugArms.adjust`; a release build never sets it.
     */
    val aware24GhzAlone: Boolean = false,
    /**
     * AP10: this phone carries Bluetooth media on a link other than the hop being decided
     * (`WifiHopPorts.otherBluetoothMedia()`). Only the hop agent fills it, for its own hop; every other reader keeps
     * true, which is the 5 GHz rule.
     */
    val otherBluetoothMedia: Boolean = true,
    /**
     * AP10 (Experimental, off by default; DEBUG `test_aware_goodput_guard`): the Aware goodput guard returns a low hop
     * to Bluetooth. Off = the guard measures and logs only. Filled only by `WifiDebugArms.adjust`.
     */
    val awareGoodputGuardActs: Boolean = false,
)

internal object WifiEligibility {
    /** The `DECLINE` reasons of PROTOCOL (6 is for the attempt owner). */
    enum class DeclineReason(val wire: Int) {
        NOT_ELIGIBLE(1),
        BUSY(2),
        WIFI_UNAVAILABLE(3),
        LIMIT_OR_COOLDOWN(4),
        BATTERY_OR_THERMAL(5),
        SETUP_FAILED(6),
        NOT_5GHZ(7),
    }

    /**
     * [acceptsUnverifiedBand] is this phone's Wider reach switch, used only locally (the band tolerance of its own data path);
     * it is never sent. [R S1]
     */
    data class Decision(
        val aware: Boolean,
        val awareIcm: Boolean,
        val directGo: Boolean,
        val directClient: Boolean,
        val acceptsUnverifiedBand: Boolean,
        val reason: DeclineReason?,
    )

    fun decide(f: WifiFacts): Decision {
        val awareReason = awareFailure(f)
        val aware = awareReason == null
        val direct = directAllowed(f)
        return Decision(
            aware = aware,
            awareIcm = aware && f.awareIcmSupported && f.debugIcm, // [R S4, M-8]
            directGo = direct,
            directClient = direct,
            acceptsUnverifiedBand = f.switches.widerReach,
            reason = awareReason,
        )
    }

    private fun awareFailure(f: WifiFacts): DeclineReason? {
        // rule 1
        if (!f.switches.carrier) return DeclineReason.NOT_ELIGIBLE
        if (f.api < 30) return DeclineReason.NOT_ELIGIBLE
        if (f.api >= 33) {
            if (!f.permissionNearby) return DeclineReason.NOT_ELIGIBLE
        } else if (!(f.switches.widerReach && f.permissionFineLocation && f.locationEnabled)) {
            return DeclineReason.NOT_ELIGIBLE
        }
        // rule 2
        if (!f.featureAware) return DeclineReason.NOT_ELIGIBLE
        if (!f.awareAvailable || !f.wifiEnabled) return DeclineReason.WIFI_UNAVAILABLE
        // rule 3: only at level Private; Wider reach holds the attach itself
        if (!f.switches.widerReach && f.api >= 31 && !f.debugShareAttach && f.otherAwareClientAttached != false) {
            return DeclineReason.BUSY
        }
        // rule 4. AP10: with the 2.4 GHz-alone switch on, a hop whose phone carries no other Bluetooth media link may
        // attempt Aware beside a 2.4 GHz client connection (Aware only: Direct keeps its own 5 GHz group request).
        val sta = f.staFrequencyMhz
        val alone24 = f.aware24GhzAlone && !f.otherBluetoothMedia
        if (!f.debugAllow24Ghz && !alone24 && sta != null && sta < WifiLimits.MIN_5GHZ_MHZ) return DeclineReason.NOT_5GHZ
        // rule 5
        if (f.familyPolicyAwareOff) return DeclineReason.NOT_ELIGIBLE
        // rule 6
        if (batteryOrThermalBlocks(f)) return DeclineReason.BATTERY_OR_THERMAL
        if (f.hotspotActive) return DeclineReason.BUSY
        if (f.directGroupActive) return DeclineReason.BUSY
        if (f.nowMs < f.localFaultPauseUntilMs) return DeclineReason.LIMIT_OR_COOLDOWN
        if (f.freeNdp <= 0) return DeclineReason.BUSY
        return null
    }

    private fun batteryOrThermalBlocks(f: WifiFacts): Boolean =
        f.batterySaver || (f.batteryPercent < WifiLimits.MIN_BATTERY_PERCENT && !f.charging) || f.thermalStatus >= WifiLimits.THERMAL_SEVERE

    private fun directAllowed(f: WifiFacts): Boolean {
        if (!f.switches.direct || !f.featureDirect || !f.wifiEnabled || f.api < 29) return false
        val permitted = if (f.api >= 33) f.permissionNearby else f.permissionFineLocation && f.locationEnabled
        if (!permitted) return false
        if (f.familyPolicyDirectOff || f.hotspotActive || f.awareAttachHeldByThisApp || f.directGroupActive) return false
        if (batteryOrThermalBlocks(f)) return false
        return f.nowMs >= f.localFaultPauseUntilMs
    }
}
