package com.example.bluetoothtest

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/*
 * Call blocking (USER_GUIDE "Block unknown callers" and "Blocked people"). Local only: no wire byte changes.
 *
 * WHAT A BLOCK KEYS ON. The only stable, authenticated identity of a person is the full fingerprint (SHA-256 of
 * the identity key, IdentityFingerprint); a block stores that and never a discovery ID, a name or a 48-bit prefix.
 * A phone sees that fingerprint before the call rings only when the caller presents its real key: a verified
 * contact whose presence lease is current, or a phone that does not use pseudonyms (older build, BMH2, the
 * "Pseudonymous toward strangers" switch off). A stranger's caller key is a throwaway made per attempt
 * (PROTOCOL "Phase 2: pseudonymous stranger identities"); the real key reaches the callee only after the callee
 * answers. So a ringing call from a stranger cannot be matched against the block list, and "Block unknown
 * callers" is the setting that stops it. The policy below says exactly that and nothing more.
 */

/** The caller as the phone knows it when a call arrives, before it rings. */
data class IncomingCaller(
    /** True while the caller is only a throwaway key: no identity exists to compare yet. */
    val pseudonymous: Boolean,
    /** The authenticated identity key's fingerprint; null when [pseudonymous]. */
    val fingerprint: IdentityFingerprint?,
)

enum class CallGateDecision {
    /** Ring as usual. */
    RING,

    /** The caller is on the block list: decline without ringing or notifying. */
    DECLINE_BLOCKED_PERSON,

    /** "Block unknown callers" is on and the caller is not a recognised verified contact. */
    DECLINE_UNKNOWN,
}

/** Decides at INVITE time whether an incoming private call may ring. Must be called off the main thread. */
fun interface CallGate {
    fun check(caller: IncomingCaller): CallGateDecision
}

/** Whether the caller is a verified contact, as far as the contact store can say. */
internal enum class ContactStanding { VERIFIED, NOT_VERIFIED, UNKNOWN }

internal object CallBlockingPolicy {
    /** A blocked or unknown caller is declined after this long, like a person who looked at the screen first. */
    const val DECLINE_DELAY_MIN_MS = 3_000L
    const val DECLINE_DELAY_SPREAD_MS = 4_000L

    /**
     * - A caller that presents a real key found on the block list never rings (blocked people win over contacts).
     * - With "Block unknown callers" on, only a caller that presents a real key and is a verified contact rings.
     *   A throwaway key cannot be a recognised contact: a contact whose lease expired arrives like this and is
     *   declined too (the setting's text says so). An unreadable contact store rings (fail open: it never
     *   blocks someone it cannot judge).
     */
    fun decide(
        blockUnknown: Boolean,
        pseudonymous: Boolean,
        blocked: Boolean,
        standing: ContactStanding,
    ): CallGateDecision = when {
        !pseudonymous && blocked -> CallGateDecision.DECLINE_BLOCKED_PERSON
        !blockUnknown -> CallGateDecision.RING
        pseudonymous -> CallGateDecision.DECLINE_UNKNOWN
        standing == ContactStanding.VERIFIED -> CallGateDecision.RING
        standing == ContactStanding.UNKNOWN -> CallGateDecision.RING
        else -> CallGateDecision.DECLINE_UNKNOWN
    }

    /** The existing decline goes out after a human-looking delay so the caller cannot tell it was automatic. */
    fun declineDelayMs(random: java.util.Random): Long =
        DECLINE_DELAY_MIN_MS + (random.nextDouble() * DECLINE_DELAY_SPREAD_MS).toLong()

    /**
     * AB3 (Windows WP6N real-key screen; owner rule "risk on the attacker"): a caller whose callee presented its real key in
     * the endpoint handshake ends the call the ordinary way when [blocked] says that key is on the block list, exactly as
     * after a blocked reveal. A throwaway callee is judged at its reveal and a callee judges its caller at the Invite (the
     * call gate), so [blocked] is asked only here. The production [blocked] is [PersonPolicy.blocked], which fails open.
     */
    fun endsRealKeyCall(caller: Boolean, peerPseudonymous: Boolean, blocked: () -> Boolean): Boolean =
        caller && !peerPseudonymous && blocked()
}

/**
 * AB3/AP3 step 2b: the one per-call "a blocked person ended this call" latch. The call keeps one instance and clears it
 * at every new connection ([reset]), so a blocked call can never carry into the next call made with the same endpoint
 * object. Media, the caller's name and presence use the existing blocked-path decisions; this class only makes the
 * lifetime of that latch testable.
 */
internal class BlockedCallLatch {
    @Volatile
    var active: Boolean = false
        private set

    fun block() {
        active = true
    }

    fun reset() {
        active = false
    }
}

