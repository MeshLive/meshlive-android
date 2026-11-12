package com.example.bluetoothtest

import java.io.File

/**
 * The benchmark phones as DeviceTraits: aliases from tools/benchmark_devices.example.json, the capability
 * file each alias uses in tools/benchmark_scope.json (manufacturer, model, API, build fingerprint
 * brand, encoder component names), plus the platform properties capability files do not carry.
 */
internal object KnownDeviceTraits {
    /**
     * ro.hardware / ro.board.platform / ro.soc.* as the runner's inventory recorded them. Build.BOARD
     * (ro.product.board) is not recorded; ro.board.platform stands in for it. Only the qcom phone's
     * board changes a matcher result, and its legacy entries are observed active in its runs (ASUS
     * 640x480 calls negotiated to 426x240: benchmarks/runs/media-workflow-7396b46e-call).
     */
    data class Platform(
        val model: String,
        val hardware: String,
        val board: String,
        val socManufacturer: String,
        val socModel: String,
        val provenance: String,
    )

    val platforms: Map<String, Platform> = mapOf(
        "s24" to Platform("SM-S921B", "s5e9945", "erd9945", "Samsung", "s5e9945",
            "benchmarks/runs/20261002-194829-samsung-classic500-parent-loss/manifest.json"),
        "s22" to Platform("SM-S901E", "qcom", "taro", "QTI", "SM8450",
            "benchmarks/runs/20261002-194829-samsung-classic500-parent-loss/manifest.json"),
        "m31" to Platform("SM-M315F", "exynos9611", "universal9611", "Samsung", "Exynos 9611",
            "benchmarks/runs/20261002-204037-asus-m31-gatt900/manifest.json"),
        "a55" to Platform("SM-A556E", "s5e8845", "erd8845", "Samsung", "s5e8845",
            "benchmarks/runs/20261002-194829-samsung-classic500-parent-loss/manifest.json"),
        "asus" to Platform("ASUS_I01WD", "qcom", "msmnile", "", "",
            "benchmarks/runs/20261002-204037-asus-m31-gatt900/manifest.json"),
        "a15s" to Platform("CPH2179", "mt6765", "mt6765", "", "",
            "benchmarks/runs/a15s-opusfix-5a2d93a7/0001-4b5ecaf7784b/physical/manifest.json"),
        "xiaomi" to Platform("23053RN02I", "mt6768", "mt6768", "Mediatek", "MT6769V/CZ",
            "benchmarks/runs/20261002-205648-asus-xiaomi-gatt500/manifest.json"),
        // Realme RMX5256 (the sixth bench phone); traits are the app's own `BENCHMARK device_policy` line from the launch log
        // (an interop run's launch log, 2026-11-07).
        "realme" to Platform("RMX5256", "mt6835", "k6835v2_64", "Mediatek", "MT6835",
            "benchmarks/catalog/capabilities/realme-20261109.json"),
    )

    data class Known(val alias: String, val traits: DeviceTraits, val model: String, val capabilityFile: String)

    @Suppress("UNCHECKED_CAST")
    fun load(root: File = projectRoot()): List<Known> {
        val devices = (TestJson.parseFile(File(root, "tools/benchmark_devices.example.json")) as Map<String, Any?>)["devices"]
            as Map<String, Any?>
        val capabilities = (TestJson.parseFile(File(root, "tools/benchmark_scope.json")) as Map<String, Any?>)["capabilities"]
            as Map<String, String>
        return devices.keys.sorted().map { alias ->
            val path = requireNotNull(capabilities[alias]) { "No capability file for $alias in benchmark_scope.json" }
            val capability = TestJson.parseFile(File(root, path)) as Map<String, Any?>
            val platform = requireNotNull(platforms[alias]) { "No platform properties recorded for $alias" }
            val model = capability["model"] as String
            require(model == platform.model) { "$alias capability model $model is not ${platform.model}" }
            val encoders = (capability["encoders"] as List<Map<String, Any?>>).map { it["name"] as String }
            Known(
                alias = alias,
                model = model,
                capabilityFile = path,
                traits = DeviceTraits(
                    hardware = platform.hardware,
                    board = platform.board,
                    sdkInt = (capability["api"] as Number).toInt(),
                    manufacturer = capability["manufacturer"] as String,
                    brand = (capability["fingerprint"] as String).substringBefore('/'),
                    socManufacturer = platform.socManufacturer,
                    socModel = platform.socModel,
                    codecComponents = encoders.filter { it.contains('.') },
                ),
            )
        }
    }
}
