package com.example

import com.example.util.SolarTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Le calcul solaire remplace un appel à un service météo : il doit donc être juste
 * sans filet.
 *
 * Les assertions portent sur des propriétés physiques vérifiables (durée du jour aux
 * solstices, symétrie à l'équinoxe, jour et nuit polaires) plutôt que sur des heures
 * au dernier tour de minute, qui varient d'une source à l'autre selon les hypothèses
 * de réfraction et d'altitude.
 */
class SolarTimesTest {

    private val parisLat = 48.8566
    private val parisLng = 2.3522

    private fun utc(date: String, time: String = "12:00"): Long =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()

    private fun riseAndSet(lat: Double, lng: Double, millis: Long): SolarTimes.Result.RiseAndSet {
        val result = SolarTimes.compute(lat, lng, millis)
        assertTrue("lever et coucher attendus, obtenu $result", result is SolarTimes.Result.RiseAndSet)
        return result as SolarTimes.Result.RiseAndSet
    }

    private fun daylightMinutes(lat: Double, lng: Double, millis: Long): Long {
        val result = riseAndSet(lat, lng, millis)
        return (result.sunsetUtcMillis - result.sunriseUtcMillis) / 60_000L
    }

    private fun hhmmUtc(millis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC)
            .toLocalTime()
            .withSecond(0)
            .withNano(0)
            .toString()

    @Test
    fun `le lever precede toujours le coucher`() {
        val result = riseAndSet(parisLat, parisLng, utc("2026-08-11"))
        assertTrue(result.sunriseUtcMillis < result.sunsetUtcMillis)
    }

    @Test
    fun `duree du jour a Paris au solstice d ete`() {
        // Paris culmine autour de 16 h 10 de jour au solstice de juin.
        val minutes = daylightMinutes(parisLat, parisLng, utc("2026-06-21"))
        assertTrue("durée obtenue : $minutes min", minutes in 950..990)
    }

    @Test
    fun `duree du jour a Paris au solstice d hiver`() {
        // Et descend autour de 8 h 15 au solstice de décembre.
        val minutes = daylightMinutes(parisLat, parisLng, utc("2026-12-21"))
        assertTrue("durée obtenue : $minutes min", minutes in 480..515)
    }

    @Test
    fun `environ douze heures de jour a l equateur a l equinoxe`() {
        val minutes = daylightMinutes(0.0, 0.0, utc("2026-03-20"))
        assertTrue("durée obtenue : $minutes min", minutes in 715..730)
    }

    @Test
    fun `le midi solaire se decale avec la longitude`() {
        // Un degré de longitude vaut quatre minutes. Paris, à 2,35° est, voit donc
        // son midi solaire environ neuf minutes avant celui de Greenwich.
        val paris = riseAndSet(parisLat, parisLng, utc("2026-03-20"))
        val greenwich = riseAndSet(parisLat, 0.0, utc("2026-03-20"))
        val offsetMinutes = (greenwich.sunriseUtcMillis - paris.sunriseUtcMillis) / 60_000L
        assertTrue("décalage obtenu : $offsetMinutes min", offsetMinutes in 7..11)
    }

    @Test
    fun `le soleil de minuit et la nuit polaire sont traites`() {
        // Au nord du cercle polaire, l'horizon n'est pas franchi aux solstices : sans
        // traitement explicite, acos renverrait NaN.
        assertEquals(
            SolarTimes.Result.PolarDay,
            SolarTimes.compute(78.0, 15.0, utc("2026-06-21"))
        )
        assertEquals(
            SolarTimes.Result.PolarNight,
            SolarTimes.compute(78.0, 15.0, utc("2026-12-21"))
        )

        assertTrue(SolarTimes.isDaylight(78.0, 15.0, utc("2026-06-21", "23:30")))
        assertTrue(!SolarTimes.isDaylight(78.0, 15.0, utc("2026-12-21", "12:00")))
    }

    @Test
    fun `il fait jour a midi et nuit a minuit`() {
        for (date in listOf("2026-01-15", "2026-04-15", "2026-07-15", "2026-10-15")) {
            assertTrue(
                "midi doit être de jour le $date",
                SolarTimes.isDaylight(parisLat, parisLng, utc(date, "12:00"))
            )
            assertTrue(
                "minuit doit être de nuit le $date",
                !SolarTimes.isDaylight(parisLat, parisLng, utc(date, "00:30"))
            )
        }
    }

    @Test
    fun `le jour solaire local est choisi de part et d autre de minuit UTC`() {
        // Une requête faite après minuit UTC mais avant le lever doit rendre le lever
        // du jour qui s'annonce, et non celui de la veille.
        val justAfterMidnight = utc("2026-08-11", "01:00")
        val result = riseAndSet(parisLat, parisLng, justAfterMidnight)
        assertTrue(
            "lever obtenu à ${hhmmUtc(result.sunriseUtcMillis)} UTC",
            result.sunriseUtcMillis > justAfterMidnight
        )
        assertTrue(!SolarTimes.isDaylight(parisLat, parisLng, justAfterMidnight))
    }

    @Test
    fun `un fuseau tres a l est reste coherent`() {
        // Auckland : le midi solaire tombe vers 00 h UTC, ce qui met à l'épreuve le
        // choix du jour solaire local.
        val aucklandLat = -36.8485
        val aucklandLng = 174.7633
        val result = riseAndSet(aucklandLat, aucklandLng, utc("2026-08-11", "00:00"))
        assertTrue(result.sunriseUtcMillis < result.sunsetUtcMillis)

        val minutes = (result.sunsetUtcMillis - result.sunriseUtcMillis) / 60_000L
        // Mi-août dans l'hémisphère sud : fin d'hiver, un peu moins de 11 h de jour.
        assertTrue("durée obtenue : $minutes min", minutes in 620..680)
    }
}
