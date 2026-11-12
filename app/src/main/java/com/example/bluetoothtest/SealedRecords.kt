package com.example.bluetoothtest

import android.content.Context
import java.io.File
import java.util.concurrent.Delayed
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A small sealed memory of `key -> (value, time)` records that name peers this phone met, kept in [SealedTextFile] with
 * the verified-contacts Keystore key. Audit 2026-11-10 SEC-05: the learned per-peer link rates, the reported people, the
 * hidden stream routes, the muted chats and the missed-call tokens were plain preferences holding node ID prefixes or
 * fingerprints (with times), and nothing deleted the link rates; PRIVACY says these memories are encrypted at rest.
 *
 * Review of 040a8c2e (PRIV1), how the store behaves now:
 * - Every disk and Keystore operation (load, seal, write, migration, expiry) runs on the one [SealedRecordsWorker] thread;
 *   callers only touch memory, so a busy Keystore never blocks the screen (finding 1). One instance per file in the
 *   process ([SealedRecordsRegistry]), so two owners never race on the file (finding 8).
 * - A change is applied in memory at once and written by the worker; a failed write is retried with a growing delay
 *   until it lands ([isDurable], finding 5). A file that cannot be opened now is never written over; changes made
 *   meanwhile are replayed onto it in order once it opens, removals included.
 * - Retention: a cache ([maxEntries] set) keeps its newest records; a store of explicit choices ([maxEntries] null) never
 *   evicts one (finding 2). A record older than [maxAgeMs] is deleted from the file at load and by a timed sweep, not only
 *   ignored (finding 6).
 * - An earlier build's plain copy ([LegacyPlain]) is imported once (the file then carries a `#legacy` mark, so a later
 *   process never imports it again), never over a key this process removed (finding 7), and its deletion is retried until
 *   the disk is confirmed clean (findings 4 and 9).
 * - The emergency wipe drains the worker before it deletes keys and files ([SealedRecordsWorker.drainForWipe]); a task
 *   that starts after the wipe began does nothing (finding 3).
 *
 * Keys and values must not contain a tab or a line break (the text format is one `time TAB key TAB value` line each).
 */
