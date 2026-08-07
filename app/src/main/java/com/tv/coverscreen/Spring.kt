package com.tv.coverscreen

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A damped spring on one value, ticked off the frame clock.
 *
 * A fixed duration animator finishes in the same time no matter how hard you
 * threw it, and that is the single loudest tell that a gesture is not a system
 * gesture. This takes the velocity the finger actually had at the moment it
 * left the glass and lets the physics decide how long it takes.
 *
 * Semi implicit Euler, substepped, with the frame delta clamped. A dropped
 * frame at this stiffness will blow a naive integrator straight off screen.
 */
class Spring(
    private val stiffness: Float = 1000f,
    private val damping: Float = 0.88f,
    private val onFrame: (Float) -> Unit,
    private val onRest: () -> Unit,
) : Choreographer.FrameCallback {

    private var value = 0f
    private var target = 0f
    private var velocity = 0f
    private var running = false
    private var last = 0L

    val active: Boolean get() = running

    /** [v0] is in value units per second, so pull fractions per second here. */
    fun start(from: Float, to: Float, v0: Float) {
        value = from
        target = to
        velocity = v0
        last = 0L
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) return
        running = false
        velocity = 0f
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(now: Long) {
        if (!running) return

        if (last == 0L) last = now
        var dt = (now - last) / 1_000_000_000f
        last = now
        if (dt <= 0f) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }
        if (dt > FLOOR) dt = FLOOR

        val c = 2f * damping * sqrt(stiffness)
        val h = dt / STEPS
        repeat(STEPS) {
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
        }

        if (abs(value - target) < REST && abs(velocity) < REST_V) {
            value = target
            velocity = 0f
            running = false
            onFrame(value)
            onRest()
            return
        }

        onFrame(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private companion object {
        /** Never integrate more than a 30fps step in one go. */
        const val FLOOR = 1f / 30f
        const val STEPS = 4
        const val REST = 0.001f
        const val REST_V = 0.02f
    }
}
