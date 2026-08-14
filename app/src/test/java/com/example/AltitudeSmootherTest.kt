package com.example

import com.example.util.AltitudeSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * L'altitude brute du GPS tremble de plusieurs mètres d'un relevé à l'autre, même
 * appareil immobile. Le lissage sert à rendre le nombre affiché stable sans le
 * décaler, et à absorber les valeurs aberrantes isolées.
 */
class AltitudeSmootherTest {

    private val smoother = AltitudeSmoother()

    @Test
    fun `sans mesure il n y a pas de valeur`() {
        assertNull(smoother.current)
    }

    @Test
    fun `la premiere mesure est reprise telle quelle`() {
        assertEquals(120.0, smoother.add(120.0), 0.001)
        assertEquals(120.0, smoother.current!!, 0.001)
    }

    @Test
    fun `une altitude stable est rendue sans decalage`() {
        repeat(30) { smoother.add(200.0) }
        assertEquals("une valeur constante ne doit pas être déplacée", 200.0, smoother.current!!, 0.01)
    }

    @Test
    fun `une valeur aberrante isolee ne deplace presque pas le resultat`() {
        repeat(10) { smoother.add(200.0) }
        val before = smoother.current!!

        // Un seul relevé à 60 m au-dessus : la médiane glissante l'écarte.
        smoother.add(260.0)
        val after = smoother.current!!

        assertTrue(
            "écart obtenu : ${abs(after - before)} m",
            abs(after - before) < 2.0
        )
    }

    @Test
    fun `le tremblement est fortement reduit`() {
        // Bruit de ±6 m autour de 300 m, comme un GPS immobile en conditions moyennes.
        val noisy = listOf(306.0, 294.0, 303.0, 297.0, 305.0, 295.0, 301.0, 299.0)
        repeat(20) { noisy.forEach { smoother.add(it) } }

        val smoothed = mutableListOf<Double>()
        repeat(5) { noisy.forEach { smoothed.add(smoother.add(it)) } }

        val rawSpread = noisy.max() - noisy.min()
        val smoothedSpread = smoothed.max() - smoothed.min()

        assertTrue(
            "amplitude brute $rawSpread m, lissée $smoothedSpread m",
            smoothedSpread < rawSpread / 3.0
        )
        assertEquals("la moyenne ne doit pas être décalée", 300.0, smoother.current!!, 3.0)
    }

    @Test
    fun `un changement reel est suivi`() {
        repeat(20) { smoother.add(100.0) }
        // Montée de 100 m à 300 m, tenue dans le temps : elle doit être suivie.
        repeat(40) { smoother.add(300.0) }
        assertEquals(300.0, smoother.current!!, 1.0)
    }

    @Test
    fun `reset oublie la serie precedente`() {
        repeat(10) { smoother.add(100.0) }
        smoother.reset()
        assertNull(smoother.current)

        // La première mesure après reset repart de zéro, sans traîner l'ancienne série.
        assertEquals(800.0, smoother.add(800.0), 0.001)
    }
}
