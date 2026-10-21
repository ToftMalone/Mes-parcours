package com.example

import com.example.util.TunnelDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tunnel detector replaces a simple absolute threshold, which confused “it is
 * dark” with “I am indoors in broad daylight” and made the
 * app switch to dark theme in a lit room.
 *
 * These tests pin down the distinction: only a sudden and deep drop in
 * brightness counts, and it must persist.
 */
class TunnelDetectorTest {

    private val detector = TunnelDetector()

    /** Sensor rate at the SENSOR_DELAY_NORMAL setting. */
    private val sampleIntervalMs = 200L

    private var clock = 0L

    /** Feeds the detector for [durationMs] at constant brightness. */
    private fun feed(lux: Float, durationMs: Long): Boolean {
        var last = detector.isInside
        var elapsed = 0L
        while (elapsed < durationMs) {
            last = detector.onReading(lux, clock)
            clock += sampleIntervalMs
            elapsed += sampleIntervalMs
        }
        return last
    }

    @Test
    fun `staying indoors in broad daylight triggers nothing`() {
        // The case that was a problem: a lit interior by day, around
        // 150 lux. It is dark compared with outdoors, but it is not a tunnel.
        feed(12_000f, durationMs = 10_000) // outdoors, full daylight
        assertFalse("entering the house must trigger nothing", feed(150f, 30_000))
        assertFalse("and staying indoors neither", feed(150f, 120_000))
    }

    @Test
    fun `a tunnel switches to dark then back to light`() {
        feed(20_000f, durationMs = 10_000) // road in full sun
        assertTrue("the tunnel entrance must switch to dark", feed(4f, 5_000))
        assertTrue("and stay so throughout the tunnel", feed(6f, 60_000))
        assertFalse("the exit must return to light", feed(20_000f, 5_000))
    }

    @Test
    fun `a momentary darkness does not make the display flicker`() {
        feed(20_000f, durationMs = 10_000)
        // Passing under a bridge: dark, but much shorter than the confirmation
        // delay. Without that delay, the display would flicker at every structure.
        assertFalse("passing under a bridge must not switch", feed(3f, 400))
        assertFalse(feed(20_000f, 2_000))
    }

    @Test
    fun `a covered car park stays dark as long as one is parked there`() {
        feed(15_000f, durationMs = 10_000)
        assertTrue(feed(8f, 5_000))
        // The ambient reference is frozen indoors. Without this freeze, it
        // would drift to the car park's level, the relative collapse would disappear and
        // the display would go back to light while we are still in the dark.
        assertTrue("ten minutes of parking must not turn it back on", feed(8f, 600_000))
        assertFalse("the exit must return to light", feed(15_000f, 5_000))
    }

    @Test
    fun `nightfall is not mistaken for a tunnel`() {
        // Dusk is a slow decay: the ambient reference follows it,
        // so there is never a relative collapse. It is the solar computation that
        // must handle this case, not the sensor.
        feed(8_000f, durationMs = 20_000)
        for (lux in listOf(4_000f, 2_000f, 900f, 400f, 150f, 60f, 25f, 10f, 4f)) {
            feed(lux, durationMs = 60_000)
        }
        assertFalse("nightfall must not be seen as a tunnel", detector.isInside)
    }

    @Test
    fun `reset forgets the ambient reference`() {
        feed(20_000f, durationMs = 10_000)
        assertTrue(feed(4f, 5_000))

        detector.reset()
        assertFalse("after reset, no state retained", detector.isInside)

        // The first measurement after a reset sets the new reference: a stable
        // low brightness no longer constitutes a drop.
        assertFalse(feed(4f, 30_000))
    }
}
