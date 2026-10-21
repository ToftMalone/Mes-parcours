package com.example.ui.screen

import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.component.MainNavigationBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShareLocation
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import com.example.ui.component.AppMessageHost
import com.example.ui.component.AppMessenger
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MpDialog
import com.example.ui.component.ShapeBadge
import com.example.ui.viewmodel.TrackViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.example.BuildConfig
import com.example.data.model.GpsStatus
import com.example.data.model.TrackPoint
import com.example.util.EnvironmentUtils

@Composable
fun MainScreen(
    viewModel: TrackViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isTracking by viewModel.isTracking.collectAsState()

    // Welcome screen: only once, on the very first launch — see
    // OnboardingPreferences. As long as it has not been passed, nothing that follows (GPS
    // tracking, update search) must start: that is the role of the early
    // return further down.
    var onboardingCompleted by remember {
        mutableStateOf(com.example.util.OnboardingPreferences.isCompleted(context))
    }

    // Distinguishes “already passed before this launch” from “just finished
    // this instant”: without this snapshot taken once and for all, the automatic
    // re-request below would fire again right after the welcome screen's own request,
    // as soon as the user refused the permission there.
    val onboardingWasAlreadyCompletedAtStart = remember { onboardingCompleted }

    // Update detected by UpdatePrompt: kept here to show a persistent badge
    // in the settings once the banner has been dismissed, and to be able to reopen it
    // from there without restarting the app.
    var availableUpdate by remember { mutableStateOf<com.example.util.update.AvailableUpdate?>(null) }
    var updateReopenTrigger by remember { mutableStateOf(0) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasBackgroundPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var showBackgroundRationaleDialog by remember { mutableStateOf(false) }

    val backgroundPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasBackgroundPermission = isGranted
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val grantedForeground = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = grantedForeground

        if (grantedForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasBackgroundPermission = bgGranted
            if (!bgGranted) {
                showBackgroundRationaleDialog = true
            }
        }

        // It is this answer — granted or not — that closes the welcome screen:
        // the user's choice is made, they can see the app. They can
        // still grant the permission later from the Record tab.
        if (!onboardingCompleted) {
            com.example.util.OnboardingPreferences.setCompleted(context)
            onboardingCompleted = true
        }
    }

    // Automatically re-requests at launch as long as the permission is missing, as
    // before the welcome screen was added — but only for a user who had
    // already passed it on a previous launch. For a first launch, it is
    // WelcomeScreen that triggers the request, on an explicit and explained tap:
    // asking again here as soon as the welcome is closed would ask a second time,
    // without explanation, for the permission the user has just refused.
    LaunchedEffect(Unit) {
        if (!onboardingWasAlreadyCompletedAtStart) return@LaunchedEffect

        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!hasLocationPermission) {
            launcher.launch(permissionsToRequest.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundPermission) {
            showBackgroundRationaleDialog = true
        }
    }

    if (!onboardingCompleted) {
        WelcomeScreen(
            onRequestPermissions = {
                val permissionsToRequest = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                launcher.launch(permissionsToRequest.toTypedArray())
            },
            modifier = modifier
        )
        return
    }

    // Altitude chain used outside a recording — during a recording,
    // it is TrackingService that handles it.
    val altitudeResolver = remember(context) { com.example.util.AltitudeResolver(context) }
    val altitudeSmoother = remember { com.example.util.AltitudeSmoother() }

    // Serialized on a single thread: the smoother carries an internal state that two readings
    // processed in parallel would corrupt. Same reason as pointExecutor in
    // TrackingService.
    val altitudeExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    val altitudeScope = remember(altitudeExecutor) {
        kotlinx.coroutines.CoroutineScope(
            altitudeExecutor.asCoroutineDispatcher() + kotlinx.coroutines.SupervisorJob()
        )
    }
    DisposableEffect(altitudeExecutor) {
        onDispose {
            altitudeScope.cancel()
            altitudeExecutor.shutdown()
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, hasLocationPermission, isTracking) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        var callback: LocationCallback? = null
        var isLocationUpdatesActive = false
        var isStoppedOrDisposed = false
 
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                isStoppedOrDisposed = false
                if (hasLocationPermission && !isTracking && callback == null) {
                    val isFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    val isCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (!isFineLocation && !isCoarseLocation) {
                        return@LifecycleEventObserver
                    }

                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    val isGpsEnabled = try { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) } catch (e: Exception) { false }
                    val isNetworkEnabled = try { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { false }
                    val hasNoProvider = !isGpsEnabled && !isNetworkEnabled

                    // The simulation fabricates a blue dot circling in Paris. It is
                    // a development tool, and it must be guarded by
                    // BuildConfig.DEBUG like TrackingService's (invariant 8):
                    // emulator detection is heuristic — it tests, for example,
                    // whether Build.PRODUCT contains “sdk” — and therefore sometimes triggers
                    // on a real device. In a published version, the user would see
                    // their position replaced by an invented one.
                    if (BuildConfig.DEBUG && (EnvironmentUtils.isEmulatorOrCloud(context) || hasNoProvider)) {
                        viewModel.updateGpsStatus(GpsStatus.SIMULATION)
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                            var simLat = 48.8566
                            var simLng = 2.3522
                            var simAlt = 100.0
                            var angle = 0.0
                            while (!isStoppedOrDisposed) {
                                angle += 0.05
                                val speedKmh = 12.0 + (Math.random() * 3.0)
                                val speedMps = speedKmh / 3.6
                                simLat += 0.00012 * Math.cos(angle)
                                simLng += 0.00018 * Math.sin(angle)
                                simAlt += (Math.random() - 0.5) * 1.5

                                val locPoint = TrackPoint(
                                    trackId = -1,
                                    latitude = simLat,
                                    longitude = simLng,
                                    altitude = simAlt,
                                    speed = speedMps.toFloat(),
                                    timestamp = System.currentTimeMillis()
                                )
                                viewModel.updateUserLocation(locPoint)
                                kotlinx.coroutines.delay(2000L)
                            }
                        }
                        return@LifecycleEventObserver
                    }

                    // No provider active: we say so, rather than displaying
                    // a position the device does not have.
                    if (hasNoProvider) {
                        viewModel.updateGpsStatus(GpsStatus.DISABLED)
                        return@LifecycleEventObserver
                    }

                    val interval = 1000L
                    val minInterval = 500L
 
                    val priority = if (isFineLocation) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
 
                    val locationRequest = LocationRequest.Builder(priority, interval)
                        .setMinUpdateIntervalMillis(minInterval)
                        .build()
 
                    val cb = object : LocationCallback() {
                        override fun onLocationResult(locationResult: LocationResult) {
                            val location = locationResult.lastLocation
                            if (location != null) {
                                viewModel.updateGpsStatus(GpsStatus.FOUND)
                                viewModel.updateGpsAccuracy(location.accuracy)
                                val locPoint = TrackPoint(
                                    trackId = -1,
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    altitude = location.altitude,
                                    speed = location.speed,
                                    timestamp = location.time
                                )
                                viewModel.updateUserLocation(locPoint)

                                // This callback arrives on the main thread, and the
                                // conversion to sea level reads geoid data:
                                // it must be done in the background.
                                altitudeScope.launch {
                                    val fix = altitudeResolver.resolve(location)
                                    if (fix != null) {
                                        val smoothed = altitudeSmoother.add(fix.metersAboveSeaLevel)
                                        viewModel.updateAltitude(fix.copy(metersAboveSeaLevel = smoothed))
                                    }
                                }
                            } else {
                                viewModel.updateGpsStatus(GpsStatus.SEARCHING)
                            }
                        }
                    }
                    callback = cb
 
                    try {
                        fusedLocationClient.requestLocationUpdates(locationRequest, cb, android.os.Looper.getMainLooper())
                            .addOnSuccessListener {
                                if (!isStoppedOrDisposed) {
                                    isLocationUpdatesActive = true
                                } else {
                                    try {
                                        fusedLocationClient.removeLocationUpdates(cb)
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
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                isStoppedOrDisposed = true
                callback?.let {
                    if (isLocationUpdatesActive) {
                        try {
                            fusedLocationClient.removeLocationUpdates(it)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        isLocationUpdatesActive = false
                    }
                    callback = null
                }
            }
        }
 
        lifecycleOwner.lifecycle.addObserver(observer)
 
        onDispose {
            isStoppedOrDisposed = true
            lifecycleOwner.lifecycle.removeObserver(observer)
            callback?.let {
                if (isLocationUpdatesActive) {
                    try {
                        fusedLocationClient.removeLocationUpdates(it)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    isLocationUpdatesActive = false
                }
            }
        }
    }

    var currentTab by remember { mutableStateOf("record") }
    var viewingDetailedTrackId by remember { mutableStateOf<Long?>(null) }

    // Redirect to home page (record tab) on system back press if currently on other tabs
    if (viewingDetailedTrackId == null && currentTab != "record") {
        BackHandler {
            currentTab = "record"
        }
    }

    // The detail view covers the tabs, but without taking MainScreen out of
    // the composition: an early `return` took UpdatePrompt with it, which
    // then lost the state of the update already detected. Every round trip to
    // a track therefore relaunched a network request and reopened the banner
    // the user had just dismissed.
    val detailId = viewingDetailedTrackId
    var toolOpen by remember { mutableStateOf(false) }
    var settingsSubPageOpen by remember { mutableStateOf(false) }

    // Opaque background **mandatory** under the transition: without it, you see through.
    //
    // The theme inherits from `Theme.DeviceDefault`, whose window background is dark.
    // During a fade, both screens are half transparent at the same time, and this
    // background showed between them — hence the black veil reported when opening
    // a track as well as on return.
    // Brief messages at the bottom of the screen (import succeeded, export, errors…), shared by
    // all screens: see AppMessageHost.
    val messenger = remember { AppMessenger() }

    CompositionLocalProvider(LocalAppMessenger provides messenger) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Sliding between the tabs and the detail view, rather than a
        // dry switch. The direction of the slide follows navigation — the detail view enters from
        // the right like a screen being stacked, and leaves to the right as it is
        // unstacked — to stay readable at a glance rather than decorative.
        //
        // **No fade on these two branches, and that is deliberate.** Two opaque screens
        // sliding over each other never let anything show between them:
        // the arithmetic shows it, the outgoing screen recedes by a fifth of the width while
        // the incoming one travels a whole one, so they overlap from one end of the
        // movement to the other. Adding a fade on top did not make the
        // transition softer: it made it transparent, hence translucent over the
        // window background.
        AnimatedContent(
            targetState = detailId,
            transitionSpec = {
                if (targetState != null && initialState == null) {
                    slideInHorizontally(tween(220)) { it } togetherWith
                        slideOutHorizontally(tween(220)) { -it / 5 }
                } else if (targetState == null && initialState != null) {
                    slideInHorizontally(tween(220)) { -it / 5 } togetherWith
                        slideOutHorizontally(tween(220)) { it }
                } else {
                    // Direct move from one track to another: without a slide, a
                    // fade is needed. The opaque background laid down above serves as its
                    // canvas, so it cannot let the window show.
                    fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                }
            },
            label = "detail_transition",
            modifier = Modifier.fillMaxSize()
        ) { id ->
            if (id != null) {
                DetailView(
                    trackId = id,
                    viewModel = viewModel,
                    onBackClick = { viewingDetailedTrackId = null },
                    onResumeTrack = {
                        viewingDetailedTrackId = null
                        currentTab = "record"
                    },
                    modifier = modifier
                )
            } else {
                Scaffold(
                    bottomBar = {
                        // An open tool or settings page takes the whole screen.
                        if (!(currentTab == "tools" && toolOpen) && !(currentTab == "settings" && settingsSubPageOpen)) MainNavigationBar(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it },
                            showUpdateBadge = availableUpdate != null,
                            isRecording = isTracking
                        )
                    },
                modifier = modifier.fillMaxSize().testTag("main_screen")
            ) { innerPadding ->
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (currentTab) {
                        "record" -> {
                            TrackingTab(
                                viewModel = viewModel,
                                hasLocationPermission = hasLocationPermission,
                                onRequestPermission = {
                                    val permissionsToRequest = mutableListOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    if (!hasLocationPermission) {
                                        launcher.launch(permissionsToRequest.toTypedArray())
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundPermission) {
                                        showBackgroundRationaleDialog = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                onNavigateToDetails = { id ->
                                    viewingDetailedTrackId = id
                                }
                            )
                        }
                        "history" -> {
                            HistoryTab(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                                // The list scrolls under the floating bar: the screen
                                // manages its own margins rather than stopping dead
                                // above it.
                                contentPadding = innerPadding,
                                onOpenRecording = { currentTab = "record" },
                                onOpenTools = { currentTab = "tools" },
                                onNavigateToDetails = { id ->
                                    viewingDetailedTrackId = id
                                },
                                onResumeTrack = {
                                    viewingDetailedTrackId = null
                                    currentTab = "record"
                                }
                            )
                        }
                        "tools" -> {
                            ToolsTab(
                                viewModel = viewModel,
                                onNavigateToDetails = { id ->
                                    viewingDetailedTrackId = id
                                },
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = innerPadding,
                                onInToolChanged = { toolOpen = it },
                                onOpenHistory = { currentTab = "history" }
                            )
                        }
                        "settings" -> {
                            SettingsTab(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = innerPadding,
                                availableUpdate = availableUpdate,
                                onShowUpdate = { updateReopenTrigger++ },
                                onSubPageChanged = { settingsSubPageOpen = it }
                            )
                        }
                    }
                }
            }
            }
        }

        // Search for a new version at startup. Shows nothing as long as there is
        // none, and stays inert as long as UpdateConfig is not filled in.
        //
        // Composed permanently, including while a track is shown in
        // detail: that is what lets it keep the update already found.
        // Only its display is suspended, so as not to cover the detail view.
        UpdatePrompt(
            reopenTrigger = updateReopenTrigger,
            isVisible = detailId == null,
            onUpdateAvailable = { availableUpdate = it }
        )

        if (showBackgroundRationaleDialog && detailId == null) {
            MpDialog(
                onDismissRequest = { showBackgroundRationaleDialog = false },
                icon = {
                    ShapeBadge(
                        icon = Icons.Rounded.ShareLocation,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                },
                title = stringResource(R.string.bg_rationale_title),
                body = stringResource(R.string.bg_rationale_body),
                dismissLabel = stringResource(R.string.common_later),
                confirmLabel = stringResource(R.string.common_allow),
                onConfirm = {
                    showBackgroundRationaleDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
            )
        }

        // Above the navigation bar on the tabs; above the import button in
        // the history, which it must not hide; near the bottom in the
        // detail view, which has no bar.
        AppMessageHost(
            messenger = messenger,
            bottomPadding = when {
                detailId != null -> 24.dp
                currentTab == "history" -> 168.dp
                else -> 96.dp
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
    }
}
