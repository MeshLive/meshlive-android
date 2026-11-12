package com.example.bluetoothtest

import android.os.SystemClock
import android.util.Log
import java.util.IdentityHashMap

/**
 * Phone-wide GATT resources MeshLive holds (G-02). Each `BluetoothGatt`, each `BluetoothGattServer` and, on Android
 * 10-12, each scanner takes one registration from a system-wide pool of 32 (two used by the stack); each GATT client
 * connection takes one of 22 client blocks on Android 10-14. A leaked object keeps its slot until process death
 * (AOSP bt_target.h GATT_MAX_APPS 32, bta_gattc_int.h BTA_GATTC_CL_MAX, bta_gattc_act.cc:277-288). The registry
 * counts what this process holds per owner and kind, shows leaks (an object registered and never released) and
 * classifies the two exhaustion statuses so a failing connect is explained rather than hammered.
 *
 * Owners register through [GattConnectCompat] (clients), [GattClientTeardown] releases on close, and the scanner or
 * server owners call [register]/[release] directly (hooks for batch S).
 */
internal class GattResourceRegistry(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    enum class Kind { CLIENT, SERVER, SCANNER }

    data class Handle(
        val id: Long,
        val owner: String,
        val kind: Kind,
        val peer: String?,
        val openedAtMs: Long,
    )

    private val live = LinkedHashMap<Long, Handle>()
    private val byObject = IdentityHashMap<Any, Long>()
    private val activeIds = HashSet<Long>()
    private val closingIds = HashSet<Long>()
    private val closers = HashMap<Long, () -> Unit>()
    private var nextId = 1L
    private var opened = 0L
    private var closed = 0L

    @Synchronized
    fun register(owner: String, kind: Kind, peer: String? = null): Handle {
        val handle = Handle(nextId++, owner, kind, peer, clock())
        live[handle.id] = handle
        opened++
        return handle
    }

    /** Registers [resource] (a BluetoothGatt, server or scanner callback) so [releaseObject] can find it. */
    @Synchronized
    fun registerObject(
        resource: Any,
        owner: String,
        kind: Kind,
        peer: String? = null,
        /** Closes the resource if it turns out idle and old (see [closeIdle]); null when the owner closes it itself. */
        closer: (() -> Unit)? = null,
    ): Handle {
        byObject[resource]?.let { existing -> live[existing]?.let { return it } }
        val handle = register(owner, kind, peer)
        byObject[resource] = handle.id
        if (closer != null) closers[handle.id] = closer
        return handle
    }

    /** Marks [resource] as carrying a live link (never closed by [closeIdle]) or as idle again. */
    @Synchronized
    fun setActive(resource: Any, active: Boolean) {
        val id = byObject[resource] ?: return
        if (active) activeIds += id else activeIds -= id
    }

    /** The owner started closing [resource] (close may still be pending on a deferral): nobody else must close it. */
    @Synchronized
    fun markClosing(resource: Any) {
        byObject[resource]?.let { closingIds += it }
    }

    /**
     * A callback arrived for a cancelled client: true when the client is still registered and nobody is closing it,
     * i.e. it would leak (backlog G-07). False when it was already closed or its close is in progress.
     */
    @Synchronized
    fun needsStaleCleanup(resource: Any): Boolean {
        val id = byObject[resource] ?: return false
        return id in live && id !in closingIds
    }

    /** Registrations [closeIdle] would close now. */
    @Synchronized
    fun idleClosable(olderThanMs: Long): Int {
        val now = clock()
        return live.values.count { it.id !in activeIds && now - it.openedAtMs > olderThanMs && it.id in closers }
    }

    /**
     * Closes registrations that are not active, older than [olderThanMs] and have a closer: leaked probes and abandoned
     * setups. Returns how many were closed. The only close a pool-exhaustion retry may rely on (G-02).
     */
    fun closeIdle(olderThanMs: Long): Int {
        val victims = synchronized(this) {
            val now = clock()
            live.values.filter { it.id !in activeIds && now - it.openedAtMs > olderThanMs && it.id in closers }
                .map { it to closers.getValue(it.id) }
        }
        var closedCount = 0
        for ((handle, closer) in victims) {
            runCatching(closer)
            synchronized(this) { if (live.remove(handle.id) != null) { closed++; closedCount++ } }
            synchronized(this) { byObject.entries.removeAll { it.value == handle.id }; activeIds -= handle.id; closingIds -= handle.id; closers.remove(handle.id) }
        }
        return closedCount
    }

    @Synchronized
    fun release(handle: Handle): Boolean {
        if (live.remove(handle.id) == null) return false
        byObject.entries.removeAll { it.value == handle.id }
        activeIds -= handle.id
        closingIds -= handle.id
        closers.remove(handle.id)
        closed++
        return true
    }

    /** Releases the registration of [resource]: its handle, or null when it was never registered or is already released. */
    @Synchronized
    fun releaseObject(resource: Any): Handle? {
        val id = byObject.remove(resource) ?: return null
        activeIds -= id
        closingIds -= id
        closers.remove(id)
        val handle = live.remove(id) ?: return null
        closed++
        return handle
    }

    private val serverLinkPeers = HashSet<String>()

    /** A central is connected to this phone's GATT server (admitted link): the phone is GATT server for [peer]. */
    @Synchronized
    fun noteServerLink(peer: String?) {
        if (peer != null) serverLinkPeers += peer
    }

    @Synchronized
    fun dropServerLink(peer: String?) {
        if (peer != null) serverLinkPeers -= peer
    }

    /**
     * Problems with dialling [peer] as a GATT client now (aosp-bt-versions GATT-07, GATT-09): a second BluetoothGatt to
     * the same remote (discovery stalls, accept-list and ContextMap bugs fixed only in Android 15 QPR1+) and a link that
     * already exists in the other direction (server responses mis-multiplexed, fixed in 15 QPR1). Empty means clean.
     */
    @Synchronized
    fun dialWarnings(peer: String?): List<String> {
        if (peer == null) return emptyList()
        return listOfNotNull(
            "second_client_to_same_remote".takeIf { liveClientsFor(peer) > 0 },
            "crossed_connection_server_link_exists".takeIf { peer in serverLinkPeers },
        )
    }

    @Synchronized
    fun isLive(resource: Any): Boolean = byObject[resource]?.let { it in live } == true

    @Synchronized
    fun count(kind: Kind? = null): Int = live.values.count { kind == null || it.kind == kind }

    @Synchronized
    fun countByOwner(owner: String, kind: Kind? = null): Int =
        live.values.count { it.owner == owner && (kind == null || it.kind == kind) }

    /** Live BluetoothGatt clients to [peer]; more than one means a second holder of the stack's per-peer write permit. */
    @Synchronized
    fun liveClientsFor(peer: String?): Int =
        if (peer == null) 0 else live.values.count { it.kind == Kind.CLIENT && it.peer == peer }

    /** Registrations held longer than [olderThanMs]: candidates for a missing close(). */
    @Synchronized
    fun older(olderThanMs: Long): List<Handle> {
        val now = clock()
        return live.values.filter { now - it.openedAtMs > olderThanMs }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val byOwner = live.values.groupBy { it.owner }.mapValues { (_, handles) -> handles.size }
        return Snapshot(
            clients = live.values.count { it.kind == Kind.CLIENT },
            servers = live.values.count { it.kind == Kind.SERVER },
            scanners = live.values.count { it.kind == Kind.SCANNER },
            byOwner = byOwner,
            opened = opened,
            closed = closed,
        )
    }

    data class Snapshot(
        val clients: Int,
        val servers: Int,
        val scanners: Int,
        val byOwner: Map<String, Int>,
        val opened: Long,
        val closed: Long,
    ) {
        val registrations: Int get() = clients + servers + scanners

        /** opened - closed: a number that keeps growing across connect/cancel cycles is a leak. */
        val outstanding: Long get() = opened - closed
    }

    fun line(event: String): String {
        val s = snapshot()
        return "BENCHMARK gatt_resources: event=$event | clients=${s.clients} | servers=${s.servers} | scanners=${s.scanners} | " +
            "registrations=${s.registrations}/$APP_POOL | opened=${s.opened} | closed=${s.closed} | " +
            "byOwner=${s.byOwner.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }.ifEmpty { "none" }}"
    }

    @Synchronized
    fun clearForTest() {
        live.clear()
        byObject.clear()
        activeIds.clear()
        serverLinkPeers.clear()
        closingIds.clear()
        closers.clear()
        opened = 0
        closed = 0
    }

    companion object {
        /** GATT_MAX_APPS (32) minus the two registrations the stack keeps for itself. */
        const val APP_POOL = 30

        /** Status the framework reports when `onClientRegistered` failed: the 32-registration pool is exhausted. */
        const val STATUS_REGISTRATION_EXHAUSTED = 257

        /** GATT_NO_RESOURCES: the 22-block client connection pool (SDK 29-34) is exhausted. */
        const val STATUS_NO_RESOURCES = 128

        val shared = GattResourceRegistry()

        fun classifyStatus(status: Int): PoolVerdict = when (status) {
            STATUS_REGISTRATION_EXHAUSTED -> PoolVerdict.REGISTRATION_EXHAUSTED
            STATUS_NO_RESOURCES -> PoolVerdict.CLIENT_POOL_EXHAUSTED
            else -> PoolVerdict.NONE
        }

        fun logQuietly(message: String) {
            try { Log.i("BluetoothVideo", message) } catch (_: RuntimeException) {} // JVM tests: Log is a stub.
        }
    }
}

