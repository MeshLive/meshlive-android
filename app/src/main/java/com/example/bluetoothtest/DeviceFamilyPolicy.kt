package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.media.MediaCodecList
import android.os.Build

/*
 * Device-family policy (2026-10-09, CONTRIBUTING): the one place where behaviour depends on what phone this
 * is. Acceptance gates never depend on these entries.
 *
 * Every device-, stack- or codec-component-specific decision is an entry in [DevicePolicyRegistry]
 * with a declarative matcher, the effects it applies, official sources, benchmark evidence, a
 * confidence, a review date and an off-switch. Code that used to test Build fields or component
 * names directly now asks the resolved policy for an effect. Matchers never use Build.MODEL: a
 * family is a SoC/stack/skin/controller/component property, not a phone model.
 *
 * Proposed entries are carried with their sources but have no effect; the resolver only reports
 * which proposals would match this phone, so runs collect evidence before anything is promoted.
 */

/** SoC vendor family, from Build.SOC_MANUFACTURER (API 31+) or the Build.HARDWARE platform name. */
enum class SocFamily { QUALCOMM, EXYNOS, MEDIATEK, TENSOR, UNISOC, UNKNOWN }

/** OEM software family, derived from Build.MANUFACTURER (a label only; never a model). */
enum class OemSkin { ONE_UI, COLOROS, REALME_UI, OXYGENOS, MIUI_HYPEROS, ASUS, PIXEL, OTHER }

/**
 * Which Bluetooth stack code base the platform ships, by era (bluetooth.md entry 29; AOSP
 * init_flags.rs of each release branch). Android 10-12 build the stack from platform/system/bt into
 * the OS image. Android 13 builds it from packages/modules/Bluetooth as an optional Mainline module,
 * updatable from Android 16 (https://source.android.com/docs/core/ota/modular-system/bluetooth), so
 * on newer phones a stack fix can arrive without an SDK change and the security patch level matters
 * too (see [DeviceMatcher.patchMin]).
 *
 * [PLATFORM_SYSTEM_BT] and [BLUETOOTH_MODULE] are umbrella values kept so existing matchers compile
 * and keep their meaning: a matcher naming one matches every era it covers ([covers]). A phone's own
 * lineage ([deriveStackLineage]) is always one of the five eras.
 */
enum class BluetoothStackLineage {
    /** Umbrella: Android 12 and older (system/bt), i.e. [LEGACY_29_30] and [LEGACY_GD_OPTIONAL_31_32]. */
    PLATFORM_SYSTEM_BT,

    /** Umbrella: Android 13 and newer (modules/Bluetooth), i.e. [GD_CORE_33], [GD_FLAGGED_34] and [ACONFIG_35_PLUS]. */
    BLUETOOTH_MODULE,

    /** Android 10-11 (API 29-30, also anything older): legacy BTA/BTM/L2CAP stack only. */
    LEGACY_29_30,

    /** Android 12 (API 31-32): legacy stack with optional GD modules behind init flags that default off. */
    LEGACY_GD_OPTIONAL_31_32,

    /** Android 13 (API 33): GD HCI, controller, ACL manager, advertising and scanning always on. */
    GD_CORE_33,

    /** Android 14 (API 34): large behaviour-flag set with new defaults (finite_att_timeout, subrating...). */
    GD_FLAGGED_34,

    /** Android 15 and newer (API 35+): switches moved to aconfig flags, behaviour changes per QPR. */
    ACONFIG_35_PLUS;

    /** The umbrella value this era belongs to (an umbrella maps to itself). */
    val umbrella: BluetoothStackLineage
        get() = when (this) {
            PLATFORM_SYSTEM_BT, LEGACY_29_30, LEGACY_GD_OPTIONAL_31_32 -> PLATFORM_SYSTEM_BT
            BLUETOOTH_MODULE, GD_CORE_33, GD_FLAGGED_34, ACONFIG_35_PLUS -> BLUETOOTH_MODULE
        }

    /** True when a matcher naming this value accepts a phone whose lineage is [phone]. */
    fun covers(phone: BluetoothStackLineage): Boolean =
        this == phone || ((this == PLATFORM_SYSTEM_BT || this == BLUETOOTH_MODULE) && phone.umbrella == this)
}

/** Controller features the public BluetoothAdapter API reports; null when not read. */
internal data class BluetoothControllerTraits(
    val le2mPhy: Boolean?,
    val leCodedPhy: Boolean?,
    val leExtendedAdvertising: Boolean?,
    val lePeriodicAdvertising: Boolean?,
    val leMaximumAdvertisingDataLength: Int?,
    val offloadedFiltering: Boolean?,
    val multipleAdvertisement: Boolean?,
) {
    fun describe(): String =
        "le2M=${le2mPhy.flag()};leCoded=${leCodedPhy.flag()};extAdv=${leExtendedAdvertising.flag()};" +
            "periodicAdv=${lePeriodicAdvertising.flag()};maxAdvData=${leMaximumAdvertisingDataLength ?: "?"};" +
            "offloadFilter=${offloadedFiltering.flag()};multiAdv=${multipleAdvertisement.flag()}"

    private fun Boolean?.flag(): String = when (this) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun read(adapter: BluetoothAdapter): BluetoothControllerTraits = BluetoothControllerTraits(
            le2mPhy = runCatching { adapter.isLe2MPhySupported }.getOrNull(),
            leCodedPhy = runCatching { adapter.isLeCodedPhySupported }.getOrNull(),
            leExtendedAdvertising = runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrNull(),
            lePeriodicAdvertising = runCatching { adapter.isLePeriodicAdvertisingSupported }.getOrNull(),
            leMaximumAdvertisingDataLength = runCatching { adapter.leMaximumAdvertisingDataLength }.getOrNull(),
            offloadedFiltering = runCatching { adapter.isOffloadedFilteringSupported }.getOrNull(),
            multipleAdvertisement = runCatching { adapter.isMultipleAdvertisementSupported }.getOrNull(),
        )
    }
}

