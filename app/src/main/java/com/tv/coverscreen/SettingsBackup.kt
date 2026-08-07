package com.tv.coverscreen

import android.content.Context
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/**
 * On-device backup/restore for the Samsung MultiStar cover-widget settings:
 * the Settings.Secure keys that decide which apps the cover launcher shows
 * and how it shows them. These are OS-level state, not anything this app
 * writes, and they can end up null or corrupted on their own -- that is what
 * a "restart the cover launcher" recovery is really restoring.
 *
 * This mirrors the developer's own coverscreen_backup.py / restore script
 * one for one: same keys, same rule (never let a null/corrupted read
 * overwrite a good backup), same restore-then-restart-aodservice sequence.
 * The only difference is this runs on the phone through Shizuku's shell
 * process instead of needing a PC and an adb cable.
 */
object SettingsBackup {

    private const val TAG = "SettingsBackup"

    private val KEYS = listOf(
        "all_widgets_list",
        "enable_widgets_list",
        "enable_widgets_id_list",
        "module_info_list",
        "multistar_setting_json_repository",
        "multistar_cover_widget_backup_list",
        "multistar_setting_repository",
        "multistar_all_setting_repository",
    )

    private const val AOD_SERVICE = "com.samsung.android.app.aodservice"

    enum class Outcome { OK, SKIPPED_CORRUPT, NO_SHIZUKU, NO_BACKUP_FOUND, FAILED }

    private fun file(context: Context, gold: Boolean): File =
        File(context.filesDir, if (gold) "coverscreen_gold_backup.json" else "coverscreen_backup.json")

    /** True once at least one backup (clean or gold) exists on disk. */
    fun hasBackup(context: Context): Boolean =
        file(context, false).exists() || file(context, true).exists()

    /** Run a command as shell through Shizuku. (-1, "") when Shizuku is not ready. */
    private fun shell(cmd: String): Pair<Int, String> {
        if (!Privileged.ready()) return -1 to ""
        return runCatching {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val process = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            code to out
        }.onFailure { Log.w(TAG, "shell(" + cmd + "): " + it) }.getOrDefault(-1 to "")
    }

    private fun read(key: String): String? {
        val (code, out) = shell("settings get secure " + key)
        if (code != 0) return null
        return out.trim()
    }

    /**
     * Snapshot the current settings to disk. Refuses to overwrite a clean
     * backup with a null/corrupted read unless [gold] is set -- [gold] is
     * only meant to be used once, right after the developer has manually
     * confirmed the cover screen is in a known-good state.
     */
    fun backup(context: Context, gold: Boolean = false): Outcome {
        if (!Privileged.ready()) return Outcome.NO_SHIZUKU

        val data = LinkedHashMap<String, String?>()
        for (key in KEYS) data[key] = read(key)

        val moduleInfo = data["module_info_list"]
        val widgetsList = data["enable_widgets_list"]
        val corrupt = moduleInfo.isNullOrEmpty() || moduleInfo == "null" ||
            widgetsList.isNullOrEmpty() || widgetsList == "null"
        if (!gold && corrupt) {
            Log.w(TAG, "backup: cover screen settings look empty/corrupted, refusing to overwrite last good backup")
            return Outcome.SKIPPED_CORRUPT
        }

        val json = JSONObject()
        for ((k, v) in data) json.put(k, v ?: "null")
        val ok = runCatching { file(context, gold).writeText(json.toString(2)) }
            .onFailure { Log.w(TAG, "backup: write failed: " + it) }
            .isSuccess
        return if (ok) Outcome.OK else Outcome.FAILED
    }

    /**
     * Write every saved key back with `settings put secure`, skipping any
     * that were null at backup time, then restart aodservice so the cover
     * launcher picks the restored state up. Falls back to the gold backup
     * when there is no clean one, and vice versa.
     */
    fun restore(context: Context, useGold: Boolean = false): Outcome {
        if (!Privileged.ready()) return Outcome.NO_SHIZUKU

        var target = file(context, useGold)
        if (!target.exists()) target = file(context, !useGold)
        if (!target.exists()) return Outcome.NO_BACKUP_FOUND

        val json = runCatching { JSONObject(target.readText()) }.getOrNull()
            ?: return Outcome.FAILED

        var ok = true
        for (key in KEYS) {
            val value = json.optString(key, "null")
            if (value.isEmpty() || value == "null") continue
            val escaped = value.replace("'", "'\\''")
            val (code, _) = shell("settings put secure " + key + " '" + escaped + "'")
            if (code != 0) ok = false
        }
        shell("am force-stop " + AOD_SERVICE)
        return if (ok) Outcome.OK else Outcome.FAILED
    }
}
