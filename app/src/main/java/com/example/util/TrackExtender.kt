package com.example.util

import android.location.Location
import com.example.data.model.TrackPoint
import kotlin.math.ceil

/**
 * Fabrique les points d'un tracé dessiné à la main, pour prolonger un parcours
 * (« j'ai arrêté ma trace trop tôt », demandé par le père de l'auteur).
 *
 * Le tracé part de [anchor] — le dernier point du parcours, ou le premier si l'on
 * prolonge par le début — et passe par chaque appui, en lignes droites. Les points
 * fabriqués portent une heure, déduite de la distance à une vitesse donnée : sans
 * elle, ni durée ni vitesse moyenne n'auraient de sens.
 *
 * **Chaque ligne est redécoupée en points espacés de [STEP_SECONDS] au plus.** Deux
 * appuis à 300 m l'un de l'autre, à allure de marche, sont séparés de plus de quatre
 * minutes : or l'affichage coupe le trait entre deux voisins distants de plus de
 * quinze secondes (`buildSegmentsFromPoints`). Sans ce redécoupage, le prolongement
 * serait dessiné en pointillés, voire pas du tout.
 *
 * L'altitude, la couleur de tronçon et le tracé KML sont ceux de [anchor] : rien de mieux n'est
 * connu, et une altitude à zéro fausserait le dénivelé.
 */
object TrackExtender {

    /** Intervalle maximal entre deux points fabriqués, en secondes. */
    const val STEP_SECONDS = 5.0

    /** Allure retenue quand le parcours n'en donne aucune exploitable : la marche. */
    const val FALLBACK_SPEED_MPS = 1.2

    /** En deçà, la vitesse moyenne du parcours n'est pas prise pour une allure. */
    const val MIN_SPEED_MPS = 0.3

    /** L'allure à prêter au prolongement, d'après la vitesse moyenne du parcours. */
    fun speedFor(trackAvgSpeedMps: Double): Double =
        if (trackAvgSpeedMps >= MIN_SPEED_MPS) trackAvgSpeedMps else FALLBACK_SPEED_MPS

    /**
     * Les points du prolongement, dans l'ordre où ils prendront place dans le
     * parcours. [taps] est la suite des appuis (latitude, longitude), du plus proche
     * de [anchor] au plus éloigné. Avec [forward], les points suivent [anchor] dans le
     * temps ; sinon ils le précèdent, et la liste va du plus lointain jusqu'à lui.
     * [anchor] lui-même n'y figure pas.
     */
    fun build(
        anchor: TrackPoint,
        taps: List<Pair<Double, Double>>,
        speedMps: Double,
        forward: Boolean,
        trackId: Long
    ): List<TrackPoint> {
        val result = ArrayList<TrackPoint>()
        val stepMeters = speedMps * STEP_SECONDS
        val distance = FloatArray(1)
        var fromLat = anchor.latitude
        var fromLon = anchor.longitude
        var elapsedMeters = 0.0
        for ((toLat, toLon) in taps) {
            Location.distanceBetween(fromLat, fromLon, toLat, toLon, distance)
            val length = distance[0].toDouble()
            val steps = maxOf(1, ceil(length / stepMeters).toInt())
            for (i in 1..steps) {
                val f = i.toDouble() / steps
                val offsetMs = ((elapsedMeters + length * f) / speedMps * 1000).toLong()
                result += TrackPoint(
                    trackId = trackId,
                    latitude = fromLat + (toLat - fromLat) * f,
                    longitude = fromLon + (toLon - fromLon) * f,
                    altitude = anchor.altitude,
                    speed = speedMps.toFloat(),
                    timestamp = if (forward) anchor.timestamp + offsetMs else anchor.timestamp - offsetMs,
                    segmentColor = anchor.segmentColor,
                    placemarkId = anchor.placemarkId
                )
            }
            elapsedMeters += length
            fromLat = toLat
            fromLon = toLon
        }
        if (!forward) result.reverse()
        return result
    }
}
