package com.example.bluetoothtest

/**
 * The shipped defaults of the connectivity features promoted on 2026-10-11 (SWITCHES register,
 * "Experimental, alternative and optional behaviour"). Each feature has exactly one constant here.
 * A feature stays OFF until the phone test named beside it passes; turning it on for users who never
 * touched its switch is then a one-line change. ON today: BMH3 and BMH3_PSEUDONYMS (0.2.0-beta, user
 * decision 2026-10-12, after T14/T15 passed) and IPHONE_DISCOVERY (2026-10-17). A user's explicit choice always wins, and
 * a DEBUG benchmark launch (any `test_` extra) never reads these constants (see [FeatureCatalog]).
 */
internal object ConnectivityFeatureDefaults {
    /** LE CoC to GATT safety net on the A15s class of stack (T6: CoC exhaustion, automatic GATT). */
    const val COC_AUTO_GATT = false

    /**
     * Sub-switch of [COC_AUTO_GATT] for streams (Design SG, Q50): receiver restart on GATT and the source's separate
     * GATT child pool. Experimental, off; it does nothing while [COC_AUTO_GATT] is off.
     */
    const val COC_AUTO_GATT_STREAMS = false

    /** Classic RFCOMM without Android pairing, both phones (T7). */
    const val CLASSIC_WITHOUT_PAIRING = false

    /** Compact (single-UUID) announcements of iPhones in discovery (T8: masked scan filter on the six phones). */
    const val IPHONE_DISCOVERY = true // on since 2026-10-17: first iPhone calls and streams passed both ways (design decision)

    /** Experimental "Find PCs": the Windows PC's service-data announcement in discovery (Design QW; extended-advert scan). Off. */
    const val FIND_PCS = false

    /** Help > Share app nearby over Wi-Fi Direct (T9). Flipping it makes the Help entry a normal one. */
    const val SHARE_APP_NEARBY = false

    /** Native libopus call audio: VOIP mode, in-band FEC, PLC (T10: A/B against MediaCodec). */
    const val NATIVE_OPUS = false

    /** Dual listening: a CoC call endpoint also answers over BLE GATT and says so in one announcement bit (T12). */
    const val DUAL_LISTENING = false

    /** Hidden identities: the BMH3 handshake hides both phones' identity keys from a sniffer (T14). On since 0.2.0-beta. */
    const val BMH3 = true

    /** Pseudonymous stranger identities on top of BMH3 and rotating IDs (T15). On since 0.2.0-beta. */
    const val BMH3_PSEUDONYMS = true

    /**
     * HEVC video, Design HC master `hevc_codec` ([VideoCodecFeatures]). On since 0.4.2-beta (design decision 2026-10-28,
     * after the codec smoke and heat runs); a benchmark launch still needs `test_hevc_codec=true`.
     */
    const val HEVC_CODEC = true

    /**
     * Hardware H.264 video, Design HC master `h264_codec` ([VideoCodecFeatures]). On since 0.4.2-beta (design decision
     * 2026-10-28); a benchmark launch still needs `test_h264_codec=true`.
     */
    const val H264_CODEC = true

    /**
     * DECISIONS "Design VX": Automatic may pick the bundled libvpx VP9 encoder, after every hardware AV1, HEVC and VP9
     * encoder and before hardware H.264 and libaom (calls and streams).
     * On since 0.4.2-beta (design decision 2026-10-28, DECISIONS "Automatic codec order after the 2026-10-28 phone
     * measurements"). DEBUG `test_prefer_libvpx` decides per launch; a benchmark launch without it keeps libvpx manual.
     */
    const val LIBVPX_AUTOMATIC = true

    /**
     * Switch `codec_power_aware` ([CodecPowerAware]): with battery saver on or a warm phone at session start, Automatic
     * prefers a hardware encoder over a software one. On since 0.4.2-beta (design decision 2026-10-28); DEBUG control arm
     * `test_codec_power_aware=false`; a benchmark launch without the extra keeps it off.
     */
    const val CODEC_POWER_AWARE = true

