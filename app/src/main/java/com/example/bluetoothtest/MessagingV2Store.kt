package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.BitSet
import java.util.Properties

/** State of the file part of a conversation message. */
enum class FileState {
    /** The file is in the vault (inline attachments, and files this phone sent). */
    STORED,
    /** A segmented file whose manifest arrived: waiting for the person (or the automatic limit) to approve the download. */
    AWAITING_APPROVAL,
    /** Approved: segments are pulled from the sender whenever the phones meet. */
    DOWNLOADING,
    /** Every segment received and the whole-file SHA-256 matched. */
    COMPLETE,
    /** The whole-file hash did not match: deleted, "Damaged, ask again". */
    DAMAGED,
    /** The sender no longer has the file ("No longer available"). */
    UNAVAILABLE,
}

/** File fields of a [ThreadMessage]. The file key is deliberately not here (separate transfer record). */
class AttachmentInfo(
    val fileIdHex: String,
    val presentation: Presentation,
    val totalSize: Long,
    val sha256: ByteArray,
    val name: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** A JPEG of at most 16 KiB. Carried and never decoded unless `test_mv2_auto_thumbnails` allows it. */
    val thumbnail: ByteArray,
    val segmentCount: Int,
    @Volatile var state: FileState,
    /** Local random vault ID, never the sender's file ID; null until a vault file exists. */
    @Volatile var vaultId: String?,
)

/**
 * One message of a conversation (both directions), the v2 counterpart of [ArchivedMessage]. Text and manifests only: file
 * bytes live in the [FileVault]. Retention is independent of the bundle's delivery lifetime: a message stays until the
 * user deletes it or its disappearing timer runs out.
 */
class ThreadMessage(
    val direction: MessageDirection,
    val peerNode: String,
    val messageIdHex: String,
    val kind: V2Kind,
    val createdMs: Long,
    val arrivalMs: Long,
    val flags: Int,
    val body: ByteArray,
    val attachment: AttachmentInfo?,
    val disappearSeconds: Int,
    val replyToHex: String?,
    /** The sealed bundle(s) carrying this message: the one received, or every one this phone sealed for it. */
    val bundleIds: MutableList<String>,
    @Volatile var deliveredAtMs: Long = 0,
    @Volatile var downloadedAtMs: Long = 0,
    @Volatile var readAtMs: Long = 0,
    /** Own message that will not be delivered (lifetime over, damaged, too many attempts). A late receipt still sets its flag. */
    @Volatile var notSent: Boolean = false,
    /** Wall-clock time the disappearing timer runs out; 0 = not running. */
    @Volatile var disappearAtMs: Long = 0,
    var timerRaised: Boolean = false,
) {
    val id: String get() = (if (direction == MessageDirection.IN) "in-" else "out-") + peerNode.take(16) + "-" + messageIdHex
    val text: String get() = String(body, Charsets.UTF_8)

    /** Sender time clamped to `[arrival - 14 days, arrival]` (M5): a wrong clock cannot place a message out of its window. */
    val displayMs: Long get() = createdMs.coerceIn(arrivalMs - 14L * 24 * 3_600_000, arrivalMs)

    val delivered: Boolean get() = deliveredAtMs > 0
    val downloaded: Boolean get() = downloadedAtMs > 0
    val read: Boolean get() = readAtMs > 0 && direction == MessageDirection.OUT
}

/**
 * Encrypted store of v2 conversation messages: one sealed record per message, written whole and atomically, an index
 * kept in memory. The message count and total size are capped (M12a: 2,000 rows).
 */
class ThreadStore(private val dir: File, private val cipher: AtRestCipher, private val maxRows: Int, private val maxBytes: Long) {
    private val messages = LinkedHashMap<String, ThreadMessage>()
    private val sizes = HashMap<String, Int>()

