package com.example

import com.example.util.TunnelDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le détecteur de tunnel remplace un simple seuil absolu, qui confondait « il fait
 * sombre » et « je suis à l'intérieur en pleine journée » et faisait basculer
 * l'application en thème sombre dans une pièce éclairée.
 *
 * Ces tests fixent la distinction : seule une chute brutale et profonde de la
 * luminosité compte, et elle doit persister.
 */
class TunnelDetectorTest {

    private val detector = TunnelDetector()

    /** Rythme du capteur au réglage SENSOR_DELAY_NORMAL. */
    private val sampleIntervalMs = 200L

    private var clock = 0L

    /** Alimente le détecteur pendant [durationMs] à luminosité constante. */
    private fun feed(lux: Float, durationMs: Long): Boolean {
        var last = detector.isInside
        var elapsed = 0L
        while (elapsed < durationMs) {
            last = detector.onReading(lux, clock)
            clock += sampleIntervalMs
            elapsed += sampleIntervalMs
        }
        return last
    }

    @Test
    fun `rester a l interieur en pleine journee ne declenche rien`() {
        // Le cas qui posait problème : un intérieur éclairé de jour, autour de
        // 150 lux. C'est sombre par rapport au dehors, mais ce n'est pas un tunnel.
        feed(12_000f, durationMs = 10_000) // dehors, plein jour
        assertFalse("l'entrée dans la maison ne doit rien déclencher", feed(150f, 30_000))
        assertFalse("et rester à l'intérieur non plus", feed(150f, 120_000))
    }

    @Test
    fun `un tunnel fait basculer en sombre puis revenir au clair`() {
        feed(20_000f, durationMs = 10_000) // route en plein soleil
        assertTrue("l'entrée du tunnel doit basculer en sombre", feed(4f, 5_000))
        assertTrue("et le rester pendant tout le tunnel", feed(6f, 60_000))
        assertFalse("la sortie doit revenir au clair", feed(20_000f, 5_000))
    }

    @Test
    fun `une obscurite d un instant ne fait pas clignoter l affichage`() {
        feed(20_000f, durationMs = 10_000)
        // Passage sous un pont : sombre, mais bien plus bref que le délai de
        // confirmation. Sans ce délai, l'affichage clignoterait à chaque ouvrage.
        assertFalse("un passage sous un pont ne doit pas basculer", feed(3f, 400))
        assertFalse(feed(20_000f, 2_000))
    }

    @Test
    fun `un parking couvert reste sombre tant qu on y stationne`() {
        feed(15_000f, durationMs = 10_000)
        assertTrue(feed(8f, 5_000))
        // La référence ambiante est gelée à l'intérieur. Sans ce gel, elle
        // rejoindrait le niveau du parking, l'effondrement relatif disparaîtrait et
        // l'affichage repasserait au clair alors qu'on est toujours dans le noir.
        assertTrue("dix minutes de stationnement ne doivent pas rallumer", feed(8f, 600_000))
        assertFalse("la sortie doit revenir au clair", feed(15_000f, 5_000))
    }

    @Test
    fun `la tombee de la nuit ne passe pas pour un tunnel`() {
        // Le crépuscule est une décroissance lente : la référence ambiante la suit,
        // il n'y a donc jamais d'effondrement relatif. C'est le calcul solaire qui
        // doit traiter ce cas, pas le capteur.
        feed(8_000f, durationMs = 20_000)
        for (lux in listOf(4_000f, 2_000f, 900f, 400f, 150f, 60f, 25f, 10f, 4f)) {
            feed(lux, durationMs = 60_000)
        }
        assertFalse("la tombée de la nuit ne doit pas être vue comme un tunnel", detector.isInside)
    }

    @Test
    fun `reset oublie la reference ambiante`() {
        feed(20_000f, durationMs = 10_000)
        assertTrue(feed(4f, 5_000))

        detector.reset()
        assertFalse("après reset, plus aucun état retenu", detector.isInside)

        // La première mesure après un reset fixe la nouvelle référence : une
        // luminosité stable et faible ne constitue plus une chute.
        assertFalse(feed(4f, 30_000))
    }
}
