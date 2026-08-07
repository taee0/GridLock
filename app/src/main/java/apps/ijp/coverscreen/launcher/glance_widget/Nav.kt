package apps.ijp.coverscreen.launcher.glance_widget

import android.content.Context

/**
 * Backing store for ACTION_NAVIGATE_LETTER. The receiver writes it, the
 * RemoteViews factories read it, so the keyboard view can jump to a letter
 * section without the widget holding any in-process state.
 */
object Nav {

    private const val PREFS = "launcher_nav"
    private const val KEY_LETTER = "letter"

    private fun p(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun letter(context: Context): String? = p(context).getString(KEY_LETTER, null)

    fun setLetter(context: Context, letter: String?) {
        if (letter.isNullOrEmpty()) {
            clearLetter(context)
            return
        }
        p(context).edit().putString(KEY_LETTER, letter.uppercase()).apply()
    }

    fun clearLetter(context: Context) {
        p(context).edit().remove(KEY_LETTER).apply()
    }
}
