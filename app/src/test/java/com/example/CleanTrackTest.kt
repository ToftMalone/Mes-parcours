package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.repository.TrackRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Removing stationary points creates a copy without the points piled up in one spot.
 * These tests lock down what it rests on: comparison with the last **kept** point,
 * segment breaks never dropped, statistics of the kept points only, and no hidden
 * row left behind when it is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CleanTrackTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    private val start = 1_000_000L
    private val second = 1_000L

    /** One meter northward, in degrees of latitude, around 45° N. */
    private val meter = 1.0 / 111_132.0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TrackRepository.createForTesting(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A track whose points lie [northMeters] north of a fixed origin, one per second. */
    private suspend fun createTrack(
        northMeters: List<Double>,
        breaks: Set<Int> = emptySet(),
        track: Track = Track(name = "Outing", startTime = start)
    ): Long {
        val trackId = db.trackDao.insertTrack(track)
        db.trackDao.insertTrackPoints(
            northMeters.mapIndexed { i, north ->
                TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + north * meter,
                    longitude = 5.0,
                    altitude = 100.0,
                    speed = 1f,
                    timestamp = start + i * second,
                    isDiscontinuous = i in breaks
                )
            }
        )
        return trackId
    }

    private suspend fun pointsOf(trackId: Long): List<TrackPoint> =
        db.trackDao.getPointsForTrack(trackId)

    /** Every row of the table, the hidden ones included. */
    private fun trackRowCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** A 100 m walk, then a thousand points jittering within half a meter of the arrival. */
    private fun walkThenForgotten(): List<Double> =
        (0..20).map { it * 5.0 } + (1..1000).map { 100.0 + (it % 5) * 0.1 }

    // ------------------------------------------------------------------
    // What is removed, what remains
    // ------------------------------------------------------------------

    @Test
    fun `points piled up after arrival are removed`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)

        assertEquals("the walk is kept, every 5 m", 21, pointsOf(result.newTrackId).size)
        assertEquals(1000, result.removedPoints)
        assertEquals(21, result.keptPoints)
    }

    @Test
    fun `a slow drift is compared with the last kept point`() = runBlocking {
        // Half a meter at a time: each step stays under the threshold, but the drift
        // as a whole does not. Comparing with the previous point would drop everything.
        val sourceId = createTrack((0..20).map { it * 0.5 })

        val result = repository.removeStationaryPoints(sourceId, 1.9, "Cleaned", false)

        val kept = pointsOf(result.newTrackId).map { (it.latitude - 45.0) / meter }
        assertEquals("0, 2, 4, 6, 8 and 10 m", 6, kept.size)
        assertEquals(10.0, kept.last(), 0.01)
    }

    @Test
    fun `segment breaks are always kept`() = runBlocking {
        // The tenth point opens a new segment at the same spot: it stays, otherwise
        // two segments would be glued together.
        val sourceId = createTrack(List(20) { 0.0 }, breaks = setOf(10))

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)

        assertEquals(2, pointsOf(result.newTrackId).size)
    }

    @Test
    fun `the first kept point is not a break`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten(), breaks = setOf(0))

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)

        assertFalse(pointsOf(result.newTrackId).first().isDiscontinuous)
    }

    // ------------------------------------------------------------------
    // The original track
    // ------------------------------------------------------------------

    @Test
    fun `the original track stays intact by default`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)

        assertEquals(1021, pointsOf(sourceId).size)
    }

    @Test
    fun `the original track is deleted when requested`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", true)

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue(pointsOf(sourceId).isEmpty())
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    fun `nothing stationary is refused and leaves no row behind`() = runBlocking {
        val sourceId = createTrack((0..10).map { it * 5.0 })

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("only the original remains", 1, trackRowCount())
        assertEquals(11, pointsOf(sourceId).size)
    }

    @Test
    fun `a cleanup that would leave a single point is refused`() = runBlocking {
        val sourceId = createTrack(List(50) { 0.0 })

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, trackRowCount())
        assertNotNull("the original was not deleted", db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `a track being recorded cannot be cleaned`() = runBlocking {
        val sourceId = createTrack(
            walkThenForgotten(),
            track = Track(name = "Recording", startTime = start, isRecording = true)
        )

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, trackRowCount())
    }

    // ------------------------------------------------------------------
    // Statistics and inheritance
    // ------------------------------------------------------------------

    @Test
    fun `statistics are those of the kept points and the track is visible`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)

        val track = db.trackDao.getTrackById(result.newTrackId)!!
        val expected = repository.calculateStatsFromPoints(pointsOf(result.newTrackId))
        assertEquals(expected.distanceMeters, track.totalDistance, 0.001)
        assertEquals(100.0, track.totalDistance, 0.5)
        assertEquals("ends on arrival, not hours later", 20L, track.duration)
        assertEquals("no longer hidden as a reserved row", start, track.startTime)
        assertEquals(start + 20 * second, track.endTime)
    }

    @Test
    fun `the result inherits the appearance and origin but is not shown on the map`() = runBlocking {
        val sourceId = createTrack(
            walkThenForgotten(),
            track = Track(
                name = "Imported",
                startTime = start,
                isImported = true,
                isSelectedForMap = true,
                sourceColor = 0xFF112233.toInt(),
                displayColor = 0xFF445566.toInt(),
                displayWidthDp = 9f
            )
        )

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Cleaned", false)

        val track = db.trackDao.getTrackById(result.newTrackId)!!
        assertEquals("Cleaned", track.name)
        assertTrue(track.isImported)
        assertFalse(track.isSelectedForMap)
        assertEquals(0xFF112233.toInt(), track.sourceColor)
        assertEquals(0xFF445566.toInt(), track.displayColor)
        assertEquals(9f, track.displayWidthDp)
    }
}
