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
     * Donne accès aux ressources pour les rares mots que ces formats contiennent
     * (« h », « min », « Parcours du … »). Appelé par `TrackApplication` : les formats
     * sont appelés depuis partout, y compris des endroits sans `Context`.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun string(@StringRes id: Int, fallback: String, vararg args: Any): String =
        appContext?.getString(id, *args) ?: String.format(Locale.US, fallback, *args)

    /**
     * Motif de date adapté à la langue (« 27 sept. » en français, « Sep 27 » en
     * anglais, « 9月27日 » en japonais), là où un motif écrit en dur ne vaut que pour
     * une langue.
     */
    private fun localizedFormat(skeleton: String): SimpleDateFormat {
        val locale = Locale.getDefault()
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** Heure « 09:14 » ou « 9:14 AM » selon l'usage de la langue et le réglage 12/24 h. */
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
        val sdf = SimpleDateFormat(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMdHm"), Locale.getDefault()
        )
        return sdf.format(Date(timestampMs))
    }

    /**
     * Nom d'un parcours terminé : « Parcours du 27/09/2026 à 09:14 à 11:02 ». Stocké
     * en base dans la langue de l'appareil au moment de l'arrêt, comme le serait un
     * nom saisi à la main.
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
    // Formats courts de la refonte (« 18,4 km », « 4 h 12 », « Dim. 27 sept. · 09:14 »).
    //
    // Ils servent à l'affichage dans les listes et les fiches, où la place compte et
    // où une précision au mètre ou à la seconde n'apprend rien. Les formats longs
    // ci-dessus restent ceux du suivi en direct.
    // ------------------------------------------------------------------

    /** « 850 m », « 6,1 km », « 18,4 km », « 142 km » : une décimale sous 100 km. */
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

    /** « 45 min », « 1 h 24 », « 13 h 06 » ; « 0 min » pour une durée nulle. */
    fun formatDurationShort(seconds: Long): String {
        val totalMinutes = seconds / 60
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h == 0L) string(R.string.duration_minutes, "%1\$d min", m)
        else string(R.string.duration_hours_minutes, "%1\$d h %2\$02d", h, m)
    }

    /** Dénivelé groupé par milliers : « 386 m », « 1 204 m ». */
    fun formatElevationShort(elevationMeters: Double): String {
        val locale = Locale.getDefault()
        return if (isMetric) String.format(locale, "%,.0f m", elevationMeters)
        else String.format(locale, "%,.0f ft", elevationMeters * 3.28084)
    }

    /** « Dim. 27 sept. · 09:14 ». */
    fun formatDayDate(timestampMs: Long): String {
        val day = localizedFormat("EEEdMMM").format(Date(timestampMs))
        return day.replaceFirstChar { it.uppercase() } + " · " + formatClock(timestampMs)
    }

    /** « 27 sept. » : pour « Importé le 27 sept. ». */
    fun formatShortDate(timestampMs: Long): String =
        localizedFormat("dMMM").format(Date(timestampMs))

    /** « 27 sept. 2026 », ou « 27 sept. » sans l'année. */
    fun formatDateLong(timestampMs: Long, withYear: Boolean): String =
        localizedFormat(if (withYear) "dMMMy" else "dMMM").format(Date(timestampMs))
}
