package app.dms

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** Settings, status and shared helpers for the focus guard. */
object Guard {

    private const val PREFS = "guard"
    private const val KEY_PAUSED_UNTIL = "paused_until"
    private const val KEY_LAST_SCREEN = "last_screen"
    private const val KEY_LAST_TABS = "last_tabs"
    private const val KEY_LAST_SEEN_AT = "last_seen_at"
    private const val KEY_BOUNCES = "bounces"
    private const val KEY_LAST_BOUNCE = "last_bounce"
    private const val KEY_LIMIT_SHARED_REELS = "limit_shared_reels"
    private const val KEY_REEL_PAGER = "reel_pager"

    private const val INBOX_URL = "https://www.instagram.com/direct/inbox/"

    /** True while Android has the accessibility service connected (same process as the UI). */
    @Volatile
    var isRunning = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blockKey(screen: Screen) = "block_${screen.name.lowercase()}"

    /** Home feed, Explore/Search and Reels are blocked by default. */
    fun isBlocked(context: Context, screen: Screen): Boolean = when (screen) {
        Screen.HOME, Screen.SEARCH, Screen.REELS -> prefs(context).getBoolean(blockKey(screen), true)
        else -> false
    }

    fun setBlocked(context: Context, screen: Screen, blocked: Boolean) =
        prefs(context).edit().putBoolean(blockKey(screen), blocked).apply()

    /** Watch the reel you were sent, but not swipe on to more. On by default. */
    fun limitSharedReels(context: Context) = prefs(context).getBoolean(KEY_LIMIT_SHARED_REELS, true)

    fun setLimitSharedReels(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_LIMIT_SHARED_REELS, on).apply()

    fun pausedUntil(context: Context): Long = prefs(context).getLong(KEY_PAUSED_UNTIL, 0L)

    fun isPaused(context: Context) = System.currentTimeMillis() < pausedUntil(context)

    fun pause(context: Context, minutes: Int) = prefs(context).edit()
        .putLong(KEY_PAUSED_UNTIL, System.currentTimeMillis() + minutes * 60_000L).apply()

    fun resume(context: Context) = prefs(context).edit().putLong(KEY_PAUSED_UNTIL, 0L).apply()

    fun isInstagramInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(InstagramScreens.PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val me = ComponentName(context, FocusGuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Opens Instagram on the Messages inbox (or just Instagram, and the guard takes it from there). */
    fun openInbox(context: Context): Boolean {
        val candidates = listOfNotNull(
            Intent(Intent.ACTION_VIEW, Uri.parse(INBOX_URL)).setPackage(InstagramScreens.PACKAGE),
            context.packageManager.getLaunchIntentForPackage(InstagramScreens.PACKAGE),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // Try the next way in.
            }
        }
        return false
    }

    // ---- Diagnostics (ids and counters only, never message content) ------------------------

    data class Diagnostics(
        val lastScreen: String?,
        val lastTabs: String?,
        val lastSeenAt: Long,
        val bounces: Int,
        val lastBounce: String?,
        val reelPager: String?,
    )

    fun recordSeen(context: Context, result: InstagramScreens.Result) {
        prefs(context).edit()
            .putString(KEY_LAST_SCREEN, result.screen.name)
            .putString(KEY_LAST_TABS, result.tabsSeen.joinToString(", ").ifEmpty { "none" })
            .putLong(KEY_LAST_SEEN_AT, System.currentTimeMillis())
            .apply()
    }

    fun recordBounce(context: Context, how: String) {
        val p = prefs(context)
        p.edit().putInt(KEY_BOUNCES, p.getInt(KEY_BOUNCES, 0) + 1).putString(KEY_LAST_BOUNCE, how).apply()
    }

    fun recordReelPager(context: Context, pager: String) =
        prefs(context).edit().putString(KEY_REEL_PAGER, pager).apply()

    fun diagnostics(context: Context): Diagnostics {
        val p = prefs(context)
        return Diagnostics(
            p.getString(KEY_LAST_SCREEN, null),
            p.getString(KEY_LAST_TABS, null),
            p.getLong(KEY_LAST_SEEN_AT, 0L),
            p.getInt(KEY_BOUNCES, 0),
            p.getString(KEY_LAST_BOUNCE, null),
            p.getString(KEY_REEL_PAGER, null),
        )
    }
}
