package com.example.util.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Où l'application va chercher la dernière version publiée.
 *
 * Tant que [GITHUB_OWNER] ou [GITHUB_REPO] est vide, la recherche est désactivée :
 * aucune requête n'est émise, et l'application se comporte exactement comme avant.
 */
object UpdateConfig {

    /** Compte GitHub hébergeant les publications. */
    const val GITHUB_OWNER = "ToftMalone"

    /** Dépôt GitHub hébergeant les publications. */
    const val GITHUB_REPO = "Mes-parcours"

    /** Nom du fichier décrivant la version, joint à chaque publication. */
    const val MANIFEST_ASSET = "update.json"

    /**
     * `releases/latest/download/<fichier>` est une URL stable : GitHub la redirige
     * toujours vers la pièce jointe de la publication la plus récente. On évite ainsi
     * l'API GitHub, ses quotas et son jeton d'authentification.
     */
    val manifestUrl: String?
        get() = if (GITHUB_OWNER.isBlank() || GITHUB_REPO.isBlank()) {
            null
        } else {
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/$MANIFEST_ASSET"
        }

    val isConfigured: Boolean get() = manifestUrl != null
}

/** Une version publiée, telle que décrite par le fichier de mise à jour. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: List<String>,
    /**
     * Empreinte SHA-256 de l'APK, en hexadécimal minuscule, ou null si la publication
     * n'en annonce pas — les versions publiées avant l'ajout de ce champ.
     *
     * Quand elle est présente, le téléchargement n'est présenté à l'installateur que
     * s'il correspond. Android refuserait de toute façon un APK signé par une autre
     * clé, mais cette vérification-ci écarte plus tôt le cas bien plus banal du
     * fichier tronqué ou corrompu en route.
     */
    val sha256: String? = null
)

/**
 * Lecture du fichier de mise à jour.
 *
 * Format attendu, à joindre à chaque publication GitHub sous le nom `update.json` :
 *
 * ```json
 * {
 *   "versionCode": 9,
 *   "versionName": "0.9.9-thierry",
 *   "apkUrl": "https://github.com/…/releases/download/v0.9.9-thierry/mes-parcours.apk",
 *   "notes": ["Première nouveauté", "Deuxième nouveauté"]
 * }
 * ```
 *
 * Sans dépendance Android hors `org.json` : directement testable.
 */
object UpdateManifest {

    /** Renvoie null si le fichier est illisible ou incomplet. */
    fun parse(json: String): AvailableUpdate? {
        return try {
            val root = JSONObject(json)
            val versionCode = root.optInt("versionCode", -1)
            val versionName = root.optString("versionName")
            val apkUrl = root.optString("apkUrl")

            // Un manifeste incomplet ne doit jamais donner lieu à une proposition de
            // mise à jour : on préfère ne rien signaler qu'envoyer l'utilisateur vers
            // un téléchargement inexistant.
            if (versionCode <= 0 || versionName.isBlank() || apkUrl.isBlank()) return null
            if (!apkUrl.startsWith("https://")) return null

            val notesArray = root.optJSONArray("notes")
            val notes = buildList {
                if (notesArray != null) {
                    for (i in 0 until notesArray.length()) {
                        val line = notesArray.optString(i)
                        if (line.isNotBlank()) add(line)
                    }
                }
            }

            // Empreinte facultative : les publications antérieures à son introduction
            // n'en portent pas, et doivent rester installables. Une empreinte présente
            // mais malformée est en revanche ignorée plutôt que retenue telle quelle —
            // la comparer échouerait toujours, rendant la mise à jour ininstallable.
            val sha256 = root.optString("sha256").lowercase().takeIf { it.isValidSha256() }

            AvailableUpdate(versionCode, versionName, apkUrl, notes, sha256)
        } catch (e: Exception) {
            null
        }
    }
}

/** Une empreinte SHA-256 fait 64 caractères hexadécimaux, et rien d'autre. */
private fun String.isValidSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

/**
 * Une mise à jour n'est proposée que si elle porte un numéro de version strictement
 * supérieur à celui installé. La comparaison se fait sur `versionCode`, un entier :
 * comparer des chaînes comme « 0.9.10 » et « 0.9.9 » se tromperait.
 */
fun AvailableUpdate.isNewerThan(installedVersionCode: Int): Boolean =
    versionCode > installedVersionCode

object UpdateChecker {

    private const val TIMEOUT_MS = 8_000

    /**
     * Va chercher la description de la dernière version publiée.
     *
     * Requête GET seule, vers une URL fixe : rien n'est transmis depuis l'appareil,
     * ni identifiant, ni position. Renvoie null en cas d'échec réseau — une recherche
     * de mise à jour qui échoue ne doit jamais déranger l'utilisateur.
     */
    suspend fun fetchLatest(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val url = UpdateConfig.manifestUrl ?: return@withContext null
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            UpdateManifest.parse(body)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
