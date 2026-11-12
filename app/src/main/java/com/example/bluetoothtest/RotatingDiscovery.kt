package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Rotating discovery IDs (on by default since 0.2.0-beta for user launches; a DEBUG benchmark launch reads only
 * `test_rotating_ids`; Advanced setting "Rotating discovery IDs" is the off-switch). PROTOCOL "Experimental rotating
 * discovery IDs" owns the wire rules; this file holds the pure, JVM-tested parts (reviewed design RV1-RV19).
 *
 * Phase 1 (strangers only): call-ready presence (role 0) advertises announcement version 5 whose
 * 6 prefix bytes are a 15-minute rotating ID (RDI) instead of the fixed node prefix. A caller that
 * selected a version 5 offer requires the link opening `BDO1` (hop type 13, link hello bit 3) and
 * checks it against the authenticated node ID (RV15). Streams, viewer requests, forwarders and
 * relay chains keep version 2 in this phase (PLAN "Discovery privacy"). Off: every byte on air and
 * on the link is unchanged, and version 5 adverts are rejected exactly as by old builds.
 */
object RotatingDiscoveryFeature {
    const val EXTRA = "test_rotating_ids"

    /**
     * The one default constant (SWITCHES register "Rotating discovery IDs"): what a user launch without a stored choice
     * uses. A DEBUG benchmark launch (any `test_` extra) never reads it and uses [EXTRA] only, as every promoted feature
     * does ([FeatureCatalog.resolve]). The neutral Bluetooth name is a separate option and stays off by default.
     */
    const val DEFAULT_ENABLED = true

    /**
     * Resolved by the shared rule ([FeatureCatalog.resolve]) as a constant: no Settings switch since 2026-11-04 (owner
     * decision), and the "off" that 0.4.4 and older stored under `experimental_rotating_ids` is ignored, because a phone
     * with it off could not find phones that had it on (release 0.4.4, two phones: "No one found yet" while the other phone
     * listed it). `bmh3_pseudonyms` and `find_pcs` depend on it; the DEBUG benchmark arm [EXTRA] stays.
     */
    val spec = FeatureSpec(
        id = "rotating_ids",
        prefKey = null,
        defaultEnabled = DEFAULT_ENABLED,
        benchmarkExtra = EXTRA,
        group = ConnectivityFeatures.GROUP,
        phoneTest = "RV",
    )

    /** The value in force since the last `applyRotatingIds` (endpoint and scan code read it). */
    @Volatile var enabled = false
}

/** The reviewed RDI construction (design section 3). All integers big-endian. */
object RotatingDiscoveryIds {
    /** The epoch length of every release and every non-benchmark launch (PROTOCOL "Keys and epochs"). */
    const val EPOCH_SECONDS = 900L

    /**
     * The epoch length in force in this process: [EPOCH_SECONDS] unless a DEBUG launch of a debuggable package gave
     * `test_rotid_epoch_s=<60..900>` at cold start ([DiscoveryEpochLength], harness only). It is set once before rotating
     * discovery starts and never changes afterwards; the wire format does not carry it, so two phones must use the same value.
     */
    @Volatile var epochSeconds: Long = EPOCH_SECONDS
        internal set

    /** Epochs in one real-time day (96 at the default); presence-lease days stay real days at any epoch length. */
    val epochsPerDay: Long get() = 86_400L / epochSeconds
    const val ID_BYTES = DiscoveryAnnouncement.NODE_PREFIX_BYTES
    const val SALT_BYTES = 32
    /** The size of a day key `L_d` (and of the Phase 1 presence key `P` that is only read for migration). */
    const val PRESENCE_KEY_BYTES = 32

    const val ROLE_CALL_OFFER = 0
    const val ROLE_STREAM_OFFER = 1
    const val ROLE_VIEWER_REQUEST = 2

    /** Naming v2 (Visible by name): the dedicated window ID, reserved for the window offer; unlinkable to roles 0 and 1. */
    const val ROLE_WINDOW_OFFER = 3

    /** The role whose ID [selected] carries: the window offer's role 3, else the call offer's role 0. */
    fun roleFor(selected: DiscoveryAnnouncement): Int = if (selected.isWindowOffer) ROLE_WINDOW_OFFER else ROLE_CALL_OFFER

