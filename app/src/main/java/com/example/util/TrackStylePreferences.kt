package com.example.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Apparence des tracés sur la carte : épaisseur du trait et couleur par catégorie
 * de parcours (enregistré / importé).
 */
object TrackStylePreferences {

    private const val KEY_THICKNESS_LEVEL = "pref_track_thickness_level"
    private const val KEY_COLOR_RECORDED = "pref_track_color_recorded"
    private const val KEY_COLOR_IMPORTED = "pref_track_color_imported"
    private const val KEY_COLOR_MERGED = "pref_track_color_merged"
    private const val KEY_IMPORTED_FROM_FILE = "pref_track_color_imported_from_file"

    /** Violet, couleur historique des parcours enregistrés. */
    const val DEFAULT_COLOR_RECORDED = 0xFF8B5CF6.toInt()

    /** Vert fluo, couleur historique des parcours importés. */
    const val DEFAULT_COLOR_IMPORTED = 0xFF39FF14.toInt()

    /** Orange, pour distinguer d'emblée les parcours fusionnés des deux autres. */
    const val DEFAULT_COLOR_MERGED = 0xFFFF9800.toInt()

    /** Niveau d'épaisseur : un libellé et la largeur du trait en pixels. */
    data class ThicknessLevel(val label: String, val strokeWidth: Float)

    val THICKNESS_LEVELS = listOf(
        ThicknessLevel("Très fin", 4f),
        ThicknessLevel("Fin", 8f),
        ThicknessLevel("Normal", 12f),
        ThicknessLevel("Épais", 16f),
        ThicknessLevel("Très épais", 22f),
        ThicknessLevel("Maximal", 28f)
    )

    /** "Normal" : correspond à l'épaisseur utilisée jusqu'ici. */
    const val DEFAULT_THICKNESS_LEVEL = 2

    /** Palette proposée pour les deux catégories. */
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

    fun getThicknessLevel(context: Context): Int {
        val stored = prefs(context).getInt(KEY_THICKNESS_LEVEL, DEFAULT_THICKNESS_LEVEL)
        return stored.coerceIn(0, THICKNESS_LEVELS.lastIndex)
    }

    fun setThicknessLevel(context: Context, level: Int) {
        prefs(context).edit()
            .putInt(KEY_THICKNESS_LEVEL, level.coerceIn(0, THICKNESS_LEVELS.lastIndex))
            .apply()
    }

    /** Largeur du trait correspondant au niveau enregistré. */
    fun getStrokeWidth(context: Context): Float =
        THICKNESS_LEVELS[getThicknessLevel(context)].strokeWidth

    fun getRecordedColor(context: Context): Int =
        prefs(context).getInt(KEY_COLOR_RECORDED, DEFAULT_COLOR_RECORDED)

    fun setRecordedColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR_RECORDED, color).apply()
    }

    fun getImportedColor(context: Context): Int =
        prefs(context).getInt(KEY_COLOR_IMPORTED, DEFAULT_COLOR_IMPORTED)

    fun setImportedColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR_IMPORTED, color).apply()
    }

    fun getMergedColor(context: Context): Int =
        prefs(context).getInt(KEY_COLOR_MERGED, DEFAULT_COLOR_MERGED)

    fun setMergedColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_COLOR_MERGED, color).apply()
    }

    /**
     * Faut-il dessiner chaque parcours importé avec la couleur que portait son
     * fichier — celle choisie dans Google Earth — plutôt qu'avec la couleur unique
     * de la palette ?
     *
     * Désactivé par défaut : l'affichage d'un parcours déjà importé ne doit pas
     * changer tout seul à la mise à jour de l'application.
     */
    fun isImportedColorFromFile(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IMPORTED_FROM_FILE, false)

    fun setImportedColorFromFile(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_IMPORTED_FROM_FILE, enabled).apply()
    }

    /**
     * Couleur à employer pour un parcours importé.
     *
     * Le repli sur [fallback] n'est pas un détail : un GPX ne porte jamais de
     * couleur, et un KML peut n'en porter aucune. Sans repli, ces parcours seraient
     * dessinés en noir — soit invisibles sur le fond de carte sombre.
     */
    fun resolveImportedColor(fromFile: Boolean, sourceColor: Int?, fallback: Int): Int =
        if (fromFile && sourceColor != null) sourceColor else fallback
}
