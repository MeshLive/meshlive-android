package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCallsTest {
    private val a = "8a1b2c3d4e5f"
    private val b = "9c8d7e6f5a40"

    @Test
    fun everyCallIsKeptNewestFirstAndOldestEntriesAreEvicted() {
        var calls = emptyList<RecentCall>()
        calls = RecentCalls.add(calls, RecentCall(a, video = true, atMs = 1))
        calls = RecentCalls.add(calls, RecentCall(b, video = false, atMs = 2))
        calls = RecentCalls.add(calls, RecentCall(a, video = false, atMs = 3))
        assertEquals(listOf(a, b, a), calls.map { it.nodePrefixHex })
        assertEquals(false, calls.first().video)
        repeat(60) { calls = RecentCalls.add(calls, RecentCall("%012x".format(it), true, 10L + it)) }
        assertEquals((69L downTo 20L).toList(), calls.map { it.atMs })
    }

    @Test
    fun controlArmStillDeduplicatesAndKeepsFivePeople() {
        var calls = emptyList<RecentCall>()
        repeat(10) { calls = RecentCalls.add(calls, RecentCall("%012x".format(it), true, it.toLong()), complete = false) }
        calls = RecentCalls.add(calls, RecentCall("%012x".format(9), false, 20), complete = false)
        assertEquals(listOf(20L, 8L, 7L, 6L, 5L), calls.map { it.atMs })
    }

    @Test
    fun controlProjectionAtReloadKeepsTheOldPeopleWithoutDestroyingFullHistory() {
        var history = emptyList<RecentCall>()
        repeat(50) { index ->
            val direction = if (index % 2 == 0) CallDirection.OUTGOING else CallDirection.INCOMING
            history = RecentCalls.add(history, RecentCall("%012x".format(index % 9), true, index.toLong(),
                direction = direction, result = if (index % 3 == 0) CallResult.MISSED else CallResult.ANSWERED))
        }
        val stored = RecentCalls.encode(history)
        val loaded = RecentCalls.decode(stored)
        val control = RecentCalls.project(loaded, complete = false)
        assertEquals(listOf(49L, 47L, 46L, 44L, 43L), control.map { it.atMs })
        assertEquals(5, control.map { it.nodePrefixHex }.toSet().size)
        assertEquals(setOf(CallDirection.INCOMING, CallDirection.OUTGOING), control.map { it.direction }.toSet())
        assertEquals(50, RecentCalls.project(loaded, complete = true).size)
        assertEquals(stored, RecentCalls.encode(loaded))
    }

    @Test
    fun controlKeepsLatestStrangerAndBlockedRowsAlongsideAnsweredPeople() {
        val calls = listOf(
            RecentCall(a, true, 8, direction = CallDirection.OUTGOING, result = CallResult.NO_ANSWER),
            RecentCall(a, true, 7, result = CallResult.MISSED),
            RecentCall("", true, 6, strangerName = "gold fox"),
            RecentCall("", false, 5, blocked = true, result = CallResult.DECLINED),
            RecentCall("", false, 4, strangerName = "gold fox"),
            RecentCall("", false, 3, blocked = true),
            RecentCall(a, false, 2), RecentCall(a, true, 1), RecentCall(b, true, 0),
        )
        assertEquals(listOf(6L, 5L, 2L, 0L), RecentCalls.project(calls, false).map { it.atMs })
    }

    @Test
    fun endReasonsDistinguishAnsweredMissedDeclinedNoAnswerAndFailure() {
        assertEquals(CallResult.ANSWERED, recentCallResult(CallDirection.OUTGOING, true, false, "Call link lost"))
        assertEquals(CallResult.MISSED, recentCallResult(CallDirection.INCOMING, false, false, CallRingTimeout.CALLER_CANCELLED_MESSAGE))
        assertEquals(CallResult.MISSED, recentCallResult(CallDirection.INCOMING, false, true, CallRingTimeout.MISSED_MESSAGE))
        assertEquals(CallResult.DECLINED, recentCallResult(CallDirection.INCOMING, false, true, "Call ended"))
        assertEquals(CallResult.DECLINED, recentCallResult(CallDirection.OUTGOING, false, false, "Call declined"))
        assertEquals(CallResult.NO_ANSWER, recentCallResult(CallDirection.OUTGOING, false, false, CallRingTimeout.NO_ANSWER_MESSAGE))
        assertEquals(CallResult.FAILED, recentCallResult(CallDirection.OUTGOING, false, false, "Call could not connect: timeout"))
    }

    @Test
    fun migrationDoesNotInventDirectionAndNewMetadataSurvivesReload() {
        val old = RecentCalls.decode("$a,v,1\n,v,2,,s,gold fox")
        assertEquals(listOf(CallDirection.UNKNOWN, CallDirection.UNKNOWN), old.map { it.direction })
        val missed = RecentCall("", true, 3, strangerName = "blue otter", direction = CallDirection.INCOMING, result = CallResult.MISSED)
        val updated = RecentCalls.add(old, missed)
        assertEquals(updated, RecentCalls.decode(RecentCalls.encode(updated)))
    }

    private val fingerprint = a + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".substring(12)

    @Test
    fun theFullIdentityRoundTripsAndOldThreeFieldLinesStillLoad() {
        val calls = listOf(RecentCall(a, true, 5, fingerprintHex = fingerprint), RecentCall(b, false, 6))
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        assertEquals(listOf(RecentCall(a, true, 9)), RecentCalls.decode("$a,v,9"))
        // A fingerprint that does not start with the row's prefix, or is not 64 hex digits, is junk.
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$b,v,9,$fingerprint"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$a,v,9,abc"))
    }

    @Test
    fun blockedCallsStayAnonymousAndNeverReplaceAPerson() {
        var calls = listOf(RecentCall(a, true, 1, fingerprintHex = fingerprint))
        calls = RecentCalls.add(calls, RecentCall("", false, 2, blocked = true))
        calls = RecentCalls.add(calls, RecentCall("", false, 3, blocked = true))
        assertEquals(listOf(true, true, false), calls.map { it.blocked })
        assertEquals(3L, calls.first().atMs)
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        // A person calling again does not remove the blocked row either.
        calls = RecentCalls.add(calls, RecentCall(a, false, 4, fingerprintHex = fingerprint))
        assertEquals(listOf(false, true, true, false), calls.map { it.blocked })
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$a,v,9,,b"))
    }

    @Test
    fun encodeDecodeRoundTripAndRejectsJunk() {
        val calls = listOf(RecentCall(a, true, 5), RecentCall(b, false, 6))
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("not a call"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("xyz,v,1"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(null))
    }

    @Test
    fun aStrangerIsRememberedByTheGeneratedNameAndTheTimeOnly() {
        val stranger = RecentCall("", true, 7, strangerName = "gold fox")
        val encoded = RecentCalls.encode(listOf(stranger))
        assertEquals(",v,7,,s,gold fox|UNKNOWN|ANSWERED", encoded)
        assertEquals(listOf(stranger), RecentCalls.decode(encoded))
        // No keys, prefix or fingerprint can ride along, and junk names are dropped.
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode("$a,v,7,,s,gold fox"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(",v,7,$fingerprint,s,gold fox"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(",v,7,,s,Gold Fox"))
        assertEquals(emptyList<RecentCall>(), RecentCalls.decode(",v,7,,s,gold fox,x"))
        assertEquals(listOf("gold fox 42-7a7d"), RecentCalls.decode(",a,7,,s,gold fox 42-7a7d").map { it.strangerName })
    }

    @Test
    fun repeatedStrangerCallsNeverReplacePreviousCalls() {
        var calls = listOf(RecentCall(a, true, 1, fingerprintHex = fingerprint), RecentCall("", false, 2, blocked = true))
        calls = RecentCalls.add(calls, RecentCall("", true, 3, strangerName = "gold fox"))
        calls = RecentCalls.add(calls, RecentCall("", true, 4, strangerName = "blue otter"))
        calls = RecentCalls.add(calls, RecentCall("", false, 5, strangerName = "gold fox"))
        assertEquals(listOf("gold fox", "blue otter", "gold fox", "", ""), calls.map { it.strangerName })
        assertEquals(listOf(5L, 4L, 3L, 1L, 2L), calls.map { it.atMs })
        assertEquals(calls, RecentCalls.decode(RecentCalls.encode(calls)))
    }

    /**
     * Review 2026-10-27 (finding 6): Recent held real-key callers' fingerprints (strangers included) in plain text. The
     * stored list is sealed now, and an earlier build's plain list is migrated once and removed.
     */
    @Test
    fun theStoredListHoldsNoReadableIdentityAndAnOlderPlainListIsSealedOnce() {
        val phone = FakePhone(java.nio.file.Files.createTempDirectory("recent-sealed").toFile())
        try {
            val context = phone.start()
            val stored = phone.preferences(RecentCalls.PREFERENCES)
            // An earlier build left a plain list.
            stored.edit().putString("calls_v2", RecentCalls.encode(listOf(RecentCall(a, true, 1, fingerprintHex = fingerprint)))).commit()
            RecentCalls.record(context, RecentCall(b, false, 2))
            val reloaded = RecentCalls.load(phone.start())
            org.junit.Assert.assertEquals(listOf(b, a), reloaded.map { it.nodePrefixHex })
            org.junit.Assert.assertEquals(fingerprint, reloaded.last().fingerprintHex)
            val onDisk = stored.all.values.joinToString(" ")
            org.junit.Assert.assertFalse("the plain value is gone", stored.contains("calls_v2"))
            org.junit.Assert.assertFalse("no fingerprint is readable", onDisk.contains(fingerprint))
            org.junit.Assert.assertFalse("no node prefix is readable", onDisk.contains(a) || onDisk.contains(b))
            // A damaged value (it fails authentication) is replaced by the next call.
            stored.edit().putString("calls_sealed_v1", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
            org.junit.Assert.assertTrue(RecentCalls.load(context).isEmpty())
            org.junit.Assert.assertTrue(RecentCalls.record(context, RecentCall(a, true, 3)))
            org.junit.Assert.assertEquals(listOf(a), RecentCalls.load(context).map { it.nodePrefixHex })
            // A Keystore that cannot open it right now: nothing is written over it, recording reports false (no crash),
            // and the screen can offer "Clear history".
            val sealedNow = stored.getString("calls_sealed_v1", null)
            val working = RecentCalls.cipher
            RecentCalls.cipher = { object : AtRestCipher {
                override fun seal(plaintext: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
                override fun open(sealed: ByteArray): ByteArray = throw java.security.KeyStoreException("busy")
            } }
            org.junit.Assert.assertFalse(RecentCalls.record(context, RecentCall(b, true, 4)))
            org.junit.Assert.assertEquals(sealedNow, stored.getString("calls_sealed_v1", null))
            org.junit.Assert.assertTrue(RecentCalls.isUnreadable(context))
            RecentCalls.cipher = working
            org.junit.Assert.assertFalse(RecentCalls.isUnreadable(context))
            org.junit.Assert.assertTrue(RecentCalls.clear(context))
            org.junit.Assert.assertTrue(RecentCalls.load(context).isEmpty())
        } finally {
            phone.root.deleteRecursively()
        }
    }

    /** Review 2026-10-27 (finding 15): once an emergency wipe started, Recent and the local hide list are not rewritten. */
    @Test
    fun nothingIsRecordedWhileAnEmergencyWipeRuns() {
        val phone = FakePhone(java.nio.file.Files.createTempDirectory("recent-wipe").toFile())
        try {
            val context = phone.start()
            WipeGuard.begin()
            // Review finding 1: the call-end path must not throw (an uncaught throw crashed the app and aborted the wipe).
            org.junit.Assert.assertFalse(RecentCalls.record(context, RecentCall(a, true, 1)))
            org.junit.Assert.assertFalse(RecentCalls.recordBlocked(context, 2))
            org.junit.Assert.assertFalse(RecentCalls.promoteStranger(context, "gold fox", fingerprint))
            val stream = ByteArray(6) { 1 }.let { prefix ->
                DiscoveryAnnouncement(
                    nodeIdPrefix = prefix,
                    flags = DiscoveryAnnouncement.FLAG_STREAM_AVAILABLE,
                    capabilitySummary = 1,
                    streamToken = StreamRouteToken.forAdvertiser(1234, prefix),
                    routeCost = 0,
                    sequence = 0,
                    version = DiscoveryAnnouncement.FIXED_PREFIX_VERSION,
                )
            }
            org.junit.Assert.assertFalse(LocalReporting.hideStream(context, stream, "00:11:22:33:44:55", hopAuthenticated = true))
            org.junit.Assert.assertTrue(phone.preferences(RecentCalls.PREFERENCES).all.isEmpty())
            org.junit.Assert.assertTrue(phone.preferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES).all.isEmpty())
        } finally {
            WipeGuard.resetForTest()
            phone.root.deleteRecursively()
        }
    }
}
