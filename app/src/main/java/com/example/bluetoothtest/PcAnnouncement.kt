package com.example.bluetoothtest

import java.util.UUID

/**
 * "Find PCs" (Settings > Features; DEBUG `test_find_pcs` is the benchmark switch; default off, Experimental;
 * Design QW in DECISIONS.md). A Windows PC cannot send the 22-byte manufacturer announcement, so it advertises one
 * connectable EXTENDED advert whose 128-bit service data under [PcAnnouncement.UUID] carries a 9-byte rotating call
 * offer. On: every discovery scanner adds one service-data scan filter (empty data, matches any payload), asks for
 * extended adverts where the controller can, and maps a valid payload into the ordinary rotating
 * [DiscoveryAnnouncement]. Off: scanning is exactly as before ([DiscoveryScanPlan]). Android never advertises this
 * form, and no handshake, record or admission rule changes. Needs rotating IDs on (the mapped announcement is
 * version 5, so a scanner that does not accept rotating offers rejects it like any rotating offer).
 */
object PcAnnouncementFeature {
    const val EXTRA = "test_find_pcs"
    const val PREF = "experimental_find_pcs"

    @Volatile var enabled = false

    /**
     * Set once a scanner reports that the filter cannot be used ([PcFilterGuard]); every later scanner of this
     * process leaves the PC filter and the extended scan settings out. Cleared when the feature is switched on again.
     */
    @Volatile var filterRejected = false

    /** Whether a scan started now carries the PC filter (before the per-adapter extended-advertising check). */
    fun filterActive(): Boolean = enabled && !filterRejected

    /** The shared feature rule ([FeatureCatalog.resolve]). */
    fun resolve(
        benchmarkLaunch: Boolean,
        extra: (String) -> Boolean,
        stored: (String) -> Boolean?,
    ): Boolean = FeatureCatalog.resolve(ConnectivityFeatures.findPcs, benchmarkLaunch, extra, stored)
}

/** Why a PC announcement payload was rejected; the names match the shared vector file's `error` strings. */
enum class PcAnnouncementError { Length, Version, Kind, Fields }

class PcAnnouncementException(val error: PcAnnouncementError, message: String) : IllegalArgumentException(message)

