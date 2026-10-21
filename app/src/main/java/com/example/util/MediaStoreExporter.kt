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
     * Destination subfolder in Downloads.
     *
     * The app's renaming changed it: backups produced
     * before stay in the old `Sillage` folder, they are not moved.
     */
    private const val SUBFOLDER = "Mes parcours"

    /** Fallback name, if the requested name leaves nothing usable once cleaned. */
    private const val FALLBACK_NAME = "parcours"

    /**
     * Neutralizes a file name before writing it.
     *
     * The only current caller composes this name from a date, so no surprise
     * is possible. But the function is public and invites passing it a name from
     * elsewhere — a track name, for example, which may have been read from an
     * imported file. Without this cleaning, the Android 9 and earlier branch wrote
     * `File(targetDir, fileName)` with no check: a name carrying a path separator
     * then escaped the target folder. The safeguard that
     * `safeFileName` provided disappeared with the sharing function; it is better to keep it
     * here, at the only place that opens the file, than in each caller.
     *
     * Separators and characters forbidden by common file systems are replaced,
     * as are control characters. Once the separators are
     * gone, `..` designates nothing reachable.
     */
    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
            .trim()
        return if (cleaned.isEmpty() || cleaned.all { it == '.' }) FALLBACK_NAME else cleaned
    }

    /** Opens the destination stream in Downloads/[SUBFOLDER]. */
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
     * Streaming variant: [write] produces the content as it goes into the Writer.
     * Makes it possible to export a track of several million points without ever
     * building the whole file in memory.
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
