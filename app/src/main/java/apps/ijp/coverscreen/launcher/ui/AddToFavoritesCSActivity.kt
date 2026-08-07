package apps.ijp.coverscreen.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import apps.ijp.coverscreen.launcher.data.AppDatabase
import apps.ijp.coverscreen.launcher.data.AppEntry
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings
import apps.ijp.coverscreen.launcher.glance_widget.WidgetHost
import com.tv.coverscreen.IconCache
import com.tv.coverscreen.R
import java.util.concurrent.Executors

/**
 * Pick which apps are pinned, and in which order.
 *
 * This used to be a stock multi choice AlertDialog listing every installed app
 * as plain text. Four things were wrong with it, and all four are fixed here.
 *
 *  1. The favourites cap was documented but never enforced. You could tick
 *     forty apps and AppsRepository.favorites() would silently take() the
 *     first favoriteMax, so the extra ticks looked accepted and did nothing.
 *  2. Every tick wrote to SQLite immediately, on the main thread, which meant
 *     the Cancel path still kept every change. Edits are staged in memory now
 *     and only reach the database on Save.
 *  3. AppsRepository has a ranked fuzzy search that nothing called. The search
 *     field is wired straight to it.
 *  4. Favourite order was insert order with no way to change it, even though
 *     AppDatabase.reorder() was sitting there unused. The chosen strip is a
 *     drag to reorder list and Save rewrites positions from it.
 *
 * Plain Activity, not AppCompatActivity, for the same reason ConfigActivity is:
 * the theme is android:Theme.Translucent.NoTitleBar.Fullscreen and AppCompat
 * throws when handed a theme that is not one of its own.
 */
