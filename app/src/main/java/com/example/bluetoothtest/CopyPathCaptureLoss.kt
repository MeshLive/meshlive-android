package com.example.bluetoothtest

/**
 * Camera-side frame loss for a MediaCodec encoder fed by copying camera YUV images into its input buffers (the software
 * Codec2 path, for example the S22's c2.android.vp9.encoder), reported in the native path's `capture drops N` format
 * (the scorer gate `outgoing.camera_input_drops`, UNMEASURED on this path until now). DEBUG benchmark logging only.
 *
 * It matches frames by sensor timestamp: every completed capture of the encoder's session delivered one frame to the
 * image reader (the reader is a target of every repeating request on this path); every frame copied into the encoder
 * removes its own timestamp. A delivered frame older than a frame that was copied can no longer be copied (images are
 * taken newest first), so it was dropped. That covers the two places this path loses frames: the image reader's
 * acquire-latest skipping images while the encoder was busy, and a waiting image replaced by a newer one before an
 * input buffer was free. Frames still in flight are never counted, so the count does not go up and down.
 *
 * What it cannot see: a capture that failed (no timestamp; counted separately, as the surface path does), a frame the
 * camera never started (a sensor below the requested rate is a frame-rate question), the last frames in flight when
 * the encoder closes, and a frame the encoder dropped after it was handed over (the encoder's own output cadence).
 *
 * Callbacks arrive on the encoder's worker thread; the counters are volatile so close() on another thread reads them.
 */
internal class CopyPathCaptureLoss {
    private val waiting = java.util.TreeSet<Long>()
    private val handedBeforeResult = java.util.TreeSet<Long>()
    private var lastHandedNs = Long.MIN_VALUE

    @Volatile var delivered = 0L
        private set
    @Volatile var handed = 0L
        private set
    @Volatile var skipped = 0L
        private set
    @Volatile var failedCaptures = 0
        private set

    /** Cumulative frames the copy path lost since this encoder started (the gate's counter). */
    val drops: Long get() = skipped + failedCaptures

    /** A completed capture: the camera delivered the frame with this sensor timestamp to the image reader. */
    fun onDelivered(sensorTimestampNs: Long) {
        delivered++
        // Its image was copied before its capture result arrived: delivered and handed, nothing waits.
        if (handedBeforeResult.remove(sensorTimestampNs)) return
        // Reported after a newer frame was copied: it can never be copied any more.
        if (sensorTimestampNs < lastHandedNs) {
            skipped++
            return
        }
        waiting += sensorTimestampNs
        // Bounded: a frame that waits this long behind newer ones was not copied (a stalled encoder input counts as loss).
        while (waiting.size > MAX_WAITING) {
            waiting.pollFirst()
            skipped++
        }
    }

    /** The image with this sensor timestamp was copied into an encoder input buffer and queued. */
    fun onHanded(sensorTimestampNs: Long) {
        handed++
        if (!waiting.remove(sensorTimestampNs)) {
            handedBeforeResult += sensorTimestampNs
            while (handedBeforeResult.size > MAX_WAITING) handedBeforeResult.pollFirst()
        }
        lastHandedNs = maxOf(lastHandedNs, sensorTimestampNs)
        while (waiting.isNotEmpty() && waiting.first() < sensorTimestampNs) {
            waiting.pollFirst()
            skipped++
        }
    }

    /** A frame this path dropped on purpose (the Experimental leading-blank drop): not a loss. */
    fun onDeliberatelyDropped(sensorTimestampNs: Long) {
        // Accounted for whichever comes first, the image or its capture result.
        if (!waiting.remove(sensorTimestampNs)) handedBeforeResult += sensorTimestampNs
    }

    fun onCaptureFailed() {
        failedCaptures++
    }

    /** The field appended to each periodic `Encoder output:` line, as the native path appends its own. */
    fun periodicField(): String = ", capture drops $drops"

    /** The line at close, in the native path's `Encoder input FINAL:` format plus this path's breakdown. */
    fun finalLine(): String =
        "Encoder input FINAL: capture drops $drops, copy path, camera delivered $delivered, handed $handed, " +
            "skipped $skipped, failed captures $failedCaptures"

    private companion object {
        const val MAX_WAITING = 32
    }
}
