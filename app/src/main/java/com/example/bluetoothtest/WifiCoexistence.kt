package com.example.bluetoothtest

import android.content.Context
import android.net.wifi.WifiManager

/**
 * 2.4 GHz Wi-Fi shares the antenna/band with Bluetooth and the chip time-slices between them. On
 * the M31 (Wi-Fi at 2472 MHz) GATT measured 0.39-0.46 Mbps and CoC 0.56 Mbps; with Wi-Fi off the
 * same links measured 0.89 and 0.95 Mbps. Apps cannot change the Wi-Fi band, so the phone detects
 * it, tells the user and keeps its learned capacity separate for that radio condition.
 */
internal fun wifiSharesBluetoothBand(frequencyMhz: Int?): Boolean = frequencyMhz != null && frequencyMhz in 2_400..2_500

object WifiCoexistence {
    @Volatile private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun connectedFrequencyMhz(): Int? = appContext?.let { connectedFrequencyMhz(it) }

    /** The band of this phone's own Wi-Fi client connection, read with the given context (A4's platform reader has no global attach). */
    @Suppress("DEPRECATION")
    fun connectedFrequencyMhz(context: Context): Int? = runCatching {
        val manager = context.getSystemService(WifiManager::class.java) ?: return null
        if (!manager.isWifiEnabled) null else manager.connectionInfo?.frequency?.takeIf { it > 0 }
    }.getOrNull()

    fun sharesBluetoothBand(): Boolean = wifiSharesBluetoothBand(connectedFrequencyMhz())

    private fun adviceVariant(): WifiAdvicePolicy.Variant = WifiAdvicePolicy.variant(WifiSwitchHub.current())

    /**
     * Plain-language advice when Wi-Fi is slowing Bluetooth, or null. With any Wi-Fi switch on it never says to turn
     * Wi-Fi off (UI audit 2026-11-10, UI-03): the Wi-Fi carriers need Wi-Fi on, only the 2.4 GHz network is in the way.
     */
    fun advice(): String? = if (!sharesBluetoothBand()) {
        null
    } else {
        appContext?.getString(
            when (adviceVariant()) {
                WifiAdvicePolicy.Variant.TURN_WIFI_OFF -> R.string.wifi_advice
                WifiAdvicePolicy.Variant.KEEP_WIFI_ON -> R.string.wifi_advice_keep_wifi_on
            },
        )
    }

    /** The title of the advice dialog for the same variant. */
    fun adviceTitleRes(): Int = when (adviceVariant()) {
        WifiAdvicePolicy.Variant.TURN_WIFI_OFF -> R.string.wifi_advice_title
        WifiAdvicePolicy.Variant.KEEP_WIFI_ON -> R.string.wifi_advice_keep_wifi_on_title
    }
}
