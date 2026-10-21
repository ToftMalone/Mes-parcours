package com.example

import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.util.Exporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A merged track gathers several tracks into one. The exported file must reflect
 * it: **a single entry**, whose geometry groups as many
 * `<LineString>`s as there are segments.
 *
 * The two properties contradict each other if one is not careful: putting everything in
 * a single coordinates block does give one entry, but joins the segments with
 * a straight line on reimport; one Placemark per segment keeps the breaks,
 * but presents ten entries where the user had just made one.
 */
class KmlExportTest {

    private val track = Track(id = 1L, name = "Trips", startTime = 1_000_000L)

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
    fun `three segments give one entry and three lines`() {
        val points = listOf(
            point(1), point(2),
            point(3, discontinuous = true), point(4),
            point(5, discontinuous = true), point(6)
        )

        val kml = export(points)

        assertEquals("a single entry expected", 1, kml.countOf("<Placemark>"))
        assertEquals(1, kml.countOf("<MultiGeometry>"))
        assertEquals("one LineString per segment", 3, kml.countOf("<LineString>"))
        assertEquals(3, kml.countOf("<coordinates>"))
    }

    @Test
    fun `the pauses of a recording do not create separate entries`() {
        // A pause, a resume and a merge produce the same signal: the next point
        // opens a segment. None of the three must give one more entry
        // in the file — a track recorded in three goes remains one track.
        val points = listOf(
            point(1), point(2), point(3),
            point(4, discontinuous = true), point(5),   // exit from the first pause
            point(6, discontinuous = true), point(7)    // exit from the second pause
        )

        val kml = export(points)

        assertEquals("a recording stays one entry", 1, kml.countOf("<Placemark>"))
        assertEquals("one line per recorded piece", 3, kml.countOf("<LineString>"))
    }

    @Test
    fun `a single segment keeps the same envelope`() {
        // Writing being incremental, we do not know when opening the geometry
        // how many segments will follow: the envelope is therefore always the same.
        val kml = export(listOf(point(1), point(2), point(3)))

        assertEquals(1, kml.countOf("<Placemark>"))
        assertEquals(1, kml.countOf("<MultiGeometry>"))
        assertEquals(1, kml.countOf("<LineString>"))
    }

    @Test
    fun `the track no longer carries numbered names`() {
        // “Trips (2)”, “Trips (3)”… betrayed the splitting into several
        // entries, which is precisely what the merge sought to remove.
        val kml = export(listOf(point(1), point(2, discontinuous = true)))

        // Counted in the Placemark alone: the <Document> header also carries the
        // track's name, and counting it over the whole file would fail a test that
        // the code satisfies all the same.
        val placemark = kml.substringAfter("<Placemark>").substringBefore("</Placemark>")
        assertEquals(1, placemark.countOf("<name>Trips</name>"))
        assertTrue("no numbered name expected", !kml.contains("Trips ("))
    }

    @Test
    fun `coordinates stay in longitude latitude altitude order`() {
        // The reverse of the order read everywhere else: it is the KML
        // convention, and swapping it would send the tracks to the other side of the world.
        val kml = export(listOf(point(0)))

        assertTrue(kml.contains("6.0,45.0,100.0"))
    }

    @Test
    fun `an empty track produces no entry`() {
        val kml = export(emptyList())

        assertEquals(0, kml.countOf("<Placemark>"))
        assertTrue(kml.contains("</kml>"))
    }

    @Test
    fun `each segment closes its line before opening the next`() {
        // A missing </LineString> produces a file that Google Earth refuses
        // to open — and the error only shows in use, not at export.
        val kml = export(listOf(point(1), point(2, discontinuous = true)))

        assertEquals(kml.countOf("<LineString>"), kml.countOf("</LineString>"))
        assertEquals(kml.countOf("<coordinates>"), kml.countOf("</coordinates>"))
        assertEquals(kml.countOf("<MultiGeometry>"), kml.countOf("</MultiGeometry>"))
    }
}
