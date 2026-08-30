package com.example.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timeline
import android.widget.Toast
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences

@Composable
fun HistoryTab(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onResumeTrack: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tracks by viewModel.allTracks.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    var trackToDelete by remember { mutableStateOf<Track?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTab by remember { mutableStateOf(0) } // 0 = Enregistrés, 1 = Importés, 2 = Fusionnés

    // Parcours dont la palette est ouverte ; null = fermée. La couleur appartient au
    // parcours, plus à la catégorie : c'est donc une trace précise que l'on colore.
    var colorPickerTrack by remember { mutableStateOf<Track?>(null) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importTrack(
                context = context,
                uri = it,
                onSuccess = { trackId ->
                    android.widget.Toast.makeText(context, "Parcours importé avec succès !", android.widget.Toast.LENGTH_LONG).show()
                    viewModel.selectTrack(trackId)
                    onNavigateToDetails(trackId)
                },
                onError = { error ->
                    android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // Trois catégories qui ne se recouvrent pas : un parcours fusionné quitte celle
    // dont il venait, sinon il apparaîtrait deux fois dans l'historique.
    //
    // Les fusions faites entre le retrait de l'onglet et son retour portent isMerged
    // à false en base : elles restent donc dans leur catégorie d'origine. Rien ne
    // permet de les reconnaître après coup, et les déplacer d'office serait pire que
    // de les laisser où l'utilisateur les a vues jusqu'ici.
    val mergedTracks = tracks.filter { it.isMerged }
    val recordedTracks = tracks.filter { !it.isImported && !it.isMerged }
    val importedTracks = tracks.filter { it.isImported && !it.isMerged }

    if (tracks.isEmpty()) {
        EmptyHistoryState(onImportClick = { filePickerLauncher.launch("*/*") })
        return
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("history_list")
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Vos parcours",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${tracks.size} tracé(s) disponible(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectedTab == 1 || importedTracks.isEmpty()) {
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                modifier = Modifier.height(40.dp).testTag("import_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timeline,
                                    contentDescription = "Importer",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Importer", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }

            item {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {},
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    // Les onglets ne portent plus l'appui long qui ouvrait la couleur de
                    // la catégorie : la couleur appartient désormais au parcours, et se
                    // choisit sur sa carte. Ce geste caché était de toute façon
                    // indevinable, et sa zone sensible se limitait aux lettres du
                    // libellé — l'élargir avait fait disparaître l'onglet « Importés »
                    // (voir l'historique de ce fichier).
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "Enregistrés (${recordedTracks.size})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = "Importés (${importedTracks.size})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                text = "Fusionnés (${mergedTracks.size})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                    )
                }
            }

            // La pastille de couleur d'une carte est cliquable, mais rien ne le dit.
            item {
                Text(
                    text = "Appuyez sur la pastille colorée d'un parcours pour changer sa couleur",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }

            if (selectedTab == 0) {
                if (recordedTracks.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Aucun parcours enregistré",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Lancez un enregistrement dans l'onglet \"Enregistrement\" pour créer votre premier parcours !",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(recordedTracks, key = { _, it -> it.id }) { index, track ->
                        StaggeredHistoryCard(index) {
                            TrackHistoryCard(
                                track = track,
                                trackColor = Color(trackDisplayColor(track)),
                                onColorClick = { colorPickerTrack = track },
                                onClick = { onNavigateToDetails(track.id) },
                                onDeleteClick = { trackToDelete = track },
                                onResumeClick = {
                                    if (isTracking) {
                                        Toast.makeText(context, "Un enregistrement est déjà en cours. Veuillez l'arrêter avant de reprendre un autre parcours.", Toast.LENGTH_LONG).show()
                                    } else {
                                        viewModel.resumeTrack(context, track.id) {
                                            Toast.makeText(context, "Reprise de la trace \"${track.name}\"", Toast.LENGTH_SHORT).show()
                                            onResumeTrack(track.id)
                                        }
                                    }
                                },
                                showMapSelection = true,
                                isSelectedForMap = track.isSelectedForMap,
                                onMapSelectionToggle = { isChecked ->
                                    viewModel.toggleTrackSelectionForMap(track)
                                }
                            )
                        }
                    }
                }
            }

            if (selectedTab == 1) {
                if (importedTracks.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timeline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Aucun parcours importé",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Importez des fichiers GPX ou KML pour les afficher en superposition sur votre carte !",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    shape = CircleShape
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timeline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Importer GPX / KML", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                } else {
                    itemsIndexed(importedTracks, key = { _, it -> it.id }) { index, track ->
                        StaggeredHistoryCard(index) {
                            TrackHistoryCard(
                                track = track,
                                trackColor = Color(trackDisplayColor(track)),
                                onColorClick = { colorPickerTrack = track },
                                onClick = { onNavigateToDetails(track.id) },
                                onDeleteClick = { trackToDelete = track },
                                onResumeClick = {
                                    if (isTracking) {
                                        Toast.makeText(context, "Un enregistrement est déjà en cours. Veuillez l'arrêter avant de reprendre un autre parcours.", Toast.LENGTH_LONG).show()
                                    } else {
                                        viewModel.resumeTrack(context, track.id) {
                                            Toast.makeText(context, "Reprise de la trace \"${track.name}\"", Toast.LENGTH_SHORT).show()
                                            onResumeTrack(track.id)
                                        }
                                    }
                                },
                                showMapSelection = true,
                                isSelectedForMap = track.isSelectedForMap,
                                onMapSelectionToggle = { isChecked ->
                                    viewModel.toggleTrackSelectionForMap(track)
                                }
                            )
                        }
                    }
                }
            }

            if (selectedTab == 2) {
                if (mergedTracks.isEmpty()) {
                    item {
                        MergedEmptyState()
                    }
                } else {
                    itemsIndexed(mergedTracks, key = { _, it -> it.id }) { index, track ->
                        StaggeredHistoryCard(index) {
                            TrackHistoryCard(
                                track = track,
                                trackColor = Color(trackDisplayColor(track)),
                                onColorClick = { colorPickerTrack = track },
                                onClick = { onNavigateToDetails(track.id) },
                                onDeleteClick = { trackToDelete = track },
                                onResumeClick = {
                                    if (isTracking) {
                                        Toast.makeText(context, "Un enregistrement est déjà en cours. Veuillez l'arrêter avant de reprendre un autre parcours.", Toast.LENGTH_LONG).show()
                                    } else {
                                        viewModel.resumeTrack(context, track.id) {
                                            Toast.makeText(context, "Reprise de la trace \"${track.name}\"", Toast.LENGTH_SHORT).show()
                                            onResumeTrack(track.id)
                                        }
                                    }
                                },
                                showMapSelection = true,
                                isSelectedForMap = track.isSelectedForMap,
                                onMapSelectionToggle = { isChecked ->
                                    viewModel.toggleTrackSelectionForMap(track)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Palette de couleurs d'un parcours précis, ouverte depuis sa pastille colorée.
    colorPickerTrack?.let { picked ->
        // La trace vient de la liste, qui se recompose à chaque écriture : on relit
        // donc la version à jour, sinon la sélection ne bougerait pas sous le doigt.
        val track = tracks.firstOrNull { it.id == picked.id } ?: picked

        // Seul un parcours dont le fichier portait une couleur peut la retrouver.
        // La proposer sur un GPX, ou sur un enregistrement, serait une option qui ne
        // fait rien.
        val hasFileColor = track.sourceColor != null

        AlertDialog(
            onDismissRequest = { colorPickerTrack = null },
            title = {
                Text(
                    text = "Couleur de « ${track.name} »",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    ColorPaletteRow(
                        selectedColor = track.displayColor
                            ?: TrackStylePreferences.resolveTrackColor(
                                displayColor = null,
                                sourceColor = track.sourceColor,
                                isImported = track.isImported,
                                isMerged = track.isMerged
                            ),
                        onColorSelected = { color ->
                            viewModel.setTrackColor(track, color)
                        },
                        showFileColorOption = hasFileColor,
                        // « Couleur d'origine » n'est retenue que tant que l'utilisateur
                        // n'a rien choisi lui-même : displayColor à null, c'est
                        // exactement cela.
                        isFileColorSelected = track.displayColor == null,
                        onFileColorSelected = {
                            viewModel.setTrackColor(track, null)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (hasFileColor) {
                            "Le globe garde la couleur que ce fichier portait dans " +
                                "Google Earth. Chaque parcours se colore séparément."
                        } else {
                            "Ce parcours ne vient d'aucun fichier coloré — les GPX n'en " +
                                "portent jamais. Choisissez-lui une couleur."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { colorPickerTrack = null },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Fermer")
                }
            }
        )
    }

    // Deletion Confirmation Dialog
    trackToDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = {
                Text(
                    text = "Supprimer le parcours ?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Êtes-vous sûr de vouloir supprimer définitivement le tracé \"${track.name}\" ainsi que toutes ses coordonnées d'enregistrement ?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTrack(track.id)
                        trackToDelete = null
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("dialog_delete_confirm_button")
                ) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                Button(
                    onClick = { trackToDelete = null },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Text("Annuler")
                }
            }
        )
    }

}

/**
 * Fait apparaître une carte de l'historique en glissant, avec un léger décalage selon
 * [index], et anime aussi son emplacement dans la liste ([LazyItemScope.animateItem]) —
 * utile lors d'une suppression ou d'un changement d'onglet, où les cartes restantes
 * glissent à leur nouvelle place plutôt que de sauter.
 *
 * Le décalage entre cartes est plafonné : au-delà d'une douzaine, attendre son tour
 * prendrait plus de temps que l'utilisateur n'en met à faire défiler jusque-là. C'est
 * une garniture, pas un ralentissement — une appli consultée parfois d'une main en
 * plein trajet ne doit pas faire patienter pour lire ses statistiques.
 *
 * **Pas de fondu, et c'est la correction d'un défaut visible.** L'entrée combinait un
 * fondu et un glissement ; or ces cartes portent une ombre d'élévation, qui déborde
 * sous leur cadre. Dès qu'une opacité inférieure à 1 est appliquée, Android compose
 * l'élément hors écran dans un tampon **aux dimensions exactes de la carte** : tout ce
 * qui débordait — donc l'ombre — se retrouvait tranché net. D'où la bande sombre à
 * bord franc sous chaque carte pendant l'animation, là où l'on attend un dégradé
 * doux. Le glissement seul n'ouvre aucun tampon : l'ombre se dessine normalement,
 * d'un bout à l'autre du mouvement.
 *
 * Ne pas réintroduire de fondu ici sans retirer l'élévation de la carte.
 */
@Composable
private fun LazyItemScope.StaggeredHistoryCard(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 12) * 18L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(200)) { it / 6 },
        modifier = Modifier.animateItem()
    ) {
        content()
    }
}

/**
 * Couleur d'affichage d'un parcours : son choix explicite, à défaut celle de son
 * fichier, à défaut celle par défaut de sa catégorie.
 *
 * Simple raccourci pour ne pas réécrire les quatre arguments sur chaque carte.
 */
private fun trackDisplayColor(track: Track): Int =
    TrackStylePreferences.resolveTrackColor(
        displayColor = track.displayColor,
        sourceColor = track.sourceColor,
        isImported = track.isImported,
        isMerged = track.isMerged
    )

@Composable
fun TrackHistoryCard(
    track: Track,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onResumeClick: (() -> Unit)? = null,
    showMapSelection: Boolean = false,
    isSelectedForMap: Boolean = false,
    onMapSelectionToggle: (Boolean) -> Unit = {},
    /** Ouvre la palette de ce parcours ; null rend la pastille inerte. */
    onColorClick: (() -> Unit)? = null,
    /** Couleur d'affichage de ce parcours précis. */
    trackColor: Color = Color(
        TrackStylePreferences.defaultColorFor(track.isImported, track.isMerged)
    )
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        trackColor.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(onClick = onClick)
            .testTag("track_card_${track.id}")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header Row (Activity Type Icon + Name)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // La pastille colorée ouvre la palette de ce parcours : c'est
                // l'élément qui porte déjà sa couleur, donc celui sur lequel on pense
                // à appuyer pour la changer. Le cadre s'affiche pour signaler qu'elle
                // se touche, et le libellé d'accessibilité le dit aussi.
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = trackColor.copy(alpha = 0.12f),
                    border = onColorClick?.let {
                        androidx.compose.foundation.BorderStroke(1.dp, trackColor.copy(alpha = 0.5f))
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .then(
                            if (onColorClick != null) {
                                Modifier
                                    .clickable(onClick = onColorClick)
                                    .testTag("track_color_button_${track.id}")
                            } else Modifier
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = if (onColorClick != null) {
                                "Changer la couleur de ${track.name}"
                            } else track.activityType,
                            tint = trackColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = track.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = FormatUtils.formatDate(track.startTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            run {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onResumeClick != null) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable(onClick = onResumeClick)
                                .padding(horizontal = 8.dp)
                                .testTag("resume_track_card_button_${track.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Reprendre la trace",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Reprendre",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    if (showMapSelection) {
                        val eyeBgColor = if (isSelectedForMap) trackColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        val eyeContentColor = if (isSelectedForMap) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        val eyeText = if (isSelectedForMap) "Cacher" else "Montrer"
                        val eyeIcon = if (isSelectedForMap) Icons.Default.Visibility else Icons.Default.VisibilityOff
                        
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(CircleShape)
                                .background(eyeBgColor)
                                .clickable { onMapSelectionToggle(!isSelectedForMap) }
                                .padding(horizontal = 8.dp)
                                .testTag("map_toggle_${track.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = eyeIcon,
                                contentDescription = eyeText,
                                tint = eyeContentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = eyeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = eyeContentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                            .clickable(onClick = onDeleteClick)
                            .padding(horizontal = 8.dp)
                            .testTag("delete_track_button_${track.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Supprimer cette trace",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Supprimer",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Stats Quick Overview Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatsBadge(
                    icon = Icons.Default.Timeline,
                    label = "Distance",
                    value = FormatUtils.formatDistance(track.totalDistance),
                    tint = trackColor
                )
                StatsBadge(
                    icon = Icons.Default.History,
                    label = "Temps",
                    value = FormatUtils.formatDuration(track.duration),
                    tint = trackColor
                )
                StatsBadge(
                    icon = Icons.Default.TrendingUp,
                    label = "Dénivelé",
                    value = "+${FormatUtils.formatElevation(track.elevationGain)}",
                    tint = trackColor
                )
            }
        }
    }
}

@Composable
fun StatsBadge(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp
        )
    }
}

@Composable
fun EmptyHistoryState(onImportClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
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
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "Historique vide",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Enregistrez vos parcours d'exploration GPS depuis l'onglet Enregistrer pour dresser votre premier compte rendu de voyage.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onImportClick,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .height(52.dp)
                    .width(240.dp)
                    .testTag("empty_state_import_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = "Importer un fichier GPX/KML",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Importer GPX / KML", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Écran vide de l'onglet « Fusionnés ».
 *
 * Il dit surtout ce qu'il faut faire pour le remplir : la fusion ne se trouve pas
 * dans l'historique mais dans les outils, et rien ne le laisserait deviner.
 */
@Composable
private fun MergedEmptyState() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Timeline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Aucun parcours fusionné",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Réunissez plusieurs parcours en un seul depuis l'onglet " +
                        "« Outils », et le résultat apparaîtra ici.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Pastille « garder la couleur d'origine du fichier », dessinée en globe terrestre.
 *
 * Un dégradé arc-en-ciel occupait cette place : il disait « plusieurs couleurs » mais
 * pas d'où elles venaient. Le globe renvoie à Google Earth, seule origine possible
 * d'une couleur de fichier ici.
 *
 * Dessiné, et non repris du logo de Google : c'est une marque déposée, et ce dépôt est
 * public sous licence GPL-3.0 — y verser l'image de quelqu'un d'autre reviendrait à la
 * redistribuer sous une licence qui ne lui appartient pas.
 */
@Composable
private fun SourceColorSwatch(selected: Boolean, onClick: () -> Unit) {
    val size = if (selected) 38.dp else 32.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .clickable { onClick() }
            .testTag("color_swatch_from_file")
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            val d = this.size.minDimension
            val r = d / 2f
            val c = androidx.compose.ui.geometry.Offset(this.size.width / 2f, this.size.height / 2f)

            // L'océan, plus clair en haut à gauche : le relief tient à ce seul dégradé.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF4FC3F7), Color(0xFF1565C0)),
                    center = androidx.compose.ui.geometry.Offset(c.x - r * 0.3f, c.y - r * 0.35f),
                    radius = d
                ),
                radius = r,
                center = c
            )

            // Trois masses de terre. Des ovales plutôt que des contours réels : à cette
            // taille le détail se perdrait, seule la silhouette continent/océan se lit.
            fun land(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
                drawOval(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(c.x + r * cx, c.y + r * cy),
                    size = androidx.compose.ui.geometry.Size(r * w, r * h)
                )
            }
            // Ce qui déborde du disque est rogné par le `clip(CircleShape)` posé sur la
            // Box parente : les ovales peuvent donc mordre le bord sans le déformer.
            land(-0.75f, -0.60f, 0.80f, 0.55f, Color(0xFF66BB6A))
            land(-0.20f, 0.00f, 0.95f, 0.70f, Color(0xFF43A047))
            land(-0.85f, 0.25f, 0.55f, 0.45f, Color(0xFF81C784))
        }
    }
}

/**
 * Rangée de pastilles de couleur, affichée dans la boîte de dialogue d'un parcours.
 * La couleur retenue s'applique à son tracé sur la carte et à sa fiche d'historique.
 *
 * Pour un parcours venu d'un fichier coloré, une pastille supplémentaire ouvre la
 * rangée : le globe, qui rend au parcours la couleur que son fichier portait.
 */
@Composable
fun ColorPaletteRow(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    showFileColorOption: Boolean = false,
    isFileColorSelected: Boolean = false,
    onFileColorSelected: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("category_color_picker"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showFileColorOption) {
            SourceColorSwatch(selected = isFileColorSelected, onClick = onFileColorSelected)
        }

        TrackStylePreferences.COLOR_PALETTE.forEach { color ->
            // Aucune pastille franche n'est sélectionnée tant que les couleurs
            // d'origine sont retenues : deux choix cochés à la fois se liraient
            // comme un bug.
            val isSelected = !isFileColorSelected && color == selectedColor
            Box(
                modifier = Modifier
                    .size(if (isSelected) 38.dp else 32.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
                    .clickable { onColorSelected(color) }
                    .testTag("color_swatch_${color}")
            )
        }
    }
}
