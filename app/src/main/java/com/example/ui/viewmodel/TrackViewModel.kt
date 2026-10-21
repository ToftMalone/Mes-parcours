package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.model.GpsStatus
import com.example.util.userMessage
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Maximum rate at which points are reloaded when the map moves.
 *
 * **`sample` and not `debounce`, and that is the whole point.** `debounce` only emits after
 * a silence — here 250 ms without a new area. But osmdroid notifies `onScroll` on every
 * frame of its animations: `MapController.onAnimationUpdate` calls `setMapCenter`
 * about sixty times a second, and each call goes down into `MapView.scrollTo`
 * which notifies the listeners. In automatic following, `animateTo` is relaunched at each
 * GPS position for a one-second animation: the animations chain without
 * interruption, so the silence never comes, and `debounce` simply no longer emitted
 * anything. Tracks stayed frozen on the coarse silhouette
 * computed when the mode had been activated — long straight lines between
 * widely spaced points — until a gesture cut the following, stopped the
 * animations, finally let the silence through and reloaded everything at once.
 *
 * `sample` reverses the logic: it emits the most recent area at a regular
 * interval as long as some arrive, and lets the last one through once the flow dries up.
 * A continuous flow can therefore no longer starve it, and a map that stops is
 * still served.
 */
private const val VIEWPORT_SAMPLE_MS = 500L

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class TrackViewModel(private val repository: TrackRepository, private val appContext: Context) : ViewModel() {

    /**
     * Area currently visible on the map. Drives the level of detail of dense
     * tracks: only the points of this area are loaded at full resolution.
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

    private fun updateGpsLostNotification(inForeground: Boolean, status: GpsStatus, isTracking: Boolean) {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "gps_lost_notification_channel"
        val notificationId = 100234

        val shouldShow = !inForeground && isTracking && status == GpsStatus.SEARCHING

        if (shouldShow) {
            if (!isGpsLostNotificationActive) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId,
                        appContext.getString(R.string.gps_lost_channel_name),
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = appContext.getString(R.string.gps_lost_channel_description)
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
                    .setContentTitle(appContext.getString(R.string.gps_lost_title))
                    .setContentText(appContext.getString(R.string.gps_lost_notification_text))
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
                if (status == GpsStatus.FOUND) {
                    timeoutJob = launch {
                        // Background location updates are typically throttled heavily by the OS (30s-60s+),
                        // so we use a much more generous timeout of 45 seconds in the background.
                        val delayMs = if (inForeground) 8000L else 45000L
                        kotlinx.coroutines.delay(delayMs)
                        repository.updateGpsStatus(GpsStatus.SEARCHING)
                        repository.updateGpsAccuracy(null)
                    }
                }
            }
        }

        viewModelScope.launch {
            // While paused, positions deliberately arrive in a trickle (see
            // TrackingService.requestLocationUpdates) and nothing is recorded: a
            // silence there is not a signal loss to report.
            combine(
                repository.isAppInForeground, repository.gpsStatus, repository.isTracking, repository.isPaused
            ) { inForeground, status, isTracking, isPaused ->
                Triple(inForeground, status, isTracking && !isPaused)
            }.collect { (inForeground, status, isRecordingActive) ->
                updateGpsLostNotification(inForeground, status, isRecordingActive)
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

    /** Altitude above sea level, null if no usable measurement. */
    val currentAltitude: StateFlow<com.example.util.AltitudeFix?> = repository.currentAltitude

    fun updateUserLocation(location: TrackPoint?) {
        repository.updateUserLocation(location)
    }

    fun updateAltitude(fix: com.example.util.AltitudeFix?) {
        repository.updateAltitude(fix)
    }

    val gpsStatus: StateFlow<GpsStatus> = repository.gpsStatus
    val gpsAccuracy: StateFlow<Float?> = repository.gpsAccuracy

    fun updateGpsStatus(status: GpsStatus) {
        repository.updateGpsStatus(status)
    }

    fun updateGpsAccuracy(accuracy: Float?) {
        repository.updateGpsAccuracy(accuracy)
    }

    /**
     * Tracks to overlay on the map, each with what is needed to choose its color.
     *
     * **`conflate()` and not `flatMapLatest`.** `flatMapLatest` cancels the read in
     * progress as soon as a new area arrives. Outside automatic following, areas
     * only arrive on the user's gestures and this was not visible; but in
     * focus mode, the area is republished every second, and any read longer
     * than a second was therefore cancelled before producing anything. It
     * restarted from zero on the next turn to be cancelled again: tracks
     * simply stopped loading as long as the mode stayed active.
     * That is what explained why a manual gesture — which cuts the following, hence
     * the republications — made everything reappear at once.
     *
     * `conflate()` never cuts a read that has started: the areas that arrive
     * while it works overwrite one another, and it is the most
     * recent that is processed once the previous one is finished. The result arrives
     * perhaps one turn late, but it arrives.
     */
    val selectedImportedPoints: StateFlow<List<MapTrack>> = combine(
        repository.getSelectedImportedTracksFlow(),
        _mapViewport.sample(VIEWPORT_SAMPLE_MS)
    ) { tracks, viewport -> tracks to viewport }
    .conflate()
    .map { (tracks, viewport) ->
        tracks.map { track ->
            MapTrack(
                isImported = track.isImported,
                isMerged = track.isMerged,
                sourceColor = track.sourceColor,
                displayColor = track.displayColor,
                displayWidthDp = track.displayWidthDp,
                points = repository.getDisplayPoints(track.id, viewport)
            )
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

    // Points meant for display only: subsampled outside the visible area
    // for very dense tracks. Never use them to export —
    // the export rereads all the points in the database (see exportToUri).
    //
    // `conflate()` for the same reason as selectedImportedPoints: a read that has
    // started must be able to run to completion, even if the area moves in the meantime.
    val selectedTrackPoints: StateFlow<List<TrackPoint>> = combine(
        _selectedTrackId,
        _mapViewport.sample(VIEWPORT_SAMPLE_MS)
    ) { id, viewport -> id to viewport }
    .conflate()
    .map { (id, viewport) ->
        if (id != null) repository.getDisplayPoints(id, viewport) else emptyList()
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

    /** Resumes the recording on a new segment, never joined to the previous one. */
    fun resumeRecording(context: Context) {
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    /**
     * Sets a track's appearance: its color, or automatic with [color] at
     * null — in which case it gets back the color of its original file if it has
     * one — and its line thickness, in dp.
     */
    fun setTrackStyle(track: Track, color: Int?, widthDp: Float) {
        if (track.displayColor == color && track.displayWidthDp == widthDp) return
        viewModelScope.launch {
            repository.updateTrack(track.copy(displayColor = color, displayWidthDp = widthDp))
        }
    }

    fun renameTrack(track: Track, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == track.name) return
        viewModelScope.launch {
            repository.updateTrack(track.copy(name = trimmed))
        }
    }

    /**
     * Deletes a track. [onResult] receives false if the deletion was refused
     * because the track is being recorded (see
     * `TrackRepository.deleteTrack`).
     */
    fun deleteTrack(trackId: Long, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = repository.deleteTrack(trackId)
            if (deleted && _selectedTrackId.value == trackId) {
                _selectedTrackId.value = null
            }
            onResult(deleted)
        }
    }

    /** True for the track a recording feeds: neither merging nor deletion. */
    fun isBeingRecorded(track: Track): Boolean = repository.isBeingRecorded(track)

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
                    onError(e.userMessage(appContext, R.string.err_file_unreadable))
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(appContext.getString(R.string.err_import_failed, e.userMessage(appContext, R.string.err_unknown)))
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
                    onError(appContext.getString(R.string.err_merge_failed, e.userMessage(appContext, R.string.err_unknown)))
                }
            }
        }
    }

    /**
     * Splits a track into several, and returns the identifiers created.
     *
     * Reading the points happens on [kotlinx.coroutines.Dispatchers.IO] as
     * for merging: it rereads the whole track and has no business on the main
     * thread.
     */
    fun splitTrack(
        trackId: Long,
        mode: TrackRepository.SplitMode,
        gapMillis: Long,
        baseName: String,
        deleteSource: Boolean,
        onSuccess: (List<Long>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val newTrackIds = repository.splitTrack(
                    sourceTrackId = trackId,
                    mode = mode,
                    gapMillis = gapMillis,
                    baseName = baseName,
                    deleteSource = deleteSource
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSuccess(newTrackIds)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // The message carried by the exception is already written to be read
                    // (“there is nothing to split”…): prefixing it would make it less
                    // clear, not more.
                    onError(e.userMessage(appContext, R.string.err_split_failed))
                }
            }
        }
    }

    /**
     * Trims the start and/or the end of a track, and returns the identifier created.
     *
     * On [kotlinx.coroutines.Dispatchers.IO] like splitting: the read walks through
     * all the points and has no business on the main thread.
     */
    fun trimTrack(
        trackId: Long,
        dropStartMillis: Long,
        dropEndMillis: Long,
        newName: String,
        deleteSource: Boolean,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val newTrackId = repository.trimTrack(
                    sourceTrackId = trackId,
                    dropStartMillis = dropStartMillis,
                    dropEndMillis = dropEndMillis,
                    newName = newName,
                    deleteSource = deleteSource
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSuccess(newTrackId)
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // Same reason as for splitting: the exception's message is
                    // already written to be read as is.
                    onError(e.userMessage(appContext, R.string.err_trim_failed))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Export
    //
    // The export always rereads all the points in the database, page by page. It
    // never depends on the displayed list, which is subsampled outside the
    // visible area for dense tracks. An exported file therefore contains
    // exactly the imported points.
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
                val stream = openTruncating(context, finalUri)
                    ?: throw java.io.IOException(context.getString(R.string.err_export_open))
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
                    onError(e.userMessage(context, R.string.err_export_failed))
                }
            }
        }
    }

    /**
     * Opens the destination, emptying it first (mode “wt”).
     *
     * The default mode, “w”, does not truncate the file with some document
     * providers: overwriting an older, longer export left its tail behind the
     * new content, and the resulting GPX was no longer readable. A provider that
     * does not know “wt” falls back to “w”, which is always better than a failure.
     */
    private fun openTruncating(context: Context, uri: android.net.Uri): java.io.OutputStream? =
        try {
            context.contentResolver.openOutputStream(uri, "wt")
        } catch (e: java.io.FileNotFoundException) {
            context.contentResolver.openOutputStream(uri, "w")
        } catch (e: IllegalArgumentException) {
            context.contentResolver.openOutputStream(uri, "w")
        }

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
