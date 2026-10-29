package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Track
import com.example.data.model.TrackPlacemark
import com.example.data.model.TrackPoint
import com.example.util.Exporter
import com.example.util.Importer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Un KML exporté puis réimporté retrouve ses couleurs de tronçon et ses ruptures :
 * rapporté par le père de l'auteur, dont le voyage colorié pays par pays ressortait
 * d'une seule couleur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KmlRoundTripTest {

    @Test
    fun `les couleurs de troncon survivent a l'export puis a l'import`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val france = 0xFF2F7DE1.toInt()
        val belgique = 0xFFE8505B.toInt()
        val points = (0 until 6).map { i ->
            TrackPoint(
                trackId = 1L,
                latitude = 45.0 + i / 1000.0,
                longitude = 6.0,
                timestamp = 1_000_000L + i * 1000L,
                isDiscontinuous = i == 3,
                segmentColor = if (i < 3) france else belgique
            )
        }
        val file = File(context.cacheDir, "voyage.kml")
        file.bufferedWriter().use { out ->
            val writer = Exporter.KmlWriter(out)
            writer.start(Track(id = 1L, name = "Voyage", startTime = 1_000_000L, isImported = true))
            points.forEach(writer::add)
            writer.finish()
        }

        val read = mutableListOf<TrackPoint>()
        Importer.importFromUri(context, Uri.fromFile(file), 7L) { read += it }

        assertEquals(6, read.size)
        assertEquals(points.map { it.segmentColor }, read.map { it.segmentColor })
        assertEquals("la rupture entre les deux pays est gardée", listOf(3), read.indices.filter { i -> i > 0 && read[i].isDiscontinuous })
    }

    /** Un fichier comme celui du père de l'auteur : dossiers imbriqués, styles partagés, tracés nommés. */
    private val googleEarthKml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
        <Document>
          <name>Mes traces</name>
          <Style id="bleu"><LineStyle><color>ffe17d2f</color><width>4</width></LineStyle></Style>
          <Style id="rouge"><LineStyle><color>ff5b50e8</color><width>2.5</width></LineStyle></Style>
          <StyleMap id="carte"><Pair><key>normal</key><styleUrl>#rouge</styleUrl></Pair></StyleMap>
          <Folder>
            <name>France</name>
            <Placemark><name>France</name><styleUrl>#bleu</styleUrl>
              <LineString><coordinates>6.0,45.0,0 6.001,45.001,0</coordinates></LineString></Placemark>
            <Folder>
              <name>A refaire</name>
              <Placemark><name>Col</name><styleUrl>#bleu</styleUrl>
                <LineString><coordinates>6.1,45.1,0 6.101,45.101,0</coordinates></LineString></Placemark>
            </Folder>
          </Folder>
          <Folder>
            <name>Etranger</name>
            <Placemark><name>Belgique</name><styleUrl>#carte</styleUrl>
              <MultiGeometry>
                <LineString><coordinates>4.0,50.0,0 4.001,50.001,0</coordinates></LineString>
                <LineString><coordinates>4.1,50.1,0 4.101,50.101,0</coordinates></LineString>
              </MultiGeometry></Placemark>
            <Placemark><name>Luxembourg</name>
              <Style><LineStyle><color>ff13a38a</color><width>6</width></LineStyle></Style>
              <LineString><coordinates>6.1,49.6,0 6.101,49.601,0</coordinates></LineString></Placemark>
          </Folder>
        </Document>
        </kml>
    """.trimIndent()

    private class Imported(val points: List<TrackPoint>, val placemarks: Map<Long, TrackPlacemark>, val name: String)

    private fun import(context: Context, text: String, fileName: String): Imported {
        val file = File(context.cacheDir, fileName)
        file.writeText(text)
        val placemarks = LinkedHashMap<Long, TrackPlacemark>()
        val points = mutableListOf<TrackPoint>()
        val summary = Importer.importFromUri(
            context, Uri.fromFile(file), 7L,
            onPlacemark = { p -> (placemarks.size + 1L).also { placemarks[it] = p.copy(id = it) } }
        ) { points += it }
        return Imported(points, placemarks, summary!!.name)
    }

    @Test
    fun `dossiers, noms, couleurs et epaisseurs ressortent comme ils sont entres`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = import(context, googleEarthKml, "mes-traces.kml")

        assertEquals("Mes traces", first.name)
        assertEquals(listOf("France", "Col", "Belgique", "Luxembourg"), first.placemarks.values.map { it.name })
        assertEquals(
            listOf(listOf("France"), listOf("France", "A refaire"), listOf("Etranger"), listOf("Etranger")),
            first.placemarks.values.map { it.folders }
        )
        assertEquals(listOf(4f, 4f, 2.5f, 6f), first.placemarks.values.map { it.width })

        val out = StringBuilder()
        val writer = Exporter.KmlWriter(out, first.placemarks)
        writer.start(Track(id = 7L, name = first.name, startTime = 0L, isImported = true))
        first.points.forEach(writer::add)
        writer.finish()
        val kml = out.toString()

        assertEquals("une entrée par tracé", 4, kml.split("<Placemark>").size - 1)
        assertEquals("France, A refaire, Etranger", 3, kml.split("<Folder>").size - 1)
        assertTrue("pas de mention de l'application dans un fichier rendu", !kml.contains("Mes parcours"))
        assertTrue(kml.contains("<width>2.5</width>"))

        val second = import(context, kml, "export.kml")
        assertEquals(first.placemarks.values.map { it.copy(id = 0) }, second.placemarks.values.map { it.copy(id = 0) })
        assertEquals(first.points.map { it.segmentColor }, second.points.map { it.segmentColor })
        assertEquals(first.points.map { it.isDiscontinuous }, second.points.map { it.isDiscontinuous })
    }
}
