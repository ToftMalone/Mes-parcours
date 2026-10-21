package com.example

import com.example.util.KmlStyleTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Google Earth does not write the color in the line: the `<Placemark>` refers to a
 * style by its identifier, and that style may be a `<StyleMap>` that distinguishes
 * the resting appearance from the hover one.
 *
 * These tests pin down the resolution, including the case that motivated this whole detour: a
 * file gathering several trips, each referring to its own style.
 */
class KmlStyleTableTest {

    private val rouge = 0xFFFF0000.toInt()
    private val bleu = 0xFF0000FF.toInt()
    private val vert = 0xFF00FF00.toInt()

    @Test
    fun `a direct styleUrl gives the style's color`() {
        val table = KmlStyleTable()
        table.putStyle("trace1", rouge)

        assertEquals(rouge, table.resolve("#trace1"))
    }

    @Test
    fun `the hash sign is optional`() {
        val table = KmlStyleTable()
        table.putStyle("#trace1", rouge)

        assertEquals(rouge, table.resolve("trace1"))
    }

    @Test
    fun `a StyleMap leads to the resting style, not the hover one`() {
        // That is the whole point of the resolution: taking the first style at hand
        // would bring back the hover color, which the user never sees.
        val table = KmlStyleTable()
        table.putStyle("repos", rouge)
        table.putStyle("survol", bleu)
        table.putStyleMap("carte", "#repos")

        assertEquals(rouge, table.resolve("#carte"))
    }

    @Test
    fun `each trip keeps its color`() {
        // The real case: a Google Earth export gathering several trips.
        val table = KmlStyleTable()
        table.putStyle("hendaye", rouge)
        table.putStyle("savoie", bleu)
        table.putStyle("dubai", vert)

        assertEquals(rouge, table.resolve("#hendaye"))
        assertEquals(bleu, table.resolve("#savoie"))
        assertEquals(vert, table.resolve("#dubai"))
    }

    @Test
    fun `an unknown style gives no color`() {
        val table = KmlStyleTable()
        table.putStyle("trace1", rouge)

        assertNull(table.resolve("#absent"))
        assertNull(table.resolve(null))
        assertNull(table.resolve(""))
    }

    @Test
    fun `a style without a line color gives no color`() {
        // A <Style> that carries only an icon or a fill: nothing to take from it
        // for the line, and above all not an invented color.
        val table = KmlStyleTable()
        table.putStyle("iconeSeule", null)

        assertNull(table.resolve("#iconeSeule"))
    }

    @Test
    fun `two StyleMaps referring to each other do not block the import`() {
        // Nothing in the format forbids it, and a naive resolution would loop
        // forever — on a file of several hundred MB, the import would
        // never return.
        val table = KmlStyleTable()
        table.putStyleMap("a", "#b")
        table.putStyleMap("b", "#a")

        assertNull(table.resolve("#a"))
    }
}
