package com.example.bluetoothtest

import java.util.concurrent.CopyOnWriteArraySet

/** Keys, extras and the pure rules of the Wi-Fi settings card (Design WF, package A6). */
internal object WifiFeatures {
    const val ID_CARRIER = "wifi_carrier"
    const val ID_WIDER_REACH = "wifi_wider_reach"
    const val ID_DIRECT = "wifi_direct"
    const val PREF_CARRIER = "wifi_carrier"
    const val PREF_WIDER_REACH = "wifi_wider_reach"
    const val PREF_DIRECT = "wifi_direct"
    const val EXTRA_CARRIER = "test_wifi_carrier"
    const val EXTRA_WIDER_REACH = "test_wifi_wider_reach"
    const val EXTRA_DIRECT = "test_wifi_direct"
    val IDS = listOf(ID_CARRIER, ID_WIDER_REACH, ID_DIRECT)

    const val PERMISSION_NEARBY_WIFI = "android.permission.NEARBY_WIFI_DEVICES"
    const val PERMISSION_FINE = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_COARSE = "android.permission.ACCESS_COARSE_LOCATION"

    /** A6's own guard for the info sheet: if the Direct name has not arrived by then, show the unnamed text. Must exceed A9 `DirectLimits.DEVICE_NAME_TIMEOUT_MS` (3_000). [R M-10] */
    const val DIRECT_NAME_WAIT_MS = 4_000L
}

/** True when any of the three switches is on. `WifiSwitches` is A0's 3-field data class (`WifiEligibility.kt`); A6 has no second switch value type. [R S5] */
internal val WifiSwitches.any: Boolean get() = carrier || widerReach || direct

/** The one hub of the three user switches (A6's former `object WifiSwitches`). [R S5] */
internal object WifiSwitchHub {
    val OFF = WifiSwitches(carrier = false, widerReach = false, direct = false)

    fun of(snapshot: FeatureSnapshot): WifiSwitches = WifiSwitches(
        snapshot.isOn(WifiFeatures.ID_CARRIER), snapshot.isOn(WifiFeatures.ID_WIDER_REACH), snapshot.isOn(WifiFeatures.ID_DIRECT),
    )
    /**
     * Reads the last `FeatureCatalog.refresh`. `MeshLiveApplication.snapshotFeatures` (B15) resolves the stored choices at
     * process start, so a process with no MainActivity (a service restart) reads the stored switches, not all off.
     */
    fun current(): WifiSwitches = of(FeatureCatalog.current)
    /** A7's "any local Wi-Fi switch on" input: pass `WifiSwitchHub::anyOn`. [GAP+] */
    fun anyOn(): Boolean = current().any

    fun interface Listener { fun onSwitchesChanged(old: WifiSwitches, new: WifiSwitches) }
    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var last = OFF
    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    private val DEFAULT_REPORT: (String) -> Unit = { line -> runCatching { android.util.Log.w("BluetoothVideo", line) } }

    /** True on the thread [publish] may run on; MeshLiveApplication sets the main-looper check. Default: any thread (JVM tests). */
    @Volatile var isPublishThread: () -> Boolean = { true }

    /** Where a failing listener is reported: its class name only, never its message (no secrets). Default: android.util.Log. */
    @Volatile var reportListenerFailure: (String) -> Unit = DEFAULT_REPORT

    /**
     * Called by MainActivity after each catalog refresh; tells listeners only about a real change. MAIN THREAD ONLY: the
     * compare-and-set of [last] is not atomic, so A7/A8/A9 must never call it from the Wi-Fi handler (review A6 P3-6).
     */
    fun publish(now: WifiSwitches = current()) {
        if (BuildConfig.DEBUG) check(isPublishThread()) { "WifiSwitchHub.publish off the main thread" }   // a debug assertion; release keeps running
        val old = last
        if (old == now) return
        last = now
        listeners.forEach { listener ->
            runCatching { listener.onSwitchesChanged(old, now) }.onFailure { error ->
                reportListenerFailure("wifi_switch_listener_failed | listener=${listener.javaClass.name} | error=${error.javaClass.name}")
            }
        }
    }

