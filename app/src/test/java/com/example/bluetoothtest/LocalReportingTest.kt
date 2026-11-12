package com.example.bluetoothtest

import java.io.File
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class LocalReportingTest {
    @Test fun reportingBlocksAndHidesTheSamePersonWithoutHidingOtherPeople() {
        val root = Files.createTempDirectory("local-report").toFile()
        try {
            val cipher = AesGcmAtRestCipher { SecretKeySpec(ByteArray(32), "AES") }
            val file = File(root, "blocked.bin")
            val person = IdentityFingerprint.fromPublicKey(byteArrayOf(1, 2, 3))
            val other = IdentityFingerprint.fromPublicKey(byteArrayOf(4, 5, 6))
            var hidden = emptySet<String>()
            fun report() = LocalReportPolicy.report({ BlockedPeopleStore(file, cipher).add(person) }) {
                hidden = hidden + person.hex
                true
            }
            assertTrue(report())
            assertTrue(BlockedPeopleStore(file, cipher).contains(person))
            assertFalse(BlockedPeopleStore(file, cipher).contains(other))
            assertTrue(LocalReportPolicy.hidden(person.hex, hidden))
            assertTrue(LocalReportPolicy.hidden(person.nodeId.toString(), hidden))
            assertTrue(LocalReportPolicy.hidden(person.nodePrefixHex, hidden))
            assertFalse(LocalReportPolicy.hidden(other.hex, hidden))
            assertFalse(LocalReportPolicy.hidden("", hidden))
            assertFalse(LocalReportPolicy.hidden(person.hex.take(4), hidden))
            assertTrue(report()) // already blocked is still a successful local report
            BlockedPeopleStore(file, cipher).remove(person)
            assertTrue(LocalReportPolicy.hidden(person.hex, hidden)) // unblocking is not unreporting
        } finally { root.deleteRecursively() }
    }

    @Test fun aFailedBlockNeverHidesOrClaimsSuccess() {
        for (failure in listOf(BlockResult.FULL, BlockResult.STORE_UNREADABLE, BlockResult.UNAVAILABLE)) {
            var hidden = false
            assertFalse(LocalReportPolicy.report({ failure }) { hidden = true; true })
            assertFalse(hidden)
        }
        assertFalse(LocalReportPolicy.report({ BlockResult.BLOCKED }) { false })
    }

    private fun bytesOf(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun stream(prefix: ByteArray, logical: Int, cost: Int = 0, rotating: Boolean = false) = DiscoveryAnnouncement(
        nodeIdPrefix = prefix,
        flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
        capabilitySummary = 1,
        streamToken = StreamRouteToken.forAdvertiser(logical, prefix),
        routeCost = cost,
        sequence = 0,
        version = if (rotating) DiscoveryAnnouncement.ROTATING_VERSION else DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
    )

    /** A phone with a block list sealed by a JVM cipher (production uses the Keystore); [PersonPolicy] starts empty. */
    private fun withPhone(body: (android.content.Context, FakePhone) -> Unit) {
        val phone = FakePhone(Files.createTempDirectory("local-report-phone").toFile())
        try {
            BlockedPeople.installForTest(BlockedPeopleStore(File(phone.root, "blocked.bin"), AesGcmAtRestCipher { SecretKeySpec(ByteArray(32), "AES") }))
            PersonPolicy.resetForTest()
            body(phone.start(), phone)
        } finally {
            BlockedPeople.installForTest(null)
            PersonPolicy.resetForTest()
            phone.root.deleteRecursively()
        }
    }

    /** AB3 (the rule the Watch list applies to blocks): a report keeps hiding the person after an unblock, never a route by itself. */
    @Test fun aReportKeepsHidingThePersonAfterAnUnblockButKeepsNoRoute() {
        withPhone { context, _ ->
            val person = IdentityFingerprint.fromPublicKey(byteArrayOf(1, 2, 3))
            assertTrue(LocalReporting.report(context, person))
            assertTrue(BlockedPeople.unblock(context, person))
            assertTrue("the report keeps hiding them", LocalReporting.hidden(context, person.hex))
            // Their own direct route (attributed by its prefix) stays hidden; another sender's route with the same token shows.
            val theirs = stream(bytesOf(person.nodePrefixHex), logical = 42)
            val genuine = stream(bytesOf("bbbbbbbbbbbb"), logical = 42)
            val shown = CallBlocking.hideBlockedStreams(listOf(theirs, genuine), { it }, LocalReporting.prefixes(context), { null })
                .filterNot { LocalReporting.hiddenStream(context, it, "radio") }
            assertEquals(listOf(genuine), shown)
        }
    }

    /**
     * AB3 spoof hole: someone reported while heard through a relay, who copied a genuine stream's token, cannot make this
     * phone hide the genuine stream on another relay or heard directly; the route the report was made on is hidden only
     * for this run (the viewer left it when its source authenticated as the reported person).
     */
    @Test fun aReportMadeThroughARelayCannotHideAGenuineStreamWithTheSameToken() {
        withPhone { context, _ ->
            val person = IdentityFingerprint.fromPublicKey(byteArrayOf(4, 5, 6))
            val watched = stream(bytesOf("0102030405aa"), logical = 42, cost = 1)
            assertTrue(LocalReporting.report(context, person))
            PersonPolicy.hideStream(watched)
            assertTrue(PersonPolicy.streamHidden(watched))
            val genuineViaOtherRelay = stream(bytesOf("0102030405bb"), logical = 42, cost = 1)
            val genuineDirect = stream(bytesOf("a1b2c3d4e5f6"), logical = 42)
            val rows = listOf(genuineViaOtherRelay, genuineDirect)
            val shown = CallBlocking.hideBlockedStreams(rows, { it }, LocalReporting.prefixes(context), { null }, PersonPolicy::streamHidden)
                .filterNot { LocalReporting.hiddenStream(context, it, "radio") }
            assertEquals(rows, shown)
            // The app restarts: only the person stays hidden, the route the report was made on shows again.
            PersonPolicy.resetForTest()
            assertFalse(PersonPolicy.streamHidden(watched))
            assertFalse(LocalReporting.hiddenStream(context, watched, "radio"))
            assertTrue(LocalReporting.hidden(context, person.hex))
        }
    }

    /**
     * AB3: "Hide this livestream" keeps a route only when the viewer's hop proved its fixed ID; otherwise only that
     * advertiser's radio address is hidden, until the app restarts, so a copy cannot hide the genuine stream.
     */
    @Test fun aHiddenLivestreamIsKeptOnlyForAProvenFixedRouteElseOnlyThatAdvertiserIsHiddenForTheRun() {
        withPhone { context, _ ->
            val fixed = stream(bytesOf("a1b2c3d4e5f6"), logical = 7)
            val unproven = stream(bytesOf("102030405060"), logical = 8)
            val rotating = stream(bytesOf("776655443322"), logical = 9, rotating = true)
            assertTrue(LocalReporting.hideStream(context, fixed, "copy", hopAuthenticated = true))
            assertTrue("a proven route stays hidden, from any radio", LocalReporting.hiddenStream(context, fixed, "other"))
            assertFalse(LocalReporting.hideStream(context, unproven, "copy-a", hopAuthenticated = false))
            assertFalse(LocalReporting.hideStream(context, rotating, "copy-b", hopAuthenticated = true))
            assertTrue(LocalReporting.hiddenStream(context, unproven, "copy-a"))
            assertTrue(LocalReporting.hiddenStream(context, rotating, "copy-b"))
            assertFalse("the same route from another radio still shows", LocalReporting.hiddenStream(context, unproven, "genuine"))
            assertFalse(LocalReporting.hiddenStream(context, rotating, "genuine"))
            // The app restarts: only the proven route stays hidden.
            PersonPolicy.resetForTest()
            assertTrue(LocalReporting.hiddenStream(context, fixed, "other"))
            assertFalse(LocalReporting.hiddenStream(context, unproven, "copy-a"))
            assertFalse(LocalReporting.hiddenStream(context, rotating, "copy-b"))
        }
    }

    /** AB3: a bare token an older build kept hid every route of that token (the spoof hole): it hides nothing and goes. */
    @Test fun aBareTokenAnOlderBuildKeptHidesNothingAndIsDeletedAtTheNextWrite() {
        withPhone { context, phone ->
            val settings = phone.preferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES)
            settings.edit().putStringSet("reported_streams", mutableSetOf("42")).commit()
            assertFalse(LocalReporting.hiddenStream(context, stream(bytesOf("a1b2c3d4e5f6"), logical = 42), "radio"))
            assertTrue(LocalReporting.report(context, IdentityFingerprint.fromPublicKey(byteArrayOf(7, 8, 9))))
            SealedRecordsWorker.awaitIdleForTest() // the plain lists are moved and deleted on the sealed-memory worker
            assertFalse(settings.contains("reported_streams"))
        }
    }
}