/**
 * What the policy matchers may look at. The first three fields keep the positional order of the
 * former BluetoothStackFingerprint (hardware, board, sdkInt), which remains a type alias.
 */
internal data class DeviceTraits(
    /** Build.HARDWARE (ro.hardware), e.g. "qcom", "mt6765", "s5e9945". */
    val hardware: String,
    /** Build.BOARD (ro.product.board), e.g. "msmnile", "atoll". */
    val board: String,
    /** Build.VERSION.SDK_INT. */
    val sdkInt: Int,
    /** Build.MANUFACTURER. */
    val manufacturer: String = "",
    /** Build.BRAND. */
    val brand: String = "",
    /** Build.SOC_MANUFACTURER (API 31+), else empty. */
    val socManufacturer: String = "",
    /** Build.SOC_MODEL (API 31+), else empty. */
    val socModel: String = "",
    /** Build.VERSION.SECURITY_PATCH; stack fixes can arrive in security patches and Mainline. */
    val securityPatch: String = "",
    val controller: BluetoothControllerTraits? = null,
    /** MediaCodec component names on this phone (encoders and decoders, no aliases). */
    val codecComponents: List<String> = emptyList(),
    /** Facts read once by [DeviceTraitsProbe]; null until it ran (JVM tests, early start). */
    val runtime: DeviceRuntimeTraits? = null,
    /**
     * Entries switched off for this view of the phone only (not a Build field): the experimental
     * legacy-call relief passes call paths a view without [DevicePolicyRegistry.LEGACY_QUALCOMM_CALL].
     */
    val policyOff: Set<String> = emptySet(),
) {
    val socFamily: SocFamily get() = deriveSocFamily(socManufacturer, hardware)
    val oemSkin: OemSkin get() = deriveOemSkin(manufacturer)
    /** The finer stack era (one of the five eras, never an umbrella value). */
    val stackLineage: BluetoothStackLineage get() = deriveStackLineage(sdkInt)

    /** The umbrella family of [stackLineage] (PLATFORM_SYSTEM_BT or BLUETOOTH_MODULE). */
    val stackFamily: BluetoothStackLineage get() = stackLineage.umbrella

    fun describe(): String = listOf(
        "manufacturer=$manufacturer",
        "brand=$brand",
        "hardware=$hardware",
        "board=$board",
        "socManufacturer=$socManufacturer",
        "socModel=$socModel",
        "sdk=$sdkInt",
        "patch=$securityPatch",
        "soc=${socFamily.name}",
        "skin=${oemSkin.name}",
        "stack=${stackLineage.name}",
        "controller=${controller?.describe() ?: "unread"}",
        "codecs=${codecComponents.size}",
        // The runtime facts are flat key=value pairs of their own (rom=, abis=, pageSize=, ...).
        runtime?.describe() ?: "runtime=unread",
    ).joinToString(";") + if (policyOff.isEmpty()) "" else ";viewOff=${policyOff.sorted().joinToString("+")}"

    companion object {
        @Volatile private var snapshot: DeviceTraits? = null
        @Volatile private var controllerTraits: BluetoothControllerTraits? = null
        @Volatile private var runtimeTraits: DeviceRuntimeTraits? = null

        /** This phone (cached; Build fields and the codec list do not change while the app runs). */
        fun current(): DeviceTraits {
            snapshot?.let { return it }
            return synchronized(this) {
                snapshot ?: DeviceTraits(
                    hardware = Build.HARDWARE.orEmpty(),
                    board = Build.BOARD.orEmpty(),
                    sdkInt = Build.VERSION.SDK_INT,
                    manufacturer = Build.MANUFACTURER.orEmpty(),
                    brand = Build.BRAND.orEmpty(),
                    socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else "",
                    socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else "",
                    securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
                    controller = controllerTraits,
                    runtime = runtimeTraits,
                    codecComponents = runCatching {
                        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                            .filterNot { it.isAlias }
                            .map { it.name }
                            .distinct()
                    }.getOrDefault(emptyList()),
                ).also { snapshot = it }
            }
        }

        /** Adds the controller's reported LE features once an adapter exists (main activity start). */
        fun attachBluetoothAdapter(adapter: BluetoothAdapter?) {
            if (adapter == null) return
            val read = BluetoothControllerTraits.read(adapter)
            synchronized(this) {
                controllerTraits = read
                snapshot = snapshot?.copy(controller = read)
            }
        }

        /** Adds the runtime facts [DeviceTraitsProbe] read (called by the probe; null changes nothing). */
        fun attachRuntime(runtime: DeviceRuntimeTraits?) {
            if (runtime == null) return
            synchronized(this) {
                runtimeTraits = runtime
                snapshot = snapshot?.copy(runtime = runtime)
            }
        }
    }
}

