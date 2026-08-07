package apps.ijp.coverscreen.launcher.glance_widget

import android.app.Activity
import android.os.Bundle
import android.util.Log
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings
import com.tv.coverscreen.AppUtils
import com.tv.coverscreen.DisplayUtils
import com.tv.coverscreen.Privileged

/**
 * Trampoline for widget row taps.
 *
 * A collection widget only gets one pending intent template, so every row
 * interaction arrives here and is dispatched by its extras:
 *   EXTRA_LETTER        -> ACTION_NAVIGATE_LETTER
 *   EXTRA_ADD_FAVORITE  -> ACTION_ADD_TO_FAVORITES
 *   EXTRA_KEY           -> AppClickAction, launched on the cover display
 *
 * RemoteViews cannot start an activity on another display by itself, which is
 * why the launch path relaunches with ActivityOptions.launchDisplayId.
 */
class WidgetLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        val letter = intent.getStringExtra(WidgetCommon.EXTRA_LETTER)
        if (!letter.isNullOrEmpty()) {
            // tapping the active letter again clears the filter
            if (Nav.letter(this) == letter.uppercase()) Nav.clearLetter(this)
            else Nav.setLetter(this, letter)
            WidgetHost.refreshAll(this)
            done()
            return
        }

        // Rows written before work profile support only carried the package,
        // so fall back to it and let keyFor() resolve the personal profile.
        val pkg = intent.getStringExtra(WidgetCommon.EXTRA_PACKAGE)
        val key = intent.getStringExtra(WidgetCommon.EXTRA_KEY) ?: pkg
        if (key.isNullOrEmpty()) {
            done()
            return
        }

        if (intent.getBooleanExtra(WidgetCommon.EXTRA_ADD_FAVORITE, false)) {
            // The row carries its own label, so the toggle no longer enumerates
            // every launchable app in every profile on the main thread just to
            // recover a name the row already had. applicationContext, because
            // this activity finishes immediately below.
            val label = intent.getStringExtra(WidgetCommon.EXTRA_LABEL) ?: key
            val app = applicationContext
            Thread {
                try {
                    AppsRepository(app).toggleFavorite(key, label)
                    WidgetHost.refreshAll(app)
                } catch (t: Throwable) {
                    Log.e(TAG, "favourite toggle failed for " + key + ": " + t)
                }
            }.start()
            done()
            return
        }

        launch(key, intent.getStringExtra(WidgetCommon.EXTRA_ACTIVITY))
        done()
    }

    private fun launch(key: String, activity: String?) {
        val s = Settings(this)
        val target = when {
            intent.hasExtra(WidgetCommon.EXTRA_DISPLAY) ->
                intent.getIntExtra(WidgetCommon.EXTRA_DISPLAY, -1)
            s.launchOnCover -> DisplayUtils.coverDisplayId(this)
            else -> 0
        }
        val pkg = AppUtils.pkgOfKey(key)
        try {
            if (target > 0 && Privileged.ready()) {
                relay(key, activity, pkg, target)
            } else {
                // One path for both profiles. launchKeyOnDisplay resolves the user
                // from the key and goes through LauncherApps.startMainActivity, so
                // a work row opens the work copy instead of the personal one.
                AppUtils.launchKeyOnDisplay(this, key, activity, if (target >= 0) target else 0)
            }
            Usage.note(this, pkg)
            WidgetHost.refreshAll(this)
        } catch (t: Throwable) {
            Log.e(TAG, "launch " + key + " failed: " + t)
        }
    }

    /**
     * Land an app on the cover panel that the system would otherwise bounce.
     *
     * Samsung refuses to *place* a brand new activity on the cover display
     * unless the caller is its own cover launcher. That refusal is what shows
     * "open phone to continue" and dumps the app on the inner screen. It does
     * not refuse to *move a task that already exists*, which is why the same
     * app opens fine from the recents deck.
     *
     * So the placement is never asked for. The app is started on the inner
     * display, where nothing objects, and the task it produces is resumed onto
     * the cover. The inner panel is off while the phone is folded, so the first
     * step is not visible.
     *
     * Requires Shizuku, because the resume goes through startActivityFromRecents
     * as shell. Without it the caller keeps the old direct launch.
     */
    private fun relay(key: String, activity: String?, pkg: String, target: Int) {
        val app = applicationContext
        Thread {
            try {
                Log.d(TAG, "relay start pkg=" + pkg + " target=" + target + " shizuku=" + Privileged.access())

                // Already running: a resume is the whole job, and it brings the
                // app back exactly where it was left instead of restarting it.
                val open = Privileged.tasks(RELAY_SCAN)?.firstOrNull { it.pkg == pkg }
                Log.d(TAG, "relay existing task=" + open?.taskId + " for " + pkg)
                if (open != null) {
                    val resumed = Privileged.resume(open.taskId, target)
                    Log.d(TAG, "relay resume(existing) taskId=" + open.taskId + " target=" + target + " result=" + resumed)
                    if (resumed) return@Thread
                }

                AppUtils.launchKeyOnDisplay(app, key, activity, 0)
                Log.d(TAG, "relay cold-launched " + pkg + " on display 0, polling for task")

                // The task does not exist the instant the start returns, so the
                // resume is retried until it does rather than fired once and hoped over.
                var waited = 0L
                while (waited < RELAY_TIMEOUT) {
                    Thread.sleep(RELAY_POLL)
                    waited += RELAY_POLL
                    val task = Privileged.tasks(RELAY_SCAN)?.firstOrNull { it.pkg == pkg }
                    if (task != null) {
                        val resumed = Privileged.resume(task.taskId, target)
                        Log.d(TAG, "relay resume(poll) waited=" + waited + " taskId=" + task.taskId + " target=" + target + " result=" + resumed)
                        if (resumed) return@Thread
                    } else {
                        Log.d(TAG, "relay poll waited=" + waited + ": task for " + pkg + " not found yet")
                    }
                }
                Log.w(TAG, "relay to display " + target + " timed out for " + pkg)
            } catch (t: Throwable) {
                Log.e(TAG, "relay failed for " + pkg + ": " + t)
            }
        }.start()
    }

    private fun done() {
        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val TAG = "WidgetLaunch"

        /** How long to keep looking for the task the cold start creates. */
        const val RELAY_TIMEOUT = 2000L
        const val RELAY_POLL = 100L
        const val RELAY_SCAN = 12
    }
}
