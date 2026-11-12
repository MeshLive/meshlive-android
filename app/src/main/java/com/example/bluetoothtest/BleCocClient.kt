package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

/**
 * Root cause (stack bug, Android 12 and 13): when we are LE central, the stack starts EATT on every
 * LE link. A peer that refuses it (the S24 refuses on an unencrypted link) fails only the first of
 * the five requested channels; the other four stay waiting for a connect response, registered to
 * EATT, which has no connect-confirm callback. Our later LE CoC response is matched to the first
 * channel with the same signal id, the stale channel can win, and the Bluetooth process jumps to a
 * null callback (M31 -> S24, xpair2-s24m31-431d40dc/0003). DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS
 * holds the sources.
 *
 * On those stacks the CoC client reads the PSM over GATT as before, closes that GATT client and waits
 * (at most [BleCocFreshAcl.ACL_DOWN_WAIT_MS]) for the ACL to drop, which also drops the stranded
 * channels. The CoC connect then creates a fresh ACL: its channel is queued before EATT starts, so the
 * response finds it first. A new GATT client is attached afterwards for link priority and PHY.
 */
internal object BleCocClient {
    /**
     * Connects an LE CoC client channel to [device]. [onControl] receives each GATT control connection
     * the caller must own and close (null while none is open).
     */
    @SuppressLint("MissingPermission")
    fun connect(
        context: Context,
        device: BluetoothDevice,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference,
        owner: String,
        connectionPriority: BleConnectionPriority = BleConnectionPriority.HIGH,
        /** Dual listening: receives the CoC socket the moment it exists, so a deadline can close one that is still connecting. */
        onSocket: (BluetoothSocket) -> Unit = {},
        /** Relay child connect only: Experimental RelayEstablishBudget for the GATT control connect. */
        establishBudget: Boolean = false,
        /** The caller's setup deadline (a call's or message's dial, a relay helper's next hop), null when unbounded. */
        deadline: SetupDeadline? = null,
        onControl: (BleL2capControlConnection?) -> Unit,
    ): BluetoothSocket {
        val freshAcl = BleCocFreshAcl.enabled()
        val control = BleL2capPsmReader.open(
            context,
            device,
            onLinkStatus,
            phyPreference,
            connectionPriority,
            establishBudget = establishBudget,
            deadline = deadline,
            quietLink = PsmReadLinkQuiet.enabled(freshAcl),
        )
        onControl(control)
        val psm = control.psm
        if (!freshAcl) {
            // The control link is alive on this ACL and its PHY request is issued: a failure here is past establishment.
            val shared = markLinkReached({ true }) { connectChannel(device, psm, owner, onSocket, deadline = deadline) }
            // The control client stayed on this ACL and already exchanged the MTU: nothing to prime.
            if (CocSwitches.mtuPrime) Log.i("BluetoothVideo", CocMtuPrime.skippedLine(owner, "control-link-alive"))
            return markLinkReached({ true }) { verifyEcho(shared, owner) }
        }
        val watch = AclDownWatch.forDevice(context, device)
        BleCocFreshAcl.releaseControlAndAwaitAclDown(
            watch = watch,
            closeControl = {
                control.close()
                onControl(null)
            },
            nowNs = System::nanoTime,
            log = { line -> Log.i("BluetoothVideo", line) },
            owner = owner,
            mayBlock = Looper.myLooper() != Looper.getMainLooper(),
        )
        val socket = if (CocSwitches.psmReuseRetry) {
            connectReusingPsm(context, device, psm, owner, onSocket, deadline)
        } else {
            connectChannel(device, psm, owner, onSocket, deadline = deadline)
        }
        if (CocSwitches.primeAsync && Looper.myLooper() != Looper.getMainLooper()) {
            primeAndAttachInBackground(context, device, socket, psm, onLinkStatus, phyPreference, connectionPriority, owner, deadline, onControl)
            return markLinkReached({ true }) { verifyEcho(socket, owner) }
        }
        // Default on here since Q49 (test_coc_mtu_prime=false is the DEBUG control arm): this fresh ACL has had no MTU exchange, so no data length update.
        if (CocMtuPrime.enabled()) CocMtuPrime.runOnAcl(context, device, owner)
        val attached = runCatching { BleL2capPsmReader.attach(context, device, psm, onLinkStatus, phyPreference, connectionPriority, deadline) }
            .onFailure { error ->
                onLinkStatus("BLE CoC connected; GATT control reattach failed: ${error.message}")
            }
            .getOrNull()
        onControl(attached)
        return markLinkReached({ true }) { verifyEcho(socket, owner) }
    }

