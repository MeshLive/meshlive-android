package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.BitSet
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Store-and-forward limits (DECISIONS Design A sections 5-7). Hop-local: every limit is applied by
 * this phone to one authenticated adjacent identity or to its own storage, never signalled onward.
 */
data class StoreForwardLimits(
    /** v2 carrier role (opt-in, QA1). Off in v1: only bundles addressed to this phone are accepted. */
    val carrierEnabled: Boolean = false,
    val foreignQuotaBytes: Long = 64L * 1024 * 1024,
    val foreignPerNeighborFraction: Double = 0.25,
    val foreignPerTagFraction: Double = 0.10,
    val ownQuotaBytes: Long = 32L * 1024 * 1024,
    val inboxQuotaBytes: Long = 64L * 1024 * 1024,
    /** Per authenticated adjacent identity per UTC day (section 7). */
    val neighborDailyBytes: Long = 20L * 1000 * 1000,
    val neighborDailyBundles: Int = 200,
    val sessionBytes: Long = 8L * 1000 * 1000,
    /** Recipient side: bundles per sending contact per UTC day. */
    val senderDailyBundles: Int = 50,
    val skewHours: Int = 2,
    val maxStoreHours: Int = StoreForwardFormat.MAX_TTL_HOURS,
    val stalledPartialMs: Long = 24L * 3_600_000,
    val progressTimeoutMs: Long = 10_000,
    val sessionTimeoutMs: Long = 120_000,
    /**
     * Messaging v2 non-chat exchange: the total limit above is extended while chunks keep flowing, up to this cap. 8 MB per
     * exchange ([sessionBytes]) at the slowest measured path (about 89 kbps) is about 12 minutes; 15 minutes covers it.
     */
    val sessionHardCapMs: Long = 900_000,
    /** "Flowing" means this many chunks moved within the last [progressTimeoutMs]; fewer is a trickle and gets no extension. */
    val flowingChunks: Int = 4,
    /** Poisoned chunks from one neighbour in one session before the session is abandoned. */
    val maxPoisonedChunks: Int = 4,
    val maxPendingCards: Int = 20,
    /** Serving side, per authenticated adjacent identity per UTC day and per exchange (finding 2). */
    val neighborDailyBytesOut: Long = 20L * 1000 * 1000,
    val sessionBytesOut: Long = 8L * 1000 * 1000,
    /** SUMMARY entries accepted from one peer in one exchange; more is a protocol violation. */
    val maxSummaryEntries: Int = 1024,
    /** Opened messages (both directions) kept in the encrypted archive. */
    val archiveQuotaBytes: Long = 96L * 1024 * 1024,
    /** A requested suspend that the writer cannot honour (blocked write) force-closes after this. */
    val suspendGraceMs: Long = 1_000,
    /** Responder side: per adjacent identity, minimum gap between exchanges and hourly cap (N3). */
    val exchangeCooldownMs: Long = 60_000,
    val maxExchangesPerHour: Int = 10,
    /** Link-received cards nobody accepted are dropped after this (N1). */
    val pendingCardMaxAgeMs: Long = 14L * 24 * 3_600_000,
    /** Neighbour/sender ledger records kept (most recently active first) (N4). */
    val maxNeighborRecords: Int = 512,
) {
    /**
     * A tombstone must outlive acceptance: entries are accepted until expiry + skew, so a seen ID is
     * kept one hour beyond that (finding 10).
     */
    fun tombstoneUntilHour(expiryHour: Int): Int = expiryHour + skewHours + 1
}

enum class BundleRole { OWN, INBOX, FOREIGN }

enum class BundleState {
    /** Own: sealed and waiting for a link to the recipient. */
    QUEUED,
    /** Own: the recipient's phone confirmed it holds the bundle (DELIVERED_LOCAL, hop-local). */
    HANDED,
    /** Own: an end-to-end receipt arrived. */
    DELIVERED,
    RECEIVING,
    /** Foreign: complete and carried. */
    STORED,
    /** Inbox: opened and archived. */
    OPENED,
    /** Inbox: the sender's card is held but not yet accepted. */
    PENDING_CONTACT,
    /**
     * Inbox: complete and authenticated, but the opened message could not be stored durably (archive
     * full or a write failed). The ciphertext is kept, delivery is not acknowledged, and opening is
     * retried at the next exchange (finding 5).
     */
    UNOPENED,
    /**
     * Own: its stored header or chunks are damaged (torn write, failed hash). It is no longer
     * offered and the user sees it failed instead of it silently disappearing (N5).
     */
    FAILED,
    /** Inbox: complete but no slot opened (wrong recipient, tampered or unknown sender). */
    REJECTED,
}

class StoredBundle internal constructor(
    val header: BundleHeader,
    val role: BundleRole,
    state: BundleState,
    internal val present: BitSet,
    val receivedAtMs: Long,
    lastProgressMs: Long,
    /** Hex node ID of the authenticated adjacent identity that handed it to us (null for own). */
    val fromNeighbor: String?,
    /** Own: the recipient's node ID. Inbox: the sender, once opened. */
    peerNode: String?,
) {
    var state: BundleState = state
        internal set
    var lastProgressMs: Long = lastProgressMs
        internal set
    var peerNode: String? = peerNode
        internal set
    val idHex: String = header.bundleId.sfHex()
    val chunkCount: Int get() = header.chunkCount
    val presentCount: Int get() = present.cardinality()
    val complete: Boolean get() = presentCount == header.chunkCount
    fun has(index: Int) = present.get(index)
    fun bitmap(): ByteArray = ChunkBitmap.encode(present, header.chunkCount)

    /** `stored_until = min(expiry_h, received_at + 14 d)` on this phone's clock. */
    fun storedUntilHour(maxStoreHours: Int): Int =
        minOf(header.expiryHour, StoreForwardFormat.hourOf(receivedAtMs) + maxStoreHours)
}

object ChunkBitmap {
    fun encode(bits: BitSet, count: Int): ByteArray {
        val bytes = ByteArray((count + 7) / 8)
        for (i in 0 until count) if (bits.get(i)) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        return bytes
    }

    fun decode(bytes: ByteArray, count: Int): BitSet {
        require(bytes.size == (count + 7) / 8) { "Invalid chunk bitmap length" }
        val bits = BitSet(count)
        for (i in 0 until count) if ((bytes[i / 8].toInt() shr (i % 8)) and 1 == 1) bits.set(i)
        for (i in count until bytes.size * 8) require((bytes[i / 8].toInt() shr (i % 8)) and 1 == 0) { "Bitmap padding bits set" }
        return bits
    }

