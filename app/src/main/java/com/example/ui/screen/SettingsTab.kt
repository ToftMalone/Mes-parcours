@file:Suppress("DEPRECATION")

package com.example.ui.screen

import android.preference.PreferenceManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.ui.theme.NightModePreferences
import com.example.ui.theme.NightModeSource
import com.example.util.AutoBackupPreferences
import com.example.util.TrackStylePreferences

/**
 * Écran des réglages.
 *
 * L'ordre des sections suit la fréquence d'usage : ce qu'on ajuste souvent en haut,
 * ce qu'on règle une fois pour toutes en bas. Chaque groupe ne traite qu'un sujet, et
 * chaque carte qu'un réglage — c'est ce qui évite de retomber sur une carte
 * fourre-tout mélangeant le fond de carte, l'orientation et un panneau de vitesse.
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    hasAvailableUpdate: Boolean = false,
    onShowUpdate: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp)
                .testTag("settings_screen_root")
        ) {
            SettingsScreenHeader()

            Spacer(modifier = Modifier.height(16.dp))

            if (hasAvailableUpdate) {
                UpdateAvailableCard(onShowUpdate = onShowUpdate)
                Spacer(modifier = Modifier.height(16.dp))
            }

            SettingsGroupHeader(title = "Carte", icon = Icons.Default.Map)
            MapBackgroundCard()
            MapOrientationCard()

            SettingsGroupHeader(title = "Thème", icon = Icons.Default.DarkMode)
            NightModeSettingsCard()

            SettingsGroupHeader(title = "Tracés", icon = Icons.Default.Timeline)
            TrackThicknessSettingsCard()
            ImportedTrackColorCard()

            SettingsGroupHeader(title = "Sauvegarde", icon = Icons.Default.CloudSync)
            AutoBackupSettingsCard()

            SettingsGroupHeader(title = "À propos", icon = Icons.Default.Info)
            AboutCard()

            // Évite que la dernière carte passe sous la barre de navigation flottante.
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun SettingsScreenHeader() {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = "Paramètres",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Ajustez vos préférences & gérez l'application",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Rappel qu'une mise à jour a été détectée puis ignorée (bandeau de [UpdatePrompt]
 * fermé sans télécharger ni installer). [onShowUpdate] rouvre ce même bandeau, sans
 * redemander au réseau ni redémarrer l'application.
 */
@Composable
private fun UpdateAvailableCard(onShowUpdate: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth().testTag("update_available_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Mise à jour disponible",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Vous l'avez ignorée — revoir les détails",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onShowUpdate,
                modifier = Modifier.testTag("show_update_button")
            ) {
                Text(
                    text = "Voir",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Briques communes
//
// Les trois listes de choix de l'écran (fond de carte, orientation, mode nuit)
// partageaient le même bloc recopié à l'identique. Elles passent désormais toutes
// par SettingsChoiceList : un réglage de plus ne coûte qu'une liste de données.
// ---------------------------------------------------------------------------

@Composable
fun SettingsGroupHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Coquille commune à toutes les cartes de réglages. */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** Intitulé d'un réglage à l'intérieur d'une carte. */
@Composable
private fun SettingsCardTitle(title: String, subtitle: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

/**
 * Pastille ronde portant l'icône d'un réglage.
 *
 * Une icône vectorielle et non un emoji : l'emoji ne se teinte pas avec le thème,
 * change de dessin d'un fabricant à l'autre, et son style plein jurait avec les
 * icônes de trait des en-têtes de groupe, juste au-dessus.
 */
@Composable
private fun SettingsIconBadge(icon: ImageVector, highlighted: Boolean) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Une option exclusive d'une liste de choix. */
private class SettingsChoice<T>(
    val value: T,
    val icon: ImageVector,
    val title: String,
    val description: String,
    val testTag: String? = null
)

/** Liste d'options exclusives, une seule sélectionnable. */
@Composable
private fun <T> SettingsChoiceList(
    choices: List<SettingsChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    choices.forEach { choice ->
        val isSelected = choice.value == selected
        val select = { onSelect(choice.value) }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { select() }
                .then(choice.testTag?.let { Modifier.testTag(it) } ?: Modifier)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsIconBadge(icon = choice.icon, highlighted = isSelected)

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = choice.title,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = choice.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                RadioButton(
                    selected = isSelected,
                    onClick = select,
                    colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** Réglage à bascule : icône, libellé, description, interrupteur. */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    switchTestTag: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBadge(icon = icon, highlighted = checked && enabled)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.6f
                )
            )
        }

        Switch(
            checked = checked && enabled,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = switchTestTag?.let { Modifier.testTag(it) } ?: Modifier
        )
    }
}

/** Remarque explicative en bas d'une carte. */
@Composable
private fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = modifier.padding(top = 12.dp)
    )
}

