package com.example.util

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

/** Nombre de points écrits en base par lot. Borne l'empreinte mémoire de l'import. */
private const val BATCH_SIZE = 1000

/** Longueur maximale retenue pour le nom lu dans le fichier. */
private const val MAX_NAME_LENGTH = 512

/**
 * Longueur maximale retenue pour une couleur lue dans le fichier. Une couleur KML
 * fait huit caractères ; la marge absorbe les espaces, et la borne évite qu'un
 * fichier malformé fasse enfler le tampon.
 */
private const val MAX_COLOR_LENGTH = 32

/** Longueur maximale retenue pour un identifiant de style ou une clé de `<Pair>`. */
private const val MAX_STYLE_ID_LENGTH = 256

/** Nom d'élément KML sans préfixe de namespace, en minuscules. */
private fun kmlTagOf(localName: String?, qName: String?): String {
    val raw = if (!localName.isNullOrEmpty()) localName else qName ?: return ""
    return raw.substringAfterLast(':').lowercase(Locale.US)
}

/**
 * Importe un GPX/KML sans jamais simplifier la trace : chaque point présent dans
 * le fichier devient un TrackPoint. Aucun sous-échantillonnage, aucun lissage.
 *
 * Pour tenir en mémoire sur de très gros fichiers (plusieurs dizaines de Mo), le
 * parsing se fait en flux : les points sont émis par lots via `onBatch` au lieu
 * d'être accumulés dans une liste, et les statistiques du parcours sont calculées
 * au fil de l'eau.
 */
object Importer {

    // RuntimeException et non Exception : Room.runInTransaction() ré-emballe les
    // exceptions vérifiées dans une RuntimeException, ce qui masquerait le message.
    class ImportException(message: String) : RuntimeException(message)

