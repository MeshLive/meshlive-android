package com.example.bluetoothtest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/*
 * Messaging v2: notifications for new messages (DECISIONS Design M, "Notification privacy"; behind `test_messaging_v2`).
 *
 * One notification per conversation, grouped under one summary. The pure rules (what each notification may say, when one is
 * shown, how a tap is routed) are in [ConversationNotificationRules] and [ConversationRoute] and have JVM tests; the Android
 * posting is in [ConversationNotifier].
 *
 * Privacy (the lock-screen rules of the call notifications, NotificationPrivacy, applied to messages):
 * - Every notification is VISIBILITY_PRIVATE with a public version: a locked phone shows "New message" under the app name, or
 *   under the contact's label only when the user allowed names on the lock screen (Settings > Features). Never the text.
 * - The full version (unlocked phone) shows the contact's label (the naming precedence of the Contacts tab) and the text of a
 *   single unread text message. A file shows only its type ("Photo", "File"), never its name. Messages with a disappearing
 *   timer and several unread messages show no text at all, so a notification never outlives a message that was meant to vanish
 *   and never shows more than the thread would.
 * - A label that would print a raw identifier is replaced by the app name. No notification ever carries a node ID or a prefix.
 */

/** What an unread incoming message may contribute to a notification. */
enum class UnreadKind { TEXT, PICTURE, VIDEO, VOICE, FILE, OTHER }

/** The facts of one unread incoming message that the notification rules need (no body unless it is text). */
data class UnreadSummary(val kind: UnreadKind, val text: String, val timed: Boolean)

/** What one notification says, in both versions. */
data class NotificationContent(
    /** The unlocked phone's title: the contact's label, else the app name. */
    val title: String,
    val text: String,
    /** The locked phone's version: [publicTitle] is the label only when names are allowed on the lock screen. */
    val publicTitle: String,
    val publicText: String,
    val count: Int,
    /** The notification shows the text of the one unread message (so the user has seen what "Mark as read" marks). */
    val showsText: Boolean,
    val markReadAllowed: Boolean,
)

/** What to do with a conversation's notification after a change. */
sealed interface NotifyPlan {
    data object Cancel : NotifyPlan
    data object Skip : NotifyPlan
    data class Post(val content: NotificationContent, val alert: Boolean) : NotifyPlan
}

object ConversationNotificationRules {
    const val PREVIEW_CHARS = 80

    fun unreadOf(message: ThreadMessage): UnreadSummary {
        val timed = message.disappearSeconds > 0
        return when (message.kind) {
            V2Kind.TEXT -> UnreadSummary(UnreadKind.TEXT, message.text, timed)
            V2Kind.ATTACHMENT -> UnreadSummary(
                when (message.attachment?.presentation) {
                    Presentation.PICTURE -> UnreadKind.PICTURE
                    Presentation.VIDEO -> UnreadKind.VIDEO
                    Presentation.VOICE -> UnreadKind.VOICE
                    else -> UnreadKind.FILE
                },
                "", timed,
            )
            else -> UnreadSummary(UnreadKind.OTHER, "", timed)
        }
    }

