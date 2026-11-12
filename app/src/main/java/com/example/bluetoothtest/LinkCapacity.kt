package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Radio features this phone reports about itself. Android exposes no API for link throughput,
 * controller airtime sharing or concurrent-link limits, so those are measured ([LinkCapacity]).
 */
data class PhoneRadioFeatures(
    val le2MPhy: Boolean,
    val leCodedPhy: Boolean,
    val leExtendedAdvertising: Boolean,
    val multipleAdvertisement: Boolean,
    val maxAdvertisingDataBytes: Int,
    val leCoc: Boolean,
) {
    val summary: String
        get() = "LE 2M ${yesNo(le2MPhy)}, Coded ${yesNo(leCodedPhy)}, extended adv ${yesNo(leExtendedAdvertising)}, " +
            "multi adv ${yesNo(multipleAdvertisement)}, adv data $maxAdvertisingDataBytes B, LE CoC ${yesNo(leCoc)}"

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    companion object {
        @SuppressLint("MissingPermission")
        fun read(adapter: BluetoothAdapter): PhoneRadioFeatures = PhoneRadioFeatures(
            le2MPhy = runCatching { adapter.isLe2MPhySupported }.getOrDefault(true),
            leCodedPhy = runCatching { adapter.isLeCodedPhySupported }.getOrDefault(false),
            leExtendedAdvertising = runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false),
            multipleAdvertisement = runCatching { adapter.isMultipleAdvertisementSupported }.getOrDefault(true),
            maxAdvertisingDataBytes = runCatching { adapter.leMaximumAdvertisingDataLength }.getOrDefault(31),
            leCoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
    }
}

/**
 * Aggregate wire throughput (all simultaneous media links, received plus sent) this phone has
 * measured on one transport.
 */
data class LearnedLinkCapacity(
    /** Highest aggregate carried for at least two consecutive windows without saturation. */
    val provenKbps: Int = 0,
    /** Aggregate at which simultaneous links last saturated (or an explicit probe maximum). */
    val ceilingKbps: Int? = null,
    val ceilingMeasuredAtMs: Long = 0L,
    /** Best single-link maximum from saturating probes with any peer (a weak peer cannot lower it). */
    val probeKbps: Int = 0,
    val probeMeasuredAtMs: Long = 0L,
)

internal object LinkCapacityPolicy {
    const val CEILING_HEADROOM = 0.9
    /**
     * A saturation ceiling measures the radio's multi-link airtime (the M31's 428/774 kbps were real:
     * clearing them admitted a relay and a second viewer it could not serve, trend-3641df77). It is
     * re-checked every 6 hours; a failed re-check is undone within seconds by the fan-out rollback
     * and relay child withdrawal, and records the ceiling again.
     */
    const val CEILING_TTL_MS = 6L * 60 * 60 * 1_000
    /** A probe maximum measures the phone itself and stays valid longer. */
    const val PROBE_TTL_MS = 7L * 24 * 60 * 60 * 1_000
    /** A sustained per-peer rate only seeds the next start; rate control climbs from it. */
    const val SUSTAINED_TTL_MS = 24L * 60 * 60 * 1_000

    /**
     * With a fresh ceiling the limit is the larger of the load carried cleanly since then and the
     * ceiling with headroom. Without one the phone may carry the larger of the prior and one more
     * link beyond what it has proven, so capable phones grow past the prior one link at a time and
     * a saturation records where they stop.
     */
    fun budgetKbps(
        learned: LearnedLinkCapacity,
        priorKbps: Double,
        perLinkKbps: Double,
        nowMs: Long,
        provenScale: Double = 1.0,
    ): Double {
        val ceiling = freshCeiling(learned, nowMs)
        return if (ceiling != null) {
            max(learned.provenKbps.toDouble(), ceiling * CEILING_HEADROOM)
        } else {
            max(priorKbps, learned.provenKbps * provenScale + perLinkKbps)
        }
    }

    /**
     * Proven load is measured wire kbps (video + audio + headers as carried); a planned link is that
     * load plus [MeshBandwidthEstimator.TRANSPORT_MARGIN]. Growing "one link beyond proven" must use
     * one unit: mesh4-c0200edf-loop7-1 0002, the ASUS carried two children cleanly at 737 kbps, but
     * 737 + 403.5 < 3 x 403.5, so a third child never fit however good the link. Scaling proven into
     * planned units (737 x 1.2 = 884, + 403.5 = 1288) admits it; a fresh ceiling still governs, and a
     * saturated third link records one through the existing rollback. Fan-out only.
     */
    fun fanOutProvenScale(kind: LinkLoadKind, enabled: Boolean): Double =
        if (enabled && kind == LinkLoadKind.FAN_OUT) 1 + MeshBandwidthEstimator.TRANSPORT_MARGIN else 1.0

    /** Experimental learned-limit recovery: first cool-down after a ceiling, doubled per failed probe. */
    const val CEILING_PROBE_COOLDOWN_MS = 2L * 60 * 1_000
    const val CEILING_PROBE_MAX_DOUBLINGS = 5

    fun ceilingProbeCooldownMs(failures: Int): Long =
        CEILING_PROBE_COOLDOWN_MS shl failures.coerceIn(0, CEILING_PROBE_MAX_DOUBLINGS)

