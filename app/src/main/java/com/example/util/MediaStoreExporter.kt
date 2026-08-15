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

    /** Ouvre le flux de destination dans Téléchargements/[SUBFOLDER]. */
    private fun openOutput(context: Context, fileName: String, mimeType: String): OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
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
            FileOutputStream(File(targetDir, fileName))
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
