package com.tv.coverscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** cover screen detection, per the integration guide */
object DisplayUtils {

    fun displays(context: Context): Array<Display> =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).displays

    fun isCover(context: Context, display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        if (display.displayId == 1) return true
        if (display.name.contains("cover", true)) return true
        val c = context.createDisplayContext(display).resources.configuration
        return c.screenWidthDp < 400 && c.screenHeightDp > c.screenWidthDp
    }

    fun coverDisplay(context: Context): Display? =
        displays(context).firstOrNull { isCover(context, it) }

    fun coverDisplayId(context: Context): Int = coverDisplay(context)?.displayId ?: -1

    fun rotation(context: Context, display: Display): Int = display.rotation
}
