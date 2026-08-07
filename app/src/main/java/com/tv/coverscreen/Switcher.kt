package com.tv.coverscreen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The deck. One UI 7 / 8 Tilt Stack.
 *
 * Built once when the service binds to the panel and then parked with its
 * window already up and its cards already bound, so the first frame of a pull
 * has nothing left to do but move something that exists. It used to be born
 * inside the first ACTION_MOVE, which meant inflating a view tree, measuring
 * it, waiting on a surface and decoding bitmaps while your thumb was already
 * halfway up the panel. That was the lurch.
 *
 * [drag] tracks the finger with no easing on the travel, and [settle] hands the
 * velocity you let go with to a spring instead of a fixed duration curve.
 *
 * The look is Samsung's current one rather than the flat One UI 6 row this
 * started as. [TiltStack] does the stacking; what lives here is the window, the
 * gesture, the blur behind it and the per app sheet the icon chip opens.
 */
class Switcher(
    private val ctx: Context,
    private val wm: WindowManager,
    private var panelWidth: Int,
    private var panelHeight: Int,
    safe: Rect,
    private val onTap: (Card) -> Unit,
    private val onDrop: (Card) -> Unit,
    private val onClearAll: () -> Unit,
    private val onDismiss: () -> Unit,
    /** Kept open, the One UI per card pin. */
    private val onKeepOpen: (Card, Boolean) -> Unit = { _, _ -> },
    private val onAppInfo: (Card) -> Unit = {},
) {

    private val root: View = LayoutInflater.from(ctx).inflate(R.layout.switcher, null, false)
    private val deck: View = root.findViewById(R.id.deck)
    private val list: RecyclerView = root.findViewById(R.id.cards)
    private val clear: TextView = root.findViewById(R.id.clear)
    private val none: TextView = root.findViewById(R.id.none)
    private val sheet: LinearLayout = root.findViewById(R.id.menu)
    private val keep: TextView = root.findViewById(R.id.keep)
    private val info: TextView = root.findViewById(R.id.info)
    private val shut: TextView = root.findViewById(R.id.shut)
    private val cards = CardAdapter(::tapped, ::chipped)

    /** Own the scrim drawable so the dim is an alpha poke, not a new drawable. */
    private val dim = ColorDrawable(Color.BLACK).apply { alpha = 0 }
    private val ease = PathInterpolator(0.17f, 0.17f, 0.2f, 1f)
    private val cardWidth = ctx.resources.getDimensionPixelSize(R.dimen.card_w)
    private val stackLift = ctx.resources.getDimensionPixelSize(R.dimen.stack_lift).toFloat()

    /** The stack transform. Layout stays a flat row; this is what tilts it. */
    private val tilt = TiltStack(list, cardWidth, stackLift)

    /**
     * The re-collapse after a card is thrown away. Left null while a finger is
     * on the glass, because the default animator fights a drag, but One UI does
     * spring the gap shut once the deck is open and still, so it goes back on
     * the moment the gesture is over.
     */
    private val collapse = DefaultItemAnimator().apply {
        removeDuration = 220
        moveDuration = 300
        addDuration = 0
        changeDuration = 140
    }

    private var added = false
    private var locked = false
    private var pulling = false
    private var progress = 0f
    private var travel = panelHeight * DECK_TRAVEL
    private var blur = 0
    private var menuFor: Card? = null

    /** The card currently being thrown, which the stack has to skip. */
    private var swiping: RecyclerView.ViewHolder? = null

    private val spring = Spring(
        onFrame = { paint(it) },
        onRest = { rested() },
    )

    /** Set while the spring is running towards open. */
    private var landing = false
    private var onClosed: (() -> Unit)? = null

    val open: Boolean get() = locked

    init {
        root.background = dim
        root.visibility = View.INVISIBLE

        list.layoutManager = LinearLayoutManager(ctx, RecyclerView.HORIZONTAL, false)
        list.adapter = cards
        list.clipToPadding = false
        list.clipChildren = false
        list.setHasFixedSize(true)
        // The stack keeps three cards a side alive, so the pool has to be
        // deeper than the old flat row needed.
        list.setItemViewCacheSize(8)
        list.itemAnimator = null
        PagerSnapHelper().attachToRecyclerView(list)

        fit(safe)

        // The measured value can arrive after the window is up, and it changes
        // when auto rotate turns the panel, so take the live one too.
        root.setOnApplyWindowInsetsListener { _, insets ->
            insets.displayCutout?.let { c ->
                fit(Rect(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom))
            }
            insets
        }

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = shade()

            override fun onScrollStateChanged(r: RecyclerView, state: Int) {
                // Anything that moves the deck out from under the sheet should
                // take the sheet with it.
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) closeMenu()
            }
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> shade() }

        // Flick a card up and it is gone, same as the inner screen.
        //
        // SimpleCallback takes (dragDirs, swipeDirs), in that order. This read
        // (UP, 0) for a long time, which is drag up and no swipe at all, so
        // onSwiped below could never fire however hard the card was thrown.
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
            override fun onMove(
                r: RecyclerView,
                a: RecyclerView.ViewHolder,
                b: RecyclerView.ViewHolder,
            ) = false

            /** Kept open means kept open. One UI will not let you flick one away. */
            override fun getSwipeDirs(
                r: RecyclerView,
                holder: RecyclerView.ViewHolder,
            ): Int {
                val card = cards.at(holder.bindingAdapterPosition)
                return if (card != null && card.pinned) 0 else super.getSwipeDirs(r, holder)
            }

            override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 0.7f

            /**
             * Default is half the card's height. The card is 252dp on a panel
             * that is 374dp tall, so half of it is most of the screen and the
             * throw never completes. Samsung lets go of a card on a flick.
             */
            override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = SWIPE_THRESHOLD

            /**
             * [TiltStack] rewrites every child's transform on every frame it
             * runs, so the card being thrown has to be left out of it or the
             * two of them fight over the same view.
             */
            override fun onSelectedChanged(
                holder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(holder, actionState)
                swiping =
                    if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) holder else null
                if (swiping != null) closeMenu()
            }

            override fun clearView(r: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(r, holder)
                swiping = null
                // A card that was dragged part way and let go keeps its offset
                // otherwise, because the stack never writes translationY.
                holder.itemView.translationY = 0f
                shade()
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val position = holder.bindingAdapterPosition
                val card = cards.at(position) ?: return
                swiping = null
                closeMenu()
                cards.removeAt(position)
                onDrop(card)
                list.post { shade() }
                if (cards.itemCount == 0) empty()
            }
        }).attachToRecyclerView(list)

        clear.setOnClickListener {
            closeMenu()
            // Close all leaves kept open cards alone, the way One UI does.
            val kept = cards.all().filter { it.pinned }
            cards.submit(kept)
            onClearAll()
            if (kept.isEmpty()) empty() else list.post { shade() }
        }

        root.setOnClickListener {
            when {
                menuFor != null -> closeMenu()
                locked -> onDismiss()
            }
        }

        keep.setOnClickListener {
            val card = menuFor ?: return@setOnClickListener
            val next = !card.pinned
            cards.repin(card.pkg, next)
            onKeepOpen(card, next)
            closeMenu()
        }
        info.setOnClickListener {
            val card = menuFor ?: return@setOnClickListener
            closeMenu()
            onAppInfo(card)
        }
        shut.setOnClickListener {
            val card = menuFor ?: return@setOnClickListener
            closeMenu()
            val at = cards.all().indexOfFirst { it.pkg == card.pkg }
            if (at >= 0) cards.removeAt(at)
            onDrop(card)
            list.post { shade() }
            if (cards.itemCount == 0) empty()
        }

        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && locked) {
                if (menuFor != null) closeMenu() else onDismiss()
                true
            } else {
                false
            }
        }
    }

    // ------------------------------------------------------------------ setup

    /**
     * Put the window up now, invisible and untouchable, and leave it there.
     *
     * An invisible view still gets measured and laid out, so the whole tree,
     * the surface and the first set of holders are all paid for long before
     * anyone touches the nav bar. Costs one transparent window that draws
     * nothing and takes no input.
     */
    fun prime() {
        if (added) return
        runCatching {
            wm.addView(root, params(touchable = false))
            added = true
        }
        paint(0f)
    }

    /** Swap the card list in while nothing is happening. Ignored mid gesture. */
    fun load(items: List<Card>) {
        if (pulling || locked || spring.active) return
        closeMenu()
        cards.submit(items)
        val bare = items.isEmpty()
        none.visibility = if (bare) View.VISIBLE else View.GONE
        list.visibility = if (bare) View.GONE else View.VISIBLE
        clear.visibility = if (bare) View.GONE else View.VISIBLE
        if (!bare) list.scrollToPosition(0)
        list.post { shade() }
    }

    /** Panel turned or changed size. Re-measure without tearing anything down. */
    fun resize(width: Int, height: Int, safe: Rect) {
        panelWidth = width
        panelHeight = height
        travel = panelHeight * DECK_TRAVEL
        closeMenu()
        fit(safe)
        if (!locked) paint(progress)
    }

    // ------------------------------------------------------------------- pull

    /** Finger has claimed the gesture. Show the parked window and start moving. */
    fun begin() {
        if (!added) prime()
        if (pulling) return
        spring.stop()
        pulling = true
        landing = false
        // The animator would try to animate its way through a finger driven
        // drag. It comes back in grab().
        list.itemAnimator = null
        // Everything that moves during the drag goes on one layer, so the pull
        // is a matrix and an alpha rather than a re-draw of the card tree.
        deck.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        root.visibility = View.VISIBLE
        paint(0f)
    }

    /** [amount] is 0 at the nav bar and 1 fully open. Called on every move. */
    fun drag(amount: Float) {
        if (!pulling) return
        // Past the top it gets stiff instead of stopping dead, so a hard pull
        // still has somewhere to go.
        val p = if (amount <= 1f) amount.coerceAtLeast(0f) else 1f + (amount - 1f) * RUBBER
        paint(p)
    }

    /**
     * Let go. [velocity] is pull fractions per second, positive upward, taken
     * straight off the touch stream.
     */
    fun settle(openIt: Boolean, velocity: Float, then: () -> Unit) {
        if (!added) return
        pulling = false
        landing = openIt
        onClosed = then
        spring.start(progress, if (openIt) 1f else 0f, velocity)
    }

    private fun rested() {
        deck.setLayerType(View.LAYER_TYPE_NONE, null)
        if (landing) {
            grab()
        } else {
            val done = onClosed
            onClosed = null
            done?.invoke()
        }
    }

    /** Take the touch stream over once the deck is all the way out. */
    private fun grab() {
        if (!added || locked) return
        locked = true
        runCatching {
            wm.updateViewLayout(root, params(touchable = true))
            root.requestFocus()
        }
        // Open and still. Springing the gap shut after a dismissal is safe now.
        list.itemAnimator = collapse
        shade()
    }

    /**
     * One frame. Travel is linear in [amount] on purpose: the deck has to sit
     * exactly where the thumb is, and any curve on the position is felt as lag
     * even when every frame lands on time. The dim, the fade and the blur are
     * eased, because nobody can feel a curve on an alpha.
     */
    private fun paint(amount: Float) {
        progress = amount
        val clamped = amount.coerceIn(0f, 1f)
        val eased = ease.getInterpolation(clamped)

        dim.alpha = (SCRIM * eased).toInt()
        blurTo((BLUR * eased).toInt())
        deck.translationY = (1f - amount) * travel
        deck.alpha = (clamped * 1.6f).coerceAtMost(1f)
        val k = 0.86f + 0.14f * amount
        deck.scaleX = k
        deck.scaleY = k
    }

    /**
     * Blur what is behind the deck, the way the real recents screen does. One
     * pass through the window manager per step of [BLUR_STEP] radius: the
     * compositor is happy to be told once a few frames, and not at 120Hz.
     *
     * Cross window blur is a hint. The device can refuse it outright, the
     * system turns it off in battery saver, and nothing here depends on it
     * landing, which is why the scrim still carries its own dim.
     */
    private fun blurTo(radius: Int) {
        if (!added) return
        if (radius == blur) return
        if (radius != 0 && blur != 0 && abs(radius - blur) < BLUR_STEP) return
        blur = radius
        runCatching {
            val p = root.layoutParams as? WindowManager.LayoutParams ?: return
            p.flags = if (radius > 0) {
                p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
            p.blurBehindRadius = radius
            wm.updateViewLayout(root, p)
        }
    }

    /**
     * Tilt Stack. Centre card square on and fully lit, everything else turned
     * about its inner edge, pulled in so it overlaps, shrunk, veiled and pushed
     * behind. One UI does this so your eye lands on the card you are about to
     * pick, and so a deck of ten still reads on a panel this small.
     */
    private fun shade() = tilt.apply(swiping?.itemView)

    // ------------------------------------------------------------------ sheet

    /**
     * The icon chip above a card was tapped. One UI answers that with a small
     * menu hung off the chip, not by switching to the app.
     */
    private fun chipped(card: Card, anchor: View) {
        if (!locked) {
            // Not open yet, so there is nothing to anchor to. Treat it as a tap
            // on the card, which is what the finger almost certainly meant.
            tapped(card)
            return
        }
        if (menuFor?.pkg == card.pkg) {
            closeMenu()
            return
        }
        menuFor = card
        keep.setText(if (card.pinned) R.string.keep_open_off else R.string.keep_open)

        val at = IntArray(2)
        val mine = IntArray(2)
        anchor.getLocationInWindow(at)
        root.getLocationInWindow(mine)

        sheet.visibility = View.VISIBLE
        sheet.alpha = 0f
        sheet.scaleX = 0.9f
        sheet.scaleY = 0.9f
        sheet.post {
            val x = at[0] - mine[0] + anchor.width / 2f - sheet.width / 2f
            val y = (at[1] - mine[1] + anchor.height + SHEET_GAP).toFloat()
            val room = (panelWidth - sheet.width - EDGE).toFloat()
            sheet.translationX = x.coerceIn(EDGE.toFloat(), room.coerceAtLeast(EDGE.toFloat()))
            sheet.translationY = y
            sheet.pivotX = sheet.width / 2f
            sheet.pivotY = 0f
            sheet.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(140)
                .setInterpolator(ease)
                .start()
        }
    }

    private fun closeMenu() {
        if (menuFor == null && sheet.visibility != View.VISIBLE) return
        menuFor = null
        sheet.animate().cancel()
        sheet.visibility = View.GONE
        sheet.alpha = 0f
    }

    /**
     * Keep everything out of the camera bump. The scrim still covers the whole
     * panel so the dim goes edge to edge, but nothing you need to see or touch
     * is laid out underneath the lenses, and the cards re-centre on what is
     * actually left rather than on the raw panel width.
     */
    private fun fit(s: Rect) {
        root.setPadding(s.left, s.top, s.right, s.bottom)
        val usable = (panelWidth - s.left - s.right).coerceAtLeast(1)
        val side = ((usable - cardWidth) / 2).coerceAtLeast(0)
        list.setPadding(side, 0, side, 0)
        tilt.stride(cardWidth)
        list.post { shade() }
    }

    private fun empty() {
        closeMenu()
        none.visibility = View.VISIBLE
        list.visibility = View.GONE
        clear.visibility = View.GONE
    }

    private fun tapped(card: Card) {
        if (!locked) return
        if (menuFor != null) {
            closeMenu()
            return
        }
        onTap(card)
    }

    // ---------------------------------------------------------------- closing

    /** Park it again. The window stays up so the next pull is still instant. */
    fun hide() {
        spring.stop()
        pulling = false
        landing = false
        onClosed = null
        closeMenu()
        list.itemAnimator = null
        deck.setLayerType(View.LAYER_TYPE_NONE, null)
        if (!added) return
        if (locked) {
            locked = false
            runCatching { wm.updateViewLayout(root, params(touchable = false)) }
        }
        paint(0f)
        root.visibility = View.INVISIBLE
    }

    /** Really take it down. Only on losing the panel or on service death. */
    fun destroy() {
        spring.stop()
        onClosed = null
        if (!added) return
        runCatching { wm.removeView(root) }
        added = false
        locked = false
        pulling = false
        blur = 0
    }

    private fun params(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!touchable) {
            flags = flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (blur > 0) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setFitInsetsTypes(0)
            windowAnimations = 0
            blurBehindRadius = blur
            // Take the whole panel including the bump, then pad it back off
            // ourselves in fit(). Left on default the system quietly letterboxes
            // the window instead, which is what was clipping the deck.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private companion object {
        /** How far down the deck sits at rest, as a share of the panel. */
        const val DECK_TRAVEL = 0.55f

        /** Resistance past fully open. */
        const val RUBBER = 0.18f

        /** Scrim at full open, out of 255. Lower now that there is a blur. */
        const val SCRIM = 166f

        /** Blur radius behind the deck at full open, in pixels. */
        const val BLUR = 44f

        /** Smallest change in radius worth another trip to the window manager. */
        const val BLUR_STEP = 6

        /** Gap between the icon chip and the sheet it opens, in pixels. */
        const val SHEET_GAP = 8

        /** Keep the sheet off the panel edge. */
        const val EDGE = 10

        /** Share of a card's height a throw has to cover to count. */
        const val SWIPE_THRESHOLD = 0.28f
    }
}