    /** Link hello transport-mask bit 3: "sends and accepts `BDO1` as the first hop record". */
    const val LINK_HELLO_BIT = 1 shl 3

    val SALT_DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Salt/1".toByteArray(Charsets.US_ASCII) + 0
    val ID_DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Id/1".toByteArray(Charsets.US_ASCII) + 0

    fun wallEpoch(unixSeconds: Long): Long = Math.floorDiv(unixSeconds, epochSeconds)

    /** Milliseconds from [nowMs] (discovery wall clock) to the next epoch boundary, in 1..epoch length. */
    fun msUntilBoundary(nowMs: Long): Long {
        val epochMs = epochSeconds * 1000L
        return epochMs - Math.floorMod(nowMs, epochMs)
    }

    fun salt(presenceKey: ByteArray, epoch: Long, role: Int): ByteArray {
        require(presenceKey.size == PRESENCE_KEY_BYTES) { "Presence key must be 32 bytes" }
        require(epoch in 0..0xffffffffL && role in 0..0xff)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(presenceKey, "HmacSHA256"))
            update(SALT_DOMAIN)
            update(u32(epoch))
            update(role.toByte())
            doFinal()
        }
    }

    fun rdi(nodeId: ByteArray, epoch: Long, role: Int, salt: ByteArray): ByteArray {
        require(nodeId.size == NodeId.BYTE_COUNT) { "Node ID must be 16 bytes" }
        require(salt.size == SALT_BYTES) { "Salt must be 32 bytes" }
        require(epoch in 0..0xffffffffL && role in 0..0xff)
        return MessageDigest.getInstance("SHA-256").run {
            update(ID_DOMAIN)
            update(nodeId)
            update(u32(epoch))
            update(role.toByte())
            update(salt)
            digest()
        }.copyOf(ID_BYTES)
    }

    /** The opening entry for ([epoch], [role]) with [saltKey] as the salt key (a lease key, or a vector's P). */
    fun entry(saltKey: ByteArray, nodeId: ByteArray, epoch: Long, role: Int): OpeningEntry {
        val salt = salt(saltKey, epoch, role)
        return OpeningEntry(rdi(nodeId, epoch, role, salt), epoch, role, salt)
    }

    /**
     * The entry this phone publishes: salts are keyed by the day key `L_d` of the epoch's day ([PresenceVault.dayKey]:
     * an independent random key, RV11, RD5), so contacts holding leases compute the same IDs (phase 2).
     */
    fun ownEntry(dayKey: ByteArray, nodeId: ByteArray, epoch: Long, role: Int): OpeningEntry =
        entry(dayKey, nodeId, epoch, role)

    internal fun u32(value: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        .putInt(value.toInt()).array()
}

/** `RDI (6) | epoch (4) | role (1) | salt (32)`: one opening entry (design 5.2/6). */
class OpeningEntry(rdi: ByteArray, val epoch: Long, val role: Int, salt: ByteArray) {
    private val rdiBytes = rdi.copyOf()
    private val saltBytes = salt.copyOf()

    init {
        require(rdi.size == RotatingDiscoveryIds.ID_BYTES)
        require(salt.size == RotatingDiscoveryIds.SALT_BYTES)
        require(epoch in 0..0xffffffffL && role in 0..0xff)
    }

    val rdi: ByteArray get() = rdiBytes.copyOf()
    val salt: ByteArray get() = saltBytes.copyOf()

    /** True when this entry opens [selected] for [nodeId] (recomputed, never trusted). */
    fun opens(selected: ByteArray, nodeId: ByteArray, expectedRole: Int): Boolean =
        role == expectedRole && rdiBytes.contentEquals(selected) &&
            RotatingDiscoveryIds.rdi(nodeId, epoch, role, saltBytes).contentEquals(selected)

    override fun equals(other: Any?): Boolean = other is OpeningEntry && epoch == other.epoch &&
        role == other.role && rdiBytes.contentEquals(other.rdiBytes) && saltBytes.contentEquals(other.saltBytes)

    override fun hashCode(): Int = (rdiBytes.contentHashCode() * 31 + epoch.hashCode()) * 31 + role

    companion object {
        const val BYTES = 43
    }
}

