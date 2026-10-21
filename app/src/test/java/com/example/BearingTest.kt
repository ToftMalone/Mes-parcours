package com.example

import com.example.data.model.TrackPoint
import com.example.ui.component.computeCurrentBearing
import com.example.ui.component.smoothedBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Orientation of the map in 3D mode.
 *
 * These tests pin down the point that made the map spin on itself: the bearing must
 * never be deduced from the recorded track as long as a GPS position is known.
 * During a recording pause, that track is frozen and keeps the bearing it had
 * when stopping; the map flipped between the two several times a second.
 *
 * Robolectric is needed for `Location.distanceBetween`, an Android method.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BearingTest {

    private fun point(lat: Double, lon: Double) =
        TrackPoint(trackId = 1, latitude = lat, longitude = lon, timestamp = 0L)

    /** About 110 m to the north, well beyond the movement threshold. */
    private val south = point(48.8500, 2.3500)
    private val north = point(48.8510, 2.3500)

    /** A frozen track pointing due east — the “fossilized” bearing of a pause. */
    private val frozenEastwardTrack = listOf(
        point(48.8000, 2.3000),
        point(48.8000, 2.3020)
    )

    @Test
    fun `the bearing follows the movement between two positions`() {
        val bearing = computeCurrentBearing(emptyList(), north, south)!!
        assertEquals("due north", 0f, bearing, 1f)
    }

    @Test
    fun `an unchanged position produces no bearing`() {
        // The heart of the fix: returning null lets the caller keep the last known
        // bearing, instead of fetching one from the recorded track.
        assertNull(computeCurrentBearing(frozenEastwardTrack, north, north))
    }

    @Test
    fun `a frozen track never dictates the bearing while a position is known`() {
        // The exact case of a paused recording: the track points east, the
        // car goes north. Trusting the track made the map swing by 90°.
        val bearing = computeCurrentBearing(frozenEastwardTrack, north, south)!!
        assertEquals("the real movement wins", 0f, bearing, 1f)

        // And without movement, no bearing at all — above all not the track's.
        assertNull(computeCurrentBearing(frozenEastwardTrack, north, north))
    }

    @Test
    fun `a movement under the threshold produces no bearing`() {
        // A few tens of centimeters: that is GPS noise, not a direction.
        val barelyMoved = point(48.8500, 2.35001)
        assertNull(computeCurrentBearing(emptyList(), barelyMoved, south))
    }

    @Test
    fun `without a known position the displayed track serves as a fallback`() {
        // The only case where the track has a say: browsing without GPS.
        val bearing = computeCurrentBearing(frozenEastwardTrack, null, null)!!
        assertEquals("due east", 90f, bearing, 2f)
    }

    // ------------------------------------------------------------------ smoothing

    @Test
    fun `the first bearing is adopted as is`() {
        assertEquals(42f, smoothedBearing(null, 42f), 0.01f)
    }

    @Test
    fun `smoothing approaches the target without reaching it at once`() {
        val result = smoothedBearing(0f, 100f)
        assertTrue("moves toward the target", result > 0f)
        assertTrue("but does not reach it immediately", result < 100f)
    }

    @Test
    fun `crossing north takes the shortest arc`() {
        // From 350° to 10°: twenty degrees via north, and not three hundred and forty
        // the other way. Without this the map did a full turn.
        val result = smoothedBearing(350f, 10f)
        val movedForward = (result - 350f + 360f) % 360f
        assertTrue("turns the short way", movedForward in 0f..20f)
    }

    @Test
    fun `smoothing stays within the range of angles`() {
        // A negative value or one beyond 360° would disorient the map.
        for (target in listOf(0f, 45f, 180f, 270f, 359f)) {
            for (previous in listOf(0f, 90f, 200f, 355f)) {
                val result = smoothedBearing(previous, target)
                assertTrue("valid angle: $result", result >= 0f && result < 360f)
            }
        }
    }

    @Test
    fun `repeated bearings converge toward the target`() {
        var bearing = 0f
        repeat(20) { bearing = smoothedBearing(bearing, 90f) }
        assertEquals("ends up aligned", 90f, bearing, 1f)
    }
}
