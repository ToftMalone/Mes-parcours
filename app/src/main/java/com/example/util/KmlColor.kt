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

    /** L'inverse de [parse] : une couleur ARGB écrite en AABBGGRR, pour l'export. */
    fun format(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return "%02x%02x%02x%02x".format(alpha, blue, green, red)
    }
}

/**
 * Répertoire des styles d'un document KML, et résolution d'un `<styleUrl>` vers une
 * couleur de ligne.
 *
 * Google Earth n'écrit pas la couleur dans le tracé : le `<Placemark>` renvoie à un
 * style par son identifiant, et ce style peut lui-même être un `<StyleMap>` qui
 * distingue l'apparence au repos de celle au survol. Sans cette résolution, on ne
 * peut que deviner — et prendre le premier style venu revient à peindre tous les
 * trajets d'un même fichier de la couleur d'un seul.
 *
 * Aucune dépendance Android : directement testable.
 */
class KmlStyleTable {

    private val lineColors = HashMap<String, Int>()
    private val lineWidths = HashMap<String, Float>()
    private val normalStyleOf = HashMap<String, String>()

    /** Enregistre la couleur et l'épaisseur de ligne d'un `<Style id="…">`. */
    fun putStyle(id: String?, color: Int?, width: Float? = null) {
        val key = normalize(id) ?: return
        if (color != null && hasRoom(lineColors, key)) lineColors[key] = color
        if (width != null && hasRoom(lineWidths, key)) lineWidths[key] = width
    }

    /** Enregistre le style « au repos » désigné par un `<StyleMap id="…">`. */
    fun putStyleMap(id: String?, normalStyleUrl: String?) {
        val key = normalize(id) ?: return
        val target = normalize(normalStyleUrl) ?: return
        if (hasRoom(normalStyleOf, key)) normalStyleOf[key] = target
    }

    /**
     * Couleur de ligne associée à [styleUrl], ou null si elle reste introuvable.
     *
     * Suit les `<StyleMap>` jusqu'au style au repos. La profondeur est bornée : un
     * fichier dont deux StyleMap se renvoient l'un à l'autre ferait sinon tourner
     * l'import en boucle, et rien dans le format n'interdit d'en écrire un.
     */
    fun resolve(styleUrl: String?): Int? {
        var key = normalize(styleUrl) ?: return null
        repeat(MAX_INDIRECTION) {
            lineColors[key]?.let { return it }
            key = normalStyleOf[key] ?: return null
        }
        return null
    }

    /** Épaisseur de ligne associée à [styleUrl], résolue comme la couleur. */
    fun resolveWidth(styleUrl: String?): Float? {
        var key = normalize(styleUrl) ?: return null
        repeat(MAX_INDIRECTION) {
            lineWidths[key]?.let { return it }
            key = normalStyleOf[key] ?: return null
        }
        return null
    }

    /**
     * Identifiant sans son croisillon de référence ni ses blancs ; null s'il est vide ou
     * démesuré. L'attribut `id` n'a pas la borne des textes lus entre balises : un
     * identifiant de plusieurs mégaoctets serait sinon gardé tel quel en mémoire.
     */
    private fun normalize(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("#")?.trim()
        return if (cleaned.isNullOrEmpty() || cleaned.length > MAX_ID_LENGTH) null else cleaned
    }

    /**
     * Place pour [key] dans [map] : une entrée existante se remplace toujours, une
     * nouvelle n'entre que sous [MAX_STYLES].
     *
     * Sans plafond, un fichier piégé de quelques centaines de mégaoctets fait de
     * millions de `<Style id="…">` remplissait cette table jusqu'à saturer la mémoire,
     * dès la première lecture — celle qui ne garde, en principe, « que quelques
     * dizaines d'entrées ». Un vrai export Google Earth en compte au plus quelques
     * centaines ; les styles au-delà de la borne retombent sur la couleur de la palette.
     */
    private fun hasRoom(map: Map<String, *>, key: String): Boolean =
        map.size < MAX_STYLES || map.containsKey(key)

    private companion object {
        const val MAX_INDIRECTION = 8
        const val MAX_STYLES = 10_000
        const val MAX_ID_LENGTH = 256
    }
}
