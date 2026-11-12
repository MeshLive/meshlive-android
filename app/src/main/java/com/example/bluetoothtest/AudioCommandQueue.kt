package com.example.bluetoothtest

import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Serialized, timed audio commands (batch U-05, the M31 audio-ANR audit).
 *
 * Audit result (2026-10-14, static, no phone): `MicrophoneOpusEncoder.start()/close()` and
 * `PrivateCallAudioSession` (setMode, setCommunicationDevice, requestAudioFocus, clearCommunicationDevice) run on
 * the caller's thread, which is the UI thread in `MainActivity.startPrivateCallMedia` / `stopPrivateCallEndpoint`;
 * `close()` also joins two worker threads for up to 1 s each. `OpusAudioDecoder` creates its AudioTrack on the
 * thread that delivers the first audio packet (a link thread, not the UI thread) and plays on its own worker, but
 * its `close()` also runs on the stopping thread. These are the calls Google tells apps to keep off the UI
 * thread (Oboe #1216 / #1474: AudioRecord/AudioTrack open and stop stall in an ioctl on Exynos 9611 and others).
 *
 * Behaviour: every command is timed and logged as `BENCHMARK audio_command` (always on, one short line per
 * command, with the thread kind), and a watchdog logs a command still running after [watchdogMs]. Moving the
 * work off the caller's thread is the Experimental switch [AudioSwitches.offMainThread] (default off): then
 * [offload] queues the command on one background thread when called from the main thread.
 * All commands, queued or inline, take one lock, so a queued teardown and a following start never overlap.
 */
internal class AudioCommandQueue(
    private val watchdogMs: Long = WATCHDOG_MS,
    private val slowMs: Long = SLOW_MS,
    private val isMainThread: () -> Boolean = ::currentThreadIsMain,
    private val offloadEnabled: () -> Boolean = { AudioSwitches.offMainThread },
    private val sink: (String) -> Unit = { line -> Log.i("BluetoothVideo", line) },
) {
    private val lock = ReentrantLock()
    private val sequence = AtomicLong()
    private val worker: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(
        daemonThreads("AudioCommands"),
    )
    private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        daemonThreads("AudioCommandWatchdog"),
    )

    /** Runs [block] now on the calling thread, serialized with queued commands, timed and watched. */
    fun <T> run(name: String, block: () -> T): T {
        val where = if (isMainThread()) "main" else "other"
        return timed(name, where, "inline", block)
    }

    /** Queues [block] on the audio command thread (serialized with every other command). */
    fun post(name: String, block: () -> Unit) {
        val requestedFrom = if (isMainThread()) "main" else "other"
        worker.execute {
            runCatching { timed(name, requestedFrom, "queued", block) }
                .onFailure { sink("BENCHMARK audio_command: name=$name | event=failed | error=${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * [post] when the experimental off-main switch is on and the caller is the main thread; otherwise [run].
     * For commands whose caller needs no result (teardown, restore).
     */
    fun offload(name: String, block: () -> Unit) {
        if (offloadEnabled() && isMainThread()) post(name, block) else run(name, block)
    }

    /**
     * [block] with its result delivered to [onResult]: queued (and [onResult] runs on the audio command thread)
     * when the off-main switch is on and the caller is the main thread; otherwise run inline and [onResult] runs
     * on the caller's thread before this returns.
     */
    fun <T> offloadForResult(name: String, block: () -> T, onResult: (Result<T>) -> Unit) {
        if (offloadEnabled() && isMainThread()) {
            post(name) { onResult(runCatching(block)) }
        } else {
            onResult(runCatching { run(name, block) })
        }
    }

    /**
     * [block] with its result delivered to [onResult] on the audio command thread, always queued (never inline, whatever the
     * off-main switch says), behind every command already queued. JT2 camera-first: the microphone start must follow the call's
     * queued `call_audio_open`, so the order of the two is the queue's, not the callers'.
     */
    fun <T> postForResult(name: String, block: () -> T, onResult: (Result<T>) -> Unit) {
        post(name) { onResult(runCatching(block)) }
    }

    fun shutdown() {
        worker.shutdown()
        watchdog.shutdownNow()
    }

    private fun <T> timed(name: String, requestedFrom: String, mode: String, block: () -> T): T {
        val id = sequence.incrementAndGet()
        val startNs = System.nanoTime()
        val stalled = watchdog.schedule(
            { sink("BENCHMARK audio_command: name=$name | id=$id | event=stalled | after_ms=$watchdogMs | from=$requestedFrom | mode=$mode") },
            watchdogMs,
            TimeUnit.MILLISECONDS,
        )
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
            stalled.cancel(false)
            val ms = (System.nanoTime() - startNs) / 1_000_000L
            if (ms >= slowMs || requestedFrom == "main" && mode == "inline") {
                sink("BENCHMARK audio_command: name=$name | id=$id | event=done | ms=$ms | from=$requestedFrom | mode=$mode | slow=${ms >= slowMs}")
            }
        }
    }

    companion object {
        const val WATCHDOG_MS = 3_000L
        const val SLOW_MS = 100L

        /** The process-wide queue the audio classes share. */
        val shared: AudioCommandQueue by lazy { AudioCommandQueue() }

        private fun daemonThreads(name: String) = ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

        private fun currentThreadIsMain(): Boolean =
            runCatching { Looper.getMainLooper().thread === Thread.currentThread() }.getOrDefault(false)
    }
}
