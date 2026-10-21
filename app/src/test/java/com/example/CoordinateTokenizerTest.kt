package com.example

import com.example.util.CoordinateTokenizer
import com.example.util.MAX_COORDINATE_TOKEN_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The splitter of a KML's `<coordinates>` blocks receives the file's text in
 * chunks, as the SAX parser delivers it. An imported file comes from
 * outside: an oversized block must not be able to exhaust memory.
 *
 * Pure function, no Android dependency: no need for Robolectric.
 */
class CoordinateTokenizerTest {

    private data class Point(val lon: Double, val lat: Double, val ele: Double)

    /** Splits [chunks], delivered one after the other as SAX would. */
    private fun tokenize(vararg chunks: String): List<Point> {
        val points = mutableListOf<Point>()
        val tokenizer = CoordinateTokenizer { lon, lat, ele -> points += Point(lon, lat, ele) }
        for (chunk in chunks) {
            val chars = chunk.toCharArray()
            tokenizer.feed(chars, 0, chars.size)
        }
        tokenizer.finish()
        return points
    }

    @Test
    fun `ordinary points are read`() {
        assertEquals(
            listOf(Point(2.35, 48.85, 35.0), Point(2.36, 48.86, 0.0)),
            tokenize("2.35,48.85,35 2.36,48.86")
        )
    }

    @Test
    fun `a point cut between two chunks is glued back`() {
        // SAX may cut the text anywhere, even in the middle of a number.
        assertEquals(listOf(Point(2.35, 48.85, 35.0)), tokenize("2.3", "5,48.", "85,35"))
    }

    @Test
    fun `spaces around commas are tolerated`() {
        assertEquals(listOf(Point(2.35, 48.85, 35.0)), tokenize("2.35 , 48.85 ,35"))
    }

    @Test
    fun `an oversized token is discarded without blocking the rest`() {
        // The case that exhausted memory: a token with no blank at all, here
        // delivered in many chunks as SAX would on a big file.
        val chunk = "1".repeat(10_000)
        val chunks = Array(100) { chunk }
        val points = tokenize("2.35,48.85 ", *chunks, " 2.36,48.86")
        // The oversized token is not emitted truncated: it disappears, and the valid
        // points around it are kept.
        assertEquals(listOf(Point(2.35, 48.85, 0.0), Point(2.36, 48.86, 0.0)), points)
    }

    @Test
    fun `a point at the maximum length is still read`() {
        // The bound must never rule out a real point: we fill it up to the
        // last allowed character with longitude decimals.
        val lat = ",48.85"
        val lon = "2." + "3".repeat(MAX_COORDINATE_TOKEN_LENGTH - lat.length - 2)
        val token = lon + lat
        assertEquals(MAX_COORDINATE_TOKEN_LENGTH, token.length)
        val points = tokenize(token)
        assertEquals(1, points.size)
        assertEquals(48.85, points[0].lat, 0.0)
    }

    @Test
    fun `a point one character too long is discarded rather than truncated`() {
        // Truncated, it would have been read as another point, in another place.
        val lat = ",48.85"
        val lon = "2." + "3".repeat(MAX_COORDINATE_TOKEN_LENGTH - lat.length - 1)
        assertEquals(MAX_COORDINATE_TOKEN_LENGTH + 1, (lon + lat).length)
        assertEquals(listOf(Point(2.36, 48.86, 0.0)), tokenize(lon + lat + " 2.36,48.86"))
    }
}
