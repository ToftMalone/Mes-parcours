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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'outil de nettoyage crée toujours une copie : ces tests vérifient qu'il ne
 * touche jamais à la trace d'origine, qu'il garde bien les points de rupture de
 * tronçon quelle que soit leur proximité, et que les statistiques du résultat
 * reflètent les points effectivement conservés.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RemoveStationaryPointsTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    /** Mètres vers un delta de latitude, approximation suffisante pour ces tests. */
    private fun metersToLatDelta(meters: Double): Double = meters / 111_320.0

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

    @Test
    fun `les points immobiles sont retires de la copie mais pas de l original`() = runBlocking {
        val baseLat = 45.0
        val baseLon = 5.0

        // Point 0 : référence. Points 1-4 : à moins d'un mètre de la référence, donc
        // écartés. Point 5 : à 5 m, donc conservé et nouvelle référence. Points 6-8 :
        // à moins d'un mètre de cette nouvelle référence, écartés. Point 9 : à 5 m de
        // plus, donc conservé.
        val points = listOf(
            0 to 0.0,
            1 to 0.2, 2 to 0.3, 3 to 0.4, 4 to 0.1,
            5 to 5.0,
            6 to 5.2, 7 to 5.3, 8 to 5.4,
            9 to 10.0
        ).map { (i, offsetMeters) ->
            TrackPoint(
                trackId = 0, // remplacé après insertion du parcours
                latitude = baseLat + metersToLatDelta(offsetMeters),
                longitude = baseLon,
                timestamp = 1_000_000L + i * 1_000L
            )
        }

        val trackId = db.trackDao.insertTrack(
            Track(name = "Trace bruitée", startTime = 1_000_000L, endTime = 1_010_000L)
        )
        db.trackDao.insertTrackPoints(points.map { it.copy(trackId = trackId) })

        val newTrackId = repository.removeStationaryPoints(
            sourceTrackId = trackId,
            thresholdMeters = 1.0,
            newName = "Trace nettoyée"
        )

        assertNotEquals("le nettoyage doit produire un parcours distinct", trackId, newTrackId)

        // L'original garde tous ses points, intacts.
        assertEquals(10, db.trackDao.getPointsForTrack(trackId).size)

        // Seuls les points 0, 5 et 9 survivent dans la copie.
        val cleanedPoints = db.trackDao.getPointsForTrack(newTrackId)
        assertEquals(3, cleanedPoints.size)

        val cleanedTrack = db.trackDao.getTrackById(newTrackId)!!
        assertEquals("Trace nettoyée", cleanedTrack.name)
        // Les deux vrais déplacements (0→5 m et 5→10 m) doivent être comptés ;
        // les micro-mouvements écartés ne doivent pas gonfler artificiellement le
        // résultat, ni non plus être totalement absents (~10 m attendus).
        assertTrue(
            "distance recalculée inattendue : ${cleanedTrack.totalDistance}",
            cleanedTrack.totalDistance in 8.0..12.0
        )
    }

    @Test
    fun `une rupture de troncon est gardee meme tres proche du point precedent`() = runBlocking {
        val baseLat = 45.0
        val baseLon = 5.0

        val trackId = db.trackDao.insertTrack(
            Track(name = "Avec pause", startTime = 1_000_000L, endTime = 1_003_000L)
        )
        val points = listOf(
            TrackPoint(trackId = trackId, latitude = baseLat, longitude = baseLon, timestamp = 1_000_000L),
            // À 0,1 m du précédent : serait écarté s'il n'était pas marqué comme
            // rupture de tronçon (reprise après une pause).
            TrackPoint(
                trackId = trackId,
                latitude = baseLat + metersToLatDelta(0.1),
                longitude = baseLon,
                timestamp = 1_002_000L,
                isDiscontinuous = true
            )
        )
        db.trackDao.insertTrackPoints(points)

        val newTrackId = repository.removeStationaryPoints(
            sourceTrackId = trackId,
            thresholdMeters = 1.0,
            newName = "Copie"
        )

        val cleanedPoints = db.trackDao.getPointsForTrack(newTrackId)
        assertEquals("le marqueur de rupture doit survivre au nettoyage", 2, cleanedPoints.size)
        assertTrue(cleanedPoints.last().isDiscontinuous)
    }
}
