package com.example.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.preference.PreferenceManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.MapTrack
import com.example.data.model.MapViewport
import com.example.data.model.TrackPoint
import com.example.ui.screen.AlertState
import com.example.ui.theme.LocalIsDarkTheme
import com.example.util.OsmConfig
import com.example.util.TrackStylePreferences
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

private const val ZOOM_THRESHOLD = 11.0

/** Inversion : retourne la clarté, mais aussi les teintes — corrigées juste après. */
private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)

/**
 * Rotation des teintes d'un demi-tour — formulation de la spécification des filtres
 * SVG pour un angle de 180°.
 *
 * Chaque ligne somme à 1, ce qui garantit que les gris restent neutres.
 */
private val HUE_ROTATE_180_MATRIX = floatArrayOf(
    -0.574f, 1.430f, 0.144f, 0f, 0f,
    0.426f, 0.430f, 0.144f, 0f, 0f,
    0.426f, 1.430f, -0.856f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
)

/**
 * Filtre appliqué aux tuiles en thème sombre : inversion, puis rotation des teintes
 * d'un demi-tour. Le résultat, ce sont les couleurs de Mapnik assombries — vert
 * foncé pour les espaces verts, bleu profond pour l'eau, fond sombre à la place du
 * papier crème, et libellés clairs donc lisibles.
 *
 * Deux tentatives précédentes, et pourquoi elles ne suffisaient pas :
 *
 * - `TilesOverlay.INVERT_COLORS` est une inversion nue. Or inverser retourne à la
 *   fois la clarté *et* la teinte : le vert des forêts devenait magenta et le bleu
 *   de l'eau devenait brun.
 * - Désaturer avant d'inverser réglait la dominante, mais en effaçant toute couleur.
 *   La carte devenait un dégradé de gris, et l'on perdait la lecture immédiate que
 *   donnent le vert des espaces verts et le bleu de l'eau.
 *
 * Faire suivre l'inversion d'une rotation de teinte de 180° remet les teintes à leur
 * place : seule la clarté reste inversée. C'est la seule des trois approches qui
 * assombrit sans mentir sur les couleurs.
 */
internal val DARK_TILES_COLOR_FILTER: ColorFilter = ColorMatrixColorFilter(
    ColorMatrix(INVERT_MATRIX).apply {
        // postConcat s'applique *après* : on inverse, puis on rétablit les teintes.
        postConcat(ColorMatrix(HUE_ROTATE_180_MATRIX))
    }
)

/**
 * Marge ajoutée de chaque côté de la zone visible avant d'aller chercher les points,
 * exprimée en fraction de la largeur de l'écran. Sert de zone tampon : on peut faire
 * glisser la carte de cette fraction avant d'atteindre une zone non chargée.
 *
 * À 0,60, la zone chargée fait 2,2 fois l'écran en largeur comme en hauteur, soit près
 * de cinq fois sa surface. C'est un compromis, pas un réglage gratuit : élargir
 * retarde le moment où il faut recharger, mais alourdit chaque requête.
 *
 * Effet indirect à garder en tête : cette zone élargie est aussi ce que
 * `TrackRepository.coversMostOfTrack` compare à l'emprise de la trace. Plus la marge
 * est large, plus tôt l'affichage se rabat sur la seule silhouette.
 */
private const val VIEWPORT_MARGIN = 0.60

/**
 * Délai laissé à un recentrage programmatique pour se terminer avant de republier la
 * zone visible. `animateTo` étant animé, `boundingBox` renverrait sinon une zone
 * intermédiaire.
 */
private const val RECENTER_SETTLE_MS = 600L

/** Cadence de republication de la zone visible pendant le suivi automatique. */
private const val AUTO_FOLLOW_VIEWPORT_POLL_MS = 500L

/** Publie la zone visible actuelle (élargie de [VIEWPORT_MARGIN]) vers le ViewModel. */
private fun reportViewport(map: MapView, onViewportChanged: (MapViewport) -> Unit) {
    val box = map.boundingBox ?: return

    val latSpan = box.latNorth - box.latSouth
    val lonSpan = box.lonEast - box.lonWest
    if (latSpan <= 0.0) return

    val latMargin = latSpan * VIEWPORT_MARGIN
    var minLon = box.lonWest - lonSpan * VIEWPORT_MARGIN
    var maxLon = box.lonEast + lonSpan * VIEWPORT_MARGIN

    // Vue à cheval sur l'antiméridien ou dégénérée : on n'essaie pas de filtrer
    // en longitude, la sélection se fera sur la latitude seule.
    if (lonSpan <= 0.0 || minLon < -180.0 || maxLon > 180.0) {
        minLon = -180.0
        maxLon = 180.0
    }

    onViewportChanged(
        MapViewport(
            minLat = (box.latSouth - latMargin).coerceAtLeast(-90.0),
            maxLat = (box.latNorth + latMargin).coerceAtMost(90.0),
            minLon = minLon,
            maxLon = maxLon,
            zoom = map.zoomLevelDouble
        )
    )
}

