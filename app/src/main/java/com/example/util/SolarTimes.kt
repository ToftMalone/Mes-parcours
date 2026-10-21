package com.example.util

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise and sunset times, computed locally.
 *
 * No network call: the algorithm (sunrise equation, NOAA formulation)
 * depends only on position and date, and lands within two minutes of the published
 * times. That is what lets the theme stay right without a connection — in a
 * tunnel, in the mountains, abroad — without an API key, and without sending the
 * user's position to a third-party service.
 *
 * No Android dependency: all the logic is directly testable.
 */
object SolarTimes {

    private const val DAY_MS = 86_400_000.0

    /** Julian day of January 1, 1970 at 0 h UTC. */
    private const val JULIAN_EPOCH = 2_440_587.5

    /** Julian day of January 1, 2000 at 12 h TT (J2000). */
    private const val J2000 = 2_451_545.0

    /** Obliquity of the ecliptic, in degrees. */
    private const val OBLIQUITY = 23.4397

    /**
     * Conventional elevation of the sun's center at sunrise and sunset: slightly
     * below the horizon, to account for the apparent radius of the solar disc and for
     * atmospheric refraction.
     */
    private const val SUNRISE_ELEVATION = -0.833

    sealed interface Result {
        /** Usual case: the sun rises and sets that day. */
        data class RiseAndSet(val sunriseUtcMillis: Long, val sunsetUtcMillis: Long) : Result

        /** Midnight sun: it does not set. */
        data object PolarDay : Result

        /** Polar night: it does not rise. */
        data object PolarNight : Result
    }

    /**
     * Sunrise and sunset of the local solar day that contains [atUtcMillis].
     *
     * [longitude] is counted positive toward the east.
     */
    fun compute(latitude: Double, longitude: Double, atUtcMillis: Long): Result {
        val julianDay = atUtcMillis / DAY_MS + JULIAN_EPOCH

        // Number of the local solar day whose noon is closest to the requested
        // instant. The longitude offset is essential: without it, a request
        // made after midnight UTC but before sunrise would return the previous day
        // for a zone to the east, and the reverse to the west.
        val dayNumber = (julianDay - J2000 + longitude / 360.0).roundToLong().toDouble()

        // Mean solar time brought back to the place's meridian. East longitude ⇒ solar
        // noon earlier in UTC, hence the subtraction.
        val meanSolarTime = dayNumber - longitude / 360.0

        // Mean anomaly of the sun.
        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarTime).mod(360.0)
        val meanAnomalyRad = Math.toRadians(meanAnomaly)

        // Equation of the center: gap between the real, elliptical orbit and a
        // circular orbit traveled at constant speed.
        val center = 1.9148 * sin(meanAnomalyRad) +
                0.0200 * sin(2 * meanAnomalyRad) +
                0.0003 * sin(3 * meanAnomalyRad)

        // Ecliptic longitude of the sun.
        val eclipticLongitude = (meanAnomaly + center + 180.0 + 102.9372).mod(360.0)
        val eclipticLongitudeRad = Math.toRadians(eclipticLongitude)

        // Local solar noon, in Julian day.
        val solarNoon = J2000 + meanSolarTime +
                0.0053 * sin(meanAnomalyRad) -
                0.0069 * sin(2 * eclipticLongitudeRad)

        // Declination of the sun.
        val sinDeclination = sin(eclipticLongitudeRad) * sin(Math.toRadians(OBLIQUITY))
        val declination = asin(sinDeclination)

        val latitudeRad = Math.toRadians(latitude)
        val cosHourAngle =
            (sin(Math.toRadians(SUNRISE_ELEVATION)) - sin(latitudeRad) * sinDeclination) /
                    (cos(latitudeRad) * cos(declination))

        // Outside [-1, 1]: at this latitude and on this date, the horizon is never
        // crossed. It must be handled explicitly, otherwise acos returns NaN.
        if (cosHourAngle > 1.0) return Result.PolarNight
        if (cosHourAngle < -1.0) return Result.PolarDay

        val hourAngle = Math.toDegrees(acos(cosHourAngle))

        return Result.RiseAndSet(
            sunriseUtcMillis = julianDayToMillis(solarNoon - hourAngle / 360.0),
            sunsetUtcMillis = julianDayToMillis(solarNoon + hourAngle / 360.0)
        )
    }

    /** Is it daytime at this place and this instant? */
    fun isDaylight(latitude: Double, longitude: Double, atUtcMillis: Long): Boolean =
        when (val result = compute(latitude, longitude, atUtcMillis)) {
            is Result.RiseAndSet ->
                atUtcMillis >= result.sunriseUtcMillis && atUtcMillis < result.sunsetUtcMillis

            Result.PolarDay -> true
            Result.PolarNight -> false
        }

    private fun julianDayToMillis(julianDay: Double): Long =
        ((julianDay - JULIAN_EPOCH) * DAY_MS).roundToLong()
}