    /** A fresh ceiling recorded at least one cool-down ago may admit one link beyond it. */
    fun ceilingProbeOpen(learned: LearnedLinkCapacity, failures: Int, nowMs: Long): Boolean =
        freshCeiling(learned, nowMs) != null && nowMs - learned.ceilingMeasuredAtMs >= ceilingProbeCooldownMs(failures)

    /**
     * Recovery arm: while the probe is open the budget is the ceiling budget plus exactly one more
     * link; otherwise [budgetKbps]. Admission still compares the need against this budget, and the
     * existing rollback/withdrawal guards undo a probe that saturates (which re-records the ceiling).
     */
    fun probingBudgetKbps(
        learned: LearnedLinkCapacity,
        priorKbps: Double,
        perLinkKbps: Double,
        nowMs: Long,
        probeOpen: Boolean,
        provenScale: Double = 1.0,
    ): Double {
        val base = budgetKbps(learned, priorKbps, perLinkKbps, nowMs, provenScale)
        return if (probeOpen && freshCeiling(learned, nowMs) != null) base + perLinkKbps else base
    }

    /**
     * Failed probes: a ceiling re-recorded while one was fresh counts one, a first ceiling starts at 0,
     * a cleared ceiling resets, and any other update keeps the count.
     */
    fun nextProbeFailures(beforeFresh: Boolean, rerecorded: Boolean, after: LearnedLinkCapacity, failures: Int): Int = when {
        after.ceilingKbps == null -> 0
        !rerecorded -> failures
        beforeFresh -> failures + 1
        else -> 0
    }

    /** No relay evidence either way: never relayed cleanly (proven 0) and no fresh relay ceiling. */
    fun relayColdStart(learned: LearnedLinkCapacity, nowMs: Long): Boolean =
        learned.provenKbps <= 0 && freshCeiling(learned, nowMs) == null

    /** [relayFloor] answers: no floor, the cold start, or the Experimental no-ceiling floor. */
    const val FLOOR_NONE = "none"
    const val FLOOR_COLD_START = "cold_start"
    const val FLOOR_NO_CEILING = "no_ceiling"

    /**
     * Which rule raises the relay budget to at least 2 x the per-link load ([relayBudgetKbps]): [FLOOR_COLD_START] when
     * [coldStartOn] (`test_relay_cold_start`, default on) and [relayColdStart]; else [FLOOR_NO_CEILING] when [noCeilingOn]
     * (Experimental `test_relay_no_ceiling_floor`, design decision 2026-11-06) and there is no fresh relay ceiling, whatever
     * was proven, so a phone that once relayed a small stream is not refused a larger one a never-measured phone would take;
     * else [FLOOR_NONE]. A fresh relay ceiling (a measured overload) always keeps the measured budget.
     */
    fun relayFloor(learned: LearnedLinkCapacity, nowMs: Long, coldStartOn: Boolean, noCeilingOn: Boolean): String = when {
        coldStartOn && relayColdStart(learned, nowMs) -> FLOOR_COLD_START
        noCeilingOn && freshCeiling(learned, nowMs) == null -> FLOOR_NO_CEILING
        else -> FLOOR_NONE
    }

    /**
     * Relay budget. At cold start the incoming link is already carried, so the relay may add exactly
     * one child: max(measured budget, incoming + one child link). Otherwise the measured budget.
     */
    fun relayBudgetKbps(measuredBudgetKbps: Double, perLinkKbps: Double, coldStart: Boolean): Double =
        if (coldStart) max(measuredBudgetKbps, 2 * perLinkKbps) else measuredBudgetKbps

    fun freshCeiling(learned: LearnedLinkCapacity, nowMs: Long): Int? =
        learned.ceilingKbps?.takeIf { nowMs - learned.ceilingMeasuredAtMs in 0 until CEILING_TTL_MS }

    fun freshProbe(learned: LearnedLinkCapacity, nowMs: Long): Int? =
        learned.probeKbps.takeIf { it > 0 && nowMs - learned.probeMeasuredAtMs in 0 until PROBE_TTL_MS }

    /**
     * The phone's own measured single-link maximum replaces the assumed prior once probed. Planned
     * per-link load already carries a 20% margin, so the clean probe maximum is not reduced again.
     */
    fun priorKbps(learned: LearnedLinkCapacity, assumedKbps: Double, nowMs: Long): Double =
        freshProbe(learned, nowMs)?.toDouble() ?: assumedKbps

    fun withProbe(learned: LearnedLinkCapacity, kbps: Int, nowMs: Long): LearnedLinkCapacity {
        val best = freshProbe(learned, nowMs)
        return if (best != null && best >= kbps) learned else learned.copy(probeKbps = kbps, probeMeasuredAtMs = nowMs)
    }

    fun withHealthy(learned: LearnedLinkCapacity, kbps: Int, nowMs: Long): LearnedLinkCapacity {
        val ceiling = freshCeiling(learned, nowMs)
        return learned.copy(
            provenKbps = max(learned.provenKbps, kbps),
            // Healthy traffic above a recorded ceiling means conditions improved.
            ceilingKbps = ceiling?.takeIf { kbps <= it },
            ceilingMeasuredAtMs = if (ceiling != null && kbps <= ceiling) learned.ceilingMeasuredAtMs else 0L,
        )
    }