/** Link opening `"BDO1" | version 1 | count (0..2) | count x entry (43)` (design section 6). */
object DiscoveryOpeningCodec {
    val MAGIC: ByteArray = "BDO1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val MAX_ENTRIES = 2

    fun encode(entries: List<OpeningEntry>): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "At most two opening entries" }
        val out = ByteArrayOutputStream(MAGIC.size + 2 + entries.size * OpeningEntry.BYTES)
        out.write(MAGIC)
        out.write(VERSION)
        out.write(entries.size)
        entries.forEach { entry ->
            out.write(entry.rdi)
            out.write(RotatingDiscoveryIds.u32(entry.epoch))
            out.write(entry.role)
            out.write(entry.salt)
        }
        return out.toByteArray()
    }

    /** Strict decode: any other magic, version, count or length throws. */
    fun decode(bytes: ByteArray): List<OpeningEntry> {
        require(bytes.size >= MAGIC.size + 2) { "Opening too short" }
        require(bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) { "Not a BDO1 opening" }
        require(bytes[4].toInt() and 0xff == VERSION) { "Unsupported opening version" }
        val count = bytes[5].toInt() and 0xff
        require(count <= MAX_ENTRIES) { "Too many opening entries" }
        require(bytes.size == MAGIC.size + 2 + count * OpeningEntry.BYTES) { "Invalid opening length" }
        val data = ByteBuffer.wrap(bytes, MAGIC.size + 2, count * OpeningEntry.BYTES).order(ByteOrder.BIG_ENDIAN)
        return List(count) {
            val rdi = ByteArray(RotatingDiscoveryIds.ID_BYTES).also(data::get)
            val epoch = data.int.toLong() and 0xffffffffL
            val role = data.get().toInt() and 0xff
            val salt = ByteArray(RotatingDiscoveryIds.SALT_BYTES).also(data::get)
            OpeningEntry(rdi, epoch, role, salt)
        }
    }
}

/**
 * RV15: the check follows the advert the caller selected, never the peer's hello. A selected
 * version 5 (or compact v2) advert requires link bit 3 and a matching opening; a selected version
 * 2 advert uses the prefix check, whatever the hello says.
 */
object DiscoverySelectionCheck {
    enum class Result { PASS, PREFIX_MISMATCH, OPENING_MISSING, OPENING_MISMATCH, OPENING_STALE }

    /**
     * RD4: an entry's epoch must lie within this many epochs of the verifier's own wall-clock epoch. The
     * salt is free for a forger, so without a bound one 2^48 table built for a fixed epoch would invert any
     * victim ID of any epoch; with it the cost is per target and epoch again (RV9).
     */
    const val EPOCH_TOLERANCE = 2L

    /** Seconds since the Unix epoch; tests replace it. */
    @Volatile internal var unixSeconds: () -> Long = ::systemUnixSeconds

    /** The discovery wall clock in seconds (real time unless a DEBUG launch shifted it, see [DiscoveryWallClock]). */
    internal fun systemUnixSeconds(): Long = DiscoveryWallClock.nowMs() / 1000L

    fun check(
        selected: DiscoveryAnnouncement,
        expectedRole: Int,
        peerNodeId: ByteArray,
        openingEntries: List<OpeningEntry>?,
    ): Result = if (!selected.rotating) {
        if (peerNodeId.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(selected.nodeIdPrefix)) {
            Result.PASS
        } else {
            Result.PREFIX_MISMATCH
        }
    } else {
        val now = RotatingDiscoveryIds.wallEpoch(unixSeconds())
        val opening = openingEntries?.filter { it.opens(selected.nodeIdPrefix, peerNodeId, expectedRole) }
        when {
            openingEntries == null -> Result.OPENING_MISSING
            opening.isNullOrEmpty() -> Result.OPENING_MISMATCH
            opening.any { Math.abs(it.epoch - now) <= EPOCH_TOLERANCE } -> Result.PASS
            else -> Result.OPENING_STALE
        }
    }
}

