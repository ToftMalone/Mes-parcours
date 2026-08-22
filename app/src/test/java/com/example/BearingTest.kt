package com.example

import com.example.data.model.TrackPoint
import com.example.ui.component.computeCurrentBearing
import com.example.ui.component.smoothedBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Orientation de la carte en mode 3D.
 *
 * Ces tests fixent le point qui faisait tourner la carte sur elle-même : le cap ne
 * doit jamais se déduire du tracé enregistré tant qu'une position GPS est connue.
 * Pendant une pause d'enregistrement, ce tracé est figé et garde le cap qu'on avait
 * en s'arrêtant ; la carte basculait entre les deux plusieurs fois par seconde.
 *
 * Robolectric est nécessaire pour `Location.distanceBetween`, une méthode Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BearingTest {

    private fun point(lat: Double, lon: Double) =
        TrackPoint(trackId = 1, latitude = lat, longitude = lon, timestamp = 0L)

    /** Environ 110 m vers le nord, bien au-delà du seuil de déplacement. */
    private val south = point(48.8500, 2.3500)
    private val north = point(48.8510, 2.3500)

    /** Un tracé figé qui pointe plein est — le cap « fossilisé » d'une pause. */
    private val frozenEastwardTrack = listOf(
        point(48.8000, 2.3000),
        point(48.8000, 2.3020)
    )

    @Test
    fun `le cap suit le deplacement entre deux positions`() {
        val bearing = computeCurrentBearing(emptyList(), north, south)!!
        assertEquals("plein nord", 0f, bearing, 1f)
    }

    @Test
    fun `une position inchangee ne produit aucun cap`() {
        // Le coeur du correctif : renvoyer null laisse l'appelant garder le dernier
        // cap connu, au lieu d'aller en chercher un dans le tracé enregistré.
        assertNull(computeCurrentBearing(frozenEastwardTrack, north, north))
    }

    @Test
    fun `un tracé figé ne dicte jamais le cap tant qu une position est connue`() {
        // Le cas exact de l'enregistrement en pause : le tracé pointe à l'est, la
        // voiture va au nord. Se fier au tracé faisait pivoter la carte de 90°.
        val bearing = computeCurrentBearing(frozenEastwardTrack, north, south)!!
        assertEquals("le déplacement réel l'emporte", 0f, bearing, 1f)

        // Et sans déplacement, aucun cap du tout — surtout pas celui du tracé.
        assertNull(computeCurrentBearing(frozenEastwardTrack, north, north))
    }

    @Test
    fun `un deplacement sous le seuil ne produit aucun cap`() {
        // Quelques dizaines de centimètres : c'est du bruit GPS, pas une direction.
        val barelyMoved = point(48.8500, 2.35001)
        assertNull(computeCurrentBearing(emptyList(), barelyMoved, south))
    }

    @Test
    fun `sans position connue le trace affiché sert de repli`() {
        // Le seul cas où le tracé a voix au chapitre : consultation sans GPS.
        val bearing = computeCurrentBearing(frozenEastwardTrack, null, null)!!
        assertEquals("plein est", 90f, bearing, 2f)
    }

    // ------------------------------------------------------------------ lissage

    @Test
    fun `le premier cap est adopté tel quel`() {
        assertEquals(42f, smoothedBearing(null, 42f), 0.01f)
    }

    @Test
    fun `le lissage se rapproche de la cible sans l atteindre d un coup`() {
        val result = smoothedBearing(0f, 100f)
        assertTrue("progresse vers la cible", result > 0f)
        assertTrue("mais ne l'atteint pas immédiatement", result < 100f)
    }

    @Test
    fun `le passage par le nord emprunte l arc le plus court`() {
        // De 350° vers 10° : vingt degrés par le nord, et non trois cent quarante
        // dans l'autre sens. Sans cela la carte faisait un tour complet.
        val result = smoothedBearing(350f, 10f)
        val movedForward = (result - 350f + 360f) % 360f
        assertTrue("tourne dans le sens court", movedForward in 0f..20f)
    }

    @Test
    fun `le lissage reste dans l intervalle des angles`() {
        // Une valeur négative ou au-delà de 360° désorienterait la carte.
        for (target in listOf(0f, 45f, 180f, 270f, 359f)) {
            for (previous in listOf(0f, 90f, 200f, 355f)) {
                val result = smoothedBearing(previous, target)
                assertTrue("angle valide : $result", result >= 0f && result < 360f)
            }
        }
    }

    @Test
    fun `des caps répétés convergent vers la cible`() {
        var bearing = 0f
        repeat(20) { bearing = smoothedBearing(bearing, 90f) }
        assertEquals("finit par s'aligner", 90f, bearing, 1f)
    }
}
