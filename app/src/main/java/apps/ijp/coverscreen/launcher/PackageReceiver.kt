package apps.ijp.coverscreen.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.glance_widget.WidgetHost

/** app installed, removed or changed means the widget list is stale */
class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The app list is cached for the whole process now, so it has to be
        // dropped here. Previously every load re-enumerated, which made the
        // refresh alone enough.
        AppsRepository.invalidateAll()
        WidgetHost.refreshAll(context)
    }
}
