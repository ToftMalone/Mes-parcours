package com.example

import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.util.Exporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun point(index: Int, discontinuous: Boolean = false, color: Int? = null) = TrackPoint(
        id = index.toLong(),
        trackId = 1L,
        latitude = 45.0 + index / 1000.0,
        longitude = 6.0,
        altitude = 100.0,
        timestamp = 1_000_000L + index * 1000L,
        isDiscontinuous = discontinuous,
        segmentColor = color
    )

    private fun export(points: List<TrackPoint>, of: Track = track): String {
        val out = StringBuilder()
        val writer = Exporter.KmlWriter(out)
        writer.start(of)
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
    fun `les pauses d un enregistrement ne creent pas d entrees separees`() {
        // Une pause, une reprise et une fusion produisent le même signal : le point
        // suivant ouvre un tronçon. Aucun des trois ne doit donner une entrée de plus
        // dans le fichier — un parcours enregistré en trois fois reste un parcours.
        val points = listOf(
            point(1), point(2), point(3),
            point(4, discontinuous = true), point(5),   // sortie de première pause
            point(6, discontinuous = true), point(7)    // sortie de seconde pause
        )

        val kml = export(points)

        assertEquals("un enregistrement reste une entrée", 1, kml.countOf("<Placemark>"))
        assertEquals("une ligne par morceau enregistré", 3, kml.countOf("<LineString>"))
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

        assertTrue(kml.contains("6,45,100"))
    }

    @Test
    fun `les coordonnees tiennent sur une ligne, sans notation scientifique`() {
        // Un point par ligne, indenté, gonflait le fichier de moitié par rapport à celui
        // de Google Earth ; 1.0E-4 n'est pas un nombre que KML prévoit.
        val kml = export(listOf(point(0), point(1)).map { it.copy(longitude = 0.0001, latitude = 45.123456789012, altitude = 0.0) })

        assertTrue(kml.contains("<coordinates>0.0001,45.123456789012,0 0.0001,45.123456789012,0</coordinates>"))
        assertFalse(kml.contains("E-"))
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

    private val france = 0xFF2F7DE1.toInt()   // bleu
    private val belgique = 0xFFE8505B.toInt() // rouge

    @Test
    fun `chaque couleur de troncon garde la sienne`() {
        // Un voyage importé de Google Earth : un pays par couleur.
        val points = listOf(
            point(1, color = france), point(2, color = france),
            point(3, discontinuous = true, color = belgique), point(4, color = belgique)
        )

        val kml = export(points, track.copy(isImported = true))

        assertEquals("une entrée par couleur", 2, kml.countOf("<Placemark>"))
        assertTrue("bleu en AABBGGRR", kml.contains("<color>ffe17d2f</color>"))
        assertTrue("rouge en AABBGGRR", kml.contains("<color>ff5b50e8</color>"))
        assertEquals(2, kml.countOf("<LineString>"))
    }

    @Test
    fun `la couleur choisie pour le parcours s'impose a tout le trace`() {
        val points = listOf(point(1, color = france), point(2, discontinuous = true, color = belgique))

        val kml = export(points, track.copy(displayColor = 0xFF13A38A.toInt(), displayWidthDp = 8f))

        assertEquals(1, kml.countOf("<Placemark>"))
        assertTrue(kml.contains("<color>ff8aa313</color>"))
        assertTrue("l'épaisseur du parcours", kml.contains("<width>8</width>"))
        assertTrue("plus jamais le rouge d'office", !kml.contains("ff0000ff"))
    }

    @Test
    fun `l'epaisseur choisie dans l'application passe devant celle du fichier`() {
        val placemark = com.example.data.model.TrackPlacemark(id = 3, name = "Belgique", folderPath = null, color = null, width = 5f)
        val points = listOf(point(1).copy(placemarkId = 3), point(2).copy(placemarkId = 3))

        fun write(of: Track, default: Float): String {
            val out = StringBuilder()
            val writer = Exporter.KmlWriter(out, mapOf(3L to placemark), default)
            writer.start(of)
            points.forEach { writer.add(it) }
            writer.finish()
            return out.toString()
        }

        assertTrue(write(track.copy(displayWidthDp = 2f), 4f).contains("<width>2</width>"))
        assertTrue("sinon celle du fichier", write(track, 4f).contains("<width>5</width>"))
        val bare = StringBuilder().also { out ->
            Exporter.KmlWriter(out, emptyMap(), 3f).apply { start(track); points.forEach { add(it.copy(placemarkId = null)) }; finish() }
        }.toString()
        assertTrue("sinon l'épaisseur générale", bare.contains("<width>3</width>"))
    }

    @Test
    fun `la couleur exportee se relit telle quelle`() {
        val written = com.example.util.KmlColor.format(france)
        assertEquals(france, com.example.util.KmlColor.parse(written))
    }
}
