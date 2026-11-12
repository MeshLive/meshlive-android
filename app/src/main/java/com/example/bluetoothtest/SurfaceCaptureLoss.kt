package com.example.bluetoothtest

/**
 * Camera-side frame loss for a hardware (surface-input) encoder, counted from the Camera2 capture callbacks of the
 * encoder's own session, and reported in the native path's `capture drops N` format (the scorer gate
 * `outgoing.camera_input_drops`). DEBUG benchmark logging only.
 *
 * What it counts:
 * - `onCaptureBufferLost` for the encoder's input surface: the camera produced the frame but could not send that
 *   buffer to the encoder surface (the preview or another target losing a buffer is not counted);
 * - every `onCaptureFailed` of the repeating request (which always targets the encoder surface): the whole capture
 *   failed, so no frame, or no trustworthy frame, went to the encoder. Flushed captures (a session rebuild while
 *   streaming) and failures whose image was captured anyway are counted too and broken out in the final line, so a
 *   capture that may still have delivered a buffer errs toward a reported drop, never toward a silent pass.
 *
 * What it cannot see: a frame the codec's input surface (its BufferQueue) drops or replaces internally after the
 * camera delivered it, a frame the camera never started (a sensor running below the requested rate is a frame-rate
 * question, not a loss), and anything on the GPU-scaled path, whose camera session belongs to the shared capture.
 *
 * Callbacks arrive on the encoder's worker thread; the counters are volatile so close() on another thread reads them.
 */
internal class SurfaceCaptureLoss {
    @Volatile var lostBuffers = 0
        private set
    @Volatile var failedCaptures = 0
        private set
    @Volatile var flushedCaptures = 0
        private set
    @Volatile var failedWithImage = 0
        private set

    /** Cumulative frames lost to the encoder target since this encoder started (the gate's counter). */
    val drops: Int get() = lostBuffers + failedCaptures

    fun onBufferLost(encoderTarget: Boolean) {
        if (encoderTarget) lostBuffers++
    }

    fun onCaptureFailed(flushed: Boolean, imageCaptured: Boolean) {
        failedCaptures++
        if (flushed) flushedCaptures++
        if (imageCaptured) failedWithImage++
    }

    /** The field appended to each periodic `Encoder output:` line, as the native path appends its own. */
    fun periodicField(): String = ", capture drops $drops"

    /** The line at close, in the native path's `Encoder input FINAL:` format plus this path's breakdown. */
    fun finalLine(): String =
        "Encoder input FINAL: capture drops $drops, surface path, lost buffers $lostBuffers, " +
            "failed captures $failedCaptures (flushed $flushedCaptures, image captured $failedWithImage)"
}
