package com.example.bluetoothtest

import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * The camera check before the QR scanner opens (Android 16 emulator crawl, 2026-10-21: with the camera denied for good,
 * zxing's own request failed and it showed "Sorry, the Android camera encountered a problem. You may need to restart
 * your device", which blames the phone). The app asks first and opens the scanner only with the camera granted.
 *
 * Android has no call that says "denied for good" before asking: a request after a permanent denial returns at once
 * without a prompt and with no rationale due, and that answer is what selects the settings text. Asking first also
 * covers a prompt the user only dismissed (it is shown again on the next tap).
 */
internal object CameraScanGate {
    enum class Step {
        /** The camera is granted: open the scanner. */
        LAUNCH,

        /** Ask through the app's own permission request. */
        REQUEST,

        /** Denied this time; the system will ask again: say why the camera is needed. */
        EXPLAIN,

        /** The system no longer asks (denied for good, or the prompt was dismissed): the text plus Open settings. */
        SETTINGS,
    }

    fun beforeScan(granted: Boolean): Step = if (granted) Step.LAUNCH else Step.REQUEST

    fun afterRequest(granted: Boolean, rationaleDue: Boolean): Step = when {
        granted -> Step.LAUNCH
        rationaleDue -> Step.EXPLAIN
        else -> Step.SETTINGS
    }
}

/**
 * zxing's prompt sits at the bottom of a full-screen window. From Android 15 (target SDK 35+) every window is drawn edge to
 * edge and the prompt fell under the navigation bar (API 36 emulator: prompt [185,2349][895,2400], navigation bar
 * [0,2337][1080,2400]). Before Android 15 the window stops above the navigation bar and the inset is zero.
 */
internal object ScannerInsets {
    /** Margins (left, right, bottom) that keep the prompt [baseBottomPx] above the system bars and cutout. */
    fun statusMargins(baseBottomPx: Int, left: Int, right: Int, bottom: Int): Triple<Int, Int, Int> =
        Triple(left, right, baseBottomPx + bottom)
}

/** The zxing scanner with its prompt kept clear of the system bars ([ScannerInsets]). */
class ScannerCaptureActivity : CaptureActivity() {
    override fun onStop() {
        // Design QL: going Home (or anywhere but back) from the camera leaves the Add contact screen as well; a scan that
        // finishes, or a configuration change, does not. The screen begins a new visit when it is shown again.
        if (!isFinishing && !isChangingConfigurations) QrMeet.visit.end()
        super.onStop()
    }

    override fun initializeContent(): DecoratedBarcodeView {
        val scanner = super.initializeContent()
        val status = scanner.statusView ?: return scanner
        val params = status.layoutParams as? ViewGroup.MarginLayoutParams ?: return scanner
        val baseBottom = params.bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(scanner) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val (left, right, bottom) = ScannerInsets.statusMargins(baseBottom, bars.left, bars.right, bars.bottom)
            params.leftMargin = left
            params.rightMargin = right
            params.bottomMargin = bottom
            status.layoutParams = params
            insets
        }
        ViewCompat.requestApplyInsets(scanner)
        return scanner
    }
}