    /** The label if it can be shown: not blank and not a raw identifier (a run of eight or more hex digits). */
    fun safeLabel(label: String?): String? {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty() || Regex("[0-9a-fA-F]{8,}").containsMatchIn(trimmed)) return null
        return trimmed.take(60)
    }

    private fun preview(item: UnreadSummary, text: ThreadText): String? = when {
        item.timed -> null
        item.kind == UnreadKind.TEXT -> item.text.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }?.take(PREVIEW_CHARS)
        item.kind == UnreadKind.PICTURE -> text.get("mv2_ui_preview_photo")
        item.kind == UnreadKind.VIDEO -> text.get("mv2_ui_preview_video")
        item.kind == UnreadKind.VOICE -> text.get("mv2_ui_preview_voice")
        item.kind == UnreadKind.FILE -> text.get("mv2_ui_preview_file")
        else -> null
    }

    /**
     * The content for [unread] (oldest first) of a conversation, plus [legacyUnread] messages from an older app (their text is
     * never shown here). Null when nothing is unread. [namesOnLockScreen] is the user's "Show names on lock screen" choice.
     */
    fun content(
        label: String?,
        unread: List<UnreadSummary>,
        legacyUnread: Int,
        namesOnLockScreen: Boolean,
        appTitle: String,
        text: ThreadText,
    ): NotificationContent? {
        val count = unread.size + legacyUnread
        if (count <= 0) return null
        val name = safeLabel(label)
        val generic = if (count == 1) text.get("mv2_ui_notify_new_message") else text.get("mv2_ui_notify_new_messages", count)
        val publicTitle = if (namesOnLockScreen && name != null) name else appTitle
        val single = if (count == 1 && legacyUnread == 0) unread.single() else null
        val shown = if (name != null && single != null) preview(single, text) else null
        val body = when {
            shown != null -> shown
            else -> generic
        }
        val showsText = shown != null && single?.kind == UnreadKind.TEXT
        return NotificationContent(
            title = name ?: appTitle,
            text = body,
            publicTitle = publicTitle,
            publicText = generic,
            count = count,
            showsText = showsText,
            // Marking read sends a read receipt when both phones agreed to receipts (the engine decides that); it is offered only
            // when the notification itself showed the whole message, so a receipt never claims a message nobody has seen.
            markReadAllowed = showsText,
        )
    }

    /** No notification while that thread is open and the app is in front: the message is on screen already. */
    fun suppressed(appForeground: Boolean, threadOpen: Boolean): Boolean = appForeground && threadOpen

    /**
     * [alert]: a new message arrived (sound and vibration per the user's channel settings). A read, a delete or a contact
     * change only updates a notification that is still there; it never brings back one the user swiped away.
     */
    fun plan(content: NotificationContent?, suppressed: Boolean, enabled: Boolean, alert: Boolean, active: Boolean): NotifyPlan = when {
        content == null || suppressed -> NotifyPlan.Cancel
        !enabled -> NotifyPlan.Skip
        alert || active -> NotifyPlan.Post(content, alert)
        else -> NotifyPlan.Skip
    }
}

/** The tap on a notification: which conversation to open. The extra holds the conversation ID, never a node ID. */
object ConversationRoute {
    const val EXTRA_CONVERSATION = "mv2_open_conversation"

    fun conversationHex(self: NodeId, peer: NodeId): String = ConversationIds.of(self, peer).sfHex()

    /** A stable request code per conversation so two notifications never share a PendingIntent. */
    fun requestCode(conversationHex: String): Int = conversationHex.take(8).toLong(16).toInt()

    fun tag(conversationHex: String): String = "mv2:$conversationHex"

    /**
     * The accepted contact whose conversation ID is [extra], or null: a missing, malformed or unknown ID (a deleted contact, a
     * forged intent) opens nothing but the app itself.
     */
    fun resolve(extra: String?, self: NodeId, accepted: Collection<NodeId>): NodeId? {
        val wanted = extra?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
        return accepted.firstOrNull { conversationHex(self, it) == wanted }
    }
}

/** Posts, updates and cancels the conversation notifications. All work runs on one background thread. */
internal object ConversationNotifier {
    const val CHANNEL_ID = "messages"
    private const val LEGACY_CHANNEL_ID = MessageNotifications.CHANNEL_ID
    private const val GROUP_KEY = "mv2_messages"
    private const val SUMMARY_ID = 1100
    private const val CHILD_ID = 1101
    const val ACTION_MARK_READ = "io.github.meshlive.app.MV2_MARK_READ"

    /** Messages from an older app per peer (no read state of their own): counted until the thread is opened. */
    private val legacyUnread = ConcurrentHashMap<String, Int>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mv2-notify").apply { isDaemon = true } }

    /** Installed once per opened node: every message that reaches storage updates the notification of its conversation. */
    fun install(context: Context, node: StoreForwardNode) {
        val app = context.applicationContext
        node.addCompletionListener { peer, completion ->
            when {
                completion is StoreForwardCompletion.V2Message && completion.message.direction == MessageDirection.IN ->
                    worker.execute { runCatching { refresh(app, peer.toString(), alert = true) }.onFailure(::logFailure) }
                // A message from a contact whose app is older: no text here, only that something arrived.
                completion is StoreForwardCompletion.Opened -> worker.execute {
                    runCatching {
                        legacyUnread.merge(peer.toString(), 1, Int::plus)
                        refresh(app, peer.toString(), alert = true)
                    }.onFailure(::logFailure)
                }
            }
        }
    }

