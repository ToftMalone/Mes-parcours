@file:Suppress("DEPRECATION")

package com.example.ui.screen

import com.example.util.FormatUtils
import android.content.Context
import com.example.R
import androidx.compose.ui.res.stringResource
import android.preference.PreferenceManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.component.CookieShape
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MpSwitch
import com.example.ui.component.SunShape
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.theme.NightModePreferences
import com.example.ui.theme.NightModeSource
import com.example.ui.theme.QuoteFontFamily
import com.example.util.AutoBackupPreferences
import com.example.util.SolarTimes
import com.example.util.update.AvailableUpdate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pages de l'onglet : la liste des réglages, et ses deux pages de détail. */
private enum class SettingsPage { MAIN, CHANGELOG, ABOUT }

/**
 * Écran des réglages.
 *
 * L'ordre des sections suit la fréquence d'usage : ce qu'on ajuste souvent en haut,
 * ce qu'on règle une fois pour toutes en bas. Chaque groupe ne traite qu'un sujet, et
 * chaque ligne qu'un réglage — c'est ce qui évite de retomber sur une carte
 * fourre-tout mélangeant le fond de carte, l'orientation et un panneau de vitesse.
 *
 * Le journal des nouveautés et « À propos » sont des pages à part entière, ouvertes
 * depuis le groupe « Application » ; le retour arrière y ramène à la liste.
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    availableUpdate: AvailableUpdate? = null,
    onShowUpdate: () -> Unit = {},
    /** Une page de détail prend tout l'écran : la barre de navigation s'efface. */
    onSubPageChanged: (Boolean) -> Unit = {}
) {
    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    LaunchedEffect(page) { onSubPageChanged(page != SettingsPage.MAIN) }
    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    when (page) {
        SettingsPage.MAIN -> SettingsMain(
            modifier = modifier,
            contentPadding = contentPadding,
            availableUpdate = availableUpdate,
            onShowUpdate = onShowUpdate,
            onOpenChangelog = { page = SettingsPage.CHANGELOG },
            onOpenAbout = { page = SettingsPage.ABOUT }
        )
        SettingsPage.CHANGELOG -> ChangelogPage(availableUpdate, onBack = { page = SettingsPage.MAIN }, modifier = modifier)
        SettingsPage.ABOUT -> AboutPage(onBack = { page = SettingsPage.MAIN }, modifier = modifier)
    }
}

@Composable
private fun SettingsMain(
    modifier: Modifier,
    contentPadding: PaddingValues,
    availableUpdate: AvailableUpdate?,
    onShowUpdate: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding() + 20.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)
            .padding(horizontal = 16.dp)
            .testTag("settings_screen_root")
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight(750)),
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        if (availableUpdate != null) {
            Spacer(modifier = Modifier.height(16.dp))
            UpdateAvailableCard(availableUpdate, onShowUpdate)
        }

        GroupLabel(stringResource(R.string.settings_group_map))
        SettingsGroup {
            MapBackgroundItem()
            MapOrientationItem()
        }

        GroupLabel(stringResource(R.string.settings_group_night))
        NightModeGroup()

        GroupLabel(stringResource(R.string.settings_group_backup))
        AutoBackupGroup()

        GroupLabel(stringResource(R.string.settings_group_app))
        SettingsGroup {
            SettingsRow(
                icon = Icons.Rounded.SystemUpdate,
                title = stringResource(R.string.settings_update),
                subtitle = if (availableUpdate == null) stringResource(R.string.settings_installed_version, BuildConfig.VERSION_NAME) else null,
                onClick = if (availableUpdate != null) onShowUpdate else null,
                testTag = "settings_update_row",
                trailing = {
                    if (availableUpdate != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.error)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                availableUpdate.versionName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onError,
                                maxLines = 1
                            )
                        }
                    }
                }
            )
            SettingsRow(
                icon = Icons.Rounded.NewReleases,
                title = stringResource(R.string.settings_changelog),
                onClick = onOpenChangelog,
                testTag = "version_badge",
                trailing = { Chevron() }
            )
            SettingsRow(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.settings_about),
                onClick = onOpenAbout,
                testTag = "open_about_button",
                trailing = { Chevron() }
            )
        }
    }
}

/**
 * Rappel qu'une mise à jour a été détectée (feuille de [UpdatePrompt] refermée sans
 * installer). [onShowUpdate] rouvre cette même feuille, sans redemander au réseau ni
 * redémarrer l'application.
 */
