package com.example.bluetoothtest

import java.util.PriorityQueue

/**
 * Virtual-time scheduler for tests: [post] and [postDelayed] queue, [drain] and [advance] run them. Thread-safe: worker
 * threads post back while the test thread drains. With [strictThreads] only a task this scheduler runs counts as the
 * wifi thread, so a test can call from "another thread" and exercise the post path and the X7 thread checks.
 */
internal class ManualScheduler : WifiScheduler {
    private class Task(val seq: Long, val tag: Any?, val dueMs: Long, val block: () -> Unit)

    private val lock = Any()
    private val tasks = PriorityQueue<Task>(compareBy<Task>({ it.dueMs }, { it.seq }))
    private var seq = 0L

    @Volatile
    var nowMs = 0L
        private set

    @Volatile
    var postCount = 0
        private set

    @Volatile
    var postDelayedCount = 0
        private set

    /** False (default): every caller is the wifi thread. True: only code inside a task run by [drain]/[advance]. */
    @Volatile
    var strictThreads = false

    private val inTask = ThreadLocal.withInitial { false }

    /** Tasks due now (posted, or delayed and already due), not yet run. */
    fun dueCount(): Int = synchronized(lock) { tasks.count { it.dueMs <= nowMs } }

    override fun post(block: () -> Unit) {
        synchronized(lock) {
            postCount++
            tasks += Task(seq++, null, nowMs, block)
        }
    }

    override fun postDelayed(tag: Any, delayMs: Long, block: () -> Unit) {
        synchronized(lock) {
            postDelayedCount++
            tasks += Task(seq++, tag, nowMs + maxOf(0L, delayMs), block)
        }
    }

    override fun cancel(tag: Any) {
        synchronized(lock) { tasks.removeIf { it.tag === tag } }
    }

    override fun nowMs(): Long = nowMs

    override fun isWifiThread(): Boolean = !strictThreads || inTask.get()

    private fun runTask(task: Task) {
        val was = inTask.get()
        inTask.set(true)
        try {
            task.block()
        } finally {
            inTask.set(was)
        }
    }

    /** Runs every task due at or before the current virtual time, including tasks posted meanwhile. */
    fun drain() {
        synchronized(lock) {
            val limit = nowMs
            while (true) {
                val next = tasks.peek() ?: break
                if (next.dueMs > limit) break
                tasks.poll()
                nowMs = maxOf(nowMs, next.dueMs)
                runTask(next)
            }
        }
    }

    /** Advances the virtual clock by [ms], running each due task in order. */
    fun advance(ms: Long) {
        synchronized(lock) {
            val target = nowMs + ms
            while (true) {
                val next = tasks.peek() ?: break
                if (next.dueMs > target) break
                tasks.poll()
                nowMs = maxOf(nowMs, next.dueMs)
                runTask(next)
            }
            nowMs = target
        }
    }

    /** Drains and sleeps briefly until [condition] holds or [timeoutRealMs] passes (worker threads post back). */
    fun runUntil(timeoutRealMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutRealMs
        while (System.currentTimeMillis() < end) {
            drain()
            if (condition()) return true
            Thread.sleep(2)
        }
        drain()
        return condition()
    }
}