/** One blocked person: the full fingerprint and when the block was made. Nothing else is stored. */
internal data class BlockedPerson(val fingerprint: IdentityFingerprint, val blockedAtMs: Long) {
    val prefixHex: String get() = fingerprint.nodePrefixHex
    val handle: String get() = IdentityHandle.fromPrefixHex(prefixHex)
}

internal enum class BlockResult { BLOCKED, ALREADY_BLOCKED, FULL, STORE_UNREADABLE, UNAVAILABLE }

/**
 * The block list, sealed at rest with the same Keystore key as the verified contacts (so the emergency wipe, which
 * deletes that key and everything under no_backup, erases it). Never overwritten while unreadable.
 */
internal class BlockedPeopleStore(
    private val file: File,
    private val cipher: AtRestCipher,
    /**
     * Design decision 2026-11-06: an unreadable list stays fail-open (CF-CALL-24) but is logged, once each time it becomes
     * unreadable (not on every check of every call). The argument is the failure's class name, never file content.
     * Placed before [clock] so the existing `BlockedPeopleStore(file, cipher) { clock }` calls keep their meaning.
     */
    private val onUnreadable: (String) -> Unit = { cause ->
        runCatching {
            Log.w(
                "BluetoothVideo",
                (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=list_unreadable | fail_open=true | cause=$cause",
            )
        }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cache: List<BlockedPerson>? = null

    /** True from the first failed read until a read succeeds or the list is reset. */
    private var unreadableLogged = false

    /** Readable entries; empty when the store cannot be read (see [isUnreadable]). */
    @Synchronized
    fun all(): List<BlockedPerson> = loadOrNull().orEmpty()

    @Synchronized
    fun isUnreadable(): Boolean = loadOrNull() == null

    @Synchronized
    fun contains(fingerprint: IdentityFingerprint): Boolean = loadOrNull()?.any { it.fingerprint == fingerprint } == true

    /** A person known only by the 128-bit node ID (the first 16 bytes of the fingerprint): a forwarded stream's source. */
    @Synchronized
    fun containsNode(nodeId: NodeId): Boolean = loadOrNull()?.any { it.fingerprint.nodeId == nodeId } == true

    @Synchronized
    fun add(fingerprint: IdentityFingerprint): BlockResult {
        val current = loadOrNull() ?: return BlockResult.STORE_UNREADABLE
        if (current.any { it.fingerprint == fingerprint }) return BlockResult.ALREADY_BLOCKED
        if (current.size >= MAX_BLOCKED) return BlockResult.FULL
        save(current + BlockedPerson(fingerprint, clock()))
        return BlockResult.BLOCKED
    }

    /** True when the person was on the list. */
    @Synchronized
    fun remove(fingerprint: IdentityFingerprint): Boolean {
        val current = loadOrNull() ?: return false
        val remaining = current.filterNot { it.fingerprint == fingerprint }
        if (remaining.size == current.size) return false
        save(remaining)
        return true
    }

    /** Deletes the whole list, including an unreadable one. */
    @Synchronized
    fun reset(): Boolean {
        val removed = !file.exists() || file.delete()
        cache = null
        unreadableLogged = false
        return removed
    }

    @Synchronized
    fun dropCache() {
        cache = null
    }

    private fun loadOrNull(): List<BlockedPerson>? {
        cache?.let { return it }
        if (!file.exists()) return emptyList<BlockedPerson>().also { cache = it }
        return try {
            parse(cipher.open(file.readBytes())).also {
                cache = it
                unreadableLogged = false
            }
        } catch (error: Exception) {
            if (!unreadableLogged) {
                unreadableLogged = true
                onUnreadable(error.javaClass.simpleName)
            }
            null
        }
    }

    private fun parse(plain: ByteArray): List<BlockedPerson> {
        DataInputStream(plain.inputStream()).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a block list" }
            val count = input.readUnsignedShort()
            require(count <= MAX_BLOCKED) { "Too many blocked people" }
            val people = List(count) {
                val fingerprint = IdentityFingerprint.fromBytes(ByteArray(IdentityFingerprint.BYTE_COUNT).also(input::readFully))
                BlockedPerson(fingerprint, input.readLong())
            }
            require(input.read() == -1) { "Trailing bytes in block list" }
            return people
        }
    }

    private fun save(people: List<BlockedPerson>) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeShort(people.size)
            people.forEach {
                output.write(it.fingerprint.bytes())
                output.writeLong(it.blockedAtMs)
            }
        }
        WipeGuard.guardedWrite("block list") { atomicWrite(file, cipher.seal(bytes.toByteArray())) }
        cache = people
    }

    companion object {
        const val MAX_BLOCKED = 512
        private val MAGIC = "BBP1".toByteArray(Charsets.US_ASCII)
    }
}

/** The process-wide block list on the phone (file next to the verified contacts, same Keystore key). */
internal object BlockedPeople {
    const val FILE = "blocked-people.bin"

