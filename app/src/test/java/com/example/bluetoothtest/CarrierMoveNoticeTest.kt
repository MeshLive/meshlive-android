package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** UI audit 2026-11-10, UI-04: the call screen's line for a carrier move is its own short notice, not the log status. */
class CarrierMoveNoticeTest {
    @Test
    fun aMoveToWifiAndBackAreTheTwoNotices() {
        assertEquals(WifiCallNotices.Move.NOW_ON_WIFI, WifiCallNotices.move(WifiCallNotices.MOVED_TO_WIFI_STATUS))
        assertEquals(WifiCallNotices.Move.BACK_ON_BLUETOOTH, WifiCallNotices.move(WifiCallNotices.BACK_ON_BLUETOOTH_STATUS))
    }

    @Test
    fun everyOtherCallStatusKeepsItsOwnText() {
        assertNull(WifiCallNotices.move(ACTIVE_CALL_STATUS))
        // The Wi-Fi notices that explain a fallback are not carrier-move lines.
        assertNull(WifiCallNotices.move(WifiCallNotices.BLUETOOTH_LOST_ON_WIFI))
        assertNull(WifiCallNotices.move(WifiCallNotices.SWITCH_OFF_WIFI_ONLY))
        assertNull(WifiCallNotices.move(WifiCallNotices.WIFI_CONNECT_FAILED))
        assertNull(WifiCallNotices.move(WifiCallNotices.WIFI_NATIVE_ENDED))
        assertNull(WifiCallNotices.move(""))
    }
}
