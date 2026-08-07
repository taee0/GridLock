package apps.ijp.coverscreen.launcher.glance_widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.tv.coverscreen.R
import apps.ijp.coverscreen.launcher.data.AppEntry
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings

open class GridRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        GridRemoteViewsFactory(applicationContext, intent)
}

/** overlay widgets get their own service so the two hosts do not share a factory */
class GridRemoteViewsServiceForOverlay : GridRemoteViewsService()

class GridRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )

    private val repo = AppsRepository(context)
    private val settings = Settings(context)
    private val apps = mutableListOf<AppEntry>()

    /**
     * Height of one grid cell, recomputed on every load.
     *
     * Settings.rows had no reader anywhere in the project. The grid took its
     * height from the item layout, so the rows slider wrote a preference that
     * nothing ever consumed. The cell is sized here so that many rows fill
     * the panel.
     */
    private var rowHeightDp: Int = 0

    override fun onCreate() = loadApps()

    override fun onDataSetChanged() = loadApps()

    override fun onDestroy() = apps.clear()

    override fun getCount(): Int = apps.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position >= apps.size) return loadingView
        val app = apps[position]
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            settings.iconDp.toFloat(),
            context.resources.displayMetrics
        ).toInt()

        return RemoteViews(context.packageName, R.layout.widget_grid_view_item).apply {
            setTextViewText(R.id.app_name, app.name)
            setTextViewTextSize(R.id.app_name, TypedValue.COMPLEX_UNIT_SP, settings.labelSp.toFloat())
            setViewVisibility(
                R.id.app_name,
                if (settings.showLabels) android.view.View.VISIBLE
                else android.view.View.GONE
            )
            // The view is sized here as well as the bitmap. A layout carries one
            // fixed size, so the icon size setting used to change the bitmap and
            // leave the view at the 48dp the layout declares.
            setViewLayoutWidth(
                R.id.app_icon,
                settings.iconDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
            setViewLayoutHeight(
                R.id.app_icon,
                settings.iconDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
            // The one place Settings.rows has any effect on what is drawn.
            setViewLayoutHeight(
                R.id.app_container,
                rowHeightDp.toFloat(),
                TypedValue.COMPLEX_UNIT_DIP
            )
            // Keyed by AppEntry.key, so the work copy gets the work profile's
            // own icon with the system badge instead of the personal one.
            repo.icon(app.key, px)?.let { setImageViewBitmap(R.id.app_icon, it) }
            // A paused work profile dims, the way the system launcher does it.
            setInt(R.id.app_icon, "setImageAlpha", if (app.quiet) QUIET_ALPHA else 255)

            // The star is always drawn. Hiding it for a non favourite also hid
            // the only control that could make it one, so a favourite could be
            // removed from the widget but never added. Opacity carries the
            // state instead.
            setInt(
                R.id.app_star,
                "setImageAlpha",
                if (app.favorite) 255 else STAR_IDLE_ALPHA
            )
            setContentDescription(
                R.id.app_star,
                context.getString(
                    if (app.favorite) R.string.fav_remove else R.string.fav_add
                )
            )
            val fill = Intent().apply {
                putExtra(WidgetCommon.EXTRA_KEY, app.key)
                putExtra(WidgetCommon.EXTRA_PACKAGE, app.packageName)
                putExtra(WidgetCommon.EXTRA_ACTIVITY, app.activity)
            }
            setOnClickFillInIntent(R.id.app_container, fill)
            // ACTION_ADD_TO_FAVORITES on the badge itself
            val fav = Intent().apply {
                putExtra(WidgetCommon.EXTRA_KEY, app.key)
                putExtra(WidgetCommon.EXTRA_PACKAGE, app.packageName)
                // carried so the toggle does not have to look the label up
                putExtra(WidgetCommon.EXTRA_LABEL, app.name)
                putExtra(WidgetCommon.EXTRA_ADD_FAVORITE, true)
            }
            setOnClickFillInIntent(R.id.app_star, fav)
        }
    }

    override fun getLoadingView(): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_loading_item)

    override fun getViewTypeCount(): Int = 1

    /**
     * The app's key, not its slot.
     *
     * Returning the position while also reporting hasStableIds() told the host
     * that whatever sits in slot three is always the same item. After a sort
     * change, a letter filter or a favourite toggle it is not, and the host is
     * entitled to recycle one app's view into another app's row on that
     * promise.
     */
    override fun getItemId(position: Int): Long =
        if (position < apps.size) apps[position].key.hashCode().toLong()
        else position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun loadApps() {
        // No invalidate here. The list is cached for the whole process and is
        // dropped by the things that change it: a package added or removed, and
        // favourite toggles. Clearing it on every load meant the cache could
        // never serve a hit and each refresh re-enumerated the whole device.
        apps.clear()
        apps.addAll(repo.forView())
        // appWidgetId was captured in the constructor and never read once.
        // It is what lets the factory ask the host how much room it has.
        rowHeightDp = WidgetSize.rowHeightDp(
            rows = settings.rows,
            iconDp = settings.iconDp,
            showLabels = settings.showLabels,
            showHeader = settings.showHeader,
            heightDp = WidgetSize.heightDp(WidgetSize.options(context, appWidgetId))
        )
    }

    private companion object {
        const val QUIET_ALPHA = 115

        /** Opacity of the star on an app that is not a favourite yet. */
        const val STAR_IDLE_ALPHA = 70
    }
}
