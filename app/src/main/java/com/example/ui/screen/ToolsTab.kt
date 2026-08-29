package com.example.ui.screen

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.Track
import com.example.data.repository.TrackRepository
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences
import kotlin.math.roundToInt

/** Outils disponibles. Le menu s'étoffera au fil des versions. */
private enum class Tool {
    MERGE,
    SPLIT
}

@Composable
fun ToolsTab(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var openTool by remember { mutableStateOf<Tool?>(null) }

    when (openTool) {
        null -> ToolsMenu(
            onOpenTool = { openTool = it },
            modifier = modifier
        )
        Tool.MERGE -> MergeTracksTool(
            viewModel = viewModel,
            onNavigateToDetails = onNavigateToDetails,
            onBack = { openTool = null },
            modifier = modifier
        )
        Tool.SPLIT -> SplitTrackTool(
            viewModel = viewModel,
            onBack = { openTool = null },
            modifier = modifier
        )
    }
}

@Composable
private fun ToolsMenu(
    onOpenTool: (Tool) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("tools_tab"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(
                    text = "Outils",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Opérations sur vos parcours",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ToolMenuEntry(
                icon = Icons.AutoMirrored.Filled.MergeType,
                title = "Fusionner des traces",
                subtitle = "Réunir plusieurs parcours en un seul, dans l'ordre chronologique",
                onClick = { onOpenTool(Tool.MERGE) },
                testTag = "open_merge_tool_button"
            )

            ToolMenuEntry(
                icon = Icons.Filled.ContentCut,
                title = "Découper un parcours",
                subtitle = "Séparer un parcours en plusieurs, par tronçon ou par longue pause",
                onClick = { onOpenTool(Tool.SPLIT) },
                testTag = "open_split_tool_button"
            )
        }
    }
}

