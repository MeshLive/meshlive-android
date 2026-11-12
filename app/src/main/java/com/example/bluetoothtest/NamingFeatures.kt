package com.example.bluetoothtest

/**
 * The switches of Naming v2 (Design G; SWITCHES "Naming and identity display"). Each ships behind its own switch;
 * a user who touched a switch keeps the explicit choice, an untouched switch follows the constant below, and a DEBUG
 * benchmark launch (any `test_` extra) reads only its extras, default off, so measured case IDs stay valid
 * ([FeatureCatalog.resolve]). Shipping a feature ON is the one-line change of its constant, made only after the phone
 * check named beside it has passed.
 */
internal object NamingFeatureDefaults {
    /** G2: generated stranger names for rotating-ID rows. Display only, no wire change (phone check N3). Off shows the handle. */
    const val GENERATED_NAMES = true

    /** Public stream title pages (G1 split). On since 2026-10-17, no Settings switch (design decision). */
    const val TITLE_PAGES = true

    /**
     * Experimental: "Show my name before they answer" (design decision G-Q1; the user default from 2026-10-12 to
     * 2026-11-06). Off since the design decision of 2026-11-06 ("A caller's name waits for the reveal"): a stranger this
     * phone calls gets the name only after its verified REVEAL passes the block check ([CallerNameRelease]). On, the name
     * goes before the Invite, so a blocked person behind a fresh throwaway would receive it. Kept as the comparison arm
     * (never delete an experiment); only the DEBUG extra `test_names_before_answer` turns it on. Phone check N2.
     */
    const val NAMES_BEFORE_ANSWER = false

    /**
     * G4: BVMID2 verification codes. On since 2026-10-19 (design decision Q-QL1): N5 and the Design QL lease test passed on two
     * Android pairs, and only a BVMID2 scan moves presence leases, so a contact added with the static BVMID1 code could not be
     * called from Contacts. An explicit stored choice still wins; `bvmid1_compat` stays the compat switch.
     */
    const val BVMID2 = true

    /** G4: "Accept older verification codes" (BVMID1 gives the shield again). Off: the 0.2.0-beta compat decision. */
    const val BVMID1_COMPAT = false

    /** G5: Visible by name (window offer, name pages). Off until phone check N7 passes. */
    const val VISIBLE_BY_NAME = false
}

internal object NamingFeatures {
    const val GROUP = "naming"

    val GENERATED_NAMES = FeatureSpec(
        id = "generated_names",
        // No Settings switch since 2026-11-04 (design decision): off gave no privacy (the name is computed from the public
        // rotating ID) and only showed every stranger, and this phone, as "Nearby phone"; an old stored "off" is ignored.
        prefKey = null,
        defaultEnabled = NamingFeatureDefaults.GENERATED_NAMES,
        benchmarkExtra = "test_generated_names",
        group = GROUP,
        phoneTest = "N3",
    )

    /**
     * "Show my stream title to everyone nearby": the public title pages of a titled stream, split from the in-session
     * names and subjects ([PeerLabelFeature.enabled], Settings "Names and subjects"). The title is a title, not a person.
     */
    val TITLE_PAGES = FeatureSpec(
        id = "stream_title_pages",
        // A constant: its `feature_stream_title_pages_v2` key was written only by a migration whose source key was never
        // written (switch audit 2026-10-27), so no build ever stored a choice.
        prefKey = null,
        defaultEnabled = NamingFeatureDefaults.TITLE_PAGES,
        benchmarkExtra = "test_title_pages",
        group = GROUP,
        phoneTest = "T5",
    )