    /** Simultaneous links saturated: this bounds the transport; older proof above it is stale. */
    fun withCeiling(learned: LearnedLinkCapacity, kbps: Int, nowMs: Long): LearnedLinkCapacity =
        learned.copy(
            provenKbps = min(learned.provenKbps, (kbps * CEILING_HEADROOM).toInt()),
            ceilingKbps = kbps,
            ceilingMeasuredAtMs = nowMs,
        )
}

/**
 * How simultaneous links share one radio. Fan-out sends to several children; a relay receives
 * one stream while forwarding it. They use airtime differently, so each is learned separately.
 */
enum class LinkLoadKind(val key: String, val label: String) {
    FAN_OUT("fanout", "fan-out"),
    RELAY("relay", "relay"),
}

enum class LinkDirection { SEND, RECEIVE }

internal enum class LinkWindowState { HEALTHY, SATURATED, PEER_LIMITED, IDLE }

/**
 * Classifies one meter window. Only simultaneous links describe this phone's shared airtime: one
 * saturated child among several healthy ones is limited by that peer, not by this phone.
 *
 * A relay with one saturated child is likewise limited by that child's link unless this phone's own
 * receiving also suffered ([radioLimited]: its playback degraded and it withdrew the child, see
 * ReceiverStreamForwarder.withdrawForLocalCapacity(congested = true)). Counting a slow child as the
 * radio's relay ceiling taught the ASUS 346 kbps from a 60-84 kbps pair-specific link to the A15s
 * (mesh3-smoke-c85a177c-1857), and it then refused every later relay (mesh4-915d7390: 807 kbps
 * needed, budget 311) although it had received at 1140 kbps and relayed 300 kbps cleanly.
 */
internal fun classifyLinkWindow(
    sendLinks: Int,
    receiveLinks: Int,
    saturatedSendLinks: Int,
    kbps: Int,
    radioLimited: Boolean = false,
): Pair<LinkLoadKind?, LinkWindowState> {
    val kind = when {
        sendLinks > 0 && receiveLinks > 0 -> LinkLoadKind.RELAY
        sendLinks >= 2 -> LinkLoadKind.FAN_OUT
        else -> null
    }
    val state = when {
        kind == null -> LinkWindowState.IDLE
        kind == LinkLoadKind.RELAY && (radioLimited || saturatedSendLinks >= 2) -> LinkWindowState.SATURATED
        kind == LinkLoadKind.FAN_OUT && saturatedSendLinks >= 2 -> LinkWindowState.SATURATED
        saturatedSendLinks > 0 -> LinkWindowState.PEER_LIMITED
        kbps > 0 -> LinkWindowState.HEALTHY
        else -> LinkWindowState.IDLE
    }
    return kind to state
}

/**
 * Per-transport capacity this phone has learned about itself, persisted across sessions. This phone's own capacity (probe,
 * proven load, ceilings per transport) stays in the [PREFS] preferences; nothing there names a peer. What is learned per
 * peer (the start rate a link to it sustained, the arrival rate it reported) names that peer by its 12-hex node prefix
 * and the time: audit 2026-11-10 SEC-05 moved it into a sealed memory ([peerRecords]).
 */
object LinkCapacity {
    private const val PREFS = "link_capacity"
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * Audit 2026-11-10 SEC-05: per-peer start rates (`<radio>.sustained2.<use>.<peer>`, value `bps` or `bps,build`) and
     * peer link reports (`<radio>.peerlink.<peer>`, value `kbps`), sealed with the verified-contacts Keystore key. A
     * record is dropped once older than [LinkCapacityPolicy.SUSTAINED_TTL_MS] (it is not read after that) and at most
     * [PEER_RECORDS_MAX] are kept. Plain entries an earlier build left in [PREFS] are moved here once and deleted (the
     * worker confirms the preferences file on the disk no longer names a peer). One store per process: a second attach
     * gets the same one.
     */
    @Volatile private var peerContext: Context? = null

    /** Looked up per use (re-review N3): after a wipe resumed in this process, the next use opens the new, empty store. */
    private val peerRecords: SealedRecords?
        get() = peerContext?.let(peerSlot::get)
    internal const val PEER_RECORDS_FILE = "link-peers.bin"
    internal const val PEER_RECORDS_MAX = 256
    private val peerSlot = SealedRecordsSlot(
        PEER_RECORDS_FILE, PEER_RECORDS_MAX, LinkCapacityPolicy.SUSTAINED_TTL_MS,
    ) { app -> plainPeerSource(app) }

    /** Opens the sealed per-peer memory (process start and [attach]); its disk work runs on the sealed-records worker. */
    internal fun openPeerRecords(context: Context): SealedRecords {
        peerContext = context.applicationContext
        return peerSlot.get(context)
    }
    @Volatile var features: PhoneRadioFeatures? = null
        private set
    /**
     * Identity of the installed app build. A sustained rate is what this build's rate control kept,
     * so a rate recorded by another build is ignored: A15s -> S24 AV1 600 kept starting at the 240 kbps
     * floor on a new build from cuts the old build made for false lateness (VMAF -3, 18-26 s climb;
     * regress-57484598-s24rerun). Physical probes and ceilings stay valid across builds.
     */
    @Volatile private var buildStamp = 0L

