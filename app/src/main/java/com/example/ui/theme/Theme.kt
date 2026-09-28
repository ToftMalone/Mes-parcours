package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = DarkPrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainerHighest,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = LightSurface,
    surfaceDim = Color(0xFFD6DBD6),
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = LightPrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkOutlineVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = Color(0xFF353B38),
    surfaceDim = DarkSurface,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest
)

/**
 * Formes de la maquette : « Moyen » 16 dp (pastilles, champs), « Grand » 28 dp
 * (cartes, groupes de réglages), « Très grand » 36 dp (panneau de statistiques,
 * dialogues). Les boutons, la barre de navigation et les puces actives sont des
 * pilules pleines, que les composants Material dessinent déjà ainsi.
 */
val MesParcoursShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Couleur de l'enregistrement en cours ; voir [RecordingLight]. */
val LocalRecordingColor = staticCompositionLocalOf { RecordingLight }

@Composable
fun MyApplicationTheme(
    // Dépend du réglage de mode nuit : thème du système ou capteur de luminosité.
    darkTheme: Boolean = rememberIsDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Palette fixe, et plus de couleur dynamique (Material You) sur Android 12+ :
    // choix de l'auteur lors de la refonte, pour une allure identique quel que soit
    // le fond d'écran du téléphone. Voir Color.kt.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // Exposé à tout l'arbre : ce qui n'est pas stylé par Material — le filtre de
    // tuiles de la carte, notamment — doit s'accorder au même état.
    CompositionLocalProvider(
        LocalIsDarkTheme provides darkTheme,
        LocalRecordingColor provides if (darkTheme) RecordingDark else RecordingLight
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = MesParcoursShapes,
            content = content
        )
    }
}
