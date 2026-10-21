package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.TrackDao
import com.example.data.model.GpsStatus
import com.example.R
import com.example.data.model.LiveStats
import com.example.data.model.MapViewport
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.model.TrackPointMeta
import com.example.util.Importer
import com.example.util.UserFacingArgumentException
import com.example.util.UserFacingStateException
import com.example.util.Exporter
import com.example.util.MediaStoreExporter
import com.example.util.TrackStatsAccumulator
import com.example.util.TrackStylePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.room.withTransaction

class TrackRepository private constructor(private val database: AppDatabase) {

    private val trackDao: TrackDao get() = database.trackDao

    // Database access
    val allTracks: Flow<List<Track>> = trackDao.getAllTracks()

    fun getTrackByIdFlow(trackId: Long): Flow<Track?> = trackDao.getTrackByIdFlow(trackId)

    suspend fun getTrackById(trackId: Long): Track? = trackDao.getTrackById(trackId)

    suspend fun resumeExistingTrack(trackId: Long): Boolean {
        val track = trackDao.getTrackById(trackId) ?: return false
        val updated = track.copy(isRecording = true, isPaused = false)
        trackDao.updateTrack(updated)
        // The track is about to grow again: what was cached while it was frozen
        // no longer describes it.
        invalidatePointCaches(trackId)
        val resume = loadResumeState(updated)
        _currentTrackId.value = trackId
        _isTracking.value = true
        _isPaused.value = false
        _livePoints.value = resume.points
        _liveStats.value = resume.stats
        return true
    }

    /** Points to redisplay and starting statistics to resume a recording. */
    data class ResumeState(
        val points: List<TrackPoint>,
        val stats: LiveStats,
        /**
         * What is needed to extend the average speed: sum of the retained speeds and number
         * of readings. The service restarted from zero on these two totals while
         * distance and duration were restored, so that after a resume the
         * average only covered what had been recorded since.
         */
        val speedSumMps: Double = 0.0,
        val speedSamples: Int = 0
    )

    /** Beyond this, statistics are no longer recomputed point by point. */
    private val statsRecomputeLimit = 200_000

    /** Number of recent points kept in memory for the live display. */
    private val liveTailLimit = 20_000


    /**
     * Prepares the resume of a track without ever loading into memory an
     * imported track of several million points.
     *
     * Below [statsRecomputeLimit] points (any realistic recording:
     * 200,000 points = more than 55 h at 1 Hz), everything is recomputed as before. Beyond,
     * we start from the statistics already stored on the track — exact for an imported
     * track — and only reread the end of the line for the live map.
     */
    suspend fun loadResumeState(track: Track): ResumeState {
        val pointCount = trackDao.getTrackPointMeta(track.id)?.pointCount ?: 0

        if (pointCount <= statsRecomputeLimit) {
            val points = trackDao.getPointsForTrack(track.id)
            val accumulator = TrackStatsAccumulator()
            for (point in points) accumulator.add(point)
            return ResumeState(
                points = points,
                stats = accumulator.result(),
                speedSumMps = accumulator.speedSumMps,
                speedSamples = accumulator.speedSamples
            )
        }

        val tail = trackDao.getLastPoints(track.id, liveTailLimit).asReversed()
        val avgSpeed = if (track.duration > 0) track.totalDistance / track.duration else track.avgSpeed
        // Having not reread the speeds, we weight the known average by the count:
        // the points to come then weigh only their share, as if everything had been reread.
        return ResumeState(
            speedSumMps = avgSpeed * pointCount,
            speedSamples = pointCount,
            points = tail,
            stats = LiveStats(
                durationSec = track.duration,
                distanceMeters = track.totalDistance,
                currentSpeedMps = tail.lastOrNull()?.speed?.toDouble() ?: 0.0,
                avgSpeedMps = avgSpeed,
                maxSpeedMps = track.maxSpeed,
                elevationGain = track.elevationGain,
                elevationLoss = track.elevationLoss
            )
        )
    }

    suspend fun createNewTrack(name: String, activityType: String): Long {
        val startTime = System.currentTimeMillis()
        val generatedName = com.example.util.FormatUtils.formatTrackInProgressName(startTime)
        val track = Track(
            name = generatedName,
            activityType = "Track",
            startTime = startTime,
            isRecording = true
        )
        val trackId = trackDao.insertTrack(track)
        _currentTrackId.value = trackId
        _isTracking.value = true
        _livePoints.value = emptyList()
        _liveStats.value = LiveStats()
        return trackId
    }

