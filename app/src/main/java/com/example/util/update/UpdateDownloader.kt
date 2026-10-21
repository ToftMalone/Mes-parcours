package com.example.util.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Fingerprint in lowercase hexadecimal, the form `sha256sum` uses. */
private fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it) }

/**
 * Download of the APK of a new version, then handing over to
 * Android's installer.
 *
 * Silent installation is impossible for an ordinary app: the
 * system always shows its own confirmation screen. All we can
 * do is present it with the file.
 */
object UpdateDownloader {

    private const val TIMEOUT_MS = 15_000
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Destination folder, in the app's **internal** storage.
     *
     * External storage would suit in many respects — no permission
     * required, erased with the app — but it remains modifiable by any other
     * app holding the storage permission on Android 9 and earlier, which
     * we still cover. Yet the fingerprint is checked during writing,
     * several seconds before the user taps “Install”: in that
     * interval, a file placed there could be replaced, and the check
     * already done protected nothing any more. Android does refuse an APK signed with another
     * key **bearing the same package name**, but an APK with a different package
     * name would install without obstacle — trusted by a
     * user in the middle of an update.
     *
     * Internal storage, for its part, is inaccessible to other apps on all
     * versions of Android. Hence also the `files-path` entry of `file_paths.xml`:
     * it is the FileProvider that then exposes the file to the installer alone.
     */
    private fun downloadDir(context: Context): File =
        File(context.filesDir, "updates")

    private fun apkFile(context: Context, update: AvailableUpdate): File? {
        val dir = downloadDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        return File(dir, "mes-parcours-${update.versionCode}.apk")
    }

    /**
     * Downloads the APK while reporting progress from 0 to 1.
     *
     * Returns null if the download fails. Writing goes to a temporary
     * file renamed at the end: an interrupted download never leaves a truncated
     * APK that one would then try to install.
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

            // Fingerprint computed while writing: rereading the file afterwards
            // would double the I/O for nothing.
            val digest = MessageDigest.getInstance("SHA-256")

            partial.delete()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        // Makes the download cancellable: closing the dialog
                        // cancels the coroutine, and we stop here.
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

            // Announced size not met: the file is incomplete.
            //
            // This check is not enough on its own: `contentLength` is -1 when the
            // server answers with chunked encoding, and we would then not know
            // that a download stopped midway. It is the fingerprint that
            // really decides, when the release announces one.
            if (total > 0 && partial.length() != total) {
                partial.delete()
                return@withContext null
            }

            if (update.sha256 != digest.digest().toHexString()) {
                // File corrupted, truncated, or served by something other than the
                // expected release: we do not present it to the installer.
                partial.delete()
                return@withContext null
            }

            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                return@withContext null
            }

            // After the rename, not before: Android's archive parser must
            // only read a file bearing the expected extension.
            if (!isExpectedApk(context, target, update)) {
                target.delete()
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
     * Is the downloaded APK really an update of **this** application, in the
     * announced version?
     *
     * The SHA-256 fingerprint does not answer this question: it comes from the same
     * `update.json` as the APK's address, and anyone who can replace one can
     * replace the other. It rules out a file damaged en route, not a substituted
     * file.
     *
     * What really protects is the signature: Android refuses an update
     * signed with another key — **but only for the same package name**. An APK
     * with a different name would install as a second application, presented to a
     * user who thinks they are updating this one. Hence this check, which brings
     * every accepted file back to the case where Android itself verifies the signature.
     *
     * The certificate is deliberately not compared here: once the package name is
     * guaranteed, that would redo Android's check, with the risk — unverifiable
     * without a device — that a version of Android does not return the signature of an
     * uninstalled archive and wrongly makes every update be refused.
     *
     * The version number must also match that of the manifest: an APK
     * different from the announced one has no legitimate reason to be served.
     *
     * The expected name is that of the release ([BuildConfig.UPDATE_PACKAGE_NAME]), and
     * not `context.packageName`, which carries the `.debug` suffix in a debug APK.
     */
    private fun isExpectedApk(context: Context, apk: File, update: AvailableUpdate): Boolean {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.path, 0)
            }
        } catch (e: Exception) {
            null
        } ?: return false

        return info.packageName == BuildConfig.UPDATE_PACKAGE_NAME &&
            PackageInfoCompat.getLongVersionCode(info) == update.versionCode.toLong()
    }

    /**
     * Since Android 8, installing an APK requires the user to have allowed this
     * specific app to install unknown apps.
     */
    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Settings screen where to grant this permission. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Intent opening the system installer on the downloaded APK.
     *
     * The file is exposed by the FileProvider: passing a `file://` would trigger
     * a FileUriExposedException since Android 7.
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
     * Location of the downloads before the move to internal storage.
     *
     * An installation updated from an earlier version keeps there an APK of about
     * twenty megabytes that nothing would ever delete. It is therefore cleaned
     * along with the others, once and for all.
     */
    private fun legacyDownloadDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** Deletes the APKs already downloaded: they serve no purpose after installation. */
    fun clearDownloads(context: Context) {
        for (dir in listOfNotNull(downloadDir(context), legacyDownloadDir(context))) {
            dir.listFiles()
                ?.filter { it.name.startsWith("mes-parcours-") }
                ?.forEach { it.delete() }
        }
    }
}
