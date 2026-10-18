package app.dms

import app.dms.ScreenContext.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenContextTest {

    private val chat = Triple(Screen.OTHER, false, true)
    private val inbox = Triple(Screen.MESSAGES, false, false)
    private val profile = Triple(Screen.OTHER, false, false)
    private val viewer = Triple(Screen.OTHER, true, false)

    private fun ScreenContext.see(s: Triple<Screen, Boolean, Boolean>) = observe(s.first, s.second, s.third)

    @Test fun reelOpenedFromAChatIsShared() {
        val c = ScreenContext()
        assertEquals(Kind.Free, c.see(chat))
        assertEquals(Kind.SharedReel, c.see(viewer))
        assertEquals(Kind.SharedReel, c.see(viewer))
        assertEquals(Kind.Free, c.see(chat))
    }

    @Test fun swipingOnMakesItFeedTime() {
        val c = ScreenContext()
        c.see(chat)
        c.see(viewer)
        c.markSwipedOn()
        assertEquals(Kind.Limited(Screen.REELS), c.see(viewer))
    }

    @Test fun reelOpenedFromTheFeedIsFeedTime() {
        val c = ScreenContext()
        assertEquals(Kind.Limited(Screen.HOME), c.observe(Screen.HOME, false, false))
        assertEquals(Kind.Limited(Screen.REELS), c.see(viewer))
    }

    @Test fun reelOpenedFromAProfileOrInboxIsFeedTime() {
        val c = ScreenContext()
        c.see(chat)
        c.see(profile)
        assertEquals(Kind.Limited(Screen.REELS), c.see(viewer))
        c.see(inbox)
        assertEquals(Kind.Limited(Screen.REELS), ScreenContext().apply { see(inbox) }.see(viewer))
    }

    @Test fun newSharedReelAfterReturningToChatIsFreeAgain() {
        val c = ScreenContext()
        c.see(chat)
        c.see(viewer)
        c.markSwipedOn()
        c.see(chat)
        assertEquals(Kind.SharedReel, c.see(viewer))
    }

    @Test fun smallReelPreviewInAChatIsNotAViewer() {
        val bubble = UiNode("root", null, height = 2400, children = listOf(
            UiNode("clips_viewer_container", null, height = 600),
            UiNode("row_thread_composer_edittext", null, editable = true, height = 120),
        ))
        val fullScreen = UiNode("root", null, height = 2400, children = listOf(
            UiNode("clips_viewer_view_pager", null, height = 2400),
        ))
        assertEquals(false, InstagramScreens.isReelViewer(bubble))
        assertEquals(true, InstagramScreens.hasTextInput(bubble))
        assertEquals(true, InstagramScreens.isReelViewer(fullScreen))
    }
}
