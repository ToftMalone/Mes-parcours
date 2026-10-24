package com.example.util

import com.example.data.model.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Réduit chaque arrêt d'un parcours à un seul point, en flux.
 *
 * Un arrêt, c'est une suite de points qui restent dans un cercle de [radiusMeters]
 * autour de leur centre pendant au moins [minDurationMs]. Le cas visé : l'enregistrement
 * oublié à l'arrivée. Sous un toit, le GPS saute de 3 à 10 m d'une seconde à l'autre et
 * dessine un gribouillis de plusieurs dizaines de mètres ; aucun seuil entre deux
 * points consécutifs ne l'efface — c'est ce que faisait la première version de l'outil,
 * qui n'en retirait que les points intermédiaires et le rendait anguleux. Ici, le
 * gribouillis entier devient un point, à son centre.
 *
 * **Pas de rupture de tronçon** à la place de l'arrêt, à la demande du père de
 * l'auteur : le tracé arrive au point puis en repart, d'un seul trait. Le point garde
 * l'horodatage de l'arrivée, si bien qu'un arrêt oublié en fin de parcours ne compte
 * plus dans la durée.
 *
 * Hors des arrêts, **tous les points sont gardés** : le tracé en mouvement reste aussi
 * fluide que l'original.
 *
 * Trois règles de plus, chacune pour un cas réel :
 *
 * - Une rupture de tronçon existante termine l'arrêt en cours et n'est jamais absorbée :
 *   deux tronçons distincts ne sont jamais recollés.
 * - Un arrêt tolère une **échappée** de moins de [maxExcursionMs] hors du cercle : un
 *   saut aberrant du GPS, à l'intérieur, dépasse parfois le rayon. Sans cette
 *   tolérance, chaque saut couperait l'arrêt en deux et laisserait une pointe — ou,
 *   avant que l'arrêt soit confirmé, l'empêcherait de l'être jamais.
 * - Tant qu'un arrêt n'est pas confirmé, ses points attendent dans [pending], et un
 *   arrêt candidat qui ne tient pas fait réexaminer ses points. Les deux tampons sont
 *   bornés en nombre ([maxPendingPoints], [maxExcursionPoints]), pas seulement en
 *   durée : un fichier dont tous les points portent le même horodatage — un GPX sans
 *   `<time>` — n'atteindrait jamais aucune durée, et le réexamen deviendrait
 *   quadratique. Revers : à 1 Hz, une durée minimale de plus d'une heure ne serait
 *   jamais atteinte.
 *
 * Les points sortent par [emit], dans l'ordre. Appeler [finish] après le dernier.
 */
