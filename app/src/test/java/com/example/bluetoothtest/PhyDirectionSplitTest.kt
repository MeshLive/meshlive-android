package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Experimental `test_phy_direction_split` (Q14, design decision 2026-11-06): which direction of a GATT central's 2M link moves
 * to 1M, step by step, and what is remembered per peer. Timelines are replayed one tick per completed write (every 100 ms).
 */
class PhyDirectionSplitTest {
    private val oneEvent15ms = AdaptivePhyPolicy.oneEventKbps(15.0)

    private fun PhyDirectionSplit.replay(
        fromMs: Long,
        toMs: Long,
        onTwoM: Boolean = true,
        writerFlowing: Boolean = true,
        quietAt: (Long) -> Long,
    ): List<PhyDirectionSplit.Action> =
        (fromMs..toMs step 100L).map { now -> onTick(now, onTwoM, writerFlowing, quietAt(now)) }
            .filter { it.kind != PhyDirectionSplit.Kind.NONE }

    private fun AdaptivePhyPolicy.feed(startMs: Long, seconds: Int, kbps: Int, onTwoM: Boolean): List<AdaptivePhyPolicy.Verdict> =
        (1..seconds * 10).map { i ->
            onWrite(startMs + i * 100L, kbps * 100 / 8, 90L, onTwoM, oneEvent15ms)
        }.filter { it != AdaptivePhyPolicy.Verdict.NONE }

    /** The peer's data arrives with every write until [silentFromMs], then nothing (its silence grows with time). */
    private fun heardUntil(silentFromMs: Long): (Long) -> Long = { now -> if (now < silentFromMs) 0L else now - silentFromMs }

    @Test
    fun aPeerThatGoesSilentWhileThisPhonesWritesFlowMovesOnlyTheReceiveDirectionAndIsRememberedWhenHeardAgain() {
        val split = PhyDirectionSplit()
        // Heard for 6 s, then silent while this phone keeps writing: the receive direction moves once the silence is 3 s.
        val moved = split.replay(0L, 9_500L, quietAt = heardUntil(6_000L)).single()
        assertEquals(PhyDirectionSplit.Kind.SPLIT, moved.kind)
        assertEquals(GattPhySplit.TX_2M_RX_1M, moved.split)
        assertEquals(PhyDirectionSplit.RX_SILENT, moved.reason)
        // The peer is heard again on the split link: that direction is proven and remembered, once.
        val proven = split.replay(9_600L, 11_000L) { 0L }.single()
        assertEquals(PhyDirectionSplit.Kind.REMEMBER, proven.kind)
        assertEquals(GattPhySplit.TX_2M_RX_1M, proven.split)
        assertEquals(PhyDirectionSplit.RX_SILENT, proven.reason)
    }

    @Test
    fun silenceWhileThisPhonesOwnWritesAreStuckIsNotAOneWayStall() {
        val split = PhyDirectionSplit()
        assertTrue(split.replay(0L, 12_000L, writerFlowing = false, quietAt = heardUntil(6_000L)).isEmpty())
        assertEquals(0, split.step)
    }

    @Test
    fun aPeerHeardOnlyBrieflyNeverHeardOrOnAOneMLinkIsNotJudged() {
        // Heard for 2 s (a handshake), then silent: not a peer that sends regularly.
        assertTrue(PhyDirectionSplit().replay(0L, 12_000L, quietAt = heardUntil(2_000L)).isEmpty())
        // Never heard at all.
        assertTrue(PhyDirectionSplit().replay(0L, 12_000L) { -1L }.isEmpty())
        // A link that is not on 2M (or already split) has no 2M receive direction to give up.
        assertTrue(PhyDirectionSplit().replay(0L, 12_000L, onTwoM = false, quietAt = heardUntil(6_000L)).isEmpty())
    }

    @Test
    fun aReceiveSplitWhosePeerStaysSilentIsUnprovenAndNeitherRememberedNorMovedFurther() {
        val split = PhyDirectionSplit()
        val actions = split.replay(0L, 20_000L, quietAt = heardUntil(6_000L))
        assertEquals(listOf(PhyDirectionSplit.Kind.SPLIT, PhyDirectionSplit.Kind.UNPROVEN), actions.map { it.kind })
        assertEquals(GattPhySplit.TX_2M_RX_1M, actions.last().split)
    }

