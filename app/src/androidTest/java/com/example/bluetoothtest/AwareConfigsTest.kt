package com.example.bluetoothtest

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builders only, no radio: a publish and a subscribe config must build; on API 33+ the ICM builder must show the
 * flag through the only public getter. The specifier and request builders need a live discovery session and are
 * exercised by the reviewer's phone step (A5 M1); the accept of that gap is deliberate.
 */
@RunWith(AndroidJUnit4::class)
class AwareConfigsTest {
    @Test
    fun publishAndSubscribeConfigsBuild() {
        AwareConfigs.publish("m0123456789abcde", 20, false)
        AwareConfigs.subscribe("m0123456789abcde", 20, false)
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue(
                AwareConfigs.publish("m0123456789abcde", 20, icm = true).isInstantCommunicationModeEnabled,
            )
            assertFalse(
                AwareConfigs.publish("m0123456789abcde", 20, icm = false).isInstantCommunicationModeEnabled,
            )
        }
    }
}
