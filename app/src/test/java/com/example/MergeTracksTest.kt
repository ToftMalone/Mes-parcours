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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La fusion supprime définitivement des parcours et recopie leurs points via
 * SQLite. Ces tests vérifient les deux hypothèses sur lesquelles elle repose :
 * que Room accepte un INSERT … SELECT, et que l'ordre imposé par le ORDER BY est
 * bien celui dans lequel les nouvelles lignes reçoivent leur identifiant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MergeTracksTest {

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

    /** Crée un parcours dont les points sont numérotés de façon reconnaissable. */
    private suspend fun createTrack(name: String, startTime: Long, marker: Double, count: Int): Long {
        val trackId = db.trackDao.insertTrack(
            Track(name = name, startTime = startTime, endTime = startTime + count * 1000L)
        )
        val points = (0 until count).map { i ->
            TrackPoint(
                trackId = trackId,
                latitude = marker + i / 10_000.0,
                longitude = marker,
                timestamp = startTime + i * 1000L
            )
        }
        db.trackDao.insertTrackPoints(points)
        return trackId
    }

    @Test
    fun `fusion respecte l ordre chronologique des parcours`() = runBlocking {
        // Le plus ancien est créé en dernier : ses identifiants de points sont donc
        // les plus élevés. Si la fusion se contentait de l'ordre des id, il finirait
        // à la fin au lieu du début.
        val recent = createTrack("recent", startTime = 3_000_000L, marker = 3.0, count = 4)
        val middle = createTrack("milieu", startTime = 2_000_000L, marker = 2.0, count = 3)
        val oldest = createTrack("ancien", startTime = 1_000_000L, marker = 1.0, count = 5)

        val mergedId = repository.mergeAndSaveTracks(
            trackIds = listOf(recent, middle, oldest),
            destinationTrackId = recent,
            mergedName = "Fusionne"
        )
        assertEquals(recent, mergedId)

        val points = db.trackDao.getPointsForTrack(mergedId)
        assertEquals(12, points.size)

        // Les longitudes servent de marqueur d'origine : 1.0 puis 2.0 puis 3.0.
        val order = points.map { it.longitude }
        assertEquals(
            listOf(1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 3.0, 3.0, 3.0, 3.0),
            order
        )

        // À l'intérieur d'un parcours, l'ordre d'origine est conservé.
        val oldestLats = points.filter { it.longitude == 1.0 }.map { it.latitude }
        assertEquals(oldestLats.sorted(), oldestLats)

        // Les identifiants restent strictement croissants.
        val ids = points.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun `chaque parcours ajoute ouvre un nouveau troncon`() = runBlocking {
        val first = createTrack("a", startTime = 1_000_000L, marker = 1.0, count = 3)
        val second = createTrack("b", startTime = 2_000_000L, marker = 2.0, count = 3)

        repository.mergeAndSaveTracks(listOf(first, second), first, "Fusionne")

        val points = db.trackDao.getPointsForTrack(first)
        assertEquals(6, points.size)

        // Pas de rupture en tête : le premier point ouvre naturellement le tracé.
        assertTrue(!points[0].isDiscontinuous)
        // Rupture exactement au raccord, pour ne pas relier les deux parcours.
        assertTrue(points[3].isDiscontinuous)
        assertEquals(1, points.count { it.isDiscontinuous })
    }

    @Test
    fun `les parcours sources sont supprimes et la destination conserve sa categorie`() = runBlocking {
        val destination = db.trackDao.insertTrack(
            Track(name = "dest", startTime = 1_000_000L, isImported = true)
        )
        db.trackDao.insertTrackPoints(
            listOf(TrackPoint(trackId = destination, latitude = 1.0, longitude = 1.0, timestamp = 1_000_000L))
        )
        val source = createTrack("source", startTime = 2_000_000L, marker = 2.0, count = 2)

        repository.mergeAndSaveTracks(listOf(destination, source), destination, "Nom final")

        assertEquals(null, db.trackDao.getTrackById(source))
        assertEquals(0, db.trackDao.getPointsForTrack(source).size)

        val merged = db.trackDao.getTrackById(destination)!!
        assertEquals("Nom final", merged.name)
        assertTrue("la destination importee doit le rester", merged.isImported)
        assertEquals(3, db.trackDao.getPointsForTrack(destination).size)
    }

    @Test
    fun `le resultat d une fusion est marque comme fusionne`() = runBlocking {
        // C'est ce marquage seul qui range le parcours dans l'onglet « Fusionnés » de
        // l'historique, et qui l'y range *à la place* de sa catégorie d'origine. Sans
        // lui, la fusion se perd parmi les parcours dont elle est issue.
        val first = createTrack("a", startTime = 1_000_000L, marker = 1.0, count = 2)
        val second = createTrack("b", startTime = 2_000_000L, marker = 2.0, count = 2)

        repository.mergeAndSaveTracks(listOf(first, second), first, "Fusion")

        val merged = db.trackDao.getTrackById(first)!!
        assertTrue("le résultat doit porter la catégorie « fusionné »", merged.isMerged)
    }

    @Test
    fun `un parcours sans point ne perturbe pas la fusion`() = runBlocking {
        val empty = db.trackDao.insertTrack(Track(name = "vide", startTime = 1_000_000L))
        val withPoints = createTrack("plein", startTime = 2_000_000L, marker = 2.0, count = 3)

        repository.mergeAndSaveTracks(listOf(empty, withPoints), withPoints, "Fusionne")

        val points = db.trackDao.getPointsForTrack(withPoints)
        assertEquals(3, points.size)
        // Aucun tronçon supplémentaire ouvert par le parcours vide.
        assertEquals(0, points.count { it.isDiscontinuous })
    }
}
