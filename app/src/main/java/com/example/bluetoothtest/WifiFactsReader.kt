package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.net.NetworkInterface

/** The platform reads of the facts reader, behind an interface so the JVM tests need no Android. */
internal interface WifiPlatformReader {
    fun sdkInt(): Int
    fun hasFeature(name: String): Boolean
    fun awareAvailable(): Boolean
    fun deviceAttached(): Boolean?
    fun wifiEnabled(): Boolean
    fun staFrequencyMhz(): Int?
    fun locationEnabled(): Boolean
    fun granted(permission: String): Boolean
    fun batteryPercent(): Int
    fun charging(): Boolean
    fun batterySaver(): Boolean
    fun thermalStatus(): Int
    fun hotspotActive(): Boolean
    fun awareFreeNdp(): Int?
    fun characteristicsNdp(): Int?

    /** API 33+: `manager.characteristics?.isInstantCommunicationModeSupported ?: false`; below 33: false. [R A0 directive] */
    fun icmSupported(): Boolean

    /** Null when no manager/context or unreadable; any thread. [GAP+] */
    fun characteristics(): AwareCapsSummary?
}

/** The user's three Wi-Fi switches (A0's three-field class); A6 installs the FeatureCatalog source. [R M-7] */
internal fun interface WifiSwitchSource {
    fun read(): WifiSwitches
}

/** The device-family policy as the facts reader sees it (A4.7 resolves it). */
internal interface WifiPolicyView {
    fun awareOff(): Boolean
    fun directOff(): Boolean
    fun maxNdp(): Int?
}

/**
 * Reads one `WifiFacts` snapshot. With `wifi_carrier` off the first statement returns [switchedOffFacts] and calls
 * nothing on [platform] except the static `sdkInt()` and nothing on [policy], so every switch off is inert (X4,
 * [Am 2a]). The reader is pure around [WifiPlatformReader], so the JVM test needs no Android.
 */
