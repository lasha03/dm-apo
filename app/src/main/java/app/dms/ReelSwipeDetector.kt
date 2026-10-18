package app.dms

import kotlin.math.abs

/**
 * Lets you watch the reel someone sent you, but notices when you swipe on to the
 * next one. Fed with scroll events from Instagram's reel player; pure logic so it
 * can be unit tested.
 */
class ReelSwipeDetector {

    private var active = false
    private var since = 0L
    private var startIndex = -1
    private var travelled = 0

    /** Last scroll source that looked like the reel pager (for the status screen). */
    var lastPager: String? = null
        private set

    /** Called whenever we learn whether a shared reel is on screen. */
    fun setViewer(open: Boolean, now: Long) {
        if (open && !active) {
            since = now
            startIndex = -1
            travelled = 0
        }
        active = open
    }

    /**
     * Returns true when this scroll moved to a different reel.
     *
     * @param heightFraction source height / screen height (the pager fills the screen; comments don't)
     * @param fromIndex first visible item, or -1 if the view doesn't report it
     * @param deltaY scrolled pixels for this event (0 if unknown)
     */
    fun onScroll(
        sourceId: String?,
        className: String?,
        heightFraction: Float,
        fromIndex: Int,
        deltaY: Int,
        screenHeight: Int,
        now: Long,
    ): Boolean {
        if (!active || heightFraction < MIN_HEIGHT_FRACTION || !isPager(sourceId, className)) return false
        lastPager = sourceId ?: className
        if (now - since < SETTLE_MS) {
            // Opening animation: just learn which reel we started on.
            if (fromIndex >= 0) startIndex = fromIndex
            return false
        }
        if (fromIndex >= 0) {
            // No start learned while opening means this scroll *is* you moving on.
            return startIndex < 0 || fromIndex != startIndex
        }
        travelled += abs(deltaY)
        return screenHeight > 0 && travelled > screenHeight / 3
    }

    private fun isPager(sourceId: String?, className: String?): Boolean {
        val id = sourceId?.lowercase().orEmpty()
        if ("comment" in id || "sheet" in id) return false
        return "clips" in id || "viewer" in id || "pager" in id ||
            className?.contains("ViewPager") == true
    }

    companion object {
        private const val MIN_HEIGHT_FRACTION = 0.8f
        private const val SETTLE_MS = 500L
    }
}
