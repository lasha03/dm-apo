package app.dms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramScreensTest {

    private fun tab(id: String?, desc: String?, selected: Boolean = false) =
        UiNode(id, desc, clickable = true, children = listOf(UiNode(null, null, selected = selected)))

    private fun tabBar(selected: String, ids: Boolean = true): UiNode {
        val tabs = listOf(
            "feed_tab" to "Home", "search_tab" to "Search and explore", "clips_tab" to "Reels",
            "direct_tab" to "Messages", "profile_tab" to "Profile",
        )
        return UiNode("tab_bar", null, children = tabs.map { (id, label) ->
            tab(if (ids) id else null, label, selected = id == selected)
        })
    }

    private fun screen(vararg parts: UiNode) = UiNode("root", null, children = parts.toList())

    @Test fun detectsSelectedTabById() {
        assertEquals(Screen.HOME, InstagramScreens.detect(screen(tabBar("feed_tab"))).screen)
        assertEquals(Screen.REELS, InstagramScreens.detect(screen(tabBar("clips_tab"))).screen)
        assertEquals(Screen.SEARCH, InstagramScreens.detect(screen(tabBar("search_tab"))).screen)
        assertEquals(Screen.MESSAGES, InstagramScreens.detect(screen(tabBar("direct_tab"))).screen)
    }

    @Test fun fallsBackToEnglishLabels() {
        assertEquals(Screen.REELS, InstagramScreens.detect(screen(tabBar("clips_tab", ids = false))).screen)
        assertEquals(Screen.HOME, InstagramScreens.detect(screen(tabBar("feed_tab", ids = false))).screen)
    }

    @Test fun noTabBarMeansInsideAChat() {
        val chat = screen(UiNode("thread_title", "Alex"), UiNode("row_thread_composer_edittext", null))
        val result = InstagramScreens.detect(chat)
        assertEquals(Screen.OTHER, result.screen)
        assertEquals(emptyList<String>(), result.tabsSeen)
    }

    @Test fun unselectedTabBarIsNotBlocked() {
        val bar = UiNode("tab_bar", null, children = listOf(tab("feed_tab", "Home"), tab("clips_tab", "Reels")))
        assertEquals(Screen.OTHER, InstagramScreens.detect(screen(bar)).screen)
    }

    @Test fun findsMessagesButton() {
        val home = screen(
            UiNode("action_bar", null, children = listOf(UiNode("action_bar_inbox_button", "Messenger", clickable = true))),
            tabBar("feed_tab"),
        )
        // The Messages tab is preferred when both exist; either leads to the inbox.
        assertEquals("direct_tab", InstagramScreens.findMessagesButton(home)?.viewId)

        val classic = screen(
            UiNode("action_bar", null, children = listOf(UiNode("action_bar_inbox_button", "Messenger", clickable = true))),
        )
        assertEquals("action_bar_inbox_button", InstagramScreens.findMessagesButton(classic)?.viewId)

        val labelOnly = screen(UiNode(null, "Messenger, 3 unread", clickable = true))
        assertEquals("Messenger, 3 unread", InstagramScreens.findMessagesButton(labelOnly)?.desc)

        assertNull(InstagramScreens.findMessagesButton(screen(UiNode(null, "Message requests"))))
    }
}
