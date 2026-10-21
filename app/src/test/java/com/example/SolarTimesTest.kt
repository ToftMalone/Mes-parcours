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
 * The solar computation replaces a call to a weather service: it must therefore be right
 * with no safety net.
 *
 * The assertions cover verifiable physical properties (day length at the
 * solstices, symmetry at the equinox, polar day and night) rather than times
 * down to the last minute, which vary from one source to another depending on assumptions
 * about refraction and altitude.
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
        assertTrue("sunrise and sunset expected, got $result", result is SolarTimes.Result.RiseAndSet)
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
    fun `sunrise always precedes sunset`() {
        val result = riseAndSet(parisLat, parisLng, utc("2026-08-11"))
        assertTrue(result.sunriseUtcMillis < result.sunsetUtcMillis)
    }

    @Test
    fun `day length in Paris at the summer solstice`() {
        // Paris peaks at around 16 h 10 of daylight at the June solstice.
        val minutes = daylightMinutes(parisLat, parisLng, utc("2026-06-21"))
        assertTrue("duration obtained: $minutes min", minutes in 950..990)
    }

    @Test
    fun `day length in Paris at the winter solstice`() {
        // And drops to around 8 h 15 at the December solstice.
        val minutes = daylightMinutes(parisLat, parisLng, utc("2026-12-21"))
        assertTrue("duration obtained: $minutes min", minutes in 480..515)
    }

    @Test
    fun `about twelve hours of daylight at the equator at the equinox`() {
        val minutes = daylightMinutes(0.0, 0.0, utc("2026-03-20"))
        assertTrue("duration obtained: $minutes min", minutes in 715..730)
    }

    @Test
    fun `solar noon shifts with longitude`() {
        // One degree of longitude is four minutes. Paris, at 2.35° east, therefore sees
        // its solar noon about nine minutes before Greenwich's.
        val paris = riseAndSet(parisLat, parisLng, utc("2026-03-20"))
        val greenwich = riseAndSet(parisLat, 0.0, utc("2026-03-20"))
        val offsetMinutes = (greenwich.sunriseUtcMillis - paris.sunriseUtcMillis) / 60_000L
        assertTrue("offset obtained: $offsetMinutes min", offsetMinutes in 7..11)
    }

    @Test
    fun `midnight sun and polar night are handled`() {
        // North of the polar circle, the horizon is not crossed at the solstices: without
        // explicit handling, acos would return NaN.
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
    fun `it is day at noon and night at midnight`() {
        for (date in listOf("2026-01-15", "2026-04-15", "2026-07-15", "2026-10-15")) {
            assertTrue(
                "noon must be daytime on $date",
                SolarTimes.isDaylight(parisLat, parisLng, utc(date, "12:00"))
            )
            assertTrue(
                "midnight must be nighttime on $date",
                !SolarTimes.isDaylight(parisLat, parisLng, utc(date, "00:30"))
            )
        }
    }

    @Test
    fun `the local solar day is chosen on either side of midnight UTC`() {
        // A request made after midnight UTC but before sunrise must return the sunrise
        // of the coming day, not of the day before.
        val justAfterMidnight = utc("2026-08-11", "01:00")
        val result = riseAndSet(parisLat, parisLng, justAfterMidnight)
        assertTrue(
            "sunrise obtained at ${hhmmUtc(result.sunriseUtcMillis)} UTC",
            result.sunriseUtcMillis > justAfterMidnight
        )
        assertTrue(!SolarTimes.isDaylight(parisLat, parisLng, justAfterMidnight))
    }

    @Test
    fun `a zone far to the east stays consistent`() {
        // Auckland: solar noon falls around 00 h UTC, which puts the choice of
        // the local solar day to the test.
        val aucklandLat = -36.8485
        val aucklandLng = 174.7633
        val result = riseAndSet(aucklandLat, aucklandLng, utc("2026-08-11", "00:00"))
        assertTrue(result.sunriseUtcMillis < result.sunsetUtcMillis)

        val minutes = (result.sunsetUtcMillis - result.sunriseUtcMillis) / 60_000L
        // Mid-August in the southern hemisphere: end of winter, a little under 11 h of daylight.
        assertTrue("duration obtained: $minutes min", minutes in 620..680)
    }
}