    suspend fun finishTracking(trackId: Long, stats: LiveStats) {
        val existing = trackDao.getTrackById(trackId)
        if (existing != null) {
            val endTime = System.currentTimeMillis()
            // Only finalizes the name generated at creation (with the end time) if it
            // was never customized. Without this safeguard, resuming then stopping
            // an already named track — by a previous stop or a manual rename —
            // overwrote that name with a recomputed “Track on … from … to …”.
            val stillPlaceholderName = existing.name ==
                com.example.util.FormatUtils.formatTrackInProgressName(existing.startTime)
            val finalName = if (stillPlaceholderName) {
                com.example.util.FormatUtils.formatTrackName(existing.startTime, endTime)
            } else {
                existing.name
            }
            val updated = existing.copy(
                name = finalName,
                endTime = endTime,
                totalDistance = stats.distanceMeters,
                duration = stats.durationSec,
                maxSpeed = stats.maxSpeedMps,
                avgSpeed = stats.avgSpeedMps,
                elevationGain = stats.elevationGain,
                elevationLoss = stats.elevationLoss,
                isRecording = false,
                isPaused = false
            )
            trackDao.updateTrack(updated)
        }
        // The track is frozen: what is computed from it from now on may be
        // cached, but nothing from before must remain there.
        invalidatePointCaches(trackId)
        _currentTrackId.value = null
        _isTracking.value = false
        _isPaused.value = false
        _livePoints.value = emptyList()
        _liveStats.value = LiveStats()
    }

    // ------------------------------------------------------------------
    // Viewport-based display
    //
    // An imported track can have millions of points: loading them all
    // to draw the map would exhaust memory. We therefore combine a coarse
    // silhouette of the whole track (so the line stays continuous off screen)
    // with the detail of the visible area. Enough zoom = all real points
    // of the area are drawn. The database always keeps 100% of the points.
    // ------------------------------------------------------------------

    /** Below this number of points, the track is loaded entirely, as before. */
    private val fullLoadLimit = 60_000

    /** Target number of points for the silhouette of the whole track. */
    private val skeletonBudget = 4_000

    /** Target number of points for the detail of the visible area. */
    private val detailBudget = 40_000

    /**
     * Beyond this fraction of the track's extent covered by the view, we settle for
     * the silhouette: the view takes in almost everything, detail would bring
     * nothing and cost a full pass over the track.
     */
    private val skeletonOnlyCoverage = 0.55

    private val pointMetaCache = java.util.concurrent.ConcurrentHashMap<Long, TrackPointMeta>()
    private val skeletonCache = java.util.concurrent.ConcurrentHashMap<Long, List<TrackPoint>>()

    /**
     * Full points of the tracks below [fullLoadLimit], kept from one call to the next.
     *
     * These tracks are returned in full whatever the visible area: the
     * result does not depend on the framing at all, so rereading it was
     * entirely wasted work. In automatic following, where the area is republished as
     * movement goes on, this amounted to asking SQLite again for up to sixty thousand rows
     * per displayed track and rebuilding as many objects — enough to keep busy
     * for several seconds before any new point appeared.
     *
     * The cache costs no extra memory, on the contrary: these lists
     * were already held by the display flow, and a fresh copy was made
     * at each turn while the previous one was still alive. Returning the same
     * instance also makes the map's comparison immediate, where it
     * used to go over every point to conclude nothing had changed.
     */
    private val fullPointsCache = java.util.concurrent.ConcurrentHashMap<Long, List<TrackPoint>>()

    fun invalidatePointCaches(trackId: Long) {
        pointMetaCache.remove(trackId)
        skeletonCache.remove(trackId)
        fullPointsCache.remove(trackId)
    }

    /**
     * Points to draw for [trackId] given the visible area [viewport].
     *
     * Never returns more than ~[skeletonBudget] + [detailBudget] points, whatever
     * the size of the track. For ordinary tracks (< [fullLoadLimit] points),
     * simply returns all points, exactly as before.
     */
    suspend fun getDisplayPoints(trackId: Long, viewport: MapViewport?): List<TrackPoint> {
        // A track being recorded grows every second: its count
        // and extent would already be wrong at the next read. Caching them
        // froze the display on what it contained at the first lookup — the
        // rest of the line no longer appeared until the app was left.
        // The cache only applies to frozen tracks, which are also the only ones dense
        // enough for the computation to cost anything.
        val isLive = _isTracking.value && _currentTrackId.value == trackId

        val meta = (if (isLive) null else pointMetaCache[trackId])
            ?: trackDao.getTrackPointMeta(trackId)?.also { if (!isLive) pointMetaCache[trackId] = it }
            ?: return emptyList()

        val minId = meta.minId
        if (meta.pointCount == 0 || minId == null) return emptyList()

        if (meta.pointCount <= fullLoadLimit) {
            // Independent of [viewport]: hence the caching, see [fullPointsCache].
            return (if (isLive) null else fullPointsCache[trackId])
                ?: trackDao.getPointsForTrack(trackId)
                    .also { if (!isLive) fullPointsCache[trackId] = it }
        }

        val globalStride = ceilDiv(meta.pointCount.toLong(), skeletonBudget.toLong())
        val skeleton = (if (isLive) null else skeletonCache[trackId])
            ?: trackDao.getSkeletonPoints(trackId, minId, globalStride)
                .also { if (!isLive) skeletonCache[trackId] = it }

        if (viewport == null) return skeleton

        // Nearly global view: the silhouette is enough, and we avoid a full pass.
        if (coversMostOfTrack(meta, viewport)) return skeleton

        val inView = trackDao.countPointsInBounds(
            trackId = trackId,
            minLat = viewport.minLat,
            maxLat = viewport.maxLat,
            minLon = viewport.minLon,
            maxLon = viewport.maxLon
        )
        if (inView == 0) return skeleton

        val localStride = ceilDiv(inView.toLong(), detailBudget.toLong())
        val detail = trackDao.getDetailPointsInBounds(
            trackId = trackId,
            minId = minId,
            stride = localStride,
            minLat = viewport.minLat,
            maxLat = viewport.maxLat,
            minLon = viewport.minLon,
            maxLon = viewport.maxLon
        )

        return mergeOrderedById(skeleton, detail)
    }

