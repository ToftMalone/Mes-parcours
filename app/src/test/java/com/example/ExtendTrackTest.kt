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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le prolongement dessiné à la main : les points ajoutés se raccordent sans rupture,
 * restent assez rapprochés pour que le trait ne soit jamais coupé, et comptent dans
 * les statistiques.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExtendTrackTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    private val start = 1_000_000L
    private val metersPerDegLat = 111_132.0

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

    /** Cent points vers le nord, un par seconde, à 1,5 m/s ; le premier ouvre le tracé. */
    private suspend fun createTrack(): Long {
        val trackId = db.trackDao.insertTrack(
            Track(name = "Sortie", startTime = start, endTime = start + 99_000L, avgSpeed = 1.5, displayColor = 0xFF445566.toInt())
        )
        db.trackDao.insertTrackPoints(
            (0 until 100).map { i ->
                TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + i * 1.5 / metersPerDegLat,
                    longitude = 5.0,
                    altitude = 300.0,
                    speed = 1.5f,
                    timestamp = start + i * 1_000L,
                    isDiscontinuous = i == 0
                )
            }
        )
        return trackId
    }

    private fun assertNoVisibleGap(points: List<TrackPoint>) {
        assertEquals("aucune rupture", 0, points.count { it.isDiscontinuous })
        points.zipWithNext().forEach { (a, b) ->
            assertTrue("heures croissantes", b.timestamp > a.timestamp)
            assertTrue("moins de quinze secondes entre voisins", b.timestamp - a.timestamp <= 15_000L)
        }
    }

    @Test
    fun `prolonger à la fin ajoute la suite, raccordée et sans trou`() = runBlocking {
        val trackId = createTrack()
        val last = db.trackDao.getPointsForTrack(trackId).last()
        // 300 m plus au nord, en deux appuis.
        val taps = listOf(
            last.latitude + 150 / metersPerDegLat to 5.0,
            last.latitude + 300 / metersPerDegLat to 5.0
        )

        val newId = repository.extendTrack(trackId, taps, atEnd = true, newName = "Sortie", deleteSource = true)

        val points = db.trackDao.getPointsForTrack(newId)
        assertTrue("les appuis ont été redécoupés", points.size > 102)
        assertEquals(taps.last().first, points.last().latitude, 1e-9)
        assertEquals(300.0, points.last().altitude, 0.0)
        assertNoVisibleGap(points)
        val track = db.trackDao.getTrackById(newId)!!
        assertEquals("148,5 m enregistrés + 300 m dessinés", 448.5, track.totalDistance, 2.0)
        assertEquals("300 m à 1,5 m/s : 200 s de plus", 299.0, track.duration.toDouble(), 1.0)
        assertEquals(0xFF445566.toInt(), track.displayColor)
        assertNotNull("l'original part à la corbeille", db.trackDao.getTrackById(trackId)?.deletedAt)
    }

    @Test
    fun `prolonger au début place les points avant et efface l'ancienne rupture`() = runBlocking {
        val trackId = createTrack()
        val first = db.trackDao.getPointsForTrack(trackId).first()
        val taps = listOf(first.latitude - 100 / metersPerDegLat to 5.0)

        val newId = repository.extendTrack(trackId, taps, atEnd = false, newName = "Sortie", deleteSource = false)

        val points = db.trackDao.getPointsForTrack(newId)
        assertEquals(taps.first().first, points.first().latitude, 1e-9)
        assertEquals(first.latitude, points.first { it.timestamp == start }.latitude, 1e-12)
        assertNoVisibleGap(points)
        assertEquals(248.5, db.trackDao.getTrackById(newId)!!.totalDistance, 2.0)
        assertNull("l'original est gardé sur demande", db.trackDao.getTrackById(trackId)!!.deletedAt)
    }

    @Test
    fun `sans appui, rien n'est créé`() = runBlocking {
        val trackId = createTrack()

        val failure = runCatching {
            repository.extendTrack(trackId, emptyList(), atEnd = true, newName = "Sortie", deleteSource = true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("aucun point ajouté à l'original", 100, db.trackDao.getPointsForTrack(trackId).size)
    }
}