internal class SealedRecords(
    private val file: SealedTextFile,
    private val maxEntries: Int?,
    private val maxAgeMs: Long? = null,
    private val legacy: LegacyPlain? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryBaseMs: Long = 1_000L,
    private val log: (String) -> Unit = { line -> runCatching { android.util.Log.w("BluetoothVideo", line) } },
    private val label: String = "records",
) {
    data class Record(val value: String, val atMs: Long)

    private sealed class Op {
        class Put(val key: String, val record: Record) : Op()
        class Remove(val keys: Collection<String>) : Op()
        class Retain(val keep: (String) -> Boolean) : Op()
    }

    private val records = LinkedHashMap<String, Record>()
    /** Changes made before the file was read, replayed onto it in order. */
    private val pending = ArrayList<Op>()
    /** Keys removed in this process: an earlier build's plain copy never brings them back. */
    private val tombstones = HashSet<String>()
    /** Retain filters applied before the plain copy was imported: it is filtered by them too (re-review N5). */
    private val importFilters = ArrayList<(String) -> Boolean>()
    /** Run once the file is read (re-review S3): readers that must not act on a partial memory wait for it. */
    private val onLoaded = ArrayList<() -> Unit>()
    private var loaded = false
    private var closed = false
    private var legacyImported = false
    private var migrationDone = legacy == null
    private var dirty = false
    private var generation = 0L
    private var writeScheduled = false
    private var writeFailures = 0
    private var loadFailures = 0
    private var migrationFailures = 0
    private var sweepAtMs = Long.MAX_VALUE

    /** Starts reading the file on the worker. */
    fun start() = SealedRecordsWorker.execute(::loadTask)

    @Synchronized fun isLoaded(): Boolean = loaded

    /**
     * Runs [action] once the file is read: at once on this thread when it already is, else on the worker right after the
     * load (a caller that needs another thread posts from there). Never runs after [close].
     */
    fun whenLoaded(action: () -> Unit) {
        val now = synchronized(this) {
            if (closed) return
            if (!loaded) {
                onLoaded += action
                return
            }
            true
        }
        if (now) action()
    }

    /** JVM tests: runs the load, the migration and a write now instead of at their retry time. */
    internal fun kickForTest() {
        SealedRecordsWorker.execute(::loadTask)
        SealedRecordsWorker.execute(::migrateTask)
        SealedRecordsWorker.execute(::writeTask)
    }

    /** True when every change made so far is on the disk (and an earlier build's plain copy is gone). */
    @Synchronized fun isDurable(): Boolean = loaded && !dirty && migrationDone

    @Synchronized
    fun get(key: String): Record? {
        val record = records[key] ?: return null
        if (fresh(record, clock())) return record
        apply(Op.Remove(listOf(key)))
        return null
    }

    @Synchronized
    fun keys(): Set<String> {
        val now = clock()
        return records.filterValues { fresh(it, now) }.keys.toSet()
    }

    @Synchronized
    fun all(): Map<String, Record> {
        val now = clock()
        return records.filterValues { fresh(it, now) }
    }

    /** Keeps [value] for [key]; false only when nothing is kept (a wipe started). The worker writes it. */
    @Synchronized
    fun put(key: String, value: String, atMs: Long = clock()): Boolean {
        if (WipeGuard.active || closed) return false
        require(valid(key) && valid(value)) { "A record cannot hold a tab or a line break" }
        apply(Op.Put(key, Record(value, atMs)))
        return true
    }

    @Synchronized
    fun remove(keys: Collection<String>): Boolean {
        if (WipeGuard.active || closed) return false
        if (keys.isNotEmpty()) apply(Op.Remove(keys.toList()))
        return true
    }

    /** Keeps only the records [keep] accepts. */
    @Synchronized
    fun retain(keep: (String) -> Boolean): Boolean {
        if (WipeGuard.active || closed) return false
        apply(Op.Retain(keep))
        return true
    }

    /** Emergency wipe: forgets everything in memory and stops writing (the wipe deletes the file and the key). */
    @Synchronized
    fun close() {
        closed = true
        records.clear()
        pending.clear()
        tombstones.clear()
        importFilters.clear()
        onLoaded.clear()
        dirty = false
    }

    @Synchronized
    internal fun encode(): String = buildString {
        append(HEADER)
        if (legacyImported) append('\n').append(LEGACY_MARK)
        records.forEach { (key, record) -> append('\n').append(record.atMs).append('\t').append(key).append('\t').append(record.value) }
    }

    private fun apply(op: Op) {
        if (!loaded) queue(op)
        if (op is Op.Retain) {
            tombstones += records.keys.filterNot(op.keep)
            if (!migrationDone && !legacyImported) importFilters += op.keep
        }
        applyTo(records, op)
        if (op is Op.Put) tombstones -= op.key
        if (op is Op.Remove) tombstones += op.keys
        if (loaded) {
            prune(clock())
            markDirty()
        }
    }

    /** Re-review N4: a later change of the same single key replaces the earlier one, so a store that cannot load stays small. */
    private fun queue(op: Op) {
        val key = when (op) {
            is Op.Put -> op.key
            is Op.Remove -> op.keys.singleOrNull()
            is Op.Retain -> null
        }
        if (key != null) {
            val last = pending.lastOrNull()
            val sameKey = when (last) {
                is Op.Put -> last.key == key
                is Op.Remove -> last.keys.singleOrNull() == key
                else -> false
            }
            if (sameKey) pending.removeAt(pending.size - 1)
        }
        pending += op
    }

    private fun applyTo(target: MutableMap<String, Record>, op: Op) {
        when (op) {
            is Op.Put -> {
                target.remove(op.key)
                target[op.key] = op.record
            }
            is Op.Remove -> op.keys.forEach { target.remove(it) }
            is Op.Retain -> target.keys.retainAll(op.keep)
        }
    }

    private fun markDirty() {
        generation++
        dirty = true
        scheduleWrite(0L)
    }

    private fun scheduleWrite(delayMs: Long) {
        if (writeScheduled || closed || !loaded) return
        writeScheduled = true
        SealedRecordsWorker.schedule(delayMs, ::writeTask)
    }

    private fun backoff(failures: Int): Long = (retryBaseMs shl (failures - 1).coerceIn(0, 6)).coerceAtMost(MAX_RETRY_MS)

    private fun loadTask() {
        if (synchronized(this) { closed || loaded }) return
        val text = try {
            file.read()
        } catch (unavailable: SealedTextUnavailable) {
            val failures = synchronized(this) { ++loadFailures }
            if (failures == 1) log("sealed_memory: event=load_unavailable | store=$label")
            SealedRecordsWorker.schedule(backoff(failures), ::loadTask)
            return
        } catch (other: Exception) {
            null // damaged or another key: empty, and the next write replaces it
        }
        val stored = LinkedHashMap<String, Record>()
        // A file without the header was written by 040a8c2e, which had already moved the plain copy (re-review N5).
        var storedLegacy = text != null && text.isNotEmpty() && !text.startsWith(HEADER)
        text?.lineSequence()?.forEach { line ->
            if (line == LEGACY_MARK) storedLegacy = true
            if (line.startsWith("#")) return@forEach
            val first = line.indexOf('\t')
            val second = if (first < 0) -1 else line.indexOf('\t', first + 1)
            if (first <= 0 || second <= first + 1) return@forEach
            val at = line.substring(0, first).toLongOrNull() ?: return@forEach
            stored[line.substring(first + 1, second)] = Record(line.substring(second + 1), at)
        }
        synchronized(this) {
            if (closed) return
            val replay = pending.toList()
            pending.clear()
            replay.forEach { applyTo(stored, it) }
            records.clear()
            records.putAll(stored)
            legacyImported = legacyImported || storedLegacy
            loaded = true
            val pruned = prune(clock())
            if (replay.isNotEmpty() || pruned) markDirty()
            scheduleSweep()
        }
        if (legacy != null) migrateTask()
        val waiting = synchronized(this) { onLoaded.toList().also { onLoaded.clear() } }
        waiting.forEach { runCatching(it) }
    }

    /** Imports the plain copy once, writes it sealed, then deletes the plain copy and confirms the disk is clean. */
    private fun migrateTask() {
        val source = legacy ?: return
        if (synchronized(this) { closed || migrationDone || !loaded }) return
        if (!synchronized(this) { legacyImported }) {
            val plain = runCatching { source.read() }.getOrElse {
                retryMigration()
                return
            }
            synchronized(this) {
                if (closed) return
                plain.forEach { (key, record) ->
                    if (valid(key) && valid(record.value) && key !in records && key !in tombstones &&
                        importFilters.all { it(key) }
                    ) {
                        records[key] = record
                    }
                }
                importFilters.clear()
                legacyImported = true
                prune(clock())
                // Nothing plain on a fresh install: no file is written (and no key made) for an empty memory.
                if (plain.isNotEmpty()) markDirty()
                scheduleSweep()
            }
        }
        if (!writeNow() && !source.cache) {
            retryMigration()
            return
        }
        if (!runCatching { source.clean() }.getOrDefault(false)) {
            retryMigration()
            return
        }
        synchronized(this) { migrationDone = true }
    }

    private fun retryMigration() {
        val failures = synchronized(this) { ++migrationFailures }
        if (failures == 1) log("sealed_memory: event=migration_retry | store=$label")
        SealedRecordsWorker.schedule(backoff(failures), ::migrateTask)
    }

    private fun writeTask() {
        synchronized(this) { writeScheduled = false }
        val ok = writeNow()
        synchronized(this) {
            if (!ok && dirty) {
                if (writeFailures == 1) log("sealed_memory: event=write_failed | store=$label | retry=true")
                scheduleWrite(backoff(writeFailures))
            } else if (dirty) {
                scheduleWrite(0L)
            }
            scheduleSweep()
        }
    }

    /** Writes the current records on the worker; true when the disk holds them (or nothing was to be written). */
    private fun writeNow(): Boolean {
        val (text, written) = synchronized(this) {
            if (closed || !loaded || WipeGuard.active) return false
            if (!dirty) return true
            prune(clock())
            encode() to generation
        }
        val ok = runCatching { file.write(text) }.isSuccess
        synchronized(this) {
            if (ok) {
                writeFailures = 0
                if (generation == written) dirty = false
            } else {
                writeFailures++
            }
        }
        return ok
    }

    /** A timed sweep at the next expiry (at most an hour away, so a changed clock cannot postpone it for long). */
    private fun scheduleSweep() {
        val age = maxAgeMs ?: return
        if (closed || records.isEmpty()) return
        val next = records.values.minOf { it.atMs } + age
        if (next >= sweepAtMs) return
        sweepAtMs = next
        SealedRecordsWorker.schedule((next - clock()).coerceIn(0L, MAX_SWEEP_DELAY_MS), ::sweepTask)
    }

    private fun sweepTask() {
        synchronized(this) {
            sweepAtMs = Long.MAX_VALUE
            if (closed || !loaded) return
            if (prune(clock())) markDirty()
            scheduleSweep()
        }
    }

    private fun fresh(record: Record, nowMs: Long): Boolean {
        val age = maxAgeMs ?: return true
        return nowMs - record.atMs in 0 until age
    }

    /** Drops expired records and, for a cache, the oldest beyond [maxEntries]; true when anything was dropped. */
    private fun prune(nowMs: Long): Boolean {
        val before = records.size
        if (maxAgeMs != null) records.entries.removeAll { !fresh(it.value, nowMs) }
        val limit = maxEntries
        if (limit != null && records.size > limit) {
            records.entries.sortedBy { it.value.atMs }.take(records.size - limit).map { it.key }.forEach(records::remove)
        }
        return records.size != before
    }

    private fun valid(text: String) = '\t' !in text && '\n' !in text && '\r' !in text

    companion object {
        private const val HEADER = "#sealed-records 1"
        private const val LEGACY_MARK = "#legacy"
        const val MAX_RETRY_MS = 60_000L
        const val MAX_SWEEP_DELAY_MS = 60L * 60 * 1000

        /** Production: the verified-contacts at-rest cipher; JVM tests install their own (as [RecentCalls.cipher]). */
        @Volatile internal var cipher: () -> AtRestCipher = { IdentityVerification.atRestCipher() }
    }
}

