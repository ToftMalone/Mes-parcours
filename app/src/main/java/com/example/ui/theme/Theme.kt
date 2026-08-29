@file:Suppress("DEPRECATION")

package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * D'où viennent les couleurs de l'application.
 *
 * Sur Android 12 et suivants, Material You les tire du fond d'écran : deux
 * installations de « Mes parcours » n'ont alors pas la même allure, et la palette
 * définie dans `Color.kt` n'est jamais affichée. C'est le comportement voulu par
 * Android, et celui de l'application depuis toujours — mais il n'était pas offert au
 * choix, alors qu'il décide de tout ce que l'utilisateur voit.
 *
 * Le réglage n'a aucun sens en deçà d'Android 12, où les couleurs dynamiques
 * n'existent pas : l'écran des réglages n'y propose donc pas la carte.
 */
object AppColorPreferences {

    internal const val KEY_DYNAMIC_COLORS = "pref_dynamic_colors"

    /** Les couleurs dynamiques sont-elles seulement possibles sur cet appareil ? */
    fun isDynamicColorAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Par défaut `true` : c'est ce que faisait l'application avant l'existence du
     * réglage, et une mise à jour ne doit pas changer l'apparence sans qu'on l'ait
     * demandé.
     */
    fun isDynamicColorEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_DYNAMIC_COLORS, true)

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_DYNAMIC_COLORS, enabled)
            .apply()
    }
}

/**
 * Le réglage de couleurs courant, réévalué dès que l'utilisateur le change.
 *
 * Sans cette écoute, basculer le réglage n'aurait d'effet qu'au prochain démarrage —
 * exactement le défaut que `rememberNightModeSource` évite déjà pour le mode nuit.
 */
@Composable
private fun rememberDynamicColorEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    var enabled by remember(context) {
        mutableStateOf(AppColorPreferences.isDynamicColorEnabled(context))
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AppColorPreferences.KEY_DYNAMIC_COLORS) {
                enabled = AppColorPreferences.isDynamicColorEnabled(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    return enabled
}

@Composable
fun MyApplicationTheme(
    // Dépend du réglage de mode nuit : thème du système ou capteur de luminosité.
    darkTheme: Boolean = rememberIsDarkTheme(),
    // Couleurs du fond d'écran (Material You, Android 12+) ou palette de
    // l'application : au choix de l'utilisateur.
    dynamicColor: Boolean = rememberDynamicColorEnabled(),
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
