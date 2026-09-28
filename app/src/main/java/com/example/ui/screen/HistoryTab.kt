package com.example.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Polyline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.ui.component.CookieShape
import com.example.ui.component.LocalAppMessenger
import com.example.ui.component.MpDialog
import com.example.ui.component.MpFilledButton
import com.example.ui.component.MpSheet
import com.example.ui.component.MpSwitch
import com.example.ui.component.MpTextButton
import com.example.ui.component.ShapeBadge
import com.example.ui.component.SheetHeader
import com.example.ui.component.SunShape
import com.example.ui.component.TrackTile
import com.example.ui.viewmodel.TrackViewModel
import com.example.util.FormatUtils
import com.example.util.TrackStylePreferences
import kotlinx.coroutines.delay

/** Les trois catégories de l'historique, qui ne se recouvrent pas. */
private enum class HistoryCategory(val label: String) {
    RECORDED("Enregistrés"),
    IMPORTED("Importés"),
    MERGED("Fusionnés")
}

@Composable
fun HistoryTab(
    viewModel: TrackViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onResumeTrack: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Marges laissées par la barre d'état et la barre de navigation flottante. */
    contentPadding: PaddingValues = PaddingValues(),
    /** « Démarrer un enregistrement » (état vide) et « Voir l'enregistrement ». */
    onOpenRecording: () -> Unit = {},
    /** « Ouvrir les outils », depuis l'état vide des fusionnés. */
    onOpenTools: () -> Unit = {}
) {
    val tracks by viewModel.allTracks.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val context = LocalContext.current
    val messenger = LocalAppMessenger.current
    var selectedCategory by rememberSaveable { mutableStateOf(HistoryCategory.RECORDED) }

    // Parcours dont la feuille d'actions, la palette ou la confirmation de suppression
    // est ouverte ; null = fermée. On garde l'identifiant plutôt que le parcours : la
    // liste se recompose à chaque écriture, et la feuille doit montrer l'état à jour.
    var actionsTrackId by remember { mutableStateOf<Long?>(null) }
    var colorTrackId by remember { mutableStateOf<Long?>(null) }
    var deleteTrackId by remember { mutableStateOf<Long?>(null) }
    var refusedTrackId by remember { mutableStateOf<Long?>(null) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importTrack(
                context = context,
                uri = it,
                onSuccess = { trackId ->
                    messenger.show("Parcours importé.", Icons.Rounded.TaskAlt)
                    viewModel.selectTrack(trackId)
                    onNavigateToDetails(trackId)
                },
                onError = { error ->
                    messenger.show(error, Icons.Rounded.Error, isError = true)
                }
            )
        }
    }
    val importFile = { filePickerLauncher.launch("*/*") }

    // Trois catégories qui ne se recouvrent pas : un parcours fusionné quitte celle
    // dont il venait, sinon il apparaîtrait deux fois dans l'historique.
    //
    // Les fusions faites entre le retrait de l'onglet et son retour portent isMerged
    // à false en base : elles restent donc dans leur catégorie d'origine. Rien ne
    // permet de les reconnaître après coup, et les déplacer d'office serait pire que
    // de les laisser où l'utilisateur les a vues jusqu'ici.
    val byCategory = mapOf(
        HistoryCategory.RECORDED to tracks.filter { !it.isImported && !it.isMerged },
        HistoryCategory.IMPORTED to tracks.filter { it.isImported && !it.isMerged },
        HistoryCategory.MERGED to tracks.filter { it.isMerged }
    )
    val shown = byCategory.getValue(selectedCategory)

    // Reprise d'une trace : refusée, avec explication, tant qu'un enregistrement court.
    val resume: (Track) -> Unit = { track ->
        if (isTracking) {
            refusedTrackId = track.id
        } else {
            viewModel.resumeTrack(context, track.id) {
                messenger.show("Reprise de « ${track.name} »", Icons.Rounded.Polyline)
                onResumeTrack(track.id)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(contentPadding.calculateTopPadding()))
            Text(
                text = "Historique",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
                    .testTag("history_categories")
            ) {
                HistoryCategory.entries.forEach { category ->
                    CategoryChip(
                        label = category.label,
                        count = byCategory.getValue(category).size,
                        selected = category == selectedCategory,
                        onClick = { selectedCategory = category },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (shown.isEmpty()) {
                    CategoryEmptyState(
                        category = selectedCategory,
                        onAction = when (selectedCategory) {
                            HistoryCategory.RECORDED -> onOpenRecording
                            HistoryCategory.IMPORTED -> importFile
                            HistoryCategory.MERGED -> onOpenTools
                        },
                        modifier = Modifier.padding(bottom = contentPadding.calculateBottomPadding())
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // Le bas laisse passer le bouton d'import et la barre flottante :
                        // la dernière carte doit pouvoir remonter au-dessus des deux.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = contentPadding.calculateBottomPadding() + 96.dp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("history_list")
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.id }) { index, track ->
                            StaggeredHistoryCard(index) {
                                TrackHistoryCard(
                                    track = track,
                                    trackColor = Color(trackDisplayColor(track)),
                                    onClick = { onNavigateToDetails(track.id) },
                                    isSelectedForMap = track.isSelectedForMap,
                                    onMapSelectionToggle = { viewModel.toggleTrackSelectionForMap(track) },
                                    onColorClick = { colorTrackId = track.id },
                                    onMoreClick = { actionsTrackId = track.id }
                                )
                            }
                        }
                    }
                }
            }
        }

        ImportFab(
            onClick = importFile,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp)
        )
    }

    // ------------------------------------------------------------ Feuilles

    tracks.firstOrNull { it.id == actionsTrackId }?.let { track ->
        TrackActionsSheet(
            track = track,
            trackColor = Color(trackDisplayColor(track)),
            onDismiss = { actionsTrackId = null },
            onToggleMap = { viewModel.toggleTrackSelectionForMap(track) },
            onChangeColor = {
                actionsTrackId = null
                colorTrackId = track.id
            },
            onResume = {
                actionsTrackId = null
                resume(track)
            },
            onDelete = {
                actionsTrackId = null
                deleteTrackId = track.id
            }
        )
    }

    tracks.firstOrNull { it.id == colorTrackId }?.let { track ->
        TrackColorSheet(
            track = track,
            onDismiss = { colorTrackId = null },
            onApply = { color ->
                viewModel.setTrackColor(track, color)
                colorTrackId = null
            }
        )
    }

    // ------------------------------------------------------------ Dialogues

    tracks.firstOrNull { it.id == deleteTrackId }?.let { track ->
        DeleteTrackDialog(
            trackName = track.name,
            onDismiss = { deleteTrackId = null },
            onConfirm = {
                viewModel.deleteTrack(track.id)
                deleteTrackId = null
                messenger.show("Parcours supprimé.", Icons.Rounded.Delete)
            }
        )
    }

    tracks.firstOrNull { it.id == refusedTrackId }?.let { track ->
        ResumeRefusedDialog(
            trackName = track.name,
            onDismiss = { refusedTrackId = null },
            onOpenRecording = {
                refusedTrackId = null
                onOpenRecording()
            }
        )
    }
}

