package com.tv.coverscreen

import android.content.Context

/**
 * The ordered list of packages, most recent first. Built from the foreground app
 * events the service sees, not from UsageStatsManager, because UsageStats lags
 * and cannot tell you what is in front right now.
 */
class Recents(context: Context) {

    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    @Synchronized
    fun touch(pkg: String) {
        val next = ArrayList<String>(LIMIT)
        next.add(pkg)
        next.addAll(read().filter { it != pkg })
        write(next.take(LIMIT))
    }

    @Synchronized
    fun drop(pkg: String) = write(read().filter { it != pkg })

    /** Close all, One UI style: anything kept open stays where it is. */
    @Synchronized
    fun clear() {
        val kept = pinned()
        write(read().filter { it in kept })
    }

    /** Clear from settings. Takes the pins with it. */
    @Synchronized
    fun wipe() {
        prefs.edit().putString(KEY, "").putString(PINS, "").apply()
    }

    @Synchronized
    fun list(): List<String> = read()

    @Synchronized
    fun pinned(): Set<String> = prefs.getString(PINS, "")
        ?.split(',')
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

    @Synchronized
    fun pin(pkg: String, on: Boolean) {
        val next = pinned().toMutableSet()
        if (on) next.add(pkg) else next.remove(pkg)
        prefs.edit().putString(PINS, next.joinToString(",")).apply()
    }

    private fun read(): List<String> = prefs.getString(KEY, "")
        ?.split(',')
        ?.filter { it.isNotBlank() }
        ?: emptyList()

    private fun write(order: List<String>) {
        prefs.edit().putString(KEY, order.joinToString(",")).apply()
    }

    private companion object {
        const val KEY = "order"
        const val PINS = "pinned"
        const val LIMIT = 20
    }
}
