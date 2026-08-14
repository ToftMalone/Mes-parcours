package com.example

import com.example.util.ElevationAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'ancien cumul comparait chaque altitude à la précédente avec un seuil de 0,80 m.
 * Il se trompait dans les deux sens : il ratait les montées régulières, dont les
 * écarts d'une seconde à l'autre restent sous le seuil, et il gonflait le dénivelé à
 * l'arrêt, où le bruit vertical du GPS franchit le seuil dans un sens puis dans
 * l'autre.
 *
 * Ces tests fixent les deux comportements attendus du cumul par altitude de
 * référence.
 */
class ElevationAccumulatorTest {

    private val accumulator = ElevationAccumulator()

    @Test
    fun `une montee reguliere est comptee malgre de petits pas`() {
        // 300 m de montée par pas de 0,15 m, soit ce que donne une pente de 10 % à
        // 1,4 m/s relevée à 1 Hz. Aucun pas n'atteint le seuil de 3 m.
        var altitude = 100.0
        accumulator.add(altitude)
        repeat(2_000) {
            altitude += 0.15
            accumulator.add(altitude)
        }

        // Tolérance d'un seuil : la référence ne suit que par paliers de 3 m.
        assertTrue(
            "D+ obtenu : ${accumulator.gainMeters}",
            accumulator.gainMeters >= 297.0 && accumulator.gainMeters <= 300.0
        )
        assertEquals(0.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `le bruit a l arret ne cree pas de denivele`() {
        // Oscillation de ±2 m autour de 200 m : c'est du bruit GPS ordinaire, et il
        // ne doit rien accumuler puisqu'il ne s'éloigne jamais assez de la référence.
        val noise = listOf(200.0, 202.0, 198.0, 201.5, 199.0, 200.5, 198.5, 201.0)
        repeat(50) { noise.forEach { accumulator.add(it) } }

        assertEquals("D+ doit rester nul", 0.0, accumulator.gainMeters, 0.001)
        assertEquals("D- doit rester nul", 0.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `une descente est comptee separement`() {
        accumulator.add(500.0)
        accumulator.add(450.0)
        accumulator.add(470.0)

        assertEquals(20.0, accumulator.gainMeters, 0.001)
        assertEquals(50.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `une rupture de troncon ne compte pas le saut d altitude`() {
        accumulator.add(100.0)
        accumulator.add(120.0)
        assertEquals(20.0, accumulator.gainMeters, 0.001)

        // Pause puis reprise 800 m plus haut : on n'a pas gravi ces 800 m.
        accumulator.breakSegment()
        accumulator.add(920.0)
        accumulator.add(930.0)

        assertEquals("seuls les 10 m du nouveau tronçon comptent", 30.0, accumulator.gainMeters, 0.001)
        assertEquals(0.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `restore reprend les totaux sans reference d altitude`() {
        accumulator.restore(gainMeters = 250.0, lossMeters = 80.0)
        assertEquals(250.0, accumulator.gainMeters, 0.001)
        assertEquals(80.0, accumulator.lossMeters, 0.001)

        // Le premier point après une reprise fixe la référence, il ne cumule rien.
        accumulator.add(1_500.0)
        assertEquals(250.0, accumulator.gainMeters, 0.001)

        accumulator.add(1_510.0)
        assertEquals(260.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `reset efface tout`() {
        accumulator.add(100.0)
        accumulator.add(200.0)
        assertTrue(accumulator.gainMeters > 0.0)

        accumulator.reset()
        assertEquals(0.0, accumulator.gainMeters, 0.001)
        assertEquals(0.0, accumulator.lossMeters, 0.001)
    }
}
