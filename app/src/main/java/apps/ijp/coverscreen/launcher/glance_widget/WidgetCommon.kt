package apps.ijp.coverscreen.launcher.glance_widget

/**
 * Widget extras and custom actions.
 *
 * Every action here is delivered to a named component: self() builds
 * Intent(context, javaClass) and push() builds Intent(context, cls). None of
 * them is ever matched against an intent filter, which is why the manifest no
 * longer declares them. Declaring an action on an exported receiver makes it a
 * public entry point for any app on the device, and these were twelve entry
 * points that nothing needed.
 */
object WidgetCommon {

    private const val P = "apps.ijp.coverscreen.launcher."

    // extras
    const val EXTRA_PACKAGE = "package_name"
    const val EXTRA_ACTIVITY = "activity_name"
    /**
     * Target display for a launch.
     *
     * Read by WidgetLaunchActivity, but nothing in this project ever writes it,
     * so that branch has never been taken and a launch falls through to the
     * launchOnCover setting. Left in place rather than deleted: it is the
     * override an external caller would use, and choosing between wiring it up
     * and removing it needs the device.
     */
    const val EXTRA_DISPLAY = "display_id"
    const val EXTRA_LETTER = "letter"
    const val EXTRA_ADD_FAVORITE = "add_favorite"

    /**
     * The row's visible label.
     *
     * Carried so a favourite toggle does not have to enumerate every launchable
     * app in every profile just to recover a name the row was already showing.
     */
    const val EXTRA_LABEL = "app_label"

    /**
     * Composite app identity, "pkg" for personal and "pkg@serial" for work.
     *
     * EXTRA_PACKAGE cannot identify a row on its own: a dual installed app has
     * the same package name in both profiles, so a tap carrying only the
     * package always resolved to the personal copy. Rows carry this as well and
     * the trampoline prefers it.
     */
    const val EXTRA_KEY = "app_key"

    // view switching
    const val ACTION_TOGGLE_KEYBOARD = P + "ACTION_TOGGLE_KEYBOARD"
    const val ACTION_TOGGLE_RECENTS = P + "ACTION_TOGGLE_RECENTS"
    const val ACTION_TOGGLE_HOME = P + "ACTION_TOGGLE_HOME"
    const val ACTION_TOGGLE_FAVORITES = P + "ACTION_TOGGLE_FAVORITES"
    /**
     * Replaces the old WidgetSearchActivity launch. Search is a view like
     * the four above rather than a separate screen, so toggling it follows
     * the same on/back-to-grid shape.
     */
    const val ACTION_TOGGLE_SEARCH = P + "ACTION_TOGGLE_SEARCH"

    // search keypad, each key its own action rather than a shared one so the
    // extras cannot be raced by two keys resolving to the same PendingIntent
    const val ACTION_SEARCH_KEY = P + "ACTION_SEARCH_KEY"
    const val ACTION_SEARCH_BACKSPACE = P + "ACTION_SEARCH_BACKSPACE"
    const val ACTION_SEARCH_SPACE = P + "ACTION_SEARCH_SPACE"

    // sorting
    const val ACTION_SORT_ALPHA = P + "ACTION_SORT_ALPHA"
    const val ACTION_SORT_RECENT = P + "ACTION_SORT_RECENT"
    const val ACTION_SORT_FREQUENT = P + "ACTION_SORT_FREQUENT"
    const val ACTION_SORT_CUSTOM = P + "ACTION_SORT_CUSTOM"

    // navigation
    const val ACTION_NAVIGATE_LETTER = P + "ACTION_NAVIGATE_LETTER"
    const val ACTION_TOGGLE_NAV_HEADER = P + "ACTION_TOGGLE_NAV_HEADER"

    // favourites
    const val ACTION_ADD_TO_FAVORITES = P + "ACTION_ADD_TO_FAVORITES"

    // housekeeping
    const val ACTION_REFRESH = P + "WIDGET_REFRESH"
}