/** The former name of [DeviceTraits]; existing call sites and tests still use it. */
internal typealias BluetoothStackFingerprint = DeviceTraits

internal fun deriveSocFamily(socManufacturer: String, hardware: String): SocFamily {
    when (socManufacturer.trim().lowercase()) {
        "qti", "qualcomm" -> return SocFamily.QUALCOMM
        "mediatek" -> return SocFamily.MEDIATEK
        "google" -> return SocFamily.TENSOR
        "spreadtrum", "unisoc" -> return SocFamily.UNISOC
        "samsung" -> return SocFamily.EXYNOS
    }
    val hw = hardware.trim().lowercase()
    return when {
        hw == "qcom" -> SocFamily.QUALCOMM
        MEDIATEK_HARDWARE.matches(hw) -> SocFamily.MEDIATEK
        EXYNOS_HARDWARE.matches(hw) -> SocFamily.EXYNOS
        else -> SocFamily.UNKNOWN
    }
}

private val MEDIATEK_HARDWARE = Regex("mt\\d{4}[a-z0-9]*")
private val EXYNOS_HARDWARE = Regex("(exynos|s5e)\\d{4}[a-z0-9]*")

internal fun deriveOemSkin(manufacturer: String): OemSkin = when (manufacturer.trim().lowercase()) {
    "samsung" -> OemSkin.ONE_UI
    "oppo" -> OemSkin.COLOROS
    "realme" -> OemSkin.REALME_UI
    "oneplus" -> OemSkin.OXYGENOS
    "xiaomi", "redmi", "poco" -> OemSkin.MIUI_HYPEROS
    "asus" -> OemSkin.ASUS
    "google" -> OemSkin.PIXEL
    else -> OemSkin.OTHER
}

/** The stack era for an SDK level (bluetooth.md entry 29). Never returns an umbrella value. */
internal fun deriveStackLineage(sdkInt: Int): BluetoothStackLineage = when {
    sdkInt <= 30 -> BluetoothStackLineage.LEGACY_29_30
    sdkInt <= 32 -> BluetoothStackLineage.LEGACY_GD_OPTIONAL_31_32
    sdkInt == 33 -> BluetoothStackLineage.GD_CORE_33
    sdkInt == 34 -> BluetoothStackLineage.GD_FLAGGED_34
    else -> BluetoothStackLineage.ACONFIG_35_PLUS
}

private val SECURITY_PATCH_FORMAT = Regex("\\d{4}-\\d{2}-\\d{2}")

/** Controller feature constraints; an unread controller never satisfies a constraint. */
internal data class ControllerMatcher(
    val le2mPhy: Boolean? = null,
    val leCodedPhy: Boolean? = null,
    val leExtendedAdvertising: Boolean? = null,
) {
    fun matches(controller: BluetoothControllerTraits?): Boolean {
        if (controller == null) return false
        return (le2mPhy == null || controller.le2mPhy == le2mPhy) &&
            (leCodedPhy == null || controller.leCodedPhy == leCodedPhy) &&
            (leExtendedAdvertising == null || controller.leExtendedAdvertising == leExtendedAdvertising)
    }

    fun describe(): String = listOfNotNull(
        le2mPhy?.let { "le2M=$it" },
        leCodedPhy?.let { "leCoded=$it" },
        leExtendedAdvertising?.let { "extAdv=$it" },
    ).joinToString(",")
}

/**
 * Declarative device matcher. Every non-empty field must hold (AND); values inside one field are
 * alternatives (OR). Text comparisons ignore case. An empty matcher matches every phone.
 */
