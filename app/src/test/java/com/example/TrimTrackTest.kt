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
 * Trimming creates a copy cut of its start and end. These tests lock down
 * what it rests on: that bounds are read on **timestamps** and not on
 * a rank, that the result's statistics are those of its own points, and that
 * the original track is only touched if that was requested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrimTrackTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    private val start = 1_000_000L
    private val minute = 60_000L

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

    /** A track of one point per minute, over [minutes] minutes. */
    private suspend fun createTrack(minutes: Int, name: String = "Outing"): Long {
        val trackId = db.trackDao.insertTrack(Track(name = name, startTime = start))
        db.trackDao.insertTrackPoints(
            (0 until minutes).map { i ->
                TrackPoint(
                    trackId = trackId,
                    // About 11 m between two points: above the anti-drift filter.
                    latitude = 45.0 + i / 10_000.0,
                    longitude = 5.0,
                    altitude = 100.0 + i,
                    speed = 1f + i * 0.1f,
                    timestamp = start + i * minute
                )
            }
        )
        return trackId
    }

    private suspend fun pointsOf(trackId: Long): List<TrackPoint> =
        db.trackDao.getPointsForTrack(trackId)

    // ------------------------------------------------------------------
    // What is removed, what remains
    // ------------------------------------------------------------------

    @Test
    fun `the start is removed`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 0L,
            newName = "Trimmed",
            deleteSource = false
        )

        val kept = pointsOf(newId)
        assertEquals("ten points minus the first three", 7, kept.size)
        assertEquals(start + 3 * minute, kept.first().timestamp)
        assertEquals(start + 9 * minute, kept.last().timestamp)
    }

    @Test
    fun `the end is removed`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 0L,
            dropEndMillis = 4 * minute,
            newName = "Trimmed",
            deleteSource = false
        )

        val kept = pointsOf(newId)
        assertEquals("ten points minus the last four", 6, kept.size)
        assertEquals(start, kept.first().timestamp)
        assertEquals(start + 5 * minute, kept.last().timestamp)
    }

    @Test
    fun `both ends are removed together`() = runBlocking {
        val sourceId = createTrack(minutes = 20)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 5 * minute,
            dropEndMillis = 5 * minute,
            newName = "Trimmed",
            deleteSource = false
        )

        val kept = pointsOf(newId)
        assertEquals(10, kept.size)
        assertEquals(start + 5 * minute, kept.first().timestamp)
        assertEquals(start + 14 * minute, kept.last().timestamp)
    }

    @Test
    fun `bounds are read on time and not on rank`() = runBlocking {
        // A track with a gap: two points, then an hour of silence, then eight
        // points. Removing “the first ten minutes” must only remove the first
        // two points — a trim counted in number of points would have taken ten.
        val trackId = db.trackDao.insertTrack(Track(name = "Gappy", startTime = start))
        val points = mutableListOf<TrackPoint>()
        var clock = start
        repeat(2) { i ->
            points += TrackPoint(
                trackId = trackId,
                latitude = 45.0 + i / 10_000.0,
                longitude = 5.0,
                timestamp = clock
            )
            clock += minute
        }
        clock += 60 * minute
        repeat(8) { i ->
            points += TrackPoint(
                trackId = trackId,
                latitude = 45.1 + i / 10_000.0,
                longitude = 5.0,
                timestamp = clock
            )
            clock += minute
        }
        db.trackDao.insertTrackPoints(points)

        val newId = repository.trimTrack(
            sourceTrackId = trackId,
            dropStartMillis = 10 * minute,
            dropEndMillis = 0L,
            newName = "Trimmed",
            deleteSource = false
        )

        assertEquals("only the two points before the gap go", 8, pointsOf(newId).size)
    }

    @Test
    fun `the first kept point is not a break`() = runBlocking {
        // The point that becomes first opened a segment in the original track:
        // in the new track, it only opens the line.
        val trackId = db.trackDao.insertTrack(Track(name = "Segments", startTime = start))
        db.trackDao.insertTrackPoints(
            (0 until 10).map { i ->
                TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + i / 10_000.0,
                    longitude = 5.0,
                    timestamp = start + i * minute,
                    isDiscontinuous = i == 3
                )
            }
        )

        val newId = repository.trimTrack(
            sourceTrackId = trackId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 0L,
            newName = "Trimmed",
            deleteSource = false
        )

        assertFalse(pointsOf(newId).first().isDiscontinuous)
    }

    // ------------------------------------------------------------------
    // The original track
    // ------------------------------------------------------------------

    @Test
    fun `the original track stays intact by default`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 3 * minute,
            newName = "Trimmed",
            deleteSource = false
        )

        assertNotNull(db.trackDao.getTrackById(sourceId))
        assertEquals("it keeps all its points", 10, pointsOf(sourceId).size)
    }

    @Test
    fun `the original track is deleted when requested`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 0L,
            newName = "Trimmed",
            deleteSource = true
        )

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue(pointsOf(sourceId).isEmpty())
        assertEquals("the copy, for its part, has its points", 7, pointsOf(newId).size)
    }

    // ------------------------------------------------------------------
    // Refusal
    // ------------------------------------------------------------------

    @Test
    fun `removing nothing is refused`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val failure = runCatching {
            repository.trimTrack(sourceId, 0L, 0L, "Trimmed", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("nothing was created in the database", 10, pointsOf(sourceId).size)
    }

    @Test
    fun `removing more than the track's duration is refused`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val failure = runCatching {
            repository.trimTrack(sourceId, 20 * minute, 0L, "Trimmed", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNotNull("the original is intact", db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `a trim that would leave a single point is refused`() = runBlocking {
        // Three points, a long gap, then an isolated point. Removing the first five
        // minutes leaves that single final point: neither distance nor duration to draw from it.
        //
        // A track with a gap is needed to reach this safeguard: on evenly spaced
        // points, any trim wide enough to leave only one makes the two bounds
        // cross, and it is the previous refusal that triggers.
        val trackId = db.trackDao.insertTrack(Track(name = "Isolated", startTime = start))
        db.trackDao.insertTrackPoints(
            listOf(0L, 1L, 2L, 20L).mapIndexed { i, m ->
                TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + i / 10_000.0,
                    longitude = 5.0,
                    timestamp = start + m * minute
                )
            }
        )

        val failure = runCatching {
            repository.trimTrack(trackId, 5 * minute, 0L, "Trimmed", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNotNull("the original is intact", db.trackDao.getTrackById(trackId))
    }

    @Test
    fun `a track being recorded cannot be trimmed`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(
            Track(name = "Recording", startTime = start, isRecording = true)
        )

        val failure = runCatching {
            repository.trimTrack(sourceId, minute, 0L, "Trimmed", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    // ------------------------------------------------------------------
    // Statistics and inheritance
    // ------------------------------------------------------------------

    @Test
    fun `statistics are those of the kept points only`() = runBlocking {
        val sourceId = createTrack(minutes = 20)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 5 * minute,
            dropEndMillis = 5 * minute,
            newName = "Trimmed",
            deleteSource = false
        )

        val track = db.trackDao.getTrackById(newId)!!
        val expected = repository.calculateStatsFromPoints(pointsOf(newId))

        assertEquals(expected.distanceMeters, track.totalDistance, 0.001)
        assertEquals(expected.durationSec, track.duration)
        assertEquals(expected.maxSpeedMps, track.maxSpeed, 0.0001)
        assertEquals(expected.avgSpeedMps, track.avgSpeed, 0.0001)
        assertEquals(expected.elevationGain, track.elevationGain, 0.001)
        assertEquals(expected.elevationLoss, track.elevationLoss, 0.001)
    }

    @Test
    fun `the trimmed track is shorter than the original`() = runBlocking {
        val sourceId = createTrack(minutes = 20)
        val whole = repository.calculateStatsFromPoints(pointsOf(sourceId))

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 5 * minute,
            dropEndMillis = 5 * minute,
            newName = "Trimmed",
            deleteSource = false
        )

        val track = db.trackDao.getTrackById(newId)!!
        // The trap trimming must avoid: copying the parent's statistics,
        // which would give the result the distance of the whole trip.
        assertTrue(track.totalDistance < whole.distanceMeters)
        assertTrue("but it does have a distance", track.totalDistance > 0.0)
        assertTrue(track.duration < 20 * 60)
    }

    @Test
    fun `the result inherits the appearance and origin`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(
            Track(
                name = "Import",
                startTime = start,
                isImported = true,
                sourceColor = 0xFF123456.toInt(),
                displayColor = 0xFFABCDEF.toInt()
            )
        )
        db.trackDao.insertTrackPoints(
            (0 until 10).map { i ->
                TrackPoint(
                    trackId = sourceId,
                    latitude = 45.0 + i / 10_000.0,
                    longitude = 5.0,
                    timestamp = start + i * minute
                )
            }
        )

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 2 * minute,
            dropEndMillis = 0L,
            newName = "Trimmed",
            deleteSource = false
        )

        val track = db.trackDao.getTrackById(newId)!!
        assertTrue("a trim of an import stays an import", track.isImported)
        assertEquals(0xFF123456.toInt(), track.sourceColor)
        assertEquals(0xFFABCDEF.toInt(), track.displayColor)
        assertFalse("never displayed by default on the map", track.isSelectedForMap)
        assertFalse(track.isRecording)
        assertEquals("Trimmed", track.name)
    }
}
