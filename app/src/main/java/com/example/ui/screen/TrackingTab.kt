@file:Suppress("DEPRECATION")

package com.example.ui.screen

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsOff
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

    // Hauteur de la carte de statistiques, pour poser le bandeau des tracés masqués
    // juste en dessous : elle grandit quand un enregistrement démarre.
    var statsCardHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val zoomBannerTop = with(density) { statsCardHeightPx.toDp() } + 36.dp

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
            zoomBannerTopPadding = zoomBannerTop,
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

        // --- Haut de l'écran : la carte de statistiques, à la place qu'occupait
        // l'ancien bandeau d'altitude — à la demande de l'auteur, qui a gardé le
        // dessin de la maquette mais voulu retrouver la disposition d'avant. Les
        // alertes de signal la recouvrent le temps de s'afficher, comme avant.
        if (hasLocationPermission) {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { statsCardHeightPx = it.height }
                ) {
                    StatsCard(
                        mode = mode,
                        stats = liveStats,
                        currentSpeedMps = currentSpeed,
                        currentAltitude = currentAlt,
                        gpsIndicator = { GpsDot(found = signalFound) }
                    )
                    when (activeAlertState) {
                        AlertState.LOST -> GpsAlert(
                            lost = true,
                            subtitle = if (isTracking) "L'enregistrement reprendra dès son retour" else "Position en attente du signal",
                            modifier = Modifier.matchParentSize()
                        )
                        AlertState.FOUND -> GpsAlert(
                            lost = false,
                            subtitle = gpsAccuracy?.let { "Précision ±${it.toInt()} m" } ?: "Position retrouvée",
                            modifier = Modifier.matchParentSize()
                        )
                        null -> Unit
                    }
                }
            }
        } else {
            GpsChip(
                icon = Icons.Rounded.LocationDisabled,
                text = "Localisation désactivée",
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp)
                    .alpha(0.5f)
            )
        }

        // --- Bas de l'écran, à droite, comme avant et aux mêmes tailles : recentrage
        // (56 dp), puis le bouton secondaire (56 dp) et le bouton principal (72 dp).
        if (hasLocationPermission) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 20.dp, bottom = 96.dp)
            ) {
                if (currentUserLocation != null || livePoints.isNotEmpty()) {
                    RoundButton(
                        icon = Icons.Rounded.MyLocation,
                        description = "Recentrer",
                        size = 56.dp,
                        iconSize = 24.dp,
                        container = if (isAutoFollowActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        content = if (isAutoFollowActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                        testTag = "recenter_button",
                        onClick = {
                            isAutoFollowActive = true
                            recenterTrigger++
                        }
                    )
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

@Composable
private fun GpsAlert(lost: Boolean, subtitle: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val bg = if (lost) colors.error else colors.primary
    val fg = if (lost) colors.onError else colors.onPrimary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .padding(horizontal = 18.dp)
            .testTag(if (lost) "gps_lost_alert" else "gps_found_alert")
    ) {
        Icon(if (lost) Icons.Rounded.GpsOff else Icons.Rounded.GpsFixed, contentDescription = null, tint = fg, modifier = Modifier.size(28.dp))
        Column {
            Text(if (lost) "Signal GPS perdu" else "Signal GPS trouvé", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(subtitle, fontSize = 13.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * État du GPS dans la carte de statistiques, comme le point de l'ancien bandeau :
 * plein quand le signal est là, qui pulse pendant la recherche. L'animation infinie
 * ne vit que pendant la recherche — une horloge d'animation permanente empêcherait
 * le processeur de se reposer (voir « Audit batterie de la 1.2 »).
 */
@Composable
private fun GpsDot(found: Boolean) {
    if (found) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .testTag("gps_dot_found")
        )
    } else {
        val pulse = rememberInfiniteTransition(label = "gps_dot")
        val scale by pulse.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "gps_dot_scale"
        )
        val tertiary = MaterialTheme.colorScheme.tertiary
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(18.dp).testTag("gps_dot_searching")) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; alpha = 1.2f - scale }
                    .clip(CircleShape)
                    .background(tertiary)
            )
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(tertiary))
        }
    }
}

// ------------------------------------------------------------------ Statistiques

private fun kmValue(meters: Double) = String.format(Locale.FRANCE, "%.2f", meters / 1000.0)

private fun speedValue(mps: Double) = String.format(Locale.FRANCE, "%.1f", (mps * 3.6).coerceAtLeast(0.0))

private fun altitudeValue(meters: Double?) = meters?.let { Math.round(it).toString() } ?: "—"

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

/**
 * Bandeau de statistiques, **à la taille de l'ancien** — une seule rangée de
 * chiffres, à la demande de l'auteur qui voulait garder la place d'avant pour la
 * carte — mais dans le dessin de la refonte : fond de surface, Bricolage pour les
 * chiffres, unités dans les libellés, point d'état du GPS à droite.
 */