    fun attach(context: Context, adapter: BluetoothAdapter?) {
        WifiCoexistence.attach(context)
        BluetoothAudioCoexistence.attach(context)
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching { openPeerRecords(context) }
        AdaptivePhyMemory.attach(context)
        buildStamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        if (adapter != null) features = PhoneRadioFeatures.read(adapter)
        if (!WipeGuard.active) features?.let { prefs?.edit()?.putString("features", it.summary)?.apply() }
        Log.i(
            "BluetoothVideo",
            "Phone radio features: ${features?.summary ?: "unavailable"}; " +
                "Wi-Fi ${WifiCoexistence.connectedFrequencyMhz()?.let { "$it MHz" } ?: "not connected"}; " +
                BluetoothAudioCoexistence.benchmarkField(),
        )
        BluetoothTransportOption.entries.forEach { Log.i("BluetoothVideo", "Link capacity ${summary(it)}") }
    }

    /**
     * Version 2: a relay ceiling needs radio evidence (see classifyLinkWindow). Relay ceilings stored
     * before it may be one slow child's link and are ignored; fan-out ceilings were already attributed.
     */
    private const val CEILING_ATTRIBUTION_VERSION = 3

    /**
     * Version 3: fan-out ceilings recorded before adaptive PHY ignored fan-out windows can be its
     * misfire (mesh4-041da09f 0002: the ASUS moved both children to 1M and learned 244-254 kbps, then
     * admitted one child in mesh4-7a6c401c 0002 against a 1105 kbps probe). Older ceilings of either
     * kind are ignored; a real one is re-learned within seconds by the fan-out rollback.
     */
    internal fun usableCeiling(@Suppress("UNUSED_PARAMETER") kind: LinkLoadKind, version: Int): Boolean =
        version >= CEILING_ATTRIBUTION_VERSION

    /** Aggregate assumed before live links have proven or bounded [transport]. */
    fun priorKbps(transport: BluetoothTransportOption): Double {
        val le = transport != BluetoothTransportOption.CLASSIC_RFCOMM
        // GATT on 1M PHY measured 0.320 Mbps and unprimed CoC 1M 0.193 Mbps (DECISIONS).
        val experimentalPhyKbps = DebugTunables.experimentalBlePhy?.pacerPriorKbps(transport)?.toDouble()
        val assumed = experimentalPhyKbps
            ?: if (le && features?.le2MPhy == false) 300.0 else MeshBandwidthEstimator.safeAggregateKbps(transport)
        return LinkCapacityPolicy.priorKbps(learned(transport, LinkLoadKind.FAN_OUT), assumed, System.currentTimeMillis())
    }

    /** Capacity learned while 2.4 GHz Wi-Fi shares the radio is kept apart from clean-radio capacity. */
    private fun radioKey(transport: BluetoothTransportOption, carrier: CarrierKind = CarrierKind.BLUETOOTH): String =
        if (carrier.isWifi) {
            // A Wi-Fi carrier's key is its name alone: Bluetooth coexistence and PHY suffixes describe a Bluetooth link.
            carrier.name
        } else {
            // AP10 (WIFI-19): a live 2.4 GHz Wi-Fi Aware data path of this app shares the radio like a 2.4 GHz access point does.
            transport.name + (if (WifiCoexistence.sharesBluetoothBand() || WifiRadio.ledger.band24PathLive) ".wifi24" else "") +
                BluetoothAudioCoexistence.capacityKeySuffix() + // "" unless the Experimental test_bt_audio_capacity_key is on
                phyCapacityKey(transport, DebugTunables.experimentalBlePhy)
        }

    fun learned(
        transport: BluetoothTransportOption,
        kind: LinkLoadKind,
        carrier: CarrierKind = CarrierKind.BLUETOOTH,
    ): LearnedLinkCapacity {
        val store = prefs ?: return LearnedLinkCapacity()
        val radio = radioKey(transport, carrier)
        val key = "$radio.${kind.key}"
        val ceilingVersion = store.getInt("$key.ceilingVersion", 1)
        return LearnedLinkCapacity(
            provenKbps = store.getInt("$key.proven", 0),
            ceilingKbps = store.getInt("$key.ceiling", 0).takeIf { it > 0 && usableCeiling(kind, ceilingVersion) },
            ceilingMeasuredAtMs = store.getLong("$key.ceilingAt", 0L),
            probeKbps = store.getInt("$radio.probe", 0),
            probeMeasuredAtMs = store.getLong("$radio.probeAt", 0L),
        )
    }

    fun budgetKbps(transport: BluetoothTransportOption, kind: LinkLoadKind, perLinkKbps: Double): Double {
        val learned = learned(transport, kind)
        val now = System.currentTimeMillis()
        val scale = LinkCapacityPolicy.fanOutProvenScale(kind, DebugTunables.fanoutProvenLinks)
        if (!DebugTunables.ceilingProbeRecovery) {
            return LinkCapacityPolicy.budgetKbps(learned, priorKbps(transport), perLinkKbps, now, scale)
        }
        val failures = probeFailures(transport, kind)
        val open = LinkCapacityPolicy.ceilingProbeOpen(learned, failures, now)
        val logKey = "${transport.name}.${kind.key}.$open.${learned.ceilingMeasuredAtMs}"
        if (open && lastProbeLog != logKey) {
            lastProbeLog = logKey
            Log.i(
                "BluetoothVideo",
                "BENCHMARK ceiling_probe: open | transport=${transport.name} | kind=${kind.key} | " +
                    "ceiling=${learned.ceilingKbps} | failures=$failures | " +
                    "cooldownMs=${LinkCapacityPolicy.ceilingProbeCooldownMs(failures)}",
            )
        }
        return LinkCapacityPolicy.probingBudgetKbps(learned, priorKbps(transport), perLinkKbps, now, open, scale)
    }