/** One decoded PC announcement (v1, kind 0 = call offer). */
class PcAnnouncement(
    val rdi: ByteArray,
    val relayCapable: Boolean,
    /** Same meaning as the Android call-offer compact bit 18 and `capabilitySummary` 0x10. */
    val relayConsent: Boolean,
    /** Codec bits 0..2 (capabilitySummary codec bits). */
    val codecs: Int,
    /** Route cost 0..15. */
    val cost: Int,
) {
    init {
        require(rdi.size == DiscoveryAnnouncement.NODE_PREFIX_BYTES)
        require(codecs in 0..CODEC_MASK && cost in 0..MAX_COST)
    }

    /**
     * The ordinary rotating call offer: prefix = RDI, ACCEPTING | CALL | GATT (+ RELAY), summary = codecs | 0x10 if
     * consent, token 0, no geometry. It then goes through `acceptAnnouncement` like any rotating call offer.
     */
    fun toDiscoveryAnnouncement(): DiscoveryAnnouncement = DiscoveryAnnouncement(
        nodeIdPrefix = rdi.copyOf(),
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE or
            DiscoveryAnnouncement.FLAG_GATT_TRANSPORT or
            (if (relayCapable) DiscoveryAnnouncement.FLAG_RELAY_CAPABLE else 0),
        capabilitySummary = codecs or (if (relayConsent) CompactDiscoveryAnnouncement.CALL_RELAY_CONSENT_SUMMARY else 0),
        streamToken = 0,
        routeCost = cost,
        sequence = 0,
        version = DiscoveryAnnouncement.ROTATING_VERSION,
    )

    /** Reference encoder for the shared vectors and tests; Android never advertises this form. */
    fun encode(): ByteArray = ByteArray(LENGTH).also {
        it[0] = ((VERSION shl 4) or KIND_CALL_OFFER).toByte()
        rdi.copyInto(it, 1)
        it[7] = (FLAG_GATT or (if (relayCapable) FLAG_RELAY_CAPABLE else 0) or (if (relayConsent) FLAG_RELAY_CONSENT else 0)).toByte()
        it[8] = (codecs or (cost shl 3)).toByte()
    }

    companion object {
        /** The fixed 128-bit service-data UUID ("a MeshLive PC"). */
        val UUID: UUID = java.util.UUID.fromString("c8d5a10a-9f88-4f65-a03d-7f5a2fb921fa")
        const val LENGTH = 9
        const val VERSION = 1
        const val KIND_CALL_OFFER = 0
        const val FLAG_GATT = 1
        const val FLAG_RELAY_CAPABLE = 1 shl 1
        const val FLAG_RELAY_CONSENT = 1 shl 2
        private const val RESERVED_FLAGS = 0xf8
        private const val CODEC_MASK = 0x7
        private const val MAX_COST = 0xf
        private const val RESERVED_BYTE8 = 0x80

        /** Strict decode: anything but exactly the v1 layout throws [PcAnnouncementException]. */
        fun decode(bytes: ByteArray?): PcAnnouncement {
            if (bytes == null || bytes.size != LENGTH) {
                throw PcAnnouncementException(PcAnnouncementError.Length, "PC announcement must be exactly $LENGTH bytes")
            }
            val first = bytes[0].toInt() and 0xff
            if (first ushr 4 != VERSION) throw PcAnnouncementException(PcAnnouncementError.Version, "Unsupported PC announcement version")
            if (first and 0xf != KIND_CALL_OFFER) throw PcAnnouncementException(PcAnnouncementError.Kind, "Unsupported PC announcement kind")
            val flags = bytes[7].toInt() and 0xff
            val last = bytes[8].toInt() and 0xff
            if (flags and FLAG_GATT == 0) throw PcAnnouncementException(PcAnnouncementError.Fields, "GATT bit is required")
            if (flags and RESERVED_FLAGS != 0) throw PcAnnouncementException(PcAnnouncementError.Fields, "Reserved flag bits are set")
            if (last and RESERVED_BYTE8 != 0) throw PcAnnouncementException(PcAnnouncementError.Fields, "Reserved codec/cost bit is set")
            return PcAnnouncement(
                rdi = bytes.copyOfRange(1, 7),
                relayCapable = flags and FLAG_RELAY_CAPABLE != 0,
                relayConsent = flags and FLAG_RELAY_CONSENT != 0,
                codecs = last and CODEC_MASK,
                cost = (last ushr 3) and MAX_COST,
            )
        }

        /** Null-safe wrapper for the scanner: the decoded announcement or the error. */
        fun tryDecode(bytes: ByteArray?): Result<PcAnnouncement> = runCatching { decode(bytes) }
    }
}

/**
 * Which scan failures mean the PC filter (or the extended scan settings that come with it) is the problem. The same
 * codes as [CompactFilterGuard], but with their own drop order: the PC filter is the newer, experimental one, so it
 * goes FIRST; the compact filter next; the app's own service-UUID filter is never dropped.
 */
internal object PcFilterGuard {
    fun refusesFilter(errorCode: Int): Boolean = CompactFilterGuard.refusesFilter(errorCode)

    enum class Drop { NONE, PC, COMPACT }

    /** What a scanner with these optional filters active drops for [errorCode]. */
    fun dropFor(errorCode: Int, pcActive: Boolean, compactActive: Boolean): Drop = when {
        !refusesFilter(errorCode) -> Drop.NONE
        pcActive -> Drop.PC
        compactActive -> Drop.COMPACT
        else -> Drop.NONE
    }
}

