package com.example.util

import com.example.R
import android.content.Context
import android.net.Uri
import android.util.Xml
import com.example.data.model.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/** Number of points written to the database per batch. Bounds the import's memory footprint. */
private const val BATCH_SIZE = 1000

/** Maximum length kept for the name read from the file. */
private const val MAX_NAME_LENGTH = 512

/**
 * Maximum length kept for the text of a GPX element.
 *
 * The name is the longest of the fields we keep; the others — `ele`, `time`,
 * `speed`, `type` — fit in a few dozen characters. Beyond that, the text
 * is truncated rather than accumulated: without this bound, a `<desc>` of several
 * tens of megabytes was copied whole into the buffer, and the GPX path
 * then had none of the protections the KML path gives itself (see
 * [MAX_COLOR_LENGTH] and [MAX_STYLE_ID_LENGTH]).
 */
private const val MAX_GPX_TEXT_LENGTH = MAX_NAME_LENGTH

/**
 * Maximum length kept for a color read from the file. A KML color
 * is eight characters; the margin absorbs spaces, and the bound keeps a
 * malformed file from swelling the buffer.
 */
private const val MAX_COLOR_LENGTH = 32

/** Maximum length kept for a style identifier or a `<Pair>` key. */
private const val MAX_STYLE_ID_LENGTH = 256

/**
 * Maximum length of a `<coordinates>` point (“lon,lat[,alt]”), in characters.
 *
 * A real point is a few dozen, even with fifteen decimals. Without this bound,
 * the token grew until the next blank: a block of several hundred
 * megabytes without a single space was accumulated whole, two bytes per character,
 * until memory was exhausted — the asymmetry already fixed by [MAX_GPX_TEXT_LENGTH]
 * on the GPX side, left open here (audit of 1.3). Beyond, the token is ignored.
 */
internal const val MAX_COORDINATE_TOKEN_LENGTH = 128

/** KML element name without namespace prefix, in lowercase. */
private fun kmlTagOf(localName: String?, qName: String?): String {
    val raw = if (!localName.isNullOrEmpty()) localName else qName ?: return ""
    return raw.substringAfterLast(':').lowercase(Locale.US)
}

/**
 * Imports a GPX/KML without ever simplifying the track: every point present in
 * the file becomes a TrackPoint. No subsampling, no smoothing.
 *
 * To fit in memory on very large files (several tens of MB), parsing
 * is streamed: points are emitted in batches via `onBatch` instead of
 * being accumulated in a list, and the track's statistics are computed
 * on the fly.
 */
object Importer {

    // RuntimeException and not Exception: Room.runInTransaction() rewraps checked
    // exceptions in a RuntimeException, which would hide the message.
    class ImportException(
        @androidx.annotation.StringRes override val messageRes: Int,
        override val messageArgs: List<Any> = emptyList()
    ) : RuntimeException("string resource #$messageRes"), UserFacing

    /** Statistics of the track, computed during parsing. */
    data class ImportSummary(
        val name: String,
        val activityType: String,
        val startTime: Long,
        val endTime: Long,
        val totalDistance: Double,
        val duration: Long,
        val maxSpeed: Double,
        val avgSpeed: Double,
        val elevationGain: Double,
        val elevationLoss: Double,
        val pointCount: Int,
        /** Track color read from the file (ARGB), null if the file carried none. */
        val sourceColor: Int? = null
    )

    /** What the first read of a KML picks up: the styles, and the number of points. */
    private data class KmlPrescan(
        val styles: KmlStyleTable,
        val pointCount: Long
    )