    fun full(count: Int): BitSet = BitSet(count).apply { set(0, count) }
}

enum class ChunkWriteResult { ACCEPTED, DUPLICATE, POISONED, UNKNOWN_BUNDLE }

/**
 * App-private bundle store (`noBackupFilesDir/store-forward`). One directory per bundle:
 * `header.bin`, a sparse `chunks.bin` with fixed 8 KiB slots, and `meta.properties`. A chunk is
 * written before its bit is persisted, so a kill mid-chunk at worst re-requests that chunk.
 */
class BundleStore(private val root: File, private val clock: () -> Long, private val cipher: AtRestCipher) {
    private val bundlesDir = File(root, "bundles").apply { mkdirs() }
    private val seenFile = File(root, "seen.properties")
    private val bundles = LinkedHashMap<String, StoredBundle>()
    private val seen = HashMap<String, Int>()
    /** Delivered inbox IDs -> the tag they carried (DELIVERED_LOCAL is answered only for that tag). */
    private val delivered = HashMap<String, String>()
    /** Bundle directories that could not be loaded (kept, not silently deleted) -> reason (N5). */
    private val failed = LinkedHashMap<String, String>()
    /** Chunks written since the bundle's last fsync + bitmap save (N4: no fsync per chunk). */
    private val unsynced = HashMap<String, Int>()

    init {
        root.mkdirs()
        bundlesDir.listFiles()?.forEach { dir ->
            runCatching { load(dir) }.onSuccess { bundles[it.idHex] = it }
                .onFailure { failed[dir.name] = it.message ?: it.javaClass.simpleName }
        }
        readSealedProperties(seenFile, cipher).forEach { (key, value) ->
            val name = key as String
            if (name.startsWith(DELIVERED_PREFIX)) delivered[name.removePrefix(DELIVERED_PREFIX)] = value as String
            else seen[name] = (value as String).toInt()
        }
        delivered.keys.retainAll(seen.keys)
    }

    @Synchronized fun get(idHex: String): StoredBundle? = bundles[idHex]

    /** Damaged bundles: unloadable directories plus own bundles marked FAILED -> reason. */
    @Synchronized fun failed(): Map<String, String> = failed.toMap()

    @Synchronized fun all(): List<StoredBundle> = bundles.values.toList()

    @Synchronized fun byPrefix(prefix: ByteArray): List<StoredBundle> {
        val hex = prefix.sfHex()
        return bundles.values.filter { it.idHex.startsWith(hex) }
    }

    @Synchronized fun isSeen(idHex: String): Boolean = seen.containsKey(idHex)

    /** The tag of a seen ID this phone opened as its recipient, or null (finding 9). */
    @Synchronized fun deliveredTag(idHex: String): String? = if (seen.containsKey(idHex)) delivered[idHex] else null

    @Synchronized
    fun markDelivered(idHex: String, tagHex: String) {
        if (seen.containsKey(idHex) && delivered.put(idHex, tagHex) != tagHex) saveSeen()
    }

    @Synchronized fun seenByPrefix(prefix: ByteArray): String? {
        val hex = prefix.sfHex()
        return seen.keys.firstOrNull { it.startsWith(hex) }
    }

    /** Ciphertext bytes held or reserved per role (a receiving bundle reserves its full size). */
    @Synchronized fun usage(role: BundleRole): Long = bundles.values.filter { it.role == role }.sumOf { it.header.storedBytes }

    @Synchronized fun foreignUsageFrom(neighbor: String): Long =
        bundles.values.filter { it.role == BundleRole.FOREIGN && it.fromNeighbor == neighbor }.sumOf { it.header.storedBytes }

    @Synchronized fun foreignUsageForTag(tag: ByteArray): Long =
        bundles.values.filter { b -> b.role == BundleRole.FOREIGN && b.header.tags.any { it.contentEquals(tag) } }
            .sumOf { it.header.storedBytes }

    @Synchronized
    fun putOwn(
        sealed: SealedBundle,
        recipient: NodeId,
        limits: StoreForwardLimits,
        /** Messaging v2 raises the own-queue cap to 1 GiB; v1 keeps [StoreForwardLimits.ownQuotaBytes]. */
        quotaBytes: Long = limits.ownQuotaBytes,
    ): StoredBundle {
        require(usage(BundleRole.OWN) + sealed.header.storedBytes <= quotaBytes) {
            "Outgoing message storage is full"
        }
        val now = clock()
        val bundle = StoredBundle(
            sealed.header, BundleRole.OWN, BundleState.QUEUED, ChunkBitmap.full(sealed.header.chunkCount),
            now, now, null, recipient.toString(),
        )
        val dir = dirOf(bundle.idHex).apply { mkdirs() }
        // Header and chunks are durable before the meta (the commit point) marks it complete (N5).
        atomicWrite(File(dir, HEADER), sealed.header.encoded)
        openRandomAccess(File(dir, CHUNKS), "rw").use { file ->
            sealed.chunks.forEachIndexed { index, chunk ->
                file.seek(index.toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES)
                file.write(chunk)
            }
            file.fd.sync()
        }
        saveMeta(bundle)
        bundles[bundle.idHex] = bundle
        return bundle
    }

    /** Creates (or returns the existing partial) receiving entry for a validated header. */
    @Synchronized
    fun beginReceive(header: BundleHeader, role: BundleRole, fromNeighbor: String): StoredBundle {
        val idHex = header.bundleId.sfHex()
        bundles[idHex]?.let { return it }
        val now = clock()
        val bundle = StoredBundle(header, role, BundleState.RECEIVING, BitSet(header.chunkCount), now, now, fromNeighbor, null)
        val dir = dirOf(idHex).apply { mkdirs() }
        atomicWrite(File(dir, HEADER), header.encoded)
        saveMeta(bundle)
        bundles[idHex] = bundle
        return bundle
    }

    @Synchronized
    fun writeChunk(idHex: String, index: Int, ciphertext: ByteArray): ChunkWriteResult {
        val bundle = bundles[idHex] ?: return ChunkWriteResult.UNKNOWN_BUNDLE
        if (!bundle.header.verifyChunk(index, ciphertext)) return ChunkWriteResult.POISONED
        if (bundle.present.get(index)) return ChunkWriteResult.DUPLICATE
        openRandomAccess(File(dirOf(idHex), CHUNKS), "rw").use { file ->
            file.seek(index.toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES)
            file.write(ciphertext)
        }
        bundle.present.set(index)
        bundle.lastProgressMs = clock()
        // fsync + bitmap save in batches: a crash loses at most the unsynced chunks, which are
        // requested again (the saved bitmap never claims a chunk whose bytes were not synced).
        val pending = (unsynced[idHex] ?: 0) + 1
        unsynced[idHex] = pending
        if (pending >= SYNC_EVERY_CHUNKS || bundle.complete) saveMeta(bundle)
        return ChunkWriteResult.ACCEPTED
    }

