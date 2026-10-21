package com.example.util

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    var isMetric: Boolean = true

    private var appContext: Context? = null

    /**
     * Gives access to resources for the few words these formats contain
     * (“h”, “min”, “Track on …”). Called by `TrackApplication`: formats
     * are called from everywhere, including places with no `Context`.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun string(@StringRes id: Int, fallback: String, vararg args: Any): String =
        appContext?.getString(id, *args) ?: String.format(Locale.US, fallback, *args)

    /**
     * Date pattern adapted to the language (“27 sept.” in French, “Sep 27” in
     * English, “9月27日” in Japanese), where a hard-coded pattern only suits one
     * language.
     */
    private fun localizedFormat(skeleton: String): SimpleDateFormat {
        val locale = Locale.getDefault()
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** Time “09:14” or “9:14 AM” according to the language's usage and the 12/24 h setting. */
    fun formatClock(timestampMs: Long): String {
        val locale = Locale.getDefault()
        val skeleton = if (appContext?.let { DateFormat.is24HourFormat(it) } != false) "Hm" else "hm"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(timestampMs))
    }

    fun formatDistance(meters: Double): String {
        return if (isMetric) {
            if (meters < 1000.0) {
                String.format(Locale.getDefault(), "%.0f m", meters)
            } else {
                String.format(Locale.getDefault(), "%.2f km", meters / 1000.0)
            }
        } else {
            val miles = meters * 0.000621371
            if (miles < 0.1) {
                val feet = meters * 3.28084
                String.format(Locale.getDefault(), "%.0f ft", feet)
            } else {
                String.format(Locale.getDefault(), "%.2f mi", miles)
            }
        }
    }

    fun formatSpeed(speedMps: Double): String {
        return if (isMetric) {
            val speedKmh = speedMps * 3.6
            String.format(Locale.getDefault(), "%.1f km/h", speedKmh)
        } else {
            val speedMph = speedMps * 2.23694
            String.format(Locale.getDefault(), "%.1f mph", speedMph)
        }
    }

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    /**
     * Formatted altitude, or a dash when it is not known.
     *
     * A fallback “0 m” would read as a measurement at sea level, whereas it
     * only means the GPS did not provide a usable altitude.
     */
    fun formatElevationOrUnknown(elevationMeters: Double?): String =
        if (elevationMeters == null) "—" else formatElevation(elevationMeters)

    fun formatElevation(elevationMeters: Double): String {
        return if (isMetric) {
            String.format(Locale.getDefault(), "%.0f m", elevationMeters)
        } else {
            val elevationFeet = elevationMeters * 3.28084
            String.format(Locale.getDefault(), "%.0f ft", elevationFeet)
        }
    }

    fun formatDate(timestampMs: Long): String {
        val sdf = SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMdHm"), Locale.getDefault()
        )
        return sdf.format(Date(timestampMs))
    }

    /**
     * Name of a finished track: “Track on 09/27/2026 from 09:14 to 11:02”. Stored
     * in the database in the device's language at the time of stopping, as a
     * name typed by hand would be.
     */
    fun formatTrackName(startTimeMs: Long, endTimeMs: Long): String =
        string(
            R.string.track_name_finished, "Track of %1\$s from %2\$s to %3\$s",
            localizedFormat("yMd").format(Date(startTimeMs)),
            formatClock(startTimeMs),
            formatClock(endTimeMs)
        )

    fun formatTrackInProgressName(startTimeMs: Long): String =
        string(
            R.string.track_name_in_progress, "Track of %1\$s at %2\$s",
            localizedFormat("yMd").format(Date(startTimeMs)),
            formatClock(startTimeMs)
        )

    // ------------------------------------------------------------------
    // Short formats of the redesign (“18.4 km”, “4 h 12”, “Sun. Sep 27 · 09:14”).
    //
    // They serve for display in lists and sheets, where room is tight and
    // where precision to the meter or second teaches nothing. The long formats
    // above remain those of live tracking.
    // ------------------------------------------------------------------

    /** “850 m”, “6.1 km”, “18.4 km”, “142 km”: one decimal under 100 km. */
    fun formatDistanceShort(meters: Double): String {
        val locale = Locale.getDefault()
        return if (isMetric) {
            when {
                meters < 1000.0 -> String.format(locale, "%.0f m", meters)
                meters < 100_000.0 -> String.format(locale, "%.1f km", meters / 1000.0)
                else -> String.format(locale, "%,.0f km", meters / 1000.0)
            }
        } else {
            val miles = meters * 0.000621371
            if (miles < 100) String.format(locale, "%.1f mi", miles)
            else String.format(locale, "%,.0f mi", miles)
        }
    }

    /** “45 min”, “1 h 24”, “13 h 06”; “0 min” for a zero duration. */
    fun formatDurationShort(seconds: Long): String {
        val totalMinutes = seconds / 60
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h == 0L) string(R.string.duration_minutes, "%1\$d min", m)
        else string(R.string.duration_hours_minutes, "%1\$d h %2\$02d", h, m)
    }

    /** Elevation grouped by thousands: “386 m”, “1,204 m”. */
    fun formatElevationShort(elevationMeters: Double): String {
        val locale = Locale.getDefault()
        return if (isMetric) String.format(locale, "%,.0f m", elevationMeters)
        else String.format(locale, "%,.0f ft", elevationMeters * 3.28084)
    }

    /** “Sun. Sep 27 · 09:14”. */
    fun formatDayDate(timestampMs: Long): String {
        val day = localizedFormat("EEEdMMM").format(Date(timestampMs))
        return day.replaceFirstChar { it.uppercase() } + " · " + formatClock(timestampMs)
    }

    /** “Sep 27”: for “Imported on Sep 27”. */
    fun formatShortDate(timestampMs: Long): String =
        localizedFormat("dMMM").format(Date(timestampMs))

    /** “Sep 27, 2026”, or “Sep 27” without the year. */
    fun formatDateLong(timestampMs: Long, withYear: Boolean): String =
        localizedFormat(if (withYear) "dMMMy" else "dMMM").format(Date(timestampMs))
}
