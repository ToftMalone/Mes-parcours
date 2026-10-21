package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val activityType: String = "Hiking", // Hiking, Cycling, Running, etc.
    val startTime: Long,
    val endTime: Long = 0,
    val totalDistance: Double = 0.0, // in meters
    val duration: Long = 0, // in seconds
    val maxSpeed: Double = 0.0, // in m/s
    val avgSpeed: Double = 0.0, // in m/s
    val elevationGain: Double = 0.0, // in meters
    val elevationLoss: Double = 0.0, // in meters
    val isRecording: Boolean = false,
    val isImported: Boolean = false,
    val isSelectedForMap: Boolean = false,
    val isMerged: Boolean = false,
    /**
     * Track color read from the imported file (ARGB), or null if the file carried
     * none — the case of every GPX, and of KMLs without a line style.
     *
     * Nullable on purpose: we must be able to tell “the file said nothing”
     * from “the file asked for black”. Without that distinction, a track without
     * a color would be drawn in black instead of taking the palette color.
     */
    val sourceColor: Int? = null,
    /**
     * Color chosen by the user for **this very track** (ARGB), or null
     * for “automatic”: the color of the original file if it carried one,
     * otherwise the category's default color.
     *
     * Nullable for the same reason as `sourceColor`: “I chose nothing” and
     * “I chose black” must remain distinct. Without that distinction, a track never
     * colored by hand would lose its file's color.
     *
     * Replaces the three per-category colors that used to live in preferences:
     * the color now belongs to the track, not to the tab.
     */
    val displayColor: Int? = null,
    /**
     * Line thickness chosen for **this very track**, in dp, or null as long as
     * nobody chose one: it then keeps the former general thickness (see
     * `TrackStylePreferences.resolveThicknessDp`). Set together with the color, in
     * the same sheet; the general setting has left the Settings screen.
     */
    val displayWidthDp: Float? = null,
    /**
     * Recording paused, **stored in the database** (migration 9→10).
     *
     * The pause only lived in the service's memory: if Android killed the app
     * during a pause and then restarted it — or after a phone reboot — recording
     * resumed by itself, and the following trip was recorded even though the user
     * had paused it. Only meaningful while [isRecording] is true.
     */
    @ColumnInfo(defaultValue = "0")
    val isPaused: Boolean = false
) {
    companion object {
        /**
         * `startTime` of the row reserved during an import, before the file
         * provides one. The import is no longer a single transaction (see
         * `TrackRepository.importTrackFromUri`): this row therefore exists in the database
         * for the duration of the import, and this marker is what hides it from the history and
         * lets it be found again if the app is killed midway.
         */
        const val IMPORT_PLACEHOLDER_START_TIME = -1L
    }
}
