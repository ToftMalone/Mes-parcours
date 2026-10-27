package com.example.util

import com.example.data.model.TrackPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Réduit chaque arrêt d'un parcours à un point, en flux, **sans toucher au reste**.
 *
 * Le cas visé : l'enregistrement oublié, ou une longue pause sous un toit. Le GPS y
 * saute de 3 à 10 m d'une seconde à l'autre et dessine un gribouillis ; le père de
 * l'auteur veut le voir disparaître, mais garder la forme exacte du trajet autour.
 *
 * **Ce qui a échoué avant, et pourquoi.** La 2.2 écartait les points à moins de 1 à
 * 10 m du dernier point gardé : le bruit dépasse ce seuil, le gribouillis restait en
 * zigzags. La 2.2.1 cherchait des cercles de 30 m tenus cinq minutes, centrés sur la
 * moyenne des points : une marche lente, un aller-retour dans une impasse, tenaient
 * dans ce cercle et étaient avalés avec l'arrêt — le tracé perdait sa forme.
 *
 * **Le critère, désormais local et prudent.** Chaque point est jugé sur une fenêtre de
 * ±[HALF_WINDOW_MS] autour de lui (bornée aussi en nombre de points). Il est immobile si
 * la fenêtre :
 *
 * - tient dans [STILL_RADIUS_M] (quasiment rien n'a bougé), ou
 * - tient dans [SCRIBBLE_MAX_RADIUS_M] **et** son chemin parcouru vaut au moins
 *   [SCRIBBLE_RATIO] fois son diamètre : beaucoup de chemin pour peu d'étendue, la
 *   signature du bruit. Une marche, même sinueuse, même un demi-tour, avance : son
 *   chemin reste de l'ordre de son étendue.
 *
 * Le rayon de la fenêtre est pris au 80ᵉ centile autour de la **médiane** : un saut
 * aberrant isolé ne fait pas croire à un déplacement. Et quand le parcours porte des
 * vitesses (celles du GPS, mesurées par effet Doppler, fiables à l'arrêt), une vitesse
 * médiane au-dessus de [MOVING_SPEED_MPS] interdit de juger la fenêtre immobile : c'est
 * ce qui protège une marche lente au signal bruité.
 *
 * Une fenêtre immobile marque aussi ses points proches de son centre, à
 * [MAX_REACH_M] au plus : sans cela, les premières secondes d'un arrêt, dont la fenêtre
 * déborde encore sur la marche, resteraient en zigzags.
 *
 * **Un arrêt d'au moins [minDurationMs] devient deux points au même endroit** : l'un à
 * l'heure d'arrivée, l'autre à l'heure de départ. Deux, et non un : l'affichage coupe le
 * trait entre deux points voisins séparés de plus de quinze secondes
 * (`buildSegmentsFromPoints`), et un point unique aurait laissé un trou dans le tracé
 * d'un côté ou de l'autre — c'est ce qu'a montré la 2.2.1. La coupure tombe ainsi entre
 * deux points confondus, invisible. Un arrêt qui termine le parcours ne garde que le
 * point d'arrivée : la durée s'arrête alors à l'arrivée, comme l'attend l'utilisateur
 * qui a oublié d'arrêter l'enregistrement.
 *
 * Les ruptures de tronçon ne sont jamais traversées : chaque tronçon est traité seul.
 * Les tampons sont bornés en nombre comme en durée — un GPX sans `<time>` porte la même
 * heure partout, n'atteint jamais aucune durée, et n'a donc jamais d'arrêt.
 *
 * Les points sortent par [emit], dans l'ordre. Appeler [finish] après le dernier.
 */
class StopCollapser(
    private val minDurationMs: Long,
    private val emit: (TrackPoint) -> Unit
) {

    /** Nombre d'arrêts réduits. */
    var stopCount = 0
        private set

    private class Item(val point: TrackPoint) {
        var still = false
    }

    // --- 1. Classement, sur une fenêtre glissante ---

    /** Points du tronçon en cours encore dans la fenêtre d'un point à juger. */
    private val window = ArrayDeque<Item>()

    /** Indice, dans [window], du prochain point à juger. */
    private var next = 0

    fun add(point: TrackPoint) {
        if (point.isDiscontinuous && window.isNotEmpty()) {
            endSegment()
            endRun(trailing = false)
        }
        window.addLast(Item(point))
        while (next < window.size && aheadIsComplete(next)) {
            classify(next)
            next++
            dropBehind()
        }
    }

    fun finish() {
        endSegment()
        endRun(trailing = true)
    }

    /** Juge les derniers points du tronçon, sur des fenêtres tronquées, et les libère tous. */
    private fun endSegment() {
        while (next < window.size) {
            classify(next)
            next++
        }
        while (window.isNotEmpty()) release(window.removeFirst())
        next = 0
    }

    private fun aheadIsComplete(index: Int): Boolean {
        val lastIndex = window.size - 1
        return lastIndex - index >= HALF_WINDOW_POINTS ||
            window[lastIndex].point.timestamp - window[index].point.timestamp >= HALF_WINDOW_MS
    }

    /**
     * Libère les points qu'aucune fenêtre à venir ne contiendra plus : leur marque est
     * alors définitive.
     */
    private fun dropBehind() {
        if (next >= window.size) return
        val time = window[next].point.timestamp
        while (next > 0 &&
            (next > HALF_WINDOW_POINTS || window.first().point.timestamp < time - HALF_WINDOW_MS)
        ) {
            release(window.removeFirst())
            next--
        }
    }

    private fun classify(center: Int) {
        val time = window[center].point.timestamp
        var lo = center
        while (lo > 0 && center - (lo - 1) <= HALF_WINDOW_POINTS &&
            time - window[lo - 1].point.timestamp <= HALF_WINDOW_MS
        ) lo--
        var hi = center
        while (hi < window.size - 1 && (hi + 1) - center <= HALF_WINDOW_POINTS &&
            window[hi + 1].point.timestamp - time <= HALF_WINDOW_MS
        ) hi++
        val count = hi - lo + 1
        if (count < MIN_WINDOW_POINTS) return

        val lats = DoubleArray(count) { window[lo + it].point.latitude }
        val lons = DoubleArray(count) { window[lo + it].point.longitude }
        val centerLat = median(lats)
        val centerLon = median(lons)
        val metersPerDegLon = METERS_PER_DEG * cos(Math.toRadians(centerLat))

        fun distanceToCenter(p: TrackPoint): Double {
            val dy = (p.latitude - centerLat) * METERS_PER_DEG
            val dx = (p.longitude - centerLon) * metersPerDegLon
            return sqrt(dx * dx + dy * dy)
        }

        val distances = DoubleArray(count) { distanceToCenter(window[lo + it].point) }
        val radius = percentile(distances, 0.8)

        var path = 0.0
        for (i in lo + 1..hi) {
            val a = window[i - 1].point
            val b = window[i].point
            val dy = (b.latitude - a.latitude) * METERS_PER_DEG
            val dx = (b.longitude - a.longitude) * metersPerDegLon
            path += sqrt(dx * dx + dy * dy)
        }

        val speeds = DoubleArray(count) { window[lo + it].point.speed.toDouble() }
        val hasSpeeds = speeds.any { it > 0.0 }
        if (hasSpeeds && median(speeds) > MOVING_SPEED_MPS) return

        val still = radius <= STILL_RADIUS_M ||
            (radius <= SCRIBBLE_MAX_RADIUS_M && path >= SCRIBBLE_RATIO * 2 * radius)
        if (!still) return

        // Un point dont le GPS mesure qu'il avance n'est jamais absorbé, même au bord
        // d'un arrêt : ce sont les derniers mètres de la marche qui y mène.
        fun moving(i: Int) = hasSpeeds && speeds[i - lo] > MOVING_SPEED_MPS
        if (!moving(center)) window[center].still = true
        // Portée bornée : une fenêtre jugée immobile peut encore contenir quelques
        // secondes de la marche qui y mène, et son rayon s'en trouve gonflé. Sans borne,
        // l'arrêt avalait jusqu'à quarante mètres de marche de chaque côté.
        val reach = (radius * 1.5).coerceIn(STILL_RADIUS_M, MAX_REACH_M)
        for (i in lo..hi) {
            if (distances[i - lo] <= reach && !moving(i)) window[i].still = true
        }
    }

    // --- 2. Réduction des arrêts ---

    /** Points de l'arrêt en cours, tant qu'il n'a pas atteint [minDurationMs]. */
    private val pending = ArrayList<TrackPoint>()
    private var runFirst: TrackPoint? = null
    private var runLast: TrackPoint? = null
    private var confirmed = false
    private var sumLat = 0.0
    private var sumLon = 0.0
    private var sumAlt = 0.0
    private var runCount = 0

    private fun release(item: Item) {
        val point = item.point
        if (!item.still) {
            endRun(trailing = false)
            emit(point)
            return
        }
        if (runFirst == null) runFirst = point
        runLast = point
        sumLat += point.latitude
        sumLon += point.longitude
        sumAlt += point.altitude
        runCount++
        if (confirmed) return
        pending += point
        if (point.timestamp - runFirst!!.timestamp >= minDurationMs) {
            confirmed = true
            pending.clear()
        } else if (pending.size > MAX_PENDING_POINTS) {
            // Jamais assez long — un fichier sans heures : rendu tel quel.
            pending.forEach(emit)
            resetRun()
        }
    }

    private fun endRun(trailing: Boolean) {
        val first = runFirst ?: return
        if (!confirmed) {
            pending.forEach(emit)
            resetRun()
            return
        }
        val last = runLast!!
        val lat = sumLat / runCount
        val lon = sumLon / runCount
        val alt = sumAlt / runCount
        stopCount++
        emit(first.copy(latitude = lat, longitude = lon, altitude = alt, speed = 0f))
        if (!trailing) {
            emit(
                last.copy(
                    latitude = lat,
                    longitude = lon,
                    altitude = alt,
                    speed = 0f,
                    isDiscontinuous = false
                )
            )
        }
        resetRun()
    }

    private fun resetRun() {
        pending.clear()
        runFirst = null
        runLast = null
        confirmed = false
        sumLat = 0.0
        sumLon = 0.0
        sumAlt = 0.0
        runCount = 0
    }

    companion object {
        const val HALF_WINDOW_MS = 30_000L
        const val HALF_WINDOW_POINTS = 60
        const val MIN_WINDOW_POINTS = 5
        const val STILL_RADIUS_M = 6.0
        const val SCRIBBLE_MAX_RADIUS_M = 30.0
        const val SCRIBBLE_RATIO = 4.0
        const val MOVING_SPEED_MPS = 0.4
        const val MAX_REACH_M = 10.0
        const val MAX_PENDING_POINTS = 4_000

        private const val METERS_PER_DEG = 111_320.0

        private fun median(values: DoubleArray): Double = percentile(values, 0.5)

        private fun percentile(values: DoubleArray, q: Double): Double {
            val sorted = values.copyOf().also { it.sort() }
            return sorted[((sorted.size - 1) * q).toInt()]
        }
    }
}