    /**
     * Parses the file and emits the points in batches via [onBatch]. The points
     * already carry [trackId], so the caller has no copy to make.
     *
     * Returns null if the file contains no usable point.
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        trackId: Long,
        onBatch: (List<TrackPoint>) -> Unit
    ): ImportSummary? {
        val fileName = getFileName(context, uri)
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val defaultName = fileName.substringBeforeLast('.')
        val isKml = extension == "kml" || isKMLContent(context, uri)

        val sink = PointSink(trackId, onBatch)

        // KML styles are collected by a first read, before the points.
        //
        // A <Placemark> designates its style by an identifier, and the matching
        // <Style> may be written further down in the file — via a <StyleMap>
        // that itself points elsewhere. In a single pass, a trip whose
        // style is declared after it would stay without a color, and its points are already
        // written to the database when we learn it: impossible to go back.
        //
        // This pass also counts the points, without keeping any: that is what
        // allows dating the track exactly (see parseKML). Its memory footprint
        // stays at a few dozen entries, whatever the size of the file.
        val prescan = if (isKml) {
            context.contentResolver.openInputStream(uri)?.use { prescanKml(it) }
                ?: KmlPrescan(KmlStyleTable(), 0L)
        } else {
            KmlPrescan(KmlStyleTable(), 0L)
        }

        val meta = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            if (isKml) {
                parseKML(inputStream, defaultName, prescan, sink)
            } else {
                parseGPX(inputStream, defaultName, sink)
            }
        } ?: return null

        sink.flush()
        if (sink.count == 0) return null

        val duration = (sink.endTime - sink.startTime) / 1000
        return ImportSummary(
            name = meta.name,
            activityType = meta.activityType,
            startTime = sink.startTime,
            endTime = sink.endTime,
            totalDistance = sink.totalDistance,
            duration = duration,
            maxSpeed = sink.maxSpeed,
            avgSpeed = if (duration > 0) sink.totalDistance / duration else 0.0,
            elevationGain = sink.elevationGain,
            elevationLoss = sink.elevationLoss,
            pointCount = sink.count,
            sourceColor = meta.sourceColor
        )
    }

    // ---------------------------------------------------------------- GPX

    private fun parseGPX(inputStream: InputStream, defaultName: String, sink: PointSink): TrackMeta {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null)

        var trackName = defaultName
        var nameResolved = false
        var activityType = "Hiking"

        var eventType = parser.eventType
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle = 0.0
        var currentSpeed = 0f
        var currentTimestamp = 0L
        val textBuffer = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    textBuffer.setLength(0)
                    if (tagName.equals("trkseg", ignoreCase = true) ||
                        tagName.equals("trk", ignoreCase = true) ||
                        tagName.equals("rte", ignoreCase = true)
                    ) {
                        sink.startNewSegment()
                    } else if (tagName.equals("trkpt", ignoreCase = true) ||
                        tagName.equals("rtept", ignoreCase = true)
                    ) {
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentEle = 0.0
                        currentSpeed = 0f
                        currentTimestamp = 0
                    }
                }
                XmlPullParser.TEXT -> {
                    // `getTextCharacters` hands back the parser's internal buffer as
                    // is, where `parser.text` first builds a complete copy: on an
                    // oversized element, that copy alone already weighed
                    // two bytes per character before even being truncated.
                    val room = MAX_GPX_TEXT_LENGTH - textBuffer.length
                    if (room > 0) {
                        val bounds = IntArray(2)
                        val chars = parser.getTextCharacters(bounds)
                        if (chars != null && bounds[1] > 0) {
                            textBuffer.append(chars, bounds[0], minOf(bounds[1], room))
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val text = textBuffer.toString().trim()
                    if (tagName.equals("name", ignoreCase = true)) {
                        if (text.isNotEmpty() && !nameResolved) {
                            trackName = text
                            nameResolved = true
                        }
                    } else if (tagName.equals("type", ignoreCase = true)) {
                        if (text.isNotEmpty()) {
                            activityType = text
                        }
                    } else if (tagName.equals("ele", ignoreCase = true)) {
                        currentEle = text.toDoubleOrNull() ?: 0.0
                    } else if (tagName.equals("speed", ignoreCase = true)) {
                        currentSpeed = text.toFloatOrNull() ?: 0f
                    } else if (tagName.equals("time", ignoreCase = true)) {
                        parseIso8601(text)?.let { currentTimestamp = it }
                    } else if (tagName.equals("trkpt", ignoreCase = true) ||
                        tagName.equals("rtept", ignoreCase = true)
                    ) {
                        val lat = currentLat
                        val lon = currentLon
                        if (lat != null && lon != null) {
                            sink.add(
                                latitude = lat,
                                longitude = lon,
                                altitude = currentEle,
                                speed = currentSpeed,
                                timestamp = if (currentTimestamp > 0) currentTimestamp else System.currentTimeMillis()
                            )
                        }
                        currentLat = null
                        currentLon = null
                    }
                }
            }
            eventType = parser.next()
        }

        return TrackMeta(trackName, activityType)
    }

    // ---------------------------------------------------------------- KML

    /**
     * KML is parsed with SAX, not with XmlPullParser: kXML2 merges adjacent text
     * into a single event and would therefore rebuild in memory the whole
     * <coordinates> block (over 100 MB in UTF-16 for a 50 MB file).
     * SAX (Expat) delivers characters in small chunks, consumed on the fly.
     */
    private fun parseKML(
        inputStream: InputStream,
        defaultName: String,
        prescan: KmlPrescan,
        sink: PointSink
    ): TrackMeta {
        // KML carries no timestamp: we keep the historical convention of one
        // point per second, set so that the track ends at the present moment.
        //
        // The number of points comes from the exact count made on the first read, and
        // no longer from an estimate drawn from the file size. That estimate
        // was badly wrong whenever the provider could not announce the size
        // — the case of Google Drive and several file managers, which
        // then return zero. The computation fell back to a single point, and a file
        // of 100,000 points was dated from “now” to “now + 27 hours”:
        // the imported track was filed in the future, hence at the top of the history.
        val startClock = System.currentTimeMillis() - prescan.pointCount * 1000L
        val handler = KmlHandler(sink, defaultName, startClock, prescan.styles)

        newSaxParser().parse(inputStream, handler)

        return TrackMeta(handler.trackName, "Hiking", handler.firstLineColor)
    }

