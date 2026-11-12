package com.example.bluetoothtest

/**
 * The band verdict of a Wi-Fi Aware data path (rev 2 step 7): a hint, not a promise.
 *
 * AP9 design decision (DECISIONS 2026-11-09): a data path is judged by its 5 GHz data channel. At least one channel
 * must be >= 5000 MHz, and every channel below 5000 MHz must be the NAN social channel 2437 MHz (channel 6), the
 * only 2.4 GHz social channel an NDP reports alongside its data channel. The Wi-Fi Aware (NAN) specification lists
 * the social channels as 6, 44 and 149 (2437, 5220 and 5745 MHz); AOSP's wifi_nan.h states "3(6, 44, 149) Social
 * channels", and Android exposes the data path's list as WifiAwareNetworkInfo.getChannelInfoList() (API 33).
 * A path with no 5 GHz channel, or any other below-5 GHz channel, is refused. [strict] restores the old rule
 * (every channel >= WifiLimits.MIN_5GHZ_MHZ) for the DEBUG control arm.
 *
 * AP10 design decision (DECISIONS 2026-11-09 "Wi-Fi Aware on 2.4 GHz"; Experimental, off by default): [judge] also
 * accepts a path whose every channel is in the 2.4 GHz band when the phone carries no other Bluetooth media link.
 */
internal object AwareBand {
    enum class Verdict { OK, TOO_LOW, UNKNOWN }

    /** AP10: which rule decided, for the `band=` field of the log. */
    enum class Rule(val log: String) {
        /** AP9's rule accepted the path (a 5 GHz data channel). */
        FIVE_G("5g"),

        /** A 2.4 GHz-only path on a phone that carries no other Bluetooth media link. */
        ALONE_24("2g4_alone"),

        /** A 2.4 GHz-only path refused because the phone carries other Bluetooth media. */
        REFUSED_24_BT_MEDIA("refused_24_bt_media"),

        /** Every other refusal of AP9's rule (the 2.4 GHz-alone switch off, the strict arm, a mixed or empty list). */
        REFUSED_LOW("refused_low"),

        /** No channel list (API < 33): the caller's unverified-band rule decides, as before. */
        UNKNOWN("unknown"),
    }

    data class Judgement(val verdict: Verdict, val rule: Rule)

    /** The NAN 2.4 GHz social channel (channel 6). */
    private const val NAN_SOCIAL_MHZ = 2_437

    /** The owner rule's 5 GHz data-channel threshold (the old eligibility constant is 4900). */
    private const val DATA_5GHZ_MHZ = 5_000

    /** The 2.4 GHz band: channel 1 is 2412 MHz, channel 14 is 2484 MHz. */
    private val BAND_24_MHZ = 2_400..2_500

    /** null = the platform gives no channel list (API < 33). An empty list on API 33+ is "no channel information": TOO_LOW. */
    fun verdict(channelsMhz: List<Int>?, strict: Boolean = false): Verdict = when {
        channelsMhz == null -> Verdict.UNKNOWN
        channelsMhz.isEmpty() -> Verdict.TOO_LOW
        strict -> if (channelsMhz.all { it >= WifiLimits.MIN_5GHZ_MHZ }) Verdict.OK else Verdict.TOO_LOW
        channelsMhz.any { it >= DATA_5GHZ_MHZ } &&
            channelsMhz.all { it >= DATA_5GHZ_MHZ || it == NAN_SOCIAL_MHZ } -> Verdict.OK
        else -> Verdict.TOO_LOW
    }

    /**
     * AP10: [verdict], plus the 2.4 GHz-alone rule. With [alone24] (the switch) on and [strict] off, a path that
     * [verdict] refuses is accepted when every one of its channels is in the 2.4 GHz band and
     * [otherBluetoothMedia] is false. Everything else is exactly [verdict]: a 2.4 GHz channel other than 2437 beside a
     * 5 GHz channel, a channel between the bands, an empty list and a missing list keep AP9's answer, and with
     * [alone24] false the verdict is AP9's for every input.
     */
    fun judge(channelsMhz: List<Int>?, strict: Boolean, alone24: Boolean, otherBluetoothMedia: Boolean): Judgement =
        when (verdict(channelsMhz, strict)) {
            Verdict.OK -> Judgement(Verdict.OK, Rule.FIVE_G)
            Verdict.UNKNOWN -> Judgement(Verdict.UNKNOWN, Rule.UNKNOWN)
            Verdict.TOO_LOW -> {
                val only24 = alone24 && !strict && !channelsMhz.isNullOrEmpty() && channelsMhz.all { it in BAND_24_MHZ }
                when {
                    !only24 -> Judgement(Verdict.TOO_LOW, Rule.REFUSED_LOW)
                    otherBluetoothMedia -> Judgement(Verdict.TOO_LOW, Rule.REFUSED_24_BT_MEDIA)
                    else -> Judgement(Verdict.OK, Rule.ALONE_24)
                }
            }
        }
}
