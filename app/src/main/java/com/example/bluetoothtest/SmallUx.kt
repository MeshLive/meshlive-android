package com.example.bluetoothtest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

/** The rename dialog's rules (UX fix 2026-10-21): an empty name explains itself instead of a silently dead Save. */
internal object ContactNameRules {
    enum class SaveAction { SAVE, SAVE_BLANK, SHOW_EMPTY_ERROR, NONE }

    /** Save stays enabled for a blank name even where blank is not allowed: pressing it shows why. */
    fun buttonEnabled(check: ContactNameCheck, allowBlank: Boolean): Boolean = check !is ContactNameCheck.Invalid

    fun onSave(check: ContactNameCheck, allowBlank: Boolean): SaveAction = when (check) {
        is ContactNameCheck.Valid -> SaveAction.SAVE
        ContactNameCheck.Blank -> if (allowBlank) SaveAction.SAVE_BLANK else SaveAction.SHOW_EMPTY_ERROR
        is ContactNameCheck.Invalid -> SaveAction.NONE
    }
}

/** The call-ended text (UX fix 2026-10-21): a BLE connect failure says "Couldn't reach <name>"; the detail stays in the log. */
internal object CallEndText {
    fun isConnectFailure(message: String): Boolean = message.startsWith("Call could not connect")

    /** The plain line for each end reason; the technical reason stays in the log ("Private call ended: ..."). */
    enum class Kind(@androidx.annotation.StringRes val res: Int) {
        ENDED(R.string.call_end_ended),
        DROPPED(R.string.call_end_dropped),
        OTHER_PHONE(R.string.call_end_other_phone),
        INCOMING_FAILED(R.string.call_end_incoming_failed),
        MESSAGES_FAILED(R.string.call_end_messages_failed),
        CONNECT_FAILED(R.string.call_end_connect_failed),
        DECLINED(R.string.call_end_declined),
        BUSY(R.string.call_end_busy),
        CANCELLED(R.string.call_end_cancelled),
        NO_ANSWER(R.string.call_end_no_answer),
        MISSED(R.string.call_end_missed),
        CALLER_CANCELLED(R.string.call_end_caller_cancelled),
    }

    /**
     * Copy audit 2026-10-21, then the user-perspective audit: every end reason maps to a plain line, and any reason this
     * list does not know (exception text, a new internal message) reads "Call ended", never the raw text.
     */
    fun kind(message: String): Kind = when {
        message.startsWith("Call link lost") || message.startsWith("Call send failed") -> Kind.DROPPED
        message.startsWith("Peer's phone ended the call") -> Kind.OTHER_PHONE
        message.startsWith("Incoming call failed") -> Kind.INCOMING_FAILED
        message.startsWith("Message exchange failed") -> Kind.MESSAGES_FAILED
        isConnectFailure(message) -> Kind.CONNECT_FAILED
        message == "Call declined" -> Kind.DECLINED
        message == BUSY_DECLINE_MESSAGE -> Kind.BUSY
        message == "Call cancelled" -> Kind.CANCELLED
        message == CallRingTimeout.NO_ANSWER_MESSAGE -> Kind.NO_ANSWER
        message == CallRingTimeout.MISSED_MESSAGE -> Kind.MISSED
        message == CallRingTimeout.CALLER_CANCELLED_MESSAGE -> Kind.CALLER_CANCELLED
        else -> Kind.ENDED
    }

    fun forUser(context: Context, message: String): String = context.getString(kind(message).res)

    /**
     * User feedback 2026-10-21: after a call only an outcome worth knowing is said (a snackbar); an ordinary end, a cancel
     * and a missed call (it has its own notification) say nothing, and a connect failure has its own "Couldn't reach".
     */
    fun worthTelling(kind: Kind): Boolean = when (kind) {
        Kind.ENDED, Kind.CANCELLED, Kind.CALLER_CANCELLED, Kind.MISSED, Kind.CONNECT_FAILED -> false
        else -> true
    }
}

