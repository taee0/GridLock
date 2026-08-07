package apps.ijp.coverscreen.launcher.data

import android.content.Context
import android.graphics.Bitmap
import apps.ijp.coverscreen.launcher.glance_widget.Usage
import com.tv.coverscreen.AppUtils
import com.tv.coverscreen.IconCache

/**
 * One launchable app, in one profile.
 *
 * [packageName] is not an identity. An app installed in both the personal and
 * the work profile produces two AppEntry values with the same packageName and
 * different [userSerial]. Use [key] for anything that has to tell them apart:
 * favourites, hiding, custom order, icon cache lookups, diffing. This used to
 * be packageName everywhere, which is why the two copies behaved as one.
 */
data class AppEntry(
    val packageName: String,
    val activity: String,
    val name: String,
    val favorite: Boolean,
    val userSerial: Long,
    /** bare package for personal, "pkg@serial" for work */
    val key: String,
    val work: Boolean,
    val quiet: Boolean
)

/** single source of truth for the app list, sorting, search and hiding */
class AppsRepository(private val context: Context) {

    private val settings = Settings(context)
    private val db = AppDatabase.get(context)

    /** Drops the shared list. See [invalidateAll]. */
    fun invalidate() = invalidateAll()

    fun all(): List<AppEntry> {
        cached?.let { return it }
        val favs = db.favorites().map { it.packageName }.toSet()
        // isQuietModeEnabled and the work profile check are both binder calls,
        // so ask once per profile rather than once per app.
        val quietBySerial = HashMap<Long, Boolean>()
        val workBySerial = HashMap<Long, Boolean>()
        val list = AppUtils.launchable(context).map {
            val key = AppUtils.keyFor(context, it.pkg, it.userSerial)
            AppEntry(
                packageName = it.pkg,
                activity = it.activity,
                name = it.label,
                favorite = favs.contains(key),
                userSerial = it.userSerial,
                key = key,
                work = workBySerial.getOrPut(it.userSerial) {
                    AppUtils.isWork(context, it.userSerial)
                },
                quiet = quietBySerial.getOrPut(it.userSerial) {
                    AppUtils.quiet(context, it.userSerial)
                }
            )
        }
        cached = list
        return list
    }

    fun visible(): List<AppEntry> = sorted(unsorted())

    /**
     * Visible apps in whatever order [all] produced, with no sort applied.
     *
     * [search] ranks its own results and throws away any order it was handed,
     * so routing it through [visible] made every keystroke pay for a sort that
     * was discarded on the next line. Under SORT_RECENT or SORT_FREQUENT that
     * sort reads a preference for every installed app, so the waste was real
     * work and not just a wasted comparison.
     */
    fun unsorted(): List<AppEntry> {
        val hidden = settings.hidden
        return all().filter { !hidden.contains(it.key) }
    }

    fun sorted(list: List<AppEntry>): List<AppEntry> = when (settings.sort) {
        Settings.SORT_RECENT -> list.sortedByDescending { Usage.lastUsed(context, it.packageName) }
        Settings.SORT_FREQUENT -> list.sortedByDescending { Usage.count(context, it.packageName) }
        Settings.SORT_CUSTOM -> {
            val order = settings.customOrder
            list.sortedBy {
                val i = order.indexOf(it.key)
                if (i < 0) Int.MAX_VALUE else i
            }
        }
        else -> list.sortedBy { it.name.lowercase() }
    }

