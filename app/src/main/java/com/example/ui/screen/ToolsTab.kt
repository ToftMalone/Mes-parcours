package com.example.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.data.repository.TrackRepository
import com.example.ui.component.CookieShape
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MpSheet
import com.example.ui.component.MpSwitch
import com.example.ui.component.ShapeBadge
import com.example.ui.component.SheetHeader
import com.example.ui.component.SunShape
import com.example.ui.component.TrackTile
import com.example.ui.theme.DisplayFontFamily
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Outils disponibles. Le menu s'étoffera au fil des versions. */
private enum class Tool(val title: String) {
    MERGE("Fusionner"),
    SPLIT("Découper"),
    TRIM("Rogner")
}

/** Où en est un outil : réglages, travail en cours, résultat, ou rien à traiter. */
private sealed interface ToolPhase {
    data object Form : ToolPhase
    data object Busy : ToolPhase
    data class Done(val title: String, val body: String, val resultTrackId: Long?) : ToolPhase
    /** Pas assez de parcours pour que l'outil ait un sens. */
    data class Need(val title: String, val body: String) : ToolPhase
}

@Composable
fun ToolsTab(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    /** Un outil ouvert prend tout l'écran : la barre de navigation s'efface. */
    onInToolChanged: (Boolean) -> Unit = {},
    onOpenHistory: () -> Unit = {}
) {
    var openTool by remember { mutableStateOf<Tool?>(null) }

    LaunchedEffect(openTool) { onInToolChanged(openTool != null) }
    // Le retour arrière ferme l'outil avant de quitter l'onglet.
    BackHandler(enabled = openTool != null) { openTool = null }

    val close = { openTool = null }
    when (openTool) {
        null -> ToolsMenu(onOpenTool = { openTool = it }, contentPadding = contentPadding, modifier = modifier)
        Tool.MERGE -> MergeTracksTool(viewModel, onNavigateToDetails, close, modifier)
        Tool.SPLIT -> SplitTrackTool(viewModel, onOpenHistory, close, modifier)
        Tool.TRIM -> TrimTrackTool(viewModel, onNavigateToDetails, close, modifier)
    }
}

// ------------------------------------------------------------------------ Menu

@Composable
private fun ToolsMenu(
    onOpenTool: (Tool) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding() + 20.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp)
            .padding(horizontal = 16.dp)
            .testTag("tools_tab")
    ) {
        Text(
            "Outils",
            style = MaterialTheme.typography.headlineLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            "Transformez vos parcours. Découper et rogner créent des copies : l'original " +
                "reste, sauf si vous choisissez de le supprimer.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)
        )
        Spacer(modifier = Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolMenuEntry(
                icon = Icons.Rounded.Merge,
                shape = CookieShape,
                container = colors.primaryContainer,
                content = colors.onPrimaryContainer,
                title = "Fusionner",
                subtitle = "Réunir plusieurs parcours en un seul, dans l'ordre chronologique.",
                onClick = { onOpenTool(Tool.MERGE) },
                testTag = "open_merge_tool_button"
            )
            ToolMenuEntry(
                icon = Icons.Rounded.ContentCut,
                shape = RoundedCornerShape(40),
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                title = "Découper",
                subtitle = "Séparer un parcours à chaque tronçon ou à chaque longue pause.",
                onClick = { onOpenTool(Tool.SPLIT) },
                testTag = "open_split_tool_button"
            )
            ToolMenuEntry(
                icon = Icons.Rounded.TimerOff,
                shape = SunShape,
                container = colors.secondaryContainer,
                content = colors.onSecondaryContainer,
                title = "Rogner",
                subtitle = "Retirer des minutes au début ou à la fin d'un parcours.",
                onClick = { onOpenTool(Tool.TRIM) },
                testTag = "open_trim_tool_button"
            )
        }
    }
}