    /** Design WF (docs/dev/decisions/README.md "Design WF"): calls and streams move to Wi-Fi Aware after they connect. Off until measured (Design WF decision D5). */
    const val WIFI_CARRIER = false

    /** Design WF: Wi-Fi Aware discovery and Wi-Fi-native hops; API 30-32 Aware. Off until measured. */
    const val WIFI_WIDER_REACH = false

    /** Design WF: Wi-Fi Direct carrier for phones and PCs without Wi-Fi Aware. Off until measured. */
    const val WIFI_DIRECT = false
}

/**
 * Specs of the connectivity features in Settings > Features. The old Advanced preference keys and
 * `test_*` extras are kept, so a stored choice of an earlier build still counts and benchmark cases
 * keep their exact switches.
 */
internal object ConnectivityFeatures {
    const val GROUP = "connectivity"

    val cocAutoGatt = FeatureSpec(
        id = "coc_auto_gatt",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.COC_AUTO_GATT,
        benchmarkExtra = CocAutoGattSettings.EXTRA_ENABLED,
        group = GROUP,
        phoneTest = "T6",
    )

    val classicWithoutPairing = FeatureSpec(
        id = "classic_without_pairing",
        prefKey = ClassicFeatureSettings.PREF_BOND_FREE,
        defaultEnabled = ConnectivityFeatureDefaults.CLASSIC_WITHOUT_PAIRING,
        benchmarkExtra = ClassicFeatureSettings.EXTRA_BOND_FREE,
        titleRes = R.string.feat_classic_bond_free,
        descriptionRes = R.string.feat_classic_bond_free_description,
        iconRes = R.drawable.ic_bluetooth,
        group = GROUP,
        phoneTest = "T7",
    )

    val iphoneDiscovery = FeatureSpec(
        id = "iphone_discovery",
        prefKey = CompactDiscoveryFeature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.IPHONE_DISCOVERY,
        benchmarkExtra = CompactDiscoveryFeature.EXTRA,
        titleRes = R.string.feat_iphone_discovery,
        descriptionRes = R.string.feat_iphone_discovery_description,
        iconRes = R.drawable.ic_visibility,
        group = GROUP,
        phoneTest = "T8",
    )

    /** Experimental (Design QW). Needs rotating IDs at run time: the mapped announcement is a rotating call offer. */
    val findPcs = FeatureSpec(
        id = "find_pcs",
        prefKey = PcAnnouncementFeature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.FIND_PCS,
        benchmarkExtra = PcAnnouncementFeature.EXTRA,
        titleRes = R.string.feat_find_pcs,
        descriptionRes = R.string.feat_find_pcs_description,
        iconRes = R.drawable.ic_visibility,
        group = GROUP,
        dependsOn = listOf(RotatingDiscoveryFeature.spec.id),
        phoneTest = "QW",
    )

    val pcStreams = FeatureSpec(
        id = "pc_streams", prefKey = PcStreamsFeature.PREF, defaultEnabled = false,
        benchmarkExtra = PcStreamsFeature.EXTRA, titleRes = R.string.feat_pc_streams,
        descriptionRes = R.string.feat_pc_streams_description, iconRes = R.drawable.ic_visibility,
        group = GROUP, dependsOn = listOf("find_pcs"), phoneTest = "QV",
    )

    val shareAppNearby = FeatureSpec(
        id = "share_app_nearby",
        prefKey = OfflineAppShareFeature.PREF,
        defaultEnabled = ConnectivityFeatureDefaults.SHARE_APP_NEARBY,
        benchmarkExtra = OfflineAppShareFeature.EXTRA,
        titleRes = R.string.feat_share_app_nearby,
        descriptionRes = R.string.feat_share_app_nearby_description,
        iconRes = R.drawable.ic_group,
        group = GROUP,
        phoneTest = "T9",
    )

    val nativeOpus = FeatureSpec(
        id = "native_opus",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.NATIVE_OPUS,
        benchmarkExtra = NativeOpusSettings.EXTRA_ENABLED,
        group = GROUP,
        phoneTest = "T10",
    )