    init {
        dir.mkdirs()
        dir.listFiles { file -> file.name.endsWith(".tmp") }?.forEach { it.delete() }
        dir.listFiles { file -> file.name.endsWith(".rec") }?.forEach { file ->
            val message = runCatching { decode(cipher.open(file.readBytes())) }.getOrNull()
            if (message != null && "${message.id}.rec" == file.name) {
                messages[message.id] = message
                sizes[message.id] = file.length().toInt()
            } else {
                file.delete()
            }
        }
    }

    @Synchronized fun count(): Int = messages.size

    @Synchronized fun bytes(): Long = sizes.values.sumOf { it.toLong() }

    @Synchronized fun get(id: String): ThreadMessage? = messages[id]

    @Synchronized fun all(): List<ThreadMessage> = messages.values.toList()

    @Synchronized
    fun find(direction: MessageDirection, peerNode: String, messageIdHex: String): ThreadMessage? =
        messages.values.firstOrNull { it.direction == direction && it.peerNode == peerNode && it.messageIdHex == messageIdHex }

    @Synchronized
    fun findByBundle(bundleIdHex: String): ThreadMessage? = messages.values.firstOrNull { bundleIdHex in it.bundleIds }

    @Synchronized
    fun forPeer(peerNode: String): List<ThreadMessage> =
        messages.values.filter { it.peerNode == peerNode }.sortedWith(compareBy({ it.displayMs }, { it.messageIdHex }))

    @Synchronized
    fun waitingFor(peerNode: String): Int = messages.values.count {
        it.direction == MessageDirection.OUT && it.peerNode == peerNode && !it.delivered && !it.notSent
    }

    /** Stores or replaces [message]. Throws (and stores nothing) when a new message would exceed the row or byte cap. */
    @Synchronized
    fun save(message: ThreadMessage) {
        val sealed = WipeGuard.guardedWrite("thread message") {
            val record = cipher.seal(encode(message))
            val existing = messages.containsKey(message.id)
            require(existing || messages.size < maxRows) { "Conversation storage is full (rows)" }
            require(bytes() - (sizes[message.id] ?: 0) + record.size <= maxBytes) { "Conversation storage is full" }
            atomicWrite(File(dir, "${message.id}.rec"), record)
            record
        }
        messages[message.id] = message
        sizes[message.id] = sealed.size
    }

    @Synchronized
    fun delete(id: String): ThreadMessage? {
        val removed = messages.remove(id) ?: return null
        sizes.remove(id)
        File(dir, "$id.rec").delete()
        return removed
    }

    /** Messages whose disappearing timer ran out; the caller deletes their files too. */
    @Synchronized
    fun expired(nowMs: Long): List<ThreadMessage> = messages.values.filter { it.disappearAtMs in 1..nowMs }

