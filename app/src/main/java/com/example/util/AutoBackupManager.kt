package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutoBackupManager {
    private const val TAG = "AutoBackupManager"
    private const val CHANNEL_ID = "auto_backup_channel"
    private const val NOTIFICATION_ID = 8801

    fun performAutoBackup(context: Context, trackId: Long) {
        if (!AutoBackupPreferences.isAutoBackupEnabled(context)) {
            Log.d(TAG, "Auto backup is disabled in settings")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val track = db.trackDao.getTrackById(trackId) ?: return@launch

                val startTime = if (track.startTime > 0) track.startTime else System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH'h'mm", Locale.US)
                val dateStr = dateFormat.format(Date(startTime))
                val baseFileName = "Mes_parcours_$dateStr"

                val isGpx = AutoBackupPreferences.isFormatGpx(context)
                val isKml = AutoBackupPreferences.isFormatKml(context)

                if (!isGpx && !isKml) return@launch

                val formatsList = mutableListOf<String>()
                if (isGpx) formatsList.add("GPX")
                if (isKml) formatsList.add("KML")

                var localSuccess = false

                // Les points sont relus page par page et écrits au fil de l'eau : une
                // trace très dense est sauvegardée intégralement sans saturer la mémoire.

                // 1. Export GPX if requested
                if (isGpx) {
                    val ok = MediaStoreExporter.saveToLocalDownloadsStreaming(
                        context = context,
                        fileName = "$baseFileName.gpx",
                        mimeType = "application/gpx+xml"
                    ) { out ->
                        val writer = Exporter.GpxWriter(out)
                        writer.start(track)
                        forEachPointOf(db, trackId) { writer.add(it) }
                        writer.finish()
                    }
                    if (ok) localSuccess = true
                }

                // 2. Export KML if requested
                if (isKml) {
                    val ok = MediaStoreExporter.saveToLocalDownloadsStreaming(
                        context = context,
                        fileName = "$baseFileName.kml",
                        mimeType = "application/vnd.google-earth.kml+xml"
                    ) { out ->
                        val writer = Exporter.KmlWriter(out)
                        writer.start(track)
                        forEachPointOf(db, trackId) { writer.add(it) }
                        writer.finish()
                    }
                    if (ok) localSuccess = true
                }

                if (localSuccess) {
                    val formatsStr = formatsList.joinToString(", ")
                    val summaryMsg = "Sauvegarde auto : $formatsStr → Stockage local"
                    Log.d(TAG, summaryMsg)

                    withContext(Dispatchers.Main) {
                        showToastAndNotification(context, summaryMsg)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error performing auto backup", e)
            }
        }
    }

    /** Parcourt tous les points d'une trace, page par page. */
    private suspend fun forEachPointOf(
        db: AppDatabase,
        trackId: Long,
        action: (com.example.data.model.TrackPoint) -> Unit
    ) {
        var afterId = 0L
        while (true) {
            val page = db.trackDao.getPointsPage(trackId, afterId, 2_000)
            if (page.isEmpty()) break
            for (point in page) action(point)
            afterId = page.last().id
        }
    }

    private fun showToastAndNotification(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }

        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Sauvegarde automatique",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Notifications de confirmation de sauvegarde automatique"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Sauvegarde automatique réusssie")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }
    }
}