/** Calls page quiet rules (user feedback 2026-10-21). */
internal object CallsQuiet {
    /** The empty state explains only after this long without anyone found; before, the indicator says "searching". */
    const val EMPTY_HINT_AFTER_MS = 8_000L

    fun emptyHintVisible(searchingForMs: Long): Boolean = searchingForMs >= EMPTY_HINT_AFTER_MS

    /**
     * `test_calls_indicator_settles` (promoted on mechanism 2026-11-12, default on; `false` is the DEBUG control arm): the
     * searching indicator spins only for the first [INDICATOR_FOR_MS] of a search, the way Android's Bluetooth settings stops
     * its indicator when a discovery round ends; discovery keeps running and the list keeps updating. An indeterminate
     * indicator redraws the window every frame: a phone left on Calls > Nearby drew 60 frames a second for as long as it
     * waited (B18, 10 s windows after 12 s on Nearby: M31 608 -> 2 frames, A15s 607 -> 2).
     */
    const val INDICATOR_FOR_MS = 12_000L

    /** Whether the searching indicator shows: on Nearby while discovery runs, and with [settles] only early in the search. */
    fun indicatorVisible(nearby: Boolean, discovering: Boolean, searchingForMs: Long, settles: Boolean): Boolean =
        nearby && discovering && (!settles || searchingForMs < INDICATOR_FOR_MS)

    /** The status line: an actionable state or nothing. */
    fun line(actionableNotice: String?): String = actionableNotice?.takeIf { it.isNotBlank() }.orEmpty()
}

/**
 * "Missed call from <name>" with a Call back action (UX fix 2026-10-21) for an incoming call that was never answered: the
 * caller cancelled or the ring timed out. The lock screen shows only "Missed call" unless names are allowed there
 * ([NotificationPrivacy]); nothing is posted without POST_NOTIFICATIONS. The Call back action carries a one-time token kept
 * in app-private storage, so no other app can make this one dial.
 *
 * Audit 2026-11-10 (beside SEC-05): the token and the missed-call count are kept per caller under the caller's node prefix,
 * which earlier builds wrote into the plain `missed_call` preferences and never removed when a notification was dismissed.
 * They are sealed now ([SealedRecords]): only the callers whose notification is still showing are kept (checked when the
 * app starts, at each new missed call and at a Call back), nothing older than [MAX_AGE_MS] (deleted from the file, not
 * only ignored), at most [MAX_RECORDS]. The plain file is moved once (only callers whose notification still shows, with
 * that notification's time) and deleted.
 */
internal object MissedCallNotification {
    const val CHANNEL_ID = "missed_calls"
    private const val NOTIFICATION_ID = 1005
    private const val REQUEST_CODE = 5
    const val EXTRA_PREFIX = "missed_call_back_prefix"
    const val EXTRA_NAME = "missed_call_back_name"
    const val EXTRA_TOKEN = "missed_call_back_token"
    const val EXTRA_VIDEO = "missed_call_back_video"
    /** The plain preferences of earlier builds (`token:<tag>`, `count:<tag>`): moved once into [FILE] and deleted. */
    private const val PREFS = "missed_call"
    private const val KEY_TOKEN = "token"
    private const val KEY_COUNT = "count"
    internal const val FILE = "missed-calls.bin"
    /** A Call back on a notification older than this does nothing (its token is gone). */
    internal const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    internal const val MAX_RECORDS = 64

    /** `tag -> "token,count"` (an empty token when the caller is unknown and the notification has no Call back). */
    private val records = SealedRecordsSlot(FILE, MAX_RECORDS, MAX_AGE_MS) { app -> plainTokens(app) }

    /** Opens the memory at process start and drops callers whose notification is gone (dismissed, or a reboot). */
    fun open(context: Context) {
        val app = context.applicationContext
        val sealed = records.get(app)
        SealedRecordsWorker.execute {
            shownTags(app)?.let { shown -> sealed.retain { it in shown.keys } }
        }
    }

