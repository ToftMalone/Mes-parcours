package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// Typographie de la refonte, relevée sur la planche « Système de design » :
// Bricolage Grotesque pour les chiffres et les titres, Figtree pour l'interface.
// Deux polices variables embarquées dans res/font, sous licence SIL OFL 1.1 (textes
// dans licenses/).
//
// **Bricolage porte trois axes** — graisse, largeur (75 à 100) et taille optique —
// et la maquette s'en sert : les grands chiffres sont resserrés (largeur 80), les
// titres un peu moins (85). Android ne choisit pas une largeur ni une taille optique
// tout seul : chaque combinaison est donc déclarée comme sa propre famille, avec ses
// réglages d'axes figés.
//
// Les axes ne s'appliquent qu'à partir d'Android 8 (API 26). En deçà — minSdk 24 —
// le fichier est lu dans son instance par défaut : la bonne police, mais ni
// resserrée ni graissée. Dégradation acceptée, ces versions étant marginales.
//
// `Font(resId, …, variationSettings)` est encore marqué expérimental dans la
// version de Compose du projet (BOM 2024.09) : l'accepter explicitement est exigé,
// la compilation échoue sinon. L'API existe depuis Compose 1.2 et n'a pas bougé
// depuis ; le risque accepté est celui d'une retouche de signature à une montée de
// version, que la compilation signalerait aussitôt.

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int, width: Float, opticalSize: Float) = Font(
    resId = R.font.bricolage_grotesque,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("opsz", opticalSize)
    )
)

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    resId = R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

/** Grands chiffres en direct (« 3,42 km ») : 750, largeur 80, taille optique 72. */
val StatFontFamily = FontFamily(bricolage(750, 80f, 72f))

/** Titres d'écran et chiffres moyens : largeur 85, en 700 et 750. */
val DisplayFontFamily = FontFamily(
    bricolage(700, 85f, 36f),
    bricolage(750, 85f, 48f)
)

/** Titres de section et de feuille (« Reprendre une trace ») : largeur normale. */
val TitleFontFamily = FontFamily(bricolage(700, 100f, 24f))

/** Citation de la dédicace (« À propos ») : 500, légèrement resserrée. */
val QuoteFontFamily = FontFamily(bricolage(500, 92f, 24f))

/** Police de l'interface. */
val FigtreeFontFamily = FontFamily(
    figtree(400),
    figtree(500),
    figtree(600),
    figtree(700)
)

/**
 * Chiffres à chasse fixe : une durée qui défile (« 00:24:18 ») ne doit pas faire
 * danser ce qui l'entoure à chaque seconde.
 */
private const val TABULAR_NUMBERS = "tnum"

/**
 * Style des grands chiffres (« Stat XL » de la maquette). Hors de l'échelle Material,
 * qui n'a pas de rôle aussi grand : à appeler explicitement.
 */
val StatXlTextStyle = TextStyle(
    fontFamily = StatFontFamily,
    fontWeight = FontWeight(750),
    fontSize = 76.sp,
    lineHeight = 76.sp,
    letterSpacing = (-1.5).sp,
    fontFeatureSettings = TABULAR_NUMBERS
)

val Typography = Typography(
    // « Display » : titre d'accueil (« Mes parcours »).
    displayLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(750),
        fontSize = 56.sp,
        lineHeight = 60.sp,
        letterSpacing = (-1.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(750),
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1).sp
    ),
    displaySmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    // « Headline » : titre des onglets (« Historique », « Paramètres »).
    headlineLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 32.sp,
        lineHeight = 36.sp
    ),
    // « Stat M » : chiffres secondaires (durée, vitesse, altitude).
    headlineSmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontFeatureSettings = TABULAR_NUMBERS
    ),
    // « Title » : titres de feuille, de dialogue et de carte.
    titleLarge = TextStyle(
        fontFamily = TitleFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 24.sp,
        lineHeight = 30.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 17.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(600),
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    // « Body » : 15/22 en 400.
    bodyLarge = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(400),
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(400),
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(400),
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    // « Label L » : 700, 16 — boutons, entrées de menu.
    labelLarge = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 16.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(600),
        fontSize = 13.sp,
        lineHeight = 16.sp
    ),
    // « Label S » : 700, 12, espacé de 0,6 — pastilles (« ENREGISTREMENT »).
    labelSmall = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp
    )
)
