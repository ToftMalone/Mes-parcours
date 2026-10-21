package com.example.util.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Where the app goes to look for the latest published version.
 *
 * As long as [GITHUB_OWNER] or [GITHUB_REPO] is empty, the search is disabled:
 * no request is issued, and the app behaves exactly as before.
 */
object UpdateConfig {

    /** GitHub account hosting the releases. */
    const val GITHUB_OWNER = "ToftMalone"

    /** GitHub repository hosting the releases. */
    const val GITHUB_REPO = "Mes-parcours"

    /** Name of the file describing the version, attached to each release. */
    const val MANIFEST_ASSET = "update.json"

    /**
     * `releases/latest/download/<file>` is a stable URL: GitHub always redirects it
     * to the attachment of the most recent release. This avoids
     * the GitHub API, its quotas and its authentication token.
     */
    val manifestUrl: String?
        get() = if (GITHUB_OWNER.isBlank() || GITHUB_REPO.isBlank()) {
            null
        } else {
            "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/latest/download/$MANIFEST_ASSET"
        }

    val isConfigured: Boolean get() = manifestUrl != null

    /**
     * Only path from which an APK can be downloaded: the attachments of the
     * releases of **this** repository. See `isTrustedApkUrl`.
     */
    val releaseDownloadPathPrefix: String
        get() = "/$GITHUB_OWNER/$GITHUB_REPO/releases/download/"
}

/** A published version, as described by the update file. */
data class AvailableUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    /** Release notes in English, the fallback for any language without a translation. */
    val notes: List<String>,
    /**
     * SHA-256 fingerprint of the APK, in lowercase hexadecimal.
     *
     * **Mandatory.** The download is only presented to the installer if it
     * matches. Android would refuse an APK signed with another key anyway,
     * but this check rules out earlier the far more common case of a file
     * truncated or corrupted en route — which the size check alone lets through
     * when the server answers with chunked encoding, `contentLength` then being -1.
     *
     * It used to be optional so that releases predating its
     * introduction stayed installable. That leniency no longer applies:
     * the app only ever consults `releases/latest`, and every release
     * produced by `release.yml` carries a fingerprint. A release without one
     * would therefore be abnormal, and refusing the update is better than
     * installing it without checking anything.
     */
    val sha256: String,
    /** Optional translations of [notes], keyed by language code (`fr`, `es`…). */
    val notesI18n: Map<String, List<String>> = emptyMap()
) {
    /**
     * The notes in the phone's language, falling back to the English ones: the
     * workflow only carries the translations the author supplied for a release.
     */
    fun notesFor(language: String): List<String> =
        notesI18n[language.lowercase()]?.takeIf { it.isNotEmpty() } ?: notes
}

/**
 * Reading of the update file.
 *
 * Expected format, to be attached to each GitHub release under the name `update.json`:
 *
 * ```json
 * {
 *   "versionCode": 9,
 *   "versionName": "0.9.9-thierry",
 *   "apkUrl": "https://github.com/…/releases/download/v0.9.9-thierry/mes-parcours.apk",
 *   "sha256": "3b1f…",
 *   "notes": ["First change", "Second change"],
 *   "notesI18n": {"fr": ["Première nouveauté", "Deuxième nouveauté"]}
 * }
 * ```
 *
 * No Android dependency apart from `org.json`: directly testable.
 */
object UpdateManifest {

    private fun readNotes(array: org.json.JSONArray?): List<String> = buildList {
        if (array != null) {
            for (i in 0 until array.length()) {
                val line = array.optString(i)
                if (line.isNotBlank()) add(line)
            }
        }
    }

