package com.tv.coverscreen

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GITHUB_API =
        "https://api.github.com/repos/taee0/GridLock/releases/latest"

    data class Release(
        val version: String,        // e.g. "0.12"
        val tag: String,            // e.g. "v0.12"
        val changelog: String,      // release body / notes
        val downloadUrl: String,    // direct APK asset URL
    )

    /**
     * Checks GitHub for the latest release and calls [onResult] on the calling
     * thread with (latestRelease, isNewer).  Runs the network request on a
     * background thread and posts the callback to the supplied [onResult].
     * Never throws — errors surface as null.
     */
    fun check(onResult: (release: Release?, isNewer: Boolean) -> Unit) {
        Thread {
            val result = runCatching {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.setRequestProperty("User-Agent", "GridLock-App")

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.getString("tag_name")          // "v0.12"
                val version = tag.trimStart('v')               // "0.12"
                val changelog = json.optString("body", "").trim()
                val assets = json.getJSONArray("assets")
                val downloadUrl = if (assets.length() > 0) {
                    assets.getJSONObject(0).getString("browser_download_url")
                } else ""

                val current = BuildConfig.VERSION_NAME        // "0.11"
                val isNewer = compareVersions(version, current) > 0

                Release(version, tag, changelog, downloadUrl) to isNewer
            }.onFailure { Log.w(TAG, "check: $it") }.getOrNull()

            onResult(result?.first, result?.second ?: false)
        }.start()
    }

    /** Simple numeric version comparator ("0.12" > "0.11" > "0.9"). */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val diff = (pa.getOrElse(i) { 0 }) - (pb.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