    /**
     * Walks through all the points of a track, page by page.
     *
     * Used by the export: guarantees that the produced file contains 100% of the
     * points, regardless of what is displayed on screen, without ever holding
     * the whole track in memory.
     */
    suspend fun forEachPoint(trackId: Long, pageSize: Int = 2_000, action: (TrackPoint) -> Unit) {
        var afterId = 0L
        while (true) {
            val page = trackDao.getPointsPage(trackId, afterId, pageSize)
            if (page.isEmpty()) break
            for (point in page) action(point)
            afterId = page.last().id
        }
    }

    private fun coversMostOfTrack(meta: TrackPointMeta, viewport: MapViewport): Boolean {
        val minLat = meta.minLat ?: return false
        val maxLat = meta.maxLat ?: return false
        val minLon = meta.minLon ?: return false
        val maxLon = meta.maxLon ?: return false

        val latSpan = maxLat - minLat
        val lonSpan = maxLon - minLon
        // Single-point track: no usable extent, we let the detail decide.
        if (latSpan <= 0.0 && lonSpan <= 0.0) return false

        val visibleLat = overlap(minLat, maxLat, viewport.minLat, viewport.maxLat)
        val visibleLon = overlap(minLon, maxLon, viewport.minLon, viewport.maxLon)

        val latRatio = if (latSpan > 0.0) visibleLat / latSpan else 1.0
        val lonRatio = if (lonSpan > 0.0) visibleLon / lonSpan else 1.0

        return latRatio >= skeletonOnlyCoverage && lonRatio >= skeletonOnlyCoverage
    }

    private fun overlap(aMin: Double, aMax: Double, bMin: Double, bMax: Double): Double {
        val lo = maxOf(aMin, bMin)
        val hi = minOf(aMax, bMax)
        return if (hi > lo) hi - lo else 0.0
    }

    private fun ceilDiv(total: Long, budget: Long): Long {
        if (budget <= 0L) return 1L
        return maxOf(1L, (total + budget - 1L) / budget)
    }

    /**
     * Merges two id-ordered subsets of the same track, with no duplicates.
     * The result remains a valid chronological subsequence: the line is continuous,
     * coarse off screen and fine in the visible area.
     */
    private fun mergeOrderedById(a: List<TrackPoint>, b: List<TrackPoint>): List<TrackPoint> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a

