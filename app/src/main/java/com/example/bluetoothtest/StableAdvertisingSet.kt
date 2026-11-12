package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A legacy (BLE 4.x format) advertisement whose low-latency -> balanced step keeps the same
 * advertising set, and therefore the same random address.
 *
 * Root cause (GATT call timeouts, voice-ptt-s24a15s-51cc8028 case 0004): the servers advertised at
 * low latency for 10 s and then called stopAdvertising + startAdvertising in balanced mode. Every new
 * advertising set gets a new resolvable private address. The S24 replaced ..:6a:97 with ..:b4:ba at
 * 15:09:36.005-.022; the A15s, which had just seen ..:6a:97, dialled it at 15:09:36.105 and its
 * direct connect never completed (await_timeout after 12 s). The step runs exactly 10 s after a
 * peer becomes available, so a call placed about then always raced it.
 *
 * Here the step disables the running set, changes its parameters and enables it again. Both stacks
 * keep the address: Android 10 `btm_ble_multi_adv.cc` regenerates it only on the rotation timer, and
 * the gd `le_advertising_manager.cc` `set_parameters` reuses `current_address`. Parameters equal what
 * BluetoothLeAdvertiser.startAdvertising builds for ADVERTISE_MODE_LOW_LATENCY/BALANCED with
 * TX_POWER_HIGH/MEDIUM. A step that fails or stalls falls back to the previous stop + restart; that
 * replacement is bounded (it must start within [REPLACEMENT_TIMEOUT_MS]) and a failure reaches the
 * owner through `onLost`, so no owner believes it advertises with nothing live.
 *
 * Scope (review 2026-10-09): a new set gets a new resolvable private address only when LE privacy
 * is on (Android 10 `btm_ble_multi_adv.cc:240-267`; every phone here advertises with an RPA), and
 * the race is the app's own stop/restart, not a stack regression. The same set still rotates its
 * address on the stack's privacy timer, so "same address" holds only until the next rotation.
 * DEBUG `test_stable_adv_address=false` always uses stop + restart (control arm).
 *
 * Rotating discovery IDs (PROTOCOL "Pre-handshake exposure"): a connectable set made while the switch is on
 * is non-discoverable on API 34+ ([NonDiscoverableAdvertising]); the in-place balanced step keeps the same
 * value, because the stack re-reads `discoverable` whenever parameters are set. A stack that refuses such a
 * start (feature unsupported, internal error, or an exception) gets the set started discoverable again,
 * visibly and logged, so a privacy flag never costs reachability; a refusal that is not an internal error is
 * remembered for the process.
 *
 * Device-family batch A (backlog A-01..A-03, entries in [AdvertisingPolicy]): the start never throws into the
 * caller ([AdvertisingStart]: an oversize payload or an adapter that is off becomes a failure status); a refused
 * start (TOO_MANY_ADVERTISERS) teaches [AdvertisingBudget], releases a lower-priority title or reach set and
 * retries once; starts wait for stops still in flight, because the stack frees an instance asynchronously; and a
 * set that stops without being asked, or stays disabled past the stack's address-rotation window, is restarted by
 * [AdvertisingWatchdog] with back-off. The stable set always runs with duration 0 and no event limit.
 */
