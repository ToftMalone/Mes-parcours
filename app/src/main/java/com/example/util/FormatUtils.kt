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

    /**
     * L'application n'existe qu'en français : dates et nombres suivent l'usage
     * français quelle que soit la langue du téléphone, comme le reste de l'interface.
     */
    private val LOCALE: Locale = Locale.FRANCE

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
        appContext?.getString(id, *args) ?: String.format(LOCALE, fallback, *args)

    /** Motif de date à la française, tiré d'un squelette (« 27 sept. », « 27/09/2026 »). */
    private fun localizedFormat(skeleton: String): SimpleDateFormat {
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(LOCALE, skeleton), LOCALE)
    }

    /** Heure « 09:14 », toujours sur 24 heures. */
    fun formatClock(timestampMs: Long): String = localizedFormat("Hm").format(Date(timestampMs))

    fun formatDistance(meters: Double): String {
        return if (isMetric) {
            if (meters < 1000.0) {
                String.format(LOCALE, "%.0f m", meters)
            } else {
                String.format(LOCALE, "%.2f km", meters / 1000.0)
            }
        } else {
            val miles = meters * 0.000621371
            if (miles < 0.1) {
                val feet = meters * 3.28084
                String.format(LOCALE, "%.0f ft", feet)
            } else {
                String.format(LOCALE, "%.2f mi", miles)
            }
        }
    }

    fun formatSpeed(speedMps: Double): String {
        return if (isMetric) {
            val speedKmh = speedMps * 3.6
            String.format(LOCALE, "%.1f km/h", speedKmh)
        } else {
            val speedMph = speedMps * 2.23694
            String.format(LOCALE, "%.1f mph", speedMph)
        }
    }

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(LOCALE, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(LOCALE, "%02d:%02d", m, s)
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
            String.format(LOCALE, "%.0f m", elevationMeters)
        } else {
            val elevationFeet = elevationMeters * 3.28084
            String.format(LOCALE, "%.0f ft", elevationFeet)
        }
    }

    fun formatDate(timestampMs: Long): String {
        val sdf = SimpleDateFormat(
            DateFormat.getBestDateTimePattern(LOCALE, "yMdHm"), LOCALE
        )
        return sdf.format(Date(timestampMs))
    }

    /**
     * Nom d'un parcours terminé : « Parcours du 27/09/2026 de 09:14 à 11:02 ». Stocké
     * en base au moment de l'arrêt, comme le serait un nom saisi à la main.
     */
    fun formatTrackName(startTimeMs: Long, endTimeMs: Long): String =
        string(
            R.string.track_name_finished, "Parcours du %1\$s de %2\$s à %3\$s",
            localizedFormat("yMd").format(Date(startTimeMs)),
            formatClock(startTimeMs),
            formatClock(endTimeMs)
        )

    fun formatTrackInProgressName(startTimeMs: Long): String =
        string(
            R.string.track_name_in_progress, "Parcours du %1\$s à %2\$s",
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
        val locale = LOCALE
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
        val locale = LOCALE
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
