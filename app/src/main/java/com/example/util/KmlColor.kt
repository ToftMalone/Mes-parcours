package com.example.util

/**
 * Reading of line colors written in a KML file.
 *
 * KML writes its colors as **AABBGGRR** — blue channel before red, exactly
 * the opposite of the ARGB Android expects. Copying the value as is
 * would therefore swap red and blue: a red line drawn in Google Earth
 * would display in blue, and nobody would understand why.
 *
 * No Android dependency: directly testable.
 */
object KmlColor {

    /**
     * Converts a KML color to an ARGB color, or returns null if the string is
     * not usable.
     *
     * A fully transparent color is rejected rather than honored: it
     * would make the line invisible on the map, which the user would read as
     * a lost track and not as a color choice. We then fall back on the
     * palette color.
     */
    fun parse(kmlColor: String?): Int? {
        val raw = kmlColor?.trim()?.removePrefix("#") ?: return null
        if (raw.length != 8) return null
        if (!raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null

        val value = raw.toLongOrNull(16) ?: return null

        val alpha = ((value shr 24) and 0xFF).toInt()
        val blue = ((value shr 16) and 0xFF).toInt()
        val green = ((value shr 8) and 0xFF).toInt()
        val red = (value and 0xFF).toInt()

        if (alpha == 0) return null

        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

/**
 * Style directory of a KML document, and resolution of a `<styleUrl>` to a
 * line color.
 *
 * Google Earth does not write the color in the line: the `<Placemark>` refers to a
 * style by its identifier, and that style may itself be a `<StyleMap>` that
 * distinguishes the resting appearance from the hover one. Without this resolution, one can
 * only guess — and taking the first style at hand amounts to painting all the
 * trips of a single file in the color of just one.
 *
 * No Android dependency: directly testable.
 */
class KmlStyleTable {

    private val lineColors = HashMap<String, Int>()
    private val normalStyleOf = HashMap<String, String>()

    /** Records the line color of a `<Style id="…">`. */
    fun putStyle(id: String?, color: Int?) {
        val key = normalize(id) ?: return
        if (color != null) lineColors[key] = color
    }

    /** Records the “resting” style designated by a `<StyleMap id="…">`. */
    fun putStyleMap(id: String?, normalStyleUrl: String?) {
        val key = normalize(id) ?: return
        val target = normalize(normalStyleUrl) ?: return
        normalStyleOf[key] = target
    }

    /**
     * Line color associated with [styleUrl], or null if it remains unfound.
     *
     * Follows the `<StyleMap>`s down to the resting style. The depth is bounded: a
     * file in which two StyleMaps refer to each other would otherwise make
     * the import loop, and nothing in the format forbids writing one.
     */
    fun resolve(styleUrl: String?): Int? {
        var key = normalize(styleUrl) ?: return null
        repeat(MAX_INDIRECTION) {
            lineColors[key]?.let { return it }
            key = normalStyleOf[key] ?: return null
        }
        return null
    }

    /** Identifier without its leading hash or its blanks. */
    private fun normalize(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("#")?.trim()
        return if (cleaned.isNullOrEmpty()) null else cleaned
    }

    private companion object {
        const val MAX_INDIRECTION = 8
    }
}