    /**
     * Experimental test_coc_psm_reuse_retry (join-latency P4b): the fresh-ACL CoC connect, retried once with the PSM
     * already read when it fails fast. The retry keeps the break-before-make rule of the stranded-EATT class: it waits
     * (bounded, [BleCocFreshAcl.ACL_DOWN_WAIT_MS]) for the failed attempt's ACL to drop, on a watch armed before the
     * first connect so a drop during it is not missed. A second failure goes to the caller as before (its full redo).
     */
    private fun connectReusingPsm(
        context: Context,
        device: BluetoothDevice,
        psm: Int,
        owner: String,
        onSocket: (BluetoothSocket) -> Unit,
        deadline: SetupDeadline?,
    ): BluetoothSocket {
        val watch = AclDownWatch.forDevice(context, device)
        val startedMs = SystemClock.elapsedRealtime()
        try {
            return connectChannel(device, psm, owner, onSocket, deadline = deadline).also { watch.release() }
        } catch (error: Throwable) {
            val nowMs = SystemClock.elapsedRealtime()
            val mayBlock = Looper.myLooper() != Looper.getMainLooper()
            if (!mayBlock || !CocPsmReuseRetry.shouldRetry(error, nowMs - startedMs, deadline?.remainingMs(nowMs))) {
                watch.release()
                throw error
            }
            val log: (String) -> Unit = { Log.i("BluetoothVideo", it) }
            val aclDown = GattLinkLifetime.awaitAclDown(watch, BleCocFreshAcl.ACL_DOWN_WAIT_MS, log = log, reason = "coc-psm-reuse")
            log(CocPsmReuseRetry.line(owner, nowMs - startedMs, error, aclDown, SystemClock.elapsedRealtime() - nowMs))
            return connectChannel(device, psm, owner, onSocket, deadline = deadline)
        }
    }

    /**
     * Experimental test_coc_prime_async (join-latency P1): the same MTU prime and GATT control reattach as the synchronous
     * path, in the same order, on a daemon thread, while the caller authenticates. [CocPrimeGate] holds the first bulk
     * write until they are done (bounded). A reattach that ends after the caller closed the socket is closed here, not
     * handed over (the caller's teardown has already run).
     */
    @SuppressLint("MissingPermission")
    private fun primeAndAttachInBackground(
        context: Context,
        device: BluetoothDevice,
        socket: BluetoothSocket,
        psm: Int,
        onLinkStatus: (String) -> Unit,
        phyPreference: BlePhyPreference,
        connectionPriority: BleConnectionPriority,
        owner: String,
        deadline: SetupDeadline?,
        onControl: (BleL2capControlConnection?) -> Unit,
    ) {
        val log: (String) -> Unit = { Log.i("BluetoothVideo", it) }
        val gate = CocPrimeGate(owner, nowMs = SystemClock::elapsedRealtime, log = log)
        CocPrimeGates.register(socket, gate)
        thread(name = "CocPrimeAsync", isDaemon = true) {
            val startedMs = SystemClock.elapsedRealtime()
            var primeOutcome = "not_applicable"
            var primeEndMs = startedMs
            var attached: BleL2capControlConnection? = null
            try {
                if (CocMtuPrime.enabled()) {
                    primeOutcome = CocMtuPrime.runOnAcl(context, device, owner)?.outcome?.label ?: "skipped"
                }
                primeEndMs = SystemClock.elapsedRealtime()
                attached = runCatching {
                    BleL2capPsmReader.attach(context, device, psm, onLinkStatus, phyPreference, connectionPriority, deadline)
                }
                    .onFailure { error -> onLinkStatus("BLE CoC connected; GATT control reattach failed: ${error.message}") }
                    .getOrNull()
                if (attached != null && !socket.isConnected) {
                    log("BENCHMARK coc_prime_async: owner=$owner | phase=late_attach_closed | reason=socket_closed")
                    runCatching { attached.close() }
                } else {
                    onControl(attached)
                }
            } catch (error: Throwable) {
                log("BENCHMARK coc_prime_async: owner=$owner | phase=error | error=${error.javaClass.simpleName}")
            } finally {
                val nowMs = SystemClock.elapsedRealtime()
                gate.release(primeOutcome, primeEndMs - startedMs, nowMs - primeEndMs, attached != null)
                CocPrimeGates.remove(socket)
            }
        }
    }

    /**
     * Experimental (test_coc_echo_verify): the new socket must round-trip a 1 KB echo before it is
     * returned. A failure closes the socket and fails the connect, so the callers' retry and GATT
     * fallback apply.
     */
    private fun verifyEcho(socket: BluetoothSocket, owner: String): BluetoothSocket {
        if (!CocEchoVerify.enabled()) return socket
        val result = CocEchoVerify.initiate(
            socket.inputStream, socket.outputStream, socket, owner, { Log.i("BluetoothVideo", it) },
        )
        if (!result.ok) {
            runCatching { socket.close() }
            throw CocEchoVerify.failure(result)
        }
        return socket
    }

