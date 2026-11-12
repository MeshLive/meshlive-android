package com.example.bluetoothtest

/**
 * Always reachable for calls (2026-10-10, first iPhone -> Android test). Before this the
 * call-availability advertisement and its GATT/CoC listener ran only while the Call (people) screen
 * was open, so on Home `dumpsys bluetooth_manager` showed "Advertisers: []" and no caller could
 * find the phone. On: the private-call endpoint (advertising + listener, no scanning) also runs on
 * Home and Settings-from-Home while the app is visible, and in the background while the session
 * foreground service (connectedDevice type) is held. Off: exactly the old behaviour.
 *
 * Normal launches: on. A DEBUG benchmark launch (any `test_*` extra) requests its role exactly,
 * so it is on only with an explicit `test_always_reachable=true`; existing cases do not change.
 */
object CallReachabilityFeature {
    const val EXTRA = "test_always_reachable"

    /**
     * Normal launches follow the Settings switch "Reachable for calls" ([PREF_REACHABLE_FOR_CALLS], its own row on the main
     * Settings page, default on); [FeatureCatalog.resolve].
     */
    val spec = FeatureSpec(
        id = "call_reachability",
        prefKey = PREF_REACHABLE_FOR_CALLS,
        defaultEnabled = true,
        benchmarkExtra = EXTRA,
        group = CallFeatureSpecs.GROUP,
    )

    @Volatile var enabled = true
}

/**
 * Reachable while browsing (2026-10-12, design decision). The Live tab is now the landing screen and
 * the stream browser: opening it used to close the call endpoint, so a phone sitting on it could
 * not receive calls. On: browsing (scanning for streams only) keeps the call endpoint; it closes
 * when the user joins a stream or goes live and is restored when they leave. Off: exactly the old
 * behaviour (the Live tab owns the radio, the endpoint is closed).
 *
 * Normal launches: on. A DEBUG launch with any `test_*` extra keeps the old behaviour unless it
 * carries `test_reachable_while_browsing=true` (and, as for every endpoint, `test_always_reachable`),
 * so existing benchmark cases do not change. `test_reachable_while_browsing=false` is the control arm.
 */
object ReachableWhileBrowsingFeature {
    const val EXTRA = "test_reachable_while_browsing"

    /** A constant (no Settings row); the extra decides in any DEBUG launch that carries it ([FeatureCatalog.resolve]). */
    val spec = FeatureSpec(
        id = "reachable_while_browsing",
        prefKey = null,
        defaultEnabled = true,
        benchmarkExtra = EXTRA,
        group = CallFeatureSpecs.GROUP,
        extraInAnyDebugLaunch = true,
    )

    @Volatile var enabled = true
}

/** Where the activity is, as far as call reachability cares. */
internal enum class ReachabilityScreen {
    /** Home, or Settings opened from Home. */
    HOME,
    /** The Call (people) screen: advertising + listening + scanning, as before. */
    CALL_BROWSER,
    /** A call is ringing or connected. */
    CALL,
    /** Camera / Go live screen (stream sender or relay): the stream advertiser owns the radio. */
    LIVE,
    /**
     * The Live tab only browsing (the stream scan runs, nothing is on air for a stream): the call
     * endpoint may keep running, as on Home. Becomes [WATCH] once a stream is joined or being rejoined.
     */
    LIVE_BROWSING,
    /** A stream is joined (or being rejoined) or playing: the viewer and its forwarder own the radio. */
    WATCH,
}

/** Which advertising role the phone holds. */
internal enum class AdvertisingRole { NONE, CALL_AVAILABLE, IN_CALL, LIVE_STREAM, WATCHING }

internal data class ReachabilityInputs(
    val featureEnabled: Boolean,
    val screen: ReachabilityScreen,
    /** The activity is started (visible); a foreground service may be started now. */
    val foreground: Boolean,
    /** The session foreground service currently holds the connectedDevice type. */
    val foregroundServiceHeld: Boolean,
    /** Bluetooth on and the call permissions granted. */
    val bluetoothReady: Boolean,
    /** Battery below [LOW_BATTERY_PERCENT]. Informational: never turns reachability off. */
    val batteryLow: Boolean = false,
    /** Battery Saver on. Informational: never turns reachability off. */
    val batterySaver: Boolean = false,
    /** [ReachableWhileBrowsingFeature]: off plans [ReachabilityScreen.LIVE_BROWSING] exactly like [ReachabilityScreen.WATCH]. */
    val reachableWhileBrowsing: Boolean = true,
    /** Wider reach (A8 step 13): Bluetooth is unusable but the endpoint may listen over Wi-Fi only. */
    val wifiNativeReady: Boolean = false,
)

internal data class ReachabilityPlan(
    val role: AdvertisingRole,
    /** The private-call endpoint (call advertisement + GATT/CoC listener) should run. */
    val endpoint: Boolean,
    /** Scan for other phones (the people list). Only the Call screen in the foreground scans. */
    val discoveryScan: Boolean,
    /** This plan (not the outcome) makes the phone callable; the home line also needs success. */
    val reachable: Boolean,
    val reason: String,
) {
    fun logLine(): String = "role=${role.name} | endpoint=$endpoint | scan=$discoveryScan | reachable=$reachable | reason=$reason"
}

