package apps.ijp.coverscreen.launcher.glance_widget

import android.content.Context

/** pokes every placed widget so the grid and keyboard reload their data */
object WidgetHost {

    /**
     * push() broadcasts ACTION_APPWIDGET_UPDATE, which arrives at onUpdate and
     * then render(), and render() already calls notifyAppWidgetViewDataChanged.
     * Notifying here as well made every tap reload the list twice for each
     * placed provider, and a reload is a full PackageManager enumeration across
     * every profile. With both providers placed that was four enumerations for
     * one button press.
     */
    fun refreshAll(context: Context) {
        // companion objects are not inherited, so push explicitly per class
        AppLauncherWidgetReceiver.push(context, AppLauncherWidgetReceiver::class.java)
        AppLauncherWidgetReceiver.push(context, AppLauncherWidgetReceiverForOverlays::class.java)
    }
}
