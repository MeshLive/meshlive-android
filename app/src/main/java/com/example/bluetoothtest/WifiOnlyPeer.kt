package com.example.bluetoothtest

import android.bluetooth.BluetoothAdapter

internal object WifiOnlyPeer {
    /** A locally administered address: no real adapter has it. Nothing may open a connection to it. */
    const val PLACEHOLDER_ADDRESS = "02:00:00:00:00:01"

    fun label(sighting: WifiSighting): String = "Wi-Fi [${sighting.key.take(8)}]"

    fun of(adapter: BluetoothAdapter, sighting: WifiSighting): BleL2capPeer = BleL2capPeer(
        device = adapter.getRemoteDevice(PLACEHOLDER_ADDRESS),
        label = label(sighting),
        announcement = sighting.announcement,
        advertisedName = null,
        wifi = sighting,
        wifiOnly = true,
    )

    /** The peer to show for a key that has both legs: the Bluetooth peer carrying the Wi-Fi sighting. */
    fun merged(ble: BleL2capPeer, sighting: WifiSighting?): BleL2capPeer =
        if (ble.wifi === sighting) ble else ble.copy(wifi = sighting, wifiOnly = false)
}
