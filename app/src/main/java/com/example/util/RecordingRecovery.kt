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
 * Reprise d'un enregistrement sans que l'application soit à l'écran : après un
 * redémarrage du téléphone, une mise à jour, ou un service relancé par le système.
 */
object RecordingRecovery {

    private const val CHANNEL_ID = "recording_interrupted_channel"
    private const val NOTIFICATION_ID = 88232

    /**
     * Le service de localisation peut-il repartir sans l'application au premier plan ?
     *
     * Depuis Android 11, un service démarré en arrière-plan n'obtient de positions que
     * si l'autorisation « Toujours » est accordée ; depuis Android 14, `startForeground`
     * lève même une exception sans elle. L'exception était attrapée, mais le service
     * restait alors sans premier plan, et Android tuait l'application quelques
     * secondes plus tard pour ne pas l'y avoir mis.
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
     * Prévient que l'enregistrement n'a pas pu reprendre seul. Le parcours reste
     * ouvert en base : l'application le retrouve en pause à l'ouverture, et un appui
     * sur « Reprendre » le relance depuis le premier plan, où rien ne s'y oppose.
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
        // Sans l'autorisation des notifications (Android 13+), notify() ne fait rien :
        // le parcours se retrouvera quand même en pause à l'ouverture.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
