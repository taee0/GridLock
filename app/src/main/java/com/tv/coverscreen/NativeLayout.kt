package com.tv.coverscreen

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import apps.ijp.coverscreen.launcher.ui.NativeLayoutParamsFactory as F

/**
 * Facade over the launcher libspark layout builders.
 * Picks the right variant for the device and rotation, falls back down the
 * chain documented in the integration guide if a getter returns null or throws.
 */
object NativeLayout {

    private const val TAG = "NativeLayoutParams"

    val available: Boolean get() = F.loaded
    val error: String? get() = F.loadError

    private var configLoaded = false

    enum class Device { FLIP, RAZR36, RAZR4 }

    val device: Device by lazy {
        val m = (Build.MODEL + " " + Build.DEVICE + " " + Build.PRODUCT).lowercase()
        when {
            m.contains("razr") && m.contains("40") -> Device.RAZR4
            m.contains("razr") -> Device.RAZR36
            else -> Device.FLIP
        }
    }

    /**
     * The lib exposes nS1 as a config loader. The argument arrays it really
     * wants are not available to us, so we call it best effort and keep going
     * either way.
     */
    fun loadConfig(): Boolean {
        if (!available) return false
        if (configLoaded) return true
        configLoaded = try {
            F.nS1(
                emptyArray(), emptyArray(), emptyArray(), emptyArray(),
                emptyArray(), emptyArray(), emptyArray(), IntArray(0)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "nS1 unavailable: $t")
            false
        }
        return configLoaded
    }

    private inline fun tryGet(name: String, body: () -> WindowManager.LayoutParams?): WindowManager.LayoutParams? =
        try {
            body()
        } catch (t: Throwable) {
            Log.w(TAG, "$name failed: $t")
            null
        }

    /** full screen params for the given rotation, with the fallback chain */
    fun fullScreen(rotation: Int): WindowManager.LayoutParams {
        loadConfig()
        if (available) {
            val chain: List<Pair<String, () -> WindowManager.LayoutParams?>> = when (device) {
                Device.FLIP -> when (rotation) {
                    Surface.ROTATION_180 -> listOf("nF1" to { F.nF1() }, "nF2" to { F.nF2() }, "nN2" to { F.nN2() })
                    Surface.ROTATION_90 -> listOf("nG1" to { F.nG1() }, "nG2" to { F.nG2() }, "nN3" to { F.nN3() })
                    Surface.ROTATION_270 -> listOf("nH1" to { F.nH1() }, "nH2" to { F.nH2() }, "nN4" to { F.nN4() })
                    else -> listOf("nE1" to { F.nE1() }, "nE2" to { F.nE2() }, "nN1" to { F.nN1() })
                }
                Device.RAZR36 -> when (rotation) {
                    Surface.ROTATION_180 -> listOf("nJ1" to { F.nJ1() }, "nN2" to { F.nN2() })
                    Surface.ROTATION_90 -> listOf("nK1" to { F.nK1() }, "nN3" to { F.nN3() })
                    Surface.ROTATION_270 -> listOf("nL1" to { F.nL1() }, "nN4" to { F.nN4() })
                    else -> listOf("nI1" to { F.nI1() }, "nN1" to { F.nN1() })
                }
                Device.RAZR4 -> when (rotation) {
                    Surface.ROTATION_180 -> listOf("nJ2" to { F.nJ2() }, "nN2" to { F.nN2() })
                    Surface.ROTATION_90 -> listOf("nK2" to { F.nK2() }, "nN3" to { F.nN3() })
                    Surface.ROTATION_270 -> listOf("nL2" to { F.nL2() }, "nN4" to { F.nN4() })
                    else -> listOf("nI2" to { F.nI2() }, "nN1" to { F.nN1() })
                }
            }
            for ((name, get) in chain) {
                val lp = tryGet(name, get)
                if (lp != null) {
                    Log.d(TAG, "fullScreen via $name")
                    return lp
                }
            }
        }
        return handBuilt()
    }

    /** flash LED style init params, used before the real window goes up */
    fun initialFlash(rotation: Int): WindowManager.LayoutParams {
        loadConfig()
        if (available) {
            val chain: List<Pair<String, () -> WindowManager.LayoutParams?>> = when (rotation) {
                Surface.ROTATION_180 -> when (device) {
                    Device.FLIP -> listOf("nB1" to { F.nB1() })
                    Device.RAZR36 -> listOf("nB2" to { F.nB2() })
                    Device.RAZR4 -> listOf("nB3" to { F.nB3() })
                }
                Surface.ROTATION_90 -> when (device) {
                    Device.FLIP -> listOf("nC1" to { F.nC1() })
                    Device.RAZR36 -> listOf("nC2" to { F.nC2() })
                    Device.RAZR4 -> listOf("nC3" to { F.nC3() })
                }
                Surface.ROTATION_270 -> when (device) {
                    Device.FLIP -> listOf("nD1" to { F.nD1() })
                    Device.RAZR36 -> listOf("nD2" to { F.nD2() })
                    Device.RAZR4 -> listOf("nD3" to { F.nD3() })
                }
                else -> when (device) {
                    Device.FLIP -> listOf("nA1" to { F.nA1() })
                    Device.RAZR36 -> listOf("nA2" to { F.nA2() })
                    Device.RAZR4 -> listOf("nA3" to { F.nA3() })
                }
            } + listOf("nN5" to { F.nN5() })
            for ((name, get) in chain) {
                val lp = tryGet(name, get)
                if (lp != null) return lp
            }
        }
        return handBuilt()
    }

    fun cornerMode(mode: Int): WindowManager.LayoutParams? {
        loadConfig()
        if (!available) return null
        return tryGet("nM1") { F.nM1(mode) }
    }

    fun background(mode: Int): WindowManager.LayoutParams? {
        loadConfig()
        if (!available) return null
        return tryGet("nM2") { F.nM2(mode) }
    }

    /** last resort, matches what the native builders produce */
    fun handBuilt(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        return lp
    }

    fun dump(context: Context) {
        Log.d(TAG, "loaded=$available err=$error device=$device config=$configLoaded")
        val lp = fullScreen(Surface.ROTATION_0)
        Log.d(TAG, "fullScreen -> type=${lp.type} flags=0x${Integer.toHexString(lp.flags)} " +
            "fmt=${lp.format} gravity=${lp.gravity} w=${lp.width} h=${lp.height} x=${lp.x} y=${lp.y}")
    }
}
