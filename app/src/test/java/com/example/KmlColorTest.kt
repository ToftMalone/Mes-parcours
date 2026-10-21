package com.example

import com.example.util.KmlColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The trap of this format fits in one sentence: KML writes AABBGGRR, Android expects
 * ARGB. The first two tests are there so that a naive copy — which would swap
 * red and blue — cannot go unnoticed.
 */
class KmlColorTest {

    @Test
    fun `KML red becomes ARGB red`() {
        // ff0000ff: opaque, blue 00, green 00, red ff.
        assertEquals(0xFFFF0000.toInt(), KmlColor.parse("ff0000ff"))
    }

    @Test
    fun `KML blue becomes ARGB blue`() {
        // ffff0000: opaque, blue ff, green 00, red 00.
        assertEquals(0xFF0000FF.toInt(), KmlColor.parse("ffff0000"))
    }

    @Test
    fun `green stays in the middle, hence unchanged`() {
        assertEquals(0xFF00FF00.toInt(), KmlColor.parse("ff00ff00"))
    }

    @Test
    fun `partial transparency is preserved`() {
        assertEquals(0x800000FF.toInt(), KmlColor.parse("80ff0000"))
    }

    @Test
    fun `a fully transparent color is rejected`() {
        // Honoring it would give an invisible line, which the user would read as
        // a lost track rather than as a color choice.
        assertNull(KmlColor.parse("00ff0000"))
    }

    @Test
    fun `the hash sign and blanks are tolerated`() {
        assertEquals(0xFFFF0000.toInt(), KmlColor.parse("  #ff0000ff \n"))
    }

    @Test
    fun `hexadecimal case does not matter`() {
        assertEquals(KmlColor.parse("ff0000ff"), KmlColor.parse("FF0000FF"))
    }

    @Test
    fun `an unusable string gives no color`() {
        assertNull(KmlColor.parse(null))
        assertNull(KmlColor.parse(""))
        assertNull(KmlColor.parse("ff00ff"))       // too short
        assertNull(KmlColor.parse("ff0000ffff"))   // too long
        assertNull(KmlColor.parse("zz0000ff"))     // not hexadecimal
    }
}
