package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

/**
 * Resuming a recording without the app being on screen: after a phone
 * reboot, an update, or a service restarted by the system.
 */
object RecordingRecovery {

    private const val CHANNEL_ID = "recording_interrupted_channel"
    private const val NOTIFICATION_ID = 88232

    /**
     * Can the location service restart without the app in the foreground?
     *
     * Since Android 11, a service started in the background only gets positions
     * if the “Always” permission is granted; since Android 14, `startForeground`
     * even throws an exception without it. The exception was caught, but the service
     * then stayed without a foreground, and Android killed the app a few
     * seconds later for not having put it there.
     */
    fun canRecordFromBackground(context: Context): Boolean {
        val fine = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        return true
    }

    /**
     * Warns that the recording could not resume on its own. The track stays
     * open in the database: the app finds it paused on opening, and a tap
     * on “Resume” restarts it from the foreground, where nothing prevents it.
     */
    fun notifyInterrupted(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.interrupted_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.interrupted_channel_description)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(context.getString(R.string.interrupted_title))
            .setContentText(context.getString(R.string.interrupted_text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // Without the notifications permission (Android 13+), notify() does nothing:
        // the track will still end up paused on opening.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
