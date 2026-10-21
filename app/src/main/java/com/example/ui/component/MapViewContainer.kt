package com.example.ui.component

import com.example.R
import androidx.compose.ui.res.stringResource
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

/** Inversion: flips lightness, but also hues — corrected right after. */
private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f
)

/**
 * Half-turn hue rotation — formulation from the SVG filters specification
 * for an angle of 180°.
 *
 * Each row sums to 1, which guarantees that grays stay neutral.
 */
private val HUE_ROTATE_180_MATRIX = floatArrayOf(
    -0.574f, 1.430f, 0.144f, 0f, 0f,
    0.426f, 0.430f, 0.144f, 0f, 0f,
    0.426f, 1.430f, -0.856f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f
)

/**
 * Filter applied to tiles in dark theme: inversion, then a half-turn hue
 * rotation. The result is Mapnik's colors darkened — dark green for green
 * spaces, deep blue for water, a dark background in place of the cream
 * paper, and light labels, hence readable.
 *
 * Two earlier attempts, and why they were not enough:
 *
 * - `TilesOverlay.INVERT_COLORS` is a bare inversion. But inverting flips both
 *   lightness *and* hue: the green of forests became magenta and the blue
 *   of water became brown.
 * - Desaturating before inverting fixed the dominant tint, but erased every color.
 *   The map became a gradient of grays, and we lost the immediate reading that
 *   the green of green spaces and the blue of water give.
 *
 * Following the inversion with a 180° hue rotation puts the hues back in their
 * place: only lightness stays inverted. It is the only one of the three approaches that
 * darkens without lying about colors.
 */
internal val DARK_TILES_COLOR_FILTER: ColorFilter = ColorMatrixColorFilter(
    ColorMatrix(INVERT_MATRIX).apply {
        // postConcat applies *after*: we invert, then restore the hues.
        postConcat(ColorMatrix(HUE_ROTATE_180_MATRIX))
    }
)

/**
 * Margin added on each side of the visible area before fetching points,
 * expressed as a fraction of the screen width. Serves as a buffer zone: the map can be
 * dragged by this fraction before reaching an unloaded area.
 *
 * At 0.60, the loaded area is 2.2 times the screen in width and in height, i.e. nearly
 * five times its surface. It is a compromise, not a free setting: widening
 * delays the moment a reload is needed, but makes each query heavier.
 *
 * Indirect effect to keep in mind: this widened area is also what
 * `TrackRepository.coversMostOfTrack` compares with the track's extent. The wider the margin,
 * the earlier the display falls back to the silhouette alone.
 */
private const val VIEWPORT_MARGIN = 0.60

/**
 * Time left for a programmatic recentering to finish before republishing the
 * visible area. `animateTo` being animated, `boundingBox` would otherwise return an
 * intermediate area.
 */
private const val RECENTER_SETTLE_MS = 600L

/**
 * Rate at which the visible area is republished during automatic following.
 *
 * One second, and not twice a second as before: the map follows
 * positions that themselves only arrive at 1 Hz, and each republication triggers
 * the point-reloading circuit. Doubling the source's rate could
 * teach nothing new — it only woke the processor for nothing
 * while the screen stays on.
 */
private const val AUTO_FOLLOW_VIEWPORT_POLL_MS = 1000L

/**
 * Distance traveled, as a fraction of the screen width, beyond which
 * automatic following republishes the visible area.
 *
 * The published area overflows the screen by [VIEWPORT_MARGIN] on each side: as long as we
 * have moved less than that, the screen stays covered by already loaded points and
 * republishing can teach nothing new. Half of that margin leaves room to
 * reload before reaching its edge.
 *
 * Without this filter, the area went out every second and restarted the whole reading
 * circuit — including, for tracks under `fullLoadLimit`, a complete reread of
 * their points that does not even depend on the area. In a car, this amounted to
 * asking again for several hundred thousand rows per second for an
 * identical result. At car speed, we now republish every few tens of
 * seconds instead of every second.
 */
