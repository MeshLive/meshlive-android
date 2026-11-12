package com.example.bluetoothtest

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A discovery plane that must stop publish/subscribe and release its attach hold while Wi-Fi Direct runs (M-10).
 * [pauseDiscovery] is called ON the wifi thread, once per lease; [owner] is the lease key `"<owner>#<n>"`, unique
 * per lease. A pauser must stop publish and subscribe, close its sessions and call `WifiRadio.releaseAware(hold)`
 * for its discovery hold BEFORE returning, and stay stopped (its reconcile loop checks
 * [WifiRadio.discoveryPaused]). [resumeDiscovery] restarts as the gate decides; a pauser must tolerate
 * `AttachFailure.BUSY_DIRECT` from its next acquire.
 */
internal interface DiscoveryPauser {
    fun pauseDiscovery(owner: String)
    fun resumeDiscovery(owner: String)
}

internal class DiscoveryPauseLease internal constructor(
    val owner: String,
    internal val key: String,
    private val gate: DiscoveryPauseGate,
) {
    internal val latch = CountDownLatch(1)

    val active: Boolean get() = gate.isActive(this)

    /**
     * Blocks until every registered pauser has returned from [DiscoveryPauser.pauseDiscovery] for this lease. On the
     * wifi thread the pause already ran inline, so it returns true at once. False on timeout or when the lease was
     * already resumed. NEVER call it on the main thread.
     */
    fun awaitPaused(timeoutMs: Long): Boolean =
        active && latch.await(timeoutMs, TimeUnit.MILLISECONDS) && active

    /** Idempotent, any thread. */
    fun resume() = gate.release(this)
}

/**
 * The pause gate of M-10: one counter serves every owner, so the keys in use are `direct#<n>`, `direct-sweep#<n>`
 * and `share#<n>` and two leases never share a key. Pure: no Context, no Log, no Handler. A lease taken while no
 * pauser is registered posts nothing and starts no thread [Am 2d].
 */
internal class DiscoveryPauseGate(private val scheduler: WifiScheduler, private val log: (String) -> Unit) {
    private val activeLeases = CopyOnWriteArraySet<DiscoveryPauseLease>() // any thread
    // A key is the "delivered" mark that [release] reads on any thread [Am 2d]; its value is the pausers that this
    // lease has actually paused, read and written on the wifi thread only. One record per pauser and lease means
    // that a lease reaching a pauser through both [register] and the pause delivery pauses it once, and that the
    // mark is only dropped by the block that created it while it was still empty. [pause gate race]
    private val delivered = ConcurrentHashMap<DiscoveryPauseLease, MutableSet<DiscoveryPauser>>()
    private val pausers = CopyOnWriteArrayList<DiscoveryPauser>() // written on the wifi thread only, read anywhere [Am 2d]
    private val serial = AtomicInteger(0)

    /** True from the moment [pause] returns, even before the pausers ran. */
    val paused: Boolean get() = activeLeases.isNotEmpty()

    fun register(pauser: DiscoveryPauser) = onWifi {
        if (pausers.none { it === pauser }) pausers.add(pauser)
        for (lease in activeLeases.toList()) deliver(lease, listOf(pauser))
    }

    fun unregister(pauser: DiscoveryPauser) = onWifi {
        pausers.removeAll { it === pauser }
        delivered.values.forEach { it.remove(pauser) } // a pauser registered again starts unpaused
    }

    fun pause(owner: String): DiscoveryPauseLease {
        val lease = DiscoveryPauseLease(owner, "$owner#${serial.incrementAndGet()}", this)
        activeLeases.add(lease)
        if (pausers.isEmpty()) {
            lease.latch.countDown() // no pauser: nothing to post, no thread to start [Am 2d]
            log(WifiLog.upgrade("discovery_pause", "step" to "pause", "owner" to owner, "pausers" to 0, "leases" to activeLeases.size))
            return lease
        }
        onWifi {
            if (deliver(lease, pausers.toList())) {
                log(WifiLog.upgrade("discovery_pause", "step" to "pause", "owner" to owner, "pausers" to pausers.size, "leases" to activeLeases.size))
            }
            lease.latch.countDown()
        }
        return lease
    }

    /**
     * Wifi thread. Pauses each of [targets] that this lease has not paused yet; false when the lease was already
     * released. The mark is set FIRST and the lease re-checked after it [A4 review P2-2]: a release that runs in
     * between either sees the mark and posts its resume after this block (FIFO), or the lease is skipped here. A
     * released lease drops the mark only while it is still empty: a mark that holds pausers belongs to an earlier
     * delivery whose resume is already queued, and removing it would leave those pausers paused for good.
     */
    private fun deliver(lease: DiscoveryPauseLease, targets: List<DiscoveryPauser>): Boolean {
        val reached = delivered.computeIfAbsent(lease) { Collections.newSetFromMap(IdentityHashMap()) }
        if (lease !in activeLeases) {
            if (reached.isEmpty()) delivered.remove(lease, reached)
            return false
        }
        for (pauser in targets) {
            if (reached.add(pauser)) callPauser(pauser) { it.pauseDiscovery(lease.key) }
        }
        return true
    }

    internal fun isActive(lease: DiscoveryPauseLease) = lease in activeLeases

    internal fun release(lease: DiscoveryPauseLease) {
        if (!activeLeases.remove(lease)) return
        lease.latch.countDown()
        if (delivered.containsKey(lease)) {
            onWifi {
                val reached = delivered.remove(lease)
                if (reached != null) {
                    // Only the pausers this lease paused, in registration order: none is resumed for a pause it never saw.
                    pausers.filter { it in reached }.forEach { pauser -> callPauser(pauser) { it.resumeDiscovery(lease.key) } }
                    log(
                        WifiLog.upgrade(
                            "discovery_pause",
                            "step" to "resume",
                            "owner" to lease.owner,
                            "pausers" to pausers.size,
                            "leases" to activeLeases.size,
                        ),
                    )
                }
            }
        }
    }

    /** Shutdown: drops every lease WITHOUT resume callbacks; [paused] becomes false. The pausers stay registered. */
    fun clear() {
        val snapshot = activeLeases.toList()
        activeLeases.clear()
        snapshot.forEach { it.latch.countDown() }
        onWifi { delivered.clear() }
        log(WifiLog.upgrade("discovery_pause", "step" to "clear", "pausers" to pausers.size, "leases" to 0))
    }

    private fun callPauser(pauser: DiscoveryPauser, block: (DiscoveryPauser) -> Unit) {
        try {
            block(pauser)
        } catch (e: RuntimeException) {
            // A pauser that throws does not stop the others; no address or name is ever in the line.
            log(WifiLog.upgrade("discovery_pause", "step" to "error", "reason" to e.javaClass.simpleName))
        }
    }

    private fun onWifi(block: () -> Unit) {
        if (scheduler.isWifiThread()) block() else scheduler.post(block)
    }
}
