package com.example.bluetoothtest

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/*
 * Emergency wipe (PROTOCOL "Emergency wipe").
 *
 * API contract for the UI (no screen lives here; no intent reaches these in release builds):
 *
 *   EmergencyWipe.panic(context): WipeReport
 *       Confirmation-free entry point for an in-app panic gesture (for example a triple-tap).
 *   EmergencyWipe.wipeConfirmed(context, confirmationText: String): WipeReport
 *       The confirmed variant: runs only when confirmationText == EmergencyWipe.CONFIRMATION_TEXT
 *       ("DELETE"); otherwise throws IllegalArgumentException and touches nothing.
 *   EmergencyWipe.wipe(context, reason: WipeReason, exitProcess: Boolean = true): WipeReport
 *       The shared synchronous implementation; safe to call from the main thread.
 *   EmergencyWipe.isPending(context) / resumePendingWipe(context): WipeReport
 *       An interrupted or incomplete wipe leaves a persistent marker. MainActivity resumes it on
 *       the next launch before any transport starts, and exits again if it still cannot finish.
 *   EmergencyWipe.registerSessionTerminator(terminator) / unregisterSessionTerminator(terminator)
 *       Owners of calls, streams and relays (MainActivity) register a terminator that ends them.
 *
 * Sequence (single flight; a concurrent or re-entrant call returns alreadyRunning):
 *   1. stop admission: WipeGuard refuses new end-to-end sessions and new Keystore keys for the
 *      rest of this process (identity, store-and-forward, verification);
 *   2. write the persistent "wipe pending" marker;
 *   3. delete every Android Keystore alias of this app, which cryptographically erases the sealed
 *      store-and-forward data and verified contacts before anything slower runs;
 *   4. end every call, stream and relay through the registered terminators on the main thread,
 *      waiting at most 2.5 s (the wipe itself runs on a worker thread, so a stuck terminator
 *      cannot hold it);
 *   5. wait for the background writers of sealed memories (SealedRecordsWorker and every
 *      SealedTextFile write) with what is left of WRITER_BUDGET_MS, so nothing they seal or write
 *      lands after the deletion below; a writer still running then leaves the wipe incomplete
 *      (PRIV1 review finding 3; the key deletion of step 3 is not delayed by it, re-review S1);
 *   6. clear and delete every SharedPreferences file and the app data, external files and caches;
 *   7. drop in-memory caches, then sweep the Keystore, preferences and files a second time for
 *      late writers; the report is complete only if that final sweep removed everything;
 *   8. cancel every notification of the app (call alerts with caller labels, session status),
 *      remove the marker only when complete, log one line and end the process.
 *
 * A wipe that still cannot complete at launch opens WipeIncompleteActivity (retry, App info)
 * instead of the app, so the user is never left with a silently closing app.
 */
enum class WipeReason { PANIC, USER_CONFIRMED, DEBUG_CONTROL, RESUMED_AFTER_INTERRUPTION }

/** Ends every call, stream and relay its owner runs. Called on the main thread. */
fun interface WipeSessionTerminator {
    fun endAllForWipe()
}

data class TerminatorOutcome(val run: Int, val failures: Int, val timedOut: Boolean)

data class WipeReport(
    val reason: WipeReason,
    val alreadyRunning: Boolean,
    val terminatorsRun: Int,
    val terminatorFailures: Int,
    val terminatorTimedOut: Boolean,
    val keystoreAliasesDeleted: Int,
    /** Failures of the final Keystore sweep (an alias that is still present or could not be listed). */
    val keystoreFailures: Int,
    val preferencesCleared: Int,
    val filesDeleted: Int,
    /** Files the final sweep could not remove. */
    val fileFailures: Int,
    /** True only when the final sweep left no key or file and the pending marker was removed. */
    val complete: Boolean,
    val durationMs: Long,
)

/**
 * Process-wide admission stop. Once a wipe starts, no new end-to-end session is admitted and no
 * Keystore key is created in this process; the process ends after the wipe. A wipe resumed at
 * launch (before any transport) reopens it only when the wipe completed.
 */
object WipeGuard {
    @Volatile var active: Boolean = false
        private set

    /** Held (shared) by every sealed write from its guard check to its file write; the wipe waits for it (PRIV1 S4). */
    private val writers = java.util.concurrent.locks.ReentrantReadWriteLock()

    fun check(what: String) {
        if (active) throw IllegalStateException("Emergency wipe: $what refused")
    }

