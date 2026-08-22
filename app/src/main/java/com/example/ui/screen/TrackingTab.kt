package com.example.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.data.model.Track
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LiveStats
import com.example.ui.component.MapViewContainer
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils

enum class AlertState {
    LOST, FOUND
}

@Composable
fun TrackingTab(
    viewModel: TrackViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToDetails: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val isTracking by viewModel.isTracking.collectAsState()
    val liveStats by viewModel.liveStats.collectAsState()
    val livePoints by viewModel.livePoints.collectAsState()
    val currentTrackId by viewModel.currentTrackId.collectAsState()
    val selectedImportedPoints by viewModel.selectedImportedPoints.collectAsState()
    val bypassZoomThreshold by viewModel.bypassZoomThreshold.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()

    // Bouton d'enregistrement : au premier appui, propose « nouvelle trace » ou
    // « reprendre une trace existante » au lieu de démarrer directement.
    var showStartOptions by remember { mutableStateOf(false) }
    var showResumePicker by remember { mutableStateOf(false) }

    val sharedPrefs = remember { android.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    var mapModeState by remember { mutableStateOf(sharedPrefs.getString("pref_map_mode", "2d") ?: "2d") }

    // Floating recenter trigger
    var recenterTrigger by remember { mutableIntStateOf(0) }
    var isAutoFollowActive by remember { mutableStateOf(viewModel.isAutoFollowActiveMap) }

    LaunchedEffect(isAutoFollowActive) {
        viewModel.isAutoFollowActiveMap = isAutoFollowActive
    }

    // GPS Status Tracking for standby card from ViewModel
    val gpsStatus by viewModel.gpsStatus.collectAsState()
    val gpsAccuracy by viewModel.gpsAccuracy.collectAsState()
    val currentUserLocation by viewModel.currentUserLocation.collectAsState()
    val currentAltitude by viewModel.currentAltitude.collectAsState()
    val isAppInForeground by viewModel.isAppInForeground.collectAsState()

    var delayedGpsStatus by remember { mutableStateOf("Recherche de signal...") }
    var activeAlertState by remember { mutableStateOf<AlertState?>(null) }

    LaunchedEffect(isAppInForeground) {
        if (isAppInForeground) {
            activeAlertState = null
        }
    }

    LaunchedEffect(activeAlertState) {
        if (activeAlertState != null) {
            kotlinx.coroutines.delay(2000L)
            activeAlertState = null
        }
    }

    LaunchedEffect(gpsStatus) {
        if (!isAppInForeground) {
            delayedGpsStatus = gpsStatus
            return@LaunchedEffect
        }
        if (gpsStatus == "Signal trouvé") {
            val wasLost = delayedGpsStatus.startsWith("Recherche")
            delayedGpsStatus = "Signal trouvé"
            if (wasLost) {
                activeAlertState = AlertState.FOUND
            }
        } else if (gpsStatus.startsWith("Recherche")) {
            val wasFound = delayedGpsStatus == "Signal trouvé"
            // Wait 3 seconds to confirm GPS is truly lost
            kotlinx.coroutines.delay(3000L)
            if (gpsStatus.startsWith("Recherche")) {
                delayedGpsStatus = gpsStatus
                if (wasFound) {
                    activeAlertState = AlertState.LOST
                }
            }
        }
    }

    // Dynamic Pulsing Dot Animation for GPS Research Status
    val infiniteTransition = rememberInfiniteTransition(label = "GpsStatusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GpsDotAlpha"
    )

    if (!hasLocationPermission) {
        PermissionDeniedState {
            onRequestPermission()
        }
        return
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MapViewContainer(
            points = livePoints,
            modifier = Modifier.fillMaxSize(),
            isInteractivityEnabled = true,
            recenterTrigger = recenterTrigger,
            currentUserLocation = currentUserLocation,
            overlayTracks = selectedImportedPoints,
            isCurrentTracking = isTracking,
            zoomBannerTopPadding = 120.dp,
            isAutoFollowActive = isAutoFollowActive,
            onAutoFollowChanged = { isAutoFollowActive = it },
            initialCenterLat = viewModel.lastMapCenterLat,
            initialCenterLng = viewModel.lastMapCenterLng,
            initialZoom = viewModel.lastMapZoom,
            bypassZoomThreshold = bypassZoomThreshold,
            onBypassZoomThresholdChanged = { viewModel.setBypassZoomThreshold(it) },
            onMapStateChanged = { lat, lng, zoom ->
                viewModel.lastMapCenterLat = lat
                viewModel.lastMapCenterLng = lng
                viewModel.lastMapZoom = zoom
            },
            onViewportChanged = { viewModel.updateMapViewport(it) }
        )

        // Real-Time Stats Floating Panel (Neural Glass Card)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxWidth()
                .testTag("live_stats_panel"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Altitude déjà ramenée au niveau de la mer et lissée par le service.
            // Null tant qu'aucune mesure exploitable n'a été obtenue : l'affichage
            // montre alors « — » plutôt qu'un zéro trompeur.
            val currentAlt = currentAltitude?.metersAboveSeaLevel
            val currentSpeed = currentUserLocation?.speed?.toDouble() ?: liveStats.currentSpeedMps

            if (isTracking) {
                LiveStatsCard(
                    stats = liveStats,
                    currentAltitude = currentAlt,
                    currentSpeed = currentSpeed,
                    delayedGpsStatus = delayedGpsStatus,
                    pulseAlpha = pulseAlpha,
                    activeAlertState = activeAlertState
                )
            } else {
                StandbyStatsCard(
                    currentSpeedMps = currentSpeed,
                    currentAltitude = currentAlt,
                    delayedGpsStatus = delayedGpsStatus,
                    pulseAlpha = pulseAlpha,
                    activeAlertState = activeAlertState
                )
            }
        }

        // Bottom-Right Controls Column (Recenter + Pause/Resume + Record Play/Stop)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 96.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Recenter Camera Button
            if (currentUserLocation != null || livePoints.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        isAutoFollowActive = true
                        recenterTrigger++
                    },
                    containerColor = if (isAutoFollowActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    contentColor = if (isAutoFollowActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .size(56.dp)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
                        .testTag("recenter_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Recents",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (isTracking) {
                val isPaused by viewModel.isPaused.collectAsState()

                // Pause / Resume FAB
                FloatingActionButton(
                    onClick = {
                        if (isPaused) {
                            viewModel.resumeRecording(context)
                        } else {
                            viewModel.pauseRecording(context)
                        }
                    },
                    containerColor = if (isPaused) Color(0xFF10B981) else Color(0xFFF59E0B), // Emerald for resume, Amber for pause
                    contentColor = Color.White,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .size(56.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .testTag("pause_resume_fab")
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Reprendre" else "Pause",
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Stop FAB
                FloatingActionButton(
                    onClick = {
                        viewModel.stopRecording(context)
                        currentTrackId?.let { id ->
                            onNavigateToDetails(id)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .size(72.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.onError.copy(alpha = 0.3f), CircleShape)
                        .testTag("action_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Arrêter",
                        modifier = Modifier.size(36.dp)
                    )
                }
            } else if (showStartOptions) {
                // Annuler : referme les options sans rien démarrer
                FloatingActionButton(
                    onClick = { showStartOptions = false },
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), CircleShape)
                        .testTag("cancel_start_options_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Annuler",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Reprendre une trace existante : seulement s'il y en a une dans l'historique
                if (allTracks.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { showResumePicker = true },
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                        modifier = Modifier
                            .size(56.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
                            .testTag("resume_existing_track_fab")
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Reprendre une trace existante",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Nouvelle trace
                FloatingActionButton(
                    onClick = {
                        viewModel.startRecording(context, "Nouveau Parcours", "Parcours")
                        showStartOptions = false
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .size(72.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f), CircleShape)
                        .testTag("start_new_track_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Nouvelle trace",
                        modifier = Modifier.size(36.dp)
                    )
                }
            } else {
                // Play FAB : révèle les deux options seulement s'il y a une trace à
                // reprendre — sinon le choix n'existe pas vraiment, autant démarrer
                // directement comme avant.
                FloatingActionButton(
                    onClick = {
                        if (allTracks.isEmpty()) {
                            viewModel.startRecording(context, "Nouveau Parcours", "Parcours")
                        } else {
                            showStartOptions = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier
                        .size(72.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f), CircleShape)
                        .testTag("action_fab")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Enregistrer",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

    }

    if (showResumePicker) {
        ResumeTrackPickerDialog(
            tracks = allTracks,
            onDismiss = { showResumePicker = false },
            onTrackSelected = { track ->
                viewModel.resumeTrack(context, track.id) {
                    android.widget.Toast.makeText(
                        context,
                        "Reprise de la trace \"${track.name}\"",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                showResumePicker = false
                showStartOptions = false
            }
        )
    }
}

@Composable
private fun ResumeTrackPickerDialog(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    onTrackSelected: (Track) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reprendre une trace") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onTrackSelected(track) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("resume_picker_track_${track.id}")
                    ) {
                        Text(
                            text = track.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = FormatUtils.formatDate(track.startTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}


@Composable
fun PermissionDeniedState(onGrantClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.LocationOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(96.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Accès GPS Requis",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pour pouvoir enregistrer vos parcours et analyser vos statistiques de randonnée, vous devez autoriser l'accès GPS.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onGrantClick,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Accorder les permissions", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Anime le changement d'une statistique en direct (distance, vitesse, altitude) par
 * un défilement vertical façon compteur, plutôt qu'un saut sec à chaque nouveau point
 * GPS — c'est-à-dire environ une fois par seconde pendant l'enregistrement.
 *
 * Toujours le même sens de défilement, que la valeur monte ou descende. La distinguer
 * demanderait de réanalyser une chaîne déjà mise en forme (unité, virgule décimale)
 * pour un gain visuel marginal : le nombre a l'air vivant dans les deux cas.
 */
@Composable
private fun AnimatedStatValue(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(180))) togetherWith
                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
        },
        label = "stat_value",
        modifier = modifier
    ) { value ->
        Text(text = value, style = style)
    }
}

@Composable
fun LiveStatsCard(
    stats: LiveStats,
    currentAltitude: Double?,
    currentSpeed: Double,
    delayedGpsStatus: String,
    pulseAlpha: Float,
    activeAlertState: AlertState?
) {
    val showLostAlert = activeAlertState == AlertState.LOST
    val showFoundAlert = activeAlertState == AlertState.FOUND

    val animatedContainerColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            showLostAlert -> Color(0xFFEA580C)
            showFoundAlert -> Color(0xFF10B981)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
        label = "LiveStatsBgColor"
    )

    val animatedBorderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (showLostAlert || showFoundAlert) {
            Color.White.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
        label = "LiveStatsBorderColor"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = animatedContainerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(animatedBorderColor, Color.Transparent)
                ),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        androidx.compose.animation.Crossfade(
            targetState = (showLostAlert || showFoundAlert),
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
            label = "LiveStatsContentFade"
        ) { isAlert ->
            if (isAlert) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showLostAlert) Icons.Default.LocationOff else Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (showLostAlert) "Signal GPS perdu 😨" else "Signal GPS trouvé 😄",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Distance Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "DISTANCE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        AnimatedStatValue(
                            text = FormatUtils.formatDistance(stats.distanceMeters),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    // Separator 1
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )

                    // Current Speed Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "VITESSE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        AnimatedStatValue(
                            text = FormatUtils.formatSpeed(currentSpeed),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }

                    // Separator 2
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    )

                    // Current Altitude Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "ALTITUDE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedStatValue(
                                text = FormatUtils.formatElevationOrUnknown(currentAltitude),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.align(Alignment.Center)
                            )

                            // Integrated GPS connection status dot to the right of altitude
                            val isSignalFound = delayedGpsStatus == "Signal trouvé"
                            val dotColor = if (isSignalFound) Color(0xFF10B981) else Color(0xFFEF4444)
                            val dotAlpha = if (isSignalFound) 1.0f else pulseAlpha

                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor.copy(alpha = dotAlpha))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StandbyStatsCard(
    currentSpeedMps: Double,
    currentAltitude: Double?,
    delayedGpsStatus: String,
    pulseAlpha: Float,
    activeAlertState: AlertState?
) {
    val showLostAlert = activeAlertState == AlertState.LOST
    val showFoundAlert = activeAlertState == AlertState.FOUND

    val animatedContainerColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            showLostAlert -> Color(0xFFEA580C)
            showFoundAlert -> Color(0xFF10B981)
            else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        },
        animationSpec = tween(durationMillis = 400),
        label = "StandbyStatsBgColor"
    )

    val animatedBorderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (showLostAlert || showFoundAlert) {
            Color.White.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        },
        animationSpec = tween(durationMillis = 400),
        label = "StandbyStatsBorderColor"
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = animatedContainerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(animatedBorderColor, Color.Transparent)
                ),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        androidx.compose.animation.Crossfade(
            targetState = (showLostAlert || showFoundAlert),
            animationSpec = tween(durationMillis = 350),
            label = "StandbyStatsContentFade"
        ) { isAlert ->
            if (isAlert) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showLostAlert) Icons.Default.LocationOff else Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (showLostAlert) "Signal GPS perdu 😨" else "Signal GPS trouvé 😄",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Current Altitude Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ALTITUDE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = FormatUtils.formatElevationOrUnknown(currentAltitude),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.align(Alignment.Center)
                            )

                            // Integrated GPS connection status dot to the right of altitude
                            val isSignalFound = delayedGpsStatus == "Signal trouvé"
                            val dotColor = if (isSignalFound) Color(0xFF10B981) else Color(0xFFEF4444)
                            val dotAlpha = if (isSignalFound) 1.0f else pulseAlpha

                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 4.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor.copy(alpha = dotAlpha))
                            )
                        }
                    }
                }
            }
        }
    }
}



