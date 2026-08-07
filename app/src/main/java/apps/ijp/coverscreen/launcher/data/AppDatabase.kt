package apps.ijp.coverscreen.launcher.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * A pinned app.
 *
 * [packageName] holds an AppEntry.key, not a raw package name: the bare package
 * for a personal app and "pkg@serial" for a work one.
 *
 * The column name is still left alone, but the reason has changed. It used to
 * be that renaming it meant an onUpgrade, and onUpgrade dropped the table, so a
 * rename would have wiped every favourite the user had pinned. onUpgrade no
 * longer drops anything, so a rename is now just a migration step that has to
 * be written. It is still not worth writing: personal rows saved before work
 * profile support are already in the right format, so a rename would correct
 * the name and nothing else.
 */
data class FavoriteApp(
    val packageName: String,
    val appName: String,
    val position: Int,
    val addedTimestamp: Long = System.currentTimeMillis()
)

/** favorite_apps, with the same schema and dao surface a Room dao would expose */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        private const val NAME = "csal_db"
        private const val VERSION = 1
        private const val TABLE = "favorite_apps"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: AppDatabase(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "packageName TEXT PRIMARY KEY NOT NULL, " +
                "appName TEXT NOT NULL, " +
                "position INTEGER NOT NULL, " +
                "addedTimestamp INTEGER NOT NULL)"
        )
    }

    /**
     * Non-destructive by design.
     *
     * This used to DROP TABLE and recreate, which means the first schema change
     * of any kind would have silently deleted every favourite the user had
     * pinned. Nothing ever warned about it, because [VERSION] has never moved
     * off 1 and so this method has never actually run. It was a trap waiting
     * for whoever bumped the version first.
     *
     * Version 1 is still the only schema that has shipped, so there is no step
     * to perform. A future change adds its own step below, guarded on the
     * version it upgrades from, and must not drop anything.
     */
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Unconditional and safe: onCreate is CREATE TABLE IF NOT EXISTS.
        onCreate(db)
        // if (old < 2) db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN ...")
    }

    /**
     * Installing an older build must not wipe the table either.
     *
     * The inherited implementation throws SQLiteDowngradeFailedException, which
     * would take the app down on the first launch after a downgrade. Every
     * column an older build knows about is still present, so there is nothing
     * to undo and nothing to lose by carrying on.
     */
    override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) {
        onCreate(db)
    }

    fun favorites(): List<FavoriteApp> {
        val out = ArrayList<FavoriteApp>()
        readableDatabase.query(TABLE, null, null, null, null, null, "position ASC").use { c ->
            while (c.moveToNext()) {
                out.add(FavoriteApp(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)))
            }
        }
        return out
    }

    /** [key] is an AppEntry.key. */
    fun favorite(key: String) = favorites().firstOrNull { it.packageName == key }

    fun isFavorite(key: String) = favorite(key) != null

    fun insert(app: FavoriteApp) {
        val v = ContentValues()
        v.put("packageName", app.packageName)
        v.put("appName", app.appName)
        v.put("position", app.position)
        v.put("addedTimestamp", app.addedTimestamp)
        writableDatabase.insertWithOnConflict(TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun add(key: String, label: String) = insert(FavoriteApp(key, label, count()))

    fun deleteByPackage(key: String) {
        writableDatabase.delete(TABLE, "packageName = ?", arrayOf(key))
    }

    fun toggle(key: String, label: String): Boolean =
        if (isFavorite(key)) {
            deleteByPackage(key)
            false
        } else {
            add(key, label)
            true
        }

    fun reorder(order: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            order.forEachIndexed { i, key ->
                val v = ContentValues()
                v.put("position", i)
                db.update(TABLE, v, "packageName = ?", arrayOf(key))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM " + TABLE, null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
