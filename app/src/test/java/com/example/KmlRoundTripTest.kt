package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.util.Exporter
import com.example.util.Importer
import org.junit.Assert.assertEquals
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
}