/**
 * Pure description of one discovery scan start: the filters and settings the scanner converts into Android objects.
 * Kept free of Android types so the "switch off is unchanged" rule is JVM-tested.
 */
internal sealed interface ScanFilterSpec {
    /** `setServiceUuid(uuid)` or, with [mask], `setServiceUuid(uuid, mask)`. */
    data class ServiceUuid(val uuid: UUID, val mask: UUID? = null) : ScanFilterSpec

    /** `setServiceData(uuid, empty)`: matches any payload under that UUID (AOSP prefix match). */
    data class ServiceDataAny(val uuid: UUID) : ScanFilterSpec
}

/** Scan settings that differ from the defaults. [legacy] / [phy] null = not set (platform default). */
internal data class ScanSettingsSpec(val mode: Int, val legacy: Boolean?, val phy: Int?)

internal object DiscoveryScanPlan {
    /** `BluetoothDevice.PHY_LE_1M` (inlined so the plan is JVM-testable). */
    const val PHY_LE_1M = 1

    /** `ScanSettings.SCAN_MODE_*` (inlined so the choice is JVM-testable). */
    const val MODE_LOW_POWER = 0
    const val MODE_BALANCED = 1
    const val MODE_LOW_LATENCY = 2

    /**
     * The system scan mode of one discovery scan: low latency while a person waits ([startLowLatency]), otherwise
     * balanced, except the idle relay helper's [lowPower] scan (screen off, not charging; [RelayHelperPolicy]).
     */
    fun mode(startLowLatency: Boolean, lowPower: Boolean): Int = when {
        startLowLatency -> MODE_LOW_LATENCY
        lowPower -> MODE_LOW_POWER
        else -> MODE_BALANCED
    }

    /**
     * Software filtering sends no hardware filter at all (the PC form is then matched in software, in `addResult`).
     * Order: the app's filter, then compact, then the PC filter, so the original two are byte-for-byte what they were.
     */
    fun filters(software: Boolean, compact: Boolean, pc: Boolean): List<ScanFilterSpec> =
        if (software) {
            emptyList()
        } else buildList {
            add(ScanFilterSpec.ServiceUuid(BleL2capControlProtocol.SERVICE_UUID))
            if (compact) {
                add(ScanFilterSpec.ServiceUuid(CompactDiscoveryAnnouncement.FILTER_UUID, CompactDiscoveryAnnouncement.FILTER_MASK))
            }
            if (pc) add(ScanFilterSpec.ServiceDataAny(PcAnnouncement.UUID))
        }

    /**
     * Unchanged unless the PC scan is active: then `setLegacy(false)` + `PHY_LE_1M`, because the PC's
     * announcement is an extended advert (gate 1, secondary PHY LE 1M) that a legacy-only scan never reports. LE 1M, not
     * `PHY_LE_ALL_SUPPORTED`: Coded scanning hurt reception on the Xiaomi (F11). Only the legacy flag differs from the
     * F11 settings, and only for the PC-enabled scan.
     */
    fun settings(mode: Int, le1mOnly: Boolean, pcExtended: Boolean): ScanSettingsSpec = when {
        pcExtended -> ScanSettingsSpec(mode, legacy = false, phy = PHY_LE_1M)
        le1mOnly -> ScanSettingsSpec(mode, legacy = true, phy = PHY_LE_1M)
        else -> ScanSettingsSpec(mode, legacy = null, phy = null)
    }
}

/** How the non-manufacturer forms of one scan record resolve (precedence: compact UUID > PC service data). */
internal object ScanRecordForms {
    sealed interface Outcome {
        object None : Outcome
        object CompactInvalid : Outcome
        class Compact(val announcement: DiscoveryAnnouncement) : Outcome
        class Pc(val announcement: DiscoveryAnnouncement, val decoded: PcAnnouncement) : Outcome
        class PcInvalid(val error: PcAnnouncementError) : Outcome
        class PcStream(val announcement: DiscoveryAnnouncement, val kind: Int) : Outcome
        object PcDisabled : Outcome
    }

