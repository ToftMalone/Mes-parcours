package com.example.ui.component

import android.view.HapticFeedbackConstants
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.LocalRecordingColor
import kotlinx.coroutines.launch

/*
 * Dock flottant, d'après le cahier des charges fourni par l'auteur (tailles, formes,
 * ressorts et durées repris tels quels). Remplace la pilule de la refonte
 * (`MainNavigationBar`).
 *
 * Adaptations à l'application, le reste étant à l'identique :
 * - **Le bouton rond de tête est l'onglet « Enregistrer »**, la carte, qui tient lieu
 *   d'accueil. Le modèle y mettait une grille, puis une croix une fois à l'accueil pour
 *   « fermer la section » : l'application n'a pas de sections, une croix sur la carte
 *   n'aurait rien à fermer. L'icône d'enregistrement passe donc de contour à pleine,
 *   avec la même animation « pop ».
 * - **Trois onglets** derrière lui : Historique, Outils, Paramètres. Quatre onglets plus
 *   ce bouton feraient 360 dp, plus que la largeur de bien des téléphones.
 * - Icônes de Material Icons (contour inactif, pleine active), déjà utilisées partout,
 *   plutôt que la police Material Symbols : le cahier des charges le prévoit.
 * - Couleurs du thème ; ombre teintée selon `LocalIsDarkTheme`, jamais
 *   `isSystemInDarkTheme()` (invariant 9).
 * - Pastilles conservées : mise à jour ignorée sur Paramètres, enregistrement en cours
 *   sur le bouton de tête.
 */

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/** Place à réserver sous le contenu qui défile quand le dock est affiché (sans la barre système). */
val DockReservedHeight: Dp = 128.dp

/** Place à réserver quand le dock est caché. */
val DockHiddenReservedHeight: Dp = 24.dp

private data class DockDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val testTag: String
)

private val HOME = DockDestination(
    "enregistrer", R.string.nav_record, Icons.Outlined.RadioButtonChecked, Icons.Rounded.RadioButtonChecked, "tab_button_tracking"
)

private val TABS = listOf(
    DockDestination("historique", R.string.nav_history, Icons.Outlined.History, Icons.Rounded.History, "tab_button_history"),
    DockDestination("outils", R.string.nav_tools, Icons.Outlined.Handyman, Icons.Rounded.Handyman, "tab_button_tools"),
    DockDestination("parametres", R.string.nav_settings, Icons.Outlined.Settings, Icons.Rounded.Settings, "tab_button_settings")
)

/** Entrée « pop » : échelle 0,4 → 1 et rotation −40° → 0, rejouée quand [key] change. */
@Composable
private fun Modifier.pop(key: Any?): Modifier {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, spring(0.55f, 420f)) }
    return graphicsLayer {
        val v = p.value
        val s = 0.4f + 0.6f * v
        scaleX = s; scaleY = s
        rotationZ = -40f * (1f - v)
        alpha = v.coerceIn(0f, 1f)
    }
}

/**
 * Le dock : bouton rond de la carte, puis les onglets, dont l'actif s'élargit et
 * affiche son libellé. Chaque changement d'onglet l'écrase un instant puis le relâche.
 */