    @Volatile private var lastProbeLog: String? = null

    private fun probeFailures(transport: BluetoothTransportOption, kind: LinkLoadKind): Int =
        prefs?.getInt("${radioKey(transport)}.${kind.key}.ceilingProbes", 0) ?: 0

    fun recordHealthy(transport: BluetoothTransportOption, kind: LinkLoadKind, kbps: Int) =
        update(transport, kind) { LinkCapacityPolicy.withHealthy(it, kbps, System.currentTimeMillis()) }

    fun recordCeiling(transport: BluetoothTransportOption, kind: LinkLoadKind, kbps: Int) =
        update(transport, kind) { LinkCapacityPolicy.withCeiling(it, kbps, System.currentTimeMillis()) }

    /**
     * Video rate Automatic rate control last saw [peer]'s link sustain for [use] (stream or call), or
     * null when unknown or older than [SUSTAINED_TTL_MS]. Kept per radio state like other learning.
     */
    fun sustainedVideoBps(
        transport: BluetoothTransportOption,
        use: String,
        peer: String,
        buildKeyed: Boolean = false,
    ): Int? {
        val record = peerRecords?.get(sustainedKey(transport, use, peer, buildKeyed)) ?: return null
        return sustainedBpsOf(record, buildKeyed, buildStamp, System.currentTimeMillis())
    }

    /** A stored start rate: `bps`, or `bps,build` for the build-keyed format; null when unusable now. */
    internal fun sustainedBpsOf(record: SealedRecords.Record, buildKeyed: Boolean, build: Long, nowMs: Long): Int? {
        val parts = record.value.split(',')
        if (buildKeyed && parts.getOrNull(1)?.toLongOrNull() != build) return null
        val age = nowMs - record.atMs
        return parts[0].toIntOrNull()?.takeIf { it > 0 && age in 0 until LinkCapacityPolicy.SUSTAINED_TTL_MS }
    }

    fun recordSustainedVideoBps(
        transport: BluetoothTransportOption,
        use: String,
        peer: String,
        bps: Int,
        buildKeyed: Boolean = false,
    ) {
        // Review 2026-10-27 (finding 15): nothing is written once an emergency wipe started (it would recreate the file).
        if (WipeGuard.active) return
        val key = sustainedKey(transport, use, peer, buildKeyed)
        peerRecords?.put(key, if (buildKeyed) "$bps,$buildStamp" else "$bps")
    }

    /**
     * Format 2 (2026-10-09, `sustained2`): only clean sustained windows are recorded (a cut is never
     * remembered since the link report version 3 change), so a rate stays valid across app builds for
     * its 24 h; radio capacity does not depend on the APK. Every new install used to start cold: an
     * M31 -> ASUS GATT call began at its full 1000 kbps on a ~620 kbps link (1.9 s backlog, 34 sender
     * drops; mvp-smoke-m31asus-b9029e48). The build-keyed format 1 (`sustained`, cuts remembered)
     * stays for the DEBUG control arms; a new format number is the only reason to drop old rates.
     */
    private fun sustainedKey(transport: BluetoothTransportOption, use: String, peer: String, buildKeyed: Boolean): String =
        sustainedKeyName(radioKey(transport), use, peer, buildKeyed)

    internal fun sustainedKeyName(radio: String, use: String, peer: String, buildKeyed: Boolean): String =
        "$radio.${if (buildKeyed) "sustained" else "sustained$SUSTAINED_FORMAT"}.$use.$peer"

    private const val SUSTAINED_FORMAT = 2

    /**
     * Arrival rate [peer] last reported for its link from this phone on [transport] (the child's own
     * packet-train measurement, hop-local), or null when unknown or older than [SUSTAINED_TTL_MS].
     * A physical link property, so it is not keyed to the build. Read by the experimental per-peer
     * pacer seed ([LinkPacerSeed]): the ASUS -> A15s link carried about 65 kbps while the ASUS seeded
     * it at its own best single-link probe (mesh4-57484598).
     */
    fun peerLinkKbps(transport: BluetoothTransportOption, peer: String): Int? {
        val record = peerRecords?.get("${radioKey(transport)}.peerlink.$peer") ?: return null
        val age = System.currentTimeMillis() - record.atMs
        return record.value.toIntOrNull()?.takeIf { it > 0 && age in 0 until LinkCapacityPolicy.SUSTAINED_TTL_MS }
    }

    /** Stores [peer]'s report; rewrites only on a 10% change or after [PEER_LINK_REWRITE_MS]. */
    fun recordPeerLinkKbps(transport: BluetoothTransportOption, peer: String, kbps: Int) {
        val store = peerRecords ?: return
        if (peer.isEmpty() || kbps <= 0 || WipeGuard.active) return
        val key = "${radioKey(transport)}.peerlink.$peer"
        val now = System.currentTimeMillis()
        val stored = store.get(key)
        val previous = stored?.value?.toIntOrNull() ?: 0
        val age = now - (stored?.atMs ?: 0L)
        if (!peerLinkNeedsRewrite(previous, kbps, age)) return
        store.put(key, "$kbps", now)
    }