internal class StableAdvertisingSet(
    private val adapter: BluetoothAdapter,
    private val connectable: Boolean,
    private val data: AdvertiseData,
    private val scanResponse: AdvertiseData,
    private val label: String,
    private val onStatus: (String) -> Unit,
    /**
     * Called on the main thread when a fallback left nothing advertising (the replacement set
     * failed, threw or did not start within [REPLACEMENT_TIMEOUT_MS]). The owner must clear its
     * advertising state and may start again.
     */
    private val onLost: (String) -> Unit,
    /** Decided once per set object (one per epoch while rotating): non-discoverable on API 34+ with the switch on. */
    private val nonDiscoverable: Boolean = false,
    /**
     * Connections this phone holds as peripheral right now. Only used to record whether a connectable set could be
     * (re)started while connected (`adv-while-connected-le-states`); the default reports none.
     */
    private val peripheralLinks: () -> Int = { 0 },
    /**
     * B2 ([DiscoverableWhenNeutral]): a call-offer or viewer-request set that is discoverable while the adapter name
     * is "Phone" (re-checked before every start), and restarted hidden when the name leaves "Phone". Null: not covered.
     */
    private val neutralRole: DiscoverableWhenNeutral.Role? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var current: SetCallback? = null
    private val budget get() = AdvertisingBudget.shared
    private val watchdog = AdvertisingWatchdog()

    /** Serialises set starts (owner starts and watchdog restarts) so two never race for the same instance. */
    private val startLock = Any()

    /** The owner asked for the set to stop: nothing restarts it. Reset by [start]. */
    @Volatile private var ownerStopped = false

    /** Bumped by every owner [start] and [stop]: a recovery that began before the change gives up. */
    @Volatile private var generation = 0

    /** The last request failed locally (threw) rather than through the stack's callback. */
    @Volatile private var requestThrew = false
    private var stuckAnnounced = false
    private val quiesceHandler = Handler(Looper.getMainLooper())

    /** The value the parameters carry now: the decision, unless this process saw a stack refuse it. */
    private fun discoverableOff(): Boolean =
        nonDiscoverable && !NonDiscoverableAdvertising.rejected && !discoverableFallback && !neutralDiscoverable

    /** B2: the last start found the adapter name "Phone" with the switch on, so this set is discoverable. */
    @Volatile private var neutralDiscoverable = false

    private val nameListener = DiscoverableWhenNeutral.Listener { onNameLeftNeutral() }

    /** B2: decides, before a start, whether the neutral name makes this set discoverable (reads the name now). */
    private fun refreshNeutral() {
        val role = neutralRole ?: return
        if (!nonDiscoverable || NonDiscoverableAdvertising.rejected || discoverableFallback) {
            // Already discoverable for another reason (rotating off, control arm, stack refusal): nothing to decide.
            neutralDiscoverable = false
            DiscoverableWhenNeutral.unregister(nameListener)
            return
        }
        val decision = DiscoverableWhenNeutral.decideNow(adapter)
        neutralDiscoverable = decision.discoverable
        DiscoverableWhenNeutral.log(role, decision)
        if (decision.discoverable) DiscoverableWhenNeutral.register(nameListener) else DiscoverableWhenNeutral.unregister(nameListener)
    }

    /**
     * B2: the adapter name left "Phone". Later starts are hidden; a running discoverable set is stopped and its owner
     * restarts it through [onLost] (a new, non-discoverable set).
     */
    private fun onNameLeftNeutral() {
        val role = neutralRole ?: return
        val restart = synchronized(this) {
            if (!neutralDiscoverable) return
            neutralDiscoverable = false
            val running = current
            val discoverableNow = running != null && !running.stopped && !running.nonDiscoverable
            if (discoverableNow && !ownerStopped) stopLocked()
            discoverableNow && !ownerStopped
        }
        DiscoverableWhenNeutral.log(role, DiscoverableWhenNeutral.Decision(false, DiscoverableWhenNeutral.Reason.NAME_CHANGED))
        if (restart) restartHidden()
    }

    /** B2: the owner starts the set again (its next start reads the name and stays hidden). */
    private fun restartHidden() {
        onStatus("$label advertising: the Bluetooth name changed; restarting with the name hidden")
        handler.post { if (!ownerStopped) onLost("bluetooth name changed") }
    }

    /**
     * B2: the name left "Phone" while [callback] was starting discoverable (between the name read and the stack's
     * answer): stop it as [onNameLeftNeutral] would have.
     */
    private fun hideIfNameLeftDuringStart(callback: SetCallback) {
        if (neutralRole == null || callback.nonDiscoverable || neutralDiscoverable || !discoverableOffBase()) return
        val restart = synchronized(this) {
            val stillRunning = current === callback && !callback.stopped && !ownerStopped
            if (stillRunning) stopLocked()
            stillRunning
        }
        if (restart) restartHidden()
    }

    /** The decision without B2 (rotating switch, control arm, stack refusal). */
    private fun discoverableOffBase(): Boolean = nonDiscoverable && !NonDiscoverableAdvertising.rejected && !discoverableFallback

    /** This set object's own start was refused once (a transient code): it stays discoverable. */
    @Volatile private var discoverableFallback = false

    /**
     * Starts a new set and waits for the stack (call off the main thread). Returns the start status
     * (AdvertiseCallback codes; 0 is success) or null on timeout. Throws when advertising is unavailable.
     */
    @SuppressLint("MissingPermission")
    fun start(lowLatency: Boolean, timeoutSeconds: Long): Int? {
        ownerStopped = false
        generation++
        // B2 decides per attempt (after the name is read), so the refusal path follows the base decision here.
        if (!discoverableOffBase()) {
            return startOnce(lowLatency, timeoutSeconds)
        }
        requestThrew = false
        val outcome = runCatching { startOnce(lowLatency, timeoutSeconds) }
        val error = outcome.exceptionOrNull()
        if (error is IOException) throw error
        val status = outcome.getOrNull()
        // A request that threw counts as a refusal of the hidden parameters (as before), except an adapter that is off.
        val threw = error != null || (requestThrew && status != AdvertisingStart.STATUS_ADAPTER_OFF)
        // A set B2 made discoverable did not ask for hidden parameters: its failure is not a refusal of them.
        if (neutralDiscoverable || !NonDiscoverableAdvertising.refusedBy(status, threw)) return outcome.getOrThrow()
        // The stack refused the non-discoverable parameters: this set starts discoverable, and a refusal that is
        // not transient (unsupported feature, an exception) is remembered for the process.
        discoverableFallback = true
        if (status != AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR) NonDiscoverableAdvertising.rejected = true
        android.util.Log.w(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_adv: event=rejected | label=$label | " +
                "code=${status ?: error?.javaClass?.simpleName} | remembered=${NonDiscoverableAdvertising.rejected} | action=discoverable",
        )
        onStatus("$label advertising: this phone's Bluetooth refused a hidden name; starting with the normal advertisement")
        return startOnce(lowLatency, timeoutSeconds)
    }

    @SuppressLint("MissingPermission")
    private fun startOnce(lowLatency: Boolean, timeoutSeconds: Long): Int? =
        synchronized(startLock) { startOnceLocked(lowLatency, timeoutSeconds) }

    @SuppressLint("MissingPermission")
    private fun startOnceLocked(lowLatency: Boolean, timeoutSeconds: Long): Int? {
        val advertiser = adapter.bluetoothLeAdvertiser ?: throw IOException("BLE advertising is unavailable")
        var status = attempt(advertiser, lowLatency, timeoutSeconds)
        if (status == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS && AdvertisingPolicy.budgetOn()) {
            // No instance is free. Make room by releasing a title or reach set, or wait once and ask again (a leaked
            // or still-releasing instance can come free); then try one more time.
            if (relieveBudget()) status = attempt(advertiser, lowLatency, timeoutSeconds)
        }
        return status
    }

    /** One start request and its wait. Returns the status, or null on timeout. */
    @SuppressLint("MissingPermission")
    private fun attempt(advertiser: android.bluetooth.le.BluetoothLeAdvertiser, lowLatency: Boolean, timeoutSeconds: Long): Int? {
        // The value is fixed for this set's life: the in-place step must pass the same one (a refusal by another set
        // in between must not flip a running set).
        refreshNeutral()
        val callback = SetCallback(nonDiscoverable = discoverableOff())
        callback.lowLatency = lowLatency
        synchronized(this) {
            stopLocked()
            current = callback
        }
        // The stack frees an instance asynchronously after a stop: do not race it (never on the main thread).
        if (AdvertisingPolicy.stopSettleOn()) budget.awaitPendingStops()
        val ownedBefore = budget.owned()
        requestThrew = false
        EncoderBenchGuard.check("StableAdvertisingSet.start")
        if (AdvertisingPolicy.budgetOn() && budget.simulatedRefusal()) {
            // Experimental test_adv_budget_cap: behave like a controller whose instances are all in use.
            callback.status = AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
            callback.started.countDown()
        } else {
            logPayload(callback, lowLatency)
            val refused = request(advertiser, callback, lowLatency)
            if (refused != null) {
                requestThrew = true
                callback.status = refused
                callback.started.countDown()
            }
        }
        if (!callback.started.await(timeoutSeconds, TimeUnit.SECONDS)) {
            stopInternal()
            logChurn("start_timeout", callback)
            return null
        }
        val status = callback.status
        logChurn(if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) "start" else "start_failed_$status", callback)
        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
            budget.acquire(callback, AdvertisingSetRole.STABLE)
            hideIfNameLeftDuringStart(callback)
        } else {
            val verdict = budget.noteRefused(AdvertisingSetRole.STABLE, status, ownedBefore, Build.VERSION.SDK_INT)
            stopInternal()
            if (verdict.stuck && AdvertisingPolicy.stuckReportOn() && !stuckAnnounced) {
                stuckAnnounced = true
                budget.stuck()?.let { onStatus("$label advertising: ${it.message}") }
            }
        }
        return status
    }

    /** The platform call: guarded by the non-throwing entry, direct (exceptions propagate) when it is switched off. */
    @SuppressLint("MissingPermission")
    private fun request(advertiser: android.bluetooth.le.BluetoothLeAdvertiser, callback: SetCallback, lowLatency: Boolean): Int? {
        val params = parameters(lowLatency, callback.nonDiscoverable)
        if (!AdvertisingPolicy.nonThrowingStart()) {
            advertiser.startAdvertisingSet(params, data, scanResponse, null, null, callback)
            return null
        }
        return AdvertisingStart.request(
            advertiser, params, data, scanResponse, callback,
            duration = AdvertisingStart.UNLIMITED_DURATION, maxEvents = AdvertisingStart.UNLIMITED_EVENTS,
        )
    }

    /** DEBUG: the platform-formula sizes against the budget of this SDK (ADV-01), so a phone log shows the margin. */
    private fun logPayload(callback: SetCallback, lowLatency: Boolean) {
        if (!BuildConfig.DEBUG) return
        val primary = AdvertisingPayloadMeter.measure(data)
        val scan = AdvertisingPayloadMeter.measure(scanResponse)
        val discoverable = !callback.nonDiscoverable
        val fits = AdvertisingPayload.fits(primary, connectable, discoverable, Build.VERSION.SDK_INT) &&
            scan <= AdvertisingPayload.scanResponseBudget()
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_payload: label=$label | " +
                AdvertisingPayload.describe(primary, scan, connectable, discoverable, Build.VERSION.SDK_INT) +
                " | sdk=${Build.VERSION.SDK_INT} | connectable=$connectable | fits=$fits | " +
                // F11: the parameters of [parameters]: INTERVAL_LOW 160 or INTERVAL_MEDIUM 400 units of 0.625 ms.
                if (lowLatency) "mode=low_latency | intervalMs=100 | tx=high" else "mode=balanced | intervalMs=250 | tx=medium",
        )
    }

    /**
     * Frees an instance for this (session-critical) set: stops the lowest-priority title or reach set and waits for
     * the stack to confirm, else waits once when the app owns nothing (a still-releasing or leaked instance). True
     * when a retry is worth making.
     */
    private fun relieveBudget(): Boolean {
        if (AdvertisingPolicy.preemptOn()) {
            val victim = budget.preemptFor(AdvertisingSetRole.STABLE)
            if (victim != null) {
                onStatus("$label advertising: no free advertising slot; the ${victim.label} advertisement stopped to make room")
                budget.awaitPendingStops(PREEMPT_SETTLE_MS)
                return true
            }
        }
        if (budget.owned() == 0) {
            if (currentThreadIsMain()) return false
            return try {
                Thread.sleep(EMPTY_RETRY_MS)
                true
            } catch (_: InterruptedException) {
                false
            }
        }
        return false
    }

    private fun currentThreadIsMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /**
     * Asks the running set to move to balanced parameters in place. Non-blocking; safe on the main
     * thread. False when there is no running set (the caller restarts, as before).
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun switchToBalanced(): Boolean {
        if (AdvertisingSwitches.inPlaceBoost) return switchMode(lowLatency = false)
        val callback = current ?: return false
        val set = callback.set ?: return false
        if (callback.stopped) return false
        // This phone's stack never confirmed an in-place disable: the set keeps its address and its low latency.
        if (InPlaceStepHealth.skipBalanced(AdvertisingSwitches.inPlaceKeepSet)) {
            logChurn("balanced_skipped", callback)
            return true
        }
        if (!callback.steps.begin()) return false
        handler.postDelayed({ callback.onStepTimeout() }, SWITCH_TIMEOUT_MS)
        set.enableAdvertising(false, 0, 0)
        return true
    }

    /**
     * Moves the running set to low-latency (true) or balanced (false) parameters in place: disable, set parameters,
     * enable, so the set and its address stay (default on; control arm `test_adv_inplace_boost=false`).
     *
     * 2026-10-20 edge-case suite (m31-a15s-redmi-1): showing the Meet code replaced the A15s's call-listener set with a new
     * low-latency one (a new random address), and 10 s later the balanced step found that set's one-time step already
     * used and replaced it again (another address). A caller that had just seen the old address dialled a gone one, and
     * every handover counted toward "More than one phone is using this ID". Now the boost and its end are in-place steps,
     * any number of times per set. Already in [lowLatency]: nothing to do (true). A phone whose stack never confirmed an
     * in-place disable ([InPlaceStepHealth], the ASUS DeadObject case) keeps the set as it is (it stays low latency) and
     * returns true, as its balanced step already does. False when there is no running set or a step is still running:
     * the caller replaces the set as before. A step that fails or stalls replaces the set in the target mode.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun switchMode(lowLatency: Boolean): Boolean {
        val callback = current
        val set = callback?.set
        val decision = ModeSwitch.decide(
            running = callback != null && set != null && !callback.stopped,
            currentLowLatency = callback?.lowLatency == true,
            target = lowLatency,
            unreliableInPlace = InPlaceStepHealth.skipBalanced(AdvertisingSwitches.inPlaceKeepSet),
        )
        when (decision) {
            ModeSwitch.Decision.NO_SET -> return false
            ModeSwitch.Decision.ALREADY -> return true
            ModeSwitch.Decision.KEEP_SET -> {
                if (callback != null) logChurn(if (lowLatency) "boost_skipped" else "balanced_skipped", callback)
                return true
            }
            ModeSwitch.Decision.STEP -> Unit
        }
        if (callback == null || set == null || !callback.steps.begin(lowLatency)) return false
        handler.postDelayed({ callback.onStepTimeout() }, SWITCH_TIMEOUT_MS)
        set.enableAdvertising(false, 0, 0)
        return true
    }

    /** The owner stops the set: nothing restarts it until the owner starts it again. */
    @Synchronized
    fun stop() {
        ownerStopped = true
        generation++
        DiscoverableWhenNeutral.unregister(nameListener)
        stopLocked()
    }

    /** A stop the set decided on itself (failed start, timeout): the owner's intent is unchanged. */
    @Synchronized
    private fun stopInternal() = stopLocked()

    @SuppressLint("MissingPermission")
    private fun stopLocked() {
        val callback = current ?: return
        current = null
        callback.stopped = true
        handler.removeCallbacksAndMessages(null)
        val startedSet = callback.set
        // A started set keeps its instance until the stack confirms the stop; a start that never worked has none.
        val holdsInstance = startedSet != null && !callback.deadByStack
        budget.release(callback)
        if (holdsInstance) budget.noteStopRequested(callback)
        if (holdsInstance && AdvertisingSwitches.quiesceStop) {
            quiesceThenStop(callback, startedSet!!)
        } else {
            runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) }
        }
        logChurn("stop", callback)
    }

    /**
     * Experimental (test_adv_quiesce_stop, ADV-03): disable the set, and stop it when that is confirmed (or after
     * [QUIESCE_FALLBACK_MS]). The stack's address rotation does nothing for a set that is not enabled, so a removal
     * cannot meet the rotation's set-random-address command.
     */
    @SuppressLint("MissingPermission")
    private fun quiesceThenStop(callback: SetCallback, startedSet: AdvertisingSet) {
        val done = AtomicBoolean(false)
        val stopNow = Runnable {
            if (done.compareAndSet(false, true)) {
                quiesceHandler.removeCallbacksAndMessages(callback)
                runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(callback) }
            }
        }
        callback.quiesced = stopNow
        quiesceHandler.postDelayed(stopNow, callback, QUIESCE_FALLBACK_MS)
        if (runCatching { startedSet.enableAdvertising(false, 0, 0) }.isFailure) stopNow.run()
    }

    /**
     * Phase 0 (rotating discovery IDs, DEBUG only): every set start/stop with its set identity, so a
     * second phone's `adv_address_seen` lines show whether each new set got a new address.
     */
    /** JT2-P2: the owner held the set at its fast interval instead of asking for the balanced step ([CallReadyAdvertisingHold]). */
    @Synchronized
    fun noteBalancedHeld() {
        current?.let { logChurn("balanced_held", it) }
    }

    private fun logChurn(event: String, callback: SetCallback) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i(
            "BluetoothVideo",
            "BENCHMARK adv_set_churn: label=$label | event=$event | set=${Integer.toHexString(System.identityHashCode(callback))} | " +
                "replacement=${callback.replacement} | elapsedMs=${android.os.SystemClock.elapsedRealtime()} | " +
                "wallMs=${System.currentTimeMillis()} | nonDiscoverable=${callback.nonDiscoverable}",
        )
    }

    /**
     * The stack disabled the set but its confirmation never reached us ([InPlaceStepHealth]): enable the same set again,
     * so it keeps its address and its parameters (low latency). Unconfirmed, like the disable.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun reenableSameSet(callback: SetCallback) {
        if (current !== callback || callback.stopped) return
        onStatus("$label advertising: the in-place disable was never confirmed; same set enabled again (same address, low latency)")
        runCatching { callback.set?.enableAdvertising(true, 0, 0) }
        logChurn("reenable_same_set", callback)
    }

    /** The old step: a new set (new address) in the failed step's target mode. Used only when the in-place step fails. */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun restartBalanced(failed: SetCallback, reason: String) {
        if (current !== failed || failed.stopped) return
        val lowLatency = failed.steps.target
        onStatus("$label advertising: in-place ${modeLabel(lowLatency)} step failed ($reason); restarting the set")
        stopLocked()
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            lost("BLE advertising is unavailable")
            return
        }
        refreshNeutral()
        val replacement = SetCallback(replacement = true, nonDiscoverable = discoverableOff())
        replacement.lowLatency = lowLatency
        current = replacement
        // Bounded: no start callback within the timeout counts as a failed replacement.
        handler.postDelayed({ onReplacementResult(replacement, null) }, REPLACEMENT_TIMEOUT_MS)
        EncoderBenchGuard.check("StableAdvertisingSet.replace")
        // This runs on the main thread: it must not block for the stop to be confirmed, so it polls on the handler.
        whenStopsSettled(android.os.SystemClock.elapsedRealtime() + AdvertisingBudget.SETTLE_MAX_MS) {
            if (current === replacement && !replacement.stopped) {
                val refused = if (AdvertisingPolicy.nonThrowingStart()) {
                    AdvertisingStart.request(
                        advertiser, parameters(lowLatency, replacement.nonDiscoverable), data, scanResponse, replacement,
                        duration = AdvertisingStart.UNLIMITED_DURATION, maxEvents = AdvertisingStart.UNLIMITED_EVENTS,
                    )
                } else {
                    runCatching {
                        advertiser.startAdvertisingSet(parameters(lowLatency, replacement.nonDiscoverable), data, scanResponse, null, null, replacement)
                    }.exceptionOrNull()?.let { START_THREW }
                }
                if (refused != null) onReplacementResult(replacement, refused)
            }
        }
    }

    /** Runs [action] once no requested stop is outstanding, or at [deadlineMs]; polls on the main handler. */
    private fun whenStopsSettled(deadlineMs: Long, action: () -> Unit) {
        val waiting = AdvertisingPolicy.stopSettleOn() && budget.pendingStopCount() > 0 &&
            android.os.SystemClock.elapsedRealtime() < deadlineMs
        if (!waiting) {
            action()
            return
        }
        handler.postDelayed({ whenStopsSettled(deadlineMs, action) }, AdvertisingBudget.SETTLE_POLL_MS)
    }

    /** Settles a fallback replacement once: success keeps it; any failure stops it and reports [onLost]. */
    @Synchronized
    private fun onReplacementResult(replacement: SetCallback, status: Int?) {
        if (current !== replacement || replacement.settled) return
        replacement.settled = true
        val failure = replacementFailure(status)
        if (failure == null) {
            budget.acquire(replacement, AdvertisingSetRole.STABLE)
            onStatus("$label advertising restarted in ${modeLabel(replacement.lowLatency)} mode (new set)")
            hideIfNameLeftDuringStart(replacement)
            return
        }
        if (status == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) {
            budget.noteRefused(AdvertisingSetRole.STABLE, status, budget.owned(), Build.VERSION.SDK_INT)
        }
        stopLocked()
        lost(failure)
    }

    private fun lost(reason: String) {
        onStatus("$label is not advertising: $reason")
        handler.post { onLost(reason) }
    }

    // ----- watchdog (A-03) -----

    /** Acts on a watchdog decision about [failed]. Main thread (callbacks) or the grace timer. */
    private fun applyDecision(failed: SetCallback, decision: AdvertisingWatchdog.Decision, signal: String) {
        if (failed.stopped) return
        val at = generation
        when (decision) {
            AdvertisingWatchdog.Decision.None -> Unit
            is AdvertisingWatchdog.Decision.WaitGrace -> handler.postDelayed({
                if (!failed.stopped && current === failed) applyDecision(failed, watchdog.onGraceElapsed(), signal)
            }, decision.ms)
            is AdvertisingWatchdog.Decision.Restart -> {
                logWatchdog("restart_planned", "signal=$signal | reason=${decision.reason} | delayMs=${decision.delayMs} | enabled=${AdvertisingPolicy.watchdogOn()}")
                if (AdvertisingPolicy.watchdogOn() && !ownerStopped) {
                    onStatus("$label advertising stopped unexpectedly ($signal); restarting")
                    thread(name = "AdvertisingWatchdog", isDaemon = true) { recover(at, decision) }
                }
            }
            is AdvertisingWatchdog.Decision.GiveUp -> {
                logWatchdog("give_up", "signal=$signal | reason=${decision.reason}")
                if (AdvertisingPolicy.watchdogOn() && !ownerStopped) {
                    lost("advertising stopped unexpectedly and could not be restarted (${decision.reason})")
                }
            }
        }
    }

    /** Worker thread: waits out the back-off, restarts as a new balanced set, repeats per the watchdog's decisions. */
    private fun recover(generationAtLoss: Int, first: AdvertisingWatchdog.Decision.Restart) {
        var decision: AdvertisingWatchdog.Decision = first
        while (true) {
            val restart = when (val next = decision) {
                is AdvertisingWatchdog.Decision.Restart -> next
                is AdvertisingWatchdog.Decision.GiveUp -> {
                    logWatchdog("give_up", "reason=${next.reason}")
                    if (generation == generationAtLoss && !ownerStopped) lost("advertising could not be restarted (${next.reason})")
                    return
                }
                else -> return
            }
            try {
                Thread.sleep(restart.delayMs)
            } catch (_: InterruptedException) {
                return
            }
            val links = peripheralLinks()
            val status = synchronized(startLock) {
                if (generation != generationAtLoss || ownerStopped) return
                runCatching { startOnceLocked(lowLatency = false, RESTART_TIMEOUT_SECONDS) }.getOrNull()
            }
            logWatchdog("restart_result", "status=${status ?: "timeout"} | links=$links | reason=${restart.reason}")
            if (links > 0) budget.noteConnectedAdvertising(status == AdvertisingSetCallback.ADVERTISE_SUCCESS)
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                if (ownerStopped || generation != generationAtLoss) {
                    stopInternal()
                } else {
                    onStatus("$label advertising restarted by the watchdog (new set)")
                }
                return
            }
            decision = watchdog.onRestartFailed()
        }
    }

    private fun logWatchdog(event: String, detail: String) {
        android.util.Log.i(
            "BluetoothVideo",
            (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "adv_watchdog: label=$label | event=$event | $detail",
        )
    }

    private fun parameters(lowLatency: Boolean, hidden: Boolean): AdvertisingSetParameters =
        AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(connectable)
            .setScannable(true)
            .setInterval(
                if (lowLatency) AdvertisingSetParameters.INTERVAL_LOW else AdvertisingSetParameters.INTERVAL_MEDIUM,
            )
            .setTxPowerLevel(
                if (lowLatency) AdvertisingSetParameters.TX_POWER_HIGH else AdvertisingSetParameters.TX_POWER_MEDIUM,
            )
            .also { builder ->
                // Only a set that rotating IDs made non-discoverable sets the flag; off, the default stands.
                if (hidden && Build.VERSION.SDK_INT >= NonDiscoverableAdvertising.MIN_SDK) {
                    builder.setDiscoverable(false)
                }
            }
            .build()

    private inner class SetCallback(val replacement: Boolean = false, val nonDiscoverable: Boolean = false) : AdvertisingSetCallback() {
        val started = CountDownLatch(1)
        @Volatile var status: Int = -1
        @Volatile var set: AdvertisingSet? = null
        @Volatile var stopped = false
        /** The mode the set advertises in now: its start mode, then each completed in-place step's target. */
        @Volatile var lowLatency = false
        /** Replacement only: its outcome was handled (success, failure or timeout). */
        var settled = false
        val steps = BalancedSwitchSteps()

        /** The stack reported this set stopped (or gone): its instance is free, there is nothing left to stop. */
        @Volatile var deadByStack = false

        /** The stack confirmed the set started successfully (the watchdog only watches such a set). */
        @Volatile var startedOk = false

        /** Experimental quiesce-then-stop: runs the real stop once the disable is confirmed. */
        @Volatile var quiesced: Runnable? = null

        @SuppressLint("MissingPermission")
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            set = advertisingSet
            this.status = status
            startedOk = status == ADVERTISE_SUCCESS
            if (stopped && status == ADVERTISE_SUCCESS) {
                budget.noteStopRequested(this)
                runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertisingSet(this) }
            }
            started.countDown()
            if (replacement) onReplacementResult(this, status)
        }

        @SuppressLint("MissingPermission")
        override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
            if (!enable) quiesced?.run()
            if (stopped) return
            val success = status == ADVERTISE_SUCCESS && advertisingSet != null
            val stepBefore = steps.step
            val action = steps.onEnabled(enable, success)
            act(action, advertisingSet, "enable($enable) status $status")
            // Only our own step's enable is balanced_in_place; the stack re-arming a legacy connectable set after an
            // inbound connection (or an address rotation) is stack_enable (2026-10-20: the S24 logged ~42 per minute
            // while a paired laptop connected about once a second, and they were read as our own churn).
            if (success && enable) logChurn(BalancedSwitchSteps.churnEvent(action, steps.target), this)
            watch(enable, success, status, stepBefore)
        }

        /**
         * Watchdog input (A-03): a disable nobody asked for starts a grace period (the stack's own address rotation
         * disables and re-enables a connectable set within milliseconds); an enable that failed, or the stack's
         * report that the set is gone, asks for a restart. Our own in-place balanced step is not watched.
         */
        private fun watch(enable: Boolean, success: Boolean, status: Int, stepBefore: BalancedSwitchSteps.Step) {
            if (!startedOk) return
            if (enable && success) {
                watchdog.onEnabled()?.let { gap -> logWatchdog("enable_gap", "ms=$gap | note=address_rotation_or_reenable") }
                return
            }
            val ownStep = stepBefore != BalancedSwitchSteps.Step.IDLE && stepBefore != BalancedSwitchSteps.Step.DONE
            if (ownStep) return
            if (!enable && success) {
                applyDecision(this, watchdog.onUnexpectedDisable(), "disabled")
            } else if (enable) {
                applyDecision(this, watchdog.onUnexpectedStop(), "enable_failed_$status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onAdvertisingParametersUpdated(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (stopped) return
            val success = status == ADVERTISE_SUCCESS && advertisingSet != null
            act(steps.onParametersUpdated(success), advertisingSet, "parameters status $status")
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            deadByStack = true
            budget.noteStopped(this)
            if (stopped || !startedOk) return
            // The stack dropped a set MeshLive did not stop: the advertisement is gone.
            applyDecision(this, watchdog.onUnexpectedStop(), "set_stopped")
        }

        fun onStepTimeout() {
            if (stopped) return
            val before = steps.step
            val action = steps.onTimeout()
            if (action == BalancedSwitchSteps.Action.RESTART &&
                InPlaceStepHealth.onTimeout(before, AdvertisingSwitches.inPlaceKeepSet) == InPlaceStepHealth.Recovery.REENABLE_SAME_SET
            ) {
                reenableSameSet(this)
                return
            }
            act(action, set, "timeout")
        }

        @SuppressLint("MissingPermission")
        private fun act(action: BalancedSwitchSteps.Action, advertisingSet: AdvertisingSet?, detail: String) {
            when (action) {
                BalancedSwitchSteps.Action.SET_PARAMETERS ->
                    advertisingSet?.setAdvertisingParameters(parameters(steps.target, nonDiscoverable))
                BalancedSwitchSteps.Action.ENABLE -> advertisingSet?.enableAdvertising(true, 0, 0)
                BalancedSwitchSteps.Action.DONE -> {
                    lowLatency = steps.target
                    onStatus("$label advertising is ${modeLabel(steps.target)} (same advertising set and address)")
                }
                BalancedSwitchSteps.Action.RESTART -> restartBalanced(this, detail)
                BalancedSwitchSteps.Action.NONE -> Unit
            }
        }
    }

    companion object {
        fun modeLabel(lowLatency: Boolean): String = if (lowLatency) "low latency" else "balanced"

        /** Each step is one HCI command; a missing callback must not leave the phone unadvertised. */
        private const val SWITCH_TIMEOUT_MS = 2_000L

        /** A replacement set must report started within this (the owners' start timeout). */
        const val REPLACEMENT_TIMEOUT_MS = 8_000L

        /** Pseudo status: startAdvertisingSet itself threw. */
        const val START_THREW = AdvertisingStart.STATUS_THREW

        /** After making room for the stable set, wait at most this for the stack to confirm the stop. */
        private const val PREEMPT_SETTLE_MS = 1_000L

        /** Nothing owned and TOO_MANY: wait this once before asking again (an instance may still be releasing). */
        private const val EMPTY_RETRY_MS = 1_500L

        /** A watchdog restart must report started within this. */
        private const val RESTART_TIMEOUT_SECONDS = 8L

        /** Quiesce-then-stop: stop anyway if the disable is not confirmed by then. */
        private const val QUIESCE_FALLBACK_MS = 500L

        /** Why a fallback replacement left nothing advertising, or null when it started (null status: timed out). */
        fun replacementFailure(status: Int?): String? = when (status) {
            AdvertisingSetCallback.ADVERTISE_SUCCESS -> null
            null -> "replacement set did not start within $REPLACEMENT_TIMEOUT_MS ms"
            START_THREW -> "replacement set could not be requested"
            else -> "replacement set failed (code $status)"
        }
    }
}