/**
 * Which pool a failed GATT open exhausted. 257 is the framework's own status for a failed client registration; 128
 * is the stack's GATT_NO_RESOURCES. Neither is retried in a tight loop (see [PoolExhaustionPolicy]).
 */
internal enum class PoolVerdict(val label: String) {
    NONE("none"),
    REGISTRATION_EXHAUSTED("GATT registration pool exhausted (32 apps phone-wide, 257)"),
    CLIENT_POOL_EXHAUSTED("GATT client pool exhausted (22 connections phone-wide, 128)"),
}

/**
 * Decides what to do with a connect failure that names a pool. At most one retry per exhausted pool and only after
 * this process closed its own idle clients (an exhausted pool does not clear on its own within seconds), then the
 * failure is surfaced. Consecutive fresh-connect failures with a peer known to be in range, across at least
 * [MIN_PEERS] peers, past [THRESHOLD] total, stop the hammering and ask for a Bluetooth restart (B2 shows the text).
 */
internal class PoolExhaustionPolicy(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    enum class Action { RETRY_AFTER_CLOSING_IDLE, SURFACE, NOT_A_POOL_FAILURE }

    private var poolRetriesUsed = 0
    private val streakPeers = LinkedHashSet<String>()
    private var streak = 0
    private var restartGuidanceAtMs = 0L
    private val registrationFailures = java.util.EnumMap<PoolSource, Int>(PoolSource::class.java)
    private var registrationLastMs = 0L

    /**
     * A scanner start, a GATT server open or a client registration failed because the phone-wide pool is exhausted
     * (one call per backoff step of [GattPoolBackoff], not per attempt). [REGISTRATION_THRESHOLD] steps in a row of one
     * source, with nothing of that source working since, raise the same restart guidance as a connect streak: the stack
     * itself said no slot is free, so unlike a connect streak no second peer is needed to rule out the peer.
     */
    @Synchronized
    fun onRegistrationFailure(source: PoolSource) {
        val now = clock()
        if (registrationFailures.isNotEmpty() && now - registrationLastMs > REGISTRATION_STALE_MS) registrationFailures.clear()
        registrationLastMs = now
        registrationFailures[source] = (registrationFailures[source] ?: 0) + 1
    }

    /** [source] registered again: its failures no longer count toward the guidance. */
    @Synchronized
    fun onRegistrationSuccess(source: PoolSource) {
        registrationFailures.remove(source)
    }

    /** A connect failed with [status]. [ownIdleClients] is how many of this process's clients could be closed first. */
    @Synchronized
    fun onConnectFailure(status: Int, peer: String?, peerInRange: Boolean, ownIdleClients: Int): Action {
        if (peerInRange) {
            streak++
            if (peer != null) streakPeers += peer
            if (streak >= THRESHOLD && streakPeers.size >= MIN_PEERS && restartGuidanceAtMs == 0L) {
                restartGuidanceAtMs = clock()
            }
        }
        if (GattResourceRegistry.classifyStatus(status) == PoolVerdict.NONE) return Action.NOT_A_POOL_FAILURE
        if (poolRetriesUsed == 0 && ownIdleClients > 0) {
            poolRetriesUsed++
            return Action.RETRY_AFTER_CLOSING_IDLE
        }
        return Action.SURFACE
    }

    @Synchronized
    fun onConnectSuccess() {
        poolRetriesUsed = 0
        streak = 0
        streakPeers.clear()
        restartGuidanceAtMs = 0L
        registrationFailures.remove(PoolSource.CLIENT)
    }

    /**
     * A native client wedge was detected ([GattNativeClientWedge], Experimental): the restart guidance is due now, as
     * after a connect streak. The next successful connect ([onConnectSuccess]) clears it.
     */
    @Synchronized
    fun onNativeClientWedge() {
        if (restartGuidanceAtMs == 0L) restartGuidanceAtMs = clock().coerceAtLeast(1L)
    }

    /**
     * True once the streak, or repeated registration failures of the scanner, the GATT server or the client, say new
     * connects are futile until Bluetooth or the phone restarts. Failures older than [REGISTRATION_STALE_MS] with no
     * newer failure no longer count (the pool may have been cleared by a Bluetooth restart).
     */
    @Synchronized
    fun restartGuidanceDue(): Boolean = restartGuidanceAtMs != 0L || registrationGuidanceDue()

    /**
     * True once [source] alone has failed [REGISTRATION_THRESHOLD] back-off steps in a row, the last within
     * [REGISTRATION_STALE_MS], with no success of [source] since (the Nearby list's restart hint asks for the scanner).
     */
    @Synchronized
    fun registrationGuidanceDue(source: PoolSource): Boolean =
        clock() - registrationLastMs <= REGISTRATION_STALE_MS && (registrationFailures[source] ?: 0) >= REGISTRATION_THRESHOLD

    private fun registrationGuidanceDue(): Boolean =
        registrationFailures.isNotEmpty() && clock() - registrationLastMs <= REGISTRATION_STALE_MS &&
            registrationFailures.values.any { it >= REGISTRATION_THRESHOLD }

    companion object {
        const val THRESHOLD = 6
        const val MIN_PEERS = 2

        /** Backoff steps (1 s, 2 s, 4 s: about 7 s of continuous refusal) of one registration source before the guidance shows. */
        const val REGISTRATION_THRESHOLD = 3

        /** Registration failures this old, with none newer, are dropped (two caps of [PoolBackoff.CAP_MS] plus margin). */
        const val REGISTRATION_STALE_MS = 120_000L
        const val GUIDANCE = "Bluetooth may be stuck: connections to nearby phones keep failing. Turn Bluetooth off and on, or restart the phone."

        val shared = PoolExhaustionPolicy()
    }
}
