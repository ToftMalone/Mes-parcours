package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.repository.TrackRepository
import com.example.service.TrackingService
import com.example.util.RecordingRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repo = TrackRepository.getInstance(context)
                    if (repo.getActiveRecordingTrack() == null) return@launch

                    // Sans l'autorisation « Toujours », le service ne pourrait pas passer
                    // au premier plan et Android tuerait l'application : on prévient
                    // plutôt, le parcours attend en pause (voir RecordingRecovery).
                    if (!RecordingRecovery.canRecordFromBackground(context)) {
                        RecordingRecovery.notifyInterrupted(context)
                        return@launch
                    }

                    // RESTORE et non RESUME : le service reprend l'état mémorisé en base.
                    // Une pause doit rester une pause après un redémarrage.
                    val serviceIntent = Intent(context, TrackingService::class.java).apply {
                        action = TrackingService.ACTION_RESTORE
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        // Démarrage refusé par le système : même repli que ci-dessus.
                        e.printStackTrace()
                        RecordingRecovery.notifyInterrupted(context)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
