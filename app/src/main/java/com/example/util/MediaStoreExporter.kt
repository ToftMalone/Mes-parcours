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

    /** Ouvre le flux de destination dans Téléchargements/[SUBFOLDER]. */
    private fun openOutput(context: Context, fileName: String, mimeType: String): OutputStream? {
        val safeName = sanitizeFileName(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null
            resolver.openOutputStream(uri)
        } else {
            // Legacy fallback for Android 9 and lower
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, SUBFOLDER)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            FileOutputStream(File(targetDir, safeName))
        }
    }

    /**
     * Variante en flux : [write] produit le contenu au fur et à mesure dans le Writer.
     * Permet d'exporter une trace de plusieurs millions de points sans jamais
     * construire le fichier entier en mémoire.
     */
    suspend fun saveToLocalDownloadsStreaming(
        context: Context,
        fileName: String,
        mimeType: String,
        write: suspend (Writer) -> Unit
    ): Boolean {
        return try {
            val output = openOutput(context, fileName, mimeType) ?: return false
            output.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    write(writer)
                }
            }
            Log.d(TAG, "Successfully streamed $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save file $fileName locally", e)
            false
        }
    }
}
