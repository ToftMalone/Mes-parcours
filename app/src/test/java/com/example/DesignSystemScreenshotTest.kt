package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.ui.component.MainNavigationBar
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StatXlTextStyle
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

/**
 * Captures de la base de la refonte : couleurs, polices, barre de navigation.
 *
 * Sert à **regarder les pixels** sans téléphone sous la main : l'intégration continue
 * enregistre ces images (`-Proborazzi.test.record=true`) et les joint à son
 * exécution, où l'on peut les comparer à la maquette. Rien n'est comparé à une image
 * de référence : ces tests échouent sur une exception, jamais sur un pixel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class DesignSystemScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun capture(name: String, darkTheme: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = darkTheme) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    content()
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/refonte/${name}_${if (darkTheme) "sombre" else "clair"}.png"
        )
    }

    @Composable
    private fun NavigationBars() {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            for (tab in listOf("enregistrer", "historique", "outils", "parametres")) {
                MainNavigationBar(currentTab = tab, onTabSelected = {}, showUpdateBadge = tab != "parametres")
            }
        }
    }

    @Composable
    private fun TypeSpecimen() {
        val t = MaterialTheme.typography
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text("3,42 km", style = StatXlTextStyle, color = MaterialTheme.colorScheme.onSurface)
            Text("Mes parcours", style = t.displayMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("Historique", style = t.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("Reprendre une trace", style = t.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("00:24:18  12,4  212", style = t.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text("Nouveau parcours", style = t.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Tout reste sur votre téléphone, sans compte ni serveur.",
                style = t.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("ENREGISTREMENT", style = t.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            Button(onClick = {}) { Text("Démarrer") }
        }
    }

    @Composable
    private fun Swatches() {
        val c = MaterialTheme.colorScheme
        val rows = listOf(
            listOf(c.primary, c.onPrimary, c.primaryContainer, c.onPrimaryContainer),
            listOf(c.secondary, c.secondaryContainer, c.tertiary, c.tertiaryContainer),
            listOf(c.error, c.errorContainer, c.outline, c.outlineVariant),
            listOf(c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(20.dp)) {
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (color in row) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(color, RoundedCornerShape(16.dp))
                        )
                    }
                }
            }
        }
    }

    @Test fun navigation_clair() = capture("navigation", darkTheme = false) { NavigationBars() }
    @Test fun navigation_sombre() = capture("navigation", darkTheme = true) { NavigationBars() }
    @Test fun typographie_clair() = capture("typographie", darkTheme = false) { TypeSpecimen() }
    @Test fun typographie_sombre() = capture("typographie", darkTheme = true) { TypeSpecimen() }
    @Test fun couleurs_clair() = capture("couleurs", darkTheme = false) { Swatches() }
    @Test fun couleurs_sombre() = capture("couleurs", darkTheme = true) { Swatches() }
}
