package apps.ijp.coverscreen.launcher.glance_widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import apps.ijp.coverscreen.launcher.data.AppsRepository
import apps.ijp.coverscreen.launcher.data.Settings
import apps.ijp.coverscreen.launcher.ui.LauncherSettingsActivity
import com.tv.coverscreen.R
import com.tv.coverscreen.keyboard.Keys

/**
 * Cover screen app launcher widget.
 */
open class AppLauncherWidgetReceiver : AppWidgetProvider() {

    protected open val gridService: Class<*> get() = GridRemoteViewsService::class.java
    protected open val keyboardService: Class<*> get() = KeyBoardRemoteViewsService::class.java
    protected open val searchService: Class<*> get() = SearchRemoteViewsService::class.java

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { render(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // This used to re-render at the old size. render() read the column
        // count straight out of settings and never consulted the bundle, so
        // resizing the widget rebuilt exactly the layout it already had.
        // render() now clamps against the reported width, and it ends in
        // notifyAppWidgetViewDataChanged, so the factory recomputes its row
        // height in the same pass.
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val s = Settings(context)
        when (intent.action) {
            WidgetCommon.ACTION_TOGGLE_HOME -> {
                s.view = Settings.VIEW_GRID
                Nav.clearLetter(context)
            }
            WidgetCommon.ACTION_TOGGLE_KEYBOARD -> {
                s.view = if (s.view == Settings.VIEW_KEYBOARD) Settings.VIEW_GRID
                else Settings.VIEW_KEYBOARD
                Nav.clearLetter(context)
            }
            WidgetCommon.ACTION_TOGGLE_RECENTS ->
                s.view = if (s.view == Settings.VIEW_RECENT) Settings.VIEW_GRID
                else Settings.VIEW_RECENT
            WidgetCommon.ACTION_TOGGLE_FAVORITES ->
                s.view = if (s.view == Settings.VIEW_FAVORITES) Settings.VIEW_GRID
                else Settings.VIEW_FAVORITES
            WidgetCommon.ACTION_TOGGLE_SEARCH -> {
                // Replaces the old WidgetSearchActivity entry point. Search is
                // now a view like grid/list/recent/favourites rather than a
                // separate screen, so leaving it clears the typed query the
                // same way switching away from the list clears its letter.
                s.view = if (s.view == Settings.VIEW_SEARCH) Settings.VIEW_GRID
                else Settings.VIEW_SEARCH
                SearchState.clear(context)
            }
            WidgetCommon.ACTION_SEARCH_KEY ->
                SearchState.append(context, intent.getStringExtra(WidgetCommon.EXTRA_LETTER) ?: "")
            WidgetCommon.ACTION_SEARCH_BACKSPACE -> SearchState.backspace(context)
            WidgetCommon.ACTION_SEARCH_SPACE -> SearchState.append(context, " ")
            WidgetCommon.ACTION_SORT_ALPHA -> s.sort = Settings.SORT_ALPHA
            WidgetCommon.ACTION_SORT_RECENT -> s.sort = Settings.SORT_RECENT
            WidgetCommon.ACTION_SORT_FREQUENT -> s.sort = Settings.SORT_FREQUENT
            WidgetCommon.ACTION_SORT_CUSTOM -> s.sort = Settings.SORT_CUSTOM
            WidgetCommon.ACTION_TOGGLE_NAV_HEADER -> s.showHeader = !s.showHeader
            WidgetCommon.ACTION_NAVIGATE_LETTER ->
                Nav.setLetter(context, intent.getStringExtra(WidgetCommon.EXTRA_LETTER))
            WidgetCommon.ACTION_ADD_TO_FAVORITES -> {
                // EXTRA_KEY tells the two profiles apart; EXTRA_PACKAGE is
                // the fallback for rows built before work profile support.
                val key = intent.getStringExtra(WidgetCommon.EXTRA_KEY)
                    ?: intent.getStringExtra(WidgetCommon.EXTRA_PACKAGE)
                if (key != null) {
                    // The row carries its own label, so this no longer walks
                    // every launchable app in every profile to find one entry.
                    // onReceive is on the main thread, so the database write and
                    // the refresh run on a worker held open by goAsync.
                    val label = intent.getStringExtra(WidgetCommon.EXTRA_LABEL) ?: key
                    val app = context.applicationContext
                    val pending = goAsync()
                    Thread {
                        try {
                            AppsRepository(app).toggleFavorite(key, label)
                            WidgetHost.refreshAll(app)
                        } catch (t: Throwable) {
                            Log.e(TAG, "favourite toggle failed for " + key + ": " + t)
                        } finally {
                            pending.finish()
                        }
                    }.start()
                }
                // the worker refreshes when it is done, so do not refresh twice
                return
            }
            WidgetCommon.ACTION_REFRESH -> Unit
            else -> return
        }
        WidgetHost.refreshAll(context)
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
        val s = Settings(context)
        val v = RemoteViews(context.packageName, R.layout.widget_layout)
        val keyboard = s.view == Settings.VIEW_KEYBOARD
        val searching = s.view == Settings.VIEW_SEARCH

        // background colour honouring the transparency option
        val bg = Color.argb(
            s.widgetAlpha,
            Color.red(s.backgroundColor),
            Color.green(s.backgroundColor),
            Color.blue(s.backgroundColor)
        )
        // Tint rather than replace. setBackgroundColor swapped the whole
        // drawable out, which threw away the 22dp corners and hairline edge
        // that widget_bg draws.
        v.setColorStateList(
            R.id.widget_root,
            "setBackgroundTintList",
            ColorStateList.valueOf(bg)
        )

        v.setViewVisibility(R.id.widget_header, if (s.showHeader) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.widget_title, context.getString(viewTitle(s.view)))
        v.setTextViewText(R.id.widget_sub, subtitle(context, s))

        // Way back out of a hidden toolbar.
        //
        // The title is what toggles the header, but the title lives inside the
        // header, so hiding it used to take away the only control that could
        // bring it back, and the choice is persisted. The subtitle sits outside
        // the header and is always drawn, so it takes the toggle over whenever
        // the header is gone.
        v.setOnClickPendingIntent(
            R.id.widget_sub,
            if (s.showHeader) null else self(context, WidgetCommon.ACTION_TOGGLE_NAV_HEADER)
        )
        // Columns and icon size are independent settings drawing on one fixed
        // panel. Six 80dp icons could be asked for in a space that fits three,
        // and the grid clipped rather than saying so. The request is reduced to
        // what the host reports it has.
        val panel = WidgetSize.options(context, id)
        v.setInt(
            R.id.grid_view,
            "setNumColumns",
            WidgetSize.columns(s.columns, s.iconDp, WidgetSize.widthDp(panel))
        )

        v.setViewVisibility(R.id.grid_content, if (searching) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.search_panel, if (searching) View.VISIBLE else View.GONE)
        // Moved out of the keypad row (v0.10.1): this is the clear/close
        // control, not an enter key, so it now sits next to the query text it
        // actually acts on instead of reading like one more letter key.
        v.setViewVisibility(R.id.search_clear, if (searching) View.VISIBLE else View.GONE)

        val service = Intent(context, if (keyboard) keyboardService else gridService).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        v.setRemoteAdapter(R.id.grid_view, service)
        v.setEmptyView(R.id.grid_view, R.id.widget_empty)

        // tap template for rows, filled in by the factory. Reused as-is below
        // for search_results: the template only names WidgetLaunchActivity,
        // the row supplies the launch extras via setOnClickFillInIntent, so
        // one PendingIntent covers both collections.
        val fill = Intent(context, WidgetLaunchActivity::class.java)
        val launchTemplate = PendingIntent.getActivity(
            context, id, fill,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        v.setPendingIntentTemplate(R.id.grid_view, launchTemplate)

        // Inline search results, live filtered by whatever SearchState holds.
        val searchIntent = Intent(context, searchService).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        v.setRemoteAdapter(R.id.search_results, searchIntent)
        v.setEmptyView(R.id.search_results, R.id.search_empty)
        v.setPendingIntentTemplate(R.id.search_results, launchTemplate)

        // The keypad is 28 fixed buttons, not a collection: it never scrolls
        // or reorders, so there is nothing an adapter's recycling would buy
        // over binding each key its own PendingIntent once per render.
        SEARCH_KEYS.forEach { (char, viewId) ->
            v.setOnClickPendingIntent(viewId, searchKeyIntent(context, char))
        }
        v.setOnClickPendingIntent(R.id.key_backspace, self(context, WidgetCommon.ACTION_SEARCH_BACKSPACE))
        v.setOnClickPendingIntent(R.id.key_space, self(context, WidgetCommon.ACTION_SEARCH_SPACE))
        v.setOnClickPendingIntent(R.id.search_clear, self(context, WidgetCommon.ACTION_TOGGLE_SEARCH))

        v.setOnClickPendingIntent(R.id.widget_home, self(context, WidgetCommon.ACTION_TOGGLE_HOME))
        v.setOnClickPendingIntent(R.id.widget_mode, self(context, WidgetCommon.ACTION_TOGGLE_KEYBOARD))
        v.setOnClickPendingIntent(R.id.widget_recents, self(context, WidgetCommon.ACTION_TOGGLE_RECENTS))
        v.setOnClickPendingIntent(R.id.widget_favorites, self(context, WidgetCommon.ACTION_TOGGLE_FAVORITES))
        // Used to launch WidgetSearchActivity as a separate full screen
        // Activity, which is why v0.9.1 had to give it its own task just so
        // the system back button would not drop users into the app's task
        // underneath. Search is now just another view toggle like
        // home/mode/recents/favorites above: it never leaves the widget, so
        // there is no separate task and nothing for back to fall into.
        v.setOnClickPendingIntent(R.id.widget_search, self(context, WidgetCommon.ACTION_TOGGLE_SEARCH))

        // Which view you are actually in was readable only from the title text.
        // All four view buttons looked identically active in every view. They
        // now carry the state as well, using the same lit/dimmed convention the
        // sort button already had.
        val lit = context.getColor(R.color.fg)
        val dimmed = context.getColor(R.color.dim)
        v.setInt(R.id.widget_home, "setColorFilter", if (s.view == Settings.VIEW_GRID) lit else dimmed)
        v.setInt(R.id.widget_mode, "setColorFilter", if (s.view == Settings.VIEW_KEYBOARD) lit else dimmed)
        v.setInt(R.id.widget_recents, "setColorFilter", if (s.view == Settings.VIEW_RECENT) lit else dimmed)
        v.setInt(R.id.widget_favorites, "setColorFilter", if (s.view == Settings.VIEW_FAVORITES) lit else dimmed)
        v.setInt(R.id.widget_search, "setColorFilter", if (searching) lit else dimmed)

        // The list view is sectioned A to Z, so it cannot honour a sort. The
        // button used to stay lit and change the subtitle while the list did
        // not move. It is dimmed and inert there instead.
        val sortless = keyboard || searching
        v.setInt(R.id.widget_sort, "setColorFilter", if (sortless) dimmed else lit)
        v.setOnClickPendingIntent(
            R.id.widget_sort,
            if (sortless) null
            else self(context, nextSort(s.sort, s.customOrder.isNotEmpty()))
        )
        v.setOnClickPendingIntent(
            R.id.widget_settings,
            PendingIntent.getActivity(
                context, 0,
                Intent(context, LauncherSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        v.setOnClickPendingIntent(
            R.id.widget_title,
            self(context, WidgetCommon.ACTION_TOGGLE_NAV_HEADER)
        )

        mgr.updateAppWidget(id, v)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.grid_view)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.search_results)
    }

    /**
     * Custom order is only in the cycle when there is one to go back to.
     * Without the [custom] guard the button would advance into a sort that
     * orders nothing, and the old `else` branch meant custom could be
     * displayed by the subtitle but never reached by the button.
     */
    private fun nextSort(current: Int, custom: Boolean) = when (current) {
        Settings.SORT_ALPHA -> WidgetCommon.ACTION_SORT_RECENT
        Settings.SORT_RECENT -> WidgetCommon.ACTION_SORT_FREQUENT
        Settings.SORT_FREQUENT ->
            if (custom) WidgetCommon.ACTION_SORT_CUSTOM
            else WidgetCommon.ACTION_SORT_ALPHA
        else -> WidgetCommon.ACTION_SORT_ALPHA
    }

    private fun viewTitle(view: Int) = when (view) {
        Settings.VIEW_KEYBOARD -> R.string.view_keyboard
        Settings.VIEW_RECENT -> R.string.view_recent
        Settings.VIEW_FAVORITES -> R.string.view_favorites
        Settings.VIEW_SEARCH -> R.string.search_title
        else -> R.string.view_grid
    }

    private fun subtitle(context: Context, s: Settings): String {
        if (s.view == Settings.VIEW_SEARCH) {
            val query = SearchState.query(context)
            val base = query.ifEmpty { context.getString(R.string.search_hint) }
            return if (s.showHeader) base
            else base + "  \u00b7  " + context.getString(R.string.widget_show_toolbar)
        }
        val sort = context.getString(
            when (s.sort) {
                Settings.SORT_RECENT -> R.string.sort_recent
                Settings.SORT_FREQUENT -> R.string.sort_frequent
                Settings.SORT_CUSTOM -> R.string.sort_custom
                else -> R.string.sort_alpha
            }
        )
        val letter = Nav.letter(context)
        val base = if (letter.isNullOrEmpty()) sort else sort + "  " + letter
        // With the header hidden this line is the only route back to it, so it
        // has to say so rather than leaving the toolbar looking lost.
        return if (s.showHeader) base
        else base + "  \u00b7  " + context.getString(R.string.widget_show_toolbar)
    }

    private fun self(context: Context, action: String): PendingIntent {
        val i = Intent(context, javaClass).setAction(action)
        return PendingIntent.getBroadcast(
            context, action.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * One PendingIntent per letter key, not a shared template. These are
     * static buttons, not a collection's rows, so there is no
     * setOnClickFillInIntent to carry a per-key extra. FLAG_UPDATE_CURRENT
     * replaces the extras of whatever PendingIntent already matches action +
     * requestCode, so every key needs a requestCode beyond the action's own
     * hash -- otherwise all 26 would collide into one PendingIntent and every
     * key would type whichever letter was bound last.
     */
    private fun searchKeyIntent(context: Context, char: Char): PendingIntent {
        val i = Intent(context, javaClass)
            .setAction(WidgetCommon.ACTION_SEARCH_KEY)
            .putExtra(WidgetCommon.EXTRA_LETTER, char.toString())
        return PendingIntent.getBroadcast(
            context, (WidgetCommon.ACTION_SEARCH_KEY + char).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val TAG = "AppLauncherWidget"

        /** view id for every letter key in the search keypad */
        /**
         * View id for every letter key in the search keypad, sourced from
         * [Keys.LETTERS].
         *
         * This used to be a literal list right here. It moved out at v0.12,
         * when the keypad itself moved into search_keypad.xml so the floating
         * cover keyboard could inflate the same file. Two keyboards reading one
         * layout would still drift if each kept its own idea of which character
         * a key produces, so the table moved out with it.
         */
        private val SEARCH_KEYS: List<Pair<Char, Int>> = Keys.LETTERS

        fun push(context: Context, cls: Class<out AppWidgetProvider>) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, cls)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
            Log.d(TAG, "pushed " + ids.size + " widgets for " + cls.simpleName)
        }
    }
}

class AppLauncherWidgetReceiverForOverlays : AppLauncherWidgetReceiver() {
    override val gridService: Class<*> get() = GridRemoteViewsServiceForOverlay::class.java
    override val keyboardService: Class<*> get() = KeyBoardRemoteViewsServiceForOverlay::class.java
    override val searchService: Class<*> get() = SearchRemoteViewsServiceForOverlay::class.java
}
