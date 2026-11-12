package com.example.bluetoothtest

/** Exports DevicePolicyRegistry and the benchmark phones' resolution as tools/device_policy_registry.json. */
internal object DevicePolicyExport {
    fun json(known: List<KnownDeviceTraits.Known>): String = TestJson.write(
        linkedMapOf(
            "schema_version" to 1,
            "revision" to DeviceFamilyPolicy.REVISION,
            "generated_by" to "app/src/test/java/com/example/bluetoothtest/DeviceFamilyPolicyTest.kt " +
                "(run :app:testDebugUnitTest with DEVICE_POLICY_UPDATE=1 to rewrite); source of truth: " +
                "app/src/main/java/com/example/bluetoothtest/DevicePolicyRegistry.kt",
            "off_switch" to "DEBUG launch extra test_device_policy_off=<id>[,<id>...] or all",
            "entries" to DeviceFamilyPolicy.entries.map(::entry),
            "known_devices" to known.map(::device),
        ),
    )

    private fun entry(entry: DevicePolicyEntry): Map<String, Any?> = entryFields(entry).also { fields ->
        // Set only where used, so other entries export exactly as before (tools/device_policy_report.py reads both).
        if (entry.supersededBy != null) fields["superseded_by"] = entry.supersededBy
        if (entry.arm != null) fields["arm"] = entry.arm.switch
        // A promoted arm (its switch defaults on, e.g. test_gatt_native_wedge_guard since 2026-11-10) is in effect unless
        // its control arm turns it off; the report must not list it as waiting for its arm.
        if (entry.arm != null && entry.arm.on()) fields["arm_on_by_default"] = true
    }

    private fun entryFields(entry: DevicePolicyEntry): MutableMap<String, Any?> = linkedMapOf(
        "id" to entry.id,
        "title" to entry.title,
        "status" to if (entry.proposed) "proposed" else "implemented",
        "matcher" to matcher(entry.matcher),
        "matcher_text" to entry.matcherDescription(),
        "component" to entry.component?.let {
            linkedMapOf("names" to it.names.sorted(), "prefixes" to it.prefixes.sorted())
        },
        "effects" to entry.effects.map(DevicePolicyEffect::describe),
        "behaviour" to entry.behaviour,
        "sources" to entry.sources.map {
            linkedMapOf("kind" to it.kind.name, "reference" to it.reference, "note" to it.note)
        },
        "source_status" to entry.sourceStatus.name,
        "area" to entry.area.name,
        "validation" to entry.validation.name,
        "fallback" to entry.fallback,
        "evidence" to entry.evidence,
        "confidence" to entry.confidence.name,
        "confidence_note" to entry.confidenceNote,
        "reviewed" to entry.reviewed,
        "off_switch" to entry.offSwitch,
        "legacy_switches" to entry.legacySwitches,
        "origin" to entry.origin,
    )

    private fun matcher(matcher: DeviceMatcher): Map<String, Any?> = matcherFields(matcher).also { fields ->
        // Newer constraints appear only when set, so entries without them export exactly as before.
        if (matcher.patchMin != null) fields["patch_min"] = matcher.patchMin
        if (matcher.patchMax != null) fields["patch_max"] = matcher.patchMax
        if (matcher.runtime != null) fields["runtime"] = matcher.runtime.describe()
    }

    private fun matcherFields(matcher: DeviceMatcher): MutableMap<String, Any?> = linkedMapOf(
        "sdk_min" to matcher.sdkMin,
        "sdk_max" to matcher.sdkMax,
        "hardware" to matcher.hardware.sorted(),
        "boards" to matcher.boards.sorted(),
        "board_prefixes" to matcher.boardPrefixes.sorted(),
        "soc_families" to matcher.socFamilies.map(SocFamily::name).sorted(),
        "soc_model_prefixes" to matcher.socModelPrefixes.sorted(),
        "manufacturers" to matcher.manufacturers.sorted(),
        "skins" to matcher.skins.map(OemSkin::name).sorted(),
        "stack_lineages" to matcher.stackLineages.map(BluetoothStackLineage::name).sorted(),
        "controller" to matcher.controller?.let {
            linkedMapOf(
                "le_2m_phy" to it.le2mPhy,
                "le_coded_phy" to it.leCodedPhy,
                "le_extended_advertising" to it.leExtendedAdvertising,
            )
        },
        "codec_component_prefixes" to matcher.codecComponentPrefixes.sorted(),
    )

    private fun device(known: KnownDeviceTraits.Known): Map<String, Any?> {
        val traits = known.traits
        val resolved = DeviceFamilyPolicy.resolve(traits, emptySet())
        val platform = KnownDeviceTraits.platforms.getValue(known.alias)
        return linkedMapOf(
            "alias" to known.alias,
            "model" to known.model,
            "capability_file" to known.capabilityFile,
            "platform_provenance" to platform.provenance,
            "traits" to linkedMapOf(
                "hardware" to traits.hardware,
                "board" to traits.board,
                "sdk" to traits.sdkInt,
                "manufacturer" to traits.manufacturer,
                "brand" to traits.brand,
                "soc_manufacturer" to traits.socManufacturer,
                "soc_model" to traits.socModel,
                "soc_family" to traits.socFamily.name,
                "skin" to traits.oemSkin.name,
                "stack_lineage" to traits.stackFamily.name, // umbrella value: tools/device_policy_report.py derives the same two
                "codec_components" to traits.codecComponents,
            ),
            "active" to resolved.reportedActive.filter { it.component == null }.map { it.id },
            "component_rules" to resolved.active.filter { it.component != null }.associate { entry ->
                entry.id to traits.codecComponents.filter { entry.component!!.matches(it) }
            }.filterValues { it.isNotEmpty() },
            "matching_proposals" to resolved.matchingProposals.map { it.id },
        )
    }
}