    private fun logFailure(error: Throwable) = Log.w("BluetoothVideo", "Message notification failed: ${error.javaClass.simpleName}")

    /** Re-evaluates the notification of [peerHex] now (off the calling thread). */
    fun update(context: Context, peerHex: String) {
        val app = context.applicationContext
        worker.execute { runCatching { refresh(app, peerHex, alert = false) }.onFailure(::logFailure) }
    }

    /** The thread was opened: its notification and the older-app count are gone. */
    fun opened(context: Context, peerHex: String) {
        legacyUnread.remove(peerHex)
        val app = context.applicationContext
        worker.execute { runCatching { cancelPeer(app, peerHex) }.onFailure(::logFailure) }
    }

    /** Emergency wipe (the wipe itself cancels every notification): forget the counts. */
    fun resetCounts() = legacyUnread.clear()

    private fun postingAllowed(context: Context, manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        // Default importance: the user changes sound, vibration and pop-up in the system settings for "Messages".
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, AndroidThreadText(context).get("mv2_ui_notify_channel_name"), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = AndroidThreadText(context).get("mv2_ui_notify_channel_description")
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        // The v1 count notification is replaced while Messaging v2 is on; its channel would only be a second "Messages" entry.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
    }

    private fun cancelPeer(context: Context, peerHex: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val self = StoreForwardRuntime.nodeIfOpen()?.nodeId
        val peer = runCatching { NodeId.fromBytes(hexToBytes(peerHex)) }.getOrNull()
        if (self != null && peer != null) manager.cancel(ConversationRoute.tag(ConversationRoute.conversationHex(self, peer)), CHILD_ID)
        refreshSummary(context, manager)
    }

    private fun refresh(context: Context, peerHex: String, alert: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val node = StoreForwardRuntime.nodeIfOpen() ?: return
        val engine = node.v2 ?: return
        val peer = runCatching { NodeId.fromBytes(hexToBytes(peerHex)) }.getOrNull() ?: return
        // Re-review S3: before the sealed mute and report lists are read, a muted chat could alert. The refresh waits for
        // them (it runs again on this worker once they are read) instead of deciding on a partial list.
        if (!ChatMute.isLoaded(context) || !LocalReporting.isLoaded(context)) {
            ChatMute.whenLoaded(context) {
                LocalReporting.whenLoaded(context) {
                    worker.execute { runCatching { refresh(context, peerHex, alert) }.onFailure(::logFailure) }
                }
            }
            return
        }
        // A muted chat (ChatMute, the chat list's long-press) keeps its unread count but shows no notification.
        if (ChatMute.isMuted(context, peerHex) || LocalReporting.hidden(context, peerHex)) {
            cancelPeer(context, peerHex)
            return
        }
        val conversation = ConversationRoute.conversationHex(node.nodeId, peer)
        val tag = ConversationRoute.tag(conversation)
        val contact = node.contacts.get(peer)?.takeIf { it.state == ContactState.ACCEPTED }
        val unread = if (contact == null) emptyList() else engine.threads.forPeer(peerHex)
            .filter { it.direction == MessageDirection.IN && it.readAtMs == 0L }.map(ConversationNotificationRules::unreadOf)
        val legacy = if (contact == null) 0 else legacyUnread[peerHex] ?: 0
        val names = runCatching { SecurityUiBackend.current.contactNames(context) }.getOrDefault(emptyMap())
        val text = AndroidThreadText(context)
        val content = ConversationNotificationRules.content(
            ThreadNaming.title(peerHex, names[peerHex.take(12)]), unread, legacy,
            NotificationPrivacy.showPeerNames(context), context.getString(R.string.app_name), text,
        )
        val presence = StoreForwardRuntime.chatIfCreated()?.presence
        val suppressed = ConversationNotificationRules.suppressed(
            presence?.appForeground == true, presence?.interest(peer) == ChatInterest.OPEN,
        )
        val active = runCatching { manager.activeNotifications.any { it.tag == tag && it.id == CHILD_ID } }.getOrDefault(false)
        when (val plan = ConversationNotificationRules.plan(content, suppressed, postingAllowed(context, manager), alert, active)) {
            NotifyPlan.Cancel -> manager.cancel(tag, CHILD_ID)
            NotifyPlan.Skip -> Unit
            is NotifyPlan.Post -> {
                ensureChannel(context, manager)
                manager.notify(tag, CHILD_ID, build(context, plan.content, conversation, plan.alert))
            }
        }
        refreshSummary(context, manager)
    }

