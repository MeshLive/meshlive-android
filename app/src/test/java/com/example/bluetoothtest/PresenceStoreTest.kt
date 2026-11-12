package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2: the sealed presence store, the vault's generation rules and recognition between two phones, with the
 * hardened key model of PROTOCOL "Keys and epochs": independent random day keys in a rolling window, no master
 * key, migration of a version 1 (single-key) store.
 */
class PresenceStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    /** A reversible "cipher" with an integrity byte, and switches for Keystore trouble. */
    private class TestCipher : AtRestCipher {
        var failing = false
        var seals = 0
        override fun seal(plaintext: ByteArray): ByteArray {
            check(!failing) { "Keystore unavailable" }
            seals++
            return byteArrayOf(0x5a) + plaintext.map { (it.toInt() xor 0x33).toByte() }.toByteArray() + byteArrayOf(0x77)
        }

        override fun open(sealed: ByteArray): ByteArray {
            check(!failing) { "Keystore unavailable" }
            if (sealed.size < 2 || sealed.first() != 0x5a.toByte() || sealed.last() != 0x77.toByte()) {
                throw AEADBadTagException("tag mismatch")
            }
            return sealed.copyOfRange(1, sealed.size - 1).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
        }
    }

    private class MemoryFloor(var value: Long = 1L) : GenerationFloor {
        override fun get() = value
        override fun raiseTo(value: Long) {
            if (value > this.value) this.value = value
        }
    }

    private val cipher = TestCipher()
    private val floor = MemoryFloor()
    private val nodeA = NodeId.fromBytes(ByteArray(16) { 0x0a })
    private val nodeB = NodeId.fromBytes(ByteArray(16) { 0x0b })
    private val nodeC = NodeId.fromBytes(ByteArray(16) { 0x0c })
    private val epoch = 1_990_656L
    private val day = PresenceLeases.dayOf(epoch)

    /** The epoch new windows start at; tests move it to simulate days passing. */
    private var now = epoch

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("presence-store-test").toFile()
        file = File(dir, "presence-leases.bin")
        now = epoch
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun vault(
        sharedFile: File = file,
        sharedCipher: AtRestCipher = cipher,
        sharedFloor: GenerationFloor = floor,
        legacy: Pair<Long, ByteArray>? = null,
        onImported: () -> Unit = {},
        random: SecureRandom = SecureRandom(),
    ) = PresenceVault(
        PresenceStore(sharedFile, sharedCipher), sharedFloor, random, { legacy }, onImported,
        epochSource = { now },
    )

    /** The day key an ID of [e] is computed from (a copy), as the advertiser asks for it. */
    private fun PresenceVault.keyOf(e: Long = epoch, current: Long = e): ByteArray = requireNotNull(dayKey(e, current))

    /** The lease the owner would send at [e] (today and 27 more days), without the delivery record of the real flow. */
    private fun leaseFrom(owner: PresenceVault, e: Long = epoch) = DiscoveryExchange.PresenceLease(
        owner.generation(), e, PresenceLeases.dayOf(e), (0 until 28).map { owner.keyOf(e + it * PresenceLeases.EPOCHS_PER_DAY, e) },
    )

    private fun own(vault: PresenceVault, node: NodeId, e: Long, role: Int, current: Long = e) =
        RotatingDiscoveryIds.ownEntry(vault.keyOf(e, current), node.toByteArray(), e, role)

    private val legacyP = ByteArray(32) { it.toByte() }

    /** A version 1 plaintext store (single long-lived key) as earlier builds sealed it. */
    private fun versionOneStore(generation: Long, key: ByteArray, contacts: ByteArray = byteArrayOf(0, 0)): ByteArray =
        "BPS1".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) +
            java.nio.ByteBuffer.allocate(4).putInt(generation.toInt()).array() + key + contacts

    // ----- codec -----

    @Test
    fun theStateRoundTripsThroughTheSealedFile() {
        val vault = vault()
        assertTrue(vault.ensureLoaded())
        assertTrue(vault.persistent)
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        // B gave A a lease; A delivered one to B; A turned B's switch off before (default on otherwise).
        assertTrue(vault.storeLease(nodeB, leaseFrom(owner), epoch + 5, 1_000L))
        assertTrue(vault.recordDelivered(nodeB))
        val reloaded = vault()
        assertTrue(reloaded.ensureLoaded())
        assertEquals(vault.generation(), reloaded.generation())
        assertArrayEquals(vault.keyOf(), reloaded.keyOf())
        assertEquals(vault.windowInfo(), reloaded.windowInfo())
        val contact = requireNotNull(reloaded.contact(nodeB))
        assertEquals(reloaded.generation(), contact.deliveredGeneration)
        val presence = requireNotNull(contact.presence)
        assertEquals(owner.generation(), presence.generation)
        assertEquals(28, presence.leasesByDay.size)
        assertEquals(-5L, presence.skewEpochs)
        // The file is sealed: no day key appears in clear (the test cipher flips every byte).
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(String(vault.keyOf(), Charsets.ISO_8859_1)))
    }

    @Test
    fun theDecoderRejectsTrailingBytesDuplicatesAndBadMagic() {
        val keys = mapOf(day to ByteArray(32) { 7 })
        val state = PresenceState(1, keys, listOf(PresenceContact(nodeA)))
        val good = PresenceStateCodec.encode(state)
        PresenceStateCodec.decode(good) { epoch }
        assertTrue(runCatching { PresenceStateCodec.decode(good + 0) { epoch } }.isFailure)
        assertTrue(runCatching { PresenceStateCodec.decode(good.copyOf().also { it[0] = 'X'.code.toByte() }) { epoch } }.isFailure)
        // Hand-built duplicate: count 2 with the same node twice.
        val head = 4 + 1 + 4 + 1 + (4 + 32)
        val duplicate = good.copyOf(head).plus(byteArrayOf(0, 2)) +
            good.copyOfRange(head + 2, good.size) + good.copyOfRange(head + 2, good.size)
        assertTrue(runCatching { PresenceStateCodec.decode(duplicate) { epoch } }.isFailure)
        // A key block with the same day twice, or with no key at all, is not a store.
        val twoKeys = PresenceStateCodec.encode(PresenceState(1, keys + (day + 1 to ByteArray(32) { 8 }), emptyList()))
        val sameDay = twoKeys.copyOf().also { bytes ->
            val secondDay = 4 + 1 + 4 + 1 + (4 + 32)
            java.nio.ByteBuffer.wrap(bytes).putInt(secondDay, day.toInt())
        }
        assertTrue(runCatching { PresenceStateCodec.decode(sameDay) { epoch } }.isFailure)
        assertTrue(runCatching { PresenceState(1, emptyMap(), emptyList()) }.isFailure)
    }

    // ----- vault: creation, generations, legacy -----

    @Test
    fun aFirstWindowTakesAGenerationAboveTheFloorAndRaisesIt() {
        val vault = vault()
        assertTrue(vault.ensureLoaded())
        assertEquals(2L, vault.generation())
        assertEquals(2L, floor.value)
        // Today and the next 27 days, nothing else: no master key and nothing before today.
        assertEquals(Triple(day, day + 27, 28), vault.windowInfo())
    }

    @Test
    fun theLegacyPlainKeyIsConvertedOnceKeepsItsGenerationAndIsNotStored() {
        val key = ByteArray(32) { (it + 1).toByte() }
        var imported = false
        val vault = vault(legacy = 1L to key, onImported = { imported = true })
        assertTrue(vault.ensureLoaded())
        assertTrue(imported)
        assertEquals(1L, vault.generation())
        // The leases already delivered to contacts stay valid: the window is the earlier derivation.
        assertArrayEquals(PresenceLeases.legacyLeaseKey(key, day), vault.keyOf())
        assertArrayEquals(PresenceLeases.legacyLeaseKey(key, day + 27), vault.keyOf(epoch + 27 * 96, epoch))
        // The long-lived key itself is nowhere in the stored state.
        val plain = cipher.open(file.readBytes())
        assertFalse(String(plain, Charsets.ISO_8859_1).contains(String(key, Charsets.ISO_8859_1)))
        // The second process finds the store and ignores the legacy file.
        var again = false
        val next = vault(legacy = 9L to ByteArray(32), onImported = { again = true })
        assertTrue(next.ensureLoaded())
        assertFalse(again)
        assertArrayEquals(vault.keyOf(), next.keyOf())
    }

    @Test
    fun aVersionOneStoreIsConvertedAtLoadAndWrittenBackWithoutTheLongLivedKey() {
        val sample = PresenceContact(nodeA, visibleToThem = false, deliveredGeneration = 4)
        val v2bytes = PresenceStateCodec.encode(PresenceState(4, mapOf(day to ByteArray(32)), listOf(sample)))
        // Contacts section of a version 2 file, reused for version 1 (the layout after the key block is the same).
        val contactsBlock = v2bytes.copyOfRange(4 + 1 + 4 + 1 + 36, v2bytes.size)
        file.writeBytes(cipher.seal(versionOneStore(4, legacyP, contactsBlock)))
        floor.value = 4
        val vault = vault()
        assertTrue(vault.ensureLoaded())
        assertEquals(4L, vault.generation())
        assertFalse(requireNotNull(vault.contact(nodeA)).visibleToThem)
        assertEquals(4L, requireNotNull(vault.contact(nodeA)).deliveredGeneration)
        // The window includes yesterday while the epoch is the first of a day (the previous epoch's ID was advertised).
        assertEquals(Triple(day - 1, day + 27, 29), vault.windowInfo())
        assertArrayEquals(PresenceLeases.legacyLeaseKey(legacyP, day - 1), vault.keyOf(epoch - 1, epoch))
        assertArrayEquals(PresenceLeases.legacyLeaseKey(legacyP, day + 3), vault.keyOf(epoch + 3 * 96, epoch))
        // Written back as version 2: no P in the plaintext, and a later load needs no conversion.
        val plain = cipher.open(file.readBytes())
        assertEquals(2, plain[4].toInt())
        assertFalse(String(plain, Charsets.ISO_8859_1).contains(String(legacyP, Charsets.ISO_8859_1)))
        val again = vault()
        assertTrue(again.ensureLoaded())
        assertArrayEquals(vault.keyOf(), again.keyOf())
        // IDs computed before and after the conversion are the same: contacts that hold a lease keep recognising.
        val before = RotatingDiscoveryIds.ownEntry(PresenceLeases.legacyLeaseKey(legacyP, day), nodeA.toByteArray(), epoch, 0)
        assertEquals(before, own(again, nodeA, epoch, 0))
    }

    @Test
    fun aVersionOneStoreThatCannotBeWrittenBackStillWorksAndIsConvertedAgainFromTheSameFile() {
        file.writeBytes(cipher.seal(versionOneStore(3, legacyP)))
        floor.value = 3
        val sealedBefore = file.readBytes()
        val vault = PresenceVault(
            PresenceStore(file, object : AtRestCipher {
                override fun seal(plaintext: ByteArray): ByteArray = throw IllegalStateException("disk full")
                override fun open(sealed: ByteArray): ByteArray = cipher.open(sealed)
            }),
            floor, SecureRandom(), epochSource = { now },
        )
        assertTrue(vault.ensureLoaded())
        assertArrayEquals(PresenceLeases.legacyLeaseKey(legacyP, day), vault.keyOf())
        assertArrayEquals(sealedBefore, file.readBytes())
    }

    @Test
    fun aLostStoreNeverReusesAGeneration() {
        val first = vault()
        first.ensureLoaded()
        val used = first.generation()
        file.delete()
        val second = vault()
        second.ensureLoaded()
        assertTrue(second.generation() > used)
        assertNotEquals(first.keyOf().toList(), second.keyOf().toList())
    }

    @Test
    fun anUnreadableStoreRunsEphemeralAndIsNeverOverwritten() {
        val first = vault()
        first.ensureLoaded()
        val firstLease = leaseFrom(first)
        val sealedBefore = file.readBytes()
        cipher.failing = true
        val second = vault()
        assertFalse(second.ensureLoaded())
        assertFalse(second.persistent)
        // Ephemeral keys: IDs still derive (stable for the day), but no lease, forget or switch change persists.
        assertEquals(32, second.keyOf().size)
        assertArrayEquals(second.keyOf(), second.keyOf())
        assertFalse(first.keyOf().contentEquals(second.keyOf()))
        assertFalse(second.recordDelivered(nodeB))
        assertFalse(second.storeLease(nodeB, firstLease, epoch, 1L))
        assertFalse(second.setVisible(nodeB, true))
        assertArrayEquals(sealedBefore, file.readBytes())
        // The Keystore comes back: the same stored keys and generation return.
        cipher.failing = false
        assertTrue(second.ensureLoaded())
        assertArrayEquals(first.keyOf(), second.keyOf())
        assertEquals(first.generation(), second.generation())
    }

    @Test
    fun aRevocationWhileTheStoreIsUnreadableStillMovesTheCounterAndReplacesTheKeysOnLoad() {
        // RD1(b): a Forget during a Keystore error cannot be written, but the old keys must not come back.
        val first = vault()
        first.ensureLoaded()
        first.recordDelivered(nodeB)
        val oldKey = first.keyOf().copyOf()
        val oldGeneration = first.generation()
        cipher.failing = true
        val second = vault()
        assertFalse(second.ensureLoaded())
        assertTrue(second.forget(nodeB))
        assertTrue(floor.value > oldGeneration)
        cipher.failing = false
        assertTrue(second.ensureLoaded())
        assertTrue(second.generation() > oldGeneration)
        assertFalse(oldKey.contentEquals(second.keyOf()))
        // A third process loads the replaced keys, not the old ones.
        val third = vault()
        assertTrue(third.ensureLoaded())
        assertArrayEquals(second.keyOf(), third.keyOf())
    }

    @Test
    fun anOlderCopyOfTheStoreIsTreatedAsRevoked() {
        // RD1(c): the counter says a newer generation existed, so a restored older file's keys are never used.
        val first = vault()
        first.ensureLoaded()
        val backup = file.readBytes()
        val oldKey = first.keyOf().copyOf()
        first.recordDelivered(nodeB)
        first.forget(nodeB)
        assertTrue(first.generation() > 2L)
        file.writeBytes(backup)
        val restored = vault()
        assertTrue(restored.ensureLoaded())
        assertTrue(restored.generation() >= floor.value)
        assertFalse(oldKey.contentEquals(restored.keyOf()))
    }

    @Test
    fun aFailedSaveDuringARevocationStopsUsingTheOldKeysAtOnce() {
        val vault = vault()
        vault.ensureLoaded()
        vault.recordDelivered(nodeB)
        val oldKey = vault.keyOf().copyOf()
        cipher.failing = true
        assertTrue(vault.forget(nodeB))
        assertFalse(vault.persistent)
        assertFalse(oldKey.contentEquals(vault.keyOf()))
        cipher.failing = false
        assertTrue(vault.ensureLoaded())
        assertFalse(oldKey.contentEquals(vault.keyOf()))
    }

    @Test
    fun aCorruptFileLatchesUntilTheUserResetsPresenceData() {
        val first = vault()
        first.ensureLoaded()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val second = vault()
        assertFalse(second.ensureLoaded())
        assertEquals(PresenceStore.Loaded.Unreadable::class, PresenceStore(file, cipher).load { epoch }::class)
        // A requested revocation still moves the counter (fail closed) although nothing can be stored.
        assertTrue(second.bump())
        assertTrue(second.wipe())
        val third = vault()
        assertTrue(third.ensureLoaded())
        assertTrue(third.generation() > first.generation())
    }

    // ----- vault: the rolling window (seizure property) -----

    @Test
    fun theWindowRollsOnceADayDeletesTheOldDayAndWritesOnce() {
        val vault = vault()
        vault.ensureLoaded()
        val sealsAfterCreate = cipher.seals
        val firstKeys = (0..27).map { vault.keyOf(epoch + it * 96L, epoch) }
        // Same day: nothing to do, nothing written.
        assertFalse(vault.maintain(epoch + 10))
        assertEquals(sealsAfterCreate, cipher.seals)
        // The first epoch of the next day keeps yesterday and adds day + 28 (one write).
        assertTrue(vault.maintain(epoch + 96))
        assertEquals(sealsAfterCreate + 1, cipher.seals)
        assertEquals(Triple(day, day + 28, 29), vault.windowInfo())
        assertFalse(vault.maintain(epoch + 96))
        // One epoch later yesterday is deleted.
        assertTrue(vault.maintain(epoch + 97))
        assertEquals(Triple(day + 1, day + 28, 28), vault.windowInfo())
        assertNull(vault.dayKey(epoch + 5, epoch + 97))
        // The keys that stayed are the very same bytes, also after a restart.
        val reloaded = vault()
        assertTrue(reloaded.ensureLoaded())
        for (i in 1..27) assertArrayEquals(firstKeys[i], reloaded.keyOf(epoch + i * 96L, epoch + 97))
        assertEquals(Triple(day + 1, day + 28, 28), reloaded.windowInfo())
    }

    @Test
    fun aSeizedStoreHoldsNoKeyOfAnEarlierDayAndNoneOfALaterDayThatIsNotYetDrawn() {
        val vault = vault()
        vault.ensureLoaded()
        val firstWindow = (0..27).associate { day + it to vault.keyOf(epoch + it * 96L, epoch) }
        val readAt = epoch + 9 * 96L + 5
        now = readAt
        vault.maintain(readAt)
        // What a seizure at that moment reads: the stored (decrypted) state.
        val seized = PresenceStateCodec.decode(cipher.open(file.readBytes())) { readAt }.state
        assertEquals((day + 9..day + 36).toList(), seized.dayKeys.keys.toList())
        // Earlier days are gone: neither the file nor the vault has them, and no stored key hashes to them.
        (day until day + 9).forEach { d ->
            val old = requireNotNull(firstWindow[d])
            assertNull(vault.dayKey(d * 96, readAt))
            assertTrue(seized.dayKeys.values.none { it.contentEquals(old) })
            assertFalse(String(cipher.open(file.readBytes()), Charsets.ISO_8859_1).contains(String(old, Charsets.ISO_8859_1)))
        }
        // The days after the window are drawn later from the random source: the same seized state yields different
        // keys for day + 37 under two different sources, so no function of the state names them.
        val a = DayKeyWindow.roll(seized.dayKeys, readAt + 96, counterSource(11))[day + 37]
        val b = DayKeyWindow.roll(seized.dayKeys, readAt + 96, counterSource(99))[day + 37]
        assertTrue(a != null && b != null && !a.contentEquals(b))
        // The keys of the delivered days, which contacts hold, are the window's own.
        (9..27).forEach { assertArrayEquals(firstWindow[day + it], seized.dayKeys[day + it]) }
    }

    private fun counterSource(seed: Int): () -> ByteArray {
        var n = seed
        return { ByteArray(32) { i -> (n * 17 + i).toByte() }.also { n++ } }
    }

    @Test
    fun aMissingKeyIsCreatedAtOnceAfterALongAbsenceAndNeverBeforeItsDay() {
        val vault = vault()
        vault.ensureLoaded()
        val longAfter = epoch + 40 * 96L
        now = longAfter
        // The first advert after 40 days asks for today's key: the window rolls and is stored.
        val key = requireNotNull(vault.dayKey(longAfter, longAfter))
        assertEquals(32, key.size)
        assertEquals(Triple(day + 40, day + 67, 28), vault.windowInfo())
        // A day beyond the window has no key (it does not exist yet).
        assertNull(vault.dayKey(longAfter + 28 * 96L, longAfter))
    }

    @Test
    fun deliverLeaseRollsAndStoresTheWindowBeforeAnyKeyLeavesAndFailsClosedWhenItCannotBeStored() {
        val vault = vault()
        vault.ensureLoaded()
        val later = epoch + 3 * 96L
        // The lease of a later day needs keys that do not exist yet: they are created and stored first.
        val lease = requireNotNull(vault.deliverLease(nodeB, later) { true })
        assertEquals(day + 3, lease.firstDay)
        assertEquals(28, lease.leases.size)
        val stored = PresenceStateCodec.decode(cipher.open(file.readBytes())) { later }.state
        lease.leases.forEachIndexed { i, key -> assertArrayEquals(key, stored.dayKeys[day + 3 + i]) }
        // With the store failing nothing is sent and nothing recorded.
        val failing = vault(File(dir, "o.bin"), cipherThatFailsToSeal(), MemoryFloor(5))
        failing.ensureLoaded()
        assertNull(failing.deliverLease(nodeB, later + 5 * 96L) { true })
        assertNull(failing.contact(nodeB))
    }

    private fun cipherThatFailsToSeal(): AtRestCipher = object : AtRestCipher {
        private val inner = TestCipher()
        var sealed = 0
        override fun seal(plaintext: ByteArray): ByteArray {
            if (sealed++ >= 1) throw IllegalStateException("disk full")
            return inner.seal(plaintext)
        }

        override fun open(sealed: ByteArray): ByteArray = inner.open(sealed)
    }

    @Test
    fun aLeaseHolderDeletesOverDaysAtMaintenanceAndDoesNotKeepAnAlreadyOverLease() {
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        val holder = vault()
        holder.ensureLoaded()
        assertTrue(holder.storeLease(nodeA, leaseFrom(owner), epoch, 1L))
        assertEquals(28, requireNotNull(holder.contact(nodeA)?.presence).leasesByDay.size)
        now = epoch + 3 * 96L
        assertTrue(holder.maintain(epoch + 3 * 96L))
        val kept = requireNotNull(holder.contact(nodeA)?.presence).leasesByDay.keys
        assertEquals((day + 2..day + 27).toSet(), kept)
        // A lease that is already over when it arrives is not kept at all.
        val stale = DiscoveryExchange.PresenceLease(7, epoch - 60 * 96L, day - 60, List(28) { ByteArray(32) { 1 } })
        holder.storeLease(nodeB, stale, epoch + 3 * 96L, 5L)
        assertNull(holder.contact(nodeB)?.presence)
    }

    @Test
    fun aRevocationStartsAWholeNewWindowAndTheOldLeaseRecognisesNothing() {
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        val holder = vault()
        holder.ensureLoaded()
        holder.storeLease(nodeA, leaseFrom(owner), epoch, System.currentTimeMillis())
        val oldWindow = (0..27).map { owner.keyOf(epoch + it * 96L, epoch) }
        owner.recordDelivered(nodeB)
        assertTrue(owner.forget(nodeB))
        val newWindow = (0..27).map { owner.keyOf(epoch + it * 96L, epoch) }
        for (i in 0..27) assertFalse("day $i", oldWindow[i].contentEquals(newWindow[i]))
        assertEquals(Triple(day, day + 27, 28), owner.windowInfo())
    }

    // ----- vault: atomic delivery, key reset -----

    @Test
    fun deliverLeaseChecksRecordsAndBuildsFromOneState() {
        val vault = vault()
        vault.ensureLoaded()
        val lease = requireNotNull(vault.deliverLease(nodeB, epoch) { true })
        assertEquals(vault.generation(), lease.generation)
        val expected = PresenceStateCodec.decode(cipher.open(file.readBytes())) { epoch }.state
        assertArrayEquals(
            DiscoveryExchange.encode(requireNotNull(PresenceLeases.delivery(expected.dayKeys, vault.generation(), epoch))),
            DiscoveryExchange.encode(lease),
        )
        assertEquals(vault.generation(), vault.contact(nodeB)?.deliveredGeneration)
        // A later switch-off finds the delivery and revokes.
        assertTrue(vault.setVisible(nodeB, false))
    }

    @Test
    fun deliverLeaseSendsNothingWhenTheSwitchIsOffOrTheContactIsNoLongerVerified() {
        val vault = vault()
        vault.ensureLoaded()
        assertNull(vault.deliverLease(nodeB, epoch) { false })
        assertNull(vault.contact(nodeB))
        vault.setVisible(nodeB, false)
        assertNull(vault.deliverLease(nodeB, epoch) { true })
        assertEquals(0L, vault.contact(nodeB)?.deliveredGeneration ?: 0L)
    }

    @Test
    fun deliverLeaseSendsNothingWhileTheStoreIsUnreadable() {
        val first = vault()
        first.ensureLoaded()
        cipher.failing = true
        val second = vault()
        assertFalse(second.ensureLoaded())
        assertNull(second.deliverLease(nodeB, epoch) { true })
    }

    @Test
    fun resetKeyReplacesACorruptFileWithAFreshGeneration() {
        val first = vault()
        first.ensureLoaded()
        val oldKey = first.keyOf().copyOf()
        val oldGeneration = first.generation()
        file.writeBytes(byteArrayOf(9, 9, 9, 9, 9))
        val second = vault()
        assertFalse(second.ensureLoaded())
        assertTrue(second.resetKey())
        assertTrue(second.persistent)
        assertTrue(second.generation() > oldGeneration)
        assertFalse(oldKey.contentEquals(second.keyOf()))
        // The file is readable again and the next process loads the new keys.
        assertArrayEquals(second.keyOf(), vault().also { it.ensureLoaded() }.keyOf())
    }

    @Test
    fun resetKeyOnAHealthyVaultIsARevocation() {
        val vault = vault()
        vault.ensureLoaded()
        val oldKey = vault.keyOf().copyOf()
        val before = vault.generation()
        assertTrue(vault.resetKey())
        assertTrue(vault.generation() > before)
        assertFalse(oldKey.contentEquals(vault.keyOf()))
    }

    @Test
    fun theKeyHandedOutIsACopyThatARevocationCannotZeroUnderTheCaller() {
        val vault = vault()
        vault.ensureLoaded()
        val handedOut = vault.keyOf()
        val snapshot = handedOut.copyOf()
        cipher.failing = true
        vault.bump()
        assertArrayEquals(snapshot, handedOut)
    }

    // ----- vault: revocation -----

    @Test
    fun forgettingAContactThatReceivedALeaseStartsANewGeneration() {
        val vault = vault()
        vault.ensureLoaded()
        val keyBefore = vault.keyOf().copyOf()
        val before = vault.generation()
        vault.recordDelivered(nodeB)
        assertTrue(vault.forget(nodeB))
        assertTrue(vault.generation() > before)
        assertFalse(keyBefore.contentEquals(vault.keyOf()))
        assertNull(vault.contact(nodeB))
    }

    @Test
    fun forgettingAContactWithoutADeliveredLeaseChangesNothingOnTheWire() {
        val vault = vault()
        vault.ensureLoaded()
        val before = vault.generation()
        val keyBefore = vault.keyOf()
        vault.storeLease(nodeB, requireNotNull(PresenceLeases.delivery(DayKeyWindow.migrated(legacyP, epoch), 9, epoch)), epoch, 1L)
        assertFalse(vault.forget(nodeB))
        assertEquals(before, vault.generation())
        assertArrayEquals(keyBefore, vault.keyOf())
        assertNull(vault.contact(nodeB))
    }

    /**
     * Review 2026-10-27 (finding 14): keys of days that are over (this phone's own and its contacts' leases) stayed in the
     * store after a restart until the next daily maintenance, so a phone seized right after being switched on named more
     * past IDs than its window allows. They are dropped when the store is loaded, in memory and on disk.
     */
    @Test
    fun keysOfDaysThatAreOverAreDroppedWhenTheStoreIsLoaded() {
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        val first = vault()
        first.ensureLoaded()
        assertTrue(first.storeLease(nodeB, leaseFrom(owner), epoch, 1L))
        // Five days later the phone starts again.
        now = epoch + 5 * PresenceLeases.EPOCHS_PER_DAY
        val restarted = vault()
        assertTrue(restarted.ensureLoaded())
        val firstKept = DayKeyWindow.firstDay(now)
        assertEquals(firstKept, requireNotNull(restarted.windowInfo()).first)
        val leaseDays = requireNotNull(restarted.contact(nodeB)?.presence).leasesByDay.keys
        assertTrue(leaseDays.all { it >= PresenceLeases.dayOf(now) - 1 })
        // The file itself no longer holds them either.
        val onDisk = PresenceStateCodec.decode(cipher.open(file.readBytes())) { now }.state
        assertTrue(onDisk.dayKeys.keys.all { it >= firstKept })
        assertTrue(requireNotNull(onDisk.contacts.single { it.nodeId == nodeB }.presence).leasesByDay.keys.all { it >= PresenceLeases.dayOf(now) - 1 })
    }

    /**
     * Review 2026-10-27 (finding 3): blocking a verified contact did not touch the presence vault, so a blocked contact kept
     * recognising this phone nearby for the rest of its lease (up to four weeks).
     */
    @Test
    fun blockingAContactThatHoldsTheCurrentLeaseRevokesItAndNoNewLeaseGoesOut() {
        val vault = vault()
        vault.ensureLoaded()
        val keyBefore = vault.keyOf().copyOf()
        vault.recordDelivered(nodeB)
        val before = vault.generation()
        assertTrue(vault.revokeForBlocked(nodeB))
        assertTrue(vault.generation() > before)
        assertFalse("the blocked contact's keys no longer name this phone", keyBefore.contentEquals(vault.keyOf()))
        assertFalse(vault.canDeliver(nodeB))
        assertNull(vault.deliverLease(nodeB, epoch) { true })
        // Blocking again (already blocked) revokes nothing more.
        val after = vault.generation()
        assertFalse(vault.revokeForBlocked(nodeB))
        assertEquals(after, vault.generation())
    }

    /**
     * reviewer review 2026-10-27 (finding 5): a contact blocked while rotating IDs were off kept its lease for the whole run when
     * the block list could not be read at start. The revocation stays pending and is done once the list can be read, before
     * another key is handed out.
     */
    @Test
    fun aBlockedContactsLeaseIsRevokedOnceTheBlockListCanBeReadLaterInTheSameRun() {
        var clock = 1_000_000L
        val vault = PresenceVault(PresenceStore(file, cipher), floor, SecureRandom(), clockMs = { clock }, epochSource = { now })
        vault.ensureLoaded()
        vault.recordDelivered(nodeB)
        val before = vault.generation()
        var blockList: Set<NodeId>? = null
        vault.requestBlockedReconcile { blockList }
        assertTrue(vault.blockedReconcileOutstanding)
        assertEquals(before, vault.generation())
        blockList = setOf(nodeB)
        clock += 5_000L
        vault.dayKey(epoch, epoch)
        assertFalse(vault.blockedReconcileOutstanding)
        assertTrue(vault.generation() > before)
        assertFalse(vault.canDeliver(nodeB))
    }

    /** Security review 2026-10-27 (item 8): the start-up reconciliation never starts a new generation from an unreadable store. */
    @Test
    fun theStartupReconciliationOfBlockedPeopleDoesNotRevokeFromAnUnreadableStore() {
        val vault = vault()
        vault.ensureLoaded()
        vault.recordDelivered(nodeB)
        val broken = vault(sharedCipher = TestCipher().also { it.failing = true })
        val floorBefore = floor.value
        assertFalse(broken.revokeForBlocked(nodeB, failClosedWhenUnreadable = false))
        assertEquals(floorBefore, floor.value)
        // A block the user makes now still fails closed.
        assertTrue(broken.revokeForBlocked(nodeB))
        assertTrue(floor.value > floorBefore)
    }

    @Test
    fun blockingSomeoneWithoutACurrentLeaseLeavesEveryoneElsesRecognitionAlone() {
        val vault = vault()
        vault.ensureLoaded()
        val before = vault.generation()
        assertFalse("a stranger is not in the vault", vault.revokeForBlocked(nodeC))
        assertNull(vault.contact(nodeC))
        assertEquals(before, vault.generation())
        vault.recordDelivered(nodeB)
        vault.bump()
        val current = vault.generation()
        assertFalse("a lease of an older generation is already dead", vault.revokeForBlocked(nodeB))
        assertEquals(current, vault.generation())
    }

    /**
     * Design decision 2026-11-06 (all platforms): "Can see when I'm nearby" starts as not yet asked, which counts as off
     * (it replaced "default on"); off after a delivery revokes once.
     */
    @Test
    fun theSwitchStartsNotYetAskedCountsAsOffAndOffAfterDeliveryRevokesOnceAndNeverAgain() {
        val vault = vault()
        vault.ensureLoaded()
        assertFalse("not yet asked counts as off", vault.visibleToThem(nodeB))
        assertNull(vault.answer(nodeB))
        assertFalse(vault.canDeliver(nodeB))
        vault.recordDelivered(nodeB)
        assertNull("a record made for a delivery is not an answer", vault.answer(nodeB))
        val before = vault.generation()
        assertTrue(vault.setVisible(nodeB, false))
        assertFalse(vault.visibleToThem(nodeB))
        assertTrue(vault.generation() > before)
        val after = vault.generation()
        // Already off, and the delivered lease is of an older generation: no second bump.
        assertFalse(vault.setVisible(nodeB, false))
        assertEquals(after, vault.generation())
        // Back on: no bump either; the next session delivers a lease under the new generation.
        assertFalse(vault.setVisible(nodeB, true))
        assertTrue(vault.visibleToThem(nodeB))
        assertEquals(after, vault.generation())
    }

    /** A no for a contact not yet asked is kept as an answer; answers survive a restart; anyone else stays not yet asked. */
    @Test
    fun aNoIsKeptAsAnAnswerAndAnswersSurviveARestartWhileOthersStayNotYetAsked() {
        val vault = vault()
        vault.ensureLoaded()
        assertFalse("no lease was delivered: nothing to revoke", vault.setVisible(nodeA, false))
        assertEquals(false, vault.answer(nodeA))
        assertFalse(vault.setVisible(nodeB, true))
        assertEquals(true, vault.answer(nodeB))
        vault.recordDelivered(nodeC)
        val restarted = vault()
        assertTrue(restarted.ensureLoaded())
        assertEquals(false, restarted.answer(nodeA))
        assertFalse(restarted.visibleToThem(nodeA))
        assertEquals(true, restarted.answer(nodeB))
        assertTrue(restarted.visibleToThem(nodeB))
        assertNull("a delivery record is not an answer", restarted.answer(nodeC))
        assertFalse(restarted.visibleToThem(nodeC))
    }

    /**
     * AB3 R5: a store an older build wrote (flag byte 1 = on, 0 = off; its default was on, so every stored contact was
     * answered) keeps each answer.
     */
    @Test
    fun answersAnOlderBuildStoredKeepTheirMeaning() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.write("BPS1".toByteArray(Charsets.US_ASCII))
            out.writeByte(2)
            out.writeInt(4)
            out.writeByte(1)
            out.writeInt(day.toInt())
            out.write(ByteArray(32) { 3 })
            out.writeShort(2)
            for ((node, flag) in listOf(nodeA to 1, nodeB to 0)) {
                out.write(node.toByteArray())
                out.writeByte(flag)
                out.writeInt(0)
                out.writeInt(0)
            }
        }
        file.writeBytes(cipher.seal(bytes.toByteArray()))
        floor.value = 4
        val vault = vault()
        assertTrue(vault.ensureLoaded())
        assertEquals(true, vault.answer(nodeA))
        assertTrue(vault.visibleToThem(nodeA))
        assertEquals(false, vault.answer(nodeB))
        assertFalse(vault.visibleToThem(nodeB))
    }

    @Test
    fun resettingContactsRevokesOnlyWhenAnyLeaseWasDelivered() {
        val vault = vault()
        vault.ensureLoaded()
        vault.storeLease(nodeB, requireNotNull(PresenceLeases.delivery(DayKeyWindow.migrated(legacyP, epoch), 9, epoch)), epoch, 1L)
        val g = vault.generation()
        assertFalse(vault.resetContacts())
        assertEquals(g, vault.generation())
        assertEquals(0, vault.contactCount())
        vault.recordDelivered(nodeC)
        assertTrue(vault.resetContacts())
        assertTrue(vault.generation() > g)
        assertEquals(0, vault.contactCount())
    }

    @Test
    fun reconcilingDropsContactsForgottenWhileTheExperimentWasOff() {
        val vault = vault()
        vault.ensureLoaded()
        vault.recordDelivered(nodeB)
        vault.storeLease(nodeC, requireNotNull(PresenceLeases.delivery(DayKeyWindow.migrated(legacyP, epoch), 9, epoch)), epoch, 1L)
        val before = vault.generation()
        // nodeB (a delivered lease) is no longer verified, nodeC is still verified.
        assertTrue(vault.reconcile(setOf(nodeC)))
        assertTrue(vault.generation() > before)
        assertNull(vault.contact(nodeB))
        assertNotNull(vault.contact(nodeC))
        // Nothing stale any more: no further bump.
        val after = vault.generation()
        assertFalse(vault.reconcile(setOf(nodeC)))
        assertEquals(after, vault.generation())
    }

    @Test
    fun reconcilingAContactThatNeverReceivedALeaseChangesNothingOnTheWire() {
        val vault = vault()
        vault.ensureLoaded()
        vault.storeLease(nodeB, requireNotNull(PresenceLeases.delivery(DayKeyWindow.migrated(legacyP, epoch), 9, epoch)), epoch, 1L)
        val before = vault.generation()
        assertFalse(vault.reconcile(emptySet()))
        assertEquals(before, vault.generation())
        assertNull(vault.contact(nodeB))
    }

    @Test
    fun theWipeDeletesTheFileAndForgetsTheKeys() {
        val vault = vault()
        vault.ensureLoaded()
        assertTrue(file.exists())
        assertTrue(vault.wipe())
        assertFalse(file.exists())
        assertFalse(vault.persistent)
    }

    // ----- leases: replay and rollback -----

    @Test
    fun anOlderGenerationCannotRollBackAStoredLease() {
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        val holder = vault()
        holder.ensureLoaded()
        val first = leaseFrom(owner)
        assertTrue(holder.storeLease(nodeA, first, epoch, 1L))
        owner.bump()
        val second = leaseFrom(owner)
        assertTrue(second.generation > first.generation)
        assertTrue(holder.storeLease(nodeA, second, epoch, 2L))
        // A malicious contact (or a replay) hands back the old lease: ignored.
        assertFalse(holder.storeLease(nodeA, first, epoch, 3L))
        assertEquals(second.generation, requireNotNull(holder.contact(nodeA)?.presence).generation)
    }

    // ----- two phones: recognition and revocation end to end -----

    @Test
    fun aContactRecognisesTheOwnersIdsFromItsLeaseAndStopsAfterARevocation() {
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        val holder = vault()
        holder.ensureLoaded()
        // The owner (node A) delivers its lease to the holder in a mutual session.
        holder.storeLease(nodeA, leaseFrom(owner), epoch, System.currentTimeMillis())
        val table = RecognitionTable()
        table.rebuild(holder.leaseHolders(), epoch, System.currentTimeMillis())
        val role0 = own(owner, nodeA, epoch, 0)
        val role1 = own(owner, nodeA, epoch + 1, 1, epoch)
        val role2 = own(owner, nodeA, epoch, 2)
        assertEquals(nodeA, table.lookup(role0.rdi).single().contact)
        assertEquals(1, table.lookup(role1.rdi).single().role)
        assertTrue("viewer requests are never recognised", table.lookup(role2.rdi).isEmpty())
        // Outside the window around the learned skew (epoch +- 1) nothing matches.
        val farAway = own(owner, nodeA, epoch + 6, 0, epoch)
        assertTrue(table.lookup(farAway.rdi).isEmpty())
        // The owner forgets the holder after delivering: new keys make the old lease useless.
        owner.recordDelivered(nodeB)
        assertTrue(owner.forget(nodeB))
        val newRole0 = own(owner, nodeA, epoch, 0)
        assertFalse(role0.rdi.contentEquals(newRole0.rdi))
        assertTrue(table.lookup(newRole0.rdi).isEmpty())
    }

    @Test
    fun theOwnersIdsAfterADayRollStillMatchWhatTheHolderComputesFromItsLease() {
        // Day 3 of the lease: the owner's maintenance created and deleted keys; the holder's keys are the ones delivered.
        val owner = vault(File(dir, "owner.bin"), TestCipher(), MemoryFloor())
        owner.ensureLoaded()
        val holder = vault()
        holder.ensureLoaded()
        holder.storeLease(nodeA, leaseFrom(owner), epoch, 1L)
        val later = epoch + 3 * 96L + 40
        now = later
        owner.maintain(later)
        val table = RecognitionTable()
        table.rebuild(holder.leaseHolders(), later, 1L)
        assertEquals(nodeA, table.lookup(own(owner, nodeA, later, 0).rdi).single().contact)
    }

    @Test
    fun aVersion5IdOfAnotherPhoneNeverMatchesAndTwoContactsOnOneIdBothShow() {
        val a = vault(File(dir, "a.bin"), TestCipher(), MemoryFloor())
        a.ensureLoaded()
        val holder = vault()
        holder.ensureLoaded()
        holder.storeLease(nodeA, leaseFrom(a), epoch, System.currentTimeMillis())
        val table = RecognitionTable()
        table.rebuild(holder.leaseHolders(), epoch, System.currentTimeMillis())
        val stranger = RotatingDiscoveryIds.ownEntry(ByteArray(32) { 9 }, nodeC.toByteArray(), epoch, 0)
        assertTrue(table.lookup(stranger.rdi).isEmpty())
    }
}