/**
 * Fait apparaître une carte de l'historique en glissant, avec un léger décalage selon
 * [index], et anime aussi son emplacement dans la liste ([LazyItemScope.animateItem]) —
 * utile lors d'une suppression ou d'un changement de catégorie, où les cartes
 * restantes glissent à leur nouvelle place plutôt que de sauter.
 *
 * Le décalage entre cartes est plafonné : au-delà d'une douzaine, attendre son tour
 * prendrait plus de temps que l'utilisateur n'en met à faire défiler jusque-là. C'est
 * une garniture, pas un ralentissement — une appli consultée parfois d'une main en
 * plein trajet ne doit pas faire patienter pour lire ses statistiques.
 *
 * **Le glissement est piloté à la main, et surtout pas confié à `AnimatedVisibility`**,
 * qui **rogne son contenu à ses propres bornes** dès qu'une transition le déplace. Un
 * contenu descendu d'un sixième de sa hauteur s'y trouve donc tranché net en bas —
 * coin arrondi et ombre compris. Mesuré image par image sur l'enregistrement d'écran
 * de l'auteur : pendant toute l'animation la carte s'arrêtait sur un bord franc avec
 * le fond juste en dessous, pas un pixel d'ombre, puis l'ombre réapparaissait d'un
 * coup à la dernière image. C'est exactement le défaut rapporté.
 *
 * Un `graphicsLayer` ne rogne rien (`clip` vaut `false` par défaut) : ce qui déborde
 * se dessine entier d'un bout à l'autre du mouvement.
 *
 * **Aucun fondu progressif ici non plus, et pour une raison distincte.** Dès qu'une
 * opacité *intermédiaire* est appliquée, Android compose l'élément hors écran dans un
 * tampon **aux dimensions exactes de la carte** : tout ce qui déborde se retrouve
 * tranché. Les cartes de la refonte ne portent plus d'ombre, mais la règle tient pour
 * qui leur en redonnerait une. Le fondu qu'`animateItem()` applique de lui-même est
 * coupé pour cette raison (`fadeInSpec` et `fadeOutSpec` ne sont pas nuls par défaut,
 * ce que son nom ne laisse pas deviner). Seule l'opacité **binaire** ci-dessous est
 * permise : à zéro, rien n'est dessiné, il n'y a donc rien à trancher.
 *
 * Ne réintroduire ni fondu progressif ni transition d'`AnimatedVisibility` sur un
 * élément qui porte une ombre sans lui retirer d'abord son élévation.
 */
