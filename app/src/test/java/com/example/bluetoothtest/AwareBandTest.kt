package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The band verdict of a data path: the default rule accepts a 5 GHz data channel with the 2437 MHz NAN social
 * channel below it; the strict arm restores the old all-channels-5GHz rule.
 */
class AwareBandTest {
    @Test
    fun aFiveGhzDataChannelWithTheSocialChannelBelowItIsOk() {
        assertEquals(AwareBand.Verdict.OK, AwareBand.verdict(listOf(5180, 5745)))
        assertEquals(AwareBand.Verdict.OK, AwareBand.verdict(listOf(5955)))
        assertEquals(AwareBand.Verdict.OK, AwareBand.verdict(listOf(5745, 2437)))
        assertEquals(AwareBand.Verdict.OK, AwareBand.verdict(listOf(5180, 2437)))
    }

    @Test
    fun anythingElseBelowFiveGhzIsTooLow() {
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(2437)))
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(2412, 5745)))
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(5745, 2412)))
        // 4900 MHz is below the owner rule's 5 GHz data-channel threshold and is not the social channel.
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(4900)))
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(4899)))
    }

    @Test
    fun theStrictArmRestoresTheOldAllChannelsRule() {
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(5745, 2437), strict = true))
        assertEquals(AwareBand.Verdict.OK, AwareBand.verdict(listOf(5180, 5745), strict = true))
        // The old threshold was MIN_5GHZ_MHZ (4900); the control arm must reproduce it exactly.
        assertEquals(AwareBand.Verdict.OK, AwareBand.verdict(listOf(4900), strict = true))
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(listOf(4899), strict = true))
    }

    @Test
    fun anEmptyListIsNoChannelInformation() {
        assertEquals(AwareBand.Verdict.TOO_LOW, AwareBand.verdict(emptyList()))
    }

    @Test
    fun noChannelListIsUnknown() {
        assertEquals(AwareBand.Verdict.UNKNOWN, AwareBand.verdict(null))
    }

    // ----- AP10: a 2.4 GHz-only path when the phone carries no other Bluetooth media -----

    private fun judge(channels: List<Int>?, otherBluetoothMedia: Boolean, alone24: Boolean = true, strict: Boolean = false) =
        AwareBand.judge(channels, strict = strict, alone24 = alone24, otherBluetoothMedia = otherBluetoothMedia)

    @Test
    fun aTwoPointFourOnlyPathIsAcceptedWhenThePhoneCarriesNoOtherBluetoothMedia() {
        for (channels in listOf(listOf(2412), listOf(2437), listOf(2412, 2462), listOf(2484))) {
            val judgement = judge(channels, otherBluetoothMedia = false)
            assertEquals("$channels", AwareBand.Verdict.OK, judgement.verdict)
            assertEquals("$channels", AwareBand.Rule.ALONE_24, judgement.rule)
        }
    }

    @Test
    fun aTwoPointFourOnlyPathIsRefusedWhileThePhoneCarriesOtherBluetoothMedia() {
        for (channels in listOf(listOf(2412), listOf(2437), listOf(2412, 2462))) {
            val judgement = judge(channels, otherBluetoothMedia = true)
            assertEquals("$channels", AwareBand.Verdict.TOO_LOW, judgement.verdict)
            assertEquals("$channels", AwareBand.Rule.REFUSED_24_BT_MEDIA, judgement.rule)
        }
    }

    @Test
    fun aFiveGhzDataChannelIsAcceptedWithAndWithoutOtherBluetoothMedia() {
        for (other in listOf(true, false)) {
            for (channels in listOf(listOf(5745, 2437), listOf(5180, 5745), listOf(5955))) {
                val judgement = judge(channels, otherBluetoothMedia = other)
                assertEquals("$channels other=$other", AwareBand.Verdict.OK, judgement.verdict)
                assertEquals("$channels other=$other", AwareBand.Rule.FIVE_G, judgement.rule)
            }
        }
    }

    @Test
    fun aPathThatIsNotTwoPointFourOnlyKeepsItsRefusal() {
        // 2412 beside 5745, or a channel outside the 2.4 GHz band: the 2.4 GHz-alone rule does not apply.
        for (other in listOf(true, false)) {
            for (channels in listOf(listOf(2412, 5745), listOf(5745, 2412), listOf(4950), listOf(2412, 4950), listOf(3600))) {
                val judgement = judge(channels, otherBluetoothMedia = other)
                assertEquals("$channels other=$other", AwareBand.Verdict.TOO_LOW, judgement.verdict)
                assertEquals("$channels other=$other", AwareBand.Rule.REFUSED_LOW, judgement.rule)
            }
        }
    }

    @Test
    fun noChannelInformationIsNeverAcceptedAsTwoPointFourAlone() {
        assertEquals(AwareBand.Verdict.TOO_LOW, judge(emptyList(), otherBluetoothMedia = false).verdict)
        val unknown = judge(null, otherBluetoothMedia = false)
        assertEquals(AwareBand.Verdict.UNKNOWN, unknown.verdict)
        assertEquals(AwareBand.Rule.UNKNOWN, unknown.rule)
    }

    @Test
    fun theControlArmGivesExactlyTheVerdictOfTheFiveGhzRule() {
        val paths = listOf(
            null, emptyList(), listOf(2412), listOf(2437), listOf(2412, 2462), listOf(5745, 2437), listOf(2412, 5745),
            listOf(5180, 5745), listOf(4900), listOf(4899), listOf(5955),
        )
        for (channels in paths) {
            for (strict in listOf(false, true)) {
                for (other in listOf(false, true)) {
                    assertEquals(
                        "$channels strict=$strict other=$other",
                        AwareBand.verdict(channels, strict),
                        judge(channels, otherBluetoothMedia = other, alone24 = false, strict = strict).verdict,
                    )
                }
            }
        }
    }

    @Test
    fun theStrictArmKeepsItsOldRuleEvenWithNoOtherBluetoothMedia() {
        assertEquals(AwareBand.Verdict.TOO_LOW, judge(listOf(2412), otherBluetoothMedia = false, strict = true).verdict)
        assertEquals(AwareBand.Verdict.TOO_LOW, judge(listOf(5745, 2437), otherBluetoothMedia = false, strict = true).verdict)
        assertEquals(AwareBand.Verdict.OK, judge(listOf(5180, 5745), otherBluetoothMedia = false, strict = true).verdict)
    }
}
