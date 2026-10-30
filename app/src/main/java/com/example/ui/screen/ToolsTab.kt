package com.example.ui.screen

import androidx.annotation.StringRes
import com.example.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
import com.example.data.model.TrackPoint
import com.example.ui.component.PortionPickerMap
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.CleaningServices
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
import androidx.compose.runtime.mutableStateListOf
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
import com.example.ui.component.MpTextButton
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
import com.example.util.PortionBound
import kotlinx.coroutines.launch
import com.example.util.TrackStylePreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Outils disponibles. Le menu s'étoffera au fil des versions. */
private enum class Tool(@StringRes val titleRes: Int) {
    MERGE(R.string.tool_merge),
    SPLIT(R.string.tool_split),
    CLEAN(R.string.tool_clean),
    EXTEND(R.string.tool_extend)
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
        Tool.CLEAN -> CleanTrackTool(viewModel, onNavigateToDetails, close, modifier)
        Tool.EXTEND -> ExtendTrackTool(viewModel, onNavigateToDetails, close, modifier)
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
            stringResource(R.string.tools_title),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            stringResource(R.string.tools_intro),
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
                title = stringResource(R.string.tool_merge),
                subtitle = stringResource(R.string.tool_merge_subtitle),
                onClick = { onOpenTool(Tool.MERGE) },
                testTag = "open_merge_tool_button"
            )
            ToolMenuEntry(
                icon = Icons.Rounded.ContentCut,
                shape = RoundedCornerShape(40),
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                title = stringResource(R.string.tool_split),
                subtitle = stringResource(R.string.tool_split_subtitle),
                onClick = { onOpenTool(Tool.SPLIT) },
                testTag = "open_split_tool_button"
            )
            ToolMenuEntry(
                icon = Icons.Rounded.CleaningServices,
                shape = CookieShape,
                container = colors.tertiaryContainer,
                content = colors.onTertiaryContainer,
                title = stringResource(R.string.tool_clean),
                subtitle = stringResource(R.string.tool_clean_subtitle),
                onClick = { onOpenTool(Tool.CLEAN) },
                testTag = "open_clean_tool_button"
            )
            ToolMenuEntry(
                icon = Icons.Rounded.Route,
                shape = SunShape,
                container = colors.primaryContainer,
                content = colors.onPrimaryContainer,
                title = stringResource(R.string.tool_extend),
                subtitle = stringResource(R.string.tool_extend_subtitle),
                onClick = { onOpenTool(Tool.EXTEND) },
                testTag = "open_extend_tool_button"
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
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.tools_back))
            }
            Text(stringResource(tool.titleRes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
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
            stringResource(R.string.tools_back),
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
    PillButton(stringResource(R.string.tool_done), onFinish, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface, "tool_done_button")
    if (primaryLabel != null) {
        PillButton(primaryLabel, onPrimary, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, primaryTag)
    }
}

// ------------------------------------------------------------ Briques communes

private fun displayColor(track: Track) = Color(
    TrackStylePreferences.resolveTrackColor(track.displayColor, track.sourceColor, track.isImported, track.isMerged)
)

@Composable
private fun categoryOf(track: Track) = stringResource(
    when {
        track.isMerged -> R.string.track_kind_merged
        track.isImported -> R.string.track_kind_imported
        else -> R.string.track_kind_recorded
    }
)

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

/** Parcours choisi pour découper, nettoyer ou prolonger ; l'appui ouvre la liste des parcours. */
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
        Icon(Icons.Rounded.UnfoldMore, contentDescription = stringResource(R.string.picker_change_track), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Choix d'un parcours, dans une feuille : pour découper, nettoyer ou prolonger. */
@Composable
private fun TrackPickerSheet(tracks: List<Track>, selectedId: Long?, onPick: (Track) -> Unit, onDismiss: () -> Unit) {
    MpSheet(onDismissRequest = onDismiss) {
        SheetHeader(stringResource(R.string.picker_title))
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
                            stringResource(R.string.track_row_subtitle_date, categoryOf(track), FormatUtils.formatShortDate(track.startTime), FormatUtils.formatDistanceShort(track.totalDistance)),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = stringResource(R.string.picker_chosen),
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
            stringResource(if (tracks.isEmpty()) R.string.merge_need_none_title else R.string.merge_need_one_title),
            stringResource(if (tracks.isEmpty()) R.string.merge_need_none_body else R.string.merge_need_one_body)
        )
    } else phase

    ToolScaffold(
        tool = Tool.MERGE,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "merge_tool",
        backTag = "merge_tool_back_button",
        modifier = modifier,
        busyTitle = stringResource(R.string.merge_busy_title),
        busySubtitle = stringResource(R.string.merge_busy_subtitle),
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
                        stringResource(R.string.merge_selected_count, n),
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
                primaryLabel = if (result != null) stringResource(R.string.tool_view_track) else null,
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
                text = if (n >= 2) stringResource(R.string.merge_action, n) else stringResource(R.string.merge_action_disabled),
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
                                title = context.getString(R.string.merge_done_title),
                                body = context.getString(R.string.merge_done_body, name, count),
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
            stringResource(R.string.merge_hint),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
        )
        // Le parcours en cours d'enregistrement n'est pas proposé : le fusionner
        // supprimerait sa ligne sous le service (voir mergeAndSaveTracks).
        val mergeable = tracks.filter { !viewModel.isBeingRecorded(it) }.sortedByDescending { it.startTime }
        val allSelected = mergeable.isNotEmpty() && mergeable.all { it.id in selectedSourceIds }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            MpTextButton(
                text = stringResource(if (allSelected) R.string.common_deselect_all else R.string.common_select_all),
                onClick = {
                    selectedSourceIds = if (allSelected) emptySet() else mergeable.map { it.id }.toSet()
                },
                modifier = Modifier.testTag("merge_select_all")
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            mergeable.forEach { track ->
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
            label = stringResource(R.string.tool_result_name_label),
            enabled = n >= 2,
            testTag = "merged_name_field"
        )
        Spacer(modifier = Modifier.height(12.dp))
        // La fusion réunit les parcours choisis en un seul : ils ne restent pas à côté.
        // La maquette promettait l'inverse ; le texte dit ce que fait réellement l'outil.
        InfoLine(
            Icons.Rounded.Sort,
            stringResource(R.string.merge_info)
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
            if (selected) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_selected_cd), tint = colors.onPrimary, modifier = Modifier.size(18.dp))
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

@Composable
private fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.duration_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.duration_hours, minutes / 60)
    else -> stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}

