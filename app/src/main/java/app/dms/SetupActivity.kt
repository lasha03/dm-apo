package app.dms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible

/** Setup, settings and a live status readout for the focus guard. */
class SetupActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var installButton: Button
    private lateinit var enableButton: Button
    private lateinit var pauseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        status = findViewById(R.id.status)
        diagnostics = findViewById(R.id.diagnostics)
        installButton = findViewById(R.id.install_instagram)
        enableButton = findViewById(R.id.enable_guard)
        pauseButton = findViewById(R.id.pause)

        installButton.setOnClickListener { openInstagramStorePage() }
        enableButton.setOnClickListener { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<View>(R.id.app_info).setOnClickListener {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<View>(R.id.open_messages).setOnClickListener {
            if (!Guard.openInbox(this)) installButton.performClick()
        }
        pauseButton.setOnClickListener {
            when {
                Guard.isUnlocked(this) -> Guard.lock(this)
                Guard.unlock(this) -> Guard.openInstagramHome(this)
            }
            refresh()
        }

        bindSwitch(R.id.block_home, Screen.HOME)
        bindSwitch(R.id.block_reels, Screen.REELS)
        bindSwitch(R.id.block_search, Screen.SEARCH)
        findViewById<Switch>(R.id.limit_shared_reels).apply {
            isChecked = Guard.limitSharedReels(this@SetupActivity)
            setOnCheckedChangeListener { _, checked -> Guard.setLimitSharedReels(this@SetupActivity, checked) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun bindSwitch(id: Int, screen: Screen) {
        val switch = findViewById<Switch>(id)
        switch.isChecked = Guard.isBlocked(this, screen)
        switch.setOnCheckedChangeListener { _, checked -> Guard.setBlocked(this, screen, checked) }
    }

    private fun refresh() {
        val installed = Guard.isInstagramInstalled(this)
        val enabled = Guard.isServiceEnabled(this)
        val running = Guard.isRunning
        val unlocked = Guard.isUnlocked(this)
        val left = Guard.remainingTodayMs(this)

        status.text = getString(
            when {
                !installed -> R.string.status_no_instagram
                !enabled -> R.string.status_guard_off
                !running -> R.string.status_not_running
                unlocked -> R.string.status_unlocked
                else -> R.string.status_ready
            }
        )
        installButton.isVisible = !installed
        enableButton.text = getString(if (enabled) R.string.guard_settings else R.string.enable_guard)
        pauseButton.isVisible = enabled
        pauseButton.isEnabled = unlocked || left > 0
        pauseButton.text = when {
            unlocked -> getString(R.string.lock_again, Guard.formatRemaining(left))
            left > 0 -> getString(R.string.use_full_instagram, Guard.formatRemaining(left))
            else -> getString(R.string.no_time_left)
        }

        val d = Guard.diagnostics(this)
        diagnostics.text = getString(
            R.string.diagnostics,
            getString(if (running) R.string.yes else R.string.no),
            d.lastScreen ?: "–",
            if (d.lastSeenAt > 0) DateUtils.getRelativeTimeSpanString(d.lastSeenAt) else "–",
            d.lastTabs ?: "–",
            d.bounces,
            d.lastBounce ?: "–",
            d.reelPager ?: "–",
        )
    }

    private fun openInstagramStorePage() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${InstagramScreens.PACKAGE}"))
        if (!open(market)) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${InstagramScreens.PACKAGE}")))
        }
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}
