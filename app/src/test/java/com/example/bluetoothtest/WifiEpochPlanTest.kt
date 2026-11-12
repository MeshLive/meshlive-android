package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiEpochPlanTest {
    @Test
    fun theServiceNameIsSixteenLowercaseBase32CharactersOfItsEpoch() {
        assertEquals(WifiEpochPlan.serviceName(5), WifiEpochPlan.serviceName(5))
        assertNotEquals(WifiEpochPlan.serviceName(5), WifiEpochPlan.serviceName(6))
        for (epoch in listOf(0L, 1L, 5L, 123_456L)) {
            val name = WifiEpochPlan.serviceName(epoch)
            assertEquals(16, name.length)
            assertTrue(name.startsWith("m"))
            assertTrue(name.drop(1).all { it in 'a'..'z' || it in '2'..'7' })
        }
    }

    @Test
    fun noRunningSessionOrTheSameEpochIgnores() {
        assertEquals(WifiEpochPlan.Action.Ignore, WifiEpochPlan.onBoundary(null, 7, 0, false, 1_000))
        assertEquals(WifiEpochPlan.Action.Ignore, WifiEpochPlan.onBoundary(7, 7, 2, false, 1_000))
    }

    @Test
    fun aNewEpochWithoutAnAdmittedLinkRestartsWithTheSilence() {
        assertEquals(WifiEpochPlan.Action.Restart(8, 1_500), WifiEpochPlan.onBoundary(7, 8, 0, false, 1_500))
    }

    @Test
    fun aLiveLinkDefersOnce() {
        assertEquals(WifiEpochPlan.Action.Defer(8), WifiEpochPlan.onBoundary(7, 8, 1, false, 1_500))
    }

    @Test
    fun theNextBoundaryForcesTheRestart() {
        assertEquals(WifiEpochPlan.Action.Restart(9, 1_500), WifiEpochPlan.onBoundary(7, 9, 1, true, 1_500))
    }

    @Test
    fun aServedKnockNeverDefers() {
        assertEquals(WifiEpochPlan.Action.Restart(8, 1_500), WifiEpochPlan.onBoundary(7, 8, 0, true, 1_500))
    }

    // --- WifiEpochTracker: the deferral as a state machine (A8 review P0-1) ---------------------------------------

    /**
     * Drives the tracker exactly as AwareDiscovery does: Run starts sessions under its epoch when none are open, Restart
     * closes them (a forced stop, which ends the deferral) and reconciles after the silence. Counts every entry, so a
     * boundary/reconcile loop would show up as a runaway depth instead of a StackOverflowError.
     */
    private class Plane {
        val tracker = WifiEpochTracker()
        var open = false
        var admitted = 0
        var wall = 7L
        val started = ArrayList<Long>()
        var restarts = 0
        var depth = 0
        var maxDepth = 0

        fun boundary(b: Long) = enter {
            wall = b
            when (val step = tracker.onBoundary(b, open, admitted, 1_000)) {
                WifiEpochTracker.Step.Reconcile -> reconcile()
                is WifiEpochTracker.Step.Restart -> restart()
                else -> Unit
            }
        }

        fun reconcile() = enter {
            when (val step = tracker.onReconcile(wall, open, admitted, 1_000)) {
                is WifiEpochTracker.Step.Run -> if (!open) {
                    open = true
                    started += step.epoch
                }
                is WifiEpochTracker.Step.Restart -> restart()
                else -> Unit
            }
        }

        /** A stop that closes every session (forced, or nothing kept). */
        fun stopClosingSessions() {
            open = false
            tracker.onSessionsClosed()
        }

        private fun restart() {
            restarts++
            stopClosingSessions()
            reconcile()   // after the silence
        }

        private fun enter(block: () -> Unit) {
            depth++
            maxDepth = maxOf(maxDepth, depth)
            check(depth < 10) { "boundary/reconcile recursion" }
            try {
                block()
            } finally {
                depth--
            }
        }
    }

    @Test
    fun aBoundaryCrossedWithAnAdmittedLinkRestartsUnderTheNewEpochExactlyOnceWhenTheLastLinkCloses() {
        val plane = Plane()
        plane.reconcile()
        assertEquals(listOf(7L), plane.started)
        plane.admitted = 1
        plane.boundary(8)                              // deferred: the sessions keep epoch 7's name
        assertEquals(8L, plane.tracker.deferred)
        repeat(3) { plane.reconcile() }                // periodic and offer reconciles during the deferral
        assertEquals(7L, plane.tracker.running)        // never overwritten while deferred
        assertEquals(0, plane.restarts)
        plane.admitted = 0                             // the last admitted link closes -> onLinksChanged
        plane.reconcile()
        assertEquals(1, plane.restarts)
        assertEquals(listOf(7L, 8L), plane.started)
        repeat(3) { plane.reconcile() }                // later reconciles change nothing
        plane.boundary(8)                              // a late duplicate of the same boundary
        assertEquals(1, plane.restarts)
        assertEquals(listOf(7L, 8L), plane.started)
        assertEquals(-1L, plane.tracker.deferred)
        assertTrue(plane.maxDepth <= 2)
    }

    @Test
    fun theSameDeferralSeenAgainWhileTheLinkLivesHolds() {
        val plane = Plane()
        plane.reconcile()
        plane.admitted = 1
        plane.boundary(8)
        plane.boundary(8)
        assertEquals(0, plane.restarts)
        assertEquals(7L, plane.tracker.running)
    }

    @Test
    fun aStopDuringTheDeferralThenTheLinkClosingStartsOnceWithoutALoop() {
        val plane = Plane()
        plane.reconcile()
        plane.admitted = 1
        plane.boundary(8)
        plane.stopClosingSessions()                    // e.g. switch off or a Direct pause: a forced stop
        assertEquals(-1L, plane.tracker.deferred)
        plane.admitted = 0
        plane.reconcile()                              // the link's end, then the offer is wanted again
        plane.reconcile()
        assertEquals(0, plane.restarts)
        assertEquals(listOf(7L, 8L), plane.started)
        assertTrue(plane.maxDepth <= 2)
    }

    @Test
    fun aMissedBoundaryWhileDeferredForcesTheRestartAtTheNextReconcile() {
        val plane = Plane()
        plane.reconcile()
        plane.admitted = 1
        plane.boundary(8)
        plane.wall = 9                                 // boundary 9 was never delivered; the 30 s reconcile notices
        plane.reconcile()
        assertEquals(1, plane.restarts)
        assertEquals(listOf(7L, 9L), plane.started)    // the old name lived at most one more epoch (F-10)
    }

    @Test
    fun aNewBoundaryDuringTheDeferralIsForcedEvenWithALiveLink() {
        val plane = Plane()
        plane.reconcile()
        plane.admitted = 1
        plane.boundary(8)
        plane.boundary(9)
        assertEquals(1, plane.restarts)
        assertEquals(listOf(7L, 9L), plane.started)
    }

    @Test
    fun sessionsThePlatformEndedDuringTheDeferralRestartUnderTheWallEpochNotTheOldOne() {
        val plane = Plane()
        plane.reconcile()
        plane.admitted = 1
        plane.boundary(8)
        plane.open = false                             // both sessions terminated by the platform (no stopAll)
        plane.reconcile()
        assertEquals(listOf(7L, 8L), plane.started)
        assertEquals(0, plane.restarts)
    }

    @Test
    fun aBoundaryWithNothingOpenOnlyReconciles() {
        val plane = Plane()
        plane.boundary(8)
        assertEquals(listOf(8L), plane.started)
        assertEquals(0, plane.restarts)
    }

    // --- PublishedKeyEpochs: the stale-key guard (A8 review P1-2) ---------------------------------------------------

    @Test
    fun aRotatingIdLeftInTheRegistryIsNeverPublishedUnderTheNextEpochsName() {
        val keys = PublishedKeyEpochs()
        assertTrue(keys.allow("call:aaaa", 7))
        assertTrue(keys.allow("call:aaaa", 7))       // the same session, or a fresh NMI in the same epoch
        assertFalse(keys.allow("call:aaaa", 8))      // the owner never rotated it: stale_key, nothing goes on air
        assertFalse(keys.allow("call:aaaa", 9))
        assertTrue(keys.allow("call:bbbb", 8))       // the owner's new ID for epoch 8
    }

    @Test
    fun theGuardForgetsOnlyTheOldestKeysWhenFull() {
        val keys = PublishedKeyEpochs(maxKeys = 2)
        keys.allow("a", 1)
        keys.allow("b", 2)
        keys.allow("c", 3)
        assertFalse(keys.allow("b", 4))
        assertFalse(keys.allow("c", 4))
    }
}
