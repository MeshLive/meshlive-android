package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceCaptureLossTest {
    /** The scorer's own pattern (tools/score_device_run.py) reads the cumulative count from either line. */
    private val scorerPattern = Regex("""capture drops (\d+)""")

    private fun scored(line: String): Int = scorerPattern.find(line)!!.groupValues[1].toInt()

    @Test
    fun aCleanSessionReportsZeroInBothLines() {
        val loss = SurfaceCaptureLoss()
        assertEquals(0, scored("Encoder output: 30.0 FPS, 1500 kbps" + loss.periodicField()))
        assertEquals(0, scored(loss.finalLine()))
    }

    @Test
    fun aBufferLostToTheEncoderAndAFailedCaptureAreDropsButAPreviewLossIsNot() {
        val loss = SurfaceCaptureLoss()
        loss.onBufferLost(encoderTarget = false)
        assertEquals(0, scored(loss.periodicField()))
        loss.onBufferLost(encoderTarget = true)
        loss.onCaptureFailed(flushed = false, imageCaptured = false)
        assertEquals(2, scored(loss.periodicField()))
    }

    @Test
    fun flushedAndImageCapturedFailuresStillCountAndAreBrokenOutAtClose() {
        val loss = SurfaceCaptureLoss()
        loss.onCaptureFailed(flushed = true, imageCaptured = false)
        loss.onCaptureFailed(flushed = false, imageCaptured = true)
        loss.onBufferLost(encoderTarget = true)
        assertEquals(3, scored(loss.finalLine()))
        assertEquals(1, loss.flushedCaptures)
        assertEquals(1, loss.failedWithImage)
    }

    @Test
    fun theCountIsCumulativeAcrossReports() {
        val loss = SurfaceCaptureLoss()
        loss.onBufferLost(encoderTarget = true)
        assertEquals(1, scored(loss.periodicField()))
        loss.onBufferLost(encoderTarget = true)
        assertEquals(2, scored(loss.periodicField()))
    }
}
