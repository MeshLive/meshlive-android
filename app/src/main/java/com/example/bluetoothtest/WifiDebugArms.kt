package com.example.bluetoothtest

/**
 * The DEBUG launch arms of the Wi-Fi carrier. `WifiRadio.facts()` applies this to every snapshot, so no consumer
 * calls it again; `WifiEligibility.decide` stays pure. Release builds never set the override fields. [R M-7]
 */
internal object WifiDebugArms {
    /** DEBUG builds only: fills A0's three override fields from the DEBUG extras. Touches nothing else (in particular NOT staFrequencyMhz / otherAwareClientAttached). */
    fun adjust(facts: WifiFacts): WifiFacts =
        if (!BuildConfig.DEBUG) {
            facts
        } else {
            adjust(
                facts, DebugTunables.wifiAllow24ghz, DebugTunables.wifiShareAttach, DebugTunables.wifiIcm, DebugTunables.wifiAwareDerivedPort,
                aware24Alone = DebugTunables.aware24GhzAlone, goodputGuardActs = DebugTunables.awareGoodputGuard,
            )
        }

    /**
     * [aware24Alone] and [goodputGuardActs] are AP10's two Experimental arms (`test_aware_24ghz_alone`,
     * `test_aware_goodput_guard`), both off by default. `otherBluetoothMedia` is never set here: it is the hop's fact.
     */
    internal fun adjust(
        facts: WifiFacts,
        allow24Ghz: Boolean,
        shareAttach: Boolean,
        icm: Boolean,
        derivedPort: Boolean = true,
        aware24Alone: Boolean = false,
        goodputGuardActs: Boolean = false,
    ): WifiFacts = facts.copy(
        debugAllow24Ghz = allow24Ghz,
        debugShareAttach = shareAttach,
        debugIcm = icm,
        derivedPort = derivedPort,
        aware24GhzAlone = aware24Alone,
        awareGoodputGuardActs = goodputGuardActs,
    )

    /** DEBUG `test_wifi_aware_require_channel`: the 5 GHz channel hint of the Aware data-path request becomes a requirement. Read by AwareDataPath. */
    fun requireChannel(): Boolean = BuildConfig.DEBUG && DebugTunables.wifiAwareRequireChannel

    /** AP9 DEBUG `test_aware_band_strict`: the old all-channels-5GHz Aware band rule (the new default judges by the 5 GHz data channel). */
    fun bandStrict(): Boolean = BuildConfig.DEBUG && DebugTunables.awareBandStrict
}
