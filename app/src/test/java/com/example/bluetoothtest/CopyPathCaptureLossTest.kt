package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyPathCaptureLossTest {
    /** The scorer's own pattern (tools/score_device_run.py) reads the cumulative count from either line. */
    private val scorerPattern = Regex("""capture drops (\d+)""")

    private fun scored(line: String): Int = scorerPattern.find(line)!!.groupValues[1].toInt()

    @Test
    fun everyFrameCopiedIsZeroDropsEvenWhileTheNextFramesAreStillInFlight() {
        val loss = CopyPathCaptureLoss()
        // The capture result may come before or after its image is copied; three frames wait at the end.
        loss.onDelivered(100)
        loss.onHanded(100)
        loss.onHanded(133)
        loss.onDelivered(133)
        loss.onDelivered(166)
        loss.onDelivered(200)
        loss.onDelivered(233)
        assertEquals(0, scored("Encoder output: 30.0 FPS, 900 kbps" + loss.periodicField()))
        assertEquals(0, scored(loss.finalLine()))
    }

    @Test
    fun framesSkippedByTheCopyQueueAreDropsOnceANewerFrameIsCopied() {
        val loss = CopyPathCaptureLoss()
        listOf(100L, 133L, 166L, 200L).forEach(loss::onDelivered)
        loss.onHanded(100)
        // The encoder was busy: 133 and 166 were replaced by 200 (acquire-latest or the waiting image).
        loss.onHanded(200)
        assertEquals(2, scored(loss.periodicField()))
        assertEquals(
            "Encoder input FINAL: capture drops 2, copy path, camera delivered 4, handed 2, skipped 2, failed captures 0",
            loss.finalLine(),
        )
    }

    @Test
    fun aFailedCaptureIsADropAndADeliberateBlankDropIsNot() {
        val loss = CopyPathCaptureLoss()
        loss.onDelivered(100)
        loss.onDeliberatelyDropped(100) // the Experimental leading-blank drop
        loss.onCaptureFailed()
        loss.onDelivered(166)
        loss.onHanded(166)
        assertEquals(1, scored(loss.periodicField()))
    }

    @Test
    fun aStalledEncoderInputCountsTheFramesItCouldNotTake() {
        val loss = CopyPathCaptureLoss()
        repeat(40) { loss.onDelivered(it * 33L) }
        assertEquals(8, scored(loss.periodicField()))
    }

    @Test
    fun aCaptureResultThatArrivesAfterItsImageWasCopiedIsNotADrop() {
        val loss = CopyPathCaptureLoss()
        loss.onHanded(100)
        loss.onDelivered(100)
        loss.onDelivered(133)
        loss.onHanded(133)
        loss.onHanded(166)
        loss.onDelivered(166)
        loss.onDelivered(200)
        loss.onHanded(233) // 200 was replaced by 233
        loss.onDelivered(233)
        assertEquals(1, scored(loss.periodicField()))
        assertEquals("Encoder input FINAL: capture drops 1, copy path, camera delivered 5, handed 4, skipped 1, failed captures 0", loss.finalLine())
    }
}
