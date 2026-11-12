package com.example.bluetoothtest

import android.content.Context

/**
 * The chat entry (user feedback 2026-10-21: "just a Messages button on the Live page"): a Chats tab in the bottom
 * navigation after Calls, present only with the Experimental messages switch on. UI and navigation only; the engine, the
 * store and the protocol are unchanged.
 */
internal object ChatTab {
    /** The tab exists only while the messages switch is on; off, the navigation is exactly as before. */
    fun visible(messagesOn: Boolean): Boolean = messagesOn

    /** The tab's badge: the unread messages of every chat, or null for no badge. */
    fun badge(unreadPerChat: Collection<Int>): Int? = unreadPerChat.sumOf { it.coerceAtLeast(0) }.takeIf { it > 0 }

    /** With Messaging v2 the Chats tab lists conversations in place; with the older Messages only, it opens that screen. */
    fun inPlace(v2: Boolean): Boolean = v2
}

/** Which thread an entry point opens: a contact row's or detail's message button, the call-ended "Message", a notification. */
internal object ChatEntry {
    enum class Source { CONTACT_ROW, CONTACT_DETAIL, CALL_ENDED, NOTIFICATION }

    /** The node ID (hex) of the thread to open, or null when there is no chat to open (feature off, no identity). */
    fun threadFor(source: Source, nodeIdHex: String?, v2: Boolean): String? =
        nodeIdHex?.lowercase()?.takeIf { v2 && it.length == NODE_ID_HEX && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private const val NODE_ID_HEX = NodeId.BYTE_COUNT * 2
}

/** The composer's action button: Send only with text; an empty field offers the voice note (Send is never pressable empty). */
internal object ComposerAction {
    enum class Action { SEND, VOICE }

    fun forText(text: CharSequence?): Action = if (text?.isNotBlank() == true) Action.SEND else Action.VOICE
}

/**
 * Muted chats: a muted chat keeps its unread count but posts no notification. The list holds the node IDs of those chats,
 * so it is sealed at rest ([SealedRecords], verified-contacts Keystore key). Audit 2026-11-10 (docs truth pass, beside
 * SEC-05): earlier builds kept it in the plain `chat_mute` preferences; it is moved once and that file is deleted.
 */
internal object ChatMute {
    private const val PREFS = "chat_mute"
    private const val KEY = "muted"
    internal const val FILE = "chat-mute.bin"

    /** Explicit choices: never evicted (review of 040a8c2e, finding 2). */
    private val store = SealedRecordsSlot(FILE, maxEntries = null) { app -> plainList(app) }

    /** Opens the list at process start, so an earlier build's plain copy is moved even if Chats is off. */
    fun open(context: Context) {
        store.get(context)
    }

    /** The plain list of an earlier build: deleted once the sealed copy is on the disk, confirmed gone from the disk. */
    private fun plainList(app: Context) = object : LegacyPlain {
        override fun read(): Map<String, SealedRecords.Record> {
            val now = System.currentTimeMillis()
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
                .associate { it.lowercase() to SealedRecords.Record("", now) }
        }

        override fun clean() = PlainPreferences.deleteFile(app, PREFS)

        override val cache = false
    }

    /** False until the sealed list is read; a notification waits for it ([whenLoaded], re-review S3). */
    fun isLoaded(context: Context): Boolean = store.get(context).isLoaded()

    /** Runs [action] once the list is read (at once if it is; else on the sealed-memory worker). */
    fun whenLoaded(context: Context, action: () -> Unit) = store.get(context).whenLoaded(action)

    fun isMuted(context: Context, peerHex: String): Boolean = store.get(context).get(peerHex.lowercase()) != null

    fun setMuted(context: Context, peerHex: String, muted: Boolean) {
        val sealed = store.get(context)
        if (muted) sealed.put(peerHex.lowercase(), "") else sealed.remove(listOf(peerHex.lowercase()))
    }
}