    @Volatile private var store: BlockedPeopleStore? = null

    /** Prefixes of the blocked people, for the Watch list; null until the first background load finished. */
    @Volatile private var prefixSnapshot: Set<String>? = null
    @Volatile private var loading = false

    private fun store(context: Context): BlockedPeopleStore = store ?: synchronized(this) {
        store ?: run {
            WipeGuard.check("block list")
            val file = File(File(context.applicationContext.noBackupFilesDir, IdentityVerification.DIRECTORY), FILE)
            BlockedPeopleStore(file, IdentityVerification.atRestCipher()).also { store = it }
        }
    }

    internal fun installForTest(testStore: BlockedPeopleStore?) {
        synchronized(this) {
            store = testStore
            prefixSnapshot = null
        }
    }

    fun list(context: Context): List<BlockedPerson> = store(context).all().sortedBy { it.blockedAtMs }

    fun isBlocked(context: Context, fingerprint: IdentityFingerprint): Boolean = store(context).contains(fingerprint)

    fun isBlockedNode(context: Context, nodeId: NodeId): Boolean = store(context).containsNode(nodeId)

    fun isUnreadable(context: Context): Boolean = store(context).isUnreadable()

    /**
     * Every block goes through here (Recent, a contact, "Decline and block", a stream, a chat, local reporting), so the
     * side effects of [PersonPolicy.onBlocked] (review 2026-10-27, finding 3: the person's presence leases) always run.
     */
    fun block(context: Context, fingerprint: IdentityFingerprint): BlockResult =
        runCatching { store(context).add(fingerprint) }.getOrDefault(BlockResult.UNAVAILABLE).also { result ->
            prefixSnapshot = null
            if (result == BlockResult.BLOCKED || result == BlockResult.ALREADY_BLOCKED) PersonPolicy.onBlocked(fingerprint)
        }

    fun unblock(context: Context, fingerprint: IdentityFingerprint): Boolean =
        runCatching { store(context).remove(fingerprint) }.getOrDefault(false).also { prefixSnapshot = null }

    /** Forgets every blocked person (the recovery for an unreadable list). Logged, like the unreadable list itself. */
    fun reset(context: Context): Boolean = runCatching { store(context).reset() }.getOrDefault(false).also { ok ->
        prefixSnapshot = null
        runCatching {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "call_blocking: event=list_reset | ok=$ok")
        }
    }

    /**
     * The 12-hex prefixes of the blocked people, never blocking the caller: before the first load completes it
     * starts one on a worker thread and returns the empty set (the Watch list refreshes every few seconds).
     */
    fun cachedPrefixes(context: Context): Set<String> {
        prefixSnapshot?.let { return it }
        if (!loading) {
            loading = true
            val app = context.applicationContext
            Thread {
                try {
                    prefixSnapshot = runCatching { store(app).all().map { it.prefixHex }.toSet() }.getOrDefault(emptySet())
                } finally {
                    loading = false
                }
            }.start()
        }
        return emptySet()
    }

    /** Emergency wipe: forget the in-memory copy (the file and its Keystore key are deleted by the wipe itself). */
    internal fun clearForWipe() {
        synchronized(this) {
            store?.dropCache()
            store = null
            prefixSnapshot = null
        }
    }
}

/**
 * What a screen that shows blocking says about the saved list (design decision 2026-11-06): while it cannot be read, the
 * note "Block list can't be read" with Reset, and never "No one is blocked" (calls are not blocked, but people may be on
 * it). Pure; [BlockedPeopleActivity] and the Settings row use it.
 */
internal data class BlockListView(val showUnreadableNote: Boolean, val showEmptyState: Boolean) {
    companion object {
        fun of(people: List<BlockedPerson>, unreadable: Boolean): BlockListView =
            BlockListView(showUnreadableNote = unreadable, showEmptyState = people.isEmpty() && !unreadable)
    }
}

/** What a person is told after a Block (Recent, a contact, "Decline and block"). Pure; [CallBlockingUi] uses it. */
internal enum class BlockOutcome {
    DONE,
    ALREADY,
    FULL,

    /** Not saved because the list cannot be read: offer Reset ([CallBlockingUi.showBlockListUnreadable]). */
    LIST_UNREADABLE,
    FAILED,
    ;

    companion object {
        fun of(result: BlockResult): BlockOutcome = when (result) {
            BlockResult.BLOCKED -> DONE
            BlockResult.ALREADY_BLOCKED -> ALREADY
            BlockResult.FULL -> FULL
            BlockResult.STORE_UNREADABLE -> LIST_UNREADABLE
            BlockResult.UNAVAILABLE -> FAILED
        }
    }
}