/**
 * 2026-10-20 (win-userflow asus-winvideo, -r2): on the ASUS (Android 11) every in-place balanced step timed out. The
 * stack did disable the set (`onAdvertisingSetEnabled() - advertiserId=0, enable=false, status=0`) but threw
 * DeadObjectException delivering that callback (`An exception was thrown by callback 'enableSetCb'`), although the app
 * and the set's callback were alive; the app never heard it, waited the 2 s step timeout and replaced the set, so the
 * address changed 12 s after every low-latency start. A PC that found the advert 8 s in had its connect in flight when
 * the address went away (services query Unreachable). Now (default on, control arm `test_adv_inplace_keep_set=false`):
 * a step that times out still waiting for the disable enables the same set again (same address, low-latency
 * parameters kept) instead of replacing it, and this process skips later balanced steps (they would fail the same way),
 * trading the balanced step's power saving for a stable address. A timeout in the later steps restarts as before.
 */
/** The decision of [StableAdvertisingSet.switchMode], kept free of Android calls for JVM tests. */
internal object ModeSwitch {
    enum class Decision { NO_SET, ALREADY, KEEP_SET, STEP }

    fun decide(running: Boolean, currentLowLatency: Boolean, target: Boolean, unreliableInPlace: Boolean): Decision = when {
        !running -> Decision.NO_SET
        currentLowLatency == target -> Decision.ALREADY
        // The ASUS case: an in-place disable is never confirmed, so the set stays as it is (low latency) with its address.
        unreliableInPlace -> Decision.KEEP_SET
        else -> Decision.STEP
    }
}

