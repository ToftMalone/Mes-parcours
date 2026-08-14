package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    primaryContainer = androidx.compose.ui.graphics.Color(0x2610B981),
    onPrimaryContainer = EmeraldPrimary,
    secondary = IndigoSecondary,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0x266366F1),
    onSecondaryContainer = IndigoSecondary,
    tertiary = AmberTertiary,
    onTertiary = androidx.compose.ui.graphics.Color.Black,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E293B),
    onSurfaceVariant = DarkOnSurfaceSecondary,
    outline = SoftGrayBorderDev
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD1FAE5),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF065F46),
    secondary = IndigoSecondary,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E7FF),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF3730A3),
    tertiary = AmberTertiary,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
    onSurfaceVariant = LightOnSurfaceSecondary,
    outline = SoftGrayBorderDev
)

@Composable
fun MyApplicationTheme(
    // Dépend du réglage de mode nuit : thème du système ou capteur de luminosité.
    darkTheme: Boolean = rememberIsDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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
