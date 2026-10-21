package com.example.data.model

/**
 * Count and geographic extent of a track's points.
 *
 * Used to calibrate the viewport-based display without ever loading the whole
 * track. The fields are nullable because SQL aggregates are NULL for a
 * track with no points at all.
 */
data class TrackPointMeta(
    val minId: Long?,
    val pointCount: Int,
    val minLat: Double?,
    val maxLat: Double?,
    val minLon: Double?,
    val maxLon: Double?
)
