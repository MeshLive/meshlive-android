package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-10 pause gate: per-lease keys, delivery once, no thread and no post without a pauser. */
class DiscoveryPauseGateTest {
    private class FakeScheduler : WifiScheduler {
        var onWifiThread = true
        var postCount = 0
        private val posted = ArrayDeque<() -> Unit>()

        override fun post(block: () -> Unit) {
            postCount++
            posted.addLast(block)
        }

        override fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit) {
            post(block)
        }

        override fun cancel(tag: Any) = Unit

        override fun nowMs(): Long = 0L

        override fun isWifiThread(): Boolean = onWifiThread

        fun drain() {
            while (posted.isNotEmpty()) posted.removeFirst().invoke()
        }
    }

    private class FakePauser(private val name: String, private val throws: Boolean = false) : DiscoveryPauser {
        val paused = mutableListOf<String>()
        val resumed = mutableListOf<String>()
        override fun pauseDiscovery(owner: String) {
            if (throws) throw IllegalStateException("pauser $name cannot pause")
            paused += owner
        }
        override fun resumeDiscovery(owner: String) {
            resumed += owner
        }
    }

    private fun newGate(scheduler: FakeScheduler = FakeScheduler()) = scheduler to DiscoveryPauseGate(scheduler) {}

    @Test
    fun everyLeasePausesEveryPauserOnceAndItsResumeResumesOnce() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val first = FakePauser("first")
        val second = FakePauser("second")
        gate.register(first)
        gate.register(second)
        val lease = gate.pause("direct")
        scheduler.drain()
        assertEquals(1, first.paused.size)
        assertEquals(1, second.paused.size)
        assertEquals(lease.key, first.paused.single())
        lease.resume()
        scheduler.drain()
        assertEquals(listOf(lease.key), first.resumed)
        assertEquals(listOf(lease.key), second.resumed)
        assertFalse(gate.paused)
    }

    @Test
    fun twoLeasesWithTheSameOwnerGetDistinctKeys() {
        val (scheduler, gate) = newGate()
        val first = gate.pause("direct")
        val second = gate.pause("direct")
        scheduler.drain()
        assertNotEquals(first.key, second.key)
        assertTrue(first.key.startsWith("direct#"))
        assertTrue(second.key.startsWith("direct#"))
    }

    @Test
    fun pausedStaysTrueUntilTheLastLeaseIsResumed() {
        val (scheduler, gate) = newGate()
        val first = gate.pause("direct")
        val second = gate.pause("share")
        assertTrue(gate.paused)
        first.resume()
        assertTrue(gate.paused)
        second.resume()
        assertFalse(gate.paused)
        scheduler.drain()
    }

    @Test
    fun resumeIsIdempotent() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val pauser = FakePauser("p")
        gate.register(pauser)
        val lease = gate.pause("direct")
        scheduler.drain()
        lease.resume()
        lease.resume()
        scheduler.drain()
        assertEquals(1, pauser.resumed.size)
    }

    @Test
    fun aPauserRegisteredWhileLeasedIsPausedAtOnce() {
        val scheduler = FakeScheduler()
        scheduler.onWifiThread = false
        val gate = DiscoveryPauseGate(scheduler) {}
        val first = FakePauser("first")
        gate.register(first)
        scheduler.drain()
        val lease = gate.pause("direct")
        scheduler.drain()
        val late = FakePauser("late")
        gate.register(late)
        scheduler.drain()
        assertEquals(listOf(lease.key), late.paused)
        lease.resume()
        scheduler.drain()
        assertEquals(listOf(lease.key), late.resumed)
    }

    @Test
    fun aPauserThatThrowsDoesNotStopTheOthers() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        gate.register(FakePauser("angry", throws = true))
        val calm = FakePauser("calm")
        gate.register(calm)
        gate.pause("direct")
        scheduler.drain()
        assertEquals(1, calm.paused.size)
    }

    @Test
    fun clearDropsLeasesWithoutResume() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val pauser = FakePauser("p")
        gate.register(pauser)
        val lease = gate.pause("direct")
        scheduler.drain()
        gate.clear()
        scheduler.drain()
        assertFalse(gate.paused)
        assertTrue(pauser.resumed.isEmpty())
        assertFalse(lease.active)
        lease.resume()
        scheduler.drain()
        assertTrue(pauser.resumed.isEmpty())
    }

    @Test
    fun aPauserStaysRegisteredAfterClearAndPausesForTheNextLease() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val pauser = FakePauser("p")
        gate.register(pauser)
        val old = gate.pause("direct")
        scheduler.drain()
        gate.clear()
        scheduler.drain()
        val next = gate.pause("direct")
        scheduler.drain()
        assertEquals(next.key, pauser.paused.last())
        old.resume()
        scheduler.drain()
        assertTrue("an old lease's resume after clear calls nothing", pauser.resumed.isEmpty())
    }

    @Test
    fun awaitPausedIsFalseAfterResume() {
        val scheduler = FakeScheduler()
        scheduler.onWifiThread = false
        val gate = DiscoveryPauseGate(scheduler) {}
        val pauser = FakePauser("p")
        gate.register(pauser)
        scheduler.drain()
        val lease = gate.pause("direct")
        lease.resume()
        assertFalse(lease.awaitPaused(0))
        scheduler.drain()
        assertTrue("a lease resumed before its pause ran is never delivered", pauser.paused.isEmpty())
    }

    @Test
    fun awaitPausedIsTrueOnceThePausersReturned() {
        val scheduler = FakeScheduler()
        scheduler.onWifiThread = false
        val gate = DiscoveryPauseGate(scheduler) {}
        gate.register(FakePauser("p"))
        scheduler.drain()
        val lease = gate.pause("direct")
        assertFalse("the pause is still queued", lease.awaitPaused(0))
        scheduler.drain()
        assertTrue(lease.awaitPaused(0))
    }

    @Test
    fun aLeaseWithNoPauserPostsNothing() {
        val scheduler = FakeScheduler()
        scheduler.onWifiThread = false
        val gate = DiscoveryPauseGate(scheduler) {}
        val lease = gate.pause("direct")
        assertEquals(0, scheduler.postCount)
        assertTrue(lease.awaitPaused(0))
        lease.resume()
        assertEquals(0, scheduler.postCount)
    }

    @Test
    fun aPauserRegisteredWhileThePauseIsQueuedIsResumedWhenTheLeaseIsReleasedFirst() {
        // The Share activity pauses on main while the wifi thread registers a plane, and the user cancels before the
        // queued pause runs: the plane that register paused must still get its resume.
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val first = FakePauser("first")
        gate.register(first) // on the wifi thread
        scheduler.onWifiThread = false
        val lease = gate.pause("share") // main: the delivery is queued
        scheduler.onWifiThread = true
        val late = FakePauser("late")
        gate.register(late) // wifi thread, before the queued delivery: register pauses `late` for the lease
        assertEquals(listOf(lease.key), late.paused)
        scheduler.onWifiThread = false
        lease.resume() // main: the lease is marked delivered, so the resume is queued after the delivery
        scheduler.onWifiThread = true
        scheduler.drain()
        assertEquals("the plane register paused is resumed", listOf(lease.key), late.resumed)
        assertTrue("a lease released before its delivery ran never pauses the other planes", first.paused.isEmpty())
        assertTrue(first.resumed.isEmpty())
        assertFalse(gate.paused)
    }

    @Test
    fun aLeaseReleasedWhileRegisterWalksTheLeasesStillResumesItsEarlierPausers() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val first = FakePauser("first")
        gate.register(first)
        val one = gate.pause("direct")
        val two = gate.pause("share")
        assertEquals(listOf(one.key, two.key), first.paused)
        val late = object : DiscoveryPauser {
            val paused = mutableListOf<String>()
            val resumed = mutableListOf<String>()
            override fun pauseDiscovery(owner: String) {
                paused += owner
                if (owner == one.key) {
                    // Main releases the second lease while register is still pausing this plane for the first.
                    scheduler.onWifiThread = false
                    two.resume()
                    scheduler.onWifiThread = true
                }
            }
            override fun resumeDiscovery(owner: String) {
                resumed += owner
            }
        }
        gate.register(late)
        scheduler.drain()
        assertEquals("the plane that the second lease paused is resumed", listOf(two.key), first.resumed)
        assertEquals("register skips the lease released under it", listOf(one.key), late.paused)
        assertTrue("no resume for a pause the plane never saw", late.resumed.isEmpty())
        one.resume()
        scheduler.drain()
        assertEquals(listOf(two.key, one.key), first.resumed)
        assertEquals(listOf(one.key), late.resumed)
        assertFalse(gate.paused)
    }

    @Test
    fun aPauserReachedByRegisterAndByTheQueuedDeliveryIsPausedOnce() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val first = FakePauser("first")
        gate.register(first)
        scheduler.onWifiThread = false
        val lease = gate.pause("direct")
        scheduler.onWifiThread = true
        val late = FakePauser("late")
        gate.register(late) // pauses `late`; the queued delivery then pauses `first` only
        scheduler.drain()
        assertEquals(listOf(lease.key), first.paused)
        assertEquals(listOf(lease.key), late.paused)
        lease.resume()
        scheduler.drain()
        assertEquals(listOf(lease.key), first.resumed)
        assertEquals(listOf(lease.key), late.resumed)
    }

    @Test
    fun aPauserRegisteredAgainAfterUnregisterIsPausedAgainForTheActiveLease() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val pauser = FakePauser("p")
        gate.register(pauser)
        val lease = gate.pause("direct")
        gate.unregister(pauser) // the plane closed; a new start registers it again while the lease holds
        gate.register(pauser)
        assertEquals(listOf(lease.key, lease.key), pauser.paused)
        lease.resume()
        scheduler.drain()
        assertEquals(listOf(lease.key), pauser.resumed)
    }

    @Test
    fun aResumeRacingThePauseDeliveryNeverLeavesDiscoveryPaused() {
        // The Share activity pauses on the main thread and the user cancels at once, while planes register on the
        // wifi thread: whatever the interleaving, every pause a pauser saw is followed by its
        // resume, and no pauser is paused twice for one lease (A4 review P2-2; register racing the delivery).
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            var wifiThread: Thread? = null
            worker.submit { wifiThread = Thread.currentThread() }.get()
            val scheduler = object : WifiScheduler {
                override fun post(block: () -> Unit) {
                    worker.execute(block)
                }
                override fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit) = post(block)
                override fun cancel(tag: Any) = Unit
                override fun nowMs(): Long = 0L
                override fun isWifiThread(): Boolean = Thread.currentThread() === wifiThread
            }
            val gate = DiscoveryPauseGate(scheduler) {}
            val stillPaused = HashSet<String>() // touched on the wifi thread only
            var doublePauses = 0 // wifi thread only
            fun tracker(name: String) = object : DiscoveryPauser {
                override fun pauseDiscovery(owner: String) {
                    if (!stillPaused.add("$name/$owner")) doublePauses++
                }
                override fun resumeDiscovery(owner: String) {
                    stillPaused -= "$name/$owner"
                }
            }
            gate.register(tracker("steady"))
            repeat(20_000) {
                // Every 20 rounds a plane registers (queued on the wifi thread) while leases are taken and released.
                // Planes stay registered: the gate never resumes an unregistered pauser, so an unregister queued
                // before a lease's resume would leave a legal stale entry here.
                if (it % 20 == 0) gate.register(tracker("plane$it"))
                val lease = gate.pause("share")
                if (it % 3 == 0) Thread.yield()
                lease.resume()
            }
            val left = worker.submit<List<String>> { stillPaused.toList() }.get()
            assertEquals("no lease is left paused after its resume", emptyList<String>(), left)
            assertEquals("no pauser is paused twice for one lease", 0, worker.submit<Int> { doublePauses }.get())
            assertFalse(gate.paused)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun aPauserRegisteredAfterALeaseIsPausedForItAndResumedWithIt() {
        val scheduler = FakeScheduler()
        val gate = DiscoveryPauseGate(scheduler) {}
        val lease = gate.pause("direct") // no pauser yet
        val pauser = FakePauser("late")
        gate.register(pauser)
        assertEquals(listOf(lease.key), pauser.paused)
        lease.resume()
        scheduler.drain()
        assertEquals(listOf(lease.key), pauser.resumed)
    }
}