    /**
     * Runs [write] (a seal and a file write) after [check], so that a wipe that has begun waits for it to finish
     * ([awaitWriters]) instead of deleting the files while it is half done.
     */
    fun <T> guardedWrite(what: String, write: () -> T): T {
        writers.readLock().lock()
        try {
            check(what)
            return write()
        } finally {
            writers.readLock().unlock()
        }
    }

    /** After [begin]: true once no guarded write is running (new ones refuse), false at [timeoutMs]. */
    internal fun awaitWriters(timeoutMs: Long): Boolean {
        val lock = writers.writeLock()
        if (!runCatching { lock.tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)) return false
        lock.unlock()
        return true
    }

    internal fun begin() {
        active = true
    }

    internal fun reopenAfterCompletedResume() {
        active = false
        // The resumed wipe drained the sealed-memory worker; this process goes on with an empty phone.
        SealedRecordsWorker.reopenAfterWipe()
    }

    internal fun resetForTest() {
        active = false
    }
}

/** Everything the wipe touches, separated from Context so the engine is unit-testable. */
interface WipeEnvironment {
    fun writePendingMarker()
    fun clearPendingMarker()
    /** Files the deletion steps must leave (the pending marker). */
    fun preservedFiles(): Set<File>
    fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long): TerminatorOutcome
    fun keystoreAliases(): Collection<String>
    fun deleteKeystoreAlias(alias: String)
    fun preferenceNames(): Collection<String>
    /** Clears the in-memory instance, commits the empty file and deletes it. */
    fun clearPreferences(name: String)
    /** Directories whose contents are deleted (the directories themselves stay). */
    fun storageRoots(): List<File>
    fun resetInMemoryState()
    /** Cancels every notification the app posted (call alerts carry the caller's label). */
    fun cancelNotifications()
    /**
     * Stops the background writers of sealed memories and waits for a write already running (PRIV1 review finding 3), so
     * nothing they seal or write lands after the deletion steps. False when one is still running at [timeoutMs].
     */
    fun drainBackgroundWriters(timeoutMs: Long): Boolean = true
    fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

object EmergencyWipe {
    const val CONFIRMATION_TEXT = "DELETE"
    const val TERMINATOR_WAIT_MS = 2_500L
    /**
     * The time from the start of a wipe by which the sealed-memory writers must have stopped (step 5); the terminators'
     * time counts against it, and at least [WRITER_DRAIN_MIN_MS] is always given. One running write is a Keystore seal and
     * a small file write.
     */
    const val WRITER_BUDGET_MS = 3_500L
    const val WRITER_DRAIN_MIN_MS = 250L

    /** In noBackupFilesDir; present from the start of a wipe until it completes. */
    const val PENDING_MARKER = "emergency-wipe-pending"

    /**
     * DEBUG only: `adb shell run-as <package> sh -c 'echo <token> > files/debug-emergency-wipe-token'`
     * then launch with `--es test_emergency_wipe <token>`. Internal storage is writable only by the
     * app and adb run-as on a debuggable build; external app storage is not used because on
     * Android 10 legacy storage another app with the storage permission could plant a token.
     */
    const val DEBUG_TOKEN_FILE = "debug-emergency-wipe-token"
    private val DEBUG_TOKEN = Regex("[0-9a-f]{32,128}")

    /** Every alias the app creates; also deleted by name in case enumeration fails. */
    val KNOWN_KEYSTORE_ALIASES: List<String> = listOf(
        "bluetooth-video-mesh-identity-v1",
        "bluetooth-video-mesh-mailbox-v1",
        "bluetooth-video-mesh-store-forward-v1",
        IdentityVerification.AT_REST_ALIAS,
    )

    /** Every SharedPreferences file the app opens; also cleared by name in case the listing fails. */
    val KNOWN_PREFERENCES: List<String> = listOf(
        "adaptive_phy_memory",
        "bluetooth_video_settings",
        // UI redesign: onboarding state (OnboardingActivity), so a wiped phone starts at onboarding.
        "bluetooth_video_ui",
        // Home "Recent" call history (RecentCalls).
        "recent_calls",
        "broadcast_group_security",
        "call_encoder_fit",
        "encoder_burst_profiles",
        // Experimental test_sw_shortfall_avc (SoftwareShortfallStore): encoder names and sizes only.
        "sw_encoder_shortfall",
        "link_capacity",
        "native_encoder_speed",
        NotificationPrivacy.PREFS, // Lock-screen caller-name opt-in returns to off.
        // Experimental rotating discovery IDs: epoch clock, and the saved original Bluetooth name
        // (after a wipe the neutral name stays; no record of the user's own name remains).
        "rotating_discovery",
        "rotating_discovery_name",
        // Crash reports: the "Don't ask again" choice (the reports themselves live in filesDir and go with it).
        "crash_reports",
        // Missed-call notification: earlier builds' plain Call back tokens (now sealed in no_backup, SEC-05).
        "missed_call",
        // Chats: earlier builds' plain mute list (now sealed in no_backup, SEC-05).
        "chat_mute",
        // Experimental persisted Android 10 CoC budget (CocBudget): last listen PSM, count, boot count.
        "coc_budget",
    )