/**
 * RV4 monotonic epoch. A tick happens only when the wall epoch differs from the one seen at the
 * last tick, or [ELAPSED_TICK_MS] of elapsedRealtime passed since it (a stuck or rewound wall
 * clock still rotates). At a tick E = max(wallEpoch, lastEpoch + 1); any other call reuses E, so
 * restarts after calls, retries and defers never push E ahead. After a reboot (elapsed time not
 * comparable) E is reused only while the wall still shows the epoch of the last tick. An offset
 * E - wallEpoch above [MAX_AHEAD_EPOCHS] (the opening tolerance, 2 epochs: Q33, 2026-10-14; it was one day
 * before) that lasts [AHEAD_RECOVERY_MS] of consistent wall time starts a new presence generation and
 * resets E to the wall epoch. E never goes down, so a backward wall-clock step (a manual fix, a time zone or
 * NITZ correction, an NTP step) leaves E ahead for good; beyond the tolerance every opening would fail
 * OPENING_STALE and contacts could not recognise the phone, so the new generation (contacts re-sync) is
 * the recovery. A lead within the tolerance, or a wall clock that is itself still changing, starts nothing.
 */
data class EpochClockState(
    val lastEpoch: Long = -1L,
    val tickWallEpoch: Long = -1L,
    val tickElapsedMs: Long = 0L,
    val bootId: String = "",
    val generation: Long = 1L,
    /**
     * The epoch length [lastEpoch] was counted in. A different length makes the stored counter meaningless (a 60 s
     * counter is 15 times ahead of a 900 s wall epoch), so the clock restarts it; the generation is kept.
     */
    val epochSeconds: Long = RotatingDiscoveryIds.EPOCH_SECONDS,
)

class DiscoveryEpochClock(
    initial: EpochClockState,
    private val bootId: String,
    private val persist: (EpochClockState) -> Unit,
) {
    var state: EpochClockState = if (initial.epochSeconds == RotatingDiscoveryIds.epochSeconds) {
        initial
    } else {
        // DEBUG short epochs (test_rotid_epoch_s) and later normal launches share one store: never carry E across lengths.
        EpochClockState(generation = initial.generation, epochSeconds = RotatingDiscoveryIds.epochSeconds).also(persist)
    }
        private set
    private var aheadSinceElapsedMs: Long? = null
    private var aheadWallOffsetMs = 0L

    /** Epoch to advertise now. Returns true in [Reading.newGeneration] once per recovery. */
    data class Reading(val epoch: Long, val ticked: Boolean, val newGeneration: Boolean)

    /** Another store (the presence vault) already used generation [value]: never start at or below it. */
    @Synchronized
    fun raiseGeneration(value: Long) {
        if (value <= state.generation) return
        state = state.copy(generation = value)
        persist(state)
    }

    @Synchronized
    fun read(wallMs: Long, elapsedMs: Long): Reading {
        val wall = RotatingDiscoveryIds.wallEpoch(Math.floorDiv(wallMs, 1000L))
        var current = state
        var newGeneration = false
        val tick = when {
            current.lastEpoch < 0 -> true
            current.bootId != bootId -> wall != current.tickWallEpoch
            else -> wall != current.tickWallEpoch || elapsedMs - current.tickElapsedMs >= ELAPSED_TICK_MS
        }
        // The lead is measured on the epoch this read would advertise: the first read after a wall boundary sees
        // the lead one epoch short before it ticks, which would otherwise restart the hour at every boundary.
        val advertised = if (tick) maxOf(wall, current.lastEpoch + 1) else current.lastEpoch
        if (current.lastEpoch >= 0 && advertised - wall > MAX_AHEAD_EPOCHS) {
            val offset = wallMs - elapsedMs
            val since = aheadSinceElapsedMs
            if (since == null || Math.abs(offset - aheadWallOffsetMs) > CONSISTENT_WALL_TOLERANCE_MS) {
                aheadSinceElapsedMs = elapsedMs
                aheadWallOffsetMs = offset
            } else if (elapsedMs - since >= AHEAD_RECOVERY_MS) {
                current = EpochClockState(wall, wall, elapsedMs, bootId, current.generation + 1, RotatingDiscoveryIds.epochSeconds)
                state = current
                persist(current)
                aheadSinceElapsedMs = null
                newGeneration = true
                return Reading(wall, ticked = true, newGeneration = true)
            }
        } else {
            aheadSinceElapsedMs = null
        }
        if (!tick) {
            if (current.bootId != bootId) {
                // Same wall epoch after a reboot: keep E, adopt this boot's elapsed base.
                current = current.copy(tickElapsedMs = elapsedMs, bootId = bootId)
                state = current
                persist(current)
            }
            return Reading(current.lastEpoch, ticked = false, newGeneration = newGeneration)
        }
        val epoch = advertised
        current = current.copy(lastEpoch = epoch, tickWallEpoch = wall, tickElapsedMs = elapsedMs, bootId = bootId)
        state = current
        persist(current)
        return Reading(epoch, ticked = true, newGeneration = newGeneration)
    }

    companion object {
        /** One epoch plus a minute, so a normal wall boundary always ticks first (no early double tick). */
        val ELAPSED_TICK_MS: Long get() = (RotatingDiscoveryIds.epochSeconds + 60L) * 1000L

        /**
         * The lead of E over the wall epoch that starts the recovery clock: the verifier's opening tolerance in
         * epochs (2), because beyond it this phone's own openings are stale (RD4). Counted in epochs at every
         * epoch length, like the tolerance.
         */
        val MAX_AHEAD_EPOCHS: Long get() = DiscoverySelectionCheck.EPOCH_TOLERANCE
        const val AHEAD_RECOVERY_MS = 3_600_000L
        const val CONSISTENT_WALL_TOLERANCE_MS = 120_000L
    }
}

