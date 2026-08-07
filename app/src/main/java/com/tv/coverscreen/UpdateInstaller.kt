package com.tv.coverscreen

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val APK_NAME = "gridlock-update.apk"

    /**
     * Downloads [url] via DownloadManager and calls [onComplete] when done.
     * Installs silently via Shizuku if available, otherwise fires the system
     * package-installer dialog.
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (downloading: Boolean) -> Unit,
        onComplete: (success: Boolean) -> Unit,
    ) {
        onProgress(true)

        // Delete any stale copy first.
        val dest = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_NAME,
        )
        if (dest.exists()) dest.delete()

        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("GridLock update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(dest))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                ctx.unregisterReceiver(this)
                onProgress(false)

                val q = DownloadManager.Query().setFilterById(id)
                val cursor = dm.query(q)
                val ok = cursor.use { c ->
                    c.moveToFirst() &&
                        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
                }

                if (!ok) { onComplete(false); return }

                // Try silent Shizuku install first.
                if (Privileged.ready()) {
                    val result = runCatching {
                        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                            "newProcess",
                            Array<String>::class.java,
                            Array<String>::class.java,
                            String::class.java,
                        )
                        m.isAccessible = true
                        val proc = m.invoke(
                            null,
                            arrayOf("pm", "install", "-r", dest.absolutePath),
                            null, null,
                        ) as Process
                        val code = proc.waitFor()
                        Log.i(TAG, "pm install exit=$code")
                        code == 0
                    }.onFailure { Log.w(TAG, "shizuku install: $it") }.getOrDefault(false)
                    onComplete(result)
                    return
                }

                // Fallback: system installer dialog.
                val uri = FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.provider",
                    dest,
                )
                val install = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(install)
                onComplete(true)
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
