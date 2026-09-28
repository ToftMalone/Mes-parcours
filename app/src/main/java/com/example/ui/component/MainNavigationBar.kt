package com.example.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        // « Niveau 3 » de la maquette : panneau de statistiques et navigation,
        // flottants au-dessus de la carte.
        shadowElevation = 6.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(64.dp)
            .testTag("bottom_nav_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DESTINATIONS.forEach { destination ->
                NavItem(
                    destination = destination,
                    selected = currentTab == destination.route,
                    showBadge = showUpdateBadge && destination.route == "parametres",
                    onClick = { onTabSelected(destination.route) },
                    // L'onglet actif peut céder de la place : sur un écran étroit
                    // ou avec un texte agrandi, son libellé se coupe plutôt que de
                    // pousser le dernier onglet hors de la barre.
                    modifier = if (currentTab == destination.route) {
                        Modifier.weight(1f, fill = false)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    destination: NavDestination,
    selected: Boolean,
    showBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(200),
        label = "nav_item_container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "nav_item_content"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .animateContentSize(spring(dampingRatio = 0.6f, stiffness = 800f))
            .padding(horizontal = if (selected) 16.dp else 12.dp)
            .testTag(destination.testTag)
    ) {
        BadgedBox(
            badge = {
                if (showBadge) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings_update_badge")
                    )
                }
            }
        ) {
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.icon,
                // Onglet réduit à son icône : c'est elle qui porte le nom, pour
                // TalkBack. Actif, le libellé affiché suffit.
                contentDescription = if (selected) null else destination.label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
