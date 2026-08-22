package com.example

import com.example.util.parseIso8601
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lecture des horodatages d'un fichier GPX.
 *
 * L'implémentation précédente ne reconnaissait que la forme UTC suffixée `Z`. Les
 * fichiers produits par la plupart des montres et applications de sport portent un
 * décalage horaire : ils échouaient tous, chaque point prenait l'heure de l'import,
 * et la chronologie du parcours importé était perdue.
 */
class Iso8601Test {

    /** 2024-01-01T10:00:00Z, la référence de tous les cas ci-dessous. */
    private val reference = 1_704_103_200_000L

    @Test
    fun `la forme UTC suffixee Z est lue`() {
        assertEquals(reference, parseIso8601("2024-01-01T10:00:00Z"))
    }

    @Test
    fun `les millisecondes sont prises en compte`() {
        assertEquals(reference + 123L, parseIso8601("2024-01-01T10:00:00.123Z"))
    }

    @Test
    fun `un decalage horaire avec deux points est applique`() {
        // 12:00 en UTC+02:00, c'est 10:00 UTC.
        assertEquals(reference, parseIso8601("2024-01-01T12:00:00+02:00"))
    }

    @Test
    fun `un decalage horaire sans deux points est applique`() {
        assertEquals(reference, parseIso8601("2024-01-01T12:00:00+0200"))
    }

    @Test
    fun `un decalage horaire en heures seules est applique`() {
        assertEquals(reference, parseIso8601("2024-01-01T12:00:00+02"))
    }

    @Test
    fun `un decalage negatif est applique dans l autre sens`() {
        // 05:00 en UTC-05:00, c'est 10:00 UTC.
        assertEquals(reference, parseIso8601("2024-01-01T05:00:00-05:00"))
    }

    @Test
    fun `un decalage a la demi-heure est applique`() {
        // 15:30 en UTC+05:30 (Inde), c'est 10:00 UTC.
        assertEquals(reference, parseIso8601("2024-01-01T15:30:00+05:30"))
    }

    @Test
    fun `millisecondes et decalage se combinent`() {
        assertEquals(reference + 500L, parseIso8601("2024-01-01T12:00:00.500+02:00"))
    }

    @Test
    fun `l absence de suffixe vaut UTC comme l impose GPX`() {
        assertEquals(reference, parseIso8601("2024-01-01T10:00:00"))
    }

    @Test
    fun `les espaces autour de la valeur sont tolerés`() {
        assertEquals(reference, parseIso8601("  2024-01-01T10:00:00Z\n"))
    }

    @Test
    fun `une fraction plus longue que trois chiffres est tronquee aux millisecondes`() {
        assertEquals(reference + 123L, parseIso8601("2024-01-01T10:00:00.123456Z"))
    }

    @Test
    fun `une fraction plus courte est completee`() {
        // « .5 » vaut cinq cents millisecondes, pas cinq.
        assertEquals(reference + 500L, parseIso8601("2024-01-01T10:00:00.5Z"))
    }

    @Test
    fun `une chaine vide est refusee`() {
        assertNull(parseIso8601(""))
    }

    @Test
    fun `une date sans heure est refusee`() {
        assertNull(parseIso8601("2024-01-01"))
    }

    @Test
    fun `un suffixe illisible est refuse plutot que decale au hasard`() {
        assertNull(parseIso8601("2024-01-01T10:00:00+bidon"))
    }

    @Test
    fun `un mois inexistant est refuse`() {
        // Sans isLenient = false, SimpleDateFormat convertissait le treizième mois
        // en janvier de l'année suivante au lieu de refuser la valeur.
        assertNull(parseIso8601("2024-13-01T10:00:00Z"))
    }
}
