package com.example

import com.example.util.AltitudeSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Raw GPS altitude jitters by several meters from one reading to the next, even
 * with the device motionless. Smoothing makes the displayed number stable without
 * shifting it, and absorbs isolated outliers.
 */
class AltitudeSmootherTest {

    private val smoother = AltitudeSmoother()

    @Test
    fun `without a measurement there is no value`() {
        assertNull(smoother.current)
    }

    @Test
    fun `the first measurement is taken as is`() {
        assertEquals(120.0, smoother.add(120.0), 0.001)
        assertEquals(120.0, smoother.current!!, 0.001)
    }

    @Test
    fun `a stable altitude is returned without offset`() {
        repeat(30) { smoother.add(200.0) }
        assertEquals("a constant value must not be moved", 200.0, smoother.current!!, 0.01)
    }

    @Test
    fun `an isolated outlier barely moves the result`() {
        repeat(10) { smoother.add(200.0) }
        val before = smoother.current!!

        // A single reading 60 m too high: the moving median discards it.
        smoother.add(260.0)
        val after = smoother.current!!

        assertTrue(
            "gap obtained: ${abs(after - before)} m",
            abs(after - before) < 2.0
        )
    }

    @Test
    fun `jitter is strongly reduced`() {
        // ±6 m of noise around 300 m, like a motionless GPS in average conditions.
        val noisy = listOf(306.0, 294.0, 303.0, 297.0, 305.0, 295.0, 301.0, 299.0)
        repeat(20) { noisy.forEach { smoother.add(it) } }

        val smoothed = mutableListOf<Double>()
        repeat(5) { noisy.forEach { smoothed.add(smoother.add(it)) } }

        val rawSpread = noisy.max() - noisy.min()
        val smoothedSpread = smoothed.max() - smoothed.min()

        assertTrue(
            "raw amplitude $rawSpread m, smoothed $smoothedSpread m",
            smoothedSpread < rawSpread / 3.0
        )
        assertEquals("the average must not be shifted", 300.0, smoother.current!!, 3.0)
    }

    @Test
    fun `a real change is followed`() {
        repeat(20) { smoother.add(100.0) }
        // Climb from 100 m to 300 m, sustained over time: it must be followed.
        repeat(40) { smoother.add(300.0) }
        assertEquals(300.0, smoother.current!!, 1.0)
    }

    @Test
    fun `reset forgets the previous series`() {
        repeat(10) { smoother.add(100.0) }
        smoother.reset()
        assertNull(smoother.current)

        // The first measurement after reset starts from zero, without dragging the old series.
        assertEquals(800.0, smoother.add(800.0), 0.001)
    }
}
