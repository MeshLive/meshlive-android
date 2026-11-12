package com.example.bluetoothtest

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The (i) behind a settings row. A row's label says what it does; the longer explanation lives
 * here, one tap away, instead of as body text under the row.
 */
internal object SettingsInfo {
    fun show(context: Context, @StringRes title: Int, @StringRes message: Int) =
        show(context, context.getString(title), context.getString(message))

    fun show(context: Context, title: CharSequence, message: CharSequence) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.info_close, null)
            .show()
    }
}

/** The settings pages: the top level and the one-level pages its rows open (Advanced is a label with five such rows). */
internal enum class SettingsPage(val viewId: Int, val titleRes: Int, val parent: SettingsPage?) {
    MAIN(R.id.settingsPageMain, R.string.settings_title, null),
    VIDEO(R.id.settingsPageVideo, R.string.settings_video_title, MAIN),
    PRIVACY(R.id.settingsPagePrivacy, R.string.settings_privacy_title, MAIN),
    CALLS(R.id.settingsPageCalls, R.string.settings_calls_title, MAIN),
    SHARE(R.id.settingsPageShare, R.string.settings_share_title, MAIN),
    CONNECTION(R.id.settingsPageConnection, R.string.advanced_section_connection, MAIN),
    MANUAL(R.id.settingsPageManual, R.string.advanced_section_manual, MAIN),
    PLAYBACK(R.id.settingsPagePlayback, R.string.advanced_section_playback, MAIN),
    EXPERIMENTS(R.id.settingsPageExperiments, R.string.settings_experiments_title, MAIN),
    HELP(R.id.settingsPageHelp, R.string.help_title, MAIN),
}

/** The tabs of the bottom navigation (Chats only with the Experimental messages switch on, [ChatTab]). */
internal enum class MainTab { LIVE, CALLS, CHATS, CONTACTS, SETTINGS }