// Helper class attached as tag to the MapView for lightweight dynamic access
private class MapState(
    var points: List<TrackPoint> = emptyList(),
    var overlayTracks: List<MapTrack> = emptyList(),
    var currentUserLocation: TrackPoint? = null,
    var isImported: Boolean = false,
    var isCurrentTracking: Boolean = false,
    var isInteractivityEnabled: Boolean = true,
    var wasZoomedOut: Boolean = false,
    var bypassZoomThreshold: Boolean = false,
    var cachedOverlayTrackPolylines: List<Polyline>? = null,
    var cachedPointsPolylines: List<Polyline>? = null,
    var lastKnownBearing: Float? = null,
    var mapMode: String = "2d",
    // Apparence choisie par l'utilisateur : tout changement invalide les polylignes en cache.
    var strokeWidth: Float = 12f,
    var recordedColor: Int = 0,
    var importedColor: Int = 0,
    var importedColorFromFile: Boolean = false,
    /** Couleur d'origine du parcours affiché en plein écran, s'il en a une. */
    var sourceColor: Int? = null
)

private fun createBlueDotIcon(context: Context): android.graphics.drawable.Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (24 * density).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    
    val paint = Paint().apply {
        isAntiAlias = true
    }
    
    // Draw outer subtle shadow/glow
    paint.color = Color.parseColor("#4285F4")
    paint.alpha = 50
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
    
    // Draw outer white circle border
    paint.color = Color.WHITE
    paint.alpha = 255
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.8f, paint)
    
    // Draw inner solid blue circle
    paint.color = Color.parseColor("#1A73E8") // Google Maps Blue
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, (sizePx / 2.8f) - (2.2f * density), paint)
    
    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

private val GOOGLE_SATELLITE_TILE_SOURCE = object : OnlineTileSourceBase(
    "GoogleSatellite",
    0, 20, 256, "",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=y&hl=fr",
        "https://mt1.google.com/vt/lyrs=y&hl=fr",
        "https://mt2.google.com/vt/lyrs=y&hl=fr",
        "https://mt3.google.com/vt/lyrs=y&hl=fr"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return getBaseUrl() + "&x=" + x + "&y=" + y + "&z=" + zoom
    }
}