    /**
     * Real time search with fuzzy matching, app name and package name, any
     * installed language. Results are ranked so an exact prefix beats a word
     * start, which beats a substring, which beats initials, which beats a
     * loose subsequence.
     */
    fun search(query: String): List<AppEntry> {
        if (query.isBlank()) return visible()
        val q = query.trim().lowercase()
        // unsorted(), not visible(): the ranking below replaces whatever order
        // a sort would have produced, and the recent and frequent sorts cost a
        // preference read per app to produce it.
        return unsorted()
            .map { it to score(it, q) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }
                .thenBy { it.first.name.lowercase() })
            .map { it.first }
    }

    /**
     * Tier first, then the shorter name wins inside that tier.
     *
     * The tiers were written as 900 - name.length, 800 - name.length and so
     * on, which lets them cross. A 150 character name matching on prefix
     * scored 750 and lost to a mere substring match on a short name, which
     * inverts the ranking [search] documents. Three of the seven tiers also
     * had no length term at all, so those ties fell through to the
     * alphabetical comparator rather than preferring the shorter name.
     *
     * Separating the two makes crossing impossible: the length term is capped
     * one below [TIER], so it can never reach into the tier underneath.
     */
    private fun score(entry: AppEntry, q: String): Int {
        val name = entry.name.lowercase()
        val pkg = entry.packageName.lowercase()
        val tier = when {
            name == q -> 7
            name.startsWith(q) -> 6
            wordStart(name, q) -> 5
            name.contains(q) -> 4
            initials(entry.name).startsWith(q) -> 3
            pkg.contains(q) -> 2
            subsequence(name, q) -> 1
            else -> 0
        }
        if (tier == 0) return 0
        return (tier * TIER) - name.length.coerceAtMost(TIER - 1)
    }

    private fun wordStart(name: String, q: String): Boolean =
        name.split(' ', '-', '_', '.').any { it.startsWith(q) }

    private fun initials(name: String): String =
        name.split(' ', '-', '_', '.')
            .mapNotNull { it.firstOrNull() }
            .joinToString("")
            .lowercase()

    /** every query char appears in order, which is what catches typos and gaps */
    private fun subsequence(name: String, q: String): Boolean {
        var i = 0
        for (c in name) {
            if (i < q.length && c == q[i]) i++
            if (i == q.length) return true
        }
        return i == q.length
    }

    fun startingWith(letter: Char): List<AppEntry> =
        visible().filter { it.name.firstOrNull()?.uppercaseChar() == letter.uppercaseChar() }

    fun letters(): List<Char> =
        visible().mapNotNull { it.name.firstOrNull()?.uppercaseChar() }.distinct().sorted()

    fun favorites(): List<AppEntry> {
        val order = db.favorites().map { it.packageName }
        val byKey = all().associateBy { it.key }
        return order.mapNotNull { byKey[it] }.take(settings.favoriteMax)
    }

    /**
     * UsageStats and our own prefs both record a package name with no profile,
     * so a recent entry cannot say which copy ran. Prefer the personal one and
     * fall back to whatever profile has it.
     */
    fun recents(): List<AppEntry> {
        val everything = all()
        val byPkg = HashMap<String, AppEntry>()
        for (e in everything) {
            val existing = byPkg[e.packageName]
            if (existing == null || (existing.work && !e.work)) byPkg[e.packageName] = e
        }
        return Usage.recent(context, settings.recentCount).mapNotNull { byPkg[it] }
    }

    fun hiddenApps(): List<AppEntry> {
        val hidden = settings.hidden
        return all().filter { hidden.contains(it.key) }.sortedBy { it.name.lowercase() }
    }

    fun forView(): List<AppEntry> = when (settings.view) {
        Settings.VIEW_FAVORITES -> favorites()
        Settings.VIEW_RECENT -> recents()
        else -> visible()
    }

    /** [key] is an AppEntry.key, not a package name. */
    fun icon(key: String, sizePx: Int): Bitmap? = IconCache.get(context, key, sizePx)

    fun toggleFavorite(entry: AppEntry) = toggleFavorite(entry.key, entry.name)

    /**
     * Toggle by key and label rather than by [AppEntry].
     *
     * Widget rows already carry both, so this spares the caller a full
     * enumeration of every launchable app in every profile purely to recover a
     * label it was already displaying.
     */
    fun toggleFavorite(key: String, label: String): Boolean {
        val added = db.toggle(key, label)
        // favourite state is baked into AppEntry, so the shared list is stale
        invalidateAll()
        return added
    }

    companion object {
        /**
         * Width of one ranking tier in [score]. The length tiebreak is capped
         * just below this, so a long name can never fall out of its own tier
         * into the one beneath it.
         */
        private const val TIER = 1000

        /**
         * The app list, shared by every AppsRepository in the process.
         *
         * This was an instance field while every caller built its own
         * repository and invalidated it at the top of each load, so it could
         * never serve a hit and each widget refresh re-enumerated the whole
         * device. It is now dropped only by the things that genuinely change
         * the list: a package added or removed, and favourite changes.
         */
        @Volatile
        private var cached: List<AppEntry>? = null

        fun invalidateAll() {
            cached = null
        }
    }
}