    /** Persists every batched chunk (end of an exchange). */
    @Synchronized
    fun flushAll() {
        unsynced.keys.toList().forEach { id -> bundles[id]?.let(::saveMeta) ?: unsynced.remove(id) }
    }

    /**
     * A stored chunk failed its hash while being served (N5): an own bundle becomes FAILED (visible,
     * no longer offered); a carried or partial one is dropped. The exchange continues.
     */
    @Synchronized
    fun markCorrupt(idHex: String, reason: String) {
        val bundle = bundles[idHex] ?: return
        if (bundle.role == BundleRole.OWN) {
            bundle.state = BundleState.FAILED
            failed[idHex] = reason
            saveMeta(bundle)
        } else {
            remove(idHex, null)
        }
    }

    @Synchronized
    fun readChunk(idHex: String, index: Int): ByteArray? {
        val bundle = bundles[idHex] ?: return null
        if (!bundle.present.get(index)) return null
        return openRandomAccess(File(dirOf(idHex), CHUNKS), "r").use { file ->
            file.seek(index.toLong() * StoreForwardFormat.CHUNK_CIPHERTEXT_BYTES)
            ByteArray(bundle.header.chunkCiphertextBytes(index)).also(file::readFully)
        }
    }

    @Synchronized
    fun chunks(idHex: String): List<ByteArray> {
        val bundle = requireNotNull(bundles[idHex]) { "Unknown bundle" }
        return List(bundle.chunkCount) { requireNotNull(readChunk(idHex, it)) { "Chunk $it is missing" } }
    }

    @Synchronized
    fun update(idHex: String, state: BundleState, peerNode: String? = null) {
        val bundle = bundles[idHex] ?: return
        bundle.state = state
        if (peerNode != null) bundle.peerNode = peerNode
        saveMeta(bundle)
    }

    /** Deletes a bundle; [seenUntilHour] keeps its ID to block re-acceptance until then. */
    @Synchronized
    fun remove(idHex: String, seenUntilHour: Int?) {
        bundles.remove(idHex)
        unsynced.remove(idHex)
        failed.remove(idHex)
        dirOf(idHex).deleteRecursively()
        if (seenUntilHour != null) {
            seen[idHex] = maxOf(seenUntilHour, seen[idHex] ?: Int.MIN_VALUE)
            saveSeen()
        }
    }

    @Synchronized
    fun markSeen(idHex: String, untilHour: Int) {
        seen[idHex] = maxOf(untilHour, seen[idHex] ?: Int.MIN_VALUE)
        saveSeen()
    }

    /** Drops expired bundles (carrier clock) and expired seen IDs. Returns removed bundle IDs. */
    @Synchronized
    fun sweep(limits: StoreForwardLimits): List<String> {
        val nowHour = StoreForwardFormat.hourOf(clock())
        val expired = bundles.values.filter { nowHour >= it.storedUntilHour(limits.maxStoreHours) }.map { it.idHex }
        // An expired ID needs no seen entry: acceptance already refuses expired bundles.
        expired.forEach { id -> remove(id, null) }
        if (seen.values.removeAll { it <= nowHour }) {
            delivered.keys.retainAll(seen.keys)
            saveSeen()
        }
        // Damaged directories stay visible for the maximum lifetime, then go.
        failed.keys.filter { it !in bundles }.forEach { id ->
            val dir = dirOf(id)
            if (!dir.exists() || clock() - dir.lastModified() > limits.maxStoreHours * 3_600_000L) {
                dir.deleteRecursively()
                failed.remove(id)
            }
        }
        return expired
    }

    /**
     * Frees foreign space for [bytesNeeded]: stalled partials first (no progress for 24 h), then
     * complete foreign bundles by class 3 > 2 > 1 > 0, oldest first. Own and inbox data are never
     * evicted for foreign data. Returns false if the quota cannot be met.
     */
    @Synchronized
    fun makeRoomForForeign(bytesNeeded: Long, limits: StoreForwardLimits): Boolean {
        sweep(limits)
        val now = clock()
        fun used() = usage(BundleRole.FOREIGN)
        if (used() + bytesNeeded <= limits.foreignQuotaBytes) return true
        val candidates = bundles.values.filter { it.role == BundleRole.FOREIGN }.sortedWith(
            compareBy<StoredBundle>(
                { if (it.state == BundleState.RECEIVING && now - it.lastProgressMs >= limits.stalledPartialMs) 0 else 1 },
                { -it.header.priorityClass },
                { it.receivedAtMs },
            ),
        )
        for (candidate in candidates) {
            if (used() + bytesNeeded <= limits.foreignQuotaBytes) break
            remove(candidate.idHex, candidate.storedUntilHour(limits.maxStoreHours))
        }
        return used() + bytesNeeded <= limits.foreignQuotaBytes
    }

    private fun dirOf(idHex: String) = File(bundlesDir, idHex)

    private fun saveMeta(bundle: StoredBundle) {
        if ((unsynced.remove(bundle.idHex) ?: 0) > 0) {
            openRandomAccess(File(dirOf(bundle.idHex), CHUNKS), "rw").use { it.fd.sync() }
        }
        val properties = Properties().apply {
            setProperty("role", bundle.role.name)
            setProperty("state", bundle.state.name)
            setProperty("present", bundle.bitmap().sfHex())
            setProperty("receivedAtMs", bundle.receivedAtMs.toString())
            setProperty("lastProgressMs", bundle.lastProgressMs.toString())
            bundle.fromNeighbor?.let { setProperty("fromNeighbor", it) }
            bundle.peerNode?.let { setProperty("peerNode", it) }
        }
        writeSealedProperties(File(dirOf(bundle.idHex), META), cipher, properties)
    }

    private fun load(dir: File): StoredBundle {
        val header = BundleHeader.decode(File(dir, HEADER).readBytes(), allowV3 = true)
        require(header.bundleId.sfHex() == dir.name) { "Bundle directory does not match its header" }
        val meta = readSealedProperties(File(dir, META), cipher)
        return StoredBundle(
            header,
            BundleRole.valueOf(meta.getProperty("role")),
            BundleState.valueOf(meta.getProperty("state")),
            ChunkBitmap.decode(hexToBytes(meta.getProperty("present")), header.chunkCount),
            meta.getProperty("receivedAtMs").toLong(),
            meta.getProperty("lastProgressMs").toLong(),
            meta.getProperty("fromNeighbor"),
            meta.getProperty("peerNode"),
        )
    }

