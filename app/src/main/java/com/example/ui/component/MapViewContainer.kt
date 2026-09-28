package com.example.ui.component

import android.content.Context
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.preference.PreferenceManager
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.ui.theme.LocalIsDarkTheme
import com.example.util.OsmConfig
import com.example.util.TrackStylePreferences
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

/**
 * Cadence de republication de la zone visible pendant le suivi automatique.
 *
 * Une seconde, et non deux fois par seconde comme auparavant : la carte suit des
 * positions qui n'arrivent elles-mêmes qu'à 1 Hz, et chaque republication déclenche
 * le circuit de rechargement des points. Doubler la cadence de la source ne pouvait
 * rien apprendre de neuf — cela ne faisait que réveiller le processeur pour rien
 * tant que l'écran reste allumé.
 */
private const val AUTO_FOLLOW_VIEWPORT_POLL_MS = 1000L

/**
 * Distance parcourue, en fraction de la largeur de l'écran, au-delà de laquelle le
 * suivi automatique republie la zone visible.
 *
 * La zone publiée déborde l'écran de [VIEWPORT_MARGIN] de chaque côté : tant que l'on
 * s'est moins déplacé que cela, l'écran reste couvert par des points déjà chargés et
 * republier ne peut rien apprendre de neuf. La moitié de cette marge laisse de quoi
 * recharger avant d'en atteindre le bord.
 *
 * Sans ce filtre, la zone repartait à chaque seconde et relançait tout le circuit de
 * lecture — y compris, pour les traces sous `fullLoadLimit`, une relecture complète de
 * leurs points qui ne dépend pourtant pas de la zone. En voiture, cela revenait à
 * redemander plusieurs centaines de milliers de lignes par seconde pour un résultat
 * identique. À vitesse d'automobile, on republie désormais toutes les dizaines de
 * secondes au lieu de chaque seconde.
 */
private const val AUTO_FOLLOW_RELOAD_FRACTION = VIEWPORT_MARGIN / 2.0

/** Intervalle minimal entre deux mémorisations de la position de carte. */
private const val MAP_STATE_PERSIST_MS = 1000L

/**
 * Déplacement minimal entre deux relevés pour en tirer un cap.
 *
 * À 1 Hz, un mètre et demi correspond à environ 5 km/h : en dessous, l'écart entre
 * deux positions vient surtout de l'imprécision du GPS, et la direction qu'on en
 * déduirait tournerait au hasard.
 */
private const val BEARING_MIN_DISTANCE_METERS = 1.5

/**
 * Part du nouveau cap reprise à chaque mise à jour.
 *
 * Assez bas pour absorber le tremblement d'un relevé à l'autre, assez haut pour
 * qu'un virage soit suivi en quelques secondes plutôt qu'accompli d'un bloc.
 */
private const val BEARING_SMOOTHING = 0.35f

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

/**
 * Variante de [reportViewport] pour le sondage périodique du suivi automatique :
 * centre la zone publiée sur la position GPS connue plutôt que sur le centre
 * rapporté par la carte. Seule l'étendue (largeur/hauteur en degrés) vient encore
 * de `map.boundingBox` — elle ne dépend que du zoom, jamais d'une animation.
 *
 * `animateTo()` est appelé à chaque nouvelle position pendant le suivi (une fois par
 * seconde environ), mais osmdroid rejette silencieusement tout appel lancé pendant
 * qu'une précédente animation tourne encore — sans erreur, sans y revenir. Un trajet
 * en voiture peut ainsi accumuler des appels ignorés : la caméra prend du retard sur
 * la position réelle sans que rien ne le signale, et le centre que rapporterait
 * `map.boundingBox` ne serait alors plus celui qu'il faut réellement charger.
 * Reconstruire la zone autour de la position GPS elle-même — toujours exacte,
 * puisqu'elle ne dépend d'aucune animation — élimine ce risque.
 */
