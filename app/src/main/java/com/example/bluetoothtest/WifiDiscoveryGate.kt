package com.example.bluetoothtest

/** When may the Wi-Fi Aware discovery plane run? Pure; MainActivity/WifiDiscovery fill the inputs. */
internal object WifiDiscoveryGate {
    enum class Block { SWITCH_OFF, NOT_ELIGIBLE, FOREGROUND_ONLY }

    data class Inputs(
        val widerReach: Boolean,
        val awareAllowed: Boolean,               // WifiDiscovery.eligibility().aware [R M-7]
        val sdkInt: Int,
        val activityStarted: Boolean,
        val connectedDeviceServiceHeld: Boolean, // ActiveVideoSessionService.holdsConnectedDevice()
        val locationServiceHeld: Boolean,        // ServiceTypePolicy.hasLocation(ActiveVideoSessionService.heldTypes)
        val locationServiceAvailable: Boolean,   // BuildConfig.LOCATION_FGS_AVAILABLE (false in the Play flavour)
        val wantPublish: Boolean,                // WifiOfferRegistry.current() != null
        val wantSubscribe: Boolean,              // any discovery demand (step 7)
    )

    data class Decision(val publish: Boolean, val subscribe: Boolean, val block: Block?) {
        companion object { val NONE = Decision(false, false, null) }
    }

    fun decide(i: Inputs): Decision {
        if (!i.widerReach) return Decision(false, false, Block.SWITCH_OFF)
        if (!i.awareAllowed) return Decision(false, false, Block.NOT_ELIGIBLE)
        val background = when {
            i.sdkInt >= 33 -> i.connectedDeviceServiceHeld
            // API 30: background discovery also depends on the location foreground-service policy (`ServiceTypePolicy`, Q37).
            i.sdkInt == 30 -> i.connectedDeviceServiceHeld && i.locationServiceAvailable && i.locationServiceHeld
            else -> false // 31-32 (issue 8) and everything below 30: the app-op needs the app visible
        }
        if (!i.activityStarted && !background) return Decision(false, false, Block.FOREGROUND_ONLY)
        return Decision(i.wantPublish, i.wantSubscribe, null)
    }
}
