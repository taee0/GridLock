package apps.ijp.coverscreen.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import apps.ijp.coverscreen.launcher.LauncherNotificationListener
import apps.ijp.coverscreen.launcher.data.AppEntry
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings
import apps.ijp.coverscreen.launcher.glance_widget.Usage
import apps.ijp.coverscreen.launcher.glance_widget.WidgetHost
import com.tv.coverscreen.AppUtils
import com.tv.coverscreen.DisplayUtils
import com.tv.coverscreen.IconCache
import com.tv.coverscreen.R
import java.util.concurrent.Executors

/**
 * The launcher itself: grid or list, search, favourites, recents, hidden apps.
 *
 * Everything expensive is off the main thread now. It used to rescan every
 * launchable app across every profile, on the main thread, on every keystroke
 * and every resume, and then decode an icon inside each bind, which is what
 * made the grid stutter. The rules here are worth keeping:
 *
 *  - nothing in [Adapter.onBindViewHolder] may touch PackageManager, the disk
 *    or SharedPreferences. Render settings are read once per [apply] and held
 *    in fields; icons come from the cache or arrive later.
 *  - list changes go through [DiffUtil], so a keystroke moves the rows that
 *    actually changed instead of rebinding the screen.
 */
class LauncherHomeActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var repo: AppsRepository
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private val adapter = Adapter()
    private var badges: Set<String> = emptySet()

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val reload = Runnable { load() }

    /** Bumped per load so a slow scan cannot overwrite a newer one. */
    private var generation = 0

    /** Set when the installed app list might have moved under us. */
    private var dirty = true

    // Read once per apply, not once per row.
    private var viewMode = Settings.VIEW_GRID
    private var columns = 4
    private var showLabels = true
    private var labelSp = 12
    private var iconPx = 0
    private var laidOutFor = ""

    private val notifs = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refreshBadge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        repo = AppsRepository(this)
        setContentView(R.layout.launcher_home)

        list = findViewById(R.id.apps)
        search = findViewById(R.id.search)
        empty = findViewById(R.id.empty)
        list.adapter = adapter
        // The cell size never depends on its contents, so the list can skip a
        // full requestLayout every time the item count changes.
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(12)

        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, LauncherSettingsActivity::class.java))
        }
        findViewById<View>(R.id.mode).setOnClickListener {
            settings.view =
                if (settings.view == Settings.VIEW_KEYBOARD) Settings.VIEW_GRID
                else Settings.VIEW_KEYBOARD
            apply()
        }
        findViewById<View>(R.id.tab_all).setOnClickListener { pick(Settings.VIEW_GRID) }
        findViewById<View>(R.id.tab_fav).setOnClickListener { pick(Settings.VIEW_FAVORITES) }
        findViewById<View>(R.id.tab_recent).setOnClickListener { pick(Settings.VIEW_RECENT) }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            /**
             * Typing outruns the search. Coalesce a burst of keystrokes into
             * one pass instead of starting a ranked match per letter.
             */
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                main.removeCallbacks(reload)
                main.postDelayed(reload, SEARCH_DEBOUNCE)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            notifs,
            IntentFilter(LauncherNotificationListener.ACTION_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
        // Apps can be installed or removed while we are away.
        dirty = true
        apply()
        refreshBadge()
    }

    override fun onPause() {
        main.removeCallbacks(reload)
        try {
            unregisterReceiver(notifs)
        } catch (ignored: Throwable) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(reload)
        io.shutdown()
        super.onDestroy()
    }

    private fun pick(view: Int) {
        settings.view = view
        apply()
    }

    private fun apply() {
        findViewById<View>(R.id.header).visibility =
            if (settings.showHeader) View.VISIBLE else View.GONE
        window.decorView.setBackgroundColor(settings.backgroundColor)

        viewMode = settings.view
        columns = settings.columns
        showLabels = settings.showLabels
        labelSp = settings.labelSp
        iconPx = (settings.iconDp * resources.displayMetrics.density).toInt()

        val keyboard = viewMode == Settings.VIEW_KEYBOARD
        // Swapping the layout manager throws away every view, so only do it
        // when the shape of the list actually changed. Moving between tabs is
        // a content change, not a layout change.
        val shape = keyboard.toString() + "x" + columns
        if (shape != laidOutFor) {
            laidOutFor = shape
            list.layoutManager =
                if (keyboard) LinearLayoutManager(this)
                else GridLayoutManager(this, columns)
            while (list.itemDecorationCount > 0) list.removeItemDecorationAt(0)
            if (!keyboard) list.addItemDecoration(Spacing(dp(12), dp(16)))
            val pad = if (keyboard) 0 else dp(12)
            list.setPadding(pad, dp(12), pad, dp(12))
            list.clipToPadding = false
        }
        tabs()
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** the selected tab has to actually look selected */
    private fun tabs() {
        val map = listOf(
            R.id.tab_all to Settings.VIEW_GRID,
            R.id.tab_fav to Settings.VIEW_FAVORITES,
            R.id.tab_recent to Settings.VIEW_RECENT
        )
        for ((id, view) in map) {
            val t = findViewById<TextView>(id)
            val on = viewMode == view
            t.setTextColor(getColor(if (on) R.color.fg else R.color.dim))
            t.setBackgroundResource(if (on) R.drawable.pill else 0)
        }
    }

    /**
     * Scan, sort and diff on the worker; touch the list on the main thread.
     * [AppsRepository.visible] walks every profile through PackageManager and
     * the recent and frequent sorts read a preference per app, so none of it
     * belongs in front of a finger.
     */
    private fun load() {
        val g = ++generation
        val q = search.text?.toString().orEmpty()
        val rescan = dirty
        dirty = false
        val before = adapter.items
        io.execute {
            if (rescan) repo.invalidate()
            val next = if (q.isNotBlank()) repo.search(q) else repo.forView()
            val diff = DiffUtil.calculateDiff(Diff(before, next))
            main.post {
                if (g != generation) return@post
                adapter.swap(next, diff)
                empty.visibility = if (next.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * A notification landing used to rebind every visible row. Only the apps
     * whose dot changed need touching.
     */
    private fun refreshBadge() {
        val live = LauncherNotificationListener.all()
        val next = live.map { it.pkg }.toSet()
        val moved = (badges - next) + (next - badges)
        badges = next
        adapter.badgesChanged(moved)
        val n = live.size
        findViewById<TextView>(R.id.badge).apply {
            text = if (n > 0) n.toString() else ""
            visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }

    /**
     * Warm icons are a hash lookup and go straight in. Cold ones are a binder
     * call and a rasterise, so they arrive later and only if the row has not
     * been recycled onto a different app in the meantime.
     */
    /** [key] is an AppEntry.key, so the work copy paints its own badged icon. */
    private fun paintIcon(view: ImageView, key: String) {
        val px = iconPx
        view.tag = key
        val warm = IconCache.peek(key, px)
        if (warm != null) {
            view.setImageBitmap(warm)
            return
        }
        view.setImageDrawable(null)
        io.execute {
            val bmp = repo.icon(key, px)
            main.post {
                if (view.tag == key && bmp != null) view.setImageBitmap(bmp)
            }
        }
    }

    private fun launch(entry: AppEntry) {
        if (settings.haptics) list.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val target = if (settings.launchOnCover) DisplayUtils.coverDisplayId(this) else 0
        Usage.note(this, entry.packageName)
        // Carries the profile, so a work row opens the work copy. The old
        // two arg call resolved in our own profile and always opened personal.
        AppUtils.launchOnDisplay(
            this,
            entry.packageName,
            entry.activity,
            entry.userSerial,
            if (target >= 0) target else 0
        )
        WidgetHost.refreshAll(this)
    }

    /** Favourite writes are SQLite. Off the main thread, then reload. */
    private fun star(entry: AppEntry) {
        io.execute {
            repo.toggleFavorite(entry)
            main.post {
                dirty = true
                WidgetHost.refreshAll(this)
                load()
            }
        }
    }

    private fun options(entry: AppEntry) {
        if (settings.haptics) list.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // The entry already knows, so this no longer costs a favourites query
        // on the main thread just to pick a menu label.
        val items = arrayOf(
            getString(if (entry.favorite) R.string.fav_remove else R.string.fav_add),
            getString(R.string.opt_hidden),
            getString(R.string.app_info)
        )
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> star(entry)
                    1 -> {
                        settings.hide(entry.key)
                        dirty = true
                        WidgetHost.refreshAll(this)
                        load()
                    }
                    2 -> startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + entry.packageName)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<AppEntry> = emptyList()
            private set

        fun swap(next: List<AppEntry>, diff: DiffUtil.DiffResult) {
            items = next
            diff.dispatchUpdatesTo(this)
        }

        fun badgesChanged(packages: Set<String>) {
            if (packages.isEmpty()) return
            for (i in items.indices) {
                if (items[i].packageName in packages) notifyItemChanged(i, PAYLOAD_BADGE)
            }
        }

        // The row and the cell are different layouts, so they are different
        // view types. Told that, RecyclerView keeps two pools and a mode switch
        // stops handing a grid cell back as a list row.
        override fun getItemViewType(position: Int) =
            if (viewMode == Settings.VIEW_KEYBOARD) R.layout.launcher_row
            else R.layout.launcher_cell

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
            payloads: MutableList<Any>,
        ) {
            if (payloads.contains(PAYLOAD_BADGE)) {
                val e = items[position]
                holder.dot.visibility =
                    if (badges.contains(e.packageName)) View.VISIBLE else View.GONE
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = items[position]
            holder.label.text = e.name
            holder.label.visibility =
                if (showLabels || viewMode == Settings.VIEW_KEYBOARD) View.VISIBLE
                else View.GONE
            holder.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp.toFloat())
            // Writing layout params always schedules a layout pass, so only
            // write them when the size really changed.
            val lp = holder.icon.layoutParams
            if (lp.width != iconPx) {
                lp.width = iconPx
                lp.height = iconPx
                holder.icon.layoutParams = lp
            }
            paintIcon(holder.icon, e.key)
            // a paused work profile dims, the way the system launcher does it
            holder.icon.alpha = if (e.quiet) QUIET_ALPHA else 1f
            holder.star.visibility = if (e.favorite) View.VISIBLE else View.GONE
            holder.dot.visibility =
                if (badges.contains(e.packageName)) View.VISIBLE else View.GONE
        }

        override fun getItemCount() = items.size
    }

    /** Listeners are bound once per holder, not allocated once per bind. */
    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.app_icon)
        val label: TextView = v.findViewById(R.id.app_name)
        val dot: View = v.findViewById(R.id.dot)
        val star: View = v.findViewById(R.id.app_star)

        init {
            v.setOnClickListener { entry()?.let { e -> launch(e) } }
            v.setOnLongClickListener {
                entry()?.let { e -> options(e) }
                true
            }
            star.setOnClickListener { entry()?.let { e -> star(e) } }
        }

        private fun entry(): AppEntry? = adapter.items.getOrNull(bindingAdapterPosition)
    }

    private class Diff(
        private val old: List<AppEntry>,
        private val next: List<AppEntry>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size

        override fun getNewListSize() = next.size

        override fun areItemsTheSame(a: Int, b: Int) =
            old[a].key == next[b].key

        override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
    }

    /**
     * Grid spacing: 12dp horizontal, 16dp vertical. A RecyclerView has no
     * spacing property so it has to come from a decoration.
     */
    private class Spacing(private val h: Int, private val v: Int) :
        RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.left = h / 2
            outRect.right = h / 2
            outRect.top = v / 2
            outRect.bottom = v / 2
        }
    }

    private companion object {
        const val PAYLOAD_BADGE = "badge"

        /** paused work profile apps dim, matching the system launcher */
        const val QUIET_ALPHA = 0.45f

        /** Long enough to swallow a burst of typing, short enough to feel live. */
        const val SEARCH_DEBOUNCE = 120L
    }
}
