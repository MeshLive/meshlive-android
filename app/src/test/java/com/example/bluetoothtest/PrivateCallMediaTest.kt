package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCallMediaTest {
    @Test
    fun explicitBitrateDiagnosticKeepsLegacyGeometryCadenceAndLowerSelections() {
        val legacy = BluetoothStackFingerprint("qcom", "msmnile", 30)
        for (bitrate in listOf(100_000, 250_000, 500_000)) {
            val selected = VideoSettings.DEFAULT.copy(fps = 30, bitRate = bitrate)
            val baseline = privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, legacy)
            val diagnostic = privateCallVideoSettings(
                selected, BluetoothTransportOption.BLE_GATT, legacy, preserveSelectedBitrate = true,
            )
            assertEquals(baseline.copy(bitRate = bitrate), diagnostic)
            assertEquals(minOf(bitrate, 150_000), baseline.bitRate)
        }
        val modern = BluetoothStackFingerprint("exynos9611", "universal9611", 31)
        val selected = VideoSettings.DEFAULT.copy(bitRate = 250_000)
        assertEquals(180_000, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, modern).bitRate)
        assertEquals(selected, privateCallVideoSettings(
            selected, BluetoothTransportOption.BLE_GATT, modern, preserveSelectedBitrate = true,
        ))
    }

    @Test
    fun aFamilyRateCapIsIgnoredOnlyWhenAsked() {
        val selected = VideoSettings.DEFAULT.copy(fps = 30, bitRate = 800_000)
        val legacyQti = BluetoothStackFingerprint("qcom", "atoll", 30)
        val capped = privateCallVideoSettings(
            selected, BluetoothTransportOption.BLE_GATT, legacyQti,
        )
        assertEquals(150_000, capped.bitRate)
        assertEquals(15, capped.fps)
        val uncapped = privateCallVideoSettings(
            selected, BluetoothTransportOption.BLE_GATT, legacyQti, ignoreFamilyRateCap = true,
        )
        assertEquals(180_000, uncapped.bitRate) // the transport budget still applies
        assertEquals(15, uncapped.fps) // family geometry and FPS caps still apply
        assertEquals(VideoResolution(426, 240, "240p"), uncapped.resolution)
    }

    @Test
    fun privateCallCapsEachDirectionWithoutChangingPictureOrCadence() {
        val selected = VideoSettings(
            VideoResolution(640, 360, "360p"),
            fps = 24,
            bitRate = 900_000,
            latencyMs = 500,
        )

        assertEquals(250_000, privateCallVideoSettings(selected, BluetoothTransportOption.CLASSIC_RFCOMM).bitRate)
        assertEquals(200_000, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_L2CAP).bitRate)
        assertEquals(180_000, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT).bitRate)
        assertEquals(selected.resolution, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_L2CAP).resolution)
        assertEquals(selected.fps, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_L2CAP).fps)
    }

    @Test
    fun privateCallDoesNotRaiseAUsersLowerBitrate() {
        val selected = VideoSettings.DEFAULT.copy(bitRate = 150_000)
        assertEquals(150_000, privateCallVideoSettings(selected, BluetoothTransportOption.CLASSIC_RFCOMM).bitRate)
    }

    @Test
    fun legacyQualcommGattCallCapsOnlyThatStacksOutgoingLoad() {
        val selected = VideoSettings.DEFAULT.copy(fps = 30, bitRate = 800_000)
        val legacyQti = BluetoothStackFingerprint("qcom", "atoll", 30)
        val samsung = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

        assertEquals(15, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, legacyQti).fps)
        assertEquals(150_000, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, legacyQti).bitRate)
        assertEquals(
            VideoResolution(426, 240, "240p"),
            privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, legacyQti).resolution,
        )
        assertEquals(180_000, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, samsung).bitRate)
        assertEquals(30, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, samsung).fps)
        assertEquals(selected.resolution, privateCallVideoSettings(selected, BluetoothTransportOption.BLE_GATT, samsung).resolution)
        assertEquals(200_000, privateCallVideoSettings(selected, BluetoothTransportOption.CLASSIC_RFCOMM, legacyQti).bitRate)
        assertEquals(15, privateCallVideoSettings(selected, BluetoothTransportOption.CLASSIC_RFCOMM, legacyQti).fps)
        assertEquals(
            VideoResolution(640, 360, "360p"),
            privateCallVideoSettings(
                selected.copy(resolution = VideoResolution(854, 480, "480p")),
                BluetoothTransportOption.CLASSIC_RFCOMM,
                legacyQti,
            ).resolution,
        )
    }

    @Test
    fun legacyQualcommCocAndMsmnileClassicUseMeasuredRealtimeProfile() {
        val selected = VideoSettings.DEFAULT.copy(fps = 30, bitRate = 800_000)
        val msmnile = BluetoothStackFingerprint("qcom", "msmnile", 30)
        val atoll = BluetoothStackFingerprint("qcom", "atoll", 30)

        for (transport in listOf(BluetoothTransportOption.BLE_L2CAP, BluetoothTransportOption.CLASSIC_RFCOMM)) {
            val adapted = privateCallVideoSettings(selected, transport, msmnile)
            assertEquals(VideoResolution(426, 240, "240p"), adapted.resolution)
            assertEquals(15, adapted.fps)
            assertEquals(110_000, adapted.bitRate)
        }
        val atollCoc = privateCallVideoSettings(selected, BluetoothTransportOption.BLE_L2CAP, atoll)
        assertEquals(110_000, atollCoc.bitRate)
        assertEquals(15, atollCoc.fps)
        assertEquals(VideoResolution(426, 240, "240p"), atollCoc.resolution)
        assertEquals(200_000, privateCallVideoSettings(selected, BluetoothTransportOption.CLASSIC_RFCOMM, atoll).bitRate)
    }

    @Test
    fun legacyQualcommGattCallReducesOnlyThatStacksAudioLoad() {
        val legacyQti = BluetoothStackFingerprint("qcom", "atoll", 30)
        val modernQualcomm = BluetoothStackFingerprint("qcom", "taro", 36)
        val samsung = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

        assertEquals(16_000, privateCallAudioBitRate(BluetoothTransportOption.BLE_GATT, legacyQti))
        assertEquals(StreamProtocol.AUDIO_BIT_RATE, privateCallAudioBitRate(BluetoothTransportOption.BLE_GATT, modernQualcomm))
        assertEquals(StreamProtocol.AUDIO_BIT_RATE, privateCallAudioBitRate(BluetoothTransportOption.BLE_GATT, samsung))
        assertEquals(16_000, privateCallAudioBitRate(BluetoothTransportOption.BLE_L2CAP, legacyQti))
        assertEquals(16_000, privateCallAudioBitRate(BluetoothTransportOption.BLE_L2CAP, modernQualcomm, 160))
        assertEquals(StreamProtocol.AUDIO_BIT_RATE, privateCallAudioBitRate(BluetoothTransportOption.BLE_L2CAP, modernQualcomm))
        assertEquals(16_000, privateCallAudioBitRate(BluetoothTransportOption.CLASSIC_RFCOMM, legacyQti))
        assertEquals(16_000, privateCallAudioBitRate(BluetoothTransportOption.CLASSIC_RFCOMM, modernQualcomm, 250))
        assertEquals(StreamProtocol.AUDIO_BIT_RATE, privateCallAudioBitRate(BluetoothTransportOption.CLASSIC_RFCOMM, modernQualcomm, 1_500))
    }

    @Test
    fun relayedCallCapsBothDirectionsForTheExtraRadioLoads() {
        val direct = VideoSettings.DEFAULT.copy(fps = 30, bitRate = 200_000)

        assertEquals(direct, privateCallVideoSettingsForRoute(direct, relayHops = 0))
        assertEquals(15, privateCallVideoSettingsForRoute(direct, relayHops = 1).fps)
        assertEquals(110_000, privateCallVideoSettingsForRoute(direct, relayHops = 1).bitRate)
    }

    @Test
    fun audioPairingIsLimitedToRelayedOrLegacyQualcommCocAndLegacyQualcommGatt() {
        val legacyQti = BluetoothStackFingerprint("qcom", "msmnile", 30)
        val modernQualcomm = BluetoothStackFingerprint("qcom", "taro", 36)
        val samsung = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

        assertEquals(true, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, relayHops = 1))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, relayHops = 0))
        assertEquals(true, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, 0, legacyQti))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, 0, modernQualcomm))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, 0, samsung))
        assertEquals(true, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, 0, samsung, 160))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_L2CAP, 0, samsung, 200))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.CLASSIC_RFCOMM, relayHops = 1))
        assertEquals(true, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_GATT, 0, legacyQti))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_GATT, 0, modernQualcomm))
        assertEquals(false, pairPrivateCallAudioForRoute(BluetoothTransportOption.BLE_GATT, 0, samsung))
        assertEquals(20, privateCallAudioBatchSizeForRoute(BluetoothTransportOption.BLE_L2CAP, 0, legacyQti))
        assertEquals(20, privateCallAudioBatchSizeForRoute(BluetoothTransportOption.BLE_L2CAP, 0, samsung, 160))
        assertEquals(2, privateCallAudioBatchSizeForRoute(BluetoothTransportOption.BLE_GATT, 0, legacyQti))
        assertEquals(1, privateCallAudioBatchSizeForRoute(BluetoothTransportOption.BLE_L2CAP, 0, samsung))
    }


    @Test
    fun legacyQualcommCocCapabilityConstrainsOnlyThatPeerPair() {
        val selected = VideoSettings.DEFAULT.copy(fps = 30, bitRate = 200_000)
        val legacyQti = BluetoothStackFingerprint("qcom", "atoll", 30)
        val msmnile = BluetoothStackFingerprint("qcom", "msmnile", 30)
        val modernQualcomm = BluetoothStackFingerprint("qcom", "taro", 36)
        val samsung = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

        assertEquals(160, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.BLE_L2CAP, legacyQti, 1_200))
        assertEquals(110, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.BLE_L2CAP, msmnile, 1_200))
        assertEquals(1_200, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.BLE_L2CAP, modernQualcomm, 1_200))
        assertEquals(1_200, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.BLE_L2CAP, samsung, 1_200))
        assertEquals(250, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.CLASSIC_RFCOMM, legacyQti, 1_500))
        assertEquals(110, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.CLASSIC_RFCOMM, msmnile, 1_500))
        assertEquals(1_500, privateCallCapabilityMaxBitrateKbps(BluetoothTransportOption.CLASSIC_RFCOMM, samsung, 1_500))
        assertEquals(
            NodeCapabilities.CODEC_VP9,
            privateCallDecodeCodecMask(
                legacyQti,
                BluetoothTransportOption.CLASSIC_RFCOMM,
                NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            ),
        )
        assertEquals(
            NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            privateCallDecodeCodecMask(
                legacyQti,
                BluetoothTransportOption.BLE_L2CAP,
                NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            ),
        )
        assertEquals(
            NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            privateCallDecodeCodecMask(
                samsung,
                BluetoothTransportOption.BLE_L2CAP,
                NodeCapabilities.CODEC_VP9 or NodeCapabilities.CODEC_AV1,
            ),
        )

        val constrained = privateCallVideoSettingsForPeer(selected, BluetoothTransportOption.BLE_L2CAP, 160)
        assertEquals(VideoResolution(426, 240, "240p"), constrained.resolution)
        assertEquals(20, constrained.fps)
        assertEquals(160_000, constrained.bitRate)
        assertEquals(selected, privateCallVideoSettingsForPeer(selected, BluetoothTransportOption.BLE_L2CAP, 1_200))
        assertEquals(selected, privateCallVideoSettingsForPeer(selected, BluetoothTransportOption.BLE_GATT, 110))
        val classicConstrained = privateCallVideoSettingsForPeer(
            selected.copy(fps = 30, bitRate = 250_000),
            BluetoothTransportOption.CLASSIC_RFCOMM,
            250,
        )
        assertEquals(selected.resolution, classicConstrained.resolution)
        assertEquals(30, classicConstrained.fps)
        assertEquals(250_000, classicConstrained.bitRate)

        assertEquals(20L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.BLE_L2CAP, legacyQti, null))
        assertEquals(20L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.BLE_L2CAP, modernQualcomm, 160))
        assertEquals(0L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.BLE_L2CAP, modernQualcomm, 1_200))
        assertEquals(20L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.CLASSIC_RFCOMM, modernQualcomm, 250))
        assertEquals(0L, privateCallMediaFlushIntervalMs(BluetoothTransportOption.CLASSIC_RFCOMM, samsung, 1_500))
    }

    @Test
    fun privateCocAddsBurstHeadroomWithoutChangingOtherTransportsOrDelay() {
        val base = PlaybackDelayProfile.STABLE.videoQueueCapacity(15)

        assertEquals(base + 8, privateCallVideoQueueCapacity(15, PlaybackDelayProfile.STABLE, BluetoothTransportOption.BLE_L2CAP))
        assertEquals(base, privateCallVideoQueueCapacity(15, PlaybackDelayProfile.STABLE, BluetoothTransportOption.BLE_GATT))
        assertEquals(base, privateCallVideoQueueCapacity(15, PlaybackDelayProfile.STABLE, BluetoothTransportOption.CLASSIC_RFCOMM))
        assertEquals(
            base + 8,
            privateCallVideoQueueCapacity(
                15,
                PlaybackDelayProfile.STABLE,
                BluetoothTransportOption.CLASSIC_RFCOMM,
                BluetoothStackFingerprint("qcom", "atoll", 30),
            ),
        )
    }

    @Test
    fun constrainedClassicAndCocRoutesRequireStablePlayback() {
        val legacyQti = BluetoothStackFingerprint("qcom", "atoll", 30)
        val modernQualcomm = BluetoothStackFingerprint("qcom", "taro", 36)
        val samsung = BluetoothStackFingerprint("exynos9611", "exynos9611", 31)

        assertEquals(
            PlaybackDelayProfile.CALL_STABLE,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.BALANCED,
                BluetoothTransportOption.CLASSIC_RFCOMM,
                legacyQti,
                null,
            ),
        )
        assertEquals(
            PlaybackDelayProfile.CALL_STABLE,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.LOW,
                BluetoothTransportOption.CLASSIC_RFCOMM,
                modernQualcomm,
                250,
            ),
        )
        assertEquals(
            PlaybackDelayProfile.CALL_BALANCED,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.BALANCED,
                BluetoothTransportOption.CLASSIC_RFCOMM,
                modernQualcomm,
                1_500,
            ),
        )
        assertEquals(
            PlaybackDelayProfile.CALL_STABLE,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.LOW,
                BluetoothTransportOption.BLE_L2CAP,
                legacyQti,
                110,
            ),
        )
        assertEquals(
            PlaybackDelayProfile.CALL_STABLE,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.BALANCED,
                BluetoothTransportOption.BLE_L2CAP,
                modernQualcomm,
                160,
            ),
        )
        assertEquals(
            PlaybackDelayProfile.CALL_LOW,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.LOW,
                BluetoothTransportOption.BLE_L2CAP,
                modernQualcomm,
                1_200,
            ),
        )
        assertEquals(
            PlaybackDelayProfile.CALL_BALANCED,
            privateCallPlaybackDelayProfile(
                PlaybackDelayProfile.BALANCED,
                BluetoothTransportOption.CLASSIC_RFCOMM,
                samsung,
                1_500,
            ),
        )
    }

    @Test
    fun uiEndEvidenceCarriesTheMessageButIsNotASecondTerminationRecord() {
        val line = privateCallEndedUiEvidence("Peer ended the call")
        assertEquals("BENCHMARK call ended_ui: message=Peer ended the call", line)
        // The runner's unexpected-termination check and the scorer's failure scan key on this prefix.
        assertFalse(line.contains("Private call ended:"))
    }

    @Test
    fun theEndLineNamesItsCallAfterTheMessageSoTheNormalEndIsStillRecognised() {
        val callId = "123e4567-e89b-12d3-a456-426614174000"
        val line = privateCallEndedLine("Call ended", callId)
        // The scorer's normal-end check reads the message right after the prefix; the call ID is a trailing field.
        assertTrue(Regex("Private call ended: (?:Call ended|Peer ended)").containsMatchIn(line))
        assertEquals(callId, Regex("""\| callId=(\S+)$""").find(line)!!.groupValues[1])
        // A release build (no call ID) keeps the bare line.
        assertFalse(privateCallEndedLine("Call ended", null).contains("callId"))
    }

    @Test
    fun socketTransportsWaitForThePeerToCloseAfterTheirTerminalSignal() {
        val peer = PrivateCallCloseWait(waitForPeer = true, maxMs = PRIVATE_CALL_PEER_CLOSE_WAIT_MS)
        assertEquals(peer, privateCallCloseWait(BluetoothTransportOption.CLASSIC_RFCOMM, waitForPeer = true))
        assertEquals(peer, privateCallCloseWait(BluetoothTransportOption.BLE_L2CAP, waitForPeer = true))
        assertEquals(3_000L, PRIVATE_CALL_PEER_CLOSE_WAIT_MS)
        assertEquals(PrivateCallCloseWait(false, 0L), privateCallCloseWait(BluetoothTransportOption.BLE_GATT, true))
        // Control arm: the previous fixed drain.
        assertEquals(
            PrivateCallCloseWait(false, 400L),
            privateCallCloseWait(BluetoothTransportOption.BLE_L2CAP, waitForPeer = false),
        )
    }

    @Test
    fun closeWaitEndsWhenThePeerClosesTheLink() {
        val reader = Thread { Thread.sleep(50) }.apply { start() }
        // The cap is far above anything a loaded machine needs to run a 50 ms thread, so "closed before the cap" cannot be
        // lost to scheduling (a 3 s cap with a 2 s elapsed assertion could).
        val capMs = 60_000L
        val outcome = awaitPrivateCallClose(reader, PrivateCallCloseWait(waitForPeer = true, maxMs = capMs))
        assertTrue(outcome.peerClosed)
        assertTrue("returned at the peer close, not the cap", outcome.waitedMs < capMs)
    }

    @Test
    fun closeWaitIsCappedWhenThePeerNeverCloses() {
        val stop = java.util.concurrent.CountDownLatch(1)
        val reader = Thread { stop.await() }.apply { start() }
        try {
            val outcome = awaitPrivateCallClose(reader, PrivateCallCloseWait(waitForPeer = true, maxMs = 100L))
            assertFalse(outcome.peerClosed)
            assertTrue(outcome.waitedMs >= 100L)
        } finally {
            stop.countDown()
        }
        assertEquals(
            PrivateCallCloseOutcome(peerClosed = false, waitedMs = 0L),
            awaitPrivateCallClose(null, PrivateCallCloseWait(waitForPeer = false, maxMs = 0L)),
        )
    }
}
