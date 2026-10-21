package com.example.util

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException

/** An altitude above sea level, and its uncertainty if known. */
data class AltitudeFix(
    val metersAboveSeaLevel: Double,
    val accuracyMeters: Float?
)

/**
 * Beyond this vertical uncertainty, the altitude is not shown.
 *
 * The vertical accuracy of GPS is commonly one and a half to three times the
 * horizontal accuracy. The service accepts positions up to 65 m of horizontal
 * accuracy, which allows altitudes off by more than 100 m: displaying them
 * as a measurement would be lying.
 */
private const val MAX_VERTICAL_ACCURACY_METERS = 20f

/**
 * Converts the raw altitude of a GPS position to altitude above
 * sea level.
 *
 * `Location.getAltitude()` returns a height above the **WGS84 ellipsoid**,
 * whereas any altitude read on a map, a sign or a guidebook refers to the
 * **geoid**, that is mean sea level. The gap between the two — around
 * 45 to 50 m over metropolitan France — is a systematic bias, not
 * noise: it is what made the displayed altitude constantly too high.
 *
 * From Android 14, the system embeds a geoid model and does the
 * conversion offline via [AltitudeConverter]. Below that, no altitude is
 * returned: better to show no value than a value wrong by 50 m. It is
 * here, and nowhere else, that an embedded geoid model would have to be plugged in
 * to cover earlier versions.
 *
 * [resolve] does I/O (loading geoid data): to be called
 * only from a background thread.
 */
class AltitudeResolver(context: Context) {

    private val appContext = context.applicationContext

    // Kept from one call to the next: the converter caches the geoid
    // data already loaded.
    private val converter: AltitudeConverter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AltitudeConverter() else null

    /** Altitude above sea level, or null if the reading is unusable. */
    fun resolve(location: Location): AltitudeFix? {
        // getAltitude() returns 0.0 when the position carries no altitude. Without
        // this test, that 0.0 was recorded as a real altitude.
        if (!location.hasAltitude()) return null
        val converter = this.converter ?: return null
        return resolveWithGeoid(converter, location)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithGeoid(converter: AltitudeConverter, location: Location): AltitudeFix? {
        // Copy: addMslAltitudeToLocation modifies the position passed to it, and
        // the caller keeps using its own.
        val converted = Location(location)
        try {
            converter.addMslAltitudeToLocation(appContext, converted)
        } catch (e: IOException) {
            // Unreadable geoid data: we prefer to show nothing.
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }

        if (!converted.hasMslAltitude()) return null

        val accuracy = when {
            converted.hasMslAltitudeAccuracy() -> converted.mslAltitudeAccuracyMeters
            location.hasVerticalAccuracy() -> location.verticalAccuracyMeters
            else -> null
        }
        if (accuracy != null && accuracy > MAX_VERTICAL_ACCURACY_METERS) return null

        return AltitudeFix(converted.mslAltitudeMeters, accuracy)
    }
}

/**
 * Smooths the series of measured altitudes.
 *
 * Two stages. First a **moving median**, which discards an isolated outlier
 * instead of averaging it: a single measurement 40 m off does not move
 * the median, whereas it would contaminate an average for several
 * seconds. Then an **exponential average**, which removes the residual
 * jitter and makes the displayed number stable.
 *
 * No Android dependency: directly testable.
 */
class AltitudeSmoother(
    private val windowSize: Int = MEDIAN_WINDOW,
    private val smoothing: Double = SMOOTHING
) {

    private val window = ArrayDeque<Double>()

    /** Last smoothed value, or null if no measurement has been integrated yet. */
    var current: Double? = null
        private set

    /** Starts from zero: to be called when the continuity of the series is broken. */
    fun reset() {
        window.clear()
        current = null
    }

    /** Integrates a measurement and returns the smoothed altitude. */
    fun add(meters: Double): Double {
        window.addLast(meters)
        if (window.size > windowSize) window.removeFirst()

        val median = window.sorted().let { sorted ->
            val middle = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[middle]
            else (sorted[middle - 1] + sorted[middle]) / 2.0
        }

        val previous = current
        val next = if (previous == null) median else previous + (median - previous) * smoothing
        current = next
        return next
    }

    companion object {
        /** Odd, so that the median is a value that was really measured. */
        const val MEDIAN_WINDOW = 5
        const val SMOOTHING = 0.35
    }
}
