package com.example.util

import android.content.Context
import android.net.Uri
import com.example.data.model.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Conversion entre CSV et les formats GPX/KML, dans les deux sens.
 *
 * Un CSV se lit dans un tableur, ce qu'aucun des deux autres formats ne permet
 * facilement : c'est tout l'intérêt de ce convertisseur, ni plus ni moins. Les deux
 * sens sont indépendants l'un de l'autre et ne passent jamais par la base : ce sont
 * de simples traductions d'un fichier vers un autre, point par point.
 *
 * Le CSV produit porte sept colonnes, toujours dans cet ordre : [COLUMN_LATITUDE],
 * [COLUMN_LONGITUDE], [COLUMN_ALTITUDE], [COLUMN_TIME], [COLUMN_SPEED],
 * [COLUMN_NEW_SEGMENT], [COLUMN_COLOR]. À la lecture, seules les deux premières sont
 * exigées : un CSV façonné à la main dans un tableur, qui ne porterait que des
 * coordonnées, reste utilisable.
 */
object CsvConverter {

    const val COLUMN_LATITUDE = "latitude"
    const val COLUMN_LONGITUDE = "longitude"
    const val COLUMN_ALTITUDE = "altitude_m"
    const val COLUMN_TIME = "horodatage"
    const val COLUMN_SPEED = "vitesse_m_s"
    const val COLUMN_NEW_SEGMENT = "nouveau_troncon"
    const val COLUMN_COLOR = "couleur_troncon"

    const val CSV_HEADER =
        "$COLUMN_LATITUDE,$COLUMN_LONGITUDE,$COLUMN_ALTITUDE,$COLUMN_TIME,$COLUMN_SPEED,$COLUMN_NEW_SEGMENT,$COLUMN_COLOR"

    /** Position de chaque colonne reconnue dans l'en-tête lu, ou -1 si absente. */
    internal data class CsvColumns(
        val latitude: Int,
        val longitude: Int,
        val altitude: Int = -1,
        val time: Int = -1,
        val speed: Int = -1,
        val newSegment: Int = -1,
        val color: Int = -1
    )

    data class GpxKmlToCsvResult(val sourceName: String, val pointCount: Int)
    data class CsvToTrackResult(val pointCount: Int, val startTime: Long, val endTime: Long)

    // ------------------------------------------------------------------
    // GPX/KML → CSV
    // ------------------------------------------------------------------

    /**
     * Convertit un GPX/KML en CSV, en flux : chaque point lu est aussitôt écrit,
     * sans jamais transiter par la base ni s'accumuler en mémoire.
     *
     * Réutilise l'analyseur d'[Importer], qui sait déjà lire les deux formats et
     * durcit son analyseur XML contre les entités (invariant 4) — la prudence vaut
     * ici aussi, le fichier converti venant lui aussi de l'extérieur. `trackId = 0`
     * n'est qu'un espace réservé : aucun point n'est inséré en base par ce chemin.
     */
    fun convertGpxKmlToCsv(context: Context, uri: Uri, out: Appendable): GpxKmlToCsvResult? {
        out.append(CSV_HEADER).append('\n')
        val summary = Importer.importFromUri(context, uri, trackId = 0L) { batch ->
            for (point in batch) writeRow(out, point)
        } ?: return null
        return GpxKmlToCsvResult(summary.name, summary.pointCount)
    }

    /** Une ligne du CSV pour un point donné. */
    internal fun writeRow(out: Appendable, point: TrackPoint) {
        out.append(point.latitude.toString()).append(',')
        out.append(point.longitude.toString()).append(',')
        out.append(point.altitude.toString()).append(',')
        out.append(formatIso8601(point.timestamp)).append(',')
        out.append(point.speed.toString()).append(',')
        out.append(if (point.isDiscontinuous) "1" else "0").append(',')
        out.append(point.segmentColor?.let { formatHexColor(it) } ?: "")
        out.append('\n')
    }

    // ------------------------------------------------------------------
    // CSV → GPX/KML
    // ------------------------------------------------------------------

    /**
     * Lit un CSV point par point, dans l'ordre du fichier, et transmet chacun à
     * [onPoint]. Ne charge jamais plus d'une ligne en mémoire à la fois : un CSV de
     * plusieurs millions de lignes passerait aussi bien qu'un GPX ou un KML.
     *
     * L'en-tête doit porter au moins les colonnes latitude et longitude ; les autres
     * sont facultatives et retombent sur un repli — voir [parseRow]. Une ligne dont
     * la latitude ou la longitude ne se lit pas est ignorée plutôt que de faire
     * échouer toute la conversion pour une seule ligne mal formée.
     *
     * Renvoie null si l'en-tête n'a pas les colonnes minimales, ou si aucune ligne
     * exploitable n'a été trouvée.
     */
    fun readCsvFromUri(context: Context, uri: Uri, onPoint: (TrackPoint) -> Unit): CsvToTrackResult? {
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val headerLine = reader.readLine() ?: return@use null
            val columns = parseHeader(headerLine) ?: return@use null

            var pointCount = 0
            var startTime = 0L
            var endTime = 0L
            // Repli si la colonne horodatage est absente ou vide sur une ligne :
            // un horodatage synthétique et régulier, comme l'import KML en fabrique
            // déjà pour les fichiers qui n'en portent pas (voir KmlHandler).
            var syntheticClock = System.currentTimeMillis()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val point = parseRow(columns, line.split(","), pointCount == 0, syntheticClock)
                    ?: continue
                onPoint(point)
                if (pointCount == 0) startTime = point.timestamp
                endTime = point.timestamp
                pointCount++
                syntheticClock += 1000L
            }

