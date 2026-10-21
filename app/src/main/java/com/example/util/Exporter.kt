package com.example.util

import com.example.data.model.Track
import com.example.data.model.TrackPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Generates the GPX/KML files.
 *
 * Writing is incremental ([GpxWriter], [KmlWriter]): the caller pushes the
 * points as it reads them from the database, and nothing is accumulated in
 * memory. Essential to re-export an imported track of several million
 * points, which would not fit in a String.
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
     * A single Placemark, whose geometry is a `<MultiGeometry>` gathering one
     * `<LineString>` per continuous segment.
     *
     * Two requirements to hold together:
     *
     * - **Breaks must survive.** Writing everything in a single `<coordinates>`
     *   block would lose them: after a pause, a resume or a merge,
     *   reimporting the file would join the segments with a straight line.
     * - **The track must stay in one piece.** The previous version opened
     *   one Placemark per segment, named “Name”, “Name (2)”…: a track merged
     *   from ten tracks showed up as ten distinct entries in Google
     *   Earth, when the user had just brought them together.
     *
     * `<MultiGeometry>` answers both: a single entry, distinct lines.
     *
     * A single segment gives a `<MultiGeometry>` of one `<LineString>`. This is
     * valid, and uniformity is worth more here than a special case: writing being
     * incremental, we do not know when opening the geometry how many
     * segments will follow.
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
            out.append("    <description>Track recorded with Mes parcours - ${escapeXml(track.activityType)}</description>\n")
            out.append("    <Style id=\"routeStyle\">\n")
            out.append("      <LineStyle>\n")
            out.append("        <color>ff0000ff</color> <!-- Red line -->\n")
            out.append("        <width>5</width>\n")
            out.append("      </LineStyle>\n")
            out.append("    </Style>\n")
        }

        fun add(pt: TrackPoint) {
            // The Placemark is only opened when the first point arrives: an empty
            // track must not produce an entry without geometry.
            if (!placemarkOpen) {
                openPlacemark()
            }
            if (pt.isDiscontinuous && lineOpen) {
                closeLine()
            }
            if (!lineOpen) {
                openLine()
            }
            // KML coordinate format: longitude,latitude,altitude
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
