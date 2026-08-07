package apps.ijp.coverscreen.launcher

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class Notif(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val priority: Int,
    val clearable: Boolean,
    val actions: List<String>
)

/**
 * Watches every posted notification so the cover screen can show them grouped
 * by app, sorted by importance then recency, with actions and clear all.
 */
class LauncherNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "LauncherNotifs"
        const val ACTION_CHANGED = "apps.ijp.coverscreen.launcher.NOTIFS_CHANGED"

        @Volatile
        var connected = false
            private set

        private val live = LinkedHashMap<String, Notif>()

        @Volatile
        private var service: LauncherNotificationListener? = null

        fun all(): List<Notif> = synchronized(live) {
            live.values.sortedWith(
                compareByDescending<Notif> { it.priority }.thenByDescending { it.postTime }
            )
        }

        fun byApp(): Map<String, List<Notif>> = all().groupBy { it.pkg }

        fun dismiss(key: String) {
            service?.cancelNotification(key)
        }

        fun clearAll() {
            service?.cancelAllNotifications()
        }

        fun isEnabled(context: Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }

        fun requestAccess(context: Context) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        service = this
        synchronized(live) {
            live.clear()
            activeNotifications?.forEach { put(it) }
        }
        broadcast()
        Log.d(TAG, "connected, " + live.size + " live")
    }

    override fun onListenerDisconnected() {
        connected = false
        service = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (filtered(sbn)) return
        synchronized(live) { put(sbn) }
        broadcast()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(live) { live.remove(sbn.key) }
        broadcast()
    }

    /** drops ongoing, group summaries and our own noise */
    private fun filtered(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return true
        val n = sbn.notification ?: return true
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && !sbn.isClearable) return true
        return false
    }

    private fun put(sbn: StatusBarNotification) {
        val e = sbn.notification.extras
        live[sbn.key] = Notif(
            sbn.key,
            sbn.packageName,
            e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            (e.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            sbn.postTime,
            sbn.notification.priority,
            sbn.isClearable,
            sbn.notification.actions?.map { it.title?.toString().orEmpty() } ?: emptyList()
        )
    }

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))
    }
}