/** The "Block unknown callers" setting and the gate the call endpoint consults. */
internal object CallBlocking {
    /** App preference (bluetooth_video_settings, wiped by the emergency wipe). Default off. */
    const val PREF_BLOCK_UNKNOWN = "block_unknown_callers"

    private val random = java.util.Random()

    fun blockUnknown(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(PREF_BLOCK_UNKNOWN, false)

    fun declineDelayMs(): Long = CallBlockingPolicy.declineDelayMs(random)

    /** A store that cannot judge (unreadable, mismatching key, absent) never makes someone "unknown". */
    internal fun standingOf(state: VerificationState?): ContactStanding = when (state) {
        VerificationState.VERIFIED -> ContactStanding.VERIFIED
        VerificationState.UNVERIFIED -> ContactStanding.NOT_VERIFIED
        else -> ContactStanding.UNKNOWN
    }

    /** The production gate: the preference, the block list and the verified-contact store. Fails open on any error. */
    fun gate(context: Context): CallGate = CallGate { caller ->
        runCatching {
            PersonPolicy.ensureInstalled(context)
            val fingerprint = caller.fingerprint
            val blocked = !caller.pseudonymous && fingerprint != null && PersonPolicy.blocked(fingerprint)
            val standing = when {
                caller.pseudonymous || fingerprint == null -> ContactStanding.NOT_VERIFIED
                else -> standingOf(PeerIdentityGate.store?.state(fingerprint.nodeId, fingerprint))
            }
            // Visible by name can allow calls from anyone for its ten minutes (asked when it starts; reverts with it).
            CallBlockingPolicy.decide(blockUnknown(context) && !NameWindowGate.allowAnyone, caller.pseudonymous, blocked, standing)
        }.getOrElse {
            Log.w("BluetoothVideo", "call_blocking: gate failed (${it.javaClass.simpleName}); ringing")
            CallGateDecision.RING
        }
    }

    /**
     * Watch list: hides the route at cost 0 (a broadcaster heard directly) that is attributed to a blocked person, judged
     * by its fixed announcement prefix, or for a rotating ID by the one contact it is recognised as, plus every route
     * whose source authenticated as a blocked person when someone watched or listened ([hiddenRoute],
     * [PersonPolicy.hideStream]; review 2026-10-27 finding 4, also under a rotating ID nobody recognises).
     * Review 2026-11-06 (finding 2, the rule Windows adopted): the logical stream token and the advertised prefix are
     * chosen by the advertiser and are not authenticated (a rotating ID can be replayed), so an attributed route hides
     * only itself. Relayed routes and other direct routes of the same token stay listed, so a spoofer who copies a
     * genuine broadcast's token cannot hide it. A relayed copy of a blocked person's stream stays listed until someone
     * opens it; playback then leaves as soon as its source authenticates as the blocked person, and that route stays
     * hidden ([hiddenRoute]).
     */
    fun <T> hideBlockedStreams(
        peers: List<T>,
        announcement: (T) -> DiscoveryAnnouncement,
        blockedPrefixes: Set<String>,
        recognised: (DiscoveryAnnouncement) -> String?,
        hiddenRoute: (DiscoveryAnnouncement) -> Boolean = { false },
    ): List<T> {
        if (peers.isEmpty()) return peers
        val authenticatedHidden = peers.filterNot { hiddenRoute(announcement(it)) }
        if (blockedPrefixes.isEmpty()) return authenticatedHidden
        return hideByPrefix(authenticatedHidden, announcement, blockedPrefixes, recognised)
    }

    /** Hides only the cost-0 routes attributed to a blocked person; never another route of the same token. */
    private fun <T> hideByPrefix(
        peers: List<T>,
        announcement: (T) -> DiscoveryAnnouncement,
        blockedPrefixes: Set<String>,
        recognised: (DiscoveryAnnouncement) -> String?,
    ): List<T> = peers.filterNot { peer ->
        val route = announcement(peer)
        route.routeCost == 0 && attributedPrefix(route, recognised)?.let { it in blockedPrefixes } == true
    }

    /** Who a direct route is attributed to: its fixed prefix, or the one contact a rotating ID is recognised as. */
    private fun attributedPrefix(route: DiscoveryAnnouncement, recognised: (DiscoveryAnnouncement) -> String?): String? =
        if (route.rotating) recognised(route) else route.nodeIdPrefix.joinToString("") { "%02x".format(it) }

    fun hideBlockedStreams(context: Context, peers: List<BleL2capPeer>): List<BleL2capPeer> =
        hideBlockedStreams(
            peers,
            BleL2capPeer::announcement,
            BlockedPeople.cachedPrefixes(context) + LocalReporting.prefixes(context),
            recognised = { RotatingDiscovery.recognize(it).singleOrNull()?.contact?.toString()?.take(12) },
            hiddenRoute = PersonPolicy::streamHidden,
        )
}
