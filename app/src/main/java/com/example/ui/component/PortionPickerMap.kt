package com.example.ui.component

import android.annotation.SuppressLint
import android.graphics.Color
import android.preference.PreferenceManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.TrackPoint
import com.example.ui.theme.LocalIsDarkTheme
import com.example.util.OsmConfig
import com.example.util.PortionBound
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Carte où l'on désigne une portion de parcours : le tracé, et en rouge la portion comprise
 * entre les bornes [cut]. Un appui sur la carte renvoie, par [onTapTrack], l'endroit
 * touché et les identifiants des points affichés qui encadrent le segment le plus
 * proche : l'appelant y cherche la borne exacte parmi les vrais points, que la carte ne
 * montre pas tous. Un repère est posé sur [start] et sur [end].
 *
 * [points] est le détail de la zone visible, que l'appelant recharge à chaque
 * déplacement signalé par [onViewportChange] : une carte zoomée sur un virage doit en
 * montrer les points, pas la silhouette de tout le parcours. [fitPoints] ne sert qu'à
 * cadrer la carte une fois, à l'ouverture — sauf si [initialBox] donne la zone où l'on
 * regardait en quittant l'outil, que la carte retrouve alors telle quelle.
 *
 * Sert aussi à l'outil « Prolonger » : avec [onTapGeo], un appui renvoie l'endroit
 * touché tel quel, et [sketch] — le tracé dessiné à la main — est dessiné depuis [start]
 * en pointillés, un petit repère sur chaque appui.
 *
 * Une carte à part, et non `MapViewContainer` : celle-là sert le suivi en direct et
 * l'affichage par fenêtre de vue (invariant 3), et reconstruit ses calques d'elle-même
 * au franchissement du seuil de zoom, ce qui effacerait ceux-ci.
 *
 * Posée dans un écran qui défile : chaque geste commencé sur la carte lui est réservé
 * (`requestDisallowInterceptTouchEvent`), sans quoi faire glisser la carte ferait
 * défiler l'écran.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PortionPickerMap(
    points: List<TrackPoint>,
    fitPoints: List<TrackPoint>,
    start: TrackPoint?,
    end: TrackPoint?,
    onTapTrack: (lowId: Long, highId: Long, tap: GeoPoint) -> Unit,
    onViewportChange: (BoundingBox) -> Unit,
    modifier: Modifier = Modifier,
    sketch: List<GeoPoint> = emptyList(),
    onTapGeo: ((GeoPoint) -> Unit)? = null,
    cut: Pair<PortionBound, PortionBound>? = null,
    initialBox: BoundingBox? = null,
    colorOf: ((TrackPoint) -> Int)? = null
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val trackColor = MaterialTheme.colorScheme.primary.toArgb()
    val cutColor = MaterialTheme.colorScheme.error.toArgb()
    val currentOnTap = rememberUpdatedState(onTapTrack)
    val currentOnTapGeo = rememberUpdatedState(onTapGeo)
    val sketchColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val currentOnViewport = rememberUpdatedState(onViewportChange)
    val currentPoints = rememberUpdatedState(points)
    val density = context.resources.displayMetrics.density

    val mapView = remember {
        OsmConfig.init(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        MapView(context).apply {
            setTileSource(
                if (prefs.getString("pref_map_style", "mapnik") == "usgs_sat") GOOGLE_SATELLITE_TILE_SOURCE
                else TileSourceFactory.MAPNIK
            )
            setMultiTouchControls(true)
            // Assez de zoom pour poser une borne au mètre près.
            maxZoomLevel = 21.0
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }

    // La zone visible est signalée une fois le geste fini, pas à chaque image.
    DisposableEffect(mapView) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val report = Runnable {
            if (mapView.width > 0) currentOnViewport.value(mapView.boundingBox)
        }
        val schedule = {
            handler.removeCallbacks(report)
            handler.postDelayed(report, 250)
        }
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                schedule()
                return false
            }

            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                schedule()
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose {
            handler.removeCallbacks(report)
            mapView.removeMapListener(listener)
        }
    }

    // Cadrage unique : la zone quittée s'il y en a une, sinon le parcours entier.
    LaunchedEffect(mapView, fitPoints) {
        if (fitPoints.isEmpty()) return@LaunchedEffect
        val box = BoundingBox.fromGeoPointsSafe(fitPoints.map { GeoPoint(it.latitude, it.longitude) })
        val frame = {
            val resumed = initialBox
            if (resumed != null) {
                mapView.zoomToBoundingBox(resumed, false, 0)
            } else {
                val margin = (32 * density).toInt()
                mapView.zoomToBoundingBox(box.increaseByScale(1.15f), false, margin)
            }
            currentOnViewport.value(mapView.boundingBox)
        }
        if (mapView.width > 0) frame() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> frame() }
    }

    // Le tracé, la portion en rouge et les deux repères suivent le détail et les bornes.
    LaunchedEffect(mapView, points, start, end, cut, trackColor, cutColor, sketch, colorOf) {
        mapView.overlays.clear()
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                val raw = currentOnTapGeo.value
                if (raw != null) raw(p)
                else tapRange(currentPoints.value, p)?.let { (low, high) -> currentOnTap.value(low, high, p) }
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        for (range in segmentRanges(points)) {
            // Les couleurs du parcours, comme sur la carte principale : le père de
            // l'auteur reconnaît ses pays à leur couleur.
            for ((color, run) in colorRuns(points.subList(range.first, range.last + 1), colorOf, trackColor)) {
                val line = styledLine(mapView, color, 4f * density)
                line.setPoints(run.map { GeoPoint(it.latitude, it.longitude) })
                mapView.overlays.add(line)
            }
        }
        if (cut != null) {
            val (low, high) = cut
            // La portion va d'une borne à l'autre, points affichés compris entre les deux.
            val inPortion = listOf(low.point.copy(isDiscontinuous = false)) +
                points.filter { it.id > low.beforeId && it.id < high.afterId } +
                high.point.copy(isDiscontinuous = false)
            for (range in segmentRanges(inPortion)) {
                // Un liseré blanc sous le rouge : sur un tracé déjà rouge, la portion
                // se confondrait sinon avec lui.
                val geo = inPortion.subList(range.first, range.last + 1).map { GeoPoint(it.latitude, it.longitude) }
                mapView.overlays.add(styledLine(mapView, Color.WHITE, 11f * density).apply { setPoints(geo) })
                mapView.overlays.add(styledLine(mapView, cutColor, 7f * density).apply { setPoints(geo) })
            }
        }
        if (sketch.isNotEmpty()) {
            val origin = start?.let { listOf(GeoPoint(it.latitude, it.longitude)) }.orEmpty()
            val line = styledLine(mapView, sketchColor, 5f * density)
            line.outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f * density, 8f * density), 0f)
            line.setPoints(origin + sketch)
            mapView.overlays.add(line)
            sketch.forEach { mapView.overlays.add(dotMarker(mapView, it, sketchColor, density)) }
        }
        start?.let { mapView.overlays.add(boundaryMarker(mapView, it, trackColor, density)) }
        end?.let { mapView.overlays.add(boundaryMarker(mapView, it, cutColor, density)) }
        mapView.invalidate()
    }

    LaunchedEffect(mapView, isDark) {
        val satellite = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("pref_map_style", "mapnik") == "usgs_sat"
        mapView.overlayManager.tilesOverlay.setColorFilter(
            if (isDark && !satellite) DARK_TILES_COLOR_FILTER else null
        )
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

    AndroidView(factory = { mapView }, modifier = modifier.testTag("portion_picker_map"))
}