private const val AUTO_FOLLOW_RELOAD_FRACTION = VIEWPORT_MARGIN / 2.0

/** Minimum interval between two saves of the map position. */
private const val MAP_STATE_PERSIST_MS = 1000L

/**
 * Minimum movement between two readings to derive a bearing from them.
 *
 * At 1 Hz, a meter and a half corresponds to about 5 km/h: below that, the gap between
 * two positions comes mostly from GPS imprecision, and the direction
 * deduced from it would turn at random.
 */
private const val BEARING_MIN_DISTANCE_METERS = 1.5

/**
 * Share of the new bearing taken at each update.
 *
 * Low enough to absorb the jitter from one reading to the next, high enough
 * for a turn to be followed in a few seconds rather than done in one go.
 */
private const val BEARING_SMOOTHING = 0.35f

/** Publishes the current visible area (widened by [VIEWPORT_MARGIN]) to the ViewModel. */
private fun reportViewport(map: MapView, onViewportChanged: (MapViewport) -> Unit) {
    val box = map.boundingBox ?: return

    val latSpan = box.latNorth - box.latSouth
    val lonSpan = box.lonEast - box.lonWest
    if (latSpan <= 0.0) return

    val latMargin = latSpan * VIEWPORT_MARGIN
    var minLon = box.lonWest - lonSpan * VIEWPORT_MARGIN
    var maxLon = box.lonEast + lonSpan * VIEWPORT_MARGIN

    // View straddling the antimeridian or degenerate: we do not try to filter
    // on longitude, selection will be done on latitude alone.
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
 * Variant of [reportViewport] for the periodic polling of automatic following:
 * centers the published area on the known GPS position rather than on the center
 * reported by the map. Only the extent (width/height in degrees) still comes
 * from `map.boundingBox` — it only depends on the zoom, never on an animation.
 *
 * `animateTo()` is called at each new position during following (about once per
 * second), but osmdroid silently rejects any call launched while
 * a previous animation is still running — no error, no retry. A trip
 * by car can thus accumulate ignored calls: the camera lags behind the
 * real position without anything flagging it, and the center that
 * `map.boundingBox` would report would no longer be the one that really needs loading.
 * Rebuilding the area around the GPS position itself — always exact,
 * since it depends on no animation — eliminates this risk.
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

    // View straddling the antimeridian or degenerate: we do not try to filter
    // on longitude, selection will be done on latitude alone.
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
     * Last value actually passed to `setMultiTouchControls`, or null as long
     * as it has never been called. Distinct from `isInteractivityEnabled`, which is
     * only updated with the rebuilding of the layers: here we need to know whether
     * the call happened, not just what the setting was.
     */
    var appliedMultiTouch: Boolean? = null,
    var wasZoomedOut: Boolean = false,
    var bypassZoomThreshold: Boolean = false,
    var cachedOverlayTrackPolylines: List<Polyline>? = null,
    var cachedPointsPolylines: List<Polyline>? = null,
    var lastKnownBearing: Float? = null,
    var mapMode: String = "2d",
    // Appearance chosen by the user: any change invalidates the cached polylines.
    var strokeWidth: Float = 12f,
    /** Original color of the track displayed full screen, if it has one. */
    var sourceColor: Int? = null,
    /** Color chosen by the user for the track displayed full screen. */
    var displayColor: Int? = null,
    /**
     * Icon of the blue position dot, built only once then reused.
     *
     * `drawMarkers` runs at every GPS point received during a recording —
     * about once per second, over its whole duration. Nothing in this icon
     * depends on the current state: rebuilding it (bitmap, canvas, three circles)
     * at each call was just wasted work, for an identical result.
     */
    var blueDotIcon: android.graphics.drawable.Drawable? = null,
    /**
     * Theme colors for markers and the current line, collected in the
     * composition: osmdroid's drawing has no access to MaterialTheme. A
     * change (light ↔ dark switch) empties [markerIcons] and the polylines.
     */
    var themeColors: MarkerColors? = null,
    /** Marker icons already drawn, by color and size. */
    val markerIcons: MutableMap<Long, android.graphics.drawable.Drawable> = mutableMapOf()
)

