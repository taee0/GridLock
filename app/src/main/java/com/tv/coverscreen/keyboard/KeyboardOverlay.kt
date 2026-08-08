package com.tv.coverscreen.keyboard

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import apps.ijp.coverscreen.launcher.data.Settings
import com.tv.coverscreen.DisplayUtils
import com.tv.coverscreen.R
import kotlin.math.roundToInt

/**
 * The widget keypad, floating over other apps on the cover panel.
 *
 * Why this exists: RemoteViews cannot host a real EditText or summon the system
 * keyboard on a secondary display, so the widget grew its own keypad, and that
 * keypad happens to sit on the cover screen perfectly - not by luck, but
 * because every key is layout_width 0dp with layout_weight 1, so the row is
 * fluid and fills whatever width it is handed. That property is exactly what
 * makes it safe to lift into a window of a different size. So it is lifted
 * rather than redrawn: this class inflates search_keypad.xml, the same file the
 * widget inflates.
 *
 * Window type is TYPE_ACCESSIBILITY_OVERLAY first. This app is already an
 * accessibility service, and that type costs no permission and no user-facing
 * grant, unlike TYPE_APPLICATION_OVERLAY which needs "display over other apps"
 * turned on by hand. TYPE_APPLICATION_OVERLAY stays as the fallback.
 *
 * FLAG_NOT_FOCUSABLE and FLAG_ALT_FOCUSABLE_IM are both load bearing. Without
 * the first, this window steals focus the instant it appears and the field
 * underneath loses its cursor, so there is nothing left to type into. Without
 * the second, the real IME is still free to come up behind us.
 *
 * Two states, one window:
 *
 *   expanded  - preview strip, control bar, keypad
 *   collapsed - a small tab (kb_pill) at the bottom edge
 *
 * Closing collapses rather than destroys. This is not cosmetic. A field that
 * already holds focus fires no focus event when you tap it a second time, and
 * an EditText tap does not reliably fire a click event either, so once the
 * window is gone there is no event left to bring it back. The pill is the
 * guaranteed way back in; TYPE_VIEW_TEXT_SELECTION_CHANGED, which does fire on
 * every tap because the caret moves, is the automatic one.
 */
object KeyboardOverlay {

    private const val TAG = "KeyboardOverlay"

    /** Focus events arrive in bursts while a screen settles. Coalesce them. */
    private const val SHOW_DEBOUNCE = 120L

    /** How long a field can be gone before the keyboard gives up and hides. */
    private const val IDLE_HIDE = 900L

    /** Text-changed arrives once per keystroke; do not re-walk the tree twice. */
    private const val PREVIEW_DEBOUNCE = 45L

    private const val CARET = "\u2502"
    private const val DOT = "\u2022"

    /** Placeholder text is dimmed so it cannot be mistaken for typed text. */
    private const val HINT_ALPHA = 0.45f

    /** Held-arrow repeat interval. The long-press timeout is the initial delay. */
    private const val REPEAT_RATE = 55L

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wm: WindowManager? = null
    @Volatile private var root: View? = null
    private var lp: WindowManager.LayoutParams? = null

    private var display = -1
    private var shift = false
    private var numeric = false
    private var collapsed = false
    private var lift = 0

    /**
     * Held as fields so they can be taken back off the queue. One tap into a
     * search box fires several focus and content events in a row, and each one
     * would otherwise queue its own show.
     */
    private var pendingShow: Runnable? = null
    private var pendingHide: Runnable? = null
    private var pendingPreview: Runnable? = null

    /** The running auto-repeat, held so releasing the arrow can stop it. */
    private var repeating: Runnable? = null

    /**
     * Compose mode.
     *
     * A notification reply has no text field to live in. The text exists only
     * as a RemoteInput bundle handed back to the posting app when the reply is
     * sent, so there is no editable node for TypeBridge to write into and no
     * caret belonging to anyone else. Keystrokes are collected here instead and
     * TypeBridge is bypassed entirely, while the caret, the arrows, the preview
     * strip and tap-to-place all behave exactly as they do normally.
     */
    private var composing = false
    private val buf = StringBuilder()
    private var bufCaret = 0
    private var composeHint = ""
    private var onSend: ((String) -> Unit)? = null

