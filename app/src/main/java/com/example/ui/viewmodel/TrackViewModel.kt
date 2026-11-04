package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.model.GpsStatus
import com.example.util.userMessage
import com.example.util.DebugLog
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
 * Cadence maximale de rechargement des points quand la carte bouge.
 *
 * **`sample` et non `debounce`, et c'est tout le sujet.** `debounce` n'émet qu'après
 * un silence — ici 250 ms sans nouvelle zone. Or osmdroid notifie `onScroll` à chaque
 * image de ses animations : `MapController.onAnimationUpdate` appelle `setMapCenter`
 * une soixantaine de fois par seconde, et chaque appel descend dans `MapView.scrollTo`
 * qui prévient les écouteurs. En suivi automatique, `animateTo` est relancé à chaque
 * position GPS pour une animation d'une seconde : les animations s'enchaînent sans
 * interruption, le silence n'arrive donc jamais, et `debounce` n'émettait tout
 * simplement plus rien. Les tracés restaient figés sur la silhouette grossière
 * calculée au moment où le mode avait été activé — de longues lignes droites entre
 * des points très espacés — jusqu'à ce qu'un geste coupe le suivi, arrête les
 * animations, laisse enfin passer le silence et recharge tout d'un coup.
 *
 * `sample` renverse la logique : il émet la zone la plus récente à intervalle
 * régulier tant qu'il en arrive, et laisse passer la dernière une fois le flux tari.
 * Un flux continu ne peut donc plus l'affamer, et une carte qui s'arrête est quand
 * même servie.
 */
