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

                    // Without the “Always” permission, the service could not move to the
                    // foreground and Android would kill the app: we notify instead,
                    // and the track waits in pause (see RecordingRecovery).
                    if (!RecordingRecovery.canRecordFromBackground(context)) {
                        RecordingRecovery.notifyInterrupted(context)
                        return@launch
                    }

                    // RESTORE and not RESUME: the service picks up the state stored in the database.
                    // A pause must remain a pause after a reboot.
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
                        // Start refused by the system: same fallback as above.
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
