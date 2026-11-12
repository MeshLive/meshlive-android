package com.example.bluetoothtest

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A9's pure Wi-Fi Direct rules: classification, bands, host frequency and the F-4 address checks. */
class DirectPolicyTest {
    private fun addr(text: String): Inet4Address = InetAddress.getByName(text) as Inet4Address

    private fun local(name: String, text: String, prefix: Int) = DirectAddressRules.LocalAddress(name, addr(text), prefix)

    @Test
    fun onlyFailuresWithoutAPeerAreLocalFaults() {
        val local = setOf(DirectFailure.PLATFORM_ERROR, DirectFailure.UNSUPPORTED, DirectFailure.BAD_CONFIG)
        for (failure in DirectFailure.entries) {
            assertEquals(failure.name, failure in local, DirectPolicy.countsAsLocalFault(failure))
        }
        assertFalse(DirectPolicy.countsAsLocalFault(DirectFailure.TIMEOUT))
    }

    @Test
    fun declineForCoversEveryFailureWithTheRuledWireValues() {
        for (failure in DirectFailure.entries) {
            assertTrue("${failure.name} has a decline", DirectPolicy.declineFor(failure).wire in 1..7)
        }
        assertEquals(2, DirectPolicy.declineFor(DirectFailure.BUSY).wire)
        assertEquals(2, DirectPolicy.declineFor(DirectFailure.FOREIGN_GROUP).wire)
        assertEquals(1, DirectPolicy.declineFor(DirectFailure.NO_PERMISSION).wire)
        assertEquals(1, DirectPolicy.declineFor(DirectFailure.UNSUPPORTED).wire)
        assertEquals(7, DirectPolicy.declineFor(DirectFailure.BAND_24).wire)
        assertEquals(3, DirectPolicy.declineFor(DirectFailure.SUBNET_OVERLAP).wire)
        assertEquals(6, DirectPolicy.declineFor(DirectFailure.CREDS_OFF_LINK).wire)
        assertEquals(6, DirectPolicy.declineFor(DirectFailure.TIMEOUT).wire)
    }

    @Test
    fun namesAreValidatedByTheShareRules() {
        val psk = "a".repeat(43)
        assertTrue(DirectPolicy.validNames("DIRECT-xy-abcdef", psk))
        assertFalse(DirectPolicy.validNames("DIRECT-xy-" + "a".repeat(23), psk)) // 33 bytes
        assertFalse(DirectPolicy.validNames("WIFI-xy-abcdef", psk))
        assertFalse(DirectPolicy.validNames("DIRECT-xy-abcdef", "a".repeat(7)))
    }

    @Test
    fun bandOkRequiresFiveGhzOrHigher() {
        assertFalse(DirectPolicy.bandOk(0))
        assertFalse(DirectPolicy.bandOk(2437))
        assertTrue(DirectPolicy.bandOk(5180))
        assertTrue(DirectPolicy.bandOk(5955))
    }

    @Test
    fun hostFrequencySkips24DfsAndSixGhz() {
        assertEquals(0, DirectPolicy.hostFrequency(null))
        assertEquals(0, DirectPolicy.hostFrequency(2437))
        assertEquals(0, DirectPolicy.hostFrequency(5260))
        assertEquals(0, DirectPolicy.hostFrequency(5500))
        assertEquals(0, DirectPolicy.hostFrequency(5955))
        assertEquals(5180, DirectPolicy.hostFrequency(5180))
        assertEquals(5745, DirectPolicy.hostFrequency(5745))
    }

    @Test
    fun p2pForegroundGateOnlyAppliesBeforeApi33() {
        assertTrue(DirectPolicy.p2pForegroundOk(33, appVisible = false, locationServiceHeld = false))
        assertTrue(DirectPolicy.p2pForegroundOk(32, appVisible = true, locationServiceHeld = false))
        assertTrue(DirectPolicy.p2pForegroundOk(29, appVisible = false, locationServiceHeld = true))
        assertFalse(DirectPolicy.p2pForegroundOk(32, appVisible = false, locationServiceHeld = false))
    }