// ---------------------------------------------------------------------------
// Carte
// ---------------------------------------------------------------------------

@Composable
private fun MapBackgroundCard() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var mapStyle by remember { mutableStateOf(prefs.getString("pref_map_style", "mapnik") ?: "mapnik") }

    SettingsCard(modifier = Modifier.testTag("map_background_card")) {
        SettingsCardTitle(
            title = "Fond de carte",
            subtitle = "Le type de cartographie utilisé pour vos sorties."
        )

        SettingsChoiceList(
            choices = listOf(
                SettingsChoice(
                    value = "mapnik",
                    icon = Icons.Default.Map,
                    title = "Standard (Mapnik)",
                    description = "Carte classique OpenStreetMap",
                    testTag = "map_style_option_mapnik"
                ),
                SettingsChoice(
                    value = "usgs_sat",
                    icon = Icons.Default.SatelliteAlt,
                    title = "Satellite hybride (Google)",
                    description = "Imagerie satellite haute définition enrichie des noms de rues",
                    testTag = "map_style_option_satellite"
                )
            ),
            selected = mapStyle,
            onSelect = { value ->
                mapStyle = value
                prefs.edit().putString("pref_map_style", value).apply()
            }
        )
    }
}

@Composable
private fun MapOrientationCard() {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var mapMode by remember { mutableStateOf(prefs.getString("pref_map_mode", "2d") ?: "2d") }

    SettingsCard(modifier = Modifier.testTag("map_orientation_card")) {
        SettingsCardTitle(
            title = "Orientation & vue",
            subtitle = "Carte fixe au nord, ou pivotant dans le sens de déplacement."
        )

        SettingsChoiceList(
            choices = listOf(
                SettingsChoice(
                    value = "2d",
                    icon = Icons.Default.Explore,
                    title = "Vue 2D (nord en haut)",
                    description = "Carte fixe orientée vers le nord",
                    testTag = "map_mode_option_2d"
                ),
                SettingsChoice(
                    value = "3d",
                    icon = Icons.Default.Navigation,
                    title = "Vue 3D (sens d'avancement)",
                    description = "La carte pivote automatiquement selon votre direction",
                    testTag = "map_mode_option_3d"
                ),
                SettingsChoice(
                    value = "auto",
                    icon = Icons.Default.AutoAwesome,
                    title = "Automatique",
                    description = "Vue 3D pendant un enregistrement, retour en 2D dès qu'il est arrêté",
                    testTag = "map_mode_option_auto"
                )
            ),
            selected = mapMode,
            onSelect = { value ->
                mapMode = value
                prefs.edit().putString("pref_map_mode", value).apply()
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Thème
// ---------------------------------------------------------------------------

/**
 * Choix de ce qui déclenche le thème sombre : le réglage d'Android, ou le lever et
 * le coucher du soleil — avec, dans ce dernier cas, un passage temporaire en sombre
 * dans les tunnels.
 *
 * Le changement s'applique immédiatement : le thème observe la préférence.
 */
@Composable
fun NightModeSettingsCard() {
    val context = LocalContext.current
    var source by remember { mutableStateOf(NightModePreferences.getSource(context)) }
    var tunnelEnabled by remember {
        mutableStateOf(NightModePreferences.isTunnelDetectionEnabled(context))
    }
    val hasLightSensor = remember { NightModePreferences.hasLightSensor(context) }

    SettingsCard(modifier = Modifier.testTag("night_mode_settings_card")) {
        SettingsCardTitle(
            title = "Mode nuit",
            subtitle = "Ce qui fait basculer l'application en thème sombre."
        )

        SettingsChoiceList(
            choices = listOf(
                SettingsChoice(
                    value = NightModeSource.SYSTEM,
                    icon = Icons.Default.PhoneAndroid,
                    title = "Suivre le téléphone",
                    description = "L'application passe en sombre en même temps qu'Android",
                    testTag = "night_mode_option_system"
                ),
                SettingsChoice(
                    value = NightModeSource.SOLAR,
                    icon = Icons.Default.WbTwilight,
                    title = "Lever et coucher du soleil",
                    description = "Clair le jour, sombre la nuit, selon l'heure réelle du soleil là où vous êtes",
                    testTag = "night_mode_option_solar"
                )
            ),
            selected = source,
            onSelect = { value ->
                source = value
                NightModePreferences.setSource(context, value)
            }
        )

        AnimatedVisibility(visible = source == NightModeSource.SOLAR) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))

                SettingsToggleRow(
                    icon = Icons.Default.Train,
                    title = "Sombre dans les tunnels",
                    description = if (hasLightSensor) {
                        "Bascule en sombre le temps d'un tunnel ou d'un parking couvert, " +
                                "puis revient au clair à la sortie"
                    } else {
                        "Cet appareil n'a pas de capteur de luminosité"
                    },
                    checked = tunnelEnabled,
                    enabled = hasLightSensor,
                    onCheckedChange = { checked ->
                        tunnelEnabled = checked
                        NightModePreferences.setTunnelDetectionEnabled(context, checked)
                    },
                    switchTestTag = "night_mode_tunnel_switch"
                )

                AnimatedVisibility(visible = tunnelEnabled && hasLightSensor) {
                    SettingsHint(
                        "La bascule demande une chute franche de luminosité, et non un simple " +
                                "seuil : rester à l'intérieur en pleine journée ne déclenche rien."
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tracés
// ---------------------------------------------------------------------------

/**
 * Couleurs des parcours importés.
 *
 * Ce choix n'existait que derrière un appui long sur l'onglet « Importés » de
 * l'historique : un geste que rien n'annonce, sur un onglet précis parmi deux. Qui
 * ouvrait la palette depuis « Enregistrés » n'y voyait rien — et pour cause, un
 * parcours enregistré ne vient d'aucun fichier. Le réglage restait donc introuvable
 * pour qui ne l'avait pas vu faire. Sa place est ici, là où on cherche les réglages ;
 * le geste de l'historique n'est plus qu'un raccourci.
 */
@Composable
fun ImportedTrackColorCard() {
    val context = LocalContext.current
    var fromFile by remember {
        mutableStateOf(TrackStylePreferences.isImportedColorFromFile(context))
    }

    SettingsCard(modifier = Modifier.testTag("imported_track_color_card")) {
        SettingsToggleRow(
            icon = Icons.Default.Palette,
            title = "Garder les couleurs des fichiers",
            description = "Chaque parcours importé reprend la couleur que portait son " +
                    "fichier, celle choisie dans Google Earth. Les fichiers qui n'en " +
                    "portent pas, les GPX notamment, gardent la couleur de la palette.",
            checked = fromFile,
            onCheckedChange = { checked ->
                fromFile = checked
                TrackStylePreferences.setImportedColorFromFile(context, checked)
            },
            switchTestTag = "imported_color_from_file_switch"
        )
    }
}

/** "3,5" plutôt que "3.5" : une virgule quel que soit le réglage régional de l'appareil. */
private fun formatThicknessDp(value: Float): String =
    String.format(java.util.Locale.US, "%.1f", value).replace('.', ',')

/**
 * Réglage de l'épaisseur du trait des tracés, avec aperçu en direct.
 *
 * Curseur continu et champ de saisie se répondent l'un l'autre : le premier pour un
 * réglage rapide, le second pour une valeur précise (en dp, avec virgule). Aucun des
 * deux n'est cranté sur des paliers prédéfinis.
 */
@Composable
fun TrackThicknessSettingsCard() {
    val context = LocalContext.current
    val density = LocalDensity.current

    var thicknessDp by remember { mutableStateOf(TrackStylePreferences.getThicknessDp(context)) }
    var textValue by remember { mutableStateOf(formatThicknessDp(thicknessDp)) }

    /** Retient une épaisseur à l'écran, ramenée dans les bornes autorisées. */
    fun applyThickness(dp: Float) {
        thicknessDp = dp.coerceIn(
            TrackStylePreferences.MIN_THICKNESS_DP,
            TrackStylePreferences.MAX_THICKNESS_DP
        )
    }

    /**
     * Enregistre l'épaisseur retenue.
     *
     * Séparé de [applyThickness] pour ne pas écrire dans les préférences à chaque
     * pixel de glissement du curseur : une seule traversée du curseur déclenchait
     * des dizaines d'écritures, chacune réveillant les écoutes de préférences.
     */
    fun persistThickness() {
        TrackStylePreferences.setThicknessDp(context, thicknessDp)
    }

    /**
     * Réaffiche dans le champ la valeur réellement retenue.
     *
     * Sans ça, taper « 99 » laissait « 99 » à l'écran alors que l'épaisseur avait été
     * ramenée au maximum, et « abc » restait affiché sans que rien ne le signale : le
     * champ contredisait en silence le curseur et l'aperçu situés juste au-dessus.
     */
    fun normalizeText() {
        textValue = formatThicknessDp(thicknessDp)
    }

    SettingsCard(modifier = Modifier.testTag("track_thickness_card")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Épaisseur du trait",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "S'applique à tous les tracés affichés sur la carte",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${formatThicknessDp(thicknessDp)} dp",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Aperçu : un trait à l'épaisseur choisie.
        //
        // La couleur est lue ici, et non dans le Canvas : la fonction de dessin est un
        // DrawScope, pas un contexte composable, et ne peut donc pas interroger le
        // thème. La constante figée qu'elle utilisait auparavant, elle, passait
        // n'importe où — c'est ce qui rendait l'erreur invisible.
        //
        // La conversion dp → pixels utilise la densité de l'écran, exactement comme
        // TrackStylePreferences.getStrokeWidth : l'aperçu montre donc la même
        // épaisseur que celle qui sera dessinée sur la carte.
        val previewColor = MaterialTheme.colorScheme.primary
        val previewStrokeWidthPx = with(density) { thicknessDp.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                drawLine(
                    color = previewColor,
                    start = Offset(24f, size.height / 2f),
                    end = Offset(size.width - 24f, size.height / 2f),
                    strokeWidth = previewStrokeWidthPx,
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = thicknessDp,
            onValueChange = {
                applyThickness(it)
                normalizeText()
            },
            onValueChangeFinished = { persistThickness() },
            valueRange = TrackStylePreferences.MIN_THICKNESS_DP..TrackStylePreferences.MAX_THICKNESS_DP,
            modifier = Modifier.fillMaxWidth().testTag("track_thickness_slider")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${formatThicknessDp(TrackStylePreferences.MIN_THICKNESS_DP)} dp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatThicknessDp(TrackStylePreferences.MAX_THICKNESS_DP)} dp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = textValue,
            onValueChange = { input ->
                textValue = input
                input.replace(',', '.').toFloatOrNull()?.let {
                    applyThickness(it)
                    persistThickness()
                }
            },
            label = { Text("Valeur précise (dp)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                // Une saisie hors bornes ou illisible est ramenée à l'affichage dès
                // que l'on quitte le champ, plutôt que de rester là à contredire
                // l'épaisseur réellement appliquée.
                .onFocusChanged { focus -> if (!focus.isFocused) normalizeText() }
                .testTag("track_thickness_field")
        )

        // La couleur se règle depuis l'historique, par appui long sur une catégorie.
        // Le geste étant indevinable, mieux vaut le rappeler là où on cherche à
        // changer l'apparence des tracés.
        SettingsHint(
            "Les couleurs se choisissent dans l'onglet Historique, par appui long sur " +
                    "« Enregistrés » ou « Importés »."
        )
    }
}

// ---------------------------------------------------------------------------
// Sauvegarde
// ---------------------------------------------------------------------------

@Composable
fun AutoBackupSettingsCard() {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(AutoBackupPreferences.isAutoBackupEnabled(context)) }
    var isGpx by remember { mutableStateOf(AutoBackupPreferences.isFormatGpx(context)) }
    var isKml by remember { mutableStateOf(AutoBackupPreferences.isFormatKml(context)) }

    SettingsCard(modifier = Modifier.testTag("auto_backup_settings_card")) {
        SettingsToggleRow(
            icon = Icons.Default.Save,
            title = "Sauvegarde automatique",
            description = "Exporte automatiquement chaque trajet terminé",
            checked = isEnabled,
            onCheckedChange = { checked ->
                isEnabled = checked
                AutoBackupPreferences.setAutoBackupEnabled(context, checked)
            },
            switchTestTag = "auto_backup_main_toggle"
        )

        AnimatedVisibility(visible = isEnabled) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Formats d'export",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Au moins un format doit rester coché, sinon la sauvegarde
                // automatique serait activée sans rien produire.
                FormatCheckboxRow(
                    label = "Format GPX (.gpx)",
                    checked = isGpx,
                    canUncheck = isKml,
                    testTag = "format_gpx_checkbox",
                    onCheckedChange = { checked ->
                        isGpx = checked
                        AutoBackupPreferences.setFormatGpx(context, checked)
                    }
                )
                FormatCheckboxRow(
                    label = "Format KML (.kml)",
                    checked = isKml,
                    canUncheck = isGpx,
                    testTag = "format_kml_checkbox",
                    onCheckedChange = { checked ->
                        isKml = checked
                        AutoBackupPreferences.setFormatKml(context, checked)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Destination",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PinDrop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Stockage local",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Enregistre dans Download/Mes parcours/ sur l'appareil",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Case de format d'export. [canUncheck] traduit la seule règle du bloc : on ne peut
 * pas décocher le dernier format restant.
 */
@Composable
private fun FormatCheckboxRow(
    label: String,
    checked: Boolean,
    canUncheck: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val toggle = {
        val next = !checked
        if (next || canUncheck) onCheckedChange(next)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { toggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { next -> if (next || canUncheck) onCheckedChange(next) },
            modifier = Modifier.testTag(testTag)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ---------------------------------------------------------------------------
// À propos
// ---------------------------------------------------------------------------

@Composable
private fun AboutCard() {
    var showReleaseNotesDialog by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = "Mes parcours",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Mes parcours",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Badge de version, cliquable pour ouvrir le journal des nouveautés.
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showReleaseNotesDialog = true }
                    .testTag("version_badge")
            ) {
                Text(
                    // Lue depuis BuildConfig : la version n'est écrite qu'une fois,
                    // dans build.gradle.kts, et l'affichage ne peut plus dériver.
                    text = "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (showReleaseNotesDialog) {
                ReleaseNotesDialog(onDismiss = { showReleaseNotesDialog = false })
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Une application de suivi GPS et de cartographie moderne pour enregistrer, " +
                        "analyser, fusionner et exporter vos parcours au format GPX, en toute sécurité.",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                fontFamily = FontFamily.SansSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Dédicace à Thierry
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "❤️",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column {
                        Text(
                            text = "Dédicace spéciale",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Application développée particulièrement pour mon père Thierry.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Open Source",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Code 100% libre",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "✍️", fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fait par ToftMalone",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Développeur",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Mes parcours • 2026",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** Une version et ce qu'elle a apporté. */
private class Release(val version: String, val changes: List<String>)

/**
 * Journal des nouveautés, de la version la plus récente à la plus ancienne.
 *
 * **Avant la 1.0-thierry** : la liste ne contient que la version courante. À chaque
 * nouvelle version, on remplace son contenu — les versions de développement se
 * succèdent trop vite pour qu'un historique ait de l'intérêt.
 *
 * **À partir de la 1.0-thierry** : on ajoute une entrée en tête au lieu de remplacer,
 * et l'historique commence à s'accumuler. L'affichage gère déjà plusieurs versions,
 * il n'y a rien d'autre à changer ce jour-là.
 *
 * La version courante est repérée par comparaison avec `BuildConfig.VERSION_NAME` :
 * elle n'est jamais à désigner à la main.
 */
private val RELEASES = listOf(
    Release(
        version = "0.11.8",
        changes = listOf(
            "Nouveau : les boutons d'enregistrement (démarrer, mettre en pause, arrêter) surgissent désormais avec un léger effet de zoom au lieu de sauter d'un jeu de boutons à l'autre, et le bouton pause/reprendre anime son icône et sa couleur"
        )
    )
)

@Composable
private fun ReleaseNotesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Journal des nouveautés",
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Journal des nouveautés",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            // Défilement : le journal s'allonge à chaque version, et sans lui les
            // versions les plus anciennes deviendraient inatteignables.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                RELEASES.forEachIndexed { index, release ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Tant qu'une seule version est listée, signaler laquelle est
                    // installée n'apprendrait rien.
                    ReleaseHeader(
                        release = release,
                        showCurrentBadge = RELEASES.size > 1
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    release.changes.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "• ",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_release_notes_button")
            ) {
                Text("Fermer", fontWeight = FontWeight.Bold)
            }
        },
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("release_notes_dialog")
    )
}

/** Numéro de version, marqué « actuelle » s'il s'agit de la version installée. */
@Composable
private fun ReleaseHeader(release: Release, showCurrentBadge: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "v${release.version}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (showCurrentBadge && release.version == BuildConfig.VERSION_NAME) {
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "actuelle",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
