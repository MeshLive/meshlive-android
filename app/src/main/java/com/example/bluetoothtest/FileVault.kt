package com.example.bluetoothtest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.BitSet
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A vault block was already written with different bytes (or a retry would reuse a nonce): never overwritten. */
class VaultImmutableException(message: String) : IllegalStateException(message)

/** A stored block does not authenticate (torn write, truncation, tampering): the file must be re-created with a fresh key. */
class VaultDamagedException(message: String) : IllegalStateException(message)

/**
 * File vault (PROTOCOL "File vault"): received and sent file bytes, inline attachments included, outside the sealed
 * archive. One directory per file under a local random ID (never the sender's file ID); each file has its own random
 * 256-bit key held in a sealed meta record; the bytes are 4096-byte blocks, each AES-256-GCM with
 * nonce = block index and AAD = `local ID || block index || final-block flag`, so a truncated file fails and a
 * segment can be written at its own offset. No plaintext copy is ever created.
 *
 * Sealed bytes are immutable (design finding 11): a block is written once. A retry after a crash may rewrite a block
 * only with the identical bytes (compared) and otherwise refuses with [VaultImmutableException]; a torn block that no
 * longer authenticates is [VaultDamagedException] and the caller allocates a new vault file with a fresh key.
 */
class FileVault(private val root: File, private val cipher: AtRestCipher, private val random: SecureRandom = SecureRandom()) {
    class Info(
        val localId: String,
        val owner: String,
        val totalSize: Long,
        internal val key: ByteArray,
        internal val written: BitSet,
    ) {
        val blockCount: Int get() = blocksFor(totalSize)
        val writtenBlocks: Int get() = written.cardinality()
        val complete: Boolean get() = written.cardinality() == blockCount

        /** Disk bytes the file can occupy: data plus one tag per block. */
        val physicalBytes: Long get() = totalSize + blockCount.toLong() * TAG_BYTES

        fun plainLength(index: Int): Int =
            if (index < blockCount - 1) BLOCK else (totalSize - index.toLong() * BLOCK).toInt()
    }

    private val files = LinkedHashMap<String, Info>()

    init {
        root.mkdirs()
        root.listFiles { file -> file.isDirectory }?.forEach { dir ->
            val info = runCatching { loadMeta(dir) }.getOrNull()
            if (info != null && info.localId == dir.name) files[info.localId] = info else dir.deleteRecursively()
        }
    }

    @Synchronized fun info(localId: String): Info? = files[localId]

    @Synchronized fun ids(): Set<String> = files.keys.toSet()

    /** Disk bytes held (or reserved: a file counts in full from its creation). */
    @Synchronized fun usedBytes(): Long = files.values.sumOf { it.physicalBytes }

    @Synchronized fun usedBytes(owner: String): Long = files.values.filter { it.owner == owner }.sumOf { it.physicalBytes }

    /** Allocates a vault file of [totalSize] bytes with a fresh key and returns its local ID. */
    @Synchronized
    fun create(owner: String, totalSize: Long): String {
        require(totalSize in 1..MessagingV2Format.MAX_FILE_BYTES) { "Invalid vault file size" }
        val id = ByteArray(16).also(random::nextBytes).sfHex()
        val key = ByteArray(32).also(random::nextBytes)
        val info = Info(id, owner, totalSize, key, BitSet())
        val dir = File(root, id).apply { mkdirs() }
        File(dir, DATA).createNewFile()
        saveMeta(dir, info)
        files[id] = info
        return id
    }

    /**
     * Writes whole blocks starting at [firstBlock]. Every block but the file's last must be exactly 4096 bytes and the
     * last block must be exactly the remainder, so the data's end is fixed by the size declared at creation.
     */
    @Synchronized
    fun writeBlocks(localId: String, firstBlock: Int, data: ByteArray) {
        val info = requireNotNull(files[localId]) { "Unknown vault file" }
        require(data.isNotEmpty() && firstBlock >= 0) { "Nothing to write" }
        val blocks = (data.size + BLOCK - 1) / BLOCK
        require(firstBlock + blocks <= info.blockCount) { "Write past the end of the file" }
        val dir = File(root, localId)
        var changed = false
        openRandomAccess(File(dir, DATA), "rw").use { file ->
            for (n in 0 until blocks) {
                val index = firstBlock + n
                val start = n * BLOCK
                val plain = data.copyOfRange(start, minOf(data.size, start + BLOCK))
                require(plain.size == info.plainLength(index)) { "Block $index has the wrong length" }
                val offset = index.toLong() * (BLOCK + TAG_BYTES)
                val sealedLength = plain.size + TAG_BYTES
                val existing = readPhysical(file, offset, sealedLength)
                if (existing != null) {
                    // A written block, or a physically present one the meta never recorded (crash between the two).
                    val old = try {
                        decrypt(info, index, existing)
                    } catch (error: java.security.GeneralSecurityException) {
                        throw VaultDamagedException("Block $index does not authenticate; allocate a new vault file")
                    }
                    if (!MessageDigest.isEqual(old, plain)) {
                        old.fill(0)
                        throw VaultImmutableException("Block $index is already written with different bytes")
                    }
                    old.fill(0)
                    if (!info.written.get(index)) {
                        info.written.set(index)
                        changed = true
                    }
                    continue
                }
                require(!info.written.get(index)) { "Block $index is recorded as written but missing" }
                file.seek(offset)
                file.write(encrypt(info, index, plain))
                info.written.set(index)
                changed = true
            }
            file.fd.sync()
        }
        if (changed) saveMeta(dir, info)
    }