    val showing: Boolean get() = root != null

    // -------------------------------------------------------------- triggers

    /**
     * Called for every accessibility event the service sees. Decides for itself
     * which ones matter, so the call site in RecentsEngine stays one line.
     */
    fun watch(svc: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = Settings(svc)
        if (!prefs.coverKeyboard) return

        // While a reply is being composed the keyboard belongs to the shade,
        // not to whatever field happens to be focused behind it.
        if (composing) return

        when (event.eventType) {
            // TEXT_SELECTION_CHANGED is the important one. FOCUSED only fires
            // when focus actually moves, so it misses every tap on a field that
            // is already focused - which is precisely the case after the user
            // has closed the keyboard and wants it back.
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            -> {
                val src = event.source ?: return
                if (!src.isEditable) return
                val id = runCatching { src.window?.displayId }.getOrNull() ?: return
                if (id == Display.DEFAULT_DISPLAY) return
                queueShow(svc, prefs.coverKeyboardAuto)
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> queuePreview(svc)

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (showing) queueIdleCheck(svc)
        }
    }

    private fun queueShow(svc: AccessibilityService, expandIt: Boolean) {
        pendingShow?.let { main.removeCallbacks(it) }
        val task = Runnable {
            when {
                root == null -> show(svc, expandIt)
                collapsed && expandIt -> expand(svc)
                !collapsed -> refreshPreview(svc)
            }
        }
        pendingShow = task
        main.postDelayed(task, SHOW_DEBOUNCE)
    }

    private fun queueIdleCheck(svc: AccessibilityService) {
        pendingHide?.let { main.removeCallbacks(it) }
        val task = Runnable {
            if (showing && !composing && !TypeBridge.hasField(svc, display)) hide()
        }
        pendingHide = task
        main.postDelayed(task, IDLE_HIDE)
    }

    private fun queuePreview(svc: AccessibilityService) {
        if (!showing || collapsed) return
        pendingPreview?.let { main.removeCallbacks(it) }
        val task = Runnable { refreshPreview(svc) }
        pendingPreview = task
        main.postDelayed(task, PREVIEW_DEBOUNCE)
    }

    private fun cancelPending() {
        stopRepeat()
        pendingShow?.let { main.removeCallbacks(it) }
        pendingHide?.let { main.removeCallbacks(it) }
        pendingPreview?.let { main.removeCallbacks(it) }
        pendingShow = null
        pendingHide = null
        pendingPreview = null
    }

    // ------------------------------------------------------------ show / hide

    fun toggle(svc: AccessibilityService) {
        when {
            root == null -> show(svc)
            collapsed -> expand(svc)
            else -> collapse()
        }
    }

    fun show(svc: AccessibilityService, startExpanded: Boolean = true) {
        val prefs = Settings(svc)
        if (!prefs.coverKeyboard) return
        if (showing) return

        val cover = DisplayUtils.coverDisplay(svc)
        if (cover == null) {
            Log.w(TAG, "no cover display, not showing")
            return
        }
        display = cover.displayId
        lift = prefs.coverKeyboardLift
        collapsed = !startExpanded

        val ctx = svc.createDisplayContext(cover)
        val themed = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
        val manager = ctx.getSystemService(WindowManager::class.java) ?: return
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.keyboard_overlay, null, false)

        bind(svc, view)
        relabel(view)
        faces(view)

        val params = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        var chosen = params
        var added = runCatching { manager.addView(view, params); true }
            .onFailure { Log.w(TAG, "accessibility overlay refused, trying app overlay", it) }
            .getOrDefault(false)

        if (!added) {
            if (!android.provider.Settings.canDrawOverlays(svc)) {
                Log.w(TAG, "no overlay permission either, giving up")
                return
            }
            val fallback = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            added = runCatching { manager.addView(view, fallback); true }
                .onFailure { Log.w(TAG, "app overlay refused too", it) }
                .getOrDefault(false)
            if (!added) return
            chosen = fallback
        }

        wm = manager
        root = view
        lp = chosen
        if (!collapsed) refreshPreview(svc)
        Log.d(TAG, "up on display " + display + " collapsed=" + collapsed)
    }

