package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryGreen,
    onPrimary = OnPrimaryGreen,
    primaryContainer = Color(0x2622C55E),
    onPrimaryContainer = PrimaryGreen,
    secondary = OverlaySecondary,
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0x26818CF8),
    onSecondaryContainer = OverlaySecondary,
    tertiary = AmberAccent,
    onTertiary = Color(0xFF241804),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF272017),
    onSurfaceVariant = DarkOnSurfaceSecondary,
    outline = SoftBorder,
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0x26EF4444),
    onErrorContainer = AlertRed
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryGreen,
    onPrimary = OnPrimaryGreen,
    primaryContainer = Color(0xFFD7F5E3),
    onPrimaryContainer = Color(0xFF0B3B22),
    secondary = OverlaySecondary,
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFFE3E4FB),
    onSecondaryContainer = Color(0xFF2A2A6B),
    tertiary = AmberAccent,
    onTertiary = Color(0xFF241804),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFF2EBE0),
    onSurfaceVariant = LightOnSurfaceSecondary,
    outline = SoftBorder,
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0xFFFCDCDC),
    onErrorContainer = Color(0xFF7A1414)
)

@Composable
fun MyApplicationTheme(
    // Dépend du réglage de mode nuit : thème du système ou capteur de luminosité.
    darkTheme: Boolean = rememberIsDarkTheme(),
    // Les couleurs dynamiques (Material You, Android 12+) écraseraient la
    // palette « chaleureuse » par celle tirée du fond d'écran de l'utilisateur —
    // c'est exactement ce que la refonte du thème cherche à éviter. Désactivées
    // par défaut pour que la palette choisie s'affiche vraiment ; l'appelant
    // peut toujours la réactiver explicitement.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Exposé à tout l'arbre : ce qui n'est pas stylé par Material — le filtre de
    // tuiles de la carte, notamment — doit s'accorder au même état.
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
