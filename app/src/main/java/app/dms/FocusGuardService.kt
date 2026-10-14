package app.dms

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Keeps the real Instagram app on Messages: when the Home feed, Explore/Search
 * or Reels tab is open, it taps Instagram's own Messages button (or reopens the
 * inbox). It only listens to Instagram (see res/xml/focus_guard.xml), doesn't
 * modify Instagram, and never reads, stores or sends message content.
 */
class FocusGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var checkScheduled = false
    private var lastBounceAt = 0L
    private val recentBounces = ArrayDeque<Long>()
    private val reels = ReelSwipeDetector()
    private var recordedPager: String? = null

    private val check = Runnable {
        checkScheduled = false
        checkScreen()
    }

    override fun onServiceConnected() {
        Guard.isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != InstagramScreens.PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) onScrolled(event)
        // Throttle: Instagram fires many events (e.g. while videos play); check at most ~4x/second.
        if (!checkScheduled) {
            checkScheduled = true
            handler.postDelayed(check, CHECK_DELAY_MS)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Guard.isRunning = false
        handler.removeCallbacks(check)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Guard.isRunning = false
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private fun checkScreen() {
        val rootInfo = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (rootInfo.packageName != InstagramScreens.PACKAGE) return
        val root = snapshot(rootInfo, 0, intArrayOf(MAX_NODES)) ?: return

        val result = InstagramScreens.detect(root)
        Guard.recordSeen(this, result)

        // A reel player outside the Reels/Home/Explore tabs is a reel someone sent you.
        val sharedReel = result.screen != Screen.REELS && result.screen != Screen.HOME &&
            result.screen != Screen.SEARCH && InstagramScreens.isReelViewer(root)
        reels.setViewer(sharedReel, System.currentTimeMillis())

        if (Guard.isPaused(this) || !Guard.isBlocked(this, result.screen)) return
        val now = System.currentTimeMillis()
        if (now - lastBounceAt < COOLDOWN_MS) return
        // Never fight the user in a tight loop if something unexpected keeps us bouncing.
        while (recentBounces.isNotEmpty() && now - recentBounces.first() > LOOP_WINDOW_MS) recentBounces.removeFirst()
        if (recentBounces.size >= MAX_BOUNCES_PER_WINDOW) return

        lastBounceAt = now
        recentBounces.addLast(now)
        bounce(root, result.screen)
    }

    /** Swiping from the reel you were sent to the next one: go back to the chat instead. */
    private fun onScrolled(event: AccessibilityEvent) {
        if (!Guard.limitSharedReels(this) || Guard.isPaused(this)) return
        val source = try { event.source } catch (e: Exception) { null } ?: return
        val bounds = android.graphics.Rect().also(source::getBoundsInScreen)
        val screenHeight = resources.displayMetrics.heightPixels
        val deltaY = if (android.os.Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else 0
        val now = System.currentTimeMillis()
        val swiped = reels.onScroll(
            sourceId = source.viewIdResourceName?.substringAfter(":id/"),
            className = event.className?.toString(),
            heightFraction = if (screenHeight > 0) bounds.height().toFloat() / screenHeight else 0f,
            fromIndex = event.fromIndex,
            deltaY = deltaY,
            screenHeight = screenHeight,
            now = now,
        )
        reels.lastPager?.takeIf { it != recordedPager }?.let {
            recordedPager = it
            Guard.recordReelPager(this, it)
        }
        if (!swiped || now - lastBounceAt < COOLDOWN_MS) return

        lastBounceAt = now
        reels.setViewer(false, now)
        performGlobalAction(GLOBAL_ACTION_BACK)
        Guard.recordBounce(this, "shared reel: swiped on, went back")
        Toast.makeText(this, R.string.reel_toast, Toast.LENGTH_SHORT).show()
    }

    private fun bounce(root: UiNode, from: Screen) {
        var node = InstagramScreens.findMessagesButton(root)
        while (node != null) {
            val info = node.ref as? AccessibilityNodeInfo
            if (info != null && info.isClickable && info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Guard.recordBounce(this, "${from.name}: tapped ${node.viewId ?: node.desc ?: "Messages"}")
                return
            }
            node = node.parent
        }
        if (Guard.openInbox(this)) {
            Guard.recordBounce(this, "${from.name}: reopened inbox")
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        Guard.recordBounce(this, "${from.name}: pressed back")
    }

    /** Copies the visible part of Instagram's UI tree: ids, labels and state only. */
    private fun snapshot(info: AccessibilityNodeInfo?, depth: Int, budget: IntArray): UiNode? {
        if (info == null || depth > MAX_DEPTH || budget[0]-- <= 0) return null
        if (!info.isVisibleToUser) return null
        val children = ArrayList<UiNode>(info.childCount)
        for (i in 0 until info.childCount) {
            snapshot(info.getChild(i), depth + 1, budget)?.let(children::add)
        }
        return UiNode(
            viewId = info.viewIdResourceName?.substringAfter(":id/"),
            desc = info.contentDescription?.toString(),
            selected = info.isSelected,
            clickable = info.isClickable,
            children = children,
            ref = info,
        )
    }

    companion object {
        private const val CHECK_DELAY_MS = 250L
        private const val COOLDOWN_MS = 1_200L
        private const val LOOP_WINDOW_MS = 8_000L
        private const val MAX_BOUNCES_PER_WINDOW = 4
        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 40
    }
}
