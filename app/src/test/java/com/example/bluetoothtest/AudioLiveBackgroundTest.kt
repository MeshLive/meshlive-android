package com.example.bluetoothtest

import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 4 of Design AL: service types, lock-screen text, reminder rule, feature switch and benchmark extras. */
class AudioLiveBackgroundTest {
    @Test
    fun aListenerRunsUnderMediaPlaybackAndABroadcasterUnderMicrophone() {
        val listen = ActiveVideoSessionService.requestedTypes(usesCamera = false, usesMicrophone = false, mediaPlayback = true)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            listen,
        )
        assertEquals(0, listen and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        assertEquals(0, listen and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        val broadcast = ActiveVideoSessionService.requestedTypes(usesCamera = false, usesMicrophone = true)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            broadcast,
        )
        assertEquals(0, broadcast and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        // The existing sessions are unchanged.
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ActiveVideoSessionService.requestedTypes(usesCamera = false, usesMicrophone = false),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ActiveVideoSessionService.requestedTypes(usesCamera = true, usesMicrophone = true),
        )
        assertEquals("connectedDevice+mediaPlayback", foregroundTypeLabels(listen))
    }

    @Test
    fun audioNotificationsShowNeutralTextOnTheLockScreenAndNeverAName() {
        val listening = NotificationPrivacy.publicText(NotificationKind.AUDIO_LISTEN)
        val broadcasting = NotificationPrivacy.publicText(NotificationKind.AUDIO_BROADCAST)
        assertTrue(listening.isNotBlank() && broadcasting.isNotBlank())
        assertNotEquals("listening and broadcasting are told apart", listening, broadcasting)
        for (kind in listOf(NotificationKind.AUDIO_LISTEN, NotificationKind.AUDIO_BROADCAST)) {
            // Private: a secure lock screen shows the public version, whatever the name opt-in says.
            assertEquals(NotificationCompat.VISIBILITY_PRIVATE, NotificationPrivacy.visibility(kind, peerNamesOptIn = false))
            assertEquals(NotificationCompat.VISIBILITY_PRIVATE, NotificationPrivacy.visibility(kind, peerNamesOptIn = true))
        }
        assertEquals("MeshLive", NotificationPrivacy.PUBLIC_TITLE)
    }

    @Test
    fun theNotificationActionsRequireTheSessionToken() {
        assertTrue(AudioLiveNotification.isAuthentic(CallNotificationActions.token))
        assertFalse(AudioLiveNotification.isAuthentic(null))
        assertFalse(AudioLiveNotification.isAuthentic("guess"))
        assertNotEquals(AudioLiveNotification.ACTION_MUTE, AudioLiveNotification.ACTION_STOP)
        assertEquals(AudioLiveServiceMode.NONE, AudioLiveServiceMode.from(null))
        assertEquals(AudioLiveServiceMode.NONE, AudioLiveServiceMode.from("garbage"))
        assertEquals(AudioLiveServiceMode.LISTEN, AudioLiveServiceMode.from("LISTEN"))
    }

    // --- "Still broadcasting" ---------------------------------------------------------------------------------------

    private val minute = 60_000L