internal class WifiFactsReader(
    private val platform: WifiPlatformReader,
    private val ledger: WifiLedger,
    private val switches: WifiSwitchSource,
    private val policy: WifiPolicyView,
    private val awareHeld: () -> Boolean,
    private val activeCount: () -> Int,
    private val now: () -> Long,
) {
    fun read(): WifiFacts {
        val sw = switches.read()
        if (!sw.carrier) return switchedOffFacts(sw) // [Am 2a]
        val api = platform.sdkInt()
        val held = awareHeld()
        val otherClient = when {
            api < 31 -> null
            held -> ledger.otherClientAtAttach
            else -> platform.deviceAttached()
        }
        return WifiFacts(
            api = api,
            featureAware = platform.hasFeature(PackageManager.FEATURE_WIFI_AWARE),
            featureDirect = platform.hasFeature(PackageManager.FEATURE_WIFI_DIRECT),
            awareAvailable = platform.awareAvailable(),
            otherAwareClientAttached = otherClient,
            awareIcmSupported = platform.icmSupported(),
            awareAttachHeldByThisApp = activeCount() > 0,
            wifiEnabled = platform.wifiEnabled(),
            staFrequencyMhz = platform.staFrequencyMhz(),
            locationEnabled = api >= 33 || platform.locationEnabled(),
            permissionNearby = api >= 33 && platform.granted(Manifest.permission.NEARBY_WIFI_DEVICES),
            permissionFineLocation = platform.granted(Manifest.permission.ACCESS_FINE_LOCATION),
            switches = sw,
            batteryPercent = platform.batteryPercent(),
            charging = platform.charging(),
            batterySaver = platform.batterySaver(),
            thermalStatus = platform.thermalStatus(),
            familyPolicyAwareOff = policy.awareOff(),
            familyPolicyDirectOff = policy.directOff(),
            localFaultPauseUntilMs = ledger.pauseUntilMs,
            nowMs = now(),
            directGroupActive = ledger.directGroupActive,
            hotspotActive = platform.hotspotActive(),
            freeNdp = computeFreeNdp(platform, ledger, policy),
        )
    }

    /** The facts of a switched-off app: no platform read (except the static `sdkInt()`) and no policy read. [Am 2a] */
    fun switchedOffFacts(sw: WifiSwitches): WifiFacts = WifiFacts(
        api = platform.sdkInt(),
        featureAware = false,
        featureDirect = false,
        awareAvailable = false,
        otherAwareClientAttached = null,
        awareIcmSupported = false,
        awareAttachHeldByThisApp = activeCount() > 0,
        wifiEnabled = false,
        staFrequencyMhz = null,
        locationEnabled = false,
        permissionNearby = false,
        permissionFineLocation = false,
        switches = sw,
        batteryPercent = 100,
        charging = false,
        batterySaver = false,
        thermalStatus = 0,
        familyPolicyAwareOff = false,
        familyPolicyDirectOff = false,
        localFaultPauseUntilMs = ledger.pauseUntilMs,
        nowMs = now(),
        directGroupActive = ledger.directGroupActive,
        hotspotActive = false,
        freeNdp = 0,
    )

    /** Free Aware data paths now: the smallest of every limit, with a preemptable stranger's path counted as free. [GAP+][Am 4] */
    fun freeNdp(): Int = computeFreeNdp(platform, ledger, policy)

    /** The cap before subtracting reservations. [GAP+] */
    fun capacityNdp(): Int = capacity(platform, ledger, policy)

    /** The budget a reservation is checked against: see [reserveCapacity]. [A4 review P1-1] */
    fun ndpCapacity(): Int = reserveCapacity(ledger, freeNdp())

    companion object {
        /**
         * The real data-path budget for `WifiLedger.reserveNdp`/`reserveUntrustedNdp`: the reserved paths plus the
         * free ones, minus the stranger's preemptable path. [freeNdp] already counts that path as free (so
         * eligibility admits an authenticated hop), so adding it to `ndpReserved` without the subtraction would count
         * it twice: on a one-path chip the budget would read 2 and the authenticated reserve would add a second
         * reservation instead of preempting the stranger. [F-3][A4 review P1-1]
         */
        fun reserveCapacity(ledger: WifiLedger, freeNdp: Int): Int = ledger.ndpReserved + freeNdp - ledger.untrustedCount

        fun computeFreeNdp(platform: WifiPlatformReader, ledger: WifiLedger, policy: WifiPolicyView): Int {
            val capacity = capacity(platform, ledger, policy)
            val byLedger = capacity - ledger.ndpReserved + ledger.untrustedCount // [Am 4]
            val byPlatform = platform.awareFreeNdp()?.plus(ledger.untrustedCount) // [Am 4]
            return maxOf(0, if (byPlatform != null) minOf(byPlatform, byLedger) else byLedger)
        }

        fun capacity(platform: WifiPlatformReader, ledger: WifiLedger, policy: WifiPolicyView): Int = minOf(
            WifiLimits.MAX_DATA_PATHS, // [S19]
            platform.characteristicsNdp() ?: 1, // API 33+; unknown or Characteristics null (Wi-Fi never on): assume 1
            ledger.observedMaxNdp ?: Int.MAX_VALUE,
            policy.maxNdp() ?: Int.MAX_VALUE, // WifiAwareMaxNdp family effect
        )
    }
}

/** The interim switch source (A4 and DEBUG builds): the three DEBUG extras, the sub-switches depending on `wifi_carrier`. [R M-7][Am 12] */
internal object DefaultWifiSwitches : WifiSwitchSource {
    override fun read(): WifiSwitches {
        val c = DebugTunables.wifiCarrier
        return WifiSwitches(carrier = c, widerReach = c && DebugTunables.wifiWiderReach, direct = c && DebugTunables.wifiDirect)
    }
}

/** A [WifiPolicyView] over an already resolved device policy; A4.7's test builds one over a synthetic entry. [Am 14] */
internal class ResolvedWifiPolicyView(private val resolved: () -> ResolvedDevicePolicy) : WifiPolicyView {
    override fun awareOff(): Boolean {
        val r = resolved()
        return r.has<WifiAwareOff>()
    }

    override fun directOff(): Boolean {
        val r = resolved()
        return r.has<WifiDirectOff>()
    }

    override fun maxNdp(): Int? {
        val r = resolved()
        return r.effects<WifiAwareMaxNdp>().minOfOrNull { it.max }
    }
}

/** The device-family Wi-Fi policy of this phone; A6 never replaces it. [Am 14] */
internal object DeviceWifiPolicyView : WifiPolicyView by ResolvedWifiPolicyView({ DeviceFamilyPolicy.current() })