    /** True when the user turned the Wi-Fi carrier off; MainActivity forwards it to the call as `wifiSwitchOff()` (the event `CarrierCutover.onSwitchOff` stands for). [API] */
    fun carrierTurnedOff(old: WifiSwitches, new: WifiSwitches): Boolean = old.carrier && !new.carrier

    /** Test hook. */
    internal fun resetForTest() { listeners.clear(); last = OFF; isPublishThread = { true }; reportListenerFailure = DEFAULT_REPORT }
}

/**
 * Called on the main thread when the user opens the Wi-Fi Direct info sheet. The callee calls `then` exactly once, from any
 * thread, with the phone's own Wi-Fi Direct name or null. Default: no name. A9 step 8 sets it once (in `DirectBearerFactory.attach`). A6 carries no `WifiP2pManager` code. [R M-10] [GAP+] [dir A9]
 */
internal object DirectNameSource {
    @Volatile var read: (then: (String?) -> Unit) -> Unit = { then -> then(null) }
}

internal object WifiPermissionPlan {
    /** Runtime permissions the switch needs on [sdkInt]. Fine location is always asked with coarse (Android 12+ rule). */
    fun permissions(featureId: String, sdkInt: Int): List<String> = when {
        featureId == WifiFeatures.ID_CARRIER -> if (sdkInt >= 33) listOf(WifiFeatures.PERMISSION_NEARBY_WIFI) else emptyList()
        featureId == WifiFeatures.ID_WIDER_REACH && sdkInt < 30 -> emptyList()       // not offered
        sdkInt >= 33 -> listOf(WifiFeatures.PERMISSION_NEARBY_WIFI)
        sdkInt >= 31 -> listOf(WifiFeatures.PERMISSION_FINE, WifiFeatures.PERMISSION_COARSE)
        else -> listOf(WifiFeatures.PERMISSION_FINE)
    }

    /** Which permission must be granted for the switch to count as allowed (coarse alone is not enough). */
    fun required(featureId: String, sdkInt: Int): List<String> =
        permissions(featureId, sdkInt).filter { it != WifiFeatures.PERMISSION_COARSE }

    /** Android 11 and 12 (and 10 for Wi-Fi Direct) need the Location switch on. */
    fun needsLocationOn(featureId: String, sdkInt: Int): Boolean = when (featureId) {
        WifiFeatures.ID_WIDER_REACH -> sdkInt in 30..32
        WifiFeatures.ID_DIRECT -> sdkInt in 29..32
        else -> false
    }

    /** The Wider reach row is not offered on Android 10: Wi-Fi Aware cannot be used there (master D4). */
    fun rowShown(featureId: String, sdkInt: Int): Boolean = !(featureId == WifiFeatures.ID_WIDER_REACH && sdkInt < 30)
}

internal class WifiEnableFlow(
    private val sdkInt: Int,
    private val granted: (String) -> Boolean,
    private val locationOn: () -> Boolean,
) {
    fun missing(featureId: String): List<String> =
        WifiPermissionPlan.required(featureId, sdkInt).filterNot(granted)
    fun locationBlocks(featureId: String): Boolean =
        WifiPermissionPlan.needsLocationOn(featureId, sdkInt) && !locationOn()
    /** Android 12: the user picked "Approximate" (coarse granted, fine refused), which is not enough for Wi-Fi discovery. */
    fun onlyApproximate(featureId: String): Boolean =
        missing(featureId) == listOf(WifiFeatures.PERMISSION_FINE) &&
            WifiFeatures.PERMISSION_COARSE in WifiPermissionPlan.permissions(featureId, sdkInt) && granted(WifiFeatures.PERMISSION_COARSE)
    /**
     * Why a stored-ON switch is not active (Reviewer amendment 4); reads nothing while the switch is off. [widerReachOn]: on
     * Android 11 and 12 the carrier switch alone changes nothing (M-12), which the row says where the user looks.
     */
    fun note(featureId: String, on: Boolean, widerReachOn: Boolean = false): WifiRowNote? = when {
        !on || featureId !in WifiFeatures.IDS -> null
        locationBlocks(featureId) -> WifiRowNote.NEEDS_LOCATION
        missing(featureId).isNotEmpty() -> WifiRowNote.NEEDS_PERMISSION
        featureId == WifiFeatures.ID_CARRIER && WifiRowPolicy.carrierNeedsWiderReach(sdkInt) && !widerReachOn -> WifiRowNote.NEEDS_WIDER_REACH
        else -> null
    }
}

