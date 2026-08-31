package com.example.util.update

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
 * Vérifie, en tâche de fond, si une nouvelle version est publiée, et prévient
 * l'utilisateur par une notification si c'est le cas.
 *
 * Complète `UpdatePrompt`, qui ne consulte le réseau qu'à l'ouverture de
 * l'application : sans cette tâche, une mise à jour publiée pendant que
 * l'application reste fermée plusieurs jours ne se voyait qu'au prochain
 * lancement — s'il y en avait un.
 *
 * **Toutes les [CHECK_INTERVAL_HOURS] heures, jamais en continu.** Programmée via
 * WorkManager plutôt qu'un minuteur maison : le système bat le rappel dans une
 * fenêtre large et regroupe les tâches de plusieurs applications pour limiter les
 * réveils du processeur, l'inverse d'une vérification continue qui viderait la
 * batterie. `setRequiresBatteryNotLow(true)` renonce même à ce battement quand
 * l'appareil est déjà en réserve.
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
                "Mises à jour disponibles",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Signale une nouvelle version de Mes parcours à installer"
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
            .setContentTitle("Mise à jour disponible")
            .setContentText("La version ${update.versionName} de Mes parcours est prête à être installée.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Pas de vérification de POST_NOTIFICATIONS avant cet appel : sans elle,
        // notify() ne fait rien, silencieusement, sur les versions qui l'exigent —
        // même parti pris que TrackingService et AutoBackupManager pour leurs propres
        // notifications.
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "update_check_channel"
        private const val NOTIFICATION_ID = 8802
        private const val WORK_NAME = "update_check"

        /**
         * Cadence de vérification. WorkManager refuse en dessous de 15 minutes
         * (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`) ; largement au-dessus
         * ici, la marge sert à laisser le système regrouper cette tâche avec
         * d'autres plutôt qu'à réveiller le processeur pile à l'heure.
         */
        private const val CHECK_INTERVAL_HOURS = 3L

        /**
         * Programme la vérification périodique. Sans effet si elle l'est déjà :
         * [ExistingPeriodicWorkPolicy.KEEP] conserve la tâche existante plutôt que de
         * repartir de zéro à chaque démarrage de l'application — la remplacer à
         * chaque lancement reviendrait, sur une application ouverte plusieurs fois
         * par jour, à ne jamais laisser le premier délai s'écouler.
         *
         * Revers de KEEP : un changement de [CHECK_INTERVAL_HOURS] entre deux
         * versions ne s'appliquera pas tout seul aux installations qui l'avaient
         * déjà programmée avec l'ancienne cadence — la tâche existante n'est jamais
         * remplacée. Sans conséquence tant que l'application n'a pas encore été
         * publiée avec la cadence précédente ; à surveiller si ce délai est retouché
         * après une publication.
         *
         * `WorkManager.getInstance` suppose son propre initialiseur déjà passé —
         * automatique dans une vraie application, absent dans les tests Robolectric,
         * qui instancient `TrackApplication` (donc appellent cette fonction) sans lui.
         * Cet échec-là ne doit pas plus empêcher l'application de démarrer qu'un
         * échec de la reprise des couleurs ou de l'accueil.
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