@Composable
private fun UpdateAvailableCard(update: AvailableUpdate, onShowUpdate: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.tertiaryContainer)
            .clickable(onClick = onShowUpdate)
            .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
            .testTag("update_available_card")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(SunShape)
                .background(colors.tertiary)
        ) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = colors.tertiaryContainer, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_update_available, update.versionName),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onTertiaryContainer
            )
            if (update.notes.isNotEmpty()) {
                Text(
                    update.notes.joinToString(", "),
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = colors.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.tertiary)
                .clickable(onClick = onShowUpdate)
                .padding(horizontal = 16.dp)
                .testTag("show_update_button")
        ) {
            Text(stringResource(R.string.settings_update_view), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.onTertiary)
        }
    }
}

// ---------------------------------------------------------------------------
// Briques communes
//
// Un groupe = un sujet, des lignes séparées de 2 dp dans un bloc aux coins de 28 dp :
// un réglage de plus ne coûte qu'une ligne, pas un bloc recopié.
// ---------------------------------------------------------------------------

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)),
        content = content
    )
}

/** Élément d'un groupe : fond de carte, padding de 16 dp. */
@Composable
private fun GroupItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content
    )
}

/** Ligne à icône : titre, sous-titre éventuel, et un élément à droite. */
@Composable
private fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleAlpha: Float = 1f,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 14.dp else 16.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        when {
            leading != null -> leading()
            icon != null -> Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alpha(titleAlpha))
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = subtitleColor)
            }
        }
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp)
    )
}

/** Bouton radio de la maquette : anneau, et pastille qui grossit à la sélection. */
@Composable
private fun MpRadio(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val inner by animateDpAsState(if (selected) 10.dp else 0.dp, label = "radio_inner")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .border(2.dp, if (selected) colors.primary else colors.onSurfaceVariant, CircleShape)
    ) {
        Box(
            modifier = Modifier
                .size(inner)
                .clip(CircleShape)
                .background(colors.primary)
        )
    }
}

// ---------------------------------------------------------------------------
// Carte
// ---------------------------------------------------------------------------

