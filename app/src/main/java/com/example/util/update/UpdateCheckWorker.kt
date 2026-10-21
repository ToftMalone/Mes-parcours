package com.example.util.update

import com.example.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.BuildConfig
import com.example.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Checks, in the background, whether a new version is published, and warns
 * the user with a notification if so.
 *
 * Complements `UpdatePrompt`, which only consults the network when the
 * app opens: without this task, an update published while
 * the app stays closed for several days was only seen at the next
 * launch — if there was one.
 *
 * **Every [CHECK_INTERVAL_HOURS] hours, never continuously.** Scheduled via
 * WorkManager rather than a home-made timer: the system shifts the callback within a
 * wide window and batches the tasks of several apps to limit
 * processor wake-ups, the opposite of a continuous check that would drain the
 * battery. `setRequiresBatteryNotLow(true)` even gives up that beat when
 * the device is already low.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!UpdateConfig.isConfigured) return Result.success()

        val update = UpdateChecker.fetchLatest() ?: return Result.success()
        if (!update.isNewerThan(BuildConfig.VERSION_CODE)) return Result.success()

        if (UpdateCheckPreferences.wasAlreadyNotified(applicationContext, update.versionCode)) {
            return Result.success()
        }

        notify(update)
        UpdateCheckPreferences.markNotified(applicationContext, update.versionCode)
        return Result.success()
    }

    private fun notify(update: AvailableUpdate) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = applicationContext.getString(R.string.update_channel_description)
            }
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.update_available_title))
            .setContentText(applicationContext.getString(R.string.update_notification_text, update.versionName))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // No POST_NOTIFICATIONS check before this call: without it,
        // notify() silently does nothing on versions that require it —
        // same stance as TrackingService and AutoBackupManager for their own
        // notifications.
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "update_check_channel"
        private const val NOTIFICATION_ID = 8802
        private const val WORK_NAME = "update_check"

        /**
         * Check rate. WorkManager refuses anything under 15 minutes
         * (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`); well above
         * here, the margin serves to let the system batch this task with
         * others rather than wake the processor on the dot.
         */
        private const val CHECK_INTERVAL_HOURS = 3L

        /**
         * Schedules the periodic check. No effect if it already is:
         * [ExistingPeriodicWorkPolicy.KEEP] keeps the existing task rather than
         * starting from zero at each app start — replacing it at
         * each launch would amount, on an app opened several times
         * a day, to never letting the first delay elapse.
         *
         * Downside of KEEP: a change of [CHECK_INTERVAL_HOURS] between two
         * versions will not apply by itself to installations that had
         * already scheduled it with the old rate — the existing task is never
         * replaced. Harmless as long as the app has not yet been
         * published with the previous rate; to watch if this delay is touched
         * after a release.
         *
         * `WorkManager.getInstance` assumes its own initializer has already run —
         * automatic in a real app, absent in Robolectric tests,
         * which instantiate `TrackApplication` (hence call this function) without it.
         * That failure must no more prevent the app from starting than a failure
         * of the color backfill or of the welcome.
         */
        fun schedule(context: Context) {
            if (!UpdateConfig.isConfigured) return

            runCatching {
                val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                    CHECK_INTERVAL_HOURS, TimeUnit.HOURS
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}