    @Test
    fun hostAddressUsesTheReportedGroupInterfaceOnly() {
        val wlan = local("wlan0", "10.0.0.8", 24)
        val p2p = local("p2p-wlan0-0", "192.168.49.1", 24)
        assertEquals(p2p, DirectAddressRules.hostAddress(listOf(wlan, p2p), "p2p-wlan0-0"))
        assertNull(DirectAddressRules.hostAddress(listOf(wlan, p2p), "p2p-wlan1-0"))
        assertNull(DirectAddressRules.hostAddress(listOf(wlan, p2p), null))
        assertNull(DirectAddressRules.hostAddress(emptyList(), "p2p-wlan0-0"))
    }

    @Test
    fun acceptRemoteStaysInsideTheGroupSubnetAndRejectsTheLocalAddress() {
        val group = local("p2p-wlan0-0", "192.168.49.1", 24)
        assertTrue(DirectAddressRules.acceptRemote(group, addr("192.168.49.5")))
        assertFalse(DirectAddressRules.acceptRemote(group, addr("192.168.50.5")))
        assertFalse(DirectAddressRules.acceptRemote(group, group.address))
        assertFalse(DirectAddressRules.acceptRemote(group, InetAddress.getByName("fe80::1")))
    }

    @Test
    fun groupAddressFindsTheNewP2pInterface() {
        val wlan = local("wlan0", "10.0.0.8", 24)
        val p2p = local("p2p-wlan0-0", "192.168.137.5", 24)
        assertEquals(p2p, DirectAddressRules.groupAddress(listOf(wlan, p2p), "p2p-wlan0-0", emptySet()))
        assertEquals(p2p, DirectAddressRules.groupAddress(listOf(wlan, p2p), null, setOf("wlan0")))
        assertNull(DirectAddressRules.groupAddress(listOf(wlan), null, setOf("wlan0")))
        val other = local("rmnet0", "10.1.0.2", 24)
        assertNull(DirectAddressRules.groupAddress(listOf(wlan, other), null, setOf("wlan0")))
        assertNull(DirectAddressRules.groupAddress(listOf(wlan, p2p), null, setOf("wlan0", "p2p-wlan0-0")))
    }

    @Test
    fun credsOutsideTheJoinedSubnetIsRefused() {
        val sta = local("wlan0", "10.0.0.8", 24)
        val p2p = local("p2p-wlan0-0", "192.168.137.5", 24)
        assertFalse(DirectAddressRules.credsOnLink(p2p, addr("10.0.0.1")))
        assertTrue(DirectAddressRules.credsOnLink(p2p, addr("192.168.137.1")))
        assertFalse(DirectAddressRules.credsOnLink(p2p, p2p.address))
        assertFalse(DirectAddressRules.subnetOverlap(p2p, listOf(sta, p2p)))
    }

    @Test
    fun anOverlappingSubnetIsRefused() {
        val p2p = local("p2p-wlan0-0", "192.168.49.2", 24)
        assertTrue(DirectAddressRules.subnetOverlap(p2p, listOf(local("wlan0", "192.168.49.30", 24), p2p)))
        assertFalse(DirectAddressRules.subnetOverlap(p2p, listOf(local("wlan0", "192.168.1.5", 24), p2p)))
        assertTrue(DirectAddressRules.subnetOverlap(p2p, listOf(local("wlan0", "192.168.0.9", 16), p2p)))
    }

    @Test
    fun credsAddressMustBeAPrivateIpv4() {
        for (text in listOf("10.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.1.1")) {
            assertTrue(text, DirectAddressRules.credsAddressOk(addr(text).address))
        }
        for (text in listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.1", "8.8.8.8", "172.32.0.1")) {
            assertFalse(text, DirectAddressRules.credsAddressOk(addr(text).address))
        }
        assertFalse(DirectAddressRules.credsAddressOk(byteArrayOf(10, 0, 0)))
    }
}
