package com.example.util

/**
 * Cumule le dénivelé positif et négatif d'un parcours.
 *
 * L'implémentation précédente comparait chaque altitude à la précédente et ne
 * retenait l'écart que s'il dépassait 0,80 m. Ce critère se trompait dans les deux
 * sens :
 *
 * - **Il ratait les montées régulières.** À 1 Hz, en marchant à 1,4 m/s sur une pente
 *   de 10 %, l'altitude ne progresse que de 0,14 m par seconde. Aucun écart entre
 *   deux points consécutifs n'atteignait le seuil, et une côte de 300 m comptait pour
 *   zéro.
 * - **Il gonflait le dénivelé à l'arrêt.** Le bruit vertical du GPS atteint plusieurs
 *   mètres d'un relevé à l'autre. Chaque oscillation dépassant le seuil était comptée,
 *   dans un sens puis dans l'autre : une marche à plat accumulait des centaines de
 *   mètres fictifs.
 *
 * Le cumul se fait donc par rapport à une **altitude de référence** qui ne se déplace
 * que lorsque l'écart dépasse [thresholdMeters]. Une montée lente finit par franchir
 * le seuil face à la référence, donc elle compte ; une oscillation autour de la
 * référence n'y parvient jamais, donc elle est ignorée.
 *
 * Sans dépendance Android : directement testable.
 */
class ElevationAccumulator(
    private val thresholdMeters: Double = THRESHOLD_METERS
) {

    private var reference: Double? = null

    var gainMeters: Double = 0.0
        private set

    var lossMeters: Double = 0.0
        private set

    /**
     * Rompt la continuité sans effacer les totaux.
     *
     * À appeler à l'ouverture d'un nouveau tronçon — sortie de pause, reprise d'une
     * trace — pour ne pas compter comme un dénivelé l'écart d'altitude entre l'endroit
     * où l'on s'était arrêté et celui où l'on repart.
     */
    fun breakSegment() {
        reference = null
    }

    /** Efface tout, totaux compris. */
    fun reset() {
        reference = null
        gainMeters = 0.0
        lossMeters = 0.0
    }

    /** Reprend des totaux déjà calculés, pour continuer un parcours existant. */
    fun restore(gainMeters: Double, lossMeters: Double) {
        this.gainMeters = gainMeters
        this.lossMeters = lossMeters
        reference = null
    }

    /** Intègre une altitude, en mètres au-dessus du niveau de la mer. */
    fun add(meters: Double) {
        val current = reference
        if (current == null) {
            reference = meters
            return
        }

        val delta = meters - current
        if (delta >= thresholdMeters) {
            gainMeters += delta
            reference = meters
        } else if (-delta >= thresholdMeters) {
            lossMeters += -delta
            reference = meters
        }
    }

    companion object {
        /**
         * Écart minimal retenu comme un vrai changement d'altitude.
         *
         * Choisi au-dessus du bruit résiduel qui subsiste après lissage — de l'ordre
         * du mètre — et bien en dessous de ce qui compte dans un parcours réel.
         */
        const val THRESHOLD_METERS = 3.0
    }
}
