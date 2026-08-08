package com.tv.coverscreen.notifs

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.text.format.DateUtils
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import apps.ijp.coverscreen.launcher.LauncherNotificationListener
import apps.ijp.coverscreen.launcher.Notif
import apps.ijp.coverscreen.launcher.NotifAction
import apps.ijp.coverscreen.launcher.NotifGroup
import apps.ijp.coverscreen.launcher.data.Settings
import com.tv.coverscreen.AppUtils
import com.tv.coverscreen.DisplayUtils
import com.tv.coverscreen.R
import com.tv.coverscreen.keyboard.KeyboardOverlay
import kotlin.math.roundToInt

/**
 * The notification holder on the cover panel.
 *
 * The gap this closes is the same shape as the keyboard's. Samsung gives the
 * cover screen a quick-settings pull-down and a notification page, but the page
 * only exists on the cover home screen: once an app is open out there, nothing
 * that arrives is visible. The data was never the problem -- this app has run a
 * NotificationListenerService the whole time -- there was simply no window over
 * other apps to put it in. There is one now.
 *
 * Nothing here reimplements or borrows from Samsung's shade. Everything comes
 * from NotificationListenerService, which is public, documented API, and the
 * PendingIntents it hands over are the same ones the real shade fires.
 *
 * TYPE_ACCESSIBILITY_OVERLAY again, for the same reason as the keyboard: this
 * app is already an accessibility service, so that window type costs no
 * permission and no user-facing grant. The keyboard owns the bottom edge, so
 * this one is anchored to the top and the two never overlap.
 *
 * ## Two states, one window
 *
 *   collapsed - a small tab at the top edge showing who is waiting
 *   open      - the list, staying until dismissed
 *
 * There used to be a third. A peek slid the newest card in by itself and folded
 * away on a timer, and it is gone as of v0.16. It was the one part of this
 * window that took the panel away from you without being asked, which on a
 * cover screen -- where the thing you are looking at is usually the only thing
 * that fits -- is a worse trade than it is on a phone. What a peek was actually
 * for was answering "who is that", so the tab answers it instead: it carries
 * the icons of the apps waiting rather than a bare count, and it bumps and
 * ticks when the set changes. Nothing covers what you were doing.
 *
 * ## Why the list stopped stuttering
 *
 * Everything up to v0.15 called removeAllViews() and re-inflated every row on
 * every notification event. That threw away scroll position, cancelled
 * in-flight swipe animations, and re-decoded every icon, several times a second
 * when a chat was busy. Rows are keyed and reused now, and a render only
 * touches what actually changed. The window itself is no longer torn down and
 * rebuilt each time the last notification clears, either.
 *
 * ## On docking to the control panel
 *
 * v0.14 removed the standing tab and showed the list only while the cover
 * screen's control panel was pulled down. That was withdrawn in v0.15.
 * Recognising the panel by shape matched windows that were not the panel and
 * missed the panel itself, and since the tab had been taken away in the same
 * change, every miss left no way to reach notifications at all.
 *
 * The window enumeration that change introduced is kept below. Launch
 * verification needs it for a narrower and much better posed question.
 */
object NotificationOverlay {

    private const val TAG = "NotifOverlay"

    private const val COLLAPSED = 0
    private const val OPEN = 1

    /**
     * The open list is capped rather than left to wrap_content. The cover panel
     * is short enough that a dozen notifications would otherwise produce a
     * window taller than the screen, and a window that tall cannot be scrolled
     * back up because its top is off the display.
     *
     * v0.15 and earlier used a flat 200dp, picked against one panel and correct
     * only there. This is a fraction of whatever the cover display actually
     * measures, with a floor so a very short panel still shows something, and
     * it is a maximum rather than a fixed height -- two notifications get a
     * two-notification sheet instead of a half-screen one with a hole in it.
     */
    private const val OPEN_FRACTION = 0.62f
    private const val MIN_SHEET_DP = 140f

    /**
     * How long to wait after firing a notification's PendingIntent before
     * checking whether anything actually arrived on the cover display.
     */
    private const val VERIFY_MS = 700L