@Composable
private fun StatsCard(
    mode: ControlsMode,
    stats: LiveStats,
    currentSpeedMps: Double,
    currentAltitude: Double?,
    modifier: Modifier = Modifier,
    gpsIndicator: @Composable () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val value = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = "tnum",
        color = colors.onSurface
    )
    val recording = mode == ControlsMode.RECORDING || mode == ControlsMode.PAUSED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainerLow)
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
            .testTag("live_stats_panel")
    ) {
        if (recording) {
            // Point d'enregistrement devant la distance ; il passe à l'orange de la
            // pause quand l'enregistrement est suspendu.
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (mode == ControlsMode.PAUSED) colors.tertiary else LocalRecordingColor.current)
                    .testTag(if (mode == ControlsMode.PAUSED) "stats_paused_dot" else "stats_recording_dot")
            )
            // Distance, vitesse, altitude : les trois chiffres du bandeau d'avant. La
            // durée n'y figure pas, à la demande de l'auteur.
            CompactStat("Distance", kmValue(stats.distanceMeters), "km", value, Modifier.weight(1f))
            CompactStat("Vitesse", speedValue(currentSpeedMps), "km/h", value, Modifier.weight(1f))
            CompactStat("Altitude", altitudeValue(currentAltitude), "m", value, Modifier.weight(1f))
        } else {
            CompactStat("Vitesse", speedValue(currentSpeedMps), "km/h", value, Modifier.weight(1f))
            CompactStat("Altitude", altitudeValue(currentAltitude), "m", value, Modifier.weight(1f))
        }
        Box(modifier = Modifier.padding(start = 6.dp)) { gpsIndicator() }
    }
}

@Composable
private fun CompactStat(label: String, value: String, unit: String?, style: TextStyle, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            RollingNumber(value, style)
            if (unit != null) {
                // Hauteur de ligne explicite : héritée du style par défaut (24 sp), elle
                // rendait l'unité plus haute que le chiffre, qui la calait alors en haut.
                Text(
                    unit,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Commandes

/**
 * Bouton rond de la refonte, aux tailles des boutons d'avant : 72 dp pour l'action
 * principale, 56 dp pour les secondaires, 48 dp pour annuler.
 */
@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    testTag: String,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(container, label = "round_bg")
    val fg by animateColorAsState(content, label = "round_fg")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Crossfade(targetState = icon, animationSpec = tween(160), label = "round_icon") { ic ->
            Icon(ic, contentDescription = description, tint = fg, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * Bouton rond qui apparaît en grossissant : une échelle ne rogne rien,
 * contrairement à une transition d'AnimatedVisibility, et l'opacité reste franche
 * (voir « L'ombre tranchée » dans CLAUDE.md).
 */
@Composable
private fun PoppingRoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    testTag: String,
    onClick: () -> Unit
) {
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
    Box(modifier = Modifier.graphicsLayer { scaleX = appear.value; scaleY = appear.value }) {
        RoundButton(icon, description, size, iconSize, container, content, testTag, onClick)
    }
}

/** Ce que montre le bouton principal dans un état donné. */
private class MainButton(
    val icon: ImageVector,
    val description: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
    val testTag: String,
    val onClick: () -> Unit
)

/**
 * Les boutons d'enregistrement, en colonne et aux tailles d'avant, dans le dessin de
 * la refonte. Le bouton du bas (72 dp) est toujours l'action principale : Démarrer,
 * Nouveau parcours pendant le choix, Arrêter pendant l'enregistrement. Au-dessus
 * (56 dp) : Pause ou Reprendre, ou « Reprendre une trace » pendant le choix ; et
 * au-dessus encore, pendant le choix seulement, Annuler (48 dp).
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (mode) {
            ControlsMode.CHOICE -> {
                key("cancel") {
                    PoppingRoundButton(
                        Icons.Rounded.Close, "Annuler", 48.dp, 20.dp,
                        colors.surfaceContainerHighest, colors.onSurfaceVariant,
                        "cancel_start_options_fab", onCancelChoice
                    )
                }
                if (canResumeExisting) {
                    key("resume_existing") {
                        PoppingRoundButton(
                            Icons.Rounded.Route, "Reprendre une trace existante", 56.dp, 24.dp,
                            colors.secondaryContainer, colors.onSecondaryContainer,
                            "resume_existing_track_fab", onResumeExisting
                        )
                    }
                }
            }
            ControlsMode.RECORDING, ControlsMode.PAUSED -> {
                val paused = mode == ControlsMode.PAUSED
                key("pause_resume") {
                    PoppingRoundButton(
                        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (paused) "Reprendre" else "Pause",
                        56.dp, 28.dp,
                        if (paused) colors.primary else colors.tertiaryContainer,
                        if (paused) colors.onPrimary else colors.onTertiaryContainer,
                        "pause_resume_fab", onPauseResume
                    )
                }
            }
            ControlsMode.IDLE -> Unit
        }

        // Le bouton principal : un seul composant, qui change de couleur et d'icône
        // d'un état à l'autre plutôt que d'être remplacé.
        val main = when (mode) {
            ControlsMode.IDLE -> MainButton(Icons.Rounded.RadioButtonChecked, "Démarrer", colors.primary, colors.onPrimary, "action_fab", onStart)
            ControlsMode.CHOICE -> MainButton(Icons.Rounded.AddLocationAlt, "Nouveau parcours", colors.primary, colors.onPrimary, "start_new_track_fab", onNewTrack)
            else -> MainButton(Icons.Rounded.CropSquare, "Arrêter", colors.errorContainer, colors.onErrorContainer, "stop_fab", onStop)
        }
        RoundButton(main.icon, main.description, 72.dp, 34.dp, main.container, main.content, main.testTag, main.onClick)
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
