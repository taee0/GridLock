package com.tv.coverscreen

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The whole thing.
 *
 * Watches which app is in front, screenshots it while it is there, and hangs a
 * touch catcher exactly over the native nav bar on the cover panel. Pull up off
 * that bar, hold, and the deck comes out of the bottom edge locked to your
 * finger. Let go past halfway and it finishes; let go short and it falls back
 * down. Taps on the bar are handed straight to the real nav buttons underneath,
 * so nothing you already had stops working.
 *
 * Window attributes, the dwell and the travel distances all come from
 * libspark.so through [Native], with the values decoded out of that binary as
 * the fallback when it does not bind.
 */
class RecentsEngine : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var snaps: Snapshots
    private lateinit var recents: Recents

    /**
     * Shizuku dies on every reboot unless the phone is rooted, so the deck has
     * to cope with it appearing and disappearing underneath us. Held in a field
     * so it can be taken back off when the service goes down.
     */
    private val shizukuWatch: () -> Unit = { main.post { scheduleFill() } }

    private var coverCtx: Context? = null
    private var coverWm: WindowManager? = null
    private var coverId = Display.INVALID_DISPLAY
    private var panelWidth = 0
    private var panelHeight = 0
    private var panelSafe = Rect()
    private var strip: View? = null
    private var switcher: Switcher? = null

    private var foreground: String? = null
    private var lastShot = 0L
    private var skip: Set<String> = emptySet()
    private var launcher: String? = null
    private var triggers: List<String> = emptyList()

    // gesture state
    private var downX = 0f
    private var downY = 0f
    private var holdX = 0f
    private var holdY = 0f
    private var armed = false
    private var claimed = false
    private var tracker: VelocityTracker? = null
    private var slop = 0f
    private var cancelSlop = 0f
    private var commit = 0f
    private var pull = 1f
    private var notched = false
    private var nativeHeld = false

    /**
     * Every broadcast the service listens for. RIF is the remote config
     * landing, ALR pops the switcher, PRINT_HIERARCHY dumps the node tree to
     * logcat. SHOW and HIDE are the two this build adds for adb.
     */
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CONFIG -> main.post { ready() }
                ACTION_LAUNCH, ACTION_SHOW -> main.post { flick() }
                ACTION_HIDE -> main.post { close() }
                ACTION_HIERARCHY -> io.execute { dumpHierarchy() }
            }
        }
    }

    private val displayWatch = DisplayWatch(
        onPanels = { main.post { attach() } },
        onState = { main.post { rotate?.check() } },
    )

    /** Auto rotate for the cover panel. Independent of the switcher. */
    var rotate: Rotate? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        live = this
        snaps = Snapshots(cacheDir.resolve("shots"), io)
        recents = Recents(this)
        rotate = Rotate(this)
        Privileged.watch(shizukuWatch)

        val filter = IntentFilter().apply {
            addAction(ACTION_CONFIG)
            addAction(ACTION_LAUNCH)
            addAction(ACTION_HIERARCHY)
            addAction(ACTION_SHOW)
            addAction(ACTION_HIDE)
        }
        registerReceiver(commands, filter, Context.RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayWatch, main)

        ready()
    }

    /**
     * This step is gated on remote config having landed, waiting on the RIF
     * broadcast when it has not. There is no remote config in this build, so
     * it runs straight away, but it stays a separate step so RIF still
     * re-reads whatever nS1 pushed into the native side.
     */
    private fun ready() {
        skip = Cover.ignored(this)
        launcher = Native.launcher(this)
        triggers = Native.triggerIds()
        Log.d(TAG, Native.dump())
        attach()
        rotate?.check()
    }

    override fun onDestroy() {
        Privileged.unwatch(shizukuWatch)
        super.onDestroy()
        live = null
        main.removeCallbacks(retry)
        main.removeCallbacks(remeasure)
        main.removeCallbacks(refill)
        main.removeCallbacks(dwell)
        tracker?.recycle()
        tracker = null
        close()
        detach()
        rotate?.stop()
        rotate = null
        runCatching { unregisterReceiver(commands) }
        runCatching {
            getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayWatch)
        }
        io.shutdown()
    }

    override fun onInterrupt() = Unit

    /** Panel went missing for a moment. Keep asking for it. */
    private val retry = Runnable { attach() }

    /**
     * The panel can turn now, so the cached width, height and camera bump go
     * stale. Measure again, but never tear the catcher down to do it. A config
     * change can land while the panel is briefly unreadable, and pulling the
     * strip in that window lost it for good.
     */
    private val remeasure = Runnable {
        close()
        val panel = Native.coverDisplay(this)
        if (panel == null || panel.displayId != coverId || strip == null) {
            attach()
        } else {
            val bounds = Cover.bounds(this, panel)
            if (bounds.width() > 0) {
                panelWidth = bounds.width()
                panelHeight = bounds.height()
                pull = (panelHeight * PULL_FRACTION).coerceAtLeast(1f)
            }
            panelSafe = Cover.safe(this, panel)
            switcher?.resize(panelWidth, panelHeight, panelSafe)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.removeCallbacks(remeasure)
        main.postDelayed(remeasure, REMEASURE_DELAY)
    }

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != foreground) {
                    // The system files its own task snapshot away as a task
                    // goes off screen, so the instant the front app changes is
                    // the one moment its picture of the outgoing app is fresh.
                    val leaving = foreground
                    foreground = pkg
                    if (leaving != null && leaving !in skip && Privileged.ready()) {
                        harvest(leaving, fresh = false)
                    }
                    if (switcher != null) main.post { close() }
                    if (pkg !in skip) {
                        recents.touch(pkg)
                        // Rebind the parked deck now, while nobody is touching
                        // it, so the gesture never has to build a list.
                        scheduleFill()
                    }
                }
                // Let the app finish drawing before grabbing the card image.
                main.postDelayed({ if (foreground == pkg) shoot(pkg) }, SETTLE)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == foreground) shoot(pkg)
            }

            // Pressing the real recents button should also bring the deck out,
            // the same way it does on the inner screen. The clicked node is
            // matched against the resource ids in gR1..gR6 and the foreground
            // package against gP1.
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            -> {
                val id = event.source?.viewIdResourceName ?: return
                val known = triggers.any { it.isNotBlank() && id == it }
                val looksRight = pkg == SYSTEMUI && id.contains("recent", true)
                if (known || looksRight) main.post { flick() }
            }
        }
    }

    // ------------------------------------------------------------ screenshot

    private fun shoot(pkg: String) {
        if (switcher != null || pkg in skip) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShot < SHOT_GAP) return
        lastShot = now

        // Ask the system for its own thumbnail first: it is the same picture the
        // real task changer draws. It can still refuse, so harvest falls through
        // to capture rather than leaving the card with nothing on it.
        if (Privileged.ready()) {
            harvest(pkg, fresh = true)
            return
        }
        capture(pkg)
    }

    /** Our own screenshot: the only path before Shizuku, the fallback after it. */
    private fun capture(pkg: String) {
        if (switcher != null || pkg in skip) return
        val target = Cover.awake(this)
        runCatching {
            takeScreenshot(target, io, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val raw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = raw?.copy(Bitmap.Config.ARGB_8888, false)
                    raw?.recycle()
                    buffer.close()
                    if (copy != null) {
                        snaps.put(pkg, copy)
                        main.post { scheduleFill() }
                    }
                }

                override fun onFailure(errorCode: Int) = Unit
            })
        }
    }

    /**
     * Pull the system's own thumbnail of whatever task [pkg] is sitting in. Two
     * binder calls and a bitmap copy, so it runs on the io thread and rebinds
     * the parked deck once it lands.
     *
     * [fresh] forces a new capture, which is what an app still on screen needs:
     * the system only files a snapshot away as a task leaves, so asking the cache
     * about the app you are looking at hands back the app-theme placeholder, and
     * that placeholder is a flat background with the launcher icon on it.
     */
    private fun harvest(pkg: String, fresh: Boolean) {
        io.execute {
            val id = Privileged.tasks(12)?.firstOrNull { it.pkg == pkg }?.taskId
            val shot = if (id == null) null else Privileged.snapshot(id, fresh)
            if (shot != null) {
                snaps.put(pkg, shot)
                main.post { scheduleFill() }
                return@execute
            }
            // Secure window, previews turned off, or nothing filed away yet.
            // Take the picture ourselves rather than leave an icon on the card,
            // but only while this app is still the one on screen: a screenshot
            // now would otherwise be of whatever replaced it.
            Log.d(TAG, "no usable system snapshot for " + pkg + ", capturing instead")
            main.post { if (foreground == pkg) capture(pkg) }
        }
    }

    // --------------------------------------------------------- cover binding

    private fun attach() {
        main.removeCallbacks(retry)
        // gD1 first, then fall back to picking the smallest panel the system
        // reports.
        val panel = Native.coverDisplay(this)
        if (panel == null) {
            detach()
            main.postDelayed(retry, REMEASURE_DELAY)
            return
        }
        if (panel.displayId == coverId && strip != null) return
        detach()

        // createDisplayContext first and pull the WindowManager out of that
        // context, not out of the service. A WindowManager taken from the
        // service always lands the window on the display the service thinks it
        // is on, which is why the overlay never showed up on the cover panel.
        val base = createDisplayContext(panel)
        val bounds = Cover.bounds(this, panel)
        coverCtx = ContextThemeWrapper(base, R.style.Theme_CoverScreen_Overlay)
        coverWm = base.getSystemService(WindowManager::class.java)
        coverId = panel.displayId
        panelWidth = bounds.width()
        panelHeight = bounds.height()
        panelSafe = Cover.safe(this, panel)
        addStrip(Cover.navBar(this, panel))
        warm()
    }

    private fun detach() {
        main.removeCallbacks(refill)
        switcher?.destroy()
        switcher = null
        strip?.let { v -> runCatching { coverWm?.removeView(v) } }
        strip = null
        coverCtx = null
        coverWm = null
        coverId = Display.INVALID_DISPLAY
    }

    /**
     * The catcher. It sits exactly on top of the native nav bar, not above it
     * and not somewhere in the middle of the screen, because the nav bar is
     * where your thumb already lives.
     *
     * Type, flags and pixel format are gL1, gL2 and gL3 out of the native
     * library: 2032, 0x40728, -3.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addStrip(navHeight: Int) {
        val ctx = coverCtx ?: return
        val wm = coverWm ?: return
        if (strip != null) return

        val density = ctx.resources.displayMetrics.density
        val height = if (navHeight > 0) navHeight else (MIN_STRIP_DP * density).toInt()
        // gT1 and gT3 are pixels, not dp, so they are used raw and only
        // floored against a dp minimum.
        slop = maxOf(Native.slopPx, TOUCH_SLOP_DP * density)
        cancelSlop = Native.cancelPx
        commit = Native.commitPx
        pull = (panelHeight * PULL_FRACTION).coerceAtLeast(1f)

        val view = View(ctx)
        view.setOnTouchListener { _, e -> onStripTouch(e) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            Native.windowType,
            Native.windowFlags,
            Native.pixelFormat,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0
            // Lifted clear of the very bottom edge. A case covers the last few
            // millimetres of the cover panel, and that is exactly where the
            // catcher used to sit, so the band starts above the case lip now.
            // The strip is moved rather than made taller on purpose: it swallows
            // everything it catches and re-sends real nav taps itself, so
            // growing it upward would eat taps in the app above the bar.
            y = (STRIP_LIFT_DP * density).toInt()
            setFitInsetsTypes(0)
            windowAnimations = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        runCatching {
            wm.addView(view, params)
            strip = view
        }.onFailure {
            Log.w(TAG, "strip refused, falling back to the activity path", it)
            strip = null
        }
    }

    // ------------------------------------------------------------- the pull

    /**
     * Fires once the finger has gone up off the bar and then stayed put for the
     * dwell. This is the whole difference between the deck coming out because
     * you meant it and the deck coming out every time you brush the bar.
     */
    private val dwell = Runnable {
        if (claimed) return@Runnable
        if (NATIVE_RECENTS) {
            // Native mode has no deck to arm. Just latch the hold and buzz so
            // the finger knows recents is now what happens on release.
            nativeHeld = true
            claimed = true
            tick()
            return@Runnable
        }
        armed = true
        claimed = true
        begin()
        tick()
        // Pick the drag up from where the finger actually is, so nothing jumps
        // at the moment it arms.
        switcher?.drag(((downY - holdY) / pull).coerceAtLeast(0f))
    }

    /** Where letting go finishes the pull instead of cancelling it. */
    private fun commitAt(): Float = minOf(maxOf(commit, pull * COMMIT), pull * COMMIT_MAX)

    private fun onStripTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                holdX = e.rawX
                holdY = e.rawY
                armed = false
                claimed = false
                notched = false
                nativeHeld = false
                main.removeCallbacks(dwell)
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(e)
                if (switcher?.open == true) close()
            }

            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                holdX = e.rawX
                holdY = e.rawY
                val up = downY - e.rawY
                val side = abs(e.rawX - downX)

                // Native mode still never arms the deck, but it does run the
                // dwell so a held pull can be told apart from a quick one.
                if (NATIVE_RECENTS) {
                    if (side > cancelSlop && side > up) {
                        main.removeCallbacks(dwell)
                        return true
                    }
                    if (up < slop) {
                        main.removeCallbacks(dwell)
                        return true
                    }
                    if (!claimed && !main.hasCallbacks(dwell)) {
                        main.postDelayed(dwell, Native.holdMs)
                    }
                    return true
                }

                if (!claimed) {
                    // Sideways wins, leave it alone. That is a nav bar swipe.
                    if (side > cancelSlop && side > up) {
                        main.removeCallbacks(dwell)
                        return true
                    }
                    if (up < slop) {
                        // Not up far enough to be a pull yet. No timer running.
                        main.removeCallbacks(dwell)
                        return true
                    }
                    // Up past gT1 and still on the way up. Start the dwell once
                    // and let it run; do not restart it on every move or it
                    // never fires while the finger is drifting.
                    if (!main.hasCallbacks(dwell)) {
                        main.postDelayed(dwell, Native.holdMs)
                    }
                    return true
                }

                switcher?.drag(up / pull)
                if (!notched && up > commitAt()) {
                    notched = true
                    tick()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(dwell)
                tracker?.addMovement(e)
                tracker?.computeCurrentVelocity(1000)
                // Screen y grows downward and the pull grows upward, so flip
                // it, then put it in pull fractions per second to match what
                // the spring animates.
                val throwUp = -(tracker?.yVelocity ?: 0f) / pull
                tracker?.recycle()
                tracker = null

                if (NATIVE_RECENTS) {
                    // The hold is the only way in. Velocity can never be a
                    // trigger here, because a fast swipe up IS the home
                    // gesture: any fling threshold is the same motion as home
                    // and just steals it. If the dwell latched, the haptic
                    // already fired and recents is expected. Everything else
                    // goes straight through to the nav bar untouched.
                    if (nativeHeld) {
                        nativeRecents()
                    } else {
                        navTap(e.rawX, e.rawY)
                    }
                    armed = false
                    claimed = false
                    nativeHeld = false
                    return true
                }

                if (!claimed) {
                    // Lifted before the dwell. That was a tap or a flick past
                    // the bar, and neither of those is meant to open anything.
                    navTap(e.rawX, e.rawY)
                    armed = false
                    return true
                }
                val up = downY - e.rawY
                // A real flick decides on its own. Distance only gets a say if
                // the finger was more or less parked when it left.
                val far = if (abs(throwUp) > FLING) {
                    throwUp > 0f
                } else {
                    up > commitAt()
                }
                switcher?.settle(far, throwUp) { close() }
                claimed = false
                armed = false
            }
        }
        return true
    }

    /**
     * Build the deck the moment there is a panel, not the moment someone pulls.
     * Everything expensive lives here: inflation, measure, the window, the
     * surface, the first bind. By the time a finger lands, the only work left
     * is moving something that already exists.
     */
    private fun warm() {
        // Dormant in native mode. Nothing is inflated, no window goes up, no
        // surface is held open. The deck is not gone, it just never gets built.
        if (NATIVE_RECENTS) return
        val ctx = coverCtx ?: return
        val wm = coverWm ?: return
        if (switcher != null) return
        switcher = Switcher(
            ctx = ctx,
            wm = wm,
            panelWidth = panelWidth,
            panelHeight = panelHeight,
            safe = panelSafe,
            onTap = ::launch,
            onDrop = ::forget,
            onClearAll = ::forgetAll,
            onDismiss = ::close,
            onKeepOpen = ::pin,
            onAppInfo = ::appInfo,
        )
        switcher?.prime()
        fill()
    }

    private fun begin() {
        if (switcher == null) warm()
        if (switcher == null) {
            // No window would go up on this panel. Throw the activity at the
            // cover display instead and let the window manager place it.
            showConfigOnCover()
            return
        }
        switcher?.begin()
    }

    private fun showConfigOnCover() {
        val target = if (coverId != Display.INVALID_DISPLAY) coverId else Cover.awake(this)
        val intent = Intent(this, ConfigActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure { Log.w(TAG, "cover activity refused", it) }
    }

    /**
     * Samsung's own task switcher, thrown at the cover panel.
     *
     * RecentsActivity lives inside the launcher process and holds on to the
     * display it last laid out against, so on its own it comes up stale: no
     * icons on the cards, bounds still sized for the inner screen. Killing the
     * process first and then starting the activity on the cover display is the
     * whole trick. It is the same pair that works by hand from a shell:
     *
     *   am force-stop com.sec.android.app.launcher
     *   am start --display 1 -n com.sec.android.app.launcher/com.android.quickstep.RecentsActivity
     *
     * The force stop is a binder call and blocks, so it runs off the main
     * thread and the start is posted back with a short gap behind it, which is
     * roughly what the shell gives you for free between two commands.
     */
    private fun nativeRecents() {
        val target = if (coverId != Display.INVALID_DISPLAY) coverId else Cover.awake(this)
        if (!Privileged.ready()) {
            Log.w(TAG, "shizuku not ready; recents will come up without a restart")
        }
        io.execute {
            Privileged.stop(LAUNCHER_PKG)
            main.postDelayed({
                val intent = Intent(Intent.ACTION_MAIN)
                    .setClassName(LAUNCHER_PKG, RECENTS_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
                runCatching { startActivity(intent, options.toBundle()) }
                    .onFailure { Log.w(TAG, "native recents refused", it) }
            }, NATIVE_RESTART_GAP)
        }
    }

    private val refill = Runnable { fill() }

    private fun scheduleFill() {
        main.removeCallbacks(refill)
        main.postDelayed(refill, REFILL_DELAY)
    }

    /**
     * Rebind the cards while nothing is happening. Reads pictures out of memory
     * only, then asks for anything still on disk in the background and binds a
     * second time once it lands. The gesture path never waits on a decode.
     */
    private fun fill() {
        val panel = switcher ?: return
        if (panel.open) return
        val items = build()
        panel.load(items)
        snaps.warm(items.filter { it.shot == null }.map { it.pkg }) {
            main.post { switcher?.load(build()) }
        }
    }

    /** Broadcast or recents button: run the same pull, just without a finger. */
    private fun flick() {
        if (NATIVE_RECENTS) {
            nativeRecents()
            return
        }
        if (switcher?.open == true) {
            close()
            return
        }
        begin()
        switcher?.settle(true, 0f) { close() }
    }

    fun close() {
        main.removeCallbacks(dwell)
        switcher?.hide()
        claimed = false
        armed = false
        scheduleFill()
    }

    /**
     * A plain tap on the bar belongs to the real nav buttons. Find the clickable
     * systemui node under the finger and click it, so back, home and recents
     * keep behaving exactly as before.
     */
    private fun navTap(x: Float, y: Float) {
        val hit = navNodeAt(x.toInt(), y.toInt())
        if (hit != null) {
            hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // Nothing found, fall back to thirds of the part of the bar you can
        // actually reach. One UI ships back on the right.
        val left = panelSafe.left.toFloat()
        val usable = (panelWidth - panelSafe.left - panelSafe.right).coerceAtLeast(1)
        val third = usable / 3f
        when {
            x < left + third -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            x < left + third * 2 -> performGlobalAction(GLOBAL_ACTION_HOME)
            else -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun navNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val all = runCatching { windowsOnAllDisplays }.getOrNull() ?: return null
        val onPanel = all.get(coverId) ?: return null
        val box = Rect()
        for (window in onPanel) {
            val root = window.root ?: continue
            if (root.packageName != SYSTEMUI) continue
            val found = descend(root) { node ->
                if (!node.isClickable) return@descend false
                node.getBoundsInScreen(box)
                box.contains(x, y)
            }
            if (found != null) return found
        }
        return null
    }

    private fun descend(
        node: AccessibilityNodeInfo,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            descend(child, match)?.let { return it }
        }
        return null
    }

    /**
     * The PRINT_HIERARCHY broadcast. Exists so you can find the resource id of
     * whatever the nav bar is calling its recents button on a given build,
     * which is what ends up in gR1..gR6.
     */
    private fun dumpHierarchy() {
        val all = runCatching { windowsOnAllDisplays }.getOrNull() ?: return
        for (i in 0 until all.size()) {
            val displayId = all.keyAt(i)
            Log.d(TAG, "---- display $displayId ----")
            for (window in all.valueAt(i)) {
                val root = window.root ?: continue
                Log.d(TAG, "window ${window.id} ${root.packageName}")
                printNode(root, 1)
            }
        }
    }

    private fun printNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 24) return
        val box = Rect()
        node.getBoundsInScreen(box)
        Log.d(
            TAG,
            "  ".repeat(depth) + "${node.className} " +
                "id=${node.viewIdResourceName} " +
                "text=${node.text} " +
                "click=${node.isClickable} $box",
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            printNode(child, depth + 1)
        }
    }

    private fun tick() {
        val v = getSystemService(Vibrator::class.java) ?: return
        runCatching {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    // ------------------------------------------------------------- the cards

    private fun build(): List<Card> {
        val pm = packageManager
        val kept = recents.pinned()

        // The real list first: system order, real task ids, the same source the
        // inner screen switcher reads. Falls through to the inferred list when
        // Shizuku is not there.
        real(pm, kept)?.let { return it }

        return recents.list().mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                ?: return@mapNotNull null
            if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
            Card(
                pkg = pkg,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                // Memory only. A disk decode here would land inside the pull.
                shot = snaps.peek(pkg),
                pinned = pkg in kept,
            )
        }
    }

    /**
     * The cards the system itself would show, or null when we cannot ask. One
     * card per app, newest task wins, because that is what the tilt stack does.
     * Home, SystemUI and our own window drop out the way quickstep drops them.
     */
    private fun real(pm: PackageManager, kept: Set<String>): List<Card>? {
        val tasks = Privileged.tasks() ?: return null
        val home = runCatching {
            pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        }.getOrNull()

        val out = ArrayList<Card>(tasks.size)
        val seen = HashSet<String>()
        // Our own PackageManager cannot see a work profile app, so
        // getApplicationInfo() returned null and the task was dropped outright:
        // work apps never reached the deck at all. LauncherApps is cross
        // profile, so it resolves what PackageManager refuses. Built lazily,
        // because the common case never needs it.
        val crossProfile: Map<String, LaunchableApp> by lazy {
            runCatching { AppUtils.launchable(this).associateBy { it.pkg } }
                .getOrDefault(emptyMap())
        }
        for (task in tasks) {
            val pkg = task.pkg
            if (pkg == packageName || pkg == SYSTEMUI || pkg == home) continue
            if (pkg in skip) continue
            if (!seen.add(pkg)) continue
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            if (info == null) {
                val alt = crossProfile[pkg] ?: continue
                val key = AppUtils.keyFor(this, alt.pkg, alt.userSerial)
                out.add(
                    Card(
                        pkg = pkg,
                        label = alt.label,
                        icon = runCatching { AppUtils.icon(this, key) }.getOrNull(),
                        shot = snaps.peek(pkg),
                        pinned = pkg in kept,
                        taskId = task.taskId,
                    )
                )
                continue
            }
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            out.add(
                Card(
                    pkg = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                    // Memory only. A disk decode here would land inside the pull.
                    shot = snaps.peek(pkg),
                    pinned = pkg in kept,
                    taskId = task.taskId,
                )
            )
        }
        return if (out.isEmpty()) null else out
    }

    private fun forget(card: Card) {
        // Actually remove the task, the way a swipe in the system switcher does.
        // The process is not killed, it is released like any backgrounded app.
        if (card.taskId >= 0) Privileged.close(card.taskId)
        recents.drop(card.pkg)
        recents.pin(card.pkg, false)
        snaps.drop(card.pkg)
    }

    /**
     * Close all. One UI leaves anything you have marked Keep open exactly where
     * it is, so this only drops the rest, and only closes the deck when there
     * is nothing left in it to look at.
     */
    private fun forgetAll() {
        val kept = recents.pinned()

        // Real removal for everything not kept open. Deliberately not a force
        // stop: Samsung does not kill your apps here either, it drops the tasks.
        if (Privileged.ready()) {
            io.execute {
                Privileged.tasks()
                    ?.filterNot { it.pkg in kept || it.pkg == packageName }
                    ?.forEach { Privileged.close(it.taskId) }
            }
        }

        recents.list().filterNot { it in kept }.forEach { snaps.drop(it) }
        recents.clear()
        if (recents.list().isEmpty()) {
            main.postDelayed({ close() }, 240)
        } else {
            scheduleFill()
        }
    }

    /** Keep open, off the per card menu. */
    private fun pin(card: Card, on: Boolean) {
        recents.pin(card.pkg, on)
    }

    /**
     * App info, off the per card menu. Thrown at the cover panel like every
     * other launch, so it does not quietly open on the folded away screen.
     */
    private fun appInfo(card: Card) {
        close()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", card.pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (coverId != Display.INVALID_DISPLAY) coverId else Cover.awake(this)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure { runCatching { startActivity(intent) } }
    }

    private fun launch(card: Card) {
        close()
        val target = if (coverId != Display.INVALID_DISPLAY) coverId else Cover.awake(this)

        // Resume the actual task so the app comes back exactly where it was
        // left. The launcher intent below can only restart it at its top screen.
        if (card.taskId >= 0 && Privileged.resume(card.taskId, target)) return

        val intent = packageManager.getLaunchIntentForPackage(card.pkg) ?: return
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target)
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure { runCatching { startActivity(intent) } }
    }

    // ------------------------------------------- what ConfigActivity needs

    fun cards(): List<Card> = build()

    fun open(card: Card) = launch(card)

    /**
     * Clear from settings. Unlike Close all this ignores Keep open, because the
     * user asked for the whole list gone, not for the deck tidied.
     */
    fun forgetEverything() {
        // Settings asked for the whole list gone, pins and all, so nothing is
        // spared here.
        if (Privileged.ready()) {
            io.execute {
                Privileged.tasks()
                    ?.filterNot { it.pkg == packageName }
                    ?.forEach { Privileged.close(it.taskId) }
            }
        }
        recents.list().forEach { snaps.drop(it) }
        recents.wipe()
        main.postDelayed({ close() }, 240)
    }

    fun safeInsets(): Rect = Rect(panelSafe)

    companion object {
        private const val TAG = "RecentsEngine"
        private const val SYSTEMUI = "com.android.systemui"

        /**
         * Which switcher the pull opens.
         *
         * true  - Samsung's own RecentsActivity, thrown at the cover panel.
         * false - the deck in [Switcher] that this project built.
         *
         * The deck is not removed. Every card, spring, tilt and snapshot is
         * still here and still wired; it is only asleep. Flip this to false and
         * all of it wakes up exactly as it was, with the dwell and the drag
         * back in charge.
         */
        const val NATIVE_RECENTS = true

        private const val LAUNCHER_PKG = "com.sec.android.app.launcher"
        private const val RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity"

        /** Breathing room between killing the launcher and starting recents. */
        private const val NATIVE_RESTART_GAP = 60L

        /**
         * How many touch slops up the strip a native pull has to travel before
         * it counts. Deliberately short: there is no hold any more, so the only
         * thing separating a pull from a tap is distance.
         */
        private const val NATIVE_TRIGGER_SLOPS = 2.5f

        /** These three action strings are fixed and must not be renamed. */
        const val ACTION_CONFIG = "RIF"
        const val ACTION_LAUNCH = "apps.ijp.coverrecents.ALR"
        const val ACTION_HIERARCHY = "apps.ijp.coverrecents.PRINT_HIERARCHY"

        const val ACTION_SHOW = "com.tv.coverscreen.SHOW"
        const val ACTION_HIDE = "com.tv.coverscreen.HIDE"

        private const val SHOT_GAP = 1200L
        private const val SETTLE = 550L
        private const val MIN_STRIP_DP = 24

        /**
         * How far up off the bottom edge the catcher sits. A case on a Flip
         * covers the bottom of the cover panel, so the band that starts the pull
         * begins above the lip instead of under it.
         */
        private const val STRIP_LIFT_DP = 12f
        private const val REMEASURE_DELAY = 400L
        private const val REFILL_DELAY = 350L
        private const val TOUCH_SLOP_DP = 10f

        /** Pull fractions per second past which the throw, not the distance, decides. */
        private const val FLING = 1.1f

        /**
         * How far up the panel a full pull is. The cover panel is barely 370dp
         * tall, so a fraction tuned on the inner screen turns into a stretch up
         * here. Shorter travel, same feel.
         */
        private const val PULL_FRACTION = 0.30f

        /** Past this much of the pull, letting go finishes instead of cancelling. */
        private const val COMMIT = 0.34f

        /**
         * Hard ceiling on the commit point. gT3 from the library is measured on
         * the inner screen and can be taller than this panel's entire pull, which
         * is what made the deck feel like it never wanted to come out.
         */
        private const val COMMIT_MAX = 0.42f

        @Volatile
        var live: RecentsEngine? = null
            private set
    }
}
