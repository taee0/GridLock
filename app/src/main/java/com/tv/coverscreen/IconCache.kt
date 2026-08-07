package com.tv.coverscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Bitmap icon cache sized off the app heap, per the integration guide.
 *
 * Keys are AppEntry.key, not package names. A dual installed app has one icon
 * per profile, the work one carrying the system badge, so caching by package
 * alone handed the personal icon to both rows.
 *
 * The cache key separator is '#', because the composite app key already
 * contains '@'.
 */
object IconCache {

    private val cache: LruCache<String, Bitmap> by lazy {
        val max = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(max / 8) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    fun get(context: Context, key: String, size: Int): Bitmap? {
        val k = key + "#" + size
        cache.get(k)?.let { return it }
        val d = AppUtils.icon(context, key) ?: return null
        val bmp = toBitmap(d, size)
        cache.put(k, bmp)
        return bmp
    }

    /**
     * Whatever is already decoded, or null. Costs a hash lookup, so it is safe
     * on the main thread inside a bind; [get] is not, because a miss there is a
     * binder call to PackageManager plus a rasterise.
     */
    fun peek(key: String, size: Int): Bitmap? = cache.get(key + "#" + size)

    fun clear() = cache.evictAll()

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width == size) return d.bitmap
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }
}