    /** dataDir children that are not app data: native library link and ART's code cache. */
    private val PRESERVED_DATA_CHILDREN = setOf("lib", "code_cache")

    private val terminators = CopyOnWriteArrayList<WipeSessionTerminator>()
    private val running = AtomicBoolean(false)

    fun registerSessionTerminator(terminator: WipeSessionTerminator) {
        terminators.addIfAbsent(terminator)
    }

    fun unregisterSessionTerminator(terminator: WipeSessionTerminator) {
        terminators.remove(terminator)
    }

    fun panic(context: Context): WipeReport = wipe(context, WipeReason.PANIC)

    fun wipeConfirmed(context: Context, confirmationText: String): WipeReport {
        require(confirmationText == CONFIRMATION_TEXT) { "Wipe not confirmed" }
        return wipe(context, WipeReason.USER_CONFIRMED)
    }

    fun isPending(context: Context): Boolean = File(context.noBackupFilesDir, PENDING_MARKER).exists()

    /** Completes an interrupted wipe without exiting; the caller must exit if it is still incomplete. */
    fun resumePendingWipe(context: Context): WipeReport =
        wipe(context, WipeReason.RESUMED_AFTER_INTERRUPTION, exitProcess = false).also {
            if (it.complete) WipeGuard.reopenAfterCompletedResume()
        }

    /** The wipe-incomplete screen's explanation for [report]; no identifiers. */
    fun incompleteMessage(report: WipeReport): String = buildString {
        append("The emergency wipe could not finish. ")
        val left = listOfNotNull(
            "${report.keystoreFailures} key problem(s)".takeIf { report.keystoreFailures > 0 },
            "${report.fileFailures} file(s) left".takeIf { report.fileFailures > 0 },
        )
        if (left.isNotEmpty()) append("Remaining: ${left.joinToString(", ")}. ")
        append("Nothing has been started. Tap Retry, or open App info and clear this app's storage to finish.")
    }

    /** DEBUG builds only (the caller checks BuildConfig.DEBUG): true once for the adb-written token. */
    fun consumeDebugWipeToken(context: Context, presented: String?): Boolean {
        if (presented == null) return false
        val file = File(context.filesDir, DEBUG_TOKEN_FILE)
        val matches = debugTokenMatches(runCatching { file.readText() }.getOrNull(), presented)
        if (matches) file.delete()
        return matches
    }