    /**
     * First read: collects the styles and counts the points, without keeping any.
     *
     * The count does go through the `<coordinates>` blocks, but only retains an
     * integer: the memory footprint does not depend on the file size.
     */
    private fun prescanKml(inputStream: InputStream): KmlPrescan {
        val handler = KmlStyleHandler()
        newSaxParser().parse(inputStream, handler)
        return KmlPrescan(handler.table, handler.pointCount)
    }

    /**
     * Hardened SAX parser: an imported file comes from outside, it must not
     * be able to declare XML entities.
     *
     * Without these settings, a `<!DOCTYPE>` containing nested entities — the
     * so-called “billion laughs” attack — makes memory swell until a crash from a
     * file of a few kilobytes; and an external entity could make a local
     * file be read or a network request be issued without the user knowing.
     *
     * GPX, for its part, goes through [Xml.newPullParser]: kXML2 ignores the
     * document type declaration by default, so there is nothing to disable on that side.
     *
     * Checked against the AOSP source rather than assumed, during the 1.1 audit:
     * `KXmlParser.readEntityDeclaration` only stores an entity in `documentEntities`
     * if `FEATURE_PROCESS_DOCDECL` is active — it is false by default and nothing
     * here activates it — so that no entity declared in a GPX is ever
     * expanded, and “billion laughs” has no hold. External entities,
     * for their part, are mapped to the empty string: no local file read or
     * network request through this path either.
     */
    private fun newSaxParser() = SAXParserFactory.newInstance()
        .apply {
            isNamespaceAware = false
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
            trySetFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        .newSAXParser()

    /**
     * Sets a security flag if it is recognized.
     *
     * Not all implementations know all the flags, and refusing
     * to import because one is missing would be worse than doing without: the first
     * of the list is enough on its own to rule out the dangerous case.
     */
    private fun SAXParserFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (e: Exception) {
            // Flag unknown to this implementation: we carry on without it.
        }
    }

    // ------------------------------------------------------------- Misc

    private fun getFileName(context: Context, uri: Uri): String {
        var name = context.getString(R.string.import_default_name)
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val n = it.getString(nameIndex)
                        if (!n.isNullOrEmpty()) name = n
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return name
    }

