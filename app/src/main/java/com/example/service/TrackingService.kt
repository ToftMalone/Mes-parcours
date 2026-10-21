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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.data.model.GpsStatus
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
import com.example.util.RecordingRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var repository: TrackRepository
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * GPS point processing, serialized on a single thread.
     *
     * Each position triggers a coroutine that updates shared accumulators
     * (distance, elevation, last position, discontinuity flag).
     * On a multi-thread pool, a batch of positions — LocationResult delivers
     * several at once — launched as many simultaneous coroutines that
     * trampled each other: distances computed against an arbitrary position,
     * lost increments. A single-thread executor guarantees arrival order.
     */
    private val pointExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pointScope = CoroutineScope(pointExecutor.asCoroutineDispatcher() + Job())

    /** Main thread only, like everything [applyState] touches. */
    private var timerJob: Job? = null

    /**
     * Mode of the current location request: true = economical (paused, app
     * left), false = full accuracy, null = no request. Main thread
     * only. Avoids requesting positions again when nothing has changed.
     */
    private var appliedLowPower: Boolean? = null

    /** Serializes the pause writes to the database, see [persistPauseState]. */
    private val pauseWriteMutex = Mutex()

    private val notificationManager: NotificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val powerManager: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /**
     * Last text actually displayed, so as not to republish the same one twice.
     *
     * Volatile: the function is called from the timer and from the point
     * processing thread. In the worst case, two threads still see it empty at the
     * same instant and each publish once — harmless, the display being
     * identical.
     */
    @Volatile
    private var lastNotificationText: String? = null

    /**
     * Monotonic clock of the last notification actually republished, to
     * bound the rate when the screen is off (see [updateStatsNotification]).
     * Volatile for the same reason as [lastNotificationText].
     */
    @Volatile
    private var lastNotifiedAtElapsedRealtime = 0L

    // Track state variables
    @Volatile
    private var trackId: Long = -1
    private var startTimeMs: Long = 0

    /**
     * Duration already acquired before the current timing segment: what the previous
     * periods counted, plus what a resumed track brought.
     */
    @Volatile
    private var elapsedBaseSeconds: Long = 0

    /** Monotonic mark of the start of the current segment, or 0 if the stopwatch is stopped. */
    @Volatile
    private var segmentStartedAtRealtime: Long = 0

    /**
     * Total duration of the recording, in seconds.
     *
     * Derived from a monotonic clock rather than incremented by a
     * `delay(1000)` loop: `delay` does not promise punctuality, and the lateness of each
     * turn added up. On an outing of several hours, the displayed duration
     * ended up noticeably below the time actually elapsed.
     *
     * `elapsedRealtime` and not `currentTimeMillis`: the system time can jump — network
     * time sync, manual change — and the duration would then leap.
     */
    private val elapsedSeconds: Long
        get() = if (segmentStartedAtRealtime == 0L) {
            elapsedBaseSeconds
        } else {
            elapsedBaseSeconds +
                    (android.os.SystemClock.elapsedRealtime() - segmentStartedAtRealtime) / 1000L
        }

    /**
     * Last retained position of the current segment, or null if the segment has just
     * started (start, exit from pause, resume of an existing track).
     *
     * This value alone decides discontinuity: a point with no
     * previous position opens a new segment and adds no distance.
     * The old isDiscontinuousNext flag was written from the main thread
     * (pause/resume) and consumed from the processing thread: a point already
     * in flight could consume it instead of the first point after the resume,
     * which would then end up joined by a straight line to the previous segment.
     */
    @Volatile
    private var lastLocation: Location? = null

    @Volatile
    private var isPaused = false

    /**
     * True as soon as a start or a resume has been launched in this service. Main
     * thread only. When false, a pause is addressed to a freshly created service
     * made to receive it — the previous one having been killed — which has nothing to pause.
     */
    private var hasSession = false
    private var isLocationUpdatesActive = false
    private var isServiceStopping = false
    private var simulationJob: kotlinx.coroutines.Job? = null
    
    // Stats accumulators
    private var totalDistanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var totalSpeedPoints = 0
    private var speedSumMps = 0.0

    /**
     * Altitude processing chain, entirely on [pointScope]: conversion
     * to sea level, smoothing, then elevation accumulation.
     */
    private lateinit var altitudeResolver: AltitudeResolver
    private val altitudeSmoother = AltitudeSmoother()
    private val elevation = ElevationAccumulator()

    /** Last known smoothed altitude, reused when a reading carries none. */
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

        // While paused, accuracy follows screen presence: full when looking at
        // the map, economical as soon as the app is left (see requestLocationUpdates).
        serviceScope.launch(Dispatchers.Main) {
            repository.isAppInForeground.collect {
                if (isPaused && isLocationUpdatesActive && !isServiceStopping) requestLocationUpdates()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart by the system after a memory eviction: START_STICKY relaunches
        // the service with a null intent. Without this case, no branch
        // applied — no foreground notification, no GPS, no timer. The
        // service restarted empty and the recording stopped silently, with
        // nothing telling the user, who believed their outing was still going.
        //
        // ACTION_RESTORE: same resume, requested by BootReceiver after a phone
        // reboot or an update. In both cases, the state picks up the one
        // stored in the database — pause included.
        if (intent?.action == null || intent.action == ACTION_RESTORE) {
            if (!RecordingRecovery.canRecordFromBackground(this)) {
                RecordingRecovery.notifyInterrupted(this)
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundServiceNotification()
            restoreAfterRestart()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_TRACK_NAME) ?: getString(R.string.track_untitled)
                val activityType = intent.getStringExtra(EXTRA_ACTIVITY_TYPE) ?: "Hiking"
                val existingTrackId = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
                
                // CRITICAL: Call startForeground synchronously AT start time to avoid OS timeout crash.
                startForegroundServiceNotification()
                
                startTracking(name, activityType, existingTrackId)
            }
            ACTION_STOP -> {
                stopTracking()
            }
            ACTION_PAUSE -> {
                if (hasSession) pauseTracking() else pauseOrphanTrack()
            }
            ACTION_RESUME -> {
                startForegroundServiceNotification()
                resumeTracking()
            }
        }
        return START_STICKY
    }

    private suspend fun attachToOrRestoreActiveTrack(name: String, activityType: String, existingTrackId: Long = -1L) {
        // Read before any suspension: a resume after a reboot may happen
        // paused, and the screen must not show “recording” while the read is under way.
        val restorePaused = isPaused
        // No previous point as long as the segment has not resumed: the first point
        // recorded will therefore open a new segment, with no joining line.
        lastLocation = null

        val activeTrack = if (existingTrackId > 0) {
            val t = repository.getTrackById(existingTrackId)
            if (t != null) {
                val updated = t.copy(isRecording = true, isPaused = false)
                repository.updateTrack(updated)
                updated
            } else null
        } else {
            // markPaused = false: the service resumes recording this very instant.
            // Leaving it true made the screen show “paused” while
            // positions were being recorded — the service and the repository contradicted each other.
            repository.checkForAndRestoreActiveTrack(markPaused = restorePaused)
        }

        if (activeTrack != null) {
            trackId = activeTrack.id
            startTimeMs = activeTrack.startTime
            // Only loads what is necessary: an imported track can have
            // several million points and would not fit in memory.
            val resume = repository.loadResumeState(activeTrack)
            val points = resume.points
            val stats = resume.stats

            elapsedBaseSeconds = stats.durationSec
            segmentStartedAtRealtime = 0L
            totalDistanceMeters = stats.distanceMeters
            maxSpeedMps = stats.maxSpeedMps
            // Elevation totals pick up where they were, but without an altitude
            // reference: the gap between the place left and the one where we resume
            // must not be counted as elevation traveled.
            elevation.restore(stats.elevationGain, stats.elevationLoss)
            altitudeSmoother.reset()
            lastKnownAltitude = null
            // The average continues instead of restarting from zero: see ResumeState.
            speedSumMps = resume.speedSumMps
            totalSpeedPoints = resume.speedSamples

            // lastLocation deliberately stays null. Filling it with the last
            // point of the resumed track counted the straight-line distance
            // between the old segment and the place where we resume.
        } else {
            trackId = repository.createNewTrack(name, activityType)
            startTimeMs = System.currentTimeMillis()
            elapsedBaseSeconds = 0
            segmentStartedAtRealtime = 0L
            totalDistanceMeters = 0.0
            maxSpeedMps = 0.0
            elevation.reset()
            altitudeSmoother.reset()
            lastKnownAltitude = null
            lastLocation = null
        }
    }

    private fun startTracking(name: String, activityType: String, existingTrackId: Long = -1L) {
        hasSession = true
        isServiceStopping = false
        isPaused = false
        lastNotificationText = null
        lastNotifiedAtElapsedRealtime = 0L

        // All the preparation goes through pointScope, the single thread that also processes
        // GPS points. It used to run on serviceScope while
        // positions were already arriving — the GPS was armed after 200 ms, while resuming a
        // track could take much longer. Two threads therefore wrote the same
        // fields: a distance just added was overwritten by the restored
        // value, and the altitude smoother — an ArrayDeque, which is not meant for
        // that — suffered a reset() on one side during an add() on the other.
        pointScope.launch {
            totalSpeedPoints = 0
            speedSumMps = 0.0

            attachToOrRestoreActiveTrack(name, activityType, existingTrackId)

            // The GPS is only armed once the preparation is finished: no point can
            // therefore be processed before the accumulators are in their starting
            // state. The short delay gives the system time to propagate the process's
            // foreground priority before the location request.
            delay(200L)
            withContext(Dispatchers.Main) { applyState() }
        }
    }

    /**
     * Aligns the GPS, the stopwatch, the screen and the database with [isPaused].
     *
     * **Main thread only**, like the pause / resume / stop commands that
     * [onStartCommand] receives. That is what orders them relative to each other: the stopwatch
     * used to be started from the points thread, after the preparation of a
     * resume. A pause requested during that preparation therefore went first, and the
     * stopwatch then restarted — it ran during the pause.
     */
    private fun applyState() {
        if (isServiceStopping || trackId <= 0) return
        repository.setRecordingPaused(isPaused)
        requestLocationUpdates()
        if (isPaused) {
            timerJob?.cancel()
            timerJob = null
            freezeElapsed()
        } else {
            startTimer()
        }
        persistPauseState()
        updateStatsNotification()
    }

    /**
     * Writes the pause to the database, so that it survives the process being killed.
     *
     * Each write rereads [isPaused] under the lock instead of carrying the value from
     * the moment it was requested: two writes launched back to back therefore
     * cannot finish out of order and leave the second-to-last state in the database.
     */
    private fun persistPauseState() {
        serviceScope.launch {
            pauseWriteMutex.withLock {
                val id = trackId
                if (id > 0) repository.setTrackPaused(id, isPaused)
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        if (segmentStartedAtRealtime == 0L) {
            segmentStartedAtRealtime = android.os.SystemClock.elapsedRealtime()
        }
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                // The timer publishes the duration itself. Leaving it to the
                // GPS points froze the displayed stopwatch as soon as the signal was lost,
                // while time kept passing.
                val stats = repository.liveStats.value
                val seconds = elapsedSeconds
                if (stats.durationSec != seconds) {
                    repository.updateLiveStats(stats.copy(durationSec = seconds))
                }
                updateStatsNotification()
            }
        }
    }

    /** Freezes the acquired duration: the stopwatch will restart from this value. */
    private fun freezeElapsed() {
        elapsedBaseSeconds = elapsedSeconds
        segmentStartedAtRealtime = 0L
    }

    private fun pauseTracking() {
        isPaused = true
        // Cuts the segment: on resume, no line will join the two pieces.
        lastLocation = null
        // Right away, even if the preparation is not finished: this is what the screen sees.
        repository.setRecordingPaused(true)
        applyState()
    }

    /**
     * Resume after a restart decided by the system, with no original intent.
     *
     * We only resume if a recording is really still open in the database. Calling
     * [resumeTracking] blindly would otherwise create an empty track: with no
     * active track to find, [attachToOrRestoreActiveTrack] opens a new one.
     */
    /**
     * Pause received with no recording in progress in this service: we store it on the
     * track left open in the database, so that no later resume restarts it,
     * then the service stops — it was not put in the foreground and has nothing to do.
     */
    private fun pauseOrphanTrack() {
        isPaused = true
        repository.setRecordingPaused(true)
        serviceScope.launch {
            repository.getActiveRecordingTrack()?.let { repository.setTrackPaused(it.id, true) }
            withContext(Dispatchers.Main) { if (!hasSession) stopSelf() }
        }
    }

    private fun restoreAfterRestart() {
        serviceScope.launch {
            val active = repository.getActiveRecordingTrack()
            withContext(Dispatchers.Main) {
                when {
                    isServiceStopping -> Unit
                    active == null -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    // The stored pause is respected: without it, the next trip
                    // was recorded while the user had paused.
                    active.isPaused -> restoreTracking(paused = true)
                    else -> restoreTracking(paused = false)
                }
            }
        }
    }

    private fun resumeTracking() = restoreTracking(paused = false)

    /** Resumes the recording, or reopens it paused after a restart. */
    private fun restoreTracking(paused: Boolean) {
        hasSession = true
        isPaused = paused
        repository.setRecordingPaused(paused)
        // New segment, with no joining to what precedes.
        lastLocation = null
        // On pointScope, for the same reason as at start: restoration touches
        // the accumulators that point processing keeps alive.
        pointScope.launch {
            if (trackId <= 0) {
                attachToOrRestoreActiveTrack(getString(R.string.track_untitled), "Hiking")
            }
            withContext(Dispatchers.Main) { applyState() }
        }
    }

    private fun removeLocationUpdatesSafely() {
        simulationJob?.cancel()
        simulationJob = null
        appliedLowPower = null
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

        // Coordinate simulation is a development tool: it fabricates
        // a fictitious circular track in Paris. It must NEVER be activated in a
        // published version, otherwise the app silently records invented data
        // that the user will take for their real trip. Emulator
        // detection is heuristic (it tests, for example, whether the product contains
        // "sdk") and may trigger wrongly on a real device.
        if (BuildConfig.DEBUG && EnvironmentUtils.isEmulatorOrCloud(this)) {
            if (simulationJob?.isActive != true) startGPSCoordinateSimulation()
            return
        }

        // No location provider active: we say so instead of inventing a trip.
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
        val isNetworkEnabled = try { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
        if (!isGpsEnabled && !isNetworkEnabled) {
            if (BuildConfig.DEBUG) {
                if (simulationJob?.isActive != true) startGPSCoordinateSimulation()
            } else {
                repository.updateGpsStatus(GpsStatus.DISABLED)
            }
            return
        }

        // While paused and off screen, nothing is recorded and nobody looks at the
        // map: the GPS chip was nevertheless running at full accuracy, one position per
        // second, throughout the pause. We now settle for an
        // approximate position every ten seconds, enough to keep the signal state up to date.
        val lowPower = isPaused && !repository.isAppInForeground.value
        if (isLocationUpdatesActive && appliedLowPower == lowPower) return

        val isFineGranted = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val locationRequest = if (lowPower) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, PAUSED_LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(PAUSED_LOCATION_INTERVAL_MS / 2)
                .build()
        } else {
            val priority = if (isFineGranted) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocationRequest.Builder(priority, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build()
        }

        try {
            // No prior removal: a new request on the same callback
            // replaces the previous one, with no gap in position reception.
            appliedLowPower = lowPower
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
            appliedLowPower = null
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
     * Converts the reading's altitude to sea level, smooths it and publishes it.
     * Returns the smoothed altitude, or null if the reading carried no usable one.
     *
     * To be called only from [pointScope]: the conversion reads geoid data.
     *
     * An isolated gap does not blank the display. A reading without altitude
     * generally means a momentary loss of the three-dimensional fix; clearing
     * the value each time would make the displayed number flicker. The last
     * real measurement therefore stays visible, and the GPS signal state is already shown next to it.
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
                repository.updateGpsStatus(GpsStatus.FOUND)
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

            // A point with no previous position opens a new segment. This covers
            // the very first point of a track, the exit from pause and the resume
            // of an existing track, with no flag shared between threads.
            val prevLoc = lastLocation
            val pointDiscontinuous = prevLoc == null

            // Altitude brought back to sea level then smoothed. This is the value that
            // is recorded, hence also the one that goes into GPX files — the
            // <ele> tag is supposed to be an altitude above the sea.
            val currAlt = publishAltitude(location)

            // The new segment also cuts the altitude series: the gap between
            // the place left and the one where we resume is not elevation traveled.
            // We hook onto the same signal as the track discontinuity, rather
            // than a flag written from the main thread.
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
                // 0.0 only if no altitude could ever be obtained.
                altitude = currAlt ?: lastKnownAltitude ?: 0.0,
                speed = currSpeed,
                timestamp = currTime,
                isDiscontinuous = pointDiscontinuous
            )
            repository.insertPoint(point)

            // Also update shared GPS/location stats in repository so UI updates in real-time
            repository.updateGpsStatus(GpsStatus.FOUND)
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

            val stats = LiveStats(
                durationSec = elapsedSeconds,
                distanceMeters = totalDistanceMeters,
                currentSpeedMps = speedMps,
                avgSpeedMps = avgSpeed,
                maxSpeedMps = maxSpeedMps,
                elevationGain = elevation.gainMeters,
                elevationLoss = elevation.lossMeters
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

        val pausePrefix = if (isPaused) getString(R.string.notif_paused_prefix) else ""
        val notificationString = pausePrefix + getString(R.string.notif_stats, formattedKm, formattedTime)

        // The timer and the arrival of a GPS point both call this function,
        // each about once per second: half of the updates
        // therefore rewrote an identical text, crossing the system for nothing.
        // On a four-hour outing, that made about fifteen thousand useless
        // round trips.
        if (notificationString == lastNotificationText) return

        // Screen off: nobody can see this notification at the moment it
        // changes, republishing it every second only consumes for nothing — the
        // defect flagged at the author's request. One beat per minute is enough to
        // keep it roughly up to date (for a screen wake-up, a preview from the
        // notification shade), without reproducing the continuous cost observed.
        //
        // `lastNotificationText` is deliberately not updated in this
        // branch: the text keeps differing at each call as long as it really
        // changes (seconds ticking), which makes this cheap test pass
        // again at each tick — only the call to notify(), the costly one, stays held back.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!powerManager.isInteractive &&
            now - lastNotifiedAtElapsedRealtime < SCREEN_OFF_NOTIFY_INTERVAL_MS
        ) {
            return
        }

        lastNotificationText = notificationString
        lastNotifiedAtElapsedRealtime = now
        notificationManager.notify(NOTIFICATION_ID, buildNotification(notificationString))
    }

    private fun stopTracking() {
        timerJob?.cancel()
        timerJob = null
        isServiceStopping = true
        removeLocationUpdatesSafely()
        freezeElapsed()

        serviceScope.launch {
            // trackId is -1 when this service has just been created to
            // receive this stop: the previous one had been killed with the process, and
            // the interface sends it ACTION_STOP all the same. Without this catch-up, the
            // track stayed marked “in progress” in the database forever and came back at
            // each launch as a phantom recording, impossible to close.
            val finishedTrackId = if (trackId != -1L) {
                trackId
            } else {
                repository.getActiveRecordingTrack()?.id ?: -1L
            }

            if (finishedTrackId != -1L) {
                val live = repository.liveStats.value
                // This service's stopwatch is only valid if it led the recording
                // itself. Otherwise, the statistics restored from the database
                // at app launch are the only ones that make sense.
                val stats = if (trackId != -1L) live.copy(durationSec = elapsedSeconds) else live
                repository.finishTracking(finishedTrackId, stats)
                com.example.util.AutoBackupManager.performAutoBackup(applicationContext, finishedTrackId)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = buildNotification(getString(R.string.notif_recording_starting))
        
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
     * Notification template, built only once.
     *
     * Nothing in it varies but the text: the title, the icon, the “Stop” action and above all
     * the two PendingIntents are identical from one end of the recording to the other.
     * Rebuilding them at each update — several thousand times per outing —
     * produced nothing but work.
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
            .setContentTitle(getString(R.string.notif_recording_title))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_stop_action),
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
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_description)
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
        // Without these cancellations, the coroutines launched by the service (including the timer
        // loop and the simulation) outlive its destruction.
        pointScope.cancel()
        serviceScope.cancel()
        pointExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "my_tracks_gps_tracking_channel"
        private const val NOTIFICATION_ID = 88231

        /**
         * Minimum republish rate of the notification when the screen is
         * off. See [updateStatsNotification].
         */
        private const val SCREEN_OFF_NOTIFY_INTERVAL_MS = 60_000L

        const val ACTION_START = "com.example.service.action.START"
        const val ACTION_STOP = "com.example.service.action.STOP"
        const val ACTION_PAUSE = "com.example.service.action.PAUSE"
        const val ACTION_RESUME = "com.example.service.action.RESUME"

        /** Resume without the app, in the state stored in the database: see BootReceiver. */
        const val ACTION_RESTORE = "com.example.service.action.RESTORE"

        /**
         * Rate of positions while paused, app left. Short enough that the
         * signal-loss alert (45 s off screen) does not trigger.
         */
        private const val PAUSED_LOCATION_INTERVAL_MS = 10_000L
        
        const val EXTRA_TRACK_NAME = "com.example.service.extra.TRACK_NAME"
        const val EXTRA_ACTIVITY_TYPE = "com.example.service.extra.ACTIVITY_TYPE"
        const val EXTRA_TRACK_ID = "com.example.service.extra.TRACK_ID"
    }
}