    /** Statistiques du parcours, calculées pendant le parsing. */
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
        /** Couleur de tracé lue dans le fichier (ARGB), null si le fichier n'en portait pas. */
        val sourceColor: Int? = null
    )

    /** Ce que la première lecture d'un KML relève : les styles, et le nombre de points. */
    private data class KmlPrescan(
        val styles: KmlStyleTable,
        val pointCount: Long
    )

    /**
     * Parse le fichier et émet les points par lots via [onBatch]. Les points
     * portent déjà [trackId], l'appelant n'a donc aucune copie à faire.
     *
     * Retourne null si le fichier ne contient aucun point exploitable.
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

        // Les styles KML sont relevés par une première lecture, avant les points.
        //
        // Un <Placemark> désigne son style par un identifiant, et le <Style>
        // correspondant peut être écrit plus loin dans le fichier — via un <StyleMap>
        // qui, lui, renvoie encore ailleurs. En une seule passe, un trajet dont le
        // style est déclaré après lui resterait sans couleur, et ses points sont déjà
        // écrits en base quand on l'apprend : impossible de revenir dessus.
        //
        // Cette passe compte aussi les points, sans en garder aucun : c'est ce qui
        // permet de dater le tracé exactement (voir parseKML). Son empreinte mémoire
        // reste de quelques dizaines d'entrées, quelle que soit la taille du fichier.
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
        var activityType = "Randonnée"

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
                    textBuffer.append(parser.text)
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
     * Le KML est parsé en SAX, pas avec XmlPullParser : kXML2 fusionne le texte
     * adjacent en un seul événement et reconstruirait donc en mémoire l'intégralité
     * du bloc <coordinates> (plus de 100 Mo en UTF-16 pour un fichier de 50 Mo).
     * SAX (Expat) livre les caractères par petits blocs, qu'on consomme au vol.
     */
    private fun parseKML(
        inputStream: InputStream,
        defaultName: String,
        prescan: KmlPrescan,
        sink: PointSink
    ): TrackMeta {
        // Le KML ne porte pas d'horodatage : on garde la convention historique d'un
        // point par seconde, calée pour que la trace se termine à l'instant présent.
        //
        // Le nombre de points vient du comptage exact fait à la première lecture, et
        // non plus d'une estimation tirée de la taille du fichier. Cette estimation se
        // trompait lourdement dès que le fournisseur ne savait pas annoncer la taille
        // — le cas de Google Drive et de plusieurs gestionnaires de fichiers, qui
        // renvoient alors zéro. Le calcul retombait sur un seul point, et un fichier
        // de 100 000 points se datait de « maintenant » à « maintenant + 27 heures » :
        // le parcours importé se rangeait dans le futur, donc en tête de l'historique.
        val startClock = System.currentTimeMillis() - prescan.pointCount * 1000L
        val handler = KmlHandler(sink, defaultName, startClock, prescan.styles)

        newSaxParser().parse(inputStream, handler)

        return TrackMeta(handler.trackName, "Randonnée", handler.firstLineColor)
    }

    /**
     * Première lecture : relève les styles et compte les points, sans en garder aucun.
     *
     * Le comptage traverse bien les blocs `<coordinates>`, mais ne retient qu'un
     * entier : l'empreinte mémoire ne dépend pas de la taille du fichier.
     */
    private fun prescanKml(inputStream: InputStream): KmlPrescan {
        val handler = KmlStyleHandler()
        newSaxParser().parse(inputStream, handler)
        return KmlPrescan(handler.table, handler.pointCount)
    }

    /**
     * Analyseur SAX durci : un fichier importé vient de l'extérieur, il ne doit pas
     * pouvoir déclarer d'entités XML.
     *
     * Sans ces réglages, un `<!DOCTYPE>` contenant des entités imbriquées — l'attaque
     * dite « billion laughs » — fait gonfler la mémoire jusqu'au plantage depuis un
     * fichier de quelques kilo-octets ; et une entité externe pourrait faire lire un
     * fichier local ou émettre une requête réseau à l'insu de l'utilisateur.
     *
     * Le GPX, lui, passe par [Xml.newPullParser] : kXML2 ignore par défaut la
     * déclaration de type de document, il n'y a donc rien à désactiver de ce côté.
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
     * Pose un drapeau de sécurité s'il est reconnu.
     *
     * Toutes les implémentations ne connaissent pas tous les drapeaux, et refuser
     * d'importer parce que l'une manque serait pire que de s'en passer : le premier
     * de la liste suffit à lui seul à écarter le cas dangereux.
     */
    private fun SAXParserFactory.trySetFeature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (e: Exception) {
            // Drapeau inconnu de cette implémentation : on poursuit sans.
        }
    }

    // ------------------------------------------------------------- Divers

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Parcours Importe"
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
 * Analyseur de la partie date-heure, conservé par thread.
 *
 * En construire un par point coûtait l'analyse du motif à chaque ligne du fichier,
 * soit plusieurs millions de fois sur une trace dense. `SimpleDateFormat` n'étant pas
 * partageable entre threads, on en garde un exemplaire par thread plutôt qu'un seul
 * partagé.
 */
private val iso8601DateTime = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }
}

/**
 * Horodatage ISO 8601 d'un fichier GPX, en millisecondes depuis l'époque.
 *
 * L'implémentation précédente ne reconnaissait que deux formes, toutes deux en UTC :
 * `2024-01-01T10:00:00Z` et `2024-01-01T10:00:00.123Z`. Or la forme la plus répandue
 * dans les fichiers réels — celle que produisent la plupart des montres et
 * applications de sport — porte un décalage horaire : `2024-01-01T10:00:00+02:00`.
 * Elle échouait, le point prenait alors l'heure de l'import, et toute la chronologie
 * du fichier était perdue : durée, vitesses et allure devenaient fausses.
 *
 * Sont désormais acceptés le suffixe `Z`, les décalages `+02:00`, `+0200` et `+02`,
 * et l'absence de suffixe — comprise comme UTC, ce qu'impose la spécification GPX.
 *
 * Fonction pure, sans dépendance Android : directement testable.
 */
internal fun parseIso8601(str: String): Long? {
    val s = str.trim()
    // « yyyy-MM-ddTHH:mm:ss » : 19 caractères, le minimum exploitable.
    if (s.length < 19) return null

    val base = try {
        iso8601DateTime.get()?.parse(s.substring(0, 19))?.time ?: return null
    } catch (e: Exception) {
        return null
    }

    var rest = s.substring(19)

    // Fraction de seconde éventuelle. On ne garde que les millièmes : au-delà, la
    // précision n'a aucun sens pour une trace enregistrée au mieux à 1 Hz.
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
 * Décalage horaire annoncé après l'heure, en minutes.
 *
 * Renvoie 0 pour `Z` comme pour un suffixe absent — la spécification GPX impose UTC
 * quand rien n'est précisé — et null si le suffixe est présent mais illisible, pour
 * que l'appelant préfère rejeter l'horodatage plutôt que de le décaler au hasard.
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
    /** Couleur de tracé portée par le fichier, quand il en porte une. */
    val sourceColor: Int? = null
)

