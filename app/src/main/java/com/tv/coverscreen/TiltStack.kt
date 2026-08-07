package com.tv.coverscreen

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.sign

/**
 * One UI 7 / 8 "Tilt Stack".
 *
 * Samsung lays the deck out as a flat row and then throws every card that is
 * not the one under your eye away from you: pulled in towards the centre so it
 * overlaps its neighbour, turned about its inner edge so the far edge recedes,
 * shrunk, dimmed and pushed behind. Only the centred card is square on and
 * fully lit, and only the centred card keeps its name.
 *
 * None of that touches layout. The [RecyclerView] still measures and positions
 * one card per full [stride], which is what keeps [androidx.recyclerview.widget.PagerSnapHelper]
 * honest and what keeps the scroll maths linear; the stack is a per-child
 * transform applied on every scrolled frame. Overlap is a translation, not a
 * negative margin, so nothing has to be re-measured when the panel turns.
 *
 * Hit testing survives this. [android.view.ViewGroup] runs the inverse of each
 * child's matrix before it dispatches a touch, so a tap lands on the card you
 * can actually see, tilt and all.
 */
class TiltStack(
    private val list: RecyclerView,
    private var stride: Int,
    private val lift: Float,
) {

    fun stride(next: Int) {
        stride = next
    }

    /**
     * One frame of the stack. Cheap on purpose: no allocation, no findViewById
     * beyond the two ids that have to fade, and every child is touched exactly
     * once.
     */
    fun apply(skip: View? = null) {
        val children = list.childCount
        if (children == 0 || stride <= 0) return

        val mid = (list.paddingLeft + (list.width - list.paddingRight)) / 2f
        if (mid <= 0f) return

        for (i in 0 until children) {
            val v = list.getChildAt(i) ?: continue

            // Being thrown away by ItemTouchHelper. Its transform wins.
            if (v === skip) continue

            // Distance from the centre in whole cards, signed. Taken from the
            // laid out position, never from the transformed one, or the stack
            // would feed on itself frame after frame.
            val here = (v.left + v.right) / 2f
            val t = ((here - mid) / stride).coerceIn(-DEPTH, DEPTH)
            val far = abs(t)
            val side = sign(t)

            // A short camera makes the far cards look like they are falling
            // over. Sit well back so the turn reads as depth, not distortion.
            v.cameraDistance = CAMERA * v.height

            // Turn about the edge that faces the centre, so the deck fans out
            // of the middle card rather than each card spinning in place.
            v.pivotX = if (t < 0f) v.width.toFloat() else 0f
            v.pivotY = v.height * PIVOT_Y
            v.rotationY = TILT * side * minOf(far, 1f)

            // Pull in towards the centre. The layout gap is a full card, so
            // anything above zero here is visible overlap.
            v.translationX = -t * stride * COMPRESS

            val k = (1f - SHRINK * far).coerceAtLeast(MIN_SCALE)
            v.scaleX = k
            v.scaleY = k

            // Behind, so the centre card occludes its neighbours and not the
            // other way round. ViewGroup sorts by Z before it draws.
            v.translationZ = -far * lift
            v.alpha = (1f - FADE * far).coerceAtLeast(0f)

            // Samsung darkens the thumbnail itself rather than fading the whole
            // card out, so the shape of the stack stays readable against a
            // bright wallpaper.
            v.findViewById<View>(R.id.veil)?.alpha = (VEIL * far).coerceAtMost(VEIL_MAX)

            // Name and icon belong to the card you are on. They are gone by
            // the time a card is a full step out.
            val text = (1f - far * TEXT_FALLOFF).coerceIn(0f, 1f)
            v.findViewById<View>(R.id.chip)?.alpha = text
            v.findViewById<View>(R.id.label)?.alpha = text
        }
    }

    /** Undo everything, for a holder about to be handed back to the pool. */
    fun clear(v: View) {
        v.rotationY = 0f
        v.translationX = 0f
        v.translationY = 0f
        v.translationZ = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.alpha = 1f
        v.findViewById<View>(R.id.veil)?.alpha = 0f
        v.findViewById<View>(R.id.chip)?.alpha = 1f
        v.findViewById<View>(R.id.label)?.alpha = 1f
    }

    private companion object {
        /** How many cards out the stack keeps stacking before it gives up. */
        const val DEPTH = 3f

        /** Degrees of turn on a card one full step out. */
        const val TILT = 26f

        /** Share of the layout stride each step is pulled back in by. */
        const val COMPRESS = 0.44f

        const val SHRINK = 0.115f
        const val MIN_SCALE = 0.62f
        const val FADE = 0.10f
        const val VEIL = 0.34f
        const val VEIL_MAX = 0.72f
        const val TEXT_FALLOFF = 1.5f

        /** Turn a little below the middle. Reads as the card leaning back. */
        const val PIVOT_Y = 0.56f

        const val CAMERA = 12f
    }
}