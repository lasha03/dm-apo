package app.dms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelSwipeDetectorTest {

    private val h = 2400

    private fun opened(at: Long = 0L) = ReelSwipeDetector().apply { setViewer(true, at) }

    @Test fun watchingTheSentReelIsFine() {
        val d = opened()
        assertFalse(d.onScroll("clips_viewer_view_pager", null, 1f, 0, 0, h, 100))
        assertFalse(d.onScroll("clips_viewer_view_pager", null, 1f, 0, 30, h, 2_000))
    }

    @Test fun swipingToNextReelIsDetectedByIndex() {
        val d = opened()
        d.onScroll("clips_viewer_view_pager", null, 1f, 4, 0, h, 100)
        assertFalse(d.onScroll("clips_viewer_view_pager", null, 1f, 4, 0, h, 1_500))
        assertTrue(d.onScroll("clips_viewer_view_pager", null, 1f, 5, 0, h, 1_800))
    }

    @Test fun swipingIsDetectedByDistanceWhenNoIndex() {
        val d = opened()
        assertFalse(d.onScroll("clips_viewer_view_pager", null, 1f, -1, 500, h, 1_000))
        assertTrue(d.onScroll("clips_viewer_view_pager", null, 1f, -1, 400, h, 1_100))
    }

    @Test fun firstSwipeCountsEvenWithoutAStartIndex() {
        // Nothing reported while the reel opened; the very first pager move is a swipe.
        val d = opened()
        assertTrue(d.onScroll("clips_viewer_view_pager", null, 1f, 1, 0, h, 3_000))
    }

    @Test fun commentsAndChatListsAreIgnored() {
        val d = opened()
        assertFalse(d.onScroll("comment_list", null, 0.9f, 3, 2000, h, 2_000))
        assertFalse(d.onScroll("clips_viewer_view_pager", null, 0.5f, 3, 2000, h, 2_000))
        assertFalse(d.onScroll("message_list", "androidx.recyclerview.widget.RecyclerView", 1f, 9, 2000, h, 2_000))
    }

    @Test fun nothingHappensWhenNoSharedReelIsOpen() {
        val d = ReelSwipeDetector()
        assertFalse(d.onScroll("clips_viewer_view_pager", null, 1f, 7, 2000, h, 5_000))
    }

    @Test fun reelViewerRecognised() {
        val viewer = UiNode("root", null, children = listOf(UiNode("clips_viewer_view_pager", null)))
        val chat = UiNode("root", null, children = listOf(UiNode("message_list", null), UiNode("clips_tab", "Reels")))
        assertTrue(InstagramScreens.isReelViewer(viewer))
        assertFalse(InstagramScreens.isReelViewer(chat))
    }
}
