package com.tv.coverscreen

import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * Invisible, one shot, and gone. Its only job is to appear on the cover panel
 * asking for an orientation, which forces the window policy to recompute. You
 * never see it: no layout, no background, no animation, no history.
 */
class Kick : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(false)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)

        val orientation = intent.getIntExtra(
            EXTRA_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        )
        runCatching { requestedOrientation = orientation }

        // The panel is often locked when it is closed, and a locked keyguard
        // will not follow the rotation on its own.
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            runCatching { km.requestDismissKeyguard(this, null) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Never let this thing land on the inner screen.
        if (display?.displayId == Display.DEFAULT_DISPLAY) {
            finishAndRemoveTask()
            return
        }
        // Long enough for the rotation to commit, short enough to never be seen.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, HOLD)
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        const val EXTRA_ORIENTATION = "orientation"
        private const val HOLD = 450L
    }
}
