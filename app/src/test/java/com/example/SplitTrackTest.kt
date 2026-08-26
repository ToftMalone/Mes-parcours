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
 * Le découpage crée plusieurs parcours à partir d'un seul, en faisant recopier les
 * points par SQLite tranche par tranche. Ces tests verrouillent ce sur quoi il
 * repose : que les tranches se suivent sans perdre ni dupliquer un point, que les
 * statistiques de chaque morceau sont bien celles de ses propres points, et que le
 * parcours d'origine n'est touché que si on l'a demandé.
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
     * Crée un parcours à partir d'une description de tronçons : chaque élément donne
     * le nombre de points du tronçon et le nombre de secondes qui le sépare du
     * précédent. Le premier point de chaque tronçon (sauf le tout premier) porte la
     * marque de rupture.
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
                    // Environ 11 m entre deux points : au-dessus du filtre anti-dérive.
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
    // Découpage aux ruptures de tronçon
    // ------------------------------------------------------------------

    @Test
    fun `le decoupage aux troncons rend un parcours par troncon`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(3 to 0L, 4 to 0L, 2 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = false
        )

        assertEquals("trois tronçons, trois parcours", 3, newIds.size)
        assertEquals(listOf(3, 4, 2), newIds.map { pointsOf(it).size })
        assertEquals(
            listOf("Voyages (1)", "Voyages (2)", "Voyages (3)"),
            newIds.map { db.trackDao.getTrackById(it)!!.name }
        )
    }

    @Test
    fun `aucun point n est perdu ni duplique`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(3 to 0L, 4 to 0L, 2 to 0L))
        val original = pointsOf(sourceId)

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = false
        )

        // Recollés bout à bout, les morceaux redonnent exactement le parcours de
        // départ — mêmes coordonnées, dans le même ordre.
        val rebuilt = newIds.flatMap { pointsOf(it) }
        assertEquals(original.size, rebuilt.size)
        assertEquals(
            original.map { it.latitude to it.timestamp },
            rebuilt.map { it.latitude to it.timestamp }
        )
    }

    @Test
    fun `le premier point d un morceau n est plus une rupture`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(3 to 0L, 4 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = false
        )

        // Dans son parcours d'origine c'était une rupture ; dans le parcours neuf
        // c'est simplement le début du tracé. Laisser la marque ouvrirait un tronçon
        // vide avant le premier point.
        for (id in newIds) {
            assertFalse(pointsOf(id).first().isDiscontinuous)
        }
    }

    @Test
    fun `le parcours d origine reste intact par defaut`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(3 to 0L, 4 to 0L))
        val before = pointsOf(sourceId)

        repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = false
        )

        assertNotNull("le parcours d'origine existe toujours", db.trackDao.getTrackById(sourceId))
        assertEquals(before.size, pointsOf(sourceId).size)
        assertTrue("sa première rupture est conservée", pointsOf(sourceId)[3].isDiscontinuous)
    }

    @Test
    fun `le parcours d origine est supprime si on le demande`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(3 to 0L, 4 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = true
        )

        assertNull(db.trackDao.getTrackById(sourceId))
        assertTrue("ses points sont partis avec lui", pointsOf(sourceId).isEmpty())
        // Les morceaux, eux, ont bien tout gardé.
        assertEquals(7, newIds.sumOf { pointsOf(it).size })
    }

    // ------------------------------------------------------------------
    // Découpage aux longues pauses
    // ------------------------------------------------------------------

    @Test
    fun `le decoupage aux pauses coupe sur les trous de temps`() = runBlocking {
        // Trois blocs séparés par une heure, sans aucune rupture de tronçon : c'est
        // le temps, et lui seul, qui doit décider des coupures.
        val trackId = db.trackDao.insertTrack(Track(name = "Sorties", startTime = 1_000_000L))
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
            baseName = "Sorties",
            deleteSource = false
        )

        assertEquals(3, newIds.size)
        assertEquals(listOf(4, 4, 4), newIds.map { pointsOf(it).size })
    }

    @Test
    fun `une pause plus courte que le seuil ne coupe pas`() = runBlocking {
        val trackId = db.trackDao.insertTrack(Track(name = "Sortie", startTime = 1_000_000L))
        val points = (0 until 6).map { i ->
            TrackPoint(
                trackId = trackId,
                latitude = 45.0 + i / 10_000.0,
                longitude = 5.0,
                // Dix minutes entre chaque point : en dessous du seuil d'une heure.
                timestamp = 1_000_000L + i * 600_000L
            )
        }
        db.trackDao.insertTrackPoints(points)

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = trackId,
                mode = TrackRepository.SplitMode.TIME_GAP,
                gapMillis = 60 * 60_000L,
                baseName = "Sortie",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue("le découpage doit être refusé", failure is IllegalStateException)
    }

    // ------------------------------------------------------------------
    // Refus
    // ------------------------------------------------------------------

    @Test
    fun `un parcours d un seul troncon n est pas decoupable`() = runBlocking {
        val sourceId = createTrack("Balade", listOf(5 to 0L))

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = sourceId,
                mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
                gapMillis = 0L,
                baseName = "Balade",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        // Découper en un seul morceau ne ferait qu'un doublon : le refus doit laisser
        // le parcours d'origine exactement tel qu'il était.
        assertEquals(5, pointsOf(sourceId).size)
        assertNotNull(db.trackDao.getTrackById(sourceId))
    }

    @Test
    fun `un parcours sans point n est pas decoupable`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(Track(name = "Vide", startTime = 1_000_000L))

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = sourceId,
                mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
                gapMillis = 0L,
                baseName = "Vide",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun `un parcours en cours d enregistrement n est pas decoupable`() = runBlocking {
        val sourceId = db.trackDao.insertTrack(
            Track(name = "En cours", startTime = 1_000_000L, isRecording = true)
        )

        val failure = runCatching {
            repository.splitTrack(
                sourceTrackId = sourceId,
                mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
                gapMillis = 0L,
                baseName = "En cours",
                deleteSource = false
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    // ------------------------------------------------------------------
    // Statistiques
    // ------------------------------------------------------------------

    @Test
    fun `chaque morceau porte les statistiques de ses propres points`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(5 to 0L, 6 to 0L, 4 to 0L))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = false
        )

        // Les statistiques sont cumulées en flux pendant la lecture du parcours
        // d'origine. Elles doivent être exactement celles que le calcul sur liste
        // donnerait pour les mêmes points : c'est ce qui garantit que les deux
        // chemins n'ont pas divergé.
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
    fun `la distance d un morceau est inferieure a celle du parcours entier`() = runBlocking {
        val sourceId = createTrack("Voyages", listOf(5 to 0L, 6 to 0L))
        val whole = repository.calculateStatsFromPoints(pointsOf(sourceId))

        val newIds = repository.splitTrack(
            sourceTrackId = sourceId,
            mode = TrackRepository.SplitMode.SEGMENT_BREAKS,
            gapMillis = 0L,
            baseName = "Voyages",
            deleteSource = false
        )

        // Le piège que le découpage doit éviter : recopier sur chaque morceau les
        // statistiques du parcours entier. Chacun afficherait alors la distance
        // totale, et deux morceaux additionnés vaudraient le double du trajet.
        for (id in newIds) {
            val track = db.trackDao.getTrackById(id)!!
            assertTrue(
                "un morceau ne peut pas être aussi long que le tout",
                track.totalDistance < whole.distanceMeters
            )
            assertTrue("mais il a bien une distance", track.totalDistance > 0.0)
        }
    }

    @Test
    fun `les morceaux heritent de l apparence et de la provenance du parent`() = runBlocking {
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
            assertTrue("un morceau d'import reste un import", track.isImported)
            assertEquals(0xFF123456.toInt(), track.sourceColor)
            assertEquals(0xFFABCDEF.toInt(), track.displayColor)
            assertFalse("jamais affiché d'office sur la carte", track.isSelectedForMap)
            assertFalse(track.isRecording)
        }
    }
}