    private fun isKMLContent(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = ByteArray(256)
                val read = stream.read(bytes)
                if (read > 0) {
                    String(bytes, 0, read).contains("<kml", ignoreCase = true)
                } else false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

}

/**
 * Parser for the date-time part, kept per thread.
 *
 * Building one per point cost the pattern analysis on every line of the file,
 * i.e. several million times on a dense track. `SimpleDateFormat` not being
 * shareable between threads, we keep one instance per thread rather than a single
 * shared one.
 */
private val iso8601DateTime = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}

/**
 * ISO 8601 timestamp of a GPX file, in milliseconds since the epoch.
 *
 * The previous implementation only recognized two forms, both in UTC:
 * `2024-01-01T10:00:00Z` and `2024-01-01T10:00:00.123Z`. But the most common form
 * in real files — the one produced by most watches and
 * sports apps — carries a time zone offset: `2024-01-01T10:00:00+02:00`.
 * It failed, the point then took the import time, and the whole chronology
 * of the file was lost: duration, speeds and pace became wrong.
 *
 * The `Z` suffix, the `+02:00`, `+0200` and `+02` offsets are now accepted,
 * and so is the absence of a suffix — understood as UTC, as the GPX specification requires.
 *
 * Pure function, with no Android dependency: directly testable.
 */
internal fun parseIso8601(str: String): Long? {
    val s = str.trim()
    // “yyyy-MM-ddTHH:mm:ss”: 19 characters, the minimum usable.
    if (s.length < 19) return null

    val base = try {
        iso8601DateTime.get()?.parse(s.substring(0, 19))?.time ?: return null
    } catch (e: Exception) {
        return null
    }

    var rest = s.substring(19)

    // Possible fraction of a second. We only keep the thousandths: beyond, the
    // precision is meaningless for a track recorded at best at 1 Hz.
    var millis = 0L
    if (rest.startsWith('.') || rest.startsWith(',')) {
        val digits = rest.drop(1).takeWhile { it.isDigit() }
        if (digits.isNotEmpty()) {
            millis = digits.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        }
        rest = rest.drop(1 + digits.length)
    }

    val offsetMinutes = parseZoneOffsetMinutes(rest) ?: return null
    return base + millis - offsetMinutes * 60_000L
}

/**
 * Time zone offset announced after the time, in minutes.
 *
 * Returns 0 for `Z` as for an absent suffix — the GPX specification requires UTC
 * when nothing is specified — and null if the suffix is present but unreadable, so
 * that the caller prefers to reject the timestamp rather than shift it at random.
 */
private fun parseZoneOffsetMinutes(zone: String): Int? {
    val z = zone.trim()
    if (z.isEmpty() || z.equals("Z", ignoreCase = true)) return 0

    val sign = when (z[0]) {
        '+' -> 1
        '-' -> -1
        else -> return null
    }

    val digits = z.drop(1).replace(":", "")
    if (!digits.all { it.isDigit() }) return null

    val hours: Int
    val minutes: Int
    when (digits.length) {
        4 -> {
            hours = digits.substring(0, 2).toInt()
            minutes = digits.substring(2, 4).toInt()
        }
        2 -> {
            hours = digits.toInt()
            minutes = 0
        }
        else -> return null
    }
    if (hours > 18 || minutes > 59) return null

    return sign * (hours * 60 + minutes)
}

private data class TrackMeta(
    val name: String,
    val activityType: String,
    /** Track color carried by the file, when it carries one. */
    val sourceColor: Int? = null
)

/**
 * Accumulates points in batches and computes statistics on the fly.
 * The memory footprint is bounded to one batch, whatever the size of the file.
 */
