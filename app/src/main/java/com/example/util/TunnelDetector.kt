package com.example.util

/**
 * Détecte l'entrée dans un espace couvert et sombre — tunnel, parking, garage — et
 * la sortie qui s'ensuit, à partir du capteur de luminosité.
 *
 * Le critère n'est délibérément pas un seuil absolu. Un seuil absolu confond « il
 * fait sombre ici » avec « je suis à l'intérieur en pleine journée » : un salon
 * éclairé tourne autour de 100 à 300 lux, soit bien en dessous de la lumière du
 * dehors, et l'application basculait en thème sombre alors qu'il faisait grand jour.
 *
 * Ce qui signe un tunnel, c'est une chute **brutale et profonde** par rapport à la
 * luminosité des instants précédents, puis un retour franc de la lumière. D'où trois
 * conditions cumulées à l'entrée : obscurité en valeur absolue, effondrement relatif
 * à la référence ambiante, et persistance pendant un court délai.
 *
 * Sans dépendance Android : toute la logique est testable directement.
 */
class TunnelDetector(
    /** Il faut qu'il fasse vraiment noir, en valeur absolue (lux). */
    private val enterLux: Float = ENTER_LUX,
    /** ...et que ce soit une fraction infime de la luminosité ambiante précédente. */
    private val enterRatio: Float = ENTER_RATIO,
    /** Retour de la lumière franc, en valeur absolue (lux). */
    private val exitLux: Float = EXIT_LUX,
    /** Durée pendant laquelle l'obscurité doit se confirmer avant de basculer. */
    private val enterDelayMs: Long = ENTER_DELAY_MS,
    /** Idem pour le retour à la lumière. */
    private val exitDelayMs: Long = EXIT_DELAY_MS,
    /** Inertie de la référence ambiante, par relevé. */
    private val baselineSmoothing: Float = BASELINE_SMOOTHING
) {

    /** Luminosité ambiante de référence. Gelée tant que l'on est à l'intérieur. */
    private var baselineLux = Float.NaN

    /** Instant du premier relevé qui contredit l'état courant, ou [NO_TIME]. */
    private var pendingSince = NO_TIME

    var isInside: Boolean = false
        private set

    /** Repart de zéro : à appeler quand on cesse puis reprend l'écoute du capteur. */
    fun reset() {
        baselineLux = Float.NaN
        pendingSince = NO_TIME
        isInside = false
    }

    /**
     * Intègre un relevé du capteur et renvoie l'état courant.
     *
     * [elapsedMs] doit provenir d'une horloge monotone — l'heure système peut sauter.
     */
    fun onReading(lux: Float, elapsedMs: Long): Boolean {
        if (baselineLux.isNaN()) baselineLux = lux

        if (isInside) {
            updatePending(condition = lux >= exitLux, elapsedMs = elapsedMs, delayMs = exitDelayMs) {
                isInside = false
                // On repart de la lumière retrouvée, sans quoi la référence resterait
                // celle d'avant l'entrée et le prochain tunnel serait mal jugé.
                baselineLux = lux
            }
            return isInside
        }

        // La référence ne suit que la lumière du dehors. Y intégrer les relevés
        // sombres la tirerait vers le bas, et l'effondrement relatif qui caractérise
        // l'entrée d'un tunnel finirait par ne plus être détectable.
        if (lux > enterLux) {
            baselineLux += (lux - baselineLux) * baselineSmoothing
        }

        val plungedIntoDark = lux <= enterLux && lux <= baselineLux * enterRatio
        updatePending(condition = plungedIntoDark, elapsedMs = elapsedMs, delayMs = enterDelayMs) {
            isInside = true
        }
        return isInside
    }

    /**
     * Bascule via [onConfirmed] si [condition] tient depuis au moins [delayMs].
     * Toute infirmation remet le compteur à zéro : une obscurité d'un instant — un
     * pont, l'ombre d'un camion — ne doit pas faire clignoter tout l'affichage.
     */
    private inline fun updatePending(
        condition: Boolean,
        elapsedMs: Long,
        delayMs: Long,
        onConfirmed: () -> Unit
    ) {
        if (!condition) {
            pendingSince = NO_TIME
            return
        }
        if (pendingSince == NO_TIME) pendingSince = elapsedMs
        if (elapsedMs - pendingSince >= delayMs) {
            pendingSince = NO_TIME
            onConfirmed()
        }
    }

    companion object {
        private const val NO_TIME = Long.MIN_VALUE

        /**
         * Un intérieur éclairé se situe bien au-dessus : c'est ce qui évite de
         * basculer en sombre dans une pièce en pleine journée.
         */
        const val ENTER_LUX = 20f

        /** L'obscurité doit représenter moins de 8 % de la luminosité ambiante. */
        const val ENTER_RATIO = 0.08f

        /** Au-delà, la lumière est revenue pour de bon. */
        const val EXIT_LUX = 80f

        const val ENTER_DELAY_MS = 1_000L
        const val EXIT_DELAY_MS = 1_500L

        /** ≈ 4 s de constante de temps au rythme normal du capteur. */
        const val BASELINE_SMOOTHING = 0.05f
    }
}