@Composable
fun FloatingDock(
    currentTab: String,
    onTabSelected: (String) -> Unit,
    showUpdateBadge: Boolean,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val shadow = if (LocalIsDarkTheme.current) Color(0x99000000) else Color(0x47461423)

    val squash = remember { Animatable(0f) }
    LaunchedEffect(currentTab) {
        squash.snapTo(1f)
        launch { squash.animateTo(0f, spring(0.5f, 500f)) }
    }
    val view = LocalView.current
    val atHome = currentTab == HOME.route

    Row(
        modifier
            .graphicsLayer {
                scaleX = 1f + 0.06f * squash.value
                scaleY = 1f - 0.1f * squash.value
            }
            .shadow(14.dp, RoundedCornerShape(32.dp), ambientColor = shadow, spotColor = shadow)
            .clip(RoundedCornerShape(32.dp))
            .background(container)
            .height(64.dp)
            .padding(8.dp)
            .testTag("bottom_nav_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .selectable(selected = atHome, role = Role.Tab) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onTabSelected(HOME.route)
                }
                .testTag(HOME.testTag),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.pop(key = atHome)) {
                Icon(
                    imageVector = if (atHome) HOME.selectedIcon else HOME.icon,
                    contentDescription = stringResource(HOME.labelRes),
                    tint = onContainer,
                    modifier = Modifier.size(24.dp)
                )
                // Enregistrement en cours vu depuis un autre onglet.
                if (isRecording && !atHome) StatusDot(LocalRecordingColor.current, container, Modifier.align(Alignment.TopEnd))
            }
        }
        TABS.forEach { tab ->
            val selected = currentTab == tab.route
            DockTab(
                destination = tab,
                active = selected,
                container = container,
                onContainer = onContainer,
                // Une fois sur l'onglet, la carte en tête des paramètres prend le relais.
                showBadge = showUpdateBadge && tab.route == "parametres" && !selected,
                onClick = { onTabSelected(tab.route) }
            )
        }
    }
}

@Composable
private fun StatusDot(color: Color, ring: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .offset(x = 5.dp, y = (-3).dp)
            .size(12.dp)
            .clip(CircleShape)
            .background(ring)
            .padding(2.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun DockTab(
    destination: DockDestination,
    active: Boolean,
    container: Color,
    onContainer: Color,
    showBadge: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val width by animateDpAsState(if (active) 128.dp else 48.dp, spring(dampingRatio = 0.8f, stiffness = 380f), label = "tabW")
    val bg by animateColorAsState(if (active) accent else Color.Transparent, tween(250, easing = Emphasized), label = "tabBg")
    val fg by animateColorAsState(if (active) onAccent else onContainer, tween(250, easing = Emphasized), label = "tabFg")
    val labelAlpha by animateFloatAsState(if (active) 1f else 0f, tween(200, easing = Emphasized), label = "tabLabel")
    val label = stringResource(destination.labelRes)
    Row(
        Modifier
            .width(width)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .selectable(selected = active, onClick = onClick, role = Role.Tab)
            .testTag(destination.testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Icon(
                imageVector = if (active) destination.selectedIcon else destination.icon,
                // Réduit à son icône, l'onglet se nomme par elle (TalkBack) ; actif, le libellé suffit.
                contentDescription = if (active) null else label,
                tint = fg,
                modifier = Modifier.size(24.dp)
            )
            if (showBadge) {
                StatusDot(
                    MaterialTheme.colorScheme.error, container,
                    Modifier.align(Alignment.TopEnd).testTag("settings_update_badge")
                )
            }
        }
        if (labelAlpha > 0.01f) {
            Text(
                label,
                Modifier.padding(start = 6.dp).graphicsLayer { alpha = labelAlpha },
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight(600)),
                color = fg,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Pose le dock en bas, 28 dp au-dessus de la barre système, et le fait monter ou
 * descendre selon [visible]. Retiré de la composition une fois entièrement caché.
 */
@Composable
fun FloatingDockHost(visible: Boolean, modifier: Modifier = Modifier, dock: @Composable (Modifier) -> Unit) {
    val hidden = with(LocalDensity.current) { 180.dp.toPx() }
    val offset = remember { Animatable(if (visible) 0f else hidden) }
    LaunchedEffect(visible) {
        if (visible) offset.animateTo(0f, spring(0.6f, 500f))
        else offset.animateTo(hidden, tween(350, easing = EmphasizedAccelerate))
    }
    if (!visible && offset.value >= hidden) return
    dock(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 28.dp)
            .graphicsLayer { translationY = offset.value }
    )
}
