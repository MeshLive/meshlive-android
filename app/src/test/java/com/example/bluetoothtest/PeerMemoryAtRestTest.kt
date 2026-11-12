package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Audit 2026-11-10 SEC-05 and the review of 040a8c2e: what the phone learned about the peers it met (link rates per peer,
 * reported people, hidden stream routes, muted chats, missed-call tokens) was kept in plain preferences under the peers'
 * node prefixes or fingerprints, with times. These flows check what an updated phone keeps, what a seized phone's files
 * show, that a busy Keystore loses no choice, and that nothing is written once the emergency wipe started.
 */
class PeerMemoryAtRestTest {
    private lateinit var dir: File
    private val key = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32) { (it * 3).toByte() }, "AES") }
    @Volatile private var now = 1_800_000_000_000L
    private val peer = "a1b2c3d4e5f6"
    private val day = LinkCapacityPolicy.SUSTAINED_TTL_MS

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("peer-memory").toFile()
        WipeGuard.resetForTest()
        SealedRecordsWorker.resetForTest()
        SealedRecordsRegistry.resetForTest()
        SealedRecords.cipher = { key }
    }

    @After
    fun tearDown() {
        WipeGuard.resetForTest()
        SealedRecordsWorker.resetForTest()
        SealedRecordsRegistry.resetForTest()
        dir.deleteRecursively()
    }

    private fun idle() = SealedRecordsWorker.awaitIdleForTest()

    /** A Keystore that can be made busy, or made to hold a seal until released. */
    private class TestCipher(private val inner: AtRestCipher) : AtRestCipher {
        @Volatile var busy = false
        @Volatile var hold: CountDownLatch? = null
        val sealing = CountDownLatch(1)
        override fun seal(plaintext: ByteArray): ByteArray {
            if (busy) throw IllegalStateException("busy")
            hold?.let {
                sealing.countDown()
                it.await(10, TimeUnit.SECONDS)
            }
            return inner.seal(plaintext)
        }
        override fun open(sealed: ByteArray): ByteArray = if (busy) throw IllegalStateException("busy") else inner.open(sealed)
    }

    private fun records(
        file: File = File(dir, LinkCapacity.PEER_RECORDS_FILE),
        cipher: AtRestCipher = key,
        maxEntries: Int? = LinkCapacity.PEER_RECORDS_MAX,
        maxAgeMs: Long? = day,
        legacy: LegacyPlain? = null,
    ) = SealedRecords(
        SealedTextFile(file, { cipher }), maxEntries, maxAgeMs, legacy, clock = { now }, retryBaseMs = 60_000, log = {},
    ).also { it.start() }

    private fun readable(file: File, text: String) = String(file.readBytes(), Charsets.ISO_8859_1).contains(text)

    private fun sealedText(file: File, cipher: AtRestCipher = key) = requireNotNull(SealedTextFile(file, { cipher }).read())

    private class MapLegacy(val plain: MutableMap<String, SealedRecords.Record>, override val cache: Boolean = false) : LegacyPlain {
        @Volatile var failCleans = 0
        override fun read() = plain.toMap()
        override fun clean(): Boolean {
            if (failCleans > 0) {
                failCleans--
                return false
            }
            plain.clear()
            return true
        }
    }

    private fun withPhone(body: (android.content.Context, FakePhone) -> Unit) {
        val phone = FakePhone(Files.createTempDirectory("peer-memory-phone").toFile())
        try {
            BlockedPeople.installForTest(BlockedPeopleStore(File(phone.root, "blocked.bin"), phone.atRestCipher))
            PersonPolicy.resetForTest()
            body(phone.start(), phone)
        } finally {
            BlockedPeople.installForTest(null)
            PersonPolicy.resetForTest()
            phone.root.deleteRecursively()
        }
    }

    @Test
    fun anUpdatedPhoneKeepsItsPeerLinkRatesSealedAndItsOwnCapacityInPlace() {
        withPhone { context, phone ->
            val realNow = System.currentTimeMillis()
            val prefs = phone.preferences("link_capacity")
            val rate = LinkCapacity.sustainedKeyName("BLE_GATT", "call", peer, buildKeyed = false)
            val oldRate = LinkCapacity.sustainedKeyName("BLE_GATT", "call", "0badc0ffee00", buildKeyed = false)
            val report = "BLE_GATT.peerlink.$peer"
            prefs.edit()
                .putInt(rate, 640_000).putLong("$rate.at", realNow - 60_000)
                .putInt(oldRate, 300_000).putLong("$oldRate.at", realNow - day - 1)
                .putInt(report, 65).putLong("$report.at", realNow - 30_000)
                .putInt("BLE_GATT.probe", 900).putLong("BLE_GATT.probeAt", realNow)
                .commit()

            LinkCapacity.openPeerRecords(context)
            idle()

            assertEquals("this phone's own capacity stays", 900, prefs.getInt("BLE_GATT.probe", 0))
            assertTrue("no plain key names a peer", prefs.all.keys.none { LinkCapacity.isPeerKey(it) })
            val file = File(phone.noBackupDir, LinkCapacity.PEER_RECORDS_FILE)
            assertFalse("the sealed file shows no peer prefix", readable(file, peer))
            assertFalse("the day-old rate was not carried over", sealedText(file, phone.atRestCipher).contains("0badc0ffee00"))
            // The next process reads them back through the one store of that file.
            SealedRecordsRegistry.resetForTest()
            val restarted = LinkCapacity.openPeerRecords(context)
            idle()
            assertEquals(640_000, LinkCapacity.sustainedBpsOf(requireNotNull(restarted.get(rate)), false, 0L, System.currentTimeMillis()))
            assertEquals("65", restarted.get(report)?.value)
            assertTrue("a second attach gets the same store", restarted === LinkCapacity.openPeerRecords(context))
        }
    }

    @Test
    fun aRateIsDeletedFromTheFileOnceItCanNoLongerBeUsedEvenWithoutAnotherChange() {
        val file = File(dir, LinkCapacity.PEER_RECORDS_FILE)
        records(file).put(LinkCapacity.sustainedKeyName("BLE_COC", "stream", peer, buildKeyed = false), "500000")
        idle()
        assertTrue(sealedText(file).contains(peer))
        now += day
        records(file) // the next start, nothing changed by the app
        idle()
        assertFalse("an expired rate leaves the disk at load", sealedText(file).contains(peer))
    }

    @Test
    fun theLearnedRatesStayBoundedOldestFirst() {
        val records = records()
        repeat(LinkCapacity.PEER_RECORDS_MAX + 40) { index ->
            now += 1
            records.put("BLE_GATT.peerlink.%012x".format(index), "$index")
        }
        idle()
        val restarted = records()
        idle()
        assertEquals(LinkCapacity.PEER_RECORDS_MAX, restarted.keys().size)
        assertNull("the oldest go first", restarted.get("BLE_GATT.peerlink.%012x".format(0)))
        assertNotNull(restarted.get("BLE_GATT.peerlink.%012x".format(LinkCapacity.PEER_RECORDS_MAX + 39)))
    }

    @Test
    fun aBuildKeyedRateIsUsedOnlyByTheBuildThatLearnedIt() {
        val record = SealedRecords.Record("420000,77", now - 1_000)
        assertEquals(420_000, LinkCapacity.sustainedBpsOf(record, buildKeyed = true, build = 77, nowMs = now))
        assertNull(LinkCapacity.sustainedBpsOf(record, buildKeyed = true, build = 78, nowMs = now))
        assertEquals(420_000, LinkCapacity.sustainedBpsOf(record, buildKeyed = false, build = 78, nowMs = now))
    }

    /** A Keystore busy for a moment: the stored memory is never replaced, and changes made meanwhile land in order later. */
    @Test
    fun aMemoryThatCannotBeOpenedNowIsNeverWrittenOverAndItsChangesLandLater() {
        val file = File(dir, "memory.bin")
        records(file, maxAgeMs = null).apply {
            put("muted:$peer", "")
            put("muted:0000000000aa", "")
        }
        idle()
        val cipher = TestCipher(key).apply { busy = true }
        val store = records(file, cipher, maxAgeMs = null)
        store.put("muted:ffffffffffff", "")
        store.remove(listOf("muted:0000000000aa"))
        idle()
        assertFalse(store.isLoaded())
        assertTrue("the stored records are untouched", sealedText(file).contains("0000000000aa"))
        cipher.busy = false
        store.kickForTest()
        idle()
        assertTrue(store.isDurable())
        val text = sealedText(file)
        assertTrue(text.contains(peer) && text.contains("ffffffffffff"))
        assertFalse("a removal made meanwhile holds", text.contains("0000000000aa"))
    }

    /** A file that opens but cannot be written now: the change is retried until it is on the disk. */
    @Test
    fun aChoiceWhoseWriteFailsIsRetriedUntilItIsSaved() {
        val file = File(dir, "memory.bin")
        val cipher = TestCipher(key)
        val store = records(file, cipher, maxAgeMs = null)
        idle()
        cipher.busy = true
        store.put("muted:$peer", "")
        idle()
        assertFalse("not saved yet", store.isDurable())
        cipher.busy = false
        store.kickForTest()
        idle()
        assertTrue(store.isDurable())
        assertTrue(sealedText(file).contains(peer))
    }

    /** Review finding 7: an unmute made while the plain copy is still on the disk is never undone by a later import. */
    @Test
    fun anUnmuteHoldsWhileAnEarlierBuildsPlainListCouldNotBeDeleted() {
        val file = File(dir, ChatMute.FILE)
        val legacy = MapLegacy(mutableMapOf(peer to SealedRecords.Record("", now))).apply { failCleans = 1_000 }
        val cipher = TestCipher(key).apply { busy = true } // the first sealed write fails too
        val store = records(file, cipher, maxEntries = null, maxAgeMs = null, legacy = legacy)
        idle()
        cipher.busy = false
        store.kickForTest()
        idle()
        assertNotNull("the old mute was carried over", store.get(peer))
        store.remove(listOf(peer))
        idle()
        assertNull(store.get(peer))
        assertFalse("the unmute is on the disk", sealedText(file).lines().any { it.endsWith("\t$peer\t") })
        // The next process: the plain list is still there, but the sealed file says it was imported.
        SealedRecordsWorker.resetForTest()
        val restarted = records(file, key, maxEntries = null, maxAgeMs = null, legacy = legacy)
        idle()
        assertNull("the unmute is not undone", restarted.get(peer))
        // Once the plain copy can be deleted, it is.
        SealedRecordsWorker.resetForTest()
        legacy.failCleans = 0
        records(file, key, maxEntries = null, maxAgeMs = null, legacy = legacy)
        idle()
        assertTrue(legacy.plain.isEmpty())
    }

    /** Review finding 7, before the memory is read: an unmute made then holds against the plain copy imported at load. */
    @Test
    fun anUnmuteMadeBeforeTheMemoryIsReadIsNotUndoneByThePlainCopy() {
        val file = File(dir, ChatMute.FILE)
        records(file, maxEntries = null, maxAgeMs = null).put("ffffffffffff", "")
        idle()
        val legacy = MapLegacy(mutableMapOf(peer to SealedRecords.Record("", now)))
        val cipher = TestCipher(key).apply { busy = true }
        val store = records(file, cipher, maxEntries = null, maxAgeMs = null, legacy = legacy)
        idle()
        store.remove(listOf(peer)) // the user unmutes while the Keystore is busy
        cipher.busy = false
        store.kickForTest()
        idle()
        assertNull(store.get(peer))
        assertNotNull(store.get("ffffffffffff"))
        assertFalse(sealedText(file).contains(peer))
        assertTrue("the plain copy is gone", legacy.plain.isEmpty())
    }

    /** Re-review S2: a learned-rate reset made before the memory is read clears the stored and the plain rates too. */
    @Test
    fun aRateResetBeforeTheMemoryIsReadClearsTheStoredRatesOnceItIsRead() {
        withPhone { context, phone ->
            val file = File(phone.noBackupDir, LinkCapacity.PEER_RECORDS_FILE)
            val rate = LinkCapacity.sustainedKeyName("BLE_GATT", "call", peer, buildKeyed = false)
            val plainRate = LinkCapacity.sustainedKeyName("BLE_GATT", "stream", "0badc0ffee00", buildKeyed = false)
            records(file, phone.atRestCipher, maxAgeMs = null).put(rate, "640000", System.currentTimeMillis())
            idle()
            phone.preferences("link_capacity").edit().putInt(plainRate, 500_000).putLong("$plainRate.at", System.currentTimeMillis()).commit()
            val cipher = TestCipher(phone.atRestCipher).apply { busy = true }
            SealedRecords.cipher = { cipher }
            val store = LinkCapacity.openPeerRecords(context)
            idle()
            assertEquals("no false count while the memory loads", "pending", LinkCapacity.clearSustainedRates())
            cipher.busy = false
            store.kickForTest()
            idle()
            assertEquals("0", LinkCapacity.clearSustainedRates())
            val text = sealedText(file, phone.atRestCipher)
            assertFalse(text.contains(peer))
            assertFalse("the plain rate was not brought back", text.contains("0badc0ffee00"))
        }
    }

    /** Re-review S3: a reader that must not act on a partial memory runs once it is read, and at once when it is. */
    @Test
    fun aReaderWaitingForTheMemoryRunsOnceItIsRead() {
        val file = File(dir, "memory.bin")
        records(file, maxAgeMs = null).put("muted:$peer", "")
        idle()
        val cipher = TestCipher(key).apply { busy = true }
        val store = records(file, cipher, maxAgeMs = null)
        idle()
        var seen = 0
        var mutedWhenRun = false
        store.whenLoaded {
            seen++
            mutedWhenRun = store.get("muted:$peer") != null
        }
        assertEquals("not before the memory is read", 0, seen)
        cipher.busy = false
        store.kickForTest()
        idle()
        assertEquals(1, seen)
        assertTrue("it sees the stored mute", mutedWhenRun)
        store.whenLoaded { seen++ }
        assertEquals("at once when already read", 2, seen)
    }

    /** Re-review S6: a plain copy whose deletion did not reach the disk is deleted again, checked on the disk. */
    @Test
    fun aPlainListWhoseDeletionFailedOnTheDiskIsDeletedOnTheNextTry() {
        withPhone { context, phone ->
            val person = IdentityFingerprint.fromPublicKey(byteArrayOf(4, 4, 4))
            val settings = phone.preferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES)
            settings.edit().putStringSet("reported_people", mutableSetOf(person.hex)).putBoolean("unrelated_setting", true).commit()
            val xml = File(phone.dataDir, "shared_prefs/${CallFeatureDefaults.APP_SETTINGS_PREFERENCES}.xml")
            assertTrue(xml.readText().contains("reported_people"))
            settings.failCommits = true
            PeerMemoryStartup.run(context)
            idle()
            assertTrue("the failed write left it on the disk", xml.readText().contains("reported_people"))
            assertTrue("the sealed copy hides them", LocalReporting.hidden(context, person.hex))
            settings.failCommits = false
            requireNotNull(SealedRecordsRegistry.openedForTest(File(phone.noBackupDir, LocalReporting.FILE))).kickForTest()
            idle()
            assertFalse("gone from the disk", xml.readText().contains("reported_people"))
            assertTrue(xml.readText().contains("unrelated_setting"))
            assertTrue(LocalReporting.hidden(context, person.hex))
        }
    }

    /** Re-review N2: while a wipe is pending (it resumes at launch), no memory is opened, read or written. */
    @Test
    fun noMemoryOpensWhileAWipeIsPending() {
        withPhone { context, phone ->
            File(phone.noBackupDir, EmergencyWipe.PENDING_MARKER).writeText("1")
            ChatMute.setMuted(context, peer.repeat(3).take(32), muted = true)
            idle()
            assertFalse(ChatMute.isMuted(context, peer.repeat(3).take(32)))
            assertFalse(File(phone.noBackupDir, ChatMute.FILE).exists())
        }
    }

    /** Re-review S6: after a wipe resumed at launch completes, the app goes on in this process with working, empty memories. */
    @Test
    fun theMemoriesWorkAgainAfterAWipeResumedAtLaunchCompletes() {
        val file = File(dir, ChatMute.FILE)
        WipeGuard.begin()
        assertTrue(SealedRecordsWorker.drainForWipe(1_000))
        assertFalse("nothing is kept during the wipe", SealedRecordsRegistry.open(file, null, null, { null }).put(peer, ""))
        SealedRecordsRegistry.clearForWipe()
        WipeGuard.reopenAfterCompletedResume()
        val store = SealedRecordsRegistry.open(file, null, null, { null })
        assertTrue(store.put(peer, ""))
        idle()
        assertTrue(sealedText(file).contains(peer))
    }

    /** Re-review S1: the keys go first; the writers are waited for after the sessions end and before any file is deleted. */
    @Test
    fun theWipeDeletesTheKeysBeforeWaitingForWritersAndTheFilesAfter() {
        val events = ArrayList<String>()
        val environment = object : WipeEnvironment {
            override fun writePendingMarker() { events += "marker" }
            override fun clearPendingMarker() { events += "unmark" }
            override fun preservedFiles(): Set<File> = emptySet()
            override fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long): TerminatorOutcome {
                events += "terminators"
                return TerminatorOutcome(0, 0, false)
            }
            override fun keystoreAliases(): Collection<String> = emptyList()
            override fun deleteKeystoreAlias(alias: String) { events += "keys" }
            override fun preferenceNames(): Collection<String> = emptyList()
            override fun clearPreferences(name: String) { events += "files" }
            override fun storageRoots(): List<File> = emptyList<File>().also { events += "files" }
            override fun resetInMemoryState() { events += "reset" }
            override fun cancelNotifications() = Unit
            override fun drainBackgroundWriters(timeoutMs: Long): Boolean {
                events += "drain"
                return true
            }
        }
        assertTrue(EmergencyWipe.run(environment, WipeReason.DEBUG_CONTROL).complete)
        val order = events.fold(ArrayList<String>()) { acc, event -> if (acc.lastOrNull() != event) acc += event; acc }
        assertEquals(listOf("marker", "keys", "terminators", "drain", "files", "reset", "keys", "files", "unmark"), order)
    }

    /** Review finding 2: explicit choices are never evicted, whatever their number. */
    @Test
    fun everyReportAndHiddenStreamOfAnEarlierBuildIsKept() {
        val plain = (0 until 3_000).associate { "person:%040x".format(it) to SealedRecords.Record("", now) }.toMutableMap()
        val legacy = MapLegacy(plain)
        val store = records(File(dir, LocalReporting.FILE), maxEntries = null, maxAgeMs = null, legacy = legacy)
        idle()
        assertEquals(3_000, store.keys().size)
        assertTrue("the plain copy is gone only after sealing", legacy.plain.isEmpty())
        val restarted = records(File(dir, LocalReporting.FILE), maxEntries = null, maxAgeMs = null)
        idle()
        assertEquals(3_000, restarted.keys().size)
    }

    /** Review finding 3: a write already running when the wipe starts finishes before the wipe deletes anything; none later. */
    @Test
    fun theWipeWaitsForARunningWriteAndNothingIsWrittenAfterIt() {
        val cipher = TestCipher(key)
        val first = records(File(dir, "first.bin"), cipher)
        idle()
        val second = records(File(dir, "second.bin"))
        idle()
        cipher.hold = CountDownLatch(1)
        first.put("BLE_GATT.peerlink.$peer", "65")
        assertTrue("the worker is inside the seal", cipher.sealing.await(5, TimeUnit.SECONDS))
        second.put("BLE_GATT.peerlink.$peer", "66") // queued behind it

        WipeGuard.begin()
        var drained = false
        val wipe = Thread { drained = SealedRecordsWorker.drainForWipe(5_000) }.apply { start() }
        wipe.join(200)
        assertTrue("the wipe waits for the running write", wipe.isAlive)
        cipher.hold?.countDown()
        wipe.join(5_000)
        assertTrue(drained)
        // The wipe deletes the files now; nothing recreates them.
        File(dir, "first.bin").delete()
        assertFalse("the queued write did nothing", File(dir, "second.bin").exists())
        assertFalse(first.put("BLE_GATT.peerlink.$peer", "67"))
        Thread.sleep(100)
        assertFalse(File(dir, "first.bin").exists())
    }

    @Test
    fun aWipeWhoseWritersDoNotStopInTimeStaysIncomplete() {
        val environment = object : WipeEnvironment {
            override fun writePendingMarker() = Unit
            override fun clearPendingMarker() = Unit
            override fun preservedFiles(): Set<File> = emptySet()
            override fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long) = TerminatorOutcome(0, 0, false)
            override fun keystoreAliases(): Collection<String> = emptyList()
            override fun deleteKeystoreAlias(alias: String) = Unit
            override fun preferenceNames(): Collection<String> = emptyList()
            override fun clearPreferences(name: String) = Unit
            override fun storageRoots(): List<File> = emptyList()
            override fun resetInMemoryState() = Unit
            override fun cancelNotifications() = Unit
            override fun drainBackgroundWriters(timeoutMs: Long) = false
        }
        assertFalse("it resumes at the next launch", EmergencyWipe.run(environment, WipeReason.DEBUG_CONTROL).complete)
    }

    @Test
    fun reportedPeopleAndHiddenRoutesOfAnEarlierBuildStayHiddenAndLeaveThePlainSettingsAtStartup() {
        withPhone { context, phone ->
            val person = IdentityFingerprint.fromPublicKey(byteArrayOf(9, 8, 7))
            val fixed = DiscoveryAnnouncement(
                nodeIdPrefix = bytesOf(peer),
                flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
                capabilitySummary = 1,
                streamToken = StreamRouteToken.forAdvertiser(7, bytesOf(peer)),
                routeCost = 0,
                sequence = 0,
                version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
            )
            val settings = phone.preferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES)
            settings.edit()
                .putStringSet("reported_people", mutableSetOf(person.hex))
                .putStringSet("hidden_stream_routes", mutableSetOf(LocalReportPolicy.routeKey(fixed)))
                .putStringSet("reported_streams", mutableSetOf("7"))
                .putBoolean("unrelated_setting", true)
                .commit()

            PeerMemoryStartup.run(context) // the app starts; nobody opens Report
            idle()
            assertTrue(settings.all.keys.none { it in setOf("reported_people", "hidden_stream_routes", "reported_streams") })
            assertTrue("other settings stay", settings.getBoolean("unrelated_setting", false))
            assertTrue("the reported person stays hidden", LocalReporting.hidden(context, person.hex))
            assertTrue("the hidden route stays hidden", LocalReporting.hiddenStream(context, fixed, "radio"))
            val file = File(phone.noBackupDir, LocalReporting.FILE)
            assertFalse("no fingerprint is readable", readable(file, person.hex.take(12)))
            assertFalse("no route ID is readable", readable(file, peer))

            // The emergency wipe forgets them: the process memory as well as the files.
            SealedRecordsRegistry.clearForWipe()
            phone.noBackupDir.deleteRecursively()
            assertFalse(LocalReporting.hidden(context, person.hex))
            assertFalse(LocalReporting.hiddenStream(context, fixed, "radio"))
        }
    }

    @Test
    fun aMutedChatStaysMutedAfterTheUpdateEvenWithChatsUnusedAndItsIdIsNotInPlainStorage() {
        withPhone { context, phone ->
            val thread = "cd".repeat(NodeId.BYTE_COUNT)
            val other = "ef".repeat(NodeId.BYTE_COUNT)
            phone.preferences("chat_mute").edit().putStringSet("muted", mutableSetOf(thread)).commit()

            PeerMemoryStartup.run(context)
            idle()
            assertTrue("the plain list is gone", phone.preferences("chat_mute").all.isEmpty())
            assertTrue(ChatMute.isMuted(context, thread.uppercase()))
            ChatMute.setMuted(context, other, muted = true)
            ChatMute.setMuted(context, thread, muted = false)
            idle()
            assertFalse(ChatMute.isMuted(context, thread))
            assertTrue(ChatMute.isMuted(context, other))
            val file = File(phone.noBackupDir, ChatMute.FILE)
            assertFalse(readable(file, other.take(12)))

            SealedRecordsRegistry.clearForWipe()
            phone.noBackupDir.deleteRecursively()
            assertFalse("a wiped phone has no muted chats", ChatMute.isMuted(context, other))
        }
    }

    @Test
    fun anEarlierBuildsMissedCallTokensAreKeptOnlyWhileTheirNotificationShows() {
        val records = MissedCallNotification.plainRecords(
            mapOf(
                "token:missed:$peer" to "t-1", "count:missed:$peer" to 3,
                "token:missed:0badc0ffee00" to "t-2", "count:missed:unknown" to 2,
            ),
            shown = mapOf("missed:$peer" to now - 5_000, "missed:unknown" to now - 9_000),
        )
        assertEquals(SealedRecords.Record("t-1,3", now - 5_000), records["missed:$peer"])
        assertEquals(",2", records["missed:unknown"]?.value)
        assertNull("a dismissed caller's token is not carried over", records["missed:0badc0ffee00"])
    }

    /** Review finding 9: the disk copy is checked, including Android's backup file, not the in-memory cache. */
    @Test
    fun plainPreferencesCountAsCleanOnlyWhenNoCopyOnTheDiskNamesThem() {
        val xml = File(dir, "app.xml")
        val backup = File(dir, "app.xml.bak")
        xml.writeText("<map><boolean name=\"unrelated_setting\" value=\"true\" /></map>")
        assertTrue(PlainPreferences.clean(listOf(xml, backup)) { it == "reported_people" })
        backup.writeText("<map><set name=\"reported_people\"><string>$peer</string></set></map>")
        assertFalse(PlainPreferences.clean(listOf(xml, backup)) { it == "reported_people" })
        backup.delete()
        xml.writeText("<map><int name=\"BLE_GATT.peerlink.$peer\" value=\"65\" /></map>")
        assertFalse(PlainPreferences.clean(listOf(xml, backup), LinkCapacity::isPeerKey))
    }

    private fun bytesOf(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
