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
}