private const val VIEWPORT_SAMPLE_MS = 500L

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

    // État de la carte, conservé d'un onglet et d'une session à l'autre
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
                // Met aussi à jour le zoom mémorisé, que reprend le recentrage
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
                        // En arrière-plan, le système espace fortement les positions (30 à 60 s et plus) :
                        // le délai y est donc porté à 45 secondes.
                        val delayMs = if (inForeground) 8000L else 45000L
                        kotlinx.coroutines.delay(delayMs)
                        repository.updateGpsStatus(GpsStatus.SEARCHING)
                        repository.updateGpsAccuracy(null)
                    }
                }
            }
        }

        viewModelScope.launch {
            // En pause, les positions arrivent volontairement au compte-gouttes (voir
            // TrackingService.requestLocationUpdates) et rien ne s'enregistre : un
            // silence n'y est pas une perte de signal à signaler.
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
        DebugLog.i("Action", "Forcer l'affichage des tracés : $value")
        _bypassZoomThreshold.value = value
    }

    fun updateAppForegroundStatus(inForeground: Boolean) {
        DebugLog.i("Appli", if (inForeground) "Application au premier plan" else "Application en arrière-plan")
        repository.updateAppForegroundStatus(inForeground)
    }

    // État de l'enregistrement, lu directement dans le dépôt, source de vérité
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
        location?.let { DebugLog.d("Position", "écran : ${it.latitude}, ${it.longitude}, vitesse ${it.speed} m/s") }
        repository.updateUserLocation(location)
    }

    fun updateAltitude(fix: com.example.util.AltitudeFix?) {
        repository.updateAltitude(fix)
    }

    val gpsStatus: StateFlow<GpsStatus> = repository.gpsStatus
    val gpsAccuracy: StateFlow<Float?> = repository.gpsAccuracy

    fun updateGpsStatus(status: GpsStatus) {
        if (status != repository.gpsStatus.value) DebugLog.i("GPS", "état : $status")
        repository.updateGpsStatus(status)
    }

    fun updateGpsAccuracy(accuracy: Float?) {
        repository.updateGpsAccuracy(accuracy)
    }

    /**
     * Tracés à superposer sur la carte, chacun avec de quoi choisir sa couleur.
     *
     * **`conflate()` et non `flatMapLatest`.** `flatMapLatest` annule la lecture en
     * cours dès qu'une nouvelle zone arrive. Hors suivi automatique, les zones
     * n'arrivent qu'aux gestes de l'utilisateur et cela ne se voyait pas ; mais en
     * mode focus, la zone est republiée à chaque seconde, et toute lecture plus
     * longue qu'une seconde était donc annulée avant d'avoir rien produit. Elle
     * repartait de zéro au tour suivant pour être annulée de nouveau : les tracés
     * cessaient purement et simplement de se charger tant que le mode restait actif.
     * C'est ce qui expliquait qu'un geste manuel — qui coupe le suivi, donc les
     * republications — fasse tout réapparaître d'un coup.
     *
     * `conflate()` ne coupe jamais une lecture commencée : les zones qui arrivent
     * pendant qu'elle travaille s'écrasent les unes les autres, et c'est la plus
     * récente qui est traitée une fois la précédente terminée. Le résultat arrive
     * peut-être avec un tour de retard, mais il arrive.
     */
    val selectedImportedPoints: StateFlow<List<MapTrack>> = combine(
        repository.getSelectedImportedTracksFlow(),
        _mapViewport.sample(VIEWPORT_SAMPLE_MS)
    ) { tracks, viewport -> tracks to viewport }
    .conflate()
    .map { (tracks, viewport) ->
        // Durée et volume de chaque rechargement : la première chose à regarder quand
        // la carte rame (rapporté après la 2.3.10 : zoom et dézoom lents).
        val start = System.nanoTime()
        tracks.map { track ->
            MapTrack(
                isImported = track.isImported,
                isMerged = track.isMerged,
                sourceColor = track.sourceColor,
                displayColor = track.displayColor,
                displayWidthDp = track.displayWidthDp,
                points = repository.getDisplayPoints(track.id, viewport)
            )
        }.also { loaded ->
            DebugLog.d(
                "Carte",
                "tracés superposés rechargés : ${loaded.size} parcours, ${loaded.sumOf { it.points.size }} points, " +
                    "${(System.nanoTime() - start) / 1_000_000} ms, zone $viewport"
            )
        }
    }.flowOn(Dispatchers.IO)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    fun toggleTrackSelectionForMap(track: Track) {
        DebugLog.i("Action", "Carte : « ${track.name} » (#${track.id}) ${if (track.isSelectedForMap) "masqué" else "affiché"}")
        viewModelScope.launch {
            repository.updateTrack(track.copy(isSelectedForMap = !track.isSelectedForMap))
        }
    }

    fun setTracksShownOnMap(tracks: List<Track>, shown: Boolean) {
        DebugLog.i("Action", "Carte : ${tracks.size} parcours ${if (shown) "affichés" else "masqués"} (${tracks.joinToString { "#" + it.id }})")
        viewModelScope.launch {
            repository.setShownOnMap(tracks.map { it.id }, shown)
        }
    }

    // Fiche détail d'un parcours
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
    //
    // `conflate()` pour la même raison que selectedImportedPoints : une lecture
    // commencée doit pouvoir aller à son terme, même si la zone bouge entre-temps.
    val selectedTrackPoints: StateFlow<List<TrackPoint>> = combine(
        _selectedTrackId,
        _mapViewport.sample(VIEWPORT_SAMPLE_MS)
    ) { id, viewport -> id to viewport }
    .conflate()
    .map { (id, viewport) ->
        if (id != null) {
            val start = System.nanoTime()
            repository.getDisplayPoints(id, viewport).also {
                DebugLog.d("Carte", "fiche #$id rechargée : ${it.size} points, ${(System.nanoTime() - start) / 1_000_000} ms")
            }
        } else emptyList()
    }.flowOn(Dispatchers.IO)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = emptyList()
    )

    fun startRecording(context: Context, trackName: String, activityType: String) {
        DebugLog.i("Action", "Démarrer un enregistrement « $trackName » ($activityType)")
        repository.setTrackingState(true)
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_TRACK_NAME, trackName)
            putExtra(TrackingService.EXTRA_ACTIVITY_TYPE, activityType)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun resumeTrack(context: Context, trackId: Long, onSuccess: () -> Unit) {
        DebugLog.i("Action", "Reprendre le parcours #$trackId")
        viewModelScope.launch {
            val success = repository.resumeExistingTrack(trackId)
            if (success) {
                val intent = Intent(context, TrackingService::class.java).apply {
                    action = TrackingService.ACTION_START
                    putExtra(TrackingService.EXTRA_TRACK_ID, trackId)
                }
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
                DebugLog.i("Action", "resumeTrack : terminé")
                onSuccess()
            }
        }
    }

    fun stopRecording(context: Context) {
        DebugLog.i("Action", "Arrêter l'enregistrement")
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun pauseRecording(context: Context) {
        DebugLog.i("Action", "Mettre en pause")
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    /** Reprend l'enregistrement sur un nouveau tronçon, jamais raccordé au précédent. */
    fun resumeRecording(context: Context) {
        DebugLog.i("Action", "Reprendre après la pause")
        val intent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_RESUME
        }
        context.startService(intent)
    }

    /**
     * Fixe l'apparence d'un parcours : sa couleur, ou l'automatique avec [color] à
     * null — auquel cas il retrouve la couleur de son fichier d'origine s'il en a
     * une — et l'épaisseur de son trait, en dp.
     */
    fun setTrackStyle(track: Track, color: Int?, widthDp: Float) {
        DebugLog.i("Action", "Apparence de « ${track.name} » (#${track.id}) : couleur $color, épaisseur $widthDp dp")
        if (track.displayColor == color && track.displayWidthDp == widthDp) return
        viewModelScope.launch {
            repository.updateTrack(track.copy(displayColor = color, displayWidthDp = widthDp))
        }
    }

    fun renameTrack(track: Track, newName: String) {
        DebugLog.i("Action", "Renommer #${track.id} « ${track.name} » en « $newName »")
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == track.name) return
        viewModelScope.launch {
            repository.updateTrack(track.copy(name = trimmed))
        }
    }

    /**
     * Supprime un parcours. [onResult] reçoit false si la suppression a été refusée
     * parce que le parcours est en cours d'enregistrement (voir
     * `TrackRepository.deleteTrack`).
     */
    fun deleteTrack(trackId: Long, onResult: (Boolean) -> Unit = {}) {
        DebugLog.i("Action", "Supprimer le parcours #$trackId")
        viewModelScope.launch {
            val deleted = repository.deleteTrack(trackId)
            if (deleted) trimTrash()
            if (deleted && _selectedTrackId.value == trackId) {
                _selectedTrackId.value = null
            }
            onResult(deleted)
        }
    }

    /**
     * Supprime plusieurs parcours d'un coup (mode sélection de l'historique). Le parcours
     * en cours d'enregistrement est refusé, comme à l'unité : [onResult] reçoit le nombre
     * de suppressions faites et de refus.
     */
    fun deleteTracks(trackIds: List<Long>, onResult: (deleted: Int, refused: Int) -> Unit = { _, _ -> }) {
        DebugLog.i("Action", "Supprimer ${trackIds.size} parcours : $trackIds")
        viewModelScope.launch {
            var deleted = 0
            var refused = 0
            trackIds.forEach { id ->
                if (repository.deleteTrack(id)) {
                    deleted++
                    if (_selectedTrackId.value == id) _selectedTrackId.value = null
                } else {
                    refused++
                }
            }
            if (deleted > 0) trimTrash()
            onResult(deleted, refused)
        }
    }

    /** Les parcours de la corbeille. */
    val trashedTracks: StateFlow<List<Track>> = repository.trashedTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Avancement du vidage de la corbeille, en pour cent ; null hors vidage. */
    val trashEraseProgress: StateFlow<Int?> = repository.trashEraseProgress

    /** Sort un parcours de la corbeille. */
    fun restoreFromTrash(trackId: Long, onDone: () -> Unit = {}) {
        DebugLog.i("Action", "Restaurer depuis la corbeille #$trackId")
        viewModelScope.launch {
            repository.restoreFromTrash(trackId)
            onDone()
        }
    }

    /**
     * Efface pour de bon tout le contenu de la corbeille (voir [TrackRepository.emptyTrash]).
     * Un échec (téléphone plein…) n'est plus avalé : il fermait l'application ou laissait
     * croire la corbeille vidée.
     */
    fun emptyTrash(onDone: (Int) -> Unit = {}, onError: (String) -> Unit = {}) {
        DebugLog.i("Action", "Vider la corbeille")
        viewModelScope.launch {
            runCatching { repository.emptyTrash() }
                .onSuccess(onDone)
                .onFailure {
                    DebugLog.e("Action", "emptyTrash : échec", it)
                    onError(it.userMessage(appContext, R.string.err_trash_empty_failed))
                }
        }
    }

    /**
     * Borne la corbeille après une mise à la corbeille (voir
     * [TrackRepository.enforceTrashLimit]). En arrière-plan et sans bruit : l'opération
     * demandée a déjà réussi, et un échec ici sera rattrapé au prochain démarrage.
     */
    private fun trimTrash() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { repository.enforceTrashLimit() }
                .onFailure { DebugLog.e("Action", "borne de la corbeille : échec", it) }
        }
    }

    /** Vrai pour le parcours qu'un enregistrement alimente : ni fusion ni suppression. */
    fun isBeingRecorded(track: Track): Boolean = repository.isBeingRecorded(track)

    fun selectTrack(trackId: Long?) {
        DebugLog.i("Action", if (trackId != null) "Ouvrir la fiche du parcours #$trackId" else "Fermer la fiche")
        _selectedTrackId.value = trackId
    }

    fun importTrack(context: Context, uri: android.net.Uri, onSuccess: (Long) -> Unit, onError: (String) -> Unit) {
        DebugLog.i("Action", "Importer $uri")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val trackId = repository.importTrackFromUri(context, uri)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    DebugLog.i("Action", "importTrack : terminé")
                    onSuccess(trackId)
                }
            } catch (e: com.example.util.Importer.ImportException) {
                DebugLog.e("Action", "importTrack : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.userMessage(appContext, R.string.err_file_unreadable))
                }
            } catch (e: Exception) {
                DebugLog.e("Action", "importTrack : échec", e)
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
        DebugLog.i("Action", "Fusionner $trackIds dans #$destinationTrackId « $mergedName »")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val mergedId = repository.mergeAndSaveTracks(trackIds, destinationTrackId, mergedName)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    DebugLog.i("Action", "mergeTracks : terminé")
                    onSuccess(mergedId)
                }
            } catch (e: Exception) {
                DebugLog.e("Action", "mergeTracks : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(appContext.getString(R.string.err_merge_failed, e.userMessage(appContext, R.string.err_unknown)))
                }
            }
        }
    }

    /**
     * Découpe un parcours en plusieurs, et renvoie les identifiants créés.
     *
     * La lecture des points se fait sur [kotlinx.coroutines.Dispatchers.IO] comme
     * pour la fusion : elle relit tout le parcours et n'a rien à faire sur le thread
     * principal.
     */
    /** Aperçu allégé d'un parcours, pour y choisir une portion sur la carte. */
    suspend fun loadPreviewPoints(trackId: Long): List<com.example.data.model.TrackPoint> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.getPreviewPoints(trackId)
        }

    /** Prolonge un parcours d'un tracé dessiné sur la carte. */
    fun extendTrack(
        trackId: Long,
        taps: List<Pair<Double, Double>>,
        atEnd: Boolean,
        /** Le point choisi sur le tracé, quand on prolonge depuis un point ; [atEnd] est alors ignoré. */
        from: com.example.util.PortionBound? = null,
        newName: String,
        deleteSource: Boolean,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        DebugLog.i("Action", "Prolonger #$trackId : ${taps.size} appuis, depuis $from, à la fin $atEnd, supprimer l'original $deleteSource")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val id = if (from != null) repository.extendFromPoint(trackId, from, taps, newName, deleteSource)
                else repository.extendTrack(trackId, taps, atEnd, newName, deleteSource)
                if (deleteSource) trimTrash()
                DebugLog.i("Action", "extendTrack : terminé")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onSuccess(id) }
            } catch (e: Exception) {
                DebugLog.e("Action", "extendTrack : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.userMessage(appContext, R.string.err_extend_failed))
                }
            }
        }
    }

    /** Le détail d'un parcours dans la zone visible du sélecteur de portion. */
    suspend fun loadPickerPoints(
        trackId: Long,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<com.example.data.model.TrackPoint> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val start = System.nanoTime()
            repository.getPickerPoints(trackId, minLat, maxLat, minLon, maxLon).also {
                DebugLog.d(
                    "Outils",
                    "carte de l'outil, #$trackId rechargé : ${it.size} points, ${(System.nanoTime() - start) / 1_000_000} ms, " +
                        "zone $minLat..$maxLat / $minLon..$maxLon"
                )
            }
        }

    /**
     * Où regarde la dernière carte, quelle qu'elle soit : les cartes des outils s'ouvrent
     * là, et la leur, en bougeant, la mémorise pour la carte principale. Demandé par
     * l'auteur : « si je suis à Paris sur la carte principale, l'outil s'ouvre sur Paris,
     * au même zoom ». Mêmes préférences que la carte principale.
     */
    var mapCamera: com.example.ui.component.MapCamera?
        get() {
            val lat = lastMapCenterLat ?: return null
            val lon = lastMapCenterLng ?: return null
            return com.example.ui.component.MapCamera(lat, lon, lastMapZoom ?: 16.5)
        }
        set(value) {
            if (value == null) return
            lastMapCenterLat = value.latitude
            lastMapCenterLng = value.longitude
            lastMapZoom = value.zoom
        }

    /** Les parcours affichés en fond dans « Prolonger », gardés tant que l'application vit. */
    val extendBackgroundIds = androidx.compose.runtime.mutableStateListOf<Long>()

    /** Distance de la portion comprise entre deux bornes d'un parcours. */
    suspend fun measurePortion(
        trackId: Long,
        from: com.example.util.PortionBound,
        to: com.example.util.PortionBound
    ): Double =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.measurePortion(trackId, from, to)
        }

    /** La borne exacte d'un appui sur le tracé (voir [TrackRepository.findPortionBound]). */
    suspend fun findPortionBound(
        trackId: Long,
        lowId: Long?,
        highId: Long?,
        lat: Double,
        lon: Double,
        radiusMeters: Double
    ): com.example.util.PortionBound? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.findPortionBound(trackId, lowId, highId, lat, lon, radiusMeters)
        }

    /** Le tronçon touché d'un appui prolongé sur la carte (voir [TrackRepository.findSegment]). */
    suspend fun findSegment(trackId: Long, pointId: Long): TrackRepository.TrackSegment? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.findSegment(trackId, pointId)
        }

    /** Retire un tronçon de son parcours (voir [TrackRepository.removeSegment]). */
    fun removeSegment(
        segment: TrackRepository.TrackSegment,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        DebugLog.i("Action", "Retirer un tronçon : $segment")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                repository.removeSegment(segment)
                trimTrash()
                DebugLog.i("Action", "removeSegment : terminé")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                DebugLog.e("Action", "removeSegment : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.userMessage(appContext, R.string.err_split_failed))
                }
            }
        }
    }

    /** Retire une portion choisie sur la carte (voir [TrackRepository.cutPortion]). */
    fun cutPortion(
        trackId: Long,
        from: com.example.util.PortionBound,
        to: com.example.util.PortionBound,
        baseName: String,
        deleteSource: Boolean,
        onSuccess: (List<Long>) -> Unit,
        onError: (String) -> Unit
    ) {
        DebugLog.i("Action", "Retirer une portion de #$trackId : de $from à $to, supprimer l'original $deleteSource")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val newTrackIds = repository.cutPortion(trackId, from, to, baseName, deleteSource)
                if (deleteSource) trimTrash()
                DebugLog.i("Action", "cutPortion : terminé")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { onSuccess(newTrackIds) }
            } catch (e: Exception) {
                DebugLog.e("Action", "cutPortion : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.userMessage(appContext, R.string.err_split_failed))
                }
            }
        }
    }

    fun splitTrack(
        trackId: Long,
        mode: TrackRepository.SplitMode,
        gapMillis: Long,
        baseName: String,
        deleteSource: Boolean,
        onSuccess: (List<Long>) -> Unit,
        onError: (String) -> Unit
    ) {
        DebugLog.i("Action", "Découper #$trackId : mode $mode, écart $gapMillis ms, supprimer l'original $deleteSource")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val newTrackIds = repository.splitTrack(
                    sourceTrackId = trackId,
                    mode = mode,
                    gapMillis = gapMillis,
                    baseName = baseName,
                    deleteSource = deleteSource
                )
                if (deleteSource) trimTrash()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    DebugLog.i("Action", "splitTrack : terminé")
                    onSuccess(newTrackIds)
                }
            } catch (e: Exception) {
                DebugLog.e("Action", "splitTrack : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // Le message porté par l'exception est déjà rédigé pour être lu
                    // (« il n'y a rien à découper »…) : le préfixer le rendrait moins
                    // clair, pas plus.
                    onError(e.userMessage(appContext, R.string.err_split_failed))
                }
            }
        }
    }

    /**
     * Copie un parcours sans ses points immobiles (voir
     * [TrackRepository.removeStationaryPoints]). Sur [kotlinx.coroutines.Dispatchers.IO]
     * comme le découpage : la lecture parcourt tous les points.
     */
    fun removeStationaryPoints(
        trackId: Long,
        minStopSeconds: Int,
        newName: String,
        deleteSource: Boolean,
        onSuccess: (TrackRepository.StationaryCleanup) -> Unit,
        onError: (String) -> Unit
    ) {
        DebugLog.i("Action", "Nettoyer #$trackId : arrêts de $minStopSeconds s et plus, supprimer l'original $deleteSource")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val result = repository.removeStationaryPoints(
                    sourceTrackId = trackId,
                    minStopSeconds = minStopSeconds,
                    newName = newName,
                    deleteSource = deleteSource
                )
                if (deleteSource) trimTrash()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    DebugLog.i("Action", "removeStationaryPoints : terminé")
                    onSuccess(result)
                }
            } catch (e: Exception) {
                DebugLog.e("Action", "removeStationaryPoints : échec", e)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onError(e.userMessage(appContext, R.string.err_clean_failed))
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
                val writer = Exporter.KmlWriter(
                    out,
                    repository.getPlacemarks(track.id),
                    com.example.util.TrackStylePreferences.getThicknessDp(appContext)
                )
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
        DebugLog.i("Action", "Exporter « ${track.name} » (#${track.id}) en $format vers $uri")
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
                    DebugLog.i("Action", "exportToUri : terminé")
                    onSuccess()
                }
            } catch (e: Exception) {
                DebugLog.e("Action", "exportToUri : échec", e)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onError(e.userMessage(context, R.string.err_export_failed))
                }
            }
        }
    }

    /**
     * Ouvre la destination en la vidant d'abord (mode « wt »).
     *
     * Le mode par défaut, « w », ne tronque pas le fichier chez certains fournisseurs
     * de documents : écraser un ancien export plus long laissait sa fin derrière le
     * nouveau contenu, et le GPX obtenu n'était plus lisible. Un fournisseur qui ne
     * connaîtrait pas « wt » retombe sur « w », qui vaut toujours mieux qu'un échec.
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
            com.example.util.DebugLog.e("Action", "exception rattrapée (TrackViewModel.kt:918)", e)
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
