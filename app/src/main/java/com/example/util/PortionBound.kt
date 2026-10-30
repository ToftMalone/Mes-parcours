package com.example.util

import com.example.data.model.TrackPoint
import kotlin.math.cos

/**
 * Une borne de la portion à retirer, posée exactement là où l'on a touché le tracé.
 *
 * Les bornes étaient des points du parcours : un appui se rabattait sur le point le plus
 * proche. Sur un tracé dessiné dans Google Earth, deux points sont souvent séparés de
 * centaines de mètres, et la coupe tombait donc loin de l'endroit touché — « il ne coupe
 * pas exactement là où j'ai mis les points », rapporté par le père de l'auteur. La borne
 * est désormais la projection de l'appui sur le segment touché : [point] est fabriqué
 * entre [beforeId] et [afterId], à la fraction [fraction] du premier au second.
 *
 * Une borne qui tombe sur un point existant le désigne lui-même : [beforeId] et
 * [afterId] valent alors tous deux son identifiant, et [point] en est la copie.
 */
data class PortionBound(
    val beforeId: Long,
    val afterId: Long,
    val fraction: Double,
    val point: TrackPoint
) {
    val isVertex: Boolean get() = beforeId == afterId

    /** Ordre le long du parcours : les identifiants croissent avec le temps. */
    operator fun compareTo(other: PortionBound): Int =
        compareValuesBy(this, other, { it.beforeId }, { it.fraction })

    companion object {
        /** En deçà, la projection est confondue avec le point le plus proche, en mètres. */
        const val VERTEX_SNAP_METERS = 1.0

        fun at(point: TrackPoint) = PortionBound(point.id, point.id, 0.0, point)

        /**
         * La borne la plus proche de ([lat], [lon]) sur [points], suite ordonnée de points
         * réels : chaque paire de voisins forme un segment, sauf quand le second ouvre un
         * tronçon. Null si la liste est vide.
         */
        fun nearest(points: List<TrackPoint>, lat: Double, lon: Double): PortionBound? {
            if (points.isEmpty()) return null
            var best: PortionBound? = null
            var bestDistance = Double.MAX_VALUE
            for (i in points.indices) {
                val a = points[i]
                val b = points.getOrNull(i + 1)?.takeIf { !it.isDiscontinuous }
                val (t, d) = if (b == null) 0.0 to squaredMeters(a, lat, lon)
                else project(a, b, lat, lon)
                if (d < bestDistance) {
                    bestDistance = d
                    best = when {
                        b == null || t <= 0.0 -> at(a)
                        t >= 1.0 -> at(b)
                        else -> {
                            val candidate = interpolate(a, b, t)
                            when {
                                squaredMeters(a, candidate.latitude, candidate.longitude) <
                                    VERTEX_SNAP_METERS * VERTEX_SNAP_METERS -> at(a)
                                squaredMeters(b, candidate.latitude, candidate.longitude) <
                                    VERTEX_SNAP_METERS * VERTEX_SNAP_METERS -> at(b)
                                else -> PortionBound(a.id, b.id, t, candidate)
                            }
                        }
                    }
                }
            }
            return best
        }

        /**
         * La fraction de [a] à [b] où tombe la projection de ([lat], [lon]), bornée à
         * [0, 1], et le carré de la distance qui l'en sépare, en mètres. Plan local :
         * à l'échelle d'un segment, la courbure de la Terre ne compte pas.
         */
        fun project(a: TrackPoint, b: TrackPoint, lat: Double, lon: Double): Pair<Double, Double> {
            val k = cos(Math.toRadians(a.latitude))
            val bx = (b.longitude - a.longitude) * k
            val by = b.latitude - a.latitude
            val px = (lon - a.longitude) * k
            val py = lat - a.latitude
            val length2 = bx * bx + by * by
            val t = if (length2 == 0.0) 0.0 else ((px * bx + py * by) / length2).coerceIn(0.0, 1.0)
            val dx = px - bx * t
            val dy = py - by * t
            return t to (dx * dx + dy * dy) * METERS_PER_DEGREE * METERS_PER_DEGREE
        }

        private fun squaredMeters(a: TrackPoint, lat: Double, lon: Double): Double {
            val dx = (lon - a.longitude) * cos(Math.toRadians(a.latitude))
            val dy = lat - a.latitude
            return (dx * dx + dy * dy) * METERS_PER_DEGREE * METERS_PER_DEGREE
        }

        /** Le point fabriqué entre [a] et [b] : il prend l'apparence de [a]. */
        private fun interpolate(a: TrackPoint, b: TrackPoint, t: Double) = a.copy(
            id = 0,
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t,
            altitude = a.altitude + (b.altitude - a.altitude) * t,
            timestamp = a.timestamp + ((b.timestamp - a.timestamp) * t).toLong(),
            isDiscontinuous = false
        )

        private const val METERS_PER_DEGREE = 111_320.0
    }
}
