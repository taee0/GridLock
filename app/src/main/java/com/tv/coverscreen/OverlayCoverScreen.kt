package com.tv.coverscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import apps.ijp.coverscreen.launcher.data.Settings

/**
 * Foreground service that owns the cover screen overlay window.
 * Flow from the integration guide: get the cover display, bind the window
 * manager to it with nW1, build LayoutParams natively, addView.
 */
class OverlayCoverScreen : Service() {

    companion object {
        private const val TAG = "OverlayCoverScreen"
        private const val CHANNEL = "cover_overlay"
        private const val NOTIF_ID = 4411
        private const val ENTER_MS = 180L
        private const val EXIT_MS = 140L

        const val ACTION_SHOW = "com.tv.coverscreen.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.tv.coverscreen.OVERLAY_HIDE"
        const val ACTION_DUMP = "com.tv.coverscreen.OVERLAY_DUMP"

        fun show(context: Context) {
            val i = Intent(context, OverlayCoverScreen::class.java).setAction(ACTION_SHOW)
            context.startForegroundService(i)
        }

        fun hide(context: Context) {
            val i = Intent(context, OverlayCoverScreen::class.java).setAction(ACTION_HIDE)
            context.startService(i)
        }
    }

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var display: Display? = null
    private var attached = false
    private val ui = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { detach() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        NativeLayout.dump(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> detach()
            ACTION_DUMP -> NativeLayout.dump(this)
            else -> attach()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Cover overlay", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun attach() {
        if (attached) return
        val d = DisplayUtils.coverDisplay(this)
        if (d == null) {
            Log.w(TAG, "no cover display")
            return
        }
        display = d

        // bind native window manager to the cover display before building params
        val app = application as? apps.ijp.coverscreen.launcher.CoverScreenAppLauncherApp
        val bound = try {
            app?.nW1(d.displayId) ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "nW1 failed: $t")
            false
        }
        Log.d(TAG, "nW1(${d.displayId}) = $bound")

        val dc = createDisplayContext(d).createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
        )
        val w = dc.getSystemService(WindowManager::class.java)
        wm = w

        val lp = nativeParams(app, d)

        val st = Settings(this)

        // adjustable overlay position
        lp.gravity = when (st.overlayPosition) {
            Settings.POS_TOP -> Gravity.TOP
            Settings.POS_BOTTOM -> Gravity.BOTTOM
            else -> Gravity.CENTER
        }

        val fl = FrameLayout(dc)
        // theme support. the launcher background colour wins, and
        // the alpha slider decides how much of the app behind still shows
        fl.setBackgroundColor(st.backgroundColor)
        fl.alpha = 0f
        fl.fitsSystemWindows = false
        LayoutInflater.from(dc).inflate(R.layout.switcher, fl, true)
        gestures(fl, st)
        root = fl

        try {
            w.addView(fl, lp)
            attached = true
            // smooth enter animation
            fl.animate()
                .alpha(st.widgetAlpha / 255f)
                .setDuration(ENTER_MS)
                .start()
            arm(st)
            Log.d(TAG, "attached to display ${d.displayId}")
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed: $t")
            root = null
        }
    }

    private fun nativeParams(
        app: apps.ijp.coverscreen.launcher.CoverScreenAppLauncherApp?,
        d: Display
    ): WindowManager.LayoutParams {
        // nV1 takes a signature string
        val sig = signature(d)
        val fromV1 = try {
            app?.nV1(sig)
        } catch (t: Throwable) {
            Log.w(TAG, "nV1 failed: $t")
            null
        }
        if (fromV1 != null) {
            Log.d(TAG, "params via nV1($sig)")
            return fromV1
        }
        return NativeLayout.fullScreen(d.rotation)
    }

    private fun signature(d: Display): String = "${packageName}:${d.displayId}:${d.rotation}"

    /** swipe to close plus tap outside, and it re arms auto hide */
    private fun gestures(view: View, st: Settings) {
        if (!st.gestures) return
        var downY = 0f
        var downX = 0f
        val slop = 24f * resources.displayMetrics.density
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    downX = e.rawX
                    ui.removeCallbacks(autoHide)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    val dx = e.rawX - downX
                    // a swipe away from the anchored edge dismisses
                    val away = when (st.overlayPosition) {
                        Settings.POS_TOP -> -dy
                        Settings.POS_BOTTOM -> dy
                        else -> Math.abs(dy)
                    }
                    if (away > slop && Math.abs(dy) > Math.abs(dx)) close() else arm(st)
                    v.performClick()
                    true
                }
                else -> true
            }
        }
    }

    /** auto hide when not in use */
    private fun arm(st: Settings) {
        ui.removeCallbacks(autoHide)
        if (st.autoHide) ui.postDelayed(autoHide, st.autoHideDelay.toLong())
    }

    /** exit animation, then tear the window down */
    private fun close() {
        val v = root
        if (v == null) {
            detach()
            return
        }
        v.animate().alpha(0f).setDuration(EXIT_MS).withEndAction { detach() }.start()
    }

    private fun detach() {
        ui.removeCallbacks(autoHide)
        val v: View? = root
        if (v != null) {
            try {
                wm?.removeViewImmediate(v)
            } catch (ignored: Throwable) {
            }
        }
        root = null
        attached = false
    }
}