    fun expand(svc: AccessibilityService) {
        val view = root ?: return show(svc)
        collapsed = false
        faces(view)
        applyGeometry()
        refreshPreview(svc)
    }

    /**
     * Shrink to the pill rather than tearing the window down, so there is
     * always something left to tap.
     */
    fun collapse() {
        val view = root ?: return
        collapsed = true
        faces(view)
        applyGeometry()
    }

    fun hide() {
        val view = root ?: return
        runCatching { wm?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView", it) }
        root = null
        wm = null
        lp = null
        shift = false
        numeric = false
        collapsed = false
        cancelPending()
        endCompose()
    }

    /** Which of the two states is on screen. */
    private fun faces(view: View) {
        view.findViewById<View>(R.id.kb_full)?.visibility =
            if (collapsed) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.kb_pill)?.visibility =
            if (collapsed) View.VISIBLE else View.GONE
    }

    /**
     * Collapsed, the window wraps the pill so it cannot swallow taps meant for
     * the app underneath. Expanded, it spans the panel and honours the lift the
     * user dragged it to.
     */
    private fun applyGeometry() {
        val params = lp ?: return
        val view = root ?: return
        params.width =
            if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT
            else WindowManager.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.BOTTOM or (if (collapsed) Gravity.END else Gravity.START)
        params.y = if (collapsed) 0 else lift
        runCatching { wm?.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "updateViewLayout", it) }
    }

    private fun params(type: Int) = WindowManager.LayoutParams(
        if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT
        else WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or (if (collapsed) Gravity.END else Gravity.START)
        y = if (collapsed) 0 else lift
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // --------------------------------------------------------------- compose

    /**
     * Take the keyboard over to compose a standalone string, handing it to
     * [send] when enter is pressed. Used for notification replies.
     */
    fun compose(svc: AccessibilityService, hint: String, send: (String) -> Unit) {
        composing = true
        buf.setLength(0)
        bufCaret = 0
        composeHint = hint
        onSend = send
        if (root == null) show(svc, true) else expand(svc)
        refreshPreview(svc)
    }

    fun endCompose() {
        composing = false
        buf.setLength(0)
        bufCaret = 0
        composeHint = ""
        onSend = null
    }

    /** Caret movement, in the buffer while composing and in the field otherwise. */
    private fun walk(svc: AccessibilityService, delta: Int) {
        if (composing) {
            bufCaret = (bufCaret + delta).coerceIn(0, buf.length)
            refreshPreview(svc)
            return
        }
        TypeBridge.nudge(svc, display, delta)
        queuePreview(svc)
    }

    // ---------------------------------------------------------------- preview

    /**
     * Mirror the target field into the strip above the keys.
     *
     * Cover-panel windows are frequently unscrollable, and plenty of them put
     * their text box somewhere the panel simply does not reach, so this is
     * regularly the only place the text is legible at all. Password fields are
     * masked here for the same reason they are masked anywhere.
     */
    private fun refreshPreview(svc: AccessibilityService) {
        val view = root ?: return
        val strip = view.findViewById<TextView>(R.id.kb_preview) ?: return
        if (composing) {
            if (buf.isEmpty()) {
                strip.alpha = HINT_ALPHA
                strip.text =
                    if (composeHint.isEmpty()) svc.getString(R.string.kb_preview_empty)
                    else composeHint
                return
            }
            strip.alpha = 1f
            strip.text = StringBuilder(buf).insert(bufCaret, CARET).toString()
            return
        }
        val node = TypeBridge.editable(svc, display)
        if (node == null) {
            strip.alpha = HINT_ALPHA
            strip.text = svc.getString(R.string.kb_preview_empty)
            return
        }
        // TypeBridge.body(), not node.text: an empty field reports its hint as
        // its text, and presenting that as though the user had typed it is the
        // display half of the same defect.
        val raw = TypeBridge.body(node)
        val body = if (node.isPassword) DOT.repeat(raw.length) else raw
        if (body.isEmpty()) {
            // Show the app's own placeholder, dimmed, so it reads as a prompt
            // rather than as content the user put there and could delete.
            val hint = TypeBridge.hint(node)
            strip.alpha = HINT_ALPHA
            strip.text =
                if (hint.isNullOrEmpty()) svc.getString(R.string.kb_preview_empty) else hint
            return
        }
        strip.alpha = 1f
        val end = node.textSelectionEnd
        val caret = if (end in 0..body.length) end else body.length
        strip.text = StringBuilder(body).insert(caret, CARET).toString()
    }

    // ---------------------------------------------------------------- wiring

    /**
     * The only real difference from the widget. There, each key gets
     * setOnClickPendingIntent and the broadcast lands in SearchState. Here the
     * same id from the same table gets an ordinary listener that lands in
     * [TypeBridge] instead. Same layout, same key table, different destination.
     */
    private fun bind(svc: AccessibilityService, view: View) {
        Keys.LETTERS.forEach { (ch, id) ->
            val key = view.findViewById<TextView>(id) ?: return@forEach
            key.setOnClickListener {
                buzz(svc)
                type(svc, resolve(ch))
            }
            val alt = Keys.ALT[ch]
            if (alt != null) {
                key.setOnLongClickListener {
                    buzz(svc)
                    type(svc, alt.toString())
                    true
                }
            }
        }

        view.findViewById<TextView>(R.id.key_backspace)?.setOnClickListener {
            buzz(svc)
            if (composing) {
                if (bufCaret > 0) {
                    buf.deleteCharAt(bufCaret - 1)
                    bufCaret--
                }
                refreshPreview(svc)
            } else {
                TypeBridge.backspace(svc, display)
                queuePreview(svc)
            }
        }
        view.findViewById<TextView>(R.id.key_space)?.setOnClickListener {
            buzz(svc)
            type(svc, " ")
        }
        view.findViewById<TextView>(R.id.kb_enter)?.setOnClickListener {
            buzz(svc)
            if (composing) {
                val text = buf.toString()
                val done = onSend
                endCompose()
                collapse()
                done?.invoke(text)
            } else {
                TypeBridge.enter(svc, display)
                queuePreview(svc)
            }
        }

        view.findViewById<TextView>(R.id.kb_close)?.setOnClickListener { collapse() }
        view.findViewById<TextView>(R.id.kb_pill)?.setOnClickListener { expand(svc) }

        view.findViewById<TextView>(R.id.kb_shift)?.setOnClickListener {
            buzz(svc)
            shift = !shift
            relabel(view)
        }
        view.findViewById<TextView>(R.id.kb_num)?.setOnClickListener {
            buzz(svc)
            numeric = !numeric
            relabel(view)
        }

        hold(svc, view.findViewById<View>(R.id.kb_left), -1)
        hold(svc, view.findViewById<View>(R.id.kb_right), 1)
        aim(svc, view)
        drag(svc, view)
    }

    /** Which character a key produces, given the current layer. */
    private fun resolve(ch: Char): String = when {
        numeric -> (Keys.ALT[ch] ?: ch).toString()
        shift -> ch.uppercaseChar().toString()
        else -> ch.toString()
    }

    private fun relabel(view: View) {
        Keys.LETTERS.forEach { (ch, id) ->
            view.findViewById<TextView>(id)?.text = resolve(ch)
        }
        view.findViewById<TextView>(R.id.kb_shift)?.alpha = if (shift) 1f else 0.45f
        view.findViewById<TextView>(R.id.kb_num)?.alpha = if (numeric) 1f else 0.45f
    }

    private fun type(svc: AccessibilityService, text: String) {
        if (composing) {
            buf.insert(bufCaret, text)
            bufCaret += text.length
            if (shift && !numeric) {
                shift = false
                root?.let { relabel(it) }
            }
            refreshPreview(svc)
            return
        }
        val landed = TypeBridge.commit(svc, display, text)
        if (!landed) Log.w(TAG, "nothing accepted " + text)
        // One-shot shift, the way every soft keyboard behaves.
        if (shift && !numeric) {
            shift = false
            root?.let { relabel(it) }
        }
        queuePreview(svc)
    }

    /**
     * Vertical drag on the handle, remembered across sessions. The cover panel
     * has a camera cutout and a gesture area, and which one is in the way
     * depends on the app underneath, so this is left to the user rather than
     * guessed at. Negative lift is allowed so it can be pushed below the
     * nominal bottom edge.
     */
    private fun drag(svc: AccessibilityService, view: View) {
        val handle = view.findViewById<View>(R.id.kb_handle) ?: return
        var startY = 0f
        var startLift = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY
                    startLift = lift
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lift = (startLift + (startY - e.rawY).roundToInt()).coerceIn(-200, 900)
                    applyGeometry()
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    Settings(svc).coverKeyboardLift = lift
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Tap an arrow to step one character, hold it to run.
     *
     * Auto-repeat matters more here than on an ordinary keyboard, because the
     * strip is the only legible copy of the text on plenty of cover-panel
     * pages, so walking to the middle of a message one tap at a time would be
     * the entire interaction.
     */
    private fun hold(svc: AccessibilityService, key: View?, step: Int) {
        if (key == null) return
        key.setOnClickListener {
            buzz(svc)
            walk(svc, step)
        }
        key.setOnLongClickListener {
            buzz(svc)
            startRepeat(svc, step)
            true
        }
        key.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> stopRepeat()
            }
            false
        }
    }

    private fun startRepeat(svc: AccessibilityService, step: Int) {
        stopRepeat()
        val task = object : Runnable {
            override fun run() {
                walk(svc, step)
                main.postDelayed(this, REPEAT_RATE)
            }
        }
        repeating = task
        main.postDelayed(task, REPEAT_RATE)
    }

    private fun stopRepeat() {
        repeating?.let { main.removeCallbacks(it) }
        repeating = null
    }

    /**
     * Tap anywhere in the preview strip to put the caret there. This is the
     * press-and-it-splices half, and it is exact rather than estimated: the
     * strip already mirrors the field character for character, so Layout can
     * convert a touch x straight into a character offset.
     *
     * Two corrections are needed. The caret glyph is inserted into the string
     * on display and does not exist in the field, so any offset past it is one
     * too high. And when the string is ellipsized the drawn glyphs no longer
     * line up with what Layout measured, so those taps are ignored rather than
     * acted on wrongly, and the arrows remain the way to move.
     */
    private fun aim(svc: AccessibilityService, view: View) {
        val strip = view.findViewById<TextView>(R.id.kb_preview) ?: return
        strip.setOnTouchListener { v, e ->
            if (e.actionMasked != MotionEvent.ACTION_UP) return@setOnTouchListener true
            val tv = v as? TextView ?: return@setOnTouchListener true
            val layout = tv.layout ?: return@setOnTouchListener true
            if (layout.lineCount < 1) return@setOnTouchListener true
            if (layout.getEllipsisCount(0) > 0) return@setOnTouchListener true
            val shown = tv.text?.toString() ?: ""
            val glyph = shown.indexOf(CARET)
            if (glyph < 0) return@setOnTouchListener true
            var off = layout.getOffsetForHorizontal(
                0, e.x - tv.totalPaddingLeft + tv.scrollX
            )
            if (off > glyph) off -= 1
            buzz(svc)
            if (composing) {
                bufCaret = off.coerceIn(0, buf.length)
                refreshPreview(svc)
            } else {
                TypeBridge.moveTo(svc, display, off)
                queuePreview(svc)
            }
            true
        }
    }

    private fun buzz(ctx: Context) {
        if (!Settings(ctx).haptics) return
        runCatching {
            ctx.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }
}
