package com.example.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.data.model.TrackPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
            } else {
                // Play FAB to start recording instantly without friction
                FloatingActionButton(
                    onClick = {
                        viewModel.startRecording(context, "Nouveau Parcours", "Parcours")
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
}

@Composable
fun SportRadarBackground() {
    val transition = rememberInfiniteTransition(label = "RadarSweep")
    
    // Smooth infinite angle rotation
    val angleSweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )

    // Animated glow pulse
    val scalePulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RadarPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(280.dp)) {
            val radius = size.minDimension / 2f

            // Outer ring
            drawCircle(
                color = Color(0xFF10B981),
                radius = radius * scalePulse,
                style = Stroke(width = 1.5f),
                alpha = 0.25f
            )

            // Inner ring
            drawCircle(
                color = Color(0xFF8B5CF6),
                radius = radius * 0.5f * scalePulse,
                style = Stroke(width = 1f),
                alpha = 0.2f
            )

            // Dynamic diagonal grid reference lines
            drawLine(
                color = Color.Gray,
                start = androidx.compose.ui.geometry.Offset(0f, center.y),
                end = androidx.compose.ui.geometry.Offset(size.width, center.y),
                strokeWidth = 1f,
                alpha = 0.15f
            )
            drawLine(
                color = Color.Gray,
                start = androidx.compose.ui.geometry.Offset(center.x, 0f),
                end = androidx.compose.ui.geometry.Offset(center.x, size.height),
                strokeWidth = 1f,
                alpha = 0.15f
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier
                    .size(80.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "GPS Prêt pour exploration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Enregistrez votre itinéraire en un seul geste",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
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
                        Text(
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
                        Text(
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

@Composable
fun StatColumn(
    icon: ImageVector,
    label: String,
    value: String
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun GpsStatusSquare(
    gpsStatus: String,
    gpsAccuracy: Float?,
    activeAlertState: AlertState?,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val isSignalFound = gpsStatus == "Signal trouvé"
    
    // Animate background color of the square based on activeAlertState
    val squareBgColor by androidx.compose.animation.animateColorAsState(
        targetValue = when (activeAlertState) {
            AlertState.LOST -> Color(0xFFEA580C)
            AlertState.FOUND -> Color(0xFF10B981)
            null -> MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
        },
        animationSpec = tween(durationMillis = 300),
        label = "GpsSquareBg"
    )

    // Animate scale of the square during alerts
    val alertScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (activeAlertState != null) 1.2f else 1.0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "GpsSquareScale"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd
    ) {
        // Expandable banner that slides out to the left
        androidx.compose.animation.AnimatedVisibility(
            visible = activeAlertState != null,
            enter = androidx.compose.animation.expandHorizontally(expandFrom = Alignment.End) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkHorizontally(shrinkTowards = Alignment.End) + androidx.compose.animation.fadeOut(),
            modifier = Modifier.padding(end = 32.dp)
        ) {
            val text = if (activeAlertState == AlertState.LOST) "Signal perdu 😨" else "Signal trouvé 😄"
            val alertBgColor = if (activeAlertState == AlertState.LOST) Color(0xFFEA580C) else Color(0xFF10B981)
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(alertBgColor)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .padding(start = 12.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // The actual Status Square (or tiny card)
        Card(
            colors = CardDefaults.cardColors(containerColor = squareBgColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer(
                    scaleX = alertScale,
                    scaleY = alertScale
                )
                .border(
                    width = 1.dp,
                    color = when (activeAlertState) {
                        AlertState.LOST -> Color.White.copy(alpha = 0.4f)
                        AlertState.FOUND -> Color.White.copy(alpha = 0.4f)
                        null -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(14.dp)
                )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (activeAlertState != null) {
                    val alertIcon = if (activeAlertState == AlertState.LOST) Icons.Default.LocationOff else Icons.Default.MyLocation
                    Icon(
                        imageVector = alertIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    // Normal state: red or green dot
                    val dotColor = if (isSignalFound) Color(0xFF10B981) else Color(0xFFEF4444)
                    val dotAlpha = if (isSignalFound) 1.0f else pulseAlpha
                    
                    if (!isSignalFound) {
                        // Ambient pulsing red circle below the dot
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444).copy(alpha = 0.15f * (1.0f - pulseAlpha)))
                        )
                    } else {
                        // Ambient green circle
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.12f))
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dotColor.copy(alpha = dotAlpha))
                    )
                }
            }
        }
    }
}

