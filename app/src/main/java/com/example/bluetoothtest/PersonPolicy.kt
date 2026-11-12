package com.example.bluetoothtest

import android.content.Context
import android.util.Log

/**
 * One rule for "this person is blocked" (review 2026-10-27, S9), applied wherever a person's real identity becomes
 * authenticated: the call gate at the Invite (a real-key caller), the reveal of a pseudonym call (either role), a live
 * stream's source (the audio header's identity key, a direct video source's handshake, a forwarded stream's `BFG1`
 * source), a message exchange with a neighbour, and the presence-lease offer. Blocking someone also revokes the presence
 * leases they hold ([onBlocked]). Each place keeps its own action (decline, hang up, leave the stream, close the link,
 * withhold the lease); only the question is answered here.
 *
 * A block keys on the full fingerprint (CallBlocking.kt); a forwarded source known only by its 128-bit node ID is
 * matched by the node ID, which is the first 16 bytes of that fingerprint. The check fails open (an unreadable block list
 * blocks nobody), like the call gate.
 */
internal object PersonPolicy {
    enum class Verdict { ALLOW, BLOCKED }

    private class Source(val fingerprint: (IdentityFingerprint) -> Boolean, val node: (NodeId) -> Boolean)

    @Volatile private var source: Source = Source({ false }, { false })
    @Volatile private var installed = false

    /** What a block does besides the list (production: revoke this phone's presence leases held by that person). */
    @Volatile private var blockedHook: (IdentityFingerprint) -> Unit = {}

    /**
     * The routes (logical stream token and the advertised ID of that route) whose source authenticated as a blocked person:
     * hidden for this process only. Review 2026-10-27 (finding 1): the token alone is public and chosen by the
     * advertiser, so hiding every route with it let a blocked broadcaster copy a genuine broadcast's token and hide that
     * broadcast too; only the route actually tried is hidden.
     */
    private val hiddenStreamTokens = LinkedHashSet<String>()

    /**
     * AB3: radio addresses of Watch rows hidden by "Hide this livestream" on a route whose advertised ID was not proven
     * ([LocalReportPolicy.keptRoute]): only that advertiser goes, for this process only (never stored).
     */
    private val hiddenAddresses = LinkedHashSet<String>()

    private fun routeKey(offer: DiscoveryAnnouncement): String =
        offer.logicalStreamToken().toString() + ":" + offer.nodeIdPrefix.joinToString("") { "%02x".format(it) }

    /** Installed by the application at start (and again by the screens that can run first); reads the phone's block list. */
    fun install(context: Context) {
        val app = context.applicationContext
        source = Source({ BlockedPeople.isBlocked(app, it) }, { BlockedPeople.isBlockedNode(app, it) })
        blockedHook = { fingerprint -> revokePresence(fingerprint.nodeId) }
        installed = true
    }

    /** For a caller that holds a context: makes sure the block list is consulted even if the application hook did not run. */
    fun ensureInstalled(context: Context) {
        if (!installed) install(context)
    }

    internal fun installForTest(blocked: Set<IdentityFingerprint>, onBlocked: (IdentityFingerprint) -> Unit = {}) {
        source = Source({ it in blocked }, { node -> blocked.any { it.nodeId == node } })
        blockedHook = onBlocked
        installed = true
        synchronized(hiddenStreamTokens) { hiddenStreamTokens.clear() }
        synchronized(hiddenAddresses) { hiddenAddresses.clear() }
    }

    internal fun resetForTest() = installForTest(emptySet())

    fun check(fingerprint: IdentityFingerprint?): Verdict {
        fingerprint ?: return Verdict.ALLOW
        return if (runCatching { source.fingerprint(fingerprint) }.getOrDefault(false)) Verdict.BLOCKED else Verdict.ALLOW
    }

    /** A source known by its node ID only (a forwarded stream's `BFG1` grant, a link peer). */
    fun checkNode(nodeId: NodeId?): Verdict {
        nodeId ?: return Verdict.ALLOW
        return if (runCatching { source.node(nodeId) }.getOrDefault(false)) Verdict.BLOCKED else Verdict.ALLOW
    }

    fun blocked(fingerprint: IdentityFingerprint?): Boolean = check(fingerprint) == Verdict.BLOCKED

    fun blockedNode(nodeId: NodeId?): Boolean = checkNode(nodeId) == Verdict.BLOCKED

    /** Called by the block list after [fingerprint] was added (or was already there). Never throws. */
    fun onBlocked(fingerprint: IdentityFingerprint) {
        runCatching { blockedHook(fingerprint) }
            .onFailure { runCatching { Log.w("BluetoothVideo", "person_policy: block side effect failed (${it.javaClass.simpleName})") } }
    }

    /** A stream turned out to come from a blocked person: this route of it stays hidden (the Watch list and the radio list). */
    fun hideStream(offer: DiscoveryAnnouncement) {
        val token = routeKey(offer)
        synchronized(hiddenStreamTokens) {
            hiddenStreamTokens.remove(token)
            hiddenStreamTokens += token
            while (hiddenStreamTokens.size > MAX_HIDDEN_STREAMS) hiddenStreamTokens.remove(hiddenStreamTokens.first())
        }
    }

    fun streamHidden(offer: DiscoveryAnnouncement): Boolean = routeKey(offer).let { key -> synchronized(hiddenStreamTokens) { key in hiddenStreamTokens } }

    /** AB3: hides the advertiser heard at [address] until the app restarts ([hiddenAddresses]). */
    fun hideAddress(address: String) {
        synchronized(hiddenAddresses) {
            hiddenAddresses.remove(address)
            hiddenAddresses += address
            while (hiddenAddresses.size > MAX_HIDDEN_STREAMS) hiddenAddresses.remove(hiddenAddresses.first())
        }
    }

    fun addressHidden(address: String): Boolean = synchronized(hiddenAddresses) { address in hiddenAddresses }

    /** Emergency wipe: forget the hidden routes and addresses (nothing about them is stored). */
    internal fun clearForWipe() {
        synchronized(hiddenStreamTokens) { hiddenStreamTokens.clear() }
        synchronized(hiddenAddresses) { hiddenAddresses.clear() }
    }

    const val MAX_HIDDEN_STREAMS = 256

    /**
     * Blocking a verified contact who holds this phone's presence lease of the current generation stops the lease
     * (a new generation, [PresenceVault.revokeForBlocked]); without it a blocked contact kept recognising this phone nearby
     * for up to four weeks.
     */
    private fun revokePresence(nodeId: NodeId) {
        if (!RotatingDiscoveryFeature.enabled) return
        val vault = RotatingDiscovery.presenceVault() ?: return
        if (vault.revokeForBlocked(nodeId)) {
            RotatingDiscovery.requestRecognitionRebuild()
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "rotid_presence: event=revoked | reason=blocked")
        }
    }
}
