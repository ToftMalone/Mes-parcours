package com.example.data.model

/**
 * Geographic area currently visible on the map, already widened by a margin
 * by the caller so that small movements do not empty the track.
 */
data class MapViewport(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val zoom: Double
) {
    val latSpan: Double get() = maxLat - minLat
    val lonSpan: Double get() = maxLon - minLon
}
