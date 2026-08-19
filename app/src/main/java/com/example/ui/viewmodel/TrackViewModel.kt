package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.LiveStats
import com.example.data.model.MapTrack
import com.example.data.model.MapViewport
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.repository.TrackRepository
import com.example.service.TrackingService
import com.example.util.Exporter
import android.preference.PreferenceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/** Délai d'accalmie avant de recharger les points après un déplacement de carte. */
private const val VIEWPORT_DEBOUNCE_MS = 250L

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TrackViewModel(private val repository: TrackRepository, private val appContext: Context) : ViewModel() {

    /**
     * Zone actuellement visible sur la carte. Pilote le niveau de détail des tracés
     * denses : seuls les points de cette zone sont chargés en pleine résolution.
     */
    private val _mapViewport = MutableStateFlow<MapViewport?>(null)

    fun updateMapViewport(viewport: MapViewport) {
        _mapViewport.value = viewport
    }

    // Persistent Map State across tabs and sessions
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    var isAutoFollowActiveMap: Boolean
        get() = prefs.getBoolean("pref_auto_follow_active", true)
        set(value) = prefs.edit().putBoolean("pref_auto_follow_active", value).apply()

    var lastMapCenterLat: Double?
        get() = if (prefs.contains("pref_last_lat")) prefs.getFloat("pref_last_lat", 0f).toDouble() else null
        set(value) {
            if (value != null) prefs.edit().putFloat("pref_last_lat", value.toFloat()).apply()
        }

    var lastMapCenterLng: Double?
        get() = if (prefs.contains("pref_last_lng")) prefs.getFloat("pref_last_lng", 0f).toDouble() else null
        set(value) {
            if (value != null) prefs.edit().putFloat("pref_last_lng", value.toFloat()).apply()
        }

    var lastMapZoom: Double?
        get() = if (prefs.contains("pref_last_zoom")) prefs.getFloat("pref_last_zoom", 16.5f).toDouble() else null
        set(value) {
            if (value != null) {
                // Also update the general default zoom pref so recenter/position uses it!
                prefs.edit()
                    .putFloat("pref_last_zoom", value.toFloat())
                    .putFloat("pref_default_zoom", value.toFloat())
                    .apply()
            }
        }

    private var isGpsLostNotificationActive = false

    private fun updateGpsLostNotification(inForeground: Boolean, status: String, isTracking: Boolean) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "gps_lost_notification_channel"
        val notificationId = 100234

        val shouldShow = !inForeground && isTracking && status == "Recherche de signal..."

        if (shouldShow) {
            if (!isGpsLostNotificationActive) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "Perte de signal GPS",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notifications lors de la perte du signal GPS"
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val notificationIntent = Intent(appContext, com.example.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    appContext,
                    0,
                    notificationIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val notification = androidx.core.app.NotificationCompat.Builder(appContext, channelId)
                    .setContentTitle("Signal GPS perdu")
                    .setContentText("Le signal GPS est perdu 😨")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(notificationId, notification)
                isGpsLostNotificationActive = true
            }
        } else {
            if (isGpsLostNotificationActive) {
                notificationManager.cancel(notificationId)
                isGpsLostNotificationActive = false
            }
        }
    }

    init {
        viewModelScope.launch {
            repository.checkForAndRestoreActiveTrack()
        }

        viewModelScope.launch {
            var timeoutJob: kotlinx.coroutines.Job? = null
            combine(repository.gpsStatus, repository.isAppInForeground) { status, inForeground ->
                Pair(status, inForeground)
            }.collect { (status, inForeground) ->
                timeoutJob?.cancel()
                if (status == "Signal trouvé") {
                    timeoutJob = launch {
                        // Background location updates are typically throttled heavily by the OS (30s-60s+),
                        // so we use a much more generous timeout of 45 seconds in the background.
                        val delayMs = if (inForeground) 8000L else 45000L
                        kotlinx.coroutines.delay(delayMs)
                        repository.updateGpsStatus("Recherche de signal...")
                        repository.updateGpsAccuracy(null)
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(repository.isAppInForeground, repository.gpsStatus, repository.isTracking) { inForeground, status, isTracking ->
                Triple(inForeground, status, isTracking)
            }.collect { (inForeground, status, isTracking) ->
                updateGpsLostNotification(inForeground, status, isTracking)
            }
        }
    }

    val isAppInForeground: StateFlow<Boolean> = repository.isAppInForeground

    private val _bypassZoomThreshold = MutableStateFlow(false)
    val bypassZoomThreshold: StateFlow<Boolean> = _bypassZoomThreshold.asStateFlow()

    fun setBypassZoomThreshold(value: Boolean) {
        _bypassZoomThreshold.value = value
    }

    fun updateAppForegroundStatus(inForeground: Boolean) {
        repository.updateAppForegroundStatus(inForeground)
    }

    // Global tracking states (piped directly from source of truth Repository)
    val isTracking: StateFlow<Boolean> = repository.isTracking
    val isPaused: StateFlow<Boolean> = repository.isPaused
    val liveStats: StateFlow<LiveStats> = repository.liveStats
    val livePoints: StateFlow<List<TrackPoint>> = repository.livePoints
    val currentTrackId: StateFlow<Long?> = repository.currentTrackId

    // All stored tracks
    val allTracks: StateFlow<List<Track>> = repository.allTracks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val currentUserLocation: StateFlow<TrackPoint?> = repository.currentUserLocation

    /** Altitude au-dessus du niveau de la mer, null si aucune mesure exploitable. */
    val currentAltitude: StateFlow<com.example.util.AltitudeFix?> = repository.currentAltitude

    fun updateUserLocation(location: TrackPoint?) {
        repository.updateUserLocation(location)
    }

    fun updateAltitude(fix: com.example.util.AltitudeFix?) {
        repository.updateAltitude(fix)
    }

    val gpsStatus: StateFlow<String> = repository.gpsStatus
    val gpsAccuracy: StateFlow<Float?> = repository.gpsAccuracy

    fun updateGpsStatus(status: String) {
        repository.updateGpsStatus(status)
    }

    fun updateGpsAccuracy(accuracy: Float?) {
        repository.updateGpsAccuracy(accuracy)
    }

    /** Tracés à superposer sur la carte, chacun avec de quoi choisir sa couleur. */
    val selectedImportedPoints: StateFlow<List<MapTrack>> = combine(
        repository.getSelectedImportedTracksFlow(),
        _mapViewport.debounce(VIEWPORT_DEBOUNCE_MS)
    ) { tracks, viewport -> tracks to viewport }
    .flatMapLatest { (tracks, viewport) ->
        flow {
            val allPoints = tracks.map { track ->
                MapTrack(
                    isImported = track.isImported,
                    isMerged = track.isMerged,
                    sourceColor = track.sourceColor,
                    points = repository.getDisplayPoints(track.id, viewport)
                )
            }
            emit(allPoints)
        }
    }.flowOn(Dispatchers.IO)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    fun toggleTrackSelectionForMap(track: Track) {
        viewModelScope.launch {
            repository.updateTrack(track.copy(isSelectedForMap = !track.isSelectedForMap))
        }
    }

    // Detailing a recorded track
    private val _selectedTrackId = MutableStateFlow<Long?>(null)
    val selectedTrackId: StateFlow<Long?> = _selectedTrackId.asStateFlow()

    val selectedTrack: StateFlow<Track?> = _selectedTrackId
        .flatMapLatest { id ->
            if (id != null) {
                repository.getTrackByIdFlow(id)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = null
        )

    // Points destinés à l'affichage uniquement : sous-échantillonnés hors de la zone
    // visible pour les traces très denses. Ne jamais s'en servir pour exporter —
    // l'export relit la totalité des points en base (voir exportToUri).
    val selectedTrackPoints: StateFlow<List<TrackPoint>> = combine(
        _selectedTrackId,
        _mapViewport.debounce(VIEWPORT_DEBOUNCE_MS)
    ) { id, viewport -> id to viewport }
    .flatMapLatest { (id, viewport) ->
        if (id != null) {
            flow { emit(repository.getDisplayPoints(id, viewport)) }
        } else {
            flowOf(emptyList())
        }
    }.flowOn(Dispatchers.IO)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    fun startRecording(context: Context, trackName: String, activityType: String) {
        repository.setTrackingState(true)
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_TRACK_NAME, trackName)
            putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, activityType)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun resumeTrack(context: Context, trackId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val success = repository.resumeExistingTrack(trackId)
            if (success) {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_START
                    putExtra(TrackingService.EXTRA_TRACK_ID, trackId)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                onSuccess()
            }
        }
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pauseRecording(context: Context) {
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    /** Reprend l'enregistrement sur un nouveau tronçon, jamais raccordé au précédent. */
    fun resumeRecording(context: Context) {
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun renameTrack(track: Track, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == track.name) return
        viewModelScope.launch {
            repository.updateTrack(track.copy(name = trimmed))
        }
    }

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch {
            repository.deleteTrack(trackId)
            if (_selectedTrackId.value == trackId) {
                _selectedTrackId.value = null
            }
        }
    }

    fun selectTrack(trackId: Long?) {
        _selectedTrackId.value = trackId
    }

    fun importTrack(context: Context, uri: android.net.Uri, onSuccess: (Long) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val trackId = repository.importTrackFromUri(context, uri)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSuccess(trackId)
                }
            } catch (e: com.example.util.Importer.ImportException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.message ?: "Fichier illisible.")
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError("Erreur d'importation : ${e.localizedMessage}")
                }
            }
        }
    }

    fun mergeTracks(
        context: Context,
        trackIds: List<Long>,
        destinationTrackId: Long,
        mergedName: String,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mergedId = repository.mergeAndSaveTracks(trackIds, destinationTrackId, mergedName)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSuccess(mergedId)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError("Erreur de fusion : ${e.localizedMessage}")
                }
            }
        }
    }

    fun removeStationaryPoints(
        trackId: Long,
        thresholdMeters: Double,
        newName: String,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val newTrackId = repository.removeStationaryPoints(trackId, thresholdMeters, newName)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSuccess(newTrackId)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError("Erreur de nettoyage : ${e.localizedMessage}")
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Export
    //
    // L'export relit toujours la totalité des points en base, page par page. Il ne
    // dépend jamais de la liste affichée, qui est sous-échantillonnée hors de la
    // zone visible pour les traces denses. Un fichier exporté contient donc
    // exactement les points importés.
    // ------------------------------------------------------------------

    private enum class ExportFormat(val extension: String) {
        GPX(".gpx"),
        KML(".kml")
    }

    private suspend fun writeTrack(track: Track, out: Appendable, format: ExportFormat) {
        when (format) {
            ExportFormat.GPX -> {
                val writer = Exporter.GpxWriter(out)
                writer.start(track)
                repository.forEachPoint(track.id) { writer.add(it) }
                writer.finish()
            }
            ExportFormat.KML -> {
                val writer = Exporter.KmlWriter(out)
                writer.start(track)
                repository.forEachPoint(track.id) { writer.add(it) }
                writer.finish()
            }
        }
    }

    private fun safeFileName(name: String): String = name
        .replace("[\\\\/:*?\"<>|]".toRegex(), "_")
        .replace("\\s+".toRegex(), "_")

    private fun exportToUri(
        context: Context,
        uri: android.net.Uri,
        track: Track,
        format: ExportFormat,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val finalUri = ensureUriExtension(context, uri, format.extension)
                val stream = context.contentResolver.openOutputStream(finalUri)
                    ?: throw java.io.IOException("Impossible d'ouvrir le fichier de destination")
                stream.use { outputStream ->
                    outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writeTrack(track, writer, format)
                    }
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Erreur de sauvegarde")
                }
            }
        }
    }

    private fun shareTrack(context: Context, track: Track, format: ExportFormat) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Sous-dossier dédié : c'est le seul chemin exposé par le FileProvider.
                val exportDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                val cacheFile = java.io.File(
                    exportDir,
                    "${safeFileName(track.name)}${format.extension}"
                )
                cacheFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writeTrack(track, writer, format)
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    shareCachedFile(context, cacheFile, "application/octet-stream")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Erreur d'exportation : ${e.localizedMessage}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun shareGPX(context: Context, track: Track) = shareTrack(context, track, ExportFormat.GPX)

    fun shareKML(context: Context, track: Track) = shareTrack(context, track, ExportFormat.KML)

    fun saveGPXToUri(
        context: Context,
        uri: android.net.Uri,
        track: Track,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = exportToUri(context, uri, track, ExportFormat.GPX, onSuccess, onError)

    fun saveKMLToUri(
        context: Context,
        uri: android.net.Uri,
        track: Track,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) = exportToUri(context, uri, track, ExportFormat.KML, onSuccess, onError)

    private fun ensureUriExtension(context: Context, uri: android.net.Uri, expectedExtension: String): android.net.Uri {
        try {
            var displayName: String? = null
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        displayName = cursor.getString(idx)
                    }
                }
            }
            val name = displayName
            if (name != null && !name.endsWith(expectedExtension, ignoreCase = true)) {
                val newName = "$name$expectedExtension"
                val renamedUri = android.provider.DocumentsContract.renameDocument(context.contentResolver, uri, newName)
                if (renamedUri != null) {
                    return renamedUri
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return uri
    }

    /** Partage un fichier déjà écrit dans le cache. À appeler depuis le thread principal. */
    private fun shareCachedFile(context: Context, cacheFile: java.io.File, fileType: String) {
        try {
            val fileName = cacheFile.name

            // Generate content URI using FileProvider
            val authority = "${context.packageName}.fileprovider"
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                authority,
                cacheFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = fileType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "Parcours : $fileName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Exporter le parcours").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } catch (e: Exception) {
            e.printStackTrace()
            // Toast crash/failure with user UI feedback
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Erreur d'exportation : ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }



    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TrackViewModel::class.java)) {
                val repo = TrackRepository.getInstance(context.applicationContext)
                @Suppress("UNCHECKED_CAST")
                return TrackViewModel(repo, context.applicationContext) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
