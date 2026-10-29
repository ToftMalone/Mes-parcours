package com.example.util

import com.example.data.model.Track
import com.example.data.model.TrackPlacemark
import com.example.data.model.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Génère les fichiers GPX/KML.
 *
 * L'écriture est incrémentale ([GpxWriter], [KmlWriter]) : l'appelant pousse les
 * points au fur et à mesure qu'il les lit en base, et rien n'est accumulé en
 * mémoire. Indispensable pour réexporter une trace importée de plusieurs millions
 * de points, qui ne tiendrait pas dans une String.
 */
object Exporter {

    private fun newIso8601Formatter() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    class GpxWriter(private val out: Appendable) {
        private val formatter = newIso8601Formatter()
        private val date = Date()
        private var inSeg = false

        fun start(track: Track) {
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.append("<gpx version=\"1.1\" creator=\"Mes parcours\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            out.append("  <metadata>\n")
            out.append("    <name>${escapeXml(track.name)}</name>\n")
            out.append("    <desc>Activity: ${escapeXml(track.activityType)}</desc>\n")
            out.append("    <time>${formatTime(track.startTime)}</time>\n")
            out.append("  </metadata>\n")
            out.append("  <trk>\n")
            out.append("    <name>${escapeXml(track.name)}</name>\n")
            out.append("    <type>${escapeXml(track.activityType)}</type>\n")
        }

        fun add(pt: TrackPoint) {
            if (pt.isDiscontinuous && inSeg) {
                out.append("    </trkseg>\n")
                out.append("    <trkseg>\n")
            } else if (!inSeg) {
                out.append("    <trkseg>\n")
                inSeg = true
            }
            out.append("      <trkpt lat=\"${pt.latitude}\" lon=\"${pt.longitude}\">\n")
            out.append("        <ele>${pt.altitude}</ele>\n")
            out.append("        <time>${formatTime(pt.timestamp)}</time>\n")
            if (pt.speed > 0f) {
                out.append("        <speed>${pt.speed}</speed>\n")
            }
            out.append("      </trkpt>\n")
        }

        fun finish() {
            if (inSeg) {
                out.append("    </trkseg>\n")
            }
            out.append("  </trk>\n")
            out.append("</gpx>")
        }

        private fun formatTime(millis: Long): String {
            date.time = millis
            return formatter.format(date)
        }
    }

    /**
     * Le KML d'un parcours, rangé comme dans Google Earth : un Placemark par tracé,
     * dont la géométrie est un `<MultiGeometry>` réunissant un `<LineString>` par
     * tronçon continu, et ces Placemarks dans leurs `<Folder>`.
     *
     * Exigences à tenir ensemble :
     *
     * - **Les ruptures doivent survivre.** Tout écrire dans un unique bloc
     *   `<coordinates>` les perdrait : après une pause, une reprise ou une fusion,
     *   réimporter le fichier relierait les tronçons par une ligne droite.
     * - **Un parcours enregistré reste d'un seul tenant.** Un Placemark par tronçon
     *   présentait un parcours fusionné à partir de dix traces comme dix entrées
     *   distinctes dans Google Earth. D'où `<MultiGeometry>` : une entrée, des lignes.
     * - **Un KML importé ressort tel qu'il est entré** : demandé par le père de
     *   l'auteur, dont le fichier rangé en dossiers (France, Étranger, À refaire…) et
     *   en tracés nommés ressortait en une liste plate de « Mes traces », tout en
     *   rouge et d'une seule épaisseur. Les points portent leur tracé d'origine
     *   (`TrackPoint.placemarkId`, voir `TrackPlacemark`) : chacun redevient un
     *   Placemark à son nom, dans ses dossiers, à sa couleur et à son épaisseur.
     *   L'écriture étant en flux, un dossier est ouvert à l'arrivée de son premier
     *   tracé et refermé quand vient un tracé qui n'y est plus.
     * - **Les couleurs suivent l'écran** : celle choisie pour le parcours, sinon celle
     *   du tronçon (`TrackPoint.segmentColor`), sinon celle du fichier ou de la
     *   catégorie. Un `<Style>` ne vaut que pour tout un Placemark : un changement de
     *   couleur en ouvre donc un nouveau, style écrit dedans. La réimportation relit
     *   ces styles en ligne, ces noms et ces dossiers.
     */
    class KmlWriter(
        private val out: Appendable,
        /** Les tracés KML d'origine du parcours, par identifiant (`TrackDao.getPlacemarksForTrack`). */
        private val placemarks: Map<Long, TrackPlacemark> = emptyMap()
    ) {
        private var trackName = ""
        private var placemarkOpen = false
        private var lineOpen = false
        private var forcedColor: Int? = null
        private var fallbackColor = 0
        private var trackWidth = DEFAULT_KML_WIDTH
        private var placemarkColor: Int? = null
        private var placemarkKey: Long? = null
        private val openFolders = ArrayList<String>()

        fun start(track: Track) {
            trackName = track.name
            forcedColor = track.displayColor
            fallbackColor = TrackStylePreferences.resolveTrackColor(null, track.sourceColor, track.isImported, track.isMerged)
            trackWidth = track.displayWidthDp ?: DEFAULT_KML_WIDTH
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            out.append("  <Document>\n")
            out.append("    <name>${escapeXml(track.name)}</name>\n")
            // La mention de l'application n'a rien à faire dans un fichier qu'on rend
            // à Google Earth tel qu'il en venait.
            if (!track.isImported) {
                out.append("    <description>Track recorded with Mes parcours - ${escapeXml(track.activityType)}</description>\n")
            }
        }

        fun add(pt: TrackPoint) {
            val color = forcedColor ?: pt.segmentColor ?: fallbackColor
            val key = pt.placemarkId
            // Le Placemark n'est ouvert qu'à l'arrivée du premier point : une trace
            // vide ne doit pas produire une entrée sans géométrie.
            if (placemarkOpen && (color != placemarkColor || key != placemarkKey)) {
                if (lineOpen) closeLine()
                closePlacemark()
            }
            if (!placemarkOpen) {
                val placemark = key?.let { placemarks[it] }
                moveToFolders(placemark?.folders.orEmpty())
                openPlacemark(placemark, color)
                placemarkKey = key
            }
            if (pt.isDiscontinuous && lineOpen) {
                closeLine()
            }
            if (!lineOpen) {
                openLine()
            }
            // Format des coordonnées KML : longitude,latitude,altitude
            out.append("${indent()}          ${pt.longitude},${pt.latitude},${pt.altitude}\n")
        }

        fun finish() {
            if (lineOpen) {
                closeLine()
            }
            if (placemarkOpen) {
                closePlacemark()
            }
            moveToFolders(emptyList())
            out.append("  </Document>\n")
            out.append("</kml>")
        }

        /** Referme les dossiers qui ne contiennent plus le tracé suivant, ouvre les siens. */
        private fun moveToFolders(target: List<String>) {
            var common = 0
            while (common < openFolders.size && common < target.size && openFolders[common] == target[common]) common++
            while (openFolders.size > common) {
                openFolders.removeAt(openFolders.lastIndex)
                out.append("${indent()}    </Folder>\n")
            }
            for (i in common until target.size) {
                out.append("${indent()}    <Folder>\n")
                out.append("${indent()}      <name>${escapeXml(target[i])}</name>\n")
                openFolders += target[i]
            }
        }

        private fun indent() = "  ".repeat(openFolders.size)

        private fun openPlacemark(placemark: TrackPlacemark?, color: Int) {
            val pad = indent()
            out.append("$pad    <Placemark>\n")
            out.append("$pad      <name>${escapeXml(placemark?.name ?: trackName)}</name>\n")
            out.append("$pad      <Style>\n")
            out.append("$pad        <LineStyle>\n")
            out.append("$pad          <color>${KmlColor.format(color)}</color>\n")
            out.append("$pad          <width>${formatWidth(placemark?.width ?: trackWidth)}</width>\n")
            out.append("$pad        </LineStyle>\n")
            out.append("$pad      </Style>\n")
            out.append("$pad      <MultiGeometry>\n")
            placemarkOpen = true
            placemarkColor = color
        }

        private fun closePlacemark() {
            out.append("${indent()}      </MultiGeometry>\n")
            out.append("${indent()}    </Placemark>\n")
            placemarkOpen = false
        }

        private fun openLine() {
            val pad = indent()
            out.append("$pad        <LineString>\n")
            out.append("$pad          <extrude>0</extrude>\n")
            out.append("$pad          <tessellate>1</tessellate>\n")
            out.append("$pad          <altitudeMode>clampToGround</altitudeMode>\n")
            out.append("$pad          <coordinates>\n")
            lineOpen = true
        }

        private fun closeLine() {
            out.append("${indent()}          </coordinates>\n")
            out.append("${indent()}        </LineString>\n")
            lineOpen = false
        }

        private fun formatWidth(width: Float): String =
            if (width % 1f == 0f) width.toInt().toString() else String.format(Locale.ROOT, "%.1f", width)

        private companion object {
            /** Épaisseur écrite quand ni le tracé ni le parcours n'en ont : celle d'avant. */
            const val DEFAULT_KML_WIDTH = 5f
        }
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
