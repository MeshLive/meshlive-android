package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP5 item 1, design decision 2026-11-06 (DECISIONS "Relay admission without a measured ceiling"): with no fresh relay
 * ceiling the relay budget is at least 2 x the per-link load, however small the relay load this phone proved before
 * (Experimental `test_relay_no_ceiling_floor`). The numbers replay rm02c-chain-20261106: 640x480 at 15 FPS and 400 kbps
 * needs 2 x 523.4 = 1047 kbps; the ASUS (relay proven 370) and the S24 on CoC (proven 495) were refused at 893 and 1018
 * while a phone with proven 0 was admitted at 1047.
 */
class RelayNoCeilingFloorTest {
    private val now = 1_000_000_000L
    private val perLink = MeshBandwidthEstimator.perLinkKbps(400, 15)
    private val prior = MeshBandwidthEstimator.safeAggregateKbps(BluetoothTransportOption.BLE_L2CAP)

    /** The admission budget as AndroidVideoReceiver computes it: the measured budget, then the floor the switches allow. */
    private fun budget(learned: LearnedLinkCapacity, coldStartOn: Boolean, noCeilingOn: Boolean, priorKbps: Double = prior): Double {
        val measured = LinkCapacityPolicy.budgetKbps(learned, priorKbps, perLink, now)
        val floor = LinkCapacityPolicy.relayFloor(learned, now, coldStartOn, noCeilingOn)
        return LinkCapacityPolicy.relayBudgetKbps(measured, perLink, floor != LinkCapacityPolicy.FLOOR_NONE)
    }

    @Test
    fun aPhoneThatRelayedASmallStreamBeforeIsNoLongerRefusedALargerOne() {
        for (proven in listOf(370, 495)) {
            val learned = LearnedLinkCapacity(provenKbps = proven)
            // Control (today): the larger of the 700 kbps prior and proven + one link, short of the two links a relay needs.
            assertFalse("proven $proven control", relayFits(budget(learned, coldStartOn = true, noCeilingOn = false), perLink))
            assertEquals(LinkCapacityPolicy.FLOOR_NONE, LinkCapacityPolicy.relayFloor(learned, now, true, false))
            // On: no fresh ceiling, so the budget is at least the two links, as a never-measured phone gets at cold start.
            assertEquals(LinkCapacityPolicy.FLOOR_NO_CEILING, LinkCapacityPolicy.relayFloor(learned, now, true, true))
            assertTrue("proven $proven on", relayFits(budget(learned, coldStartOn = true, noCeilingOn = true), perLink))
        }
    }

    @Test
    fun aFreshRelayCeilingKeepsTheMeasuredOverloadGuard() {
        // A saturation recorded a minute ago: the floor never applies over a measured overload.
        val saturated = LearnedLinkCapacity(provenKbps = 495, ceilingKbps = 600, ceilingMeasuredAtMs = now - 60_000)
        assertEquals(LinkCapacityPolicy.FLOOR_NONE, LinkCapacityPolicy.relayFloor(saturated, now, true, true))
        assertFalse(relayFits(budget(saturated, coldStartOn = true, noCeilingOn = true), perLink))
        // The same ceiling 6 hours later is no longer fresh (re-checked like every ceiling): the floor applies.
        val expired = saturated.copy(ceilingMeasuredAtMs = now - LinkCapacityPolicy.CEILING_TTL_MS)
        assertEquals(LinkCapacityPolicy.FLOOR_NO_CEILING, LinkCapacityPolicy.relayFloor(expired, now, true, true))
        assertTrue(relayFits(budget(expired, coldStartOn = true, noCeilingOn = true), perLink))
    }

    @Test
    fun theColdStartKeepsItsNameAndTheSwitchOffIsTodaysRuleExactly() {
        val fresh = LearnedLinkCapacity()
        assertEquals(LinkCapacityPolicy.FLOOR_COLD_START, LinkCapacityPolicy.relayFloor(fresh, now, true, true))
        assertEquals(LinkCapacityPolicy.FLOOR_COLD_START, LinkCapacityPolicy.relayFloor(fresh, now, true, false))
        // With the cold start's control arm off, the floor still covers a phone that never relayed.
        assertEquals(LinkCapacityPolicy.FLOOR_NO_CEILING, LinkCapacityPolicy.relayFloor(fresh, now, false, true))
        assertEquals(LinkCapacityPolicy.FLOOR_NONE, LinkCapacityPolicy.relayFloor(fresh, now, false, false))
        // Off: a floor applies exactly when today's cold start does, for every kind of relay evidence.
        val states = listOf(
            fresh,
            LearnedLinkCapacity(provenKbps = 370),
            LearnedLinkCapacity(provenKbps = 918),
            LearnedLinkCapacity(provenKbps = 0, ceilingKbps = 450, ceilingMeasuredAtMs = now - 60_000),
            LearnedLinkCapacity(provenKbps = 0, ceilingKbps = 450, ceilingMeasuredAtMs = now - LinkCapacityPolicy.CEILING_TTL_MS),
            LearnedLinkCapacity(provenKbps = 495, ceilingKbps = 600, ceilingMeasuredAtMs = now - 60_000),
        )
        for (learned in states) {
            for (coldStartOn in listOf(true, false)) {
                val floorOn = LinkCapacityPolicy.relayFloor(learned, now, coldStartOn, false) != LinkCapacityPolicy.FLOOR_NONE
                assertEquals("$learned coldStart=$coldStartOn", coldStartOn && LinkCapacityPolicy.relayColdStart(learned, now), floorOn)
            }
        }
    }

    @Test
    fun theFloorNeverLowersAMeasuredBudgetAndAdmitsExactlyOneChild() {
        // S24 on GATT (probe 1341, relay proven 918): 918 + 523 = 1441 already fits and stays as measured.
        val strong = LearnedLinkCapacity(provenKbps = 918)
        val measured = LinkCapacityPolicy.budgetKbps(strong, 1341.0, perLink, now)
        assertEquals(measured, budget(strong, coldStartOn = true, noCeilingOn = true, priorKbps = 1341.0), 0.0)
        // A weak phone gets exactly the incoming link plus one child: never room for a second forwarded link.
        val floored = budget(LearnedLinkCapacity(provenKbps = 370), coldStartOn = true, noCeilingOn = true)
        assertEquals(2 * perLink, floored, 1e-9)
        assertFalse(floored >= 3 * perLink)
    }
}
