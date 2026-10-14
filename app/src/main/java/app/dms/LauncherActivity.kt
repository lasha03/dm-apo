package app.dms

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The DMs icon: jumps straight into Instagram's Messages once everything is set
 * up, otherwise shows the setup screen. Has no UI of its own.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ready = Guard.isInstagramInstalled(this) && Guard.isServiceEnabled(this)
        if (!ready || !Guard.openInbox(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        finish()
    }
}
