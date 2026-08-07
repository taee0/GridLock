package apps.ijp.coverscreen.launcher.glance_widget

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import apps.ijp.coverscreen.launcher.data.AppEntry
import apps.ijp.coverscreen.launcher.data.AppsRepository
import com.tv.coverscreen.R

/**
 * Backs the inline search results list (v0.10), replacing the RecyclerView
 * WidgetSearchActivity used to run in its own Activity. Same query split as
 * that screen and every other search field in the app: repo.visible() for a
 * blank query, repo.search() once there is text, read from SearchState
 * instead of an EditText since RemoteViews cannot host one.
 */
open class SearchRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        SearchRemoteViewsFactory(applicationContext, intent)
}

/** overlay widgets get their own service so the two hosts do not share a factory */
class SearchRemoteViewsServiceForOverlay : SearchRemoteViewsService()

class SearchRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val repo = AppsRepository(context)
    private val shown = mutableListOf<AppEntry>()

    override fun onCreate() = reload()

    /**
     * The host calls this on notifyAppWidgetViewDataChanged, which is exactly
     * what fires after every keypad tap, so this is where the live query is
     * actually re-read and re-ranked -- once per keystroke, same cadence a
     * debounced EditText would settle to, but here each tap is already a
     * discrete event rather than a fast stream that needs coalescing.
     */
    override fun onDataSetChanged() = reload()

    override fun onDestroy() = shown.clear()

    override fun getCount(): Int = shown.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= shown.size) return loadingView
        val app = shown[position]
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, ICON_DP, context.resources.displayMetrics
        ).toInt()

        return RemoteViews(context.packageName, R.layout.launcher_row).apply {
            setTextViewText(
                R.id.app_name,
                if (app.work) context.getString(R.string.work_app, app.name) else app.name
            )
            repo.icon(app.key, px)?.let { setImageViewBitmap(R.id.app_icon, it) }
            setInt(R.id.app_icon, "setImageAlpha", if (app.quiet) QUIET_ALPHA else 255)

            val fill = Intent().apply {
                putExtra(WidgetCommon.EXTRA_KEY, app.key)
                putExtra(WidgetCommon.EXTRA_PACKAGE, app.packageName)
                putExtra(WidgetCommon.EXTRA_ACTIVITY, app.activity)
            }
            setOnClickFillInIntent(R.id.app_container, fill)
        }
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_loading_item)

    override fun getViewTypeCount(): Int = 1

    /** Positions shift with every keystroke, so recycling by slot is not safe. See GridRemoteViewsFactory. */
    override fun getItemId(position: Int): Long =
        if (position < shown.size) shown[position].key.hashCode().toLong() else position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun reload() {
        val query = SearchState.query(context)
        shown.clear()
        shown.addAll(if (query.isBlank()) repo.visible() else repo.search(query))
    }

    private companion object {
        const val ICON_DP = 32f
        const val QUIET_ALPHA = 115
    }
}
