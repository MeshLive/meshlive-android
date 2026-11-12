package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallPrewarmTest {
    /** A fake encoder: what was done to it, on the fake clock. */
    private class FakeEncoder(val name: String) {
        val events = mutableListOf<String>()
        var cameraOpened = false
    }

    /**
     * The camera executor on a fake clock: tasks run one at a time, in order, each taking its declared cost (prepare
     * [prepareMs], camera start [cameraMs]); a task posted at time t starts no earlier than t.
     */
    private inner class FakeCameraExecutor {
        var busyUntilMs = 0L
        fun execute(task: Runnable) {
            val start = maxOf(nowMs, busyUntilMs)
            val saved = nowMs
            nowMs = start
            task.run()
            busyUntilMs = nowMs
            nowMs = saved
        }
    }

    private var nowMs = 0L
    private val prepareMs = 300L
    private val cameraMs = 500L
    private val lines = mutableListOf<String>()
    private val executor = FakeCameraExecutor()
    private val released = mutableListOf<FakeEncoder>()
    private val prewarm = CallEncoderPrewarm<String, FakeEncoder>(
        run = executor::execute,
        nowMs = { nowMs },
        log = { lines += it },
        prepare = { encoder -> nowMs += prepareMs; encoder.events += "prepared" },
        release = { encoder -> encoder.events += "released"; released += encoder },
    )

    /** Today's start (or an adopted encoder's): the encoder part when not prepared, then the camera. Returns first-frame time. */
    private fun startOnExecutor(encoder: FakeEncoder, prepared: Boolean): Long {
        var firstFrameAtMs = -1L
        executor.execute(
            Runnable {
                if (!prepared) {
                    nowMs += prepareMs
                    encoder.events += "prepared"
                }
                encoder.cameraOpened = true
                encoder.events += "camera"
                nowMs += cameraMs
                firstFrameAtMs = nowMs
            },
        )
        return firstFrameAtMs
    }

    /** A call: ringing at 0, Accept at [acceptAtMs]; the media start takes the prepared encoder or builds one. */
    private fun firstEncodedFrameMs(prewarmOn: Boolean, acceptAtMs: Long = 2_000L): Long {
        nowMs = 0L
        executor.busyUntilMs = 0L
        if (CallPrewarm.preparesAt(prewarmOn, PrivateCallPhase.INCOMING_RINGING, video = true)) {
            prewarm.offer("vp9-640x480-30") { FakeEncoder("prepared") }
        }
        nowMs = acceptAtMs
        val taken = prewarm.take("vp9-640x480-30")
        return startOnExecutor(taken?.encoder ?: FakeEncoder("built"), prepared = taken != null)
    }

    @Test
    fun withTheSwitchOnTheFirstEncodedFrameComesEarlierByTheEncoderSetup() {
        val off = firstEncodedFrameMs(prewarmOn = false)
        val on = firstEncodedFrameMs(prewarmOn = true)
        assertEquals(2_000L + prepareMs + cameraMs, off)
        assertEquals(2_000L + cameraMs, on)
    }

    @Test
    fun anAcceptBeforeThePrepareFinishedStillAdoptsItAndIsNeverLater() {
        // Accepted 100 ms into a 300 ms prepare: the serial executor finishes it first, then opens the camera.
        assertEquals(prepareMs + cameraMs, firstEncodedFrameMs(prewarmOn = true, acceptAtMs = 100L))
    }

    @Test
    fun theEncoderIsPreparedWhileRingingButThePrewarmNeverOpensTheCamera() {
        val encoder = FakeEncoder("prepared")
        assertTrue(prewarm.offer("key") { encoder })
        assertEquals(listOf("prepared"), encoder.events)
        assertFalse(encoder.cameraOpened)
        // Only the call's media start (after Accept) starts it; the prewarm hands it over unstarted.
        val taken = prewarm.take("key")
        assertSame(encoder, taken?.encoder)
        assertFalse(encoder.cameraOpened)
    }

    @Test
    fun onlyTheRingingPhasesOfAVideoCallPrepare() {
        for (phase in PrivateCallPhase.values()) {
            val ringing = phase == PrivateCallPhase.OUTGOING_RINGING || phase == PrivateCallPhase.INCOMING_RINGING
            assertEquals(phase.name, ringing, CallPrewarm.preparesAt(true, phase, video = true))
            assertFalse(phase.name, CallPrewarm.preparesAt(false, phase, video = true))
            assertFalse(phase.name, CallPrewarm.preparesAt(true, phase, video = false))
        }
        assertTrue(CallPrewarm.releasesAt(PrivateCallPhase.ENDED))
        assertTrue(CallPrewarm.releasesAt(PrivateCallPhase.ACTIVE))
        assertFalse(CallPrewarm.releasesAt(PrivateCallPhase.NEGOTIATING_MEDIA))
    }

    @Test
    fun aDeclineReleasesThePreparedEncoderAndTheNextMediaStartBuildsAsToday() {
        val encoder = FakeEncoder("prepared")
        prewarm.offer("key") { encoder }
        prewarm.release("ended")
        assertEquals(listOf(encoder), released)
        assertFalse(encoder.cameraOpened)
        assertNull(prewarm.take("key"))
        assertTrue(lines.any { it.contains("event=released | reason=ended") })
    }

    @Test
    fun aRingThatOutlastsTheHoldReleasesTheEncoder() {
        val encoder = FakeEncoder("prepared")
        prewarm.offer("key") { encoder }
        nowMs += CallPrewarm.MAX_HOLD_MS - 1
        prewarm.expire()
        assertTrue(prewarm.holding)
        nowMs += 1
        prewarm.expire()
        assertFalse(prewarm.holding)
        assertEquals(listOf(encoder), released)
    }

    @Test
    fun aProfileChangedBeforeMediaStartReleasesThePreparedEncoderAndBuildsAFreshOne() {
        val encoder = FakeEncoder("prepared")
        prewarm.offer("vp9-640x480") { encoder }
        assertNull(prewarm.take("vp9-320x240"))
        assertEquals(listOf(encoder), released)
        assertTrue(lines.any { it.contains("reason=profile_changed") })
    }

    @Test
    fun aFailedPrepareOrEncoderIsNotAdopted() {
        val failing = CallEncoderPrewarm<String, FakeEncoder>(
            run = executor::execute, nowMs = { nowMs }, log = { lines += it },
            prepare = { error("codec refused") }, release = { released += it },
        )
        failing.offer("key") { FakeEncoder("broken") }
        assertNull(failing.take("key"))
        assertEquals(1, released.size)

        var sink: PrewarmPacketSink? = null
        prewarm.offer("key") { s -> sink = s; FakeEncoder("dies") }
        sink!!.onFailure(IllegalStateException("codec error while ringing"))
        assertNull(prewarm.take("key"))
    }

    @Test
    fun codecConfigEmittedBeforeAdoptionReachesTheCallFirstAndInOrder() {
        val sink = PrewarmPacketSink()
        val config = StreamProtocol.FLAG_CODEC_CONFIG
        sink.onPacket(config, 0L, byteArrayOf(1))
        sink.onPacket(config, 0L, byteArrayOf(2))
        sink.onPacket(0, 33L, byteArrayOf(9)) // no frame can precede the camera: dropped, counted
        val sent = mutableListOf<Byte>()
        sink.attach({ _, _, bytes -> sent += bytes[0] }, {})
        sink.onPacket(StreamProtocol.FLAG_KEY_FRAME, 66L, byteArrayOf(3))
        assertEquals(listOf<Byte>(1, 2, 3), sent)
        assertEquals(1, sink.discarded)
    }

    @Test
    fun aFailureAfterAdoptionGoesToTheCall() {
        val sink = PrewarmPacketSink()
        val failures = mutableListOf<Throwable>()
        sink.attach({ _, _, _ -> }, { failures += it })
        val error = IllegalStateException("camera stopped")
        sink.onFailure(error)
        assertEquals(listOf<Throwable>(error), failures)
    }

    private class Component(val name: String) { var released = false }

    private fun decoderHolder(pending: MutableList<Runnable>, name: String?) = PreparedDecoderComponent<Component>(
        runAsync = { pending += it },
        allocate = { name?.let { it to Component(it) } },
        release = { it.released = true },
        log = { lines += it },
    )

    @Test
    fun theDecoderComponentAllocatedAtTheHeaderIsUsedByTheFirstBuildOfTheSameDecoder() {
        val pending = mutableListOf<Runnable>()
        val holder = decoderHolder(pending, "c2.qti.hevc.decoder")
        holder.prepare()
        pending.forEach(Runnable::run)
        val used = holder.take("c2.qti.hevc.decoder")
        assertEquals("c2.qti.hevc.decoder", used?.name)
        assertFalse(used!!.released)
        holder.close() // the receiver closing later does not release a component the decoder owns
        assertFalse(used.released)
    }

    @Test
    fun aDifferentDecoderALateAllocationOrTheCallEndingReleasesTheComponent() {
        val other = mutableListOf<Runnable>()
        val holder = decoderHolder(other, "c2.qti.hevc.decoder")
        holder.prepare()
        other.forEach(Runnable::run)
        assertNull(holder.take("c2.android.hevc.decoder"))
        assertTrue(lines.any { it.contains("reason=other_decoder") })

        val late = mutableListOf<Runnable>()
        val slow = decoderHolder(late, "c2.qti.vp9.decoder")
        slow.prepare()
        assertNull(slow.take("c2.qti.vp9.decoder")) // the first key frame came first: built as today
        late.forEach(Runnable::run)
        assertTrue(lines.any { it.contains("reason=late") })

        val ended = mutableListOf<Runnable>()
        val unused = decoderHolder(ended, "c2.qti.vp9.decoder")
        unused.prepare()
        ended.forEach(Runnable::run)
        unused.close()
        assertTrue(lines.any { it.contains("reason=closed") })
    }
}
