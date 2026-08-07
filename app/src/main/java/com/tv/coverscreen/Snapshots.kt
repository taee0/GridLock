package com.tv.coverscreen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

/**
 * The app screens shown on the cards. Captured by the accessibility service
 * while an app is in front, kept in memory and on disk so the switcher has
 * something to show straight after a reboot.
 *
 * Nothing here touches the disk on the main thread any more. [peek] is the only
 * call the gesture path is allowed to make, and it is a memory lookup. Decoding
 * happens in [warm] on the io thread, well before anyone pulls.
 */
class Snapshots(private val dir: File, private val io: Executor) {

    /** Sized in bytes, not entries. A dozen full panels is not a fixed cost. */
    private val mem = object : LruCache<String, Bitmap>(BUDGET) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    init {
        dir.mkdirs()
    }

    fun put(pkg: String, full: Bitmap) {
        val small = shrink(full)
        if (small !== full) full.recycle()
        mem.put(pkg, small)
        io.execute {
            runCatching {
                FileOutputStream(File(dir, name(pkg))).use {
                    small.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, it)
                }
            }
        }
    }

    /** Memory only. Safe on the main thread, safe inside a gesture. */
    fun peek(pkg: String): Bitmap? = mem.get(pkg)

    /** Memory, then disk. Never call this on the main thread. */
    fun get(pkg: String): Bitmap? {
        mem.get(pkg)?.let { return it }
        val f = File(dir, name(pkg))
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val b = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        val small = shrink(b)
        if (small !== b) b.recycle()
        mem.put(pkg, small)
        return small
    }

    /**
     * Pull everything these packages need off the disk in the background, then
     * say so once. The switcher rebinds on the callback, so the first pull of
     * the day is already holding its pictures.
     */
    fun warm(pkgs: List<String>, done: () -> Unit) {
        val cold = pkgs.filter { mem.get(it) == null }
        if (cold.isEmpty()) return
        io.execute {
            var got = false
            cold.forEach { if (get(it) != null) got = true }
            if (got) done()
        }
    }

    fun drop(pkg: String) {
        mem.remove(pkg)
        io.execute { runCatching { File(dir, name(pkg)).delete() } }
    }

    private fun name(pkg: String) = pkg.replace('.', '_') + ".webp"

    /**
     * The thumbnail is 176dp by 216dp, so about 352 by 432 real pixels on this
     * panel. Anything past [TARGET] on the long edge is memory and upload
     * bandwidth spent on pixels that get scaled away during a drag.
     */
    private fun shrink(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= TARGET) return b
        val f = TARGET.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private companion object {
        const val TARGET = 480
        const val BUDGET = 8 * 1024 * 1024
    }
}
