package app.dms

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Keeps the real Instagram app on Messages: once today's allowance is used, the
 * Home feed, Explore/Search, Reels (including reels opened from those or from a
 * profile) send you back by tapping Instagram's own Messages button (or reopening
 * the inbox). A reel someone sent you in a chat plays; swiping on is feed time. It only listens to Instagram (see res/xml/focus_guard.xml), doesn't
 * modify Instagram, and never reads, stores or sends message content.
 */
class FocusGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var checkScheduled = false
    private var lastBounceAt = 0L
    private val recentBounces = ArrayDeque<Long>()
    private val reels = ReelSwipeDetector()
    private val screenContext = ScreenContext()
    private var recordedPager: String? = null

    // Daily allowance meter: runs only while feed-type content is on screen.
    private var meterRunning = false
    private var counting = false
    private var warnedLastMinute = false
    private var wasUsingAllowance = false
    private var lastAllowanceToastAt = 0L

    private val check = Runnable {
        checkScheduled = false
        checkScreen()
    }

    private val meter = object : Runnable {
        override fun run() {
            tickMeter()
            if (meterRunning) handler.postDelayed(this, METER_MS)
        }
    }

    override fun onServiceConnected() {
        Guard.isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != InstagramScreens.PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) onScrolled(event)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // A new screen opened: check right away, so being quick doesn't slip past.
            handler.removeCallbacks(check)
            checkScheduled = true
            handler.post(check)
            return
        }
        // Throttle: Instagram fires many events (e.g. while videos play).
        if (!checkScheduled) {
            checkScheduled = true
            handler.postDelayed(check, CHECK_DELAY_MS)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Guard.isRunning = false
        handler.removeCallbacks(check)
        handler.removeCallbacks(meter)
        meterRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Guard.isRunning = false
        handler.removeCallbacks(check)
        handler.removeCallbacks(meter)
        super.onDestroy()
    }

    private fun checkScreen() {
        val rootInfo = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        if (rootInfo.packageName != InstagramScreens.PACKAGE) return
        val root = snapshot(rootInfo, 0, intArrayOf(MAX_NODES)) ?: return

        val result = InstagramScreens.detect(root)
        Guard.recordSeen(this, result)

        val now = System.currentTimeMillis()
        val kind = screenContext.observe(result.screen, InstagramScreens.isReelViewer(root), InstagramScreens.hasTextInput(root))
        reels.setViewer(kind == ScreenContext.Kind.SharedReel && Guard.limitSharedReels(this), now)

        val limitedBy = (kind as? ScreenContext.Kind.Limited)?.screen
        counting = limitedBy != null && Guard.isBlocked(this, limitedBy)
        if (!counting) {
            // Somewhere free (a chat, the inbox…): any earlier bounce worked.
            wasUsingAllowance = false
            recentBounces.clear()
            return
        }

        val left = Guard.remainingTodayMs(this)
        if (left > 0) {
            // Today's 10 minutes still cover it: let it be, show what's left, start the meter.
            if (!wasUsingAllowance) showAllowanceLeft(left)
            wasUsingAllowance = true
            ensureMeter()
            return
        }
        bounceWithLimits(root, limitedBy ?: result.screen, now)
    }

    private fun bounceWithLimits(root: UiNode, from: Screen, now: Long) {
        if (now - lastBounceAt < COOLDOWN_MS) return
        // Only stops if bouncing keeps failing to get out (the list clears once we're somewhere free).
        while (recentBounces.isNotEmpty() && now - recentBounces.first() > LOOP_WINDOW_MS) recentBounces.removeFirst()
        if (recentBounces.size >= MAX_BOUNCES_PER_WINDOW) return
        lastBounceAt = now
        recentBounces.addLast(now)
        bounce(root, from)
    }

    /** Swiping past the reel you were sent: that's feed time, or back to the chat if none is left. */
    private fun onScrolled(event: AccessibilityEvent) {
        if (!Guard.limitSharedReels(this)) return
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
        if (!swiped) return

        screenContext.markSwipedOn()
        reels.setViewer(false, now)
        val left = Guard.remainingTodayMs(this)
        if (Guard.isBlocked(this, Screen.REELS) && left > 0) {
            counting = true
            wasUsingAllowance = true
            showAllowanceLeft(left)
            ensureMeter()
            return
        }
        if (!Guard.isBlocked(this, Screen.REELS)) return
        lastBounceAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        Guard.recordBounce(this, "shared reel: swiped on, went back")
        Toast.makeText(this, R.string.reel_toast, Toast.LENGTH_SHORT).show()
    }

    private fun showAllowanceLeft(left: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAllowanceToastAt < ALLOWANCE_TOAST_GAP_MS) return
        lastAllowanceToastAt = now
        Toast.makeText(this, getString(R.string.allowance_toast, Guard.formatRemaining(left)), Toast.LENGTH_SHORT).show()
    }

    private fun ensureMeter() {
        if (meterRunning || !Guard.hasAllowanceLeft(this)) return
        meterRunning = true
        handler.postDelayed(meter, METER_MS)
    }

    /**
     * Every few seconds while feed-type content is on screen, spend that time from
     * today's allowance. Stops as soon as you're somewhere free, out of Instagram or
     * the screen is off (it restarts when feed content opens again). Chatting is free.
     */
    private fun tickMeter() {
        val power = getSystemService(android.os.PowerManager::class.java)
        val rootInfo = try { rootInActiveWindow } catch (e: Exception) { null }
        if (!counting || !Guard.hasAllowanceLeft(this) || power?.isInteractive != true ||
            rootInfo?.packageName != InstagramScreens.PACKAGE
        ) {
            meterRunning = false
            return
        }

        val remaining = Guard.addUsage(this, METER_MS)
        when {
            remaining <= 0 -> {
                meterRunning = false
                Toast.makeText(this, R.string.time_up_toast, Toast.LENGTH_LONG).show()
                lastBounceAt = 0L
                checkScreen() // now out of time: this sends you back
            }
            remaining <= 60_000 && !warnedLastMinute -> {
                warnedLastMinute = true
                Toast.makeText(this, R.string.one_minute_toast, Toast.LENGTH_SHORT).show()
            }
            remaining > 60_000 -> warnedLastMinute = false
        }
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
        val bounds = android.graphics.Rect().also(info::getBoundsInScreen)
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
            editable = info.isEditable,
            height = bounds.height(),
            ref = info,
        )
    }

    companion object {
        private const val CHECK_DELAY_MS = 150L
        private const val METER_MS = 5_000L
        private const val ALLOWANCE_TOAST_GAP_MS = 60_000L
        private const val COOLDOWN_MS = 600L
        private const val LOOP_WINDOW_MS = 8_000L
        private const val MAX_BOUNCES_PER_WINDOW = 6
        private const val MAX_NODES = 1_500
        private const val MAX_DEPTH = 40
    }
}
