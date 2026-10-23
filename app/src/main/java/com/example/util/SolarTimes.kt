package com.example.util

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Heures de lever et de coucher du soleil, calculées localement.
 *
 * Aucun appel réseau : l'algorithme (équation du lever du soleil, formulation NOAA)
 * ne dépend que de la position et de la date, et tombe à moins de deux minutes des
 * heures publiées. C'est ce qui permet au thème de rester juste sans connexion — en
 * tunnel, en montagne, à l'étranger — sans clé d'API, et sans transmettre la
 * position de l'utilisateur à un service tiers.
 *
 * Sans dépendance Android : toute la logique est testable directement.
 */
object SolarTimes {

    private const val DAY_MS = 86_400_000.0

    /** Jour julien du 1ᵉʳ janvier 1970 à 0 h UTC. */
    private const val JULIAN_EPOCH = 2_440_587.5

    /** Jour julien du 1ᵉʳ janvier 2000 à 12 h TT (J2000). */
    private const val J2000 = 2_451_545.0

    /** Obliquité de l'écliptique, en degrés. */
    private const val OBLIQUITY = 23.4397

    /**
     * Élévation conventionnelle du centre du soleil au lever et au coucher : un peu
     * sous l'horizon, pour tenir compte du rayon apparent du disque solaire et de la
     * réfraction atmosphérique.
     */
    private const val SUNRISE_ELEVATION = -0.833

    sealed interface Result {
        /** Cas courant : le soleil se lève et se couche ce jour-là. */
        data class RiseAndSet(val sunriseUtcMillis: Long, val sunsetUtcMillis: Long) : Result

        /** Soleil de minuit : il ne se couche pas. */
        data object PolarDay : Result

        /** Nuit polaire : il ne se lève pas. */
        data object PolarNight : Result
    }

    /**
     * Lever et coucher du jour solaire local qui contient [atUtcMillis].
     *
     * [longitude] est comptée positive vers l'est.
     */
    fun compute(latitude: Double, longitude: Double, atUtcMillis: Long): Result {
        val julianDay = atUtcMillis / DAY_MS + JULIAN_EPOCH

        // Numéro du jour solaire local dont le midi est le plus proche de l'instant
        // demandé. Le décalage en longitude est indispensable : sans lui, une requête
        // faite après minuit UTC mais avant le lever du soleil renverrait le jour
        // précédent pour un fuseau à l'est, et l'inverse à l'ouest.
        val dayNumber = (julianDay - J2000 + longitude / 360.0).roundToLong().toDouble()

        // Temps solaire moyen ramené au méridien du lieu. Longitude est ⇒ midi
        // solaire plus tôt en UTC, d'où la soustraction.
        val meanSolarTime = dayNumber - longitude / 360.0

        // Anomalie moyenne du soleil.
        val meanAnomaly = (357.5291 + 0.98560028 * meanSolarTime).mod(360.0)
        val meanAnomalyRad = Math.toRadians(meanAnomaly)

        // Équation du centre : écart entre l'orbite réelle, elliptique, et une
        // orbite circulaire parcourue à vitesse constante.
        val center = 1.9148 * sin(meanAnomalyRad) +
                0.0200 * sin(2 * meanAnomalyRad) +
                0.0003 * sin(3 * meanAnomalyRad)

        // Longitude écliptique du soleil.
        val eclipticLongitude = (meanAnomaly + center + 180.0 + 102.9372).mod(360.0)
        val eclipticLongitudeRad = Math.toRadians(eclipticLongitude)

        // Midi solaire local, en jour julien.
        val solarNoon = J2000 + meanSolarTime +
                0.0053 * sin(meanAnomalyRad) -
                0.0069 * sin(2 * eclipticLongitudeRad)

        // Déclinaison du soleil.
        val sinDeclination = sin(eclipticLongitudeRad) * sin(Math.toRadians(OBLIQUITY))
        val declination = asin(sinDeclination)

        val latitudeRad = Math.toRadians(latitude)
        val cosHourAngle =
            (sin(Math.toRadians(SUNRISE_ELEVATION)) - sin(latitudeRad) * sinDeclination) /
                    (cos(latitudeRad) * cos(declination))

        // Hors de [-1, 1] : à cette latitude et à cette date, l'horizon n'est jamais
        // franchi. Il faut le traiter explicitement, sinon acos renvoie NaN.
        if (cosHourAngle > 1.0) return Result.PolarNight
        if (cosHourAngle < -1.0) return Result.PolarDay

        val hourAngle = Math.toDegrees(acos(cosHourAngle))

        return Result.RiseAndSet(
            sunriseUtcMillis = julianDayToMillis(solarNoon - hourAngle / 360.0),
            sunsetUtcMillis = julianDayToMillis(solarNoon + hourAngle / 360.0)
        )
    }

    /** Fait-il jour à cet endroit et à cet instant ? */
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
