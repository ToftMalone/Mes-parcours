package com.example.util

/**
 * Detects entering a covered, dark space — tunnel, car park, garage — and
 * the exit that follows, from the light sensor.
 *
 * The criterion is deliberately not an absolute threshold. An absolute threshold confuses “it
 * is dark here” with “I am indoors in broad daylight”: a lit living
 * room sits around 100 to 300 lux, well below outdoor light,
 * and the app switched to dark theme in broad daylight.
 *
 * What signs a tunnel is a **sudden and deep** drop relative to the
 * brightness of the preceding moments, then a clear return of light. Hence three
 * cumulative conditions on entry: darkness in absolute value, collapse relative
 * to the ambient reference, and persistence for a short delay.
 *
 * No Android dependency: all the logic is directly testable.
 */
class TunnelDetector(
    /** It must really be dark, in absolute value (lux). */
    private val enterLux: Float = ENTER_LUX,
    /** ...and be a tiny fraction of the previous ambient brightness. */
    private val enterRatio: Float = ENTER_RATIO,
    /** Clear return of light, in absolute value (lux). */
    private val exitLux: Float = EXIT_LUX,
    /** Time during which darkness must be confirmed before switching. */
    private val enterDelayMs: Long = ENTER_DELAY_MS,
    /** Same for the return to light. */
    private val exitDelayMs: Long = EXIT_DELAY_MS,
    /** Inertia of the ambient reference, per reading. */
    private val baselineSmoothing: Float = BASELINE_SMOOTHING
) {

    /** Reference ambient brightness. Frozen as long as we are indoors. */
    private var baselineLux = Float.NaN

    /** Instant of the first reading that contradicts the current state, or [NO_TIME]. */
    private var pendingSince = NO_TIME

    var isInside: Boolean = false
        private set

    /** Starts from zero: to be called when listening to the sensor stops then resumes. */
    fun reset() {
        baselineLux = Float.NaN
        pendingSince = NO_TIME
        isInside = false
    }

    /**
     * Integrates a sensor reading and returns the current state.
     *
     * [elapsedMs] must come from a monotonic clock — the system time can jump.
     */
    fun onReading(lux: Float, elapsedMs: Long): Boolean {
        if (baselineLux.isNaN()) baselineLux = lux

        if (isInside) {
            updatePending(condition = lux >= exitLux, elapsedMs = elapsedMs, delayMs = exitDelayMs) {
                isInside = false
                // We start again from the light found, otherwise the reference would stay
                // the one from before the entry and the next tunnel would be misjudged.
                baselineLux = lux
            }
            return isInside
        }

        // The reference only follows outdoor light. Integrating the dark
        // readings would pull it down, and the relative collapse that characterizes
        // a tunnel entry would end up no longer detectable.
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
     * Switches via [onConfirmed] if [condition] has held for at least [delayMs].
     * Any contradiction resets the counter: a momentary darkness — a
     * bridge, a truck's shadow — must not make the whole display flicker.
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
         * A lit interior sits well above: that is what avoids
         * switching to dark in a room in broad daylight.
         */
        const val ENTER_LUX = 20f

        /** Darkness must represent less than 8% of the ambient brightness. */
        const val ENTER_RATIO = 0.08f

        /** Beyond this, the light is really back. */
        const val EXIT_LUX = 80f

        const val ENTER_DELAY_MS = 1_000L
        const val EXIT_DELAY_MS = 1_500L

        /** ≈ 4 s time constant at the sensor's normal rate. */
        const val BASELINE_SMOOTHING = 0.05f
    }
}