    @Test
    fun anEmptyChannelIsRemindedAfterThirtyMinutesAndStoppedFiveMinutesLater() {
        val policy = AudioLiveReminderPolicy(startMs = 0)
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(29 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.REMIND, policy.decide(30 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(34 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.STOP, policy.decide(35 * minute))
    }

    @Test
    fun aListenerKeepsTheChannelFromBeingEmptyButTwoHoursStillRemind() {
        val policy = AudioLiveReminderPolicy(startMs = 0)
        policy.onListeners(1, 5 * minute)
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(100 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(119 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.REMIND, policy.decide(120 * minute))
        // The last listener leaves at 121 minutes: the empty clock starts then, not before.
        policy.onUserPresent(121 * minute)
        policy.onListeners(0, 121 * minute)
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(150 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.REMIND, policy.decide(151 * minute))
    }

    @Test
    fun openingTheAppAnswersTheReminder() {
        val policy = AudioLiveReminderPolicy(startMs = 0)
        assertEquals(AudioLiveReminderPolicy.Action.REMIND, policy.decide(30 * minute))
        policy.onUserPresent(32 * minute)
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(40 * minute)) // no stop
        assertEquals(AudioLiveReminderPolicy.Action.NONE, policy.decide(61 * minute))
        assertEquals(AudioLiveReminderPolicy.Action.REMIND, policy.decide(62 * minute))
    }

    // --- Switch and benchmark extras ----------------------------------------------------------------------------------

    @Test
    fun theFeatureIsOnByDefaultHasNoSettingsRowAndFollowsTheSharedRules() {
        val spec = AudioLiveFeatures.audioLive
        assertTrue(spec.defaultEnabled)
        assertEquals("test_audio_live", spec.benchmarkExtra)
        // No Settings row: a constant, so no stored choice (0.2.0 or later) applies.
        assertEquals(0, spec.titleRes)
        assertNull(spec.prefKey)
        assertTrue(FeatureCatalog.resolve(spec, benchmarkLaunch = false, extra = { false }, stored = { false }))
        // A normal launch with no stored choice follows the default constant.
        assertTrue(FeatureCatalog.resolve(spec, benchmarkLaunch = false, extra = { false }, stored = { null }))
        // A benchmark launch reads only its own extra.
        assertFalse(FeatureCatalog.resolve(spec, benchmarkLaunch = true, extra = { false }, stored = { true }))
        assertTrue(FeatureCatalog.resolve(spec, benchmarkLaunch = true, extra = { it == "test_audio_live" }, stored = { null }))
        assertTrue(ViewerFeatures.all.contains(spec))
    }

    @Test
    fun benchmarkExtrasSetEveryArmAndTheDefaultsComeBack() {
        try {
            AudioLiveTunables.applyExtras(
                boolExtra = { name, default -> if (name == "test_audio_live_age_bound") false else if (name.endsWith("fec") || name.endsWith("dtx")) true else default },
                intExtra = { name, default ->
                    when (name) {
                        "test_audio_live_kbps" -> 16
                        "test_audio_live_batch_frames" -> 5
                        "test_audio_live_max_children" -> 2
                        "test_audio_live_relay_children" -> 2
                        else -> default
                    }
                },
                stringExtra = { if (it == "test_audio_live_link_priority") "balanced" else null },
            )
            if (!BuildConfig.DEBUG) {
                // A release build ignores benchmark extras: every arm keeps its shipped default.
                assertEquals(AudioLive.PRESET_MUSIC, AudioLiveTunables.effectivePreset(AudioLive.PRESET_MUSIC))
                assertEquals(AudioLive.DEFAULT_BATCH_FRAMES, AudioLiveTunables.batchFrames)
                assertEquals(AudioLive.DEFAULT_MAX_CHILDREN, AudioLiveTunables.maxChildren)
                assertEquals(1, AudioLiveTunables.relayChildren)
                assertFalse(AudioLiveTunables.fec || AudioLiveTunables.dtx || AudioLiveTunables.balancedLinkPriority)
                assertTrue(AudioLiveTunables.ageBound)
                return
            }
            assertEquals(AudioLive.PRESET_LOW_DATA, AudioLiveTunables.effectivePreset(AudioLive.PRESET_MUSIC))
            assertEquals(5, AudioLiveTunables.batchFrames)
            assertEquals(2, AudioLiveTunables.maxChildren)
            assertEquals(2, AudioLiveTunables.relayChildren)
            assertTrue(AudioLiveTunables.fec)
            assertTrue(AudioLiveTunables.dtx)
            assertFalse(AudioLiveTunables.ageBound)
            assertTrue(AudioLiveTunables.balancedLinkPriority)
            // An invalid override is ignored.
            AudioLiveTunables.applyExtras({ _, d -> d }, { n, d -> if (n == "test_audio_live_kbps") 33 else d }, { null })
            assertNull(AudioLiveTunables.kbpsOverride)
        } finally {
            AudioLiveTunables.applyExtras({ _, d -> d }, { _, d -> d }, { null })
        }
        assertFalse(AudioLiveTunables.fec)
        assertFalse(AudioLiveTunables.balancedLinkPriority)
        assertTrue(AudioLiveTunables.ageBound)
        assertEquals(AudioLive.DEFAULT_MAX_CHILDREN, AudioLiveTunables.maxChildren)
        assertEquals(1, AudioLiveTunables.relayChildren)
    }
}
