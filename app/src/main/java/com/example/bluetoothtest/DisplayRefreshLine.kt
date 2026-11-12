package com.example.bluetoothtest

import android.app.Activity
import android.os.Build
import java.util.Locale

/**
 * DEBUG launch line `BENCHMARK display: refreshHz=<n> | maxHz=<n> | modes=<a/b/...>`: the display's refresh rate when
 * the app started, the fastest mode at the current resolution and every rate offered there. The scorer takes half a
 * refresh period as its smoothness tolerance; without this line it assumes 60 Hz. An adaptive panel (1-120 Hz) can
 * change rate later: `refreshHz` is the launch value only, `maxHz` bounds it. `unknown` when the display cannot say.
 */
internal object DisplayRefreshLine {
    fun line(currentHz: Float?, modeHz: List<Float>): String {
        val current = currentHz?.takeIf { it > 0f }
        val modes = modeHz.filter { it > 0f }.distinct().sorted()
        val max = (modes + listOfNotNull(current)).maxOrNull()
        return "BENCHMARK display: refreshHz=${hz(current)} | maxHz=${hz(max)} | " +
            "modes=${if (modes.isEmpty()) "unknown" else modes.joinToString("/") { hz(it) }}"
    }

    private fun hz(value: Float?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "unknown"

    fun line(activity: Activity): String {
        val display = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
        }.getOrNull() ?: return line(null, emptyList())
        val mode = runCatching { display.mode }.getOrNull()
        val modes = runCatching {
            display.supportedModes
                .filter { mode == null || it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                .map { it.refreshRate }
        }.getOrDefault(emptyList())
        return line(runCatching { display.refreshRate }.getOrNull(), modes)
    }
}