@Composable
fun MapViewContainer(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    isInteractivityEnabled: Boolean = true,
    recenterTrigger: Int = 0,
    currentUserLocation: TrackPoint? = null,
    overlayTracks: List<MapTrack> = emptyList(),
    isImported: Boolean = false,
    sourceColor: Int? = null,
    isCurrentTracking: Boolean = false,
    zoomBannerTopPadding: androidx.compose.ui.unit.Dp = 110.dp,
    isAutoFollowActive: Boolean = false,
    onAutoFollowChanged: (Boolean) -> Unit = {},
    initialCenterLat: Double? = null,
    initialCenterLng: Double? = null,
    initialZoom: Double? = null,
    bypassZoomThreshold: Boolean = false,
    onBypassZoomThresholdChanged: (Boolean) -> Unit = {},
    onMapStateChanged: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onViewportChanged: (MapViewport) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Decouple zoom gesture updates from Jetpack Compose recomposition cycles
    var isZoomedOutTooMuch by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "TrackHiddenPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "TrackHiddenAlpha"
    )

    val mapView = rememberMapViewWithLifecycle(
        initialCenterLat = initialCenterLat,
        initialCenterLng = initialCenterLng,
        initialZoom = initialZoom,
        onZoomThresholdChanged = { zoomedOut ->
            isZoomedOutTooMuch = zoomedOut
        },
        onMapStateChanged = { lat, lng, zoom ->
            onMapStateChanged(lat, lng, zoom)
        },
        onViewportChanged = onViewportChanged
    )

    var hasInitiallyCenteredPoints by remember { mutableStateOf(initialCenterLat != null) }
    var hasInitiallyCenteredLocation by remember { mutableStateOf(initialCenterLat != null) }

    // Reset initial centering when points become empty (e.g. stopped tracking)
    LaunchedEffect(points.isEmpty()) {
        if (points.isEmpty()) {
            hasInitiallyCenteredPoints = false
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                val prefs = PreferenceManager.getDefaultSharedPreferences(map.context)
                val selectedStyle = prefs.getString("pref_map_style", "mapnik") ?: "mapnik"
                val tileSource = when (selectedStyle) {
                    "usgs_sat" -> GOOGLE_SATELLITE_TILE_SOURCE
                    else -> TileSourceFactory.MAPNIK
                }
                if (map.tileProvider.tileSource != tileSource) {
                    map.setTileSource(tileSource)
                }

                map.setUseDataConnection(true)
                map.setMultiTouchControls(isInteractivityEnabled)
                map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                
                map.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        onAutoFollowChanged(false)
                        isExpanded = false
                    }
                    false
                }
                
                // Retrieve or initialize map cache state
                val state = map.tag as? MapState ?: MapState()
                state.bypassZoomThreshold = bypassZoomThreshold
                val isZoomedOut = isZoomedOutTooMuch && !bypassZoomThreshold

                // "auto" : la carte s'oriente dans le sens de la marche pendant un
                // enregistrement, et revient au Nord dès qu'il est arrêté. Le mode
                // effectif est mémorisé dans state.mapMode, ce qui déclenche la
                // reconstruction des calques au moment de la bascule.
                val mapModePreference = prefs.getString("pref_map_mode", "2d") ?: "2d"
                val mapMode = when (mapModePreference) {
                    "auto" -> if (isCurrentTracking) "3d" else "2d"
                    else -> mapModePreference
                }

                if (mapMode == "2d") {
                    if (map.mapOrientation != 0f) {
                        map.mapOrientation = 0f
                    }
                } else {
                    val calcBearing = computeCurrentBearing(points, currentUserLocation, state.currentUserLocation)
                    if (calcBearing != null) {
                        state.lastKnownBearing = calcBearing
                    }
                    val activeBearing = state.lastKnownBearing ?: 0f
                    val targetOrientation = -activeBearing
                    if (kotlin.math.abs(map.mapOrientation - targetOrientation) > 0.5f) {
                        map.mapOrientation = targetOrientation
                    }
                }

                // Apparence choisie dans les paramètres / l'historique
                val strokeWidth = TrackStylePreferences.getStrokeWidth(map.context)
                val recordedColor = TrackStylePreferences.getRecordedColor(map.context)
                val importedColor = TrackStylePreferences.getImportedColor(map.context)
                val importedColorFromFile = TrackStylePreferences.isImportedColorFromFile(map.context)

                val styleChanged = state.strokeWidth != strokeWidth ||
                                   state.recordedColor != recordedColor ||
                                   state.importedColor != importedColor ||
                                   state.importedColorFromFile != importedColorFromFile ||
                                   state.sourceColor != sourceColor

                val configChanged = state.isImported != isImported ||
                                    state.isCurrentTracking != isCurrentTracking ||
                                    state.isInteractivityEnabled != isInteractivityEnabled ||
                                    state.mapMode != mapMode ||
                                    styleChanged

                val zoomBoundaryChanged = (state.wasZoomedOut != isZoomedOut)

                // Track actual identity changes dynamically
                val dataChanged = state.points != points ||
                                  state.overlayTracks != overlayTracks ||
                                  state.currentUserLocation != currentUserLocation

                // Invalidate specific caches if lists or styling have changed
                if (state.overlayTracks != overlayTracks || state.isImported != isImported || styleChanged) {
                    state.cachedOverlayTrackPolylines = null
                }
                if (state.points != points || state.isCurrentTracking != isCurrentTracking || state.isImported != isImported || styleChanged) {
                    state.cachedPointsPolylines = null
                }

                // Only trigger lightweight overlay reconstruction if underlying dataset, critical view configuration or zoom warnings cross state bounds
                if (dataChanged || zoomBoundaryChanged || configChanged) {
                    state.points = points
                    state.overlayTracks = overlayTracks
                    state.currentUserLocation = currentUserLocation
                    state.wasZoomedOut = isZoomedOut
                    state.isImported = isImported
                    state.isCurrentTracking = isCurrentTracking
                    state.isInteractivityEnabled = isInteractivityEnabled
                    state.mapMode = mapMode
                    state.strokeWidth = strokeWidth
                    state.recordedColor = recordedColor
                    state.importedColor = importedColor
                    state.importedColorFromFile = importedColorFromFile
                    state.sourceColor = sourceColor
                    map.tag = state

                    rebuildMapOverlays(map, state, isZoomedOut)
                }
            }
        )

        var showWarningDialog by remember { mutableStateOf(false) }

        // Floating Overlay Banner when zoomed out too much with tracks loaded
        val hasTracks = points.isNotEmpty() || overlayTracks.any { it.points.isNotEmpty() }
        val showZoomBanner = hasTracks && isZoomedOutTooMuch

        var bannerAlertState by remember { mutableStateOf<AlertState?>(null) }

        if (showWarningDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showWarningDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = "Avertissement de performance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = "L'affichage de tracés contenant des milliers de points de coordonnées géographiques à un niveau de zoom global demande une puissance de calcul graphique très élevée pour votre appareil.\n\n⚠️ Risques potentiels :\n• Ralentissement important de l'interface et de la carte (baisse de FPS)\n• Consommation accrue de la batterie\n• Surchauffe temporaire de l'appareil lors des déplacements sur la carte\n\n💡 Alternative recommandée :\nZoomez simplement sur la carte pour que l'affichage se réactive automatiquement et de manière fluide sans forcer.\n\nVoulez-vous tout de même forcer l'affichage de l'intégralité des tracés ?",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showWarningDialog = false
                            onBypassZoomThresholdChanged(true)
                            bannerAlertState = null
                            isExpanded = false
                        },
                        modifier = Modifier.testTag("confirm_bypass_zoom_button")
                    ) {
                        Text(
                            text = "Accepter et afficher",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showWarningDialog = false }
                    ) {
                        Text(
                            text = "Annuler",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            )
        }

        LaunchedEffect(bannerAlertState) {
            if (bannerAlertState != null) {
                kotlinx.coroutines.delay(2000L)
                bannerAlertState = null
            }
        }

        var lastShowZoomBanner by remember { mutableStateOf(showZoomBanner) }
        LaunchedEffect(showZoomBanner, bypassZoomThreshold) {
            if (!showZoomBanner) {
                isExpanded = false
            }
            if (showZoomBanner != lastShowZoomBanner) {
                if (!bypassZoomThreshold) {
                    if (showZoomBanner) {
                        bannerAlertState = AlertState.LOST
                    } else {
                        bannerAlertState = AlertState.FOUND
                    }
                } else {
                    bannerAlertState = null
                }
                lastShowZoomBanner = showZoomBanner
            } else if (bypassZoomThreshold) {
                bannerAlertState = null
            }
        }

        val isDark = LocalIsDarkTheme.current
        val blueBg = if (isDark) androidx.compose.ui.graphics.Color(0xFF1E293B).copy(alpha = 0.95f) else androidx.compose.ui.graphics.Color(0xFFE0F2FE).copy(alpha = 0.95f)
        val blueText = if (isDark) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFF0369A1)
        val blueBorder = if (isDark) androidx.compose.ui.graphics.Color(0xFF38BDF8).copy(alpha = 0.3f) else androidx.compose.ui.graphics.Color(0xFF0369A1).copy(alpha = 0.3f)

        val animatedContainerColor by androidx.compose.animation.animateColorAsState(
            targetValue = when (bannerAlertState) {
                AlertState.LOST -> androidx.compose.ui.graphics.Color(0xFFEA580C)
                AlertState.FOUND -> androidx.compose.ui.graphics.Color(0xFF10B981)
                else -> blueBg
            },
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
            label = "BannerBgColor"
        )

        val animatedTextColor by androidx.compose.animation.animateColorAsState(
            targetValue = if (bannerAlertState != null) androidx.compose.ui.graphics.Color.White else blueText,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
            label = "BannerTextColor"
        )

        val animatedBorderColor by androidx.compose.animation.animateColorAsState(
            targetValue = if (bannerAlertState != null) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f) else blueBorder,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 400),
            label = "BannerBorderColor"
        )

        AnimatedVisibility(
            visible = showZoomBanner || bannerAlertState == AlertState.FOUND,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { -it }),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(if (isCurrentTracking) Alignment.TopCenter else Alignment.TopStart)
                .statusBarsPadding()
                .padding(
                    top = zoomBannerTopPadding,
                    start = 16.dp,
                    end = if (isCurrentTracking) 16.dp else 80.dp
                )
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = animatedContainerColor
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = animatedBorderColor,
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                val titleEmoji = when {
                    bannerAlertState == AlertState.FOUND -> "😄"
                    bypassZoomThreshold -> "😄"
                    else -> "⚠️"
                }
                val titleText = when {
                    bannerAlertState == AlertState.FOUND -> "Tracé affiché"
                    bannerAlertState == AlertState.LOST -> "Tracé masqué"
                    isExpanded -> "Tracé de carte"
                    bypassZoomThreshold -> "Tracé affiché"
                    else -> "Tracé masqué"
                }

                Column(
                    modifier = Modifier
                        .then(
                            if (isExpanded) {
                                Modifier.width(if (isCurrentTracking) 280.dp else 240.dp)
                            } else {
                                Modifier
                            }
                        )
                        .clipToBounds()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            isExpanded = !isExpanded
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(if (isExpanded) 10.dp else 0.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = titleEmoji,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = animatedTextColor
                        )
                    }

                    if (isExpanded) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .requiredWidth(if (isCurrentTracking) 252.dp else 212.dp)
                                .padding(top = 10.dp)
                        ) {
                            Text(
                                text = if (bypassZoomThreshold) {
                                    "L'affichage du tracé est actuellement forcé malgré le faible niveau de zoom."
                                } else {
                                    "Le niveau de zoom est trop faible pour afficher les tracés sans ralentissement."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Start,
                                color = animatedTextColor.copy(alpha = 0.9f),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (bypassZoomThreshold) {
                                androidx.compose.material3.Button(
                                    onClick = {
                                        onBypassZoomThresholdChanged(false)
                                        isExpanded = false
                                    },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = animatedTextColor,
                                        contentColor = animatedContainerColor
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .testTag("hide_zoom_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ZoomOut,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = animatedContainerColor
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Masquer les tracés",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = animatedContainerColor
                                        )
                                    }
                                }
                            } else {
                                androidx.compose.material3.Button(
                                    onClick = { showWarningDialog = true },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = animatedTextColor,
                                        contentColor = animatedContainerColor
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .testTag("bypass_zoom_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ZoomIn,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = animatedContainerColor
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Forcer l'affichage",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = animatedContainerColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Handle center cameras when recenter trigger changes explicitly or auto-follow is activated
    LaunchedEffect(recenterTrigger, isAutoFollowActive) {
        if (recenterTrigger > 0 || isAutoFollowActive) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val zoomLevelToSet = prefs.getFloat("pref_default_zoom", 16.5f).toDouble()
            mapView.controller.setZoom(zoomLevelToSet)
            if (isCurrentTracking && currentUserLocation != null) {
                mapView.controller.animateTo(GeoPoint(currentUserLocation.latitude, currentUserLocation.longitude))
            } else if (points.isNotEmpty()) {
                val lastPt = points.last()
                mapView.controller.animateTo(GeoPoint(lastPt.latitude, lastPt.longitude))
            } else if (currentUserLocation != null) {
                mapView.controller.animateTo(GeoPoint(currentUserLocation.latitude, currentUserLocation.longitude))
            }
            // Un déplacement programmatique de la carte ne produit pas d'événement de
            // défilement exploitable : sans cette republication, la zone visible reste
            // celle d'avant le recentrage.
            kotlinx.coroutines.delay(RECENTER_SETTLE_MS)
            reportViewport(mapView, onViewportChanged)
        }
    }

    /*
     * Pendant le suivi automatique, la carte se déplace seule au fil des positions.
     * Comme aucun de ces déplacements ne republie la zone visible, le ViewModel
     * conservait celle d'avant : `getDisplayPoints` continuait de renvoyer le détail
     * de l'ancienne zone, et la partie nouvellement visible d'un tracé dense restait
     * réduite à sa silhouette — jusqu'à ce qu'un glissement à la main déclenche enfin
     * un `onScroll`.
     *
     * La republication est périodique, et non accrochée au recentrage : les positions
     * arrivant environ une fois par seconde, un effet dépendant de currentUserLocation
     * serait annulé avant la fin de l'animation et ne publierait jamais rien.
     * `MapViewport` étant une data class dans un StateFlow, une zone inchangée
     * n'entraîne aucun rechargement.
     */
    LaunchedEffect(isAutoFollowActive) {
        if (!isAutoFollowActive) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(AUTO_FOLLOW_VIEWPORT_POLL_MS)
            reportViewport(mapView, onViewportChanged)
        }
    }

    // Auto-follow live coordinate updates when auto-follow is active
    LaunchedEffect(points.lastOrNull(), currentUserLocation) {
        if (isAutoFollowActive) {
            if (isCurrentTracking && currentUserLocation != null) {
                mapView.controller.animateTo(GeoPoint(currentUserLocation.latitude, currentUserLocation.longitude))
            } else if (points.isNotEmpty()) {
                val lastPt = points.last()
                mapView.controller.animateTo(GeoPoint(lastPt.latitude, lastPt.longitude))
            } else if (currentUserLocation != null) {
                mapView.controller.animateTo(GeoPoint(currentUserLocation.latitude, currentUserLocation.longitude))
            }
        }
    }

    // Centering on first points update
    LaunchedEffect(points.isNotEmpty()) {
        if (points.isNotEmpty() && !hasInitiallyCenteredPoints) {
            val lastPt = points.last()
            mapView.controller.animateTo(GeoPoint(lastPt.latitude, lastPt.longitude))
            hasInitiallyCenteredPoints = true
            kotlinx.coroutines.delay(RECENTER_SETTLE_MS)
            reportViewport(mapView, onViewportChanged)
        }
    }

    // Centering on first live user location (standby)
    LaunchedEffect(currentUserLocation != null) {
        if (currentUserLocation != null && !hasInitiallyCenteredLocation && points.isEmpty()) {
            mapView.controller.animateTo(GeoPoint(currentUserLocation.latitude, currentUserLocation.longitude))
            hasInitiallyCenteredLocation = true
            kotlinx.coroutines.delay(RECENTER_SETTLE_MS)
            reportViewport(mapView, onViewportChanged)
        }
    }
}

@Composable
fun rememberMapViewWithLifecycle(
    initialCenterLat: Double? = null,
    initialCenterLng: Double? = null,
    initialZoom: Double? = null,
    onZoomThresholdChanged: (Boolean) -> Unit = {},
    onMapStateChanged: (Double, Double, Double) -> Unit = { _, _, _ -> },
    onViewportChanged: (MapViewport) -> Unit = {}
): MapView {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Initialize OSMDroid config
    val mapView = remember {
        OsmConfig.init(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val defaultZoom = initialZoom ?: prefs.getFloat("pref_default_zoom", 16.5f).toDouble()
        val selectedStyle = prefs.getString("pref_map_style", "mapnik") ?: "mapnik"
        val tileSource = when (selectedStyle) {
            "usgs_sat" -> GOOGLE_SATELLITE_TILE_SOURCE
            else -> TileSourceFactory.MAPNIK
        }

        val map = MapView(context).apply {
            setTileSource(tileSource)
            controller.setZoom(defaultZoom)
            if (initialCenterLat != null && initialCenterLng != null) {
                controller.setCenter(GeoPoint(initialCenterLat, initialCenterLng))
            } else {
                // Center default on France / generic coordinates if empty
                controller.setCenter(GeoPoint(46.603354, 1.888334))
            }
        }

        // Attach state container and a MapListener once to capture zoom depth with zero rendering updates
        val state = MapState()
        map.tag = state

        map.addMapListener(object : org.osmdroid.events.MapListener {
            private var lastZoomedOut = defaultZoom < ZOOM_THRESHOLD

            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                val centerPt = map.mapCenter
                onMapStateChanged(centerPt.latitude, centerPt.longitude, map.zoomLevelDouble)
                reportViewport(map, onViewportChanged)
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                val zoom = map.zoomLevelDouble
                val zoomedOut = zoom < ZOOM_THRESHOLD
                val mapState = map.tag as? MapState ?: return false

                val centerPt = map.mapCenter
                onMapStateChanged(centerPt.latitude, centerPt.longitude, zoom)
                reportViewport(map, onViewportChanged)

                if (zoomedOut != lastZoomedOut) {
                    lastZoomedOut = zoomedOut
                    map.post {
                        onZoomThresholdChanged(zoomedOut)
                    }
                }
                
                val actualZoomedOut = zoomedOut && !mapState.bypassZoomThreshold
                // Synchronously toggle tracking visibility under zoom threshold boundary shift
                if (mapState.wasZoomedOut != actualZoomedOut) {
                    mapState.wasZoomedOut = actualZoomedOut
                    rebuildMapOverlays(map, mapState, actualZoomedOut)
                }
                return true
            }
        })

        // Première publication de la zone visible, une fois la carte réellement
        // mesurée : avant le layout, boundingBox n'a pas de valeur exploitable.
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            reportViewport(map, onViewportChanged)
        }

        // Initial callback so the compose state aligns immediately
        onZoomThresholdChanged(defaultZoom < ZOOM_THRESHOLD)

        map
    }

    val isDark = LocalIsDarkTheme.current

    LaunchedEffect(mapView, isDark) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val selectedStyle = prefs.getString("pref_map_style", "mapnik") ?: "mapnik"
        if (selectedStyle != "usgs_sat") {
            // L'imagerie satellite est laissée intacte : l'assombrir la rendrait
            // illisible, et une photo aérienne n'a pas de fond clair à inverser.
            mapView.overlayManager.tilesOverlay.setColorFilter(
                if (isDark) DARK_TILES_COLOR_FILTER else null
            )
        } else {
            mapView.overlayManager.tilesOverlay.setColorFilter(null)
        }
        mapView.setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.WHITE)
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    return mapView
}

