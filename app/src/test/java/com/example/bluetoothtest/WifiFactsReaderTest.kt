package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facts reader: inert with every switch off, per-API permissions, the free data-path formula and DEBUG arms. */
class WifiFactsReaderTest {
    private class FakePlatform : WifiPlatformReader {
        var sdk = 33
        var calls = 0
        var featureAware = true
        var featureDirect = true
        var available = true
        var attached: Boolean? = false
        var wifi = true
        var sta: Int? = null
        var location = true
        var nearbyGranted = true
        var fineGranted = true
        var battery = 100
        var chargingValue = false
        var saver = false
        var thermal = 0
        var hotspot = false
        var free: Int? = 4
        var ndp: Int? = 4
        var icm = false
        var caps: AwareCapsSummary? = null

        private fun count() {
            calls++
        }

        override fun sdkInt(): Int = sdk // never counted: the static Build.VERSION.SDK_INT [Am 2a]

        override fun hasFeature(name: String): Boolean {
            count()
            return if (name.contains("aware")) featureAware else featureDirect
        }

        override fun awareAvailable(): Boolean {
            count()
            return available
        }

        override fun deviceAttached(): Boolean? {
            count()
            return attached
        }

        override fun wifiEnabled(): Boolean {
            count()
            return wifi
        }

        override fun staFrequencyMhz(): Int? {
            count()
            return sta
        }

        override fun locationEnabled(): Boolean {
            count()
            return location
        }

        override fun granted(permission: String): Boolean {
            count()
            return if (permission.contains("NEARBY")) nearbyGranted else fineGranted
        }

        override fun batteryPercent(): Int {
            count()
            return battery
        }

        override fun charging(): Boolean {
            count()
            return chargingValue
        }

        override fun batterySaver(): Boolean {
            count()
            return saver
        }

        override fun thermalStatus(): Int {
            count()
            return thermal
        }

        override fun hotspotActive(): Boolean {
            count()
            return hotspot
        }

        override fun awareFreeNdp(): Int? {
            count()
            return free
        }

        override fun characteristicsNdp(): Int? {
            count()
            return ndp
        }

        override fun icmSupported(): Boolean {
            count()
            return icm
        }

        override fun characteristics(): AwareCapsSummary? {
            count()
            return caps
        }
    }

    private class FakePolicy : WifiPolicyView {
        var calls = 0
        var aware = false
        var direct = false
        var max: Int? = null
        override fun awareOff(): Boolean {
            calls++
            return aware
        }
        override fun directOff(): Boolean {
            calls++
            return direct
        }
        override fun maxNdp(): Int? {
            calls++
            return max
        }
    }

    private class Fakes {
        val platform = FakePlatform()
        val policy = FakePolicy()
        val ledger = WifiLedger { 0L }
        var awareHeld = false
        var links = 0
        var switches = WifiSwitches(carrier = true, widerReach = false, direct = false)
        fun reader() = WifiFactsReader(
            platform,
            ledger,
            WifiSwitchSource { switches },
            policy,
            { awareHeld },
            { links },
            { 0L },
        )
    }

    private fun freeNdp(ndp: Int?, free: Int?, observed: Int? = null, reserved: Int = 0, policyMax: Int? = null): Int {
        val f = Fakes()
        f.platform.ndp = ndp
        f.platform.free = free
        f.policy.max = policyMax
        if (observed != null) f.ledger.noteNdpRefusedWhileActive(observed)
        repeat(reserved) { f.ledger.reserveNdp("owner$it", 99) }
        return f.reader().freeNdp()
    }

    @Test
    fun unknownBatteryReadsAsFullSoItNeverBlocks() {
        // A stub or unreadable battery is read as full and not charging (AndroidWifiPlatformReader), so it never blocks a call.
        val f = Fakes()
        val facts = f.reader().read()
        assertEquals(100, facts.batteryPercent)
        assertFalse(facts.charging)
        assertTrue(WifiEligibility.decide(facts).aware)
    }

    @Test
    fun otherClientIsTheStoredValueWhileWeHoldAnAttach() {
        val f = Fakes()
        f.platform.attached = true
        assertEquals(true, f.reader().read().otherAwareClientAttached)

        f.awareHeld = true
        f.ledger.otherClientAtAttach = false
        assertEquals(false, f.reader().read().otherAwareClientAttached)

        f.platform.sdk = 30
        assertNull(f.reader().read().otherAwareClientAttached)
    }

    @Test
    fun freeNdpTakesTheSmallestOfEveryLimit() {
        assertEquals(4, freeNdp(ndp = 8, free = null))
        assertEquals(1, freeNdp(ndp = null, free = null))
        assertEquals(0, freeNdp(ndp = 8, free = null, observed = 2, reserved = 2))
        assertEquals(1, freeNdp(ndp = 8, free = 1))
        assertEquals(2, freeNdp(ndp = 3, free = null, reserved = 1))
        assertEquals(0, freeNdp(ndp = 8, free = -5))
        assertEquals(1, freeNdp(ndp = 8, free = null, policyMax = 1))
    }

    @Test
    fun locationEnabledIsAlwaysTrueFromApi33() {
        val f = Fakes()
        f.platform.location = false
        assertTrue(f.reader().read().locationEnabled)
        f.platform.sdk = 31
        assertFalse(f.reader().read().locationEnabled)
        f.platform.location = true
        assertTrue(f.reader().read().locationEnabled)
    }

