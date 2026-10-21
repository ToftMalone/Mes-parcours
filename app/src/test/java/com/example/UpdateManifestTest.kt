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
 * The update file comes from the network and decides what the app
 * will offer to download and then install — the only place in the project where
 * data read elsewhere determines what will run on the device.
 *
 * An incomplete or malformed manifest, one without a fingerprint, or one pointing anywhere but to
 * GitHub over HTTPS must therefore never lead to a proposal.
 *
 * Robolectric is needed for `org.json`, which is an Android class.
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
          "notes": ["First change", "Second change"]
        }
    """.trimIndent()

    /** A complete manifest of which only one field is changed. */
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
    fun `a complete manifest is read`() {
        val update = UpdateManifest.parse(validJson)!!
        assertEquals(9, update.versionCode)
        assertEquals("0.9.9-thierry", update.versionName)
        assertTrue(update.apkUrl.endsWith("app.apk"))
        assertEquals(validSha, update.sha256)
        assertEquals(listOf("First change", "Second change"), update.notes)
    }

    @Test
    fun `notes are optional`() {
        val json = """
            {"versionCode": 9, "versionName": "0.9.9", "apkUrl": "$validUrl", "sha256": "$validSha"}
        """.trimIndent()
        val update = UpdateManifest.parse(json)!!
        assertTrue(update.notes.isEmpty())
    }

    @Test
    fun `an incomplete manifest is rejected`() {
        val cases = mapOf(
            "without versionCode" to """{"versionName": "1.0", "apkUrl": "$validUrl", "sha256": "$validSha"}""",
            "versionCode nul" to manifest(versionCode = "0"),
            "without versionName" to """{"versionCode": 9, "apkUrl": "$validUrl", "sha256": "$validSha"}""",
            "without apkUrl" to """{"versionCode": 9, "versionName": "1.0", "sha256": "$validSha"}""",
            "without sha256" to """{"versionCode": 9, "versionName": "1.0", "apkUrl": "$validUrl"}"""
        )
        for ((label, json) in cases) {
            assertNull("must be rejected: $label", UpdateManifest.parse(json))
        }
    }

    @Test
    fun `an insecure url is rejected`() {
        // We are going to download and then install this file: the transport must be
        // authenticated, otherwise any intermediary could replace it.
        assertNull(UpdateManifest.parse(manifest(apkUrl = "http://github.com/a/b/a.apk")))
    }

    @Test
    fun `a url served by anything other than github is rejected`() {
        // A tampered manifest must not be able to hijack the installation to an
        // arbitrary server.
        assertNull(UpdateManifest.parse(manifest(apkUrl = "https://exemple.test/a.apk")))
    }

    @Test
    fun `a host that imitates github is rejected`() {
        // The trap that comparing the address text would let through:
        // these three start with “https://github.com” or contain it, without
        // being GitHub. The check therefore covers the parsed host.
        val imitations = listOf(
            "https://github.com.exemple.test/a/b/a.apk",
            "https://github.com%2eexemple.test/a/b/a.apk",
            "https://exemple.test/https://github.com/a/b/a.apk"
        )
        for (url in imitations) {
            assertNull("must be rejected: $url", UpdateManifest.parse(manifest(apkUrl = url)))
        }
    }

    @Test
    fun `an apk from another github repository is rejected`() {
        // Anyone can publish an APK on github.com: the host is not enough,
        // only the releases of this repository are accepted.
        val ailleurs = listOf(
            "https://github.com/quelquun/autre/releases/download/v1/app.apk",
            "https://github.com/ToftMalone/autre/releases/download/v1/app.apk",
            "https://github.com/ToftMalone/Mes-parcours/raw/main/app.apk",
            "https://github.com/ToftMalone/Mes-parcours-faux/releases/download/v1/app.apk"
        )
        for (url in ailleurs) {
            assertNull("must be rejected: $url", UpdateManifest.parse(manifest(apkUrl = url)))
        }
    }

    @Test
    fun `the repository prefix cannot be bypassed`() {
        // Each starts with the right prefix, or seems to, but designates once
        // resolved something other than a release of this repository.
        val contournements = listOf(
            "https://github.com/ToftMalone/Mes-parcours/releases/download/../../../autre/depot/releases/download/v1/app.apk",
            "https://github.com/ToftMalone/Mes-parcours/releases/download/./../../x/app.apk",
            "https://github.com/ToftMalone/Mes-parcours/releases/download/%2e%2e/%2e%2e/x/app.apk",
            "https://github.com/ToftMalone%2FMes-parcours/releases/download/v1/app.apk",
            "https://quelquun@github.com/ToftMalone/Mes-parcours/releases/download/v1/app.apk",
            "https://github.com:8443/ToftMalone/Mes-parcours/releases/download/v1/app.apk"
        )
        for (url in contournements) {
            assertNull("must be rejected: $url", UpdateManifest.parse(manifest(apkUrl = url)))
        }
    }

    @Test
    fun `the address produced by release yml is accepted`() {
        // The exact form the publishing workflow writes: the constraint must
        // never refuse it, otherwise no update would ever be offered.
        val url = "https://github.com/ToftMalone/Mes-parcours/releases/download/v1.4/mes-parcours-1.4.apk"
        assertEquals(url, UpdateManifest.parse(manifest(apkUrl = url))?.apkUrl)
        // Explicit port 443 and a different case of the account: same destination.
        val variante = "https://github.com:443/toftmalone/mes-parcours/releases/download/v1.4/mes-parcours-1.4.apk"
        assertEquals(variante, UpdateManifest.parse(manifest(apkUrl = variante))?.apkUrl)
    }

    @Test
    fun `malformed json does not make the app fail`() {
        assertNull(UpdateManifest.parse(""))
        assertNull(UpdateManifest.parse("not json"))
        assertNull(UpdateManifest.parse("{"))
    }

    @Test
    fun `only a strictly newer version is offered`() {
        val update = AvailableUpdate(9, "0.9.9", validUrl, emptyList(), validSha)
        assertTrue("9 > 8", update.isNewerThan(8))
        assertTrue("no offer for an equal version", !update.isNewerThan(9))
        assertTrue("no going backward", !update.isNewerThan(10))
    }

    @Test
    fun `the comparison is on the number and not on the text`() {
        // “0.9.10” is more recent than “0.9.9”, whereas a string comparison
        // would conclude the opposite. That is why versionCode is authoritative.
        val ten = AvailableUpdate(10, "0.9.10-thierry", validUrl, emptyList(), validSha)
        assertTrue(ten.isNewerThan(9))
    }

    // ------------------------------------------------------------------
    // Fingerprint of the APK. Mandatory: without it, the download would only be
    // checked by its size, a useless check with chunked encoding.
    // ------------------------------------------------------------------

    @Test
    fun `a valid fingerprint is retained`() {
        assertEquals(validSha, UpdateManifest.parse(manifest(sha256 = validSha))?.sha256)
    }

    @Test
    fun `an uppercase fingerprint is brought back to lowercase`() {
        // sha256sum produces lowercase, and it is to this form that the
        // download is compared. Comparing without normalizing would always fail.
        val update = UpdateManifest.parse(manifest(sha256 = "A".repeat(64)))
        assertEquals("a".repeat(64), update?.sha256)
    }

    @Test
    fun `a manifest without a fingerprint is rejected`() {
        // Offering an update that cannot be verified would be worse than offering
        // nothing: the size check alone lets a truncated file through
        // as soon as the server answers with chunked encoding.
        val json = """
            {"versionCode": 9, "versionName": "1.0", "apkUrl": "$validUrl", "notes": []}
        """.trimIndent()
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun `a malformed fingerprint is rejected`() {
        assertNull(UpdateManifest.parse(manifest(sha256 = "too short")))
        assertNull(UpdateManifest.parse(manifest(sha256 = "z".repeat(64))))
        assertNull(UpdateManifest.parse(manifest(sha256 = "a".repeat(63))))
    }

    @Test
    fun `translated notes are chosen by language with an English fallback`() {
        val update = UpdateManifest.parse(
            validJson.replace(
                "\"notes\": [\"First change\", \"Second change\"]",
                "\"notes\": [\"First change\"], \"notesI18n\": {\"fr\": [\"Première nouveauté\"], \"ES\": [\"Primera novedad\"], \"de\": []}"
            )
        )!!
        assertEquals(listOf("Première nouveauté"), update.notesFor("fr"))
        assertEquals(listOf("Primera novedad"), update.notesFor("es"))
        // An empty or missing translation falls back on the English notes.
        assertEquals(listOf("First change"), update.notesFor("de"))
        assertEquals(listOf("First change"), update.notesFor("ja"))
    }
}