internal data class DeviceMatcher(
    val sdkMin: Int? = null,
    val sdkMax: Int? = null,
    /** Build.HARDWARE, exact. */
    val hardware: Set<String> = emptySet(),
    /** Build.BOARD, exact; combined with [boardPrefixes] as alternatives. */
    val boards: Set<String> = emptySet(),
    val boardPrefixes: Set<String> = emptySet(),
    val socFamilies: Set<SocFamily> = emptySet(),
    /** Build.SOC_MODEL prefixes (API 31+ only). */
    val socModelPrefixes: Set<String> = emptySet(),
    val manufacturers: Set<String> = emptySet(),
    val skins: Set<OemSkin> = emptySet(),
    val stackLineages: Set<BluetoothStackLineage> = emptySet(),
    val controller: ControllerMatcher? = null,
    /** The phone has at least one codec component whose name starts with one of these. */
    val codecComponentPrefixes: Set<String> = emptySet(),
    /**
     * Build.VERSION.SECURITY_PATCH window, inclusive, ISO yyyy-MM-dd (compared as text, which orders
     * correctly for that format). A phone whose patch is empty or not in that format never satisfies a
     * patch-constrained matcher. Stack fixes land by patch level and QPR, not by SDK.
     */
    val patchMin: String? = null,
    val patchMax: String? = null,
    /** Runtime facts from [DeviceTraitsProbe]; an unread probe never satisfies a constraint. */
    val runtime: RuntimeMatcher? = null,
) {
    init {
        require(patchMin == null || SECURITY_PATCH_FORMAT.matches(patchMin)) { "patchMin must be yyyy-MM-dd: $patchMin" }
        require(patchMax == null || SECURITY_PATCH_FORMAT.matches(patchMax)) { "patchMax must be yyyy-MM-dd: $patchMax" }
    }

    val universal: Boolean
        get() = this == DeviceMatcher()

    fun matches(traits: DeviceTraits): Boolean {
        if (sdkMin != null && traits.sdkInt < sdkMin) return false
        if (sdkMax != null && traits.sdkInt > sdkMax) return false
        if (hardware.isNotEmpty() && hardware.none { it.equals(traits.hardware, ignoreCase = true) }) return false
        if (boards.isNotEmpty() || boardPrefixes.isNotEmpty()) {
            val board = traits.board.lowercase()
            val exact = boards.any { it.lowercase() == board }
            val prefixed = boardPrefixes.any { board.startsWith(it.lowercase()) }
            if (!exact && !prefixed) return false
        }
        if (socFamilies.isNotEmpty() && traits.socFamily !in socFamilies) return false
        if (socModelPrefixes.isNotEmpty() &&
            socModelPrefixes.none { traits.socModel.startsWith(it, ignoreCase = true) }
        ) return false
        if (manufacturers.isNotEmpty() && manufacturers.none { it.equals(traits.manufacturer, ignoreCase = true) }) return false
        if (skins.isNotEmpty() && traits.oemSkin !in skins) return false
        if (stackLineages.isNotEmpty() && stackLineages.none { it.covers(traits.stackLineage) }) return false
        if (patchMin != null || patchMax != null) {
            val patch = traits.securityPatch
            if (!SECURITY_PATCH_FORMAT.matches(patch)) return false
            if (patchMin != null && patch < patchMin) return false
            if (patchMax != null && patch > patchMax) return false
        }
        if (runtime != null && !runtime.matches(traits.runtime)) return false
        if (controller != null && !controller.matches(traits.controller)) return false
        if (codecComponentPrefixes.isNotEmpty() &&
            traits.codecComponents.none { name -> codecComponentPrefixes.any { name.startsWith(it, ignoreCase = true) } }
        ) return false
        return true
    }

    fun describe(): String {
        if (universal) return "every phone"
        return listOfNotNull(
            when {
                sdkMin != null && sdkMax != null -> "API $sdkMin-$sdkMax"
                sdkMin != null -> "API >= $sdkMin"
                sdkMax != null -> "API <= $sdkMax"
                else -> null
            },
            hardware.takeIf { it.isNotEmpty() }?.let { "Build.HARDWARE in ${it.sorted()}" },
            (boards.isNotEmpty() || boardPrefixes.isNotEmpty()).takeIf { it }?.let {
                "Build.BOARD in ${boards.sorted()}" +
                    if (boardPrefixes.isNotEmpty()) " or prefix ${boardPrefixes.sorted()}" else ""
            },
            socFamilies.takeIf { it.isNotEmpty() }?.let { "SoC ${it.map(SocFamily::name).sorted()}" },
            socModelPrefixes.takeIf { it.isNotEmpty() }?.let { "SOC_MODEL prefix ${it.sorted()}" },
            manufacturers.takeIf { it.isNotEmpty() }?.let { "manufacturer ${it.sorted()}" },
            skins.takeIf { it.isNotEmpty() }?.let { "skin ${it.map(OemSkin::name).sorted()}" },
            stackLineages.takeIf { it.isNotEmpty() }?.let { "stack ${it.map(BluetoothStackLineage::name).sorted()}" },
            (patchMin != null || patchMax != null).takeIf { it }?.let {
                when {
                    patchMin != null && patchMax != null -> "security patch $patchMin..$patchMax"
                    patchMin != null -> "security patch >= $patchMin"
                    else -> "security patch <= $patchMax"
                }
            },
            runtime?.let { "runtime ${it.describe()}" },
            controller?.let { "controller ${it.describe()}" },
            codecComponentPrefixes.takeIf { it.isNotEmpty() }?.let { "has codec ${it.sorted()}" },
        ).joinToString("; ")
    }
}

/** Codec component name rule: exact names ignore case; prefixes are case-sensitive. */
internal data class CodecComponentMatcher(
    val names: Set<String> = emptySet(),
    val prefixes: Set<String> = emptySet(),
) {
    fun matches(name: String): Boolean =
        names.any { it.equals(name, ignoreCase = true) } || prefixes.any { name.startsWith(it) }

    fun describe(): String = listOfNotNull(
        names.takeIf { it.isNotEmpty() }?.let { "component ${it.sorted()}" },
        prefixes.takeIf { it.isNotEmpty() }?.let { "component prefix ${it.sorted()}" },
    ).joinToString(" or ")
}

private fun Set<BluetoothTransportOption>.label(): String =
    if (size == BluetoothTransportOption.entries.size) "all transports" else sortedBy { it.ordinal }.joinToString("/") {
        when (it) {
            BluetoothTransportOption.CLASSIC_RFCOMM -> "Classic"
            BluetoothTransportOption.BLE_L2CAP -> "CoC"
            BluetoothTransportOption.BLE_GATT -> "GATT"
        }
    }

