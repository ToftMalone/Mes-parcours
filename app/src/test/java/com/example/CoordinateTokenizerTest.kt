package com.example

import com.example.util.CoordinateTokenizer
import com.example.util.MAX_COORDINATE_TOKEN_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Le découpeur des blocs `<coordinates>` d'un KML reçoit le texte du fichier par
 * morceaux, tel que l'analyseur SAX le livre. Un fichier importé vient de
 * l'extérieur : un bloc démesuré ne doit pas pouvoir saturer la mémoire.
 *
 * Fonction pure, sans dépendance Android : pas besoin de Robolectric.
 */
class CoordinateTokenizerTest {

    private data class Point(val lon: Double, val lat: Double, val ele: Double)

    /** Découpe [chunks], livrés l'un après l'autre comme le ferait SAX. */
    private fun tokenize(vararg chunks: String): List<Point> {
        val points = mutableListOf<Point>()
        val tokenizer = CoordinateTokenizer { lon, lat, ele -> points += Point(lon, lat, ele) }
        for (chunk in chunks) {
            val chars = chunk.toCharArray()
            tokenizer.feed(chars, 0, chars.size)
        }
        tokenizer.finish()
        return points
    }

    @Test
    fun `des points ordinaires sont lus`() {
        assertEquals(
            listOf(Point(2.35, 48.85, 35.0), Point(2.36, 48.86, 0.0)),
            tokenize("2.35,48.85,35 2.36,48.86")
        )
    }

    @Test
    fun `un point coupe entre deux morceaux est recolle`() {
        // SAX peut trancher le texte n'importe où, au milieu d'un nombre compris.
        assertEquals(listOf(Point(2.35, 48.85, 35.0)), tokenize("2.3", "5,48.", "85,35"))
    }

    @Test
    fun `les espaces autour des virgules sont toleres`() {
        assertEquals(listOf(Point(2.35, 48.85, 35.0)), tokenize("2.35 , 48.85 ,35"))
    }

    @Test
    fun `un jeton demesure est ecarte sans empecher la suite`() {
        // Le cas qui faisait saturer la mémoire : un jeton sans aucun blanc, ici
        // livré en de nombreux morceaux comme le ferait SAX sur un gros fichier.
        val chunk = "1".repeat(10_000)
        val chunks = Array(100) { chunk }
        val points = tokenize("2.35,48.85 ", *chunks, " 2.36,48.86")
        // Le jeton démesuré n'est pas émis tronqué : il disparaît, et les points
        // valides qui l'encadrent sont conservés.
        assertEquals(listOf(Point(2.35, 48.85, 0.0), Point(2.36, 48.86, 0.0)), points)
    }

    @Test
    fun `un point a la longueur maximale est encore lu`() {
        // La borne ne doit jamais écarter un point réel : on la remplit jusqu'au
        // dernier caractère autorisé avec des décimales de longitude.
        val lat = ",48.85"
        val lon = "2." + "3".repeat(MAX_COORDINATE_TOKEN_LENGTH - lat.length - 2)
        val token = lon + lat
        assertEquals(MAX_COORDINATE_TOKEN_LENGTH, token.length)
        val points = tokenize(token)
        assertEquals(1, points.size)
        assertEquals(48.85, points[0].lat, 0.0)
    }

    @Test
    fun `un point d un caractere de trop est ecarte plutot que tronque`() {
        // Tronqué, il aurait été lu comme un autre point, à un autre endroit.
        val lat = ",48.85"
        val lon = "2." + "3".repeat(MAX_COORDINATE_TOKEN_LENGTH - lat.length - 1)
        assertEquals(MAX_COORDINATE_TOKEN_LENGTH + 1, (lon + lat).length)
        assertEquals(listOf(Point(2.36, 48.86, 0.0)), tokenize(lon + lat + " 2.36,48.86"))
    }
}