private fun reportAutoFollowViewport(
    map: MapView,
    userLat: Double,
    userLon: Double,
    onViewportChanged: (MapViewport) -> Unit
) {
    val box = map.boundingBox ?: return

    val latSpan = box.latNorth - box.latSouth
    val lonSpan = box.lonEast - box.lonWest
    if (latSpan <= 0.0) return

    val halfLat = latSpan * (0.5 + VIEWPORT_MARGIN)
    var minLon = userLon - lonSpan * (0.5 + VIEWPORT_MARGIN)
    var maxLon = userLon + lonSpan * (0.5 + VIEWPORT_MARGIN)

    // Vue à cheval sur l'antiméridien ou dégénérée : on n'essaie pas de filtrer
    // en longitude, la sélection se fera sur la latitude seule.
    if (lonSpan <= 0.0 || minLon < -180.0 || maxLon > 180.0) {
        minLon = -180.0
        maxLon = 180.0
    }

    onViewportChanged(
        MapViewport(
            minLat = (userLat - halfLat).coerceAtLeast(-90.0),
            maxLat = (userLat + halfLat).coerceAtMost(90.0),
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
    var isMerged: Boolean = false,
    var isCurrentTracking: Boolean = false,
    var isInteractivityEnabled: Boolean = true,
    /**
     * Dernière valeur réellement passée à `setMultiTouchControls`, ou null tant
     * qu'on ne l'a jamais appelée. Distinct de `isInteractivityEnabled`, qui n'est
     * mis à jour qu'avec la reconstruction des calques : il faut ici savoir si
     * l'appel a eu lieu, pas seulement ce que valait le réglage.
     */
    var appliedMultiTouch: Boolean? = null,
    var wasZoomedOut: Boolean = false,
    var bypassZoomThreshold: Boolean = false,
    var cachedOverlayTrackPolylines: List<Polyline>? = null,
    var cachedPointsPolylines: List<Polyline>? = null,
    var lastKnownBearing: Float? = null,
    var mapMode: String = "2d",
    // Apparence choisie par l'utilisateur : tout changement invalide les polylignes en cache.
    var strokeWidth: Float = 12f,
    /** Couleur d'origine du parcours affiché en plein écran, s'il en a une. */
    var sourceColor: Int? = null,
    /** Couleur choisie par l'utilisateur pour le parcours affiché en plein écran. */
    var displayColor: Int? = null,
    /**
     * Icône du point bleu de position, construite une seule fois puis réutilisée.
     *
     * `drawMarkers` s'exécute à chaque point GPS reçu pendant un enregistrement —
     * environ une fois par seconde, sur toute sa durée. Rien dans cette icône ne
     * dépend de l'état courant : la refabriquer (bitmap, canvas, trois cercles)
     * à chaque appel n'était que du travail jeté, pour un résultat identique.
     */
    var blueDotIcon: android.graphics.drawable.Drawable? = null,
    /**
     * Couleurs du thème pour les repères et la trace en cours, relevées dans la
     * composition : le dessin d'osmdroid n'a pas accès à MaterialTheme. Un
     * changement (bascule clair ↔ sombre) vide [markerIcons] et les polylignes.
     */
    var themeColors: MarkerColors? = null,
    /** Icônes des repères déjà dessinées, par couleur et par taille. */
    val markerIcons: MutableMap<Long, android.graphics.drawable.Drawable> = mutableMapOf()
)

/** Couleurs des repères de carte, tirées du thème. */
internal data class MarkerColors(
    val position: Int,
    val start: Int,
    val end: Int,
    val recording: Int
)

private fun blueDotIcon(context: Context, state: MapState): android.graphics.drawable.Drawable =
    state.blueDotIcon ?: createDotIcon(
        context,
        fill = state.themeColors?.position ?: Color.parseColor("#1F6A4F"),
        sizeDp = 26f,
        ringDp = 4f
    ).also { state.blueDotIcon = it }

/**
 * Pastille d'un repère (départ, arrivée), dessinée une fois par couleur puis
 * réutilisée : même raison que [MapState.blueDotIcon].
 */
private fun markerIcon(context: Context, state: MapState, fill: Int, sizeDp: Float): android.graphics.drawable.Drawable {
    val key = (fill.toLong() shl 8) or sizeDp.toLong()
    return state.markerIcons.getOrPut(key) { createDotIcon(context, fill, sizeDp, ringDp = 3f) }
}

/**
 * Pastille de la maquette : disque de couleur cerclé de blanc, sur une ombre douce
 * qui la détache des tuiles claires comme sombres.
 */
private fun createDotIcon(context: Context, fill: Int, sizeDp: Float, ringDp: Float): android.graphics.drawable.Drawable {
    val density = context.resources.displayMetrics.density
    val shadowPx = 3 * density
    val sizePx = (sizeDp * density + 2 * shadowPx).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val c = sizePx / 2f
    val radius = sizeDp * density / 2f

    val paint = Paint().apply { isAntiAlias = true }

    // Ombre : deux disques translucides légèrement décalés vers le bas.
    paint.color = Color.BLACK
    paint.alpha = 40
    canvas.drawCircle(c, c + density, radius + shadowPx * 0.8f, paint)

    paint.color = Color.WHITE
    paint.alpha = 255
    canvas.drawCircle(c, c, radius, paint)

    paint.color = fill
    canvas.drawCircle(c, c, radius - ringDp * density, paint)

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
    isMerged: Boolean = false,
    sourceColor: Int? = null,
    /** Couleur choisie par l'utilisateur pour le parcours affiché, null s'il n'a rien choisi. */
    displayColor: Int? = null,
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
    onViewportChanged: (MapViewport) -> Unit = {},
    /**
     * Fond de carte imposé par l'appelant (bouton « calques » de l'écran
     * d'enregistrement) ; null = celui des réglages. Passer par un paramètre plutôt
     * que par la seule préférence fait réexécuter le bloc `update` au changement.
     */
    tileStyle: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val markerColors = MarkerColors(
        position = scheme.primary.toArgb(),
        start = scheme.primary.toArgb(),
        end = scheme.onSurface.toArgb(),
        recording = com.example.ui.theme.LocalRecordingColor.current.toArgb()
    )
    // Decouple zoom gesture updates from Jetpack Compose recomposition cycles
    var isZoomedOutTooMuch by remember { mutableStateOf(false) }

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
                val selectedStyle = tileStyle ?: prefs.getString("pref_map_style", "mapnik") ?: "mapnik"
                val tileSource = when (selectedStyle) {
                    "usgs_sat" -> GOOGLE_SATELLITE_TILE_SOURCE
                    else -> TileSourceFactory.MAPNIK
                }
                if (map.tileProvider.tileSource != tileSource) {
                    map.setTileSource(tileSource)
                }

                map.setUseDataConnection(true)
                map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                
                map.setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        onAutoFollowChanged(false)
                    }
                    false
                }
                
                // Retrieve or initialize map cache state
                val state = map.tag as? MapState ?: MapState().also { map.tag = it }
                state.bypassZoomThreshold = bypassZoomThreshold

                // Bascule clair ↔ sombre : les icônes et la trace en cours changent de
                // couleur, donc tout ce qui en est dessiné est à refaire.
                val themeChanged = state.themeColors != markerColors
                if (themeChanged) {
                    state.themeColors = markerColors
                    state.markerIcons.clear()
                    state.blueDotIcon = null
                    state.cachedPointsPolylines = null
                }
                val isZoomedOut = isZoomedOutTooMuch && !bypassZoomThreshold

                // `setMultiTouchControls` n'est pas un simple réglage : chaque appel
                // remplace le MultiTouchController d'osmdroid par un neuf. L'appeler à
                // chaque recomposition — donc une à deux fois par seconde pendant un
                // enregistrement, à chaque nouvelle position GPS — jetait le contrôleur
                // au milieu du geste de l'utilisateur.
                //
                // Or c'est ce contrôleur qui referme le pincement. osmdroid pilote le vrai
                // niveau de zoom pendant un pincement (`setMultiTouchScale` calcule
                // log2(échelle) + le zoom relevé à l'ouverture du geste), et ne remet le
                // point d'ancrage à null que dans le `selectObject` de fin de geste.
                // Contrôleur remplacé en cours de route = fin de geste jamais reçue :
                // `mMultiTouchScaleCurrentPoint` reste renseigné, et `getProjection()`
                // continue d'y recaler toutes les projections suivantes. La carte semblait
                // alors zoomer ou dézoomer d'elle-même, bien après que le doigt ait quitté
                // l'écran — et seulement en mode focus, seul moment où les recompositions
                // s'enchaînent assez vite pour tomber pendant un geste.
                //
                // On ne touche donc à ce réglage que lorsqu'il change réellement, comme le
                // fait déjà le code juste au-dessus pour la source de tuiles.
                if (state.appliedMultiTouch != isInteractivityEnabled) {
                    state.appliedMultiTouch = isInteractivityEnabled
                    map.setMultiTouchControls(isInteractivityEnabled)
                }

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
                        state.lastKnownBearing = smoothedBearing(state.lastKnownBearing, calcBearing)
                    }
                    val activeBearing = state.lastKnownBearing ?: 0f
                    val targetOrientation = -activeBearing
                    if (kotlin.math.abs(map.mapOrientation - targetOrientation) > 0.5f) {
                        map.mapOrientation = targetOrientation
                    }
                }

                // Apparence choisie dans les paramètres / l'historique
                val strokeWidth = TrackStylePreferences.getStrokeWidth(map.context)

                val styleChanged = state.strokeWidth != strokeWidth ||
                                   state.sourceColor != sourceColor ||
                                   state.displayColor != displayColor

                val configChanged = state.isImported != isImported ||
                                    state.isMerged != isMerged ||
                                    state.isCurrentTracking != isCurrentTracking ||
                                    state.isInteractivityEnabled != isInteractivityEnabled ||
                                    state.mapMode != mapMode ||
                                    styleChanged ||
                                    themeChanged

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
                    state.isMerged = isMerged
                    state.isCurrentTracking = isCurrentTracking
                    state.isInteractivityEnabled = isInteractivityEnabled
                    state.mapMode = mapMode
                    state.strokeWidth = strokeWidth
                    state.sourceColor = sourceColor
                    state.displayColor = displayColor
                    map.tag = state

                    rebuildMapOverlays(map, state, isZoomedOut)
                }
            }
        )

        var showWarningDialog by remember { mutableStateOf(false) }

        // Bandeau quand le zoom est trop large pour dessiner les tracés sans ralentir.
        val hasTracks = points.isNotEmpty() || overlayTracks.any { it.points.isNotEmpty() }
        val showZoomBanner = hasTracks && isZoomedOutTooMuch
        // Le bandeau replié en pastille reste replié jusqu'à la prochaine fois qu'il
        // réapparaît : un utilisateur qui l'a écarté n'a pas à le revoir à chaque geste.
        var bannerCollapsed by remember { mutableStateOf(false) }
        LaunchedEffect(showZoomBanner) {
            if (!showZoomBanner) bannerCollapsed = false
        }

        if (showWarningDialog) {
            MpDialog(
                onDismissRequest = { showWarningDialog = false },
                icon = {
                    ShapeBadge(
                        icon = Icons.Rounded.Warning,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = SunShape,
                        size = 56.dp,
                        iconSize = 28.dp
                    )
                },
                title = "Forcer l'affichage ?",
                body = "Afficher tous les tracés à ce niveau de zoom peut ralentir la carte, " +
                    "vider la batterie plus vite et faire chauffer le téléphone.",
                dismissLabel = "Annuler",
                confirmLabel = "Forcer quand même",
                confirmContainerColor = MaterialTheme.colorScheme.tertiary,
                confirmContentColor = MaterialTheme.colorScheme.onTertiary,
                confirmTestTag = "confirm_bypass_zoom_button",
                onConfirm = {
                    showWarningDialog = false
                    onBypassZoomThresholdChanged(true)
                }
            )
        }

        // Pas d'AnimatedVisibility ici : elle rogne son contenu, ombre comprise, pendant
        // toute sa transition (voir « L'ombre tranchée » dans CLAUDE.md).
        if (showZoomBanner) {
            val bannerModifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = zoomBannerTopPadding, start = 16.dp, end = 76.dp)
            when {
                bypassZoomThreshold -> MapPill(
                    icon = Icons.Rounded.Visibility,
                    text = "Tracés affichés",
                    actionLabel = "Masquer",
                    onClick = { onBypassZoomThresholdChanged(false) },
                    testTag = "hide_zoom_button",
                    modifier = bannerModifier
                )
                bannerCollapsed -> MapPill(
                    icon = Icons.Rounded.VisibilityOff,
                    text = "Tracés masqués",
                    actionLabel = null,
                    onClick = { bannerCollapsed = false },
                    testTag = "zoom_banner_pill",
                    modifier = bannerModifier
                )
                else -> HeavyTracksBanner(
                    onForce = { showWarningDialog = true },
                    onDismiss = { bannerCollapsed = true },
                    modifier = bannerModifier
                )
            }
        }
    }

    // Recentrage de la caméra, à l'appui sur le bouton « localiser » (mode focus).
    //
    // Ce bloc imposait aussi le zoom, en le ramenant à `pref_default_zoom`. C'était le
    // défaut rapporté : « quand j'appuie sur localiser pour passer en focus, ça zoome
    // ou dézoome tout seul, au moment du clic ». Deux raisons de ne plus y toucher :
    //
    // - `pref_default_zoom` n'est pas un réglage choisi par l'utilisateur : c'est le
    //   dernier zoom mémorisé par la carte. Or cette mémorisation est bornée à une fois
    //   par seconde et sans rattrapage de la dernière valeur (voir MAP_STATE_PERSIST_MS
    //   et `persistMapStateThrottled`) : elle retient donc presque toujours un zoom
    //   relevé *au milieu* du dernier geste, jamais celui où l'utilisateur s'est arrêté.
    //   Le recentrage y ramenait la carte, d'où le saut en avant ou en arrière.
    // - Même juste, cette valeur n'aurait pas à s'imposer : « localiser » veut dire
    //   « centre-toi sur moi », pas « change mon échelle ».
    //
    // Le zoom courant est donc conservé. Seule exception : une carte assez dézoomée pour
    // que les tracés ne soient plus dessinés (ZOOM_THRESHOLD) — s'y recentrer sans
    // rapprocher laisserait l'utilisateur devant un pays entier, ce qui n'est pas ce
    // qu'il demande en appuyant sur « localiser ».
    //
    // `recenterTrigger` reste dans les clés pour forcer une nouvelle exécution quand on
    // retape sur le bouton alors que le mode focus est déjà actif (sa valeur seule ne
    // changerait pas), mais la garde ne porte que sur `isAutoFollowActive` : sinon
    // l'effet se rejouait aussi au moment où le mode focus s'éteint, donc juste après le
    // geste manuel qui vient de le désengager.
    LaunchedEffect(recenterTrigger, isAutoFollowActive) {
        if (isAutoFollowActive) {
            if (mapView.zoomLevelDouble < ZOOM_THRESHOLD) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val fallbackZoom = prefs.getFloat("pref_default_zoom", 16.5f).toDouble()
                mapView.controller.setZoom(fallbackZoom.coerceAtLeast(ZOOM_THRESHOLD))
            }
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
     *
     * Se fier au centre rapporté par la carte ne suffit pas : `animateTo()`, appelé à
     * chaque nouvelle position, peut être rejeté par osmdroid si l'animation
     * précédente tourne encore — sans erreur, sans y revenir. Pendant un trajet en
     * voiture, ces appels ignorés s'accumulent et la caméra prend du retard sur la
     * position réelle, silencieusement : le sondage se répète bien, mais republie une
     * zone qui n'a jamais fini de rejoindre l'endroit à charger — jusqu'à ce qu'un
     * geste manuel (glissement, zoom) recale tout d'un coup via `onScroll`/`onZoom`.
     * `reportAutoFollowViewport` reconstruit donc la zone autour de la position GPS
     * elle-même, lue via `rememberUpdatedState` pour rester à jour dans cette boucle
     * de longue durée — jamais autour d'un centre de caméra qui peut avoir décroché.
     */
    val latestUserLocation by rememberUpdatedState(currentUserLocation)
    val latestIsCurrentTracking by rememberUpdatedState(isCurrentTracking)
    LaunchedEffect(isAutoFollowActive) {
        if (!isAutoFollowActive) return@LaunchedEffect

        // Repère du dernier envoi. Tant que la vue reste bien à l'intérieur de ce qui
        // a déjà été demandé, republier ne ferait que relancer une lecture identique.
        var publishedLat = Double.NaN
        var publishedLon = Double.NaN
        var publishedZoom = Double.NaN

        while (true) {
            kotlinx.coroutines.delay(AUTO_FOLLOW_VIEWPORT_POLL_MS)

            val box = mapView.boundingBox ?: continue
            val latSpan = box.latNorth - box.latSouth
            val lonSpan = box.lonEast - box.lonWest
            if (latSpan <= 0.0) continue

            val loc = latestUserLocation
            val followingGps = latestIsCurrentTracking && loc != null
            // Le centre du suivi GPS est la position elle-même, jamais celle que
            // rapporte la caméra : `animateTo` peut avoir pris du retard (voir
            // reportAutoFollowViewport).
            val centerLat = if (followingGps) loc!!.latitude else (box.latNorth + box.latSouth) / 2.0
            val centerLon = if (followingGps) loc!!.longitude else (box.lonEast + box.lonWest) / 2.0
            val zoom = mapView.zoomLevelDouble

            // Un changement de zoom change le niveau de détail attendu : on republie
            // toujours. Sauter ce cas laisserait un zoom avant sur la silhouette
            // grossière, exactement le gain de détail qu'il venait chercher.
            val zoomChanged = publishedZoom.isNaN() || zoom != publishedZoom
            val movedFar = publishedLat.isNaN() ||
                    kotlin.math.abs(centerLat - publishedLat) > latSpan * AUTO_FOLLOW_RELOAD_FRACTION ||
                    kotlin.math.abs(centerLon - publishedLon) > lonSpan * AUTO_FOLLOW_RELOAD_FRACTION

            if (!zoomChanged && !movedFar) continue

            if (followingGps) {
                reportAutoFollowViewport(mapView, centerLat, centerLon, onViewportChanged)
            } else {
                reportViewport(mapView, onViewportChanged)
            }

            publishedLat = centerLat
            publishedLon = centerLon
            publishedZoom = zoom
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

            /**
             * Dernière mémorisation de la position de carte, sur horloge monotone.
             *
             * osmdroid notifie ces deux rappels à chaque image de ses animations —
             * `MapController.onAnimationUpdate` déplace la carte une soixantaine de
             * fois par seconde. Y enregistrer la position à chaque fois revenait à
             * demander quatre écritures de préférences par image, soit plus de deux
             * cents accès disque par seconde tant que le suivi automatique animait la
             * carte. Une fois par seconde suffit largement : cette valeur ne sert qu'à
             * retrouver le même cadrage au prochain lancement.
             */
            private var lastPersistedAt = 0L

            private fun persistMapStateThrottled() {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPersistedAt < MAP_STATE_PERSIST_MS) return
                lastPersistedAt = now
                val centerPt = map.mapCenter
                onMapStateChanged(centerPt.latitude, centerPt.longitude, map.zoomLevelDouble)
            }

            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                persistMapStateThrottled()
                reportViewport(map, onViewportChanged)
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                val zoom = map.zoomLevelDouble
                val zoomedOut = zoom < ZOOM_THRESHOLD
                val mapState = map.tag as? MapState ?: return false

                persistMapStateThrottled()
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
    val colors = state.themeColors
    if (state.points.isNotEmpty()) {
        val geoPoints = state.points.map { GeoPoint(it.latitude, it.longitude) }
        val startPoint = geoPoints.first()
        val startMarker = Marker(map).apply {
            position = startPoint
            title = "Départ"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = markerIcon(map.context, state, colors?.start ?: Color.parseColor("#1F6A4F"), 20f)
        }
        map.overlays.add(startMarker)

        val lastPoint = geoPoints.last()
        val currentMarker = Marker(map).apply {
            position = if (state.isCurrentTracking && state.currentUserLocation != null) {
                GeoPoint(state.currentUserLocation!!.latitude, state.currentUserLocation!!.longitude)
            } else {
                lastPoint
            }
            title = if (state.isCurrentTracking) "Position actuelle" else "Arrivée"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = if (state.isCurrentTracking) {
                blueDotIcon(map.context, state)
            } else {
                markerIcon(map.context, state, colors?.end ?: Color.DKGRAY, 20f)
            }
        }
        map.overlays.add(currentMarker)
    } else if (state.currentUserLocation != null) {
        val currentPoint = GeoPoint(state.currentUserLocation!!.latitude, state.currentUserLocation!!.longitude)
        val currentMarker = Marker(map).apply {
            position = currentPoint
            title = "Ma position"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = blueDotIcon(map.context, state)
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
internal fun buildSegmentsFromPoints(trackPoints: List<TrackPoint>): List<MapSegment> {
    val segments = mutableListOf<MapSegment>()
    var curSegment = mutableListOf<GeoPoint>()
    var curColor: Int? = null
    var prevTime = 0L
    var prevId = 0L
    var prevColor: Int? = null
    var hasPrevious = false

    for (pt in trackPoints) {
        if (pt.latitude == 0.0 && pt.longitude == 0.0) continue

        // Un changement de couleur coupe le tronçon même sans rupture déclarée : une
        // polyligne n'a qu'une couleur, et deux trajets voisins d'un même fichier
        // KML se retrouveraient sinon peints du même trait.
        val colorChanged = hasPrevious && pt.segmentColor != prevColor

        // Le saut de temps ne signale une pause d'enregistrement que si les deux points
        // se suivent réellement. Sur une trace dense affichée en niveau de détail réduit,
        // les points intermédiaires sont volontairement omis (les id sautent) : l'écart de
        // temps y est normal et ne doit pas couper le tracé. Les vraies ruptures de
        // segment restent portées par isDiscontinuous, toujours conservé à l'affichage.
        val pointsAreAdjacent = prevId <= 0L || pt.id <= 0L || pt.id == prevId + 1L
        val isTimeGap = pointsAreAdjacent &&
                prevTime > 0L && pt.timestamp > 0L &&
                (pt.timestamp - prevTime > 15_000L)

        if ((pt.isDiscontinuous || isTimeGap || colorChanged) && curSegment.isNotEmpty()) {
            segments.add(MapSegment(curColor, curSegment))
            curSegment = mutableListOf()
        }
        if (curSegment.isEmpty()) curColor = pt.segmentColor
        curSegment.add(GeoPoint(pt.latitude, pt.longitude))
        prevTime = pt.timestamp
        prevId = pt.id
        prevColor = pt.segmentColor
        hasPrevious = true
    }
    if (curSegment.isNotEmpty()) {
        segments.add(MapSegment(curColor, curSegment))
    }
    return segments
}

/**
 * Un morceau de tracé d'un seul tenant, et la couleur que son fichier d'origine lui
 * donnait — null s'il n'en donnait pas.
 */
internal data class MapSegment(
    val sourceColor: Int?,
    val points: List<GeoPoint>
)

private fun drawAllPointsAndMarkers(map: MapView, state: MapState) {
    // 1. Draw overlay (imported/merged) tracks if cached is null
    var cachedOverlayPolylines = state.cachedOverlayTrackPolylines
    if (cachedOverlayPolylines == null) {
        val buildList = mutableListOf<Polyline>()
        for (overlayTrack in state.overlayTracks) {
            val trackPoints = overlayTrack.points
            if (trackPoints.isNotEmpty()) {
                val segments = buildSegmentsFromPoints(trackPoints)

                for (segment in segments) {
                    if (segment.points.isNotEmpty()) {
                        // La couleur se décide tronçon par tronçon : un même fichier
                        // importé peut réunir des dizaines de trajets, chacun de la
                        // sienne. Une couleur choisie à la main sur le parcours passe
                        // devant : elle vaut pour lui tout entier, tronçons compris,
                        // sinon la choisir ne se verrait pas sur un KML coloré.
                        val segmentColor = TrackStylePreferences.resolveTrackColor(
                            displayColor = overlayTrack.displayColor,
                            sourceColor = segment.sourceColor ?: overlayTrack.sourceColor,
                            isImported = overlayTrack.isImported,
                            isMerged = overlayTrack.isMerged
                        )

                        val polyline = Polyline().apply {
                            outlinePaint.color = segmentColor
                            outlinePaint.strokeWidth = state.strokeWidth
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            setPoints(segment.points)
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

            for (segment in segments) {
                if (segment.points.isNotEmpty()) {
                    // La couleur d'enregistrement du thème prime sur tout : c'est le
                    // seul signal qui distingue la trace en train de s'écrire.
                    val trackLineColor = if (state.isCurrentTracking) {
                        state.themeColors?.recording ?: Color.parseColor("#E4572E")
                    } else {
                        TrackStylePreferences.resolveTrackColor(
                            displayColor = state.displayColor,
                            sourceColor = segment.sourceColor ?: state.sourceColor,
                            isImported = state.isImported,
                            isMerged = state.isMerged
                        )
                    }

                    val polyline = Polyline().apply {
                        outlinePaint.color = trackLineColor
                        outlinePaint.strokeWidth = state.strokeWidth
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        setPoints(segment.points)
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

    // 3. Draw markers. Les objets Marker sont refaits à chaque appel (légers),
    // mais leur icône de position — coûteuse à dessiner — est mise en cache
    // dans MapState par blueDotIcon() : voir sa raison d'être plus haut.
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

/**
 * Cap à afficher, ou null s'il n'y a rien de neuf à en dire — l'appelant garde alors
 * le dernier cap connu.
 *
 * **Une position connue fait autorité, et elle seule.** Le repli sur les points
 * enregistrés ne vaut que faute de position GPS : s'en servir parce que la position
 * n'a pas changé depuis le passage précédent était un piège. Pendant une pause
 * d'enregistrement, `points` ne bouge plus et garde éternellement le cap qu'on avait
 * en s'arrêtant ; la carte basculait donc entre le cap réel et ce cap fossilisé,
 * plusieurs fois par seconde et sur une centaine de degrés. Le défaut est d'autant
 * plus visible que l'affichage est réévalué souvent, ce qui est le cas depuis que la
 * zone visible est échantillonnée à intervalle régulier.
 *
 * Renvoyer null quand la position n'a pas assez bougé est donc la bonne réponse :
 * mieux vaut conserver le cap précédent qu'en inventer un autre.
 */
internal fun computeCurrentBearing(
    points: List<TrackPoint>,
    currentLocation: TrackPoint?,
    previousLocation: TrackPoint?
): Float? {
    if (currentLocation != null) {
        if (previousLocation == null || currentLocation == previousLocation) return null
        val dist = calculateDistanceMeters(
            previousLocation.latitude, previousLocation.longitude,
            currentLocation.latitude, currentLocation.longitude
        )
        // Sous ce seuil, l'écart entre deux relevés tient davantage au bruit du GPS
        // qu'à un déplacement réel : le cap qu'on en tirerait serait aléatoire.
        if (dist < BEARING_MIN_DISTANCE_METERS) return null
        return calculateBearing(
            previousLocation.latitude, previousLocation.longitude,
            currentLocation.latitude, currentLocation.longitude
        )
    }

    // Aucune position connue : on s'oriente sur la fin du tracé affiché.
    if (points.size >= 2) {
        val last = points.last()
        for (i in points.size - 2 downTo 0.coerceAtLeast(points.size - 10)) {
            val prev = points[i]
            val dist = calculateDistanceMeters(
                prev.latitude, prev.longitude,
                last.latitude, last.longitude
            )
            if (dist >= BEARING_MIN_DISTANCE_METERS) {
                return calculateBearing(
                    prev.latitude, prev.longitude,
                    last.latitude, last.longitude
                )
            }
        }
    }
    return null
}

/**
 * Rapproche le cap affiché de [target], en suivant l'arc le plus court.
 *
 * Deux raisons de ne pas poser directement la valeur. D'abord le passage par le nord :
 * interpoler de 350° à 10° en ligne droite ferait faire à la carte un tour complet
 * pour un virage de vingt degrés. Ensuite le confort : le cap issu de deux positions
 * consécutives tressaute, et l'appliquer tel quel donnait une carte qui sursaute à
 * chaque relevé au lieu de tourner.
 */
internal fun smoothedBearing(previous: Float?, target: Float): Float {
    if (previous == null) return target
    // Écart ramené dans [-180°, 180°] : c'est ce qui choisit le sens de rotation.
    val delta = ((target - previous + 540f) % 360f) - 180f
    return ((previous + delta * BEARING_SMOOTHING) % 360f + 360f) % 360f
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

/**
 * Bandeau de la maquette quand les tracés sont masqués faute de zoom suffisant :
 * explication, « Forcer l'affichage » (avec confirmation) et « Laisser masqués ».
 */
@Composable
private fun HeavyTracksBanner(onForce: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(colors.tertiaryContainer)
            .padding(16.dp)
            .testTag("zoom_banner")
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(24.dp))
            Column {
                Text(
                    "Tracés masqués à ce niveau de zoom",
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onTertiaryContainer
                )
                Text(
                    "Les tracés très détaillés ralentiraient la carte. Rapprochez-vous pour les revoir.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = colors.onTertiaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.tertiary)
                    .clickable(onClick = onForce)
                    .padding(horizontal = 16.dp)
                    .testTag("bypass_zoom_button")
            ) {
                Text("Forcer l'affichage", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onTertiary)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, colors.onTertiaryContainer, RoundedCornerShape(20.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp)
                    .testTag("dismiss_zoom_banner_button")
            ) {
                Text("Laisser masqués", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onTertiaryContainer)
            }
        }
    }
}

/** Pastille flottante sur la carte : état des tracés, et une action éventuelle. */
@Composable
private fun MapPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    actionLabel: String?,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .height(40.dp)
                .shadow(6.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(colors.tertiaryContainer)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 16.dp)
                .testTag(testTag)
        ) {
            Icon(icon, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onTertiaryContainer)
            if (actionLabel != null) {
                Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.tertiary)
            }
        }
    }
}