internal val ALL_TRANSPORTS: Set<BluetoothTransportOption> = BluetoothTransportOption.entries.toSet()

/** What an entry changes. Effects of several active entries combine as each query documents. */
internal sealed interface DevicePolicyEffect {
    fun describe(): String
}

/** Private-call video limits; several caps combine to the smallest geometry, FPS and rate. */
internal data class CallVideoCap(
    val transports: Set<BluetoothTransportOption>,
    val maxResolution: VideoResolution? = null,
    val maxFps: Int? = null,
    val maxVideoKbps: Int? = null,
) : DevicePolicyEffect {
    override fun describe(): String = "call video on ${transports.label()}: " + listOfNotNull(
        maxResolution?.let { "<=${it.width}x${it.height}" },
        maxFps?.let { "<=$it FPS" },
        maxVideoKbps?.let { "<=$it kbps (not when the selected rate is preserved)" },
    ).joinToString(", ")
}

/** Maximum video rate this phone advertises to call peers (smallest applicable value wins). */
internal data class CallAdvertisedMaxBitrate(val transports: Set<BluetoothTransportOption>, val kbps: Int) : DevicePolicyEffect {
    override fun describe(): String = "advertise call max $kbps kbps on ${transports.label()}"
}

internal data class CallAudioBitrate(val transports: Set<BluetoothTransportOption>, val bitsPerSecond: Int) : DevicePolicyEffect {
    override fun describe(): String = "call Opus ${bitsPerSecond / 1_000} kbps on ${transports.label()}"
}

/** Two 20 ms Opus packets (CoC: twenty) per record instead of one. */
internal data class CallAudioPairing(val transports: Set<BluetoothTransportOption>) : DevicePolicyEffect {
    override fun describe(): String = "batch call audio packets on ${transports.label()}"
}

internal data class CallMediaFlushInterval(val transports: Set<BluetoothTransportOption>, val ms: Long) : DevicePolicyEffect {
    override fun describe(): String = "coalesce call media writes every $ms ms on ${transports.label()}"
}

internal data class CallExtraVideoQueue(val transports: Set<BluetoothTransportOption>, val frames: Int) : DevicePolicyEffect {
    override fun describe(): String = "+$frames call video decode-queue frames on ${transports.label()}"
}

/** The call starts with the constrained-link playback profile (CALL_STABLE). */
internal data class CallConstrainedPlayback(val transports: Set<BluetoothTransportOption>) : DevicePolicyEffect {
    override fun describe(): String = "constrained call playback profile on ${transports.label()}"
}

/** Calls send VP9 instead of a selected AV1 and advertise VP9-only decoding when VP9 exists. */
internal data class CallVp9Compatibility(val transports: Set<BluetoothTransportOption>) : DevicePolicyEffect {
    override fun describe(): String = "call codec VP9 (encode and advertised decode) on ${transports.label()}"
}

internal data class CallPreferredDecoder(val codecId: Int, val decoderName: String) : DevicePolicyEffect {
    override fun describe(): String =
        "call ${VideoCodecMap.label(codecId)} decoder $decoderName"
}

internal data object CallRelayAcknowledgedGattWrites : DevicePolicyEffect {
    override fun describe(): String = "call relay GATT writes with response, paced at 24 KB/s"
}

/** This phone takes call-relay (helper) duty only over LE CoC: no helper offer, link-hello mark or SETUP over GATT. */
internal data object CallRelayHelperCocOnly : DevicePolicyEffect {
    override fun describe(): String = "call-relay helper duty over LE CoC only (never over GATT)"
}

internal data object ClassicSourceBalancedViewerScan : DevicePolicyEffect {
    override fun describe(): String = "Classic source viewer discovery uses balanced scanning, never continuous low latency"
}

internal data object DeferredGattClientClose : DevicePolicyEffect {
    override fun describe(): String = "GATT client close() waits for the disconnect callback (at most 2 s)"
}

internal data object FreshAclLeCocClient : DevicePolicyEffect {
    override fun describe(): String = "LE CoC client connects on a fresh ACL (PSM read, GATT closed first)"
}

internal data object AppBluetoothRestartAllowed : DevicePolicyEffect {
    override fun describe(): String = "DEBUG bt_restart may toggle the adapter (idle only)"
}

internal data object SharedLeCocListener : DevicePolicyEffect {
    override fun describe(): String = "one long-lived LE CoC listener per role with session leases"
}

/** Among encoders of equal hardware/codec/input rank, matching components sort first. */
internal data object PreferredEncoderTieBreak : DevicePolicyEffect {
    override fun describe(): String = "encoder tie-break: matching component first"
}

/**
 * A matching hardware AV1 encoder component is never chosen by Automatic (it stays selectable by hand); see
 * [AutomaticEncoderChoice]. No registry entry uses it yet: no hardware AV1 encoder is known bad.
 */
internal data object NoAutomaticHardwareAv1 : DevicePolicyEffect {
    override fun describe(): String = "Automatic encoder: skip this hardware AV1 component"
}

/**
 * Design HC: a matching hardware HEVC encoder component is never chosen by Automatic (it stays selectable by hand); see
 * [VideoCodecChoice]. Component-scoped like [NoAutomaticHardwareAv1]; an entry needs official sources and evidence, and
 * none exists yet.
 */
