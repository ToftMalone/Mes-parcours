package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.repository.TrackRepository
import com.example.util.PortionBound
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

    private fun at(point: TrackPoint) = PortionBound.at(point)

    @Test
    fun `la portion du milieu disparaît et le reste tient en un seul parcours`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, at(points[40]), at(points[59]), "Sortie", false)

        assertEquals(1, ids.size)
        val kept = db.trackDao.getPointsForTrack(ids[0])
        assertEquals("les deux bornes restent, chacune au bout de son côté", 82, kept.size)
        assertEquals(points[40].timestamp, kept[40].timestamp)
        assertEquals(points[59].timestamp, kept[41].timestamp)
        assertFalse("le début n'ouvre pas de rupture", kept.first().isDiscontinuous)
        assertTrue("la suite ouvre un nouveau tronçon", kept[41].isDiscontinuous)
        assertEquals(1, kept.count { it.isDiscontinuous })
        assertEquals("Sortie", db.trackDao.getTrackById(ids[0])!!.name)
        // 40 + 40 intervalles de cinq mètres : rien n'est compté à travers le trou.
        assertEquals(400.0, db.trackDao.getTrackById(ids[0])!!.totalDistance, 1.0)
        assertEquals(0xFF445566.toInt(), db.trackDao.getTrackById(ids[0])!!.displayColor)
        assertEquals("l'original reste", 100, db.trackDao.getPointsForTrack(trackId).size)
    }

    @Test
    fun `une portion au bout ne laisse que le début, sans rupture`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, at(points[80]), at(points[99]), "Sortie", true)

        assertEquals(1, ids.size)
        val kept = db.trackDao.getPointsForTrack(ids[0])
        assertEquals(81, kept.size)
        assertEquals(points[80].timestamp, kept.last().timestamp)
        assertEquals(0, kept.count { it.isDiscontinuous })
        assertNotNull("l'original part à la corbeille sur demande", db.trackDao.getTrackById(trackId)?.deletedAt)
    }

    @Test
    fun `une portion au début ne laisse que la fin, sans rupture`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, at(points[0]), at(points[19]), "Sortie", false)

        val kept = db.trackDao.getPointsForTrack(ids[0])
        assertEquals(81, kept.size)
        assertEquals(points[19].timestamp, kept.first().timestamp)
        assertEquals(0, kept.count { it.isDiscontinuous })
    }

    @Test
    fun `les bornes peuvent être données dans les deux sens`() = runBlocking {
        val (trackId, points) = createTrack()

        val ids = repository.cutPortion(trackId, at(points[59]), at(points[40]), "Sortie", false)

        assertEquals(82, db.trackDao.getPointsForTrack(ids[0]).size)
    }

    @Test
    fun `tout retirer est refusé`() = runBlocking {
        val (trackId, points) = createTrack()

        val failure = runCatching {
            repository.cutPortion(trackId, at(points[0]), at(points[99]), "Sortie", true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("rien n'est supprimé", 100, db.trackDao.getPointsForTrack(trackId).size)
    }

    @Test
    fun `la coupe tombe là où l'on a touché, même entre deux points`() = runBlocking {
        val (trackId, points) = createTrack()
        val step = 5 / 111_132.0

        // Deux appuis à mi-chemin entre deux points, un peu à côté du tracé.
        val from = repository.findPortionBound(trackId, points[38].id, points[45].id, 45.0 + 40.5 * step, 5.00002)!!
        val to = repository.findPortionBound(trackId, points[55].id, points[62].id, 45.0 + 58.5 * step, 4.99998)!!

        assertEquals(points[40].id, from.beforeId)
        assertEquals(points[41].id, from.afterId)
        assertEquals(0.5, from.fraction, 0.05)
        assertEquals(points[58].id, to.beforeId)

        val ids = repository.cutPortion(trackId, from, to, "Sortie", false)
        val kept = db.trackDao.getPointsForTrack(ids[0])
        assertEquals("deux points fabriqués aux bornes", 84, kept.size)
        assertEquals(45.0 + 40.5 * step, kept[41].latitude, 1e-7)
        assertEquals(5.0, kept[41].longitude, 1e-9)
        assertEquals(45.0 + 58.5 * step, kept[42].latitude, 1e-7)
        assertTrue("la suite repart de la fin, en ouvrant un tronçon", kept[42].isDiscontinuous)
        assertEquals(1, kept.count { it.isDiscontinuous })
        assertEquals(405.0, db.trackDao.getTrackById(ids[0])!!.totalDistance, 1.0)
        assertEquals(90.0, repository.measurePortion(trackId, from, to), 0.5)
    }

    @Test
    fun `un appui tout près d'un point se pose sur lui`() = runBlocking {
        val (trackId, points) = createTrack()
        val step = 5 / 111_132.0

        val bound = repository.findPortionBound(trackId, points[10].id, points[20].id, 45.0 + 15.05 * step, 5.0)!!

        assertTrue(bound.isVertex)
        assertEquals(points[15].id, bound.point.id)
    }

    @Test
    fun `l'aperçu reste léger et garde le dernier point`() = runBlocking {
        val (trackId, points) = createTrack(10_000)

        val preview = repository.getPreviewPoints(trackId, maxPoints = 1_000)

        assertTrue(preview.size in 1_000..1_001)
        assertEquals(points.last().id, preview.last().id)
        assertEquals(points.first().id, preview.first().id)
    }

    @Test
    fun `l'aperçu marque un trou de temps comme une rupture`() = runBlocking {
        val trackId = db.trackDao.insertTrack(Track(name = "Trou", startTime = start))
        db.trackDao.insertTrackPoints(
            (0 until 1_000).map { i ->
                // Un trou de dix minutes au point 500, sans rupture déclarée.
                val delay = if (i >= 500) 600_000L else 0L
                TrackPoint(
                    trackId = trackId,
                    latitude = 45.0 + i * 5 / 111_132.0,
                    longitude = 5.0,
                    timestamp = start + i * 1_000L + delay
                )
            }
        )
        val points = db.trackDao.getPointsForTrack(trackId)

        val preview = repository.getPreviewPoints(trackId, maxPoints = 100)

        val after = preview.first { it.id == points[500].id }
        assertTrue("le point après le trou ouvre un tronçon", after.isDiscontinuous)
        assertEquals(
            "le point d'avant le trou est gardé",
            points[499].id,
            preview[preview.indexOf(after) - 1].id
        )
    }

    @Test
    fun `zoomé, le sélecteur rend tous les points de la zone`() = runBlocking {
        val (trackId, points) = createTrack(10_000)
        // Une zone de cent points, au milieu : le détail doit en rendre cent, pas une silhouette.
        val zone = points.subList(5_000, 5_100)

        val detail = repository.getPickerPoints(
            trackId, zone.first().latitude, zone.last().latitude, 4.99, 5.01
        )

        assertEquals(100, detail.size)
        assertEquals(zone.first().id, detail.first().id)
        assertFalse(detail.drop(1).any { it.isDiscontinuous })
    }

    @Test
    fun `le sélecteur coupe le trait là où le tracé sort de la zone`() = runBlocking {
        val trackId = db.trackDao.insertTrack(Track(name = "Aller-retour", startTime = start))
        // Le tracé monte vers le nord, sort de la zone, puis y revient plus loin : les deux
        // morceaux visibles ne doivent pas être reliés.
        val lats = (0 until 30).map { 45.0 + it * 5 / 111_132.0 } +
            (0 until 30).map { 45.5 + it * 5 / 111_132.0 } +
            (0 until 30).map { 45.0 + (30 + it) * 5 / 111_132.0 }
        db.trackDao.insertTrackPoints(
            lats.mapIndexed { i, lat ->
                TrackPoint(trackId = trackId, latitude = lat, longitude = 5.0, timestamp = start + i * 1_000L)
            }
        )

        val detail = repository.getPickerPoints(trackId, 44.99, 45.01, 4.99, 5.01)

        assertEquals(60, detail.size)
        assertEquals("le premier point de la reprise ouvre un tronçon", 1, detail.count { it.isDiscontinuous })
    }

    @Test
    fun `la distance d'une portion s'additionne point à point`() = runBlocking {
        val (trackId, points) = createTrack()

        val meters = repository.measurePortion(trackId, at(points[40]), at(points[59]))

        assertEquals(19 * 5.0, meters, 1.0)
    }
}
