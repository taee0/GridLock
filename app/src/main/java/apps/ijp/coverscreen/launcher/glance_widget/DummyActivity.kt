package apps.ijp.coverscreen.launcher.glance_widget

import android.app.Activity
import android.os.Bundle

/**
 * Widget related placeholder activity.
 *
 * A widget needs a real activity component to point a PendingIntent at when the
 * tap should do nothing visible. Transparent, not exported, finishes at once.
 */
class DummyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        overridePendingTransition(0, 0)
    }
}
