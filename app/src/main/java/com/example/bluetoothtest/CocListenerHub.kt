package com.example.bluetoothtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root cause (Android 10 AOSP `btif_sock_l2cap.cc:250-257` `btsock_l2cap_free_l`): closing an LE
 * CoC server socket frees only its PSM number (`BTA_JvFreeChannel`); it never calls
 * `BTA_JvL2capStopServer` (added by AOSP d3c996662453, in Android 11). Every closed LE listener
 * therefore keeps one BTA JV security id (a pool of 18 shared with RFCOMM and classic L2CAP
 * servers), one GAP CCB, one LE L2CAP RCB and one BTM service record until the Bluetooth process
 * restarts. Accepted sockets and client sockets follow the normal cleanup path. On the A15s every
 * Bluetooth process failed after about 12 app listens plus 3 connects (exactly 18 `Limiting MPS`
 * GAP opens before the first `Error starting l2cap_listen - status: 0x01`), and from then on LE
 * CoC listens, LE CoC connects and the system's own MAP server all failed.
 *
 * Closing gracefully does not avoid it (the same free path runs for close() and process death).
 * On stacks with the leak (DeviceFamilyPolicy.LE_COC_SERVER_LEAK, Android 10) the app keeps one
 * long-lived listener per role instead of one per call, availability restart, stream or exchange.
 * A session takes a [CocListenLease]: the PSM to advertise and its own accept queue. Releasing the
 * lease keeps the stack listener; a connection accepted while no session holds the role, or while
 * a different session held it, is closed at once, as the closed listener used to refuse it. The
 * idle listener stays remotely connectable on those stacks (closing it would leak); fixed stacks
 * keep the per-session listener, so no idle PSM stays open there. Each app process then leaks one
 * registration per role it used, plus one per concurrent same-role session (dedicated fallback)
 * or per listener failure.
 *
 * Security and the wire protocol are unchanged: the PSM is still handed out by the role's GATT
 * control server, and every connection still runs the authenticated, ordered encrypted handshake.
 * A peer that connects just as one session ends and the next starts can still reach the next one
 * (same role, same PSM); that session authenticates it like any caller.
 */
interface CocListenLease<S : Closeable> : Closeable {
    val psm: Int

    /** True when this lease reuses the role's long-lived listener (false: a dedicated socket). */
    val shared: Boolean

    /** Blocks until a connection arrives; throws [IOException] when the lease or listener closes. */
    fun accept(): S

    /** As [accept], but throws [IOException] after [timeoutMs] without a connection. */
    fun accept(timeoutMs: Long): S
}

/** The platform listener behind a shared role (a BluetoothServerSocket on phones). */
internal interface CocServerHandle<S : Closeable> : Closeable {
    val psm: Int

    /** Blocks until a connection arrives; throws [IOException] when the listener closes or fails. */
    fun accept(): S
}

/**
 * One role's long-lived listener. JVM-testable: [open] creates the platform listener and
 * [startThread] runs the accept loop. At most one lease at a time; a second concurrent request
 * gets null and the caller opens a dedicated listener (the previous behaviour).
 *
 * Delivery is bound to a lease generation: every acquire, release, reset and failure advances the
 * generation, the accept loop records it the moment accept() returns, and a connection is handed
 * only to the lease that was current then. A lease never returns a connection after it is closed
 * or failed; such a connection is closed instead.
 */
