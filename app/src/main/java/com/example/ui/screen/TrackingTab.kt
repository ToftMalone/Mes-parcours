@file:Suppress("DEPRECATION")

package com.example.ui.screen

import android.preference.PreferenceManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationDisabled
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LiveStats
import com.example.data.model.Track
import com.example.ui.component.CookieShape
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MapViewContainer
import com.example.ui.component.MpSheet
import com.example.ui.component.SheetHeader
import com.example.ui.component.ShapeBadge
import com.example.ui.component.TrackTile
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.LocalRecordingColor
import com.example.ui.theme.StatXlTextStyle
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences
import java.util.Locale

enum class AlertState {
    LOST, FOUND
}

/** État des commandes du bas : chacun redessine le même bouton principal. */
private enum class ControlsMode { IDLE, CHOICE, RECORDING, PAUSED }

/**
 * Écran « Enregistrer » : la carte plein écran, et par-dessus, comme sur la maquette,
 * la pastille d'état du GPS, le bouton de fond de carte, la carte de statistiques et
 * le bouton principal qui se transforme d'un état à l'autre (Démarrer → choix →
 * Pause/Arrêter → Reprendre/Arrêter).
 */
@Composable
fun TrackingTab(
    viewModel: TrackViewModel,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToDetails: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val isTracking by viewModel.isTracking.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val liveStats by viewModel.liveStats.collectAsState()
    val livePoints by viewModel.livePoints.collectAsState()
    val currentTrackId by viewModel.currentTrackId.collectAsState()
    val selectedImportedPoints by viewModel.selectedImportedPoints.collectAsState()
    val bypassZoomThreshold by viewModel.bypassZoomThreshold.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()

    // Démarrer : au premier appui, propose « nouveau parcours » ou « reprendre une
    // trace » au lieu de démarrer directement — s'il existe une trace à reprendre.
    var showStartOptions by remember { mutableStateOf(false) }
    var showResumePicker by remember { mutableStateOf(false) }

    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    // Fond de carte, basculé par le bouton « calques » et mémorisé comme le réglage.
    var tileStyle by remember { mutableStateOf(prefs.getString("pref_map_style", "mapnik") ?: "mapnik") }

    var recenterTrigger by remember { mutableIntStateOf(0) }
    var isAutoFollowActive by remember { mutableStateOf(viewModel.isAutoFollowActiveMap) }
    LaunchedEffect(isAutoFollowActive) {
        viewModel.isAutoFollowActiveMap = isAutoFollowActive
    }

    val gpsStatus by viewModel.gpsStatus.collectAsState()
    val gpsAccuracy by viewModel.gpsAccuracy.collectAsState()
    val currentUserLocation by viewModel.currentUserLocation.collectAsState()
    val currentAltitude by viewModel.currentAltitude.collectAsState()
    val isAppInForeground by viewModel.isAppInForeground.collectAsState()

    // Part de l'état courant : sinon chaque arrivée sur l'onglet, signal déjà
    // acquis, annonçait « Signal GPS trouvé » comme s'il venait de revenir.
    var delayedGpsStatus by remember { mutableStateOf(gpsStatus) }
    var activeAlertState by remember { mutableStateOf<AlertState?>(null) }

    LaunchedEffect(isAppInForeground) {
        if (isAppInForeground) {
            activeAlertState = null
        }
    }

    LaunchedEffect(activeAlertState) {
        if (activeAlertState != null) {
            kotlinx.coroutines.delay(2500L)
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
            // Trois secondes de confirmation avant d'annoncer une perte : un passage
            // sous un pont ne mérite pas une alerte.
            kotlinx.coroutines.delay(3000L)
            if (gpsStatus.startsWith("Recherche")) {
                delayedGpsStatus = gpsStatus
                if (wasFound) {
                    activeAlertState = AlertState.LOST
                }
            }
        }
    }

    val mode = when {
        isTracking && isPaused -> ControlsMode.PAUSED
        isTracking -> ControlsMode.RECORDING
        showStartOptions -> ControlsMode.CHOICE
        else -> ControlsMode.IDLE
    }
    val signalFound = delayedGpsStatus == "Signal trouvé"
    val currentAlt = currentAltitude?.metersAboveSeaLevel
    val currentSpeed = currentUserLocation?.speed?.toDouble() ?: liveStats.currentSpeedMps
    val trackName = allTracks.firstOrNull { it.id == currentTrackId }?.name ?: "Nouveau parcours"

    val startNew = {
        viewModel.startRecording(context, "Nouveau parcours", "Parcours")
        showStartOptions = false
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
            zoomBannerTopPadding = 72.dp,
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
            onViewportChanged = { viewModel.updateMapViewport(it) },
            tileStyle = tileStyle
        )

        // --- Haut de l'écran : pastille GPS (ou alerte), bouton de fond de carte.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
        ) {
            when {
                !hasLocationPermission -> GpsChip(
                    icon = Icons.Rounded.LocationDisabled,
                    text = "Localisation désactivée",
                    modifier = Modifier.alpha(0.5f)
                )
                activeAlertState == AlertState.LOST -> GpsAlert(
                    lost = true,
                    subtitle = if (isTracking) "L'enregistrement reprendra dès son retour" else "Position en attente du signal"
                )
                activeAlertState == AlertState.FOUND -> GpsAlert(
                    lost = false,
                    subtitle = gpsAccuracy?.let { "Précision ±${it.toInt()} m" } ?: "Position retrouvée"
                )
                !signalFound -> SearchingChip()
                else -> GpsChip(
                    icon = Icons.Rounded.GpsFixed,
                    text = if (isTracking && gpsAccuracy != null) "GPS · ±${gpsAccuracy!!.toInt()} m" else "Signal trouvé"
                )
            }
        }
        if (hasLocationPermission) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 72.dp, end = 16.dp)
                    .size(48.dp)
                    .shadow(6.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable {
                        tileStyle = if (tileStyle == "usgs_sat") "mapnik" else "usgs_sat"
                        prefs.edit().putString("pref_map_style", tileStyle).apply()
                    }
                    .testTag("map_layers_button")
            ) {
                Icon(Icons.Rounded.Layers, contentDescription = "Changer de fond de carte")
            }
        }

        // --- Bas de l'écran : recentrage, statistiques, commandes.
        if (hasLocationPermission) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 100.dp)
            ) {
                val canRecenter = currentUserLocation != null || livePoints.isNotEmpty()
                if (!isAutoFollowActive && canRecenter && mode != ControlsMode.CHOICE) {
                    Box(modifier = Modifier.fillMaxWidth().padding(end = 16.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .height(56.dp)
                                .shadow(8.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable {
                                    isAutoFollowActive = true
                                    recenterTrigger++
                                }
                                .padding(start = 16.dp, end = 20.dp)
                                .testTag("recenter_button")
                        ) {
                            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Recentrer", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }

                // La carte de statistiques s'efface pendant le choix de démarrage, que le
                // panneau de choix recouvre. Effacement franc, sans fondu : la carte porte
                // une ombre (voir « L'ombre tranchée » dans CLAUDE.md).
                if (mode != ControlsMode.CHOICE) {
                    StatsCard(
                        mode = mode,
                        stats = liveStats,
                        trackName = trackName,
                        currentSpeedMps = currentSpeed,
                        currentAltitude = currentAlt,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                RecordingControls(
                    mode = mode,
                    onStart = {
                        if (allTracks.isEmpty()) startNew() else showStartOptions = true
                    },
                    onNewTrack = startNew,
                    onResumeExisting = { showResumePicker = true },
                    onCancelChoice = { showStartOptions = false },
                    onPauseResume = {
                        if (isPaused) viewModel.resumeRecording(context) else viewModel.pauseRecording(context)
                    },
                    onStop = {
                        viewModel.stopRecording(context)
                        currentTrackId?.let { id -> onNavigateToDetails(id) }
                    },
                    canResumeExisting = allTracks.isNotEmpty()
                )
            }
        } else {
            PermissionDeniedOverlay(onGrantClick = onRequestPermission)
        }
    }

    if (showResumePicker) {
        ResumeTrackPickerSheet(
            tracks = allTracks,
            onDismiss = { showResumePicker = false },
            onTrackSelected = { track ->
                viewModel.resumeTrack(context, track.id) {
                    messenger.show("Reprise de « ${track.name} »", Icons.Rounded.PlayArrow)
                }
                showResumePicker = false
                showStartOptions = false
            }
        )
    }
}

// ------------------------------------------------------------------ Pastilles

@Composable
private fun GpsChip(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .height(48.dp)
            .shadow(6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 14.dp, end = 18.dp)
            .testTag("gps_status_chip")
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Recherche du signal : un point qui pulse. L'animation infinie ne vit que tant que
 * cette pastille est affichée — une horloge d'animation permanente empêcherait le
 * processeur de se reposer (voir « Audit batterie de la 1.2 »).
 */
@Composable
private fun SearchingChip() {
    val pulse = rememberInfiniteTransition(label = "gps_search")
    val scale by pulse.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "gps_search_scale"
    )
    val tertiary = MaterialTheme.colorScheme.tertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .height(48.dp)
            .shadow(6.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 14.dp, end = 18.dp)
            .testTag("gps_status_chip")
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 1.2f - scale }
                    .clip(CircleShape)
                    .background(tertiary)
            )
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(tertiary))
        }
        Text("Recherche du signal…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GpsAlert(lost: Boolean, subtitle: String) {
    val colors = MaterialTheme.colorScheme
    val bg = if (lost) colors.error else colors.primary
    val fg = if (lost) colors.onError else colors.onPrimary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(8.dp, RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .padding(horizontal = 18.dp)
            .testTag(if (lost) "gps_lost_alert" else "gps_found_alert")
    ) {
        Icon(if (lost) Icons.Rounded.GpsOff else Icons.Rounded.GpsFixed, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
        Column {
            Text(if (lost) "Signal GPS perdu" else "Signal GPS trouvé", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(subtitle, fontSize = 13.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ Statistiques

private fun kmValue(meters: Double) = String.format(Locale.FRANCE, "%.2f", meters / 1000.0)

private fun speedValue(mps: Double) = String.format(Locale.FRANCE, "%.1f", (mps * 3.6).coerceAtLeast(0.0))

private fun altitudeValue(meters: Double?) = meters?.let { Math.round(it).toString() } ?: "—"

private fun durationValue(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return String.format(Locale.FRANCE, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/**
 * Chiffres qui défilent un à un, comme un compteur mécanique : seul le chiffre qui
 * change bouge. Les positions sont comptées depuis la droite, pour qu'un chiffre de
 * plus à gauche (« 9,99 » → « 10,00 ») ne fasse pas rouler tous les autres.
 */
@Composable
private fun RollingNumber(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        text.forEachIndexed { index, ch ->
            key(text.length - index) {
                if (ch.isDigit()) {
                    AnimatedContent(
                        targetState = ch,
                        transitionSpec = {
                            slideInVertically(tween(220)) { it } togetherWith slideOutVertically(tween(220)) { -it }
                        },
                        label = "rolling_digit",
                        modifier = Modifier.clipToBounds()
                    ) { digit -> Text(digit.toString(), style = style) }
                } else {
                    Text(ch.toString(), style = style)
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    mode: ControlsMode,
    stats: LiveStats,
    trackName: String,
    currentSpeedMps: Double,
    currentAltitude: Double?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(36.dp))
            .clip(RoundedCornerShape(36.dp))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 22.dp, vertical = 20.dp)
            .testTag("live_stats_panel")
    ) {
        val mid = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, fontFeatureSettings = "tnum", color = colors.onSurface)
        if (mode == ControlsMode.RECORDING || mode == ControlsMode.PAUSED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode == ControlsMode.RECORDING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.errorContainer)
                            .padding(start = 10.dp, end = 12.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(LocalRecordingColor.current))
                        Text("ENREGISTREMENT", style = MaterialTheme.typography.labelSmall, color = colors.onErrorContainer)
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.tertiaryContainer)
                            .padding(start = 8.dp, end = 12.dp)
                    ) {
                        Icon(Icons.Rounded.Pause, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(16.dp))
                        Text("EN PAUSE", style = MaterialTheme.typography.labelSmall, color = colors.onTertiaryContainer)
                    }
                }
                Text(
                    trackName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 10.dp)) {
                RollingNumber(kmValue(stats.distanceMeters), StatXlTextStyle.copy(color = colors.onSurface))
                Text(
                    "km",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                StatColumn("Durée", durationValue(stats.durationSec), mid, Modifier.weight(1.25f))
                StatColumn("Vitesse · km/h", speedValue(currentSpeedMps), mid, Modifier.weight(1f))
                StatColumn("Altitude · m", altitudeValue(currentAltitude), mid, Modifier.weight(0.9f))
            }
        } else {
            val big = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Bold, fontSize = 48.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum", color = colors.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IdleStat("Vitesse", speedValue(currentSpeedMps), "km/h", big, Modifier.weight(1f))
                IdleStat("Altitude", altitudeValue(currentAltitude), "m", big, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, style: TextStyle, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        RollingNumber(value, style, Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun IdleStat(label: String, value: String, unit: String, style: TextStyle, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
            RollingNumber(value, style)
            Text(
                unit,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ Commandes

/**
 * Le bouton principal de la maquette, qui change de forme plutôt que d'être remplacé :
 * « Démarrer » (pilule large) s'ouvre en panneau de choix, devient « Pause » (carré
 * arrondi) pendant l'enregistrement, puis « Reprendre » (pilule) en pause. « Arrêter »
 * l'accompagne dès que l'enregistrement tourne, et s'élargit en pause pour montrer
 * son libellé.
 *
 * Les dimensions s'animent, jamais l'opacité de ce qui porte une ombre.
 */
@Composable
private fun RecordingControls(
    mode: ControlsMode,
    onStart: () -> Unit,
    onNewTrack: () -> Unit,
    onResumeExisting: () -> Unit,
    onCancelChoice: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    canResumeExisting: Boolean
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val panelWidth = (maxWidth - 24.dp).coerceAtMost(420.dp)
        val panelHeight = if (canResumeExisting) 232.dp else 152.dp
        val targetWidth: Dp
        val targetHeight: Dp
        val targetRadius: Dp
        when (mode) {
            ControlsMode.IDLE -> { targetWidth = 236.dp; targetHeight = 80.dp; targetRadius = 40.dp }
            ControlsMode.CHOICE -> { targetWidth = panelWidth; targetHeight = panelHeight; targetRadius = 36.dp }
            ControlsMode.RECORDING -> { targetWidth = 136.dp; targetHeight = 88.dp; targetRadius = 28.dp }
            ControlsMode.PAUSED -> { targetWidth = 136.dp; targetHeight = 88.dp; targetRadius = 44.dp }
        }
        val springSpec = spring<Dp>(dampingRatio = 0.8f, stiffness = 500f)
        val width by animateDpAsState(targetWidth, springSpec, label = "main_w")
        val height by animateDpAsState(targetHeight, springSpec, label = "main_h")
        val radius by animateDpAsState(targetRadius, springSpec, label = "main_r")
        val bg by animateColorAsState(
            when (mode) {
                ControlsMode.CHOICE -> colors.surfaceContainerHigh
                ControlsMode.RECORDING -> colors.tertiaryContainer
                else -> colors.primary
            },
            label = "main_bg"
        )
        val fg by animateColorAsState(
            when (mode) {
                ControlsMode.CHOICE -> colors.onSurface
                ControlsMode.RECORDING -> colors.onTertiaryContainer
                else -> colors.onPrimary
            },
            label = "main_fg"
        )

        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            val shape = RoundedCornerShape(radius)
            val mainClick: (() -> Unit)? = when (mode) {
                ControlsMode.IDLE -> onStart
                ControlsMode.CHOICE -> null
                else -> onPauseResume
            }
            Box(
                modifier = Modifier
                    .size(width, height)
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(bg)
                    .then(if (mainClick != null) Modifier.clickable(onClick = mainClick) else Modifier)
                    .testTag(
                        when (mode) {
                            ControlsMode.IDLE -> "action_fab"
                            ControlsMode.CHOICE -> "start_options_panel"
                            else -> "pause_resume_fab"
                        }
                    )
            ) {
                Crossfade(targetState = mode, animationSpec = tween(180), label = "main_content") { m ->
                    when (m) {
                        ControlsMode.IDLE -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(Icons.Rounded.RadioButtonChecked, contentDescription = null, tint = fg, modifier = Modifier.size(30.dp))
                            Text("Démarrer", fontFamily = DisplayFontFamily, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                        }
                        ControlsMode.CHOICE -> StartChoicePanel(onNewTrack, onResumeExisting, onCancelChoice, canResumeExisting, panelWidth)
                        ControlsMode.RECORDING -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, tint = fg, modifier = Modifier.size(36.dp))
                            Text("Pause", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                        }
                        ControlsMode.PAUSED -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = fg, modifier = Modifier.size(38.dp))
                            Text("Reprendre", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
                        }
                    }
                }
            }

            if (mode == ControlsMode.RECORDING || mode == ControlsMode.PAUSED) {
                StopButton(expanded = mode == ControlsMode.PAUSED, onClick = onStop)
            }
        }
    }
}

@Composable
private fun StopButton(expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Apparition en grossissant — une échelle ne rogne rien, contrairement à une
    // transition d'AnimatedVisibility, et l'opacité reste franche.
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
    val width by animateDpAsState(if (expanded) 150.dp else 88.dp, spring(dampingRatio = 0.8f, stiffness = 500f), label = "stop_w")
    val radius by animateDpAsState(if (expanded) 28.dp else 44.dp, label = "stop_r")
    val shape = RoundedCornerShape(radius)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .padding(start = 12.dp)
            .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
            .size(width, 88.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(colors.errorContainer)
            .clickable(onClick = onClick)
            .testTag("stop_fab")
    ) {
        Icon(Icons.Rounded.CropSquare, contentDescription = "Arrêter", tint = colors.onErrorContainer, modifier = Modifier.size(32.dp))
        if (expanded) {
            Text("Arrêter", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onErrorContainer, maxLines = 1)
        }
    }
}

@Composable
private fun StartChoicePanel(
    onNewTrack: () -> Unit,
    onResumeExisting: () -> Unit,
    onCancel: () -> Unit,
    canResumeExisting: Boolean,
    width: Dp
) {
    val colors = MaterialTheme.colorScheme
    // Largeur fixe : le contenu ne se recompose pas en se tassant pendant que le
    // bouton s'élargit, il se découvre.
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(width)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Text("Démarrer un enregistrement", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .clickable(onClick = onCancel)
                    .testTag("cancel_start_options_fab")
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Annuler", modifier = Modifier.size(22.dp))
            }
        }
        ChoiceRow(
            icon = Icons.Rounded.AddLocationAlt,
            title = "Nouveau parcours",
            subtitle = "Partir de zéro",
            container = colors.primary,
            content = colors.onPrimary,
            onClick = onNewTrack,
            testTag = "start_new_track_fab"
        )
        if (canResumeExisting) {
            ChoiceRow(
                icon = Icons.Rounded.Route,
                title = "Reprendre une trace",
                subtitle = "Continuer un parcours existant",
                container = colors.secondaryContainer,
                content = colors.onSecondaryContainer,
                onClick = onResumeExisting,
                testTag = "resume_existing_track_fab"
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp)
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = content, maxLines = 1)
            Text(subtitle, fontSize = 13.sp, color = content.copy(alpha = 0.9f), maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ Reprise

@Composable
private fun ResumeTrackPickerSheet(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    onTrackSelected: (Track) -> Unit
) {
    MpSheet(onDismissRequest = onDismiss) {
        SheetHeader("Reprendre une trace", "L'enregistrement continuera le parcours choisi.")
        Spacer(modifier = Modifier.height(14.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            tracks.forEach { track ->
                val color = androidx.compose.ui.graphics.Color(
                    TrackStylePreferences.resolveTrackColor(track.displayColor, track.sourceColor, track.isImported, track.isMerged)
                )
                val category = when {
                    track.isMerged -> "Fusionné"
                    track.isImported -> "Importé"
                    else -> "Enregistré"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onTrackSelected(track) }
                        .padding(horizontal = 12.dp)
                        .testTag("resume_picker_track_${track.id}")
                ) {
                    TrackTile(color = color)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "$category · ${FormatUtils.formatDayDate(track.startTime)} · ${FormatUtils.formatDistanceShort(track.totalDistance)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onDismiss)
                .testTag("resume_picker_cancel")
        ) {
            Text("Annuler", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ------------------------------------------------------------------ Permission

/**
 * Sans localisation : la carte reste visible mais voilée, et une carte explique ce
 * qui manque — plutôt qu'un écran vide qui ne dit pas où l'on est.
 */
@Composable
private fun PermissionDeniedOverlay(onGrantClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.72f)))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 130.dp)
            .shadow(10.dp, RoundedCornerShape(36.dp))
            .clip(RoundedCornerShape(36.dp))
            .background(colors.surfaceContainerLow)
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)
            .testTag("permission_denied_card")
    ) {
        ShapeBadge(
            icon = Icons.Rounded.LocationOff,
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
            shape = CookieShape,
            size = 112.dp,
            iconSize = 52.dp
        )
        Text(
            "Sans localisation, pas de parcours",
            fontFamily = DisplayFontFamily,
            fontSize = 26.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            "Mes parcours a besoin de votre position pour afficher la carte et mesurer " +
                "distance, vitesse et dénivelé. Elle ne quitte jamais votre téléphone.",
            fontSize = 15.sp,
            lineHeight = 22.sp,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(top = 22.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.primary)
                .clickable(onClick = onGrantClick)
                .padding(horizontal = 28.dp)
                .testTag("grant_permission_button")
        ) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
            Text("Accorder les permissions", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary)
        }
    }
}
