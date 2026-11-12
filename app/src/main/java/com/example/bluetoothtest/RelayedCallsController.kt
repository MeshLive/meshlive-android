package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** What the activity knows that decides whether this phone helps (read on the main thread, cached for admission). */
internal data class HelperDeviceState(
    val bluetoothReady: Boolean,
    /** The call endpoint exists and is available for calls (it may be serving a request right now). */
    val callReady: Boolean,
    /** The call endpoint's transport, null while there is none. */
    val transport: BluetoothTransportOption?,
    val batteryPercent: Int,
    val charging: Boolean,
    val batterySaver: Boolean,
    val thermalSevere: Boolean,
    /** The user's own call, ringing call, stream, viewing or message exchange. */
    val ownActivity: Boolean,
    val screenOn: Boolean,
    val rotatingIdsOn: Boolean,
)

/** Design OR (DEBUG prototype): the phone-wide search budgets and the FAIL replay cache of this process. */
internal object RelaySeekHelperState {
    val budgets = RelaySeekBudgets(SystemClock::elapsedRealtime)
    val failCache = RelaySeekFailCache(SystemClock::elapsedRealtime)

    /** Advertiser addresses whose link failed the opening of a sought ID, by epoch: not dialled again in that epoch. */
    val badAddresses = RelaySeekBadAddresses()
}

/**
 * The runtime of experimental relayed calls (DECISIONS Design C) for one activity: the reach table, the idle
 * scan, the reach pages, SETUP admission and the helper state shown in Settings. It exists only while the
 * experiment is on; the activity creates it in applyRelayedCalls and drops it when the switch goes off.
 * All radio work is bounded by [RelayHelperPolicy]; a relay already running is never cut by a pause.
 */
