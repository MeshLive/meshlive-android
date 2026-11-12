package com.example.bluetoothtest

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Joining a stream while the call endpoint is still starting (STREAM-INTEROP block 1). */
class CallEndpointStartsTest {
    /** A call endpoint as the activity drives it: a start on a worker, a stop, and the listener it may leave up. */
    private class FakeEndpoint {
        val running = AtomicBoolean(true)
        val fence = ListenerStartFence(running)
        var listenerUp = false
        var published = false

        /** The worker: opening runs outside any lock, then publishes under the fence. */
        fun finishOpening(): Boolean {
            listenerUp = true
            return fence.publish(stopOpened = { listenerUp = false }, register = { published = true })
        }

        fun close() {
            if (!running.getAndSet(false)) return
            fence.stop { listenerUp = false; published = false }
        }
    }

    @Test
    fun joinDuringStartLeavesTheCallListenerStoppedBeforeTheViewerStarts() {
        val gate = CallEndpointStartGate()
        val endpoint = FakeEndpoint()
        val token = gate.begin()

        // The user joins a stream while the worker is still opening the listener.
        endpoint.close()
        assertEquals(1, gate.cancelAll())
        var viewerStarted = false
        var listenerUpWhenViewerStarted: Boolean? = null
        val deferred = gate.whenSettled {
            viewerStarted = true
            listenerUpWhenViewerStarted = endpoint.listenerUp
        }
        assertTrue(deferred)
        assertFalse(viewerStarted)

        // The worker finishes after the stop: the late listener closes itself.
        assertFalse(endpoint.finishOpening())
        assertFalse(endpoint.listenerUp)
        val publish = gate.finish(token, stillCurrent = false) { endpoint.close() }

        assertFalse(publish)
        assertFalse(endpoint.published)
        assertTrue(viewerStarted)
        assertEquals(false, listenerUpWhenViewerStarted)
    }

    @Test
    fun startThatFinishedBeforeTheStopIsClosedByTheStop() {
        val endpoint = FakeEndpoint()
        assertTrue(endpoint.finishOpening())
        assertTrue(endpoint.listenerUp)

        endpoint.close()

        assertFalse(endpoint.listenerUp)
        assertFalse(endpoint.published)
    }

    @Test
    fun cancelledStartNeverPublishesEvenIfStillTheActivitysEndpoint() {
        val gate = CallEndpointStartGate()
        val token = gate.begin()
        gate.cancelAll()
        var closed = false

        assertFalse(gate.finish(token, stillCurrent = true) { closed = true })
        assertTrue(closed)
    }

    @Test
    fun uncancelledStartPublishesAndCallsStayReachable() {
        val gate = CallEndpointStartGate()
        val endpoint = FakeEndpoint()
        val token = gate.begin()

        assertTrue(endpoint.finishOpening())
        var closed = false
        assertTrue(gate.finish(token, stillCurrent = true) { closed = true })

        assertFalse(closed)
        assertTrue(endpoint.listenerUp)
        assertTrue(endpoint.published)
    }

    @Test
    fun viewerStartsAtOnceWhenNoEndpointStartIsInFlight() {
        val gate = CallEndpointStartGate()
        var viewerStarted = false

        assertFalse(gate.whenSettled { viewerStarted = true })
        assertTrue(viewerStarted)
    }

    @Test
    fun aHungStartDoesNotHoldTheViewerPastTheSettleTimeout() {
        val gate = CallEndpointStartGate()
        val token = gate.begin()
        gate.cancelAll()
        var viewerStarts = 0
        gate.whenSettled { viewerStarts++ }

        assertEquals(1, gate.abandonInFlight())
        assertEquals(1, viewerStarts)
        // The retried viewer start is not deferred again by the hung start.
        assertFalse(gate.startInFlight)
        assertFalse(gate.whenSettled { viewerStarts++ })
        assertEquals(2, viewerStarts)

        // The late finish still closes its endpoint and does not start the viewer twice.
        var closed = false
        assertFalse(gate.finish(token, stillCurrent = false) { closed = true })
        assertTrue(closed)
        assertEquals(2, viewerStarts)
    }

    @Test
    fun restartAfterTheViewerEndsPublishesAgain() {
        val gate = CallEndpointStartGate()
        val first = gate.begin()
        gate.cancelAll()
        gate.finish(first, stillCurrent = false) {}

        val endpoint = FakeEndpoint()
        val second = gate.begin()
        assertTrue(endpoint.finishOpening())
        assertTrue(gate.finish(second, stillCurrent = true) {})
        assertTrue(endpoint.published)
    }
}
