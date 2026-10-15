package app.dms

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * The DMs icon: jumps straight into Instagram's Messages once everything is set
 * up, otherwise shows the setup screen. Has no UI of its own.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_UNLOCK) {
            unlockFullInstagram()
            finish()
            return
        }
        val ready = Guard.isInstagramInstalled(this) && Guard.isServiceEnabled(this)
        if (!ready || !Guard.openInbox(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        finish()
    }

    private fun unlockFullInstagram() {
        if (!Guard.unlock(this)) {
            Toast.makeText(this, R.string.no_time_left, Toast.LENGTH_LONG).show()
            return
        }
        val left = Guard.formatRemaining(Guard.remainingTodayMs(this))
        Toast.makeText(this, getString(R.string.unlocked_toast, left), Toast.LENGTH_LONG).show()
        Guard.openInstagramHome(this)
    }

    companion object {
        const val ACTION_UNLOCK = "app.dms.UNLOCK"
    }
}
