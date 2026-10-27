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
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline

/**
 * Carte où l'on désigne une portion de parcours : le tracé entier, et en rouge la
 * portion comprise entre [startIndex] et [endIndex] (indices dans [points]). Un appui
 * sur la carte renvoie, par [onTapPoint], l'indice du point du tracé le plus proche.
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
    startIndex: Int,
    endIndex: Int,
    onTapPoint: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = LocalIsDarkTheme.current
    val trackColor = MaterialTheme.colorScheme.primary.toArgb()
    val cutColor = MaterialTheme.colorScheme.error.toArgb()
    val currentOnTap = rememberUpdatedState(onTapPoint)
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
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }

    // Un tracé par tronçon : une rupture ne doit jamais être reliée par un trait.
    val wholeLines = remember(mapView) { mutableListOf<Polyline>() }
    val cutLines = remember(mapView) { mutableListOf<Polyline>() }

    // Le tracé entier, et le cadrage, ne changent qu'avec le parcours.
    LaunchedEffect(mapView, points) {
        mapView.overlays.clear()
        if (points.isEmpty()) return@LaunchedEffect
        wholeLines.clear()
        cutLines.clear()
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                currentOnTap.value(nearestIndex(points, p))
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        val all = mutableListOf<GeoPoint>()
        for (range in segmentRanges(points)) {
            val line = styledLine(mapView, trackColor, 4f * density)
            line.setPoints(points.subList(range.first, range.last + 1).map { GeoPoint(it.latitude, it.longitude) })
            all += line.actualPoints
            wholeLines += line
            mapView.overlays.add(line)
        }

        val box = BoundingBox.fromGeoPointsSafe(all)
        val frame = {
            val margin = (32 * density).toInt()
            mapView.zoomToBoundingBox(box.increaseByScale(1.15f), false, margin)
        }
        if (mapView.width > 0) frame() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> frame() }
    }

    LaunchedEffect(mapView, points, startIndex, endIndex) {
        if (points.isEmpty()) return@LaunchedEffect
        val lo = startIndex.coerceIn(0, points.lastIndex)
        val hi = endIndex.coerceIn(lo, points.lastIndex)
        mapView.overlays.removeAll(cutLines.toSet())
        cutLines.clear()
        for (range in segmentRanges(points)) {
            val from = maxOf(range.first, lo)
            val to = minOf(range.last, hi)
            if (from > to) continue
            val line = styledLine(mapView, cutColor, 7f * density)
            line.setPoints(points.subList(from, to + 1).map { GeoPoint(it.latitude, it.longitude) })
            cutLines += line
            mapView.overlays.add(line)
        }
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

/** Indice du point de [points] le plus proche de [target], à l'échelle d'un écran. */
private fun nearestIndex(points: List<TrackPoint>, target: GeoPoint): Int {
    val cosLat = kotlin.math.cos(Math.toRadians(target.latitude))
    var best = 0
    var bestDistance = Double.MAX_VALUE
    points.forEachIndexed { i, p ->
        val dy = p.latitude - target.latitude
        val dx = (p.longitude - target.longitude) * cosLat
        val d = dx * dx + dy * dy
        if (d < bestDistance) {
            bestDistance = d
            best = i
        }
    }
    return best
}