    /**
     * For a record WITHOUT the 22-byte manufacturer announcement (that form always wins and is handled first).
     * [compactCandidate] is non-null only while compact discovery is active, [pcData] only while the PC scan is.
     * A compact candidate wins even when it is invalid: the PC form is never tried behind it.
     */
    fun resolve(compactCandidate: UUID?, pcData: ByteArray?, acceptRotating: Boolean, pcStreams: Boolean = PcStreamsFeature.enabled): Outcome {
        if (compactCandidate != null) {
            return runCatching { CompactDiscoveryAnnouncement.decode(compactCandidate, acceptRotating) }
                .fold({ Outcome.Compact(it) }, { Outcome.CompactInvalid })
        }
        if (pcData == null) return Outcome.None
        if (pcStreams && pcData.isNotEmpty()) {
            val first = pcData[0].toInt() and 0xff
            if (first ushr 4 != 1) return Outcome.PcInvalid(PcAnnouncementError.Version)
            if (first and 0xf !in 0..2) return Outcome.PcInvalid(PcAnnouncementError.Kind)
        }
        if (pcData.isNotEmpty() && pcData[0].toInt() and 0xf != 0) {
            val first = pcData[0].toInt() and 0xff
            if (first ushr 4 != 1) return Outcome.PcInvalid(PcAnnouncementError.Version)
            val kind = first and 0xf
            if (kind !in 1..2) return Outcome.PcInvalid(PcAnnouncementError.Kind)
            if (!pcStreams) return Outcome.PcDisabled
            if (!acceptRotating) return Outcome.PcInvalid(PcAnnouncementError.Fields)
            return runCatching { PcStreamAnnouncement.decode(pcData) }.fold(
                { Outcome.PcStream(it, kind) },
                { Outcome.PcInvalid((it as? PcAnnouncementException)?.error ?: PcAnnouncementError.Fields) },
            )
        }
        return PcAnnouncement.tryDecode(pcData).fold(
            { Outcome.Pc(it.toDiscoveryAnnouncement(), it) },
            { Outcome.PcInvalid((it as? PcAnnouncementException)?.error ?: PcAnnouncementError.Fields) },
        )
    }
}

/** Design QV revision 4: a second, default-off gate in addition to Find PCs. */
object PcStreamsFeature {
    const val EXTRA = "test_pc_streams"
    const val PREF = "experimental_pc_streams"
    @Volatile var enabled = false
}

/** Kinds 1/2 wrap the unchanged 22-byte rotating announcement. No handshake changes. */
internal object PcStreamAnnouncement {
    /** Kind-2 summary bits: VP9, AV1 and H.264 (bits 0..2) and the audio-only marker `0x40`. */
    private const val OFFER_SUMMARY_BASE = 0x47

    /**
     * The kind-2 summary bits a reader accepts: [OFFER_SUMMARY_BASE] plus the HEVC summary bit (`0x80`,
     * [VideoCodecMap]) while the HEVC master is on. With it off a `0x80` offer stays `Fields` (hidden), as before.
     */
    fun offerSummaryMask(hevc: Boolean): Int =
        OFFER_SUMMARY_BASE or (if (hevc) VideoCodecMap.summaryBit(StreamProtocol.CODEC_HEVC) else 0)

