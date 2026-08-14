package com.example

import com.example.util.update.AvailableUpdate
import com.example.util.update.UpdateManifest
import com.example.util.update.isNewerThan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le fichier de mise à jour vient du réseau et décide de ce que l'application
 * proposera de télécharger puis d'installer. Un manifeste incomplet, malformé ou
 * pointant ailleurs qu'en HTTPS ne doit jamais aboutir à une proposition.
 *
 * Robolectric est nécessaire pour `org.json`, qui est une classe Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManifestTest {

    private val validJson = """
        {
          "versionCode": 9,
          "versionName": "0.9.9-thierry",
          "apkUrl": "https://github.com/toche/mes-parcours/releases/download/v0.9.9/app.apk",
          "notes": ["Première nouveauté", "Deuxième nouveauté"]
        }
    """.trimIndent()

    @Test
    fun `un manifeste complet est lu`() {
        val update = UpdateManifest.parse(validJson)!!
        assertEquals(9, update.versionCode)
        assertEquals("0.9.9-thierry", update.versionName)
        assertTrue(update.apkUrl.endsWith("app.apk"))
        assertEquals(listOf("Première nouveauté", "Deuxième nouveauté"), update.notes)
    }

    @Test
    fun `les notes sont facultatives`() {
        val json = """
            {"versionCode": 9, "versionName": "0.9.9", "apkUrl": "https://x.test/a.apk"}
        """.trimIndent()
        val update = UpdateManifest.parse(json)!!
        assertTrue(update.notes.isEmpty())
    }

    @Test
    fun `un manifeste incomplet est rejete`() {
        val cases = mapOf(
            "sans versionCode" to """{"versionName": "1.0", "apkUrl": "https://x.test/a.apk"}""",
            "versionCode nul" to """{"versionCode": 0, "versionName": "1.0", "apkUrl": "https://x.test/a.apk"}""",
            "sans versionName" to """{"versionCode": 9, "apkUrl": "https://x.test/a.apk"}""",
            "sans apkUrl" to """{"versionCode": 9, "versionName": "1.0"}"""
        )
        for ((label, json) in cases) {
            assertNull("doit être rejeté : $label", UpdateManifest.parse(json))
        }
    }

    @Test
    fun `une url non securisee est rejetee`() {
        // On va télécharger puis installer ce fichier : le transport doit être
        // authentifié, sans quoi n'importe quel intermédiaire pourrait le remplacer.
        val json = """
            {"versionCode": 9, "versionName": "1.0", "apkUrl": "http://x.test/a.apk"}
        """.trimIndent()
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun `un json malforme ne fait pas echouer l application`() {
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("pas du json"))
        assertNull(UpdateManifest.parse("{"))
    }

    @Test
    fun `seule une version strictement plus recente est proposee`() {
        val update = AvailableUpdate(9, "0.9.9", "https://x.test/a.apk", emptyList())
        assertTrue("9 > 8", update.isNewerThan(8))
        assertTrue("pas de proposition à version égale", !update.isNewerThan(9))
        assertTrue("pas de retour en arrière", !update.isNewerThan(10))
    }

    @Test
    fun `la comparaison porte sur le numero et non sur le texte`() {
        // « 0.9.10 » est plus récent que « 0.9.9 », alors qu'une comparaison de
        // chaînes conclurait l'inverse. C'est pourquoi versionCode fait foi.
        val ten = AvailableUpdate(10, "0.9.10-thierry", "https://x.test/a.apk", emptyList())
        assertTrue(ten.isNewerThan(9))
    }
}