/** Un rond plein, cerclé de blanc, posé sur la borne [point]. */
private fun boundaryMarker(mapView: MapView, point: TrackPoint, fill: Int, density: Float): Marker =
    Marker(mapView).apply {
        position = GeoPoint(point.latitude, point.longitude)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        val size = (22 * density).toInt()
        icon = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            setStroke((3 * density).toInt(), Color.WHITE)
            setSize(size, size)
        }
        setOnMarkerClickListener { _, _ -> false }
    }

/** Un petit rond plein sur un appui du tracé dessiné. */
private fun dotMarker(mapView: MapView, at: GeoPoint, fill: Int, density: Float): Marker =
    Marker(mapView).apply {
        position = at
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        val size = (12 * density).toInt()
        icon = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            setStroke((2 * density).toInt(), Color.WHITE)
            setSize(size, size)
        }
        setOnMarkerClickListener { _, _ -> false }
    }

/** Une polyligne sans réaction au toucher, du trait et de l'épaisseur donnés. */
private fun styledLine(mapView: MapView, strokeColor: Int, widthPx: Float): Polyline =
    Polyline(mapView).apply {
        setOnClickListener { _, _, _ -> false }
        outlinePaint.apply {
            color = strokeColor
            strokeWidth = widthPx
            strokeCap = android.graphics.Paint.Cap.ROUND
            isAntiAlias = true
        }
    }

/**
 * Les tronçons de [points], en plages d'indices : un point marqué `isDiscontinuous`
 * ouvre le suivant. Les relier d'un trait dessinerait un chemin que personne n'a parcouru.
 */
private fun segmentRanges(points: List<TrackPoint>): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    for (i in 1 until points.size) {
        if (points[i].isDiscontinuous) {
            ranges += start until i
            start = i
        }
    }
    if (points.isNotEmpty()) ranges += start until points.size
    return ranges
}

/**
 * Découpe un tronçon en suites d'une seule couleur, une polyligne n'en portant qu'une.
 * Chaque suite reprend le dernier point de la précédente, pour qu'aucun jour ne
 * s'ouvre entre deux couleurs.
 */
private fun colorRuns(points: List<TrackPoint>, colorOf: ((TrackPoint) -> Int)?, fallback: Int): List<Pair<Int, List<TrackPoint>>> {
    if (colorOf == null || points.isEmpty()) return listOf(fallback to points)
    val runs = mutableListOf<Pair<Int, List<TrackPoint>>>()
    var start = 0
    var color = colorOf(points[0])
    for (i in 1 until points.size) {
        val next = colorOf(points[i])
        if (next != color) {
            runs += color to points.subList(start, i)
            start = i - 1
            color = next
        }
    }
    runs += color to points.subList(start, points.size)
    return runs
}

/**
 * Les identifiants des points affichés qui encadrent le segment le plus proche de
 * [target], élargis d'un voisin de chaque côté : à pas régulier, le segment affiché ne
 * suit le tracé réel qu'à peu près, et le bon segment réel peut tomber juste à côté.
 */
private fun tapRange(points: List<TrackPoint>, target: GeoPoint): Pair<Long, Long>? {
    val bound = PortionBound.nearest(points, target.latitude, target.longitude) ?: return null
    val first = points.indexOfFirst { it.id == bound.beforeId }
    val last = points.indexOfFirst { it.id == bound.afterId }
    if (first < 0 || last < 0) return null
    return points[maxOf(first - 1, 0)].id to points[minOf(last + 1, points.lastIndex)].id
}