private fun drawMarkers(map: MapView, state: MapState) {
    if (state.points.isNotEmpty()) {
        val geoPoints = state.points.map { GeoPoint(it.latitude, it.longitude) }
        val startPoint = geoPoints.first()
        val startMarker = Marker(map).apply {
            position = startPoint
            title = "Départ"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = map.resources.getDrawable(android.R.drawable.presence_online, null)
        }
        map.overlays.add(startMarker)

        val lastPoint = geoPoints.last()
        val currentMarker = Marker(map).apply {
            position = if (state.isCurrentTracking && state.currentUserLocation != null) {
                GeoPoint(state.currentUserLocation!!.latitude, state.currentUserLocation!!.longitude)
            } else {
                lastPoint
            }
            title = if (state.isCurrentTracking) "Position Actuelle" else "Arrivée"
            if (state.isCurrentTracking) {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createBlueDotIcon(map.context)
            } else {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = map.resources.getDrawable(
                    if (state.isInteractivityEnabled) android.R.drawable.presence_away else android.R.drawable.presence_busy, 
                    null
                )
            }
        }
        map.overlays.add(currentMarker)
    } else if (state.currentUserLocation != null) {
        val currentPoint = GeoPoint(state.currentUserLocation!!.latitude, state.currentUserLocation!!.longitude)
        val currentMarker = Marker(map).apply {
            position = currentPoint
            title = "Ma Position"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createBlueDotIcon(map.context)
        }
        map.overlays.add(currentMarker)
    }
}