    /** A key that names a peer: a start rate (with its `.at`/`.build` companions in the old plain format) or a peer link report. */
    internal fun isPeerKey(key: String): Boolean = isSustainedKey(key) || ".peerlink." in key

    /**
     * The plain per-peer entries an earlier build wrote into [PREFS] (`key` Int, `key.at` Long, `key.build` Long), as
     * sealed records; entries without a time are dropped (they could never be read as fresh).
     */
    internal fun plainPeerRecords(all: Map<String, *>): Map<String, SealedRecords.Record> =
        all.keys.filter { isPeerKey(it) && !it.endsWith(".at") && !it.endsWith(".build") }.mapNotNull { key ->
            val value = all[key] as? Int ?: return@mapNotNull null
            val at = all["$key.at"] as? Long ?: return@mapNotNull null
            val build = all["$key.build"] as? Long
            key to SealedRecords.Record(if (build != null) "$value,$build" else "$value", at)
        }.toMap()

    /** The plain per-peer entries of an earlier build (a cache: deleted from [PREFS] even if sealing fails). */
    private fun plainPeerSource(app: Context) = object : LegacyPlain {
        override fun read() = plainPeerRecords(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all)
        override fun clean() = PlainPreferences.removeKeys(app, PREFS) { isPeerKey(it) }
        override val cache = true
    }

    internal fun peerLinkNeedsRewrite(previousKbps: Int, kbps: Int, ageMs: Long): Boolean =
        previousKbps <= 0 || ageMs !in 0 until PEER_LINK_REWRITE_MS ||
            kotlin.math.abs(kbps - previousKbps) * 10 >= previousKbps

    private const val PEER_LINK_REWRITE_MS = 60_000L


    /**
     * DEBUG `test_reset_learned_state`: removes relay/fan-out ceilings (and their probe counters),
     * sustained start rates and bad-2M marks; probes and proven loads stay. Returns "kind=count" pairs.
     */
    @Synchronized
    fun resetLearnedState(): String {
        val store = prefs
        val ceilingKeys = store?.all?.keys?.filter(::isCeilingKey).orEmpty()
        if (ceilingKeys.isNotEmpty()) store?.edit()?.apply { ceilingKeys.forEach(::remove) }?.commit()
        val ceilings = ceilingKeys.count { it.endsWith(".ceiling") }
        return "ceilings=$ceilings | sustained=${clearSustainedRates()} | bad2m=${AdaptivePhyMemory.clearAll()}"
    }

    internal fun isCeilingKey(key: String): Boolean =
        key.endsWith(".ceiling") || key.endsWith(".ceilingAt") || key.endsWith(".ceilingVersion") ||
            key.endsWith(".ceilingProbes")

    /** DEBUG launch record: fresh ceilings per transport and kind, and the bad-2M marks. */
    fun learnedStateSummary(): String {
        val now = System.currentTimeMillis()
        val ceilings = BluetoothTransportOption.entries.flatMap { transport ->
            LinkLoadKind.entries.mapNotNull { kind ->
                val learned = learned(transport, kind)
                LinkCapacityPolicy.freshCeiling(learned, now)?.let {
                    "${transport.name}.${kind.key}=$it(%.0fmin)".format(java.util.Locale.ROOT, (now - learned.ceilingMeasuredAtMs) / 60_000.0)
                }
            }
        }
        return "ceilings=${ceilings.joinToString(",").ifEmpty { "none" }} | bad2m=${AdaptivePhyMemory.summary(now)}"
    }

    /**
     * DEBUG A/B arms: removes every remembered sustained start rate; returns how many were removed, or `pending` while the
     * sealed memory is still loading (re-review S2: the removal is replayed onto it, and on an earlier build's plain rates,
     * once it loads; a count taken then would claim 0).
     */
    @Synchronized
    fun clearSustainedRates(): String {
        val store = peerRecords ?: return "0"
        val loaded = store.isLoaded()
        val known = store.keys().count { isSustainedKey(it) }
        store.retain { !isSustainedKey(it) }
        return if (loaded) "$known" else "pending"
    }

    /** DEBUG: every remembered sustained start rate, "radio.sustained[2].use.peer=bps", for the launch record. */
    fun sustainedRatesSummary(): String {
        val store = peerRecords ?: return "unavailable"
        if (!store.isLoaded()) return "loading"
        val rates = store.all().filterKeys { isSustainedKey(it) }.mapValues { it.value.value.substringBefore(',') }
        return if (rates.isEmpty()) "none" else rates.entries.sortedBy { it.key }.joinToString(" | ") { "${it.key}=${it.value}" }
    }

    internal fun isSustainedKey(key: String): Boolean = ".sustained." in key || ".sustained$SUSTAINED_FORMAT." in key

