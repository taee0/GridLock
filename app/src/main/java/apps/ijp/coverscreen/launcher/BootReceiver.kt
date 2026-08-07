package apps.ijp.coverscreen.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import apps.ijp.coverscreen.launcher.glance_widget.WidgetHost

/** priority 1000, same as the launcher manifest. refreshes widgets after boot */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("BootReceiver", "got " + intent.action)
        WidgetHost.refreshAll(context)
    }
}
