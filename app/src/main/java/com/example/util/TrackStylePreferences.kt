package com.example.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Apparence des tracés sur la carte : épaisseur du trait, et palette proposée pour
 * colorer un parcours.
 *
 * **La couleur appartient au parcours, plus à la catégorie.** Elle est enregistrée
 * dans `Track.displayColor` et se choisit depuis la carte du parcours, dans
 * l'historique. Les trois couleurs par catégorie qui vivaient ici en préférences ont
 * été retirées : elles ne servent plus que de valeur par défaut, sous forme de
 * constantes, pour un parcours dont personne n'a choisi la couleur et dont le fichier
 * n'en portait pas.
 */
object TrackStylePreferences {

    private const val KEY_THICKNESS_DP = "pref_track_thickness_dp"

    /**
     * Marque la reprise des couleurs déjà faite, pour ne la faire qu'une fois.
     *
     * Voir `TrackRepository.backfillDisplayColors` : au passage aux couleurs par
     * parcours, chaque parcours existant reçoit explicitement celle qu'il affichait
     * jusque-là, pour que la mise à jour ne change rien à l'écran.
     */
    private const val KEY_COLORS_MIGRATED = "pref_track_colors_migrated_to_per_track"

    // Anciennes clés, lues une seule fois par la reprise ci-dessus puis plus jamais.
    // Ne pas les réutiliser pour autre chose : une installation ancienne les porte
    // encore, avec les couleurs que l'utilisateur avait choisies par catégorie.
    private const val LEGACY_KEY_COLOR_RECORDED = "pref_track_color_recorded"
    private const val LEGACY_KEY_COLOR_IMPORTED = "pref_track_color_imported"
    private const val LEGACY_KEY_COLOR_MERGED = "pref_track_color_merged"
    private const val LEGACY_KEY_IMPORTED_FROM_FILE = "pref_track_color_imported_from_file"

    /** Violet, couleur historique des parcours enregistrés. */
    const val DEFAULT_COLOR_RECORDED = 0xFF8B5CF6.toInt()

    /** Vert fluo, couleur historique des parcours importés. */
    const val DEFAULT_COLOR_IMPORTED = 0xFF39FF14.toInt()

    /** Orange, pour distinguer d'emblée les parcours fusionnés des deux autres. */
    const val DEFAULT_COLOR_MERGED = 0xFFFF9800.toInt()

    /** Épaisseur par défaut, en dp — comparable à l'ancien niveau « Normal ». */
    const val DEFAULT_THICKNESS_DP = 4f

    /** Bornes du réglage : en dessous, le trait devient difficile à voir sur la
     *  carte ; au-delà, il masque le fond de carte plus qu'il ne montre le tracé. */
    const val MIN_THICKNESS_DP = 0.5f
    const val MAX_THICKNESS_DP = 15f

    /** Palette proposée pour colorer un parcours. */
    val COLOR_PALETTE = listOf(
        0xFF8B5CF6.toInt(), // Violet
        0xFF39FF14.toInt(), // Vert fluo
        0xFFD32F2F.toInt(), // Rouge
        0xFFFF9800.toInt(), // Orange
        0xFF2196F3.toInt(), // Bleu
        0xFF00BCD4.toInt(), // Cyan
        0xFFE91E63.toInt(), // Rose
        0xFFFFEB3B.toInt(), // Jaune
        0xFF795548.toInt(), // Brun
        0xFF000000.toInt()  // Noir
    )

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /** Épaisseur choisie par l'utilisateur, en dp — indépendante de la densité d'écran. */
    fun getThicknessDp(context: Context): Float {
        val stored = prefs(context).getFloat(KEY_THICKNESS_DP, DEFAULT_THICKNESS_DP)
        return stored.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP)
    }

    fun setThicknessDp(context: Context, dp: Float) {
        prefs(context).edit()
            .putFloat(KEY_THICKNESS_DP, dp.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP))
            .apply()
    }

    /**
     * Largeur du trait en pixels, celle qu'attend `Paint.strokeWidth` — convertie
     * depuis la valeur en dp choisie par l'utilisateur via la densité de l'écran.
     */
    fun getStrokeWidth(context: Context): Float =
        getThicknessDp(context) * context.resources.displayMetrics.density

    /**
     * Couleur d'un parcours dont personne n'a choisi la couleur et dont le fichier
     * n'en portait pas.
     *
     * Ce repli n'est pas un détail : un GPX ne porte jamais de couleur, et un KML peut
     * n'en porter aucune. Sans lui, ces parcours seraient dessinés en noir — soit
     * invisibles sur le fond de carte sombre.
     */
    fun defaultColorFor(isImported: Boolean, isMerged: Boolean): Int = when {
        isMerged -> DEFAULT_COLOR_MERGED
        isImported -> DEFAULT_COLOR_IMPORTED
        else -> DEFAULT_COLOR_RECORDED
    }

    /**
     * Couleur à employer pour dessiner un parcours, dans l'ordre de priorité :
     *
     * 1. le choix explicite de l'utilisateur pour ce parcours ([displayColor]) ;
     * 2. à défaut, la couleur que portait son fichier ([sourceColor]) ;
     * 3. à défaut, la couleur par défaut de sa catégorie.
     *
     * Le choix explicite passe **devant** la couleur du fichier, et c'est voulu :
     * demander une couleur franche sur un parcours importé doit se voir, sinon la
     * pastille se sélectionnerait sans que le tracé change à l'écran.
     */
    fun resolveTrackColor(
        displayColor: Int?,
        sourceColor: Int?,
        isImported: Boolean,
        isMerged: Boolean
    ): Int = displayColor ?: sourceColor ?: defaultColorFor(isImported, isMerged)

    /** La reprise des couleurs par parcours a-t-elle déjà eu lieu ? */
    fun hasMigratedColors(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COLORS_MIGRATED, false)

    fun setMigratedColors(context: Context) {
        prefs(context).edit().putBoolean(KEY_COLORS_MIGRATED, true).apply()
    }

    /**
     * Les couleurs par catégorie de l'ancienne version, telles que l'utilisateur les
     * avait réglées. Lues uniquement par la reprise, pour figer l'apparence existante.
     */
    class LegacyCategoryColors(
        val recorded: Int,
        val imported: Int,
        val merged: Int,
        val fromFile: Boolean
    )

    fun readLegacyCategoryColors(context: Context): LegacyCategoryColors {
        val p = prefs(context)
        return LegacyCategoryColors(
            recorded = p.getInt(LEGACY_KEY_COLOR_RECORDED, DEFAULT_COLOR_RECORDED),
            imported = p.getInt(LEGACY_KEY_COLOR_IMPORTED, DEFAULT_COLOR_IMPORTED),
            merged = p.getInt(LEGACY_KEY_COLOR_MERGED, DEFAULT_COLOR_MERGED),
            fromFile = p.getBoolean(LEGACY_KEY_IMPORTED_FROM_FILE, false)
        )
    }
}
