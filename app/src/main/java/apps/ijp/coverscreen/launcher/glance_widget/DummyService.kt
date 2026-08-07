package apps.ijp.coverscreen.launcher.glance_widget

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Widget related background service.
 *
 * Exists so a widget can bind something cheap to keep its host process warm.
 * Deliberately does no work.
 */
class DummyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY
}
