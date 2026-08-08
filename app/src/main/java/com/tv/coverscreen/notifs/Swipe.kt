package com.tv.coverscreen.notifs

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Horizontal swipe-to-dismiss for one notification card.
 *
 * Hand-written rather than borrowed from RecyclerView's ItemTouchHelper. The
 * holder is a plain LinearLayout inside a ScrollView because the cover panel
 * never shows more than a handful of rows, and a RecyclerView inside a window
 * this small costs more than it saves.
 *
 * The contract with the tap listener is the delicate part. A finger that moves
 * less than the system touch slop is a tap, and this listener returns false so
 * the card's own OnClickListener still sees it. Past the slop the gesture turns
 * into a drag, the parent ScrollView is asked to stop intercepting, and the
 * card follows the finger.
 *
 * ## What v0.16 changed
 *
 * The v0.14 version committed on distance alone, which is the wrong test. A
 * short fast flick is the most natural way to throw a notification away and it
 * failed, because it never crossed a third of the row before the finger left
 * the glass. Velocity is tracked now and either test can commit the gesture.
 *
 * Three further corrections, all of them things that made the old one feel
 * unfinished rather than things that made it wrong:
 *
 *  - The axis decision is made once. The old code re-evaluated horizontal
 *    versus vertical on every MOVE, so a diagonal drag could start as a scroll
 *    and silently turn into a dismiss halfway down.
 *  - A second finger abandons the gesture instead of steering it.
 *  - The exit duration is derived from the distance left to travel and the
 *    speed the finger was already going, so the card keeps the momentum it was
 *    thrown with instead of always taking the same 140 ms.
 *
 * Rows that cannot be dismissed rubber-band and snap back rather than sliding
 * away and reappearing a moment later, which is what the old one did whenever
 * cancelNotification was refused.
 */
class Swipe(
    private val canDismiss: () -> Boolean,
    private val onDismiss: () -> Unit,
) : View.OnTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var abandoned = false
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var tracker: VelocityTracker? = null

    private var slop = -1
    private var minFling = 0
    private var maxFling = 0

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        if (slop < 0) {
            val cfg = ViewConfiguration.get(v.context)
            slop = cfg.scaledTouchSlop
            minFling = cfg.scaledMinimumFlingVelocity
            maxFling = cfg.scaledMaximumFlingVelocity
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                dragging = false
                abandoned = false
                pointer = e.getPointerId(0)
                // Touching a card that is mid-flight stops it where it is and
                // hands control back. cancel() deliberately skips the end
                // action, so an interrupted dismiss does not fire.
                v.animate().cancel()
                v.translationX = 0f
                v.alpha = 1f
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                return false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is a pinch or a palm, never a dismiss.
                abandoned = true
                if (dragging) {
                    dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    snap(v)
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (abandoned) return false
                tracker?.addMovement(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging) {
                    if (abs(dx) < slop) return false
                    // Decided once and never revisited: vertical wins ties, and
                    // losing here takes this row out of the running until the
                    // finger lifts.
                    if (abs(dx) <= abs(dy)) {
                        abandoned = true
                        return false
                    }
                    dragging = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                v.translationX = if (canDismiss()) dx else resist(dx, v.width)
                v.alpha = fade(v.translationX, v.width)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vx = 0f
                tracker?.let {
                    it.addMovement(e)
                    it.computeCurrentVelocity(1000, maxFling.toFloat())
                    vx = it.getXVelocity(pointer)
                    it.recycle()
                }
                tracker = null
                pointer = MotionEvent.INVALID_POINTER_ID
                if (!dragging) return false
                dragging = false
                v.parent?.requestDisallowInterceptTouchEvent(false)
                val go =
                    e.actionMasked == MotionEvent.ACTION_UP &&
                        canDismiss() &&
                        committed(v, vx)
                if (go) commit(v, vx) else snap(v)
                return true
            }
        }
        return false
    }

    /**
     * Either test can carry the gesture: far enough, or fast enough in the
     * direction it is already going. The direction check matters -- a finger
     * that drags right and then whips back left at the last moment has changed
     * its mind, and the sign comparison catches that.
     */
    private fun committed(v: View, vx: Float): Boolean {
        val dx = v.translationX
        if (abs(dx) < slop) return false
        if (abs(dx) > v.width * THRESHOLD) return true
        return abs(vx) >= minFling && vx.sign == dx.sign
    }

    private fun commit(v: View, vx: Float) {
        val dx = v.translationX
        val to = if (dx >= 0f) v.width.toFloat() else -v.width.toFloat()
        val speed = abs(vx).coerceAtLeast(minFling.toFloat()).coerceAtLeast(1f)
        val ms = ((abs(to - dx) / speed) * 1000f).toLong()
            .coerceIn(MIN_EXIT_MS, MAX_EXIT_MS)
        v.animate()
            .translationX(to)
            .alpha(0f)
            .setDuration(ms)
            .withEndAction {
                val container = v.parent as? View
                if (container == null) onDismiss() else collapse(container) { onDismiss() }
            }
            .start()
    }

    private fun snap(v: View) {
        v.animate().translationX(0f).alpha(1f).setDuration(SNAP_MS).start()
    }

    /**
     * Rubber band for a row that refuses to go. Asymptotic, so the card always
     * moves a little under the finger and never reaches the commit distance.
     */
    private fun resist(dx: Float, width: Int): Float {
        val limit = (width * RUBBER).coerceAtLeast(1f)
        return dx.sign * limit * (1f - exp(-abs(dx) / limit))
    }

    private fun fade(tx: Float, width: Int): Float {
        val span = (width * FADE).coerceAtLeast(1f)
        return (1f - abs(tx) / span).coerceIn(MIN_ALPHA, 1f)
    }

    companion object {
        /** Fraction of the card width the finger must cross to commit. */
        const val THRESHOLD = 0.30f

        /** Fade is gentler than the travel, so the card is still legible. */
        const val FADE = 1.25f

        /** How far a card that cannot be dismissed will stretch. */
        const val RUBBER = 0.12f

        const val MIN_ALPHA = 0.25f
        const val SNAP_MS = 160L
        const val MIN_EXIT_MS = 90L
        const val MAX_EXIT_MS = 260L
        const val COLLAPSE_MS = 170L

        /**
         * Close the gap a dismissed card leaves behind.
         *
         * Without this the rows below jump up a whole card height the instant
         * the listener reports the removal, which is the single most obvious
         * piece of jank in the old holder. Animating the wrapper rather than
         * the card itself keeps the card's margins collapsing with it.
         */
        fun collapse(container: View, onEnd: () -> Unit) {
            val from = container.height
            val lp = container.layoutParams
            if (from <= 0 || lp == null) {
                onEnd()
                return
            }
            val anim = ValueAnimator.ofInt(from, 0)
            anim.duration = COLLAPSE_MS
            anim.addUpdateListener {
                lp.height = it.animatedValue as Int
                container.layoutParams = lp
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    container.layoutParams = lp
                    onEnd()
                }
            })
            anim.start()
        }

        /**
         * Put a recycled row back in its resting state. Views are reused across
         * renders now, so a card left half-swiped or mid-collapse would come
         * back wearing the last gesture's translation.
         */
        fun reset(card: View, container: View) {
            card.animate().cancel()
            card.translationX = 0f
            card.alpha = 1f
            val lp = container.layoutParams
            if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                container.layoutParams = lp
            }
        }
    }
}
