package com.example.util

/**
 * Accumulates the positive and negative elevation of a track.
 *
 * The previous implementation compared each altitude with the previous one and only
 * kept the gap if it exceeded 0.80 m. That criterion was wrong in both
 * directions:
 *
 * - **It missed steady climbs.** At 1 Hz, walking at 1.4 m/s up a 10% slope,
 *   the altitude only rises by 0.14 m per second. No gap between
 *   two consecutive points reached the threshold, and a 300 m climb counted for
 *   zero.
 * - **It inflated elevation when stopped.** GPS vertical noise reaches several
 *   meters from one reading to the next. Each oscillation exceeding the threshold was counted,
 *   in one direction then the other: a walk on the flat accumulated hundreds of
 *   fictitious meters.
 *
 * Accumulation is therefore done against a **reference altitude** that only moves
 * when the gap exceeds [thresholdMeters]. A slow climb eventually crosses
 * the threshold against the reference, so it counts; an oscillation around the
 * reference never gets there, so it is ignored.
 *
 * No Android dependency: directly testable.
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
     * Breaks continuity without erasing the totals.
     *
     * To be called when a new segment opens — exit from pause, resume of a
     * track — so as not to count as elevation the altitude gap between the place
     * where we stopped and the one where we start again.
     */
    fun breakSegment() {
        reference = null
    }

    /** Erases everything, totals included. */
    fun reset() {
        reference = null
        gainMeters = 0.0
        lossMeters = 0.0
    }

    /** Takes over already computed totals, to continue an existing track. */
    fun restore(gainMeters: Double, lossMeters: Double) {
        this.gainMeters = gainMeters
        this.lossMeters = lossMeters
        reference = null
    }

    /** Integrates an altitude, in meters above sea level. */
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
         * Minimum gap retained as a real change of altitude.
         *
         * Chosen above the residual noise that remains after smoothing — of the order
         * of a meter — and well below what counts in a real track.
         */
        const val THRESHOLD_METERS = 3.0
    }
}