    /** The missed-call notifications showing now, tag -> posted time; null when the system cannot say. */
    private fun shownTags(app: Context): Map<String, Long>? = runCatching {
        app.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.filter { it.id == NOTIFICATION_ID && it.tag != null }
            ?.associate { it.tag to it.postTime }
    }.getOrNull()

    /** An earlier build's tokens: those whose notification still shows, sealed; the plain file is deleted in any case. */
    private fun plainTokens(app: Context) = object : LegacyPlain {
        override fun read(): Map<String, SealedRecords.Record> {
            val all = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            if (all.isEmpty()) return emptyMap()
            return plainRecords(all, shownTags(app).orEmpty())
        }

        override fun clean() = PlainPreferences.deleteFile(app, PREFS)

        override val cache = true
    }

    /**
     * An earlier build's `token:<tag>` and `count:<tag>` preferences as records, only for callers whose notification is in
     * [shown] (tag -> posted time), dated by that notification.
     */
    internal fun plainRecords(all: Map<String, *>, shown: Map<String, Long>): Map<String, SealedRecords.Record> {
        val tags = all.keys.mapNotNull { key ->
            key.removePrefix("$KEY_TOKEN:").takeIf { it != key } ?: key.removePrefix("$KEY_COUNT:").takeIf { it != key }
        }.toSet()
        return tags.filter { it in shown && it.isNotEmpty() && '\t' !in it && '\n' !in it }.associateWith { tag ->
            val token = all["$KEY_TOKEN:$tag"] as? String ?: ""
            val count = all["$KEY_COUNT:$tag"] as? Int ?: 1
            SealedRecords.Record("$token,$count", shown.getValue(tag))
        }
    }

    fun isMissed(message: String): Boolean =
        message == CallRingTimeout.MISSED_MESSAGE || message == CallRingTimeout.CALLER_CANCELLED_MESSAGE

    /** The title: who (null when names stay off the notification or there is none) and how many missed calls. */
    data class Title(val name: String?, val count: Int)

    fun title(name: String?, count: Int): Title = Title(NotificationPrivacy.sessionPeerName(name), count.coerceAtLeast(1))

    fun text(context: Context, title: Title): String {
        val res = context.resources
        return when {
            title.name == null && title.count == 1 -> context.getString(R.string.missed_call_title)
            title.name == null -> res.getQuantityString(R.plurals.missed_calls, title.count, title.count)
            title.count == 1 -> context.getString(R.string.missed_call_from, title.name)
            else -> res.getQuantityString(R.plurals.missed_calls_from, title.count, title.name, title.count)
        }
    }

    /** Audit 2026-10-21: one notification per caller (the tag), so a second caller does not replace the first. */
    fun tag(prefix: String?): String = "missed:" + (prefix?.lowercase() ?: "unknown")

    /** The missed-call count of a caller: one more while that caller's notification is still showing, else 1. */
    fun nextCount(stillShowing: Boolean, previous: Int): Int = if (stillShowing) previous + 1 else 1

    fun allowed(sdkInt: Int, granted: Boolean): Boolean = sdkInt < 33 || granted

    /** What a Call back action dials: the caller and the original call type. */
    data class CallBack(val prefix: String, val name: String, val video: Boolean)

