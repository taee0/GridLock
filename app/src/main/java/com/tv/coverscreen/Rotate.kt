package com.tv.coverscreen

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager

/**
 * Auto rotate for the cover panel.
 *
 * The shape matters and it is not obvious. The overlay is not re-aimed for each
 * direction. The show path bails out early if the overlay is already up, which
 * means the params handed back by the native method are a single fixed value
 * and can only be a sensor orientation. So the overlay is one permanent full
 * screen window whose job is to say "this display may follow the sensor", and
 * the orientation listener exists purely to notice a change and fire the
 * transparent activity that makes the window policy recompute.
 *
 * Full screen, TYPE_APPLICATION_OVERLAY, and SYSTEM_ALERT_WINDOW, because that
 * is what every shipping rotation app uses.
 */
class Rotate(private val service: AccessibilityService) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = service.getSharedPreferences("rotate", Context.MODE_PRIVATE)

    private var wm: WindowManager? = null
    private var mark: View? = null
    private var shown = false
    private var panelId = Display.INVALID_DISPLAY

    private var facing = UNKNOWN
    private var pending = UNKNOWN

    private val sensor = object : OrientationEventListener(service) {
        override fun onOrientationChanged(angle: Int) = saw(angle)
    }

    var on: Boolean
        get() = prefs.getBoolean("on", false)
        set(value) {
            prefs.edit().putBoolean("on", value).apply()
            check()
        }

    /**
     * Fallback. Instead of one sensor window, re-add the overlay pointing at an
     * explicit direction every time the phone turns. Blunter, but it works on
     * builds that will not honour a sensor request coming from an overlay.
     */
    var strict: Boolean
        get() = prefs.getBoolean("strict", false)
        set(value) {
            prefs.edit().putBoolean("strict", value).apply()
            facing = UNKNOWN
            hide()
            check()
        }

    val allowed: Boolean get() = Settings.canDrawOverlays(service)

    val live: Boolean get() = shown

    /**
     * The DisplayListener decision. Main display on means tear it down, cover
     * panel on means put it up, both off means tear it down.
     */
    fun check() {
        val dm = service.getSystemService(DisplayManager::class.java)
        val inner = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val panel = Cover.panel(service)

        if (!on || panel == null) {
            stop()
            return
        }
        if (inner?.state == Display.STATE_ON) {
            hide()
            sensor.disable()
            return
        }
        if (panel.state == Display.STATE_ON) {
            show(panel)
            if (sensor.canDetectOrientation()) sensor.enable()
            return
        }
        hide()
        sensor.disable()
    }

    fun stop() {
        sensor.disable()
        hide()
        facing = UNKNOWN
        pending = UNKNOWN
    }

    // ------------------------------------------------------------ the window

    private fun show(panel: Display) {
        if (shown) return
        if (!Settings.canDrawOverlays(service)) return

        val ctx = service.createDisplayContext(panel)
        val manager = ctx.getSystemService(WindowManager::class.java) ?: return
        val view = View(ctx)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            screenOrientation = aim()
            windowAnimations = 0
            // Must genuinely cover the panel. If the system letterboxes this
            // window off the bump it stops counting as full screen, and a window
            // that is not full screen does not get to influence rotation.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        val ok = runCatching { manager.addView(view, lp) }.isSuccess
        if (!ok) return

        wm = manager
        mark = view
        shown = true
        panelId = panel.displayId
    }

    private fun hide() {
        mark?.let { v -> runCatching { wm?.removeView(v) } }
        mark = null
        wm = null
        shown = false
    }

    /**
     * What the overlay asks for. Normally one fixed sensor request, which is
     * what the native method almost certainly handed back. In strict mode it is
     * the exact direction we measured ourselves.
     */
    private fun aim(): Int {
        if (!strict) return ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        return when (facing) {
            UP -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            DOWN -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            LEFT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            RIGHT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    // ----------------------------------------------------------- the sensor

    private fun saw(angle: Int) {
        val next = bucket(angle)
        if (next == UNKNOWN || next == facing) {
            pending = facing
            return
        }
        if (next == pending) return
        pending = next
        main.postDelayed({ if (pending == next && facing != next) turned(next) }, HOLD)
    }

    private fun bucket(angle: Int) = when {
        angle < 0 -> UNKNOWN
        angle < 46 || angle >= 315 -> UP
        angle < 135 -> LEFT
        angle < 226 -> DOWN
        else -> RIGHT
    }

    private fun turned(next: Int) {
        facing = next

        // show() re-runs on every turn and then the activity launches. In
        // sensor mode that show is a no-op because the window is already up,
        // which is exactly the point.
        if (strict) hide()
        Cover.panel(service)?.let { show(it) }

        kick()
    }

    /**
     * 268566528 decodes to FLAG_ACTIVITY_NEW_TASK or
     * FLAG_ACTIVITY_REORDER_TO_FRONT and nothing else. No clear task, no
     * no-animation, no exclude from recents. The manifest handles the rest.
     */
    private fun kick() {
        val target = if (panelId != Display.INVALID_DISPLAY) {
            panelId
        } else {
            Cover.panel(service)?.displayId ?: return
        }

        val intent = Intent(service, Kick::class.java)
        intent.flags = LAUNCH_FLAGS
        if (strict) intent.putExtra(Kick.EXTRA_ORIENTATION, aim())

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        runCatching { service.startActivity(intent, options.toBundle()) }
    }

    private companion object {
        const val UNKNOWN = -1
        const val UP = 0
        const val LEFT = 1
        const val DOWN = 2
        const val RIGHT = 3
        const val HOLD = 220L

        /** 268566528. */
        const val LAUNCH_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    }
}
