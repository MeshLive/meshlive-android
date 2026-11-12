package com.example.bluetoothtest

import java.util.concurrent.CopyOnWriteArrayList

internal enum class DirectReserve { OK, BUSY_AWARE, BUSY_FOREIGN, BUSY_OWN }

/**
 * Phone-wide Wi-Fi bookkeeping. In memory, process lifetime (master 5.5: not persisted).
 * Thread-safe with ONE lock for the ledger: every member that reads or writes [faults], the NDP owner set, the
 * untrusted reservation, the authenticated wishes, the release listeners or [directGroupActive]'s own owner is
 * `@Synchronized`; the raw `@Volatile` fields are for reads from any thread. The Share activity calls
 * [reserveDirect] from the main thread; A5, A8 and A9 record faults and reserve from the wifi thread. [API]
 */
internal class WifiLedger(private val clock: () -> Long) {
    companion object {
        /** The owner a release listener hears when every claim was released at once. */
        const val ALL_OWNERS = "*"
    }

    private val faults = kotlin.collections.ArrayDeque<Long>()
    private val ndpOwners = LinkedHashSet<String>()
    private var untrusted: String? = null // the pending, unauthenticated native reservation (at most one) [F-3]
    private var untrustedPreempt: (() -> Unit)? = null
    private val authenticatedWants = HashSet<String>()
    private val releaseListeners = CopyOnWriteArrayList<(owner: String) -> Unit>()
    @Volatile private var directOwner: String? = null

    @Volatile var pauseUntilMs: Long = 0L
        private set
    @Volatile var observedMaxNdp: Int? = null
        private set
    @Volatile var awareHeld: Boolean = false // written by AwareAttachCoordinator only (attaching or attached)
    @Volatile var foreignDirectGroup: Boolean = false // Share app nearby (OfflineAppShareActivity) owns a group
    @Volatile var otherClientAtAttach: Boolean? = null // isDeviceAttached() read just before our own attach
    @Volatile var lastReleaseMs: Long = 0L

    /** A Wi-Fi Direct group whose group info is unconfirmed (A9's quarantine): an all-claims release keeps it claimed. [F-12] */
    @Volatile var directQuarantined: Boolean = false

    private val band24Paths = HashSet<String>()

    /** AP10 (WIFI-19): a 2.4 GHz-only Wi-Fi Aware data path of this app is live ([setBand24Path]); read from any thread. */
    @Volatile var band24PathLive: Boolean = false
        private set

    val ndpReserved: Int @Synchronized get() = ndpOwners.size // one lock for the whole ledger [Am 6]

    val directGroupActive: Boolean get() = directOwner != null || foreignDirectGroup

    /** Returns true when this fault started a pause. WifiLimits.LOCAL_FAULT_LIMIT faults inside LOCAL_FAULT_WINDOW_MS pause attempts for LOCAL_FAULT_PAUSE_MS. Any thread. */
    @Synchronized
    fun recordLocalFault(kind: String): Boolean {
        val now = clock()
        while (faults.isNotEmpty() && now - faults.first() >= WifiLimits.LOCAL_FAULT_WINDOW_MS) faults.removeFirst()
        faults.addLast(now)
        if (faults.size < WifiLimits.LOCAL_FAULT_LIMIT) return false // [S19]
        pauseUntilMs = now + WifiLimits.LOCAL_FAULT_PAUSE_MS
        faults.clear()
        return true
    }

    /**
     * An authenticated (this app's hop) reservation. Not synchronized itself: the decision is taken under the lock
     * and a preempted untrusted owner is told after it ([Am 4]). Preempts a pending untrusted native reservation
     * when the budget is full.
     */
    fun reserveNdp(owner: String, capacity: Int): Boolean {
        val (ok, preempted) = reserveLocked(owner, capacity)
        preempted?.let { runCatching { it() } }
        return ok
    }

    @Synchronized
    private fun reserveLocked(owner: String, capacity: Int): Pair<Boolean, (() -> Unit)?> {
        if (owner in ndpOwners) return true to null
        var preempted: (() -> Unit)? = null
        if (ndpOwners.size >= capacity) {
            val pending = untrusted ?: return false to null
            ndpOwners.remove(pending)
            preempted = untrustedPreempt
            untrusted = null
            untrustedPreempt = null
        }
        ndpOwners.add(owner)
        return true to preempted
    }

    /** Not synchronized itself: removes under the lock, then calls the release listeners ([Am 5]) outside it when an owner was removed. */
    fun releaseNdp(owner: String): Boolean {
        val removed = releaseLocked(owner)
        if (removed) notifyReleased(owner)
        return removed
    }

    @Synchronized
    private fun releaseLocked(owner: String): Boolean {
        if (owner == untrusted) {
            untrusted = null
            untrustedPreempt = null
        }
        return ndpOwners.remove(owner)
    }

