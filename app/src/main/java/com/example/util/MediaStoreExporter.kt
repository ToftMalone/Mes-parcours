package com.example.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.Writer

object MediaStoreExporter {
    private const val TAG = "MediaStoreExporter"
    /**
     * Sous-dossier de destination dans Téléchargements.
     *
     * Le renommage de l'application l'a fait changer : les sauvegardes produites
     * avant restent dans l'ancien dossier `Sillage`, elles ne sont pas déplacées.
     */
    private const val SUBFOLDER = "Mes parcours"

    /** Nom de repli, si le nom demandé ne laisse rien d'exploitable une fois nettoyé. */
    private const val FALLBACK_NAME = "parcours"

    /**
     * Neutralise un nom de fichier avant de l'écrire.
     *
     * Le seul appelant actuel compose ce nom à partir d'une date, donc sans surprise
     * possible. Mais la fonction est publique et invite à lui passer un nom venu
     * d'ailleurs — un nom de parcours, par exemple, qui peut avoir été lu dans un
     * fichier importé. Sans ce nettoyage, la branche Android 9 et antérieur écrivait
     * `File(targetDir, fileName)` sans rien vérifier : un nom portant un séparateur
     * de chemin sortait alors du dossier visé. Le garde-fou qu'assurait
     * `safeFileName` a disparu avec la fonction de partage ; il vaut mieux le tenir
     * ici, au seul endroit qui ouvre le fichier, que dans chaque appelant.
     *
     * Sont remplacés les séparateurs et les caractères interdits par les systèmes de
     * fichiers usuels, ainsi que les caractères de contrôle. Une fois les séparateurs
     * partis, `..` ne désigne plus rien d'atteignable.
     */
    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
            .trim()
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) FALLBACK_NAME else cleaned
    }

    /**
     * Destination ouverte dans Téléchargements/[SUBFOLDER] : son flux, ce qui la publie
     * une fois complète, et ce qui l'efface si l'écriture échoue.
     */
    private class Destination(val stream: OutputStream, val publish: () -> Unit, val discard: () -> Unit)

    private fun openOutput(context: Context, fileName: String, mimeType: String): Destination? {
        val safeName = sanitizeFileName(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
                // Caché tant qu'il s'écrit : ni l'explorateur de fichiers ni une autre
                // application ne voient un fichier à moitié écrit.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            val stream = try {
                resolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                null
            }
            if (stream == null) {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            Destination(
                stream,
                publish = {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                },
                discard = { runCatching { resolver.delete(uri, null, null) } }
            )
        } else {
            // Repli pour Android 9 et antérieur
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, SUBFOLDER)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val file = File(targetDir, safeName)
            Destination(FileOutputStream(file), publish = {}, discard = { file.delete() })
        }
    }

    /**
     * Variante en flux : [write] produit le contenu au fur et à mesure dans le Writer.
     * Permet d'exporter une trace de plusieurs millions de points sans jamais
     * construire le fichier entier en mémoire.
     *
     * Un échec efface le fichier entamé. Il restait jusqu'ici dans Téléchargements,
     * tronqué mais avec le nom d'une sauvegarde normale : la seule copie qu'on croyait
     * avoir pouvait être un fichier illisible.
     */
    suspend fun saveToLocalDownloadsStreaming(
        context: Context,
        fileName: String,
        mimeType: String,
        write: suspend (Writer) -> Unit
    ): Boolean {
        val destination = try {
            openOutput(context, fileName, mimeType)
        } catch (e: Exception) {
            com.example.util.DebugLog.e("Export", "Impossible d'ouvrir $fileName", e)
            null
        } ?: return false
        return try {
            destination.stream.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    write(writer)
                }
            }
            destination.publish()
            Log.d(TAG, "Successfully streamed $fileName")
            com.example.util.DebugLog.d("Export", "Fichier écrit : $fileName")
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save file $fileName locally", e)
            com.example.util.DebugLog.e("Export", "Échec de l'écriture de $fileName", e)
            destination.discard()
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}