    @SuppressLint("MissingPermission")
    private fun connectChannel(
        device: BluetoothDevice,
        psm: Int,
        owner: String,
        onSocket: (BluetoothSocket) -> Unit = {},
        precededByGatt: Boolean = true,
        deadline: SetupDeadline? = null,
    ): BluetoothSocket {
        EncoderBenchGuard.check("BleCocClient.connect")
        // Both callers read the PSM over a GATT connect just before; Android 10 needs that to know the address type.
        CocAddressPrecondition.check(CocPolicy.current(), precededByGatt, owner) { Log.i("BluetoothVideo", it) }
        val socket = device.createInsecureL2capChannel(psm).also { CocRegistrations.note("connect", owner) }
        onSocket(socket)
        try {
            // Bounded on every SDK (coc-connect-bounded): a watchdog closes a connect the stack never answers.
            // Experimental test_unified_connect_policy: the bound is clipped to the caller's setup deadline.
            val boundMs = CocConnectBound.currentBoundMs().let { bound ->
                if (deadline != null && ConnectRetryConfig.current().unified) {
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    UnifiedConnectRule.clipMs(bound, deadline.endMs, nowMs)
                } else {
                    bound
                }
            }
            CocConnectBound.run(socket, boundMs, owner, { Log.i("BluetoothVideo", it) }) { it.connect() }
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
        return socket
    }
}

internal object BleCocFreshAcl {
    /** GATT drops an unused LE link after 1 s (android12 gatt_api.h GATT_LINK_IDLE_TIMEOUT_WHEN_NO_APP); the one ACL-wait bound. */
    const val ACL_DOWN_WAIT_MS = GattLinkLifetime.ACL_DOWN_WAIT_MS

    /** The stack-family rule lives in DeviceFamilyPolicy.LE_COC_EATT_STRANDED_CHANNELS; DEBUG may override it. */
    fun enabledFor(fingerprint: BluetoothStackFingerprint, override: Boolean?): Boolean =
        override ?: DeviceFamilyPolicy.leCocEattStrandedChannels(fingerprint)

    fun enabled(): Boolean = enabledFor(BluetoothStackFingerprint.current(), DebugTunables.cocFreshAcl)

    /**
     * JVM-testable core: arm, close, wait (bounded), log. Returns whether the ACL was seen down. A
     * caller on the main thread ([mayBlock] false) never waits: it proceeds at once with aclDown=false.
     */
    internal fun releaseControlAndAwaitAclDown(
        watch: AclDownWatch,
        closeControl: () -> Unit,
        nowNs: () -> Long,
        log: (String) -> Unit,
        owner: String,
        mayBlock: Boolean = true,
    ): Boolean {
        val startedNs = nowNs()
        try {
            runCatching(closeControl)
            val aclDown = mayBlock && runCatching { watch.await(ACL_DOWN_WAIT_MS) }.getOrDefault(false)
            log(
                "BENCHMARK coc_fresh_acl: owner=$owner | waitedMs=${(nowNs() - startedNs) / 1_000_000L} | " +
                    "aclDown=$aclDown" + if (mayBlock) "" else " | skipped=main-thread",
            )
            return aclDown
        } finally {
            runCatching { watch.release() }
        }
    }
}

/**
 * Experimental `test_coc_psm_reuse_retry` (default off; join-latency P4b). In rem-u, 3 of 14 M31-client joins lost their
 * first fresh-ACL CoC connect 0.43-0.53 s in ("read failed, socket might closed or timeout"), and the caller then
 * repeated GATT connect, PSM read, ACL-down wait and CoC connect (+2.9 s). The PSM does not change between the two
 * connects, so one retry reuses it; only a fast failure qualifies (not the app-side connect bound), and only when the
 * caller's deadline leaves room for the ACL wait and a connect.
 */
internal object CocPsmReuseRetry {
    /** A failure later than this is not the fast refusal seen in the evidence. */
    const val FAST_FAIL_MS = 3_000L

    /** Room a retry needs inside the caller's deadline: the ACL-down wait bound plus a connect. */
    const val ROOM_MS = GattLinkLifetime.ACL_DOWN_WAIT_MS + 1_000L

    fun shouldRetry(error: Throwable, attemptElapsedMs: Long, deadlineRemainingMs: Long?): Boolean =
        error is java.io.IOException && error !is CocConnectTimeoutException &&
            attemptElapsedMs in 0..FAST_FAIL_MS &&
            (deadlineRemainingMs == null || deadlineRemainingMs >= ROOM_MS)

    fun line(owner: String, failedAfterMs: Long, error: Throwable, aclDown: Boolean, waitedMs: Long): String =
        "BENCHMARK coc_psm_reuse_retry: owner=$owner | failedAfterMs=$failedAfterMs | error=${error.javaClass.simpleName} | " +
            "aclDown=$aclDown | aclWaitMs=$waitedMs"
}