@Composable
private fun MapBackgroundItem() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var mapStyle by remember { mutableStateOf(prefs.getString("pref_map_style", "mapnik") ?: "mapnik") }
    val select = { value: String ->
        mapStyle = value
        prefs.edit().putString("pref_map_style", value).apply()
    }

    GroupItem(modifier = Modifier.testTag("map_background_card")) {
        Text(stringResource(R.string.map_background), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            MapStyleThumb(
                title = stringResource(R.string.map_style_standard),
                subtitle = stringResource(R.string.map_style_standard_sub),
                selected = mapStyle == "mapnik",
                onClick = { select("mapnik") },
                satellite = false,
                testTag = "map_style_option_mapnik",
                modifier = Modifier.weight(1f)
            )
            MapStyleThumb(
                title = stringResource(R.string.map_style_satellite),
                subtitle = stringResource(R.string.map_style_satellite_sub),
                selected = mapStyle == "usgs_sat",
                onClick = { select("usgs_sat") },
                satellite = true,
                testTag = "map_style_option_satellite",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Vignette d'un fond de carte, dessinée comme sur la maquette : quelques formes qui
 * évoquent la carte, sans charger la moindre tuile.
 */
@Composable
private fun MapStyleThumb(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    satellite: Boolean,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 28.dp else 18.dp, label = "thumb_radius")
    val ring by animateColorAsState(if (selected) colors.primary else Color.Transparent, label = "thumb_ring")
    val check by animateFloatAsState(if (selected) 1f else 0f, label = "thumb_check")
    // Pas de rognage sur la colonne entière : ses coins arrondis mordaient sur les
    // légendes sous la vignette.
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .border(3.dp, ring, RoundedCornerShape(radius))
                .padding(3.dp)
                .clip(RoundedCornerShape((radius - 3.dp).coerceAtLeast(0.dp)))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val u = 1.dp.toPx()
                if (!satellite) {
                    drawRect(Color(0xFFEEF1EA))
                    drawOval(Color(0xFFCFE6C4), Offset(-10 * u, 10 * u), Size(70 * u, 50 * u))
                    drawOval(Color(0xFFA9D3EA), Offset(size.width - 60 * u, 14 * u), Size(50 * u, 40 * u))
                    rotate(-6f, Offset(size.width / 2, 59 * u)) {
                        drawRect(Color.White, Offset(-10 * u, 56 * u), Size(size.width + 20 * u, 6 * u))
                    }
                    drawRect(Color.White, Offset(60 * u, 0f), Size(6 * u, size.height))
                } else {
                    drawRect(Color(0xFF3B4A32))
                    drawOval(Color(0xFF2A3A24), Offset(-10 * u, 4 * u), Size(80 * u, 60 * u))
                    drawOval(Color(0xFF1E3A4A), Offset(size.width - 62 * u, 12 * u), Size(56 * u, 44 * u))
                    drawRoundRect(Color(0xFF6B6045), Offset(30 * u, 60 * u), Size(50 * u, 24 * u), CornerRadius(8 * u))
                    rotate(-6f, Offset(size.width / 2, 57 * u)) {
                        drawRect(Color(0xFFF5E7A8), Offset(-10 * u, 56 * u), Size(size.width + 20 * u, 3 * u))
                    }
                }
            }
            if (satellite) {
                Text(
                    stringResource(R.string.map_thumb_street),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.offset(x = 8.dp, y = 36.dp)
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(24.dp * check)
                    .clip(CircleShape)
                    .background(colors.primary)
            ) {
                if (check > 0.5f) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.picker_chosen), tint = colors.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, fontSize = 12.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun MapOrientationItem() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var mapMode by remember { mutableStateOf(prefs.getString("pref_map_mode", "2d") ?: "2d") }

    GroupItem(modifier = Modifier.testTag("map_orientation_card")) {
        Text(stringResource(R.string.map_orientation), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 12.dp)) {
            listOf("2d" to "2D", "3d" to "3D", "auto" to stringResource(R.string.map_mode_auto)).forEach { (value, label) ->
                OrientationSegment(
                    label = label,
                    selected = mapMode == value,
                    onClick = {
                        mapMode = value
                        prefs.edit().putString("pref_map_mode", value).apply()
                    },
                    testTag = "map_mode_option_$value",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Text(
            stringResource(
                when (mapMode) {
                    "3d" -> R.string.map_mode_3d_hint
                    "auto" -> R.string.map_mode_auto_hint
                    else -> R.string.map_mode_2d_hint
                }
            ),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun OrientationSegment(label: String, selected: Boolean, onClick: () -> Unit, testTag: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 28.dp else 12.dp, label = "orient_radius")
    val bg by animateColorAsState(if (selected) colors.primary else colors.surfaceContainer, label = "orient_bg")
    val fg by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, label = "orient_fg")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Mode nuit
// ---------------------------------------------------------------------------

/**
 * Sous-titre du mode solaire : la prochaine bascule, calculée hors ligne pour la
 * dernière position de carte connue (voir `NightModePreferences.lastKnownLocation`).
 */
private fun nextSolarSwitch(context: Context, lat: Double, lng: Double, now: Long): String? {
    return when (val today = SolarTimes.compute(lat, lng, now)) {
        SolarTimes.Result.PolarDay -> context.getString(R.string.solar_polar_day)
        SolarTimes.Result.PolarNight -> context.getString(R.string.solar_polar_night)
        is SolarTimes.Result.RiseAndSet -> when {
            now < today.sunriseUtcMillis ->
                context.getString(R.string.solar_light_from, FormatUtils.formatClock(today.sunriseUtcMillis))
            now < today.sunsetUtcMillis ->
                context.getString(R.string.solar_dark_from, FormatUtils.formatClock(today.sunsetUtcMillis))
            else -> (SolarTimes.compute(lat, lng, now + 86_400_000L) as? SolarTimes.Result.RiseAndSet)
                ?.let { context.getString(R.string.solar_light_from, FormatUtils.formatClock(it.sunriseUtcMillis)) }
        }
    }
}

/**
 * Choix de ce qui déclenche le thème sombre : le réglage d'Android, ou le lever et
 * le coucher du soleil — avec, dans ce dernier cas, un passage temporaire en sombre
 * dans les tunnels.
 *
 * Le changement s'applique immédiatement : le thème observe la préférence.
 */
@Composable
private fun NightModeGroup() {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    var source by remember { mutableStateOf(NightModePreferences.getSource(context)) }
    var tunnelEnabled by remember { mutableStateOf(NightModePreferences.isTunnelDetectionEnabled(context)) }
    val hasLightSensor = remember { NightModePreferences.hasLightSensor(context) }
    val solarHint = remember(source) {
        val (lat, lng) = NightModePreferences.lastKnownLocation(context)
        nextSolarSwitch(context, lat, lng, System.currentTimeMillis())
    }
    val select = { value: NightModeSource ->
        source = value
        NightModePreferences.setSource(context, value)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .testTag("night_mode_settings_card")
    ) {
        SettingsRow(
            icon = null,
            leading = { MpRadio(source == NightModeSource.SYSTEM) },
            title = stringResource(R.string.night_follow_phone),
            subtitle = stringResource(R.string.night_follow_phone_sub),
            onClick = { select(NightModeSource.SYSTEM) },
            testTag = "night_mode_option_system"
        )
        SettingsRow(
            icon = null,
            leading = { MpRadio(source == NightModeSource.SOLAR) },
            title = stringResource(R.string.night_solar),
            subtitle = stringResource(R.string.night_solar_sub) + (solarHint?.let { " · $it" } ?: ""),
            onClick = { select(NightModeSource.SOLAR) },
            testTag = "night_mode_option_solar"
        )
        // La détection de tunnel n'agit qu'en mode solaire : c'est une exception au
        // jour, pas un réglage à part. Proposée seulement là où elle a un effet.
        if (source == NightModeSource.SOLAR) {
            SettingsRow(
                icon = Icons.Rounded.LightMode,
                title = stringResource(R.string.night_tunnel),
                titleAlpha = if (hasLightSensor) 1f else 0.5f,
                subtitle = stringResource(if (hasLightSensor) R.string.night_tunnel_sub else R.string.night_tunnel_no_sensor),
                subtitleColor = if (hasLightSensor) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                onClick = {
                    if (hasLightSensor) {
                        tunnelEnabled = !tunnelEnabled
                        NightModePreferences.setTunnelDetectionEnabled(context, tunnelEnabled)
                    } else {
                        messenger.show(
                            context.getString(R.string.night_tunnel_no_sensor_toast),
                            Icons.Rounded.LightMode,
                            isError = true
                        )
                    }
                },
                trailing = {
                    MpSwitch(
                        checked = tunnelEnabled && hasLightSensor,
                        enabled = hasLightSensor,
                        onCheckedChange = { checked ->
                            tunnelEnabled = checked
                            NightModePreferences.setTunnelDetectionEnabled(context, checked)
                        },
                        modifier = Modifier.testTag("night_mode_tunnel_switch")
                    )
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sauvegarde
// ---------------------------------------------------------------------------

@Composable
private fun AutoBackupGroup() {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(AutoBackupPreferences.isAutoBackupEnabled(context)) }
    var isGpx by remember { mutableStateOf(AutoBackupPreferences.isFormatGpx(context)) }
    var isKml by remember { mutableStateOf(AutoBackupPreferences.isFormatKml(context)) }
    val toggle = { checked: Boolean ->
        isEnabled = checked
        AutoBackupPreferences.setAutoBackupEnabled(context, checked)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .testTag("auto_backup_settings_card")
    ) {
        SettingsRow(
            icon = Icons.Rounded.Save,
            title = stringResource(R.string.backup_export_each),
            subtitle = stringResource(R.string.backup_destination),
            onClick = { toggle(!isEnabled) },
            trailing = {
                MpSwitch(
                    checked = isEnabled,
                    onCheckedChange = toggle,
                    modifier = Modifier.testTag("auto_backup_main_toggle")
                )
            }
        )
        // Au moins un format doit rester coché, sinon la sauvegarde automatique serait
        // activée sans rien produire.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(start = 54.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
                .alpha(if (isEnabled) 1f else 0.45f)
        ) {
            FormatChip("GPX", isGpx, enabled = isEnabled, testTag = "format_gpx_checkbox") {
                if (!isGpx || isKml) {
                    isGpx = !isGpx
                    AutoBackupPreferences.setFormatGpx(context, isGpx)
                }
            }
            FormatChip("KML", isKml, enabled = isEnabled, testTag = "format_kml_checkbox") {
                if (!isKml || isGpx) {
                    isKml = !isKml
                    AutoBackupPreferences.setFormatKml(context, isKml)
                }
            }
        }
    }
}

@Composable
private fun FormatChip(label: String, checked: Boolean, enabled: Boolean, testTag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val checkWidth by animateDpAsState(if (checked) 18.dp else 0.dp, label = "chip_check")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) colors.secondaryContainer else Color.Transparent)
            .border(1.dp, if (checked) colors.secondaryContainer else colors.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 10.dp, end = 14.dp)
            .testTag(testTag)
    ) {
        Box(modifier = Modifier.width(checkWidth).clip(RoundedCornerShape(0.dp))) {
            if (checkWidth > 0.dp) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (checked) colors.onSecondaryContainer else colors.onSurface)
    }
}

// ---------------------------------------------------------------------------
// Pages de détail : en-tête commun
// ---------------------------------------------------------------------------

@Composable
private fun SubPageTopBar(title: String?, onBack: () -> Unit, backTag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(56.dp)
            .offset(x = (-8).dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .testTag(backTag)
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

// ---------------------------------------------------------------------------
// Journal des nouveautés
// ---------------------------------------------------------------------------

/** Une version, sa date de publication, et ce qu'elle a apporté. */
private class Release(val version: String, val date: String?, val changes: List<Int>)

/**
 * Journal des nouveautés, de la version la plus récente à la plus ancienne.
 *
 * **Avant la 1.0** : la liste ne contient que la version courante. À chaque
 * nouvelle version, on remplace son contenu — les versions de développement se
 * succèdent trop vite pour qu'un historique ait de l'intérêt.
 *
 * **À partir de la 1.0** : on ajoute une entrée en tête au lieu de remplacer.
 *
 * La date est celle de la publication GitHub (AAAA-MM-JJ), `null` tant que la version
 * n'est pas publiée. La version installée est repérée par comparaison avec
 * `BuildConfig.VERSION_NAME` : elle n'est jamais à désigner à la main.
 */
private val RELEASES = listOf(
    Release(
        version = "2.2.3",
        date = "2026-10-27",
        changes = listOf(R.string.rel_2_2_3_a)
    ),
    Release(
        version = "2.2.2",
        date = "2026-10-27",
        changes = listOf(
            R.string.rel_2_2_2_a,
            R.string.rel_2_2_2_b,
            R.string.rel_2_2_2_c,
            R.string.rel_2_2_2_d
        )
    ),
    Release(
        version = "2.2.1",
        date = "2026-10-24",
        changes = listOf(
            R.string.rel_2_2_1_a
        )
    ),
    Release(
        version = "2.2",
        date = "2026-10-23",
        changes = listOf(
            R.string.rel_2_2_a,
            R.string.rel_2_2_b,
            R.string.rel_2_2_c
        )
    ),
    Release(
        version = "2.1.0",
        date = "2026-10-21",
        changes = listOf(
            R.string.rel_2_1_0_a,
            R.string.rel_2_1_0_b
        )
    ),
    Release(
        version = "2.0.3",
        date = "2026-10-15",
        changes = listOf(
            R.string.rel_2_0_3_a,
            R.string.rel_2_0_3_b
        )
    ),
    Release(
        version = "2.0.2",
        date = "2026-10-11",
        changes = listOf(
            R.string.rel_2_0_2_a,
            R.string.rel_2_0_2_b
        )
    ),
    Release(
        version = "2.0.1",
        date = "2026-10-05",
        changes = listOf(
            R.string.rel_2_0_1_a
        )
    ),
    Release(
        version = "2.0",
        date = "2026-09-28",
        changes = listOf(
            R.string.rel_2_0_a
        )
    ),
    Release(
        version = "1.3.1",
        date = "2026-09-23",
        changes = listOf(
            R.string.rel_1_3_1_a
        )
    ),
    Release(
        version = "1.3",
        date = "2026-09-15",
        changes = listOf(
            R.string.rel_1_3_a
        )
    ),
    Release(
        version = "1.2",
        date = "2026-09-09",
        changes = listOf(
            R.string.rel_1_2_a
        )
    ),
    Release(
        version = "1.1",
        date = "2026-09-02",
        changes = listOf(
            R.string.rel_1_1_a,
            R.string.rel_1_1_b
        )
    ),
    Release(
        version = "1.0",
        date = "2026-08-31",
        changes = listOf(
            R.string.rel_1_0_a
        )
    )
)

/** Version installée, sans le suffixe de la variante de debug. */
private val INSTALLED_VERSION = BuildConfig.VERSION_NAME.removeSuffix("-debug")

private fun formatReleaseDate(date: String?, withYear: Boolean): String? {
    if (date == null) return null
    val parsed = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date) }.getOrNull() ?: return null
    return FormatUtils.formatDateLong(parsed.time, withYear)
}

@Composable
private fun ChangelogPage(availableUpdate: AvailableUpdate?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)
            .testTag("release_notes_dialog")
    ) {
        SubPageTopBar(stringResource(R.string.changelog_title), onBack, "close_release_notes_button")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (availableUpdate != null) {
                val outline = colors.outline
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRoundRect(
                                outline,
                                cornerRadius = CornerRadius(28.dp.toPx()),
                                style = Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                )
                            )
                        }
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(availableUpdate.versionName, fontFamily = DisplayFontFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(colors.tertiaryContainer)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.changelog_available), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onTertiaryContainer)
                        }
                    }
                    ReleaseBullets(availableUpdate.notes, colors.onSurfaceVariant, 14)
                }
            }

            RELEASES.forEach { release ->
                if (release.version == INSTALLED_VERSION) InstalledReleaseCard(release) else PastReleaseCard(release)
            }
        }
    }
}

