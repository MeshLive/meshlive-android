package com.example.bluetoothtest

import android.net.wifi.ScanResult
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The exact Wi-Fi Aware builders of the plan (PROTOCOL "Setup"): a SOLICITED publication (a matching subscriber is
 * answered in unicast, never unsolicited) and an ACTIVE subscription, no service-specific info and no match filter.
 * The PMK must be 32 bytes; the channel is only ever a hint (`required = false`).
 */
internal object AwareConfigs {
    fun publish(serviceName: String, ttlSec: Int, icm: Boolean): PublishConfig {
        val b = PublishConfig.Builder()
            .setServiceName(serviceName)
            .setPublishType(PublishConfig.PUBLISH_TYPE_SOLICITED) // rev 2: no unsolicited publication; a matching subscriber is answered in unicast
            .setTtlSec(ttlSec)
            .setTerminateNotificationEnabled(true)
            .setRangingEnabled(false)
        // no setServiceSpecificInfo, no setMatchFilter (rev 2 step 1)
        if (icm && Build.VERSION.SDK_INT >= 33) Api33.icm(b)
        return b.build()
    }

    fun subscribe(serviceName: String, ttlSec: Int, icm: Boolean): SubscribeConfig {
        val b = SubscribeConfig.Builder()
            .setServiceName(serviceName)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
            .setTtlSec(ttlSec)
            .setTerminateNotificationEnabled(true)
        if (icm && Build.VERSION.SDK_INT >= 33) Api33.icm(b)
        return b.build()
    }

    fun responderSpecifier(session: PublishDiscoverySession, peer: PeerHandle, pmk: ByteArray, port: Int, hintMhz: Int?, requireChannel: Boolean = false): WifiAwareNetworkSpecifier {
        require(pmk.size == 32)
        require(Build.VERSION.SDK_INT >= 30)
        val b = Api30.specifier(WifiAwareNetworkSpecifier.Builder(session, peer), pmk).setPort(port) // setPort: responder only, requires security (set by setPmk)
        if (hintMhz != null && Build.VERSION.SDK_INT >= 33) Api33.hint(b, hintMhz, requireChannel)
        return b.build()
    }

    fun initiatorSpecifier(session: SubscribeDiscoverySession, peer: PeerHandle, pmk: ByteArray, hintMhz: Int?, requireChannel: Boolean = false): WifiAwareNetworkSpecifier {
        require(pmk.size == 32)
        require(Build.VERSION.SDK_INT >= 30)
        val b = Api30.specifier(WifiAwareNetworkSpecifier.Builder(session, peer), pmk) // no setPort: initiator side [GAP+ body for A5 and A8]
        if (hintMhz != null && Build.VERSION.SDK_INT >= 33) Api33.hint(b, hintMhz, requireChannel)
        return b.build()
    }

    fun request(specifier: WifiAwareNetworkSpecifier): NetworkRequest =
        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(specifier).build()

    @RequiresApi(30)
    private object Api30 {
        fun specifier(b: WifiAwareNetworkSpecifier.Builder, pmk: ByteArray) = b.setPmk(pmk)
    }

    @RequiresApi(33)
    private object Api33 {
        // ICM band: 5 GHz for these link sessions (PROTOCOL "Setup"); A8's Wider-reach discovery sessions use the 2.4 GHz social channel; both only under test_wifi_icm (DEBUG), see SWITCHES [judgement C-A4-5]
        fun icm(b: PublishConfig.Builder) {
            b.setInstantCommunicationModeEnabled(true, ScanResult.WIFI_BAND_5_GHZ)
        }

        fun icm(b: SubscribeConfig.Builder) {
            b.setInstantCommunicationModeEnabled(true, ScanResult.WIFI_BAND_5_GHZ)
        }

        fun hint(b: WifiAwareNetworkSpecifier.Builder, mhz: Int, required: Boolean) {
            b.setChannelFrequencyMhz(mhz, required) // required=false: a hint, never enforcement; true only under the Experimental test_wifi_aware_require_channel
        }
    }
}