private class PointSink(
    private val trackId: Long,
    private val onBatch: (List<TrackPoint>) -> Unit
) {
    private var buffer = ArrayList<TrackPoint>(BATCH_SIZE)

    var count = 0
        private set
    var startTime = 0L
        private set
    var endTime = 0L
        private set
    var totalDistance = 0.0
        private set
    var elevationGain = 0.0
        private set
    var elevationLoss = 0.0
        private set
    var maxSpeed = 0.0
        private set

    // A "segment" is a GPX <trkseg>/<trk>/<rte> or a KML <coordinates> block.
    // Neither distance nor elevation is accumulated across a segment break.
    private var segmentIndex = 0
    private var pointsInSegment = 0

    private var hasPrevious = false
    private var prevLat = 0.0
    private var prevLon = 0.0
    private var prevAlt = 0.0

    /** Opens a new segment, unless the current segment is still empty. */
    fun startNewSegment() {
        if (pointsInSegment > 0) {
            segmentIndex++
            pointsInSegment = 0
            hasPrevious = false
        }
    }

    fun add(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speed: Float,
        timestamp: Long,
        segmentColor: Int? = null
    ) {
        if (hasPrevious) {
            totalDistance += haversineMeters(prevLat, prevLon, latitude, longitude)
            val eleDiff = altitude - prevAlt
            if (eleDiff > 0) elevationGain += eleDiff else elevationLoss += -eleDiff
        }
        if (speed > maxSpeed) maxSpeed = speed.toDouble()
        if (count == 0) startTime = timestamp
        endTime = timestamp

        buffer.add(
            TrackPoint(
                trackId = trackId,
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                speed = speed,
                timestamp = timestamp,
                isDiscontinuous = segmentIndex > 0 && pointsInSegment == 0,
                segmentColor = segmentColor
            )
        )

        count++
        pointsInSegment++
        prevLat = latitude
        prevLon = longitude
        prevAlt = altitude
        hasPrevious = true

        if (buffer.size >= BATCH_SIZE) flush()
    }

    fun flush() {
        if (buffer.isEmpty()) return
        onBatch(buffer)
        buffer = ArrayList(BATCH_SIZE)
    }
}

/**
 * First read of a KML: collects the document's `<Style>` and `<StyleMap>`.
 *
 * Only keeps identifiers and colors — a few dozen entries even
 * for a file of several hundred megabytes, since the `<coordinates>`
 * are ignored.
 */
private class KmlStyleHandler : DefaultHandler() {

    val table = KmlStyleTable()

    /**
     * Number of points in the file, counted without keeping any.
     *
     * Used to date the track: KML carries no timestamp, and knowing the exact
     * count before writing the first point is the only way to make it end
     * at the present moment rather than in the future.
     */
    var pointCount = 0L
        private set

    private var inCoordinates = false
    private val counter = CoordinateTokenizer { _, _, _ -> pointCount++ }

    private var styleId: String? = null
    private var styleMapId: String? = null

    private var inLineStyle = false
    private var inColor = false
    private val colorBuffer = StringBuilder()
    private var styleColor: Int? = null

