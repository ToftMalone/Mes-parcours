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
 * Le nettoyage réduit chaque arrêt à un seul point, sans rupture de tracé. Ces tests
 * verrouillent ce sur quoi il repose : un gribouillis de bruit GPS qui disparaît en
 * entier, une marche laissée intacte, des ruptures de tronçon jamais écartées, des
 * statistiques tirées des seuls points gardés, et aucune ligne cachée laissée derrière
 * un refus.
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
        track: Track = Track(name = "Sortie", startTime = start),
        secondsPerPoint: Long = 1
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
                    timestamp = start + i * secondsPerPoint * second,
                    isDiscontinuous = i in breaks
                )
            }
        )
        return trackId
    }

    private suspend fun pointsOf(trackId: Long): List<TrackPoint> =
        db.trackDao.getPointsForTrack(trackId)

    private fun TrackPoint.north(): Double = (latitude - 45.0) / meter

    /** Toutes les lignes de la table, cachées comprises. */
    private fun trackRowCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM tracks").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** Une marche, 5 m par seconde, de [from] à [to] mètres. */
    private fun walk(from: Int, to: Int): List<Double> =
        (from..to step 5).map { it.toDouble() }

    /** [seconds] secondes de bruit GPS sous un toit : des sauts jusqu'à 8 m autour de [at]. */
    private fun noise(at: Double, seconds: Int): List<Double> =
        (1..seconds).map { at + ((it * 7) % 17 - 8) }

    // ------------------------------------------------------------------
    // Ce qui est réduit, ce qui reste
    // ------------------------------------------------------------------

    @Test
    fun `l'enregistrement oublié à l'arrivée devient un seul point`() = runBlocking {
        val sourceId = createTrack(walk(0, 100) + noise(100.0, 3600))

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        val kept = pointsOf(result.newTrackId)
        assertEquals(1, result.stops)
        assertTrue("la marche reste, plus un point : ${kept.size}", kept.size in 12..22)
        assertEquals("le point d'arrêt est au centre du bruit", 100.0, kept.last().north(), 2.0)
        assertTrue("aucune rupture de tracé", kept.none { it.isDiscontinuous })
    }

    @Test
    fun `la marche autour d'un arrêt est gardée point pour point`() = runBlocking {
        val before = walk(0, 300)
        val after = walk(305, 600)
        val sourceId = createTrack(before + noise(300.0, 900) + after)

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        val kept = pointsOf(result.newTrackId).map { it.north() }
        // Seuls les derniers et premiers mètres, à moins du rayon de l'arrêt, s'y fondent.
        val farFromStop = (before + after).filter { kotlin.math.abs(it - 300.0) > 35 }
        farFromStop.forEach { north ->
            assertTrue("$north m est gardé", kept.any { kotlin.math.abs(it - north) < 0.01 })
        }
        assertEquals(1, result.stops)
        assertEquals(0.0, kept.first(), 0.01)
        assertEquals(600.0, kept.last(), 0.01)
        assertTrue(pointsOf(result.newTrackId).none { it.isDiscontinuous })
    }

    @Test
    fun `un saut aberrant du GPS ne coupe pas l'arrêt`() = runBlocking {
        // Toutes les cent secondes, une position à 80 m : l'arrêt doit rester d'un tenant.
        val noisy = noise(100.0, 1200).mapIndexed { i, north -> if (i % 100 == 50) north + 80 else north }
        val sourceId = createTrack(walk(0, 100) + noisy)

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        assertEquals(1, result.stops)
        assertTrue(pointsOf(result.newTrackId).none { it.north() > 150 })
    }

    @Test
    fun `un arrêt plus court que la durée choisie reste tel quel`() = runBlocking {
        val sourceId = createTrack(walk(0, 100) + noise(100.0, 60) + walk(105, 200))

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("seul l'original reste", 1, trackRowCount())
    }

    @Test
    fun `les ruptures de tronçon sont toujours gardées`() = runBlocking {
        // Le même arrêt, coupé par une rupture : deux arrêts, jamais recollés.
        val sourceId = createTrack(noise(0.0, 1200), breaks = setOf(600))

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        val kept = pointsOf(result.newTrackId)
        assertEquals(2, result.stops)
        assertEquals(2, kept.size)
        assertTrue(kept[1].isDiscontinuous)
    }

    @Test
    fun `le premier point gardé n'est pas une rupture`() = runBlocking {
        val sourceId = createTrack(walk(0, 100) + noise(100.0, 600), breaks = setOf(0))

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        assertFalse(pointsOf(result.newTrackId).first().isDiscontinuous)
    }

    // ------------------------------------------------------------------
    // Le parcours d'origine
    // ------------------------------------------------------------------

    @Test
    fun `le parcours d'origine reste intact par défaut`() = runBlocking {
        val sourceId = createTrack(walk(0, 100) + noise(100.0, 1000))

        repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        assertEquals(1021, pointsOf(sourceId).size)
    }

    @Test
    fun `le parcours d'origine est supprimé sur demande`() = runBlocking {
        val sourceId = createTrack(walk(0, 100) + noise(100.0, 1000))

        repository.removeStationaryPoints(sourceId, 5, "Nettoyé", true)

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue(pointsOf(sourceId).isEmpty())
    }

    // ------------------------------------------------------------------
    // Refus
    // ------------------------------------------------------------------

    @Test
    fun `rien d'immobile est refusé sans laisser de ligne`() = runBlocking {
        val sourceId = createTrack(walk(0, 50))

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("seul l'original reste", 1, trackRowCount())
        assertEquals(11, pointsOf(sourceId).size)
    }

    @Test
    fun `un nettoyage qui ne laisserait qu'un point est refusé`() = runBlocking {
        val sourceId = createTrack(noise(0.0, 600))

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 5, "Nettoyé", true)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, trackRowCount())
        assertNotNull("l'original n'a pas été supprimé", db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `un parcours en cours d'enregistrement ne se nettoie pas`() = runBlocking {
        val sourceId = createTrack(
            walk(0, 100) + noise(100.0, 1000),
            track = Track(name = "En cours", startTime = start, isRecording = true)
        )

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(1, trackRowCount())
    }

    @Test
    fun `un fichier sans horodatage n'a pas d'arrêt et ne bloque pas`() = runBlocking {
        // Un GPX sans <time> : tous les points à la même heure, la durée n'est jamais atteinte.
        val sourceId = createTrack(noise(0.0, 30_000), secondsPerPoint = 0)

        val failure = runCatching {
            repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, trackRowCount())
    }

    // ------------------------------------------------------------------
    // Statistiques et héritage
    // ------------------------------------------------------------------

    @Test
    fun `les statistiques sont celles des points gardés et le parcours est visible`() = runBlocking {
        val sourceId = createTrack(walk(0, 100) + noise(100.0, 1000))

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        val track = db.trackDao.getTrackById(result.newTrackId)!!
        val expected = repository.calculateStatsFromPoints(pointsOf(result.newTrackId))
        assertEquals(expected.distanceMeters, track.totalDistance, 0.001)
        assertEquals(100.0, track.totalDistance, 5.0)
        assertTrue("finit à l'arrivée, pas une heure après : ${track.duration}", track.duration <= 20L)
        assertEquals("n'est plus cachée comme ligne réservée", start, track.startTime)
        assertEquals(pointsOf(result.newTrackId).last().timestamp, track.endTime)
    }

    @Test
    fun `le résultat hérite de l'apparence et de la provenance mais pas de la carte`() = runBlocking {
        val sourceId = createTrack(
            walk(0, 100) + noise(100.0, 1000),
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

        val result = repository.removeStationaryPoints(sourceId, 5, "Nettoyé", false)

        val track = db.trackDao.getTrackById(result.newTrackId)!!
        assertEquals("Nettoyé", track.name)
        assertTrue(track.isImported)
        assertFalse(track.isSelectedForMap)
        assertEquals(0xFF112233.toInt(), track.sourceColor)
        assertEquals(0xFF445566.toInt(), track.displayColor)
        assertEquals(9f, track.displayWidthDp)
    }
}
