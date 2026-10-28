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
import com.example.util.StopCollapser
import com.example.util.TrackExtender
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
        val stats: LiveStats,
        /**
         * De quoi prolonger la vitesse moyenne : somme des vitesses retenues et nombre
         * de relevés. Le service repartait de zéro sur ces deux cumuls alors que la
         * distance et la durée étaient restaurées, si bien qu'après une reprise la
         * moyenne ne portait plus que sur ce qui avait été enregistré depuis.
         */
        val speedSumMps: Double = 0.0,
        val speedSamples: Int = 0
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
        // Faute d'avoir relu les vitesses, on pondère la moyenne connue par l'effectif :
        // les points à venir ne pèsent alors que leur part, comme si on avait tout relu.
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
                isRecording = false,
                isPaused = false
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
     * **Une transaction par lot, plus une seule pour tout l'import.** Une transaction
     * unique verrouillait la base en écriture d'un bout à l'autre : importer un gros
     * KML pendant une sortie figeait l'enregistrement — points GPS, statistiques,
     * notification — jusqu'à la fin de l'import. Chaque lot ne tient plus le verrou
     * que le temps de son insertion.
     *
     * L'atomicité est rendue à la main : la ligne réservée porte un `startTime`
     * repère ([Track.IMPORT_PLACEHOLDER_START_TIME]) qui la cache de l'historique, un
     * échec la supprime avec ses points, et [cleanupInterruptedImports] rattrape au
     * lancement suivant celle d'un import tué avec l'application.
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
                // NonCancellable : une annulation doit, elle aussi, laisser la base nette.
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
     * Supprime les lignes d'imports interrompus par l'arrêt de l'application. À
     * n'appeler qu'au démarrage du processus : aucun import ne peut alors être en
     * cours, puisqu'ils ne survivent pas au processus qui les a lancés.
     */
    fun cleanupInterruptedImports() {
        database.runInTransaction {
            trackDao.deleteImportPlaceholderPointsBlocking()
            trackDao.deleteImportPlaceholdersBlocking()
        }
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
                trackDao.getTrackById(trackId) ?: throw UserFacingArgumentException(R.string.err_track_id_not_found, listOf(trackId))
            }
            if (tracks.size < 2) {
                throw UserFacingArgumentException(R.string.err_merge_min_two)
            }
            // Fusionner le parcours en cours d'enregistrement supprimait sa ligne sous le
            // service, qui continuait d'y écrire : chaque point suivant était refusé par
            // la clé étrangère, en silence, et tout le reste de la sortie était perdu.
            if (tracks.any { isBeingRecorded(it) }) {
                throw UserFacingArgumentException(R.string.err_merge_one_recording)
            }

            val destinationTrack = tracks.find { it.id == destinationTrackId }
                ?: throw UserFacingArgumentException(R.string.err_merge_destination_missing)

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

    // ------------------------------------------------------------------
    // Découpage d'un parcours
    // ------------------------------------------------------------------

    /** Ce qui décide de l'endroit où le parcours est coupé. */
    enum class SplitMode {
        /**
         * Aux ruptures de tronçon déjà présentes dans le parcours. C'est ce que
         * portent les fichiers importés : un export Google Earth réunit couramment
         * des dizaines de voyages, chacun dans son propre `<coordinates>`.
         */
        SEGMENT_BREAKS,

        /**
         * Là où le temps s'est arrêté plus longtemps qu'un seuil choisi. Utile sur un
         * enregistrement réel coupé par une nuit ou une longue pause.
         *
         * Sans effet sur un GPX sans balise `<time>` ou sur un KML, dont les points
         * reçoivent un horodatage synthétique régulier à l'import : il n'y a alors
         * aucun trou à trouver, et le découpage le signale au lieu d'échouer.
         */
        TIME_GAP
    }

    /** Nombre de points relus par lot pendant le repérage des coupures. */
    private val splitScanPageSize = 2_000
    private val previewGapMs = 15_000L

    /** Au-delà, la zone du sélecteur de portion est trop chargée pour être lue d'un coup. */
    private val maxPickerScan = 40_000


    /**
     * Une tranche repérée pendant la lecture, avant d'être écrite en base.
     *
     * Les bornes sont des identifiants de points, jamais des positions : c'est ce qui
     * permet à SQLite de recopier la tranche lui-même.
     */
    private data class PlannedPiece(
        val fromId: Long,
        val untilId: Long,
        val startTime: Long,
        val endTime: Long,
        val stats: LiveStats
    )

    /**
     * Découpe [sourceTrackId] en plusieurs parcours, selon [mode].
     *
     * Le parcours d'origine n'est modifié en rien, et n'est supprimé que si
     * [deleteSource] le demande — les morceaux contiennent alors exactement les mêmes
     * points, rien n'est perdu.
     *
     * Deux temps, et c'est délibéré :
     *
     * 1. **Une seule lecture** du parcours, page par page, qui repère les coupures et
     *    cumule au passage les statistiques de chaque morceau
     *    ([TrackStatsAccumulator]). Rien d'autre que la page courante n'est en
     *    mémoire : un parcours de plusieurs millions de points passe. Cette lecture
     *    se fait **hors transaction**, pour ne pas tenir la base en écriture pendant
     *    toute sa durée.
     * 2. **Une transaction unique** qui crée les parcours et fait recopier les points
     *    par SQLite (`INSERT … SELECT`, invariant 5). Une interruption ne laisse donc
     *    jamais un découpage à moitié fait.
     *
     * Lève [IllegalArgumentException] si le parcours est introuvable ou en cours
     * d'enregistrement, et [IllegalStateException] s'il n'y a rien à découper.
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

        // --- 1. Repérage des coupures, en une passe ---

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
                    // Tout premier point du parcours : il ouvre la première tranche.
                    pieceFromId = point.id
                    pieceStartTime = point.timestamp
                } else {
                    val startsNewPiece = when (mode) {
                        SplitMode.SEGMENT_BREAKS -> point.isDiscontinuous
                        SplitMode.TIME_GAP -> point.timestamp - prev.timestamp >= gapMillis
                    }
                    if (startsNewPiece) {
                        // La tranche courante s'arrête juste avant ce point : sa borne
                        // haute est exclusive, donc c'est l'identifiant de ce point.
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

        // Dernière tranche : elle va jusqu'au bout, d'où la borne haute maximale.
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

        // --- 2. Écriture ---

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
                        // La provenance ne change pas : un morceau de parcours importé
                        // reste importé, et reste rangé dans l'onglet où l'on avait
                        // l'habitude de trouver son parent.
                        isImported = source.isImported,
                        isMerged = source.isMerged,
                        // Jamais affiché d'office : un découpage en trente morceaux
                        // les allumerait tous d'un coup sur la carte.
                        isSelectedForMap = false,
                        // Les morceaux gardent l'apparence du parent, y compris la
                        // couleur choisie à la main s'il en avait une.
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
    // Retrait d'une portion choisie sur la carte
    // ------------------------------------------------------------------

    /**
     * Un aperçu de [trackId] pour le choix d'une portion à la main : au plus
     * [maxPoints] points, pris à pas régulier, plus le dernier et toutes les ruptures de
     * tronçon. Lu page par page, comme le reste : un parcours de plusieurs millions de
     * points passe. Ses identifiants sont ceux de la base, ce qui permet de désigner les
     * bornes de la portion sans relire le parcours.
     */
    suspend fun getPreviewPoints(trackId: Long, maxPoints: Int = 3_000): List<TrackPoint> {
        val total = trackDao.getTrackPointMeta(trackId)?.pointCount ?: 0
        if (total == 0) return emptyList()
        val stride = maxOf(1, (total + maxPoints - 1) / maxPoints)
        val preview = ArrayList<TrackPoint>(minOf(total, maxPoints + 1))
        var index = 0
        var last: TrackPoint? = null
        var afterId = 0L
        while (true) {
            val page = trackDao.getPointsPage(trackId, afterId, splitScanPageSize)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (point in page) {
                // Un trou de plus de quinze secondes coupe le trait à l'affichage
                // (`buildSegmentsFromPoints`), mais l'échantillonnage en cache la preuve :
                // on garde les deux bords du trou, le second marqué comme rupture, sans quoi
                // l'aperçu tirerait un trait entre deux points que rien ne relie.
                val previous = last
                val gap = previous != null && !point.isDiscontinuous &&
                    previous.timestamp > 0L && point.timestamp > 0L &&
                    point.timestamp - previous.timestamp > previewGapMs
                if (gap) {
                    if (preview.lastOrNull()?.id != previous!!.id) preview += previous
                    preview += point.copy(isDiscontinuous = true)
                } else if (index % stride == 0 || point.isDiscontinuous) {
                    preview += point
                }
                last = point
                index++
            }
        }
        last?.let { if (preview.lastOrNull()?.id != it.id) preview += it }
        return preview
    }

    /**
     * Le détail de [trackId] dans la zone donnée, pour la carte du choix d'une portion :
     * zoomée sur un virage, elle doit montrer ses points, pas la silhouette de tout le
     * parcours — c'est ce qui faisait « couper » les virages en lignes droites.
     *
     * Au plus [maxPoints] points, à pas régulier ; les points qui suivent une sortie de
     * zone, un trou de temps ou une rupture ouvrent un tronçon, et ceux qui les précèdent
     * sont gardés, pour qu'aucun trait ne relie deux points que rien ne relie. Tant que la
     * zone contient trop de points pour être lue d'un coup ([maxPickerScan]), on retombe
     * sur l'aperçu du parcours entier.
     */
    suspend fun getPickerPoints(
        trackId: Long,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        maxPoints: Int = 4_000
    ): List<TrackPoint> {
        val inside = trackDao.countPointsInBounds(trackId, minLat, maxLat, minLon, maxLon)
        if (inside == 0) return emptyList()
        if (inside > maxPickerScan) return getPreviewPoints(trackId)
        val raw = trackDao.getDetailPointsInBounds(trackId, 0L, 1L, minLat, maxLat, minLon, maxLon)
        val marked = ArrayList<TrackPoint>(raw.size)
        for ((i, point) in raw.withIndex()) {
            val previous = raw.getOrNull(i - 1)
            val broken = previous != null && !point.isDiscontinuous && (
                point.id != previous.id + 1 ||
                    (previous.timestamp > 0L && point.timestamp > 0L &&
                        point.timestamp - previous.timestamp > previewGapMs)
                )
            marked += if (broken) point.copy(isDiscontinuous = true) else point
        }
        if (marked.size <= maxPoints) return marked
        val stride = (marked.size + maxPoints - 1) / maxPoints
        val sampled = ArrayList<TrackPoint>(maxPoints + 16)
        for ((i, point) in marked.withIndex()) {
            val startsRun = point.isDiscontinuous
            val endsRun = marked.getOrNull(i + 1)?.isDiscontinuous == true
            if (i % stride == 0 || startsRun || endsRun || i == marked.lastIndex) sampled += point
        }
        return sampled
    }

    /** Distance parcourue entre deux points d'un parcours, ruptures de tronçon exclues. */
    suspend fun measurePortion(trackId: Long, fromPointId: Long, toPointId: Long): Double {
        val low = minOf(fromPointId, toPointId)
        val high = maxOf(fromPointId, toPointId)
        var total = 0.0
        var previous: TrackPoint? = null
        var afterId = low - 1
        val result = FloatArray(1)
        while (true) {
            val page = trackDao.getPointsPage(trackId, afterId, splitScanPageSize)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (point in page) {
                if (point.id > high) return total
                val last = previous
                if (last != null && !point.isDiscontinuous) {
                    android.location.Location.distanceBetween(
                        last.latitude, last.longitude, point.latitude, point.longitude, result
                    )
                    total += result[0]
                }
                previous = point
            }
        }
        return total
    }

    /**
     * Retire de [sourceTrackId] les points d'identifiant compris entre [fromPointId] et
     * [toPointId] inclus, choisis sur la carte, et crée les morceaux qui restent : celui
     * d'avant et celui d'après, chacun s'il compte au moins deux points.
     *
     * C'est un découpage : même lecture unique page par page, mêmes statistiques
     * cumulées au passage, même transaction finale où SQLite recopie chaque tranche
     * (`copyPointRangeInto`, invariant 5). Le parcours d'origine n'est jamais modifié,
     * et n'est supprimé que si [deleteSource] le demande.
     */
    suspend fun cutPortion(
        sourceTrackId: Long,
        fromPointId: Long,
        toPointId: Long,
        baseName: String,
        deleteSource: Boolean
    ): List<Long> {
        val source = trackDao.getTrackById(sourceTrackId)
            ?: throw UserFacingArgumentException(R.string.err_track_not_found)
        if (isBeingRecorded(source)) {
            throw UserFacingArgumentException(R.string.err_track_is_recording)
        }
        val low = minOf(fromPointId, toPointId)
        val high = maxOf(fromPointId, toPointId)

        // Un seul parcours en sortie : ce qui précède la portion, puis ce qui la suit. Le
        // premier point de la suite ouvre un nouveau tronçon — rien n'est tracé ni compté
        // à travers le trou, la distance à vol d'oiseau n'a jamais été parcourue.
        val stats = TrackStatsAccumulator()
        var keptCount = 0
        var startTime = 0L
        var endTime = 0L
        var beforeFirstId: Long? = null
        var afterFirstId: Long? = null
        var afterId = 0L
        while (true) {
            val page = trackDao.getPointsPage(sourceTrackId, afterId, splitScanPageSize)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (point in page) {
                if (point.id in low..high) continue
                var counted = point
                if (point.id < low) {
                    if (beforeFirstId == null) beforeFirstId = point.id
                } else if (afterFirstId == null) {
                    afterFirstId = point.id
                    if (beforeFirstId != null) counted = point.copy(isDiscontinuous = true)
                }
                if (keptCount == 0) startTime = point.timestamp
                endTime = point.timestamp
                stats.add(counted)
                keptCount++
            }
        }
        if (keptCount < 2) throw UserFacingStateException(R.string.err_cut_everything)

        return database.withTransaction {
            val result = stats.result()
            val newTrackId = trackDao.insertTrack(
                Track(
                    name = baseName,
                    activityType = source.activityType,
                    startTime = startTime,
                    endTime = endTime,
                    totalDistance = result.distanceMeters,
                    duration = result.durationSec,
                    maxSpeed = result.maxSpeedMps,
                    avgSpeed = result.avgSpeedMps,
                    elevationGain = result.elevationGain,
                    elevationLoss = result.elevationLoss,
                    isRecording = false,
                    // Même héritage que le découpage : provenance et apparence, mais
                    // jamais l'affichage sur la carte.
                    isImported = source.isImported,
                    isMerged = source.isMerged,
                    isSelectedForMap = false,
                    sourceColor = source.sourceColor,
                    displayColor = source.displayColor,
                    displayWidthDp = source.displayWidthDp
                )
            )
            beforeFirstId?.let {
                trackDao.copyPointRangeInto(
                    destinationId = newTrackId,
                    sourceId = sourceTrackId,
                    fromId = it,
                    untilId = low
                )
            }
            val lastBeforeId = trackDao.getMaxPointId(newTrackId)
            afterFirstId?.let {
                trackDao.copyPointRangeInto(
                    destinationId = newTrackId,
                    sourceId = sourceTrackId,
                    fromId = it,
                    untilId = Long.MAX_VALUE
                )
            }
            // Le premier point de la suite ouvre un tronçon ; celui du tout début du
            // parcours, lui, n'a plus à en ouvrir un (voir le découpage).
            if (lastBeforeId != null) trackDao.markFirstPointAfterDiscontinuous(newTrackId, lastBeforeId)
            trackDao.clearFirstPointDiscontinuity(newTrackId)
            if (deleteSource) {
                trackDao.deletePointsForTrack(sourceTrackId)
                trackDao.deleteTrack(sourceTrackId)
            }
            invalidatePointCaches(sourceTrackId)
            listOf(newTrackId)
        }
    }

    // ------------------------------------------------------------------
    // Prolongement dessiné à la main
    // ------------------------------------------------------------------

    /** Le point par lequel [trackId] se prolonge : son dernier, ou son premier. */
    suspend fun getEndPoint(trackId: Long, atEnd: Boolean): TrackPoint? =
        if (atEnd) trackDao.getLastPoint(trackId) else trackDao.getFirstPoint(trackId)

    /**
     * Crée une copie de [sourceTrackId] prolongée par un tracé dessiné sur la carte :
     * [taps] (latitude, longitude), à partir de son dernier point avec [atEnd], de son
     * premier sinon. Les points du prolongement viennent de [TrackExtender], à
     * l'allure moyenne du parcours.
     *
     * Le prolongement est raccordé sans rupture : c'est tout son intérêt. Prolongé par
     * le début, l'ancien premier point — qui ouvrait le tracé, donc portait la marque
     * de rupture — la perd, sans quoi le trait s'interromprait au raccord.
     *
     * Même écriture que le découpage : une lecture page par page pour les statistiques,
     * puis une transaction unique où SQLite recopie les points d'origine
     * (`copyPointRangeInto`, invariant 5) à côté des points fabriqués. Le parcours
     * d'origine n'est supprimé que si [deleteSource] le demande.
     */
    suspend fun extendTrack(
        sourceTrackId: Long,
        taps: List<Pair<Double, Double>>,
        atEnd: Boolean,
        newName: String,
        deleteSource: Boolean
    ): Long {
        val source = trackDao.getTrackById(sourceTrackId)
            ?: throw UserFacingArgumentException(R.string.err_track_not_found)
        if (isBeingRecorded(source)) {
            throw UserFacingArgumentException(R.string.err_track_is_recording)
        }
        if (taps.isEmpty()) throw UserFacingArgumentException(R.string.err_extend_empty)
        val anchor = getEndPoint(sourceTrackId, atEnd)
            ?: throw UserFacingStateException(R.string.err_extend_empty_track)
        val added = TrackExtender.build(
            anchor = anchor,
            taps = taps,
            speedMps = TrackExtender.speedFor(source.avgSpeed),
            forward = atEnd,
            trackId = 0L
        )

        val stats = TrackStatsAccumulator()
        if (!atEnd) added.forEach(stats::add)
        var first = true
        var afterId = 0L
        while (true) {
            val page = trackDao.getPointsPage(sourceTrackId, afterId, splitScanPageSize)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (point in page) {
                stats.add(if (first && !atEnd) point.copy(isDiscontinuous = false) else point)
                first = false
            }
        }
        if (atEnd) added.forEach(stats::add)
        val result = stats.result()
        val startTime = if (atEnd) source.startTime else added.first().timestamp
        val endTime = if (atEnd) added.last().timestamp else maxOf(source.endTime, source.startTime)

        return database.withTransaction {
            val newTrackId = trackDao.insertTrack(
                Track(
                    name = newName,
                    activityType = source.activityType,
                    startTime = startTime,
                    endTime = endTime,
                    totalDistance = result.distanceMeters,
                    duration = result.durationSec,
                    maxSpeed = result.maxSpeedMps,
                    avgSpeed = result.avgSpeedMps,
                    elevationGain = result.elevationGain,
                    elevationLoss = result.elevationLoss,
                    isRecording = false,
                    // Même héritage que le découpage : provenance et apparence, mais
                    // jamais l'affichage sur la carte.
                    isImported = source.isImported,
                    isMerged = source.isMerged,
                    isSelectedForMap = false,
                    sourceColor = source.sourceColor,
                    displayColor = source.displayColor,
                    displayWidthDp = source.displayWidthDp
                )
            )
            val fabricated = added.map { it.copy(trackId = newTrackId) }
            if (atEnd) {
                trackDao.copyPointRangeInto(newTrackId, sourceTrackId, 0L, Long.MAX_VALUE)
                trackDao.insertTrackPoints(fabricated)
            } else {
                trackDao.insertTrackPoints(fabricated)
                val lastAddedId = trackDao.getMaxPointId(newTrackId) ?: 0L
                trackDao.copyPointRangeInto(newTrackId, sourceTrackId, 0L, Long.MAX_VALUE)
                trackDao.clearFirstPointAfterDiscontinuity(newTrackId, lastAddedId)
            }
            trackDao.clearFirstPointDiscontinuity(newTrackId)
            if (deleteSource) {
                trackDao.deletePointsForTrack(sourceTrackId)
                trackDao.deleteTrack(sourceTrackId)
            }
            invalidatePointCaches(sourceTrackId)
            newTrackId
        }
    }

    // ------------------------------------------------------------------
    // Nettoyage : chaque arrêt réduit à un point
    // ------------------------------------------------------------------

    /** Résultat de [removeStationaryPoints] : le parcours créé et ce qui a été écarté. */
    data class StationaryCleanup(
        val newTrackId: Long,
        val removedPoints: Int,
        val keptPoints: Int,
        val stops: Int
    )

    /**
     * Crée une copie de [sourceTrackId] où chaque arrêt d'au moins [minStopSeconds]
     * secondes — un gribouillis de bruit GPS au même endroit — est réduit à un point,
     * sans rupture de tracé. Hors des arrêts, tous les points restent : le tracé garde
     * sa forme. La détection, et l'histoire de ses deux premières versions ratées, sont
     * dans [StopCollapser].
     *
     * Contrairement au découpage, les points gardés ne forment pas une tranche : SQLite
     * ne peut donc pas les recopier d'un seul `INSERT … SELECT`. Ils passent par la
     * mémoire, une page à la fois, et sont écrits au fur et à mesure. Chaque page est
     * une écriture courte, comme à l'import : une transaction unique autour de toute la
     * copie verrouillerait la base en écriture, et un enregistrement en cours avec
     * elle. L'atomicité est rétablie de la même façon : la nouvelle ligne porte
     * [Track.IMPORT_PLACEHOLDER_START_TIME] jusqu'à la fin, ce qui la cache de
     * l'historique, un échec la supprime, et [cleanupInterruptedImports] rattrape celle
     * d'un processus tué en route.
     *
     * Le parcours d'origine n'est jamais modifié, et n'est supprimé que si
     * [deleteSource] le demande. Lève une exception si le parcours est introuvable ou
     * en cours d'enregistrement, s'il n'a aucun arrêt, ou s'il resterait moins de
     * deux points.
     */
    suspend fun removeStationaryPoints(
        sourceTrackId: Long,
        minStopSeconds: Int,
        newName: String,
        deleteSource: Boolean
    ): StationaryCleanup {
        val source = trackDao.getTrackById(sourceTrackId)
            ?: throw UserFacingArgumentException(R.string.err_track_not_found)
        if (isBeingRecorded(source)) {
            throw UserFacingArgumentException(R.string.err_track_is_recording)
        }

        val newTrackId = trackDao.insertTrack(
            Track(
                name = newName,
                activityType = source.activityType,
                startTime = Track.IMPORT_PLACEHOLDER_START_TIME,
                isRecording = false,
                // Même héritage que le découpage : la provenance et
                // l'apparence du parent, mais jamais son affichage sur la carte.
                isImported = source.isImported,
                isMerged = source.isMerged,
                isSelectedForMap = false,
                sourceColor = source.sourceColor,
                displayColor = source.displayColor,
                displayWidthDp = source.displayWidthDp
            )
        )

        try {
            val accumulator = TrackStatsAccumulator()
            var firstKept: TrackPoint? = null
            var lastKept: TrackPoint? = null
            var keptCount = 0
            var readCount = 0
            val batch = ArrayList<TrackPoint>(splitScanPageSize)

            val collapser = StopCollapser(minDurationMs = minStopSeconds * 1_000L) { point ->
                if (firstKept == null) firstKept = point
                accumulator.add(point)
                batch += point.copy(id = 0, trackId = newTrackId)
                lastKept = point
                keptCount++
            }

            var afterId = 0L
            while (true) {
                val page = trackDao.getPointsPage(sourceTrackId, afterId, splitScanPageSize)
                if (page.isEmpty()) break
                afterId = page.last().id
                readCount += page.size
                page.forEach(collapser::add)
                if (batch.size >= splitScanPageSize) {
                    trackDao.insertTrackPoints(batch)
                    batch.clear()
                }
            }
            collapser.finish()
            if (batch.isNotEmpty()) trackDao.insertTrackPoints(batch)

            if (readCount == 0) throw UserFacingStateException(R.string.err_track_empty)
            if (collapser.stopCount == 0) throw UserFacingStateException(R.string.err_clean_none)
            if (keptCount < 2) throw UserFacingStateException(R.string.err_clean_too_few)
            val firstKeptTime = firstKept!!.timestamp

            val stats = accumulator.result()
            database.withTransaction {
                val placeholder = trackDao.getTrackById(newTrackId)
                    ?: throw UserFacingStateException(R.string.err_track_not_found)
                trackDao.updateTrack(
                    placeholder.copy(
                        startTime = firstKeptTime,
                        endTime = lastKept?.timestamp ?: firstKeptTime,
                        totalDistance = stats.distanceMeters,
                        duration = stats.durationSec,
                        maxSpeed = stats.maxSpeedMps,
                        avgSpeed = stats.avgSpeedMps,
                        elevationGain = stats.elevationGain,
                        elevationLoss = stats.elevationLoss
                    )
                )
                // Le premier point gardé ouvrait peut-être un tronçon dans le parcours
                // d'origine ; dans le nouveau, il ne fait qu'ouvrir le tracé.
                trackDao.clearFirstPointDiscontinuity(newTrackId)

                if (deleteSource) {
                    trackDao.deletePointsForTrack(sourceTrackId)
                    trackDao.deleteTrack(sourceTrackId)
                }
            }
            invalidatePointCaches(sourceTrackId)

            return StationaryCleanup(newTrackId, readCount - keptCount, keptCount, collapser.stopCount)
        } catch (e: Throwable) {
            // Même en cas d'annulation : la ligne réservée ne doit pas rester, cachée.
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    database.withTransaction {
                        trackDao.deletePointsForTrack(newTrackId)
                        trackDao.deleteTrack(newTrackId)
                    }
                }
            }
            throw e
        }
    }

    fun getSelectedImportedTracksFlow(): Flow<List<Track>> {
        return trackDao.getSelectedImportedTracksFlow()
    }

    suspend fun updateTrack(track: Track) {
        trackDao.updateTrack(track)
    }

    suspend fun setShownOnMap(trackIds: List<Long>, shown: Boolean) {
        // SQLite borne le nombre de paramètres d'une requête : par paquets.
        trackIds.chunked(500).forEach { trackDao.setShownOnMap(it, shown) }
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
            // Ajouté à la liste en direct, pour que l'écran suive immédiatement
            _livePoints.value = _livePoints.value + point
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Supprime un parcours, sauf s'il est en cours d'enregistrement : renvoie alors
     * false sans rien toucher.
     *
     * Le supprimer sous le service le laissait tourner à vide — GPS allumé,
     * notification affichée, chaque point refusé par la clé étrangère — pendant que
     * l'écran, lui, se croyait à l'arrêt et n'offrait plus de quoi l'interrompre.
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

    /** Vrai pour le parcours qu'un enregistrement alimente, ou laissé ouvert en base. */
    fun isBeingRecorded(track: Track): Boolean =
        track.isRecording || (_isTracking.value && _currentTrackId.value == track.id)

    /** Mémorise la pause en base, pour qu'elle survive à l'arrêt du processus. */
    suspend fun setTrackPaused(trackId: Long, paused: Boolean) {
        trackDao.setTrackPaused(trackId, paused)
    }

    // État en mémoire, partagé entre le service et l'interface
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

    /**
     * Statistiques d'une liste de points déjà en mémoire.
     *
     * Simple enveloppe autour de [TrackStatsAccumulator], qui porte le calcul lui-même
     * et sert aussi au découpage, où les points arrivent en flux. Une seconde
     * implémentation pour les listes finirait par diverger de celle des flux.
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
