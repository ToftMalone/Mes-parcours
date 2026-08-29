package com.example

import com.example.data.model.TrackPoint
import com.example.util.CsvConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le convertisseur CSV ne touche ni Room ni un vrai fichier : ces tests portent
 * uniquement sur ses fonctions pures (écriture d'une ligne, lecture d'un en-tête,
 * lecture d'une ligne), directement — sans Robolectric, à la manière de
 * `KmlExportTest` pour l'export GPX/KML.
 */
class CsvConverterTest {

    private fun point(
        latitude: Double = 45.0,
        longitude: Double = 6.0,
        altitude: Double = 1200.0,
        speed: Float = 2.5f,
        timestamp: Long = 1_700_000_000_000L,
        isDiscontinuous: Boolean = false,
        segmentColor: Int? = null
    ) = TrackPoint(
        trackId = 0L,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speed = speed,
        timestamp = timestamp,
        isDiscontinuous = isDiscontinuous,
        segmentColor = segmentColor
    )

    private fun writeRow(point: TrackPoint): String {
        val out = StringBuilder()
        CsvConverter.writeRow(out, point)
        return out.toString()
    }

    // ------------------------------------------------------------------
    // En-tête
    // ------------------------------------------------------------------

    @Test
    fun `l en-tete porte les sept colonnes dans l ordre`() {
        assertEquals(
            "latitude,longitude,altitude_m,horodatage,vitesse_m_s,nouveau_troncon,couleur_troncon",
            CsvConverter.CSV_HEADER
        )
    }

    @Test
    fun `parseHeader retrouve les colonnes quel que soit leur ordre`() {
        val columns = CsvConverter.parseHeader("vitesse_m_s,longitude,latitude")
        assertEquals(2, columns?.latitude)
        assertEquals(1, columns?.longitude)
        assertEquals(0, columns?.speed)
        assertEquals(-1, columns?.altitude)
    }

    @Test
    fun `parseHeader n est pas sensible a la casse ni aux espaces`() {
        val columns = CsvConverter.parseHeader(" Latitude , LONGITUDE ")
        assertEquals(0, columns?.latitude)
        assertEquals(1, columns?.longitude)
    }

    @Test
    fun `parseHeader refuse un en-tete sans latitude ou longitude`() {
        assertNull(CsvConverter.parseHeader("longitude,altitude_m"))
        assertNull(CsvConverter.parseHeader("latitude,altitude_m"))
        assertNull(CsvConverter.parseHeader("altitude_m,horodatage"))
    }

    // ------------------------------------------------------------------
    // Écriture d'une ligne
    // ------------------------------------------------------------------

    @Test
    fun `une ligne porte les sept champs dans l ordre`() {
        val row = writeRow(
            point(latitude = 45.123, longitude = 6.456, altitude = 1200.0, speed = 2.5f)
        )
        val cells = row.trim().split(",")
        assertEquals(7, cells.size)
        assertEquals("45.123", cells[0])
        assertEquals("6.456", cells[1])
        assertEquals("1200.0", cells[2])
        assertEquals("2.5", cells[4])
    }

    @Test
    fun `un point sans couleur laisse la derniere colonne vide`() {
        val row = writeRow(point(segmentColor = null))
        assertTrue(row.trim().endsWith(","))
    }

    @Test
    fun `une rupture de troncon s ecrit 1, sinon 0`() {
        assertTrue(writeRow(point(isDiscontinuous = true)).contains(",1,"))
        assertTrue(writeRow(point(isDiscontinuous = false)).contains(",0,"))
    }

    // ------------------------------------------------------------------
    // Lecture d'une ligne
    // ------------------------------------------------------------------

    private val fullColumns = CsvConverter.parseHeader(CsvConverter.CSV_HEADER)!!
    private val minimalColumns = CsvConverter.parseHeader("latitude,longitude")!!

    @Test
    fun `une ligne complete se relit exactement`() {
        val line = "45.123,6.456,1200.0,2024-01-01T10:00:00Z,2.5,0,#FF8B5CF6"
        val point = CsvConverter.parseRow(fullColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 0L)!!

        assertEquals(45.123, point.latitude, 0.0)
        assertEquals(6.456, point.longitude, 0.0)
        assertEquals(1200.0, point.altitude, 0.0)
        assertEquals(2.5f, point.speed, 0.0f)
        assertFalse(point.isDiscontinuous)
        assertEquals(0xFF8B5CF6.toInt(), point.segmentColor)
    }

    @Test
    fun `une latitude illisible fait ignorer la ligne`() {
        val line = "pas-un-nombre,6.456"
        assertNull(CsvConverter.parseRow(minimalColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 0L))
    }

    @Test
    fun `une longitude absente fait ignorer la ligne`() {
        // Une seule cellule alors que deux colonnes sont attendues.
        val line = "45.123"
        assertNull(CsvConverter.parseRow(minimalColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 0L))
    }

    @Test
    fun `les colonnes facultatives absentes retombent sur des valeurs par defaut`() {
        val line = "45.123,6.456"
        val point = CsvConverter.parseRow(minimalColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 999_000L)!!

        assertEquals(0.0, point.altitude, 0.0)
        assertEquals(0f, point.speed, 0f)
        assertEquals(999_000L, point.timestamp)
        assertFalse(point.isDiscontinuous)
        assertNull(point.segmentColor)
    }

    @Test
    fun `le premier point ne peut jamais etre une rupture`() {
        // La colonne dit pourtant "1" : c'est le tout premier point du parcours,
        // il ne fait qu'ouvrir le tracé quoi qu'elle dise.
        val line = "45.123,6.456,1200.0,2024-01-01T10:00:00Z,2.5,1,"
        val point = CsvConverter.parseRow(fullColumns, line.split(","), isFirstPoint = true, fallbackTimestamp = 0L)!!
        assertFalse(point.isDiscontinuous)
    }

    @Test
    fun `un horodatage illisible retombe sur le repli plutot que de rejeter la ligne`() {
        val line = "45.123,6.456,1200.0,pas-une-date,2.5,0,"
        val point = CsvConverter.parseRow(fullColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 123_456L)!!
        assertEquals(123_456L, point.timestamp)
    }

    @Test
    fun `une couleur malformee est ignoree plutot que retenue`() {
        val line = "45.123,6.456,1200.0,2024-01-01T10:00:00Z,2.5,0,pas-une-couleur"
        val point = CsvConverter.parseRow(fullColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 0L)!!
        assertNull(point.segmentColor)
    }

    @Test
    fun `nouveau_troncon accepte 1, true et vrai`() {
        for (value in listOf("1", "true", "TRUE", "vrai", "VRAI")) {
            val line = "45.123,6.456,1200.0,2024-01-01T10:00:00Z,2.5,$value,"
            val point = CsvConverter.parseRow(fullColumns, line.split(","), isFirstPoint = false, fallbackTimestamp = 0L)!!
            assertTrue("« $value » doit être compris comme une rupture", point.isDiscontinuous)
        }
    }

    // ------------------------------------------------------------------
    // Aller-retour écriture / lecture
    // ------------------------------------------------------------------

    @Test
    fun `un point ecrit puis relu redonne les memes valeurs`() {
        val original = point(
            latitude = 45.987654,
            longitude = 6.123456,
            altitude = 850.5,
            speed = 3.75f,
            timestamp = 1_700_000_123_000L, // arrondi à la seconde par le format ISO 8601
            isDiscontinuous = true,
            segmentColor = 0xFF39FF14.toInt()
        )

        val row = writeRow(original)
        val rebuilt = CsvConverter.parseRow(fullColumns, row.trim().split(","), isFirstPoint = false, fallbackTimestamp = 0L)!!

        assertEquals(original.latitude, rebuilt.latitude, 0.0)
        assertEquals(original.longitude, rebuilt.longitude, 0.0)
        assertEquals(original.altitude, rebuilt.altitude, 0.0)
        assertEquals(original.speed, rebuilt.speed, 0.0f)
        assertEquals(1_700_000_123_000L, rebuilt.timestamp) // pas de milliseconde perdue ici : elle est nulle
        assertTrue(rebuilt.isDiscontinuous)
        assertEquals(original.segmentColor, rebuilt.segmentColor)
    }
}
