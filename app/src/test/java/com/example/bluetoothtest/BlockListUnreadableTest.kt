package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Design decision 2026-11-06 ("Unreadable block list"): the saved list that cannot be read keeps failing open (calls ring,
 * CF-CALL-24), but the person sees it and can reset it, and the app logs it once each time it becomes unreadable. A list
 * sealed under another key stands in for a damaged file or a lost Keystore key (they look the same to the app).
 */
class BlockListUnreadableTest {
    private lateinit var base: File
    private lateinit var file: File
    private val keyA = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 1 }, "AES") }
    private val keyB = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { 2 }, "AES") }
    private val logged = mutableListOf<String>()

    private fun fingerprint(seed: Int): IdentityFingerprint =
        IdentityFingerprint.fromPublicKey(byteArrayOf(seed.toByte(), 9, 9, 9, (seed * 5).toByte()))

    @Before
    fun setUp() {
        base = Files.createTempDirectory("block-list-unreadable").toFile()
        file = File(File(base, "no_backup/identity-verification"), BlockedPeople.FILE)
    }

    @After
    fun tearDown() {
        BlockedPeople.installForTest(null)
        PersonPolicy.resetForTest()
        base.deleteRecursively()
    }

    @Test
    fun anUnreadableListRingsEveryoneShowsTheNoteIsLoggedOnceAndResetMakesBlockingWorkAgain() {
        val context = FakePhone(File(base, "phone")).start()
        val person = fingerprint(1)
        BlockedPeopleStore(file, keyA).add(person)
        BlockedPeople.installForTest(BlockedPeopleStore(file, keyB, onUnreadable = { cause -> logged += cause }))
        PersonPolicy.install(context)

        // Fail open: the blocked person is not treated as blocked, call after call.
        repeat(3) { assertFalse(PersonPolicy.blocked(person)) }
        // The screens show the note and never "No one is blocked".
        val shown = BlockListView.of(BlockedPeople.list(context), BlockedPeople.isUnreadable(context))
        assertTrue(shown.showUnreadableNote)
        assertFalse(shown.showEmptyState)
        // A new Block cannot be saved and says why, so the person is offered Reset.
        assertEquals(BlockOutcome.LIST_UNREADABLE, BlockOutcome.of(BlockedPeople.block(context, fingerprint(2))))
        assertEquals("logged when it became unreadable, not on every check", 1, logged.size)

        // Reset: the note goes, the empty list says so, and blocking works again for calls.
        assertTrue(BlockedPeople.reset(context))
        val afterReset = BlockListView.of(BlockedPeople.list(context), BlockedPeople.isUnreadable(context))
        assertFalse(afterReset.showUnreadableNote)
        assertTrue(afterReset.showEmptyState)
        assertEquals(BlockOutcome.DONE, BlockOutcome.of(BlockedPeople.block(context, person)))
        assertTrue(PersonPolicy.blocked(person))
    }

    @Test
    fun aListThatBecomesUnreadableAgainIsLoggedAgain() {
        BlockedPeopleStore(file, keyA).add(fingerprint(1))
        val store = BlockedPeopleStore(file, keyB, onUnreadable = { cause -> logged += cause })
        assertTrue(store.isUnreadable())
        assertTrue(store.isUnreadable())
        assertEquals(1, logged.size)
        assertTrue(store.reset())
        assertEquals(BlockResult.BLOCKED, store.add(fingerprint(2)))
        // Damaged on disk later: a new episode, logged once more.
        file.writeBytes(byteArrayOf(1, 2, 3))
        store.dropCache()
        assertTrue(store.isUnreadable())
        assertEquals(2, logged.size)
    }

    @Test
    fun aReadableListShowsItsPeopleWithoutTheNote() {
        val shown = BlockListView.of(listOf(BlockedPerson(fingerprint(1), 1L)), unreadable = false)
        assertFalse(shown.showUnreadableNote)
        assertFalse(shown.showEmptyState)
    }
}