internal class RelayedCallsController(
    private val adapterProvider: () -> BluetoothAdapter?,
    private val deviceState: () -> HelperDeviceState,
    /** The foreground notification text: relaying (with the relay count) or back to ready. */
    private val onRelayNotification: (relaying: Boolean, relays: Int) -> Unit,
    /** The helper state or table changed in a way Settings or the call list shows. */
    private val onChanged: () -> Unit,
    /**
     * The call offer on air no longer matches the helper/consent state: restart the idle endpoint. True when the
     * endpoint was really stopped for a rebuild; false when it was busy (asked again at the next tick).
     */
    private val onOfferStale: () -> Boolean,
) : RelayHelperHost {
    private val handler = Handler(Looper.getMainLooper())
    private val localNodeId = AndroidNodeIdentityStore().getOrCreate().nodeId
    private val localPrefix = localNodeId.toByteArray().copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES)

    val table = ReachTable<BleL2capPeer>(
        localPrefix,
        ReachConfig(maxRelays = RelayedCallsFeature.maxRelays),
        clock = RelayedCallsRuntime::nowMs,
        directSightings = ::directSightings,
    )
    /**
     * Rotating IDs + relayed calls (PLAN C4, reach pages version 6): the version 5 population has its own table,
     * flushed at every boundary together with the reach pages' set (RV6).
     */
    val rotatingTable = ReachTable<BleL2capPeer>(
        ByteArray(DiscoveryAnnouncement.NODE_PREFIX_BYTES),
        ReachConfig(maxRelays = RelayedCallsFeature.maxRelays),
        clock = RelayedCallsRuntime::nowMs,
        rotating = true,
        ownIds = { RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER) },
        directSightings = ::directSightings,
    )
    private fun directSightings(): Boolean = !RelayedCallsFeature.noDirectSightings
    private var boundaryListening = false
    private val boundaryListener: (Long) -> Unit = { _ -> onBoundary() }
    @Volatile private var advertiserHoldUntilMs = 0L
    private var advertiserRotating = false
    private val limiter = RelaySetupRateLimiter(RelayedCallsRuntime::nowMs)

    @Volatile private var snapshot: HelperDeviceState? = null
    @Volatile var plan: RelayHelperPlan = RelayHelperPolicy.plan(inputsFor(null))
        private set
    @Volatile private var relaying = false

    /** The call offer on air and its staleness check (RelayedOfferRestart). */
    private val offer = RelayedOfferTracker()
    private var scanner: BleL2capDiscovery? = null
    private var scannerMode: HelperScan? = null
    private var scannerStartedMs = 0L
    private var scannerRetryAtMs = 0L
    private var advertiser: ReachAdvertiser? = null
    private var advertiserTransport: BluetoothTransportOption? = null
    private var lastStateLine: String? = null
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        RelayedCallsRuntime.table = table
        RelayedCallsRuntime.rotatingTable = rotatingTable
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        stopScanner()
        stopAdvertiser()
        if (boundaryListening) {
            boundaryListening = false
            RotatingDiscovery.removeBoundaryListener(boundaryListener)
        }
        RelayedCallsRuntime.table = null
        RelayedCallsRuntime.rotatingTable = null
        table.clear()
        rotatingTable.clear()
        relaying = false
    }

    /**
     * Rotating IDs (RV6): a boundary flushes the version 5 table (its entries name last epoch's IDs) and stops
     * the version 6 pages for the usual 1-8 s silence; the next refresh starts them on a new set with the new
     * RDI. Learned entries need their sightings and five seconds again, so relayed reach is briefly empty.
     */
    private fun onBoundary() {
        rotatingTable.clear()
        stopAdvertiser()
        advertiserHoldUntilMs = SystemClock.elapsedRealtime() + RotatingDiscovery.boundarySilenceMs()
        handler.postDelayed({ refresh() }, advertiserHoldUntilMs - SystemClock.elapsedRealtime() + 100L)
    }

    /** Re-evaluates the helper policy and starts or stops the scan and the reach pages (main thread). */
    fun refresh() {
        val state = deviceState()
        snapshot = state
        val previous = plan
        val rotatingNow = RelayedCallsFeature.rotatingActive(state.rotatingIdsOn)
        if (rotatingNow != boundaryListening) {
            boundaryListening = rotatingNow
            if (rotatingNow) {
                RotatingDiscovery.addBoundaryListener(boundaryListener)
            } else {
                RotatingDiscovery.removeBoundaryListener(boundaryListener)
                rotatingTable.clear()
            }
        }
        plan = RelayHelperPolicy.plan(inputsFor(state))
        val line = "state=${if (plan.helping) "RUNNING" else "PAUSED"} | reason=${plan.pause.name} | scan=${plan.scan.name} | relaying=$relaying"
        if (line != lastStateLine) {
            lastStateLine = line
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "relayed_helper: $line")
        }
        // The offer on air carries the helper flag and the consent bit from when it opened. If the policy or the
        // user's choice changed since, a restart makes callers see the truth (an idle endpoint only).
        offer.check(
            callReady = state.callReady,
            relaying = relaying,
            liveHelping = { liveHelping(state) },
            consent = ::consentOn,
            restart = onOfferStale,
        )
        if (plan.helping && RelaySeekFeature.tablesOff()) {
            // Design OR arm O: no reach-table scan and no reach pages; searches arrive over the call listener.
            stopScanner()
            stopAdvertiser()
        } else if (plan.helping) {
            ensureScanner(plan.scan)
            if (!relaying) ensureAdvertiser(state.transport) else stopAdvertiser()
        } else {
            stopScanner()
            stopAdvertiser()
        }
        if (BuildConfig.DEBUG && ++ticks % TABLE_LOG_TICKS == 0) {
            Log.i(
                "BluetoothVideo",
                "BENCHMARK relayed_reach_table: entries=${table.size} | rotating_entries=${rotatingTable.size} | " +
                    "pages=${RelayedCallsRuntime.pagesSeen} | offers=${RelayedCallsRuntime.offersSeen} | " +
                    "costs=${table.costSummary()} | rotating_costs=${rotatingTable.costSummary()}",
            )
        }
        if (previous.pause != plan.pause) onChanged()
    }

    /**
     * A new call endpoint is about to be created (main thread): the helper bits its offer and hellos sign are read from
     * the device state now, with this endpoint's own transport, instead of a cached snapshot up to 5 s old. A snapshot
     * from before the endpoint existed (no transport) or from during the call that just ended (own activity) made the
     * offer disagree with the first tick, and that tick rebuilt the endpoint under an incoming link (rc-ctl-20261106).
     * Off with the restart guard's control arm.
     */
    fun endpointStarting(transport: BluetoothTransportOption) {
        if (!DebugTunables.relayedRestartGuard) return
        // Every reader of the snapshot until the next tick is this endpoint, which is call-ready once it listens.
        snapshot = deviceState().copy(callReady = true, transport = transport)
    }

    fun statusLine(): String = RelayHelperPolicy.statusLine(plan, snapshot?.batteryPercent ?: -1)

    private fun inputsFor(state: HelperDeviceState?): RelayHelperInputs = RelayHelperInputs(
        featureOn = RelayedCallsFeature.enabled,
        helpOn = RelayedCallsFeature.helpEnabled,
        rotatingIdsOn = state?.rotatingIdsOn ?: RotatingDiscoveryFeature.enabled,
        rotatingCompat = RelayedCallsFeature.rotatingCompat,
        leTransport = state?.transport?.let { it != BluetoothTransportOption.CLASSIC_RFCOMM } ?: false,
        gattHelperBlocked = RelayedCalls.gattHelperDutyBlocked(state?.transport),
        bluetoothReady = state?.bluetoothReady ?: false,
        callReady = state?.callReady ?: false,
        batteryPercent = state?.batteryPercent ?: -1,
        charging = state?.charging ?: false,
        batterySaver = state?.batterySaver ?: false,
        thermalSevere = state?.thermalSevere ?: false,
        ownActivity = state?.ownActivity ?: false,
        screenOn = state?.screenOn ?: true,
        seekScopeBlocked = RelaySeekFeature.active() && run {
            val rotatingIds = state?.rotatingIdsOn ?: RotatingDiscoveryFeature.enabled
            !RelaySeekFeature.helperScope(rotatingIds, Bmh3Feature.enabled, Bmh3PseudonymFeature.active(rotatingIds))
        },
    )

    // ----- scan and reach pages -----

    private fun ensureScanner(mode: HelperScan) {
        val now = SystemClock.elapsedRealtime()
        val running = scanner
        // R19 (corrected, batch S-02): AOSP downgrades a filtered scan after 10 minutes on Android 14+ and 30 minutes
        // on Android 10-13, so the scan restarts at 8 / 25 minutes (ScanRenewalPolicy; `test_scan_renewal_legacy`
        // restores the old 20) and whenever the screen/charging state changes the mode. The age restart needs a free
        // scan-start slot: without one the running scan stays and the next tick asks again instead of queueing a gap.
        // With the app-wide renewal on (`test_scan_renewal_all`) that coordinator owns the age.
        if (running != null && scannerMode == mode && !ageRestartDue(now)) return
        if (running == null && now < scannerRetryAtMs) return
        stopScanner()
        val adapter = adapterProvider() ?: return
        scannerMode = mode
        scannerStartedMs = now
        scanner = BleL2capDiscovery(
            adapter,
            onPeer = {},
            onFinished = {},
            onError = { message ->
                handler.post {
                    Log.w("BluetoothVideo", "relayed_helper: idle scan stopped: $message")
                    stopScanner()
                    scannerRetryAtMs = SystemClock.elapsedRealtime() + SCAN_RETRY_MS
                }
            },
            acceptAnnouncement = { it.isPrivateCallOffer() },
            scanDurationMs = 0L,
            startLowLatency = false,
            lowPower = mode == HelperScan.LOW_POWER,
            acceptRotating = RelayedCallsFeature.rotatingActive(RotatingDiscoveryFeature.enabled),
        ).also { it.start() }
    }

    private fun ageRestartDue(now: Long): Boolean {
        val budget = runCatching { BluetoothTransportPolicy.scanBudget() }.getOrNull()
        return ScanRenewalPolicy.ageRestartDue(
            ageMs = now - scannerStartedMs,
            intervalMs = ScanRenewalPolicy.intervalMs(android.os.Build.VERSION.SDK_INT),
            gateHasSlot = ScanStartGate.shared.available(budget),
            appWideRenewal = ScanSwitches.renewalAll,
        )
    }

    private fun stopScanner() {
        scanner?.close()
        scanner = null
        scannerMode = null
    }

    private fun ensureAdvertiser(transport: BluetoothTransportOption?) {
        val adapter = adapterProvider()
        if (adapter == null || transport == null || transport == BluetoothTransportOption.CLASSIC_RFCOMM) {
            stopAdvertiser()
            return
        }
        val rotatingPages = RelayedCallsFeature.rotatingActive(RotatingDiscoveryFeature.enabled)
        if (rotatingPages && (RotatingDiscovery.visibleByHandle() || SystemClock.elapsedRealtime() < advertiserHoldUntilMs)) {
            // Inside a Visible-by-handle window the phone advertises fixed-prefix offers, and across a boundary
            // the old set stays silent: no version 6 page names an ID that no offer carries.
            stopAdvertiser()
            return
        }
        if (advertiser != null && advertiserTransport == transport && advertiserRotating == rotatingPages) return
        stopAdvertiser()
        advertiserTransport = transport
        advertiserRotating = rotatingPages
        advertiser = if (rotatingPages) {
            ReachAdvertiser(
                adapter,
                { RotatingDiscovery.currentId(RotatingDiscoveryIds.ROLE_CALL_OFFER) },
                transport,
                rotatingTable::advertisable,
                rotating = true,
            )
        } else {
            ReachAdvertiser(adapter, { localPrefix }, transport, table::advertisable)
        }.also { it.start() }
    }

    private fun stopAdvertiser() {
        advertiser?.close()
        advertiser = null
        advertiserTransport = null
    }

    // ----- RelayHelperHost -----

    override fun consentOn(): Boolean = RelayedCallsFeature.consentAllowed(snapshot?.rotatingIdsOn ?: RotatingDiscoveryFeature.enabled)

    /**
     * Asked by a live endpoint (opening its offer, signing a hello), so the endpoint's own existence counts as
     * call-ready here: the periodic plan only turns "running" once the endpoint is available, which is too late
     * for the first offer.
     */
    override fun helperActive(): Boolean =
        !relaying && liveHelping(snapshot) && !(RelaySeekFeature.active() && RelayDutyReservation.shared.held)

    /**
     * Design OR: a helper whose search budgets have been exhausted for 60 s drops `FLAG_RELAY_CAPABLE` at its next offer
     * restart, until they recover.
     */
    private fun liveHelping(state: HelperDeviceState?): Boolean =
        RelayHelperPolicy.plan(inputsFor(state).copy(callReady = true)).helping &&
            !(RelaySeekFeature.active() && RelaySeekHelperState.budgets.exhaustedFor(RelaySeekBudgets.FLAG_WITHDRAW_AFTER_MS))

    override fun offerOpened(helperActive: Boolean, consent: Boolean) {
        offer.opened(helperActive, consent)
    }

    override fun admit(
        parentNodeId: NodeId,
        target: ByteArray,
        budget: Int,
        transport: BluetoothTransportOption,
        rotating: Boolean,
        parentKey: String?,
    ): RelayAdmission {
        val activeTable = if (rotating) rotatingTable else table
        val state = snapshot ?: deviceState()
        val live = RelayHelperPolicy.plan(inputsFor(state))
        val featureOn = RelayedCallsFeature.enabled
        // A SETUP over a GATT link (a dual-listening secondary) is refused where the family policy keeps helping on CoC.
        val allowed = RelayedCallsFeature.helperAllowed(state.rotatingIdsOn) && !RelayedCalls.gattHelperDutyBlocked(transport)
        val paused = live.paused
        val busy = relaying || state.ownActivity
        // The rate limit counts only requests that could have been served, so a paused phone costs nothing.
        val limited = if (!featureOn || !allowed || paused || busy) {
            false
        } else {
            !limiter.tryAdmit(parentKey ?: parentNodeId.toByteArray().prefixHex())
        }
        val accept = { offer: DiscoveryAnnouncement -> offer.transportOption() == transport }
        val route = if (featureOn && !paused && budget in 1..RelayedCallsFeature.maxRelays) {
            activeTable.setupRoute(target, budget, accept, RelayedCallsFeature.nextHopPin)
        } else {
            null
        }
        val parent = parentNodeId.toByteArray()
        val refusal = RelaySetupAdmission.refusal(
            RelaySetupInputs(
                featureOn = featureOn,
                helperAllowed = allowed,
                paused = paused,
                busy = busy,
                rateLimited = limited,
                parentIsSelf = parent.contentEquals(localNodeId.toByteArray()),
                // A rotating target is an RDI: the parent equal to the target is caught by parentProblem after the
                // child authenticates (the parent cannot be its own child).
                parentIsTarget = !rotating && parent.copyOf(DiscoveryAnnouncement.NODE_PREFIX_BYTES).contentEquals(target),
                targetIsSelf = if (rotating) {
                    RotatingDiscovery.ownRdis(RotatingDiscoveryIds.ROLE_CALL_OFFER).any { it.contentEquals(target) }
                } else {
                    target.contentEquals(localPrefix)
                },
                budget = budget,
                maxRelays = RelayedCallsFeature.maxRelays,
                routeAvailable = route != null,
            ),
        )
        // AP8: a pinned helper never searches for another next hop; a missing pinned route is the existing NO_ROUTE FAIL.
        if (refusal == RelaySetupFailure.NO_ROUTE && rotating && RelaySeekFeature.active() &&
            RelayedCallsFeature.nextHopPin == null
        ) {
            // Design OR: a plain SETUP without a table route is handled as a search for its one ID (budget clamped to 2).
            return RelayAdmission.Search(target.copyOf(), budget.coerceAtMost(RelaySeek.MAX_BUDGET))
        }
        if (refusal != null) return RelayAdmission.Refuse(refusal)
        val chosen = requireNotNull(route)
        val nextHopPrefix = chosen.nextHop
        if (nextHopPrefix == null) {
            return RelayAdmission.Accept(chosen.hop.peer, nextBudget = 0, hopPrefix = null, rotating = rotating)
        }
        val next = BleL2capPeer(
            device = chosen.hop.peer.device,
            label = chosen.hop.peer.label,
            announcement = RelayedCalls.routedAnnouncement(target, budget - 1, chosen.hop.announcement),
        )
        return RelayAdmission.Accept(next, nextBudget = budget - 1, hopPrefix = nextHopPrefix, rotating = rotating)
    }

    override fun relayStarted(relays: Int) {
        relaying = true
        handler.post {
            stopAdvertiser()
            onRelayNotification(true, relays)
            onChanged()
        }
    }

    override fun relayEnded() {
        relaying = false
        handler.post {
            onRelayNotification(false, 0)
            refresh()
            onChanged()
        }
    }

    override fun routeOutcome(route: RelayRoute, ready: Boolean, reason: RelaySetupFailure?) {
        val routeTable = if (route.rotating) rotatingTable else table
        if (ready) {
            routeTable.markUsed(route.helperPrefix, route.target)
        } else {
            routeTable.markFailed(route.helperPrefix, route.target, noRoute = reason == RelaySetupFailure.NO_ROUTE)
        }
        handler.post { onChanged() }
    }

    // ----- Design OR: on-demand route search (DEBUG prototype) -----

    override fun seekFeatures(): Int? {
        if (!RelaySeekFeature.active()) return null
        val state = snapshot ?: return null
        if (!RelaySeekFeature.helperScope(state.rotatingIdsOn, Bmh3Feature.enabled, Bmh3PseudonymFeature.active(state.rotatingIdsOn))) return null
        if (!helperActive() || RelaySeekHelperState.budgets.exhausted()) return null
        return RelaySeek.FEATURE_SEEK_V1
    }

    override fun admitSeek(budget: Int, requestedMs: Int, transport: BluetoothTransportOption): SeekAdmission {
        val state = snapshot ?: deviceState()
        val live = RelayHelperPolicy.plan(inputsFor(state))
        if (!RelaySeekFeature.active() || !RelayedCallsFeature.helperAllowed(state.rotatingIdsOn) || live.paused) {
            return SeekAdmission.Refuse(RelaySetupFailure.REFUSED, if (live.paused) live.pause.name.lowercase() else "off")
        }
        if (transport == BluetoothTransportOption.CLASSIC_RFCOMM) return SeekAdmission.Refuse(RelaySetupFailure.REFUSED, "classic")
        if (RelayedCalls.gattHelperDutyBlocked(transport)) return SeekAdmission.Refuse(RelaySetupFailure.REFUSED, "gatt_family")
        if (relaying || state.ownActivity) return SeekAdmission.Refuse(RelaySetupFailure.BUSY, "busy")
        if (budget !in 1..minOf(RelaySeek.MAX_BUDGET, RelayedCallsFeature.maxRelays)) {
            return SeekAdmission.Refuse(RelaySetupFailure.REFUSED, "budget")
        }
        val owner = Any()
        if (!RelayDutyReservation.shared.tryAcquire(owner)) return SeekAdmission.Refuse(RelaySetupFailure.BUSY, "duty")
        return when (val admission = RelaySeekHelperState.budgets.admit(budget, requestedMs.toLong())) {
            is RelaySeekBudgets.Admission.Accept -> SeekAdmission.Accept(owner, admission.allowanceMs)
            is RelaySeekBudgets.Admission.Refuse -> {
                RelayDutyReservation.shared.release(owner)
                logBudget()
                SeekAdmission.Refuse(RelaySetupFailure.REFUSED, "budget_" + admission.reason)
            }
        }
    }

    override fun seekFinished(owner: Any, startedMs: Long, ready: Boolean) {
        RelaySeekHelperState.budgets.finish(owner, startedMs, ready)
        logBudget()
    }

    override fun seekCharge(owner: Any, dial: Boolean) {
        if (dial) RelaySeekHelperState.budgets.chargeDial(owner) else RelaySeekHelperState.budgets.chargeHandshake(owner)
    }

    /**
     * The on-demand low-latency scan of a search: only while two scan starts stay free in the app's 30 s window for the
     * owner and the scan budget has time left; its results reach the search through [RelayedCallsRuntime.seekOffers].
     */
    override fun seekScan(owner: Any, untilMs: Long, done: () -> Boolean): Long {
        val adapter = adapterProvider() ?: return 0L
        val budget = runCatching { BluetoothTransportPolicy.scanBudget() }.getOrNull()
        val freeStarts = budget?.let { it.maxStarts - ScanStartGate.shared.used(it) } ?: Int.MAX_VALUE
        val scanLeft = RelaySeekHelperState.budgets.scanLeftMs()
        val startMs = SystemClock.elapsedRealtime()
        val endMs = minOf(untilMs, startMs + scanLeft)
        if (freeStarts < OWNER_SCAN_STARTS + 1 || endMs - startMs < MIN_SCAN_MS) {
            Log.i(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                    "relayed_seek_scan: start_ms=-1 | first_result_ms=-1 | refused=${if (freeStarts < OWNER_SCAN_STARTS + 1) "quota" else "budget"}",
            )
            return 0L
        }
        val firstResultMs = java.util.concurrent.atomic.AtomicLong(-1L)
        val started = java.util.concurrent.CountDownLatch(1)
        val scan = java.util.concurrent.atomic.AtomicReference<BleL2capDiscovery?>(null)
        handler.post {
            scan.set(
                BleL2capDiscovery(
                    adapter,
                    onPeer = { firstResultMs.compareAndSet(-1L, SystemClock.elapsedRealtime() - startMs) },
                    onFinished = {},
                    onError = { message -> Log.w("BluetoothVideo", "relayed_seek_scan: error | $message") },
                    acceptAnnouncement = { it.isPrivateCallOffer() },
                    scanDurationMs = 0L,
                    reportDuplicates = true,
                    continuousLowLatency = true,
                    startLowLatency = true,
                    acceptRotating = true,
                ).also { it.start() },
            )
            started.countDown()
        }
        val launchedMs = try {
            started.await(1, java.util.concurrent.TimeUnit.SECONDS)
            SystemClock.elapsedRealtime() - startMs
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1L
        }
        try {
            while (SystemClock.elapsedRealtime() < endMs && !done()) Thread.sleep(SCAN_POLL_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            handler.post { scan.getAndSet(null)?.close() }
        }
        val scannedMs = SystemClock.elapsedRealtime() - startMs
        RelaySeekHelperState.budgets.chargeScan(scannedMs, owner)
        Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") +
                "relayed_seek_scan: start_ms=$launchedMs | first_result_ms=${firstResultMs.get()} | refused=none | scanned_ms=$scannedMs",
        )
        return scannedMs
    }

    private fun logBudget() {
        if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK " + RelaySeekHelperState.budgets.line())
    }

    private companion object {
        /** Scan starts kept free for the owner's own actions in the app's 30 s window. */
        const val OWNER_SCAN_STARTS = 2
        const val MIN_SCAN_MS = 300L
        const val SCAN_POLL_MS = 100L
        const val TICK_MS = 5_000L
        const val TABLE_LOG_TICKS = 6
        const val SCAN_RETRY_MS = 30_000L
    }
}
