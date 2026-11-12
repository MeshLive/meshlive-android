package com.example.bluetoothtest

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * SW1 F3: the Bluetooth reader's liveness stamp and the call's record lock. A consumer blocked in the video queue's back-pressure
 * wait holds the record lock; with the switch the reader's stamp does not wait behind it.
 */
class BluetoothHeardTest {
    @get:Rule
    val perTestTimeout: Timeout = Timeout.seconds(30)

    private val second = 1_000_000_000L
    private val recordLock = Any()
    private val generation = AtomicInteger(1)
    private val lastHeartbeatNs = AtomicLong(0L)
    private val clockNs = AtomicLong(100 * second)

    /** A consumer that holds the record lock until [release] (the blocked `EncodedFrameQueue.offer`). */
    private inner class BlockedConsumer {
        private val holding = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val thread: Thread = thread {
            synchronized(recordLock) {
                holding.countDown()
                release.await()
            }
        }

        init {
            holding.await()
        }

        fun release() {
            release.countDown()
            thread.join(5_000)
        }
    }

    private fun heard(stampBeforeLock: Boolean, generationHeard: Int = generation.get(), underLock: () -> Unit = {}): Boolean =
        heardOnBluetoothLink(
            stampBeforeLock, recordLock, generationHeard, { generation.get() },
            { lastHeartbeatNs.set(clockNs.get()) },
        ) {
            lastHeartbeatNs.set(clockNs.get())
            underLock()
        }

    @Test
    fun withTheSwitchTheStampIsTakenWhileAnotherThreadHoldsTheRecordLock() {
        val consumer = BlockedConsumer()
        clockNs.set(105 * second)
        val reader = thread { heard(stampBeforeLock = true) }
        Thread.sleep(300)
        assertEquals("the stamp did not wait for the lock", 105 * second, lastHeartbeatNs.get())
        assertTrue("the rest of the record still waits for the lock", reader.isAlive)
        consumer.release()
        reader.join(5_000)
        assertFalse(reader.isAlive)
    }

    @Test
    fun withoutTheSwitchTheStampWaitsBehindTheRecordLock() {
        val consumer = BlockedConsumer()
        clockNs.set(105 * second)
        val reader = thread { heard(stampBeforeLock = false) }
        Thread.sleep(300)
        assertEquals("today's behaviour: liveness is frozen while the lock is held", 0L, lastHeartbeatNs.get())
        consumer.release()
        reader.join(5_000)
        assertEquals(105 * second, lastHeartbeatNs.get())
    }

    @Test
    fun aRecordThatArrivedWhileTheConsumerWasBlockedCountsAsHeardWithTheSwitch() {
        // Heard at 100 s; the next record is read at 105 s while the consumer holds the lock for 5 s; the supervisor ticks at 109.5 s.
        lastHeartbeatNs.set(100 * second)
        val consumer = BlockedConsumer()
        clockNs.set(105 * second)
        val reader = thread { heard(stampBeforeLock = true) }
        Thread.sleep(200)
        clockNs.set(109_500_000_000L)
        assertEquals(
            "the record of 105 s is counted although its lock part still waits",
            CallStallTolerance.Verdict.OK,
            CallStallTolerance.verdict(false, clockNs.get(), lastHeartbeatNs.get(), clockNs.get()),
        )
        consumer.release()
        reader.join(5_000)
    }

    @Test
    fun theSameRecordIsNotCountedWithoutTheSwitchSoTheSilenceRuleFires() {
        lastHeartbeatNs.set(100 * second)
        val consumer = BlockedConsumer()
        clockNs.set(105 * second)
        val reader = thread { heard(stampBeforeLock = false) }
        Thread.sleep(200)
        clockNs.set(109_500_000_000L)
        assertEquals(
            CallStallTolerance.Verdict.END,
            CallStallTolerance.verdict(false, clockNs.get(), lastHeartbeatNs.get(), clockNs.get()),
        )
        consumer.release()
        reader.join(5_000)
    }

    @Test
    fun aRecordOfAReplacedLinkStampsNothingAndEndsTheReader() {
        generation.set(2)
        assertFalse(heard(stampBeforeLock = true, generationHeard = 1))
        assertEquals(0L, lastHeartbeatNs.get())
        var ran = false
        assertTrue(heard(stampBeforeLock = true, generationHeard = 2) { ran = true })
        assertTrue(ran)
    }
}