internal data object NoAutomaticHardwareHevc : DevicePolicyEffect {
    override fun describe(): String = "Automatic encoder: skip this hardware HEVC component"
}

/** Design HC: as [NoAutomaticHardwareHevc], for a hardware H.264 encoder component. No entry exists yet. */
internal data object NoAutomaticHardwareAvc : DevicePolicyEffect {
    override fun describe(): String = "Automatic encoder: skip this hardware H.264 component"
}

/** Matching Opus decoders are tried before the framework's choice. */
internal data object PreferredOpusDecoder : DevicePolicyEffect {
    override fun describe(): String = "Opus decoder: matching component tried first"
}

/** A failed call video decoder of this component is recreated in place up to [maxAttempts] times. */
internal data class DecoderInPlaceRecovery(val maxAttempts: Int) : DevicePolicyEffect {
    override fun describe(): String = "recreate the call video decoder in place up to $maxAttempts times"
}

enum class PolicySourceKind { AOSP_SOURCE, AOSP_COMMIT, API_REFERENCE, PLATFORM_DOC, CDD, VENDOR_DOC, GOOGLE_OPEN_SOURCE, BLUETOOTH_SPEC }

internal data class PolicySource(val kind: PolicySourceKind, val reference: String, val note: String)

/**
 * OFFICIAL: official sources support both the mechanism and the matcher scope.
 * PARTIAL: official sources support the mechanism, but the scope or values rest on our evidence.
 * EVIDENCE_ONLY: no official backing found; "evidence-only, needs source".
 */
enum class PolicySourceStatus { OFFICIAL, PARTIAL, EVIDENCE_ONLY }

enum class PolicyConfidence { HIGH, MEDIUM, LOW }

/**
 * Component policy an entry belongs to (one registry, organised by pipeline component). Each area
 * contributes its entries from its own file (CodecPolicy, YuvPolicy, BluetoothTransportPolicy,
 * BackgroundPolicy, MemoryThermalPolicy, NativeCompat, and from the camera/audio batches CameraPolicy
 * and AudioCapturePolicy); core Bluetooth-stack and call entries stay in DevicePolicyRegistry.
 */
enum class PolicyArea { CODEC, YUV, BLUETOOTH_TRANSPORT, GRAPHICS, BACKGROUND, MEMORY_THERMAL, NATIVE, CAMERA, AUDIO, WIFI }

/**
 * How far the entry's premise is validated. UPSTREAM_DOCUMENTED: an upstream source documents the
 * behaviour but no local phone has reproduced it (never a pass for an untested phone).
 * LOCALLY_REPRODUCED: a retained benchmark run shows it on our phones. EXPERIMENTAL: neither; the
 * entry is a hypothesis under A/B.
 */
enum class PolicyValidation { UPSTREAM_DOCUMENTED, LOCALLY_REPRODUCED, EXPERIMENTAL }

internal data class DevicePolicyEntry(
    val id: String,
    val title: String,
    val matcher: DeviceMatcher,
    val effects: List<DevicePolicyEffect>,
    /** Plain-language behaviour change. */
    val behaviour: String,
    val sources: List<PolicySource>,
    val sourceStatus: PolicySourceStatus,
    /** Benchmark run paths (relative to the project root) or "none". */
    val evidence: List<String>,
    val confidence: PolicyConfidence,
    /** ISO date this entry and its sources were last reviewed. */
    val reviewed: String,
    /** The code this entry replaced, or the reason it exists. */
    val origin: String,
    /** Optional codec component rule; the entry then applies per component name. */
    val component: CodecComponentMatcher? = null,
    /** Proposed: sourced but not implemented; never active, only reported when it matches. */
    val proposed: Boolean = false,
    /** Extra off-switches that already existed before the registry. */
    val legacySwitches: List<String> = emptyList(),
    /** What the confidence rests on, and what is still unproven. */
    val confidenceNote: String = "",
    /** Pipeline component policy this entry belongs to. */
    val area: PolicyArea = PolicyArea.BLUETOOTH_TRANSPORT,
    /** Validation status of the entry's premise (see [PolicyValidation]). */
    val validation: PolicyValidation = PolicyValidation.EXPERIMENTAL,
    /** What the app does when the entry is off or its effect fails (the generic path). */
    val fallback: String = "",
    /**
     * Id of the implemented entry that replaced this proposal; it is kept for its sources and never reported as a
     * matching proposal (switch audit 2026-10-27, A10).
     */
    val supersededBy: String? = null,
    /**
     * The default-off Experimental arm the entry's effect waits for. While the arm is off the entry is reported under
     * `armOff`, not `active` (its effect stays resolvable: the code path already needs the arm as well).
     */
    val arm: PolicyArm? = null,
) {
    val offSwitch: String get() = "test_device_policy_off=$id"

    fun matcherDescription(): String = listOfNotNull(
        matcher.describe(),
        component?.describe(),
    ).joinToString("; ")
}

/** A DEBUG switch an entry's effect needs ([DevicePolicyEntry.arm]); [on] reads its current value. */
internal class PolicyArm(val switch: String, val on: () -> Boolean) {
    override fun toString(): String = switch
}

