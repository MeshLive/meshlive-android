package com.example.bluetoothtest

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * Rotating discovery IDs, Phase 2: what this phone keeps about presence (PROTOCOL "Recognition and
 * presence leases"). One sealed file holds this phone's presence state (generation `g` and key `P`)
 * and, per verified contact, the per-contact switch "Can see when I'm nearby", the generation of the
 * last lease delivered to them and the lease record they gave this phone.
 */
class PresenceContact(
    val nodeId: NodeId,
    /**
     * The per-contact switch "Can see when I'm nearby": true only after the user answered yes. Design decision 2026-11-06
     * (all platforms): a contact the user was not yet asked about ([answered] false) counts as off.
     */
    val visibleToThem: Boolean = false,
    /** Generation of the last lease sent to this contact; 0 = none. */
    val deliveredGeneration: Long = 0L,
    /** The leases this contact gave us (null = none). */
    val presence: ContactPresence? = null,
    /**
     * The user answered "Can see when I'm nearby" (yes or no). False = not yet asked: a record made for a received lease
     * or a delivery. A value an older build stored (always an answer there: its default was on) reads as that answer.
     */
    val answered: Boolean = visibleToThem,
) {
    init {
        require(answered || !visibleToThem) { "A yes is an answer" }
    }

    /** The user's answer, or null while not yet asked. */
    val answer: Boolean? get() = if (answered) visibleToThem else null
}

class PresenceState(val generation: Long, dayKeys: Map<Long, ByteArray>, val contacts: List<PresenceContact>) {
    /** The day-key window (independent random keys, PROTOCOL "Keys and epochs"); a private copy, oldest day first. */
    val dayKeys: java.util.SortedMap<Long, ByteArray> = java.util.TreeMap<Long, ByteArray>().also { map ->
        dayKeys.forEach { (day, key) -> map[day] = key.copyOf() }
    }

    init {
        require(generation in 1..0xffffffffL) { "Presence generation" }
        require(this.dayKeys.isNotEmpty() && this.dayKeys.size <= PresenceStateCodec.MAX_KEYS) { "Presence key count" }
        require(this.dayKeys.all { (day, key) -> day in 0..0xffffffffL && key.size == DiscoveryExchange.LEASE_BYTES }) { "Presence day key" }
        require(contacts.size <= PresenceStateCodec.MAX_CONTACTS) { "Too many presence contacts" }
    }

    /** Overwrites the key bytes held by this object (a replaced or revoked state must not linger in memory). */
    fun zeroise() = dayKeys.values.forEach { it.fill(0) }
}

/**
 * `"BPS1" | version 2 | generation u32 | key count u8 | count x (day u32, L_d (32)) | contact count u16 | count x contact`,
 * sealed by [PresenceStore]. Version 1 held one long-lived key `P` in place of the key block; it is read once and
 * converted ([DayKeyWindow.migrated]) and never written again.
 */
object PresenceStateCodec {
    private val MAGIC = "BPS1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    const val MAX_CONTACTS = VerifiedContactStore.MAX_CONTACTS
    const val MAX_KEYS = 64
    private const val MAX_DAYS = ContactPresence.MAX_DAYS_KEPT
    /** Per-contact flag: "Can see when I'm nearby" is on. */
    private const val FLAG_VISIBLE = 1
    /** Per-contact flag (design decision 2026-11-06): not yet asked. Older builds never wrote it (their contacts were answered). */
    private const val FLAG_NOT_ASKED = 2

    /** [migrated] is true when the bytes were a version 1 store converted with the epoch passed to [decode]. */
    class Decoded(val state: PresenceState, val migrated: Boolean)

    fun encode(state: PresenceState): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeInt(state.generation.toInt())
            out.writeByte(state.dayKeys.size)
            state.dayKeys.forEach { (day, key) ->
                out.writeInt(day.toInt())
                out.write(key)
            }
            out.writeShort(state.contacts.size)
            state.contacts.forEach { contact ->
                out.write(contact.nodeId.toByteArray())
                out.writeByte((if (contact.visibleToThem) FLAG_VISIBLE else 0) or (if (contact.answered) 0 else FLAG_NOT_ASKED))
                out.writeInt(contact.deliveredGeneration.toInt())
                val presence = contact.presence
                if (presence == null) {
                    out.writeInt(0)
                } else {
                    out.writeInt(presence.generation.toInt())
                    out.writeInt(presence.skewEpochs.toInt())
                    out.writeLong(presence.receivedAtMs)
                    val days = presence.leasesByDay.toSortedMap().entries.toList().takeLast(MAX_DAYS)
                    out.writeByte(days.size)
                    days.forEach { (day, lease) ->
                        out.writeInt(day.toInt())
                        out.write(lease)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    /** [migrationEpoch] supplies the epoch for converting a version 1 store; it is not called for version 2. */
    fun decode(plain: ByteArray, migrationEpoch: () -> Long): Decoded {
        DataInputStream(plain.inputStream()).use { input ->
            val magic = ByteArray(4).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Not a presence store" }
            val version = input.readUnsignedByte()
            require(version == VERSION || version == LEGACY_VERSION) { "Unsupported presence store version" }
            val generation = input.readInt().toLong() and 0xffffffffL
            val keys = java.util.TreeMap<Long, ByteArray>()
            var legacyKey: ByteArray? = null
            if (version == LEGACY_VERSION) {
                legacyKey = ByteArray(RotatingDiscoveryIds.PRESENCE_KEY_BYTES).also(input::readFully)
            } else {
                val keyCount = input.readUnsignedByte()
                require(keyCount in 1..MAX_KEYS) { "Presence key count" }
                repeat(keyCount) {
                    val day = input.readInt().toLong() and 0xffffffffL
                    require(keys.put(day, ByteArray(DiscoveryExchange.LEASE_BYTES).also(input::readFully)) == null) { "Duplicate presence day" }
                }
            }
            val count = input.readUnsignedShort()
            require(count <= MAX_CONTACTS) { "Too many presence contacts" }
            val seen = HashSet<NodeId>()
            val contacts = List(count) {
                val nodeId = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT).also(input::readFully))
                require(seen.add(nodeId)) { "Duplicate presence contact" }
                val flags = input.readUnsignedByte()
                require(flags and (FLAG_VISIBLE or FLAG_NOT_ASKED).inv() == 0) { "Reserved presence flags" }
                require(flags != (FLAG_VISIBLE or FLAG_NOT_ASKED)) { "Reserved presence flags" }
                val delivered = input.readInt().toLong() and 0xffffffffL
                val leaseGeneration = input.readInt().toLong() and 0xffffffffL
                val presence = if (leaseGeneration == 0L) {
                    null
                } else {
                    val skew = input.readInt().toLong()
                    val receivedAt = input.readLong()
                    val dayCount = input.readUnsignedByte()
                    require(dayCount in 1..MAX_DAYS) { "Presence lease count" }
                    val days = LinkedHashMap<Long, ByteArray>()
                    repeat(dayCount) {
                        val day = input.readInt().toLong() and 0xffffffffL
                        days[day] = ByteArray(DiscoveryExchange.LEASE_BYTES).also(input::readFully)
                    }
                    ContactPresence(nodeId.toByteArray(), leaseGeneration, days, skew, receivedAt)
                }
                PresenceContact(nodeId, flags and FLAG_VISIBLE != 0, delivered, presence, answered = flags and FLAG_NOT_ASKED == 0)
            }
            require(input.read() == -1) { "Trailing bytes in presence store" }
            if (legacyKey == null) return Decoded(PresenceState(generation, keys, contacts), migrated = false)
            return try {
                Decoded(PresenceState(generation, DayKeyWindow.migrated(legacyKey, migrationEpoch()), contacts), migrated = true)
            } finally {
                legacyKey.fill(0)
            }
        }
    }
}

/**
 * The sealed presence file. Like the verified-contact store it is absent until first needed, never
 * overwritten while it cannot be read, and reports why it is unreadable: a corrupt file latches until
 * [reset], a Keystore or I/O failure is retried on the next access.
 */
class PresenceStore(private val file: File, private val cipher: AtRestCipher) {
    sealed class Loaded {
        object Missing : Loaded()
        /** [migrated]: the file was a version 1 store and [state] holds the converted window (not yet written back). */
        class Ok(val state: PresenceState, val migrated: Boolean = false) : Loaded()
        class Unreadable(val cause: StoreUnreadableCause) : Loaded()
    }

    private var latched: StoreUnreadableCause? = null

    @Synchronized
    fun load(migrationEpoch: () -> Long): Loaded {
        latched?.let { return Loaded.Unreadable(it) }
        if (!file.exists()) return Loaded.Missing
        val sealed = try {
            file.readBytes()
        } catch (error: IOException) {
            return Loaded.Unreadable(StoreUnreadableCause.IO_ERROR)
        }
        val plain = try {
            cipher.open(sealed)
        } catch (error: AEADBadTagException) {
            return corrupt()
        } catch (error: IllegalArgumentException) {
            return corrupt()
        } catch (error: Exception) {
            return Loaded.Unreadable(StoreUnreadableCause.KEYSTORE_ERROR)
        }
        return try {
            PresenceStateCodec.decode(plain, migrationEpoch).let { Loaded.Ok(it.state, it.migrated) }
        } catch (error: Exception) {
            corrupt()
        }
    }

    private fun corrupt(): Loaded {
        latched = StoreUnreadableCause.CORRUPT_FILE
        return Loaded.Unreadable(StoreUnreadableCause.CORRUPT_FILE)
    }

    /** Seals and writes [state]; false (nothing written) while a corrupt file is latched or on an I/O or Keystore error. */
    @Synchronized
    fun save(state: PresenceState): Boolean {
        if (latched != null) return false
        return try {
            file.parentFile?.mkdirs()
            WipeGuard.guardedWrite("presence") { atomicWrite(file, cipher.seal(PresenceStateCodec.encode(state))) }
            true
        } catch (error: Exception) {
            false
        }
    }

    /** Deletes the file, including a corrupt one (the user chose to reset presence data). */
    @Synchronized
    fun reset(): Boolean {
        val removed = !file.exists() || file.delete()
        if (removed) latched = null
        return removed
    }
}

/** The highest presence generation ever used on this phone (preferences): survives a lost or reset store. */
interface GenerationFloor {
    fun get(): Long
    fun raiseTo(value: Long)
}

/**
 * This phone's presence state in memory, over a [PresenceStore] (design 3, 5.2, RV11, RV4). The day keys never
 * leave the phone except in a lease to a verified contact: the window `[dayOf(E - 1), dayOf(E) + 27]` of
 * independent random keys ([DayKeyWindow], PROTOCOL "Keys and epochs"). While the store cannot be read the vault
 * runs with an **ephemeral** window and refuses every change: the phone advertises (fresh IDs nobody
 * recognises), sends no OFFER and recognises nobody, and it never writes over the unreadable file.
 *
 * A key is persisted before it is used for a lease: [deliverLease] rolls and stores the window first, so two
 * different keys for one day can never reach two contacts (a crash before the write loses a key nobody saw).
 */
class PresenceVault(
    private val store: PresenceStore,
    private val floor: GenerationFloor,
    private val random: SecureRandom = SecureRandom(),
    /** The Phase 1 plain key file (generation, key), imported once if the store is missing. */
    private val legacyKey: () -> Pair<Long, ByteArray>? = { null },
    private val onLegacyImported: () -> Unit = {},
    private val clockMs: () -> Long = DiscoveryWallClock::nowMs,
    /** The epoch new windows start at: the monotonic `E` of the epoch clock, or the wall epoch when it is ahead. */
    private val epochSource: () -> Long = { RotatingDiscoveryIds.wallEpoch(clockMs() / 1000L) },
) {
    private var state: PresenceState? = null
    private val ephemeral = java.util.TreeMap<Long, ByteArray>()
    private var lastAutoRetryMs = 0L

    /** True when the key and the lease records are stored (leases may be delivered and recognised). */
    @get:Synchronized
    val persistent: Boolean get() = state != null

    /** Loads (or creates) the state; true when persistent. Call again to retry a transient failure. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (state != null) return true
        when (val loaded = store.load(epochSource)) {
            is PresenceStore.Loaded.Ok -> {
                if (loaded.state.generation < floor.get()) {
                    // The counter moved past this file: a revocation that could not be stored, or an older
                    // copy was restored. Its keys must never be used again; contacts' records are kept.
                    val next = PresenceState(floor.get() + 1, DayKeyWindow.fresh(epochSource(), ::newKey), loaded.state.contacts)
                    floor.raiseTo(next.generation)
                    loaded.state.zeroise()
                    if (store.save(next)) {
                        state = next
                        clearEphemeral()
                    }
                } else {
                    // A converted version 1 store is written back once; if that fails the derived window is used in
                    // memory and the conversion is repeated from the unchanged file at the next load (RH5).
                    if (loaded.migrated) store.save(loaded.state)
                    state = loaded.state
                    floor.raiseTo(loaded.state.generation)
                    clearEphemeral()
                    // Review 2026-10-27 (finding 14): a phone that was off (or whose app did not run) over a day boundary
                    // kept the keys of days that are over, its own and its contacts' leases, until the next daily
                    // maintenance. They are dropped as soon as the store is loaded.
                    runCatching { pruneExpiredLocked(epochSource()) }
                }
            }
            PresenceStore.Loaded.Missing -> {
                val legacy = legacyKey()
                val epoch = epochSource()
                val created = if (legacy != null && legacy.second.size == RotatingDiscoveryIds.PRESENCE_KEY_BYTES &&
                    legacy.first in maxOf(1L, floor.get())..0xffffffffL
                ) {
                    // The Phase 1 plain file held a long-lived key: convert it once, like a version 1 store.
                    PresenceState(legacy.first, DayKeyWindow.migrated(legacy.second, epoch), emptyList())
                } else {
                    PresenceState(floor.get() + 1, DayKeyWindow.fresh(epoch, ::newKey), emptyList())
                }
                floor.raiseTo(created.generation)
                if (store.save(created)) {
                    state = created
                    clearEphemeral()
                    if (legacy != null) onLegacyImported()
                }
            }
            is PresenceStore.Loaded.Unreadable -> Unit
        }
        // A load that just succeeded settles a pending blocked-people revocation before any restored key is used.
        if (state != null) reconcileBlockedIfPending(force = true)
        return state != null
    }

    /** Who is blocked (node IDs), or null while the block list cannot be read; set by [requestBlockedReconcile]. */
    private var blockedPeopleSource: (() -> Set<NodeId>?)? = null
    private var blockedReconcilePending = false
    private var lastBlockedAttemptMs = 0L
    private var reconcilingBlocked = false

    /**
     * Review 2026-10-27 (finding 5): a contact blocked while rotating IDs were off still holds a current lease. The
     * revocation stays pending until both the vault and the block list can be read, and it is completed before a restored
     * key is handed out ([dayKey]), not skipped for the rest of the run after one temporary failure.
     */
    @Synchronized
    fun requestBlockedReconcile(source: () -> Set<NodeId>?) {
        blockedPeopleSource = source
        blockedReconcilePending = true
        ensureLoaded()
        reconcileBlockedIfPending(force = true)
    }

    @get:Synchronized
    val blockedReconcileOutstanding: Boolean get() = blockedReconcilePending

    private fun reconcileBlockedIfPending(force: Boolean) {
        if (!blockedReconcilePending || reconcilingBlocked || state == null) return
        val now = clockMs()
        if (!force && lastBlockedAttemptMs != 0L && now - lastBlockedAttemptMs in 0 until AUTO_RETRY_MS) return
        lastBlockedAttemptMs = now
        val blocked = runCatching { blockedPeopleSource?.invoke() }.getOrNull() ?: return
        reconcilingBlocked = true
        try {
            blocked.forEach { revokeForBlocked(it, failClosedWhenUnreadable = false) }
            blockedReconcilePending = false
        } finally {
            reconcilingBlocked = false
        }
    }

    @Synchronized
    fun generation(): Long = state?.generation ?: maxOf(floor.get(), 1L)

    /**
     * The day key for the day of [idEpoch] (an ID of that epoch is computed from it), with the window rolled to
     * [currentEpoch] when a key is missing; a copy. Null only for a day outside the window. While the store is
     * unreadable the key is an in-memory ephemeral one (the retry of the load runs at most every few seconds).
     */
    @Synchronized
    fun dayKey(idEpoch: Long, currentEpoch: Long): ByteArray? {
        if (state == null) {
            // Every advert and reach page asks for a key: while the store is unreadable retry the load only
            // every few seconds (a Keystore read and a decrypt under the lock each time would be wasteful).
            val now = clockMs()
            if (lastAutoRetryMs == 0L || now - lastAutoRetryMs >= AUTO_RETRY_MS || now < lastAutoRetryMs) {
                lastAutoRetryMs = now
                ensureLoaded()
            }
        } else {
            // The block list could not be read at start: retried here (every few seconds) until the revocation is done.
            reconcileBlockedIfPending(force = false)
        }
        val day = PresenceLeases.dayOf(idEpoch)
        if (!DayKeyWindow.contains(currentEpoch, day)) return null
        state?.let { current ->
            // A copy: a failed revocation zeroes the stored array while a caller may still be hashing with it.
            current.dayKeys[day]?.let { return it.copyOf() }
            if (rollLocked(currentEpoch)) state?.dayKeys?.get(day)?.let { return it.copyOf() }
        }
        return ephemeralKey(day, currentEpoch).copyOf()
    }

    /**
     * Once-per-day maintenance at the monotonic epoch [epoch]: deletes the day that left the window, creates the
     * key of the new last day, deletes contacts' lease days that are over, and stores the result once. True when
     * the stored state changed. Off the advertisers' path: every key the next epoch needs already exists.
     */
    @Synchronized
    fun maintain(epoch: Long): Boolean {
        if (epoch < 0) return false
        if (state == null) {
            ensureLoaded()
            if (state == null) {
                ephemeral.headMap(DayKeyWindow.firstDay(epoch)).also { old -> old.values.forEach { it.fill(0) } }.clear()
                return false
            }
        }
        val current = state ?: return false
        val rolled = DayKeyWindow.roll(current.dayKeys, epoch, ::newKey)
        var contactsChanged = false
        val contacts = current.contacts.map { contact ->
            val presence = contact.presence ?: return@map contact
            val pruned = presence.pruned(epoch)
            if (pruned === presence) {
                contact
            } else {
                contactsChanged = true
                contact.copyWith(presence = pruned)
            }
        }
        if (rolled === current.dayKeys && !contactsChanged) return false
        val next = PresenceState(current.generation, rolled, contacts)
        if (!store.save(next)) return false
        current.zeroise()
        state = next
        return true
    }

    /** The window of the stored state for the log (first day, last day, key count), or null while it is ephemeral. */
    @Synchronized
    fun windowInfo(): Triple<Long, Long, Int>? =
        state?.dayKeys?.let { keys -> Triple(keys.firstKey(), keys.lastKey(), keys.size) }

    @Synchronized
    fun contact(nodeId: NodeId): PresenceContact? = state?.contacts?.firstOrNull { it.nodeId == nodeId }

    /** Contacts that gave this phone leases, for the recognition table. */
    @Synchronized
    fun leaseHolders(): List<ContactPresence> = state?.contacts?.mapNotNull { it.presence }.orEmpty()

    /** "Can see when I'm nearby" is on only after the user answered yes; not yet asked counts as off (design decision 2026-11-06). */
    @Synchronized
    fun visibleToThem(nodeId: NodeId): Boolean = contact(nodeId)?.visibleToThem ?: false

    /** The user's answer for [nodeId], or null while not yet asked. */
    @Synchronized
    fun answer(nodeId: NodeId): Boolean? = contact(nodeId)?.answer

    /** A lease may be delivered to [nodeId] now: the vault is persistent and the per-contact switch is on. */
    @Synchronized
    fun canDeliver(nodeId: NodeId): Boolean = state != null && visibleToThem(nodeId)

    @Synchronized
    fun contactCount(): Int = state?.contacts?.size ?: 0

    /**
     * Builds the lease for [nodeId] and records its delivery in one step under the vault's lock (RD1): the
     * persistent state, the per-contact switch and [stillVerified] are checked, the window is rolled and stored
     * (a key is persisted before it is delivered), the delivery is stored durably BEFORE the lease is returned,
     * and the keys and generation come from the same state. Null (nothing delivered, nothing recorded) when any
     * of it fails, so a Forget, a switch-off or a revocation that ran before this call can never be followed by
     * a lease, and one that runs after it finds the record.
     */
    @Synchronized
    fun deliverLease(nodeId: NodeId, epoch: Long, stillVerified: () -> Boolean): DiscoveryExchange.PresenceLease? {
        // A stored no never gets a lease. A contact not yet asked is refused before this by the session's eligibility
        // (visibleToThem: no OFFER) and by the call's lease hook ([stillVerified] includes visibleToThem), AB3 R6.
        if (state == null || answer(nodeId) == false || !stillVerified()) return null
        if (!rollLocked(epoch)) return null
        if (!recordDelivered(nodeId)) return null
        val current = state ?: return null
        return PresenceLeases.delivery(current.dayKeys, current.generation, epoch)
    }

    /** Records that a lease of the current generation was sent to [nodeId]. */
    @Synchronized
    fun recordDelivered(nodeId: NodeId): Boolean = mutate { current ->
        val generation = current.generation
        PresenceState(generation, current.dayKeys, upsert(current.contacts, nodeId) { it.copyWith(delivered = generation) })
    }

    /** Stores [lease] from [nodeId] (the receiver rules of [ContactPresence.receive]). False when nothing changed. */
    @Synchronized
    fun storeLease(nodeId: NodeId, lease: DiscoveryExchange.PresenceLease, myEpoch: Long, nowMs: Long): Boolean {
        val before = contact(nodeId)?.presence
        val received = ContactPresence.receive(before, nodeId.toByteArray(), lease, myEpoch, nowMs)
        if (before === received) return false
        // Days that are already over in the sender's numbering are not kept (a seized lease holder).
        val after = received.pruned(myEpoch)
        return mutate { current ->
            PresenceState(current.generation, current.dayKeys, upsert(current.contacts, nodeId) { it.copyWith(presence = after) })
        }
    }

    /**
     * Sets "Can see when I'm nearby" for [nodeId]. Returns true when turning it off after a lease of the
     * current generation was delivered made the phone start a new generation (revocation).
     */
    @Synchronized
    fun setVisible(nodeId: NodeId, visible: Boolean): Boolean {
        val current = state ?: return failClosed(revoke = !visible)
        val existing = current.contacts.firstOrNull { it.nodeId == nodeId }
        // The answer is stored whenever it differs from the stored one, so a no for a contact not yet asked is kept.
        if (existing?.answer == visible) return false
        val revoke = !visible && existing != null && existing.deliveredGeneration == current.generation
        mutate { s ->
            PresenceState(s.generation, s.dayKeys, upsert(s.contacts, nodeId) { it.copyWith(visible = visible, answered = true) })
        }
        return if (revoke) bump() else false
    }

    /**
     * [nodeId] was blocked (review 2026-10-27, finding 3). When it received a lease of the current generation, its
     * "Can see when I'm nearby" is switched off, which starts a new generation ([setVisible]): a blocked contact must not
     * keep recognising this phone. A contact that holds no current lease is left alone (the lease offer skips blocked
     * people, [PersonPolicy]); while the store is unreadable the revocation fails closed. True when a revocation happened.
     */
    @Synchronized
    fun revokeForBlocked(nodeId: NodeId, failClosedWhenUnreadable: Boolean = true): Boolean {
        ensureLoaded()
        // The start-up reconciliation passes false: an unreadable store must not start a new generation at every launch.
        val current = state ?: return failClosedWhenUnreadable && failClosed(revoke = true)
        val existing = current.contacts.firstOrNull { it.nodeId == nodeId } ?: return false
        if (existing.deliveredGeneration != current.generation) return false
        return setVisible(nodeId, false)
    }

    /** Forgets [nodeId]: drops its record and, if a lease of the current generation was delivered, starts a new generation. */
    @Synchronized
    fun forget(nodeId: NodeId): Boolean {
        val current = state ?: return failClosed(revoke = true)
        val existing = current.contacts.firstOrNull { it.nodeId == nodeId } ?: return false
        val revoke = existing.deliveredGeneration == current.generation
        // Revoke first: the keys must stop being valid even if removing the record cannot be stored.
        val revoked = if (revoke) bump() else false
        mutate { s -> PresenceState(s.generation, s.dayKeys, s.contacts.filterNot { it.nodeId == nodeId }) }
        return revoked
    }

    /**
     * Drops the records of contacts that are no longer verified ([verified]) and starts a new generation if a
     * lease of the current generation went to one of them. This catches a Forget or a reset that ran while the
     * experiment was off (the vault did not exist then), so a forgotten contact cannot keep recognising this
     * phone when the experiment is switched on again. True when a revocation happened.
     */
    @Synchronized
    fun reconcile(verified: Set<NodeId>): Boolean {
        val current = state ?: return false
        val stale = current.contacts.filterNot { it.nodeId in verified }
        if (stale.isEmpty()) return false
        val revoke = stale.any { it.deliveredGeneration == current.generation }
        val revoked = if (revoke) bump() else false
        mutate { s -> PresenceState(s.generation, s.dayKeys, s.contacts.filter { it.nodeId in verified }) }
        return revoked
    }

    /** Resets every contact record (the verified contacts were reset); a new generation if any lease was delivered. */
    @Synchronized
    fun resetContacts(): Boolean {
        val current = state ?: return failClosed(revoke = true)
        val revoke = current.contacts.any { it.deliveredGeneration == current.generation }
        val revoked = if (revoke) bump() else false
        mutate { s -> PresenceState(s.generation, s.dayKeys, emptyList()) }
        return revoked
    }

    /**
     * A revocation or "Reset presence key": a whole new window of fresh random keys and a new generation now.
     * The counter is raised first, so if the new state cannot be stored the old keys are dropped from memory (an
     * ephemeral window serves until the store works) and the next load replaces the stored keys (see
     * [ensureLoaded]). True when the old keys are no longer in use.
     */
    @Synchronized
    fun bump(): Boolean {
        val current = state ?: return failClosed(revoke = true)
        val generation = maxOf(floor.get(), current.generation) + 1
        floor.raiseTo(generation)
        val next = PresenceState(generation, DayKeyWindow.fresh(epochSource(), ::newKey), current.contacts)
        current.zeroise()
        if (store.save(next)) {
            state = next
        } else {
            state = null
            clearEphemeral()
        }
        return true
    }

    /**
     * "Reset presence key": a new window and generation. A corrupt file (latched, so nothing could be stored)
     * is deleted and replaced - its lease records are lost - which is the only recovery from that state short
     * of the emergency wipe; every other state takes the normal revocation of [bump].
     */
    @Synchronized
    fun resetKey(): Boolean {
        if (state == null) {
            val loaded = store.load(epochSource)
            if (loaded is PresenceStore.Loaded.Unreadable && loaded.cause == StoreUnreadableCause.CORRUPT_FILE) {
                floor.raiseTo(floor.get() + 1)
                if (store.reset()) {
                    clearEphemeral()
                    ensureLoaded()
                    return true
                }
            }
        }
        return bump()
    }

    /** The store cannot be read: a requested revocation still moves the counter, so no stored key stays valid. */
    private fun failClosed(revoke: Boolean): Boolean {
        if (!revoke) return false
        floor.raiseTo(floor.get() + 1)
        clearEphemeral()
        return true
    }

    /** Forward-jump recovery (RV4) already advanced the clock's generation: adopt a fresh window under it. */
    @Synchronized
    fun adoptGeneration(generation: Long): Boolean {
        floor.raiseTo(generation)
        val current = state ?: run {
            clearEphemeral()
            return false
        }
        if (generation <= current.generation) return false
        val next = PresenceState(generation, DayKeyWindow.fresh(epochSource(), ::newKey), current.contacts)
        current.zeroise()
        if (!store.save(next)) {
            state = null
            return false
        }
        state = next
        return true
    }

    /** Deletes all presence data (reset after a corrupt file, or the wipe). */
    @Synchronized
    fun wipe(): Boolean {
        val removed = store.reset()
        if (removed) {
            state?.zeroise()
            state = null
            clearEphemeral()
        }
        return removed
    }

    /**
     * The load-time half of [maintain] (review 2026-10-27, finding 14): deletes this phone's day keys of days before
     * [DayKeyWindow.firstDay] and the contacts' lease days that are over, and stores the result. It never creates a key:
     * a clock that reads earlier than the stored window must not bring back a day that was already deleted ("a key is
     * never recreated within a generation"); the daily maintenance on the monotonic epoch adds the new days. Only when
     * every stored day is over does it run the whole [maintain] (a new window of days that never existed).
     */
    private fun pruneExpiredLocked(epoch: Long): Boolean {
        if (epoch < 0) return false
        val current = state ?: return false
        val first = DayKeyWindow.firstDay(epoch)
        val kept = current.dayKeys.filterKeys { it >= first }
        if (kept.isEmpty()) return maintain(epoch)
        var contactsChanged = false
        val contacts = current.contacts.map { contact ->
            val presence = contact.presence ?: return@map contact
            val pruned = presence.pruned(epoch)
            if (pruned === presence) contact else contact.copyWith(presence = pruned).also { contactsChanged = true }
        }
        if (kept.size == current.dayKeys.size && !contactsChanged) return false
        val next = PresenceState(current.generation, kept, contacts)
        if (!store.save(next)) return false
        current.zeroise()
        state = next
        return true
    }

    /** Moves the stored window to [epoch] and stores it; false when it could not be stored (the state is unchanged). */
    private fun rollLocked(epoch: Long): Boolean {
        val current = state ?: return false
        val rolled = DayKeyWindow.roll(current.dayKeys, epoch, ::newKey)
        if (rolled === current.dayKeys) return true
        val next = PresenceState(current.generation, rolled, current.contacts)
        if (!store.save(next)) return false
        current.zeroise()
        state = next
        return true
    }

    private fun mutate(change: (PresenceState) -> PresenceState): Boolean {
        val current = state ?: return false
        val next = try {
            change(current)
        } catch (error: IllegalArgumentException) {
            return false
        }
        if (!store.save(next)) return false
        // The new state holds its own copies of the keys; the replaced object must not keep the old ones.
        current.zeroise()
        state = next
        return true
    }

    private fun upsert(
        contacts: List<PresenceContact>,
        nodeId: NodeId,
        change: (PresenceContact) -> PresenceContact,
    ): List<PresenceContact> {
        val index = contacts.indexOfFirst { it.nodeId == nodeId }
        return if (index >= 0) {
            contacts.toMutableList().also { it[index] = change(contacts[index]) }
        } else {
            require(contacts.size < PresenceStateCodec.MAX_CONTACTS) { "Too many presence contacts" }
            contacts + change(PresenceContact(nodeId))
        }
    }

    private fun PresenceContact.copyWith(
        visible: Boolean = visibleToThem,
        delivered: Long = deliveredGeneration,
        presence: ContactPresence? = this.presence,
        answered: Boolean = this.answered,
    ) = PresenceContact(nodeId, visible, delivered, presence, answered)

    private fun ephemeralKey(day: Long, epoch: Long): ByteArray {
        ephemeral.headMap(DayKeyWindow.firstDay(epoch)).also { old -> old.values.forEach { it.fill(0) } }.clear()
        return ephemeral.getOrPut(day) { newKey() }
    }

    private fun clearEphemeral() {
        ephemeral.values.forEach { it.fill(0) }
        ephemeral.clear()
    }

    private fun newKey(): ByteArray = ByteArray(DiscoveryExchange.LEASE_BYTES).also(random::nextBytes)

    private companion object {
        const val AUTO_RETRY_MS = 5_000L
    }
}
