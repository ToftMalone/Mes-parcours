package com.example.data.model

/**
 * A track overlaid on the map, with what is needed to choose its color.
 *
 * Replaces the `(isImported, points)` pair used until now: the color can no longer
 * be deduced from the track's category alone, since an imported track can now
 * carry the one from its original file.
 */
data class MapTrack(
    val isImported: Boolean,
    /** A merged track forms its own category, with its own color. */
    val isMerged: Boolean,
    /** Color read from the imported file, null if it carried none. */
    val sourceColor: Int?,
    /** Color chosen by the user for this track, null if nothing was chosen. */
    val displayColor: Int? = null,
    /** Thickness chosen for this track, in dp, null if nothing was chosen. */
    val displayWidthDp: Float? = null,
    val points: List<TrackPoint>
)