@Composable
private fun LazyItemScope.StaggeredHistoryCard(index: Int, content: @Composable () -> Unit) {
    // 1 = carte encore décalée vers le bas, 0 = en place.
    val slide = remember { Animatable(1f) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 12) * 30L)
        visible = true
        slide.animateTo(0f, tween(260))
    }
    Box(
        modifier = Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .graphicsLayer {
                translationY = slide.value * 24.dp.toPx()
                // Tout ou rien : la carte n'est pas dessinée avant son tour. Une
                // valeur intermédiaire trancherait son ombre (voir ci-dessus).
                alpha = if (visible) 1f else 0f
            }
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

/**
 * Puce de catégorie : pleine et arrondie en pilule quand elle est choisie, en
 * rectangle doux sinon — le changement de rayon est l'animation de la maquette.
 */
@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val radius by animateDpAsState(if (selected) 24.dp else 10.dp, spring(0.6f, 800f), label = "chip_radius")
    val container by animateColorAsState(if (selected) colors.primary else colors.surfaceContainer, label = "chip_bg")
    val content by animateColorAsState(if (selected) colors.onPrimary else colors.onSurfaceVariant, label = "chip_fg")
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(radius))
            .background(container)
            .clickable(onClick = onClick)
            .testTag("history_category_$label")
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) colors.onPrimary else colors.surfaceContainerHighest)
                .padding(horizontal = 6.dp)
        ) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) colors.primary else colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Carte d'un parcours dans l'historique : tuile colorée, nom, date, trois chiffres,
 * puis l'œil qui l'affiche sur la carte et le menu d'actions.
 *
 * La tuile colorée ouvre toujours la palette du parcours, comme la pastille qu'elle
 * remplace ; la feuille d'actions y mène aussi, pour qui ne devinerait pas le geste.
 */
