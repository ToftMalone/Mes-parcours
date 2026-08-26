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
 * proposera de télécharger puis d'installer — le seul endroit du projet où une
 * donnée lue ailleurs détermine ce qui s'exécutera sur l'appareil.
 *
 * Un manifeste incomplet, malformé, sans empreinte, ou pointant ailleurs que vers
 * GitHub en HTTPS ne doit donc jamais aboutir à une proposition.
 *
 * Robolectric est nécessaire pour `org.json`, qui est une classe Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManifestTest {

    private val validSha = "a".repeat(64)
    private val validUrl = "https://github.com/ToftMalone/Mes-parcours/releases/download/v0.9.9/app.apk"

    private val validJson = """
        {
          "versionCode": 9,
          "versionName": "0.9.9-thierry",
          "apkUrl": "$validUrl",
          "sha256": "$validSha",
          "notes": ["Première nouveauté", "Deuxième nouveauté"]
        }
    """.trimIndent()

    /** Un manifeste complet dont on ne change qu'un champ. */
    private fun manifest(
        versionCode: String = "9",
        versionName: String = "0.9.9",
        apkUrl: String = validUrl,
        sha256: String = validSha
    ) = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkUrl": "$apkUrl",
          "sha256": "$sha256",
          "notes": []
        }
    """.trimIndent()

    @Test
    fun `un manifeste complet est lu`() {
        val update = UpdateManifest.parse(validJson)!!
        assertEquals(9, update.versionCode)
        assertEquals("0.9.9-thierry", update.versionName)
        assertTrue(update.apkUrl.endsWith("app.apk"))
        assertEquals(validSha, update.sha256)
        assertEquals(listOf("Première nouveauté", "Deuxième nouveauté"), update.notes)
    }

    @Test
    fun `les notes sont facultatives`() {
        val json = """
            {"versionCode": 9, "versionName": "0.9.9", "apkUrl": "$validUrl", "sha256": "$validSha"}
        """.trimIndent()
        val update = UpdateManifest.parse(json)!!
        assertTrue(update.notes.isEmpty())
    }

    @Test
    fun `un manifeste incomplet est rejete`() {
        val cases = mapOf(
            "sans versionCode" to """{"versionName": "1.0", "apkUrl": "$validUrl", "sha256": "$validSha"}""",
            "versionCode nul" to manifest(versionCode = "0"),
            "sans versionName" to """{"versionCode": 9, "apkUrl": "$validUrl", "sha256": "$validSha"}""",
            "sans apkUrl" to """{"versionCode": 9, "versionName": "1.0", "sha256": "$validSha"}""",
            "sans sha256" to """{"versionCode": 9, "versionName": "1.0", "apkUrl": "$validUrl"}"""
        )
        for ((label, json) in cases) {
            assertNull("doit être rejeté : $label", UpdateManifest.parse(json))
        }
    }

    @Test
    fun `une url non securisee est rejetee`() {
        // On va télécharger puis installer ce fichier : le transport doit être
        // authentifié, sans quoi n'importe quel intermédiaire pourrait le remplacer.
        assertNull(UpdateManifest.parse(manifest(apkUrl = "http://github.com/a/b/a.apk")))
    }

    @Test
    fun `une url servie ailleurs que par github est rejetee`() {
        // Un manifeste altéré ne doit pas pouvoir détourner l'installation vers un
        // serveur quelconque.
        assertNull(UpdateManifest.parse(manifest(apkUrl = "https://exemple.test/a.apk")))
    }

    @Test
    fun `un hote qui imite github est rejete`() {
        // Le piège que la comparaison sur le texte de l'adresse laisserait passer :
        // ces trois-là commencent par « https://github.com » ou le contiennent, sans
        // être GitHub. La vérification porte donc sur l'hôte analysé.
        val imitations = listOf(
            "https://github.com.exemple.test/a/b/a.apk",
            "https://github.com%2eexemple.test/a/b/a.apk",
            "https://exemple.test/https://github.com/a/b/a.apk"
        )
        for (url in imitations) {
            assertNull("doit être rejeté : $url", UpdateManifest.parse(manifest(apkUrl = url)))
        }
    }

    @Test
    fun `un json malforme ne fait pas echouer l application`() {
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("pas du json"))
        assertNull(UpdateManifest.parse("{"))
    }

    @Test
    fun `seule une version strictement plus recente est proposee`() {
        val update = AvailableUpdate(9, "0.9.9", validUrl, emptyList(), validSha)
        assertTrue("9 > 8", update.isNewerThan(8))
        assertTrue("pas de proposition à version égale", !update.isNewerThan(9))
        assertTrue("pas de retour en arrière", !update.isNewerThan(10))
    }

    @Test
    fun `la comparaison porte sur le numero et non sur le texte`() {
        // « 0.9.10 » est plus récent que « 0.9.9 », alors qu'une comparaison de
        // chaînes conclurait l'inverse. C'est pourquoi versionCode fait foi.
        val ten = AvailableUpdate(10, "0.9.10-thierry", validUrl, emptyList(), validSha)
        assertTrue(ten.isNewerThan(9))
    }

    // ------------------------------------------------------------------
    // Empreinte de l'APK. Obligatoire : sans elle, le téléchargement ne serait
    // vérifié que par sa taille, contrôle inopérant en découpage par blocs.
    // ------------------------------------------------------------------

    @Test
    fun `une empreinte valide est retenue`() {
        assertEquals(validSha, UpdateManifest.parse(manifest(sha256 = validSha))?.sha256)
    }

    @Test
    fun `une empreinte en majuscules est ramenee en minuscules`() {
        // sha256sum produit des minuscules, et c'est à cette forme que le
        // téléchargement se compare. Comparer sans normaliser échouerait toujours.
        val update = UpdateManifest.parse(manifest(sha256 = "A".repeat(64)))
        assertEquals("a".repeat(64), update?.sha256)
    }

    @Test
    fun `un manifeste sans empreinte est rejete`() {
        // Proposer une mise à jour que l'on ne saura pas vérifier serait pire que ne
        // rien proposer : le contrôle de taille seul laisse passer un fichier tronqué
        // dès que le serveur répond en découpage par blocs.
        val json = """
            {"versionCode": 9, "versionName": "1.0", "apkUrl": "$validUrl", "notes": []}
        """.trimIndent()
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun `une empreinte malformee est rejetee`() {
        assertNull(UpdateManifest.parse(manifest(sha256 = "trop court")))
        assertNull(UpdateManifest.parse(manifest(sha256 = "z".repeat(64))))
        assertNull(UpdateManifest.parse(manifest(sha256 = "a".repeat(63))))
    }
}
