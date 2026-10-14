package app.dms

/**
 * Decides where a URL is allowed to open. Pure Kotlin (no Android types) so it
 * can be unit tested on the JVM.
 */
object UrlPolicy {

    const val INBOX_URL = "https://www.instagram.com/direct/inbox/"

    enum class Action {
        /** Load it in the current WebView. */
        ALLOW,
        /** Home feed / explore / etc: send the user back to the inbox. */
        INBOX,
        /** Shared post, reel, story or profile: open in the full-screen viewer. */
        VIEWER,
        /** Not Instagram/Facebook: hand off to the system browser. */
        EXTERNAL,
    }

    enum class ViewerAction { ALLOW, OPEN_IN_MAIN, CLOSE, EXTERNAL }

    /** First path segments that stay inside the DMs window (DMs + login flows). */
    private val ALLOWED_SEGMENTS = setOf(
        "direct", "accounts", "challenge", "auth_platform", "two_factor", "oauth",
        "login", "logout", "session", "api", "ajax", "graphql", "call", "consent",
        "privacy", "emails", "nux", "web",
    )

    /** First path segments that are feed-style pages we never want to show. */
    private val FEED_SEGMENTS = setOf("explore", "create", "feed", "home")

    private val VIEWER_SEGMENTS = setOf("p", "reel", "reels", "tv", "stories")

    /** instagram.com hosts whose paths are filtered segment by segment. */
    private val PATH_RULED_HOSTS = setOf("instagram.com", "www.instagram.com", "m.instagram.com")

    /** Other hosts that are part of logging in or opening DM links. */
    private val ALLOWED_HOSTS = setOf(
        "i.instagram.com", "accountscenter.instagram.com", "ig.me", "www.ig.me",
    )

    private val LOGIN_DOMAINS = listOf("facebook.com", "fb.com", "meta.com", "messenger.com")

    private val CDN_DOMAINS = listOf("cdninstagram.com", "fbcdn.net")

    private val LINK_SHIMS = setOf("l.instagram.com", "l.facebook.com", "lm.facebook.com")

    private val META_APP_SCHEMES = setOf("instagram", "instagram-stories", "fb", "fb-messenger", "fbapi", "fbauth2")

    /**
     * True if this would hand off to the Instagram/Facebook/Messenger apps. The website
     * tries this ("open in app") once the Instagram app is installed; DMs never allows it.
     */
    fun isMetaAppLink(scheme: String?, targetPackage: String?): Boolean {
        if (scheme?.lowercase() in META_APP_SCHEMES) return true
        val pkg = targetPackage?.lowercase() ?: return false
        return pkg.startsWith("com.instagram.") || pkg.startsWith("com.facebook.")
    }

    fun isPathRuledInstagramHost(host: String?): Boolean =
        host != null && host.lowercase() in PATH_RULED_HOSTS

    /** True for any instagram.com host (used for permission and bridge checks). */
    fun isInstagramHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "instagram.com" || h.endsWith(".instagram.com")
    }

    /** l.instagram.com/?u=... style redirectors wrapping outbound links. */
    fun isLinkShim(host: String?): Boolean = host != null && host.lowercase() in LINK_SHIMS

    fun decide(host: String?, path: String?): Action {
        val h = host?.lowercase()?.trimEnd('.') ?: return Action.EXTERNAL
        if (h in PATH_RULED_HOSTS) return decideInstagramPath(path)
        if (h in ALLOWED_HOSTS) return Action.ALLOW
        if (LOGIN_DOMAINS.any { matchesDomain(h, it) }) return Action.ALLOW
        if (CDN_DOMAINS.any { matchesDomain(h, it) }) return Action.VIEWER
        return Action.EXTERNAL
    }

    fun decideInViewer(host: String?, path: String?): ViewerAction =
        when (decide(host, path)) {
            Action.INBOX -> ViewerAction.CLOSE
            Action.EXTERNAL -> ViewerAction.EXTERNAL
            Action.VIEWER -> ViewerAction.ALLOW
            Action.ALLOW ->
                if (isPathRuledInstagramHost(host) && segments(path).firstOrNull() == "direct") {
                    ViewerAction.OPEN_IN_MAIN
                } else {
                    ViewerAction.ALLOW
                }
        }

    private fun decideInstagramPath(path: String?): Action {
        val segs = segments(path)
        val first = segs.firstOrNull() ?: return Action.INBOX
        return when {
            first in ALLOWED_SEGMENTS -> Action.ALLOW
            first in FEED_SEGMENTS && segs.size == 1 -> Action.INBOX
            first == "reels" && segs.size == 1 -> Action.INBOX
            first in VIEWER_SEGMENTS -> Action.VIEWER
            // Profiles (/username/), hashtags, locations, anything else on Instagram.
            else -> Action.VIEWER
        }
    }

    private fun segments(path: String?): List<String> =
        path.orEmpty().split('/').filter { it.isNotEmpty() }.map { it.lowercase() }

    private fun matchesDomain(host: String, domain: String) =
        host == domain || host.endsWith(".$domain")
}

object TitleParser {
    private val UNREAD = Regex("""^\((\d+)\+?\)""")

    /** Instagram titles look like "(3) Instagram" when there is something unread. */
    fun unreadCount(title: String?): Int =
        title?.trim()?.let { UNREAD.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
}