/**
 * Section 6 pin rule: a phone opens only an ID of the role that is on air, or that left the air less
 * than [PREVIOUS_GRACE_MS] earlier (the connection was accepted under it, or the boundary just
 * passed). An ID that went off air long ago (a long call, a Visible-by-handle window, an absence)
 * is never opened again, and an arbitrary or older epoch is never opened on request.
 */
class OpeningPins {
    private var current: OpeningEntry? = null
    private var currentOffAirMs: Long? = null
    private var previous: OpeningEntry? = null
    private var previousOffAirMs = Long.MIN_VALUE / 2

    @Synchronized
    fun onAdvertised(entry: OpeningEntry, elapsedMs: Long) {
        if (current == entry) {
            currentOffAirMs = null
            return
        }
        current?.let { old ->
            previous = old
            previousOffAirMs = currentOffAirMs ?: elapsedMs
        }
        current = entry
        currentOffAirMs = null
    }

    /** The role's advertising stopped (connection accepted, boundary silence, window, close). */
    @Synchronized
    fun offAir(elapsedMs: Long) {
        if (current != null && currentOffAirMs == null) currentOffAirMs = elapsedMs
    }

    @Synchronized
    fun pinned(elapsedMs: Long): List<OpeningEntry> = listOfNotNull(
        current?.takeIf { currentOffAirMs.let { off -> off == null || elapsedMs - off < PREVIOUS_GRACE_MS } },
        previous?.takeIf { elapsedMs - previousOffAirMs < PREVIOUS_GRACE_MS },
    )

    companion object {
        const val PREVIOUS_GRACE_MS = 60_000L
    }
}

/**
 * "Visible by handle" window (design decisions, design 8.1, RV5): call-ready presence advertises
 * version 2 (the fixed prefix, visible to old builds and to anyone who knows the handle) from the
 * start until the first epoch boundary at least [MIN_SECONDS] later, so a window lasts 10-25 min. It
 * starts and ends on a new advertising set. Every window is linkable to every other window (same
 * prefix) and to the part of the epoch just before it.
 */
object VisibleByHandleWindow {
    const val MIN_SECONDS = 600L

    /** Upper bound by elapsed time (10 min + one epoch), whatever the wall clock does. */
    val MAX_MS: Long get() = (MIN_SECONDS + RotatingDiscoveryIds.epochSeconds) * 1000L

    /** The epoch whose start ends a window that began at [startUnixSeconds]. */
    fun endEpoch(startUnixSeconds: Long): Long =
        Math.floorDiv(startUnixSeconds + MIN_SECONDS + RotatingDiscoveryIds.epochSeconds - 1, RotatingDiscoveryIds.epochSeconds)

    fun isActive(endEpoch: Long, nowUnixSeconds: Long): Boolean =
        endEpoch >= 0 && RotatingDiscoveryIds.wallEpoch(nowUnixSeconds) < endEpoch
}