    private fun contentIntent(context: Context, conversation: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (conversation != null) intent.putExtra(ConversationRoute.EXTRA_CONVERSATION, conversation)
        val request = if (conversation != null) ConversationRoute.requestCode(conversation) else 0
        return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun build(context: Context, content: NotificationContent, conversation: String, alert: Boolean): android.app.Notification {
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(content.publicTitle)
            .setContentText(content.publicText)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setNumber(content.count)
            .setContentIntent(contentIntent(context, conversation))
        if (content.markReadAllowed) {
            val markRead = PendingIntent.getBroadcast(
                context, ConversationRoute.requestCode(conversation),
                Intent(context, MessageNotificationReceiver::class.java).setAction(ACTION_MARK_READ)
                    .putExtra(ConversationRoute.EXTRA_CONVERSATION, conversation),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, AndroidThreadText(context).get("mv2_ui_notify_mark_read"), markRead)
        }
        return builder.build()
    }

    /** One generic summary while any conversation notification is up, none when the last one is gone. */
    private fun refreshSummary(context: Context, manager: NotificationManager) {
        val children = runCatching { manager.activeNotifications.filter { it.id == CHILD_ID && it.tag?.startsWith("mv2:") == true } }
            .getOrDefault(emptyList())
        if (children.isEmpty()) {
            manager.cancel(SUMMARY_ID)
            return
        }
        val total = children.sumOf { it.notification.number.coerceAtLeast(1) }
        val generic = if (total == 1) AndroidThreadText(context).get("mv2_ui_notify_new_message")
        else AndroidThreadText(context).get("mv2_ui_notify_new_messages", total)
        val appTitle = context.getString(R.string.app_name)
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meshlive).setContentTitle(appTitle).setContentText(generic).build()
        manager.notify(
            SUMMARY_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_meshlive)
                .setContentTitle(appTitle)
                .setContentText(generic)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(context, null))
                .build(),
        )
    }

    /** A contact was removed or renamed: notifications of conversations that no longer resolve go, the rest are refreshed. */
    fun contactsChanged(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val manager = app.getSystemService(NotificationManager::class.java) ?: return@runCatching
                val node = StoreForwardRuntime.nodeIfOpen() ?: return@runCatching
                val accepted = node.contacts.accepted().map { it.nodeId }
                manager.activeNotifications.filter { it.id == CHILD_ID && it.tag?.startsWith("mv2:") == true }.forEach { active ->
                    val peer = ConversationRoute.resolve(active.tag.removePrefix("mv2:"), node.nodeId, accepted)
                    if (peer == null) manager.cancel(active.tag, CHILD_ID) else refresh(app, peer.toString(), alert = false)
                }
                refreshSummary(app, manager)
            }.onFailure(::logFailure)
        }
    }

    /** "Mark as read" from the notification: the same call the open thread makes for what it showed. */
    fun markReadFromNotification(context: Context, conversation: String?) {
        // Only a running node: a process that was restarted for this broadcast has neither the switch nor the node, and must not
        // open the store as version 1 just to mark a message.
        val node = StoreForwardRuntime.nodeIfOpen() ?: return
        val engine = node.v2 ?: return
        val peer = ConversationRoute.resolve(conversation, node.nodeId, node.contacts.accepted().map { it.nodeId }) ?: return
        engine.threads.forPeer(peer.toString())
            .filter { it.direction == MessageDirection.IN && it.readAtMs == 0L }
            .forEach { runCatching { engine.markRead(it.id) } }
        legacyUnread.remove(peer.toString())
        refresh(context.applicationContext, peer.toString(), alert = false)
    }
}

/** The "Mark as read" action of a conversation notification. Not exported; the PendingIntent is immutable and explicit. */
class MessageNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConversationNotifier.ACTION_MARK_READ || !MessagingV2Feature.enabled) return
        val conversation = intent.getStringExtra(ConversationRoute.EXTRA_CONVERSATION)
        val pending = goAsync()
        Thread {
            try {
                ConversationNotifier.markReadFromNotification(context.applicationContext, conversation)
            } catch (error: Throwable) {
                Log.w("BluetoothVideo", "Mark as read failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.apply { name = "mv2-mark-read"; isDaemon = true }.start()
    }
}
