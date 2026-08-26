package com.example.util.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Empreinte en hexadécimal minuscule, la forme qu'emploie `sha256sum`. */
private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

/**
 * Téléchargement de l'APK d'une nouvelle version, puis passage de relais à
 * l'installateur d'Android.
 *
 * L'installation silencieuse est impossible pour une application ordinaire : le
 * système affiche toujours son propre écran de confirmation. Tout ce que l'on peut
 * faire, c'est lui présenter le fichier.
 */
object UpdateDownloader {

    private const val TIMEOUT_MS = 15_000
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Dossier de destination, dans le stockage **interne** de l'application.
     *
     * Le stockage externe conviendrait par bien des aspects — aucune permission
     * requise, effacé avec l'application — mais il reste modifiable par toute autre
     * application détenant la permission de stockage sur Android 9 et antérieur, que
     * l'on continue de couvrir. Or l'empreinte est vérifiée pendant l'écriture,
     * plusieurs secondes avant que l'utilisateur n'appuie sur « Installer » : dans
     * cet intervalle, un fichier posé là pouvait être remplacé, et la vérification
     * déjà faite ne protégeait plus rien. Android refuse bien un APK signé d'une
     * autre clé **portant le même nom de paquet**, mais un APK au nom de paquet
     * différent s'installerait sans obstacle — validé de confiance par un
     * utilisateur en pleine mise à jour.
     *
     * Le stockage interne, lui, est inaccessible aux autres applications sur toutes
     * les versions d'Android. D'où aussi l'entrée `files-path` de `file_paths.xml` :
     * c'est le FileProvider qui expose ensuite le fichier au seul installateur.
     */
    private fun downloadDir(context: Context): File =
        File(context.filesDir, "updates")

    private fun apkFile(context: Context, update: AvailableUpdate): File? {
        val dir = downloadDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "mes-parcours-${update.versionCode}.apk")
    }

    /**
     * Télécharge l'APK en signalant l'avancement de 0 à 1.
     *
     * Renvoie null si le téléchargement échoue. L'écriture se fait dans un fichier
     * temporaire renommé à la fin : un téléchargement interrompu ne laisse jamais un
     * APK tronqué que l'on tenterait ensuite d'installer.
     */
    suspend fun download(
        context: Context,
        update: AvailableUpdate,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val target = apkFile(context, update) ?: return@withContext null
        val partial = File(target.parentFile, target.name + ".part")

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val total = connection.contentLength.toLong()
            var downloaded = 0L

            // Empreinte calculée pendant l'écriture : relire le fichier ensuite
            // doublerait les entrées-sorties pour rien.
            val digest = MessageDigest.getInstance("SHA-256")

            partial.delete()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // Rend le téléchargement annulable : fermer la boîte de
                        // dialogue annule la coroutine, et l'on s'arrête ici.
                        coroutineContext.ensureActive()

                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }

            // Taille annoncée non respectée : le fichier est incomplet.
            //
            // Ce contrôle ne suffit pas à lui seul : `contentLength` vaut -1 quand le
            // serveur répond en découpage par blocs, et l'on ne saurait alors pas
            // qu'un téléchargement s'est arrêté en route. C'est l'empreinte qui
            // tranche vraiment, quand la publication en annonce une.
            if (total > 0 && partial.length() != total) {
                partial.delete()
                return@withContext null
            }

            if (update.sha256 != digest.digest().toHexString()) {
                // Fichier corrompu, tronqué, ou servi par autre chose que la
                // publication attendue : on ne le présente pas à l'installateur.
                partial.delete()
                return@withContext null
            }

            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext null
            }
            onProgress(1f)
            target
        } catch (e: Exception) {
            partial.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Depuis Android 8, installer un APK exige que l'utilisateur ait autorisé cette
     * application précise à installer des applications inconnues.
     */
    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Écran de réglages où accorder cette autorisation. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Intention ouvrant l'installateur système sur l'APK téléchargé.
     *
     * Le fichier est exposé par le FileProvider : passer un `file://` déclencherait
     * une FileUriExposedException depuis Android 7.
     */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Emplacement des téléchargements avant le passage au stockage interne.
     *
     * Une installation mise à jour depuis une version antérieure y garde un APK d'une
     * vingtaine de mégaoctets que plus rien ne viendrait effacer. On le nettoie donc
     * en même temps que les autres, une fois pour toutes.
     */
    private fun legacyDownloadDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** Supprime les APK déjà téléchargés : ils ne servent plus après installation. */
    fun clearDownloads(context: Context) {
        for (dir in listOfNotNull(downloadDir(context), legacyDownloadDir(context))) {
            dir.listFiles()
                ?.filter { it.name.startsWith("mes-parcours-") }
                ?.forEach { it.delete() }
        }
    }
}
