package com.example.util.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
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
     * Empreinte SHA-256 de l'APK, en hexadécimal minuscule.
     *
     * **Obligatoire.** Le téléchargement n'est présenté à l'installateur que s'il
     * correspond. Android refuserait de toute façon un APK signé par une autre clé,
     * mais cette vérification-ci écarte plus tôt le cas bien plus banal du fichier
     * tronqué ou corrompu en route — que le seul contrôle de taille laisse passer
     * quand le serveur répond en découpage par blocs, `contentLength` valant alors -1.
     *
     * Elle était facultative pour que les publications antérieures à son
     * introduction restent installables. Ce ménagement n'a plus lieu d'être :
     * l'application ne consulte jamais que `releases/latest`, et toute publication
     * produite par `release.yml` porte une empreinte. Une publication qui n'en
     * porterait pas serait donc anormale, et refuser la mise à jour vaut mieux que
     * l'installer sans rien vérifier.
     */
    val sha256: String
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
 *   "sha256": "3b1f…",
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
            if (!apkUrl.isTrustedApkUrl()) return null

            val notesArray = root.optJSONArray("notes")
            val notes = buildList {
                if (notesArray != null) {
                    for (i in 0 until notesArray.length()) {
                        val line = notesArray.optString(i)
                        if (line.isNotBlank()) add(line)
                    }
                }
            }

            // Empreinte obligatoire, et rejetée si elle est malformée : sans elle, le
            // téléchargement ne serait vérifié que par sa taille — contrôle inopérant
            // quand le serveur répond en découpage par blocs. Mieux vaut ne pas
            // proposer la mise à jour que la proposer sans pouvoir la vérifier.
            val sha256 = root.optString("sha256").lowercase()
            if (!sha256.isValidSha256()) return null

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
 * L'APK doit être servi en HTTPS **et** par GitHub.
 *
 * Ce fichier va être installé : c'est le seul endroit du projet où une adresse lue
 * ailleurs décide de ce qui s'exécutera sur l'appareil. Exiger HTTPS authentifie le
 * transport ; contraindre l'hôte fait qu'un manifeste altéré ne peut pas rediriger
 * l'installation vers un serveur quelconque.
 *
 * La comparaison porte sur l'hôte analysé, jamais sur le texte de l'adresse :
 * `https://github.com.exemple.test/` commence bien par « https://github.com » sans
 * être GitHub pour autant.
 */
private fun String.isTrustedApkUrl(): Boolean {
    if (!startsWith("https://")) return false
    val host = try {
        URI(this).host
    } catch (e: Exception) {
        null
    } ?: return false
    return host.equals("github.com", ignoreCase = true)
}

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
     * Longueur maximale acceptée pour le manifeste, en caractères.
     *
     * Il fait quelques centaines de caractères ; 65 536 laissent une marge large.
     * Sans plafond, la lecture avalait ce qu'on lui servait : une réponse anormale —
     * serveur détourné, page d'erreur volumineuse — faisait grossir la mémoire
     * jusqu'au plantage, pour une requête que l'utilisateur n'a même pas demandée.
     */
    private const val MAX_MANIFEST_CHARS = 64 * 1024

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

            // Lecture bornée : un manifeste plus gros que prévu est refusé plutôt que
            // chargé en entier. On lit un caractère de plus que le plafond pour
            // distinguer « pile à la limite » de « tronqué ».
            val body = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(MAX_MANIFEST_CHARS + 1)
                var filled = 0
                while (filled < buffer.size) {
                    val read = reader.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (filled > MAX_MANIFEST_CHARS) null else String(buffer, 0, filled)
            } ?: return@withContext null

            UpdateManifest.parse(body)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
