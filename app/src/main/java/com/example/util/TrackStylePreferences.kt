package com.example.util

import android.content.Context
import android.preference.PreferenceManager

/**
 * Appearance of tracks on the map: line thickness, and palette offered to
 * color a track.
 *
 * **The color belongs to the track, no longer to the category.** It is stored
 * in `Track.displayColor` and chosen from the track's card in the
 * history. The three per-category colors that used to live here in preferences were
 * removed: they now only serve as a default value, in the form of
 * constants, for a track whose color nobody chose and whose file
 * carried none.
 */
object TrackStylePreferences {

    private const val KEY_THICKNESS_DP = "pref_track_thickness_dp"

    /**
     * Marks the color backfill as done, so it only runs once.
     *
     * See `TrackRepository.backfillDisplayColors`: on moving to per-track
     * colors, each existing track explicitly receives the one it displayed
     * until then, so that the update changes nothing on screen.
     */
    private const val KEY_COLORS_MIGRATED = "pref_track_colors_migrated_to_per_track"

    // Old keys, read only once by the backfill above and never again.
    // Do not reuse them for anything else: an old installation still carries
    // them, with the colors the user had chosen by category.
    private const val LEGACY_KEY_COLOR_RECORDED = "pref_track_color_recorded"
    private const val LEGACY_KEY_COLOR_IMPORTED = "pref_track_color_imported"
    private const val LEGACY_KEY_COLOR_MERGED = "pref_track_color_merged"
    private const val LEGACY_KEY_IMPORTED_FROM_FILE = "pref_track_color_imported_from_file"

    /** Purple, historical color of recorded tracks. */
    const val DEFAULT_COLOR_RECORDED = 0xFF8B5CF6.toInt()

    /** Neon green, historical color of imported tracks. */
    const val DEFAULT_COLOR_IMPORTED = 0xFF39FF14.toInt()

    /** Orange, to tell merged tracks apart at a glance from the other two. */
    const val DEFAULT_COLOR_MERGED = 0xFFFF9800.toInt()

    /** Default thickness, in dp — comparable to the old “Normal” level. */
    const val DEFAULT_THICKNESS_DP = 4f

    /** Bounds of the setting: below, the line becomes hard to see on the
      *  map; beyond, it hides the map background more than it shows the track. */
    const val MIN_THICKNESS_DP = 0.5f
    const val MAX_THICKNESS_DP = 15f

    /**
     * Palette offered to color a track: the redesign's, saturated enough
     * to stay readable on the Standard and Satellite backgrounds, in light as in
     * dark. Changing the colors offered touches no existing track: their
     * color is stored in `Track.displayColor`, not designated by its place
     * in this list. The old palette had a black, invisible on the dark
     * map; the new one has none.
     */
    val COLOR_PALETTE = listOf(
        0xFFE8505B.toInt(), // Red
        0xFFFF7043.toInt(), // Orange
        0xFFF2A516.toInt(), // Amber
        0xFF7CB342.toInt(), // Green
        0xFF13A38A.toInt(), // Emerald
        0xFF2F7DE1.toInt(), // Blue
        0xFF5C6BC0.toInt(), // Indigo
        0xFF8E5BE8.toInt(), // Violet
        0xFFE0569B.toInt(), // Pink
        0xFF795548.toInt()  // Brown
    )

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    /**
     * General thickness, in dp — the one the single Settings option had
     * before thickness became a per-track choice. No screen writes it any
     * more: it is still read so that a track with no choice of its own keeps the look
     * it had, and for the track being recorded.
     */
    fun getThicknessDp(context: Context): Float {
        val stored = prefs(context).getFloat(KEY_THICKNESS_DP, DEFAULT_THICKNESS_DP)
        return stored.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP)
    }

    fun setThicknessDp(context: Context, dp: Float) {
        prefs(context).edit()
            .putFloat(KEY_THICKNESS_DP, dp.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP))
            .apply()
    }

    /** Thickness to use for a track: its own if it has one, otherwise the general one. */
    fun resolveThicknessDp(context: Context, displayWidthDp: Float?): Float =
        displayWidthDp?.coerceIn(MIN_THICKNESS_DP, MAX_THICKNESS_DP) ?: getThicknessDp(context)

    /**
     * Line width in pixels, the one `Paint.strokeWidth` expects — converted
     * from dp via the screen density.
     */
    fun getStrokeWidth(context: Context, displayWidthDp: Float? = null): Float =
        resolveThicknessDp(context, displayWidthDp) * context.resources.displayMetrics.density

    /**
     * Color of a track whose color nobody chose and whose file
     * carried none.
     *
     * This fallback is not a detail: a GPX never carries a color, and a KML may
     * carry none. Without it, these tracks would be drawn in black — i.e.
     * invisible on the dark map background.
     */
    fun defaultColorFor(isImported: Boolean, isMerged: Boolean): Int = when {
        isMerged -> DEFAULT_COLOR_MERGED
        isImported -> DEFAULT_COLOR_IMPORTED
        else -> DEFAULT_COLOR_RECORDED
    }

    /**
     * Color to use to draw a track, in order of priority:
     *
     * 1. the user's explicit choice for this track ([displayColor]);
     * 2. failing that, the color its file carried ([sourceColor]);
     * 3. failing that, the default color of its category.
     *
     * The explicit choice comes **before** the file's color, and that is deliberate:
     * asking for a bold color on an imported track must show, otherwise the
     * chip would get selected without the line changing on screen.
     */
    fun resolveTrackColor(
        displayColor: Int?,
        sourceColor: Int?,
        isImported: Boolean,
        isMerged: Boolean
    ): Int = displayColor ?: sourceColor ?: defaultColorFor(isImported, isMerged)

    /** Has the per-track color backfill already happened? */
    fun hasMigratedColors(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COLORS_MIGRATED, false)

    fun setMigratedColors(context: Context) {
        prefs(context).edit().putBoolean(KEY_COLORS_MIGRATED, true).apply()
    }

    /**
     * The per-category colors of the old version, as the user had
     * set them. Read only by the backfill, to freeze the existing appearance.
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