/** The Android implementation of [WifiPlatformReader]; API-31 and API-33 members live in nested objects (API isolation). */
internal class AndroidWifiPlatformReader(private val context: Context) : WifiPlatformReader {
    override fun sdkInt(): Int = Build.VERSION.SDK_INT

    override fun hasFeature(name: String): Boolean = runCatching { context.packageManager.hasSystemFeature(name) }.getOrDefault(false)

    override fun awareAvailable(): Boolean = runCatching {
        val manager = manager() ?: return@runCatching false
        if (Build.VERSION.SDK_INT < 33 && !locationEnabled()) return@runCatching false // platform 1.1: below 33 Aware is gone while Location is off
        manager.isAvailable
    }.getOrDefault(false)

    override fun deviceAttached(): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < 31) null else manager()?.let { Api31.deviceAttached(it) }
    }.getOrNull()

    override fun wifiEnabled(): Boolean = runCatching {
        context.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: false
    }.getOrDefault(false)

    override fun staFrequencyMhz(): Int? = WifiCoexistence.connectedFrequencyMhz(context)

    override fun locationEnabled(): Boolean = runCatching {
        Build.VERSION.SDK_INT >= 33 || context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }.getOrDefault(false)

    override fun granted(permission: String): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    override fun batteryPercent(): Int = runCatching {
        val intent = batteryIntent() ?: return@runCatching 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) 100 else (level * 100) / scale
    }.getOrDefault(100)

    override fun charging(): Boolean = runCatching {
        val intent = batteryIntent() ?: return@runCatching false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }.getOrDefault(false)

    override fun batterySaver(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false
    }.getOrDefault(false)

    override fun thermalStatus(): Int = runCatching {
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
    }.getOrDefault(0)

    override fun hotspotActive(): Boolean = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
            iface.isUp && HOTSPOT_NAME.matches(iface.name ?: "")
        }
    }.getOrDefault(false)

    override fun awareFreeNdp(): Int? = runCatching {
        if (Build.VERSION.SDK_INT < 31) null else manager()?.let { Api31.freeNdp(it) }
    }.getOrNull()

    override fun characteristicsNdp(): Int? = runCatching {
        if (Build.VERSION.SDK_INT < 33) null else manager()?.let { Api33.ndp(it) }
    }.getOrNull()

    override fun icmSupported(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 33) false else manager()?.let { Api33.icm(it) } ?: false
    }.getOrDefault(false)

    override fun characteristics(): AwareCapsSummary? = runCatching {
        val manager = manager() ?: return@runCatching null
        val c = manager.characteristics ?: return@runCatching null
        AwareCapsSummary(
            maxServiceSpecificInfoLength = c.maxServiceSpecificInfoLength,
            instantCommunicationSupported = if (Build.VERSION.SDK_INT >= 33) Api33.icm(manager) else false,
            setChannelOnDataPathSupported = if (Build.VERSION.SDK_INT >= 33) Api33.setChannelSupported(manager) else false,
            maxNdp = if (Build.VERSION.SDK_INT >= 33) Api33.ndp(manager) ?: 1 else 1,
        )
    }.getOrNull()

    private fun manager(): WifiAwareManager? = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    private fun batteryIntent(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    @RequiresApi(31)
    private object Api31 {
        fun freeNdp(manager: WifiAwareManager): Int? = manager.availableAwareResources?.availableDataPathsCount
        fun deviceAttached(manager: WifiAwareManager): Boolean? = manager.isDeviceAttached
    }

    @RequiresApi(33)
    private object Api33 {
        fun ndp(manager: WifiAwareManager): Int? = manager.characteristics?.numberOfSupportedDataPaths
        fun icm(manager: WifiAwareManager): Boolean = manager.characteristics?.isInstantCommunicationModeSupported ?: false
        fun setChannelSupported(manager: WifiAwareManager): Boolean = manager.isSetChannelOnDataPathSupported
    }

    private companion object {
        /** The interface-name heuristic of Issue 4: a soft AP is `ap0`, `swlan0`, `softap0`, `ap<digit>`; never reflection. */
        val HOTSPOT_NAME = Regex("^(ap|softap|swlan)\\d+$")
    }
}
