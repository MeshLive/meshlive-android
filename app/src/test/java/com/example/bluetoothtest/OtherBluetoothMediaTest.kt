package com.example.bluetoothtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AP10: what a call counts as "other Bluetooth media on this phone" for the 2.4 GHz-alone Aware rule. */
class OtherBluetoothMediaTest {
    private fun forCall(
        liveHops: Int = 0,
        streamSendLinks: Int = 0,
        streamReceiveLinks: Int = 0,
        relayDuty: Boolean = false,
        manualRelays: Int = 0,
    ) = OtherBluetoothMedia.forCall(liveHops, streamSendLinks, streamReceiveLinks, relayDuty, manualRelays)

    @Test
    fun aCallAloneOnThePhoneCarriesNoOtherBluetoothMedia() {
        // The call's own link (its Bluetooth standby once media moves) is in none of the inputs.
        assertFalse(forCall())
    }

    @Test
    fun aStreamThisPhoneServesWatchesOrForwardsIsOtherBluetoothMedia() {
        assertTrue("a viewer or forwarder child", forCall(liveHops = 1, streamSendLinks = 1))
        assertTrue("the parent it watches or forwards from", forCall(liveHops = 1, streamReceiveLinks = 1))
        assertTrue("a hop whose media is on Wi-Fi at the moment can return to Bluetooth", forCall(liveHops = 1))
        assertTrue("stream media still counted on Bluetooth", forCall(streamSendLinks = 1))
        assertTrue("a relay fed over Bluetooth", forCall(streamReceiveLinks = 1))
    }

    @Test
    fun relayingAnotherCallIsOtherBluetoothMedia() {
        assertTrue("relay duty before its hops exist", forCall(relayDuty = true))
        assertTrue("the two hops of a relayed call", forCall(liveHops = 2, relayDuty = true))
    }

    @Test
    fun theManualStreamRelayIsOtherBluetoothMedia() {
        assertTrue(forCall(manualRelays = 1))
    }

    @Test
    fun aManualStreamRelayIsSeenByACallWhileItRuns() {
        OtherBluetoothMedia.manualRelayStarted()
        try {
            assertTrue(OtherBluetoothMedia.forCall(relayDuty = false))
        } finally {
            OtherBluetoothMedia.manualRelayStopped()
        }
    }

    @Test
    fun aCallEndpointOnRelayDutySeesOtherBluetoothMedia() {
        assertTrue(OtherBluetoothMedia.forCall(relayDuty = true))
    }
}
