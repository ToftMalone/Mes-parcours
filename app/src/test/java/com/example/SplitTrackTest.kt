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
 * Splitting creates several tracks from one, by having SQLite copy the
 * points slice by slice. These tests lock down what it rests on:
 * that slices follow one another without losing or duplicating a point, that the
 * statistics of each piece are really those of its own points, and that the
 * original track is only touched if that was requested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SplitTrackTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

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

    /**
     * Creates a track from a description of segments: each element gives
     * the number of points of the segment and the number of seconds separating it from the
     * previous one. The first point of each segment (except the very first) carries the
     * break mark.
     */
    private suspend fun createTrack(
        name: String,
        segments: List<Pair<Int, Long>>
    ): Long {
        val trackId = db.trackDao.insertTrack(Track(name = name, startTime = 1_000_000L))
        val points = mutableListOf<TrackPoint>()
        var clock = 1_000_000L
        var index = 0

        segments.forEachIndexed { segmentIndex, (count, gapSeconds) ->
            clock += gapSeconds * 1000L
            repeat(count) { positionInSegment ->
                points += TrackPoint(
                    trackId = trackId,
                    // About 11 m between two points: above the anti-drift filter.
                    latitude = 45.0 + index / 10_000.0,
                    longitude = 5.0,
                    altitude = 100.0 + index,
                    speed = 1f + index * 0.1f,
                    timestamp = clock,
                    isDiscontinuous = segmentIndex > 0 && positionInSegment == 0
                )
                clock += 1_000L
                index++
            }
        }
        db.trackDao.insertTrackPoints(points)
        return trackId
    }

    private suspend fun pointsOf(trackId: Long): List<TrackPoint> =
        db.trackDao.getPointsForTrack(trackId)

    // ------------------------------------------------------------------
    // Splitting at segment breaks
    // ------------------------------------------------------------------

    @Test
    fun `splitting at segments gives one track per segment`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(3 to 0L, 4 to 0L, 2 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = false
        )

        assertEquals("three segments, three tracks", 3, newIds.size)
        assertEquals(listOf(3, 4, 2), newIds.map { pointsOf(it).size })
        assertEquals(
            listOf("Trips (1)", "Trips (2)", "Trips (3)"),
            newIds.map { db.trackDao.getTrackById(it)!!.name }
        )
    }

    @Test
    fun `no point is lost or duplicated`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(3 to 0L, 4 to 0L, 2 to 0L))
        val original = pointsOf(sourceId)

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = false
        )

        // Glued end to end, the pieces give back exactly the starting track —
        // same coordinates, in the same order.
        val rebuilt = newIds.flatMap { pointsOf(it) }
        assertEquals(original.size, rebuilt.size)
        assertEquals(
            original.map { it.latitude to it.timestamp },
            rebuilt.map { it.latitude to it.timestamp }
        )
    }

    @Test
    fun `the first point of a piece is no longer a break`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(3 to 0L, 4 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = false
        )

        // In its original track this was a break; in the new track
        // it is simply the start of the line. Leaving the mark would open an empty
        // segment before the first point.
        for (id in newIds) {
            assertFalse(pointsOf(id).first().isDiscontinuous)
        }
    }

    @Test
    fun `the original track stays intact by default`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(3 to 0L, 4 to 0L))
        val before = pointsOf(sourceId)

        repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = false
        )

        assertNotNull("the original track still exists", db.trackDao.getTrackById(sourceId))
        assertEquals(before.size, pointsOf(sourceId).size)
        assertTrue("its first break is kept", pointsOf(sourceId)[3].isDiscontinuous)
    }

    @Test
    fun `the original track is deleted when requested`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(3 to 0L, 4 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = true
        )

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue("its points left with it", pointsOf(sourceId).isEmpty())
        // The pieces, for their part, did keep everything.
        assertEquals(7, newIds.sumOf { pointsOf(it).size })
    }

    // ------------------------------------------------------------------
    // Splitting at long pauses
    // ------------------------------------------------------------------

    @Test
    fun `splitting at pauses cuts on time gaps`() = runBlocking {
        // Three blocks separated by an hour, with no segment break at all: it is
        // time, and time alone, that must decide the cuts.
        val trackId = db.trackDao.insertTrack(Track(name = "Outings", startTime = 1_000_000L))
        val points = mutableListOf<TrackPoint>()
        var clock = 1_000_000L
        var index = 0
        repeat(3) { block ->
            if (block > 0) clock += 3_600_000L
            repeat(4) {
                points += TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + index / 10_000.0,
                    longitude = 5.0,
                    timestamp = clock
                )
                clock += 1_000L
                index++
            }
        }
        db.trackDao.insertTrackPoints(points)

        val newIds = repository.splitTrack(
            sourceTrackId = trackId,
            mode = TrackRepository.SplitMode.TIME_GAP,
            gapMillis = 30 * 60_000L,
            baseName = "Outings",
            deleteSource = false
        )

        assertEquals(3, newIds.size)
        assertEquals(listOf(4, 4, 4), newIds.map { pointsOf(it).size })
    }

    @Test
    fun `a pause shorter than the threshold does not cut`() = runBlocking {
        val trackId = db.trackDao.insertTrack(Track(name = "Outing", startTime = 1_000_000L))
        val points = (0 until 6).map { i ->
            TrackPoint(
                trackId = trackId,
                latitude = 45.0 + i / 10_000.0,
                longitude = 5.0,
                // Ten minutes between each point: below the one-hour threshold.
                timestamp = 1_000_000L + i * 600_000L
            )
        }
        db.trackDao.insertTrackPoints(points)

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = trackId,
                mode = TrackRepository.SplitMode.TIME_GAP,
                gapMillis = 60 * 60_000L,
                baseName = "Outing",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue("the split must be refused", failure is IllegalStateException)
    }

    // ------------------------------------------------------------------
    // Refusal
    // ------------------------------------------------------------------

    @Test
    fun `a single-segment track cannot be split`() = runBlocking {
        val sourceId = createTrack("Walk", listOf(5 to 0L))

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = sourceId,
                mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
                gapMillis = 0L,
                baseName = "Walk",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        // Splitting into a single piece would only make a duplicate: the refusal must leave
        // the original track exactly as it was.
        assertEquals(5, pointsOf(sourceId).size)
        assertNotNull(db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `a track without points cannot be split`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(Track(name = "Empty", startTime = 1_000_000L))

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = sourceId,
                mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
                gapMillis = 0L,
                baseName = "Empty",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `a track being recorded cannot be split`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(
            Track(name = "Recording", startTime = 1_000_000L, isRecording = true)
        )

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = sourceId,
                mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
                gapMillis = 0L,
                baseName = "Recording",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    @Test
    fun `each piece carries the statistics of its own points`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(5 to 0L, 6 to 0L, 4 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = false
        )

        // The statistics are accumulated as a stream while reading the original
        // track. They must be exactly those the list computation
        // would give for the same points: that is what guarantees the two
        // paths have not diverged.
        for (id in newIds) {
            val track = db.trackDao.getTrackById(id)!!
            val expected = repository.calculateStatsFromPoints(pointsOf(id))

            assertEquals(expected.distanceMeters, track.totalDistance, 0.001)
            assertEquals(expected.durationSec, track.duration)
            assertEquals(expected.maxSpeedMps, track.maxSpeed, 0.0001)
            assertEquals(expected.avgSpeedMps, track.avgSpeed, 0.0001)
            assertEquals(expected.elevationGain, track.elevationGain, 0.001)
            assertEquals(expected.elevationLoss, track.elevationLoss, 0.001)
        }
    }

    @Test
    fun `the distance of a piece is less than that of the whole track`() = runBlocking {
        val sourceId = createTrack("Trips", listOf(5 to 0L, 6 to 0L))
        val whole = repository.calculateStatsFromPoints(pointsOf(sourceId))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Trips",
            deleteSource = false
        )

        // The trap splitting must avoid: copying onto each piece the statistics of
        // the whole track. Each would then display the total
        // distance, and two pieces added together would be worth double the trip.
        for (id in newIds) {
            val track = db.trackDao.getTrackById(id)!!
            assertTrue(
                "a piece cannot be as long as the whole",
                track.totalDistance < whole.distanceMeters
            )
            assertTrue("but it does have a distance", track.totalDistance > 0.0)
        }
    }

    @Test
    fun `pieces inherit the appearance and origin of the parent`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(
            Track(
                name = "Import",
                startTime = 1_000_000L,
                isImported = true,
                sourceColor = 0xFF123456.toInt(),
                displayColor = 0xFFABCDEF.toInt()
            )
        )
        db.trackDao.insertTrackPoints(
            (0 until 6).map { i ->
                TrackPoint(
                    trackId = sourceId,
                    latitude = 45.0 + i / 10_000.0,
                    longitude = 5.0,
                    timestamp = 1_000_000L + i * 1_000L,
                    isDiscontinuous = i == 3
                )
            }
        )

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Import",
            deleteSource = false
        )

        for (id in newIds) {
            val track = db.trackDao.getTrackById(id)!!
            assertTrue("a piece of an import stays an import", track.isImported)
            assertEquals(0xFF123456.toInt(), track.sourceColor)
            assertEquals(0xFFABCDEF.toInt(), track.displayColor)
            assertFalse("never displayed by default on the map", track.isSelectedForMap)
            assertFalse(track.isRecording)
        }
    }
}