    fun post(context: Context, name: String?, prefix: String?, video: Boolean = true) {
        val app = context.applicationContext
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed(Build.VERSION.SDK_INT, granted)) {
            Log.i("BluetoothVideo", (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "missed_call: posted=false | reason=no_permission")
            return
        }
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, app.getString(R.string.missed_call_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val tag = tag(prefix)
        val sealed = records.get(app)
        val shown = shownTags(app)?.keys
        // Only a caller whose notification still shows needs its token and count.
        if (shown != null) sealed.retain { it == tag || it in shown }
        val showing = shown?.contains(tag) == true
        val count = nextCount(showing, sealed.get(tag)?.value?.substringAfter(',')?.toIntOrNull() ?: 0)
        val token = if (prefix != null) UUID.randomUUID().toString() else ""
        sealed.put(tag, "$token,$count")
        val requestBase = REQUEST_CODE + 2 * ((tag.hashCode() and 0x7fffffff) % 10_000)
        val open = PendingIntent.getActivity(
            app, requestBase,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(text(app, title(name, count)))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (prefix != null) {
            val callBack = Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PREFIX, prefix)
                .putExtra(EXTRA_NAME, NotificationPrivacy.sessionPeerName(name))
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_VIDEO, video)
            builder.addAction(
                0, app.getString(R.string.missed_call_back),
                PendingIntent.getActivity(app, requestBase + 1, callBack, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        val notification = NotificationPrivacy.apply(
            builder, app, CHANNEL_ID, NotificationKind.MISSED_CALL, R.drawable.ic_stat_meshlive, NotificationPrivacy.showPeerNames(app),
        ).build()
        runCatching { manager.notify(tag, NOTIFICATION_ID, notification) }
            .onSuccess {
                Log.i(
                    "BluetoothVideo",
                    (if (BuildConfig.DEBUG) "BENCHMARK " else "") + "missed_call: posted=true | callBack=${prefix != null} | count=$count | video=$video",
                )
            }
            .onFailure { Log.w("BluetoothVideo", "Missed call notification could not be posted", it) }
    }

    /** True when [intent] is a Call back action (whether or not its token is still valid). */
    fun isCallBack(intent: Intent): Boolean = intent.hasExtra(EXTRA_PREFIX) && intent.hasExtra(EXTRA_TOKEN)

    /** False until the sealed tokens are read: a Call back then waits ([whenLoaded], re-review S3). */
    fun isLoaded(context: Context): Boolean = records.get(context).isLoaded()

    /** Runs [action] once the tokens are read (at once if they are; else on the sealed-memory worker). */
    fun whenLoaded(context: Context, action: () -> Unit) = records.get(context).whenLoaded(action)

    /** The Call back action's target, once: null when the intent has none or its token is not the one this app posted. */
    fun takeCallBack(context: Context, intent: Intent): CallBack? {
        val prefix = intent.getStringExtra(EXTRA_PREFIX) ?: return null
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
        val tag = tag(prefix)
        val sealed = records.get(context)
        val stored = sealed.get(tag)?.value?.substringBefore(',')?.takeIf { it.isNotEmpty() }
        if (!tokenMatches(stored, token)) return null
        sealed.remove(listOf(tag))
        shownTags(context.applicationContext)?.let { shown -> sealed.retain { it in shown.keys && it != tag } }
        context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(tag, NOTIFICATION_ID)
        return CallBack(prefix, intent.getStringExtra(EXTRA_NAME) ?: "", intent.getBooleanExtra(EXTRA_VIDEO, true))
    }

    fun tokenMatches(stored: String?, presented: String?): Boolean =
        stored != null && presented != null && java.security.MessageDigest.isEqual(stored.toByteArray(), presented.toByteArray())
}

/** Main-thread startup timing (UX fix 2026-10-21): named sections of onCreate, logged as `startup_main_thread_ms`. */
internal class StartupTimer(private val clockMs: () -> Long) {
    private val started = clockMs()
    private var last = started
    private val sections = ArrayList<Pair<String, Long>>()

    fun mark(name: String) {
        val now = clockMs()
        sections += name to (now - last)
        last = now
    }

    fun totalMs(): Long = last - started

    /** `phase=<phase> | ms=<total> | sections=a:12,b:300` with the sections in order. */
    fun line(phase: String): String =
        "startup_main_thread_ms: phase=$phase | ms=${totalMs()} | sections=" + sections.joinToString(",") { "${it.first}:${it.second}" }
}