    private fun encode(m: ThreadMessage): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeByte(1)
            writeByte(m.direction.ordinal)
            writeUTF(m.peerNode)
            writeUTF(m.messageIdHex)
            writeByte(m.kind.wireValue)
            writeLong(m.createdMs)
            writeLong(m.arrivalMs)
            writeByte(m.flags)
            writeInt(m.body.size)
            write(m.body)
            writeInt(m.disappearSeconds)
            writeUTF(m.replyToHex.orEmpty())
            writeByte(m.bundleIds.size)
            m.bundleIds.forEach(::writeUTF)
            writeLong(m.deliveredAtMs)
            writeLong(m.downloadedAtMs)
            writeLong(m.readAtMs)
            writeBoolean(m.notSent)
            writeLong(m.disappearAtMs)
            writeBoolean(m.timerRaised)
            val a = m.attachment
            writeBoolean(a != null)
            if (a != null) {
                writeUTF(a.fileIdHex)
                writeByte(a.presentation.wireValue)
                writeLong(a.totalSize)
                write(a.sha256)
                writeUTF(a.name)
                writeUTF(a.mime)
                writeInt(a.width)
                writeInt(a.height)
                writeLong(a.durationMs)
                writeInt(a.thumbnail.size)
                write(a.thumbnail)
                writeInt(a.segmentCount)
                writeByte(a.state.ordinal)
                writeUTF(a.vaultId.orEmpty())
            }
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): ThreadMessage {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readUnsignedByte() == 1) { "Unsupported thread record" }
        val direction = MessageDirection.entries[input.readUnsignedByte()]
        val peer = input.readUTF()
        val messageId = input.readUTF()
        val kind = V2Kind.fromWire(input.readUnsignedByte())
        val created = input.readLong()
        val arrival = input.readLong()
        val flags = input.readUnsignedByte()
        val bodySize = input.readInt()
        require(bodySize in 0..MessagingV2Format.TEXT_MAX_BYTES * 2)
        val body = ByteArray(bodySize).also(input::readFully)
        val disappear = input.readInt()
        val reply = input.readUTF().ifEmpty { null }
        val bundles = MutableList(input.readUnsignedByte()) { input.readUTF() }
        val delivered = input.readLong()
        val downloaded = input.readLong()
        val read = input.readLong()
        val notSent = input.readBoolean()
        val disappearAt = input.readLong()
        val raised = input.readBoolean()
        val attachment = if (input.readBoolean()) {
            val fileId = input.readUTF()
            val presentation = Presentation.fromWire(input.readUnsignedByte())
            val total = input.readLong()
            val sha = ByteArray(32).also(input::readFully)
            val name = input.readUTF()
            val mime = input.readUTF()
            val width = input.readInt()
            val height = input.readInt()
            val duration = input.readLong()
            val thumbnail = ByteArray(input.readInt().also { require(it in 0..MessagingV2Format.THUMBNAIL_MAX_BYTES) }).also(input::readFully)
            val segments = input.readInt()
            val state = FileState.entries[input.readUnsignedByte()]
            val vault = input.readUTF().ifEmpty { null }
            AttachmentInfo(fileId, presentation, total, sha, name, mime, width, height, duration, thumbnail, segments, state, vault)
        } else null
        return ThreadMessage(
            direction, peer, messageId, kind, created, arrival, flags, body, attachment, disappear, reply, bundles,
            delivered, downloaded, read, notSent, disappearAt, raised,
        )
    }
}

enum class TransferState {
    /** In: manifest accepted, no download approved. Out: unused. */
    AWAITING_APPROVAL,
    /** In: approved, segments are pulled. Out: sealed segments wait for the receiver to pull them. */
    ACTIVE,
    /** In: complete and verified. Out: the receiver reported `downloaded` (status 3). */
    DONE,
    DAMAGED,
    UNAVAILABLE,
    ABANDONED,
}

/**
 * One file in flight, either direction. The file key is kept in [TransferKeys], never here, and erased as soon as the
 * file is complete and verified, abandoned, withdrawn or expired (design finding 14).
 */
class TransferRecord(
    val id: String,
    val direction: MessageDirection,
    val peer: String,
    var messageIdHex: String,
    val fileIdHex: String,
    var manifestBundleHex: String,
    val totalSize: Long,
    var sha256: ByteArray,
    /** Bundle IDs of the segments (empty strings until an outgoing segment is sealed). */
    val segmentIds: MutableList<String>,
    val segmentChunks: MutableList<Int>,
    var expiryHour: Int,
    var state: TransferState,
    val doneSegments: BitSet,
    var vaultId: String?,
    /** Out only: segments sealed so far. A segment index is sealed once per file key (nonce = chunk index). */
    val sealedSegments: BitSet = BitSet(),
    /** In only: segment fetches abandoned for a failed segment (counts against the sender). */
    var failures: Int = 0,
) {
    val segmentCount: Int get() = segmentIds.size
    val complete: Boolean get() = doneSegments.cardinality() == segmentCount
}

/** Transfer records, sealed at rest. */
class TransferBook(private val file: File, private val cipher: AtRestCipher) {
    private val records = LinkedHashMap<String, TransferRecord>()