/**
 * Découpe une trace en tronçons continus, un par polyligne à dessiner.
 *
 * C'est ce qui fait qu'une trace reprise, mise en pause puis relancée, ou issue d'une
 * fusion, apparaît comme un seul parcours mais sans trait reliant ses morceaux.
 */
internal fun buildSegmentsFromPoints(trackPoints: List<TrackPoint>): List<List<GeoPoint>> {
    val segments = mutableListOf<List<GeoPoint>>()
    var curSegment = mutableListOf<GeoPoint>()
    var prevTime = 0L
    var prevId = 0L

    for (pt in trackPoints) {
        if (pt.latitude == 0.0 && pt.longitude == 0.0) continue

        // Le saut de temps ne signale une pause d'enregistrement que si les deux points
        // se suivent réellement. Sur une trace dense affichée en niveau de détail réduit,
        // les points intermédiaires sont volontairement omis (les id sautent) : l'écart de
        // temps y est normal et ne doit pas couper le tracé. Les vraies ruptures de
        // segment restent portées par isDiscontinuous, toujours conservé à l'affichage.
        val pointsAreAdjacent = prevId <= 0L || pt.id <= 0L || pt.id == prevId + 1L
        val isTimeGap = pointsAreAdjacent &&
                prevTime > 0L && pt.timestamp > 0L &&
                (pt.timestamp - prevTime > 15_000L)

        if ((pt.isDiscontinuous || isTimeGap) && curSegment.isNotEmpty()) {
            segments.add(curSegment)
            curSegment = mutableListOf()
        }
        curSegment.add(GeoPoint(pt.latitude, pt.longitude))
        prevTime = pt.timestamp
        prevId = pt.id
    }
    if (curSegment.isNotEmpty()) {
        segments.add(curSegment)
    }
    return segments
}

