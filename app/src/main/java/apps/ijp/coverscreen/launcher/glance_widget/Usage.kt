package apps.ijp.coverscreen.launcher.glance_widget

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.tv.coverscreen.Privileged

/**
 * Launch recency and counts. Backs the Recent tab and the recent and most used
 * sort modes.
 *
 * This used to be nothing but our own SharedPreferences, written by [note] from
 * the two places in this app that launch something. That means it could only
 * ever list apps you had opened from this launcher, which is why the Recent tab
 * never matched what you had actually been using. There are three sources now,
 * best first:
 *
 *  1. [Privileged.tasks] — the system recent task list, in the system order.
 *     This is the same list the real app switcher draws and it is live, so an
 *     app you opened from a notification thirty seconds ago is at the top.
 *     Needs Shizuku.
 *  2. UsageStatsManager — every foreground app on the device for the last week,
 *     from the same data Digital Wellbeing draws Screen time from. Needs usage
 *     access, which Shizuku can also grant, see [Privileged.grantUsage]. Note
 *     the framework returns nothing while the device is locked, hence the
 *     fallthrough below rather than an empty list.
 *  3. our own prefs, unchanged, for a phone with neither.
 *
 * Nothing here throws. Every tier degrades to the next one.
 */
object Usage {

    private const val PREFS = "launcher_usage"

    /** How far back to ask the system. A week is plenty for a recents list. */
    private const val WINDOW = 7L * 24 * 60 * 60 * 1000

    /**
     * queryEvents walks every event in the window, so it is not something to
     * run per bind or per comparison. Sorting by recency alone would call it
     * once per compare.
     */
    private const val TTL = 20_000L

    @Volatile private var cachedAt = 0L
    @Volatile private var cached: Map<String, Long> = emptyMap()

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Still recorded, so tier 3 keeps working and tier 1 has a tiebreak. */
    fun note(c: Context, pkg: String) {
        p(c).edit()
            .putLong("t_" + pkg, System.currentTimeMillis())
            .putInt("c_" + pkg, count(c, pkg) + 1)
            .apply()
    }

    fun count(c: Context, pkg: String) = p(c).getInt("c_" + pkg, 0)

    /** The later of what we saw and what the system saw. */
    fun lastUsed(c: Context, pkg: String): Long {
        val mine = p(c).getLong("t_" + pkg, 0L)
        val theirs = system(c)[pkg] ?: 0L
        return if (theirs > mine) theirs else mine
    }

    /** True when the system will actually answer usage queries. */
    fun granted(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            c.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Which source answered, for the settings screen to say so out loud. */
    fun source(c: Context): String = when {
        Privileged.ready() -> "tasks"
        granted(c) -> "usage"
        else -> "local"
    }

    fun recent(c: Context, limit: Int): List<String> {
        val live = tasks(c)
        if (live != null && live.isNotEmpty()) return live.take(limit)

        val seen = system(c)
        if (seen.isNotEmpty()) {
            return seen.entries
                .sortedByDescending { it.value }
                .map { it.key }
                .filter { it != c.packageName }
                .take(limit)
        }

        return p(c).all.keys
            .filter { it.startsWith("t_") }
            .map { it.substring(2) }
            .sortedByDescending { p(c).getLong("t_" + it, 0L) }
            .take(limit)
    }

    /**
     * The system recents order, deduplicated. One app can hold several tasks
     * and we only want its most recent appearance.
     */
    private fun tasks(c: Context): List<String>? {
        val tasks = Privileged.tasks() ?: return null
        val out = ArrayList<String>(tasks.size)
        for (t in tasks) {
            if (t.pkg == c.packageName) continue
            if (!out.contains(t.pkg)) out.add(t.pkg)
        }
        return out
    }

    /** Package to the last time it came to the foreground. Cached for [TTL]. */
    private fun system(c: Context): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < TTL) return cached
        cachedAt = now
        if (!granted(c)) {
            cached = emptyMap()
            return cached
        }
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm == null) {
            cached = emptyMap()
            return cached
        }
        val out = HashMap<String, Long>()
        runCatching {
            val events = usm.queryEvents(now - WINDOW, now)
            val e = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName
                // RESUMED is the app arriving in front. MOVE_TO_FOREGROUND is
                // the same event under its old name.
                if (pkg != null && e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val at = e.timeStamp
                    if (at > (out[pkg] ?: 0L)) out[pkg] = at
                }
            }
        }
        cached = out
        return out
    }
}