    fun decode(bytes: ByteArray, hevc: Boolean = VideoCodecSwitches.hevc): DiscoveryAnnouncement {
        fun fail(error: PcAnnouncementError): Nothing = throw PcAnnouncementException(error, "Invalid PC stream announcement")
        if (bytes.isEmpty()) fail(PcAnnouncementError.Length)
        val first = bytes[0].toInt() and 0xff
        if (first ushr 4 != 1) fail(PcAnnouncementError.Version)
        val kind = first and 0xf
        if (kind !in 1..2) fail(PcAnnouncementError.Kind)
        if (bytes.size != 23) fail(PcAnnouncementError.Length)
        val a = try { DiscoveryAnnouncementCodec.decode(bytes.copyOfRange(1, 23), acceptRotating = true) }
            catch (_: IllegalArgumentException) { fail(PcAnnouncementError.Fields) }
        if (a.version != 5 || a.streamToken >= 0) fail(PcAnnouncementError.Fields)
        val valid = if (kind == 1) {
            val plain = a.flags == 0x09 && a.recoveryFromNodeIdPrefix == null &&
                a.sequence == 0 && a.streamWidth == 0 && a.streamHeight == 0 && a.streamFps == 0
            val recovering = a.flags == 0x89 && a.recoveryFromNodeIdPrefix?.any { it != 0.toByte() } == true
            (plain || recovering) && a.capabilitySummary == 0
        } else {
            a.flags and 0x20.inv() == 0x0c && a.capabilitySummary and offerSummaryMask(hevc).inv() == 0 &&
                a.routeCost == 0 && a.recoveryFromNodeIdPrefix == null
        }
        if (!valid) fail(PcAnnouncementError.Fields)
        return a
    }
}

/**
 * Rotating IDs of PC call offers (kind 0) seen recently, so a call attempt knows its target is a PC. A PC answers
 * only BMH3 (it refuses a BMH2 hello as `Format`), so a phone with BMH3 ("Hide my ID from strangers") off does not
 * dial it: the attempt would hand the PC this phone's signed BMH2 hello and fail anyway (PROTOCOL "Experimental PC
 * announcement", "Calls to a PC"). Phone targets are unaffected: a phone with BMH3 off still accepts BMH2.
 * Bounded (64 IDs, 30 minutes: two epochs).
 */
internal object PcCallTargets {
    private const val CAPACITY = 64
    private const val TTL_MS = 30 * 60_000L
    private val seen = LinkedHashMap<String, Long>()

    const val BMH3_OFF_MESSAGE =
        "This PC takes calls only with \"Hide my ID from strangers\" on (Settings > Privacy > Advanced privacy)."

    private fun key(prefix: ByteArray) = prefix.joinToString("") { "%02x".format(it) }

    @Synchronized
    fun record(prefix: ByteArray, nowMs: Long) {
        val k = key(prefix)
        seen.remove(k)
        seen[k] = nowMs
        while (seen.size > CAPACITY) seen.remove(seen.keys.first())
    }

    @Synchronized
    fun isPc(prefix: ByteArray, nowMs: Long): Boolean = seen[key(prefix)]?.let { nowMs - it in 0..TTL_MS } == true

    @Synchronized
    fun clear() = seen.clear()

    /** True when an attempt toward [offer] must not start: BMH3 off and the offer is a PC's rotating call offer. */
    fun refusesBmh2Dial(bmh3: Boolean, offer: DiscoveryAnnouncement?, isPc: Boolean): Boolean =
        !bmh3 && offer != null && offer.rotating && isPc
}

/** A call attempt toward a PC was not started because BMH3 is off ([PcCallTargets.refusesBmh2Dial]). */
internal class PcNeedsBmh3Exception : IllegalStateException(PcCallTargets.BMH3_OFF_MESSAGE)

/** Per-address log rate limit (bounded), so a flood of adverts cannot flood the log. */
internal class PcLogLimiter(private val intervalMs: Long = 10_000L, private val maxAddresses: Int = 64) {
    private val last = LinkedHashMap<String, Long>()

    fun allow(key: String, nowMs: Long): Boolean {
        val previous = last[key]
        if (previous != null && nowMs - previous in 0 until intervalMs) return false
        last.remove(key)
        last[key] = nowMs
        while (last.size > maxAddresses) last.remove(last.keys.first())
        return true
    }
}