private fun drawAllPointsAndMarkers(map: MapView, state: MapState) {
    // 1. Draw overlay (imported/merged) tracks if cached is null
    var cachedOverlayPolylines = state.cachedOverlayTrackPolylines
    if (cachedOverlayPolylines == null) {
        val buildList = mutableListOf<Polyline>()
        for (overlayTrack in state.overlayTracks) {
            val trackPoints = overlayTrack.points
            if (trackPoints.isNotEmpty()) {
                val segments = buildSegmentsFromPoints(trackPoints)

                val overlayColor = if (overlayTrack.isImported) {
                    TrackStylePreferences.resolveImportedColor(
                        fromFile = state.importedColorFromFile,
                        sourceColor = overlayTrack.sourceColor,
                        fallback = state.importedColor
                    )
                } else {
                    state.recordedColor
                }

                for (segmentPoints in segments) {
                    if (segmentPoints.isNotEmpty()) {
                        val polyline = Polyline().apply {
                            outlinePaint.color = overlayColor
                            outlinePaint.strokeWidth = state.strokeWidth
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            setPoints(segmentPoints)
                        }
                        buildList.add(polyline)
                    }
                }
            }
        }
        cachedOverlayPolylines = buildList
        state.cachedOverlayTrackPolylines = buildList
    }

    if (cachedOverlayPolylines != null) {
        map.overlays.addAll(cachedOverlayPolylines)
    }

    // 2. Draw live/main tracking points if cached is null
    var cachedPointsPolylines = state.cachedPointsPolylines
    if (cachedPointsPolylines == null) {
        val buildList = mutableListOf<Polyline>()
        if (state.points.isNotEmpty()) {
            val segments = buildSegmentsFromPoints(state.points)

            val trackLineColor = when {
                state.isCurrentTracking -> Color.parseColor("#D32F2F") // Rouge pendant l'enregistrement
                state.isImported -> TrackStylePreferences.resolveImportedColor(
                    fromFile = state.importedColorFromFile,
                    sourceColor = state.sourceColor,
                    fallback = state.importedColor
                )
                else -> state.recordedColor
            }

            for (segmentPoints in segments) {
                if (segmentPoints.isNotEmpty()) {
                    val polyline = Polyline().apply {
                        outlinePaint.color = trackLineColor
                        outlinePaint.strokeWidth = state.strokeWidth
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        setPoints(segmentPoints)
                    }
                    buildList.add(polyline)
                }
            }
        }
        cachedPointsPolylines = buildList
        state.cachedPointsPolylines = buildList
    }

    if (cachedPointsPolylines != null) {
        map.overlays.addAll(cachedPointsPolylines)
    }

    // 3. Draw markers (extremely cheap, always draw fresh)
    drawMarkers(map, state)
}