internal class SharedCocListener<S : Closeable>(
    private val role: String,
    private val open: () -> CocServerHandle<S>,
    private val log: (String) -> Unit,
    private val startThread: (String, () -> Unit) -> Unit = { name, body ->
        thread(name = name, isDaemon = true) { body() }
    },
) {
    private var server: CocServerHandle<S>? = null
    private var active: Lease? = null
    private var generation = 0L
    private var leasesTotal = 0
    private var listensTotal = 0
    private var refusedTotal = 0

    val listenCount: Int @Synchronized get() = listensTotal
    val refusedCount: Int @Synchronized get() = refusedTotal
    val listening: Boolean @Synchronized get() = server != null

    /** The current delivery generation (what the accept loop records when accept() returns). */
    val currentGeneration: Long @Synchronized get() = generation

    /** A lease on the shared listener, or null while another session holds this role. */
    @Synchronized
    fun acquire(owner: String): CocListenLease<S>? {
        if (active != null) {
            log("LE CoC listener busy: role=$role | owner=$owner | dedicated listener used")
            return null
        }
        val handle = server ?: open().also { opened ->
            server = opened
            listensTotal++
            val loopThread = "CocListener-$role"
            startThread(loopThread) { acceptLoop(opened) }
        }
        leasesTotal++
        generation++
        val lease = Lease(owner, handle, generation)
        active = lease
        log(
            "LE CoC listener lease: role=$role | owner=$owner | psm=${handle.psm} | " +
                "listens=$listensTotal | leases=$leasesTotal",
        )
        return lease
    }

    /** Bluetooth turned off or on: the stack listener is gone, so the next lease listens again. */
    @Synchronized
    fun reset(reason: String) {
        val old = server ?: return
        server = null
        runCatching { old.close() }
        endLease(old, IOException("LE CoC listener reset: $reason"))
        log("LE CoC listener reset: role=$role | reason=$reason")
    }

    private fun acceptLoop(handle: CocServerHandle<S>) {
        while (true) {
            val socket = try {
                handle.accept()
            } catch (error: IOException) {
                onListenerFailed(handle, error)
                return
            }
            deliver(handle, socket, currentGeneration)
        }
    }

    /**
     * Hands [socket] to the lease that was current when accept() returned, or closes it. The close
     * (a socket shutdown) runs outside the hub lock, so a session releasing its lease on the main
     * thread never waits behind it.
     */
    internal fun deliver(handle: CocServerHandle<S>, socket: S, acceptedGeneration: Long) {
        val refused = synchronized(this) {
            val lease = active
            if (lease != null && lease.handle === handle && lease.generation == acceptedGeneration &&
                lease.offer(socket)
            ) {
                return
            }
            ++refusedTotal
        }
        runCatching { socket.close() }
        log("LE CoC listener closed a connection with no current session: role=$role | refused=$refused")
    }

    @Synchronized
    private fun onListenerFailed(handle: CocServerHandle<S>, error: IOException) {
        if (server === handle) {
            server = null
            log("LE CoC listener failed: role=$role | error=${error.message}")
        }
        runCatching { handle.close() }
        endLease(handle, error)
    }

    /** The failed session sees [error] on accept(); the role is free for a fresh listener. */
    private fun endLease(handle: CocServerHandle<S>, error: IOException) {
        val lease = active?.takeIf { it.handle === handle } ?: return
        lease.fail(error)
        active = null
        generation++
    }

    @Synchronized
    private fun release(lease: Lease) {
        if (active === lease) {
            active = null
            generation++
        }
    }

    private inner class Lease(
        private val owner: String,
        val handle: CocServerHandle<S>,
        val generation: Long,
    ) : CocListenLease<S> {
        private val queue = LinkedBlockingQueue<Any>()
        private var closed = false
        private var failure: IOException? = null

        override val psm: Int = handle.psm
        override val shared: Boolean = true

        /** Queues at most [MAX_QUEUED] connections; a closed or failed lease takes none. */
        @Synchronized
        fun offer(socket: S): Boolean =
            !closed && failure == null && queue.size < MAX_QUEUED && queue.offer(socket)

        @Synchronized
        fun fail(error: IOException) {
            if (failure == null) failure = error
            queue.offer(WAKE)
        }

        override fun accept(): S = take(queue.take())

        override fun accept(timeoutMs: Long): S =
            take(queue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException("LE CoC accept timed out"))

        /**
         * Returns a dequeued connection only while the lease is open and healthy. A connection taken
         * after close() or a listener failure is closed, never handed to the session.
         */
        @Suppress("UNCHECKED_CAST")
        private fun take(item: Any): S {
            val usable = synchronized(this) { item !== WAKE && !closed && failure == null }
            if (usable) return item as S
            // Closed outside the lease lock, so delivery (which holds the hub lock) never waits on it.
            if (item !== WAKE) runCatching { (item as Closeable).close() }
            synchronized(this) {
                queue.offer(WAKE)
                if (closed) throw IOException("LE CoC listener lease closed: role=$role | owner=$owner")
                throw failure ?: IOException("LE CoC listener unavailable: role=$role")
            }
        }

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            release(this)
            queue.offer(WAKE)
            while (true) {
                val item = queue.poll() ?: break
                if (item !== WAKE) runCatching { (item as Closeable).close() }
            }
            queue.offer(WAKE)
        }
    }

    private companion object {
        /** Connections queued for a session that has not called accept() yet (raw benchmark: up to 4 channels). */
        const val MAX_QUEUED = 8
        val WAKE = Any()
    }
}

/** A listener owned by one session and closed with it: the behaviour before shared listeners. */
internal class DedicatedCocListener(private val server: BluetoothServerSocket) : CocListenLease<BluetoothSocket> {
    override val psm: Int get() = server.psm
    override val shared: Boolean = false
    override fun accept(): BluetoothSocket = server.accept()
    override fun accept(timeoutMs: Long): BluetoothSocket = server.accept(timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
    override fun close() = server.close()
}

/**
 * Process-wide LE CoC listeners, one per role ("private-call", "receiver", "relay", "call-relay",
 * "raw-benchmark"), only where DeviceFamilyPolicy.leCocServerLeaks matches (Android 10). Elsewhere
 * each session opens and closes its own listener, as before. DEBUG launch extra
 * `test_coc_shared_listener=true|false` forces either behaviour ([DebugTunables.sharedCocListener]).
 */
object CocListeners {
    private val roles = HashMap<String, SharedCocListener<BluetoothSocket>>()