class StopCollapser(
    private val radiusMeters: Double,
    private val minDurationMs: Long,
    private val maxExcursionMs: Long = DEFAULT_MAX_EXCURSION_MS,
    private val maxExcursionPoints: Int = DEFAULT_MAX_EXCURSION_POINTS,
    private val maxPendingPoints: Int = DEFAULT_MAX_PENDING_POINTS,
    private val emit: (TrackPoint) -> Unit
) {

    /** Nombre d'arrêts réduits à un point. */
    var stopCount = 0
        private set

    /** Points à traiter. Une file plutôt qu'une récursion : un arrêt non confirmé y remet ses points. */
    private val input = ArrayDeque<TrackPoint>()

    /** Points de l'arrêt en cours tant qu'il n'est pas confirmé. */
    private val pending = ArrayList<TrackPoint>()

    /** Points sortis du cercle d'un arrêt confirmé, en attendant de savoir s'il y revient. */
    private val excursion = ArrayList<TrackPoint>()

    private var first: TrackPoint? = null
    private var confirmed = false
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var sumAlt = 0.0
    private var count = 0

    fun add(point: TrackPoint) {
        input.addLast(point)
        while (input.isNotEmpty()) step(input.removeFirst())
    }

    fun finish() {
        var leftover = endCluster()
        while (leftover.isNotEmpty()) {
            requeue(leftover)
            while (input.isNotEmpty()) step(input.removeFirst())
            leftover = endCluster()
        }
    }

    private fun step(point: TrackPoint) {
        val anchor = first
        if (anchor == null || point.isDiscontinuous) {
            val leftover = endCluster()
            if (leftover.isNotEmpty()) {
                // L'échappée de l'arrêt clos est réexaminée avant ce point.
                requeue(leftover + point)
                return
            }
            startCluster(point)
            return
        }

        if (distanceMeters(sumLat / count, sumLon / count, point.latitude, point.longitude) <= radiusMeters) {
            // Retour dans le cercle : l'échappée n'était qu'un saut du GPS. Elle ne
            // compte pas dans le centre ; tant que l'arrêt n'est pas confirmé, elle reste
            // dans [pending], à rendre telle quelle s'il ne l'était jamais.
            if (!confirmed) pending += excursion
            excursion.clear()
            include(point)
            if (!confirmed) {
                if (point.timestamp - anchor.timestamp >= minDurationMs) {
                    tryConfirm()
                } else if (pending.size > maxPendingPoints) {
                    // Jamais confirmé : on rend tout tel quel et on repart de zéro.
                    pending.forEach(emit)
                    reset()
                }
            }
            return
        }

        excursion += point
        if (point.timestamp - excursion.first().timestamp <= maxExcursionMs &&
            excursion.size <= maxExcursionPoints
        ) return

        // L'échappée dure : on est vraiment parti.
        if (confirmed) {
            requeue(endCluster())
            return
        }
        // L'arrêt candidat ne tient pas : son premier point est un point ordinaire, et
        // les suivants sont réexaminés, puisqu'un arrêt peut commencer juste après.
        val again = pending.drop(1) + excursion
        emit(pending.first())
        reset()
        requeue(again)
    }

    /** Remet [points] en tête de file, dans leur ordre. */
    private fun requeue(points: List<TrackPoint>) {
        for (i in points.indices.reversed()) input.addFirst(points[i])
    }

    /**
     * Confirme l'arrêt candidat, après avoir rendu les points d'approche.
     *
     * Le centre se déplace pendant que l'arrêt se forme : au début, il suit les
     * derniers mètres de la marche qui y mène, et ces points y entrent. Une fois les
     * minutes d'arrêt accumulées, il est à sa vraie place. Les premiers points qui en
     * sont alors à plus du rayon sont l'approche, pas l'arrêt : ils sont rendus tels
     * quels, sans quoi le tracé couperait au plus court sur les dernières dizaines de
     * mètres. Si l'arrêt qui reste ne dure plus assez, la confirmation attend le point
     * suivant.
     */
    private fun tryConfirm() {
        val centerLat = sumLat / count
        val centerLon = sumLon / count
        var cut = 0
        while (cut < pending.lastIndex &&
            distanceMeters(centerLat, centerLon, pending[cut].latitude, pending[cut].longitude) > radiusMeters
        ) cut++
        if (pending.last().timestamp - pending[cut].timestamp < minDurationMs) return

        for (i in 0 until cut) {
            val approach = pending[i]
            emit(approach)
            sumLat -= approach.latitude
            sumLon -= approach.longitude
            sumAlt -= approach.altitude
            count--
        }
        first = pending[cut]
        confirmed = true
        pending.clear()
    }

    private fun startCluster(point: TrackPoint) {
        reset()
        first = point
        include(point)
    }

    private fun include(point: TrackPoint) {
        sumLat += point.latitude
        sumLon += point.longitude
        sumAlt += point.altitude
        count++
        if (!confirmed) pending += point
    }

    /**
     * Clôt l'arrêt en cours : un seul point s'il est confirmé, ses points tels quels
     * sinon. Renvoie l'échappée en attente, à réexaminer comme des points ordinaires.
     */
    private fun endCluster(): List<TrackPoint> {
        val anchor = first ?: return emptyList()
        if (confirmed) {
            stopCount++
            emit(
                anchor.copy(
                    latitude = sumLat / count,
                    longitude = sumLon / count,
                    altitude = sumAlt / count,
                    speed = 0f
                )
            )
            val leftover = excursion.toList()
            reset()
            return leftover
        }
        pending.forEach(emit)
        val leftover = excursion.toList()
        reset()
        return leftover
    }

    private fun reset() {
        first = null
        confirmed = false
        pending.clear()
        excursion.clear()
        sumLat = 0.0
        sumLon = 0.0
        sumAlt = 0.0
        count = 0
    }

    companion object {
        const val DEFAULT_MAX_EXCURSION_MS = 30_000L
        const val DEFAULT_MAX_EXCURSION_POINTS = 60
        const val DEFAULT_MAX_PENDING_POINTS = 4_000

        private const val EARTH_RADIUS_METERS = 6_371_000.0

        private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