internal object InPlaceStepHealth {
    enum class Recovery { RESTART, REENABLE_SAME_SET }

    @Volatile var unreliable = false
        private set

    fun onTimeout(stepBefore: BalancedSwitchSteps.Step, enabled: Boolean): Recovery =
        if (enabled && stepBefore == BalancedSwitchSteps.Step.DISABLING) {
            unreliable = true
            Recovery.REENABLE_SAME_SET
        } else {
            Recovery.RESTART
        }

    fun skipBalanced(enabled: Boolean): Boolean = enabled && unreliable

    /** Tests only. */
    internal fun reset() {
        unreliable = false
    }
}

/**
 * Order of the in-place balanced step: disable -> set parameters -> enable, each confirmed by its
 * callback. Any failure or a step that stalls past the timeout asks for the old restart.
 */
internal class BalancedSwitchSteps {
    enum class Step { IDLE, DISABLING, UPDATING, ENABLING, DONE, FAILED }
    enum class Action { NONE, SET_PARAMETERS, ENABLE, DONE, RESTART }

    @Volatile var step: Step = Step.IDLE
        private set

    /** The mode the running (or last) step moves to: true low latency, false balanced. */
    @Volatile var target: Boolean = false
        private set

    /** The balanced step, once per set (the control arm of [StableAdvertisingSet.switchMode]). */
    @Synchronized
    fun begin(): Boolean {
        if (step != Step.IDLE) return false
        target = false
        step = Step.DISABLING
        return true
    }

