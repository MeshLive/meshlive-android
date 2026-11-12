package com.example.bluetoothtest

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the manifest facts the foreground-service batch depends on (B-01, B-02, B-05/K B19, B-06). */
class ServiceManifestTest {
    private val manifest: String by lazy {
        // Gradle runs unit tests with the module directory as the working directory; tolerate the repository root too.
        val candidates = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        candidates.first { it.isFile }.readText()
    }

    private fun declaresPermission(name: String) =
        Regex("<uses-permission\\s+android:name=\"android\\.permission\\.$name\"").containsMatchIn(manifest)

    @Test
    fun theConnectedDeviceFallbackStillRestsOnChangeWifiState() {
        // K B19: on Android 14+ the connectedDevice type is startable without Bluetooth grants only because this is declared.
        val name = ServiceTypePolicy.CONNECTED_DEVICE_PREREQUISITE_PERMISSION.removePrefix("android.permission.")
        assertTrue("CHANGE_WIFI_STATE must stay declared while connectedDevice is the fallback type", declaresPermission(name))
        assertTrue(declaresPermission("FOREGROUND_SERVICE_CONNECTED_DEVICE"))
    }

    @Test
    fun theWifiAwareDataPathPermissionsAreDeclared() {
        // Without CHANGE_NETWORK_STATE ConnectivityManager.requestNetwork for an Aware data path throws SecurityException. [Am 8]
        assertTrue(declaresPermission("CHANGE_NETWORK_STATE"))
        assertTrue(declaresPermission("ACCESS_NETWORK_STATE"))
    }

    @Test
    fun theSessionServiceDeclaresTheLocationTypeAndItsPermission() {
        val service = Regex("<service\\s+android:name=\"\\.ActiveVideoSessionService\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)!!.value
        val types = Regex("android:foregroundServiceType=\"([^\"]+)\"").find(service)!!.groupValues[1].split('|').toSet()
        assertEquals(setOf("camera", "microphone", "connectedDevice", "mediaPlayback", "location"), types)
        assertTrue(declaresPermission("FOREGROUND_SERVICE_LOCATION"))
    }

    @Test
    fun noBackgroundLocationPermissionIsRequested() {
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
    }

    @Test
    fun theBatteryExemptionPromptPermissionAndQueriesAreDeclared() {
        assertTrue(declaresPermission("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(manifest.contains("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertTrue(manifest.contains("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))
    }

    @Test
    fun theBootReceiverIsDisabledByDefaultAndNotExported() {
        val receiver = Regex("<receiver\\s+android:name=\"\\.BootResumeReceiver\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)!!.value
        assertTrue(receiver.contains("android:enabled=\"false\""))
        assertTrue(receiver.contains("android:exported=\"false\""))
        assertTrue(declaresPermission("RECEIVE_BOOT_COMPLETED"))
        BootResume.ACTIONS.forEach { assertTrue(it, manifest.contains("<action android:name=\"$it\" />")) }
    }
}
