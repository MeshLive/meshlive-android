package com.example.bluetoothtest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattLinkBudgetTest {
    @After
    fun reset() = GattSwitches.resetToDefaults()

    private fun budget(sdk: Int, sysprop: Int? = null, system: Int? = 0, own: Int = 0, log: MutableList<String> = mutableListOf()) =
        GattLinkBudget(sdk, { sysprop }, { system }, { own }, { log += it })

    @Test
    fun theCeilingIsSevenUpToAndroid14AndThePropertyClampedFromAndroid15() {
        assertEquals(7, GattLinkBudget.ceilingFor(29, null))
        assertEquals(7, GattLinkBudget.ceilingFor(33, 12))
        assertEquals(7, GattLinkBudget.ceilingFor(34, 16))
        assertEquals(8, GattLinkBudget.ceilingFor(35, null))
        assertEquals(8, GattLinkBudget.ceilingFor(35, 3))
        assertEquals(12, GattLinkBudget.ceilingFor(35, 12))
        assertEquals(16, GattLinkBudget.ceilingFor(36, 40))
        // The property is only read on Android 15+ (older stacks have a build-time constant).
        assertEquals(7, budget(34, sysprop = 16).statedCeiling())
        assertEquals(16, budget(35, sysprop = 16).statedCeiling())
    }

    @Test
    fun otherHardCeilingsFollowTheSdk() {
        val a12 = GattLinkBudget.limitsFor(32, 7)
        assertEquals(13, a12.aclLinks)
        assertEquals(32, a12.l2capChannels)
        assertEquals(7, a12.dmPeers)
        val a13 = GattLinkBudget.limitsFor(33, 7)
        assertEquals(16, a13.aclLinks)
        assertEquals(32, a13.l2capChannels)
        assertNull(a13.dmPeers)
        val a14 = GattLinkBudget.limitsFor(34, 7)
        assertEquals(64, a14.l2capChannels)
        assertEquals(32, a14.gattClients)
        assertEquals(128, a14.lePsms)
        assertEquals(30, a14.rfcommPorts)
        // Seven links leave 32 - 21 = 11 channels on Android 10-13 before any CoC.
        assertEquals(11, budget(32).l2capChannelsFree(links = 7, cocChannels = 0))
        assertEquals(6, budget(32).l2capChannelsFree(links = 7, cocChannels = 5))
        assertEquals(0, budget(32).l2capChannelsFree(links = 7, cocChannels = 40))
        assertEquals(43, budget(34).l2capChannelsFree(links = 7, cocChannels = 0))
    }

    @Test
    fun aRelayKeepsOneSlotForItsUpstreamParent() {
        // Android 14, six links up (parent among them): one child fits.
        val withParent = budget(34, system = 6).admitChild(upstreamParentLinked = true, enforce = true)
        assertTrue(withParent.allowed)
        assertEquals(1, withParent.snapshot.childSlotsFree)
        // Seven links up: relay full, with its own reason.
        val full = budget(34, system = 7).admitChild(upstreamParentLinked = true, enforce = true)
        assertFalse(full.allowed)
        assertEquals("relay full", full.reason)
        // Parent not yet linked: its slot stays reserved, so five links up already leaves one child slot.
        assertTrue(budget(34, system = 5).admitChild(upstreamParentLinked = false, enforce = true).allowed)
        assertFalse(budget(34, system = 6).admitChild(upstreamParentLinked = false, enforce = true).allowed)
    }

    @Test
    fun theLargerOfTheStackCountAndOwnLinksIsUsed() {
        // A watch holds 2 links the app never made; the stack reports them, own count does not.
        assertEquals(5, budget(34, system = 5, own = 3).snapshot(true).used)
        // getConnectedDevices missed the CoC-only links but the registry knows them.
        assertEquals(4, budget(34, system = 1, own = 4).snapshot(true).used)
        // An unreadable stack count falls back to the registry.
        assertEquals(2, budget(34, system = null, own = 2).snapshot(true).used)
    }

    @Test
    fun theRefusalIsOnlyEnforcedByTheExperimentalSwitch() {
        val log = mutableListOf<String>()
        val full = budget(34, system = 7, log = log)
        assertTrue(full.admitChild(true).allowed) // default: logged, not enforced
        assertTrue(log.single(), "fits=false enforced=false" in log.single())
        GattSwitches.applyExtras({ key, default -> if (key == "test_link_budget_admission") true else default }, { _, d -> d }, { null })
        // A release build ignores the launch extra: the refusal is never enforced.
        assertEquals(!BuildConfig.DEBUG, full.admitChild(true).allowed)
    }

    @Test
    fun theFirstUnexplainedDropLowersTheCeilingForTheRun() {
        val b = budget(34, system = 4)
        assertEquals(7, b.snapshot(true).ceiling)
        b.onUnexplainedDrop(linksBeforeDrop = 5)
        assertEquals(4, b.snapshot(true).ceiling)
        assertEquals(0, b.snapshot(true).childSlotsFree)
        // A later drop at a higher count never raises it again.
        b.onUnexplainedDrop(linksBeforeDrop = 6)
        assertEquals(4, b.snapshot(true).ceiling)
        b.onUnexplainedDrop(linksBeforeDrop = 3)
        assertEquals(2, b.snapshot(true).ceiling)
        b.resetLearned()
        assertEquals(7, b.snapshot(true).ceiling)
        val line = b.line("test")
        assertTrue(line, "BENCHMARK gatt_link_budget: event=test | sdk=34 | stated=7" in line)
        assertTrue(line, "aclMax=16 | l2capChannelsMax=64" in line)
    }

    @Test
    fun sourceRejectionKeepsTheReasonDistinct() {
        val policy = SourceCapacityRejectionPolicy()
        policy.reject("viewer-a", 0L, SourceCapacityRejectionPolicy.REASON_RELAY_FULL)
        policy.reject("viewer-b", 0L)
        assertEquals("relay full", policy.reasonFor("viewer-a"))
        assertEquals("capacity", policy.reasonFor("viewer-b"))
        assertNull(policy.reasonFor("viewer-c"))
        assertTrue(policy.shouldSuppress("viewer-a", 1L, capacityAvailable = false, previousParentStale = false))
        assertFalse(policy.shouldSuppress("viewer-a", 1L, capacityAvailable = true, previousParentStale = false))
        assertNull(policy.reasonFor("viewer-a"))
    }
}
