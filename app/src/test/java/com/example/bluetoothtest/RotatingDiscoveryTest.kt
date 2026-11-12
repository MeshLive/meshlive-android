package com.example.bluetoothtest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Reviewed vectors of the rotating discovery IDs (design sections 3, 4, 6; recomputed by the
 * review's independent recompute.py) plus reject cases, pin rules, the RV4 clock, RV8 and RV15.
 */
class RotatingDiscoveryTest {
    private val presenceKey = ByteArray(32) { it.toByte() }
    private val nodeId = hex("a1b2c3d4e5f60718293a4b5c6d7e8f90")
    private val epoch = 1_990_656L

    @Before
    fun fixTheClockOfTheEpochWindow() {
        // 100 s into the reviewed vector epoch (RD4: openings must be within two epochs of the verifier's clock).
        DiscoverySelectionCheck.unixSeconds = { 1_791_590_400L + 100L }
    }

    @After
    fun restoreTheClock() {
        DiscoverySelectionCheck.unixSeconds = { System.currentTimeMillis() / 1000L }
    }

    @Test
    fun domainsAndEpochMatchTheReviewedVectors() {
        assertEquals(
            "426c7565746f6f7468566964656f4d6573682f446973636f766572792f53616c742f3100",
            RotatingDiscoveryIds.SALT_DOMAIN.toHex(),
        )
        assertEquals(
            "426c7565746f6f7468566964656f4d6573682f446973636f766572792f49642f3100",
            RotatingDiscoveryIds.ID_DOMAIN.toHex(),
        )
        assertEquals(epoch, RotatingDiscoveryIds.wallEpoch(1_791_590_400L))
        assertEquals(0x001e6000L, epoch)
    }

    @Test
    fun saltsAndIdsMatchTheReviewedVectors() {
        val vectors = listOf(
            Triple(epoch to 0, "d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167", "44e69c0a7a7d"),
            Triple(epoch to 1, "dcd9349e228e00ceb4a7ec2e7e023fc0c34c507f88e99934897105ca4fae84c0", "bb2d061f6480"),
            Triple(epoch to 2, "e59970c8523f23a6b1b16a858daf75320e3291d6c86d0329df89a62899804335", "974ae410ea91"),
            Triple(epoch + 1 to 0, "7ece98afad0378297e68c9e2b59c1a1f88448a0c70b86d91f192f65d263a763b", "67aba670e65a"),
        )
        vectors.forEach { (input, salt, rdi) ->
            val (e, role) = input
            val computedSalt = RotatingDiscoveryIds.salt(presenceKey, e, role)
            assertEquals(salt, computedSalt.toHex())
            assertEquals(rdi, RotatingDiscoveryIds.rdi(nodeId, e, role, computedSalt).toHex())
            val entry = RotatingDiscoveryIds.entry(presenceKey, nodeId, e, role)
            assertEquals(rdi, entry.rdi.toHex())
        }
    }

    @Test
    fun announcementVersion5MatchesTheReviewedVectors() {
        val call = DiscoveryAnnouncement(
            nodeIdPrefix = hex("44e69c0a7a7d"),
            flags = 0x41,
            capabilitySummary = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        )
        assertEquals("0544e69c0a7a7d410300000000000000000000000000", DiscoveryAnnouncementCodec.encode(call).toHex())

        val rdi1 = hex("bb2d061f6480")
        val token = StreamRouteToken.forAdvertiser(0x12345678, rdi1)
        assertEquals(0x92529fc4.toInt(), token)
        assertEquals(0x8066c9bc.toInt(), 0x12345678 xor token)
        val stream = DiscoveryAnnouncement(
            nodeIdPrefix = rdi1,
            flags = 0x0c,
            capabilitySummary = NodeCapabilities.CODEC_VP9,
            streamToken = token,
            routeCost = 0,
            sequence = 1,
            streamWidth = 640,
            streamHeight = 360,
            streamFps = 15,
            streamBitrateKbps = 300,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        )
        val encoded = DiscoveryAnnouncementCodec.encode(stream)
        assertEquals("05bb2d061f64800c0192529fc40001028001680f012c", encoded.toHex())
        val decoded = DiscoveryAnnouncementCodec.decode(encoded, acceptRotating = true)
        assertTrue(decoded.rotating)
        assertArrayEquals(rdi1, decoded.nodeIdPrefix)
        assertEquals(0x12345678, StreamRouteToken.logicalFrom(decoded.streamToken, decoded.nodeIdPrefix))
    }

    @Test
    fun version5IsRejectedWithTheSwitchOffAndVersion2IsUnchanged() {
        val v5 = hex("0544e69c0a7a7d410300000000000000000000000000")
        try {
            DiscoveryAnnouncementCodec.decode(v5)
            fail("version 5 must be rejected when rotating IDs are off")
        } catch (expected: IllegalArgumentException) {
            assertEquals("Unsupported announcement version", expected.message)
        }
        val v2 = v5.copyOf().also { it[0] = 2 }
        val decoded = DiscoveryAnnouncementCodec.decode(v2)
        assertFalse(decoded.rotating)
        assertArrayEquals(v2, DiscoveryAnnouncementCodec.encode(decoded))
        // Versions other than 2 and 5 stay rejected even with the switch on.
        listOf(3, 4, 6).forEach { version ->
            val other = v5.copyOf().also { it[0] = version.toByte() }
            try {
                DiscoveryAnnouncementCodec.decode(other, acceptRotating = true)
                fail("version $version must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun compactV2MatchesTheReviewedVectorAndIsRejectedWhenOff() {
        val call = DiscoveryAnnouncement(
            nodeIdPrefix = hex("44e69c0a7a7d"),
            flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
            capabilitySummary = NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            streamToken = 0,
            routeCost = 0,
            sequence = 0,
            version = DiscoveryAnnouncement.ROTATING_VERSION,
        )
        val uuid = CompactDiscoveryAnnouncement.encode(call)
        assertEquals(UUID.fromString("C8D6844E-69C0-A7A7-D0C0-000000000000"), uuid)
        val decoded = CompactDiscoveryAnnouncement.decode(uuid, acceptRotating = true)
        assertTrue(decoded.rotating)
        assertArrayEquals(call.nodeIdPrefix, decoded.nodeIdPrefix)
        try {
            CompactDiscoveryAnnouncement.decode(uuid)
            fail("compact v2 must be rejected when rotating IDs are off")
        } catch (_: IllegalArgumentException) {
        }
        // Compact v1 still decodes as a fixed-prefix (version 2) announcement.
        val v1 = CompactDiscoveryAnnouncement.decode(UUID.fromString("C8D64010-2030-4050-60C0-000000000000"), acceptRotating = true)
        assertFalse(v1.rotating)
        assertEquals("010203040506", v1.nodeIdPrefix.toHex())
    }

    @Test
    fun linkOpeningMatchesTheReviewedVectors() {
        val entry = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)
        val one = DiscoveryOpeningCodec.encode(listOf(entry))
        assertEquals(
            "42444f31010144e69c0a7a7d001e600000d8cbe82b78824c1ba08d0c4eed26502189e19ccccd180e46c4826c5ba47e7167",
            one.toHex(),
        )
        assertEquals("42444f310100", DiscoveryOpeningCodec.encode(emptyList()).toHex())
        assertEquals(listOf(entry), DiscoveryOpeningCodec.decode(one))
        assertTrue(DiscoveryOpeningCodec.decode(hex("42444f310100")).isEmpty())
    }

    @Test
    fun linkOpeningRejectsMalformedRecords() {
        val one = DiscoveryOpeningCodec.encode(listOf(RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)))
        val bad = listOf(
            one.copyOf().also { it[0] = 'X'.code.toByte() }, // magic
            one.copyOf().also { it[4] = 2 }, // version
            one.copyOf().also { it[5] = 3 }, // count above 2
            one.copyOf().also { it[5] = 2 }, // count does not match length
            one.copyOf(one.size - 1), // truncated
            one + byteArrayOf(0), // trailing byte
            hex("42444f31"), // too short
        )
        bad.forEachIndexed { index, bytes ->
            try {
                DiscoveryOpeningCodec.decode(bytes)
                fail("case $index must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            DiscoveryOpeningCodec.encode(List(3) { RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch + it, 0) })
            fail("three entries must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun openingBindsTheSelectedIdToTheAuthenticatedNode() {
        val entry = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)
        val selected = selectedCall(entry.rdi)
        val result = DiscoverySelectionCheck.check(selected, 0, nodeId, listOf(entry))
        assertEquals(DiscoverySelectionCheck.Result.PASS, result)
        // Another identity presenting the same entry (a relay answering itself) fails.
        val other = nodeId.copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertEquals(DiscoverySelectionCheck.Result.OPENING_MISMATCH, DiscoverySelectionCheck.check(selected, 0, other, listOf(entry)))
        // A forged salt for the right node does not recompute to the selected ID.
        val forged = OpeningEntry(entry.rdi, entry.epoch, 0, ByteArray(32))
        assertEquals(DiscoverySelectionCheck.Result.OPENING_MISMATCH, DiscoverySelectionCheck.check(selected, 0, nodeId, listOf(forged)))
        // The right entry under the wrong role fails (each role has its own ID).
        val streamEntry = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 1)
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            DiscoverySelectionCheck.check(selectedCall(streamEntry.rdi), 0, nodeId, listOf(streamEntry)),
        )
        // An entry for another ID (e.g. last epoch's) does not open the selected one.
        val previous = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch - 1, 0)
        assertEquals(DiscoverySelectionCheck.Result.OPENING_MISMATCH, DiscoverySelectionCheck.check(selected, 0, nodeId, listOf(previous)))
        assertEquals(DiscoverySelectionCheck.Result.PASS, DiscoverySelectionCheck.check(selected, 0, nodeId, listOf(previous, entry)))
    }

    @Test
    fun anOpeningOutsideTheEpochWindowIsStaleEvenWhenItRecomputes() {
        // RD4: a forger may choose any epoch and salt, so one precomputed table would invert any victim ID of
        // any epoch. The verifier bounds the entry's epoch by its own clock.
        listOf(-2L, -1L, 0L, 1L, 2L).forEach { offset ->
            val entry = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch + offset, 0)
            assertEquals(
                "offset $offset",
                DiscoverySelectionCheck.Result.PASS,
                DiscoverySelectionCheck.check(selectedCall(entry.rdi), 0, nodeId, listOf(entry)),
            )
        }
        listOf(-3L, 3L, 96L, -96L, 1_000_000L).forEach { offset ->
            val entry = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch + offset, 0)
            assertEquals(
                "offset $offset",
                DiscoverySelectionCheck.Result.OPENING_STALE,
                DiscoverySelectionCheck.check(selectedCall(entry.rdi), 0, nodeId, listOf(entry)),
            )
        }
    }

    @Test
    fun checkFollowsTheSelectedAdvertNotTheHello() {
        val entry = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)
        // RV15: a selected version 5 advert from a peer without bit 3 (no opening) fails.
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISSING,
            DiscoverySelectionCheck.check(selectedCall(entry.rdi), 0, nodeId, null),
        )
        assertEquals(
            DiscoverySelectionCheck.Result.OPENING_MISMATCH,
            DiscoverySelectionCheck.check(selectedCall(entry.rdi), 0, nodeId, emptyList()),
        )
        // A selected version 2 advert uses the prefix check even when an opening arrived.
        val v2 = selectedCall(nodeId.copyOf(6), DiscoveryAnnouncement.FIXED_PREFIX_VERSION)
        assertEquals(DiscoverySelectionCheck.Result.PASS, DiscoverySelectionCheck.check(v2, 0, nodeId, listOf(entry)))
        val wrongPrefix = selectedCall(entry.rdi, DiscoveryAnnouncement.FIXED_PREFIX_VERSION)
        assertEquals(DiscoverySelectionCheck.Result.PREFIX_MISMATCH, DiscoverySelectionCheck.check(wrongPrefix, 0, nodeId, listOf(entry)))
    }

    @Test
    fun pinsIncludeThePreviousIdOnlyForSixtySeconds() {
        val pins = OpeningPins()
        val first = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)
        val second = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch + 1, 0)
        assertTrue(pins.pinned(0L).isEmpty())
        pins.onAdvertised(first, 1_000L)
        assertEquals(listOf(first), pins.pinned(2_000L))
        // A restart inside the epoch re-advertises the same ID: no change, no "previous".
        pins.onAdvertised(first, 5_000L)
        assertEquals(listOf(first), pins.pinned(5_000L))
        // Boundary: the old set stops (break-before-make), the new one starts after the silence.
        pins.offAir(900_000L)
        pins.onAdvertised(second, 904_000L)
        assertEquals(listOf(second, first), pins.pinned(959_999L))
        assertEquals(listOf(second), pins.pinned(960_000L))
    }

    @Test
    fun anIdThatLeftTheAirLongAgoIsNeverOpenedAgain() {
        val pins = OpeningPins()
        val before = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch, 0)
        val after = RotatingDiscoveryIds.entry(presenceKey, nodeId, epoch + 12, 0)
        pins.onAdvertised(before, 1_000L)
        // An incoming connection stops advertising; its opening still goes out within the grace.
        pins.offAir(2_000L)
        assertEquals(listOf(before), pins.pinned(31_999L))
        // A three-hour call later the listener reopens: the old ID gets no fresh grace (review finding).
        pins.onAdvertised(after, 2_000L + 3 * 3_600_000L)
        assertEquals(listOf(after), pins.pinned(2_000L + 3 * 3_600_000L + 1))
        // A Visible-by-handle window (no rotating advert) leaves nothing openable after 60 s.
        pins.offAir(20_000_000L)
        assertEquals(listOf(after), pins.pinned(20_059_999L))
        assertTrue(pins.pinned(20_060_000L).isEmpty())
        assertTrue(VisibleByHandleWindow.MAX_MS >= 1_500_000L)
    }

    @Test
    fun restartsInsideAnEpochReuseItAndTicksFollowTheWallBoundary() {
        val saved = mutableListOf<EpochClockState>()
        val clock = DiscoveryEpochClock(EpochClockState(), "boot1") { saved += it }
        val t0 = 1_791_590_400_000L
        assertEquals(epoch, clock.read(t0 + 1_000, 10_000).epoch)
        // Advertiser restarts, retries and defers inside the epoch never push E ahead (RV4).
        repeat(50) { i -> assertEquals(epoch, clock.read(t0 + 2_000 + i * 1_000L, 11_000L + i * 1_000L).epoch) }
        val next = clock.read(t0 + 900_000 + 100, 910_100)
        assertEquals(epoch + 1, next.epoch)
        assertTrue(next.ticked)
        assertEquals(epoch + 1, clock.read(t0 + 1_000_000, 1_010_000).epoch)
        assertEquals(epoch + 1, saved.last().lastEpoch)
    }

    @Test
    fun aBackwardClockNeverRepeatsAnEpoch() {
        val clock = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        val t0 = 1_791_590_400_000L
        assertEquals(epoch, clock.read(t0, 0).epoch)
        // Fake cell time moves the wall clock back 3 h: the next tick still moves forward.
        val back = t0 - 3 * 3_600_000L
        assertEquals(epoch + 1, clock.read(back, 1_000).epoch)
        // The wall clock is stuck in the past: the elapsed rule still rotates (no ID stays forever).
        val elapsedTick = DiscoveryEpochClock.ELAPSED_TICK_MS
        assertEquals(epoch + 2, clock.read(back + 2_000, 1_000 + elapsedTick).epoch)
        // A normal wall boundary never gets an early elapsed double tick.
        val fresh = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        assertEquals(epoch, fresh.read(t0 + 500, 0).epoch)
        assertEquals(epoch, fresh.read(t0 + 899_000, 899_500).epoch)
        assertEquals(epoch + 1, fresh.read(t0 + 900_200, 900_700).epoch)
        assertEquals(epoch + 1, fresh.read(t0 + 1_799_000, 1_799_500).epoch)
        assertEquals(epoch + 2, fresh.read(t0 + 1_800_100, 1_800_600).epoch)
    }

    @Test
    fun aRebootReusesTheEpochOnlyWhileTheWallAgrees() {
        val t0 = 1_791_590_400_000L
        val state = EpochClockState(lastEpoch = epoch, tickWallEpoch = epoch, tickElapsedMs = 500_000L, bootId = "boot1")
        val sameEpoch = DiscoveryEpochClock(state, "boot2") { }
        val reading = sameEpoch.read(t0 + 300_000, 20_000)
        assertEquals(epoch, reading.epoch)
        assertFalse(reading.ticked)
        val later = DiscoveryEpochClock(state, "boot2") { }
        assertEquals(epoch + 3, later.read(t0 + 3 * 900_000L + 5, 20_000).epoch)
        // A process restart in the same boot and epoch reuses it too.
        val sameBoot = DiscoveryEpochClock(state, "boot1") { }
        assertEquals(epoch, sameBoot.read(t0 + 600_000, 600_000).epoch)
    }

    @Test
    fun aPinnedForwardJumpRecoversAfterAnHourOfConsistentWallTime() {
        val t0 = 1_791_590_400_000L
        val clock = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        // A forged clock a year ahead pins E far beyond the wall.
        val ahead = t0 + 365L * 24 * 3_600_000L
        val pinned = clock.read(ahead, 0).epoch
        assertTrue(pinned - epoch > DiscoveryEpochClock.MAX_AHEAD_EPOCHS)
        // The clock is corrected; E cannot go back, so it stays ahead...
        assertEquals(pinned + 1, clock.read(t0, 1_000).epoch)
        // ...an inconsistent wall (jumping again) restarts the one-hour window...
        clock.read(t0 + 1_800_000, 1_801_000 + 300_000)
        assertFalse(clock.read(t0 + 3_600_000, 3_601_000 + 300_000).newGeneration)
        // ...and after an hour of consistent wall time a new generation resets E to the wall epoch.
        val end = 3_601_000L + 300_000 + DiscoveryEpochClock.AHEAD_RECOVERY_MS
        val recovered = clock.read(t0 + end - 300_000 - 1_000, end)
        assertTrue(recovered.newGeneration)
        assertEquals(2L, clock.state.generation)
        assertEquals(RotatingDiscoveryIds.wallEpoch((t0 + end - 301_000) / 1000), recovered.epoch)
        // A lead inside the opening tolerance never triggers a new generation: the wall steps back one epoch
        // and E (which ticks once more, never repeating) ends 2 epochs ahead.
        val small = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        small.read(t0 + 900_000L, 0)
        var newGeneration = false
        for (minute in 0..180) {
            newGeneration = newGeneration || small.read(t0 + minute * 60_000L, 1_000 + minute * 60_000L).newGeneration
        }
        assertFalse(newGeneration)
    }

    /** Q33: the recovery bound is the opening tolerance, so a backward wall step of 3 epochs recovers. */
    @Test
    fun theLeadBoundIsTheOpeningToleranceNotADay() {
        assertEquals(DiscoverySelectionCheck.EPOCH_TOLERANCE, DiscoveryEpochClock.MAX_AHEAD_EPOCHS)
        assertEquals(2L, DiscoveryEpochClock.MAX_AHEAD_EPOCHS)
    }

    @Test
    fun aBackwardWallStepBeyondTheToleranceStartsANewGenerationAfterAnHourOfConsistentWall() {
        val t0 = 1_791_590_400_000L
        val saved = ArrayList<EpochClockState>()
        val clock = DiscoveryEpochClock(EpochClockState(), "boot1") { saved += it }
        assertEquals(epoch, clock.read(t0, 0).epoch)
        // A manual fix moves the wall back 2 epochs (30 min) at elapsed 1 s. E must still tick forward (an epoch is
        // never advertised twice), so it ends 3 epochs ahead of the wall. The app reads once a minute.
        val wallAfterStep = t0 - 2 * 900_000L
        fun readAt(minute: Int) = clock.read(wallAfterStep + minute * 60_000L, 1_000 + minute * 60_000L)
        var firstGenerationMinute = -1
        var maxLead = 0L
        for (minute in 0..180) {
            val reading = readAt(minute)
            maxLead = maxOf(maxLead, reading.epoch - RotatingDiscoveryIds.wallEpoch((wallAfterStep + minute * 60_000L) / 1000))
            if (reading.newGeneration && firstGenerationMinute < 0) firstGenerationMinute = minute
            // E never goes down and is never advertised twice across epochs.
        }
        // E stayed 3 epochs ahead (beyond the tolerance of 2) until the hour of steady wall time ended at minute 60.
        assertEquals(3L, maxLead)
        assertEquals(60, firstGenerationMinute)
        assertEquals(2L, clock.state.generation)
        assertEquals(2L, saved.last().generation)
        // After the recovery E equals the wall epoch and nothing further starts.
        assertEquals(RotatingDiscoveryIds.wallEpoch((wallAfterStep + 180 * 60_000L) / 1000), clock.state.lastEpoch)
    }

    @Test
    fun aLeadOfExactlyTheToleranceOrAnUnsteadyWallClockStartsNothing() {
        val t0 = 1_791_590_400_000L
        // Exactly 2 epochs ahead (not "exceeds"): no recovery however long it lasts.
        val edge = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        edge.read(t0 + 900_000L, 0)
        var started = false
        var lead = 0L
        for (minute in 0..240) {
            val reading = edge.read(t0 + minute * 60_000L, 1_000 + minute * 60_000L)
            lead = reading.epoch - RotatingDiscoveryIds.wallEpoch((t0 + minute * 60_000L) / 1000)
            started = started || reading.newGeneration
        }
        assertEquals(2L, lead)
        assertFalse(started)
        // Ahead by 3 or more, but the wall keeps being corrected (its offset moves 3 min every 30 min): the hour restarts.
        val unsteady = DiscoveryEpochClock(EpochClockState(), "boot1") { }
        unsteady.read(t0, 0)
        for (minute in 0..240) {
            val wall = t0 - 3 * 900_000L + minute * 60_000L - (minute / 30) * 180_000L
            val reading = unsteady.read(wall, 1_000 + minute * 60_000L)
            started = started || reading.newGeneration
        }
        assertFalse(started)
        assertEquals(1L, unsteady.state.generation)
    }

    @Test
    fun tarpitGrowsPerSilentStrangerAndResetsEachEpoch() {
        assertEquals(0L, StrangerTarpit.delayMs(0))
        assertEquals(31L, StrangerTarpit.delayMs(1))
        assertEquals(1_000L, StrangerTarpit.delayMs(6))
        assertEquals(2_000L, StrangerTarpit.delayMs(7))
        assertEquals(16_000L, StrangerTarpit.delayMs(10))
        assertEquals(30_000L, StrangerTarpit.delayMs(11))
        assertEquals(30_000L, StrangerTarpit.delayMs(1_000))
        val tarpit = StrangerTarpit()
        val delays = (1..7).map { tarpit.nextDelayMs(epoch) }
        assertEquals(listOf(31L, 62L, 125L, 250L, 500L, 1_000L, 2_000L), delays)
        tarpit.forgive(epoch) // the 7th stranger's call rang: not a probe
        assertEquals(2_000L, tarpit.nextDelayMs(epoch))
        assertEquals(31L, tarpit.nextDelayMs(epoch + 1))
    }

    @Test
    fun visibleByHandleWindowEndsAtTheFirstBoundaryTenMinutesLater() {
        val boundary = 1_791_590_400L
        // Started on a boundary: 15 min. Started 299 s in: just over 10 min. 301 s in: just under 25 min.
        assertEquals(epoch + 1, VisibleByHandleWindow.endEpoch(boundary))
        assertEquals(epoch + 1, VisibleByHandleWindow.endEpoch(boundary + 300))
        assertEquals(epoch + 2, VisibleByHandleWindow.endEpoch(boundary + 301))
        listOf(0L, 299L, 300L, 301L, 899L).forEach { offset ->
            val start = boundary + offset
            val lengthSeconds = VisibleByHandleWindow.endEpoch(start) * 900L - start
            assertTrue("offset $offset gives $lengthSeconds s", lengthSeconds in 600L..1_500L)
        }
        val end = VisibleByHandleWindow.endEpoch(boundary + 100)
        assertTrue(VisibleByHandleWindow.isActive(end, boundary + 100))
        assertTrue(VisibleByHandleWindow.isActive(end, end * 900L - 1))
        assertFalse(VisibleByHandleWindow.isActive(end, end * 900L))
        assertFalse(VisibleByHandleWindow.isActive(-1L, boundary))
    }

    @Test
    fun recoveryHintIsKeySeparatedAndBoundToTheRequestsOwnIdAndToken() {
        val mediaKey = ByteArray(32) { (0x40 + it).toByte() }
        val hintKey = RecoveryHint.hintKey(mediaKey)
        assertEquals("07eb05087658fd20da5cd7f88afd09a1e4cbd2c92e03066ced3cf16084305c28", hintKey.toHex())
        val rdi = hex("974ae410ea91")
        assertEquals("4d832e9d4dc7", RecoveryHint.hint(hintKey, nodeId, 0x12345678, rdi, 0x92529fc4.toInt()).toHex())
        // RD3: another epoch's request ID gives another hint, so nothing constant crosses a boundary and a hint
        // copied into a different advert matches nothing.
        assertEquals(
            "f9fa5ad0b780",
            RecoveryHint.hint(hintKey, nodeId, 0x12345678, hex("67aba670e65a"), 0x92529fc4.toInt()).toHex(),
        )
        assertTrue(
            !RecoveryHint.hint(hintKey, nodeId, 0x12345678, rdi, 0x92529fc5.toInt())
                .contentEquals(RecoveryHint.hint(hintKey, nodeId, 0x12345678, rdi, 0x92529fc4.toInt())),
        )
    }

    @Test
    fun neutralNameIsSetWhileOnAndTheUsersNameComesBack() {
        val enable = NeutralBluetoothName.onEnabled("Alex's Phone", null)
        assertEquals(NeutralBluetoothName.NEUTRAL, enable.setName)
        assertEquals("Alex's Phone", enable.saveOriginal)
        assertNull(NeutralBluetoothName.onEnabled(NeutralBluetoothName.NEUTRAL, "Alex's Phone").setName)
        val disable = NeutralBluetoothName.onDisabled(NeutralBluetoothName.NEUTRAL, "Alex's Phone")
        assertEquals("Alex's Phone", disable.setName)
        // Kept until the adapter reads it back (a rename is asynchronous).
        assertFalse(disable.clearSaved)
        // The user picked a new name meanwhile: keep it, forget the old one.
        val renamed = NeutralBluetoothName.onDisabled("Kitchen", "Alex's Phone")
        assertNull(renamed.setName)
        assertTrue(renamed.clearSaved)
        assertNull(NeutralBluetoothName.onDisabled("Kitchen", null).setName)
    }

    @Test
    fun featureFollowsTheUsualLaunchRule() {
        // A DEBUG benchmark launch reads only its extra: not the default, not a stored choice.
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, true, { it == "test_rotating_ids" }) { false })
        assertFalse(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, true, { false }) { true })
        assertFalse(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, true, { false }) { null })
        // A user launch: always the shipped default (no switch since 2026-11-04; an old stored choice is ignored).
        assertTrue(RotatingDiscoveryFeature.DEFAULT_ENABLED)
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, false, { false }) { null })
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, false, { false }) { if (it == "experimental_rotating_ids") false else null })
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, false, { false }) { null })
        assertTrue(FeatureCatalog.resolve(RotatingDiscoveryFeature.spec, false, { false }, { null }))
    }

    @Test
    fun hopType13IsUnknownUnlessTheOpeningIsExpected() {
        assertEquals(13, SecureRecordType.DISCOVERY_OPENING.wireValue)
        assertEquals(1 shl 3, RotatingDiscoveryIds.LINK_HELLO_BIT)
    }

    private fun selectedCall(sixBytes: ByteArray, version: Int = DiscoveryAnnouncement.ROTATING_VERSION) = DiscoveryAnnouncement(
        nodeIdPrefix = sixBytes,
        flags = DiscoveryAnnouncement.FLAG_ACCEPTING_CONNECTIONS or DiscoveryAnnouncement.FLAG_PRIVATE_CALL_AVAILABLE,
        capabilitySummary = NodeCapabilities.CODEC_VP9,
        streamToken = 0,
        routeCost = 0,
        sequence = 0,
        version = version,
    )

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
