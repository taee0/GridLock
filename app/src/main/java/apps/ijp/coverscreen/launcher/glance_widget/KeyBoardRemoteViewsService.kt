package apps.ijp.coverscreen.launcher.glance_widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.tv.coverscreen.R
import apps.ijp.coverscreen.launcher.data.AppEntry
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings

open class KeyBoardRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        KeyBoardRemoteViewsFactory(applicationContext, intent)
}

class KeyBoardRemoteViewsServiceForOverlay : KeyBoardRemoteViewsService()

/**
 * Alphabetical list view. Rows are either a letter header or an app, which is
 * why this factory reports two view types.
 */
class KeyBoardRemoteViewsFactory(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    // There is deliberately no appWidgetId here. The grid factory keeps one
    // because its cell height depends on how much room the host reports it
    // has. This list is full width with rows sized by their own contents, so
    // the panel size tells it nothing, and the field sat unread. The intent
    // stays in the signature because that is how RemoteViewsService hands a
    // factory its binding.

    private val repo = AppsRepository(context)
    private val settings = Settings(context)

    private sealed class Row {
        class Header(val letter: Char) : Row()
        class App(val entry: AppEntry) : Row()
    }

    private val rows = mutableListOf<Row>()

    override fun onCreate() = load()

    override fun onDataSetChanged() = load()

    override fun onDestroy() = rows.clear()

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= rows.size) return loadingView
        return when (val r = rows[position]) {
            is Row.Header -> RemoteViews(context.packageName, R.layout.widget_keyboard_header)
                .apply {
                    setTextViewText(R.id.letter, r.letter.toString())
                    // tapping a letter header jumps to that section, tapping the
                    // active one clears it.
                    val jump = Intent().apply {
                        putExtra(WidgetCommon.EXTRA_LETTER, r.letter.toString())
                    }
                    setOnClickFillInIntent(R.id.letter, jump)
                }
            is Row.App -> {
                // One source for the list icon size, rather than the same
                // expression written out three times and kept in step by hand.
                val iconDp = (settings.iconDp - 12).coerceAtLeast(20)
                val px = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    iconDp.toFloat(),
                    context.resources.displayMetrics
                ).toInt()
                RemoteViews(context.packageName, R.layout.widget_keyboard_item).apply {
                    // Same reason as the grid: the layout can only hold one
                    // fixed icon size, so the view is sized alongside the bitmap.
                    setViewLayoutWidth(
                        R.id.app_icon,
                        iconDp.toFloat(),
                        TypedValue.COMPLEX_UNIT_DIP
                    )
                    setViewLayoutHeight(
                        R.id.app_icon,
                        iconDp.toFloat(),
                        TypedValue.COMPLEX_UNIT_DIP
                    )
                    // The row is a fixed 56dp in the layout while this icon can
                    // reach 68dp, so a large icon was clipped by its own row.
                    // The row grows to fit and never drops below its design
                    // height.
                    setViewLayoutHeight(
                        R.id.app_container,
                        (iconDp + 16).coerceAtLeast(56).toFloat(),
                        TypedValue.COMPLEX_UNIT_DIP
                    )
                    setTextViewText(R.id.app_name, r.entry.name)
                    setTextViewTextSize(
                        R.id.app_name,
                        TypedValue.COMPLEX_UNIT_SP,
                        settings.labelSp.toFloat() + 2f
                    )
                    // Keyed, so the work copy carries the badged work icon.
                    repo.icon(r.entry.key, px)?.let { setImageViewBitmap(R.id.app_icon, it) }
                    setInt(
                        R.id.app_icon,
                        "setImageAlpha",
                        if (r.entry.quiet) QUIET_ALPHA else 255
                    )
                    // Always drawn, for the reason given in the grid factory:
                    // a hidden star cannot be tapped to add a favourite.
                    setInt(
                        R.id.app_star,
                        "setImageAlpha",
                        if (r.entry.favorite) 255 else STAR_IDLE_ALPHA
                    )
                    setContentDescription(
                        R.id.app_star,
                        context.getString(
                            if (r.entry.favorite) R.string.fav_remove
                            else R.string.fav_add
                        )
                    )
                    val fill = Intent().apply {
                        putExtra(WidgetCommon.EXTRA_KEY, r.entry.key)
                        putExtra(WidgetCommon.EXTRA_PACKAGE, r.entry.packageName)
                        putExtra(WidgetCommon.EXTRA_ACTIVITY, r.entry.activity)
                    }
                    setOnClickFillInIntent(R.id.app_container, fill)
                    // ACTION_ADD_TO_FAVORITES, routed through the launch
                    // trampoline because widget rows only get one template
                    val fav = Intent().apply {
                        putExtra(WidgetCommon.EXTRA_KEY, r.entry.key)
                        putExtra(WidgetCommon.EXTRA_PACKAGE, r.entry.packageName)
                        // carried so the toggle does not have to look it up
                        putExtra(WidgetCommon.EXTRA_LABEL, r.entry.name)
                        putExtra(WidgetCommon.EXTRA_ADD_FAVORITE, true)
                    }
                    setOnClickFillInIntent(R.id.app_star, fav)
                }
            }
        }
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_loading_item)

    override fun getViewTypeCount(): Int = 2

    /**
     * The row's own identity, not its slot.
     *
     * Same defect as the grid factory, and more visible here: applying or
     * clearing a letter filter changes the shape of the list completely, so
     * position based ids under hasStableIds() point the host at the wrong row
     * every time.
     */
    override fun getItemId(position: Int): Long =
        if (position >= rows.size) position.toLong()
        else when (val r = rows[position]) {
            // namespaced, so a header can never collide with an app key
            is Row.Header -> ("#" + r.letter).hashCode().toLong()
            is Row.App -> r.entry.key.hashCode().toLong()
        }

    override fun hasStableIds(): Boolean = true

    private fun load() {
        // See the grid factory: the shared list is dropped by the mutations
        // that change it, not by every single load.
        rows.clear()
        val letter = Nav.letter(context)
        // visible() applies the sort setting and this view then threw it away by
        // re-sorting by name, so the sort button moved the subtitle and not the
        // list. Letter headers only make sense in name order, so the sort is
        // skipped rather than run and discarded.
        val hidden = settings.hidden
        val all = repo.all()
            .filter { !hidden.contains(it.key) }
            .sortedBy { it.name.lowercase() }
        val apps = if (letter.isNullOrEmpty()) all
        else all.filter { it.name.firstOrNull()?.uppercaseChar()?.toString() == letter }
        var current: Char? = null
        for (a in apps) {
            val c = a.name.firstOrNull()?.uppercaseChar() ?: '#'
            if (c != current) {
                rows.add(Row.Header(c))
                current = c
            }
            rows.add(Row.App(a))
        }
    }

    private companion object {
        const val QUIET_ALPHA = 115

        /** Opacity of the star on an app that is not a favourite yet. */
        const val STAR_IDLE_ALPHA = 70
    }
}