    /** [unknownPeerStartBps] seeds a peer this phone has not measured yet (null = start at the request). */
    internal fun sustainedRateMemory(
        transport: BluetoothTransportOption,
        use: String,
        buildKeyed: Boolean = false,
        unknownPeerStartBps: () -> Int? = { null },
    ): SustainedRateMemory =
        object : SustainedRateMemory {
            override fun startBps(peer: String): Int? =
                sustainedVideoBps(transport, use, peer, buildKeyed) ?: unknownPeerStartBps()
            override fun record(peer: String, bps: Int) = recordSustainedVideoBps(transport, use, peer, bps, buildKeyed)
        }

    /** This phone's fresh measured single-link capacity on [transport], or null before any measurement. */
    fun measuredLinkKbps(transport: BluetoothTransportOption): Int? =
        LinkCapacityPolicy.freshProbe(learned(transport, LinkLoadKind.FAN_OUT), System.currentTimeMillis())

    /** This phone's fresh measured capacity of [carrier] on [transport], or null before any measurement. */
    fun measuredLinkKbps(transport: BluetoothTransportOption, carrier: CarrierKind): Int? =
        LinkCapacityPolicy.freshProbe(learned(transport, LinkLoadKind.FAN_OUT, carrier), System.currentTimeMillis())

    fun recordProbe(
        transport: BluetoothTransportOption,
        kbps: Int,
        carrier: CarrierKind = CarrierKind.BLUETOOTH,
    ) = update(transport, LinkLoadKind.FAN_OUT, carrier) { LinkCapacityPolicy.withProbe(it, kbps, System.currentTimeMillis()) }

    fun summary(transport: BluetoothTransportOption): String {
        val now = System.currentTimeMillis()
        val probe = LinkCapacityPolicy.freshProbe(learned(transport, LinkLoadKind.FAN_OUT), now)
        val radio = if (WifiCoexistence.sharesBluetoothBand()) " (2.4 GHz Wi-Fi active)" else ""
        return "${transport.shortLabel}$radio: probe ${probe?.let { "$it kbps" } ?: "none"}, " +
            "prior %.0f kbps".format(priorKbps(transport)) +
            LinkLoadKind.entries.joinToString("") { kind ->
                val learned = learned(transport, kind)
                val ceiling = LinkCapacityPolicy.freshCeiling(learned, now)
                " | ${kind.label} proven ${learned.provenKbps}, ceiling ${ceiling ?: "none"}"
            }
    }

    @Synchronized
    private fun update(
        transport: BluetoothTransportOption,
        kind: LinkLoadKind,
        carrier: CarrierKind = CarrierKind.BLUETOOTH,
        change: (LearnedLinkCapacity) -> LearnedLinkCapacity,
    ) {
        val store = prefs ?: return
        if (WipeGuard.active) return
        val before = learned(transport, kind, carrier)
        val after = change(before)
        if (after == before) return
        val radio = radioKey(transport, carrier)
        val key = "$radio.${kind.key}"
        val nowMs = System.currentTimeMillis()
        val failures = LinkCapacityPolicy.nextProbeFailures(
            LinkCapacityPolicy.freshCeiling(before, nowMs) != null,
            after.ceilingMeasuredAtMs != before.ceilingMeasuredAtMs,
            after,
            store.getInt("$key.ceilingProbes", 0),
        ).takeIf { DebugTunables.ceilingProbeRecovery } ?: 0
        store.edit()
            .putInt("$key.ceilingProbes", failures)
            .putInt("$key.proven", after.provenKbps)
            .putInt("$key.ceiling", after.ceilingKbps ?: 0)
            .putLong("$key.ceilingAt", after.ceilingMeasuredAtMs)
            .putInt("$key.ceilingVersion", CEILING_ATTRIBUTION_VERSION)
            .putInt("$radio.probe", after.probeKbps)
            .putLong("$radio.probeAt", after.probeMeasuredAtMs)
            .apply()
        if (after.ceilingKbps != before.ceilingKbps || after.probeKbps != before.probeKbps ||
            after.provenKbps > before.provenKbps + 25 || after.provenKbps < before.provenKbps
        ) {
            Log.i("BluetoothVideo", "Link capacity ${summary(transport)}")
        }
    }
}

/**
 * Process-wide meter of the media links this phone is carrying. Every window it classifies each
 * transport's aggregate load as healthy or saturated and teaches [LinkCapacity].
 */
object NodeLinkLoad {
    private const val WINDOW_MS = 5_000L

    private class Session {
        var healthyStreak = 0
        var previousHealthyKbps = 0
        var saturatedWindows = 0
        var saturatedMaxKbps = 0
    }

    private class TransportLoad {
        val bytes = AtomicLong()
        val sendLinks = AtomicInteger()
        val receiveLinks = AtomicInteger()
        val peakSendLinks = AtomicInteger()
        val peakReceiveLinks = AtomicInteger()
        val saturatedHandles: MutableSet<Handle> = ConcurrentHashMap.newKeySet()
        val decisive = AtomicBoolean()
        /** This phone's own receiving degraded while it forwarded: shared airtime, not one child's link. */
        val radioLimited = AtomicBoolean()
        val sessions = LinkLoadKind.entries.associateWith { Session() }

        fun counter(direction: LinkDirection) = if (direction == LinkDirection.SEND) sendLinks else receiveLinks
        fun peak(direction: LinkDirection) = if (direction == LinkDirection.SEND) peakSendLinks else peakReceiveLinks
    }

