package com.tv.coverscreen

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val APK_NAME = "gridlock-update.apk"

    /**
     * Downloads [url] on a background thread, reporting progress via callbacks,
     * then launches the system package installer (or Shizuku silent install).
     *
     * All callbacks are delivered on the background thread — callers must
     * dispatch to the main thread themselves (e.g. runOnUiThread).
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (downloading: Boolean) -> Unit,
        onComplete: (success: Boolean) -> Unit,
    ) {
        Thread {
            val dest = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                APK_NAME,
            )
            if (dest.exists()) dest.delete()

            // ── Download ──────────────────────────────────────────────────────
            val ok = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout    = 30_000
                conn.connect()
                check(conn.responseCode == HttpURLConnection.HTTP_OK) {
                    "HTTP ${conn.responseCode}"
                }
                conn.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }.onFailure {
                Log.e(TAG, "download failed: $it")
            }.getOrDefault(false)

            if (!ok) { onProgress(false); onComplete(false); return@Thread }

            onProgress(false)

            // ── Install ───────────────────────────────────────────────────────
            // Try silent Shizuku install first.
            if (Privileged.ready()) {
                val tmpPath = "/data/local/tmp/gridlock-update.apk"
                val result = runCatching {
                    val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java,
                    )
                    m.isAccessible = true
                    
                    // Copy to /data/local/tmp so pm has read access
                    val cpCmd = arrayOf("sh", "-c", "cp '${dest.absolutePath}' '$tmpPath' && pm install -r '$tmpPath' && rm '$tmpPath'")
                    val proc = m.invoke(null, cpCmd, null, null) as Process
                    val out = proc.inputStream.bufferedReader().readText()
                    val code = proc.waitFor()
                    Log.i(TAG, "pm install exit=$code out=$out")
                    code == 0
                }.onFailure { Log.w(TAG, "shizuku install: $it") }.getOrDefault(false)
                onComplete(result)
                return@Thread
            }

            // Fallback: system installer dialog.
            runCatching {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    dest,
                )
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                onComplete(true)
            }.onFailure {
                Log.e(TAG, "system install: $it")
                onComplete(false)
            }
        }.start()
    }
}
