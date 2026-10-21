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

// Typography of the redesign, taken from the “Design system” board:
// Bricolage Grotesque for figures and titles, Figtree for the interface.
// Two variable fonts embedded in res/font, under the SIL OFL 1.1 license (texts
// in licenses/).
//
// **Bricolage carries three axes** — weight, width (75 to 100) and optical size —
// and the mockup uses them: big figures are condensed (width 80), titles
// a little less (85). Android does not pick a width or an optical size
// on its own: each combination is therefore declared as its own family, with its
// axis settings frozen.
//
// Axes only apply from Android 8 (API 26). Below — minSdk 24 —
// the file is read in its default instance: the right font, but neither
// condensed nor bolded. Accepted degradation, these versions being marginal.
//
// `Font(resId, …, variationSettings)` is still marked experimental in the
// project's version of Compose (BOM 2024.09): accepting it explicitly is required,
// compilation fails otherwise. The API has existed since Compose 1.2 and has not moved
// since; the accepted risk is that of a signature tweak on a version
// upgrade, which compilation would flag at once.

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

/** Big live figures (“3.42 km”): 750, width 80, optical size 72. */
val StatFontFamily = FontFamily(bricolage(750, 80f, 72f))

/** Screen titles and medium figures: width 85, in 700 and 750. */
val DisplayFontFamily = FontFamily(
    bricolage(700, 85f, 36f),
    bricolage(750, 85f, 48f)
)

/** Section and sheet titles (“Resume a track”): normal width. */
val TitleFontFamily = FontFamily(bricolage(700, 100f, 24f))

/** Quote of the dedication (“About”): 500, slightly condensed. */
val QuoteFontFamily = FontFamily(bricolage(500, 92f, 24f))

/** Interface font. */
val FigtreeFontFamily = FontFamily(
    figtree(400),
    figtree(500),
    figtree(600),
    figtree(700)
)

/**
 * Fixed-width figures: a scrolling duration (“00:24:18”) must not make
 * its surroundings dance every second.
 */
private const val TABULAR_NUMBERS = "tnum"

/**
 * Style of the big figures (“Stat XL” from the mockup). Outside the Material scale,
 * which has no role that large: to be called explicitly.
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
    // “Display”: welcome title (“Mes parcours”).
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
    // “Headline”: tab titles (“History”, “Settings”).
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
    // “Stat M”: secondary figures (duration, speed, altitude).
    headlineSmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 26.sp,
        lineHeight = 30.sp,
        fontFeatureSettings = TABULAR_NUMBERS
    ),
    // “Title”: sheet, dialog and card titles.
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
    // “Body”: 15/22 in 400.
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
    // “Label L”: 700, 16 — buttons, menu entries.
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
    // “Label S”: 700, 12, spaced by 0.6 — chips (“RECORDING”).
    labelSmall = TextStyle(
        fontFamily = FigtreeFontFamily,
        fontWeight = FontWeight(700),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp
    )
)
