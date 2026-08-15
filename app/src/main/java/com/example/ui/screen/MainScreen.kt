package com.example.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.viewmodel.TrackViewModel
import com.example.ui.screen.SettingsTab
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
import com.example.data.model.TrackPoint
import com.example.util.EnvironmentUtils

@Composable
fun MainScreen(
    viewModel: TrackViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isTracking by viewModel.isTracking.collectAsState()

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
    }

    LaunchedEffect(Unit) {
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

    // Chaîne d'altitude utilisée hors enregistrement — pendant un enregistrement,
    // c'est TrackingService qui s'en charge.
    val altitudeResolver = remember(context) { com.example.util.AltitudeResolver(context) }
    val altitudeSmoother = remember { com.example.util.AltitudeSmoother() }

    // Sérialisé sur un seul thread : le lisseur porte un état interne que deux relevés
    // traités en parallèle corrompraient. Même raison que pointExecutor dans
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

                    // La simulation fabrique un point bleu qui tourne dans Paris. C'est
                    // un outil de développement, et elle doit être gardée par
                    // BuildConfig.DEBUG comme celle de TrackingService (invariant 8) :
                    // la détection d'émulateur est heuristique — elle teste par exemple
                    // si Build.PRODUCT contient « sdk » — et se déclenche donc parfois
                    // sur un vrai appareil. En version publiée, l'utilisateur y verrait
                    // sa position remplacée par une position inventée.
                    if (BuildConfig.DEBUG && (EnvironmentUtils.isEmulatorOrCloud(context) || hasNoProvider)) {
                        viewModel.updateGpsStatus("Simulation GPS")
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

                    // Aucun fournisseur actif : on le signale, plutôt que d'afficher
                    // une position que l'appareil n'a pas.
                    if (hasNoProvider) {
                        viewModel.updateGpsStatus("Localisation désactivée")
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
                                viewModel.updateGpsStatus("Signal trouvé")
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

                                // Ce rappel arrive sur le thread principal, et la
                                // conversion vers le niveau de la mer lit les données
                                // de géoïde : elle doit se faire en arrière-plan.
                                altitudeScope.launch {
                                    val fix = altitudeResolver.resolve(location)
                                    if (fix != null) {
                                        val smoothed = altitudeSmoother.add(fix.metersAboveSeaLevel)
                                        viewModel.updateAltitude(fix.copy(metersAboveSeaLevel = smoothed))
                                    }
                                }
                            } else {
                                viewModel.updateGpsStatus("Recherche de signal...")
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

    var currentTab by remember { mutableStateOf("enregistrer") }
    var viewingDetailedTrackId by remember { mutableStateOf<Long?>(null) }

    var showEasterEgg by remember { mutableStateOf(false) }

    if (showEasterEgg) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(3000L)
            showEasterEgg = false
        }
    }

    LaunchedEffect(showEasterEgg) {
        val activity = context.findActivity()
        val window = activity?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            if (showEasterEgg) {
                insetsController.hide(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                )
                insetsController.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars()
                )
            }
        }
    }

    // Redirect to home page (enregistrer tab) on system back press if currently on other tabs
    if (viewingDetailedTrackId == null && currentTab != "enregistrer") {
        BackHandler {
            currentTab = "enregistrer"
        }
    }

    // Overriding Tab screen if inspecting detail view
    val detailId = viewingDetailedTrackId
    if (detailId != null) {
        DetailView(
            trackId = detailId,
            viewModel = viewModel,
            onBackClick = { viewingDetailedTrackId = null },
            onResumeTrack = {
                viewingDetailedTrackId = null
                currentTab = "enregistrer"
            },
            modifier = modifier
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (!showEasterEgg) {
                    NavigationBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        tonalElevation = 8.dp,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .height(72.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                ),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .testTag("bottom_nav_bar")
                    ) {
                NavigationBarItem(
                    selected = currentTab == "enregistrer",
                    onClick = { currentTab = "enregistrer" },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == "enregistrer") Icons.Filled.PlayArrow else Icons.Outlined.PlayArrow,
                            contentDescription = "Enregistrer"
                        )
                    },
                    label = { Text("Enregistrer") },
                    modifier = Modifier.testTag("tab_button_tracking")
                )

                NavigationBarItem(
                    selected = currentTab == "historique",
                    onClick = { currentTab = "historique" },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == "historique") Icons.Filled.History else Icons.Outlined.History,
                            contentDescription = "Historique"
                        )
                    },
                    label = { Text("Historique") },
                    modifier = Modifier.testTag("tab_button_history")
                )

                NavigationBarItem(
                    selected = currentTab == "outils",
                    onClick = { currentTab = "outils" },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == "outils") Icons.Filled.Build else Icons.Outlined.Build,
                            contentDescription = "Outils"
                        )
                    },
                    label = { Text("Outils") },
                    modifier = Modifier.testTag("tab_button_tools")
                )

                NavigationBarItem(
                    selected = currentTab == "parametres",
                    onClick = { currentTab = "parametres" },
                    icon = {
                        Icon(
                            imageVector = if (currentTab == "parametres") Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Paramètres"
                        )
                    },
                    label = { Text("Paramètres") },
                    modifier = Modifier.testTag("tab_button_settings")
                )
              }
            }
        },
        modifier = modifier.fillMaxSize().testTag("main_screen")
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            when (currentTab) {
                "enregistrer" -> {
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
                "historique" -> {
                    HistoryTab(
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onNavigateToDetails = { id ->
                            viewingDetailedTrackId = id
                        },
                        onResumeTrack = {
                            viewingDetailedTrackId = null
                            currentTab = "enregistrer"
                        }
                    )
                }
                "outils" -> {
                    ToolsTab(
                        viewModel = viewModel,
                        onNavigateToDetails = { id ->
                            viewingDetailedTrackId = id
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
                "parametres" -> {
                    SettingsTab(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onEasterEggTriggered = { showEasterEgg = true }
                    )
                }
            }
        }
    }

        // Recherche d'une nouvelle version au démarrage. Ne montre rien tant qu'il
        // n'y en a pas, et reste inerte tant qu'UpdateConfig n'est pas renseigné.
        UpdatePrompt()

        AnimatedVisibility(
            visible = showEasterEgg,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFEF4444))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { /* block all clicks */ }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Je t'aime sacré Pache 💓",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 32.sp
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        if (showBackgroundRationaleDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showBackgroundRationaleDialog = false },
                title = {
                    Text(
                        text = "Localisation 'Tout le temps' requise",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Pour enregistrer vos déplacements et activités de manière continue, même lorsque l'application est en arrière-plan ou que votre écran est éteint, « Mes parcours » a besoin de l'autorisation 'Autoriser tout le temps'.",
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showBackgroundRationaleDialog = false
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        }
                    ) {
                        Text("Autoriser tout le temps", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showBackgroundRationaleDialog = false }
                    ) {
                        Text("Plus tard")
                    }
                }
            )
        }
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context
        context = context.baseContext
    }
    return null
}
