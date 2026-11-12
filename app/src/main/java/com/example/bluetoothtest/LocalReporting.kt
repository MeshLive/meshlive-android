package com.example.bluetoothtest

import android.content.Context

/** Local moderation only. The callbacks cannot submit a report or open a URL. */
internal object LocalReportPolicy {
    fun report(block: () -> BlockResult, hide: () -> Boolean): Boolean = when (block()) {
        BlockResult.BLOCKED, BlockResult.ALREADY_BLOCKED -> hide()
        else -> false
    }

    fun hidden(identity: String, fingerprints: Set<String>): Boolean = identity.isNotEmpty() &&
        fingerprints.any { it.startsWith(identity.lowercase()) && identity.length in setOf(12, NodeId.BYTE_COUNT * 2, IdentityFingerprint.BYTE_COUNT * 2) }

    /** A stream route: its logical token and the advertised ID of that route, never the token alone (as [PersonPolicy]). */
    fun routeKey(offer: DiscoveryAnnouncement): String =
        offer.logicalStreamToken().toString() + ":" + offer.nodeIdPrefix.joinToString("") { "%02x".format(it) }

    /**
     * AB3: the route "Hide this livestream" may keep, or null. A route's token and advertised ID are chosen by its sender;
     * only a fixed ID that this viewer's hop handshake proved (the receiver refuses a link whose authenticated ID is not
     * the advertised fixed one) names that sender. An unproven ID could be a copy of someone else's, and a rotating ID can
     * be replayed and changes every epoch.
     */
    fun keptRoute(offer: DiscoveryAnnouncement, hopAuthenticated: Boolean): String? =
        if (hopAuthenticated && !offer.rotating) routeKey(offer) else null

    /** A Watch row stays hidden when its route was kept, or its radio [address] was hidden for this run ([addressHidden]). */
    fun streamHidden(
        offer: DiscoveryAnnouncement,
        address: String?,
        keptRoutes: Set<String>,
        addressHidden: (String) -> Boolean,
    ): Boolean = routeKey(offer) in keptRoutes || (address != null && addressHidden(address))
}

/**
 * Sealed at rest (audit 2026-11-10 SEC-05: earlier builds kept both lists in the plain app settings preferences; they are
 * moved once into [SealedRecords] files under the verified-contacts Keystore key and then deleted from the preferences;
 * the emergency wipe deletes the files and the key). A report keeps hiding the person even if the block is later
 * removed: their attributed direct route ([prefixes]) and any route whose source authenticates as them ([hidden]; the
 * viewer then leaves and that route is hidden for this run, [PersonPolicy.hideStream]). AB3 (the rule the Watch list
 * applies to blocks): a report keeps no stream token or route, and "Hide this livestream" keeps a route only when the
 * viewer's hop proved its fixed ID ([LocalReportPolicy.keptRoute]); otherwise only that advertiser's radio address is
 * hidden, until the app restarts ([PersonPolicy.hideAddress]). Bare tokens older builds kept are not read (a copied token
 * hid every route of it) and are deleted at the next write. Nothing is sent.
 */
internal object LocalReporting {
    private const val PEOPLE = "reported_people"
    /** Bare stream tokens older builds kept (a report's watched stream, "Hide this livestream"): never read, deleted on write. */
    private const val LEGACY_STREAMS = "reported_streams"
    /** Routes kept by "Hide this livestream" ([LocalReportPolicy.keptRoute]). */
    private const val ROUTES = "hidden_stream_routes"
    /** Both lists in one sealed file: `person:<fingerprint hex>` and `route:<route key>`. */
    internal const val FILE = "reported-people.bin"
    private const val PERSON = "person:"
    private const val ROUTE = "route:"

    /** Explicit choices: never evicted (review of 040a8c2e, finding 2); kept until the emergency wipe. */
    private val lists = SealedRecordsSlot(FILE, maxEntries = null) { app -> plainLists(app) }

    /** Opens the lists at process start, so an earlier build's plain copy is moved even if Report is never used. */
    fun open(context: Context) {
        lists.get(context)
    }

    /** False until the sealed lists are read: a hidden stream may still show (re-review S3; reports also block). */
    fun isLoaded(context: Context): Boolean = lists.get(context).isLoaded()

    /** Runs [action] once the lists are read (at once if they are; else on the sealed-memory worker). */
    fun whenLoaded(context: Context, action: () -> Unit) = lists.get(context).whenLoaded(action)

    /**
     * The plain lists of an earlier build in the app settings preferences. They lose them only once the sealed copy is
     * on the disk (a user's choice is not dropped because the Keystore was busy), and the worker confirms the file on
     * the disk no longer names them.
     */
    private fun plainLists(app: Context) = object : LegacyPlain {
        override fun read(): Map<String, SealedRecords.Record> {
            val prefs = app.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            fun plain(key: String, namespace: String) =
                prefs.getStringSet(key, emptySet()).orEmpty().associate { namespace + it to SealedRecords.Record("", now) }
            return plain(PEOPLE, PERSON) + plain(ROUTES, ROUTE)
        }

        override fun clean() =
            PlainPreferences.removeKeys(app, CallFeatureDefaults.APP_SETTINGS_PREFERENCES) { it in PLAIN_KEYS }

        override val cache = false
    }

    private val PLAIN_KEYS = setOf(PEOPLE, ROUTES, LEGACY_STREAMS)

    private fun people(context: Context): Set<String> =
        lists.get(context).keys().filter { it.startsWith(PERSON) }.map { it.removePrefix(PERSON) }.toSet()

    private fun routes(context: Context): Set<String> =
        lists.get(context).keys().filter { it.startsWith(ROUTE) }.map { it.removePrefix(ROUTE) }.toSet()

    fun hidden(context: Context, identity: String): Boolean = DebugTunables.localReporting &&
        LocalReportPolicy.hidden(identity, people(context))

    fun prefixes(context: Context): Set<String> = if (!DebugTunables.localReporting) emptySet() else
        people(context).map { it.take(12) }.toSet()

    /** True when the Watch list hides [offer] heard from the radio [address]. */
    fun hiddenStream(context: Context, offer: DiscoveryAnnouncement, address: String?): Boolean = DebugTunables.localReporting &&
        LocalReportPolicy.streamHidden(offer, address, routes(context), PersonPolicy::addressHidden)

    /**
     * "Hide this livestream" for a source that did not authenticate itself, heard from the radio [address];
     * [hopAuthenticated]: this viewer's hop handshake passed. True when the route was kept; false when only [address] was
     * hidden for this run, or nothing could be written. Nobody is blocked.
     */
    @Synchronized
    fun hideStream(context: Context, offer: DiscoveryAnnouncement, address: String?, hopAuthenticated: Boolean): Boolean {
        if (WipeGuard.active) return false // review 2026-10-27 (finding 15): nothing is written once a wipe started
        val route = LocalReportPolicy.keptRoute(offer, hopAuthenticated)
        if (route == null) {
            address?.let(PersonPolicy::hideAddress)
            return false
        }
        return lists.get(context).put(ROUTE + route, "")
    }

    @Synchronized
    fun report(context: Context, fingerprint: IdentityFingerprint): Boolean =
        !WipeGuard.active && LocalReportPolicy.report({ BlockedPeople.block(context, fingerprint) }) {
            lists.get(context).put(PERSON + fingerprint.hex, "")
        }
}
