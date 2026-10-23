package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.example.ui.component.DARK_TILES_COLOR_FILTER
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

/**
 * Le thème sombre de la carte assombrit les tuiles Mapnik par une matrice de
 * couleurs. Trois exigences se sont succédé, et les tests fixent la dernière :
 *
 * 1. `TilesOverlay.INVERT_COLORS`, une inversion nue, teintait les forêts en magenta
 *    et l'eau en brun — inverser retourne la teinte autant que la clarté.
 * 2. Désaturer puis inverser supprimait la dominante, mais aussi toute couleur.
 * 3. Inverser puis tourner les teintes d'un demi-tour rend les couleurs de Mapnik
 *    assombries : c'est le comportement attendu ici.
 *
 * Les assertions portent donc à la fois sur ce qu'il faut obtenir (des teintes
 * reconnaissables et sombres) et sur les deux travers à ne pas retrouver (le magenta,
 * et l'absence de couleur).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DarkTilesColorFilterTest {

    /** Couleurs réellement présentes sur une tuile OpenStreetMap standard. */
    private val grassGreen = Color.rgb(200, 230, 160)
    private val waterBlue = Color.rgb(170, 211, 223)
    private val paperBeige = Color.rgb(242, 239, 233)
    private val midGrey = Color.rgb(128, 128, 128)

    /** Fait passer une couleur unie au travers du filtre et renvoie le résultat. */
    private fun applyFilter(color: Int): Int {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(
            0f, 0f, 1f, 1f,
            Paint().apply {
                this.color = color
                colorFilter = DARK_TILES_COLOR_FILTER
            }
        )
        return bitmap.getPixel(0, 0)
    }

    /** Écart entre la composante la plus forte et la plus faible : 0 = gris pur. */
    private fun chroma(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun luminance(color: Int): Int =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()

    private fun describe(color: Int): String =
        "r=${Color.red(color)} g=${Color.green(color)} b=${Color.blue(color)}"

    @Test
    fun `le gazon ressort en vert fonce`() {
        val filtered = applyFilter(grassGreen)
        val r = Color.red(filtered)
        val g = Color.green(filtered)
        val b = Color.blue(filtered)

        assertTrue("le vert doit rester la composante dominante : ${describe(filtered)}", g > r && g > b)
        assertTrue("et le résultat doit être sombre : ${luminance(filtered)}", luminance(filtered) < 90)
    }

    @Test
    fun `l eau ressort en bleu sombre et non en brun`() {
        val filtered = applyFilter(waterBlue)
        val r = Color.red(filtered)
        val b = Color.blue(filtered)

        // L'inversion nue rendait l'eau brune, c'est-à-dire rouge dominant sur bleu.
        assertTrue("le bleu doit dominer le rouge : ${describe(filtered)}", b > r)
        assertTrue("et le résultat doit être sombre : ${luminance(filtered)}", luminance(filtered) < 90)
    }

    @Test
    fun `le vert ne devient plus magenta`() {
        // Le défaut d'origine : une inversion pure donne du magenta, soit rouge et
        // bleu nettement au-dessus du vert.
        val filtered = applyFilter(grassGreen)
        val r = Color.red(filtered)
        val g = Color.green(filtered)
        val b = Color.blue(filtered)

        assertTrue("dominante magenta détectée : ${describe(filtered)}", !(r > g && b > g))
    }

    @Test
    fun `les couleurs ne sont pas effacees`() {
        // La variante désaturée rendait un gris parfait. Les teintes doivent
        // désormais rester franchement identifiables.
        for ((name, color) in listOf("gazon" to grassGreen, "eau" to waterBlue)) {
            val filtered = applyFilter(color)
            assertTrue(
                "$name doit garder de la couleur, chroma obtenu = ${chroma(filtered)}",
                chroma(filtered) >= 15
            )
        }
    }

    @Test
    fun `le fond clair devient sombre`() {
        val filtered = applyFilter(paperBeige)
        assertTrue(
            "le beige du papier doit ressortir sombre, luminance = ${luminance(filtered)}",
            luminance(filtered) < 40
        )

        // L'ordre des clartés est renversé : ce qui était le plus clair sur la tuile
        // est désormais le plus sombre.
        assertTrue(
            "le fond, plus clair que le gazon, doit ressortir plus sombre que lui",
            luminance(filtered) < luminance(applyFilter(grassGreen))
        )
    }

    @Test
    fun `les gris restent neutres`() {
        // Chaque ligne de la matrice de teinte somme à 1 : un gris ne doit pas prendre
        // de dominante, sans quoi les routes et les libellés se coloreraient.
        val filtered = applyFilter(midGrey)
        assertTrue(
            "un gris moyen doit rester neutre : ${describe(filtered)}",
            chroma(filtered) <= 3
        )
    }
}
