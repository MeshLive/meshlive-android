package com.example.bluetoothtest

/** Which knocks (MWK1) a Wider-reach phone answers. Hostile phones can knock; every refusal is cheap and silent on air. */
internal class NativeResponderPolicy(
    private val maxResponders: Int = WifiLimits.NATIVE_RESPONDERS_MAX,
    private val perHandleIntervalMs: Long = WifiLimits.WIDER_KNOCK_INTERVAL_MS,   // [API]
) {
    data class Context(
        val eligible: Boolean,        // WifiDiscovery.eligibility().aware [R M-7]
        val accepting: Boolean,       // WifiOfferRegistry.currentAcceptor() != null
        val freeDataPaths: Int,       // WifiDiscovery.freeDataPaths() = WifiRadio.freeNdp() [API]
        val upgradeBusy: Boolean,     // (WifiRadio.activeCount() - WifiNativeLink.liveLinks()) > 0: A5 attempts and carriers only, A8's own links excluded [API][GAP+]
    )

    sealed interface Decision {
        data class Serve(val nonceHex: String) : Decision
        data class Resend(val port: Int) : Decision
        data class Refuse(val reason: String) : Decision
    }

    private class Active(val handleKey: String, var port: Int?)
    private val active = HashMap<String, Active>()
    private val lastServed = HashMap<String, Long>()

    @Synchronized
    fun onKnock(handleKey: String, nonce: ByteArray, nowMs: Long, ctx: Context): Decision {
        val hex = nonce.joinToString("") { "%02x".format(it) }
        active[hex]?.let { return it.port?.let { port -> Decision.Resend(port) } ?: Decision.Refuse("pending") }
        if (!ctx.eligible) return Decision.Refuse("not_eligible")
        if (!ctx.accepting) return Decision.Refuse("not_accepting")
        if (ctx.freeDataPaths <= 0 || ctx.upgradeBusy) return Decision.Refuse("no_free_path")
        if (active.size >= maxResponders) return Decision.Refuse("busy")
        lastServed[handleKey]?.let { if (nowMs - it < perHandleIntervalMs) return Decision.Refuse("rate") }
        active[hex] = Active(handleKey, null)
        lastServed[handleKey] = nowMs
        if (lastServed.size > 64) lastServed.entries.removeAll { nowMs - it.value >= perHandleIntervalMs }
        return Decision.Serve(hex)
    }

    @Synchronized fun armed(nonceHex: String, port: Int) { active[nonceHex]?.port = port }
    @Synchronized fun finished(nonceHex: String) { active.remove(nonceHex) }
    /** The link completed BMH3/BMH2 admission: it leaves the unauthenticated count (F-3) [AM 12]. */
    @Synchronized fun admitted(nonceHex: String) { active.remove(nonceHex) }
    @Synchronized fun activeCount(): Int = active.size
}

/**
 * The unauthenticated stranger (F-3): the one responder exchange that has reserved an untrusted data path and not yet
 * completed BMH3/BMH2 admission. [reserved] registers it before the ledger reservation, [accepted] arms its admission
 * deadline ([WifiLimits.NATIVE_ADMISSION_MS]) when its socket is accepted, and admission or close clears it.
 * [preemptPending] is what A4's ledger runs when an authenticated reservation finds the budget full: the ledger runs it
 * on the RESERVING thread (a call's connect thread for our own native dial), so the stranger is taken at once but
 * released and ended on the wifi thread, where [WifiNativeLink]'s maps live (A8 review P1-3). Pure: the scheduler and
 * the data-path release are injected, so the JVM tests drive it without Android. [AM 12]
 */
internal class NativeAdmission(
    private val scheduler: WifiScheduler,
    private val release: (ndpOwner: String) -> Unit,
) {
    private class Entry(val linkGen: Long, val ndpOwner: String, val end: () -> Unit) {
        val tag = "a8-admit-$linkGen"   // one instance per entry: a scheduler cancels by tag identity
        var onDeadline: (() -> Unit)? = null
    }

    private val entries = LinkedHashMap<Long, Entry>()

    /** Wifi thread, before the untrusted reservation: [end] stops the exchange (cancel, or close once it has a socket). */
    fun reserved(linkGen: Long, ndpOwner: String, end: () -> Unit) {
        synchronized(this) { entries[linkGen] = Entry(linkGen, ndpOwner, end) }
    }

    /** Wifi thread: the stranger's socket was accepted; [close] runs if admission has not completed by the deadline. */
    fun accepted(linkGen: Long, ndpOwner: String, close: () -> Unit) {
        val entry = synchronized(this) {
            entries.getOrPut(linkGen) { Entry(linkGen, ndpOwner, close) }.also { it.onDeadline = close }
        }
        scheduler.cancel(entry.tag)
        scheduler.postDelayed(entry.tag, WifiLimits.NATIVE_ADMISSION_MS) { onDeadline(linkGen) }
    }

    /** BMH3/BMH2 admission completed: the socket is no longer a stranger and the timer is cancelled. */
    fun admitted(linkGen: Long) { take(linkGen)?.let { scheduler.cancel(it.tag) } }

    /** The exchange ended for any other reason. */
    fun closed(linkGen: Long) { take(linkGen)?.let { scheduler.cancel(it.tag) } }

    /**
     * Any thread (A4's ledger, after it removed the untrusted owner): takes the pending stranger now and releases and
     * ends it on the wifi thread. True when a stranger was pending.
     */
    fun preemptPending(): Boolean {
        val entry = takeFirst() ?: return false
        if (scheduler.isWifiThread()) finish(entry) else scheduler.post { finish(entry) }
        return true
    }

    @Synchronized
    fun pending(): Int = entries.size

    private fun finish(entry: Entry) {
        scheduler.cancel(entry.tag)
        take(entry.linkGen)?.let { scheduler.cancel(it.tag) }   // re-registered by a socket accepted meanwhile
        release(entry.ndpOwner)
        entry.end()
    }

    @Synchronized
    private fun take(linkGen: Long): Entry? = entries.remove(linkGen)

    @Synchronized
    private fun takeFirst(): Entry? {
        val key = entries.keys.firstOrNull() ?: return null
        return entries.remove(key)
    }

    private fun onDeadline(linkGen: Long) {
        val entry = take(linkGen) ?: return
        release(entry.ndpOwner)
        (entry.onDeadline ?: entry.end)()
    }
}

/**
 * Wider-reach native hops are 5 GHz only (owner rule 2026-11-04, decision D9: 2.4 GHz Wi-Fi halved Bluetooth on the
 * M31). Android 13 and newer read a data path's channels; on Android 11 and 12 the band of a native hop cannot be read,
 * and nothing on a native hop (MWK1/MWA1 carry no band bit) tells that end whether the other end verified it, so such a
 * hop is refused there with the reason `band_unverified`, before any data path is requested (A8 review P2-3).
 */
internal object NativeBandRule {
    const val MIN_VERIFY_SDK = 33

    /** This phone can read a native data path's band. */
    fun canVerify(sdkInt: Int): Boolean = sdkInt >= MIN_VERIFY_SDK

    /** Why a ready native data path is refused, or null when it may carry the hop. */
    fun refusal(verdict: AwareBand.Verdict): String? = when (verdict) {
        AwareBand.Verdict.OK -> null
        AwareBand.Verdict.TOO_LOW -> "band_low"
        AwareBand.Verdict.UNKNOWN -> "band_unverified"
    }
}
