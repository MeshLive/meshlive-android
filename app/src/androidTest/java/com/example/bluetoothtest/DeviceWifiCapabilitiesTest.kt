package com.example.bluetoothtest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.Characteristics
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Design WF M0 active census: exports what the installed firmware offers.
 *
 * It never publishes, subscribes, requests a network, creates a Wi-Fi Direct group or connects to
 * anything. Attach is time-boxed and its session is always closed. Wi-Fi Direct is only queried for
 * this device's own information, and both the address and the name are exported as salted hashes.
 */
@RunWith(AndroidJUnit4::class)
class DeviceWifiCapabilitiesTest {
    @Test
    fun exportWifiCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val result = JSONObject()
            .put("schema_version", 1)
            .put("api", Build.VERSION.SDK_INT)
        try {
            val timeoutMs = arguments.getString("wifi_census_timeout_ms")?.toLongOrNull()
                ?.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
            val hashes = SaltedHashes(saltFrom(arguments))
            result.put("feature_wifi_aware",
                featureSupported(context, PackageManager.FEATURE_WIFI_AWARE))
            result.put("feature_wifi_direct",
                featureSupported(context, PackageManager.FEATURE_WIFI_DIRECT))
            result.put("aware", safeSection { probeAware(context, hashes, timeoutMs) })
            result.put("wifi_direct", safeSection { probeWifiDirect(context, hashes) })
        } catch (error: Throwable) {
            result.put("error", error.javaClass.simpleName)
        } finally {
            try {
                File(requireNotNull(context.getExternalFilesDir(null)), "wifi-capabilities.json")
                    .writeText(result.toString(2))
            } catch (error: Throwable) {
                // A platform mismatch must not crash the instrumentation run; the host
                // reports missing_output and can retry on another build.
                println("BENCHMARK wifi capabilities write failed: ${error.javaClass.simpleName}")
            }
            println("BENCHMARK wifi capabilities exported: API ${Build.VERSION.SDK_INT}")
        }
    }

    private fun featureSupported(context: Context, feature: String): Any =
        guarded { context.packageManager.hasSystemFeature(feature) }.first ?: "unsupported"

    private fun safeSection(block: () -> JSONObject): JSONObject = try {
        block()
    } catch (error: Throwable) {
        JSONObject().put("status", errorValue(error))
    }

    private fun probeAware(context: Context, hashes: SaltedHashes, timeoutMs: Long): JSONObject {
        val json = JSONObject().put("permission", permissionState(context, awarePermission()))
        json.put("location_enabled", locationEnabled(context))
        val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (manager == null) {
            json.put("is_available", "unavailable")
            json.put("is_device_attached", "unavailable")
            json.put("characteristics", "unavailable")
            json.put("available_aware_resources", "unavailable")
            json.put("is_set_channel_on_data_path_supported", "unavailable")
            json.put("attach_status", "unavailable")
            return json
        }

        val (available, availableError) = guarded { manager.isAvailable() }
        json.put("is_available", available ?: availableError ?: "unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val attached = guarded { manager.isDeviceAttached() }.first
            json.put("is_device_attached", attached ?: "unsupported")
        } else {
            json.put("is_device_attached", "api_too_low")
        }

        val attachment = AttachSession()
        try {
            val outcome = attach(manager, timeoutMs, attachment)
            val attachStatus = if (outcome.status == "failed" &&
                json.getString("permission") != "granted") "not_granted" else outcome.status
            json.put("attach_status", attachStatus)
            json.put("attach_elapsed_ms", outcome.elapsedMs)
            json.put("attach_timeout_ms", timeoutMs)

            val (characteristics, characteristicsError) = guarded { manager.getCharacteristics() }
            when {
                characteristicsError != null -> json.put("characteristics", characteristicsError)
                characteristics == null -> json.put("characteristics", "unavailable")
                else -> json.put("characteristics", characteristicsJson(characteristics))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val (resources, resourcesError) = guarded { Api31Aware.resources(manager) }
                json.put("available_aware_resources", resources ?: resourcesError ?: "unavailable")
            } else {
                json.put("available_aware_resources", "api_too_low")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val (supported, supportedError) = guarded { manager.isSetChannelOnDataPathSupported() }
                json.put("is_set_channel_on_data_path_supported",
                    supported ?: supportedError ?: "unavailable")
            } else {
                json.put("is_set_channel_on_data_path_supported", "api_too_low")
            }
        } finally {
            try {
                attachment.close()
            } catch (error: Throwable) {
                // Closing is cleanup; never let a platform mismatch replace the recorded result.
            }
        }
        return json
    }

    private fun attach(manager: WifiAwareManager, timeoutMs: Long,
                       attachment: AttachSession): AttachOutcome {
        // The main looper is never quit, unlike a private HandlerThread. A session granted
        // after the timeout therefore still reaches onAttached and is closed exactly once.
        val handler = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val status = AtomicReference("failed")
        val started = SystemClock.elapsedRealtime()
        return try {
            manager.attach(object : AttachCallback() {
                override fun onAttached(newSession: WifiAwareSession) {
                    if (attachment.accept(newSession)) {
                        status.set("ok")
                    } else {
                        newSession.close()
                    }
                    done.countDown()
                }

                override fun onAttachFailed() {
                    if (!attachment.isClosed()) status.set("failed")
                    done.countDown()
                }
            }, handler)
            val completed = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            val elapsed = SystemClock.elapsedRealtime() - started
            if (completed) AttachOutcome(status.get(), minOf(elapsed, timeoutMs))
            else AttachOutcome("timeout", minOf(elapsed, timeoutMs))
        } catch (error: SecurityException) {
            AttachOutcome("not_granted", SystemClock.elapsedRealtime() - started)
        } catch (error: Throwable) {
            AttachOutcome(errorValue(error), SystemClock.elapsedRealtime() - started)
        }
    }

    /** Exports only what this API level exposes; older fields stay absent, not zeroed. */
    private fun characteristicsJson(characteristics: Characteristics): JSONObject {
        val json = JSONObject()
            .put("max_service_name_length", characteristics.getMaxServiceNameLength())
            .put("max_service_specific_info_length", characteristics.getMaxServiceSpecificInfoLength())
            .put("max_match_filter_length", characteristics.getMaxMatchFilterLength())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            json.put("supported_cipher_suites", characteristics.getSupportedCipherSuites())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            json.put("instant_communication_mode_supported",
                characteristics.isInstantCommunicationModeSupported())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            json.put("number_of_supported_data_interfaces",
                characteristics.getNumberOfSupportedDataInterfaces())
            json.put("number_of_supported_data_paths", characteristics.getNumberOfSupportedDataPaths())
            json.put("number_of_supported_publish_sessions",
                characteristics.getNumberOfSupportedPublishSessions())
            json.put("number_of_supported_subscribe_sessions",
                characteristics.getNumberOfSupportedSubscribeSessions())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            json.put("aware_pairing_supported", characteristics.isAwarePairingSupported())
            json.put("suspension_supported", characteristics.isSuspensionSupported())
            json.put("supported_pairing_cipher_suites",
                characteristics.getSupportedPairingCipherSuites())
        }
        return json
    }

    private fun probeWifiDirect(context: Context, hashes: SaltedHashes): JSONObject {
        val permission = permissionState(context, directPermission())
        val json = JSONObject().put("permission", permission)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            json.put("device_info_status", "api_too_low")
            return json
        }
        if (permission != "granted") {
            json.put("device_info_status",
                if (permission == "not_granted") "not_granted" else permission)
            return json
        }
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            json.put("device_info_status", "unavailable")
            return json
        }
        var channel: WifiP2pManager.Channel? = null
        try {
            val currentChannel = manager.initialize(context, Looper.getMainLooper(),
                object : WifiP2pManager.ChannelListener {
                    override fun onChannelDisconnected() = Unit
                }) ?: run {
                json.put("device_info_status", "unavailable")
                return json
            }
            channel = currentChannel
            val done = CountDownLatch(1)
            val device = AtomicReference<WifiP2pDevice?>(null)
            manager.requestDeviceInfo(currentChannel, object : WifiP2pManager.DeviceInfoListener {
                override fun onDeviceInfoAvailable(wifiP2pDevice: WifiP2pDevice?) {
                    device.set(wifiP2pDevice)
                    done.countDown()
                }
            })
            if (!done.await(DEVICE_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                json.put("device_info_status", "timeout")
                return json
            }
            val info = device.get() ?: run {
                val current = permissionState(context, directPermission())
                json.put("device_info_status",
                    if (current != "granted") "not_granted" else "unavailable")
                return json
            }
            json.put("device_info_status", "ok")
            json.put("device_address_sha256", hashes.value(info.deviceAddress) ?: JSONObject.NULL)
            json.put("device_name_sha256", hashes.value(info.deviceName) ?: JSONObject.NULL)
        } catch (error: Throwable) {
            json.put("device_info_status", errorValue(error))
        } finally {
            channel?.close()
        }
        return json
    }

    private fun permissionState(context: Context, permission: String): String = try {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "not_granted"
        }
    } catch (error: Throwable) {
        errorValue(error)
    }

    private fun locationEnabled(context: Context): Any {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return "not_required"
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "unavailable"
        return guarded { manager.isLocationEnabled() }.first ?: "unsupported"
    }

    private fun awarePermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun directPermission(): String = awarePermission()

    private fun errorValue(error: Throwable): String = when (error) {
        is SecurityException -> "not_granted"
        is UnsupportedOperationException, is LinkageError -> "unsupported"
        else -> "error"
    }

    private fun <T> guarded(block: () -> T): Pair<T?, String?> = try {
        block() to null
    } catch (error: Throwable) {
        null to errorValue(error)
    }

    private fun saltFrom(arguments: Bundle): ByteArray {
        val text = arguments.getString("wifi_census_salt")?.trim().orEmpty()
        if (text.length == SALT_BYTES * 2 &&
            text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ByteArray(SALT_BYTES) { index ->
                text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /**
     * API 31 types live only in this separate class. JUnit reads every method signature of the
     * test class (lambdas included) before running it, so an AwareResources there made the whole
     * class fail to load on Android 11 (NoClassDefFoundError before any guard could run).
     */
    private object Api31Aware {
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        fun resources(manager: WifiAwareManager): JSONObject? {
            val resources = manager.getAvailableAwareResources() ?: return null
            return JSONObject()
                .put("available_data_paths", resources.getAvailableDataPathsCount())
                .put("available_publish_sessions", resources.getAvailablePublishSessionsCount())
                .put("available_subscribe_sessions", resources.getAvailableSubscribeSessionsCount())
        }
    }

    private data class AttachOutcome(val status: String, val elapsedMs: Long)

    /** Keeps the session open for the capability reads, then closes it exactly once. */
    private class AttachSession {
        private val lock = Any()
        private var closed = false
        private var session: WifiAwareSession? = null

        fun accept(newSession: WifiAwareSession): Boolean = synchronized(lock) {
            if (closed || session != null) {
                false
            } else {
                session = newSession
                true
            }
        }

        fun isClosed(): Boolean = synchronized(lock) { closed }

        fun close() {
            val toClose = synchronized(lock) {
                closed = true
                session.also { session = null }
            }
            toClose?.close()
        }
    }

    /** SHA-256(salt | value)[0:8] hex, matching tools/wifi_census.py exactly. */
    private class SaltedHashes(private val salt: ByteArray) {
        fun value(value: String?): String? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            val normalized = if (MAC.matches(text)) text.lowercase().replace(":", "").replace("-", "")
            else text
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(salt)
            digest.update('|'.code.toByte())
            digest.update(normalized.toByteArray(Charsets.UTF_8))
            return digest.digest().take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        }

        private companion object {
            const val HASH_BYTES = 8
            val MAC = Regex("(?i)^(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}$")
        }
    }

    private companion object {
        const val SALT_BYTES = 32
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 30_000L
        const val DEVICE_INFO_TIMEOUT_MS = 5_000L
    }
}