/** Blue of the position dot (#1A73E8). */
private val POSITION_DOT_BLUE = Color.parseColor("#1A73E8")

/** Map marker colors, taken from the theme. */
internal data class MarkerColors(
    val position: Int,
    val start: Int,
    val end: Int,
    val recording: Int
)

private fun blueDotIcon(context: Context, state: MapState): android.graphics.drawable.Drawable =
    state.blueDotIcon ?: createDotIcon(
        context,
        fill = state.themeColors?.position ?: POSITION_DOT_BLUE,
        sizeDp = 26f,
        ringDp = 4f
    ).also { state.blueDotIcon = it }

/**
 * Chip of a marker (start, finish), drawn once per color then
 * reused: same reason as [MapState.blueDotIcon].
 */
private fun markerIcon(context: Context, state: MapState, fill: Int, sizeDp: Float): android.graphics.drawable.Drawable {
    val key = (fill.toLong() shl 8) or sizeDp.toLong()
    return state.markerIcons.getOrPut(key) { createDotIcon(context, fill, sizeDp, ringDp = 3f) }
}

/**
 * Chip from the mockup: a color disc ringed in white, on a soft shadow
 * that sets it apart from both light and dark tiles.
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

    // Shadow: two translucent discs slightly offset downward.
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

// `lazy`: the label language follows the device's at first use, without
// rebuilding a tile source (and its identity) at every recomposition.
private val GOOGLE_SATELLITE_TILE_SOURCE: OnlineTileSourceBase by lazy {
    val hl = java.util.Locale.getDefault().language.ifEmpty { "en" }
    object : OnlineTileSourceBase(
        "GoogleSatellite",
        0, 20, 256, "",
        arrayOf(
            "https://mt0.google.com/vt/lyrs=y&hl=$hl",
            "https://mt1.google.com/vt/lyrs=y&hl=$hl",
            "https://mt2.google.com/vt/lyrs=y&hl=$hl",
            "https://mt3.google.com/vt/lyrs=y&hl=$hl"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return getBaseUrl() + "&x=" + x + "&y=" + y + "&z=" + zoom
        }
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
    /** Color chosen by the user for the displayed track, null if nothing was chosen. */
    displayColor: Int? = null,
    /** Thickness chosen for the displayed track, in dp, null if nothing was chosen. */
    displayWidthDp: Float? = null,
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
    val scheme = MaterialTheme.colorScheme
    val markerColors = MarkerColors(
        // Google Maps blue, as before the redesign: it is the color the eye
        // looks for to get its bearings, and the theme's green lost it among the
        // map's forests. The start stays in the theme color.
        position = POSITION_DOT_BLUE,
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
                val selectedStyle = prefs.getString("pref_map_style", "mapnik") ?: "mapnik"
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

                // Light ↔ dark switch: icons and the current line change
                // color, so everything drawn from them must be redone.
                val themeChanged = state.themeColors != markerColors
                if (themeChanged) {
                    state.themeColors = markerColors
                    state.markerIcons.clear()
                    state.blueDotIcon = null
                    state.cachedPointsPolylines = null
                }
                val isZoomedOut = isZoomedOutTooMuch && !bypassZoomThreshold

                // `setMultiTouchControls` is not a simple setting: each call
                // replaces osmdroid's MultiTouchController with a new one. Calling it at
                // each recomposition — hence once or twice a second during a
                // recording, at every new GPS position — threw the controller away
                // in the middle of the user's gesture.
                //
                // Yet it is this controller that closes the pinch. osmdroid drives the real
                // zoom level during a pinch (`setMultiTouchScale` computes
                // log2(scale) + the zoom noted when the gesture opened), and only resets the
                // anchor point to null in the end-of-gesture `selectObject`.
                // Controller replaced midway = end of gesture never received:
                // `mMultiTouchScaleCurrentPoint` stays set, and `getProjection()`
                // keeps readjusting all the following projections to it. The map seemed
                // to zoom in or out by itself, long after the finger left
                // the screen — and only in focus mode, the only moment when
                // recompositions chain fast enough to land during a gesture.
                //
                // We therefore only touch this setting when it really changes, as
                // the code just above already does for the tile source.
                if (state.appliedMultiTouch != isInteractivityEnabled) {
                    state.appliedMultiTouch = isInteractivityEnabled
                    map.setMultiTouchControls(isInteractivityEnabled)
                }

                // "auto": the map orients itself in the direction of travel during a
                // recording, and returns to North as soon as it stops. The effective
                // mode is stored in state.mapMode, which triggers the
                // rebuilding of the layers at the moment of the switch.
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

                // Appearance chosen in the settings / the history
                val strokeWidth = TrackStylePreferences.getStrokeWidth(map.context, displayWidthDp)

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

        // Banner when the zoom is too wide to draw tracks without slowing down.
        val hasTracks = points.isNotEmpty() || overlayTracks.any { it.points.isNotEmpty() }
        val showZoomBanner = hasTracks && isZoomedOutTooMuch
        // The banner collapsed into a chip stays collapsed until the next time it
        // reappears: a user who dismissed it does not have to see it again at every gesture.
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
                title = stringResource(R.string.zoom_force_title),
                body = stringResource(R.string.zoom_force_body),
                dismissLabel = stringResource(R.string.common_cancel),
                confirmLabel = stringResource(R.string.zoom_force_confirm),
                confirmContainerColor = MaterialTheme.colorScheme.tertiary,
                confirmContentColor = MaterialTheme.colorScheme.onTertiary,
                confirmTestTag = "confirm_bypass_zoom_button",
                onConfirm = {
                    showWarningDialog = false
                    onBypassZoomThresholdChanged(true)
                }
            )
        }

        // No AnimatedVisibility here: it clips its content, shadow included, during
        // its whole transition (see “The sliced shadow” in CLAUDE.md).
        if (showZoomBanner) {
            val bannerModifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = zoomBannerTopPadding, start = 16.dp, end = 16.dp)
            when {
                bypassZoomThreshold -> MapPill(
                    icon = Icons.Rounded.Visibility,
                    text = stringResource(R.string.zoom_tracks_shown),
                    actionLabel = stringResource(R.string.zoom_hide),
                    onClick = { onBypassZoomThresholdChanged(false) },
                    testTag = "hide_zoom_button",
                    modifier = bannerModifier
                )
                bannerCollapsed -> MapPill(
                    icon = Icons.Rounded.VisibilityOff,
                    text = stringResource(R.string.zoom_tracks_hidden),
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

    // Camera recentering, on pressing the “locate” button (focus mode).
    //
    // This block also forced the zoom, bringing it back to `pref_default_zoom`. That was the
    // reported defect: “when I press locate to go into focus, it zooms
    // in or out by itself, at the moment of the click”. Two reasons not to touch it any more:
    //
    // - `pref_default_zoom` is not a setting chosen by the user: it is the
    //   last zoom stored by the map. But this storing is bounded to once
    //   per second with no catch-up of the last value (see MAP_STATE_PERSIST_MS
    //   and `persistMapStateThrottled`): it therefore almost always keeps a zoom
    //   noted *in the middle* of the last gesture, never the one where the user stopped.
    //   Recentering brought the map back to it, hence the jump forward or backward.
    // - Even if right, this value would not have to be imposed: “locate” means
    //   “center on me”, not “change my scale”.
    //
    // The current zoom is therefore kept. Only exception: a map zoomed out enough that
    // tracks are no longer drawn (ZOOM_THRESHOLD) — recentering there without
    // zooming in would leave the user in front of a whole country, which is not
    // what they ask for by pressing “locate”.
    //
    // `recenterTrigger` stays in the keys to force a new run when the button is
    // tapped again while focus mode is already active (its value alone would not
    // change), but the guard only depends on `isAutoFollowActive`: otherwise
    // the effect also replayed when focus mode turns off, hence right after the
    // manual gesture that has just disengaged it.
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
            // A programmatic move of the map produces no usable scroll
            // event: without this republication, the visible area stays
            // the one from before the recentering.
            kotlinx.coroutines.delay(RECENTER_SETTLE_MS)
            reportViewport(mapView, onViewportChanged)
        }
    }

    /*
     * During automatic following, the map moves by itself as positions come in.
     * As none of these moves republishes the visible area, the ViewModel
     * kept the previous one: `getDisplayPoints` kept returning the detail
     * of the old area, and the newly visible part of a dense track stayed
     * reduced to its silhouette — until a manual drag finally triggered
     * an `onScroll`.
     *
     * The republication is periodic, and not hooked to the recentering: positions
     * arriving about once per second, an effect depending on currentUserLocation
     * would be cancelled before the animation ends and would never publish anything.
     * `MapViewport` being a data class in a StateFlow, an unchanged area
     * triggers no reload.
     *
     * Relying on the center reported by the map is not enough: `animateTo()`, called at
     * each new position, can be rejected by osmdroid if the previous
     * animation is still running — no error, no retry. During a car
     * trip, these ignored calls pile up and the camera lags behind the
     * real position, silently: the polling does repeat, but publishes an
     * area that never finished reaching the place to load — until a
     * manual gesture (drag, zoom) readjusts everything at once via `onScroll`/`onZoom`.
     * `reportAutoFollowViewport` therefore rebuilds the area around the GPS position
     * itself, read via `rememberUpdatedState` to stay current in this long-lived
     * loop — never around a camera center that may have come loose.
     */
    val latestUserLocation by rememberUpdatedState(currentUserLocation)
    val latestIsCurrentTracking by rememberUpdatedState(isCurrentTracking)
    LaunchedEffect(isAutoFollowActive) {
        if (!isAutoFollowActive) return@LaunchedEffect

        // Mark of the last send. As long as the view stays well inside what has
        // already been requested, republishing would only restart an identical read.
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
            // The center of GPS following is the position itself, never the one
            // reported by the camera: `animateTo` may have lagged behind (see
            // reportAutoFollowViewport).
            val centerLat = if (followingGps) loc!!.latitude else (box.latNorth + box.latSouth) / 2.0
            val centerLon = if (followingGps) loc!!.longitude else (box.lonEast + box.lonWest) / 2.0
            val zoom = mapView.zoomLevelDouble

            // A zoom change changes the expected level of detail: we always
            // republish. Skipping this case would leave a zoom-in on the coarse
            // silhouette, exactly the gain in detail it came for.
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
             * Last storing of the map position, on a monotonic clock.
             *
             * osmdroid notifies these two callbacks at every frame of its animations —
             * `MapController.onAnimationUpdate` moves the map about sixty
             * times a second. Saving the position each time amounted to
             * four preference writes per frame, i.e. over two
             * hundred disk accesses per second as long as automatic following animated the
             * map. Once per second is plenty: this value only serves to
             * find the same framing at the next launch.
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

        // First publication of the visible area, once the map is really
        // measured: before layout, boundingBox has no usable value.
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
            // Satellite imagery is left untouched: darkening it would make it
            // unreadable, and an aerial photo has no light background to invert.
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
            title = map.context.getString(R.string.marker_start)
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
            title = map.context.getString(if (state.isCurrentTracking) R.string.marker_current else R.string.marker_end)
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
            title = map.context.getString(R.string.marker_me)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = blueDotIcon(map.context, state)
        }
        map.overlays.add(currentMarker)
    }
}

