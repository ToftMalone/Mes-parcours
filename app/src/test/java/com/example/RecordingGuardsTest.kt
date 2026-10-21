package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.repository.TrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Safeguards around the recording in progress and the state it leaves in the database:
 * each fixes a data loss or a wrong state found in review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecordingGuardsTest {

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

    private suspend fun createTrack(
        name: String,
        startTime: Long,
        count: Int,
        isRecording: Boolean = false,
        speed: Float = 2f
    ): Long {
        val trackId = db.trackDao.insertTrack(
            Track(name = name, startTime = startTime, isRecording = isRecording)
        )
        db.trackDao.insertTrackPoints((0 until count).map { i ->
            TrackPoint(
                trackId = trackId,
                latitude = 45.0 + i / 10_000.0,
                longitude = 5.0,
                speed = speed,
                timestamp = startTime + i * 1000L
            )
        })
        return trackId
    }

    @Test
    fun `the track being recorded cannot be deleted`() = runBlocking {
        val recording = createTrack("en cours", startTime = 1_000L, count = 3, isRecording = true)

        assertFalse(repository.deleteTrack(recording))
        assertNotNull(db.trackDao.getTrackById(recording))
        assertEquals(3, db.trackDao.getPointsForTrack(recording).size)
    }

    @Test
    fun `a finished track is deleted with its points`() = runBlocking {
        val done = createTrack("fini", startTime = 1_000L, count = 3)

        assertTrue(repository.deleteTrack(done))
        assertNull(db.trackDao.getTrackById(done))
        assertTrue(db.trackDao.getPointsForTrack(done).isEmpty())
    }

    @Test
    fun `merging refuses the track being recorded`() = runBlocking {
        val old = createTrack("ancien", startTime = 1_000L, count = 3)
        val recording = createTrack("en cours", startTime = 9_000L, count = 3, isRecording = true)

        try {
            repository.mergeAndSaveTracks(listOf(old, recording), old, "Fusion")
            fail("The merge should have been refused")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // Nothing moved: both tracks and their points are intact.
        assertNotNull(db.trackDao.getTrackById(recording))
        assertEquals(3, db.trackDao.getPointsForTrack(recording).size)
        assertEquals(3, db.trackDao.getPointsForTrack(old).size)
    }

    @Test
    fun `the pause is stored in the database and lifted at the end`() = runBlocking {
        val recording = createTrack("en cours", startTime = 1_000L, count = 3, isRecording = true)

        repository.setTrackPaused(recording, true)
        assertTrue(db.trackDao.getActiveRecordingTrack()!!.isPaused)

        repository.finishTracking(recording, repository.liveStats.value)
        val finished = db.trackDao.getTrackById(recording)!!
        assertFalse(finished.isRecording)
        assertFalse(finished.isPaused)
    }

    @Test
    fun `resuming a paused track lifts the pause`() = runBlocking {
        val track = createTrack("ancien", startTime = 1_000L, count = 3)
        repository.setTrackPaused(track, true)

        assertTrue(repository.resumeExistingTrack(track))
        assertFalse(db.trackDao.getTrackById(track)!!.isPaused)
    }

    @Test
    fun `the resume carries what is needed to extend the average speed`() = runBlocking {
        val track = createTrack("ancien", startTime = 1_000L, count = 5, speed = 3f)

        val resume = repository.loadResumeState(db.trackDao.getTrackById(track)!!)
        // The first point brings no speed (see TrackStatsAccumulator).
        assertEquals(4, resume.speedSamples)
        assertEquals(12.0, resume.speedSumMps, 1e-6)
        assertEquals(3.0, resume.stats.avgSpeedMps, 1e-6)
    }

    @Test
    fun `the reserved row of an import is hidden then cleaned up`() = runBlocking {
        val visible = createTrack("visible", startTime = 1_000L, count = 2)
        val placeholder = db.trackDao.insertTrack(
            Track(name = "Import in progress", startTime = Track.IMPORT_PLACEHOLDER_START_TIME, isImported = true)
        )
        db.trackDao.insertTrackPoints(listOf(TrackPoint(trackId = placeholder, latitude = 1.0, longitude = 1.0, timestamp = 0L)))

        assertEquals(listOf(visible), repository.allTracks.first().map { it.id })

        repository.cleanupInterruptedImports()
        assertNull(db.trackDao.getTrackById(placeholder))
        assertTrue(db.trackDao.getPointsForTrack(placeholder).isEmpty())
        assertNotNull(db.trackDao.getTrackById(visible))
    }
}