    // A <StyleMap> distinguishes the resting appearance from the hover one. Only the
    // first matters to us: it is the one the user sees in Google Earth.
    private var inPair = false
    private var inKey = false
    private var inStyleUrl = false
    private val keyBuffer = StringBuilder()
    private val styleUrlBuffer = StringBuilder()
    private var normalStyleUrl: String? = null

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        when (kmlTagOf(localName, qName)) {
            "style" -> {
                styleId = attributes?.getValue("id")
                styleColor = null
            }
            "stylemap" -> {
                styleMapId = attributes?.getValue("id")
                normalStyleUrl = null
            }
            "linestyle" -> inLineStyle = true
            "color" -> if (inLineStyle) {
                inColor = true
                colorBuffer.setLength(0)
            }
            "pair" -> {
                inPair = true
                keyBuffer.setLength(0)
                styleUrlBuffer.setLength(0)
            }
            "key" -> if (inPair) inKey = true
            "styleurl" -> if (inPair) inStyleUrl = true
            "coordinates" -> {
                inCoordinates = true
                counter.reset()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        when {
            inCoordinates -> counter.feed(ch, start, length)
            inColor && colorBuffer.length < MAX_COLOR_LENGTH ->
                colorBuffer.append(ch, start, minOf(length, MAX_COLOR_LENGTH - colorBuffer.length))
            inKey && keyBuffer.length < MAX_STYLE_ID_LENGTH ->
                keyBuffer.append(ch, start, minOf(length, MAX_STYLE_ID_LENGTH - keyBuffer.length))
            inStyleUrl && styleUrlBuffer.length < MAX_STYLE_ID_LENGTH ->
                styleUrlBuffer.append(ch, start, minOf(length, MAX_STYLE_ID_LENGTH - styleUrlBuffer.length))
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (kmlTagOf(localName, qName)) {
            "coordinates" -> if (inCoordinates) {
                counter.finish()
                inCoordinates = false
            }
            "color" -> if (inColor) {
                inColor = false
                KmlColor.parse(colorBuffer.toString())?.let { styleColor = it }
                colorBuffer.setLength(0)
            }
            "linestyle" -> {
                inLineStyle = false
                inColor = false
            }
            "key" -> inKey = false
            "styleurl" -> inStyleUrl = false
            "pair" -> {
                if (inPair && keyBuffer.toString().trim().equals("normal", ignoreCase = true)) {
                    normalStyleUrl = styleUrlBuffer.toString()
                }
                inPair = false
            }
            "style" -> {
                table.putStyle(styleId, styleColor)
                styleId = null
                styleColor = null
            }
            "stylemap" -> {
                table.putStyleMap(styleMapId, normalStyleUrl)
                styleMapId = null
                normalStyleUrl = null
            }
        }
    }
}

private class KmlHandler(
    private val sink: PointSink,
    defaultName: String,
    startClock: Long,
    private val styleTable: KmlStyleTable
) : DefaultHandler() {

    var trackName: String = defaultName
        private set

    /**
     * Color of the first track encountered. Serves as the file's representative color —
     * the track's chip in the history, which only has room for one.
     */
    var firstLineColor: Int? = null
        private set

    private var nameResolved = false
    private var inName = false
    private var inCoordinates = false
    private val nameBuffer = StringBuilder()

    // ------------------------------------------------------------------
    // Color of the current track.
    //
    // It comes either from a <styleUrl> resolved in the directory collected on the
    // first read, or from a <Style> written directly in the <Placemark>.
    // The second wins: a local style is more precise than a reference.
    // ------------------------------------------------------------------

    private var inPlacemark = false
    private var inStyleUrl = false
    private var inLineStyle = false
    private var inLineStyleColor = false
    private val styleUrlBuffer = StringBuilder()
    private val colorBuffer = StringBuilder()

    private var referencedColor: Int? = null
    private var inlineColor: Int? = null

    /** Color retained for the points being emitted. */
    private val currentColor: Int? get() = inlineColor ?: referencedColor

    private var clock = startClock

    private val tokenizer = CoordinateTokenizer { lon, lat, ele ->
        sink.add(
            latitude = lat,
            longitude = lon,
            altitude = ele,
            speed = 0f,
            timestamp = clock,
            segmentColor = currentColor
        )
        clock += 1000L
    }

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        when (kmlTagOf(localName, qName)) {
            "name" -> if (!nameResolved) {
                inName = true
                nameBuffer.setLength(0)
            }
            "placemark" -> {
                inPlacemark = true
                referencedColor = null
                inlineColor = null
            }
            "styleurl" -> if (inPlacemark) {
                inStyleUrl = true
                styleUrlBuffer.setLength(0)
            }
            "linestyle" -> if (inPlacemark) inLineStyle = true
            "color" -> if (inLineStyle) {
                inLineStyleColor = true
                colorBuffer.setLength(0)
            }
            "coordinates" -> {
                inCoordinates = true
                tokenizer.reset()
                // Each geometry opens a segment: two trips of the same file
                // must never be joined by a line, and this is also what
                // lets each carry its own color.
                sink.startNewSegment()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        when {
            inCoordinates -> tokenizer.feed(ch, start, length)
            inName && nameBuffer.length < MAX_NAME_LENGTH ->
                nameBuffer.append(ch, start, minOf(length, MAX_NAME_LENGTH - nameBuffer.length))
            inStyleUrl && styleUrlBuffer.length < MAX_STYLE_ID_LENGTH ->
                styleUrlBuffer.append(ch, start, minOf(length, MAX_STYLE_ID_LENGTH - styleUrlBuffer.length))
            inLineStyleColor && colorBuffer.length < MAX_COLOR_LENGTH ->
                colorBuffer.append(ch, start, minOf(length, MAX_COLOR_LENGTH - colorBuffer.length))
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when (kmlTagOf(localName, qName)) {
            "name" -> if (inName) {
                inName = false
                val text = nameBuffer.toString().trim()
                if (text.isNotEmpty()) {
                    trackName = text
                    nameResolved = true
                }
                nameBuffer.setLength(0)
            }
            "styleurl" -> if (inStyleUrl) {
                inStyleUrl = false
                referencedColor = styleTable.resolve(styleUrlBuffer.toString())
                rememberFirstColor()
                styleUrlBuffer.setLength(0)
            }
            "color" -> if (inLineStyleColor) {
                inLineStyleColor = false
                // Rejected if unreadable or transparent: we then fall back to the
                // palette color rather than an invisible line.
                KmlColor.parse(colorBuffer.toString())?.let {
                    inlineColor = it
                    rememberFirstColor()
                }
                colorBuffer.setLength(0)
            }
            "linestyle" -> {
                inLineStyle = false
                inLineStyleColor = false
            }
            "coordinates" -> if (inCoordinates) {
                tokenizer.finish()
                inCoordinates = false
            }
            "placemark" -> {
                inPlacemark = false
                referencedColor = null
                inlineColor = null
            }
        }
    }

    private fun rememberFirstColor() {
        if (firstLineColor == null) firstLineColor = currentColor
    }
}

/**
 * Splits a stream of KML coordinates ("lon,lat[,alt]" separated by blanks) without
 * ever materializing the whole block. Tolerates spaces around commas, as
 * the previous regular-expression normalization did.
 */
internal class CoordinateTokenizer(
    private val onPoint: (lon: Double, lat: Double, ele: Double) -> Unit
) {
    private val token = StringBuilder(64)
    private var sawSpace = false

    /**
     * The current token has exceeded [MAX_COORDINATE_TOKEN_LENGTH]: its following
     * characters are no longer kept, and it will be discarded instead of emitted. A
     * truncated point is worth nothing — “2.35,48.8” cut to “2.35,4” would place the point
     * elsewhere without anything flagging it.
     */
    private var overflowed = false

    fun reset() {
        token.setLength(0)
        sawSpace = false
        overflowed = false
    }

    fun feed(ch: CharArray, start: Int, length: Int) {
        var i = start
        val end = start + length
        while (i < end) {
            val c = ch[i]
            if (c <= ' ') {
                if (token.isNotEmpty()) sawSpace = true
            } else {
                if (sawSpace) {
                    sawSpace = false
                    // A blank only separates two points if it does not border a comma.
                    if (c != ',' && token[token.length - 1] != ',') {
                        emit()
                    }
                }
                if (token.length < MAX_COORDINATE_TOKEN_LENGTH) {
                    token.append(c)
                } else {
                    overflowed = true
                }
            }
            i++
        }
    }

    fun finish() {
        emit()
        sawSpace = false
    }

    private fun emit() {
        if (token.isEmpty()) return
        if (overflowed) {
            token.setLength(0)
            overflowed = false
            return
        }

        var comma1 = -1
        var comma2 = -1
        for (idx in 0 until token.length) {
            if (token[idx] == ',') {
                if (comma1 == -1) {
                    comma1 = idx
                } else {
                    comma2 = idx
                    break
                }
            }
        }

        if (comma1 > 0) {
            val lon = token.substring(0, comma1).toDoubleOrNull()
            val lat = if (comma2 != -1) {
                token.substring(comma1 + 1, comma2).toDoubleOrNull()
            } else {
                token.substring(comma1 + 1).toDoubleOrNull()
            }
            val ele = if (comma2 != -1) token.substring(comma2 + 1).toDoubleOrNull() ?: 0.0 else 0.0

            if (lon != null && lat != null) {
                onPoint(lon, lat, ele)
            }
        }

        token.setLength(0)
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
}
