package apps.ijp.coverscreen.launcher.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import com.tv.coverscreen.R
import apps.ijp.coverscreen.launcher.glance_widget.WidgetCommon
import com.tv.coverscreen.AppUtils
import com.tv.coverscreen.DisplayUtils

/**
 * Some apps refuse to open over the lock screen. This asks the keyguard to
 * dismiss, then continues the pending launch.
 */
class RequestUnlockActivity : Activity() {

    private var pending: String? = null
    private var pendingActivity: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A locked work profile is exactly the case this screen exists for,
        // so it has to keep the profile identity, not just the package.
        pending = intent.getStringExtra(WidgetCommon.EXTRA_KEY)
            ?: intent.getStringExtra(WidgetCommon.EXTRA_PACKAGE)
        pendingActivity = intent.getStringExtra(WidgetCommon.EXTRA_ACTIVITY)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            go()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = go()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun go() {
        val key = pending
        if (key != null) {
            val id = DisplayUtils.coverDisplayId(this)
            AppUtils.launchKeyOnDisplay(this, key, pendingActivity, if (id >= 0) id else 0)
        }
        finish()
    }
}