@Composable
private fun ToolMenuEntry(
    icon: ImageVector,
    shape: Shape,
    container: Color,
    content: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(20.dp)
            .testTag(testTag)
    ) {
        ShapeBadge(icon = icon, containerColor = container, contentColor = content, shape = shape, size = 76.dp, iconSize = 34.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------ Gabarit commun des outils

/**
 * Gabarit d'un outil ouvert : en-tête avec retour, contenu défilant, et bouton
 * d'action en bas — ou, selon [phase], l'état « en cours », le résultat, ou l'écran
 * qui explique qu'il manque des parcours.
 */
@Composable
private fun ToolScaffold(
    tool: Tool,
    phase: ToolPhase,
    onBack: () -> Unit,
    screenTag: String,
    backTag: String,
    modifier: Modifier = Modifier,
    busyTitle: String = "",
    busySubtitle: String = "",
    headerExtra: (@Composable () -> Unit)? = null,
    doneActions: @Composable () -> Unit = {},
    action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(screenTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp)
                .height(56.dp)
                .fillMaxWidth()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(backTag)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour aux outils")
            }
            Text(tool.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (phase == ToolPhase.Form) headerExtra?.invoke()
        }

        when (phase) {
            ToolPhase.Form -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                content = content
            )
            ToolPhase.Busy -> BusyState(busyTitle, busySubtitle, Modifier.weight(1f))
            is ToolPhase.Done -> DoneState(phase.title, phase.body, doneActions, Modifier.weight(1f))
            is ToolPhase.Need -> NeedTracksState(phase.title, phase.body, onBack, Modifier.weight(1f))
        }

        if (phase == ToolPhase.Form) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) { action() }
        }
    }
}

/** Bouton d'action d'un outil : 60 dp, pleine largeur. */
@Composable
private fun ToolActionButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(30.dp),
        color = if (enabled) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Travail en cours : un cookie qui tourne, et une vague qui avance. */