/**
 * The advertising state machine. Pure, so every screen/background/battery combination is unit
 * tested. Budget notes:
 * - Advertising sets: Home adds one call-availability set (legacy, as on the Call screen) and
 *   nothing on Live/Watch, where the stream offer and the peer-labels title set (which keeps one
 *   free set of headroom, TitleAdvertiser.MIN_FREE_SETS) need the controller's sets; the endpoint
 *   is closed before those start (showStreamScreen when going live, connectToStream when joining).
 *   Browsing the Live tab (LIVE_BROWSING) only scans: no stream offer, viewer request or title set
 *   is on air yet, so the endpoint stays like on Home (design decision 2026-10-12).
 * - Scan quota (ScanStartGate, 5 starts / 30 s): reachability never scans, so Home and the browsing
 *   Live tab cost no start; the browser's own stream scan is unchanged.
 * - iPhone compact discovery changes only scanning; Android still advertises the ordinary
 *   announcement, which iPhones read.
 * - Battery (design decision 2026-10-10): relay helping pauses below 20 % or in Battery Saver, but
 *   incoming-call reachability is core and stays on.
 * - Android 12+ background: in the background the endpoint runs only while the connectedDevice
 *   foreground service is held (started while visible); without it the process can be frozen, so
 *   the endpoint stops instead of claiming a reachability it cannot deliver.
 */
internal object CallReachability {
    const val LOW_BATTERY_PERCENT = 20

    /**
     * Bluetooth-off reachability over Wider reach (A8 step 13): claimed only when something can really be on air and a
     * call can really arrive: the switch, the endpoint permissions, the adapter off, Wi-Fi Aware eligible now (Wi-Fi on,
     * and Location on for Android 11-12) and native hops possible on this phone (A8 review P2-4, P2-3).
     */
    fun wifiNativeReady(
        widerReach: Boolean,
        bluetoothReady: Boolean,
        permissionsGranted: Boolean,
        adapterOff: Boolean,
        awareEligible: () -> Boolean,
        nativeHopsPossible: Boolean,
    ): Boolean = widerReach && !bluetoothReady && permissionsGranted && adapterOff && nativeHopsPossible && awareEligible()

    fun plan(inputs: ReachabilityInputs): ReachabilityPlan {
        val batteryNote = when {
            inputs.batteryLow && inputs.batterySaver -> " (low battery + saver: calls stay on, relay helping pauses)"
            inputs.batteryLow -> " (low battery: calls stay on, relay helping pauses)"
            inputs.batterySaver -> " (battery saver: calls stay on, relay helping pauses)"
            else -> ""
        }
        return when (inputs.screen) {
            ReachabilityScreen.CALL -> ReachabilityPlan(AdvertisingRole.IN_CALL, true, false, false, "in_call")
            // Unchanged Call-screen behaviour, independent of the flag: the screen manages its own
            // endpoint and the foreground service it starts on success.
            ReachabilityScreen.CALL_BROWSER -> ReachabilityPlan(
                AdvertisingRole.CALL_AVAILABLE,
                endpoint = inputs.bluetoothReady || inputs.wifiNativeReady,
                discoveryScan = inputs.bluetoothReady && inputs.foreground,
                reachable = inputs.bluetoothReady || inputs.wifiNativeReady,
                reason = "call_screen$batteryNote",
            )
            ReachabilityScreen.LIVE -> ReachabilityPlan(AdvertisingRole.LIVE_STREAM, false, false, false, "stream_owns_advertising")
            ReachabilityScreen.WATCH -> watching()
            ReachabilityScreen.LIVE_BROWSING ->
                if (inputs.reachableWhileBrowsing) idleEndpoint(inputs, "live_browsing", batteryNote) else watching()
            ReachabilityScreen.HOME -> idleEndpoint(inputs, "home", batteryNote)
        }
    }

    private fun watching() = ReachabilityPlan(AdvertisingRole.WATCHING, false, false, false, "viewer_owns_advertising")

    /**
     * An idle endpoint with nothing else on air: Home, and the Live tab while only browsing. The
     * stream scan of the browser is not this plan's scan ([ReachabilityPlan.discoveryScan] is the
     * people scan), and scanning needs no advertising set.
     */
    private fun idleEndpoint(inputs: ReachabilityInputs, name: String, batteryNote: String): ReachabilityPlan = when {
        !inputs.featureEnabled -> ReachabilityPlan(AdvertisingRole.NONE, false, false, false, "flag_off")
        !inputs.bluetoothReady && !inputs.wifiNativeReady -> ReachabilityPlan(AdvertisingRole.NONE, false, false, false, "bluetooth_or_permissions")
        !inputs.foreground && !inputs.foregroundServiceHeld ->
            ReachabilityPlan(AdvertisingRole.NONE, false, false, false, "background_without_foreground_service")
        else -> ReachabilityPlan(
            AdvertisingRole.CALL_AVAILABLE,
            endpoint = true,
            discoveryScan = false,
            reachable = true,
            reason = (if (inputs.foreground) name else "${name}_background_service") + batteryNote,
        )
    }
}

/**
 * The Live tab screen as reachability sees it: browsing (the scan only) until a stream is joined,
 * being rejoined after a loss, relayed or played, then Watch. Feature off: always Watch (old behaviour).
 */
internal fun liveTabReachabilityScreen(reachableWhileBrowsing: Boolean, streamJoinedOrRejoining: Boolean): ReachabilityScreen =
    if (reachableWhileBrowsing && !streamJoinedOrRejoining) ReachabilityScreen.LIVE_BROWSING else ReachabilityScreen.WATCH

/** The home status line's reachability part. */
internal enum class HomeReachability { REACHABLE, STARTING, NOT_REACHABLE }

internal fun homeReachability(plan: ReachabilityPlan, endpointAvailable: Boolean, endpointStarting: Boolean): HomeReachability =
    when {
        !plan.reachable -> HomeReachability.NOT_REACHABLE
        endpointAvailable -> HomeReachability.REACHABLE
        endpointStarting -> HomeReachability.STARTING
        else -> HomeReachability.NOT_REACHABLE
    }