        val out = ArrayList<TrackPoint>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val ai = a[i].id
            val bj = b[j].id
            when {
                ai < bj -> { out.add(a[i]); i++ }
                ai > bj -> { out.add(b[j]); j++ }
                else -> { out.add(a[i]); i++; j++ }
            }
        }
        while (i < a.size) { out.add(a[i]); i++ }
        while (j < b.size) { out.add(b[j]); j++ }
        return out
    }

    /**
     * Imports a GPX/KML as a stream: points are written to the database as the
     * parsing goes, without ever loading the whole file or the complete list of
     * points into memory. The track row is inserted first (to get its
     * id), then updated with the statistics once parsing is finished.
     *
     * **One transaction per batch, no longer a single one for the whole import.** A single
     * transaction locked the database for writing from end to end: importing a big
     * KML during an outing froze the recording — GPS points, statistics,
     * notification — until the import ended. Each batch now holds the lock
     * only for the time of its insertion.
     *
     * Atomicity is restored by hand: the reserved row carries a marker `startTime`
     * ([Track.IMPORT_PLACEHOLDER_START_TIME]) that hides it from the history, a
     * failure deletes it along with its points, and [cleanupInterruptedImports] catches up at the
     * next launch the one from an import killed along with the app.
     */
    suspend fun importTrackFromUri(context: Context, uri: android.net.Uri): Long =
        withContext(Dispatchers.IO) {
            val placeholder = Track(
                name = "…",
                startTime = Track.IMPORT_PLACEHOLDER_START_TIME,
                isRecording = false,
                isImported = true,
                isSelectedForMap = false
            )
            val trackId = trackDao.insertTrackBlocking(placeholder)
            try {
                val summary = Importer.importFromUri(context, uri, trackId) { batch ->
                    trackDao.insertTrackPointsBlocking(batch)
                } ?: throw Importer.ImportException(R.string.err_import_unreadable)

                trackDao.updateTrackBlocking(
                    placeholder.copy(
                        id = trackId,
                        name = summary.name,
                        activityType = summary.activityType,
                        startTime = summary.startTime,
                        endTime = summary.endTime,
                        totalDistance = summary.totalDistance,
                        duration = summary.duration,
                        maxSpeed = summary.maxSpeed,
                        avgSpeed = summary.avgSpeed,
                        elevationGain = summary.elevationGain,
                        elevationLoss = summary.elevationLoss,
                        sourceColor = summary.sourceColor
                    )
                )
                trackId
            } catch (e: Throwable) {
                // NonCancellable: a cancellation must also leave the database clean.
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        trackDao.deletePointsForTrack(trackId)
                        trackDao.deleteTrack(trackId)
                    }
                }
                throw e
            }
        }

    /**
     * Deletes the rows of imports interrupted by the app being stopped. To be called
     * only at process start: no import can then be
     * running, since they do not survive the process that launched them.
     */
    fun cleanupInterruptedImports() {
        database.runInTransaction {
            trackDao.deleteImportPlaceholderPointsBlocking()
            trackDao.deleteImportPlaceholdersBlocking()
        }
    }

    /**
     * Merges [trackIds] into [destinationTrackId], in chronological order.
     *
     * The copying of points is done by SQLite itself (INSERT … SELECT): no
     * point passes through the app's memory, which makes it possible to merge
     * tracks of several million points.
     *
     * The destination's points are copied too, so they take their
     * chronological place among the others, then their original rows are
     * deleted. Everything happens in a single transaction: on failure,
     * no track is lost.
     */
    suspend fun mergeAndSaveTracks(
        trackIds: List<Long>,
        destinationTrackId: Long,
        mergedName: String
    ): Long {
        return database.withTransaction {
            val tracks = trackIds.map { trackId ->
                trackDao.getTrackById(trackId) ?: throw UserFacingArgumentException(R.string.err_track_id_not_found, listOf(trackId))
            }
            if (tracks.size < 2) {
                throw UserFacingArgumentException(R.string.err_merge_min_two)
            }
            // Merging the track being recorded deleted its row under the
            // service, which kept writing to it: every following point was refused by
            // the foreign key, silently, and the whole rest of the outing was lost.
            if (tracks.any { isBeingRecorded(it) }) {
                throw UserFacingArgumentException(R.string.err_merge_one_recording)
            }

            val destinationTrack = tracks.find { it.id == destinationTrackId }
                ?: throw UserFacingArgumentException(R.string.err_merge_destination_missing)

            val orderedTracks = tracks.sortedBy { it.startTime }

            // Bound of the points already present in the destination. It separates
            // old from new throughout the operation.
            val destinationOldMaxId = trackDao.getMaxPointId(destinationTrackId) ?: 0L

            val totalDistance = tracks.sumOf { it.totalDistance }
            val totalDuration = tracks.sumOf { it.duration }
            val totalElevationGain = tracks.sumOf { it.elevationGain }
            val totalElevationLoss = tracks.sumOf { it.elevationLoss }
            val maxSpeed = tracks.maxOf { it.maxSpeed }
            val avgSpeed = if (totalDuration > 0) totalDistance / totalDuration else tracks.maxOf { it.avgSpeed }

            val updatedDestinationTrack = destinationTrack.copy(
                name = mergedName,
                startTime = tracks.minOf { it.startTime },
                endTime = tracks.maxOf { it.endTime },
                totalDistance = totalDistance,
                duration = totalDuration,
                maxSpeed = maxSpeed,
                avgSpeed = avgSpeed,
                elevationGain = totalElevationGain,
                elevationLoss = totalElevationLoss,
                isRecording = false,
                // isImported is not recomputed: the origin of the host track
                // remains true. But the result now carries its own category —
                // the history has a “Merged” tab — and it is isMerged that
                // files it there, removing it from its original category.
                isMerged = true
            )

            trackDao.updateTrack(updatedDestinationTrack)

            // Copy track by track, in chronological order.
            var hasCopiedSomething = false
            for (track in orderedTracks) {
                val sourceMaxId = if (track.id == destinationTrackId) {
                    destinationOldMaxId
                } else {
                    trackDao.getMaxPointId(track.id) ?: 0L
                }
                if (sourceMaxId <= 0L) continue

                // Mark placed before the copy: everything carrying a higher id
                // has just been inserted by this step.
                val watermark = trackDao.getMaxPointIdGlobal() ?: 0L
                trackDao.copyPointsInto(destinationTrackId, track.id, sourceMaxId)

                if (trackDao.countPointsAfter(destinationTrackId, watermark) == 0) continue

                // Each added track opens a new segment: no line
                // joining the end of the previous one to the start of this one.
                if (hasCopiedSomething) {
                    trackDao.markFirstPointAfterAsDiscontinuous(destinationTrackId, watermark)
                }
                hasCopiedSomething = true
            }

            // The original points of the destination were copied into place:
            // we remove the initial rows.
            trackDao.deletePointsUpTo(destinationTrackId, destinationOldMaxId)

            // Delete the other tracks as well as their points
            tracks.forEach { track ->
                if (track.id != destinationTrackId) {
                    trackDao.deletePointsForTrack(track.id)
                    trackDao.deleteTrack(track.id)
                }
            }

            tracks.forEach { invalidatePointCaches(it.id) }

            destinationTrackId
        }
    }

    // ------------------------------------------------------------------
    // Splitting a track
    // ------------------------------------------------------------------

    /** What decides where the track is cut. */
    enum class SplitMode {
        /**
         * At the segment breaks already present in the track. This is what
         * imported files carry: a Google Earth export commonly gathers
         * dozens of trips, each in its own `<coordinates>`.
         */
        SEGMENT_BREAKS,

        /**
         * Where time stopped for longer than a chosen threshold. Useful on a
         * real recording cut by a night or a long pause.
         *
         * No effect on a GPX without `<time>` tags or on a KML, whose points
         * receive a regular synthetic timestamp on import: there is then
         * no gap to find, and the split reports it instead of failing.
         */
        TIME_GAP
    }

    /** Number of points reread per batch while locating the cuts. */
    private val splitScanPageSize = 2_000

    /**
     * A slice located during reading, before being written to the database.
     *
     * Bounds are point identifiers, never positions: that is what
     * lets SQLite copy the slice itself.
     */
    private data class PlannedPiece(
        val fromId: Long,
        val untilId: Long,
        val startTime: Long,
        val endTime: Long,
        val stats: LiveStats
    )

    /**
     * Splits [sourceTrackId] into several tracks, according to [mode].
     *
     * The original track is not modified in any way, and is only deleted if
     * [deleteSource] asks for it — the pieces then contain exactly the same
     * points, nothing is lost.
     *
     * Two steps, and this is deliberate:
     *
     * 1. **A single read** of the track, page by page, which locates the cuts and
     *    accumulates along the way the statistics of each piece
     *    ([TrackStatsAccumulator]). Nothing but the current page is in
     *    memory: a track of several million points goes through. This read
     *    happens **outside a transaction**, so as not to hold the database for writing
     *    for its whole duration.
     * 2. **A single transaction** that creates the tracks and has SQLite copy the points
     *    (`INSERT … SELECT`, invariant 5). An interruption therefore never leaves
     *    a half-done split.
     *
     * Throws [IllegalArgumentException] if the track is not found or is being
     * recorded, and [IllegalStateException] if there is nothing to split.
     */
    suspend fun splitTrack(
        sourceTrackId: Long,
        mode: SplitMode,
        gapMillis: Long,
        baseName: String,
        deleteSource: Boolean
    ): List<Long> {
        val source = trackDao.getTrackById(sourceTrackId)
            ?: throw UserFacingArgumentException(R.string.err_track_not_found)
        if (source.isRecording) {
            throw UserFacingArgumentException(R.string.err_track_is_recording)
        }

        // --- 1. Locating the cuts, in one pass ---

        val pieces = mutableListOf<PlannedPiece>()
        var accumulator = TrackStatsAccumulator()
        var pieceFromId = 0L
        var pieceStartTime = 0L
        var pieceEndTime = 0L
        var previous: TrackPoint? = null
        var afterId = 0L

        while (true) {
            val page = trackDao.getPointsPage(sourceTrackId, afterId, splitScanPageSize)
            if (page.isEmpty()) break
            afterId = page.last().id

            for (point in page) {
                val prev = previous
                if (prev == null) {
                    // Very first point of the track: it opens the first slice.
                    pieceFromId = point.id
                    pieceStartTime = point.timestamp
                } else {
                    val startsNewPiece = when (mode) {
                        SplitMode.SEGMENT_BREAKS -> point.isDiscontinuous
                        SplitMode.TIME_GAP -> point.timestamp - prev.timestamp >= gapMillis
                    }
                    if (startsNewPiece) {
                        // The current slice stops just before this point: its upper
                        // bound is exclusive, so it is this point's identifier.
                        pieces += PlannedPiece(
                            fromId = pieceFromId,
                            untilId = point.id,
                            startTime = pieceStartTime,
                            endTime = pieceEndTime,
                            stats = accumulator.result()
                        )
                        accumulator = TrackStatsAccumulator()
                        pieceFromId = point.id
                        pieceStartTime = point.timestamp
                    }
                }
                accumulator.add(point)
                pieceEndTime = point.timestamp
                previous = point
            }
        }

        if (previous == null) {
            throw UserFacingStateException(R.string.err_track_empty)
        }

        // Last slice: it goes to the very end, hence the maximal upper bound.
        pieces += PlannedPiece(
            fromId = pieceFromId,
            untilId = Long.MAX_VALUE,
            startTime = pieceStartTime,
            endTime = pieceEndTime,
            stats = accumulator.result()
        )

        if (pieces.size < 2) {
            throw UserFacingStateException(
                when (mode) {
                    SplitMode.SEGMENT_BREAKS -> R.string.err_split_single_segment
                    SplitMode.TIME_GAP -> R.string.err_split_no_gap
                }
            )
        }

        // --- 2. Writing ---

        return database.withTransaction {
            val newTrackIds = mutableListOf<Long>()

            pieces.forEachIndexed { index, piece ->
                val stats = piece.stats
                val newTrackId = trackDao.insertTrack(
                    Track(
                        name = "$baseName (${index + 1})",
                        activityType = source.activityType,
                        startTime = piece.startTime,
                        endTime = piece.endTime,
                        totalDistance = stats.distanceMeters,
                        duration = stats.durationSec,
                        maxSpeed = stats.maxSpeedMps,
                        avgSpeed = stats.avgSpeedMps,
                        elevationGain = stats.elevationGain,
                        elevationLoss = stats.elevationLoss,
                        isRecording = false,
                        // The origin does not change: a piece of an imported track
                        // stays imported, and stays filed in the tab where its parent
                        // used to be found.
                        isImported = source.isImported,
                        isMerged = source.isMerged,
                        // Never displayed by default: a split into thirty pieces
                        // would light them all up on the map at once.
                        isSelectedForMap = false,
                        // The pieces keep the parent's appearance, including the
                        // color chosen by hand if it had one.
                        sourceColor = source.sourceColor,
                        displayColor = source.displayColor,
                        displayWidthDp = source.displayWidthDp
                    )
                )

                trackDao.copyPointRangeInto(
                    destinationId = newTrackId,
                    sourceId = sourceTrackId,
                    fromId = piece.fromId,
                    untilId = piece.untilId
                )
                trackDao.clearFirstPointDiscontinuity(newTrackId)

                newTrackIds += newTrackId
            }

            if (deleteSource) {
                trackDao.deletePointsForTrack(sourceTrackId)
                trackDao.deleteTrack(sourceTrackId)
            }

            invalidatePointCaches(sourceTrackId)

            newTrackIds
        }
    }

    // ------------------------------------------------------------------
    // Trimming the start and the end
    // ------------------------------------------------------------------

    /**
     * Creates a copy of [sourceTrackId] cut of its first [dropStartMillis]
     * milliseconds and its last [dropEndMillis].
     *
     * The most ordinary need of a GPS tracker: the recording forgotten that
     * kept going during the trip back, or started from the car park ten minutes
     * before the real departure.
     *
     * Same shape as [splitTrack], and for the same reasons: a single read of the
     * track page by page — hence a memory footprint bounded whatever its
     * size — which locates the bounds **by point identifier** and accumulates along the
     * way the statistics of what will be kept; then a single transaction
     * where SQLite copies the slice itself (invariant 5).
     *
     * Bounds are compared with timestamps, never with a rank: on a
     * recording with gaps — pause, signal loss — removing “the first five
     * minutes” does not mean removing a fixed number of points.
     *
     * The original track is never modified, and is only deleted if
     * [deleteSource] asks for it. Throws [IllegalArgumentException] if the track is
     * not found or is being recorded, and [IllegalStateException] if there is
     * nothing to trim or if not enough would remain to make a track.
     */
    suspend fun trimTrack(
        sourceTrackId: Long,
        dropStartMillis: Long,
        dropEndMillis: Long,
        newName: String,
        deleteSource: Boolean
    ): Long {
        val source = trackDao.getTrackById(sourceTrackId)
            ?: throw UserFacingArgumentException(R.string.err_track_not_found)
        if (source.isRecording) {
            throw UserFacingArgumentException(R.string.err_track_is_recording)
        }
        if (dropStartMillis <= 0L && dropEndMillis <= 0L) {
            throw UserFacingStateException(R.string.err_trim_none_selected)
        }

        // --- 1. Track bounds, then locating, in two light reads ---

        val firstTimestamp = trackDao.getFirstPointTimestamp(sourceTrackId)
            ?: throw UserFacingStateException(R.string.err_track_empty)
        val lastTimestamp = trackDao.getLastPointTimestamp(sourceTrackId)
            ?: throw UserFacingStateException(R.string.err_track_empty)

        val keepFromTime = firstTimestamp + dropStartMillis
        val keepUntilTime = lastTimestamp - dropEndMillis
        if (keepFromTime >= keepUntilTime) {
            throw UserFacingStateException(R.string.err_trim_nothing_left)
        }

        // A single pass: the retained bounds are identifiers, and the
        // statistics are accumulated over the kept points only. Without this accumulation we
        // would have to reread the copy afterwards, i.e. the track twice.
        val accumulator = TrackStatsAccumulator()
        var fromId = -1L
        var untilId = Long.MAX_VALUE
        var keptStartTime = 0L
        var keptEndTime = 0L
        var keptCount = 0
        var afterId = 0L

        while (true) {
            val page = trackDao.getPointsPage(sourceTrackId, afterId, splitScanPageSize)
            if (page.isEmpty()) break
            afterId = page.last().id

            for (point in page) {
                if (point.timestamp < keepFromTime) continue
                if (point.timestamp > keepUntilTime) {
                    // First point beyond the kept end: it gives the upper
                    // bound, which is exclusive.
                    if (untilId == Long.MAX_VALUE) untilId = point.id
                    continue
                }
                if (fromId < 0L) {
                    fromId = point.id
                    keptStartTime = point.timestamp
                }
                accumulator.add(point)
                keptEndTime = point.timestamp
                keptCount++
            }
        }

        // At least two points: below that, there is neither a line nor a distance to show.
        if (keptCount < 2) {
            throw UserFacingStateException(R.string.err_trim_too_few)
        }

        val stats = accumulator.result()

        // --- 2. Writing ---

        return database.withTransaction {
            val newTrackId = trackDao.insertTrack(
                Track(
                    name = newName,
                    activityType = source.activityType,
                    startTime = keptStartTime,
                    endTime = keptEndTime,
                    totalDistance = stats.distanceMeters,
                    duration = stats.durationSec,
                    maxSpeed = stats.maxSpeedMps,
                    avgSpeed = stats.avgSpeedMps,
                    elevationGain = stats.elevationGain,
                    elevationLoss = stats.elevationLoss,
                    isRecording = false,
                    // Same inheritance as splitting: the origin and appearance
                    // of the parent, but never its display on the map.
                    isImported = source.isImported,
                    isMerged = source.isMerged,
                    isSelectedForMap = false,
                    sourceColor = source.sourceColor,
                    displayColor = source.displayColor,
                    displayWidthDp = source.displayWidthDp
                )
            )

            trackDao.copyPointRangeInto(
                destinationId = newTrackId,
                sourceId = sourceTrackId,
                fromId = fromId,
                untilId = untilId
            )
            // The first kept point may have opened a segment in the original
            // track; in the new track it only opens the line.
            trackDao.clearFirstPointDiscontinuity(newTrackId)

            if (deleteSource) {
                trackDao.deletePointsForTrack(sourceTrackId)
                trackDao.deleteTrack(sourceTrackId)
            }

            invalidatePointCaches(sourceTrackId)

            newTrackId
        }
    }

    fun getSelectedImportedTracksFlow(): Flow<List<Track>> {
        return trackDao.getSelectedImportedTracksFlow()
    }

    suspend fun updateTrack(track: Track) {
        trackDao.updateTrack(track)
    }

    /**
     * Gives each existing track the color it displayed before the move to
     * per-track colors, then notes that it is done.
     *
     * Called at every launch but only works once: without this backfill,
     * all the tracks of a user who had set per-category colors
     * would change appearance on update, without them having asked for anything.
     */
    suspend fun backfillDisplayColors(context: android.content.Context) {
        if (TrackStylePreferences.hasMigratedColors(context)) return
        val legacy = TrackStylePreferences.readLegacyCategoryColors(context)
        trackDao.backfillDisplayColors(
            recorded = legacy.recorded,
            imported = legacy.imported,
            merged = legacy.merged,
            fromFile = if (legacy.fromFile) 1 else 0
        )
        TrackStylePreferences.setMigratedColors(context)
        // Nothing to invalidate: caches only hold points, and Room re-emits
        // the track flows by itself after this UPDATE.
    }

    suspend fun insertPoint(point: TrackPoint) {
        if (point.trackId <= 0) return
        try {
            // No prior check that the track exists: it was an extra
            // query for every GPS point, i.e. one per second while
            // recording. The foreign key constraint already plays that role and
            // any failure is caught here.
            trackDao.insertTrackPoint(point)
            // Add to live list to refresh UI instantly
            _livePoints.value = _livePoints.value + point
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Deletes a track, unless it is being recorded: it then returns
     * false without touching anything.
     *
     * Deleting it under the service left it running idle — GPS on,
     * notification shown, every point refused by the foreign key — while
     * the screen thought it stopped and no longer offered a way to interrupt it.
     */
    suspend fun deleteTrack(trackId: Long): Boolean {
        val track = trackDao.getTrackById(trackId)
        if (track != null && isBeingRecorded(track)) return false
        database.withTransaction {
            trackDao.deletePointsForTrack(trackId)
            trackDao.deleteTrack(trackId)
        }
        invalidatePointCaches(trackId)
        return true
    }

    /** True for the track a recording feeds, or left open in the database. */
    fun isBeingRecorded(track: Track): Boolean =
        track.isRecording || (_isTracking.value && _currentTrackId.value == track.id)

    /** Stores the pause in the database, so that it survives the process being killed. */
    suspend fun setTrackPaused(trackId: Long, paused: Boolean) {
        trackDao.setTrackPaused(trackId, paused)
    }

    // In-memory state tracking to sync Service with UI
    private val _currentTrackId = MutableStateFlow<Long?>(null)
    val currentTrackId: StateFlow<Long?> = _currentTrackId.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    fun setTrackingState(isTracking: Boolean) {
        _isTracking.value = isTracking
    }

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    fun setRecordingPaused(paused: Boolean) {
        _isPaused.value = paused
    }

    private val _livePoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val livePoints: StateFlow<List<TrackPoint>> = _livePoints.asStateFlow()

    private val _liveStats = MutableStateFlow(LiveStats())
    val liveStats: StateFlow<LiveStats> = _liveStats.asStateFlow()

    fun updateLiveStats(stats: LiveStats) {
        _liveStats.value = stats
    }

    private val _gpsStatus = MutableStateFlow(GpsStatus.SEARCHING)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private val _gpsAccuracy = MutableStateFlow<Float?>(null)
    val gpsAccuracy: StateFlow<Float?> = _gpsAccuracy.asStateFlow()

    private val _currentUserLocation = MutableStateFlow<TrackPoint?>(null)
    val currentUserLocation: StateFlow<TrackPoint?> = _currentUserLocation.asStateFlow()

    /**
     * Current altitude above sea level, or null if no usable
     * measurement was obtained.
     *
     * Published apart from [currentUserLocation]: `TrackPoint.altitude` is not
     * nullable and therefore cannot tell “unknown altitude” from “zero
     * altitude”, whereas the display must show “—” in the first case rather
     * than a “0 m” that would read as a measurement.
     */
    private val _currentAltitude = MutableStateFlow<com.example.util.AltitudeFix?>(null)
    val currentAltitude: StateFlow<com.example.util.AltitudeFix?> = _currentAltitude.asStateFlow()

    fun updateAltitude(fix: com.example.util.AltitudeFix?) {
        _currentAltitude.value = fix
    }

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    fun updateAppForegroundStatus(inForeground: Boolean) {
        _isAppInForeground.value = inForeground
    }

    fun updateGpsStatus(status: GpsStatus) {
        _gpsStatus.value = status
    }

    fun updateGpsAccuracy(accuracy: Float?) {
        _gpsAccuracy.value = accuracy
    }

    fun updateUserLocation(location: TrackPoint?) {
        _currentUserLocation.value = location
    }

    fun restoreActiveTrackingState(trackId: Long, points: List<TrackPoint>, stats: LiveStats) {
        _currentTrackId.value = trackId
        _isTracking.value = true
        _livePoints.value = points
        _liveStats.value = stats
    }

    suspend fun getActiveRecordingTrack(): Track? {
        return trackDao.getActiveRecordingTrack()
    }

    /**
     * Finds a recording left open and brings the live state up to date.
     *
     * @param markPaused Should the track found be presented as paused?
     * True at app launch, which discovers a track with nothing feeding
     * it. False when it is the service calling to resume right
     * away: marking a pause it has just lifted showed “paused”
     * on screen while positions were really being recorded.
     */
    suspend fun checkForAndRestoreActiveTrack(markPaused: Boolean = true): Track? {
        if (_isTracking.value && _currentTrackId.value != null) {
            val id = _currentTrackId.value!!
            return trackDao.getTrackById(id)
        }
        val activeTrack = trackDao.getActiveRecordingTrack()
        if (activeTrack != null) {
            val resume = loadResumeState(activeTrack)
            restoreActiveTrackingState(activeTrack.id, resume.points, resume.stats)
            _isPaused.value = markPaused
            return activeTrack
        }
        return null
    }

    /**
     * Statistics of a list of points already in memory.
     *
     * A simple wrapper around [TrackStatsAccumulator], which carries the computation itself
     * and also serves splitting, where points arrive as a stream. A second
     * implementation for lists would end up diverging from the streaming one.
     */
    fun calculateStatsFromPoints(points: List<TrackPoint>): LiveStats {
        val accumulator = TrackStatsAccumulator()
        for (point in points) accumulator.add(point)
        return accumulator.result()
    }

    companion object {
        @Volatile
        private var INSTANCE: TrackRepository? = null

        fun getInstance(context: Context): TrackRepository {
            return INSTANCE ?: synchronized(this) {
                // Re-check under the lock. Without it, two simultaneous calls
                // (GPS service and interface at startup, for example) each create
                // a repository with its own StateFlows: the service then feeds
                // one instance while the screen observes another, and the live points and
                // statistics never appear.
                INSTANCE ?: TrackRepository(AppDatabase.getInstance(context))
                    .also { INSTANCE = it }
            }
        }

        /** Isolated instance on a supplied database, for tests. */
        @androidx.annotation.VisibleForTesting
        fun createForTesting(database: AppDatabase): TrackRepository = TrackRepository(database)
    }
}