    private fun saveSeen() = writeSealedProperties(seenFile, cipher, Properties().apply {
        seen.forEach { (id, until) -> setProperty(id, until.toString()) }
        delivered.forEach { (id, tag) -> setProperty(DELIVERED_PREFIX + id, tag) }
    })

    private companion object {
        const val HEADER = "header.bin"
        const val CHUNKS = "chunks.bin"
        const val META = "meta.properties"
        const val DELIVERED_PREFIX = "delivered."
        const val SYNC_EVERY_CHUNKS = 16
    }
}

/** Per authenticated adjacent identity (and per sending contact) daily counters. */
class NeighborLedger(
    private val file: File,
    private val clock: () -> Long,
    private val cipher: AtRestCipher,
    private val maxRecords: Int = 512,
) {
    private val properties = readSealedProperties(file, cipher)

    init {
        prune()
    }

    /**
     * Responder-side exchange admission for one adjacent identity (N3): at least [cooldownMs]
     * between exchange starts and at most [maxPerHour] per hour. Records the start when admitted.
     */
    @Synchronized
    fun admitExchange(key: String, cooldownMs: Long, maxPerHour: Int): Boolean {
        val now = clock()
        val last = properties.getProperty("$key.lastExchangeMs")?.toLongOrNull()
        if (last != null && now - last in 0 until cooldownMs) return false
        val hour = StoreForwardFormat.hourOf(now)
        val count = if (properties.getProperty("$key.exchangeHour")?.toIntOrNull() == hour) {
            properties.getProperty("$key.exchangeCount")?.toIntOrNull() ?: 0
        } else 0
        if (count >= maxPerHour) return false
        // Remembered so an admitted exchange that moved nothing can give back the spacing ([refundCooldown]).
        properties.setProperty("$key.prevExchangeMs", last?.toString() ?: "")
        properties.setProperty("$key.lastExchangeMs", now.toString())
        properties.setProperty("$key.exchangeHour", hour.toString())
        properties.setProperty("$key.exchangeCount", (count + 1).toString())
        writeSealedProperties(file, cipher, properties)
        return true
    }

    /**
     * Gives back the spacing of the exchange just admitted for [key] ([admitExchange] remembered the previous start): an
     * exchange that was admitted, then lost its link before anything moved, must not make the neighbour's retry wait out the
     * cooldown (lane3-asus-a15s-m31-20261013: each failed A15s dial made the next one RATE_LIMITED 46 s later). The hourly count
     * is NOT refunded, so a neighbour that connects and vanishes is still bounded by the hourly cap. Idempotent.
     */
    @Synchronized
    fun refundCooldown(key: String) {
        val previous = properties.getProperty("$key.prevExchangeMs") ?: return
        if (previous.isEmpty()) properties.remove("$key.lastExchangeMs") else properties.setProperty("$key.lastExchangeMs", previous)
        properties.remove("$key.prevExchangeMs")
        writeSealedProperties(file, cipher, properties)
    }

    /**
     * Drops records idle for 30 days (no counters for today or yesterday) and keeps at most
     * [maxRecords] records, most recently active first (N4).
     */
    @Synchronized
    fun prune() {
        val today = currentDay()
        val now = clock()
        val byRecord = properties.stringPropertyNames().groupBy { it.substringBeforeLast('.') }
        fun activity(record: String): Long = listOfNotNull(
            properties.getProperty("$record.day")?.toLongOrNull()?.times(86_400_000L),
            properties.getProperty("$record.lastSyncMs")?.toLongOrNull(),
            properties.getProperty("$record.lastExchangeMs")?.toLongOrNull(),
            // The hour of the last admission: a record whose cooldown was refunded ([refundCooldown]) still has its hourly count.
            properties.getProperty("$record.exchangeHour")?.toLongOrNull()?.times(3_600_000L),
        ).maxOrNull() ?: 0L
        val stale = byRecord.keys.filter { record ->
            val day = properties.getProperty("$record.day")?.toIntOrNull()
            (day == null || day < today - 1) && now - activity(record) > IDLE_MS
        }.toMutableSet()
        val kept = byRecord.keys.filter { it !in stale }.sortedByDescending(::activity)
        stale += kept.drop(maxRecords)
        if (stale.isEmpty()) return
        stale.forEach { record -> byRecord[record].orEmpty().forEach(properties::remove) }
        writeSealedProperties(file, cipher, properties)
    }

    @Synchronized fun recordCount(): Int = properties.stringPropertyNames().map { it.substringBeforeLast('.') }.toSet().size

    data class Usage(val bytesIn: Long, val bundlesIn: Int, val bytesOut: Long)

    @Synchronized
    fun today(key: String): Usage {
        val day = currentDay()
        if (properties.getProperty("$key.day")?.toIntOrNull() != day) return Usage(0, 0, 0)
        return Usage(
            properties.getProperty("$key.bytesIn")?.toLong() ?: 0,
            properties.getProperty("$key.bundlesIn")?.toInt() ?: 0,
            properties.getProperty("$key.bytesOut")?.toLong() ?: 0,
        )
    }

    @Synchronized
    fun add(key: String, bytesIn: Long = 0, bundlesIn: Int = 0, bytesOut: Long = 0) {
        val current = today(key)
        properties.setProperty("$key.day", currentDay().toString())
        properties.setProperty("$key.bytesIn", (current.bytesIn + bytesIn).toString())
        properties.setProperty("$key.bundlesIn", (current.bundlesIn + bundlesIn).coerceAtLeast(0).toString())
        properties.setProperty("$key.bytesOut", (current.bytesOut + bytesOut).toString())
        writeSealedProperties(file, cipher, properties)
    }

    @Synchronized
    fun recordSync(neighbor: String, peerDigest: ByteArray) {
        properties.setProperty("$neighbor.lastSyncMs", clock().toString())
        properties.setProperty("$neighbor.peerDigest", peerDigest.sfHex())
        writeSealedProperties(file, cipher, properties)
    }

    @Synchronized fun lastSyncMs(neighbor: String): Long? = properties.getProperty("$neighbor.lastSyncMs")?.toLong()

    private fun currentDay() = StoreForwardFormat.dayOfHour(StoreForwardFormat.hourOf(clock()))

    companion object {
        private const val IDLE_MS = 30L * 24 * 3_600_000
        fun neighborKey(node: NodeId) = "neighbor.$node"
        fun senderKey(node: String) = "sender.$node"
    }
}

