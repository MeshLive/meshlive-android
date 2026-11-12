package com.example.bluetoothtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat entry rebuilt (user feedback 2026-10-21): the Chats tab, its badge, the composer and the entry points. */
class ChatUiTest {
    private val peerHex = "ab".repeat(NodeId.BYTE_COUNT)

    @Test
    fun theChatsTabExistsOnlyWithTheMessagesSwitch() {
        assertTrue(ChatTab.visible(messagesOn = true))
        assertFalse(ChatTab.visible(messagesOn = false))
        assertTrue("conversations in place with Messaging v2", ChatTab.inPlace(v2 = true))
        assertFalse("the older Messages screen otherwise", ChatTab.inPlace(v2 = false))
    }

    @Test
    fun theBadgeCountsUnreadAndClearsWhenRead() {
        assertEquals(5, ChatTab.badge(listOf(2, 0, 3)))
        assertNull("opened and read: no badge", ChatTab.badge(listOf(0, 0, 0)))
        assertNull(ChatTab.badge(emptyList()))
        assertEquals("a bad count never subtracts", 2, ChatTab.badge(listOf(2, -1)))
    }

    @Test
    fun sendIsOnlyOfferedWithText() {
        assertEquals(ComposerAction.Action.VOICE, ComposerAction.forText(""))
        assertEquals(ComposerAction.Action.VOICE, ComposerAction.forText("   \n"))
        assertEquals(ComposerAction.Action.VOICE, ComposerAction.forText(null))
        assertEquals(ComposerAction.Action.SEND, ComposerAction.forText("hi"))
    }

    @Test
    fun everyEntryPointOpensThatPersonsThread() {
        for (source in ChatEntry.Source.values()) {
            assertEquals(source.name, peerHex, ChatEntry.threadFor(source, peerHex.uppercase(), v2 = true))
        }
        assertNull("chat UI off", ChatEntry.threadFor(ChatEntry.Source.CONTACT_ROW, peerHex, v2 = false))
        assertNull("no identity (a stranger)", ChatEntry.threadFor(ChatEntry.Source.CALL_ENDED, null, v2 = true))
        assertNull("not a node ID", ChatEntry.threadFor(ChatEntry.Source.NOTIFICATION, "../etc", v2 = true))
    }

    @Test
    fun yourOwnLastMessageReadsYou() {
        val peer = NodeId.fromBytes(ByteArray(NodeId.BYTE_COUNT) { 0x11 })
        fun summary(incoming: Boolean) = ConversationSummary(
            peer, "Asha", true, "see you at the bridge", "text", 1_000L, incoming, 0, 0,
            if (incoming) null else OwnMessageState.DELIVERED, ConversationLinkState.READY, null,
        )
        assertEquals("see you at the bridge", ConversationRows.subtitle(summary(incoming = true), false, XmlThreadText))
        assertEquals(XmlThreadText.get("mv2_ui_preview_you", "see you at the bridge"), ConversationRows.subtitle(summary(incoming = false), false, XmlThreadText))
    }
}