@Composable
fun TrackHistoryCard(
    track: Track,
    onClick: () -> Unit,
    isSelectedForMap: Boolean = false,
    onMapSelectionToggle: (Boolean) -> Unit = {},
    /** Ouvre la palette de ce parcours ; null rend la tuile inerte. */
    onColorClick: (() -> Unit)? = null,
    /** Ouvre la feuille d'actions ; null masque le bouton. */
    onMoreClick: (() -> Unit)? = null,
    /** Couleur d'affichage de ce parcours précis. */
    trackColor: Color = Color(
        TrackStylePreferences.defaultColorFor(track.isImported, track.isMerged)
    )
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 14.dp, bottom = 14.dp)
            .testTag("track_card_${track.id}")
    ) {
        TrackTile(
            color = trackColor,
            modifier = if (onColorClick != null) {
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onColorClick)
                    .testTag("track_color_button_${track.id}")
            } else Modifier
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Un parcours qui porte encore la couleur de son fichier le dit : c'est
                // la seule origine possible d'une couleur de fichier ici.
                if (track.sourceColor != null && track.displayColor == null) {
                    OutlinedTag("Google Earth")
                }
            }
            Text(
                text = trackSubtitle(track),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                CardStat(Icons.Rounded.Straighten, FormatUtils.formatDistanceShort(track.totalDistance))
                CardStat(Icons.Rounded.Schedule, FormatUtils.formatDurationShort(track.duration))
                CardStat(Icons.Rounded.TrendingUp, FormatUtils.formatElevationShort(track.elevationGain))
            }
        }

        val eyeBg by animateColorAsState(
            if (isSelectedForMap) colors.primaryContainer else colors.surfaceContainer,
            label = "eye_bg"
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(eyeBg)
                .clickable { onMapSelectionToggle(!isSelectedForMap) }
                .testTag("map_toggle_${track.id}")
        ) {
            Icon(
                imageVector = if (isSelectedForMap) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = if (isSelectedForMap) "Masquer de la carte" else "Afficher sur la carte",
                tint = if (isSelectedForMap) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        if (onMoreClick != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 28.dp, height = 44.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onMoreClick)
                    .testTag("track_actions_${track.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Actions",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** Date, ou provenance pour un parcours importé ou fusionné. */
private fun trackSubtitle(track: Track): String = when {
    track.isMerged -> "Fusionné · " + FormatUtils.formatShortDate(track.startTime)
    track.isImported -> "Importé · " + FormatUtils.formatShortDate(track.startTime)
    else -> FormatUtils.formatDayDate(track.startTime)
}

@Composable
private fun CardStat(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun OutlinedTag(text: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(20.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Bouton flottant « Importer GPX / KML », au-dessus de la barre de navigation. */
@Composable
private fun ImportFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier.testTag("import_button")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .height(60.dp)
                .padding(start = 18.dp, end = 22.dp)
        ) {
            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(24.dp))
            Text("Importer GPX / KML", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

// --------------------------------------------------------------------- États vides

/**
 * État vide d'une catégorie : une illustration sur forme expressive, un titre, une
 * phrase, et l'action qui remplit la catégorie — elle ne se trouve pas toujours dans
 * l'historique (enregistrer, fusionner), rien ne le laisserait deviner.
 */
@Composable
private fun CategoryEmptyState(
    category: HistoryCategory,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    data class Empty(
        val icon: ImageVector, val bg: Color, val fg: Color, val shape: Shape,
        val title: String, val body: String, val button: String, val buttonIcon: ImageVector
    )
    val e = when (category) {
        HistoryCategory.RECORDED -> Empty(
            Icons.Rounded.Hiking, colors.primaryContainer, colors.onPrimaryContainer, CookieShape,
            "Votre première sortie vous attend",
            "Lancez un enregistrement : distance, vitesse et dénivelé s'afficheront ici dès votre retour.",
            "Démarrer un enregistrement", Icons.Rounded.RadioButtonChecked
        )
        HistoryCategory.IMPORTED -> Empty(
            Icons.Rounded.UploadFile, colors.tertiaryContainer, colors.onTertiaryContainer, SunShape,
            "Rien d'importé pour l'instant",
            "Ajoutez des fichiers GPX ou KML : anciennes sorties, traces partagées ou dessinées dans Google Earth.",
            "Importer un fichier", Icons.Rounded.UploadFile
        )
        HistoryCategory.MERGED -> Empty(
            Icons.Rounded.Merge, colors.secondaryContainer, colors.onSecondaryContainer, RoundedCornerShape(44.dp),
            "Aucune fusion",
            "Réunissez plusieurs sorties, par exemple les étapes d'un week-end, en un seul parcours.",
            "Ouvrir les outils", Icons.Rounded.Handyman
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp)
            .testTag("history_empty_${category.name.lowercase()}")
    ) {
        ShapeBadge(
            icon = e.icon,
            containerColor = e.bg,
            contentColor = e.fg,
            shape = e.shape,
            size = 168.dp,
            iconSize = 72.dp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = e.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = null),
            color = colors.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = e.body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(22.dp))
        MpFilledButton(
            text = e.button,
            icon = e.buttonIcon,
            onClick = onAction,
            height = 52.dp,
            modifier = Modifier.testTag("empty_state_action_button")
        )
    }
}

/**
 * État vide de la catégorie « Importés », avec son bouton d'import. Gardé public pour
 * le test de capture d'écran hérité (`GreetingScreenshotTest`).
 */
@Composable
fun EmptyHistoryState(onImportClick: () -> Unit) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        CategoryEmptyState(HistoryCategory.IMPORTED, onImportClick)
    }
}

// ------------------------------------------------------------------ Feuilles

/** Feuille d'actions d'un parcours : carte, couleur, reprise, suppression. */
@Composable
private fun TrackActionsSheet(
    track: Track,
    trackColor: Color,
    onDismiss: () -> Unit,
    onToggleMap: () -> Unit,
    onChangeColor: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    MpSheet(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrackTile(color = trackColor, size = 48.dp, corner = 16.dp, iconSize = 26.dp)
            Column {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${FormatUtils.formatDistanceShort(track.totalDistance)} · ${FormatUtils.formatDurationShort(track.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.clip(RoundedCornerShape(24.dp))
        ) {
            SheetRow(Icons.Rounded.Visibility, "Afficher sur la carte", onClick = onToggleMap, testTag = "action_toggle_map") {
                MpSwitch(checked = track.isSelectedForMap, onCheckedChange = { onToggleMap() })
            }
            SheetRow(Icons.Rounded.Palette, "Changer la couleur", onClick = onChangeColor, testTag = "action_change_color") {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(trackColor)
                )
            }
            SheetRow(Icons.Rounded.Polyline, "Reprendre la trace", onClick = onResume, testTag = "resume_track_card_button_${track.id}")
            SheetRow(
                Icons.Rounded.Delete, "Supprimer", onClick = onDelete,
                color = MaterialTheme.colorScheme.error,
                testTag = "delete_track_button_${track.id}"
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/**
 * Palette d'un parcours.
 *
 * Le choix n'est appliqué qu'à « Appliquer » : on essaie une couleur sur l'aperçu sans
 * repeindre le tracé à chaque pastille touchée.
 *
 * Un parcours dont le fichier portait une couleur peut la retrouver : « Garder la
 * couleur Google Earth » remet `displayColor` à null, ce qui est exactement « couleur
 * d'origine » (voir TrackStylePreferences.resolveTrackColor). La proposer sur un GPX,
 * ou sur un enregistrement, serait une option qui ne fait rien : elle n'apparaît pas.
 */
@Composable
private fun TrackColorSheet(
    track: Track,
    onDismiss: () -> Unit,
    onApply: (Int?) -> Unit
) {
    val hasFileColor = track.sourceColor != null
    var keepFileColor by remember { mutableStateOf(hasFileColor && track.displayColor == null) }
    var pending by remember { mutableIntStateOf(trackDisplayColor(track)) }
    val previewColor = if (keepFileColor) track.sourceColor ?: pending else pending

    MpSheet(onDismissRequest = onDismiss) {
        SheetHeader("Couleur du tracé", track.name)
        Spacer(modifier = Modifier.height(14.dp))
        TrackColorPreview(color = Color(previewColor))

        if (hasFileColor) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { keepFileColor = !keepFileColor }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("color_swatch_from_file")
            ) {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Garder la couleur Google Earth", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Définie dans le fichier KML d'origine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MpSwitch(checked = keepFileColor, onCheckedChange = { keepFileColor = it })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // Les pastilles s'estompent tant que la couleur du fichier est gardée : deux
        // choix cochés à la fois se liraient comme un bug.
        val dim = if (keepFileColor) 0.38f else 1f
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = dim }
                .testTag("category_color_picker")
        ) {
            TrackStylePreferences.COLOR_PALETTE.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    row.forEach { color ->
                        ColorSwatch(
                            color = Color(color),
                            selected = !keepFileColor && color == pending,
                            enabled = !keepFileColor,
                            onClick = { pending = color },
                            modifier = Modifier.testTag("color_swatch_$color")
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            MpTextButton("Annuler", onDismiss)
            MpFilledButton(
                text = "Appliquer",
                onClick = { onApply(if (keepFileColor) null else pending) },
                modifier = Modifier.testTag("color_apply_button")
            )
        }
    }
}

/** Pastille de couleur : ronde au repos, carré arrondi cochée quand elle est choisie. */
@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val radius by animateDpAsState(if (selected) 16.dp else 26.dp, spring(0.6f, 800f), label = "swatch_radius")
    val scale by animateFloatAsState(if (selected) 1f else 0.86f, spring(0.6f, 800f), label = "swatch_scale")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(radius))
            .background(color)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = "Couleur choisie", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** Aperçu de la couleur choisie : un tracé ondulé sur un fond de carte neutre. */
@Composable
private fun TrackColorPreview(color: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.03f, h * 0.68f)
            cubicTo(w * 0.22f, h * 0.30f, w * 0.38f, h * 0.95f, w * 0.55f, h * 0.55f)
            cubicTo(w * 0.70f, h * 0.20f, w * 0.85f, h * 0.30f, w * 0.97f, h * 0.36f)
        }
        drawPath(path, color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ----------------------------------------------------------------- Dialogues

@Composable
fun DeleteTrackDialog(trackName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    MpDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeBadge(
                icon = Icons.Rounded.DeleteForever,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(22.dp)
            )
        },
        title = "Supprimer ce parcours ?",
        body = "« $trackName » sera effacé définitivement de votre téléphone. Cette action est irréversible.",
        dismissLabel = "Annuler",
        confirmLabel = "Supprimer",
        onConfirm = onConfirm,
        confirmContainerColor = MaterialTheme.colorScheme.error,
        confirmContentColor = MaterialTheme.colorScheme.onError,
        confirmTestTag = "dialog_delete_confirm_button"
    )
}

/** Reprise refusée : on ne peut enregistrer qu'une trace à la fois. */
@Composable
fun ResumeRefusedDialog(trackName: String, onDismiss: () -> Unit, onOpenRecording: () -> Unit) {
    MpDialog(
        onDismissRequest = onDismiss,
        icon = {
            ShapeBadge(
                icon = Icons.Rounded.RadioButtonChecked,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = CircleShape
            )
        },
        title = "Un enregistrement est en cours",
        body = "Arrêtez-le avant de reprendre « $trackName ». On ne peut enregistrer qu'une trace à la fois.",
        dismissLabel = "OK",
        confirmLabel = "Voir l'enregistrement",
        onConfirm = onOpenRecording
    )
}
