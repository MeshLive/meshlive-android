package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PRIV1: every sealed store writes its seal and file under [WipeGuard.guardedWrite]. A wipe that has begun waits for a
 * write already sealing (it would otherwise land after the files were deleted) and every later write is refused.
 */
class WipeWriterBarrierTest {
    private lateinit var dir: File
    private val key = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { (it * 5).toByte() }, "AES") }

    /** A Keystore stand-in whose seal can be held until released. */
    private class HeldCipher(private val inner: AtRestCipher) : AtRestCipher {
        @Volatile var hold: CountDownLatch? = null
        val sealing = CountDownLatch(1)
        override fun seal(plaintext: ByteArray): ByteArray {
            hold?.let {
                sealing.countDown()
                it.await(10, TimeUnit.SECONDS)
            }
            return inner.seal(plaintext)
        }
        override fun open(sealed: ByteArray): ByteArray = inner.open(sealed)
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("wipe-barrier").toFile()
        WipeGuard.resetForTest()
    }

    @After
    fun tearDown() {
        WipeGuard.resetForTest()
        dir.deleteRecursively()
    }

    @Test
    fun theWipeWaitsForABlockListWriteThatIsSealingAndRefusesTheNextOne() {
        val cipher = HeldCipher(key)
        val file = File(dir, "blocked-people.bin")
        val store = BlockedPeopleStore(file, cipher) { 1_700_000_000_000L }
        val first = IdentityFingerprint.fromPublicKey(byteArrayOf(1, 2, 3))
        val second = IdentityFingerprint.fromPublicKey(byteArrayOf(4, 5, 6))
        cipher.hold = CountDownLatch(1)
        var added: BlockResult? = null
        val writer = Thread { added = store.add(first) }.apply { start() }
        assertTrue("the block is being sealed", cipher.sealing.await(5, TimeUnit.SECONDS))

        WipeGuard.begin()
        var waited = false
        val wipe = Thread { waited = WipeGuard.awaitWriters(5_000) }.apply { start() }
        wipe.join(200)
        assertTrue("the wipe waits for the write in progress", wipe.isAlive)
        cipher.hold?.countDown()
        writer.join(5_000)
        wipe.join(5_000)
        assertTrue(waited)
        assertEquals(BlockResult.BLOCKED, added)
        val written = file.readBytes()

        // The wipe deletes the files after this point; a later write is refused and changes nothing.
        cipher.hold = null
        assertThrows(IllegalStateException::class.java) { store.add(second) }
        assertArrayEquals(written, file.readBytes())
    }

    @Test
    fun aSealedPropertiesStoreWriteAfterTheWipeBeganIsRefused() {
        val file = File(dir, "ledger.bin")
        writeSealedProperties(file, key, Properties().apply { setProperty("peer", "a1b2c3d4e5f6") })
        val before = file.readBytes()
        WipeGuard.begin()
        assertTrue("nothing is running", WipeGuard.awaitWriters(1_000))
        assertThrows(IllegalStateException::class.java) {
            writeSealedProperties(file, key, Properties().apply { setProperty("peer", "0badc0ffee00") })
        }
        assertArrayEquals(before, file.readBytes())
        assertFalse(File(dir, "ledger.bin.tmp").exists())
    }
}
