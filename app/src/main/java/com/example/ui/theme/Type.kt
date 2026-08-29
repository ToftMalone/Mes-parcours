package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Échelle typographique de l'application.
 *
 * Elle a longtemps été celle de l'échafaudage — une seule taille redéfinie, tout le
 * reste laissé aux valeurs de Material. Les écrans compensaient chacun de leur côté
 * en posant `fontWeight` et `fontSize` à la main, et surtout en mettant
 * `FontWeight.Black` un peu partout : la hiérarchie se faisait à la graisse, faute
 * d'échelle pour la porter. D'où une interface qui parlait fort au lieu de parler
 * clair, et des titres qui changeaient d'aspect d'un écran à l'autre.
 *
 * Les tailles restent proches de celles de Material pour ne déplacer aucune mise en
 * page ; ce qui change, c'est que les graisses et les interlettrages sont désormais
 * décidés **ici**, une fois, au lieu d'être répétés à chaque appel.
 */

/**
 * Chiffres à chasse fixe.
 *
 * Sans cela, un « 1 » est plus étroit qu'un « 8 » : à chaque seconde d'un
 * enregistrement, la distance et la vitesse changent de largeur et le texte tressaute
 * horizontalement. `tnum` donne à tous les chiffres la même largeur, et la colonne se
 * fige. C'est autant de la lisibilité que de l'esthétique — on lit ces nombres en
 * marchant.
 *
 * Fourni par la police système, il n'y a donc aucun fichier de police à embarquer.
 */
private const val TABULAR_FIGURES = "tnum"

private val Default = FontFamily.Default

val Typography = Typography(
    // ---- Grands nombres et titres d'accueil ----
    displayLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 52.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    displayMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 42.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.75).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    displaySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),

    // ---- Titres d'écran ----
    headlineLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    headlineMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    headlineSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),

    // ---- Titres de section et de carte ----
    //
    // `titleLarge` porte la graisse que les écrans posaient jusqu'ici à la main :
    // c'est le style des en-têtes d'écran (« Outils », « Découper un parcours »).
    titleLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.25).sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    titleMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    titleSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),

    // ---- Texte courant ----
    //
    // L'interlettrage de Material (0,5 sp sur `bodyLarge`) est trop large pour des
    // libellés courts, qui sont l'essentiel de cette application : resserré.
    bodyLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp
    ),

    // ---- Libellés, boutons ----
    labelLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    labelMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = TABULAR_FIGURES
    ),
    labelSmall = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
        fontFeatureSettings = TABULAR_FIGURES
    )
)

/**
 * Styles propres à l'application, là où l'échelle de Material n'a pas de case.
 *
 * Les chiffres d'un traqueur GPS ne sont ni un titre ni un libellé : ce sont des
 * mesures, affichées grand et relues en marchant. Les définir ici plutôt qu'en
 * `TextStyle` écrit à la main dans chaque écran évite que la même mesure change de
 * taille selon l'écran qui l'affiche — ce qui était le cas jusqu'ici.
 */
object AppTextStyles {

    /** Mesure mise en avant : la distance et la durée d'un parcours, dans son détail. */
    val statLarge = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = TABULAR_FIGURES
    )

    /** Mesure suivie en direct pendant un enregistrement. */
    val statMedium = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = TABULAR_FIGURES
    )

    /**
     * Libellé de section, en petites capitales espacées — le « DISTANCE PARCOURUE »
     * qui coiffe une mesure.
     */
    val overline = TextStyle(
        fontFamily = Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp
    )
}
