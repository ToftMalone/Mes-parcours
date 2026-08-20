package com.example.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Apparence des tracés sur la carte : épaisseur du trait et couleur par catégorie
 * de parcours (enregistré / importé).
 */
object TrackStylePreferences {

    private const val KEY_THICKNESS_DP = "pref_track_thickness_dp"
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

    /** Épaisseur par défaut, en dp — comparable à l'ancien niveau « Normal ». */
    const val DEFAULT_THICKNESS_DP = 4f

    /** Bornes du réglage : en dessous, le trait devient difficile à voir sur la
     *  carte ; au-delà, il masque le fond de carte plus qu'il ne montre le tracé. */
    const val MIN_THICKNESS_DP = 0.5f
    const val MAX_THICKNESS_DP = 15f

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
