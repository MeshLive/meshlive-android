package com.example.bluetoothtest

import android.app.Application
import android.content.Context

/** The process entry point: installs [CrashReports] before any screen, service or receiver runs. */
class MeshLiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { CrashReports.install(this) }
        // Before the installers below and any receiver or service: the person's feature snapshot (B15).
        runCatching { snapshotFeatures(this) }
        // One "is this person blocked" rule for calls, reveals, streams, message exchanges and leases (PersonPolicy).
        runCatching { PersonPolicy.install(this) }
        // B2: hide the call-offer/viewer-request sets again as soon as the Bluetooth name leaves "Phone".
        runCatching { DiscoverableWhenNeutral.install(this) }
        // Wi-Fi radio: stores the context only; no thread, receiver or platform call until a Wi-Fi switch is on (X4).
        runCatching { WifiRadio.attach(this) }
        // The attempt owner's PowerManager seam: stores the manager only; no platform call while every switch is off.
        runCatching { RealWifiEnv.attach(this) }
        // Both production switch sources read the catalog lazily; non-blocking, exception = all off [R M-4] [R M-7] [X6].
        runCatching {
            WifiSwitchHub.isPublishThread = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }
            WifiHopGate.switchSource = { WifiSwitchHub.current() }                 // A2: the effective own BMH2 build tag [R M-4] [X6]
            WifiRadio.switchSource = WifiSwitchSource { WifiSwitchHub.current() }  // A4: the facts reader reads it on every call [R M-7]
        }
        // A9: the Direct factory stores the context only; the sweep reads the sealed file's presence on this thread.
        runCatching { DirectBearerFactory.attach(this) }
        runCatching { DirectStartupSweep.run(this) }
        // PRIV1: opens the sealed peer memories on their worker thread (an earlier build's plain copies are moved and
        // deleted even when the feature is never used); nothing runs while a wipe is pending.
        runCatching { PeerMemoryStartup.run(this) }
    }

    companion object {
        /**
         * Switch audit B15: the feature snapshot existed only after MainActivity ran, so a receiver, provider or the
         * boot-resume start in a fresh process read release-ON features (BMH3, names) as off. A person's launch is
         * resolved here (stored choices, else the defaults); MainActivity resolves again with its own launch, which in a
         * DEBUG benchmark run reads the extras instead.
         */
        internal fun snapshotFeatures(context: Context) {
            AppFeatures.registerAll()
            val prefs = context.getSharedPreferences(CallFeatureDefaults.APP_SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
            FeatureCatalog.refresh(
                benchmarkLaunch = false,
                extra = { null },
                stored = { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null },
                advancedSettings = prefs.getBoolean(PREF_ADVANCED_SETTINGS, false),
            )
        }
    }
}
