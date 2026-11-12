package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-10-27 (finding 6): the BMH3 pins, the Classic address book and the bad-2M PHY memory name peers this phone
 * met and were plain text. They are sealed now; a phone updated from an earlier build keeps its memory and loses the
 * plain file.
 */
class SealedTextFileTest {
    private lateinit var dir: File
    private val key = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
    private val otherKey = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES") }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sealed-text").toFile()
        WipeGuard.resetForTest()
    }

    @After
    fun tearDown() {
        WipeGuard.resetForTest()
        dir.deleteRecursively()
    }

    private val nodeLine = "0123456789abcdef0123456789abcdef AA:BB:CC:DD:EE:FF 1700000000000"

    @Test
    fun anUpdatedPhoneKeepsItsMemorySealedAndThePlainFileIsGone() {
        val plain = File(dir, Bmh3Pins.FILE_NAME).apply { writeText("a1b2c3d4e5f6 1700000000000") }
        val sealed = File(dir, Bmh3Pins.SEALED_FILE_NAME)
        val file = SealedTextFile(sealed, { key }, legacyPlain = plain)
        assertEquals("a1b2c3d4e5f6 1700000000000", file.read())
        assertFalse("the plaintext file is deleted", plain.exists())
        assertTrue(sealed.exists())
        assertFalse("no peer prefix is readable in the file", String(sealed.readBytes(), Charsets.ISO_8859_1).contains("a1b2c3d4e5f6"))
        // The next process reads the sealed copy.
        assertEquals("a1b2c3d4e5f6 1700000000000", SealedTextFile(sealed, { key }, legacyPlain = plain).read())
    }

    @Test
    fun theBmh3PinsAndTheClassicBookRoundTripThroughTheSealedFile() {
        val pinsFile = SealedTextFile(File(dir, Bmh3Pins.SEALED_FILE_NAME), { key })
        val pins = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = pinsFile.read()
            override fun write(value: String) = pinsFile.write(value)
        })
        val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
        pins.pin(prefix)
        val reopened = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = SealedTextFile(File(dir, Bmh3Pins.SEALED_FILE_NAME), { key }).read()
            override fun write(value: String) = Unit
        })
        assertTrue(reopened.isPinned(prefix))
        val book = ClassicAddressBookState.decode(nodeLine)
        val bookFile = SealedTextFile(File(dir, "classic-addresses.bin"), { key })
        bookFile.write(book.encode())
        assertEquals(1, ClassicAddressBookState.decode(requireNotNull(bookFile.read())).size())
    }

    private fun marks(path: File, cipher: AtRestCipher = key, legacy: LegacyPlain? = null) = AdaptivePhyMarks(
        SealedRecords(SealedTextFile(path, { cipher }), 1024, null, legacy, retryBaseMs = 60_000, log = {}).also { it.start() },
    )

    /**
     * PRIV1 re-review S4: the bad-2M marks moved from the 2026-10-27 sealed `key=value` file (and the plain preferences
     * before it) into a sealed-records store on the sealed-memory worker; an updated phone keeps its marks and loses both
     * old copies.
     */
    @Test
    fun theBad2mMemoryMovesFromItsOldFilesIntoTheSealedStoreAndSurvivesARestart() {
        val phone = FakePhone(File(dir, "phone"))
        SealedRecordsWorker.resetForTest()
        try {
            val context = phone.start()
            val node = "a1b2c3d4e5f6"
            val markKey = AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, node)
            val oldKey = AdaptivePhyMemory.key(BluetoothTransportOption.BLE_L2CAP, "0badc0ffee00")
            val oldFile = File(phone.noBackupDir, "adaptive-phy-memory.bin")
            SealedTextFile(oldFile, { phone.atRestCipher }).write("$markKey=1700000000000")
            phone.preferences("adaptive_phy_memory").edit().putLong(oldKey, 1_700_000_000_001L)
                .putBoolean(AdaptivePhyMemory.GATT_FANOUT_PURGED, true).putString("junk", "x").commit()
            val path = File(phone.noBackupDir, AdaptivePhyMemory.MARKS_FILE)
            marks(path, phone.atRestCipher, AdaptivePhyMemory.legacyMarks(context))
            SealedRecordsWorker.awaitIdleForTest()
            assertFalse("the old sealed file is gone", oldFile.exists())
            assertTrue("the plain preferences are gone", phone.preferences("adaptive_phy_memory").all.isEmpty())
            assertFalse("no peer label is readable", String(path.readBytes(), Charsets.ISO_8859_1).contains(node))
            val restarted = marks(path, phone.atRestCipher)
            SealedRecordsWorker.awaitIdleForTest()
            assertEquals(1_700_000_000_000L, restarted.get(markKey))
            assertEquals(1_700_000_000_001L, restarted.get(oldKey))
            assertTrue(restarted.flag(AdaptivePhyMemory.GATT_FANOUT_PURGED))
            assertNull(restarted.get("junk"))
            restarted.remove(listOf(markKey))
            SealedRecordsWorker.awaitIdleForTest()
            val again = marks(path, phone.atRestCipher)
            SealedRecordsWorker.awaitIdleForTest()
            assertNull(again.get(markKey))
        } finally {
            SealedRecordsWorker.resetForTest()
        }
    }

    /** S4: a wipe that has begun waits for the marks' running write, and none is written after it. */
    @Test
    fun theBad2mMarksAreNotWrittenOnceTheWipeStopsTheirWorker() {
        SealedRecordsWorker.resetForTest()
        try {
            val path = File(dir, AdaptivePhyMemory.MARKS_FILE)
            val marks = marks(path)
            SealedRecordsWorker.awaitIdleForTest()
            WipeGuard.begin()
            assertTrue(SealedRecordsWorker.drainForWipe(5_000))
            assertTrue(WipeGuard.awaitWriters(1_000))
            marks.put(AdaptivePhyMemory.key(BluetoothTransportOption.BLE_GATT, "a1b2c3d4e5f6"), 5L)
            Thread.sleep(100)
            assertFalse(path.exists())
        } finally {
            WipeGuard.resetForTest()
            SealedRecordsWorker.resetForTest()
        }
    }

    @Test
    fun aFileSealedUnderAnotherKeyReadsAsEmptyAndIsReplacedByTheNextWrite() {
        val path = File(dir, "classic-addresses.bin")
        SealedTextFile(path, { otherKey }).write(nodeLine)
        val file = SealedTextFile(path, { key })
        assertNull(file.read())
        file.write(nodeLine)
        assertEquals(nodeLine, file.read())
    }

    /** A Keystore that refuses now (locked, busy) and works later, like the real one after unlock. */
    private class FlakyCipher(private val inner: AtRestCipher) : AtRestCipher {
        var failing = true
        override fun seal(plaintext: ByteArray): ByteArray = if (failing) throw java.security.KeyStoreException("busy") else inner.seal(plaintext)
        override fun open(sealed: ByteArray): ByteArray = if (failing) throw java.security.KeyStoreException("busy") else inner.open(sealed)
    }

    /**
     * Security review 2026-10-27 (item 6): a passing Keystore failure used to read as "empty", so the next pin or address
     * replaced the whole stored memory. Now the memory stays unloaded, nothing is written, and it loads once the Keystore
     * works again; a damaged file (another key) is still replaced.
     */
    @Test
    fun aPassingKeystoreFailureNeverWipesTheStoredMemory() {
        val path = File(dir, Bmh3Pins.SEALED_FILE_NAME)
        val stored = byteArrayOf(9, 8, 7, 6, 5, 4)
        Bmh3Pins(object : Bmh3Pins.Storage {
            val f = SealedTextFile(path, { key })
            override fun read(): String? = f.read()
            override fun write(value: String) = f.write(value)
        }).pin(stored)
        val before = path.readBytes()
        val flaky = FlakyCipher(key)
        val file = SealedTextFile(path, { flaky })
        assertThrows(SealedTextUnavailable::class.java) { file.read() }
        val pins = Bmh3Pins(object : Bmh3Pins.Storage {
            override fun read(): String? = file.read()
            override fun write(value: String) = runCatching { file.write(value) }.let { }
        })
        pins.pin(byteArrayOf(1, 1, 1, 1, 1, 1))
        assertFalse(pins.isPinned(stored))
        assertTrue("the stored pins were not written over", before.contentEquals(path.readBytes()))
        flaky.failing = false
        assertTrue("loaded once the Keystore works", pins.isPinned(stored))
        // The bad-2M memory waits the same way and keeps what it learned meanwhile.
        SealedRecordsWorker.resetForTest()
        try {
            val marksPath = File(dir, AdaptivePhyMemory.MARKS_FILE)
            marks(marksPath).put("bad2m2.BLE_GATT.aaaaaaaaaaaa", 5L)
            SealedRecordsWorker.awaitIdleForTest()
            val flakyMarks = FlakyCipher(key)
            val records = SealedRecords(SealedTextFile(marksPath, { flakyMarks }), 1024, null, retryBaseMs = 60_000, log = {})
            records.start()
            val marks = AdaptivePhyMarks(records)
            marks.put("bad2m2.BLE_GATT.bbbbbbbbbbbb", 6L)
            SealedRecordsWorker.awaitIdleForTest()
            assertTrue("the stored marks were not written over", marks(marksPath).let {
                SealedRecordsWorker.awaitIdleForTest()
                it.get("bad2m2.BLE_GATT.aaaaaaaaaaaa") == 5L && it.get("bad2m2.BLE_GATT.bbbbbbbbbbbb") == null
            })
            flakyMarks.failing = false
            records.kickForTest()
            SealedRecordsWorker.awaitIdleForTest()
            marks.put("bad2m2.BLE_GATT.cccccccccccc", 7L)
            SealedRecordsWorker.awaitIdleForTest()
            val reopened = marks(marksPath)
            SealedRecordsWorker.awaitIdleForTest()
            assertEquals(5L, reopened.get("bad2m2.BLE_GATT.aaaaaaaaaaaa"))
            assertEquals(6L, reopened.get("bad2m2.BLE_GATT.bbbbbbbbbbbb"))
            assertEquals(7L, reopened.get("bad2m2.BLE_GATT.cccccccccccc"))
        } finally {
            SealedRecordsWorker.resetForTest()
        }
    }

    @Test
    fun nothingIsWrittenOnceAnEmergencyWipeStarted() {
        val path = File(dir, "classic-addresses.bin")
        WipeGuard.begin()
        assertThrows(IllegalStateException::class.java) { SealedTextFile(path, { key }).write(nodeLine) }
        assertFalse(path.exists())
    }
}