    init {
        val props = readSealedProperties(file, cipher)
        props.stringPropertyNames().filter { it.endsWith(".rec") }.forEach { name ->
            runCatching { decode(hexToBytes(props.getProperty(name))) }.getOrNull()?.let { records[it.id] = it }
        }
    }

    @Synchronized fun all(): List<TransferRecord> = records.values.toList()

    @Synchronized fun get(id: String): TransferRecord? = records[id]

    @Synchronized
    fun forMessage(direction: MessageDirection, peer: String, messageIdHex: String): TransferRecord? =
        records.values.firstOrNull { it.direction == direction && it.peer == peer && it.messageIdHex == messageIdHex }

    @Synchronized
    fun forFile(direction: MessageDirection, peer: String, fileIdHex: String): TransferRecord? =
        records.values.firstOrNull { it.direction == direction && it.peer == peer && it.fileIdHex == fileIdHex }

    /** The incoming transfer that lists [segmentIdHex] and the segment's index. */
    @Synchronized
    fun forSegment(direction: MessageDirection, segmentIdHex: String): Pair<TransferRecord, Int>? {
        for (record in records.values) {
            if (record.direction != direction) continue
            val index = record.segmentIds.indexOf(segmentIdHex)
            if (index >= 0) return record to index
        }
        return null
    }

    @Synchronized
    fun save(record: TransferRecord) {
        records[record.id] = record
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        if (records.remove(id) != null) persist()
    }

    private fun persist() {
        val props = Properties()
        records.values.forEach { props.setProperty("${it.id}.rec", encode(it).sfHex()) }
        writeSealedProperties(file, cipher, props)
    }

    private fun encode(r: TransferRecord): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeByte(1)
            writeUTF(r.id)
            writeByte(r.direction.ordinal)
            writeUTF(r.peer)
            writeUTF(r.messageIdHex)
            writeUTF(r.fileIdHex)
            writeUTF(r.manifestBundleHex)
            writeLong(r.totalSize)
            write(r.sha256)
            writeByte(r.segmentIds.size)
            r.segmentIds.forEachIndexed { index, id ->
                writeUTF(id)
                writeShort(r.segmentChunks[index])
            }
            writeInt(r.expiryHour)
            writeByte(r.state.ordinal)
            val done = ChunkBitmap.encode(r.doneSegments, r.segmentIds.size)
            writeInt(done.size)
            write(done)
            val sealed = ChunkBitmap.encode(r.sealedSegments, r.segmentIds.size)
            writeInt(sealed.size)
            write(sealed)
            writeUTF(r.vaultId.orEmpty())
            writeInt(r.failures)
        }
    }.toByteArray()

    private fun decode(bytes: ByteArray): TransferRecord {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readUnsignedByte() == 1)
        val id = input.readUTF()
        val direction = MessageDirection.entries[input.readUnsignedByte()]
        val peer = input.readUTF()
        val messageId = input.readUTF()
        val fileId = input.readUTF()
        val manifest = input.readUTF()
        val total = input.readLong()
        val sha = ByteArray(32).also(input::readFully)
        val count = input.readUnsignedByte()
        val ids = ArrayList<String>()
        val chunks = ArrayList<Int>()
        repeat(count) {
            ids += input.readUTF()
            chunks += input.readUnsignedShort()
        }
        val expiry = input.readInt()
        val state = TransferState.entries[input.readUnsignedByte()]
        val done = ChunkBitmap.decode(ByteArray(input.readInt()).also(input::readFully), count)
        val sealed = ChunkBitmap.decode(ByteArray(input.readInt()).also(input::readFully), count)
        val vault = input.readUTF().ifEmpty { null }
        val failures = input.readInt()
        return TransferRecord(id, direction, peer, messageId, fileId, manifest, total, sha, ids, chunks, expiry, state, done, vault, sealed, failures)
    }
}

