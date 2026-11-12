package com.example.bluetoothtest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Structure of the four tab screens after the "Plain Signal" redesign.
 *
 * - Live is the stream browser itself: the list of nearby live streams, and Go live as the one primary
 *   action, pinned below the list. Contacts pins Add contact the same way. Calls (Nearby | Recent) has none.
 * - No cards or boxes on the tab screens: plain rows on the black ground.
 * - Go live is the solid red live button, never a white button with a dot.
 * - The bottom navigation offers exactly Live, Calls, Contacts and Settings; every tab title bar ends with
 *   the status indicator.
 */
class HomeLayoutTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun parse(relative: String): Element {
        val file = listOf("src/main/res/$relative", "app/src/main/res/$relative").map(::File).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun layout(): Element = parse("layout/activity_main.xml")

    private fun Element.descendants(): Sequence<Element> = sequence {
        val children = childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            yield(child)
            yieldAll(child.descendants())
        }
    }

    private fun idOf(element: Element) = element.getAttributeNS(androidNs, "id").removePrefix("@+id/")

    private fun byId(root: Element, id: String): Element =
        root.descendants().first { idOf(it) == id }

    private fun order(root: Element): List<String> = root.descendants().map(::idOf).toList()

    @Test
    fun liveTabIsTheStreamBrowserWithOneGoLive() {
        val root = layout()
        val live = byId(root, "receiverBrowserScreen")
        val ids = order(live)
        assertTrue("stream list on the Live tab", "streamList" in ids)
        // Go live is pinned below the list, and it is the only primary button on the tab (white with a red live
        // icon since 2026-10-17: filled red is kept for being live and for Wipe).
        assertTrue(ids.indexOf("streamModeButton") > ids.indexOf("streamList"))
        val liveButtons = live.descendants().filter { it.getAttribute("style") == "@style/Widget.App.Button.Primary" }.toList()
        assertEquals(1, liveButtons.size)
        assertEquals("streamModeButton", idOf(liveButtons.single()))
        assertEquals("wrap_content", liveButtons.single().getAttributeNS(androidNs, "layout_height"))
        // No separate "find streams" row or Home screen any more.
        assertNull(root.descendants().firstOrNull { idOf(it) == "receiveModeButton" })
        assertNull(root.descendants().firstOrNull { idOf(it) == "homeScreen" })
    }

    @Test
    fun goLiveIsWhiteWithARedIconAndTheBroadcastToggleStaysRed() {
        val root = layout()
        val goLive = byId(root, "streamModeButton")
        assertEquals("@style/Widget.App.Button.Primary", goLive.getAttribute("style"))
        assertEquals("@color/live_red", goLive.getAttribute("app:iconTint"))
        // On the camera screen the start/stop toggle is the live control itself and stays filled red.
        assertEquals("@style/Widget.App.Button.Live", byId(root, "streamToggleButton").getAttribute("style"))
    }

    @Test
    fun contactsPinsAddContactAndCallsPinsNothing() {
        val root = layout()
        val contacts = order(byId(root, "contactsTabScreen"))
        assertTrue(contacts.indexOf("contactsAddButton") > contacts.indexOf("contactsList"))
        assertEquals(1, order(root).count { it == "contactsAddButton" })
        // Both Calls screens (Nearby, Recent) have no button of their own.
        listOf("callsTabScreen", "callBrowserScreen").forEach { id ->
            assertTrue(byId(root, id).descendants().none { it.tagName.endsWith("Button") })
            // and the Nearby | Recent switch
            assertTrue(byId(root, id).descendants().any { it.getAttribute("layout") == "@layout/view_segments" })
        }
    }

    @Test
    fun tabScreensHaveNoCards() {
        val root = layout()
        listOf("receiverBrowserScreen", "callsTabScreen", "callBrowserScreen", "contactsTabScreen", "settingsScreen").forEach { id ->
            val cards = byId(root, id).descendants().filter { it.tagName.endsWith("CardView") }.toList()
            assertTrue("$id should have no cards, found ${cards.size}", cards.isEmpty())
        }
    }

    @Test
    fun oldHomeViewsAreKeptButHidden() {
        val root = layout()
        // Views the code still reads and writes sit in a hidden container on the Live tab.
        listOf("statusText", "homeReadinessCard", "homePushToTalkButton", "homeIdentityCard", "homeChangeSettingsButton")
            .forEach { id ->
                var node: org.w3c.dom.Node? = byId(root, id)
                var hidden = false
                while (node is Element) {
                    if (node.getAttributeNS(androidNs, "visibility") == "gone") hidden = true
                    node = node.parentNode
                }
                assertTrue("$id should be hidden", hidden)
            }
    }

    @Test
    fun everyTabHasABottomNavigationEntryWithAnIconAndATitle() {
        val menu = parse("menu/main_nav.xml")
        val items = menu.descendants().toList()
        val ids = items.map { it.getAttributeNS(androidNs, "id").removePrefix("@+id/") }
        // A tab without an entry cannot be reached; an entry without a tab does nothing when tapped.
        assertEquals(MainTab.entries.map { "nav" + it.name.lowercase().replaceFirstChar(Char::uppercase) }.toSet(), ids.toSet())
        assertEquals("no entry twice", ids.size, ids.toSet().size)
        val res = java.io.File(listOf("src/main/res", "app/src/main/res").first { java.io.File(it).isDirectory })
        val strings = res.resolve("values").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }!!
            .joinToString("\n") { it.readText() }
        items.forEach { item ->
            val title = item.getAttributeNS(androidNs, "title").removePrefix("@string/")
            assertTrue("$title has text", Regex("<string name=\"$title\"[^>]*>[^<]+</string>").containsMatchIn(strings))
            val icon = item.getAttributeNS(androidNs, "icon").removePrefix("@drawable/")
            assertTrue("$icon exists", res.resolve("drawable").listFiles { f -> f.nameWithoutExtension == icon }!!.isNotEmpty())
        }
        val nav = byId(layout(), "mainNav")
        assertEquals("gone", nav.getAttributeNS(androidNs, "visibility"))
        assertFalse(nav.getAttribute("app:menu").isEmpty())
    }

    @Test
    fun everyTabTitleBarHasTheStatusIndicator() {
        val root = layout()
        listOf("tabStatusReceiver", "tabStatusCalls", "tabStatusNearby", "tabStatusContacts", "tabStatusSettings").forEach { id ->
            byId(root, id)
        }
    }

    @Test
    fun incomingCallDeclineAndAcceptShareOneRow() {
        val root = layout()
        val controls = byId(root, "incomingCallControls")
        val children = (0 until controls.childNodes.length).mapNotNull { controls.childNodes.item(it) as? Element }
        // Row first (both circles together, on one baseline), then "Decline and block" below it.
        val row = children.first()
        val rowIds = row.descendants().map(::idOf).toSet()
        assertTrue("declineCallButton" in rowIds && "acceptCallButton" in rowIds)
        assertEquals("top", row.getAttributeNS(androidNs, "gravity"))
        assertEquals(listOf("declineAndBlockButton", "declineAndReportButton"), children.drop(1).map(::idOf).filter { it.startsWith("declineAnd") })
        assertEquals(
            "accept and decline are the same size and shape",
            byId(root, "declineCallButton").getAttribute("style"),
            byId(root, "acceptCallButton").getAttribute("style"),
        )
    }

    @Test
    fun streamScreensHaveNoDecorativeLock() {
        val root = layout()
        // The lock under the LIVE badge is gone: the pill only appears for a hard identity mismatch.
        val pill = byId(root, "streamSecurityText")
        assertEquals("", pill.getAttribute("app:drawableStartCompat"))
        assertEquals("gone", pill.getAttributeNS(androidNs, "visibility"))
        // The call screen's lock shows only on a mismatch (hidden by default).
        assertEquals("gone", byId(root, "callLockButton").getAttributeNS(androidNs, "visibility"))
    }

    @Test
    fun settingsNeverNestTwoLevels() {
        // Every page opens from the top page (Advanced privacy, the one second level, is gone since 2026-11-04).
        SettingsPage.entries.forEach { page ->
            assertTrue("${page.name} nests", page.parent == null || page.parent == SettingsPage.MAIN)
        }
        // Every "Advanced" row opens one page of that group's options: it names a real page whose parent is the top page.
        val root = layout()
        val main = byId(root, "settingsPageMain")
        val ids = order(main)
        val rowIds = ids.filter { it.startsWith("advancedRow") }
        assertTrue("the Advanced section has rows", rowIds.isNotEmpty())
        rowIds.forEach { rowId ->
            val page = SettingsPage.entries.firstOrNull { it.name.equals(rowId.removePrefix("advancedRow"), ignoreCase = true) }
            assertNotNull("$rowId opens a settings page", page)
            assertEquals(rowId, SettingsPage.MAIN, page!!.parent)
            byId(root, "settingsPage" + page.name.lowercase().replaceFirstChar { it.uppercase() })
        }
        // The groups' options live on those pages, not on the top page.
        val groupOptions = listOf(
            SettingsPage.CONNECTION to "transportSpinner",
            SettingsPage.MANUAL to "advancedSettingsSwitch",
            SettingsPage.MANUAL to "receiverPlaybackDelaySlider", // Playback merged into Manual video (2026-10-17)
            SettingsPage.HELP to "exportDiagnosticsButton",
        )
        groupOptions.forEach { (page, option) ->
            assertTrue("$option not on the top page", option !in ids)
            val pageId = "settingsPage" + page.name.lowercase().replaceFirstChar { it.uppercase() }
            assertTrue("$option on its page", option in order(byId(root, pageId)))
        }
        assertNull(root.descendants().firstOrNull { idOf(it) == "advancedToggleRow" })
    }

    @Test
    fun everySettingsPageExists() {
        val root = layout()
        SettingsPage.entries.forEach { page ->
            val name = "settingsPage" + when (page) {
                SettingsPage.MAIN -> "Main"
                SettingsPage.VIDEO -> "Video"
                SettingsPage.PRIVACY -> "Privacy"
                SettingsPage.CALLS -> "Calls"
                SettingsPage.SHARE -> "Share"
                SettingsPage.CONNECTION -> "Connection"
                SettingsPage.MANUAL -> "Manual"
                SettingsPage.PLAYBACK -> "Playback"
                SettingsPage.EXPERIMENTS -> "Experiments"
                SettingsPage.HELP -> "Help"
            }
            byId(root, name)
        }
    }

    /** The direct children of [container] that hold one control each (the equal columns of a control row). */
    private fun columns(container: Element): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = container.childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(result::add)
        return result
    }

    private fun assertEqualColumnsWithEndInTheMiddle(container: Element, expectedOrder: List<String>, endId: String) {
        val cols = columns(container)
        assertTrue("an odd number of columns, so End can sit in the middle", cols.size % 2 == 1)
        cols.forEach { col ->
            assertEquals("equal columns: weight", "1", col.getAttributeNS(androidNs, "layout_weight"))
            assertEquals("equal columns: width", "0dp", col.getAttributeNS(androidNs, "layout_width"))
        }
        // The control that sits in each column, in order: the first listed id inside the column.
        val ownerOfEnd = cols.indexOfFirst { col -> col.descendants().any { idOf(it) == endId } || idOf(col) == endId }
        assertEquals("End is exactly in the middle column", cols.size / 2, ownerOfEnd)
        // Every control the screen promises is present, and End comes after the controls listed before it.
        val position = expectedOrder.map { id ->
            cols.indexOfFirst { col -> idOf(col) == id || col.descendants().any { idOf(it) == id } }
        }
        position.forEachIndexed { index, at -> assertTrue("${expectedOrder[index]} is in the row", at >= 0) }
        assertEquals("the controls keep their order", position.sorted(), position)
    }

    @Test
    fun callControlsAreOneRowWithEndInTheMiddle() {
        val root = layout()
        val row = byId(root, "activeCallControls")
        // Voice and video calls: the same five controls.
        assertEqualColumnsWithEndInTheMiddle(
            row,
            listOf("callMuteButton", "callCameraButton", "endCallButton", "callSwitchCameraButton", "callSpeakerButton"),
            "endCallButton",
        )
        // End is the red, slightly larger circle; the others are the standard round control.
        assertEquals("@style/Widget.App.RoundControl.Large", byId(root, "endCallButton").getAttribute("style"))
        listOf("callMuteButton", "callCameraButton", "callSwitchCameraButton", "callSpeakerButton").forEach {
            assertEquals("@style/Widget.App.RoundControl", byId(root, it).getAttribute("style"))
        }
        // A control row, not a second row: nothing but the five columns, and no separate End row.
        assertEquals("horizontal", row.getAttributeNS(androidNs, "orientation"))
        assertEquals(1, order(root).count { it == "endCallButton" })
    }

    @Test
    fun liveControlsAreOneRowWithEndInTheMiddle() {
        val root = layout()
        assertEqualColumnsWithEndInTheMiddle(
            byId(root, "liveControls"),
            listOf("broadcastMuteButton", "broadcastCameraButton", "streamToggleButton", "cameraSwitchButton", "broadcastPauseButton"),
            "streamToggleButton",
        )
        // The rules keep End in the middle for a video broadcast.
        assertTrue(LiveControlSlots.endIsCentred(LiveControlSlots.slots(audioOnly = false)))
    }

    @Test
    fun audioLiveControlsAreOneRowWithEndInTheMiddle() {
        val root = parse("layout/screen_audio_broadcast.xml")
        assertEqualColumnsWithEndInTheMiddle(
            byId(root, "audioControls"),
            listOf("audioMuteButton", "audioStopButton", "audioPauseButton"),
            "audioStopButton",
        )
        assertEquals("@style/Widget.App.RoundControl.Large", byId(root, "audioStopButton").getAttribute("style"))
        assertTrue(LiveControlSlots.endIsCentred(LiveControlSlots.slots(audioOnly = true)))
        // Switch to video is a plain action under the row, not a fourth control in it.
        val ids = order(root)
        assertTrue(ids.indexOf("audioSwitchToVideoButton") > ids.indexOf("audioControls"))
    }

    @Test
    fun everyFlipControlUsesTheCameraSwitchIcon() {
        val files = listOf("layout/activity_main.xml", "layout/screen_audio_broadcast.xml")
        files.forEach { name ->
            val text = listOf("src/main/res/$name", "app/src/main/res/$name").map(::File).first { it.isFile }.readText()
            assertFalse("$name still uses the old flip icon", text.contains("ic_flip_camera"))
        }
        val root = layout()
        listOf("callSwitchCameraButton", "cameraSwitchButton").forEach { id ->
            val attributes = byId(root, id).attributes
            val values = (0 until attributes.length).map { attributes.item(it).nodeValue }
            assertTrue("$id shows the camera-switch icon", values.any { it.contains("ic_camera_switch") })
        }
    }
}
