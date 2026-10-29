package com.example.util

import com.example.data.model.Track
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
     * Un Placemark par couleur, dont la géométrie est un `<MultiGeometry>` réunissant
     * un `<LineString>` par tronçon continu.
     *
     * Trois exigences à tenir ensemble :
     *
     * - **Les ruptures doivent survivre.** Tout écrire dans un unique bloc
     *   `<coordinates>` les perdrait : après une pause, une reprise ou une fusion,
     *   réimporter le fichier relierait les tronçons par une ligne droite.
     * - **Le parcours doit rester d'un seul tenant.** Un Placemark par tronçon
     *   présentait un parcours fusionné à partir de dix traces comme dix entrées
     *   distinctes dans Google Earth, alors que l'utilisateur venait de les réunir.
     *   D'où `<MultiGeometry>` : une seule entrée, des lignes distinctes.
     * - **Les couleurs doivent survivre.** L'export écrivait tout en rouge, quelle que
     *   soit la couleur à l'écran : le père de l'auteur, qui avait colorié chaque pays
     *   d'un voyage, a retrouvé un fichier d'une seule couleur. La couleur d'un point
     *   se résout comme au dessin : celle choisie pour le parcours, sinon celle du
     *   tronçon (`TrackPoint.segmentColor`), sinon celle du fichier ou de la
     *   catégorie. Un `<Style>` ne vaut que pour tout un Placemark : chaque
     *   changement de couleur en ouvre donc un nouveau, avec son style écrit dedans
     *   (l'écriture étant en flux, on ne connaît pas les couleurs à l'avance). Un
     *   parcours d'une seule couleur reste un seul Placemark. La réimportation relit
     *   ces styles en ligne : la boucle est bouclée.
     */
    class KmlWriter(private val out: Appendable) {
        private var trackName = ""
        private var placemarkOpen = false
        private var lineOpen = false
        private var forcedColor: Int? = null
        private var fallbackColor = 0
        private var widthPx = DEFAULT_KML_WIDTH
        private var placemarkColor: Int? = null

        fun start(track: Track) {
            trackName = track.name
            forcedColor = track.displayColor
            fallbackColor = TrackStylePreferences.resolveTrackColor(null, track.sourceColor, track.isImported, track.isMerged)
            widthPx = track.displayWidthDp ?: DEFAULT_KML_WIDTH
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            out.append("  <Document>\n")
            out.append("    <name>${escapeXml(track.name)}</name>\n")
            out.append("    <description>Track recorded with Mes parcours - ${escapeXml(track.activityType)}</description>\n")
        }

        fun add(pt: TrackPoint) {
            val color = forcedColor ?: pt.segmentColor ?: fallbackColor
            // Le Placemark n'est ouvert qu'à l'arrivée du premier point : une trace
            // vide ne doit pas produire une entrée sans géométrie.
            if (placemarkOpen && color != placemarkColor) {
                if (lineOpen) closeLine()
                closePlacemark()
            }
            if (!placemarkOpen) {
                openPlacemark(color)
            }
            if (pt.isDiscontinuous && lineOpen) {
                closeLine()
            }
            if (!lineOpen) {
                openLine()
            }
            // Format des coordonnées KML : longitude,latitude,altitude
            out.append("            ${pt.longitude},${pt.latitude},${pt.altitude}\n")
        }

        fun finish() {
            if (lineOpen) {
                closeLine()
            }
            if (placemarkOpen) {
                closePlacemark()
            }
            out.append("  </Document>\n")
            out.append("</kml>")
        }

        private fun openPlacemark(color: Int) {
            out.append("    <Placemark>\n")
            out.append("      <name>${escapeXml(trackName)}</name>\n")
            out.append("      <Style>\n")
            out.append("        <LineStyle>\n")
            out.append("          <color>${KmlColor.format(color)}</color>\n")
            out.append("          <width>${formatWidth(widthPx)}</width>\n")
            out.append("        </LineStyle>\n")
            out.append("      </Style>\n")
            out.append("      <MultiGeometry>\n")
            placemarkOpen = true
            placemarkColor = color
        }

        private fun closePlacemark() {
            out.append("      </MultiGeometry>\n")
            out.append("    </Placemark>\n")
            placemarkOpen = false
        }

        private fun openLine() {
            out.append("        <LineString>\n")
            out.append("          <extrude>0</extrude>\n")
            out.append("          <tessellate>1</tessellate>\n")
            out.append("          <altitudeMode>clampToGround</altitudeMode>\n")
            out.append("          <coordinates>\n")
            lineOpen = true
        }

        private fun closeLine() {
            out.append("          </coordinates>\n")
            out.append("        </LineString>\n")
            lineOpen = false
        }

        private fun formatWidth(width: Float): String =
            if (width % 1f == 0f) width.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", width)

        private companion object {
            /** Épaisseur écrite quand le parcours n'en a pas choisi : celle d'avant. */
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
