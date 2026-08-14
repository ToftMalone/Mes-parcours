package com.example.util

/**
 * Lecture des couleurs de tracé écrites dans un fichier KML.
 *
 * KML note ses couleurs en **AABBGGRR** — le canal bleu avant le rouge, exactement
 * l'inverse de l'ARGB attendu par Android. Recopier la valeur telle quelle
 * échangerait donc le rouge et le bleu : un tracé rouge dessiné dans Google Earth
 * s'afficherait en bleu, et personne ne comprendrait pourquoi.
 *
 * Aucune dépendance Android : directement testable.
 */
object KmlColor {

    /**
     * Convertit une couleur KML en couleur ARGB, ou renvoie null si la chaîne n'est
     * pas exploitable.
     *
     * Une couleur entièrement transparente est refusée plutôt que respectée : elle
     * rendrait le tracé invisible sur la carte, ce que l'utilisateur lirait comme
     * une trace perdue et non comme un choix de couleur. On retombe alors sur la
     * couleur de la palette.
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
