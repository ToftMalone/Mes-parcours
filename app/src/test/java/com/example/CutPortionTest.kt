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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le retrait d'une portion choisie sur la carte : ce qui reste avant et après devient
 * un morceau chacun, la portion disparaît, l'original reste intact sauf demande.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CutPortionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    private val start = 1_000_000L

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

    /** Cent points vers le nord, un par seconde, cinq mètres d'écart. */
    private suspend fun createTrack(count: Int = 100): Pair<Long, List<TrackPoint>> {
        val trackId = db.trackDao.insertTrack(Track(name = "Sortie", startTime = start, displayColor = 0xFF445566.toInt()))
        db.trackDao.insertTrackPoints(
            (0 until count).map { i ->
                TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + i * 5 / 111_132.0,
                    longitude = 5.0,
                    timestamp = start + i * 1_000L
                )
            }
        )
        return trackId to db.trackDao.getPointsForTrack(trackId)
    }

    @Test
    fun `la portion du milieu disparaît et laisse deux morceaux`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, points[40].id, points[59].id, "Sortie", false)

        assertEquals(2, ids.size)
        val before = db.trackDao.getPointsForTrack(ids[0])
        val after = db.trackDao.getPointsForTrack(ids[1])
        assertEquals(40, before.size)
        assertEquals(40, after.size)
        assertEquals(points[39].timestamp, before.last().timestamp)
        assertEquals(points[60].timestamp, after.first().timestamp)
        assertFalse(after.first().isDiscontinuous)
        assertEquals("Sortie (1)", db.trackDao.getTrackById(ids[0])!!.name)
        assertEquals(195.0, db.trackDao.getTrackById(ids[0])!!.totalDistance, 1.0)
        assertEquals(0xFF445566.toInt(), db.trackDao.getTrackById(ids[1])!!.displayColor)
        assertEquals("l'original reste", 100, db.trackDao.getPointsForTrack(trackId).size)
    }

    @Test
    fun `une portion au bout ne laisse qu'un morceau, au nom d'origine`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, points[80].id, points[99].id, "Sortie", true)

        assertEquals(1, ids.size)
        assertEquals(80, db.trackDao.getPointsForTrack(ids[0]).size)
        assertEquals("Sortie", db.trackDao.getTrackById(ids[0])!!.name)
        assertNull("l'original est supprimé sur demande", db.trackDao.getTrackById(trackId))
    }

    @Test
    fun `les bornes peuvent être données dans les deux sens`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, points[59].id, points[40].id, "Sortie", false)

        assertEquals(2, ids.size)
    }

    @Test
    fun `retirer tout est refusé`() = runBlocking {
        val (trackId, points) = createTrack()

        val failure = runCatching {
            repository.cutPortion(trackId, points[1].id, points[98].id, "Sortie", true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("rien n'est supprimé", 100, db.trackDao.getPointsForTrack(trackId).size)
    }

    @Test
    fun `l'aperçu reste léger et garde le dernier point`() = runBlocking {
        val (trackId, points) = createTrack(10_000)

        val preview = repository.getPreviewPoints(trackId, maxPoints = 1_000)

        assertTrue(preview.size in 1_000..1_001)
        assertEquals(points.last().id, preview.last().id)
        assertEquals(points.first().id, preview.first().id)
    }
}