@SuppressLint("NotifyDataSetChanged")
class AddToFavoritesCSActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val requery = Runnable { query(search.text?.toString().orEmpty()) }

    /**
     * Bumped per query so a slow search cannot overwrite a newer one.
     *
     * Every keystroke used to start its own io.execute with nothing ordering
     * the results, so a slower earlier query could land after a faster later
     * one and leave the list showing matches for a prefix the user had already
     * typed past. The home screen's field has guarded against this since it
     * was written; this one never did.
     */
    private var generation = 0

    private lateinit var repo: AppsRepository
    private lateinit var count: TextView
    private lateinit var hint: TextView
    private lateinit var search: EditText
    private lateinit var strip: RecyclerView
    private lateinit var list: RecyclerView

    private lateinit var chosenAdapter: ChosenAdapter
    private lateinit var appAdapter: AppAdapter
    private lateinit var dragger: ItemTouchHelper

    /** Staged pick order. Nothing reaches the database until Save. */
    private val chosen = ArrayList<AppEntry>()
    private var shown = ArrayList<AppEntry>()
    private var max = 12
    private var iconPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            windowAnimations = 0
        }
        setContentView(R.layout.favorites_picker)

        repo = AppsRepository(this)
        max = Settings(this).favoriteMax
        val density = resources.displayMetrics.density
        iconPx = (ICON_DP * density).toInt()

        count = findViewById(R.id.count)
        hint = findViewById(R.id.hint)
        search = findViewById(R.id.search)
        strip = findViewById(R.id.chosen)
        list = findViewById(R.id.list)

        // The cover panel has a cutout and the theme lays out under it, so pad
        // by the safe insets rather than trusting the window to be rectangular.
        val pad = (PAD_DP * density).toInt()
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(safe.left + pad, safe.top + pad, safe.right + pad, safe.bottom + pad)
            insets
        }

        chosenAdapter = ChosenAdapter()
        strip.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        strip.adapter = chosenAdapter

        appAdapter = AppAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = appAdapter

        // Drag sideways to reorder the chosen strip. No swipe directions: a
        // swipe would collide with the horizontal scroll of the strip itself.
        dragger = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun onMove(
                recycler: RecyclerView,
                holder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = holder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                chosen.add(to, chosen.removeAt(from))
                chosenAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        dragger.attachToRecyclerView(strip)

        findViewById<TextView>(R.id.cancel).setOnClickListener { finish() }
        findViewById<TextView>(R.id.save).setOnClickListener { save() }

        search.addTextChangedListener(object : TextWatcher {
            /**
             * Coalesce a burst of typing into one ranked pass, the way the
             * home screen's field already does. Searching per keystroke meant
             * filtering and scoring every installed app once per letter.
             */
            override fun afterTextChanged(text: Editable?) {
                main.removeCallbacks(requery)
                main.postDelayed(requery, SEARCH_DEBOUNCE)
            }

            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        // the pending debounce can outlive the activity, and it would run a
        // search and then touch an adapter belonging to a dead window
        main.removeCallbacks(requery)
        io.shutdown()
    }

    /**
     * The app list is a PackageManager scan and the sort can hit usage stats,
     * so it never runs on the main thread. Favourites resolve against all()
     * rather than visible(), otherwise a favourite that is also hidden would
     * quietly vanish from the strip and get dropped on the next Save.
     */
    private fun load() {
        io.execute {
            val visible = repo.visible()
            val everything = repo.all().associateBy { it.key }
            val saved = AppDatabase.get(this).favorites().mapNotNull { everything[it.packageName] }
            main.post {
                chosen.clear()
                chosen.addAll(saved.take(max))
                shown = ArrayList(visible)
                chosenAdapter.notifyDataSetChanged()
                appAdapter.notifyDataSetChanged()
                paint()
            }
        }
    }

    private fun query(text: String) {
        val g = ++generation
        io.execute {
            val next = if (text.isBlank()) repo.visible() else repo.search(text)
            main.post {
                // a slower earlier query must not overwrite a newer one
                if (g != generation) return@post
                shown = ArrayList(next)
                appAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun paint() {
        count.text = getString(R.string.fav_count, chosen.size, max)
        hint.setText(if (chosen.isEmpty()) R.string.fav_none_chosen else R.string.fav_reorder)
    }

    private fun toggle(entry: AppEntry) {
        val at = chosen.indexOfFirst { it.key == entry.key }
        if (at >= 0) {
            chosen.removeAt(at)
            chosenAdapter.notifyItemRemoved(at)
        } else {
            if (chosen.size >= max) {
                Toast.makeText(this, getString(R.string.fav_full, max), Toast.LENGTH_SHORT).show()
                return
            }
            chosen.add(entry)
            chosenAdapter.notifyItemInserted(chosen.size - 1)
            strip.scrollToPosition(chosen.size - 1)
        }
        appAdapter.notifyDataSetChanged()
        paint()
    }

    /**
     * One pass, off the main thread: drop what was removed, insert what is new,
     * then rewrite every position from the staged order. add() parks a row at
     * count(), which leaves gaps after deletes, so reorder() runs last and
     * makes positions contiguous. That is the order the launcher grid and the
     * home screen widget both read back.
     */
    private fun save() {
        val order = chosen.map { it.key to it.name }
        io.execute {
            val db = AppDatabase.get(this)
            val had = db.favorites().map { it.packageName }.toSet()
            val keep = order.map { it.first }.toSet()
            for (pkg in had) if (!keep.contains(pkg)) db.deleteByPackage(pkg)
            for (pair in order) if (!had.contains(pair.first)) db.add(pair.first, pair.second)
            db.reorder(order.map { it.first })
            repo.invalidate()
            main.post {
                WidgetHost.refreshAll(this)
                finish()
            }
        }
    }

    /**
     * Icons decode on the io thread and land back through a tag check, so a
     * recycled row cannot end up wearing the previous app icon.
     */
    /** [key] is an AppEntry.key, so the work copy paints its own badged icon. */
    private fun paintIcon(view: ImageView, key: String) {
        view.setImageDrawable(null)
        view.tag = key
        io.execute {
            val bitmap = IconCache.get(this, key, iconPx)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    private inner class ChosenAdapter : RecyclerView.Adapter<ChosenAdapter.Holder>() {

        override fun getItemCount() = chosen.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.favorites_chip, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(chosen[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: ImageView = view.findViewById(R.id.app_icon)
            private val name: TextView = view.findViewById(R.id.app_name)

            init {
                view.setOnClickListener {
                    val at = bindingAdapterPosition
                    if (at >= 0) toggle(chosen[at])
                }
                view.setOnLongClickListener {
                    dragger.startDrag(this)
                    true
                }
            }

            fun bind(entry: AppEntry) {
                name.text = labelOf(entry)
                paintIcon(icon, entry.key)
            }
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.launcher_row, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(shown[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: ImageView = view.findViewById(R.id.app_icon)
            private val name: TextView = view.findViewById(R.id.app_name)
            private val star: ImageView = view.findViewById(R.id.app_star)

            init {
                view.setOnClickListener {
                    val at = bindingAdapterPosition
                    if (at >= 0) toggle(shown[at])
                }
            }

            fun bind(entry: AppEntry) {
                name.text = labelOf(entry)
                val picked = chosen.any { it.key == entry.key }
                star.visibility = View.VISIBLE
                star.alpha = if (picked) 1f else UNPICKED_STAR
                paintIcon(icon, entry.key)
            }
        }
    }

    /** work rows are suffixed, because the badge alone is easy to miss */
    private fun labelOf(entry: AppEntry): String =
        if (entry.work) getString(R.string.work_app, entry.name) else entry.name

    private companion object {
        /**
         * Matches LauncherHomeActivity: long enough to swallow a burst of
         * typing, short enough to still feel live.
         */
        const val SEARCH_DEBOUNCE = 120L

        const val ICON_DP = 44f
        const val PAD_DP = 12f
        const val UNPICKED_STAR = 0.22f
    }
}