    internal fun debugTokenMatches(stored: String?, presented: String?): Boolean {
        val expected = stored?.trim() ?: return false
        val offered = presented?.trim() ?: return false
        if (!DEBUG_TOKEN.matches(expected)) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), offered.toByteArray(Charsets.US_ASCII))
    }

    fun wipe(context: Context, reason: WipeReason, exitProcess: Boolean = true): WipeReport {
        val app = context.applicationContext
        val mainLooper = Looper.getMainLooper()
        val onMain = Looper.myLooper() == mainLooper
        // On the main thread the caller waits here and runs the terminator task when the worker
        // hands it over; the worker never waits for it longer than TERMINATOR_WAIT_MS.
        val mainTasks = LinkedBlockingQueue<Runnable>()
        val executor: (Runnable) -> Unit = if (onMain) {
            { task -> mainTasks.put(task) }
        } else {
            { task -> Handler(mainLooper).postAtFrontOfQueue(task) }
        }
        val result = AtomicReference<WipeReport?>()
        val done = CountDownLatch(1)
        thread(name = "EmergencyWipe") {
            try {
                val report = run(AndroidWipeEnvironment(app, executor), reason)
                result.set(report)
                runCatching {
                    Log.w(
                        "BluetoothVideo",
                        "EMERGENCY_WIPE reason=$reason | complete=${report.complete} | " +
                            "alreadyRunning=${report.alreadyRunning} | durationMs=${report.durationMs}",
                    )
                }
                if (exitProcess && !report.alreadyRunning) android.os.Process.killProcess(android.os.Process.myPid())
            } finally {
                done.countDown()
            }
        }
        if (onMain) {
            while (done.count > 0L) mainTasks.poll(20, TimeUnit.MILLISECONDS)?.let { task -> runCatching { task.run() } }
        } else {
            done.await()
        }
        return result.get() ?: WipeReport(reason, false, 0, 0, false, 0, 1, 0, 0, 1, false, 0)
    }

    /** The engine: synchronous, single flight, never throws, continues past individual failures. */
    internal fun run(
        environment: WipeEnvironment,
        reason: WipeReason,
        terminatorTimeoutMs: Long = TERMINATOR_WAIT_MS,
    ): WipeReport {
        if (!running.compareAndSet(false, true)) {
            return WipeReport(reason, true, 0, 0, false, 0, 0, 0, 0, 0, false, 0)
        }
        try {
            WipeGuard.begin()
            val started = environment.nowMs()
            runCatching { environment.writePendingMarker() }
            val firstKeys = deleteKeys(environment)
            val sessions = runCatching { environment.runTerminators(terminators.toList(), terminatorTimeoutMs) }
                .getOrElse { TerminatorOutcome(0, 1, false) }
            runCatching { environment.cancelNotifications() }
            // Before any file is deleted: a write that passed the guard before begin() finishes first, and every later one
            // does nothing; a key it made after step 3 goes in the final sweep. Not drained in time: the wipe stays
            // incomplete and resumes at the next launch.
            val drainMs = (WRITER_BUDGET_MS - (environment.nowMs() - started)).coerceAtLeast(WRITER_DRAIN_MIN_MS)
            val drained = runCatching { environment.drainBackgroundWriters(drainMs) }.getOrDefault(false)
            val firstData = deletePreferencesAndFiles(environment)
            runCatching { environment.resetInMemoryState() }
            // Late writers (threads ending in step 4) may have recreated keys or files: sweep again.
            val finalKeys = deleteKeys(environment)
            val finalData = deletePreferencesAndFiles(environment)
            // A session that ended late may have posted again; nothing identifying stays on screen.
            runCatching { environment.cancelNotifications() }
            val swept = finalKeys.second == 0 && finalData.third == 0
            val complete = swept && drained && runCatching { environment.clearPendingMarker() }.isSuccess
            return WipeReport(
                reason = reason,
                alreadyRunning = false,
                terminatorsRun = sessions.run,
                terminatorFailures = sessions.failures,
                terminatorTimedOut = sessions.timedOut,
                keystoreAliasesDeleted = firstKeys.first + finalKeys.first,
                keystoreFailures = finalKeys.second,
                preferencesCleared = firstData.first + finalData.first,
                filesDeleted = firstData.second + finalData.second,
                fileFailures = finalData.third,
                complete = complete,
                durationMs = environment.nowMs() - started,
            )
        } finally {
            running.set(false)
        }
    }

    /** Returns (aliases deleted, failures); a failure is an alias still present or an unlistable store. */
    private fun deleteKeys(environment: WipeEnvironment): Pair<Int, Int> {
        var deleted = 0
        var failures = 0
        val listed = runCatching { environment.keystoreAliases().toSet() }.onFailure { failures++ }.getOrNull()
        val aliases = LinkedHashSet<String>(KNOWN_KEYSTORE_ALIASES).apply { listed?.let(::addAll) }
        aliases.forEach { alias ->
            val removed = runCatching { environment.deleteKeystoreAlias(alias) }.isSuccess
            if (removed && (listed == null || alias in listed)) deleted++
        }
        runCatching { failures += environment.keystoreAliases().size }.onFailure { failures++ }
        return deleted to failures
    }

    /** Returns (preferences cleared, files deleted, files left). */
    private fun deletePreferencesAndFiles(environment: WipeEnvironment): Triple<Int, Int, Int> {
        var preferences = 0
        var deleted = 0
        var failed = 0
        val names = LinkedHashSet<String>(KNOWN_PREFERENCES)
        runCatching { names += environment.preferenceNames() }
        names.forEach { name -> if (runCatching { environment.clearPreferences(name) }.isSuccess) preferences++ }
        val preserved = runCatching { environment.preservedFiles() }.getOrDefault(emptySet()).map { it.absolutePath }.toSet()
        runCatching { environment.storageRoots() }.getOrDefault(emptyList()).forEach { root ->
            root.listFiles()?.forEach { child ->
                if (child.absolutePath !in preserved) {
                    val (d, f) = deleteTree(child)
                    deleted += d
                    failed += f
                }
            }
        }
        return Triple(preferences, deleted, failed)
    }

    /**
     * Runs [terminators] through [executor] (the main thread) and waits at most [timeoutMs]. A
     * stuck terminator is reported as a timeout; the caller continues without it.
     */
    internal fun runTerminatorsBounded(
        terminators: List<WipeSessionTerminator>,
        executor: (Runnable) -> Unit,
        timeoutMs: Long,
    ): TerminatorOutcome {
        if (terminators.isEmpty()) return TerminatorOutcome(0, 0, false)
        val failures = AtomicInteger()
        val finished = CountDownLatch(1)
        executor(
            Runnable {
                try {
                    terminators.forEach { runCatching { it.endAllForWipe() }.onFailure { failures.incrementAndGet() } }
                } finally {
                    finished.countDown()
                }
            },
        )
        val inTime = finished.await(timeoutMs, TimeUnit.MILLISECONDS)
        return TerminatorOutcome(terminators.size, failures.get() + if (inTime) 0 else 1, !inTime)
    }

    /** Deletes [file] and everything below it without following symbolic links out of the tree. */
    internal fun deleteTree(file: File): Pair<Int, Int> {
        var deleted = 0
        var failed = 0
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (file.isDirectory && !isLink) {
            file.listFiles()?.forEach { child ->
                val (d, f) = deleteTree(child)
                deleted += d
                failed += f
            }
        }
        if (file.delete() || !file.exists()) deleted++ else failed++
        return deleted to failed
    }

    internal fun storageRootsFor(dataDir: File?, extraRoots: List<File?>): List<File> {
        val roots = ArrayList<File>()
        dataDir?.listFiles()?.forEach { child ->
            if (child.name !in PRESERVED_DATA_CHILDREN && child.isDirectory) roots += child
        }
        extraRoots.filterNotNull().forEach { roots += it }
        return roots.distinctBy { it.absolutePath }
    }

    private class AndroidWipeEnvironment(
        private val context: Context,
        private val mainExecutor: (Runnable) -> Unit,
    ) : WipeEnvironment {
        private val marker = File(context.noBackupFilesDir, PENDING_MARKER)

        override fun writePendingMarker() {
            marker.parentFile?.mkdirs()
            FileOutputStream(marker).use { output ->
                output.write('1'.code)
                output.fd.sync()
            }
        }

        override fun clearPendingMarker() {
            check(!marker.exists() || marker.delete()) { "Wipe marker could not be removed" }
        }

        override fun preservedFiles(): Set<File> = setOf(marker)

        override fun runTerminators(terminators: List<WipeSessionTerminator>, timeoutMs: Long): TerminatorOutcome =
            runTerminatorsBounded(terminators, mainExecutor, timeoutMs)

        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        override fun keystoreAliases(): Collection<String> = keyStore().aliases().toList()

        override fun deleteKeystoreAlias(alias: String) {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }

        override fun preferenceNames(): Collection<String> =
            File(context.dataDir, "shared_prefs").listFiles()
                ?.filter { it.name.endsWith(".xml") }
                ?.map { it.name.removeSuffix(".xml") }
                .orEmpty()

        override fun clearPreferences(name: String) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(name)
        }

        override fun storageRoots(): List<File> = storageRootsFor(
            context.dataDir,
            context.getExternalFilesDirs(null).toList() + context.externalCacheDirs.toList(),
        )

        override fun cancelNotifications() {
            context.getSystemService(android.app.NotificationManager::class.java)?.cancelAll()
        }

        override fun drainBackgroundWriters(timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() / 1_000_000L + timeoutMs
            val worker = SealedRecordsWorker.drainForWipe(timeoutMs)
            return WipeGuard.awaitWriters((deadline - System.nanoTime() / 1_000_000L).coerceAtLeast(0L)) && worker
        }

        override fun resetInMemoryState() {
            IdentityVerification.clearForWipe()
            BlockedPeople.clearForWipe()
            PersonPolicy.clearForWipe()
            Bmh3Android.clearForWipe()
            ContactClaims.clearForWipe()
            StoreForwardRuntime.clearForWipe()
            RotatingDiscovery.clearForWipe()
            // Audit 2026-11-10 SEC-05: the sealed peer memories held in memory (their files go with no_backup).
            SealedRecordsRegistry.clearForWipe()
        }
    }
}
