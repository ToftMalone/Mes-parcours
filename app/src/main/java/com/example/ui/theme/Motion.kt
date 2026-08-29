package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * Vocabulaire de mouvement de l'application, repris des jetons de Material 3.
 *
 * Même raison d'être que `Type.kt` pour la typographie : les durées et les courbes
 * étaient jusqu'ici écrites à la main à chaque appel — `tween(220)`, `tween(180)`,
 * `tween(150)` — sans que rien ne dise pourquoi 220 plutôt que 180. Des animations
 * réglées séparément finissent par ne plus se ressembler, et l'interface donne
 * l'impression d'être faite de plusieurs applications.
 *
 * **Règle de fluidité, valable pour tout ce qui s'anime ici** : n'animer que ce que
 * la carte graphique sait faire seule — opacité, échelle, translation, via
 * `graphicsLayer`. Animer une taille, une marge ou un poids relance une mesure de
 * mise en page à chaque image, et c'est la cause la plus courante de saccade. Cette
 * distinction compte doublement dans cette application : l'écran d'enregistrement se
 * recompose à chaque position GPS, soit deux fois par seconde.
 */
object AppMotion {

    // ------------------------------------------------------------------
    // Courbes
    //
    // « Emphasized » pour ce qui entre et sort de l'écran — un départ lent puis une
    // arrivée franche, qui donne au mouvement l'air d'obéir à une inertie.
    // « Standard » pour les changements plus discrets, à l'intérieur d'un écran.
    // ------------------------------------------------------------------

    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ------------------------------------------------------------------
    // Durées, en millisecondes
    //
    // Ce qui disparaît va toujours plus vite que ce qui apparaît : l'œil n'a pas à
    // attendre que l'ancien contenu ait fini de partir pour lire le nouveau.
    // ------------------------------------------------------------------

    /** Réaction immédiate : ce qui s'efface, une icône qui change. */
    const val DurationShort = 100

    /** Le cas courant : un contenu qui apparaît à l'intérieur d'un écran. */
    const val DurationMedium = 200

    /** Changement d'écran, où le mouvement doit rester lisible. */
    const val DurationLong = 300

    // ------------------------------------------------------------------
    // Ressorts
    // ------------------------------------------------------------------

    /**
     * Réponse à un appui : un ressort, pas une durée fixe.
     *
     * Une durée fixe suppose que le geste s'arrête au moment prévu ; un ressort
     * repart de la vitesse en cours, si bien qu'un doigt qui relâche à mi-course ne
     * provoque pas de rupture. C'est ce qui distingue une animation qui suit le
     * doigt d'une animation qui se contente de se jouer.
     *
     * Sans rebond ([Spring.DampingRatioNoBouncy]) : un bouton de commande n'a pas à
     * tressauter, surtout celui qui arrête un enregistrement.
     */
    val PressSpring: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** Échelle d'un bouton pendant l'appui — assez pour se sentir, pas pour se voir. */
    const val PressedScale = 0.94f
}