@Composable
private fun SplitTrackTool(
    viewModel: TrackViewModel,
    onOpenHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val tracks by viewModel.allTracks.collectAsState()
    val eligibleTracks = tracks.filter { !it.isRecording }.sortedByDescending { it.startTime }

    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    val selectedTrack = eligibleTracks.find { it.id == selectedTrackId } ?: eligibleTracks.firstOrNull()

    var mode by remember { mutableStateOf(TrackRepository.SplitMode.SEGMENT_BREAKS) }
    // Troisième mode, demandé par le père de l'auteur : désigner sur la carte la
    // portion à retirer. Les deux autres coupent sans rien perdre ; celui-ci retire.
    var onMap by remember { mutableStateOf(false) }
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

    // Le choix sur la carte : l'aperçu du parcours entier (pour cadrer), le détail de la
    // zone visible (pour dessiner et viser), et les deux bornes, désignées par leurs points.
    var preview by remember(selectedTrack?.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var detail by remember(selectedTrack?.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var viewport by remember(selectedTrack?.id) { mutableStateOf<org.osmdroid.util.BoundingBox?>(null) }
    var cutStart by remember(selectedTrack?.id) { mutableStateOf<PortionBound?>(null) }
    var cutEnd by remember(selectedTrack?.id) { mutableStateOf<PortionBound?>(null) }
    LaunchedEffect(selectedTrack?.id, onMap) {
        val id = selectedTrack?.id ?: return@LaunchedEffect
        if (onMap && preview == null) {
            val loaded = viewModel.loadPreviewPoints(id)
            preview = loaded
            if (detail == null) detail = loaded
        }
    }
    LaunchedEffect(selectedTrack?.id, viewport) {
        val id = selectedTrack?.id ?: return@LaunchedEffect
        val box = viewport ?: return@LaunchedEffect
        detail = viewModel.loadPickerPoints(id, box.latSouth, box.latNorth, box.lonWest, box.lonEast)
    }

    val pauseMinutes = PAUSE_STEPS_MINUTES[pauseIndex]
    val shownPhase = if (phase == ToolPhase.Form && selectedTrack == null) {
        ToolPhase.Need(stringResource(R.string.split_need_title), stringResource(R.string.split_need_body))
    } else phase

    ToolScaffold(
        tool = Tool.SPLIT,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "split_tool",
        backTag = "split_tool_back_button",
        modifier = modifier,
        busyTitle = stringResource(R.string.split_busy_title),
        busySubtitle = stringResource(
            when {
                onMap -> R.string.cut_busy
                mode == TrackRepository.SplitMode.TIME_GAP -> R.string.split_busy_gap
                else -> R.string.split_busy_segments
            }
        ),
        doneActions = {
            DoneButtons(onFinish = onBack, primaryLabel = stringResource(R.string.split_view_history), onPrimary = onOpenHistory, primaryTag = "split_open_history")
        },
        action = {
            ToolActionButton(
                text = stringResource(if (onMap) R.string.cut_action else R.string.split_action),
                icon = Icons.Rounded.ContentCut,
                enabled = selectedTrack != null && baseName.isNotBlank() &&
                    (!onMap || (cutStart != null && cutEnd != null && cutStart?.compareTo(cutEnd!!) != 0)),
                testTag = "confirm_split_button",
                onClick = {
                    val track = selectedTrack ?: return@ToolActionButton
                    val base = baseName.trim()
                    val deleting = deleteSource
                    if (onMap) {
                        val from = cutStart ?: return@ToolActionButton
                        val to = cutEnd ?: return@ToolActionButton
                        phase = ToolPhase.Busy
                        viewModel.cutPortion(
                            trackId = track.id,
                            from = from,
                            to = to,
                            baseName = base,
                            deleteSource = deleting,
                            onSuccess = { newTrackIds ->
                                phase = ToolPhase.Done(
                                    title = context.getString(R.string.cut_done_title),
                                    body = context.getString(
                                        if (deleting) R.string.cut_done_body_deleted else R.string.cut_done_body_kept,
                                        base
                                    ),
                                    resultTrackId = newTrackIds.singleOrNull()
                                )
                            },
                            onError = { error ->
                                phase = ToolPhase.Form
                                messenger.show(error, Icons.Rounded.Error, isError = true)
                            }
                        )
                        return@ToolActionButton
                    }
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
                                title = context.getString(R.string.split_done_title),
                                body = context.getString(
                                    if (deleting) R.string.split_done_body_deleted else R.string.split_done_body_kept,
                                    base, count
                                ),
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
            subtitle = stringResource(R.string.track_row_subtitle_date, categoryOf(track), FormatUtils.formatDistanceShort(track.totalDistance), FormatUtils.formatDurationShort(track.duration)),
            onClick = { picking = true }
        )

        SectionLabel(stringResource(R.string.split_mode_label))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SegmentButton(
                stringResource(R.string.split_mode_segments), Icons.Rounded.Splitscreen,
                selected = !onMap && mode == TrackRepository.SplitMode.SEGMENT_BREAKS,
                onClick = { if (onMap) deleteSource = false; onMap = false; mode = TrackRepository.SplitMode.SEGMENT_BREAKS },
                testTag = "split_mode_segments",
                modifier = Modifier.weight(1f)
            )
            SegmentButton(
                stringResource(R.string.split_mode_gap), Icons.Rounded.Coffee,
                selected = !onMap && mode == TrackRepository.SplitMode.TIME_GAP,
                onClick = { if (onMap) deleteSource = false; onMap = false; mode = TrackRepository.SplitMode.TIME_GAP },
                testTag = "split_mode_gap",
                modifier = Modifier.weight(1f)
            )
            SegmentButton(
                stringResource(R.string.split_mode_map), Icons.Rounded.Map,
                selected = onMap,
                // Retirer une portion remplace le parcours : l'original est supprimé d'office,
                // la case reste là pour le garder.
                onClick = { if (!onMap) deleteSource = true; onMap = true },
                testTag = "split_mode_map",
                modifier = Modifier.weight(1f)
            )
        }
        if (onMap) {
            CutOnMapSection(
                trackId = track.id,
                fitPoints = preview,
                points = detail,
                start = cutStart,
                end = cutEnd,
                onStartChange = { cutStart = it },
                onEndChange = { cutEnd = it },
                onViewportChange = {
                    viewport = it
                    viewModel.pickerViewports[track.id] = it
                },
                viewModel = viewModel
            )
        } else if (mode == TrackRepository.SplitMode.SEGMENT_BREAKS) {
            Text(
                stringResource(R.string.split_hint_segments),
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
                Text(stringResource(R.string.split_gap_title), style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp))
                Text(formatMinutes(pauseMinutes), style = MaterialTheme.typography.headlineMedium)
                MpSlider(
                    value = pauseIndex.toFloat(),
                    onValueChange = { pauseIndex = it.roundToInt().coerceIn(0, PAUSE_STEPS_MINUTES.lastIndex) },
                    valueRange = 0f..PAUSE_STEPS_MINUTES.lastIndex.toFloat(),
                    steps = PAUSE_STEPS_MINUTES.size - 2,
                    testTag = "split_gap_slider"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(formatMinutes(PAUSE_STEPS_MINUTES.first()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(formatMinutes(PAUSE_STEPS_MINUTES.last()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.split_gap_note),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        FilledField(baseName, { baseName = it }, stringResource(R.string.split_base_name), "split_base_name_field")
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
            title = stringResource(R.string.tool_delete_original),
            subtitle = stringResource(if (onMap) R.string.cut_delete_original_hint else R.string.split_delete_original_hint),
            checked = deleteSource,
            onToggle = { deleteSource = !deleteSource },
            testTag = "split_delete_source_row"
        )
        Spacer(modifier = Modifier.height(14.dp))
        InfoLine(Icons.Rounded.Verified, stringResource(if (onMap) R.string.cut_info else R.string.split_info))
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

/**
 * Le choix de la portion à retirer, sur la carte, qui se zoome à volonté : on touche le
 * début, puis la fin ; la portion entre les deux apparaît en rouge. Deux boutons disent
 * quelle borne le prochain appui posera, et redevient disponible à tout moment pour
 * en reprendre une.
 */
@Composable
private fun CutOnMapSection(
    trackId: Long,
    fitPoints: List<TrackPoint>?,
    points: List<TrackPoint>?,
    start: PortionBound?,
    end: PortionBound?,
    onStartChange: (PortionBound?) -> Unit,
    onEndChange: (PortionBound?) -> Unit,
    onViewportChange: (org.osmdroid.util.BoundingBox) -> Unit,
    viewModel: TrackViewModel
) {
    // Vrai tant que le prochain appui doit poser le début ; il passe tout seul à la fin.
    var placingStart by remember(trackId) { mutableStateOf(true) }
    // Chaque borne posée, avec celle qu'elle remplaçait : « Annuler le point » revient
    // en arrière sans quitter la carte, donc sans perdre le zoom. Demandé par le père
    // de l'auteur, qui devait ressortir de l'outil pour reprendre un point mal placé.
    val placed = remember(trackId) { androidx.compose.runtime.mutableStateListOf<Pair<Boolean, PortionBound?>>() }
    // La zone regardée la dernière fois sur ce parcours : ressortir de l'outil ne fait
    // pas perdre le zoom.
    val resumedBox = remember(trackId) { viewModel.pickerViewports[trackId] }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val none = stringResource(R.string.cut_unset)
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            stringResource(R.string.cut_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            SegmentButton(
                stringResource(R.string.cut_start_label, start?.let { FormatUtils.formatClock(it.point.timestamp) } ?: none),
                Icons.Rounded.Flag,
                selected = placingStart,
                onClick = { placingStart = true },
                testTag = "cut_place_start",
                modifier = Modifier.weight(1f)
            )
            SegmentButton(
                stringResource(R.string.cut_end_label, end?.let { FormatUtils.formatClock(it.point.timestamp) } ?: none),
                Icons.Rounded.Flag,
                selected = !placingStart,
                onClick = { placingStart = false },
                testTag = "cut_place_end",
                modifier = Modifier.weight(1f)
            )
        }
        SegmentButton(
            stringResource(R.string.cut_undo), Icons.AutoMirrored.Rounded.Undo,
            selected = false,
            onClick = {
                val (wasStart, previous) = placed.removeLastOrNull() ?: return@SegmentButton
                if (wasStart) onStartChange(previous) else onEndChange(previous)
                placingStart = wasStart
            },
            testTag = "cut_undo",
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            if (points == null || fitPoints == null) {
                CircularProgressIndicator()
            } else if (fitPoints.size >= 2) {
                PortionPickerMap(
                    points = points,
                    fitPoints = fitPoints,
                    start = start?.point,
                    end = end?.point,
                    initialBox = resumedBox,
                    cut = if (start != null && end != null && start.compareTo(end) != 0) {
                        if (start < end) start to end else end to start
                    } else null,
                    onTapTrack = { lowId, highId, tap ->
                        // La borne exacte se cherche parmi les vrais points : la carte
                        // n'en montre parfois qu'une partie.
                        val placing = placingStart
                        placingStart = !placingStart
                        scope.launch {
                            val bound = viewModel.findPortionBound(trackId, lowId, highId, tap.latitude, tap.longitude)
                                ?: return@launch
                            placed += placing to (if (placing) start else end)
                            if (placing) onStartChange(bound) else onEndChange(bound)
                        }
                    },
                    onViewportChange = onViewportChange,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (start != null && end != null && start.compareTo(end) != 0) {
            val from = if (start < end) start else end
            val to = if (start < end) end else start
            var meters by remember(from, to) { mutableStateOf<Double?>(null) }
            LaunchedEffect(trackId, from, to) {
                meters = viewModel.measurePortion(trackId, from, to)
            }
            Text(
                stringResource(
                    R.string.cut_summary,
                    FormatUtils.formatClock(from.point.timestamp),
                    FormatUtils.formatClock(to.point.timestamp),
                    meters?.let { FormatUtils.formatDistanceShort(it) } ?: "…"
                ),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp)
            )
        }
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

// -------------------------------------------------------------------- Nettoyer

/**
 * Durées minimales d'un arrêt, en secondes. Une minute par défaut : la détection ne
 * retient que du bruit sur place (voir StopCollapser), si bien que réduire même un
 * court arrêt ne change pas la forme du tracé.
 */
private val CLEAN_STOP_SECONDS = listOf(30, 60, 120, 300, 600)
private const val CLEAN_DEFAULT_INDEX = 1 // 1 min

@Composable
private fun CleanTrackTool(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val nameFormat = stringResource(R.string.clean_name_format)
    val tracks by viewModel.allTracks.collectAsState()
    val eligibleTracks = tracks.filter { !it.isRecording }.sortedByDescending { it.startTime }

    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    val selectedTrack = eligibleTracks.find { it.id == selectedTrackId } ?: eligibleTracks.firstOrNull()

    var thresholdIndex by remember { mutableIntStateOf(CLEAN_DEFAULT_INDEX) }
    var newName by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<ToolPhase>(ToolPhase.Form) }
    var picking by remember { mutableStateOf(false) }

    // Le résultat est une copie, et deux lignes de même
    // nom dans l'historique seraient impossibles à distinguer.
    LaunchedEffect(selectedTrack?.id) {
        selectedTrack?.let { newName = nameFormat.format(it.name) }
    }

    val stopSeconds = CLEAN_STOP_SECONDS[thresholdIndex]
    val shownPhase = if (phase == ToolPhase.Form && selectedTrack == null) {
        ToolPhase.Need(stringResource(R.string.clean_need_title), stringResource(R.string.clean_need_body))
    } else phase

    ToolScaffold(
        tool = Tool.CLEAN,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "clean_tool",
        backTag = "clean_tool_back_button",
        modifier = modifier,
        busyTitle = stringResource(R.string.clean_busy_title),
        busySubtitle = stringResource(R.string.clean_busy_subtitle),
        doneActions = {
            val result = (phase as? ToolPhase.Done)?.resultTrackId
            DoneButtons(
                onFinish = onBack,
                primaryLabel = if (result != null) stringResource(R.string.tool_view_track) else null,
                onPrimary = {
                    if (result != null) {
                        viewModel.selectTrack(result)
                        onNavigateToDetails(result)
                    }
                },
                primaryTag = "clean_open_result"
            )
        },
        action = {
            ToolActionButton(
                text = stringResource(R.string.clean_action),
                icon = Icons.Rounded.CleaningServices,
                enabled = selectedTrack != null && newName.isNotBlank(),
                testTag = "confirm_clean_button",
                onClick = {
                    val track = selectedTrack ?: return@ToolActionButton
                    val name = newName.trim()
                    val deleting = deleteSource
                    phase = ToolPhase.Busy
                    viewModel.removeStationaryPoints(
                        trackId = track.id,
                        minStopSeconds = stopSeconds,
                        newName = name,
                        deleteSource = deleting,
                        onSuccess = { result ->
                            phase = ToolPhase.Done(
                                title = context.getString(R.string.clean_done_title),
                                body = context.getString(
                                    if (deleting) R.string.clean_done_body_deleted else R.string.clean_done_body_kept,
                                    result.stops,
                                    java.text.NumberFormat.getIntegerInstance(Locale.FRANCE).format(result.removedPoints),
                                    name
                                ),
                                resultTrackId = result.newTrackId
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
        val end = if (track.endTime > track.startTime) track.endTime else track.startTime + track.duration * 1000
        SelectedTrackCard(
            track = track,
            subtitle = "${FormatUtils.formatClock(track.startTime)} → ${FormatUtils.formatClock(end)} · ${FormatUtils.formatDurationShort(track.duration)}",
            onClick = { picking = true }
        )

        Spacer(modifier = Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.clean_threshold_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (stopSeconds < 60) stringResource(R.string.clean_threshold_seconds, stopSeconds)
                    else stringResource(R.string.clean_threshold_value, stopSeconds / 60),
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp)
                )
            }
            MpSlider(
                value = thresholdIndex.toFloat(),
                onValueChange = { thresholdIndex = it.roundToInt().coerceIn(0, CLEAN_STOP_SECONDS.lastIndex) },
                valueRange = 0f..CLEAN_STOP_SECONDS.lastIndex.toFloat(),
                steps = CLEAN_STOP_SECONDS.size - 2,
                testTag = "clean_threshold_slider"
            )
            Text(
                stringResource(R.string.clean_threshold_note),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        FilledField(newName, { newName = it }, stringResource(R.string.tool_result_name_label), "clean_name_field")
        Spacer(modifier = Modifier.height(10.dp))
        SwitchCard(
            title = stringResource(R.string.tool_delete_original),
            subtitle = stringResource(R.string.clean_delete_original_hint),
            checked = deleteSource,
            onToggle = { deleteSource = !deleteSource },
            testTag = "clean_delete_source_row"
        )
        Spacer(modifier = Modifier.height(14.dp))
        InfoLine(Icons.Rounded.Verified, stringResource(R.string.clean_info))
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

// ------------------------------------------------------------------- Prolonger

/**
 * Prolonger un parcours d'un tracé dessiné à la main, demandé par le père de l'auteur :
 * « si j'ai arrêté ma trace trop tôt, créer des points manuellement sur la carte pour
 * compléter la partie qui manque, comme dans Earth ». Chaque appui sur la carte ajoute
 * un point, relié au précédent ; le premier part du bout du parcours.
 */
@Composable
private fun ExtendTrackTool(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    val tracks by viewModel.allTracks.collectAsState()
    val eligibleTracks = tracks.filter { !it.isRecording }.sortedByDescending { it.startTime }

    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    val selectedTrack = eligibleTracks.find { it.id == selectedTrackId } ?: eligibleTracks.firstOrNull()

    var atEnd by remember(selectedTrack?.id) { mutableStateOf(true) }
    var newName by remember { mutableStateOf("") }
    // Comme le retrait d'une portion : on veut une seule trace à la fin.
    var deleteSource by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf<ToolPhase>(ToolPhase.Form) }
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTrack?.id) {
        selectedTrack?.let { newName = it.name }
    }

    var preview by remember(selectedTrack?.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var detail by remember(selectedTrack?.id) { mutableStateOf<List<TrackPoint>?>(null) }
    var viewport by remember(selectedTrack?.id) { mutableStateOf<org.osmdroid.util.BoundingBox?>(null) }
    var anchor by remember(selectedTrack?.id, atEnd) { mutableStateOf<TrackPoint?>(null) }
    // Les appuis, du plus proche du bout au plus éloigné ; changer de bout les efface.
    val taps = remember(selectedTrack?.id, atEnd) { mutableStateListOf<org.osmdroid.util.GeoPoint>() }
    LaunchedEffect(selectedTrack?.id) {
        val id = selectedTrack?.id ?: return@LaunchedEffect
        val loaded = viewModel.loadPreviewPoints(id)
        preview = loaded
        if (detail == null) detail = loaded
    }
    LaunchedEffect(selectedTrack?.id, atEnd) {
        val id = selectedTrack?.id ?: return@LaunchedEffect
        anchor = viewModel.loadEndPoint(id, atEnd)
    }
    LaunchedEffect(selectedTrack?.id, viewport) {
        val id = selectedTrack?.id ?: return@LaunchedEffect
        val box = viewport ?: return@LaunchedEffect
        detail = viewModel.loadPickerPoints(id, box.latSouth, box.latNorth, box.lonWest, box.lonEast)
    }

    val shownPhase = if (phase == ToolPhase.Form && selectedTrack == null) {
        ToolPhase.Need(stringResource(R.string.extend_need_title), stringResource(R.string.extend_need_body))
    } else phase

    ToolScaffold(
        tool = Tool.EXTEND,
        phase = shownPhase,
        onBack = onBack,
        screenTag = "extend_tool",
        backTag = "extend_tool_back_button",
        modifier = modifier,
        busyTitle = stringResource(R.string.extend_busy_title),
        busySubtitle = stringResource(R.string.extend_busy_subtitle),
        doneActions = {
            val result = (phase as? ToolPhase.Done)?.resultTrackId
            DoneButtons(
                onFinish = onBack,
                primaryLabel = if (result != null) stringResource(R.string.tool_view_track) else null,
                onPrimary = {
                    if (result != null) {
                        viewModel.selectTrack(result)
                        onNavigateToDetails(result)
                    }
                },
                primaryTag = "extend_open_result"
            )
        },
        action = {
            ToolActionButton(
                text = stringResource(R.string.extend_action),
                icon = Icons.Rounded.Route,
                enabled = selectedTrack != null && newName.isNotBlank() && taps.isNotEmpty() && anchor != null,
                testTag = "confirm_extend_button",
                onClick = {
                    val track = selectedTrack ?: return@ToolActionButton
                    val name = newName.trim()
                    val deleting = deleteSource
                    phase = ToolPhase.Busy
                    viewModel.extendTrack(
                        trackId = track.id,
                        taps = taps.map { it.latitude to it.longitude },
                        atEnd = atEnd,
                        newName = name,
                        deleteSource = deleting,
                        onSuccess = { id ->
                            phase = ToolPhase.Done(
                                title = context.getString(R.string.extend_done_title),
                                body = context.getString(
                                    if (deleting) R.string.extend_done_body_deleted else R.string.extend_done_body_kept,
                                    name
                                ),
                                resultTrackId = id
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
            subtitle = stringResource(R.string.track_row_subtitle_date, categoryOf(track), FormatUtils.formatDistanceShort(track.totalDistance), FormatUtils.formatDurationShort(track.duration)),
            onClick = { picking = true }
        )

        SectionLabel(stringResource(R.string.extend_side_label))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SegmentButton(
                stringResource(R.string.extend_side_end), Icons.Rounded.Flag,
                selected = atEnd,
                onClick = { atEnd = true },
                testTag = "extend_side_end",
                modifier = Modifier.weight(1f)
            )
            SegmentButton(
                stringResource(R.string.extend_side_start), Icons.Rounded.Flag,
                selected = !atEnd,
                onClick = { atEnd = false },
                testTag = "extend_side_start",
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            stringResource(R.string.extend_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            val shown = detail
            val fit = preview
            if (shown == null || fit == null) {
                CircularProgressIndicator()
            } else if (fit.isNotEmpty()) {
                PortionPickerMap(
                    points = shown,
                    fitPoints = fit,
                    start = anchor,
                    end = null,
                    onTapTrack = { _, _, _ -> },
                    onViewportChange = { viewport = it },
                    sketch = taps.toList(),
                    onTapGeo = { taps.add(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            SegmentButton(
                stringResource(R.string.extend_undo), Icons.AutoMirrored.Rounded.Undo,
                selected = false,
                onClick = { if (taps.isNotEmpty()) taps.removeAt(taps.lastIndex) },
                testTag = "extend_undo",
                modifier = Modifier.weight(1f)
            )
            SegmentButton(
                stringResource(R.string.extend_clear), Icons.Rounded.Delete,
                selected = false,
                onClick = { taps.clear() },
                testTag = "extend_clear",
                modifier = Modifier.weight(1f)
            )
        }
        val origin = anchor
        if (origin != null && taps.isNotEmpty()) {
            val meters = remember(origin, taps.toList()) {
                val result = FloatArray(1)
                var total = 0.0
                var lat = origin.latitude
                var lon = origin.longitude
                for (p in taps) {
                    android.location.Location.distanceBetween(lat, lon, p.latitude, p.longitude, result)
                    total += result[0]
                    lat = p.latitude
                    lon = p.longitude
                }
                total
            }
            Text(
                pluralStringResource(R.plurals.extend_summary, taps.size, taps.size, FormatUtils.formatDistanceShort(meters)),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        FilledField(newName, { newName = it }, stringResource(R.string.tool_result_name_label), "extend_name_field")
        Spacer(modifier = Modifier.height(10.dp))
        SwitchCard(
            title = stringResource(R.string.tool_delete_original),
            subtitle = stringResource(R.string.extend_delete_original_hint),
            checked = deleteSource,
            onToggle = { deleteSource = !deleteSource },
            testTag = "extend_delete_source_row"
        )
        Spacer(modifier = Modifier.height(14.dp))
        InfoLine(Icons.Rounded.Verified, stringResource(R.string.extend_info))
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