    /**
     * The pending reservation of an unauthenticated native responder (A8). At most one at a time; never the last
     * free data path while an authenticated hop says it wants Wi-Fi; false when full. [F-3][Am 4]
     */
    @Synchronized
    fun reserveUntrustedNdp(owner: String, capacity: Int, preempt: () -> Unit): Boolean {
        if (untrusted != null) return false
        if (ndpOwners.size >= capacity) return false
        if (capacity - ndpOwners.size <= 1 && authenticatedWants.isNotEmpty()) return false
        ndpOwners.add(owner)
        untrusted = owner
        untrustedPreempt = preempt
        return true
    }

    /** The unauthenticated reservation became ordinary (BMH3 admission completed on its socket). [F-3][Am 4] */
    @Synchronized
    fun trustNdp(owner: String) {
        if (owner == untrusted) {
            untrusted = null
            untrustedPreempt = null
        }
    }

    /** An authenticated hop wants (or no longer wants) Wi-Fi, so an untrusted reservation never takes the last free path. [F-3][Am 4] */
    @Synchronized
    fun setAuthenticatedWant(hop: String, wants: Boolean) {
        if (wants) authenticatedWants.add(hop) else authenticatedWants.remove(hop)
    }

    /**
     * AP10 (WIFI-19): a hop carries (or no longer carries) on a Wi-Fi Aware data path that has only 2.4 GHz channels.
     * While any does, Bluetooth capacity is learned and read under the 2.4 GHz coexistence key, like beside a 2.4 GHz
     * access point (`LinkCapacity.radioKey`), so a halved measurement never lands in the clean-radio key.
     */
    @Synchronized
    fun setBand24Path(hop: String, live: Boolean) {
        if (live) band24Paths.add(hop) else band24Paths.remove(hop)
        band24PathLive = band24Paths.isNotEmpty()
    }

    /** 1 while a pending untrusted reservation exists, else 0; a preemptable reservation counts as free for authenticated users. [Am 4] */
    val untrustedCount: Int @Synchronized get() = if (untrusted != null) 1 else 0

    /**
     * Called after a real NDP release, outside the lock ([Am 5]), with the released owner ([ALL_OWNERS] for
     * [releaseAllClaims]); A5's agent resets its busy back-off from a release that is not its own (A5 review P2-2).
     */
    fun addNdpReleaseListener(listener: (owner: String) -> Unit) {
        releaseListeners.add(listener)
    }

    fun removeNdpReleaseListener(listener: (owner: String) -> Unit) {
        releaseListeners.remove(listener)
    }

    private fun notifyReleased(owner: String) {
        releaseListeners.forEach { listener -> runCatching { listener(owner) } }
    }

    /** A local request error (requestNetwork threw, not SecurityException) while [active] other data paths were alive: the phone cannot hold more than [active]. Never called from onUnavailable (F-2, [Am 3]). */
    @Synchronized
    fun noteNdpRefusedWhileActive(active: Int) {
        if (active < 1) return
        observedMaxNdp = minOf(observedMaxNdp ?: Int.MAX_VALUE, active)
    }

    /** Atomic: callable from the main thread (Share activity) and from the wifi thread. */
    @Synchronized
    fun reserveDirect(owner: String): DirectReserve = when {
        foreignDirectGroup -> DirectReserve.BUSY_FOREIGN
        directOwner != null -> DirectReserve.BUSY_OWN
        awareHeld -> DirectReserve.BUSY_AWARE
        else -> {
            directOwner = owner
            DirectReserve.OK
        }
    }

    @Synchronized
    fun releaseDirect(owner: String) {
        if (directOwner == owner) directOwner = null
    }

    /**
     * Shutdown and wipe: the closed links' owners were never told, so their claims are freed here. Leaves the
     * fault history, the pause and [foreignDirectGroup] alone. A quarantined Wi-Fi Direct group (an unconfirmed
     * `removeGroup`, F-12) keeps its claim so Direct and Aware stay refused until its absence is confirmed. [Am 16]
     * Not synchronized itself: clears under the lock, then calls the release listeners outside it ([Am 5]), so a
     * listener that takes another lock cannot deadlock against a thread that holds it and reads the ledger.
     * [A4 review P2-1]
     */
    fun releaseAllClaims() {
        if (releaseAllLocked()) notifyReleased(ALL_OWNERS)
    }

    @Synchronized
    private fun releaseAllLocked(): Boolean {
        val hadNdp = ndpOwners.isNotEmpty()
        ndpOwners.clear()
        untrusted = null
        untrustedPreempt = null
        if (!directQuarantined) directOwner = null
        band24Paths.clear() // every data path is closed with its claim
        band24PathLive = false
        return hadNdp
    }
}
