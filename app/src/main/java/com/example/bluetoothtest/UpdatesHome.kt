package com.example.bluetoothtest

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.widget.TextView

/**
 * Glue between MainActivity and the update features (both flavours): applies the switches to the link
 * hello's build tag, keeps the quiet "Newer version nearby" row on the home screen, asks the one-time
 * question and triggers a due automatic check. All decisions are in the pure classes ([BuildTag],
 * [NewerVersionNearby], [UpdateSchedule], [UpdatePrompt]).
 */
internal class UpdatesHome(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val notice: TextView,
    /** True while the home screen is shown and no stream, call, relay or reconnect exists. */
    private val homeIdle: () -> Boolean,
) {
    private val noticeStore = NewerVersionNearbyStore(PrefsNoticeStore(prefs))
    private val checkStore = UpdateCheckStore(PrefsUpdateStore(prefs))
    private val build: InstalledBuild? = InstalledBuild.read(activity)
    private val lock = Any()
    private var sink: ((BuildTag) -> Unit)? = null

    /** Applies both switches (call after the feature catalog was refreshed) and shows the current notice. */
    fun apply() {
        val b = build ?: return
        if (Updater.backend.available && UpdateConsent.reconcile(activity, prefs)) {
            // A restored "yes" without its marker: forgotten, so the question comes back (UpdateConsent).
            // Keeps the launch of the activity's last refresh (its extras and Advanced toggle).
            FeatureCatalog.refreshStoredChoices { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null }
        }
        val nearbyOn = FeatureCatalog.isEnabled(UpdateFeatures.newerVersionsNearby.id)
        val release = UpdateRuntime.release(b)
        val tag = if (nearbyOn && release in 1..ReleaseNumber.MAX) BuildTag(release, UpdateRuntime.hasReleaseKey(b)) else null
        LinkBuildTags.local = tag
        LinkBuildTags.build = if (release in 1..ReleaseNumber.MAX) BuildTag(release, UpdateRuntime.hasReleaseKey(b)) else null
        sink?.let { if (LinkBuildTags.sink === it) LinkBuildTags.sink = null }
        sink = if (nearbyOn) ({ peer: BuildTag -> observe(peer) }) else null
        LinkBuildTags.sink = sink
        if (BuildConfig.DEBUG) {
            Log.i("BluetoothVideo", "BENCHMARK newer_versions_nearby: on=$nearbyOn | sendsTag=${tag?.encode()?.toString(16)} | release=$release")
        }
        if (Updater.backend.available) {
            Updater.backend.applyCheckSetting(
                activity,
                FeatureCatalog.isEnabled(UpdateFeatures.checkForUpdates.id) && UpdateRuntime.onlineCheckOffered(b),
            )
        }
        refreshNotice()
    }

    /** The activity is going away: stop reporting tags into a dead screen (the tag we send stays). */
    fun stop() {
        sink?.let { if (LinkBuildTags.sink === it) LinkBuildTags.sink = null }
        sink = null
    }

    fun onResume() {
        refreshNotice()
        val b = build ?: return
        val action = UpdateResume.action(
            updaterAvailable = Updater.backend.available,
            onlineOffered = UpdateRuntime.onlineCheckOffered(b),
            homeIdle = homeIdle(),
            askedAtMs = checkStore.load().askedAtMs,
            benchmarkLaunch = DebugTunables.benchmarkLaunch,
            checkEnabled = FeatureCatalog.isEnabled(UpdateFeatures.checkForUpdates.id),
        )
        when (action) {
            UpdateResumeAction.ASK -> Updater.backend.askOnce(activity) { yes ->
                UpdateConsent.set(activity, prefs, yes)
                // Keeps the launch of the activity's last refresh (its extras and Advanced toggle).
                FeatureCatalog.refreshStoredChoices { key -> if (prefs.contains(key)) prefs.getBoolean(key, false) else null }
                apply()
            }
            UpdateResumeAction.CHECK_NOW -> Updater.backend.maybeCheckNow(activity) { activity.runOnUiThread { refreshNotice() } }
            UpdateResumeAction.NOTHING -> Unit
        }
    }

    /** A user choice in Settings > Features counts as the answer to the question. */
    fun onSettingChanged(spec: FeatureSpec) {
        // Turning the check off also forgets what it learned, so no "Update available" lingers.
        if (spec == UpdateFeatures.checkForUpdates) UpdateConsent.onSwitchChanged(activity, prefs)
        apply()
    }

    private fun observe(tag: BuildTag) {
        val b = build ?: return
        val now = System.currentTimeMillis()
        val changed = synchronized(lock) {
            val before = noticeStore.load()
            val after = NewerVersionNearby.observe(before, tag, UpdateRuntime.release(b), UpdateRuntime.hasReleaseKey(b), now)
            if (after != before) noticeStore.save(after)
            after != before
        }
        if (changed) {
            if (BuildConfig.DEBUG) Log.i("BluetoothVideo", "BENCHMARK newer_version_nearby: peerRelease=${tag.releaseNumber} | peerReleaseKey=${tag.releaseKey}")
            activity.runOnUiThread { refreshNotice() }
        }
    }

    /** The newest release tag the last check saw, when it is newer than this copy and the online check is on. */
    private fun onlineUpdateTag(b: InstalledBuild): String? {
        if (!Updater.backend.available || !UpdateRuntime.onlineCheckOffered(b)) return null
        if (!FeatureCatalog.isEnabled(UpdateFeatures.checkForUpdates.id)) return null
        val tag = checkStore.load().latestTag ?: return null
        val latest = ReleaseVersion.parse(tag) ?: return null
        val installed = ReleaseVersion.parse(b.versionName) ?: return null
        return tag.takeIf { latest > installed }
    }

    fun refreshNotice() {
        val b = build
        if (b == null || !homeIdle()) {
            hideNotice()
            return
        }
        // A newer release GitHub named (the user opted in) is more certain than a nearby phone's claim.
        onlineUpdateTag(b)?.let { tag ->
            notice.text = activity.getString(R.string.update_available_notice, ReleaseVersion.parse(tag)?.toString().orEmpty())
            notice.visibility = View.VISIBLE
            notice.setOnClickListener { Updater.backend.open(activity, 0) }
            return
        }
        val nearbyOn = FeatureCatalog.isEnabled(UpdateFeatures.newerVersionsNearby.id)
        val release = UpdateRuntime.release(b)
        val shown = if (!nearbyOn || release < 1) {
            null
        } else {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val before = noticeStore.load()
                val tidy = NewerVersionNearby.tidy(before, release, now)
                if (tidy != before) noticeStore.save(tidy)
                NewerVersionNearby.notice(tidy, release, UpdateRuntime.hasReleaseKey(b), now)
            }
        }
        if (shown == null) {
            hideNotice()
        } else {
            notice.text = activity.getString(R.string.update_nearby_notice) // no release number (audit 2026-10-21)
            notice.visibility = View.VISIBLE
            notice.setOnClickListener { Updater.backend.open(activity, shown) }
        }
    }

    private fun hideNotice() {
        notice.visibility = View.GONE
        notice.setOnClickListener(null)
    }

    companion object {
        /** "Hide this for a week" from the update screen or the F-Droid dialog. */
        fun dismissNearby(context: Context, prefs: SharedPreferences) {
            val store = NewerVersionNearbyStore(PrefsNoticeStore(prefs))
            store.save(NewerVersionNearby.dismiss(store.load(), System.currentTimeMillis()))
        }
    }
}
