package com.example.util

import android.location.Location
import com.example.data.model.LiveStats
import com.example.data.model.TrackPoint

/**
 * Accumulates the statistics of a track **point by point**, without ever holding the
 * complete list.
 *
 * This is the only implementation of the computation: `TrackRepository.calculateStatsFromPoints`
 * is now just a call to this one on a list already in memory. Two
 * parallel implementations — one for lists, one for streams — would end up
 * diverging, and two identical tracks would then display different figures
 * depending on the path taken.
 *
 * Feeding as a stream is what lets splitting compute the statistics of
 * each piece during its single read of the original track, with no size cap
 * and no second pass.
 */
class TrackStatsAccumulator {

    private val elevation = ElevationAccumulator()

    /** Reused at each measurement: `distanceBetween` writes its result into it. */
    private val distanceResult = FloatArray(1)

    private var first: TrackPoint? = null
    private var previous: TrackPoint? = null
    private var count = 0

    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0
    /** Sum of the speeds retained for the average: enough to extend it elsewhere. */
    var speedSumMps = 0.0
        private set

    /** Number of readings counted in [speedSumMps]. */
    val speedSamples: Int get() = speedPoints
    private var speedPoints = 0

    /** Adds a point. Points must arrive in track order. */
    fun add(point: TrackPoint) {
        val prev = previous
        if (prev == null) {
            // First point: it opens the reference altitude, but provides neither
            // distance nor speed — it has no predecessor to measure against.
            first = point
            elevation.add(point.altitude)
        } else {
            if (point.isDiscontinuous) {
                // Segment break: neither the straight-line distance from the
                // previous segment nor the altitude jump must be counted.
                elevation.breakSegment()
            } else {
                Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    point.latitude, point.longitude,
                    distanceResult
                )
                val meters = distanceResult[0].toDouble()
                // Anti-drift filter: under a meter, it is GPS noise at
                // rest, not a movement.
                if (meters > 1.0) distanceMeters += meters
            }
            elevation.add(point.altitude)

            val speed = point.speed.toDouble()
            if (speed > maxSpeedMps) maxSpeedMps = speed
            if (speed > 0.1) {
                speedSumMps += speed
                speedPoints++
            }
        }
        previous = point
        count++
    }

    /** Statistics of the points received so far. Can be called at any time. */
    fun result(): LiveStats {
        val start = first ?: return LiveStats()
        val end = previous ?: return LiveStats()

        val elapsedSeconds = if (count >= 2) (end.timestamp - start.timestamp) / 1000L else 0L

        return LiveStats(
            durationSec = maxOf(0L, elapsedSeconds),
            distanceMeters = distanceMeters,
            currentSpeedMps = end.speed.toDouble(),
            avgSpeedMps = if (speedPoints > 0) speedSumMps / speedPoints else 0.0,
            maxSpeedMps = maxSpeedMps,
            elevationGain = elevation.gainMeters,
            elevationLoss = elevation.lossMeters
        )
    }
}