    private val loads = BluetoothTransportOption.entries.associateWith { TransportLoad() }
    private var executor: ScheduledExecutorService? = null
    private var lastWindowNs = 0L

    class Handle internal constructor(
        private val transport: BluetoothTransportOption,
        private val direction: LinkDirection,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        fun add(bytes: Int) {
            if (bytes > 0) loads.getValue(transport).bytes.addAndGet(bytes.toLong())
        }

        /** This link dropped media or built a backlog. */
        fun saturated() {
            loads.getValue(transport).saturatedHandles += this
        }

        /** The link was abandoned for sustained congestion; one window is enough evidence. */
        fun withdrawnForCongestion() {
            val load = loads.getValue(transport)
            load.decisive.set(true)
            load.saturatedHandles += this
        }

        /**
         * Like [withdrawnForCongestion], and this phone's own receiving degraded too: the radio, not
         * the child's link, limited receiving and forwarding together.
         */
        fun withdrawnForLocalCapacity() {
            loads.getValue(transport).radioLimited.set(true)
            withdrawnForCongestion()
        }

        fun countingInput(input: InputStream): InputStream = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) add(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { add(it) }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) release(transport, direction)
        }
    }

    /** Media links this phone is sending on [transport] right now (fan-out when two or more). */
    fun sendLinks(transport: BluetoothTransportOption): Int = loads.getValue(transport).sendLinks.get()

    /** Media links this phone is receiving on [transport] right now (a stream it watches or forwards). */
    fun receiveLinks(transport: BluetoothTransportOption): Int = loads.getValue(transport).receiveLinks.get()

    /** Bytes counted on [transport] in the current window (diagnostics and JVM tests; the window evaluation resets it). */
    internal fun windowBytes(transport: BluetoothTransportOption): Long = loads.getValue(transport).bytes.get()

    @Synchronized
    fun open(transport: BluetoothTransportOption, direction: LinkDirection): Handle {
        val load = loads.getValue(transport)
        val links = load.counter(direction).incrementAndGet()
        load.peak(direction).accumulateAndGet(links, ::max)
        if (executor == null) {
            lastWindowNs = System.nanoTime()
            executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "NodeLinkLoad").apply { isDaemon = true }
            }.also { it.scheduleWithFixedDelay(::evaluate, WINDOW_MS, WINDOW_MS, TimeUnit.MILLISECONDS) }
        }
        return Handle(transport, direction)
    }

    @Synchronized
    private fun release(transport: BluetoothTransportOption, direction: LinkDirection) {
        loads.getValue(transport).counter(direction).decrementAndGet()
        if (loads.values.all { it.sendLinks.get() <= 0 && it.receiveLinks.get() <= 0 }) {
            // Evaluate the final partial window so a congestion withdrawal that ended the last
            // links is still learned.
            evaluate()
            executor?.shutdownNow()
            executor = null
            loads.values.forEach { load -> load.sessions.values.forEach(::resetSession) }
        }
    }

    @Synchronized
    private fun evaluate() {
        val nowNs = System.nanoTime()
        val seconds = (nowNs - lastWindowNs) / 1_000_000_000.0
        lastWindowNs = nowNs
        if (seconds <= 0.0) return
        loads.forEach { (transport, load) ->
            // Links that closed during the window (e.g. a withdrawn child) still shaped its load.
            val sendLinks = load.peakSendLinks.getAndSet(load.sendLinks.get())
            val receiveLinks = load.peakReceiveLinks.getAndSet(load.receiveLinks.get())
            val bytes = load.bytes.getAndSet(0)
            // A final window shorter than a second gives a noisy rate; keep only its saturation.
            val kbps = if (seconds < 1.0) 0 else (bytes * 8 / seconds / 1_000).toInt()
            val saturatedLinks = load.saturatedHandles.size
            load.saturatedHandles.clear()
            val decisive = load.decisive.getAndSet(false)
            val radioLimited = load.radioLimited.getAndSet(false)
            val (kind, state) = classifyLinkWindow(sendLinks, receiveLinks, saturatedLinks, kbps, radioLimited)
            if (kind == null) {
                load.sessions.values.forEach(::resetSession)
                return@forEach
            }
            val session = load.sessions.getValue(kind)
            when (state) {
                LinkWindowState.SATURATED -> {
                    session.healthyStreak = 0
                    session.saturatedWindows += if (decisive) 2 else 1
                    session.saturatedMaxKbps = max(session.saturatedMaxKbps, kbps)
                    if (session.saturatedWindows >= 2 && session.saturatedMaxKbps > 0) {
                        LinkCapacity.recordCeiling(transport, kind, session.saturatedMaxKbps)
                    }
                }
                LinkWindowState.HEALTHY -> {
                    session.healthyStreak++
                    val sustained = min(kbps, session.previousHealthyKbps)
                    session.previousHealthyKbps = kbps
                    if (session.healthyStreak >= 2) LinkCapacity.recordHealthy(transport, kind, sustained)
                }
                LinkWindowState.PEER_LIMITED, LinkWindowState.IDLE -> session.healthyStreak = 0
            }
        }
    }

    private fun resetSession(session: Session) {
        session.healthyStreak = 0
        session.previousHealthyKbps = 0
        session.saturatedWindows = 0
        session.saturatedMaxKbps = 0
    }
}
