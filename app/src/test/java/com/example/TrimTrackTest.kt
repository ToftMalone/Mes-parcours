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
 * Le rognage crée une copie amputée de son début et de sa fin. Ces tests verrouillent
 * ce sur quoi il repose : que les bornes se lisent sur les **horodatages** et non sur
 * un rang, que les statistiques du résultat sont celles de ses propres points, et que
 * le parcours d'origine n'est touché que si on l'a demandé.
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

    /** Un parcours d'un point par minute, sur [minutes] minutes. */
    private suspend fun createTrack(minutes: Int, name: String = "Sortie"): Long {
        val trackId = db.trackDao.insertTrack(Track(name = name, startTime = start))
        db.trackDao.insertTrackPoints(
            (0 until minutes).map { i ->
                TrackPoint(
                    trackId = trackId,
                    // Environ 11 m entre deux points : au-dessus du filtre anti-dérive.
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
    // Ce qui est retiré, ce qui reste
    // ------------------------------------------------------------------

    @Test
    fun `le debut est retire`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 0L,
            newName = "Rogné",
            deleteSource = false
        )

        val kept = pointsOf(newId)
        assertEquals("dix points moins les trois premiers", 7, kept.size)
        assertEquals(start + 3 * minute, kept.first().timestamp)
        assertEquals(start + 9 * minute, kept.last().timestamp)
    }

    @Test
    fun `la fin est retiree`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 0L,
            dropEndMillis = 4 * minute,
            newName = "Rogné",
            deleteSource = false
        )

        val kept = pointsOf(newId)
        assertEquals("dix points moins les quatre derniers", 6, kept.size)
        assertEquals(start, kept.first().timestamp)
        assertEquals(start + 5 * minute, kept.last().timestamp)
    }

    @Test
    fun `les deux bouts sont retires ensemble`() = runBlocking {
        val sourceId = createTrack(minutes = 20)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 5 * minute,
            dropEndMillis = 5 * minute,
            newName = "Rogné",
            deleteSource = false
        )

        val kept = pointsOf(newId)
        assertEquals(10, kept.size)
        assertEquals(start + 5 * minute, kept.first().timestamp)
        assertEquals(start + 14 * minute, kept.last().timestamp)
    }

    @Test
    fun `les bornes se lisent sur le temps et non sur le rang`() = runBlocking {
        // Un parcours à trou : deux points, puis une heure de silence, puis huit
        // points. Retirer « les dix premières minutes » ne doit retirer que les deux
        // premiers points — un rognage compté en nombre de points en aurait pris dix.
        val trackId = db.trackDao.insertTrack(Track(name = "À trou", startTime = start))
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
            newName = "Rogné",
            deleteSource = false
        )

        assertEquals("seuls les deux points d'avant le trou partent", 8, pointsOf(newId).size)
    }

    @Test
    fun `le premier point conserve n est pas une rupture`() = runBlocking {
        // Le point qui devient premier ouvrait un tronçon dans le parcours d'origine :
        // dans le parcours neuf, il ne fait qu'ouvrir le tracé.
        val trackId = db.trackDao.insertTrack(Track(name = "Tronçons", startTime = start))
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
            newName = "Rogné",
            deleteSource = false
        )

        assertFalse(pointsOf(newId).first().isDiscontinuous)
    }

    // ------------------------------------------------------------------
    // Le parcours d'origine
    // ------------------------------------------------------------------

    @Test
    fun `le parcours d origine reste intact par defaut`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 3 * minute,
            newName = "Rogné",
            deleteSource = false
        )

        assertNotNull(db.trackDao.getTrackById(sourceId))
        assertEquals("il garde tous ses points", 10, pointsOf(sourceId).size)
    }

    @Test
    fun `le parcours d origine est supprime si on le demande`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 3 * minute,
            dropEndMillis = 0L,
            newName = "Rogné",
            deleteSource = true
        )

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue(pointsOf(sourceId).isEmpty())
        assertEquals("la copie, elle, a bien ses points", 7, pointsOf(newId).size)
    }

    // ------------------------------------------------------------------
    // Refus
    // ------------------------------------------------------------------

    @Test
    fun `ne rien retirer est refuse`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val failure = runCatching {
            repository.trimTrack(sourceId, 0L, 0L, "Rogné", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("rien n'a été créé en base", 10, pointsOf(sourceId).size)
    }

    @Test
    fun `retirer plus que la duree du parcours est refuse`() = runBlocking {
        val sourceId = createTrack(minutes = 10)

        val failure = runCatching {
            repository.trimTrack(sourceId, 20 * minute, 0L, "Rogné", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNotNull("l'original est intact", db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `un rognage qui ne laisserait qu un point est refuse`() = runBlocking {
        // Trois points, un long trou, puis un point isolé. Retirer les cinq premières
        // minutes laisse ce seul point final : ni distance ni durée à en tirer.
        //
        // Il faut un parcours à trou pour atteindre ce garde-fou : sur des points
        // régulièrement espacés, tout rognage assez large pour n'en laisser qu'un fait
        // se croiser les deux bornes, et c'est le refus précédent qui se déclenche.
        val trackId = db.trackDao.insertTrack(Track(name = "Isolé", startTime = start))
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
            repository.trimTrack(trackId, 5 * minute, 0L, "Rogné", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNotNull("l'original est intact", db.trackDao.getTrackById(trackId))
    }

    @Test
    fun `un parcours en cours d enregistrement n est pas rognable`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(
            Track(name = "En cours", startTime = start, isRecording = true)
        )

        val failure = runCatching {
            repository.trimTrack(sourceId, minute, 0L, "Rogné", false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    // ------------------------------------------------------------------
    // Statistiques et héritage
    // ------------------------------------------------------------------

    @Test
    fun `les statistiques sont celles des seuls points conserves`() = runBlocking {
        val sourceId = createTrack(minutes = 20)

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 5 * minute,
            dropEndMillis = 5 * minute,
            newName = "Rogné",
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
    fun `le parcours rogne est plus court que l original`() = runBlocking {
        val sourceId = createTrack(minutes = 20)
        val whole = repository.calculateStatsFromPoints(pointsOf(sourceId))

        val newId = repository.trimTrack(
            sourceTrackId = sourceId,
            dropStartMillis = 5 * minute,
            dropEndMillis = 5 * minute,
            newName = "Rogné",
            deleteSource = false
        )

        val track = db.trackDao.getTrackById(newId)!!
        // Le piège que le rognage doit éviter : recopier les statistiques du parent,
        // qui donneraient au résultat la distance du trajet entier.
        assertTrue(track.totalDistance < whole.distanceMeters)
        assertTrue("mais il a bien une distance", track.totalDistance > 0.0)
        assertTrue(track.duration < 20 * 60)
    }

    @Test
    fun `le resultat herite de l apparence et de la provenance`() = runBlocking {
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
            newName = "Rogné",
            deleteSource = false
        )

        val track = db.trackDao.getTrackById(newId)!!
        assertTrue("un rognage d'import reste un import", track.isImported)
        assertEquals(0xFF123456.toInt(), track.sourceColor)
        assertEquals(0xFFABCDEF.toInt(), track.displayColor)
        assertFalse("jamais affiché d'office sur la carte", track.isSelectedForMap)
        assertFalse(track.isRecording)
        assertEquals("Rogné", track.name)
    }
}