enum class ContactState { ACCEPTED, PENDING }

/** How an ACCEPTED contact got accepted. Only [VERIFIED] contacts follow the verified-contact store when it forgets them. */
enum class ContactSource {
    /** The user compared the safety number of a card (v1) or typed a card in. */
    MANUAL,
    /** Messaging v2: the card's identity was already verified in person, so no second comparison was needed (M1). */
    VERIFIED,
}

class StoreForwardContact(
    val card: ContactCard,
    val state: ContactState,
    val name: String,
    val addedMs: Long,
    val source: ContactSource = ContactSource.MANUAL,
    /**
     * Messaging v2 (M1): a newer signed card of this identity carries another mailbox key. Nothing is sent to the contact
     * (and nothing new is read from the new key) until the user compares the new safety number and accepts it.
     */
    val pendingKeyChange: ContactCard? = null,
) {
    val nodeId: NodeId get() = card.nodeId
    val label: String get() = name.ifBlank { card.nodeId.shortLabel() }
    val keysChanged: Boolean get() = pendingKeyChange != null
}

enum class CardOffer {
    ADDED_PENDING,
    ALREADY_KNOWN,
    KEY_CHANGED_IGNORED,
    IDENTITY_MISMATCH,
    /** The card's mailbox key already belongs to another held card, pending or accepted (N2). */
    MAILBOX_IN_USE,
    /** Messaging v2: the card's identity is verified in person, so it was accepted without a second comparison (M1). */
    AUTO_ACCEPTED,
    /** Messaging v2: an accepted contact's newer card carries another mailbox key; held back until the user compares again (M1). */
    KEY_CHANGED,
    /** Messaging v2: a card with another mailbox key that is not newer than the stored one (a replayed old card): ignored. */
    KEY_CHANGED_STALE,
}

/**
 * Messaging v2 (M1, one contact system): the verified-contact store is the source of truth for who the user has compared in
 * person. The mailbox card only carries the key material a message needs. Implemented on Android over [VerifiedContactStore].
 */
interface VerifiedIdentityDirectory {
    /** The label of the verified contact that owns [identityKey] (a DER SubjectPublicKeyInfo), "" if unnamed; null if not verified. */
    fun verifiedLabel(identityKey: ByteArray): String?

    /** Whether a verified contact has exactly this node ID. */
    fun isVerifiedNode(node: NodeId): Boolean

    /** False while the verified store cannot be read: nothing is demoted or removed because of it. */
    val readable: Boolean
        get() = true
}

/**
 * Contacts (QA2: messages only from contacts). Cards received over an authenticated link stay
 * PENDING until the user accepts them; a different mailbox key for a known identity is ignored
 * (key-change handling and safety numbers are PLAN "Discovery privacy" work).
 */
