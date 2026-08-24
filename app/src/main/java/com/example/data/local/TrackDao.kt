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
    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
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
     * Écrit dans `displayColor` la couleur que chaque parcours affichait du temps des
     * couleurs par catégorie, pour que le passage aux couleurs par parcours ne change
     * rien à l'écran.
     *
     * Reproduit exactement l'ancienne résolution : couleur du fichier si le réglage
     * « garder les couleurs d'origine » était actif et que le fichier en portait une,
     * sinon la couleur de la catégorie.
     *
     * En SQL plutôt qu'en Kotlin : une seule requête, aucun parcours chargé en mémoire.
     * `WHERE displayColor IS NULL` la rend rejouable sans écraser un choix déjà fait.
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
    // Affichage par fenêtre de vue : on ne charge jamais l'intégralité d'une
    // trace dense en mémoire, seulement une silhouette globale + le détail
    // de la zone visible. Les points restent tous en base.
    // ------------------------------------------------------------------

    /** Effectif et emprise géographique d'une trace, calculés une fois puis mis en cache. */
    @Query(
        "SELECT MIN(id) AS minId, COUNT(*) AS pointCount, " +
                "MIN(latitude) AS minLat, MAX(latitude) AS maxLat, " +
                "MIN(longitude) AS minLon, MAX(longitude) AS maxLon " +
                "FROM track_points WHERE trackId = :trackId"
    )
    suspend fun getTrackPointMeta(trackId: Long): TrackPointMeta?

    /** Nombre de points dans la zone visible. Sert à calibrer le pas d'échantillonnage. */
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
     * Silhouette de la trace entière : un point tous les [stride], plus toutes les
     * ruptures de segment pour ne pas raccorder deux tronçons distincts.
     * Calculée une fois par trace puis gardée en cache.
     */
    @Query(
        "SELECT * FROM track_points " +
                "WHERE trackId = :trackId " +
                "AND (isDiscontinuous = 1 OR ((id - :minId) % :stride) = 0) " +
                "ORDER BY id ASC"
    )
    suspend fun getSkeletonPoints(trackId: Long, minId: Long, stride: Long): List<TrackPoint>

    /**
     * Détail de la zone visible. Avec [stride] = 1 (zoom suffisant), renvoie
     * absolument tous les points de la trace présents à l'écran.
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
    // Fusion de parcours, entièrement côté base : les points sont recopiés
    // par SQLite sans jamais transiter par la mémoire de l'application.
    // ------------------------------------------------------------------

    @Query("SELECT MAX(id) FROM track_points WHERE trackId = :trackId")
    suspend fun getMaxPointId(trackId: Long): Long?

    @Query("SELECT MAX(id) FROM track_points")
    suspend fun getMaxPointIdGlobal(): Long?

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId AND id > :afterId")
    suspend fun countPointsAfter(trackId: Long, afterId: Long): Int

    /**
     * Recopie les points de [sourceId] à la fin de [destinationId].
     *
     * Le ORDER BY est indispensable : sans lui le planificateur pourrait parcourir
     * l'index (trackId, latitude) et recopier les points dans l'ordre des latitudes.
     * La borne [maxSourceId] permet de recopier un parcours dans lui-même sans
     * relire les lignes que l'on vient d'écrire.
     */
    @Query(
        "INSERT INTO track_points (trackId, latitude, longitude, altitude, speed, timestamp, isDiscontinuous, segmentColor) " +
                "SELECT :destinationId, latitude, longitude, altitude, speed, timestamp, isDiscontinuous, segmentColor " +
                "FROM track_points WHERE trackId = :sourceId AND id <= :maxSourceId ORDER BY id ASC"
    )
    suspend fun copyPointsInto(destinationId: Long, sourceId: Long, maxSourceId: Long)

    /** Marque le premier point inséré après [afterId] comme début d'un nouveau tronçon. */
    @Query(
        "UPDATE track_points SET isDiscontinuous = 1 " +
                "WHERE id = (SELECT MIN(id) FROM track_points WHERE trackId = :trackId AND id > :afterId)"
    )
    suspend fun markFirstPointAfterAsDiscontinuous(trackId: Long, afterId: Long)

    @Query("DELETE FROM track_points WHERE trackId = :trackId AND id <= :maxId")
    suspend fun deletePointsUpTo(trackId: Long, maxId: Long)

    /** Derniers points d'une trace (ordre décroissant), pour reprendre un enregistrement. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY id DESC LIMIT :limit")
    suspend fun getLastPoints(trackId: Long, limit: Int): List<TrackPoint>

    /** Lecture paginée par clé, pour exporter une trace sans la charger entièrement. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPointsPage(trackId: Long, afterId: Long, limit: Int): List<TrackPoint>

    // ------------------------------------------------------------------
    // Insertions : ABORT, et surtout pas REPLACE.
    //
    // Toutes les insertions passent un id à 0, donc laissent SQLite l'attribuer : un
    // conflit de clé primaire ne peut pas se produire aujourd'hui. Mais REPLACE
    // signifie DELETE puis INSERT, et track_points est lié à tracks par une clé
    // étrangère ON DELETE CASCADE : le jour où un Track serait réinséré avec un id
    // existant, la suppression implicite emporterait tous ses points. Un parcours de
    // plusieurs millions de points disparaîtrait sans le moindre message.
    //
    // ABORT fait échouer l'insertion à la place. Le piège ne peut plus se refermer.
    // ------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrack(track: Track): Long

    @Update
    suspend fun updateTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrackPoint(point: TrackPoint): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrackPoints(points: List<TrackPoint>)

    // Variantes bloquantes, utilisées par l'import en flux : le parsing SAX est
    // synchrone et ne peut pas appeler de fonction suspend. À n'appeler que
    // depuis un thread d'arrière-plan.
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
}