/**
 * Splits a track into continuous segments, one per polyline to draw.
 *
 * This is what makes a resumed track, paused then restarted, or one resulting from a
 * merge, appear as a single track but with no line joining its pieces.
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

        // A color change cuts the segment even without a declared break: a
        // polyline has only one color, and two neighboring trips of the same KML
        // file would otherwise end up painted in the same stroke.
        val colorChanged = hasPrevious && pt.segmentColor != prevColor

        // The time jump only signals a recording pause if the two points
        // really follow each other. On a dense track shown at reduced detail,
        // the intermediate points are deliberately omitted (ids skip): the time
        // gap is normal there and must not cut the line. Real segment
        // breaks remain carried by isDiscontinuous, always kept for display.
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
 * A piece of line in one go, and the color its original file
 * gave it — null if it gave none.
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
                val overlayStrokeWidth =
                    TrackStylePreferences.getStrokeWidth(map.context, overlayTrack.displayWidthDp)

                for (segment in segments) {
                    if (segment.points.isNotEmpty()) {
                        // The color is decided segment by segment: a single imported
                        // file can gather dozens of trips, each with its
                        // own. A color chosen by hand on the track takes
                        // precedence: it applies to the whole of it, segments included,
                        // otherwise choosing it would not show on a colored KML.
                        val segmentColor = TrackStylePreferences.resolveTrackColor(
                            displayColor = overlayTrack.displayColor,
                            sourceColor = segment.sourceColor ?: overlayTrack.sourceColor,
                            isImported = overlayTrack.isImported,
                            isMerged = overlayTrack.isMerged
                        )

                        // Each overlaid line has its own thickness: that of the main
                        // track ([MapState.strokeWidth]) only applies to it.
                        val polyline = Polyline().apply {
                            outlinePaint.color = segmentColor
                            outlinePaint.strokeWidth = overlayStrokeWidth
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
                    // The theme's recording color wins over everything: it is the
                    // only signal that distinguishes the track currently being written.
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

    // 3. Draw markers. Marker objects are rebuilt at each call (light),
    // but their position icon — costly to draw — is cached
    // in MapState by blueDotIcon(): see its reason for being above.
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
 * Bearing to display, or null if there is nothing new to say about it — the caller then keeps
 * the last known bearing.
 *
 * **A known position is authoritative, and it alone.** Falling back on the recorded
 * points only applies for lack of a GPS position: using them because the position
 * had not changed since the previous pass was a trap. During a recording
 * pause, `points` no longer moves and forever keeps the bearing we had
 * when stopping; the map therefore flipped between the real bearing and this fossilized one,
 * several times a second and over a hundred degrees. The defect is all the
 * more visible as the display is reevaluated often, which has been the case since the
 * visible area is sampled at regular intervals.
 *
 * Returning null when the position has not moved enough is therefore the right answer:
 * better to keep the previous bearing than to invent another.
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
        // Below this threshold, the gap between two readings is due more to GPS noise
        // than to real movement: the bearing derived from it would be random.
        if (dist < BEARING_MIN_DISTANCE_METERS) return null
        return calculateBearing(
            previousLocation.latitude, previousLocation.longitude,
            currentLocation.latitude, currentLocation.longitude
        )
    }

    // No known position: we orient on the end of the displayed track.
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
 * Brings the displayed bearing closer to [target], following the shortest arc.
 *
 * Two reasons not to set the value directly. First the crossing of north:
 * interpolating from 350° to 10° in a straight line would make the map do a full turn
 * for a twenty-degree bend. Then comfort: the bearing from two
 * consecutive positions jitters, and applying it as is gave a map that jolts at
 * every reading instead of turning.
 */
internal fun smoothedBearing(previous: Float?, target: Float): Float {
    if (previous == null) return target
    // Gap brought back into [-180°, 180°]: this is what chooses the rotation direction.
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
 * Banner from the mockup when tracks are hidden for lack of enough zoom:
 * explanation, “Force display” (with confirmation) and “Keep hidden”.
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
                    stringResource(R.string.zoom_banner_title),
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onTertiaryContainer
                )
                Text(
                    stringResource(R.string.zoom_banner_body),
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
                Text(stringResource(R.string.zoom_force_display), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onTertiary)
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
                Text(stringResource(R.string.zoom_keep_hidden), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onTertiaryContainer)
            }
        }
    }
}

/** Floating chip on the map: state of the tracks, and an optional action. */
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