    /**
     * A step to [lowLatency], again after an earlier one completed (boost, its end, the balanced step); false while a
     * step runs or after one failed (the set was replaced).
     */
    @Synchronized
    fun begin(lowLatency: Boolean): Boolean {
        if (step != Step.IDLE && step != Step.DONE) return false
        target = lowLatency
        step = Step.DISABLING
        return true
    }

    @Synchronized
    fun onEnabled(enable: Boolean, success: Boolean): Action = when (step) {
        Step.DISABLING -> if (success && !enable) {
            step = Step.UPDATING
            Action.SET_PARAMETERS
        } else {
            fail()
        }
        Step.ENABLING -> if (success && enable) {
            step = Step.DONE
            Action.DONE
        } else {
            fail()
        }
        else -> Action.NONE
    }

    @Synchronized
    fun onParametersUpdated(success: Boolean): Action = when {
        step != Step.UPDATING -> Action.NONE
        success -> {
            step = Step.ENABLING
            Action.ENABLE
        }
        else -> fail()
    }

    @Synchronized
    fun onTimeout(): Action =
        if (step == Step.DISABLING || step == Step.UPDATING || step == Step.ENABLING) fail() else Action.NONE

    private fun fail(): Action {
        step = Step.FAILED
        return Action.RESTART
    }

    companion object {
        /** The adv_set_churn event for a successful enable: ours completes the step, any other is the stack's. */
        fun churnEvent(action: Action): String = churnEvent(action, target = false)

        fun churnEvent(action: Action, target: Boolean): String = when {
            action != Action.DONE -> "stack_enable"
            target -> "boost_in_place"
            else -> "balanced_in_place"
        }
    }
}
