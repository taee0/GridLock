package com.tv.coverscreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Runs in the background every 6 hours (even when the app is closed).
 * If a newer release is found on GitHub, it posts a notification.
 * Tapping the notification opens MainActivity which then shows the update banner.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        var result: Result = Result.success()
        val latch = java.util.concurrent.CountDownLatch(1)

        UpdateChecker.check { release, isNewer ->
            if (release != null && isNewer) {
                postNotification(applicationContext, release.version, release.changelog)
            }
            latch.countDown()
        }

        latch.await(15, TimeUnit.SECONDS)
        return result
    }

    private fun postNotification(ctx: Context, version: String, changelog: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel once (no-op if already exists).
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GridLock Updates",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Notifies when a new GridLock version is available" }
        nm.createNotificationChannel(channel)

        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = changelog.lines().take(3).joinToString("\n").ifBlank { "Tap to install." }

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("GridLock v$version available")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val CHANNEL_ID = "gridlock_updates"
        private const val NOTIF_ID  = 9001
        private const val WORK_NAME = "gridlock_update_check"

        /** Call once from Application or MainActivity — idempotent. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,   // don't reset timer if already scheduled
                request,
            )
        }
    }
}