/**
 * Accumule les points par lots et calcule les statistiques au fil de l'eau.
 * L'empreinte mémoire est bornée à un lot, quelle que soit la taille du fichier.
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

    // Un "segment" est un <trkseg>/<trk>/<rte> GPX ou un bloc <coordinates> KML.
    // Ni la distance ni le dénivelé ne sont cumulés par-dessus une rupture de segment.
    private var segmentIndex = 0
    private var pointsInSegment = 0

    private var hasPrevious = false
    private var prevLat = 0.0
    private var prevLon = 0.0
    private var prevAlt = 0.0

    /** Ouvre un nouveau segment, sauf si le segment courant est encore vide. */
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
 * Première lecture d'un KML : relève les `<Style>` et les `<StyleMap>` du document.
 *
 * Ne conserve que des identifiants et des couleurs — quelques dizaines d'entrées même
 * pour un fichier de plusieurs centaines de mégaoctets, puisque les `<coordinates>`
 * sont ignorées.
 */
private class KmlStyleHandler : DefaultHandler() {

    val table = KmlStyleTable()

    /**
     * Nombre de points du fichier, compté sans en garder aucun.
     *
     * Sert à dater le tracé : le KML ne porte pas d'horodatage, et connaître l'effectif
     * exact avant d'écrire le premier point est le seul moyen de le faire se terminer
     * à l'instant présent plutôt que dans le futur.
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

    // Un <StyleMap> distingue l'apparence au repos de celle au survol. Seule la
    // première nous intéresse : c'est celle que l'utilisateur voit dans Google Earth.
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
     * Couleur du premier tracé rencontré. Sert de couleur représentative du fichier —
     * la pastille de la fiche dans l'historique, qui n'a de place que pour une.
     */
    var firstLineColor: Int? = null
        private set

    private var nameResolved = false
    private var inName = false
    private var inCoordinates = false
    private val nameBuffer = StringBuilder()

    // ------------------------------------------------------------------
    // Couleur du tracé en cours.
    //
    // Elle vient soit d'un <styleUrl> résolu dans le répertoire relevé à la
    // première lecture, soit d'un <Style> écrit directement dans le <Placemark>.
    // Le second l'emporte : un style local est plus précis qu'un renvoi.
    // ------------------------------------------------------------------

    private var inPlacemark = false
    private var inStyleUrl = false
    private var inLineStyle = false
    private var inLineStyleColor = false
    private val styleUrlBuffer = StringBuilder()
    private val colorBuffer = StringBuilder()

    private var referencedColor: Int? = null
    private var inlineColor: Int? = null

    /** Couleur retenue pour les points en cours d'émission. */
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
                // Chaque géométrie ouvre un tronçon : deux trajets d'un même fichier
                // ne doivent jamais être reliés par un trait, et c'est aussi ce qui
                // permet à chacun de porter sa propre couleur.
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
                // Refusée si illisible ou transparente : on retombe alors sur la
                // couleur de la palette plutôt que sur un tracé invisible.
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
 * Découpe un flux de coordonnées KML ("lon,lat[,alt]" séparés par des blancs) sans
 * jamais matérialiser le bloc entier. Tolère les espaces autour des virgules, comme
 * le faisait la normalisation par expression régulière précédente.
 */
private class CoordinateTokenizer(
    private val onPoint: (lon: Double, lat: Double, ele: Double) -> Unit
) {
    private val token = StringBuilder(64)
    private var sawSpace = false

    fun reset() {
        token.setLength(0)
        sawSpace = false
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
                    // Un blanc ne sépare deux points que s'il ne borde pas une virgule.
                    if (c != ',' && token[token.length - 1] != ',') {
                        emit()
                    }
                }
                token.append(c)
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
