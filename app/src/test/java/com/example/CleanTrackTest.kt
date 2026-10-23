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
 * Le nettoyage crée une copie sans les points entassés au même endroit. Ces tests
 * verrouillent ce sur quoi il repose : la comparaison au dernier point **gardé**, des
 * ruptures de tronçon jamais écartées, des statistiques tirées des seuls points gardés,
 * et aucune ligne cachée laissée derrière un refus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CleanTrackTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    private val start = 1_000_000L
    private val second = 1_000L

    /** Un mètre vers le nord, en degrés de latitude, vers 45° N. */
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

    /** Un parcours dont les points sont à [northMeters] au nord d'une origine fixe, un par seconde. */
    private suspend fun createTrack(
        northMeters: List<Double>,
        breaks: Set<Int> = emptySet(),
        track: Track = Track(name = "Sortie", startTime = start)
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

    /** Toutes les lignes de la table, cachées comprises. */
    private fun trackRowCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** Une marche de 100 m, puis mille points qui tremblent à moins d'un demi-mètre de l'arrivée. */
    private fun walkThenForgotten(): List<Double> =
        (0..20).map { it * 5.0 } + (1..1000).map { 100.0 + (it % 5) * 0.1 }

    // ------------------------------------------------------------------
    // Ce qui est retiré, ce qui reste
    // ------------------------------------------------------------------

    @Test
    fun `les points entassés après l'arrivée sont retirés`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)

        assertEquals("la marche est gardée, tous les 5 m", 21, pointsOf(result.newTrackId).size)
        assertEquals(1000, result.removedPoints)
        assertEquals(21, result.keptPoints)
    }

    @Test
    fun `une lente dérive se compare au dernier point gardé`() = runBlocking {
        // Un demi-mètre à la fois : chaque pas reste sous le seuil, mais pas la dérive
        // entière. Comparer au point précédent écarterait tout.
        val sourceId = createTrack((0..20).map { it * 0.5 })

        val result = repository.removeStationaryPoints(sourceId, 1.9, "Nettoyé", false)

        val kept = pointsOf(result.newTrackId).map { (it.latitude - 45.0) / meter }
        assertEquals("0, 2, 4, 6, 8 et 10 m", 6, kept.size)
        assertEquals(10.0, kept.last(), 0.01)
    }

    @Test
    fun `les ruptures de tronçon sont toujours gardées`() = runBlocking {
        // Le dixième point ouvre un nouveau tronçon au même endroit : il reste, sans
        // quoi deux tronçons seraient recollés.
        val sourceId = createTrack(List(20) { 0.0 }, breaks = setOf(10))

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)

        assertEquals(2, pointsOf(result.newTrackId).size)
    }

    @Test
    fun `le premier point gardé n'est pas une rupture`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten(), breaks = setOf(0))

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)

        assertFalse(pointsOf(result.newTrackId).first().isDiscontinuous)
    }

    // ------------------------------------------------------------------
    // Le parcours d'origine
    // ------------------------------------------------------------------

    @Test
    fun `le parcours d'origine reste intact par défaut`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)

        assertEquals(1021, pointsOf(sourceId).size)
    }

    @Test
    fun `le parcours d'origine est supprimé sur demande`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", true)

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue(pointsOf(sourceId).isEmpty())
    }

    // ------------------------------------------------------------------
    // Refus
    // ------------------------------------------------------------------

    @Test
    fun `rien d'immobile est refusé sans laisser de ligne`() = runBlocking {
        val sourceId = createTrack((0..10).map { it * 5.0 })

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("seul l'original reste", 1, trackRowCount())
        assertEquals(11, pointsOf(sourceId).size)
    }

    @Test
    fun `un nettoyage qui ne laisserait qu'un point est refusé`() = runBlocking {
        val sourceId = createTrack(List(50) { 0.0 })

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, trackRowCount())
        assertNotNull("l'original n'a pas été supprimé", db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `un parcours en cours d'enregistrement ne se nettoie pas`() = runBlocking {
        val sourceId = createTrack(
            walkThenForgotten(),
            track = Track(name = "En cours", startTime = start, isRecording = true)
        )

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, trackRowCount())
    }

    // ------------------------------------------------------------------
    // Statistiques et héritage
    // ------------------------------------------------------------------

    @Test
    fun `les statistiques sont celles des points gardés et le parcours est visible`() = runBlocking {
        val sourceId = createTrack(walkThenForgotten())

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)

        val track = db.trackDao.getTrackById(result.newTrackId)!!
        val expected = repository.calculateStatsFromPoints(pointsOf(result.newTrackId))
        assertEquals(expected.distanceMeters, track.totalDistance, 0.001)
        assertEquals(100.0, track.totalDistance, 0.5)
        assertEquals("finit à l'arrivée, pas des heures après", 20L, track.duration)
        assertEquals("n'est plus cachée comme ligne réservée", start, track.startTime)
        assertEquals(start + 20 * second, track.endTime)
    }

    @Test
    fun `le résultat hérite de l'apparence et de la provenance mais pas de la carte`() = runBlocking {
        val sourceId = createTrack(
            walkThenForgotten(),
            track = Track(
                name = "Importé",
                startTime = start,
                isImported = true,
                isSelectedForMap = true,
                sourceColor = 0xFF112233.toInt(),
                displayColor = 0xFF445566.toInt(),
                displayWidthDp = 9f
            )
        )

        val result = repository.removeStationaryPoints(sourceId, 2.0, "Nettoyé", false)

        val track = db.trackDao.getTrackById(result.newTrackId)!!
        assertEquals("Nettoyé", track.name)
        assertTrue(track.isImported)
        assertFalse(track.isSelectedForMap)
        assertEquals(0xFF112233.toInt(), track.sourceColor)
        assertEquals(0xFF445566.toInt(), track.displayColor)
        assertEquals(9f, track.displayWidthDp)
    }
}
