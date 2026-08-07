package com.tv.coverscreen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Cover panel discovery through public APIs only.
 * No hardcoded display ids, no device table.
 */
object Cover {

    /** The cover panel: the smallest non-default display the system reports. */
    fun panel(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        return dm.displays
            .filter { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }
            .minByOrNull { area(context, it) }
    }

    /** Whichever panel is currently lit. On a Flip only one is on at a time. */
    fun awake(context: Context): Int {
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return Display.DEFAULT_DISPLAY
        return dm.displays.firstOrNull { it.state == Display.STATE_ON }?.displayId
            ?: Display.DEFAULT_DISPLAY
    }

    fun bounds(context: Context, display: Display): Rect {
        val wm = context.createDisplayContext(display).getSystemService(WindowManager::class.java)
            ?: return Rect()
        return Rect(wm.maximumWindowMetrics.bounds)
    }

    fun area(context: Context, display: Display): Long {
        val b = bounds(context, display)
        return b.width().toLong() * b.height().toLong()
    }

    fun size(context: Context, display: Display): String {
        val b = bounds(context, display)
        return "${b.width()}x${b.height()}"
    }

    /**
     * Height of the native nav bar on this panel, in pixels. This is the thing
     * the user actually pulls up on, so the catcher has to line up with it
     * exactly. Falls back to the system gesture inset when the phone is in
     * gesture mode and the bar itself reports nothing.
     */
    fun navBar(context: Context, display: Display): Int {
        val wm = context.createDisplayContext(display).getSystemService(WindowManager::class.java)
            ?: return 0
        val insets = wm.currentWindowMetrics.windowInsets
        val bar = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        val gesture = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
        val tappable = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
        return maxOf(bar, gesture, tappable)
    }

    /**
     * The camera bump, as pixels to stay out of on each edge of this panel.
     *
     * Read from the panel's own DisplayCutout rather than assumed, because on a
     * Flip the bump is not a notch at the top: the panel wraps around the two
     * lenses, and which edge that lands on changes the moment auto rotate turns
     * the panel sideways. safeInsets already account for that, so this is the
     * one number that is right in every orientation.
     */
    fun safe(context: Context, display: Display): Rect {
        val wm = context.createDisplayContext(display).getSystemService(WindowManager::class.java)
            ?: return Rect()
        val insets = wm.currentWindowMetrics.windowInsets

        insets.displayCutout?.let { c ->
            return Rect(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom)
        }
        // Some builds report nothing for the cutout object but still fill in the
        // inset type. Take whichever one actually has numbers.
        val i = insets.getInsets(WindowInsets.Type.displayCutout())
        return Rect(i.left, i.top, i.right, i.bottom)
    }

    fun home(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /** Packages that should never end up in the switcher. */
    fun ignored(context: Context): Set<String> = buildSet {
        add(context.packageName)
        home(context)?.let { add(it) }
        add("com.android.systemui")
        add("com.samsung.android.app.cocktailbarservice")
        add("com.samsung.android.coverscreen")
        add("com.sec.android.app.launcher")
    }

    fun dump(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return "no display manager"
        val cover = panel(context)
        return dm.displays.joinToString("\n") { d ->
            val tag = if (d.displayId == cover?.displayId) "  <- cover" else ""
            val s = safe(context, d)
            "id ${d.displayId}  ${size(context, d)}  nav ${navBar(context, d)}px  " +
                "cutout l${s.left} t${s.top} r${s.right} b${s.bottom}  ${d.name}$tag"
        }
    }
}
