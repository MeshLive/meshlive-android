package com.example.bluetoothtest

/**
 * F-12: a Wi-Fi Direct group whose removal was not confirmed. One per process. Every member runs on the wifi thread.
 * While active the ledger claim stays held (so Direct and Aware stay refused) and every held discovery lease stays
 * paused; confirmation releases both.
 */
internal class DirectQuarantine(
    private val openPort: () -> P2pPort,
    private val record: DirectGroupRecord,
    private val ledger: WifiLedger,
    private val scheduler: DirectGroup.Scheduler,
    private val notice: (stuck: Boolean) -> Unit,
    private val log: (String) -> Unit,
) {
    companion object {
        const val RETRY_MS = 10_000L
        const val MAX_RETRIES = 6
        const val SLOW_CHECK_MS = 60_000L

        /** One retry (open, read, remove, confirm poll) ends within this; then its channel is closed (P2-2). */
        const val RETRY_TIMEOUT_MS = DirectLimits.CLEANUP_WATCHDOG_MS
    }

    @Volatile
    private var activeFlag = false

    /** From [enter] until absence is confirmed. */
    val active: Boolean get() = activeFlag

    private var claimOwner: String? = null
    private var name: String? = null
    private var owned = false
    private var retries = 0
    private var stuck = false
    private var scheduled: DirectGroup.Cancelable? = null
    private val leases = ArrayList<DiscoveryPauseLease>()

    // The retry in flight (A9 review P2-2): a token so a late callback of an expired retry changes nothing.
    private var round = 0
    private var roundLive = false
    private var roundPort: P2pPort? = null
    private var roundTimer: DirectGroup.Cancelable? = null
    private val roundTimers = ArrayList<DirectGroup.Cancelable>()

    /**
     * Keeps [claimOwner]'s ledger claim (never released here until confirmed) and starts retrying. [owned]: this process
     * created or joined the group, or saw our name on it, so an unreadable group that groupFormed reports may be removed
     * while the record names it (A9 review P1-2); false (a start-up sweep that could not read the group) removes only a
     * group whose name it can read.
     */
    fun enter(name: String?, claimOwner: String, owned: Boolean = true) {
        if (activeFlag) return
        activeFlag = true
        this.name = name
        this.claimOwner = claimOwner
        this.owned = owned && name != null
        retries = 0
        stuck = false
        ledger.directQuarantined = true
        log(WifiLog.upgrade("quarantine", "reason" to "entered"))
        schedule(DirectQuarantine.RETRY_MS)
    }

    /** Resumed only after confirmation. */
    fun holdLease(lease: DiscoveryPauseLease) {
        if (!activeFlag) {
            lease.resume()
            return
        }
        leases += lease
    }

    private fun schedule(delayMs: Long) {
        scheduled?.cancel()
        scheduled = scheduler.post(delayMs) { retry() }
    }

    private fun retry() {
        if (!activeFlag) return
        val token = ++round
        roundLive = true
        val port = try {
            openPort()
        } catch (error: RuntimeException) {
            roundLive = false
            failed()
            return
        }
        roundPort = port
        roundTimer = scheduler.post(RETRY_TIMEOUT_MS) { if (endRound(token)) failed() }
        val opened = try {
            port.open { if (endRound(token)) failed() }
        } catch (error: RuntimeException) {
            false
        }
        if (!opened) {
            if (endRound(token)) failed()
            return
        }
        val alive = { token == round && roundLive && activeFlag }
        if (stuck) {
            port.readGroup { read ->
                if (!alive()) return@readGroup
                val gone = read == GroupRead.Absent ||
                    (read is GroupRead.Present && read.group.networkName.let { it == null || isOurs(it) == false })
                if (!endRound(token)) return@readGroup
                if (gone) confirm(clear = true, removedName = null) else slowFailed()
            }
            return
        }
        val expected = name
        DirectRemoval(port, scheduler, alive, track = { roundTimers += it }).run(
            isOurs = ::isOurs,
            removeUnreadable = owned && expected != null && record.containsOrNull(expected) != false,
        ) { outcome ->
            if (!endRound(token)) return@run
            when (outcome) {
                DirectRemoval.Outcome.Absent, is DirectRemoval.Outcome.Other -> confirm(clear = true, removedName = null)
                is DirectRemoval.Outcome.Removed -> confirm(clear = false, removedName = outcome.name ?: expected)
                is DirectRemoval.Outcome.Unconfirmed, DirectRemoval.Outcome.Unknown -> failed()
            }
        }
    }

    /** Ends the retry [token] once: cancels its timers and closes its channel. false = already ended (or superseded). */
    private fun endRound(token: Int): Boolean {
        if (token != round || !roundLive) return false
        roundLive = false
        roundTimer?.cancel()
        roundTimer = null
        roundTimers.forEach { runCatching { it.cancel() } }
        roundTimers.clear()
        val port = roundPort
        roundPort = null
        if (port != null) runCatching { port.closeChannel() }
        return true
    }

    /** null = cannot tell (the record cannot be read): such a group is kept, never removed (A9 review P1-3). */
    private fun isOurs(networkName: String): Boolean? {
        val expected = name
        if (expected != null && networkName == expected) return true
        return record.containsOrNull(networkName)
    }

    private fun failed() {
        retries++
        if (retries >= DirectQuarantine.MAX_RETRIES) {
            if (!stuck) {
                stuck = true
                notice(true)
                log(WifiLog.upgrade("quarantine", "reason" to "stuck"))
            }
            schedule(DirectQuarantine.SLOW_CHECK_MS)
        } else {
            schedule(DirectQuarantine.RETRY_MS)
        }
    }

    private fun slowFailed() {
        if (!stuck) {
            stuck = true
            notice(true)
            log(WifiLog.upgrade("quarantine", "reason" to "stuck"))
        }
        schedule(DirectQuarantine.SLOW_CHECK_MS)
    }

    /** Absence confirmed: only now may the record lose the name (a failed record write keeps it, P1-3). */
    private fun confirm(clear: Boolean, removedName: String?) {
        if (clear) {
            record.tryClear()
        } else if (removedName != null) {
            record.tryRemove(removedName)
        }
        ledger.directQuarantined = false
        claimOwner?.let { ledger.releaseDirect(it) }
        claimOwner = null
        activeFlag = false
        notice(false)
        log(WifiLog.upgrade("quarantine", "reason" to "confirmed"))
        val held = leases.toList()
        leases.clear()
        held.forEach { runCatching { it.resume() } }
    }
}

/** The UI's one signal for F-12: a stuck group's notice, shown while the app is started. */
internal object DirectQuarantineNotice {
    @Volatile
    var stuck: Boolean = false

    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    fun set(stuck: Boolean) {
        this.stuck = stuck
        listener?.invoke(stuck)
    }
}
