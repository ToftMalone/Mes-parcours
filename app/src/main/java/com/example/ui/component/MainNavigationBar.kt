package com.example.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalRecordingColor

/** Un onglet de la barre : sa clé de navigation (voir `MainScreen`), et son apparence. */
private data class NavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val testTag: String
)

private val DESTINATIONS = listOf(
    NavDestination("enregistrer", "Enregistrer", Icons.Outlined.RadioButtonChecked, Icons.Rounded.RadioButtonChecked, "tab_button_tracking"),
    NavDestination("historique", "Historique", Icons.Outlined.History, Icons.Rounded.History, "tab_button_history"),
    NavDestination("outils", "Outils", Icons.Outlined.Handyman, Icons.Rounded.Handyman, "tab_button_tools"),
    NavDestination("parametres", "Paramètres", Icons.Outlined.Settings, Icons.Rounded.Settings, "tab_button_settings")
)

/**
 * Barre de navigation de la refonte : une pilule flottante, au-dessus de la carte
 * comme des listes, où seul l'onglet actif affiche son libellé.
 *
 * Remplace la `NavigationBar` de Material, dont chaque onglet réserve la place d'un
 * libellé et d'un indicateur : impossible d'y obtenir l'onglet actif qui s'élargit
 * pendant que les autres se réduisent à leur icône.
 *
 * L'élargissement passe par `animateContentSize`, ressort « spatial rapide » de la
 * maquette (raideur 800, amortissement 0,6). Il rogne le contenu à sa taille animée,
 * sans conséquence ici : la pilule d'un onglet ne porte aucune ombre — voir
 * « L'ombre tranchée des cartes » dans CLAUDE.md pour ce qu'un rognage fait à une
 * ombre. L'ombre est celle de la barre entière, hors de toute animation.
 *
 * [showUpdateBadge] : pastille rouge sur « Paramètres » quand une mise à jour a été
 * ignorée, de quoi la retrouver sans redire ce que le bandeau a déjà proposé.
 */
@Composable
fun MainNavigationBar(
    currentTab: String,
    onTabSelected: (String) -> Unit,
    showUpdateBadge: Boolean,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    // Le conteneur pleine largeur ne sert qu'à centrer la pilule, qui n'a que la
    // largeur de son contenu : une barre étirée d'un bord à l'autre ne flotte plus.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            // « Niveau 3 » de la maquette : panneau de statistiques et navigation,
            // flottants au-dessus de la carte.
            shadowElevation = 8.dp,
            modifier = Modifier.testTag("bottom_nav_bar")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DESTINATIONS.forEach { destination ->
                    val selected = currentTab == destination.route
                    NavItem(
                        destination = destination,
                        selected = selected,
                        // La pastille de mise à jour ne se montre que tant qu'on n'est pas
                        // sur l'onglet : une fois dessus, la carte en tête la remplace.
                        showBadge = showUpdateBadge && destination.route == "parametres" && !selected,
                        // Enregistrement en cours vu depuis un autre onglet : un point de
                        // la couleur d'enregistrement le rappelle.
                        showRecordingDot = isRecording && destination.route == "enregistrer" && !selected,
                        onClick = { onTabSelected(destination.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: NavDestination,
    selected: Boolean,
    showBadge: Boolean,
    showRecordingDot: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(200),
        label = "nav_item_container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onPrimaryContainer,
        animationSpec = tween(200),
        label = "nav_item_content"
    )
    val dotRing = MaterialTheme.colorScheme.primaryContainer

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(56.dp)
            // Onglet réduit : une cible de 60 dp, pas seulement la largeur de l'icône.
            .then(if (selected) Modifier else Modifier.width(60.dp))
            .clip(CircleShape)
            .background(containerColor)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .animateContentSize(spring(dampingRatio = 0.6f, stiffness = 800f))
            .padding(horizontal = if (selected) 22.dp else 0.dp)
            .testTag(destination.testTag)
    ) {
        Box {
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.icon,
                // Onglet réduit à son icône : c'est elle qui porte le nom, pour
                // TalkBack. Actif, le libellé affiché suffit.
                contentDescription = if (selected) null else destination.label,
                tint = contentColor,
                modifier = Modifier.size(26.dp)
            )
            val dotColor = when {
                showBadge -> MaterialTheme.colorScheme.error
                showRecordingDot -> LocalRecordingColor.current
                else -> null
            }
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-3).dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotRing)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .then(if (showBadge) Modifier.testTag("settings_update_badge") else Modifier)
                )
            }
        }
        if (selected) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
