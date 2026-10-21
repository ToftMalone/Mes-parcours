package com.example.ui.component

import com.example.R
import androidx.compose.ui.res.stringResource
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
import androidx.annotation.StringRes
import com.example.ui.theme.LocalRecordingColor

/** A bar tab: its navigation key (see `MainScreen`), and its appearance. */
private data class NavDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val testTag: String
)

private val DESTINATIONS = listOf(
    NavDestination("record", R.string.nav_record, Icons.Outlined.RadioButtonChecked, Icons.Rounded.RadioButtonChecked, "tab_button_tracking"),
    NavDestination("history", R.string.nav_history, Icons.Outlined.History, Icons.Rounded.History, "tab_button_history"),
    NavDestination("tools", R.string.nav_tools, Icons.Outlined.Handyman, Icons.Rounded.Handyman, "tab_button_tools"),
    NavDestination("settings", R.string.nav_settings, Icons.Outlined.Settings, Icons.Rounded.Settings, "tab_button_settings")
)

/**
 * Navigation bar of the redesign: a floating pill, above the map
 * as well as the lists, where only the active tab shows its label.
 *
 * Replaces Material's `NavigationBar`, whose every tab reserves room for a
 * label and an indicator: it is impossible to get the active tab widening
 * while the others shrink to their icon.
 *
 * The widening goes through `animateContentSize`, the mockup's “fast spatial” spring
 * (stiffness 800, damping 0.6). It clips the content to its animated size,
 * with no consequence here: a tab's pill carries no shadow — see
 * “The sliced shadow of the cards” in CLAUDE.md for what clipping does to a
 * shadow. The shadow is that of the whole bar, outside any animation.
 *
 * [showUpdateBadge]: red dot on “Settings” when an update has been
 * ignored, enough to find it again without repeating what the banner already offered.
 */
@Composable
fun MainNavigationBar(
    currentTab: String,
    onTabSelected: (String) -> Unit,
    showUpdateBadge: Boolean,
    modifier: Modifier = Modifier,
    isRecording: Boolean = false
) {
    // The full-width container only serves to center the pill, which has only the
    // width of its content: a bar stretched edge to edge no longer floats.
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
            // The mockup's “level 3”: statistics panel and navigation,
            // floating above the map.
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
                        // The update dot only shows while we are not
                        // on the tab: once on it, the card at the top replaces it.
                        showBadge = showUpdateBadge && destination.route == "settings" && !selected,
                        // Recording in progress seen from another tab: a dot in
                        // the recording color reminds of it.
                        showRecordingDot = isRecording && destination.route == "record" && !selected,
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
            // Collapsed tab: a 60 dp target, not just the width of the icon.
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
                // Tab reduced to its icon: it is the icon that carries the name, for
                // TalkBack. When active, the displayed label is enough.
                contentDescription = if (selected) null else stringResource(destination.labelRes),
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
                text = stringResource(destination.labelRes),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}