/**
 * RV8 tarpit (replaces the lockout): the responder delays its hello by 2^(n-6) s, capped at 30 s,
 * for the n-th stranger handshake in the epoch that has not led to a user-visible action. Verified
 * contacts are never delayed. It bounds mass probing and battery, not privacy.
 */
class StrangerTarpit {
    private var epoch = -1L
    private var probes = 0

    /** Delay before this stranger's hello; counts it as a probe until [forgive] is called. */
    @Synchronized
    fun nextDelayMs(currentEpoch: Long): Long {
        if (currentEpoch != epoch) {
            epoch = currentEpoch
            probes = 0
        }
        probes++
        return delayMs(probes)
    }

    /** The handshake led to a user-visible action (an incoming call rang): it was not a probe. */
    @Synchronized
    fun forgive(currentEpoch: Long) {
        if (currentEpoch == epoch && probes > 0) probes--
    }

    companion object {
        const val CAP_MS = 30_000L

        fun delayMs(n: Int): Long {
            if (n <= 0) return 0L
            val exponent = n - 6
            if (exponent >= 15) return CAP_MS
            val ms = if (exponent >= 0) 1000L shl exponent else 1000L shr -exponent
            return minOf(ms, CAP_MS)
        }
    }
}

/**
 * Section 4.3 recovery hint with RV18 key separation and RD3 binding: `Khint = HKDF-SHA256(Kmedia, salt =
 * 32 zero bytes, info = "…/Recovery/2")`, hint = HMAC-SHA256(Khint, domain || 0 || failedNodeId (16) ||
 * u32 logicalToken || viewer RDI (6) || u32 routeToken)[0:6]. Binding the request's own RDI and token makes
 * the hint change every epoch (nothing constant crosses a boundary) and useless when copied into another
 * advert. Phase 1b viewer requests.
 */
object RecoveryHint {
    val DOMAIN: ByteArray = "BluetoothVideoMesh/Discovery/Recovery/2".toByteArray(Charsets.US_ASCII)

    fun hintKey(mediaKey: ByteArray): ByteArray = hkdfSha256(mediaKey, ByteArray(32), DOMAIN, 32)

    fun hint(
        key: ByteArray,
        failedNodeId: ByteArray,
        logicalToken: Int,
        viewerRdi: ByteArray,
        routeToken: Int,
    ): ByteArray {
        require(failedNodeId.size == NodeId.BYTE_COUNT)
        require(viewerRdi.size == RotatingDiscoveryIds.ID_BYTES)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(DOMAIN + 0)
            update(failedNodeId)
            update(RotatingDiscoveryIds.u32(logicalToken.toLong() and 0xffffffffL))
            update(viewerRdi)
            update(RotatingDiscoveryIds.u32(routeToken.toLong() and 0xffffffffL))
            doFinal()
        }.copyOf(RotatingDiscoveryIds.ID_BYTES)
    }

    internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        val out = ByteArrayOutputStream(length)
        var previous = byteArrayOf()
        var counter = 1
        while (out.size() < length) {
            previous = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(previous)
                update(info)
                update(counter.toByte())
                doFinal()
            }
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}

/**
 * The option "Hide phone name" (PROTOCOL "Pre-handshake exposure"). Default on since 2026-10-27 (DECISIONS "Hide phone
 * name on by default"); the phone is renamed only after the user answered [NeutralNameChoice]'s question.
 */
object NeutralNameOption {
    /** The user's explicit choice (Settings > Privacy, or the onboarding question); independent of the rotating switch. */
    const val PREF = "neutral_bluetooth_name"

    /** DEBUG launch extra; [EXTRA_ALIAS] is the earlier name (it renamed with the rotating switch) kept so launch lines keep working. */
    const val EXTRA = "test_neutral_bluetooth_name"
    const val EXTRA_ALIAS = "test_rotating_neutral_name"

    /**
     * A DEBUG benchmark launch changes the name only with its extra (or the alias), default off; otherwise the stored
     * choice, else on, with or without the Advanced view. Resolved by [FeatureCatalog.resolve]; its switch is the
     * Settings > Privacy row. Off-switch: the stored choice (Settings, onboarding "Keep my name").
     */
    val spec = FeatureSpec(
        id = "neutral_bluetooth_name",
        prefKey = PREF,
        defaultEnabled = true,
        benchmarkExtra = EXTRA,
        extraAliases = listOf(EXTRA_ALIAS),
        group = ConnectivityFeatures.GROUP,
    )
}

