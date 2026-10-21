package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.model.TrackPointMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    // The reserved row of an import in progress is not yet a track: showing it
    // would allow opening, merging or deleting it while the import
    // is still writing to it.
    @Query("SELECT * FROM tracks WHERE startTime != ${Track.IMPORT_PLACEHOLDER_START_TIME} ORDER BY startTime DESC")
    fun getAllTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE isSelectedForMap = 1")
    fun getSelectedImportedTracksFlow(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    fun getTrackByIdFlow(trackId: Long): Flow<Track?>

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: Long): Track?

    @Query("SELECT * FROM tracks WHERE isRecording = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getActiveRecordingTrack(): Track?

    /**
     * Writes to `displayColor` the color each track displayed in the days of
     * per-category colors, so that the move to per-track colors changes
     * nothing on screen.
     *
     * Reproduces the old resolution exactly: the file's color if the “keep original
     * colors” setting was on and the file carried one,
     * otherwise the category's color.
     *
     * In SQL rather than Kotlin: a single query, no track loaded into memory.
     * `WHERE displayColor IS NULL` makes it replayable without overwriting a choice already made.
     */
    @Query(
        """
        UPDATE tracks SET displayColor = CASE
            WHEN isMerged = 1 THEN
                CASE WHEN :fromFile = 1 AND sourceColor IS NOT NULL THEN sourceColor ELSE :merged END
            WHEN isImported = 1 THEN
                CASE WHEN :fromFile = 1 AND sourceColor IS NOT NULL THEN sourceColor ELSE :imported END
            ELSE :recorded
        END
        WHERE displayColor IS NULL
        """
    )
    suspend fun backfillDisplayColors(
        recorded: Int,
        imported: Int,
        merged: Int,
        fromFile: Int
    )

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY id ASC")
    suspend fun getPointsForTrack(trackId: Long): List<TrackPoint>

    // ------------------------------------------------------------------
    // Viewport-based display: we never load a whole dense track into memory,
    // only an overall silhouette + the detail of the visible
    // area. All points remain in the database.
    // ------------------------------------------------------------------

    /** Count and geographic extent of a track, computed once then cached. */
    @Query(
        "SELECT MIN(id) AS minId, COUNT(*) AS pointCount, " +
                "MIN(latitude) AS minLat, MAX(latitude) AS maxLat, " +
                "MIN(longitude) AS minLon, MAX(longitude) AS maxLon " +
                "FROM track_points WHERE trackId = :trackId"
    )
    suspend fun getTrackPointMeta(trackId: Long): TrackPointMeta?

    /** Number of points in the visible area. Used to calibrate the sampling step. */
    @Query(
        "SELECT COUNT(*) FROM track_points " +
                "WHERE trackId = :trackId " +
                "AND latitude BETWEEN :minLat AND :maxLat " +
                "AND longitude BETWEEN :minLon AND :maxLon"
    )
    suspend fun countPointsInBounds(
        trackId: Long,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): Int

    /**
     * Silhouette of the whole track: one point every [stride], plus all the
     * segment breaks so as not to join two distinct segments.
     * Computed once per track then kept in cache.
     */
    @Query(
        "SELECT * FROM track_points " +
                "WHERE trackId = :trackId " +
                "AND (isDiscontinuous = 1 OR ((id - :minId) % :stride) = 0) " +
                "ORDER BY id ASC"
    )
    suspend fun getSkeletonPoints(trackId: Long, minId: Long, stride: Long): List<TrackPoint>

    /**
     * Detail of the visible area. With [stride] = 1 (enough zoom), returns
     * absolutely every point of the track present on screen.
     */
    @Query(
        "SELECT * FROM track_points " +
                "WHERE trackId = :trackId " +
                "AND latitude BETWEEN :minLat AND :maxLat " +
                "AND longitude BETWEEN :minLon AND :maxLon " +
                "AND ((id - :minId) % :stride) = 0 " +
                "ORDER BY id ASC"
    )
    suspend fun getDetailPointsInBounds(
        trackId: Long,
        minId: Long,
        stride: Long,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<TrackPoint>

    // ------------------------------------------------------------------
    // Track merging, entirely on the database side: points are copied
    // by SQLite without ever passing through the app's memory.
    // ------------------------------------------------------------------

    /**
     * Timestamp of the first and last point of a track.
     *
     * Trimming uses them to convert “remove the first five minutes” into
     * an absolute time bound, without loading a single point: two integers
     * are enough, whatever the size of the track.
     *
     * The order is that of the identifiers, not of the timestamps — it is that of the
     * trip, and the one the rest of the project follows.
     */
    @Query("SELECT timestamp FROM track_points WHERE trackId = :trackId ORDER BY id ASC LIMIT 1")
    suspend fun getFirstPointTimestamp(trackId: Long): Long?

    @Query("SELECT timestamp FROM track_points WHERE trackId = :trackId ORDER BY id DESC LIMIT 1")
    suspend fun getLastPointTimestamp(trackId: Long): Long?

    @Query("SELECT MAX(id) FROM track_points WHERE trackId = :trackId")
    suspend fun getMaxPointId(trackId: Long): Long?

    @Query("SELECT MAX(id) FROM track_points")
    suspend fun getMaxPointIdGlobal(): Long?

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId AND id > :afterId")
    suspend fun countPointsAfter(trackId: Long, afterId: Long): Int

    /**
     * Copies the points of [sourceId] to the end of [destinationId].
     *
     * The ORDER BY is essential: without it the planner could walk
     * the (trackId, latitude) index and copy the points in latitude order.
     * The [maxSourceId] bound makes it possible to copy a track into itself
     * without rereading the rows just written.
     */
    @Query(
        "INSERT INTO track_points (trackId, latitude, longitude, altitude, speed, timestamp, isDiscontinuous, segmentColor) " +
                "SELECT :destinationId, latitude, longitude, altitude, speed, timestamp, isDiscontinuous, segmentColor " +
                "FROM track_points WHERE trackId = :sourceId AND id <= :maxSourceId ORDER BY id ASC"
    )
    suspend fun copyPointsInto(destinationId: Long, sourceId: Long, maxSourceId: Long)

    /**
     * Copies into [destinationId] the points of [sourceId] whose identifier goes from
     * [fromId] inclusive to [untilId] exclusive. This is what lets splitting pour
     * a slice of the original track into a new track without loading a single
     * point into memory.
     *
     * The `ORDER BY` is essential for the same reason as in [copyPointsInto]:
     * without it, the planner may take the (trackId, latitude) index and
     * copy the points in latitude order rather than trip order.
     *
     * For the last slice, pass `Long.MAX_VALUE` as [untilId].
     */
    @Query(
        "INSERT INTO track_points (trackId, latitude, longitude, altitude, speed, timestamp, isDiscontinuous, segmentColor) " +
                "SELECT :destinationId, latitude, longitude, altitude, speed, timestamp, isDiscontinuous, segmentColor " +
                "FROM track_points WHERE trackId = :sourceId AND id >= :fromId AND id < :untilId ORDER BY id ASC"
    )
    suspend fun copyPointRangeInto(
        destinationId: Long,
        sourceId: Long,
        fromId: Long,
        untilId: Long
    )

    /**
     * Removes the break mark from the first point of a track.
     *
     * A split slice starts precisely on the point that opened a new
     * segment: in its original track that was a break, in the new
     * track it is simply the start of the line. Leaving the mark would open an
     * empty segment before the first point.
     */
    @Query(
        "UPDATE track_points SET isDiscontinuous = 0 " +
                "WHERE id = (SELECT MIN(id) FROM track_points WHERE trackId = :trackId)"
    )
    suspend fun clearFirstPointDiscontinuity(trackId: Long)

    /** Marks the first point inserted after [afterId] as the start of a new segment. */
    @Query(
        "UPDATE track_points SET isDiscontinuous = 1 " +
                "WHERE id = (SELECT MIN(id) FROM track_points WHERE trackId = :trackId AND id > :afterId)"
    )
    suspend fun markFirstPointAfterAsDiscontinuous(trackId: Long, afterId: Long)

    @Query("DELETE FROM track_points WHERE trackId = :trackId AND id <= :maxId")
    suspend fun deletePointsUpTo(trackId: Long, maxId: Long)

    /** Last points of a track (descending order), to resume a recording. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY id DESC LIMIT :limit")
    suspend fun getLastPoints(trackId: Long, limit: Int): List<TrackPoint>

    /** Key-paginated read, to export a track without loading it entirely. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPointsPage(trackId: Long, afterId: Long, limit: Int): List<TrackPoint>

    // ------------------------------------------------------------------
    // Inserts: ABORT, and above all not REPLACE.
    //
    // All inserts pass an id of 0, so they let SQLite assign it: a
    // primary key conflict cannot happen today. But REPLACE
    // means DELETE then INSERT, and track_points is tied to tracks by a
    // foreign key ON DELETE CASCADE: the day a Track were reinserted with an
    // existing id, the implicit deletion would take all its points with it. A track of
    // several million points would vanish without a single message.
    //
    // ABORT makes the insert fail instead. The trap can no longer close.
    // ------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrack(track: Track): Long

    @Update
    suspend fun updateTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrackPoint(point: TrackPoint): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrackPoints(points: List<TrackPoint>)

    // Blocking variants, used by the streaming import: SAX parsing is
    // synchronous and cannot call a suspend function. To be called only
    // from a background thread.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTrackBlocking(track: Track): Long

    @Update
    fun updateTrackBlocking(track: Track)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTrackPointsBlocking(points: List<TrackPoint>)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deletePointsForTrack(trackId: Long)

    /** Stores the pause of a recording, without rewriting the rest of the row. */
    @Query("UPDATE tracks SET isPaused = :paused WHERE id = :trackId")
    suspend fun setTrackPaused(trackId: Long, paused: Boolean)

    /** Rows left by an interrupted import: see `Track.IMPORT_PLACEHOLDER_START_TIME`. */
    @Query("DELETE FROM track_points WHERE trackId IN (SELECT id FROM tracks WHERE startTime = ${Track.IMPORT_PLACEHOLDER_START_TIME})")
    fun deleteImportPlaceholderPointsBlocking()

    @Query("DELETE FROM tracks WHERE startTime = ${Track.IMPORT_PLACEHOLDER_START_TIME}")
    fun deleteImportPlaceholdersBlocking()
}
