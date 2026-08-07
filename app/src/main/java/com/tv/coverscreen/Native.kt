package com.tv.coverscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import apps.ijp.coverrecents.NativeRecentsFactory

/**
 * Everything the app reads out of libspark.so, with the values baked into
 * the library as the fallback.
 *
 * The fallbacks are not guesses. Every getter in that library is three
 * instructions, adrp x8, ldr from .data, ret, so the constants below are the
 * exact bytes the native call would have returned on the device the library
 * shipped on. If the library binds we use it, because remote config can move
 * these at runtime through nS1. If it does not bind, nothing changes
 * behaviourally.
 */
object Native {

    val available: Boolean get() = NativeRecentsFactory.loaded
    val error: String? get() = NativeRecentsFactory.loadError

    // ------------------------------------------------------- window params

    /** 2032, TYPE_ACCESSIBILITY_OVERLAY. */
    val windowType: Int get() = int({ NativeRecentsFactory.gL1() }, L1)

    /**
     * 0x40728. NOT_FOCUSABLE | NOT_TOUCH_MODAL | LAYOUT_IN_SCREEN |
     * LAYOUT_NO_LIMITS | FULLSCREEN | WATCH_OUTSIDE_TOUCH.
     */
    val windowFlags: Int get() = int({ NativeRecentsFactory.gL2() }, L2)

    /** -3, PixelFormat.TRANSLUCENT. */
    val pixelFormat: Int get() = int({ NativeRecentsFactory.gL3() }, L3)

    /** 51, Gravity.TOP or Gravity.LEFT. */
    val gravity: Int get() = int({ NativeRecentsFactory.gL4() }, L4)

    /** 80. */
    val l5: Int get() = int({ NativeRecentsFactory.gL5() }, L5)

    /** 748, the cover panel window height handed to the overlay. */
    val coverHeight: Int get() = int({ NativeRecentsFactory.gL6() }, L6)

    /** -100, added to height/2 for the y position when on the cover. */
    val yNudge: Int get() = int({ NativeRecentsFactory.gL7() }, L7)

    /** 1048, used in place of gL6 when not on the cover panel. */
    val mainHeight: Int get() = int({ NativeRecentsFactory.gD3() }, D3)

    // ------------------------------------------------------------- timings

    /** 80px. Travel before the pull is treated as a pull at all. */
    val slopPx: Float get() = float({ NativeRecentsFactory.gT1() }, T1)

    /** 200ms. */
    val shortMs: Long get() = float({ NativeRecentsFactory.gT2() }, T2).toLong()

    /** 120px. Travel past which letting go finishes instead of cancelling. */
    val commitPx: Float get() = float({ NativeRecentsFactory.gT3() }, T3)

    /** 800ms. The long dwell. Used when strict hold is on. */
    val longHoldMs: Long get() = float({ NativeRecentsFactory.gT4() }, T4).toLong()

    /** 100px. */
    val t5: Float get() = float({ NativeRecentsFactory.gT5() }, T5)

    /** 200ms. */
    val settleMs: Long get() = float({ NativeRecentsFactory.gT6() }, T6).toLong()

    /**
     * 500ms. The dwell for swipe and hold. gT4 and gT7 are both plausible
     * sources for this timeout and there is no way to tell which one the
     * library intends, so gT7 is the default and gT4 sits behind the strict
     * toggle.
     */
    val holdMs: Long get() = float({ NativeRecentsFactory.gT7() }, T7).toLong()

    /** 50px. Sideways travel that cancels the hold. */
    val cancelPx: Float get() = float({ NativeRecentsFactory.gT8() }, T8)

    // -------------------------------------------------------------- strings

    /** Launcher package the service compares the foreground app against. */
    fun launcher(context: Context): String =
        str({ NativeRecentsFactory.gP1() }) ?: Cover.home(context) ?: SAMSUNG_LAUNCHER

    fun p2(): String? = str { NativeRecentsFactory.gP2() }
    fun p3(): String? = str { NativeRecentsFactory.gP3() }
    fun p4(): String? = str { NativeRecentsFactory.gP4() }
    fun p5(): String? = str { NativeRecentsFactory.gP5() }

    /** viewIdResourceName the service hunts for to know the gesture happened. */
    fun triggerIds(): List<String> = listOfNotNull(
        str { NativeRecentsFactory.gR1() },
        str { NativeRecentsFactory.gR2() },
        str { NativeRecentsFactory.gR3() },
        str { NativeRecentsFactory.gR4() },
        str { NativeRecentsFactory.gR5() },
        str { NativeRecentsFactory.gR6() },
    ).filter { it.isNotBlank() }

    // ------------------------------------------------------------- displays

    /**
     * Cover display id. gD1 lives in .bss so it reads 0 until remote config
     * calls nS1, and 0 is the inner panel, which would be wrong. Only trust it
     * when it is not 0 and the system actually reports a display with that id.
     */
    fun coverDisplay(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        if (available) {
            val id = runCatching { NativeRecentsFactory.gD1() }.getOrDefault(0)
            if (id != 0) {
                val viaNative = runCatching {
                    NativeRecentsFactory.gDM1(dm, id) as? Display
                }.getOrNull()
                if (viaNative != null && viaNative.isValid) return viaNative
                dm.getDisplay(id)?.let { if (it.isValid) return it }
            }
        }
        return Cover.panel(context)
    }

    fun mainDisplayId(): Int = int({ NativeRecentsFactory.gD2() }, D2)

    // ------------------------------------------------------------- plumbing

    private inline fun int(get: () -> Int, fallback: Int): Int =
        if (!available) fallback else runCatching(get).getOrDefault(fallback)

    private inline fun float(get: () -> Float, fallback: Float): Float =
        if (!available) fallback else runCatching(get).getOrDefault(fallback)

    private inline fun str(get: () -> String): String? =
        if (!available) null else runCatching(get).getOrNull()

    /** What the config screen shows so you can see whether the lib bound. */
    fun dump(): String = buildString {
        append(if (available) "libspark bound" else "libspark not bound (${error})")
        append("\n")
        append("type $windowType  flags 0x${Integer.toHexString(windowFlags)}  ")
        append("format $pixelFormat  gravity $gravity\n")
        append("gL5 $l5  gL6 $coverHeight  gL7 $yNudge  gD3 $mainHeight\n")
        append("slop $slopPx  commit $commitPx  hold ${holdMs}ms  long ${longHoldMs}ms  ")
        append("cancel $cancelPx\n")
        val ids = triggerIds()
        append(if (ids.isEmpty()) "no resource ids" else ids.joinToString("\n"))
    }

    private const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"

    // Decoded from .data in libspark.so. 0x52b04..0x52b1c and 0x52b20..0x52b3c.
    private const val L1 = 2032
    private const val L2 = 0x40728
    private const val L3 = -3
    private const val L4 = 51
    private const val L5 = 80
    private const val L6 = 748
    private const val L7 = -100
    private const val D2 = 1
    private const val D3 = 1048

    private const val T1 = 80f
    private const val T2 = 200f
    private const val T3 = 120f
    private const val T4 = 800f
    private const val T5 = 100f
    private const val T6 = 200f
    private const val T7 = 500f
    private const val T8 = 50f
}
