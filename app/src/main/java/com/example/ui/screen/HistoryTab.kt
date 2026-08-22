package com.example.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
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

    // Couleur d'affichage de chaque catégorie, modifiable par appui long sur l'onglet.
    var recordedColor by remember { mutableStateOf(TrackStylePreferences.getRecordedColor(context)) }
    var importedColor by remember { mutableStateOf(TrackStylePreferences.getImportedColor(context)) }
    var mergedColor by remember { mutableStateOf(TrackStylePreferences.getMergedColor(context)) }

    // Les parcours importés gardent-ils la couleur de leur fichier d'origine ?
    var importedColorFromFile by remember {
        mutableStateOf(TrackStylePreferences.isImportedColorFromFile(context))
    }

    // Catégorie dont la palette est ouverte : 0, 1 ou 2 comme les onglets ; null = fermée.
    var colorPickerCategory by remember { mutableStateOf<Int?>(null) }

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
                    // Appui long sur un onglet : choix de la couleur de la catégorie.
                    //
                    // Le geste reste posé sur le Text, donc sensible sur les seules
                    // lettres du libellé. L'élargir à tout l'onglet par un Box en
                    // fillMaxWidth a été essayé et retiré : dans le créneau `text` d'un
                    // Tab, cette largeur est réclamée sur la rangée entière, le premier
                    // onglet la prend toute, et le second sort de l'écran — que la
                    // rangée rogne. L'onglet « Importés » disparaissait purement et
                    // simplement. Le réglage est de toute façon atteignable depuis
                    // l'écran des paramètres, qui est sa vraie place.
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "Enregistrés (${recordedTracks.size})",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { selectedTab = 0 },
                                        onLongPress = { colorPickerCategory = 0 }
                                    )
                                }
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
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { selectedTab = 1 },
                                        onLongPress = { colorPickerCategory = 1 }
                                    )
                                }
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
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = { selectedTab = 2 },
                                        onLongPress = { colorPickerCategory = 2 }
                                    )
                                }
                            )
                        }
                    )
                }
            }

            // Le geste serait indevinable sans un rappel discret.
            item {
                Text(
                    text = "Appui long sur une catégorie pour changer sa couleur — " +
                            "ou garder celles des fichiers importés",
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
                                trackColor = Color(recordedColor),
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
                                trackColor = Color(
                                    TrackStylePreferences.resolveImportedColor(
                                        fromFile = importedColorFromFile,
                                        sourceColor = track.sourceColor,
                                        fallback = importedColor
                                    )
                                ),
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
                                // Un parcours fusionné à partir de fichiers garde les
                                // couleurs de ceux-ci quand le réglage le demande ; sinon
                                // il prend la couleur de sa propre catégorie.
                                trackColor = Color(
                                    TrackStylePreferences.resolveImportedColor(
                                        fromFile = importedColorFromFile,
                                        sourceColor = track.sourceColor,
                                        fallback = mergedColor
                                    )
                                ),
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

    // Palette de couleurs, ouverte par appui long sur un onglet de catégorie
    colorPickerCategory?.let { category ->
        // Les parcours enregistrés ne viennent d'aucun fichier : eux seuls n'ont pas
        // de couleur d'origine à conserver, et la pastille en dégradé n'a donc rien
        // à leur proposer.
        val comesFromFiles = category != 0
        AlertDialog(
            onDismissRequest = { colorPickerCategory = null },
            title = {
                Text(
                    text = when (category) {
                        0 -> "Couleur des parcours enregistrés"
                        1 -> "Couleur des parcours importés"
                        else -> "Couleur des parcours fusionnés"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    ColorPaletteRow(
                        selectedColor = when (category) {
                            0 -> recordedColor
                            1 -> importedColor
                            else -> mergedColor
                        },
                        onColorSelected = { color ->
                            when (category) {
                                0 -> {
                                    recordedColor = color
                                    TrackStylePreferences.setRecordedColor(context, color)
                                }
                                1 -> {
                                    importedColor = color
                                    TrackStylePreferences.setImportedColor(context, color)
                                }
                                else -> {
                                    mergedColor = color
                                    TrackStylePreferences.setMergedColor(context, color)
                                }
                            }
                            if (comesFromFiles) {
                                // Choisir une couleur franche, c'est renoncer à celle
                                // du fichier : sans quoi la pastille se sélectionnerait
                                // sans que le tracé change à l'écran.
                                importedColorFromFile = false
                                TrackStylePreferences.setImportedColorFromFile(context, false)
                            }
                        },
                        showFileColorOption = comesFromFiles,
                        isFileColorSelected = importedColorFromFile,
                        onFileColorSelected = {
                            importedColorFromFile = true
                            TrackStylePreferences.setImportedColorFromFile(context, true)
                        }
                    )

                    if (comesFromFiles) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (importedColorFromFile) {
                                "Chaque parcours garde la couleur de son fichier. " +
                                    "Ceux qui n'en portent pas — les GPX, notamment — " +
                                    "utilisent la couleur choisie ici."
                            } else {
                                "La première pastille garde les couleurs d'origine des " +
                                    "fichiers, telles que Google Earth les a enregistrées."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { colorPickerCategory = null },
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
 * Fait apparaître une carte de l'historique en fondu-glissé, avec un léger décalage
 * selon [index], et anime aussi son emplacement dans la liste ([LazyItemScope.animateItem]) —
 * utile lors d'une suppression ou d'un changement d'onglet, où les cartes restantes
 * glissent à leur nouvelle place plutôt que de sauter.
 *
 * Le décalage entre cartes est plafonné : au-delà d'une douzaine, attendre son tour
 * prendrait plus de temps que l'utilisateur n'en met à faire défiler jusque-là. C'est
 * une garniture, pas un ralentissement — une appli consultée parfois d'une main en
 * plein trajet ne doit pas faire patienter pour lire ses statistiques.
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
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 6 },
        modifier = Modifier.animateItem()
    ) {
        content()
    }
}

@Composable
fun TrackHistoryCard(
    track: Track,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onResumeClick: (() -> Unit)? = null,
    showMapSelection: Boolean = false,
    isSelectedForMap: Boolean = false,
    onMapSelectionToggle: (Boolean) -> Unit = {},
    /** Couleur de la catégorie, choisie par l'utilisateur dans l'historique. */
    trackColor: Color = Color(
        if (track.isImported) TrackStylePreferences.DEFAULT_COLOR_IMPORTED
        else TrackStylePreferences.DEFAULT_COLOR_RECORDED
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
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = trackColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = track.activityType,
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
 * Rangée de pastilles de couleur, affichée dans la boîte de dialogue ouverte par
 * un appui long sur une catégorie. La couleur retenue s'applique aux tracés sur
 * la carte et aux fiches de l'historique.
 *
 * Pour les parcours importés, une pastille supplémentaire ouvre la rangée : elle ne
 * porte pas une couleur mais un dégradé, parce qu'elle en désigne autant qu'il y a
 * de fichiers. C'est le choix « garder les couleurs d'origine ».
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
            Box(
                modifier = Modifier
                    .size(if (isFileColorSelected) 38.dp else 32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFFD32F2F),
                                Color(0xFFFF9800),
                                Color(0xFFFFEB3B),
                                Color(0xFF39FF14),
                                Color(0xFF2196F3),
                                Color(0xFF8B5CF6),
                                Color(0xFFD32F2F)
                            )
                        )
                    )
                    .border(
                        width = if (isFileColorSelected) 3.dp else 1.dp,
                        color = if (isFileColorSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
                    .clickable { onFileColorSelected() }
                    .testTag("color_swatch_from_file")
            )
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
