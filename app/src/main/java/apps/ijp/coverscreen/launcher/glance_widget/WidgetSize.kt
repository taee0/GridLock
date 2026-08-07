package apps.ijp.coverscreen.launcher.glance_widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle

/**
 * Panel measurements for the widget grid.
 *
 * Column count, row count and icon size are three independent settings that
 * all draw on the same fixed panel, and nothing reconciled them. Six 80dp
 * icons could be requested in a space that fits three, and the grid simply
 * clipped. These helpers reduce a request to what the host says it has.
 *
 * The size is always taken from the host's options bundle when it is there,
 * because that is the only figure that accounts for the actual placement.
 * The constants below are a fallback for the window before the first bind,
 * and for hosts that never populate the bundle.
 */
object WidgetSize {

    /**
     * Cover panel size in dp.
     *
     * From `res/values/dimens.xml`, which records the panel as 720x748 px at
     * density 2.0. Note that `app_launcher_widget_info.xml` declares
     * `minWidth="748px"` and `minHeight="720px"`, which is the transpose of
     * that, and in px rather than the dp the attribute expects. The two
     * disagree. The host bundle is preferred precisely so that this
     * disagreement cannot decide the layout.
     */
    const val PANEL_WIDTH_DP = 360
    const val PANEL_HEIGHT_DP = 374

    /** `android:padding` on grid_view in widget_layout.xml. */
    private const val GRID_PADDING_DP = 12

    /** `android:horizontalSpacing` on grid_view. */
    private const val H_SPACING_DP = 12

    /** `android:verticalSpacing` on grid_view. */
    private const val V_SPACING_DP = 16

    /** Height of widget_header: 44dp of buttons over 10dp of top padding. */
    private const val HEADER_DP = 54

    /**
     * Height of widget_sub.
     *
     * Counted separately from [HEADER_DP] because the subtitle is *not* part of
     * the header and is never hidden with it: it is the only way back to a
     * hidden toolbar, so it is always drawn. Leaving it out of the chrome, as
     * the first version of [rowHeightDp] did, over-estimated the space
     * available by a whole row of text.
     */
    private const val SUBTITLE_DP = 24

    /** Room a grid cell needs under the icon for a two line label. */
    private const val LABEL_BLOCK_DP = 30

    /** Vertical padding declared on the grid item's own container. */
    private const val CELL_PADDING_DP = 16

    /**
     * The host's size bundle, or null when there is no widget to ask about.
     *
     * Guarded because a factory can outlive its widget: the id is captured when
     * the service is bound and the widget can be removed before the next load.
     */
    fun options(context: Context, appWidgetId: Int): Bundle? =
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) null
        else try {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        } catch (t: Throwable) {
            null
        }

    fun widthDp(options: Bundle?): Int {
        val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) ?: 0
        return if (w > 0) w else PANEL_WIDTH_DP
    }

    fun heightDp(options: Bundle?): Int {
        val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        return if (h > 0) h else PANEL_HEIGHT_DP
    }

    /**
     * The requested column count, reduced until the icons fit across [widthDp].
     *
     * Never returns less than one. A single clipped column is a worse layout
     * than the user asked for, but a grid with zero columns draws nothing at
     * all, and the icon size alone can exceed a narrow panel.
     */
    fun columns(requested: Int, iconDp: Int, widthDp: Int): Int {
        val want = requested.coerceAtLeast(1)
        val usable = widthDp - (GRID_PADDING_DP * 2)
        val cell = iconDp + H_SPACING_DP
        if (cell <= 0 || usable <= 0) return want
        // The last column carries no trailing gap, so the gap is added back
        // before dividing rather than being charged to every column.
        val fits = (usable + H_SPACING_DP) / cell
        return fits.coerceIn(1, want)
    }

    /**
     * Cell height that puts [rows] rows in the panel.
     *
     * Floored at what the cell's own contents need. A row shorter than its
     * icon and label does not fit more rows on screen, it clips the label, so
     * an unreachable row count degrades to fewer, correct rows instead.
     */
    fun rowHeightDp(
        rows: Int,
        iconDp: Int,
        showLabels: Boolean,
        showHeader: Boolean,
        heightDp: Int
    ): Int {
        val n = rows.coerceAtLeast(1)
        val chrome = (GRID_PADDING_DP * 2) + SUBTITLE_DP +
            if (showHeader) HEADER_DP else 0
        val usable = heightDp - chrome
        val each = (usable - (V_SPACING_DP * (n - 1))) / n
        val minimum = iconDp + CELL_PADDING_DP + if (showLabels) LABEL_BLOCK_DP else 0
        return if (each < minimum) minimum else each
    }
}