internal class ResolvedDevicePolicy(
    val traits: DeviceTraits,
    val disabled: Set<String>,
    /** Active, non-proposed entries whose device matcher holds. */
    val active: List<DevicePolicyEntry>,
    /** Entries that match but were turned off by their switch. */
    val switchedOff: List<DevicePolicyEntry>,
    /** Proposed entries that would match this phone. */
    val matchingProposals: List<DevicePolicyEntry>,
    /** Active entries whose Experimental arm is off ([DevicePolicyEntry.arm]): in effect only once the arm is on. */
    val armOff: List<DevicePolicyEntry> = emptyList(),
) {
    /** What the phone actually runs: [active] without the entries still waiting for their arm ([armOff]). */
    val reportedActive: List<DevicePolicyEntry> get() = active - armOff.toSet()

    val activeIds: List<String> get() = active.map(DevicePolicyEntry::id)

    fun isActive(id: String): Boolean = active.any { it.id == id }

    /** Device-level effects; component-scoped entries contribute only through [componentEffects]. */
    inline fun <reified T : DevicePolicyEffect> effects(): List<T> =
        active.filter { it.component == null }.flatMap { it.effects }.filterIsInstance<T>()

    inline fun <reified T : DevicePolicyEffect> has(): Boolean = effects<T>().isNotEmpty()

    fun callVideoCaps(transport: BluetoothTransportOption): List<CallVideoCap> =
        effects<CallVideoCap>().filter { transport in it.transports }

    fun callAdvertisedMaxBitrateKbps(transport: BluetoothTransportOption): Int? =
        effects<CallAdvertisedMaxBitrate>().filter { transport in it.transports }.minOfOrNull { it.kbps }

    fun callAudioBitRate(transport: BluetoothTransportOption): Int? =
        effects<CallAudioBitrate>().filter { transport in it.transports }.minOfOrNull { it.bitsPerSecond }

    fun callAudioPairing(transport: BluetoothTransportOption): Boolean =
        effects<CallAudioPairing>().any { transport in it.transports }

    fun callMediaFlushIntervalMs(transport: BluetoothTransportOption): Long? =
        effects<CallMediaFlushInterval>().filter { transport in it.transports }.maxOfOrNull { it.ms }

    fun callExtraVideoQueueFrames(transport: BluetoothTransportOption): Int =
        effects<CallExtraVideoQueue>().filter { transport in it.transports }.maxOfOrNull { it.frames } ?: 0

    fun callConstrainedPlayback(transport: BluetoothTransportOption): Boolean =
        effects<CallConstrainedPlayback>().any { transport in it.transports }

    fun callVp9Compatibility(transport: BluetoothTransportOption): Boolean =
        effects<CallVp9Compatibility>().any { transport in it.transports }

    fun callPreferredDecoder(codecId: Int): String? =
        effects<CallPreferredDecoder>().firstOrNull { it.codecId == codecId }?.decoderName

    /** Effects of active component-scoped entries whose component rule matches [componentName]. */
    inline fun <reified T : DevicePolicyEffect> componentEffects(componentName: String): List<T> =
        active.filter { it.component?.matches(componentName) == true }.flatMap { it.effects }.filterIsInstance<T>()
}

internal object DeviceFamilyPolicy {
    /** Bump when entries, matchers or effects change. */
    const val REVISION = "2026-11-09.1"

    val entries: List<DevicePolicyEntry> get() = DevicePolicyRegistry.entries

    @Volatile private var cached: ResolvedDevicePolicy? = null

    /** Entry IDs turned off on this run ("all" turns every entry off). DEBUG launch extra only. */
    val switchedOff: Set<String> get() = DebugTunables.devicePolicyOff

    fun resolve(traits: DeviceTraits, disabled: Set<String> = switchedOff): ResolvedDevicePolicy {
        val armsOff = DevicePolicyOff.armsOff(entries)
        cached?.let { last ->
            // An arm switched after the last resolve (launch extras are read after the first device_policy line) re-resolves.
            if ((last.traits === traits || last.traits == traits) && last.disabled == disabled && armsOff == lastArmsOff) {
                return last
            }
        }
        return DevicePolicyOff.resolve(entries, traits, disabled).also {
            lastArmsOff = armsOff
            cached = it
        }
    }

    @Volatile private var lastArmsOff: Set<String> = emptySet()

    fun current(): ResolvedDevicePolicy = resolve(DeviceTraits.current())

    /** Android 10 LE CoC server leak (AOSP d3c9966624530772fbdc469179726bd9191314c5 fixed it in Android 11). */
    val LE_COC_SERVER_LEAK: DevicePolicyEntry get() = DevicePolicyRegistry.LE_COC_SERVER_LEAK

    /** Android 10/11 GattService ContextMap race (AOSP 1a2c1e97d203bd8e635104c72abdba16944ee3b6, Android 12). */
    val GATT_CONTEXT_MAP_RACE: DevicePolicyEntry get() = DevicePolicyRegistry.GATT_CONTEXT_MAP_RACE

    /** Android 12/13 refused-EATT stranded L2CAP channels (fixed in android14-release l2c_ble.cc). */
    val LE_COC_EATT_STRANDED_CHANNELS: DevicePolicyEntry get() = DevicePolicyRegistry.LE_COC_EATT_STRANDED_CHANNELS

