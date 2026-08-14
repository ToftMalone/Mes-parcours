package com.example

import com.example.data.model.TrackPoint
import com.example.ui.component.buildSegmentsFromPoints
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Un parcours peut contenir plusieurs tronçons sans qu'aucun trait ne les relie :
 * c'est le cas après une pause, après la reprise d'une trace, et après une fusion.
 * Chaque tronçon devient une polyligne distincte sur la carte.
 *
 * Ces tests fixent le découpage, y compris le point subtil qui distingue une vraie
 * pause d'un simple sous-échantillonnage à l'affichage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackSegmentsTest {

    /** Un point à identifiant et horodatage maîtrisés. */
    private fun point(
        id: Long,
        timestampSec: Long,
        discontinuous: Boolean = false
    ) = TrackPoint(
        id = id,
        trackId = 1L,
        latitude = 45.0 + id / 10_000.0,
        longitude = 6.0,
        timestamp = timestampSec * 1000L,
        isDiscontinuous = discontinuous
    )

    @Test
    fun `un parcours continu ne donne qu un seul troncon`() {
        val points = (1L..10L).map { point(id = it, timestampSec = it) }
        val segments = buildSegmentsFromPoints(points)

        assertEquals(1, segments.size)
        assertEquals(10, segments[0].size)
    }

    @Test
    fun `une pause puis une reprise coupent le trace`() {
        // Le service marque le premier point d'après la reprise comme discontinu.
        val points = listOf(
            point(1, 1), point(2, 2), point(3, 3),
            point(4, 600, discontinuous = true), point(5, 601), point(6, 602)
        )
        val segments = buildSegmentsFromPoints(points)

        assertEquals("deux tronçons attendus", 2, segments.size)
        assertEquals(3, segments[0].size)
        assertEquals(3, segments[1].size)
        // Aucun point commun : rien ne relie la fin du premier au début du second.
        assertEquals(6, segments.sumOf { it.size })
    }

    @Test
    fun `une fusion de trois parcours donne trois troncons`() {
        val points = listOf(
            point(1, 1), point(2, 2),
            point(3, 1_000, discontinuous = true), point(4, 1_001), point(5, 1_002),
            point(6, 5_000, discontinuous = true), point(7, 5_001)
        )
        val segments = buildSegmentsFromPoints(points)

        assertEquals(3, segments.size)
        assertEquals(listOf(2, 3, 2), segments.map { it.size })
    }

    @Test
    fun `un premier point discontinu ne cree pas de troncon vide`() {
        // Le tout premier point d'une trace est marqué discontinu par le service.
        val points = listOf(point(1, 1, discontinuous = true), point(2, 2), point(3, 3))
        val segments = buildSegmentsFromPoints(points)

        assertEquals(1, segments.size)
        assertEquals(3, segments[0].size)
    }

    @Test
    fun `un long arret sur des points consecutifs coupe le trace`() {
        // Rupture détectée par l'écart de temps, même sans drapeau : cas des traces
        // enregistrées avant que le drapeau existe.
        val points = listOf(point(1, 1), point(2, 2), point(3, 120), point(4, 121))
        val segments = buildSegmentsFromPoints(points)

        assertEquals(2, segments.size)
        assertEquals(listOf(2, 2), segments.map { it.size })
    }

    @Test
    fun `un sous-echantillonnage a l affichage ne coupe pas le trace`() {
        // Sur une trace dense, l'affichage n'garde qu'un point sur N : les
        // identifiants sautent et l'écart de temps est normal. Le tracé doit rester
        // continu, sans quoi une trace importée apparaîtrait en pointillés.
        val points = (0L until 10L).map { i ->
            point(id = 1 + i * 500, timestampSec = 1 + i * 500)
        }
        val segments = buildSegmentsFromPoints(points)

        assertEquals("le tracé doit rester d'un seul morceau", 1, segments.size)
        assertEquals(10, segments[0].size)
    }

    @Test
    fun `les points sans coordonnees sont ignores`() {
        val points = listOf(
            point(1, 1),
            TrackPoint(id = 2, trackId = 1L, latitude = 0.0, longitude = 0.0, timestamp = 2_000L),
            point(3, 3)
        )
        val segments = buildSegmentsFromPoints(points)

        assertEquals(1, segments.size)
        assertEquals(2, segments[0].size)
    }
}