    @Test
    fun aBackloggedWriterMovesTheReceiveDirectionFirstThenTriesTheMirrorThenFullOneM() {
        val split = PhyDirectionSplit()
        val first = split.onVerdict(writerFailed = true, reason = "one_pdu_per_event")
        assertEquals(PhyDirectionSplit.Kind.SPLIT, first.kind)
        assertEquals(GattPhySplit.TX_2M_RX_1M, first.split)
        assertEquals(1, first.step)
        val second = split.onVerdict(writerFailed = true, reason = "one_pdu_per_event")
        assertEquals(PhyDirectionSplit.Kind.SPLIT, second.kind)
        assertEquals(GattPhySplit.TX_1M_RX_2M, second.split)
        assertEquals(2, second.step)
        val third = split.onVerdict(writerFailed = true, reason = "stalled_one_way")
        assertEquals(PhyDirectionSplit.Kind.FULL, third.kind)
        // After the full move nothing else happens on this link, and nothing is remembered as a split.
        assertEquals(PhyDirectionSplit.Kind.NONE, split.onVerdict(writerFailed = true, reason = "one_pdu_per_event").kind)
        assertEquals(PhyDirectionSplit.Kind.NONE, split.onVerdict(writerFailed = false, reason = "kept").kind)
    }

    @Test
    fun theReceiveSplitThatTheNextDecisionKeepsIsRememberedOnce() {
        // ASUS -> A15s on 2M: one PDU per event while backlogged (slowlink-asussnoop2-b9029e48).
        var policy = AdaptivePhyPolicy()
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.MOVE_TO_1M), policy.feed(0, 9, kbps = 60, onTwoM = true))
        val split = PhyDirectionSplit()
        val moved = split.onVerdict(writerFailed = true, reason = "one_pdu_per_event")
        assertEquals(GattPhySplit.TX_2M_RX_1M, moved.split)
        // The receive split leaves the transmit PHY on 2M: the re-armed policy measures again and the link now delivers.
        policy = AdaptivePhyPolicy()
        val onTwoM = adaptiveLinkOnTwoM(LePhy.TWO_M, moved.split)
        assertEquals(listOf(AdaptivePhyPolicy.Verdict.KEEP), policy.feed(10_000, 9, kbps = 400, onTwoM = onTwoM))
        val kept = split.onVerdict(writerFailed = false, reason = "kept")
        assertEquals(PhyDirectionSplit.Kind.REMEMBER, kept.kind)
        assertEquals(GattPhySplit.TX_2M_RX_1M, kept.split)
        assertEquals("one_pdu_per_event", kept.reason)
        assertEquals(PhyDirectionSplit.Kind.NONE, split.onVerdict(writerFailed = false, reason = "kept").kind)
    }

    @Test
    fun whenOnlyTheMirrorKeepsTheLinkTheMirrorIsRemembered() {
        val split = PhyDirectionSplit()
        split.onVerdict(writerFailed = true, reason = "stalled_one_way")
        val mirror = split.onVerdict(writerFailed = true, reason = "stalled_one_way")
        assertEquals(GattPhySplit.TX_1M_RX_2M, mirror.split)
        // The mirror's transmit PHY is 1M but its receive direction is still 2M: the link is still measured.
        assertTrue(adaptiveLinkOnTwoM(LePhy.ONE_M, mirror.split))
        val kept = split.onVerdict(writerFailed = false, reason = "kept")
        assertEquals(PhyDirectionSplit.Kind.REMEMBER, kept.kind)
        assertEquals(GattPhySplit.TX_1M_RX_2M, kept.split)
    }

    @Test
    fun aLinkThatStartedSplitFromTheMemoryIsNotRecordedAgainButCorrectsAWrongDirection() {
        val split = PhyDirectionSplit().also { it.startedSplit(GattPhySplit.TX_2M_RX_1M) }
        assertEquals(PhyDirectionSplit.Kind.NONE, split.onVerdict(writerFailed = false, reason = "kept").kind)
        // It is already split: receive silence does not split it again.
        assertTrue(split.replay(0L, 12_000L, onTwoM = false, quietAt = heardUntil(6_000L)).isEmpty())
        val mirror = split.onVerdict(writerFailed = true, reason = "one_pdu_per_event")
        assertEquals(PhyDirectionSplit.Kind.SPLIT, mirror.kind)
        assertEquals(GattPhySplit.TX_1M_RX_2M, mirror.split)
        assertEquals(GattPhySplit.TX_1M_RX_2M, split.onVerdict(writerFailed = false, reason = "kept").split)
    }

    @Test
    fun onlyAGattCentralLinkOnTheDefaultPhyTakesPart() {
        assertTrue(phyDirectionSplitFor(true, "gatt", null, null))
        assertFalse(phyDirectionSplitFor(false, "gatt", null, null))
        assertFalse(phyDirectionSplitFor(true, "coc", null, null))
        assertFalse(phyDirectionSplitFor(true, "gatt", GattPhySplit.TX_2M_RX_1M, null))
        assertFalse(phyDirectionSplitFor(true, "gatt", null, ExperimentalBlePhy.LE_1M))
    }

    @Test
    fun theSwitchIsADebugOnlyLaunchArm() {
        val asked: (String) -> Boolean = { it == "test_phy_direction_split" }
        assertTrue(PhySwitches.radioArms(debug = true, string = { null }, flag = asked).phyDirectionSplit)
        assertFalse(PhySwitches.radioArms(debug = false, string = { null }, flag = asked).phyDirectionSplit)
        assertFalse(PhySwitches.radioArms(debug = true, string = { null }, flag = { false }).phyDirectionSplit)
    }

    @Test
    fun anArmThatCannotTakeEffectIsReportedInert() {
        fun inert(vararg extras: Pair<String, Any>): List<SwitchInert.Inert> {
            val map = extras.toMap()
            return SwitchInert.inert(SwitchInert.Extras { map[it] })
        }
        // With adaptive PHY off nothing measures the link, so the direction split never runs.
        assertEquals(
            listOf(SwitchInert.Inert("phy_direction_split", "adaptive_phy=false")),
            inert("test_adaptive_phy" to false, "test_phy_direction_split" to true),
        )
        // With the direction split on, it decides instead of the fixed-direction split.
        assertEquals(
            listOf(SwitchInert.Inert("adaptive_phy_split", "phy_direction_split=true")),
            inert("test_phy_direction_split" to true, "test_adaptive_phy_split" to true),
        )
        assertTrue(inert("test_phy_direction_split" to true).isEmpty())
    }

    @Test
    fun aRememberedSplitIsFoundOnlyForItsPeerAndTransportUntilItExpires() {
        val now = 1_800_000_000_000L
        val gatt = BluetoothTransportOption.BLE_GATT
        val node = "a15a15a15a15"
        val ttl = AdaptivePhyMemory.TTL_MS
        val marks = mapOf(AdaptivePhyMemory.splitKey(gatt, GattPhySplit.TX_2M_RX_1M, node) to now - 60_000L)
        assertEquals(GattPhySplit.TX_2M_RX_1M, AdaptivePhyMemory.rememberedSplit(marks, gatt, node, now, ttl))
        assertNull(AdaptivePhyMemory.rememberedSplit(marks, gatt, "a5a5a5000001", now, ttl))
        assertNull(AdaptivePhyMemory.rememberedSplit(marks, BluetoothTransportOption.BLE_L2CAP, node, now, ttl))
        assertNull(AdaptivePhyMemory.rememberedSplit(marks, gatt, node, now + ttl, ttl))
    }

    @Test
    fun theNewerDirectionWinsAndALearnedStateResetClearsSplitsWithTheBadTwoMMarks() {
        val now = 1_800_000_000_000L
        val gatt = BluetoothTransportOption.BLE_GATT
        val node = "a15a15a15a15"
        val marks = mapOf(
            AdaptivePhyMemory.splitKey(gatt, GattPhySplit.TX_2M_RX_1M, node) to now - 120_000L,
            AdaptivePhyMemory.splitKey(gatt, GattPhySplit.TX_1M_RX_2M, node) to now - 60_000L,
        )
        assertEquals(GattPhySplit.TX_1M_RX_2M, AdaptivePhyMemory.rememberedSplit(marks, gatt, node, now, AdaptivePhyMemory.TTL_MS))
        // test_reset_learned_state (AdaptivePhyMemory.clearAll) removes split marks and bad-2M marks, never the purge flags.
        assertTrue(marks.keys.all { AdaptivePhyMemory.isMark(it) })
        assertTrue(AdaptivePhyMemory.isMark(AdaptivePhyMemory.key(gatt, node)))
        assertFalse(AdaptivePhyMemory.isMark(AdaptivePhyMemory.GATT_FANOUT_PURGED))
    }
}