internal enum class WifiRowNote { NEEDS_PERMISSION, NEEDS_LOCATION, NEEDS_WIDER_REACH }

internal class WifiEnableDriver(
    private val flow: WifiEnableFlow,
    private val sdkInt: Int,
    /** Shows the system permission dialog for [permissions]; calls back once with the user's answer already applied. */
    private val askPermissions: (permissions: List<String>, answered: () -> Unit) -> Unit,
    private val showLocationOff: () -> Unit,
    /** [approximateOnly]: the user granted only approximate location, so the dialog says precise location is needed. */
    private val showDenied: (approximateOnly: Boolean) -> Unit,
    /**
     * UI-W7: the plain-words confirmation of Wi-Fi Direct (what it does, and this phone's own Wi-Fi Direct name as other phones
     * see it). Calls [proceed] only when the user taps Turn on. Null = no confirmation (the other switches never have one).
     * It runs after the permission and Location steps, so a refused permission never shows it.
     */
    private val confirmDirect: ((proceed: () -> Unit) -> Unit)? = null,
) {
    /** True: the driver took over and the switch stays off until [proceed] runs. False: switch on at once. */
    fun confirm(featureId: String, proceed: () -> Unit): Boolean {
        if (featureId !in WifiFeatures.IDS) return false
        val needsConfirmation = featureId == WifiFeatures.ID_DIRECT && confirmDirect != null
        val finish: () -> Unit = { if (needsConfirmation) confirmDirect?.invoke(proceed) else proceed() }
        if (flow.locationBlocks(featureId)) { showLocationOff(); return true }
        if (flow.missing(featureId).isEmpty()) {
            if (!needsConfirmation) return false
            finish()
            return true
        }
        askPermissions(WifiPermissionPlan.permissions(featureId, sdkInt)) {
            when {
                flow.missing(featureId).isNotEmpty() -> showDenied(flow.onlyApproximate(featureId))
                flow.locationBlocks(featureId) -> showLocationOff()
                else -> finish()
            }
        }
        return true
    }
}

/**
 * Which rows of Settings > Connection are developer or test rows (UI-W7, design decision 2026-11-10): the Bluetooth transport
 * picker, the paired destination and the next-broadcast and next-call summaries. A DEBUG build keeps them all. A release build
 * hides them, because the app chooses the transport itself, except that the transport picker stays when a non-default
 * transport is stored, so a person who once chose Classic or L2CAP can still change it back. Nobody's stored choice is changed.
 */
internal object ConnectionPageRows {
    /** The paired destination and the next-broadcast and next-call summaries. */
    fun developerRowsShown(debugBuild: Boolean): Boolean = debugBuild

    /** The transport picker card. [storedTransportName] is the stored choice (`BluetoothTransportOption.name`), null when never chosen. */
    fun transportPickerShown(debugBuild: Boolean, storedTransportName: String?): Boolean {
        if (debugBuild) return true
        val stored = storedTransportName?.let { name -> BluetoothTransportOption.entries.firstOrNull { it.name == name } } ?: return false
        return stored != BluetoothTransportOption.DEFAULT
    }
}

