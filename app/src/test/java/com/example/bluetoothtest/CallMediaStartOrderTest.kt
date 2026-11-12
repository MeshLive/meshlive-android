package com.example.bluetoothtest

import com.example.bluetoothtest.CallMediaStartOrder.Step
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMediaStartOrderTest {
    private val queues = mutableListOf<AudioCommandQueue>()

    @After
    fun shutdown() {
        queues.forEach { it.shutdown() }
    }

    @Test
    fun withTheSwitchOffAVideoCallStartsInTheOrderOfBefore() {
        assertEquals(
            listOf(
                Step.FOREGROUND_SERVICE, Step.AUDIO_SESSION, Step.MUTE_STATE, Step.CAMERA_FLAGS, Step.LOCAL_PREVIEW,
                Step.CONTROL_LABELS, Step.MICROPHONE_START, Step.CAMERA_START,
            ),
            CallMediaStartOrder.plan(cameraFirst = false, video = true),
        )
    }

    @Test
    fun cameraFirstOpensTheCameraBeforeTheAudioRouteAndTheMicrophoneAfterIt() {
        val plan = CallMediaStartOrder.plan(cameraFirst = true, video = true)
        assertTrue(plan.indexOf(Step.CAMERA_START) < plan.indexOf(Step.AUDIO_SESSION))
        assertTrue("the microphone follows the route open", plan.indexOf(Step.AUDIO_SESSION) < plan.indexOf(Step.MICROPHONE_START))
        assertEquals("the foreground service holds before the camera and microphone", 0, plan.indexOf(Step.FOREGROUND_SERVICE))
        assertTrue(
            "the camera flags and the preview exist before the camera starts",
            plan.indexOf(Step.CAMERA_FLAGS) < plan.indexOf(Step.LOCAL_PREVIEW) && plan.indexOf(Step.LOCAL_PREVIEW) < plan.indexOf(Step.CAMERA_START),
        )
        assertTrue("the labels read the route after it exists", plan.indexOf(Step.AUDIO_SESSION) < plan.indexOf(Step.CONTROL_LABELS))
        assertEquals(plan.size, plan.toSet().size)
    }

    @Test
    fun bothOrdersRunTheSameStepsAndAVoiceCallHasNoCameraStep() {
        for (video in listOf(true, false)) {
            assertEquals(
                CallMediaStartOrder.plan(false, video).toSet(),
                CallMediaStartOrder.plan(true, video).toSet(),
            )
        }
        for (cameraFirst in listOf(true, false)) {
            val voice = CallMediaStartOrder.plan(cameraFirst, video = false)
            assertFalse(Step.CAMERA_START in voice)
            assertTrue(Step.AUDIO_SESSION in voice && Step.MICROPHONE_START in voice)
            assertEquals(CallMediaStartOrder.plan(cameraFirst, true) - Step.CAMERA_START, voice)
        }
    }

    @Test
    fun aFailedStepStopsTheStartAndLeavesTheRestUnrun() {
        val ran = mutableListOf<Step>()
        val finished = CallMediaStartOrder.run(cameraFirst = true, video = true) { step ->
            ran += step
            step != Step.AUDIO_SESSION
        }
        assertFalse(finished)
        assertEquals(Step.AUDIO_SESSION, ran.last())
        assertFalse(Step.MICROPHONE_START in ran)
    }

    /** The queued route open and the queued microphone keep the plan's order even when the off-main switch is off. */
    @Test
    fun aQueuedMicrophoneStartFollowsTheQueuedRouteOpenWhateverTheOffMainSwitchSays() {
        val queue = AudioCommandQueue(isMainThread = { true }, offloadEnabled = { false }, sink = {}).also { queues += it }
        val events = CopyOnWriteArrayList<String>()
        val releaseOpen = CountDownLatch(1)
        val done = CountDownLatch(1)
        queue.post("call_audio_open") {
            releaseOpen.await(5, TimeUnit.SECONDS)
            events += "route_open"
        }
        queue.postForResult("mic_encoder_start", { events += "mic_start" }) { done.countDown() }
        events += "caller_continues" // the caller was blocked by neither
        releaseOpen.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("caller_continues", "route_open", "mic_start"), events.toList())
    }

    @Test
    fun remoteAudioWaitsForTheRouteOpenOnlyWhileOneIsPending() {
        val gate = CallAudioOpenGate()
        assertTrue("no open expected: nothing to wait for", gate.await(10))

        val open = gate.expect()
        assertFalse("the open has not finished: playback waits, then goes on after the bound", gate.await(20))
        val released = CountDownLatch(1)
        val waiter = thread {
            if (gate.await(5_000)) released.countDown()
        }
        Thread.sleep(30)
        assertEquals("still waiting for the open", 1L, released.count)
        open.countDown() // the queued open finished (or the session closed first)
        assertTrue(released.await(5, TimeUnit.SECONDS))
        waiter.join(5_000)
        assertTrue(gate.await(10))

        gate.expect() // the next call's open replaces the finished one
        assertFalse(gate.await(10))
    }
}