            if (pointCount == 0) null else CsvToTrackResult(pointCount, startTime, endTime)
        }
    }

    /** Repère les colonnes reconnues dans l'en-tête. Null si latitude/longitude manquent. */
    internal fun parseHeader(headerLine: String): CsvColumns? {
        val names = headerLine.split(",").map { it.trim().lowercase() }
        fun indexOf(name: String) = names.indexOf(name)

        val latitude = indexOf(COLUMN_LATITUDE)
        val longitude = indexOf(COLUMN_LONGITUDE)
        if (latitude < 0 || longitude < 0) return null

        return CsvColumns(
            latitude = latitude,
            longitude = longitude,
            altitude = indexOf(COLUMN_ALTITUDE),
            time = indexOf(COLUMN_TIME),
            speed = indexOf(COLUMN_SPEED),
            newSegment = indexOf(COLUMN_NEW_SEGMENT),
            color = indexOf(COLUMN_COLOR)
        )
    }

    /**
     * Une ligne de CSV vers un point, ou null si la latitude ou la longitude ne se
     * lit pas — la ligne est alors ignorée par l'appelant plutôt que de faire
     * échouer toute la conversion.
     *
     * [isFirstPoint] force l'absence de rupture : le tout premier point d'un
     * parcours ne fait qu'ouvrir le tracé, quoi que dise la colonne — le même
     * raisonnement que `clearFirstPointDiscontinuity` pour un découpage.
     * [fallbackTimestamp] ne sert que si la colonne horodatage est absente, vide,
     * ou illisible sur cette ligne précise.
     */
    internal fun parseRow(
        columns: CsvColumns,
        cells: List<String>,
        isFirstPoint: Boolean,
        fallbackTimestamp: Long
    ): TrackPoint? {
        val latitude = cells.getOrNull(columns.latitude)?.trim()?.toDoubleOrNull() ?: return null
        val longitude = cells.getOrNull(columns.longitude)?.trim()?.toDoubleOrNull() ?: return null

        val altitude = cells.getOrNull(columns.altitude)?.trim()?.toDoubleOrNull() ?: 0.0
        val speed = cells.getOrNull(columns.speed)?.trim()?.toFloatOrNull() ?: 0f

        val timeCell = cells.getOrNull(columns.time)?.trim()
        val timestamp = if (timeCell.isNullOrBlank()) fallbackTimestamp else {
            parseIso8601(timeCell) ?: fallbackTimestamp
        }

        val isDiscontinuous = !isFirstPoint && cells.getOrNull(columns.newSegment)
            ?.trim()
            ?.let { it == "1" || it.equals("true", ignoreCase = true) || it.equals("vrai", ignoreCase = true) }
            ?: false

        val colorCell = cells.getOrNull(columns.color)?.trim()
        val segmentColor = if (colorCell.isNullOrBlank()) null else parseHexColor(colorCell)

        return TrackPoint(
            trackId = 0L,
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = speed,
            timestamp = timestamp,
            isDiscontinuous = isDiscontinuous,
            segmentColor = segmentColor
        )
    }

    // ------------------------------------------------------------------
    // Divers
    // ------------------------------------------------------------------

    /**
     * Analyseur de date, conservé par thread : en construire un par ligne coûterait
     * l'analyse du motif à chaque point, comme le documente déjà l'analyseur
     * équivalent d'[Importer].
     */
    private val iso8601Writer = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    private fun formatIso8601(millis: Long): String = iso8601Writer.get()!!.format(Date(millis))

    /** Couleur ARGB en hexadécimal, huit chiffres avec l'alpha : `#FF8B5CF6`. */
    private fun formatHexColor(color: Int): String = String.format(Locale.US, "#%08X", color)

    private fun parseHexColor(raw: String): Int? {
        val hex = raw.removePrefix("#")
        if (hex.length != 8) return null
        return hex.toLongOrNull(16)?.toInt()
    }

    /** Nom du fichier source, sans son extension, pour nommer le résultat de la conversion. */
    internal fun baseFileName(context: Context, uri: Uri): String =
        displayName(context, uri).substringBeforeLast('.')

    /**
     * Le fichier choisi est-il un CSV (à convertir vers GPX/KML) plutôt qu'un GPX/KML
     * (à convertir vers CSV) ?
     *
     * Sert à n'imposer qu'un seul bouton « Choisir un fichier » dans l'outil de
     * conversion : le sens se déduit du fichier plutôt que de se choisir à part.
     * L'extension tranche la plupart des cas ; à défaut, on regarde le début du
     * fichier, comme le fait déjà `Importer` pour reconnaître un KML sans extension.
     */
    fun isCsvFile(context: Context, uri: Uri): Boolean {
        val extension = displayName(context, uri).substringAfterLast('.', "").lowercase()
        if (extension == "csv") return true
        if (extension == "gpx" || extension == "kml") return false
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = ByteArray(256)
                val read = stream.read(bytes)
                val head = if (read > 0) String(bytes, 0, read) else ""
                !head.contains("<gpx", ignoreCase = true) && !head.contains("<kml", ignoreCase = true)
            } ?: true
        } catch (e: Exception) {
            true
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        var name = "Converti"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        val n = cursor.getString(idx)
                        if (!n.isNullOrEmpty()) name = n
                    }
                }
            }
        } catch (e: Exception) {
            // Nom générique en repli : la conversion elle-même ne doit pas échouer pour si peu.
        }
        return name
    }
}
