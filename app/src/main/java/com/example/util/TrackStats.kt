package com.example.util

import android.location.Location
import com.example.data.model.LiveStats
import com.example.data.model.TrackPoint

/**
 * Cumule les statistiques d'un parcours **point par point**, sans jamais tenir la
 * liste complète.
 *
 * C'est la seule implémentation du calcul : `TrackRepository.calculateStatsFromPoints`
 * n'est plus qu'un appel à celle-ci sur une liste déjà en mémoire. Deux
 * implémentations parallèles — une pour les listes, une pour les flux — finiraient
 * par diverger, et deux parcours identiques afficheraient alors des chiffres
 * différents selon le chemin emprunté.
 *
 * Alimenter en flux est ce qui permet au découpage de calculer les statistiques de
 * chaque morceau pendant sa seule lecture du parcours d'origine, sans plafond de
 * taille ni seconde passe.
 */
class TrackStatsAccumulator {

    private val elevation = ElevationAccumulator()

    /** Réutilisé à chaque mesure : `distanceBetween` écrit son résultat dedans. */
    private val distanceResult = FloatArray(1)

    private var first: TrackPoint? = null
    private var previous: TrackPoint? = null
    private var count = 0

    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var speedSumMps = 0.0
    private var speedPoints = 0

    /** Ajoute un point. Les points doivent arriver dans l'ordre du parcours. */
    fun add(point: TrackPoint) {
        val prev = previous
        if (prev == null) {
            // Premier point : il ouvre l'altitude de référence, mais ne fournit ni
            // distance ni vitesse — il n'a pas de précédent avec quoi les mesurer.
            first = point
            elevation.add(point.altitude)
        } else {
            if (point.isDiscontinuous) {
                // Rupture de tronçon : ni la distance à vol d'oiseau depuis le
                // tronçon précédent, ni le saut d'altitude ne doivent être comptés.
                elevation.breakSegment()
            } else {
                Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    point.latitude, point.longitude,
                    distanceResult
                )
                val meters = distanceResult[0].toDouble()
                // Filtre anti-dérive : sous un mètre, c'est le bruit du GPS à
                // l'arrêt, pas un déplacement.
                if (meters > 1.0) distanceMeters += meters
            }
            elevation.add(point.altitude)

            val speed = point.speed.toDouble()
            if (speed > maxSpeedMps) maxSpeedMps = speed
            if (speed > 0.1) {
                speedSumMps += speed
                speedPoints++
            }
        }
        previous = point
        count++
    }

    /** Statistiques des points reçus jusqu'ici. Peut être appelé à tout moment. */
    fun result(): LiveStats {
        val start = first ?: return LiveStats()
        val end = previous ?: return LiveStats()

        val elapsedSeconds = if (count >= 2) (end.timestamp - start.timestamp) / 1000L else 0L

        return LiveStats(
            durationSec = maxOf(0L, elapsedSeconds),
            distanceMeters = distanceMeters,
            currentSpeedMps = end.speed.toDouble(),
            avgSpeedMps = if (speedPoints > 0) speedSumMps / speedPoints else 0.0,
            maxSpeedMps = maxSpeedMps,
            elevationGain = elevation.gainMeters,
            elevationLoss = elevation.lossMeters
        )
    }
}
