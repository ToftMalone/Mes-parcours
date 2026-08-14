package com.example

import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.util.Exporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un parcours fusionné réunit plusieurs traces en une. Le fichier exporté doit le
 * refléter : **une seule entrée**, dont la géométrie regroupe autant de
 * `<LineString>` qu'il y a de tronçons.
 *
 * Les deux propriétés se contredisent si on n'y prend pas garde : tout mettre dans
 * un bloc de coordonnées unique donne bien une entrée, mais relie les tronçons par
 * une ligne droite à la réimportation ; un Placemark par tronçon garde les ruptures,
 * mais présente dix entrées là où l'utilisateur venait d'en fabriquer une.
 */
class KmlExportTest {

    private val track = Track(id = 1L, name = "Voyages", startTime = 1_000_000L)

    private fun point(index: Int, discontinuous: Boolean = false) = TrackPoint(
        id = index.toLong(),
        trackId = 1L,
        latitude = 45.0 + index / 1000.0,
        longitude = 6.0,
        altitude = 100.0,
        timestamp = 1_000_000L + index * 1000L,
        isDiscontinuous = discontinuous
    )

    private fun export(points: List<TrackPoint>): String {
        val out = StringBuilder()
        val writer = Exporter.KmlWriter(out)
        writer.start(track)
        points.forEach { writer.add(it) }
        writer.finish()
        return out.toString()
    }

    private fun String.countOf(needle: String) = split(needle).size - 1

    @Test
    fun `trois troncons donnent une entree et trois lignes`() {
        val points = listOf(
            point(1), point(2),
            point(3, discontinuous = true), point(4),
            point(5, discontinuous = true), point(6)
        )

        val kml = export(points)

        assertEquals("une seule entrée attendue", 1, kml.countOf("<Placemark>"))
        assertEquals(1, kml.countOf("<MultiGeometry>"))
        assertEquals("un LineString par tronçon", 3, kml.countOf("<LineString>"))
        assertEquals(3, kml.countOf("<coordinates>"))
    }

    @Test
    fun `un troncon unique reste enveloppe de la meme facon`() {
        // L'écriture étant incrémentale, on ne sait pas en ouvrant la géométrie
        // combien de tronçons suivront : l'enveloppe est donc toujours la même.
        val kml = export(listOf(point(1), point(2), point(3)))

        assertEquals(1, kml.countOf("<Placemark>"))
        assertEquals(1, kml.countOf("<MultiGeometry>"))
        assertEquals(1, kml.countOf("<LineString>"))
    }

    @Test
    fun `le parcours ne porte plus de noms numerotes`() {
        // « Voyages (2) », « Voyages (3) »… trahissaient le découpage en plusieurs
        // entrées, ce que la fusion cherchait précisément à supprimer.
        val kml = export(listOf(point(1), point(2, discontinuous = true)))

        // Compté dans le Placemark seul : l'en-tête <Document> porte lui aussi le nom
        // du parcours, et le compter sur le fichier entier ferait échouer un test que
        // le code satisfait pourtant.
        val placemark = kml.substringAfter("<Placemark>").substringBefore("</Placemark>")
        assertEquals(1, placemark.countOf("<name>Voyages</name>"))
        assertTrue("aucun nom numéroté attendu", !kml.contains("Voyages ("))
    }

    @Test
    fun `les coordonnees restent en longitude latitude altitude`() {
        // L'ordre inverse de celui qu'on lit partout ailleurs : c'est la convention
        // KML, et l'intervertir enverrait les tracés à l'autre bout du monde.
        val kml = export(listOf(point(0)))

        assertTrue(kml.contains("6.0,45.0,100.0"))
    }

    @Test
    fun `une trace vide ne produit aucune entree`() {
        val kml = export(emptyList())

        assertEquals(0, kml.countOf("<Placemark>"))
        assertTrue(kml.contains("</kml>"))
    }

    @Test
    fun `chaque troncon ferme sa ligne avant d ouvrir la suivante`() {
        // Un </LineString> manquant produit un fichier que Google Earth refuse
        // d'ouvrir — et l'erreur ne se voit qu'à l'usage, pas à l'export.
        val kml = export(listOf(point(1), point(2, discontinuous = true)))

        assertEquals(kml.countOf("<LineString>"), kml.countOf("</LineString>"))
        assertEquals(kml.countOf("<coordinates>"), kml.countOf("</coordinates>"))
        assertEquals(kml.countOf("<MultiGeometry>"), kml.countOf("</MultiGeometry>"))
    }
}
