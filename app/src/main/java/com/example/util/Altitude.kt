package com.example.util

import android.content.Context
import android.location.Location
import android.location.altitude.AltitudeConverter
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException

/** Une altitude au-dessus du niveau de la mer, et son incertitude si elle est connue. */
data class AltitudeFix(
    val metersAboveSeaLevel: Double,
    val accuracyMeters: Float?
)

/**
 * Au-delà de cette incertitude verticale, l'altitude n'est pas montrée.
 *
 * La précision verticale du GPS vaut couramment une fois et demie à trois fois la
 * précision horizontale. Le service accepte des positions jusqu'à 65 m de précision
 * horizontale, ce qui autorise des altitudes à plus de 100 m près : les afficher
 * comme une mesure serait mentir.
 */
private const val MAX_VERTICAL_ACCURACY_METERS = 20f

/**
 * Convertit l'altitude brute d'une position GPS en altitude au-dessus du niveau de
 * la mer.
 *
 * `Location.getAltitude()` renvoie une hauteur au-dessus de l'**ellipsoïde WGS84**,
 * alors que toute altitude lue sur une carte, un panneau ou un topoguide se réfère au
 * **géoïde**, c'est-à-dire au niveau moyen des mers. L'écart entre les deux — de
 * l'ordre de 45 à 50 m sur la France métropolitaine — est un biais systématique, pas
 * du bruit : c'est lui qui rendait l'altitude affichée constamment trop haute.
 *
 * À partir d'Android 14, le système embarque un modèle de géoïde et fait la
 * conversion hors ligne via [AltitudeConverter]. En deçà, aucune altitude n'est
 * renvoyée : mieux vaut n'afficher aucune valeur qu'une valeur fausse de 50 m. C'est
 * ici, et nulle part ailleurs, qu'il faudrait brancher un modèle de géoïde embarqué
 * pour couvrir les versions antérieures.
 *
 * [resolve] fait des entrées-sorties (chargement des données de géoïde) : à n'appeler
 * que depuis un thread d'arrière-plan.
 */
class AltitudeResolver(context: Context) {

    private val appContext = context.applicationContext

    // Conservé d'un appel à l'autre : le convertisseur met en cache les données de
    // géoïde déjà chargées.
    private val converter: AltitudeConverter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) AltitudeConverter() else null

    /** Altitude au-dessus du niveau de la mer, ou null si le relevé est inexploitable. */
    fun resolve(location: Location): AltitudeFix? {
        // getAltitude() renvoie 0.0 quand la position ne porte pas d'altitude. Sans
        // ce test, ce 0.0 était enregistré comme une altitude réelle.
        if (!location.hasAltitude()) return null
        val converter = this.converter ?: return null
        return resolveWithGeoid(converter, location)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveWithGeoid(converter: AltitudeConverter, location: Location): AltitudeFix? {
        // Copie : addMslAltitudeToLocation modifie la position qu'on lui passe, et
        // l'appelant continue de se servir de la sienne.
        val converted = Location(location)
        try {
            converter.addMslAltitudeToLocation(appContext, converted)
        } catch (e: IOException) {
            // Données de géoïde illisibles : on préfère ne rien afficher.
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }

        if (!converted.hasMslAltitude()) return null

        val accuracy = when {
            converted.hasMslAltitudeAccuracy() -> converted.mslAltitudeAccuracyMeters
            location.hasVerticalAccuracy() -> location.verticalAccuracyMeters
            else -> null
        }
        if (accuracy != null && accuracy > MAX_VERTICAL_ACCURACY_METERS) return null

        return AltitudeFix(converted.mslAltitudeMeters, accuracy)
    }
}

/**
 * Lisse la suite des altitudes mesurées.
 *
 * Deux étages. D'abord une **médiane glissante**, qui écarte une valeur aberrante
 * isolée au lieu de la moyenner : une seule mesure à 40 m de la réalité ne déplace
 * pas la médiane, alors qu'elle contaminerait une moyenne pendant plusieurs
 * secondes. Ensuite une **moyenne exponentielle**, qui enlève le tremblement
 * résiduel et rend le nombre affiché stable.
 *
 * Sans dépendance Android : directement testable.
 */
class AltitudeSmoother(
    private val windowSize: Int = MEDIAN_WINDOW,
    private val smoothing: Double = SMOOTHING
) {

    private val window = ArrayDeque<Double>()

    /** Dernière valeur lissée, ou null si aucune mesure n'a encore été intégrée. */
    var current: Double? = null
        private set

    /** Repart de zéro : à appeler quand la continuité de la série est rompue. */
    fun reset() {
        window.clear()
        current = null
    }

    /** Intègre une mesure et renvoie l'altitude lissée. */
    fun add(meters: Double): Double {
        window.addLast(meters)
        if (window.size > windowSize) window.removeFirst()

        val median = window.sorted().let { sorted ->
            val middle = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[middle]
            else (sorted[middle - 1] + sorted[middle]) / 2.0
        }

        val previous = current
        val next = if (previous == null) median else previous + (median - previous) * smoothing
        current = next
        return next
    }

    companion object {
        /** Impair, pour que la médiane soit une valeur réellement mesurée. */
        const val MEDIAN_WINDOW = 5
        const val SMOOTHING = 0.35
    }
}
