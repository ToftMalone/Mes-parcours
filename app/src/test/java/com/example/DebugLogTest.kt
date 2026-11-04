package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.DebugLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * Le journal de débogage : borné quoi qu'on y écrive, tournant sur plusieurs fichiers,
 * exporté en une archive complète. Bornes réduites (le principe est le même qu'à
 * 200 Mo, sans écrire 200 Mo).
 */
@RunWith(RobolectricTestRunner::class)
class DebugLogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DebugLog.init(context)
        DebugLog.clear(context)
        DebugLog.maxFileBytes = 10 * 1024
        DebugLog.maxTotalBytes = 50 * 1024
    }

    @After
    fun tearDown() {
        DebugLog.maxFileBytes = 10L * 1024 * 1024
        DebugLog.maxTotalBytes = DebugLog.MAX_TOTAL_BYTES
        DebugLog.clear(context)
    }

    private fun logFiles() = File(context.filesDir, "logs").listFiles().orEmpty().filter { it.name.startsWith("journal-") }

    @Test
    fun `le journal ne dépasse jamais sa borne, et garde les lignes les plus récentes`() {
        repeat(3_000) { DebugLog.d("Test", "ligne numéro $it, avec des accents éèà pour compter en octets") }
        DebugLog.flushNow()

        val total = DebugLog.totalSize(context)
        assertTrue("journal de $total octets pour une borne de ${50 * 1024}", total <= 50 * 1024 + 200)
        assertTrue("plusieurs fichiers tournants", logFiles().size > 1)
        val all = logFiles().sortedBy { it.name }.joinToString("") { it.readText() }
        assertTrue("la dernière ligne est gardée", all.contains("ligne numéro 2999,"))
        assertTrue("les plus anciennes sont parties", !all.contains("ligne numéro 0,"))
    }

    @Test
    fun `une erreur est notée avec sa pile d'appels`() {
        DebugLog.e("Test", "quelque chose a échoué", IllegalStateException("cause précise"))
        DebugLog.flushNow()

        val all = logFiles().joinToString("") { it.readText() }
        assertTrue(all.contains(" E [") && all.contains("Test : quelque chose a échoué"))
        assertTrue(all.contains("java.lang.IllegalStateException: cause précise"))
        assertTrue(all.contains("at com.example.DebugLogTest"))
    }

    @Test
    fun `l'archive contient l'état des lieux et tout le journal`() {
        repeat(500) { DebugLog.i("Test", "action $it") }
        val zip = DebugLog.buildExport(context, "état de test")

        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertTrue("etat.txt" in names)
            val journal = names.filter { it.startsWith("journal-") }
            assertTrue(journal.isNotEmpty())
            val etat = archive.getInputStream(archive.getEntry("etat.txt")).bufferedReader().readText()
            assertTrue(etat.contains("état de test") && etat.contains("Mes parcours"))
            val text = journal.sorted().joinToString("") { archive.getInputStream(archive.getEntry(it)).bufferedReader().readText() }
            assertTrue(text.contains("action 499"))
        }
    }

    @Test
    fun `effacer vide le journal`() {
        repeat(100) { DebugLog.i("Test", "à effacer $it") }
        DebugLog.flushNow()
        DebugLog.clear(context)
        DebugLog.flushNow()

        val all = logFiles().joinToString("") { it.readText() }
        assertTrue(!all.contains("à effacer"))
        assertEquals(true, all.contains("Journal effacé") || all.isEmpty())
    }
}