@Composable
private fun ToolMenuEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MergeTracksTool(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tracks by viewModel.allTracks.collectAsState()

    var selectedSourceIds by remember { mutableStateOf(emptySet<Long>()) }
    var mergedName by remember { mutableStateOf("") }
    var isMerging by remember { mutableStateOf(false) }

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
        val destination = destinationTrack
        if (destination != null) {
            mergedName = destination.name
        }
    }

    if (tracks.size < 2) {
        ToolsEmptyState(onBack = onBack, modifier = modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("merge_tool"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("merge_tool_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Retour aux outils",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Fusionner des traces",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Réunir plusieurs parcours en un seul",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Étape 1 : quels parcours fusionner
            ToolStepCard(
                stepNumber = 1,
                title = "Parcours à fusionner",
                subtitle = "${selectedSourceIds.size} sélectionné(s) — il en faut au moins 2"
            ) {
                tracks.sortedByDescending { it.startTime }.forEach { track ->
                    val isSelected = track.id in selectedSourceIds
                    MergeTrackRow(
                        track = track,
                        selected = isSelected,
                        onToggle = {
                            selectedSourceIds = if (isSelected) {
                                selectedSourceIds - track.id
                            } else {
                                selectedSourceIds + track.id
                            }
                        }
                    )
                }
            }

            // Étape 2 : nom final
            ToolStepCard(
                stepNumber = 2,
                title = "Nom du parcours fusionné",
                subtitle = if (destinationTrack != null) {
                    "Le résultat rejoint la catégorie de « ${destinationTrack.name} », le plus ancien"
                } else {
                    "Sélectionnez d'abord au moins deux parcours"
                }
            ) {
                OutlinedTextField(
                    value = mergedName,
                    onValueChange = { mergedName = it },
                    label = { Text("Nom final") },
                    singleLine = true,
                    enabled = selectedTracks.size >= 2,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("merged_name_field")
                )
            }

            val canMerge = selectedTracks.size >= 2 &&
                    destinationTrack != null &&
                    mergedName.isNotBlank() &&
                    !isMerging

            Button(
                onClick = {
                    val finalDestinationId = destinationTrack?.id ?: return@Button
                    isMerging = true
                    viewModel.mergeTracks(
                        context = context,
                        trackIds = selectedTracks.map { it.id },
                        destinationTrackId = finalDestinationId,
                        mergedName = mergedName,
                        onSuccess = { mergedId ->
                            isMerging = false
                            Toast.makeText(context, "Parcours fusionnés avec succès !", Toast.LENGTH_LONG).show()
                            selectedSourceIds = emptySet()
                            viewModel.selectTrack(mergedId)
                            onNavigateToDetails(mergedId)
                        },
                        onError = { error ->
                            isMerging = false
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                enabled = canMerge,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_merge_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MergeType,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isMerging) "Fusion en cours…" else "Fusionner",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun trackSubtitle(track: Track): String {
    val date = FormatUtils.formatDate(track.startTime)
    val distance = FormatUtils.formatDistance(track.totalDistance)
    val category = if (track.isImported) "Importé" else "Enregistré"
    return "$category • $date • $distance"
}

@Composable
private fun ToolStepCard(
    stepNumber: Int,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MergeTrackRow(
    track: Track,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val categoryColor = Color(
        if (track.isImported) TrackStylePreferences.DEFAULT_COLOR_IMPORTED
        else TrackStylePreferences.DEFAULT_COLOR_RECORDED
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag("merge_source_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("merge_checkbox_${track.id}")
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(categoryColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = trackSubtitle(track),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Ligne à sélection unique, pour les outils qui n'opèrent que sur un seul parcours. */
@Composable
private fun SingleTrackRow(
    track: Track,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val categoryColor = Color(
        if (track.isImported) TrackStylePreferences.DEFAULT_COLOR_IMPORTED
        else TrackStylePreferences.DEFAULT_COLOR_RECORDED
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag("single_track_row_${track.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.testTag("single_track_radio_${track.id}")
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(categoryColor)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = trackSubtitle(track),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Découper un parcours
// ---------------------------------------------------------------------------

/** Bornes du seuil de pause, en minutes, et pas du curseur. */
private const val GAP_MIN_MINUTES = 5f
private const val GAP_MAX_MINUTES = 180f
private const val GAP_SLIDER_STEPS = 34 // un cran tous les 5 minutes

private fun formatGap(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

/** Une des deux façons de couper, présentée comme un choix exclusif. */
@Composable
private fun SplitModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp, horizontal = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SplitTrackTool(
    viewModel: TrackViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tracks by viewModel.allTracks.collectAsState()
    val eligibleTracks = tracks.filter { !it.isRecording }

    var selectedTrackId by remember { mutableStateOf<Long?>(null) }
    val selectedTrack = eligibleTracks.find { it.id == selectedTrackId }

    var mode by remember { mutableStateOf(TrackRepository.SplitMode.SEGMENT_BREAKS) }
    var gapMinutes by remember { mutableStateOf(30f) }
    var baseName by remember { mutableStateOf("") }
    var deleteSource by remember { mutableStateOf(false) }
    var isSplitting by remember { mutableStateOf(false) }

    // Le nom proposé est celui du parcours choisi : les morceaux s'appelleront
    // « … (1) », « … (2) », etc.
    LaunchedEffect(selectedTrack?.id) {
        val track = selectedTrack
        if (track != null) baseName = track.name
    }

    if (eligibleTracks.isEmpty()) {
        ToolsEmptyState(
            onBack = onBack,
            modifier = modifier,
            title = "Découpage indisponible",
            message = "Il faut au moins un parcours enregistré ou importé pour pouvoir le découper.",
            screenTestTag = "split_tool",
            backButtonTestTag = "split_tool_back_button"
        )
        return
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("split_tool"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("split_tool_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Retour aux outils",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Découper un parcours",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Séparer un parcours en plusieurs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Étape 1 : quel parcours découper
            ToolStepCard(
                stepNumber = 1,
                title = "Parcours à découper",
                subtitle = selectedTrack?.name ?: "Aucun parcours sélectionné"
            ) {
                eligibleTracks.sortedByDescending { it.startTime }.forEach { track ->
                    SingleTrackRow(
                        track = track,
                        selected = track.id == selectedTrackId,
                        onSelect = { selectedTrackId = track.id }
                    )
                }
            }

            // Étape 2 : où couper
            ToolStepCard(
                stepNumber = 2,
                title = "Où couper",
                subtitle = "Le parcours d'origine n'est pas modifié"
            ) {
                SplitModeRow(
                    selected = mode == TrackRepository.SplitMode.SEGMENT_BREAKS,
                    title = "À chaque tronçon",
                    subtitle = "Pour un fichier importé qui réunit plusieurs trajets",
                    onSelect = { mode = TrackRepository.SplitMode.SEGMENT_BREAKS },
                    testTag = "split_mode_segments"
                )
                SplitModeRow(
                    selected = mode == TrackRepository.SplitMode.TIME_GAP,
                    title = "À chaque longue pause",
                    subtitle = "Pour un enregistrement qui couvre plusieurs sorties",
                    onSelect = { mode = TrackRepository.SplitMode.TIME_GAP },
                    testTag = "split_mode_gap"
                )

                if (mode == TrackRepository.SplitMode.TIME_GAP) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Couper dès ${formatGap(gapMinutes.roundToInt())} sans le moindre point",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = gapMinutes,
                        onValueChange = { gapMinutes = it },
                        valueRange = GAP_MIN_MINUTES..GAP_MAX_MINUTES,
                        steps = GAP_SLIDER_STEPS,
                        modifier = Modifier.fillMaxWidth().testTag("split_gap_slider")
                    )
                }
            }

            // Étape 3 : nom des morceaux
            ToolStepCard(
                stepNumber = 3,
                title = "Nom des morceaux",
                subtitle = if (baseName.isBlank()) {
                    "Sélectionnez d'abord un parcours"
                } else {
                    "Ils seront numérotés : « $baseName (1) », « $baseName (2) »…"
                }
            ) {
                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = { Text("Nom de base") },
                    singleLine = true,
                    enabled = selectedTrack != null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("split_base_name_field")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = selectedTrack != null) { deleteSource = !deleteSource }
                        .padding(vertical = 4.dp)
                        .testTag("split_delete_source_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = deleteSource,
                        onCheckedChange = { deleteSource = it },
                        enabled = selectedTrack != null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Supprimer le parcours d'origine",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Les morceaux en contiennent tous les points : rien n'est perdu",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            val canSplit = selectedTrack != null && baseName.isNotBlank() && !isSplitting

            Button(
                onClick = {
                    val trackId = selectedTrack?.id ?: return@Button
                    isSplitting = true
                    viewModel.splitTrack(
                        trackId = trackId,
                        mode = mode,
                        gapMillis = gapMinutes.roundToInt() * 60_000L,
                        baseName = baseName.trim(),
                        deleteSource = deleteSource,
                        onSuccess = { newTrackIds ->
                            isSplitting = false
                            Toast.makeText(
                                context,
                                "Découpé en ${newTrackIds.size} parcours",
                                Toast.LENGTH_LONG
                            ).show()
                            selectedTrackId = null
                            onBack()
                        },
                        onError = { error ->
                            isSplitting = false
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                enabled = canSplit,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("confirm_split_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCut,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSplitting) "Découpage en cours…" else "Découper le parcours",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ToolsEmptyState(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Fusion indisponible",
    message: String = "Il faut au moins deux parcours enregistrés ou importés pour pouvoir en fusionner.",
    screenTestTag: String = "merge_tool",
    backButtonTestTag: String = "merge_tool_back_button"
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp)
            .testTag(screenTestTag),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                modifier = Modifier
                    .size(110.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Construction,
                        contentDescription = "Outils",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onBack,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag(backButtonTestTag)
            ) {
                Text("Retour aux outils", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
