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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La corbeille : supprimer y met le parcours, avec ses points, pour trente jours ;
 * on peut l'en sortir, et passé ce délai il est effacé pour de bon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TrashTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TrackRepository

    private val day = 24L * 60 * 60 * 1000

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

    private suspend fun createTrack(name: String = "Sortie"): Long {
        val id = db.trackDao.insertTrack(Track(name = name, startTime = 1_000_000L, isSelectedForMap = true))
        db.trackDao.insertTrackPoints(
            (0 until 10).map { TrackPoint(trackId = id, latitude = 45.0 + it * 1e-4, longitude = 5.0, timestamp = 1_000_000L + it * 1_000L) }
        )
        return id
    }

    @Test
    fun `supprimer met à la corbeille, points compris, et retire de la carte`() = runBlocking {
        val id = createTrack()

        assertTrue(repository.deleteTrack(id))

        assertTrue("absent de l'historique", repository.allTracks.first().none { it.id == id })
        assertEquals(listOf(id), repository.trashedTracks.first().map { it.id })
        assertEquals("les points sont gardés", 10, db.trackDao.getPointsForTrack(id).size)
        assertFalse("plus dessiné sur la carte", db.trackDao.getTrackById(id)!!.isSelectedForMap)
    }

    @Test
    fun `restaurer ramène le parcours dans l'historique`() = runBlocking {
        val id = createTrack()
        repository.deleteTrack(id)

        repository.restoreFromTrash(id)

        assertTrue(repository.allTracks.first().any { it.id == id })
        assertTrue(repository.trashedTracks.first().isEmpty())
        assertNull(db.trackDao.getTrackById(id)!!.deletedAt)
    }

    @Test
    fun `la corbeille ne s'efface qu'au bout de trente jours`() = runBlocking {
        val id = createTrack()
        repository.deleteTrack(id)
        val deletedAt = db.trackDao.getTrackById(id)!!.deletedAt!!

        assertEquals("29 jours plus tard : encore là", 0, repository.purgeTrash(deletedAt + 29 * day))
        assertEquals(10, db.trackDao.getPointsForTrack(id).size)

        assertEquals("31 jours plus tard : effacé", 1, repository.purgeTrash(deletedAt + 31 * day))
        assertNull(db.trackDao.getTrackById(id))
        assertTrue("points compris", db.trackDao.getPointsForTrack(id).isEmpty())
    }

    @Test
    fun `l'effacement ne touche jamais un parcours hors de la corbeille`() = runBlocking {
        val kept = createTrack("Gardé")
        val trashed = createTrack("Supprimé")
        repository.deleteTrack(trashed)

        repository.purgeTrash(System.currentTimeMillis() + 365 * day)

        assertEquals(10, db.trackDao.getPointsForTrack(kept).size)
        assertNull(db.trackDao.getTrackById(trashed))
    }

    @Test
    fun `vider la corbeille efface tout son contenu, et rien d'autre`() = runBlocking {
        val kept = createTrack("Gardé")
        val first = createTrack("Supprimé 1")
        val second = createTrack("Supprimé 2")
        repository.deleteTrack(first)
        repository.deleteTrack(second)

        assertEquals(2, repository.emptyTrash())

        assertNull(db.trackDao.getTrackById(first))
        assertNull(db.trackDao.getTrackById(second))
        assertTrue("points effacés", db.trackDao.getPointsForTrack(first).isEmpty())
        assertNotNull(db.trackDao.getTrackById(kept))
        assertFalse(db.trackDao.getPointsForTrack(kept).isEmpty())
    }

    @Test
    fun `la corbeille trop lourde efface ses plus anciens, jamais le dernier`() = runBlocking {
        repository.trashPointLimit = 25
        val kept = createTrack("Gardé")
        val oldest = createTrack("Ancien")
        val middle = createTrack("Moyen")
        val newest = createTrack("Récent")
        // Des dates de suppression distinctes, de la plus ancienne à la plus récente.
        db.trackDao.moveToTrash(oldest, 1_000L)
        db.trackDao.moveToTrash(middle, 2_000L)
        db.trackDao.moveToTrash(newest, 3_000L)

        // 10 points chacun : récent + moyen = 20 tiennent sous 25, l'ancien déborde.
        assertEquals(1, repository.enforceTrashLimit())

        assertNull(db.trackDao.getTrackById(oldest))
        assertTrue("points effacés", db.trackDao.getPointsForTrack(oldest).isEmpty())
        assertNotNull(db.trackDao.getTrackById(middle))
        assertNotNull(db.trackDao.getTrackById(newest))
        assertNotNull(db.trackDao.getTrackById(kept))

        // Même seul au-dessus de la borne, le plus récent reste rattrapable.
        repository.trashPointLimit = 1
        assertEquals(1, repository.enforceTrashLimit())
        assertNotNull(db.trackDao.getTrackById(newest))
        assertNull(db.trackDao.getTrackById(middle))
    }

    @Test
    fun `un gros parcours s'efface par paquets, jusqu'au dernier point`() = runBlocking {
        val big = db.trackDao.insertTrack(Track(name = "Gros", startTime = 1_000_000L))
        (0 until 5).forEach { batch ->
            db.trackDao.insertTrackPoints(
                (0 until 10_000).map {
                    TrackPoint(trackId = big, latitude = 45.0, longitude = 5.0, timestamp = (batch * 10_000L + it) * 1_000L)
                }
            )
        }
        val kept = createTrack("Gardé")
        repository.deleteTrack(big)

        assertEquals(1, repository.emptyTrash())

        assertNull(db.trackDao.getTrackById(big))
        assertTrue(db.trackDao.getPointsForTrack(big).isEmpty())
        assertEquals(10, db.trackDao.getPointsForTrack(kept).size)
    }

    @Test
    fun `vider la corbeille rend la place au téléphone`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("corbeille_test.db")
        val fileDb = Room.databaseBuilder(context, AppDatabase::class.java, "corbeille_test.db")
            .allowMainThreadQueries()
            .build()
        try {
            val repo = TrackRepository.createForTesting(fileDb)
            val big = fileDb.trackDao.insertTrack(Track(name = "Gros", startTime = 1_000_000L))
            (0 until 30).forEach { batch ->
                fileDb.trackDao.insertTrackPoints(
                    (0 until 10_000).map {
                        val n = batch * 10_000 + it
                        TrackPoint(trackId = big, latitude = 45.0 + n * 1e-6, longitude = 5.0 + n * 1e-6, altitude = n.toDouble(), timestamp = n * 1_000L)
                    }
                )
            }
            fileDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val file = context.getDatabasePath("corbeille_test.db")
            val before = file.length()

            repo.deleteTrack(big)
            assertEquals(1, repo.emptyTrash())

            val after = file.length()
            assertTrue("base de $before octets ramenée à $after", after < before / 4)
        } finally {
            fileDb.close()
            context.deleteDatabase("corbeille_test.db")
        }
    }
}
