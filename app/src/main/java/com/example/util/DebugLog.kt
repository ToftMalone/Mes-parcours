package com.example.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import com.example.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Journal de débogage, demandé par l'auteur : « trouver quand mon père rencontre des
 * problèmes ». Jusqu'ici, un défaut se diagnostiquait sur des captures de SMS et des
 * suppositions (voir les leçons de méthode de CLAUDE.md) ; le journal donne l'erreur
 * exacte, l'enchaînement des gestes et les durées.
 *
 * **Très verbeux par choix**, mais **borné à [MAX_TOTAL_BYTES]** (50 Mo, ramené de 200 Mo à la demande de l'auteur) : des fichiers
 * tournants de [MAX_FILE_BYTES], les plus anciens effacés dès que le total déborderait.
 *
 * - Écriture sur un fil unique, jamais sur l'appelant : une ligne ne coûte à l'écran
 *   que la mise en file d'une chaîne. Les lignes sont vidées sur le disque par paquets,
 *   dès que la file se tarit.
 * - Un plantage est écrit **avant** que l'application ne meure ([installCrashHandler]) :
 *   c'est la ligne qui compte le plus.
 * - Stockage interne (`filesDir/logs`), illisible par les autres applications. Rien ne
 *   quitte le téléphone sans un geste de l'utilisateur : l'export ([buildExport]) est
 *   partagé ou enregistré à la main. Le journal contient des positions : c'est voulu
 *   (le défaut à trouver est souvent sur la carte), et c'est la raison de ce confinement.
 */
object DebugLog {
    const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    internal const val MAX_FILE_BYTES = 5L * 1024 * 1024

    /** Les bornes en vigueur ; réduites par les tests, qui n'écrivent pas 50 Mo. */
    @androidx.annotation.VisibleForTesting
    internal var maxTotalBytes = MAX_TOTAL_BYTES
    @androidx.annotation.VisibleForTesting
    internal var maxFileBytes = MAX_FILE_BYTES
    private const val DIR = "logs"
    private const val EXPORT_DIR = "log_export"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "journal").apply { isDaemon = true }
    }

    @Volatile
    private var logDir: File? = null

    // Ces champs ne sont touchés que depuis le fil du journal.
    private var writer: BufferedWriter? = null
    private var currentSize = 0L
    private var flushScheduled = false
    private var fileSequence = 0
    private val lineTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.FRANCE)

    /** À appeler le plus tôt possible (`TrackApplication.onCreate`). Rejouable. */
    fun init(context: Context) {
        val dir = File(context.filesDir, DIR)
        if (logDir?.path == dir.path) return
        // Un autre dossier ne se voit que dans les tests, où chaque application a le sien.
        val first = logDir == null
        runCatching {
            executor.submit {
                runCatching { writer?.close() }
                writer = null
            }.get(2, TimeUnit.SECONDS)
        }
        logDir = dir.apply { mkdirs() }
        if (first) installCrashHandler()
        i("Appli", "===== Démarrage du processus ===== ${environment(context)}")
    }

    fun d(tag: String, message: String) = enqueue('D', tag, message, null)
    fun i(tag: String, message: String) = enqueue('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = enqueue('W', tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = enqueue('E', tag, message, error)

    /** Exécute [block] et note sa durée ; une exception est notée puis relancée. */
    inline fun <T> timed(tag: String, label: String, block: () -> T): T {
        val start = System.nanoTime()
        return try {
            block().also { d(tag, "$label : ${(System.nanoTime() - start) / 1_000_000} ms") }
        } catch (t: Throwable) {
            e(tag, "$label : échec après ${(System.nanoTime() - start) / 1_000_000} ms", t)
            throw t
        }
    }

    private fun enqueue(level: Char, tag: String, message: String, error: Throwable?) {
        if (logDir == null) return
        val now = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        val trace = error?.let(::stackTrace)
        runCatching { executor.execute { append(now, level, thread, tag, message, trace) } }
    }

    private fun append(time: Long, level: Char, thread: String, tag: String, message: String, trace: String?) {
        try {
            val out = writer ?: openNewFile() ?: return
            val line = buildString {
                append(lineTime.format(Date(time))).append(' ').append(level).append(' ')
                append('[').append(thread).append("] ").append(tag).append(" : ").append(message).append('\n')
                if (trace != null) append(trace).append('\n')
            }
            out.write(line)
            // En octets et non en caractères : les accents comptent double, et la borne
            // de 50 Mo est une promesse de place sur le disque.
            currentSize += line.encodeToByteArray().size
            if (level == 'E' || level == 'W') {
                out.flush()
            } else if (!flushScheduled) {
                // Mis en file derrière les lignes déjà en attente : un seul vidage par paquet.
                flushScheduled = true
                executor.execute {
                    flushScheduled = false
                    runCatching { writer?.flush() }
                }
            }
            if (currentSize >= maxFileBytes) {
                runCatching { out.close() }
                writer = null
            }
        } catch (_: Exception) {
            // Le journal ne doit jamais faire tomber l'application, même disque plein.
            runCatching { writer?.close() }
            writer = null
        }
    }

    /** Ouvre un fichier neuf, après avoir fait la place pour qu'il tienne sous la borne. */
    private fun openNewFile(): BufferedWriter? {
        val dir = logDir ?: return null
        dir.mkdirs()
        val existing = logFiles(dir).toMutableList()
        var total = existing.sumOf { it.length() }
        while (existing.isNotEmpty() && total + maxFileBytes > maxTotalBytes) {
            val oldest = existing.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
        // Le numéro départage deux fichiers ouverts dans la même milliseconde ; le nom
        // reste triable, donc l'ordre chronologique se lit dans la liste des fichiers.
        val name = "journal-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.FRANCE).format(Date()) +
            "-" + (fileSequence++).toString().padStart(6, '0') + ".txt"
        currentSize = 0L
        return BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, name), true), Charsets.UTF_8), 64 * 1024)
            .also { writer = it }
    }

    /** Les fichiers du journal, du plus ancien au plus récent (le nom porte la date). */
    private fun logFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith("journal-") }?.sortedBy { it.name }.orEmpty()

    /** Attend que tout ce qui a été noté soit sur le disque. */
    @androidx.annotation.VisibleForTesting
    internal fun flushNow(timeoutMs: Long = 3_000) {
        runCatching {
            executor.submit { runCatching { writer?.flush() } }.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Écrit et vidé avant de rendre la main : le processus meurt juste après.
            enqueue('E', "PLANTAGE", "Exception non rattrapée sur le fil « ${thread.name} »", error)
            flushNow(2_000)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stackTrace(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    /** Taille totale du journal sur le disque, en octets. */
    fun totalSize(context: Context): Long =
        logFiles(File(context.filesDir, DIR)).sumOf { it.length() }

    /** Efface tout le journal (le fichier en cours repart de zéro). */
    fun clear(context: Context) {
        runCatching {
            executor.submit {
                runCatching { writer?.close() }
                writer = null
                logFiles(File(context.filesDir, DIR)).forEach { it.delete() }
            }.get(5, TimeUnit.SECONDS)
        }
        i("Journal", "Journal effacé à la demande")
    }

    /**
     * Fabrique l'archive à envoyer : tous les fichiers du journal, un état des lieux
     * ([snapshot], écrit par l'appelant qui a accès à la base) et la sortie `logcat` du
     * processus, qui garde ce que les bibliothèques et Android ont écrit de leur côté.
     * À appeler hors du fil principal. Les archives précédentes sont effacées : une
     * seule à la fois, pour ne pas doubler la place prise.
     */
    fun buildExport(context: Context, snapshot: String): File {
        i("Journal", "Export du journal demandé")
        flushNow()
        val exportDir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
        exportDir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.FRANCE).format(Date())
        val zip = File(exportDir, "journal-mes-parcours-$stamp.zip")
        ZipOutputStream(FileOutputStream(zip).buffered()).use { out ->
            out.putNextEntry(ZipEntry("etat.txt"))
            out.write(("${environment(context)}\n\n$snapshot\n").toByteArray())
            out.closeEntry()

            runCatching { readLogcat() }.getOrNull()?.let { logcat ->
                out.putNextEntry(ZipEntry("logcat.txt"))
                out.write(logcat)
                out.closeEntry()
            }

            logFiles(File(context.filesDir, DIR)).forEach { file ->
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        i("Journal", "Archive prête : ${zip.name}, ${zip.length() / 1024} Kio")
        return zip
    }

    /** Ce que le processus a écrit dans le journal d'Android (ses seules lignes : Android 4.1+). */
    private fun readLogcat(): ByteArray {
        val process = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
        )
        val bytes = process.inputStream.use { it.readBytes() }
        process.waitFor(5, TimeUnit.SECONDS)
        return bytes
    }

    /** Version, appareil, mémoire et place libre : la première chose à lire d'un journal. */
    fun environment(context: Context): String {
        val memory = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val rt = Runtime.getRuntime()
            "mémoire appareil ${info.availMem / MB}/${info.totalMem / MB} Mo libres (bas : ${info.lowMemory}), " +
                "tas Java ${(rt.totalMemory() - rt.freeMemory()) / MB}/${rt.maxMemory() / MB} Mo"
        }.getOrDefault("mémoire inconnue")
        val storage = runCatching {
            val fs = StatFs(context.filesDir.absolutePath)
            "stockage ${fs.availableBytes / MB}/${fs.totalBytes / MB} Mo libres"
        }.getOrDefault("stockage inconnu")
        return "Mes parcours ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · $memory · $storage"
    }

    private const val MB = 1024L * 1024
}
