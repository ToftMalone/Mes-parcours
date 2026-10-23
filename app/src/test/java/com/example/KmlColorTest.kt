package com.example

import com.example.util.KmlColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le piège de ce format tient en une phrase : KML écrit AABBGGRR, Android attend
 * ARGB. Les deux premiers tests sont là pour qu'une recopie naïve — qui échangerait
 * le rouge et le bleu — ne puisse pas passer inaperçue.
 */
class KmlColorTest {

    @Test
    fun `le rouge KML devient du rouge ARGB`() {
        // ff0000ff : opaque, bleu 00, vert 00, rouge ff.
        assertEquals(0xFFFF0000.toInt(), KmlColor.parse("ff0000ff"))
    }

    @Test
    fun `le bleu KML devient du bleu ARGB`() {
        // ffff0000 : opaque, bleu ff, vert 00, rouge 00.
        assertEquals(0xFF0000FF.toInt(), KmlColor.parse("ffff0000"))
    }

    @Test
    fun `le vert reste au milieu, donc inchangé`() {
        assertEquals(0xFF00FF00.toInt(), KmlColor.parse("ff00ff00"))
    }

    @Test
    fun `la transparence partielle est conservée`() {
        assertEquals(0x800000FF.toInt(), KmlColor.parse("80ff0000"))
    }

    @Test
    fun `une couleur totalement transparente est refusee`() {
        // La respecter donnerait un tracé invisible, que l'utilisateur lirait comme
        // une trace perdue plutôt que comme un choix de couleur.
        assertNull(KmlColor.parse("00ff0000"))
    }

    @Test
    fun `le croisillon et les blancs sont tolérés`() {
        assertEquals(0xFFFF0000.toInt(), KmlColor.parse("  #ff0000ff \n"))
    }

    @Test
    fun `la casse hexadécimale est indifférente`() {
        assertEquals(KmlColor.parse("ff0000ff"), KmlColor.parse("FF0000FF"))
    }

    @Test
    fun `une chaîne inexploitable ne donne pas de couleur`() {
        assertNull(KmlColor.parse(null))
        assertNull(KmlColor.parse(""))
        assertNull(KmlColor.parse("ff00ff"))       // trop courte
        assertNull(KmlColor.parse("ff0000ffff"))   // trop longue
        assertNull(KmlColor.parse("zz0000ff"))     // pas de l'hexadécimal
    }
}