class ContactBook(
    private val file: File,
    private val clock: () -> Long,
    private val maxPending: Int = 20,
    private val cipher: AtRestCipher,
) {
    private val contacts = LinkedHashMap<NodeId, StoreForwardContact>()

    init {
        val properties = readSealedProperties(file, cipher)
        properties.stringPropertyNames().filter { it.endsWith(".card") }.forEach { key ->
            val node = key.removeSuffix(".card")
            runCatching {
                val card = ContactCard.fromText(properties.getProperty(key))
                require(card.nodeId.toString() == node)
                contacts[card.nodeId] = StoreForwardContact(
                    card,
                    ContactState.valueOf(properties.getProperty("$node.state", ContactState.PENDING.name)),
                    properties.getProperty("$node.name", ""),
                    properties.getProperty("$node.addedMs", "0").toLong(),
                    runCatching { ContactSource.valueOf(properties.getProperty("$node.source", ContactSource.MANUAL.name)) }
                        .getOrDefault(ContactSource.MANUAL),
                    properties.getProperty("$node.pendingCard")?.let { text ->
                        runCatching { ContactCard.fromText(text) }.getOrNull()?.takeIf { it.nodeId == card.nodeId }
                    },
                )
            }
        }
    }

    /** Changes whenever the set of accepted cards or any card changes; a running exchange rebuilds its tag table when it moves. */
    @Volatile var generation: Int = 0
        private set

    @Synchronized fun all(): List<StoreForwardContact> = contacts.values.toList()
    @Synchronized fun get(node: NodeId): StoreForwardContact? = contacts[node]
    @Synchronized fun accepted(): List<StoreForwardContact> = contacts.values.filter { it.state == ContactState.ACCEPTED }

    /** Messaging v2: the newer card of [node] whose other mailbox key waits for the user's comparison, or null. */
    @Synchronized fun pendingKeyChange(node: NodeId): ContactCard? = contacts[node]?.pendingKeyChange

    /**
     * Sender resolution by authenticated identity: true only if an ACCEPTED card held for [node]
     * carries exactly [mailbox]. Pending cards never authorize storage (N1), and a second card that
     * reuses someone's mailbox key cannot shadow them.
     */
    @Synchronized
    fun isKnownSender(node: NodeId, mailbox: ByteArray): Boolean = contacts[node]?.let {
        it.state == ContactState.ACCEPTED && it.card.mailboxPublic.contentEquals(mailbox)
    } == true

    private fun mailboxHeldByOtherAccepted(card: ContactCard): Boolean = contacts.values.any {
        it.state == ContactState.ACCEPTED && it.nodeId != card.nodeId && it.card.mailboxPublic.contentEquals(card.mailboxPublic)
    }

    private fun mailboxHeldByOther(card: ContactCard): Boolean = contacts.values.any {
        it.nodeId != card.nodeId && it.card.mailboxPublic.contentEquals(card.mailboxPublic)
    }

    /** A user-confirmed card wins over pending cards that copied its mailbox key. */
    private fun dropPendingDuplicates(card: ContactCard) {
        contacts.values.filter {
            it.state == ContactState.PENDING && it.nodeId != card.nodeId && it.card.mailboxPublic.contentEquals(card.mailboxPublic)
        }.forEach { contacts.remove(it.nodeId) }
    }

    /** Drops link-received cards nobody accepted within [maxAgeMs]; returns their node IDs (N1). */
    @Synchronized
    fun prunePending(maxAgeMs: Long): List<NodeId> {
        val now = clock()
        val stale = contacts.values.filter { it.state == ContactState.PENDING && now - it.addedMs > maxAgeMs }.map { it.nodeId }
        if (stale.isNotEmpty()) {
            stale.forEach(contacts::remove)
            save()
        }
        return stale
    }

    /** Adds a card the user entered (QR text): accepted immediately. */
    @Synchronized
    fun addAccepted(card: ContactCard, name: String): StoreForwardContact {
        val existing = contacts[card.nodeId]
        require(existing == null || existing.card.mailboxPublic.contentEquals(card.mailboxPublic)) {
            "This contact already has a different mailbox key"
        }
        require(!mailboxHeldByOtherAccepted(card)) { "Another contact already uses this card's mailbox key" }
        dropPendingDuplicates(card)
        return StoreForwardContact(card, ContactState.ACCEPTED, name.ifBlank { existing?.name.orEmpty() },
            existing?.addedMs ?: clock(), existing?.source ?: ContactSource.MANUAL, existing?.pendingKeyChange)
            .also { contacts[card.nodeId] = it; changed() }
    }

    /** A card received from the authenticated adjacent identity [linkPeer]. */
    @Synchronized
    fun offerFromLink(card: ContactCard, linkPeer: NodeId): CardOffer {
        if (card.nodeId != linkPeer) return CardOffer.IDENTITY_MISMATCH
        val existing = contacts[card.nodeId]
        if (existing != null) {
            return if (existing.card.mailboxPublic.contentEquals(card.mailboxPublic)) CardOffer.ALREADY_KNOWN
            else CardOffer.KEY_CHANGED_IGNORED
        }
        if (mailboxHeldByOther(card)) return CardOffer.MAILBOX_IN_USE
        contacts[card.nodeId] = StoreForwardContact(card, ContactState.PENDING, "", clock())
        val pending = contacts.values.filter { it.state == ContactState.PENDING }.sortedBy { it.addedMs }
        pending.take((pending.size - maxPending).coerceAtLeast(0)).forEach { contacts.remove(it.nodeId) }
        save()
        return CardOffer.ADDED_PENDING
    }

    @Synchronized
    fun accept(node: NodeId, name: String) {
        val existing = contacts[node] ?: return
        require(!mailboxHeldByOtherAccepted(existing.card)) { "Another contact already uses this card's mailbox key" }
        dropPendingDuplicates(existing.card)
        contacts[node] = StoreForwardContact(existing.card, ContactState.ACCEPTED, name.ifBlank { existing.name }, existing.addedMs,
            ContactSource.MANUAL, existing.pendingKeyChange)
        changed()
    }

    @Synchronized
    fun remove(node: NodeId): Boolean {
        val removed = contacts.remove(node) != null
        if (removed) changed()
        return removed
    }

    /**
     * Messaging v2 (M1): a card received over the authenticated link of [linkPeer]. The verified-contact store decides
     * trust: a card whose identity [verified] lists is accepted at once (no second comparison); any other card stays pending
     * behind the safety number. An accepted contact's card with another mailbox key is held as a pending key change when it
     * is newer than the stored card (a replayed older signed card cannot roll a key back) and nothing is sent until the
     * user accepts it ([acceptKeyChange]).
     */
    @Synchronized
    fun offerFromLinkV2(card: ContactCard, linkPeer: NodeId, verified: VerifiedIdentityDirectory?): CardOffer {
        if (card.nodeId != linkPeer) return CardOffer.IDENTITY_MISMATCH
        val label = verified?.verifiedLabel(card.identityPublicKey)
        val existing = contacts[card.nodeId]
        if (existing != null) {
            if (existing.card.mailboxPublic.contentEquals(card.mailboxPublic)) {
                // Same keys: a newer card only refreshes the stored creation time (the freshness anchor).
                val newer = card.createdMs > existing.card.createdMs
                if (existing.state == ContactState.PENDING && label != null) {
                    if (mailboxHeldByOtherAccepted(existing.card)) return CardOffer.MAILBOX_IN_USE
                    dropPendingDuplicates(existing.card)
                    contacts[card.nodeId] = StoreForwardContact(if (newer) card else existing.card, ContactState.ACCEPTED,
                        label.ifBlank { existing.name }, existing.addedMs, ContactSource.VERIFIED, null)
                    changed()
                    return CardOffer.AUTO_ACCEPTED
                }
                if (newer) {
                    contacts[card.nodeId] = StoreForwardContact(card, existing.state, existing.name, existing.addedMs, existing.source,
                        existing.pendingKeyChange)
                    save()
                }
                return CardOffer.ALREADY_KNOWN
            }
            if (existing.state != ContactState.ACCEPTED) return CardOffer.KEY_CHANGED_IGNORED
            if (card.createdMs <= existing.card.createdMs) return CardOffer.KEY_CHANGED_STALE
            if (mailboxHeldByOther(card)) return CardOffer.MAILBOX_IN_USE
            val held = existing.pendingKeyChange
            if (held != null && held.createdMs >= card.createdMs) return CardOffer.KEY_CHANGED
            contacts[card.nodeId] = StoreForwardContact(existing.card, existing.state, existing.name, existing.addedMs, existing.source, card)
            changed()
            return CardOffer.KEY_CHANGED
        }
        if (mailboxHeldByOther(card)) return CardOffer.MAILBOX_IN_USE
        if (label != null) {
            contacts[card.nodeId] = StoreForwardContact(card, ContactState.ACCEPTED, label, clock(), ContactSource.VERIFIED, null)
            changed()
            return CardOffer.AUTO_ACCEPTED
        }
        contacts[card.nodeId] = StoreForwardContact(card, ContactState.PENDING, "", clock())
        val pending = contacts.values.filter { it.state == ContactState.PENDING }.sortedBy { it.addedMs }
        pending.take((pending.size - maxPending).coerceAtLeast(0)).forEach { contacts.remove(it.nodeId) }
        changed()
        return CardOffer.ADDED_PENDING
    }

    /**
     * The user compared the safety number of [node]'s newer card and accepts the new mailbox key: it replaces the stored card
     * (name and source stay). Returns the replaced card, or null when no key change was pending.
     */
    @Synchronized
    fun acceptKeyChange(node: NodeId): ContactCard? {
        val existing = contacts[node] ?: return null
        val replacement = existing.pendingKeyChange ?: return null
        if (mailboxHeldByOtherAccepted(replacement)) return null
        contacts[node] = StoreForwardContact(replacement, existing.state, existing.name, existing.addedMs, existing.source, null)
        changed()
        return existing.card
    }

    /** The user keeps the old key: the held card is dropped (the next meeting offers it again). */
    @Synchronized
    fun dismissKeyChange(node: NodeId): Boolean {
        val existing = contacts[node] ?: return false
        if (existing.pendingKeyChange == null) return false
        contacts[node] = StoreForwardContact(existing.card, existing.state, existing.name, existing.addedMs, existing.source, null)
        changed()
        return true
    }

    /** What [reconcileVerified] changed. */
    class Reconciled(val promoted: List<NodeId>, val demoted: List<NodeId>)

    /**
     * Follows the verified-contact store (M1): a PENDING card whose identity became verified is accepted, and a contact that was
     * auto-accepted because it was verified is removed when the user forgot the verification. Contacts the user accepted by
     * hand are never touched. Nothing is removed while the store is unreadable.
     */
    @Synchronized
    fun reconcileVerified(verified: VerifiedIdentityDirectory): Reconciled {
        val promoted = ArrayList<NodeId>()
        val demoted = ArrayList<NodeId>()
        for (contact in contacts.values.toList()) {
            val label = verified.verifiedLabel(contact.card.identityPublicKey)
            if (contact.state == ContactState.PENDING && label != null && !mailboxHeldByOtherAccepted(contact.card)) {
                dropPendingDuplicates(contact.card)
                contacts[contact.nodeId] = StoreForwardContact(contact.card, ContactState.ACCEPTED, label.ifBlank { contact.name },
                    contact.addedMs, ContactSource.VERIFIED, contact.pendingKeyChange)
                promoted += contact.nodeId
            } else if (contact.state == ContactState.ACCEPTED && contact.source == ContactSource.VERIFIED && label == null && verified.readable) {
                contacts.remove(contact.nodeId)
                demoted += contact.nodeId
            }
        }
        if (promoted.isNotEmpty() || demoted.isNotEmpty()) changed()
        return Reconciled(promoted, demoted)
    }

    private fun changed() {
        generation++
        save()
    }

    private fun save() = writeSealedProperties(file, cipher, Properties().apply {
        contacts.values.forEach { contact ->
            val node = contact.nodeId.toString()
            setProperty("$node.card", contact.card.toText())
            setProperty("$node.state", contact.state.name)
            setProperty("$node.name", contact.name)
            setProperty("$node.addedMs", contact.addedMs.toString())
            if (contact.source != ContactSource.MANUAL) setProperty("$node.source", contact.source.name)
            contact.pendingKeyChange?.let { setProperty("$node.pendingCard", it.toText()) }
        }
    })
}