    /** One decrypted block. */
    @Synchronized
    fun readBlock(localId: String, index: Int): ByteArray {
        val info = requireNotNull(files[localId]) { "Unknown vault file" }
        require(index in 0 until info.blockCount && info.written.get(index)) { "Block $index is not written" }
        openRandomAccess(File(File(root, localId), DATA), "r").use { file ->
            val sealed = readPhysical(file, index.toLong() * (BLOCK + TAG_BYTES), info.plainLength(index) + TAG_BYTES)
                ?: throw VaultDamagedException("Block $index is missing")
            return try {
                decrypt(info, index, sealed)
            } catch (error: java.security.GeneralSecurityException) {
                throw VaultDamagedException("Block $index does not authenticate")
            }
        }
    }

    /** [length] plaintext bytes from byte [offset] (shorter at the end of the file). Needs every touched block. */
    @Synchronized
    fun read(localId: String, offset: Long, length: Int): ByteArray {
        val info = requireNotNull(files[localId]) { "Unknown vault file" }
        require(offset >= 0 && length >= 0) { "Invalid range" }
        if (offset >= info.totalSize || length == 0) return ByteArray(0)
        val end = minOf(info.totalSize, offset + length)
        val out = ByteArrayOutputStream((end - offset).toInt())
        var position = offset
        while (position < end) {
            val index = (position / BLOCK).toInt()
            val block = readBlock(localId, index)
            val from = (position - index.toLong() * BLOCK).toInt()
            val take = minOf(block.size - from, (end - position).toInt())
            out.write(block, from, take)
            block.fill(0)
            position += take
        }
        return out.toByteArray()
    }

    /** SHA-256 over the whole file, one block at a time; needs every block. */
    @Synchronized
    fun sha256(localId: String): ByteArray {
        val info = requireNotNull(files[localId]) { "Unknown vault file" }
        require(info.complete) { "The file is incomplete" }
        val digest = MessageDigest.getInstance("SHA-256")
        for (index in 0 until info.blockCount) {
            val block = readBlock(localId, index)
            digest.update(block)
            block.fill(0)
        }
        return digest.digest()
    }

    /** Removes the key first (the sealed meta), then the bytes. */
    @Synchronized
    fun delete(localId: String) {
        val info = files.remove(localId) ?: return
        info.key.fill(0)
        val dir = File(root, localId)
        File(dir, META).delete()
        dir.deleteRecursively()
    }

    @Synchronized
    fun deleteAll() {
        files.keys.toList().forEach(::delete)
    }

    private fun readPhysical(file: RandomAccessFile, offset: Long, length: Int): ByteArray? {
        val available = (file.length() - offset).coerceAtMost(length.toLong()).toInt()
        if (available <= 0) return null
        file.seek(offset)
        // Only the bytes that exist: a short block is never zero-padded to its full length, because a padded tail would
        // authenticate whenever the missing bytes happened to be zero (one tag in 256 ends in a zero byte).
        val bytes = ByteArray(available)
        file.readFully(bytes)
        // An unwritten region of a sparse file reads as zeros; a sealed block never does. A region that holds some
        // bytes but not a whole block is a torn write: it is returned (and fails to authenticate), never overwritten.
        return if (bytes.all { it.toInt() == 0 }) null else bytes
    }

    private fun aad(info: Info, index: Int): ByteArray {
        val id = hexToBytes(info.localId)
        val final = if (index == info.blockCount - 1) 1 else 0
        return id + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte(), final.toByte())
    }

    private fun nonce(index: Int): ByteArray =
        ByteArray(12).also { n -> for (i in 0 until 4) n[8 + i] = (index ushr (24 - 8 * i)).toByte() }

    private fun encrypt(info: Info, index: Int, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(info.key, "AES"), GCMParameterSpec(128, nonce(index)))
            updateAAD(aad(info, index))
            doFinal(plain)
        }

    private fun decrypt(info: Info, index: Int, sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(info.key, "AES"), GCMParameterSpec(128, nonce(index)))
            updateAAD(aad(info, index))
            doFinal(sealed)
        }

    private fun saveMeta(dir: File, info: Info) {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeByte(1)
                write(hexToBytes(info.localId))
                write(info.key)
                writeLong(info.totalSize)
                writeUTF(info.owner)
                val bitmap = ChunkBitmap.encode(info.written, info.blockCount)
                writeInt(bitmap.size)
                write(bitmap)
            }
        }.toByteArray()
        WipeGuard.guardedWrite("file vault") { atomicWrite(File(dir, META), cipher.seal(bytes)) }
    }

    private fun loadMeta(dir: File): Info {
        val input = DataInputStream(ByteArrayInputStream(cipher.open(File(dir, META).readBytes())))
        require(input.readUnsignedByte() == 1) { "Unsupported vault meta" }
        val id = ByteArray(16).also(input::readFully).sfHex()
        val key = ByteArray(32).also(input::readFully)
        val total = input.readLong()
        require(total in 1..MessagingV2Format.MAX_FILE_BYTES) { "Invalid vault size" }
        val owner = input.readUTF()
        val bitmapLength = input.readInt()
        require(bitmapLength == (blocksFor(total) + 7) / 8) { "Invalid vault bitmap" }
        val bitmap = ByteArray(bitmapLength).also(input::readFully)
        return Info(id, owner, total, key, ChunkBitmap.decode(bitmap, blocksFor(total)))
    }

    companion object {
        const val BLOCK = MessagingV2Format.BLOCK_BYTES
        const val TAG_BYTES = 16
        private const val DATA = "data.bin"
        private const val META = "meta.bin"

        fun blocksFor(totalSize: Long): Int = ((totalSize + BLOCK - 1) / BLOCK).toInt()
    }
}
