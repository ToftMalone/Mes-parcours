package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.ui.component.DARK_TILES_COLOR_FILTER
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

/**
 * The map's dark theme darkens the Mapnik tiles with a color
 * matrix. Three requirements followed one another, and the tests pin down the last:
 *
 * 1. `TilesOverlay.INVERT_COLORS`, a bare inversion, tinted forests magenta
 *    and water brown — inverting flips hue as much as lightness.
 * 2. Desaturating then inverting removed the dominant tint, but also every color.
 * 3. Inverting then rotating the hues by a half turn gives back Mapnik's colors,
 *    darkened: that is the expected behavior here.
 *
 * The assertions therefore cover both what must be obtained (recognizable
 * and dark hues) and the two faults not to be found again (magenta,
 * and the absence of color).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DarkTilesColorFilterTest {

    /** Colors actually present on a standard OpenStreetMap tile. */
    private val grassGreen = Color.rgb(200, 230, 160)
    private val waterBlue = Color.rgb(170, 211, 223)
    private val paperBeige = Color.rgb(242, 239, 233)
    private val midGrey = Color.rgb(128, 128, 128)

    /** Runs a solid color through the filter and returns the result. */
    private fun applyFilter(color: Int): Int {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, 1f, 1f,
            Paint().apply {
                this.color = color
                colorFilter = DARK_TILES_COLOR_FILTER
            }
        )
        return bitmap.getPixel(0, 0)
    }

    /** Gap between the strongest and weakest component: 0 = pure gray. */
    private fun chroma(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun luminance(color: Int): Int =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()

    private fun describe(color: Int): String =
        "r=${Color.red(color)} g=${Color.green(color)} b=${Color.blue(color)}"

    @Test
    fun `grass comes out dark green`() {
        val filtered = applyFilter(grassGreen)
        val r = Color.red(filtered)
        val g = Color.green(filtered)
        val b = Color.blue(filtered)

        assertTrue("green must remain the dominant component: ${describe(filtered)}", g > r && g > b)
        assertTrue("and the result must be dark: ${luminance(filtered)}", luminance(filtered) < 90)
    }

    @Test
    fun `water comes out dark blue and not brown`() {
        val filtered = applyFilter(waterBlue)
        val r = Color.red(filtered)
        val b = Color.blue(filtered)

        // The bare inversion made water brown, i.e. red dominant over blue.
        assertTrue("blue must dominate red: ${describe(filtered)}", b > r)
        assertTrue("and the result must be dark: ${luminance(filtered)}", luminance(filtered) < 90)
    }

    @Test
    fun `green no longer turns magenta`() {
        // The original defect: a pure inversion gives magenta, i.e. red and
        // blue clearly above green.
        val filtered = applyFilter(grassGreen)
        val r = Color.red(filtered)
        val g = Color.green(filtered)
        val b = Color.blue(filtered)

        assertTrue("magenta dominant tint detected: ${describe(filtered)}", !(r > g && b > g))
    }

    @Test
    fun `colors are not erased`() {
        // The desaturated variant gave a perfect gray. Hues must now
        // remain clearly identifiable.
        for ((name, color) in listOf("gazon" to grassGreen, "eau" to waterBlue)) {
            val filtered = applyFilter(color)
            assertTrue(
                "$name must keep some color, chroma obtained = ${chroma(filtered)}",
                chroma(filtered) >= 15
            )
        }
    }

    @Test
    fun `a light background becomes dark`() {
        val filtered = applyFilter(paperBeige)
        assertTrue(
            "the paper beige must come out dark, luminance = ${luminance(filtered)}",
            luminance(filtered) < 40
        )

        // The order of lightness is reversed: what was lightest on the tile
        // is now the darkest.
        assertTrue(
            "the background, lighter than the grass, must come out darker than it",
            luminance(filtered) < luminance(applyFilter(grassGreen))
        )
    }

    @Test
    fun `grays stay neutral`() {
        // Each row of the hue matrix sums to 1: a gray must not take on
        // a tint, otherwise roads and labels would be colored.
        val filtered = applyFilter(midGrey)
        assertTrue(
            "a mid gray must stay neutral: ${describe(filtered)}",
            chroma(filtered) <= 3
        )
    }
}