/** Encrypts user content at rest (Android: a non-exportable Keystore AES-256-GCM key). */
interface AtRestCipher {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a provider-generated IV, as Android Keystore requires for randomized encryption. */
class AesGcmAtRestCipher(private val keyProvider: () -> SecretKey) : AtRestCipher {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
        val iv = cipher.iv
        require(iv.size == 12) { "Unexpected IV size" }
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > 12 + 16) { "Sealed data is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, sealed.copyOf(12)))
        }
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

enum class MessageDirection { IN, OUT }

class ArchivedMessage(
    val bundleIdHex: String,
    val direction: MessageDirection,
    val peerNode: String,
    val receivedMs: Long,
    /** The bundle's expiry hour: the opened message is deleted then too (finding 6). */
    val expiryHour: Int,
    val message: BundleMessage,
)

/** Small encrypted listing record; the body is decrypted only when opened (paging, finding 6). */
class ArchivedSummary(
    val bundleIdHex: String,
    val direction: MessageDirection,
    val peerNode: String,
    val receivedMs: Long,
    val createdMs: Long,
    val kind: MessageKind,
    val mime: String,
    val bodyBytes: Int,
    val expiryHour: Int,
    /** First characters of a text message (empty for media). */
    val preview: String,
) {
    /** Bytes this message occupies on disk (body plus encryption and listing overhead). */
    val storedBytes: Long get() = bodyBytes.toLong() + BundleMessage.OVERHEAD_BYTES + 256
}

/**
 * Decrypted user content, encrypted at rest and kept separate from carrier storage. Each message is
 * `<id>.body` (sealed BSM1) plus `<id>.meta` (sealed listing record, written last as the commit
 * point, both fsynced). Messages expire with their bundle's lifetime and count against a quota;
 * listings decrypt only the small metas and bodies are loaded one at a time.
 */
class MessageArchive(private val dir: File, private val cipher: AtRestCipher) {
    private var index: MutableMap<String, ArchivedSummary>? = null