    /** Returns null if the file is unreadable or incomplete. */
    fun parse(json: String): AvailableUpdate? {
        return try {
            val root = JSONObject(json)
            val versionCode = root.optInt("versionCode", -1)
            val versionName = root.optString("versionName")
            val apkUrl = root.optString("apkUrl")

            // An incomplete manifest must never lead to an update proposal: we
            // prefer reporting nothing to sending the user to a
            // nonexistent download.
            if (versionCode <= 0 || versionName.isBlank() || apkUrl.isBlank()) return null
            if (!apkUrl.isTrustedApkUrl()) return null

            val notes = readNotes(root.optJSONArray("notes"))
            val notesI18n = buildMap {
                val translations = root.optJSONObject("notesI18n")
                translations?.keys()?.forEach { language ->
                    val lines = readNotes(translations.optJSONArray(language))
                    if (lines.isNotEmpty()) put(language.lowercase(), lines)
                }
            }

            // Fingerprint mandatory, and rejected if malformed: without it, the
            // download would only be checked by its size — a useless check
            // when the server answers with chunked encoding. Better not to
            // offer the update than to offer it without being able to verify it.
            val sha256 = root.optString("sha256").lowercase()
            if (!sha256.isValidSha256()) return null

            AvailableUpdate(versionCode, versionName, apkUrl, notes, sha256 = sha256, notesI18n = notesI18n)
        } catch (e: Exception) {
            null
        }
    }
}

/** A SHA-256 fingerprint is 64 hexadecimal characters, and nothing else. */
private fun String.isValidSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

/**
 * The APK must be served over HTTPS **and** by GitHub.
 *
 * This file is going to be installed: it is the only place in the project where an address read
 * elsewhere decides what will run on the device. Requiring HTTPS authenticates the
 * transport; constraining the host means a tampered manifest cannot redirect
 * the installation to an arbitrary server.
 *
 * The comparison is on the parsed host, never on the text of the address:
 * `https://github.com.example.test/` does start with “https://github.com” without
 * being GitHub.
 *
 * **The host is not enough, the path is constrained too** (audit of 1.3): anyone
 * can publish an APK on `github.com`, in their own repository. Only the
 * attachments of this repository's releases ([UpdateConfig.releaseDownloadPathPrefix])
 * are accepted. So that this prefix cannot be bypassed, the raw path must
 * carry neither encoding (`%2e%2e`, `%2f`) nor a `.` or `..` segment — otherwise
 * `/ToftMalone/Mes-parcours/releases/download/../../../other/…` would indeed start with
 * the right prefix while designating, once resolved by the server, a completely different repository.
 * For the same reason, no credentials in the address and no unusual port.
 */
private fun String.isTrustedApkUrl(): Boolean {
    if (!startsWith("https://")) return false
    val uri = try {
        URI(this)
    } catch (e: Exception) {
        null
    } ?: return false

    val host = uri.host ?: return false
    if (!host.equals("github.com", ignoreCase = true)) return false
    if (uri.rawUserInfo != null) return false
    if (uri.port != -1 && uri.port != 443) return false

    val path = uri.rawPath ?: return false
    if ('%' in path) return false
    if (path.split('/').any { it == "." || it == ".." }) return false
    // GitHub does not distinguish the case of the account or repository.
    return path.startsWith(UpdateConfig.releaseDownloadPathPrefix, ignoreCase = true)
}

/**
 * An update is only offered if it carries a version number strictly
 * greater than the installed one. The comparison is on `versionCode`, an integer:
 * comparing strings like “0.9.10” and “0.9.9” would get it wrong.
 */
fun AvailableUpdate.isNewerThan(installedVersionCode: Int): Boolean =
    versionCode > installedVersionCode

object UpdateChecker {

    private const val TIMEOUT_MS = 8_000

    /**
     * Maximum length accepted for the manifest, in characters.
     *
     * It is a few hundred characters; 65,536 leaves a wide margin.
     * Without a cap, the read swallowed whatever it was served: an abnormal response —
     * hijacked server, large error page — made memory grow
     * until a crash, for a request the user did not even ask for.
     */
    private const val MAX_MANIFEST_CHARS = 64 * 1024

    /**
     * Fetches the description of the latest published version.
     *
     * A lone GET request, to a fixed URL: nothing is sent from the device,
     * neither identifier nor position. Returns null on network failure — an update
     * search that fails must never bother the user.
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

            // Bounded read: a manifest bigger than expected is refused rather than
            // loaded in full. We read one character more than the cap to
            // tell “exactly at the limit” from “truncated”.
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
