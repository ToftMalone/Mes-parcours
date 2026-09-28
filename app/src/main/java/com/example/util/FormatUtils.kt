package com.example.util

import java.text.SimpleDateFormat
import java.util.Locale

object FormatUtils {

    var isMetric: Boolean = true

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
     * Altitude formatée, ou un tiret quand elle n'est pas connue.
     *
     * Un « 0 m » de repli se lirait comme une mesure au niveau de la mer, alors qu'il
     * signifie seulement que le GPS n'a pas fourni d'altitude exploitable.
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
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return sdf.format(java.util.Date(timestampMs))
    }

    fun formatTrackName(startTimeMs: Long, endTimeMs: Long): String {
        val dateSdf = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val timeSdf = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val dateStr = dateSdf.format(java.util.Date(startTimeMs))
        val startStr = timeSdf.format(java.util.Date(startTimeMs))
        val endStr = timeSdf.format(java.util.Date(endTimeMs))
        return "Parcours du $dateStr à $startStr à $endStr"
    }

    fun formatTrackInProgressName(startTimeMs: Long): String {
        val dateSdf = SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
        val timeSdf = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val dateStr = dateSdf.format(java.util.Date(startTimeMs))
        val startStr = timeSdf.format(java.util.Date(startTimeMs))
        return "Parcours du $dateStr à $startStr"
    }

    // ------------------------------------------------------------------
    // Formats courts de la refonte (« 18,4 km », « 4 h 12 », « Dim. 27 sept. · 09:14 »).
    //
    // Ils servent à l'affichage dans les listes et les fiches, où la place compte et
    // où une précision au mètre ou à la seconde n'apprend rien. Les formats longs
    // ci-dessus restent ceux du suivi en direct.
    // ------------------------------------------------------------------

    /** « 850 m », « 6,1 km », « 18,4 km », « 142 km » : une décimale sous 100 km. */
    fun formatDistanceShort(meters: Double): String = if (isMetric) {
        when {
            meters < 1000.0 -> String.format(Locale.FRANCE, "%.0f m", meters)
            meters < 100_000.0 -> String.format(Locale.FRANCE, "%.1f km", meters / 1000.0)
            else -> String.format(Locale.FRANCE, "%,.0f km", meters / 1000.0)
        }
    } else {
        val miles = meters * 0.000621371
        if (miles < 100) String.format(Locale.US, "%.1f mi", miles)
        else String.format(Locale.US, "%,.0f mi", miles)
    }

    /** « 45 min », « 1 h 24 », « 13 h 06 » ; « 0 min » pour une durée nulle. */
    fun formatDurationShort(seconds: Long): String {
        val totalMinutes = seconds / 60
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h == 0L) "$m min" else String.format(Locale.FRANCE, "%d h %02d", h, m)
    }

    /** Dénivelé groupé par milliers : « 386 m », « 1 204 m ». */
    fun formatElevationShort(elevationMeters: Double): String = if (isMetric) {
        String.format(Locale.FRANCE, "%,.0f m", elevationMeters)
    } else {
        String.format(Locale.US, "%,.0f ft", elevationMeters * 3.28084)
    }

    /** « Dim. 27 sept. · 09:14 ». */
    fun formatDayDate(timestampMs: Long): String {
        val day = SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(java.util.Date(timestampMs))
        val time = SimpleDateFormat("HH:mm", Locale.FRANCE).format(java.util.Date(timestampMs))
        return day.replaceFirstChar { it.uppercase() } + " · " + time
    }

    /** « 27 sept. » : pour « Importé le 27 sept. ». */
    fun formatShortDate(timestampMs: Long): String =
        SimpleDateFormat("d MMM", Locale.FRANCE).format(java.util.Date(timestampMs))
}