    /** The single decision: DEBUG override, else the stack-family rule. */
    internal fun sharedEnabledFor(fingerprint: BluetoothStackFingerprint, override: Boolean?): Boolean =
        override ?: DeviceFamilyPolicy.leCocServerLeaks(fingerprint)

    fun sharedEnabled(): Boolean =
        sharedEnabledFor(BluetoothStackFingerprint.current(), DebugTunables.sharedCocListener)

    @SuppressLint("MissingPermission")
    fun listen(adapter: BluetoothAdapter, role: String, owner: String = role): CocListenLease<BluetoothSocket> {
        EncoderBenchGuard.check("CocListenerHub.listen")
        if (sharedEnabled()) {
            val shared = synchronized(roles) {
                roles.getOrPut(role) {
                    SharedCocListener(
                        role,
                        open = { PlatformCocServer(role, platformListen(adapter, role)) },
                        log = { Log.i("BluetoothVideo", it) },
                    )
                }
            }
            shared.acquire(owner)?.let { return echoGuarded(it, owner) }
        }
        val server = platformListen(adapter, role)
        CocRegistrations.note("listen", "$owner-dedicated")
        CocBudget.onListen(runCatching { server.psm }.getOrDefault(-1), CocRegistrations.ledger)
        CocListenerLedger.opened(owner, server)
        return echoGuarded(
            object : CocListenLease<BluetoothSocket> by DedicatedCocListener(server) {
                override fun close() {
                    runCatching { server.close() }
                    CocListenerLedger.closed(owner, server)
                }
            },
            owner,
        )
    }

    /**
     * Experimental (`test_coc_echo_verify`): while the switch is on, every accepted connection answers the
     * client's 1 KB echo before the session sees it. Off: the lease is returned unchanged.
     */
    private fun echoGuarded(lease: CocListenLease<BluetoothSocket>, owner: String): CocListenLease<BluetoothSocket> =
        if (CocEchoVerify.enabled()) EchoRespondingLease.forSockets(lease, owner) { Log.i("BluetoothVideo", it) } else lease

    /**
     * The platform listen. Experimental watchdog (`test_call_listen_watchdog_ms`, off): a listen the stack has not
     * completed by the deadline throws [ListenStalledException] (the wedged S22 stack blocked it forever inside
     * createSocketChannel), and a server socket that arrives later is closed. Off: a plain blocking call, as before.
     */
    @SuppressLint("MissingPermission")
    private fun platformListen(adapter: BluetoothAdapter, role: String): BluetoothServerSocket {
        val deadlineMs = CocAvailabilitySwitches.listenWatchdogMs
        val open = {
            if (CallListenInject.hangRequested(role)) CallListenInject.hangForever()
            adapter.listenUsingInsecureL2capChannel()
        }
        try {
            return if (deadlineMs <= 0) open() else watchdogListen(role, deadlineMs, open)
        } catch (error: IOException) {
            if (error !is ListenStalledException) lastListenFailure = error
            throw error
        }
    }

    /** The last exception the platform listen itself threw (identity only; the status tells a used-up stack by it). */
    @Volatile var lastListenFailure: Throwable? = null
        private set

    private fun watchdogListen(role: String, deadlineMs: Long, open: () -> BluetoothServerSocket): BluetoothServerSocket {
        val watchdog = ListenWatchdog<BluetoothServerSocket>(
            deadlineMs,
            closeLate = { late -> late.close() },
            onLate = { lateMs ->
                Log.i("BluetoothVideo", "BENCHMARK call_availability: state=late_socket_closed | stage=coc_listen | role=$role | lateMs=$lateMs")
            },
        )
        return try {
            watchdog.run(role, open)
        } catch (stalled: ListenStalledException) {
            Log.w(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_availability: state=failed | stage=coc_listen | " +
                    "reason=stalled | role=$role | waitedMs=${stalled.waitedMs} | deadlineMs=$deadlineMs",
            )
            CallsAvailabilityTracker.noteListenStalled(role)
            throw stalled
        }
    }

    /** Adapter off/on: every stack listener is gone (and on Android 10 its leaked slots are freed). */
    fun resetAll(reason: String) {
        val all = synchronized(roles) { roles.values.toList() }
        all.forEach { it.reset(reason) }
    }

    private class PlatformCocServer(
        private val role: String,
        private val server: BluetoothServerSocket,
    ) : CocServerHandle<BluetoothSocket> {
        init {
            CocRegistrations.note("listen", "$role-shared")
            CocBudget.onListen(runCatching { server.psm }.getOrDefault(-1), CocRegistrations.ledger)
            CocListenerLedger.opened("$role-shared", server)
        }

        override val psm: Int = server.psm
        override fun accept(): BluetoothSocket = server.accept()
        override fun close() {
            runCatching { server.close() }
            CocListenerLedger.closed("$role-shared", server)
        }
    }
}