    /** Relative timestamps go stale; they are refreshed only while open. */
    private const val TICK_MS = 30_000L

    private const val TAB_ICONS = 3
    private const val BUMP_MS = 90L
    private const val JUST_NOW_MS = 60_000L

    /** Marker in the trailer map meaning "this group is open, offer to close". */
    private const val FOLD = -1

    private const val DOT = "\u00B7"

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wm: WindowManager? = null
    @Volatile private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var display = -1
    private var state = COLLAPSED

    /** Live row views by notification key. The reason renders are cheap. */
    private val rows = LinkedHashMap<String, View>()

    /** Packages the user has opened up to see every notification from. */
    private val expanded = HashSet<String>()

    private var tabSig = ""
    private var total = 0
    private var sheet = -1
    private var tick: Runnable? = null

    val showing: Boolean get() = root != null

    /**
     * Listener callbacks are on the main thread from API 24 onward and this app
     * is minSdk 31, so in practice everything already arrives there. The guard
     * is here because the accessibility service is not bound by that promise
     * and a WindowManager call from the wrong thread fails in a way that is
     * very hard to read in a log.
     */
    private fun onMain(body: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) body() else main.post { body() }
    }

    // --------------------------------------------------------------- triggers

    /** Called whenever the listener says something changed. */
    fun sync(svc: AccessibilityService) = onMain {
        if (!Settings(svc).coverNotifications) {
            hideNow()
            return@onMain
        }
        val groups = LauncherNotificationListener.groups()
        // Nothing to show and nothing up: do not build a window just to keep it
        // empty. But once it exists it stays, because tearing it down every
        // time the last notification clears means paying for a full inflate on
        // the arrival of the next one.
        if (groups.isEmpty() && root == null) return@onMain
        if (!ensure(svc)) return@onMain
        render(svc, groups)
    }

    fun toggle(svc: AccessibilityService) = onMain {
        if (!ensure(svc)) return@onMain
        state = if (state == OPEN) COLLAPSED else OPEN
        if (state == COLLAPSED) expanded.clear()
        faces()
        render(svc, LauncherNotificationListener.groups())
        if (state == OPEN) {
            root?.findViewById<ScrollView>(R.id.notif_scroll)?.let { sv ->
                sv.post { sv.scrollTo(0, 0) }
            }
            startTick()
        } else {
            stopTick()
        }
    }

    fun collapse() = onMain { collapseNow() }

    fun hide() = onMain { hideNow() }

    private fun collapseNow() {
        if (root == null || state == COLLAPSED) return
        state = COLLAPSED
        expanded.clear()
        stopTick()
        faces()
        applyGeometry()
    }

    private fun hideNow() {
        val view = root ?: return
        stopTick()
        runCatching { wm?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView", it) }
        root = null
        wm = null
        lp = null
        state = COLLAPSED
        rows.clear()
        expanded.clear()
        tabSig = ""
        total = 0
        sheet = -1
    }

    // ------------------------------------------------------- reading windows
    //
    // Kept from v0.14 minus the panel detection. What is left answers exactly
    // one question -- did the thing we just launched actually appear out here
    // -- and nothing decides where this window sits based on it.

    private fun coverWindows(svc: AccessibilityService): List<AccessibilityWindowInfo> {
        if (display < 0) return emptyList()
        val all = runCatching { svc.windowsOnAllDisplays }.getOrNull() ?: return emptyList()
        return runCatching { all.get(display) }.getOrNull() ?: emptyList()
    }

    private fun pkgOf(w: AccessibilityWindowInfo): String =
        runCatching { w.root?.packageName?.toString() }.getOrNull() ?: ""

    /** True once a window belonging to [pkg] is up on the cover display. */
    private fun landed(svc: AccessibilityService, pkg: String): Boolean =
        coverWindows(svc).any {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && pkgOf(it) == pkg
        }

    // ------------------------------------------------------------- the window

    private fun ensure(svc: AccessibilityService): Boolean {
        if (root != null) return true
        val cover = DisplayUtils.coverDisplay(svc)
        if (cover == null) {
            Log.w(TAG, "no cover display")
            return false
        }
        display = cover.displayId

        val ctx = svc.createDisplayContext(cover)
        val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
        val manager = ctx.getSystemService(WindowManager::class.java) ?: return false
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.notif_overlay, null, false)

        view.findViewById<View>(R.id.notif_tab)?.setOnClickListener {
            buzz(svc)
            toggle(svc)
        }
        view.findViewById<View>(R.id.notif_close)?.setOnClickListener {
            buzz(svc)
            collapseNow()
        }
        view.findViewById<View>(R.id.notif_clear)?.setOnClickListener {
            buzz(svc)
            LauncherNotificationListener.clearAll()
            collapseNow()
        }

        state = COLLAPSED
        rows.clear()
        expanded.clear()
        tabSig = ""
        total = 0
        sheet = -1

        val params = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        var chosen = params
        var added = runCatching { manager.addView(view, params); true }
            .onFailure { Log.w(TAG, "accessibility overlay refused, trying app overlay", it) }
            .getOrDefault(false)

        if (!added) {
            if (!android.provider.Settings.canDrawOverlays(svc)) {
                Log.w(TAG, "no overlay permission either, giving up")
                return false
            }
            val fallback = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            added = runCatching { manager.addView(view, fallback); true }
                .onFailure { Log.w(TAG, "app overlay refused too", it) }
                .getOrDefault(false)
            if (!added) return false
            chosen = fallback
        }

        wm = manager
        root = view
        lp = chosen
        faces()
        Log.d(TAG, "up on display " + display)
        return true
    }

    private fun params(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun faces() {
        val view = root ?: return
        view.findViewById<View>(R.id.notif_full)?.visibility =
            if (state == OPEN) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.notif_tab)?.visibility =
            if (state == COLLAPSED && total > 0) View.VISIBLE else View.GONE
    }

    private fun applyGeometry() {
        val params = lp ?: return
        val view = root ?: return

        val width =
            if (state == OPEN) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT
        val gravity = Gravity.TOP or (if (state == OPEN) Gravity.START else Gravity.END)
        val height =
            if (state == OPEN) sheetHeight(view) else ViewGroup.LayoutParams.WRAP_CONTENT

        var changed = false
        val scroll = view.findViewById<View>(R.id.notif_scroll)
        if (scroll != null && height != sheet) {
            val sp = scroll.layoutParams
            sp.height = height
            scroll.layoutParams = sp
            sheet = height
            changed = true
        }
        if (params.width != width || params.gravity != gravity) {
            params.width = width
            params.gravity = gravity
            changed = true
        }
        if (!changed) return

        runCatching { wm?.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "updateViewLayout", it) }
    }

    /**
     * As tall as the content wants, up to a fraction of the panel.
     *
     * The list is measured directly rather than guessed at. It holds a handful
     * of rows whose text is already set, so this costs almost nothing, and it
     * is the difference between a sheet that fits its contents and one that is
     * always the same size no matter how little is in it.
     */
    private fun sheetHeight(view: View): Int {
        val dm = view.resources.displayMetrics
        val cap = (dm.heightPixels * OPEN_FRACTION).roundToInt()
            .coerceAtLeast((MIN_SHEET_DP * dm.density).roundToInt())
        val list = view.findViewById<LinearLayout>(R.id.notif_list) ?: return cap
        val wanted = runCatching {
            list.measure(
                View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            list.measuredHeight
        }.getOrDefault(0)
        return if (wanted in 1 until cap) wanted else cap
    }

    // ---------------------------------------------------------------- content

    /**
     * Bring the window in line with [groups].
     *
     * This runs whether the holder is open or collapsed, so the list is always
     * correct and opening it is instant. The only things gated on being open
     * are the timestamp ticker and the sheet geometry.
     */
    private fun render(svc: AccessibilityService, groups: List<NotifGroup>) {
        val view = root ?: return

        val was = total
        total = groups.sumOf { it.items.size }
        if (expanded.isNotEmpty()) expanded.retainAll(groups.map { it.pkg }.toSet())
        if (total == 0 && state == OPEN) {
            state = COLLAPSED
            stopTick()
        }

        tab(svc, view, groups, total > was)
        faces()
        list(svc, view, groups)

        view.findViewById<TextView>(R.id.notif_heading)?.text =
            view.resources.getString(R.string.notif_count, total)
        view.findViewById<View>(R.id.notif_empty)?.visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE

        applyGeometry()
    }

    /**
     * The collapsed tab: up to three app icons, then a count if that does not
     * cover everything waiting.
     */
    private fun tab(
        svc: AccessibilityService,
        view: View,
        groups: List<NotifGroup>,
        grew: Boolean,
    ) {
        val bar = view.findViewById<View>(R.id.notif_tab) ?: return
        val sig = groups.joinToString("|") { it.pkg + ":" + it.items.size }
        if (sig == tabSig) return
        tabSig = sig

        val slots = intArrayOf(R.id.notif_tab_a, R.id.notif_tab_b, R.id.notif_tab_c)
        for (i in slots.indices) {
            val slot = view.findViewById<ImageView>(slots[i]) ?: continue
            val group = groups.getOrNull(i)
            if (group == null) {
                slot.setImageDrawable(null)
                slot.visibility = View.GONE
            } else {
                slot.setImageDrawable(LauncherNotificationListener.icon(svc, group.lead))
                slot.visibility = View.VISIBLE
            }
        }

        val count = view.findViewById<TextView>(R.id.notif_tab_count)
        if (count != null) {
            val shown = minOf(groups.size, TAB_ICONS)
            val label = if (total > shown) total.toString() else ""
            count.text = label
            count.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        }
        bar.contentDescription = view.resources.getString(R.string.notif_tab_desc, total)

        // The arrival signal that replaced the peek. A tick and a nudge, in the
        // corner, over nothing.
        if (grew && state == COLLAPSED && total > 0) {
            bump(bar)
            buzz(svc)
        }
    }

    private fun bump(bar: View) {
        bar.animate().cancel()
        bar.scaleX = 1f
        bar.scaleY = 1f
        bar.animate().scaleX(1.14f).scaleY(1.14f).setDuration(BUMP_MS)
            .withEndAction {
                bar.animate().scaleX(1f).scaleY(1f).setDuration(BUMP_MS).start()
            }
            .start()
    }

    /**
     * Reconcile the row views against what should be on screen.
     *
     * Keyed and reused, in place. A notification that is merely updated keeps
     * its view, its position and any gesture in progress; only genuinely new
     * keys are inflated and only departed ones are removed.
     */
    private fun list(svc: AccessibilityService, view: View, groups: List<NotifGroup>) {
        val holder = view.findViewById<LinearLayout>(R.id.notif_list) ?: return
        val inf = LayoutInflater.from(view.context)

        // A group of one is just a row. A bigger group shows its best-ranked
        // member and offers the rest, unless the user has already asked for
        // them, in which case the last row offers to fold it back up.
        val want = LinkedHashMap<String, Notif>()
        val trailer = HashMap<String, Int>()
        for (group in groups) {
            if (group.items.size == 1) {
                want[group.lead.key] = group.lead
            } else if (expanded.contains(group.pkg)) {
                for (n in group.items) want[n.key] = n
                trailer[group.items[group.items.size - 1].key] = FOLD
            } else {
                want[group.lead.key] = group.lead
                trailer[group.lead.key] = group.extra
            }
        }

        for (key in rows.keys.toList()) {
            if (!want.containsKey(key)) rows.remove(key)?.let { holder.removeView(it) }
        }

        var at = 0
        for (entry in want) {
            val key = entry.key
            var row = rows[key]
            if (row == null) {
                row = inf.inflate(R.layout.notif_row, holder, false)
                rows[key] = row
                wire(svc, row)
                holder.addView(row, at)
            } else if (holder.indexOfChild(row) != at) {
                holder.removeView(row)
                holder.addView(row, at)
            }
            bind(svc, row, entry.value, trailer[key])
            at++
        }
    }

    /**
     * Listeners are attached once, when the view is created, and read the
     * current notification off the view at the moment they fire.
     *
     * Older versions captured the Notif in the closure. Because a row is reused
     * when its notification is updated, and because the PendingIntent inside it
     * is replaced when that happens, a captured one goes stale: tapping a chat
     * that had received another message since the row was built fired the
     * previous intent.
     */
    private fun wire(svc: AccessibilityService, row: View) {
        val card = row.findViewById<View>(R.id.nr_card) ?: return

        card.setOnClickListener {
            val n = current(row) ?: return@setOnClickListener
            buzz(svc)
            open(svc, n)
        }
        card.setOnTouchListener(
            Swipe(
                canDismiss = { current(row)?.clearable == true },
                onDismiss = {
                    val n = current(row)
                    if (n != null) {
                        buzz(svc)
                        LauncherNotificationListener.dismiss(n.key)
                    }
                },
            )
        )
        row.findViewById<View>(R.id.nr_dismiss)?.setOnClickListener {
            val n = current(row) ?: return@setOnClickListener
            buzz(svc)
            // Same exit as a swipe, so the two ways of doing this look alike.
            Swipe.collapse(row) { LauncherNotificationListener.dismiss(n.key) }
        }
        row.findViewById<View>(R.id.nr_more)?.setOnClickListener {
            val n = current(row) ?: return@setOnClickListener
            buzz(svc)
            if (!expanded.add(n.pkg)) expanded.remove(n.pkg)
            sync(svc)
        }
    }

    private fun current(row: View): Notif? = row.tag as? Notif

    private fun bind(svc: AccessibilityService, row: View, n: Notif, trailer: Int?) {
        val before = current(row)
        val fresh = before == null || before.key != n.key
        row.tag = n

        val card = row.findViewById<View>(R.id.nr_card)
        if (card != null && fresh) Swipe.reset(card, row)

        val app = LauncherNotificationListener.label(svc, n.pkg)

        if (fresh) {
            row.findViewById<ImageView>(R.id.nr_icon)
                ?.setImageDrawable(LauncherNotificationListener.icon(svc, n))
        }

        row.findViewById<TextView>(R.id.nr_title)?.text =
            if (n.title.isEmpty()) app else n.title

        val body = row.findViewById<TextView>(R.id.nr_text)
        if (body != null) {
            body.text = n.text
            body.visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
        }

        row.findViewById<TextView>(R.id.nr_meta)?.text = meta(app, n)

        val bar = row.findViewById<LinearLayout>(R.id.nr_actions)
        if (bar != null) {
            bar.removeAllViews()
            val inf = LayoutInflater.from(row.context)
            for (a in n.actions) {
                if (a.intent == null) continue
                bar.addView(chip(svc, inf, bar, row, a))
            }
            bar.visibility = if (bar.childCount == 0) View.GONE else View.VISIBLE
        }

        val more = row.findViewById<TextView>(R.id.nr_more)
        if (more != null) {
            if (trailer == null) {
                more.visibility = View.GONE
            } else {
                more.text =
                    if (trailer == FOLD) row.resources.getString(R.string.notif_less)
                    else row.resources.getString(R.string.notif_more, trailer, app)
                more.visibility = View.VISIBLE
            }
        }
    }

    /**
     * "Messages - 4 min. ago".
     *
     * Relative rather than absolute. A clock time makes you do the subtraction
     * yourself, which is a strange thing to ask of someone glancing at a closed
     * phone. Anything under a minute reads as "now" rather than "0 min. ago".
     */
    private fun meta(app: String, n: Notif): String {
        val since = System.currentTimeMillis() - n.postTime
        if (since in 0 until JUST_NOW_MS) {
            val now = root?.resources?.getString(R.string.notif_now).orEmpty()
            return if (now.isEmpty()) app else app + "  " + DOT + "  " + now
        }
        val ago = runCatching {
            DateUtils.getRelativeTimeSpanString(
                n.postTime,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        }.getOrDefault("")
        return if (ago.isEmpty()) app else app + "  " + DOT + "  " + ago
    }

    /**
     * Relative times only stay true if something redraws them, and only matter
     * while they are on screen, so the ticker runs when the list is open and
     * not otherwise. It rewrites one TextView per row and touches nothing else.
     */
    private fun startTick() {
        stopTick()
        val task = object : Runnable {
            override fun run() {
                if (state != OPEN || root == null) return
                retime()
                main.postDelayed(this, TICK_MS)
            }
        }
        tick = task
        main.postDelayed(task, TICK_MS)
    }

    private fun stopTick() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
    }

    private fun retime() {
        val ctx = root?.context ?: return
        for (row in rows.values) {
            val n = current(row) ?: continue
            row.findViewById<TextView>(R.id.nr_meta)?.text =
                meta(LauncherNotificationListener.label(ctx, n.pkg), n)
        }
    }

    /**
     * Open what a notification points at, and make sure it actually opened.
     *
     * The notification's own contentIntent goes first, because it is the only
     * thing that carries the deep link: it opens the conversation, not just the
     * messaging app. But PendingIntent.send() returning without throwing does
     * not mean an activity started. It means the system took delivery. Asking
     * for launchDisplayId from an ordinary app is a request, not an
     * instruction, and when it is refused the refusal is silent and the
     * activity lands on the inner panel, which is folded shut. That is the
     * whole bug: the intent fired, something opened, and it opened somewhere
     * nobody could see.
     *
     * So the result is verified instead of assumed. If nothing belonging to the
     * app is on the cover display shortly afterwards, the launch falls through
     * to LauncherApps.startMainActivity -- the same call the widget and the
     * task switcher use, which places activities correctly because it runs
     * inside system_server with the caller's identity cleared. The deep link is
     * lost in that tier and the app opens at its front door, which beats
     * opening nowhere.
     *
     * The dismiss waits for all of that. A notification that disappears while
     * nothing opens is worse than one that stays put.
     */
    private fun open(svc: AccessibilityService, n: Notif) {
        val fired = LauncherNotificationListener.open(svc, n, display)
        Log.d(TAG, "tap " + n.pkg + " contentIntent=" + fired)
        collapseNow()
        main.postDelayed({
            var ok = landed(svc, n.pkg)
            if (ok) {
                Log.d(TAG, "landed on display " + display + ": " + n.pkg)
            } else {
                Log.w(TAG, "nothing on display " + display + ", relaying " + n.pkg)
                ok = runCatching {
                    AppUtils.launchOnDisplay(
                        svc, n.pkg, null, AppUtils.mySerial(svc), display
                    )
                    true
                }.onFailure { Log.w(TAG, "relay refused", it) }.getOrDefault(false)
            }
            if (ok && n.clearable) LauncherNotificationListener.dismiss(n.key)
            else if (!ok) Log.w(TAG, "could not open " + n.pkg + ", keeping it")
        }, VERIFY_MS)
    }

    /**
     * One action button.
     *
     * A reply is the interesting case. There is no text field anywhere to type
     * into -- the reply exists only as a RemoteInput bundle handed back to the
     * posting app -- so it borrows the cover keyboard in compose mode, which
     * collects keystrokes into its own buffer instead of writing them into
     * another app. Enter delivers it and dismisses the notification.
     */
    private fun chip(
        svc: AccessibilityService,
        inf: LayoutInflater,
        parent: ViewGroup,
        row: View,
        a: NotifAction,
    ): View {
        val b = inf.inflate(R.layout.notif_action, parent, false) as TextView
        b.text = if (a.title.isEmpty()) svc.getString(R.string.notif_action) else a.title
        b.setOnClickListener {
            buzz(svc)
            collapseNow()
            if (a.isReply) {
                KeyboardOverlay.compose(svc, svc.getString(R.string.notif_reply_hint)) { text ->
                    val n = current(row)
                    if (text.isNotBlank() && n != null &&
                        LauncherNotificationListener.reply(svc, a, text)
                    ) {
                        LauncherNotificationListener.dismiss(n.key)
                    }
                }
            } else {
                val sent = LauncherNotificationListener.fire(svc, a, display)
                Log.d(TAG, "action " + a.title + " sent=" + sent)
            }
        }
        return b
    }

    private fun buzz(ctx: Context) {
        if (!Settings(ctx).haptics) return
        runCatching {
            ctx.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }
}