@Composable
private fun BusyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "tool_busy")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "tool_busy_angle"
    )
    val phase by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "tool_busy_wave"
    )
    val wave = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .padding(bottom = 80.dp)
            .testTag("tool_busy")
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .rotate(angle)
                .clip(CookieShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = null), textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Canvas(
            modifier = Modifier
                .padding(top = 22.dp)
                .width(240.dp)
                .height(12.dp)
        ) {
            val amplitude = size.height / 3f
            val wavelength = 24.dp.toPx()
            val shift = phase * wavelength
            fun y(x: Float) = size.height / 2 + amplitude * sin((x + shift) / wavelength * 2 * PI).toFloat()
            val path = Path()
            path.moveTo(0f, y(0f))
            var x = 2f
            while (x <= size.width) {
                path.lineTo(x, y(x))
                x += 2f
            }
            drawPath(path, wave, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun DoneState(
    title: String,
    body: String,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(bottom = 80.dp)
            .testTag("tool_done")
    ) {
        ShapeBadge(
            icon = Icons.Rounded.Check,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = SunShape,
            size = 150.dp,
            iconSize = 80.dp
        )
        Spacer(modifier = Modifier.height(26.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFontFamily, fontSize = 30.sp, lineHeight = 34.sp),
            textAlign = TextAlign.Center
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/** Il manque des parcours pour que l'outil ait un sens. */
@Composable
private fun NeedTracksState(title: String, body: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp)
            .padding(bottom = 80.dp)
            .testTag("tool_need_tracks")
    ) {
        ShapeBadge(
            icon = Icons.Rounded.PlaylistAdd,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = CookieShape,
            size = 150.dp,
            iconSize = 64.dp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = null), textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        Spacer(modifier = Modifier.height(22.dp))
        PillButton(
            "Retour aux outils",
            onClick = onBack,
            container = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            testTag = "tool_need_back_button"
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    container: Color,
    contentColor: Color,
    testTag: String? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        color = container,
        contentColor = contentColor,
        modifier = Modifier
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 22.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

/** Les deux boutons de fin d'un outil : « Terminé », et l'accès au résultat. */
@Composable
private fun DoneButtons(onFinish: () -> Unit, primaryLabel: String?, onPrimary: () -> Unit, primaryTag: String) {
    PillButton("Terminé", onFinish, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, "tool_done_button")
    if (primaryLabel != null) {
        PillButton(primaryLabel, onPrimary, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, primaryTag)
    }
}

// ------------------------------------------------------------ Briques communes

private fun displayColor(track: Track) = Color(
    TrackStylePreferences.resolveTrackColor(track.displayColor, track.sourceColor, track.isImported, track.isMerged)
)

private fun categoryOf(track: Track) = when {
    track.isMerged -> "Fusionné"
    track.isImported -> "Importé"
    else -> "Enregistré"
}

/** Champ de saisie « rempli » de la maquette : fond neutre, trait primaire dessous. */
@Composable
private fun FilledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTag: String,
    enabled: Boolean = true
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontWeight = FontWeight.Bold) },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}

/** Ligne d'explication : icône primaire et texte discret. */
@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun SwitchCard(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit, testTag: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MpSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Parcours choisi pour découper ou rogner ; l'appui ouvre la liste des parcours. */
@Composable
private fun SelectedTrackCard(track: Track, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .testTag("selected_track_card")
    ) {
        TrackTile(color = displayColor(track), size = 44.dp, iconSize = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.UnfoldMore, contentDescription = "Changer de parcours", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Choix d'un parcours, dans une feuille : pour découper ou rogner. */
@Composable
private fun TrackPickerSheet(tracks: List<Track>, selectedId: Long?, onPick: (Track) -> Unit, onDismiss: () -> Unit) {
    MpSheet(onDismissRequest = onDismiss) {
        SheetHeader("Choisir un parcours")
        Spacer(modifier = Modifier.height(14.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            tracks.forEach { track ->
                val selected = track.id == selectedId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onPick(track) }
                        .padding(horizontal = 12.dp)
                        .testTag("single_track_row_${track.id}")
                ) {
                    TrackTile(color = displayColor(track))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${categoryOf(track)} · ${FormatUtils.formatShortDate(track.startTime)} · ${FormatUtils.formatDistanceShort(track.totalDistance)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Choisi",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("single_track_radio_${track.id}")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Curseur de la maquette (Material 3 Expressive) : piste épaisse coupée d'un vide
 * de part et d'autre d'une poignée verticale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MpSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    testTag: String
) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.secondaryContainer
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .testTag(testTag),
        thumb = {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(active)
            )
        },
        track = { state: SliderState ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
            ) {
                val gap = 6.dp.toPx()
                val split = size.width * fraction
                val r = CornerRadius(8.dp.toPx())
                val leftWidth = split - gap
                if (leftWidth > 0f) drawRoundRect(active, Offset.Zero, Size(leftWidth, size.height), r)
                val rightStart = split + gap
                if (rightStart < size.width) {
                    drawRoundRect(inactive, Offset(rightStart, 0f), Size(size.width - rightStart, size.height), r)
                }
            }
        }
    )
}

// ------------------------------------------------------------------ Fusionner

@Composable
private fun MergeTracksTool(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val tracks by viewModel.allTracks.collectAsState()

    var selectedSourceIds by remember { mutableStateOf(emptySet<Long>()) }
    var mergedName by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<ToolPhase>(ToolPhase.Form) }

    val selectedTracks = tracks.filter { it.id in selectedSourceIds }.sortedBy { it.startTime }

    /**
     * Le parcours qui accueille les autres est **le plus ancien de la sélection**, et
     * n'est plus demandé.
     *
     * C'était déjà celui que l'écran proposait par défaut, et le choix n'avait guère
     * de sens à poser : les points sont de toute façon recopiés dans l'ordre
     * chronologique, si bien que la destination ne change ni le tracé, ni les
     * statistiques, ni le nom — seulement la ligne de la base qui survit et, avec
     * elle, la catégorie du résultat. Partir du plus ancien donne la réponse
     * attendue : la fusion se range là où commence le voyage.
     */
    val destinationTrack = selectedTracks.firstOrNull()

    // Le nom proposé est celui du parcours d'accueil.
    LaunchedEffect(destinationTrack?.id) {
        destinationTrack?.let { mergedName = it.name }
    }

    val n = selectedTracks.size
    val canMerge = n >= 2 && destinationTrack != null && mergedName.isNotBlank()
    val shownPhase = if (phase == ToolPhase.Form && tracks.size < 2) {
        ToolPhase.Need(
            if (tracks.isEmpty()) "Aucun parcours à fusionner" else "Il faut au moins 2 parcours",
            if (tracks.isEmpty()) "Enregistrez ou importez au moins deux parcours, puis revenez ici."
            else "Vous n'avez qu'un seul parcours pour l'instant. Enregistrez ou importez-en un autre, puis revenez ici."
        )
    } else phase

    ToolScaffold(
        tool = Tool.MERGE,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "merge_tool",
        backTag = "merge_tool_back_button",
        modifier = modifier,
        busyTitle = "Fusion en cours…",
        busySubtitle = "Tri chronologique des points",
        headerExtra = {
            if (n > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Text(
                        if (n > 1) "$n sélectionnés" else "1 sélectionné",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        },
        doneActions = {
            val result = (phase as? ToolPhase.Done)?.resultTrackId
            DoneButtons(
                onFinish = onBack,
                primaryLabel = if (result != null) "Voir le parcours" else null,
                onPrimary = {
                    if (result != null) {
                        viewModel.selectTrack(result)
                        onNavigateToDetails(result)
                    }
                },
                primaryTag = "merge_open_result"
            )
        },
        action = {
            ToolActionButton(
                text = if (n >= 2) "Fusionner $n parcours" else "Sélectionnez au moins 2 parcours",
                icon = Icons.Rounded.Merge,
                enabled = canMerge,
                testTag = "confirm_merge_button",
                onClick = {
                    val destination = destinationTrack ?: return@ToolActionButton
                    val name = mergedName.trim()
                    val count = n
                    phase = ToolPhase.Busy
                    viewModel.mergeTracks(
                        context = context,
                        trackIds = selectedTracks.map { it.id },
                        destinationTrackId = destination.id,
                        mergedName = name,
                        onSuccess = { mergedId ->
                            selectedSourceIds = emptySet()
                            phase = ToolPhase.Done(
                                title = "Parcours fusionnés",
                                body = "« $name » réunit désormais les $count parcours, dans l'ordre chronologique.",
                                resultTrackId = mergedId
                            )
                        },
                        onError = { error ->
                            phase = ToolPhase.Form
                            messenger.show(error, Icons.Rounded.Error, isError = true)
                        }
                    )
                }
            )
        }
    ) {
        Text(
            "Choisissez au moins 2 parcours.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tracks.sortedByDescending { it.startTime }.forEach { track ->
                val isSelected = track.id in selectedSourceIds
                MergeTrackRow(track, isSelected) {
                    selectedSourceIds = if (isSelected) selectedSourceIds - track.id else selectedSourceIds + track.id
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        FilledField(
            value = mergedName,
            onValueChange = { mergedName = it },
            label = "Nom du résultat",
            enabled = n >= 2,
            testTag = "merged_name_field"
        )
        Spacer(modifier = Modifier.height(12.dp))
        // La fusion réunit les parcours choisis en un seul : ils ne restent pas à côté.
        // La maquette promettait l'inverse ; le texte dit ce que fait réellement l'outil.
        InfoLine(
            Icons.Rounded.Sort,
            "Les points sont remis dans l'ordre chronologique. Les parcours choisis sont " +
                "réunis en un seul, rangé dans la catégorie du plus ancien."
        )
    }
}

@Composable
private fun MergeTrackRow(track: Track, selected: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowBg by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerLow, label = "merge_row")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(rowBg)
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp)
            .testTag("merge_source_${track.id}")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) colors.primary else Color.Transparent)
                .border(2.dp, if (selected) colors.primary else colors.onSurfaceVariant, RoundedCornerShape(7.dp))
                .testTag("merge_checkbox_${track.id}")
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = "Sélectionné", tint = colors.onPrimary, modifier = Modifier.size(18.dp))
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(displayColor(track))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${categoryOf(track)} · ${FormatUtils.formatDistanceShort(track.totalDistance)}", fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------- Découper

/**
 * Seuils de pause proposés, de 5 minutes à 6 heures. Une liste plutôt qu'un pas
 * régulier : entre 5 et 30 minutes chaque cran compte, au-delà d'une heure seule
 * l'heure compte.
 */
private val PAUSE_STEPS_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300, 360)

private fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> String.format(Locale.FRANCE, "%d h %02d", minutes / 60, minutes % 60)
}

@Composable
private fun SplitTrackTool(
    viewModel: TrackViewModel,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messenger = LocalAppMessenger.current
    val tracks by viewModel.allTracks.collectAsState()
    val eligibleTracks = tracks.filter { !it.isRecording }.sortedByDescending { it.startTime }

    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    val selectedTrack = eligibleTracks.find { it.id == selectedTrackId } ?: eligibleTracks.firstOrNull()

    var mode by remember { mutableStateOf(TrackRepository.SplitMode.SEGMENT_BREAKS) }
    var pauseIndex by remember { mutableIntStateOf(PAUSE_STEPS_MINUTES.indexOf(30)) }
    var baseName by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<ToolPhase>(ToolPhase.Form) }
    var picking by remember { mutableStateOf(false) }

    // Le nom proposé est celui du parcours choisi : les morceaux s'appelleront
    // « … (1) », « … (2) », etc.
    LaunchedEffect(selectedTrack?.id) {
        selectedTrack?.let { baseName = it.name }
    }

    val pauseMinutes = PAUSE_STEPS_MINUTES[pauseIndex]
    val shownPhase = if (phase == ToolPhase.Form && selectedTrack == null) {
        ToolPhase.Need("Aucun parcours à découper", "Enregistrez ou importez un parcours pour pouvoir le découper ici.")
    } else phase

    ToolScaffold(
        tool = Tool.SPLIT,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "split_tool",
        backTag = "split_tool_back_button",
        modifier = modifier,
        busyTitle = "Découpage en cours…",
        busySubtitle = if (mode == TrackRepository.SplitMode.TIME_GAP) "Recherche des pauses" else "Recherche des tronçons",
        doneActions = {
            DoneButtons(onFinish = onBack, primaryLabel = "Voir l'historique", onPrimary = onOpenHistory, primaryTag = "split_open_history")
        },
        action = {
            ToolActionButton(
                text = "Découper le parcours",
                icon = Icons.Rounded.ContentCut,
                enabled = selectedTrack != null && baseName.isNotBlank(),
                testTag = "confirm_split_button",
                onClick = {
                    val track = selectedTrack ?: return@ToolActionButton
                    val base = baseName.trim()
                    val deleting = deleteSource
                    phase = ToolPhase.Busy
                    viewModel.splitTrack(
                        trackId = track.id,
                        mode = mode,
                        gapMillis = pauseMinutes * 60_000L,
                        baseName = base,
                        deleteSource = deleting,
                        onSuccess = { newTrackIds ->
                            val count = newTrackIds.size
                            phase = ToolPhase.Done(
                                title = "Découpé en $count parcours",
                                body = "De « $base (1) » à « $base ($count) », dans l'historique. " +
                                    if (deleting) "L'original a été supprimé." else "L'original est conservé.",
                                resultTrackId = null
                            )
                        },
                        onError = { error ->
                            phase = ToolPhase.Form
                            messenger.show(error, Icons.Rounded.Error, isError = true)
                        }
                    )
                }
            )
        }
    ) {
        val track = selectedTrack ?: return@ToolScaffold
        SelectedTrackCard(
            track = track,
            subtitle = "${categoryOf(track)} · ${FormatUtils.formatDistanceShort(track.totalDistance)} · ${FormatUtils.formatDurationShort(track.duration)}",
            onClick = { picking = true }
        )

        SectionLabel("Mode de découpe")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SegmentButton(
                "À chaque tronçon", Icons.Rounded.Splitscreen,
                selected = mode == TrackRepository.SplitMode.SEGMENT_BREAKS,
                onClick = { mode = TrackRepository.SplitMode.SEGMENT_BREAKS },
                testTag = "split_mode_segments",
                modifier = Modifier.weight(1f)
            )
            SegmentButton(
                "À chaque pause", Icons.Rounded.Coffee,
                selected = mode == TrackRepository.SplitMode.TIME_GAP,
                onClick = { mode = TrackRepository.SplitMode.TIME_GAP },
                testTag = "split_mode_gap",
                modifier = Modifier.weight(1f)
            )
        }
        if (mode == TrackRepository.SplitMode.SEGMENT_BREAKS) {
            Text(
                "Pour un fichier importé qui réunit plusieurs trajets : chaque tronçon devient un parcours.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Text("Couper après une pause d'au moins", style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp))
                Text(formatMinutes(pauseMinutes), style = MaterialTheme.typography.headlineMedium)
                MpSlider(
                    value = pauseIndex.toFloat(),
                    onValueChange = { pauseIndex = it.roundToInt().coerceIn(0, PAUSE_STEPS_MINUTES.lastIndex) },
                    valueRange = 0f..PAUSE_STEPS_MINUTES.lastIndex.toFloat(),
                    steps = PAUSE_STEPS_MINUTES.size - 2,
                    testTag = "split_gap_slider"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("5 min", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("6 h", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Sans effet sur un KML, ou sur un GPX sans horodatage : leurs points sont " +
                        "régulièrement espacés, il n'y a aucune pause à trouver.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        FilledField(baseName, { baseName = it }, "Nom de base", "split_base_name_field")
        if (baseName.isNotBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                listOf("${baseName.trim()} (1)", "${baseName.trim()} (2)").forEach { name ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .height(32.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SwitchCard(
            title = "Supprimer l'original",
            subtitle = "Les morceaux en contiennent tous les points",
            checked = deleteSource,
            onToggle = { deleteSource = !deleteSource },
            testTag = "split_delete_source_row"
        )
        Spacer(modifier = Modifier.height(14.dp))
        InfoLine(Icons.Rounded.Verified, "Rien n'est perdu : chaque point se retrouve dans l'un des morceaux.")
    }

    if (picking) {
        TrackPickerSheet(
            tracks = eligibleTracks,
            selectedId = selectedTrack?.id,
            onPick = {
                selectedTrackId = it.id
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 28.dp else 12.dp, spring(0.6f, 800f), label = "segment_radius")
    val bg by animateColorAsState(if (selected) colors.primary else colors.surfaceContainer, label = "segment_bg")
    val fg by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, label = "segment_fg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .testTag(testTag)
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --------------------------------------------------------------------- Rogner

/** Bornes des curseurs de rognage, en minutes, et pas du curseur. */
private const val TRIM_MAX_MINUTES = 60f
private const val TRIM_SLIDER_STEPS = 59 // un cran par minute

@Composable
private fun TrimTrackTool(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messenger = LocalAppMessenger.current
    val tracks by viewModel.allTracks.collectAsState()
    val eligibleTracks = tracks.filter { !it.isRecording }.sortedByDescending { it.startTime }

    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    val selectedTrack = eligibleTracks.find { it.id == selectedTrackId } ?: eligibleTracks.firstOrNull()

    var dropStartMinutes by remember { mutableFloatStateOf(0f) }
    var dropEndMinutes by remember { mutableFloatStateOf(0f) }
    var newName by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<ToolPhase>(ToolPhase.Form) }
    var picking by remember { mutableStateOf(false) }

    // Le nom proposé est celui du parcours choisi, suffixé : le résultat est une
    // copie, et deux lignes du même nom dans l'historique ne se distingueraient pas.
    LaunchedEffect(selectedTrack?.id) {
        selectedTrack?.let { newName = "${it.name} (rogné)" }
    }

    val a = dropStartMinutes.roundToInt()
    val b = dropEndMinutes.roundToInt()
    val shownPhase = if (phase == ToolPhase.Form && selectedTrack == null) {
        ToolPhase.Need("Aucun parcours à rogner", "Enregistrez ou importez un parcours pour pouvoir le rogner ici.")
    } else phase

    ToolScaffold(
        tool = Tool.TRIM,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "trim_tool",
        backTag = "trim_tool_back_button",
        modifier = modifier,
        busyTitle = "Rognage en cours…",
        busySubtitle = "Création de la copie",
        doneActions = {
            val result = (phase as? ToolPhase.Done)?.resultTrackId
            DoneButtons(
                onFinish = onBack,
                primaryLabel = if (result != null) "Voir le parcours" else null,
                onPrimary = {
                    if (result != null) {
                        viewModel.selectTrack(result)
                        onNavigateToDetails(result)
                    }
                },
                primaryTag = "trim_open_result"
            )
        },
        action = {
            ToolActionButton(
                text = "Créer la copie rognée",
                icon = Icons.Rounded.TimerOff,
                enabled = selectedTrack != null && newName.isNotBlank() && (a > 0 || b > 0),
                testTag = "confirm_trim_button",
                onClick = {
                    val track = selectedTrack ?: return@ToolActionButton
                    val name = newName.trim()
                    val deleting = deleteSource
                    phase = ToolPhase.Busy
                    viewModel.trimTrack(
                        trackId = track.id,
                        dropStartMillis = a * 60_000L,
                        dropEndMillis = b * 60_000L,
                        newName = name,
                        deleteSource = deleting,
                        onSuccess = { newTrackId ->
                            phase = ToolPhase.Done(
                                title = "Copie rognée créée",
                                body = "« $name » est dans l'historique. " +
                                    if (deleting) "L'original a été supprimé." else "L'original est conservé.",
                                resultTrackId = newTrackId
                            )
                        },
                        onError = { error ->
                            phase = ToolPhase.Form
                            messenger.show(error, Icons.Rounded.Error, isError = true)
                        }
                    )
                }
            )
        }
    ) {
        val track = selectedTrack ?: return@ToolScaffold
        val time = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val end = if (track.endTime > track.startTime) track.endTime else track.startTime + track.duration * 1000
        SelectedTrackCard(
            track = track,
            subtitle = "${time.format(Date(track.startTime))} → ${time.format(Date(end))} · ${FormatUtils.formatDurationShort(track.duration)}",
            onClick = { picking = true }
        )

        Spacer(modifier = Modifier.height(18.dp))
        KeptDurationBar(totalSeconds = track.duration, dropStartMinutes = a, dropEndMinutes = b)

        Spacer(modifier = Modifier.height(12.dp))
        TrimSliderCard("Retirer au début", a, dropStartMinutes, { dropStartMinutes = it }, "trim_start_slider")
        Spacer(modifier = Modifier.height(8.dp))
        TrimSliderCard("Retirer à la fin", b, dropEndMinutes, { dropEndMinutes = it }, "trim_end_slider")

        Spacer(modifier = Modifier.height(12.dp))
        FilledField(newName, { newName = it }, "Nom du résultat", "trim_name_field")
        Spacer(modifier = Modifier.height(10.dp))
        SwitchCard(
            title = "Supprimer l'original",
            subtitle = "Le résultat est une copie",
            checked = deleteSource,
            onToggle = { deleteSource = !deleteSource },
            testTag = "trim_delete_source_row"
        )
        if (deleteSource && (a > 0 || b > 0)) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("trim_delete_warning")
            ) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                Text(
                    trimWarning(a, b),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }

    if (picking) {
        TrackPickerSheet(
            tracks = eligibleTracks,
            selectedId = selectedTrack?.id,
            onPick = {
                selectedTrackId = it.id
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

private fun trimWarning(a: Int, b: Int): String {
    val parts = buildList {
        if (a > 0) add("les $a premières minutes")
        if (b > 0) add("les $b dernières minutes")
    }
    val what = parts.joinToString(" et ").replaceFirstChar { it.uppercase() }
    return "$what seront perdues définitivement avec l'original."
}

@Composable
private fun TrimSliderCard(label: String, minutes: Int, value: Float, onValueChange: (Float) -> Unit, testTag: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            Text("$minutes min", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp))
        }
        MpSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..TRIM_MAX_MINUTES,
            steps = TRIM_SLIDER_STEPS,
            testTag = testTag
        )
    }
}

/**
 * Barre de la durée conservée : le parcours entier, dont les bouts retirés sont
 * hachurés, en proportion de leur durée réelle.
 */
@Composable
private fun KeptDurationBar(totalSeconds: Long, dropStartMinutes: Int, dropEndMinutes: Int) {
    val colors = MaterialTheme.colorScheme
    val total = totalSeconds.coerceAtLeast(1L).toFloat()
    val startFraction = (dropStartMinutes * 60f / total).coerceIn(0f, 1f)
    val endFraction = (dropEndMinutes * 60f / total).coerceIn(0f, 1f - startFraction)
    val keptSeconds = (totalSeconds - (dropStartMinutes + dropEndMinutes) * 60L).coerceAtLeast(0L)
    val hatchLight = colors.errorContainer
    val hatchDark = colors.error
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.primary)
            .testTag("trim_kept_bar")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun hatch(x0: Float, width: Float) {
                if (width <= 0f) return
                clipRect(left = x0, top = 0f, right = x0 + width, bottom = size.height) {
                    drawRect(hatchLight, Offset(x0, 0f), Size(width, size.height))
                    val step = 8.dp.toPx()
                    var x = x0 - size.height
                    while (x < x0 + width) {
                        drawLine(hatchDark, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.dp.toPx())
                        x += step
                    }
                }
            }
            hatch(0f, size.width * startFraction)
            hatch(size.width * (1f - endFraction), size.width * endFraction)
        }
        Text(
            if (keptSeconds > 0) "${FormatUtils.formatDurationShort(keptSeconds)} conservées" else "Rien ne resterait",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onPrimary
        )
    }
}