    val dualListening = FeatureSpec(
        id = "dual_listening",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.DUAL_LISTENING,
        benchmarkExtra = DualListeningFeature.EXTRA_ENABLED,
        group = GROUP,
        phoneTest = "T12",
    )

    val bmh3 = FeatureSpec(
        id = "bmh3",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.BMH3,
        benchmarkExtra = Bmh3Feature.EXTRA,
        group = GROUP,
        phoneTest = "T14",
    )

    /** Needs BMH3 and rotating IDs (it is greyed out and off while "Hidden identities" or rotating IDs is off). */
    val bmh3Pseudonyms = FeatureSpec(
        id = "bmh3_pseudonyms",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = ConnectivityFeatureDefaults.BMH3_PSEUDONYMS,
        benchmarkExtra = Bmh3PseudonymFeature.EXTRA,
        group = GROUP,
        dependsOn = listOf("bmh3", RotatingDiscoveryFeature.spec.id),
        phoneTest = "T15",
    )

    val wifiCarrier = FeatureSpec(
        id = WifiFeatures.ID_CARRIER,
        prefKey = WifiFeatures.PREF_CARRIER,
        defaultEnabled = ConnectivityFeatureDefaults.WIFI_CARRIER,
        benchmarkExtra = WifiFeatures.EXTRA_CARRIER,
        titleRes = R.string.feat_wifi_carrier,
        descriptionRes = R.string.feat_wifi_carrier_description,
        iconRes = R.drawable.ic_wifi,
        group = GROUP,
        phoneTest = "WF",
        extraInAnyDebugLaunch = true,
    )

    /** Sub-switch of [wifiCarrier]: greyed out and off while the carrier switch is off. */
    val wifiWiderReach = FeatureSpec(
        id = WifiFeatures.ID_WIDER_REACH,
        prefKey = WifiFeatures.PREF_WIDER_REACH,
        defaultEnabled = ConnectivityFeatureDefaults.WIFI_WIDER_REACH,
        benchmarkExtra = WifiFeatures.EXTRA_WIDER_REACH,
        titleRes = R.string.feat_wifi_wider_reach,
        descriptionRes = R.string.feat_wifi_wider_reach_description,
        iconRes = R.drawable.ic_wifi,
        group = GROUP,
        dependsOn = listOf(WifiFeatures.ID_CARRIER),
        phoneTest = "WF",
        extraInAnyDebugLaunch = true,
    )

    val wifiDirect = FeatureSpec(
        id = WifiFeatures.ID_DIRECT,
        prefKey = WifiFeatures.PREF_DIRECT,
        defaultEnabled = ConnectivityFeatureDefaults.WIFI_DIRECT,
        benchmarkExtra = WifiFeatures.EXTRA_DIRECT,
        titleRes = R.string.feat_wifi_direct,
        descriptionRes = R.string.feat_wifi_direct_description_unnamed,
        iconRes = R.drawable.ic_wifi,
        group = GROUP,
        dependsOn = listOf(WifiFeatures.ID_CARRIER),
        phoneTest = "WF",
        extraInAnyDebugLaunch = true,
    )

    /** The Wi-Fi card, in its Settings order. Parents first (the catalog registers dependencies first). */
    val wifi: List<FeatureSpec> = listOf(wifiCarrier, wifiWiderReach, wifiDirect)

    /** In Settings order. */
    val all: List<FeatureSpec> = listOf(
        classicWithoutPairing, cocAutoGatt, dualListening, iphoneDiscovery, findPcs, pcStreams, nativeOpus, shareAppNearby, bmh3, bmh3Pseudonyms,
        wifiCarrier, wifiWiderReach, wifiDirect,
    )

    /** Idempotent: an activity can be recreated. Rotating IDs first: `find_pcs` and `bmh3_pseudonyms` depend on it. */
    fun registerAll() = (listOf(RotatingDiscoveryFeature.spec) + all).forEach(FeatureCatalog::register)
}