/**
 * The explicit option "Use a neutral Bluetooth name ("Phone")" (PROTOCOL "Pre-handshake exposure", revised
 * 2026-10-11; the earlier automatic rename with the rotating switch is withdrawn). While the option is on the
 * adapter name (served as the GAP Device Name to any central that connects, before any app handshake, and to
 * Classic name requests) is [NEUTRAL]; the user's own name is saved and restored when it is turned off.
 * Pure decisions; the Android side applies them, only when the option changes and at app start or resume,
 * never per call, advertising session or epoch.
 */
object NeutralBluetoothName {
    /** The same for every phone using the option, so it identifies nobody. */
    const val NEUTRAL = "Phone"

    /**
     * [setName]: write this name; [saveOriginal]: remember this as the user's own name; [clearSaved]: forget the saved
     * name; [pause]: the user chose another name after the option had been applied, so the option is switched off and
     * their name is left alone.
     */
    data class Action(val setName: String?, val saveOriginal: String?, val clearSaved: Boolean, val pause: Boolean = false)

    /**
     * A name that can be the user's own: not blank and not [NEUTRAL]. The neutral name is never saved as "the
     * original" (release recheck 2026-10-28: a phone left called "Phone" with nothing to restore).
     */
    fun ownName(name: String?): String? = name?.takeIf { it.isNotBlank() && it != NEUTRAL }

    /** A suggestion for the user's own name when none was saved: Android's device name, else the model. */
    fun suggestedOwnName(deviceName: String?, model: String?): String? =
        ownName(deviceName?.trim()) ?: ownName(model?.trim())

    /**
     * The phone reads [NEUTRAL] but no own name is saved to restore (app data cleared, or the lost-original state of
     * the 0.4.1 release candidate): Settings offers to rename the phone instead of leaving it stuck.
     */
    fun needsOwnName(currentName: String?, savedOriginal: String?): Boolean =
        currentName == NEUTRAL && ownName(savedOriginal) == null

    /**
     * [currentName] is the adapter's name now (null: unreadable or Bluetooth off), [savedOriginal] the saved copy,
     * [applied] whether the adapter has been READ as [NEUTRAL] under this option, and [pending] a name the app wrote
     * that the adapter has not reported back yet. Android applies a rename asynchronously (AOSP AdapterProperties:
     * setName() returns once the request is queued; getName() changes only in the stack's property callback, which
     * also sends ACTION_LOCAL_NAME_CHANGED), so a read right after a write can still return the old name. Before the
     * name was read back a name other than [NEUTRAL] is the user's own (also after a failed attempt); after it, any
     * other name is the user's later choice and wins: the option pauses instead of fighting a deliberate rename.
     */
    fun onEnabled(currentName: String?, savedOriginal: String?, applied: Boolean = false, pending: String? = null): Action {
        val saved = ownName(savedOriginal)
        return when {
            currentName == null -> Action(null, null, false)
            // A restore is still on its way, so "Phone" read now may be stale: write it again, after the restore.
            pending != null && pending != NEUTRAL -> Action(NEUTRAL, saved ?: ownName(pending), false)
            currentName == NEUTRAL -> Action(null, null, false)
            applied -> Action(null, null, clearSaved = true, pause = true)
            else -> Action(NEUTRAL, ownName(currentName) ?: saved, false)
        }
    }

    /**
     * Restores the saved name over our own [NEUTRAL]; a name the user chose since then is kept. The saved copy stays
     * until the adapter reads it back ([clearSaved] false while restoring), and a "Phone" write not reported back yet
     * ([pending] = [NEUTRAL]) is followed by the restore even while the old name still reads.
     */
    fun onDisabled(currentName: String?, savedOriginal: String?, pending: String? = null): Action {
        val saved = ownName(savedOriginal) ?: return Action(null, null, savedOriginal != null)
        return when {
            currentName == null -> Action(null, null, false)
            currentName == NEUTRAL || (pending == NEUTRAL && currentName == saved) -> Action(saved, null, false)
            else -> Action(null, null, true)
        }
    }
}
