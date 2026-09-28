package com.example.ui.screen

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.component.CookieShape
import com.example.ui.component.ShapeBadge
import com.example.ui.component.SunShape
import com.example.ui.theme.DisplayFontFamily

/** Nombre de pages de l'accueil. */
private const val WELCOME_PAGES = 3

/**
 * Écran de bienvenue, montré une seule fois au tout premier lancement — voir
 * `OnboardingPreferences`.
 *
 * Trois pages, comme sur la maquette : ce que fait l'application, la promesse que
 * tout reste sur le téléphone, puis les autorisations expliquées avant d'être
 * demandées. Un appui sur « Commencer » déclenche la demande système (localisation,
 * et notifications à partir d'Android 13), gérée par l'appelant. La permission de
 * localisation « tout le temps » n'est pas demandée ici — `MainScreen` l'enchaîne
 * juste après, avec sa propre explication, comme elle le faisait déjà avant cet écran.
 */
@Composable
fun WelcomeScreen(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Survit à une rotation : recommencer l'accueil au début serait déroutant.
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 24.dp)
            .testTag("welcome_screen")
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // Deux pages opaques qui glissent : pas de fondu, pour la même raison
                // que les transitions de MainScreen.
                val forward = targetState > initialState
                slideInHorizontally(tween(260)) { if (forward) it else -it } togetherWith
                    slideOutHorizontally(tween(260)) { if (forward) -it else it }
            },
            label = "welcome_page",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
            ) {
                when (current) {
                    0 -> IntroPage()
                    1 -> PrivacyPage()
                    else -> PermissionsPage()
                }
            }
        }

        // Points de pagination : la page courante s'allonge.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 20.dp)
        ) {
            repeat(WELCOME_PAGES) { i ->
                val width by animateDpAsState(if (i == page) 24.dp else 8.dp, label = "dot_width")
                Box(
                    modifier = Modifier
                        .size(width = width, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (page > 0) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { page-- }
                        .testTag("welcome_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Page précédente", modifier = Modifier.size(26.dp))
                }
            }
            val last = page == WELCOME_PAGES - 1
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { if (last) onRequestPermissions() else page++ }
                    .testTag(if (last) "welcome_continue_button" else "welcome_next_button")
            ) {
                Text(
                    if (last) "Commencer" else "Suivant",
                    fontFamily = DisplayFontFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun PageTitle(text: String, size: Int) {
    Text(
        text,
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight(750),
        fontSize = size.sp,
        lineHeight = (size * 1.05f).sp,
        letterSpacing = (-1).sp
    )
}

// ------------------------------------------------------------------ Page 1

@Composable
private fun IntroPage() {
    val colors = MaterialTheme.colorScheme
    // Illustration : un grand cookie portant le tracé, un compteur et une pastille
    // « GPX · KML » posés autour, comme sur la maquette.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp)
    ) {
        Box(modifier = Modifier.align(Alignment.TopCenter).offset(y = 30.dp)) {
            ShapeBadge(
                icon = Icons.Rounded.Route,
                containerColor = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                shape = CookieShape,
                size = 210.dp,
                iconSize = 96.dp
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-18).dp, y = 6.dp)
                .size(78.dp)
                .clip(CircleShape)
                .background(colors.tertiaryContainer)
        ) {
            Icon(Icons.Rounded.Speed, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(36.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .offset(x = 10.dp, y = 196.dp)
                .size(width = 120.dp, height = 60.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(colors.secondaryContainer)
        ) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(26.dp))
            Text("GPX · KML", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
        }
    }
    PageTitle("Mes parcours", 48)
    Text(
        "Un traqueur GPS pour vos sorties en plein air.",
        fontSize = 18.sp,
        lineHeight = 26.sp,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 24.dp)) {
        FeatureLine(Icons.Rounded.Map, "Carte interactive")
        FeatureLine(Icons.Rounded.QueryStats, "Statistiques en direct")
        FeatureLine(Icons.Rounded.SwapVert, "Import et export GPX / KML")
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ------------------------------------------------------------------ Page 2

@Composable
private fun PrivacyPage() {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(220.dp)
                .clip(SunShape)
                .background(colors.primary)
        ) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(96.dp))
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.primary, modifier = Modifier.size(38.dp).offset(y = 2.dp))
        }
    }
    PageTitle("Tout reste sur votre téléphone", 38)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 24.dp)) {
        PromiseLine(Icons.Rounded.PersonOff, "Aucun compte", "Ouvrez l'app et partez, sans inscription.")
        PromiseLine(Icons.Rounded.CloudOff, "Aucun serveur", "Vos parcours ne sont envoyés nulle part.")
        PromiseLine(Icons.Rounded.Smartphone, "100 % local", "Vous exportez vos fichiers quand vous le décidez.")
    }
}

@Composable
private fun PromiseLine(icon: ImageVector, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ Page 3

@Composable
private fun PermissionsPage() {
    val colors = MaterialTheme.colorScheme
    // Avant Android 13, les notifications ne se demandent pas : une seule autorisation.
    val asksNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    Spacer(modifier = Modifier.height(24.dp))
    PageTitle(if (asksNotifications) "Deux autorisations" else "Une autorisation", 38)
    Text(
        if (asksNotifications) "Voici pourquoi nous allons les demander." else "Voici pourquoi nous allons la demander.",
        fontSize = 16.sp,
        color = colors.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 24.dp)) {
        PermissionCard(
            icon = Icons.Rounded.LocationOn,
            container = colors.primaryContainer,
            content = colors.onPrimaryContainer,
            shape = CookieShape,
            title = "Localisation",
            text = "Pour tracer votre position et calculer la distance, la vitesse et le dénivelé."
        )
        if (asksNotifications) {
            PermissionCard(
                icon = Icons.Rounded.Notifications,
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                shape = SunShape,
                title = "Notifications",
                text = "Pour suivre la progression pendant l'enregistrement et être prévenu des nouvelles versions."
            )
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(22.dp))
        Text(
            "Ensuite, nous demanderons la localisation « Tout le temps » pour continuer à enregistrer écran éteint.",
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    shape: androidx.compose.ui.graphics.Shape,
    title: String,
    text: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp)
    ) {
        ShapeBadge(icon = icon, containerColor = container, contentColor = content, shape = shape, size = 56.dp, iconSize = 28.dp)
        Column {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