private fun rebuildMapOverlays(map: MapView, state: MapState, isZoomedOut: Boolean) {
    map.overlays.clear()

    // If zoomed out beyond the safety threshold, render nothing but markers
    if (isZoomedOut) {
        drawMarkers(map, state)
        map.invalidate()
        return
    }

    // Draw the complete continuous tracks
    drawAllPointsAndMarkers(map, state)

    map.invalidate()
}

private fun computeCurrentBearing(
    points: List<TrackPoint>,
    currentLocation: TrackPoint?,
    previousLocation: TrackPoint?
): Float? {
    if (currentLocation != null && previousLocation != null && currentLocation != previousLocation) {
        val dist = calculateDistanceMeters(
            previousLocation.latitude, previousLocation.longitude,
            currentLocation.latitude, currentLocation.longitude
        )
        if (dist >= 1.5) {
            return calculateBearing(
                previousLocation.latitude, previousLocation.longitude,
                currentLocation.latitude, currentLocation.longitude
            )
        }
    }

    if (points.size >= 2) {
        val last = points.last()
        for (i in points.size - 2 downTo 0.coerceAtLeast(points.size - 10)) {
            val prev = points[i]
            val dist = calculateDistanceMeters(
                prev.latitude, prev.longitude,
                last.latitude, last.longitude
            )
            if (dist >= 1.5) {
                return calculateBearing(
                    prev.latitude, prev.longitude,
                    last.latitude, last.longitude
                )
            }
        }
    }
    return null
}

private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
    return results[0].toDouble()
}

private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLonRad = Math.toRadians(lon2 - lon1)

    val y = Math.sin(deltaLonRad) * Math.cos(lat2Rad)
    val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(deltaLonRad)

    val bearingRad = Math.atan2(y, x)
    val bearingDeg = Math.toDegrees(bearingRad)
    return ((bearingDeg + 360) % 360).toFloat()
}
