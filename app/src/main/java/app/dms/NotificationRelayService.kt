package app.dms

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Mirrors Instagram DM notifications as DMs notifications, using Android's
 * official notification-access API (the same one smartwatch apps use).
 *
 * Instagram's push messages still arrive the normal way, through the Instagram
 * app or through Chrome with instagram.com notifications allowed. This service
 * only reacts when Android hands it one of those notifications: it reposts the
 * DM, opening this app when tapped, and removes the original. It never runs on
 * its own, so it costs no extra battery. Nothing is logged or sent anywhere.
 */
class NotificationRelayService : NotificationListenerService() {

    override fun onListenerConnected() {
        isRunning = true
        prefs(this).edit().putLong(PREF_CONNECTED_AT, System.currentTimeMillis()).apply()
        // Replace anything Instagram posted while we weren't running.
        try { activeNotifications?.forEach(::onNotificationPosted) } catch (e: Exception) {}
    }

    override fun onListenerDisconnected() {
        isRunning = false
        rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        sourceOf(sbn) ?: return
        val outcome = handle(sbn)
        // Diagnostics only: a counter and an outcome code, never notification content.
        val p = prefs(this)
        p.edit()
            .putInt(PREF_SEEN_COUNT, p.getInt(PREF_SEEN_COUNT, 0) + 1)
            .putLong(PREF_LAST_SEEN_AT, System.currentTimeMillis())
            .putString(PREF_LAST_OUTCOME, outcome)
            .apply()
    }

    private fun handle(sbn: StatusBarNotification): String {
        val n = sbn.notification
        // Calls and progress notifications ("uploading…") are left to Instagram.
        if (isCall(n)) return "left alone (call)"
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return "left alone (ongoing)"

        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            // Instagram's "N new messages" bundle header: drop it, we post our own.
            return dismiss(sbn) ?: "removed summary"
        }

        val content = extract(n) ?: Content(getString(R.string.app_name), getString(R.string.notif_new_message))
        val posted = MainActivity.isInForeground || post(sbn.key, content)
        val dismissError = dismiss(sbn)
        return when {
            !posted -> "couldn't post (DMs notifications blocked?)"
            dismissError != null -> "relayed, but $dismissError"
            else -> "relayed"
        }
    }

    private enum class Source { INSTAGRAM_APP, BROWSER }

    /**
     * Every Instagram notification except calls is relayed: guessing which ones are
     * DMs proved unreliable, and the setup asks you to turn the rest off in Instagram.
     */
    private fun isCall(n: Notification): Boolean =
        n.category == Notification.CATEGORY_CALL || "call" in n.channelId.orEmpty().lowercase()

    private fun sourceOf(sbn: StatusBarNotification): Source? = when (sbn.packageName) {
        in INSTAGRAM_PACKAGES -> Source.INSTAGRAM_APP
        in BROWSER_PACKAGES -> if (mentionsInstagram(sbn.notification)) Source.BROWSER else null
        else -> null
    }

    /** Browsers show the site's origin (e.g. "instagram.com") in the notification. */
    private fun mentionsInstagram(n: Notification): Boolean {
        val extras = n.extras ?: return false
        return listOf(
            Notification.EXTRA_SUB_TEXT, Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
            Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT,
        ).any { extras.getCharSequence(it)?.contains("instagram.com", ignoreCase = true) == true }
    }


    private data class Content(val title: String, val text: String)

    private fun extract(n: Notification): Content? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val extras = n.extras
        val title = style?.conversationTitle?.toString()
            ?: last?.person?.name?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = last?.text?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        val sender = last?.person?.name?.toString()
        val body = if (style?.conversationTitle != null && sender != null) "$sender: $text" else text
        return Content(title.orEmpty().ifBlank { getString(R.string.app_name) }, body.orEmpty())
    }

    private fun post(key: String, content: Content): Boolean {
        if (!canPost(this)) return false
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hidden = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_new_message))
            .build()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.icon_bg))
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            NotificationManagerCompat.from(this).notify(TAG, key.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            false // Notification permission was revoked.
        }
    }

    /** Removes Instagram's original. Returns an error description, or null on success. */
    private fun dismiss(sbn: StatusBarNotification): String? {
        if (!sbn.isClearable) return "original can't be cleared"
        return try {
            cancelNotification(sbn.key)
            null
        } catch (e: Exception) {
            "couldn't remove original"
        }
    }

    companion object {
        private const val CHANNEL_ID = "messages"
        private const val TAG = "relay"
        private const val GROUP = "dms_messages"

        private val INSTAGRAM_PACKAGES = setOf("com.instagram.android", "com.instagram.lite")
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.sec.android.app.sbrowser", "com.microsoft.emmx", "com.brave.browser",
            "org.mozilla.firefox", "com.opera.browser",
        )

        private const val PREF_CONNECTED_AT = "relay_connected_at"
        private const val PREF_SEEN_COUNT = "relay_seen_count"
        private const val PREF_LAST_SEEN_AT = "relay_last_seen_at"
        private const val PREF_LAST_OUTCOME = "relay_last_outcome"

        /** True while Android has the listener connected (same process as the UI). */
        @Volatile
        var isRunning = false
            private set

        data class Diagnostics(val seen: Int, val lastSeenAt: Long, val lastOutcome: String?)

        fun diagnostics(context: Context): Diagnostics {
            val p = prefs(context)
            return Diagnostics(p.getInt(PREF_SEEN_COUNT, 0), p.getLong(PREF_LAST_SEEN_AT, 0L), p.getString(PREF_LAST_OUTCOME, null))
        }

        private fun prefs(context: Context) = context.getSharedPreferences("relay", Context.MODE_PRIVATE)


        /** Xiaomi and others sometimes stop listeners in the background; ask Android to reconnect. */
        fun rebind(context: Context) {
            if (!isAccessGranted(context)) return
            try {
                requestRebind(ComponentName(context, NotificationRelayService::class.java))
            } catch (e: Exception) {}
        }

        fun isAccessGranted(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun canPost(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        fun accessSettingsIntent(context: Context): Intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, NotificationRelayService::class.java).flattenToString(),
                )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH)
            )
        }

        /** Clears relayed notifications, e.g. once the inbox is open. */
        fun clearAll(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.activeNotifications.filter { it.tag == TAG }.forEach { nm.cancel(TAG, it.id) }
        }
    }
}
