package com.example.bluetoothtest

/**
 * The first capture session of a call camera waits for two things that no longer wait for each other (JT2, switch
 * `test_call_camera_first`, promoted 2026-11-12, default on): the opened camera device and the preview surface. The encoder asks for the surface and
 * opens the camera together; whichever finishes second tells this join, which says when to configure the session. Pure and
 * JVM-tested. All events arrive on the encoder's one camera worker thread, so it needs no lock.
 *
 * Before the switch, the surface was requested first and the camera opened only when the surface arrived (serial). Only the order
 * of the two changes; the session is configured with the same targets, after the same two facts.
 */
internal class CameraOpenJoin {
    enum class Action {
        /** Configure the capture session now (once, for the first session; later opens of the same device always configure). */
        CONFIGURE,

        /** The other half is still missing. */
        WAIT,

        /** The surface request failed: end the camera start with the surface error. */
        FAIL,

        /** Nothing to do: the join was closed, or the event is stale. */
        IGNORE,
    }

    private var opened = false
    private var surfaceReady = false
    @Volatile private var finished = false
    @Volatile private var closed = false

    /** The camera device opened (the first open, or a reopen after a transient error). */
    fun onCameraOpened(): Action {
        if (closed) return Action.IGNORE
        opened = true
        // After the first session was asked for, a reopen configures at once, as it always did.
        if (finished) return Action.CONFIGURE
        return configureWhenBoth()
    }

    /** The preview surface is ready (the viewfinder gave its surface). */
    fun onSurfaceReady(): Action {
        if (closed) return Action.IGNORE
        surfaceReady = true
        if (finished) return Action.IGNORE
        return configureWhenBoth()
    }

    /** The surface request failed. */
    fun onSurfaceFailed(): Action = if (closed || finished) Action.IGNORE else Action.FAIL

    /**
     * The camera device reported an error, was disconnected or closed: it is not open any more, so a session is never
     * configured on it; a retry that opens it again joins as usual.
     */
    fun onCameraGone() {
        if (!closed) opened = false
    }

    /** The encoder closed (any thread): nothing is configured afterwards, whatever arrives. */
    fun onEncoderClosed() {
        closed = true
    }

    /** The first session was asked for. */
    val configured: Boolean get() = finished

    private fun configureWhenBoth(): Action {
        if (!opened || !surfaceReady) return Action.WAIT
        finished = true
        return Action.CONFIGURE
    }
}