    @Test
    fun permissionsAreReadPerApi() {
        val f = Fakes()
        f.platform.nearbyGranted = true
        f.platform.fineGranted = false
        val modern = f.reader().read()
        assertTrue(modern.permissionNearby)
        assertFalse(modern.permissionFineLocation)

        f.platform.sdk = 31
        f.platform.fineGranted = true
        val legacy = f.reader().read()
        assertFalse("the nearby permission does not exist below 33", legacy.permissionNearby)
        assertTrue(legacy.permissionFineLocation)
    }

    @Test
    fun pauseAndDirectGroupComeFromTheLedger() {
        val f = Fakes()
        f.ledger.recordLocalFault("a")
        f.ledger.recordLocalFault("b")
        f.ledger.recordLocalFault("c")
        assertEquals(DirectReserve.OK, f.ledger.reserveDirect("direct"))
        val facts = f.reader().read()
        assertEquals(f.ledger.pauseUntilMs, facts.localFaultPauseUntilMs)
        assertTrue(facts.directGroupActive)
    }

    @Test
    fun awareAttachHeldByThisAppIsTrueOnlyWhileALinkIsLive() {
        val f = Fakes()
        f.awareHeld = true
        assertFalse("a discovery hold is not an attempt or carrier", f.reader().read().awareAttachHeldByThisApp)
        f.links = 1
        assertTrue(f.reader().read().awareAttachHeldByThisApp)
    }

    @Test
    fun icmComesFromThePlatformReader() {
        val f = Fakes()
        f.platform.sdk = 31
        f.platform.icm = false
        assertFalse(f.reader().read().awareIcmSupported)
        f.platform.icm = true
        assertTrue(f.reader().read().awareIcmSupported)
    }

    @Test
    fun aSwitchedOffReaderMakesNoPlatformCall() {
        val f = Fakes()
        f.switches = WifiSwitches(false, false, false)
        val off = f.reader().read()
        assertEquals(0, f.platform.calls)
        assertEquals(0, f.policy.calls)
        assertEquals(f.switches, off.switches)

        f.switches = WifiSwitches(false, true, true)
        val subsOn = f.reader().read()
        assertEquals(0, f.platform.calls)
        assertEquals(0, f.policy.calls)
        assertFalse(subsOn.switches.carrier)
        assertEquals(0, subsOn.freeNdp)
    }

    @Test
    fun subSwitchesNeedTheCarrierSwitch() {
        val carrier = DebugTunables.wifiCarrier
        val wider = DebugTunables.wifiWiderReach
        val direct = DebugTunables.wifiDirect
        try {
            DebugTunables.wifiCarrier = false
            DebugTunables.wifiWiderReach = true
            DebugTunables.wifiDirect = true
            val off = DefaultWifiSwitches.read()
            assertFalse(off.carrier)
            assertFalse("Wider reach depends on the carrier switch", off.widerReach)
            assertFalse("Wi-Fi Direct depends on the carrier switch", off.direct)

            DebugTunables.wifiCarrier = true
            if (!BuildConfig.DEBUG) {
                // The DEBUG fallback source: a release build reads all off (the catalog source is installed at start).
                assertFalse(DefaultWifiSwitches.read().any)
                return
            }
            val on = DefaultWifiSwitches.read()
            assertTrue(on.carrier)
            assertTrue(on.widerReach)
            assertTrue(on.direct)
        } finally {
            DebugTunables.wifiCarrier = carrier
            DebugTunables.wifiWiderReach = wider
            DebugTunables.wifiDirect = direct
        }
    }

    @Test
    fun aPreemptableReservationCountsAsFree() {
        val f = Fakes()
        f.platform.ndp = 1
        f.platform.free = 0
        assertTrue(f.ledger.reserveUntrustedNdp("n1", 1) {})
        assertEquals(1, f.reader().freeNdp())
    }

    @Test
    fun onAOnePathChipOurOwnHopPreemptsTheStrangerInsteadOfOverReserving() {
        val f = Fakes()
        f.platform.ndp = 1
        f.platform.free = 1
        val reader = f.reader()
        var strangerClosed = 0
        assertTrue("a stranger takes the only path", f.ledger.reserveUntrustedNdp("native-1", reader.ndpCapacity()) { strangerClosed++ })
        f.platform.free = 0 // the chip now has its one data path in use
        assertTrue("our hop is still eligible", reader.read().freeNdp > 0)

        assertTrue(f.ledger.reserveNdp("wifi-1", reader.ndpCapacity()))

        assertEquals("the stranger's link is closed once", 1, strangerClosed)
        assertEquals("never two reservations on a one-path chip", 1, f.ledger.ndpReserved)
        assertEquals(0, f.ledger.untrustedCount)
        assertEquals(0, reader.read().freeNdp)
    }

    @Test
    fun aSecondPathIsTakenWithoutPreemptingTheStrangerWhenTheChipHasOne() {
        val f = Fakes()
        f.platform.ndp = 2
        f.platform.free = 2
        val reader = f.reader()
        var strangerClosed = 0
        assertTrue(f.ledger.reserveUntrustedNdp("native-1", reader.ndpCapacity()) { strangerClosed++ })
        f.platform.free = 1

        assertTrue(f.ledger.reserveNdp("wifi-1", reader.ndpCapacity()))

        assertEquals(0, strangerClosed)
        assertEquals(2, f.ledger.ndpReserved)

        f.platform.free = 0
        assertTrue("a second hop of ours now takes the stranger's path", f.ledger.reserveNdp("wifi-2", reader.ndpCapacity()))
        assertEquals(1, strangerClosed)
        assertEquals(2, f.ledger.ndpReserved)
    }
}