/** An earlier build's plain copy of a [SealedRecords] store. Called on the worker thread only. */
internal interface LegacyPlain {
    /** What is left in plain storage (empty when nothing). */
    fun read(): Map<String, SealedRecords.Record>

    /** Deletes the plain copy; true only when the disk is confirmed clean. */
    fun clean(): Boolean

    /** A cache (learned rates, one-time tokens): the plain copy is deleted even when the sealed write failed. */
    val cache: Boolean
}

/**
 * The one thread every [SealedRecords] disk and Keystore operation runs on. The emergency wipe drains it before deleting
 * keys and files: a running task finishes, and every task still queued or scheduled later does nothing.
 */
internal object SealedRecordsWorker {
    @Volatile private var executor = newExecutor()

    private fun newExecutor() = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "SealedRecords").apply { isDaemon = true }
    }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }

    fun execute(task: () -> Unit) = schedule(0L, task)

    fun schedule(delayMs: Long, task: () -> Unit) {
        try {
            executor.schedule(Runnable { if (!WipeGuard.active) runCatching(task) }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (stopped: RejectedExecutionException) {
            // Drained for a wipe: nothing more is written in this process.
        }
    }

    /** Emergency wipe (after [WipeGuard.begin]): stops the worker and waits for a running write; false on timeout. */
    fun drainForWipe(timeoutMs: Long): Boolean {
        val current = executor
        current.shutdown()
        return runCatching { current.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** A wipe resumed at launch completed and the app goes on in this process: a fresh worker for the empty memories. */
    @Synchronized
    fun reopenAfterWipe() {
        if (executor.isShutdown) executor = newExecutor()
    }

    /** JVM tests: waits until nothing runs and nothing is due within a second. */
    internal fun awaitIdleForTest(timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val due = executor.queue.any { (it as Delayed).getDelay(TimeUnit.MILLISECONDS) < 1_000L }
            if (!due && executor.activeCount == 0) return
            Thread.sleep(5)
        }
        error("SealedRecordsWorker still busy")
    }

    internal fun resetForTest() {
        executor.shutdownNow()
        executor = newExecutor()
    }
}

/** One [SealedRecords] per file in the process (finding 8), opened from any context. */
internal object SealedRecordsRegistry {
    private val open = HashMap<String, SealedRecords>()

    /**
     * The store of [file]. While [canOpen] is false (a wipe started or is pending, re-review N2) an inert store is returned
     * and nothing is registered, read or written; the next call after the wipe completed opens the real one.
     */
    @Synchronized
    fun open(
        file: File,
        maxEntries: Int?,
        maxAgeMs: Long?,
        legacy: () -> LegacyPlain?,
        canOpen: () -> Boolean = { !WipeGuard.active },
    ): SealedRecords {
        open[file.absolutePath]?.let { return it }
        val records = SealedRecords(SealedTextFile(file, { SealedRecords.cipher() }), maxEntries, maxAgeMs, legacy(), label = file.name)
        if (!canOpen()) return records.also { it.close() }
        open[file.absolutePath] = records
        records.start()
        return records
    }

    /** Emergency wipe: every store forgets its records and stops writing. */
    @Synchronized
    fun clearForWipe() {
        open.values.forEach { it.close() }
        open.clear()
    }

    internal fun resetForTest() = clearForWipe()

    @Synchronized
    internal fun openedForTest(file: File): SealedRecords? = open[file.absolutePath]
}

/** A [SealedRecords] file in no-backup storage, with its earlier build's plain copy. */
internal class SealedRecordsSlot(
    private val fileName: String,
    private val maxEntries: Int?,
    private val maxAgeMs: Long? = null,
    private val legacy: ((Context) -> LegacyPlain)? = null,
) {
    /** The file per application context, so a hot path does not ask the platform for the directory on every use. */
    @Volatile private var resolved: Pair<Context, File>? = null

    fun get(context: Context): SealedRecords {
        val app = context.applicationContext
        val file = resolved?.takeIf { it.first === app }?.second
            ?: File(app.noBackupFilesDir, fileName).also { resolved = app to it }
        return SealedRecordsRegistry.open(
            file, maxEntries, maxAgeMs, { legacy?.invoke(app) },
            canOpen = { !WipeGuard.active && !runCatching { EmergencyWipe.isPending(app) }.getOrDefault(true) },
        )
    }
}

/** Plain preference files of earlier builds: removal confirmed on the disk, not inferred from the in-memory cache. */
internal object PlainPreferences {
    /** The XML of [name] and Android's backup copy of it; empty when the data directory is unknown (JVM tests). */
    fun files(context: Context, name: String): List<File> = runCatching {
        val dir = File(context.dataDir, "shared_prefs")
        listOf(File(dir, "$name.xml"), File(dir, "$name.xml.bak"))
    }.getOrDefault(emptyList())

    private val NAME = Regex("""\bname="([^"]*)"""")

    /** True when no file still names a key [matches] accepts; an unreadable file is not clean. */
    fun clean(files: List<File>, matches: (String) -> Boolean): Boolean = files.all { file ->
        if (!file.exists()) return@all true
        val text = runCatching { file.readText() }.getOrNull() ?: return@all false
        NAME.findAll(text).none { matches(it.groupValues[1]) }
    }

    /**
     * Removes the keys [matches] accepts from the shared preferences file [name] and confirms the disk copy is clean. When
     * the cache already lacks them but the disk does not (an earlier write failed), forces a rewrite.
     */
    fun removeKeys(context: Context, name: String, matches: (String) -> Boolean): Boolean {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val cached = prefs.all.keys.filter(matches)
        if (cached.isNotEmpty() && !prefs.edit().apply { cached.forEach(::remove) }.commit()) return false
        val files = files(context, name)
        if (clean(files, matches)) return true
        if (!prefs.edit().putBoolean(REWRITE_MARK, true).commit() || !prefs.edit().remove(REWRITE_MARK).commit()) return false
        return clean(files, matches)
    }

    /** Deletes a preference file that held only peer data; true when neither copy is left on the disk. */
    fun deleteFile(context: Context, name: String): Boolean {
        val files = files(context, name)
        // Re-review N6: nothing on the disk (the usual case after the first start) is left alone, not recreated empty.
        if (files.isNotEmpty() && files.none { it.exists() }) return true
        runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
        runCatching { context.deleteSharedPreferences(name) }
        return files(context, name).none { it.exists() }
    }

    private const val REWRITE_MARK = "sealed_memory_rewrite"
}

/**
 * Opens every sealed peer memory at process start (finding 4), so an earlier build's plain copies are moved and deleted
 * even when the feature that reads them is never used, and the memories are loaded before a screen needs them. Nothing
 * runs while a wipe is pending (the wipe resumes first).
 */
internal object PeerMemoryStartup {
    fun run(context: Context) {
        if (isolatedProcess()) return // re-review N1: an isolated service process has no access to the app's files
        if (WipeGuard.active || runCatching { EmergencyWipe.isPending(context) }.getOrDefault(true)) return
        runCatching { LinkCapacity.openPeerRecords(context) }
        runCatching { LocalReporting.open(context) }
        runCatching { ChatMute.open(context) }
        runCatching { MissedCallNotification.open(context) }
    }

    /** True in an `isolatedProcess` service (MessagingV2ImageService), where Application.onCreate also runs. */
    fun isolatedProcess(): Boolean = runCatching { android.os.Process.isIsolated() }.getOrDefault(false)
}
