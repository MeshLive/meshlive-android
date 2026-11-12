package com.example.bluetoothtest

/** Wi-Fi Aware is not offered on a matching phone family (e.g. where the census found addresses that do not rotate, or a HAL with no STA+NAN combination). */
internal data object WifiAwareOff : DevicePolicyEffect {
    override fun describe() = "Wi-Fi Aware carrier is not offered on this phone family"
}

/** Wi-Fi Direct is not offered on a matching phone family. */
internal data object WifiDirectOff : DevicePolicyEffect {
    override fun describe() = "Wi-Fi Direct carrier is not offered on this phone family"
}

/** At most [max] simultaneous Wi-Fi Aware data paths (a vendor HAL may expose a single NDI/NDP, platform 2.3). */
internal data class WifiAwareMaxNdp(val max: Int) : DevicePolicyEffect {
    override fun describe() = "at most $max Wi-Fi Aware data path(s)"
}

/**
 * Device-family policy of the Wi-Fi carriers (package A4). The single entry below is a proposal with no effect
 * until the Wi-Fi census confirms the Exynos single-NDP behaviour; every future Aware/Direct off-switch follows the
 * entry format documented in the package (official source plus census run id, `area = PolicyArea.WIFI`).
 */
internal object WifiFamilyPolicy {
    private const val REVIEWED = "2026-11-04"

    val entries: List<DevicePolicyEntry> = listOf(
        DevicePolicyEntry(
            id = "wifi-aware-exynos-one-ndp",
            title = "Samsung Exynos (S.LSI): at most one Wi-Fi Aware data path",
            matcher = DeviceMatcher(socFamilies = setOf(SocFamily.EXYNOS)),
            effects = emptyList(), // PROPOSAL: no effect until the census confirms it
            behaviour = "Would cap simultaneous Wi-Fi Aware data paths at 1 on matching phones. The runtime already reads " +
                "Characteristics and getAvailableAwareResources and learns a lower cap from a local request error, so this entry " +
                "matters only if those over-report.",
            sources = listOf(
                PolicySource(
                    PolicySourceKind.AOSP_SOURCE,
                    "https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/service/java/com/android/server/wifi/aware/WifiAwareDataPathStateManager.java",
                    "one Network per NDI; the NDI and NDP counts come from the vendor HAL capabilities, so a vendor may ship 1",
                ),
            ),
            sourceStatus = PolicySourceStatus.PARTIAL,
            evidence = listOf("none"),
            confidence = PolicyConfidence.LOW,
            reviewed = REVIEWED,
            origin = "new (Wi-Fi carrier plan, package A4)",
            confidenceNote = "The value 1 for Exynos rests on a third-party app's field notes, not on an official source or on our " +
                "phones; the Wi-Fi census (DeviceWifiCapabilitiesTest) decides. Promote with the census run id as evidence.",
            area = PolicyArea.WIFI,
            proposed = true,
            validation = PolicyValidation.EXPERIMENTAL,
            fallback = "free data paths come from the platform and the learned cap",
        ),
    )
}
