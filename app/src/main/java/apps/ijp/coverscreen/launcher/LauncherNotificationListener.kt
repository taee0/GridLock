package apps.ijp.coverscreen.launcher

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.LruCache

/**
 * One button on a notification.
 *
 * [inputs] is what separates a reply from an ordinary action. A reply button
 * carries RemoteInput objects describing the text the app expects back, and
 * firing it without filling those in does nothing at all -- which is why the
 * old model, which kept only the button's label, could never have replied.
 */
class NotifAction(
    val title: String,
    val intent: PendingIntent?,
    val inputs: List<RemoteInput>,
) {
    val isReply: Boolean get() = inputs.isNotEmpty()
}

data class Notif(
    val key: String,
    val pkg: String,
    val title: String,
    val text: String,
    val postTime: Long,
    /** The system's own 0-based position for this notification. */
    val rank: Int,
    /** Channel importance, as the user configured it. */
    val importance: Int,
    val ambient: Boolean,
    val clearable: Boolean,
    val actions: List<NotifAction>,
    val contentIntent: PendingIntent?,
    val smallIcon: Icon?,
) {
    /** Background noise: sorted last, never worth interrupting for. */
    val quiet: Boolean
        get() = ambient || importance <= NotificationManager.IMPORTANCE_MIN
}

/** Every live notification from one app, in the order the system ranks them. */
class NotifGroup(val pkg: String, val items: List<Notif>) {
    val lead: Notif get() = items[0]
    val extra: Int get() = items.size - 1
}

/**
 * Watches every posted notification so the cover screen can show them grouped
 * by app, in the system's own order, with actions and reply.
 *
 * Nothing here reads or reimplements Samsung's shade. NotificationListenerService
 * is the public, documented, first-party way to receive posted notifications,
 * and it hands over the same PendingIntents the real shade taps. No decompiling
 * and no privileged access is involved: the user grants notification access
 * once in Settings and that is the whole mechanism.
 *
 * ## Ordering, and why it changed in v0.16
 *
 * Every version up to v0.15 sorted on Notification.priority. That field has
 * been deprecated since API 26, when channel importance replaced it, and most
 * modern apps leave it at PRIORITY_DEFAULT -- but not all of them, so the sort
 * was neither recency nor importance. It was recency with occasional
 * unexplained jumps, and because the list was rebuilt from scratch on every
 * event, those jumps happened under the user's finger.
 *
 * RankingMap is the right answer and was available the whole time. getRank()
 * is the position the system itself has already decided on, taking channel
 * importance, conversation status, People-service signals and the user's own
 * adjustments into account. Reproducing that from a priority int was never
 * going to work. This class now keeps the ranking beside each notification and
 * refreshes it when the system says the ranking changed, which it does without
 * posting or removing anything.
 */
class LauncherNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "LauncherNotifs"
        const val ACTION_CHANGED = "apps.ijp.coverscreen.launcher.NOTIFS_CHANGED"

        /** More than this many buttons will not fit on a cover panel. */
        private const val MAX_ACTIONS = 3

        private val WHITESPACE = Regex("\\s+")

        @Volatile
        var connected = false
            private set

        private val live = LinkedHashMap<String, Notif>()

        @Volatile
        private var service: LauncherNotificationListener? = null

        /**
         * Sorted the way the shade sorts: quiet things last, then the system's
         * own rank, then recency as a tie-break for anything unranked.
         */
        fun all(): List<Notif> = synchronized(live) {
            live.values.sortedWith(
                compareBy<Notif> { if (it.quiet) 1 else 0 }
                    .thenBy { it.rank }
                    .thenByDescending { it.postTime }
            )
        }

        /**
         * The same list folded by app, each group keeping the sorted order and
         * the groups themselves ordered by their best-ranked member.
         *
         * Fifteen separate rows from one chat app is the single worst thing
         * that can happen to a list this size, and it is also the most common.
         */
        fun groups(): List<NotifGroup> {
            val byPkg = LinkedHashMap<String, MutableList<Notif>>()
            for (n in all()) byPkg.getOrPut(n.pkg) { ArrayList() }.add(n)
            return byPkg.map { NotifGroup(it.key, it.value) }
        }

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

        // ------------------------------------------------------------ display
        //
        // Both of these used to run on every row of every render. Loading an
        // Icon crosses into the posting app's resources and getApplicationInfo
        // is a call into system_server; doing either once per row per event, on
        // the main thread, is most of why the list stuttered when notifications
        // were arriving.

        private val labels = HashMap<String, String>()
        private val icons = LruCache<String, Drawable>(24)

        fun label(ctx: Context, pkg: String): String = synchronized(labels) {
            labels.getOrPut(pkg) {
                runCatching {
                    val pm = ctx.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
            }
        }

        /**
         * The notification's own icon, falling back to the app's.
         *
         * Callers get a private copy rather than the cached instance. The same
         * Drawable displayed in the tab and in a row at the same time would
         * share one set of bounds and one alpha between them.
         */
        fun icon(ctx: Context, n: Notif): Drawable? {
            val base = icons.get(n.key) ?: run {
                val loaded = runCatching { n.smallIcon?.loadDrawable(ctx) }.getOrNull()
                    ?: runCatching { ctx.packageManager.getApplicationIcon(n.pkg) }.getOrNull()
                if (loaded != null) icons.put(n.key, loaded)
                loaded
            } ?: return null
            return runCatching { base.constantState?.newDrawable()?.mutate() }.getOrNull() ?: base
        }

        private fun forget(key: String) {
            icons.remove(key)
        }

        // ------------------------------------------------------------- acting

        /**
         * Open what the notification points at, on [displayId].
         *
         * The display is named explicitly, but naming it is a request rather
         * than an instruction: placing an activity on another display from an
         * ordinary app can be refused, and the refusal is silent. So the return
         * value means only that the system accepted delivery of the intent. It
         * does not mean an activity started, and it certainly does not mean one
         * started where it was asked to.
         *
         * Nothing is dismissed here on purpose. Whoever calls this is expected
         * to confirm that something actually appeared before clearing the
         * notification the user was relying on. See NotificationOverlay.open.
         */
        fun open(ctx: Context, n: Notif, displayId: Int): Boolean {
            val pi = n.contentIntent ?: return false
            return send(ctx, pi, null, displayId)
        }

        /** Fire a plain action button. */
        fun fire(ctx: Context, a: NotifAction, displayId: Int): Boolean {
            val pi = a.intent ?: return false
            return send(ctx, pi, null, displayId)
        }

        /**
         * Fire a reply button with [text] attached.
         *
         * RemoteInput results ride in a bundle keyed by each input's resultKey,
         * which is why the keys have to be kept rather than just the label. No
         * launch display is set here on purpose: a reply is answered in place
         * and should not drag an activity onto the panel.
         */
        fun reply(ctx: Context, a: NotifAction, text: String): Boolean {
            val pi = a.intent ?: return false
            if (a.inputs.isEmpty()) return false
            val fill = Intent()
            val values = Bundle()
            for (ri in a.inputs) values.putCharSequence(ri.resultKey, text)
            return runCatching {
                RemoteInput.addResultsToIntent(a.inputs.toTypedArray(), fill, values)
                RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
                pi.send(ctx, 0, fill)
                true
            }.onFailure { Log.w(TAG, "reply refused", it) }.getOrDefault(false)
        }

        private fun send(
            ctx: Context,
            pi: PendingIntent,
            fill: Intent?,
            displayId: Int,
        ): Boolean {
            val opts = ActivityOptions.makeBasic()
            if (displayId >= 0) opts.launchDisplayId = displayId
            return runCatching {
                pi.send(ctx, 0, fill, null, null, null, opts.toBundle())
                true
            }.onFailure { Log.w(TAG, "pending intent refused", it) }.getOrDefault(false)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        service = this
        val map = runCatching { currentRanking }.getOrNull()
        synchronized(live) {
            live.clear()
            // The filter is applied here too. It was not before, so every group
            // summary already on screen at connect time survived until the app
            // reposted it, which is why the list looked duplicated after a
            // reboot and then quietly fixed itself.
            activeNotifications?.forEach { if (!filtered(it)) put(it, map) }
        }
        broadcast()
        Log.d(TAG, "connected, " + live.size + " live")
    }

    override fun onListenerDisconnected() {
        connected = false
        service = null
        super.onListenerDisconnected()
    }

    // The two-argument callbacks are the real ones. The framework's default
    // implementation of each simply drops the RankingMap and calls the
    // one-argument version, so overriding these replaces that path entirely and
    // the single-argument overloads are deliberately not implemented.

    override fun onNotificationPosted(sbn: StatusBarNotification, map: RankingMap?) {
        if (filtered(sbn)) return
        synchronized(live) { put(sbn, map) }
        rerank(map)
        broadcast()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, map: RankingMap?) {
        synchronized(live) { live.remove(sbn.key) }
        forget(sbn.key)
        rerank(map)
        broadcast()
    }

    /**
     * The system re-ranks without anything being posted or removed -- a channel
     * gets muted, a conversation is promoted, Do Not Disturb turns on. Ignoring
     * this was why the order could disagree with the real shade until the next
     * notification happened to arrive.
     */
    override fun onNotificationRankingUpdate(map: RankingMap?) {
        if (rerank(map)) broadcast()
    }

    private fun rerank(map: RankingMap?): Boolean {
        if (map == null) return false
        val r = Ranking()
        var changed = false
        synchronized(live) {
            for (entry in live.entries.toList()) {
                val n = entry.value
                if (!runCatching { map.getRanking(entry.key, r) }.getOrDefault(false)) continue
                if (n.rank == r.rank && n.importance == r.importance && n.ambient == r.isAmbient) {
                    continue
                }
                live[entry.key] = n.copy(
                    rank = r.rank,
                    importance = r.importance,
                    ambient = r.isAmbient,
                )
                changed = true
            }
        }
        return changed
    }

    /** drops ongoing, group summaries and our own noise */
    private fun filtered(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return true
        val n = sbn.notification ?: return true
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && !sbn.isClearable) return true
        return false
    }

    private fun put(sbn: StatusBarNotification, map: RankingMap?) {
        val n = sbn.notification ?: return
        val e = n.extras
        val r = Ranking()
        val ranked = map != null && runCatching { map.getRanking(sbn.key, r) }.getOrDefault(false)

        live[sbn.key] = Notif(
            key = sbn.key,
            pkg = sbn.packageName,
            title = tidy(
                e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?: e.getCharSequence(Notification.EXTRA_TITLE)
            ),
            text = body(e),
            postTime = sbn.postTime,
            rank = if (ranked) r.rank else Int.MAX_VALUE,
            importance = if (ranked) r.importance else NotificationManager.IMPORTANCE_DEFAULT,
            ambient = ranked && r.isAmbient,
            clearable = sbn.isClearable,
            actions = n.actions?.take(MAX_ACTIONS)?.map { a ->
                NotifAction(
                    a.title?.toString().orEmpty(),
                    a.actionIntent,
                    a.remoteInputs?.toList() ?: emptyList()
                )
            } ?: emptyList(),
            contentIntent = n.contentIntent,
            smallIcon = runCatching { n.smallIcon }.getOrNull(),
        )
    }

    /**
     * The one-line form first.
     *
     * Earlier versions preferred EXTRA_BIG_TEXT, which is the expanded body and
     * can run to several paragraphs. On a panel this size that produced rows
     * taller than the window, which is the other half of why the list needed a
     * height cap at all. EXTRA_TEXT is what the real shade shows collapsed and
     * is the right length here; big text is kept only as a fallback for
     * notifications that set nothing else.
     */
    private fun body(e: Bundle): String {
        val lines = runCatching {
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        }.getOrNull()
        return tidy(
            e.getCharSequence(Notification.EXTRA_TEXT)
                ?: lines?.lastOrNull()
                ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )
    }

    /**
     * Collapse runs of whitespace. Notification text arrives with newlines and
     * tabs in it more often than you would expect, and a row that silently
     * grows to four lines because a message contained a line break is the kind
     * of thing that makes a list feel unfinished.
     */
    private fun tidy(cs: CharSequence?): String =
        cs?.toString()?.replace(WHITESPACE, " ")?.trim().orEmpty()

    private fun broadcast() {
        sendBroadcast(Intent(ACTION_CHANGED).setPackage(packageName))
    }
}
