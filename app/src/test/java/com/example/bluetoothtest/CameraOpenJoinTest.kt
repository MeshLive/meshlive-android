package com.example.bluetoothtest

import com.example.bluetoothtest.CameraOpenJoin.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpenJoinTest {
    private val join = CameraOpenJoin()

    @Test
    fun theSessionIsConfiguredByWhicheverHalfArrivesSecond() {
        assertEquals(Action.WAIT, join.onCameraOpened())
        assertEquals(Action.CONFIGURE, join.onSurfaceReady())
        assertTrue(join.configured)

        val other = CameraOpenJoin()
        assertEquals(Action.WAIT, other.onSurfaceReady())
        assertEquals(Action.CONFIGURE, other.onCameraOpened())
    }

    @Test
    fun theFirstSessionIsAskedForOnceAndAReopenConfiguresAgainAsItAlwaysDid() {
        join.onSurfaceReady()
        assertEquals(Action.CONFIGURE, join.onCameraOpened())
        assertEquals("a repeated surface changes nothing", Action.IGNORE, join.onSurfaceReady())
        join.onCameraGone() // a transient open error, retried by the encoder
        assertTrue(join.configured)
        assertEquals("the retried open configures at once", Action.CONFIGURE, join.onCameraOpened())
    }

    @Test
    fun aFailedSurfaceEndsTheStartUnlessItAlreadyHasASession() {
        assertEquals(Action.FAIL, join.onSurfaceFailed())
        assertEquals("the camera opening later does not configure a session without its preview", Action.WAIT, join.onCameraOpened())

        val configured = CameraOpenJoin()
        configured.onCameraOpened()
        configured.onSurfaceReady()
        assertEquals(Action.IGNORE, configured.onSurfaceFailed())
    }

    @Test
    fun aCameraErrorBeforeTheSurfaceMeansTheLaterSurfaceWaitsForTheRetry() {
        assertEquals(Action.WAIT, join.onCameraOpened())
        join.onCameraGone() // error or disconnect: that device is gone
        assertEquals(Action.WAIT, join.onSurfaceReady())
        assertFalse(join.configured)
        assertEquals(Action.CONFIGURE, join.onCameraOpened()) // the retried open
    }

    @Test
    fun aDeviceClosedWhilePendingIsNotConfiguredAndAnEncoderClosedWhilePendingNeverIs() {
        join.onCameraOpened()
        join.onCameraGone() // the device closed before the surface arrived
        assertEquals(Action.WAIT, join.onSurfaceReady())

        val encoderClosed = CameraOpenJoin()
        encoderClosed.onCameraOpened()
        encoderClosed.onEncoderClosed()
        assertEquals(Action.IGNORE, encoderClosed.onSurfaceReady())
        assertEquals(Action.IGNORE, encoderClosed.onCameraOpened())
        assertEquals(Action.IGNORE, encoderClosed.onSurfaceFailed())
        assertFalse(encoderClosed.configured)
    }
}