    /**
     * Experimental, off ([NamingFeatureDefaults.NAMES_BEFORE_ANSWER]): a sub-option of "Names and subjects"
     * ([CallFeature.NAMES_AND_TITLES], id `names_and_titles`). docs/dev/switches.md lists it.
     */
    val NAMES_BEFORE_ANSWER = FeatureSpec(
        id = "names_before_answer",
        // No Settings switch since 2026-11-04 (owner: one name, one "Who sees my name" choice): the audience decides who
        // gets the name; an "off" stored by 0.4.4 or older under NamingSettings.PREF_BEFORE_ANSWER is ignored.
        prefKey = null,
        defaultEnabled = NamingFeatureDefaults.NAMES_BEFORE_ANSWER,
        benchmarkExtra = NamingSettings.EXTRA_BEFORE_ANSWER,
        group = GROUP,
        dependsOn = listOf("names_and_titles"),
        phoneTest = "N2",
    )

    /** Expiring one-use verification codes on the Add contact screen (bound to the endpoint through [MeetBridge]). */
    val BVMID2 = FeatureSpec(
        id = "bvmid2",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = NamingFeatureDefaults.BVMID2,
        benchmarkExtra = "test_bvmid2",
        group = GROUP,
        phoneTest = "N5",
    )

    val BVMID1_COMPAT = FeatureSpec(
        id = "bvmid1_compat",
        // No Settings switch since 2026-11-04 (owner: fewer knobs); a stored choice is ignored, the DEBUG extra still works.
        prefKey = null,
        defaultEnabled = NamingFeatureDefaults.BVMID1_COMPAT,
        benchmarkExtra = "test_bvmid1_compat",
        group = GROUP,
        dependsOn = listOf("bvmid2"),
        phoneTest = "N5",
    )

    val VISIBLE_BY_NAME = FeatureSpec(
        id = "visible_by_name",
        prefKey = "feature_visible_by_name",
        defaultEnabled = NamingFeatureDefaults.VISIBLE_BY_NAME,
        benchmarkExtra = "test_visible_by_name",
        titleRes = R.string.feature_visible_by_name,
        descriptionRes = R.string.feature_visible_by_name_description,
        iconRes = R.drawable.ic_visibility,
        group = GROUP,
        phoneTest = "N7",
    )

    /** Registered in this order: parents first ([FeatureCatalog.register]). `names_and_titles` is registered by [CallFeatureSpecs]. */
    val all: List<FeatureSpec> = listOf(GENERATED_NAMES, TITLE_PAGES, NAMES_BEFORE_ANSWER, BVMID2, BVMID1_COMPAT, VISIBLE_BY_NAME)

    /** The switches that have a Settings row (Settings > Experiments since 2026-11-04). `visible_by_name` ships off. */
    val settingsRows: List<FeatureSpec> = listOf(VISIBLE_BY_NAME)

    /** Idempotent. Needs [CallFeatureSpecs.registerAll] first (the parent of `names_before_answer`). */
    fun registerAll() = all.forEach(FeatureCatalog::register)

    /** Whether generated names are shown now (the last [FeatureCatalog.refresh]). */
    fun generatedNamesOn(): Boolean = FeatureCatalog.isEnabled(GENERATED_NAMES.id)

    fun bvmid2On(): Boolean = FeatureCatalog.isEnabled(BVMID2.id)

    /** Whether version 7 pages and version 8 window offers are read (the last [FeatureCatalog.refresh]). */
    fun visibleByNameOn(): Boolean = FeatureCatalog.isEnabled(VISIBLE_BY_NAME.id)

    fun bvmid1CompatOn(): Boolean = FeatureCatalog.isEnabled(BVMID1_COMPAT.id)

    /**
     * Title pages for this launch. A DEBUG benchmark launch reads its own extra and, for the cases measured before the split,
     * also `test_peer_labels` (which used to switch names, subjects and title pages together).
     */
    fun resolveTitlePages(benchmarkLaunch: Boolean, extra: (String) -> Boolean, stored: (String) -> Boolean?): Boolean =
        FeatureCatalog.resolve(TITLE_PAGES, benchmarkLaunch, extra, stored) ||
            (benchmarkLaunch && extra(PeerLabelFeature.EXTRA))
}
