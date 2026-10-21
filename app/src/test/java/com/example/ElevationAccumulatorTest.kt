package com.example

import com.example.util.ElevationAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The old accumulation compared each altitude with the previous one using a 0.80 m threshold.
 * It was wrong both ways: it missed steady climbs, whose
 * second-to-second gaps stay under the threshold, and it inflated elevation
 * when stopped, where GPS vertical noise crosses the threshold in one direction then
 * the other.
 *
 * These tests pin down the two expected behaviors of accumulation against a
 * reference altitude.
 */
class ElevationAccumulatorTest {

    private val accumulator = ElevationAccumulator()

    @Test
    fun `a steady climb is counted despite small steps`() {
        // 300 m of climb in steps of 0.15 m, which is what a 10% slope at
        // 1.4 m/s sampled at 1 Hz gives. No step reaches the 3 m threshold.
        var altitude = 100.0
        accumulator.add(altitude)
        repeat(2_000) {
            altitude += 0.15
            accumulator.add(altitude)
        }

        // Tolerance of one threshold: the reference only follows in steps of 3 m.
        assertTrue(
            "D+ obtenu : ${accumulator.gainMeters}",
            accumulator.gainMeters >= 297.0 && accumulator.gainMeters <= 300.0
        )
        assertEquals(0.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `noise at rest creates no elevation`() {
        // Oscillation of ±2 m around 200 m: this is ordinary GPS noise, and it
        // must accumulate nothing since it never strays far enough from the reference.
        val noise = listOf(200.0, 202.0, 198.0, 201.5, 199.0, 200.5, 198.5, 201.0)
        repeat(50) { noise.forEach { accumulator.add(it) } }

        assertEquals("D+ must stay zero", 0.0, accumulator.gainMeters, 0.001)
        assertEquals("D- must stay zero", 0.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `a descent is counted separately`() {
        accumulator.add(500.0)
        accumulator.add(450.0)
        accumulator.add(470.0)

        assertEquals(20.0, accumulator.gainMeters, 0.001)
        assertEquals(50.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `a segment break does not count the altitude jump`() {
        accumulator.add(100.0)
        accumulator.add(120.0)
        assertEquals(20.0, accumulator.gainMeters, 0.001)

        // Pause then resume 800 m higher: we did not climb those 800 m.
        accumulator.breakSegment()
        accumulator.add(920.0)
        accumulator.add(930.0)

        assertEquals("only the 10 m of the new segment count", 30.0, accumulator.gainMeters, 0.001)
        assertEquals(0.0, accumulator.lossMeters, 0.001)
    }

    @Test
    fun `restore takes over the totals without an altitude reference`() {
        accumulator.restore(gainMeters = 250.0, lossMeters = 80.0)
        assertEquals(250.0, accumulator.gainMeters, 0.001)
        assertEquals(80.0, accumulator.lossMeters, 0.001)

        // The first point after a resume sets the reference, it accumulates nothing.
        accumulator.add(1_500.0)
        assertEquals(250.0, accumulator.gainMeters, 0.001)

        accumulator.add(1_510.0)
        assertEquals(260.0, accumulator.gainMeters, 0.001)
    }

    @Test
    fun `reset erases everything`() {
        accumulator.add(100.0)
        accumulator.add(200.0)
        assertTrue(accumulator.gainMeters > 0.0)

        accumulator.reset()
        assertEquals(0.0, accumulator.gainMeters, 0.001)
        assertEquals(0.0, accumulator.lossMeters, 0.001)
    }
}
