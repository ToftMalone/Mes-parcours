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
            out.append("    <desc>Activite: ${escapeXml(track.activityType)}</desc>\n")
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
     * Un seul Placemark, dont la géométrie est un `<MultiGeometry>` réunissant un
     * `<LineString>` par tronçon continu.
     *
     * Deux exigences à tenir ensemble :
     *
     * - **Les ruptures doivent survivre.** Tout écrire dans un unique bloc
     *   `<coordinates>` les perdrait : après une pause, une reprise ou une fusion,
     *   réimporter le fichier relierait les tronçons par une ligne droite.
     * - **Le parcours doit rester d'un seul tenant.** La version précédente ouvrait
     *   un Placemark par tronçon, nommés « Nom », « Nom (2) »… : un parcours fusionné
     *   à partir de dix traces se présentait comme dix entrées distinctes dans Google
     *   Earth, alors que l'utilisateur venait justement de les réunir.
     *
     * `<MultiGeometry>` répond aux deux : une seule entrée, des lignes distinctes.
     *
     * Un tronçon unique donne un `<MultiGeometry>` d'un seul `<LineString>`. C'est
     * valide, et l'uniformité vaut mieux ici qu'un cas particulier : l'écriture étant
     * incrémentale, on ne sait pas au moment d'ouvrir la géométrie combien de
     * tronçons suivront.
     */
    class KmlWriter(private val out: Appendable) {
        private var trackName = ""
        private var placemarkOpen = false
        private var lineOpen = false

        fun start(track: Track) {
            trackName = track.name
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            out.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            out.append("  <Document>\n")
            out.append("    <name>${escapeXml(track.name)}</name>\n")
            out.append("    <description>Tracé enregistré avec Mes parcours - ${escapeXml(track.activityType)}</description>\n")
            out.append("    <Style id=\"routeStyle\">\n")
            out.append("      <LineStyle>\n")
            out.append("        <color>ff0000ff</color> <!-- Red line -->\n")
            out.append("        <width>5</width>\n")
            out.append("      </LineStyle>\n")
            out.append("    </Style>\n")
        }

        fun add(pt: TrackPoint) {
            // Le Placemark n'est ouvert qu'à l'arrivée du premier point : une trace
            // vide ne doit pas produire une entrée sans géométrie.
            if (!placemarkOpen) {
                openPlacemark()
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

        private fun openPlacemark() {
            out.append("    <Placemark>\n")
            out.append("      <name>${escapeXml(trackName)}</name>\n")
            out.append("      <styleUrl>#routeStyle</styleUrl>\n")
            out.append("      <MultiGeometry>\n")
            placemarkOpen = true
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
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
