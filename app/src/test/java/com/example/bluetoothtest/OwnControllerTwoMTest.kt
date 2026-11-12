package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Experimental `test_own_2m_memory` ([OwnControllerTwoM]): this phone's own symmetric 2M call setup stalls as the dialing
 * central move later links to peers with no memory key (unrecognised rotating offers) to 1M, and nothing else.
 */
class OwnControllerTwoMTest {
    private class MapStore : OwnControllerTwoM.Store {
        val values = HashMap<String, Long>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: Long) {
            values[key] = value
        }
        override fun remove(keys: Collection<String>) {
            keys.forEach(values::remove)
        }
    }

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val prefix = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f)
    private lateinit var store: MapStore

    @Before
    fun setUp() {
        store = MapStore()
        AdaptivePhyMemory.ownTwoMOverride = store
    }

    @After
    fun tearDown() {
        AdaptivePhyMemory.ownTwoMOverride = null
        PhySwitches.applyFlags { false }
        DebugTunables.experimentalBlePhy = null
    }

    private fun switchOn() {
        assumeTrue("DEBUG arm", BuildConfig.DEBUG)
        PhySwitches.applyFlags { it == "test_own_2m_memory" }
    }

    /** A call dial to a stranger: an unrecognised rotating offer has no memory key. */
    private fun strangerCall(transport: BluetoothTransportOption = BluetoothTransportOption.BLE_GATT) =
        AdaptivePhyMemory.phyFor(transport, prefix, BlePhyPreference.TWO_M, "call", relayedOffer = false, memoryNode = null)

    private fun stallsAt(vararg agoMs: Long) {
        val now = System.currentTimeMillis()
        agoMs.forEach { AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - it) }
    }

    @Test
    fun twoStallsInADayMoveTheNextCallToAStrangerToOneM() {
        switchOn()
        stallsAt(3 * hour, 1 * hour)
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, strangerCall())
        // The controller is the same whatever the LE transport.
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, strangerCall(BluetoothTransportOption.BLE_L2CAP))
        // Classic has no LE PHY.
        assertEquals(BlePhyPreference.TWO_M, strangerCall(BluetoothTransportOption.CLASSIC_RFCOMM))
    }

    @Test
    fun withTheSwitchOffTheSameStallsChangeNothing() {
        stallsAt(3 * hour, 1 * hour)
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
        assertTrue(store.values.isEmpty())
        // Marks written while the switch was on are not read with it off.
        switchOn()
        stallsAt(3 * hour, 1 * hour)
        PhySwitches.applyFlags { false }
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun oneStallIsNotEnough() {
        switchOn()
        stallsAt(1 * hour)
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun twoStallsMoreThanADayApartAreNotARun() {
        switchOn()
        stallsAt(day + hour, 0L)
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun aCleanSymmetricTwoMSetupBetweenTwoStallsBreaksTheRun() {
        switchOn()
        val now = System.currentTimeMillis()
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - 3 * hour)
        AdaptivePhyMemory.recordOwnTwoMSuccess(now - 2 * hour)
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - 1 * hour)
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun aPeerWithAMemoryKeyAndARelayedOfferKeepTheirPerPeerRules() {
        switchOn()
        stallsAt(3 * hour, 1 * hour)
        // A recognised contact (or a stable advertised ID) with no per-peer mark stays on 2M, as before.
        assertEquals(
            BlePhyPreference.TWO_M,
            AdaptivePhyMemory.phyFor(BluetoothTransportOption.BLE_GATT, prefix, BlePhyPreference.TWO_M, "call", memoryNode = "a1b2c3d4e5f6"),
        )
        assertEquals(
            BlePhyPreference.TWO_M,
            AdaptivePhyMemory.phyFor(BluetoothTransportOption.BLE_GATT, prefix, BlePhyPreference.TWO_M, "stream"),
        )
        // A relayed offer is looked up under its advertised prefix by the via memory, not by this mark.
        assertEquals(
            BlePhyPreference.TWO_M,
            AdaptivePhyMemory.phyFor(
                BluetoothTransportOption.BLE_GATT, prefix, BlePhyPreference.TWO_M, "call", relayedOffer = true, memoryNode = null,
            ),
        )
    }

    @Test
    fun aBlePhyArmKeepsItsPhy() {
        switchOn()
        stallsAt(3 * hour, 1 * hour)
        DebugTunables.experimentalBlePhy = ExperimentalBlePhy.CODED_S2
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun theMarkExpiresWithTheBadTwoMMarksExpiry() {
        switchOn()
        val now = System.currentTimeMillis()
        val ttl = AdaptivePhyMemory.ttlMs()
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - ttl - 2 * hour)
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - ttl - 1 * hour)
        assertEquals(BlePhyPreference.TWO_M, AdaptivePhyMemory.ownControllerPhy(BluetoothTransportOption.BLE_GATT, BlePhyPreference.TWO_M, "call", now))
        // Just inside the expiry it still applies.
        assertEquals(
            BlePhyPreference.ONE_M_ADAPTIVE,
            AdaptivePhyMemory.ownControllerPhy(BluetoothTransportOption.BLE_GATT, BlePhyPreference.TWO_M, "call", now - 2 * hour),
        )
    }

    @Test
    fun aFurtherStallRefreshesTheMark() {
        switchOn()
        val now = System.currentTimeMillis()
        val ttl = AdaptivePhyMemory.ttlMs()
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - ttl - 2 * hour)
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - ttl - 1 * hour)
        // One more stall while the mark was still fresh (a recognised peer still dials 2M) keeps it for another expiry.
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - ttl + hour)
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, strangerCall())
    }

    @Test
    fun twoCleanSymmetricTwoMSetupsInARowClearTheMarkButOneDoesNot() {
        switchOn()
        val now = System.currentTimeMillis()
        stallsAt(5 * hour, 4 * hour)
        AdaptivePhyMemory.recordOwnTwoMSuccess(now - 3 * hour)
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, strangerCall())
        // A stall in between restarts the count.
        AdaptivePhyMemory.recordOwnTwoMStall("setup_stall", now - 2 * hour)
        AdaptivePhyMemory.recordOwnTwoMSuccess(now - 1 * hour)
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, strangerCall())
        AdaptivePhyMemory.recordOwnTwoMSuccess(now)
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun theLearnedStateResetClearsTheMark() {
        switchOn()
        stallsAt(3 * hour, 1 * hour)
        assertEquals(BlePhyPreference.ONE_M_ADAPTIVE, strangerCall())
        AdaptivePhyMemory.clearAll()
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
        // The pending run is gone too: one more stall does not mark.
        stallsAt(3 * hour)
        AdaptivePhyMemory.clearAll()
        stallsAt(1 * hour)
        assertEquals(BlePhyPreference.TWO_M, strangerCall())
    }

    @Test
    fun theStoredMemoryNamesNoPeerAndCountsAsALearnedMark() {
        switchOn()
        stallsAt(3 * hour, 1 * hour)
        assertTrue(store.values.isNotEmpty())
        store.values.keys.forEach { key ->
            assertTrue(key, AdaptivePhyMemory.isMark(key))
            assertFalse(key, key.contains("0a0b0c0d0e0f"))
        }
    }

    @Test
    fun onlyASymmetricTwoMDialToAnIdleHopIsEvidence() {
        fun evidence(
            setupPhy: BlePhyPreference = BlePhyPreference.TWO_M,
            le: Boolean = true,
            idleHop: Boolean = true,
            splitApplied: Boolean = false,
            blePhyArm: Boolean = false,
            staticSplit: Boolean = false,
        ) = OwnControllerTwoM.symmetricTwoMSetup(setupPhy, le, idleHop, splitApplied, blePhyArm, staticSplit)
        assertTrue(evidence())
        // A dial already moved to 1M (remembered peer, this mark, 1M first) cannot stall on 2M.
        assertFalse(evidence(setupPhy = BlePhyPreference.ONE_M_ADAPTIVE))
        // A relayed offer's hop relays another link on the same radio.
        assertFalse(evidence(idleHop = false))
        assertFalse(evidence(le = false))
        assertFalse(evidence(splitApplied = true))
        assertFalse(evidence(blePhyArm = true))
        assertFalse(evidence(staticSplit = true))
    }
}
