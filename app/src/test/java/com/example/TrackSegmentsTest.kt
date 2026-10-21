package com.example

import com.example.data.model.TrackPoint
import com.example.ui.component.buildSegmentsFromPoints
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A track can contain several segments with no line joining them:
 * this is the case after a pause, after resuming a track, and after a merge.
 * Each segment becomes a distinct polyline on the map.
 *
 * These tests pin down the splitting, including the subtle point that tells a real
 * pause from a simple subsampling at display.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackSegmentsTest {

    /** A point with controlled identifier and timestamp. */
    private fun point(
        id: Long,
        timestampSec: Long,
        discontinuous: Boolean = false
    ) = TrackPoint(
        id = id,
        trackId = 1L,
        latitude = 45.0 + id / 10_000.0,
        longitude = 6.0,
        timestamp = timestampSec * 1000L,
        isDiscontinuous = discontinuous
    )

    @Test
    fun `a continuous track gives a single segment`() {
        val points = (1L..10L).map { point(id = it, timestampSec = it) }
        val segments = buildSegmentsFromPoints(points)

        assertEquals(1, segments.size)
        assertEquals(10, segments[0].points.size)
    }

    @Test
    fun `a pause then a resume cut the line`() {
        // The service marks the first point after the resume as discontinuous.
        val points = listOf(
            point(1, 1), point(2, 2), point(3, 3),
            point(4, 600, discontinuous = true), point(5, 601), point(6, 602)
        )
        val segments = buildSegmentsFromPoints(points)

        assertEquals("two segments expected", 2, segments.size)
        assertEquals(3, segments[0].points.size)
        assertEquals(3, segments[1].points.size)
        // No common point: nothing joins the end of the first to the start of the second.
        assertEquals(6, segments.sumOf { it.points.size })
    }

    @Test
    fun `a merge of three tracks gives three segments`() {
        val points = listOf(
            point(1, 1), point(2, 2),
            point(3, 1_000, discontinuous = true), point(4, 1_001), point(5, 1_002),
            point(6, 5_000, discontinuous = true), point(7, 5_001)
        )
        val segments = buildSegmentsFromPoints(points)

        assertEquals(3, segments.size)
        assertEquals(listOf(2, 3, 2), segments.map { it.points.size })
    }

    @Test
    fun `a discontinuous first point does not create an empty segment`() {
        // The very first point of a track is marked discontinuous by the service.
        val points = listOf(point(1, 1, discontinuous = true), point(2, 2), point(3, 3))
        val segments = buildSegmentsFromPoints(points)

        assertEquals(1, segments.size)
        assertEquals(3, segments[0].points.size)
    }

    @Test
    fun `a long stop on consecutive points cuts the line`() {
        // Break detected by the time gap, even without a flag: the case of tracks
        // recorded before the flag existed.
        val points = listOf(point(1, 1), point(2, 2), point(3, 120), point(4, 121))
        val segments = buildSegmentsFromPoints(points)

        assertEquals(2, segments.size)
        assertEquals(listOf(2, 2), segments.map { it.points.size })
    }

    @Test
    fun `subsampling at display does not cut the line`() {
        // On a dense track, the display only keeps one point in N: the
        // identifiers skip and the time gap is normal. The line must stay
        // continuous, otherwise an imported track would appear dotted.
        val points = (0L until 10L).map { i ->
            point(id = 1 + i * 500, timestampSec = 1 + i * 500)
        }
        val segments = buildSegmentsFromPoints(points)

        assertEquals("the line must stay in one piece", 1, segments.size)
        assertEquals(10, segments[0].points.size)
    }

    @Test
    fun `points without coordinates are ignored`() {
        val points = listOf(
            point(1, 1),
            TrackPoint(id = 2, trackId = 1L, latitude = 0.0, longitude = 0.0, timestamp = 2_000L),
            point(3, 3)
        )
        val segments = buildSegmentsFromPoints(points)

        assertEquals(1, segments.size)
        assertEquals(2, segments[0].points.size)
    }
}