/** File keys, one sealed file apart from the transfer records so erasing a key never depends on rewriting a manifest. */
class TransferKeys(private val file: File, private val cipher: AtRestCipher) {
    private val properties = readSealedProperties(file, cipher)

    @Synchronized
    fun put(transferId: String, key: ByteArray) {
        properties.setProperty(transferId, key.sfHex())
        writeSealedProperties(file, cipher, properties)
    }

    @Synchronized fun get(transferId: String): ByteArray? = properties.getProperty(transferId)?.let(::hexToBytes)

    @Synchronized fun has(transferId: String): Boolean = properties.containsKey(transferId)

    @Synchronized
    fun erase(transferId: String) {
        if (properties.remove(transferId) != null) writeSealedProperties(file, cipher, properties)
    }

    @Synchronized fun count(): Int = properties.size
}

/**
 * (authenticated sender, message ID) pairs already opened, kept until the bundle tombstone lifetime ends, so a re-sealed
 * replacement of an opened message is dropped (finding 3). Bounded (M12a: 20,000 entries).
 */
class SeenMessageIds(private val file: File, private val cipher: AtRestCipher, private val maxEntries: Int) {
    private val properties = readSealedProperties(file, cipher)

    @Synchronized fun contains(sender: String, messageIdHex: String): Boolean = properties.containsKey("$sender:$messageIdHex")

    @Synchronized fun size(): Int = properties.size

    /** False when the set is full: the caller refuses new messages until old entries expire. */
    @Synchronized
    fun add(sender: String, messageIdHex: String, untilHour: Int): Boolean {
        val key = "$sender:$messageIdHex"
        if (!properties.containsKey(key) && properties.size >= maxEntries) return false
        properties.setProperty(key, untilHour.toString())
        writeSealedProperties(file, cipher, properties)
        return true
    }

    @Synchronized
    fun sweep(nowHour: Int) {
        val stale = properties.stringPropertyNames().filter { (properties.getProperty(it).toIntOrNull() ?: 0) <= nowHour }
        if (stale.isEmpty()) return
        stale.forEach(properties::remove)
        writeSealedProperties(file, cipher, properties)
    }
}

/**
 * Receipts waiting to be sent (statuses 2 and 3, and 1 where a direct DELIVERED_LOCAL did not cover it), persisted so a
 * crash between "downloaded" and the next exchange loses nothing. Deduplicated by (peer, status, bundle) and batched up
 * to 64 bundle IDs per receipt bundle.
 */
class ReceiptOutbox(private val file: File, private val cipher: AtRestCipher) {
    class Pending(val peer: String, val status: ReceiptStatus, val bundleIdHex: String, val queuedMs: Long)

    private val properties = readSealedProperties(file, cipher)

    @Synchronized
    fun queue(peer: String, status: ReceiptStatus, bundleIdHex: String, nowMs: Long): Boolean {
        val key = "$peer|${status.wireValue}|$bundleIdHex"
        if (properties.containsKey(key)) return false
        properties.setProperty(key, nowMs.toString())
        writeSealedProperties(file, cipher, properties)
        return true
    }

    @Synchronized
    fun pending(peer: String? = null): List<Pending> = properties.stringPropertyNames().mapNotNull { key ->
        val parts = key.split('|')
        if (parts.size != 3 || (peer != null && parts[0] != peer)) return@mapNotNull null
        Pending(parts[0], ReceiptStatus.fromWire(parts[1].toInt()), parts[2], properties.getProperty(key).toLong())
    }.sortedBy { it.queuedMs }

    @Synchronized
    fun remove(items: Collection<Pending>) {
        if (items.isEmpty()) return
        items.forEach { properties.remove("${it.peer}|${it.status.wireValue}|${it.bundleIdHex}") }
        writeSealedProperties(file, cipher, properties)
    }

    @Synchronized fun size(): Int = properties.size
}