    init {
        dir.mkdirs()
        // Messages from the v1 prototype format (single .msg file) are not migrated.
        dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /** Durably stores [entry]; throws (and stores nothing) if it would exceed [quotaBytes]. */
    @Synchronized
    fun save(entry: ArchivedMessage, quotaBytes: Long) {
        val summary = ArchivedSummary(
            entry.bundleIdHex, entry.direction, entry.peerNode, entry.receivedMs, entry.message.createdMs,
            entry.message.kind, entry.message.mime, entry.message.body.size, entry.expiryHour,
            if (entry.message.kind == MessageKind.TEXT) String(entry.message.body, Charsets.UTF_8).take(PREVIEW_CHARS) else "",
        )
        val existing = loadedIndex()[entry.bundleIdHex]
        require(bytes() - (existing?.storedBytes ?: 0) + summary.storedBytes <= quotaBytes) { "Message storage is full" }
        WipeGuard.guardedWrite("message archive") {
            atomicWrite(bodyFile(entry.bundleIdHex), cipher.seal(entry.message.encode()))
            atomicWrite(metaFile(entry.bundleIdHex), cipher.seal(encodeSummary(summary)))
        }
        loadedIndex()[entry.bundleIdHex] = summary
    }

    /** Newest first; decrypts only listing records. */
    @Synchronized
    fun page(offset: Int, limit: Int, direction: MessageDirection? = null): List<ArchivedSummary> =
        loadedIndex().values.filter { direction == null || it.direction == direction }
            .sortedWith(compareByDescending<ArchivedSummary> { it.receivedMs }.thenByDescending { it.createdMs })
            .drop(offset).take(limit)

    @Synchronized fun count(direction: MessageDirection? = null): Int =
        loadedIndex().values.count { direction == null || it.direction == direction }

    @Synchronized
    fun load(bundleIdHex: String): ArchivedMessage? {
        val summary = loadedIndex()[bundleIdHex] ?: return null
        val message = runCatching { BundleMessage.decode(cipher.open(bodyFile(bundleIdHex).readBytes())) }.getOrNull() ?: return null
        return ArchivedMessage(bundleIdHex, summary.direction, summary.peerNode, summary.receivedMs, summary.expiryHour, message)
    }

    @Synchronized fun contains(bundleIdHex: String) = loadedIndex().containsKey(bundleIdHex)

    @Synchronized
    fun delete(bundleIdHex: String): Boolean {
        val removed = loadedIndex().remove(bundleIdHex) != null
        metaFile(bundleIdHex).delete()
        bodyFile(bundleIdHex).delete()
        return removed
    }

    @Synchronized fun bytes(direction: MessageDirection? = null): Long =
        loadedIndex().values.filter { direction == null || it.direction == direction }.sumOf { it.storedBytes }

    /** Deletes messages whose lifetime ended. Returns their IDs. */
    @Synchronized
    fun sweep(nowHour: Int): List<String> {
        val expired = loadedIndex().values.filter { nowHour >= it.expiryHour }.map { it.bundleIdHex }
        expired.forEach(::delete)
        return expired
    }

    private fun loadedIndex(): MutableMap<String, ArchivedSummary> = index ?: HashMap<String, ArchivedSummary>().also { map ->
        dir.listFiles { file -> file.name.endsWith(".meta") }.orEmpty().forEach { file ->
            val id = file.name.removeSuffix(".meta")
            val summary = runCatching { decodeSummary(cipher.open(file.readBytes())) }.getOrNull()
            if (summary != null && summary.bundleIdHex == id && bodyFile(id).exists()) map[id] = summary
            else {
                file.delete()
                bodyFile(id).delete()
            }
        }
        // A body without its meta is an interrupted save: the meta is the commit point.
        dir.listFiles { file -> file.name.endsWith(".body") }.orEmpty()
            .filter { it.name.removeSuffix(".body") !in map }.forEach { it.delete() }
        index = map
    }

    private fun bodyFile(id: String) = File(dir, "$id.body")
    private fun metaFile(id: String) = File(dir, "$id.meta")

    private fun encodeSummary(summary: ArchivedSummary): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeByte(2)
            writeUTF(summary.bundleIdHex)
            writeByte(summary.direction.ordinal)
            writeUTF(summary.peerNode)
            writeLong(summary.receivedMs)
            writeLong(summary.createdMs)
            writeByte(summary.kind.wireValue)
            writeUTF(summary.mime)
            writeInt(summary.bodyBytes)
            writeInt(summary.expiryHour)
            writeUTF(summary.preview)
        }
    }.toByteArray()

    private fun decodeSummary(bytes: ByteArray): ArchivedSummary {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readUnsignedByte() == 2)
        return ArchivedSummary(
            input.readUTF(),
            MessageDirection.entries[input.readUnsignedByte()],
            input.readUTF(),
            input.readLong(),
            input.readLong(),
            MessageKind.fromWire(input.readUnsignedByte()),
            input.readUTF(),
            input.readInt(),
            input.readInt(),
            input.readUTF(),
        )
    }

    private companion object {
        const val PREVIEW_CHARS = 280
    }
}

/**
 * Decrypted media must not linger in files (finding 8). Playback streams from memory through
 * [readAt]; the only file is the user's own voice-note recording while it is captured, and any
 * leftover from a crash is deleted when Messages starts, pauses or closes.
 */
object StoreForwardPlaintextFiles {
    const val RECORDING = "sf-voice-note.m4a"
    /** Written by the v1 prototype's playback; removed if still present. */
    const val LEGACY_PLAYBACK = "sf-play.m4a"

    fun cleanLeftovers(cacheDir: File): Int =
        listOf(RECORDING, LEGACY_PLAYBACK).count { File(cacheDir, it).let { file -> file.exists() && file.delete() } }

    /** MediaDataSource.readAt over an in-memory buffer: -1 at or past the end. */
    fun readAt(source: ByteArray, position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= source.size) return -1
        val count = minOf(size.toLong(), source.size - position).toInt()
        System.arraycopy(source, position.toInt(), buffer, offset, count)
        return count
    }
}

/**
 * Metadata files (contacts, neighbour ledger, bundle meta, seen IDs) are sealed with the same
 * Keystore AES key as messages (N4). A prototype plaintext file (Properties text starts with '#')
 * is read once and sealed on its next write.
 */
internal fun readSealedProperties(file: File, cipher: AtRestCipher): Properties {
    if (!file.exists()) return Properties()
    val bytes = file.readBytes()
    if (bytes.isEmpty()) return Properties()
    val plain = try {
        cipher.open(bytes)
    } catch (error: Exception) {
        if (bytes.isNotEmpty() && bytes[0] == '#'.code.toByte()) bytes else throw error
    }
    return Properties().apply { load(ByteArrayInputStream(plain)) }
}

/** Every sealed properties store (Messaging v2, store-and-forward) writes here; the wipe waits for a running write. */
internal fun writeSealedProperties(file: File, cipher: AtRestCipher, properties: Properties) {
    val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
    WipeGuard.guardedWrite("sealed ${file.name}") { atomicWrite(file, cipher.seal(bytes)) }
}

internal fun readProperties(file: File): Properties = Properties().apply {
    if (file.exists()) file.inputStream().use(::load)
}

internal fun writeProperties(file: File, properties: Properties) {
    val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
    atomicWrite(file, bytes)
}

internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.outputStream().use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    // A replace can briefly fail while another process (a host virus scanner in JVM tests) holds
    // the target; retry before giving up.
    var attempt = 0
    while (true) {
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (error: java.io.IOException) {
            if (++attempt >= HOST_LOCK_RETRIES) throw error
            try {
                Thread.sleep(HOST_LOCK_RETRY_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
    }
}

private const val HOST_LOCK_RETRIES = 100
private const val HOST_LOCK_RETRY_MS = 10L

/**
 * [RandomAccessFile] that waits out a lock another process holds on the file for a moment. On a Windows host a virus scanner or
 * indexer that has just seen a closed file can keep it open for a few milliseconds, and the next open of a chunk file (one per
 * chunk) then fails with "being used by another process"; that surfaced in JVM tests as a stored-message exchange ending
 * INTERRUPTED with that text as its detail. A missing file, or any other failure, is thrown at once, and no phone reports this
 * error, so nothing on Android changes. Waits at most about a second.
 */
internal fun openRandomAccess(file: File, mode: String): RandomAccessFile {
    var attempt = 0
    while (true) {
        try {
            return RandomAccessFile(file, mode)
        } catch (error: java.io.FileNotFoundException) {
            if (!file.exists() || error.message?.contains("another process") != true || ++attempt >= HOST_LOCK_RETRIES) throw error
            try {
                Thread.sleep(HOST_LOCK_RETRY_MS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            }
        }
    }
}
