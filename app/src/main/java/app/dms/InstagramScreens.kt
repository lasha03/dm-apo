package app.dms

/**
 * A small, testable snapshot of one node of Instagram's on-screen UI, as seen
 * through Android's accessibility API. Only ids, labels and state are kept:
 * never message text.
 */
class UiNode(
    /** Short resource id, e.g. "feed_tab" (the part after ":id/"). */
    val viewId: String?,
    val desc: String?,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val children: List<UiNode> = emptyList(),
    /** A text field (e.g. the message box in a chat). */
    val editable: Boolean = false,
    /** On-screen height in pixels (0 if unknown). */
    val height: Int = 0,
    /** The live AccessibilityNodeInfo, so the service can click it. Null in tests. */
    val ref: Any? = null,
) {
    var parent: UiNode? = null
        private set

    init {
        children.forEach { it.parent = this }
    }
}

enum class Screen { HOME, SEARCH, REELS, MESSAGES, PROFILE, CREATE, OTHER }

/**
 * Works out which Instagram tab is open from its bottom tab bar, and finds the
 * button that leads to Messages. Resource ids are language-independent and the
 * main signal; English labels are a fallback.
 */
object InstagramScreens {

    const val PACKAGE = "com.instagram.android"

    private val TAB_IDS = mapOf(
        "feed_tab" to Screen.HOME,
        "search_tab" to Screen.SEARCH,
        "explore_tab" to Screen.SEARCH,
        "clips_tab" to Screen.REELS,
        "reels_tab" to Screen.REELS,
        "direct_tab" to Screen.MESSAGES,
        "inbox_tab" to Screen.MESSAGES,
        "profile_tab" to Screen.PROFILE,
        "creation_tab" to Screen.CREATE,
    )

    private val TAB_LABELS = listOf(
        Regex("^home$", RegexOption.IGNORE_CASE) to Screen.HOME,
        Regex("^(search and explore|search|explore)$", RegexOption.IGNORE_CASE) to Screen.SEARCH,
        Regex("^reels$", RegexOption.IGNORE_CASE) to Screen.REELS,
        Regex("^(messages?|direct|messenger|inbox)$", RegexOption.IGNORE_CASE) to Screen.MESSAGES,
        Regex("^profile$", RegexOption.IGNORE_CASE) to Screen.PROFILE,
    )

    private val DM_BUTTON_IDS = listOf(
        "direct_tab", "inbox_tab", "action_bar_inbox_button", "action_bar_direct_button", "direct_inbox_button",
    )

    /** "Messenger", "Direct", "Messages", also with an unread suffix like "Messenger, 3 unread". */
    private val DM_BUTTON_LABEL =
        Regex("^(messenger|direct( messages?)?|messages?|inbox)($|,)", RegexOption.IGNORE_CASE)

    private const val MAX_NODES = 2_000

    data class Result(val screen: Screen, val tabsSeen: List<String>)

    fun detect(root: UiNode): Result {
        val tabs = mutableListOf<Pair<UiNode, Screen>>()
        walk(root) { node -> tabFor(node)?.let { tabs += node to it } }
        val selected = tabs.firstOrNull { (node, _) -> node.selected || anyDescendant(node) { it.selected } }
        val seen = tabs.map { (node, screen) -> node.viewId ?: "label:${screen.name.lowercase()}" }.distinct()
        return Result(selected?.second ?: Screen.OTHER, seen)
    }

    /**
     * True when Instagram's full-screen reel player is on screen (its views are named
     * "clips_…"). Callers combine this with the tab: outside the Reels tab it means a
     * reel someone sent you.
     */
    fun isReelViewer(root: UiNode): Boolean {
        // Must fill most of the screen, so a reel preview bubble inside a chat doesn't count.
        val minHeight = if (root.height > 0) root.height * 3 / 4 else 0
        return find(root) { node ->
            node.viewId?.let { isReelViewId(it) } == true && node.height >= minHeight
        } != null
    }

    /** True if a text field is on screen; a chat always has its message box. */
    fun hasTextInput(root: UiNode): Boolean = find(root) { it.editable } != null

    internal fun isReelViewId(id: String): Boolean {
        val lower = id.lowercase()
        return "clips" in lower && lower !in TAB_IDS && "tab" !in lower && "share" !in lower
    }

    /** The node to tap to get to Messages, or null if none is on screen. */
    fun findMessagesButton(root: UiNode): UiNode? {
        for (id in DM_BUTTON_IDS) {
            find(root) { it.viewId == id }?.let { return it }
        }
        return find(root) { node ->
            node.clickable && node.desc?.trim()?.let { DM_BUTTON_LABEL.containsMatchIn(it) } == true
        }
    }

    private fun tabFor(node: UiNode): Screen? {
        node.viewId?.let { id -> TAB_IDS[id]?.let { return it } }
        val label = node.desc?.trim() ?: return null
        if (!node.clickable) return null
        return TAB_LABELS.firstOrNull { (re, _) -> re.matches(label) }?.second
    }

    private fun walk(root: UiNode, visit: (UiNode) -> Unit) {
        val stack = ArrayDeque<UiNode>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count++ < MAX_NODES) {
            val node = stack.removeLast()
            visit(node)
            for (i in node.children.indices.reversed()) stack.addLast(node.children[i])
        }
    }

    private fun find(root: UiNode, predicate: (UiNode) -> Boolean): UiNode? {
        var found: UiNode? = null
        walk(root) { if (found == null && predicate(it)) found = it }
        return found
    }

    private fun anyDescendant(node: UiNode, predicate: (UiNode) -> Boolean): Boolean {
        var hit = false
        node.children.forEach { child -> walk(child) { if (predicate(it)) hit = true } }
        return hit
    }
}
