package apps.ijp.coverscreen.launcher.glance_widget

import android.content.Context

/**
 * Backing store for the widget's inline search query (v0.10).
 *
 * Same shape as Nav: the receiver appends or trims a character and writes it
 * here, SearchRemoteViewsFactory reads it back, so the query survives
 * between one keypad tap and the next without the widget holding any
 * in-process state of its own. Global rather than keyed by appWidgetId, same
 * as Nav's letter -- only one panel is realistically being typed into at a
 * time, and keying by id would just be state the two widget hosts (main and
 * overlay) would need to stay in sync on for no benefit.
 */
object SearchState {

    private const val PREFS = "launcher_search"
    private const val KEY_QUERY = "query"

    private fun p(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun query(context: Context): String = p(context).getString(KEY_QUERY, "") ?: ""

    fun append(context: Context, char: String) {
        if (char.isEmpty()) return
        p(context).edit().putString(KEY_QUERY, query(context) + char).apply()
    }

    fun backspace(context: Context) {
        val q = query(context)
        if (q.isNotEmpty()) p(context).edit().putString(KEY_QUERY, q.dropLast(1)).apply()
    }

    fun clear(context: Context) {
        p(context).edit().remove(KEY_QUERY).apply()
    }
}
