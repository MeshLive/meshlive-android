package com.example.bluetoothtest

import java.io.Closeable
import java.net.Inet4Address

/**
 * One attempt's WifiP2p channel and one group (hosted or joined). All work runs on the wifi looper through
 * [Scheduler]; the ledger claim is released only after the group's absence is confirmed, otherwise it moves to
 * [DirectQuarantine]. [AM 8][AM 9]
 */
internal class DirectGroup(
    private val port: P2pPort,
    private val record: DirectGroupRecord,
    private val ledger: WifiLedger,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
    private val events: Events,
    private val quarantine: DirectQuarantine,
    private val allow24Ghz: Boolean = false,
) : Closeable {
    companion object {
        const val LEDGER_OWNER = "direct" // the Share activity uses the literal "share"
        const val SWEEP_OWNER = "direct-sweep"

        /**
         * Startup sweep (4.4); a REMOVE_FAILED enters the quarantine with SWEEP_OWNER [AM 8]. Bounded by
         * [DirectLimits.CLEANUP_WATCHDOG_MS] (A9 review P2-2): on expiry or channel loss the channel is closed and
         * either the quarantine takes the claim (our recorded name was seen on air) or the claim is released; [done]
         * runs exactly once, so the caller always resumes or hands over its discovery lease.
         */
        fun sweepOnStartup(
            port: P2pPort,
            record: DirectGroupRecord,
            ledger: WifiLedger,
            quarantine: DirectQuarantine,
            scheduler: Scheduler,
            done: (SweepResult) -> Unit,
        ) {
            val names = record.namesOrNull().orEmpty()
            if (names.isEmpty()) {
                done(SweepResult.SKIPPED)
                return
            }
            if (ledger.reserveDirect(SWEEP_OWNER) != DirectReserve.OK) {
                done(SweepResult.SKIPPED)
                return
            }
            var finished = false
            var seen: String? = null
            var timer: Cancelable? = null
            val timers = ArrayList<Cancelable>()
            fun end(result: SweepResult) {
                if (finished) return
                finished = true
                timer?.cancel()
                timers.forEach { runCatching { it.cancel() } }
                runCatching { port.closeChannel() }
                if (!quarantine.active) ledger.releaseDirect(SWEEP_OWNER)
                done(result)
            }
            fun expire() {
                if (finished) return
                val name = seen
                if (name != null) {
                    quarantine.enter(name, SWEEP_OWNER, owned = true)
                    end(SweepResult.REMOVE_FAILED)
                } else {
                    end(SweepResult.TIMEOUT)
                }
            }
            val opened = runCatching { port.open { expire() } }.getOrDefault(false)
            if (!opened) {
                end(SweepResult.SKIPPED)
                return
            }
            timer = scheduler.post(DirectLimits.CLEANUP_WATCHDOG_MS) { expire() }
            sweepThen(
                port, record, quarantine, SWEEP_OWNER, null, scheduler,
                alive = { !finished },
                track = { timers += it },
                onRemoving = { seen = it },
            ) { result -> end(result) }
        }

        /**
         * The sweep before an attempt and at start-up. A group named [ssid] or a name in the record is removed; another
         * readable group is never removed (the record is pruned: ours cannot be on air); an unreadable group is never
         * removed here (nothing in this process created it) and prunes nothing: with names recorded it enters the
         * quarantine, which retries until a read can tell (A9 review P1-2). Record reads that fail keep everything (P1-3).
         */
        internal fun sweepThen(
            port: P2pPort,
            record: DirectGroupRecord,
            quarantine: DirectQuarantine,
            owner: String,
            ssid: String?,
            scheduler: Scheduler,
            alive: () -> Boolean,
            track: (Cancelable) -> Unit = {},
            onRemoving: (String?) -> Unit = {},
            then: (SweepResult) -> Unit,
        ) {
            DirectRemoval(port, scheduler, alive, track).run(
                isOurs = { name -> if (name == ssid) true else record.containsOrNull(name) },
                removeUnreadable = false,
                onRemoving = onRemoving,
            ) { outcome ->
                when (outcome) {
                    DirectRemoval.Outcome.Absent -> {
                        record.tryClear()
                        then(SweepResult.CLEAR)
                    }
                    is DirectRemoval.Outcome.Other -> {
                        // Another app's group (or a group this app did not record): never removed; ours cannot be on air.
                        record.tryClear()
                        then(SweepResult.FOREIGN)
                    }
                    is DirectRemoval.Outcome.Removed -> {
                        outcome.name?.let { record.tryRemove(it) }
                        then(SweepResult.REMOVED)
                    }
                    is DirectRemoval.Outcome.Unconfirmed -> {
                        quarantine.enter(outcome.name, owner, owned = true)
                        then(SweepResult.REMOVE_FAILED)
                    }
                    DirectRemoval.Outcome.Unknown -> {
                        val recorded = record.namesOrNull()
                        if (recorded == null || recorded.isNotEmpty()) quarantine.enter(null, owner, owned = false)
                        then(SweepResult.UNKNOWN)
                    }
                }
            }
        }
    }

    interface Scheduler {
        fun post(delayMs: Long, block: () -> Unit): Cancelable
    }

    fun interface Cancelable {
        fun cancel()
    }

    /** UNKNOWN: a group could not be read (nothing removed, nothing pruned); TIMEOUT: the start-up sweep's bound expired. */
    enum class SweepResult { CLEAR, REMOVED, FOREIGN, REMOVE_FAILED, UNKNOWN, TIMEOUT, SKIPPED }

    interface Events {
        fun onHostReady(ready: HostReady)
        fun onJoined(joined: Joined)
        fun onFailed(failure: DirectFailure)
        fun onLost()
    }

    data class HostReady(val local: DirectAddressRules.LocalAddress, val frequencyMhz: Int)

    data class Joined(
        val local: DirectAddressRules.LocalAddress,
        val frequencyMhz: Int,
        val groupInterface: String?,
        val ownerAddress: Inet4Address?,
        /** AM 2's `freq_source`: group (read), host (the authenticated HOST frequency) or none. */
        val frequencySource: String = if (frequencyMhz > 0) "group" else "none",
    )

    @Volatile
    private var closed = false
    private val lifecycle = NearbyGroupLifecycle()
    private var reserved = false
    private var terminating = false
    private var terminated = false
    private var failureReported = false
    private var lostReported = false
    private var quarantineMoved = false
    private var channelOpen = false
    private var pendingCreate = false
    private var createdOrJoined = false
    private var retriedFrequency = false
    private var busyRetries = 0
    private var ssid: String? = null
    private var psk: String = ""
    private var guest = false
    private var requestedFrequencyMhz = 0
    private var hostFrequencyMhz = 0 // guest: the authenticated HOST frequency, kept for AM 2's fallback (P2-3)
    private var interfacesBefore: Set<String> = emptySet()
    private var joined: Joined? = null
    private var pollStartedAt = 0L
    private var firstGroupSeenAt: Long? = null
    private var pollTimer: Cancelable? = null
    private var watchdog: Cancelable? = null
    private val timers = ArrayList<Cancelable>()
    private val closeWaiters = ArrayList<() -> Unit>()

    fun host(ssid: String, psk: String, frequencyMhz: Int) {
        scheduler.post(0) { if (!closed) begin(ssid, psk, frequencyMhz, guest = false) }
    }

    fun join(ssid: String, psk: String, frequencyMhz: Int) {
        scheduler.post(0) { if (!closed) begin(ssid, psk, frequencyMhz, guest = true) }
    }

    override fun close() = close {}

    /** Idempotent, any thread; [done] runs once on the looper after the cleanup chain and release. */
    fun close(done: () -> Unit) {
        scheduler.post(0) {
            if (terminated) {
                done()
                return@post
            }
            closeWaiters += done
            if (terminating) return@post
            closed = true
            terminate()
        }
    }

    private fun begin(ssid: String, psk: String, frequencyMhz: Int, guest: Boolean) {
        this.ssid = ssid
        this.psk = psk
        this.guest = guest
        this.requestedFrequencyMhz = frequencyMhz
        if (guest) this.hostFrequencyMhz = frequencyMhz
        when (ledger.reserveDirect(LEDGER_OWNER)) {
            DirectReserve.OK -> reserved = true
            else -> {
                fail(DirectFailure.BUSY)
                return
            }
        }
        if (!DirectPolicy.validNames(ssid, psk)) {
            fail(DirectFailure.BAD_CONFIG)
            return
        }
        if (!port.open { onChannelLost() }) {
            fail(DirectFailure.PLATFORM_ERROR)
            return
        }
        channelOpen = true
        if (guest) interfacesBefore = port.localAddresses().map { it.interfaceName }.toSet()
        try {
            sweepThen(port, record, quarantine, LEDGER_OWNER, ssid, scheduler, alive = { !terminated }, track = { timers += it }) { result ->
                if (closed) {
                    terminate(keepClaim = quarantine.active) // a sweep that quarantined holds our claim
                    return@sweepThen
                }
                when (result) {
                    DirectGroup.SweepResult.FOREIGN -> fail(DirectFailure.FOREIGN_GROUP)
                    DirectGroup.SweepResult.REMOVE_FAILED -> fail(DirectFailure.BUSY, keepClaim = true)
                    DirectGroup.SweepResult.UNKNOWN, DirectGroup.SweepResult.TIMEOUT ->
                        fail(DirectFailure.BUSY, keepClaim = quarantine.active)
                    else -> {
                        try {
                            record.add(ssid)
                        } catch (error: Exception) {
                            fail(DirectFailure.RECORD_UNAVAILABLE)
                            return@sweepThen
                        }
                        startCreate(ssid, psk, frequencyMhz)
                    }
                }
            }
        } catch (error: Exception) {
            fail(DirectFailure.RECORD_UNAVAILABLE)
        }
    }

    private fun startCreate(ssid: String, psk: String, frequencyMhz: Int) {
        if (closed || terminating) {
            terminate()
            return
        }
        lifecycleCreating()
        pendingCreate = true
        try {
            if (guest) {
                port.connect(ssid, psk, frequencyMhz) { result -> onCreateResult(result) }
            } else {
                port.createGroup(ssid, psk, frequencyMhz) { result -> onCreateResult(result) }
            }
        } catch (error: RuntimeException) {
            pendingCreate = false
            onCreateResult(P2pResult.Failure(OfflineAppSharePolicy.P2P_ERROR))
        }
    }

    private fun lifecycleCreating() {
        if (closed) return
        lifecycle.creating()
    }

    private fun onCreateResult(result: P2pResult) {
        pendingCreate = false
        when (result) {
            P2pResult.Success -> {
                createdOrJoined = true
                val cleanup = lifecycle.created()
                if (cleanup == NearbyGroupLifecycle.Cleanup.REMOVE_GROUP || closed) {
                    removeLateGroup()
                    return
                }
                if (guest) startGuestPoll() else startHostPoll()
            }
            is P2pResult.Failure -> onFailureResult(result.reason)
            P2pResult.BadConfig -> onFailureResult(OfflineAppSharePolicy.P2P_ERROR, badConfig = true)
            P2pResult.NotVisible -> onFailureResult(OfflineAppSharePolicy.P2P_BUSY, notVisible = true)
            P2pResult.Denied -> {
                lifecycle.failed()
                fail(DirectFailure.NO_PERMISSION)
            }
        }
    }

    private fun onFailureResult(reason: Int, badConfig: Boolean = false, notVisible: Boolean = false) {
        val cleanup = lifecycle.failed()
        if (cleanup == NearbyGroupLifecycle.Cleanup.CLOSE_CHANNEL) {
            closeChannelOnly()
            finishTerminate()
            return
        }
        if (closed) {
            terminate()
            return
        }
        if (notVisible) {
            // Root note (c): not visible on API 29-32; every retry would be refused the same way (P3-7).
            fail(DirectFailure.BUSY)
            return
        }
        if (!badConfig) {
            val (failure, delay) = DirectPolicy.classify(reason, busyRetries)
            if (failure == DirectFailure.BUSY && delay != null) {
                busyRetries++
                schedule(delay) { if (!closed && !terminating) startCreate(ssid.orEmpty(), psk, requestedFrequencyMhz) }
                return
            }
            if (mayRetryWithoutFrequency()) {
                retryWithoutFrequency()
                return
            }
            fail(failure)
            return
        }
        if (mayRetryWithoutFrequency()) {
            retryWithoutFrequency()
        } else {
            fail(DirectFailure.BAD_CONFIG)
        }
    }

    /** AM 3 is the host's refused-frequency retry only; a guest keeps the host's frequency (A9 review P2-3, R-16). */
    private fun mayRetryWithoutFrequency(): Boolean = !guest && requestedFrequencyMhz != 0 && !retriedFrequency

    private fun retryWithoutFrequency() {
        retriedFrequency = true
        requestedFrequencyMhz = 0
        busyRetries = 0
        startCreate(ssid.orEmpty(), psk, 0)
    }

    private fun startHostPoll() {
        pollStartedAt = clock()
        firstGroupSeenAt = null
        schedulePoll(DirectLimits.FIRST_POLL_MS) { pollHost() }
    }

    private fun pollHost() {
        if (closed) return
        if (clock() - pollStartedAt >= DirectLimits.GROUP_READY_MS) {
            fail(DirectFailure.TIMEOUT)
            return
        }
        port.requestGroup { group ->
            if (closed || terminating) return@requestGroup
            if (group == null) {
                schedulePoll(DirectLimits.POLL_MS) { pollHost() }
                return@requestGroup
            }
            if (group.networkName != ssid || !group.isOwner) {
                fail(DirectFailure.FOREIGN_GROUP)
                return@requestGroup
            }
            val addresses = port.localAddresses()
            val local = DirectAddressRules.hostAddress(addresses, group.interfaceName)
            if (local == null) {
                val seen = firstGroupSeenAt ?: clock().also { firstGroupSeenAt = it }
                if (clock() - seen >= DirectLimits.ADDRESS_MS) fail(DirectFailure.NO_ADDRESS)
                else schedulePoll(DirectLimits.POLL_MS) { pollHost() }
                return@requestGroup
            }
            if (DirectAddressRules.subnetOverlap(local, addresses)) {
                // P3-10: a station-side peer in an overlapping subnet could reach the group address.
                fail(DirectFailure.SUBNET_OVERLAP)
                return@requestGroup
            }
            if (!DirectPolicy.bandOk(group.frequencyMhz) && !allow24Ghz) {
                fail(DirectFailure.BAND_24)
                return@requestGroup
            }
            events.onHostReady(HostReady(local, group.frequencyMhz))
        }
    }

    private fun startGuestPoll() {
        pollStartedAt = clock()
        schedulePoll(DirectLimits.POLL_MS) { pollGuest() }
    }

    private fun pollGuest() {
        if (closed) return
        if (clock() - pollStartedAt >= DirectLimits.GROUP_READY_MS) {
            fail(DirectFailure.TIMEOUT)
            return
        }
        port.requestGroup { group ->
            port.requestConnection { connection ->
                if (closed || terminating) return@requestConnection
                if (connection?.isOwner == true) {
                    fail(DirectFailure.FOREIGN_GROUP)
                    return@requestConnection
                }
                val local = DirectAddressRules.groupAddress(port.localAddresses(), group?.interfaceName, interfacesBefore)
                if (local == null) {
                    schedulePoll(DirectLimits.POLL_MS) { pollGuest() }
                    return@requestConnection
                }
                if (DirectAddressRules.subnetOverlap(local, port.localAddresses())) {
                    fail(DirectFailure.SUBNET_OVERLAP)
                    return@requestConnection
                }
                val read = group?.frequencyMhz ?: 0
                val (effective, source) = when {
                    read > 0 -> read to "group"
                    hostFrequencyMhz >= WifiLimits.MIN_5GHZ_MHZ -> hostFrequencyMhz to "host"
                    else -> 0 to "none"
                }
                if (!DirectPolicy.bandOk(effective) && !allow24Ghz) {
                    fail(DirectFailure.BAND_24)
                    return@requestConnection
                }
                val value = Joined(local, effective, group?.interfaceName, connection?.ownerAddress, source)
                joined = value
                events.onJoined(value)
            }
        }
    }

    private fun removeLateGroup() {
        removeOurGroup { confirmed -> onRemoval(confirmed) }
    }

    /**
     * Removes this attempt's group and confirms its absence (A9 review P1-2, P2-1): a readable group named [ssid] is
     * removed; an unreadable group that groupFormed reports is removed only because this instance created or joined it
     * and the record still names it (a record that cannot be read, as during an emergency wipe, does not stop the
     * removal); another name is never removed. Unconfirmed -> the quarantine.
     */
    private fun removeOurGroup(onConfirmed: (Boolean) -> Unit) {
        val expected = ssid
        val recordMatches = expected != null && record.containsOrNull(expected) != false
        DirectRemoval(port, scheduler, alive = { !terminated }, track = { timers += it }).run(
            isOurs = { name -> name == expected },
            removeUnreadable = createdOrJoined && recordMatches,
        ) { outcome ->
            onConfirmed(
                outcome == DirectRemoval.Outcome.Absent ||
                    outcome is DirectRemoval.Outcome.Other ||
                    outcome is DirectRemoval.Outcome.Removed,
            )
        }
    }

    private fun fail(failure: DirectFailure, keepClaim: Boolean = false) {
        if (failureReported || terminated) return
        failureReported = true
        if (DirectPolicy.countsAsLocalFault(failure)) {
            ledger.recordLocalFault("direct_" + failure.name.lowercase())
        }
        if (!closed) events.onFailed(failure)
        terminate(keepClaim)
    }

    private fun terminate(keepClaim: Boolean = false) {
        if (terminated || terminating) return
        terminating = true
        if (keepClaim) quarantineMoved = true
        armWatchdog()
        when (lifecycle.stop()) {
            NearbyGroupLifecycle.Cleanup.REMOVE_GROUP -> removeOurGroup { confirmed -> onRemoval(confirmed) }
            NearbyGroupLifecycle.Cleanup.CLOSE_CHANNEL -> {
                closeChannelOnly()
                finishTerminate()
            }
            NearbyGroupLifecycle.Cleanup.NONE -> {
                if (!pendingCreate) {
                    closeChannelOnly()
                    finishTerminate()
                }
                // else the create callback (or the watchdog) continues the chain
            }
        }
    }

    private fun onRemoval(confirmed: Boolean) {
        if (terminated) return
        if (confirmed) {
            ssid?.let { record.tryRemove(it) } // P1-3: a failure (wipe, Keystore) keeps the name; the group is gone
            closeChannelOnly()
            finishTerminate()
        } else {
            if (!quarantineMoved) {
                quarantine.enter(ssid, LEDGER_OWNER, owned = true)
                quarantineMoved = true
            }
            closeChannelOnly()
            finishTerminate()
        }
    }

    private fun armWatchdog() {
        if (watchdog != null) return
        watchdog = scheduler.post(DirectLimits.CLEANUP_WATCHDOG_MS) {
            if (terminated) return@post
            // P3-4: a create or join still pending may form after the channel closes; the quarantine finds it by name.
            if ((createdOrJoined || pendingCreate) && !quarantineMoved) {
                quarantine.enter(ssid, LEDGER_OWNER, owned = true)
                quarantineMoved = true
            }
            closeChannelOnly()
            finishTerminate()
        }
    }

    private fun closeChannelOnly() {
        if (!channelOpen) return
        channelOpen = false
        port.closeChannel()
    }

    private fun finishTerminate() {
        if (terminated) return
        terminated = true
        watchdog?.cancel()
        pollTimer?.cancel()
        timers.forEach { runCatching { it.cancel() } }
        timers.clear()
        if (reserved && !quarantineMoved) {
            ledger.releaseDirect(LEDGER_OWNER)
            reserved = false
        }
        val waiters = closeWaiters.toList()
        closeWaiters.clear()
        waiters.forEach { it() }
    }

    private fun onChannelLost() {
        if (lostReported || closed || terminated) return
        lostReported = true
        if (!closed) events.onLost()
        if (createdOrJoined && !quarantineMoved) {
            quarantine.enter(ssid, LEDGER_OWNER, owned = true)
            quarantineMoved = true
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        val timer = scheduler.post(delayMs, block)
        timers += timer
    }

    private fun schedulePoll(delayMs: Long, block: () -> Unit) {
        pollTimer?.cancel()
        val timer = scheduler.post(delayMs, block)
        pollTimer = timer
        timers += timer
    }
}

/** Production scheduler: one fresh tag per post, so cancel reaches exactly that post. */
internal class DirectSchedulerAdapter(private val wifi: WifiScheduler) : DirectGroup.Scheduler {
    override fun post(delayMs: Long, block: () -> Unit): DirectGroup.Cancelable {
        val tag = Any()
        wifi.postDelayed(tag, delayMs, block)
        return DirectGroup.Cancelable { wifi.cancel(tag) }
    }
}