internal object WifiRowPolicy {
    /** The specs the Wi-Fi card shows on [sdkInt] (a spec stays registered when hidden). */
    fun rows(all: List<FeatureSpec>, sdkInt: Int): List<FeatureSpec> =
        all.filter { it.id in WifiFeatures.IDS && WifiPermissionPlan.rowShown(it.id, sdkInt) }

    /** Description text of the Direct row: the phone's own Wi-Fi Direct name when it could be read, else the unnamed text key. */
    fun directDescriptionUsesName(name: String?): Boolean = !name.isNullOrBlank()

    /** API 30-32: `wifi_carrier` alone changes nothing on air (WifiEligibility rule 1 needs Wider reach there), so the info sheet says so. [R M-12] */
    fun carrierNeedsWiderReach(sdkInt: Int): Boolean = sdkInt in 30..32
}

internal data class CarrierUi(val effective: CarrierKind, val bluetoothAlive: Boolean)

internal object WifiCallNotices {
    const val LABEL = "Wi-Fi"
    /** D6: the user turned a Wi-Fi switch off (the carrier switch, or Wi-Fi Direct alone) while Wi-Fi was the only live carrier. */
    const val SWITCH_OFF_WIFI_ONLY =
        "You turned a Wi-Fi option off, but this call is still on Wi-Fi because Bluetooth is not connected. It stays on Wi-Fi until Bluetooth is back or the call ends."
    const val BLUETOOTH_LOST_ON_WIFI = "Bluetooth is not connected. This call continues on Wi-Fi."
    const val WIFI_CONNECT_FAILED = "Could not connect over Wi-Fi."
    const val WIFI_NATIVE_ENDED = "The Wi-Fi connection was lost. Call again to reconnect."

    fun label(kind: CarrierKind): String = if (kind == CarrierKind.BLUETOOTH) "Bluetooth" else LABEL

    /** The call status of a carrier move, as the endpoint reports and logs it (unchanged text). */
    const val MOVED_TO_WIFI_STATUS = "Call media moved to $LABEL"
    const val BACK_ON_BLUETOOTH_STATUS = "Call media back on Bluetooth"

    /** What the call screen says for a carrier move (UI audit 2026-11-10, UI-04): a short line from strings.xml. */
    enum class Move { NOW_ON_WIFI, BACK_ON_BLUETOOTH }

    /** The carrier move a call [status] reports, or null for every other status (those keep their own text). */
    fun move(status: String): Move? = when (status) {
        MOVED_TO_WIFI_STATUS -> Move.NOW_ON_WIFI
        BACK_ON_BLUETOOTH_STATUS -> Move.BACK_ON_BLUETOOTH
        else -> null
    }

    /** D6 after the user's switch-off: [keptOnWifi] is [CarrierSet.switchOff]'s outcome (the standby is dead or stale). */
    fun afterSwitchOff(keptOnWifi: Boolean): String? = if (keptOnWifi) SWITCH_OFF_WIFI_ONLY else null

    /**
     * D6 after a carrier died: the Bluetooth carrier is gone and a Wi-Fi carrier carries the call. Read from the live
     * carriers in [after], because in fan-out mode the send carrier still names the dead one until the consumer moves it.
     */
    fun afterCarrierLoss(lost: CarrierKind?, after: CarrierSnapshot): String? =
        if (lost == CarrierKind.BLUETOOTH && !after.bluetoothAlive && after.hasWifiCarrier) BLUETOOTH_LOST_ON_WIFI else null

    /** The notice to show for a change of the effective carrier, or null. [switchTurnedOff]: see [WifiSwitchHub.carrierTurnedOff]. */
    fun forTransition(prev: CarrierUi, next: CarrierUi, switchTurnedOff: Boolean): String? = when {
        next.effective == CarrierKind.BLUETOOTH || next.bluetoothAlive -> null
        switchTurnedOff -> SWITCH_OFF_WIFI_ONLY
        prev.bluetoothAlive -> BLUETOOTH_LOST_ON_WIFI
        else -> null
    }
}
