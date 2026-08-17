package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.preference.PreferenceManager
import com.example.BuildConfig
import com.example.MainActivity
import com.example.data.model.LiveStats
import com.example.data.model.TrackPoint
import com.example.data.repository.TrackRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.example.util.AltitudeFix
import com.example.util.AltitudeResolver
import com.example.util.AltitudeSmoother
import com.example.util.ElevationAccumulator
import com.example.util.EnvironmentUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var repository: TrackRepository
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * Traitement des points GPS, sérialisé sur un seul thread.
     *
     * Chaque position déclenche une coroutine qui met à jour des accumulateurs
     * partagés (distance, dénivelé, dernière position, drapeau de discontinuité).
     * Sur un pool multi-thread, un lot de positions — LocationResult en livre
     * plusieurs d'un coup — lançait autant de coroutines simultanées qui se
     * marchaient dessus : distances calculées contre une position arbitraire,
     * incréments perdus. Un exécuteur à un seul thread garantit l'ordre d'arrivée.
     */
    private val pointExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pointScope = CoroutineScope(pointExecutor.asCoroutineDispatcher() + Job())

    private var timerJob: Job? = null

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Dernier texte réellement affiché, pour ne pas republier deux fois le même.
     *
     * Volatile : la fonction est appelée depuis le minuteur et depuis le thread de
     * traitement des points. Dans le pire des cas, deux fils la voient encore vide au
     * même instant et publient chacun une fois — sans conséquence, l'affichage étant
     * identique.
     */
    @Volatile
    private var lastNotificationText: String? = null

    // Track state variables
    @Volatile
    private var trackId: Long = -1
    private var startTimeMs: Long = 0
    private var elapsedSeconds: Long = 0

    /**
     * Dernière position retenue du tronçon en cours, ou null si le tronçon vient de
     * commencer (démarrage, sortie de pause, reprise d'une trace existante).
     *
     * C'est cette valeur seule qui décide de la discontinuité : un point sans
     * position précédente ouvre un nouveau tronçon et n'ajoute aucune distance.
     * L'ancien drapeau isDiscontinuousNext était écrit depuis le thread principal
     * (pause/reprise) et consommé depuis le thread de traitement : un point déjà
     * en vol pouvait le consommer à la place du premier point d'après la reprise,
     * qui se retrouvait alors relié en ligne droite au tronçon précédent.
     */
    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var isPaused = false
    private var isLocationUpdatesActive = false
    private var isServiceStopping = false
    private var simulationJob: kotlinx.coroutines.Job? = null
    
    // Stats accumulators
    private var totalDistanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var totalSpeedPoints = 0
    private var speedSumMps = 0.0

    /**
     * Chaîne de traitement de l'altitude, entièrement sur [pointScope] : conversion
     * vers le niveau de la mer, lissage, puis cumul du dénivelé.
     */
    private lateinit var altitudeResolver: AltitudeResolver
    private val altitudeSmoother = AltitudeSmoother()
    private val elevation = ElevationAccumulator()

    /** Dernière altitude lissée connue, réutilisée quand un relevé n'en porte pas. */
    private var lastKnownAltitude: Double? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val locations = locationResult.locations
            if (locations.isNotEmpty()) {
                for (location in locations) {
                    processNewLocation(location)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = TrackRepository.getInstance(applicationContext)
        altitudeResolver = AltitudeResolver(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_TRACK_NAME) ?: "Parcours sans titre"
                val activityType = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "Randonnée"
                val existingTrackId = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
                
                // CRITICAL: Call startForeground synchronously AT start time to avoid OS timeout crash.
                startForegroundServiceNotification()
                
                startTracking(name, activityType, existingTrackId)
            }
            ACTION_STOP -> {
                stopTracking()
            }
            ACTION_PAUSE -> {
                pauseTracking()
            }
            ACTION_RESUME -> {
                startForegroundServiceNotification()
                resumeTracking()
            }
        }
        return START_STICKY
    }

    private suspend fun attachToOrRestoreActiveTrack(name: String, activityType: String, existingTrackId: Long = -1L) {
        // Aucun point précédent tant que le tronçon n'a pas repris : le premier point
        // enregistré ouvrira donc un nouveau tronçon, sans trait de raccordement.
        lastLocation = null

        val activeTrack = if (existingTrackId > 0) {
            val t = repository.getTrackById(existingTrackId)
            if (t != null) {
                val updated = t.copy(isRecording = true)
                repository.updateTrack(updated)
                updated
            } else null
        } else {
            repository.checkForAndRestoreActiveTrack()
        }

        if (activeTrack != null) {
            trackId = activeTrack.id
            startTimeMs = activeTrack.startTime
            // Ne charge que ce qui est nécessaire : une trace importée peut compter
            // plusieurs millions de points et ne tiendrait pas en mémoire.
            val resume = repository.loadResumeState(activeTrack)
            val points = resume.points
            val stats = resume.stats

            elapsedSeconds = stats.durationSec
            totalDistanceMeters = stats.distanceMeters
            maxSpeedMps = stats.maxSpeedMps
            // Les totaux de dénivelé reprennent où ils étaient, mais sans référence
            // d'altitude : l'écart entre l'endroit quitté et celui où l'on reprend ne
            // doit pas être compté comme un dénivelé parcouru.
            elevation.restore(stats.elevationGain, stats.elevationLoss)
            altitudeSmoother.reset()
            lastKnownAltitude = null

            // lastLocation reste volontairement null. La renseigner avec le dernier
            // point de la trace reprise faisait compter la distance à vol d'oiseau
            // entre l'ancien tronçon et l'endroit où l'on reprend.
        } else {
            trackId = repository.createNewTrack(name, activityType)
            startTimeMs = System.currentTimeMillis()
            elapsedSeconds = 0
            totalDistanceMeters = 0.0
            maxSpeedMps = 0.0
            elevation.reset()
            altitudeSmoother.reset()
            lastKnownAltitude = null
            lastLocation = null
        }
    }

    private fun startTracking(name: String, activityType: String, existingTrackId: Long = -1L) {
        // Start Foreground Location Request after a very small delay to allow the System Server's
        // ActivityManager and AppOps services to fully bind and propagate our foreground-location process priority.
        serviceScope.launch {
            kotlinx.coroutines.delay(200L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                requestLocationUpdates()
            }
        }

        serviceScope.launch {
            // Reset track stats
            totalSpeedPoints = 0
            speedSumMps = 0.0
            isServiceStopping = false
            isPaused = false
            lastNotificationText = null

            attachToOrRestoreActiveTrack(name, activityType, existingTrackId)

            startTimer()

            // Update Foreground Notification with initial track content
            updateStatsNotification()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                elapsedSeconds++
                updateStatsNotification()
            }
        }
    }

    private fun pauseTracking() {
        isPaused = true
        // Coupe le tronçon : à la reprise, aucun trait ne reliera les deux morceaux.
        lastLocation = null
        repository.setRecordingPaused(true)
        timerJob?.cancel()
        updateStatsNotification()
    }

    private fun resumeTracking() {
        isPaused = false
        repository.setRecordingPaused(false)
        // Nouveau tronçon, sans raccordement avec ce qui précède.
        lastLocation = null
        serviceScope.launch {
            if (trackId <= 0) {
                attachToOrRestoreActiveTrack("Parcours sans titre", "Randonnée")
            }
            if (!isLocationUpdatesActive) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    requestLocationUpdates()
                }
            }
            startTimer()
            updateStatsNotification()
        }
    }

    private fun removeLocationUpdatesSafely() {
        simulationJob?.cancel()
        simulationJob = null
        if (isLocationUpdatesActive) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isLocationUpdatesActive = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        // La simulation de coordonnées est un outil de développement : elle fabrique
        // un parcours circulaire fictif à Paris. Elle ne doit JAMAIS s'activer dans une
        // version publiée, sinon l'application enregistre en silence des données
        // inventées que l'utilisateur croira être son trajet réel. La détection
        // d'émulateur est heuristique (elle teste par exemple si le produit contient
        // "sdk") et peut se déclencher à tort sur un vrai appareil.
        if (BuildConfig.DEBUG && EnvironmentUtils.isEmulatorOrCloud(this)) {
            startGPSCoordinateSimulation()
            return
        }

        // Aucun fournisseur de position actif : on le signale au lieu d'inventer un trajet.
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        val isNetworkEnabled = try { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
        if (!isGpsEnabled && !isNetworkEnabled) {
            if (BuildConfig.DEBUG) {
                startGPSCoordinateSimulation()
            } else {
                repository.updateGpsStatus("Localisation désactivée")
            }
            return
        }

        val interval = 1000L
        val minInterval = 500L

        val isFineGranted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val priority = if (isFineGranted) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(minInterval)
            .build()

        try {
            removeLocationUpdatesSafely()
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            ).addOnSuccessListener {
                if (!isServiceStopping) {
                    isLocationUpdatesActive = true
                } else {
                    try {
                        fusedLocationClient.removeLocationUpdates(locationCallback)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            isLocationUpdatesActive = false
            e.printStackTrace()
        }
    }

    private fun startGPSCoordinateSimulation() {
        simulationJob?.cancel()
        isLocationUpdatesActive = true
        simulationJob = serviceScope.launch {
            var simLatitude = 48.8566
            var simLongitude = 2.3522
            var simAltitude = 100.0
            var angle = 0.0
            
            while (isLocationUpdatesActive && !isServiceStopping) {
                kotlinx.coroutines.delay(2000L) // generate simulated location every 2 seconds
                
                // Simulate a circular cycling trajectory
                angle += 0.05
                val speedKmh = 12.0 + (Math.random() * 3.0)
                val speedMps = speedKmh / 3.6
                
                simLatitude += 0.00012 * Math.cos(angle)
                simLongitude += 0.00018 * Math.sin(angle)
                simAltitude += (Math.random() - 0.5) * 1.5
                
                val location = Location("simulated").apply {
                    latitude = simLatitude
                    longitude = simLongitude
                    altitude = simAltitude
                    speed = speedMps.toFloat()
                    accuracy = 3.5f
                    time = System.currentTimeMillis()
                }
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    processNewLocation(location)
                }
            }
        }
    }

    /**
     * Convertit l'altitude du relevé vers le niveau de la mer, la lisse et la publie.
     * Renvoie l'altitude lissée, ou null si le relevé n'en portait pas d'exploitable.
     *
     * À n'appeler que depuis [pointScope] : la conversion lit les données de géoïde.
     *
     * Une lacune isolée ne remet pas l'affichage à blanc. Un relevé sans altitude
     * signifie généralement une perte momentanée du calage tridimensionnel ; effacer
     * la valeur à chaque fois ferait clignoter le nombre affiché. La dernière mesure
     * réelle reste donc visible, et l'état du signal GPS est déjà signalé à côté.
     */
    private fun publishAltitude(location: Location): Double? {
        val fix = altitudeResolver.resolve(location) ?: return null
        val smoothed = altitudeSmoother.add(fix.metersAboveSeaLevel)
        lastKnownAltitude = smoothed
        repository.updateAltitude(AltitudeFix(smoothed, fix.accuracyMeters))
        return smoothed
    }

    private fun processNewLocation(location: Location) {
        if (isPaused) {
            pointScope.launch {
                val currSpeed = if (location.hasSpeed()) location.speed else 0f
                publishAltitude(location)
                val point = TrackPoint(
                    trackId = trackId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = lastKnownAltitude ?: 0.0,
                    speed = currSpeed,
                    timestamp = location.time,
                    isDiscontinuous = false
                )
                repository.updateGpsStatus("Signal trouvé")
                repository.updateGpsAccuracy(location.accuracy)
                repository.updateUserLocation(point)

                val currentStats = repository.liveStats.value
                repository.updateLiveStats(currentStats.copy(currentSpeedMps = currSpeed.toDouble()))
            }
            return
        }
        pointScope.launch {
            // Ignore if trackId is not yet initialized to prevent FOREIGN KEY constraint crash
            if (trackId <= 0) return@launch

            // Ignore inaccurate points (only filter if accuracy is explicitly reported as greater than 65 meters)
            if (location.hasAccuracy() && location.accuracy > 65.0f) return@launch

            val currLat = location.latitude
            val currLng = location.longitude
            val currSpeed = if (location.hasSpeed()) location.speed else 0f
            val currTime = location.time

            // Un point sans position précédente ouvre un nouveau tronçon. Cela couvre
            // le tout premier point d'une trace, la sortie de pause et la reprise
            // d'une trace existante, sans drapeau partagé entre threads.
            val prevLoc = lastLocation
            val pointDiscontinuous = prevLoc == null

            // Altitude ramenée au niveau de la mer puis lissée. C'est cette valeur qui
            // est enregistrée, donc aussi celle qui part dans les fichiers GPX — la
            // balise <ele> est censée être une altitude au-dessus de la mer.
            val currAlt = publishAltitude(location)

            // Le nouveau tronçon coupe aussi la série d'altitudes : l'écart entre
            // l'endroit quitté et celui où l'on reprend n'est pas un dénivelé parcouru.
            // On se raccroche au même signal que la discontinuité du tracé, plutôt
            // qu'à un drapeau écrit depuis le thread principal.
            if (pointDiscontinuous) {
                elevation.breakSegment()
            }
            if (currAlt != null) {
                elevation.add(currAlt)
            }

            // Record trackpoint
            val point = TrackPoint(
                trackId = trackId,
                latitude = currLat,
                longitude = currLng,
                // 0.0 seulement si aucune altitude n'a jamais pu être obtenue.
                altitude = currAlt ?: lastKnownAltitude ?: 0.0,
                speed = currSpeed,
                timestamp = currTime,
                isDiscontinuous = pointDiscontinuous
            )
            repository.insertPoint(point)

            // Also update shared GPS/location stats in repository so UI updates in real-time
            repository.updateGpsStatus("Signal trouvé")
            repository.updateGpsAccuracy(location.accuracy)
            repository.updateUserLocation(point)

            if (prevLoc != null) {
                // Compute distance
                val distanceResult = FloatArray(1)
                Location.distanceBetween(
                    prevLoc.latitude,
                    prevLoc.longitude,
                    currLat,
                    currLng,
                    distanceResult
                )
                val dist = distanceResult[0].toDouble()
                if (dist > 1.0) { // filter out GPS micro-drift
                    totalDistanceMeters += dist
                }
            }

            // Updates max speed
            val speedMps = currSpeed.toDouble()
            if (speedMps > maxSpeedMps) {
                maxSpeedMps = speedMps
            }

            // Track speeds for average
            if (speedMps > 0.1) {
                speedSumMps += speedMps
                totalSpeedPoints++
            }

            lastLocation = location

            // Calculate live statistics
            val avgSpeed = if (totalSpeedPoints > 0) speedSumMps / totalSpeedPoints else 0.0
            
            // Pace (min/km)
            val paceMinPerKm = if (avgSpeed > 0.1) {
                // speed is m/s
                // 1 km takes 1000 / avgSpeed seconds
                // / 60 = minutes
                (1000.0 / avgSpeed) / 60.0
            } else {
                0.0
            }

            val stats = LiveStats(
                durationSec = elapsedSeconds,
                distanceMeters = totalDistanceMeters,
                currentSpeedMps = speedMps,
                avgSpeedMps = avgSpeed,
                maxSpeedMps = maxSpeedMps,
                elevationGain = elevation.gainMeters,
                elevationLoss = elevation.lossMeters,
                paceMinPerKm = paceMinPerKm
            )

            repository.updateLiveStats(stats)
            updateStatsNotification()
        }
    }

    private fun updateStatsNotification() {
        val stats = repository.liveStats.value
        val km = stats.distanceMeters / 1000.0
        val formattedKm = String.format("%.2f km", km)
        
        val hours = stats.durationSec / 3600
        val minutes = (stats.durationSec % 3600) / 60
        val seconds = stats.durationSec % 60
        val formattedTime = if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }

        val notificationString = "Distance: $formattedKm | Temps: $formattedTime"

        // Le minuteur et l'arrivée d'un point GPS appellent tous deux cette fonction,
        // chacun à environ une fois par seconde : la moitié des mises à jour
        // réécrivait donc un texte identique, en traversant le système pour rien.
        // Sur une sortie de quatre heures, cela faisait une quinzaine de milliers
        // d'allers-retours inutiles.
        if (notificationString == lastNotificationText) return
        lastNotificationText = notificationString

        notificationManager.notify(NOTIFICATION_ID, buildNotification(notificationString))
    }

    private fun stopTracking() {
        timerJob?.cancel()
        isServiceStopping = true
        removeLocationUpdatesSafely()

        serviceScope.launch {
            if (trackId != -1L) {
                val stats = repository.liveStats.value
                val finishedTrackId = trackId
                repository.finishTracking(finishedTrackId, stats)
                com.example.util.AutoBackupManager.performAutoBackup(applicationContext, finishedTrackId)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification("Enregistrement démarré...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                // Fallback to standard foreground service without type
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    /**
     * Gabarit de la notification, construit une seule fois.
     *
     * Rien n'y varie que le texte : le titre, l'icône, l'action « Arrêter » et surtout
     * les deux PendingIntent sont identiques d'un bout à l'autre de l'enregistrement.
     * Les refabriquer à chaque mise à jour — plusieurs milliers de fois par sortie —
     * ne produisait que du travail.
     */
    private val notificationBuilder: NotificationCompat.Builder by lazy {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mes parcours : Enregistrement actif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Arrêter l'enregistrement",
                stopPendingIntent
            )
            .setOnlyAlertOnce(true)
    }

    private fun buildNotification(contentText: String): Notification {
        val notification = notificationBuilder.setContentText(contentText).build()
        notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mes parcours : Service GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification persistante de suivi de trajet GPS"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        timerJob?.cancel()
        isServiceStopping = true
        removeLocationUpdatesSafely()
        // Sans ces annulations, les coroutines lancées par le service (dont la boucle
        // du minuteur et la simulation) survivent à sa destruction.
        pointScope.cancel()
        serviceScope.cancel()
        pointExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "my_tracks_gps_tracking_channel"
        private const val NOTIFICATION_ID = 88231
        
        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val ACTION_PAUSE = "com.example.service.action.PAUSE"
        const val ACTION_RESUME = "com.example.service.action.RESUME"
        
        const val EXTRA_TRACK_NAME = "com.example.service.extra.TRACK_NAME"
        const val EXTRA_ACTIVITY_TYPE = "com.example.service.extra.ACTIVITY_TYPE"
        const val EXTRA_TRACK_ID = "com.example.service.extra.TRACK_ID"
    }
}
