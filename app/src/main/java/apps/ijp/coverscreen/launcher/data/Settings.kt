package apps.ijp.coverscreen.launcher.data

import android.content.Context

/** every launcher customization option */
class Settings(context: Context) {

    private val p = context.applicationContext
        .getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)

    companion object {
        const val SORT_ALPHA = 0
        const val SORT_RECENT = 1
        const val SORT_FREQUENT = 2
        const val SORT_CUSTOM = 3

        const val VIEW_GRID = 0
        const val VIEW_KEYBOARD = 1
        const val VIEW_RECENT = 2
        const val VIEW_FAVORITES = 3
        const val VIEW_SEARCH = 4

        const val POS_TOP = 0
        const val POS_CENTER = 1
        const val POS_BOTTOM = 2

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }

    private fun i(k: String, d: Int) = p.getInt(k, d)
    private fun b(k: String, d: Boolean) = p.getBoolean(k, d)

    var theme: Int
        get() = i("theme", THEME_SYSTEM)
        set(v) = p.edit().putInt("theme", v).apply()
    var columns: Int
        get() = i("columns", 3)
        set(v) = p.edit().putInt("columns", v.coerceIn(2, 6)).apply()
    var rows: Int
        get() = i("rows", 4)
        set(v) = p.edit().putInt("rows", v.coerceIn(2, 8)).apply()
    var iconDp: Int
        get() = i("icon_dp", 48)
        set(v) = p.edit().putInt("icon_dp", v.coerceIn(28, 80)).apply()
    var labelSp: Int
        get() = i("label_sp", 11)
        set(v) = p.edit().putInt("label_sp", v.coerceIn(7, 18)).apply()
    var showLabels: Boolean
        get() = b("show_labels", true)
        set(v) = p.edit().putBoolean("show_labels", v).apply()
    var backgroundColor: Int
        get() = i("bg_color", 0xFF000000.toInt())
        set(v) = p.edit().putInt("bg_color", v).apply()
    var backgroundImage: String?
        get() = p.getString("bg_image", null)
        set(v) = p.edit().putString("bg_image", v).apply()
    /**
     * Defaults to 64 (25%) rather than fully opaque, now that the background
     * colour row is gone from settings and black-at-low-opacity is the only
     * look offered out of the box. Still fully adjustable via its own slider,
     * 0 to 255 -- only the out-of-the-box value changed.
     */
    var widgetAlpha: Int
        get() = i("widget_alpha", 64)
        set(v) = p.edit().putInt("widget_alpha", v.coerceIn(0, 255)).apply()

    var launchOnCover: Boolean
        get() = b("launch_on_cover", true)
        set(v) = p.edit().putBoolean("launch_on_cover", v).apply()
    var autoRotate: Boolean
        get() = b("auto_rotate", false)
        set(v) = p.edit().putBoolean("auto_rotate", v).apply()
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()
    var sounds: Boolean
        get() = b("sounds", false)
        set(v) = p.edit().putBoolean("sounds", v).apply()
    var gestures: Boolean
        get() = b("gestures", true)
        set(v) = p.edit().putBoolean("gestures", v).apply()
    var autoHide: Boolean
        get() = b("auto_hide", false)
        set(v) = p.edit().putBoolean("auto_hide", v).apply()

    /**
     * Master switch for the floating keypad on the cover panel. On by default:
     * without it there is no way to type into another app out there at all, so
     * defaulting it off would just look broken.
     */
    var coverKeyboard: Boolean
        get() = b("cover_keyboard", true)
        set(v) = p.edit().putBoolean("cover_keyboard", v).apply()

    /**
     * Whether it comes up by itself when a text field takes focus. Turning this
     * off leaves the keyboard available but manual, for anyone who finds it
     * appearing over content they were reading.
     */
    var coverKeyboardAuto: Boolean
        get() = b("cover_keyboard_auto", true)
        set(v) = p.edit().putBoolean("cover_keyboard_auto", v).apply()

    /**
     * Where the user dragged the floating keyboard to, in pixels above the
     * bottom edge. Negative is allowed so it can be pushed below the nominal
     * edge, because the usable bottom of the cover panel is not the same in
     * every app.
     */
    var coverKeyboardLift: Int
        get() = i("cover_keyboard_lift", 0)
        set(v) = p.edit().putInt("cover_keyboard_lift", v.coerceIn(-200, 900)).apply()

    /**
     * Master switch for the notification shade on the cover panel. The
     * listener that feeds it is a separate, user-granted permission, so this
     * defaulting on cannot surface anything the user has not already allowed.
     */
    var coverNotifications: Boolean
        get() = b("cover_notifs", true)
        set(v) = p.edit().putBoolean("cover_notifs", v).apply()

    /** adjustable overlay position */
    var overlayPosition: Int
        get() = i("overlay_position", POS_CENTER)
        set(v) = p.edit().putInt("overlay_position", v.coerceIn(0, 2)).apply()
    /** how long the overlay waits before auto hiding, milliseconds */
    var autoHideDelay: Int
        get() = i("auto_hide_delay", 5000)
        set(v) = p.edit().putInt("auto_hide_delay", v.coerceIn(1000, 30000)).apply()

    var autoStart: Boolean
        get() = b("auto_start", true)
        set(v) = p.edit().putBoolean("auto_start", v).apply()
    var lockScreen: Boolean
        get() = b("lock_screen", true)
        set(v) = p.edit().putBoolean("lock_screen", v).apply()

    var showHeader: Boolean
        get() = b("show_header", true)
        set(v) = p.edit().putBoolean("show_header", v).apply()
    var recentCount: Int
        get() = i("recent_count", 6)
        set(v) = p.edit().putInt("recent_count", v.coerceIn(0, 20)).apply()
    var favoriteMax: Int
        get() = i("favorite_max", 12)
        set(v) = p.edit().putInt("favorite_max", v.coerceIn(1, 40)).apply()

    var sort: Int
        get() = i("sort", SORT_ALPHA)
        set(v) = p.edit().putInt("sort", v).apply()
    var view: Int
        get() = i("view", VIEW_GRID)
        set(v) = p.edit().putInt("view", v).apply()

    var hidden: MutableSet<String>
        get() = HashSet(p.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("hidden", v).apply()

    /** [key] is an AppEntry.key, not a bare package name. */
    fun hide(key: String) {
        val h = hidden
        h.add(key)
        hidden = h
    }

    fun unhide(key: String) {
        val h = hidden
        h.remove(key)
        hidden = h
    }

    fun isHidden(key: String) = hidden.contains(key)

    /** wipe every launcher preference back to its default */
    fun reset() = p.edit().clear().apply()

    var customOrder: List<String>
        get() = (p.getString("custom_order", "") ?: "").split(",").filter { it.isNotEmpty() }
        set(v) = p.edit().putString("custom_order", v.joinToString(",")).apply()
}
