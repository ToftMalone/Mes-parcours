package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.TrackDao
import com.example.data.model.LiveStats
import com.example.data.model.MapViewport
import com.example.data.model.Track
import com.example.data.model.TrackPoint
import com.example.data.model.TrackPointMeta
import com.example.util.Importer
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
        val updated = track.copy(isRecording = true)
        trackDao.updateTrack(updated)
        // La trace va se remettre à grossir : ce qui avait été mis en cache quand elle
        // était figée ne la décrit déjà plus.
        invalidatePointCaches(trackId)
        val resume = loadResumeState(updated)
        _currentTrackId.value = trackId
        _isTracking.value = true
        _isPaused.value = false
        _livePoints.value = resume.points
        _liveStats.value = resume.stats
        return true
    }

    /** Points à réafficher et statistiques de départ pour reprendre un enregistrement. */
    data class ResumeState(
        val points: List<TrackPoint>,
        val stats: LiveStats
    )

    /** Au-delà, on ne recalcule plus les statistiques point par point. */
    private val statsRecomputeLimit = 200_000

    /** Nombre de points récents conservés en mémoire pour l'affichage en direct. */
    private val liveTailLimit = 20_000


    /**
     * Prépare la reprise d'une trace sans jamais charger en mémoire une trace
     * importée de plusieurs millions de points.
     *
     * En dessous de [statsRecomputeLimit] points (tout enregistrement réaliste :
     * 200 000 points = plus de 55 h à 1 Hz), on recalcule tout comme avant. Au-delà,
     * on repart des statistiques déjà stockées sur la trace — exactes pour une trace
     * importée — et on ne relit que la fin du tracé pour la carte en direct.
     */
    suspend fun loadResumeState(track: Track): ResumeState {
        val pointCount = trackDao.getTrackPointMeta(track.id)?.pointCount ?: 0

        if (pointCount <= statsRecomputeLimit) {
            val points = trackDao.getPointsForTrack(track.id)
            return ResumeState(points, calculateStatsFromPoints(points))
        }

        val tail = trackDao.getLastPoints(track.id, liveTailLimit).asReversed()
        val avgSpeed = if (track.duration > 0) track.totalDistance / track.duration else track.avgSpeed
        return ResumeState(
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
            activityType = "Parcours",
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
            // Ne finalise le nom généré à la création (avec l'heure de fin) que s'il
            // n'a jamais été personnalisé. Sans ce garde-fou, reprendre puis arrêter
            // une trace déjà nommée — par un arrêt précédent ou un renommage manuel —
            // écrasait ce nom par un « Parcours du … à … » recalculé.
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
                isRecording = false
            )
            trackDao.updateTrack(updated)
        }
        // La trace est figée : ce qui en sera calculé à partir de maintenant peut être
        // mis en cache, mais rien de ce qui précède ne doit y subsister.
        invalidatePointCaches(trackId)
        _currentTrackId.value = null
        _isTracking.value = false
        _isPaused.value = false
        _livePoints.value = emptyList()
        _liveStats.value = LiveStats()
    }

    // ------------------------------------------------------------------
    // Affichage par fenêtre de vue
    //
    // Une trace importée peut compter des millions de points : les charger tous
    // pour dessiner la carte saturerait la mémoire. On combine donc une silhouette
    // grossière de la trace entière (pour que le tracé reste continu hors écran)
    // avec le détail de la zone visible. Zoom suffisant = tous les points réels
    // de la zone sont dessinés. La base, elle, conserve toujours 100 % des points.
    // ------------------------------------------------------------------

    /** En deçà de ce nombre de points, la trace est chargée entièrement, comme avant. */
    private val fullLoadLimit = 60_000

    /** Nombre de points visé pour la silhouette de la trace entière. */
    private val skeletonBudget = 4_000

    /** Nombre de points visé pour le détail de la zone visible. */
    private val detailBudget = 40_000

    /**
     * Au-delà de cette fraction de l'emprise de la trace couverte par la vue, on se
     * contente de la silhouette : la vue embrasse quasiment tout, le détail n'apporterait
     * rien et coûterait un parcours complet de la trace.
     */
    private val skeletonOnlyCoverage = 0.55

    private val pointMetaCache = java.util.concurrent.ConcurrentHashMap<Long, TrackPointMeta>()
    private val skeletonCache = java.util.concurrent.ConcurrentHashMap<Long, List<TrackPoint>>()

    /**
     * Points complets des traces sous [fullLoadLimit], gardés d'un appel à l'autre.
     *
     * Ces traces-là sont renvoyées en entier quelle que soit la zone visible : le
     * résultat ne dépend pas du tout du cadrage, et le relire était donc du travail
     * intégralement perdu. En suivi automatique, où la zone se republie au fil du
     * déplacement, cela revenait à redemander à SQLite jusqu'à soixante mille lignes
     * par trace affichée et à en reconstruire autant d'objets — de quoi occuper
     * plusieurs secondes avant que le moindre point nouveau n'apparaisse.
     *
     * Le cache ne coûte pas de mémoire supplémentaire, au contraire : ces listes
     * étaient déjà retenues par le flux d'affichage, et l'on en fabriquait une copie
     * neuve à chaque tour pendant que la précédente vivait encore. Renvoyer la même
     * instance rend en prime la comparaison de la carte immédiate, là où elle
     * repassait sur chaque point pour conclure que rien n'avait changé.
     */
    private val fullPointsCache = java.util.concurrent.ConcurrentHashMap<Long, List<TrackPoint>>()

    fun invalidatePointCaches(trackId: Long) {
        pointMetaCache.remove(trackId)
        skeletonCache.remove(trackId)
        fullPointsCache.remove(trackId)
    }

    /**
     * Points à dessiner pour [trackId] compte tenu de la zone visible [viewport].
     *
     * Ne renvoie jamais plus de ~[skeletonBudget] + [detailBudget] points, quelle que
     * soit la taille de la trace. Pour les traces courantes (< [fullLoadLimit] points),
     * renvoie simplement tous les points, exactement comme auparavant.
     */
    suspend fun getDisplayPoints(trackId: Long, viewport: MapViewport?): List<TrackPoint> {
        // Une trace en cours d'enregistrement grossit à chaque seconde : son effectif
        // et son emprise seraient déjà faux à la lecture suivante. Les mettre en cache
        // figeait l'affichage sur ce qu'elle contenait à la première consultation — la
        // suite du tracé n'apparaissait plus tant qu'on ne quittait pas l'application.
        // Le cache ne vaut que pour les traces figées, seules assez denses d'ailleurs
        // pour que le calcul coûte quelque chose.
        val isLive = _isTracking.value && _currentTrackId.value == trackId

        val meta = (if (isLive) null else pointMetaCache[trackId])
            ?: trackDao.getTrackPointMeta(trackId)?.also { if (!isLive) pointMetaCache[trackId] = it }
            ?: return emptyList()

        val minId = meta.minId
        if (meta.pointCount == 0 || minId == null) return emptyList()

        if (meta.pointCount <= fullLoadLimit) {
            // Indépendant de [viewport] : d'où la mise en cache, voir [fullPointsCache].
            return (if (isLive) null else fullPointsCache[trackId])
                ?: trackDao.getPointsForTrack(trackId)
                    .also { if (!isLive) fullPointsCache[trackId] = it }
        }

        val globalStride = ceilDiv(meta.pointCount.toLong(), skeletonBudget.toLong())
        val skeleton = (if (isLive) null else skeletonCache[trackId])
            ?: trackDao.getSkeletonPoints(trackId, minId, globalStride)
                .also { if (!isLive) skeletonCache[trackId] = it }

        if (viewport == null) return skeleton

        // Vue quasi globale : la silhouette suffit, et on évite un parcours complet.
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
     * Parcourt la totalité des points d'une trace, page par page.
     *
     * Utilisé par l'export : garantit que le fichier produit contient 100 % des
     * points, indépendamment de ce qui est affiché à l'écran, sans jamais tenir
     * la trace entière en mémoire.
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
        // Trace ponctuelle : pas d'emprise exploitable, on laisse le détail décider.
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
     * Fusionne deux sous-ensembles ordonnés par id de la même trace, sans doublon.
     * Le résultat reste une sous-suite chronologique valide : le tracé est continu,
     * grossier hors écran et fin dans la zone visible.
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
     * Importe un GPX/KML en flux : les points sont écrits en base au fur et à mesure
     * du parsing, sans jamais charger le fichier entier ni la liste complète des
     * points en mémoire. La ligne du parcours est insérée d'abord (pour obtenir son
     * id), puis mise à jour avec les statistiques une fois le parsing terminé.
     *
     * Le tout dans une seule transaction : en cas d'échec, rien n'est conservé.
     */
    suspend fun importTrackFromUri(context: Context, uri: android.net.Uri): Long =
        withContext(Dispatchers.IO) {
            database.runInTransaction(
                java.util.concurrent.Callable {
                    val placeholder = Track(
                        name = "Import en cours",
                        startTime = 0L,
                        isRecording = false,
                        isImported = true,
                        isSelectedForMap = false
                    )
                    val trackId = trackDao.insertTrackBlocking(placeholder)

                    val summary = Importer.importFromUri(context, uri, trackId) { batch ->
                        trackDao.insertTrackPointsBlocking(batch)
                    } ?: throw Importer.ImportException(
                        "Impossible de lire ou de parser le fichier. Vérifiez le format GPX/KML."
                    )

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
                }
            )
        }

    /**
     * Fusionne [trackIds] dans [destinationTrackId], par ordre chronologique.
     *
     * La recopie des points est faite par SQLite lui-même (INSERT … SELECT) : aucun
     * point ne transite par la mémoire de l'application, ce qui permet de fusionner
     * des parcours de plusieurs millions de points.
     *
     * Les points de la destination sont eux aussi recopiés, pour qu'ils se placent à
     * leur rang chronologique parmi les autres, puis leurs lignes d'origine sont
     * supprimées. Tout se déroule dans une transaction unique : en cas d'échec,
     * aucune trace n'est perdue.
     */
    suspend fun mergeAndSaveTracks(
        trackIds: List<Long>,
        destinationTrackId: Long,
        mergedName: String
    ): Long {
        return database.withTransaction {
            val tracks = trackIds.map { trackId ->
                trackDao.getTrackById(trackId) ?: throw IllegalArgumentException("Tracé $trackId introuvable")
            }
            if (tracks.size < 2) {
                throw IllegalArgumentException("Veuillez sélectionner au moins 2 tracés à fusionner")
            }

            val destinationTrack = tracks.find { it.id == destinationTrackId }
                ?: throw IllegalArgumentException("Tracé de destination introuvable")

            val orderedTracks = tracks.sortedBy { it.startTime }

            // Borne des points déjà présents dans la destination. Elle sépare
            // l'ancien du nouveau pendant toute l'opération.
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
                // isImported n'est pas recalculé : la provenance du parcours d'accueil
                // reste vraie. Mais le résultat porte désormais sa propre catégorie —
                // l'historique a un onglet « Fusionnés » — et c'est isMerged qui l'y
                // range, en le retirant de sa catégorie d'origine.
                isMerged = true
            )

            trackDao.updateTrack(updatedDestinationTrack)

            // Recopie parcours par parcours, dans l'ordre chronologique.
            var hasCopiedSomething = false
            for (track in orderedTracks) {
                val sourceMaxId = if (track.id == destinationTrackId) {
                    destinationOldMaxId
                } else {
                    trackDao.getMaxPointId(track.id) ?: 0L
                }
                if (sourceMaxId <= 0L) continue

                // Repère posé avant la copie : tout ce qui portera un id supérieur
                // vient d'être inséré par cette étape.
                val watermark = trackDao.getMaxPointIdGlobal() ?: 0L
                trackDao.copyPointsInto(destinationTrackId, track.id, sourceMaxId)

                if (trackDao.countPointsAfter(destinationTrackId, watermark) == 0) continue

                // Chaque parcours ajouté ouvre un nouveau tronçon : pas de trait
                // reliant la fin du précédent au début de celui-ci.
                if (hasCopiedSomething) {
                    trackDao.markFirstPointAfterAsDiscontinuous(destinationTrackId, watermark)
                }
                hasCopiedSomething = true
            }

            // Les points d'origine de la destination ont été recopiés à leur place :
            // on retire les lignes initiales.
            trackDao.deletePointsUpTo(destinationTrackId, destinationOldMaxId)

            // Supprimer les autres tracés ainsi que leurs points
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

    /**
     * Crée une copie de [sourceTrackId] débarrassée des points immobiles : tout
     * point à moins de [thresholdMeters] du dernier point conservé est écarté, sauf
     * les marqueurs de rupture de tronçon ([TrackPoint.isDiscontinuous]), toujours
     * gardés pour ne pas recoller deux tronçons distincts.
     *
     * Ne modifie jamais [sourceTrackId] : le résultat est un nouveau parcours,
     * l'original reste disponible tel quel dans l'historique.
     *
     * Le tout dans une transaction unique, comme la fusion et l'import : une
     * interruption en cours de route laissait sinon dans l'historique une copie
     * tronquée, indiscernable d'un nettoyage réussi.
     */
    suspend fun removeStationaryPoints(
        sourceTrackId: Long,
        thresholdMeters: Double,
        newName: String
    ): Long = database.withTransaction {
        val source = trackDao.getTrackById(sourceTrackId)
            ?: throw IllegalArgumentException("Parcours introuvable")

        val newTrack = source.copy(id = 0, name = newName, isRecording = false)
        val newTrackId = trackDao.insertTrack(newTrack)

        var lastKeptLat: Double? = null
        var lastKeptLon: Double? = null
        var afterId = 0L
        val pageSize = 2_000
        val distResult = FloatArray(1)

        while (true) {
            val page = trackDao.getPointsPage(sourceTrackId, afterId, pageSize)
            if (page.isEmpty()) break
            afterId = page.last().id

            val kept = page.filter { point ->
                val keep = if (point.isDiscontinuous || lastKeptLat == null) {
                    true
                } else {
                    android.location.Location.distanceBetween(
                        lastKeptLat!!, lastKeptLon!!, point.latitude, point.longitude, distResult
                    )
                    distResult[0].toDouble() >= thresholdMeters
                }
                if (keep) {
                    lastKeptLat = point.latitude
                    lastKeptLon = point.longitude
                }
                keep
            }
            if (kept.isNotEmpty()) {
                trackDao.insertTrackPoints(kept.map { it.copy(id = 0, trackId = newTrackId) })
            }
        }

        // Recalcule distance, vitesses et dénivelé à partir des points conservés :
        // sans ça, le nettoyage laisserait les statistiques gonflées par le bruit
        // qu'il vient pourtant de retirer. Au-delà de statsRecomputeLimit points, on
        // laisse les statistiques de la trace d'origine — même compromis que
        // loadResumeState pour ne jamais charger une trace entière de plusieurs
        // millions de points en mémoire.
        val keptPointCount = trackDao.getTrackPointMeta(newTrackId)?.pointCount ?: 0
        if (keptPointCount in 1..statsRecomputeLimit) {
            val points = trackDao.getPointsForTrack(newTrackId)
            val stats = calculateStatsFromPoints(points)
            trackDao.updateTrack(
                newTrack.copy(
                    id = newTrackId,
                    totalDistance = stats.distanceMeters,
                    duration = source.duration,
                    maxSpeed = stats.maxSpeedMps,
                    avgSpeed = stats.avgSpeedMps,
                    elevationGain = stats.elevationGain,
                    elevationLoss = stats.elevationLoss
                )
            )
        }

        newTrackId
    }

    fun getSelectedImportedTracksFlow(): Flow<List<Track>> {
        return trackDao.getSelectedImportedTracksFlow()
    }

    suspend fun updateTrack(track: Track) {
        trackDao.updateTrack(track)
    }

    /**
     * Donne à chaque parcours existant la couleur qu'il affichait avant le passage aux
     * couleurs par parcours, puis note que c'est fait.
     *
     * Appelée à chaque démarrage mais ne travaille qu'une fois : sans cette reprise,
     * tous les parcours d'un utilisateur ayant réglé ses couleurs par catégorie
     * changeraient d'apparence à la mise à jour, sans qu'il ait rien demandé.
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
        // Rien à invalider : les caches ne retiennent que des points, et Room réémet
        // de lui-même les flux de parcours après cet UPDATE.
    }

    suspend fun insertPoint(point: TrackPoint) {
        if (point.trackId <= 0) return
        try {
            // Pas de vérification préalable de l'existence du parcours : c'était une
            // requête supplémentaire à chaque point GPS, soit une par seconde en
            // enregistrement. La contrainte de clé étrangère joue déjà ce rôle et
            // l'échec éventuel est rattrapé ici.
            trackDao.insertTrackPoint(point)
            // Add to live list to refresh UI instantly
            _livePoints.value = _livePoints.value + point
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteTrack(trackId: Long) {
        trackDao.deletePointsForTrack(trackId)
        trackDao.deleteTrack(trackId)
        invalidatePointCaches(trackId)
        if (_currentTrackId.value == trackId) {
            _currentTrackId.value = null
            _isTracking.value = false
            _livePoints.value = emptyList()
            _liveStats.value = LiveStats()
        }
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

    private val _gpsStatus = MutableStateFlow("Recherche de signal...")
    val gpsStatus: StateFlow<String> = _gpsStatus.asStateFlow()

    private val _gpsAccuracy = MutableStateFlow<Float?>(null)
    val gpsAccuracy: StateFlow<Float?> = _gpsAccuracy.asStateFlow()

    private val _currentUserLocation = MutableStateFlow<TrackPoint?>(null)
    val currentUserLocation: StateFlow<TrackPoint?> = _currentUserLocation.asStateFlow()

    /**
     * Altitude courante au-dessus du niveau de la mer, ou null si aucune mesure
     * exploitable n'a été obtenue.
     *
     * Publiée à part de [currentUserLocation] : `TrackPoint.altitude` n'est pas
     * nullable et ne peut donc pas distinguer « altitude inconnue » de « altitude
     * nulle », alors que l'affichage doit montrer « — » dans le premier cas plutôt
     * qu'un « 0 m » qui se lirait comme une mesure.
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

    fun updateGpsStatus(status: String) {
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
     * Retrouve un enregistrement laissé ouvert et remet l'état en direct à sa hauteur.
     *
     * @param markPaused Faut-il présenter la trace retrouvée comme mise en pause ?
     * Vrai au lancement de l'application, qui découvre une trace sans que rien ne
     * l'alimente. Faux quand c'est le service qui appelle pour reprendre tout de
     * suite : marquer une pause qu'il vient justement de lever affichait « en pause »
     * à l'écran alors que les positions s'enregistraient bel et bien.
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

    fun calculateStatsFromPoints(points: List<TrackPoint>): LiveStats {
        if (points.isEmpty()) return LiveStats()
        var totalDistanceMeters = 0.0
        var maxSpeedMps = 0.0
        var speedSumMps = 0.0
        var totalSpeedPoints = 0

        // Même algorithme de dénivelé qu'à l'enregistrement, pour que reprendre une
        // trace ne change pas ses totaux.
        val elevation = com.example.util.ElevationAccumulator()
        points.firstOrNull()?.let { elevation.add(it.altitude) }

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            if (curr.isDiscontinuous) {
                elevation.breakSegment()
            } else {
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude,
                    dist
                )
                val d = dist[0].toDouble()
                if (d > 1.0) {
                    totalDistanceMeters += d
                }
            }
            elevation.add(curr.altitude)

            val speed = curr.speed.toDouble()
            if (speed > maxSpeedMps) maxSpeedMps = speed
            if (speed > 0.1) {
                speedSumMps += speed
                totalSpeedPoints++
            }
        }
        val elevationGainMeters = elevation.gainMeters
        val elevationLossMeters = elevation.lossMeters

        val elapsedSeconds = if (points.size >= 2) {
            (points.last().timestamp - points.first().timestamp) / 1000L
        } else 0L

        val avgSpeed = if (totalSpeedPoints > 0) speedSumMps / totalSpeedPoints else 0.0

        return LiveStats(
            durationSec = maxOf(0L, elapsedSeconds),
            distanceMeters = totalDistanceMeters,
            currentSpeedMps = points.lastOrNull()?.speed?.toDouble() ?: 0.0,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = maxSpeedMps,
            elevationGain = elevationGainMeters,
            elevationLoss = elevationLossMeters
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: TrackRepository? = null

        fun getInstance(context: Context): TrackRepository {
            return INSTANCE ?: synchronized(this) {
                // Re-vérification sous le verrou. Sans elle, deux appels simultanés
                // (service GPS et interface au démarrage, par exemple) créent chacun
                // un dépôt avec ses propres StateFlow : le service alimente alors une
                // instance pendant que l'écran en observe une autre, et les points et
                // statistiques en direct n'apparaissent jamais.
                INSTANCE ?: TrackRepository(AppDatabase.getInstance(context))
                    .also { INSTANCE = it }
            }
        }

        /** Instance isolée sur une base fournie, pour les tests. */
        @androidx.annotation.VisibleForTesting
        fun createForTesting(database: AppDatabase): TrackRepository = TrackRepository(database)
    }
}