    /** True where a refused EATT request strands L2CAP channels that can capture a later CoC response (entry on). */
    fun leCocEattStrandedChannels(fingerprint: BluetoothStackFingerprint): Boolean =
        resolve(fingerprint).has<FreshAclLeCocClient>()

    /** True where a closed LE CoC server keeps its stack registration until Bluetooth restarts (entry on). */
    fun leCocServerLeaks(fingerprint: BluetoothStackFingerprint): Boolean =
        resolve(fingerprint).has<SharedLeCocListener>()

    /** True where an immediate GATT close can race the stack's own connection bookkeeping (entry on). */
    fun gattContextMapRace(fingerprint: BluetoothStackFingerprint): Boolean =
        resolve(fingerprint).has<DeferredGattClientClose>()

    /**
     * Component-rule effects without device traits: only entries whose device matcher is universal
     * apply (pure JVM paths have no Build). Pass [traits] to include device-scoped component entries.
     */
    inline fun <reified T : DevicePolicyEffect> componentEffects(
        componentName: String,
        traits: DeviceTraits? = null,
        disabled: Set<String> = switchedOff,
    ): List<T> {
        if (traits != null) return resolve(traits, disabled).componentEffects(componentName)
        return entries.filter { entry ->
            !entry.proposed && entry.matcher.universal && !DevicePolicyOff.isOff(entry.id, disabled) &&
                entry.component?.matches(componentName) == true
        }.flatMap { it.effects }.filterIsInstance<T>()
    }

    /**
     * One `BENCHMARK device_policy` line: this phone's traits, active entries and switches. A user's
     * bug report carries it; `unconfirmed` lists active entries no local phone has reproduced
     * (upstream-documented or experimental), so reports can confirm or refute them.
     */
    fun benchmarkLine(resolved: ResolvedDevicePolicy): String {
        val componentHits = resolved.active.filter { it.component != null }.joinToString(",") { entry ->
            val names = resolved.traits.codecComponents.filter { entry.component!!.matches(it) }
            "${entry.id}[${names.size}]"
        }
        return "BENCHMARK device_policy: revision=$REVISION | traits=${resolved.traits.describe()} | " +
            "active=${resolved.reportedActive.filter { it.component == null }.joinToString(",") { it.id }.ifEmpty { "none" }} | " +
            "componentRules=${componentHits.ifEmpty { "none" }} | " +
            "switchedOff=${resolved.switchedOff.joinToString(",") { it.id }.ifEmpty { "none" }} | " +
            "matchingProposals=${resolved.matchingProposals.joinToString(",") { it.id }.ifEmpty { "none" }} | " +
            "armOff=${resolved.armOff.joinToString(",") { it.id }.ifEmpty { "none" }} | " +
            "unconfirmed=${resolved.reportedActive.filter { it.validation != PolicyValidation.LOCALLY_REPRODUCED }.joinToString(",") { it.id }.ifEmpty { "none" }} | " +
            "entries=${entries.size}"
    }
}

/** Parses `test_device_policy_off` ("id1,id2" or "all"). */
internal fun parseDevicePolicySwitches(value: String?): Set<String> =
    value?.split(',', ';', ' ')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

/**
 * The one off-switch evaluation for every registry batch (switch audit 2026-10-27, A10). Before, ScanPolicy, AudioSwitches,
 * AdvertisingPolicy and BleL2capControl checked `test_device_policy_off` by hand and ignored a traits view's own
 * [DeviceTraits.policyOff], while [DeviceFamilyPolicy.resolve], CameraPolicy and CocPolicy honoured it.
 */
internal object DevicePolicyOff {
    /** True when "all" or [id] is switched off by the launch extra ([disabled]) or by the traits view ([viewOff]). */
    fun isOff(id: String, disabled: Set<String> = DeviceFamilyPolicy.switchedOff, viewOff: Set<String> = emptySet()): Boolean =
        "all" in disabled || id in disabled || "all" in viewOff || id in viewOff

    /** Ids of [entries] whose Experimental arm is off now. */
    fun armsOff(entries: List<DevicePolicyEntry>): Set<String> =
        entries.filter { it.arm?.on?.invoke() == false }.map { it.id }.toSet()

    /**
     * [entries] as seen by [traits]: the matcher, the off-switches ([isOff] with the view's own ids), Experimental arms, and
     * proposals (a component-scoped one only where this phone has such a component; superseded ones never).
     */
    fun resolve(entries: List<DevicePolicyEntry>, traits: DeviceTraits, disabled: Set<String>): ResolvedDevicePolicy {
        val matching = entries.filter { it.matcher.matches(traits) }
        val implemented = matching.filterNot { it.proposed }
        val proposals = matching.filter { entry ->
            entry.proposed && entry.supersededBy == null &&
                (entry.component?.let { rule -> traits.codecComponents.any { rule.matches(it) } } ?: true)
        }
        val switchedOff = implemented.filter { isOff(it.id, disabled, traits.policyOff) }
        val active = implemented - switchedOff.toSet()
        return ResolvedDevicePolicy(
            traits = traits,
            disabled = disabled,
            active = active,
            switchedOff = switchedOff,
            matchingProposals = proposals,
            armOff = active.filter { it.arm?.on?.invoke() == false },
        )
    }
}