@Composable
private fun ReleaseBullets(items: List<String>, color: Color, fontSize: Int) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        items.forEach { item ->
            Text("• $item", fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp, color = color)
        }
    }
}

@Composable
private fun InstalledReleaseCard(release: Release) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(36.dp))
            .background(colors.primaryContainer)
    ) {
        // Cookie décoratif dans le coin, comme sur la maquette.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 30.dp, y = (-30).dp)
                .size(120.dp)
                .alpha(0.18f)
                .clip(CookieShape)
                .background(colors.primary)
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    release.version,
                    fontFamily = DisplayFontFamily,
                    fontSize = 34.sp,
                    fontWeight = FontWeight(750),
                    color = colors.onPrimaryContainer
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.primary)
                        .padding(horizontal = 10.dp)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.changelog_installed), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onPrimary)
                }
            }
            formatReleaseDate(release.date, withYear = true)?.let {
                Text(it, fontSize = 13.sp, color = colors.onPrimaryContainer, modifier = Modifier.padding(top = 2.dp))
            }
            Column(modifier = Modifier.padding(top = 4.dp)) {
                ReleaseBullets(release.changes.map { stringResource(it) }, colors.onPrimaryContainer, 15)
            }
        }
    }
}

@Composable
private fun PastReleaseCard(release: Release) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerLow)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(release.version, fontFamily = DisplayFontFamily, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            formatReleaseDate(release.date, withYear = false)?.let {
                Text(
                    " · $it",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        ReleaseBullets(release.changes.map { stringResource(it) }, colors.onSurfaceVariant, 14)
    }
}

// ---------------------------------------------------------------------------
// À propos
// ---------------------------------------------------------------------------

@Composable
private fun AboutPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp)
            .testTag("about_page")
    ) {
        SubPageTopBar(null, onBack, "about_back_button")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(colors.primary)
            ) {
                Icon(Icons.Rounded.Route, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(56.dp))
            }
            Text(
                stringResource(R.string.app_name),
                fontFamily = DisplayFontFamily,
                fontSize = 32.sp,
                fontWeight = FontWeight(750),
                modifier = Modifier.padding(top = 14.dp)
            )
            // Lue depuis BuildConfig : la version n'est écrite qu'une fois, dans
            // build.gradle.kts, et l'affichage ne peut plus dériver.
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }

        // Dédicace à Thierry.
        Box(
            modifier = Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(40.dp))
                .background(colors.tertiaryContainer)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-40).dp, y = 50.dp)
                    .size(150.dp)
                    .alpha(0.14f)
                    .clip(SunShape)
                    .background(colors.tertiary)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 30.dp, y = (-40).dp)
                    .size(110.dp)
                    .alpha(0.14f)
                    .clip(CookieShape)
                    .background(colors.tertiary)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 26.dp)
            ) {
                Text(stringResource(R.string.about_dedication_label), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = colors.onTertiaryContainer)
                Text(
                    stringResource(R.string.about_dedication_quote),
                    fontFamily = QuoteFontFamily,
                    fontSize = 24.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = colors.onTertiaryContainer,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        SettingsGroup {
            AboutRow(Icons.Rounded.Code, stringResource(R.string.about_open_source), stringResource(R.string.about_license))
            AboutRow(Icons.Rounded.Lock, stringResource(R.string.about_local_storage), stringResource(R.string.about_no_account))
            AboutRow(Icons.Rounded.Person, stringResource(R.string.about_developer), "ToftMalone")
        }
    }
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, subtitle: String) {
    SettingsRow(icon = icon, iconTint = MaterialTheme.colorScheme.primary, title = title, subtitle = subtitle)
}
